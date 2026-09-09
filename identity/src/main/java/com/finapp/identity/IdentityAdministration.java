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
import java.util.Objects;
import java.util.Optional;

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
 * revocation and a credential change, which are the tools for that and are reversible by them
 * alone.
 */
public final class IdentityAdministration {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "identity";

    private static final int EVENT_VERSION = 1;

    /** What an audit record about an administrative action points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    private final IdGenerator ids;
    private final Clock clock;
    private final IdentityStore<Connection> identities;
    private final Authorization authorization;
    private final SessionRevocation sessions;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outboxWriter;

    public IdentityAdministration(
            IdGenerator ids,
            Clock clock,
            IdentityStore<Connection> identities,
            Authorization authorization,
            SessionRevocation sessions,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.authorization = Objects.requireNonNull(authorization, "authorization must not be null");
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
    }

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

        // The aggregate decides whether the transition is legal (INV-LIFE-02: rejected by the
        // aggregate, not merely unreachable through the API); the conditional UPDATE decides who
        // wins when two administrators act at once. Neither substitutes for the other.
        Identity suspended = identity.suspend(clock);
        if (!identities.moveStatus(unitOfWork, subject, IdentityStatus.ACTIVE, suspended)) {
            return Suspension.NOT_ACTIVE;
        }

        int ended = sessions.revokeAll(unitOfWork, subject);

        Instant at = Instant.now(clock);
        audit(
                unitOfWork,
                at,
                IdentityAuditAction.IDENTITY_SUSPENDED,
                subject,
                "sessionsRevoked=" + ended,
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
        if (!identities.moveStatus(unitOfWork, subject, IdentityStatus.SUSPENDED, reinstated)) {
            return Reinstatement.NOT_SUSPENDED;
        }

        Instant at = Instant.now(clock);
        audit(
                unitOfWork,
                at,
                IdentityAuditAction.IDENTITY_REINSTATED,
                subject,
                "status=" + reinstated.status().name(),
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
        return authorization.assign(unitOfWork, subject, role, actor, reason)
                ? RoleGrant.GRANTED
                : RoleGrant.ALREADY_HELD;
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
        SELF
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
        SELF
    }

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
