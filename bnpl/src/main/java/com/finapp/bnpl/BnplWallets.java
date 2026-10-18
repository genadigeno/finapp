package com.finapp.bnpl;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The customer's wallet, as bnpl reaches it (`P12-TSK-013`; owner decision B6 - instalments are collected from the wallet
 * only, A7; {@code PHASE_12_PLAN.md} sections 3.3 and 7.1 T-pur) - a port {@code app} implements over {@code accounts},
 * because bnpl has no build edge to the module that owns wallets ({@code BnplModuleIsolationTest}; lending's
 * {@code LoanWallets.existingWallet} precedent). On the caller's unit of work.
 *
 * <p><strong>The existing wallet only</strong>: the party's live {@code WALLET} agreement's {@code CUSTOMER_WALLET} ledger
 * account in exactly {@code currency} - never opened, never a fallback currency. A pay-in-4 purchase collects instalment
 * 1 from money already there; a customer with no wallet in the currency cannot fund it ({@code 422
 * bnpl.InsufficientFunds}). Whether the account accepts the posting, and what it can spend, the purchase judges under its
 * own (P) lock on the row. The answer is a ledger account id - never a wallet identifier in an event or a log
 * ({@code INV-AUD-02}).
 */
public interface BnplWallets {

    /** The party's existing wallet ledger account in {@code currency}, or empty. */
    Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency);
}
