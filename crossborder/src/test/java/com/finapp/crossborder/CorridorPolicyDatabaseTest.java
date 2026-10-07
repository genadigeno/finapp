package com.finapp.crossborder;

import static com.finapp.crossborder.CorridorPolicyFixtures.activated;
import static com.finapp.crossborder.CorridorPolicyFixtures.administration;
import static com.finapp.crossborder.CorridorPolicyFixtures.application;
import static com.finapp.crossborder.CorridorPolicyFixtures.controller;
import static com.finapp.crossborder.CorridorPolicyFixtures.IDS;
import static com.finapp.crossborder.CorridorPolicyFixtures.correlation;
import static com.finapp.crossborder.CorridorPolicyFixtures.eurUsdUs;
import static com.finapp.crossborder.CorridorPolicyFixtures.proposal;
import static com.finapp.crossborder.CorridorPolicyFixtures.scalar;
import static com.finapp.crossborder.CorridorPolicyFixtures.terms;
import static com.finapp.crossborder.CorridorPolicyFixtures.withdrawPending;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The corridor policy against a live PostgreSQL (`P9-TSK-015`, ADR-0080 section 4; {@code INV-AUD-04},
 * {@code INV-HIST-04}, {@code INV-MON-03}) - the pricing policy's suite on crossborder's tables: no
 * version is {@code ACTIVE} without two named persons, each rank of that rule proven alone, every
 * invalid edge refused for every writer, the content frozen, a retirement only beside its successor,
 * the build judged at proposal and at approval, and ten racers converging (counted).
 */
