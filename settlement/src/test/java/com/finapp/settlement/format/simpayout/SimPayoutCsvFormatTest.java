package com.finapp.settlement.format.simpayout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFormatId;
import com.finapp.settlement.SettlementLineType;
import com.finapp.settlement.format.FormatDefect;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * `SIM_PAYOUT_CSV` v1, frozen (`P8-TSK-018`, ADR-0066 §8 — the
 * {@code RailMoneySemanticsArePinnedTest} rule applied to a format): the golden daily report's
 * every canonical line, the fee split naming its payout, the fingerprints and raw-record digests
 * pinned by hex literal, each fault rejecting the WHOLE file with its named errors, and the
 * field-class screen refusing instrument data wherever it hides — the beneficiary always, a
 * reference that fails its class, and never the platform's own minted payout reference. A
 * behaviour change here is a NEW format version, never an edit of this one.
 */
@DisplayName("the simulated payout provider's CSV settlement report format, version 1"
        + " (P8-TSK-018)")
class SimPayoutCsvFormatTest {

    private static final SimPayoutCsvFormat FORMAT = SimPayoutCsvFormat.INSTANCE;

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static final LocalDate REPORT_DAY = LocalDate.of(2026, 9, 26);

    /** An international account identifier shape, not Luhn-valid as a digit run. */
    private static final String IBAN = "GB82WEST12345698765432";

    /** A Luhn-valid card number. */
    private static final String PAN = "4111111111111111";

    /** The golden file's two minted payout references, as merchant's mint prints them. */
    private static final String OUR_FIRST = "pyo-01a0db02-f800-7a1c-9d3e-5b7f0c2a4e61";

    private static final String OUR_THIRD = "pyo-01a0db03-1c20-7b4d-a2f1-6c8e1d3b5f72";

    /**
     * A minted payout reference whose hex happens to be all digits: 32 of them once the dashes
     * collapse — beyond card length, so the door's walk passes it, but no reference class
     * admits it except the platform's exact one.
     */
    private static final String MINTED_ALL_DIGITS = "pyo-01923456-7890-7123-8123-456789012345";

