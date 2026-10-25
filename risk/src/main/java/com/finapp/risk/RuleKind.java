package com.finapp.risk;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The kinds of rule a risk policy version may carry (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 12.2; ADR-0109) -
 * <strong>code, never configuration</strong>: a kind is an evaluation the engine knows how to perform; a policy version
 * holds rows of these kinds with typed parameters, a weight and/or a hard decision, and the policy door refuses a
 * parameter outside its kind's schema ({@code risk.PolicyInvalid}, `-002`). The engine (`-003`) evaluates a row by its
 * kind and parameters alone, deterministically.
 *
 * <p><strong>Each kind declares the operations it may be written for</strong> ({@link #operations}) and its parameter
 * schema ({@link #parameters}) - the names and types a policy row must supply, nothing more. The five judged operations
 * share the transactional kinds; the monitoring kinds are the sweep's alone; a kind written for an operation it does not
 * declare is refused at the door. The twenty-two the plan names, no speculative member; a kind added here is a reviewed
 * change to the engine and to {@code RiskVocabulariesTest}.
 */
public enum RuleKind {

    /** The judged amount at or above a threshold. */
    AMOUNT_BAND(Scope.JUDGED, Parameter.of("threshold_minor", ParameterType.MINOR_AMOUNT)),

    /** The subject's movement count in a window at or above a threshold (the durable velocity windows, `-006`). */
    VELOCITY_COUNT(Scope.JUDGED_AND_MONITORING,
            Parameter.of("dimension", ParameterType.VELOCITY_DIMENSION),
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("threshold_count", ParameterType.COUNT)),

    /** The subject's movement sum in a window at or above a threshold. */
    VELOCITY_SUM(Scope.JUDGED_AND_MONITORING,
            Parameter.of("dimension", ParameterType.VELOCITY_DIMENSION),
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("threshold_minor", ParameterType.MINOR_AMOUNT)),

    /** The destination account or beneficiary was first seen within the lookback. */
    NEW_PAYEE(Scope.JUDGED, Parameter.of("lookback_days", ParameterType.DAYS)),

    /** The device hash was first seen within the lookback (only identity's hash is ever received, OQ-4). */
    NEW_DEVICE(Scope.JUDGED, Parameter.of("lookback_days", ParameterType.DAYS)),

    /** The acting session's assurance is below the named level. */
    SESSION_ASSURANCE_BELOW(Scope.JUDGED, Parameter.of("assurance", ParameterType.ASSURANCE)),

    /** Authentication failures on the identity in a window at or above a threshold. */
    RECENT_AUTH_FAILURES(Scope.JUDGED,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("threshold_count", ParameterType.COUNT)),

    /**
     * A credential changed within the lookback - an account-takeover sequence's anchor ({@link AtoSequence}). Admitted
     * for {@code MONITORING} too (`P13-TSK-013`): the profile leg reads it over the party's identities and raises the
     * retrospective {@code ACCOUNT_TAKEOVER} alert when it fires.
     */
    RECENT_CREDENTIAL_CHANGE(Scope.JUDGED_AND_MONITORING, Parameter.of("lookback_hours", ParameterType.HOURS)),

    /** An account recovery completed within the lookback - the other anchor; judged and monitored alike (`-013`). */
    RECENT_RECOVERY(Scope.JUDGED_AND_MONITORING, Parameter.of("lookback_hours", ParameterType.HOURS)),

    /** The account is younger than the named age. */
    ACCOUNT_AGE_BELOW(Scope.JUDGED, Parameter.of("days", ParameterType.DAYS)),

    /** The party's KYC is not approved. */
    KYC_NOT_APPROVED(Scope.JUDGED),

    /** A screening clearance recorded a hit on the party or a counterparty. */
    SCREENING_HIT(Scope.JUDGED_AND_MONITORING),

    /** The customer risk profile's rating is at or above the named rating. */
    PROFILE_RATING(Scope.JUDGED, Parameter.of("rating", ParameterType.RATING)),

    /** The merchant is suspended. */
    MERCHANT_SUSPENDED(Scope.PAYMENTS),

    /** The corridor is in the named high-risk set. */
    CORRIDOR_HIGH_RISK(Scope.CROSS_BORDER_AND_MONITORING, Parameter.of("corridors", ParameterType.CORRIDOR_SET)),

    /** Dormant for the named days, then at or above the named sum within the window (`-020`). */
    DORMANT_THEN_ACTIVE(Scope.MONITORING,
            Parameter.of("dormant_days", ParameterType.DAYS),
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("threshold_minor", ParameterType.MINOR_AMOUNT)),

    /** At or above the named share of an inflow leaves again within the window. */
    RAPID_IN_OUT(Scope.MONITORING,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("ratio_bps", ParameterType.BASIS_POINTS)),

    /** At or above the named count of movements each below one amount, summing above another, within the window. */
    STRUCTURING(Scope.MONITORING,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("below_minor", ParameterType.MINOR_AMOUNT),
            Parameter.of("threshold_count", ParameterType.COUNT),
            Parameter.of("sum_minor", ParameterType.MINOR_AMOUNT)),

    /** The amount is a multiple of the named modulus. */
    ROUND_AMOUNT(Scope.JUDGED_AND_MONITORING, Parameter.of("modulus_minor", ParameterType.MINOR_AMOUNT)),

    /**
     * A merchant's or customer's refund ratio in a window at or above the named share, over at least the named count of
     * refunds (`P13-TSK-014`). <strong>Whose ratio is the operation's</strong>: in a {@code CARD_PAYMENT} or
     * {@code WALLET_PAYMENT} version the <em>merchant</em> the payment pays (a merchant has no profile - its payments
     * are where it is judged, T-eval); in the {@code MONITORING} version the <em>party</em> the profile is (the
     * retrospective leg). The share is the subject's {@code REFUND_COMPLETED} signals in the window's bucket over its
     * velocity window count for the same bucket, compared in basis points by cross-multiplication; a zero denominator
     * seals nothing ({@code ABSENT}). {@code threshold_count} is the schemes' count condition - a ratio over a handful
     * of payments is noise, and every tumbling bucket begins with a handful. Widened to the payments by `-014` (scope
     * only - the database has no (operation, kind) {@code CHECK}, `-013`'s precedent).
     */
    REFUND_RATIO(Scope.PAYMENTS_AND_MONITORING,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("ratio_bps", ParameterType.BASIS_POINTS),
            Parameter.of("threshold_count", ParameterType.COUNT)),

    /**
     * A merchant's or customer's chargeback ratio in a window at or above the named share, over at least the named count
     * of chargebacks - {@link #REFUND_RATIO}'s shape over {@code CHARGEBACK_RECEIVED} signals (`P13-TSK-014`).
     */
    CHARGEBACK_RATIO(Scope.PAYMENTS_AND_MONITORING,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("ratio_bps", ParameterType.BASIS_POINTS),
            Parameter.of("threshold_count", ParameterType.COUNT)),

    /**
     * A party's BNPL applications within the window at or above a count - whatever the merchants, the applications
     * across them (`P13-TSK-014`): judged retrospectively only ({@code MONITORING}), so it moves the party's rating and
     * so credit's signal (BNPL is judged through credit, plan section 12.1) - never a seam.
     */
    BNPL_MULTI_APPLICATION(Scope.MONITORING,
            Parameter.of("window", ParameterType.WINDOW),
            Parameter.of("threshold_count", ParameterType.COUNT));

    /** The type a rule parameter's value must have - what the policy door validates and the engine reads. */
    public enum ParameterType {
        /** A non-negative count. */
        COUNT,
        /** An amount in minor units of the policy's currency, non-negative. */
        MINOR_AMOUNT,
        /** A share in basis points, 0 to 10000. */
        BASIS_POINTS,
        /** A number of days, positive. */
        DAYS,
        /** A number of hours, positive. */
        HOURS,
        /** A {@link WindowKind}. */
        WINDOW,
        /**
         * A {@link VelocityDimension} - the role a velocity rule counts per (`P13-TSK-006`, the coordinator's decision at
         * wave C; it replaced {@code SUBJECT_KIND}, which no kind used afterwards and {@code risk V005} dropped).
         */
        VELOCITY_DIMENSION,
        /** A set of corridor codes. */
        CORRIDOR_SET,
        /** A {@link RiskAssurance}. */
        ASSURANCE,
        /** A {@link RiskRating}. */
        RATING
    }

    /** One named, typed parameter a kind requires. */
    public record Parameter(String name, ParameterType type) {
        public Parameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
            if (!name.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("a parameter name is lower snake case: " + name);
            }
        }

        static Parameter of(String name, ParameterType type) {
            return new Parameter(name, type);
        }
    }

    /** Which operations a kind may be written for. */
    private enum Scope {
        JUDGED(EnumSet.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CARD_PAYMENT,
                RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER)),
        JUDGED_AND_MONITORING(EnumSet.of(RiskOperation.TRANSFER, RiskOperation.WITHDRAWAL, RiskOperation.CARD_PAYMENT,
                RiskOperation.WALLET_PAYMENT, RiskOperation.CROSS_BORDER, RiskOperation.MONITORING)),
        PAYMENTS(EnumSet.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT)),
        PAYMENTS_AND_MONITORING(EnumSet.of(RiskOperation.CARD_PAYMENT, RiskOperation.WALLET_PAYMENT,
                RiskOperation.MONITORING)),
        CROSS_BORDER_AND_MONITORING(EnumSet.of(RiskOperation.CROSS_BORDER, RiskOperation.MONITORING)),
        MONITORING(EnumSet.of(RiskOperation.MONITORING));

        private final Set<RiskOperation> operations;

        Scope(Set<RiskOperation> operations) {
            this.operations = Set.copyOf(operations);
        }
    }

    private final Scope scope;
    private final List<Parameter> parameters;

    RuleKind(Scope scope, Parameter... parameters) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.parameters = List.of(parameters);
        long distinct = this.parameters.stream().map(Parameter::name).distinct().count();
        if (distinct != this.parameters.size()) {
            throw new IllegalArgumentException(name() + ": a parameter is named once");
        }
    }

    static {
        for (RuleKind kind : values()) {
            if (kind.operations().isEmpty()) {
                throw new IllegalStateException(kind + ": a rule kind is written for at least one operation");
            }
            if (kind.operations().contains(RiskOperation.CREDIT_SIGNAL)) {
                throw new IllegalStateException(kind + ": the credit signal carries no rule - it answers a rating");
            }
        }
    }

    /** The operations a policy version may carry this kind for. Immutable. */
    public Set<RiskOperation> operations() {
        return scope.operations;
    }

    /** The parameters a policy row of this kind must supply, in declaration order. Immutable; possibly empty. */
    public List<Parameter> parameters() {
        return parameters;
    }

    /** True when this kind may be written for {@code operation}. */
    public boolean appliesTo(RiskOperation operation) {
        return scope.operations.contains(Objects.requireNonNull(operation, "operation"));
    }
}