@Tag("database")
@DisplayName("the corridor policy: four eyes, frozen, versioned, raced (P9-TSK-015)")
class CorridorPolicyDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String RAISED = "P0001";

    @Test
    @DisplayName("a version is proposed by one controller, refused to its own approval, activated by"
            + " a second - and the next activation retires it, all recorded")
    void theLifecycleIsFourEyes() throws SQLException {
        withdrawPending();
        Actor first = controller();
        Actor second = controller();
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("a new price"), first, Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> administration().approve(app, proposed.id(), first, "mine", Instant.now(), correlation()))
                    .isInstanceOf(CorridorPolicyAdministration.SelfApprovalRefused.class);
            app.rollback();
            CorridorPolicyAdministration.Decided activated =
                    administration().approve(app, proposed.id(), second, "reviewed", Instant.now(), correlation());
            app.commit();
            assertThat(activated.status()).isEqualTo(CorridorPolicyStatus.ACTIVE);
            // The same person's retry converges, writing nothing.
            assertThat(administration().approve(app, proposed.id(), second, "again", Instant.now(), correlation()).replayed())
                    .isTrue();
            app.commit();

            CorridorPolicyAdministration.Proposed next =
                    administration().propose(app, proposal("the next price"), second, Instant.now(), correlation());
            CorridorPolicyAdministration.Decided successor =
                    administration().approve(app, next.id(), first, "reviewed too", Instant.now(), correlation());
            app.commit();
            assertThat(successor.retired()).contains(proposed.id());
            assertThat(audits(app, "crossborder.CorridorPolicyProposed", proposed.id().value().toString())).as("proposed, audited once").isEqualTo("1");
            assertThat(audits(app, "crossborder.CorridorPolicyActivated", proposed.id().value().toString()))
                    .as("activated, audited once - the retry wrote nothing").isEqualTo("1");
            assertThat(status(app, proposed.id())).isEqualTo("RETIRED");
            assertThat(scalar(app, "SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM crossborder.corridor_policy_event WHERE policy_id = '"
                            + proposed.id().value() + "'"))
                    .as("proposed, activated, retired")
                    .isEqualTo("3");
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                            + " 'crossborder.CorridorPolicyActivated' AND aggregate_id = '" + next.id().value() + "'"))
                    .isEqualTo("1");
            app.commit();
        }
    }

    @Test
    @DisplayName("the four-eyes CHECK alone: activating a version by its own proposer through raw SQL"
            + " is refused, with no seed exemption")
    void theFourEyesCheckHoldsAlone() throws SQLException {
        withdrawPending();
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("for the CHECK"), controller(), Instant.now(), correlation());
            app.commit();
            // Any ACTIVE version is retired first, so only the four-eyes rule can refuse.
            execute(app, "UPDATE crossborder.corridor_policy_version SET status = 'RETIRED', retired_at = now()"
                    + " WHERE status = 'ACTIVE'");
            assertThatThrownBy(() -> execute(app, "UPDATE crossborder.corridor_policy_version SET status = 'ACTIVE',"
                            + " decided_by = proposed_by, decided_at = now(), decision_reason = 'myself'"
                            + " WHERE id = '" + proposed.id().value() + "'"))
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("every invalid edge, a frozen identity and a deletion are refused by the trigger, for"
            + " every writer")
    void theMachineHoldsForEveryWriter() throws SQLException {
        CorridorPolicyId active = activated();
        withdrawPending();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (String sql :
                    List.of(
                            "UPDATE crossborder.corridor_policy_version SET status = 'PROPOSED' WHERE id = '%s'",
                            "UPDATE crossborder.corridor_policy_version SET status = 'REJECTED' WHERE id = '%s'",
                            "UPDATE crossborder.corridor_policy_version SET proposed_by = 'someone' WHERE id = '%s'",
                            "DELETE FROM crossborder.corridor_policy_version WHERE id = '%s'")) {
                assertThatThrownBy(() -> execute(migrator, sql.formatted(active.value())))
                        .as(sql)
                        .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
                migrator.rollback();
            }
            assertThatThrownBy(() -> execute(migrator, "UPDATE crossborder.corridor SET fee_fixed_minor = 1"
                            + " WHERE policy_id = '" + active.value() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            migrator.rollback();
            assertThatThrownBy(() -> execute(migrator, "DELETE FROM crossborder.corridor"
                            + " WHERE policy_id = '" + active.value() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            migrator.rollback();
            assertThatThrownBy(() -> execute(migrator, "INSERT INTO crossborder.corridor SELECT policy_id, 'GBP',"
                            + " destination_currency, destination_country, rails, fee_fixed_minor, fee_margin, fee_rounding,"
                            + " maximum_minor, screening_validity_hours, delivery_estimate_hours, required_data"
                            + " FROM crossborder.corridor WHERE policy_id = '" + active.value() + "'"))
                    .as("a corridor added to a decided version")
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            migrator.rollback();
        }
        // A REJECTED version is terminal.
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("to reject"), controller(), Instant.now(), correlation());
            administration().reject(app, proposed.id(), controller(), "no", Instant.now(), correlation());
            app.commit();
            assertThat(audits(app, "crossborder.CorridorPolicyRejected", proposed.id().value().toString())).as("rejected, audited once").isEqualTo("1");
            assertThatThrownBy(() -> execute(app, "UPDATE crossborder.corridor_policy_version SET status = 'ACTIVE'"
                            + " WHERE id = '" + proposed.id().value() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> administration().approve(app, proposed.id(), controller(), "late", Instant.now(), correlation()))
                    .isInstanceOf(CorridorPolicyAdministration.ProposalNotPending.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("a retirement without a later ACTIVE successor is refused at commit")
    void aRetirementNeedsItsSuccessor() throws SQLException {
        activated();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            execute(migrator, "UPDATE crossborder.corridor_policy_version SET status = 'RETIRED', retired_at = now()"
                    + " WHERE status = 'ACTIVE'");
            assertThatThrownBy(migrator::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("successor");
        }
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'ACTIVE'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    @Test
    @DisplayName("the corridor's money rules for every writer: S = D, a negative fee, a margin of one, an"
            + " unnamed rounding, a zero maximum and an unknown required datum are unstorable; a second ACTIVE"
            + " version too")
    void theMoneyRulesHoldForEveryWriter() throws SQLException {
        CorridorPolicyId active = activated();
        withdrawPending();
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("for the corridor CHECKs"), controller(), Instant.now(), correlation());
            app.commit();
            String clone = "INSERT INTO crossborder.corridor SELECT policy_id, %s, rails, %s, %s, maximum_minor,"
                    + " screening_validity_hours, delivery_estimate_hours, %s FROM crossborder.corridor WHERE policy_id = '"
                    + proposed.id().value() + "'";
            record Plant(String what, String keys, String fee, String rounding, String required) {}
            for (Plant plant : List.of(
                    new Plant("S = D", "'USD', 'USD', 'JP'", "fee_fixed_minor, fee_margin", "fee_rounding", "required_data"),
                    new Plant("a negative fee", "'GBP', destination_currency, destination_country", "-1, fee_margin",
                            "fee_rounding", "required_data"),
                    new Plant("a margin of one", "'GBP', destination_currency, destination_country", "fee_fixed_minor, 1",
                            "fee_rounding", "required_data"),
                    new Plant("an unnamed rounding", "'GBP', destination_currency, destination_country",
                            "fee_fixed_minor, fee_margin", "'BANKERS'", "required_data"),
                    new Plant("an unknown required datum", "'GBP', destination_currency, destination_country",
                            "fee_fixed_minor, fee_margin", "fee_rounding", "ARRAY['SHOE_SIZE']::text[]"))) {
                assertThatThrownBy(() -> execute(app, clone.formatted(plant.keys(), plant.fee(), plant.rounding(), plant.required())))
                        .as(plant.what())
                        .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
                app.rollback();
            }
            assertThatThrownBy(() -> execute(app, "INSERT INTO crossborder.corridor SELECT policy_id, 'GBP',"
                            + " destination_currency, destination_country, rails, fee_fixed_minor, fee_margin, fee_rounding,"
                            + " 0, screening_validity_hours, delivery_estimate_hours, required_data FROM crossborder.corridor"
                            + " WHERE policy_id = '" + proposed.id().value() + "'"))
                    .as("a zero maximum")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "UPDATE crossborder.corridor_policy_version SET status = 'ACTIVE',"
                            + " decided_by = 'another-person', decided_at = now(), decision_reason = 'x'"
                            + " WHERE id = '" + proposed.id().value() + "'"))
                    .as("a second ACTIVE version beside " + active.value())
                    .matches(e -> UNIQUE_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("the rounding and required-data CHECK lists are exactly their enums' names (INV-MON-03)")
    void theCheckListsAreTheEnums() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThat(namesIn(migrator, "corridor_fee_rounding_is_named"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(RoundingPolicy.values()).map(Enum::name).toList());
            assertThat(namesIn(migrator, "corridor_required_data_is_known"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(RequiredData.values()).map(Enum::name).toList());
            assertThat(namesIn(migrator, "corridor_policy_status_is_known"))
                    .containsExactlyInAnyOrderElementsOf(Arrays.stream(CorridorPolicyStatus.values()).map(Enum::name).toList());
        }
    }

    @Test
    @DisplayName("the build is judged at proposal AND at approval: a rail the build no longer declares, or one not"
            + " covering the corridor's destination, refuses the activation; data the platform does not hold"
            + " refuses the proposal")
    void theBuildIsJudgedTwice() throws SQLException {
        withdrawPending();
        Actor proposer = controller();
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("judged twice"), proposer, Instant.now(), correlation());
            app.commit();
            CorridorDirectory nothingDeclared = Set::of;
            assertThatThrownBy(() -> administration(nothingDeclared)
                            .approve(app, proposed.id(), controller(), "the build moved", Instant.now(), correlation()))
                    .isInstanceOf(CorridorPolicyAdministration.RailNotDeclared.class);
            app.rollback();
            assertThat(scalar(app, "SELECT status FROM crossborder.corridor_policy_version WHERE id = '"
                            + proposed.id().value() + "'"))
                    .isEqualTo("PROPOSED");
            administration().reject(app, proposed.id(), proposer, "withdrawn", Instant.now(), correlation());
            app.commit();
        }
        try (Connection app = application()) {
            assertThatThrownBy(() -> administration().propose(app,
                            new CorridorPolicyProposal(List.of(terms("EUR", "USD", "JP", "2.50", "10000.00")), "not covered"),
                            controller(), Instant.now(), correlation()))
                    .as("corridor-sim-a delivers JPY in JP, never USD")
                    .isInstanceOf(CorridorPolicyAdministration.RailNotDeclared.class);
            app.rollback();
            CorridorTerms needsAnAddress = eurUsdUs();
            CorridorTerms unsatisfiable = new CorridorTerms(needsAnAddress.key(), needsAnAddress.rails(),
                    needsAnAddress.feeFixed(), needsAnAddress.feeMargin(), needsAnAddress.feeRounding(),
                    needsAnAddress.maximum(), needsAnAddress.screeningValidity(), needsAnAddress.deliveryEstimate(),
                    Set.of(RequiredData.BENEFICIARY_NAME, RequiredData.BENEFICIARY_ADDRESS));
            assertThatThrownBy(() -> administration().propose(app,
                            new CorridorPolicyProposal(List.of(unsatisfiable), "needs an address"),
                            controller(), Instant.now(), correlation()))
                    .isInstanceOf(CorridorPolicyAdministration.RequiredDataUnsatisfiable.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("the stored terms read back exactly as proposed - O7's corridors at their own scales")
    void theTermsReadBackExactly() throws SQLException {
        withdrawPending();
        List<CorridorTerms> o7 = List.of(
                terms("EUR", "USD", "US", "2.50", "10000.00"),
                terms("EUR", "JPY", "JP", "2.50", "1500000"),
                terms("USD", "BHD", "BH", "3.00", "4000.000"),
                terms("GBP", "USD", "US", "2.00", "10000.00"));
        try (Connection app = application()) {
            CorridorPolicyAdministration.Proposed proposed = administration().propose(
                    app, new CorridorPolicyProposal(o7, "O7's four corridors"), controller(), Instant.now(), correlation());
            app.commit();
            assertThat(new JdbcCorridorPolicyStore(IDS).version(app, proposed.id()).orElseThrow().corridors())
                    .containsExactlyInAnyOrderElementsOf(o7);
            administration().reject(app, proposed.id(), controller(), "read back", Instant.now(), correlation());
            app.commit();
        }
    }

    private static List<String> namesIn(Connection connection, String constraint) throws SQLException {
        String definition = scalar(connection,
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraint + "'");
        List<String> names = new ArrayList<>();
        Matcher name = Pattern.compile("'([A-Z_]+)'::text").matcher(definition);
        while (name.find()) {
            names.add(name.group(1));
        }
        return names;
    }

    @Test
    @DisplayName("one proposal at a time: a second proposal is refused while one is pending")
    void oneProposalAtATime() throws SQLException {
        withdrawPending();
        try (Connection app = application()) {
            administration().propose(app, proposal("first"), controller(), Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> administration().propose(app, proposal("second"), controller(), Instant.now(), correlation()))
                    .isInstanceOf(CorridorPolicyAdministration.ProposalPending.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten approvers of one proposal: one activation; ten proposers: one proposal (counted)")
    void tenRacersConverge() throws Exception {
        withdrawPending();
        CorridorPolicyId proposedId;
        Actor proposer = controller();
        try (Connection app = application()) {
            proposedId = administration().propose(app, proposal("raced"), proposer, Instant.now(), correlation()).id();
            app.commit();
        }
        long activeBefore = activations();
        List<Optional<CorridorPolicyAdministration.Decided>> approvals =
                race(10, () -> {
                    try (Connection own = application()) {
                        try {
                            CorridorPolicyAdministration.Decided decided =
                                    administration().approve(own, proposedId, controller(), "raced approval",
                                            Instant.now(), correlation());
                            own.commit();
                            return Optional.of(decided);
                        } catch (CorridorPolicyAdministration.ProposalNotPending lost) {
                            own.rollback();
                            return Optional.empty();
                        }
                    }
                });
        assertThat(approvals.stream().filter(Optional::isPresent).count()).isEqualTo(1);
        assertThat(activations() - activeBefore).as("counted in the history").isEqualTo(1);

        List<Boolean> proposals =
                race(10, () -> {
                    try (Connection own = application()) {
                        try {
                            administration().propose(own, proposal("raced proposal"), controller(), Instant.now(), correlation());
                            own.commit();
                            return true;
                        } catch (CorridorPolicyAdministration.ProposalPending lost) {
                            own.rollback();
                            return false;
                        }
                    }
                });
        assertThat(proposals.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM crossborder.corridor_policy_version WHERE status = 'PROPOSED'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static long activations() throws SQLException {
        try (Connection app = application()) {
            long count = Long.parseLong(scalar(app,
                    "SELECT count(*) FROM crossborder.corridor_policy_event WHERE to_status = 'ACTIVE'"));
            app.rollback();
            return count;
        }
    }

    private static String status(Connection app, CorridorPolicyId id) throws SQLException {
        return scalar(app, "SELECT status FROM crossborder.corridor_policy_version WHERE id = '" + id.value() + "'");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
    /** The audit records of {@code operation} on {@code target} (the P9-DOC-001 exit review: every act positively asserted). */
    private static String audits(Connection app, String operation, String target) throws SQLException {
        return scalar(app, "SELECT count(*) FROM platform.audit_record WHERE operation = '" + operation
                + "' AND target_id = '" + target + "'");
    }
}
