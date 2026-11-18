package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Another identity's sessions, administered (`P17-TSK-015`, gap G-12): listed, one ended, or all ended - without suspending the
 * person, so a lost laptop's session goes and its holder keeps the rest of their access.
 *
 * <p>Every rule is the session store's own, on the database's clock, so ten instances agree: a session is live by
 * {@code SessionStore}'s one predicate; one ends only if it belongs to the identity named ({@code revokeOwned}, the ownership in
 * the statement) and only while live, so a revocation racing the session's own request or another administrator's revocation
 * ends it once; "all" takes the identity's lock first ({@code revokeAllFor}). The administrator's own identity is refused, as the
 * role doors refuse it - their own sessions are their own doors' to end.
 *
 * <p>Ending a session that has already ended, or that is not the identity's, changes nothing and is answered as done: an answer
 * that told the two apart would be an oracle over other people's session identifiers, and the trail records no guessed one.
 */
@RequiredArgsConstructor
public final class SessionAdministration {

    private static final String IDENTITY = SessionRevocation.IDENTITY_TARGET_TYPE;

    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final SessionStore<Connection> sessions;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What an administrator's act on another's sessions came to. */
    public enum Outcome {
        /** Done: the session ended, or there was nothing live of that identity's to end. */
        DONE,
        /** The administrator named themselves. */
        SELF,
        /** No identity has that identifier. */
        NOT_FOUND
    }

    /** The answer to "end them all": the outcome, and how many ended. */
    public record AllRevoked(Outcome outcome, int revoked) {}

    /** The identity's live sessions, newest first, or empty when no identity has that identifier; recorded with the count. */
    public Optional<List<Session>> liveSessionsOf(Connection unitOfWork, IdentityId subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (identities.findById(unitOfWork, subject).isEmpty()) {
            return Optional.empty();
        }
        List<Session> live = sessions.findLiveFor(unitOfWork, subject);
        record(unitOfWork, IdentityAuditAction.SESSIONS_READ, IDENTITY, subject.value().toString(), Optional.empty(),
                "live=" + live.size());
        return Optional.of(live);
    }

    /**
     * Ends one session of {@code subject}'s; recorded only when one ended, with the reason. An empty {@code session} - a value no
     * session identifier can be - ends nothing, after the same refusals, and is answered as done: one that did not exist.
     */
    public Outcome revoke(Connection unitOfWork, IdentityId subject, Optional<SessionId> session, IdentityId actor, String reason) {
        Objects.requireNonNull(session, "session must not be null");
        Optional<Outcome> refused = refusal(unitOfWork, subject, actor, reason);
        if (refused.isPresent()) {
            return refused.get();
        }
        if (session.isEmpty()) {
            return Outcome.DONE;
        }
        Instant at = Instant.now(clock);
        OptionalLong ended = sessions.revokeOwned(unitOfWork, session.get(), subject, at);
        if (ended.isPresent()) {
            record(unitOfWork, IdentityAuditAction.SESSION_REVOKED_BY_ADMINISTRATOR, SessionRevocation.SESSION_TARGET_TYPE,
                    session.get().value().toString(), Optional.of(reason), "identity=" + subject.value() + ", lifetimeSeconds=" + ended.getAsLong());
        }
        return Outcome.DONE;
    }

    /** Ends every live session of {@code subject}'s, under the identity's lock; recorded with the count, however small. */
    public AllRevoked revokeAll(Connection unitOfWork, IdentityId subject, IdentityId actor, String reason) {
        Optional<Outcome> refused = refusal(unitOfWork, subject, actor, reason);
        if (refused.isPresent()) {
            return new AllRevoked(refused.get(), 0);
        }
        int revoked = sessions.revokeAllFor(unitOfWork, subject, Instant.now(clock));
        record(unitOfWork, IdentityAuditAction.SESSION_REVOKED_BY_ADMINISTRATOR, IDENTITY, subject.value().toString(),
                Optional.of(reason), "sessionsRevoked=" + revoked);
        return new AllRevoked(Outcome.DONE, revoked);
    }

    private Optional<Outcome> refusal(Connection unitOfWork, IdentityId subject, IdentityId actor, String reason) {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (subject.equals(actor)) {
            return Optional.of(Outcome.SELF);
        }
        if (identities.findById(unitOfWork, subject).isEmpty()) {
            return Optional.of(Outcome.NOT_FOUND);
        }
        return Optional.empty();
    }

    private void record(
            Connection unitOfWork, IdentityAuditAction action, String targetType, String targetId, Optional<String> reason,
            String summary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        action,
                        targetType,
                        targetId,
                        reason,
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("a session's administration runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of(summary)));
    }
}
