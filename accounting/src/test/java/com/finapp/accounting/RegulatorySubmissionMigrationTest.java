package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code accounting V010}, pinned against its definitions (`P14-TSK-018`; {@code PriorPeriodAdjustmentMigrationTest}'s shape; {@code INV-ACC-13}, {@code INV-ACC-04}, {@code INV-HIST-02}, {@code INV-AUD-04}). A pin proves the text is there; {@code RegulatorySubmissionDatabaseTest} proves what it does (owed, C5).
 */
@DisplayName("accounting V010 (P14-TSK-018)")
class RegulatorySubmissionMigrationTest {

    static String migration() {
        try (InputStream migration = RegulatorySubmissionMigrationTest.class.getClassLoader().getResourceAsStream("db/migration/accounting/V010__regulatory_obligations_and_submissions.sql")) {
            if (migration == null) {
                throw new IllegalStateException("V010 is not on the test classpath");
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String function(String name) {
        String text = migration();
        int start = text.indexOf("CREATE OR REPLACE FUNCTION accounting." + name);
        assertThat(start).as(name).isNotNegative();
        return text.substring(start, text.indexOf("$$;", start));
    }

    @Test
    @DisplayName("the status CHECKs are the enums' own")
    void theStatusListsAreTheEnums() {
        assertThat(migration()).contains("CHECK (status IN (" + quoted(SubmissionStatus.values()) + "))").contains("CHECK (status IN (" + quoted(RegulatoryObligationStatus.values()) + "))");
        assertThat(function("regulatory_obligation_permits_only_machine_edges")).contains("NOT IN (" + quoted(RegulatoryRuleType.values()) + ")");
    }

    private static String quoted(Enum<?>[] values) {
        return Arrays.stream(values).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

    @Test
    @DisplayName("V010 states reference only: no row claims a real jurisdiction's form until a migration says otherwise; and it seeds nothing")
    void nothingIsSeededAndEverythingIsReference() {
        assertThat(migration()).contains("CONSTRAINT regulatory_obligation_is_reference CHECK (standing = 'REFERENCE')");
        assertThat(function("regulatory_submission_permits_only_machine_edges")).contains("stated ->> 'standing' IS DISTINCT FROM 'REFERENCE'");
        assertThat(migration().replaceAll("(?m)^--.*$", "")).doesNotContainPattern("(?i)INSERT\\s+INTO");
    }

    @Test
    @DisplayName("V010 states four eyes on the row and on the history, and on the row that the approver does not send; persons in every slot; the provider's answers the platform's")
    void fourEyesForEveryWriter() {
        assertThat(migration())
                .contains("CONSTRAINT regulatory_submission_four_eyes CHECK (approved_by IS NULL OR approved_by <> prepared_by)")
                .contains("CONSTRAINT regulatory_submission_approver_does_not_send CHECK (submitted_by IS NULL OR submitted_by <> approved_by)")
                .contains("CONSTRAINT regulatory_obligation_four_eyes CHECK (status NOT IN ('ACTIVE', 'RETIRED') OR decided_by <> proposed_by)")
                .contains("(act = 'APPROVED') = (prepared_by IS NOT NULL) AND (prepared_by IS NULL OR prepared_by <> actor)")
                .contains("prepared_by_type IN ('CUSTOMER', 'EMPLOYEE')")
                .contains("(act IN ('ACKNOWLEDGED', 'REJECTED') AND actor_type = 'SYSTEM')");
        assertThat(migration()).contains("validated_by_type IS NULL OR validated_by_type IN ('CUSTOMER', 'EMPLOYEE')").contains("approved_by_type IS NULL OR approved_by_type IN ('CUSTOMER', 'EMPLOYEE')")
                .contains("submitted_by_type IS NULL OR submitted_by_type IN ('CUSTOMER', 'EMPLOYEE')").contains("closed_by_type IS NULL OR closed_by_type IN ('CUSTOMER', 'EMPLOYEE')").contains("decided_by_type IS NULL OR decided_by_type IN ('CUSTOMER', 'EMPLOYEE')");
    }

    @Test
    @DisplayName("a status that round-trips: every edge going forward judges the month, the version and the runs, under FOR SHARE, the period first; none judges by status alone")
    void everyEdgeGoingForwardJudgesTheBooks() {
        String judge = function("regulatory_submission_judge_stands");
        assertThat(judge).contains("FROM accounting.accounting_period p WHERE p.id = p_period FOR SHARE")
                .contains("held_status <> 'CLOSED' OR held_revision <> p_revision")
                .contains("FROM accounting.regulatory_obligation o WHERE o.id = p_obligation FOR SHARE")
                .contains("FROM accounting.report_run r WHERE r.id = source_row.run_id FOR SHARE")
                .contains("run_status <> 'FINAL' OR run_period <> p_period OR run_rev <> p_revision OR run_hash IS DISTINCT FROM source_row.run_sha256")
                .contains("encode(sha256(convert_to(r.content, 'UTF8')), 'hex')")
                .contains("sources = 0");
        assertThat(judge.indexOf("accounting.accounting_period")).as("the period first").isLessThan(judge.indexOf("accounting.regulatory_obligation"));
        String machine = function("regulatory_submission_permits_only_machine_edges");
        for (String edge : new String[] {"OLD.status = 'DRAFT' AND NEW.status = 'DRAFT'", "OLD.status = 'DRAFT' AND NEW.status = 'VALIDATED'", "OLD.status = 'VALIDATED' AND NEW.status = 'APPROVED'", "OLD.status = 'APPROVED' AND NEW.status = 'SUBMITTED'"}) {
            int at = machine.indexOf("IF " + edge);
            assertThat(at).as(edge).isPositive();
            String next = machine.substring(at, machine.indexOf("RETURN NEW;", at));
            assertThat(next).as(edge).contains("regulatory_submission_judge_stands(NEW.id, NEW.period_id, NEW.period_revision, NEW.obligation_id)").contains("regulatory_submission_lines_agree(NEW.id, NEW.content)");
        }
        // The doors out ask nothing of the month.
        int doors = machine.indexOf("OLD.status IN ('DRAFT', 'VALIDATED', 'APPROVED') AND NEW.status = 'WITHDRAWN'");
        assertThat(machine.substring(doors, machine.indexOf("RETURN NEW;", doors))).doesNotContain("judge_stands");
    }

    @Test
    @DisplayName("a draft only from a FINAL run of the month at its revision, the bytes the hash named; born whole at its commit; the rows are what the content states")
    void lineage() {
        String source = function("regulatory_submission_source_is_a_final_run");
        assertThat(source).contains("run_status <> 'FINAL' OR run_basis <> 'FINAL'").contains("run_rev <> parent_rev").contains("run_hash IS DISTINCT FROM NEW.run_sha256").contains("held_status <> 'CLOSED' OR held_revision <> parent_rev");
        assertThat(migration()).contains("CREATE CONSTRAINT TRIGGER regulatory_submission_is_born_whole").contains("DEFERRABLE INITIALLY DEFERRED")
                .contains("CONSTRAINT regulatory_submission_line_source_fk FOREIGN KEY (submission_id, run_id) REFERENCES accounting.regulatory_submission_source (submission_id, run_id)");
        String agree = function("regulatory_submission_lines_agree");
        for (String field : new String[] {"'code'", "'label'", "'currency'", "'minor'", "'scale'", "'runId'", "'runSha256'", "'source'", "'figure'"}) {
            assertThat(agree).as(field).contains("(stated_lines -> (l.ordinal - 1)) ->> " + field + " IS DISTINCT FROM");
        }
        assertThat(agree).contains("held_lines = 0").contains("held_sources = 0");
        assertThat(function("regulatory_submission_source_is_a_final_run")).contains("run_period <> parent_period");
    }

    @Test
    @DisplayName("one live per form and month; one standing correction per original; a plain draft after a sent return refused under the unique's own SQLSTATE; a correction only of a concluded return of its own form and month")
    void oneLiveAndAChain() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX regulatory_submission_one_live ON accounting.regulatory_submission (jurisdiction_code, form_code, period_id)\n    WHERE status IN ('DRAFT', 'VALIDATED', 'APPROVED', 'SUBMITTED');")
                .contains("CREATE UNIQUE INDEX regulatory_submission_one_correction ON accounting.regulatory_submission (corrects_submission_id)\n    WHERE corrects_submission_id IS NOT NULL AND status NOT IN ('REFUSED', 'WITHDRAWN');");
        String machine = function("regulatory_submission_permits_only_machine_edges");
        assertThat(machine).contains("s.status IN ('SUBMITTED', 'ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED')").contains("USING ERRCODE = 'unique_violation'").contains("original_status NOT IN ('ACKNOWLEDGED', 'REJECTED', 'UNDELIVERED')");
        assertThat(function("regulatory_submission_permits_only_machine_edges")).contains("original_form <> NEW.form_code OR original_period <> NEW.period_id");
    }

    @Test
    @DisplayName("V010 states what is frozen: the content, its hash and who drew it; every slot once; ack against rejection one way, and only on a recorded answer; undelivered only on a definite not received; a resend only while its month stands")
    void frozenAndOneWay() {
        String machine = function("regulatory_submission_permits_only_machine_edges");
        assertThat(machine).contains("NEW.content IS DISTINCT FROM OLD.content OR NEW.content_sha256 IS DISTINCT FROM OLD.content_sha256")
                .contains("NEW.period_revision IS DISTINCT FROM OLD.period_revision")
                .contains("a return a person closed is final")
                .contains("OLD.status = 'SUBMITTED' AND NEW.status IN ('ACKNOWLEDGED', 'REJECTED')")
                .contains("a.read_outcome = NEW.status")
                .contains("OLD.last_answer IS DISTINCT FROM 'NOT_RECEIVED'")
                .contains("NEW.resends > OLD.resends AND NEW.last_answer IS DISTINCT FROM 'RESENDING'")
                .contains("a resend goes only while the month stands at the revision the return read")
                .contains("held_revision IS DISTINCT FROM NEW.period_revision")
                .contains("stated ->> 'period' IS DISTINCT FROM to_char(held_month, 'YYYY-MM')");
        // Nothing leaves ACKNOWLEDGED or REJECTED: no edge in the function names them as OLD.
        assertThat(machine).doesNotContain("OLD.status = 'ACKNOWLEDGED'").doesNotContain("OLD.status = 'REJECTED'").doesNotContain("OLD.status IN ('ACKNOWLEDGED'");
        assertThat(machine).contains("NEW.prepared_by IS DISTINCT FROM OLD.prepared_by").contains("a validation is recorded once").contains("an attestation is recorded once").contains("a sending is recorded once");
    }

    @Test
    @DisplayName("the grants: never the content or its hash, the configuration or its hash, or who drew it in an UPDATE; no DELETE; history, lineage and answers INSERT-only; hashes recomputed at birth; content as text")
    void theGrantsAndTheHashes() {
        String text = migration();
        String submission = text.substring(text.indexOf("GRANT UPDATE (status, validation_outcome"), text.indexOf("ON accounting.regulatory_submission TO finapp_app", text.indexOf("GRANT UPDATE (status, validation_outcome")));
        assertThat(submission).doesNotContainPattern("[ (]content(_sha256)?[,)]").doesNotContain("prepared_by").doesNotContainPattern("[ (]period_revision[,)]").doesNotContain("obligation_id").doesNotContain("corrects");
        assertThat(text).contains("GRANT UPDATE (status, decided_by, decided_by_type, decided_at, decision_reason, retired_at) ON accounting.regulatory_obligation TO finapp_app;");
        assertThat(text).contains("GRANT SELECT, INSERT ON accounting.regulatory_submission_source TO finapp_app;").contains("GRANT SELECT, INSERT ON accounting.regulatory_submission_line TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON accounting.regulatory_submission_event TO finapp_app;").contains("GRANT SELECT, INSERT ON accounting.regulatory_provider_answer TO finapp_app;");
        assertThat(text.replaceAll("(?m)^--.*$", "")).doesNotContainPattern("GRANT[^;]*DELETE").doesNotContain("jsonb NOT NULL").doesNotContainPattern("(?m)^    content +jsonb");
        assertThat(text).containsPattern("(?m)^    content +text +NOT NULL").containsPattern("(?m)^    configuration +text +NOT NULL");
        assertThat(function("regulatory_submission_permits_only_machine_edges")).contains("NEW.content_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.content, 'UTF8')), 'hex')");
        assertThat(function("regulatory_obligation_permits_only_machine_edges")).contains("NEW.configuration_sha256 IS DISTINCT FROM encode(sha256(convert_to(NEW.configuration, 'UTF8')), 'hex')");
    }

    @Test
    @DisplayName("the provider's answer: bytes, bounded, the rule held by CHECK; append-only; what is read off it held to shapes")
    void theProvidersAnswer() {
        assertThat(migration()).containsPattern("(?m)^    raw +bytea +NOT NULL")
                .contains("octet_length(raw) <= " + RegulatoryAnswerReading.MAX_RAW_BYTES)
                .contains("truncated = (received_bytes > octet_length(raw)) AND (NOT truncated OR anomaly IS NOT DISTINCT FROM 'OVERSIZED')")
                .contains("truncated OR raw_sha256 = encode(sha256(raw), 'hex')")
                .contains("anomaly IN ('OVERSIZED', 'MALFORMED', 'CONTRADICTORY') AND read_outcome = 'UNKNOWN'")
                .contains("flag IS NULL OR flag IN ('LATE', 'CONTRARY')");
        assertThat(function("regulatory_provider_answer_is_append_only")).contains("TG_OP <> 'INSERT'");
        assertThat(migration()).contains("provider_reference ~ '^[A-Za-z0-9._:-]{1,128}$'").contains("rejection_code ~ '^[A-Z][A-Z0-9_]{0,63}$'");
    }

    @Test
    @DisplayName("the constants this test names, which a CHECK or a trigger repeats, are the code's")
    void theBoundsAreTheCodes() {
        assertThat(function("regulatory_submission_permits_only_machine_edges")).contains("octet_length(NEW.content) > " + RegulatoryForm.MAX_CONTENT_BYTES);
        assertThat(function("regulatory_obligation_permits_only_machine_edges")).contains("octet_length(NEW.configuration) > " + RegulatoryObligationConfiguration.MAX_BYTES);
        assertThat(migration()).contains("char_length(figure_path) BETWEEN 6 AND " + FigurePath.MAX_LENGTH).contains("'^-?[0-9]{1,40}$'");
    }

    @Test
    @DisplayName("nothing names another schema; nothing is deleted or truncated")
    void theSchemaFloor() {
        String code = migration().replaceAll("(?m)^--.*$", "");
        assertThat(code).doesNotContainPattern("(?i)\\b(ledger|platform|identity|risk|reconciliation)\\.[a-z_]+");
        for (String table : new String[] {"regulatory_obligation", "regulatory_submission", "regulatory_submission_source", "regulatory_submission_line", "regulatory_submission_event", "regulatory_provider_answer"}) {
            assertThat(code).as(table).contains("CREATE TRIGGER " + table + "_is_never_truncated");
        }
        java.util.regex.Matcher named = java.util.regex.Pattern.compile("\\b(?:(?<!DISTINCT )FROM|JOIN|INTO|UPDATE|TABLE|REFERENCES|ON|FUNCTION)\\s+([a-z_]+)\\.[a-z_]+").matcher(code);
        int references = 0;
        while (named.find()) {
            references++;
            assertThat(named.group(1)).as(named.group()).isEqualTo("accounting");
        }
        assertThat(references).as("the census: it read the statements").isGreaterThan(20);
        assertThat(code).doesNotContainPattern("(?i)\\bDELETE\\s+FROM\\b").contains("is never deleted").contains("append-only");
    }
}
