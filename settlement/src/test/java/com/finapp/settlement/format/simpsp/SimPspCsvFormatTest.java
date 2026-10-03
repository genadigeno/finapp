package com.finapp.settlement.format.simpsp;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementLineType;
import com.finapp.settlement.format.FormatDefect;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.settlement.format.SettlementFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * `SIM_PSP_CSV` v1, frozen (`P8-TSK-008`, ADR-0066 §8 — the
 * {@code RailMoneySemanticsArePinnedTest} rule applied to a format): the golden file's every
 * canonical line, the fingerprint algorithm pinned by hex literal, each fault rejecting the
 * WHOLE file with its named errors, and the field-class screen's three-way discipline. A
 * behaviour change here is a NEW format version, never an edit of this one.
 */
@DisplayName("the simulated PSP's CSV settlement format, version 1 (P8-TSK-008)")
class SimPspCsvFormatTest {

    private static final SimPspCsvFormat FORMAT = SimPspCsvFormat.INSTANCE;

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        SimPspCsvFormatTest.class.getResourceAsStream(
                                "/format/simpsp/golden-v1.csv"),
                        "the golden file is a test resource")) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ParsedBatch parsedGolden() {
        SettlementFormat.Result result = FORMAT.parse(golden());
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        return ((SettlementFormat.Result.Parsed) result).batch();
    }

    private static SettlementFormat.Result.Rejected rejected(String content) {
        SettlementFormat.Result result =
                FORMAT.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result)
                .as("the whole file is rejected (INV-SET-07)")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    /** The golden file with one physical line's text replaced — one fault at a time. */
    private static String goldenWith(String from, String to) {
        String text = new String(golden(), StandardCharsets.UTF_8);
        assertThat(text).contains(from);
        return text.replace(from, to);
    }

    // ------------------------------------------------------------------------- the freeze

    @Nested
    @DisplayName("the golden file")
    class TheGoldenFile {

        @Test
        @DisplayName("parses whole: batch identity, ten canonical lines, the gross-plus-fee"
                + " split, and every type mapped - unknown ones to OTHER_*, never a success")
        void goldenParsesWhole() {
            ParsedBatch batch = parsedGolden();
            assertThat(batch.externalBatchRef()).isEqualTo("PSPB-2026-09-25-01");
            assertThat(batch.declaredNet().currency().code()).isEqualTo("EUR");
            assertThat(batch.businessDate()).hasToString("2026-09-25");
            assertThat(batch.remittanceReference()).contains("PSP-REM-20260925");
            assertThat(batch.declaredLineCount()).isEqualTo(9);
            assertThat(batch.declaredNet().minorUnits()).isEqualTo(29_500L);

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(10);
            assertLine(lines.get(0), 1, SettlementLineType.CAPTURE, LineDirection.INBOUND,
                    10_000L);
            // The split: the fee rides the SALE record, becomes its own PROCESSING_FEE line
            // and names its transaction (ORIGINAL_REF) - the record digest shared.
            assertLine(lines.get(1), 2, SettlementLineType.PROCESSING_FEE,
                    LineDirection.OUTBOUND, 175L);
            assertThat(lines.get(1).references())
                    .containsExactly(
                            java.util.Map.entry(LineReferenceKind.ORIGINAL_REF, "PSP-CAP-001"));
            assertThat(lines.get(1).rawRecordSha256())
                    .isEqualTo(lines.get(0).rawRecordSha256());
            assertLine(lines.get(2), 3, SettlementLineType.CAPTURE, LineDirection.INBOUND,
                    25_050L);
            assertLine(lines.get(3), 4, SettlementLineType.REFUND, LineDirection.OUTBOUND,
                    4_025L);
            assertLine(lines.get(4), 5, SettlementLineType.CHARGEBACK, LineDirection.OUTBOUND,
                    10_000L);
            assertLine(lines.get(5), 6, SettlementLineType.CHARGEBACK_REVERSAL,
                    LineDirection.INBOUND, 10_000L);
            assertLine(lines.get(6), 7, SettlementLineType.DISPUTE_FEE, LineDirection.OUTBOUND,
                    1_500L);
            assertLine(lines.get(7), 8, SettlementLineType.COUNTERPARTY_ADJUSTMENT,
                    LineDirection.OUTBOUND, 250L);
            assertLine(lines.get(8), 9, SettlementLineType.OTHER_IN, LineDirection.INBOUND,
                    500L);
            assertLine(lines.get(9), 10, SettlementLineType.OTHER_OUT, LineDirection.OUTBOUND,
                    100L);

            assertThat(lines.get(0).references())
                    .containsExactly(
                            java.util.Map.entry(LineReferenceKind.PSP_CAPTURE_REF,
                                    "PSP-CAP-001"),
                            java.util.Map.entry(LineReferenceKind.ACQUIRER_REF,
                                    "44400012345678901"),
                            java.util.Map.entry(LineReferenceKind.OUR_REF, "ORD-1001"));
            assertThat(lines.get(4).references())
                    .containsExactly(
                            java.util.Map.entry(LineReferenceKind.DISPUTE_REF, "DSP-777"));
        }

        @Test
        @DisplayName("the fingerprint algorithm is FROZEN by hex literal, deterministic"
                + " across parses, and the raw-record digest is the delivered record's")
        void fingerprintsAreFrozen() {
            ParsedBatch first = parsedGolden();
            ParsedBatch second = parsedGolden();
            assertThat(HexFormat.of().formatHex(first.lines().get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo(
                            "3da8a0ea931bb81e74906519306660a5cf8c248ba1dcc2b9f496ab7f864f6de4");
            assertThat(HexFormat.of().formatHex(first.lines().get(0).rawRecordSha256()))
                    .isEqualTo(
                            "acdf785c74895b7c311e6190f325b3214d02e6805b981c7e41886a812804b049");
            assertThat(HexFormat.of().formatHex(first.lines().get(1).canonicalFingerprint()))
                    .isEqualTo(
                            "b50002d8ee1f1d5bff6f9fb2eecbaa124212ec43915b9a4b66636142270e1141");
            for (int i = 0; i < first.lines().size(); i++) {
                assertThat(first.lines().get(i).canonicalFingerprint())
                        .as("line %s re-parses to the same identity", i + 1)
                        .isEqualTo(second.lines().get(i).canonicalFingerprint());
            }
        }

        @Test
        @DisplayName("the Money fold per (type, direction) is the batch's own arithmetic")
        void totalsFold() {
            List<SettlementBatchStore.TotalRow> totals =
                    FileParsing.totalsOf(parsedGolden());
            assertThat(totals)
                    .contains(
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.CAPTURE, LineDirection.INBOUND, 2,
                                    35_050L, 2),
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.PROCESSING_FEE, LineDirection.OUTBOUND,
                                    1, 175L, 2))
                    .hasSize(9);
        }

        @Test
        @DisplayName("a BOM, CRLF endings and the trailing newline are tolerated")
        void bomAndCrlfTolerated() {
            String text = new String(golden(), StandardCharsets.UTF_8).replace("\n", "\r\n");
            byte[] withBom = ("﻿" + text).getBytes(StandardCharsets.UTF_8);
            SettlementFormat.Result result = FORMAT.parse(withBom);
            assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
            assertThat(((SettlementFormat.Result.Parsed) result).batch().lines()).hasSize(10);
        }
    }

    // -------------------------------------------------------------- each fault, one at a time

    @Nested
    @DisplayName("each fault rejects the whole file")
    class EachFaultRejectsWhole {

        @Test
        @DisplayName("an empty file is MALFORMED")
        void emptyFile() {
            assertThat(rejected("").code()).isEqualTo(RejectionCode.MALFORMED);
        }

        @Test
        @DisplayName("a header naming another format or version is UNSUPPORTED_FORMAT")
        void unsupportedFormat() {
            assertThat(rejected(goldenWith("H,SIM_PSP_CSV,1,", "H,SIM_PSP_CSV,2,")).code())
                    .isEqualTo(RejectionCode.UNSUPPORTED_FORMAT);
            assertThat(rejected(goldenWith("H,SIM_PSP_CSV,1,", "H,OTHER_CSV,1,")).code())
                    .isEqualTo(RejectionCode.UNSUPPORTED_FORMAT);
        }

        @Test
        @DisplayName("a currency the platform cannot represent is UNKNOWN_CURRENCY")
        void unknownCurrency() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith(",EUR,2026-09-25\nD", ",XXX,2026-09-25\nD"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.UNKNOWN_CURRENCY);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(1);
                                assertThat(defect.field()).contains("currency");
                            });
        }

        @Test
        @DisplayName("an amount with more decimals than the currency's scale is"
                + " SCALE_MISMATCH, named to its line and field")
        void scaleMismatch() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith("100.00,1.75", "100.001,1.75"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
            assertThat(verdict.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.code())
                                        .isEqualTo(RejectionCode.SCALE_MISMATCH);
                                assertThat(defect.lineNo()).contains(2);
                                assertThat(defect.field()).contains("amount");
                            });
        }

        @Test
        @DisplayName("a trailer count mismatch and a trailer net mismatch are each"
                + " CONTROL_TOTAL_MISMATCH")
        void controlTotals() {
            assertThat(rejected(goldenWith("T,9,295.00,", "T,8,295.00,")).code())
                    .isEqualTo(RejectionCode.CONTROL_TOTAL_MISMATCH);
            assertThat(rejected(goldenWith("T,9,295.00,", "T,9,295.01,")).code())
                    .isEqualTo(RejectionCode.CONTROL_TOTAL_MISMATCH);
        }

        @Test
        @DisplayName("a truncated body - the trailer gone - fails as MALFORMED")
        void truncatedBody() {
            String text = new String(golden(), StandardCharsets.UTF_8);
            String truncated = text.substring(0, text.indexOf("T,9,"));
            assertThat(rejected(truncated).code()).isEqualTo(RejectionCode.MALFORMED);
        }

        @Test
        @DisplayName("each field malformed in turn is MALFORMED at its line and field")
        void eachFieldMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults =
                    List.of(
                            new Fault("D,2,SALE", "D,x,SALE", 3, "seq"),
                            new Fault("250.50,", "25O.50,", 3, "amount"),
                            new Fault("100.00,1.75", "100.00,-1.75", 2, "fee"),
                            new Fault("D,3,REFUND,-40.25,,EUR", "D,3,REFUND,-40.25,,GBP", 4,
                                    "currency"),
                            new Fault(",2026-09-25,2026-09-26,,PSP-REF-001",
                                    ",2026-13-25,2026-09-26,,PSP-REF-001", 4, "businessDate"),
                            new Fault("PSP-CAP-002", "", 3, "pspRef"),
                            new Fault("44400012345678901", "4440001", 2, "acquirerRef"),
                            // A dispute-stage record's primary reference IS its dispute
                            // reference; a second one is refused.
                            new Fault("D,4,CHARGEBACK,-100.00,,EUR,2026-09-25,,,DSP-777,,,",
                                    "D,4,CHARGEBACK,-100.00,,EUR,2026-09-25,,,DSP-777,,DSP-778,",
                                    5, "disputeRef"),
                            // The sign is the type's own: a negative SALE is not a sale.
                            new Fault("D,2,SALE,250.50", "D,2,SALE,-250.50", 3, "amount"));
            for (Fault fault : faults) {
                SettlementFormat.Result.Rejected verdict =
                        rejected(goldenWith(fault.from(), fault.to()));
                assertThat(verdict.code())
                        .as("fault %s -> %s", fault.from(), fault.field())
                        .isEqualTo(RejectionCode.MALFORMED);
                assertThat(verdict.defects())
                        .as("fault %s names its line and field", fault.field())
                        .anySatisfy(
                                defect -> {
                                    assertThat(defect.lineNo()).contains(fault.line());
                                    assertThat(defect.field()).contains(fault.field());
                                });
            }
        }

        @Test
        @DisplayName("a duplicate provider sequence number is MALFORMED - two records"
                + " claiming one seat")
        void duplicateSeq() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith("D,9,MYSTERY", "D,8,MYSTERY"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .anySatisfy(defect -> assertThat(defect.field()).contains("seq"));
        }

        @Test
        @DisplayName("more malformed records than the bounded defect list holds still reject"
                + " the whole file MALFORMED with the first hundred - whether a record is read"
                + " is its own defects' verdict, never the list's room")
        void defectOverflowRejectsWhole() {
            // One defect a record (ADJUSTMENT declares no sign), so records 101-120 meet a FULL
            // list. Judged by the list's growth, each would read as clean and its null amount
            // would throw - leaving the file RECEIVED and retried forever (INV-SET-07).
            int records = 120;
            StringBuilder file =
                    new StringBuilder("H,SIM_PSP_CSV,1,PSPB-2026-09-25-01,EUR,2026-09-25\n");
            for (int seq = 1; seq <= records; seq++) {
                file.append("D,").append(seq).append(",ADJUSTMENT,25O.50,,EUR,2026-09-25,,,ADJ-")
                        .append(seq).append(",,,,\n");
            }
            file.append("T,").append(records).append(",0.00,PSP-REM-20260925\n");

            SettlementFormat.Result.Rejected verdict = rejected(file.toString());
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .as("bounded like V003's table: the first hundred tell the story")
                    .hasSize(100);
            for (int i = 0; i < verdict.defects().size(); i++) {
                FormatDefect defect = verdict.defects().get(i);
                assertThat(defect.code()).isEqualTo(RejectionCode.MALFORMED);
                assertThat(defect.lineNo()).as("defect %s", i).contains(i + 2);
                assertThat(defect.field()).contains("amount");
            }
        }

        @Test
        @DisplayName("a file of 120 defective records - past the 100-defect cap - is rejected"
                + " whole as MALFORMED with exactly 100 bounded defects, never a crash on the"
                + " 101st record (P8-DOC-001)")
        void moreDefectsThanTheCapRejectCleanly() {
            StringBuilder text =
                    new StringBuilder("H,SIM_PSP_CSV,1,PSPB-2026-09-25-01,EUR,2026-09-25\n");
            int records = 120;
            for (int seq = 1; seq <= records; seq++) {
                text.append("D,").append(seq).append(",SALE,12O.00,,EUR,2026-09-25,,,PSP-CAP-")
                        .append(seq).append(",,,,Bad amount\n");
            }
            text.append("T,").append(records).append(",0.00,PSP-REM-20260925\n");

            SettlementFormat.Result.Rejected verdict = rejected(text.toString());
            assertThat(verdict.code())
                    .as("every record's amount is malformed, so the verdict is MALFORMED")
                    .isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .as("the defects stay bounded at the cap: 100 of the 120 records named,"
                            + " each by its line and its amount field")
                    .hasSize(100)
                    .allSatisfy(defect -> assertThat(defect.field()).contains("amount"));
            assertThat(verdict.defects().get(0).lineNo())
                    .as("the first defect names the first detail record's physical line")
                    .contains(2);
        }

        @Test
        @DisplayName("no defect ever carries a value: fields are NAMES, positions are"
                + " numbers")
        void defectsCarryNoValue() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith("100.00,1.75", "100.001,1.75"));
            for (FormatDefect defect : verdict.defects()) {
                defect.field()
                        .ifPresent(
                                name ->
                                        assertThat(name)
                                                .doesNotContain("100.001")
                                                .matches("[A-Za-z]+"));
            }
        }
    }

    // ------------------------------------------------------------- the field-class screen

    @Nested
    @DisplayName("the field-class screen (ADR-0066 §3, C6)")
    class TheFieldClassScreen {

        @Test
        @DisplayName("the golden file passes, and its record count is the walk's own")
        void goldenPasses() {
            DeliveryScreen.Screening screening = FORMAT.screen(golden());
            assertThat(screening.finding()).isEmpty();
            assertThat(screening.lineCount()).isEqualTo(11);
        }

        @Test
        @DisplayName("a Luhn-valid digit run in the ACQUIRER reference column is its class's"
                + " own shape and is NOT refused - never tested as free text")
        void luhnValidNetworkIdSurvives() {
            // 15 digits, Luhn-valid (Amex-shaped): the conservative screen would refuse it;
            // the field class knows better.
            String luhnValid = "340000000000009";
            DeliveryScreen.Screening screening =
                    FORMAT.screen(
                            goldenWith("44400012345678901", luhnValid)
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding()).isEmpty();
        }

        @Test
        @DisplayName("a card number in the DESCRIPTOR - declared free text - is refused with"
                + " its line and field, and never stored")
        void panInFreeTextRefused() {
            DeliveryScreen.Screening screening =
                    FORMAT.screen(
                            goldenWith("Desk sale", "customer card 4111 1111 1111 1111")
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(2);
                                assertThat(finding.fieldName()).contains("descriptor");
                            });
        }

        @Test
        @DisplayName("a card number in a REFERENCE column fails its class and is screened as"
                + " free text - refused, not retained as a malformed file (C6)")
        void panInReferenceColumnRefused() {
            DeliveryScreen.Screening screening =
                    FORMAT.screen(
                            goldenWith("PSP-CAP-001", "4111111111111111")
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(2);
                                assertThat(finding.fieldName()).contains("pspRef");
                            });
        }

        /**
         * SEC-02 (the Phase 8 → 9 transition): the screen read the PSP's references with the
         * parse's class, which a dash or a letter satisfies, so these passed the door and rested
         * in {@code batch.external_batch_ref} and {@code line_reference}.
         */
        @Test
        @DisplayName("SEC-02: a card number written with dashes or behind letters is refused in"
                + " every PSP reference class - batchRef, type, pspRef, disputeRef, ourRef")
        void aCardNumberInEveryPspReferenceClassIsRefused() {
            String detail =
                    "D,1,SALE,100.00,1.75,EUR,2026-09-25,2026-09-26,2026-09-27,PSP-CAP-001,"
                            + "44400012345678901,,ORD-1001,Desk sale";
            for (String pan : List.of("4111-1111-1111-1111", "PAN4111111111111111",
                    "CAP-4111-1111-1111-1111",
                    // NEW-SEC-2: the machine separators, alone and mixed with the printed ones.
                    "4111:1111:1111:1111", "4111_1111_1111_1111",
                    "CAP_4111:1111-1111_1111")) {
                assertScreenRefuses(goldenWith("PSPB-2026-09-25-01", pan), 1, "batchRef",
                        RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                assertScreenRefuses(goldenWith(detail, detail.replace(",SALE,", "," + pan + ",")),
                        2, "type", RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                assertScreenRefuses(goldenWith(detail, detail.replace("PSP-CAP-001", pan)), 2,
                        "pspRef", RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                assertScreenRefuses(goldenWith(detail, detail.replace("901,,ORD", "901," + pan
                                + ",ORD")), 2, "disputeRef",
                        RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                assertScreenRefuses(goldenWith(detail, detail.replace("ORD-1001", pan)), 2,
                        "ourRef", RefusalReason.PRIMARY_ACCOUNT_NUMBER);
            }
        }

        @Test
        @DisplayName("SEC-02: a card-length Luhn-valid acquirer reference is refused; the ARN,"
                + " the 15-digit network transaction id and a card-length value that fails"
                + " Luhn are not")
        void aCardNumberInTheAcquirerColumnIsRefused() {
            for (String pan : List.of("4111111111111111", "4222222222222",
                    "4000000000000002",
                    // NEW-SEC-2: a card number grouped by the machine separators fails the
                    // digits-only class and the translated free-text walk refuses it.
                    "4111:1111:1111:1111", "4111_1111_1111_1111")) {
                assertScreenRefuses(goldenWith("44400012345678901", pan), 2, "acquirerRef",
                        RefusalReason.PRIMARY_ACCOUNT_NUMBER);
            }
            for (String reference : List.of("12345678901234567890123", "340000000000009",
                    "4111111111111112")) {
                String content = goldenWith("44400012345678901", reference);
                assertThat(FORMAT.screen(content.getBytes(StandardCharsets.UTF_8)).finding())
                        .as("%s is the class's own shape or clean free text", reference)
                        .isEmpty();
                assertThat(FORMAT.parse(content.getBytes(StandardCharsets.UTF_8)))
                        .as("and the parse's class is unchanged: it reads as before")
                        .isInstanceOf(SettlementFormat.Result.Parsed.class);
            }
        }

        @Test
        @DisplayName("SEC-02: an account identifier in a reference class is refused, contiguous"
                + " or in its dashed printed form")
        void anAccountIdentifierInAReferenceIsRefused() {
            assertScreenRefuses(goldenWith("PSPB-2026-09-25-01", "DE89370400440532013000"), 1,
                    "batchRef", RefusalReason.ACCOUNT_IDENTIFIER);
            assertScreenRefuses(goldenWith("PSP-CAP-001", "GB82-WEST-1234-5698-7654-32"), 2,
                    "pspRef", RefusalReason.ACCOUNT_IDENTIFIER);
            assertScreenRefuses(goldenWith("Desk sale", "pay to GB82 WEST 1234 5698 7654 32"),
                    2, "descriptor", RefusalReason.ACCOUNT_IDENTIFIER);
        }

        private void assertScreenRefuses(
                String content, int line, String field, RefusalReason reason) {
            DeliveryScreen.Screening screening =
                    FORMAT.screen(content.getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding())
                    .as("refused at the door, naming %s on line %d and never its value", field,
                            line)
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason()).isEqualTo(reason);
                                assertThat(finding.lineNo()).isEqualTo(line);
                                assertThat(finding.fieldName()).contains(field);
                            });
        }

        @Test
        @DisplayName("an account identifier in the descriptor is refused as such")
        void accountIdentifierRefused() {
            DeliveryScreen.Screening screening =
                    FORMAT.screen(
                            goldenWith("Desk sale", "pay to DE89370400440532013000")
                                    .getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding ->
                                    assertThat(finding.reason())
                                            .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER));
        }

        @Test
        @DisplayName("a CLEAN malformed field - no instrument data - passes the screen, so"
                + " the corrupt delivery is stored as evidence and rejected at parse")
        void cleanMalformedPasses() {
            String cleanFault = goldenWith("250.50", "25O.50");
            assertThat(FORMAT.screen(cleanFault.getBytes(StandardCharsets.UTF_8)).finding())
                    .isEmpty();
            assertThat(rejected(cleanFault).code()).isEqualTo(RejectionCode.MALFORMED);
        }

        @Test
        @DisplayName("bytes that do not parse structurally are screened as one conservative"
                + " stream")
        void structuralFailureFallsBack() {
            String notCsv = "just some text with 4111 1111 1111 1111 inside\n";
            DeliveryScreen.Screening screening =
                    FORMAT.screen(notCsv.getBytes(StandardCharsets.UTF_8));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding ->
                                    assertThat(finding.reason())
                                            .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER));
        }
    }

    private static void assertLine(
            ParsedLine line,
            int lineNo,
            SettlementLineType type,
            LineDirection direction,
            long minor) {
        assertThat(line.lineNo()).isEqualTo(lineNo);
        assertThat(line.type()).isEqualTo(type);
        assertThat(line.direction()).isEqualTo(direction);
        assertThat(line.amount().minorUnits()).isEqualTo(minor);
        assertThat(line.amount().scale()).isEqualTo(2);
    }
}
