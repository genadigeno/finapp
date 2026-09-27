package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The book rail's declaration, pinned field by field (`P7-TSK-011`, ADR-0059 §6) — the card
 * and instant RAILs' discipline at the third rail: the declaration is compiled data, and
 * every claim the platform makes about book payments rests on exactly these fields.
 */
@DisplayName("the book rail's declaration (P7-TSK-011)")
class BookRailTest {

    @Test
    @DisplayName("the declaration: book, FINAL ON POSTING, no reversals, book-refund"
            + " refunds, nothing external to settle and NO clearing position, no outcome"
            + " deadline, no disputes, no restriction the wallet's own lock does not judge")
    void theDeclarationIsPinnedFieldByField() {
        assertThat(BookRail.RAIL.id()).isEqualTo(RailId.of("book"));
        assertThat(BookRail.RAIL.declarationVersion()).isEqualTo(1);
        RailCapabilities book = BookRail.RAIL.capabilities();
        assertThat(book.interactionModel()).isEqualTo(InteractionModel.BOOK);
        assertThat(book.finality())
                .as("nobody else ever decides: the posting's commit IS the payment"
                        + " (ADR-0059 §6)")
                .isEqualTo(RailCapabilities.Finality.FINAL_ON_POSTING);
        assertThat(book.reversals())
                .as("no void, no reversal of any kind: the compensation is a refund")
                .isEmpty();
        assertThat(book.refundMode()).isEqualTo(RailCapabilities.RefundMode.BOOK_REFUND);
        assertThat(book.settlement())
                .as("nothing external ever settles (INV-SET-01, vacuously and declared)")
                .isEqualTo(RailCapabilities.SettlementModel.NONE);
        assertThat(book.outcomeDeadline())
                .as("there is never an outcome to wait for")
                .isEmpty();
        assertThat(book.disputes()).isEqualTo(RailCapabilities.DisputeModel.NONE);
        assertThat(book.currencies()).isEmpty();
        assertThat(book.perCurrencyMaximum()).isEmpty();
        assertThat(book.clearingPurpose())
                .as("no clearing position exists to net against (INV-RAIL-04's inverse:"
                        + " the counterpart is the payer's own wallet)")
                .isEmpty();
    }

    @Test
    @DisplayName("the coherence rules force this exact combination: a book rail cannot"
            + " declare a clearing position, an outside settlement or another finality")
    void theCoherenceRulesForceTheCombination() {
        assertThatThrownBy(
                        () ->
                                new RailCapabilities(
                                        InteractionModel.BOOK,
                                        RailCapabilities.Finality.FINAL_ON_POSTING,
                                        Set.of(),
                                        RailCapabilities.RefundMode.BOOK_REFUND,
                                        RailCapabilities.SettlementModel.SCHEME_REPORTED,
                                        Optional.empty(),
                                        RailCapabilities.DisputeModel.NONE,
                                        Optional.empty(),
                                        Map.of(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("book rail");
        assertThatThrownBy(
                        () ->
                                new RailCapabilities(
                                        InteractionModel.BOOK,
                                        RailCapabilities.Finality.FINAL_ON_ACCEPTANCE,
                                        Set.of(),
                                        RailCapabilities.RefundMode.BOOK_REFUND,
                                        RailCapabilities.SettlementModel.NONE,
                                        Optional.empty(),
                                        RailCapabilities.DisputeModel.NONE,
                                        Optional.empty(),
                                        Map.of(),
                                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final-on-posting");
    }
}
