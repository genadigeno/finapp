package com.finapp.settlement.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.settlement.RejectionCode;
import com.finapp.settlement.format.simpayout.SimPayoutCsvFormat;
import com.finapp.settlement.format.simscheme.SimSchemeJsonFormat;
import com.finapp.settlement.format.simstatement.SimStatementTaggedFormat;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rejection's substantiation is bounded (ADR-0066 §9, `V003`'s "up to 100 rows"): a file
 * with far more faults than the bound still rejects cleanly, {@code MALFORMED}, with exactly
 * the FIRST 100 defects — one per faulty record, in file order — never an unbounded list and
 * never a different verdict. The scheme, payout and bank-statement formats each prove it on
 * 150 faulty records (the PSP report's bound is proven beside its own format's tests).
 *
 * <p>Each faulty record is built to carry exactly ONE defect, so 150 records would give 150
 * defects without the bound: a mutation of any format's {@code MAX_DEFECTS} check (removed, or
 * {@code <=}) changes the count.
 */
@DisplayName("every format bounds its defect list at 100 (ADR-0066 §9)")
class FormatDefectBoundTest {

    private static final int FAULTY_RECORDS = 150;
    private static final int BOUND = 100;

    @Test
    @DisplayName("SIM_SCHEME_JSON: 150 entries each with an unknown direction reject MALFORMED"
            + " with exactly the first 100 defects")
    void theSchemeReportIsBounded() {
        StringBuilder json =
                new StringBuilder(
                        "{\n"
                                + "  \"format\": \"SIM_SCHEME_JSON\",\n"
                                + "  \"version\": 1,\n"
                                + "  \"cycle\": \"2026-09-25-C1\",\n"
                                + "  \"currency\": \"EUR\",\n"
                                + "  \"businessDate\": \"2026-09-25\",\n"
                                + "  \"remittanceReference\": \"SCH-REM-20260925\",\n"
                                + "  \"entries\": [\n");
        for (int i = 1; i <= FAULTY_RECORDS; i++) {
            json.append("    {\"seq\": ")
                    .append(i)
                    .append(", \"code\": \"CT\", \"dir\": \"X\", \"amount\": \"1.00\","
                            + " \"schemeRef\": \"SCH-BOUND-")
                    .append(i)
                    .append("\"}")
                    .append(i < FAULTY_RECORDS ? ",\n" : "\n");
        }
        json.append("  ],\n  \"net\": \"0.00\",\n  \"entryCount\": ")
                .append(FAULTY_RECORDS)
                .append("\n}\n");

        assertBoundedMalformed(
                SimSchemeJsonFormat.INSTANCE.parse(bytes(json.toString())), "dir");
    }

    @Test
    @DisplayName("SIM_PAYOUT_CSV: 150 detail records each with a malformed code reject"
            + " MALFORMED with exactly the first 100 defects")
    void thePayoutReportIsBounded() {
        StringBuilder csv =
                new StringBuilder("H,SIM_PAYOUT_CSV,1,PAYDAY-2026-09-26-EUR,EUR,2026-09-26\n");
        for (int i = 1; i <= FAULTY_RECORDS; i++) {
            // "settled" fails the code's class ([A-Z][A-Z_]*) and nothing else on the record.
            csv.append("D,").append(i).append(",settled,-1.00,,po_bound").append(i).append(",,\n");
        }
        csv.append("T,").append(FAULTY_RECORDS).append(",-150.00,PAY-REM-20260926\n");

        assertBoundedMalformed(SimPayoutCsvFormat.INSTANCE.parse(bytes(csv.toString())), "code");
    }

    @Test
    @DisplayName("SIM_STATEMENT_TAGGED: 150 statement lines each with an unknown mark reject"
            + " MALFORMED with exactly the first 100 defects")
    void theBankStatementIsBounded() {
        StringBuilder statement =
                new StringBuilder(
                        ":20:SB-STMT-20260925-EUR\n"
                                + ":25:SIMBANK-EUR-01\n"
                                + ":28C:1\n"
                                + ":60F:C,2026-09-24,EUR,0.00\n");
        for (int i = 1; i <= FAULTY_RECORDS; i++) {
            statement.append(":61:2026-09-25,X,1.00\n");
        }
        statement.append(":62F:C,2026-09-25,EUR,0.00\n");
        SimStatementTaggedFormat format =
                new SimStatementTaggedFormat(Map.of(CurrencyCode.of("EUR"), "SIMBANK-EUR-01"));

        assertBoundedMalformed(format.parse(bytes(statement.toString())), "mark");
    }

    // -----------------------------------------------------------------

    private static void assertBoundedMalformed(SettlementFormat.Result result, String hint) {
        assertThat(result)
                .as("150 faulty records reject the whole file - never a partial batch")
                .isInstanceOf(SettlementFormat.Result.Rejected.class);
        SettlementFormat.Result.Rejected rejected = (SettlementFormat.Result.Rejected) result;
        assertThat(rejected.code())
                .as("a clean MALFORMED verdict: the bound changes how much is said, never what")
                .isEqualTo(RejectionCode.MALFORMED);
        List<FormatDefect> defects = rejected.defects();
        assertThat(defects)
                .as("MAX_DEFECTS = 100: 150 one-defect records yield exactly 100 defects")
                .hasSize(BOUND);
        assertThat(defects)
                .as("every kept defect is the records' own MALFORMED fault (" + hint + ")")
                .allSatisfy(
                        defect -> {
                            assertThat(defect.code()).isEqualTo(RejectionCode.MALFORMED);
                            assertThat(defect.lineNo()).isPresent();
                        });
        List<Integer> lines =
                defects.stream().map(FormatDefect::lineNo).map(Optional::orElseThrow).toList();
        assertThat(lines)
                .as("the FIRST 100 faults are kept, in file order, one per record")
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(defects.stream().map(FormatDefect::field).distinct().toList())
                .as("one field is named throughout - each record carries exactly one fault")
                .hasSize(1);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
