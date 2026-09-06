package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RawPassword;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sinks a credential could reach that are not a log line (`P1-TSK-009`).
 *
 * <h2>Three sinks whose credential-carrying producer does not exist yet</h2>
 *
 * <p>{@code P1-TST-001} names five sinks. Only the log has a credential-carrying producer today:
 * no event carries a credential ({@code P1-TSK-010} owns the authentication events), no endpoint
 * takes or returns one ({@code P1-TSK-010} and {@code P1-TSK-026}), and neither a span nor a
 * metric touches one.
 *
 * <p><strong>That is a reason to write the guard now, not a reason to defer it</strong> - but the
 * artefact has to be the right one. An assertion that a credential is absent from an empty event
 * stream passes vacuously and proves nothing, which is the "green while checking nothing" failure
 * this repository has met six times. So for a sink with no producer this asserts the
 * <em>mechanism that will refuse the producer</em>, which is a claim with a subject today and is
 * load-bearing the moment the producer arrives.
 *
 * <h2>The span and metric sinks are cited, not duplicated</h2>
 *
 * <p>{@code MetricConventionTest} already fails the build on a meter tag whose value a request
 * could influence, and ADR-0017 keeps statement text off spans for the same reason. Restating
 * either here would be a second copy that drifts while looking authoritative - the reason
 * {@code API_CONVENTIONS.md} references the error-code catalogue rather than pasting it.
 */
@DisplayName("a credential reaches no emitted sink (P1-TSK-009)")
class CredentialReachesNoEmittedSinkTest {

    private static final String PASSWORD = "zqx-sink-marker-9c15b8-never-emitted";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static final Argon2PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    /**
     * Words that mean "this is a secret", in the vocabulary ADR-0019 settled.
     *
     * <p>A <strong>superset</strong> of {@code secretsAreWrapped}'s, reconciled against it by test
     * so the two cannot drift - {@code signingkey} and {@code cvv2} had already gone missing when
     * the completion gate compared them. It adds {@code derivation}, which the field rule has no
     * reason to carry: a derivation is not a secret a <em>field</em> should hold unwrapped, it is a
     * value that must never be <em>published</em>.
     *
     * <p>{@code key} is absent, for ADR-0019's stated reason: an idempotency key is not a secret,
     * it is recorded in audit on purpose, and a rule with false positives is a rule somebody turns
     * off.
     */
    private static final Set<String> SECRET_WORDS =
            Set.of(
                    "password", "passwd", "passphrase",
                    "secret", "apikey", "privatekey", "secretkey", "signingkey",
                    "credential", "credentials", "derivation",
                    "token", "bearer", "authorization",
                    "pan", "cardnumber", "cvv", "cvc", "cvv2", "pin",
                    "otp", "mfacode", "sessionid");

    @Test
    @DisplayName("this vocabulary does not drift from the one the build rule enforces")
    @SuppressWarnings("unchecked")
    void theVocabularyIsReconciledWithTheRule() throws Exception {
        // A second copy of a security vocabulary is drift waiting to happen, and this one had
        // ALREADY drifted when the completion gate compared them: `signingkey` and `cvv2` were in
        // the rule's list and missing here, so a `signingKey` property could have reached the
        // published contract while the build rule forbade the field that would hold it.
        //
        // Reconciled rather than merged, which is this repository's established answer to the same
        // shape (AuditableActionRegistryTest, ApiConventionsAreAccurateTest): the two lists stay
        // where they are, and disagreeing fails the build. Merging them would put ADR-0019's
        // vocabulary in a shared helper and make the rule's own list a redirection, which is worse
        // reading for the rule that matters most.
        java.lang.reflect.Field words =
                Class.forName("com.finapp.app.architecture.NoUnwrappedSecretRulesTest")
                        .getDeclaredField("SECRET_WORDS");
        words.setAccessible(true);
        Set<String> enforcedByTheRule = (Set<String>) words.get(null);

        assertThat(enforcedByTheRule).as("precondition: the rule's vocabulary was read").isNotEmpty();
        assertThat(SECRET_WORDS)
                .as("every word the build rule treats as a secret must also be refused in the"
                        + " published contract; this list may be wider, never narrower")
                .containsAll(enforcedByTheRule);
    }

