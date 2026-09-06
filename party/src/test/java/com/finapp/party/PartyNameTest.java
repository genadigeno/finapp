package com.finapp.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PartyName}: what it accepts, and that it never prints itself (`P1-TSK-005`).
 *
 * <p><strong>The masking half is the security half</strong>, and it is asserted rather than trusted
 * for the reason {@code P0-TSK-030} recorded: a record's generated {@code toString} prints every
 * component, so the override is the <em>only</em> thing standing between a name and any log line
 * that interpolates a {@code Party}. Jackson was found declining to reveal a {@code Sensitive} by
 * accident in that task, and accidental safety ends the day somebody changes the shape. Nothing here
 * is accidental once it is asserted.
 */
@DisplayName("PartyName (P1-TSK-005)")
class PartyNameTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the name never appears in its own toString")
    void theNameIsMasked() {
        PartyName name = new PartyName("Ada Lovelace");

        assertThat(name.toString())
                .as("RESTRICTED-PII must not reach a log line through a generated toString")
                .doesNotContain("Ada")
                .doesNotContain("Lovelace")
                .isEqualTo("PartyName[***]");
        assertThat(name.value()).as("code that has decided it needs it can still get it")
                .isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("a Party does not print its name either")
    void thePartyDoesNotPrintTheName() {
        // The aggregate's toString is the likelier accident: an exception message or a log line
        // interpolating the object, not the value.
        Party party = Party.register(IDS, CLOCK, PartyKind.PERSON, new PartyName("Ada Lovelace"));

        assertThat(party.toString())
                .doesNotContain("Ada")
                .doesNotContain("Lovelace")
                .contains(party.id().toString());
    }

    @Test
    @DisplayName("real names are accepted, because a rule narrow enough to be a control is wrong")
    void realNamesAreAccepted() {
        // Names contain apostrophes, hyphens, spaces, accents, non-Latin scripts, and any number of
        // parts including one. A charset restriction here would reject legitimate customers, which
        // is a worse outcome than the thing it would be guarding against - and the injection concern
        // is handled where it arises: by the mask above, and by parameter binding (ADR-0033).
        for (String name :
                new String[] {
                    "O'Brien",
                    "Ada",
                    "Jean-Luc Picard",
                    "Ægir Þórsson",
                    "李雷",
                    "Иван Петров",
                    "María José de la Cruz-Fernández"
                }) {
            assertThatCode(() -> new PartyName(name))
                    .as("%s is somebody's actual name", name)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a control character is refused, and that is not a charset restriction")
    void controlCharactersAreRefused() {
        // Added by the `P1-TSK-006` gate, after a probe found that a NUL in a display name reached
        // PostgreSQL - which cannot store one in a text column at all - and surfaced as a 500: a
        // caller's mistake reported as ours. The rule is narrow on purpose. It excludes five
        // Unicode categories that contain no character of any name, so it rejects nothing the test
        // above accepts, and that is exactly what separates it from the charset restriction this
        // type refuses.
        for (int hostile : new int[] {0x00, 0x0A, 0x0D, 0x09, 0x08, 0x7F, 0x202E, 0x200D}) {
            String name = "Ada" + (char) hostile + "Lovelace";
            assertThatThrownBy(() -> new PartyName(name))
                    .as("U+%04X is in nobody's name", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the rejection does not repeat the name it refused")
    void theRejectionDoesNotEchoTheName() {
        // A RESTRICTED-PII value, and an exception message reaches a log line (INV-AUD-02). Naming
        // the offending character would echo the input one code point at a time.
        assertThatThrownBy(() -> new PartyName("Ada" + (char) 0x00 + "Lovelace"))
                .hasMessageNotContaining("Ada")
                .hasMessageNotContaining("Lovelace");
    }

    @Test
    @DisplayName("an emoji is a code point, not a surrogate, so it is still a legal name")
    void emojiSurviveTheSurrogateExclusion() {
        // The exclusion covers unpaired surrogates. A valid pair is one code point in the Symbol
        // category, and asserting so is what stops a well-meant tightening from breaking a name
        // that contains one.
        String withEmoji = "Ada " + new String(Character.toChars(0x1F600));
        assertThatCode(() -> new PartyName(withEmoji)).doesNotThrowAnyException();

        // And a lone surrogate is refused - the half that would otherwise be an untested claim.
        String loneSurrogate = "Ada" + (char) 0xD83D;
        assertThatThrownBy(() -> new PartyName(loneSurrogate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("blank and over-long are refused, because those are the two the column enforces")
    void theBoundsAreEnforced() {
        assertThatThrownBy(() -> new PartyName("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartyName("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PartyName(null)).isInstanceOf(NullPointerException.class);

        assertThatCode(() -> new PartyName("a".repeat(PartyName.MAX_LENGTH)))
                .as("exactly at the bound must be accepted, or the column and the type disagree")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new PartyName("a".repeat(PartyName.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
