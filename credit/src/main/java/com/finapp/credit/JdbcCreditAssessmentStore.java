package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link CreditAssessmentStore} over {@code credit V007} (`P10-TSK-011`). Stateless. */
public final class JdbcCreditAssessmentStore implements CreditAssessmentStore {

    private static final String COLUMNS = "id, snapshot_id, decision_request_id, snapshot_sha256, policy_version_id,"
            + " model_version_id, engine_version, currency, affordability_assessed, income_minor, expenditure_minor,"
            + " obligations_minor, repayment_minor, disposable_minor, affordable, affordability_absent, exposure_assessed,"
            + " exposure_minor, headroom_minor, within_limit, exposure_absent, score, assessed_at";

    @Override
    public boolean insert(Connection unitOfWork, CreditAssessment assessment) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO credit.credit_assessment (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())"
                + " ON CONFLICT (snapshot_id) DO NOTHING")) {
            insert.setObject(1, assessment.id().value());
            insert.setObject(2, assessment.snapshot().value());
            insert.setObject(3, assessment.decisionRequest());
            insert.setBytes(4, assessment.snapshotSha256());
            insert.setObject(5, assessment.versions().policyVersion());
            insert.setObject(6, assessment.versions().modelVersion());
            insert.setInt(7, assessment.versions().engineVersion());
            insert.setString(8, assessment.currency().code());
            switch (assessment.affordability()) {
                case AffordabilityAssessment.Assessment.Assessed a -> {
                    insert.setBoolean(9, true);
                    insert.setLong(10, a.income().minorUnits());
                    insert.setLong(11, a.expenditure().minorUnits());
                    insert.setLong(12, a.obligations().minorUnits());
                    insert.setLong(13, a.repayment().minorUnits());
                    insert.setLong(14, a.disposable().minorUnits());
                    insert.setBoolean(15, a.affordable());
                    insert.setArray(16, codes(unitOfWork, Set.of()));
                }
                case AffordabilityAssessment.Assessment.Unassessable u -> {
                    insert.setBoolean(9, false);
                    for (int i = 10; i <= 14; i++) {
                        insert.setNull(i, Types.BIGINT);
                    }
                    insert.setNull(15, Types.BOOLEAN);
                    insert.setArray(16, codes(unitOfWork, u.absent()));
                }
            }
            switch (assessment.exposure()) {
                case ExposureAssessment.Assessment.Assessed e -> {
                    insert.setBoolean(17, true);
                    insert.setLong(18, e.exposure().minorUnits());
                    insert.setLong(19, e.headroom().minorUnits());
                    insert.setBoolean(20, e.within());
                    insert.setArray(21, codes(unitOfWork, Set.of()));
                }
                case ExposureAssessment.Assessment.Unassessable u -> {
                    insert.setBoolean(17, false);
                    insert.setNull(18, Types.BIGINT);
                    insert.setNull(19, Types.BIGINT);
                    insert.setNull(20, Types.BOOLEAN);
                    insert.setArray(21, codes(unitOfWork, u.absent()));
                }
            }
            insert.setInt(22, assessment.score());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a credit assessment", failure));
        }
    }

    @Override
    public Optional<CreditAssessment> bySnapshot(Connection unitOfWork, DecisionSnapshotId snapshot) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM credit.credit_assessment WHERE snapshot_id = ?")) {
            select.setObject(1, snapshot.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(assessment(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit assessment", failure));
        }
    }

    private static CreditAssessment assessment(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        AffordabilityAssessment.Assessment affordability = row.getBoolean("affordability_assessed")
                ? new AffordabilityAssessment.Assessment.Assessed(
                        money(row, "income_minor", currency), money(row, "expenditure_minor", currency),
                        money(row, "obligations_minor", currency), money(row, "repayment_minor", currency),
                        money(row, "disposable_minor", currency), row.getBoolean("affordable"))
                : new AffordabilityAssessment.Assessment.Unassessable(codes(row, "affordability_absent"));
        ExposureAssessment.Assessment exposure = row.getBoolean("exposure_assessed")
                ? new ExposureAssessment.Assessment.Assessed(
                        money(row, "exposure_minor", currency), money(row, "headroom_minor", currency),
                        row.getBoolean("within_limit"))
                : new ExposureAssessment.Assessment.Unassessable(codes(row, "exposure_absent"));
        return new CreditAssessment(
                CreditAssessmentId.of(row.getObject("id", UUID.class)),
                DecisionSnapshotId.of(row.getObject("snapshot_id", UUID.class)),
                row.getObject("decision_request_id", UUID.class),
                row.getBytes("snapshot_sha256"),
                new PinnedVersions(row.getObject("policy_version_id", UUID.class),
                        row.getObject("model_version_id", UUID.class), row.getInt("engine_version")),
                currency,
                affordability,
                exposure,
                row.getInt("score"),
                row.getTimestamp("assessed_at").toInstant());
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency) throws SQLException {
        return Money.ofMinorUnits(row.getLong(column), currency);
    }

    private static java.sql.Array codes(Connection unitOfWork, Set<CreditAttributeCode> codes) throws SQLException {
        return unitOfWork.createArrayOf("text", codes.stream().map(Enum::name).sorted().toArray());
    }

    private static Set<CreditAttributeCode> codes(ResultSet row, String column) throws SQLException {
        Set<CreditAttributeCode> codes = EnumSet.noneOf(CreditAttributeCode.class);
        Arrays.stream((String[]) row.getArray(column).getArray()).map(CreditAttributeCode::valueOf).forEach(codes::add);
        return codes;
    }
}
