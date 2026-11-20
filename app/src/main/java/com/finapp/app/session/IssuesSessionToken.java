package com.finapp.app.session;

/**
 * A response that issues a session token (ADR-0156 decision 3, `X-TSK-029`): sign-in, step-up and password change.
 *
 * <p>{@link SessionCookieAdvice} reads it to carry the token as the cookie when the request opted in, and answers
 * {@link #withoutSessionToken()} in its place - so a door that issues a token by returning this type cannot forget the cookie.
 */
public interface IssuesSessionToken {

    /** The token, or {@code null} once it has been moved to the cookie. */
    String sessionToken();

    /** This answer with the token removed, for a response whose token travels in the cookie. */
    IssuesSessionToken withoutSessionToken();
}
