package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanTermsStore} over plain JDBC (`P11-TSK-005`, ADR-0033): every time the database's, every move a
 * conditional {@code UPDATE} of the machine row's decision columns under {@code lending V003}'s edge trigger, the term
 * set and the history INSERT-only, every failure its operation and SQLSTATE only ({@link LendingStorageException}).
 */
public final class JdbcLoanTermsStore implements LoanTermsStore {

    /**
     * Lending's administration namespace - the ONE constant `P11-TSK-004` registered
     * ({@link JdbcCapitalContributionStore#NAMESPACE}), extended here on {@code hashtext(product)}. A product's name
     * hashing with a currency code only serialises the two administrations a moment longer - never a wrong answer.
     */
    static final int NAMESPACE = JdbcCapitalContributionStore.NAMESPACE;

    private static final String ROW_COLUMNS =
            "id, product, version, status, proposed_by, proposed_at, decided_by, decided_at, effective_from, effective_to";

    private static final String TERM_COLUMNS =
            "t.currency, t.scale, t.nominal_annual_rate, t.rate_type, t.origination_fee_minor, t.origination_fee_bps,"
                    + " t.origination_fee_treatment, t.late_fee_minor, t.late_fee_day, t.late_fee_cap_minor, t.grace_days,"
                    + " t.delinquency_bounds, t.default_threshold_days, t.allocation_sequence, t.allocation_order,"
                    + " t.overpayment_treatment, t.auto_collection, t.partial_collection, t.instalment_rounding,"
                    + " t.interest_rounding, t.day_count, t.servicing_zone, t.allowed_days, t.min_first_period_days,"
                    + " t.offer_validity_days, t.payoff_horizon_days, t.disbursement_deadline_hours,"
                    + " t.minimum_payment_floor_minor, t.minimum_payment_ratio_bps, t.payment_due_days,"
                    + " t.schedule_engine_version, t.accrual_engine_version, t.allocation_engine_version,"
                    + " t.statement_engine_version, t.agreement_template_id, t.agreement_template_version";

