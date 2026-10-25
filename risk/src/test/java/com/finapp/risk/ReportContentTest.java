package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The artefact's values (`P13-TSK-021`; {@code PHASE_13_PLAN.md} section 12.9; ADR-0111 decisions 5-7; owner decision C4):
 * {@link ReportContent} carries section 12.9's categories - the jurisdiction and format version, the reporting entity's
 * reference, the subject and its accounts, the transactions referenced, the grounds and the narrative, the attachments'
 * references - each bounded and shaped, the narrative screened and never in its {@code toString}; the port's records hold
 * their own shapes ({@link RegulatoryFilingProvider.Receipt}: an acknowledgement has the authority's reference, a
 * rejection its code, nothing-received nothing); {@link FilingOutcome} reads a submission's contradictory
 * {@code NOT_RECEIVED} as unknown; {@link FilingInquiry} submits on the first attempt only and is asked by the report's
 * own reference. The adapter's additions (the EU reference's fields) are {@code app}'s {@code SimulatedFilingProviderTest}.
 */
@DisplayName("the report's content, the port's receipts, the outcome and the inquiry (P13-TSK-021)")
class ReportContentTest {

    private static final UUID PARTY = UUID.fromString("0192e1a0-0000-7000-8000-000000000021");

    @Test
    @DisplayName("section 12.9's categories, each held: the jurisdiction shaped, the format version 1 to 999, the"
            + " reporting entity's reference opaque, the references bounded and once each, the grounds closed codes")
    void theCategoriesAreHeld() {
        ReportContent content = content("EU-REF", List.of(id()), List.of(id(), id()), List.of("STRUCTURING"), List.of());
        assertThat(content.jurisdiction()).isEqualTo("EU-REF");
        assertThat(content.subject()).isEqualTo(PARTY);
        assertThat(content.transactions()).hasSize(2);
        assertThatThrownBy(() -> content("eu-ref", List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportContent("EU-REF", 0, "ENTITY", PARTY, List.of(), List.of(), List.of(),
                "a narrative", List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReportContent("EU-REF", 1, "AN ENTITY", PARTY, List.of(), List.of(), List.of(),
                "a narrative", List.of())).as("an opaque reference").isInstanceOf(IllegalArgumentException.class);
        UUID twice = id();
        assertThatThrownBy(() -> content("EU-REF", List.of(twice, twice), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content("EU-REF", List.of(), IntStream.rangeClosed(0, ReportContent.TRANSACTIONS_BOUND)
                .mapToObj(i -> id()).toList(), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content("EU-REF", List.of(), List.of(), List.of("structuring"), List.of()))
                .as("a ground is a closed code").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content("EU-REF", List.of(), List.of(), List.of("STRUCTURING", "STRUCTURING"),
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content("EU-REF", List.of(), List.of(), List.of(), IntStream.rangeClosed(0,
                ReportContent.ATTACHMENTS_BOUND).mapToObj(i -> id()).toList()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ReportContent.ACCOUNTS_BOUND).isEqualTo(MonitoredParties.BOUND);
        assertThat(ReportContent.TRANSACTIONS_BOUND).isEqualTo(AlertEvidence.BOUND);
    }

    @Test
    @DisplayName("the narrative: required, bounded, screened for card-number and account shapes - and never in a"
            + " toString, which names codes and counts")
    void theNarrativeIsScreenedAndRedacted() {
        assertThat(ReportContent.narrated("a structured pattern")).isEqualTo("a structured pattern");
        for (String refused : new String[] {null, "", "   ", "x".repeat(ReportContent.NARRATIVE_BOUND + 1),
                "the card 4111 1111 1111 1111 paid", "into DE89 3704 0044 0532 0130 00"}) {
            assertThatThrownBy(() -> ReportContent.narrated(refused)).isInstanceOf(RiskReasons.ReasonRequired.class);
        }
        ReportContent content = new ReportContent("EU-REF", 1, "FINAPP-ENTITY", PARTY, List.of(id()), List.of(),
                List.of("STRUCTURING"), "a secret structured pattern", List.of());
        assertThat(content.toString()).contains("EU-REF").contains("accounts=1").doesNotContain("secret")
                .doesNotContain(PARTY.toString());
        RegulatoryReports.Draft draft = new RegulatoryReports.Draft("EU-REF", "a secret", null, "a reason");
        assertThat(draft.attachments()).isEmpty();
        assertThat(draft.toString()).doesNotContain("secret").doesNotContain("a reason");
    }

    @Test
    @DisplayName("the port's receipts hold their shapes: an acknowledgement has the authority's reference, a rejection its"
            + " code, nothing-received no reference; a declaration is shaped")
    void theReceiptsHoldTheirShapes() {
        assertThat(RegulatoryFilingProvider.Receipt.acknowledged("FIU-1").providerReference()).contains("FIU-1");
        assertThatThrownBy(() -> new RegulatoryFilingProvider.Receipt(RegulatoryFilingProvider.Outcome.ACKNOWLEDGED,
                Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegulatoryFilingProvider.Receipt(RegulatoryFilingProvider.Outcome.REJECTED,
                Optional.empty(), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegulatoryFilingProvider.Receipt.rejected(Optional.empty(), "bad code"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegulatoryFilingProvider.Receipt(RegulatoryFilingProvider.Outcome.NOT_RECEIVED,
                Optional.of("FIU-1"), Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegulatoryFilingProvider.Receipt.unknown(Optional.of("not opaque!")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegulatoryFilingProvider.Declaration("EU-REF", 1, "AN ENTITY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new RegulatoryFilingProvider.Declaration("EU-REF", 1, "FINAPP-ENTITY").formatVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("the outcome: acknowledged, rejected and unknown as answered; a submission's own NOT_RECEIVED read as"
            + " unknown - one inquiry, never an answer nobody gave")
    void theOutcomeReadsTheReceipt() {
        assertThat(FilingOutcome.of(RegulatoryFilingProvider.Receipt.acknowledged("FIU-1"), true).kind())
                .isEqualTo(FilingOutcome.Kind.ACKNOWLEDGED);
        FilingOutcome rejected = FilingOutcome.of(RegulatoryFilingProvider.Receipt.rejected(Optional.empty(),
                "FORMAT_INVALID"), false);
        assertThat(rejected.kind()).isEqualTo(FilingOutcome.Kind.REJECTED);
        assertThat(rejected.rejectionCode()).contains("FORMAT_INVALID");
        assertThat(rejected.concludes()).isTrue();
        FilingOutcome contradictory = FilingOutcome.of(RegulatoryFilingProvider.Receipt.notReceived(), true);
        assertThat(contradictory.kind()).isEqualTo(FilingOutcome.Kind.UNKNOWN);
        assertThat(contradictory.concludes()).isFalse();
        assertThatThrownBy(() -> new FilingOutcome(FilingOutcome.Kind.ACKNOWLEDGED, Optional.empty(), Optional.empty(),
                true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the inquiry: asked by the report's own reference; the first attempt submits, every later one inquires")
    void theInquirySubmitsOnlyFirst() {
        RegulatoryReportId report = RegulatoryReportId.of(id());
        FilingInquiry first = new FilingInquiry(report, FilingInquiry.referenceOf(report), 1);
        assertThat(first.submits()).isTrue();
        assertThat(new FilingInquiry(report, FilingInquiry.referenceOf(report), 2).submits()).isFalse();
        assertThat(FilingInquiry.referenceOf(report)).isEqualTo(report.value().toString());
        assertThatThrownBy(() -> new FilingInquiry(report, "another reference", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FilingInquiry(report, FilingInquiry.referenceOf(report), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static ReportContent content(String jurisdiction, List<UUID> accounts, List<UUID> transactions,
            List<String> grounds, List<UUID> attachments) {
        return new ReportContent(jurisdiction, 1, "FINAPP-ENTITY", PARTY, accounts, transactions, grounds,
                "a structured pattern", attachments);
    }

    private static UUID id() {
        return EvaluationWorld.IDS.next();
    }
}
