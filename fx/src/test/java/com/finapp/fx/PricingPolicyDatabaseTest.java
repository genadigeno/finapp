package com.finapp.fx;

import static com.finapp.fx.FxPolicyFixtures.activated;
import static com.finapp.fx.FxPolicyFixtures.administration;
import static com.finapp.fx.FxPolicyFixtures.application;
import static com.finapp.fx.FxPolicyFixtures.controller;
import static com.finapp.fx.FxPolicyFixtures.correlation;
import static com.finapp.fx.FxPolicyFixtures.proposal;
import static com.finapp.fx.FxPolicyFixtures.scalar;
import static com.finapp.fx.FxPolicyFixtures.withdrawPending;
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
 * The pricing policy against a live PostgreSQL (`P9-TSK-007`, ADR-0075 §7; {@code INV-AUD-04},
 * {@code INV-HIST-04}, {@code INV-MON-03}): no version is {@code ACTIVE} without two named persons,
 * each rank of that rule proven alone, every invalid edge refused for every writer, the content
 * frozen, a retirement only beside its successor, and ten racers converging (counted).
 */
@Tag("database")
@DisplayName("the pricing policy: four eyes, frozen, versioned, raced (P9-TSK-007)")
class PricingPolicyDatabaseTest {

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
            PricingPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("a new price"), first, Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> administration().approve(app, proposed.id(), first, "mine", Instant.now(), correlation()))
                    .isInstanceOf(PricingPolicyAdministration.SelfApprovalRefused.class);
            app.rollback();
            PricingPolicyAdministration.Decided activated =
                    administration().approve(app, proposed.id(), second, "reviewed", Instant.now(), correlation());
            app.commit();
            assertThat(activated.status()).isEqualTo(PricingPolicyStatus.ACTIVE);
            // The same person's retry converges, writing nothing.
            assertThat(administration().approve(app, proposed.id(), second, "again", Instant.now(), correlation()).replayed())
                    .isTrue();
            app.commit();

            PricingPolicyAdministration.Proposed next =
                    administration().propose(app, proposal("the next price"), second, Instant.now(), correlation());
            PricingPolicyAdministration.Decided successor =
                    administration().approve(app, next.id(), first, "reviewed too", Instant.now(), correlation());
            app.commit();
            assertThat(successor.retired()).contains(proposed.id());
            assertThat(audits(app, "fx.PricingPolicyProposed", proposed.id().value().toString())).as("proposed, audited once").isEqualTo("1");
            assertThat(audits(app, "fx.PricingPolicyActivated", proposed.id().value().toString()))
                    .as("activated, audited once - the retry wrote nothing").isEqualTo("1");
            assertThat(status(app, proposed.id())).isEqualTo("RETIRED");
            assertThat(scalar(app, "SELECT count(*) FROM fx.pricing_policy_version WHERE status = 'ACTIVE'"))
                    .isEqualTo("1");
            assertThat(scalar(app, "SELECT count(*) FROM fx.pricing_policy_event WHERE policy_id = '"
                            + proposed.id().value() + "'"))
                    .as("proposed, activated, retired")
                    .isEqualTo("3");
            assertThat(scalar(app, "SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                            + " 'fx.PricingPolicyActivated' AND aggregate_id = '" + next.id().value() + "'"))
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
            PricingPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("for the CHECK"), controller(), Instant.now(), correlation());
            app.commit();
            // Any ACTIVE version is retired first, so only the four-eyes rule can refuse.
            execute(app, "UPDATE fx.pricing_policy_version SET status = 'RETIRED', retired_at = now()"
                    + " WHERE status = 'ACTIVE'");
            assertThatThrownBy(() -> execute(app, "UPDATE fx.pricing_policy_version SET status = 'ACTIVE',"
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
        PricingPolicyId active = activated();
        withdrawPending();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (String sql :
                    List.of(
                            "UPDATE fx.pricing_policy_version SET status = 'PROPOSED' WHERE id = '%s'",
                            "UPDATE fx.pricing_policy_version SET status = 'REJECTED' WHERE id = '%s'",
                            "UPDATE fx.pricing_policy_version SET open_quote_cap = 99 WHERE id = '%s'",
                            "UPDATE fx.pricing_policy_version SET proposed_by = 'someone' WHERE id = '%s'",
                            "DELETE FROM fx.pricing_policy_version WHERE id = '%s'")) {
                assertThatThrownBy(() -> execute(migrator, sql.formatted(active.value())))
                        .as(sql)
                        .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
                migrator.rollback();
            }
            assertThatThrownBy(() -> execute(migrator, "UPDATE fx.pricing_pair SET spread = 0.01"
                            + " WHERE policy_id = '" + active.value() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            migrator.rollback();
            assertThatThrownBy(() -> execute(migrator, "INSERT INTO fx.pricing_pair SELECT policy_id, 'USD', 'EUR',"
                            + " purpose, providers, spread, markup, rate_scale, rate_rounding, amount_rounding,"
                            + " margin_rounding, window_seconds, cover_margin_seconds, band,"
                            + " reference_max_age_seconds, source_min_minor, source_max_minor,"
                            + " destination_min_minor, destination_max_minor FROM fx.pricing_pair"
                            + " WHERE policy_id = '" + active.value() + "'"))
                    .as("a pair added to a decided version")
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            migrator.rollback();
        }
        // A REJECTED version is terminal.
        try (Connection app = application()) {
            PricingPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("to reject"), controller(), Instant.now(), correlation());
            administration().reject(app, proposed.id(), controller(), "no", Instant.now(), correlation());
            app.commit();
            assertThat(audits(app, "fx.PricingPolicyRejected", proposed.id().value().toString())).as("rejected, audited once").isEqualTo("1");
            assertThatThrownBy(() -> execute(app, "UPDATE fx.pricing_policy_version SET status = 'ACTIVE'"
                            + " WHERE id = '" + proposed.id().value() + "'"))
                    .matches(e -> RAISED.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> administration().approve(app, proposed.id(), controller(), "late", Instant.now(), correlation()))
                    .isInstanceOf(PricingPolicyAdministration.ProposalNotPending.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("a retirement without a later ACTIVE successor is refused at commit")
    void aRetirementNeedsItsSuccessor() throws SQLException {
        activated();
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            execute(migrator, "UPDATE fx.pricing_policy_version SET status = 'RETIRED', retired_at = now()"
                    + " WHERE status = 'ACTIVE'");
            assertThatThrownBy(migrator::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("successor");
        }
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM fx.pricing_policy_version WHERE status = 'ACTIVE'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    @Test
    @DisplayName("the pair's money rules for every writer: a zero margin, a scale above 10 and an"
            + " unnamed rounding are unstorable; a second ACTIVE version too")
    void theMoneyRulesHoldForEveryWriter() throws SQLException {
        PricingPolicyId active = activated();
        withdrawPending();
        try (Connection app = application()) {
            PricingPolicyAdministration.Proposed proposed =
                    administration().propose(app, proposal("for the pair CHECKs"), controller(), Instant.now(), correlation());
            app.commit();
            String clone = "INSERT INTO fx.pricing_pair SELECT policy_id, %s, purpose, providers, %s, rate_rounding,"
                    + " amount_rounding, %s, window_seconds, cover_margin_seconds, band,"
                    + " reference_max_age_seconds, source_min_minor, source_max_minor, destination_min_minor,"
                    + " destination_max_minor FROM fx.pricing_pair WHERE policy_id = '"
                    + proposed.id().value() + "'";
            assertThatThrownBy(() -> execute(app, clone.formatted("'USD', 'EUR'", "0, 0, rate_scale", "margin_rounding")))
                    .as("spread + markup = 0")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, clone.formatted("'USD', 'EUR'", "spread, markup, 11", "margin_rounding")))
                    .as("a rate scale of 11")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, clone.formatted("'USD', 'EUR'", "spread, markup, rate_scale", "'BANKERS'")))
                    .as("an unnamed rounding")
                    .matches(e -> CHECK_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
            assertThatThrownBy(() -> execute(app, "UPDATE fx.pricing_policy_version SET status = 'ACTIVE',"
                            + " decided_by = 'another-person', decided_at = now(), decision_reason = 'x'"
                            + " WHERE id = '" + proposed.id().value() + "'"))
                    .as("a second ACTIVE version beside " + active.value())
                    .matches(e -> UNIQUE_VIOLATION.equals(((SQLException) e).getSQLState()));
            app.rollback();
        }
    }

    @Test
    @DisplayName("the rounding CHECK lists are exactly RoundingPolicy's names (INV-MON-03)")
    void theRoundingListsAreTheEnums() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            for (String constraint :
                    List.of("pricing_pair_rate_rounding_is_named", "pricing_pair_amount_rounding_is_named",
                            "pricing_pair_margin_rounding_is_named")) {
                String definition = scalar(migrator,
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + constraint + "'");
                List<String> names = new ArrayList<>();
                Matcher name = Pattern.compile("'([A-Z_]+)'::text").matcher(definition);
                while (name.find()) {
                    names.add(name.group(1));
                }
                assertThat(names)
                        .as(constraint)
                        .containsExactlyInAnyOrderElementsOf(
                                Arrays.stream(RoundingPolicy.values()).map(Enum::name).toList());
            }
        }
    }

    @Test
    @DisplayName("one proposal at a time: a second proposal is refused while one is pending")
    void oneProposalAtATime() throws SQLException {
        withdrawPending();
        try (Connection app = application()) {
            administration().propose(app, proposal("first"), controller(), Instant.now(), correlation());
            app.commit();
            assertThatThrownBy(() -> administration().propose(app, proposal("second"), controller(), Instant.now(), correlation()))
                    .isInstanceOf(PricingPolicyAdministration.ProposalPending.class);
            app.rollback();
        }
    }

    @Test
    @DisplayName("ten approvers of one proposal: one activation; ten proposers: one proposal (counted)")
    void tenRacersConverge() throws Exception {
        withdrawPending();
        PricingPolicyId proposedId;
        Actor proposer = controller();
        try (Connection app = application()) {
            proposedId = administration().propose(app, proposal("raced"), proposer, Instant.now(), correlation()).id();
            app.commit();
        }
        long activeBefore = activations();
        List<Optional<PricingPolicyAdministration.Decided>> approvals =
                race(10, () -> {
                    try (Connection own = application()) {
                        try {
                            PricingPolicyAdministration.Decided decided =
                                    administration().approve(own, proposedId, controller(), "raced approval",
                                            Instant.now(), correlation());
                            own.commit();
                            return Optional.of(decided);
                        } catch (PricingPolicyAdministration.ProposalNotPending lost) {
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
                        } catch (PricingPolicyAdministration.ProposalPending lost) {
                            own.rollback();
                            return false;
                        }
                    }
                });
        assertThat(proposals.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        try (Connection app = application()) {
            assertThat(scalar(app, "SELECT count(*) FROM fx.pricing_policy_version WHERE status = 'PROPOSED'"))
                    .isEqualTo("1");
            app.rollback();
        }
    }

    // -----------------------------------------------------------------

    private static long activations() throws SQLException {
        try (Connection app = application()) {
            long count = Long.parseLong(scalar(app,
                    "SELECT count(*) FROM fx.pricing_policy_event WHERE to_status = 'ACTIVE'"));
            app.rollback();
            return count;
        }
    }

    private static String status(Connection app, PricingPolicyId id) throws SQLException {
        return scalar(app, "SELECT status FROM fx.pricing_policy_version WHERE id = '" + id.value() + "'");
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
