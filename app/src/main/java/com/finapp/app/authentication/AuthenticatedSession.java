package com.finapp.app.authentication;

/**
 * The session a successful authentication produced (`P1-TSK-027`).
 *
 * <h2>The shape `ElevatedSession` settled, inherited rather than re-argued</h2>
 *
 * <p>`P1-TSK-018` said so in as many words: *"`P1-TSK-027` inherits whatever shape this settles
 * on."* Same three members, same reason for each - the token cannot be `Sensitive`, because the
 * platform's serialiser renders that as a mask (`P0-TSK-030`) and the client would receive
 * «redacted» for the one value whose whole purpose is to be transmitted.
 *
 * <h2>Why a second record rather than reusing that one</h2>
 *
 * <p>Two responses with identical members are still two responses. Reusing `ElevatedSession` would
 * make `authentication` depend on `mfa` for a type whose **name is a claim about how the session
 * was obtained** - and this one was not elevated. It would also publish `ElevatedSession` as the
 * schema of a login response, so a client reading the contract would be told a password login
 * returns an elevated session, which is exactly the assurance confusion ADR-0030 exists to prevent.
 *
 * <h2>`assurance` is published, and that is the point of publishing it</h2>
 *
 * <p>Always `PASSWORD` here today. A client that must know whether to prompt for a second factor
 * reads this rather than inferring it from which endpoint answered - which is the inference that
 * stops being true the moment a third way to obtain a session exists.
 *
 * <p>It discloses nothing: the caller just authenticated, so *"you have a password-assurance
 * session"* tells them what they already know. In particular it does **not** say whether MFA is
 * enrolled - the level is a property of this session, not of the identity - so `INV-IDN-07` is
 * untouched.
 */
public record AuthenticatedSession(
        String sessionToken, String assurance, java.time.Instant expiresAt) {

    /**
     * Masked, closing the half of `secretsAreWrapped` that still applies.
     *
     * <p>The rule guards two harms and they separate here exactly as they do for `ElevatedSession`:
     * a serialiser reading this field is the entire purpose, while a record's **generated
     * `toString`** printing a live session token into a log is the accident `INV-AUD-02` exists to
     * stop - no getter call, no concatenation, nothing a reviewer stops at. The exemption permits
     * the serialisation; this override closes the logging, and a test asserts it.
     */
    @Override
    public String toString() {
        return "AuthenticatedSession[assurance="
                + assurance
                + ", expiresAt="
                + expiresAt
                + ", sessionToken="
                + com.finapp.sharedkernel.security.Sensitive.MASK
                + "]";
    }
}
