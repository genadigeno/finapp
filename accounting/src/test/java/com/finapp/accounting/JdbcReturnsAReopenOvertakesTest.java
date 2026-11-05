package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a reopen's receipts are told, as the real class reads it (`P14-TSK-023`; {@code JdbcPositionBreakdownOriginTest}'s stand-in rows): the statement pinned, since it first executes at the phase's database pass, and the real fold over what it can hand back. That PostgreSQL returns those rows for a return of each status is {@code PeriodReopenDatabaseTest#aReopensReceiptsListTheReturnsItOvertakes}'s, owed C5.
 */
@DisplayName("the returns a reopen overtakes, as the statement reads them (P14-TSK-023)")
class JdbcReturnsAReopenOvertakesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.fixed(Instant.parse("2026-11-05T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final AccountingPeriodId PERIOD = AccountingPeriodId.next(IDS);
    private static final String FORM = "REF-SAFEGUARDING-POSITION";

    private final List<Object[]> bound = new ArrayList<>();

    private static Map<String, Object> row(UUID id, String form, String status, long total) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("form_code", form);
        row.put("status", status);
        row.put("total", total);
        return row;
    }

    /** A connection answering the one statement with {@code rows}, recording what was bound; {@code failing}, the driver refuses it. */
    private Connection answering(List<Map<String, Object>> rows, boolean failing) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (connection, method, args) -> {
            if (!method.getName().equals("prepareStatement")) {
                throw new UnsupportedOperationException(method.getName());
            }
            assertThat(args[0]).isEqualTo(JdbcReturnsAReopenOvertakes.SQL);
            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[] {PreparedStatement.class}, (statement, called, given) -> switch (called.getName()) {
                case "setObject", "setInt" -> {
                    bound.add(given);
                    yield null;
                }
                case "executeQuery" -> {
                    if (failing) {
                        throw new SQLException("the driver's own words, naming a row", "53300");
                    }
                    yield rowsOf(rows);
                }
                case "close" -> null;
                default -> throw new UnsupportedOperationException(called.getName());
            });
        });
    }

    private static ResultSet rowsOf(List<Map<String, Object>> rows) {
        Iterator<Map<String, Object>> cursor = rows.iterator();
        Object[] current = new Object[1];
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "next" -> {
                    if (!cursor.hasNext()) {
                        return false;
                    }
                    current[0] = cursor.next();
                    return true;
                }
                case "getObject", "getString", "getLong" -> {
                    return ((Map<?, ?>) current[0]).get(args[0]);
                }
                case "close" -> {
                    return null;
                }
                default -> throw new UnsupportedOperationException(method.getName() + Arrays.toString(args));
            }
        });
    }

    @Test
    @DisplayName("the statement: the month's returns SUBMITTED or ACKNOWLEDGED and no other status, newest sent first, bounded, the count before the limit, one plain read taking no lock and naming no figure, person, reason or reference")
    void theStatement() {
        assertThat(JdbcReturnsAReopenOvertakes.SQL)
                .startsWith("SELECT s.id, s.form_code, s.status, count(*) OVER () AS total FROM accounting.regulatory_submission s")
                .contains("WHERE s.period_id = ? AND s.status IN ('SUBMITTED', 'ACKNOWLEDGED') ORDER BY")
                .endsWith("ORDER BY s.submitted_at DESC, s.id DESC LIMIT ?")
                .doesNotContain("FOR UPDATE").doesNotContain("FOR SHARE").doesNotContain("content").doesNotContain("_by").doesNotContain("reason").doesNotContain("provider_reference");
    }

    @Test
    @DisplayName("the read: it asks for the month and the bound, and serves each row's id, form and status in the statement's order, with the count the statement took before its limit, larger than the list when a month holds more")
    void aBoundedList() {
        UUID newest = IDS.next();
        UUID older = IDS.next();

        ReturnsAReopenOvertakes.Overtaken read = new JdbcReturnsAReopenOvertakes().of(answering(List.of(row(newest, FORM, "SUBMITTED", 47), row(older, FORM, "ACKNOWLEDGED", 47)), false), PERIOD);

        assertThat(bound).extracting(given -> given[1]).containsExactly(PERIOD.value(), ReturnsAReopenOvertakes.NEWEST);
        assertThat(ReturnsAReopenOvertakes.NEWEST).isEqualTo(20);
        assertThat(read.newest()).containsExactly(new ReturnsAReopenOvertakes.Item(RegulatorySubmissionId.of(newest), FORM, SubmissionStatus.SUBMITTED), new ReturnsAReopenOvertakes.Item(RegulatorySubmissionId.of(older), FORM, SubmissionStatus.ACKNOWLEDGED));
        assertThat(read.total()).isEqualTo(47);
    }

    @Test
    @DisplayName("a month with no returns: no row comes back, read as an empty list and zero, never an absence")
    void aMonthWithNone() {
        ReturnsAReopenOvertakes.Overtaken read = new JdbcReturnsAReopenOvertakes().of(answering(List.of(), false), PERIOD);

        assertThat(read.newest()).isEmpty();
        assertThat(read.total()).isZero();
    }

    @Test
    @DisplayName("a read that fails is a failure, never an empty list: it throws, naming its operation and SQLSTATE and not the driver's words")
    void aFailedReadIsNoEmptyList() {
        assertThatThrownBy(() -> new JdbcReturnsAReopenOvertakes().of(answering(List.of(), true), PERIOD)).isInstanceOf(AccountingStorageException.class)
                .hasMessageContaining("reading the returns a reopen overtakes").hasMessageContaining("53300").hasMessageNotContaining("naming a row");
    }

    @Test
    @DisplayName("what a receipt is handed holds the ruling's three and no more: nothing of a figure, a person, a reason or a reference can reach it")
    void anItemHoldsThreeThings() {
        assertThat(ReturnsAReopenOvertakes.Item.class.getRecordComponents()).extracting(java.lang.reflect.RecordComponent::getName).containsExactly("id", "form", "status");
    }
}
