package com.finapp.risk.testing;

import com.finapp.platform.security.Actor;
import com.finapp.risk.DecisionOverride;
import com.finapp.risk.DecisionOverrideId;
import com.finapp.risk.DecisionOverrideStore;
import com.finapp.risk.OverrideStatus;
import com.finapp.risk.RiskAction;
import com.finapp.risk.RiskAssessmentId;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A {@link DecisionOverrideStore} in memory for the hermetic suites (`P13-TSK-012`; folded by `P13-TSK-017`) -
 * {@code risk V009}'s arbiters as the statements apply them: one standing override per assessment ({@code ON CONFLICT
 * DO NOTHING} against the partial uniques), every edge conditional on the state it moves from, the approval naming the
 * effect as its action. The issued action's permit, attempts and outcome are read beside the override from the action
 * machine's row ({@link #actionOf} - the suite wires {@code InMemoryRiskActionStore::ofOverride}), as the store's left
 * join reads them. The database's clock is {@link #now}, which the suite moves. <strong>Not modelled,
 * deliberately</strong>: the four-eyes {@code CHECK} and the birth trigger's C7 and effect rules - the domain's own
 * refusals are what the hermetic suites prove, each alone; the database's are {@code DecisionOverrideDatabaseTest}'s
 * (written and owed, C5). Single-threaded; {@link #snapshot} and {@link #restore} let a suite's fake transaction roll a
 * failed act back.
 */
public final class InMemoryDecisionOverrideStore implements DecisionOverrideStore {

    /** The database's clock, as the suite sets it. */
    public Instant now = Instant.parse("2026-10-24T12:00:00Z");

    /** Each operation, in the order it was asked. */
    public final List<String> log = new ArrayList<>();

    /** The action machine's row an override issued - read beside it; none until the suite wires its action store. */
    public Function<DecisionOverrideId, Optional<RiskAction>> actionOf = id -> Optional.empty();

    private Map<DecisionOverrideId, DecisionOverride> rows = new LinkedHashMap<>();

    /** Every override stored, in insert order, with its action's state. */
    public List<DecisionOverride> all() {
        return rows.values().stream().map(this::read).toList();
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
        return Optional.ofNullable(rows.get(id)).map(this::read);
    }

    @Override
    public Optional<DecisionOverride> lock(Connection unitOfWork, DecisionOverrideId id) {
        log.add("lock");
        return Optional.ofNullable(rows.get(id)).map(this::read);
    }

    @Override
    public List<DecisionOverride> ofAssessment(Connection unitOfWork, RiskAssessmentId assessment) {
        log.add("ofAssessment");
        return rows.values().stream().filter(row -> row.assessment().equals(assessment)).map(this::read).toList();
    }

    @Override
    public Optional<DecisionOverride> approve(Connection unitOfWork, DecisionOverrideId id, Actor approver,
            String reason) {
        log.add("approve");
        DecisionOverride row = rows.get(id);
        if (row == null || row.status() != OverrideStatus.PROPOSED) {
            return Optional.empty();
        }
        DecisionOverride approved = new DecisionOverride(row.id(), row.assessment(), OverrideStatus.APPROVED,
                row.effect(), row.target(), row.reasonCode(), row.reason(), row.requiresSecondPerson(),
                row.proposedBy(), row.proposedByType(), row.proposedAt(), Optional.of(approver.id()),
                Optional.of(approver.type()), Optional.of(now), Optional.of(reason), Optional.of(row.effect()),
                Optional.empty(), Optional.empty(), 0, Optional.empty(), row.correlationId());
        rows.put(id, approved);
        return Optional.of(read(approved));
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

    /** {@code row} with its issued action's state, as the store's left join reads it. */
    private DecisionOverride read(DecisionOverride row) {
        Optional<RiskAction> action = actionOf.apply(row.id());
        if (action.isEmpty()) {
            return row;
        }
        RiskAction issued = action.get();
        return new DecisionOverride(row.id(), row.assessment(), row.status(), row.effect(), row.target(),
                row.reasonCode(), row.reason(), row.requiresSecondPerson(), row.proposedBy(), row.proposedByType(),
                row.proposedAt(), row.decidedBy(), row.decidedByType(), row.decidedAt(), row.decisionReason(),
                row.action(), issued.outcome(), issued.nextAttemptAt(), issued.attempts(), issued.concludedAt(),
                row.correlationId());
    }
}
