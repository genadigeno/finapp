package com.finapp.lending;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The borrower's wallet, as lending reaches it (`P11-TSK-014`; ADR-0097 point 1, {@code PHASE_11_PLAN.md} section 3.2,
 * A16) - a port {@code app} implements over {@code accounts}, because lending has no build edge to the module that owns
 * wallets. On the CALLER's unit of work, so a wallet it opens commits or rolls back with the disbursement.
 *
 * <p>{@link #walletFor} answers the party's {@code CUSTOMER_WALLET} ledger account in {@code currency} - its live wallet
 * agreement's, opened through accounts' one wallet door when the agreement holds none in the currency, and the agreement
 * itself opened when the customer holds none. Empty when the party's wallet cannot be had now - no active customer, the
 * agreement not active - which the disbursement treats as a wallet that cannot be posted: retried until the deadline.
 * Whether the account accepts the posting is the ledger's to judge, under its own lock ({@code ledger V007}'s trigger).
 * The answer is a ledger account id - never a wallet identifier in an event or a log ({@code INV-AUD-02}).
 */
public interface LoanWallets {

    /** The party's wallet ledger account in {@code currency}, opened if absent; empty when it cannot be had now. */
    Optional<LedgerAccountId> walletFor(Connection unitOfWork, UUID party, CurrencyCode currency);

    /**
     * The party's existing wallet ledger account in {@code currency}, whatever the customer's standing - never opened
     * (`P11-TSK-018`): the account a repayment is debited from and a closure's refund credited to. A suspended customer's
     * wallet is found (a suspended customer may still repay); empty when the party holds no live wallet agreement with a
     * wallet in the currency. Whether the account accepts the posting is the ledger's to judge, under the caller's L7.
     */
    Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency);
}
