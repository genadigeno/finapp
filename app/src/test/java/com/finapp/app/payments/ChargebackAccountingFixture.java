package com.finapp.app.payments;

import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.payments.ChargebackAccounting;
import com.finapp.payments.JdbcDisputeStore;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcPaymentIntentStore;
import com.finapp.payments.JdbcRefundStore;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.WalletDisputeComposition;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;

/**
 * A production-shaped {@link ChargebackAccounting} for the database suites that compose their
 * own outcome component (`P7-TSK-013`): the real stores, the caller's posting service, and the
 * merchant-bound dispute composition production wires — so a refund's failure in those suites
 * runs the SAME re-attribution path production does (a card attempt is locked first, and finds no
 * standing chargeback to re-attribute), never a double that would hide a lock-order defect.
 */
public final class ChargebackAccountingFixture {

    private ChargebackAccountingFixture() {}

    public static ChargebackAccounting over(
            PostingService postings, PaymentRails rails, IdGenerator ids, Clock clock) {
        JdbcLedgerAccountStore ledgerAccounts = new JdbcLedgerAccountStore();
        return new ChargebackAccounting(
                new JdbcDisputeStore(),
                new JdbcRefundStore(),
                new JdbcPaymentAttemptStore(),
                new JdbcPaymentIntentStore(),
                rails,
                new com.finapp.app.merchant.MerchantBoundDisputeComposition(
                        new com.finapp.merchant.MerchantSettlement(
                                new com.finapp.merchant.JdbcPaymentFeePinStore(),
                                new com.finapp.merchant.JdbcFeeScheduleStore(),
                                ledgerAccounts,
                                new ChartOfAccounts<>(ledgerAccounts),
                                new JdbcOutboxWriter(),
                                ids),
                        new WalletDisputeComposition()),
                postings,
                new ChartOfAccounts<>(ledgerAccounts),
                ledgerAccounts,
                new JdbcAuditWriter(),
                ids,
                clock,
                // No dispute stage runs in the suites this fixture serves (P8-TSK-005): a stage
                // expectation reaching here is a harness defect, loud rather than swallowed.
                new com.finapp.payments.SettlementExpectations() {
                    @Override
                    public void open(java.sql.Connection unitOfWork, Opening opening) {
                        throw new AssertionError(
                                "no dispute stage runs in a suite built on this fixture");
                    }

                    @Override
                    public void alias(
                            java.sql.Connection unitOfWork, AliasRegistration registration) {
                        throw new AssertionError(
                                "no dispute stage runs in a suite built on this fixture");
                    }
                });
    }
}
