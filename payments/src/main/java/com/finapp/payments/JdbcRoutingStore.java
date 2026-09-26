package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.platform.money.MoneyColumns;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The routing tables, explicit SQL (`P7-TSK-003`, ADR-0033): `V013`'s policy versions with
 * their rules and candidate rails, the availability facts, and the decisions with their step
 * trails.
 *
 * <p>The version insert is savepoint-guarded exactly like the fee schedule's
 * ({@code JdbcFeeScheduleStore}): a lost race for the number is the unique index answering,
 * rolled back to the savepoint so the caller's transaction — and the audit record beside it —
 * survives the refusal and retries with the next number.
 */
public final class JdbcRoutingStore implements RoutingStore<Connection> {

    /** SQLState 23505. The arbiter's answer, not a failure. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String VERSION_COLUMNS =
            "id, version, effective_from, created_at, created_by, reason";
    private static final String RULE_COLUMNS =
            "id, policy_version_id, rule_index, direction, instrument_kind, currency,"
                    + " ceiling_amount_minor, ceiling_currency, ceiling_scale";
    private static final String DECISION_COLUMNS =
            "id, intent_id, policy_version_id, direction, instrument_kind, amount_minor,"
                    + " currency, scale, matched_rule_index, chosen_rail, created_at";
    private static final String STEP_COLUMNS =
            "decision_id, step_index, rail, verdict, rejection, rail_available,"
                    + " descriptor_version";

    // ----------------------------------------------------------------- policy versions

    @Override
    public int nextVersionNumber(Connection unitOfWork) {
        try (PreparedStatement read =
                        unitOfWork.prepareStatement(
                                "SELECT COALESCE(MAX(version), 0) + 1"
                                        + " FROM payments.routing_policy_version");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getInt(1);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("minting a routing policy version number", failure));
        }
    }

    @Override
    public boolean insertVersionIfNumberIsFree(
            Connection unitOfWork, RoutingPolicyVersion version) {
        Savepoint attempt;
        try {
            attempt = unitOfWork.setSavepoint("routing_policy_version");
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "preparing a routing policy version insert", failure));
        }
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO payments.routing_policy_version (" + VERSION_COLUMNS
                                    + ") VALUES (?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, version.id().value());
                insert.setInt(2, version.version());
                insert.setTimestamp(3, Timestamp.from(version.effectiveFrom()));
                insert.setTimestamp(4, Timestamp.from(version.createdAt()));
                insert.setString(5, version.createdBy());
                insert.setString(6, version.reason());
                insert.executeUpdate();
            }
            insertRules(unitOfWork, version);
            unitOfWork.releaseSavepoint(attempt);
            return true;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                // Another writer took this number. Not an error - the arbiter answering.
                rollbackTo(unitOfWork, attempt);
                return false;
            }
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting a routing policy version", failure));
        }
    }

    private static void insertRules(Connection unitOfWork, RoutingPolicyVersion version)
            throws SQLException {
        try (PreparedStatement insertRule =
                        unitOfWork.prepareStatement(
                                "INSERT INTO payments.routing_rule (" + RULE_COLUMNS
                                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)");
                PreparedStatement insertRail =
                        unitOfWork.prepareStatement(
                                "INSERT INTO payments.routing_rule_rail (rule_id, position,"
                                        + " rail) VALUES (?, ?, ?)")) {
            for (RoutingRule rule : version.rules()) {
                insertRule.setObject(1, rule.id().value());
                insertRule.setObject(2, version.id().value());
                insertRule.setInt(3, rule.ruleIndex());
                insertRule.setString(4, rule.direction().name());
                insertRule.setString(5, rule.instrumentKind().name());
                insertRule.setString(6, rule.currency().map(CurrencyCode::code).orElse(null));
                if (rule.ceiling().isPresent()) {
                    Money ceiling = rule.ceiling().orElseThrow();
                    insertRule.setLong(7, MoneyColumns.amountMinorOf(ceiling));
                    insertRule.setString(8, MoneyColumns.currencyOf(ceiling));
                    insertRule.setShort(9, MoneyColumns.scaleOf(ceiling));
                } else {
                    insertRule.setObject(7, null);
                    insertRule.setObject(8, null);
                    insertRule.setObject(9, null);
                }
                insertRule.executeUpdate();
                for (int position = 0; position < rule.rails().size(); position++) {
                    insertRail.setObject(1, rule.id().value());
                    insertRail.setInt(2, position);
                    insertRail.setString(3, rule.rails().get(position).value());
                    insertRail.executeUpdate();
                }
            }
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint attempt) {
        try {
            unitOfWork.rollback(attempt);
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe(
                            "abandoning a lost routing version race", failure));
        }
    }

    @Override
    public Optional<RoutingPolicyVersion> findVersionInForce(Connection unitOfWork, Instant at) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM payments.routing_policy_version"
                                + " WHERE effective_from <= ?"
                                + " ORDER BY effective_from DESC, version DESC LIMIT 1")) {
            read.setTimestamp(1, Timestamp.from(at));
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrateVersion(unitOfWork, row));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("resolving the routing version in force", failure));
        }
    }

    @Override
    public Optional<RoutingPolicyVersion> findVersionById(
            Connection unitOfWork, RoutingPolicyVersionId id) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + VERSION_COLUMNS + " FROM payments.routing_policy_version"
                                + " WHERE id = ?")) {
            read.setObject(1, id.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(rehydrateVersion(unitOfWork, row));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a routing policy version", failure));
        }
    }

    private RoutingPolicyVersion rehydrateVersion(Connection unitOfWork, ResultSet row)
            throws SQLException {
        RoutingPolicyVersionId versionId =
                RoutingPolicyVersionId.of(row.getObject("id", UUID.class));
        int version = row.getInt("version");
        Instant effectiveFrom = row.getTimestamp("effective_from").toInstant();
        Instant createdAt = row.getTimestamp("created_at").toInstant();
        String createdBy = row.getString("created_by");
        String reason = row.getString("reason");
        return RoutingPolicyVersion.rehydrate(
                versionId, version, rulesOf(unitOfWork, versionId), effectiveFrom, createdAt,
                createdBy, reason);
    }

    private List<RoutingRule> rulesOf(Connection unitOfWork, RoutingPolicyVersionId versionId)
            throws SQLException {
        record RuleRow(
                RoutingRuleId id,
                int index,
                PaymentDirection direction,
                InstrumentKind kind,
                Optional<CurrencyCode> currency,
                Optional<Money> ceiling) {}
        List<RuleRow> rows = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + RULE_COLUMNS + " FROM payments.routing_rule"
                                + " WHERE policy_version_id = ? ORDER BY rule_index")) {
            read.setObject(1, versionId.value());
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    String currency = row.getString("currency");
                    long ceilingMinor = row.getLong("ceiling_amount_minor");
                    boolean bounded = !row.wasNull();
                    rows.add(new RuleRow(
                            RoutingRuleId.of(row.getObject("id", UUID.class)),
                            row.getInt("rule_index"),
                            PaymentDirection.valueOf(row.getString("direction")),
                            InstrumentKind.valueOf(row.getString("instrument_kind")),
                            Optional.ofNullable(currency).map(CurrencyCode::of),
                            bounded
                                    ? Optional.of(Money.ofPersisted(
                                            ceilingMinor,
                                            CurrencyCode.of(row.getString("ceiling_currency")),
                                            row.getShort("ceiling_scale")))
                                    : Optional.empty()));
                }
            }
        }
        List<RoutingRule> rules = new ArrayList<>();
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT rail FROM payments.routing_rule_rail WHERE rule_id = ?"
                                + " ORDER BY position")) {
            for (RuleRow rule : rows) {
                read.setObject(1, rule.id().value());
                List<RailId> rails = new ArrayList<>();
                try (ResultSet row = read.executeQuery()) {
                    while (row.next()) {
                        rails.add(RailId.of(row.getString("rail")));
                    }
                }
                rules.add(new RoutingRule(
                        rule.id(), rule.index(), rule.direction(), rule.kind(),
                        rule.currency(), rule.ceiling(), rails));
            }
        }
        return rules;
    }

    // ----------------------------------------------------------------- availability

    @Override
    public void recordAvailability(Connection unitOfWork, RailAvailability availability) {
        try (PreparedStatement upsert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.rail_availability (rail, available, reason,"
                                + " changed_by, changed_at) VALUES (?, ?, ?, ?, ?)"
                                + " ON CONFLICT (rail) DO UPDATE SET"
                                + " available = EXCLUDED.available,"
                                + " reason = EXCLUDED.reason,"
                                + " changed_by = EXCLUDED.changed_by,"
                                + " changed_at = EXCLUDED.changed_at")) {
            upsert.setString(1, availability.rail().value());
            upsert.setBoolean(2, availability.available());
            upsert.setString(3, availability.reason());
            upsert.setString(4, availability.changedBy());
            upsert.setTimestamp(5, Timestamp.from(availability.changedAt()));
            upsert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("recording a rail availability fact", failure));
        }
    }

    @Override
    public Map<RailId, RailAvailability> availabilityByRail(Connection unitOfWork) {
        try (PreparedStatement read =
                        unitOfWork.prepareStatement(
                                "SELECT rail, available, reason, changed_by, changed_at"
                                        + " FROM payments.rail_availability");
                ResultSet row = read.executeQuery()) {
            Map<RailId, RailAvailability> byRail = new LinkedHashMap<>();
            while (row.next()) {
                RailId rail = RailId.of(row.getString("rail"));
                byRail.put(
                        rail,
                        new RailAvailability(
                                rail,
                                row.getBoolean("available"),
                                row.getString("reason"),
                                row.getString("changed_by"),
                                row.getTimestamp("changed_at").toInstant()));
            }
            return byRail;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading the rail availability facts", failure));
        }
    }

    // ----------------------------------------------------------------- decisions

    @Override
    public void insertDecision(Connection unitOfWork, RoutingDecision decision) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.routing_decision (" + DECISION_COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, decision.id().value());
            insert.setObject(2, decision.intentId().value());
            insert.setObject(3, decision.policyVersionId().value());
            insert.setString(4, decision.direction().name());
            insert.setString(5, decision.instrumentKind().name());
            insert.setLong(6, MoneyColumns.amountMinorOf(decision.amount()));
            insert.setString(7, MoneyColumns.currencyOf(decision.amount()));
            insert.setShort(8, MoneyColumns.scaleOf(decision.amount()));
            insert.setObject(9, decision.matchedRuleIndex().orElse(null));
            insert.setString(10, decision.chosenRail().map(RailId::value).orElse(null));
            insert.setTimestamp(11, Timestamp.from(decision.createdAt()));
            insert.executeUpdate();
            for (RoutingStep step : decision.steps()) {
                appendStep(unitOfWork, decision.id(), step);
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("inserting a routing decision", failure));
        }
    }

    @Override
    public Optional<RoutingDecision> findLatestDecisionForIntent(
            Connection unitOfWork, PaymentIntentId intent) {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + DECISION_COLUMNS + " FROM payments.routing_decision"
                                + " WHERE intent_id = ? ORDER BY created_at DESC, id DESC"
                                + " LIMIT 1")) {
            read.setObject(1, intent.value());
            try (ResultSet row = read.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                RoutingDecisionId decisionId =
                        RoutingDecisionId.of(row.getObject("id", UUID.class));
                Integer matched = row.getObject("matched_rule_index", Integer.class);
                String chosen = row.getString("chosen_rail");
                return Optional.of(
                        RoutingDecision.rehydrate(
                                decisionId,
                                PaymentIntentId.of(row.getObject("intent_id", UUID.class)),
                                RoutingPolicyVersionId.of(
                                        row.getObject("policy_version_id", UUID.class)),
                                PaymentDirection.valueOf(row.getString("direction")),
                                InstrumentKind.valueOf(row.getString("instrument_kind")),
                                Money.ofPersisted(
                                        row.getLong("amount_minor"),
                                        CurrencyCode.of(row.getString("currency")),
                                        row.getShort("scale")),
                                Optional.ofNullable(matched),
                                Optional.ofNullable(chosen).map(RailId::of),
                                stepsOf(unitOfWork, decisionId),
                                row.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("reading a payment's routing decision", failure));
        }
    }

    private List<RoutingStep> stepsOf(Connection unitOfWork, RoutingDecisionId decisionId)
            throws SQLException {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT " + STEP_COLUMNS + " FROM payments.routing_decision_step"
                                + " WHERE decision_id = ? ORDER BY step_index")) {
            read.setObject(1, decisionId.value());
            try (ResultSet row = read.executeQuery()) {
                List<RoutingStep> steps = new ArrayList<>();
                while (row.next()) {
                    String rejection = row.getString("rejection");
                    Integer descriptor = row.getObject("descriptor_version", Integer.class);
                    steps.add(new RoutingStep(
                            row.getInt("step_index"),
                            RailId.of(row.getString("rail")),
                            RoutingStepVerdict.valueOf(row.getString("verdict")),
                            Optional.ofNullable(rejection).map(RoutingRejection::valueOf),
                            row.getBoolean("rail_available"),
                            Optional.ofNullable(descriptor)));
                }
                return steps;
            }
        }
    }

    @Override
    public void appendStep(
            Connection unitOfWork, RoutingDecisionId decision, RoutingStep step) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO payments.routing_decision_step (" + STEP_COLUMNS
                                + ") VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, decision.value());
            insert.setInt(2, step.stepIndex());
            insert.setString(3, step.rail().value());
            insert.setString(4, step.verdict().name());
            insert.setString(5, step.rejection().map(Enum::name).orElse(null));
            insert.setBoolean(6, step.railAvailable());
            insert.setObject(7, step.descriptorVersion().orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(
                    DatabaseFailure.describe("appending a routing step", failure));
        }
    }
}
