package com.finapp.settlement.format.simscheme;

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
import java.io.ByteArrayOutputStream;
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
 * `SIM_SCHEME_JSON` v1, frozen (`P8-TSK-017`, ADR-0066 §8 — the
 * {@code RailMoneySemanticsArePinnedTest} rule applied to a format): the golden cycle report's
 * every canonical line, the fee split, the fingerprints and raw-record digests pinned by hex
 * literal, each fault rejecting the WHOLE file with its named errors, the strict reader, and the
 * field-class screen refusing instrument data wherever it hides — decoded values, numbers and
 * undeclared member names included. A behaviour change here is a NEW format version, never an
 * edit of this one.
 */
@DisplayName("the simulated instant scheme's JSON cycle report format, version 1 (P8-TSK-017)")
class SimSchemeJsonFormatTest {

    private static final SimSchemeJsonFormat FORMAT = SimSchemeJsonFormat.INSTANCE;

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static final LocalDate CYCLE_DAY = LocalDate.of(2026, 9, 25);

    /** An international account identifier shape, not Luhn-valid as a digit run. */
    private static final String IBAN = "GB82WEST12345698765432";

    /** A Luhn-valid card number. */
    private static final String PAN = "4111111111111111";

    /** The golden report, minified, with every member of the report and of entry 1 reordered. */
    private static final String COMPACT_REORDERED =
            "{\"entryCount\":4,\"net\":\"17.20\",\"entries\":["
                    + "{\"narrative\":\"Pay-in for order 42\",\"endToEndRef\":\"E2E-PAYIN-0001\","
                    + "\"schemeRef\":\"SCH-PAYIN-0001\",\"fee\":\"0.10\",\"amount\":\"25.00\","
                    + "\"dir\":\"C\",\"code\":\"CT\",\"seq\":1},"
                    + "{\"seq\":2,\"code\":\"CT\",\"dir\":\"D\",\"amount\":\"10.00\","
                    + "\"fee\":\"0.10\",\"schemeRef\":\"SCH-WDL-0002\","
                    + "\"endToEndRef\":\"E2E-WDL-0002\"},"
                    + "{\"seq\":3,\"code\":\"RT\",\"dir\":\"D\",\"amount\":\"5.00\","
                    + "\"fee\":\"0.10\",\"schemeRef\":\"SCH-RTN-0003\",\"ourRef\":\"RTN-0003\"},"
                    + "{\"seq\":4,\"code\":\"CT\",\"dir\":\"C\",\"amount\":\"7.50\","
                    + "\"schemeRef\":\"SCH-PARK-0004\"}],"
                    + "\"remittanceReference\":\"SCH-REM-20260925\","
                    + "\"businessDate\":\"2026-09-25\","
                    + "\"currency\":\"EUR\",\"cycle\":\"2026-09-25-C1\",\"version\":1,"
                    + "\"format\":\"SIM_SCHEME_JSON\"}";

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        SimSchemeJsonFormatTest.class.getResourceAsStream(
                                "/format/simscheme/golden-v1.json"),
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

