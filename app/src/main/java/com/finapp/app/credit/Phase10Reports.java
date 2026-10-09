package com.finapp.app.credit;

import com.finapp.credit.CreditAuditAction;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 10's operations reports (`P10-TSK-020`; PHASE_10_PLAN.md sections 9 and 15): a month's decision outcomes by
 * pinned policy version, its reason-code distribution, and its credit data sources' availability. Each is read by a
 * holder of {@code CREDIT_INVESTIGATE} in ONE {@code REPEATABLE READ} snapshot - so a report's total and its rows agree
 * whatever commits beside it - bounded at 100 rows with {@code truncated}, and audited {@code credit.ReportRead} in the
 * read's own transaction. A malformed or unreportable month is {@code 422} before any read.
 *
 * <p><strong>Counts and rates only.</strong> No amount, score, attribute value, party, person or decision identifier is
 * ever read into a report ({@code INV-CRD-02}); a rate is a decimal string of counts at four places, never floating
 * point. Credit portfolio reporting - exposure, amounts approved - is Phase 14's.
 */
public final class Phase10Reports {

    /** Every report's bound: {@code truncated} says when there were more rows. */
    static final int BOUND = 100;

    private static final Pattern MONTH = Pattern.compile("^[0-9]{4}-[0-9]{2}$");
    private static final YearMonth EARLIEST = YearMonth.of(2026, 1);

