package com.finapp.app.fx;

import com.finapp.crossborder.CrossborderAuditAction;
import com.finapp.crossborder.CrossborderErrorCode;
import com.finapp.fx.FxAuditAction;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 9's reports, not metrics (`P9-TSK-027`, PHASE_9_PLAN.md section 15; ADR-0072): where the amounts live. Each is
 * read by an investigator holding {@code FX_INVESTIGATE}, in ONE {@code REPEATABLE READ} snapshot, bounded at 100 rows
 * with {@code truncated}, and audited in the read's own transaction - the FX position by currency with the open cover
 * legs, the FX revenue of a month, the corridor report of a month - and the cross-border payment's durable identifier
 * chain, its trace. A malformed or unreportable month is {@code 422} before any read. Amounts are decimal strings with
 * their currency; nothing here ever reaches a metric, a span or a log.
 */
public final class Phase9Reports {

    /** Every report's bound: the first hundred rows tell the story, {@code truncated} says when there were more. */
    static final int BOUND = 100;

    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-[0-9]{2}$");
    private static final YearMonth EARLIEST = YearMonth.of(2026, 1);

    private final TransactionRunner transactions;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;
    private final List<String> corridorSources;

    public Phase9Reports(
            TransactionRunner transactions, AuditWriter<Connection> audit, IdGenerator ids, Clock clock,
            List<String> corridorSources) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.corridorSources = List.copyOf(corridorSources);
    }

    /** An amount: the decimal string at its currency's scale, and the currency. */
    public record ReportAmount(String amount, String currency) {
        static ReportAmount of(long minor, String currency) {
            String code = currency.strip();
            return new ReportAmount(BigDecimal.valueOf(minor, CurrencyCode.of(code).minorUnits()).toPlainString(), code);
        }
    }

    // ------------------------------------------------------------------ the FX position

    public record CurrencyPositionRow(String currency, ReportAmount position) {}

    public record OpenLegRow(
            String coverId, String kind, String provider, String status, String sourceCurrency,
            String destinationCurrency, String fixedSide, ReportAmount fixedAmount, long ageSeconds) {}

    public record FxPositionReport(String asOf, List<CurrencyPositionRow> positions, List<OpenLegRow> openLegs, boolean truncated) {}

    /** {@code FX_POSITION} by currency, and every cover leg not yet executed or voided - the oldest first. */
    public FxPositionReport fxPosition() {
        return read(FxAuditAction.FX_POSITION_REPORT_READ, "fx_position", "fx-position", "asOf=now", uow -> {
            List<CurrencyPositionRow> positions = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT a.currency, COALESCE(SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor"
                            + " END), 0) FROM ledger.ledger_account a LEFT JOIN ledger.journal_line l"
                            + " ON l.ledger_account_id = a.id WHERE a.purpose = 'FX_POSITION' GROUP BY a.currency"
                            + " ORDER BY a.currency");
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    positions.add(new CurrencyPositionRow(rows.getString(1).strip(), ReportAmount.of(rows.getLong(2), rows.getString(1))));
                }
            }
            List<OpenLegRow> legs = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT id, kind, provider_code, status, source_currency, destination_currency, fixed_side,"
                            + " fixed_amount_minor, EXTRACT(EPOCH FROM (statement_timestamp() - created_at))::bigint"
                            + " FROM fx.cover WHERE status IN ('DISPATCHED', 'UNKNOWN', 'REJECTED')"
                            + " ORDER BY created_at, id LIMIT ?")) {
                select.setInt(1, BOUND + 1);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        if (legs.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        String fixedCurrency = rows.getString(7).equals("FIXED_SOURCE") ? rows.getString(5) : rows.getString(6);
                        legs.add(new OpenLegRow(rows.getObject(1, UUID.class).toString(), rows.getString(2), rows.getString(3),
                                rows.getString(4), rows.getString(5).strip(), rows.getString(6).strip(), rows.getString(7),
                                ReportAmount.of(rows.getLong(8), fixedCurrency), rows.getLong(9)));
                    }
                }
            }
            return new FxPositionReport(Instant.now(clock).toString(), positions, legs, truncated);
        });
    }

    // ------------------------------------------------------------------ the FX revenue of a month

    public record PairRow(
            String pair, String purpose, long trades, long reversed, ReportAmount margin, ReportAmount spreadMargin,
            ReportAmount markupMargin, ReportAmount residual) {}

    public record RealisedRow(String currency, ReportAmount gains, ReportAmount losses) {}

    public record FxRevenueReport(String month, List<PairRow> pairs, List<RealisedRow> realised, long offPlanCovers,
            boolean truncated) {}

    /**
     * A month's FX revenue: per pair and purpose, the trades booked and the margin expected - its spread and markup
     * split - and the residual accumulated, in the computed leg's currency; the covers' realised results per currency;
     * the off-plan executions.
     */
    public FxRevenueReport fxRevenue(String month) {
        YearMonth period = period(month);
        return read(FxAuditAction.FX_REVENUE_REPORT_READ, "fx_revenue", period.toString(), "month=" + period, uow -> {
            Timestamp from = start(period);
            Timestamp to = start(period.plusMonths(1));
            List<PairRow> pairs = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT source_currency || '-' || destination_currency, purpose,"
                            + " CASE fixed_side WHEN 'FIXED_SOURCE' THEN destination_currency ELSE source_currency END,"
                            + " count(*), count(*) FILTER (WHERE status = 'REVERSED'),"
                            + " SUM(margin_minor), SUM(spread_margin_minor), SUM(markup_margin_minor), SUM(residual_minor)"
                            + " FROM fx.trade WHERE booked_at >= ? AND booked_at < ? GROUP BY 1, 2, 3 ORDER BY 1, 2, 3 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        if (pairs.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        String leg = rows.getString(3);
                        pairs.add(new PairRow(rows.getString(1), rows.getString(2), rows.getLong(4), rows.getLong(5),
                                ReportAmount.of(rows.getLong(6), leg), ReportAmount.of(rows.getLong(7), leg),
                                ReportAmount.of(rows.getLong(8), leg), ReportAmount.of(rows.getLong(9), leg)));
                    }
                }
            }
            List<RealisedRow> realised = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT currency, SUM(GREATEST(amount, 0)), SUM(GREATEST(-amount, 0)) FROM ("
                            + " SELECT sold_currency AS currency, realised_sold_minor AS amount, recorded_at FROM fx.cover_execution"
                            + " UNION ALL SELECT bought_currency, realised_bought_minor, recorded_at FROM fx.cover_execution) r"
                            + " WHERE recorded_at >= ? AND recorded_at < ? GROUP BY currency ORDER BY currency")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        realised.add(new RealisedRow(rows.getString(1).strip(), ReportAmount.of(rows.getLong(2), rows.getString(1)),
                                ReportAmount.of(rows.getLong(3), rows.getString(1))));
                    }
                }
            }
            long offPlan;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT count(*) FROM fx.cover_execution WHERE executed_off_plan AND recorded_at >= ? AND recorded_at < ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                try (ResultSet rows = select.executeQuery()) {
                    rows.next();
                    offPlan = rows.getLong(1);
                }
            }
            return new FxRevenueReport(period.toString(), pairs, realised, offPlan, truncated);
        });
    }

    // ------------------------------------------------------------------ the corridor report of a month

    public record CorridorRow(
            String corridor, long payments, long inTransit, long delivered, long failed, long returned,
            ReportAmount volume, ReportAmount feesCharged, long returns) {}

    public record CorridorCostRow(String source, ReportAmount costs) {}

    public record CorridorReport(String month, List<CorridorRow> corridors, List<CorridorCostRow> providerCosts,
            boolean truncated) {}

    /**
     * A month's corridors: per corridor, the payments authorized and where they stand, the volume delivered for and the
     * fees charged, the returns; and each corridor provider's costs - the fees its own report charged - per currency.
     */
    public CorridorReport corridors(String month) {
        YearMonth period = period(month);
        return read(CrossborderAuditAction.CORRIDOR_REPORT_READ, "crossborder_corridor", period.toString(),
                "month=" + period, uow -> {
            Timestamp from = start(period);
            Timestamp to = start(period.plusMonths(1));
            List<CorridorRow> corridors = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT p.corridor, o.destination_currency, o.source_currency, count(*),"
                            + " count(*) FILTER (WHERE p.status = 'IN_TRANSIT'), count(*) FILTER (WHERE p.status = 'DELIVERED'),"
                            + " count(*) FILTER (WHERE p.status = 'FAILED'), count(*) FILTER (WHERE p.status = 'RETURNED'),"
                            + " SUM(o.destination_minor), SUM(o.fee_minor),"
                            + " count(r.id) FROM crossborder.payment p JOIN crossborder.payment_offer o ON o.id = p.offer_id"
                            + " LEFT JOIN payments.outbound_credit_return r ON r.outbound_credit_id = p.outbound_credit_id"
                            + " WHERE p.created_at >= ? AND p.created_at < ? GROUP BY 1, 2, 3 ORDER BY 1, 2, 3 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        if (corridors.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        corridors.add(new CorridorRow(rows.getString(1), rows.getLong(4), rows.getLong(5), rows.getLong(6),
                                rows.getLong(7), rows.getLong(8), ReportAmount.of(rows.getLong(9), rows.getString(2)),
                                ReportAmount.of(rows.getLong(10), rows.getString(3)), rows.getLong(11)));
                    }
                }
            }
            List<CorridorCostRow> costs = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT s.code, l.currency, SUM(CASE l.direction WHEN 'DEBIT' THEN l.amount_minor ELSE -l.amount_minor END)"
                            + " FROM settlement.batch b JOIN settlement.source s ON s.id = b.source_id"
                            + " JOIN ledger.journal_line l ON l.entry_id = b.journal_entry_id"
                            + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id AND a.purpose = 'PROCESSING_COSTS'"
                            + " WHERE s.code = ANY(?) AND b.created_at >= ? AND b.created_at < ? GROUP BY 1, 2 ORDER BY 1, 2")) {
                select.setArray(1, uow.createArrayOf("text", corridorSources.toArray()));
                select.setTimestamp(2, from);
                select.setTimestamp(3, to);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        costs.add(new CorridorCostRow(rows.getString(1), ReportAmount.of(rows.getLong(3), rows.getString(2))));
                    }
                }
            }
            return new CorridorReport(period.toString(), corridors, costs, truncated);
        });
    }

    // ------------------------------------------------------------------ the trace

    public record ItemLink(String itemId, String batchId, String lineType, String status) {}

    public record ExpectationLink(String expectationId, String kind, String status) {}

    public record CoverLink(
            String coverId, String kind, String status, List<String> attemptReferences, String providerTradeReference,
            String entryId, List<ExpectationLink> legs) {}

    public record PaymentTrace(
            String paymentId, String customerParty, String corridor, String status, String quoteId, String offerId,
            String outboundCreditId, String endToEndReference, String providerReference, String creditStatus,
            String completionEntryId, List<ExpectationLink> payout, List<ItemLink> settlementItems, String tradeId,
            String tradeEntryId, List<CoverLink> covers) {}

    /**
     * A cross-border payment's durable identifier chain: customer, quote, offer, payment, outbound credit ({@code E},
     * the provider's reference), completion entry, {@code CROSSBORDER_PAYOUT} expectation, the settlement items naming
     * {@code E} and their batches; the trade, its covers, their attempts, entries and leg expectations. 404 when no
     * payment matches.
     */
    public PaymentTrace trace(String rawPaymentId) {
        UUID paymentId;
        try {
            paymentId = UUID.fromString(rawPaymentId);
        } catch (IllegalArgumentException malformed) {
            throw new ApiException(CrossborderErrorCode.PAYMENT_NOT_FOUND, "No payment matches the requested identifier",
                    "no such payment.");
        }
        return read(CrossborderAuditAction.PAYMENT_TRACE_READ, "crossborder_payment", paymentId.toString(), "trace", uow -> {
            String[] payment = one(uow, "SELECT owner_party::text, corridor, status, quote_id::text, offer_id::text,"
                    + " outbound_credit_id::text FROM crossborder.payment WHERE id = ?", paymentId, 6)
                    .orElseThrow(() -> new ApiException(CrossborderErrorCode.PAYMENT_NOT_FOUND,
                            "No payment matches the requested identifier", "no such payment."));
            UUID credit = UUID.fromString(payment[5]);
            String[] outbound = one(uow, "SELECT end_to_end_reference, provider_reference, status FROM payments.outbound_credit"
                    + " WHERE id = ?", credit, 3).orElse(new String[3]);
            String completion = one(uow, "SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                    com.finapp.ledger.PostingService.IDEMPOTENCY_SCOPE + ":"
                            + com.finapp.payments.OutboundCreditOutcomes.POSTING_KEY_PREFIX + credit, 1).map(row -> row[0]).orElse(null);
            List<ExpectationLink> payout = expectations(uow, credit.toString(), "('CROSSBORDER_PAYOUT', 'CROSSBORDER_RETURN')");
            List<ItemLink> items = new ArrayList<>();
            if (outbound[0] != null) {
                try (PreparedStatement select = uow.prepareStatement(
                        "SELECT i.id::text, r.batch_id::text, i.line_type, i.status FROM reconciliation.external_item i"
                                + " JOIN reconciliation.reconciliation_batch r ON r.id = i.run_id"
                                + " JOIN reconciliation.external_item_key k ON k.item_id = i.id AND k.key_kind = 'END_TO_END_REF'"
                                + " WHERE k.key_value = ? ORDER BY r.source_sequence, i.line_no LIMIT ?")) {
                    select.setString(1, outbound[0]);
                    select.setInt(2, BOUND);
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            items.add(new ItemLink(rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4)));
                        }
                    }
                }
            }
            String[] trade = one(uow, "SELECT id::text, journal_entry_id::text FROM fx.trade WHERE quote_id = ?",
                    UUID.fromString(payment[3]), 2).orElse(new String[2]);
            List<CoverLink> covers = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT c.id, c.kind, c.status, e.provider_trade_ref FROM fx.cover c LEFT JOIN fx.cover_execution e"
                            + " ON e.cover_id = c.id WHERE c.quote_id = ? ORDER BY c.kind")) {
                select.setObject(1, UUID.fromString(payment[3]));
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        UUID cover = rows.getObject(1, UUID.class);
                        covers.add(new CoverLink(cover.toString(), rows.getString(2), rows.getString(3), List.of(),
                                rows.getString(4), null, List.of()));
                    }
                }
            }
            List<CoverLink> complete = new ArrayList<>();
            for (CoverLink cover : covers) {
                List<String> attempts = new ArrayList<>();
                try (PreparedStatement select = uow.prepareStatement(
                        "SELECT client_reference FROM fx.cover_attempt WHERE cover_id = ? ORDER BY attempt")) {
                    select.setObject(1, UUID.fromString(cover.coverId()));
                    try (ResultSet rows = select.executeQuery()) {
                        while (rows.next()) {
                            attempts.add(rows.getString(1));
                        }
                    }
                }
                String entry = one(uow, "SELECT id::text FROM ledger.journal_entry WHERE idempotency_scope = ?",
                        com.finapp.ledger.PostingService.IDEMPOTENCY_SCOPE + ":"
                                + com.finapp.fx.FxCoverOutcomes.POSTING_PREFIX + cover.coverId(), 1).map(row -> row[0]).orElse(null);
                complete.add(new CoverLink(cover.coverId(), cover.kind(), cover.status(), List.copyOf(attempts),
                        cover.providerTradeReference(), entry,
                        expectations(uow, cover.coverId(), "('FX_SELL_LEG', 'FX_BUY_LEG')")));
            }
            return new PaymentTrace(paymentId.toString(), payment[0], payment[1], payment[2], payment[3], payment[4],
                    payment[5], outbound[0], outbound[1], outbound[2], completion, payout, items, trade[0], trade[1],
                    List.copyOf(complete));
        });
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Read<R> {
        R in(Connection unitOfWork) throws SQLException;
    }

    /** One snapshot, read-only, with its audit record in the same transaction. */
    private <R> R read(AuditableAction action, String targetType, String target, String summary, Read<R> work) {
        Actor actor = SecurityContext.require();
        return transactions.inTransaction(unitOfWork -> {
            try {
                try (Statement snapshot = unitOfWork.createStatement()) {
                    snapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                R result = work.in(unitOfWork);
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, targetType,
                        target, Optional.empty(), AuditOutcome.SUCCEEDED, CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("a report runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of(summary)));
                return result;
            } catch (SQLException failure) {
                throw new IllegalStateException("a Phase 9 report could not be read: " + failure.getSQLState(), failure);
            }
        });
    }

    private static List<ExpectationLink> expectations(Connection uow, String operationRef, String kinds) throws SQLException {
        List<ExpectationLink> links = new ArrayList<>();
        try (PreparedStatement select = uow.prepareStatement("SELECT id::text, kind, status FROM reconciliation.expectation"
                + " WHERE operation_ref = ? AND kind IN " + kinds + " ORDER BY kind")) {
            select.setString(1, operationRef);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    links.add(new ExpectationLink(rows.getString(1), rows.getString(2), rows.getString(3)));
                }
            }
        }
        return links;
    }

    private static Optional<String[]> one(Connection uow, String sql, Object parameter, int columns) throws SQLException {
        try (PreparedStatement select = uow.prepareStatement(sql)) {
            select.setObject(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                String[] values = new String[columns];
                for (int i = 0; i < columns; i++) {
                    values[i] = row.getString(i + 1);
                }
                return Optional.of(values);
            }
        }
    }

    private YearMonth period(String month) {
        YearMonth current = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        if (month == null) {
            return current;
        }
        if (MONTH.matcher(month).matches()) {
            try {
                YearMonth period = YearMonth.parse(month);
                if (!period.isBefore(EARLIEST) && !period.isAfter(current)) {
                    return period;
                }
            } catch (java.time.format.DateTimeParseException unparseable) {
                // Falls through to the refusal: 2026-13 has the shape and no meaning.
            }
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED,
                "A Phase 9 report was asked for a period that is not a reportable calendar month",
                "month must be YYYY-MM, from " + EARLIEST + " to the current month.");
    }

    private static Timestamp start(YearMonth period) {
        return Timestamp.from(period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
