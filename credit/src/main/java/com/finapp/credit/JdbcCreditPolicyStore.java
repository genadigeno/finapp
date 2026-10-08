package com.finapp.credit;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link CreditPolicyStore} over {@code credit V008} (`P10-TSK-012`). Stateless. */
public final class JdbcCreditPolicyStore implements CreditPolicyStore {

    /** The administration's proposal lock - credit's own, shared with the scorecard (DISTRIBUTED_EXECUTION.md section 3). */
    static final int NAMESPACE = JdbcScorecardStore.NAMESPACE;

    private static final String ROW_COLUMNS = "id, product, version, status, proposed_by, decided_by";

    @Override
    public void lockProduct(Connection unitOfWork, CreditProduct product) {
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, product.name());
            lock.execute();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("locking a credit policy's product", failure));
        }
    }

    @Override
    public boolean proposalPending(Connection unitOfWork, CreditProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM credit.credit_policy_version WHERE product = ? AND status = 'PROPOSED')")) {
            select.setString(1, product.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a product's policy proposal", failure));
        }
    }

    @Override
    public int maxVersion(Connection unitOfWork, CreditProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT coalesce(max(version), 0) FROM credit.credit_policy_version WHERE product = ?")) {
            select.setString(1, product.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("numbering a credit policy version", failure));
        }
    }

    @Override
    public void insertProposal(
            Connection unitOfWork,
            CreditPolicyVersionId id,
            int version,
            CreditPolicy policy,
            String proposedBy,
            String reason) {
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_policy_version (id, product, version, status, currency, scale,"
                            + " assessment_rate_bps, minimum_disposable_minor, minimum_payment_ratio_bps,"
                            + " maximum_exposure_minor, max_data_age_bureau_seconds, max_data_age_financial_data_seconds,"
                            + " unavailable_fallback, auto_approval_ceiling_minor, proposed_by, proposed_at, proposal_reason)"
                            + " VALUES (?, ?, ?, 'PROPOSED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, transaction_timestamp(), ?)")) {
                insert.setObject(1, id.value());
                insert.setString(2, policy.product().name());
                insert.setInt(3, version);
                insert.setString(4, policy.product().currency().code());
                insert.setInt(5, policy.product().currency().minorUnits());
                insert.setInt(6, policy.assessmentRateBps());
                insert.setLong(7, policy.minimumDisposable().minorUnits());
                insert.setInt(8, policy.minimumPaymentRatioBps());
                insert.setLong(9, policy.maximumExposure().minorUnits());
                age(insert, 10, policy.maximumDataAge().get(CreditSourceKind.BUREAU));
                age(insert, 11, policy.maximumDataAge().get(CreditSourceKind.FINANCIAL_DATA));
                insert.setString(12, policy.unavailableFallback().name());
                insert.setLong(13, policy.autoApprovalCeiling().minorUnits());
                insert.setString(14, proposedBy);
                insert.setString(15, reason);
                insert.executeUpdate();
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO credit.credit_policy_rule (policy_version_id, ordinal, rule_code, subject_kind, subject,"
                            + " operator, operand_integer, operand_money_minor, operand_boolean, operand_codes, effect,"
                            + " cap_amount_minor, reason_code) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                int ordinal = 1;
                for (CreditPolicy.PolicyRule rule : policy.rules()) {
                    insert.setObject(1, id.value());
                    insert.setInt(2, ordinal++);
                    insert.setString(3, rule.ruleCode());
                    insert.setString(4, rule.subject() instanceof CreditPolicy.Subject.Figure ? "FIGURE" : "ATTRIBUTE");
                    insert.setString(5, rule.subject().name());
                    insert.setString(6, rule.operator().name());
                    insert.setNull(7, Types.BIGINT);
                    insert.setNull(8, Types.BIGINT);
                    insert.setNull(9, Types.BOOLEAN);
                    insert.setNull(10, Types.ARRAY);
                    switch (rule.operand()) {
                        case CreditPolicy.Operand.None none -> { }
                        case CreditPolicy.Operand.IntegerOperand integer -> insert.setLong(7, integer.value());
                        case CreditPolicy.Operand.MoneyOperand money -> insert.setLong(8, money.value().minorUnits());
                        case CreditPolicy.Operand.BooleanOperand bool -> insert.setBoolean(9, bool.value());
                        case CreditPolicy.Operand.CodesOperand codes ->
                                insert.setArray(10, unitOfWork.createArrayOf("text", codes.codes().toArray()));
                    }
                    insert.setString(11, rule.effect().name());
                    if (rule.cap().isPresent()) {
                        insert.setLong(12, rule.cap().get().minorUnits());
                    } else {
                        insert.setNull(12, Types.BIGINT);
                    }
                    insert.setString(13, rule.reason().code());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("proposing a credit policy version", failure));
        }
    }

    private static void age(PreparedStatement insert, int index, Duration age) throws SQLException {
        if (age == null) {
            insert.setNull(index, Types.INTEGER);
        } else {
            insert.setInt(index, Math.toIntExact(age.toSeconds()));
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            CreditPolicyVersionId id,
            Optional<CreditPolicyStatus> from,
            CreditPolicyStatus to,
            String actorId,
            String reason) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO credit.credit_policy_event (id, policy_version_id, from_status, to_status, actor_id, reason,"
                        + " occurred_at) VALUES (?, ?, ?, ?, ?, ?, transaction_timestamp())")) {
            insert.setObject(1, eventId);
            insert.setObject(2, id.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, actorId);
            insert.setString(6, reason);
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("recording a credit policy version's history", failure));
        }
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, CreditPolicyVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.credit_policy_version WHERE id = ? FOR UPDATE",
                id.value(), "locking a credit policy version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, CreditProduct product) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.credit_policy_version"
                + " WHERE product = ? AND status = 'ACTIVE' FOR UPDATE", product.name(), "locking the active credit policy");
    }

    @Override
    public boolean retire(Connection unitOfWork, CreditPolicyVersionId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE credit.credit_policy_version SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("retiring a credit policy version", failure));
        }
    }

    @Override
    public Optional<Optional<Instant>> decide(
            Connection unitOfWork, CreditPolicyVersionId id, CreditPolicyStatus to, String decidedBy, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE credit.credit_policy_version SET status = ?, decided_by = ?, decided_at = transaction_timestamp(),"
                        + " decision_reason = ? WHERE id = ? AND status = 'PROPOSED' RETURNING effective_from")) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setString(3, reason);
            update.setObject(4, id.value());
            try (ResultSet row = update.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                Timestamp from = row.getTimestamp(1);
                return Optional.of(Optional.ofNullable(from).map(Timestamp::toInstant));
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("deciding a credit policy version", failure));
        }
    }

    @Override
    public Optional<PolicyVersion> policy(Connection unitOfWork, CreditPolicyVersionId id) {
        try {
            VersionRow row;
            CurrencyCode currency;
            int scale;
            int assessmentRate;
            long minimumDisposable;
            int minimumPaymentRatio;
            long maximumExposure;
            Map<CreditSourceKind, Duration> ages = new EnumMap<>(CreditSourceKind.class);
            UnavailableFallback fallback;
            long ceiling;
            Instant from;
            Instant to;
            try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + ROW_COLUMNS
                    + ", currency, scale, assessment_rate_bps, minimum_disposable_minor, minimum_payment_ratio_bps,"
                    + " maximum_exposure_minor, max_data_age_bureau_seconds, max_data_age_financial_data_seconds,"
                    + " unavailable_fallback, auto_approval_ceiling_minor, effective_from, effective_to"
                    + " FROM credit.credit_policy_version WHERE id = ?")) {
                select.setObject(1, id.value());
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    row = versionRow(result);
                    currency = CurrencyCode.of(result.getString("currency"));
                    scale = result.getInt("scale");
                    assessmentRate = result.getInt("assessment_rate_bps");
                    minimumDisposable = result.getLong("minimum_disposable_minor");
                    minimumPaymentRatio = result.getInt("minimum_payment_ratio_bps");
                    maximumExposure = result.getLong("maximum_exposure_minor");
                    Integer bureau = (Integer) result.getObject("max_data_age_bureau_seconds");
                    Integer findata = (Integer) result.getObject("max_data_age_financial_data_seconds");
                    if (bureau != null) {
                        ages.put(CreditSourceKind.BUREAU, Duration.ofSeconds(bureau));
                    }
                    if (findata != null) {
                        ages.put(CreditSourceKind.FINANCIAL_DATA, Duration.ofSeconds(findata));
                    }
                    fallback = UnavailableFallback.valueOf(result.getString("unavailable_fallback"));
                    ceiling = result.getLong("auto_approval_ceiling_minor");
                    from = instant(result.getTimestamp("effective_from"));
                    to = instant(result.getTimestamp("effective_to"));
                }
            }
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            try (PreparedStatement select = unitOfWork.prepareStatement(
                    "SELECT rule_code, subject_kind, subject, operator, operand_integer, operand_money_minor,"
                            + " operand_boolean, operand_codes, effect, cap_amount_minor, reason_code"
                            + " FROM credit.credit_policy_rule WHERE policy_version_id = ? ORDER BY ordinal")) {
                select.setObject(1, id.value());
                try (ResultSet result = select.executeQuery()) {
                    while (result.next()) {
                        rules.add(rule(result, currency, scale));
                    }
                }
            }
            CreditPolicy policy = new CreditPolicy(row.product(), assessmentRate,
                    Money.ofPersisted(minimumDisposable, currency, scale), minimumPaymentRatio,
                    Money.ofPersisted(maximumExposure, currency, scale), ages, fallback,
                    Money.ofPersisted(ceiling, currency, scale), rules);
            return Optional.of(new PolicyVersion(row, policy, Optional.ofNullable(from), Optional.ofNullable(to)));
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a credit policy version", failure));
        }
    }

    private static CreditPolicy.PolicyRule rule(ResultSet result, CurrencyCode currency, int scale) throws SQLException {
        String subjectName = result.getString("subject");
        CreditPolicy.Subject subject = "FIGURE".equals(result.getString("subject_kind"))
                ? new CreditPolicy.Subject.Figure(PolicyFigure.valueOf(subjectName))
                : new CreditPolicy.Subject.Attribute(CreditAttributeCode.valueOf(subjectName));
        PolicyOperator operator = PolicyOperator.valueOf(result.getString("operator"));
        CreditPolicy.Operand operand;
        Long integer = (Long) result.getObject("operand_integer");
        Long money = (Long) result.getObject("operand_money_minor");
        Boolean bool = (Boolean) result.getObject("operand_boolean");
        java.sql.Array codes = result.getArray("operand_codes");
        if (integer != null) {
            operand = new CreditPolicy.Operand.IntegerOperand(integer);
        } else if (money != null) {
            operand = new CreditPolicy.Operand.MoneyOperand(Money.ofPersisted(money, currency, scale));
        } else if (bool != null) {
            operand = new CreditPolicy.Operand.BooleanOperand(bool);
        } else if (codes != null) {
            operand = new CreditPolicy.Operand.CodesOperand(List.of((String[]) codes.getArray()));
        } else {
            operand = new CreditPolicy.Operand.None();
        }
        Long cap = (Long) result.getObject("cap_amount_minor");
        return new CreditPolicy.PolicyRule(
                result.getString("rule_code"),
                subject,
                operator,
                operand,
                PolicyEffect.valueOf(result.getString("effect")),
                Optional.ofNullable(cap).map(minor -> Money.ofPersisted(minor, currency, scale)),
                reasonCode(result.getString("reason_code")));
    }

    private static ReasonCode reasonCode(String code) {
        for (ReasonCode candidate : ReasonCode.values()) {
            if (candidate.code().equals(code)) {
                return candidate;
            }
        }
        throw new IllegalStateException("a stored reason code is not in the catalogue enum");
    }

    @Override
    public Optional<CreditPolicyVersionId> activeAt(Connection unitOfWork, CreditProduct product, Instant instant) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM credit.credit_policy_version WHERE product = ? AND effective_from <= ?"
                        + " AND (effective_to IS NULL OR effective_to > ?)")) {
            select.setString(1, product.name());
            select.setTimestamp(2, Timestamp.from(instant));
            select.setTimestamp(3, Timestamp.from(instant));
            return single(select, "two credit policy versions effective at one instant");
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the credit policy active at an instant", failure));
        }
    }

    @Override
    public Optional<CreditPolicyVersionId> active(Connection unitOfWork, CreditProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM credit.credit_policy_version WHERE product = ? AND status = 'ACTIVE'")) {
            select.setString(1, product.name());
            return single(select, "two ACTIVE credit policy versions for one product");
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the active credit policy", failure));
        }
    }

    @Override
    public Map<CreditProduct, Integer> activeVersions(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT product, version FROM credit.credit_policy_version WHERE status = 'ACTIVE'");
                ResultSet row = select.executeQuery()) {
            Map<CreditProduct, Integer> versions = new EnumMap<>(CreditProduct.class);
            while (row.next()) {
                versions.put(CreditProduct.valueOf(row.getString(1)), row.getInt(2));
            }
            return versions;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading the active credit policies", failure));
        }
    }

    private static Optional<CreditPolicyVersionId> single(PreparedStatement select, String twoFound) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            CreditPolicyVersionId id = CreditPolicyVersionId.of(row.getObject(1, UUID.class));
            if (row.next()) {
                throw new IllegalStateException(twoFound);
            }
            return Optional.of(id);
        }
    }

    private static Optional<VersionRow> row(Connection unitOfWork, String sql, Object key, String what) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(versionRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe(what, failure));
        }
    }

    private static VersionRow versionRow(ResultSet row) throws SQLException {
        return new VersionRow(
                CreditPolicyVersionId.of(row.getObject("id", UUID.class)),
                CreditProduct.valueOf(row.getString("product")),
                row.getInt("version"),
                CreditPolicyStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                Optional.ofNullable(row.getString("decided_by")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    @Override
    public Optional<CreditPolicyVersionId> shareActive(Connection unitOfWork, CreditProduct product) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.credit_policy_version"
                + " WHERE product = ? AND status = 'ACTIVE' FOR SHARE", product.name(), "sharing the active version")
                .map(VersionRow::id);
    }

    @Override
    public boolean sharePinned(Connection unitOfWork, CreditPolicyVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM credit.credit_policy_version WHERE id = ? FOR SHARE", id.value(),
                "sharing a pinned version").isPresent();
    }
}
