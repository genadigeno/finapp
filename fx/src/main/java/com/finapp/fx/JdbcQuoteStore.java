package com.finapp.fx;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
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
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link QuoteStore} over {@code fx V005} (`P9-TSK-008`). Every clock judgement is the
 * database's: the effective status, the live count and the expiry conditional all read
 * {@code statement_timestamp()} - this class takes no clock.
 */
@RequiredArgsConstructor
public final class JdbcQuoteStore implements QuoteStore {

    /** The cap trigger's SQLSTATE ({@code fx V005}'s {@code quote_is_born_issued}). */
    static final String CAP_SQLSTATE = "FXCAP";

    private static final String REQUEST_COLUMNS =
            "id, reference, owner_party_id, purpose, source_currency, destination_currency, fixed_side,"
                    + " fixed_amount_minor, fixed_scale, pricing_policy_version_id, requested_at";

    private static final String QUOTE_COLUMNS =
            "id, owner_party_id, purpose, fixed_side, source_currency, destination_currency,"
                    + " CASE WHEN status = 'ISSUED' AND expires_at <= statement_timestamp() THEN 'EXPIRED'"
                    + " ELSE status END AS effective_status, customer_source_minor, source_scale,"
                    + " customer_destination_minor, destination_scale, customer_rate, rate_scale,"
                    + " disclosed_margin, pricing_policy_version_id, issued_at, expires_at, correlation_id,"
                    + " issued_event_id";

    @NonNull private final IdGenerator ids;

