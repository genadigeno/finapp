package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.RegulatoryFilingProvider;
import com.finapp.risk.RegulatoryReportId;
import com.finapp.risk.ReportContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SimulatedFilingProvider} (`P13-TSK-021`; {@code PHASE_13_PLAN.md} section 12.9; ADR-0111 decisions 6-7; owner
 * decision C4; the {@code ScreeningAdapter} precedent; {@code DESIGN-021} D13): the EU reference's declaration
 * ({@code EU-REF}, format 1, the configured reporting entity) and nothing else; deterministic outcomes by the knob - an
 * acknowledgement, a rejection, the {@code UNKNOWN} path acknowledged on inquiry, a standing unknown, an unreachable
 * authority; references derived from the filing reference, so two instances agree with no state; the adapter's own
 * additions in the rendering, the platform's categories all present, the narrative's line breaks escaped; a format it
 * does not speak, a document past its bound and a reference not the report's own refused.
 */
@DisplayName("the simulated FIU: the EU reference's adapter data, deterministic by the knob, stateless (P13-TSK-021)")
class SimulatedFilingProviderTest {

    private static final String ENTITY = "FINAPP-EU-REF-0001";
    private static final UUID PARTY = UUID.fromString("0192e1a0-0000-7000-8000-000000000210");

    @Test
    @DisplayName("the declaration: EU-REF, format version 1, the configured reporting entity - and no other jurisdiction")
    void theDeclaration() {
        SimulatedFilingProvider provider = provider(SimulatedFilingProvider.Behaviour.ACKNOWLEDGE);
        assertThat(provider.declared("EU-REF")).contains(new RegulatoryFilingProvider.Declaration("EU-REF", 1, ENTITY));
        assertThat(provider.declared("DE")).isEmpty();
        assertThat(provider.declared(null)).isEmpty();
    }

    @Test
    @DisplayName("deterministic by the knob: acknowledge, reject, unknown then acknowledged on inquiry, a standing"
            + " unknown, unavailable - every answer's reference derived from the filing reference")
    void deterministicByTheKnob() {
        RegulatoryFilingProvider.Request request = request(content(ENTITY, "a structured pattern"));
        String authority = SimulatedFilingProvider.authorityReference(request.filingReference());
        assertThat(authority).matches("SIM-FIU-[0-9A-F]{16}");

        assertThat(provider(SimulatedFilingProvider.Behaviour.ACKNOWLEDGE).file(request))
                .isEqualTo(RegulatoryFilingProvider.Receipt.acknowledged(authority));
        assertThat(provider(SimulatedFilingProvider.Behaviour.REJECT).file(request)).isEqualTo(
                RegulatoryFilingProvider.Receipt.rejected(Optional.of(authority),
                        SimulatedFilingProvider.REJECTED_BY_AUTHORITY));
        SimulatedFilingProvider unknownThen = provider(SimulatedFilingProvider.Behaviour.UNKNOWN_THEN_ACKNOWLEDGE);
        assertThat(unknownThen.file(request).outcome()).isEqualTo(RegulatoryFilingProvider.Outcome.UNKNOWN);
        assertThat(unknownThen.inquire(request.filingReference()))
                .isEqualTo(RegulatoryFilingProvider.Receipt.acknowledged(authority));
        SimulatedFilingProvider unknown = provider(SimulatedFilingProvider.Behaviour.UNKNOWN);
        assertThat(unknown.inquire(request.filingReference()).outcome())
                .isEqualTo(RegulatoryFilingProvider.Outcome.UNKNOWN);
        SimulatedFilingProvider unavailable = provider(SimulatedFilingProvider.Behaviour.UNAVAILABLE);
        assertThatThrownBy(() -> unavailable.file(request))
                .isInstanceOf(SimulatedFilingProvider.FilingUnavailable.class);
        assertThatThrownBy(() -> unavailable.inquire(request.filingReference()))
                .isInstanceOf(SimulatedFilingProvider.FilingUnavailable.class);
        assertThat(unavailable.declared("EU-REF")).as("the declaration is data, never a call").isPresent();
    }

    @Test
    @DisplayName("stateless: two instances' answers to one filing reference agree, submission and inquiry alike; it never"
            + " answers NOT_RECEIVED")
    void statelessAcrossInstances() {
        RegulatoryFilingProvider.Request request = request(content(ENTITY, "a structured pattern"));
        for (SimulatedFilingProvider.Behaviour behaviour : SimulatedFilingProvider.Behaviour.values()) {
            if (behaviour == SimulatedFilingProvider.Behaviour.UNAVAILABLE) {
                continue;
            }
            SimulatedFilingProvider one = provider(behaviour);
            SimulatedFilingProvider two = provider(behaviour);
            assertThat(one.file(request)).isEqualTo(two.file(request));
            assertThat(two.inquire(request.filingReference())).isEqualTo(one.inquire(request.filingReference()));
            assertThat(two.inquire(request.filingReference()).outcome())
                    .isNotEqualTo(RegulatoryFilingProvider.Outcome.NOT_RECEIVED);
        }
    }

