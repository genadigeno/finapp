package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.ADMINISTRATION;
import static com.finapp.credit.ScorecardFixtures.STORE;
import static com.finapp.credit.ScorecardFixtures.activate;
import static com.finapp.credit.ScorecardFixtures.clearPending;
import static com.finapp.credit.ScorecardFixtures.correlation;
import static com.finapp.credit.ScorecardFixtures.count;
import static com.finapp.credit.ScorecardFixtures.employee;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;
import static com.finapp.credit.ScorecardFixtures.propose;
import static com.finapp.credit.ScorecardFixtures.race;
import static com.finapp.credit.ScorecardFixtures.refused;
import static com.finapp.credit.ScorecardFixtures.status;
import static com.finapp.credit.ScorecardFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The scorecard model's versioning against a real database (`P10-TSK-011`; {@code INV-CRD-05}, {@code INV-HIST-04},
 * {@code INV-AUD-04}): every race counted, the four eyes and the frozen bands held for every writer by raw SQL, and
 * the version active at any past instant answered from the rows.
 */
@Tag("database")
@DisplayName("the scorecard model and its versioning (P10-TSK-011)")
class ScorecardVersionDatabaseTest {

    @Test
    @DisplayName("the seed is RETAIL_SCORECARD v1, proposed by the migration, its bands exactly the table - never active by migration")
    void theSeedIsAProposal() throws SQLException {
        Optional<ScorecardStore.ModelVersion> seed = inOneTransaction(uow -> STORE.model(uow, RetailScorecardV1.ID));
        assertThat(seed).isPresent();
        assertThat(seed.get().row().version()).isEqualTo(1);
        assertThat(seed.get().row().proposedBy()).isEqualTo("migration:V006");
        assertThat(seed.get().scorecard()).isEqualTo(RetailScorecardV1.scorecard());
        assertThat(count("SELECT count(*) FROM credit.scorecard_model_event WHERE model_version_id = '"
                + RetailScorecardV1.ID.value() + "' AND to_status = 'ACTIVE' AND actor_id LIKE 'migration:%'"))
                .as("no migration ever activates").isZero();
    }

