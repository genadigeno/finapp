package com.finapp.app.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditTrail;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.PlatformAuditAction;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit trail's operator read (`P17-TSK-017`, gap G-20; finapp-admin-panel ADR-C134): the §7 rules, a required window of at
 * most thirty-one days, and every read recorded in the read's own transaction as {@code audit.RecordsRead} - a page with its
 * filter and count, a record with itself as the target. What a page promises about rows committed late is {@link AuditTrail}'s.
 * On the application's pool, never a replica (ADR-0129): the plans the indexes give (OPERATIONS_RUNBOOK.md section 11.48) show no
 * contention to move it for.
 */
@RequiredArgsConstructor
public class AuditTrailService {

    /** The trail's collection name, inside its cursors. */
    static final String TRAIL = "audit.records";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    /** The widest window one read covers (ADR-C134 decision 4's proposal, kept): the window, not retention, bounds a query. */
    static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);
    /** A filter value no stored column could exceed (V009 bounds every text column at 200, the correlation at 128). */
    private static final int MAXIMUM_FILTER = 200;

    @NonNull private final AuditTrail<Connection> trail;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** A §7 page of the window, newest first; recorded once, its filter and count the summary. */
    public AuditRecordPage page(
            String from, String to, String actorId, String operation, String targetType, String targetId, String correlationId,
            String outcome, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Instant start = instant("from", from);
        Instant end = instant("to", to);
        if (!start.isBefore(end)) {
            throw OperatorCollection.invalid("from is before to: the window is [from, to).");
        }
        if (Duration.between(start, end).compareTo(MAXIMUM_WINDOW) > 0) {
            throw OperatorCollection.invalid("the window is at most " + MAXIMUM_WINDOW.toDays() + " days; narrow it.");
        }
        AuditTrail.Filter filter = new AuditTrail.Filter(start, end, text("actor", actorId), text("operation", operation),
                text("targetType", targetType), text("targetId", targetId), text("correlationId", correlationId),
                Optional.ofNullable(outcome).map(value -> {
                    try {
                        return AuditOutcome.valueOf(value);
                    } catch (IllegalArgumentException unknown) {
                        throw OperatorCollection.invalid("outcome is one of " + Arrays.toString(AuditOutcome.values()) + ".");
                    }
                }));
        Optional<AuditTrail.Cursor> after = OperatorCollection.key(TRAIL, cursor, 2).map(key -> {
            try {
                return new AuditTrail.Cursor(Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        List<AuditTrail.Row> rows = inOneTransaction(unitOfWork -> {
            List<AuditTrail.Row> read = trail.page(unitOfWork, filter, after, size + 1);
            record(unitOfWork, "audit-trail", summary(filter, after.isPresent(), Math.min(size, read.size())));
            return read;
        });
        List<AuditTrail.Row> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            AuditTrail.Row tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(TRAIL,
                    Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, tail.occurredAt())), tail.auditId().toString());
        }
        return new AuditRecordPage(served.stream().map(AuditRecordSummary::of).toList(), next);
    }

    /** One record; unknown and malformed ids are one {@code 404}, and a record that does not exist is no read. */
    public AuditRecordView record(String rawId) {
        UUID auditId;
        try {
            auditId = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        return inOneTransaction(unitOfWork -> {
            Optional<AuditTrail.Row> found = trail.byId(unitOfWork, auditId);
            found.ifPresent(row -> record(unitOfWork, auditId.toString(), "record"));
            return found;
        }).map(AuditRecordView::of).orElseThrow(AuditTrailService::notFound);
    }

    private void record(Connection unitOfWork, String targetId, String summary) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        PlatformAuditAction.AUDIT_RECORDS_READ,
                        "platform.AuditRecord",
                        targetId,
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(() -> new IllegalStateException("an audit trail read runs inside a correlation scope"))
                                .correlationId(),
                        Optional.of(summary)));
    }

    private static String summary(AuditTrail.Filter filter, boolean continued, int served) {
        StringBuilder text = new StringBuilder("from=").append(filter.from()).append(", to=").append(filter.to());
        filter.actorId().ifPresent(value -> text.append(", actor=").append(value));
        filter.operation().ifPresent(value -> text.append(", operation=").append(value));
        filter.targetType().ifPresent(value -> text.append(", targetType=").append(value));
        filter.targetId().ifPresent(value -> text.append(", targetId=").append(value));
        filter.correlationId().ifPresent(value -> text.append(", correlationId=").append(value));
        filter.outcome().ifPresent(value -> text.append(", outcome=").append(value));
        text.append(", continued=").append(continued).append(", served=").append(served);
        String summary = text.toString();
        return summary.length() <= 1000 ? summary : summary.substring(0, 1000);
    }

    private static Instant instant(String name, String value) {
        if (value == null) {
            throw OperatorCollection.invalid(name + " is required: a read of the audit trail names its window.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException unreadable) {
            throw OperatorCollection.invalid(name + " is an instant, e.g. 2026-11-18T00:00:00Z.");
        }
    }

    private static Optional<String> text(String name, String value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value.isBlank() || value.length() > MAXIMUM_FILTER) {
            throw OperatorCollection.invalid(name + " is 1 to " + MAXIMUM_FILTER + " characters.");
        }
        return Optional.of(value);
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No audit record matched the given identifier");
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

    /** A §7 page: {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditRecordPage(List<AuditRecordSummary> items, String nextCursor) {}

    /** One record in a page: who, when, what, to what, how it came out, in which flow, and its summary. */
    public record AuditRecordSummary(
            String auditId,
            Instant occurredAt,
            String actorId,
            String operation,
            String targetType,
            String targetId,
            String outcome,
            String correlationId,
            String changeSummary) {
        static AuditRecordSummary of(AuditTrail.Row row) {
            return new AuditRecordSummary(row.auditId().toString(), row.occurredAt(), row.actorId(), row.operation(),
                    row.targetType(), row.targetId(), row.outcome().name(), row.correlationId(), row.changeSummary().orElse(null));
        }
    }

    /** One record in full: the summary's fields, the actor's type and the reason given. */
    public record AuditRecordView(
            String auditId,
            Instant occurredAt,
            String actorId,
            String actorType,
            String operation,
            String targetType,
            String targetId,
            String reason,
            String outcome,
            String correlationId,
            String changeSummary) {
        static AuditRecordView of(AuditTrail.Row row) {
            return new AuditRecordView(row.auditId().toString(), row.occurredAt(), row.actorId(), row.actorType(), row.operation(),
                    row.targetType(), row.targetId(), row.reason().orElse(null), row.outcome().name(), row.correlationId(),
                    row.changeSummary().orElse(null));
        }
    }
}
