package com.finapp.payments;

import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The card rail's hinted inquiries (`P15-TSK-036`, ADR-0083 decision 4): what a webhook's arrival triggers once its
 * evidence and dedupe have committed. The PSP is asked over the provider credential, holding no connection (ADR-0046), and
 * only what it answers reaches the appliers the sweep uses, on the locked row re-read in this transaction.
 *
 * <h2>A hint hastens learning and licenses nothing else</h2>
 *
 * <p>{@code APPROVED} and {@code DECLINED} are adopted. {@code UNRECOGNISED} and {@code INDETERMINATE} adopt nothing and
 * write no state, which is narrower than the sweep on purpose. The sweep may read "never saw it" as a failure because its
 * bounds say the request cannot still be in flight; a callback carries no such bound, so a forged one aimed at a
 * just-dispatched capture must not be able to fail it while the PSP is still performing it. For the same reason a hint
 * re-sends nothing: no void, no refund.
 *
 * <h2>A late answer and the row (the instant door's repair, at the card door)</h2>
 *
 * <p>The row is locked and re-read first. An answer on an operation the row has moved past moves nothing; if it agrees with
 * what the row holds that is convergence, and if it does not the PSP has contradicted itself and the outcome says so,
 * loudly, with the answer retained.
 */
@Slf4j
@RequiredArgsConstructor
public final class CardCallbackInquiry implements HintedInquiry {

    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final ProviderEvidenceStore<Connection> evidence;
    @NonNull private final PaymentProvider provider;
    @NonNull private final CallbackAppliers appliers;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final Clock clock;

    @Override
    public Set<CallbackHint.Kind> kinds() {
        return Set.of(CallbackHint.Kind.CARD_CLEARING, CallbackHint.Kind.CARD_DISPUTE);
    }

    @Override
    public HintedOutcome inquire(CallbackHint hint) {
        Objects.requireNonNull(hint, "hint must not be null");
        return switch (hint.kind()) {
            case CARD_CLEARING -> clearing(hint);
            case CARD_DISPUTE -> dispute(hint);
            default -> throw new IllegalArgumentException("the card rail does not answer a " + hint.kind() + " hint");
        };
    }

    /** The authorization, capture or void {@code operation} names on this attempt. */
    public HintedOutcome operation(PaymentAttemptId attemptId, ProviderIdempotencyReference operation) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
        QueryAnswer answer = provider.query(operation);
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            PaymentAttempt locked = lockedAttempt(uow, attemptId);
            HintedOutcome outcome = judge(uow, locked, operation, answer, correlation);
            // Whatever it said, what arrived is retained (INV-HIST-02), after the row lock (the P5-TSK-013 order).
            retainForAttempt(uow, locked.id(), answer.evidence());
            return outcome;
        });
    }

    /** The refund this rail's callback named. */
    public HintedOutcome refund(Refund named) {
        Objects.requireNonNull(named, "named must not be null");
        QueryAnswer answer = provider.query(named.providerIdempotencyReference());
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            Refund locked = HintedRefunds.lock(uow, refunds, named.id());
            HintedOutcome outcome = switch (answer.verdict()) {
                case APPROVED, DECLINED -> HintedRefunds.judge(
                        uow, attempts, intents, appliers, locked,
                        answer.verdict() == QueryAnswer.Verdict.APPROVED
                                ? ProviderAnswer.Verdict.APPROVED
                                : ProviderAnswer.Verdict.DECLINED,
                        answer.providerReference(), correlation);
                case UNRECOGNISED, INDETERMINATE -> HintedOutcome.UNDECIDED;
            };
            answer.evidence().ifPresent(bytes -> evidence.append(
                    uow, Optional.empty(), Optional.of(locked.id()), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock)));
            return outcome;
        });
    }

    private HintedOutcome judge(
            Connection uow, PaymentAttempt locked, ProviderIdempotencyReference operation, QueryAnswer answer,
            Correlation correlation) {
        if (answer.verdict() != QueryAnswer.Verdict.APPROVED && answer.verdict() != QueryAnswer.Verdict.DECLINED) {
            // INV-LIFE-03, and the class javadoc: no state is written, and the sweep decides inside its own bounds.
            log.info("The inquiry a webhook triggered for attempt {} gave no usable answer ({}); the sweep decides",
                    locked.id(), answer.verdict());
            return HintedOutcome.UNDECIDED;
        }
        ProviderAnswer.Verdict verdict = answer.verdict() == QueryAnswer.Verdict.APPROVED
                ? ProviderAnswer.Verdict.APPROVED
                : ProviderAnswer.Verdict.DECLINED;
        boolean authOperation = operation.equals(locked.authorizationReference());
        boolean voidOperation = operation.equals(locked.voidReference());
        PaymentIntent intent = intents.findById(uow, locked.intentId())
                .orElseThrow(() -> new IllegalStateException("an attempt row's intent exists: V003's foreign key holds it"));
        PaymentAttemptStatus status = locked.status();
        if (authOperation && authResolvable(status)) {
            return applied(appliers.applyAuthorization(
                    uow, intent.id(), locked.id(), status, verdict, answer.providerReference(),
                    // The issuer approved the dispatched ask: the intent's amount, as every resolver carries it.
                    intent.amount(), correlation));
        }
        if (voidOperation && voidResolvable(status)) {
            return applied(appliers.applyVoid(
                    uow, intent.id(), locked.id(), status, verdict, answer.providerReference(), correlation));
        }
        if (!authOperation && !voidOperation && captureResolvable(status)) {
            // A DECLINED here may redirect into the void; the sweep's void leg performs that send (P7-TSK-004). A hint
            // sends nothing.
            return applied(appliers.applyCapture(
                    uow, intent.id(), locked.id(), status, verdict, answer.providerReference(), intent.creditAccount(),
                    // The capture is the authorized promise, in full (ADR-0045 section 4).
                    locked.authorizedAmount(), correlation));
        }
        return late(locked, operation, answer, authOperation, voidOperation);
    }

    /** The row has moved past this operation: the answer moves nothing, and either agrees with the row or does not. */
    private HintedOutcome late(
            PaymentAttempt locked, ProviderIdempotencyReference operation, QueryAnswer answer, boolean authOperation,
            boolean voidOperation) {
        ProviderReference held = authOperation
                ? locked.authorizationProviderReference()
                : voidOperation ? locked.voidProviderReference() : locked.captureProviderReference();
        boolean contradicted = answer.verdict() == QueryAnswer.Verdict.APPROVED
                ? held == null || !held.equals(answer.providerReference().orElseThrow())
                : held != null;
        if (contradicted) {
            log.warn("The PSP's inquiry on attempt {} in state {} contradicts the row: answered {}; the row stands and the"
                            + " answer rests as evidence - a break reconciliation must see",
                    locked.id(), locked.status(), answer.verdict());
            return HintedOutcome.CONTRADICTED;
        }
        log.info("The PSP's inquiry on attempt {} in state {} agrees with the row (INV-LIFE-04)", locked.id(), locked.status());
        return HintedOutcome.CONVERGED;
    }

    private HintedOutcome clearing(CallbackHint hint) {
        ClearingAnswer answer = provider.queryClearing(new ProviderIdempotencyReference(hint.askedAbout()));
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            PaymentAttempt locked = lockedAttempt(uow, hint.requireAttempt());
            HintedOutcome outcome = switch (answer.verdict()) {
                case CLEARED -> recorded(uow, locked, answer, correlation);
                case NOT_CLEARED, UNRECOGNISED -> refuted("clearing notice", locked.id());
                case INDETERMINATE -> HintedOutcome.UNDECIDED;
            };
            retainForAttempt(uow, locked.id(), answer.evidence());
            return outcome;
        });
    }

    private HintedOutcome recorded(
            Connection uow, PaymentAttempt locked, ClearingAnswer answer, Correlation correlation) {
        PaymentClearing.Outcome recorded = appliers.recordClearing(
                uow, locked, answer.acquirerReference().orElseThrow(), answer.networkTransactionId().orElseThrow(),
                correlation);
        return switch (recorded) {
            case RECORDED -> {
                if (locked.status() == PaymentAttemptStatus.FAILED || locked.status() == PaymentAttemptStatus.VOIDED) {
                    // Recorded - reconciliation needs the references - and loud (the Phase 7 -> 8 transition).
                    log.warn("A clearing was recorded for attempt {} which the platform holds {} - the network presented a"
                                    + " payment our books did not capture; a break reconciliation must see",
                            locked.id(), locked.status());
                    yield HintedOutcome.CONTRADICTED;
                }
                log.info("A capture's clearing was recorded with its network references (attempt {}, INV-SET-01)", locked.id());
                yield HintedOutcome.ADOPTED;
            }
            case ALREADY_RECORDED -> HintedOutcome.CONVERGED;
            case SECOND_PRESENTMENT, REFERENCE_CLAIMED_ELSEWHERE -> {
                log.warn("A clearing inquiry for attempt {} answered references that collide with a standing record ({});"
                                + " the first record stands and the answer rests as evidence", locked.id(), recorded);
                yield HintedOutcome.CONTRADICTED;
            }
        };
    }

    private HintedOutcome dispute(CallbackHint hint) {
        DisputeAnswer answer = provider.queryDispute(new ProviderReference(hint.askedAbout()));
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            // The attempt's lock is the applier's own first act; this read only attributes.
            PaymentAttempt attempt = attempts.findById(uow, hint.requireAttempt())
                    .orElseThrow(() -> new PaymentsStorageException("a hinted attempt vanished"));
            HintedOutcome outcome = switch (answer.verdict()) {
                case FOUND -> applied(uow, attempt, answer, correlation);
                case UNRECOGNISED -> refuted("dispute notice", attempt.id());
                case INDETERMINATE -> HintedOutcome.UNDECIDED;
            };
            retainForAttempt(uow, attempt.id(), answer.evidence());
            return outcome;
        });
    }

    private HintedOutcome applied(Connection uow, PaymentAttempt attempt, DisputeAnswer answer, Correlation correlation) {
        ProviderIdempotencyReference contested = answer.operation().orElseThrow();
        if (!contested.equals(attempt.authorizationReference()) && !contested.equals(attempt.captureReference())) {
            // Attribution from the authenticated channel: the dispute is real and contests something else.
            log.warn("A dispute inquiry for attempt {} answered that the dispute contests another operation; nothing moves"
                    + " and the answer rests as evidence", attempt.id());
            return HintedOutcome.CONTRADICTED;
        }
        DisputeNotifications.Outcome applied =
                appliers.applyDispute(uow, attempt, answer.notice().orElseThrow(), correlation);
        return switch (applied) {
            case OPENED, ADVANCED, FEE_RECORDED, DEADLINE_RECORDED -> {
                log.info("A dispute on attempt {} moved at the PSP's own word ({})", attempt.id(), applied);
                yield HintedOutcome.ADOPTED;
            }
            case UNCHANGED, LATE -> HintedOutcome.CONVERGED;
            case STAGE_CONTRADICTED, FACTS_CONTRADICTED, NOT_DISPUTABLE -> {
                log.warn("A dispute inquiry for attempt {} contradicts what the platform holds ({}); the record stands and the"
                        + " answer rests as evidence", attempt.id(), applied);
                yield HintedOutcome.CONTRADICTED;
            }
        };
    }

    private static HintedOutcome refuted(String kind, PaymentAttemptId attempt) {
        log.warn("The PSP's inquiry denies a signed {} naming attempt {}: nothing moves. A refuted callback is what a"
                + " compromised webhook key looks like", kind, attempt);
        return HintedOutcome.REFUTED;
    }

    private static HintedOutcome applied(PaymentOutcomes.Applied applied) {
        return applied.acting() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
    }

    private PaymentAttempt lockedAttempt(Connection uow, PaymentAttemptId attempt) {
        return attempts.lockById(uow, attempt)
                .orElseThrow(() -> new PaymentsStorageException("a hinted attempt vanished"));
    }

    private void retainForAttempt(Connection uow, PaymentAttemptId attempt, Optional<byte[]> bytes) {
        bytes.ifPresent(body -> evidence.append(
                uow, Optional.of(attempt), Optional.empty(), EvidenceKind.QUERY_RESULT, body, Instant.now(clock)));
    }

    private static boolean authResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.AUTH_DISPATCHED || status == PaymentAttemptStatus.AUTH_UNKNOWN;
    }

    private static boolean captureResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.CAPTURE_DISPATCHED || status == PaymentAttemptStatus.CAPTURE_UNKNOWN;
    }

    private static boolean voidResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.VOID_DISPATCHED || status == PaymentAttemptStatus.VOID_UNKNOWN;
    }
}
