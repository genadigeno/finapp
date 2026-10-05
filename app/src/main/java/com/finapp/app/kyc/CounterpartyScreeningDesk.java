package com.finapp.app.kyc;

import com.finapp.kyc.CounterpartyScreeningId;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreenings;
import com.finapp.kyc.KycErrorCode;
import com.finapp.kyc.TransactionRunner;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the counterparty review door (`P9-TSK-016`): parse exactly, key the decision per
 * principal, run it in one transaction, translate kyc's refusals onto the error contract, and tell the
 * meters once it committed. The rules - a reason code that justifies the decision, a screened narrative,
 * one decision per screening - are kyc's and {@code kyc V009}'s.
 */
@RequiredArgsConstructor
public final class CounterpartyScreeningDesk {

    static final String SCOPE = "kyc.counterparty-screening-decision:";

    @NonNull private final CounterpartyScreenings screenings;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionRunner transactions;

    /** The decision's receipt - identifiers and the outcome. */
    public record ScreeningDecisionReceipt(String id, String status) {}

    public ScreeningDecisionReceipt decide(
            String idempotencyKey, String rawId, CounterpartyScreeningController.ScreeningDecisionRequest request) {
        CounterpartyScreeningId id = screeningId(rawId);
        Decision decision = parse(Decision.class, request.decision());
        ReasonCode code = parse(ReasonCode.class, request.reasonCode());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        // The narrative enters the fingerprint as its digest, so the stored key carries no prose.
        RequestFingerprint fingerprint = RequestFingerprint.sha256(
                (id.value() + "|" + decision.name() + "|" + code.name() + "|" + digest(request.narrative()))
                        .getBytes(StandardCharsets.UTF_8));
        AtomicReference<CounterpartyScreenings.Screening> decided = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> transactions.inTransaction(
                unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                    CounterpartyScreenings.Screening screening =
                            screenings.review(uow, id, actor, decision, code, request.narrative(), correlation);
                    decided.set(screening);
                    String receipt = screening.id().value() + "|" + screening.status().name();
                    return CommandResult.succeeded(StoredResponse.of(receipt.getBytes(StandardCharsets.UTF_8), "text/plain"));
                })));
        if (decided.get() != null) {
            screenings.committed(decided.get());
        }
        String[] stored = new String(
                        outcome.body().orElseThrow(() -> new IllegalStateException("a screening decision stores its receipt")),
                        StandardCharsets.UTF_8)
                .split("\\|", -1);
        return new ScreeningDecisionReceipt(stored[0], stored[1]);
    }

    // ------------------------------------------------------------------ plumbing

    /** kyc's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (CounterpartyScreenings.ScreeningNotFound unknown) {
            throw notFound();
        } catch (CounterpartyScreenings.ScreeningNotInReview decided) {
            throw new ApiException(
                    KycErrorCode.SCREENING_NOT_IN_REVIEW, "A counterparty screening decision was refused", decided.getMessage());
        } catch (CounterpartyScreenings.ScreeningReviewInvalid invalid) {
            throw invalid(invalid.getMessage());
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknown) {
            throw invalid("unknown " + type.getSimpleName() + ": it must be one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(KycErrorCode.SCREENING_REVIEW_INVALID, "A counterparty screening decision was refused", detail);
    }

    private static ApiException notFound() {
        return new ApiException(
                KycErrorCode.SCREENING_NOT_FOUND, "No counterparty screening matches the requested identifier", "no such record.");
    }

    private static CounterpartyScreeningId screeningId(String raw) {
        try {
            return CounterpartyScreeningId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static String digest(String narrative) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(narrative.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JCA algorithm", impossible);
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a screening decision runs inside a correlation scope"))
                .correlationId();
    }
}
