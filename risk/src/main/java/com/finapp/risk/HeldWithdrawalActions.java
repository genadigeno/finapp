package com.finapp.risk;

import java.util.UUID;

/**
 * Payments' doors on a held withdrawal, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md}
 * sections 5, 12.3, 12.8; ADR-0108, ADR-0112) - {@code app} implements this over payments' release and denial of a
 * {@code HELD} withdrawal ({@code PaymentsHeldWithdrawalActions}, `P13-TSK-009`), each under the withdrawal row's lock in
 * payments' order, keyed by {@code idempotencyKey} ({@code risk.action:<id>}) and audited by payments: a release
 * dispatches it; a denial fails it {@code RISK_REFUSED} and releases its ledger hold. The release or denial an approved
 * override issues (`P13-TSK-012`, its first risk caller) or a case's approved action asks runs through this port from
 * the action machine ({@link ActionDispatch}, T-act, `P13-TSK-017`) - after the approval commits, and from
 * {@code RiskActionSchedule}'s re-drive - under the system actor, which payments' door admits (never widened to the
 * operator's session actor).
 *
 * <p><strong>Reshaped by its first implementation (`P13-TSK-009`)</strong>, as this module's ports may be until then:
 * <ul>
 *   <li><strong>No unit of work.</strong> A release <em>sends</em> - the push rail is called after the release commits,
 *       holding no connection (ADR-0046) - so the door runs in payments' own transactions, never inside a risk one;
 *       which is T-act's shape anyway (`-017`: the owning domain's command in its own transaction, with no risk row
 *       held). The caller holds no transaction across the call.</li>
 *   <li><strong>The reason is a code</strong> ({@code [A-Z][A-Z0-9_]{0,63}}, e.g. {@code OVERRIDE_APPROVED}) - recorded
 *       on payments' audit record. A note never crosses into payments: risk's notes are references resolved through
 *       its own audited reads ({@code INV-RSK-08}).</li>
 * </ul>
 *
 * <p><strong>The outcomes.</strong> {@link ActionOutcome#DONE}: this call (or this key's earlier flight) moved the
 * withdrawal out of {@code HELD}. {@link ActionOutcome#ALREADY}: it had already left {@code HELD} by another hand -
 * released, denied, or failed by its hold's expiry - so a release racing the expiry leaves exactly one outcome on the
 * row and the loser reads {@code ALREADY} ({@code PHASE_13_PLAN.md} §7.4; `-012` records the expiry's outcome beside
 * it). {@link ActionOutcome#REFUSED}: no withdrawal answers to the id, or - a release - the hold's validity has lapsed on
 * the database clock while the row is still {@code HELD}: the policy's outcome is then the expiry, which payments' leg
 * commits, never a late release. A key reused for another withdrawal, an in-flight
 * duplicate, or a storage failure is thrown - the action machine's {@code FAILED}, re-driven by the same key.
 */
public interface HeldWithdrawalActions {

    ActionOutcome release(UUID withdrawalId, String idempotencyKey, String reason);

    ActionOutcome deny(UUID withdrawalId, String idempotencyKey, String reason);
}
