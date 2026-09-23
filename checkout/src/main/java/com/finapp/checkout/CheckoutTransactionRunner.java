package com.finapp.checkout;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One transaction, begun and committed around {@code work} (`P6-TSK-008`).
 *
 * <p>The {@code payments.TransactionRunner} shape, declared again here rather than shared,
 * because {@code checkout} sees no business sibling at all ({@code CheckoutModuleIsolationTest})
 * — a module that cannot import another module's port declares its own. Two four-line
 * interfaces is the price of the isolation, and it is the cheaper half of that trade.
 *
 * <p><strong>Why the seam exists here</strong>: {@link CheckoutExpirySweeper} runs
 * <em>one transaction per row</em>, and that is a correctness property rather than a
 * composition detail — a tick that swept a hundred sessions in one transaction would let one
 * poisoned row roll back ninety-nine other merchants' expiries. Owning the boundary in the
 * component makes the property hermetically provable instead of something each caller
 * re-performs.
 *
 * <p>The connection's lifetime is the call: implementations open it when {@code work} begins,
 * commit when it returns, roll back when it throws, and never leak it past the return.
 */
@FunctionalInterface
public interface CheckoutTransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
