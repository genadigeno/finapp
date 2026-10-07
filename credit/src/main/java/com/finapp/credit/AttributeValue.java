package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * A credit attribute's value (`P10-TSK-005`; PHASE_10_PLAN.md section 12.2): exactly one of the
 * {@link AttributeValueType} shapes, or {@link Absent}.
 *
 * <p><strong>Absence is a value</strong> ({@code INV-CRD-07}, {@code INV-CRD-10}): an attribute a
 * source did not report, or reported in a currency the product cannot assess, is {@code Absent} -
 * the policy reasons about it explicitly, and nothing ever defaults it.
 *
 * <p><strong>No value renders itself.</strong> Every {@code toString} names the shape only: an
 * attribute value is {@code RESTRICTED-FINANCIAL}, and a log line or an exception message is no
 * place for it ({@code security.md}).
 */
public sealed interface AttributeValue
        permits AttributeValue.IntegerValue,
                AttributeValue.MoneyValue,
                AttributeValue.BooleanValue,
                AttributeValue.CodeValue,
                AttributeValue.Absent {

    /** Whether this value has the declared type - {@link Absent} has every type. */
    boolean admits(AttributeValueType type);

    /** A whole number. */
    record IntegerValue(long value) implements AttributeValue {
        @Override
        public boolean admits(AttributeValueType type) {
            return type == AttributeValueType.INTEGER;
        }

        @Override
        public String toString() {
            return "IntegerValue[redacted]";
        }
    }

    /** An amount in minor units with its explicit currency - never floating point. */
    record MoneyValue(Money value) implements AttributeValue {
        public MoneyValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public boolean admits(AttributeValueType type) {
            return type == AttributeValueType.MONEY;
        }

        @Override
        public String toString() {
            return "MoneyValue[redacted]";
        }
    }

    /** A yes or no fact. */
    record BooleanValue(boolean value) implements AttributeValue {
        @Override
        public boolean admits(AttributeValueType type) {
            return type == AttributeValueType.BOOLEAN;
        }

        @Override
        public String toString() {
            return "BooleanValue[redacted]";
        }
    }

    /** A member of a closed set of codes - an uppercase token. */
    record CodeValue(String value) implements AttributeValue {
        public CodeValue {
            Objects.requireNonNull(value, "value");
            if (!value.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("a code value is an uppercase token");
            }
        }

        @Override
        public boolean admits(AttributeValueType type) {
            return type == AttributeValueType.CODE;
        }

        @Override
        public String toString() {
            return "CodeValue[redacted]";
        }
    }

    /** Not reported, or not assessable - a value the policy reasons about, never a default. */
    record Absent() implements AttributeValue {
        @Override
        public boolean admits(AttributeValueType type) {
            return true;
        }

        @Override
        public String toString() {
            return "Absent";
        }
    }
}
