package com.finapp.risk;

import java.util.Objects;

/**
 * A risk policy version - its money-free machine row and its whole, immutable content, one id for both
 * (`P13-TSK-002`; {@code risk V002}'s {@code risk_policy_version} and {@code risk_policy} with its rules and bands,
 * bnpl {@code V002}'s split along the money line). An assessment pins it by id for life ({@code INV-HIST-04},
 * {@code INV-RSK-04}).
 */
public record RiskPolicyVersion(RiskPolicyStore.VersionRow row, RiskPolicy policy) {

    public RiskPolicyVersion {
        Objects.requireNonNull(row, "row must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        if (row.operation() != policy.operation()) {
            throw new IllegalStateException("a version's content is its own operation's");
        }
    }

    /** The version's id. */
    public RiskPolicyVersionId id() {
        return row.id();
    }

    /** The operation this version is written for. */
    public RiskOperation operation() {
        return row.operation();
    }

    /** The version number - per operation, monotonic. */
    public int version() {
        return row.version();
    }

    @Override
    public String toString() {
        return "RiskPolicyVersion[" + row + "]";
    }
}
