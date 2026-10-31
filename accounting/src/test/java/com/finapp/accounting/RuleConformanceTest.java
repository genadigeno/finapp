package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rule conformance (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-12}'s runtime half): every journal entry judged
 * against the posting rule register in force at its posting date - an unregistered namespace once per namespace, a shape
 * that matches no rule in force once per entry, an unbalanced currency once per entry, nothing for a conforming entry.
 * The entry's probe - the shape comparison skipped - turns {@link #aPlantedShapeMismatchIsOneFindingPerEntry} red.
 * Hermetic: the register is code and the entries values; {@code AccountingBreakDatabaseTest} (owed, C5) judges the real
 * journal.
 */
@DisplayName("rule conformance: every entry against the register in force at its posting date (P14-TSK-010)")
class RuleConformanceTest {

    private static final LocalDate DAY = LocalDate.of(2097, 3, 10);

    private final RuleConformance conformance = new RuleConformance(PostingRuleRegister.CURRENT);

    @Test
    @DisplayName("a conforming entry raises nothing - a transfer's two wallet lines, a reversal under its own rule, a"
            + " manual adjustment on purposes open to it")
    void aConformingEntryRaisesNothing() {
        assertThat(conformance.judge(entry(EntryType.POSTING, "transfer:",
                line("CUSTOMER_WALLET", Side.DEBIT, 100), line("CUSTOMER_WALLET", Side.CREDIT, 100)))).isEmpty();
        assertThat(conformance.judge(entry(EntryType.REVERSAL, "transfer:",
                line("CUSTOMER_WALLET", Side.CREDIT, 100), line("CUSTOMER_WALLET", Side.DEBIT, 100)))).isEmpty();
        assertThat(conformance.judge(entry(EntryType.ADJUSTMENT, "MANUAL",
                line("CUSTOMER_WALLET", Side.DEBIT, 5), line("FEE_REVENUE", Side.CREDIT, 5)))).isEmpty();
    }

