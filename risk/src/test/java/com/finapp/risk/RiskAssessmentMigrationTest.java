package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code risk V007}'s text held to the code (`P13-TSK-004`; the migration-test idiom, hermetic - the live ranks are
 * {@code RiskEvaluationDatabaseTest}'s and {@code RiskMigrationTest}'s, written and owed under C5): the vocabularies
 * are the enums', the five tables sealed for every writer and granted {@code SELECT, INSERT} alone, every name held
 * once across every risk migration (the Phase 12 V012 lesson) - and <strong>no risk SQL names another schema</strong>,
 * in a migration or in a store ({@code INV-RSK-02}: a risk decision never writes another domain's row).
 */
@DisplayName("risk V007 seals the assessment as the code declares it, and risk's SQL names no other schema (P13-TSK-004)")
class RiskAssessmentMigrationTest {

    private static final String RESOURCE = "db/migration/risk/V007__the_sealed_assessment.sql";

    /** risk V008 (P13-TSK-010): the decision admission and the policy knobs regenerated for CARD_PAYMENT's HOLD. */
    private static final String HELD_CARD_PAYMENT = "db/migration/risk/V008__the_held_card_payment.sql";

    /** risk V011 (P13-TSK-014): the assessment's {@code session_id}, among its widenings. */
    private static final String ABUSE = "db/migration/risk/V011__payment_merchant_and_bnpl_abuse.sql";

    private static final List<String> MIGRATIONS = List.of(
            "db/migration/risk/V001__initialise_risk_schema.sql",
            "db/migration/risk/V002__risk_policy_versions_as_data.sql",
            "db/migration/risk/V003__risk_signal.sql",
            "db/migration/risk/V004__monitored_transactions_and_the_watermark.sql",
            "db/migration/risk/V005__velocity_windows.sql",
            "db/migration/risk/V006__the_customer_risk_profile.sql",
            RESOURCE);

    /** Every other module's schema - and PostgreSQL's public one. */
    private static final Pattern ANOTHER_SCHEMA = Pattern.compile("\\b(lending|credit|ledger|platform|checkout|merchant"
            + "|identity|accounts|payments|paymentmethods|bnpl|kyc|party|transfers|crossborder|settlement"
            + "|reconciliation|fx|consent|public)\\.[a-z_]+");

    /** A literal that is one dotted code and nothing else: {@code ledger.journal}, {@code risk.RiskDecisionMade}. */
    private static final Pattern CODE_TOKEN = Pattern.compile("[a-z]+\\.[A-Za-z_]+");

    @Test
    @DisplayName("the vocabularies are the enums': the judged operations, the decisions each admits, the fail-safe's"
            + " reasons and causes, the velocity outcomes, the rule kinds and outcomes, the reason codes less the seam's")
    void theListsAreTheEnums() {
        String sql = collapsed(statements(RESOURCE));
        String judged = Arrays.stream(RiskOperation.values()).filter(RiskOperation::judgesAMovement)
                .map(o -> "'" + o.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("CHECK (operation IN (" + judged + "))")
                .contains("unavailable_cause IN (" + list(UnavailableCause.values()) + ")")
                .contains("velocity_outcome IN (" + list(VelocityOutcome.values()) + ")")
                .contains("CHECK (outcome IN (" + list(RuleOutcome.values()) + "))")
                .contains("CHECK (kind IN (" + list(RuleKind.values()) + "))")
                .contains("CHECK (engine IN (" + list(RiskEngine.values()) + "))")
                .contains("command_ref ~ '^" + CommandRef.SHAPE.pattern() + "$'")
                .contains("device_hash ~ '" + DeviceHash.SHAPE.pattern() + "'")
                .contains("CHECK (amount_minor BETWEEN 0 AND " + EvaluationInputs.MAX_MINOR + ")");
        String reasons = Arrays.stream(ReasonCode.values()).filter(code -> code.source() != ReasonCode.Source.SEAM)
                .map(code -> "'" + code.name() + "'").collect(Collectors.joining(", "));
        assertThat(sql).contains("CHECK (code IN (" + reasons + "))");
        // The decisions each operation admits: V007's CHECK is applied history since risk V008 (P13-TSK-010) dropped and
        // re-made it with CARD_PAYMENT's HOLD - the current definition is V008's, and THAT is held to admittedBy.
        assertThat(sql).as("V007 as it was written: CARD_PAYMENT without HOLD")
                .contains("OR (operation IN ('CARD_PAYMENT', 'WALLET_PAYMENT') AND decision IN ('ALLOW', 'CHALLENGE',"
                        + " 'DENY'))");
        String current = collapsed(statements(HELD_CARD_PAYMENT));
        assertThat(current).contains("ALTER TABLE risk.risk_assessment DROP CONSTRAINT risk_assessment_decision_admitted;");
        sql = current;
        for (RiskOperation operation : RiskOperation.values()) {
            if (!operation.judgesAMovement()) {
                continue;
            }
            String admitted = RiskDecision.admittedBy(operation).stream().map(d -> "'" + d.name() + "'").sorted()
                    .collect(Collectors.joining(","));
            Matcher row = Pattern.compile("operation (?:= '" + operation.name() + "'|IN \\([^)]*'" + operation.name()
                    + "'[^)]*\\)) AND decision IN \\(([^)]*)\\)").matcher(sql);
            assertThat(row.find()).as(operation.name()).isTrue();
            String declared = Arrays.stream(row.group(1).split(",")).map(String::strip).sorted()
                    .collect(Collectors.joining(","));
            assertThat(declared).as(operation + " admits exactly what RiskDecision.admittedBy says").isEqualTo(admitted);
        }
    }

    @Test
    @DisplayName("sealed for every writer: the four assessment tables refuse UPDATE, DELETE and TRUNCATE by trigger; the"
            + " override refuses every UPDATE until P13-TSK-012; the stamp and the inputs' digest are the database's")
    void theTablesAreSealed() {
        String sql = collapsed(statements(RESOURCE));
        for (String table : List.of("risk_assessment", "risk_assessment_input", "risk_rule_hit", "risk_reason")) {
            assertThat(sql).as(table)
                    .contains("CREATE TRIGGER " + table + "_is_sealed BEFORE UPDATE OR DELETE ON risk." + table)
                    .contains("CREATE TRIGGER " + table + "_is_never_truncated BEFORE TRUNCATE ON risk." + table);
        }
        assertThat(sql).contains("NEW.assessed_at := clock_timestamp();")
                .contains("inputs_sha256 = encode(sha256(convert_to(inputs_canonical, 'UTF8')), 'hex')")
                .contains("CONSTRAINT risk_assessment_command_once UNIQUE (operation, command_ref)")
                .contains("RAISE EXCEPTION 'a decision override does not move until P13-TSK-012 builds its machine'")
                .contains("CREATE UNIQUE INDEX decision_override_one_live ON risk.decision_override (assessment_id)"
                        + " WHERE status = 'PROPOSED'")
                .contains("CHECK (status <> 'APPROVED' OR NOT requires_second_person OR decided_by <> proposed_by)");
        assertThat(sql).doesNotContain("now()").doesNotContain("transaction_timestamp()");
    }

    @Test
    @DisplayName("the application holds SELECT and INSERT on the five tables and nothing else: no UPDATE, no DELETE, no"
            + " default privilege, nothing seeded")
    void theGrantsAreInsertOnly() {
        String sql = statements(RESOURCE);
        assertThat(Arrays.stream(sql.split(";")).map(s -> s.strip().replaceAll("\\s+", " "))
                .filter(s -> s.startsWith("GRANT") || s.startsWith("REVOKE")).toList()).containsExactly(
                "GRANT SELECT, INSERT ON risk.risk_assessment TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_assessment_input TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_rule_hit TO finapp_app",
                "GRANT SELECT, INSERT ON risk.risk_reason TO finapp_app",
                "GRANT SELECT, INSERT ON risk.decision_override TO finapp_app");
        assertThat(sql).doesNotContain("INSERT INTO risk.").doesNotContain("ALTER DEFAULT PRIVILEGES");
    }

    @Test
    @DisplayName("every constraint, index and trigger name is held once across EVERY risk migration (V001 to V007) - the"
            + " Phase 12 V012 class, refused by PostgreSQL only at the second migration that reuses a name")
    void namesAreUniqueAcrossTheSchema() {
        java.util.Set<String> earlier = new java.util.HashSet<>();
        for (String migration : MIGRATIONS.subList(0, MIGRATIONS.size() - 1)) {
            earlier.addAll(names(statements(migration)));
        }
        List<String> v007 = names(statements(RESOURCE));
        assertThat(v007).as("the guard is not vacuous").hasSizeGreaterThan(40);
        assertThat(v007).doesNotHaveDuplicates();
        assertThat(v007).as("no name an earlier risk migration already holds").noneMatch(earlier::contains);
        assertThat(v007).allMatch(name -> name.startsWith("risk_assessment") || name.startsWith("risk_rule_hit_")
                || name.startsWith("risk_reason_") || name.startsWith("decision_override_"));
    }

    @Test
    @DisplayName("risk V008 (P13-TSK-010) declares no new name: it regenerates exactly the two widened CHECKs, each"
            + " DROPped first, and names no other schema")
    void theHeldCardPaymentRegeneratesTwoChecks() {
        String sql = statements(HELD_CARD_PAYMENT);
        assertThat(names(sql)).containsExactly("risk_assessment_decision_admitted", "risk_policy_knobs_coherent");
        assertThat(sql).contains("ALTER TABLE risk.risk_assessment DROP CONSTRAINT risk_assessment_decision_admitted;")
                .contains("ALTER TABLE risk.risk_policy DROP CONSTRAINT risk_policy_knobs_coherent;")
                .doesNotContain("GRANT").doesNotContain("CREATE TABLE");
        assertThat(ANOTHER_SCHEMA.matcher(sql).find()).isFalse();
        java.util.Set<String> earlier = new java.util.HashSet<>();
        for (String migration : MIGRATIONS) {
            earlier.addAll(names(statements(migration)));
        }
        assertThat(earlier).as("both names are earlier migrations' constraints, re-made")
                .contains("risk_assessment_decision_admitted", "risk_policy_knobs_coherent");
    }

    /** The constraint, index and trigger names a migration creates. */
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

    @Test
    @DisplayName("the store's inserts name each column once with one value each, and every column the head names is one"
            + " V007 declares or V011 adds (P13-TSK-014's session_id) - the seal's statements cannot drift from the table")
    void theStoresInsertsMatchTheTables() {
        String sql = collapsed(statements(RESOURCE));
        String added = collapsed(statements(ABUSE));
        for (String insert : List.of(JdbcRiskAssessmentStore.INSERT_HEAD, JdbcRiskAssessmentStore.INSERT_INPUT,
                JdbcRiskAssessmentStore.INSERT_HIT, JdbcRiskAssessmentStore.INSERT_REASON)) {
            Matcher shape = Pattern.compile("INSERT INTO risk\\.([a-z_]+) \\(([^)]*)\\) VALUES \\(([^)]*\\)?)\\)")
                    .matcher(insert);
            assertThat(shape.find()).as(insert).isTrue();
            List<String> columns = Arrays.stream(shape.group(2).split(",")).map(String::strip).toList();
            List<String> values = Arrays.stream(shape.group(3).split(",")).map(String::strip).toList();
            assertThat(columns).as(insert).doesNotHaveDuplicates().hasSameSizeAs(values);
            assertThat(values).as(insert).allMatch(value -> value.equals("?") || value.equals("clock_timestamp()"));
            String table = shape.group(1);
            for (String column : columns) {
                boolean declared = Pattern.compile("CREATE TABLE risk\\." + table + " \\([^;]*\\b" + column
                        + "\\s+(uuid|text|bigint|integer|boolean|timestamptz)").matcher(sql).find();
                boolean addedLater = Pattern.compile("ALTER TABLE risk\\." + table + " ADD COLUMN " + column
                        + " (uuid|text|bigint|integer|boolean|timestamptz)").matcher(added).find();
                assertThat(declared || addedLater).as(table + "." + column).isTrue();
            }
        }
    }

    @Test
    @DisplayName("INV-RSK-02: no risk migration and no risk class names another module's schema - risk's SQL reads and"
            + " writes schema risk alone")
    void riskSqlNamesNoOtherSchema() throws IOException {
        for (String migration : MIGRATIONS) {
            // A schema is never named inside a quoted value: 'ledger.journal' is V004's source CODE, not a schema.
            String unquoted = statements(migration).replaceAll("'(?:[^']|'')*'", "''");
            assertThat(ANOTHER_SCHEMA.matcher(unquoted).results().map(MatchResult::group).toList())
                    .as(migration).isEmpty();
        }
        Path sources = Path.of("src", "main", "java", "com", "finapp", "risk");
        assertThat(sources).as("the module's sources, from the module's directory").isDirectory();
        List<String> found = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(sources)) {
            // risk's SQL lives in its Jdbc* stores, by the module's convention - and only there is a dotted literal SQL.
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("Jdbc")
                    && f.toString().endsWith(".java")).toList()) {
                scanned++;
                for (String literal : literals(Files.readString(file, StandardCharsets.UTF_8))) {
                    if (CODE_TOKEN.matcher(literal).matches()) {
                        continue; // a whole literal that is one dotted code - an event type, a source - is no SQL
                    }
                    ANOTHER_SCHEMA.matcher(literal.replaceAll("'(?:[^']|'')*'", "''")).results()
                            .forEach(hit -> found.add(file.getFileName() + ": " + hit.group()));
                }
            }
        }
        assertThat(scanned).as("the scan is not vacuous: every store").isGreaterThanOrEqualTo(9);
        assertThat(found).isEmpty();
    }

    // -----------------------------------------------------------------

    /** The string literals of a Java source - the only place SQL lives in a store. */
    private static List<String> literals(String source) {
        List<String> literals = new ArrayList<>();
        Matcher matched = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(source);
        while (matched.find()) {
            literals.add(matched.group(1));
        }
        return literals;
    }

    private static String list(Enum<?>[] values) {
        return Arrays.stream(values).map(v -> "'" + v.name() + "'").collect(Collectors.joining(", "));
    }

    private static String collapsed(String sql) {
        return sql.replaceAll("\\s+", " ").replace("( ", "(").replace(" )", ")");
    }

    /** The migration with its comment lines removed - what actually executes. */
    private static String statements(String resource) {
        try (InputStream in = RiskAssessmentMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource + " on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
