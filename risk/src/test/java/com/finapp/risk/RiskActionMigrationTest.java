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
 * {@code risk V013}'s text held to the code (`P13-TSK-017`; the migration-test idiom, hermetic - the live tables are
 * {@code RiskActionDatabaseTest}'s and the live grants {@code RiskMigrationTest}'s, written and owed under C5): the
 * vocabularies are the enums', each kind's target {@link ActionKind#target()}, the override's kinds its four, the live
 * partial unique the store's inference, four eyes the {@code CHECK}'s alone, the trigger's proposability
 * {@link ActionRules}', the machine's every rule present, the fold keeping every issued action's key, the case's
 * re-made {@code CHECK}s the whole enums, the grants column-scoped with no {@code DELETE}, every new name new across V001
 * to V012 (the re-made ones by name), and no other schema named.
 */
@DisplayName("risk V013 holds the action machine and the fold as the code declares them (P13-TSK-017)")
class RiskActionMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V013__downstream_risk_actions.sql";

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
            "db/migration/risk/V012__the_case.sql");

    /** The constraints V013 re-makes by name - dropped and added in the same statement. */
    private static final Set<String> REMADE = Set.of("decision_override_action_shape", "risk_case_action_texted",
            "risk_case_event_kind_is_known", "risk_case_event_names_its_act");

    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    @Test
    @DisplayName("the vocabularies are the enums': source, kind, target kind, status, outcome; each kind acts on its"
            + " own target kind; an override issues payments' four held doors alone")
    void theListsAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("CHECK (source IN (" + ActionSource.sqlValueList() + "))")
                .contains("CHECK (kind IN (" + ActionKind.sqlValueList() + "))")
                .contains("CHECK (target_kind IN (" + ActionTarget.sqlValueList() + "))")
                .contains("CHECK (status IN (" + ActionStatus.sqlValueList() + "))")
                .contains("outcome IN (" + list(ActionOutcome.values()) + ")");
        for (ActionKind kind : ActionKind.values()) {
            assertThat(sql).as(kind.name()).contains("WHEN '" + kind.name() + "' THEN '" + kind.target().name() + "'");
        }
        String overrides = Arrays.stream(ActionKind.values()).filter(ActionKind::issuedByOverride)
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("CHECK (source <> 'OVERRIDE' OR kind IN (" + overrides + "))");
        assertThat(Arrays.stream(OverrideEffect.values()).flatMap(effect -> effect.action().stream())
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "))).isEqualTo(overrides);
    }

    @Test
    @DisplayName("INV-RSK-06: one live action per (case, kind, target) over the live states, and the store's proposal"
            + " infers it - ON CONFLICT ... WHERE status IN (the live) DO NOTHING; the claim SKIP LOCKED on the permit")
    void theOneLiveUniqueAndTheClaim() {
        String sql = collapsed(statements(RESOURCE));
        String live = Arrays.stream(ActionStatus.values()).filter(ActionStatus::live)
                .map(status -> "'" + status.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("CREATE UNIQUE INDEX risk_action_one_live ON risk.risk_action (case_id, kind,"
                + " target_ref) WHERE status IN (" + live + ");");
        assertThat(JdbcRiskActionStore.INSERT_PROPOSED)
                .contains("ON CONFLICT (case_id, kind, target_ref) WHERE status IN (" + live + ") DO NOTHING RETURNING");
        assertThat(sql).contains("CREATE INDEX risk_action_due ON risk.risk_action (next_attempt_at, id) WHERE"
                + " next_attempt_at IS NOT NULL;");
        for (String claim : List.of(JdbcRiskActionStore.CLAIM, JdbcRiskActionStore.CLAIM_NEXT_DUE)) {
            assertThat(claim).contains("FOR UPDATE SKIP LOCKED").contains("next_attempt_at <= clock_timestamp()")
                    .contains("attempts = attempts + 1").contains("? * interval '1 millisecond'")
                    .contains("status IN ('APPROVED', 'DISPATCHED')");
        }
        assertThat(JdbcRiskActionStore.CONCLUDE).contains("WHERE id = ? AND status = 'DISPATCHED'");
        assertThat(JdbcRiskActionStore.APPROVE).contains("WHERE id = ? AND status = 'PROPOSED'");
    }

    @Test
    @DisplayName("INV-RSK-07: four eyes is the CHECK's alone - a case's approved action was approved by someone other"
            + " than its proposer; the trigger never compares the two persons; a case's persons are persons")
    void fourEyesIsTheChecksAlone() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql).contains("CONSTRAINT risk_action_four_eyes CHECK (source <> 'CASE' OR status IN ('PROPOSED',"
                + " 'REJECTED') OR decided_by <> proposed_by)");
        assertThat(sql).contains("(proposed_by_type IN ('CUSTOMER', 'EMPLOYEE') AND (decided_by_type IS NULL OR"
                + " decided_by_type IN ('CUSTOMER', 'EMPLOYEE')))");
        String trigger = trigger(sql);
        assertThat(trigger).as("four eyes is provable by the CHECK alone")
                .doesNotContain("decided_by <> NEW.proposed_by").doesNotContain("NEW.decided_by <> NEW.proposed_by")
                .doesNotContain("decided_by = NEW.proposed_by");
    }

    @Test
    @DisplayName("the trigger's proposability is ActionRules': identity's three on a party's case's own party,"
            + " SUSPEND_MERCHANT on a merchant-abuse case's own merchant, a held movement's on a HOLD the case holds,"
            + " anything else refused")
    void theTriggerProposesWhatTheRulesAdmit() {
        String trigger = trigger(collapsed(statements(RESOURCE)));
        assertThat(trigger)
                .contains("IF NEW.kind IN (" + names(ActionRules.IDENTITY_KINDS) + ") THEN IF acted.kind NOT IN ("
                        + names(ActionRules.PARTY_CASES) + ") OR acted.subject_kind <> 'PARTY' OR"
                        + " NEW.target_ref::text <> acted.subject_ref THEN")
                .contains("ELSIF NEW.kind IN (" + names(ActionRules.MERCHANT_KINDS) + ") THEN IF acted.kind NOT IN ("
                        + names(ActionRules.MERCHANT_CASES) + ") OR acted.subject_kind <> 'MERCHANT' OR"
                        + " NEW.target_ref::text <> acted.subject_ref THEN")
                .contains("ELSIF NEW.kind IN (" + names(ActionRules.HELD_WITHDRAWAL_KINDS) + ", "
                        + names(ActionRules.HELD_PAYMENT_KINDS) + ") THEN IF acted.kind = 'COLLECTIONS' OR NOT EXISTS")
                .contains("WHEN NEW.kind IN (" + names(ActionRules.HELD_WITHDRAWAL_KINDS)
                        + ") THEN 'WITHDRAWAL' ELSE 'CARD_PAYMENT' END")
                .contains("a.decision = 'HOLD'")
                .contains("RAISE EXCEPTION 'this kind is not proposed on a case (P13-TSK-017)'");
        assertThat(ActionRules.admits(CaseKind.COLLECTIONS, SubjectKind.PARTY, ActionKind.RELEASE_HELD_WITHDRAWAL))
                .isFalse();
    }

    @Test
    @DisplayName("the machine's rules are present: born PROPOSED (a case's) or APPROVED (an override's), the proposal"
            + " frozen, a decision by its case's own act, a claim only of a permit that has come, the record once,"
            + " nothing out of DONE, FAILED or REJECTED, never deleted")
    void theMachinesRulesArePresent() {
        String sql = collapsed(statements(RESOURCE));
        assertThat(sql)
                .contains("RAISE EXCEPTION 'a risk action is never deleted or truncated (P13-TSK-017, INV-RSK-06)';")
                .contains("RAISE EXCEPTION 'a case''s action is born PROPOSED, undecided and unclaimed (P13-TSK-017)'")
                .contains("RAISE EXCEPTION 'an override''s action is exactly what its approval issued (P13-TSK-017)'")
                .contains("RAISE EXCEPTION 'a risk action''s proposal never changes (P13-TSK-017)'")
                .contains("RAISE EXCEPTION 'a concluded or rejected risk action never moves (P13-TSK-017)'")
                .contains("RAISE EXCEPTION 'an action''s permit is claimed only when it has come (P13-TSK-017)'")
                .contains("IF NEW.attempts <> OLD.attempts + 1")
                .contains("RAISE EXCEPTION 'a risk action moves only along its machine (P13-TSK-017)'")
                .contains("x.kind = 'ACTION_PROPOSED'")
                .contains("WHEN 'APPROVED' THEN 'ACTION_APPROVED' ELSE 'ACTION_REJECTED' END")
                .contains("NEW.concluded_at := clock_timestamp();")
                .contains("NEW.decided_at := clock_timestamp();")
                .contains("NEW.proposed_at := clock_timestamp();")
                .contains("BEFORE INSERT OR UPDATE OR DELETE ON risk.risk_action")
                .contains("BEFORE TRUNCATE ON risk.risk_action")
                .contains("FOREIGN KEY (id, case_id) REFERENCES risk.risk_case_action (id, case_id)")
                .contains("(override_id IS NULL OR override_id = id)");
        assertThat(sql).contains("CREATE TRIGGER risk_case_disposes_no_standing_action BEFORE UPDATE ON risk.risk_case"
                + " FOR EACH ROW WHEN (NEW.status = 'DISPOSED' AND OLD.status <> 'DISPOSED')");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the fold keeps every key: each action an approved override issued is copied under the override's own"
            + " id - an awaiting one DISPATCHED with its permit, a concluded one with its outcome - before the four"
            + " columns are dropped; the override's function is replaced by name and its action held by a deferred"
            + " trigger")
    void theFoldKeepsEveryKey() {
        String sql = collapsed(statements(RESOURCE));
        int copied = sql.indexOf("INSERT INTO risk.risk_action (");
        int dropped = sql.indexOf("DROP COLUMN action_outcome");
        assertThat(copied).as("copied first").isPositive().isLessThan(dropped);
        assertThat(sql.substring(copied, sql.indexOf(";", copied)))
                .contains("SELECT o.id, 'OVERRIDE', NULL, o.id, o.effect")
                .contains("CASE WHEN o.action_outcome IS NULL THEN 'DISPATCHED' WHEN o.action_outcome = 'REFUSED' THEN"
                        + " 'FAILED' ELSE 'DONE' END")
                .contains("CASE WHEN o.action_outcome IS NULL THEN COALESCE(o.action_due_at, clock_timestamp()) END")
                .contains("GREATEST(o.action_attempts, 1)")
                .contains("FROM risk.decision_override o WHERE o.status = 'APPROVED' AND o.effect <> 'RESUBMISSION'");
        assertThat(sql.indexOf("CREATE TRIGGER risk_action_permits_only_machine_edges"))
                .as("the machine's trigger after the copy").isGreaterThan(copied);
        assertThat(sql).contains("DROP COLUMN action_outcome, DROP COLUMN action_due_at, DROP COLUMN action_attempts,"
                + " DROP COLUMN action_concluded_at;")
                .contains("DROP INDEX risk.decision_override_action_due;")
                .contains("CREATE OR REPLACE FUNCTION risk.decision_override_permits_only_machine_edges()")
                .contains("CREATE CONSTRAINT TRIGGER decision_override_issues_its_action AFTER UPDATE ON"
                        + " risk.decision_override DEFERRABLE INITIALLY DEFERRED");
        assertThat(RiskActionId.ofOverride(DecisionOverrideId.of(EvaluationWorld.IDS.next())).value()).isNotNull();
        String replaced = sql.substring(sql.indexOf("CREATE OR REPLACE FUNCTION risk.decision_override_permits"),
                sql.indexOf("ALTER TABLE risk.decision_override DROP CONSTRAINT"));
        assertThat(replaced).as("the replaced function names no moved column").doesNotContain("action_outcome")
                .doesNotContain("action_due_at").doesNotContain("action_attempts").doesNotContain("action_concluded_at");
    }

    @Test
    @DisplayName("the case's re-made CHECKs are the whole enums: the texted kinds, the history's kinds, a person's steps")
    void theCasesRemadeChecksAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        String texted = Arrays.stream(CaseActionKind.values()).filter(CaseActionKind::texted)
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
        String persons = Arrays.stream(CaseEventKind.values()).filter(CaseEventKind::persons)
                .map(kind -> "'" + kind.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql)
                .contains("DROP CONSTRAINT risk_case_action_texted, ADD CONSTRAINT risk_case_action_texted CHECK"
                        + " ((kind NOT IN (" + texted + ") OR text IS NOT NULL)")
                .contains("DROP CONSTRAINT risk_case_event_kind_is_known, ADD CONSTRAINT risk_case_event_kind_is_known"
                        + " CHECK (kind IN (" + list(CaseEventKind.values()) + "))")
                .contains("ADD CONSTRAINT risk_case_event_names_its_act CHECK ((kind IN (" + persons
                        + ")) = (action_id IS NOT NULL)");
    }

    @Test
    @DisplayName("the grants: SELECT and INSERT, a column-scoped UPDATE of the machine's columns - never a fact or a"
            + " stamp - and no DELETE, no ALL, no default privilege")
    void theGrantIsColumnScoped() {
        String sql = statements(RESOURCE);
        List<String> grants = Arrays.stream(sql.split(";")).map(s -> s.strip().replaceAll("\\s+", " "))
                .filter(s -> s.startsWith("GRANT") || s.startsWith("REVOKE")).toList();
        assertThat(grants).containsExactly(
                "GRANT SELECT, INSERT ON risk.risk_action TO finapp_app",
                "GRANT UPDATE (status, decided_by, decided_by_type, decision_action_id, next_attempt_at, attempts,"
                        + " outcome) ON risk.risk_action TO finapp_app");
        assertThat(grants).noneMatch(grant -> grant.contains("DELETE") || grant.contains("TRUNCATE")
                || grant.contains("proposed_at") || grant.contains("concluded_at") || grant.contains("target_ref"));
        assertThat(sql).doesNotContain("GRANT ALL").doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("every name V013 creates is new across V001 to V012 but the four it re-makes by name, held once in"
            + " V013; the store's columns are V013's; no other schema is named")
    void namesAreUniqueAndNoOtherSchema() {
        Set<String> earlier = new HashSet<>();
        for (String migration : EARLIER) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v013 = names(statements(RESOURCE));
        assertThat(v013).as("the guard is not vacuous").hasSizeGreaterThanOrEqualTo(30);
        assertThat(v013).doesNotHaveDuplicates();
        assertThat(v013.stream().filter(earlier::contains)).containsExactlyInAnyOrderElementsOf(REMADE);
        assertThat(v013).allMatch(name -> name.startsWith("risk_action_") || REMADE.contains(name)
                || name.equals("decision_override_issues_its_action")
                || name.equals("risk_case_disposes_no_standing_action"));
        String unquoted = statements(RESOURCE).replaceAll("'(?:[^']|'')*'", "''");
        assertThat(ANOTHER_SCHEMA.matcher(unquoted).find()).isFalse();
        String table = collapsed(statements(RESOURCE));
        for (String column : JdbcRiskActionStore.COLUMNS.split(",")) {
            assertThat(table).as(column.strip()).containsPattern("\\b" + column.strip()
                    + "\\s+(uuid|text|integer|timestamptz)[\\s,]");
        }
    }

    // -----------------------------------------------------------------

    private static String trigger(String sql) {
        return sql.substring(sql.indexOf("CREATE FUNCTION risk.risk_action_permits_only_machine_edges()"),
                sql.indexOf("CREATE TRIGGER risk_action_permits_only_machine_edges"));
    }

    private static String names(Set<? extends Enum<?>> values) {
        return values.stream().map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }

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
        try (InputStream in = RiskActionMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
