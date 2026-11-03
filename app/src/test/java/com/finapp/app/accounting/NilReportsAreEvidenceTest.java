package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.settlement.DeliveryScreen;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.settlement.format.simcorridor.SimCorridorCsvFormat;
import com.finapp.settlement.format.simfx.SimFxCsvFormat;
import com.finapp.settlement.format.simpayout.SimPayoutCsvFormat;
import com.finapp.settlement.format.simpsp.SimPspCsvFormat;
import com.finapp.settlement.format.simscheme.SimSchemeJsonFormat;
import com.finapp.settlement.format.simstatement.SimStatementTaggedFormat;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <strong>How a month with no activity gets its run</strong> (`P14-TSK-012`; the owner's decisions of 2026-11-02 and 2026-11-03): by a counterparty's nil report through the existing door, no new act, door or migration. Every
 * format the build declares takes a file with no lines: the door's screen passes it, the parse answers a batch with no lines and a zero net. From there acceptance posts nothing ({@code posting_omitted}), a zero net opens no remittance, and
 * reconciliation's run has its own edge at no items ({@code JdbcMatchingStore.completeRun}: {@code OPEN -> COMPLETED}). The whole path against PostgreSQL is {@code QuietMonthDatabaseTest}'s (owed, C5).
 *
 * <p>This is evidence or a person's recorded act, never the platform's: a batch is born only of a file through the door, by a pull its channel authenticates or an upload a second person attests.
 */
@DisplayName("a nil report is evidence, in every declared format (P14-TSK-012)")
class NilReportsAreEvidenceTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** A nil report per format, each by its own golden file's grammar. */
    private static Map<SettlementFormat, String> nilReports() {
        Map<SettlementFormat, String> reports = new LinkedHashMap<>();
        reports.put(SimPspCsvFormat.INSTANCE, "H,SIM_PSP_CSV,1,PSPB-2097-03-31-01,EUR,2097-03-31\nT,0,0.00,PSP-REM-20970331\n");
        reports.put(SimPayoutCsvFormat.INSTANCE, "H,SIM_PAYOUT_CSV,1,PAYDAY-2097-03-31-EUR,EUR,2097-03-31\nT,0,0.00,PAY-REM-20970331\n");
        reports.put(SimFxCsvFormat.INSTANCE, "H,SIM_FX_CSV,1,FXB-2097-03-31-EUR,EUR,2097-03-31\nT,0,0.00,FXA-20970331\n");
        reports.put(SimCorridorCsvFormat.INSTANCE, "H,SIM_CORRIDOR_CSV,1,XB-2097-03-31-USD,USD,2097-03-31\nT,0,0.00,XBA-20970331\n");
        reports.put(SimSchemeJsonFormat.INSTANCE, "{\"format\":\"SIM_SCHEME_JSON\",\"version\":1,\"cycle\":\"2097-03-31-C1\",\"currency\":\"EUR\",\"businessDate\":\"2097-03-31\","
                + "\"remittanceReference\":\"SCH-REM-20970331\",\"entries\":[],\"net\":\"0.00\",\"entryCount\":0}");
        reports.put(new SimStatementTaggedFormat(Map.of(EUR, "SIMBANK-EUR-01")),
                ":20:SB-STMT-20970331-EUR\n:25:SIMBANK-EUR-01\n:28C:1\n:60F:C,2097-03-30,EUR,130.25\n:62F:C,2097-03-31,EUR,130.25\n");
        return reports;
    }

    @Test
    @DisplayName("every format screens a file with no lines clean and parses it: one batch, no lines, a zero net, its business date in the month")
    void everyFormatTakesANilReport() {
        assertThat(nilReports()).hasSize(6);
        nilReports().forEach((format, report) -> {
            byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
            DeliveryScreen.Screening screened = format.screen(bytes);
            assertThat(screened.finding()).as("%s: the door's screen", format.id()).isEmpty();
            SettlementFormat.Result result = format.parse(bytes);
            assertThat(result).as("%s: the parse", format.id()).isInstanceOf(SettlementFormat.Result.Parsed.class);
            ParsedBatch batch = ((SettlementFormat.Result.Parsed) result).batch();
            assertThat(batch.lines()).as(format.id().name()).isEmpty();
            assertThat(batch.declaredLineCount()).as(format.id().name()).isZero();
            assertThat(batch.declaredNet().isZero()).as(format.id().name()).isTrue();
            assertThat(batch.businessDate().getMonthValue()).as(format.id().name()).isEqualTo(3);
        });
    }
}
