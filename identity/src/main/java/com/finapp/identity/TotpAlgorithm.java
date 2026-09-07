package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The HMAC an enrolment uses (`P1-TSK-017`).
 *
 * <h2>Three values, and unlike P1-TSK-013's enums that is not speculation</h2>
 *
 * <p>The provisioning URI carries an {@code algorithm} parameter, so this is a value a *client*
 * can already observe and a value a future enrolment may legitimately be created under. What has
 * no producer is not the enum but the *choice*: every enrolment this platform creates is
 * {@link #SHA1}, because that is what authenticator apps actually implement.
 */
public enum TotpAlgorithm {
    SHA1("HmacSHA1"),
    SHA256("HmacSHA256"),
    SHA512("HmacSHA512");

    private final String macAlgorithm;

    TotpAlgorithm(String macAlgorithm) {
        this.macAlgorithm = macAlgorithm;
    }

    /** The JCA name, so no caller assembles one from the enum's own name. */
    public String macAlgorithm() {
        return macAlgorithm;
    }

    /**
     * The values as a SQL list, so the CHECK constraint and this enum are one definition.
     *
     * <p>The `P0-TSK-022` pattern, reconciled by a migration test — because an enum value the
     * database refuses fails at the last write, after all the work, for a reason no error message
     * explains (the `P1-TSK-013` finding).
     */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
