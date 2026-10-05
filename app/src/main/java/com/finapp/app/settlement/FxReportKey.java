package com.finapp.app.settlement;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;

/**
 * Where the credential that pulls the FX provider's trade report comes from (`P9-TSK-011`,
 * PHASE_9_PLAN.md section 9) - read as {@code finapp.fx.report.key}, one key per concern: report
 * access is neither the provider's money-moving API ({@code FxProviderKey}) nor its evidence
 * encryption ({@code FxEvidenceKey}), so it shares neither key. Marked-local-default recognition,
 * loopback confinement, domain separation, both length rules and the non-echoing refusals are
 * inherited from {@code ConfinedCredential}; {@code ConfinedCredentialVariablesTest} pins the
 * variable a refusal names to the property the application really reads.
 *
 * <p><strong>{@code AT_LEAST_32}</strong>, the bearer-secret argument. The domain suffix
 * {@code "/fx-report"} keeps locally derived bytes separated from every sibling's. Consumed at the
 * composition root: the collector takes the decoded bytes and never sees this class; the bytes are
 * sent only over a transport {@code ProviderTransportGuard} admits, and never logged.
 */
public final class FxReportKey {

    private static final KeySpec SPEC =
            new KeySpec(
                    "FX provider report key",
                    "FX provider report key",
                    "FINAPP_FX_REPORT_KEY",
                    "/fx-report",
                    KeyLength.AT_LEAST_32,
                    ".");

    private FxReportKey() {}

    /**
     * Decodes a configured key.
     *
     * @param configured base64, decoding to at least 32 bytes, or the marked local default
     * @param localDefaultPermitted whether the marked default may be used - false anywhere the
     *     database is not on loopback
     */
    public static byte[] decode(String configured, boolean localDefaultPermitted) {
        return SPEC.decode(configured, localDefaultPermitted);
    }
}
