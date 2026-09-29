package com.finapp.reconciliation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The pinned rule set's matching content (`P8-TSK-011`, ADR-0068 §§2, 7): the rules for
 * one line type in priority order, and the {@code SETTLEMENT_DATE_DAYS} tolerance — read
 * lock-free from the frozen version the run pins ({@code INV-HIST-04}). Line types are
 * filtered as text so another source's vocabulary never materialises here.
 */
public final class MatchingRules {

    /** One rule row of the pinned version, as `V002` seeded it. */
    public record RuleRow(
            int priority,
            Optional<KeyKind> keyKind,
            Optional<ExpectationKind> expectationKind,
            Cardinality cardinality,
            boolean operationAnchored,
            int graceHours) {}

    public List<RuleRow> rulesFor(
            Connection unitOfWork, UUID ruleSetId, ExternalLineType lineType) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT priority, key_kind, expectation_kind, cardinality,"
                                + " operation_anchored, grace_hours"
                                + " FROM reconciliation.rule"
                                + " WHERE rule_set_id = ? AND line_type = ?"
                                + " ORDER BY priority")) {
            read.setObject(1, ruleSetId);
            read.setString(2, lineType.name());
            try (ResultSet rows = read.executeQuery()) {
                List<RuleRow> rules = new ArrayList<>();
                while (rows.next()) {
                    String keyKind = rows.getString("key_kind");
                    String expectationKind = rows.getString("expectation_kind");
                    Cardinality cardinality =
                            Cardinality.valueOf(rows.getString("cardinality"));
                    rules.add(
                            new RuleRow(
                                    rows.getInt("priority"),
                                    // Only a landed rule's key names the EXPECTATION-side
                                    // vocabulary; a CHECK or CORRECTION rule's key (e.g.
                                    // ORIGINAL_REF) is the item's own, read by its leg
                                    // (`P8-TSK-012`) in its own shape.
                                    cardinality == Cardinality.ONE_TO_ONE
                                            ? Optional.ofNullable(keyKind)
                                                    .map(KeyKind::valueOf)
                                            : Optional.empty(),
                                    Optional.ofNullable(expectationKind)
                                            .map(ExpectationKind::valueOf),
                                    cardinality,
                                    rows.getBoolean("operation_anchored"),
                                    rows.getInt("grace_hours")));
                }
                return List.copyOf(rules);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the pinned rules", failure);
        }
    }

    /** The pinned date window; absent means zero days — never a loosened default. */
    public int settlementDateToleranceDays(Connection unitOfWork, UUID ruleSetId) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT days FROM reconciliation.tolerance"
                                + " WHERE rule_set_id = ? AND comparison ="
                                + " 'SETTLEMENT_DATE_DAYS'")) {
            read.setObject(1, ruleSetId);
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? row.getInt("days") : 0;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException(
                    "could not read the date tolerance", failure);
        }
    }
}