    @Override
    public void lockProduct(Connection unitOfWork, LoanProduct product) {
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, product.name());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the terms administration lock", failure);
        }
    }

    @Override
    public boolean proposalPending(Connection unitOfWork, LoanProduct product) {
        return exists(unitOfWork, "SELECT EXISTS (SELECT 1 FROM lending.loan_terms_version WHERE product = ?"
                + " AND status = 'PROPOSED')", product, "reading the pending terms version");
    }

    @Override
    public int maxVersion(Connection unitOfWork, LoanProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT COALESCE(max(version), 0) FROM lending.loan_terms_version WHERE product = ?")) {
            select.setString(1, product.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failed("reading the latest terms version number", failure);
        }
    }

    @Override
    public void insertProposal(Connection unitOfWork, NewProposal proposal) {
        LoanTerms terms = proposal.terms();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO lending.loan_terms_version (id, product, version, status, proposed_by, proposed_by_type,"
                            + " proposed_at, proposal_reason, correlation_id)"
                            + " VALUES (?, ?, ?, 'PROPOSED', ?, ?, transaction_timestamp(), ?, ?)")) {
                insert.setObject(1, proposal.id().value());
                insert.setString(2, terms.product().name());
                insert.setInt(3, proposal.version());
                insert.setString(4, proposal.proposer().id());
                insert.setString(5, proposal.proposer().type().name());
                insert.setString(6, proposal.reason());
                insert.setString(7, proposal.correlation().value());
                insert.executeUpdate();
            }
            insertTerms(unitOfWork, proposal.id(), terms);
            appendEvent(unitOfWork, new HistoryRow(proposal.eventId(), proposal.id(), terms.product(), Optional.empty(),
                    LoanTermsVersionStatus.PROPOSED, proposal.proposer(), proposal.reason(), proposal.correlation()));
        } catch (SQLException failure) {
            throw failed("recording a terms version's proposal", failure);
        }
    }

    private static void insertTerms(Connection unitOfWork, LoanTermsVersionId id, LoanTerms terms) throws SQLException {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_terms (terms_version_id, " + TERM_COLUMNS.replace("t.", "") + ", product)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 1;
            insert.setObject(i++, id.value());
            insert.setString(i++, terms.currency().code());
            insert.setInt(i++, terms.currency().minorUnits());
            insert.setString(i++, terms.nominalAnnualRate().toPlainString());
            insert.setString(i++, terms.rateType().name());
            setLong(insert, i++, terms.originationFee().amount().map(Money::minorUnits));
            setInt(insert, i++, terms.originationFee().basisPoints());
            insert.setString(i++, terms.originationFeeTreatment().name());
            insert.setLong(i++, terms.lateFee().minorUnits());
            insert.setInt(i++, terms.lateFeeDay());
            insert.setLong(i++, terms.lateFeeCap().minorUnits());
            insert.setInt(i++, terms.graceDays());
            insert.setArray(i++, unitOfWork.createArrayOf("integer", terms.delinquencyBounds().toArray(Integer[]::new)));
            insert.setInt(i++, terms.defaultThresholdDays());
            insert.setString(i++, terms.allocationSequence().name());
            insert.setArray(i++, unitOfWork.createArrayOf("text",
                    terms.allocationOrder().stream().map(Enum::name).toArray(String[]::new)));
            insert.setString(i++, terms.overpaymentTreatment().name());
            insert.setBoolean(i++, terms.autoCollection());
            insert.setBoolean(i++, terms.partialCollection());
            insert.setString(i++, terms.instalmentRounding().name());
            insert.setString(i++, terms.interestRounding().name());
            insert.setString(i++, terms.dayCount().name());
            insert.setString(i++, terms.servicingZone());
            insert.setArray(i++, unitOfWork.createArrayOf("integer", terms.allowedDays().toArray(Integer[]::new)));
            setInt(insert, i++, terms.instalment().map(LoanTerms.InstalmentTerms::minFirstPeriodDays));
            insert.setInt(i++, terms.offerValidityDays());
            insert.setInt(i++, terms.payoffHorizonDays());
            setInt(insert, i++, terms.instalment().map(LoanTerms.InstalmentTerms::disbursementDeadlineHours));
            setLong(insert, i++, terms.revolving().map(own -> own.minimumPaymentFloor().minorUnits()));
            setInt(insert, i++, terms.revolving().map(LoanTerms.RevolvingTerms::minimumPaymentRatioBps));
            setInt(insert, i++, terms.revolving().map(LoanTerms.RevolvingTerms::paymentDueDays));
            setInt(insert, i++, terms.instalment().map(own -> own.scheduleEngine().number()));
            insert.setInt(i++, terms.accrualEngine().number());
            insert.setInt(i++, terms.allocationEngine().number());
            setInt(insert, i++, terms.revolving().map(own -> own.statementEngine().number()));
            insert.setString(i++, terms.agreementTemplate().id());
            insert.setInt(i++, terms.agreementTemplate().version());
            insert.setString(i, terms.product().name());
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<VersionRow> find(Connection unitOfWork, LoanTermsVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM lending.loan_terms_version WHERE id = ?", id.value(),
                "reading a terms version");
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, LoanTermsVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM lending.loan_terms_version WHERE id = ? FOR UPDATE",
                id.value(), "locking a terms version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, LoanProduct product) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM lending.loan_terms_version"
                + " WHERE product = ? AND status = 'ACTIVE' FOR UPDATE", product.name(), "locking the active terms version");
    }

    @Override
    public boolean retire(Connection unitOfWork, LoanTermsVersionId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_terms_version SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring a terms version", failure);
        }
    }

    @Override
    public Optional<DecisionStamps> decide(
            Connection unitOfWork, LoanTermsVersionId id, LoanTermsVersionStatus status, Actor actor, String reason) {
        if (status != LoanTermsVersionStatus.ACTIVE && status != LoanTermsVersionStatus.REJECTED) {
            throw new IllegalArgumentException("a decision activates or rejects");
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_terms_version SET status = ?, decided_by = ?, decided_by_type = ?,"
                        + " decided_at = transaction_timestamp(), decision_reason = ?"
                        + " WHERE id = ? AND status = 'PROPOSED' RETURNING decided_at, effective_from")) {
            update.setString(1, status.name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            try (ResultSet row = update.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DecisionStamps(row.getTimestamp(1).toInstant(),
                        Optional.ofNullable(row.getTimestamp(2)).map(Timestamp::toInstant)));
            }
        } catch (SQLException failure) {
            throw failed("deciding a terms version", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow history) {
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_terms_version_event (id, terms_version_id, product, from_status, to_status,"
                        + " actor, actor_type, reason, occurred_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, transaction_timestamp(), ?)")) {
            insert.setObject(1, history.eventId());
            insert.setObject(2, history.version().value());
            insert.setString(3, history.product().name());
            insert.setString(4, history.from().map(Enum::name).orElse(null));
            insert.setString(5, history.to().name());
            insert.setString(6, history.actor().id());
            insert.setString(7, history.actor().type().name());
            insert.setString(8, history.reason());
            insert.setString(9, history.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("recording a terms version's history", failure);
        }
    }

    @Override
    public Optional<LoanTermsVersion> version(Connection unitOfWork, LoanTermsVersionId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT v.id, v.product, v.version, v.status, v.proposed_by, v.proposed_at, v.decided_by, v.decided_at,"
                        + " v.effective_from, v.effective_to, " + TERM_COLUMNS
                        + " FROM lending.loan_terms_version v JOIN lending.loan_terms t ON t.terms_version_id = v.id"
                        + " WHERE v.id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                VersionRow version = versionRow(row);
                return Optional.of(new LoanTermsVersion(version, terms(version.product(), row)));
            }
        } catch (SQLException failure) {
            throw failed("reading a terms version's term set", failure);
        }
    }

    @Override
    public Optional<LoanTermsVersionId> activeAt(Connection unitOfWork, LoanProduct product, Instant instant) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM lending.loan_terms_version WHERE product = ? AND effective_from <= ?"
                        + " AND (effective_to IS NULL OR effective_to > ?)")) {
            select.setString(1, product.name());
            select.setTimestamp(2, Timestamp.from(instant));
            select.setTimestamp(3, Timestamp.from(instant));
            return single(select, "two terms versions in force at one instant");
        } catch (SQLException failure) {
            throw failed("reading the terms version in force at an instant", failure);
        }
    }

    @Override
    public Optional<LoanTermsVersionId> active(Connection unitOfWork, LoanProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM lending.loan_terms_version WHERE product = ? AND status = 'ACTIVE'")) {
            select.setString(1, product.name());
            return single(select, "two ACTIVE terms versions for one product");
        } catch (SQLException failure) {
            throw failed("reading the active terms version", failure);
        }
    }

    @Override
    public Optional<LoanTermsVersionId> shareActive(Connection unitOfWork, LoanProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM lending.loan_terms_version WHERE product = ? AND status = 'ACTIVE' FOR SHARE")) {
            select.setString(1, product.name());
            return single(select, "two ACTIVE terms versions for one product");
        } catch (SQLException failure) {
            throw failed("pinning the active terms version", failure);
        }
    }

    @Override
    public Map<LoanProduct, Integer> activeVersions(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT product, version FROM lending.loan_terms_version WHERE status = 'ACTIVE'");
                ResultSet row = select.executeQuery()) {
            Map<LoanProduct, Integer> versions = new EnumMap<>(LoanProduct.class);
            while (row.next()) {
                versions.put(LoanProduct.valueOf(row.getString(1)), row.getInt(2));
            }
            return versions;
        } catch (SQLException failure) {
            throw failed("reading the active terms versions", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static LoanTerms terms(LoanProduct product, ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").trim());
        int scale = row.getShort("scale");
        Optional<Long> feeMinor = optionalLong(row, "origination_fee_minor");
        Optional<Integer> feeBps = optionalInt(row, "origination_fee_bps");
        Optional<LoanTerms.InstalmentTerms> instalment = optionalInt(row, "schedule_engine_version").map(schedule ->
                instalmentTerms(row, schedule));
        Optional<LoanTerms.RevolvingTerms> revolving = optionalInt(row, "statement_engine_version").map(statement ->
                revolvingTerms(row, currency, scale, statement));
        List<AllocationComponent> order = new ArrayList<>();
        for (String component : (String[]) array(row, "allocation_order")) {
            order.add(AllocationComponent.valueOf(component));
        }
        return LoanTerms.builder()
                .product(product)
                .nominalAnnualRate(new BigDecimal(row.getString("nominal_annual_rate")))
                .rateType(RateType.valueOf(row.getString("rate_type")))
                .originationFee(new LoanTerms.OriginationFee(
                        feeMinor.map(minor -> Money.ofPersisted(minor, currency, scale)), feeBps))
                .originationFeeTreatment(OriginationFeeTreatment.valueOf(row.getString("origination_fee_treatment")))
                .lateFee(Money.ofPersisted(row.getLong("late_fee_minor"), currency, scale))
                .lateFeeDay(row.getInt("late_fee_day"))
                .lateFeeCap(Money.ofPersisted(row.getLong("late_fee_cap_minor"), currency, scale))
                .graceDays(row.getInt("grace_days"))
                .delinquencyBounds(List.of((Integer[]) array(row, "delinquency_bounds")))
                .defaultThresholdDays(row.getInt("default_threshold_days"))
                .allocationSequence(AllocationSequence.valueOf(row.getString("allocation_sequence")))
                .allocationOrder(order)
                .overpaymentTreatment(OverpaymentTreatment.valueOf(row.getString("overpayment_treatment")))
                .autoCollection(row.getBoolean("auto_collection"))
                .partialCollection(row.getBoolean("partial_collection"))
                .instalmentRounding(RoundingMode.valueOf(row.getString("instalment_rounding")))
                .interestRounding(RoundingMode.valueOf(row.getString("interest_rounding")))
                .dayCount(DayCountConvention.valueOf(row.getString("day_count")))
                .servicingZone(row.getString("servicing_zone"))
                .allowedDays(List.of((Integer[]) array(row, "allowed_days")))
                .offerValidityDays(row.getInt("offer_validity_days"))
                .payoffHorizonDays(row.getInt("payoff_horizon_days"))
                .accrualEngine(engine(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version")))
                .allocationEngine(engine(LendingEngine.ALLOCATION, row.getInt("allocation_engine_version")))
                .instalment(instalment)
                .revolving(revolving)
                .agreementTemplate(AgreementTemplate.of(row.getString("agreement_template_id"),
                                row.getInt("agreement_template_version"))
                        .orElseThrow(() -> new IllegalStateException("a stored terms version names an unregistered template")))
                .build();
    }

    private static LoanTerms.InstalmentTerms instalmentTerms(ResultSet row, int schedule) {
        try {
            return new LoanTerms.InstalmentTerms(row.getInt("min_first_period_days"),
                    row.getInt("disbursement_deadline_hours"), engine(LendingEngine.SCHEDULE, schedule));
        } catch (SQLException failure) {
            throw failed("reading an instalment loan's terms", failure);
        }
    }

    private static LoanTerms.RevolvingTerms revolvingTerms(ResultSet row, CurrencyCode currency, int scale, int statement) {
        try {
            return new LoanTerms.RevolvingTerms(
                    Money.ofPersisted(row.getLong("minimum_payment_floor_minor"), currency, scale),
                    row.getInt("minimum_payment_ratio_bps"), row.getInt("payment_due_days"),
                    engine(LendingEngine.STATEMENT, statement));
        } catch (SQLException failure) {
            throw failed("reading a credit line's terms", failure);
        }
    }

    private static EngineVersion engine(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number)
                .orElseThrow(() -> new IllegalStateException("a stored terms version names an engine version this build"
                        + " does not hold"));
    }

    private static Object[] array(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        return (Object[]) array.getArray();
    }

    private static Optional<Long> optionalLong(ResultSet row, String column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<Integer> optionalInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static void setLong(PreparedStatement statement, int index, Optional<Long> value) throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.get());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }

    private static void setInt(PreparedStatement statement, int index, Optional<Integer> value) throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static boolean exists(Connection unitOfWork, String sql, LoanProduct product, String what) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setString(1, product.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed(what, failure);
        }
    }

    private static Optional<VersionRow> row(Connection unitOfWork, String sql, Object key, String what) {
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(versionRow(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(what, failure);
        }
    }

    private static VersionRow versionRow(ResultSet row) throws SQLException {
        return new VersionRow(
                LoanTermsVersionId.of(row.getObject("id", UUID.class)),
                LoanProduct.valueOf(row.getString("product")),
                row.getInt("version"),
                LoanTermsVersionStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                Optional.ofNullable(row.getString("decided_by")),
                instant(row, "decided_at"),
                instant(row, "effective_from"),
                instant(row, "effective_to"));
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getTimestamp(column)).map(Timestamp::toInstant);
    }

    private static Optional<LoanTermsVersionId> single(PreparedStatement select, String twoFound) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            LoanTermsVersionId id = LoanTermsVersionId.of(row.getObject(1, UUID.class));
            if (row.next()) {
                throw new IllegalStateException(twoFound);
            }
            return Optional.of(id);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
