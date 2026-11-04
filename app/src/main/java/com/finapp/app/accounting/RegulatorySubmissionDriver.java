package com.finapp.app.accounting;

import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.RegulatorySubmissions.Answered;
import com.finapp.accounting.RegulatorySubmissions.Claimed;
import com.finapp.accounting.RegulatorySubmissions.Disposition;
import com.finapp.accounting.RegulatorySubmissions.Recorded;
import com.finapp.platform.security.SecurityContext;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@code SUBMITTED} regulatory return taken to the provider and its answer recorded (`P14-TSK-018`; {@code PHASE_14_PLAN.md} sections 8.3 row 13, 8.4 (d); ADR-0118 decision 2; {@code RegulatoryFilingDriver}'s shape), in three steps with the provider in none of
 * accounting's transactions:
 *
 * <ol>
 *   <li><strong>the claim</strong>: {@link RegulatorySubmissions#claim} for the sending door's own return after its commit, on every replay of its key, or {@link RegulatorySubmissions#claimNextDue} for {@code AccountingCloseSchedule}'s inquiry leg: the row
 *       {@code FOR UPDATE SKIP LOCKED} on a permit that has come, one attempt counted, the permit a lease ahead, <strong>committed</strong>, so ten instances claim a return once per lease;</li>
 *   <li><strong>the call</strong> on <strong>no transaction</strong>: the first attempt sends, since no earlier call can have preceded it; every later one inquires. A definite "not received" lets it send again, under the same id, after counting that send in a commit of
 *       its own - which the provider's idempotency makes the first send, not a second return. An unknown answer is never a re-send;</li>
 *   <li><strong>the record</strong>: the answer kept verbatim, and the first definite one taking the one-way edge.</li>
 * </ol>
 *
 * <p><strong>The platform executes; persons decided</strong>: every drive runs under {@link SecurityContext#enterSystem()}, the one place this drive claims to be the acting party. Two persons prepared and attested the return and a third committed it, each under their
 * own actor at the door; the provider's answer is the platform's record.
 *
 * <p><strong>Never throws.</strong> A failed claim is counted {@code finapp.accounting.close.leg.failed{leg=inquiry}} and ends the tick, from the first run on; a call that throws records nothing, counted {@code call_failed}, one warn line with the exception's class only - never a
 * reference, a provider's text or a figure - and the permit is left for the next claim, which inquires. A record that fails to commit is the same: the next claim asks again.
 *
 * <p><strong>Past the horizon, or the resends spent</strong>, nothing here gives up: it goes on asking. What runs out is the driver's permission to send; an operator sees {@code overdue} and {@code resendsExhausted} on the read and {@code
 * finapp.accounting.regulatory.attention}, and the {@code non-delivery} door opens on a definite "not received".
 */
@Slf4j
public final class RegulatorySubmissionDriver {

    static final String SPAN = "accounting.regulatory.inquire";

    private final RegulatorySubmissions submissions;
    private final AccountingTransactions transactions;
    private final RegulatorySubmissionMetrics metrics;
    private final CloseRunMetrics legs;
    private final AccountingFlowScope flow;
    private final Duration lease;
    private final Duration inquiryInterval;
    private final int perTick;

    /**
     * @param lease how long a claimed return's permit is held before another claimer may take it - longer than an adapter's timeout, so a live call is not raced
     * @param inquiryInterval how long an answer that decides nothing waits before it is asked again
     * @param perTick the most due returns one tick drives
     */
    public RegulatorySubmissionDriver(RegulatorySubmissions submissions, AccountingTransactions transactions, RegulatorySubmissionMetrics metrics, CloseRunMetrics legs, AccountingFlowScope flow, Duration lease,
            Duration inquiryInterval, int perTick) {
        this.submissions = Objects.requireNonNull(submissions, "submissions");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.legs = Objects.requireNonNull(legs, "legs");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.lease = positive(lease, "lease");
        this.inquiryInterval = positive(inquiryInterval, "inquiryInterval");
        if (perTick < 1) {
            throw new IllegalArgumentException("perTick must be positive: " + perTick);
        }
        this.perTick = perTick;
    }

    /**
     * {@code id} driven when its permit has come: the sending door's drive, after its transaction committed. A failed claim is counted and left to the leg: the return stands {@code SUBMITTED}.
     *
     * @return whether an answer was recorded
     */
    public boolean submitIfDue(RegulatorySubmissionId id) {
        Objects.requireNonNull(id, "id");
        return asThePlatform(() -> {
            Optional<Claimed> claimed;
            try {
                claimed = transactions.inTransaction(uow -> submissions.claim(uow, id, lease));
            } catch (RuntimeException failure) {
                legs.legFailed(AccountingCloseSchedule.INQUIRY_LEG);
                log.warn("A regulatory submission's claim failed: {}", failure.getClass().getSimpleName());
                return false;
            }
            return claimed.map(this::inItsFlow).orElse(false);
        });
    }

    /**
     * The inquiry leg's tick: up to the bound, the oldest due return claimed in a transaction of its own, then asked and recorded, each in its own return's flow. A failed claim is counted and ends the tick; a failed call is counted and the tick goes on.
     *
     * @return how many answers this tick recorded
     */
    public int inquireDue() {
        return asThePlatform(() -> {
            int recorded = 0;
            for (int taken = 0; taken < perTick; taken++) {
                Optional<Claimed> claimed;
                try {
                    claimed = transactions.inTransaction(uow -> submissions.claimNextDue(uow, lease));
                } catch (RuntimeException failure) {
                    legs.legFailed(AccountingCloseSchedule.INQUIRY_LEG);
                    log.warn("A regulatory submission's claim failed: {}", failure.getClass().getSimpleName());
                    return recorded;
                }
                if (claimed.isEmpty()) {
                    return recorded;
                }
                if (inItsFlow(claimed.get())) {
                    recorded++;
                }
            }
            return recorded;
        });
    }

    private boolean inItsFlow(Claimed claimed) {
        return flow.inFlow(SPAN, claimed.row().correlation(), () -> drive(claimed));
    }

    private boolean drive(Claimed claimed) {
        Optional<Answered> answered = called(() -> submissions.ask(claimed));
        if (answered.isEmpty()) {
            return false;
        }
        Optional<Recorded> recorded = record(claimed, answered.get());
        if (recorded.isEmpty()) {
            return false;
        }
        if (recorded.get().disposition() != Disposition.NOT_RECEIVED) {
            return true;
        }
        // A definite "nothing arrived under this id": the send is still owed. Counted and committed first, conditional on this attempt and the bound.
        boolean permitted;
        try {
            permitted = transactions.inTransaction(uow -> submissions.permitResend(uow, claimed));
        } catch (RuntimeException failure) {
            metrics.count("record_failed");
            log.warn("A regulatory submission's resend could not be counted: {}", failure.getClass().getSimpleName());
            return true;
        }
        if (!permitted) {
            metrics.count("resends_exhausted");
            return true;
        }
        metrics.count("resent");
        Optional<Answered> sent = called(() -> submissions.resend(claimed));
        sent.ifPresent(answer -> record(claimed, answer));
        return true;
    }

    /** A call on no transaction. One that throws records nothing: the permit stands, and the next claim inquires. The class only - never a reference, a provider's text or a figure. */
    private Optional<Answered> called(Supplier<Answered> call) {
        try {
            return Optional.of(call.get());
        } catch (RuntimeException failure) {
            metrics.count("call_failed");
            log.warn("A regulatory submission's call failed: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<Recorded> record(Claimed claimed, Answered answered) {
        Recorded recorded;
        try {
            recorded = transactions.inTransaction(uow -> submissions.record(uow, claimed, answered, inquiryInterval, claimed.row().correlation()));
        } catch (RuntimeException failure) {
            // The provider answered but the record did not commit: the next claim asks again and records its answer.
            metrics.count("record_failed");
            log.warn("A regulatory submission's answer could not be recorded: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
        metrics.count(switch (recorded.disposition()) {
            case ACKNOWLEDGED -> "acknowledged";
            case REJECTED -> "rejected";
            case AWAITING -> "unknown";
            case NOT_RECEIVED -> "not_received";
            case LATE -> "late";
            case CONTRARY -> "contrary";
        });
        if (recorded.anomalous()) {
            metrics.count("answer_anomaly");
        }
        return Optional.of(recorded);
    }

    /** {@code work} as the platform. */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private static <T> T asThePlatform(Supplier<T> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return work.get();
        }
    }

    private static Duration positive(Duration duration, String what) {
        Objects.requireNonNull(duration, what);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(what + " is positive");
        }
        return duration;
    }
}
