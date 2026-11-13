package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ledger {@code V036} and {@code V037} - one journal entry per posting scope, for ever, landed only over a census that found no
 * duplicate (`P16-TSK-011`, ADR-0141; {@code INV-IDEM-01}, {@code INV-LED-04}, {@code INV-HIST-01}) - read from the migrations, and
 * the store's conditional insert, read through a connection that records what it is asked. What PostgreSQL does with them is
 * {@code PostingScopeUniquenessDatabaseTest}'s (written, owed to the database pass).
 */
@DisplayName("one journal entry per posting scope over a census - ledger V036 and V037, and the store's conditional insert (P16-TSK-011)")
class PostingScopeUniquenessMigrationTest {

    private static final String CENSUS = "db/migration/ledger/V036__the_posting_scope_census.sql";
    private static final String UNIQUE = "db/migration/ledger/V037__one_journal_entry_per_posting_scope.sql";

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("the census counts, and records nothing but counts: no scope, key, entry or amount is read into a row or a message")
    void theCensusRecordsCountsOnly() {
        String census = normalised(statements(CENSUS));
        assertThat(between(census, "CREATE TABLE ledger.posting_scope_census (", ");"))
                .isEqualTo("CREATE TABLE ledger.posting_scope_census ( taken_at timestamptz, entries_read bigint, scopes_read bigint,"
                        + " duplicated_scopes bigint, surplus_entries bigint );");
        String insert = census.substring(census.indexOf("INSERT INTO ledger.posting_scope_census"));
        assertThat(insert)
                .contains("count(*) FILTER (WHERE per_scope.entries > 1)")
                .contains("coalesce(sum(per_scope.entries - 1), 0)")
                .contains("GROUP BY entry.idempotency_scope) per_scope;")
                .contains("pg_catalog.statement_timestamp()");
        assertThat(insert.substring(0, insert.indexOf("FROM (SELECT")))
                .as("what the row takes: counts and the time, never a scope")
                .doesNotContain("idempotency_scope")
                .doesNotContain("amount");
        assertThat(census).doesNotContain("RAISE NOTICE").doesNotContain("DELETE FROM").doesNotContain("UPDATE ledger");
    }

    @Test
    @DisplayName("the census table has no constraint, so it always records what it found, and refuses every edit and every truncation")
    void theCensusAlwaysRecordsAndIsAppendOnly() {
        String census = normalised(statements(CENSUS));
        String table = between(census, "CREATE TABLE ledger.posting_scope_census (", ");");
        for (String constraint : List.of("NOT NULL", "CHECK", "UNIQUE", "PRIMARY KEY", "REFERENCES", "DEFAULT")) {
            assertThat(table).as("the census table carries no %s", constraint).doesNotContain(constraint);
        }
        assertThat(census)
                .contains("CREATE TRIGGER posting_scope_census_is_append_only BEFORE UPDATE OR DELETE ON ledger.posting_scope_census"
                        + " FOR EACH ROW EXECUTE FUNCTION ledger.posting_scope_census_is_append_only();")
                .contains("CREATE TRIGGER posting_scope_census_is_never_truncated BEFORE TRUNCATE ON ledger.posting_scope_census"
                        + " FOR EACH STATEMENT EXECUTE FUNCTION ledger.history_is_never_truncated();");
        assertThat(census.indexOf("CREATE TRIGGER posting_scope_census_is_append_only"))
                .as("refusing before the census writes").isLessThan(census.indexOf("INSERT INTO"));
        assertThat(census).doesNotContain("GRANT");
    }

    @Test
    @DisplayName("V037 judges the census first, failing closed on any count but zero or an unknown one, then builds the unique index")
    void theUniquenessStandsOnTheCensus() {
        String unique = normalised(statements(UNIQUE));
        String check = "ALTER TABLE ledger.posting_scope_census ADD CONSTRAINT posting_scope_census_found_no_duplicate CHECK"
                + " (coalesce(duplicated_scopes, -1) = 0 AND coalesce(surplus_entries, -1) = 0 AND entries_read IS NOT NULL);";
        String index = "CREATE UNIQUE INDEX IF NOT EXISTS journal_entry_one_per_idempotency_scope ON ledger.journal_entry"
                + " (idempotency_scope);";
        assertThat(unique).contains(check).contains(index);
        assertThat(unique.indexOf(check)).as("the census is judged before the index is built").isLessThan(unique.indexOf(index));
        for (String forbidden : List.of("DEFERRABLE", "CONCURRENTLY", "DELETE", "UPDATE ", "DROP", "WHERE", "INCLUDE")) {
            assertThat(unique).as("V037 holds no %s", forbidden).doesNotContain(forbidden);
        }
    }

