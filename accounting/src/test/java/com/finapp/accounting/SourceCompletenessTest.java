package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Source completeness (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-12}, {@code INV-REC-02}): each proof's
 * finding one {@code SOURCE_DISCREPANCY} on {@code <PROOF>:<subject>}, judged by {@code source-proofs:v1}; a proof the
 * answer omits, answers twice or answers incomplete - and a source that could not be read - unavailable, never passing.
 * Hermetic; the adapter over the nine real proofs is {@code ProductSourceProofsTest}'s.
 */
@DisplayName("source completeness: the product proofs' findings, never a false pass (P14-TSK-010)")
class SourceCompletenessTest {

    private final SourceCompleteness judge = new SourceCompleteness();

    @Test
    @DisplayName("the catalogue is the entry's nine proofs over seven sources, each proof on one source")
    void theCatalogueIsTheNineProofs() {
        assertThat(Arrays.stream(SourceProofs.Proof.values()).map(Enum::name).toList()).containsExactly(
                "LOAN_SUBLEDGER", "LOAN_REPLAY", "LENDING_CAPITAL", "BNPL_SUBLEDGER", "BNPL_THREE_WAY", "FX_BOOKS",
                "POSITION", "MERCHANT_PAYABLE", "PROJECTION");
        assertThat(Arrays.stream(SourceProofs.Source.values()).flatMap(source -> source.proofs().stream()).toList())
                .containsExactly(SourceProofs.Proof.values());
        assertThat(SourceProofs.Source.LENDING.proofs())
                .containsExactly(SourceProofs.Proof.LOAN_SUBLEDGER, SourceProofs.Proof.LOAN_REPLAY);
        assertThat(SourceCompleteness.JUDGED_BY).isEqualTo("source-proofs:v1");
    }

    @Test
    @DisplayName("a proof's finding is one SOURCE_DISCREPANCY on the proof's subject, its evidence the proof's own")
    void aSourceDiscrepancyIsOneFinding() {
        SourceCompleteness.Judged judged = judge.judge(SourceProofs.Source.LENDING, List.of(
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_SUBLEDGER, true,
                        List.of(new SourceProofs.Discrepancy("loan-7", "fails=[LOAN_PRINCIPAL]"))),
                new SourceProofs.Reading(SourceProofs.Proof.LOAN_REPLAY, true, List.of())));

        assertThat(judged.unavailable()).isEmpty();
        assertThat(judged.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.check()).isEqualTo(CloseCheckCode.SOURCE_COMPLETENESS);
            assertThat(finding.type()).isEqualTo(BreakType.SOURCE_DISCREPANCY);
            assertThat(finding.subject()).isEqualTo("LOAN_SUBLEDGER:loan-7");
            assertThat(finding.evidence()).contains("proof=LOAN_SUBLEDGER").contains("fails=[LOAN_PRINCIPAL]");
            assertThat(finding.judgedBy()).isEqualTo("source-proofs:v1");
        });
    }

    @Test
    @DisplayName("an unavailable proof is never a pass: omitted, answered twice, incomplete, another source's, or the"
            + " source unreadable")
    void anUnavailableProofIsNeverAPass() {
        SourceProofs.Reading subledger = new SourceProofs.Reading(SourceProofs.Proof.BNPL_SUBLEDGER, true, List.of());
        SourceProofs.Reading threeWay = new SourceProofs.Reading(SourceProofs.Proof.BNPL_THREE_WAY, true, List.of());

        assertThat(judge.judge(SourceProofs.Source.BNPL, List.of(subledger)).unavailable())
                .as("a proof the answer omits").containsExactly(SourceProofs.Proof.BNPL_THREE_WAY);
        assertThat(judge.judge(SourceProofs.Source.BNPL, List.of(subledger, threeWay, threeWay)).unavailable())
                .as("a proof answered twice").containsExactly(SourceProofs.Proof.BNPL_THREE_WAY);
        assertThat(judge.judge(SourceProofs.Source.PROJECTION, List.of(new SourceProofs.Reading(
                SourceProofs.Proof.PROJECTION, false, List.of()))).unavailable())
                .as("a reading that could not judge everything").containsExactly(SourceProofs.Proof.PROJECTION);
        assertThat(judge.judge(SourceProofs.Source.FX_BOOKS, List.of(subledger)).unavailable())
                .as("another source's answer").containsExactly(SourceProofs.Proof.FX_BOOKS);
        assertThat(judge.unreadable(SourceProofs.Source.LENDING).unavailable())
                .containsExactly(SourceProofs.Proof.LOAN_SUBLEDGER, SourceProofs.Proof.LOAN_REPLAY);
        assertThat(judge.judge(SourceProofs.Source.BNPL, List.of(subledger, threeWay)).unavailable()).isEmpty();
    }
}
