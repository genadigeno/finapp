package com.finapp.platform.audit;

/**
 * An action type that must produce an audit record.
 *
 * <p>The registry {@code INV-AUD-01} names as half of its enforcement, and that Phase 15 verifies
 * audit completeness against. {@code V009} already assumed it existed: {@code operation} is
 * documented there as "a stable identifier from the auditable-action registry, not free text",
 * because a completeness check is only possible over a set somebody can enumerate.
 *
 * <h2>Why an interface rather than one enum in the platform</h2>
 *
 * <p>The obvious shape — a single {@code AuditAction} enum listing every action in the system —
 * cannot be built. Actions belong to the modules that perform them: {@code kyc.CaseApproved} is
 * the KYC module's concept, and the platform sits <em>below</em> every business module
 * ({@code app -> business modules -> platform -> sharedkernel}). An enum here naming KYC's
 * actions would invert that dependency and make the platform's vocabulary a union of every
 * domain's.
 *
 * <p>So each module declares its own actions as an enum implementing this interface, and the
 * registry is the set of those declarations. Only {@code app} sees every module, which is
 * where the reconciliation lives.
 *
 * <h2>Why implementations must be enums</h2>
 *
 * <p>Enumerability is the entire point. The Phase 15 gate must be able to ask "what actions must
 * be audited" and get a complete answer; a set assembled at run time from whatever happened to
 * register itself would answer with whatever was loaded. {@code AuditableActionRegistryTest}
 * fails the build if an implementation is not an enum, and reconciles the declared set against
 * {@code docs/architecture/AUDITABLE_ACTIONS.md} in both directions — so an action added without
 * being documented fails, and a documented action that no longer exists fails too.
 *
 * <h2>What this does and does not guarantee</h2>
 *
 * <p>It guarantees that every audit record names a <em>declared</em> action: the type system
 * makes an ad-hoc string impossible, so an action cannot enter the trail without being in the
 * registry. It does <strong>not</strong> guarantee the converse — that every privileged action
 * in the codebase writes an audit record at all. Nothing mechanical can, because a missing call
 * is a missing call. That is what the Phase 15 completeness verification and code review are
 * for, and stating the limit is more useful than implying a guarantee the mechanism does not
 * provide.
 */
public interface AuditableAction {

    /**
     * The stable value persisted in {@code audit_record.operation}.
     *
     * <p><strong>Stable</strong> is load-bearing: audit records outlive the code that wrote
     * them, and a renamed code makes historical records refer to an action nobody can look up.
     * Renaming one is a data-migration question, not a refactor.
     *
     * <p>Namespaced by module — {@code outbox.EventAbandoned} — so that two modules cannot
     * collide on a bare name and merge two different actions into one line of the trail. The
     * convention is enforced by {@code AuditableActionRegistryTest}.
     */
    String code();

    /**
     * What the action is, for the catalogue an auditor reads.
     *
     * <p>Required rather than optional because the registry's readers are not the people who
     * wrote it. A code with no description is a row in a completeness report that nobody can
     * assess.
     */
    String description();

    /**
     * Whether an audit record for this action must carry a reason.
     *
     * <p>{@code V009} left this decision open — its {@code reason} column is nullable, with a
     * comment that "for the actions that do, absence is not permitted, and the domain decides
     * which those are". This is where the domain decides, and {@link AuditRecord} enforces it,
     * so the answer is attached to the action rather than remembered at each call site.
     *
     * <p>True for anything a human chose to do that the system would not have done by itself —
     * an override, a manual correction, a discretionary refusal. {@code INV-REV-04} requires it
     * for adjustments, and the same reasoning covers any privileged action whose justification is
     * the only evidence it was legitimate.
     */
    boolean requiresReason();
}
