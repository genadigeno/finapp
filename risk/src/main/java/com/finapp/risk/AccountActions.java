package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Accounts' doors, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112;
 * implemented by `P17-TSK-028`, ADR-0153 decision 5): {@link ActionKind#SUSPEND_ACCOUNT} and
 * {@link ActionKind#REINSTATE_ACCOUNT} through accounts' restriction machine - T-act's shape, no unit of work, the reason the
 * action's code, keyed {@code risk.action:<id>} by the owner. Risk's case approval is already four eyes, so the agreement
 * moves on the owner's own lock with no second proposal.
 */
public interface AccountActions {

    ActionOutcome suspend(UUID accountId, String idempotencyKey, String reason);

    ActionOutcome reinstate(UUID accountId, String idempotencyKey, String reason);

    /**
     * The proposal's one read (`P17-TSK-028`): whether {@code accountId} is an agreement of {@code partyId} - a party's case
     * acts on that party's own accounts only. In the proposer's unit of work; false for an unknown agreement.
     */
    boolean heldBy(Connection unitOfWork, UUID partyId, UUID accountId);
}
