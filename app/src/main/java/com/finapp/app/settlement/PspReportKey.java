package com.finapp.app.settlement;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the credential that pulls the card PSP's settlement report comes from (`P8-TSK-021`,
 * ADR-0066 §1) — read as {@code finapp.settlement.psp.report.key}, one key per concern: report
 * access is not the money-moving API, so it never shares that API's key. Marked-local-default
 * recognition, loopback confinement, domain separation, both length rules and the non-echoing
 * refusals are inherited from {@code ConfinedCredential}; {@code ConfinedCredentialVariablesTest}
 * pins the variable a refusal names to the property the application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the bearer-secret argument. The domain suffix {@code
 * "/settlement-psp-report"} keeps locally derived bytes separated from every sibling's. Consumed
 * at the composition root: the collector takes the decoded bytes and never sees this class; the
 * bytes are sent only over a transport {@code ProviderTransportGuard} admits, and never logged.
 */
public final class PspReportKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "PSP settlement report key",
                    "PSP settlement report key",
                    "FINAPP_SETTLEMENT_PSP_REPORT_KEY",
                    "/settlement-psp-report",
                    KeyLength.AT_LEAST_32,
                    ".");

    private PspReportKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used — false anywhere
     *     the database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
