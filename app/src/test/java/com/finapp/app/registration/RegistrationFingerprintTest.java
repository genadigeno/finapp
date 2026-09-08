package com.finapp.app.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LoginIdentifier;
import com.finapp.party.PartyName;
import com.finapp.platform.idempotency.RequestFingerprint;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the idempotency mechanism considers "the same request" (`P1-TSK-006`, {@code INV-IDEM-03}).
 *
 * <p>{@code RequestFingerprint} leaves the choice of significant fields to each command, because
 * including a timestamp would make every retry look different and excluding a significant field
 * would let a different request replay another's response. This is that choice, asserted.
 */
@DisplayName("registration request fingerprint (P1-TSK-006)")
class RegistrationFingerprintTest {

    @Test
    @DisplayName("the same registration fingerprints identically, however it was typed")
    void normalisationHappensBeforeHashing() {
        // The retry case. A client resending after a lost response may not reproduce the exact
        // casing, and a fingerprint taken before normalisation would call that a different request
        // and answer 409 to a caller doing exactly the right thing.
        assertThat(fingerprintOf("Ada.L", "Ada Lovelace"))
                .isEqualTo(fingerprintOf("ada.l", "Ada Lovelace"));
    }

    @Test
    @DisplayName("a changed name is a different request, and is refused rather than replayed")
    void aChangedFieldChangesTheFingerprint() {
        assertThat(fingerprintOf("ada.l", "Ada Lovelace"))
                .isNotEqualTo(fingerprintOf("ada.l", "Ada L"));
        assertThat(fingerprintOf("ada.l", "Ada Lovelace"))
                .isNotEqualTo(fingerprintOf("ada.k", "Ada Lovelace"));
    }

    @Test
    @DisplayName("the field boundary cannot be moved, which is what a canonical form is for")
    void fieldsCannotRunTogether() {
        // Without length prefixes, ("ab","c") and ("a","bc") concatenate to the same bytes, and two
        // different registrations would be indistinguishable to INV-IDEM-03. This is the reason the
        // canonical form is a canonical form rather than a concatenation.
        assertThat(fingerprintOf("abc.x", "Ada Lovelace"))
                .isNotEqualTo(fingerprintOf("abc.x", "Ada Lovelace "));
        assertThat(new String(RegistrationService.canonicalForm(login("ab.cd"), name("ef"))))
                .isNotEqualTo(new String(RegistrationService.canonicalForm(login("ab.c"), name("def"))));
    }

    @Test
    @DisplayName("the scope is part of the canonical form")
    void theScopeIsInTheCanonicalForm() {
        // So a fingerprint computed for one command can never be mistaken for another command's,
        // even if a future command hashed the same two fields.
        assertThat(new String(RegistrationService.canonicalForm(login("ada.l"), name("Ada"))))
                .startsWith(RegistrationService.SCOPE);
    }

    /**
     * No credential reaches the fingerprint, asserted structurally because there is nothing to vary.
     *
     * <p>{@code canonicalForm} takes no password, so a value-level test cannot express this at all -
     * the only way to break the property is to change the signature, so the signature is what is
     * asserted. A mutation adding the password is caught here.
     *
     * <p><strong>Why it matters more than an ordinary field choice.</strong>
     * {@code RequestFingerprint} is a single-round SHA-256 and
     * {@code idempotency_record.request_fingerprint} is durable, so hashing a body containing a
     * password would store an offline-crackable derivation of every registration password for as
     * long as the record is retained - {@code INV-IDN-01} defeated by the idempotency mechanism
     * rather than by the credential store.
     *
     * <p>{@code P1-TSK-026} added the password to the request and deliberately did <em>not</em>
     * add it here. The consequence is recorded on {@code RegistrationService.canonicalForm}: a
     * retry with the same key and a different password replays rather than being refused as an
     * {@code INV-IDEM-03} conflict, and {@code RegistrationCredentialDatabaseTest} asserts that
     * behaviour end to end.
     */
    @Test
    @DisplayName("the canonical form cannot see a password, so it cannot store a derivation of one")
    void noCredentialReachesTheFingerprint() throws Exception {
        Class<?>[] parameters =
                RegistrationService.class
                        .getDeclaredMethod("canonicalForm", LoginIdentifier.class, PartyName.class)
                        .getParameterTypes();

        assertThat(parameters).containsExactly(LoginIdentifier.class, PartyName.class);
        assertThat(Arrays.stream(RegistrationService.class.getDeclaredMethods())
                        .filter(method -> "canonicalForm".equals(method.getName()))
                        .count())
                .as("a second overload taking a secret would slip past the assertion above")
                .isEqualTo(1);
    }

    private static byte[] fingerprintOf(String loginIdentifier, String displayName) {
        return RequestFingerprint.sha256(
                        RegistrationService.canonicalForm(login(loginIdentifier), name(displayName)))
                .digest();
    }

    private static LoginIdentifier login(String value) {
        return new LoginIdentifier(value);
    }

    private static PartyName name(String value) {
        return new PartyName(value);
    }
}
