package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.ValuationRate;
import com.finapp.accounting.ValuationRateSet;
import com.finapp.accounting.ValuationRateSetId;
import com.finapp.accounting.ValuationRateSets;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the valuation rate set doors (`P14-TSK-014`; {@code GlMappingDesk}'s shape): key every act per
 * principal ({@code accounting.valuation:<type>:<id>}), run each in one transaction under its span, and translate the
 * domain's refusals onto the error contract; a refused self-approval is recorded {@code FAILED} in a transaction of its
 * own. The judgement of the reference, the period's state, the four eyes and the one approved set per period are the
 * domain's and {@code accounting V005}'s; nothing here decides them. Every rate leaves as its decimal string - never a
 * JSON number, never a floating-point value.
 */
@RequiredArgsConstructor
public final class ValuationRateSetDesk {

    static final String SCOPE = "accounting.valuation:";

    static final String CAPTURE_SPAN = "accounting.valuation.capture";
    static final String APPROVE_SPAN = "accounting.valuation.approve";
    static final String REJECT_SPAN = "accounting.valuation.reject";
    static final String READ_SPAN = "accounting.valuation.read";

    @NonNull private final ValuationRateSets sets;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;

    /** One rate with its provenance - the rate a decimal string exactly as stored. */
    public record ValuationRateView(
            String baseCurrency,
            String quoteCurrency,
            String rate,
            String source,
            String snapshotId,
            String observedAt,
            String receivedAt) {

        static ValuationRateView of(ValuationRate rate) {
            return new ValuationRateView(rate.base().code(), rate.quote().code(), rate.rate().toPlainString(),
                    rate.source(), rate.snapshotId().toString(), rate.observedAt().toString(),
                    rate.receivedAt().toString());
        }
    }

    /** An act's receipt: the set as the act left it, every rate with its provenance. No person, no reason. */
    public record ValuationRateSetReceipt(
            String id,
            String periodId,
            String period,
            String status,
            String asOf,
            int maxAgeHours,
            List<ValuationRateView> rates) {}

    /** A set as the reviewer reads it: the receipt's facts and its two instants. No person, no reason. */
    public record ValuationRateSetView(
            String id,
            String periodId,
            String period,
            String status,
            String asOf,
            int maxAgeHours,
            String capturedAt,
            String decidedAt,
            List<ValuationRateView> rates) {}

    public ValuationRateSetReceipt capture(String idempotencyKey, String rawPeriodId, String reason) {
        AccountingPeriodId periodId = periodId(rawPeriodId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(CAPTURE_SPAN, () -> keyed(actor, idempotencyKey,
                "capture|" + periodId.value() + "|" + reason,
                uow -> receipt(sets.capture(uow, periodId, actor, reason, correlation))));
    }

    public ValuationRateSetReceipt approve(String idempotencyKey, String rawSetId, String reason) {
        ValuationRateSetId id = setId(rawSetId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(APPROVE_SPAN, () -> keyed(actor, idempotencyKey, "approve|" + id.value() + "|" + reason,
                uow -> receipt(sets.approve(uow, id, actor, reason, correlation))));
    }

    public ValuationRateSetReceipt reject(String idempotencyKey, String rawSetId, String reason) {
        ValuationRateSetId id = setId(rawSetId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(REJECT_SPAN, () -> keyed(actor, idempotencyKey, "reject|" + id.value() + "|" + reason,
                uow -> receipt(sets.reject(uow, id, actor, reason, correlation))));
    }

    /** One set by id - a read, unkeyed, in one transaction. */
    public ValuationRateSetView read(String rawSetId) {
        ValuationRateSetId id = setId(rawSetId);
        return guarded(() -> flow.door(READ_SPAN, () -> transactions.inTransaction(uow -> view(sets.read(uow, id)))));
    }

    // ------------------------------------------------------------------ the views

    static ValuationRateSetReceipt receipt(ValuationRateSets.Valued set) {
        return new ValuationRateSetReceipt(set.id().value().toString(), set.period().value().toString(),
                set.month().toString(), set.status().name(), set.asOf().toString(), set.maxAgeHours(),
                set.rates().stream().map(ValuationRateView::of).toList());
    }

    static ValuationRateSetView view(ValuationRateSets.Read read) {
        ValuationRateSets.Valued set = read.set();
        return new ValuationRateSetView(set.id().value().toString(), set.period().value().toString(),
                set.month().toString(), set.status().name(), set.asOf().toString(), set.maxAgeHours(),
                read.capturedAt().toString(), read.decidedAt().map(Instant::toString).orElse(null),
                set.rates().stream().map(ValuationRateView::of).toList());
    }

    /** The receipt as the idempotency record stores it: pipe-separated, each rate's seven fields comma-separated. */
    static String stored(ValuationRateSetReceipt receipt) {
        List<String> rates = new ArrayList<>();
        for (ValuationRateView rate : receipt.rates()) {
            rates.add(String.join(",", rate.baseCurrency(), rate.quoteCurrency(), rate.rate(), rate.source(),
                    rate.snapshotId(), rate.observedAt(), rate.receivedAt()));
        }
        return String.join("|", receipt.id(), receipt.periodId(), receipt.period(), receipt.status(), receipt.asOf(),
                Integer.toString(receipt.maxAgeHours()), String.join(";", rates));
    }

    /** {@link #stored}'s inverse. */
    static ValuationRateSetReceipt fromStored(String stored) {
        String[] fields = stored.split("\\|", -1);
        List<ValuationRateView> rates = new ArrayList<>();
        for (String rate : fields[6].isEmpty() ? new String[0] : fields[6].split(";", -1)) {
            String[] parts = rate.split(",", -1);
            rates.add(new ValuationRateView(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5], parts[6]));
        }
        return new ValuationRateSetReceipt(fields[0], fields[1], fields[2], fields[3], fields[4],
                Integer.parseInt(fields[5]), List.copyOf(rates));
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing, a
     * self-approval is recorded {@code FAILED} in a transaction of its own.
     */
    private ValuationRateSetReceipt keyed(
            Actor actor,
            String idempotencyKey,
            String fingerprinted,
            Function<Connection, ValuationRateSetReceipt> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor,
                () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint,
                        uow -> CommandResult.succeeded(StoredResponse.of(
                                stored(act.apply(uow)).getBytes(StandardCharsets.UTF_8), "text/plain"))))));
        return fromStored(new String(
                outcome.body().orElseThrow(() -> new IllegalStateException("a valuation act stores its receipt")),
                StandardCharsets.UTF_8));
    }

    /**
     * {@code work}, a refused self-approval recorded {@code FAILED} in a transaction of its own after the act rolled back,
     * then refused as before.
     */
    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (ValuationRateSets.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                sets.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. */
    static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (ValuationRateSets.ValuationSetNotFound | AccountingPeriods.PeriodNotFound unknown) {
            throw notFound();
        } catch (ValuationRateSets.SelfApprovalRefused self) {
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (ValuationRateSets.ValuationSetStale stale) {
            throw refused(AccountingErrorCode.VALUATION_SET_STALE, stale);
        } catch (ValuationRateSet.ValuationRateStale stale) {
            throw refused(AccountingErrorCode.VALUATION_RATE_STALE, stale);
        } catch (AccountingPeriods.PeriodStale stale) {
            throw refused(AccountingErrorCode.PERIOD_STALE, stale);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A valuation rate set command was refused", cause.getMessage());
    }

    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No accounting record answers the request",
                "no such record.");
    }

    private static ValuationRateSetId setId(String raw) {
        try {
            return ValuationRateSetId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static AccountingPeriodId periodId(String raw) {
        try {
            return AccountingPeriodId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a valuation command runs inside a correlation scope"))
                .correlationId();
    }
}
