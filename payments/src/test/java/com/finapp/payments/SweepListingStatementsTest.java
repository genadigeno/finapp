package com.finapp.payments;

import static com.finapp.platform.testing.ScriptedListingConnection.row;
import static com.finapp.platform.testing.ScriptedListingConnection.versionFour;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.persistence.SweepBackoff;
import com.finapp.platform.persistence.SweepListing;
import com.finapp.platform.testing.ScriptedListingConnection;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The payments module's seven sweep listings and their failure record, run as written over a scripted JDBC double
 * (`P16-TSK-017`; {@code THREAT_MODEL.md} section 6 item 42 (i); {@code OPERATIONS_RUNBOOK.md} section 11.39): each listing
 * skips and reports a row it cannot read rather than ending, leaves out a row whose retry is still ahead on the database's
 * clock - bound to the whole candidacy, never to one arm of an {@code OR} - and the record counts one failure per backoff
 * window, on the database's clock. What PostgreSQL makes of the statements is the owed database cases'.
 */
@DisplayName("each payments sweep listing skips a row it cannot read and leaves out a row in backoff; the record counts one failure"
        + " per window on the database's clock (P16-TSK-017)")
class SweepListingStatementsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    /** One listing: its leg, the fragment that names its statement, and the call. */
    private record Listing(SweepLeg leg, String alias, BiFunction<Connection, Instant, SweepListing<?>> call) {}

    static Stream<Arguments> listings() {
        JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
        JdbcRefundStore refunds = new JdbcRefundStore();
        JdbcWithdrawalStore withdrawals = new JdbcWithdrawalStore();
        JdbcDisputeResponseStore responses = new JdbcDisputeResponseStore();
        return Stream.of(
                new Listing(SweepLeg.CARD_ATTEMPT, "a", (uow, at) -> attempts.findSweepable(uow, at, at, 50)),
                new Listing(SweepLeg.CARD_STRANDED, "a", (uow, at) -> attempts.findStrandedAuthorizations(uow, at, 50)),
                new Listing(SweepLeg.PAY_IN, "payment_attempt", (uow, at) -> attempts.findResolvableInitiations(uow, at, 50)),
                new Listing(SweepLeg.CARD_REFUND, "r", (uow, at) -> refunds.findSweepable(uow, at, at, 50)),
                new Listing(SweepLeg.RETURN, "r", (uow, at) -> refunds.findSweepableReturns(uow, at, at, 50)),
                new Listing(SweepLeg.WITHDRAWAL, "w", (uow, at) -> withdrawals.findSweepable(uow, at, at, 50)),
                new Listing(SweepLeg.DISPUTE_ANSWER, "r", (uow, at) -> responses.findSweepable(uow, at, at, 50)))
                .map(listing -> Arguments.of(listing.leg(), listing));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("a v4 id at the head is skipped and reported by its raw id, never the listing's end")
    void aCorruptIdIsSkippedAndReported(SweepLeg leg, Listing listing) {
        UUID corrupt = versionFour();
        ScriptedListingConnection jdbc =
                new ScriptedListingConnection(NOW).answering("f.leg = '" + leg.name() + "'", List.of(row("id", corrupt)));
        SweepListing<?> listed = listing.call().apply(jdbc.connection(), NOW);
        assertThat(listed.candidates()).as("nothing judged from an unread row").isEmpty();
        assertThat(listed.unreadable()).containsExactly(corrupt);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listings")
    @DisplayName("the listing leaves out a row whose own leg's retry is still ahead, on the database's clock, over the whole"
            + " candidacy: no top-level OR survives beside it")
    void theBackoffBindsTheWholeCandidacy(SweepLeg leg, Listing listing) {
        ScriptedListingConnection jdbc = new ScriptedListingConnection(NOW);
        listing.call().apply(jdbc.connection(), NOW);
        String sql = jdbc.sentContaining("payments.sweep_row_failure").get(0).sql();
        assertThat(sql).contains(" AND NOT EXISTS (SELECT 1 FROM payments.sweep_row_failure f WHERE f.leg = '" + leg.name()
                + "' AND f.row_id = " + listing.alias() + ".id AND f.retry_at > statement_timestamp())");
        assertThat(sql.indexOf("payments.sweep_row_failure")).as("in the WHERE, before the order and the limit")
                .isLessThan(sql.indexOf(" ORDER BY "));
        assertThat(topLevelOr(outerWhere(sql))).as("an OR at the outer WHERE's top level would bind the backoff to one arm")
                .isFalse();
        assertThat(sql).as("one leg's records only").doesNotContainPattern("f\\.leg = '(?!" + leg.name() + "')");
    }

    @Test
    @DisplayName("the rule above sees a backoff bound to one arm of an OR: a planted precedence error is refused")
    void theRuleSeesAnUnparenthesisedOr() {
        String planted = "SELECT id FROM t w LEFT JOIN LATERAL (SELECT max(x) FROM e WHERE e.id = w.id OR e.y = 1) h ON true"
                + " WHERE (w.status = 'A' AND w.t <= ?) OR (w.status = 'B' AND w.u <= ?) AND NOT EXISTS (SELECT 1) ORDER BY w.id";
        assertThat(topLevelOr(outerWhere(planted))).isTrue();
        String held = planted.replace("WHERE (w.status = 'A'", "WHERE ((w.status = 'A'").replace("<= ?) AND NOT", "<= ?)) AND NOT");
        assertThat(topLevelOr(outerWhere(held))).isFalse();
    }

    /** The outer query's WHERE clause: from its depth-0 {@code WHERE} to its depth-0 {@code ORDER BY}. */
    private static String outerWhere(String sql) {
        int depth = 0;
        int start = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && sql.startsWith(" WHERE ", i) && start < 0) {
                start = i + 7;
            } else if (depth == 0 && start >= 0 && sql.startsWith(" ORDER BY ", i)) {
                return sql.substring(start, i);
            }
        }
        throw new AssertionError("no outer WHERE ... ORDER BY in " + sql);
    }

    private static boolean topLevelOr(String clause) {
        int depth = 0;
        for (int i = 0; i < clause.length(); i++) {
            char c = clause.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && clause.regionMatches(true, i, " OR ", 0, 4)) {
                return true;
            }
        }
        return false;
    }

    /** One failure recorder: the leg it records under and the call. */
    private record Recorder(SweepLeg leg, RecordCall call) {}

    @FunctionalInterface
    private interface RecordCall {
        boolean record(Connection uow, UUID row, String failure);
    }

    static Stream<Arguments> recorders() {
        JdbcPaymentAttemptStore attempts = new JdbcPaymentAttemptStore();
        JdbcRefundStore refunds = new JdbcRefundStore();
        return Stream.of(
                new Recorder(SweepLeg.CARD_ATTEMPT, (uow, row, f) -> attempts.recordSweepFailure(uow, SweepLeg.CARD_ATTEMPT, row, f)),
                new Recorder(SweepLeg.CARD_STRANDED, (uow, row, f) -> attempts.recordSweepFailure(uow, SweepLeg.CARD_STRANDED, row, f)),
                new Recorder(SweepLeg.PAY_IN, (uow, row, f) -> attempts.recordSweepFailure(uow, SweepLeg.PAY_IN, row, f)),
                new Recorder(SweepLeg.CARD_REFUND, (uow, row, f) -> refunds.recordSweepFailure(uow, SweepLeg.CARD_REFUND, row, f)),
                new Recorder(SweepLeg.RETURN, (uow, row, f) -> refunds.recordSweepFailure(uow, SweepLeg.RETURN, row, f)),
                new Recorder(SweepLeg.WITHDRAWAL, (uow, row, f) -> new JdbcWithdrawalStore().recordSweepFailure(uow, row, f)),
                new Recorder(SweepLeg.DISPUTE_ANSWER, (uow, row, f) -> new JdbcDisputeResponseStore().recordSweepFailure(uow, row, f)))
                .map(recorder -> Arguments.of(recorder.leg(), recorder));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recorders")
    @DisplayName("each store records under its own leg with the one conditional, capped, database-clocked statement, and reports"
            + " whether it counted")
    void theRecordCountsOncePerWindow(SweepLeg leg, Recorder recorder) {
        UUID row = versionFour();
        ScriptedListingConnection counted = new ScriptedListingConnection(NOW).updating("payments.sweep_row_failure", 1);
        assertThat(recorder.call().record(counted.connection(), row, "IllegalStateException")).isTrue();
        ScriptedListingConnection inBackoff = new ScriptedListingConnection(NOW).updating("payments.sweep_row_failure", 0);
        assertThat(recorder.call().record(inBackoff.connection(), row, "IllegalStateException"))
                .as("a row whose retry is still ahead: another instance counted it").isFalse();
        ScriptedListingConnection.Sent sent = counted.sentContaining("INSERT INTO payments.sweep_row_failure").get(0);
        assertThat(sent.parameter(1)).isEqualTo(leg.name());
        assertThat(sent.parameter(2)).isEqualTo(row);
        assertThat(sent.parameter(3)).as("the base").isEqualTo(SweepBackoff.BASE.toSeconds());
        assertThat(sent.parameter(4)).isEqualTo("IllegalStateException");
        assertThat(sent.parameter(10)).as("the cap").isEqualTo(SweepBackoff.CAP.toSeconds());
        assertThat(sent.parameters().values()).as("no instance's clock: every bound is the database's")
                .noneMatch(value -> value instanceof Timestamp || value instanceof Instant);
        assertThat(sent.sql())
                .contains("ON CONFLICT (leg, row_id) DO UPDATE SET")
                .contains("f.failures + 1")
                .contains("LEAST(make_interval(secs => CAST(? AS double precision) * power(2, LEAST(f.failures, 20))),")
                .endsWith(" WHERE f.retry_at <= statement_timestamp()");
    }

    @Test
    @DisplayName("a store refuses a leg it does not list, so no failure is ever recorded under another listing's key")
    void aStoreRefusesAnotherListingsLeg() {
        Connection uow = new ScriptedListingConnection(NOW).connection();
        UUID row = versionFour();
        assertThatThrownBy(() -> new JdbcPaymentAttemptStore().recordSweepFailure(uow, SweepLeg.CARD_REFUND, row, "X"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcRefundStore().recordSweepFailure(uow, SweepLeg.WITHDRAWAL, row, "X"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the legs are the migration's CHECK vocabulary exactly")
    void theLegsAreTheMigrationsVocabulary() throws Exception {
        String migration = new String(
                SweepListingStatementsTest.class.getResourceAsStream(
                                "/db/migration/payments/V040__a_sweep_row_that_fails_is_counted_and_backed_off.sql")
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        String vocabulary = String.join(", ", Stream.of(SweepLeg.values()).map(leg -> "'" + leg.name() + "'").toList());
        assertThat(migration.replaceAll("\\s+", " ")).contains("CHECK (leg IN (" + vocabulary + "))");
    }
}
