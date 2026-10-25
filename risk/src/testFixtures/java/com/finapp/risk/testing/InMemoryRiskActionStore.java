package com.finapp.risk.testing;

import com.finapp.platform.security.Actor;
import com.finapp.risk.ActionKind;
import com.finapp.risk.ActionOutcome;
import com.finapp.risk.ActionSource;
import com.finapp.risk.ActionStatus;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskActionId;
import com.finapp.risk.RiskActionStore;
import com.finapp.risk.RiskCase;
import com.finapp.risk.RiskCaseId;
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
import java.util.UUID;

/**
 * A {@link RiskActionStore} in memory for the hermetic suites (`P13-TSK-017`) - {@code risk V013}'s arbiters as the
 * statements apply them: the case's proposal under the one-live unique (a live twin of (case, kind, target) is no row; a
 * second id throws, as a primary-key collision does), every edge conditional on the state it leaves, T-act's claim only
 * of a permit that has come on the database clock {@link #now} (which the suite moves), moved a lease ahead with one
 * attempt counted, the record once. <strong>Not modelled, deliberately</strong>: the trigger's proposability and target
 * rules and the four-eyes {@code CHECK} - the domain's own refusals are what the hermetic suites prove, each alone; the
 * database's are {@code RiskActionDatabaseTest}'s (written and owed, C5). {@link #log} records each operation in order.
 * Single-threaded; {@link #snapshot}/{@link #restore} let a suite's fake transaction roll a failed act back.
 */
