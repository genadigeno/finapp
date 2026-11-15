package com.finapp.app.accounting;

import com.finapp.ledger.CheckpointedPositionBreakdown;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.merchant.MerchantPayable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The census of the merchant API's payable checkpoints, wired (`X-TSK-021`; ADR-0133 decision 3): the two reads the source proofs'
 * merchant payable reading compares.
 *
 * <p>The merchant API answers a payable from a commit-order checkpoint and the lines since ({@code P16-REM-001}). A checkpoint row is
 * the application role's to write, and each new one is built on the one before, so one stored wrong - by a defect, or by a holder of
 * that credential - would show a merchant a wrong payable for good, and the proofs, which fold the whole history, would never see it.
 * So the reading judges each payable twice on the proofs' snapshot: its terms against its position, from the whole fold
 * ({@code MERCHANT_PAYABLE}), and the API's drill-down, read exactly as the API reads it but never storing, against the whole fold's
 * ({@code PAYABLE_CHECKPOINT}). A disagreement is an accounting break; an operator retires the account's checkpoints
 * ({@code OPERATIONS_RUNBOOK.md} 11.48).
 *
 * <p>Wiring, so it may build the ledger's adapters (ADR-0006's store rule), and the one class besides the payable query's wiring that
 * may name the checkpointed read, and the only one that makes {@link ProductSourceProofs.PayableReads}: the proofs' wiring takes that
 * bean, so the comparison is always made ({@code PositionCheckpointsAreTheApisAloneTest}).
 */
@Configuration(proxyBeanMethods = false)
public class PayableCheckpointCensus {

    /** The whole fold, and the API's read from its checkpoint that never stores. */
    @Bean
    public ProductSourceProofs.PayableReads payableReads() {
        return new ProductSourceProofs.PayableReads(
                new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown()),
                new MerchantPayable(new JdbcLedgerAccountStore(), CheckpointedPositionBreakdown.readingOnly()));
    }
}
