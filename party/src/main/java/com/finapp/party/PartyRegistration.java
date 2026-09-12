package com.finapp.party;

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
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Registers a {@link Party} and opens its {@link Customer} relationship (`P1-TSK-006`).
 *
 * <h2>The transaction is the caller's</h2>
 *
 * <p>Every write here - two rows, one audit record, two outbox rows - happens on the
 * {@link Connection} it is handed. Nothing is committed, nothing is rolled back, and no connection
 * is opened. That is what lets {@code app} put this module's writes and {@code identity}'s in one
 * transaction, which is the property ADR-0001 exists to preserve and the only thing standing in for
 * the foreign key ADR-0029 deliberately does not have.
 *
 * <p>A service that opened its own transaction would look identical in every test and would
 * silently make a partial registration possible - the same argument {@code OutboxWriter} and
 * {@code AuditWriter} make for taking a unit of work rather than creating one.
 *
 * <h2>Explicit SQL, on the caller's connection</h2>
 *
 * <p>ADR-0033 decided explicit SQL and no ORM. It named {@code JdbcClient}, which is built on a
 * {@code DataSource} and would therefore acquire its <em>own</em> connection - a second
 * transaction, with the atomicity gone while every test still passed. Where the unit of work is
 * handed in, the idiom is a {@code PreparedStatement} on that connection, which is what all four
 * Phase 0 writers do for exactly this reason.
 *
 * <h2>Two events, one audit record</h2>
 *
 * <p>{@code PartyRegistered} and {@code CustomerOpened} are separate events because they concern
 * separate aggregates and a consumer may care about one and not the other. There is one audit
 * record because there was one decision; see {@link PartyAuditAction#CUSTOMER_REGISTERED}.
 *
 * <p>The two events are about different aggregates, so {@code OutboxRelay} orders neither against
 * the other: a consumer may see {@code CustomerOpened} first and must tolerate it
 * ({@code INV-EVT-04}).
 */
public final class PartyRegistration {

    /** Kept in one place because it is a published value: consumers route on it. */
    static final String PRODUCER = "party";

    // Package-private: OrganisationRegistration publishes the same two event types at the
    // same version - one definition, so the wire cannot fork by module-internal drift.
    static final int EVENT_VERSION = 1;

    private final IdGenerator ids;
    private final Clock clock;
    private final AuditWriter<Connection> auditWriter;
    private final OutboxWriter<Connection> outboxWriter;

    public PartyRegistration(
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.outboxWriter = Objects.requireNonNull(outboxWriter, "outboxWriter must not be null");
    }

    /**
     * Creates the Party and its relationship, on {@code unitOfWork}.
     *
     * @param subject what the resulting records are <em>about</em>, for the audit trail. Passed in
     *     rather than derived here: the registration's subject is the login being claimed, this
     *     module does not know what a login identifier is, and recording the Party's own identifier
     *     instead would make the record unfindable from the only value anyone would search by if
     *     the registration were later disputed
     */
    public RegisteredParty register(Connection unitOfWork, PartyName name, AuditSubject subject) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        Party party = Party.register(ids, clock, PartyKind.PERSON, name);
        Customer customer = Customer.open(ids, clock, party.id());

        insertParty(unitOfWork, party);
        insertCustomer(unitOfWork, customer);

        Correlation correlation = currentCorrelation();
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PartyAuditAction.CUSTOMER_REGISTERED,
                        subject.targetType(),
                        subject.targetId(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only. The name is RESTRICTED-PII and an audit record is read
                        // by more people than the table it describes (INV-AUD-02).
                        Optional.of("party=" + party.id() + " customer=" + customer.id())));

        publish(
                unitOfWork,
                correlation,
                "party.PartyRegistered",
                "Party",
                party.id(),
                EventPayload.of()
                        .with("partyId", party.id().value().toString())
                        .with("kind", party.kind().name()),
                party.registeredAt());
        publish(
                unitOfWork,
                correlation,
                "party.CustomerOpened",
                "Customer",
                customer.id(),
                EventPayload.of()
                        .with("customerId", customer.id().value().toString())
                        .with("partyId", party.id().value().toString())
                        .with("status", customer.status().name()),
                customer.openedAt());

        return new RegisteredParty(party, customer);
    }

    /**
     * Records that a registration was attempted and refused, on {@code unitOfWork}.
     *
     * <p>Separate from {@link #register} because it is called <em>after</em> the effect has been
     * rolled back to a savepoint: the audit record must survive, and everything it would have
     * described must not. Writing it inside {@code register} would put it on the wrong side of that
     * rollback, which is a mistake nothing else would notice - the refusal would simply not be in
     * the trail.
     *
     * <p><strong>No event.</strong> Nothing happened, so there is no fact to publish, and an event
     * announcing a refusal would carry an attempted login identifier into a stream
     * {@code INV-IDN-07} keeps it out of.
     */
    public void recordRefusedRegistration(Connection unitOfWork, AuditSubject subject) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(subject, "subject must not be null");

        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PartyAuditAction.CUSTOMER_REGISTERED,
                        subject.targetType(),
                        subject.targetId(),
                        Optional.empty(),
                        // FAILED, not DENIED: DENIED is an authorization outcome, and this endpoint
                        // has no authorization to deny.
                        AuditOutcome.FAILED,
                        currentCorrelation().correlationId(),
                        Optional.empty()));
    }

    // -----------------------------------------------------------------

    // Package-private: OrganisationRegistration writes the same rows with the same SQL
    // (P2-TSK-016) - a second copy of an INSERT is a second place for a column to drift.
    static void insertParty(Connection unitOfWork, Party party) {
        String sql =
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, party.id().value());
            insert.setString(2, party.kind().name());
            insert.setString(3, party.name().value());
            insert.setTimestamp(4, Timestamp.from(party.registeredAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            // The message names no column value: this row holds a person's name (INV-AUD-02).
            throw new PartyStorageException(
                    DatabaseFailure.describe("Could not insert party " + party.id(), e));
        }
    }

    static void insertCustomer(Connection unitOfWork, Customer customer) {
        String sql =
                "INSERT INTO party.customer (id, party_id, status, opened_at, status_changed_at)"
                        + " VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, customer.id().value());
            insert.setObject(2, customer.partyId().value());
            insert.setString(3, customer.status().name());
            insert.setTimestamp(4, Timestamp.from(customer.openedAt()));
            insert.setTimestamp(5, Timestamp.from(customer.statusChangedAt()));
            insert.executeUpdate();
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe("Could not insert customer " + customer.id(), e));
        }
    }

    private void publish(
            Connection unitOfWork,
            Correlation correlation,
            String eventType,
            String aggregateType,
            EntityId aggregateId,
            EventPayload payload,
            Instant occurredAt) {

        outboxWriter.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        aggregateId,
                        aggregateType,
                        occurredAt,
                        PRODUCER,
                        correlation.correlationId(),
                        causedByTheRequest(correlation)),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * The cause of an event at the root of a flow.
     *
     * <p>{@code Correlation} leaves causation null at a flow's root - deliberately, so a root is
     * distinguishable from a cycle - while {@code EventEnvelope} requires it. An HTTP-initiated
     * event therefore has no message that caused it, and the honest answer is that <strong>the
     * request caused it</strong>.
     *
     * <p>The value looks self-referential and is not: the correlation identifier names a real,
     * recorded thing - it is on the idempotency record and on the audit record written in this same
     * transaction - so the causal chain terminates at the request rather than at nothing. Minting a
     * fresh identifier here would be worse, because it would point at something that exists
     * nowhere.
     */
    private static CausationId causedByTheRequest(Correlation correlation) {
        return CausationId.of(correlation.correlationId().value());
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Registration must run inside a correlation scope: the audit"
                                            + " record and every event it writes carry the"
                                            + " identifier, and a fabricated one would point at no"
                                            + " flow at all (P0-TSK-014)"));
    }

    /** What was created. */
    public record RegisteredParty(Party party, Customer customer) {
        public RegisteredParty {
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(customer, "customer must not be null");
        }
    }

    /**
     * What an audit record for a registration is <em>about</em>.
     *
     * <p>A type rather than two strings, so a caller cannot transpose them and so the one place
     * that decides what a registration's subject is can be found by looking for its uses.
     */
    public record AuditSubject(String targetType, String targetId) {
        public AuditSubject {
            Objects.requireNonNull(targetType, "targetType must not be null");
            Objects.requireNonNull(targetId, "targetId must not be null");
        }
    }
}
