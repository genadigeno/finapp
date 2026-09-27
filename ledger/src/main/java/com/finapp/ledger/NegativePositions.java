package com.finapp.ledger;

import java.util.Map;
import java.util.Set;

/**
 * How many accounts of the named purposes stand below zero (`P7-TSK-013`, ADR-0061 §5) — the
 * negative-position gauge's read: a merchant payable driven negative by a chargeback after a
 * payout is merchant debt, and a customer wallet driven negative by a charged-back top-up the
 * customer spent is a receivable from the customer. Both are legal and neither is absorbed; both
 * must be visible.
 *
 * <p><strong>A count, from the projection, for telemetry only.</strong> It reads
 * {@code ledger.account_balance} — current with every posting, never behind — and is never the
 * input to a financial decision ({@code INV-BAL-05}: decisions derive from the journal under the
 * account lock). A count is not an amount ({@code INV-AUD-02}).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface NegativePositions<T> {

    /** Per requested purpose, how many of its accounts are below zero; zero-count purposes
     * are present with {@code 0L}, so an absent key never masquerades as a clean reading. */
    Map<AccountPurpose, Long> countBelowZero(T unitOfWork, Set<AccountPurpose> purposes);
}
