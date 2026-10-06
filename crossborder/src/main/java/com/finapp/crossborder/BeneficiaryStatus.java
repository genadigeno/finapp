package com.finapp.crossborder;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The cross-border beneficiary's machine (`P9-TSK-017`, ADR-0080 section 3, the lifecycle document
 * section 3.7): {@code PENDING_SCREENING -> ACTIVE | IN_REVIEW}, {@code IN_REVIEW -> ACTIVE | BLOCKED},
 * {@code ACTIVE -> IN_REVIEW}, and the customer's revocation from every non-terminal state.
 * {@code REVOKED} is terminal.
 */
public enum BeneficiaryStatus {
    PENDING_SCREENING,
    ACTIVE,
    IN_REVIEW,
    BLOCKED,
    REVOKED;

    /** Whether the machine has the edge {@code this -> target}. */
    public boolean canMoveTo(BeneficiaryStatus target) {
        return switch (this) {
            case PENDING_SCREENING -> target == ACTIVE || target == IN_REVIEW || target == REVOKED;
            case IN_REVIEW -> target == ACTIVE || target == BLOCKED || target == REVOKED;
            case ACTIVE -> target == IN_REVIEW || target == REVOKED;
            case BLOCKED -> target == REVOKED;
            case REVOKED -> false;
        };
    }

    /**
     * What a customer is shown - shaped for tipping-off: screening and review are both
     * {@code PENDING_VERIFICATION}, and a block is {@code UNAVAILABLE}.
     */
    public String shaped() {
        return switch (this) {
            case PENDING_SCREENING, IN_REVIEW -> "PENDING_VERIFICATION";
            case BLOCKED -> "UNAVAILABLE";
            case ACTIVE -> "ACTIVE";
            case REVOKED -> "REVOKED";
        };
    }

    /** The values as a SQL literal list - {@code crossborder V003}'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
