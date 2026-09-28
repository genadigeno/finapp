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
 * The void command (`P7-TSK-004`, ADR-0059 §1's declared card reversal, `INV-REV-03`'s
 * revocable half performed): releasing an uncaptured authorization at the provider, in the
 * ADR-0046 shape — <strong>Tx1</strong> commits the conditional {@code AUTHORIZED →
 * VOID_DISPATCHED} with our minted reference ({@code INV-PAY-04}) and the dispatch audit;
 * the provider is asked holding no connection; <strong>Tx2</strong> applies the answer
 * through {@link PaymentOutcomes#applyVoid} as the platform and retains the received bytes.
 *
 * <h2>The capability, before anything else</h2>
 *
 * <p>{@code INV-REV-03} is judged from the STORED rail's declared capabilities — never a
 * name ({@code INV-RAIL-01}) — and <strong>before the transition</strong>: a rail without
 * {@code VOID} answers {@link ReversalNotSupportedException} with nothing written and
 * nothing sent.
 *
 * <h2>{@link #completeDispatched} is every stranded void's finisher</h2>
 *
 * <p>A void may be committed {@code VOID_DISPATCHED} by a resolver that cannot send — the
 * webhook door's declined-capture redirect has no provider port — or by a sender that
 * crashed mid-call. Any instance finishes it: the send is idempotent at the provider by our
 * stored reference (releasing a released promise converges), so the sweeper's void leg
 * re-sends without a permit — the deliberate asymmetry with the refund's `V009`, recorded
 * where the refund's reasoning lives. Racing finishers converge on the conditional
 * transitions like every other resolver pair.
 *
 * <h2>Two callers, one machine door</h2>
 *
 * <p>The customer's cancellation of an authorized intent (their own intent only —
 * {@code findOwned}) and the operator's reasoned void (behind {@code PAYMENT_REFUND} at the
 * surface) run the same choreography; only the audit's actor and reason differ. The race
 * against the capture chain is the conditional out of {@code AUTHORIZED}: one row count
 * wins, the loser converges or is told the machine's truth.
 */
@RequiredArgsConstructor
public final class PaymentVoid {

    @NonNull private final TransactionRunner transactions;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final ProviderEvidenceStore<Connection> evidence;
    @NonNull private final PaymentProvider provider;
    @NonNull private final PaymentOutcomes outcomes;
    @NonNull private final PaymentRails rails;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The states after this call, and whether it converged on work already done. */
    public record VoidResult(
            PaymentIntentStatus intent,
            PaymentAttemptStatus attempt,
            boolean converged,
            boolean acting) {}

    /** Tx1's yield, carried across the connectionless gap. */
    private record Dispatched(PaymentAttemptId attempt) {}

    private record Tx1Outcome(Optional<Dispatched> dispatched, Optional<VoidResult> converged) {}

    /**
     * Voids the {@code AUTHORIZED} attempt of {@code intentId}.
     *
     * @param ownerParty the caller's party for the customer door ({@code findOwned} — a
     *     stranger's and an unknown intent are one 404); empty for the operator door, whose
     *     wall is {@code PAYMENT_REFUND} at the surface
     * @param reason the operator's words, recorded verbatim ({@code INV-AUD-03}); empty on
     *     the customer door, whose act speaks for itself
     * @throws UnknownPaymentException nothing written — the caller's one 404
     * @throws ReversalNotSupportedException the rail declares no reversal — nothing written,
     *     nothing sent ({@code INV-REV-03})
     * @throws IllegalPaymentAttemptTransitionException the attempt is not voidable — a
     *     capture already dispatched, or a terminal — the caller's 409, nothing written
     */
    public VoidResult voidAuthorized(
            Optional<UUID> ownerParty, PaymentIntentId intentId, Optional<String> reason) {
        Objects.requireNonNull(ownerParty, "ownerParty must not be null");
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Actor actor = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        Tx1Outcome tx1 =
                transactions.inTransaction(
                        uow -> dispatch(uow, ownerParty, intentId, reason, actor, correlation));
        if (tx1.converged().isPresent()) {
            return tx1.converged().get();
        }
        return completeDispatched(tx1.dispatched().orElseThrow().attempt());
    }

