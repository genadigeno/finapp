package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanOfferStore} over plain JDBC (`P11-TSK-011`, ADR-0033): every time the database's, every customer read
 * owner-scoped in the statement itself (ADR-0031), every failure its operation and SQLSTATE only
 * ({@link LendingStorageException}).
 */
public final class JdbcLoanOfferStore implements LoanOfferStore {

    private static final String COLUMNS =
            "o.id, o.application_id, o.party_id, o.product, o.status, o.decision_id, o.terms_version_id, v.version,"
                    + " o.offered_at, o.expires_at, o.closed_at, o.correlation_id, t.currency, t.scale, t.amount_minor,"
                    + " t.term_months, t.nominal_annual_rate, t.repayment_day, t.illustrative_start, t.first_due_date,"
                    + " t.instalment_minor, t.final_instalment_minor, t.total_interest_minor, t.total_payable_minor,"
                    + " t.minimum_payment_floor_minor, t.minimum_payment_ratio_bps, t.payment_due_days,"
                    + " t.schedule_engine_version, t.accrual_engine_version, t.allocation_engine_version,"
                    + " t.statement_engine_version, t.agreement_template_id, t.agreement_template_version,"
                    + " t.canonical_terms, t.terms_sha256, t.document_sha256";

    private static final String FROM = " FROM lending.loan_offer o"
            + " JOIN lending.loan_offer_terms t ON t.offer_id = o.id"
            + " JOIN lending.loan_terms_version v ON v.id = o.terms_version_id";

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public Instant insert(Connection unitOfWork, NewOffer offer) {
        Objects.requireNonNull(offer, "offer");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_offer (id, application_id, party_id, product, status, decision_id,"
                        + " terms_version_id, offered_at, decision_valid_until, expires_at, next_step_at, correlation_id)"
                        // The expiry and the permit are the trigger's; the placeholders are overwritten.
                        + " VALUES (?, ?, ?, ?, 'OFFERED', ?, ?, ?, ?, ?, ?, ?) RETURNING expires_at")) {
            insert.setObject(1, offer.id().value());
            insert.setObject(2, offer.application().value());
            insert.setObject(3, offer.party());
            insert.setString(4, offer.product().name());
            insert.setObject(5, offer.decision());
            insert.setObject(6, offer.termsVersion().value());
            insert.setTimestamp(7, Timestamp.from(offer.offeredAt()));
            insert.setTimestamp(8, Timestamp.from(offer.decisionValidUntil()));
            insert.setTimestamp(9, Timestamp.from(offer.decisionValidUntil()));
            insert.setTimestamp(10, Timestamp.from(offer.decisionValidUntil()));
            insert.setString(11, offer.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a loan offer", failure);
        }
    }

