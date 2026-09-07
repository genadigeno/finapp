package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A second factor an identity is enrolling, or has enrolled (`P1-TSK-017`, `INV-IDN-05`).
 *
 * <h2>Two phases, and the second one is the whole security property</h2>
 *
 * <p>An enrolment is {@link MfaFactorStatus#PENDING} from the moment a secret is issued until the
 * customer returns a valid code, proving they hold it. Only then does it become
 * {@link MfaFactorStatus#ACTIVE}.
 *
 * <p><strong>Without that step, starting an enrolment would be enough to add a factor.</strong>
 * Anyone who reached this endpoint on somebody else's account would have attached a secret the
 * victim cannot generate codes for — locking them out at best, and at worst adding a factor only
 * the attacker can satisfy. Confirmation is what makes an enrolment a statement about the person
 * rather than about the request.
 *
 * <h2>The secret is never on this aggregate in plaintext</h2>
 *
 * <p>What the row holds is a ciphertext, its nonce and the key version ({@code INV-IDN-08}). The
 * plaintext exists in exactly two moments: when it is generated, and inside a verification. It is
 * never a field here, so no {@code toString}, serialiser or logger can reach one.
 */
public final class MfaEnrolment {

    private final MfaEnrolmentId id;
    private final IdentityId identityId;
    private final MfaFactorType type;
    private final SecretCipher.Encrypted secret;
    private final TotpParameters parameters;
    private final MfaFactorStatus status;
    private final Instant createdAt;
    private final Instant confirmedAt;
    private final Instant discardedAt;
    private final java.util.OptionalLong lastUsedStep;

    private MfaEnrolment(
            MfaEnrolmentId id,
            IdentityId identityId,
            MfaFactorType type,
            SecretCipher.Encrypted secret,
            TotpParameters parameters,
            MfaFactorStatus status,
            Instant createdAt,
            Instant confirmedAt,
            Instant discardedAt,
            java.util.OptionalLong lastUsedStep) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.identityId = Objects.requireNonNull(identityId, "identityId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.secret = Objects.requireNonNull(secret, "secret must not be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.confirmedAt = confirmedAt;
        this.discardedAt = discardedAt;
        this.lastUsedStep =
                Objects.requireNonNull(lastUsedStep, "lastUsedStep must not be null");

        if ((status == MfaFactorStatus.ACTIVE) != (confirmedAt != null)) {
            // The same rule V005 applies to revoked_at: a status and its timestamp are one fact,
            // and a row where they disagree is a row nobody can interpret.
            throw new IllegalArgumentException(
                    "An enrolment is ACTIVE if and only if it has been confirmed");
        }
        if ((status == MfaFactorStatus.DISCARDED) != (discardedAt != null)) {
            throw new IllegalArgumentException(
                    "An enrolment is DISCARDED if and only if it has a discard time");
        }
        if (confirmedAt != null && confirmedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("An enrolment cannot be confirmed before it began");
        }
        if (discardedAt != null && discardedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("An enrolment cannot be discarded before it began");
        }
    }

    /** Begins an enrolment. The factor does nothing until it is confirmed. */
    public static MfaEnrolment begin(
            IdGenerator ids,
            Clock clock,
            IdentityId identityId,
            MfaFactorType type,
            SecretCipher.Encrypted secret,
            TotpParameters parameters) {
        return new MfaEnrolment(
                MfaEnrolmentId.next(ids),
                identityId,
                type,
                secret,
                parameters,
                MfaFactorStatus.PENDING,
                Instant.now(clock),
                null,
                null,
                java.util.OptionalLong.empty());
    }

    /** Rebuilds an enrolment the database has already validated. */
    public static MfaEnrolment rehydrate(
            MfaEnrolmentId id,
            IdentityId identityId,
            MfaFactorType type,
            SecretCipher.Encrypted secret,
            TotpParameters parameters,
            MfaFactorStatus status,
            Instant createdAt,
            Instant confirmedAt,
            Instant discardedAt,
            java.util.OptionalLong lastUsedStep) {
        return new MfaEnrolment(
                id,
                identityId,
                type,
                secret,
                parameters,
                status,
                createdAt,
                confirmedAt,
                discardedAt,
                lastUsedStep);
    }

    /**
     * Whether this factor may satisfy a challenge.
     *
     * <p><strong>The definition of usable, kept in the domain even though the store also filters on
     * it.</strong> The `P1-TSK-013` finding: a rule that lives only in a `WHERE` clause is a rule no
     * reader and no architecture test will ever find. The SQL is an implementation of this; this is
     * what it implements.
     */
    public boolean isUsable() {
        return status == MfaFactorStatus.ACTIVE;
    }

    public MfaEnrolmentId id() {
        return id;
    }

    public IdentityId identityId() {
        return identityId;
    }

    public MfaFactorType type() {
        return type;
    }

    public SecretCipher.Encrypted encryptedSecret() {
        return secret;
    }

    public TotpParameters parameters() {
        return parameters;
    }

    public MfaFactorStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> confirmedAt() {
        return Optional.ofNullable(confirmedAt);
    }

    public Optional<Instant> discardedAt() {
        return Optional.ofNullable(discardedAt);
    }

    /**
     * The time step of the last accepted code, if any.
     *
     * <p>Read for diagnosis and for tests; the <strong>decision</strong> is never made from it in
     * Java. {@code MfaEnrolmentStore.consumeStep} compares and advances in one statement, because a
     * read-then-compare here would be a race two instances could both win.
     */
    public java.util.OptionalLong lastUsedStep() {
        return lastUsedStep;
    }

    /**
     * Deliberately says nothing about the secret.
     *
     * <p>`Encrypted` masks itself, so this would already be safe — and stating it here is the
     * `P1-TSK-005` lesson that accidental safety ends the day somebody changes the shape.
     */
    @Override
    public String toString() {
        return "MfaEnrolment[id=" + id + ", type=" + type + ", status=" + status + "]";
    }
}
