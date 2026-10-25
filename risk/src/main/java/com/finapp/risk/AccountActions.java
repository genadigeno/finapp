package com.finapp.risk;

import java.util.UUID;

/**
 * Accounts' doors, as a risk action would reach them (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112).
 *
 * <p><strong>Unimplemented: accounts has no such command</strong> (`P13-TSK-017`'s finding). {@code CustomerAccountStatus}
 * names {@code SUSPENDED} and the edges {@code ACTIVE ⇄ SUSPENDED}, but no door produces it ("no producer this phase");
 * {@code AccountClosing} is accounts' only status command. A new command in another domain was outside the task, so
 * {@link ActionKind#SUSPEND_ACCOUNT} and {@link ActionKind#REINSTATE_ACCOUNT} are unavailable - never proposable, never
 * dispatched - and this port is reshaped to T-act's shape (no unit of work, the reason a code) for the owner item that
 * builds the command.
 */
public interface AccountActions {

    ActionOutcome suspend(UUID accountId, String idempotencyKey, String reason);

    ActionOutcome reinstate(UUID accountId, String idempotencyKey, String reason);
}
