package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V012` reconciled against the vocabularies that generate it (`P8-TSK-022`, ADR-0068 §§8-9):
 * the rule set's machine and its four-eyes rule, the members admitted only with their proposal,
 * the decision snapshot's replay inputs, the replay record, and the grants - the version moves
 * only by its machine, and everything new is insert-only. Behaviour at every rank is proven
 * against the real schema by the database suites.
 */
@DisplayName("reconciliation V012 reconciliation (P8-TSK-022)")
class ReconciliationV012MigrationTest {

    private static final String V012 =
            "db/migration/reconciliation/V012__operating_without_editing_history.sql";

    @Test
    @DisplayName("the rule set's status CHECK is RuleSetStatus's whole list, activation is four-eyed"
            + " at the CHECK, and one proposal stands per source")
    void theRuleSetHasItsMachine() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT rule_set_status CHECK (status IN ("
                                + RuleSetStatus.sqlValueList() + "))"))
                .contains("decided_by <> proposed_by")
                .contains("CREATE UNIQUE INDEX rule_set_one_proposed ON reconciliation.rule_set"
                        + " (source_id) WHERE status = 'PROPOSED';")
                .contains("(OLD.status = 'PROPOSED' AND NEW.status IN ('ACTIVE', 'REJECTED'))")
                .contains("(OLD.status = 'ACTIVE' AND NEW.status = 'RETIRED')")
                .contains("UNIQUE NULLS NOT DISTINCT (rule_set_id, comparison, currency)");
        for (RuleSetStatus status : RuleSetStatus.values()) {
            for (RuleSetStatus target : status.permittedTransitions()) {
                assertThat(sql)
                        .as("the trigger names the edge %s -> %s", status, target)
                        .contains("'" + target.name() + "'");
            }
        }
    }

    @Test
    @DisplayName("every member table admits a row only with a PROPOSED parent born in the same"
            + " transaction")
    void theMembersJoinOnlyTheirProposal() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("p.xmin::text::bigint = pg_current_xact_id()::text::bigint % 4294967296");
        for (String table :
                java.util.List.of(
                        "rule_set_lag", "rule", "tolerance", "provider_fee_schedule",
                        "severity_threshold")) {
            assertThat(sql)
                    .as(table)
                    .contains("BEFORE INSERT ON reconciliation." + table + " FOR EACH ROW"
                            + " EXECUTE FUNCTION reconciliation.rule_set_member_joins_its_proposal();");
        }
    }

    @Test
    @DisplayName("the decision's replay inputs: the verdict and judged-status CHECKs are the enums'"
            + " lists, and the insert trigger names the matching engine's verdicts")
    void theSnapshotIsComplete() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT match_decision_verdict CHECK (verdict IS NULL OR verdict IN ("
                                + DecisionVerdict.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT match_decision_judged_status CHECK (judged_status IS NULL OR"
                                + " judged_status IN (" + JudgedStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "IF NEW.verdict IN ("
                                + DecisionVerdict.sqlValueList(DecisionVerdict.matchEngine())
                                + ")"))
                .contains("BEFORE INSERT ON reconciliation.match_decision");
    }

    @Test
    @DisplayName("the replay record and every new table are insert-only; the rule set moves only"
            + " its status and decision")
    void theGrantsAreInsertOnly() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("GRANT UPDATE (status, decided_by, decided_at) ON reconciliation.rule_set"
                        + " TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON reconciliation.rule_set_event TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON reconciliation.match_parked_original TO"
                        + " finapp_app;")
                .contains("GRANT SELECT, INSERT ON reconciliation.run_replay TO finapp_app;")
                .contains("CONSTRAINT run_replay_verdict CHECK (verdict IN ('IDENTICAL',"
                        + " 'DIVERGED'))")
                .contains("CREATE UNIQUE INDEX break_one_open_per_decision ON reconciliation.break"
                        + " (type, decision_id) WHERE status <> 'RESOLVED';")
                .doesNotContain("GRANT DELETE");
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration() {
        try (InputStream in =
                ReconciliationV012MigrationTest.class.getClassLoader().getResourceAsStream(V012)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
