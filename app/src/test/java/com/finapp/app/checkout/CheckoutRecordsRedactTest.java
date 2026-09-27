package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checkout's own records render by the rule its aggregate renders itself by - the
 * identifier and the state, never the token, the amount or the line summary ({@code INV-AUD-02}).
 *
 * <h2>Why these four, and why now</h2>
 *
 * <p>The Phase 6 → 7 transition found every one of them printing all of it through a record's
 * generated {@code toString} - no getter call, no concatenation, nothing a reviewer stops at. The
 * worst was {@code CreatedSessionView}: three guards exempt its token field so that it can be
 * SERIALISED, on the stated ground that the merchant key's view closes the logging half with an
 * override. This record had no override. An exemption resting on means that do not exist is the
 * {@code P1-TSK-018} lesson exactly, so the override arrives here with the test that backs it.
 *
 * <p>The line summary is {@code RESTRICTED-PII} - free text about what one person bought - and the
 * amount {@code RESTRICTED-FINANCIAL}; both appear in the sentinels below so that a regression
 * names which one leaked.
 */
@DisplayName("the checkout's records name no token, no amount and no line summary (the Phase 6 -> 7"
        + " transition)")
class CheckoutRecordsRedactTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String TOKEN = "chk_tok_" + UUID.randomUUID();
    private static final String LINE = "Insulin pens for Jane Q. Example";
    private static final long AMOUNT_MINOR = 987_654L;
    private static final String AMOUNT = "9876.54";

    @Test
    @DisplayName("the creation's view masks the token on every rendering path")
    void theCreatedViewMasksTheToken() {
        CheckoutService.CreatedSessionView view =
                new CheckoutService.CreatedSessionView(
                        UUID.randomUUID().toString(), "OPEN", "2026-09-24T12:00:00Z", TOKEN, false);

        assertThat(view.toString())
                .as("the exemption permits serialisation, never logging")
                .doesNotContain(TOKEN)
                .contains(Sensitive.MASK);
        assertThat("" + view).doesNotContain(TOKEN);
        assertThat(String.format("%s", view)).doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("the merchant's view of a session names neither the amount nor the line summary")
    void theSessionViewNamesNoAmountAndNoLine() {
        String checkoutId = UUID.randomUUID().toString();
        CheckoutService.SessionView view =
                new CheckoutService.SessionView(
                        checkoutId,
                        UUID.randomUUID().toString(),
                        AMOUNT,
                        "EUR",
                        LINE,
                        "OPEN",
                        "2026-09-24T12:00:00Z",
                        null,
                        null,
                        // The payer's authorization handle (P7-TSK-009): a capability URL,
                        // and the identifiers-only toString below must hold for it too.
                        "https://payer-psp.example/authorize/opaque-handle");

        assertThat(view.toString())
                .doesNotContain(AMOUNT)
                .doesNotContain(LINE)
                // The handle too (P7-TSK-009): the secret-name rule's exemption for the
                // field is CONDITIONED on this override holding.
                .doesNotContain("payer-psp")
                .contains(checkoutId)
                .contains("OPEN");
    }

    @Test
    @DisplayName("the open command names neither the amount nor the line summary")
    void theOpenCommandNamesNoAmountAndNoLine() {
        CheckoutSessions.OpenSessionCommand command =
                new CheckoutSessions.OpenSessionCommand(
                        "client-key-1",
                        MerchantId.of(IDS.next()),
                        MerchantApiKeyId.of(IDS.next()),
                        Money.ofMinorUnits(AMOUNT_MINOR, CurrencyCode.of("EUR")),
                        LINE);

        assertThat(command.toString())
                .doesNotContain(AMOUNT)
                .doesNotContain(Long.toString(AMOUNT_MINOR))
                .doesNotContain(LINE)
                .contains("EUR");
    }

    @Test
    @DisplayName("the creation request names neither the amount nor the line summary")
    void theCreateRequestNamesNoAmountAndNoLine() {
        CreateSessionRequest request = new CreateSessionRequest(AMOUNT_MINOR, "EUR", LINE);

        assertThat(request.toString())
                .doesNotContain(Long.toString(AMOUNT_MINOR))
                .doesNotContain(LINE)
                .contains("EUR");
    }
}
