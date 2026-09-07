package com.finapp.app.mfa;

/**
 * The session a successful step-up produced (`P1-TSK-018`).
 *
 * <h2>This carries a session token, and there is no design in which it does not</h2>
 *
 * <p>Elevation **rotates the identifier** (`P1-TSK-015`): the presented session is revoked and a new
 * one issued. A response that did not hand back the replacement would **log the customer out** at
 * the exact moment they proved a second factor.
 *
 * <p>That is different from `P1-TSK-017`'s `sharedSecret`, which was removed because the
 * provisioning URI already carried it. Here there is no second artefact - the token exists only
 * here, once.
 *
 * <h2>Why the field is not `Sensitive`</h2>
 *
 * <p>The platform's serialiser renders `Sensitive` as a mask (`P0-TSK-030`), so a wrapped field
 * would hand the client «redacted» and the session would be unreachable. Wrapping is not available
 * for a value whose purpose is to be transmitted.
 *
 * <p>The name is `sessionToken` - what it is - rather than something chosen to avoid a build rule.
 * If `secretsAreWrapped` refuses it, that is a decision to make out loud rather than to dodge, and
 * `P1-TSK-027` inherits whatever shape this settles on.
 */
public record ElevatedSession(
        String sessionToken, String assurance, java.time.Instant expiresAt) {

    /**
     * Masked, and this is the half of `secretsAreWrapped` that still applies.
     *
     * <p>The rule guards two harms and they separate here: a serialiser reading this field is the
     * entire purpose, while a record's **generated `toString`** printing a live session token into
     * a log is exactly the accident `INV-AUD-02` exists to stop - no getter call, no concatenation,
     * nothing a reviewer stops at.
     *
     * <p>So the exemption in `NoUnwrappedSecretRulesTest` permits the *serialisation* and this
     * override closes the *logging*, with a test asserting it. An exemption that permitted both
     * would be a hole.
     */
    @Override
    public String toString() {
        return "ElevatedSession[assurance="
                + assurance
                + ", expiresAt="
                + expiresAt
                + ", sessionToken="
                + com.finapp.sharedkernel.security.Sensitive.MASK
                + "]";
    }
}
