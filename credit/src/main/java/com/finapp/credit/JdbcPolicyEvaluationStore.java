package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** {@link PolicyEvaluationStore} over {@code credit V009} (`P10-TSK-013`). Stateless. */
public final class JdbcPolicyEvaluationStore implements PolicyEvaluationStore {

    private static final String COLUMNS = "id, assessment_id, decision_request_id, policy_version_id, engine_version,"
            + " outcome, currency, requested_minor, approved_minor, reason_codes, fallback_applied, evaluated_at";

    @Override
    public boolean insert(Connection unitOfWork, PolicyEvaluation evaluation) {
        EvaluationResult result = evaluation.result();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO credit.policy_evaluation (" + COLUMNS
                    + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp())"
                    + " ON CONFLICT (assessment_id) DO NOTHING")) {
                insert.setObject(1, evaluation.id().value());
                insert.setObject(2, evaluation.assessment().value());
                insert.setObject(3, evaluation.decisionRequest());
                insert.setObject(4, evaluation.policyVersion().value());
                insert.setInt(5, result.engineVersion());
                insert.setString(6, result.outcome().name());
                insert.setString(7, result.requested().currency().code());
                insert.setLong(8, result.requested().minorUnits());
                if (result.approved().isPresent()) {
                    insert.setLong(9, result.approved().get().minorUnits());
                } else {
                    insert.setNull(9, Types.BIGINT);
                }
                insert.setArray(10, unitOfWork.createArrayOf("text",
                        result.reasons().stream().map(ReasonCode::code).toArray()));
                insert.setBoolean(11, result.fallbackApplied());
                if (insert.executeUpdate() != 1) {
                    return false;
                }
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.policy_evaluation_rule (evaluation_id, ordinal, rule_code, effect, triggered, assessed)"
                            + " VALUES (?, ?, ?, ?, ?, ?)")) {
                for (EvaluationResult.RuleResult rule : result.rules()) {
                    insert.setObject(1, evaluation.id().value());
                    insert.setInt(2, rule.ordinal());
                    insert.setString(3, rule.ruleCode());
                    insert.setString(4, rule.effect().name());
                    insert.setBoolean(5, rule.state().triggered());
                    insert.setBoolean(6, rule.state().assessed());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return true;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a policy evaluation", failure));
        }
    }

    @Override
    public Optional<PolicyEvaluation> byAssessment(Connection unitOfWork, CreditAssessmentId assessment) {
        try {
            UUID id;
            UUID decisionRequest;
            UUID policyVersion;
            int engine;
            EvaluationOutcome outcome;
            Money requested;
            Optional<Money> approved;
            List<ReasonCode> reasons;
            boolean fallback;
            java.time.Instant evaluatedAt;
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT " + COLUMNS + " FROM credit.policy_evaluation WHERE assessment_id = ?")) {
                select.setObject(1, assessment.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    id = row.getObject("id", UUID.class);
                    decisionRequest = row.getObject("decision_request_id", UUID.class);
                    policyVersion = row.getObject("policy_version_id", UUID.class);
                    engine = row.getInt("engine_version");
                    outcome = EvaluationOutcome.valueOf(row.getString("outcome"));
                    CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                    requested = Money.ofMinorUnits(row.getLong("requested_minor"), currency);
                    long approvedMinor = row.getLong("approved_minor");
                    approved = row.wasNull() ? Optional.empty() : Optional.of(Money.ofMinorUnits(approvedMinor, currency));
                    reasons = Arrays.stream((String[]) row.getArray("reason_codes").getArray())
                            .map(JdbcPolicyEvaluationStore::reasonCode)
                            .toList();
                    fallback = row.getBoolean("fallback_applied");
                    evaluatedAt = row.getTimestamp("evaluated_at").toInstant();
                }
            }
            List<EvaluationResult.RuleResult> rules = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT ordinal, rule_code, effect, triggered, assessed FROM credit.policy_evaluation_rule"
                            + " WHERE evaluation_id = ? ORDER BY ordinal")) {
                select.setObject(1, id);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        rules.add(new EvaluationResult.RuleResult(row.getInt("ordinal"), row.getString("rule_code"),
                                PolicyEffect.valueOf(row.getString("effect")),
                                EvaluationResult.RuleState.of(row.getBoolean("triggered"), row.getBoolean("assessed"))));
                    }
                }
            }
            return Optional.of(new PolicyEvaluation(PolicyEvaluationId.of(id), assessment, decisionRequest,
                    CreditPolicyVersionId.of(policyVersion),
                    new EvaluationResult(engine, outcome, requested, approved, reasons, fallback, rules), evaluatedAt));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a policy evaluation", failure));
        }
    }

    private static ReasonCode reasonCode(String code) {
        for (ReasonCode candidate : ReasonCode.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalStateException("a stored reason code is not in the catalogue enum");
    }
}
