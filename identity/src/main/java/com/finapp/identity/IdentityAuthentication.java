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
 * Records an authentication attempt: its audit record and its event (`P1-TSK-010`).
 *
 * <h2>Both paths are recorded, and the failure path is the important one</h2>
 *
 * <p>A successful login answers <em>when was this account used</em>. A <strong>failed</strong> one
 * answers <em>who is trying</em>, and it is recorded for login identifiers that do not exist -
 * which is the point, because a failure rate against identifiers nobody registered is credential
 * stuffing and is invisible if only real accounts are recorded.
 *
 * <p>The write is on the caller's connection, so the record commits with the verification that
 * produced it. <strong>That is what forces the refusal to be returned rather than thrown</strong>:
 * an exception would roll the transaction back and take the audit record of the failed attempt with
 * it, and a failed authentication is the most security-relevant record this phase produces.
 *
 * <h2>What each sink is allowed to carry</h2>
 *
 * <p>The <strong>audit record</strong> may name the attempted login identifier - it is the
 * regulatory artefact, it is not client-visible, and {@code PHASE_1_PLAN.md} §10 names it as the one
 * place an attempted identifier belongs.
 *
 * <p>The <strong>event</strong> may not. An event stream reaches systems with different access
 * control ({@code INV-AUD-02}), and on the failure path there is frequently no identity to name at
 * all - so a payload that named one would either be absent for some failures, which is a shape
 * difference a consumer can read, or fabricated. It carries neither identifier nor identity.
 */
public final class IdentityAuthentication {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "identity";

    private static final int EVENT_VERSION = 1;

    /** What an audit record for an authentication points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.LoginIdentifier";

    /**
     * The aggregate a failure event names.
     *
     * <p>{@code EventEnvelope} requires an aggregate identifier, and a failed authentication
     * frequently has no aggregate - the identity may not exist. A fabricated identity identifier
     * would be worse than useless: a consumer would join it to nothing, or worse, to somebody. So a
     * failure names a fresh identifier of its own, which is honest - the thing that happened is the
     * attempt, and the attempt is what the event is about.
     */
    private final IdGenerator ids;

    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outboxWriter;

    public IdentityAuthentication(
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
    }

    /**
     * Records a successful authentication.
     *
     * <p>The caller must have established a {@link SecurityContext} naming the authenticated
     * identity: this is the platform's first audit record with a real actor, and defaulting it to
     * the system would record the platform as having logged somebody in.
     */
    public void succeeded(
            Connection unitOfWork, IdentityId identityId, LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");

        Correlation correlation = currentCorrelation();
        Instant at = Instant.now(clock);

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.AUTHENTICATION_SUCCEEDED,
                        AUDIT_TARGET_TYPE,
                        loginIdentifier.value(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of("identity=" + identityId)));

        outboxWriter.write(
                unitOfWork,
                envelope("identity.AuthenticationSucceeded", identityId, at, correlation),
                EventPayload.of()
                        .with("identityId", identityId.value().toString())
                        // The factor used, not the assurance level: AssuranceLevel is ADR-0030's
                        // and arrives with the session in P1-TSK-013. Publishing a level this
                        // endpoint cannot establish would be a claim about a session that does not
                        // exist.
                        .with("factor", CredentialType.PASSWORD.name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * Records a failed authentication.
     *
     * <p>{@code attempted} is whatever the caller typed, already normalised. It reaches the audit
     * record and nothing else.
     */
    public void failed(Connection unitOfWork, LoginIdentifier attempted) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(attempted, "attempted must not be null");

        Correlation correlation = currentCorrelation();
        Instant at = Instant.now(clock);

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.AUTHENTICATION_FAILED,
                        AUDIT_TARGET_TYPE,
                        attempted.value(),
                        Optional.empty(),
                        AuditOutcome.FAILED,
                        correlation.correlationId(),
                        // No reason, and no change summary. The four causes - no identity, an
                        // identity that cannot authenticate, no credential, a wrong password -
                        // are deliberately not distinguished anywhere, because a field that
                        // distinguishes them is a field somebody eventually maps to a response
                        // (INV-IDN-07). The audit trail says an attempt failed.
                        Optional.empty()));

        outboxWriter.write(
                unitOfWork,
                envelope("identity.AuthenticationFailed", IdentityId.of(ids.next()), at, correlation),
                // Deliberately empty of identifiers. EventPayload refuses an empty payload, so it
                // carries the one fact that is safe to publish: what kind of attempt this was.
                EventPayload.of().with("factor", CredentialType.PASSWORD.name()).toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    // -----------------------------------------------------------------

    private EventEnvelope envelope(
            String type, IdentityId aggregate, Instant at, Correlation correlation) {
        return new EventEnvelope(
                EventId.next(ids),
                type,
                EVENT_VERSION,
                EventEnvelope.CURRENT_SCHEMA_VERSION,
                aggregate,
                "Identity",
                at,
                PRODUCER,
                correlation.correlationId(),
                // The request is the cause. It looks self-referential and is not: the correlation
                // identifier is also on the audit record written in this same transaction, so the
                // chain terminates at something real rather than at nothing (`P1-TSK-006`).
                CausationId.of(correlation.correlationId().value()));
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Authentication must run inside a correlation scope: the"
                                            + " audit record and the event it writes carry the"
                                            + " identifier, and a fabricated one would point at no"
                                            + " flow at all (P0-TSK-014)"));
    }
}
