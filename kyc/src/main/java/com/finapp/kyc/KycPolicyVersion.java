package com.finapp.kyc;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The version of the KYC policy a case is assessed under, pinned at open (`P2-TSK-005`).
 *
 * <p>{@code INV-HIST-04}'s mechanism, applied at the moment it is still free: a decision must
 * record the version of the policy that produced it ({@code INV-KYC-02}), and a case that never
 * recorded which regime it was opened under cannot have that fact restored later. What exists
 * today is the <em>pin</em> — {@link #CURRENT} is a platform label, and the versioned policy
 * artefact it names (the rules, thresholds and check set) arrives with decisioning
 * ({@code P2-TSK-013}), which will pin the same version on the decision it records.
 *
 * <p>Bounds mirror the {@code CHECK} constraint on {@code kyc.kyc_case.policy_version}; the
 * charset is closed because the value reaches durable rows and log lines, and a platform-issued
 * label has no business carrying anything a charset would have to defend against.
 */
public record KycPolicyVersion(String value) implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** Matches {@code kyc_case_policy_version_is_bounded}. */
    public static final int MAX_LENGTH = 50;

    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    /**
     * The policy regime cases open under today. Bumped by the task that changes the policy —
     * a reviewed code change, exactly like a role's permission set.
     *
     * <p>Dashes rather than a dot, because the version travels in the {@code KycCaseOpened}
     * event payload and {@code EventPayload}'s charset — identifiers and enumerated names only —
     * admits no dot. The type permits dots; the platform's own label simply does not use one
     * (`P2-TSK-007`).
     */
    public static final KycPolicyVersion CURRENT = new KycPolicyVersion("kyc-2026-09");

    public KycPolicyVersion {
        Objects.requireNonNull(value, "policy version must not be null");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "policy version must be at most " + MAX_LENGTH + " characters but was "
                            + value.length());
        }
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "policy version must match " + SHAPE.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