    @Override
    public void insertTerms(Connection unitOfWork, LoanOfferId offer, LoanProduct product, LoanOffer.Terms terms) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(terms, "terms");
        Optional<LoanOffer.Illustration> illustration = terms.illustration();
        Optional<LoanOffer.MinimumPaymentRule> rule = terms.minimumPayment();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_offer_terms (offer_id, product, currency, scale, amount_minor, term_months,"
                        + " nominal_annual_rate, repayment_day, illustrative_start, first_due_date, instalment_minor,"
                        + " final_instalment_minor, total_interest_minor, total_payable_minor,"
                        + " minimum_payment_floor_minor, minimum_payment_ratio_bps, payment_due_days,"
                        + " schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                        + " statement_engine_version, agreement_template_id, agreement_template_version,"
                        + " canonical_format, canonical_terms, terms_sha256, document_sha256)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            insert.setObject(++i, offer.value());
            insert.setString(++i, product.name());
            insert.setString(++i, terms.amount().currency().code());
            insert.setInt(++i, terms.amount().scale());
            insert.setLong(++i, terms.amount().minorUnits());
            setInt(insert, ++i, terms.termMonths());
            insert.setString(++i, terms.nominalAnnualRate().toPlainString());
            insert.setInt(++i, terms.repaymentDay());
            setDate(insert, ++i, illustration.map(LoanOffer.Illustration::start));
            setDate(insert, ++i, illustration.map(LoanOffer.Illustration::firstDueDate));
            setMinor(insert, ++i, illustration.map(LoanOffer.Illustration::instalment));
            setMinor(insert, ++i, illustration.map(LoanOffer.Illustration::finalInstalment));
            setMinor(insert, ++i, illustration.map(LoanOffer.Illustration::totalInterest));
            setMinor(insert, ++i, illustration.map(LoanOffer.Illustration::totalPayable));
            setMinor(insert, ++i, rule.map(LoanOffer.MinimumPaymentRule::floor));
            setInt(insert, ++i, rule.map(LoanOffer.MinimumPaymentRule::ratioBps));
            setInt(insert, ++i, rule.map(LoanOffer.MinimumPaymentRule::paymentDueDays));
            setInt(insert, ++i, terms.scheduleEngine().map(EngineVersion::number));
            insert.setInt(++i, terms.accrualEngine().number());
            insert.setInt(++i, terms.allocationEngine().number());
            setInt(insert, ++i, terms.statementEngine().map(EngineVersion::number));
            insert.setString(++i, terms.template().id());
            insert.setInt(++i, terms.template().version());
            insert.setInt(++i, CanonicalOfferTerms.FORMAT);
            insert.setString(++i, terms.canonicalTerms());
            insert.setBytes(++i, terms.termsSha256());
            insert.setBytes(++i, terms.documentSha256());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a loan offer's terms", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_offer_event (id, offer_id, from_status, to_status, reason, actor, actor_type,"
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, row.eventId());
            insert.setObject(2, row.offer().value());
            insert.setString(3, row.from().map(Enum::name).orElse(null));
            insert.setString(4, row.to().name());
            insert.setString(5, row.reason());
            insert.setString(6, row.actor().id());
            insert.setString(7, row.actor().type().name());
            insert.setString(8, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending a loan offer event", failure);
        }
    }

    @Override
    public Optional<LoanOffer> ownedBy(Connection unitOfWork, LoanOfferId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE o.id = ? AND o.party_id = ?")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("reading a loan offer", failure);
        }
    }

    @Override
    public Optional<LoanOffer> lockOwnedBy(Connection unitOfWork, LoanOfferId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE o.id = ? AND o.party_id = ? FOR UPDATE OF o")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("locking a loan offer", failure);
        }
    }

    @Override
    public Optional<LoanOfferId> offerFor(Connection unitOfWork, LoanApplicationId application, UUID party) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT o.id FROM lending.loan_offer o WHERE o.application_id = ? AND o.party_id = ?")) {
            select.setObject(1, application.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(LoanOfferId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading an application's loan offer", failure);
        }
    }

    @Override
    public boolean decline(Connection unitOfWork, LoanOfferId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_offer SET status = 'DECLINED' WHERE id = ? AND status = 'OFFERED'"
                        + " AND expires_at > statement_timestamp()")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("declining a loan offer", failure);
        }
    }

    @Override
    public boolean accept(Connection unitOfWork, LoanOfferId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_offer SET status = 'ACCEPTED' WHERE id = ? AND status = 'OFFERED'"
                        + " AND expires_at > statement_timestamp()")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("accepting a loan offer", failure);
        }
    }

    @Override
    public List<LoanOfferId> claimDue(Connection unitOfWork, int limit, Duration permit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one offer");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE lending.loan_offer SET next_step_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM lending.loan_offer WHERE status = 'OFFERED'"
                        + " AND next_step_at <= statement_timestamp()"
                        + " ORDER BY next_step_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status = 'OFFERED' RETURNING id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<LoanOfferId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(LoanOfferId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming due loan offers", failure);
        }
    }

    @Override
    public Optional<LoanOffer> lockClaimed(Connection unitOfWork, LoanOfferId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE o.id = ? FOR UPDATE OF o")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(offer(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("locking a claimed loan offer", failure);
        }
    }

    @Override
    public boolean expire(Connection unitOfWork, LoanOfferId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_offer SET status = 'EXPIRED' WHERE id = ? AND status = 'OFFERED'"
                        + " AND expires_at <= statement_timestamp()")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("expiring a loan offer", failure);
        }
    }

    @Override
    public void permitAtExpiry(Connection unitOfWork, LoanOfferId id) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_offer SET next_step_at = expires_at WHERE id = ? AND status = 'OFFERED'"
                        + " AND next_step_at IS DISTINCT FROM expires_at")) {
            update.setObject(1, id.value());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw failed("re-stamping a loan offer's permit", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Binds the id and the owner and reads the one row - the statement is the caller's own. */
    private static Optional<LoanOffer> owned(PreparedStatement select, LoanOfferId id, UUID party) throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        select.setObject(1, id.value());
        select.setObject(2, party);
        try (ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(offer(row)) : Optional.empty();
        }
    }

    private static LoanOffer offer(ResultSet row) throws SQLException {
        LoanProduct product = LoanProduct.valueOf(row.getString("product"));
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        int scale = row.getInt("scale");
        Optional<LoanOffer.Illustration> illustration = Optional.empty();
        java.time.LocalDate start = row.getObject("illustrative_start", java.time.LocalDate.class);
        if (start != null) {
            illustration = Optional.of(new LoanOffer.Illustration(
                    start,
                    row.getObject("first_due_date", java.time.LocalDate.class),
                    money(row, "instalment_minor", currency, scale),
                    money(row, "final_instalment_minor", currency, scale),
                    money(row, "total_interest_minor", currency, scale),
                    money(row, "total_payable_minor", currency, scale)));
        }
        Optional<LoanOffer.MinimumPaymentRule> rule = Optional.empty();
        if (row.getObject("minimum_payment_floor_minor") != null) {
            rule = Optional.of(new LoanOffer.MinimumPaymentRule(money(row, "minimum_payment_floor_minor", currency, scale),
                    row.getInt("minimum_payment_ratio_bps"), row.getInt("payment_due_days")));
        }
        LoanOffer.Terms terms = new LoanOffer.Terms(
                money(row, "amount_minor", currency, scale),
                optionalInt(row, "term_months"),
                new BigDecimal(row.getString("nominal_annual_rate")),
                row.getInt("repayment_day"),
                illustration,
                rule,
                optionalInt(row, "schedule_engine_version").map(n -> engine(LendingEngine.SCHEDULE, n)),
                engine(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version")),
                engine(LendingEngine.ALLOCATION, row.getInt("allocation_engine_version")),
                optionalInt(row, "statement_engine_version").map(n -> engine(LendingEngine.STATEMENT, n)),
                AgreementTemplate.of(row.getString("agreement_template_id"), row.getInt("agreement_template_version"))
                        .orElseThrow(() -> new IllegalStateException("an offer names a template this build registers")),
                row.getString("canonical_terms"),
                row.getBytes("terms_sha256"),
                row.getBytes("document_sha256"));
        Timestamp closed = row.getTimestamp("closed_at");
        return new LoanOffer(
                LoanOfferId.of(row.getObject("id", UUID.class)),
                LoanApplicationId.of(row.getObject("application_id", UUID.class)),
                row.getObject("party_id", UUID.class),
                product,
                LoanOfferStatus.valueOf(row.getString("status")),
                row.getObject("decision_id", UUID.class),
                LoanTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                row.getInt("version"),
                row.getTimestamp("offered_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                Optional.ofNullable(closed).map(Timestamp::toInstant),
                CorrelationId.of(row.getString("correlation_id")),
                terms);
    }

    private static EngineVersion engine(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number)
                .orElseThrow(() -> new IllegalStateException("an offer pins an engine version this build holds"));
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency, scale);
    }

    private static Optional<Integer> optionalInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static void setInt(PreparedStatement statement, int index, Optional<Integer> value) throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static void setMinor(PreparedStatement statement, int index, Optional<Money> value) throws SQLException {
        if (value.isPresent()) {
            statement.setLong(index, value.get().minorUnits());
        } else {
            statement.setNull(index, Types.BIGINT);
        }
    }

    private static void setDate(PreparedStatement statement, int index, Optional<java.time.LocalDate> value)
            throws SQLException {
        if (value.isPresent()) {
            // A LocalDate, never java.sql.Date: no default time zone between the day and the column.
            statement.setObject(index, value.get());
        } else {
            statement.setNull(index, Types.DATE);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
