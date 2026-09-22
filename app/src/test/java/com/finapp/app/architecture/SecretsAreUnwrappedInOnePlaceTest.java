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
 * set below being a short, individually justified list rather than a codebase. (This sentence
 * carried a count - "seven methods" - that had silently gone stale as entries arrived; corrected
 * count-free by `P5-TSK-003`, the stale-count class this repository keeps meeting.)
 */
@Tag("architecture")
@DisplayName("a secret is unwrapped only where this test says it may be (P1-TSK-009)")
class SecretsAreUnwrappedInOnePlaceTest {

    /** The two ways a wrapped secret becomes a bare value. */
    private static final Set<String> UNWRAPPING_METHODS =
            Set.of(
                    "com.finapp.sharedkernel.security.Sensitive.expose()",
                    // P6-TSK-002: the merchant API key's secret, the platform's fourth
                    // credential kind. Named here so unwrapping it is governed by the same
                    // rule as a password or an instrument token, rather than being invisible
                    // to it.
                    "com.finapp.merchant.MerchantApiKeySecret.exposeOnceForIssuance()",
                    "com.finapp.identity.RawPassword.expose()",
                    "com.finapp.payments.InstrumentToken.expose()",
                    "com.finapp.paymentmethods.TokenReference.expose()",
                    "com.finapp.paymentmethods.TokenisationGrant.expose()");

