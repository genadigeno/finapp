package com.finapp.merchant;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Which merchant a payment is for, and which fee schedule version prices it — written when the
 * intent is created, read when the capture posts (`P6-TSK-005`, ADR-0050 §5,
 * {@code INV-MER-03}, {@code INV-HIST-04}).
 *
 * <h2>Why the version is pinned here and not resolved at capture</h2>
 *
 * <p>A capture can arrive seconds or days after the payment was created — a provider answers on
 * its own schedule (ADR-0046), and the sweeper resolves what never answered. If the fee were
 * resolved at capture, a schedule version created in between would reprice a payment the
 * customer had already agreed to and the merchant had already been quoted. That is the
 * restatement question 8 carried, arriving one level down.
 *
 * <p>So the version is fixed at the moment the price is agreed, and the capture applies it.
 * ADR-0050 §5 in one row: <em>a capture mid-flight when the version changes uses the version
 * pinned at dispatch.</em>
 *
 * <h2>Immutable, and one per payment</h2>
 *
 * <p>The intent reference is the primary key: a second pin would be a second price for one
 * payment, and there is no reading of that which is not a defect. The row is never updated and
 * never deleted — `V005` refuses both for every writer, reusing `V004`'s own trigger function,
 * because a pin that can move is not a pin.
 *
 * <h2>The gross is recorded so the capture can refuse a mismatch</h2>
 *
 * <p>Today a capture is the authorized promise in full (ADR-0045 §4 — no partial capture until
 * its producer exists), so the captured amount always equals this one. That is exactly why it
 * is worth storing: the assumption is invisible otherwise, and the day it stops holding, a
 * capture priced against a gross nobody agreed is a silent mispricing rather than a loud
 * refusal.
 *
 * @param paymentIntentRef the {@code payments.payment_intent} row, by value — no cross-schema
 *     foreign key (ADR-0029)
 * @param merchantId whose payment this is
 * @param versionId the fee schedule version that prices it, pinned
 * @param gross what was agreed — the amount the capture must match
 */
public record PaymentFeePin(
        UUID paymentIntentRef,
        MerchantId merchantId,
        FeeScheduleVersionId versionId,
        Money gross,
        Instant pinnedAt,
        String pinnedBy) {

    public PaymentFeePin {
        Objects.requireNonNull(paymentIntentRef, "paymentIntentRef must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(versionId, "versionId must not be null");
        Objects.requireNonNull(gross, "gross must not be null");
        if (!gross.isPositive()) {
            throw new IllegalArgumentException(
                    "A pinned gross must be positive, but was: " + gross);
        }
        Objects.requireNonNull(pinnedAt, "pinnedAt must not be null");
        Objects.requireNonNull(pinnedBy, "pinnedBy must not be null");
    }

    /**
     * Whether this pin and {@code other} name the <strong>same price for the same payment</strong>
     * -- the question {@code MerchantSettlement.pin} asks when the primary key refuses a second
     * insert (`P6-TSK-007`).
     *
     * <p>Four fields, and deliberately not six: the payment, the merchant, the version and the
     * gross are the <em>decision</em>. {@code pinnedAt} and {@code pinnedBy} are its
     * <em>provenance</em>, and two instances racing to record one decision necessarily differ in
     * both -- comparing them would turn a duplicate into a repricing alarm, which is the one
     * alarm that must never cry wolf ({@code INV-MER-03}).
     *
     * <p>{@code Money}'s equality is currency- and scale-aware, so a gross of 100.00 EUR and one
     * of 100.00 USD do not pass as one another here.
     */
    public boolean pricesTheSameAs(PaymentFeePin other) {
        Objects.requireNonNull(other, "other must not be null");
        return paymentIntentRef.equals(other.paymentIntentRef)
                && merchantId.equals(other.merchantId)
                && versionId.equals(other.versionId)
                && gross.equals(other.gross);
    }
}
