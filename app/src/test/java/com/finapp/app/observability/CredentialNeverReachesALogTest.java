package com.finapp.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RawPassword;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A real credential type, logged the careless way, reaches the log as a mask (`P1-TSK-009`).
 *
 * <h2>Why this is not a duplicate of {@code LogRedactionTest}</h2>
 *
 * <p>That suite proves the <em>mechanism</em> - {@code Sensitive} masks, and an unwrapped field
 * does not - using a synthetic {@code record Credentials(String, Sensitive&lt;String&gt;)} fixture
 * written for the purpose. Every assertion protecting {@code INV-AUD-02} to date has been against
 * a fixture. This is the invariant's <strong>first real subject</strong>: the types that actually
 * hold a customer's password on the authentication path.
 *
 * <p>The distinction matters because a fixture is written by somebody who already knows the rule.
 * {@code RawPassword} and {@code Credential} were written to do a job, and whether they happen to
 * be safe when logged is a fact about them rather than about the wrapper.
 *
 * <h2>Emitted output, not {@code toString()}</h2>
 *
 * <p>{@code CredentialNeverLeaksDatabaseTest} asserts what these types render in memory.
 * <strong>That is a weaker claim than this one.</strong> Between {@code toString()} and the bytes
 * a log shipper takes there is an ECS encoder, a structured-logging layer that can lift an argument
 * into a top-level field, and Jackson's fallback for a type it cannot introspect - each of which
 * has been the source of a real defect in this repository. What matters is what leaves the process.
 */
@Tag("slice")
@SpringBootTest(properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("a credential never reaches a log line (P1-TSK-009)")
class CredentialNeverReachesALogTest {

    private static final Logger log = LoggerFactory.getLogger(CredentialNeverReachesALogTest.class);

    /** Distinctive, so finding it anywhere in the output is proof rather than coincidence. */
    private static final String PASSWORD = "zqx-log-sink-marker-6b204e-never-emitted";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap parameters: this suite is about what is printed, not about how long deriving takes. */
    private static final Argon2PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    @Test
    @DisplayName("a RawPassword logged whole prints a mask")
    void aRawPasswordDoesNotPrintItself(CapturedOutput output) {
        RawPassword password = RawPassword.of(PASSWORD);

        // Exactly how the leak happens in real code: the object, the lazy `{}`, no getter call and
        // nothing a reviewer would stop at. Something more careful would prove a property nobody
        // violates.
        log.info("verifying {}", password);

        assertThat(output.getAll())
                .as("the line must have been written, or every assertion below is vacuous")
                .contains("verifying");
        assertThat(output.getAll())
                .as("INV-AUD-02: no emitted representation may contain the password")
                .doesNotContain(PASSWORD);
        assertThat(output.getAll())
                .as("and a reader must be able to tell a withheld value from an absent one")
                .contains(Sensitive.MASK);
    }

    @Test
    @DisplayName("a Credential logged whole prints neither the password nor the derivation")
    void aCredentialPrintsNeitherThePasswordNorTheDerivation(CapturedOutput output) {
        Credential credential =
                Credential.forPassword(
                        IDS,
                        CLOCK,
                        IdentityId.of(IDS.next()),
                        CredentialType.PASSWORD,
                        DERIVER,
                        RawPassword.of(PASSWORD));

        log.info("upgrading {}", credential);

        assertThat(output.getAll()).as("precondition").contains("upgrading");
        assertThat(output.getAll())
                .as("the password, obviously")
                .doesNotContain(PASSWORD);
        // The derivation is the sharper half. It is not the password, so a rule about passwords
        // would let it through - and it is offline-crackable material, which is precisely the thing
        // an attacker with a log archive wants. ADR-0032 is why it exists at all; INV-AUD-02 is why
        // it must not be published.
        assertThat(output.getAll())
                .as("nor the derivation, which is offline-crackable material and not a secret's mask")
                .doesNotContain(credential.credentialDerivation().expose());
    }

    @Test
    @DisplayName("the test can see a leak: an unwrapped password does appear")
    void theTestWouldNoticeALeak(CapturedOutput output) {
        // The negative control, and it is not optional. Without it a capture that silently caught
        // nothing - a changed appender, a filter set to WARN, a context that failed to start - would
        // make every "does not contain" above pass while reading an empty string.
        //
        // This is also the only place in this suite that unwraps, and it does so in test code:
        // `SecretsAreUnwrappedInOnePlaceTest` pins the production sites and does not constrain a
        // test that exists to prove a leak would be visible.
        log.info("verifying {}", RawPassword.of(PASSWORD).expose());

        assertThat(output.getAll())
                .as("an unwrapped password DOES reach the log, which is why the wrapper exists")
                .contains(PASSWORD);
    }

    @Test
    @DisplayName("a rejected password is not echoed by the message that rejects it")
    void aRejectionDoesNotEchoWhatItRefused(CapturedOutput output) {
        // The likeliest accidental disclosure, and it defeats the wrapper completely: a validation
        // message is a String by the time it exists, so `Sensitive` is not in the path at all.
        String tooShort = "zqx-6b2";

        try {
            RawPassword.of(tooShort);
        } catch (IllegalArgumentException refused) {
            log.warn("refused a credential", refused);
        }

        assertThat(output.getAll()).as("precondition").contains("refused a credential");
        assertThat(output.getAll())
                .as("a message that helpfully repeats what it refused puts it in the log")
                .doesNotContain(tooShort);
    }
}
