package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The function that produced a credential's derivation.
 *
 * <p><strong>Recorded per credential, not configured globally</strong> (ADR-0032, {@code
 * INV-IDN-02}). The point is not that there are two algorithms today - there is one - but that a
 * second one can coexist with the first while a store migrates. A platform whose algorithm is a
 * setting cannot change it: the setting decides what <em>new</em> credentials use, nothing records
 * what the old ones used, and the only exits are a forced reset for every customer or a guess.
 *
 * <p>Named rather than derived from the stored PHC string, although that string also contains it.
 * The duplication is ADR-0032 Option D and is deliberate: the encoded form is authoritative for
 * verification, and this column is what makes <em>"how many credentials use the old algorithm?"</em>
 * an indexed query rather than a full scan with a parse per row.
 */
public enum CredentialAlgorithm {

    /**
     * Argon2id, the memory-hard function {@code DELIVERY_PLAN.md} §9 names.
     *
     * <p>The {@code id} variant rather than {@code i} or {@code d}: it is the hybrid, and it is the
     * one every current recommendation names for password storage.
     */
    ARGON2ID("$argon2id$");

    /**
     * What a derivation produced by this algorithm must start with.
     *
     * <p>This is not decoration. It is what lets the database itself refuse a plaintext in the
     * derivation column - a {@code DB-CONSTRAINT}, which outranks every other mechanism in the
     * catalogue, standing behind {@code INV-IDN-01}.
     */
    private final String derivationPrefix;

    CredentialAlgorithm(String derivationPrefix) {
        this.derivationPrefix = derivationPrefix;
    }

    public String derivationPrefix() {
        return derivationPrefix;
    }

    /** Whether {@code derivation} is in this algorithm's encoded form. */
    public boolean produces(String derivation) {
        return derivation != null && derivation.startsWith(derivationPrefix);
    }

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(algorithm -> "'" + algorithm.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