    @Test
    @DisplayName("the EU reference's rendering: the adapter's additions (report type, legal basis to be verified) and every"
            + " platform category; the narrative's line breaks escaped; deterministic")
    void theRendering() {
        UUID account = UUID.fromString("0192e1a0-0000-7000-8000-000000000211");
        UUID transaction = UUID.fromString("0192e1a0-0000-7000-8000-000000000212");
        ReportContent content = new ReportContent("EU-REF", 1, ENTITY, PARTY, List.of(account), List.of(transaction),
                List.of("RAPID_IN_OUT", "STRUCTURING"), "split below the threshold\nthen moved out", List.of());
        String rendered = SimulatedFilingProvider.render("0192e1a0-0000-7000-8000-000000000213", content);
        assertThat(rendered.lines()).containsExactly(
                "format: EU-REF/1",
                "reportType: " + SimulatedFilingProvider.REPORT_TYPE,
                "legalBasis: " + SimulatedFilingProvider.LEGAL_BASIS,
                "filingReference: 0192e1a0-0000-7000-8000-000000000213",
                "reportingEntity: " + ENTITY,
                "subject: PARTY/" + PARTY,
                "accounts: " + account,
                "transactions: " + transaction,
                "grounds: RAPID_IN_OUT,STRUCTURING",
                "narrative: split below the threshold\\nthen moved out",
                "attachments: ");
        assertThat(SimulatedFilingProvider.render("0192e1a0-0000-7000-8000-000000000213", content)).isEqualTo(rendered);
        assertThat(SimulatedFilingProvider.LEGAL_BASIS).contains("TO-BE-VERIFIED");
    }

    @Test
    @DisplayName("an authority's refusals: another reporting entity or format - FORMAT_UNSUPPORTED; a document past the"
            + " bound - DOCUMENT_TOO_LARGE; a reference not the report's own or not an identifier - refused")
    void theAuthoritysRefusals() {
        SimulatedFilingProvider provider = provider(SimulatedFilingProvider.Behaviour.ACKNOWLEDGE);
        assertThat(provider.file(request(content("ANOTHER-ENTITY", "a pattern"))).rejectionCode())
                .contains(SimulatedFilingProvider.FORMAT_UNSUPPORTED);
        List<UUID> many = java.util.stream.IntStream.range(0, ReportContent.TRANSACTIONS_BOUND)
                .mapToObj(i -> UUID.randomUUID()).toList();
        ReportContent large = new ReportContent("EU-REF", 1, ENTITY, PARTY, List.of(), many, List.of(),
                "y".repeat(ReportContent.NARRATIVE_BOUND), List.of());
        assertThat(SimulatedFilingProvider.render(UUID.randomUUID().toString(), large).length())
                .as("the bound is above any report the platform can draft").isLessThan(
                        SimulatedFilingProvider.MAX_RENDERED_BYTES);
        assertThat(provider.file(request(large)).outcome()).isEqualTo(RegulatoryFilingProvider.Outcome.ACKNOWLEDGED);
        RegulatoryReportId report = RegulatoryReportId.of(UUID.fromString("0192e1a0-0000-7000-8000-000000000214"));
        assertThatThrownBy(() -> provider.file(new RegulatoryFilingProvider.Request(report,
                "0192e1a0-0000-7000-8000-000000000215", content(ENTITY, "x")))).isInstanceOf(
                IllegalArgumentException.class);
        assertThatThrownBy(() -> provider.inquire("not a reference")).isInstanceOf(IllegalArgumentException.class);
    }

    // -----------------------------------------------------------------

    private static SimulatedFilingProvider provider(SimulatedFilingProvider.Behaviour behaviour) {
        return new SimulatedFilingProvider(behaviour, ENTITY);
    }

    private static ReportContent content(String entity, String narrative) {
        return new ReportContent("EU-REF", 1, entity, PARTY, List.of(), List.of(), List.of(), narrative, List.of());
    }

    private static RegulatoryFilingProvider.Request request(ReportContent content) {
        RegulatoryReportId report = RegulatoryReportId.of(UUID.fromString("0192e1a0-0000-7000-8000-000000000216"));
        return new RegulatoryFilingProvider.Request(report, report.value().toString(), content);
    }
}
