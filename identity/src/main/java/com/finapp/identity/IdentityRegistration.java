package com.finapp.identity;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Creates the {@link Identity} for a newly registered party (`P1-TSK-006`).
 *
 * <h2>The contended write</h2>
 *
 * <p>This is the one insert in a registration that two instances can genuinely race for:
 * {@code identity_login_identifier_is_unique} is a <strong>total</strong> unique index, so a login
 * identifier is claimed once ever and a closed login never frees its name. Under
 * {@code READ COMMITTED} the second inserter <em>blocks</em> on the index until the first
 * transaction ends and only then learns its fate, which is why a pre-flight {@code SELECT} is not
 * a substitute and must not be added: two instances would both see the identifier free, both
 * insert, and one would get {@code 23505} anyway. A pre-check makes the defect rarer, not absent,
 * which is worse.
 *
 * <p>So the violation is caught and reported as {@link LoginIdentifierAlreadyTakenException}, and
 * the caller - which owns the savepoint - decides what to do about a transaction PostgreSQL has
 * already aborted.
 *
 * <h2>The party reference</h2>
 *
 * <p>A {@code UUID} by value. There is no foreign key to {@code party.party} and no compile-time
 * dependency on that module (ADR-0029), so nothing in the database stops an identity referencing a
 * party that does not exist. What prevents it is that this method is only ever called in the same
 * transaction that created the party - which is a property a test can assert, and does.
 *
 * <h2>Two phases, and the split is the control ({@code P1-TSK-026})</h2>
 *
 * <p>{@link #prepare} does everything expensive and takes <strong>no {@code Connection}</strong>;
 * {@link #create} does everything transactional and takes no password. You cannot call the second
 * without having called the first, so the derivation cannot drift inside the transaction by
 * omission - only by somebody deliberately moving the {@code prepare} call, which is visible in a
 * diff. {@code PasswordDeriver} states why that matters: Argon2id is deliberately expensive, and
 * deriving while holding one of eight pooled connections (`P1-TSK-004`) turns a registration flood
 * into connection-timeout errors that point at the database.
 *
 * <p><strong>An Identity is no longer constructible without a credential.</strong> Before
 * {@code P1-TSK-026} this class created a login that could never authenticate, which was
 * {@code P1-TSK-006}'s recorded remainder rather than a design. The credential-less path is gone
 * rather than deprecated: leaving it would leave the defect reachable, and Phase 1 has no
 * legitimate caller for it.
 */
@RequiredArgsConstructor
public final class IdentityRegistration {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "identity";

    private static final int EVENT_VERSION = 1;

    /** PostgreSQL {@code unique_violation}. */
    private static final String UNIQUE_VIOLATION = "23505";

    /** What an audit record for an identity action points at. */
    public static final String AUDIT_TARGET_TYPE = "identity.LoginIdentifier";

    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;
    @NonNull private final CredentialStore<Connection> credentials;
    @NonNull private final PasswordDeriver deriver;

    /**
     * Mints the identifier and derives the credential. Expensive, and deliberately unable to
     * enlist in a transaction: it takes no {@code Connection} and touches no store.
     *
     * <p>Call it <strong>before</strong> opening the transaction that calls {@link #create}. The
     * signature is the reminder; see the class documentation for what it costs to forget.
     */
    public Enrolment prepare(RawPassword password) {
        Objects.requireNonNull(password, "password must not be null");
        IdentityId identityId = IdentityId.next(ids);
        return new Enrolment(
                identityId,
                Credential.forPassword(
                        ids, clock, identityId, CredentialType.PASSWORD, deriver, password));
    }

    /**
     * An identifier and the credential derived against it, before either exists in the database.
     *
     * <p>A per-call value, never retained: it holds a derivation rather than a plaintext, and
     * {@code Credential} wraps that in {@code Sensitive} so no rendering path can print it.
     */
    public record Enrolment(IdentityId identityId, Credential credential) {

        public Enrolment {
            Objects.requireNonNull(identityId, "identityId must not be null");
            Objects.requireNonNull(credential, "credential must not be null");
        }
    }

    /**
     * Creates the login, on {@code unitOfWork}.
     *
     * @param enrolment from {@link #prepare}, derived before this transaction opened
     * @throws LoginIdentifierAlreadyTakenException the identifier is in use. The transaction is
     *     aborted at this point and the caller must roll back to a savepoint before doing anything
     *     else with the connection
     */
    public Identity create(
            Connection unitOfWork,
            Enrolment enrolment,
            UUID partyId,
            LoginIdentifier loginIdentifier) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(enrolment, "enrolment must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(loginIdentifier, "loginIdentifier must not be null");

        Identity identity =
                Identity.create(enrolment.identityId(), clock, partyId, loginIdentifier);
        insert(unitOfWork, identity);
        // After the identity, because credential.identity_id references it. A taken login
        // identifier aborts the transaction at the line above, so this is never reached with a
        // parent row that will not survive.
        credentials.insert(unitOfWork, enrolment.credential());

        Correlation correlation = currentCorrelation();
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        IdentityAuditAction.IDENTITY_CREATED,
                        AUDIT_TARGET_TYPE,
                        loginIdentifier.value(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "identity="
                                        + identity.id()
                                        + " party="
                                        + partyId
                                        + " credential="
                                        + enrolment.credential().id())));

        // No login identifier in the payload. The event stream reaches systems with different
        // access control, and knowing an identifier is in use tells a reader an account exists
        // (INV-AUD-02, INV-IDN-07). The audit record is where an attempted identifier belongs.
        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        "identity.IdentityCreated",
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        identity.id(),
                        "Identity",
                        identity.createdAt(),
                        PRODUCER,
                        correlation.correlationId(),
                        CausationId.of(correlation.correlationId().value())),
                EventPayload.of()
                        .with("identityId", identity.id().value().toString())
                        .with("partyId", partyId.toString())
                        .with("status", identity.status().name())
                        .with("credentialId", enrolment.credential().id().value().toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);

        return identity;
    }

    // -----------------------------------------------------------------

    private static void insert(Connection unitOfWork, Identity identity) {
        String sql =
                "INSERT INTO identity.identity"
                        + " (id, party_id, login_identifier, status, created_at, status_changed_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, identity.id().value());
            insert.setObject(2, identity.partyId());
            insert.setString(3, identity.loginIdentifier().value());
            insert.setString(4, identity.status().name());
            insert.setTimestamp(5, Timestamp.from(identity.createdAt()));
            insert.setTimestamp(6, Timestamp.from(identity.statusChangedAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                throw new LoginIdentifierAlreadyTakenException(e);
            }
            // Neither message names the identifier: it is CONFIDENTIAL and an exception message
            // reaches a log line (INV-AUD-02).
            throw new IdentityStorageException(
                    DatabaseFailure.describe("Could not insert identity " + identity.id(), e));
        }
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Identity creation must run inside a correlation scope: the"
                                            + " audit record and the event it writes carry the"
                                            + " identifier, and a fabricated one would point at no"
                                            + " flow at all (P0-TSK-014)"));
    }
}
