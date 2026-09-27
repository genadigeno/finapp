package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment view's own rendering discipline (`P7-TSK-009`): the record carries an amount
 * ({@code RESTRICTED-FINANCIAL}, {@code INV-AUD-02}) and — while its payer must act — the
 * authorization handle, a capability URL whose appearance in a log line can complete or
 * observe the payer's flow. The generated record {@code toString} would print both, so the
 * override is load-bearing, and the secret-name rule's exemption for
 * {@code authorizationHandle} is CONDITIONED on this test (the
 * {@code CheckoutRecordsRedactTest} claim at the payments surface).
 */
@DisplayName("the payment view renders identifiers only (P7-TSK-009)")
class PaymentViewRedactsTest {

    private static final String HANDLE = "https://payer-psp.example/authorize/opaque-handle";

    @Test
    @DisplayName("toString names the id and the state - never the amount, never the handle")
    void theViewNamesNoAmountAndNoHandle() {
        String id = UUID.randomUUID().toString();
        PaymentService.PaymentView view =
                new PaymentService.PaymentView(
                        id,
                        "PROCESSING",
                        null,
                        "123.45",
                        "USD",
                        UUID.randomUUID().toString(),
                        "2026-09-27T12:00:00Z",
                        "0.00",
                        "0.00",
                        HANDLE);

        assertThat(view.toString())
                .contains(id)
                .contains("PROCESSING")
                .doesNotContain("123.45")
                .doesNotContain(HANDLE)
                .doesNotContain("payer-psp");
        assertThat(String.format("%s", view)).doesNotContain(HANDLE);
    }
}
