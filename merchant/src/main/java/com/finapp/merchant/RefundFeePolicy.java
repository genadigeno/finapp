package com.finapp.merchant;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What happens to the platform's fee when a merchant-bound capture is refunded
 * (`P6-TSK-004`, ADR-0050's consequences: <em>"fee retained or returned — a schedule
 * attribute, versioned with it, so the answer is data, not code"</em>).
 *
 * <p><strong>Two values, because ADR-0050 named two.</strong> A third — pro-rata as a separate
 * policy — would be a state with no producer, which this phase has refused three times already
 * ({@code MerchantStatus}'s {@code PENDING} reasoning). Proportionality is not a third policy;
 * it is how {@link #RETURNED} behaves on a partial refund, defined below.
 *
 * <p><strong>The meanings are defined here, and the arithmetic is deliberately elsewhere.</strong>
 * This task pins the policy; the refund task computes with it. Defining what each value MEANS
 * is this task's job precisely because it is the value that gets frozen into a version — a
 * later task that had to invent the meaning would be inventing the past. The computation
 * itself is out of scope and named to its owner.
 */
public enum RefundFeePolicy {

    /**
     * The platform keeps the fee. A refund returns the gross to the customer out of the
     * merchant's payable and leaves the {@code FEE_REVENUE} line untouched.
     *
     * <p>The commercial default in card processing: the platform did the work of processing
     * the payment whether or not the merchant later refunded it.
     */
    RETAINED,

    /**
     * The platform returns the fee. A refund returns the gross and credits the payable the
     * fee back, <strong>in proportion to the refunded amount</strong> when the refund is
     * partial.
     *
     * <p>The proportional share is computed by the same discipline the original assessment
     * used ({@code INV-MER-04}): the returned fee is computed <em>once</em> under the
     * <strong>pinned</strong> version, and what stays with the platform is derived by
     * subtraction — never both rounded independently, which is how a cent is created across a
     * refund boundary. Successive partial refunds of one capture must not, in sum, return more
     * fee than was assessed; the bound is the assessed fee, judged against what has already
     * been returned.
     */
    RETURNED;

    /** The values as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(policy -> "'" + policy.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /**
     * Resolves a policy from its recorded name.
     *
     * @throws IllegalArgumentException if the name is not a known policy — never silently
     *     replaced with a default, because that would change the meaning of a decision being
     *     replayed ({@code RoundingPolicy.ofName}'s rule, for the same reason)
     */
    public static RefundFeePolicy ofName(String name) {
        for (RefundFeePolicy policy : values()) {
            if (policy.name().equals(name)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown refund fee policy: '" + name + "'");
    }
}
