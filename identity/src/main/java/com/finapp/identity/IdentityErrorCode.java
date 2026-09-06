package com.finapp.identity;

import com.finapp.platform.api.ErrorCode;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code identity.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against these
 * strings, so one is deprecated rather than renamed.
 */
public enum IdentityErrorCode implements ErrorCode {

    /**
     * An authentication attempt did not succeed. <strong>One code for every reason.</strong>
     *
     * <p>Unknown identity, wrong password, suspended identity, an identity with no credential yet -
     * all four produce a byte-identical response. That is what {@code INV-IDN-07} requires, and the
     * response body is only half of it: {@code P1-TSK-008} made every one of those paths perform a
     * full Argon2id verification, so they are indistinguishable by <em>timing</em> as well. A
     * suspended account answering instantly would tell an attacker both that it exists and that it
     * is suspended.
     *
     * <p><strong>Why not {@code api.Unauthenticated}.</strong> That code is defined as
     * <em>"Authentication is required"</em> - a protected route reached with no credentials at all.
     * Here credentials <em>were</em> presented and were not accepted. They are different facts, a
     * client handles them differently ("log in" versus "check what you typed"), and giving one
     * string two meanings is exactly what {@code ERROR_CONTRACT.md} §4 forbids.
     *
     * <p><strong>401 and not 403.</strong> 403 means authenticated and not permitted, which
     * presupposes an established identity - and presupposing one here would leak that there is one.
     */
    AUTHENTICATION_FAILED(
            "identity.AuthenticationFailed", 401, "Authentication failed.");

    private final String code;
    private final int status;
    private final String title;

    IdentityErrorCode(String code, int status, String title) {
        this.code = code;
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
