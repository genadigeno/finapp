package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V009` reconciled against the vocabulary that generates it and the functions it re-states
 * (`P8-TSK-017`, ADR-0065 §2, ADR-0067 §5): the item's line-type and key-kind {@code CHECK}s are
 * the scheme vocabulary's value lists — the enums' whole lists when `V009` landed, the payout
 * members `V010`'s (`P8-TSK-018`) — the scheme members exactly what `V009` adds to `V008`'s bank
 * vocabulary; the run's trigger freezes {@code settlement_cycle} beside `V003`'s birth facts and
 * keeps `V003`'s edges; the item's trigger keeps `V008`'s frozen copy and adds the learned-cycle
 * rule (written once, equal to its run's cycle); an item is never BORN knowing a cycle; and the
 * one grant is the learned cycle's UPDATE column — the run's cycle is never granted.
 */
@DisplayName("reconciliation V009 reconciliation (P8-TSK-017)")
class ReconciliationV009MigrationTest {

    private static final String V003 =
            "db/migration/reconciliation/V003__runs_and_external_items.sql";
    private static final String V008 =
            "db/migration/reconciliation/V008__bank_items_and_attribution.sql";
    private static final String V009 =
            "db/migration/reconciliation/V009__scheme_items_and_the_learned_cycle.sql";

    private static final String RUN_FUNCTION =
            "reconciliation.reconciliation_batch_moves_only_on_machine_edges";
    private static final String ITEM_FUNCTION =
            "reconciliation.external_item_moves_only_on_machine_edges";
    private static final String BIRTH_FUNCTION =
            "reconciliation.external_item_learns_no_cycle_at_birth";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final UUID SCHEME_SOURCE =
            UUID.fromString("01a0e2bc-8200-7002-8000-000000000002");
    private static final UUID SCHEME_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7002-8000-000000000002");

    // ----------------------------------------------------------------- the vocabulary

    @Test
    @DisplayName("the line-type and key-kind CHECKs are the scheme vocabulary's lists - the scheme"
            + " members and references exactly what V009 adds to V008's bank vocabulary")
    void theSchemeVocabularyIsTheEnums() {
        String sql = normalized(migration(V009));
        assertThat(sql)
                .contains(normalized(
                        "ADD CONSTRAINT external_item_line_type CHECK (line_type IN ("
                                + ExternalLineType.sqlValueList(
                                        ExternalLineType.schemeVocabulary()) + "))"))
                .contains(normalized(
                        "ADD CONSTRAINT external_item_key_kind CHECK (key_kind IN ("
                                + ItemKeyKind.sqlValueList(ItemKeyKind.schemeVocabulary())
                                + "))"));

        Set<ExternalLineType> schemeMembers = EnumSet.copyOf(ExternalLineType.schemeVocabulary());
        schemeMembers.removeAll(ExternalLineType.bankVocabulary());
        assertThat(schemeMembers)
                .containsExactlyInAnyOrder(
                        ExternalLineType.CREDIT_IN,
                        ExternalLineType.DEBIT_OUT,
                        ExternalLineType.SCHEME_FEE);
        assertThat(schemeMembers).noneMatch(ExternalLineType::isBankLine);
        Set<ItemKeyKind> schemeKeys = EnumSet.copyOf(ItemKeyKind.schemeVocabulary());
        schemeKeys.removeAll(ItemKeyKind.bankVocabulary());
        assertThat(schemeKeys)
                .containsExactlyInAnyOrder(ItemKeyKind.SCHEME_REF, ItemKeyKind.END_TO_END_REF);

        assertThat(ExternalLineType.SCHEME_FEE.allocating())
                .as("the scheme's fee is the recognition's cost line: judged, never allocated")
                .isFalse();
        assertThat(ExternalLineType.CREDIT_IN.allocating()).isTrue();
        assertThat(ExternalLineType.DEBIT_OUT.allocating()).isTrue();
        assertThat(ExternalLineType.SCHEME_FEE.originalKeyKind())
                .as("the scheme's fee names its execution by the scheme's reference")
                .contains(KeyKind.SCHEME_REF);
        assertThat(ExternalLineType.PROCESSING_FEE.originalKeyKind())
                .as("the PSP's fee still names its capture")
                .contains(KeyKind.PSP_CAPTURE_REF);
        assertThat(ExternalLineType.BANK_FEE.originalKeyKind())
                .as("the bank's fee has no original: it is judged against a zero gross")
                .isEmpty();
        assertThat(ExternalLineType.CREDIT_IN.originalKeyKind()).isEmpty();
        assertThat(ExternalLineType.DEBIT_OUT.originalKeyKind()).isEmpty();
    }

    // ----------------------------------------------------------------- the run's cycle

    @Test
    @DisplayName("the cycle is the run's frozen birth fact: V003's frozen columns plus"
            + " settlement_cycle, V003's edges and cursor rule kept, the column bounded 1..64 -"
            + " and NewRun refuses what the CHECK refuses")
    void theCycleIsTheRunsFrozenBirthFact() {
        String sql = normalized(migration(V009));
        assertThat(sql)
                .contains("ADD COLUMN settlement_cycle TEXT")
                .contains("CHECK (char_length(settlement_cycle) BETWEEN 1 AND 64)");

        String v009Run = functionBody(sql, RUN_FUNCTION);
        String v003Run = functionBody(normalized(migration(V003)), RUN_FUNCTION);
        Set<String> expected = new LinkedHashSet<>(frozenColumns(v003Run));
        expected.add("settlement_cycle");
        assertThat(frozenColumns(v009Run))
                .as("V003's birth facts, the cycle beside them")
                .containsExactlyInAnyOrderElementsOf(expected);
        assertThat(v009Run)
                .contains(RunStatus.sqlTransitionRule())
                .contains("IF NEW.cursor < OLD.cursor THEN")
                .contains("birth statement is frozen");

        // The domain rank mirrors the bound.
        assertThat(newRun(Optional.of("C".repeat(64))).settlementCycle()).isPresent();
        assertThatThrownBy(() -> newRun(Optional.of("")))
                .as("an empty cycle token")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newRun(Optional.of("C".repeat(65))))
                .as("a cycle token beyond the column's bound")
                .isInstanceOf(IllegalArgumentException.class);
        ReconciliationRuns.NewRun cycleLess =
                new ReconciliationRuns.NewRun(
                        IDS.next(),
                        SCHEME_SOURCE,
                        Optional.of(IDS.next()),
                        RunKind.BATCH,
                        SCHEME_RULE_SET,
                        LocalDate.parse("2026-09-30"),
                        Optional.of(1L),
                        1,
                        Optional.empty(),
                        Optional.empty(),
                        new Actor("system", ActorType.SYSTEM),
                        Instant.now(CLOCK),
                        CorrelationId.generate(IDS));
        assertThat(cycleLess.settlementCycle())
                .as("every run that is not a scheme cycle report carries no cycle")
                .isEmpty();
    }

    // ----------------------------------------------------------------- the item's cycle

    @Test
    @DisplayName("the item keeps V008's frozen copy and its machine's edges; the learned cycle"
            + " is not frozen but written once, equal to its own run's cycle")
    void theItemKeepsItsCopyAndLearnsOnce() {
        String sql = normalized(migration(V009));
        String v009Item = functionBody(sql, ITEM_FUNCTION);
        String v008Item = functionBody(normalized(migration(V008)), ITEM_FUNCTION);

        assertThat(frozenColumns(v009Item))
                .as("the copied line exactly as V008 froze it")
                .containsExactlyInAnyOrderElementsOf(frozenColumns(v008Item))
                .doesNotContain("learned_cycle", "status");
        assertThat(v009Item)
                .contains(ItemStatus.sqlTransitionRule())
                .contains(normalized(
                        "IF NEW.learned_cycle IS DISTINCT FROM OLD.learned_cycle THEN"
                                + " IF OLD.learned_cycle IS NOT NULL THEN RAISE EXCEPTION"))
                .contains("recorded once")
                .contains(normalized(
                        "SELECT settlement_cycle INTO run_cycle"
                                + " FROM reconciliation.reconciliation_batch"
                                + " WHERE id = NEW.run_id;"))
                .contains(normalized(
                        "IF run_cycle IS NULL OR NEW.learned_cycle IS DISTINCT FROM run_cycle"
                                + " THEN RAISE EXCEPTION"));
        assertThat(sql)
                .contains("ADD COLUMN learned_cycle TEXT")
                .contains("CHECK (char_length(learned_cycle) BETWEEN 1 AND 64)");
    }

    @Test
    @DisplayName("an item is never BORN knowing a cycle - the BEFORE INSERT trigger - and the one"
            + " grant is UPDATE (learned_cycle): the run's cycle is never granted, nothing is"
            + " deleted")
    void theBirthTriggerAndTheNarrowedGrant() {
        String sql = normalized(migration(V009));
        assertThat(functionBody(sql, BIRTH_FUNCTION))
                .contains("IF NEW.learned_cycle IS NOT NULL THEN RAISE EXCEPTION")
                .contains("never at birth");
        assertThat(sql)
                .contains(normalized(
                        "CREATE TRIGGER external_item_learns_no_cycle_at_birth"
                                + " BEFORE INSERT ON reconciliation.external_item"
                                + " FOR EACH ROW"
                                + " EXECUTE FUNCTION " + BIRTH_FUNCTION + "();"));
        assertThat(occurrences(sql, "CREATE TRIGGER"))
                .as("the run and item functions are re-stated under their standing triggers")
                .isEqualTo(1);

        List<String> grants = new ArrayList<>();
        Matcher grant = Pattern.compile("GRANT [^;]*;").matcher(sql);
        while (grant.find()) {
            grants.add(grant.group());
        }
        assertThat(grants)
                .containsExactly(
                        "GRANT UPDATE (learned_cycle) ON reconciliation.external_item TO"
                                + " finapp_app;");
        assertThat(sql).doesNotContain("DELETE");
    }

    // -----------------------------------------------------------------

    private static ReconciliationRuns.NewRun newRun(Optional<String> cycle) {
        return new ReconciliationRuns.NewRun(
                IDS.next(),
                SCHEME_SOURCE,
                Optional.of(IDS.next()),
                RunKind.BATCH,
                SCHEME_RULE_SET,
                LocalDate.parse("2026-09-30"),
                Optional.of(1L),
                1,
                Optional.empty(),
                Optional.empty(),
                new Actor("system", ActorType.SYSTEM),
                Instant.now(CLOCK),
                CorrelationId.generate(IDS),
                cycle);
    }

    /** The columns a trigger function's FIRST condition freezes: its birth statement. */
    private static List<String> frozenColumns(String body) {
        int start = body.indexOf("IF NEW.id IS DISTINCT FROM OLD.id");
        assertThat(start).as("the function opens with its frozen birth statement").isNotNegative();
        String condition = body.substring(start, body.indexOf(" THEN", start));
        List<String> columns = new ArrayList<>();
        Matcher column =
                Pattern.compile("NEW\\.(\\w+) IS DISTINCT FROM OLD\\.\\1").matcher(condition);
        while (column.find()) {
            columns.add(column.group(1));
        }
        return columns;
    }

    /** The body between the {@code $$} delimiters of the named function's definition. */
    private static String functionBody(String sql, String function) {
        int definition = sql.indexOf("CREATE OR REPLACE FUNCTION " + function + "()");
        assertThat(definition).as("%s is defined here", function).isNotNegative();
        int open = sql.indexOf("$$", definition);
        int close = sql.indexOf("$$", open + 2);
        return sql.substring(open + 2, close);
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) {
            count++;
        }
        return count;
    }

    /** Whitespace collapsed, so a generated fragment matches however the SQL wraps. */
    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration(String path) {
        try (InputStream migration =
                ReconciliationV009MigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
