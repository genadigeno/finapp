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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
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

    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final SecretCipher cipher;
    @NonNull private final TotpVerifier verifier;
    @NonNull private final SecureRandom randomness;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final com.finapp.platform.outbox.OutboxWriter<Connection> outbox;

    /**
     * Begins an enrolment, returning the secret for the customer to scan.
     *
     * <p>Any pending enrolment is discarded first: a customer who closed the page before scanning
     * would otherwise hold a secret they can neither confirm nor remove. <strong>An ACTIVE factor is
     * untouched</strong> — discarding one here would let anyone with a session disable somebody's
     * second factor without proving anything.
     *
     * @param currentAssurance the level of the session asking. <strong>Replacing a confirmed factor
     *     requires {@code MULTI_FACTOR}</strong>; adding a first one does not, because it cannot
     * @return the started enrolment, or empty when a factor already exists and the caller is not
     *     assured enough to replace it
     */
    public Optional<Started> begin(
            Connection unitOfWork, IdentityId identityId, AssuranceLevel currentAssurance) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(identityId, "identityId must not be null");
        Objects.requireNonNull(currentAssurance, "currentAssurance must not be null");

        Instant at = Instant.now(clock);

        // REPLACING a confirmed factor requires the confirmed factor. Adding a FIRST one does not,
        // because it cannot: you cannot demand a second factor to add your first.
        //
        // Found by `P1-TSK-019`'s probe, and it was a real defect. Before this check, an attacker
        // holding a stolen password could begin a second enrolment with a secret THEY control - and
        // the only thing that stopped them was the partial unique index refusing a second ACTIVE
        // row, which surfaced as an unhandled storage exception and a 500. The security property
        // held by accident of a uniqueness constraint rather than by a decision, and removing that
        // index for any reason would have made a stolen password enough to swap somebody's
        // authenticator - INV-IDN-05's bypass in its purest form.
        //
        // Refused HERE rather than at confirmation, deliberately: the attacker never receives a
        // secret at all, and a legitimate customer with a new phone is told at the operation they
        // initiated rather than after copying a QR code.
        if (enrolments.findActive(unitOfWork, identityId, MfaFactorType.TOTP).isPresent()
                && !currentAssurance.atLeast(AssuranceLevel.MULTI_FACTOR)) {
            auditRefusal(unitOfWork, at, identityId, currentAssurance);
            return Optional.empty();
        }

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
        return Optional.of(new Started(enrolment, secret, parameters));
    }

    /**
     * Records a refused enrolment.
     *
     * <p>This is the trace of somebody with a stolen password trying to swap a second factor, which
     * is exactly what an investigator wants and what no other record would show — the request is
     * otherwise indistinguishable from a customer tapping the wrong button.
     */
    private void auditRefusal(
            Connection unitOfWork, Instant at, IdentityId identityId, AssuranceLevel presented) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        at,
                        IdentityAuditAction.MFA_ENROLMENT_STARTED,
                        AUDIT_TARGET_TYPE,
                        identityId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.FAILED,
                        correlation().correlationId(),
                        Optional.of("refused=replacingAFactorRequiresIt presented=" + presented)));
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
        java.util.OptionalLong matchedStep =
                verifier.verify(secret, presentedCode, enrolment.parameters());
        if (matchedStep.isEmpty()) {
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

        // The confirming code is CONSUMED, added by `P1-TSK-018`. Without it, the code that
        // confirms an enrolment at step N is still usable for a challenge at step N - a replay
        // across two operations, and RFC 6238 5.2 does not care which operation the first use was.
        // The result is deliberately ignored: this row has just become ACTIVE with no prior step,
        // so the only way it fails is a concurrent challenge, which has already consumed the step.
        enrolments.consumeStep(unitOfWork, enrolment.id(), matchedStep.getAsLong());

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
