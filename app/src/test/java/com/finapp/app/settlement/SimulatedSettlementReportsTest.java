package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fixture keeps its word (`P8-TSK-008`): a clean render PARSES whole through the real
 * adapter, and each parse-level fault produces exactly the defect it claims — so the later
 * suites (`P8-TSK-009`…) that drive rejections from this fixture rely on pinned behaviour,
 * not on a rendering accident.
 */
@DisplayName("the simulated PSP's report fixture (P8-TSK-008)")
class SimulatedSettlementReportsTest {

    private static SimulatedSettlementReports genuine() {
        return new SimulatedSettlementReports(
                        "PSPB-FIX-01", "EUR", LocalDate.parse("2026-09-29"), "PSP-REM-777001")
                .with(SimulatedSettlementReports.Line.capture(
                        "PSP-CAP-9001", "44400012345678901", "ORD-9001", "100.00", "1.75"))
                .with(SimulatedSettlementReports.Line.refund("PSP-REF-9002", "ORD-9001",
                        "40.25"))
                .with(SimulatedSettlementReports.Line.chargeback("DSP-9003", "100.00"))
                .with(SimulatedSettlementReports.Line.unknown("MISC-9004", "5.00"));
    }

    @Test
    @DisplayName("a clean render screens clean and parses whole through the real adapter")
    void aCleanRenderParsesWhole() {
        byte[] report = genuine().render();
        assertThat(SimPspCsvFormat.INSTANCE.screen(report).finding()).isEmpty();
        SettlementFormat.Result result = SimPspCsvFormat.INSTANCE.parse(report);
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        // 4 records, the capture's fee split into its own PROCESSING_FEE line.
        assertThat(((SettlementFormat.Result.Parsed) result).batch().lines()).hasSize(5);
    }

    @Test
    @DisplayName("each parse-level fault produces exactly the defect it claims")
    void eachFaultKeepsItsWord() {
        record Case(SimulatedSettlementReports.Fault fault, RejectionCode expected) {}
        for (Case each :
                java.util.List.of(
                        new Case(SimulatedSettlementReports.Fault.MALFORMED_FIELD,
                                RejectionCode.MALFORMED),
                        new Case(SimulatedSettlementReports.Fault.BAD_TRAILER_COUNT,
                                RejectionCode.CONTROL_TOTAL_MISMATCH),
                        new Case(SimulatedSettlementReports.Fault.BAD_TRAILER_NET,
                                RejectionCode.CONTROL_TOTAL_MISMATCH),
                        new Case(SimulatedSettlementReports.Fault.UNKNOWN_LINE,
                                RejectionCode.MALFORMED),
                        new Case(SimulatedSettlementReports.Fault.DUPLICATE_LINE,
                                RejectionCode.MALFORMED),
                        new Case(SimulatedSettlementReports.Fault.WRONG_CURRENCY,
                                RejectionCode.MALFORMED))) {
            byte[] report = genuine().faulted(each.fault()).render();
            SettlementFormat.Result result = SimPspCsvFormat.INSTANCE.parse(report);
            assertThat(result)
                    .as("fault %s rejects", each.fault())
                    .isInstanceOf(SettlementFormat.Result.Rejected.class);
            assertThat(((SettlementFormat.Result.Rejected) result).code())
                    .as("fault %s's verdict", each.fault())
                    .isEqualTo(each.expected());
        }
    }

    @Test
    @DisplayName("instrument data in free text is the DOOR's to refuse: the screen finds the"
            + " PAN and the account identifier, so such a report is never stored")
    void theFreeTextFaultsAreRefusedAtTheScreen() {
        DeliveryScreen.Screening pan =
                SimPspCsvFormat.INSTANCE.screen(
                        genuine().faulted(SimulatedSettlementReports.Fault.PAN_IN_FREE_TEXT)
                                .render());
        assertThat(pan.finding())
                .hasValueSatisfying(
                        finding ->
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER));
        DeliveryScreen.Screening iban =
                SimPspCsvFormat.INSTANCE.screen(
                        genuine().faulted(SimulatedSettlementReports.Fault.IBAN_IN_FREE_TEXT)
                                .render());
        assertThat(iban.finding())
                .hasValueSatisfying(
                        finding ->
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER));
    }
}
