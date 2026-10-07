package com.finapp.credit;

import java.util.Objects;

/**
 * One input the decision engine may read (`P10-TSK-005`; PHASE_10_PLAN.md section 12.2): a code
 * from the closed vocabulary, a typed value and its provenance ({@code INV-CRD-07}).
 *
 * <p>The value must have the code's declared type ({@link CreditAttributeCode#valueType()}), or be
 * {@link AttributeValue.Absent} - a money attribute holding an integer is unconstructible. A
 * marker's value, when present, is a code naming the source kind it is about.
 *
 * <p>{@code toString} names the code alone: the value is {@code RESTRICTED-FINANCIAL}.
 *
 * @param code which attribute
 * @param value its value, or {@code Absent}
 * @param provenance where it came from
 */
public record CreditAttribute(CreditAttributeCode code, AttributeValue value, AttributeProvenance provenance) {

    public CreditAttribute {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
        if (!value.admits(code.valueType())) {
            throw new IllegalArgumentException(code + " holds a " + code.valueType() + " value");
        }
    }

    /** Whether the attribute's value is {@link AttributeValue.Absent}. */
    public boolean absent() {
        return value instanceof AttributeValue.Absent;
    }

    @Override
    public String toString() {
        return "CreditAttribute[" + code + "]";
    }
}
