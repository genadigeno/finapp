package com.finapp.payments;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The cancellation command ({@code P5-TSK-009}): the customer's own withdrawal, only from
 * {@code REQUIRES_CONFIRMATION} — the window ADR-0045 created for exactly this. Nothing was
 * dispatched, nothing posts; the conditional transition's row count arbitrates against a
 * racing confirm, and the loser of that race is told the truth ({@code 409} at the surface).
 *
 * <p>A retried cancel converges — {@code CANCELLED} found is the retry of a lost response,
 * one act however many times it was asked ({@code INV-IDEM-01}'s shape without a key: the
 * machine's one-way edge is the record). No event, deliberately: {@code PHASE_5_PLAN.md}
 * §10's register carries no {@code PaymentCancelled} — an event without a consumer is
 * vocabulary, and the register is the decision (the deliberately-few licence).
 */
@RequiredArgsConstructor
public final class PaymentCancellation {

    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The status after this call, and whether it converged on an earlier cancel. */
    public record CancellationResult(PaymentIntentStatus status, boolean converged) {}

    /**
     * Cancels the caller's intent, or converges on a cancel already committed.
     *
     * @throws UnknownPaymentException nothing written — the caller's one 404
     * @throws IllegalPaymentIntentTransitionException the intent is already processing or
     *     terminal — the caller's 409, nothing written
     */
    public CancellationResult cancel(
            Connection unitOfWork, UUID callerPartyId, PaymentIntentId intentId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        Actor person = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        intents.findOwned(unitOfWork, intentId, callerPartyId)
                .orElseThrow(UnknownPaymentException::new);

        if (intents.transition(
                unitOfWork,
                intentId,
                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                PaymentIntentStatus.CANCELLED)) {
            Instant now = Instant.now(clock);
            intents.recordTransition(
                    unitOfWork,
                    intentId,
                    PaymentIntentStatus.REQUIRES_CONFIRMATION,
                    PaymentIntentStatus.CANCELLED,
                    person,
                    now);
            audit.append(
                    unitOfWork,
                    new AuditRecord(
                            AuditId.next(ids),
                            person,
                            now,
                            PaymentsAuditAction.PAYMENT_CANCELLED,
                            PaymentCreation.TARGET_TYPE,
                            intentId.value().toString(),
                            Optional.empty(),
                            AuditOutcome.SUCCEEDED,
                            correlation.correlationId(),
                            Optional.of("intent=" + intentId + ", status=CANCELLED")));
            return new CancellationResult(PaymentIntentStatus.CANCELLED, false);
        }

        PaymentIntentStatus current =
                intents.findById(unitOfWork, intentId)
                        .orElseThrow(UnknownPaymentException::new)
                        .status();
        if (current == PaymentIntentStatus.CANCELLED) {
            // The retry of a lost response: one act, converged, nothing recorded twice.
            return new CancellationResult(PaymentIntentStatus.CANCELLED, true);
        }
        throw new IllegalPaymentIntentTransitionException(
                intentId, current, PaymentIntentStatus.CANCELLED);
    }
}
