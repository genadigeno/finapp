package com.finapp.settlement.format.simfx;

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
 * `SIM_FX_CSV` v1, frozen (`P9-TSK-011`, ADR-0066 §8 applied to the FX provider's report): the golden
 * day's every canonical line - one currency, one value date, each leg keyed by the platform's cover
 * reference, the fee split naming its leg - the fingerprints pinned by hex literal, each fault
 * rejecting the WHOLE file with its named errors, and the field-class screen refusing instrument data
 * wherever it hides while admitting the platform's own cover reference exactly. A behaviour change
 * here is a NEW format version, never an edit of this one.
 */
@DisplayName("the simulated FX provider's trade report format, version 1 (P9-TSK-011)")
class SimFxCsvFormatTest {

    private static final SimFxCsvFormat FORMAT = SimFxCsvFormat.INSTANCE;

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static final LocalDate VALUE_DAY = LocalDate.of(2026, 9, 28);

    private static final String IBAN = "GB82WEST12345698765432";

    private static final String PAN = "4111111111111111";

    private static final String COVER_FIRST = "T-0123456789abcdef0123456789abcdef";

    private static final String COVER_SECOND = "T-fedcba9876543210fedcba9876543210";

    private static final String COVER_THIRD = "T-00000000000000000000000000000abc";

    /** A minted cover reference whose hex holds a Luhn-valid run of card length. */
    private static final String COVER_CARD_LENGTH = "T-4111111111111111abcdefabcdefabcd";

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        SimFxCsvFormatTest.class.getResourceAsStream("/format/simfx/golden-v1.csv"),
                        "the golden file is a test resource")) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String goldenText() {
        return new String(golden(), StandardCharsets.UTF_8);
    }

    private static ParsedBatch parsed(String content) {
        SettlementFormat.Result result = FORMAT.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        return ((SettlementFormat.Result.Parsed) result).batch();
    }

    private static ParsedBatch parsedGolden() {
        return parsed(goldenText());
    }

    private static SettlementFormat.Result.Rejected rejected(String content) {
        SettlementFormat.Result result = FORMAT.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result).as("the whole file is rejected (INV-SET-07)")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    private static DeliveryScreen.Screening screened(String content) {
        return FORMAT.screen(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String goldenWith(String from, String to) {
        String text = goldenText();
        int at = text.indexOf(from);
        assertThat(at).as("the text to replace is present: %s", from).isNotNegative();
        assertThat(text.indexOf(from, at + 1)).as("unique - one fault at a time: %s", from).isNegative();
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

    @Nested
    @DisplayName("the golden trade report")
    class TheGoldenReport {

        @Test
        @DisplayName("parses whole: one currency, one value date, four canonical lines - three legs and"
                + " one fee split from its leg - each leg keyed by its cover reference")
        void goldenParsesWhole() {
            ParsedBatch batch = parsedGolden();
            assertThat(batch.externalBatchRef()).isEqualTo("FXB-2026-09-28-EUR");
            assertThat(batch.businessDate()).isEqualTo(VALUE_DAY);
            assertThat(batch.remittanceReference()).contains("FXA-20260928");
            assertThat(batch.statement()).isEmpty();
            assertThat(batch.declaredLineCount()).as("records before the fee split").isEqualTo(3);
            assertThat(batch.declaredNet().minorUnits()).as("-1000.00 + 925.00 - 0.40 - 250.00").isEqualTo(-32_540L);

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(4);
            assertLine(lines.get(0), 1, SettlementLineType.FX_SOLD, LineDirection.OUTBOUND, 100_000L);
            assertLine(lines.get(1), 2, SettlementLineType.FX_BOUGHT, LineDirection.INBOUND, 92_500L);
            assertLine(lines.get(2), 3, SettlementLineType.FX_FEE, LineDirection.OUTBOUND, 40L);
            assertLine(lines.get(3), 4, SettlementLineType.FX_SOLD, LineDirection.OUTBOUND, 25_000L);
            assertThat(lines.get(0).references()).containsExactly(
                    Map.entry(LineReferenceKind.COVER_REF, COVER_FIRST),
                    Map.entry(LineReferenceKind.FX_TRADE_REF, "fxt_0001"));
            assertThat(lines.get(1).references()).containsExactly(
                    Map.entry(LineReferenceKind.COVER_REF, COVER_SECOND),
                    Map.entry(LineReferenceKind.FX_TRADE_REF, "fxt_0002"));
            assertThat(lines.get(2).references())
                    .as("the fee names the leg it rode in on: ORIGINAL_REF = Tn (PHASE_9_PLAN.md 12.9.1)")
                    .containsExactly(Map.entry(LineReferenceKind.ORIGINAL_REF, COVER_SECOND));
            assertThat(lines.get(2).rawRecordSha256()).isEqualTo(lines.get(1).rawRecordSha256());
            assertThat(lines.get(3).references()).containsEntry(LineReferenceKind.COVER_REF, COVER_THIRD);
        }

        @Test
        @DisplayName("the fingerprint is FROZEN by hex literal and deterministic; the digest is the record's own text")
        void fingerprintsAreFrozen() {
            List<ParsedLine> lines = parsedGolden().lines();
            List<ParsedLine> again = parsedGolden().lines();
            assertThat(HexFormat.of().formatHex(lines.get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo("6e983d3be90cd1e58c2e1437171c6f3f87a4e841dcc49f23fae23181d1ef13be");
            assertThat(HexFormat.of().formatHex(lines.get(2).canonicalFingerprint())).isEqualTo("1dc8790b3bf3afde2661b75c0e2ef185aa7dcb158fe9d596552832880da340a9");
            assertThat(lines.get(1).rawRecordSha256()).isEqualTo(ParsedLine.sha256(
                    ("D,2,BOUGHT,925.00,0.40,fxt_0002," + COVER_SECOND).getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < lines.size(); i++) {
                assertThat(lines.get(i).canonicalFingerprint()).isEqualTo(again.get(i).canonicalFingerprint());
            }
        }

        @Test
        @DisplayName("the Money fold per (type, direction) is the report's own arithmetic")
        void totalsFold() {
            assertThat(FileParsing.totalsOf(parsedGolden())).containsExactlyInAnyOrder(
                    new SettlementBatchStore.TotalRow(SettlementLineType.FX_SOLD, LineDirection.OUTBOUND, 2, 125_000L, 2),
                    new SettlementBatchStore.TotalRow(SettlementLineType.FX_BOUGHT, LineDirection.INBOUND, 1, 92_500L, 2),
                    new SettlementBatchStore.TotalRow(SettlementLineType.FX_FEE, LineDirection.OUTBOUND, 1, 40L, 2));
        }

        @Test
        @DisplayName("a JPY day parses at scale 0 and a BHD day at scale 3 - one currency per file")
        void otherCurrencies() {
            ParsedBatch jpy = parsed("H,SIM_FX_CSV,1,FXB-JPY,JPY,2026-09-28\n"
                    + "D,1,BOUGHT,148250,,fxt_9,T-11111111111111111111111111111111\nT,1,148250,FXA-20260928\n");
            assertThat(jpy.lines().get(0).amount().scale()).isZero();
            assertThat(jpy.lines().get(0).amount().minorUnits()).isEqualTo(148_250L);
            ParsedBatch bhd = parsed("H,SIM_FX_CSV,1,FXB-BHD,BHD,2026-09-28\n"
                    + "D,1,SOLD,-12.345,,fxt_8,T-22222222222222222222222222222222\nT,1,-12.345,FXA-20260928\n");
            assertThat(bhd.lines().get(0).amount().scale()).isEqualTo(3);
        }

        @Test
        @DisplayName("a day with no trades parses: a header, a trailer of count 0 and net 0")
        void emptyDayParses() {
            ParsedBatch batch = parsed("H,SIM_FX_CSV,1,FXB-EMPTY,EUR,2026-09-29\nT,0,0.00,FXA-20260929\n");
            assertThat(batch.lines()).isEmpty();
            assertThat(batch.declaredNet().isZero()).isTrue();
        }

        @Test
        @DisplayName("a BOM and CRLF endings move no fingerprint")
        void bomAndCrlfTolerated() {
            List<ParsedLine> lf = parsedGolden().lines();
            List<ParsedLine> crlf = parsed((char) 0xFEFF + goldenText().replace("\n", "\r\n")).lines();
            for (int i = 0; i < lf.size(); i++) {
                assertThat(crlf.get(i).canonicalFingerprint()).isEqualTo(lf.get(i).canonicalFingerprint());
            }
        }
    }

    @Nested
    @DisplayName("each fault rejects the whole file")
    class EachFaultRejectsWhole {

        @Test
        @DisplayName("another format or version is UNSUPPORTED_FORMAT; an unknown currency UNKNOWN_CURRENCY")
        void headerVerdicts() {
            assertSingleDefect(rejected(goldenWith("H,SIM_FX_CSV,1,", "H,SIM_FX_CSV,2,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatVersion");
            assertSingleDefect(rejected(goldenWith("H,SIM_FX_CSV,1,", "H,SIM_PAYOUT_CSV,1,")),
                    RejectionCode.UNSUPPORTED_FORMAT, 1, "formatId");
            assertSingleDefect(rejected(goldenWith(",EUR,2026-09-28\n", ",XXX,2026-09-28\n")),
                    RejectionCode.UNKNOWN_CURRENCY, 1, "currency");
        }

        @Test
        @DisplayName("more decimals than the currency's scale is SCALE_MISMATCH; a control total off by a cent"
                + " is CONTROL_TOTAL_MISMATCH")
        void scaleAndTotals() {
            assertSingleDefect(rejected(goldenWith("-1000.00,", "-1000.001,")), RejectionCode.SCALE_MISMATCH, 2, "amount");
            assertSingleDefect(rejected(goldenWith("0.40,", "0.405,")), RejectionCode.SCALE_MISMATCH, 3, "fee");
            assertSingleDefect(rejected(goldenWith("T,3,", "T,2,")), RejectionCode.CONTROL_TOTAL_MISMATCH, 5, "count");
            assertSingleDefect(rejected(goldenWith("-325.40,", "-325.41,")), RejectionCode.CONTROL_TOTAL_MISMATCH, 5, "net");
        }

        @Test
        @DisplayName("a known code with the wrong sign is MALFORMED at its amount; an unknown code is KEPT")
        void signsAndUnknownCodes() {
            assertSingleDefect(rejected(goldenWith("D,1,SOLD,-1000.00", "D,1,SOLD,1000.00")),
                    RejectionCode.MALFORMED, 2, "amount");
            assertSingleDefect(rejected(goldenWith("D,2,BOUGHT,925.00", "D,2,BOUGHT,-925.00")),
                    RejectionCode.MALFORMED, 3, "amount");
            ParsedBatch kept = parsed(goldenWith("D,3,SOLD,-250.00", "D,3,SWAP,-250.00"));
            assertThat(kept.lines().get(3).type()).isEqualTo(SettlementLineType.OTHER_OUT);
            assertThat(kept.lines().get(3).references()).containsEntry(LineReferenceKind.COVER_REF, COVER_THIRD);
        }

        @Test
        @DisplayName("each field malformed in turn is MALFORMED at its line and field")
        void eachFieldMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults = List.of(
                    new Fault("FXB-2026-09-28-EUR", "FXB 2026-09-28-EUR", 1, "batchRef"),
                    new Fault(",EUR,2026-09-28\n", ",eur,2026-09-28\n", 1, "currency"),
                    new Fault(",EUR,2026-09-28\n", ",EUR,2026-13-28\n", 1, "valueDate"),
                    new Fault("D,2,BOUGHT", "D,x,BOUGHT", 3, "seq"),
                    new Fault("D,2,BOUGHT", "D,2,bought", 3, "code"),
                    new Fault("925.00,", "925.0O,", 3, "amount"),
                    new Fault("0.40,", "-0.40,", 3, "fee"),
                    new Fault("fxt_0002", "", 3, "fxTradeRef"),
                    new Fault("fxt_0002", "fxt 0002", 3, "fxTradeRef"),
                    new Fault(COVER_SECOND, "", 3, "coverRef"),
                    new Fault(COVER_SECOND, COVER_SECOND.toUpperCase().replace("T-", "T-"), 3, "coverRef"),
                    new Fault(COVER_SECOND, "T-fedcba98", 3, "coverRef"),
                    new Fault("fxt_0002", "fxt,0002", 3, "recordType"),
                    new Fault("T,3,", "T,three,", 5, "count"),
                    new Fault("-325.40,", "-325.4O,", 5, "net"),
                    new Fault("FXA-20260928", "PAY-REM-20260928", 5, "remittanceRef"));
            for (Fault fault : faults) {
                SettlementFormat.Result.Rejected verdict = rejected(goldenWith(fault.from(), fault.to()));
                assertThat(verdict.code()).as("fault %s -> %s", fault.to(), fault.field()).isEqualTo(RejectionCode.MALFORMED);
                assertThat(verdict.defects()).as("fault %s names its line and field", fault.to())
                        .anySatisfy(defect -> {
                            assertThat(defect.lineNo()).contains(fault.line());
                            assertThat(defect.field()).contains(fault.field());
                        });
            }
        }

        @Test
        @DisplayName("no defect carries a value")
        void defectsCarryNoValue() {
            for (SettlementFormat.Result.Rejected verdict : List.of(
                    rejected(goldenWith("-1000.00,", "-1000.001,")),
                    rejected(goldenWith("fxt_0002", "fxt 0002")))) {
                assertThat(verdict.toString()).doesNotContain("1000.001", "fxt 0002");
                for (FormatDefect defect : verdict.defects()) {
                    defect.field().ifPresent(name -> assertThat(name).matches("[A-Za-z]+"));
                }
            }
        }
    }

    @Nested
    @DisplayName("the field-class screen (ADR-0066 §3, C6)")
    class TheFieldClassScreen {

        @Test
        @DisplayName("the golden report passes, its record count the reader's own")
        void goldenPasses() {
            DeliveryScreen.Screening screening = FORMAT.screen(golden());
            assertThat(screening.finding()).isEmpty();
            assertThat(screening.lineCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("an account identifier as the trade reference, a card number as the batch reference or an"
                + " amount, fails the class and is refused")
        void instrumentsWhereAClassBelongsRefused() {
            assertRefused(screened(goldenWith("fxt_0002", IBAN)), RefusalReason.ACCOUNT_IDENTIFIER, 3, "fxTradeRef");
            assertRefused(screened(goldenWith("FXB-2026-09-28-EUR", PAN)), RefusalReason.PRIMARY_ACCOUNT_NUMBER, 1, "batchRef");
            assertRefused(screened(goldenWith("-1000.00,", "-" + PAN + ",")), RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, "amount");
        }

        @Test
        @DisplayName("the platform's OWN cover reference is admitted exactly even when its hex holds a card-length"
                + " run; the same run as the trade reference is refused")
        void thePlatformsOwnCoverReferenceIsAdmitted() {
            String ours = goldenWith(COVER_SECOND, COVER_CARD_LENGTH);
            assertThat(screened(ours).finding()).isEmpty();
            assertThat(parsed(ours).lines().get(1).references()).containsEntry(LineReferenceKind.COVER_REF, COVER_CARD_LENGTH);
            assertRefused(screened(goldenWith("fxt_0002", COVER_CARD_LENGTH)),
                    RefusalReason.PRIMARY_ACCOUNT_NUMBER, 3, "fxTradeRef");
        }

        @Test
        @DisplayName("a clean malformed field passes the screen, so the delivery is stored and rejected at parse")
        void cleanMalformedPasses() {
            for (String fault : List.of(goldenWith("925.00,", "925.0O,"), goldenWith("fxt_0002", "fxt 0002"),
                    goldenWith(COVER_SECOND, "T-fedcba98"))) {
                assertThat(screened(fault).finding()).isEmpty();
                assertThat(rejected(fault).code()).isEqualTo(RejectionCode.MALFORMED);
            }
        }
    }

    @Test
    @DisplayName("the format names itself - SIM_FX_CSV, version 1 - and its remittance pattern admits FXA- alone")
    void identity() {
        assertThat(FORMAT.id()).isEqualTo(SettlementFormatId.SIM_FX_CSV);
        assertThat(FORMAT.version()).isEqualTo(1);
        assertThat("FXA-20260928").matches(SimFxCsvFormat.REMITTANCE_REFERENCE);
        assertThat("PAY-REM-20260928").doesNotMatch(SimFxCsvFormat.REMITTANCE_REFERENCE);
        assertThat("FXA-" + PAN).doesNotMatch(SimFxCsvFormat.REMITTANCE_REFERENCE);
    }

    private static void assertLine(ParsedLine line, int lineNo, SettlementLineType type, LineDirection direction, long minor) {
        assertThat(line.lineNo()).isEqualTo(lineNo);
        assertThat(line.type()).isEqualTo(type);
        assertThat(line.direction()).isEqualTo(direction);
        assertThat(line.amount().minorUnits()).isEqualTo(minor);
        assertThat(line.amount().currency()).isEqualTo(EUR);
        assertThat(line.businessDate()).isEqualTo(VALUE_DAY);
        assertThat(line.settlementDate()).as("settled on the header's value date").contains(VALUE_DAY);
        assertThat(line.valueDate()).as("and valued on it - the timing detector reads it").contains(VALUE_DAY);
    }
}
