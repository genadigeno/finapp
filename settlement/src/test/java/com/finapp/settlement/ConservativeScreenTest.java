package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The whole-stream screen (`P8-TSK-002`, ADR-0066 §3): what must never be stored is found
 * before anything is, and what merely looks numeric passes — the Luhn checksum is the line
 * between instrument data and invoice numbers.
 */
@DisplayName("the conservative door screen (P8-TSK-002)")
class ConservativeScreenTest {

    private static DeliveryScreen.Screening screened(String text) {
        return ConservativeScreen.INSTANCE.screen(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a Luhn-valid 16-digit run is refused, naming its line")
    void aPlainPanIsRefused() {
        DeliveryScreen.Screening screening =
                screened("header\nrow,4111111111111111,EUR\ntrailer");
        assertThat(screening.finding())
                .contains(
                        new DeliveryScreen.Finding(
                                RefusalReason.PRIMARY_ACCOUNT_NUMBER, 2, Optional.empty()));
    }

    @Test
    @DisplayName("a PAN written with separators is still a PAN - single spaces or dashes"
            + " between digit groups collapse")
    void aSeparatedPanIsRefused() {
        assertThat(screened("note: 4111 1111 1111 1111 paid").finding())
                .map(DeliveryScreen.Finding::reason)
                .contains(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
        assertThat(screened("note: 4111-1111-1111-1111 paid").finding())
                .map(DeliveryScreen.Finding::reason)
                .contains(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
    }

    @Test
    @DisplayName("a PAN at the end of a record, and at the end of the file, is still caught")
    void aPanAtTheBoundariesIsCaught() {
        assertThat(screened("row,4111111111111111\nnext").finding())
                .map(DeliveryScreen.Finding::reason)
                .contains(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
        assertThat(screened("row,4111111111111111").finding())
                .map(DeliveryScreen.Finding::reason)
                .contains(RefusalReason.PRIMARY_ACCOUNT_NUMBER);
    }

    @Test
    @DisplayName("sixteen digits that fail Luhn pass - an order number is not an instrument")
    void aNonLuhnRunPasses() {
        assertThat(screened("order 4111111111111112 shipped\n").finding()).isEmpty();
    }

    @Test
    @DisplayName("a 20-digit run passes the card band and a 12-digit run never reaches it")
    void theBandBounds() {
        // 20 digits, Luhn-valid by construction would still be outside 13..19.
        assertThat(screened("ref 45645645645645645602\n").finding()).isEmpty();
        assertThat(screened("ref 123456789012\n").finding()).isEmpty();
    }

    @Test
    @DisplayName("an international account identifier shape is refused, wherever it sits")
    void anAccountIdentifierIsRefused() {
        DeliveryScreen.Screening screening =
                screened("line1\npaid by DE44500105175407324931 today\n");
        assertThat(screening.finding())
                .contains(
                        new DeliveryScreen.Finding(
                                RefusalReason.ACCOUNT_IDENTIFIER, 2, Optional.empty()));
        // Mixed letters inside (a GB-style identifier) - the shape, not a country list.
        assertThat(screened("GB29NWBK60161331926819").finding())
                .map(DeliveryScreen.Finding::reason)
                .contains(RefusalReason.ACCOUNT_IDENTIFIER);
    }

    @Test
    @DisplayName("clean text passes, and short reference-like tokens pass")
    void cleanTextPasses() {
        assertThat(
                        screened("id,amount,currency,reference\n"
                                        + "1,100,EUR,PSP-REM-123456\n"
                                        + "2,250,EUR,ORD-2026-09-28\n")
                                .finding())
                .isEmpty();
    }

    @Test
    @DisplayName("the walk counts records: terminated, unterminated and empty")
    void theWalkCountsRecords() {
        assertThat(screened("a\nb\nc").lineCount()).isEqualTo(3);
        assertThat(screened("a\nb\n").lineCount()).isEqualTo(2);
        assertThat(screened("").lineCount()).isZero();
    }
}
