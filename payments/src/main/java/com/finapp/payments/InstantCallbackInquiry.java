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
 * The instant rail's hinted inquiries (`P15-TSK-036`, ADR-0083 decision 4), the {@link CardCallbackInquiry} doctrine at the
 * second rail: asked over the provider credential holding no connection, and only {@code ACCEPTED} and {@code REJECTED}
 * are adopted, through the appliers the resolution sweeps use. {@code UNRECOGNISED} never concludes a send from a hint: the
 * sweeps judge it against the declared deadline since the latest permit, and a callback carries no such bound.
 *
 * <p>A statement of value on a row that can no longer move, or naming nothing of ours, parks only on the scheme's own word
 * ({@code INV-REC-05}). The amount, the scheme reference and the cycle are the inquiry's; none is the callback's.
 */
@Slf4j
@RequiredArgsConstructor
public final class InstantCallbackInquiry implements HintedInquiry {

    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final WithdrawalStore<Connection> withdrawals;
    @NonNull private final ProviderEvidenceStore<Connection> evidence;
    @NonNull private final PushRail rail;
    @NonNull private final CallbackAppliers appliers;
    @NonNull private final RailId railId;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final Clock clock;

    @Override
    public Set<CallbackHint.Kind> kinds() {
        return Set.of(CallbackHint.Kind.INSTANT_CONCLUDED_EXECUTION, CallbackHint.Kind.INSTANT_UNATTRIBUTED_EXECUTION);
    }

    @Override
    public HintedOutcome inquire(CallbackHint hint) {
        Objects.requireNonNull(hint, "hint must not be null");
        return switch (hint.kind()) {
            case INSTANT_CONCLUDED_EXECUTION -> payIn(hint.requireAttempt(), new EndToEndReference(hint.askedAbout()));
            case INSTANT_UNATTRIBUTED_EXECUTION -> unattributed(new ProviderReference(hint.askedAbout()));
            default -> throw new IllegalArgumentException("the instant rail does not answer a " + hint.kind() + " hint");
        };
    }

