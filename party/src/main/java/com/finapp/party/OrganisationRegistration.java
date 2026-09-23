package com.finapp.party;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Registers an ORGANISATION Party and its Customer, recording the acting person as its one
 * registrant (`P2-TSK-016`).
 *
 * <p><strong>Who may act for an organisation — Phase 2's deliberate minimum.</strong> An
 * organisation holds no Identity and cannot log in, so "the acting person" is a recorded fact
 * rather than something a session proves. The fact recorded here is the backlog's own scope:
 * <em>the registering identity</em> — the authenticated person whose Party lands in
 * {@code party.organisation_registrant}. Delegated access, multiple representatives and
 * registrant replacement are Phase 6+'s, which is why the table is append-only and its unique
 * index total.
 *
 * <p><strong>Registration converges rather than duplicating.</strong> "Register my organisation"
 * twice — a double-tap, a lost-response retry, ten instances racing — is one intent, and the
 * total {@code UNIQUE (registrant_party_id)} index is the arbiter: the insert happens behind a
 * savepoint (the {@code openOrConverge} idiom, because the unique violation aborts the
 * transaction), and a loser converges onto the winner's organisation <em>when the name
 * matches</em>. A different name is a {@link Result#NAME_CONFLICT} rather than a silent
 * convergence — returning the first organisation to a request that described a different one is
 * the {@code INV-IDEM-03} defect wearing convergence's clothes.
 *
 * <p><strong>Created announces; converged is silent</strong> (the `P2-TSK-007` rule): only the
 * creating path writes {@code party.OrganisationRegistered} and publishes
 * {@code party.PartyRegistered} / {@code party.CustomerOpened} — the same events person
 * registration publishes, because a customer opened is a customer opened, and the case-opening
 * consumer's kind resolution already answers {@code KYB} for an organisation (`P2-TSK-015`).
 *
 * <p><strong>The actor is the person, never the platform.</strong> Unlike
 * {@link PartyRegistration}, whose caller is unauthenticated, this caller holds a proven
 * session — so the audit record names them via {@link SecurityContext#require()}, and
 * {@code enterSystem()} has no business here.
 */
@RequiredArgsConstructor
public final class OrganisationRegistration {

    private static final String UNIQUE_VIOLATION = "23505";

    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final OutboxWriter<Connection> outboxWriter;

    /**
     * Registers the organisation {@code name} with {@code registrant} as its acting person, or
     * converges on the one they already registered.
     *
     * <p>On the caller's connection, so the organisation, its customer, the registrant record,
     * the audit record, the events — and whatever the caller writes beside them (the KYB case) —
     * commit together or not at all.
     */
    public Result registerOrConverge(
            Connection unitOfWork, PartyId registrant, PartyName name) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(registrant, "registrant must not be null");
        Objects.requireNonNull(name, "name must not be null");

        // The pre-read is the business rule made visible (one organisation per registrant),
        // never a substitute for the index (P1-TSK-006's distinction): two instances can both
        // read nothing here and both insert, and the index decides.
        Existing existing = findExisting(unitOfWork, registrant);
        if (existing != null) {
            return converge(existing, name);
        }

        Party party = Party.register(ids, clock, PartyKind.ORGANISATION, name);
        Customer customer = Customer.open(ids, clock, party.id());
        try {
            Savepoint beforeInsert = unitOfWork.setSavepoint("organisation_registration");
            try {
                PartyRegistration.insertParty(unitOfWork, party);
                PartyRegistration.insertCustomer(unitOfWork, customer);
                insertRegistrant(unitOfWork, customer, registrant);
                unitOfWork.releaseSavepoint(beforeInsert);
            } catch (SQLException insertFailed) {
                if (!UNIQUE_VIOLATION.equals(insertFailed.getSQLState())) {
                    throw insertFailed;
                }
                unitOfWork.rollback(beforeInsert);
                // READ COMMITTED takes a new snapshot per statement, so this read sees the
                // committed winner whose registrant row just refused ours.
                Existing winner = findExisting(unitOfWork, registrant);
                if (winner == null) {
                    throw new PartyStorageException(
                            "the one-organisation-per-registrant index refused an insert but no"
                                    + " registration is visible - a concurrent registrant may"
                                    + " have rolled back; retry");
                }
                return converge(winner, name);
            }
        } catch (SQLException failure) {
            // Names identifiers only: the organisation name is RESTRICTED-PII column content
            // and an exception message reaches a log line (INV-AUD-02).
            throw new PartyStorageException(
                    DatabaseFailure.describe(
                            "registering an organisation for party " + registrant, failure));
        }

        Correlation correlation = currentCorrelation();
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        // The proven person - this caller is authenticated, so recording the
                        // platform would erase exactly the attribution the trail exists for.
                        SecurityContext.require(),
                        Instant.now(clock),
                        PartyAuditAction.ORGANISATION_REGISTERED,
                        "Customer",
                        customer.id().value().toString(),
                        java.util.Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only (INV-AUD-02): the name is RESTRICTED-PII, and the
                        // registrant linkage is the record's point.
                        java.util.Optional.of(
                                "party=" + party.id()
                                        + " customer=" + customer.id()
                                        + " registrant=" + registrant)));

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

        return new Result.Registered(party.id(), customer.id());
    }

    // -----------------------------------------------------------------

    private static Result converge(Existing existing, PartyName requested) {
        return existing.name().equals(requested)
                ? new Result.Converged(existing.partyId(), existing.customerId())
                // The INV-IDEM-03 shape: same intent replays, a different request conflicts.
                // Nothing is disclosed the caller does not already hold - the conflict is about
                // THEIR organisation, and the stored name is not echoed.
                : Result.NAME_CONFLICT;
    }

    /** The registrant's existing organisation, if any - the statement carries the owner. */
    private static Existing findExisting(Connection unitOfWork, PartyId registrant) {
        String sql =
                "SELECT p.id AS party_id, p.display_name, r.customer_id"
                        + " FROM party.organisation_registrant r"
                        + " JOIN party.customer c ON c.id = r.customer_id"
                        + " JOIN party.party p ON p.id = c.party_id"
                        + " WHERE r.registrant_party_id = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, registrant.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? new Existing(
                                PartyId.of((UUID) rows.getObject("party_id")),
                                CustomerId.of((UUID) rows.getObject("customer_id")),
                                new PartyName(rows.getString("display_name")))
                        : null;
            }
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe(
                            "reading the organisation registered by party " + registrant, e));
        }
    }

    private void insertRegistrant(Connection unitOfWork, Customer customer, PartyId registrant)
            throws SQLException {
        String sql =
                "INSERT INTO party.organisation_registrant"
                        + " (customer_id, registrant_party_id, registered_at) VALUES (?, ?, ?)";
        try (PreparedStatement insert = unitOfWork.prepareStatement(sql)) {
            insert.setObject(1, customer.id().value());
            insert.setObject(2, registrant.value());
            insert.setTimestamp(3, Timestamp.from(Instant.now(clock)));
            insert.executeUpdate();
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
                        PartyRegistration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        aggregateId,
                        aggregateType,
                        occurredAt,
                        PartyRegistration.PRODUCER,
                        correlation.correlationId(),
                        CausationId.of(correlation.correlationId().value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static Correlation currentCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "Organisation registration must run inside a correlation"
                                                + " scope: the audit record and every event it"
                                                + " writes carry the identifier (P0-TSK-014)"));
    }

    private record Existing(PartyId partyId, CustomerId customerId, PartyName name) {}

    /** What the registration came to. The orchestration maps these. */
    public sealed interface Result {

        /** Created: the caller must open the KYB case in this same transaction. */
        record Registered(PartyId partyId, CustomerId customerId) implements Result {}

        /** The registrant already has this organisation, by the same name - one intent. */
        record Converged(PartyId partyId, CustomerId customerId) implements Result {}

        /** The registrant already has an organisation under a DIFFERENT name. */
        Result NAME_CONFLICT = new NameConflict();

        record NameConflict() implements Result {}
    }
}
