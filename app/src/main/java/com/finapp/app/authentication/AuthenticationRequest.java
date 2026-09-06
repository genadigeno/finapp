package com.finapp.app.authentication;

import com.finapp.sharedkernel.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/authentications}.
 *
 * <h2>The secret is wrapped, and the build made that decision rather than taste</h2>
 *
 * <p>The first version of this record declared {@code String password}, and
 * {@code secretsAreWrapped} rejected it - twice, once for the component and once for the accessor a
 * serialiser would read. <strong>The rule was right, and this is the most important subject it has
 * had.</strong> A record's generated {@code toString} prints every component, so
 * {@code log.info("authenticating {}", request)} would print a customer's password: no getter call,
 * no concatenation, nothing a reviewer stops at. This DTO is the exact place a plaintext password
 * enters the platform, which makes it the first place it could leave.
 *
 * <p>Wrapping it required a Jackson <em>deserialiser</em> ({@code SensitiveSerialization}), the
 * symmetric half of the masking serialiser {@code P0-TSK-030} added. That is the cost of the rule
 * being right, and it is small.
 *
 * <h2>The two fields are validated to different depths, deliberately</h2>
 *
 * <p>The <strong>login identifier</strong> is validated exactly as registration validates it -
 * length and charset, rejected as {@code api.ValidationFailed} naming the field. That discloses the
 * <em>format</em> the platform accepts, which is public, and says nothing about whether any account
 * exists.
 *
 * <p>The <strong>password</strong> carries {@code @NotNull} and nothing else. Bean Validation
 * cannot see inside the wrapper, and that turns out not to matter:
 *
 * <ul>
 *   <li><strong>No minimum.</strong> {@code RawPassword} enforces one, and rejecting a short
 *       password here with a {@code 422} would give this endpoint a <em>second response shape</em>
 *       and a fast path that skips the derivation. A guess of {@code "abc"} would answer
 *       differently, and sooner, than a guess of {@code "abcdefgh"}. Neither difference discloses
 *       whether an account exists - but {@code INV-IDN-07} is defeated by paths nobody enumerated,
 *       and the way to have none is for this endpoint to have exactly two outcomes. So a password
 *       this platform could never have stored is an ordinary authentication failure, and it costs a
 *       full derivation on the way there.
 *   <li><strong>No maximum, because the real bound is elsewhere.</strong> The tempting reason for
 *       one is denial of service - and Argon2id's cost does not vary with input length, since the
 *       input is absorbed into a fixed-size state. What actually bounds the work is the global
 *       request-size limit {@code P0-TSK-025} enforces, which rejects an oversized body
 *       <em>without reading it</em>. A {@code @Size} here would be a second, weaker copy of a
 *       control that already exists.
 *   <li><strong>{@code @NotNull}, though.</strong> An absent field is a mistake about the shape of
 *       the request, not about any account, so a {@code 422} naming it is correct - and without it
 *       the component would be null and the service would fail with a 500, turning the caller's
 *       mistake into our fault ({@code ERROR_CONTRACT.md} §3).
 * </ul>
 *
 * @param loginIdentifier what the person types to log in
 * @param password their secret. Never logged, never stored, never echoed
 */
public record AuthenticationRequest(
        @NotBlank
                @Size(min = LOGIN_MIN, max = LOGIN_MAX)
                @Pattern(regexp = LOGIN_CHARSET)
                String loginIdentifier,
        @NotNull Sensitive<String> password) {

    /**
     * Mirrors {@code LoginIdentifier}, as {@code RegistrationRequest} does and for the same reason:
     * an annotation needs a compile-time constant.
     *
     * <p>{@code AuthenticationRequestTest} asserts they still match, and sweeps every code point the
     * boundary admits to prove the domain admits it too. That test exists because the completion
     * gate checked this sentence and found it was claiming a test that had never been written.
     */
    static final int LOGIN_MIN = 3;

    static final int LOGIN_MAX = 64;

    static final String LOGIN_CHARSET = "[A-Za-z0-9._-]+";
}
