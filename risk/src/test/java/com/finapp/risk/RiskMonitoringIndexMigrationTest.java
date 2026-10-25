package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V015}'s text held to the code (`P13-TSK-020`; the migration-test idiom, hermetic - the live index and the
 * statements over it are {@code MonitoringRulesDatabaseTest}'s, written and owed under C5): one partial index on the
 * columns, the predicate and the order the counterparty side of {@link JdbcMonitoredTransactionStore}'s reads use;
 * nothing else; every name new across V001 to V014; no other schema named; V004 left as applied.
 */
@DisplayName("risk V015 adds the monitoring rules' counterparty index and nothing else (P13-TSK-020)")
class RiskMonitoringIndexMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V015__monitored_transaction_by_counterparty.sql";

    private static final List<String> EARLIER = List.of(
            "db/migration/risk/V001__initialise_risk_schema.sql",
            "db/migration/risk/V002__risk_policy_versions_as_data.sql",
            "db/migration/risk/V003__risk_signal.sql",
            "db/migration/risk/V004__monitored_transactions_and_the_watermark.sql",
            "db/migration/risk/V005__velocity_windows.sql",
            "db/migration/risk/V006__the_customer_risk_profile.sql",
            "db/migration/risk/V007__the_sealed_assessment.sql",
            "db/migration/risk/V008__the_held_card_payment.sql",
            "db/migration/risk/V009__the_decision_override_machine.sql",
            "db/migration/risk/V010__the_born_once_alert.sql",
            "db/migration/risk/V011__payment_merchant_and_bnpl_abuse.sql",
            "db/migration/risk/V012__the_case.sql",
            "db/migration/risk/V013__downstream_risk_actions.sql",
            "db/migration/risk/V014__the_queues_and_the_suppression_persons.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the index: CUSTOMER counterparties by (counterparty_ref, value_date) - the counterparty side's"
            + " predicate and range, beside V004's subject index")
    void theCounterpartyIndex() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).isEqualTo("CREATE INDEX monitored_transaction_by_counterparty ON risk.monitored_transaction"
                + " (counterparty_ref, value_date) WHERE counterparty_kind = 'CUSTOMER';");
        String store = JdbcMonitoredTransactionStore.MOVEMENTS;
        assertThat(store).contains("WHERE counterparty_kind = 'CUSTOMER' AND counterparty_ref = ANY (?) AND value_date >="
                + " ? AND value_date < ?");
        assertThat(store).contains("WHERE subject_kind = 'ACCOUNT' AND subject_ref = ANY (?) AND value_date >= ? AND"
                + " value_date < ?");
        assertThat(JdbcMonitoredTransactionStore.LAST_MOVEMENT)
                .contains("counterparty_kind = 'CUSTOMER' AND counterparty_ref = account.ref AND value_date < ?")
                .contains("subject_kind = 'ACCOUNT' AND subject_ref = account.ref AND value_date < ?");
        assertThat(collapsed(statements("db/migration/risk/V004__monitored_transactions_and_the_watermark.sql")))
                .as("V004 is never edited: its subject index stands as -019 built it for this task")
                .contains("CREATE INDEX monitored_transaction_by_subject ON risk.monitored_transaction (subject_kind,"
                        + " subject_ref, value_date) WHERE subject_kind IS NOT NULL;");
    }

    @Test
    @DisplayName("nothing else: no table, column, constraint, function, trigger or grant; the name new across V001 to"
            + " V014; no other schema named")
    void nothingElseAndNoOtherSchema() {
        String sql = statements(RESOURCE);
        assertThat(sql).doesNotContain("CREATE TABLE").doesNotContain("ADD COLUMN").doesNotContain("CONSTRAINT")
                .doesNotContain("CREATE FUNCTION").doesNotContain("CREATE TRIGGER").doesNotContain("GRANT")
                .doesNotContain("REVOKE").doesNotContain("DELETE").doesNotContain("UPDATE ");
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        assertThat(names(sql)).containsExactly("monitored_transaction_by_counterparty");
        assertThat(earlier).doesNotContain("monitored_transaction_by_counterparty");
        String unquoted = sql.replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
    }

    // -----------------------------------------------------------------

    private static List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        for (Pattern pattern : List.of(
                Pattern.compile("CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)"))) {
            Matcher matched = pattern.matcher(sql);
            while (matched.find()) {
                names.add(matched.group(1));
            }
        }
        return names.stream().distinct().collect(Collectors.toList());
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")").strip();
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = RiskMonitoringIndexMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
