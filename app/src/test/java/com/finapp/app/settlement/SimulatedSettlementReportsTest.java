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
    @DisplayName("the matching-level lines keep their word (P8-TST-001): a correction parses as"
            + " a COUNTERPARTY_ADJUSTMENT naming its original, a late line carries its own"
            + " settlement date, a repeated record without its fee is ONE line of the same"
            + " fingerprint, and the trailer nets them all")
    void theMatchingLevelLinesKeepTheirWord() {
        LocalDate late = LocalDate.parse("2026-10-09");
        SimulatedSettlementReports.Line sale =
                SimulatedSettlementReports.Line.capture("PSP-CAP-9101", "", "", "20.00", "0.55");
        byte[] report =
                new SimulatedSettlementReports(
                                "PSPB-FIX-02", "EUR", LocalDate.parse("2026-09-29"),
                                "PSP-REM-777002")
                        .with(sale)
                        .with(SimulatedSettlementReports.Line.capture(
                                        "PSP-CAP-9102", "", "", "30.00", "0.70")
                                .settledOn(late))
                        .with(sale.withoutFee())
                        .with(SimulatedSettlementReports.Line.adjustment("PSP-CAP-9101", "-0.50"))
                        .render();
        assertThat(SimPspCsvFormat.INSTANCE.screen(report).finding()).isEmpty();
        SettlementFormat.Result result = SimPspCsvFormat.INSTANCE.parse(report);
        assertThat(result)
                .as("the trailer's net is the lines' own fold: 20.00 + 30.00 + 20.00 - 0.50 - fees")
                .isInstanceOf(SettlementFormat.Result.Parsed.class);
        java.util.List<com.finapp.settlement.format.ParsedLine> lines =
                ((SettlementFormat.Result.Parsed) result).batch().lines();
        assertThat(lines).extracting(com.finapp.settlement.format.ParsedLine::type)
                .as("two fee splits, the repeated record without one, the correction")
                .containsExactly(
                        com.finapp.settlement.SettlementLineType.CAPTURE,
                        com.finapp.settlement.SettlementLineType.PROCESSING_FEE,
                        com.finapp.settlement.SettlementLineType.CAPTURE,
                        com.finapp.settlement.SettlementLineType.PROCESSING_FEE,
                        com.finapp.settlement.SettlementLineType.CAPTURE,
                        com.finapp.settlement.SettlementLineType.COUNTERPARTY_ADJUSTMENT);
        assertThat(lines.get(2).settlementDate()).as("the late line settles on its own date")
                .contains(late);
        assertThat(lines.get(0).settlementDate()).as("an undated line settles on the report's")
                .isEmpty();
        assertThat(lines.get(4).canonicalFingerprint())
                .as("the repeated record is the same economic statement as the first")
                .isEqualTo(lines.get(0).canonicalFingerprint());
        com.finapp.settlement.format.ParsedLine correction = lines.get(5);
        assertThat(correction.direction())
                .as("a claw-back is money back to the counterparty")
                .isEqualTo(com.finapp.settlement.LineDirection.OUTBOUND);
        assertThat(correction.references())
                .containsEntry(com.finapp.settlement.LineReferenceKind.ORIGINAL_REF,
                        "PSP-CAP-9101");
    }

    @Test
    @DisplayName("the simulated bank's statement adds up and parses whole: credits and debits by"
            + " remittance reference, the bank's fee, a signed closing (P8-TST-001)")
    void theBankStatementAddsUpAndParses() {
        LocalDate day = LocalDate.parse("2026-09-29");
        SimulatedBankStatements statement =
                new SimulatedBankStatements("SB-EUR-FIX-1", "EUR", 4, day, 10_00)
                        .credit(day, 98_25, java.util.Optional.of("PSP-REM-777001"))
                        .narrative("Remittance for the day")
                        .debit(day, 120_00, java.util.Optional.of("PAY-REM-777003"))
                        .fee(day, 50);
        assertThat(statement.closingMinor())
                .as("10.00 + 98.25 - 120.00 - 0.50: a debit closing")
                .isEqualTo(-12_25);
        byte[] rendered = statement.render(day);
        com.finapp.settlement.format.simstatement.SimStatementTaggedFormat format =
                new com.finapp.settlement.format.simstatement.SimStatementTaggedFormat(
                        java.util.Map.of(com.finapp.sharedkernel.money.CurrencyCode.of("EUR"),
                                "SIMBANK-EUR-01"));
        assertThat(format.screen(rendered).finding()).isEmpty();
        SettlementFormat.Result result = format.parse(rendered);
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        com.finapp.settlement.format.ParsedBatch batch =
                ((SettlementFormat.Result.Parsed) result).batch();
        assertThat(batch.lines()).hasSize(3);
        assertThat(batch.statement()).hasValueSatisfying(facts -> {
            assertThat(facts.sequence()).isEqualTo(4);
            assertThat(facts.opening().minorUnits()).isEqualTo(10_00);
            assertThat(facts.closing().minorUnits()).isEqualTo(-12_25);
        });
    }

    @Test
    @DisplayName("the two-decimal renders are byte for byte what they were before the generators"
            + " learned each currency's own scale (P9-TSK-003): the PSP report and the bank"
            + " statement in EUR, pinned whole")
    void theTwoDecimalRendersAreUnchanged() {
        assertThat(new String(genuine().render(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("H,SIM_PSP_CSV,1,PSPB-FIX-01,EUR,2026-09-29\n"
                        + "D,1,SALE,100.00,1.75,EUR,2026-09-29,,,PSP-CAP-9001,44400012345678901,,"
                        + "ORD-9001,Card capture\n"
                        + "D,2,REFUND,-40.25,,EUR,2026-09-29,,,PSP-REF-9002,,,ORD-9001,Card refund\n"
                        + "D,3,CHARGEBACK,-100.00,,EUR,2026-09-29,,,DSP-9003,,,,Chargeback\n"
                        + "D,4,PROMO_BONUS,5.00,,EUR,2026-09-29,,,MISC-9004,,,,"
                        + "Unclassified by the platform\n"
                        + "T,4,-37.00,PSP-REM-777001\n");
        LocalDate day = LocalDate.parse("2026-09-29");
        assertThat(new String(
                        new SimulatedBankStatements("SB-EUR-FIX-1", "EUR", 4, day, 10_00)
                                .credit(day, 98_25, java.util.Optional.of("PSP-REM-777001"))
                                .narrative("Remittance for the day")
                                .debit(day, 120_00, java.util.Optional.of("PAY-REM-777003"))
                                .fee(day, 50)
                                .render(day),
                        java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(":20:SB-EUR-FIX-1\n"
                        + ":25:SIMBANK-EUR-01\n"
                        + ":28C:4\n"
                        + ":60F:C,2026-09-29,EUR,10.00\n"
                        + ":61:2026-09-29,C,98.25,PSP-REM-777001\n"
                        + ":86:Remittance for the day\n"
                        + ":61:2026-09-29,D,120.00,PAY-REM-777003\n"
                        + ":61:2026-09-29,F,0.50\n"
                        + ":62F:D,2026-09-29,EUR,12.25\n");
    }

    @Test
    @DisplayName("a JPY and a BHD render are written at the currency's own minor units - 0 and 3 -"
            + " and parse whole through the real adapters to exactly the minor units stated"
            + " (P9-TSK-003)")
    void zeroAndThreeMinorUnitRendersParseExactly() {
        LocalDate day = LocalDate.parse("2026-10-04");
        record Case(String currency, String gross, String fee, long grossMinor, long feeMinor,
                long netMinor, int scale) {}
        for (Case each : java.util.List.of(
                new Case("JPY", "12345", "225", 12_345, 225, 12_120, 0),
                new Case("BHD", "12.345", "0.285", 12_345, 285, 12_060, 3))) {
            byte[] report =
                    new SimulatedSettlementReports("PSPB-FIX-" + each.currency(), each.currency(),
                                    day, "PSP-REM-777009")
                            .with(SimulatedSettlementReports.Line.capture(
                                    "PSP-CAP-9201", "", "", each.gross(), each.fee()))
                            .render();
            assertThat(new String(report, java.nio.charset.StandardCharsets.UTF_8))
                    .as("%s: the trailer's net at the currency's own scale", each.currency())
                    .endsWith("T,1," + java.math.BigDecimal.valueOf(each.netMinor(), each.scale())
                            .toPlainString() + ",PSP-REM-777009\n");
            SettlementFormat.Result result = SimPspCsvFormat.INSTANCE.parse(report);
            assertThat(result).as("%s parses", each.currency())
                    .isInstanceOf(SettlementFormat.Result.Parsed.class);
            com.finapp.settlement.format.ParsedBatch batch =
                    ((SettlementFormat.Result.Parsed) result).batch();
            assertThat(batch.lines()).extracting(line -> line.amount().minorUnits())
                    .containsExactly(each.grossMinor(), each.feeMinor());
            assertThat(batch.lines()).extracting(line -> line.amount().scale())
                    .containsOnly(each.scale());
            assertThat(batch.declaredNet().minorUnits()).isEqualTo(each.netMinor());

            SimulatedBankStatements statement =
                    new SimulatedBankStatements("SB-" + each.currency() + "-FIX-1",
                                    each.currency(), 1, day, 0)
                            .credit(day, each.netMinor(), java.util.Optional.of("PSP-REM-777009"))
                            .fee(day, each.feeMinor());
            com.finapp.settlement.format.simstatement.SimStatementTaggedFormat format =
                    new com.finapp.settlement.format.simstatement.SimStatementTaggedFormat(
                            java.util.Map.of(
                                    com.finapp.sharedkernel.money.CurrencyCode.of(each.currency()),
                                    "SIMBANK-" + each.currency() + "-01"));
            SettlementFormat.Result parsed = format.parse(statement.render(day));
            assertThat(parsed).as("%s's statement parses", each.currency())
                    .isInstanceOf(SettlementFormat.Result.Parsed.class);
            com.finapp.settlement.format.ParsedBatch facts =
                    ((SettlementFormat.Result.Parsed) parsed).batch();
            assertThat(facts.lines()).extracting(line -> line.amount().minorUnits())
                    .containsExactly(each.netMinor(), each.feeMinor());
            assertThat(facts.statement()).hasValueSatisfying(chain -> {
                assertThat(chain.closing().minorUnits())
                        .isEqualTo(each.netMinor() - each.feeMinor());
                assertThat(chain.closing().scale()).isEqualTo(each.scale());
            });
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