    /**
     * The production classes permitted to unwrap a secret.
     *
     * <p>Nearly all are in {@code identity}, and that is the property worth reading off this
     * list: a plaintext exists inside the owning module's call chain, and every entry elsewhere
     * carries its own justification below. Adding an entry is a decision; adding one in another
     * module should be a conversation, because it means a secret has left the module that owns
     * it. (This sentence too carried a stale count - "all six" - corrected count-free by
     * `P5-TSK-003`.)
     */
    private static final Set<String> PERMITTED =
            Set.of(
                    // P6-TSK-002. THE FOUR ENTRIES BELOW ARE THE FIRST OUTSIDE `identity`
                    // TO HANDLE A CREDENTIAL, and this rule's own message calls that out:
                    // "an entry outside identity means a credential has left the module that
                    // owns credentials". It has, and the decision is deliberate rather than
                    // drifted - a merchant API key is not an identity's credential. It
                    // authenticates a COUNTERPARTY, its lifecycle is the merchant
                    // relationship's (a closed merchant cannot hold one; a suspended
                    // merchant's stops working through the lookup's join), and putting it in
                    // `identity` would make that module own the commercial relationship's
                    // state. What travels instead are the DISCIPLINES: hashed at rest,
                    // derivation recorded, shown once, constant-time verification, never
                    // recoverable - each inherited explicitly and each named here.
                    //
                    // Mints the secret and verifies a presented one. The only component that
                    // sees the plaintext at all, and the analogue of Argon2PasswordDeriver.
                    "com.finapp.merchant.MerchantApiKeySecret",
                    // Holds the HASH wrapped and compares against it; surrenders it to no
                    // caller (the accessor is package-private).
                    "com.finapp.merchant.MerchantApiKey",
                    // Writes the hash - not the secret - to its column, and wraps on read.
                    // JdbcCredentialStore's role exactly.
                    "com.finapp.merchant.JdbcMerchantApiKeyStore",
                    // NOT MerchantApiKeys, the issuing command - and its absence is the
                    // design rather than an oversight. It carries the minted plaintext to the
                    // boundary WRAPPED (MerchantApiKeySecret.plaintext()), so the component
                    // with the widest reach on this path never holds a bare secret. Three
                    // entries rather than four, bought for one accessor.
                    // Unwraps ONCE, at the boundary that must transmit the freshly minted
                    // secret in the issuance response - AuthenticationService's role, and the
                    // only place a merchant key's plaintext leaves its wrapper.
                    "com.finapp.app.merchant.MerchantApiKeyOperations",
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
                    "com.finapp.app.mfa.MfaEnrolmentApplicationService",
                    // P1-TSK-018. A step-up ROTATES the session, so the response must carry the
                    // replacement token or the customer is logged out at the moment they proved a
                    // second factor. The one unwrap outside `identity` besides the provisioning
                    // URI, and for the same reason: a value whose purpose is to be transmitted.
                    "com.finapp.app.mfa.MfaChallengeApplicationService",
                    // P1-TSK-027. The SAME case one endpoint over: a login now issues a session,
                    // and the token exists exactly once - here, on its way into the response. It
                    // cannot be handed back later, because Session holds only the hash.
                    //
                    // The third unwrap outside `identity`, and the reason that is not a slope: all
                    // three are a session or enrolment token being handed to the one caller
                    // entitled to it, at the boundary, in the response to the request that created
                    // it. An entry for anything read out of storage would be a different claim.
                    "com.finapp.app.authentication.AuthenticationService",
                    // P1-TSK-033. The SAME case a fourth time: a password change ROTATES the
                    // caller's session (P1-TSK-015's fixation defence), so the response must carry
                    // the replacement token or the customer is logged out by their own password
                    // change. A session token being handed to the one caller entitled to it, at
                    // the boundary, in the response to the request that created it - the exact
                    // claim the three entries above make, and no other unwrap happens here: both
                    // passwords cross this class still wrapped, becoming RawPassword without an
                    // expose() (the RegistrationService shape).
                    "com.finapp.app.credential.ChangePasswordService",
                    // P1-TSK-023. All three inside `identity`, which is the property this list
                    // exists to keep true - and the recovery boundary was DELIBERATELY built to
                    // keep them there. The controllers pass Sensitive<String> straight through from
                    // the request body: an earlier version unwrapped in RecoveryController and
                    // ContactChannelController, this guard refused it, and the rule had the better
                    // argument, because a plaintext in `app` is a plaintext outside the module that
                    // owns secrets.
                    //
                    // Writes the token HASH to its column and compares one on lookup - never the
                    // token, which does not reach the database at all.
                    "com.finapp.identity.JdbcContactChannelStore",
                    "com.finapp.identity.JdbcRecoveryRequestStore",
                    // Reads a wrapped address to normalise and validate it. RESTRICTED-PII rather
                    // than a secret, and it is here for the same reason: the wrapper is what keeps
                    // it out of a log, and the one place it comes off is the type that owns it.
                    "com.finapp.identity.EmailAddress",
                    // Hashes the token it holds, and hands its one copy to a notifier that does not
                    // exist yet (`SessionToken`'s shape, for a shorter-lived value).
                    "com.finapp.identity.SingleUseToken",
                    // P5-TSK-003. The instrument token, wrapped on the RawPassword idiom:
                    // validates its charset at construction and re-exposes for the wire. Its
                    // expose() is itself an unwrapping method above, so every caller is an entry
                    // here - the RawPassword shape exactly.
                    "com.finapp.payments.InstrumentToken",
                    // P5-TSK-003. The one production caller of InstrumentToken.expose(): the
                    // token coming off onto the provider wire, which is the one place it
                    // legitimately goes (INV-PAY-02 - the token IS what we hold instead of raw
                    // card data, and the provider is who it is FOR). The fourth-and-unlike
                    // unwrap outside `identity`: not a session token handed back to its owner,
                    // but the same claim one boundary over - a value whose purpose is to be
                    // transmitted, unwrapped at the transmitting edge and nowhere else.
                    "com.finapp.payments.SimulatedCardPspAdapter",
                    // P5-TSK-004. The stored instrument's token, wrapped on the same idiom -
                    // the InstrumentToken mechanism RESTATED in paymentmethods, because the
                    // PCI module sees no business sibling and cannot import it (the
                    // DocumentCipher restated-mechanism precedent). Validates its charset and
                    // PAN-shape refusal at construction and re-exposes; its expose() is an
                    // unwrapping method above, so every caller is an entry here.
                    "com.finapp.paymentmethods.TokenReference",
                    // P5-TSK-004. The token's one production caller besides its type: writing
                    // the column and binding the converge read's parameter - the store IS
                    // where the stored token must exist bare, and DatabaseFailure.describe
                    // keeps it out of every failure message.
                    "com.finapp.paymentmethods.JdbcPaymentMethodStore",
                    // P5-TSK-005. The one-time tokenisation grant, wrapped end to end on the
                    // same idiom: validates its shape - the card-number refusal included,
                    // INV-PAY-02 at the surface - and re-exposes; its expose() is an
                    // unwrapping method above, so every caller is an entry here.
                    "com.finapp.paymentmethods.TokenisationGrant",
                    // P5-TSK-005. The grant's one production caller: onto the exchange wire,
                    // which is the one place it legitimately goes - the SimulatedCardPspAdapter
                    // claim one boundary over, for a shorter-lived value.
                    "com.finapp.paymentmethods.SimulatedTokenisationAdapter",
                    // P5-TSK-009. The registered bridge across the PCI boundary: the stored
                    // TokenReference comes off in ONE expression and is immediately re-wrapped
                    // as the InstrumentToken the provider port carries - the port app
                    // implements because payments cannot see paymentmethods (INV-PAY-02).
                    // Nothing is held bare, nothing is logged, and a second bridging site is
                    // a review question by construction.
                    "com.finapp.app.payments.JdbcPaymentParticipants");

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
