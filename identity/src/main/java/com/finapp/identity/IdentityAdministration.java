package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * What an administrator may do to somebody else's identity (`P1-TSK-028`).
 *
 * <h2>Ownership here is inverted, and that is the whole shape of this class</h2>
 *
 * <p>ADR-0031 requires two checks and this class holds the second one. Everywhere else in
 * {@code identity} the ownership rule is <em>the resource must belong to the caller</em>; here it
 * is the exact opposite — <strong>the subject must not be the actor</strong> — because these
 * operations exist to be performed on other people.
 *
 * <p>That is why the rule cannot live in {@code @RequiresPermission}. A boundary annotation is
 * static per handler and knows nothing about which identity the path names; comparing the two is a
 * fact about this request, so it is a domain rule (ADR-0031's own argument for why the boundary
 * cannot answer <em>may this actor do it to this resource?</em>).
 *
 * <h2>What refusing self-elevation actually buys, stated honestly</h2>
 *
 * <p><strong>It is not a containment control.</strong> An administrator holding
 * {@link PermissionName#ROLE_ASSIGN} can escalate through a second account they control, and no
 * rule here stops that. What it buys is that <strong>the trail never contains a self-loop</strong>:
 * every escalation names two parties, so <em>A granted B</em> is always readable and a self-grant —
 * which reads like a system action rather than a decision somebody took — can never appear.
 *
 * <p>That is {@code INV-AUD-04}'s four-eyes principle pointing the same way. Four-eyes itself is
 * not modelled: {@code audit_record} holds one actor, which is recorded debt (ADR-0010).
 *
 * <h2>Why refusing self-suspension survives reinstatement existing</h2>
 *
 * <p>The original argument was the one-way door: no reinstatement endpoint existed, so a suspended
 * administrator could not be un-suspended by anybody through the API. {@code P1-TSK-032} built
 * reinstatement and the backlog required both decisions to be revisited together. <strong>The
 * refusal stays, on a corrected argument</strong>: reinstatement makes the door two-way only when a
 * <em>second</em> administrator exists, and the platform does not guarantee one — the last
 * administrator self-suspending is still locked out of the platform with the remedy being the
 * out-of-band operator action of {@code README.md} §5e. And the trail argument is untouched: an
 * administrative record naming one party twice reads like a system action rather than a decision
 * somebody took. An administrator who suspects their own account is compromised has session
 * revocation and — through a verified channel — account recovery to replace the credential.
 * (An earlier version of this paragraph named "a credential change" as one of the tools; no
 * logged-in credential-change endpoint exists — {@code POST /v1/me/credential} is planned and
 * unbuilt, {@code P1-TSK-033} — and `P1-DOC-002` corrected the claim rather than let a reader
 * plan an incident response around a tool that is not there.)
 */
@RequiredArgsConstructor
public final class IdentityAdministration {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "identity";

    private static final int EVENT_VERSION = 1;

    /** What an audit record about an administrative action points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final Authorization authorization;
    @NonNull private final SessionRevocation sessions;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;

    /**
     * Suspends an identity and ends every session it holds.
     *
     * <h2>The sessions are the point, and without them this operation does almost nothing</h2>
     *
     * <p>{@code JdbcSessionStore.findByToken} filters on the <strong>session's</strong> status and
     * never joins this table, so a suspended identity keeps every live session until its absolute
     * bound expires. {@code CredentialVerifier} refuses a suspended identity at
     * <em>authentication</em> — which stops the next login and does nothing about the session an
     * attacker is holding right now.
     *
     * <p>So the revocation is not a courtesy, it is what makes the word <em>suspended</em> true:
     * {@code INV-IDN-03}'s reasoning, that an eventually-revoked session is an unrevoked session.
     *
     * <p><strong>Joining identity status into the session lookup was the alternative and was
     * rejected</strong>: it puts a second table into the hottest query on the platform, on every
     * authenticated request, to enforce once per request a rule that a revoke enforces once per
     * decision. Revocation is also the honest model — the sessions really are over.
     *
     * @return the outcome. {@link Suspension#NOT_FOUND} and {@link Suspension#NOT_ACTIVE}
     *     are distinguished because the caller is a <em>proven administrator</em>, not an anonymous
     *     prober: this is the one place in the module where telling somebody an identity exists is
     *     correct rather than an oracle ({@code INV-IDN-07} governs unauthenticated surfaces)
     */
    public Suspension suspend(
            Connection unitOfWork, IdentityId subject, IdentityId actor, String reason) {
        return suspend(unitOfWork, subject, actor, reason, SuspensionCause.ADMINISTRATOR);
    }

    /**
     * {@link #suspend(Connection, IdentityId, IdentityId, String)}, recording {@code cause} beside the status (identity
     * {@code V024}; the Phase 13 gate, owner item 25) - {@link SuspensionCause#RISK} for risk's action, so only risk's
     * own suspension is lifted by risk ({@link #reinstate(Connection, IdentityId, IdentityId, String,
     * SuspensionCause)}). An identity already suspended answers {@link Suspension#NOT_ACTIVE} and keeps its cause.
     *
     * <h2>An administrator never suspends the last identity that can grant roles</h2>
     *
     * <p>`P15-TSK-032`, item 42 (d) as decided: with {@link SuspensionCause#ADMINISTRATOR}, a subject that is the last
     * {@code ACTIVE} identity holding a role that can grant roles is refused, {@link Suspension#LAST_ROLE_GRANTER}, nothing
     * written - the same rule a revocation keeps ({@link #revokeRole}). Two administrators suspending each other at once leave
     * exactly one. <strong>The locks come before the status write, in the revocation's order</strong>: the subject's row
     * ({@link RoleAssignmentStore#lockForRoleChange}), then every live holding of a granting role in key order; then the
     * status update and the session revocation's {@code FOR UPDATE} of the subject's row. Taken after the status write, the
     * holdings' lock would deadlock with a revocation whose actor is the subject: that revocation holds the holdings and then
     * takes {@code KEY SHARE} on its actor's row for {@code revoked_by}'s foreign key, which a held {@code FOR UPDATE} blocks.
     * <strong>The count is a fresh statement after the locks</strong> ({@link Authorization#lockActiveGranters}), so a
     * suspension or revocation that committed while they waited is seen. A subject holding no granting role takes the
     * identity's lock alone.
     *
     * <p><strong>Risk's automated suspension is not bound by it</strong> ({@link SuspensionCause#RISK}, risk's
     * {@code SUSPEND_IDENTITY}): a compromised last administrator must still be suspendable, so security wins over
     * availability there, and risk's path takes none of these locks. If it suspends the last one, nobody can grant, revoke,
     * suspend or reinstate through the platform; the only way back is the out-of-band write of {@code README.md} §5e - a
     * granting role inserted for an {@code ACTIVE} identity, or the suspended one's status set back - by someone holding a
     * database credential that can write {@code identity}'s tables. In a deployed environment no person has such a session
     * today ({@code THREAT_MODEL.md} §6 item 31 (e)).
     */
    public Suspension suspend(
            Connection unitOfWork, IdentityId subject, IdentityId actor, String reason, SuspensionCause cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (subject.equals(actor)) {
            return Suspension.SELF;
        }

        Optional<Identity> found = identities.findById(unitOfWork, subject);
        if (found.isEmpty()) {
            return Suspension.NOT_FOUND;
        }
        Identity identity = found.get();
        if (identity.status() != IdentityStatus.ACTIVE) {
            return Suspension.NOT_ACTIVE;
        }

        if (cause == SuspensionCause.ADMINISTRATOR
                && authorization.standingForChange(unitOfWork, subject).roles().stream().anyMatch(Authorization::grantsRoles)) {
            java.util.Set<RoleAssignmentStore.Holding> granters = authorization.lockActiveGranters(unitOfWork);
            if (!granters.isEmpty() && granters.stream().allMatch(holding -> holding.identityId().equals(subject))) {
                return Suspension.LAST_ROLE_GRANTER;
            }
        }

        // The aggregate decides whether the transition is legal (INV-LIFE-02: rejected by the
        // aggregate, not merely unreachable through the API); the conditional UPDATE decides who
        // wins when two administrators act at once. Neither substitutes for the other.
        Identity suspended = identity.suspend(clock);
        if (!identities.moveStatus(unitOfWork, subject, IdentityStatus.ACTIVE, suspended, Optional.of(cause))) {
            return Suspension.NOT_ACTIVE;
        }

        int ended = sessions.revokeAll(unitOfWork, subject);

        Instant at = Instant.now(clock);
        audit(
                unitOfWork,
                at,
                IdentityAuditAction.IDENTITY_SUSPENDED,
                subject,
                "sessionsRevoked=" + ended + ", cause=" + cause.name(),
                reason);
        announce(unitOfWork, at, "identity.IdentitySuspended", subject, suspended.status().name());
        return Suspension.SUSPENDED;
    }

    /**
     * Lifts a suspension ({@code P1-TSK-032}).
     *
     * <h2>Sessions are not restored, and that is a decision rather than an omission</h2>
     *
     * <p>The suspension revoked them, the revocations happened, and {@code INV-HIST-01} does not
     * un-happen things — a session resurrected here would be a bearer credential coming back to
     * life in whatever hands last held it, including the attacker's whose activity may be why the
     * account was suspended. The person logs in again, which re-proves the credential.
     *
     * <h2>A {@code CLOSED} identity does not come back through this door</h2>
     *
     * <p>{@code CLOSED} is terminal ({@code INV-LIFE-04}). The conditional
     * {@code moveStatus(from = SUSPENDED)} enforces that at the write, the aggregate's own
     * transition check enforces it independently ({@code INV-LIFE-02}), and the outcome says
     * {@link Reinstatement#NOT_SUSPENDED} — named for what was <em>checked</em>, the
     * {@code NOT_ACTIVE} lesson, because "already active" would be a claim that is false for a
     * closed identity.
     *
     * <h2>The {@code SELF} branch is nearly unreachable, and kept anyway</h2>
     *
     * <p>A suspended identity holds no live session — {@code suspend} revoked them in the same
     * transaction — so a suspended person cannot call this endpoint to free themselves; an actor
     * who names themselves is almost certainly {@code ACTIVE} and lands on
     * {@code NOT_SUSPENDED}. What remains is the race where the actor is suspended mid-request,
     * and the branch is kept for the property the whole class protects: <strong>the trail never
     * contains a self-loop</strong> — every administrative record names two parties.
     */
    public Reinstatement reinstate(
            Connection unitOfWork, IdentityId subject, IdentityId actor, String reason) {
        return reinstate(unitOfWork, subject, actor, reason, Optional.empty());
    }

    /**
     * Lifts a suspension only while its recorded cause is {@code cause} (identity {@code V024}; the Phase 13 gate, owner
     * item 25): risk's {@code REINSTATE_IDENTITY} lifts risk's own suspension and never an administrator's, nor one
     * recorded before V024 - those answer {@link Reinstatement#OTHER_CAUSE} and stand. Otherwise as
     * {@link #reinstate(Connection, IdentityId, IdentityId, String)}.
     */
    public Reinstatement reinstate(
            Connection unitOfWork, IdentityId subject, IdentityId actor, String reason, SuspensionCause cause) {
        return reinstate(unitOfWork, subject, actor, reason, Optional.of(Objects.requireNonNull(cause, "cause")));
    }

    private Reinstatement reinstate(
            Connection unitOfWork,
            IdentityId subject,
            IdentityId actor,
            String reason,
            Optional<SuspensionCause> only) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (subject.equals(actor)) {
            return Reinstatement.SELF;
        }

        Optional<Identity> found = identities.findById(unitOfWork, subject);
        if (found.isEmpty()) {
            return Reinstatement.NOT_FOUND;
        }
        Identity identity = found.get();
        if (identity.status() != IdentityStatus.SUSPENDED) {
            return Reinstatement.NOT_SUSPENDED;
        }

        Identity reinstated = identity.reinstate(clock);
        if (!identities.moveStatus(unitOfWork, subject, IdentityStatus.SUSPENDED, reinstated, only)) {
            // Lost to a concurrent move - or, when a cause was named, the suspension in force is another's.
            boolean stillSuspended = identities.findById(unitOfWork, subject)
                    .filter(now -> now.status() == IdentityStatus.SUSPENDED).isPresent();
            return only.isPresent() && stillSuspended
                    ? Reinstatement.OTHER_CAUSE
                    : Reinstatement.NOT_SUSPENDED;
        }

        Instant at = Instant.now(clock);
        audit(
                unitOfWork,
                at,
                IdentityAuditAction.IDENTITY_REINSTATED,
                subject,
                "status=" + reinstated.status().name()
                        + only.map(cause -> ", cause=" + cause.name()).orElse(""),
                reason);
        announce(
                unitOfWork, at, "identity.IdentityReinstated", subject, reinstated.status().name());
        return Reinstatement.REINSTATED;
    }

    /**
     * Grants a role to an identity.
     *
     * <p>The assignment itself is {@link Authorization#assign}, which already audits and is already
     * safe under concurrency ({@code ON CONFLICT DO NOTHING} against a partial unique index,
     * `P1-TSK-020`). What this adds is the not-self rule and the subject's existence.
     *
     * <p><strong>The identity is looked up before the role is granted</strong>, so a role cannot be
     * assigned to an identifier that names nobody. Without it the assignment table would accept a
     * row referencing a party that does not exist — the foreign key would catch it, but as a
     * storage exception rendered {@code 500}, which is our fault reported for a caller's typo.
     *
     * <p><strong>A role that can redirect a merchant's money needs a factor first</strong> (`P15-TSK-039`;
     * {@code PRIVILEGED_ACCESS_REVIEW.md} §6), and since `P15-TSK-032` so does the role that grants roles and suspends
     * identities ({@link RoleName#requiresEnrolledFactor}): read here, on the grant's own unit of work, per decision. No door removes a
     * confirmed factor, so a factor seen here cannot be taken away between this read and the grant. A retried grant to
     * someone already holding the role without a factor is refused too: the answer says what the holding lacks, where
     * reporting success would tell the administrator the rule was satisfied.
     *
     * <h2>A first role ends every session the grantee holds</h2>
     *
     * <p>`P15-TSK-043`, {@code THREAT_MODEL.md} §6 item 39 (a2). The interceptor checks a session's level and nothing else,
     * so a session a customer stepped up from the internet would become a staff session at {@code MULTI_FACTOR} the moment a
     * role is granted. A <strong>first</strong> role - the identity held no live role, read here, on the grant's own unit of
     * work, as the interceptor and the public doors decide who is staff - moves it from the customer population into the
     * operator one, so every session it holds is revoked in this transaction, between the grant's row and its record: a
     * grant that commits has ended them, and one that rolls back, the revocation's failure included, has not. The grantee
     * signs in again on the operator surface. Every replica reads sessions from PostgreSQL per request, so the next request
     * on any of them is refused.
     *
     * <p>A second role ends nothing: its holder's sessions were issued at doors that already knew it was staff, since the
     * public doors refuse staff. A retry of a first grant finds it held and ends nothing more - the first attempt ended them
     * or granted nothing. A re-grant after a revocation is a first role again, because in between the identity was a
     * customer. A revocation of a role beside another ends nothing: the role stops granting on the next request, and the
     * sessions serve what the identity still is; a revocation of the <em>last</em> role ends them ({@link #revokeRole}). A
     * request already past the interceptor when the grant commits completes as it began.
     *
     * <p><strong>The first-role read is taken under the identity's lock</strong> (`P15-TSK-032`;
     * {@link RoleAssignmentStore#lockForRoleChange}), the lock every grant and revocation of the identity takes first. A
     * revocation of the identity's last role either committed before - and this read, taken after the lock, sees it gone and
     * calls the role first - or waits for this grant, and then sees its role and ends nothing.
     */
    public RoleGrant assignRole(
            Connection unitOfWork,
            IdentityId subject,
            RoleName role,
            IdentityId actor,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (subject.equals(actor)) {
            return RoleGrant.SELF;
        }
        if (identities.findById(unitOfWork, subject).isEmpty()) {
            return RoleGrant.NOT_FOUND;
        }
        if (role.requiresEnrolledFactor()
                && enrolments.findActive(unitOfWork, subject, MfaFactorType.TOTP).isEmpty()) {
            return RoleGrant.FACTOR_REQUIRED;
        }
        // Staff is any live role: the same rows the interceptor types an act by (Standing.actorType), read here after the
        // identity's lock (P15-TSK-032), so no other grant or revocation of this identity commits between this read and this
        // grant's commit, and the read is the state the grant changes. A committed role read here came through this door, which
        // ended them at its first role, or around it (a script, break-glass, a grant before this release), which ended nothing:
        // the review asks after those (PRIVILEGED_ACCESS_REVIEW.md §5 question 6), and a session's absolute bound ends what they
        // left.
        boolean firstRole = authorization.standingForChange(unitOfWork, subject).roles().isEmpty();
        return authorization.assign(unitOfWork, subject, role, actor, reason,
                        granted -> firstRole
                                ? "firstRole=true, sessionsRevoked=" + sessions.revokeAll(granted, subject)
                                : "firstRole=false")
                ? RoleGrant.GRANTED
                : RoleGrant.ALREADY_HELD;
    }

    /**
     * Takes a role from an identity (`P15-TSK-032`): the act the access review's revocations, break-glass's close in the
     * application ({@code PRIVILEGED_ACCESS_REVIEW.md} §5 and §7) and the compromise procedure name. The row is marked by
     * {@link Authorization#revoke}, conditional on being live, and the record is {@code identity.RoleAssigned} saying
     * {@code revoked=<role>}, with the reason.
     *
     * <h2>Refused for oneself</h2>
     *
     * <p>As a grant, a suspension and a reinstatement are: every administrative record names two parties, so the trail never
     * holds a self-loop. An administrator who should not hold a role - the review's question 3 - has it taken by another, as
     * it was given. And the person acting holds {@code ROLE_ASSIGN}, so revoking one's own granting role is the shortest way to
     * leave nobody who can grant.
     *
     * <h2>Never the last identity that can grant roles</h2>
     *
     * <p>A role holding {@link PermissionName#ROLE_ASSIGN} (today {@code ADMINISTRATOR}, read from what each role grants) is
     * not revoked from the last {@code ACTIVE} identity holding one: {@link RoleRevocation#LAST_ROLE_GRANTER}, nothing written.
     * With nobody left, nobody grants, revokes, suspends or reinstates through the platform again. <strong>"Last" is counted
     * after every live holding of such a role is locked</strong> ({@link Authorization#lockActiveGranters}), the one lock here
     * that spans identities, and by a fresh statement after it: two revocations of the last two holders - two administrators
     * revoking each other - or a revocation and an administrator's suspension of the other holder lock the same rows in the
     * same order, the second waits for the first, and its count sees what the first committed, a suspension included. A
     * suspended holder does not count - a suspended administrator cannot sign in - and a grant still in flight is not seen,
     * which errs towards refusing. Risk's automated suspension takes no such lock and is not bound by the rule
     * ({@link #suspend(Connection, IdentityId, IdentityId, String, SuspensionCause)}).
     *
     * <h2>The last role ends every session the identity holds</h2>
     *
     * <p>The coordinator's ruling under the owner's standing word (2026-11-12), the mirror of a first role (`P15-TSK-043`): the
     * identity's sessions were issued at doors that knew it was staff - the operator surface's - and a session is not bound to
     * the surface that issued it ({@code THREAT_MODEL.md} §6 item 39 (a3)), so a person who is staff no more would otherwise
     * keep a session from the operator host, at the level it was issued at. Revoked in this transaction, between the row and
     * its record, which says {@code lastRole=true, sessionsRevoked=<n>}: a revocation that commits has ended them, one that
     * rolls back, the session revocation's failure included, has not. Every replica reads sessions from PostgreSQL per request,
     * so the next request on any of them is refused; the person signs in again as a customer. A role revoked beside another
     * ends nothing ({@code lastRole=false}): the sessions serve what the identity still is. Which role is last is read after
     * the identity's lock ({@link RoleAssignmentStore#lockForRoleChange}), which every grant and revocation of the identity
     * takes first: a concurrent revocation of its other role, or a grant to it, either committed before - and is read - or
     * waits for this one. So two revocations of an identity's last two roles end its sessions once, the second, and a grant
     * racing a revocation never leaves "last" said of a role that was not.
     *
     * <h2>Idempotent in effect</h2>
     *
     * <p>A role the identity does not hold - never held, or revoked already - answers {@link RoleRevocation#NOT_HELD}: the
     * caller asked for the identity not to hold it, and it does not. Nothing is written, recorded or ended, so a retry after a
     * lost answer changes nothing. A grant of the same role racing this ends in one defined state, serialised by the identity's
     * lock: a grant that committed first is revoked, one that waited stands, and each act's record says what it did.
     *
     * <h2>The order of the locks</h2>
     *
     * <p>The subject's row, {@code FOR NO KEY UPDATE}; then - for a granting role only - every live holding of a granting role,
     * {@code FOR UPDATE} in the primary key's order, and the fresh count; then the role row's update, whose {@code revoked_by}
     * foreign key takes {@code KEY SHARE} on the <em>actor's</em> row; then, for a last role, the session revocation's
     * {@code FOR UPDATE} of the subject's row - an upgrade of the {@code NO KEY UPDATE} this transaction holds, which waits for
     * any transaction holding {@code KEY SHARE} on that row: a session insert in flight (its foreign key) or an act whose actor
     * is the subject. An administrator's suspension takes the same first two locks in the same order before its status write
     * ({@link #suspend(Connection, IdentityId, IdentityId, String, SuspensionCause)}), so the two never wait on each other in
     * a cycle; two revocations or suspensions touching granting roles meet first on the holdings, in one order.
     */
    public RoleRevocation revokeRole(
            Connection unitOfWork,
            IdentityId subject,
            RoleName role,
            IdentityId actor,
            String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        if (subject.equals(actor)) {
            return RoleRevocation.SELF;
        }
        if (identities.findById(unitOfWork, subject).isEmpty()) {
            return RoleRevocation.NOT_FOUND;
        }
        java.util.Set<RoleName> held = authorization.standingForChange(unitOfWork, subject).roles();
        if (!held.contains(role)) {
            return RoleRevocation.NOT_HELD;
        }
        if (Authorization.grantsRoles(role)) {
            java.util.Set<RoleAssignmentStore.Holding> granters = authorization.lockActiveGranters(unitOfWork);
            RoleAssignmentStore.Holding revoking = new RoleAssignmentStore.Holding(subject, role);
            if (granters.contains(revoking) && granters.stream().allMatch(revoking::equals)) {
                return RoleRevocation.LAST_ROLE_GRANTER;
            }
        }
        boolean lastRole = held.equals(java.util.Set.of(role));
        return authorization.revoke(unitOfWork, subject, role, actor, reason,
                        revoked -> lastRole
                                ? "lastRole=true, sessionsRevoked=" + sessions.revokeAll(revoked, subject)
                                : "lastRole=false")
                ? RoleRevocation.REVOKED
                // Unreachable under the identity's lock: no other revocation of it can have committed since the read.
                : RoleRevocation.NOT_HELD;
    }

    // -----------------------------------------------------------------

    /** What a suspension attempt did. */
    public enum Suspension {
        SUSPENDED,

        /**
         * The identity was not {@code ACTIVE}.
         *
         * <p>Named for what was <em>checked</em> rather than for the commonest cause. The first
         * version was {@code NOT_ACTIVE}, which is a <strong>claim that can be false</strong>:
         * a {@code CLOSED} identity reaches this branch too, and it is not suspended - it is gone
         * permanently, and a caller reading "already suspended" would conclude the operation had
         * effectively succeeded. The completion gate found it; the client detail had been accurate
         * all along, so only the enumeration lied.
         */
        NOT_ACTIVE,

        NOT_FOUND,
        /** The administrator named themselves. */
        SELF,
        /**
         * An administrator's suspension of the last {@code ACTIVE} identity holding a role that can grant roles (`P15-TSK-032`).
         * Nothing was written. Never answered to risk's suspension, which the rule does not bind.
         */
        LAST_ROLE_GRANTER
    }

    /** What a reinstatement attempt did (`P1-TSK-032`). */
    public enum Reinstatement {
        REINSTATED,

        /**
         * The identity was not {@code SUSPENDED} — {@code ACTIVE} and {@code CLOSED} both land
         * here, and only the first could honestly be called "already done". Named for what is
         * checked, the {@link Suspension#NOT_ACTIVE} lesson.
         */
        NOT_SUSPENDED,

        /**
         * The identity is suspended, but not by the cause the caller may lift (the Phase 13 gate, owner item 25): an
         * administrator's suspension, or one recorded before identity {@code V024}, asked of risk's reinstatement. It
         * stands. Never answered to an administrator, who lifts any.
         */
        OTHER_CAUSE,

        NOT_FOUND,
        /** The administrator named themselves. See {@code reinstate} — nearly unreachable. */
        SELF
    }

    /** What a role assignment did. */
    public enum RoleGrant {
        GRANTED,
        ALREADY_HELD,
        NOT_FOUND,
        /** The administrator named themselves. */
        SELF,
        /** The role can redirect a merchant's money and its holder has enrolled no factor. Nothing was granted. */
        FACTOR_REQUIRED
    }

    /** What a role revocation did (`P15-TSK-032`). */
    public enum RoleRevocation {
        REVOKED,
        /** The identity did not hold the role live: nothing was revoked, recorded or ended. */
        NOT_HELD,
        NOT_FOUND,
        /** The administrator named themselves. */
        SELF,
        /** The subject is the last {@code ACTIVE} identity holding a role that can grant roles. Nothing was revoked. */
        LAST_ROLE_GRANTER
    }

    /**
     * The check of the holdings the factor rule found standing (`P15-TSK-039`): who holds a role that needs a factor
     * and has none, at most {@code mostServed}, and whether there were more. It grants nothing and revokes nothing; the
     * review (§5) resolves what it names. Recorded in this read's transaction, with what was served.
     */
    public FactorlessHoldings factorlessHoldings(Connection unitOfWork, int mostServed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        List<RoleAssignmentStore.FactorlessHolding> found = authorization.holdingsWithoutFactor(unitOfWork, mostServed + 1);
        boolean truncated = found.size() > mostServed;
        List<RoleAssignmentStore.FactorlessHolding> served = truncated ? found.subList(0, mostServed) : found;
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        IdentityAuditAction.FACTORLESS_HOLDINGS_READ,
                        "identity.RoleAssignment",
                        "factorless-holdings",
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        currentCorrelation().correlationId(),
                        Optional.of("served=" + served.size() + ", truncated=" + truncated)));
        return new FactorlessHoldings(served, truncated);
    }

    /** The holdings served and whether there were more than were. */
    public record FactorlessHoldings(List<RoleAssignmentStore.FactorlessHolding> holdings, boolean truncated) {}

    private void audit(
            Connection unitOfWork,
            Instant at,
            IdentityAuditAction action,
            IdentityId subject,
            String changeSummary,
            String reason) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        // The administrator, proven by the session interceptor. Never the subject:
                        // an audit record names who acted, and recording the person acted upon
                        // would make every administrative action look self-inflicted.
                        SecurityContext.require(),
                        at,
                        action,
                        AUDIT_TARGET_TYPE,
                        subject.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        currentCorrelation().correlationId(),
                        Optional.of(changeSummary)));
    }

    private void announce(
            Connection unitOfWork, Instant at, String type, IdentityId subject, String status) {
        Correlation correlation = currentCorrelation();
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        subject,
                        "Identity",
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        CausationId.of(correlation.correlationId().value())),
                // Identifiers and an enumerated name. No reason: it is free text an administrator
                // typed, it may name a person or an incident, and the event stream reaches systems
                // with different access control (INV-AUD-02). The audit record is where it belongs.
                EventPayload.of()
                        .with("identityId", subject.value().toString())
                        .with("status", status)
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "An administrative action must run inside a correlation"
                                            + " scope: the audit record and the event it writes"
                                            + " carry the identifier, and a fabricated one would"
                                            + " point at no flow at all (P0-TSK-014)"));
    }
}
