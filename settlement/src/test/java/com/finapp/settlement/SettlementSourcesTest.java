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
}
