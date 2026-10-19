package com.finapp.bnpl;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the customer's repayment and the obligation's closure (`P12-TSK-018`, {@code bnpl V010}; lending's
 * {@code RepaymentStore} and {@code LoanStore.close}): the owner-scoped B4 the repayment door takes, the repayment born
 * under a key (the arbiter behind a lapsed claim), the owner's repayment history, and the closure - its row and the
 * obligation's conditional edge. A repayment and its allocation are born through {@link BnplPurchaseStore#insertRepayment}
 * (`P12-TSK-013`'s row, extended). Every instant the database's; every read a customer reaches is owner-scoped in the
 * statement or names an obligation the caller already holds under B4.
 */
public interface BnplRepaymentStore {

    /**
     * (B4) {@code party}'s obligation under agreement {@code agreement} {@code FOR UPDATE}, with its agreement version in
     * force - {@code party_id = ?} in the statement, so another party's agreement is never locked and is the same empty
     * answer as an absent one.
     */
    Optional<BnplServicingStore.Locked> lockOwnedBy(Connection unitOfWork, BnplAgreementId agreement, UUID party);

    /** The repayment of {@code kind} born under {@code requestKey} for the obligation - {@code UNIQUE (obligation_id, kind, request_key)}. */
    Optional<BnplPurchaseStore.Repayment> findByKey(Connection unitOfWork, BnplObligationId obligation, String kind,
            String requestKey);

    /** The obligation's repayments, newest first, at most {@code limit} - each with its allocation in order. */
    List<BnplPurchaseStore.Repayment> ofObligation(Connection unitOfWork, BnplObligationId obligation, int limit);

    /** The obligation's closure, if it closed. */
    Optional<BnplClosure> closureOf(Connection unitOfWork, BnplObligationId obligation);

    /** Births the closure - once per obligation; the database stamps its instant and holds its rows' side. */
    void insertClosure(Connection unitOfWork, BnplClosure closure);

    /**
     * {@code ACTIVE -> CLOSED} for {@code reason}, conditional on {@code ACTIVE} - whether it moved. {@code bnpl V010}'s edge
     * function admits it only beside the closure row born in this transaction for the same reason, and stamps the instant.
     */
    boolean close(Connection unitOfWork, BnplObligationId obligation, BnplClosureReason reason);
}
