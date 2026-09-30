package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The statement chain's seams, pure (`P8-TSK-016`, {@code INV-SET-06}): the first statement opens
 * at zero or raises {@code OPENING_BALANCE} for the absolute opening — the only producer of that
 * cause; a later statement stitches to its accepted predecessor's closing, and raises
 * {@code STATEMENT_GAP} both without one (the absolute opening) and when the predecessor's closing
 * is not its opening (the absolute difference); a predecessor that is not the statement just
 * before is a caller's defect; and a fill stitches exactly when its closing is the successor's
 * opening.
 */
@DisplayName("the statement chain's seams (P8-TSK-016)")
class StatementChainTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static Money eur(long minor) {
        return Money.ofPersisted(minor, EUR, 2);
    }

    private static StatementChain.Link link(long sequence, long openingMinor, long closingMinor) {
        return new StatementChain.Link(IDS.next(), sequence, eur(openingMinor), eur(closingMinor));
    }

    private static StatementChain.Seam seam(BreakCause cause, long minor) {
        return new StatementChain.Seam(cause, eur(minor));
    }

    @Test
    @DisplayName("the first statement opening at zero has no seam - the account opened empty")
    void theFirstStatementAtZeroHasNoSeam() {
        assertThat(StatementChain.seamOf(1, eur(0), Optional.empty())).isEmpty();
    }

    @Test
    @DisplayName("a non-zero first opening is OPENING_BALANCE at its ABSOLUTE value - a credit"
            + " balance and a debit balance alike")
    void aNonZeroFirstOpeningIsAnOpeningBalance() {
        assertThat(StatementChain.seamOf(1, eur(1_000), Optional.empty()))
                .contains(seam(BreakCause.OPENING_BALANCE, 1_000));
        assertThat(StatementChain.seamOf(1, eur(-1_000), Optional.empty()))
                .as("a debit balance is value nobody booked too, never a negative value")
                .contains(seam(BreakCause.OPENING_BALANCE, 1_000));
    }

    @Test
    @DisplayName("a later statement without its accepted predecessor is STATEMENT_GAP at the"
            + " absolute opening - a gap even at zero value")
    void aLaterStatementWithoutItsPredecessorIsAGap() {
        assertThat(StatementChain.seamOf(3, eur(14_525), Optional.empty()))
                .contains(seam(BreakCause.STATEMENT_GAP, 14_525));
        assertThat(StatementChain.seamOf(3, eur(-250), Optional.empty()))
                .contains(seam(BreakCause.STATEMENT_GAP, 250));
        assertThat(StatementChain.seamOf(2, eur(0), Optional.empty()))
                .as("the hole is the break, whatever the opening")
                .contains(seam(BreakCause.STATEMENT_GAP, 0));
    }

    @Test
    @DisplayName("a predecessor whose closing is this opening stitches - no seam")
    void aStitchedPredecessorHasNoSeam() {
        assertThat(StatementChain.seamOf(2, eur(13_025), Optional.of(link(1, 0, 13_025))))
                .isEmpty();
        assertThat(StatementChain.seamOf(2, eur(-500), Optional.of(link(1, 0, -500))))
                .as("a debit balance carried forward stitches the same way")
                .isEmpty();
    }

    @Test
    @DisplayName("a PRESENT predecessor whose closing is not this opening is STATEMENT_GAP at"
            + " the ABSOLUTE difference, either way - OPENING_BALANCE is the first statement's"
            + " alone")
    void aMisStitchedOpeningIsAGap() {
        assertThat(StatementChain.seamOf(2, eur(13_000), Optional.of(link(1, 0, 13_025))))
                .contains(seam(BreakCause.STATEMENT_GAP, 25));
        assertThat(StatementChain.seamOf(2, eur(13_100), Optional.of(link(1, 0, 13_025))))
                .contains(seam(BreakCause.STATEMENT_GAP, 75));
        assertThat(StatementChain.seamOf(2, eur(-100), Optional.of(link(1, 0, 100))))
                .as("across zero: the difference, not either balance")
                .contains(seam(BreakCause.STATEMENT_GAP, 200));
        assertThat(StatementChain.seamOf(5, eur(10_000), Optional.of(link(4, 0, 9_000))))
                .map(StatementChain.Seam::cause)
                .as("a later statement never raises OPENING_BALANCE")
                .contains(BreakCause.STATEMENT_GAP);
    }

    @Test
    @DisplayName("a predecessor that is not the statement just before is refused - the"
            + " caller read the wrong neighbour")
    void aPredecessorOutOfSequenceIsRefused() {
        assertThatThrownBy(
                        () ->
                                StatementChain.seamOf(
                                        3, eur(13_025), Optional.of(link(1, 0, 13_025))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                StatementChain.seamOf(
                                        3, eur(13_025), Optional.of(link(3, 0, 13_025))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a fill stitches exactly when its closing is the successor's opening")
    void aFillStitchesExactlyAtTheSuccessorsOpening() {
        StatementChain.Link successor = link(3, 14_525, 14_300);
        assertThat(StatementChain.stitches(eur(14_525), successor)).isTrue();
        assertThat(StatementChain.stitches(eur(14_524), successor)).isFalse();
        assertThat(StatementChain.stitches(eur(-14_525), successor))
                .as("the sign is part of the balance")
                .isFalse();
    }
}