    private static SettlementFormat.Result.Rejected rejected(byte[] content) {
        SettlementFormat.Result result = FORMAT.parse(content);
        assertThat(result)
                .as("the whole file is rejected (INV-SET-07)")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    private static SettlementFormat.Result.Rejected rejected(String content) {
        return rejected(content.getBytes(StandardCharsets.UTF_8));
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

    /** The golden bytes with one raw byte put in place of a piece of text. */
    private static byte[] goldenWithByte(String from, byte replacement) {
        String text = goldenText();
        int at = text.indexOf(from);
        assertThat(at).as("the text to replace is present: %s", from).isNotNegative();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(text.substring(0, at).getBytes(StandardCharsets.UTF_8));
        bytes.write(replacement);
        bytes.writeBytes(text.substring(at + from.length()).getBytes(StandardCharsets.UTF_8));
        return bytes.toByteArray();
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

    // ------------------------------------------------------------------------- the freeze

    @Nested
    @DisplayName("the golden cycle report")
    class TheGoldenReport {

        @Test
        @DisplayName("parses whole: the cycle as the batch identity, the remittance, and seven"
                + " canonical lines - four entries, three of them split by their scheme fee")
        void goldenParsesWhole() {
            ParsedBatch batch = parsedGolden();
            assertThat(batch.externalBatchRef())
                    .as("one cycle per file: the cycle token IS the batch identity")
                    .isEqualTo("2026-09-25-C1");
            assertThat(batch.businessDate()).isEqualTo(CYCLE_DAY);
            assertThat(batch.remittanceReference()).contains("SCH-REM-20260925");
            assertThat(batch.statement()).as("a report carries no statement facts").isEmpty();
            assertThat(batch.declaredLineCount())
                    .as("the entries, counted before any fee split")
                    .isEqualTo(4);
            assertThat(batch.declaredNet().currency()).isEqualTo(EUR);
            assertThat(batch.declaredNet().scale()).isEqualTo(2);
            assertThat(batch.declaredNet().minorUnits())
                    .as("25.00 + 7.50 - 10.00 - 5.00 - 3 x 0.10")
                    .isEqualTo(1_720L);

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(7);
            assertLine(lines.get(0), 1, SettlementLineType.CREDIT_IN, LineDirection.INBOUND,
                    2_500L);
            assertLine(lines.get(1), 2, SettlementLineType.SCHEME_FEE, LineDirection.OUTBOUND,
                    10L);
            assertLine(lines.get(2), 3, SettlementLineType.DEBIT_OUT, LineDirection.OUTBOUND,
                    1_000L);
            assertLine(lines.get(3), 4, SettlementLineType.SCHEME_FEE, LineDirection.OUTBOUND,
                    10L);
            // A return (RT) out of the platform: the direction decides the type, not the code.
            assertLine(lines.get(4), 5, SettlementLineType.DEBIT_OUT, LineDirection.OUTBOUND,
                    500L);
            assertLine(lines.get(5), 6, SettlementLineType.SCHEME_FEE, LineDirection.OUTBOUND,
                    10L);
            // No fee, no split.
            assertLine(lines.get(6), 7, SettlementLineType.CREDIT_IN, LineDirection.INBOUND,
                    750L);

            assertThat(lines.get(0).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.SCHEME_REF, "SCH-PAYIN-0001"),
                            Map.entry(LineReferenceKind.END_TO_END_REF, "E2E-PAYIN-0001"));
            assertThat(lines.get(2).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.SCHEME_REF, "SCH-WDL-0002"),
                            Map.entry(LineReferenceKind.END_TO_END_REF, "E2E-WDL-0002"));
            assertThat(lines.get(4).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.OUR_REF, "RTN-0003"),
                            Map.entry(LineReferenceKind.SCHEME_REF, "SCH-RTN-0003"));
            assertThat(lines.get(6).references())
                    .containsExactly(Map.entry(LineReferenceKind.SCHEME_REF, "SCH-PARK-0004"));
        }

        @Test
        @DisplayName("each fee line names the execution it rode in on (ORIGINAL_REF = its scheme"
                + " reference) and shares its entry's raw-record digest")
        void feeLinesNameTheirEntry() {
            List<ParsedLine> lines = parsedGolden().lines();
            record Split(int entryLine, int feeLine, String schemeRef) {}
            for (Split split :
                    List.of(
                            new Split(0, 1, "SCH-PAYIN-0001"),
                            new Split(2, 3, "SCH-WDL-0002"),
                            new Split(4, 5, "SCH-RTN-0003"))) {
                ParsedLine entry = lines.get(split.entryLine());
                ParsedLine fee = lines.get(split.feeLine());
                assertThat(fee.references())
                        .containsExactly(
                                Map.entry(LineReferenceKind.ORIGINAL_REF, split.schemeRef()));
                assertThat(fee.rawRecordSha256()).isEqualTo(entry.rawRecordSha256());
            }
            assertThat(lines.get(0).rawRecordSha256())
                    .as("two entries are two delivered records")
                    .isNotEqualTo(lines.get(2).rawRecordSha256());
        }

        @Test
        @DisplayName("the fingerprint algorithm is FROZEN by hex literal, deterministic across"
                + " parses, and the raw-record digest is the entry object's own bytes")
        void fingerprintsAreFrozen() {
            ParsedBatch first = parsedGolden();
            ParsedBatch second = parsedGolden();
            List<ParsedLine> lines = first.lines();
            assertThat(HexFormat.of().formatHex(lines.get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo(
                            "b2d477b5e25c172f35efe9152163e9b28baffe7785da5229df82ad2a7b1946dc");
            assertThat(HexFormat.of().formatHex(lines.get(0).rawRecordSha256()))
                    .isEqualTo(
                            "8bde80a51233d2d8f3fc3829be2900c8b2bc84d51da281f67dda9e1e956b1f03");
            assertThat(HexFormat.of().formatHex(lines.get(1).canonicalFingerprint()))
                    .isEqualTo(
                            "f8ee2f903cb758afa402b4a7f877a6e55c7c2c0f5790b8dc6fa2be2145ed0e2b");
            assertThat(HexFormat.of().formatHex(lines.get(4).canonicalFingerprint()))
                    .isEqualTo(
                            "cd8220e592f3e91ffaebae2e53045b200c620c8cfa0726c7238168e03920584d");
            assertThat(HexFormat.of().formatHex(lines.get(4).rawRecordSha256()))
                    .isEqualTo(
                            "14808be15c01869cf26d5738896d6abfeb22d9089acc0654571a5549c01cc83c");
            assertThat(HexFormat.of().formatHex(lines.get(6).canonicalFingerprint()))
                    .isEqualTo(
                            "68d604c792032b6e3ed81de923e4e344ccac7f8916aaf8c771a1228b6406d907");
            assertThat(HexFormat.of().formatHex(lines.get(6).rawRecordSha256()))
                    .isEqualTo(
                            "9c09e450bf8b0489b422b3344323fd8172517edeab9124c18fe2d38169c750ff");
            assertThat(lines.get(6).rawRecordSha256())
                    .as("the digest covers the entry object alone, braces included")
                    .isEqualTo(
                            ParsedLine.sha256(
                                    ("{\n"
                                                    + "      \"seq\": 4,\n"
                                                    + "      \"code\": \"CT\",\n"
                                                    + "      \"dir\": \"C\",\n"
                                                    + "      \"amount\": \"7.50\",\n"
                                                    + "      \"schemeRef\": \"SCH-PARK-0004\"\n"
                                                    + "    }")
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
                                    SettlementLineType.CREDIT_IN, LineDirection.INBOUND, 2,
                                    3_250L, 2),
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.DEBIT_OUT, LineDirection.OUTBOUND, 2,
                                    1_500L, 2),
                            new SettlementBatchStore.TotalRow(
                                    SettlementLineType.SCHEME_FEE, LineDirection.OUTBOUND, 3,
                                    30L, 2));
        }

        @Test
        @DisplayName("a net-payable cycle - a negative net - parses: money the platform owes")
        void netPayableCycleParses() {
            String text =
                    with(
                            goldenWith("\"amount\": \"25.00\"", "\"amount\": \"4.40\""),
                            "\"net\": \"17.20\"",
                            "\"net\": \"-3.40\"");
            ParsedBatch batch = parsed(text);
            assertThat(batch.declaredNet().minorUnits())
                    .as("4.40 + 7.50 - 10.00 - 5.00 - 0.30")
                    .isEqualTo(-340L);
            assertThat(batch.lines()).hasSize(7);
            assertThat(batch.lines().get(0).amount().minorUnits()).isEqualTo(440L);
        }

        @Test
        @DisplayName("whitespace and member order are free: the report minified and reordered"
                + " parses to the same canonical lines, its records to their own digests")
        void whitespaceAndOrderAreFree() {
            ParsedBatch golden = parsedGolden();
            ParsedBatch compact = parsed(COMPACT_REORDERED);
            assertThat(compact.externalBatchRef()).isEqualTo(golden.externalBatchRef());
            assertThat(compact.remittanceReference()).isEqualTo(golden.remittanceReference());
            assertThat(compact.declaredNet()).isEqualTo(golden.declaredNet());
            assertThat(compact.lines()).hasSameSizeAs(golden.lines());
            for (int i = 0; i < golden.lines().size(); i++) {
                assertThat(compact.lines().get(i).canonicalFingerprint())
                        .as("line %s is the same economic statement", i + 1)
                        .isEqualTo(golden.lines().get(i).canonicalFingerprint());
            }
            assertThat(compact.lines().get(6).rawRecordSha256())
                    .as("a differently written record is a different delivered record")
                    .isNotEqualTo(golden.lines().get(6).rawRecordSha256());
        }

        @Test
        @DisplayName("a BOM is tolerated with nothing moved; CRLF endings move no fingerprint")
        void bomAndCrlfTolerated() {
            ParsedBatch lf = parsedGolden();
            String bom = String.valueOf((char) 0xFEFF);
            ParsedBatch withBom = parsed(bom + goldenText());
            ParsedBatch crlf = parsed(bom + goldenText().replace("\n", "\r\n"));
            assertThat(withBom.lines()).hasSize(7);
            assertThat(crlf.lines()).hasSize(7);
            for (int i = 0; i < lf.lines().size(); i++) {
                assertThat(withBom.lines().get(i).rawRecordSha256())
                        .as("line %s: the BOM is not part of any record", i + 1)
                        .isEqualTo(lf.lines().get(i).rawRecordSha256());
                assertThat(crlf.lines().get(i).canonicalFingerprint())
                        .as("line %s: line endings are whitespace, not economics", i + 1)
                        .isEqualTo(lf.lines().get(i).canonicalFingerprint());
            }
        }

        @Test
        @DisplayName("a cycle with no entries parses: net zero, no lines")
        void emptyCycleParses() {
            ParsedBatch batch =
                    parsed(
                            "{\"format\":\"SIM_SCHEME_JSON\",\"version\":1,"
                                    + "\"cycle\":\"2026-09-26-C1\",\"currency\":\"EUR\","
                                    + "\"businessDate\":\"2026-09-26\","
                                    + "\"remittanceReference\":\"SCH-REM-20260926\","
                                    + "\"entries\":[],\"net\":\"0.00\",\"entryCount\":0}");
            assertThat(batch.lines()).isEmpty();
            assertThat(batch.declaredLineCount()).isZero();
            assertThat(batch.declaredNet().isZero()).isTrue();
        }

        @Test
        @DisplayName("string escapes are decoded, and the narrative reaches no canonical output")
        void escapesDecodedNarrativeNeverCarried() {
            ParsedBatch batch =
                    parsed(
                            goldenWith(
                                    "\"schemeRef\": \"SCH-PARK-0004\"",
                                    "\"schemeRef\": \"SCH-PARK-" + "\\" + "u0030004\""));
            assertThat(batch.lines().get(6).references())
                    .containsExactly(Map.entry(LineReferenceKind.SCHEME_REF, "SCH-PARK-0004"));
            for (ParsedLine line : parsedGolden().lines()) {
                assertThat(line.toString()).doesNotContain("Pay-in", "order 42");
                assertThat(line.references().values()).noneMatch(value -> value.contains("42"));
            }
        }
    }

    // -------------------------------------------------------------- each fault, one at a time

    @Nested
    @DisplayName("each fault rejects the whole file")
    class EachFaultRejectsWhole {

        @Test
        @DisplayName("an empty file is MALFORMED, named 'json' for the file as a whole")
        void emptyFile() {
            SettlementFormat.Result.Rejected verdict = rejected("");
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).isEmpty();
                                assertThat(defect.field()).contains("json");
                            });
        }

        @Test
        @DisplayName("another format, another version, or none declared is UNSUPPORTED_FORMAT")
        void unsupportedFormat() {
            assertSingleDefect(
                    rejected(goldenWith("\"format\": \"SIM_SCHEME_JSON\"",
                            "\"format\": \"SIM_PSP_CSV\"")),
                    RejectionCode.UNSUPPORTED_FORMAT, 2, "format");
            assertSingleDefect(
                    rejected(goldenWith("\"version\": 1", "\"version\": 2")),
                    RejectionCode.UNSUPPORTED_FORMAT, 3, "version");
            // The integer 1, not the text "1".
            assertSingleDefect(
                    rejected(goldenWith("\"version\": 1", "\"version\": \"1\"")),
                    RejectionCode.UNSUPPORTED_FORMAT, 3, "version");
            SettlementFormat.Result.Rejected undeclared =
                    rejected(goldenWith("  \"format\": \"SIM_SCHEME_JSON\",\n", ""));
            assertThat(undeclared.code()).isEqualTo(RejectionCode.UNSUPPORTED_FORMAT);
            assertThat(undeclared.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).isEmpty();
                                assertThat(defect.field()).contains("format");
                            });
        }

        @Test
        @DisplayName("a file declaring several cycles - twice, or as a list - is"
                + " UNSUPPORTED_FORMAT: multi-part files are not v1's")
        void severalCyclesUnsupported() {
            assertSingleDefect(
                    rejected(goldenWith("\"cycle\": \"2026-09-25-C1\",",
                            "\"cycle\": \"2026-09-25-C1\",\n  \"cycle\": \"2026-09-25-C2\",")),
                    RejectionCode.UNSUPPORTED_FORMAT, 5, "cycle");
            assertSingleDefect(
                    rejected(goldenWith("\"cycle\": \"2026-09-25-C1\"",
                            "\"cycle\": [\"2026-09-25-C1\", \"2026-09-25-C2\"]")),
                    RejectionCode.UNSUPPORTED_FORMAT, 4, "cycle");
        }

        @Test
        @DisplayName("a currency the platform cannot represent is UNKNOWN_CURRENCY")
        void unknownCurrency() {
            assertSingleDefect(
                    rejected(goldenWith("\"currency\": \"EUR\"", "\"currency\": \"XXX\"")),
                    RejectionCode.UNKNOWN_CURRENCY, 5, "currency");
        }

        @Test
        @DisplayName("an amount with more decimals than the currency's scale is SCALE_MISMATCH,"
                + " named to its line and field")
        void scaleMismatch() {
            SettlementFormat.Result.Rejected amount =
                    rejected(goldenWith("\"amount\": \"25.00\"", "\"amount\": \"25.001\""));
            assertThat(amount.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
            assertThat(amount.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
                                assertThat(defect.lineNo()).contains(13);
                                assertThat(defect.field()).contains("amount");
                            });
            SettlementFormat.Result.Rejected net =
                    rejected(goldenWith("\"net\": \"17.20\"", "\"net\": \"17.200\""));
            assertThat(net.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
            assertThat(net.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(45);
                                assertThat(defect.field()).contains("net");
                            });
        }

        @Test
        @DisplayName("an entry count mismatch and a net mismatch - off by one cent - are each"
                + " CONTROL_TOTAL_MISMATCH")
        void controlTotals() {
            assertSingleDefect(
                    rejected(goldenWith("\"entryCount\": 4", "\"entryCount\": 3")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 46, "entryCount");
            assertSingleDefect(
                    rejected(goldenWith("\"net\": \"17.20\"", "\"net\": \"17.21\"")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 45, "net");
        }

        @Test
        @DisplayName("each member malformed in turn is MALFORMED at its line and field")
        void eachMemberMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults =
                    List.of(
                            // An undeclared member is named 'member', never by its name.
                            new Fault("\"currency\": \"EUR\",",
                                    "\"currency\": \"EUR\",\n  \"operator\": \"desk-7\",", 6,
                                    "member"),
                            // Every member exactly once.
                            new Fault("\"currency\": \"EUR\",",
                                    "\"currency\": \"EUR\",\n  \"currency\": \"EUR\",", 6,
                                    "currency"),
                            new Fault("\"currency\": \"EUR\"", "\"currency\": \"eur\"", 5,
                                    "currency"),
                            new Fault("\"cycle\": \"2026-09-25-C1\"",
                                    "\"cycle\": \"" + "C".repeat(65) + "\"", 4, "cycle"),
                            new Fault("\"businessDate\": \"2026-09-25\"",
                                    "\"businessDate\": \"2026-02-30\"", 6, "businessDate"),
                            new Fault("\"SCH-REM-20260925\"", "\"PSP-REM-20260925\"", 7,
                                    "remittanceReference"),
                            // A JSON number invites a binary float: amounts are strings.
                            new Fault("\"amount\": \"10.00\"", "\"amount\": 10.00", 23, "amount"),
                            new Fault("\"net\": \"17.20\"", "\"net\": 17.20", 45, "net"),
                            // The counts are integers, not text.
                            new Fault("\"entryCount\": 4", "\"entryCount\": \"4\"", 46,
                                    "entryCount"),
                            new Fault("\"amount\": \"10.00\",\n      \"fee\": \"0.10\"",
                                    "\"amount\": \"10.00\",\n      \"fee\": \"-0.10\"", 24, "fee"),
                            // A line's amount is positive; zero is no line.
                            new Fault("\"amount\": \"7.50\"", "\"amount\": \"0.00\"", 41,
                                    "amount"),
                            new Fault("\"dir\": \"C\",\n      \"amount\": \"7.50\"",
                                    "\"dir\": \"X\",\n      \"amount\": \"7.50\"", 40, "dir"),
                            new Fault("\"code\": \"RT\"", "\"code\": \"XX\"", 30, "code"),
                            // 1..n strictly in order.
                            new Fault("\"seq\": 3", "\"seq\": 4", 29, "seq"),
                            new Fault("\"schemeRef\": \"SCH-WDL-0002\"",
                                    "\"schemeRef\": \"SCH WDL 0002\"", 25, "schemeRef"),
                            // A required member missing is named at its entry's opening brace.
                            new Fault(
                                    "\"amount\": \"7.50\",\n      \"schemeRef\": \"SCH-PARK-0004\"",
                                    "\"amount\": \"7.50\"", 37, "schemeRef"),
                            // A reference is present by its value, never by an empty one.
                            new Fault("\"ourRef\": \"RTN-0003\"", "\"ourRef\": \"\"", 35,
                                    "ourRef"),
                            new Fault("\"ourRef\": \"RTN-0003\"",
                                    "\"ourRef\": \"RTN-0003\",\n      \"channel\": \"x\"", 36,
                                    "member"),
                            new Fault("\"narrative\": \"Pay-in for order 42\"",
                                    "\"narrative\": \"" + "x".repeat(141) + "\"", 17,
                                    "narrative"),
                            // true, false and null are read, and v1 uses none of them.
                            new Fault("\"narrative\": \"Pay-in for order 42\"",
                                    "\"narrative\": null", 17, "narrative"));
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
        @DisplayName("the reader is strict JSON: each departure is MALFORMED, named 'json' at the"
                + " physical line it happened on")
        void strictJson() {
            record Departure(String description, byte[] content, int line) {}
            String text = goldenText();
            List<Departure> departures =
                    List.of(
                            new Departure("anything after the root",
                                    (text + "{}\n").getBytes(StandardCharsets.UTF_8), 48),
                            new Departure("a trailing comma",
                                    goldenWith("\"ourRef\": \"RTN-0003\"\n",
                                                    "\"ourRef\": \"RTN-0003\",\n")
                                            .getBytes(StandardCharsets.UTF_8), 36),
                            new Departure("a leading zero",
                                    goldenWith("\"seq\": 1,", "\"seq\": 01,")
                                            .getBytes(StandardCharsets.UTF_8), 10),
                            new Departure("a raw control character in a string",
                                    goldenWith("Pay-in for order 42", "Pay-in for\torder 42")
                                            .getBytes(StandardCharsets.UTF_8), 17),
                            new Departure("an unknown escape",
                                    goldenWith("Pay-in for order 42", "Pay-in for order \\x42")
                                            .getBytes(StandardCharsets.UTF_8), 17),
                            new Departure("single quotes",
                                    goldenWith("\"currency\": \"EUR\"", "\"currency\": 'EUR'")
                                            .getBytes(StandardCharsets.UTF_8), 5),
                            new Departure("a comment",
                                    goldenWith("\"version\": 1,", "\"version\": 1, // v1")
                                            .getBytes(StandardCharsets.UTF_8), 3),
                            new Departure("a root that is not an object",
                                    ("[" + text + "]").getBytes(StandardCharsets.UTF_8), 1),
                            new Departure("a document nested beyond any v1 reading",
                                    goldenWith("\"narrative\": \"Pay-in for order 42\"",
                                                    "\"narrative\": " + "[".repeat(9) + "\"x\""
                                                            + "]".repeat(9))
                                            .getBytes(StandardCharsets.UTF_8), 17),
                            new Departure("bytes that are not UTF-8",
                                    goldenWithByte("order 42", (byte) 0xFF), 17),
                            new Departure("a document that stops mid-way",
                                    text.substring(0, text.indexOf("  \"net\""))
                                            .getBytes(StandardCharsets.UTF_8), 45));
            for (Departure departure : departures) {
                SettlementFormat.Result.Rejected verdict = rejected(departure.content());
                assertThat(verdict.code())
                        .as(departure.description())
                        .isEqualTo(RejectionCode.MALFORMED);
                assertThat(verdict.defects())
                        .as(departure.description())
                        .singleElement()
                        .satisfies(
                                defect -> {
                                    assertThat(defect.lineNo()).contains(departure.line());
                                    assertThat(defect.field()).contains("json");
                                });
            }
        }

        @Test
        @DisplayName("no defect ever carries a value - not an undeclared member's name, not a"
                + " token, not an amount: fields are NAMES, positions are numbers")
        void defectsCarryNoValue() {
            List<SettlementFormat.Result.Rejected> verdicts =
                    List.of(
                            rejected(goldenWith("\"currency\": \"EUR\",",
                                    "\"currency\": \"EUR\",\n  \"customerName\": \"Jane Doe\",")),
                            rejected(goldenWith("\"cycle\": \"2026-09-25-C1\"",
                                    "\"cycle\": \"" + "Q".repeat(65) + "\"")),
                            rejected(goldenWith("\"amount\": \"25.00\"", "\"amount\": \"25.001\"")),
                            rejected(goldenWith("\"net\": \"17.20\"", "\"net\": \"17.21\"")));
            for (SettlementFormat.Result.Rejected verdict : verdicts) {
                assertThat(verdict.toString())
                        .doesNotContain("customerName", "Jane", "QQQQ", "25.001", "17.21");
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
        @DisplayName("the golden report passes, and its record count is its entries' - pretty"
                + " or minified, whitespace is not a record")
        void goldenPasses() {
            DeliveryScreen.Screening screening = FORMAT.screen(golden());
            assertThat(screening.finding()).isEmpty();
            assertThat(screening.lineCount()).isEqualTo(4);
            DeliveryScreen.Screening compact = screened(COMPACT_REORDERED);
            assertThat(compact.finding()).isEmpty();
            assertThat(compact.lineCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("a card number in the NARRATIVE - declared free text - is refused with its"
                + " line and field, written plain or grouped")
        void panInNarrativeRefused() {
            for (String card : List.of(PAN, "4111 1111 1111 1111")) {
                DeliveryScreen.Screening screening =
                        screened(goldenWith("Pay-in for order 42", "Paid with card " + card));
                assertThat(screening.finding())
                        .as("card written as %s", card)
                        .hasValueSatisfying(
                                finding -> {
                                    assertThat(finding.reason())
                                            .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                    assertThat(finding.lineNo()).isEqualTo(17);
                                    assertThat(finding.fieldName()).contains("narrative");
                                });
            }
        }

        @Test
        @DisplayName("a card number written in unicode escapes is decoded, then refused")
        void escapedPanRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith("Pay-in for order 42",
                            "card " + "\\" + "u0034" + PAN.substring(1)));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(17);
                                assertThat(finding.fieldName()).contains("narrative");
                            });
        }

        @Test
        @DisplayName("an account identifier in the NARRATIVE is refused as such (INV-RAIL-03)")
        void ibanInNarrativeRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith("Pay-in for order 42", "Pay to " + IBAN + " today"));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(17);
                                assertThat(finding.fieldName()).contains("narrative");
                            });
        }

        @Test
        @DisplayName("an account identifier as the SCHEME reference fails its class - which"
                + " admits no instrument shape - and is refused, never retained as malformed (C6)")
        void ibanAsSchemeReferenceRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith("\"schemeRef\": \"SCH-WDL-0002\"",
                            "\"schemeRef\": \"" + IBAN + "\""));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(25);
                                assertThat(finding.fieldName()).contains("schemeRef");
                            });
        }

        @Test
        @DisplayName("a card number behind letters in a reference, or as the cycle token, fails"
                + " the class and is refused")
        void panAsReferenceRefused() {
            DeliveryScreen.Screening endToEnd =
                    screened(goldenWith("\"E2E-PAYIN-0001\"", "\"E2E-" + PAN + "\""));
            assertThat(endToEnd.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(16);
                                assertThat(finding.fieldName()).contains("endToEndRef");
                            });
            DeliveryScreen.Screening cycle =
                    screened(goldenWith("\"cycle\": \"2026-09-25-C1\"",
                            "\"cycle\": \"" + PAN + "\""));
            assertThat(cycle.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(4);
                                assertThat(finding.fieldName()).contains("cycle");
                            });
        }

        @Test
        @DisplayName("the platform's OWN minted reference - a dashless UUIDv7 - is admitted"
                + " exactly as an end-to-end or our reference, even when its hex holds a digit"
                + " run of card length; the same run in any other shape still fails the class")
        void thePlatformsOwnReferenceIsAdmitted() {
            // A UUIDv7 without dashes whose hex happens to be all digits: 12, '7', 3, '8', 15.
            String minted = "019234567890" + "7" + "123" + "8" + "123456789012345";
            assertThat(minted).hasSize(32);
            String endToEnd = goldenWith("\"E2E-PAYIN-0001\"", "\"" + minted + "\"");
            assertThat(screened(endToEnd).finding()).isEmpty();
            assertThat(parsed(endToEnd).lines().get(0).references())
                    .containsEntry(LineReferenceKind.END_TO_END_REF, minted);
            String ours = goldenWith("\"RTN-0003\"", "\"" + minted + "\"");
            assertThat(screened(ours).finding()).isEmpty();
            assertThat(parsed(ours).lines().get(4).references())
                    .containsEntry(LineReferenceKind.OUR_REF, minted);
            // Not the minted shape (no version nibble): the no-instrument class, and refused.
            String notMinted = "0192345678901123" + "8123456789012345";
            assertThat(rejected(goldenWith("\"E2E-PAYIN-0001\"", "\"" + notMinted + "\"")).code())
                    .isEqualTo(RejectionCode.MALFORMED);
            // The scheme's own reference is not ours: the same run fails its class.
            assertThat(rejected(goldenWith("\"SCH-WDL-0002\"", "\"" + minted + "\"")).code())
                    .isEqualTo(RejectionCode.MALFORMED);
        }

        @Test
        @DisplayName("a card number as a JSON NUMBER fails its member's class and is refused -"
                + " a number is text in the file too")
        void panAsNumberRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith("\"amount\": \"10.00\"", "\"amount\": " + PAN));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(23);
                                assertThat(finding.fieldName()).contains("amount");
                            });
        }

        @Test
        @DisplayName("an undeclared member's NAME and value are free text - refused, and named"
                + " 'member', never by what the member is called")
        void undeclaredMemberScreened() {
            DeliveryScreen.Screening name =
                    screened(goldenWith("\"currency\": \"EUR\",",
                            "\"currency\": \"EUR\",\n  \"" + PAN + "\": \"x\","));
            assertThat(name.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(6);
                                assertThat(finding.fieldName()).contains("member");
                            });
            DeliveryScreen.Screening value =
                    screened(goldenWith("\"currency\": \"EUR\",",
                            "\"currency\": \"EUR\",\n  \"note\": \"pay to " + IBAN + "\","));
            assertThat(value.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(6);
                                assertThat(finding.fieldName()).contains("member");
                            });
        }

        @Test
        @DisplayName("a CLEAN malformed value - no instrument data - passes the screen, so the"
                + " corrupt delivery is stored as evidence and rejected at parse")
        void cleanMalformedPasses() {
            for (String fault :
                    List.of(
                            goldenWith("\"amount\": \"10.00\"", "\"amount\": 10.00"),
                            goldenWith("\"cycle\": \"2026-09-25-C1\"",
                                    "\"cycle\": \"" + "C".repeat(65) + "\""),
                            goldenWith("\"code\": \"RT\"", "\"code\": \"XX\""))) {
                assertThat(screened(fault).finding()).isEmpty();
                assertThat(rejected(fault).code()).isEqualTo(RejectionCode.MALFORMED);
            }
        }

        @Test
        @DisplayName("bytes that are not one well-formed JSON object are screened as one"
                + " conservative stream")
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
            // Anything after the root makes the whole stream unstructured: every byte screened.
            DeliveryScreen.Screening trailing = screened(goldenText() + IBAN + "\n");
            assertThat(trailing.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(48);
                                assertThat(finding.fieldName()).isEmpty();
                            });
            // Not UTF-8: the conservative walk, which counts physical lines.
            DeliveryScreen.Screening notUtf8 =
                    FORMAT.screen(goldenWithByte("order 42", (byte) 0xFF));
            assertThat(notUtf8.finding()).isEmpty();
            assertThat(notUtf8.lineCount()).isEqualTo(47);
        }
    }

    // ------------------------------------------------------------------------ the identity

    @Test
    @DisplayName("the format names itself - SIM_SCHEME_JSON, version 1 - and its remittance"
            + " pattern admits the scheme's references alone")
    void identity() {
        assertThat(FORMAT.id()).isEqualTo(SettlementFormatId.SIM_SCHEME_JSON);
        assertThat(FORMAT.version()).isEqualTo(1);
        assertThat("SCH-REM-20260925").matches(SimSchemeJsonFormat.REMITTANCE_REFERENCE);
        assertThat("PSP-REM-20260925").doesNotMatch(SimSchemeJsonFormat.REMITTANCE_REFERENCE);
        assertThat("SCH-REM-" + PAN).doesNotMatch(SimSchemeJsonFormat.REMITTANCE_REFERENCE);
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
        assertThat(line.businessDate()).isEqualTo(CYCLE_DAY);
        assertThat(line.settlementDate())
                .as("the cycle settles on the report's business date")
                .contains(CYCLE_DAY);
        assertThat(line.valueDate()).as("a scheme report carries no value date").isEmpty();
    }
}
