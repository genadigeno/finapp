package com.finapp.payments;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One immutable version of the routing policy (`P7-TSK-003`, ADR-0060 §1): an ordered list of
 * {@link RoutingRule}s, effective forward, created by a named operator for a recorded reason.
 * Change creates a new version and re-routes nothing in flight — the fee schedule's
 * discipline ({@code INV-MER-03}'s shape), applied to `INV-HIST-04`'s third subject.
 *
 * <h2>{@link #decide} is pure, and that purity is the determinism test</h2>
 *
 * <p>The function reads its version's rules, the build's declared capabilities and the
 * availability observations handed to it — all stored or compiled facts — and returns a
 * {@link RoutingPlan}. No clock, no randomness, no I/O: recomputing the pinned version over
 * the stored inputs reproduces the stored plan, which is `INV-RAIL-02`'s verification and
 * the property the recomputation suite drives across versions.
 *
 * <p>Within a candidate, refusals are judged in ADR-0060 §3's own order — currency, ceiling,
 * instrument-model fit, destination, availability — and the first refusal is the recorded
 * one. The order is part of determinism and is pinned by test; changing it changes what
 * explanations say and is a reviewed decision.
 */
public final class RoutingPolicyVersion {

    public static final int FIRST_VERSION = 1;

    /** A rule as the operator states it — ids and indexes are this class's to mint. */
    public record NewRule(
            PaymentDirection direction,
            InstrumentKind instrumentKind,
            Optional<CurrencyCode> currency,
            Optional<Money> ceiling,
            List<RailId> rails) {}

    private final RoutingPolicyVersionId id;
    private final int version;
    private final List<RoutingRule> rules;
    private final Instant effectiveFrom;
    private final Instant createdAt;
    private final String createdBy;
    private final String reason;

    private RoutingPolicyVersion(
            RoutingPolicyVersionId id,
            int version,
            List<RoutingRule> rules,
            Instant effectiveFrom,
            Instant createdAt,
            String createdBy,
            String reason) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules must not be null"));
        this.effectiveFrom =
                Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");

        if (version < FIRST_VERSION) {
            throw new IllegalArgumentException("policy versions are numbered from 1");
        }
        this.version = version;
        if (this.rules.isEmpty()) {
            throw new IllegalArgumentException(
                    "a policy version carries at least one rule: a version that routes"
                            + " nothing refuses every payment, and a refusal of everything is"
                            + " an availability act, not a policy (ADR-0060)");
        }
        for (int index = 0; index < this.rules.size(); index++) {
            if (this.rules.get(index).ruleIndex() != index) {
                throw new IllegalArgumentException(
                        "rule indexes are contiguous from 0 in list order - position " + index
                                + " holds index " + this.rules.get(index).ruleIndex());
            }
        }
        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a policy version requires its reason (INV-AUD-03): changing how money"
                            + " travels is an operational judgement");
        }
        if (createdBy.isBlank()) {
            throw new IllegalArgumentException("a policy version records who created it");
        }
        // The keystone, the fee schedule's own (INV-MER-03's shape): a version cannot be
        // born already effective in the past, so no version can ever claim to have routed a
        // payment that was decided before it existed.
        if (effectiveFrom.isBefore(createdAt)) {
            throw new BackdatedRoutingPolicyVersionException(effectiveFrom, createdAt);
        }
    }

    /**
     * A new version, effective forward. An empty {@code effectiveFrom} means <strong>now</strong>
     * — the only way to say it, because any explicit instant an operator types is in the past
     * by the time it is judged (the fee schedule's recorded argument).
     */
    public static RoutingPolicyVersion create(
            IdGenerator ids,
            int version,
            List<NewRule> rules,
            Optional<Instant> effectiveFrom,
            String createdBy,
            String reason,
            Clock clock) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(rules, "rules must not be null");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        List<RoutingRule> built = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            NewRule rule = Objects.requireNonNull(rules.get(index), "a rule must not be null");
            built.add(
                    new RoutingRule(
                            RoutingRuleId.next(ids),
                            index,
                            rule.direction(),
                            rule.instrumentKind(),
                            rule.currency(),
                            rule.ceiling(),
                            rule.rails()));
        }
        return new RoutingPolicyVersion(
                RoutingPolicyVersionId.next(ids),
                version,
                built,
                effectiveFrom.orElse(now),
                now,
                createdBy,
                reason);
    }

    /** A version read back from storage, through the same constructor. */
    public static RoutingPolicyVersion rehydrate(
            RoutingPolicyVersionId id,
            int version,
            List<RoutingRule> rules,
            Instant effectiveFrom,
            Instant createdAt,
            String createdBy,
            String reason) {
        return new RoutingPolicyVersion(
                id, version, rules, effectiveFrom, createdAt, createdBy, reason);
    }

    /**
     * Judges this version over the payment's inputs — pure (see the class contract). The
     * first rule that matches supplies the candidates; the first eligible candidate is
     * chosen; every judged candidate becomes a step.
     */
    public RoutingPlan decide(
            RoutingInputs inputs,
            PaymentRails rails,
            Map<RailId, RailAvailability> availability) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Objects.requireNonNull(rails, "rails must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
        for (RoutingRule rule : rules) {
            if (!rule.matches(inputs)) {
                continue;
            }
            List<RoutingPlan.PlannedStep> steps = new ArrayList<>();
            for (RailId candidate : rule.rails()) {
                RailAvailability observation = availability.get(candidate);
                boolean available = observation == null || observation.available();
                Optional<PaymentRail> declared = rails.declared(candidate);
                if (declared.isEmpty()) {
                    steps.add(new RoutingPlan.PlannedStep(
                            candidate,
                            RoutingStepVerdict.REJECTED,
                            Optional.of(RoutingRejection.UNDECLARED_BY_BUILD),
                            available,
                            Optional.empty()));
                    continue;
                }
                Optional<Integer> descriptor = Optional.of(declared.get().declarationVersion());
                Optional<RoutingRejection> refusal =
                        firstRefusal(inputs, declared.get().capabilities(), available);
                if (refusal.isPresent()) {
                    steps.add(new RoutingPlan.PlannedStep(
                            candidate, RoutingStepVerdict.REJECTED, refusal, available,
                            descriptor));
                    continue;
                }
                steps.add(new RoutingPlan.PlannedStep(
                        candidate, RoutingStepVerdict.CHOSEN, Optional.empty(), available,
                        descriptor));
                return new RoutingPlan(Optional.of(rule.ruleIndex()), steps);
            }
            // The rule matched and every candidate was refused: the refusal is recorded
            // step by step, and the payment meets payments.NoEligibleRail (ADR-0060 section 3).
            return new RoutingPlan(Optional.of(rule.ruleIndex()), steps);
        }
        // No rule matches this payment's shape: refused with nothing to judge - the pinned
        // version and the recorded inputs are the whole explanation.
        return new RoutingPlan(Optional.empty(), List.of());
    }

    /** ADR-0060 §3's refusals, in its own order — the first one found is the recorded one. */
    private static Optional<RoutingRejection> firstRefusal(
            RoutingInputs inputs, RailCapabilities capabilities, boolean available) {
        if (capabilities.currencies().isPresent()
                && !capabilities.currencies().get().contains(inputs.currency())) {
            return Optional.of(RoutingRejection.CURRENCY_UNSUPPORTED);
        }
        Money maximum = capabilities.perCurrencyMaximum().get(inputs.currency());
        if (maximum != null) {
            if (maximum.scale() != inputs.amount().scale()) {
                throw new IllegalStateException(
                        "a declared ceiling for " + inputs.currency() + " is at scale "
                                + maximum.scale() + " but the payment is at scale "
                                + inputs.amount().scale()
                                + ": the declaration prices amounts the platform does not"
                                + " mint (P7-TSK-003)");
            }
            if (inputs.amount().compareTo(maximum) > 0) {
                return Optional.of(RoutingRejection.AMOUNT_EXCEEDS_CEILING);
            }
        }
        if (!inputs.instrumentKind().carriedBy(capabilities.interactionModel())) {
            return Optional.of(RoutingRejection.MODEL_CANNOT_CARRY_INSTRUMENT);
        }
        if (inputs.destinationReachable().map(reachable -> !reachable).orElse(false)) {
            return Optional.of(RoutingRejection.DESTINATION_UNREACHABLE);
        }
        if (!available) {
            return Optional.of(RoutingRejection.UNAVAILABLE);
        }
        return Optional.empty();
    }

    public RoutingPolicyVersionId id() {
        return id;
    }

    public int version() {
        return version;
    }

    /** The rules, in judgement order. */
    public List<RoutingRule> rules() {
        return rules;
    }

    public Instant effectiveFrom() {
        return effectiveFrom;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String createdBy() {
        return createdBy;
    }

    /** The operator's own words, recorded verbatim ({@code INV-AUD-03}). */
    public String reason() {
        return reason;
    }
}
