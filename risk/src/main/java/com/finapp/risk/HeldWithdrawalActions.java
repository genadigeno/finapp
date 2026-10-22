package com.finapp.risk;

import java.sql.Connection;
import java.util.UUID;

/**
 * Payments' doors on a held withdrawal, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md}
 * sections 5, 12.3, 12.8; ADR-0108, ADR-0112) - {@code app} implements this over payments' release and denial of a
 * {@code HELD} withdrawal (`-009`), each under the withdrawal row's lock in payments' order, keyed and audited by
 * payments: a release dispatches it; a denial fails it {@code RISK_REFUSED} and releases its ledger hold. The release an
 * approved override issues runs through this port from the approval door (`-012`) until `-017` folds it into the action
 * machine; a release racing the hold's expiry leaves exactly one outcome, the loser {@link ActionOutcome#ALREADY}.
 */
public interface HeldWithdrawalActions {

    ActionOutcome release(Connection unitOfWork, UUID withdrawalId, String idempotencyKey, String reason);

    ActionOutcome deny(Connection unitOfWork, UUID withdrawalId, String idempotencyKey, String reason);
}
