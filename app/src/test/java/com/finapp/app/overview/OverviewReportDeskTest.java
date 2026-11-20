package com.finapp.app.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.party.CustomerReports;
import com.finapp.party.CustomerStatus;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.TransferReports;
import com.finapp.transfers.TransferStatus;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The overview reports' requests and views, without a database (`P17-TSK-039`): a report day is an ISO date from 2000 on; every
 * lifecycle status is reported, one nobody stands in as zero; a total is exact at its stored scale; and the transfers' day window
 * on identifiers holds every id minted in it and none before. The reads, the as-of, the permission and the records are
 * {@code OverviewReportsDatabaseTest}'s.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("the overview reports' requests (P17-TSK-039)")
class OverviewReportDeskTest {

    @Test
    @DisplayName("a report day is an ISO date that exists, on or after 2000-01-01, and required")
    void aReportDayIsStrict() {
        assertThat(OverviewReportDesk.reportDay("2026-11-20")).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(OverviewReportDesk.reportDay("2000-01-01")).isEqualTo(LocalDate.parse("2000-01-01"));
        for (String refused : new String[] {null, "", "2026-11-2", "20261120", "2026-02-30", "1999-12-31", "today"}) {
            assertRefused(() -> OverviewReportDesk.reportDay(refused));
        }
    }

    @Test
    @DisplayName("every lifecycle status is reported in the machine's order, one nobody stands in as zero")
    void everyStatusIsReported() {
        var rows = OverviewReportDesk.statusRows(Map.of(
                CustomerStatus.ACTIVE, new CustomerReports.StatusCount(CustomerStatus.ACTIVE, 7, 2, 1),
                CustomerStatus.CLOSED, new CustomerReports.StatusCount(CustomerStatus.CLOSED, 3, 0, 3)));
        assertThat(rows).extracting(OverviewReportDesk.CustomerStatusRow::status)
                .containsExactly("PENDING", "ACTIVE", "SUSPENDED", "CLOSED", "REJECTED");
        assertThat(rows.get(1)).isEqualTo(new OverviewReportDesk.CustomerStatusRow("ACTIVE", 7, 2, 1));
        assertThat(rows.get(0)).isEqualTo(new OverviewReportDesk.CustomerStatusRow("PENDING", 0, 0, 0));
        assertThat(rows.get(3)).isEqualTo(new OverviewReportDesk.CustomerStatusRow("CLOSED", 3, 0, 3));
    }

    @Test
    @DisplayName("a transfers total is exact at its stored scale, and a failure's reason only when it failed")
    void aTotalIsExact() {
        var bhd = OverviewReportDesk.outcomeRow(new TransferReports.OutcomeSum(TransferStatus.COMPLETED, Optional.empty(),
                CurrencyCode.of("BHD"), 3, 2, new BigDecimal("1234")));
        assertThat(bhd).isEqualTo(new OverviewReportDesk.TransferOutcomeRow("COMPLETED", null, "BHD", 2, "1.234"));
        var failed = OverviewReportDesk.outcomeRow(new TransferReports.OutcomeSum(TransferStatus.FAILED,
                Optional.of(FailureReason.INSUFFICIENT_FUNDS), CurrencyCode.of("JPY"), 0, 1, new BigDecimal("500")));
        assertThat(failed).isEqualTo(new OverviewReportDesk.TransferOutcomeRow("FAILED", "INSUFFICIENT_FUNDS", "JPY", 1, "500"));
        var big = OverviewReportDesk.outcomeRow(new TransferReports.OutcomeSum(TransferStatus.COMPLETED, Optional.empty(),
                CurrencyCode.of("EUR"), 2, 3, new BigDecimal("922337203685477580700")));
        assertThat(big.amount()).as("past a long, still exact").isEqualTo("9223372036854775807.00");
        assertThatThrownBy(() -> new TransferReports.OutcomeSum(TransferStatus.COMPLETED, Optional.empty(), CurrencyCode.of("EUR"),
                2, 1, new BigDecimal("1.5"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the transfers' day window on identifiers holds every id minted in the day and none before or after,"
            + " in the database's byte order")
    void theDayWindowHoldsTheDaysIds() {
        Instant start = LocalDate.parse("2026-11-20").atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = start.plusSeconds(86_400);
        UUID from = TransferReports.firstIdAt(start);
        UUID to = TransferReports.firstIdAt(end);
        for (int i = 0; i < 200; i++) {
            assertThat(order(minted(start), from)).as("the day's first millisecond is in").isGreaterThanOrEqualTo(0);
            assertThat(order(minted(end.minusMillis(1)), to)).as("its last is in").isLessThan(0);
            assertThat(order(minted(start.minusMillis(1)), from)).as("the day before is out").isLessThan(0);
            assertThat(order(minted(end), to)).as("the next day is out").isGreaterThanOrEqualTo(0);
        }
        assertThatThrownBy(() -> TransferReports.firstIdAt(Instant.ofEpochMilli(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the as-of is the database's instant - the report transaction's first statement - never the instance's clock,"
            + " and each serving is recorded in that transaction")
    void theAsOfIsTheDatabases() {
        Instant database = Instant.parse("2001-02-03T04:05:06.789Z");
        Instant instance = Instant.parse("2026-11-20T12:00:00Z");
        List<String> statements = new ArrayList<>();
        Connection connection = connection(database, statements);
        List<AuditRecord> written = new ArrayList<>();
        OverviewReportDesk desk = OverviewReportDeskFixture.desk(
                (uow, from, to) -> {
                    statements.add("customers");
                    return List.of(new CustomerReports.StatusCount(CustomerStatus.ACTIVE, 4, 1, 0));
                },
                (uow, from, to) -> {
                    statements.add("transfers");
                    return List.of();
                },
                (uow, record) -> written.add(record),
                instance,
                connection);
        try (var correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.of("the-overview")));
                var actor = SecurityContext.enter(new Actor("op-viewer", ActorType.EMPLOYEE))) {
            OverviewReportDesk.CustomersReportView customers = desk.customers("2026-11-19");
            assertThat(customers.asOf()).isEqualTo(database.toString());
            assertThat(customers.total()).isEqualTo(4);
            OverviewReportDesk.TransfersReportView transfers = desk.transfers("2026-11-19");
            assertThat(transfers.asOf()).isEqualTo(database.toString());
        }
        assertThat(statements).as("the instant read before every figure")
                .containsExactly("SELECT statement_timestamp()", "customers", "SELECT statement_timestamp()", "transfers");
        assertThat(written).extracting(record -> record.operation().code())
                .containsExactly("party.CustomersReportRead", "transfers.TransfersReportRead");
    }

    /** A connection whose database answers {@code now} to the clock's query, logging each statement. */
    private static Connection connection(Instant now, List<String> statements) {
        ResultSet clock = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> true;
                    case "getTimestamp" -> Timestamp.from(now);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        statements.add((String) args[0]);
                        yield clock;
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement;
                    case "close" -> null;
                    case "isClosed" -> false;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static UUID minted(Instant at) {
        return new IdGenerator(Clock.fixed(at, ZoneOffset.UTC), new SecureRandom()).next();
    }

    /** PostgreSQL's uuid order: the sixteen bytes compared unsigned, left to right. */
    private static int order(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }

    private static void assertRefused(ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
    }
}
