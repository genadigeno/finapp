package com.finapp.app.fx;

import com.finapp.app.api.DecimalText;
import com.finapp.fx.AvailabilitySubject;
import com.finapp.fx.FixedSide;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxErrorCode;
import com.finapp.fx.FxQuoteId;
import com.finapp.fx.PolicyPair;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.PricingPurpose;
import com.finapp.fx.QuoteIssuance;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.fx.QuoteRefusal;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.ReferencePair;
import com.finapp.fx.ReferenceSourceDeclaration;
import com.finapp.fx.TransactionRunner;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The customer's FX quote doors (`P9-TSK-008`; PHASE_9_PLAN.md section 9). Creation is keyed and
 * runs three steps that never share a connection: Tx1 claims the key and runs
 * {@link QuoteIssuance#claim} beside it - a refusal is recorded on the claim and answered after
 * the commit, before any provider call; the wire ({@link QuoteIssuance#source}) holds no
 * connection; Tx2 runs {@link QuoteIssuance#issue} and completes the claim in the same
 * transaction. A replay answers the recorded outcome byte for byte; a flight in progress answers
 * {@code 409 platform.IdempotencyInProgress}, so ten same-key requests make one provider call.
 */
public final class FxQuoteDesk {

    static final String QUOTE_SCOPE = "fx.quote:";
    static final String CANCEL_SCOPE = "fx.quote-cancel:";

    /** A quote as its owner sees it - the customer rate, never the provider's. */
    public record FxQuoteView(
            String id,
            String status,
            String sourceCurrency,
            String destinationCurrency,
            String fixedSide,
            String sourceAmount,
            String destinationAmount,
            String customerRate,
            String disclosedMarginOverMid,
            String issuedAt,
            String expiresAt) {}

    /** One offered pair: availability and the fixed leg's bounds per side. */
    public record OfferedPair(
            String sourceCurrency,
            String destinationCurrency,
            String marketBase,
            boolean available,
            String sourceMinimum,
            String sourceMaximum,
            String destinationMinimum,
            String destinationMaximum) {}

    /** The offered pairs, bounded at 100. */
    public record OfferedPairs(List<OfferedPair> pairs, boolean truncated) {}

    private final QuoteIssuance issuance;
    private final QuoteLifecycle lifecycle;
    private final PricingPolicyStore policies;
    private final FxAvailability availability;
    private final IdentityStore<Connection> identities;
    private final IdempotentExecutor executor;
    private final TransactionRunner transactions;
    private final FxQuoteMetrics metrics;
    private final Clock clock;

    public FxQuoteDesk(
            QuoteIssuance issuance,
            QuoteLifecycle lifecycle,
            PricingPolicyStore policies,
            FxAvailability availability,
            IdentityStore<Connection> identities,
            IdempotentExecutor executor,
            TransactionRunner transactions,
            FxQuoteMetrics metrics,
            Clock clock) {
        this.issuance = Objects.requireNonNull(issuance, "issuance must not be null");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.availability = Objects.requireNonNull(availability, "availability must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------ request a quote

    public FxQuoteView request(Session current, String idempotencyKey, FxQuoteController.QuoteRequestBody body) {
        Objects.requireNonNull(current, "current must not be null");
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Parsed parsed = parse(body);
        String pair = parsed.source().code() + "-" + parsed.destination().code();
        IdempotencyKey key = new IdempotencyKey(QUOTE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);

        record Begun(IdempotentExecutor.BeginOutcome outcome, QuoteIssuance.Claimed claimed, QuoteRefusal refusal) {}
        Begun begun = transactions.inTransaction(unitOfWork -> {
            UUID party = partyOf(unitOfWork, current);
            RequestFingerprint fingerprint = RequestFingerprint.sha256(
                    ("fx.quote|" + party + "|" + PricingPurpose.CONVERSION + "|" + pair + "|" + parsed.fixedSide()
                                    + "|" + parsed.amount().minorUnits() + "|" + parsed.amount().scale())
                            .getBytes(StandardCharsets.UTF_8));
            AtomicReference<QuoteIssuance.Claimed> claimed = new AtomicReference<>();
            AtomicReference<QuoteRefusal> refused = new AtomicReference<>();
            IdempotentExecutor.BeginOutcome outcome = executor.begin(unitOfWork, key, fingerprint, claimedUnit -> {
                try {
                    claimed.set(issuance.claim(claimedUnit, key.scope() + "|" + key.key(),
                            new QuoteIssuance.QuoteRequest(party, parsed.source(), parsed.destination(),
                                    parsed.fixedSide(), parsed.amount()),
                            correlation));
                } catch (QuoteRefusal.Refused refusal) {
                    refused.set(refusal.refusal());
                }
                return new byte[] {1};
            });
            if (refused.get() != null) {
                // Recorded on the claim before any provider call - replays answer the same refusal.
                executor.complete(unitOfWork, key, false, stored(refused.get()));
            }
            return new Begun(outcome, claimed.get(), refused.get());
        });
        if (begun.outcome().replay().isPresent()) {
            return replayed(begun.outcome().replay().get());
        }
        if (begun.refusal() != null) {
            throw refusedAfterCommit(pair, begun.refusal());
        }

        QuoteIssuance.Sourced sourced = issuance.source(begun.claimed());

        record Outcome(FxQuoteView view, QuoteRefusal refusal) {}
        Outcome outcome = transactions.inTransaction(unitOfWork -> {
            try {
                QuoteIssuance.Issued issued = issuance.issue(unitOfWork, begun.claimed(), sourced, actor, correlation);
                FxQuoteView view = view(issued.quote());
                executor.complete(unitOfWork, key, true, stored(view));
                return new Outcome(view, null);
            } catch (QuoteRefusal.Refused refusal) {
                executor.complete(unitOfWork, key, false, stored(refusal.refusal()));
                return new Outcome(null, refusal.refusal());
            }
        });
        if (outcome.refusal() != null) {
            throw refusedAfterCommit(pair, outcome.refusal());
        }
        metrics.quoted(pair, "issued");
        return outcome.view();
    }

    // ------------------------------------------------------------------ read and cancel

    public FxQuoteView read(Session current, String rawId) {
        FxQuoteId id = quoteId(rawId);
        return transactions.inTransaction(unitOfWork -> lifecycle.read(unitOfWork, id, partyOf(unitOfWork, current)))
                .map(FxQuoteDesk::view)
                .orElseThrow(FxQuoteDesk::notFound);
    }

    public FxQuoteView cancel(Session current, String rawId, String idempotencyKey) {
        FxQuoteId id = quoteId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(CANCEL_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("fx.quote-cancel|" + id.value()).getBytes(StandardCharsets.UTF_8));
        AtomicReference<String> pairOfCancelled = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                QuoteStore.QuoteRow cancelled = lifecycle.cancel(uow, id, partyOf(uow, current), actor, Instant.now(clock), correlation);
                pairOfCancelled.set(cancelled.customerSource().currency().code() + "-"
                        + cancelled.customerDestination().currency().code());
                return CommandResult.succeeded(stored(view(cancelled)));
            }));
        } catch (QuoteLifecycle.QuoteNotFound absent) {
            throw notFound();
        } catch (QuoteLifecycle.QuoteNotCancellable closed) {
            throw new ApiException(FxErrorCode.QUOTE_NOT_CANCELLABLE, "The quote is no longer live",
                    "the quote can no longer be cancelled.");
        }
        if (outcome.executed() && pairOfCancelled.get() != null) {
            metrics.closed(pairOfCancelled.get(), "cancelled", 1);
        }
        return replayed(StoredResponse.of(outcome.body().orElseThrow(), "text/plain"));
    }

    // ------------------------------------------------------------------ pairs

    public OfferedPairs pairs() {
        return transactions.inTransaction(unitOfWork -> {
            Optional<PricingPolicyStore.VersionView> active = policies.active(unitOfWork);
            if (active.isEmpty()) {
                return new OfferedPairs(List.of(), false);
            }
            List<OfferedPair> pairs = active.get().pairs().stream()
                    .filter(pair -> pair.purpose() == PricingPurpose.CONVERSION)
                    .limit(100)
                    .map(pair -> pairView(unitOfWork, pair))
                    .toList();
            return new OfferedPairs(pairs, false);
        });
    }

    private OfferedPair pairView(Connection unitOfWork, PolicyPair pair) {
        CurrencyCode source = pair.pricing().source();
        CurrencyCode destination = pair.pricing().destination();
        ReferencePair forward = new ReferencePair(source, destination);
        String marketBase = ReferenceSourceDeclaration.declares(forward) ? source.code() : destination.code();
        boolean available = availability.isAvailable(unitOfWork, AvailabilitySubject.pair(source.code() + "-" + destination.code()));
        return new OfferedPair(
                source.code(), destination.code(), marketBase, available,
                pair.pricing().sourceBounds().minimum().toBigDecimal().toPlainString(),
                pair.pricing().sourceBounds().maximum().toBigDecimal().toPlainString(),
                pair.pricing().destinationBounds().minimum().toBigDecimal().toPlainString(),
                pair.pricing().destinationBounds().maximum().toBigDecimal().toPlainString());
    }

    // ------------------------------------------------------------------ plumbing

    /** The request in the domain's words; a malformed field is the caller's own defect (422). */
    private record Parsed(CurrencyCode source, CurrencyCode destination, FixedSide fixedSide, Money amount) {}

    private static Parsed parse(FxQuoteController.QuoteRequestBody body) {
        CurrencyCode source;
        CurrencyCode destination;
        try {
            source = CurrencyCode.of(body.sourceCurrency());
            destination = CurrencyCode.of(body.destinationCurrency());
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(FxErrorCode.PAIR_NOT_OFFERED, "The currency pair is not offered", "the pair is not offered.");
        }
        FixedSide fixedSide;
        try {
            fixedSide = FixedSide.valueOf(body.fixedSide());
        } catch (IllegalArgumentException unknown) {
            throw invalid("fixedSide must be FIXED_SOURCE or FIXED_DESTINATION");
        }
        Money amount;
        try {
            // The shape first (the Phase 9 to 10 transition gate): an exponent never reaches Money.of's rescale.
            BigDecimal value = DecimalText.parse(body.amount());
            amount = Money.of(value, fixedSide == FixedSide.FIXED_SOURCE ? source : destination);
        } catch (RuntimeException malformed) {
            throw invalid("amount must be an exact decimal at the fixed currency's minor units");
        }
        if (!amount.isPositive()) {
            throw invalid("amount must be positive");
        }
        return new Parsed(source, destination, fixedSide, amount);
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The quote request was not valid", detail);
    }

    private ApiException refusedAfterCommit(String pair, QuoteRefusal refusal) {
        if (refusal.metricOutcome() != null) {
            metrics.quoted(pair, refusal.metricOutcome());
        }
        return refused(refusal.code());
    }

    private static ApiException refused(FxErrorCode code) {
        return new ApiException(code, "The quote was refused");
    }

    private static ApiException notFound() {
        return new ApiException(FxErrorCode.QUOTE_NOT_FOUND, "No quote matches the requested identifier",
                "no such quote.");
    }

    /** The stored response of record: {@code OK|<view>} or {@code ERR|<code name>}. */
    private static StoredResponse stored(FxQuoteView view) {
        String body = String.join("|", "OK", view.id(), view.status(), view.sourceCurrency(), view.destinationCurrency(),
                view.fixedSide(), view.sourceAmount(), view.destinationAmount(), view.customerRate(),
                view.disclosedMarginOverMid(), view.issuedAt(), view.expiresAt());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static StoredResponse stored(QuoteRefusal refusal) {
        return StoredResponse.of(("ERR|" + refusal.code().name()).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    /** A recorded outcome, byte for byte: the view, or the refusal re-thrown. */
    private static FxQuoteView replayed(StoredResponse response) {
        String[] fields = new String(response.body(), StandardCharsets.UTF_8).split("\\|", -1);
        if (fields[0].equals("ERR")) {
            throw refused(FxErrorCode.valueOf(fields[1]));
        }
        return new FxQuoteView(fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8],
                fields[9], fields[10], fields[11]);
    }

    static FxQuoteView view(QuoteStore.QuoteRow quote) {
        return new FxQuoteView(
                quote.id().value().toString(),
                quote.status().name(),
                quote.customerSource().currency().code(),
                quote.customerDestination().currency().code(),
                quote.fixedSide().name(),
                quote.customerSource().toBigDecimal().toPlainString(),
                quote.customerDestination().toBigDecimal().toPlainString(),
                quote.customerRate().value().toPlainString(),
                quote.disclosedMargin().toPlainString(),
                quote.issuedAt().toString(),
                quote.expiresAt().toString());
    }

    private static FxQuoteId quoteId(String raw) {
        try {
            return FxQuoteId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
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
