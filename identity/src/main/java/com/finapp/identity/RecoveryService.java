package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Account recovery, which exists to bypass the credential (`P1-TSK-023`, {@code INV-IDN-06}).
 *
 * <h2>What it does, and the two things it deliberately does not</h2>
 *
 * <p>It <strong>replaces the credential</strong>. It does <strong>not</strong> issue a session and it
 * does <strong>not</strong> remove a factor.
 *
 * <p>Both come straight from {@code INV-IDN-06}'s second clause — <em>"recovery never lowers the
 * assurance required to reach an account"</em>. The conventional design logs you in on completion,
 * and that <em>is</em> the lowering: an attacker holding the mailbox would skip the credential
 * <strong>and</strong> whatever stood behind it. Setting the credential and stopping means the
 * customer authenticates normally afterwards, so MFA applies in full and a compromised mailbox still
 * meets the second factor.
 *
 * <p>It also keeps {@code MfaBypassPathsAreEnumeratedTest}'s statement true — nothing new creates a
 * session — which is the question that guard listed recovery as a recorded remainder for.
 *
 * <h2>Every response is the same response</h2>
 *
 * <p>{@link #initiate} answers identically whether the identifier names anybody, whether that
 * identity has a verified channel, and whether cooling-off refused it. The store does the whole
 * decision in <strong>one statement</strong>, so the work is equivalent too: a version that looked
 * the identity up first would run a different number of queries for an account that exists, which is
 * the timing channel {@code P1-TSK-008} found in authentication.
 */
@RequiredArgsConstructor
public final class RecoveryService {

    /** Long enough to read a message; short enough that a stolen token is not a standing key. */
    public static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);

    /**
     * How long after one initiation another is refused.
     *
     * <p>This is the cooling-off {@code INV-IDN-06} names, and it bounds a real attack rather than
     * being politeness: without it anybody who knows a login identifier can make somebody's inbox
     * ring indefinitely, and the customer learns to ignore the message that matters.
     */
    public static final Duration COOLING_OFF = Duration.ofMinutes(5);

    private static final String AUDIT_TARGET_TYPE = "RecoveryRequest";
    private static final String PRODUCER = "identity";
    private static final int EVENT_VERSION = 1;

    @NonNull private final RecoveryRequestStore<Connection> requests;
    @NonNull private final CredentialStore<Connection> credentials;
    @NonNull private final SessionStore<Connection> sessions;
    @NonNull private final PasswordDeriver deriver;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final SecureRandom randomness;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;

    /**
     * Begins recovery, if this identifier names somebody who can recover.
     *
     * @return the request and its token, for a notifier that does not exist yet. <strong>Empty is
     *     one answer for every reason</strong>, and the caller must answer its own caller
     *     identically either way
     */
    public Optional<Initiated> initiate(Connection unitOfWork, LoginIdentifier login) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(login, "login must not be null");

        Instant at = Instant.now(clock);

        // Cancel first, so a second initiation kills the first token. Without it an attacker who
        // initiated once keeps a live token while the customer initiates again and believes they
        // have fixed their account - two valid ways in, which is one more than recovery may have.
        //
        // This runs before the cooling-off check rather than after, and the order matters: the
        // NOT EXISTS below counts requests of EVERY status, so a request cancelled here still
        // enforces cooling-off. Cancelling did not buy the caller a fresh attempt.
        requests.cancelLiveFor(unitOfWork, login, at);

        SingleUseToken token = SingleUseToken.issue(randomness);
        Optional<RecoveryRequest> created =
                requests.initiate(
                        unitOfWork,
                        login,
                        ContactChannelKind.EMAIL,
                        token,
                        at,
                        at.plus(TOKEN_LIFETIME),
                        at.minus(COOLING_OFF));

        return created.map(
                request -> {
                    audit(
                            unitOfWork,
                            at,
                            IdentityAuditAction.RECOVERY_INITIATED,
                            request,
                            AuditOutcome.SUCCEEDED,
                            // The IDENTITY as well as the channel, and a test found it missing.
                            // The record's target is the request, so without this an investigator
                            // asking "what happened to this account?" cannot find recovery
                            // initiations for it at all - and initiation is the step that shows a
                            // takeover IN PROGRESS rather than after it succeeded.
                            "identity=" + request.identityId() + " channel=" + request.channelId());
                    announce(unitOfWork, at, "identity.RecoveryInitiated", request);
                    return new Initiated(request, token);
                });
    }

    /**
     * Spends a token and replaces the credential.
     *
     * <p><strong>One transaction</strong>: the request is consumed, the old credential superseded,
     * the new one inserted, <em>every</em> session revoked, the audit record written and the event
     * queued. A failure anywhere leaves none of it.
     *
     * @return whether recovery completed. <strong>False is one answer for every reason</strong> —
     *     unknown request, wrong token, replayed, expired, or the credential changed since
     *     initiation
     */
    public boolean complete(
            Connection unitOfWork,
            RecoveryRequestId id,
            com.finapp.sharedkernel.security.Sensitive<String> presentedToken,
            RawPassword replacement) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(presentedToken, "presentedToken must not be null");
        Objects.requireNonNull(replacement, "replacement must not be null");

        Instant at = Instant.now(clock);
        Optional<RecoveryRequest> consumed =
                requests.consume(unitOfWork, id, SingleUseToken.of(presentedToken), at);
        if (consumed.isEmpty()) {
            return false;
        }

        RecoveryRequest request = consumed.get();
        request.credentialId()
                .ifPresent(previous -> credentials.supersede(unitOfWork, previous, at));
        credentials.insert(
                unitOfWork,
                Credential.forPassword(
                        ids,
                        clock,
                        request.identityId(),
                        CredentialType.PASSWORD,
                        deriver,
                        replacement));

        // EVERY session, with no spare - because recovery issues none, so there is nothing to keep.
        //
        // Both directions matter. If the attacker recovered, the customer's session must die; if the
        // customer recovered because they suspected a compromise, the attacker's must. A credential
        // change spares the session performing it (P1-TSK-014); this has no such session.
        int ended = sessions.revokeAllFor(unitOfWork, request.identityId(), at);

        audit(
                unitOfWork,
                at,
                IdentityAuditAction.RECOVERY_COMPLETED,
                request,
                AuditOutcome.SUCCEEDED,
                "identity=" + request.identityId() + " sessionsRevoked=" + ended);
        announce(unitOfWork, at, "identity.RecoveryCompleted", request);
        return true;
    }

    // -----------------------------------------------------------------

    /** A request and its token. The token is a per-call return value, never retained. */
    public record Initiated(RecoveryRequest request, SingleUseToken token) {
        public Initiated {
            Objects.requireNonNull(request, "request must not be null");
            Objects.requireNonNull(token, "token must not be null");
        }
    }

    /**
     * Announces a recovery step.
     *
     * <p><strong>No token in the payload, and the guard would not have stopped one.</strong>
     * {@code EventPayload}'s charset is {@code [A-Za-z0-9_-]}, which a base64url token satisfies
     * perfectly — {@code P1-TSK-009} recorded that limit in as many words: <em>"a charset, not a
     * secret detector"</em>. What keeps the token out is that this event declares no field for it,
     * which is a design property rather than something the builder enforces, and a test asserts it.
     *
     * <p>No login identifier either: the stream reaches systems with different access control, and
     * knowing an identifier is in use tells a reader an account exists ({@code INV-IDN-07}).
     */
    private void announce(
            Connection unitOfWork, Instant at, String eventType, RecoveryRequest request) {
        Correlation correlation = correlation();
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        request.id(),
                        AUDIT_TARGET_TYPE,
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        CausationId.of(correlation.correlationId().value())),
                EventPayload.of()
                        .with("recoveryRequestId", request.id().value().toString())
                        .with("identityId", request.identityId().value().toString())
                        .with("channelId", request.channelId().value().toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private void audit(
            Connection unitOfWork,
            Instant at,
            IdentityAuditAction action,
            RecoveryRequest request,
            AuditOutcome outcome,
            String changeSummary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        action,
                        AUDIT_TARGET_TYPE,
                        request.id().value().toString(),
                        Optional.empty(),
                        outcome,
                        correlation().correlationId(),
                        Optional.of(changeSummary)));
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A recovery operation must run inside a correlation scope"));
    }
}
