package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.JdbcAccountingPeriodStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.UnderwritingCaseId;
import com.finapp.credit.UnderwritingCaseStore;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcRoleAssignmentStore;
import com.finapp.identity.RoleAssignmentStore;
import com.finapp.identity.RoleName;
import com.finapp.platform.deadletter.DeadLetterStore;
import com.finapp.platform.deadletter.JdbcDeadLetterStore;
import com.finapp.reconciliation.BreakInquiries;
import com.finapp.reconciliation.JdbcBreakInquiries;
import com.finapp.settlement.JdbcSettlementFileStore;
import com.finapp.settlement.SettlementFileCipher;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Each retrofitted list's resumed read, as its store sends it (`P17-TSK-042`; `API_CONVENTIONS.md` section 7). A keyset page
 * resumes strictly after the last row served <em>in the list's own order</em>: the keyset names exactly the columns the
 * {@code ORDER BY} names, in that order, and compares {@code <} where the order descends and {@code >} where it ascends. A keyset
 * that compared the other way would serve the rows already behind the cursor, or nothing; one that named a different tiebreak
 * would skip or repeat the rows tied at a page boundary.
 *
 * <p>The hermetic half: each store is driven over a connection that records what it is asked to prepare and answers no rows,
 * so a statement that drifts fails here in seconds. That each statement binds and runs on PostgreSQL, and walks once, is
 * {@code OperatorListRetrofitApiDatabaseTest}'s.
 */
@DisplayName("the retrofitted lists' keysets resume in their own order (P17-TSK-042)")
class OperatorListKeysetsTest {

    private static final Instant AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final UUID ID = UUID.fromString("01937c2a-0000-7000-8000-000000000001");
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    /** A row comparison against bound parameters: {@code (a, b) < (?, ?)}. */
    private static final Pattern ROW_KEYSET = Pattern.compile("\\(([\\w., ]+)\\)\\s*([<>])\\s*\\((\\?(?:\\s*,\\s*\\?)*)\\)");
    /** A one-column keyset: {@code a < ?}, never a comparison with an expression. */
    private static final Pattern COLUMN_KEYSET = Pattern.compile("([\\w.]+)\\s*([<>])\\s*\\?(?![\\w(])");
    private static final Pattern ORDER = Pattern.compile("ORDER BY (.+?) LIMIT \\?");

    /** One list's resumed read, driven over a recording connection. */
    private record Resumed(String list, Consumer<Connection> read) {}

