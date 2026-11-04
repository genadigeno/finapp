package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regulatory framework's statements, pinned (`P14-TSK-018`; {@code PriorPeriodAdjustmentStatementsTest}'s shape): the arbiters are these and {@code accounting V010} beneath them, and {@code RegulatorySubmissionDatabaseTest} (owed, C5) proves them against PostgreSQL. A pin proves the text; it keeps the conditions from leaving
 * unnoticed. Every column a statement names is held against the migration, by name, because the migration runs no earlier than the phase exit.
 */
@DisplayName("the regulatory framework's statements (P14-TSK-018)")
class RegulatorySubmissionStatementsTest {

    @Test
    @DisplayName("the birth infers one_live, naming its predicate; each edge is conditional on where it may leave from; the database stamps; no statement names content in a SET")
    void theEdgesAreConditional() {
        assertThat(JdbcRegulatorySubmissionStore.INSERT_SQL).contains("'DRAFT'").endsWith("ON CONFLICT (jurisdiction_code, form_code, period_id) WHERE status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED') DO NOTHING");
        assertThat(JdbcRegulatorySubmissionStore.VALIDATE_SQL).contains("validated_at = clock_timestamp()").endsWith("WHERE id = ? AND status = 'DRAFT' AND validation_outcome IS NULL");
        assertThat(JdbcRegulatorySubmissionStore.APPROVE_SQL).contains("approved_at = clock_timestamp()").contains("approved_content_sha256 = ?, approved_period_revision = ?").endsWith("WHERE id = ? AND status = 'VALIDATED'");
        assertThat(JdbcRegulatorySubmissionStore.REFUSE_SQL).contains("SET status = 'REFUSED'").endsWith("WHERE id = ? AND status IN ('VALIDATED', 'APPROVED')");
        assertThat(JdbcRegulatorySubmissionStore.WITHDRAW_SQL).contains("SET status = 'WITHDRAWN'").endsWith("WHERE id = ? AND status IN ('DRAFT', 'VALIDATED', 'APPROVED')");
        assertThat(JdbcRegulatorySubmissionStore.SUBMIT_SQL).contains("SET status = 'SUBMITTED'").contains("answer_horizon_at = clock_timestamp() + ? * interval '1 millisecond'").endsWith("WHERE id = ? AND status = 'APPROVED'");
        assertThat(JdbcRegulatorySubmissionStore.ACKNOWLEDGE_SQL).contains("provider_reference = COALESCE(provider_reference, ?)").endsWith("WHERE id = ? AND status = 'SUBMITTED'");
        assertThat(JdbcRegulatorySubmissionStore.REJECT_SQL).contains("rejection_code = ?").endsWith("WHERE id = ? AND status = 'SUBMITTED'");
        assertThat(JdbcRegulatorySubmissionStore.UNDELIVER_SQL).endsWith("WHERE s.id = ? AND s.status = 'SUBMITTED' AND s.last_answer = 'NOT_RECEIVED' AND (s.resends >= ? OR s.answer_horizon_at <= clock_timestamp()"
                + " OR s.period_revision <> (SELECT p.revision FROM accounting.accounting_period p WHERE p.id = s.period_id))");
        for (String edge : List.of(JdbcRegulatorySubmissionStore.VALIDATE_SQL, JdbcRegulatorySubmissionStore.APPROVE_SQL, JdbcRegulatorySubmissionStore.REFUSE_SQL, JdbcRegulatorySubmissionStore.WITHDRAW_SQL, JdbcRegulatorySubmissionStore.SUBMIT_SQL,
                JdbcRegulatorySubmissionStore.ACKNOWLEDGE_SQL, JdbcRegulatorySubmissionStore.REJECT_SQL, JdbcRegulatorySubmissionStore.UNDELIVER_SQL, JdbcRegulatorySubmissionStore.CLAIM_SQL, JdbcRegulatorySubmissionStore.CLAIM_NEXT_SQL,
                JdbcRegulatorySubmissionStore.PERMIT_RESEND_SQL, JdbcRegulatorySubmissionStore.AWAIT_SQL)) {
            assertThat(edge).doesNotContain("content =");
        }
        assertThat(JdbcRegulatorySubmissionStore.REFUSE_SQL).contains("closed_at = clock_timestamp()");
        assertThat(JdbcRegulatorySubmissionStore.WITHDRAW_SQL).contains("closed_at = clock_timestamp()");
        assertThat(JdbcRegulatorySubmissionStore.UNDELIVER_SQL).contains("closed_at = clock_timestamp()");
        assertThat(JdbcRegulatorySubmissionStore.SUBMIT_SQL).contains("submitted_at = clock_timestamp()");
        assertThat(JdbcRegulatorySubmissionStore.ACKNOWLEDGE_SQL).contains("concluded_at = clock_timestamp()");
        assertThat(JdbcRegulatorySubmissionStore.REJECT_SQL).contains("concluded_at = clock_timestamp()");
    }

