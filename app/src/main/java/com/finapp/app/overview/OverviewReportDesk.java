package com.finapp.app.overview;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.party.CustomerReports;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyAuditAction;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseTime;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.TransferReports;
import com.finapp.transfers.TransfersAuditAction;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operations overview's own figures (`P17-TSK-039`, finapp-admin-panel B10.1, gap G-150): the customers report - how many
 * customers stand in each lifecycle status, and a day's openings and changes - and a day's transfers report - how many and how
 * much, by outcome and currency. Under {@code PLATFORM_OVERVIEW_READ}, beside the payments report, the overview's third.
 *
 * <p>Each report is one transaction: the database's instant first ({@code asOf} - every figure is at least as fresh), then the
 * read, then its record ({@code party.CustomersReportRead}, {@code transfers.TransfersReportRead}) in the same transaction, so
 * nothing is served unrecorded. Counts and totals only: no customer and no transfer is ever named.
 */
@RequiredArgsConstructor
public final class OverviewReportDesk {

    static final LocalDate EARLIEST_REPORT_DAY = LocalDate.of(2000, 1, 1);
    static final String CUSTOMERS_TARGET = "customers_report";
    static final String TRANSFERS_TARGET = "transfers_report";
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");

    @NonNull private final CustomerReports customers;
    @NonNull private final TransferReports transfers;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** The customers report: every lifecycle status with its counts, the standing total, and the named UTC day. */
    public record CustomersReportView(String date, String asOf, long total, List<CustomerStatusRow> statuses) {}

    /**
     * One lifecycle status: the customers standing in it at {@code asOf}, and of them those opened on the day and those whose last
     * status change, after their opening, fell on the day.
     */
    public record CustomerStatusRow(String status, long standing, long openedOnDay, long changedOnDay) {}

    /** The day's transfers by outcome and currency. */
    public record TransfersReportView(String date, String asOf, List<TransferOutcomeRow> outcomes) {}

    /** One outcome in one currency: how many transfers, and their amounts' exact total as a decimal string. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferOutcomeRow(String status, String failureReason, String currency, long count, String amount) {}

    /** The customers report for the UTC day {@code rawDate} - read and recorded. */
    public CustomersReportView customers(String rawDate) {
        LocalDate day = reportDay(rawDate);
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Actor actor = SecurityContext.require();
        return inOneTransaction(uow -> {
            // The transaction's first statement: the database's instant the counts are at least as fresh as.
            Instant asOf = DatabaseTime.now(uow);
            Map<CustomerStatus, CustomerReports.StatusCount> counted = customers.counts(uow, from, to).stream()
                    .collect(Collectors.toMap(CustomerReports.StatusCount::status, Function.identity()));
            List<CustomerStatusRow> rows = statusRows(counted);
            long total = rows.stream().mapToLong(CustomerStatusRow::standing).sum();
            record(uow, actor, PartyAuditAction.CUSTOMERS_REPORT_READ, CUSTOMERS_TARGET, day.toString(),
                    "date=" + day + ", total=" + total);
            return new CustomersReportView(day.toString(), asOf.toString(), total, rows);
        });
    }

    /** The transfers report for the UTC day {@code rawDate} - read and recorded. */
    public TransfersReportView transfers(String rawDate) {
        LocalDate day = reportDay(rawDate);
        Instant from = day.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Actor actor = SecurityContext.require();
        return inOneTransaction(uow -> {
            // The transaction's first statement: the database's instant the totals are at least as fresh as.
            Instant asOf = DatabaseTime.now(uow);
            List<TransferOutcomeRow> rows = transfers.outcomes(uow, from, to).stream()
                    .map(OverviewReportDesk::outcomeRow)
                    .toList();
            record(uow, actor, TransfersAuditAction.TRANSFERS_REPORT_READ, TRANSFERS_TARGET, day.toString(),
                    "date=" + day + ", outcomes=" + rows.size());
            return new TransfersReportView(day.toString(), asOf.toString(), rows);
        });
    }

    // ------------------------------------------------------------------ the request's words and the views

    /** A UTC day: an ISO date, on or after 2000-01-01. */
    static LocalDate reportDay(String raw) {
        if (raw == null || !DATE.matcher(raw).matches()) {
            throw OperatorCollection.invalid("date is required: an ISO date (YYYY-MM-DD).");
        }
        LocalDate day;
        try {
            day = LocalDate.parse(raw);
        } catch (DateTimeParseException impossible) {
            throw OperatorCollection.invalid("date is an ISO date (YYYY-MM-DD) that exists.");
        }
        if (day.isBefore(EARLIEST_REPORT_DAY)) {
            throw OperatorCollection.invalid("date is on or after " + EARLIEST_REPORT_DAY + ".");
        }
        return day;
    }

    /** Every lifecycle status, in the machine's order - one nobody stands in counted zero, so the overview never guesses. */
    static List<CustomerStatusRow> statusRows(Map<CustomerStatus, CustomerReports.StatusCount> counted) {
        return Arrays.stream(CustomerStatus.values())
                .map(status -> Optional.ofNullable(counted.get(status))
                        .map(count -> new CustomerStatusRow(status.name(), count.standing(), count.openedInWindow(),
                                count.changedInWindow()))
                        .orElseGet(() -> new CustomerStatusRow(status.name(), 0, 0, 0)))
                .toList();
    }

    static TransferOutcomeRow outcomeRow(TransferReports.OutcomeSum sum) {
        return new TransferOutcomeRow(
                sum.status().name(),
                sum.failureReason().map(Enum::name).orElse(null),
                sum.currency().code(),
                sum.count(),
                sum.amount().toPlainString());
    }

    private void record(Connection uow, Actor actor, AuditableAction action, String targetType, String targetId, String summary) {
        audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), action, targetType, targetId,
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation(), Optional.of(summary)));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("an overview report runs inside a correlation scope"))
                .correlationId();
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }
}
