package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link RuleSets} over JDBC (ADR-0033) — the opener's lock-free read of the one
 * {@code ACTIVE} version per source (`P8-TSK-004`, ADR-0068 §8).
 */
public final class JdbcRuleSets implements RuleSets {

    @Override
    public ActiveRuleSet activeFor(Connection unitOfWork, UUID sourceId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        try {
            UUID ruleSetId;
            int version;
            int fundingLagDays;
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT id, version, funding_lag_days FROM reconciliation.rule_set"
                                    + " WHERE source_id = ? AND status = 'ACTIVE'")) {
                read.setObject(1, sourceId);
                try (ResultSet row = read.executeQuery()) {
                    if (!row.next()) {
                        // Version 1 is seeded ACTIVE per source and activation retires the
                        // prior version in its own transaction (ADR-0068 §8): an absence is
                        // a defect, and an opener that guessed a default would date
                        // expectations nothing decided (INV-HIST-04).
                        throw new ReconciliationStorageException(
                                "no ACTIVE rule set for source " + sourceId
                                        + ": V002 seeds version 1 for every source");
                    }
                    ruleSetId = row.getObject("id", UUID.class);
                    version = row.getInt("version");
                    fundingLagDays = row.getInt("funding_lag_days");
                }
            }
            Map<ExpectationKind, Integer> lagDays = new EnumMap<>(ExpectationKind.class);
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT expectation_kind, lag_days FROM"
                                    + " reconciliation.rule_set_lag WHERE rule_set_id = ?")) {
                read.setObject(1, ruleSetId);
                try (ResultSet rows = read.executeQuery()) {
                    while (rows.next()) {
                        lagDays.put(
                                ExpectationKind.valueOf(rows.getString("expectation_kind")),
                                rows.getInt("lag_days"));
                    }
                }
            }
            return new ActiveRuleSet(ruleSetId, version, lagDays, fundingLagDays);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the active rule set", failure);
        }
    }
}
