package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The register's own rules (`P8-TSK-002`): one source per code, and at most one source per
 * settled position — {@code INV-SET-05}'s static rank.
 */
@DisplayName("the settlement source register (P8-TSK-002)")
class SettlementSourcesTest {

    private static SettlementSourceDescriptor psp(String code, AccountPurpose position) {
        return new SettlementSourceDescriptor(
                code,
                SourceKind.PSP_SETTLEMENT_REPORT,
                SettlementFormatId.SIM_PSP_CSV,
                1,
                Set.of(DeliveryChannel.UPLOAD),
                Optional.of(position),
                Optional.of("PSP-REM-[0-9]{4,12}"));
    }

    @Test
    @DisplayName("a duplicate code is refused - a source's code is its identity")
    void aDuplicateCodeIsRefused() {
        assertThatThrownBy(
                        () ->
                                SettlementSources.of(
                                        List.of(
                                                psp("simulated-psp.settlement",
                                                        AccountPurpose.SETTLEMENT_CLEARING),
                                                psp("simulated-psp.settlement",
                                                        AccountPurpose.INSTANT_CLEARING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity");
    }

    @Test
    @DisplayName("two sources on one position are refused - exactly one declared source"
            + " discharges each settling position (INV-SET-05)")
    void aTwiceDischargedPositionIsRefused() {
        assertThatThrownBy(
                        () ->
                                SettlementSources.of(
                                        List.of(
                                                psp("simulated-psp.settlement",
                                                        AccountPurpose.SETTLEMENT_CLEARING),
                                                psp("simulated-psp.second",
                                                        AccountPurpose.SETTLEMENT_CLEARING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-SET-05");
    }

    @Test
    @DisplayName("lookups answer by code and by position")
    void lookupsAnswer() {
        SettlementSources sources =
                SettlementSources.of(
                        List.of(psp("simulated-psp.settlement", AccountPurpose.SETTLEMENT_CLEARING)));
        assertThat(sources.byCode("simulated-psp.settlement")).isPresent();
        assertThat(sources.byCode("simulated-psp.unknown")).isEmpty();
        assertThat(sources.dischargedBy(AccountPurpose.SETTLEMENT_CLEARING))
                .map(SettlementSourceDescriptor::code)
                .contains("simulated-psp.settlement");
        assertThat(sources.dischargedBy(AccountPurpose.INSTANT_CLEARING)).isEmpty();
    }

    // ------------------------------------------------ attribution (P8-TSK-016, ADR-0065 §3)

    private static SettlementSourceDescriptor report(
            String code,
            SourceKind kind,
            SettlementFormatId format,
            AccountPurpose position,
            String remittancePattern) {
        return new SettlementSourceDescriptor(
                code,
                kind,
                format,
                1,
                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                Optional.of(position),
                Optional.of(remittancePattern));
    }

    /** The composed register's shape: three report sources and the bank that attributes. */
    private static SettlementSources composed() {
        return SettlementSources.of(
                List.of(
                        report("simulated-psp.settlement", SourceKind.PSP_SETTLEMENT_REPORT,
                                SettlementFormatId.SIM_PSP_CSV,
                                AccountPurpose.SETTLEMENT_CLEARING, "PSP-REM-[0-9]{4,12}"),
                        report("simulated-scheme.cycle-report", SourceKind.SCHEME_CYCLE_REPORT,
                                SettlementFormatId.SIM_SCHEME_JSON,
                                AccountPurpose.INSTANT_CLEARING, "SCH-REM-[0-9]{4,12}"),
                        report("simulated-payout.settlement",
                                SourceKind.PAYOUT_PROVIDER_REPORT,
                                SettlementFormatId.SIM_PAYOUT_CSV,
                                AccountPurpose.PAYOUT_CLEARING, "PAY-REM-[0-9]{4,12}"),
                        new SettlementSourceDescriptor(
                                "simulated-bank.statement",
                                SourceKind.BANK_STATEMENT,
                                SettlementFormatId.SIM_STATEMENT_TAGGED,
                                1,
                                Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                Optional.empty(),
                                Optional.empty())));
    }

    @Test
    @DisplayName("attribution answers the ONE declared source whose pattern fully matches")
    void attributionAnswersTheUniqueFullMatch() {
        SettlementSources sources = composed();
        assertThat(sources.attribute("PSP-REM-20260925"))
                .map(SettlementSourceDescriptor::code)
                .contains("simulated-psp.settlement");
        assertThat(sources.attribute("SCH-REM-4411"))
                .map(SettlementSourceDescriptor::code)
                .contains("simulated-scheme.cycle-report");
        assertThat(sources.attribute("PAY-REM-7788"))
                .map(SettlementSourceDescriptor::code)
                .contains("simulated-payout.settlement");
    }

    @Test
    @DisplayName("a reference no pattern matches attributes to nobody - unexplained value,"
            + " parked owned at acceptance")
    void aReferenceNoPatternMatchesIsUnattributed() {
        SettlementSources sources = composed();
        assertThat(sources.attribute("XYZ-REM-1234")).isEmpty();
        assertThat(sources.attribute("PSP-REM-123"))
                .as("one digit short of the declared shape")
                .isEmpty();
        assertThat(sources.attribute("")).isEmpty();
    }

    @Test
    @DisplayName("attribution is a FULL match - a pattern found inside a longer reference"
            + " attributes nothing")
    void attributionIsAFullMatchOnly() {
        SettlementSources sources = composed();
        assertThat(sources.attribute("xxPSP-REM-1234")).isEmpty();
        assertThat(sources.attribute("PSP-REM-1234xx")).isEmpty();
        assertThat(sources.attribute("SCH-REM-4411 PSP-REM-1234")).isEmpty();
    }

    @Test
    @DisplayName("two sources whose patterns both match are ambiguous: empty, NEVER the first"
            + " of the two - and one match alone still attributes")
    void anAmbiguousReferenceIsUnattributed() {
        SettlementSources overlapping =
                SettlementSources.of(
                        List.of(
                                report("overlap-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        AccountPurpose.SETTLEMENT_CLEARING, "REM-[0-9]{4}"),
                                report("overlap-scheme.cycle-report",
                                        SourceKind.SCHEME_CYCLE_REPORT,
                                        SettlementFormatId.SIM_SCHEME_JSON,
                                        AccountPurpose.INSTANT_CLEARING, "REM-[0-9]{4,6}")));
        assertThat(overlapping.attribute("REM-1234"))
                .as("both patterns match: an ambiguous line is never guessed into a position")
                .isEmpty();
        assertThat(overlapping.attribute("REM-12345"))
                .as("only the wider pattern matches five digits")
                .map(SettlementSourceDescriptor::code)
                .contains("overlap-scheme.cycle-report");
    }

    @Test
    @DisplayName("the bank statement declares no pattern of its own and is never an"
            + " attribution's answer")
    void theBankIsNeverAnAttribution() {
        SettlementSources sources = composed();
        assertThat(sources.declared())
                .filteredOn(source -> source.kind() == SourceKind.BANK_STATEMENT)
                .singleElement()
                .satisfies(bank -> assertThat(bank.remittanceReferencePattern()).isEmpty());
        assertThat(sources.attribute("SB-STMT-20260925-EUR")).isEmpty();
        assertThatThrownBy(() -> sources.attribute(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ------------------------------------------ per counterparty (P9-TSK-010, ADR-0078 section 6)

    private static SettlementSourceDescriptor counterpartySource(String code, String counterparty) {
        return new SettlementSourceDescriptor(
                code,
                SourceKind.PSP_SETTLEMENT_REPORT,
                SettlementFormatId.SIM_PSP_CSV,
                1,
                Set.of(DeliveryChannel.UPLOAD),
                Optional.of(AccountPurpose.FX_PROVIDER_CLEARING),
                Optional.of(code.replace('.', '-').toUpperCase() + "-[0-9]{4}"),
                Optional.of(counterparty),
                Set.of(com.finapp.sharedkernel.money.CurrencyCode.of("EUR")));
    }

    @Test
    @DisplayName("two sources on ONE counterparty's position are refused; two counterparties on one"
            + " purpose are two positions, each with its own source (INV-SET-05, INV-RAIL-04)")
    void theRegisterIsKeyedPerCounterparty() {
        assertThatThrownBy(() -> SettlementSources.of(List.of(
                        counterpartySource("fx-sim-a.trade-report", "fx-sim-a"),
                        counterpartySource("fx-sim-a.second-report", "fx-sim-a"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FX_PROVIDER_CLEARING of fx-sim-a")
                .hasMessageContaining("INV-SET-05");

        SettlementSources two = SettlementSources.of(List.of(
                counterpartySource("fx-sim-a.trade-report", "fx-sim-a"),
                counterpartySource("fx-sim-b.trade-report", "fx-sim-b"),
                psp("simulated-psp.settlement", AccountPurpose.SETTLEMENT_CLEARING)));
        assertThat(two.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-a").orElseThrow().code())
                .isEqualTo("fx-sim-a.trade-report");
        assertThat(two.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-b").orElseThrow().code())
                .isEqualTo("fx-sim-b.trade-report");
        assertThat(two.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING, "fx-sim-c")).isEmpty();
        assertThat(two.settledPositions()).containsExactlyInAnyOrder(
                new SettlementSources.Position(AccountPurpose.FX_PROVIDER_CLEARING, Optional.of("fx-sim-a")),
                new SettlementSources.Position(AccountPurpose.FX_PROVIDER_CLEARING, Optional.of("fx-sim-b")),
                new SettlementSources.Position(AccountPurpose.SETTLEMENT_CLEARING, Optional.empty()));
        assertThatThrownBy(() -> two.dischargedBy(AccountPurpose.FX_PROVIDER_CLEARING))
                .as("a counterparty-owned purpose has no shared position to ask about")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counterparty-owned");
    }
}