    /**
     * The pay-in {@code reference} names, waiting or concluded. A concluded row reaches the applier through its stale-source
     * branch, the one place that judges value arriving on a concluded pay-in, so this inquiry cannot judge it differently.
     */
    public HintedOutcome payIn(PaymentAttemptId attemptId, EndToEndReference reference) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        PushInquiryAnswer answer = rail.inquireInitiation(reference);
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        boolean noAmount = answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED && answer.executed().isEmpty();
        if (!usable(answer) || noAmount) {
            answer.evidence().ifPresent(bytes -> transactions.inTransaction(uow -> {
                retainForAttempt(uow, attemptId, bytes);
                return null;
            }));
            return HintedOutcome.UNDECIDED;
        }
        return transactions.inTransaction(uow -> {
            PaymentAttempt current = attempts.findById(uow, attemptId)
                    .orElseThrow(() -> new PaymentsStorageException("a hinted attempt vanished"));
            PaymentIntent intent = intents.findById(uow, current.intentId())
                    .orElseThrow(() -> new IllegalStateException("an attempt row's intent exists: V003's foreign key holds it"));
            boolean waiting = pushResolvable(current.status());
            boolean accepted = answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED;
            if (!waiting && !accepted) {
                retainAnswer(uow, Optional.empty(), attemptId, answer);
                return rejectionOnConcluded(current);
            }
            if (!waiting && !appliers.canPark(uow, railId, answer.executed().orElseThrow().currency())) {
                log.warn("The scheme's inquiry reports an execution on concluded attempt {} in a currency this rail cannot"
                        + " park; the answer rests as evidence - a break reconciliation must see", attemptId);
                retainAnswer(uow, Optional.empty(), attemptId, answer);
                return HintedOutcome.CONTRADICTED;
            }
            PaymentOutcomes.Applied applied = appliers.applyExecution(
                    uow, intent.id(), current.id(),
                    waiting ? current.status() : PaymentAttemptStatus.AWAITING_PAYER,
                    answer.verdict(), answer.schemeReference(), answer.settlementCycle(), intent.creditAccount(),
                    intent.amount(), answer.executed(), correlation);
            retainAnswer(uow, applied.parking(), attemptId, answer);
            return applied.acting() || applied.parking().isPresent() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
        });
    }

    /** The return a refund row carries on this rail. */
    public HintedOutcome returnPayment(Refund named) {
        Objects.requireNonNull(named, "named must not be null");
        PushInquiryAnswer answer =
                rail.inquireReturn(new EndToEndReference(named.providerIdempotencyReference().value()));
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            Refund locked = HintedRefunds.lock(uow, refunds, named.id());
            HintedOutcome outcome = !usable(answer)
                    ? HintedOutcome.UNDECIDED
                    : HintedRefunds.judge(
                            uow, attempts, intents, appliers, locked,
                            answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED
                                    ? ProviderAnswer.Verdict.APPROVED
                                    : ProviderAnswer.Verdict.DECLINED,
                            answer.schemeReference(), correlation);
            answer.evidence().ifPresent(bytes -> evidence.append(
                    uow, Optional.empty(), Optional.of(locked.id()), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock)));
            return outcome;
        });
    }

    /** The withdrawal this rail's callback named. */
    public HintedOutcome withdrawal(Withdrawal named) {
        Objects.requireNonNull(named, "named must not be null");
        PushInquiryAnswer answer = rail.inquire(named.reference());
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            Withdrawal locked = withdrawals.findForUpdate(uow, named.id())
                    .orElseThrow(() -> new PaymentsStorageException("a hinted withdrawal vanished"));
            HintedOutcome outcome = usable(answer) ? judged(uow, locked, answer, correlation) : HintedOutcome.UNDECIDED;
            answer.evidence().ifPresent(bytes -> evidence.appendForWithdrawal(
                    uow, locked.id(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock)));
            return outcome;
        });
    }

    private HintedOutcome judged(Connection uow, Withdrawal locked, PushInquiryAnswer answer, Correlation correlation) {
        boolean resolvable = locked.status().isResolvable();
        // The bound licenses UNRECOGNISED and nothing else, and a hint never carries UNRECOGNISED this far.
        WithdrawalOutcomes.Applied applied = appliers.applyWithdrawal(uow, locked, answer, Instant.EPOCH, correlation);
        if (resolvable) {
            return applied.acting() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
        }
        boolean accepted = answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED;
        boolean agrees = (locked.status() == WithdrawalStatus.COMPLETED && accepted)
                || (locked.status() == WithdrawalStatus.FAILED && !accepted);
        return agrees ? HintedOutcome.CONVERGED : HintedOutcome.CONTRADICTED;
    }

    /** Value claimed under no reference of ours: parked only on the scheme's own record of it. */
    private HintedOutcome unattributed(ProviderReference scheme) {
        ExecutionAnswer answer = rail.inquireExecution(scheme);
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        return transactions.inTransaction(uow -> {
            HintedOutcome outcome;
            Optional<java.util.UUID> parkedIn = Optional.empty();
            switch (answer.verdict()) {
                case UNRECOGNISED -> {
                    log.warn("The scheme's inquiry denies a signed execution statement naming nothing of ours: nothing parks."
                            + " A refuted callback is what a compromised webhook key looks like");
                    outcome = HintedOutcome.REFUTED;
                }
                case INDETERMINATE -> outcome = HintedOutcome.UNDECIDED;
                case EXECUTED -> {
                    if (answer.endToEndReference().filter(reference -> names(uow, reference)).isPresent()) {
                        // The scheme says this execution carries a reference of ours, so a row of ours explains it and that
                        // row's own sweep concludes it. Parking would book it twice, or book value that went out as value
                        // that came in.
                        log.info("The scheme's inquiry places a claimed execution on a reference of ours; nothing parks");
                        outcome = HintedOutcome.CONVERGED;
                    } else if (!appliers.canPark(uow, railId, answer.executed().orElseThrow().currency())) {
                        log.warn("The scheme's inquiry confirms an unattributed execution in a currency this rail cannot"
                                + " park; the answer rests as evidence - a break reconciliation must see");
                        outcome = HintedOutcome.CONTRADICTED;
                    } else {
                        UnmatchedConfirmations.Parked parked = appliers.park(
                                uow,
                                new UnmatchedConfirmations.Parking(
                                        railId,
                                        answer.schemeReference().orElseThrow(),
                                        answer.executed().orElseThrow(),
                                        UnmatchedConfirmation.Attribution.unattributed(
                                                answer.endToEndReference(), answer.settlementCycle()),
                                        correlation));
                        parkedIn = parked.parking();
                        outcome = parked.acting() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
                    }
                }
                default -> throw new IllegalStateException("an execution answer's verdict is one of the three");
            }
            Optional<java.util.UUID> parking = parkedIn;
            answer.evidence().ifPresent(bytes -> {
                if (parking.isPresent()) {
                    evidence.appendForUnmatched(uow, parking.get(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
                } else {
                    evidence.append(
                            uow, Optional.empty(), Optional.empty(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
                }
            });
            return outcome;
        });
    }

    private boolean names(Connection uow, EndToEndReference reference) {
        return attempts.findByEndToEndReference(uow, reference).isPresent()
                || refunds.findByOperationReference(uow, new ProviderIdempotencyReference(reference.value())).isPresent()
                || withdrawals.findByEndToEndReference(uow, reference).isPresent();
    }

    private static HintedOutcome rejectionOnConcluded(PaymentAttempt current) {
        if (current.status() == PaymentAttemptStatus.EXECUTED) {
            log.warn("The scheme's inquiry reports a rejection on executed attempt {}; the row stands and the answer rests as"
                    + " evidence - a break reconciliation must see", current.id());
            return HintedOutcome.CONTRADICTED;
        }
        return HintedOutcome.CONVERGED;
    }

    private static boolean usable(PushInquiryAnswer answer) {
        return answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED || answer.verdict() == PushInquiryAnswer.Verdict.REJECTED;
    }

    private void retainAnswer(
            Connection uow, Optional<java.util.UUID> parking, PaymentAttemptId attempt, PushInquiryAnswer answer) {
        // An answer whose value parked rests addressed to its parking (V023's fifth subject, P8-TSK-020), else to the attempt.
        answer.evidence().ifPresent(bytes -> {
            if (parking.isPresent()) {
                evidence.appendForUnmatched(uow, parking.get(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
            } else {
                retainForAttempt(uow, attempt, bytes);
            }
        });
    }

    private void retainForAttempt(Connection uow, PaymentAttemptId attempt, byte[] bytes) {
        evidence.append(uow, Optional.of(attempt), Optional.empty(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
    }

    private static boolean pushResolvable(PaymentAttemptStatus status) {
        return status == PaymentAttemptStatus.AWAITING_PAYER
                || status == PaymentAttemptStatus.EXECUTION_DISPATCHED
                || status == PaymentAttemptStatus.EXECUTION_UNKNOWN;
    }
}
