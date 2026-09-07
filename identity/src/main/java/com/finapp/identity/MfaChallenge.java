package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Proving a second factor, and elevating the session that proved it (`P1-TSK-018`,
 * {@code INV-IDN-05}).
 *
 * <h2>Replay needs its own mechanism, because a challenge has nothing to consume</h2>
 *
 * <p>{@code P1-TSK-017} made confirmation replay-safe by consuming the {@code PENDING} row: a second
 * attempt found nothing to confirm. A challenge leaves the factor {@code ACTIVE}, so there is no
 * state to spend — and a TOTP code stays valid for roughly ninety seconds under the ±1 window.
 * Without a new mechanism, a code captured in that window is replayable and <em>"one-time
 * password"</em> is simply false.
 *
 * <p>{@link MfaEnrolmentStore#consumeStep} is that mechanism: the last accepted time step is
 * recorded, and anything at or before it is refused. RFC 6238 §5.2 asks for exactly that — not
 * merely "the same code twice", but no code at or before the last accepted step.
 *
 * <h2>Elevation rotates the identifier; it never mutates the level in place</h2>
 *
 * <p>{@link SessionRotation} was built for this by {@code P1-TSK-015} and has had no caller until
 * now. Elevating in place would let an identifier stolen <em>before</em> the step-up become elevated
 * behind the legitimate user's back: the attacker does nothing, waits for the customer to complete a
 * second factor, and inherits it.
 *
 * <p>The old session is revoked by the rotation, so no {@code PASSWORD} session survives the
 * step-up <em>on that identifier</em>. The identity's <em>other</em> sessions stay {@code PASSWORD},
 * which is correct rather than a bypass: any operation requiring {@code MULTI_FACTOR} refuses them.
 *
 * <h2>Throttling is not optional here</h2>
 *
 * <p>A six-digit code with a ±1 window is <strong>three valid values in a million</strong> per
 * attempt. RFC 4226 §7.3 requires throttling, and an unthrottled challenge is brute-forceable by
 * automation. Failures count against the account's existing lockout budget — see
 * {@link AuthenticationThrottle#recordFailureFor}.
 *
 * <p><strong>A replay is refused but not counted.</strong> The common cause is a client retrying
 * after a network timeout with the same code; the malicious cause requires already holding a valid
 * one. Counting it would let a flaky connection lock a customer out of their own account.
 */
public final class MfaChallenge {

    /** What an audit record for a challenge points at: the identity that was challenged. */
    public static final String AUDIT_TARGET_TYPE = "identity.Identity";

    private final MfaEnrolmentStore<Connection> enrolments;
    private final SecretCipher cipher;
    private final TotpVerifier verifier;
    private final SessionRotation rotation;
    private final AuthenticationThrottle throttle;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;

    public MfaChallenge(
            MfaEnrolmentStore<Connection> enrolments,
            SecretCipher cipher,
            TotpVerifier verifier,
            SessionRotation rotation,
            AuthenticationThrottle throttle,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.rotation = Objects.requireNonNull(rotation, "rotation must not be null");
        this.throttle = Objects.requireNonNull(throttle, "throttle must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
    }

    /**
     * Verifies a code against the session's identity and, on success, elevates the session.
     *
     * @param current the session the caller presented, already proven by the boundary
     * @return the replacement session and its token, or empty. <strong>Empty is one answer for
     *     every reason</strong> — no factor, a wrong code, a replay, a lock, a lost race — because a
     *     caller able to tell them apart learns about the state of an account's defences
     */
    public Optional<SessionRotation.Rotated> elevate(
            Connection unitOfWork, Session current, String presentedCode, Duration idleTimeout) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");

        IdentityId identityId = current.identityId();

        // Refused before the work, unlike P1-TSK-011's password path - and the difference is that
        // there is nothing to disclose here. That endpoint had to pay for a derivation on every
        // failure because answering faster for a locked account would have told an attacker the
        // account exists; this caller already holds a session this platform issued for their own
        // identity, so uniform cost buys nothing.
        if (throttle.isLocked(unitOfWork, identityId)) {
            return refuse(unitOfWork, identityId, "locked");
        }

        Optional<MfaEnrolment> active =
                enrolments.findActive(unitOfWork, identityId, MfaFactorType.TOTP);
        if (active.isEmpty()) {
            // A PENDING factor lands here too, which is P1-TSK-017's acceptance criterion arriving
            // at the operation that would otherwise honour it: `findActive` never returns one.
            throttle.recordFailureFor(unitOfWork, identityId);
            return refuse(unitOfWork, identityId, "noFactor");
        }

        MfaEnrolment enrolment = active.get();
        Sensitive<String> secret = cipher.decrypt(enrolment.encryptedSecret());
        OptionalLong matchedStep = verifier.verify(secret, presentedCode, enrolment.parameters());
        if (matchedStep.isEmpty()) {
            throttle.recordFailureFor(unitOfWork, identityId);
            return refuse(unitOfWork, identityId, "wrongCode");
        }

        if (!enrolments.consumeStep(unitOfWork, enrolment.id(), matchedStep.getAsLong())) {
            // The code was right and its step is spent. NOT counted as a failure: see the class
            // javadoc - a client retrying after a timeout presents the same code, and locking
            // somebody out for their own network is a self-inflicted denial of service.
            return refuse(unitOfWork, identityId, "replayed");
        }

        Optional<SessionRotation.Rotated> rotated =
                rotation.rotate(unitOfWork, current, AssuranceLevel.MULTI_FACTOR, idleTimeout);
        if (rotated.isEmpty()) {
            // The session was revoked or elevated by another instance between the boundary proving
            // it and this line. Reported as a refusal rather than as a success: this call did not
            // produce a session, and an audit record claiming it did would be wrong for ever.
            return refuse(unitOfWork, identityId, "sessionGone");
        }

        // A correct code clears the run of failures - the same rule the password path applies, and
        // for the same reason: the account is demonstrably in its owner's hands.
        throttle.clear(unitOfWork, identityId);
        audit(unitOfWork, identityId, AuditOutcome.SUCCEEDED, "elevated=MULTI_FACTOR");
        return rotated;
    }

    // -----------------------------------------------------------------

    /**
     * Every refusal is audited, and the reason is in the record rather than in the response.
     *
     * <p>An investigator needs to tell a wrong code from a challenge against an account with no
     * factor — the second is somebody probing. The <em>caller</em> gets one shape for all of them.
     */
    private Optional<SessionRotation.Rotated> refuse(
            Connection unitOfWork, IdentityId identityId, String reason) {
        audit(unitOfWork, identityId, AuditOutcome.FAILED, "reason=" + reason);
        return Optional.empty();
    }

    private void audit(
            Connection unitOfWork,
            IdentityId identityId,
            AuditOutcome outcome,
            String changeSummary) {
        Correlation correlation =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "An MFA challenge must run inside a correlation"
                                                    + " scope: the audit record carries the"
                                                    + " identifier, and a fabricated one would point"
                                                    + " at no flow at all (P0-TSK-014)"));

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        outcome == AuditOutcome.SUCCEEDED
                                ? IdentityAuditAction.MFA_CHALLENGE_SUCCEEDED
                                : IdentityAuditAction.MFA_CHALLENGE_FAILED,
                        AUDIT_TARGET_TYPE,
                        identityId.value().toString(),
                        Optional.empty(),
                        outcome,
                        correlation.correlationId(),
                        Optional.of(changeSummary)));
    }
}
