package com.finapp.accounting.reporting;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * The view of a report snapshot a definition is handed (`P14-TSK-008`'s review, I-1 and I-2; {@code INV-ACC-14}): a
 * {@link Connection} that can <strong>only prepare reads</strong> and that <strong>spends one time budget</strong>.
 *
 * <p><strong>Prepared reads only.</strong> {@code prepareStatement} admits one statement - no {@code ;} anywhere, so no
 * second statement can end the read-only transaction and open another - beginning with {@code SELECT} or {@code WITH}, and
 * naming none of the functions that act outside a read ({@code set_config} - the bound is this view's alone -
 * {@code pg_advisory*}, {@code pg_notify}, {@code pg_sleep}, {@code dblink}, {@code lo_*}). Its statements execute only by
 * {@code executeQuery()}; every mutator of the connection ({@code setAutoCommit}, {@code setReadOnly},
 * {@code setTransactionIsolation}, {@code commit}, {@code rollback}, {@code setSavepoint}, {@code close}, ...), every
 * plain or callable statement, every write a statement or a result set offers ({@code executeUpdate}, {@code addBatch},
 * {@code updateRow}, ...) and every way back to the raw objects ({@code unwrap}, {@code getStatement} answers this view's
 * own) is refused with {@link Refused}. What a single {@code SELECT} could still try - a data-modifying {@code WITH}, a
 * {@code FOR UPDATE}, {@code nextval} - the database's {@code READ ONLY} transaction refuses ({@code 25006}): the two are
 * the belt and the braces.
 *
 * <p><strong>One budget.</strong> Before each statement the remaining budget is armed as the transaction's
 * {@code statement_timeout} ({@code set_config(..., true)} - {@code SET LOCAL}, gone with the rollback); a budget already
 * spent refuses the statement, and a statement PostgreSQL cancels ({@code 57014}) is
 * {@link ReportGeneration.GenerationTimedOut}. So a whole generation - however many statements it makes - ends within the
 * budget, which the configuration holds below the generator's lease: a live generator is never overtaken by its own
 * reclaim. The view remembers a timeout ({@link #timedOut}) so a definition that catches it cannot turn it into an answer.
 *
 * <p>Report code: it reads and writes nothing itself beyond the bound's setting; {@code ReportingNeverWritesTest}
 * guards it like every class of this package.
 */
public final class ReadOnlySnapshot {

    /** The bound, armed before each statement - local to the transaction. */
    static final String BOUND = "SELECT set_config('statement_timeout', ?, true)";

    /** PostgreSQL's {@code query_canceled} - a statement cancelled by {@code statement_timeout}. */
    static final String QUERY_CANCELED = "57014";

    /** A single statement that reads: {@code SELECT} or {@code WITH} first. */
    private static final Pattern READ = Pattern.compile("(?is)\\s*(select|with)\\b.*");

    /** The functions a report's statement may not name - each acts outside a read, or on the bound. */
    static final List<String> REFUSED_FUNCTIONS = List.of("set_config", "pg_advisory", "pg_notify", "pg_sleep", "dblink",
            "lo_");

    /** What a connection of this view answers itself or forwards - everything else is refused. */
    private static final Set<String> CONNECTION_READS = Set.of("createArrayOf", "isReadOnly", "getAutoCommit",
            "getTransactionIsolation", "isClosed", "isValid", "getMetaData", "getSchema", "getCatalog", "getWarnings",
            "getHoldability");

    /** What a statement of this view forwards besides its parameter setters and {@code executeQuery()}. */
    private static final Set<String> STATEMENT_READS = Set.of("clearParameters", "close", "isClosed", "getMetaData",
            "getParameterMetaData", "getWarnings", "clearWarnings", "getFetchSize", "setFetchSize", "getMaxRows",
            "setMaxRows");

    /** A result set's writes - refused. */
    private static final Set<String> RESULT_SET_WRITES = Set.of("insertRow", "deleteRow", "updateRow", "moveToInsertRow",
            "cancelRowUpdates", "refreshRow", "moveToCurrentRow");

    /** A refused act on the snapshot - nothing reached the database. */
    public static final class Refused extends IllegalStateException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Refused(String detail) {
            super(detail);
        }
    }

    private final Connection snapshot;
    private final long budgetNanos;
    private final LongSupplier ticker;
    private final long startedAt;
    private final Connection view;
    private volatile boolean timedOut;

    /**
     * @param snapshot the read-only {@code REPEATABLE READ} transaction's connection - never handed out
     * @param budget the whole generation's time, at least a millisecond
     * @param ticker a monotonic nanosecond clock ({@code System::nanoTime} in production)
     */
    public ReadOnlySnapshot(Connection snapshot, Duration budget, LongSupplier ticker) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(budget, "budget");
        if (budget.toMillis() < 1) {
            // PostgreSQL reads zero as "no timeout": a bound under a millisecond is no bound.
            throw new IllegalArgumentException("a report snapshot's budget is at least one millisecond");
        }
        this.budgetNanos = budget.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.startedAt = ticker.getAsLong();
        this.view = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, new ConnectionView());
    }

    /** The restricted connection - the only one a definition sees. */
    public Connection connection() {
        return view;
    }

    /** Whether a statement was refused for the budget or cancelled by the bound - the generation timed out. */
    public boolean timedOut() {
        return timedOut;
    }

    /**
     * {@code sql}, if it is one reading statement naming no refused function.
     *
     * @throws Refused otherwise
     */
    static String screened(String sql) {
        if (sql == null || sql.indexOf(';') >= 0 || !READ.matcher(sql).matches()) {
            throw new Refused("a report snapshot prepares one SELECT or WITH statement - no other, and never two");
        }
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String function : REFUSED_FUNCTIONS) {
            if (lower.contains(function)) {
                throw new Refused("a report snapshot's statement may not call " + function);
            }
        }
        return sql;
    }

    // ------------------------------------------------------------------ the three views

    private final class ConnectionView implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            switch (name) {
                case "prepareStatement" -> {
                    if (args.length == 1) {
                        return statement(snapshot.prepareStatement(screened((String) args[0])));
                    }
                    if (args.length == 3 && (Integer) args[2] == ResultSet.CONCUR_READ_ONLY) {
                        return statement(snapshot.prepareStatement(screened((String) args[0]), (Integer) args[1],
                                ResultSet.CONCUR_READ_ONLY));
                    }
                    throw new Refused("a report snapshot prepares read-only statements only");
                }
                case "isWrapperFor" -> {
                    return false;
                }
                case "toString" -> {
                    return "a read-only report snapshot";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> {
                    if (CONNECTION_READS.contains(name)) {
                        return forward(snapshot, method, args);
                    }
                    throw new Refused("a report snapshot refuses Connection." + name);
                }
            }
        }
    }

    private PreparedStatement statement(PreparedStatement raw) {
        PreparedStatement[] self = new PreparedStatement[1];
        self[0] = (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("executeQuery") && (args == null || args.length == 0)) {
                        return resultSet(bounded(raw), self[0]);
                    }
                    if (name.equals("getConnection")) {
                        return view;
                    }
                    if (name.equals("isWrapperFor")) {
                        return false;
                    }
                    if (name.equals("toString")) {
                        return "a read-only report statement";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    boolean setter = name.startsWith("set") && !name.equals("setQueryTimeout")
                            && !name.equals("setCursorName") && !name.equals("setEscapeProcessing")
                            && !name.equals("setPoolable") && !name.equals("setLargeMaxRows");
                    if (setter || STATEMENT_READS.contains(name)) {
                        return forward(raw, method, args);
                    }
                    throw new Refused("a report snapshot's statement refuses PreparedStatement." + name);
                });
        return self[0];
    }

    private ResultSet resultSet(ResultSet raw, PreparedStatement owner) {
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("getStatement")) {
                        return owner;
                    }
                    if (name.equals("isWrapperFor")) {
                        return false;
                    }
                    if (name.equals("unwrap") || name.startsWith("update") || RESULT_SET_WRITES.contains(name)) {
                        throw new Refused("a report snapshot's result refuses ResultSet." + name);
                    }
                    if (name.equals("toString")) {
                        return "a read-only report result";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    return forward(raw, method, args);
                });
    }

    /** The statement run inside what is left of the budget - the bound armed first, a cancellation the timeout. */
    private ResultSet bounded(PreparedStatement raw) throws SQLException {
        long remaining = budgetNanos - (ticker.getAsLong() - startedAt);
        if (remaining <= 0) {
            timedOut = true;
            throw new ReportGeneration.GenerationTimedOut();
        }
        try (PreparedStatement bound = snapshot.prepareStatement(BOUND)) {
            bound.setString(1, Math.max(1L, Duration.ofNanos(remaining).toMillis()) + "ms");
            try (ResultSet armed = bound.executeQuery()) {
                if (!armed.next()) {
                    throw new Refused("a report snapshot's bound could not be armed");
                }
            }
        }
        try {
            return raw.executeQuery();
        } catch (SQLException cancelled) {
            if (QUERY_CANCELED.equals(cancelled.getSQLState())) {
                timedOut = true;
                throw new ReportGeneration.GenerationTimedOut();
            }
            throw cancelled;
        }
    }

    private static Object forward(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }
}
