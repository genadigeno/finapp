package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Ends sessions, and records who decided to (`P1-TSK-014`, {@code INV-IDN-03}).
 *
 * <h2>One audit record per operation, never per session</h2>
 *
 * <p>Revoking forty sessions writes <strong>one</strong> record, with the count in the change
 * summary. Forty would bury the decision under its consequences — the argument {@code P1-TSK-011}
 * already made for auditing the lockout crossing once rather than every attempt afterwards.
 *
 * <p>The division of labour is clean, and it is what makes one record sufficient: <strong>the
 * session row says when each session ended; the audit record says who decided and why.</strong> An
 * investigator asking "when did session S end?" reads {@code revoked_at} on the row; asking "who
 * ended it?" reads the trail.
 *
 * <h2>The action requires no reason, and that is a statement about which action this is</h2>
 *
 * <p>Logging yourself out — or having your other sessions ended because you changed your password —
 * is not an action taken against anybody. {@code PHASE_1_PLAN.md} §5 lists <em>"forced session
 * revocation"</em> separately among privileged actions: that is an administrator acting on somebody
 * else's account, it is a different action, and it belongs to the task that builds it.
 *
 * <h2>Nothing calls this yet</h2>
 *
 * <p>{@code P1-TSK-016} builds the endpoints and {@code P1-TSK-026} the credential change that ends
 * every other session. The capability is this task; the callers are theirs. That is the same seam
 * {@code P1-TSK-013} left for issuance.
 */
@RequiredArgsConstructor
public final class SessionRevocation {

    /** What an audit record for a single revocation points at. */
    public static final String SESSION_TARGET_TYPE = "identity.Session";

    /** What a bulk revocation points at: the identity, because that is what was acted on. */
    public static final String IDENTITY_TARGET_TYPE = "identity.Identity";

    @NonNull private final SessionStore<Connection> sessions;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;

    /**
     * Ends one session <strong>belonging to {@code owner}</strong>.
     *
     * <p>Audited only when something was actually ended. A record for a revocation that revoked
     * nothing would put a caller's <em>guess</em> at a session identifier into the trail, and the
     * trail would then answer questions about identifiers that were never real.
     *
     * <h2>The ownership check, and why this method previously did not have one</h2>
     *
     * <p>This took an {@code owner} and used it <strong>only in the audit change summary</strong>:
     * the statement underneath was {@code WHERE id = ? AND status = 'ACTIVE'}, so any caller could
     * end any session by identifier. {@code P1-TSK-014} built it that way because it had no caller;
     * {@code P1-TSK-016} is the first, and found it.
     *
     * <p>That shape is <strong>worse than an absent parameter</strong>. A signature naming an owner
     * reads as though ownership is enforced, and the audit record then asserts an owner nobody
     * verified — so the trail is confidently wrong rather than silent. It is exactly the defect
     * ADR-0031 names: <em>a legitimate permission used against someone else's resource, where every
     * check passes and nothing is logged as a denial.</em>
     *
     * @return the ended session's lifetime in whole seconds, or empty if nothing was ended.
     *     Presence is the old boolean; a caller must not report "not yours" and "no such session"
     *     differently — see {@code SessionStore#revokeOwned}.
     *     <p><strong>The lifetime is passed upward rather than measured here</strong>, because
     *     meters are incremented in {@code app} and never in {@code identity} — the convention
     *     {@code AuthenticationThrottle} established, which keeps the domain free of the telemetry
     *     framework. {@code SessionQueries} records it.
     */
    public java.util.OptionalLong revoke(
            Connection unitOfWork, SessionId sessionId, IdentityId owner) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(owner, "owner must not be null");

        Instant at = Instant.now(clock);
        java.util.OptionalLong lifetime = sessions.revokeOwned(unitOfWork, sessionId, owner, at);
        if (lifetime.isPresent()) {
            audit(unitOfWork, at, SESSION_TARGET_TYPE, sessionId.value().toString(),
                    "identity=" + owner);
        }
        return lifetime;
    }

    /** Ends every live session of an identity. */
    public int revokeAll(Connection unitOfWork, IdentityId identityId) {
        Instant at = Instant.now(clock);
        int revoked = sessions.revokeAllFor(unitOfWork, identityId, at);
        if (revoked > 0) {
            audit(unitOfWork, at, IDENTITY_TARGET_TYPE, identityId.value().toString(),
                    "sessionsRevoked=" + revoked);
        }
        return revoked;
    }

    /**
     * Ends every live session of an identity except the one named — what a credential change does.
     *
     * <p>The count is recorded even when it is zero-worthy in the caller's eyes, because <em>"the
     * password was changed and no other session was open"</em> is a different fact from <em>"and
     * eleven were closed"</em>, and only the second suggests somebody else was using the account.
     */
    public int revokeAllExcept(Connection unitOfWork, IdentityId identityId, SessionId spare) {
        Objects.requireNonNull(spare, "spare must not be null");

        Instant at = Instant.now(clock);
        int revoked = sessions.revokeAllForExcept(unitOfWork, identityId, spare, at);
        if (revoked > 0) {
            audit(unitOfWork, at, IDENTITY_TARGET_TYPE, identityId.value().toString(),
                    "sessionsRevoked=" + revoked + " spared=" + spare);
        }
        return revoked;
    }

    // -----------------------------------------------------------------

    private void audit(
            Connection unitOfWork,
            Instant at,
            String targetType,
            String targetId,
            String changeSummary) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A revocation must run inside a correlation scope:"
                                                    + " the audit record carries the identifier, and"
                                                    + " a fabricated one would point at no flow at"
                                                    + " all (P0-TSK-014)"));

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.SESSION_REVOKED,
                        targetType,
                        targetId,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(changeSummary)));
    }
}
