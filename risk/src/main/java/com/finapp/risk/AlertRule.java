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
 * policy rule carries. A closed vocabulary - the code is part of the alert's identity (subject, rule, window start), so
 * it is never a parameter, a free name or a rule's ordinal ({@code INV-RSK-08}: a rule's parameters never leave the
 * policy).
 */
public record AlertRule(String code) {

    public AlertRule {
        Objects.requireNonNull(code, "code");
        if (!codes().contains(code)) {
            throw new IllegalArgumentException("an alert's rule is a rule kind or an operational alert kind");
        }
    }

    /** The rule of a policy's rule kind. */
    public static AlertRule of(RuleKind kind) {
        return new AlertRule(Objects.requireNonNull(kind, "kind").name());
    }

    /** The rule of an operational alert kind - its own code. */
    public static AlertRule operational(AlertKind kind) {
        Objects.requireNonNull(kind, "kind");
        if (!kind.operational()) {
            throw new IllegalArgumentException(kind + " is raised by a policy's rule, not operationally");
        }
        return new AlertRule(kind.name());
    }

    /** The rule named {@code code}, or empty outside the vocabulary. */
    public static Optional<AlertRule> named(String code) {
        return code != null && codes().contains(code) ? Optional.of(new AlertRule(code)) : Optional.empty();
    }

    /** Every code an alert may carry: the rule kinds in declared order, then the operational kinds. */
    public static List<String> codes() {
        List<String> codes = new ArrayList<>();
        Arrays.stream(RuleKind.values()).map(Enum::name).forEach(codes::add);
        Arrays.stream(AlertKind.values()).filter(AlertKind::operational).map(Enum::name).forEach(codes::add);
        return List.copyOf(codes);
    }

    /** True for an operational kind's own code. */
    public boolean operational() {
        return Arrays.stream(AlertKind.values()).anyMatch(kind -> kind.operational() && kind.name().equals(code));
    }

    /** The rule kind this code names - empty for an operational code. */
    public Optional<RuleKind> ruleKind() {
        return operational() ? Optional.empty() : Optional.of(RuleKind.valueOf(code));
    }

    @Override
    public String toString() {
        return code;
    }
}
