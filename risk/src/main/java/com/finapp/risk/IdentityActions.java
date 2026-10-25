package com.finapp.risk;

import java.util.UUID;

/**
 * Identity's doors, as a risk action reaches them (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.8; ADR-0112;
 * owner decision C10) - {@code app} implements this over identity's existing administration
 * ({@code IdentityAdministration.suspend} / {@code reinstate}) and session revocation ({@code SessionRevocation.revokeAll})
 * ({@code IdentityRiskActions}), each call keyed {@code risk.action:<id>} and audited by identity as its own act, the
 * platform the executor. The platform never resets a credential.
 *
 * <p><strong>Reshaped by its first implementation (`P13-TSK-017`)</strong>, as `-009` reshaped
 * {@link HeldWithdrawalActions}: <strong>no unit of work</strong> - the command runs in identity's own transaction, never
 * inside a risk one (T-act: no risk row is held while the owner works) - and <strong>the reason is a code</strong>
 * ({@code [A-Z][A-Z0-9_]{0,63}}), never a note ({@code INV-RSK-08}). The target is the <strong>party</strong> - the case's
 * subject ({@link ActionKind#target()}): every identity the party holds is acted on, in one identity transaction.
 * {@code decidedBy} is the person who approved the action - identity's administration names two parties and refuses a
 * self-loop, so it is given the decider, never the subject.
 *
 * <p><strong>The outcomes</strong>: {@link ActionOutcome#DONE} - this key moved at least one identity (or ended at least
 * one session); {@link ActionOutcome#ALREADY} - every identity was already in the state asked (or no session was live);
 * {@link ActionOutcome#REFUSED} - the party holds no identity, every one is closed, or the decider is no identity the
 * platform holds. A re-drive under the same key replays the first answer. A storage failure or an in-flight duplicate
 * is thrown - nothing recorded, re-driven by the same key.
 */
public interface IdentityActions {

    ActionOutcome suspend(UUID partyId, String decidedBy, String idempotencyKey, String reason);

    ActionOutcome reinstate(UUID partyId, String decidedBy, String idempotencyKey, String reason);

    ActionOutcome revokeSessions(UUID partyId, String idempotencyKey, String reason);
}