    /**
     * A minted payout reference whose dash-collapsed digits hold a Luhn-valid run of card length
     * ({@code 890} {@code 7123} {@code 8123} {@code 45673}): what the platform's own mint
     * produces about once in several dozen, and what the door refuses anywhere it is free text.
     */
    private static final String MINTED_CARD_LENGTH = "pyo-019234ab-c890-7123-8123-45673cdef012";

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        SimPayoutCsvFormatTest.class.getResourceAsStream(
                                "/format/simpayout/golden-v1.csv"),
                        "the golden file is a test resource")) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String goldenText() {
        return new String(golden(), StandardCharsets.UTF_8);
    }

    private static ParsedBatch parsed(byte[] content) {
        SettlementFormat.Result result = FORMAT.parse(content);
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        return ((SettlementFormat.Result.Parsed) result).batch();
    }

    private static ParsedBatch parsed(String content) {
        return parsed(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ParsedBatch parsedGolden() {
        return parsed(golden());
    }

    private static SettlementFormat.Result.Rejected rejected(String content) {
        SettlementFormat.Result result = FORMAT.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result)
                .as("the whole file is rejected (INV-SET-07)")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    private static DeliveryScreen.Screening screened(String content) {
        return FORMAT.screen(content.getBytes(StandardCharsets.UTF_8));
    }

    /** The golden file with one piece of text replaced — one fault at a time. */
    private static String goldenWith(String from, String to) {
        return with(goldenText(), from, to);
    }

    private static String with(String text, String from, String to) {
        int at = text.indexOf(from);
        assertThat(at).as("the text to replace is present: %s", from).isNotNegative();
        assertThat(text.indexOf(from, at + 1))
                .as("the text to replace is unique - one fault at a time: %s", from)
                .isNegative();
        return text.replace(from, to);
    }

    private static void assertSingleDefect(
            SettlementFormat.Result.Rejected verdict, RejectionCode code, int line, String field) {
        assertThat(verdict.code()).isEqualTo(code);
        assertThat(verdict.defects())
                .singleElement()
                .satisfies(
                        defect -> {
                            assertThat(defect.code()).isEqualTo(code);
                            assertThat(defect.lineNo()).contains(line);
                            assertThat(defect.field()).contains(field);
                        });
    }

    private static void assertRefused(
            DeliveryScreen.Screening screening, RefusalReason reason, int line, String field) {
        assertThat(screening.finding())
                .hasValueSatisfying(
                        finding -> {
                            assertThat(finding.reason()).isEqualTo(reason);
                            assertThat(finding.lineNo()).isEqualTo(line);
                            assertThat(finding.fieldName()).contains(field);
                        });
    }

    // ------------------------------------------------------------------------- the freeze

    @Nested
    @DisplayName("the golden daily report")
    class TheGoldenReport {

        @Test
        @DisplayName("parses whole: the provider's batch as the identity, the remittance, and"
                + " seven canonical lines - four records, three of them split by their fee")
        void goldenParsesWhole() {
            ParsedBatch batch = parsedGolden();
            assertThat(batch.externalBatchRef()).isEqualTo("PAYDAY-2026-09-26-EUR");
            assertThat(batch.businessDate()).isEqualTo(REPORT_DAY);
            assertThat(batch.remittanceReference()).contains("PAY-REM-20260926");
            assertThat(batch.statement()).as("a report carries no statement facts").isEmpty();
            assertThat(batch.declaredLineCount())
                    .as("the detail records, counted before any fee split")
                    .isEqualTo(4);
            assertThat(batch.declaredNet().currency()).isEqualTo(EUR);
            assertThat(batch.declaredNet().scale()).isEqualTo(2);
            assertThat(batch.declaredNet().minorUnits())
                    .as("-40.00 - 12.50 + 7.00 - 3.00 - 3 x 0.25")
                    .isEqualTo(-4_925L);

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(7);
            // SETTLED: a payout executed, money out.
            assertLine(lines.get(0), 1, SettlementLineType.PAYOUT_EXECUTED,
                    LineDirection.OUTBOUND, 4_000L);
            assertLine(lines.get(1), 2, SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND,
                    25L);
            assertLine(lines.get(2), 3, SettlementLineType.PAYOUT_EXECUTED,
                    LineDirection.OUTBOUND, 1_250L);
            assertLine(lines.get(3), 4, SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND,
                    25L);
            // RETURNED: the beneficiary bank sent a payout back, money in. No fee, no split.
            assertLine(lines.get(4), 5, SettlementLineType.PAYOUT_RETURNED,
                    LineDirection.INBOUND, 700L);
            assertLine(lines.get(5), 6, SettlementLineType.PAYOUT_EXECUTED,
                    LineDirection.OUTBOUND, 300L);
            assertLine(lines.get(6), 7, SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND,
                    25L);

            assertThat(lines.get(0).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.OUR_REF, OUR_FIRST),
                            Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "po_ab12cd"));
            assertThat(lines.get(2).references())
                    .as("our reference is optional: absent, never an empty key")
                    .containsExactly(Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "po_ef34gh"));
            assertThat(lines.get(4).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.OUR_REF, OUR_THIRD),
                            Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "po_ij56kl"));
            assertThat(lines.get(5).references())
                    .containsExactly(Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "po_mn78op"));
        }

        @Test
        @DisplayName("each fee line names the payout it rode in on (ORIGINAL_REF = its provider"
                + " reference) and shares its record's raw-record digest")
        void feeLinesNameTheirPayout() {
            List<ParsedLine> lines = parsedGolden().lines();
            record Split(int payoutLine, int feeLine, String providerRef) {}
            for (Split split :
                    List.of(
                            new Split(0, 1, "po_ab12cd"),
                            new Split(2, 3, "po_ef34gh"),
                            new Split(5, 6, "po_mn78op"))) {
                ParsedLine payout = lines.get(split.payoutLine());
                ParsedLine fee = lines.get(split.feeLine());
                assertThat(fee.references())
                        .containsExactly(
                                Map.entry(LineReferenceKind.ORIGINAL_REF, split.providerRef()));
                assertThat(fee.rawRecordSha256()).isEqualTo(payout.rawRecordSha256());
            }
            assertThat(lines.get(0).rawRecordSha256())
                    .as("two records are two delivered records")
                    .isNotEqualTo(lines.get(2).rawRecordSha256());
            assertThat(lines.get(4).rawRecordSha256())
                    .isNotEqualTo(lines.get(5).rawRecordSha256());
        }

        @Test
        @DisplayName("the fingerprint algorithm is FROZEN by hex literal, deterministic across"
                + " parses, and the raw-record digest is the delivered record's own text")
        void fingerprintsAreFrozen() {
            ParsedBatch first = parsedGolden();
            ParsedBatch second = parsedGolden();
            List<ParsedLine> lines = first.lines();
            assertThat(HexFormat.of().formatHex(lines.get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo(
                            "f69045d89356ecae76b4a620ebcf1fdac3d14c3acc61321f1b8064d0d33dc5b2");
            assertThat(HexFormat.of().formatHex(lines.get(0).rawRecordSha256()))
                    .isEqualTo(
                            "84794daaf1550c0241ea90cc186e52356bdb5fd971191b34c59f9794e860d312");
            assertThat(HexFormat.of().formatHex(lines.get(1).canonicalFingerprint()))
                    .isEqualTo(
                            "08113382c87ad45b3f8d604c96b1b2b05cba7d138add8f4a5de824214ece0ad4");
            assertThat(HexFormat.of().formatHex(lines.get(2).canonicalFingerprint()))
                    .isEqualTo(
                            "c999b96ec1693b734342fa1a1e1fabdff407a254b208dff7766331d947966845");
            assertThat(HexFormat.of().formatHex(lines.get(2).rawRecordSha256()))
                    .isEqualTo(
                            "78a908dab132966ec3913f6a9e561c1ff1dc647bb7754badc92b2353ef202007");
            assertThat(HexFormat.of().formatHex(lines.get(4).canonicalFingerprint()))
                    .isEqualTo(
                            "23dd37fa1332c550c1bbc197b3259c04bebd3ffb430e1f5c3024d07a64fa4ad6");
            assertThat(HexFormat.of().formatHex(lines.get(4).rawRecordSha256()))
                    .isEqualTo(
                            "461540eeb8884815bd0bf5f95cf5e8ac75990c3d16785cb935e0eaa284a15a50");
            assertThat(HexFormat.of().formatHex(lines.get(5).canonicalFingerprint()))
                    .isEqualTo(
                            "192ce2c3f05387f6ba3ebb639a16d3950b8e30468e8c285509f73ab1828bbb37");
            assertThat(HexFormat.of().formatHex(lines.get(5).rawRecordSha256()))
                    .isEqualTo(
                            "f685b1aaaaa3fb24f04406ff89cfe2603718be7649d18828eb6cd067d004aac4");
            assertThat(lines.get(4).rawRecordSha256())
                    .as("the digest covers the record's own text, no line ending")
                    .isEqualTo(
                            ParsedLine.sha256(
                                    ("D,3,RETURNED,7.00,,po_ij56kl," + OUR_THIRD + ",Blue Shop")
                                            .getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < lines.size(); i++) {
                assertThat(lines.get(i).canonicalFingerprint())
                        .as("line %s re-parses to the same identity", i + 1)
                        .isEqualTo(second.lines().get(i).canonicalFingerprint());
                assertThat(lines.get(i).rawRecordSha256())
                        .as("line %s re-parses to the same record digest", i + 1)
                        .isEqualTo(second.lines().get(i).rawRecordSha256());
            }
        }

        @Test
        @DisplayName("the Money fold per (type, direction) is the report's own arithmetic")
        void totalsFold() {
            assertThat(FileParsing.totalsOf(parsedGolden()))
                    .containsExactlyInAnyOrder(
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.PAYOUT_EXECUTED, LineDirection.OUTBOUND,
                                    3, 5_550L, 2),
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.PAYOUT_RETURNED, LineDirection.INBOUND,
                                    1, 700L, 2),
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND, 3,
                                    75L, 2));
        }

        @Test
        @DisplayName("the golden day is net payable - a negative net, what the platform owes the"
                + " provider - and a day the returns outweigh parses net receivable")
        void netPayableAndReceivable() {
            assertThat(parsedGolden().declaredNet().isNegative())
                    .as("the payouts and their fees outweigh the return")
                    .isTrue();
            ParsedBatch receivable =
                    parsed(
                            with(
                                    goldenWith("D,3,RETURNED,7.00,", "D,3,RETURNED,70.00,"),
                                    "T,4,-49.25,",
                                    "T,4,13.75,"));
            assertThat(receivable.declaredNet().minorUnits())
                    .as("-40.00 - 12.50 + 70.00 - 3.00 - 0.75")
                    .isEqualTo(1_375L);
            assertThat(receivable.lines().get(4).amount().minorUnits()).isEqualTo(7_000L);
        }

        @Test
        @DisplayName("a day with no payouts parses: a header, a trailer of count 0 and net 0,"
                + " no lines")
        void emptyDayParses() {
            ParsedBatch batch =
                    parsed(
                            "H,SIM_PAYOUT_CSV,1,PAYDAY-2026-09-27-EUR,EUR,2026-09-27\n"
                                    + "T,0,0.00,PAY-REM-20260927\n");
            assertThat(batch.lines()).isEmpty();
            assertThat(batch.declaredLineCount()).isZero();
            assertThat(batch.declaredNet().isZero()).isTrue();
            assertThat(batch.remittanceReference()).contains("PAY-REM-20260927");
        }

        @Test
        @DisplayName("a BOM, CRLF endings and the trailing newline are tolerated - no"
                + " fingerprint and no record digest moves")
        void bomAndCrlfTolerated() {
            ParsedBatch lf = parsedGolden();
            String bom = String.valueOf((char) 0xFEFF);
            ParsedBatch crlf = parsed(bom + goldenText().replace("\n", "\r\n"));
            assertThat(crlf.lines()).hasSize(7);
            for (int i = 0; i < lf.lines().size(); i++) {
                assertThat(crlf.lines().get(i).canonicalFingerprint())
                        .as("line %s: line endings are not economics", i + 1)
                        .isEqualTo(lf.lines().get(i).canonicalFingerprint());
                assertThat(crlf.lines().get(i).rawRecordSha256())
                        .as("line %s: the record is its text, not its line ending", i + 1)
                        .isEqualTo(lf.lines().get(i).rawRecordSha256());
            }
        }

        @Test
        @DisplayName("the beneficiary name reaches no canonical output")
        void beneficiaryNeverCarried() {
            for (ParsedLine line : parsedGolden().lines()) {
                assertThat(line.toString()).doesNotContain("Acme", "Blue Shop");
                assertThat(line.references().values())
                        .noneMatch(value -> value.contains("Acme") || value.contains("Blue"));
            }
        }
    }

    // -------------------------------------------------------------- each fault, one at a time

    @Nested
    @DisplayName("each fault rejects the whole file")
    class EachFaultRejectsWhole {

        @Test
        @DisplayName("an empty file is MALFORMED, named 'recordType' for the file as a whole")
        void emptyFile() {
            SettlementFormat.Result.Rejected verdict = rejected("");
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).isEmpty();
                                assertThat(defect.field()).contains("recordType");
                            });
        }

        @Test
        @DisplayName("a header naming another format or version is UNSUPPORTED_FORMAT")
        void unsupportedFormat() {
            assertSingleDefect(
                    rejected(goldenWith("H,SIM_PAYOUT_CSV,1,", "H,SIM_PAYOUT_CSV,2,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatVersion");
            assertSingleDefect(
                    rejected(goldenWith("H,SIM_PAYOUT_CSV,1,", "H,SIM_PSP_CSV,1,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatId");
        }

        @Test
        @DisplayName("a currency the platform cannot represent is UNKNOWN_CURRENCY")
        void unknownCurrency() {
            assertSingleDefect(
                    rejected(goldenWith(",EUR,2026-09-26\n", ",XXX,2026-09-26\n")),
                    RejectionCode.UNKNOWN_CURRENCY, 1, "currency");
        }

        @Test
        @DisplayName("an amount or a fee with more decimals than the currency's scale is"
                + " SCALE_MISMATCH, named to its line and field")
        void scaleMismatch() {
            assertSingleDefect(
                    rejected(goldenWith("-40.00,0.25", "-40.001,0.25")),
                    RejectionCode.SCALE_MISMATCH, 2, "amount");
            assertSingleDefect(
                    rejected(goldenWith("0.25,po_ef34gh", "0.255,po_ef34gh")),
                    RejectionCode.SCALE_MISMATCH, 3, "fee");
        }

        @Test
        @DisplayName("a trailer count mismatch and a net mismatch - off by one cent - are each"
                + " CONTROL_TOTAL_MISMATCH, naming the field")
        void controlTotals() {
            assertSingleDefect(
                    rejected(goldenWith("T,4,-49.25,", "T,3,-49.25,")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 6, "count");
            assertSingleDefect(
                    rejected(goldenWith("T,4,-49.25,", "T,4,-49.24,")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 6, "net");
        }

        @Test
        @DisplayName("a known code carrying the wrong sign is MALFORMED at its amount: a"
                + " positive SETTLED is no payout, a negative RETURNED no return")
        void knownCodeWrongSign() {
            assertSingleDefect(
                    rejected(goldenWith("D,1,SETTLED,-40.00", "D,1,SETTLED,40.00")),
                    RejectionCode.MALFORMED, 2, "amount");
            assertSingleDefect(
                    rejected(goldenWith("D,3,RETURNED,7.00", "D,3,RETURNED,-7.00")),
                    RejectionCode.MALFORMED, 4, "amount");
        }

        @Test
        @DisplayName("a zero amount is MALFORMED: a line's amount is positive, its sign the"
                + " direction's")
        void zeroAmount() {
            assertSingleDefect(
                    rejected(goldenWith("D,3,RETURNED,7.00,", "D,3,RETURNED,0.00,")),
                    RejectionCode.MALFORMED, 4, "amount");
        }

        @Test
        @DisplayName("the sequence is 1..n strictly in order: a gap or a repeat is MALFORMED")
        void sequenceInOrder() {
            assertSingleDefect(
                    rejected(goldenWith("D,2,SETTLED", "D,5,SETTLED")),
                    RejectionCode.MALFORMED, 3, "seq");
            assertSingleDefect(
                    rejected(goldenWith("D,4,SETTLED", "D,3,SETTLED")),
                    RejectionCode.MALFORMED, 5, "seq");
            // A record of the wrong shape keeps its seat: the next record's seq still stands,
            // so one fault is one defect.
            assertSingleDefect(
                    rejected(goldenWith("Blue Shop", "Blue, Shop")),
                    RejectionCode.MALFORMED, 4, "recordType");
        }

        @Test
        @DisplayName("an unknown well-formed code is KEPT as OTHER_OUT/OTHER_IN by its sign -"
                + " never dropped, never a success, its fee still split")
        void unknownCodeKept() {
            ParsedBatch out = parsed(goldenWith("D,2,SETTLED,-12.50", "D,2,ON_HOLD,-12.50"));
            assertThat(out.lines()).hasSize(7);
            assertLine(out.lines().get(2), 3, SettlementLineType.OTHER_OUT,
                    LineDirection.OUTBOUND, 1_250L);
            assertThat(out.lines().get(2).references())
                    .containsExactly(Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "po_ef34gh"));
            assertLine(out.lines().get(3), 4, SettlementLineType.PAYOUT_FEE,
                    LineDirection.OUTBOUND, 25L);
            assertThat(out.lines().get(3).references())
                    .containsExactly(Map.entry(LineReferenceKind.ORIGINAL_REF, "po_ef34gh"));

            ParsedBatch in = parsed(goldenWith("D,3,RETURNED,7.00", "D,3,REVERSAL,7.00"));
            assertLine(in.lines().get(4), 5, SettlementLineType.OTHER_IN, LineDirection.INBOUND,
                    700L);
            assertThat(in.declaredNet()).isEqualTo(parsedGolden().declaredNet());
        }

        @Test
        @DisplayName("a truncated body - the trailer gone - fails as MALFORMED")
        void truncatedBody() {
            String text = goldenText();
            String truncated = text.substring(0, text.indexOf("T,4,"));
            SettlementFormat.Result.Rejected verdict = rejected(truncated);
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(5);
                                assertThat(defect.field()).contains("recordType");
                            });
        }

        @Test
        @DisplayName("each field malformed in turn is MALFORMED at its line and field")
        void eachFieldMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults =
                    List.of(
                            new Fault("PAYDAY-2026-09-26-EUR", "PAYDAY 2026-09-26-EUR", 1,
                                    "batchRef"),
                            new Fault(",EUR,2026-09-26\n", ",eur,2026-09-26\n", 1, "currency"),
                            new Fault(",EUR,2026-09-26\n", ",EUR,2026-13-26\n", 1,
                                    "businessDate"),
                            new Fault("D,2,SETTLED", "D,x,SETTLED", 3, "seq"),
                            new Fault("D,2,SETTLED", "D,2,settled", 3, "code"),
                            new Fault("-12.50,0.25", "-12.5O,0.25", 3, "amount"),
                            new Fault("-40.00,0.25", "-40.00,-0.25", 2, "fee"),
                            // The provider's reference is required: an empty one fails its class.
                            new Fault("po_ef34gh", "", 3, "providerRef"),
                            new Fault("po_ef34gh", "po ef34gh", 3, "providerRef"),
                            new Fault(OUR_THIRD, "pyo/0003", 4, "ourRef"),
                            new Fault("Blue Shop", "B".repeat(71), 4, "beneficiary"),
                            // v1 has no quoting: a comma inside a value changes the record's
                            // shape - which keeps its seat, so the next record's seq stands.
                            new Fault("Blue Shop", "Blue, Shop", 4, "recordType"),
                            new Fault("T,4,-49.25,", "T,four,-49.25,", 6, "count"),
                            new Fault("-49.25,", "-49.2S,", 6, "net"),
                            new Fault("PAY-REM-20260926", "PSP-REM-20260926", 6,
                                    "remittanceRef"));
            for (Fault fault : faults) {
                SettlementFormat.Result.Rejected verdict =
                        rejected(goldenWith(fault.from(), fault.to()));
                assertThat(verdict.code())
                        .as("fault %s -> %s", fault.to(), fault.field())
                        .isEqualTo(RejectionCode.MALFORMED);
                assertThat(verdict.defects())
                        .as("fault %s names its line and field", fault.to())
                        .anySatisfy(
                                defect -> {
                                    assertThat(defect.lineNo()).contains(fault.line());
                                    assertThat(defect.field()).contains(fault.field());
                                });
            }
        }

        @Test
        @DisplayName("no defect ever carries a value: fields are NAMES, positions are numbers")
        void defectsCarryNoValue() {
            List<SettlementFormat.Result.Rejected> verdicts =
                    List.of(
                            rejected(goldenWith("-40.00,0.25", "-40.001,0.25")),
                            rejected(goldenWith("Blue Shop", "Q".repeat(71))),
                            rejected(goldenWith("po_ef34gh", "po ef34gh")),
                            rejected(goldenWith("T,4,-49.25,", "T,4,-49.24,")));
            for (SettlementFormat.Result.Rejected verdict : verdicts) {
                assertThat(verdict.toString())
                        .doesNotContain("40.001", "QQQQ", "ef34gh", "49.24");
                for (FormatDefect defect : verdict.defects()) {
                    defect.field()
                            .ifPresent(name -> assertThat(name).matches("[A-Za-z]+"));
                }
            }
        }
    }

    // ------------------------------------------------------------- the field-class screen

    @Nested
    @DisplayName("the field-class screen (ADR-0066 §3, C6)")
    class TheFieldClassScreen {

        @Test
        @DisplayName("the golden report passes, and its record count is the reader's own")
        void goldenPasses() {
            DeliveryScreen.Screening screening = FORMAT.screen(golden());
            assertThat(screening.finding()).isEmpty();
            assertThat(screening.lineCount()).isEqualTo(6);
        }

        @Test
        @DisplayName("a card number in the BENEFICIARY - declared free text - is refused with its"
                + " line and field, written plain or grouped")
        void panInBeneficiaryRefused() {
            for (String card : List.of(PAN, "4111 1111 1111 1111")) {
                assertRefused(
                        screened(goldenWith("Acme GmbH", "Acme GmbH card " + card)),
                        RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "beneficiary");
            }
        }

        @Test
        @DisplayName("an account identifier in the BENEFICIARY is refused as such (INV-RAIL-03)")
        void ibanInBeneficiaryRefused() {
            assertRefused(
                    screened(goldenWith("Blue Shop", "Blue Shop " + IBAN)),
                    RefusalReason.ACCOUNT_IDENTIFIER, 4, "beneficiary");
        }

        @Test
        @DisplayName("an account identifier as the PROVIDER reference fails its class - which"
                + " admits no instrument shape - and is refused, never retained as malformed (C6)")
        void ibanAsProviderReferenceRefused() {
            assertRefused(
                    screened(goldenWith("po_ij56kl", IBAN)),
                    RefusalReason.ACCOUNT_IDENTIFIER, 4, "providerRef");
        }

        @Test
        @DisplayName("a card number behind letters in OUR reference, as the batch reference, as"
                + " an amount or as the format name fails the class and is refused")
        void panWhereAClassBelongsRefused() {
            assertRefused(
                    screened(goldenWith(OUR_FIRST, "ORD-" + PAN)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "ourRef");
            assertRefused(
                    screened(goldenWith("PAYDAY-2026-09-26-EUR", PAN)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 1, "batchRef");
            assertRefused(
                    screened(goldenWith("-40.00,0.25", "-" + PAN + ",0.25")),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "amount");
            // The header's identity fields are classed too: nothing in a file goes unscreened.
            assertRefused(
                    screened(goldenWith("H,SIM_PAYOUT_CSV,1,", "H," + PAN + ",1,")),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 1, "formatId");
            // Machine-grouped (the re-gate's NEW-SEC-2): the class refuses, the free-text walk
            // cannot see ':'/'_' groups, so the line is MALFORMED - stored nowhere - rather
            // than refused by name.
            for (String grouped : List.of("4111:1111:1111:1111", "4111_1111_1111_1111")) {
                assertThat(screened(goldenWith("po_ij56kl", grouped)).finding())
                        .as("grouped %s passes the walk", grouped).isEmpty();
                assertSingleDefect(rejected(goldenWith("po_ij56kl", grouped)),
                        RejectionCode.MALFORMED, 4, "providerRef");
            }
        }

        @Test
        @DisplayName("the platform's OWN minted payout reference is admitted exactly as our"
                + " reference, even when its digits reach card length; the same run as the"
                + " provider's reference fails the class")
        void thePlatformsOwnReferenceIsAdmitted() {
            for (String minted : List.of(MINTED_ALL_DIGITS, MINTED_CARD_LENGTH)) {
                String ours = goldenWith(OUR_THIRD, minted);
                assertThat(screened(ours).finding()).as("our %s", minted).isEmpty();
                assertThat(parsed(ours).lines().get(4).references())
                        .containsEntry(LineReferenceKind.OUR_REF, minted);
            }
            // As the provider's reference - not the platform's - the all-digit run fails the
            // no-instrument class: clean (32 digits is no card), so stored and rejected...
            String allDigits = goldenWith("po_ij56kl", MINTED_ALL_DIGITS);
            assertThat(screened(allDigits).finding()).isEmpty();
            assertSingleDefect(rejected(allDigits), RejectionCode.MALFORMED, 4, "providerRef");
            // ...and the Luhn-valid card-length run is refused at the door.
            assertRefused(
                    screened(goldenWith("po_ij56kl", MINTED_CARD_LENGTH)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 4, "providerRef");
            // Not the minted shape (no version nibble; the last digit keeps the run Luhn-valid):
            // the no-instrument class, and refused.
            String notMinted = "pyo-019234ab-c890-1123-8123-45679cdef012";
            assertRefused(
                    screened(goldenWith(OUR_THIRD, notMinted)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 4, "ourRef");
        }

        @Test
        @DisplayName("a CLEAN malformed field - no instrument data - passes the screen, so the"
                + " corrupt delivery is stored as evidence and rejected at parse")
        void cleanMalformedPasses() {
            for (String fault :
                    List.of(
                            goldenWith("-12.50,0.25", "-12.5O,0.25"),
                            goldenWith("D,2,SETTLED", "D,2,settled"),
                            goldenWith("po_ef34gh", "po ef34gh"),
                            goldenWith("Blue Shop", "B".repeat(71)))) {
                assertThat(screened(fault).finding()).isEmpty();
                assertThat(rejected(fault).code()).isEqualTo(RejectionCode.MALFORMED);
            }
        }

        @Test
        @DisplayName("bytes that do not parse structurally are screened as one conservative"
                + " stream")
        void structuralFailureFallsBack() {
            DeliveryScreen.Screening plain =
                    screened("just some text with 4111 1111 1111 1111 inside\n");
            assertThat(plain.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.fieldName()).isEmpty();
                            });
            // A comma inside the beneficiary breaks the record's shape: every byte screened.
            DeliveryScreen.Screening shapeless =
                    screened(goldenWith("Blue Shop", "Blue, Shop " + IBAN));
            assertThat(shapeless.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(4);
                                assertThat(finding.fieldName()).isEmpty();
                            });
            assertThat(shapeless.lineCount()).isEqualTo(6);
        }
    }

    // ------------------------------------------------------------------------ the identity

    @Test
    @DisplayName("the format names itself - SIM_PAYOUT_CSV, version 1 - and its remittance"
            + " pattern admits the payout provider's references alone")
    void identity() {
        assertThat(FORMAT.id()).isEqualTo(SettlementFormatId.SIM_PAYOUT_CSV);
        assertThat(FORMAT.version()).isEqualTo(1);
        assertThat("PAY-REM-20260926").matches(SimPayoutCsvFormat.REMITTANCE_REFERENCE);
        assertThat("PSP-REM-20260926").doesNotMatch(SimPayoutCsvFormat.REMITTANCE_REFERENCE);
        assertThat("SCH-REM-20260926").doesNotMatch(SimPayoutCsvFormat.REMITTANCE_REFERENCE);
        assertThat("PAY-REM-" + PAN).doesNotMatch(SimPayoutCsvFormat.REMITTANCE_REFERENCE);
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
        assertThat(line.amount().currency()).isEqualTo(EUR);
        assertThat(line.businessDate()).isEqualTo(REPORT_DAY);
        assertThat(line.settlementDate())
                .as("the report's lines settle on its business date")
                .contains(REPORT_DAY);
        assertThat(line.valueDate()).as("the report carries no value date").isEmpty();
    }
}
