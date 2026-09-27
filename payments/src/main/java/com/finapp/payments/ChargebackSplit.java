package com.finapp.payments;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * What a chargeback took, and who bears it (`P7-TSK-013`, ADR-0061 §3–§5) — the combined
 * bound's arithmetic as a value.
 *
 * <pre>
 *   amount            what the network took — the external fact, posted in full against the
 *                     card rail's clearing position whatever the attribution says
 *   counterpartyShare the part charged to the payment's counterparty (the merchant's payable,
 *                     or the customer's wallet for a top-up) — posted to its account
 *   parkedShare       the part that IS the counterparty's but could not be posted, because its
 *                     account no longer takes postings (a closed wallet): parked in
 *                     CHARGEBACK_RECOVERABLE, visibly, for an operator to recover
 *   excess            amount − both shares: value the network took that the platform had
 *                     ALREADY returned (a refund) or never credited (nothing captured) —
 *                     never the counterparty's, recoverable by representment, written off on a
 *                     loss
 * </pre>
 *
 * <h2>The bound, by construction</h2>
 *
 * <p>{@link #of} attributes {@code min(amount, headroom)} to the counterparty, where the caller's
 * headroom is {@code captured − non-failed refunds − what standing chargebacks already
 * attribute}, read under the attempt row lock both money paths take. So the counterparty is
 * never charged more than the capture credited it ({@code INV-DSP-01}), and the platform's
 * double-take lands where it belongs — ADR-0061's risk 2 closed by arithmetic, not by a check
 * someone must remember. A parked share counts against the bound exactly like a posted one: it
 * is the counterparty's by attribution, and a second cycle must not attribute it twice.
 *
 * <h2>Only the excess ever moves back</h2>
 *
 * <p>{@link #reattributed} is the one legal change after the chargeback: a counted refund that
 * later failed never returned the money its count assumed, so the share of the excess it had
 * caused comes back to the counterparty (ADR-0061 §3's last rule). The shares only grow, the
 * excess only shrinks, and the amount never moves — `V021` holds the same for every writer.
 */
public record ChargebackSplit(Money amount, Money counterpartyShare, Money parkedShare) {

    public ChargebackSplit {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(counterpartyShare, "counterpartyShare must not be null");
        Objects.requireNonNull(parkedShare, "parkedShare must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a chargeback amount must be positive");
        }
        if (!counterpartyShare.currency().equals(amount.currency())
                || !parkedShare.currency().equals(amount.currency())
                || counterpartyShare.scale() != amount.scale()
                || parkedShare.scale() != amount.scale()) {
            throw new IllegalArgumentException(
                    "a chargeback's shares are parts of its amount, in its currency and scale");
        }
        if (counterpartyShare.isNegative() || parkedShare.isNegative()) {
            throw new IllegalArgumentException("a chargeback's shares are never negative");
        }
        if (counterpartyShare.plus(parkedShare).compareTo(amount) > 0) {
            throw new IllegalArgumentException(
                    "a chargeback's shares never exceed what the network took");
        }
    }

    /**
     * The split of a chargeback of {@code amount} when {@code headroom} is what the capture
     * still leaves chargeable to the counterparty — judged by the caller under the attempt row
     * lock. A negative headroom is read as none (an invariant broken elsewhere never becomes a
     * negative share here).
     *
     * @param counterpartyPostable whether the counterparty's account takes postings now; when it
     *     does not, its share is parked rather than refused (ADR-0061 §5 — the network has
     *     already taken the money)
     */
    public static ChargebackSplit of(Money amount, Money headroom, boolean counterpartyPostable) {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(headroom, "headroom must not be null");
        Money zero = Money.ofPersisted(0L, amount.currency(), amount.scale());
        Money available = headroom.isNegative() ? zero : headroom;
        Money attributable = available.compareTo(amount) < 0 ? available : amount;
        return counterpartyPostable
                ? new ChargebackSplit(amount, attributable, zero)
                : new ChargebackSplit(amount, zero, attributable);
    }

    /** The part nobody has been charged: value the network took twice, or never credited. */
    public Money excess() {
        return amount.minus(counterpartyShare).minus(parkedShare);
    }

    /** What the counterparty bears by attribution, posted or parked — the bound's term. */
    public Money attributed() {
        return counterpartyShare.plus(parkedShare);
    }

    /** What rests in {@code CHARGEBACK_RECOVERABLE} while the chargeback stands. */
    public Money recoverable() {
        return amount.minus(counterpartyShare);
    }

    /**
     * {@code moved} of the excess comes back to the counterparty — posted to it, or parked when
     * its account takes no postings.
     *
     * @throws IllegalArgumentException if {@code moved} is not positive or exceeds the excess
     */
    public ChargebackSplit reattributed(Money moved, boolean counterpartyPostable) {
        Objects.requireNonNull(moved, "moved must not be null");
        if (!moved.isPositive()) {
            throw new IllegalArgumentException("a re-attribution moves a positive amount");
        }
        if (moved.compareTo(excess()) > 0) {
            throw new IllegalArgumentException(
                    "a re-attribution moves at most the excess: only value the network took"
                            + " twice ever comes back to the counterparty");
        }
        return counterpartyPostable
                ? new ChargebackSplit(amount, counterpartyShare.plus(moved), parkedShare)
                : new ChargebackSplit(amount, counterpartyShare, parkedShare.plus(moved));
    }

    /** Never an amount (INV-AUD-02): a record's generated form would print all three. */
    @Override
    public String toString() {
        return "ChargebackSplit[" + amount.currency() + "]";
    }
}