    @Override
    public Optional<RequestRow> requestByClaim(Connection unitOfWork, String claimKey) {
        Objects.requireNonNull(claimKey, "claimKey must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement("SELECT " + REQUEST_COLUMNS + " FROM fx.quote_request WHERE claim_key = ?")) {
            select.setString(1, claimKey);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(request(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure("reading a quote request", failure);
        }
    }

    @Override
    public RequestRow insertRequestIfAbsent(Connection unitOfWork, RequestDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.quote_request (id, reference, claim_key, owner_party_id, purpose,"
                                + " source_currency, destination_currency, fixed_side, fixed_amount_minor,"
                                + " fixed_scale, pricing_policy_version_id, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (claim_key) DO NOTHING")) {
            insert.setObject(1, draft.id());
            insert.setString(2, draft.reference());
            insert.setString(3, draft.claimKey());
            insert.setObject(4, draft.owner());
            insert.setString(5, draft.purpose().name());
            insert.setString(6, draft.source().code());
            insert.setString(7, draft.destination().code());
            insert.setString(8, draft.fixedSide().name());
            insert.setLong(9, draft.fixedAmount().minorUnits());
            insert.setInt(10, draft.fixedAmount().scale());
            insert.setObject(11, draft.version().value());
            insert.setString(12, draft.correlationId());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failure("claiming a quote request", failure);
        }
        return requestByClaim(unitOfWork, draft.claimKey())
                .orElseThrow(() -> new IllegalStateException("a claimed quote request must read back"));
    }

    @Override
    public int liveCount(Connection unitOfWork, UUID owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM fx.quote WHERE owner_party_id = ? AND status = 'ISSUED'"
                                + " AND expires_at > statement_timestamp()")) {
            select.setObject(1, owner);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failure("counting live quotes", failure);
        }
    }

    @Override
    public int nextAttempt(Connection unitOfWork, UUID requestId) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT COALESCE(MAX(attempt), 0) + 1 FROM fx.quote_sourcing_step WHERE quote_request_id = ?")) {
            select.setObject(1, requestId);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        } catch (SQLException failure) {
            throw failure("numbering a sourcing attempt", failure);
        }
    }

    @Override
    public void insertSteps(Connection unitOfWork, UUID requestId, int attempt, List<Step> steps) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.quote_sourcing_step (quote_request_id, attempt, position, provider_code,"
                                + " declaration_version, outcome, detail) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (Step step : steps) {
                insert.setObject(1, requestId);
                insert.setInt(2, attempt);
                insert.setInt(3, step.position());
                insert.setString(4, step.providerCode());
                insert.setInt(5, step.declarationVersion());
                insert.setString(6, step.outcome().name());
                insert.setString(7, step.detail().orElse(null));
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failure("recording sourcing steps", failure);
        }
    }

    @Override
    public List<Step> steps(Connection unitOfWork, UUID requestId, int attempt) {
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT position, provider_code, declaration_version, outcome, detail"
                                + " FROM fx.quote_sourcing_step WHERE quote_request_id = ? AND attempt = ?"
                                + " ORDER BY position, outcome = 'CHOSEN'")) {
            select.setObject(1, requestId);
            select.setInt(2, attempt);
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    steps.add(new Step(
                            row.getInt("position"),
                            row.getString("provider_code"),
                            row.getInt("declaration_version"),
                            StepOutcome.valueOf(row.getString("outcome")),
                            Optional.ofNullable(row.getString("detail"))));
                }
            }
            return steps;
        } catch (SQLException failure) {
            throw failure("reading sourcing steps", failure);
        }
    }

    @Override
    public Optional<FxQuoteId> quoteOfRequest(Connection unitOfWork, UUID requestId) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement("SELECT id FROM fx.quote WHERE quote_request_id = ?")) {
            select.setObject(1, requestId);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(FxQuoteId.of(row.getObject(1, UUID.class))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure("reading a request's quote", failure);
        }
    }

    @Override
    public Insertion insertQuote(Connection unitOfWork, QuoteDraft draft) {
        Objects.requireNonNull(draft, "draft must not be null");
        ConversionPlan.Plan plan = draft.plan();
        PricingPair pricing = draft.terms().pricing();
        Savepoint before;
        try {
            before = unitOfWork.setSavepoint();
        } catch (SQLException failure) {
            throw failure("marking a quote insert", failure);
        }
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.quote (id, quote_request_id, owner_party_id, purpose, source_currency,"
                                + " destination_currency, fixed_side, pricing_policy_version_id, provider_code,"
                                + " provider_quote_reference, provider_rate, provider_valid_for_ms,"
                                + " provider_value_date, obtained_at, requested_at, reference_snapshot_id,"
                                + " reference_rate, customer_rate, internal_rate, disclosed_margin, spread, markup,"
                                + " rate_scale, rate_rounding, amount_rounding, margin_rounding, window_seconds,"
                                + " cover_margin_seconds, source_scale, destination_scale, customer_source_minor,"
                                + " customer_destination_minor, position_source_minor, position_destination_minor,"
                                + " margin_minor, spread_margin_minor, markup_margin_minor, residual_minor, status,"
                                + " issued_event_id, correlation_id)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                + " (SELECT requested_at FROM fx.quote_request WHERE id = ?),"
                                + " ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ISSUED', ?, ?)"
                                + " RETURNING issued_at, expires_at")) {
            RequestRow request = draft.request();
            int i = 1;
            insert.setObject(i++, draft.id().value());
            insert.setObject(i++, request.id());
            insert.setObject(i++, request.owner());
            insert.setString(i++, request.purpose().name());
            insert.setString(i++, request.source().code());
            insert.setString(i++, request.destination().code());
            insert.setString(i++, request.fixedSide().name());
            insert.setObject(i++, request.version().value());
            insert.setString(i++, draft.providerCode());
            insert.setString(i++, draft.providerQuoteReference());
            insert.setBigDecimal(i++, draft.providerQuote().rate().value());
            insert.setLong(i++, draft.providerQuote().validFor().toMillis());
            insert.setDate(i++, Date.valueOf(draft.valueDate()));
            insert.setTimestamp(i++, Timestamp.from(draft.obtainedAt()));
            insert.setObject(i++, request.id());
            insert.setObject(i++, draft.reference().id());
            insert.setBigDecimal(i++, draft.reference().rate().value());
            insert.setBigDecimal(i++, plan.customerRate().value());
            insert.setBigDecimal(i++, plan.internalRate().value());
            insert.setBigDecimal(i++, draft.disclosedMargin());
            insert.setBigDecimal(i++, pricing.spread().value());
            insert.setBigDecimal(i++, pricing.markup().value());
            insert.setInt(i++, pricing.rateScale());
            insert.setString(i++, pricing.rateRounding().name());
            insert.setString(i++, pricing.amountRounding().name());
            insert.setString(i++, pricing.marginRounding().name());
            insert.setLong(i++, draft.terms().window().toSeconds());
            insert.setLong(i++, draft.terms().coverMargin().toSeconds());
            insert.setInt(i++, plan.customerPays().scale());
            insert.setInt(i++, plan.customerReceives().scale());
            insert.setLong(i++, plan.customerPays().minorUnits());
            insert.setLong(i++, plan.customerReceives().minorUnits());
            insert.setLong(i++, plan.positionSource().minorUnits());
            insert.setLong(i++, plan.positionDestination().minorUnits());
            insert.setLong(i++, plan.margin().minorUnits());
            insert.setLong(i++, plan.spreadMargin().minorUnits());
            insert.setLong(i++, plan.markupMargin().minorUnits());
            insert.setLong(i++, plan.residual().minorUnits());
            insert.setObject(i++, draft.issuedEventId());
            insert.setString(i, draft.correlationId());
            Inserted inserted;
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                inserted = new Inserted(row.getTimestamp(1).toInstant(), row.getTimestamp(2).toInstant());
            }
            unitOfWork.releaseSavepoint(before);
            return inserted;
        } catch (SQLException failure) {
            rollbackTo(unitOfWork, before, failure);
            String message = String.valueOf(failure.getMessage());
            if (CAP_SQLSTATE.equals(failure.getSQLState())) {
                return new CapReached();
            }
            if ("23514".equals(failure.getSQLState()) && message.contains("quote_window_at_least_five_seconds")) {
                return new WindowTooShort();
            }
            if ("23505".equals(failure.getSQLState()) && message.contains("quote_request_unique")) {
                return new AlreadyIssued(quoteOfRequest(unitOfWork, draft.request().id())
                        .orElseThrow(() -> new IllegalStateException("the racing quote must read back")));
            }
            throw failure("issuing a quote", failure);
        }
    }

    @Override
    public void appendEvent(
            Connection unitOfWork,
            FxQuoteId id,
            Optional<QuoteStatus> from,
            QuoteStatus to,
            String actorId,
            String actorType,
            Optional<String> detectedBy,
            String correlationId) {
        insertEvent(unitOfWork, id, from, to, actorId, actorType, detectedBy, correlationId, Optional.empty());
    }

    @Override
    public void appendEventAt(
            Connection unitOfWork,
            FxQuoteId id,
            Optional<QuoteStatus> from,
            QuoteStatus to,
            String actorId,
            String actorType,
            Optional<String> detectedBy,
            String correlationId,
            Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        insertEvent(unitOfWork, id, from, to, actorId, actorType, detectedBy, correlationId, Optional.of(occurredAt));
    }

    /** One history row: {@code occurred_at} the given judged instant, else this statement's clock. */
    private void insertEvent(
            Connection unitOfWork,
            FxQuoteId id,
            Optional<QuoteStatus> from,
            QuoteStatus to,
            String actorId,
            String actorType,
            Optional<String> detectedBy,
            String correlationId,
            Optional<Instant> occurredAt) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO fx.quote_event (id, quote_id, from_status, to_status, actor_id, actor_type,"
                                + " detected_by, correlation_id, occurred_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, coalesce(?, statement_timestamp()))")) {
            insert.setObject(1, ids.next());
            insert.setObject(2, id.value());
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, actorId);
            insert.setString(6, actorType);
            insert.setString(7, detectedBy.orElse(null));
            insert.setString(8, correlationId);
            insert.setTimestamp(9, occurredAt.map(Timestamp::from).orElse(null));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failure("recording a quote's history", failure);
        }
    }

    @Override
    public Optional<QuoteRow> findOwned(Connection unitOfWork, FxQuoteId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + QUOTE_COLUMNS + " FROM fx.quote WHERE id = ? AND owner_party_id = ?")) {
            select.setObject(1, id.value());
            select.setObject(2, owner);
            return one(select);
        } catch (SQLException failure) {
            throw failure("reading a quote", failure);
        }
    }

    @Override
    public Optional<QuoteRow> lockOwned(Connection unitOfWork, FxQuoteId id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + QUOTE_COLUMNS + " FROM fx.quote WHERE id = ? AND owner_party_id = ? FOR UPDATE")) {
            select.setObject(1, id.value());
            select.setObject(2, owner);
            return one(select);
        } catch (SQLException failure) {
            throw failure("locking a quote", failure);
        }
    }

    @Override
    public Optional<Instant> cancel(Connection unitOfWork, FxQuoteId id) {
        return judged(unitOfWork, "UPDATE fx.quote SET status = 'CANCELLED' WHERE id = ? AND status = 'ISSUED'"
                + " AND expires_at > statement_timestamp() RETURNING statement_timestamp()", id, "cancelling a quote");
    }

    @Override
    public Optional<Instant> accept(Connection unitOfWork, FxQuoteId id) {
        return judged(unitOfWork, "UPDATE fx.quote SET status = 'ACCEPTED' WHERE id = ? AND status = 'ISSUED'"
                + " AND expires_at > statement_timestamp() RETURNING statement_timestamp()", id, "accepting a quote");
    }

    @Override
    public boolean expire(Connection unitOfWork, FxQuoteId id) {
        return transition(unitOfWork, "UPDATE fx.quote SET status = 'EXPIRED' WHERE id = ? AND status = 'ISSUED'"
                + " AND expires_at <= statement_timestamp()", id, "expiring a quote on acceptance");
    }

    @Override
    public boolean execute(Connection unitOfWork, FxQuoteId id) {
        return transition(unitOfWork, "UPDATE fx.quote SET status = 'EXECUTED' WHERE id = ? AND status = 'ACCEPTED'",
                id, "executing a quote");
    }

    @Override
    public Optional<PlanRow> plan(Connection unitOfWork, FxQuoteId id) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, owner_party_id, purpose, source_currency, destination_currency, fixed_side,"
                        + " pricing_policy_version_id, provider_code, provider_quote_reference, customer_rate,"
                        + " source_scale, destination_scale, customer_source_minor, customer_destination_minor,"
                        + " position_source_minor, position_destination_minor, margin_minor, spread_margin_minor,"
                        + " markup_margin_minor, residual_minor, correlation_id, issued_event_id"
                        + " FROM fx.quote WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlanRow(
                        FxQuoteId.of(row.getObject("id", UUID.class)),
                        row.getObject("owner_party_id", UUID.class),
                        PricingPurpose.valueOf(row.getString("purpose")),
                        CurrencyCode.of(row.getString("source_currency")),
                        CurrencyCode.of(row.getString("destination_currency")),
                        FixedSide.valueOf(row.getString("fixed_side")),
                        PricingPolicyId.of(row.getObject("pricing_policy_version_id", UUID.class)),
                        row.getString("provider_code"),
                        row.getString("provider_quote_reference"),
                        row.getBigDecimal("customer_rate"),
                        row.getInt("source_scale"),
                        row.getInt("destination_scale"),
                        row.getLong("customer_source_minor"),
                        row.getLong("customer_destination_minor"),
                        row.getLong("position_source_minor"),
                        row.getLong("position_destination_minor"),
                        row.getLong("margin_minor"),
                        row.getLong("spread_margin_minor"),
                        row.getLong("markup_margin_minor"),
                        row.getLong("residual_minor"),
                        row.getString("correlation_id"),
                        row.getObject("issued_event_id", UUID.class)));
            }
        } catch (SQLException failure) {
            throw failure("reading a quote's plan", failure);
        }
    }

    /** A conditional transition that returns the instant its own statement judged, if it matched. */
    private static Optional<Instant> judged(Connection unitOfWork, String sql, FxQuoteId id, String operation) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setObject(1, id.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failure(operation, failure);
        }
    }

    private static boolean transition(Connection unitOfWork, String sql, FxQuoteId id, String operation) {
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failure(operation, failure);
        }
    }

    @Override
    public List<ExpiredRow> expirePage(Connection unitOfWork, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("an expiry page is 1..1000 rows");
        }
        List<ExpiredRow> expired = new ArrayList<>();
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE fx.quote SET status = 'EXPIRED' WHERE id IN (SELECT id FROM fx.quote"
                                + " WHERE status = 'ISSUED' AND expires_at <= statement_timestamp()"
                                + " ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED)"
                                + " AND status = 'ISSUED' AND expires_at <= statement_timestamp()"
                                + " RETURNING id, source_currency, destination_currency, correlation_id, issued_event_id")) {
            update.setInt(1, limit);
            try (ResultSet row = update.executeQuery()) {
                while (row.next()) {
                    expired.add(new ExpiredRow(
                            FxQuoteId.of(row.getObject("id", UUID.class)),
                            CurrencyCode.of(row.getString("source_currency")),
                            CurrencyCode.of(row.getString("destination_currency")),
                            row.getString("correlation_id"),
                            row.getObject("issued_event_id", UUID.class)));
                }
            }
            return expired;
        } catch (SQLException failure) {
            throw failure("expiring quotes", failure);
        }
    }

    @Override
    public Map<String, Integer> liveByPair(Connection unitOfWork) {
        Map<String, Integer> live = new LinkedHashMap<>();
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT source_currency || '-' || destination_currency, count(*) FROM fx.quote"
                                + " WHERE status = 'ISSUED' AND expires_at > statement_timestamp()"
                                + " GROUP BY 1 ORDER BY 1");
                ResultSet row = select.executeQuery()) {
            while (row.next()) {
                live.put(row.getString(1), row.getInt(2));
            }
            return live;
        } catch (SQLException failure) {
            throw failure("counting live quotes per pair", failure);
        }
    }

    // -----------------------------------------------------------------

    private static Optional<QuoteRow> one(PreparedStatement bound) throws SQLException {
        try (ResultSet row = bound.executeQuery()) {
            return row.next() ? Optional.of(quote(row)) : Optional.empty();
        }
    }

    private static RequestRow request(ResultSet row) throws SQLException {
        CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
        CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
        FixedSide fixedSide = FixedSide.valueOf(row.getString("fixed_side"));
        CurrencyCode fixed = fixedSide == FixedSide.FIXED_SOURCE ? source : destination;
        return new RequestRow(
                row.getObject("id", UUID.class),
                row.getString("reference"),
                row.getObject("owner_party_id", UUID.class),
                PricingPurpose.valueOf(row.getString("purpose")),
                source,
                destination,
                fixedSide,
                Money.ofPersisted(row.getLong("fixed_amount_minor"), fixed, row.getInt("fixed_scale")),
                PricingPolicyId.of(row.getObject("pricing_policy_version_id", UUID.class)),
                row.getTimestamp("requested_at").toInstant());
    }

    private static QuoteRow quote(ResultSet row) throws SQLException {
        CurrencyCode source = CurrencyCode.of(row.getString("source_currency"));
        CurrencyCode destination = CurrencyCode.of(row.getString("destination_currency"));
        int rateScale = row.getInt("rate_scale");
        BigDecimal customerRate = row.getBigDecimal("customer_rate").setScale(rateScale, java.math.RoundingMode.UNNECESSARY);
        return new QuoteRow(
                FxQuoteId.of(row.getObject("id", UUID.class)),
                row.getObject("owner_party_id", UUID.class),
                PricingPurpose.valueOf(row.getString("purpose")),
                FixedSide.valueOf(row.getString("fixed_side")),
                QuoteStatus.valueOf(row.getString("effective_status")),
                Money.ofPersisted(row.getLong("customer_source_minor"), source, row.getInt("source_scale")),
                Money.ofPersisted(row.getLong("customer_destination_minor"), destination, row.getInt("destination_scale")),
                ExchangeRate.of(source, destination, customerRate),
                rateScale,
                row.getBigDecimal("disclosed_margin"),
                PricingPolicyId.of(row.getObject("pricing_policy_version_id", UUID.class)),
                row.getTimestamp("issued_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                row.getString("correlation_id"),
                row.getObject("issued_event_id", UUID.class));
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint savepoint, SQLException cause) {
        try {
            unitOfWork.rollback(savepoint);
        } catch (SQLException rollback) {
            cause.addSuppressed(rollback);
            throw failure("rolling back a refused quote insert", cause);
        }
    }

    private static FxStorageException failure(String operation, SQLException failure) {
        return new FxStorageException(DatabaseFailure.describe(operation, failure), failure);
    }
}
