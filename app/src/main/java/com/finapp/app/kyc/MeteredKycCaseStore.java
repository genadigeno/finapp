package com.finapp.app.kyc;

import com.finapp.app.telemetry.KycMeters;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Counts case throughput at the store seam — {@code finapp.kyc.case} by outcome
 * (`P2-TSK-020`, `PHASE_2_PLAN.md` §10).
 *
 * <h2>The seam, not the doors</h2>
 *
 * <p>Cases are created through <strong>three</strong> doors ({@code KycCaseService}, the
 * registration consumer, {@code KybService}) and decided through <strong>two</strong>
 * ({@code DecisionRecording}'s reviewer and automatic paths) — and every one of them goes
 * through this one bean. Counting per door is five increment sites, one of them in a module
 * with no metrics dependency, and a sixth door added later is a count silently lost. Counting
 * here, the meter's semantics are exactly the arbiters' own verdicts:
 *
 * <ul>
 *   <li><strong>{@code opened}</strong> when {@code openOrConverge} answers {@code created} —
 *       the one-open-case index's decision, so a converged retry, a duplicate delivery and the
 *       nine losers of a ten-way race are never counted (`INV-KYC-03`'s discipline, at the
 *       meter).
 *   <li><strong>{@code approved}/{@code rejected}</strong> when a {@code moveStatus} into a
 *       terminal status wins — the conditional's row count already makes exactly one of N
 *       deciders the winner, so one decision is one increment whichever door recorded it.
 * </ul>
 *
 * <p>The terminal tag values are derived from the machine ({@link KycCaseStatus#isTerminal()}),
 * so a state added to the enum registers its series the day it becomes terminal rather than
 * when somebody remembers.
 *
 * <h2>What a counter here is, and is not</h2>
 *
 * <p>A per-instance signal, never a record: increments happen inside the caller's transaction,
 * so a transaction that rolls back after a won write can overcount by one. That is accepted —
 * the trail and the table are the record, the meter answers <em>how many, roughly, lately</em>
 * — and it is the same stance {@code CheckOutcomeTrail} already takes. Dashboards aggregate
 * with {@code sum(rate(...))} across the fleet.
 */
final class MeteredKycCaseStore implements KycCaseStore<Connection> {

    private final KycCaseStore<Connection> delegate;
    private final Counter opened;
    private final Map<KycCaseStatus, Counter> decided;

    MeteredKycCaseStore(KycCaseStore<Connection> delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        // Eager, at construction (P1-TSK-029): a freshly started instance publishes every
        // outcome's series at zero, so an alert on a rate has something to evaluate before the
        // first case ever opens.
        this.opened = KycMeters.caseOutcome(registry, "opened");
        this.decided = new EnumMap<>(KycCaseStatus.class);
        for (KycCaseStatus status : KycCaseStatus.values()) {
            if (status.isTerminal()) {
                decided.put(
                        status,
                        KycMeters.caseOutcome(
                                registry, status.name().toLowerCase(Locale.ROOT)));
            }
        }
    }

    @Override
    public Opening openOrConverge(Connection unitOfWork, KycCase fresh) {
        Opening opening = delegate.openOrConverge(unitOfWork, fresh);
        if (opening.created()) {
            opened.increment();
        }
        return opening;
    }

    @Override
    public boolean moveStatus(
            Connection unitOfWork,
            KycCaseId caseId,
            KycCaseStatus from,
            KycCaseStatus to,
            Instant at) {
        boolean won = delegate.moveStatus(unitOfWork, caseId, from, to, at);
        if (won && to.isTerminal()) {
            decided.get(to).increment();
        }
        return won;
    }

    @Override
    public Optional<KycCase> findOpenFor(Connection unitOfWork, UUID customerId) {
        return delegate.findOpenFor(unitOfWork, customerId);
    }

    @Override
    public Optional<KycCase> findById(Connection unitOfWork, KycCaseId caseId) {
        return delegate.findById(unitOfWork, caseId);
    }

    @Override
    public Optional<KycCase> findLatestFor(Connection unitOfWork, UUID customerId) {
        return delegate.findLatestFor(unitOfWork, customerId);
    }

    @Override
    public boolean moveToReadyForDecision(
            Connection unitOfWork, KycCaseId caseId, KycCaseStatus from, Instant at) {
        // Never terminal by construction - the readiness move is the decision's precondition,
        // not the decision - so nothing is counted here.
        return delegate.moveToReadyForDecision(unitOfWork, caseId, from, at);
    }
}
