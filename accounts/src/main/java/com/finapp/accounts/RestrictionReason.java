package com.finapp.accounts;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why an agreement is suspended or reinstated (`P17-TSK-028`, ADR-0153 decision 4): a closed vocabulary, each code belonging
 * to one {@link RestrictionKind}, beside a person's free-text reason. The code is what reports count; the text is what an
 * auditor reads. V003's {@code CHECK} pairs each code with its kind, generated from {@link #sqlPairs()}.
 */
public enum RestrictionReason {

    FRAUD_SUSPECTED(RestrictionKind.SUSPENSION),
    ACCOUNT_TAKEOVER_SUSPECTED(RestrictionKind.SUSPENSION),
    AML_INVESTIGATION(RestrictionKind.SUSPENSION),
    LEGAL_ORDER(RestrictionKind.SUSPENSION),
    CUSTOMER_REQUEST(RestrictionKind.SUSPENSION),

    INVESTIGATION_CLEARED(RestrictionKind.REINSTATEMENT),
    LEGAL_ORDER_LIFTED(RestrictionKind.REINSTATEMENT),
    CUSTOMER_REQUESTED_REINSTATEMENT(RestrictionKind.REINSTATEMENT),
    RAISED_IN_ERROR(RestrictionKind.REINSTATEMENT);

    private final RestrictionKind kind;

    RestrictionReason(RestrictionKind kind) {
        this.kind = kind;
    }

    /** The one kind this code explains. */
    public RestrictionKind kind() {
        return kind;
    }

    /** The {@code (kind = …, reason_code IN (…))} disjunction V003's {@code CHECK} is, one term per kind. */
    public static String sqlPairs() {
        return Arrays.stream(RestrictionKind.values())
                .map(kind -> "(kind = '" + kind.name() + "' AND reason_code IN (" + Arrays.stream(values())
                        .filter(reason -> reason.kind == kind)
                        .map(reason -> "'" + reason.name() + "'")
                        .collect(Collectors.joining(", ")) + "))")
                .collect(Collectors.joining(" OR "));
    }
}
