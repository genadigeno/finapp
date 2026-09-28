package com.finapp.payments;

/**
 * The wallet's available balance cannot cover the withdrawal (`P7-TSK-008`,
 * {@code INV-BAL-04}) — judged under the wallet account's lock by the hold, so ten
 * concurrent withdrawals admit exactly the affordable set. The refusal aborts the dispatch
 * transaction whole, claim included: an unfunded wallet is transient state, and the same
 * key may honestly retry after a top-up. Names no amount ({@code INV-AUD-02}).
 */
public class WithdrawalUnfundedException extends RuntimeException {

    @java.io.Serial private static final long serialVersionUID = 1L;

    public WithdrawalUnfundedException() {
        super("the wallet's available balance cannot cover the withdrawal");
    }
}
