package com.finapp.platform.sourcejoin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A key that cannot name a record joins nothing (`P15-TSK-035`): every parse here is what stands between a forger's free text and a
 * cast that would poison the census. {@code UUID.fromString}'s leniency in particular is not the join's: a short-group or an
 * upper-cased copy of a real id is a different key, and must not resolve to a real record.
 */
@DisplayName("a key that cannot name a record joins nothing (P15-TSK-035)")
class KeysTest {

    private static final String REAL = "018f4a2e-7b6a-7c3d-8e9f-0123456789ab";

    private static EntryRef suffix(String suffix) {
        return new EntryRef(UUID.randomUUID(), suffix, "reference");
    }

    @Test
    @DisplayName("suffixUuid takes the canonical form only: a lenient short-group or an upper-cased copy of a real id joins nothing")
    void suffixUuidIsCanonical() {
        Keys.KeyForm key = Keys.suffixUuid();
        assertThat(key.keyOf(suffix(REAL))).hasValue(REAL);
        assertThat(key.keyOf(suffix(REAL.toUpperCase()))).as("the upper-cased copy is another key").isEmpty();
        assertThat(key.keyOf(suffix("1-2-3-4-5"))).isEmpty();
        assertThat(key.keyOf(suffix("not-a-uuid"))).isEmpty();
        assertThat(key.bind(List.of(suffix(REAL), suffix("junk")))).containsExactly(UUID.fromString(REAL));
    }

    @Test
    @DisplayName("uuid-and-date: a real date only: year 0 and an impossible date are not castable, and join nothing rather than failing the batch")
    void uuidThenDateIsARealDate() {
        assertThat(Keys.uuidThenDate().test(REAL + ":2026-11-09")).isTrue();
        assertThat(Keys.uuidThenDate().test(REAL + ":0000-01-01")).as("PostgreSQL refuses year 0").isFalse();
        assertThat(Keys.uuidThenDate().test(REAL + ":+12345-01-01")).as("LocalDate reads it; ::date does not (the review's plant)").isFalse();
        assertThat(Keys.uuidThenDate().test(REAL + ":2026-13-01")).isFalse();
        assertThat(Keys.uuidThenDate().test("garbage:2026-11-09")).isFalse();
        assertThat(Keys.uuidThenDate().test(REAL)).isFalse();
    }

    @Test
    @DisplayName("uuid-and-word: only the words named")
    void uuidThenWord() {
        assertThat(Keys.uuidThenWord("LATE").test(REAL + ":LATE")).isTrue();
        assertThat(Keys.uuidThenWord("LATE").test(REAL + ":EARLY")).isFalse();
    }

    @Test
    @DisplayName("catch-up: a sequence that fits the integer the statement casts it to")
    void catchUp() {
        assertThat(Keys.uuidThenCatchUp().test(REAL + ":catch-up:7")).isTrue();
        assertThat(Keys.uuidThenCatchUp().test(REAL + ":catch-up:99999999999999")).as("past the int the cast expects").isFalse();
        assertThat(Keys.uuidThenCatchUp().test(REAL + ":something:7")).isFalse();
    }

    @Test
    @DisplayName("rail-and-reference: a rail's own shape; a suffix with no separator is not one")
    void railReference() {
        assertThat(Keys.railReference().test("sim-rail:ABC-123:45")).isTrue();
        assertThat(Keys.railReference().test("NOTARAIL:ABC")).isFalse();
        assertThat(Keys.railReference().test("sim-rail")).isFalse();
    }

    @Test
    @DisplayName("reference and entry keys bind what they name, and only what parses")
    void entryAndReferenceKeys() {
        UUID entry = UUID.randomUUID();
        assertThat(Keys.entryId().keyOf(new EntryRef(entry, "", ""))).hasValue(entry.toString());
        assertThat(Keys.referenceUuid().keyOf(new EntryRef(entry, "", REAL))).hasValue(REAL);
        assertThat(Keys.referenceUuid().keyOf(new EntryRef(entry, "", "free text"))).isEmpty();
    }

    @Test
    @DisplayName("a figure's bounds: a currency code, a scale, never a negative amount")
    void expectedLegValidates() {
        assertThat(ExpectedLeg.exact(ExpectedLeg.Side.DEBIT, "USD", 2, 5).bound()).isEqualTo(ExpectedLeg.Bound.EXACT);
        assertThat(SourceFigures.total("USD", 2, 1).compares()).isTrue();
        assertThat(SourceFigures.presenceOnly("why").missingFigure()).isFalse();
        assertThat(SourceFigures.noFigure().missingFigure()).isTrue();
    }
}
