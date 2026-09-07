package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every place a secret leaves its wrapper is named here (`P1-TSK-009`, {@code INV-AUD-02}).
 *
 * <h2>Why this exists: the answer to the scrubber question</h2>
 *
 * <p>{@code secretsAreWrapped} governs what a <strong>type stores</strong>. It cannot see a secret
 * held only in a <em>local variable</em> and passed straight to a log call, because a local has no
 * declaration to inspect. That gap was recorded as debt in Phase 0 - <em>"no output scrubber for
 * text the platform does not control"</em> - with the trigger <em>"a business module logging real
 * flows"</em> and the owning phase Phase 1. The {@code identity} module is that module and
 * {@code CredentialVerifier} holds that log call, so the trigger has been reached and this task
 * has to answer it rather than carry it forward.
 *
 * <p><strong>The scrubber was rejected, and this is what replaces it.</strong> A scrubber is a
 * deny-list over emitted text, and to recognise a secret it must be <em>given</em> the secret -
 * which makes the plaintext travel further, into a filter invoked on every log statement in the
 * platform, rather than less far. It also produces exactly the false confidence ADR-0019 warns
 * about: a deny-list that misses one shape is indistinguishable from one that misses none.
 *
 * <p>The whitelist is the opposite shape and is checkable. A plaintext can only reach a log line,
 * an event, a response, a span or a metric if something first <em>unwraps</em> it, and every unwrap
 * is a call to {@code expose()} - named to be found, deliberately (see {@code Sensitive}). Pinning
 * that set means a new unwrap anywhere in the platform fails the build and forces somebody to
 * decide, rather than being a diff line nobody stops at.
 *
 * <h2>The limit, stated</h2>
 *
 * <p>This says <em>where</em> a secret may be unwrapped, not what happens to it afterwards. Inside
 * {@code identity} a plaintext could still be handed to a log call, and nothing mechanical would
 * catch it - that residual is real, is recorded in {@code CURRENT_STATE.md}, and is bounded by the
 * set below being seven methods rather than a codebase.
 */
@Tag("architecture")
@DisplayName("a secret is unwrapped only where this test says it may be (P1-TSK-009)")
class SecretsAreUnwrappedInOnePlaceTest {

    /** The two ways a wrapped secret becomes a bare value. */
    private static final Set<String> UNWRAPPING_METHODS =
            Set.of(
                    "com.finapp.sharedkernel.security.Sensitive.expose()",
                    "com.finapp.identity.RawPassword.expose()");

    /**
     * The production classes permitted to unwrap a secret.
     *
     * <p>All six are in {@code identity}, and that is the property worth reading off this list:
     * the plaintext exists inside one module's call chain and nowhere else. Adding an entry is a
     * decision; adding one in another module should be a conversation, because it means a
     * credential has left the module that owns credentials.
     */
    private static final Set<String> PERMITTED =
            Set.of(
                    // Derives and verifies. The only component that must see the plaintext at all.
                    "com.finapp.identity.Argon2PasswordDeriver",
                    // Writes the derivation - not the password - to its column.
                    "com.finapp.identity.JdbcCredentialStore",
                    // Reads the encoded form to answer "is this below policy?".
                    "com.finapp.identity.Credential",
                    // Validates length at construction, and re-exposes for the deriver.
                    "com.finapp.identity.RawPassword",
                    // Hashes the token it holds, and hands the client its one copy (`P1-TSK-013`).
                    "com.finapp.identity.SessionToken",
                    // Writes the token HASH to its column, and compares one on lookup. Not the
                    // token: that never reaches the database at all.
                    "com.finapp.identity.JdbcSessionStore",
                    // P1-TSK-017. The MFA secret is the one secret this platform can RECOVER, so
                    // it is unwrapped in more places than a password ever is - encrypting it,
                    // decrypting it, and computing a code from it. Every one is still inside
                    // `identity`, which is the property this list exists to keep true.
                    "com.finapp.identity.SecretCipher",
                    "com.finapp.identity.TotpVerifier",
                    // A TEST FIXTURE, and its appearance here is worth recording: `identity` gained
                    // testFixtures in P1-TSK-017, and this sweep covers them. That is more coverage
                    // rather than less - a fixture that mishandled a secret would be just as able to
                    // put one in a log - so the sweep is left alone and the entry is named.
                    // `Authenticator` models the customer's phone: it exists so that generating
                    // codes never becomes production API.
                    "com.finapp.identity.Authenticator",
                    // MfaEnrolmentService is deliberately NOT here, and the guard caught it when
                    // this list over-declared: it hands the wrapped secret straight to the verifier
                    // and never unwraps one. An entry with no subject is an exemption nobody can
                    // evaluate, which is why the assertion is an equality rather than a subset.
                    // And the one deliberate emission: the provisioning URI a customer scans.
                    // Bounded to one response, to the proven owner, never retrievable again.
                    "com.finapp.app.mfa.MfaEnrolmentApplicationService");

