package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of proof a credential is.
 *
 * <p><strong>One value, and that is deliberate.</strong> ADR-0032's follow-up records that a
 * WebAuthn credential is a different <em>type</em> in this same aggregate rather than a different
 * table - it shares the lifecycle and differs only in what verification means. That is milestone
 * M1.4, and {@code EXECUTION_PROTOCOL.md} rule 3 forbids implementing it now.
 *
 * <p>So this enum existing <em>is</em> the seam: adding a second type is a constant and a
 * migration, not a schema redesign. An enum with one value looks like ceremony until the moment
 * somebody would otherwise have written {@code boolean isPassword}.
 */
public enum CredentialType {

    /** A secret the person knows, stored as an Argon2id derivation. */
    PASSWORD;

    /**
     * The values, as a SQL list for a {@code CHECK} constraint.
     *
     * <p>Generated from the enum rather than written twice, so the constraint and the type cannot
     * drift - the {@code P0-TSK-022} pattern, guarded hermetically by a migration test.
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(type -> "'" + type.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
