package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationSequence;
import com.finapp.creditmath.DayCountConvention;
import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
import com.finapp.creditmath.OverpaymentTreatment;
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
 * {@link BnplTermsStore} over plain JDBC (`P12-TSK-004`, ADR-0033; lending's {@code JdbcLoanTermsStore}): every time the
 * database's, every move a conditional {@code UPDATE} of the machine row's decision columns under {@code bnpl V002}'s
 * edge trigger, the term set and the history INSERT-only, every failure its operation and SQLSTATE only
 * ({@link BnplStorageException}).
 */
public final class JdbcBnplTermsStore implements BnplTermsStore {

    /**
     * bnpl's administration namespace (`DISTRIBUTED_EXECUTION.md` section 3, reserved by the Phase 11 to 12 transition and
     * registered here, by its first writer): every proposal and decision of one product's terms serialises on
     * {@code (12, hashtext(product))}.
     */
    public static final int NAMESPACE = 12;

    /** A0's statement, pinned by {@code BnplTermsNamespaceIsPinnedTest}. */
    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", hashtext(?))";

    /**
     * A0 in SHARED mode - taken only by a pin whose first read found no {@code ACTIVE} version (the Phase 12 transition
     * gate's A3-I1): every administration act holds A0 exclusively to its commit, so once this is granted no activation is
     * in flight, and the statement after it reads every committed one. Pinned beside {@link #LOCK_SQL}.
     */
    static final String SHARED_LOCK_SQL = "SELECT pg_advisory_xact_lock_shared(" + NAMESPACE + ", hashtext(?))";

    private static final String SHARE_ACTIVE_SQL =
            "SELECT id FROM bnpl.bnpl_terms_version WHERE product = ? AND status = 'ACTIVE' FOR SHARE";

    private static final String ROW_COLUMNS =
            "id, product, version, status, proposed_by, proposed_at, decided_by, decided_at, effective_from, effective_to";

    static final String TERM_COLUMNS =
            "t.currency, t.scale, t.nominal_annual_rate, t.frequency, t.late_fee_minor, t.late_fee_day,"
                    + " t.late_fee_cap_minor, t.grace_days, t.delinquency_bounds, t.default_threshold_days,"
                    + " t.allocation_sequence, t.allocation_order, t.overpayment_treatment, t.auto_collection,"
                    + " t.partial_collection, t.collection_retry_days, t.instalment_rounding, t.interest_rounding,"
                    + " t.day_count, t.servicing_zone, t.instalment_count, t.interval_days, t.allowed_terms,"
                    + " t.min_first_period_days, t.offer_validity_minutes, t.dispute_response_days,"
                    + " t.dispute_four_eyes_threshold_minor, t.payoff_horizon_days, t.schedule_engine, t.accrual_engine,"
                    + " t.allocation_engine, t.refund_allocation_engine, t.agreement_template_id,"
                    + " t.agreement_template_version";

    @Override
    public void lockProduct(Connection unitOfWork, BnplProduct product) {
        try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_SQL)) {
            lock.setString(1, product.name());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the terms administration lock", failure);
        }
    }

    @Override
    public boolean proposalPending(Connection unitOfWork, BnplProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM bnpl.bnpl_terms_version WHERE product = ? AND status = 'PROPOSED')")) {
            select.setString(1, product.name());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading the pending terms version", failure);
        }
    }

    @Override
    public int maxVersion(Connection unitOfWork, BnplProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT COALESCE(max(version), 0) FROM bnpl.bnpl_terms_version WHERE product = ?")) {
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
        BnplTerms terms = proposal.terms();
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO bnpl.bnpl_terms_version (id, product, version, status, proposed_by, proposed_by_type,"
                            + " proposed_at, proposal_reason, correlation_id)"
                            + " VALUES (?, ?, ?, 'PROPOSED', ?, ?, clock_timestamp(), ?, ?)")) {
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
                    BnplTermsVersionStatus.PROPOSED, proposal.proposer(), proposal.reason(), proposal.correlation()));
        } catch (SQLException failure) {
            throw failed("recording a terms version's proposal", failure);
        }
    }

    private static void insertTerms(Connection unitOfWork, BnplTermsVersionId id, BnplTerms terms) throws SQLException {
        String columns = TERM_COLUMNS.replace("t.", "");
        String placeholders = "?" + ", ?".repeat(columns.split(",").length + 1);
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_terms (terms_version_id, " + columns + ", product) VALUES (" + placeholders + ")")) {
            Optional<BnplTerms.PayIn4Terms> payIn4 = terms.payIn4();
            Optional<BnplTerms.PayInNTerms> payInN = terms.payInN();
            int i = 1;
            insert.setObject(i++, id.value());
            insert.setString(i++, terms.currency().code());
            insert.setInt(i++, terms.currency().minorUnits());
            insert.setString(i++, terms.nominalAnnualRate().toPlainString());
            insert.setString(i++, terms.frequency().name());
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
            insert.setInt(i++, terms.collectionRetryDays());
            insert.setString(i++, terms.instalmentRounding().name());
            insert.setString(i++, payInN.map(own -> own.interestRounding().name()).orElse(null));
            insert.setString(i++, payInN.map(own -> own.dayCount().name()).orElse(null));
            insert.setString(i++, terms.servicingZone());
            setInt(insert, i++, payIn4.map(BnplTerms.PayIn4Terms::instalments));
            setInt(insert, i++, payIn4.map(BnplTerms.PayIn4Terms::intervalDays));
            if (payInN.isPresent()) {
                insert.setArray(i++, unitOfWork.createArrayOf("integer", payInN.get().allowedTerms().toArray(Integer[]::new)));
            } else {
                insert.setNull(i++, Types.ARRAY);
            }
            setInt(insert, i++, payInN.map(BnplTerms.PayInNTerms::minFirstPeriodDays));
            insert.setInt(i++, terms.offerValidityMinutes());
            insert.setInt(i++, terms.disputeResponseDays());
            insert.setLong(i++, terms.disputeFourEyesThreshold().minorUnits());
            insert.setInt(i++, terms.payoffHorizonDays());
            insert.setString(i++, payIn4.map(own -> own.scheduleEngine().name())
                    .orElseGet(() -> payInN.orElseThrow().scheduleEngine().name()));
            insert.setString(i++, payInN.map(own -> own.accrualEngine().name()).orElse(null));
            insert.setString(i++, terms.allocationEngine().name());
            insert.setString(i++, terms.refundAllocationEngine().name());
            insert.setString(i++, terms.agreementTemplate().id());
            insert.setInt(i++, terms.agreementTemplate().version());
            insert.setString(i, terms.product().name());
            insert.executeUpdate();
        }
    }

    @Override
    public Optional<VersionRow> find(Connection unitOfWork, BnplTermsVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM bnpl.bnpl_terms_version WHERE id = ?", id.value(),
                "reading a terms version");
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, BnplTermsVersionId id) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM bnpl.bnpl_terms_version WHERE id = ? FOR UPDATE",
                id.value(), "locking a terms version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork, BnplProduct product) {
        return row(unitOfWork, "SELECT " + ROW_COLUMNS + " FROM bnpl.bnpl_terms_version"
                + " WHERE product = ? AND status = 'ACTIVE' FOR UPDATE", product.name(), "locking the active terms version");
    }

    @Override
    public boolean retire(Connection unitOfWork, BnplTermsVersionId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_terms_version SET status = 'RETIRED' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring a terms version", failure);
        }
    }

    @Override
    public Optional<DecisionStamps> decide(
            Connection unitOfWork, BnplTermsVersionId id, BnplTermsVersionStatus status, Actor actor, String reason) {
        if (status != BnplTermsVersionStatus.ACTIVE && status != BnplTermsVersionStatus.REJECTED) {
            throw new IllegalArgumentException("a decision activates or rejects");
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_terms_version SET status = ?, decided_by = ?, decided_by_type = ?,"
                        + " decided_at = clock_timestamp(), decision_reason = ?"
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
                "INSERT INTO bnpl.bnpl_terms_version_event (id, terms_version_id, product, from_status, to_status,"
                        + " actor, actor_type, reason, occurred_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
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
    public Optional<BnplTermsVersion> version(Connection unitOfWork, BnplTermsVersionId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT v.id, v.product, v.version, v.status, v.proposed_by, v.proposed_at, v.decided_by, v.decided_at,"
                        + " v.effective_from, v.effective_to, " + TERM_COLUMNS
                        + " FROM bnpl.bnpl_terms_version v JOIN bnpl.bnpl_terms t ON t.terms_version_id = v.id"
                        + " WHERE v.id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                VersionRow version = versionRow(row);
                return Optional.of(new BnplTermsVersion(version, terms(version.product(), row)));
            }
        } catch (SQLException failure) {
            throw failed("reading a terms version's term set", failure);
        }
    }

    @Override
    public Optional<BnplTermsVersionId> activeAt(Connection unitOfWork, BnplProduct product, Instant instant) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM bnpl.bnpl_terms_version WHERE product = ? AND effective_from <= ?"
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
    public Optional<BnplTermsVersionId> active(Connection unitOfWork, BnplProduct product) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id FROM bnpl.bnpl_terms_version WHERE product = ? AND status = 'ACTIVE'")) {
            select.setString(1, product.name());
            return single(select, "two ACTIVE terms versions for one product");
        } catch (SQLException failure) {
            throw failed("reading the active terms version", failure);
        }
    }

    /**
     * The {@code ACTIVE} row {@code FOR SHARE}; read again after A0 in shared mode when the first read finds none (the
     * Phase 12 transition gate's A3-I1, the class of {@code P12-TST-001}'s B4 finding). Under {@code READ COMMITTED} a
     * {@code FOR SHARE} that waited on an activation's lock re-checks only the row it waited on: it finds the predecessor
     * {@code RETIRED} and never sees the successor, born after its snapshot - "neither". The second read is a statement
     * begun after A0 is granted shared, so no activation is in flight (each holds A0 exclusively to its commit) and the
     * successor, if one committed, is in its snapshot: a retirement commits only beside its successor, so an empty second
     * read is genuine - no version of the product is {@code ACTIVE}. The common path takes nothing of the administration's,
     * so an activation still waits on the pin at A2; the shared A0 is held only by a pin that found none, which holds no
     * terms row an administration act waits on - one-way.
     */
    @Override
    public Optional<BnplTermsVersionId> shareActive(Connection unitOfWork, BnplProduct product) {
        try {
            Optional<BnplTermsVersionId> pinned = shareActiveOnce(unitOfWork, product);
            if (pinned.isPresent()) {
                return pinned;
            }
            try (PreparedStatement lock = unitOfWork.prepareStatement(SHARED_LOCK_SQL)) {
                lock.setString(1, product.name());
                lock.execute();
            }
            return shareActiveOnce(unitOfWork, product);
        } catch (SQLException failure) {
            throw failed("pinning the active terms version", failure);
        }
    }

    private static Optional<BnplTermsVersionId> shareActiveOnce(Connection unitOfWork, BnplProduct product)
            throws SQLException {
        try (PreparedStatement select = unitOfWork.prepareStatement(SHARE_ACTIVE_SQL)) {
            select.setString(1, product.name());
            return single(select, "two ACTIVE terms versions for one product");
        }
    }

    @Override
    public Map<BnplProduct, Integer> activeVersions(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                        "SELECT product, version FROM bnpl.bnpl_terms_version WHERE status = 'ACTIVE'");
                ResultSet row = select.executeQuery()) {
            Map<BnplProduct, Integer> versions = new EnumMap<>(BnplProduct.class);
            while (row.next()) {
                versions.put(BnplProduct.valueOf(row.getString(1)), row.getInt(2));
            }
            return versions;
        } catch (SQLException failure) {
            throw failed("reading the active terms versions", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static BnplTerms terms(BnplProduct product, ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").trim());
        int scale = row.getShort("scale");
        List<AllocationComponent> order = new ArrayList<>();
        for (String component : (String[]) array(row, "allocation_order")) {
            order.add(AllocationComponent.valueOf(component));
        }
        Optional<BnplTerms.PayIn4Terms> payIn4 = Optional.empty();
        Optional<BnplTerms.PayInNTerms> payInN = Optional.empty();
        String schedule = row.getString("schedule_engine");
        if (product.shape() == BnplProduct.PlanShape.FIXED) {
            payIn4 = Optional.of(new BnplTerms.PayIn4Terms(row.getInt("instalment_count"), row.getInt("interval_days"),
                    bnplEngine(schedule)));
        } else {
            payInN = Optional.of(new BnplTerms.PayInNTerms(
                    List.of((Integer[]) array(row, "allowed_terms")),
                    row.getInt("min_first_period_days"),
                    DayCountConvention.valueOf(row.getString("day_count")),
                    RoundingMode.valueOf(row.getString("interest_rounding")),
                    engine(LendingEngine.SCHEDULE, schedule),
                    engine(LendingEngine.ACCRUAL, row.getString("accrual_engine"))));
        }
        return BnplTerms.builder()
                .product(product)
                .frequency(InstalmentFrequency.valueOf(row.getString("frequency")))
                .nominalAnnualRate(new BigDecimal(row.getString("nominal_annual_rate")))
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
                .collectionRetryDays(row.getInt("collection_retry_days"))
                .instalmentRounding(RoundingMode.valueOf(row.getString("instalment_rounding")))
                .servicingZone(row.getString("servicing_zone"))
                .offerValidityMinutes(row.getInt("offer_validity_minutes"))
                .disputeResponseDays(row.getInt("dispute_response_days"))
                .disputeFourEyesThreshold(Money.ofPersisted(row.getLong("dispute_four_eyes_threshold_minor"), currency,
                        scale))
                .payoffHorizonDays(row.getInt("payoff_horizon_days"))
                .allocationEngine(engine(LendingEngine.ALLOCATION, row.getString("allocation_engine")))
                .refundAllocationEngine(bnplEngine(row.getString("refund_allocation_engine")))
                .payIn4(payIn4)
                .payInN(payInN)
                .agreementTemplate(BnplAgreementTemplate.of(row.getString("agreement_template_id"),
                                row.getInt("agreement_template_version"))
                        .orElseThrow(() -> new IllegalStateException("a stored terms version names an unregistered template")))
                .build();
    }

    /** A stored creditmath engine version by name - one this build holds, of the engine the column names. */
    private static EngineVersion engine(LendingEngine engine, String name) {
        for (EngineVersion version : EngineVersion.values()) {
            if (version.name().equals(name) && version.engine() == engine) {
                return version;
            }
        }
        throw new IllegalStateException("a stored terms version names an engine version this build does not hold");
    }

    /** A stored bnpl engine version by name - one this build holds. */
    private static BnplEngine bnplEngine(String name) {
        for (BnplEngine version : BnplEngine.values()) {
            if (version.name().equals(name)) {
                return version;
            }
        }
        throw new IllegalStateException("a stored terms version names an engine version this build does not hold");
    }

    private static Object[] array(ResultSet row, String column) throws SQLException {
        Array array = row.getArray(column);
        return (Object[]) array.getArray();
    }

    private static void setInt(PreparedStatement statement, int index, Optional<Integer> value) throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
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
                BnplTermsVersionId.of(row.getObject("id", UUID.class)),
                BnplProduct.valueOf(row.getString("product")),
                row.getInt("version"),
                BnplTermsVersionStatus.valueOf(row.getString("status")),
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

    private static Optional<BnplTermsVersionId> single(PreparedStatement select, String twoFound) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            BnplTermsVersionId id = BnplTermsVersionId.of(row.getObject(1, UUID.class));
            if (row.next()) {
                throw new IllegalStateException(twoFound);
            }
            return Optional.of(id);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