    @Test
    @DisplayName("ten approvers of one proposal: one activation, nine PolicyStale - counted")
    void tenApproversActivateOnce() throws Exception {
        ScorecardAdministration.Proposed proposed = propose(611, employee());
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "racing", correlation()));
            } catch (ScorecardAdministration.PolicyStale stale) {
                return stale;
            }
        });
        assertThat(outcomes.stream().filter(ScorecardAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(ScorecardAdministration.PolicyStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.ScorecardModelVersionActivated'"
                + " AND aggregate_id = '" + proposed.id().value() + "'")).as("one event").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.ScorecardVersionActivated'"
                + " AND target_id = '" + proposed.id().value() + "'")).as("losers record nothing").isEqualTo(1);
    }

    @Test
    @DisplayName("ten proposers of one family: one proposal, nine ProposalPending - counted")
    void tenProposersLeaveOneProposal() throws Exception {
        clearPending();
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.propose(uow, ScorecardFamily.RETAIL_SCORECARD, table(612),
                        "racing", employee(), correlation()));
            } catch (ScorecardAdministration.ProposalPending pending) {
                return pending;
            }
        });
        assertThat(outcomes.stream().filter(ScorecardAdministration.Proposed.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(ScorecardAdministration.ProposalPending.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD'"
                + " AND status = 'PROPOSED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the proposer cannot approve - refused by the domain, and by the CHECK for a raw-SQL writer")
    void theProposerCannotApprove() throws SQLException {
        Actor proposer = employee();
        ScorecardAdministration.Proposed proposed = propose(613, proposer);
        assertThatExceptionOfType(ScorecardAdministration.SelfApprovalRefused.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.approve(uow, proposed.id(), proposer, "my own", correlation())));
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.scorecard_model_version SET status = 'ACTIVE', decided_by = proposed_by,"
                    + " decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'", "23514");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE credit.scorecard_model_version SET status = 'ACTIVE', decided_by = proposed_by,"
                    + " decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'", "23514");
        }
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
    }

    @Test
    @DisplayName("bands are immutable from insert - an UPDATE, a DELETE and a late band refused while PROPOSED, and again once ACTIVE")
    void bandsAreImmutableFromInsert() throws SQLException {
        ScorecardAdministration.Proposed proposed = propose(614, employee());
        bandsRefused(proposed.id());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "now active", correlation()));
        bandsRefused(proposed.id());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "TRUNCATE credit.scorecard_band", "P0001");
            refused(owner, "UPDATE credit.scorecard_model_version SET base_points = 1 WHERE id = '" + proposed.id().value()
                    + "'", "P0001");
            refused(owner, "DELETE FROM credit.scorecard_model_version WHERE id = '" + proposed.id().value() + "'", "P0001");
        }
        assertThat(inOneTransaction(uow -> STORE.model(uow, proposed.id())).orElseThrow().scorecard()).isEqualTo(table(614));
    }

    private static void bandsRefused(ScorecardModelVersionId id) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.scorecard_band SET points = points + 1 WHERE model_version_id = '" + id.value()
                    + "' AND attribute_code = 'BUREAU_EXTERNAL_SCORE' AND ordinal = 1", "P0001");
            refused(owner, "DELETE FROM credit.scorecard_band WHERE model_version_id = '" + id.value()
                    + "' AND attribute_code = 'BUREAU_INSOLVENCY_FLAG' AND ordinal = 2", "P0001");
            refused(owner, "INSERT INTO credit.scorecard_band (model_version_id, attribute_code, ordinal, kind, points)"
                    + " VALUES ('" + id.value() + "', 'RISK_SIGNAL', 0, 'ABSENT', 99)", "P0001");
        }
    }

    @Test
    @DisplayName("one ACTIVE version per family - a raw-SQL second activation is refused by the partial unique")
    void oneActivePerFamily() throws SQLException {
        activate(615);
        ScorecardAdministration.Proposed second = propose(616, employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.scorecard_model_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decision_reason = 'raw' WHERE id = '" + second.id().value() + "'", "23505");
            refused(owner, "INSERT INTO credit.scorecard_model_version (id, family, version, status, base_points, proposed_by,"
                    + " proposed_at, proposal_reason, decided_by, decided_at, decision_reason, effective_from) VALUES"
                    + " (gen_random_uuid(), 'RETAIL_SCORECARD', 999, 'ACTIVE', 0, 'a', now(), 'r', 'b', now(), 'r', now())",
                    "P0001");
        }
        assertThat(count("SELECT count(*) FROM credit.scorecard_model_version WHERE family = 'RETAIL_SCORECARD'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the version active at any past instant is answered from the rows - the predecessor's end is the successor's start")
    void theVersionActiveAtAnyPastInstantIsAnswerable() throws Exception {
        ScorecardModelVersionId first = activate(617);
        Instant firstFrom = effective(first, "effective_from");
        Thread.sleep(20);
        ScorecardModelVersionId second = activate(618);
        Instant secondFrom = effective(second, "effective_from");
        assertThat(effective(first, "effective_to")).as("they meet").isEqualTo(secondFrom);
        assertThat(status(first)).isEqualTo("RETIRED");
        assertThat(activeAt(firstFrom)).contains(first);
        assertThat(activeAt(secondFrom.minusNanos(1000))).contains(first);
        assertThat(activeAt(secondFrom)).as("the start belongs to the successor").contains(second);
        assertThat(activeAt(secondFrom.plusSeconds(3600))).contains(second);
    }

    @Test
    @DisplayName("a retirement that commits alone is refused at commit - only beside its successor")
    void aRetirementNeverCommitsAlone() throws SQLException {
        ScorecardModelVersionId active = activate(619);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.scorecard_model_version SET status = 'RETIRED' WHERE id = '" + active.value() + "'",
                    "P0001");
        }
        assertThat(status(active)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("an activation committed while an assessment is under way leaves the assessment on the model its snapshot pinned")
    void anActivationMidAssessmentKeepsThePinnedModel() throws Exception {
        ScorecardModelVersionId pinned = activate(620);
        DecisionSnapshot snapshot = ScorecardFixtures.snapshot(pinned);
        CreditAssessments assessments = CreditAssessmentDatabaseTest.assessments();
        CreditAssessments.Assessed assessed = inOneTransaction(uow -> {
            ScorecardModelVersionId successor = activate(9_620);
            assertThat(successor).isNotEqualTo(pinned);
            return assessments.assess(uow, snapshot, CreditAssessmentDatabaseTest.TERMS, correlation());
        });
        assertThat(status(pinned)).as("superseded mid-assessment").isEqualTo("RETIRED");
        assertThat(assessed.assessment().score()).as("620 + the golden 70, never 9,620's").isEqualTo(690);
        assertThat(assessed.assessment().versions().modelVersion()).isEqualTo(pinned.value());
    }

    private static Optional<ScorecardModelVersionId> activeAt(Instant instant) {
        return inOneTransaction(uow -> STORE.activeAt(uow, ScorecardFamily.RETAIL_SCORECARD, instant));
    }

    private static Instant effective(ScorecardModelVersionId id, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT " + column + " FROM credit.scorecard_model_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }
}