    private final TransactionRunner transactions;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public Phase10Reports(TransactionRunner transactions, AuditWriter<Connection> audit, IdGenerator ids, Clock clock) {
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    // ------------------------------------------------------------------ outcomes by pinned policy version

    /** One product's decisions under one pinned policy version by one kind of decider. */
    public record OutcomeRow(
            String product, int policyVersion, String decidedBy, long decisions, long approved, long declined,
            String approvalRate) {}

    /** One product's referrals to a person under one pinned policy version. */
    public record ReferralRow(String product, int policyVersion, long referred) {}

    public record OutcomesReport(
            String month, long decisions, List<OutcomeRow> rows, List<ReferralRow> referrals, boolean truncated) {}

    /** A month's decisions by product, the policy version each pinned and who decided; the referrals opened. */
    public OutcomesReport outcomes(String month) {
        YearMonth period = period(month);
        return read("outcomes", period, uow -> {
            Timestamp from = start(period);
            Timestamp to = start(period.plusMonths(1));
            List<OutcomeRow> rows = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT d.product, p.version, d.decided_by_type, count(*),"
                            + " count(*) FILTER (WHERE d.outcome = 'APPROVED'), count(*) FILTER (WHERE d.outcome = 'DECLINED')"
                            + " FROM credit.credit_decision d JOIN credit.credit_policy_version p ON p.id = d.policy_version_id"
                            + " WHERE d.decided_at >= ? AND d.decided_at < ? GROUP BY 1, 2, 3 ORDER BY 1, 2, 3 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        if (rows.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        long decisions = found.getLong(4);
                        long approved = found.getLong(5);
                        rows.add(new OutcomeRow(tag(found.getString(1)), found.getInt(2), decidedBy(found.getString(3)),
                                decisions, approved, found.getLong(6), rate(approved, decisions)));
                    }
                }
            }
            long decisions = decisionsIn(uow, from, to);
            List<ReferralRow> referrals = new ArrayList<>();
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT c.product, p.version, count(*) FROM credit.underwriting_case c"
                            + " JOIN credit.decision_request r ON r.id = c.decision_request_id"
                            + " JOIN credit.credit_policy_version p ON p.id = r.pinned_policy_version_id"
                            + " WHERE c.opened_at >= ? AND c.opened_at < ? GROUP BY 1, 2 ORDER BY 1, 2 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        if (referrals.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        referrals.add(new ReferralRow(tag(found.getString(1)), found.getInt(2), found.getLong(3)));
                    }
                }
            }
            return new Served<>(new OutcomesReport(period.toString(), decisions, List.copyOf(rows), List.copyOf(referrals),
                    truncated), rows.size() + referrals.size());
        });
    }

    // ------------------------------------------------------------------ the reason-code distribution

    /** How many of one product's decisions carried one catalogued reason, and how many as their first. */
    public record ReasonRow(String product, String reasonCode, long decisions, long asPrimary) {}

    public record ReasonsReport(String month, long decisions, List<ReasonRow> rows, boolean truncated) {}

    /** A month's reason codes per product - every decision's, the platform's and a person's. */
    public ReasonsReport reasons(String month) {
        YearMonth period = period(month);
        return read("reasons", period, uow -> {
            Timestamp from = start(period);
            Timestamp to = start(period.plusMonths(1));
            List<ReasonRow> rows = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT d.product, r.reason_code, count(*), count(*) FILTER (WHERE r.ordinal = 1)"
                            + " FROM credit.credit_decision_reason r JOIN credit.credit_decision d ON d.id = r.decision_id"
                            + " WHERE d.decided_at >= ? AND d.decided_at < ? GROUP BY 1, 2 ORDER BY 1, 2 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        if (rows.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        rows.add(new ReasonRow(tag(found.getString(1)), found.getString(2), found.getLong(3),
                                found.getLong(4)));
                    }
                }
            }
            return new Served<>(new ReasonsReport(period.toString(), decisionsIn(uow, from, to), List.copyOf(rows),
                    truncated), rows.size());
        });
    }

    // ------------------------------------------------------------------ source availability

    /**
     * One provider's data requests born in the month, where they stand, how many attempts they took and how many were
     * reported unavailable past their deadline; {@code availability} is received over answered (received or
     * unavailable), absent when none answered.
     */
    public record SourceRow(
            String sourceKind, String provider, long dataRequests, long received, long unavailable,
            long consentWithdrawn, long awaiting, long reportedOverdue, long attempts, String availability) {}

    public record SourcesReport(String month, long dataRequests, List<SourceRow> rows, boolean truncated) {}

    /** A month's credit data requests by source kind and provider - the bureau-cost proxy's own counts. */
    public SourcesReport sources(String month) {
        YearMonth period = period(month);
        return read("sources", period, uow -> {
            Timestamp from = start(period);
            Timestamp to = start(period.plusMonths(1));
            List<SourceRow> rows = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT source_kind, provider_code, count(*), count(*) FILTER (WHERE status = 'RECEIVED'),"
                            + " count(*) FILTER (WHERE status = 'UNAVAILABLE'),"
                            + " count(*) FILTER (WHERE status = 'CONSENT_WITHDRAWN'),"
                            + " count(*) FILTER (WHERE status = 'REQUESTED'), count(*) FILTER (WHERE unavailable_reported),"
                            + " COALESCE(SUM(attempts), 0) FROM credit.data_request"
                            + " WHERE requested_at >= ? AND requested_at < ? GROUP BY 1, 2 ORDER BY 1, 2 LIMIT ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                select.setInt(3, BOUND + 1);
                try (ResultSet found = select.executeQuery()) {
                    while (found.next()) {
                        if (rows.size() == BOUND) {
                            truncated = true;
                            break;
                        }
                        long received = found.getLong(4);
                        long unavailable = found.getLong(5);
                        rows.add(new SourceRow(tag(found.getString(1)), found.getString(2), found.getLong(3), received,
                                unavailable, found.getLong(6), found.getLong(7), found.getLong(8), found.getLong(9),
                                rate(received, received + unavailable)));
                    }
                }
            }
            long dataRequests;
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT count(*) FROM credit.data_request WHERE requested_at >= ? AND requested_at < ?")) {
                select.setTimestamp(1, from);
                select.setTimestamp(2, to);
                try (ResultSet found = select.executeQuery()) {
                    found.next();
                    dataRequests = found.getLong(1);
                }
            }
            return new Served<>(new SourcesReport(period.toString(), dataRequests, List.copyOf(rows), truncated),
                    rows.size());
        });
    }

    // ------------------------------------------------------------------ plumbing

    /** A report and how many rows it serves - the audit's detail. */
    private record Served<R>(R report, int rows) {}

    @FunctionalInterface
    private interface Read<R> {
        Served<R> in(Connection unitOfWork) throws SQLException;
    }

    /** One snapshot, with its audit record in the same transaction. */
    private <R> R read(String report, YearMonth period, Read<R> work) {
        Actor actor = SecurityContext.require();
        return transactions.inTransaction(unitOfWork -> {
            try {
                try (Statement snapshot = unitOfWork.createStatement()) {
                    snapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
                }
                Served<R> served = work.in(unitOfWork);
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                        CreditAuditAction.REPORT_READ, "credit_report", report, Optional.empty(), AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("a report runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of("report=" + report + ", month=" + period + ", rows=" + served.rows())));
                return served.report();
            } catch (SQLException failure) {
                throw new IllegalStateException("a Phase 10 report could not be read: " + failure.getSQLState(), failure);
            }
        });
    }

    private static long decisionsIn(Connection uow, Timestamp from, Timestamp to) throws SQLException {
        try (PreparedStatement select = uow.prepareStatement(
                "SELECT count(*) FROM credit.credit_decision WHERE decided_at >= ? AND decided_at < ?")) {
            select.setTimestamp(1, from);
            select.setTimestamp(2, to);
            try (ResultSet found = select.executeQuery()) {
                found.next();
                return found.getLong(1);
            }
        }
    }

    /** {@code part} over {@code whole} at four places, half-even; null when nothing was counted. */
    static String rate(long part, long whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_EVEN).toPlainString();
    }

    private static String decidedBy(String decidedByType) {
        return "SYSTEM".equals(decidedByType) ? "system" : "person";
    }

    private static String tag(String name) {
        return name.toLowerCase(Locale.ROOT);
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
                "A Phase 10 report was asked for a period that is not a reportable calendar month",
                "month must be YYYY-MM, from " + EARLIEST + " to the current month.");
    }

    private static Timestamp start(YearMonth period) {
        return Timestamp.from(period.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
