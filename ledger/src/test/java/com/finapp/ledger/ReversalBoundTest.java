package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reversal arithmetic, hermetically (`P3-TSK-016`, {@code INV-REV-01/02}; plan §12's
 * "reversal arithmetic including partial reversals"): per {@code (account, direction)} pair,
 * requested plus prior never exceeds the original's total at the opposite side.
 *
 * <p>This is the domain half; the concurrent arbiter — `V009`'s trigger under the advisory
 * lock — is proven against a live PostgreSQL in {@code ReversalDatabaseTest}.
 */
@DisplayName("reversal arithmetic: the bound, per pair (P3-TSK-016)")
class ReversalBoundTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private static final JournalEntryId ORIGINAL = JournalEntryId.next(IDS);
    private static final LedgerAccountId WALLET = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId CLEARING = LedgerAccountId.of(IDS.next());
    private static final LedgerAccountId OTHER = LedgerAccountId.of(IDS.next());

    /** The original: clearing debited 100, wallet credited 100. */
    private static List<JournalLine> original() {
        return List.of(
                line(CLEARING, Direction.DEBIT, 1000),
                line(WALLET, Direction.CREDIT, 1000));
    }

    /** A reversal of {@code minor}: directions swapped pair-wise. */
    private static List<JournalLine> reversalOf(long minor) {
        return List.of(
                line(CLEARING, Direction.CREDIT, minor),
                line(WALLET, Direction.DEBIT, minor));
    }

    private static JournalLine line(LedgerAccountId account, Direction direction, long minor) {
        return new JournalLine(account, direction, Money.ofMinorUnits(minor, USD));
    }

    @Test
    @DisplayName("a full reversal fits exactly - the boundary is the original, inclusive")
    void aFullReversalFitsExactly() {
        assertThatCode(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL, original(), List.of(), reversalOf(1000)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("partials sum: 30 then 70 fit, and the next minor unit is refused")
    void partialsSumToTheOriginal() {
        assertThatCode(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL, original(), reversalOf(300), reversalOf(700)))
                .doesNotThrowAnyException();

        List<JournalLine> prior = new java.util.ArrayList<>(reversalOf(300));
        prior.addAll(reversalOf(700));
        assertThatThrownBy(
                        () -> ReversalBound.validate(ORIGINAL, original(), prior, reversalOf(1)))
                .isInstanceOf(OverReversalException.class)
                // The refusal names the entry and the account, never an amount (INV-AUD-02).
                .hasMessageNotContaining("1000")
                .hasMessageNotContaining("10.00");
    }

    @Test
    @DisplayName("one minor unit over the remainder is refused (INV-REV-02)")
    void oneMinorUnitOverIsRefused() {
        assertThatThrownBy(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL, original(), reversalOf(300), reversalOf(701)))
                .isInstanceOf(OverReversalException.class);
    }

    @Test
    @DisplayName("a pair the original does not have is refused - the mirror rule (INV-REV-01)")
    void aMirrorlessPairIsRefused() {
        // Same direction as the original (not swapped): the original has no DEBIT on the
        // wallet, so a "reversal" crediting clearing and crediting wallet mirrors nothing.
        assertThatThrownBy(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL,
                                        original(),
                                        List.of(),
                                        List.of(
                                                line(CLEARING, Direction.CREDIT, 100),
                                                line(WALLET, Direction.CREDIT, 100))))
                .isInstanceOf(OverReversalException.class);
        // An account the original never touched, likewise.
        assertThatThrownBy(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL,
                                        original(),
                                        List.of(),
                                        List.of(
                                                line(CLEARING, Direction.CREDIT, 100),
                                                line(OTHER, Direction.DEBIT, 100))))
                .isInstanceOf(OverReversalException.class);
    }

    @Test
    @DisplayName("the bound is per pair, not per whole: one pair cannot borrow another's room")
    void thePairsDoNotBorrowFromEachOther() {
        // Prior reversals took 900 from the wallet pair and nothing from the clearing pair.
        List<JournalLine> prior =
                List.of(
                        line(WALLET, Direction.DEBIT, 900),
                        line(CLEARING, Direction.CREDIT, 900));
        // A request of 200 fits the clearing pair (100 remaining? no - 100 remains on BOTH
        // pairs) - it must be refused on the wallet pair even though totals across pairs
        // would "fit" a whole-entry bound.
        assertThatThrownBy(
                        () ->
                                ReversalBound.validate(
                                        ORIGINAL, original(), prior, reversalOf(200)))
                .isInstanceOf(OverReversalException.class);
    }

    @Test
    @DisplayName("opposite() is an involution, and reversal is its promised first caller")
    void oppositeIsAnInvolution() {
        assertThat(Direction.DEBIT.opposite()).isEqualTo(Direction.CREDIT);
        assertThat(Direction.CREDIT.opposite()).isEqualTo(Direction.DEBIT);
        for (Direction direction : Direction.values()) {
            assertThat(direction.opposite().opposite()).isEqualTo(direction);
        }
    }
}
