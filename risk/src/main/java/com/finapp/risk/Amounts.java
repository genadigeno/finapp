package com.finapp.risk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The amounts of a subject's movements in one window, in minor units (`P13-TSK-003`; {@link RuleKind#STRUCTURING}) -
 * sealed as the amounts themselves because the rule's "below" threshold is the policy's, which the reader (`-020`)
 * must not know. Sorted ascending at construction, so two presentations of the same movements are one value and
 * one canonical form; at most {@link EvaluationInputs#MAX_AMOUNTS}, a bound exceeded being a refusal, never a cut.
 */
public record Amounts(List<Long> minor) {

    public Amounts {
        Objects.requireNonNull(minor, "minor");
        if (minor.size() > EvaluationInputs.MAX_AMOUNTS) {
            throw new IllegalArgumentException("a window seals at most " + EvaluationInputs.MAX_AMOUNTS + " amounts");
        }
        List<Long> sorted = new ArrayList<>(minor.size());
        for (Long amount : minor) {
            Objects.requireNonNull(amount, "an amount");
            if (amount < 0 || amount > EvaluationInputs.MAX_MINOR) {
                throw new IllegalArgumentException("an amount is 0 to " + EvaluationInputs.MAX_MINOR + " minor units");
            }
            sorted.add(amount);
        }
        Collections.sort(sorted);
        minor = Collections.unmodifiableList(sorted);
    }

    public static Amounts of(long... minor) {
        List<Long> list = new ArrayList<>(minor.length);
        for (long amount : minor) {
            list.add(amount);
        }
        return new Amounts(list);
    }

    /** How many amounts are below {@code thresholdMinor}. */
    public int countBelow(long thresholdMinor) {
        int count = 0;
        for (long amount : minor) {
            if (amount < thresholdMinor) {
                count++;
            }
        }
        return count;
    }

    /** The sum of the amounts below {@code thresholdMinor} - at most 10^18, so never an overflow. */
    public long sumBelow(long thresholdMinor) {
        long sum = 0;
        for (long amount : minor) {
            if (amount < thresholdMinor) {
                sum += amount;
            }
        }
        return sum;
    }
}
