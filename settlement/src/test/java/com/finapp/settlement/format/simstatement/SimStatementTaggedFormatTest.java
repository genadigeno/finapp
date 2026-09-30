package com.finapp.settlement.format.simstatement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.LineDirection;
import com.finapp.settlement.LineReferenceKind;
import com.finapp.settlement.RefusalReason;
import com.finapp.settlement.RejectionCode;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * `SIM_STATEMENT_TAGGED` v1, frozen (`P8-TSK-016`, ADR-0066 §8 — the
 * {@code RailMoneySemanticsArePinnedTest} rule applied to a format): the golden statement's every
 * canonical line and continuity fact, the fingerprint pinned by hex literal, each fault rejecting
 * the WHOLE file with its named errors, the account reference checked against configuration and
 * never carried, and the field-class screen refusing instrument data wherever it hides. A
 * behaviour change here is a NEW format version, never an edit of this one.
 */
@DisplayName("the simulated bank's tagged statement format, version 1 (P8-TSK-016)")
class SimStatementTaggedFormatTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    /** The golden statement's own account — configured, and never expected in any output. */
    private static final String EUR_ACCOUNT = "SIMBANK-EUR-01";

    private static final String GBP_ACCOUNT = "SIMBANK-GBP-02";

    /** An international account identifier shape, not Luhn-valid as a digit run. */
    private static final String IBAN = "GB82WEST12345698765432";

    /** A Luhn-valid card number. */
    private static final String PAN = "4111111111111111";

    private static final LocalDate STATEMENT_DAY = LocalDate.of(2026, 9, 25);

    private static final SimStatementTaggedFormat FORMAT =
            new SimStatementTaggedFormat(Map.of(EUR, EUR_ACCOUNT, GBP, GBP_ACCOUNT));

    private static byte[] golden() {
        try (var stream =
                Objects.requireNonNull(
                        SimStatementTaggedFormatTest.class.getResourceAsStream(
                                "/format/simstatement/golden-v1.txt"),
                        "the golden file is a test resource")) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String goldenText() {
        return new String(golden(), StandardCharsets.UTF_8);
    }

    private static ParsedBatch parsed(SimStatementTaggedFormat format, byte[] content) {
        SettlementFormat.Result result = format.parse(content);
        assertThat(result).isInstanceOf(SettlementFormat.Result.Parsed.class);
        return ((SettlementFormat.Result.Parsed) result).batch();
    }

    private static ParsedBatch parsed(String content) {
        return parsed(FORMAT, content.getBytes(StandardCharsets.UTF_8));
    }

    private static ParsedBatch parsedGolden() {
        return parsed(FORMAT, golden());
    }

    private static SettlementFormat.Result.Rejected rejected(
            SimStatementTaggedFormat format, String content) {
        SettlementFormat.Result result = format.parse(content.getBytes(StandardCharsets.UTF_8));
        assertThat(result)
                .as("the whole file is rejected (INV-SET-07)")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        return (SettlementFormat.Result.Rejected) result;
    }

    private static SettlementFormat.Result.Rejected rejected(String content) {
        return rejected(FORMAT, content);
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

    // ------------------------------------------------------------------------- the freeze

    @Nested
    @DisplayName("the golden statement")
    class TheGoldenStatement {

        @Test
        @DisplayName("parses whole: the statement's identity, its continuity facts and five"
                + " canonical lines - credits, a debit, an unreferenced credit and the bank's fee")
        void goldenParsesWhole() {
            ParsedBatch batch = parsedGolden();
            assertThat(batch.externalBatchRef()).isEqualTo("SB-STMT-20260925-EUR");
            assertThat(batch.businessDate()).isEqualTo(STATEMENT_DAY);
            assertThat(batch.remittanceReference())
                    .as("a statement carries no remittance reference of its own")
                    .isEmpty();
            assertThat(batch.declaredLineCount()).isEqualTo(5);
            assertThat(batch.declaredNet().currency()).isEqualTo(EUR);
            assertThat(batch.declaredNet().scale()).isEqualTo(2);
            assertThat(batch.declaredNet().minorUnits()).isEqualTo(13_025L);
            assertThat(batch.statement())
                    .hasValueSatisfying(
                            facts -> {
                                assertThat(facts.sequence()).isEqualTo(1L);
                                assertThat(facts.opening().currency()).isEqualTo(EUR);
                                assertThat(facts.opening().minorUnits()).isZero();
                                assertThat(facts.closing().currency()).isEqualTo(EUR);
                                assertThat(facts.closing().minorUnits()).isEqualTo(13_025L);
                            });

            List<ParsedLine> lines = batch.lines();
            assertThat(lines).hasSize(5);
            assertLine(lines.get(0), 1, SettlementLineType.BANK_CREDIT, LineDirection.INBOUND,
                    9_825L);
            assertLine(lines.get(1), 2, SettlementLineType.BANK_CREDIT, LineDirection.INBOUND,
                    4_000L);
            assertLine(lines.get(2), 3, SettlementLineType.BANK_DEBIT, LineDirection.OUTBOUND,
                    1_250L);
            assertLine(lines.get(3), 4, SettlementLineType.BANK_CREDIT, LineDirection.INBOUND,
                    500L);
            assertLine(lines.get(4), 5, SettlementLineType.BANK_FEE, LineDirection.OUTBOUND,
                    50L);

            assertThat(lines.get(0).references())
                    .containsExactly(
                            Map.entry(LineReferenceKind.REMITTANCE_REF, "PSP-REM-20260925"));
            assertThat(lines.get(1).references())
                    .containsExactly(Map.entry(LineReferenceKind.REMITTANCE_REF, "SCH-REM-4411"));
            assertThat(lines.get(2).references())
                    .containsExactly(Map.entry(LineReferenceKind.REMITTANCE_REF, "PAY-REM-7788"));
            assertThat(lines.get(3).references())
                    .as("a credit may omit its reference - it then attributes to nobody")
                    .isEmpty();
            assertThat(lines.get(4).references())
                    .as("the bank's own charge answers to no remittance")
                    .isEmpty();
        }

        @Test
        @DisplayName("the fingerprint algorithm is FROZEN by hex literal, deterministic across"
                + " parses, and the raw-record digest is the delivered :61: record's")
        void fingerprintsAreFrozen() {
            ParsedBatch first = parsedGolden();
            ParsedBatch second = parsedGolden();
            assertThat(HexFormat.of().formatHex(first.lines().get(0).canonicalFingerprint()))
                    .as("a change to this literal is a NEW format version")
                    .isEqualTo(
                            "3c1e8a0267329a9baaadd9e9fdaafc7eb1e8498f18b034b53a22608c0c11a314");
            assertThat(HexFormat.of().formatHex(first.lines().get(0).rawRecordSha256()))
                    .isEqualTo(
                            "b3b3f4353179c1c25c89b4258df2a0524dd80954c95f32485c0f0b2bbfe00e33");
            assertThat(HexFormat.of().formatHex(first.lines().get(4).canonicalFingerprint()))
                    .isEqualTo(
                            "ffbe15100b4e7a14e49823a902ab11b8de9a3510ca1cb21904168439e5660c22");
            assertThat(HexFormat.of().formatHex(first.lines().get(4).rawRecordSha256()))
                    .isEqualTo(
                            "5541412dc930a0fa6a0b9d60947015d3e5d7bd087b5331268cb4428680769b8b");
            assertThat(first.lines().get(1).rawRecordSha256())
                    .as("the digest covers the :61: record alone - never its narrative")
                    .isEqualTo(
                            ParsedLine.sha256(
                                    ":61:2026-09-25,C,40.00,SCH-REM-4411"
                                            .getBytes(StandardCharsets.UTF_8)));
            for (int i = 0; i < first.lines().size(); i++) {
                assertThat(first.lines().get(i).canonicalFingerprint())
                        .as("line %s re-parses to the same identity", i + 1)
                        .isEqualTo(second.lines().get(i).canonicalFingerprint());
            }
        }

        @Test
        @DisplayName("a BOM, CRLF endings and a missing trailing newline are tolerated, and the"
                + " digests do not move")
        void bomAndCrlfTolerated() {
            ParsedBatch lf = parsedGolden();
            String crlf = "﻿" + goldenText().replace("\n", "\r\n");
            ParsedBatch withBom = parsed(crlf);
            assertThat(withBom.lines()).hasSize(5);
            assertThat(withBom.statement()).isEqualTo(lf.statement());
            for (int i = 0; i < lf.lines().size(); i++) {
                assertThat(withBom.lines().get(i).rawRecordSha256())
                        .as("line %s's record digest is the record's, not its line ending", i + 1)
                        .isEqualTo(lf.lines().get(i).rawRecordSha256());
            }
            String text = goldenText();
            ParsedBatch unterminated = parsed(text.substring(0, text.length() - 1));
            assertThat(unterminated.lines()).hasSize(5);
        }

        @Test
        @DisplayName("a debit balance is signed negative, and the net is closing minus opening")
        void debitBalancesAreSigned() {
            String text =
                    with(
                            goldenWith(":60F:C,2026-09-24,EUR,0.00", ":60F:D,2026-09-24,EUR,10.00"),
                            ":62F:C,2026-09-25,EUR,130.25",
                            ":62F:C,2026-09-25,EUR,120.25");
            ParsedBatch batch = parsed(text);
            assertThat(batch.statement())
                    .hasValueSatisfying(
                            facts -> {
                                assertThat(facts.opening().minorUnits()).isEqualTo(-1_000L);
                                assertThat(facts.closing().minorUnits()).isEqualTo(12_025L);
                            });
            assertThat(batch.declaredNet().minorUnits()).isEqualTo(13_025L);
        }

        @Test
        @DisplayName("a statement with no lines still continues the chain: opening equals"
                + " closing, net zero")
        void emptyStatementParses() {
            String text =
                    ":20:SB-STMT-20260926-EUR\n"
                            + ":25:SIMBANK-EUR-01\n"
                            + ":28C:2\n"
                            + ":60F:C,2026-09-25,EUR,130.25\n"
                            + ":62F:C,2026-09-26,EUR,130.25\n";
            ParsedBatch batch = parsed(text);
            assertThat(batch.lines()).isEmpty();
            assertThat(batch.declaredLineCount()).isZero();
            assertThat(batch.declaredNet().isZero()).isTrue();
            assertThat(batch.statement())
                    .hasValueSatisfying(facts -> assertThat(facts.sequence()).isEqualTo(2L));
        }

        @Test
        @DisplayName("neither the account reference nor any narrative reaches the canonical"
                + " output")
        void nothingButReferencesLeaves() {
            ParsedBatch batch = parsedGolden();
            String[] leaks = {EUR_ACCOUNT, "SIMBANK", "Jane", "Remittance for", "Scheme cycle"};
            assertThat(batch.toString()).doesNotContain(leaks);
            assertThat(batch.externalBatchRef()).doesNotContain(leaks);
            for (ParsedLine line : batch.lines()) {
                assertThat(line.toString()).doesNotContain(leaks);
                for (String value : line.references().values()) {
                    assertThat(value).doesNotContain(leaks);
                }
            }
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
        @DisplayName("each field malformed in turn is MALFORMED at its line and field")
        void eachFieldMalformed() {
            record Fault(String from, String to, int line, String field) {}
            List<Fault> faults =
                    List.of(
                            new Fault(":20:SB-STMT-20260925-EUR", ":20:SB STMT", 1,
                                    "statementRef"),
                            // The account reference: its shape, then THIS account's value.
                            new Fault(":25:SIMBANK-EUR-01", ":25:SIMBANK-EUR-X1", 2,
                                    "accountRef"),
                            new Fault(":25:SIMBANK-EUR-01", ":25:SIMBANK-EUR-02", 2,
                                    "accountRef"),
                            // Another configured account's reference on a EUR statement.
                            new Fault(":25:SIMBANK-EUR-01", ":25:SIMBANK-GBP-02", 2,
                                    "accountRef"),
                            new Fault(":28C:1\n", ":28C:0\n", 3, "sequence"),
                            new Fault(":28C:1\n", ":28C:1a\n", 3, "sequence"),
                            new Fault(":60F:C,", ":60F:X,", 4, "openingMark"),
                            new Fault("C,2026-09-24,EUR", "C,2026-02-30,EUR", 4, "openingDate"),
                            new Fault("EUR,0.00", "EUR,O.00", 4, "openingAmount"),
                            new Fault(":60F:C,2026-09-24,EUR,0.00", ":60F:C,2026-09-24,EUR", 4,
                                    "openingBalance"),
                            new Fault(":61:2026-09-25,C,98.25", ":61:2026-13-25,C,98.25", 5,
                                    "valueDate"),
                            new Fault(":61:2026-09-25,C,40.00,SCH-REM-4411",
                                    ":61:2026-09-25,C,40.00,4411", 7, "remittanceRef"),
                            new Fault(":86:Scheme cycle settlement", ":86:" + "x".repeat(391), 8,
                                    "narrative"),
                            new Fault(":61:2026-09-25,C,5.00", ":61:2026-09-25,X,5.00", 11,
                                    "mark"),
                            // A line's amount is positive; zero is no line.
                            new Fault(":61:2026-09-25,C,5.00", ":61:2026-09-25,C,0.00", 11,
                                    "amount"),
                            // The sign is the mark's: a signed amount is not the format's.
                            new Fault(":61:2026-09-25,C,5.00", ":61:2026-09-25,C,-5.00", 11,
                                    "amount"),
                            // A reference is present by its field, never by an empty one.
                            new Fault(":61:2026-09-25,C,5.00", ":61:2026-09-25,C,5.00,", 11,
                                    "remittanceRef"),
                            new Fault(":61:2026-09-25,C,5.00", ":61:2026-09-25,C", 11,
                                    "statementLine"),
                            // The bank's own charge answers to no remittance.
                            new Fault(":61:2026-09-25,F,0.50", ":61:2026-09-25,F,0.50,FEE-REF-1",
                                    12, "remittanceRef"),
                            new Fault(":62F:C,", ":62F:Z,", 13, "closingMark"),
                            new Fault("C,2026-09-25,EUR,130.25", "C,20260925,EUR,130.25", 13,
                                    "closingDate"),
                            // One statement, one currency.
                            new Fault("C,2026-09-25,EUR,130.25", "C,2026-09-25,GBP,130.25", 13,
                                    "closingCurrency"));
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
        @DisplayName("a statement in a currency with no configured account is not ours:"
                + " MALFORMED at the account reference")
        void noConfiguredAccountForTheCurrency() {
            SimStatementTaggedFormat gbpOnly =
                    new SimStatementTaggedFormat(Map.of(GBP, GBP_ACCOUNT));
            SettlementFormat.Result.Rejected verdict = rejected(gbpOnly, goldenText());
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(2);
                                assertThat(defect.field()).contains("accountRef");
                            });
        }

        @Test
        @DisplayName("a currency the platform cannot represent is UNKNOWN_CURRENCY")
        void unknownCurrency() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith(",EUR,0.00", ",XXX,0.00"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.UNKNOWN_CURRENCY);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(4);
                                assertThat(defect.field()).contains("openingCurrency");
                            });
        }

        @Test
        @DisplayName("an amount with more decimals than the currency's scale is SCALE_MISMATCH,"
                + " named to its line and field")
        void scaleMismatch() {
            SettlementFormat.Result.Rejected line =
                    rejected(goldenWith(":61:2026-09-25,C,98.25,", ":61:2026-09-25,C,98.255,"));
            assertThat(line.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
            assertThat(line.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
                                assertThat(defect.lineNo()).contains(5);
                                assertThat(defect.field()).contains("amount");
                            });
            SettlementFormat.Result.Rejected closing =
                    rejected(goldenWith("EUR,130.25", "EUR,130.250"));
            assertThat(closing.code()).isEqualTo(RejectionCode.SCALE_MISMATCH);
            assertThat(closing.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(13);
                                assertThat(defect.field()).contains("closingAmount");
                            });
        }

        @Test
        @DisplayName("a statement that does not add up - off by one cent - is"
                + " CONTROL_TOTAL_MISMATCH at the closing balance")
        void controlTotal() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith("EUR,130.25", "EUR,130.26"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.CONTROL_TOTAL_MISMATCH);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(13);
                                assertThat(defect.field()).contains("closing");
                            });
        }

        @Test
        @DisplayName("a record out of order is MALFORMED, named 'tag' at its line")
        void outOfOrderRecord() {
            SettlementFormat.Result.Rejected swapped =
                    rejected(goldenWith(":25:SIMBANK-EUR-01\n:28C:1\n",
                            ":28C:1\n:25:SIMBANK-EUR-01\n"));
            assertThat(swapped.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(swapped.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(3);
                                assertThat(defect.field()).contains("tag");
                            });
            SettlementFormat.Result.Rejected afterClosing =
                    rejected(goldenText() + ":61:2026-09-25,C,1.00\n");
            assertThat(afterClosing.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(afterClosing.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(14);
                                assertThat(defect.field()).contains("tag");
                            });
        }

        @Test
        @DisplayName("an unknown tag is MALFORMED at its line, and nothing else is blamed")
        void unknownTag() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith(":25:SIMBANK-EUR-01\n",
                            ":21:RELATED-1\n:25:SIMBANK-EUR-01\n"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(2);
                                assertThat(defect.field()).contains("tag");
                            });
        }

        @Test
        @DisplayName("a second narrative in a row, or one before any line, is MALFORMED")
        void narrativeOutOfPlace() {
            SettlementFormat.Result.Rejected twice =
                    rejected(goldenWith(":86:Remittance for 2026-09-25\n",
                            ":86:Remittance for 2026-09-25\n:86:Second narrative\n"));
            assertThat(twice.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(twice.defects())
                    .singleElement()
                    .satisfies(
                            defect -> {
                                assertThat(defect.lineNo()).contains(7);
                                assertThat(defect.field()).contains("tag");
                            });
            SettlementFormat.Result.Rejected early =
                    rejected(goldenWith(":60F:C,2026-09-24,EUR,0.00\n",
                            ":60F:C,2026-09-24,EUR,0.00\n:86:Opening remark\n"));
            assertThat(early.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(5);
                                assertThat(defect.field()).contains("tag");
                            });
        }

        @Test
        @DisplayName("a missing mandatory record is MALFORMED, named by the record")
        void missingMandatoryRecords() {
            SettlementFormat.Result.Rejected noSequence =
                    rejected(goldenWith(":28C:1\n", ""));
            assertThat(noSequence.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(noSequence.defects())
                    .anySatisfy(defect -> assertThat(defect.field()).contains("sequence"));

            String text = goldenText();
            SettlementFormat.Result.Rejected truncated =
                    rejected(text.substring(0, text.indexOf(":62F:")));
            assertThat(truncated.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(truncated.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).isEmpty();
                                assertThat(defect.field()).contains("closingBalance");
                            });
        }

        @Test
        @DisplayName("a blank line inside the statement is MALFORMED")
        void blankLineInside() {
            SettlementFormat.Result.Rejected verdict =
                    rejected(goldenWith(":28C:1\n", ":28C:1\n\n"));
            assertThat(verdict.code()).isEqualTo(RejectionCode.MALFORMED);
            assertThat(verdict.defects())
                    .anySatisfy(
                            defect -> {
                                assertThat(defect.lineNo()).contains(4);
                                assertThat(defect.field()).contains("tag");
                            });
        }

        @Test
        @DisplayName("no defect ever carries a value - not the account reference, not the"
                + " narrative: fields are NAMES, positions are numbers")
        void defectsCarryNoValue() {
            List<SettlementFormat.Result.Rejected> verdicts =
                    List.of(
                            rejected(goldenWith(":25:SIMBANK-EUR-01", ":25:SIMBANK-EUR-02")),
                            rejected(goldenWith(":86:Customer name Jane Doe",
                                    ":86:" + "Customer name Jane Doe ".repeat(20))),
                            rejected(goldenWith("EUR,130.25", "EUR,130.26")));
            for (SettlementFormat.Result.Rejected verdict : verdicts) {
                assertThat(verdict.toString())
                        .doesNotContain("SIMBANK", "Jane", "130.26");
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
        @DisplayName("the golden statement passes - a name in a narrative is not instrument"
                + " data - and its record count is the walk's own")
        void goldenPasses() {
            DeliveryScreen.Screening screening = FORMAT.screen(golden());
            assertThat(screening.finding()).isEmpty();
            assertThat(screening.lineCount()).isEqualTo(13);
        }

        @Test
        @DisplayName("a card number in a NARRATIVE - declared free text - is refused with its"
                + " line and field, written plain or grouped")
        void panInNarrativeRefused() {
            for (String card : List.of(PAN, "4111 1111 1111 1111")) {
                DeliveryScreen.Screening screening =
                        screened(goldenWith(":86:Customer name Jane Doe",
                                ":86:Paid with card " + card));
                assertThat(screening.finding())
                        .as("card written as %s", card)
                        .hasValueSatisfying(
                                finding -> {
                                    assertThat(finding.reason())
                                            .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                    assertThat(finding.lineNo()).isEqualTo(10);
                                    assertThat(finding.fieldName()).contains("narrative");
                                });
            }
        }

        @Test
        @DisplayName("an account identifier in a NARRATIVE is refused as such (INV-RAIL-03)")
        void ibanInNarrativeRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith(":86:Remittance for 2026-09-25",
                            ":86:Pay to " + IBAN + " today"));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(6);
                                assertThat(finding.fieldName()).contains("narrative");
                            });
        }

        @Test
        @DisplayName("an account identifier in place of the opaque account reference fails its"
                + " class and is refused - never retained as a malformed file (C6)")
        void ibanInAccountReferenceRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith(":25:SIMBANK-EUR-01", ":25:" + IBAN));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(2);
                                assertThat(finding.fieldName()).contains("accountRef");
                            });
        }

        @Test
        @DisplayName("an account identifier as a line's remittance reference fails the class"
                + " and is refused")
        void ibanAsRemittanceReferenceRefused() {
            DeliveryScreen.Screening screening =
                    screened(goldenWith(",PSP-REM-20260925", "," + IBAN));
            assertThat(screening.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(5);
                                assertThat(finding.fieldName()).contains("remittanceRef");
                            });
        }

        @Test
        @DisplayName("a card number as a reference - bare or behind letters - fails the class"
                + " and is refused")
        void panAsReferenceRefused() {
            DeliveryScreen.Screening statementRef =
                    screened(goldenWith(":20:SB-STMT-20260925-EUR", ":20:" + PAN));
            assertThat(statementRef.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(1);
                                assertThat(finding.fieldName()).contains("statementRef");
                            });
            DeliveryScreen.Screening remittanceRef =
                    screened(goldenWith(",PSP-REM-20260925", ",REM-" + PAN));
            assertThat(remittanceRef.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
                                assertThat(finding.lineNo()).isEqualTo(5);
                                assertThat(finding.fieldName()).contains("remittanceRef");
                            });
        }

        @Test
        @DisplayName("a CLEAN malformed field - no instrument data - passes the screen, so the"
                + " corrupt delivery is stored as evidence and rejected at parse")
        void cleanMalformedPasses() {
            for (String fault : List.of(
                    goldenWith(":25:SIMBANK-EUR-01", ":25:SIMBANK-EUR-X1"),
                    goldenWith(":25:SIMBANK-EUR-01", ":25:SIMBANK-EUR-02"))) {
                assertThat(screened(fault).finding()).isEmpty();
                assertThat(rejected(fault).code()).isEqualTo(RejectionCode.MALFORMED);
            }
        }

        @Test
        @DisplayName("bytes that are not tagged records are screened as one conservative"
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
            // One unknown tag makes the whole stream unstructured: every byte is screened.
            DeliveryScreen.Screening unknownTag =
                    screened(goldenWith(":25:SIMBANK-EUR-01\n",
                            ":21:" + IBAN + "\n:25:SIMBANK-EUR-01\n"));
            assertThat(unknownTag.finding())
                    .hasValueSatisfying(
                            finding -> {
                                assertThat(finding.reason())
                                        .isEqualTo(RefusalReason.ACCOUNT_IDENTIFIER);
                                assertThat(finding.lineNo()).isEqualTo(2);
                                assertThat(finding.fieldName()).isEmpty();
                            });
        }
    }

    // ------------------------------------------------------------------- the configuration

    @Nested
    @DisplayName("the configured settlement accounts")
    class TheConfiguredAccounts {

        @Test
        @DisplayName("the format names itself: SIM_STATEMENT_TAGGED, version 1")
        void identity() {
            assertThat(FORMAT.id()).isEqualTo(SettlementFormatId.SIM_STATEMENT_TAGGED);
            assertThat(FORMAT.version()).isEqualTo(1);
        }

        @Test
        @DisplayName("a configured reference without the bank's opaque shape is refused at"
                + " construction, and the refusal never echoes it")
        void malformedReferenceRefused() {
            Throwable refusal =
                    catchThrowable(() -> new SimStatementTaggedFormat(Map.of(EUR, IBAN)));
            assertThat(refusal).isInstanceOf(IllegalArgumentException.class);
            assertThat(refusal.getMessage()).contains("EUR").doesNotContain(IBAN);
        }

        @Test
        @DisplayName("a null configuration, or a null reference in it, is refused")
        void nullsRefused() {
            assertThat(catchThrowable(() -> new SimStatementTaggedFormat(null)))
                    .isInstanceOf(NullPointerException.class);
            Map<CurrencyCode, String> withNull = new HashMap<>();
            withNull.put(EUR, null);
            assertThat(catchThrowable(() -> new SimStatementTaggedFormat(withNull)))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("the configuration is copied: a later change to the caller's map changes"
                + " nothing")
        void configurationIsCopied() {
            Map<CurrencyCode, String> mutable = new HashMap<>();
            mutable.put(EUR, EUR_ACCOUNT);
            SimStatementTaggedFormat format = new SimStatementTaggedFormat(mutable);
            mutable.put(EUR, "SIMBANK-EUR-99");
            assertThat(parsed(format, golden()).lines()).hasSize(5);
        }

        @Test
        @DisplayName("toString carries identifiers only - never a configured reference")
        void toStringCarriesNoReference() {
            assertThat(FORMAT.toString())
                    .contains("SIM_STATEMENT_TAGGED")
                    .doesNotContain(EUR_ACCOUNT, GBP_ACCOUNT, "SIMBANK");
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
        assertThat(line.amount().currency()).isEqualTo(EUR);
        assertThat(line.businessDate()).isEqualTo(STATEMENT_DAY);
        assertThat(line.settlementDate()).isEmpty();
        assertThat(line.valueDate()).contains(STATEMENT_DAY);
    }
}
