package com.finapp.credit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credit reason screen (the Phase 10 to 11 transition; {@code INV-AUD-02}): a person's reason is present, bounded,
 * and holds no card-number or bank-account shape - while the platform's own identifiers and ordinary prose pass.
 */
@DisplayName("a credit reason holds no instrument shape (the Phase 10 to 11 transition)")
class CreditReasonsTest {

    @Test
    @DisplayName("a card number - spaced, dashed, colon-grouped - and an account identifier are refused, by what they are")
    void instrumentShapesAreRefused() {
        for (String needle : List.of("card 4111 1111 1111 1111 seen", "card 4111-1111-1111-1111 seen",
                "card 4111:1111:1111:1111 seen", "pay GB82 WEST 1234 5698 7654 32", "pay GB82WEST12345698765432")) {
            assertThat(CreditReasons.defect(needle)).as(needle).contains(
                    "a reason must not hold a card-number or bank-account shape");
            assertThat(CreditReasons.holdsInstrument(needle)).as(needle).isTrue();
        }
    }

    @Test
    @DisplayName("blank, absent and over-long reasons are refused; prose, invoice numbers and platform UUIDs pass")
    void theBoundsAndTheProse() {
        assertThat(CreditReasons.defect(null)).isPresent();
        assertThat(CreditReasons.defect("   ")).isPresent();
        assertThat(CreditReasons.defect("x".repeat(CreditReasons.MAX_LENGTH + 1))).isPresent();
        assertThat(CreditReasons.defect("x".repeat(CreditReasons.MAX_LENGTH))).isEmpty();
        for (String prose : List.of("verified income by phone", "invoice 2026-10-10 / 4471",
                "see case 01a121cc-7402-7abc-8def-0123456789ab")) {
            assertThat(CreditReasons.defect(prose)).as(prose).isEmpty();
            assertThat(CreditReasons.holdsInstrument(prose)).as(prose).isFalse();
        }
        assertThat(CreditReasons.holdsInstrument(null)).isFalse();
    }
}
