package com.finapp.identity;

import com.finapp.sharedkernel.security.Sensitive;

/**
 * Turns a plaintext into a credential's derivation, and tells you whether one matches.
 *
 * <h2>A port, so the algorithm is replaceable and the domain does not name a library</h2>
 *
 * <p>{@code CLAUDE.md} keeps provider and framework vocabulary out of domain types.
 * {@link Credential} therefore knows about {@link CredentialAlgorithm} and
 * {@link DerivationParameters}, and knows nothing about the class that computes them. That is not
 * abstraction for its own sake: ADR-0032 requires a second algorithm to be able to coexist with the
 * first while a store migrates, and an interface is what makes that a new implementation rather
 * than an edit to everything that derives.
 *
 * <h2>Derive outside the transaction</h2>
 *
 * <p><strong>This is the operational constraint that matters and the one nothing here can
 * enforce.</strong> Argon2id is deliberately expensive - that is the whole point of it - so a
 * caller that derives while holding a pooled connection holds it for the duration of the CPU work.
 * With a fixed pool of eight per instance (`P1-TSK-004`), a handful of concurrent registrations
 * would exhaust the pool doing arithmetic rather than database work, and the symptom would be
 * connection-timeout errors that point at the database.
 *
 * <p>So the deriver is a <em>separate collaborator</em> from {@link CredentialStore} rather than a
 * method on it: the caller can derive first and open the transaction second, and the shape of the
 * API is what suggests it. The order cannot be checked here because this task has no caller yet;
 * `P1-TSK-026` is where it becomes real, and it has a second reason to do it - deriving before any
 * insert is what makes the timing of a successful and a refused registration equivalent
 * ({@code INV-IDN-07}).
 *
 * <h2>What an implementation must not do</h2>
 *
 * <p>ADR-0032: no custom primitive, no hand-rolled comparison, no bespoke salting scheme.
 * Verification is constant-time. An implementation that compared derivations with
 * {@code String.equals} would be a timing oracle on the stored value.
 */
public interface PasswordDeriver {

    /** The algorithm this deriver produces. Recorded on every credential ({@code INV-IDN-02}). */
    CredentialAlgorithm algorithm();

    /** The parameters this deriver currently applies. Recorded on every credential it produces. */
    DerivationParameters currentParameters();

    /**
     * Derives {@code password} under {@link #currentParameters()}.
     *
     * <p>Returns the wrapper rather than the string, so the result cannot reach a log line by
     * interpolation. Two derivations of the same password differ, because the salt does - which is
     * why a derivation can never be used as a lookup key.
     */
    Sensitive<String> derive(RawPassword password);

    /**
     * Whether {@code password} produced {@code credentialDerivation}.
     *
     * <p>Constant-time with respect to the stored value. Used by {@code P1-TSK-008}; provided here
     * because a deriver that cannot verify what it derived is only half a port, and because the
     * round-trip is what this task's tests assert.
     */
    boolean matches(RawPassword password, Sensitive<String> credentialDerivation);

    /**
     * The parameters a derivation was produced with, read back from its encoded form.
     *
     * <p>Needed to verify that what was stored in the queryable columns is what the encoded string
     * actually says - a check this task's tests make, because the two are duplicated on purpose
     * (ADR-0032 Option D) and duplication that nothing reconciles is drift waiting to happen.
     */
    DerivationParameters parametersOf(Sensitive<String> credentialDerivation);
}