    @Test
    @DisplayName("nothing outside the named set unwraps a secret")
    void theUnwrapSitesAreExactlyTheOnesNamed() {
        Set<String> found = classesThatUnwrapASecret();

        assertThat(found)
                .as(
                        "a secret leaves its wrapper only where this test names. A new entry here is"
                            + " a decision about where a plaintext is allowed to exist; an entry"
                            + " outside `identity` means a credential has left the module that owns"
                            + " credentials (INV-AUD-02, INV-IDN-01)")
                .isEqualTo(new TreeSet<>(PERMITTED));
    }

    @Test
    @DisplayName("the guard is not vacuous: it sees every module, and it really can see an unwrap")
    void theGuardSeesEveryModule() {
        // TWO assertions, and the second is the one the completion gate had to add.
        //
        // The first version asserted `contains("identity", "platform", "sharedkernel")`, which is
        // satisfied by a sweep that has silently stopped analysing `app` - and `app` is where a
        // credential would most plausibly reach a response or a log line, since that is where the
        // controllers are. Proven by narrowing the sweep: the guard stayed green.
        //
        // That is the EXACT finding the `P0-TSK-008` review made ("the coverage guard asserted only
        // that Money was analysed, so it could not see a whole module falling out of the sweep"),
        // and the corrected idiom has been sitting two files away in every rule suite since. This
        // suite deviated from it, and deviating from a sibling idiom is what hid `secretsAreWrapped`
        // being incapable of failing in the first place.
        Set<String> analysed = new TreeSet<>();
        productionClasses().stream()
                .map(ProductionModules::of)
                .filter(java.util.Objects::nonNull)
                .forEach(analysed::add);

        assertThat(analysed)
                .as("the sweep must see every module that has production code, or it bounds the"
                        + " unwrap sites of only some of them")
                .isEqualTo(ProductionModules.onClasspathWithProductionClasses());

        assertThat(classesThatUnwrapASecret())
                .as("and if this is empty the guard has stopped working, not the platform")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------

    /**
     * Callers of {@code expose()}, by class.
     *
     * <p>Method <strong>references</strong> as well as calls. A method reference compiles to an
     * {@code invokedynamic} and carries no call site, so a rule reading only
     * {@code getMethodCallsFromSelf()} is one syntax away from being bypassed - which is exactly
     * the defect the {@code P0-TSK-013} review found in the ambient-time rule, where
     * {@code Instant::now} walked straight through it.
     */
    private static Set<String> classesThatUnwrapASecret() {
        Set<String> callers = new TreeSet<>();
        for (JavaClass javaClass : productionClasses()) {
            javaClass.getMethodCallsFromSelf().stream()
                    .filter(call -> UNWRAPPING_METHODS.contains(call.getTarget().getFullName()))
                    .forEach(call -> callers.add(outermost(javaClass)));
            javaClass.getMethodReferencesFromSelf().stream()
                    .filter(reference -> UNWRAPPING_METHODS.contains(reference.getTarget().getFullName()))
                    .forEach(reference -> callers.add(outermost(javaClass)));
        }
        return callers;
    }

    /**
     * The top-level class, so a lambda or a nested helper is attributed to the file it lives in.
     *
     * <p>Otherwise the permitted set would have to name synthetic types, which change when the code
     * is reformatted and would make this guard fail for reasons that are not about secrets.
     */
    private static String outermost(JavaClass javaClass) {
        JavaClass owner = javaClass;
        while (owner.getEnclosingClass().isPresent()) {
            owner = owner.getEnclosingClass().get();
        }
        return owner.getName();
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.finapp");
    }
}
