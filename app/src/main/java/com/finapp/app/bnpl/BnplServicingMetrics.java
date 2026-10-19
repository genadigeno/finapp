package com.finapp.app.bnpl;

import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.CollectionOutcome;
import com.finapp.bnpl.ServicingOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * The servicing step's series (`P12-TSK-015`; {@code PHASE_12_PLAN.md} section 15, which this task extends with them -
 * the plan named no servicing series; lending's {@code LoanServicingMetrics}):
 *
 * <ul>
 *   <li>{@code finapp.bnpl.billing{product, outcome}} - each instalment a committed servicing step billed
 *       ({@code billed}) and each credit-balance application born at one ({@code credit_applied}), counted once after the
 *       step's transaction committed;
 *   <li>{@code finapp.bnpl.servicing{step, outcome}} - each servicing step ({@code step = service}) by its outcome
 *       ({@code serviced}, {@code behind_accrual}, {@code not_serviced}, {@code failed} - rolled back, retried at the
 *       claim's cadence), and each catch-up application ({@code step = catch_up}, {@code outcome = applied}) - the
 *       Phase 11 gate's R2 visible; and each closure the step made ({@code step = closure}, {@code outcome = closed},
 *       `P12-TSK-018`); the steps later tasks add (condition, late fee) join the {@code step} set;
 *   <li>{@code finapp.bnpl.collection{outcome}} (`P12-TSK-019`, {@code PHASE_12_PLAN.md} section 15) - each automatic
 *       collection attempt of a committed step, by its outcome ({@code collected}, {@code partial},
 *       {@code insufficient_funds}, {@code wallet_not_postable}, {@code nothing_due}); a collection that moved money is
 *       also one {@code finapp.bnpl.repayment{kind = auto_collection}} ({@code repaid}, or {@code closed} when the step's
 *       closure followed it), and one that took nothing from an instalment with something due one
 *       {@code insufficient_funds} there - {@link BnplRepaymentMetrics}' series, the customer's door's beside it.
 * </ul>
 *
 * <p>Every series registered eagerly. Never an amount, a party, an obligation id or a date in a tag (ADR-0072).
 */
public final class BnplServicingMetrics {

    /** Exported as {@code finapp_bnpl_billing_total}. */
    public static final String BILLING = "finapp.bnpl.billing";

    /** Exported as {@code finapp_bnpl_servicing_total}. */
    public static final String SERVICING = "finapp.bnpl.servicing";

    /** Exported as {@code finapp_bnpl_collection_total} (`P12-TSK-019`), the plan's name. */
    public static final String COLLECTION = "finapp.bnpl.collection";

    /** The billing series' closed set of outcomes. */
    public enum BillingOutcome {
        BILLED,
        CREDIT_APPLIED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The servicing series' closed set of steps. */
    public enum Step {
        SERVICE,
        CATCH_UP,
        /** The closure step (`P12-TSK-018`): an obligation the step settled and closed. */
        CLOSURE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The servicing series' closed set of outcomes. */
    public enum Outcome {
        SERVICED,
        BEHIND_ACCRUAL,
        NOT_SERVICED,
        FAILED,
        APPLIED,
        CLOSED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final BnplRepaymentMetrics repayments;

    public BnplServicingMetrics(MeterRegistry registry, BnplRepaymentMetrics repayments) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.repayments = Objects.requireNonNull(repayments, "repayments");
        for (CollectionOutcome outcome : CollectionOutcome.values()) {
            collection(outcome);
        }
        for (BnplProduct product : BnplProduct.values()) {
            for (BillingOutcome outcome : BillingOutcome.values()) {
                billing(product, outcome);
            }
        }
        for (Outcome outcome : new Outcome[] {Outcome.SERVICED, Outcome.BEHIND_ACCRUAL, Outcome.NOT_SERVICED,
                Outcome.FAILED}) {
            servicing(Step.SERVICE, outcome);
        }
        servicing(Step.CATCH_UP, Outcome.APPLIED);
        servicing(Step.CLOSURE, Outcome.CLOSED);
    }

