package com.finapp.ledger;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The atomic accounting unit: at least two lines whose debits equal credits, per currency
 * (`P3-TSK-004`, {@code INV-LED-01}, {@code INV-LED-02}).
 *
 * <h2>An unbalanced entry cannot be constructed</h2>
 *
 * <p>The balance rule runs in the one factory, so there is no code path on which an unbalanced
 * {@code JournalEntry} exists as an object — the check has no call site to be forgotten at.
 * The schema restates the rule for writers that never run this code (`P3-TSK-005`, where "a
 * {@code CHECK} cannot see sibling rows" is a named design problem), which is defence in depth
 * and not a substitute in either direction.
 *
 * <h2>Balance is two sums that must be equal, per currency</h2>
 *
 * <p>Amounts are positive and {@link Direction} carries the sign, so the rule is
 * {@code sum(debits) == sum(credits)} in every currency the entry touches — a multi-currency
 * entry must balance <em>in each</em>, because "balanced in total" across currencies is
 * cross-currency arithmetic wearing a summary's clothes ({@code INV-MON-04}). The sums fold
 * through {@link Money#plus}, which is what makes two properties structural rather than
 * checked: cross-currency addition is impossible, and <strong>mixed scales within one currency
 * are refused rather than normalised</strong> — a {@code ScaleMismatchException} propagates,
 * because silently rescaling a line to make the sum computable is the implicit rounding
 * {@code INV-MON-03} forbids.
 *
 * <h2>The three dates ({@code DOMAIN_MODEL.md} §Time)</h2>
 *
 * <p>{@code createdAt} is system time, read from the injected clock at construction and never
 * a business fact. <strong>Posting date and value date are required inputs</strong>: the
 * factory demands both, so a component that wants to derive one from the clock has no overload
 * to do it with — deciding that execution time and accounting date are the same thing takes an
 * explicit {@code LocalDate.now(clock)} at the caller, where a design review can see it. No
 * ordering between the three is imposed: a back-dated correction posts today about then, and
 * late settlement's value date precedes its system time.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <p>Attribution (actor, correlation, causation — {@code INV-LED-05}), the entry type, the
 * reference and the reason are the persisted record's fields, arriving with `P3-TSK-005`/`-006`
 * whose designs fix their semantics; line order ({@code seq}) is persistence's — the list order
 * here is the order. Reversal is `P3-TSK-016`'s new entry, never a method on this one.
 */
public final class JournalEntry {

    private final JournalEntryId id;
    private final LocalDate postingDate;
    private final LocalDate valueDate;
    private final List<JournalLine> lines;
    private final Instant createdAt;

    private JournalEntry(
            JournalEntryId id,
            LocalDate postingDate,
            LocalDate valueDate,
            List<JournalLine> lines,
            Instant createdAt) {
        this.id = id;
        this.postingDate = postingDate;
        this.valueDate = valueDate;
        this.lines = lines;
        this.createdAt = createdAt;
    }

    /**
     * Reconstitutes from storage (`P3-TSK-005`). Applies no validation — the row was already
     * valid, held by the schema's own triggers — and exists so a historical entry whose
     * shapes a LATER rule would refuse still reads back ({@code INV-MON-05}'s reasoning,
     * applied to the whole record).
     */
    public static JournalEntry rehydrate(
            JournalEntryId id,
            LocalDate postingDate,
            LocalDate valueDate,
            List<JournalLine> lines,
            Instant createdAt) {
        return new JournalEntry(id, postingDate, valueDate, List.copyOf(lines), createdAt);
    }

    /**
     * The one way to a NEW entry: validates {@code INV-LED-02} then {@code INV-LED-01} and
     * yields an immutable value, or throws with nothing constructed.
     */
    public static JournalEntry balanced(
            IdGenerator ids,
            Clock clock,
            LocalDate postingDate,
            LocalDate valueDate,
            List<JournalLine> lines) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(postingDate, "postingDate is a domain input and must be given");
        Objects.requireNonNull(valueDate, "valueDate is a domain input and must be given");
        Objects.requireNonNull(lines, "lines must not be null");
        List<JournalLine> copied = List.copyOf(lines);
        if (copied.size() < 2) {
            throw new IllegalArgumentException(
                    "a journal entry has at least two lines (INV-LED-02): a single-sided"
                            + " posting is unbalanced value movement by definition");
        }
        requireBalancedPerCurrency(copied);
        return new JournalEntry(
                JournalEntryId.next(ids), postingDate, valueDate, copied, Instant.now(clock));
    }

    /**
     * Debits equal credits in every currency present. Folded through {@link Money#plus}, so a
     * mixed-scale currency refuses as {@code ScaleMismatchException} before any comparison —
     * the sum is not computable, and computing it anyway would be an implicit rescale.
     */
    private static void requireBalancedPerCurrency(List<JournalLine> lines) {
        Map<CurrencyCode, Money[]> sums = new LinkedHashMap<>();
        for (JournalLine line : lines) {
            CurrencyCode currency = line.amount().currency();
            Money[] debitAndCredit =
                    sums.computeIfAbsent(
                            currency,
                            each -> new Money[] {Money.zero(each), Money.zero(each)});
            int side = line.direction() == Direction.DEBIT ? 0 : 1;
            debitAndCredit[side] = sum(debitAndCredit[side], line.amount());
        }
        for (Map.Entry<CurrencyCode, Money[]> perCurrency : sums.entrySet()) {
            if (!perCurrency.getValue()[0].equals(perCurrency.getValue()[1])) {
                throw new UnbalancedJournalEntryException(perCurrency.getKey());
            }
        }
    }

    /**
     * {@code Money.zero} is at the currency's current default scale, and lines legitimately
     * arrive at another (a persisted-scale amount, {@code INV-MON-05}) — so the identity is
     * replaced rather than added to on the first line, and every later addition is
     * line-to-line, where a scale mismatch is a genuine refusal rather than an artefact of the
     * zero's own scale.
     *
     * <p>Package-private since `P3-TSK-008`: the balance derivation folds an account's whole
     * line history through exactly this identity treatment, and a second copy of a monetary
     * subtlety is the copy that drifts. The caller guarantees the currency matches — this
     * adoption branch deliberately bypasses {@link Money#plus}'s currency check for a zero.
     */
    static Money sum(Money accumulated, Money amount) {
        return accumulated.isZero() && accumulated.scale() != amount.scale()
                ? amount
                : accumulated.plus(amount);
    }

    public JournalEntryId id() {
        return id;
    }

    /** The accounting date deciding which period this entry falls in. A domain input. */
    public LocalDate postingDate() {
        return postingDate;
    }

    /** When value is available or interest starts accruing. A rail/product rule, an input. */
    public LocalDate valueDate() {
        return valueDate;
    }

    /** Immutable, in the order given; {@code seq} in persistence records exactly this order. */
    public List<JournalLine> lines() {
        return lines;
    }

    /** System time of construction. Never a business fact ({@code DOMAIN_MODEL.md} §Time). */
    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JournalEntry entry && id.equals(entry.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers, dates and a line count — never an amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "JournalEntry[" + id + ", posting=" + postingDate + ", value=" + valueDate
                + ", lines=" + lines.size() + "]";
    }
}
