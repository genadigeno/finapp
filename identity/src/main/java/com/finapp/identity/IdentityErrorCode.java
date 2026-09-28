package com.finapp.identity;

import com.finapp.platform.api.ErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * The failures this module reports to a client.
 *
 * <p>Namespaced {@code identity.*} so two modules cannot give one string two meanings
 * ({@code ERROR_CONTRACT.md} §4), and permanent: a client's error handling is written against these
 * strings, so one is deprecated rather than renamed.
 */
@RequiredArgsConstructor
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
            "identity.AuthenticationFailed", 401, "Authentication failed."),

    /**
     * The session is valid but not assured enough for this operation (`P1-TSK-018`).
     *
     * <p><strong>Distinct from a bare 403, and the distinction is what a client acts on.</strong>
     * <em>"You may never do this"</em> and <em>"prove a second factor and try again"</em> call for
     * different behaviour: the first is an error to show a person, the second is a step-up flow to
     * start. A single {@code api.Forbidden} would make them indistinguishable and every client would
     * guess.
     *
     * <p>403 rather than 401, because the caller <em>is</em> authenticated. A 401 would invite a
     * client to discard a perfectly good session and send the customer back to a password prompt.
     */
    ASSURANCE_REQUIRED(
            "identity.AssuranceRequired",
            403,
            "This operation requires a stronger authentication."),

    /**
     * A verification would give the identity a second verified contact channel of that kind
     * (`X-TSK-004`, {@code INV-IDN-06}).
     *
     * <p><strong>Refused, not replaced.</strong> The channel already verified is where recovery goes.
     * Adding a channel needs only a session, so letting a newly verified one displace it would let
     * whoever holds a stolen password redirect the account's recovery to a mailbox of their own.
     *
     * <p><strong>409</strong>: the request is well-formed and its token live; it conflicts with the
     * account's current state, and repeating it changes nothing until that state does. It was a
     * {@code 500} until {@code X-TSK-004} - the database's refusal reported as the platform's
     * failure, which invites a client to retry something that can never succeed.
     *
     * <p><strong>Not folded into {@code api.Forbidden}</strong>, which answers every reason a token
     * is refused. That uniformity stops a caller without a live token from learning whether a
     * verification is pending. This refusal is reachable only by presenting one, so it discloses
     * nothing the uniform answer protects - and the customer who typed a second address learns why.
     */
    VERIFIED_CHANNEL_ALREADY_EXISTS(
            "identity.VerifiedChannelAlreadyExists",
            409,
            "The account already has a verified contact channel of this kind.");

    private final String code;
    private final int status;
    private final String title;

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