    // -----------------------------------------------------------------
    // The event sink.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an event payload cannot carry a derivation at all")
    void anEventPayloadRefusesADerivation() {
        // Argon2's encoded form is $argon2id$v=19$m=...,t=...,p=...$<salt>$<hash> - it contains
        // '$', '=', ',' and base64's '+' and '/', none of which EventPayload permits.
        //
        // That is true TODAY, and it is true by coincidence of a rule written for a different
        // reason: EventPayload's charset exists to keep names and login identifiers out. Coincidental
        // safety is the shape P0-TSK-030 found with Jackson - correct until somebody changes the
        // shape, and silent when they do. Asserting it turns the coincidence into a stated property
        // with a failing test behind it.
        String derivation = derivedCredential().credentialDerivation().expose();

        assertThat(derivation).as("precondition: this really is the encoded form").startsWith("$argon2id$");
        assertThat(catchThrowable(() -> EventPayload.of().with("derivation", derivation)))
                .as("a derivation is offline-crackable material; an event stream is not the place")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the refusal does not repeat the derivation it refused")
    void theRefusalDoesNotEchoTheDerivation() {
        String derivation = derivedCredential().credentialDerivation().expose();

        assertThat(catchThrowable(() -> EventPayload.of().with("derivation", derivation)))
                .hasMessageNotContaining(derivation);
    }

    @Test
    @DisplayName("the limit, stated: a punctuation-free password WOULD be accepted")
    void theEventPayloadIsNotACredentialFilter() {
        // Recorded rather than glossed. EventPayload is a charset, not a secret detector: a password
        // of "hunter2" satisfies [A-Za-z0-9_-] perfectly and would be published.
        //
        // So what actually keeps credentials out of events is that no event declares a credential
        // field - a P1-TSK-010 design property, not this one. Asserting the opposite here would
        // overclaim, and a suite believed to cover more than it does is worse than one that covers
        // less: the second gets a second control, the first does not.
        assertThatCode(() -> EventPayload.of().with("probe", "hunter2"))
                .as("this passing is the limit being true, not the guard being broken")
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------
    // The response sink.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the published contract declares no property whose name says it holds a secret")
    void thePublishedContractCarriesNoSecret() {
        // Today this has no subject: the one request body carries a display name and a login
        // identifier. It becomes load-bearing the day P1-TSK-026 adds a password to registration or
        // P1-TSK-010 publishes an authentication request - which is exactly when nobody will be
        // thinking about whether a password belongs in a RESPONSE schema, an error example, or a
        // header.
        //
        // Asserted over the whole document as text rather than by walking schemas, because the
        // places a secret could appear are not only properties: a header name, a parameter, an
        // example value and a description all reach a generated client.
        String contract = readRepositoryFile("docs/api/openapi.json");

        assertThat(contract)
                .as("precondition: the document must be there to inspect")
                .contains("\"openapi\"");

        assertThat(secretNamedMembersIn(contract))
                .as(
                        "the published contract is what a generated client models and what an access"
                            + " log records; a credential-named member here is a credential in"
                            + " somebody's HTTP tooling (INV-AUD-02)")
                .isEmpty();
    }

    @Test
    @DisplayName("the contract guard is not vacuous: it sees each shape a secret could take")
    void theContractGuardHasTeeth() {
        // Four shapes, asserted individually. An aggregate probe reporting "caught" would say
        // nothing about WHICH of them it caught - the lesson the P0-TSK-032 review recorded after
        // five bypass shapes were probed together, where one catch could have masked four misses.
        assertThat(secretNamedMembersIn("{\"properties\":{\"password\":{}}}"))
                .as("a schema property")
                .containsExactly("password");
        assertThat(secretNamedMembersIn("{\"properties\":{\"cardNumber\":{}}}"))
                .as("a compound spelling, invisible without adjacent-pair matching")
                .containsExactly("cardNumber");
        assertThat(secretNamedMembersIn("{\"parameters\":[{\"name\":\"token\",\"in\":\"query\"}]}"))
                .as("a parameter, where the name is a VALUE and a key-only scan sees nothing")
                .containsExactly("token");
        assertThat(secretNamedMembersIn("{\"properties\":{\"idempotencyKey\":{},\"companyName\":{}}}"))
                .as("and the false-positive direction: ADR-0019 excluded `key` deliberately")
                .isEmpty();
    }

    @Test
    @DisplayName("the limit, stated: a credential in an example VALUE is not caught")
    void theContractGuardMatchesNamesNotValues() {
        // Recorded rather than glossed, and it is a real risk: P0-TSK-031 found this repository's
        // own documentation tripping the secret scanner because it quoted realistic example keys.
        // A name-matching guard cannot see `"example": "hunter2"`, and nothing here pretends it can
        // - the control for that is the gitleaks scan over the whole repository, which does read
        // values.
        assertThat(secretNamedMembersIn("{\"schema\":{\"example\":\"hunter2\"}}"))
                .as("this being empty is the limit being true, not the guard being broken")
                .isEmpty();
    }

    // -----------------------------------------------------------------
    // Exception messages, which are a sink in their own right.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("no rejection on the credential path repeats what it refused")
    void noRejectionEchoesItsInput() {
        // An exception message is a String by the time it exists, so Sensitive is not in the path
        // at all - which makes this the one disclosure route the wrapper structurally cannot close.
        // Three of them, each the message a caller would actually trigger.
        String tooShort = "zqx-9c1";
        String tooLong = "z".repeat(RawPassword.MAX_LENGTH + 1);

        assertThat(catchThrowable(() -> RawPassword.of(tooShort)))
                .hasMessageNotContaining(tooShort);
        assertThat(catchThrowable(() -> RawPassword.of(tooLong)))
                .hasMessageNotContaining(tooLong);
        assertThat(
                        catchThrowable(
                                () ->
                                        DERIVER.parametersOf(
                                                com.finapp.sharedkernel.security.Sensitive.of(
                                                        PASSWORD))))
                .as("asked to read parameters out of something that is not a derivation")
                .hasMessageNotContaining(PASSWORD);
    }

    // -----------------------------------------------------------------

    private static Credential derivedCredential() {
        return Credential.forPassword(
                IDS,
                CLOCK,
                IdentityId.of(IDS.next()),
                CredentialType.PASSWORD,
                DERIVER,
                RawPassword.of(PASSWORD));
    }

    private static final Pattern WORD_BOUNDARY =
            Pattern.compile("[_\\s-]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /**
     * Every name in the document: JSON keys, plus the value of every {@code "name"} member.
     *
     * <p>The second half is not tidiness. OpenAPI declares a parameter or a header as
     * {@code {"name": "token", "in": "query"}} - the secret name is a <strong>value</strong> there,
     * not a key, so a key-only scan reports a clean contract for a
     * {@code GET /v1/things?token=…}. Found by probing shapes the first version was not designed
     * against, rather than by re-reading it.
     */
    private static List<String> secretNamedMembersIn(String document) {
        List<String> offending = new ArrayList<>();
        Matcher keys = Pattern.compile("\"([A-Za-z][A-Za-z0-9_-]{2,63})\"\\s*:").matcher(document);
        while (keys.find()) {
            if (namesASecret(keys.group(1))) {
                offending.add(keys.group(1));
            }
        }
        Matcher declared =
                Pattern.compile("\"name\"\\s*:\\s*\"([A-Za-z][A-Za-z0-9_-]{2,63})\"")
                        .matcher(document);
        while (declared.find()) {
            if (namesASecret(declared.group(1))) {
                offending.add(declared.group(1));
            }
        }
        return offending;
    }

    /**
     * Camel-case word matching, so {@code companyName} is not a PAN. ADR-0019's reasoning.
     *
     * <p>Single words <strong>and adjacent pairs</strong> - the same correction this gate had to
     * make to {@code secretsAreWrapped} itself, which carried six compound entries the splitter
     * guaranteed could never match. Without pairs, {@code apiKey} and {@code cardNumber} would be
     * invisible here too, and this list would look like coverage it did not have.
     */
    private static boolean namesASecret(String name) {
        String[] words = WORD_BOUNDARY.split(name);
        for (int index = 0; index < words.length; index++) {
            if (SECRET_WORDS.contains(words[index].toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (index + 1 < words.length
                    && SECRET_WORDS.contains(
                            (words[index] + words[index + 1]).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String readRepositoryFile(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException(
                    "No settings.gradle.kts above " + Path.of("").toAbsolutePath());
        }
        try {
            return Files.readString(directory.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + relativePath, e);
        }
    }
}
