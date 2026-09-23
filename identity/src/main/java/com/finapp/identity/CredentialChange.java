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
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A logged-in person replaces their own password (`P1-TSK-033`).
 *
 * <h2>Composition, not new mechanism — and that is the point</h2>
 *
 * <p>Every piece existed before this task: {@link CredentialVerifier#matchCurrent} re-proves the
 * current password, {@link Credential#forPassword} derives the new one under current parameters,
 * {@link CredentialStore#supersede} moves the old row <em>conditionally</em>, {@link
 * SessionRevocation#revokeAllExcept} ends the attacker's sessions, and {@link
 * SessionRotation#rotate} replaces the caller's own identifier so a session stolen <em>before</em>
 * the change cannot outlive it. What this class contributes is the order and the transaction.
 *
 * <h2>The current password is re-proven, and that is the whole security of it</h2>
 *
 * <p>A session is a bearer credential. If holding one were enough to change the password, a
 * stolen session would be a permanent takeover — the thief locks the owner out and keeps the
 * account. Re-proving the current password means the session alone is not enough, and it is why
 * a wrong current password is <strong>counted into the lockout budget</strong> ({@code
 * P1-TSK-011}): a stolen session must not be an unthrottled password oracle.
 *
 * <h2>MFA is required only of an identity that has it</h2>
 *
 * <p>The plan's endpoint row reads {@code MULTI_FACTOR}; taken literally that makes the endpoint
 * unreachable for every password-only customer. The requirement is <em>conditional on whether a
 * factor exists</em>, which a boundary annotation cannot express because it is static per handler
 * — the exact {@code P1-TSK-019} re-enrolment finding. So the check lives here: an identity with a
 * confirmed factor must present a {@code MULTI_FACTOR} session; one without changes at {@code
 * PASSWORD}, with the current password as the re-proof. `PHASE_1_PLAN.md` §7 is corrected with the
 * provenance noted.
 *
 * <h2>Concurrency: the conditional supersede is the arbiter</h2>
 *
 * <p>Ten instances changing one password concurrently each read the same active credential and
 * each try to supersede it. {@link CredentialStore#supersede} returns whether <em>this</em> call
 * performed the transition; the row count is the outcome, so exactly one wins and nine are told
 * they lost — no second credential is inserted against a row somebody else already moved. The
 * session rotation's revoke-first conditional arbitrates the session race the same way.
 */
@RequiredArgsConstructor
public final class CredentialChange {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "identity";

    private static final int EVENT_VERSION = 1;

    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    @NonNull private final CredentialVerifier verifier;
    @NonNull private final CredentialStore<Connection> credentials;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final AuthenticationThrottle throttle;
    @NonNull private final SessionRevocation revocations;
    @NonNull private final SessionRotation rotation;
    @NonNull private final PasswordDeriver deriver;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;

    /**
     * Derives the new credential <strong>before the transaction opens</strong> (`P1-TSK-026`).
     *
     * <p>~46&nbsp;ms of CPU and ~19&nbsp;MiB per derivation (ADR-0032) must not be paid while
     * holding one of eight pooled connections, or a burst of password changes becomes
     * connection-timeout errors pointing at a database that is perfectly healthy. The identity is
     * the caller's own — {@code current.identityId()} — so the credential can be minted here with
     * nothing read from the database.
     */
    public Prepared prepare(IdentityId identityId, RawPassword newPassword) {
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(newPassword, "newPassword must not be null");
        return new Prepared(
                Credential.forPassword(
                        ids, clock, identityId, CredentialType.PASSWORD, deriver, newPassword));
    }

    /**
     * Applies a prepared change, inside the caller's transaction.
     *
     * @param current the caller's proven session, from the boundary
     * @param currentPassword the password being replaced, re-proven here
     * @param prepared the new credential, derived by {@link #prepare} before this transaction
     * @param idleTimeout the rotated session's idle bound
     * @return {@link Changed} with the rotated session; {@link AssuranceRequired} when an
     *     MFA-enrolled identity presented a password-only session — actionable, so distinguished
     *     ({@code P1-TSK-018}); or {@link Refused} for a wrong current password, a lock, a lost
     *     supersede race or a concurrently-killed session, which are <strong>one</strong> outcome
     *     so none is distinguishable (the {@link MfaChallenge} shape)
     */
    public Result apply(
            Connection unitOfWork,
            Session current,
            RawPassword currentPassword,
            Prepared prepared,
            Duration idleTimeout) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(currentPassword, "currentPassword must not be null");
        Objects.requireNonNull(prepared, "prepared must not be null");
        Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");

        IdentityId identityId = current.identityId();

        // A lock refuses even a correct current password (P1-TSK-011's rule): a lock a correct
        // guess clears is a signal the guess was right, and this endpoint is reachable only with a
        // session, so an attacker probing here already holds one.
        if (throttle.isLocked(unitOfWork, identityId)) {
            return refuse(unitOfWork, identityId, "locked");
        }

        // MFA required only of an identity that has it (see class javadoc). A MULTI_FACTOR session
        // satisfies it; a PASSWORD session against an identity WITH a confirmed factor does not.
        // Decided before any password work, so the distinct outcome discloses only what the caller
        // already knows - they enrolled MFA and are holding a password session.
        boolean hasFactor =
                enrolments.findActive(unitOfWork, identityId, MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            audit(unitOfWork, Instant.now(clock), identityId, AuditOutcome.FAILED,
                    "reason=assuranceTooLow");
            return new AssuranceRequired();
        }

        Optional<Credential> matched =
                verifier.matchCurrent(unitOfWork, identityId, currentPassword);
        if (matched.isEmpty()) {
            // A wrong current password IS counted: a stolen session must not be an unthrottled
            // oracle for the password it rides on.
            throttle.recordFailureFor(unitOfWork, identityId);
            return refuse(unitOfWork, identityId, "wrongCurrentPassword");
        }

        Instant at = Instant.now(clock);
        if (!credentials.supersede(unitOfWork, matched.get().id(), at)) {
            // Another instance replaced this credential between the match and here. Not a failure
            // to count - the caller knew the password - and not a success either: nothing this
            // call did took effect, so it reports the same refusal as any other lost race.
            return refuse(unitOfWork, identityId, "supersededConcurrently");
        }
        credentials.insert(unitOfWork, prepared.credential());

        // Every OTHER session dies now (INV-IDN-03), and the caller's own is rotated rather than
        // spared-then-kept: a password change is what somebody does after suspecting theft, so the
        // identifier they are holding must be replaced too - fixation defence (P1-TSK-015).
        int otherSessionsEnded = revocations.revokeAllExcept(unitOfWork, identityId, current.id());
        Optional<SessionRotation.Rotated> rotated =
                rotation.rotate(unitOfWork, current, current.assurance(), idleTimeout);

        // The change committed either way. If the caller's session was concurrently killed there is
        // no session to hand back - reported as Refused, which the boundary renders 401: the
        // caller's session IS gone, so re-authenticating (with the NEW password, which now works)
        // is exactly right, and the change is durable, audited and announced regardless.
        audit(unitOfWork, at, identityId, AuditOutcome.SUCCEEDED,
                "sessionsEnded=" + otherSessionsEnded + " rotated=" + rotated.isPresent());
        announce(unitOfWork, at, identityId);
        return rotated.<Result>map(Changed::new).orElseGet(Refused::new);
    }

    /** What a change attempt did. */
    public sealed interface Result permits Changed, AssuranceRequired, Refused {}

    /** The password changed and the caller's session was rotated. */
    public record Changed(SessionRotation.Rotated rotated) implements Result {
        public Changed {
            Objects.requireNonNull(rotated, "rotated must not be null");
        }
    }

    /**
     * An MFA-enrolled identity presented a password-only session. Actionable - step up and retry -
     * so distinguished from {@link Refused}, the {@code P1-TSK-018} distinction.
     */
    public record AssuranceRequired() implements Result {}

    /**
     * Uniform refusal: a wrong current password, a lock, a lost supersede race, or a session
     * killed concurrently. One outcome for all of them, so none is readable from the response.
     */
    public record Refused() implements Result {}

    // -----------------------------------------------------------------

    /** A new credential derived outside the transaction that will store it. */
    public record Prepared(Credential credential) {
        public Prepared {
            Objects.requireNonNull(credential, "credential must not be null");
        }
    }

    private Result refuse(Connection unitOfWork, IdentityId identityId, String reason) {
        // A refused change is audited: the reason lives in the record, never in the response, so an
        // investigator can tell a wrong password from a lock while the caller gets one empty shape.
        audit(unitOfWork, Instant.now(clock), identityId, AuditOutcome.FAILED, "reason=" + reason);
        return new Refused();
    }

    private void audit(
            Connection unitOfWork,
            Instant at,
            IdentityId identityId,
            AuditOutcome outcome,
            String changeSummary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.CREDENTIAL_CHANGED,
                        AUDIT_TARGET_TYPE,
                        identityId.value().toString(),
                        Optional.empty(),
                        outcome,
                        correlation().correlationId(),
                        Optional.of(changeSummary)));
    }

    private void announce(Connection unitOfWork, Instant at, IdentityId identityId) {
        Correlation correlation = correlation();
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        "identity.CredentialChanged",
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        identityId,
                        "Identity",
                        at,
                        PRODUCER,
                        correlation.correlationId(),
                        CausationId.of(correlation.correlationId().value())),
                // Identifiers only. No password, old or new, and no session token: the event
                // stream reaches systems with different access control (INV-AUD-02).
                EventPayload.of().with("identityId", identityId.value().toString()).toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A credential change must run inside a correlation scope:"
                                            + " the audit record and the event carry the identifier,"
                                            + " and a fabricated one would point at no flow at all"
                                            + " (P0-TSK-014)"));
    }
}
