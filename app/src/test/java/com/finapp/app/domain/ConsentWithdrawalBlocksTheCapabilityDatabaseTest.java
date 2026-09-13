package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.kyc.CaseKindResolver;
import com.finapp.kyc.CaseOpeningConsent;
import com.finapp.kyc.CustomerOpenedOpensCase;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCaseKind;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.CustomerId;
import com.finapp.party.JdbcPartyStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * M2.5's stated acceptance, demonstrated at the <strong>capability</strong> (`P2-TST-002`):
 * <em>withdrawal demonstrably blocks the dependent capability, across instances</em>.
 *
 * <h2>Why the gate's own cross-instance test is not this</h2>
 *
 * <p>{@code ConsentGateDatabaseTest#withdrawalOnOneInstanceRefusesOnAnother} proves the
 * <strong>gate's answer</strong> flips across instances, and the consumer's tests prove the
 * capability follows the gate. Those two together are a <em>composition argument</em>, and this
 * repository has repeatedly found that a property assembled from two green tests is not the
 * same as a property demonstrated — {@code P1-TSK-027}'s session gap is the sharpest case: both
 * halves worked, nothing joined them, and every suite passed. The milestone bullet names the
 * <strong>capability</strong>, so the capability is what gets driven.
 *
 * <p>So this test runs the real production door — {@link CustomerOpenedOpensCase}, with the real
 * case store, the real party store, the real gate and the real audit and outbox writers, wired
 * as {@code KycBeans} wires them — and asserts that after a withdrawal committed on another
 * instance it opens <strong>nothing</strong>.
 */
@Tag("database")
@DisplayName("withdrawal blocks the dependent capability, across instances (P2-TST-002)")
@SuppressWarnings("try") // the correlation Scope is used for its close side effect
class ConsentWithdrawalBlocksTheCapabilityDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final ConsentStore<Connection> consents = new JdbcConsentStore();
    private final PartyStore<Connection> parties = new JdbcPartyStore();
    private final KycCaseStore<Connection> cases = new JdbcKycCaseStore();

    @Test
    @DisplayName("a withdrawal on one instance stops the other instance opening a case")
    void aWithdrawalOnOneInstanceBlocksCaseOpeningOnAnother() throws SQLException {
        try (SimulatedInstance instanceA = SimulatedInstance.inAgreementWithTheServer();
                SimulatedInstance instanceB = SimulatedInstance.inAgreementWithTheServer()) {

            // Instance B runs the capability, through its own gate - the component whose
            // process-local state would be what goes stale.
            ConsentGate<Connection> gateOnB = new ConsentGate<>(new JdbcConsentStore());
            CustomerOpenedOpensCase capabilityOnB = capabilityOver(gateOnB);

            // --- Positive control: with a basis, the capability proceeds. -------------------
            // Without this the test could pass against a capability that never opens anything.
            Person consented = givenAConsentedPerson();
            open(capabilityOnB, instanceB, consented.customer());
            assertThat(caseCountFor(consented.customer()))
                    .as("the capability proceeds on a current basis - so its refusal below is"
                            + " the gate's doing and not this test's")
                    .isEqualTo(1);

            // --- The demonstration. --------------------------------------------------------
            Person withdrawing = givenAConsentedPerson();

            // Instance B has ALREADY seen this party's basis: a cache now holds `true`, which
            // is what gives the withdrawal something to be immediate against.
            assertThat(gateOnB.permits(
                            instanceB.connection(),
                            withdrawing.party(),
                            ConsentPurpose.KYC_PROCESSING))
                    .isTrue();

            // Instance A records the withdrawal and commits - a different session, and in a
            // deployment a different machine.
            consents.append(
                    instanceA.connection(),
                    ConsentRecord.withdrawal(
                            IDS, CLOCK, withdrawing.party(), ConsentPurpose.KYC_PROCESSING, 1));
            instanceA.commit();

            // Instance B's very next run of the capability opens nothing.
            open(capabilityOnB, instanceB, withdrawing.customer());

            assertThat(caseCountFor(withdrawing.customer()))
                    .as("no case: the capability is blocked from the transaction that recorded"
                            + " the withdrawal, on an instance that never saw it happen"
                            + " (INV-CNS-01, INV-CNS-03)")
                    .isZero();
            assertThat(auditCountFor(withdrawing.customer()))
                    .as("and nothing happened, so nothing is recorded")
                    .isZero();
            assertThat(announcementCountFor(withdrawing.customer()))
                    .as("and nothing is announced - no consumer may hear of a case that was"
                            + " never lawfully opened")
                    .isZero();
        }
    }

    // -----------------------------------------------------------------

    /** The consumer, wired exactly as {@code KycBeans} wires it. */
    private CustomerOpenedOpensCase capabilityOver(ConsentGate<Connection> gate) {
        CaseKindResolver<Connection> kinds =
                (unitOfWork, customerId) ->
                        parties.kindOfCustomer(unitOfWork, CustomerId.of(customerId))
                                        .orElseThrow()
                                        .name()
                                        .equals("ORGANISATION")
                                ? KycCaseKind.KYB
                                : KycCaseKind.KYC;
        CaseOpeningConsent<Connection> consent =
                (unitOfWork, customerId) ->
                        gate.permits(
                                unitOfWork,
                                parties.partyOfCustomer(unitOfWork, CustomerId.of(customerId))
                                        .orElseThrow()
                                        .value(),
                                ConsentPurpose.KYC_PROCESSING);
        return new CustomerOpenedOpensCase(
                cases, kinds, consent, IDS, CLOCK, new JdbcAuditWriter(), new JdbcOutboxWriter());
    }

    private void open(
            CustomerOpenedOpensCase capability, SimulatedInstance instance, UUID customerId)
            throws SQLException {
        EventId eventId = EventId.next(IDS);
        try (CorrelationContext.Scope scope =
                CorrelationContext.enter(
                        new Correlation(
                                CorrelationId.of(UUID.randomUUID().toString()),
                                CausationId.of(eventId.value().toString())))) {
            capability.handle(instance.connection(), customerOpened(eventId, customerId));
        }
        instance.commit();
    }

    private static ReceivedEvent customerOpened(EventId eventId, UUID customerId) {
        return new ReceivedEvent(
                eventId,
                "party.CustomerOpened",
                1,
                1,
                customerId,
                "Customer",
                Instant.now(),
                "party",
                CorrelationId.of(UUID.randomUUID().toString()),
                CausationId.of("p2-tst-002"),
                "application/json",
                "{\"customerId\":\"ignored-by-design\"}".getBytes(StandardCharsets.UTF_8));
    }

    private record Person(UUID party, UUID customer) {}

    /** A person, their customer relationship, and a committed current KYC_PROCESSING grant. */
    private Person givenAConsentedPerson() throws SQLException {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Consent Capability', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'PENDING',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
            consents.append(
                    app, ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
        }
        return new Person(party, customer);
    }

    private static long caseCountFor(UUID customerId) throws SQLException {
        return countOf("SELECT count(*) FROM kyc.kyc_case WHERE customer_id = ?", customerId);
    }

    /** {@code audit_record.target_id} is text, so the customer is matched as its string form. */
    private static long auditCountFor(UUID customerId) throws SQLException {
        return countOf(
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'kyc.CaseOpened' AND target_id = ?",
                customerId.toString());
    }

    /**
     * Announcements are keyed by the CASE, so the customer is reached through it — which also
     * means a case that was never opened can have no announcement to find, and the assertion
     * is honest only beside {@link #caseCountFor}. Both are asserted.
     */
    private static long announcementCountFor(UUID customerId) throws SQLException {
        return countOf(
                "SELECT count(*) FROM platform.outbox_event e"
                        + " WHERE e.event_type = 'kyc.KycCaseOpened'"
                        + " AND EXISTS (SELECT 1 FROM kyc.kyc_case c"
                        + "             WHERE c.id = e.aggregate_id AND c.customer_id = ?)",
                customerId);
    }

    private static long countOf(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(sql)) {
            count.setObject(1, argument);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
