package com.finapp.identity;

import java.util.Objects;

/**
 * The parameters a TOTP enrolment was created under (`P1-TSK-017`).
 *
 * <h2>Recorded per enrolment, for ADR-0032's reason</h2>
 *
 * <p>A platform whose TOTP parameters are a global setting cannot change them: altering the setting
 * changes what <em>new</em> enrolments use, nothing records what the old ones used, and every
 * existing authenticator app silently stops matching. That is the same defect ADR-0032 identified
 * for credential work factors, and the same answer — the parameters live on the row.
 *
 * <p>It is also not hypothetical here: an authenticator app is configured once, from the QR code,
 * and cannot be told later that the period changed.
 */
public record TotpParameters(TotpAlgorithm algorithm, int digits, int periodSeconds) {

    /**
     * What every authenticator app assumes when the provisioning URI omits them.
     *
     * <p>SHA-1 is not a weakness here and choosing SHA-256 would be. HOTP's security rests on
     * HMAC, whose collision resistance requirement SHA-1 still meets, and the practical constraint
     * is that Google Authenticator and its imitators <strong>ignore the algorithm parameter</strong>
     * — so an enrolment claiming SHA-256 would produce codes the customer's app never generates.
     * Interoperability decides this, and RFC 6238 §1.2 assumes SHA-1 for the same reason.
     */
    public static TotpParameters current() {
        return new TotpParameters(TotpAlgorithm.SHA1, 6, 30);
    }

    public TotpParameters {
        Objects.requireNonNull(algorithm, "algorithm must not be null");
        if (digits < 6 || digits > 8) {
            // RFC 4226 requires at least six. Eight is the practical ceiling: the truncation reads
            // 31 bits, so ten digits would have values no code could ever take.
            throw new IllegalArgumentException("digits must be between 6 and 8 but was " + digits);
        }
        if (periodSeconds < 1) {
            throw new IllegalArgumentException(
                    "periodSeconds must be positive but was " + periodSeconds);
        }
    }
}
