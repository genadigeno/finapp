package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal's resolved participants never print the customer's bank destination (the Phase
 * 7 -&gt; 8 transition, {@code INV-RAIL-03}): the generated form did, and the review's claim that
 * the destination travelled only in redacting records was false for this record.
 */
@DisplayName("the resolved withdrawal redacts its destination (the Phase 7 -> 8 transition)")
class WithdrawalsResolvedRedactsTest {

    @Test
    @DisplayName("toString names the identifiers and never the destination reference")
    void theDestinationNeverPrints() {
        String destination = "dst-bank-" + UUID.randomUUID().toString().substring(0, 8);
        Withdrawals.Resolved resolved =
                new Withdrawals.Resolved(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        LedgerAccountId.of(UUID.fromString("01a0e873-2e18-70b6-9674-12bc0c0ea34c")),
                        CurrencyCode.of("EUR"),
                        UUID.randomUUID(),
                        new ProviderReference(destination));
        assertThat(resolved.toString())
                .doesNotContain(destination)
                .contains("destination=<redacted>");
    }
}
