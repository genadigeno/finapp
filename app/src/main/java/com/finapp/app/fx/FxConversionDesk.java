package com.finapp.app.fx;

import com.finapp.fx.ConversionRefusal;
import com.finapp.fx.CoverDispatchNudge;
import com.finapp.fx.FxConversion;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.FxTradeId;
import com.finapp.fx.TradeStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The customer's conversion doors (`P9-TSK-009`; PHASE_9_PLAN.md section 9): convert by quote id
 * - keyed {@code fx.convert:<actorType>:<actorId>}, synchronous and final, ONE transaction (T-a)
 * with no provider call - and read a conversion. A refusal rolls the transaction back, the key
 * unburned and the quote reusable; a quote found lapsed commits its expiry and the claim's failed
 * outcome, so the key's replay answers {@code 409 fx.QuoteExpired}. After a booking commits, the
 * cover dispatcher is nudged - a hint; the DISPATCHED row is the guarantee.
 */
public final class FxConversionDesk {

    static final String CONVERT_SCOPE = "fx.convert:";

    /** A conversion as its owner sees it - the executed (customer) rate, never the provider's. */
    public record FxTradeView(
            String id,
            String quoteId,
            String status,
            String sourceCurrency,
            String destinationCurrency,
            String fixedSide,
            String sourceAmount,
            String destinationAmount,
            String executedRate,
            String bookedAt,
            String bookedOn) {}

    private final FxConversion conversion;
    private final IdentityStore<Connection> identities;
    private final IdempotentExecutor executor;
    private final TransactionRunner transactions;
    private final FxQuoteMetrics metrics;
    private final CoverDispatchNudge nudge;

    public FxConversionDesk(
            FxConversion conversion,
            IdentityStore<Connection> identities,
            IdempotentExecutor executor,
            TransactionRunner transactions,
            FxQuoteMetrics metrics,
            CoverDispatchNudge nudge) {
        this.conversion = Objects.requireNonNull(conversion, "conversion must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.nudge = Objects.requireNonNull(nudge, "nudge must not be null");
    }

    public FxTradeView convert(Session current, String idempotencyKey, FxQuoteController.ConversionRequestBody body) {
        Objects.requireNonNull(current, "current must not be null");
        FxQuoteId quoteId = quoteId(body.quoteId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(CONVERT_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        AtomicReference<FxConversion.Outcome> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                RequestFingerprint fingerprint = RequestFingerprint.sha256(
                        ("fx.convert|" + party + "|" + quoteId.value()).getBytes(StandardCharsets.UTF_8));
                return executor.execute(unitOfWork, key, fingerprint, uow -> {
                    FxConversion.Outcome done = conversion.convert(uow, quoteId, party, actor, correlation);
                    acted.set(done);
                    return switch (done) {
                        case FxConversion.Booked booked -> CommandResult.succeeded(stored(view(booked.trade())));
                        case FxConversion.Expired expired -> CommandResult.failed(stored(FxErrorCode.QUOTE_EXPIRED));
                    };
                });
            });
        } catch (ConversionRefusal.Refused refused) {
            throw refusal(refused.refusal().code());
        }
        if (outcome.executed()) {
            switch (acted.get()) {
                case FxConversion.Booked booked -> {
                    nudge.nudge(booked.coverId());
                    metrics.traded(booked.pair(), booked.residualMinor());
                    metrics.closed(booked.pair(), "accepted", 1);
                }
                case FxConversion.Expired expired -> metrics.closed(expired.pair(), "expired", 1);
                case null -> { }
            }
        }
        return replayed(outcome.body().orElseThrow());
    }

    public FxTradeView read(Session current, String rawId) {
        FxTradeId id;
        try {
            id = FxTradeId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw tradeNotFound();
        }
        return transactions.inTransaction(unitOfWork -> conversion.trade(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(FxConversionDesk::view)
                .orElseThrow(FxConversionDesk::tradeNotFound);
    }

    // -----------------------------------------------------------------

    private static FxQuoteId quoteId(String raw) {
        try {
            return FxQuoteId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw refusal(FxErrorCode.QUOTE_NOT_FOUND);
        }
    }

    static FxTradeView view(TradeStore.TradeRow trade) {
        return new FxTradeView(
                trade.id().value().toString(),
                trade.quoteId().value().toString(),
                trade.status().name(),
                trade.customerSource().currency().code(),
                trade.customerDestination().currency().code(),
                trade.fixedSide().name(),
                trade.customerSource().toBigDecimal().toPlainString(),
                trade.customerDestination().toBigDecimal().toPlainString(),
                trade.executedRate().toPlainString(),
                trade.bookedAt().toString(),
                trade.bookedOn().toString());
    }

    /** The stored response of record: {@code OK|<view>} or {@code ERR|<code name>}. */
    private static StoredResponse stored(FxTradeView view) {
        String body = String.join("|", "OK", view.id(), view.quoteId(), view.status(), view.sourceCurrency(),
                view.destinationCurrency(), view.fixedSide(), view.sourceAmount(), view.destinationAmount(),
                view.executedRate(), view.bookedAt(), view.bookedOn());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static StoredResponse stored(FxErrorCode code) {
        return StoredResponse.of(("ERR|" + code.name()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static FxTradeView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        if (fields[0].equals("ERR")) {
            throw refusal(FxErrorCode.valueOf(fields[1]));
        }
        return new FxTradeView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8],
                fields[9], fields[10], fields[11]);
    }

    private static ApiException refusal(FxErrorCode code) {
        return new ApiException(code, "The conversion was refused");
    }

    private static ApiException tradeNotFound() {
        return new ApiException(FxErrorCode.TRADE_NOT_FOUND, "No conversion matches the requested identifier",
                "no such conversion.");
    }

    /** {@code Session -> Identity -> Party}: the {@code ProfileService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an FX command runs inside a correlation scope"))
                .correlationId();
    }
}
