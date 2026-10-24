package com.finapp.risk.testing;

import com.finapp.platform.security.Actor;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.DecisionOverrideStore;
import com.finapp.risk.OverrideStatus;
import com.finapp.risk.RiskAssessmentId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A {@link DecisionOverrideStore} in memory for the hermetic suites (`P13-TSK-012`) - {@code risk V009}'s arbiters as
 * the statements apply them: one standing override per assessment ({@code ON CONFLICT DO NOTHING} against the partial
 * uniques), every edge conditional on the state it moves from, the approval stamping the action and its permit, the
 * claim the oldest due permit re-stamped with an attempt counted, the outcome recorded once. The database's clock is
 * {@link #now}, which the suite moves. <strong>Not modelled, deliberately</strong>: the four-eyes {@code CHECK} and the
 * birth trigger's C7 and effect rules - the domain's own refusals are what the hermetic suites prove, each alone; the
 * database's are {@code DecisionOverrideDatabaseTest}'s (written and owed, C5). Single-threaded; {@link #snapshot} and
 * {@link #restore} let a suite's fake transaction roll a failed act back.
 */
public final class InMemoryDecisionOverrideStore implements DecisionOverrideStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-24T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    private Map<DecisionOverrideId, DecisionOverride> rows = new LinkedHashMap<>();

    /** Every override stored, in insert order. */
    public List<DecisionOverride> all() {
        return List.copyOf(rows.values());
    }

    /** A copy of the rows - a fake transaction's begin. */
    public Map<DecisionOverrideId, DecisionOverride> snapshot() {
        return new LinkedHashMap<>(rows);
    }

    /** The rows put back as {@code snapshot} held them - a fake transaction's rollback. */
    public void restore(Map<DecisionOverrideId, DecisionOverride> snapshot) {
        rows = new LinkedHashMap<>(snapshot);
    }

    /** Puts {@code override} in place as a writer that bypassed the domain would - a suite's planted state. */
    public void plant(DecisionOverride override) {
        rows.put(override.id(), override);
    }

    @Override
    public boolean insert(Connection unitOfWork, NewOverride proposal) {
        log.add("insert");
        Objects.requireNonNull(proposal, "proposal");
        boolean standing = rows.values().stream().anyMatch(row -> row.assessment().equals(proposal.assessment())
                && row.status() != OverrideStatus.REJECTED);
        if (standing || rows.containsKey(proposal.id())) {
            return false;
        }
        rows.put(proposal.id(), new DecisionOverride(proposal.id(), proposal.assessment(), OverrideStatus.PROPOSED,
                proposal.effect(), proposal.target(), proposal.reasonCode(), proposal.reason(),
                proposal.requiresSecondPerson(), proposal.proposer().id(), proposal.proposer().type(), Optional.of(now),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, Optional.empty(), proposal.correlation().value()));
        return true;
    }

    @Override
    public Optional<DecisionOverride> find(Connection unitOfWork, DecisionOverrideId id) {
        log.add("find");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<DecisionOverride> lock(Connection unitOfWork, DecisionOverrideId id) {
        log.add("lock");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<DecisionOverride> ofAssessment(Connection unitOfWork, RiskAssessmentId assessment) {
        log.add("ofAssessment");
        return rows.values().stream().filter(row -> row.assessment().equals(assessment)).toList();
    }

    @Override
    public Optional<DecisionOverride> approve(Connection unitOfWork, DecisionOverrideId id, Actor approver,
            String reason, Duration lease) {
        log.add("approve");
        DecisionOverride row = rows.get(id);
        if (row == null || row.status() != OverrideStatus.PROPOSED) {
            return Optional.empty();
        }
        boolean dispatches = row.effect().dispatches();
        DecisionOverride approved = new DecisionOverride(row.id(), row.assessment(), OverrideStatus.APPROVED,
                row.effect(), row.target(), row.reasonCode(), row.reason(), row.requiresSecondPerson(),
                row.proposedBy(), row.proposedByType(), row.proposedAt(), Optional.of(approver.id()),
                Optional.of(approver.type()), Optional.of(now), Optional.of(reason), Optional.of(row.effect()),
                Optional.empty(), dispatches ? Optional.of(now.plus(lease)) : Optional.empty(), dispatches ? 1 : 0,
                Optional.empty(), row.correlationId());
        rows.put(id, approved);
        return Optional.of(approved);
    }

    @Override
    public Optional<DecisionOverride> reject(Connection unitOfWork, DecisionOverrideId id, Actor rejecter,
            String reason) {
        log.add("reject");
        DecisionOverride row = rows.get(id);
        if (row == null || row.status() != OverrideStatus.PROPOSED) {
            return Optional.empty();
        }
        DecisionOverride rejected = new DecisionOverride(row.id(), row.assessment(), OverrideStatus.REJECTED,
                row.effect(), row.target(), row.reasonCode(), row.reason(), row.requiresSecondPerson(),
                row.proposedBy(), row.proposedByType(), row.proposedAt(), Optional.of(rejecter.id()),
                Optional.of(rejecter.type()), Optional.of(now), Optional.of(reason), Optional.empty(),
                Optional.empty(), Optional.empty(), 0, Optional.empty(), row.correlationId());
        rows.put(id, rejected);
        return Optional.of(rejected);
    }

    @Override
    public Optional<DecisionOverride> claimDue(Connection unitOfWork, Duration lease) {
        log.add("claimDue");
        Optional<DecisionOverride> due = rows.values().stream()
                .filter(row -> row.actionDueAt().filter(at -> !at.isAfter(now)).isPresent())
                .min(Comparator.comparing((DecisionOverride row) -> row.actionDueAt().orElseThrow())
                        .thenComparing(row -> row.id().value()));
        return due.map(row -> {
            DecisionOverride claimed = withAction(row, row.actionOutcome(), Optional.of(now.plus(lease)),
                    row.actionAttempts() + 1, row.actionConcludedAt());
            rows.put(row.id(), claimed);
            return claimed;
        });
    }

    @Override
    public boolean conclude(Connection unitOfWork, DecisionOverrideId id, ActionOutcome outcome) {
        log.add("conclude");
        DecisionOverride row = rows.get(id);
        if (row == null || row.status() != OverrideStatus.APPROVED || row.actionOutcome().isPresent()
                || row.actionDueAt().isEmpty()) {
            return false;
        }
        rows.put(id, withAction(row, Optional.of(outcome), Optional.empty(), row.actionAttempts(), Optional.of(now)));
        return true;
    }

    private static DecisionOverride withAction(DecisionOverride row, Optional<ActionOutcome> outcome,
            Optional<Instant> dueAt, int attempts, Optional<Instant> concludedAt) {
        return new DecisionOverride(row.id(), row.assessment(), row.status(), row.effect(), row.target(),
                row.reasonCode(), row.reason(), row.requiresSecondPerson(), row.proposedBy(), row.proposedByType(),
                row.proposedAt(), row.decidedBy(), row.decidedByType(), row.decidedAt(), row.decisionReason(),
                row.action(), outcome, dueAt, attempts, concludedAt, row.correlationId());
    }
}
