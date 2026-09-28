package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The push-rail port's records never print what the platform may not log (`P7-DOC-001`,
 * {@code INV-RAIL-03}, {@code security.md}). The Phase 7 review found the grant and the
 * customer's bank destination travelling past the {@code paymentmethods} boundary in records
 * whose GENERATED {@code toString} printed them: "never logged" rested on nobody logging the
 * record. Each record now names our reference, and nothing else it carries.
 */
@DisplayName("the push-rail port's records redact (P7-DOC-001, INV-RAIL-03)")
class PushRailRecordsRedactTest {

    private static final EndToEndReference OURS = new EndToEndReference("e2e-ours-1");
    private static final String GRANT = "grant-SECRET-needle-7731";
    private static final ProviderReference DESTINATION =
            new ProviderReference("dest-NEEDLE-4419-opaque");
    private static final Money AMOUNT = Money.ofMinorUnits(987_65, CurrencyCode.of("EUR"));

    @Test
    @DisplayName("the grant exchange's ask names our reference and never the grant")
    void theGrantNeverPrints() {
        String printed = new PushRail.GrantExchange(OURS, GRANT).toString();

        assertThat(printed).contains(OURS.value()).doesNotContain(GRANT).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("a credit transfer names our reference - never the destination or the amount")
    void theDestinationNeverPrints() {
        String printed = new PushRail.CreditTransfer(OURS, DESTINATION, AMOUNT).toString();

        assertThat(printed)
                .contains(OURS.value())
                .doesNotContain(DESTINATION.value())
                .doesNotContain("NEEDLE")
                .doesNotContain("987");
    }

    @Test
    @DisplayName("an exchange answer names its outcome and name check - never the destination"
            + " or the display suffix")
    void theExchangeAnswerNeverPrintsTheDestination() {
        String printed =
                ExchangeAnswer.exchanged(
                                DESTINATION,
                                "4419",
                                ExchangeAnswer.ConfirmationOfPayee.MATCH,
                                "evidence".getBytes(StandardCharsets.UTF_8))
                        .toString();

        assertThat(printed)
                .contains("EXCHANGED")
                .contains("MATCH")
                .doesNotContain(DESTINATION.value())
                .doesNotContain("NEEDLE")
                .doesNotContain("4419");
    }
}
