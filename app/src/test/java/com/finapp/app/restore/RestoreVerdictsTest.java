package com.finapp.app.restore;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.SourceFindingKind;
import com.finapp.accounting.SourceProofs;
import com.finapp.app.restore.RestoreVerdicts.PeriodJudgement;
import com.finapp.app.restore.RestoreVerdicts.SourceReading;
import com.finapp.app.restore.RestoreVerdicts.Verdict;
import com.finapp.ledger.TrialBalance;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The restore verification's judgements (`P15-TSK-030`): no answer of the platform's checks becomes a pass unless it is one. Each case
 * is a way a verification could pass over nothing, or over an answer it never read.
 */
@DisplayName("the restore verification's verdicts: never a pass over nothing or over an answer not read (P15-TSK-030)")
class RestoreVerdictsTest {

    private static final YearMonth MONTH = YearMonth.of(2026, 11);

    @Test
    @DisplayName("the trial balance: an empty journal fails, a currency out of balance fails and is named, a balanced journal passes")
    void theTrialBalanceFailsOverNothingAndNamesTheCurrency() {
        assertThat(RestoreVerdicts.trialBalance(new TrialBalance.Report(0, List.of())).passed()).isFalse();
        Verdict out = RestoreVerdicts.trialBalance(new TrialBalance.Report(2, List.of(CurrencyCode.of("EUR"))));
        assertThat(out.passed()).isFalse();
        assertThat(out.detail()).contains("EUR").contains("1 of 2");
        assertThat(RestoreVerdicts.trialBalance(new TrialBalance.Report(2, List.of())).passed()).isTrue();
    }

    @Test
    @DisplayName("GL = ledger: a journal with no period judged fails; an unreadable period (a lock that pinned nothing) fails; a finding fails")
    void glEqualsLedgerFailsOnNothingJudgedOnAnUnreadablePeriodAndOnAFinding() {
        assertThat(RestoreVerdicts.glEqualsLedger(List.of(), 10).passed()).as("entries and no period").isFalse();
        assertThat(RestoreVerdicts.glEqualsLedger(List.of(), 0).passed()).as("nothing at all").isFalse();
        PeriodJudgement lockedWithoutPin = new PeriodJudgement(MONTH, PeriodStatus.LOCKED, Optional.empty(),
                Optional.of("com.finapp.accounting.GlPins$NoActiveMapping"), List.of());
        Verdict unpinned = RestoreVerdicts.glEqualsLedger(List.of(lockedWithoutPin), 10);
        assertThat(unpinned.passed()).isFalse();
        assertThat(unpinned.detail()).contains("LOCKED unreadable").contains("1 locked");
        PeriodJudgement disagrees = new PeriodJudgement(MONTH, PeriodStatus.OPEN, Optional.of(1), Optional.empty(), List.of("currency:EUR"));
        assertThat(RestoreVerdicts.glEqualsLedger(List.of(disagrees), 10).detail()).contains("currency:EUR");
        assertThat(RestoreVerdicts.glEqualsLedger(List.of(disagrees), 10).passed()).isFalse();
        PeriodJudgement locked = new PeriodJudgement(MONTH.minusMonths(1), PeriodStatus.CLOSED, Optional.of(1), Optional.empty(), List.of());
        PeriodJudgement open = new PeriodJudgement(MONTH, PeriodStatus.OPEN, Optional.of(2), Optional.empty(), List.of());
        Verdict agrees = RestoreVerdicts.glEqualsLedger(List.of(locked, open), 10);
        assertThat(agrees.passed()).isTrue();
        assertThat(agrees.detail()).contains("periods agreeing: 2 (1 locked").contains("2026-10 CLOSED v1").contains("2026-11 OPEN v2");
    }

    @Test
    @DisplayName("the proofs: the two checks cover every proof of the catalogue exactly once, read from the enum")
    void theTwoProofChecksCoverTheCatalogueOnce() {
        Set<SourceProofs.Proof> both = EnumSet.copyOf(RestoreVerdicts.projectionProofs());
        both.addAll(RestoreVerdicts.productProofs());
        assertThat(both).isEqualTo(EnumSet.allOf(SourceProofs.Proof.class));
        assertThat(RestoreVerdicts.projectionProofs()).doesNotContainAnyElementsOf(RestoreVerdicts.productProofs());
    }