    @Test
    @DisplayName("the claim is one statement: SKIP LOCKED on a permit that has come, one attempt counted, the permit a lease ahead on the database's clock; a resend counted against its bound, the attempt held and the month's revision; an unconcluded answer conditional on the attempt, its wait backing off on the database's clock")
    void theDriveIsConditional() {
        assertThat(JdbcRegulatorySubmissionStore.CLAIM_SQL).contains("attempts = attempts + 1").contains("next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'")
                .contains("next_inquiry_at <= clock_timestamp() FOR UPDATE SKIP LOCKED").contains("status = 'SUBMITTED'");
        assertThat(JdbcRegulatorySubmissionStore.CLAIM_NEXT_SQL).contains("ORDER BY next_inquiry_at, id LIMIT 1 FOR UPDATE SKIP LOCKED").contains("status = 'SUBMITTED'");
        assertThat(JdbcRegulatorySubmissionStore.PERMIT_RESEND_SQL).contains("resends = s.resends + 1, last_answer = 'RESENDING'")
                .endsWith("s.attempts = ? AND s.last_answer = 'NOT_RECEIVED' AND s.resends < ? AND s.period_revision = (SELECT p.revision FROM accounting.accounting_period p WHERE p.id = s.period_id)");
        assertThat(JdbcRegulatorySubmissionStore.AWAIT_SQL).endsWith("WHERE id = ? AND status = 'SUBMITTED' AND attempts = ?")
                .contains("LEAST(? * interval '1 millisecond', GREATEST(? * interval '1 millisecond', clock_timestamp() - answer_horizon_at))");
        assertThat(JdbcRegulatorySubmissionStore.CLAIM_NEXT_SQL).contains("attempts = attempts + 1").contains("next_inquiry_at = clock_timestamp() + ? * interval '1 millisecond'").contains("next_inquiry_at <= clock_timestamp() ORDER BY");
    }

    @Test
    @DisplayName("the locks: one row FOR UPDATE OF its own alias, a period's and obligation's never by a join; a form's versions in id order; the filters in the statement, before the limit")
    void theLocks() {
        assertThat(JdbcRegulatorySubmissionStore.LOCK_SQL).endsWith("WHERE s.id = ? FOR UPDATE OF s");
        assertThat(JdbcRegulatorySubmissionStore.FIND_SQL).doesNotContain("FOR ");
        assertThat(JdbcRegulatorySubmissionStore.AWAITING_SQL).endsWith("WHERE s.status = 'VALIDATED' AND s.prepared_by <> ? ORDER BY s.validated_at, s.id LIMIT ?");
        assertThat(JdbcRegulatoryObligationStore.AWAITING_SQL).endsWith("WHERE status = 'PROPOSED' AND proposed_by <> ? ORDER BY proposed_at, id LIMIT ?");
        assertThat(JdbcRegulatoryObligationStore.LOCK_SHARED_SQL).endsWith("WHERE id = ? FOR SHARE");
        assertThat(JdbcRegulatoryObligationStore.LOCK_FORM_SQL).endsWith("WHERE jurisdiction_code = ? AND form_code = ? ORDER BY id FOR UPDATE");
        assertThat(JdbcRegulatorySubmissionStore.STANDING_SQL).endsWith("ORDER BY s.prepared_at, s.id LIMIT ?").contains("f.flag = 'CONTRARY'").contains(JdbcRegulatorySubmissionStore.OVERTAKEN);
        assertThat(JdbcRegulatorySubmissionStore.STANDING_AFTER_SQL).endsWith("AND (s.prepared_at, s.id) > (SELECT l.prepared_at, l.id FROM accounting.regulatory_submission l WHERE l.id = ?) ORDER BY s.prepared_at, s.id LIMIT ?");
        assertThat(JdbcRegulatorySubmissionStore.OVERTAKEN).contains("p.revision <> s.period_revision").contains("n.status = 'ACKNOWLEDGED' AND n.period_revision = p.revision");
        assertThat(JdbcRegulatorySubmissionStore.ATTENTION_SQL).contains(JdbcRegulatorySubmissionStore.OVERTAKEN).doesNotContain("CONTRARY");
        assertThat(JdbcRegulatorySubmissionStore.ANSWERS_SQL).contains("ORDER BY received_at DESC, id DESC LIMIT ?").endsWith("newest ORDER BY received_at, id");
    }

