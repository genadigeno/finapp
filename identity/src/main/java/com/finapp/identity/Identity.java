package com.finapp.identity;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A means by which someone proves they are present (ADR-0029).
 *
 * <p>Not a person, and not a customer. An Identity is a login: the thing that authenticates. One
 * Party may hold several — a retired login and its replacement, or a person who is also staff —
 * and staff hold an Identity and are never Customers at all. A model with one row for all three
 * cannot represent any of that, and unpicking it later means migrating identity data out of a table
 * financial records already reference, where {@code INV-HIST-01} forbids rewriting the history that
 * points at it.
 *
 * <h2>The Party reference is a value, not a foreign key</h2>
 *
 * <p>{@link #partyId()} is a {@code UUID} held by value. There is no database foreign key to
 * {@code party.party} and there is no compile-time dependency on the {@code party} module — the
 * modules cannot see each other, which {@code IdentityModuleIsolationTest} asserts.
 *
 * <p>That is ADR-0029's boundary made structural. A cross-schema FK is coupling neither Gradle nor
 * ArchUnit can see, and it would turn ADR-0001's stated escape — extracting a module into its own
 * service — into a data migration. Referential integrity across that edge is a domain rule,
 * enforced by the registration transaction that creates both in one commit.
 *
 * <p>The cost is real and is accepted: nothing at the database level stops an Identity referencing
 * a Party that does not exist. What prevents it is that the only code that creates an Identity
 * creates the Party in the same transaction, and that is a property a review can check and a test
 * can assert, rather than one the schema enforces.
 *
 * <h2>What it deliberately does not hold</h2>
 *
 * <p>No credential, no password, no derivation — {@code Credential} is an entity inside this
 * aggregate and arrives with {@code P1-TSK-007}. No email address either: an email is a contact
 * channel that changes and must be separately verified, and a login identifier that is also a
 * contact channel cannot be changed without changing how someone logs in
 * (`PHASE_1_PLAN.md` §4). The login identifier here is opaque.
 */
public final class Identity {

    private final IdentityId id;
    private final UUID partyId;
    private final LoginIdentifier loginIdentifier;
    private final IdentityStatus status;
    private final Instant createdAt;
    private final Instant statusChangedAt;

    private Identity(
            IdentityId id,
            UUID partyId,
            LoginIdentifier loginIdentifier,
            IdentityStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyId = Objects.requireNonNull(partyId, "partyId must not be null");
        this.loginIdentifier =
                Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
    }

    /**
     * Creates an Identity for a Party, {@link IdentityStatus#ACTIVE}.
     *
     * <p>Active immediately, unlike {@code Customer}, which opens {@code PENDING}. The asymmetry is
     * deliberate: a Customer is pending because a decision about it has not been taken, and no such
     * decision stands between creating a login and being able to use it. Whether the login is
     * <em>useful</em> depends on it having a credential, which is a different question and is
     * represented by there being no credential rather than by a state.
     *
     * @param partyId the Party this login belongs to, held by value (see the class documentation)
     */
    public static Identity create(
            IdGenerator ids, Clock clock, UUID partyId, LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(ids, "ids must not be null");
        return create(IdentityId.next(ids), clock, partyId, loginIdentifier);
    }

    /**
     * Creates an Identity whose identifier the caller already holds.
     *
     * <p>Added by {@code P1-TSK-026}, and the reason is worth stating because "pass the id in" is
     * otherwise an odd thing to want. Registration must derive the credential <strong>before the
     * transaction opens</strong> - Argon2id costs ~46 ms of CPU and ~19 MiB by design (ADR-0032),
     * and doing it while holding one of eight pooled connections turns a registration flood into
     * connection-timeout errors that point at the database ({@code PasswordDeriver}, {@code
     * P1-TSK-004}). A {@link Credential} records the identity it belongs to, so deriving early
     * means minting the identifier early.
     *
     * <p>That is unremarkable here rather than a concession: ADR-0013 makes identifiers
     * <em>application-minted</em> UUIDv7 values, so nothing is being borrowed from the database and
     * an identifier that is minted and then discarded costs nothing.
     */
    public static Identity create(
            IdentityId id, Clock clock, UUID partyId, LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new Identity(id, partyId, loginIdentifier, IdentityStatus.ACTIVE, now, now);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static Identity rehydrate(
            IdentityId id,
            UUID partyId,
            LoginIdentifier loginIdentifier,
            IdentityStatus status,
            Instant createdAt,
            Instant statusChangedAt) {
        return new Identity(id, partyId, loginIdentifier, status, createdAt, statusChangedAt);
    }

    /** {@code ACTIVE → SUSPENDED}. Reversible. */
    public Identity suspend(Clock clock) {
        return transitionTo(IdentityStatus.SUSPENDED, clock);
    }

    /** {@code SUSPENDED → ACTIVE}. */
    public Identity reinstate(Clock clock) {
        return transitionTo(IdentityStatus.ACTIVE, clock);
    }

    /**
     * {@code → CLOSED}. Terminal.
     *
     * <p>Not deletion: audit records and past sessions reference this identity, and
     * {@code INV-HIST-01} forbids rewriting history that points at it.
     */
    public Identity close(Clock clock) {
        return transitionTo(IdentityStatus.CLOSED, clock);
    }

    private Identity transitionTo(IdentityStatus target, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalIdentityTransitionException(id, status, target);
        }
        return new Identity(id, partyId, loginIdentifier, target, createdAt, Instant.now(clock));
    }

    /** Whether this identity may currently be used to authenticate. */
    public boolean canAuthenticate() {
        return status == IdentityStatus.ACTIVE;
    }

    public IdentityId id() {
        return id;
    }

    /** The Party this login belongs to, by value. Fixed for the life of the Identity. */
    public UUID partyId() {
        return partyId;
    }

    public LoginIdentifier loginIdentifier() {
        return loginIdentifier;
    }

    public IdentityStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Identity identity && id.equals(identity.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /**
     * Excludes the login identifier.
     *
     * <p>It is what someone types to log in, so it is an account-enumeration aid
     * ({@code INV-IDN-07}) and is classified accordingly. The identifiers and the status are enough
     * to diagnose anything, and carry nothing about who the person is.
     */
    @Override
    public String toString() {
        return "Identity[" + id + ", status=" + status + "]";
    }
}
