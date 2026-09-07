package com.finapp.identity;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What kind of second factor an enrolment is (`P1-TSK-017`).
 *
 * <p>One value, deliberately. `EXECUTION_PROTOCOL.md` rule 3 forbids implementing WebAuthn now, and
 * the enum *existing* is the seam a second factor type needs - the same decision `P1-TSK-007` made
 * for `CredentialType`.
 */
public enum MfaFactorType {
    TOTP;

    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