    private static final List<Resumed> RESUMED = List.of(
            new Resumed("reconciliation.breaks", connection -> new JdbcBreakInquiries().breaks(connection,
                    new BreakInquiries.BreakFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty()),
                    Optional.of(new BreakInquiries.BreakCursor(AT, ID)), 10)),
            new Resumed("settlement.files", connection -> store().newestFiles(connection,
                    Optional.of(new SettlementFileStore.NewestFirst(AT, ID)), 10)),
            new Resumed("settlement.refused-deliveries", connection -> store().newestRefusals(connection,
                    Optional.of(new SettlementFileStore.NewestFirst(AT, ID)), 10)),
            new Resumed("identity.factorless-holdings", connection -> new JdbcRoleAssignmentStore(IDS).holdingsWithoutFactor(connection,
                    Set.of(RoleName.ADMINISTRATOR), Optional.of(new RoleAssignmentStore.FactorlessHolding(IdentityId.of(ID),
                            RoleName.ADMINISTRATOR, AT)), 10)),
            new Resumed("credit.review-cases", connection -> new JdbcUnderwritingCaseStore().queue(connection, Optional.empty(),
                    Optional.of(new UnderwritingCaseStore.QueuePosition(AT, UnderwritingCaseId.of(ID))), 10)),
            new Resumed("accounting.periods", connection -> new JdbcAccountingPeriodStore().recentBefore(connection,
                    YearMonth.of(2026, 11), 10)),
            new Resumed("deadletter.abandoned", connection -> new JdbcDeadLetterStore().abandoned(connection,
                    Optional.of(new DeadLetterStore.Position(AT, ID)), 10)),
            new Resumed("deadletter.parked", connection -> new JdbcDeadLetterStore().parked(connection,
                    Optional.of(new DeadLetterStore.Position(AT, ID)), 10)),
            new Resumed("deadletter.discards", connection -> new JdbcDeadLetterStore().standingProposals(connection,
                    Optional.of(new DeadLetterStore.Position(AT, ID)), 10)));

    @Test
    @DisplayName("every resumed read compares in its order's direction over exactly its order's columns: < where the order"
            + " descends, > where it ascends, the tiebreak the order's own")
    void everyKeysetIsItsOrder() {
        // Every list judged before the answer, so one run names each list that disagrees.
        List<String> disagreeing = new ArrayList<>();
        for (Resumed resumed : RESUMED) {
            List<String> prepared = new ArrayList<>();
            resumed.read().accept(recording(prepared));
            List<String> keyed = prepared.stream().filter(sql -> ORDER.matcher(sql).find() && hasKeyset(sql)).toList();
            if (keyed.size() != 1) {
                disagreeing.add(resumed.list() + " prepares no one resumed, ordered statement: " + prepared);
            } else if (!agrees(keyed.get(0))) {
                disagreeing.add(resumed.list() + ": " + keyed.get(0));
            }
        }
        assertThat(disagreeing).as("each keyset is its order's columns, in order, compared < where the order descends and >"
                + " where it ascends").isEmpty();
    }

    @Test
    @DisplayName("the guard is not vacuous: a keyset against its order, or naming another tiebreak, is caught")
    void theGuardSeesAWrongKeyset() {
        assertThat(agrees("SELECT * FROM t WHERE (a, id) > (?, ?) ORDER BY a DESC, id DESC LIMIT ?")).isFalse();
        assertThat(agrees("SELECT * FROM t WHERE (a, id) < (?, ?) ORDER BY a DESC, id ASC LIMIT ?")).isFalse();
        assertThat(agrees("SELECT * FROM t WHERE (a, other) < (?, ?) ORDER BY a DESC, id DESC LIMIT ?")).isFalse();
        assertThat(agrees("SELECT * FROM t WHERE a < ? ORDER BY a DESC LIMIT ?")).isTrue();
        assertThat(agrees("SELECT * FROM t WHERE (a, id) > (?, ?) ORDER BY a, id LIMIT ?")).isTrue();
        assertThat(agrees("SELECT * FROM t WHERE (a, id) > (?) ORDER BY a, id LIMIT ?")).as("a parameter short").isFalse();
        assertThat(agrees("SELECT * FROM t WHERE a <= ? ORDER BY a DESC LIMIT ?")).as("not strictly after").isFalse();
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private record Keyset(List<String> columns, String operator) {}

    private static SettlementFileStore<Connection> store() {
        return new JdbcSettlementFileStore(new SettlementFileCipher(new byte[32], 1, new SecureRandom()));
    }

    private static boolean hasKeyset(String statement) {
        return ROW_KEYSET.matcher(statement).find() || COLUMN_KEYSET.matcher(statement).find();
    }

    /** The statement's one keyset, or null where it has none, more than one, or a parameter count not its columns'. */
    private static Keyset keyset(String statement) {
        Matcher row = ROW_KEYSET.matcher(statement);
        if (row.find()) {
            List<String> columns = Arrays.stream(row.group(1).split(",")).map(String::trim).toList();
            Keyset found = new Keyset(columns, row.group(2));
            return row.group(3).split(",").length == columns.size() && !row.find() ? found : null;
        }
        Matcher column = COLUMN_KEYSET.matcher(statement);
        if (!column.find()) {
            return null;
        }
        Keyset found = new Keyset(List.of(column.group(1)), column.group(2));
        return column.find() ? null : found;
    }

    /**
     * The rule: one keyset, naming exactly the order's columns in order, compared {@code <} where the order descends and
     * {@code >} where it ascends, the order one direction throughout.
     */
    private static boolean agrees(String statement) {
        Keyset keyset = keyset(statement);
        Matcher ordered = ORDER.matcher(statement);
        if (keyset == null || !ordered.find()) {
            return false;
        }
        List<String> order = new ArrayList<>();
        List<Boolean> descending = new ArrayList<>();
        for (String term : ordered.group(1).split(",")) {
            String[] words = term.trim().split("\\s+");
            order.add(words[0]);
            descending.add(words.length > 1 && words[1].equalsIgnoreCase("DESC"));
        }
        return descending.stream().allMatch(descending.get(0)::equals) && keyset.columns().equals(order)
                && keyset.operator().equals(descending.get(0) ? "<" : ">");
    }

    /** A connection that records each statement it is asked to prepare, binds nothing, and answers no rows. */
    private static Connection recording(List<String> prepared) {
        ResultSet empty = proxy(ResultSet.class, (method, args) -> null);
        PreparedStatement statement = proxy(PreparedStatement.class,
                (method, args) -> method.equals("executeQuery") ? empty : null);
        return proxy(Connection.class, (method, args) -> {
            if (method.equals("prepareStatement")) {
                prepared.add((String) args[0]);
                return statement;
            }
            return null;
        });
    }

    private interface Answer {
        Object answer(String method, Object[] args);
    }

    /** A proxy answering {@code answer}'s value, or the return type's default where it gives none for a primitive. */
    private static <T> T proxy(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(OperatorListKeysetsTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> {
                    Object answered = answer.answer(method.getName(), args);
                    if (answered != null || !method.getReturnType().isPrimitive()) {
                        return answered;
                    }
                    Class<?> returned = method.getReturnType();
                    if (returned == boolean.class) {
                        return false;
                    }
                    if (returned == void.class) {
                        return null;
                    }
                    if (returned == long.class) {
                        return 0L;
                    }
                    if (returned == double.class) {
                        return 0.0d;
                    }
                    if (returned == float.class) {
                        return 0.0f;
                    }
                    if (returned == short.class) {
                        return (short) 0;
                    }
                    if (returned == byte.class) {
                        return (byte) 0;
                    }
                    if (returned == char.class) {
                        return (char) 0;
                    }
                    return 0;
                }));
    }
}
