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
            case INSTANT_CONCLUDED_EXECUTION -> concludedPayIn(hint.requireAttempt(), new EndToEndReference(hint.askedAbout()));
            case INSTANT_UNATTRIBUTED_EXECUTION -> unattributed(new ProviderReference(hint.askedAbout()));
            default -> throw new IllegalArgumentException("the instant rail does not answer a " + hint.kind() + " hint");
        };
    }

    /** The pay-in {@code reference} names, still waiting at delivery. Its own sweep decides whatever this cannot. */
    public HintedOutcome payIn(PaymentAttemptId attemptId, EndToEndReference reference) {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(reference, "reference must not be null");
        return askAboutPayIn(attemptId, reference, HintedOutcome.UNDECIDED);
    }

    /** Value claimed on a pay-in that had concluded. No sweep looks at it, so the scheme's "no such thing" is a refutation. */
    private HintedOutcome concludedPayIn(PaymentAttemptId attemptId, EndToEndReference reference) {
        return askAboutPayIn(attemptId, reference, HintedOutcome.REFUTED);
    }

    private HintedOutcome askAboutPayIn(
            PaymentAttemptId attemptId, EndToEndReference reference, HintedOutcome whenUnrecognised) {
        PushInquiryAnswer answer = rail.inquireInitiation(reference);
        Correlation correlation = PaymentCreation.resolvedCorrelation();
        boolean noAmount = answer.verdict() == PushInquiryAnswer.Verdict.ACCEPTED && answer.executed().isEmpty();
        if (!usable(answer) || noAmount) {
            answer.evidence().ifPresent(bytes -> transactions.inTransaction(uow -> {
                retainForAttempt(uow, attemptId, bytes);
                return null;
            }));
            return answer.verdict() == PushInquiryAnswer.Verdict.UNRECOGNISED ? whenUnrecognised : HintedOutcome.UNDECIDED;
        }
        return transactions.inTransaction(uow -> {
            PaymentAttempt current = attempts.findById(uow, attemptId)
                    .orElseThrow(() -> new PaymentsStorageException("a hinted attempt vanished"));
            if (answer.verdict() == PushInquiryAnswer.Verdict.REJECTED) {
                if (!pushResolvable(current.status())) {
                    retainAnswer(uow, Optional.empty(), attemptId, answer);
                    return rejectionOnConcluded(current);
                }
                PaymentIntent intent = intentOf(uow, current);
                PaymentOutcomes.Applied applied = appliers.applyExecution(
                        uow, intent.id(), current.id(), current.status(), answer.verdict(), answer.schemeReference(),
                        answer.settlementCycle(), intent.creditAccount(), intent.amount(), answer.executed(), correlation);
                retainAnswer(uow, applied.parking(), attemptId, answer);
                return applied.acting() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
            }
            OnAttempt judged = executionOn(
                    uow, current, answer.schemeReference(), answer.settlementCycle(), answer.executed(), correlation);
            retainAnswer(uow, judged.parking(), attemptId, answer);
            return judged.outcome();
        });
    }

    /** What an execution the scheme confirmed came to on one of our pay-ins, and the parking it rests in, if any. */
    private record OnAttempt(HintedOutcome outcome, Optional<java.util.UUID> parking) {}

    /**
     * An execution the scheme confirmed, on a pay-in of ours, waiting or concluded. A concluded row reaches the applier
     * through its stale-source branch, the one place that judges value arriving on a concluded pay-in, so no inquiry can judge
     * it differently.
     */
    private OnAttempt executionOn(
            Connection uow, PaymentAttempt current, Optional<ProviderReference> scheme, Optional<String> cycle,
            Optional<com.finapp.sharedkernel.money.Money> executed, Correlation correlation) {
        boolean waiting = pushResolvable(current.status());
        if (!waiting && !appliers.canPark(uow, railId, executed.orElseThrow().currency())) {
            log.warn("The scheme's inquiry reports an execution on concluded attempt {} in a currency this rail cannot"
                    + " park; the answer rests as evidence - a break reconciliation must see", current.id());
            return new OnAttempt(HintedOutcome.CONTRADICTED, Optional.empty());
        }
        PaymentIntent intent = intentOf(uow, current);
        PaymentOutcomes.Applied applied = appliers.applyExecution(
                uow, intent.id(), current.id(),
                waiting ? current.status() : PaymentAttemptStatus.AWAITING_PAYER,
                PushInquiryAnswer.Verdict.ACCEPTED, scheme, cycle, intent.creditAccount(), intent.amount(), executed,
                correlation);
        return new OnAttempt(
                applied.acting() || applied.parking().isPresent() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED,
                applied.parking());
    }

    private PaymentIntent intentOf(Connection uow, PaymentAttempt attempt) {
        return intents.findById(uow, attempt.intentId())
                .orElseThrow(() -> new IllegalStateException("an attempt row's intent exists: V003's foreign key holds it"));
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
            Optional<PaymentAttemptId> onAttempt = Optional.empty();
            switch (answer.verdict()) {
                case UNRECOGNISED -> {
                    log.info("The scheme's inquiry denies a signed execution statement naming nothing of ours: nothing parks");
                    outcome = HintedOutcome.REFUTED;
                }
                case UNREADABLE -> {
                    log.warn("The scheme answered an execution inquiry and we cannot read it: nothing parks, the question stays"
                            + " open and the answer rests as evidence - an integration break");
                    outcome = HintedOutcome.UNREADABLE;
                }
                case INDETERMINATE -> outcome = HintedOutcome.UNDECIDED;
                case EXECUTED -> {
                    Optional<PaymentAttempt> onOurPayIn = answer.endToEndReference()
                            .flatMap(reference -> attempts.findByEndToEndReference(uow, reference));
                    if (onOurPayIn.isPresent()) {
                        // The scheme places it on a pay-in of ours. A waiting one takes it through the applier; a concluded
                        // one is the concluded branch's, which parks it, because no sweep would ever find that row again.
                        OnAttempt judged = executionOn(
                                uow, onOurPayIn.get(), answer.schemeReference(), answer.settlementCycle(), answer.executed(),
                                correlation);
                        outcome = judged.outcome();
                        parkedIn = judged.parking();
                        onAttempt = Optional.of(onOurPayIn.get().id());
                    } else if (answer.endToEndReference().filter(reference -> namesAReturnOrAWithdrawal(uow, reference)).isPresent()) {
                        // A row of ours explains it and that row's own sweep concludes it. Parking would book it twice, or book
                        // value that went out as value that came in.
                        log.info("The scheme's inquiry places a claimed execution on a return or withdrawal of ours; nothing parks");
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
                default -> throw new IllegalStateException("an execution answer's verdict is one of the four");
            }
            Optional<java.util.UUID> parking = parkedIn;
            Optional<PaymentAttemptId> subject = onAttempt;
            answer.evidence().ifPresent(bytes -> {
                if (parking.isPresent()) {
                    evidence.appendForUnmatched(uow, parking.get(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
                } else {
                    evidence.append(uow, subject, Optional.empty(), EvidenceKind.QUERY_RESULT, bytes, Instant.now(clock));
                }
            });
            return outcome;
        });
    }

    private boolean namesAReturnOrAWithdrawal(Connection uow, EndToEndReference reference) {
        return refunds.findByOperationReference(uow, new ProviderIdempotencyReference(reference.value())).isPresent()
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
