package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplOfferStore} over plain JDBC (`P12-TSK-011`, ADR-0033; lending's {@code JdbcLoanOfferStore}): every instant
 * the database's - {@code bnpl V004}'s trigger stamps the offering instant, the expiry and the permit, and the moves
 * judge {@code clock_timestamp()} after the row lock - every customer read owner-scoped in the statement, every failure
 * its operation and SQLSTATE only ({@link BnplStorageException}).
 */
public final class JdbcBnplOfferStore implements BnplOfferStore {

    private static final String COLUMNS =
            "o.id, o.application_id, o.party_id, o.product, o.plan, o.status, o.decision_id, o.terms_version_id,"
                    + " v.version, o.checkout_session_id, o.offered_at, o.expires_at, o.closed_at, o.correlation_id";

    private static final String FROM =
            " FROM bnpl.bnpl_offer o JOIN bnpl.bnpl_terms_version v ON v.id = o.terms_version_id";

    @Override
    public Offered insert(Connection unitOfWork, NewOffer offer) {
        Objects.requireNonNull(offer, "offer");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // offered_at, expires_at and next_step_at are the trigger's: whatever is bound here is overwritten.
                "INSERT INTO bnpl.bnpl_offer (id, application_id, party_id, product, plan, status, decision_id,"
                        + " terms_version_id, checkout_session_id, offered_at, decision_valid_until, session_expires_at,"
                        + " expires_at, next_step_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, 'OFFERED', ?, ?, ?, clock_timestamp(), ?, ?, clock_timestamp(),"
                        + " clock_timestamp(), ?) RETURNING offered_at, expires_at")) {
            insert.setObject(1, offer.id().value());
            insert.setObject(2, offer.application().value());
            insert.setObject(3, offer.party());
            insert.setString(4, offer.product().name());
            insert.setString(5, offer.plan().name());
            insert.setObject(6, offer.decision());
            insert.setObject(7, offer.termsVersion().value());
            insert.setObject(8, offer.checkoutSession());
            insert.setTimestamp(9, Timestamp.from(offer.decisionValidUntil()));
            insert.setTimestamp(10, Timestamp.from(offer.sessionExpiresAt()));
            insert.setString(11, offer.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return new Offered(row.getTimestamp("offered_at").toInstant(), row.getTimestamp("expires_at").toInstant());
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL offer", failure);
        }
    }

    @Override
    public void insertTerms(Connection unitOfWork, BnplOfferId offer, StoredTerms terms) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(terms, "terms");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_offer_terms (offer_id, product, plan, currency, scale, amount_minor,"
                        + " nominal_annual_rate, schedule_engine, illustrative_start, instalment_count, first_due_date,"
                        + " last_due_date, first_instalment_minor, regular_instalment_minor, final_instalment_minor,"
                        + " total_interest_minor, total_payable_minor, canonical_format, canonical_terms, terms_sha256,"
                        + " document_sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            Money amount = terms.amount();
            insert.setObject(1, offer.value());
            insert.setString(2, terms.product().name());
            insert.setString(3, terms.plan().name());
            insert.setString(4, amount.currency().code());
            insert.setInt(5, amount.scale());
            insert.setLong(6, amount.minorUnits());
            insert.setString(7, terms.nominalAnnualRate().toPlainString());
            insert.setString(8, terms.scheduleEngine());
            insert.setDate(9, Date.valueOf(terms.illustrativeStart()));
            insert.setInt(10, terms.instalmentCount());
            insert.setDate(11, Date.valueOf(terms.firstDueDate()));
            insert.setDate(12, Date.valueOf(terms.lastDueDate()));
            insert.setLong(13, terms.firstInstalment().minorUnits());
            insert.setLong(14, terms.regularInstalment().minorUnits());
            insert.setLong(15, terms.finalInstalment().minorUnits());
            insert.setLong(16, terms.totalInterest().minorUnits());
            insert.setLong(17, terms.totalPayable().minorUnits());
            insert.setInt(18, terms.canonicalFormat());
            insert.setString(19, terms.canonicalTerms());
            insert.setBytes(20, terms.termsSha256());
            insert.setBytes(21, terms.documentSha256());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL offer's terms", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_offer_event (id, offer_id, from_status, to_status, reason, actor, actor_type,"
                        + " occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
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
            throw failed("appending a BNPL offer event", failure);
        }
    }

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT clock_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public List<BnplOfferId> claimDue(Connection unitOfWork, int limit, Duration permit) {
        Objects.requireNonNull(permit, "permit");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one offer");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_offer SET next_step_at = clock_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM bnpl.bnpl_offer"
                        + " WHERE status = 'OFFERED' AND next_step_at <= clock_timestamp()"
                        + " ORDER BY next_step_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " RETURNING id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<BnplOfferId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(BnplOfferId.of(rows.getObject("id", UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming BNPL offers", failure);
        }
    }

    @Override
    public Optional<BnplOffer> lockClaimed(Connection unitOfWork, BnplOfferId id) {
        return one(unitOfWork, " WHERE o.id = ? FOR UPDATE OF o", "locking a claimed BNPL offer",
                Objects.requireNonNull(id, "id").value());
    }

    @Override
    public Optional<BnplOffer> lockOwnedBy(Connection unitOfWork, BnplOfferId id, UUID party) {
        return one(unitOfWork, " WHERE o.id = ? AND o.party_id = ? FOR UPDATE OF o", "locking a BNPL offer",
                Objects.requireNonNull(id, "id").value(), Objects.requireNonNull(party, "party"));
    }

    @Override
    public Optional<BnplOffer> ownedBy(Connection unitOfWork, BnplOfferId id, UUID party) {
        return one(unitOfWork, " WHERE o.id = ? AND o.party_id = ?", "reading a BNPL offer",
                Objects.requireNonNull(id, "id").value(), Objects.requireNonNull(party, "party"));
    }

    @Override
    public Optional<BnplOffer> ofApplication(Connection unitOfWork, BnplApplicationId application) {
        return one(unitOfWork, " WHERE o.application_id = ?", "reading an application's BNPL offer",
                Objects.requireNonNull(application, "application").value());
    }

    @Override
    public Optional<StoredTerms> terms(Connection unitOfWork, BnplOfferId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT product, plan, currency, scale, amount_minor, nominal_annual_rate, schedule_engine,"
                        + " illustrative_start, instalment_count, first_due_date, last_due_date, first_instalment_minor,"
                        + " regular_instalment_minor, final_instalment_minor, total_interest_minor, total_payable_minor,"
                        + " canonical_format, canonical_terms, terms_sha256, document_sha256"
                        + " FROM bnpl.bnpl_offer_terms WHERE offer_id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
                int scale = row.getInt("scale");
                return Optional.of(new StoredTerms(
                        BnplProduct.valueOf(row.getString("product")),
                        BnplPlan.valueOf(row.getString("plan")),
                        money(row, "amount_minor", currency, scale),
                        new BigDecimal(row.getString("nominal_annual_rate")),
                        row.getString("schedule_engine"),
                        row.getDate("illustrative_start").toLocalDate(),
                        row.getInt("instalment_count"),
                        row.getDate("first_due_date").toLocalDate(),
                        row.getDate("last_due_date").toLocalDate(),
                        money(row, "first_instalment_minor", currency, scale),
                        money(row, "regular_instalment_minor", currency, scale),
                        money(row, "final_instalment_minor", currency, scale),
                        money(row, "total_interest_minor", currency, scale),
                        money(row, "total_payable_minor", currency, scale),
                        row.getInt("canonical_format"),
                        row.getString("canonical_terms"),
                        row.getBytes("terms_sha256"),
                        row.getBytes("document_sha256")));
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL offer's terms", failure);
        }
    }

    @Override
    public boolean expire(Connection unitOfWork, BnplOfferId id) {
        return move(unitOfWork, id, "EXPIRED", "expires_at <= clock_timestamp()", "expiring a BNPL offer");
    }

    @Override
    public boolean decline(Connection unitOfWork, BnplOfferId id) {
        return move(unitOfWork, id, "DECLINED", "expires_at > clock_timestamp()", "declining a BNPL offer");
    }

    @Override
    public void permitAtExpiry(Connection unitOfWork, BnplOfferId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_offer SET next_step_at = expires_at WHERE id = ? AND status = 'OFFERED'"
                        + " AND next_step_at IS DISTINCT FROM expires_at")) {
            update.setObject(1, id.value());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw failed("re-stamping a BNPL offer's permit", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A conditional move; the side of the clock is this class's own constant, judged after the row lock. */
    private static boolean move(Connection unitOfWork, BnplOfferId id, String to, String clockSide, String operation) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_offer SET status = ? WHERE id = ? AND status = 'OFFERED' AND " + clockSide)) {
            update.setString(1, to);
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static Optional<BnplOffer> one(Connection unitOfWork, String where, String operation, Object... arguments) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + FROM + where)) {
            for (int index = 0; index < arguments.length; index++) {
                select.setObject(index + 1, arguments[index]);
            }
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(offer(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static BnplOffer offer(ResultSet row) throws SQLException {
        Timestamp closed = row.getTimestamp("closed_at");
        return new BnplOffer(
                BnplOfferId.of(row.getObject("id", UUID.class)),
                BnplApplicationId.of(row.getObject("application_id", UUID.class)),
                row.getObject("party_id", UUID.class),
                BnplProduct.valueOf(row.getString("product")),
                BnplPlan.valueOf(row.getString("plan")),
                BnplOfferStatus.valueOf(row.getString("status")),
                row.getObject("decision_id", UUID.class),
                BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                row.getInt("version"),
                row.getObject("checkout_session_id", UUID.class),
                row.getTimestamp("offered_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                Optional.ofNullable(closed).map(Timestamp::toInstant),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency, scale);
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
