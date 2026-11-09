package com.finapp.app.accounting;

import com.finapp.accounting.AccountingJournalSources;
import com.finapp.bnpl.BnplJournalSources;
import com.finapp.crossborder.CrossBorderJournalSources;
import com.finapp.fx.FxJournalSources;
import com.finapp.ledger.LedgerJournalSources;
import com.finapp.lending.LendingJournalSources;
import com.finapp.merchant.MerchantJournalSources;
import com.finapp.payments.PaymentJournalSources;
import com.finapp.platform.sourcejoin.JournalSources;
import com.finapp.reconciliation.ReconciliationJournalSources;
import com.finapp.settlement.SettlementJournalSources;
import com.finapp.transfers.TransferJournalSources;
import java.util.List;

/**
 * Every posting module's read port for the source-join census (`P15-TSK-035`): the census's whole reach over the domain, in one place
 * only {@code app} can name. A new posting module joins this list and registers its namespaces in the same change, or
 * {@code SourceJoinCoverageTest} fails the build.
 */
public final class SourceCensusComposition {

    private SourceCensusComposition() {}

    /** Each module's port, new instances: they hold no state and read through the caller's connection. */
    public static List<JournalSources> sources() {
        return List.of(
                new TransferJournalSources(),
                new PaymentJournalSources(),
                new MerchantJournalSources(),
                new CrossBorderJournalSources(),
                new SettlementJournalSources(),
                new ReconciliationJournalSources(),
                new FxJournalSources(),
                new LendingJournalSources(),
                new BnplJournalSources(),
                new LedgerJournalSources(),
                new AccountingJournalSources());
    }
}
