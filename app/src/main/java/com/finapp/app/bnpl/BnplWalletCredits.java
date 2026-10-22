package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplDisputes;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.CustomerId;
import com.finapp.party.PartyStore;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The BNPL arm of the wallet close's pending-credits question (the Phase 12 to 13 transition gate's A7-1; the payments
 * precedent {@code DisputeStore.anyRestorableTo} beside it in {@code AccountsBeans}): <strong>a wallet cannot close while an
 * open BNPL dispute may refund to it</strong>. A dispute's refund outcome credits the customer's wallet - all of it on a
 * {@code CLOSED} obligation, which holds no credit balance (INV-BNPL-09) - and a closed wallet refuses the posting, so the
 * dispute's lapse would retry {@code not_postable} for ever and the refund owed would be recorded nowhere.
 *
 * <p>Asked by {@code AccountClosing} for each ledger account of the closing agreement, <strong>under that account's
 * {@code FOR UPDATE}</strong>: the account re-read under the lock the close holds (re-taking it is free) - only a
 * {@code CUSTOMER_WALLET} is a refund's destination - and the closing customer's party asked of bnpl
 * ({@link BnplDisputes#anyMayRefundToWalletOf}, a read of bnpl's own rows): any {@code OPEN} or {@code MERCHANT_RESPONDED}
 * dispute of the party in the wallet's currency. accounts never reads a bnpl table, and bnpl never reads accounts'. The
 * opening of a dispute on a {@code CLOSED} obligation, and every refund outcome, take the wallet's row {@code FOR UPDATE} at
 * (P): a close either waits for such an act and then sees its dispute, or commits first and the act finds the wallet
 * {@code CLOSED} and refuses ({@code bnpl.WalletUnavailable} at the opening).
 */
@RequiredArgsConstructor
public final class BnplWalletCredits {

    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final BnplDisputes disputes;

    /** Whether an open BNPL dispute of {@code customerId}'s party may refund to {@code account}. */
    public boolean anyFor(Connection unitOfWork, LedgerAccountId account, UUID customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(customerId, "customerId");
        Optional<LedgerAccount> wallet = ledgerAccounts.lockForUpdate(unitOfWork, account)
                .filter(found -> found.purpose() == AccountPurpose.CUSTOMER_WALLET);
        if (wallet.isEmpty()) {
            return false;
        }
        UUID party = parties.partyOfCustomer(unitOfWork, CustomerId.of(customerId))
                .orElseThrow(() -> new IllegalStateException("a customer closing an agreement belongs to a party"))
                .value();
        return disputes.anyMayRefundToWalletOf(unitOfWork, party, wallet.get().currency());
    }
}
