package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.BornOnceArbiters;
import com.finapp.platform.testing.database.BornOnceArbiters.Probe;
import com.finapp.platform.testing.database.BornOnceArbiters.Verdict;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every born-once arbiter of the {@code risk} schema survives a lock-bypass probe (`P13-DOC-001`; {@code PHASE_GATES.md}
 * section 5 Phase 13, "Multi-instance correctness": "each arbiter surviving a lock-bypass probe"; "Testing": "each
 * born-once arbiter ... probed alone with the lock or domain above it bypassed").
 *
 * <p>The Phase 11 and 12 exit reviews' {@code BornOnceArbiters} applied to {@code risk}. The Phase 13 tasks probed their
 * arbiters as migration text and recorded the live probes "written and owed" (C5); the exit review's independent audit
 * counted four of the schema's uniques refused alone by a raw-SQL test and seventeen with none. This suite closes the
 * class rather than the instances: it reads every unique index of the schema from the catalogue and demands that each,
 * with every user and foreign-key trigger suspended and every {@code CHECK} of its table dropped inside the probe's own
 * rolled-back savepoint, refuses a copy of a row under a fresh key BY ITSELF - so neither a lock above it (R1-R9, the
 * advisory keys, the claims), nor the domain's look-up, nor a trigger beside it is what refuses the copy.
 *
 * <p><strong>Its rows.</strong> The source row of each probe is a SEED planted in that probe's own savepoint (triggers and
 * foreign keys suspended, the table's {@code CHECK}s already dropped), every column filled with a value of its type and
 * the columns a partial index reads set inside its predicate - so the suite needs no other suite's rows and leaves none:
 * a unique's refusal of a duplicate is the index's property, not the path that wrote the row. Both sets are pinned: a
 * catalogue-driven probe cannot see a unique that is gone, and a born-once unique widened to include the key would
 * otherwise pass as a foreign-key target.
 */
@Tag("database")
@DisplayName("every born-once arbiter of the risk schema refuses a copy alone, triggers off (P13-DOC-001)")
class RiskBornOnceArbitersDatabaseTest {

    private static final String SCHEMA = "risk";

    /** Every unique of the schema, pinned (a unique added later fails this suite until it is named here and probed). */
    private static final Set<String> THE_SCHEMAS_UNIQUES = Set.of(
            "customer_risk_profile_version_one_per_disposition", "decision_override_one_live",
            "decision_override_one_standing", "monitored_transaction_born_once", "regulatory_report_one_live_per_case",
            "rescreening_permit_once", "risk_action_one_live", "risk_action_one_per_override",
            "risk_alert_born_once", "risk_assessment_command_once", "risk_case_action_of_its_case",
            "risk_case_action_one_per_step", "risk_case_one_open", "risk_policy_one_active",
            "risk_policy_version_number_unique", "risk_policy_version_operation_pinned", "risk_signal_born_once",
            "risk_signal_id_and_source", "screening_list_version_once", "screening_list_version_one_active",
            "screening_list_version_ordinal_once");

    /** The uniques that bind the whole primary key - composite foreign-key targets the key already implies. */
    private static final Set<String> FOREIGN_KEY_TARGETS = Set.of(
            "risk_case_action_of_its_case", "risk_policy_version_operation_pinned", "risk_signal_id_and_source");

    /**
     * The columns a partial index's predicate reads, set inside it on the seed (every other column takes a value of its
     * type, which no predicate below excludes): an ACTIVE version, a PROPOSED override (live and standing), a PROPOSED
     * action, an ACTIVE list version. The case's, the report's and the profile version's predicates ({@code <> 'DISPOSED'},
     * {@code <> 'REJECTED'}, {@code IS NOT NULL}) admit the generic seed as it is.
     */
    private static final Map<String, Map<String, String>> PREDICATE_COLUMNS = Map.of(
            "risk_policy_version", Map.of("status", "'ACTIVE'"),
            "decision_override", Map.of("status", "'PROPOSED'"),
            "risk_action", Map.of("status", "'PROPOSED'"),
            "screening_list_version", Map.of("status", "'ACTIVE'"),
            // fact_signal_id is GENERATED from a SCREENING_HIT's first evidence signal (V018) - NULL for every other kind,
            // and the probe copies only a row whose probed columns are all non-null.
            "risk_alert", Map.of("kind", "'SCREENING_HIT'"));

    /**
     * Per index, where a table's two uniques overlap: {@code decision_override_one_live} (PROPOSED) lies inside
     * {@code decision_override_one_standing} (PROPOSED or APPROVED) on the same column, so a PROPOSED copy collides with
     * both and the first in the index order answers. The standing probe's rows are made APPROVED - every PROPOSED row
     * moved, in its own rolled-back savepoint, and its seed born APPROVED - so its copy meets the standing unique alone.
     * (The live probe's PROPOSED copy meets the live unique first; with it gone the standing one answers, which this suite
     * reports as a sibling's refusal - so dropping either is caught.)
     */
    private static final Map<String, String> BEFORE_THE_SEED = Map.of(
            "decision_override_one_standing",
            "UPDATE risk.decision_override SET status = 'APPROVED' WHERE status = 'PROPOSED'");
    private static final Map<String, Map<String, String>> PREDICATE_COLUMNS_BY_INDEX = Map.of(
            "decision_override_one_standing", Map.of("status", "'APPROVED'"));

    @Test
    @DisplayName("every unique of every risk table, read from the catalogue, refuses a copy of a seeded row under a fresh"
            + " key by itself with every trigger suspended - none admits its copy, none needs a sibling to refuse it, none"
            + " is left unprobed")
    void everyBornOnceArbiterRefusesACopyWithEveryTriggerOff() throws Exception {
        Map<String, String> seeds = seeds();
        List<Probe> probes = BornOnceArbiters.probe(SCHEMA, seeds);
        System.out.println("P13-DOC-001 ARBITERS: " + probes.stream().collect(Collectors.groupingBy(Probe::verdict,
                Collectors.counting())));
        probes.forEach(probe -> System.out.println("  " + probe));

        assertThat(probes).extracting(Probe::constraint).as("the schema's uniques, every one still standing")
                .containsExactlyInAnyOrderElementsOf(THE_SCHEMAS_UNIQUES);
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.ADMITTED).as("a unique admitting its own copy")
                .isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_BY_ANOTHER)
                .as("a copy the probe could not isolate to its unique").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.EXPRESSION_INDEX).isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.PRIMARY_KEY_BOUND).extracting(Probe::constraint)
                .as("only the foreign-key targets bind the whole key").containsExactlyInAnyOrderElementsOf(
                        FOREIGN_KEY_TARGETS);
        Set<String> unprobed = probes.stream().filter(probe -> probe.verdict() == Verdict.NO_SOURCE_ROW)
                .map(Probe::toString).collect(Collectors.toCollection(TreeSet::new));
        assertThat(unprobed).as("a unique left unprobed for want of a row to copy").isEmpty();
        assertThat(probes).filteredOn(probe -> probe.verdict() == Verdict.REFUSED_ALONE).as("every other unique refused alone")
                .hasSize(THE_SCHEMAS_UNIQUES.size() - FOREIGN_KEY_TARGETS.size());
    }

    /** One seed per unique index: an INSERT of one row of its table, every column a value of its type. */
    private static Map<String, String> seeds() throws SQLException {
        Map<String, String> seeds = new LinkedHashMap<>();
        try (Connection bootstrap = DatabaseRoles.bootstrap();
                PreparedStatement uniques = bootstrap.prepareStatement("SELECT c.relname, i.relname FROM pg_index x"
                        + " JOIN pg_class i ON i.oid = x.indexrelid JOIN pg_class c ON c.oid = x.indrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relkind = 'r' AND x.indisunique AND NOT x.indisprimary")) {
            uniques.setString(1, SCHEMA);
            try (ResultSet row = uniques.executeQuery()) {
                while (row.next()) {
                    String index = row.getString(2);
                    String seed = seedOf(bootstrap, row.getString(1), index);
                    seeds.put(index, BEFORE_THE_SEED.containsKey(index) ? BEFORE_THE_SEED.get(index) + "; " + seed : seed);
                }
            }
        }
        return seeds;
    }

    private static String seedOf(Connection connection, String table, String index) throws SQLException {
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        Map<String, String> fixed = new LinkedHashMap<>(PREDICATE_COLUMNS.getOrDefault(table, Map.of()));
        fixed.putAll(PREDICATE_COLUMNS_BY_INDEX.getOrDefault(index, Map.of()));
        try (PreparedStatement columns = connection.prepareStatement("SELECT column_name, data_type, udt_name,"
                + " character_maximum_length, is_generated FROM information_schema.columns WHERE table_schema = ?"
                + " AND table_name = ? ORDER BY ordinal_position")) {
            columns.setString(1, SCHEMA);
            columns.setString(2, table);
            try (ResultSet column = columns.executeQuery()) {
                while (column.next()) {
                    if ("ALWAYS".equals(column.getString(5))) {
                        continue;
                    }
                    String name = column.getString(1);
                    names.add('"' + name + '"');
                    values.add(fixed.containsKey(name) ? fixed.get(name)
                            : valueOf(column.getString(2), column.getString(3), column.getObject(4, Integer.class)));
                }
            }
        }
        return "INSERT INTO " + SCHEMA + "." + table + " (" + String.join(", ", names) + ") OVERRIDING SYSTEM VALUE"
                + " VALUES (" + String.join(", ", values) + ")";
    }

    /** A value of the column's type - random where a type admits it, so no seed collides with a row already there. */
    private static String valueOf(String dataType, String udtName, Integer length) {
        return switch (dataType) {
            case "uuid" -> "gen_random_uuid()";
            case "text" -> "'seed-' || substr(md5(random()::text), 1, 12)";
            case "character varying" -> length == null ? "'seed-' || substr(md5(random()::text), 1, 12)"
                    : "substr('s' || md5(random()::text), 1, " + length + ")";
            case "character" -> "rpad('S', " + (length == null ? 1 : length) + ", 'S')";
            case "smallint" -> "(1 + floor(random() * 30000))::smallint";
            case "integer" -> "(1 + floor(random() * 2000000000))::integer";
            case "bigint", "numeric" -> "(1 + floor(random() * 2000000000))::bigint";
            case "boolean" -> "false";
            case "timestamp with time zone", "timestamp without time zone" -> "now()";
            case "date" -> "current_date";
            case "interval" -> "interval '1 hour'";
            case "jsonb", "json" -> "'{}'";
            case "bytea" -> "'\\x00'::bytea";
            case "ARRAY" -> switch (udtName) {
                case "_uuid" -> "ARRAY[gen_random_uuid()]";
                case "_text" -> "ARRAY['seed']";
                case "_int4", "_int8", "_int2" -> "ARRAY[1]";
                default -> throw new IllegalStateException("no seed value for an array of " + udtName);
            };
            default -> throw new IllegalStateException("no seed value for a column of type " + dataType + " ("
                    + udtName + ") - extend RiskBornOnceArbitersDatabaseTest.valueOf");
        };
    }
}
