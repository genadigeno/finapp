package com.finapp.risk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rule an alert speaks for - its {@code rule_code} (`P13-TSK-015`; {@code risk V010}'s
 * {@code risk_alert_rule_code_is_known}): a {@link RuleKind}'s name for an alert a policy's rule raised, or for an
 * operational {@link AlertKind} its own name ({@code UNCLASSIFIED_TRANSACTION}, {@code VELOCITY_DIVERGENCE}), which no
 * policy rule carries - and since `P13-TSK-025` ({@code risk V017}) a fact kind's own name
 * ({@code OBLIGATION_DEFAULTED}; since `P13-TSK-022`, {@code risk V018}, {@code SCREENING_HIT} - a name it shares
 * with a rule kind, which therefore raises no alert under it). A closed vocabulary - the code is part of the
 * alert's identity (subject, rule, window start), so it is never a parameter, a free name or a rule's ordinal
 * ({@code INV-RSK-08}: a rule's parameters never leave the policy).
 */
public record AlertRule(String code) {

    public AlertRule {
        Objects.requireNonNull(code, "code");
        if (!codes().contains(code)) {
            throw new IllegalArgumentException("an alert's rule is a rule kind or an operational alert kind");
        }
    }

    /** The rule of a policy's rule kind - never a name a fact owns ({@code SCREENING_HIT} since {@code risk V018}). */
    public static AlertRule of(RuleKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (Arrays.stream(AlertKind.values()).anyMatch(own -> own.ownCode() && own.name().equals(kind.name()))) {
            // `P13-TSK-022`: SCREENING_HIT is the rescreening fact's own code since risk V018 - no policy rule
            // raises an alert under it, or one key would mean two things.
            throw new IllegalArgumentException(kind + " is a fact's own code: no policy rule raises an alert under it");
        }
        return new AlertRule(kind.name());
    }

    /** The rule of an operational alert kind - its own code. */
    public static AlertRule operational(AlertKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.operational()) {
            throw new IllegalArgumentException(kind + " is raised by a policy's rule, not operationally");
        }
        return new AlertRule(kind.name());
    }

    /** The rule of a fact kind - its own code (`P13-TSK-025`: {@code OBLIGATION_DEFAULTED}). */
    public static AlertRule fact(AlertKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.fact()) {
            throw new IllegalArgumentException(kind + " is not a fact's alert");
        }
        return new AlertRule(kind.name());
    }

    /** The rule named {@code code}, or empty outside the vocabulary. */
    public static Optional<AlertRule> named(String code) {
        return code != null && codes().contains(code) ? Optional.of(new AlertRule(code)) : Optional.empty();
    }

    /**
     * Every code an alert may carry: the rule kinds in declared order, then the own-code kinds - the operational ones and
     * the facts - in {@link AlertKind}'s order; a name in both ({@code SCREENING_HIT}) once, at its rule kind's place.
     */
    public static List<String> codes() {
        List<String> codes = new ArrayList<>();
        Arrays.stream(RuleKind.values()).map(Enum::name).forEach(codes::add);
        Arrays.stream(AlertKind.values()).filter(AlertKind::ownCode).map(Enum::name).forEach(codes::add);
        // Once each: SCREENING_HIT is both a rule kind's name and, since risk V018, a fact's own code - one code.
        return codes.stream().distinct().toList();
    }

    /** True for an operational kind's own code. */
    public boolean operational() {
        return Arrays.stream(AlertKind.values()).anyMatch(kind -> kind.operational() && kind.name().equals(code));
    }

    /** True for an own-code kind's code - an operational kind's or a fact's: no policy rule carries it. */
    public boolean ownCode() {
        return Arrays.stream(AlertKind.values()).anyMatch(kind -> kind.ownCode() && kind.name().equals(code));
    }

    /** The rule kind this code names - empty for an own code (an operational kind's or a fact's). */
    public Optional<RuleKind> ruleKind() {
        return ownCode() ? Optional.empty() : Optional.of(RuleKind.valueOf(code));
    }

    @Override
    public String toString() {
        return code;
    }
}
