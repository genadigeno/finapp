package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentNotGrantedException;
import com.finapp.consent.ConsentPurpose;
import com.finapp.consent.ConsentRecord;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The enforcement gate against a real database (`P2-TSK-019`).
 *
 * <h2>The cross-instance test IS the milestone's demonstration</h2>
 *
 * <p>M2.5's stated acceptance — <em>withdrawal demonstrably blocks the dependent capability,
 * across instances</em> — is performed here in the {@code P0-TST-009} convention: two
 * connections standing for two instances, a withdrawal committed on one, and the other's very
 * next gate decision refusing. `P2-TST-002` records the register row and performs the
 * cached-read mutation against exactly this test.
 */
@Tag("database")
@DisplayName("the consent gate (P2-TSK-019)")
class ConsentGateDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final ConsentStore<Connection> store = new JdbcConsentStore();
    private final ConsentGate<Connection> gate = new ConsentGate<>(store);

    /**
     * {@code INV-CNS-01}'s three refusal causes are one answer, and {@code require} agrees
     * with {@code permits} at every step — asserted as a walk through one party's history
     * rather than three fixtures, because the property is that the <em>same</em> gate flips
     * exactly when the history says so.
     */
    @Test
    @DisplayName("absent, withdrawn and stale bases each refuse; a current grant permits")
    void theCausesOfRefusalAreOneAnswer() throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            // Absence is refusal - a party the history has never heard of.
            assertThat(gate.permits(app, party, ConsentPurpose.KYC_PROCESSING)).isFalse();
            assertThatThrownBy(
                            () -> gate.require(app, party, ConsentPurpose.KYC_PROCESSING))
                    .isInstanceOf(ConsentNotGrantedException.class);

            // A current grant permits - and require goes quiet.
            store.append(
                    app,
                    ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
            assertThat(gate.permits(app, party, ConsentPurpose.KYC_PROCESSING)).isTrue();
            assertThatCode(() -> gate.require(app, party, ConsentPurpose.KYC_PROCESSING))
                    .doesNotThrowAnyException();

            // Purpose-scoped: the KYC grant says nothing about screening.
            assertThat(gate.permits(app, party, ConsentPurpose.SCREENING)).isFalse();

            // A withdrawal refuses - and is indistinguishable from the absence above: the
            // same false, the same exception, nothing a caller could tell apart.
            store.append(
                    app,
                    ConsentRecord.withdrawal(
                            IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
            assertThat(gate.permits(app, party, ConsentPurpose.KYC_PROCESSING)).isFalse();
            assertThatThrownBy(
                            () -> gate.require(app, party, ConsentPurpose.KYC_PROCESSING))
                    .isInstanceOf(ConsentNotGrantedException.class);
        }
    }

    @Test
    @DisplayName("a grant lapsed by a re-consent-demanding version refuses at the gate")
    void aStaleBasisRefuses() throws SQLException {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            store.append(
                    app, ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.SCREENING, 1));
            assertThat(gate.permits(app, party, ConsentPurpose.SCREENING)).isTrue();

            // v2 arrives - by migration in production, by the migrator here - and demands
            // re-consent: the gate's very next decision refuses, with no restart, no cache
            // and no sweep anywhere in between (INV-CNS-04 through the gate).
            seedTextVersion(ConsentPurpose.SCREENING, 2, true);
            try {
                assertThat(gate.permits(app, party, ConsentPurpose.SCREENING)).isFalse();
            } finally {
                removeSeededState();
            }
        }
    }

    /**
     * {@code INV-CNS-03}, in the {@code P0-TST-009} convention: two instances, and the
     * withdrawal is effective on the other's <strong>very next decision</strong> — not after a
     * TTL, not after a restart. This is the demonstration M2.5's acceptance names, performed,
     * and {@code P2-TST-002}'s subject.
     *
     * <h2>Each instance gets its own gate, and that is the convention rather than ceremony</h2>
     *
     * <p>{@code SimulatedInstance}'s javadoc states the rule this follows: a test that shares
     * the thing whose sharing hides the defect proves far less than it looks like it proves.
     * Here the defect is a <strong>process-local cache</strong>, one {@code ConsentGate} bean
     * per deployed instance — so two instances must be two gates, or the test could not tell a
     * gate that caches from one that does not on the axis that matters. Sharing one gate
     * happened to catch the mutation anyway (instance A memoises before B withdraws), and
     * "happened to" is what this convention exists to remove.
     *
     * <h2>The boundary asserted is the COMMIT, which is what makes this deterministic</h2>
     *
     * <p>The invariant's own words are <em>"from the transaction that records a withdrawal"</em>,
     * so the test holds B's transaction open and asserts <strong>both sides</strong>: while the
     * withdrawal is uncommitted A still permits — it is not yet a fact, and an instance that
     * refused here would be reading dirty — and on A's very next decision after the commit it
     * refuses. No sleep, no polling, no timing luck: the commit is the event, and the
     * assertion straddles it.
     */
    @Test
    @DisplayName("a withdrawal on one instance refuses the gate on another, immediately")
    void withdrawalOnOneInstanceRefusesOnAnother() throws SQLException {
        UUID party = IDS.next();
        try (SimulatedInstance instanceA = SimulatedInstance.inAgreementWithTheServer();
                SimulatedInstance instanceB = SimulatedInstance.inAgreementWithTheServer()) {
            // Its own connection, its own clock, and its own gate - the component whose
            // process-local state is the whole subject.
            ConsentGate<Connection> gateOnA = new ConsentGate<>(new JdbcConsentStore());
            ConsentGate<Connection> gateOnB = new ConsentGate<>(new JdbcConsentStore());

            store.append(
                    instanceA.connection(),
                    ConsentRecord.grant(IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));
            instanceA.commit();
            assertThat(gateOnA.permits(instanceA.connection(), party, ConsentPurpose.KYC_PROCESSING))
                    .as("instance A holds a basis and has just seen it - which is what gives a"
                            + " cache something stale to serve")
                    .isTrue();

            // Instance B records the withdrawal - the person acting through whichever instance
            // the load balancer picked, which is never guaranteed to be A - and HOLDS the
            // transaction open.
            store.append(
                    instanceB.connection(),
                    ConsentRecord.withdrawal(
                            IDS, CLOCK, party, ConsentPurpose.KYC_PROCESSING, 1));

            assertThat(gateOnA.permits(instanceA.connection(), party, ConsentPurpose.KYC_PROCESSING))
                    .as("an UNCOMMITTED withdrawal is not yet a fact: A still permits, and an"
                            + " instance that refused here would be reading dirty")
                    .isTrue();

            instanceB.commit();

            assertThat(gateOnA.permits(instanceA.connection(), party, ConsentPurpose.KYC_PROCESSING))
                    .as("instance A's very next decision refuses: an eventually-withdrawn"
                            + " consent is an unwithdrawn consent (INV-CNS-03)")
                    .isFalse();
            assertThat(gateOnB.permits(instanceB.connection(), party, ConsentPurpose.KYC_PROCESSING))
                    .as("and so does the instance that recorded it")
                    .isFalse();
        }
    }

    // -----------------------------------------------------------------
    // Fixtures (the ConsentHistoryDatabaseTest idiom)

    private static void seedTextVersion(
            ConsentPurpose purpose, int version, boolean requiresReconsent) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement insert =
                        migrator.prepareStatement(
                                "INSERT INTO consent.consent_text"
                                        + " (purpose, version, body, requires_reconsent,"
                                        + " published_at) VALUES (?, ?, 'test fixture version',"
                                        + " ?, now())")) {
            insert.setString(1, purpose.name());
            insert.setInt(2, version);
            insert.setBoolean(3, requiresReconsent);
            insert.executeUpdate();
        }
    }

    private static void removeSeededState() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            try (PreparedStatement delete =
                    migrator.prepareStatement(
                            "DELETE FROM consent.consent_record WHERE text_version >= 2")) {
                delete.executeUpdate();
            }
            try (PreparedStatement delete =
                    migrator.prepareStatement(
                            "DELETE FROM consent.consent_text WHERE version >= 2"
                                    + " AND body = 'test fixture version'")) {
                delete.executeUpdate();
            }
        }
    }
}
