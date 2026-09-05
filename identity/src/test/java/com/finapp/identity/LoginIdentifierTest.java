package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LoginIdentifier}: normalisation, the charset, and that it never prints itself
 * (`P1-TSK-005`).
 */
@DisplayName("LoginIdentifier (P1-TSK-005)")
class LoginIdentifierTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-05T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("normalisation happens once, here, which is what makes the unique index mean what it says")
    void normalisedOnConstruction() {
        // A person who typed their identifier with a capital on Tuesday is the same person. If
        // normalisation happened at call sites instead, one that forgot would let the database
        // accept a second identity that looks identical to the first.
        assertThat(new LoginIdentifier("Ada.L").value()).isEqualTo("ada.l");
        assertThat(new LoginIdentifier("  ada.l  ").value()).isEqualTo("ada.l");
        assertThat(new LoginIdentifier("ADA.L")).isEqualTo(new LoginIdentifier("ada.l"));
    }

    @Test
    @DisplayName("an email address is refused, so the confusion cannot arrive silently")
    void anEmailAddressIsNotALoginIdentifier() {
        // The rule PHASE_1_PLAN.md §4 states, enforced where it can be. An identifier that is also
        // a contact channel cannot be changed without changing how someone logs in, nor verified
        // without blocking login - and the first person to enter an address would otherwise
        // establish a convention nobody decided.
        assertThatThrownBy(() -> new LoginIdentifier("ada@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the rejection does not echo the value it refused")
    void theRejectionDoesNotEchoTheValue() {
        // A rejection message repeating the input would put a near-miss of a real account
        // identifier into a log line - and this is the type whose whole confidentiality concern is
        // that knowing one exists tells an attacker an account exists (INV-IDN-07).
        assertThatThrownBy(() -> new LoginIdentifier("ada@example.com"))
                .hasMessageNotContaining("ada")
                .hasMessageNotContaining("example.com");
    }

    @Test
    @DisplayName("the identifier never appears in its own toString, nor in an Identity's")
    void theIdentifierIsMasked() {
        LoginIdentifier login = new LoginIdentifier("ada.l");
        Identity identity = Identity.create(IDS, CLOCK, UUID.randomUUID(), login);

        assertThat(login.toString()).isEqualTo("LoginIdentifier[***]").doesNotContain("ada");
        assertThat(identity.toString())
                .as("an identifier in a log is an enumeration aid for anyone who can read logs")
                .doesNotContain("ada")
                .contains(identity.id().toString());
        assertThat(login.value()).isEqualTo("ada.l");
    }

    @Test
    @DisplayName("the bounds are the column's bounds, at both ends and exactly")
    void theBoundsAreEnforced() {
        assertThatCode(() -> new LoginIdentifier("a".repeat(LoginIdentifier.MIN_LENGTH)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new LoginIdentifier("a".repeat(LoginIdentifier.MAX_LENGTH)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new LoginIdentifier("a".repeat(LoginIdentifier.MIN_LENGTH - 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginIdentifier("a".repeat(LoginIdentifier.MAX_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("characters that would forge a log line are refused")
    void theCharsetIsDefaultDeny() {
        // Default-deny, like the correlation identifier's: this value reaches log lines and error
        // paths, so a permissive charset is a log-injection vector. Unlike a name, a login
        // identifier is chosen from a stated set, so a restriction here rejects nothing legitimate.
        for (String hostile :
                new String[] {
                    "ada\nlogin", "ada\rlogin", "ada login", "ada;drop", "ada<script>", "ada%00", "adaé"
                }) {
            assertThatThrownBy(() -> new LoginIdentifier(hostile))
                    .as("%s must be refused", hostile.replaceAll("\\s", "_"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        for (String legitimate : new String[] {"ada.l", "ada_l", "ada-l", "ada123"}) {
            assertThatCode(() -> new LoginIdentifier(legitimate))
                    .as("%s is a reasonable handle", legitimate)
                    .doesNotThrowAnyException();
        }
    }
}
