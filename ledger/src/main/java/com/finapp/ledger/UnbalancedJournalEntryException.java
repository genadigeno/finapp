package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.io.Serial;

/**
 * A journal entry whose debits and credits disagree in at least one currency
 * ({@code INV-LED-01}).
 *
 * <p><strong>The message names the currency and the fact — never the amounts.</strong> An
 * exception message reaches logs, and journal amounts are {@code RESTRICTED-FINANCIAL}
 * ({@code INV-AUD-02}); the {@code DatabaseFailure} discipline, applied one layer earlier. The
 * caller that needs the sums holds the lines it just tried to construct from.
 *
 * <p>Carries the currency as a {@code String} rather than the value object, for the recorded
 * serializability reason ({@code IllegalLedgerAccountTransitionException}).
 */
public final class UnbalancedJournalEntryException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    private final String currency;

    public UnbalancedJournalEntryException(CurrencyCode currency) {
        super("journal entry does not balance in " + currency.code()
                + ": total debits must equal total credits per currency (INV-LED-01)");
        this.currency = currency.code();
    }

    /** The ISO 4217 code of the first currency found unbalanced. */
    public String currency() {
        return currency;
    }
}
