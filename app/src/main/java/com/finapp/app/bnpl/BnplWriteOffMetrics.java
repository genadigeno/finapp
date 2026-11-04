package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Objects;

/**
 * {@code finapp.bnpl.write.off{product, outcome}} (`P14-TSK-021`): a write-off act counted once, after its transaction committed or
 * its refusal rolled back, per product: {@code proposed}, {@code approved}, {@code rejected}, or the act's own refusal. A replayed key, an unknown agreement or write-off, a missing
 * reason and a malformed request count nothing. Every series registered eagerly with its closed tags. {@code approved} above zero is a
 * financial event an operator expects to know of; {@code self_dealing_refused} above zero a signal for the security desk. Never an
 * amount, a party, an obligation or an operator (ADR-0072).
 */
public final class BnplWriteOffMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_write_off_total}. */
    public static final String WRITE_OFF = "finapp.bnpl.write.off";

    /** The closed set of outcomes. */
    public enum Outcome {
        PROPOSED,
        APPROVED,
        REJECTED,
        NOT_DEFAULTED,
        NOT_IN_SERVICING,
        STALE,
        CREDIT_HELD,
        BEHIND_ACCRUAL,
        DISPUTE_OPEN,
        SELF_APPROVAL_REFUSED,
        SELF_DEALING_REFUSED,
        PROPOSAL_PENDING,
        PROPOSAL_NOT_PENDING;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;

    public BnplWriteOffMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            for (BnplProduct product : BnplProduct.values()) {
                counter(tag(product), outcome);
            }
        }
    }

    /** One act on an obligation of {@code product}, with its outcome. */
    public void record(BnplProduct product, Outcome outcome) {
        counter(tag(Objects.requireNonNull(product, "product")), outcome).increment();
    }

    static String tag(BnplProduct product) {
        return product.name().toLowerCase(Locale.ROOT);
    }

    private Counter counter(String product, Outcome outcome) {
        return Counter.builder(WRITE_OFF)
                .description("BNPL write-off acts - proposed, approved, rejected, and refusals by reason - by product and outcome")
                .tags("product", product, "outcome", outcome.tag())
                .register(registry);
    }
}