    @Test
    @DisplayName("the columns the stores use, named here by hand, exist in V010 under their own names")
    void theColumnsAreV010s() {
        String migration = RegulatorySubmissionMigrationTest.migration();
        for (String column : new String[] {"jurisdiction_code", "form_code", "form_version", "period_id", "period_revision", "corrects_submission_id", "content", "content_sha256", "status", "prepared_by", "prepared_by_type", "prepared_at",
                "preparation_reason", "validation_outcome", "validation_passed", "validated_by", "validated_by_type", "validated_at", "approved_by", "approved_by_type", "approved_at", "approval_reason", "approved_content_sha256",
                "approved_period_revision", "submitted_by", "submitted_by_type", "submitted_at", "submission_reason", "closed_by", "closed_by_type", "closed_at", "closing_reason", "attempts", "resends", "next_inquiry_at", "answer_horizon_at",
                "last_answer", "provider_reference", "rejection_code", "concluded_at", "correlation_id", "obligation_id"}) {
            assertThat(migration).as(column).containsPattern("(?m)^    " + column + " +[a-z]");
        }
        for (String column : new String[] {"period_type", "adapter_name", "standing", "configuration", "configuration_sha256", "proposed_by", "proposed_by_type", "proposed_at", "proposal_reason", "decided_by", "decided_by_type", "decided_at",
                "decision_reason", "retired_at"}) {
            assertThat(migration).as(column).containsPattern("(?m)^    " + column + " +[a-z]");
            assertThat(JdbcRegulatoryObligationStore.INSERT_SQL + JdbcRegulatoryObligationStore.FIND_SQL + JdbcRegulatoryObligationStore.ACTIVATE_SQL + JdbcRegulatoryObligationStore.RETIRE_SQL).as(column).contains(column);
        }
        for (String column : new String[] {"submission_id", "run_id", "run_sha256", "definition", "definition_version", "ordinal", "line_code", "label", "currency", "minor_units", "scale", "source_definition", "figure_path", "act", "from_status",
                "to_status", "actor", "actor_type", "reason", "occurred_at", "attempt", "call_kind", "stated_outcome", "read_outcome", "anomaly", "flag", "raw", "raw_sha256", "received_bytes", "truncated", "received_at"}) {
            assertThat(migration).as(column).containsPattern("(?m)^    " + column + " +[a-z]");
        }
        assertThat(JdbcRegulatorySubmissionStore.INSERT_LINE_SQL).contains("minor_units").contains("figure_path").contains("source_definition");
        assertThat(JdbcRegulatorySubmissionStore.INSERT_ANSWER_SQL).contains("raw_sha256").contains("received_bytes").contains("truncated");
        assertThat(JdbcRegulatorySubmissionStore.INSERT_EVENT_SQL).contains("prepared_by").contains("occurred_at");
    }

    @Test
    @DisplayName("these statements hold the count of placeholders their stores were written to bind")
    void thePlaceholdersAreCounted() {
        assertThat(placeholders(JdbcRegulatorySubmissionStore.INSERT_SQL)).isEqualTo(14);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.INSERT_SOURCE_SQL)).isEqualTo(5);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.INSERT_LINE_SQL)).isEqualTo(11);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.INSERT_ANSWER_SQL)).isEqualTo(15);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.INSERT_EVENT_SQL)).isEqualTo(10);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.VALIDATE_SQL)).isEqualTo(6);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.APPROVE_SQL)).isEqualTo(6);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.REFUSE_SQL)).isEqualTo(4);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.WITHDRAW_SQL)).isEqualTo(4);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.SUBMIT_SQL)).isEqualTo(5);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.ACKNOWLEDGE_SQL)).isEqualTo(2);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.REJECT_SQL)).isEqualTo(3);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.UNDELIVER_SQL)).isEqualTo(5);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.CLAIM_SQL)).isEqualTo(2);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.CLAIM_NEXT_SQL)).isEqualTo(1);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.PERMIT_RESEND_SQL)).isEqualTo(3);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.AWAIT_SQL)).isEqualTo(6);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.ANSWERS_SQL)).isEqualTo(2);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.ANSWER_COUNT_SQL)).isEqualTo(1);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.STANDING_SQL)).isEqualTo(1);
        assertThat(placeholders(JdbcRegulatorySubmissionStore.STANDING_AFTER_SQL)).isEqualTo(2);
        assertThat(placeholders(JdbcRegulatoryObligationStore.INSERT_SQL)).isEqualTo(11);
        assertThat(placeholders(JdbcRegulatoryObligationStore.ACTIVATE_SQL)).isEqualTo(4);
    }

    private static long placeholders(String statement) {
        return statement.chars().filter(c -> c == '?').count();
    }
}
