package com.finapp.credit;

import static com.finapp.credit.ScorecardFixtures.CLOCK;
import static com.finapp.credit.ScorecardFixtures.IDS;
import static com.finapp.credit.ScorecardFixtures.STORE;
import static com.finapp.credit.ScorecardFixtures.activate;
import static com.finapp.credit.ScorecardFixtures.correlation;
import static com.finapp.credit.ScorecardFixtures.count;
import static com.finapp.credit.ScorecardFixtures.inOneTransaction;
import static com.finapp.credit.ScorecardFixtures.propose;
import static com.finapp.credit.ScorecardFixtures.race;
import static com.finapp.credit.ScorecardFixtures.refused;
import static com.finapp.credit.ScorecardFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The credit assessment against a real database (`P10-TSK-011`; {@code INV-CRD-06}, {@code INV-CRD-04},
 * {@code INV-CRD-12}): born once per snapshot under every race, each figure assessed or named unassessable, never
 * changed by any role.
 */
@Tag("database")
@DisplayName("the credit assessment (P10-TSK-011)")
class CreditAssessmentDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The terms a case judges against: an 8.99% stress rate, 3% of a line, 500.00 left over, 20,000.00 of exposure. */
    static final CreditAssessments.Terms TERMS = new CreditAssessments.Terms(
            new AffordabilityAssessment.Parameters(new BigDecimal("0.0899"), new BigDecimal("0.03"), Money.ofMinorUnits(500_00, EUR)),
            Money.ofMinorUnits(2_000_000, EUR));

    static CreditAssessments assessments() {
        return new CreditAssessments(new JdbcCreditAssessmentStore(), STORE, new JdbcOutboxWriter(), IDS, CLOCK);
    }

    @Test
    @DisplayName("ten assessors of one snapshot leave one assessment, answer with it, and publish once - counted")
    void tenAssessorsLeaveOneAssessment() throws Exception {
        DecisionSnapshot snapshot = snapshot(activate(701));
        CreditAssessments assessments = assessments();
        List<CreditAssessments.Assessed> outcomes = race(10,
                () -> inOneTransaction(uow -> assessments.assess(uow, snapshot, TERMS, correlation())));
        assertThat(outcomes.stream().filter(outcome -> !outcome.replayed())).hasSize(1);
        assertThat(outcomes.stream().map(outcome -> outcome.assessment().id()).distinct()).hasSize(1);
        assertThat(count("SELECT count(*) FROM credit.credit_assessment WHERE snapshot_id = '" + snapshot.id().value() + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'credit.CreditAssessmentCreated'"
                + " AND aggregate_id = '" + outcomes.get(0).assessment().id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the three figures are recorded exactly, and read back as computed")
    void theFiguresAreRecorded() {
        DecisionSnapshot snapshot = snapshot(activate(702));
        CreditAssessment assessment = inOneTransaction(uow -> assessments().assess(uow, snapshot, TERMS, correlation()))
                .assessment();
        AffordabilityAssessment.Assessment.Assessed affordability =
                (AffordabilityAssessment.Assessment.Assessed) assessment.affordability();
        assertThat(affordability.repayment()).isEqualTo(Money.ofMinorUnits(317_95, EUR));
        assertThat(affordability.disposable()).isEqualTo(Money.ofMinorUnits(1_132_05, EUR));
        ExposureAssessment.Assessment.Assessed exposure = (ExposureAssessment.Assessment.Assessed) assessment.exposure();
        assertThat(exposure.exposure()).isEqualTo(Money.ofMinorUnits(1_420_050, EUR));
        assertThat(exposure.headroom()).isEqualTo(Money.ofMinorUnits(579_950, EUR));
        assertThat(assessment.score()).isEqualTo(702 + 70);
        assertThat(assessment.snapshotSha256()).isEqualTo(snapshot.sha256());
        assertThat(inOneTransaction(uow -> new JdbcCreditAssessmentStore().bySnapshot(uow, snapshot.id())).orElseThrow()
                .sameFigures(assessment)).isTrue();
    }

    @Test
    @DisplayName("an unassessable figure is recorded with the codes that made it so - never a zero")
    void anUnassessableFigureIsNamed() {
        List<CreditAttribute> attributes = new ArrayList<>();
        for (CreditAttribute attribute : CanonicalSnapshotTest.attributes()) {
            boolean absent = attribute.code() == CreditAttributeCode.BUREAU_TOTAL_BALANCE
                    || attribute.code() == CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS;
            attributes.add(absent ? new CreditAttribute(attribute.code(), new AttributeValue.Absent(), attribute.provenance())
                    : attribute);
        }
        DecisionSnapshot snapshot = snapshot(activate(703), attributes);
        CreditAssessment assessment = inOneTransaction(uow -> assessments().assess(uow, snapshot, TERMS, correlation()))
                .assessment();
        assertThat(assessment.affordability()).isEqualTo(new AffordabilityAssessment.Assessment.Unassessable(
                Set.of(CreditAttributeCode.BUREAU_MONTHLY_OBLIGATIONS)));
        assertThat(assessment.exposure()).isEqualTo(new ExposureAssessment.Assessment.Unassessable(
                Set.of(CreditAttributeCode.BUREAU_TOTAL_BALANCE)));
    }

    @Test
    @DisplayName("a model that was never active scores nothing; a disagreeing second computation is a defect, not a replacement")
    void theAssessmentIsTheSnapshotsAndBornOnce() {
        DecisionSnapshot proposedOnly = snapshot(propose(704, ScorecardFixtures.employee()).id());
        assertThatIllegalStateException().isThrownBy(() -> inOneTransaction(
                uow -> assessments().assess(uow, proposedOnly, TERMS, correlation())));
        DecisionSnapshot snapshot = snapshot(activate(705));
        inOneTransaction(uow -> assessments().assess(uow, snapshot, TERMS, correlation()));
        CreditAssessments.Terms other = new CreditAssessments.Terms(TERMS.affordability(), Money.ofMinorUnits(100, EUR));
        assertThatIllegalStateException().isThrownBy(() -> inOneTransaction(
                uow -> assessments().assess(uow, snapshot, other, correlation())));
    }

    @Test
    @DisplayName("no role changes an assessment - an UPDATE, a DELETE and a TRUNCATE refused, even for the owner")
    void noRoleChangesAnAssessment() throws SQLException {
        DecisionSnapshot snapshot = snapshot(activate(706));
        inOneTransaction(uow -> assessments().assess(uow, snapshot, TERMS, correlation()));
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE credit.credit_assessment SET score = 0 WHERE snapshot_id = '" + snapshot.id().value() + "'",
                    "P0001");
            refused(owner, "DELETE FROM credit.credit_assessment WHERE snapshot_id = '" + snapshot.id().value() + "'", "P0001");
            refused(owner, "TRUNCATE credit.credit_assessment", "P0001");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE credit.credit_assessment SET score = 0 WHERE snapshot_id = '"
                    + snapshot.id().value() + "'", "42501");
        }
    }
}
