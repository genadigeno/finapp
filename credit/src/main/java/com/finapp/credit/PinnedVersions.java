package com.finapp.credit;

import java.util.Objects;
import java.util.UUID;

/**
 * The versions a snapshot is evaluated under (`P10-TSK-008`; ADR-0086): the policy and scorecard model versions
 * pinned on the request (their tables arrive with `P10-TSK-011` and `-012`) and the evaluator's engine version - all
 * three sealed into the snapshot, so a replay evaluates exactly what decided ({@code INV-CRD-01}).
 */
public record PinnedVersions(UUID policyVersion, UUID modelVersion, int engineVersion) {

    public PinnedVersions {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(modelVersion, "modelVersion");
        if (engineVersion < 1) {
            throw new IllegalArgumentException("an engine version counts from 1");
        }
    }
}
