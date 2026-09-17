package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * {@code finapp.accounts.account} by outcome (`P3-TSK-020`, `PHASE_3_PLAN.md` §15): account
 * lifecycle movement — {@code opened} and {@code closed}.
 *
 * <p><strong>Only the acting call counts.</strong> A converged open, a replayed key and the
 * nine losers of a ten-way close are never throughput (`P2-TSK-020`'s discipline at the case
 * counter, verbatim): {@code Creation.created()} and {@code Closure.closed()} already say
 * which call acted, and {@link com.finapp.app.accounts.AccountService} increments
 * <strong>after its transaction commits</strong> — the {@code ConsentService} rule, because
 * an act that rolled back is an act that did not happen.
 *
 * <p>Both series are registered at construction (`P1-TSK-029`): a freshly started instance
 * publishes healthy zeros. Public because the counting seam lives in {@code app.accounts};
 * the vocabulary is two methods, not a registry handle.
 */
public final class AccountMetrics {

    /** {@code finapp.accounts.account} — lifecycle movement, by outcome. */
    static final String ACCOUNT = "finapp.accounts.account";

    private final Counter opened;
    private final Counter closed;

    AccountMetrics(MeterRegistry registry) {
        this.opened = counter(registry, "opened", "a customer account was opened");
        this.closed = counter(registry, "closed", "a customer account was closed");
    }

    private static Counter counter(MeterRegistry registry, String outcome, String what) {
        return Counter.builder(ACCOUNT)
                .tag("outcome", outcome)
                .description(
                        "Customer-account lifecycle movement: " + what + ". Only the acting"
                                + " call counts - a converged retry is never throughput."
                                + " Per instance; rate() and sum() aggregate")
                .register(registry);
    }

    /** The creating act, post-commit. */
    public void opened() {
        opened.increment();
    }

    /** The winning close, post-commit. */
    public void closed() {
        closed.increment();
    }
}
