package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Replaces a session's identifier on a privilege change (`P1-TSK-015`, ADR-0030).
 *
 * <h2>Session fixation, and why elevating in place is the bug</h2>
 *
 * <p>If a step-up left the identifier unchanged, an identifier stolen <em>before</em> the elevation
 * would become elevated behind the legitimate user's back: the attacker does nothing, waits for the
 * customer to complete a second factor, and inherits it. ADR-0030 states it as a rule — <em>"step-up
 * does not mutate the session's level in place"</em> — and this is that rule.
 *
 * <h2>Revoke first, and issue only if the revoke won</h2>
 *
 * <p>That ordering is the concurrency property rather than a matter of style.
 * {@link SessionStore#revoke} is conditional and its row count is the outcome, so two instances
 * rotating the same session produce <strong>one</strong> new session and the loser issues nothing.
 *
 * <p>Inserting first and revoking afterwards would leave the loser's session live — a rotation that
 * handed out two identifiers where there should be one, with both usable. That is the
 * {@code P1-TSK-008} conditional-supersede pattern, applied to sessions.
 *
 * <h2>What is carried forward, and the one that matters</h2>
 *
 * <p>The identity, the device, and — <strong>preserved rather than reset</strong> — the absolute
 * expiry. If rotation reset it, anyone able to trigger one could hold a session indefinitely: step
 * up, rotate, step up again. The absolute lifetime would become advisory, which is exactly the
 * failure {@code P1-TSK-013} closed on the idle bound with {@code LEAST(…, absolute_expires_at)},
 * reappearing through a different door. <strong>A step-up must not extend how long you can stay
 * logged in.</strong> A customer who wants a fresh absolute bound authenticates again, which is an
 * issue and not a rotation.
 *
 * <p>The idle bound <em>is</em> fresh: the session is demonstrably being used at this moment.
 *
 * <p><strong>Assurance is never inferred.</strong> The caller states the target level. Silently
 * raising it would be {@code INV-IDN-05}'s bypass with extra steps; silently preserving it would
 * make step-up a no-op nobody noticed.
 *
 * <h2>Login needs no rotation, and that is structural</h2>
 *
 * <p>Classic fixation is the attacker planting an identifier that the victim then authenticates
 * <em>with</em>. This platform cannot be attacked that way: a session is created by the server with
 * a token from {@link SecureRandom}, and there is no path by which a client supplies one.
 * {@link SessionToken#of} exists to wrap a presented value <em>for lookup</em> and never for issue.
 * A test asserts that rather than a redundant rotation step implementing it.
 */
public final class SessionRotation {

    /** What an audit record for a rotation points at: the session that was replaced. */
    public static final String AUDIT_TARGET_TYPE = "identity.Session";

    private final SessionStore<Connection> sessions;
    private final SecureRandom randomness;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;

    public SessionRotation(
            SessionStore<Connection> sessions,
            SecureRandom randomness,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        this.sessions = Objects.requireNonNull(sessions, "sessions must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * Replaces {@code current} with a new session at {@code toAssurance}.
     *
     * @param current the session being replaced. Must be the aggregate as read, not a stale copy
     * @param idleTimeout how long the replacement may sit unused. <strong>A {@code Duration}, not a
     *     {@code SessionPolicy}</strong>, and the completion gate made that change: this uses only
     *     the idle half, and a parameter whose other half is silently ignored is a trap for the
     *     caller who passes {@code SessionPolicy.current()} and reasonably expects both to apply.
     *     The absolute bound is inherited, so there is nothing here for a policy to say about it
     * @return the new session and its token, or empty if {@code current} was no longer live — in
     *     which case <strong>nothing was issued</strong>. A rotation of a dead session must not mint
     *     a live one, which is what makes a lost race safe rather than merely unlikely
     */
    public Optional<Rotated> rotate(
            Connection unitOfWork,
            Session current,
            AssuranceLevel toAssurance,
            Duration idleTimeout) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(toAssurance, "toAssurance must not be null");
        Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");

        Instant at = Instant.now(clock);

        // Revoke FIRST. The row count is the gate: if another instance got here first, this returns
        // false and nothing below runs.
        if (!sessions.revoke(unitOfWork, current.id(), at)) {
            return Optional.empty();
        }

        // A token independent of its predecessor. Deriving one from the old would make the stolen
        // identifier a key to its replacement, which is the fixation defence undone.
        SessionToken token = SessionToken.issue(randomness);
        Session replacement =
                Session.rehydrate(
                        SessionId.next(ids),
                        current.identityId(),
                        token.hash(),
                        toAssurance,
                        SessionStatus.ACTIVE,
                        at,
                        idleBoundFrom(at, idleTimeout, current.absoluteExpiresAt()),
                        // PRESERVED. See the class javadoc: resetting it makes the absolute
                        // lifetime advisory for anybody who can trigger a rotation.
                        current.absoluteExpiresAt(),
                        current.device().orElse(null),
                        null);
        sessions.insert(unitOfWork, replacement);

        audit(unitOfWork, at, current, replacement);
        return Optional.of(new Rotated(replacement, token));
    }

    // -----------------------------------------------------------------

    /**
     * The fresh idle bound, clamped to the inherited absolute bound.
     *
     * <p>The same clamp {@code JdbcSessionStore.touch} applies, for the same reason and in a second
     * place because this constructs the row rather than updating it — and {@code Session}'s own
     * constructor refuses an idle bound beyond the absolute one, so without the clamp a rotation
     * near the end of a session's life would throw rather than produce a short-lived session.
     */
    private static Instant idleBoundFrom(
            Instant at, Duration idleTimeout, Instant absolute) {
        Instant extended = at.plus(idleTimeout);
        return extended.isAfter(absolute) ? absolute : extended;
    }

    private void audit(Connection unitOfWork, Instant at, Session replaced, Session replacement) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "A rotation must run inside a correlation scope: the"
                                                    + " audit record carries the identifier, and a"
                                                    + " fabricated one would point at no flow at all"
                                                    + " (P0-TSK-014)"));

        // ONE record, and deliberately not also a SessionRevoked. An investigator must be able to
        // tell "this session was ended" from "this session was replaced" - they are different facts,
        // and a rotation logged as a revocation reads as a logout that never happened. Naming both
        // identifiers is what makes the chain followable.
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.SESSION_ROTATED,
                        AUDIT_TARGET_TYPE,
                        replaced.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "replacedBy=" + replacement.id()
                                        + " assurance=" + replaced.assurance()
                                        + "->" + replacement.assurance())));
    }

    /**
     * A rotation's result: the new session, and the token to hand the client.
     *
     * <p>The token is returned because this is the only moment it exists in a form anybody can use —
     * the row holds its hash. A caller that loses it has issued a session nobody can present.
     */
    public record Rotated(Session session, SessionToken token) {}
}
