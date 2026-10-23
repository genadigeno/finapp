package com.finapp.risk;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * The closed, ordered vocabulary of a risk decision's reasons (`P13-TSK-003`; {@code PHASE_13_PLAN.md} section
 * 12.3; ADR-0108 decision 4; {@code INV-RSK-04}) - never free text. Three sources:
 *
 * <ul>
 *   <li>{@link Source#RULE}: one member per {@link RuleKind}, named exactly as the kind - the code a fired rule gives
 *       its verdict, one per fired rule in evaluation order;</li>
 *   <li>{@link Source#FAIL_SAFE}: {@link #NO_POLICY_ACTIVE} and {@link #RISK_UNAVAILABLE} - the one reason of a
 *       recorded fail-safe verdict ({@link RiskEngineV1#failSafe});</li>
 *   <li>{@link Source#SEAM}: {@link #CHALLENGE_REQUIRED} - the reason a seam commits when a {@code CHALLENGE} is
 *       unsatisfied (`-004`, `-008`); never an engine's verdict's.</li>
 * </ul>
 *
 * <p>The member's name is the code - what every document, `-004`'s {@code risk_reason.code} and the event
 * {@code RiskDecisionMade} carry. That every kind has its code, and every rule code names a kind, is checked as the
 * class loads: a kind added without its code fails to load.
 */
public enum ReasonCode {
    NO_POLICY_ACTIVE(Source.FAIL_SAFE),
    RISK_UNAVAILABLE(Source.FAIL_SAFE),
    CHALLENGE_REQUIRED(Source.SEAM),
    AMOUNT_BAND(Source.RULE),
    VELOCITY_COUNT(Source.RULE),
    VELOCITY_SUM(Source.RULE),
    NEW_PAYEE(Source.RULE),
    NEW_DEVICE(Source.RULE),
    SESSION_ASSURANCE_BELOW(Source.RULE),
    RECENT_AUTH_FAILURES(Source.RULE),
    RECENT_CREDENTIAL_CHANGE(Source.RULE),
    RECENT_RECOVERY(Source.RULE),
    ACCOUNT_AGE_BELOW(Source.RULE),
    KYC_NOT_APPROVED(Source.RULE),
    SCREENING_HIT(Source.RULE),
    PROFILE_RATING(Source.RULE),
    MERCHANT_SUSPENDED(Source.RULE),
    CORRIDOR_HIGH_RISK(Source.RULE),
    DORMANT_THEN_ACTIVE(Source.RULE),
    RAPID_IN_OUT(Source.RULE),
    STRUCTURING(Source.RULE),
    ROUND_AMOUNT(Source.RULE),
    REFUND_RATIO(Source.RULE),
    CHARGEBACK_RATIO(Source.RULE),
    BNPL_MULTI_APPLICATION(Source.RULE);

    /** Where a reason comes from. */
    public enum Source {
        /** A fired rule of the policy version. */
        RULE,
        /** The recorded fail-safe. */
        FAIL_SAFE,
        /** The seam's own refusal reason, never the engine's. */
        SEAM
    }

    static {
        Set<String> kinds = EnumSet.allOf(RuleKind.class).stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());
        for (ReasonCode code : values()) {
            if ((code.source == Source.RULE) != kinds.contains(code.name())) {
                throw new IllegalStateException(code + ": a rule reason names a rule kind, and only a rule reason does");
            }
        }
        for (RuleKind kind : RuleKind.values()) {
            of(kind);
        }
    }

    private final Source source;

    ReasonCode(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public Source source() {
        return source;
    }

    /** The code - the member's name. */
    public String code() {
        return name();
    }

    /** The reason a fired rule of {@code kind} gives. */
    public static ReasonCode of(RuleKind kind) {
        Objects.requireNonNull(kind, "kind");
        try {
            return valueOf(kind.name());
        } catch (IllegalArgumentException missing) {
            throw new IllegalStateException(kind + " has no reason code: a kind is added with its code");
        }
    }
}
