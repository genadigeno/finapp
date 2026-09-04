/**
 * Identity, credentials and sessions: who can prove they are present, and with what.
 *
 * <p><strong>What belongs here.</strong> The Identity aggregate, Credential, MFA enrolment,
 * Device, Session and Role assignment. This is the platform's highest-sensitivity module, and the
 * one whose product is security rather than a business capability.
 *
 * <p><strong>Three concepts this module must never collapse</strong> ({@code CLAUDE.md}
 * §Domain Distinctions, {@code INV-IDN-04}):
 *
 * <ul>
 *   <li><strong>Authentication</strong> — proving presence. An authenticated session grants no
 *       permission by itself.
 *   <li><strong>Authorization</strong> — whether this actor may perform this operation. Evaluated
 *       explicitly per operation, deny by default; nothing is permitted by the absence of a rule.
 *   <li><strong>Consent</strong> — a lawful basis for processing. Neither of the above is ever
 *       treated as one ({@code INV-CRD-03}).
 * </ul>
 *
 * <p>Authorization lives here as a recorded merge with a named split trigger (ADR-0031), not
 * because it is the same concept. Two checks are always required and are never collapsed into one:
 * <em>permission</em> at the boundary — may an actor of this kind do this at all — and
 * <em>ownership</em> in the domain — may <em>this</em> actor do it to <em>this</em> resource.
 * Collapsing them is the most common authorization defect in financial software: a customer with
 * a legitimate {@code transfer:create} permission uses it against someone else's account, every
 * check passes, and nothing is logged as a denial.
 *
 * <p><strong>Sessions are authoritative in the database</strong> (ADR-0030), so revocation is
 * immediate on every instance by construction ({@code INV-IDN-03}). An eventually-revoked session
 * is an unrevoked session, and "log out everywhere" after a suspected compromise is worthless if
 * it takes effect when a cache expires. Assurance is a <em>level</em> recorded on the session, not
 * an MFA boolean: every real MFA bypass is a route that produces a session a boolean calls fine.
 *
 * <p><strong>Credential material never leaves this module in any form</strong> ({@code INV-IDN-01},
 * ADR-0032). Not in a return value, not in an event payload, not in a log line. A credential row
 * stores a derivation together with the algorithm and parameters that produced it, so the work
 * factor can be raised without invalidating every credential — the decision usually missed is not
 * which algorithm but where the parameters live.
 *
 * <p><strong>What this module holds of {@code party}: a {@code PartyId} by value.</strong> No
 * foreign key crosses the schema boundary (ADR-0029). Referential integrity across that edge is a
 * domain rule enforced by the registration transaction, which writes both modules' tables in one
 * commit — exactly the property ADR-0001 chose a modular monolith to preserve.
 *
 * <p><strong>Failure is closed.</strong> An unavailable credential store refuses authentication;
 * it never bypasses it.
 *
 * <p><strong>Nothing is implemented yet.</strong> This module is the skeleton created by
 * {@code P1-TSK-003}: the boundary, the schema and the auditable-action registry exist so that
 * credential-handling code lands inside an enforced boundary rather than establishing one
 * afterwards. The aggregates are {@code P1-TSK-005} onward.
 */
package com.finapp.identity;