    /** A committed step's outcome - its billings, its applications and its catch-up. */
    public void record(ServicingOutcome step) {
        Objects.requireNonNull(step, "step");
        servicing(Step.SERVICE, switch (step.kind()) {
            case SERVICED -> Outcome.SERVICED;
            case BEHIND_ACCRUAL -> Outcome.BEHIND_ACCRUAL;
            case NOT_SERVICED -> Outcome.NOT_SERVICED;
        }).increment();
        step.product().ifPresent(product -> {
            // One increment per item - a count, never a floating-point amount (INV-MON-01's rule over every call).
            Counter billed = billing(product, BillingOutcome.BILLED);
            for (int item = 0; item < step.billed(); item++) {
                billed.increment();
            }
            Counter applied = billing(product, BillingOutcome.CREDIT_APPLIED);
            for (int item = 0; item < step.appliedAtBilling(); item++) {
                applied.increment();
            }
        });
        if (step.caughtUp()) {
            servicing(Step.CATCH_UP, Outcome.APPLIED).increment();
        }
        if (step.closed()) {
            servicing(Step.CLOSURE, Outcome.CLOSED).increment();
        }
        int moved = 0;
        for (CollectionOutcome outcome : CollectionOutcome.values()) {
            int attempts = step.collected(outcome);
            Counter counter = collection(outcome);
            // One increment per attempt - a count, never a floating-point amount.
            for (int attempt = 0; attempt < attempts; attempt++) {
                counter.increment();
                if (outcome.failed()) {
                    repayments.record(BnplRepaymentMetrics.Kind.AUTO_COLLECTION,
                            BnplRepaymentMetrics.Outcome.INSUFFICIENT_FUNDS);
                }
            }
            if (outcome.collected()) {
                moved += attempts;
            }
        }
        // A collection that closed the obligation is the step's last money: counted closing, the others repaid. When no
        // collection moved money a closure was the step's own (after an application) and no repayment is counted.
        int closing = moved > 0 && step.closed() ? 1 : 0;
        for (int repayment = 0; repayment < moved - closing; repayment++) {
            repayments.record(BnplRepaymentMetrics.Kind.AUTO_COLLECTION, BnplRepaymentMetrics.Outcome.REPAID);
        }
        if (closing == 1) {
            repayments.record(BnplRepaymentMetrics.Kind.AUTO_COLLECTION, BnplRepaymentMetrics.Outcome.CLOSED);
        }
    }

    /** A step that failed - rolled back; its claim's cadence retries it. */
    public void failed() {
        servicing(Step.SERVICE, Outcome.FAILED).increment();
    }

    /**
     * A step that failed - and, when the failure is a refused conservation (`P12-TSK-019`: the step's applications and
     * collections allocate like a repayment - an {@link AllocationAnomaly} in the domain, or the database's deferred
     * conservation at commit), also one {@code finapp.bnpl.allocation.anomaly}, the alert the customer's door raises for
     * the same defect: a sweep's anomaly is never only a failed step.
     */
    public void failed(RuntimeException failure) {
        failed();
        if (failure instanceof AllocationAnomaly || BnplRepaymentDesk.refusedConservation(failure)) {
            repayments.anomaly();
        }
    }

    private Counter billing(BnplProduct product, BillingOutcome outcome) {
        return Counter.builder(BILLING)
                .description("BNPL instalments billed by the servicing step and credit-balance applications at them,"
                        + " by product")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }

    private Counter collection(CollectionOutcome outcome) {
        return Counter.builder(COLLECTION)
                .description("Automatic BNPL collection attempts from the wallet of committed servicing steps, by"
                        + " outcome - collected, partial, insufficient funds, wallet not postable, nothing due")
                .tags("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    private Counter servicing(Step step, Outcome outcome) {
        return Counter.builder(SERVICING)
                .description("BNPL servicing steps by outcome, and the catch-up applications they made")
                .tags("step", step.tag(), "outcome", outcome.tag())
                .register(registry);
    }
}
