package com.finapp.app.risk;

import com.finapp.risk.Cases;
import com.finapp.risk.ProfileRecompute;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskCaseStore;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * An approved disposition's {@code PROHIBITED}, applied to the customer risk profile <strong>after the case's
 * transaction commits</strong> (`P13-TSK-016`; {@code DESIGN-016} D3, D7; {@code INV-RSK-10}; `-013`'s lock order) - in
 * a transaction of its own holding no case row: the profile leg takes the profile head (R8) before its folds take a case
 * (R4), so a case act holding R4 must never wait on R8, and the approval therefore writes the case alone and this writes
 * the profile alone ({@link ProfileRecompute#applyDisposition}: R8, one version per disposition).
 *
 * <p>At least once: {@link #applyIfDue} after the approving door's commit - and again on every replay of its key - and
 * {@link #applyDue} on the case leg of {@code RiskMonitoringSchedule}, for a door whose instance died between the two
 * transactions. Ten instances applying one disposition write one version (the head's lock and
 * {@code customer_risk_profile_version_one_per_disposition}).
 */
@Slf4j
public final class CaseProhibitionDriver {

    private final Cases cases;
    private final ProfileRecompute recompute;
    private final RiskTransactions transactions;
    private final IdGenerator ids;
    private final int perTick;
    private final RiskFlowScope flow;

    /** @param perTick the most prohibitions one tick of the case leg applies - no spans ({@link RiskFlowScope#NONE}) */
    public CaseProhibitionDriver(Cases cases, ProfileRecompute recompute, RiskTransactions transactions,
            IdGenerator ids, int perTick) {
        this(cases, recompute, transactions, ids, perTick, RiskFlowScope.NONE);
    }

    /**
     * @param perTick the most prohibitions one tick of the case leg applies
     * @param flow each prohibition's version write recorded {@code risk.profile.recompute}, no attribute (`P13-TSK-024`)
     */
    public CaseProhibitionDriver(Cases cases, ProfileRecompute recompute, RiskTransactions transactions,
            IdGenerator ids, int perTick, RiskFlowScope flow) {
        this.flow = Objects.requireNonNull(flow, "flow");
        this.cases = Objects.requireNonNull(cases, "cases");
        this.recompute = Objects.requireNonNull(recompute, "recompute");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.ids = Objects.requireNonNull(ids, "ids");
        if (perTick < 1) {
            throw new IllegalArgumentException("the prohibitions per tick must be positive: " + perTick);
        }
        this.perTick = perTick;
    }

    /**
     * {@code caseId}'s prohibition applied when its approved disposition sets one - the door's follow-up, on no
     * transaction of the caller's. A failure is logged (ids and class only) and left to the case leg: the approval has
     * committed, and its answer stands.
     */
    public void applyIfDue(RiskCaseId caseId, CorrelationId correlation) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(correlation, "correlation");
        try {
            flow.sweep(RiskSpans.PROFILE_RECOMPUTE, () -> transactions.inTransaction(uow -> {
                Optional<RiskCaseStore.PendingProhibition> due = cases.prohibitionOf(uow, caseId);
                due.ifPresent(pending -> recompute.applyDisposition(uow, pending.partyId(), pending.dispositionId(),
                        correlation));
                return null;
            }));
        } catch (RuntimeException failure) {
            log.warn("Risk case prohibition deferred to the case leg: {}", failure.getClass().getSimpleName());
        }
    }

    /** The case leg's re-drive: up to {@code perTick} approved prohibitions with no version, each in its own transaction. */
    public int applyDue() {
        List<RiskCaseStore.PendingProhibition> due = flow.sweep(RiskSpans.PROFILE_RECOMPUTE,
                () -> transactions.inTransaction(uow -> cases.prohibitionsDue(uow, perTick)));
        int applied = 0;
        for (RiskCaseStore.PendingProhibition pending : due) {
            CorrelationId correlation = CorrelationId.generate(ids);
            flow.sweep(RiskSpans.PROFILE_RECOMPUTE, () -> transactions.inTransaction(uow -> recompute.applyDisposition(
                    uow, pending.partyId(), pending.dispositionId(), correlation)));
            applied++;
        }
        return applied;
    }
}
