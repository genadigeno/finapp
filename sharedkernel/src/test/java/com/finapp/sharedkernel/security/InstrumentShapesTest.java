package com.finapp.sharedkernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link InstrumentShapes}: the one screen every person-written reason, note and narrative
 * passes (the Phase 8 → 9 transition's correction of {@code SEC-03} and {@code SEC-04}). The
 * card number as a person writes it — grouped by spaces or dashes — and the account identifier
 * in its printed form are refused; the platform's own UUIDs and ordinary prose are not.
 */
@DisplayName("the instrument-shape screen for person-written prose")
class InstrumentShapesTest {

    /** 4111111111111111 is the canonical Luhn-valid test card number. */
    @ParameterizedTest(name = "a card number: {0}")
    @ValueSource(strings = {
        "4111111111111111",
        "card 4111111111111111 seen in the report",
        // SEC-03: the spaced and the dashed card number, the most common way a person writes it.
        "customer says card 4111 1111 1111 1111 was charged twice",
        "4111-1111-1111-1111",
        // Inside a longer digit string, and glued to letters.
        "ref 994111111111111111 end",
        "PAN4111111111111111",
        // A grouped card followed by more digits: the span ends on its own group.
        "4111 1111 1111 1111 2 times",
        // Grouped unevenly, and an American Express number in its 4-6-5 print.
        "4111111 111111111",
        "3782 822463 10005",
        // ISO/IEC 7812's twelve-digit card, contiguous and grouped.
        "501800000009",
        "maestro 5018 0000 0009",
    })
    void aCardNumberIsRefused(String text) {
        assertThat(InstrumentShapes.find(text))
                .as("a Luhn-valid 12..19-digit run, grouped by single spaces or dashes or not, is"
                        + " a card number")
                .contains(InstrumentShapes.Shape.CARD_NUMBER);
        assertThat(InstrumentShapes.holdsCardNumber(text)).isTrue();
    }

    /**
     * NEW-SEC-2 (the Phase 8 → 9 transition's re-gate): a card number grouped by the machine
     * separators ':' or '_' — the PSP reference alphabet's own characters — is refused by the
     * domain scan behind {@code find}; the PL/pgSQL twins keep the printed forms, so the
     * twin-rank half stays false and the corpus parity holds.
     */
    @ParameterizedTest(name = "a machine-grouped card number: {0}")
    @ValueSource(strings = {
        "4111:1111:1111:1111",
        "4111_1111_1111_1111",
        "ref 4111:1111_1111-1111 echoed",
        "maestro 5018:0000:0009",
    })
    void aMachineGroupedCardNumberIsRefusedByTheDomainRank(String text) {
        assertThat(InstrumentShapes.find(text))
                .as("':' and '_' group a card number as readily as a dash does")
                .contains(InstrumentShapes.Shape.CARD_NUMBER);
        assertThat(InstrumentShapes.holdsCardNumber(text))
                .as("the twin's verdict is unchanged: V012 and V019 keep the printed forms")
                .isFalse();
    }

    @Test
    @DisplayName("the machine separators widen the card scan alone: non-Luhn groups stay prose")
    void machineSeparatedProseIsStillAdmitted() {
        assertThat(InstrumentShapes.find("9999:9999:9999:9999")).isEmpty();
        assertThat(InstrumentShapes.find("batch 2026_09_25_000017 re-sent")).isEmpty();
    }

    @ParameterizedTest(name = "an account identifier: {0}")
    @ValueSource(strings = {
        "GB82WEST12345698765432",
        "paid to GB82WEST12345698765432 per the bank",
        "moved to GB82WESTABCDEFGHIJKLM today",
        // SEC-03's related gap: the ISO 13616 print form, spaced and dashed, any case.
        "pay instead to GB82 WEST 1234 5698 7654 32 please",
        "GB82-WEST-1234-5698-7654-32",
        "de89 3704 0044 0532 0130 00",
        // The shortest country's, and an identifier after a word that merely looks like an opening.
        "NO93 8601 1117 947",
        "ref XX12 GB82 WEST 1234 5698 7654 32",
    })
    void anAccountIdentifierIsRefused(String text) {
        assertThat(InstrumentShapes.holdsAccountIdentifier(text))
                .as("the contiguous shape, or the printed form whose mod-97 check holds")
                .isTrue();
        assertThat(InstrumentShapes.find(text)).isPresent();
    }

    @Test
    @DisplayName("the printed form ends where its checksum holds, even when a word of four follows")
    void thePrintedFormIsJudgedAtEachGroup() {
        assertThat(InstrumentShapes.holdsAccountIdentifier("BE68 5390 0754 7034 done"))
                .as("the identifier is a prefix of the token run; 'done' is not part of it")
                .isTrue();
        assertThat(InstrumentShapes.firstAccountIdentifier("line one\nto GB82 WEST 1234 5698 7654 32"))
                .as("the index is the text's own, for the settlement door's line count")
                .isEqualTo("line one\nto ".length());
    }

    @ParameterizedTest(name = "admitted: {0}")
    @ValueSource(strings = {
        "the PSP confirmed the capture settled a day late",
        // Thirteen and twelve digits that fail Luhn: references, not cards.
        "network ref 1234567890123 checked",
        "batch 123456789012 re-sent",
        "file received 2026-10-02 12:30:45, retried at 13:00",
        "call the desk on +44 20 7946 0958",
        // Groups of four that are words, an opening a word cannot carry, a broken checksum.
        "FY26 plan 2027 will need more review",
        "moved to xGB82WESTABCDEFGHIJKLM today",
        "GB82 WEST 1234 5698 7654 33",
        "from=AMOUNT_MISMATCH, to=FEE_MISMATCH",
        "retired by version 3's activation: the fee terms moved",
    })
    void ordinaryProseIsAdmitted(String text) {
        assertThat(InstrumentShapes.find(text)).isEmpty();
    }

    @Test
    @DisplayName("the platform's own UUIDs are masked: a dashed UUID whose digits join into a"
            + " Luhn-valid span and a dashless v7 that opens like an account are admitted, and"
            + " the same digits outside a UUID are not")
    void thePlatformsOwnIdentifiersAreNotInstrumentData() {
        // Its dash-joined digits 3451 4013 9675 are a Luhn-valid twelve-digit span.
        String dashed = "7b8f2ab5-3451-4013-9675-f6ad325b55dd";
        assertThat(InstrumentShapes.find("duplicate of run " + dashed)).isEmpty();
        assertThat(InstrumentShapes.find("duplicate of run " + dashed.toUpperCase())).isEmpty();
        assertThat(InstrumentShapes.find("x-3451-4013-9675"))
                .as("the same digits outside a UUID are a grouped card number")
                .contains(InstrumentShapes.Shape.CARD_NUMBER);

        // Opens with two letters and two digits and runs 32 alphanumerics: the account shape.
        String dashless = "ae97ba94d0ed782f8f6d05584ef8aa38";
        assertThat(InstrumentShapes.find("PUSH_WITHDRAWAL:" + dashless)).isEmpty();
        assertThat(InstrumentShapes.find("PUSH_WITHDRAWAL:" + dashless.replace('7', '1')))
                .as("a token that is not a version-7 UUID is screened as written")
                .isPresent();
    }

    @Test
    @DisplayName("the mask is a whole token: a card number cannot hide behind UUID-like text")
    void theMaskIsAWholeToken() {
        assertThat(InstrumentShapes.find("4111111111111111-1111-1111-1111-111111111111"))
                .as("sixteen digits are no UUID's first group")
                .contains(InstrumentShapes.Shape.CARD_NUMBER);
        assertThat(InstrumentShapes.find("x7b8f2ab5-3451-4013-9675-f6ad325b55dd"))
                .as("glued to a letter it is no standing UUID, so it is screened as written")
                .contains(InstrumentShapes.Shape.CARD_NUMBER);
    }

    @Test
    @DisplayName("the verdict names the shape only, card numbers first")
    void theVerdictIsTheShape() {
        assertThat(InstrumentShapes.find("4111 1111 1111 1111 to GB82 WEST 1234 5698 7654 32"))
                .isEqualTo(Optional.of(InstrumentShapes.Shape.CARD_NUMBER));
        assertThat(InstrumentShapes.holdsAny("")).isFalse();
    }
}
