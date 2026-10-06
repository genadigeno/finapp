package com.finapp.payments;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One rule of a routing policy version (`P7-TSK-003`, ADR-0060 §1): its matchers — direction,
 * instrument kind, optionally a currency, optionally a ceiling — and its ordered candidate
 * rails. Rules are judged in {@code ruleIndex} order and the first match wins; a rule list is
 * therefore also how bands are expressed downward ("up to 1000 on the instant rail, the rest
 * on cards" is two rules), which is why the ceiling is one-sided: a lower bound would say
 * nothing an ordered list cannot already say, and was left out on that argument.
 *
 * <h2>The ceiling binds only with a currency, at the version's own construction</h2>
 *
 * <p>An amount bound without a currency is not a number ({@code INV-MON}); construction
 * refuses a ceiling on an any-currency rule, and a ceiling priced in a different currency
 * than the rule matches. A matched payment whose scale differs from the ceiling's is a
 * configuration written against amounts the platform does not mint — refused loudly at
 * decision time as a wiring fault, never silently unbounded.
 *
 * <p>A class rather than a record: the ceiling is money, and a generated {@code toString}
 * would print it ({@code INV-AUD-02}'s logging half — the {@code PaymentIntent} form).
 */
public final class RoutingRule {

    private final RoutingRuleId id;
    private final int ruleIndex;
    private final PaymentDirection direction;
    private final InstrumentKind instrumentKind;
    private final Optional<CurrencyCode> currency;
    private final Optional<Money> ceiling;
    private final List<RailId> rails;
    private final boolean requiresDestinationCountry;

    public RoutingRule(
            RoutingRuleId id,
            int ruleIndex,
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Optional<CurrencyCode> currency,
            Optional<Money> ceiling,
            List<RailId> rails) {
        this(id, ruleIndex, direction, instrumentKind, currency, ceiling, rails, false);
    }

    /**
     * A rule that, with {@code requiresDestinationCountry}, matches only a payment that names a destination
     * country (`P9-TSK-019`, ADR-0080 section 5b) - routing policy v5's cross-border rule. Default {@code false}:
     * every older rule matches exactly as before.
     */
    public RoutingRule(
            RoutingRuleId id,
            int ruleIndex,
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Optional<CurrencyCode> currency,
            Optional<Money> ceiling,
            List<RailId> rails,
            boolean requiresDestinationCountry) {
        this.requiresDestinationCountry = requiresDestinationCountry;
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.instrumentKind =
                Objects.requireNonNull(instrumentKind, "instrumentKind must not be null");
        this.currency = Objects.requireNonNull(
                currency, "currency must not be null - any currency is empty");
        this.ceiling = Objects.requireNonNull(
                ceiling, "ceiling must not be null - unbounded is empty");
        Objects.requireNonNull(rails, "rails must not be null");

        if (ruleIndex < 0) {
            throw new IllegalArgumentException("a rule index is zero-based and never negative");
        }
        this.ruleIndex = ruleIndex;
        if (rails.isEmpty()) {
            throw new IllegalArgumentException(
                    "a routing rule names at least one candidate rail: a rule that can only"
                            + " refuse is a refusal, not a route (ADR-0060 section 1)");
        }
        if (rails.stream().distinct().count() != rails.size()) {
            throw new IllegalArgumentException(
                    "a routing rule's candidates are distinct: naming a rail twice would make"
                            + " the recorded order ambiguous");
        }
        this.rails = List.copyOf(rails);
        if (ceiling.isPresent() && currency.isEmpty()) {
            throw new IllegalArgumentException(
                    "a ceiling without a currency is not a number (INV-MON): an any-currency"
                            + " rule cannot carry an amount bound");
        }
        if (ceiling.isPresent() && !ceiling.get().currency().equals(currency.get())) {
            throw new IllegalArgumentException(
                    "a rule's ceiling is priced in the currency the rule matches - "
                            + ceiling.get().currency() + " against " + currency.get()
                            + " is incoherent");
        }
        if (ceiling.isPresent() && !ceiling.get().isPositive()) {
            throw new IllegalArgumentException(
                    "a non-positive ceiling matches nothing and routes nothing - refused as"
                            + " the misconfiguration it is");
        }
    }

    /** Whether this rule matches the payment — pure, over the recorded inputs alone. */
    boolean matches(RoutingInputs inputs) {
        if (direction != inputs.direction() || instrumentKind != inputs.instrumentKind()) {
            return false;
        }
        if (currency.isPresent() && !currency.get().equals(inputs.currency())) {
            return false;
        }
        if (requiresDestinationCountry && inputs.destinationCountry().isEmpty()) {
            return false;
        }
        if (ceiling.isPresent()) {
            Money bound = ceiling.get();
            if (bound.scale() != inputs.amount().scale()) {
                // A policy priced at a scale the platform does not mint: a configuration
                // fault, loud at the first payment it would misjudge - never silently
                // unbounded and never silently unmatched.
                throw new IllegalStateException(
                        "routing rule " + ruleIndex + " bounds " + currency.get()
                                + " at scale " + bound.scale() + " but the payment is at scale "
                                + inputs.amount().scale()
                                + ": the policy is priced against amounts the platform does"
                                + " not mint (P7-TSK-003)");
            }
            if (inputs.amount().compareTo(bound) > 0) {
                return false;
            }
        }
        return true;
    }

    public RoutingRuleId id() {
        return id;
    }

    public int ruleIndex() {
        return ruleIndex;
    }

    public PaymentDirection direction() {
        return direction;
    }

    public InstrumentKind instrumentKind() {
        return instrumentKind;
    }

    /** The currency this rule matches, or empty for any. */
    public Optional<CurrencyCode> currency() {
        return currency;
    }

    /** The inclusive upper bound this rule matches, or empty for unbounded. */
    public Optional<Money> ceiling() {
        return ceiling;
    }

    /** The candidate rails, in preference order — judged first to last (ADR-0060 §3). */
    public List<RailId> rails() {
        return rails;
    }

    public boolean requiresDestinationCountry() {
        return requiresDestinationCountry;
    }
}
