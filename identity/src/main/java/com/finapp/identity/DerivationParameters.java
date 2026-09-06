package com.finapp.identity;

import java.util.Objects;

/**
 * The cost factors a specific derivation was produced with ({@code INV-IDN-02}, ADR-0032).
 *
 * <h2>Why these are stored per credential and not read from configuration</h2>
 *
 * <p>This is the decision ADR-0032 exists for, and it is the one usually missed. A platform whose
 * work factor is a global setting <strong>cannot raise it</strong>: changing the setting changes
 * what new credentials use, and nothing records what the old ones used. The store becomes a mix of
 * strengths that nothing can distinguish, and the only exits are invalidating every credential -
 * a forced reset for every customer - or guessing.
 *
 * <p>Recorded per credential, <em>"how strongly was this one protected?"</em> is answerable
 * permanently, and <em>"which credentials are below current policy?"</em> is an indexed query. That
 * query is what an upgrade campaign is, and it is the entire reason these are columns rather than
 * being left inside the encoded derivation where only the hashing library can read them.
 *
 * @param memoryKib memory cost in kibibytes - the parameter that makes Argon2id memory-hard, and
 *     therefore the one that actually resists a GPU
 * @param iterations time cost: passes over the memory
 * @param parallelism lanes. One unless the deployment is known to have cores to spare, because a
 *     lane costs a thread on every verification
 */
public record DerivationParameters(int memoryKib, int iterations, int parallelism) {

    /**
     * The current policy: m=19456 KiB, t=2, p=1.
     *
     * <p>OWASP's Argon2id baseline. <strong>The measured cost on the development machine is
     * recorded in ADR-0032's follow-up</strong> rather than asserted here, because ADR-0032 asks
     * for parameters chosen against a stated verification time and a stated time nobody measured is
     * not stated. A test pins the shipped values so raising them is a deliberate, reviewable edit
     * rather than a drift.
     *
     * <p>19456 KiB is 19 MiB <em>per concurrent derivation</em>. That is a real capacity fact and
     * not a detail: it is why ADR-0032 records that this makes login the platform's most expensive
     * operation, and why rate limiting (`P1-TSK-011`) is part of the same design rather than an
     * extra.
     */
    public static DerivationParameters current() {
        return new DerivationParameters(19456, 2, 1);
    }

    public DerivationParameters {
        positive(memoryKib, "memoryKib");
        positive(iterations, "iterations");
        positive(parallelism, "parallelism");
    }

    /**
     * Whether a credential derived with these falls short of {@code policy}.
     *
     * <p>Any factor being lower is enough, and the reasoning differs by factor - which is worth
     * stating rather than asserting a single tidy rule that is only true of two of them.
     * <strong>Memory and time are strength</strong>: they multiply an attacker's cost directly, so
     * a credential with current memory and half the iterations is genuinely weaker.
     * <strong>Parallelism is not</strong> - more lanes spread the same total work rather than
     * adding to it - and it is included anyway, because the goal is convergence on the current
     * policy and a credential produced under different lanes was not produced under current policy.
     *
     * <p>Erring that way is deliberate and safe: the cost of including it is an occasional
     * unnecessary re-derivation at the one moment the platform legitimately holds the plaintext,
     * and the cost of excluding it would be a credential that policy no longer describes.
     *
     * <p>Provided here, used by {@code P1-TSK-008}'s upgrade-on-use. It is a property of the
     * parameters rather than of the verification, which is why it lives on the value object.
     */
    public boolean isWeakerThan(DerivationParameters policy) {
        Objects.requireNonNull(policy, "policy must not be null");
        return memoryKib < policy.memoryKib
                || iterations < policy.iterations
                || parallelism < policy.parallelism;
    }

    private static void positive(int value, String what) {
        if (value < 1) {
            // Zero is what an uninitialised int holds, so accepting it would let a caller that
            // forgot to set a factor produce a credential that looks parameterised.
            throw new IllegalArgumentException(what + " must be at least 1 but was " + value);
        }
    }
}
