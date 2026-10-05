package com.finapp.settlement.format.simcorridor;

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
 * `SIM_CORRIDOR_CSV` v1, frozen (`P9-TSK-014`, ADR-0066 §8 - the {@code RailMoneySemanticsArePinnedTest}
 * rule applied to a format): the golden daily report's every canonical line with BOTH references, the
 * fee split naming its credit by the provider's reference, the fingerprints and raw-record digests
 * pinned by hex literal, each field's fault rejecting the WHOLE file at its line and field, a JPY and
 * a BHD day at their own scales, and the field-class screen refusing instrument data wherever it
 * hides. A behaviour change here is a NEW format version, never an edit of this one.
 */
@DisplayName("the simulated corridor provider's CSV settlement report format, version 1 (P9-TSK-014)")
class SimCorridorCsvFormatTest {

    private static final SimCorridorCsvFormat FORMAT = SimCorridorCsvFormat.INSTANCE;

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final LocalDate REPORT_DAY = LocalDate.of(2026, 10, 5);

    /** An international account identifier shape. */
    private static final String IBAN = "GB82WEST12345698765432";

    /** A Luhn-valid card number. */
    private static final String PAN = "4111111111111111";

    private static byte[] golden() {
        try (var stream = Objects.requireNonNull(
                SimCorridorCsvFormatTest.class.getResourceAsStream("/format/simcorridor/golden-v1.csv"),
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

    private static SettlementFormat.Result.Rejected rejected(String content) {
        SettlementFormat.Result result = FORMAT.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result).as("the whole file is rejected (INV-SET-07)").isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    private static DeliveryScreen.Screening screened(String content) {
        return FORMAT.screen(content.getBytes(StandardCharsets.UTF_8));
    }

    /** The golden file with one piece of text replaced - one fault at a time. */
    private static String goldenWith(String from, String to) {
        String text = goldenText();
        int at = text.indexOf(from);
        assertThat(at).as("the text to replace is present: %s", from).isNotNegative();
        assertThat(text.indexOf(from, at + 1)).as("the text to replace is unique: %s", from).isNegative();
        return text.replace(from, to);
    }

    private static void assertSingleDefect(
            SettlementFormat.Result.Rejected verdict, RejectionCode code, int line, String field) {
        assertThat(verdict.code()).isEqualTo(code);
        assertThat(verdict.defects()).singleElement().satisfies(defect -> {
            assertThat(defect.code()).isEqualTo(code);
            assertThat(defect.lineNo()).contains(line);
            assertThat(defect.field()).contains(field);
        });
    }

    private static void assertRefused(DeliveryScreen.Screening screening, RefusalReason reason, int line, String field) {
        assertThat(screening.finding()).hasValueSatisfying(finding -> {
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
        @DisplayName("parses whole: the batch identity, the XBA remittance and five canonical lines - three"
                + " records, two of them split by their fee - each carrying both references")
        void goldenParsesWhole() {
            ParsedBatch batch = parsed(golden());
            assertThat(batch.externalBatchRef()).isEqualTo("XB-2026-10-05-USD");
            assertThat(batch.businessDate()).isEqualTo(REPORT_DAY);
            assertThat(batch.remittanceReference()).contains("XBA-20261005");
            assertThat(batch.statement()).isEmpty();
            assertThat(batch.declaredLineCount()).as("records, before any fee split").isEqualTo(3);
            assertThat(batch.declaredNet().minorUnits())
                    .as("-1,079.60 - 250.00 + 500.00 - 2 x 1.20")
                    .isEqualTo(-83_200L);

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(5);
            assertLine(lines.get(0), 1, SettlementLineType.PAYOUT_EXECUTED, LineDirection.OUTBOUND, 107_960L);
            assertLine(lines.get(1), 2, SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND, 120L);
            assertLine(lines.get(2), 3, SettlementLineType.PAYOUT_EXECUTED, LineDirection.OUTBOUND, 25_000L);
            assertLine(lines.get(3), 4, SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND, 120L);
            assertLine(lines.get(4), 5, SettlementLineType.PAYOUT_RETURNED, LineDirection.INBOUND, 50_000L);

            assertThat(lines.get(0).references()).containsExactly(
                    Map.entry(LineReferenceKind.END_TO_END_REF, "XB-0001-E2E-a"),
                    Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "xp_a1b2c3"));
            assertThat(lines.get(4).references())
                    .as("a bounce names the credit coming back by its E")
                    .containsExactly(
                            Map.entry(LineReferenceKind.END_TO_END_REF, "XB-0003-E2E-c"),
                            Map.entry(LineReferenceKind.PAYOUT_PROVIDER_REF, "xp_g7h8i9"));
        }

        @Test
        @DisplayName("each fee line names the credit it rode in on by the provider's reference - the key"
                + " reconciliation reads a PAYOUT_FEE's original by - and shares its record's digest")
        void feeLinesNameTheirCredit() {
            List<ParsedLine> lines = parsed(golden()).lines();
            assertThat(lines.get(1).references())
                    .containsExactly(Map.entry(LineReferenceKind.ORIGINAL_REF, "xp_a1b2c3"));
            assertThat(lines.get(3).references())
                    .containsExactly(Map.entry(LineReferenceKind.ORIGINAL_REF, "xp_d4e5f6"));
            assertThat(lines.get(1).rawRecordSha256()).isEqualTo(lines.get(0).rawRecordSha256());
            assertThat(lines.get(3).rawRecordSha256()).isEqualTo(lines.get(2).rawRecordSha256());
        }

        @Test
        @DisplayName("the fingerprint algorithm is FROZEN by hex literal, deterministic across parses")
        void fingerprintsAreFrozen() {
            List<ParsedLine> lines = parsed(golden()).lines();
            List<ParsedLine> again = parsed(golden()).lines();
            assertThat(HexFormat.of().formatHex(lines.get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo("f64f577c7ff251b6cc3b87f911ba534a983b0e37954fcbfd48dcc3b7e6f96f01");
            assertThat(HexFormat.of().formatHex(lines.get(1).canonicalFingerprint())).isEqualTo("7276e59fc2c234e2c38722daa5cf58b3453fbbc1007d3db4ecebb5b3c2517ebe");
            assertThat(HexFormat.of().formatHex(lines.get(4).canonicalFingerprint())).isEqualTo("f4b15c5d6c7e4d06801d5a374ca1167d26f491b78d05034dacb428c8add525fa");
            assertThat(lines.get(4).rawRecordSha256())
                    .as("the digest covers the record's own text, no line ending")
                    .isEqualTo(ParsedLine.sha256(
                            "D,3,BOUNCED,500.00,,xp_g7h8i9,XB-0003-E2E-c".getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < lines.size(); i++) {
                assertThat(lines.get(i).canonicalFingerprint()).isEqualTo(again.get(i).canonicalFingerprint());
            }
        }

        @Test
        @DisplayName("the Money fold per (type, direction) is the report's own arithmetic")
        void totalsFold() {
            assertThat(FileParsing.totalsOf(parsed(golden()))).containsExactlyInAnyOrder(
                    new SettlementBatchStore.TotalRow(SettlementLineType.PAYOUT_EXECUTED, LineDirection.OUTBOUND, 2, 132_960L, 2),
                    new SettlementBatchStore.TotalRow(SettlementLineType.PAYOUT_RETURNED, LineDirection.INBOUND, 1, 50_000L, 2),
                    new SettlementBatchStore.TotalRow(SettlementLineType.PAYOUT_FEE, LineDirection.OUTBOUND, 2, 240L, 2));
        }

        @Test
        @DisplayName("a JPY day parses at scale 0 and a BHD day at scale 3; a day with no credits parses empty")
        void otherCurrenciesAndTheEmptyDay() {
            ParsedBatch jpy = parsed("H,SIM_CORRIDOR_CSV,1,XB-2026-10-05-JPY,JPY,2026-10-05\n"
                    + "D,1,CREDITED,-150000,180,xp_j1,XB-0004-E2E-d\n"
                    + "T,1,-150180,XBA-20261006\n");
            assertThat(jpy.lines().get(0).amount().scale()).isZero();
            assertThat(jpy.lines().get(0).amount().minorUnits()).isEqualTo(150_000L);
            assertThat(jpy.lines().get(1).amount().minorUnits()).isEqualTo(180L);
            ParsedBatch bhd = parsed("H,SIM_CORRIDOR_CSV,1,XB-2026-10-05-BHD,BHD,2026-10-05\n"
                    + "D,1,CREDITED,-376.500,0.450,xp_b1,XB-0005-E2E-e\n"
                    + "T,1,-376.950,XBA-20261007\n");
            assertThat(bhd.lines().get(0).amount().scale()).isEqualTo(3);
            assertThat(bhd.lines().get(0).amount().minorUnits()).isEqualTo(376_500L);
            assertThat(bhd.declaredNet().minorUnits()).isEqualTo(-376_950L);
            ParsedBatch empty = parsed("H,SIM_CORRIDOR_CSV,1,XB-2026-10-06-USD,USD,2026-10-06\nT,0,0.00,XBA-20261008\n");
            assertThat(empty.lines()).isEmpty();
            assertThat(empty.declaredNet().isZero()).isTrue();
        }

        @Test
        @DisplayName("a BOM, CRLF endings and the trailing newline are tolerated - no fingerprint moves")
        void bomAndCrlfTolerated() {
            List<ParsedLine> lf = parsed(golden()).lines();
            List<ParsedLine> crlf = parsed((char) 0xFEFF + goldenText().replace("\n", "\r\n")).lines();
            for (int i = 0; i < lf.size(); i++) {
                assertThat(crlf.get(i).canonicalFingerprint()).isEqualTo(lf.get(i).canonicalFingerprint());
                assertThat(crlf.get(i).rawRecordSha256()).isEqualTo(lf.get(i).rawRecordSha256());
            }
        }
    }

    // -------------------------------------------------------------- each fault, one at a time

    @Nested
    @DisplayName("each fault rejects the whole file")
    class EachFaultRejectsWhole {

        @Test
        @DisplayName("each field malformed in turn is MALFORMED at its line and field - both references required")
        void eachFieldMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults = List.of(
                    new Fault("H,SIM_CORRIDOR_CSV,1,", "H,SIM_CORRIDOR_CSV,x,", 1, "formatVersion"),
                    new Fault("XB-2026-10-05-USD", "XB 2026-10-05-USD", 1, "batchRef"),
                    new Fault(",USD,2026-10-05\n", ",usd,2026-10-05\n", 1, "currency"),
                    new Fault(",USD,2026-10-05\n", ",USD,2026-13-05\n", 1, "businessDate"),
                    new Fault("D,2,CREDITED", "D,x,CREDITED", 3, "seq"),
                    new Fault("D,2,CREDITED", "D,2,credited", 3, "code"),
                    new Fault("-250.00,1.20", "-250.0O,1.20", 3, "amount"),
                    new Fault("-250.00,1.20", "-250.00,-1.20", 3, "fee"),
                    new Fault("xp_d4e5f6", "", 3, "providerRef"),
                    new Fault("xp_d4e5f6", "xp d4e5f6", 3, "providerRef"),
                    new Fault("XB-0002-E2E-b", "", 3, "endToEndRef"),
                    new Fault("XB-0002-E2E-b", "XB/0002", 3, "endToEndRef"),
                    new Fault("XB-0002-E2E-b", "X".repeat(36), 3, "endToEndRef"),
                    new Fault("XB-0002-E2E-b", "XB-0002,E2E-b", 3, "recordType"),
                    new Fault("T,3,-832.00,", "T,three,-832.00,", 5, "count"),
                    new Fault("-832.00,", "-832.0O,", 5, "net"),
                    new Fault("XBA-20261005", "PAY-REM-20261005", 5, "remittanceRef"));
            for (Fault fault : faults) {
                SettlementFormat.Result.Rejected verdict = rejected(goldenWith(fault.from(), fault.to()));
                assertThat(verdict.code())
                        .as("fault %s -> %s", fault.to(), fault.field())
                        .isIn(RejectionCode.MALFORMED, RejectionCode.UNSUPPORTED_FORMAT);
                assertThat(verdict.defects())
                        .as("fault %s names its line and field", fault.to())
                        .anySatisfy(defect -> {
                            assertThat(defect.lineNo()).contains(fault.line());
                            assertThat(defect.field()).contains(fault.field());
                        });
            }
        }

        @Test
        @DisplayName("another format or version is UNSUPPORTED_FORMAT; an unknown currency UNKNOWN_CURRENCY;"
                + " a third decimal on a USD amount SCALE_MISMATCH; the totals CONTROL_TOTAL_MISMATCH")
        void theWholeFileVerdicts() {
            assertSingleDefect(rejected(goldenWith("H,SIM_CORRIDOR_CSV,1,", "H,SIM_CORRIDOR_CSV,2,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatVersion");
            assertSingleDefect(rejected(goldenWith("H,SIM_CORRIDOR_CSV,1,", "H,SIM_PAYOUT_CSV,1,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatId");
            assertSingleDefect(rejected(goldenWith(",USD,2026-10-05\n", ",XXX,2026-10-05\n")),
                    RejectionCode.UNKNOWN_CURRENCY, 1, "currency");
            assertSingleDefect(rejected(goldenWith("-250.00,1.20", "-250.001,1.20")),
                    RejectionCode.SCALE_MISMATCH, 3, "amount");
            assertSingleDefect(rejected(goldenWith("T,3,-832.00,", "T,2,-832.00,")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 5, "count");
            assertSingleDefect(rejected(goldenWith("T,3,-832.00,", "T,3,-831.99,")),
                    RejectionCode.CONTROL_TOTAL_MISMATCH, 5, "net");
        }

        @Test
        @DisplayName("a known code carrying the wrong sign is MALFORMED at its amount; an unknown well-formed"
                + " code is KEPT as OTHER_OUT by its sign, its fee still split")
        void codesAndSigns() {
            assertSingleDefect(rejected(goldenWith("D,1,CREDITED,-1079.60", "D,1,CREDITED,1079.60")),
                    RejectionCode.MALFORMED, 2, "amount");
            assertSingleDefect(rejected(goldenWith("D,3,BOUNCED,500.00", "D,3,BOUNCED,-500.00")),
                    RejectionCode.MALFORMED, 4, "amount");
            ParsedBatch held = parsed(goldenWith("D,2,CREDITED,-250.00", "D,2,ON_HOLD,-250.00"));
            assertLine(held.lines().get(2), 3, SettlementLineType.OTHER_OUT, LineDirection.OUTBOUND, 25_000L);
            assertThat(held.lines().get(3).type()).isEqualTo(SettlementLineType.PAYOUT_FEE);
        }

        @Test
        @DisplayName("no defect ever carries a value: fields are NAMES")
        void defectsCarryNoValue() {
            for (SettlementFormat.Result.Rejected verdict : List.of(
                    rejected(goldenWith("-250.00,1.20", "-250.001,1.20")),
                    rejected(goldenWith("xp_d4e5f6", "xp d4e5f6")),
                    rejected(goldenWith("T,3,-832.00,", "T,3,-831.99,")))) {
                assertThat(verdict.toString()).doesNotContain("250.001", "d4e5f6", "831.99");
                for (FormatDefect defect : verdict.defects()) {
                    defect.field().ifPresent(name -> assertThat(name).matches("[A-Za-z]+"));
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
            assertThat(screening.lineCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("instrument data where a reference, an amount or the format name belongs fails the"
                + " class and is refused with its line and field")
        void instrumentDataIsRefused() {
            assertRefused(screened(goldenWith("xp_g7h8i9", IBAN)), RefusalReason.ACCOUNT_IDENTIFIER, 4, "providerRef");
            assertRefused(screened(goldenWith("XB-0003-E2E-c", IBAN)), RefusalReason.ACCOUNT_IDENTIFIER, 4, "endToEndRef");
            assertRefused(screened(goldenWith("XB-0001-E2E-a", "E-" + PAN)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "endToEndRef");
            assertRefused(screened(goldenWith("XB-2026-10-05-USD", PAN)), RefusalReason.PRIMARY_ACCOUNT_NUMBER, 1, "batchRef");
            assertRefused(screened(goldenWith("-1079.60,1.20", "-" + PAN + ",1.20")),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "amount");
            assertRefused(screened(goldenWith("H,SIM_CORRIDOR_CSV,1,", "H," + PAN + ",1,")),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 1, "formatId");
        }

        @Test
        @DisplayName("a CLEAN malformed field passes the screen, so the corrupt delivery is stored as"
                + " evidence and rejected at parse; shapeless bytes are one conservative stream")
        void cleanMalformedAndShapeless() {
            for (String fault : List.of(
                    goldenWith("-250.00,1.20", "-250.0O,1.20"),
                    goldenWith("xp_d4e5f6", "xp d4e5f6"),
                    goldenWith("XB-0002-E2E-b", "XB/0002"))) {
                assertThat(screened(fault).finding()).isEmpty();
                assertThat(rejected(fault).code()).isEqualTo(RejectionCode.MALFORMED);
            }
            assertThat(screened("just text with 4111 1111 1111 1111 inside\n").finding())
                    .hasValueSatisfying(finding -> {
                        assertThat(finding.reason()).isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                        assertThat(finding.fieldName()).isEmpty();
                    });
        }
    }

    // ------------------------------------------------------------------------ the identity

    @Test
    @DisplayName("the format names itself - SIM_CORRIDOR_CSV, version 1 - and its remittance pattern admits"
            + " the corridor provider's references alone")
    void identity() {
        assertThat(FORMAT.id()).isEqualTo(SettlementFormatId.SIM_CORRIDOR_CSV);
        assertThat(FORMAT.version()).isEqualTo(1);
        assertThat("XBA-20261005").matches(SimCorridorCsvFormat.REMITTANCE_REFERENCE);
        assertThat("PAY-REM-20261005").doesNotMatch(SimCorridorCsvFormat.REMITTANCE_REFERENCE);
        assertThat("FXA-20261005").doesNotMatch(SimCorridorCsvFormat.REMITTANCE_REFERENCE);
        assertThat("XBA-" + PAN).doesNotMatch(SimCorridorCsvFormat.REMITTANCE_REFERENCE);
    }

    private static void assertLine(
            ParsedLine line, int lineNo, SettlementLineType type, LineDirection direction, long minor) {
        assertThat(line.lineNo()).isEqualTo(lineNo);
        assertThat(line.type()).isEqualTo(type);
        assertThat(line.direction()).isEqualTo(direction);
        assertThat(line.amount().minorUnits()).isEqualTo(minor);
        assertThat(line.amount().scale()).isEqualTo(2);
        assertThat(line.amount().currency()).isEqualTo(USD);
        assertThat(line.businessDate()).isEqualTo(REPORT_DAY);
        assertThat(line.settlementDate()).contains(REPORT_DAY);
        assertThat(line.valueDate()).isEmpty();
    }
}
