package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
 * {@code risk V018}'s text held to the code (`P13-TSK-022`; the migration-test idiom, hermetic - the live constraints,
 * grants and triggers are {@code RescreeningDatabaseTest}'s and {@code RiskMigrationTest}'s, written and owed under C5):
 * the vocabularies are the enums', one version per (list, label) and one in force per list, four eyes a {@code CHECK} of
 * its own, the persons {@code CUSTOMER} or {@code EMPLOYEE} exactly as {@link Cases#person} admits, one permit per (party,
 * version), the permit's signal its own, the claim and the conclusions' shapes, the grants the machines' columns alone,
 * every name new, no other schema.
 */
@DisplayName("risk V018: screening list versions and rescreening permits, held to the code (P13-TSK-022)")
class ScreeningListMigrationTest {

    static final String RESOURCE = "db/migration/risk/V018__screening_list_versions_and_rescreening.sql";

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
            "db/migration/risk/V014__the_queues_and_the_suppression_persons.sql",
            "db/migration/risk/V015__monitored_transaction_by_counterparty.sql",
            "db/migration/risk/V016__the_regulatory_report.sql",
            "db/migration/risk/V017__the_collections_case.sql");

    /** The list version's machine columns - exactly the application role's column-scoped UPDATE. */
    static final List<String> VERSION_MACHINE = List.of("status", "activated_by", "activated_by_type", "enqueue_next_at",
            "enqueue_cursor", "enqueued", "correlation_id");

    /** The permit's machine columns - exactly the application role's column-scoped UPDATE. */
    static final List<String> PERMIT_MACHINE = List.of("status", "outcome", "cause", "signal_id", "next_attempt_at",
            "attempts");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    private final String sql = collapsed(statements(RESOURCE));

    @Test
    @DisplayName("the vocabularies are the code's: ScreeningList, ScreeningListVersionStatus, and the permit's status,"
            + " outcome and cause")
    void theVocabularies() {
        assertThat(sql).contains("CONSTRAINT screening_list_version_list_is_known CHECK (list IN ("
                + ScreeningList.sqlValueList() + "))")
                .contains("CONSTRAINT screening_list_version_status_is_known CHECK (status IN ("
                        + ScreeningListVersionStatus.sqlValueList() + "))")
                .contains("CONSTRAINT rescreening_permit_status_is_known CHECK (status IN ("
                        + RescreeningPermit.Status.sqlValueList() + "))")
                .contains("CONSTRAINT rescreening_permit_outcome_is_known CHECK (outcome IS NULL OR outcome IN ("
                        + RescreeningPermit.Outcome.sqlValueList() + "))")
                .contains("CONSTRAINT rescreening_permit_cause_is_known CHECK (cause IS NULL OR cause IN ("
                        + RescreeningPermit.Cause.sqlValueList() + "))");
        assertThat(sql).contains("CONSTRAINT screening_list_version_label_shaped CHECK (version ~ '"
                + ScreeningListVersion.LABEL.pattern() + "')");
    }

    @Test
    @DisplayName("INV-RSK-06: one version per (list, label), one in force per list, one permit per (party, version) - the"
            + " stores' births infer them")
    void theArbiters() {
        assertThat(sql).contains("CONSTRAINT screening_list_version_once UNIQUE (list, version)")
                .contains("CONSTRAINT screening_list_version_ordinal_once UNIQUE (list, ordinal)")
                .contains("CREATE UNIQUE INDEX screening_list_version_one_active ON risk.screening_list_version (list)"
                        + " WHERE status = 'ACTIVE';")
                .contains("CONSTRAINT rescreening_permit_once UNIQUE (party_id, list_version_id)");
        assertThat(JdbcScreeningListStore.INSERT).contains("ON CONFLICT (list, version) DO NOTHING");
        assertThat(JdbcRescreeningPermitStore.ENQUEUE).contains("ON CONFLICT (party_id, list_version_id) DO NOTHING");
    }

    @Test
    @DisplayName("a permit names exactly the signal born of it: the composite foreign key over (id, source_event_id),"
            + " present exactly for a clearance")
    void thePermitsSignalIsItsOwn() {
        assertThat(sql).contains("ALTER TABLE risk.risk_signal ADD CONSTRAINT risk_signal_id_and_source UNIQUE (id,"
                + " source_event_id);")
                .contains("CONSTRAINT rescreening_permit_signal_is_its_own FOREIGN KEY (signal_id, id) REFERENCES"
                        + " risk.risk_signal (id, source_event_id)")
                .contains("CONSTRAINT rescreening_permit_signal_coherent CHECK ((signal_id IS NOT NULL) ="
                        + " coalesce(outcome IN ('CLEARED', 'HIT'), false))");
        for (RescreeningPermit.Outcome outcome : RescreeningPermit.Outcome.values()) {
            assertThat(sql.contains("outcome IN ('CLEARED', 'HIT')")).isTrue();
            assertThat(List.of("CLEARED", "HIT").contains(outcome.name())).as("%s records a signal", outcome)
                    .isEqualTo(outcome.recordsASignal());
        }
    }

    @Test
    @DisplayName("four eyes is a CHECK of its own, provable alone; the persons CUSTOMER or EMPLOYEE - exactly what"
            + " Cases.person admits")
    void fourEyesAndThePersons() {
        assertThat(sql).contains("CONSTRAINT screening_list_version_four_eyes CHECK (activated_by IS NULL OR activated_by"
                + " <> recorded_by)");
        String persons = sql.substring(sql.indexOf("CONSTRAINT screening_list_version_by_persons"),
                sql.indexOf("CONSTRAINT screening_list_version_four_eyes"));
        for (ActorType type : ActorType.values()) {
            assertThat(persons.contains("'" + type.name() + "'")).as("%s admitted exactly when Cases.person admits it", type)
                    .isEqualTo(Cases.person(new Actor("someone", type)));
        }
        String machine = function(sql, "screening_list_version_permits_only_machine_edges");
        assertThat(machine).as("the trigger leaves four eyes to the CHECK").doesNotContain("activated_by <> ")
                .doesNotContain("activated_by = NEW.recorded_by");
    }

    @Test
    @DisplayName("the list version's machine: born RECORDED, numbered without a gap, the fact frozen, activated with its"
            + " enqueue due from nothing, the enqueue only forward within a claim, retired with its enqueue stopped, never"
            + " deleted")
    void theListVersionsMachine() {
        String machine = function(sql, "screening_list_version_permits_only_machine_edges");
        assertThat(machine).contains("a screening list version is born RECORDED, unactivated and enqueuing nothing")
                .contains("NEW.ordinal <> (SELECT coalesce(max(v.ordinal), 0) + 1")
                .contains("a screening list version''s fact never changes")
                .contains("NEW.activated_at := clock_timestamp(); NEW.enqueue_next_at := clock_timestamp();")
                .contains("OLD.enqueue_next_at > clock_timestamp()")
                .contains("NEW.enqueue_cursor < OLD.enqueue_cursor")
                .contains("NEW.retired_at := clock_timestamp(); NEW.enqueue_next_at := NULL;")
                .contains("a retired screening list version never moves")
                .contains("a screening list version is never deleted or truncated");
        for (String frozen : List.of("id", "list", "version", "ordinal", "recorded_by", "recorded_by_type",
                "recorded_at")) {
            assertThat(machine).as("%s frozen", frozen).contains("NEW." + frozen + " IS DISTINCT FROM OLD." + frozen);
        }
        assertThat(sql).contains("CREATE TRIGGER screening_list_version_is_never_truncated BEFORE TRUNCATE ON"
                + " risk.screening_list_version");
        assertThat(JdbcScreeningListStore.CLAIM_ENQUEUE_DUE).contains("status = 'ACTIVE'")
                .contains("enqueue_next_at <= clock_timestamp()").contains("FOR UPDATE SKIP LOCKED");
        assertThat(JdbcScreeningListStore.RETIRE).contains("WHERE id = ? AND status = 'ACTIVE'");
        assertThat(JdbcScreeningListStore.ACTIVATE).contains("WHERE id = ? AND status = 'RECORDED'");
        assertThat(JdbcScreeningListStore.LOCK_LIST).contains("pg_advisory_xact_lock(13, hashtext(?))");
    }

    @Test
    @DisplayName("the permit's machine: born PENDING and due only for a version in force; a claim only of a permit that"
            + " has come, one attempt; a deferral after a claim with its cause; the conclusion once, at the claimed"
            + " attempt; nothing out of DONE or FAILED; never deleted")
    void thePermitsMachine() {
        String machine = function(sql, "rescreening_permit_permits_only_machine_edges");
        assertThat(machine).contains("a rescreening permit is born PENDING, unclaimed and unconcluded")
                .contains("v.status = 'ACTIVE'")
                .contains("NEW.attempts = OLD.attempts + 1").contains("OLD.next_attempt_at > clock_timestamp()")
                .contains("a rescreening permit is deferred after a claim, with its cause")
                .contains("a rescreening permit is concluded after a claim, counting no attempt")
                .contains("a concluded rescreening permit never moves")
                .contains("a rescreening permit is never deleted or truncated");
        assertThat(JdbcRescreeningPermitStore.CLAIM_NEXT_DUE).contains("status = 'PENDING'")
                .contains("next_attempt_at <= clock_timestamp()")
                .contains("ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED")
                .contains("attempts = attempts + 1");
        for (String conditional : List.of(JdbcRescreeningPermitStore.CONCLUDE, JdbcRescreeningPermitStore.FAIL,
                JdbcRescreeningPermitStore.DEFER)) {
            assertThat(conditional).contains("WHERE id = ? AND status = 'PENDING' AND attempts = ?");
        }
        assertThat(JdbcRescreeningPermitStore.LOCK).contains("FOR UPDATE OF p");
        assertThat(sql).contains("CREATE INDEX rescreening_permit_due ON risk.rescreening_permit (next_attempt_at, id)"
                + " WHERE status = 'PENDING';");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT, and an UPDATE of the machines' columns alone - every column the stores"
            + " set is granted, no fact and no stamp is; nothing deleted, truncated or granted whole")
    void theGrants() {
        assertThat(granted("screening_list_version")).containsExactlyInAnyOrderElementsOf(VERSION_MACHINE);
        assertThat(granted("rescreening_permit")).containsExactlyInAnyOrderElementsOf(PERMIT_MACHINE);
        assertThat(sql).contains("GRANT SELECT, INSERT ON risk.screening_list_version TO finapp_app;")
                .contains("GRANT SELECT, INSERT ON risk.rescreening_permit TO finapp_app;")
                .doesNotContain("GRANT DELETE").doesNotContain("GRANT ALL").doesNotContain("GRANT TRUNCATE")
                .doesNotContain("ON risk.risk_signal TO");
        for (String statement : List.of(JdbcScreeningListStore.RETIRE, JdbcScreeningListStore.ACTIVATE,
                JdbcScreeningListStore.ADVANCE)) {
            assertThat(VERSION_MACHINE).containsAll(set(statement));
        }
        for (String statement : List.of(JdbcRescreeningPermitStore.CONCLUDE, JdbcRescreeningPermitStore.FAIL,
                JdbcRescreeningPermitStore.DEFER)) {
            assertThat(PERMIT_MACHINE).containsAll(set(statement));
        }
    }

    @Test
    @DisplayName("the signal's two references and two kinds: risk_signal gains list_version_ref and screening_ref, the"
            + " code's last two attribute columns, and the store's statements name them")
    void theSignalsReferences() {
        assertThat(sql).contains("ALTER TABLE risk.risk_signal ADD COLUMN list_version_ref uuid;")
                .contains("ALTER TABLE risk.risk_signal ADD COLUMN screening_ref uuid;");
        assertThat(SignalKind.ATTRIBUTE_COLUMNS).endsWith("list_version_ref", "screening_ref");
        for (SignalKind kind : List.of(SignalKind.SCREENING_CLEARED, SignalKind.SCREENING_HIT)) {
            assertThat(sql).containsPattern("WHEN '" + kind.name() + "'\\s+THEN 'PARTY'");
            assertThat(kind.consumed()).isFalse();
        }
        assertThat(sql).contains("CREATE INDEX risk_signal_by_list_version ON risk.risk_signal (list_version_ref, kind)"
                + " WHERE list_version_ref IS NOT NULL;");
    }

    @Test
    @DisplayName("the review's IMPORTANT 2: the hit's alert is a fact - V017's two pairing CHECKs re-made with AlertKind's"
            + " own-code kinds, SCREENING_HIT among them, and its shape held: the party, the activation's day, its one"
            + " signal; the kind and rule-code lists left as V017 made them")
    void theHitsAlertIsAFact() {
        String own = Arrays.stream(AlertKind.values()).filter(AlertKind::ownCode).map(kind -> "'" + kind.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        assertThat(own).isEqualTo(
                "'SCREENING_HIT', 'UNCLASSIFIED_TRANSACTION', 'VELOCITY_DIVERGENCE', 'OBLIGATION_DEFAULTED'");
        assertThat(sql)
                .contains("ADD CONSTRAINT risk_alert_rule_matches_kind CHECK ((kind IN (" + own + ")) = (rule_code IN ("
                        + own + ")) AND (kind NOT IN (" + own + ") OR rule_code = kind));")
                .contains("ADD CONSTRAINT risk_alert_policy_named CHECK ((kind IN (" + own
                        + ")) = (policy_version_id IS NULL));")
                .contains("ADD CONSTRAINT risk_alert_screening_hit_names_its_party CHECK (kind <> 'SCREENING_HIT' OR"
                        + " (subject_kind = 'PARTY' AND window_kind = 'D1' AND cardinality(evidence_signal_ids) = 1"
                        + " AND cardinality(evidence_assessment_ids) = 0 AND cardinality(evidence_journal_entry_ids) = 0));")
                .doesNotContain("risk_alert_kind_is_known").doesNotContain("risk_alert_rule_code_is_known")
                .doesNotContain("CONSTRAINT risk_alert_born_once");
        assertThat(AlertKind.SCREENING_HIT.factSubject()).contains(SubjectKind.PARTY);
        assertThat(RescreeningRun.HIT_WINDOW).isEqualTo(WindowKind.D1);
    }

    @Test
    @DisplayName("nothing else: every name new across V001 to V017 but the three re-made signal constraints and the two"
            + " re-made alert pairings, each dropped first under its own name; no other schema named; no row written")
    void everyNameNewAndNoOtherSchema() {
        String raw = statements(RESOURCE);
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> made = names(raw);
        assertThat(made).contains("screening_list_version_pk", "screening_list_version_once",
                "screening_list_version_one_active", "screening_list_version_four_eyes", "rescreening_permit_once",
                "rescreening_permit_signal_is_its_own", "rescreening_permit_permits_only_machine_edges");
        List<String> remade = made.stream().filter(earlier::contains).toList();
        assertThat(remade).containsExactlyInAnyOrder("risk_signal_kind_is_known", "risk_signal_subject_matches_kind",
                "risk_signal_attributes_match_kind", "risk_alert_rule_matches_kind", "risk_alert_policy_named");
        for (String name : remade) {
            assertThat(raw).as("%s dropped before it is made again", name).contains("DROP CONSTRAINT " + name + ";");
        }
        assertThat(raw).doesNotContain("INSERT INTO").doesNotContain("UPDATE risk.").doesNotContain("DELETE FROM")
                .doesNotContain("CREATE OR REPLACE");
        String unquoted = raw.replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
        assertThat(unquoted).doesNotContainPattern("amount|balance");
    }

    // -----------------------------------------------------------------

    private List<String> granted(String table) {
        Matcher grant = Pattern.compile("GRANT UPDATE \\(([^)]*)\\) ON risk\\." + table + " TO finapp_app;").matcher(sql);
        assertThat(grant.find()).as("the column-scoped UPDATE grant of %s", table).isTrue();
        return Arrays.stream(grant.group(1).split(",")).map(String::strip).toList();
    }

    /** The columns {@code statement}'s SET assigns. */
    private static List<String> set(String statement) {
        String set = statement.substring(statement.indexOf(" SET ") + 5, statement.indexOf(" WHERE "));
        List<String> columns = new ArrayList<>();
        for (String assignment : set.split(",(?![^(]*\\))")) {
            columns.add(assignment.strip().split(" ")[0]);
        }
        return columns;
    }

    /** The body of function {@code name}, from its CREATE to its closing dollar-quote. */
    private static String function(String sql, String name) {
        int start = sql.indexOf("CREATE FUNCTION risk." + name + "()");
        assertThat(start).as("function %s", name).isNotNegative();
        int body = sql.indexOf("$$", start);
        return sql.substring(start, sql.indexOf("$$", body + 2));
    }

    private static List<String> names(String sql) {
        List<String> names = new ArrayList<>();
        for (Pattern pattern : List.of(
                Pattern.compile("(?<!ADD )CONSTRAINT\\s+([a-z0-9_]+)\\s+(?:PRIMARY|UNIQUE|CHECK|FOREIGN)"),
                Pattern.compile("ADD CONSTRAINT\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:UNIQUE )?INDEX\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:CONSTRAINT )?TRIGGER\\s+([a-z0-9_]+)"),
                Pattern.compile("CREATE (?:OR REPLACE )?FUNCTION\\s+risk\\.([a-z0-9_]+)"))) {
            Matcher matched = pattern.matcher(sql);
            while (matched.find()) {
                names.add(matched.group(1));
            }
        }
        return names.stream().distinct().collect(Collectors.toList());
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = ScreeningListMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
