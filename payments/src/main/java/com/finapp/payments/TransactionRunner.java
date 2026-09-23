package com.finapp.payments;

import java.sql.Connection;
import java.util.function.Function;

/**
 * One transaction, begun and committed around {@code work} ({@code P5-TSK-009}).
 *
 * <p><strong>Why this seam exists</strong>: ADR-0046's discipline — dispatch committed, the
 * provider called <em>holding no database connection</em>, the outcome applied in a second
 * transaction — must be <strong>production code in the command</strong>, hermetically provable,
 * rather than a composition each caller re-performs (a choreography that lives in callers is a
 * choreography one caller eventually performs wrong). {@link PaymentConfirmation} owns the
 * whole shape through this one seam; the composition root supplies the
 * {@code TransactionTemplate}-backed implementation, a test supplies a recording one and proves
 * the provider call happens strictly between the two transactions (the {@code P1-TSK-026}
 * discipline, asserted rather than described).
 *
 * <p>The connection's lifetime is the call: implementations open it when {@code work} begins,
 * commit when it returns, roll back when it throws, and never leak it past the return — which
 * is exactly what makes "no connection is held during the provider call" a property of
 * {@link PaymentConfirmation}'s straight-line code.
 */
@FunctionalInterface
public interface TransactionRunner {

    <R> R inTransaction(Function<Connection, R> work);
}