    /** Tx1: capability first, then the conditional dispatch — or converge on the truth. */
    private Tx1Outcome dispatch(
            Connection uow,
            Optional<UUID> ownerParty,
            PaymentIntentId intentId,
            Optional<String> reason,
            Actor actor,
            Correlation correlation) {
        PaymentIntent intent =
                (ownerParty.isPresent()
                                ? intents.findOwned(uow, intentId, ownerParty.get())
                                : intents.findById(uow, intentId))
                        .orElseThrow(UnknownPaymentException::new);
        PaymentAttempt attempt =
                attempts.findForIntent(uow, intentId)
                        .orElseThrow(
                                () ->
                                        new IllegalPaymentIntentTransitionException(
                                                intentId,
                                                intent.status(),
                                                PaymentIntentStatus.FAILED));

        // INV-REV-03, before anything is written: the STORED rail's declaration decides.
        if (!rails.capabilitiesOf(attempt.rail())
                .reversals()
                .contains(RailCapabilities.Reversal.VOID)) {
            throw new ReversalNotSupportedException(attempt.rail());
        }

        // A void already in flight, or already concluded: the retry converges on the truth.
        if (attempt.status() == PaymentAttemptStatus.VOID_DISPATCHED
                || attempt.status() == PaymentAttemptStatus.VOID_UNKNOWN
                || attempt.status() == PaymentAttemptStatus.VOIDED) {
            return new Tx1Outcome(
                    Optional.empty(),
                    Optional.of(
                            new VoidResult(intent.status(), attempt.status(), true, false)));
        }
        if (attempt.status() != PaymentAttemptStatus.AUTHORIZED) {
            throw new IllegalPaymentAttemptTransitionException(
                    attempt.id(), attempt.status(), PaymentAttemptStatus.VOID_DISPATCHED);
        }

        ProviderIdempotencyReference reference =
                new ProviderIdempotencyReference("void-" + ids.next());
        if (!attempts.dispatchVoid(
                uow, attempt.id(), PaymentAttemptStatus.AUTHORIZED, reference)) {
            // The race the task names: the capture chain won the conditional out of
            // AUTHORIZED (or another void did). Converge or tell the machine's truth.
            PaymentAttempt current =
                    attempts.findById(uow, attempt.id())
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "a judged attempt has a row"));
            if (current.status() == PaymentAttemptStatus.VOID_DISPATCHED
                    || current.status() == PaymentAttemptStatus.VOID_UNKNOWN
                    || current.status() == PaymentAttemptStatus.VOIDED) {
                return new Tx1Outcome(
                        Optional.empty(),
                        Optional.of(
                                new VoidResult(
                                        intent.status(), current.status(), true, false)));
            }
            throw new IllegalPaymentAttemptTransitionException(
                    current.id(), current.status(), PaymentAttemptStatus.VOID_DISPATCHED);
        }

        Instant now = Instant.now(clock);
        attempts.recordTransition(
                uow,
                attempt.id(),
                PaymentAttemptStatus.AUTHORIZED,
                PaymentAttemptStatus.VOID_DISPATCHED,
                actor,
                now);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        PaymentsAuditAction.PAYMENT_VOID_DISPATCHED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        // The operator's words verbatim; the customer's act speaks for
                        // itself (INV-AUD-03).
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + intentId
                                        + ", attempt=" + attempt.id()
                                        + ", reference=" + reference.value()
                                        + ", rail=" + attempt.rail().value())));
        return new Tx1Outcome(Optional.of(new Dispatched(attempt.id())), Optional.empty());
    }

    /**
     * Sends the void a {@code VOID_DISPATCHED} row records and applies the answer — the
     * finisher every path funnels through: the fresh dispatch above, the declined-capture
     * redirect's caller, and the sweeper's re-send of a stranded row. Reads the row itself,
     * so a caller needs only the attempt.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public VoidResult completeDispatched(PaymentAttemptId attemptId) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        record ToSend(
                PaymentIntentId intent,
                ProviderIdempotencyReference reference,
                ProviderReference authorization,
                Optional<VoidResult> converged) {}
        ToSend toSend =
                transactions.inTransaction(
                        uow -> {
                            PaymentAttempt current =
                                    attempts.findById(uow, attemptId)
                                            .orElseThrow(
                                                    () ->
                                                            new IllegalStateException(
                                                                    "a void's attempt row"
                                                                            + " exists"));
                            if (current.status() != PaymentAttemptStatus.VOID_DISPATCHED) {
                                PaymentIntentStatus intentStatus =
                                        intents.findById(uow, current.intentId())
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "an attempt row's"
                                                                                + " intent"
                                                                                + " exists"))
                                                .status();
                                return new ToSend(
                                        current.intentId(),
                                        null,
                                        null,
                                        Optional.of(
                                                new VoidResult(
                                                        intentStatus,
                                                        current.status(),
                                                        true,
                                                        false)));
                            }
                            return new ToSend(
                                    current.intentId(),
                                    current.voidReference(),
                                    current.authorizationProviderReference(),
                                    Optional.empty());
                        });
        if (toSend.converged().isPresent()) {
            return toSend.converged().get();
        }

        // The provider call - between the transactions, holding no database connection
        // (ADR-0046, P1-TSK-026). An exception here propagates: the dispatch stays
        // committed and visibly stranded, the sweeper's void leg the finisher.
        ProviderAnswer answer =
                provider.voidAuthorization(
                        new PaymentProvider.VoidRequest(
                                toSend.reference(), toSend.authorization()));

        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        PaymentOutcomes.Applied applied =
                                outcomes.applyVoid(
                                        uow,
                                        toSend.intent(),
                                        attemptId,
                                        PaymentAttemptStatus.VOID_DISPATCHED,
                                        answer.verdict(),
                                        answer.providerReference(),
                                        correlation);
                        // Retained AFTER the outcome's row lock (the P5-TSK-013 order).
                        answer.evidence()
                                .ifPresent(
                                        bytes ->
                                                evidence.append(
                                                        uow,
                                                        Optional.of(attemptId),
                                                        Optional.empty(),
                                                        EvidenceKind.RESPONSE,
                                                        bytes,
                                                        Instant.now(clock)));
                        return new VoidResult(
                                applied.intent(), applied.attempt(), false, applied.acting());
                    });
        }
    }
}
