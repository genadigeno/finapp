package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * The origination's meters (`P12-TSK-011`, PHASE_12_PLAN.md section 15; lending's {@code LoanOfferMetrics}), each counted
 * once after its transaction committed and registered eagerly for every product and outcome, so a fresh instance
 * publishes every series at zero. Never an amount, a party, a session, an application or an offer id (ADR-0072).
 *
 * <ul>
 *   <li>{@code finapp.bnpl.offer{product, outcome}} - every origination step that moved an application (the offer born,
 *       credit's decline, the application closed undecided), every expiry and every customer decline, and the decline
 *       refused. A step that found nothing to do, and a replayed key, count nothing.</li>
 *   <li>{@code finapp.bnpl.eligibility{product, outcome}} - the merchant's one signal as it was emitted: {@code eligible}
 *       for an offer, {@code not_eligible} for a decline or an approval below the purchase - the approval rate.</li>
 *   <li>{@code finapp.bnpl.origination{kind, outcome=failed}} - a sweep step that failed and rolled back (its permit
 *       lapses, a later tick retries), by what it acted on; without it a step failing on every tick is visible to
 *       nobody (the Phase 11 gate's A5-03, applied from the first BNPL sweep).</li>
 * </ul>
 */
public final class BnplOriginationMetrics {

    /** Exported as {@code finapp_bnpl_offer_total}. */
    public static final String OFFER = "finapp.bnpl.offer";

    /** Exported as {@code finapp_bnpl_eligibility_total}. */
    public static final String ELIGIBILITY = "finapp.bnpl.eligibility";

    /** Exported as {@code finapp_bnpl_origination_total}. */
    public static final String ORIGINATION = "finapp.bnpl.origination";

    /** What a failed sweep step was acting on. */
    public enum Step {
        APPLICATION,
        OFFER;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The closed set of offer outcomes. */
    public enum Outcome {
        OFFERED,
        APPLICATION_DECLINED,
        APPLICATION_UNDECIDED,
        DECLINED,
        EXPIRED,
        NOT_ACCEPTABLE;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplOriginationMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            for (Outcome outcome : Outcome.values()) {
                offer(product, outcome);
            }
            for (BnplOrigination.Eligibility eligibility : BnplOrigination.Eligibility.values()) {
                eligibility(product, eligibility);
            }
        }
        for (Step step : Step.values()) {
            failures(step);
        }
    }

    /** One committed origination step - counted only when it moved the application, with its eligibility signal. */
    public void record(BnplOrigination.Outcome step) {
        Objects.requireNonNull(step, "step");
        Outcome outcome = switch (step.kind()) {
            case OFFERED -> Outcome.OFFERED;
            case DECLINED -> Outcome.APPLICATION_DECLINED;
            case CLOSED_UNDECIDED -> Outcome.APPLICATION_UNDECIDED;
            case NOTHING, WAITING -> null;
        };
        if (outcome == null) {
            return;
        }
        BnplProduct product = step.product().orElseThrow(() -> new IllegalStateException("a moved step names its product"));
        record(product, outcome);
        step.eligibility().ifPresent(eligibility -> eligibility(product, eligibility).increment());
    }

    /** One act on an offer of {@code product}, with its outcome. */
    public void record(BnplProduct product, Outcome outcome) {
        offer(product, outcome).increment();
    }

    /** One sweep step that failed and rolled back. */
    public void failed(Step step) {
        failures(Objects.requireNonNull(step, "step")).increment();
    }

    private Counter offer(BnplProduct product, Outcome outcome) {
        return Counter.builder(OFFER)
                .description("BNPL origination steps that moved an application, offer expiries and customer declines -"
                        + " by product and outcome; a step with nothing to do and a replayed key count nothing")
                .tags("product", product.name(), "outcome", outcome.tag())
                .register(registry);
    }

    private Counter eligibility(BnplProduct product, BnplOrigination.Eligibility eligibility) {
        return Counter.builder(ELIGIBILITY)
                .description("The BNPL eligibility signal the merchant receives - eligible (offered) or not_eligible"
                        + " (declined, or approved below the purchase) - by product: the approval rate")
                .tags("product", product.name(), "outcome", eligibility.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    private Counter failures(Step step) {
        return Counter.builder(ORIGINATION)
                .description("BNPL origination sweep steps that failed and rolled back - an application's step or an"
                        + " offer's expiry - by kind; each retried by a later tick. Alert on any sustained increase")
                .tags("kind", step.tag(), "outcome", "failed")
                .register(registry);
    }
}
