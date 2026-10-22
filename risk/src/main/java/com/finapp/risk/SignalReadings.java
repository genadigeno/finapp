package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The readings an evaluation takes from the signals (`P13-TSK-005`; {@code PHASE_13_PLAN.md} section 12.5) - the
 * signal half of `-004`'s {@code RiskInputs}, each one bounded query on the caller's unit of work (T-eval's, after
 * the caller's locks) and one range scan on {@code risk_signal_by_subject}.
 *
 * <p><strong>The named inputs, and their source here</strong> (the design's mapping, 2026-10-23): {@code
 * recentCredentialChange} = {@link #withinLookback} of {@link SignalKind#CREDENTIAL_CHANGED} on the identity;
 * {@code recentRecovery} = the same of {@link SignalKind#RECOVERY_COMPLETED}; {@code deviceNovelty} =
 * {@link #isNovel} of the device's hash; {@code accountAge} = {@link #age} from {@link SignalKind#ACCOUNT_OPENED};
 * a merchant's age and status from {@link SignalKind#MERCHANT_ONBOARDED}, a beneficiary's standing from its latest
 * {@code BENEFICIARY_*} signal ({@link #latest}). {@code screeningHit} is {@link ScreeningClearances}' answer, not a
 * signal. <strong>Two of the plan's inputs have no source today</strong> and this class does not pretend otherwise:
 * {@code recentAuthFailures} - identity's failure event names no identity ({@code INV-IDN-07}), so `-004` reads
 * identity's failures through a port or not at all - and {@code kycStatus} - kyc publishes no case outcome.
 *
 * <p>Every instant is the caller's reading of the database clock after its lock ({@code RISK_LIFECYCLES.md} section
 * 3): this class takes {@code now} as a parameter and reads no clock of its own.
 */
public final class SignalReadings {

    private final RiskSignalStore store;

    public SignalReadings(RiskSignalStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** How many signals of {@code kind} about {@code subject} were observed within {@code lookback} before {@code now}. */
    public int countWithin(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant now, Duration lookback) {
        return store.countSince(unitOfWork, subject, kind, since(now, lookback));
    }

    /** Whether a signal of {@code kind} about {@code subject} was observed within {@code lookback} before {@code now}. */
    public boolean withinLookback(
            Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant now, Duration lookback) {
        return countWithin(unitOfWork, subject, kind, now, lookback) > 0;
    }

    /** The latest signal of {@code kind} about {@code subject}. */
    public Optional<RiskSignal> latest(Connection unitOfWork, SignalSubject subject, SignalKind kind) {
        return store.latest(unitOfWork, subject, kind);
    }

    /** How long since the first signal of {@code kind} about {@code subject} - empty when there is none. */
    public Optional<Duration> age(Connection unitOfWork, SignalSubject subject, SignalKind kind, Instant now) {
        Objects.requireNonNull(now, "now");
        return store.firstObservedAt(unitOfWork, subject, kind)
                .map(first -> first.isAfter(now) ? Duration.ZERO : Duration.between(first, now));
    }

    /** Whether nothing at all has been observed about {@code subject} - a device never seen is novel. */
    public boolean isNovel(Connection unitOfWork, SignalSubject subject) {
        return !store.seen(unitOfWork, subject);
    }

    private static Instant since(Instant now, Duration lookback) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(lookback, "lookback");
        if (lookback.isNegative()) {
            throw new IllegalArgumentException("a lookback is never negative");
        }
        return now.minus(lookback);
    }
}
