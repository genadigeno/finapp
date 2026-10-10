package com.finapp.app.credit;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * THE MISSING-DATA CENSUS ({@code INV-CRD-10}, "missing data never approves"), recomputed from the rows alone - never from
 * what the evaluator recorded about itself, so an evaluator that approves on missing data cannot also hide it.
 *
 * <p>A snapshot rests on missing data, for the policy it was evaluated under, when either holds:
 *
 * <ol>
 *   <li><strong>a source the policy reads was unavailable</strong>: the snapshot's {@code SOURCE_UNAVAILABLE} marker holds
 *       a code ({@link com.finapp.credit.SourceKindsMarker}: the kinds joined by {@code _AND_}) naming a kind for which
 *       the pinned policy declares a maximum data age - exactly the kinds a policy reads
 *       ({@link com.finapp.credit.CreditPolicy#sourceKinds});
 *   <li><strong>a comparison the policy makes has nothing to compare</strong>: a rule of the pinned policy whose operator
 *       is a comparison (not {@code IS_ABSENT} / {@code IS_PRESENT}, which decide on absence itself) reads an attribute the
 *       snapshot holds {@code ABSENT}, or a figure its assessment could not compute ({@code DISPOSABLE_INCOME},
 *       {@code AFFORDABLE} without the affordability; {@code EXPOSURE}, {@code EXPOSURE_HEADROOM} without the exposure;
 *       the score is always computed). That is engine version 1's {@code UNASSESSED}, derived here independently.
 * </ol>
 *
 * <p>Either way an approval would rest on data nobody read, so the policy's fallback - refer or decline - must decide.
 * The census counts the breaches: a {@code SYSTEM} decision {@code APPROVED} on such a snapshot (a person's decision is
 * outside it - an underwriter may approve a referral on evidence the snapshot does not hold), and, one step earlier, any
 * evaluation {@code APPROVE} on one. Both must be zero; the subjects - evaluations on missing data at all - show the census
 * is not vacuous.
 *
 * <p>Read by the storm in its one {@code REPEATABLE READ} snapshot every round, and by the battery over its ten thousand.
 */
final class MissingDataCensus {

    /** Half 1: the snapshot {@code s} records a source the pinned policy version {@code p} reads as unavailable. */
    static final String SOURCE_UNAVAILABLE_READ = "EXISTS (SELECT 1 FROM jsonb_array_elements(s.canonical::jsonb ->"
            + " 'attributes') marker WHERE marker ->> 'code' = 'SOURCE_UNAVAILABLE' AND marker -> 'value' ->> 'code' IS NOT NULL"
            + " AND ((p.max_data_age_bureau_seconds IS NOT NULL AND 'BUREAU' = ANY (string_to_array(marker -> 'value' ->>"
            + " 'code', '_AND_'))) OR (p.max_data_age_financial_data_seconds IS NOT NULL AND 'FINANCIAL_DATA' = ANY"
            + " (string_to_array(marker -> 'value' ->> 'code', '_AND_')))))";

    /**
     * Half 2: a comparison rule of the pinned policy version {@code p} reads an attribute the snapshot {@code s} holds
     * {@code ABSENT}, or a figure its assessment {@code a} could not compute.
     */
    static final String ABSENT_COMPARED = "EXISTS (SELECT 1 FROM credit.credit_policy_rule r WHERE r.policy_version_id = p.id"
            + " AND r.operator NOT IN ('IS_ABSENT', 'IS_PRESENT') AND ("
            + "(r.subject_kind = 'ATTRIBUTE' AND EXISTS (SELECT 1 FROM jsonb_array_elements(s.canonical::jsonb -> 'attributes')"
            + " attribute WHERE attribute ->> 'code' = r.subject AND attribute -> 'value' ->> 'absent' = 'true'))"
            + " OR (r.subject_kind = 'FIGURE' AND r.subject IN ('DISPOSABLE_INCOME', 'AFFORDABLE') AND NOT a.affordability_assessed)"
            + " OR (r.subject_kind = 'FIGURE' AND r.subject IN ('EXPOSURE', 'EXPOSURE_HEADROOM') AND NOT a.exposure_assessed)))";

    /** The snapshot {@code s}, its assessment {@code a} and the pinned policy version {@code p} rest on missing data. */
    static final String RESTS_ON_MISSING_DATA = "(" + SOURCE_UNAVAILABLE_READ + " OR " + ABSENT_COMPARED + ")";

    /** Every SYSTEM approval resting on missing data, each named. */
    static final String SYSTEM_APPROVALS = "SELECT 'decision ' || d.id || ' (request ' || d.decision_request_id || ')'"
            + " FROM credit.credit_decision d JOIN credit.decision_snapshot s ON s.id = d.snapshot_id"
            + " JOIN credit.credit_assessment a ON a.snapshot_id = s.id"
            + " JOIN credit.credit_policy_version p ON p.id = d.policy_version_id"
            + " WHERE d.decided_by_type = 'SYSTEM' AND d.outcome = 'APPROVED' AND " + RESTS_ON_MISSING_DATA
            + " ORDER BY d.id";

    /** Every evaluation approving on missing data, each named - decided yet or not. */
    static final String APPROVING_EVALUATIONS = "SELECT 'evaluation ' || e.id || ' (request ' || e.decision_request_id"
            + " || ')' FROM credit.policy_evaluation e JOIN credit.credit_assessment a ON a.id = e.assessment_id"
            + " JOIN credit.decision_snapshot s ON s.id = a.snapshot_id"
            + " JOIN credit.credit_policy_version p ON p.id = e.policy_version_id"
            + " WHERE e.outcome = 'APPROVE' AND " + RESTS_ON_MISSING_DATA + " ORDER BY e.id";

    /** The census's subjects: evaluations on missing data, whatever their outcome, counted by outcome. */
    static final String SUBJECTS = "SELECT e.outcome || ' ' || count(*) FROM credit.policy_evaluation e"
            + " JOIN credit.credit_assessment a ON a.id = e.assessment_id JOIN credit.decision_snapshot s ON s.id = a.snapshot_id"
            + " JOIN credit.credit_policy_version p ON p.id = e.policy_version_id WHERE " + RESTS_ON_MISSING_DATA
            + " GROUP BY e.outcome ORDER BY e.outcome";

    /** Evaluations whose snapshot satisfies {@code predicate} (a half, or both), counted. */
    static String subjectsWhere(String predicate) {
        return "SELECT count(*) FROM credit.policy_evaluation e JOIN credit.credit_assessment a ON a.id = e.assessment_id"
                + " JOIN credit.decision_snapshot s ON s.id = a.snapshot_id"
                + " JOIN credit.credit_policy_version p ON p.id = e.policy_version_id WHERE " + predicate;
    }

    private MissingDataCensus() {}

    /** The SYSTEM approvals resting on missing data, read on {@code connection} - empty when the invariant holds. */
    static List<String> systemApprovals(Connection connection) throws SQLException {
        return strings(connection, SYSTEM_APPROVALS);
    }

    /** The evaluations approving on missing data, read on {@code connection} - empty when the invariant holds. */
    static List<String> approvingEvaluations(Connection connection) throws SQLException {
        return strings(connection, APPROVING_EVALUATIONS);
    }

    /** The evaluations on missing data by outcome ("REFER 12"), read on {@code connection}. */
    static List<String> subjects(Connection connection) throws SQLException {
        return strings(connection, SUBJECTS);
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement read = connection.createStatement(); ResultSet row = read.executeQuery(sql)) {
            while (row.next()) {
                found.add(row.getString(1));
            }
        }
        return found;
    }
}
