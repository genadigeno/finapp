package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V012}'s text held to the code (`P13-TSK-016`; the migration-test idiom, hermetic - the live tables are
 * {@code CaseDatabaseTest}'s and the live grants {@code RiskMigrationTest}'s, written and owed under C5): the
 * vocabularies are the enums', the one-open partial unique is the plan's and the store's birth infers it, four eyes is
 * the {@code CHECK}'s alone, the machine's every rule is present, the grants are column-scoped with no {@code DELETE},
 * every name is new across V001 to V011 and no other schema is named.
 */
@DisplayName("risk V012 holds the case as the code declares it (P13-TSK-016)")
class RiskCaseMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V012__the_case.sql";

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
            "db/migration/risk/V011__payment_merchant_and_bnpl_abuse.sql");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the vocabularies are the enums': kind, subject kind, status, level, queue, disposition, the action's and"
            + " the history's kinds, the actor types")
    void theListsAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("CHECK (kind IN (" + list(CaseKind.values()) + "))")
                .contains("CHECK (subject_kind IN (" + list(SubjectKind.values()) + "))")
                .contains("CHECK (status IN (" + list(CaseStatus.values()) + "))")
                .contains("CHECK (level IN (" + list(CaseLevel.values()) + "))")
                .contains("CHECK (queue IN (" + list(CaseQueue.values()) + "))")
                .contains("disposition IN (" + list(Disposition.values()) + ")")
                .contains("CHECK (kind IN (" + list(CaseActionKind.values()) + "))")
                .contains("CHECK (kind IN (" + list(CaseEventKind.values()) + "))")
                .contains("actor_type IN (" + list(com.finapp.platform.security.ActorType.values()) + ")");
        String escalationQueues = Arrays.stream(CaseQueue.values()).filter(CaseQueue::escalationTarget)
                .map(queue -> "'" + queue.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("queue IN (" + escalationQueues + ")");
        String texted = Arrays.stream(CaseActionKind.values()).filter(CaseActionKind::texted)
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("(kind NOT IN (" + texted + ") OR text IS NOT NULL)");
        String persons = Arrays.stream(CaseEventKind.values()).filter(CaseEventKind::persons)
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("(kind IN (" + persons + ")) = (action_id IS NOT NULL)");
    }

    @Test
    @DisplayName("INV-RSK-06: the one-open partial unique over the live states, and the store's birth infers it - ON"
            + " CONFLICT (subject_ref, kind) WHERE status <> 'DISPOSED' DO NOTHING; the lock reads its own predicate")
    void theOneOpenUnique() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CREATE UNIQUE INDEX risk_case_one_open ON risk.risk_case (subject_ref, kind) WHERE"
                + " status <> 'DISPOSED';");
        assertThat(JdbcRiskCaseStore.INSERT).startsWith("INSERT INTO risk.risk_case (")
                .contains("ON CONFLICT (subject_ref, kind) WHERE status <> 'DISPOSED' DO NOTHING RETURNING ")
                .contains("'OPEN', 0, 'L1', 'FIRST_LINE'");
        assertThat(JdbcRiskCaseStore.LOCK_OPEN)
                .contains("WHERE subject_ref = ? AND kind = ? AND status <> 'DISPOSED' FOR UPDATE");
        assertThat(JdbcRiskCaseStore.ADVANCE).contains("revision = revision + 1").endsWith(
                "WHERE id = ? AND revision = ? RETURNING " + JdbcRiskCaseStore.COLUMNS);
    }

    @Test
    @DisplayName("INV-RSK-07: four eyes is the CHECK's alone - the trigger never compares the two persons - and both are"
            + " persons (CUSTOMER-typed sessions or EMPLOYEEs); PROHIBITED only of a confirming party's case")
    void fourEyesIsTheChecksAlone() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CONSTRAINT risk_case_disposition_four_eyes CHECK (status <> 'DISPOSED' OR"
                + " (disposition IN ('CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION') AND NOT disposition_prohibits) OR"
                + " disposition_approved_by <> disposition_proposed_by)");
        assertThat(sql).contains("(disposition_proposed_by_type IS NULL OR disposition_proposed_by_type IN ('CUSTOMER',"
                        + " 'EMPLOYEE'))")
                .contains("(disposition_approved_by_type IS NULL OR disposition_approved_by_type IN ('CUSTOMER',"
                        + " 'EMPLOYEE'))")
                .contains("CONSTRAINT risk_case_action_by_a_person CHECK (actor_type IN ('CUSTOMER', 'EMPLOYEE'))")
                .contains("disposition_prohibits IS NOT TRUE OR (disposition IN ('CONFIRMED_FRAUD',"
                        + " 'CONFIRMED_SUSPICIOUS') AND subject_kind = 'PARTY')");
        String trigger = sql.substring(sql.indexOf("CREATE FUNCTION risk.risk_case_permits_only_machine_edges()"),
                sql.indexOf("CREATE TRIGGER risk_case_permits_only_machine_edges"));
        assertThat(trigger).as("four eyes is provable by the CHECK alone")
                .doesNotContain("disposition_approved_by <>").doesNotContain("disposition_approved_by =")
                .doesNotContain("disposition_approved_by IS DISTINCT FROM NEW.disposition_proposed_by");
        // The domain's list of the two persons' dispositions is the CHECK's complement.
        String onePerson = Arrays.stream(Disposition.values())
                .filter(disposition -> !DispositionRules.needsSecondPerson(disposition, false))
                .map(disposition -> "'" + disposition.name() + "'").collect(Collectors.joining(", "));
        assertThat(onePerson).isEqualTo("'CLOSED_FALSE_POSITIVE', 'CLOSED_NO_ACTION'");
    }

    @Test
    @DisplayName("the machine's rules are present: born OPEN and stamped, the facts frozen, one revision a step, the"
            + " edges, nothing out of DISPOSED, every revision its history row, the actions and history append-only")
    void theMachinesRulesArePresent() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("RAISE EXCEPTION 'a risk case is never deleted or truncated (P13-TSK-016, INV-RSK-06)';")
                .contains("NEW.opened_at := clock_timestamp();")
                .contains("NEW.sla_deadline := NEW.opened_at + make_interval(hours => NEW.sla_hours);")
                .contains("RAISE EXCEPTION 'a risk case''s facts never change (P13-TSK-016)'")
                .contains("RAISE EXCEPTION 'a disposed risk case never moves and never re-opens (P13-TSK-016, OQ-6)'")
                .contains("IF NEW.revision <> OLD.revision + 1 THEN")
                .contains("RAISE EXCEPTION 'a risk case moves only along its machine (P13-TSK-016)'")
                .contains("NEW.disposed_at := clock_timestamp();")
                .contains("NEW.disposition_proposed_at := clock_timestamp();")
                .contains("CREATE CONSTRAINT TRIGGER risk_case_has_its_history AFTER INSERT OR UPDATE ON risk.risk_case"
                        + " DEFERRABLE INITIALLY DEFERRED")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_case_action")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_case_event")
                .contains("BEFORE TRUNCATE ON risk.risk_case")
                .contains("BEFORE TRUNCATE ON risk.risk_case_action")
                .contains("BEFORE TRUNCATE ON risk.risk_case_event")
                .contains("ALTER TABLE risk.risk_alert ADD CONSTRAINT risk_alert_attached_case_fk FOREIGN KEY"
                        + " (attached_case_id) REFERENCES risk.risk_case (id);")
                .contains("ALTER TABLE risk.customer_risk_profile_version ADD CONSTRAINT"
                        + " customer_risk_profile_version_disposition_fk FOREIGN KEY (disposition_id) REFERENCES"
                        + " risk.risk_case_action (id);")
                .contains("CREATE UNIQUE INDEX customer_risk_profile_version_one_per_disposition ON"
                        + " risk.customer_risk_profile_version (disposition_id) WHERE disposition_id IS NOT NULL;");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT on the three, a column-scoped UPDATE of the case's machine columns -"
            + " never a fact or a stamp - and no DELETE, no ALL, no default privilege")
    void theGrantIsColumnScoped() {
        String sql = statements(RESOURCE);
        List<String> grants = Arrays.stream(sql.split(";")).map(s -> s.strip().replaceAll("\\s+", " "))
                .filter(s -> s.startsWith("GRANT") || s.startsWith("REVOKE")).toList();
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON risk.risk_case TO finapp_app",
                "GRANT UPDATE (status, revision, level, queue, taken_by, taken_by_type, disposition, disposition_id,"
                        + " disposition_prohibits, disposition_proposed_by, disposition_proposed_by_type,"
                        + " disposition_approved_by, disposition_approved_by_type) ON risk.risk_case TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_case_action TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_case_event TO finapp_app");
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE")
                || grant.contains("opened_at") || grant.contains("disposed_at") || grant.contains("sla_deadline"));
        assertThat(sql).doesNotContain("GRANT ALL").doesNotContain("ALTER DEFAULT PRIVILEGES")
                .doesNotContain("INSERT INTO risk.");
    }

    @Test
    @DisplayName("every constraint, index and trigger name V012 creates is new across V001 to V011 and its own, the"
            + " store's columns are V012's, and no other schema is named")
    void namesAreUniqueAndNoOtherSchema() {
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v012 = names(statements(RESOURCE));
        assertThat(v012).as("the guard is not vacuous").hasSizeGreaterThanOrEqualTo(40);
        assertThat(v012).doesNotHaveDuplicates().noneMatch(earlier::contains)
                .allMatch(name -> name.startsWith("risk_case_") || name.equals("risk_alert_attached_case_fk")
                        || name.startsWith("customer_risk_profile_version_"));
        String unquoted = statements(RESOURCE).replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
        String table = collapsed(statements(RESOURCE));
        for (String columns : List.of(JdbcRiskCaseStore.COLUMNS, JdbcRiskCaseStore.ACTION_COLUMNS,
                JdbcRiskCaseStore.EVENT_COLUMNS)) {
            for (String column : columns.split(",")) {
                assertThat(table).as(column.strip()).containsPattern("\\b" + column.strip()
                        + "\\s+(uuid|text|integer|smallint|boolean|timestamptz|uuid\\[\\])[\\s,]");
            }
        }
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
        return names;
    }

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = RiskCaseMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
