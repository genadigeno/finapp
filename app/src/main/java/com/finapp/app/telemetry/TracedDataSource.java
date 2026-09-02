package com.finapp.app.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Records a span for the acquisition of a database connection, and nothing else.
 *
 * <h2>Why not a JDBC tracing library</h2>
 *
 * <p>Every off-the-shelf option records the <strong>SQL statement text</strong> as a span
 * attribute. On this platform's tables that means amounts, account identifiers and personal data
 * flowing into a telemetry backend that is not the database, has different retention and different
 * access control — a direct {@code INV-AUD-02} problem, and one that arrives silently the first
 * time somebody adds a query. Turning the statement capture off leaves a span that says only "an
 * SQL statement happened", which is worth less than a domain-named span recorded by the component
 * that knows what the operation meant.
 *
 * <p>The available libraries are also either alpha-versioned or third-party auto-configuration of
 * uncertain compatibility with this Spring Boot line. Neither belongs on the runtime classpath of
 * a financial platform for a signal this narrow.
 *
 * <h2>Why connection acquisition specifically</h2>
 *
 * <p>Because it is the one thing that can be measured usefully without looking at a statement, and
 * it is the failure this platform has already written down twice: {@code ADR-0016} answers
 * readiness <em>through the pool</em> precisely because a health check with its own connection
 * reports healthy while the pool is exhausted, and the connection-pool sizing debt notes that ten
 * instances at Hikari's default exhaust PostgreSQL's default {@code max_connections} before doing
 * any work. A span here turns "requests are slow" into "requests are waiting for a connection",
 * which are different incidents with different fixes.
 *
 * <p>Per-operation spans — {@code finapp.ledger.post}, {@code finapp.outbox.write} — belong to the
 * components that perform them, and arrive with the first component that does real work
 * (Phase 3). This is the seam they attach to, not a substitute for them.
 */
final class TracedDataSource implements DataSource {

    /** The span name. Stable: dashboards and latency alerts are written against it. */
    static final String SPAN_NAME = "finapp.db.connection";

    /** OpenTelemetry's conventional key for what kind of thing went wrong. */
    static final String ERROR_TYPE_TAG = "error.type";

    private final DataSource delegate;

    /**
     * Resolved per call, not at construction.
     *
     * <p>This wrapper is installed by a bean post-processor, which runs while the context is still
     * being built - early enough that the tracing beans may not exist yet. Taking the tracer then
     * makes the data source depend on the order two unrelated auto-configurations happen to
     * initialise in, and the first attempt at this failed exactly that way: every context in the
     * suite refused to start with "no qualifying bean of type Tracer". Resolving at the moment a
     * connection is actually asked for removes the ordering question rather than betting on it.
     */
    private final Supplier<Tracer> tracer;

    /**
     * Memoised after the first successful resolution.
     *
     * <p>Lazy resolution is required (see above) but a bean lookup on every connection acquisition
     * is not: this sits in front of every database connection the platform will ever make, and by
     * Phase 3 that is the hot path. Resolved once, then held.
     */
    private volatile Tracer resolved;

    TracedDataSource(DataSource delegate, Supplier<Tracer> tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    private Tracer tracer() {
        Tracer current = resolved;
        if (current == null) {
            // A benign race: two threads may both resolve, and both get the same singleton.
            current = tracer.get();
            resolved = current;
        }
        return current;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return traced(delegate::getConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        // The credentials are arguments here and are neither recorded nor logged. They do not
        // reach the span, which carries a name and nothing else.
        return traced(() -> delegate.getConnection(username, password));
    }

    private Connection traced(ConnectionSupplier supplier) throws SQLException {
        Tracer current = tracer();
        Span span = current.nextSpan().name(SPAN_NAME).start();
        // Not try-with-resources: the scope variable would be unreferenced, and this build treats
        // that warning as an error. Closing it explicitly says the same thing and reads no worse.
        Tracer.SpanInScope scope = current.withSpan(span);
        try {
            return supplier.get();
        } catch (SQLException | RuntimeException failure) {
            // The exception TYPE, as a tag - not the exception. A JDBC failure message names the
            // host, the database and sometimes the user, and a span goes to a backend with
            // different access control from the database itself.
            //
            // Not `span.error(new IllegalStateException(type))` either, which was the first
            // attempt: fabricating an exception to carry a string attaches a stack trace pointing
            // at this line rather than at anything that failed, which is worse than no stack trace
            // because it looks like one.
            span.tag(ERROR_TYPE_TAG, failure.getClass().getSimpleName());
            throw failure;
        } finally {
            scope.close();
            span.end();
        }
    }

    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    // --- Everything below delegates. A wrapper that answered any of these itself would be -----
    // --- lying about the pool it is standing in front of. --------------------------------------

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        // Spring's health indicator and pool metadata look through the wrapper for the concrete
        // pool. Answering honestly is what keeps readiness checking the real Hikari pool rather
        // than falling back to something more generic.
        return iface.isInstance(this) ? iface.cast(this) : delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