public final class InMemoryRiskActionStore implements RiskActionStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-25T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    private Map<RiskActionId, RiskAction> rows = new LinkedHashMap<>();

    /** Every action stored, in insert order. */
    public List<RiskAction> all() {
        return List.copyOf(rows.values());
    }

    /** A copy of the rows - a fake transaction's begin. */
    public Map<RiskActionId, RiskAction> snapshot() {
        return new LinkedHashMap<>(rows);
    }

    /** The rows put back as {@code snapshot} held them - a fake transaction's rollback. */
    public void restore(Map<RiskActionId, RiskAction> snapshot) {
        rows = new LinkedHashMap<>(snapshot);
    }

    /** Puts {@code action} in place as a writer that bypassed the domain would - a suite's planted state. */
    public void plant(RiskAction action) {
        rows.put(action.id(), action);
    }

    /** Whether an action of {@code caseId} stands {@code PROPOSED} - {@code InMemoryRiskCaseStore.actionStands}'s wiring. */
    public boolean proposalStands(RiskCaseId caseId) {
        return rows.values().stream().anyMatch(row -> row.caseId().filter(caseId::equals).isPresent()
                && row.status() == ActionStatus.PROPOSED);
    }

    /** The action an override issued, if any - {@code InMemoryDecisionOverrideStore.actionOf}'s wiring. */
    public Optional<RiskAction> ofOverride(DecisionOverrideId override) {
        return rows.values().stream().filter(row -> row.overrideId().filter(override::equals).isPresent())
                .findFirst();
    }

    @Override
    public Optional<RiskAction> insertProposed(Connection unitOfWork, NewProposal proposal) {
        log.add("insertProposed " + proposal.kind());
        if (rows.containsKey(proposal.id())) {
            throw new IllegalStateException("risk_action_pk: a primary-key collision is never a conflict");
        }
        if (live(proposal.caseId(), proposal.kind(), proposal.target()).isPresent()) {
            return Optional.empty();
        }
        RiskAction born = new RiskAction(proposal.id(), ActionSource.CASE, Optional.of(proposal.caseId()),
                Optional.empty(), proposal.kind(), proposal.kind().target(), proposal.target(), proposal.reasonCode(),
                ActionStatus.PROPOSED, RiskCase.Person.of(proposal.proposer()), Optional.of(now), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                Optional.empty(), proposal.correlationId());
        rows.put(born.id(), born);
        return Optional.of(born);
    }

    @Override
    public RiskAction insertIssued(Connection unitOfWork, NewIssue issue) {
        log.add("insertIssued " + issue.kind());
        RiskActionId id = RiskActionId.ofOverride(issue.override());
        if (rows.containsKey(id)) {
            throw new IllegalStateException("risk_action_pk: an override issues one action");
        }
        RiskAction born = new RiskAction(id, ActionSource.OVERRIDE, Optional.empty(), Optional.of(issue.override()),
                issue.kind(), issue.kind().target(), issue.target(), issue.reasonCode(), ActionStatus.APPROVED,
                issue.proposer(), Optional.of(now), Optional.of(issue.decider()), Optional.of(now), Optional.empty(),
                Optional.of(now), 0, Optional.empty(), Optional.empty(), Optional.empty(), issue.correlationId());
        rows.put(id, born);
        return born;
    }

    @Override
    public Optional<RiskAction> find(Connection unitOfWork, RiskActionId id) {
        log.add("find");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskAction> lock(Connection unitOfWork, RiskActionId id) {
        log.add("lock action");
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public Optional<RiskAction> findLive(Connection unitOfWork, RiskCaseId caseId, ActionKind kind, UUID target) {
        log.add("findLive");
        return live(caseId, kind, target);
    }

    @Override
    public List<RiskAction> ofCase(Connection unitOfWork, RiskCaseId caseId) {
        log.add("ofCase");
        return rows.values().stream().filter(row -> row.caseId().filter(caseId::equals).isPresent()).toList();
    }

    @Override
    public Optional<RiskAction> approve(Connection unitOfWork, RiskActionId id, Actor approver, UUID decisionAction) {
        log.add("approve action");
        RiskAction row = rows.get(id);
        if (row == null || row.status() != ActionStatus.PROPOSED) {
            return Optional.empty();
        }
        RiskAction approved = with(row, ActionStatus.APPROVED, Optional.of(RiskCase.Person.of(approver)),
                Optional.of(now), Optional.of(decisionAction), Optional.of(now), 0, Optional.empty(), Optional.empty(),
                Optional.empty());
        rows.put(id, approved);
        return Optional.of(approved);
    }

    @Override
    public Optional<RiskAction> reject(Connection unitOfWork, RiskActionId id, Actor rejecter, UUID decisionAction) {
        log.add("reject action");
        RiskAction row = rows.get(id);
        if (row == null || row.status() != ActionStatus.PROPOSED) {
            return Optional.empty();
        }
        RiskAction rejected = with(row, ActionStatus.REJECTED, Optional.of(RiskCase.Person.of(rejecter)),
                Optional.of(now), Optional.of(decisionAction), Optional.empty(), 0, Optional.empty(), Optional.empty(),
                Optional.empty());
        rows.put(id, rejected);
        return Optional.of(rejected);
    }

    @Override
    public Optional<RiskAction> claim(Connection unitOfWork, RiskActionId id, Duration lease) {
        log.add("claim");
        Objects.requireNonNull(lease, "lease");
        return Optional.ofNullable(rows.get(id)).filter(this::due).map(row -> claimed(row, lease));
    }

    @Override
    public Optional<RiskAction> claimNextDue(Connection unitOfWork, Duration lease) {
        log.add("claimNextDue");
        Objects.requireNonNull(lease, "lease");
        return rows.values().stream().filter(this::due)
                .min(Comparator.comparing((RiskAction row) -> row.nextAttemptAt().orElseThrow())
                        .thenComparing(row -> row.id().value()))
                .map(row -> claimed(row, lease));
    }

    @Override
    public Optional<RiskAction> conclude(Connection unitOfWork, RiskActionId id, ActionOutcome outcome) {
        log.add("conclude");
        RiskAction row = rows.get(id);
        if (row == null || row.status() != ActionStatus.DISPATCHED) {
            return Optional.empty();
        }
        RiskAction concluded = with(row, RiskAction.concludedBy(outcome), row.decidedBy(), row.decidedAt(),
                row.decisionAction(), Optional.empty(), row.attempts(), row.dispatchedAt(), Optional.of(outcome),
                Optional.of(now));
        rows.put(id, concluded);
        return Optional.of(concluded);
    }

    // ------------------------------------------------------------------ plumbing

    private boolean due(RiskAction row) {
        return row.status().permitted() && row.nextAttemptAt().filter(at -> !at.isAfter(now)).isPresent();
    }

    private RiskAction claimed(RiskAction row, Duration lease) {
        RiskAction claimed = with(row, ActionStatus.DISPATCHED, row.decidedBy(), row.decidedAt(), row.decisionAction(),
                Optional.of(now.plus(lease)), row.attempts() + 1, row.dispatchedAt().or(() -> Optional.of(now)),
                Optional.empty(), Optional.empty());
        rows.put(row.id(), claimed);
        return claimed;
    }

    private Optional<RiskAction> live(RiskCaseId caseId, ActionKind kind, UUID target) {
        return rows.values().stream().filter(row -> row.caseId().filter(caseId::equals).isPresent()
                && row.kind() == kind && row.target().equals(target) && row.status().live()).findFirst();
    }

    private static RiskAction with(RiskAction row, ActionStatus status, Optional<RiskCase.Person> decidedBy,
            Optional<Instant> decidedAt, Optional<UUID> decisionAction, Optional<Instant> nextAttemptAt, int attempts,
            Optional<Instant> dispatchedAt, Optional<ActionOutcome> outcome, Optional<Instant> concludedAt) {
        return new RiskAction(row.id(), row.source(), row.caseId(), row.overrideId(), row.kind(), row.targetKind(),
                row.target(), row.reasonCode(), status, row.proposedBy(), row.proposedAt(), decidedBy, decidedAt,
                decisionAction, nextAttemptAt, attempts, dispatchedAt, outcome, concludedAt, row.correlationId());
    }
}