    @Test
    @DisplayName("V037's contract marker names V036 as the data fix it stands on, and waives exactly the unique index and the CHECK")
    void theMarkerNamesTheCensus() {
        String header = raw(UNIQUE).lines().takeWhile(line -> line.startsWith("--")).collect(Collectors.joining("\n"));
        Matcher marker = Pattern.compile("(?m)^-- finapp-contract: first-conforming-release=ANY; waives=([A-Z_,]+); data-fix=V(\\d+);"
                + " reason=\\S.*$").matcher(header);
        assertThat(marker.find()).isTrue();
        assertThat(marker.group(1)).isEqualTo("UNIQUE,CHECK");
        assertThat(marker.group(2)).isEqualTo("036");
    }

    @Test
    @DisplayName("the store's entry insert is conditional on the scope: an entry the index refuses writes no line and is PostingScopeTaken")
    void aTakenScopeWritesNothingAndSaysSo() {
        List<String> asked = new ArrayList<>();
        JournalEntry entry = JournalEntry.balanced(IDS, CLOCK, LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 13), List.of(
                new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, CurrencyCode.of("USD"))),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, CurrencyCode.of("USD")))));
        String scope = PostingService.IDEMPOTENCY_SCOPE + ":a-client-key-" + IDS.next();

        assertThatThrownBy(() -> new JdbcJournalEntryStore(IDS).append(connection(asked, 0), entry, attribution(scope)))
                .isInstanceOf(PostingScopeTakenException.class)
                .hasMessageContaining(entry.id().toString())
                .hasMessageNotContaining(scope)
                .hasMessageNotContaining("a-client-key");
        assertThat(asked).hasSize(1);
        assertThat(asked.getFirst()).startsWith("INSERT INTO ledger.journal_entry").endsWith(" ON CONFLICT (idempotency_scope) DO NOTHING");

        asked.clear();
        new JdbcJournalEntryStore(IDS).append(connection(asked, 1), entry, attribution(scope));
        assertThat(asked).as("an entry inserted goes on to its lines").hasSize(2);
        assertThat(asked.get(1)).startsWith("INSERT INTO ledger.journal_line");
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static PostingAttribution attribution(String scope) {
        return new PostingAttribution(JournalEntryType.POSTING, "a posting", Optional.empty(), Optional.empty(), "system",
                Correlation.startingWith(CorrelationId.generate(IDS)).causing(CausationId.of("cause")), scope);
    }

    /** A connection that records each statement it prepares; the entry's insert answers {@code entryRows}. */
    private static Connection connection(List<String> asked, int entryRows) {
        return (Connection) Proxy.newProxyInstance(PostingScopeUniquenessMigrationTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("prepareStatement")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    String sql = (String) args[0];
                    asked.add(sql);
                    return Proxy.newProxyInstance(PostingScopeUniquenessMigrationTest.class.getClassLoader(),
                            new Class<?>[] {PreparedStatement.class}, (statement, call, values) -> switch (call.getName()) {
                                case "executeUpdate" -> entryRows;
                                case "executeBatch" -> new int[] {1, 1};
                                case "close" -> null;
                                default -> null; // the binds
                            });
                });
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as("'%s'", from).isNotNegative();
        int end = text.indexOf(to, start);
        assertThat(end).as("'%s' after '%s'", to, from).isNotNegative();
        return text.substring(start, end + to.length());
    }

    private static String normalised(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }

    private static String statements(String resource) {
        return raw(resource).lines().filter(line -> !line.stripLeading().startsWith("--")).collect(Collectors.joining("\n"));
    }

    private static String raw(String resource) {
        try (InputStream in = PostingScopeUniquenessMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("%s on the test classpath", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
