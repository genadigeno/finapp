package com.finapp.payments;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks about the durable hints (`P15-TSK-036`, ADR-0083): the door's immediate inquiry and the leaderless sweep are the same
 * path, so a hint whose first inquiry failed, or whose instance crashed, is finished by whichever instance comes next.
 *
 * <p>Leaderless by design: every instance reads the due hints without a lock, a conditional permit on the database's clock
 * decides who asks this tick (a wire-noise arbiter), and the applier's row locks and conditionals decide what happens. A
 * hint concluded twice concludes once; an effect applied and its conclusion lost costs one more converging inquiry.
 * Registered in {@code DISTRIBUTED_EXECUTION.md} section 3.
 */
@Slf4j
public final class CallbackHintResolution {

    /**
     * The sweep's pacing.
     *
     * @param firstAsk how long a new hint waits before the sweep may ask, longer than the door's own inquiry takes
     * @param retryAfter the permit's base: the wait after the first sweep ask, doubling each time after that
     * @param retryCap the longest the permit is ever stamped forward
     * @param giveUpAfter how old an unanswered hint is when it is abandoned, loudly
     * @param refuteAfter per kind, how old a hint must be before the provider's "no such thing" is believed: inside it, the
     *     answer may be a read model lagging, and the hint stays open
     */
    public record Config(
            Duration firstAsk, Duration retryAfter, Duration retryCap, Duration giveUpAfter,
            Map<CallbackHint.Kind, Duration> refuteAfter, int batchSize) {

        public Config {
            for (Duration bound : List.of(firstAsk, retryAfter, retryCap, giveUpAfter)) {
                Objects.requireNonNull(bound, "every bound must be given");
                if (bound.isNegative() || bound.isZero()) {
                    throw new IllegalArgumentException("every hint bound must be positive");
                }
            }
            refuteAfter = Map.copyOf(refuteAfter);
            for (CallbackHint.Kind kind : CallbackHint.Kind.values()) {
                Duration bound = refuteAfter.get(kind);
                if (bound == null || bound.isNegative() || bound.isZero()) {
                    throw new IllegalArgumentException("every kind needs a positive refutation bound: " + kind);
                }
            }
            if (batchSize < 1) {
                throw new IllegalArgumentException("batchSize must be at least 1");
            }
        }
    }

    /** One tick's tally: telemetry, never the count of record. */
    public record SweepResult(int candidates, int asked, int concluded, int expired) {}

    private final CallbackHintStore<Connection> hints;
    private final Map<CallbackHint.Kind, HintedInquiry> inquiries;
    private final Config config;
    private final IdGenerator ids;
    private final TransactionRunner transactions;
    private final CallbackHintObserver observer;

    public CallbackHintResolution(
            CallbackHintStore<Connection> hints, List<HintedInquiry> inquiries, Config config, IdGenerator ids,
            TransactionRunner transactions, CallbackHintObserver observer) {
        this.hints = Objects.requireNonNull(hints, "hints must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.observer = Objects.requireNonNull(observer, "observer must not be null");
        Map<CallbackHint.Kind, HintedInquiry> byKind = new EnumMap<>(CallbackHint.Kind.class);
        for (HintedInquiry inquiry : inquiries) {
            inquiry.kinds().forEach(kind -> byKind.put(kind, inquiry));
        }
        this.inquiries = Map.copyOf(byKind);
    }

    /** How long a door's new hint stands before the sweep may ask. */
    public Duration firstAsk() {
        return config.firstAsk();
    }

    /**
     * Asks once and concludes what the answer concludes. Runs as the caller's actor and in the caller's correlation.
     *
     * <p>A provider's "no such thing" inside the kind's bound may be its read model lagging, and for a dispute nothing else can
     * ever create the row, so the hint stays open and the answer reports {@code UNDECIDED}: only a denial that has outlived its
     * bound is a refutation, and only then is it loud or counted.
     */
    public HintedOutcome resolve(CallbackHint hint) {
        Objects.requireNonNull(hint, "hint must not be null");
        HintedInquiry inquiry = inquiries.get(hint.kind());
        if (inquiry == null) {
            log.warn("No rail on this instance answers a {} hint; it stays for one that does", hint.kind());
            return HintedOutcome.UNDECIDED;
        }
        HintedOutcome outcome = inquiry.inquire(hint);
        if (outcome == HintedOutcome.REFUTED) {
            boolean past = transactions.inTransaction(
                    uow -> hints.concludeRefuted(uow, hint.id(), config.refuteAfter().get(hint.kind())));
            if (!past) {
                return HintedOutcome.UNDECIDED;
            }
            log.warn("The provider has denied a signed {} callback for longer than its bound (hint {}): nothing moved. A refuted"
                    + " callback is what a compromised webhook key looks like", hint.kind(), hint.id());
            return HintedOutcome.REFUTED;
        }
        if (outcome.concludes()) {
            transactions.inTransaction(uow -> hints.conclude(uow, hint.id(), outcome));
        }
        return outcome;
    }

    /** One tick: the old abandoned, then every due hint permitted and asked, each contained. */
    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        int expired = transactions.inTransaction(uow -> hints.expire(uow, config.giveUpAfter(), config.batchSize()));
        if (expired > 0) {
            log.warn("{} callback hints were never answered within their bound and are abandoned; each callback's evidence"
                    + " stands and reconciliation is the backstop", expired);
            for (int abandoned = 0; abandoned < expired; abandoned++) {
                observer.broke(HintedOutcome.EXPIRED);
            }
        }
        List<CallbackHint> due = transactions.inTransaction(uow -> hints.findDue(uow, config.batchSize()));
        int asked = 0;
        int concluded = 0;
        for (CallbackHint hint : due) {
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(ids)))) {
                boolean permitted =
                        transactions.inTransaction(
                                uow -> hints.renewPermit(uow, hint, config.retryAfter(), config.retryCap()));
                if (!permitted) {
                    continue;
                }
                asked++;
                HintedOutcome outcome = resolve(hint);
                if (outcome.concludes()) {
                    concluded++;
                }
                if (outcome.isBreak()) {
                    observer.broke(outcome);
                }
            } catch (RuntimeException oneRowFailed) {
                // Identifier and failure class only - never provider bytes (INV-AUD-02).
                log.warn("callback hint {} could not be resolved: {}", hint.id(), oneRowFailed.getClass().getSimpleName());
            }
        }
        return new SweepResult(due.size(), asked, concluded, expired);
    }
}
