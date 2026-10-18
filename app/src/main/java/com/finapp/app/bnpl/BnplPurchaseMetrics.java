package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * The BNPL purchase's meter (`P12-TSK-013`; {@code PHASE_12_PLAN.md} section 15): {@code finapp.bnpl.purchase{product,
 * plan, outcome}} - every acceptance's outcome, the plan mix of what was financed and why the rest was refused. Every
 * series registered eagerly (`P1-TSK-029`'s rule), counted after the transaction commits or rolls back - never inside it,
 * so a rolled-back purchase is never counted financed; a replayed key counts nothing. Tags are closed sets: no amount,
 * party, merchant, purchase or agreement ever ({@code INV-AUD-02}).
 */
public final class BnplPurchaseMetrics {

    /** Exported as {@code finapp_bnpl_purchase_total}. */
    public static final String PURCHASE = "finapp.bnpl.purchase";

    /** The closed set of acceptance outcomes. */
    public enum Outcome {
        FINANCED,
        NOT_ACCEPTABLE,
        TERMS_CHANGED,
        SESSION_NOT_PAYABLE,
        MERCHANT_NOT_ENABLED,
        CAPITAL_UNAVAILABLE,
        INSUFFICIENT_FUNDS;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplPurchaseMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            for (BnplPlan plan : product.plans()) {
                for (Outcome outcome : Outcome.values()) {
                    counter(product, plan, outcome);
                }
            }
        }
    }

    /** One acceptance of an offer of {@code plan}, with its outcome. */
    public void record(BnplProduct product, BnplPlan plan, Outcome outcome) {
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(outcome, "outcome");
        if (plan.product() != product) {
            throw new IllegalArgumentException("a plan is counted under its own product");
        }
        counter(product, plan, outcome).increment();
    }

    private Counter counter(BnplProduct product, BnplPlan plan, Outcome outcome) {
        return Counter.builder(PURCHASE)
                .description("BNPL acceptances - financed purchases and refusals - by product, plan and outcome; a"
                        + " replayed key counts nothing. The financed series is the plan mix")
                .tags("product", product.name(), "plan", plan.name(), "outcome", outcome.tag())
                .register(registry);
    }
}