    @Test
    @DisplayName("a planted shape mismatch is one SHAPE_MISMATCH per entry: a transfer crediting fee revenue - its rules"
            + " named, its lines' purposes and sides, never an amount")
    void aPlantedShapeMismatchIsOneFindingPerEntry() {
        JournalShapes.EntryShape planted = entry(EntryType.POSTING, "transfer:",
                line("CUSTOMER_WALLET", Side.DEBIT, 4321), line("FEE_REVENUE", Side.CREDIT, 4321));

        List<BreakFinding> findings = conformance.judge(planted);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.check()).isEqualTo(CloseCheckCode.RULE_CONFORMANCE);
            assertThat(finding.type()).isEqualTo(BreakType.SHAPE_MISMATCH);
            assertThat(finding.subject()).isEqualTo("shape:" + planted.id());
            assertThat(finding.evidence()).contains("TRANSFER v1").contains("CREDIT FEE_REVENUE")
                    .doesNotContain("4321").doesNotContain("43.21");
            assertThat(finding.judgedBy()).isEqualTo("posting-rules:v" + PostingRuleRegister.CATALOGUE_VERSION);
        });
        // A required line missing is a mismatch too, every line admitted and the entry balanced: a cover that closed one
        // leg of its four.
        assertThat(conformance.judge(entry(EntryType.POSTING, "fx-cover:", line("FX_POSITION", Side.DEBIT, 1),
                line("FX_PROVIDER_CLEARING", Side.CREDIT, 1)))).extracting(BreakFinding::type)
                .containsExactly(BreakType.SHAPE_MISMATCH);
    }

    @Test
    @DisplayName("an unregistered namespace is one UNREGISTERED_POSTING per type and namespace, whatever its entries -"
            + " counted, its first entries named; a key with no namespace and an adjustment with no origin folded")
    void anUnregisteredNamespaceIsOneFindingPerNamespace() {
        RuleConformance.Tally tally = conformance.tally(java.util.Map.of(), 100);
        List<UUID> planted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JournalShapes.EntryShape entry = entry(EntryType.POSTING, "p14-010-probe:",
                    line("CUSTOMER_WALLET", Side.DEBIT, 7), line("FEE_REVENUE", Side.CREDIT, 7));
            planted.add(entry.id());
            tally.add(entry);
        }
        // A posting namespace is not a reversal's: the same key under the other door is its own subject.
        tally.add(entry(EntryType.REVERSAL, "payment-capture:", line("SETTLEMENT_CLEARING", Side.CREDIT, 3),
                line("CUSTOMER_WALLET", Side.DEBIT, 3)));
        tally.add(new JournalShapes.EntryShape(UUID.randomUUID(), EntryType.POSTING, DAY, Optional.of("gl-read-9f1c"),
                List.of(line("CUSTOMER_WALLET", Side.DEBIT, 1), line("FEE_REVENUE", Side.CREDIT, 1))));
        tally.add(new JournalShapes.EntryShape(UUID.randomUUID(), EntryType.ADJUSTMENT, DAY, Optional.empty(),
                List.of(line("CUSTOMER_WALLET", Side.DEBIT, 1), line("FEE_REVENUE", Side.CREDIT, 1))));

        assertThat(tally.findings()).extracting(BreakFinding::subject).containsExactly(
                "namespace:POSTING p14-010-probe:", "namespace:REVERSAL payment-capture:",
                "namespace:POSTING " + RuleConformance.UNNAMED, "namespace:ADJUSTMENT " + RuleConformance.UNNAMED);
        assertThat(tally.findings()).allSatisfy(finding ->
                assertThat(finding.type()).isEqualTo(BreakType.UNREGISTERED_POSTING));
        assertThat(tally.findings().get(0).evidence()).contains("entries=3").contains(planted.get(0).toString())
                .contains("postingDates=" + DAY + ".." + DAY);
        assertThat(tally.entries()).isEqualTo(6);
    }

    @Test
    @DisplayName("an unbalanced entry is one UNBALANCED_ENTRY - impossible by the ledger's trigger and still judged,"
            + " per currency and scale")
    void anUnbalancedEntryIsFound() {
        JournalShapes.EntryShape lopsided = entry(EntryType.POSTING, "transfer:",
                line("CUSTOMER_WALLET", Side.DEBIT, 100), line("CUSTOMER_WALLET", Side.CREDIT, 90));
        assertThat(conformance.judge(lopsided)).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(BreakType.UNBALANCED_ENTRY);
            assertThat(finding.subject()).isEqualTo("unbalanced:" + lopsided.id());
            assertThat(finding.evidence()).contains("EUR@2").doesNotContain("100").doesNotContain("90");
        });
        // Balanced per currency is balanced: two currencies each netting to zero raise nothing.
        assertThat(RuleConformance.unbalanced(List.of(line("CUSTOMER_WALLET", Side.DEBIT, 5),
                new JournalShapes.ShapeLine(1, "CUSTOMER_WALLET", Side.CREDIT, "EUR", 2, BigInteger.valueOf(5)),
                new JournalShapes.ShapeLine(2, "FX_POSITION", Side.DEBIT, "USD", 2, BigInteger.valueOf(9)),
                new JournalShapes.ShapeLine(3, "FX_POSITION", Side.CREDIT, "USD", 2, BigInteger.valueOf(9))))).isEmpty();
    }

    @Test
    @DisplayName("the version in force at the posting date judges: a rule whose version 2 changed its shape judges an"
            + " earlier entry by version 1 and a later one by version 2")
    void theVersionInForceAtThePostingDateJudges() {
        LocalDate change = LocalDate.of(2097, 1, 1);
        PostingRule one = rule(1, PostingRuleRegister.ORIGIN, "FEE_REVENUE");
        PostingRule two = rule(2, change, "PROCESSING_COSTS");
        RuleConformance versioned = new RuleConformance(new PostingRuleRegister(7, List.of(one, two)));
        JournalShapes.ShapeLine wallet = line("CUSTOMER_WALLET", Side.DEBIT, 3);

        JournalShapes.EntryShape before = new JournalShapes.EntryShape(UUID.randomUUID(), EntryType.POSTING,
                change.minusDays(1), Optional.of("probe:"), List.of(wallet, line("FEE_REVENUE", Side.CREDIT, 3)));
        JournalShapes.EntryShape after = new JournalShapes.EntryShape(UUID.randomUUID(), EntryType.POSTING, change,
                Optional.of("probe:"), List.of(wallet, line("FEE_REVENUE", Side.CREDIT, 3)));

        assertThat(versioned.judge(before)).isEmpty();
        assertThat(versioned.judge(after)).singleElement().satisfies(finding -> {
            assertThat(finding.type()).isEqualTo(BreakType.SHAPE_MISMATCH);
            assertThat(finding.evidence()).contains("PROBE v2");
            assertThat(finding.judgedBy()).isEqualTo("posting-rules:v7");
        });
    }

    @Test
    @DisplayName("a run counts the subjects already raised and never keeps them; past its cap it only counts")
    void knownSubjectsAreCountedAndTheCapHolds() {
        JournalShapes.EntryShape known = mismatch();
        RuleConformance.Tally tally =
                conformance.tally(java.util.Map.of("shape:" + known.id(), BreakStatus.RESOLVED), 2);
        tally.add(known);
        tally.add(mismatch());
        tally.add(mismatch());
        assertThat(tally.known()).containsExactly(java.util.Map.entry(BreakStatus.RESOLVED, 1));
        assertThat(tally.findings()).hasSize(2).noneMatch(finding -> finding.subject().equals("shape:" + known.id()));
        assertThat(tally.capped()).isFalse();
        tally.add(mismatch());
        assertThat(tally.findings()).hasSize(2);
        assertThat(tally.capped()).isTrue();
    }

    @Test
    @DisplayName("an identifier in a namespace's position folds to one subject (the review's M-12): a UUID prefix or an"
            + " id-like segment, never one break per entry; a namespace without one is kept as it is")
    void anIdentifierLikeNamespaceFoldsToOneSubject() {
        RuleConformance.Tally tally = conformance.tally(java.util.Map.of(), 100);
        for (String namespace : java.util.List.of("abcdef01-2345-4678-9abc-def012345678:",
                "abcdef99-2345-4678-9abc-def012345678:", "order-123456:", "order-987654:")) {
            tally.add(entry(EntryType.POSTING, namespace, line("CUSTOMER_WALLET", Side.DEBIT, 1),
                    line("FEE_REVENUE", Side.CREDIT, 1)));
        }
        tally.add(entry(EntryType.POSTING, "p14-010-probe:", line("CUSTOMER_WALLET", Side.DEBIT, 1),
                line("FEE_REVENUE", Side.CREDIT, 1)));
        assertThat(tally.findings()).extracting(BreakFinding::subject).containsExactly(
                "namespace:POSTING " + RuleConformance.ID_TOKEN + ":",
                "namespace:POSTING order-" + RuleConformance.ID_TOKEN + ":", "namespace:POSTING p14-010-probe:");
        assertThat(tally.findings().get(0).evidence()).contains("entries=2");
        assertThat(RuleConformance.folded("bnpl.financing:")).isEqualTo("bnpl.financing:");
    }

    // ------------------------------------------------------------------ fixtures

    private static JournalShapes.EntryShape mismatch() {
        return entry(EntryType.POSTING, "transfer:", line("CUSTOMER_WALLET", Side.DEBIT, 2),
                line("FEE_REVENUE", Side.CREDIT, 2));
    }

    private static JournalShapes.EntryShape entry(EntryType type, String namespace, JournalShapes.ShapeLine... lines) {
        List<JournalShapes.ShapeLine> ordered = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            JournalShapes.ShapeLine line = lines[i];
            ordered.add(new JournalShapes.ShapeLine(i, line.purpose(), line.side(), line.currency(), line.scale(),
                    line.minorUnits()));
        }
        return new JournalShapes.EntryShape(UUID.randomUUID(), type, DAY, Optional.of(namespace), ordered);
    }

    private static JournalShapes.ShapeLine line(String purpose, Side side, long minorUnits) {
        return new JournalShapes.ShapeLine(0, purpose, side, "EUR", 2, BigInteger.valueOf(minorUnits));
    }

    private static PostingRule rule(int version, LocalDate effectiveFrom, String credit) {
        return new PostingRule("PROBE", version, effectiveFrom, EntryType.POSTING, "probe:", "transfers",
                List.of("ProbePoster"), "a probe's event", "a probe's row", DatingClass.TODAY_CLOCK,
                CutoffReaction.NOT_REACHED, List.of(LineShape.required(Side.DEBIT, "CUSTOMER_WALLET"),
                        LineShape.required(Side.CREDIT, credit)), "this suite");
    }
}
