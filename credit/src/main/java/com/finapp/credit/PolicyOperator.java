package com.finapp.credit;

/**
 * A rule's comparison (ADR-0086 section 1) - closed. A new operator is a reviewed code change with a new engine
 * version ({@code INV-CRD-01}), never an expression.
 */
public enum PolicyOperator {
    LT,
    LE,
    GT,
    GE,
    EQ,
    NE,
    IN,
    NOT_IN,
    IS_ABSENT,
    IS_PRESENT;

    /** Whether the operator orders numbers - an integer or money of the product's currency. */
    public boolean ordering() {
        return this == LT || this == LE || this == GT || this == GE;
    }

    /** Whether the operator asks only whether a value exists - it takes no operand. */
    public boolean presence() {
        return this == IS_ABSENT || this == IS_PRESENT;
    }

    /** Whether the operator tests membership of a code set. */
    public boolean membership() {
        return this == IN || this == NOT_IN;
    }
}
