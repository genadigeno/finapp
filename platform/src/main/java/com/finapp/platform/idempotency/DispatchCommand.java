package com.finapp.platform.idempotency;

/**
 * The first transaction of a two-transaction keyed command (`P5-TSK-016`,
 * {@link IdempotentExecutor#begin}): the dispatch — everything that must be durable before an
 * external party is spoken to (ADR-0046), committed beside the still-{@code IN_PROGRESS}
 * claim.
 *
 * <p>Returns the command's <em>working state</em> — whatever the caller needs to carry across
 * the connectionless gap (an identifier, typically). It is returned, never stored: the claim's
 * response is written exactly once, by {@link IdempotentExecutor#complete}, and is frozen from
 * then on (platform {@code V003}, {@code INV-LIFE-04}).
 *
 * <p><strong>Must converge.</strong> A lease takeover re-runs this against work the crashed
 * flight already committed, so the implementation's first act is to look for that work by its
 * own natural key and return it rather than repeat it.
 */
@FunctionalInterface
public interface DispatchCommand {

    byte[] dispatch(java.sql.Connection unitOfWork);
}
