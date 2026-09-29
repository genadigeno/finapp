package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The descriptor's coherence, refused at construction (`P8-TSK-002`, the
 * {@code RailCapabilities} precedent): a descriptor that constructs is a descriptor whose
 * parts agree.
 */
@DisplayName("the settlement source descriptor (P8-TSK-002)")
class SettlementSourceDescriptorTest {

    @Test
    @DisplayName("a coherent report source and a coherent bank source both construct")
    void coherentDescriptorsConstruct() {
        assertThatCode(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.PULL),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-bank.statement",
                                        SourceKind.BANK_STATEMENT,
                                        SettlementFormatId.SIM_STATEMENT_TAGGED,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.empty(),
                                        Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a format declared for another source kind is refused - a format belongs to"
            + " one kind")
    void aForeignFormatIsRefused() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_SCHEME_JSON,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SIM_SCHEME_JSON");
    }

    @Test
    @DisplayName("an empty channel set is refused - evidence that cannot arrive reconciles"
            + " nothing")
    void anEmptyChannelSetIsRefused() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channel");
    }

    @Test
    @DisplayName("READMISSION is not a channel a source declares")
    void readmissionIsNotADeclaredChannel() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD, DeliveryChannel.READMISSION),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("READMISSION");
    }

    @Test
    @DisplayName("a malformed remittance-reference pattern is refused at construction, not at"
            + " the first match")
    void aMalformedPatternIsRefused() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed remittance-reference pattern");
    }

    @Test
    @DisplayName("the position and the kind agree both ways: a report settles exactly one, the"
            + " bank statement none (INV-SET-05)")
    void positionAndKindAgree() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.empty(),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INV-SET-05");
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-bank.statement",
                                        SourceKind.BANK_STATEMENT,
                                        SettlementFormatId.SIM_STATEMENT_TAGGED,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("P8-TSK-016");
    }

    @Test
    @DisplayName("the remittance pattern and the kind agree both ways: reports declare one, the"
            + " bank statement none")
    void remittancePatternAndKindAgree() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-psp.settlement",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remittance-reference");
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "simulated-bank.statement",
                                        SourceKind.BANK_STATEMENT,
                                        SettlementFormatId.SIM_STATEMENT_TAGGED,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.empty(),
                                        Optional.of("BANK-[0-9]+")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remittance-reference");
    }

    @Test
    @DisplayName("the code has the dotted lowercase shape")
    void theCodeShapeHolds() {
        assertThatThrownBy(
                        () ->
                                new SettlementSourceDescriptor(
                                        "Simulated PSP!",
                                        SourceKind.PSP_SETTLEMENT_REPORT,
                                        SettlementFormatId.SIM_PSP_CSV,
                                        1,
                                        Set.of(DeliveryChannel.UPLOAD),
                                        Optional.of(AccountPurpose.SETTLEMENT_CLEARING),
                                        Optional.of("PSP-REM-[0-9]{4,12}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowercase");
    }
}