    @Test
    @DisplayName("the proofs: one never asked, one unreadable, one incomplete, one discrepant, one answered twice: each fails by name")
    void eachWayAProofIsNotReadFails() {
        Set<SourceProofs.Proof> all = EnumSet.allOf(SourceProofs.Proof.class);
        assertThat(RestoreVerdicts.proofs("p", all, whole()).passed()).as("every proof whole").isTrue();
        assertThat(RestoreVerdicts.proofs("p", EnumSet.noneOf(SourceProofs.Proof.class), whole()).passed()).as("over nothing").isFalse();

        List<SourceReading> missing = new ArrayList<>(whole());
        missing.removeIf(source -> source.source() == SourceProofs.Source.FX_BOOKS);
        assertThat(RestoreVerdicts.proofs("p", all, missing).detail()).contains("FX_BOOKS never asked");

        List<SourceReading> unreadable = replaced(SourceProofs.Source.POSITION,
                new SourceReading(SourceProofs.Source.POSITION, List.of(), Optional.of("java.lang.IllegalStateException")));
        assertThat(RestoreVerdicts.proofs("p", all, unreadable).detail()).contains("POSITION unreadable");

        List<SourceReading> incomplete = replaced(SourceProofs.Source.PROJECTION, new SourceReading(SourceProofs.Source.PROJECTION,
                List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, false, List.of())), Optional.empty()));
        Verdict projection = RestoreVerdicts.proofs("p", RestoreVerdicts.projectionProofs(), incomplete);
        assertThat(projection.passed()).isFalse();
        assertThat(projection.detail()).contains("PROJECTION incomplete");

        List<SourceReading> discrepant = replaced(SourceProofs.Source.PROJECTION, new SourceReading(SourceProofs.Source.PROJECTION,
                List.of(new SourceProofs.Reading(SourceProofs.Proof.PROJECTION, true,
                        List.of(new SourceProofs.Discrepancy("0190b7d2-0000-7000-8000-000000000001", "verdict=DRIFTING")))), Optional.empty()));
        assertThat(RestoreVerdicts.proofs("p", RestoreVerdicts.projectionProofs(), discrepant).detail())
                .contains("1 discrepant").contains("0190b7d2-0000-7000-8000-000000000001");

        List<SourceReading> twice = replaced(SourceProofs.Source.FX_BOOKS, new SourceReading(SourceProofs.Source.FX_BOOKS,
                List.of(clean(SourceProofs.Proof.FX_BOOKS), clean(SourceProofs.Proof.FX_BOOKS)), Optional.empty()));
        assertThat(RestoreVerdicts.proofs("p", all, twice).detail()).contains("FX_BOOKS answered 2 times");
    }

    @Test
    @DisplayName("the census: a walk short of the journal fails (the counter behind the restore), an unrecorded finding fails, a recorded one is disclosed")
    void theCensusFailsShortOfTheJournalAndOnANewFinding() {
        UUID recorded = UUID.fromString("0190b7d2-0000-7000-8000-00000000000a");
        UUID fresh = UUID.fromString("0190b7d2-0000-7000-8000-00000000000b");
        assertThat(RestoreVerdicts.census(0, 0, Map.of(), Set.of()).passed()).as("an empty journal").isFalse();
        Verdict shortWalk = RestoreVerdicts.census(4, 10, Map.of(), Set.of());
        assertThat(shortWalk.passed()).isFalse();
        assertThat(shortWalk.detail()).contains("walked 4 of the journal's 10").contains("transaction counter");
        Verdict unrecorded = RestoreVerdicts.census(10, 10, Map.of(fresh, SourceFindingKind.UNSOURCED, recorded, SourceFindingKind.MISMATCHED),
                Set.of(recorded));
        assertThat(unrecorded.passed()).isFalse();
        assertThat(unrecorded.detail()).contains("1 unsourced and 0 mismatched").contains(fresh.toString()).doesNotContain(recorded.toString());
        Verdict disclosed = RestoreVerdicts.census(10, 10, Map.of(recorded, SourceFindingKind.MISMATCHED), Set.of(recorded));
        assertThat(disclosed.passed()).isTrue();
        assertThat(disclosed.detail()).contains("1 found that were recorded before the backup");
        assertThat(RestoreVerdicts.census(10, 10, Map.of(), Set.of()).detail()).isEqualTo("walked 10 of 10 entries: no new finding");
    }

    @Test
    @DisplayName("a verdict is one line the script can split: the separator and line breaks never leave the detail")
    void aVerdictIsOneLine() {
        assertThat(Verdict.fail("c", "a|b\nc\rd").line()).isEqualTo("FAIL|c|a/b c d");
        assertThat(Verdict.pass("c", "x").line()).isEqualTo("PASS|c|x");
        assertThat(RestoreVerdicts.unreadable("c", new IllegalStateException("a row's value")).detail())
                .as("the class, never the message").isEqualTo("could not be read: java.lang.IllegalStateException");
        assertThat(RestoreVerdicts.CHECKS).hasSize(5).doesNotHaveDuplicates();
    }

    private static List<SourceReading> whole() {
        List<SourceReading> sources = new ArrayList<>();
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            sources.add(new SourceReading(source, source.proofs().stream().map(RestoreVerdictsTest::clean).toList(), Optional.empty()));
        }
        return sources;
    }

    private static SourceProofs.Reading clean(SourceProofs.Proof proof) {
        return new SourceProofs.Reading(proof, true, List.of());
    }

    private static List<SourceReading> replaced(SourceProofs.Source source, SourceReading with) {
        List<SourceReading> sources = new ArrayList<>(whole());
        sources.replaceAll(reading -> reading.source() == source ? with : reading);
        return sources;
    }
}
