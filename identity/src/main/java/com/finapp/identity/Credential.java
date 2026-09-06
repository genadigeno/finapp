package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stored proof that somebody knows a secret - and never the secret (ADR-0032, {@code INV-IDN-01}).
 *
 * <h2>An entity, not an aggregate</h2>
 *
 * <p>A credential has no meaning apart from the identity it authenticates, and the rule <em>at most
 * one active credential per identity and type</em> is an invariant across the set - which is
 * exactly what an aggregate boundary is for ({@code PHASE_1_PLAN.md} §4). It is therefore never
 * independently addressable: no URL names one, and no API contract mentions one.
 *
 * <h2>What it stores, and why that is three facts rather than one</h2>
 *
 * <p>The derivation is the encoded output, and it is authoritative for verification. The algorithm
 * and the parameters are <strong>additionally</strong> stored as their own values, duplicating what
 * the encoded string already contains. That duplication is ADR-0032 Option D and is deliberate: the
 * encoded form is optimised for verifying, and the separate values are optimised for the question
 * an upgrade campaign asks - <em>which credentials are below current policy?</em> - which would
 * otherwise be a full scan with a parse per row.
 *
 * <h2>Immutable, and superseded rather than edited</h2>
 *
 * <p>{@link #supersede} returns a new instance; nothing here has a setter. A change of password
 * creates a <em>new</em> credential and supersedes this one, so the superseded rows record when
 * protection changed - {@code INV-HIST-01}'s reasoning applied to authentication. The database
 * enforces it too, with a trigger, because a {@code CHECK} constraint cannot see the previous row.
 *
 * <h2>What can never come out of it</h2>
 *
 * <p>There is no accessor returning anything from which the password could be recovered, because no
 * such value is held. The derivation itself is wrapped: it is not the plaintext, but it is
 * offline-crackable material, and {@link #toString()} omits it entirely rather than masking it.
 */
public final class Credential {

    private final CredentialId id;
    private final IdentityId identityId;
    private final CredentialType type;
    private final CredentialAlgorithm algorithm;
    private final DerivationParameters parameters;

    /**
     * The encoded derivation.
     *
     * <p><strong>Named so that {@code secretsAreWrapped} covers it.</strong> That rule matches
     * field-name words against a fixed vocabulary; {@code derivation} is not in it and
     * {@code credential} is. Naming the field accurately makes the existing build rule enforce the
     * wrapping, which is better than adding a rule and far better than remembering.
     */
    private final Sensitive<String> credentialDerivation;

    private final CredentialStatus status;
    private final Instant createdAt;
    private final Instant supersededAt;

    private Credential(
            CredentialId id,
            IdentityId identityId,
            CredentialType type,
            CredentialAlgorithm algorithm,
            DerivationParameters parameters,
            Sensitive<String> credentialDerivation,
            CredentialStatus status,
            Instant createdAt,
            Instant supersededAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.identityId = Objects.requireNonNull(identityId, "identityId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm must not be null");
        this.parameters = Objects.requireNonNull(parameters, "parameters must not be null");
        this.credentialDerivation =
                Objects.requireNonNull(credentialDerivation, "derivation must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.supersededAt = supersededAt;

        if (!algorithm.produces(credentialDerivation.expose())) {
            // The domain half of INV-IDN-01, and the reason it is here rather than only in the
            // column: a derivation that is not in the algorithm's encoded form is either a
            // plaintext or a value from a function nobody recorded. Neither may become a row.
            //
            // The message never repeats the value - saying what was rejected would put it in a log
            // line, which is the disclosure this check exists to prevent.
            throw new IllegalArgumentException(
                    "a " + algorithm + " derivation must be in that algorithm's encoded form");
        }
        if ((status == CredentialStatus.ACTIVE) != (supersededAt == null)) {
            // The two must agree. A superseded credential with no timestamp cannot be aged, and an
            // active one with a timestamp is a contradiction the database also refuses.
            throw new IllegalArgumentException(
                    "supersededAt must be present exactly when the credential is not ACTIVE");
        }
    }

    /**
     * Derives a new credential from a plaintext, {@link CredentialStatus#ACTIVE}.
     *
     * <h2>The deriver is passed in, and that is what makes {@code INV-IDN-02} mean something</h2>
     *
     * <p>An earlier version of this factory took the algorithm, the parameters <em>and</em> the
     * finished derivation as three independent arguments. It compiled, it stored, and it let a
     * caller record <strong>parameters that were not the ones used</strong> - the completion gate
     * probed it and found a credential whose columns said {@code m=19456,t=2,p=1} while its
     * derivation had been produced at {@code m=1024,t=1,p=1}, with {@code isWeakerThan(current())}
     * answering <em>false</em>.
     *
     * <p>That is {@code INV-IDN-02} satisfied in form and defeated in substance. The invariant is
     * not "the columns are populated", it is "the recorded parameters are the ones that produced
     * this derivation" - and a credential that misreports its own strength is <em>worse</em> than
     * one that records nothing, because an upgrade campaign would skip it while believing it had
     * been assessed.
     *
     * <p>So the three facts now come from one place. The deriver produces the derivation and states
     * its own algorithm and parameters; there is no argument a caller can get wrong, because there
     * is no argument. The duplication ADR-0032 Option D takes deliberately is now duplication that
     * <strong>cannot</strong> disagree.
     *
     * <p>The plaintext is used and not retained: it reaches the deriver and nothing else, and
     * nothing on the returned object can produce it.
     */
    public static Credential forPassword(
            IdGenerator ids,
            Clock clock,
            IdentityId identityId,
            CredentialType type,
            PasswordDeriver deriver,
            RawPassword password) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(deriver, "deriver must not be null");
        Objects.requireNonNull(password, "password must not be null");
        return new Credential(
                CredentialId.next(ids),
                identityId,
                type,
                deriver.algorithm(),
                deriver.currentParameters(),
                deriver.derive(password),
                CredentialStatus.ACTIVE,
                Instant.now(clock),
                null);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static Credential rehydrate(
            CredentialId id,
            IdentityId identityId,
            CredentialType type,
            CredentialAlgorithm algorithm,
            DerivationParameters parameters,
            Sensitive<String> credentialDerivation,
            CredentialStatus status,
            Instant createdAt,
            Instant supersededAt) {
        return new Credential(
                id,
                identityId,
                type,
                algorithm,
                parameters,
                credentialDerivation,
                status,
                createdAt,
                supersededAt);
    }

    /**
     * {@code ACTIVE → SUPERSEDED}. Terminal.
     *
     * <p>Superseding an already-superseded credential throws rather than being a quiet no-op: a
     * caller that believes it is retiring a credential which was retired months ago has a defect
     * worth a stack trace ({@code INV-LIFE-04}), and the aggregate is where that is rejected rather
     * than at whatever API happens to call it ({@code INV-LIFE-02}).
     */
    public Credential supersede(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(CredentialStatus.SUPERSEDED)) {
            throw new IllegalCredentialTransitionException(status, CredentialStatus.SUPERSEDED);
        }
        return new Credential(
                id,
                identityId,
                type,
                algorithm,
                parameters,
                credentialDerivation,
                CredentialStatus.SUPERSEDED,
                createdAt,
                Instant.now(clock));
    }

    /** Whether this credential may currently be used to authenticate. */
    public boolean isActive() {
        return status == CredentialStatus.ACTIVE;
    }

    /**
     * Whether this credential was derived more weakly than {@code policy} requires.
     *
     * <p>The question {@code P1-TSK-008}'s upgrade-on-use asks, and the question an upgrade
     * campaign asks of the whole table. It is answerable at all only because the parameters were
     * recorded per credential ({@code INV-IDN-02}).
     */
    public boolean isWeakerThan(DerivationParameters policy) {
        return parameters.isWeakerThan(policy);
    }

    public CredentialId id() {
        return id;
    }

    public IdentityId identityId() {
        return identityId;
    }

    public CredentialType type() {
        return type;
    }

    public CredentialAlgorithm algorithm() {
        return algorithm;
    }

    public DerivationParameters parameters() {
        return parameters;
    }

    /**
     * The encoded derivation, for verification and for persistence.
     *
     * <p>Returns the wrapper rather than the string, so a caller has to expose it deliberately and
     * cannot interpolate it into a log line by accident.
     */
    public Sensitive<String> credentialDerivation() {
        return credentialDerivation;
    }

    public CredentialStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Optional<Instant> supersededAt() {
        return Optional.ofNullable(supersededAt);
    }

    /** Identity is the identifier, not the contents - as for every other aggregate here. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Credential credential && id.equals(credential.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Omits the derivation entirely rather than masking it.
     *
     * <p>{@code Sensitive} would render {@code «redacted»}, which is safe; leaving the field out
     * says something stronger, which is that this object has no business carrying that value into
     * a log line at all. What remains identifies the row and describes its strength, both of which
     * are what somebody reading a log actually needs.
     */
    @Override
    public String toString() {
        return "Credential["
                + id
                + ", identity="
                + identityId
                + ", "
                + type
                + ", "
                + algorithm
                + ", "
                + status
                + "]";
    }
}
