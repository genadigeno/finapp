package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V002` reconciled against the vocabulary that generates it (`P8-TSK-004`, the
 * {@code PaymentsMigrationTest} discipline): the status and kind {@code CHECK}s are the
 * enums' value lists, the transition trigger's edge conditions are exactly
 * {@link ExpectationStatus#permittedTransitions()}, the key-kind lists are
 * {@link KeyKind}'s, and the tolerance comparisons are the closed three — an edge or a value
 * added on one side without the other fails here, not in production.
 */
@DisplayName("reconciliation V002 reconciliation (P8-TSK-004)")
class ReconciliationV002MigrationTest {

    private static final String V002 =
            "db/migration/reconciliation/V002__expectation_register_and_rule_set_v1.sql";

    /**
     * The key kinds as V002 wrote them - applied history: reconciliation `V020` (`P9-TSK-011`)
     * appended the FX provider's, and {@code ReconciliationV020MigrationTest} holds the whole lists.
     */
    private static final java.util.Set<KeyKind> AS_OF_V002 =
            java.util.EnumSet.range(KeyKind.PSP_CAPTURE_REF, KeyKind.REMITTANCE_REF);

    @Test
    @DisplayName("the expectation's status and kind CHECKs are the enums' value lists")
    void statusAndKindChecksMatchTheEnums() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT expectation_status CHECK (status IN ("
                                + ExpectationStatus.sqlValueList() + "))"))
                .contains(normalized(
                        "CONSTRAINT expectation_kind CHECK (kind IN ("
                                + ExpectationKind.sqlValueList(java.util.EnumSet.range(
                                        ExpectationKind.CARD_CAPTURE, ExpectationKind.REMITTANCE)) + "))"));
    }

    @Test
    @DisplayName("the key and alias kind CHECKs are KeyKind's value list")
    void keyKindChecksMatchTheEnum() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains(normalized(
                        "CONSTRAINT expectation_key_kind CHECK (key_kind IN ("
                                + KeyKind.sqlValueList(AS_OF_V002) + "))"))
                .contains(normalized(
                        "CONSTRAINT reference_alias_kind CHECK (key_kind IN ("
                                + KeyKind.sqlValueList(AS_OF_V002) + "))"));
    }

    @Test
    @DisplayName("the transition trigger's edge conditions are generated from the machine -"
            + " the repudiation's reopening included")
    void triggerEdgesMatchTheMachine() {
        // Every OLD-status clause in the trigger, parsed to a set of targets.
        Pattern edge =
                Pattern.compile(
                        "OLD\\.status = '([A-Z_]+)' AND NEW\\.status IN\\s*\\(([^)]+)\\)");
        Matcher matcher = edge.matcher(normalized(migration()));
        Map<String, Set<String>> declared = new HashMap<>();
        while (matcher.find()) {
            declared.put(
                    matcher.group(1),
                    Arrays.stream(matcher.group(2).split(","))
                            .map(value -> value.trim().replace("'", ""))
                            .collect(Collectors.toSet()));
        }
        Map<String, Set<String>> machine = new HashMap<>();
        for (ExpectationStatus status : ExpectationStatus.values()) {
            if (!status.permittedTransitions().isEmpty()) {
                machine.put(
                        status.name(),
                        status.permittedTransitions().stream()
                                .map(Enum::name)
                                .collect(Collectors.toSet()));
            }
        }
        assertThat(declared)
                .as("an edge added to the machine without its trigger half is a transition"
                        + " the aggregate permits and every other writer is refused")
                .isEqualTo(machine);
        assertThat(machine.keySet())
                .as("RESOLVED_BY_ADJUSTMENT is terminal: no clause may exist for it")
                .doesNotContain("RESOLVED_BY_ADJUSTMENT");
    }

    @Test
    @DisplayName("the tolerance comparisons are the closed three - no amount member is"
            + " representable (INV-REC-08)")
    void toleranceComparisonsAreClosed() {
        assertThat(normalized(migration()))
                .contains(normalized(
                        "CONSTRAINT tolerance_comparison CHECK (comparison IN ("
                                + "'PROCESSING_FEE_PER_LINE', 'PROCESSING_FEE_PER_BATCH',"
                                + " 'SETTLEMENT_DATE_DAYS'))"));
        assertThat(migration())
                .as("no comparison naming a principal amount exists to seed or store")
                .doesNotContain("PRINCIPAL")
                .doesNotContain("AMOUNT_TOLERANCE");
    }

    @Test
    @DisplayName("rule set v1's dating values are the plan's: card 3, instant 1, payout 2,"
            + " funding 2, gain age 90 - and the payout return is operation-anchored at 72h")
    void theSeedCarriesTheDecidedValues() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("'CARD_CAPTURE', 3")
                .contains("'PUSH_PAY_IN', 1")
                .contains("'MERCHANT_PAYOUT', 2")
                .contains("'PAYOUT_RETURN', 2")
                .contains(normalized(
                        "'PAYOUT_RETURNED', 'PAYOUT_PROVIDER_REF', 'PAYOUT_RETURN',"
                                + " 'ONE_TO_ONE', true, 72"))
                .contains(normalized(
                        "'PAYOUT_RETURNED', 'OUR_REF', 'PAYOUT_RETURN', 'ONE_TO_ONE',"
                                + " true, 72"));
    }

    // -----------------------------------------------------------------

    private static String migration() {
        try (InputStream in =
                ReconciliationV002MigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(V002)) {
            assertThat(in).as("V002 must be on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("could not read " + V002, failure);
        }
    }

    private static String normalized(String sql) {
        // Whitespace collapsed AND parenthesis-adjacent space dropped, so a value list
        // wrapped across lines compares equal to the enum's one-line rendering.
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")")
                .trim();
    }
}
