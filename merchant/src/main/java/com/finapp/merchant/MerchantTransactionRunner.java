package com.finapp.merchant;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One transaction, begun and committed around {@code work} (`P6-TSK-011`) — the
 * {@code CheckoutTransactionRunner} shape, declared again here rather than shared, because a
 * module that cannot import another module's port declares its own.
 *
 * <p><strong>Why the seam exists here</strong>: {@link PayoutDestinationEffectuation} runs
 * <em>one transaction per row</em>, and that is a correctness property rather than a
 * composition detail — a tick that effected a hundred destinations in one transaction would let
 * one poisoned row roll back ninety-nine other merchants' changes. Owning the boundary in the
 * component makes the property provable instead of something each caller re-performs.
 *
 * <p>The connection's lifetime is the call: implementations open it when {@code work} begins,
 * commit when it returns, roll back when it throws, and never leak it past the return.
 */
@FunctionalInterface
public interface MerchantTransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
