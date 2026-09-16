package com.finapp.ledger;

import java.io.Serial;
import java.util.Objects;

/**
 * A balance that cannot be honestly derived (`P3-TSK-008`).
 *
 * <p>The one unacceptable outcome for the number everything else is checked against is a wrong
 * value returned quietly, so every condition under which the derivation cannot answer is a loud
 * refusal: an account that does not exist, a history mixing scales within one currency (summing
 * raw minor units across scales is meaningless, and normalising is the implicit rounding
 * {@code INV-MON-03} forbids), a line in a foreign currency ({@code INV-MON-04} — unstorable
 * under {@code V005}'s composite FK, guarded here as defence in depth), and a sum outside the
 * representable range ({@code INV-MON-06}).
 *
 * <p><strong>The message names the account, the currency and the fact — never a sum.</strong>
 * An exception message reaches logs and balances are {@code RESTRICTED-FINANCIAL}
 * ({@code INV-AUD-02}); the {@link UnbalancedJournalEntryException} discipline, applied to the
 * derivation. This is also why the fold's own monetary exceptions are translated rather than
 * propagated: {@code Money}'s diagnostics render amounts, which is right for the kernel and
 * wrong for a message describing an account's whole history.
 */
public final class UnderivableBalanceException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Identifier as a {@code String} for the recorded serializability reason. */
    private final String account;

    public UnderivableBalanceException(LedgerAccountId account, String fact) {
        super("balance of account " + account + " is not derivable: "
                + Objects.requireNonNull(fact, "fact must not be null"));
        this.account = account.toString();
    }

    /** The account whose balance could not be derived, as rendered by its identifier type. */
    public String account() {
        return account;
    }
}
