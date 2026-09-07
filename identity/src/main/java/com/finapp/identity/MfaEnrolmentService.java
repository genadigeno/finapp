package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Enrolling a second factor (`P1-TSK-017`, {@code INV-IDN-05}).
 *
 * <h2>The secret is emitted exactly once, and that is a bound rather than a denial</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §184 says the secret is <em>"never emitted"</em>, and {@code
 * INV-AUD-02} forbids credentials in API responses. <strong>Neither can be satisfied literally</strong>:
 * the customer must receive the secret or MFA cannot work, and the QR code <em>is</em> the secret in
 * base32.
 *
 * <p>So what is delivered is the tightest honest bound: it leaves the server <strong>once</strong>,
 * in the response to the request that created it, to the <strong>proven owner</strong> of the
 * session, and it is never retrievable afterwards — no read path returns it, and a customer who
 * loses it re-enrols. That is stated here rather than discovered later, and it is tested.
 *
 * <h2>Starting an enrolment does not add a factor</h2>
 *
 * <p>{@link #begin} produces a {@code PENDING} row and nothing that can satisfy a challenge. Only
 * {@link #confirm}, on a valid code, makes it usable. Without that step, reaching this endpoint on
 * somebody else's session would be enough to attach a factor.
 */
public final class MfaEnrolmentService {

    /**
     * 160 bits, which is what RFC 4226 §4 R6 recommends and what every authenticator app expects.
     *
     * <p>Not 256: a longer secret is not stronger here — HMAC-SHA1's key is folded to the block
     * size — and a longer base32 string is one more thing for a customer to mistype when their
     * camera will not focus.
     */
    private static final int SECRET_BYTES = 20;

    public static final String AUDIT_TARGET_TYPE = "identity.MfaEnrolment";

    private final MfaEnrolmentStore<Connection> enrolments;
    private final SecretCipher cipher;
    private final TotpVerifier verifier;
    private final SecureRandom randomness;
    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;
    private final com.finapp.platform.outbox.OutboxWriter<Connection> outbox;

    public MfaEnrolmentService(
            MfaEnrolmentStore<Connection> enrolments,
            SecretCipher cipher,
            TotpVerifier verifier,
            SecureRandom randomness,
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            com.finapp.platform.outbox.OutboxWriter<Connection> outbox) {
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.cipher = Objects.requireNonNull(cipher, "cipher must not be null");
        this.verifier = Objects.requireNonNull(verifier, "verifier must not be null");
        this.randomness = Objects.requireNonNull(randomness, "randomness must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
    }

    /**
     * Begins an enrolment, returning the secret for the customer to scan.
     *
     * <p>Any pending enrolment is discarded first: a customer who closed the page before scanning
     * would otherwise hold a secret they can neither confirm nor remove. <strong>An ACTIVE factor is
     * untouched</strong> — discarding one here would let anyone with a session disable somebody's
     * second factor without proving anything.
     */
    public Started begin(Connection unitOfWork, IdentityId identityId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        Instant at = Instant.now(clock);
        enrolments.discardPending(unitOfWork, identityId, MfaFactorType.TOTP, at);

        byte[] secretBytes = new byte[SECRET_BYTES];
        randomness.nextBytes(secretBytes);
        Sensitive<String> secret = Sensitive.of(TotpVerifier.Base32.encode(secretBytes));

        TotpParameters parameters = TotpParameters.current();
        MfaEnrolment enrolment =
                MfaEnrolment.begin(
                        ids,
                        clock,
                        identityId,
                        MfaFactorType.TOTP,
                        cipher.encrypt(secret),
                        parameters);
        enrolments.insert(unitOfWork, enrolment);

        audit(unitOfWork, at, IdentityAuditAction.MFA_ENROLMENT_STARTED, enrolment,
                "type=" + enrolment.type());

        // No event. An enrolment nobody confirmed is not a fact about the account, and announcing
        // one would tell every consumer that a factor exists when none is usable.
        return new Started(enrolment, secret, parameters);
    }

    /**
     * Confirms a pending enrolment against a code the customer produced.
     *
     * @return whether the factor is now usable. <strong>False is one answer for every reason</strong>
     *     — no pending enrolment, a wrong code, a lost race — because a caller able to tell them
     *     apart learns whether an enrolment is in progress on an account
     */
    public boolean confirm(Connection unitOfWork, IdentityId identityId, String presentedCode) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");

        Optional<MfaEnrolment> pending =
                enrolments.findPending(unitOfWork, identityId, MfaFactorType.TOTP);
        if (pending.isEmpty()) {
            return false;
        }

        MfaEnrolment enrolment = pending.get();
        Sensitive<String> secret = cipher.decrypt(enrolment.encryptedSecret());
        if (!verifier.verify(secret, presentedCode, enrolment.parameters())) {
            // The enrolment stays PENDING and assurance is unchanged - this task's acceptance
            // criterion, on the path that produces it.
            return false;
        }

        Instant at = Instant.now(clock);
        if (!enrolments.confirm(unitOfWork, enrolment.id(), at)) {
            // Another instance confirmed it first. Reported as a failure rather than a success,
            // because this call did not do it and its audit record would claim otherwise.
            return false;
        }

        audit(unitOfWork, at, IdentityAuditAction.MFA_ENROLMENT_CONFIRMED, enrolment,
                "type=" + enrolment.type());
        announce(unitOfWork, at, enrolment);
        return true;
    }

    // -----------------------------------------------------------------

    private void audit(
            Connection unitOfWork,
            Instant at,
            IdentityAuditAction action,
            MfaEnrolment enrolment,
            String changeSummary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        action,
                        AUDIT_TARGET_TYPE,
                        enrolment.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation().correlationId(),
                        Optional.of(changeSummary)));
    }

    private void announce(Connection unitOfWork, Instant at, MfaEnrolment enrolment) {
        Correlation correlation = correlation();
        outbox.write(
                unitOfWork,
                new com.finapp.sharedkernel.event.EventEnvelope(
                        com.finapp.sharedkernel.event.EventId.next(ids),
                        "identity.MfaEnrolled",
                        1,
                        com.finapp.sharedkernel.event.EventEnvelope.CURRENT_SCHEMA_VERSION,
                        enrolment.identityId(),
                        "Identity",
                        at,
                        "identity",
                        correlation.correlationId(),
                        // The request is the cause - the P1-TSK-006 reasoning, unchanged.
                        com.finapp.sharedkernel.correlation.CausationId.of(
                                correlation.correlationId().value())),
                // The secret is NOT here, and EventPayload could not stop it: its charset accepts
                // base32 perfectly (the P1-TSK-009 finding). What keeps it out is that this event
                // declares no field for it.
                EventPayload.of()
                        .with("identityId", enrolment.identityId().value().toString())
                        .with("factorType", enrolment.type().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "An MFA enrolment must run inside a correlation scope: the"
                                            + " audit record carries the identifier, and a"
                                            + " fabricated one would point at no flow at all"
                                            + " (P0-TSK-014)"));
    }

    /**
     * A started enrolment, and the one moment its secret exists outside the database.
     *
     * <p>The secret is here because the caller must hand it to the customer. It is
     * {@code Sensitive}, so every rendering path masks it, and nothing stores this record.
     */
    public record Started(
            MfaEnrolment enrolment, Sensitive<String> secret, TotpParameters parameters) {}
}
