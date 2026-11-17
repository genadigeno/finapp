package com.finapp.app.architecture;

import com.finapp.kyc.CounterpartyScreeningProvider;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.TransactionRunner;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deliberate violations of ADR-0046, one per way code can hold a connection around a provider call, used by
 * {@code NoTransactionSpansAProviderCallTest} (`X-TSK-023`).
 *
 * <p>Never wired and never run: the test reads this class file beside production's, and each method must be found reaching the
 * network while a connection is held. Each calls a real provider port, so the path the guard finds runs through production's own
 * adapters to {@code OutboundExchange.send}; a guard that found these by name would prove nothing.
 */
@SuppressWarnings("unused")
final class ProviderCallsHeldProbe {

    private final PaymentProvider provider;
    private final Supplier<Object> stored;

    ProviderCallsHeldProbe(PaymentProvider provider) {
        this.provider = provider;
        this.stored = () -> provider.query(null);
    }

    /** Inside Spring's template: the body is handed the transaction's status. */
    Object insideTheTemplate(TransactionTemplate transactions) {
        return transactions.execute(status -> provider.authorize(null));
    }

    /** Inside a module's own runner: the body is handed the connection. */
    Object insideTheModulesRunner(TransactionRunner transactions) {
        return transactions.inTransaction(connection -> provider.capture(null));
    }

    /** A lambda handed nothing, run by a method that is handed the connection. */
    Object handedToHeldCode() {
        return runsWhileHeld(null, () -> provider.refund(null));
    }

    private static Object runsWhileHeld(Connection connection, Supplier<Object> work) {
        return work.get();
    }

    /** A functional field, read and run by a method that is handed the connection. */
    Object throughAField(Connection connection) {
        return stored.get();
    }

    /** A row being read: its result set is open, and so is its connection. */
    void whileReadingARow(ResultSet row) {
        provider.voidAuthorization(null);
    }

    /** A connection whose auto-commit this method turns off itself. */
    void opensItsOwn(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            provider.queryClearing(null);
            connection.commit();
        }
    }

    /** Annotated: Spring's proxy would hold a transaction around the whole method. */
    @Transactional
    public Object annotated() {
        return provider.query(null);
    }

    /** Through a production port that production implements with a lambda (kyc's screening provider, wired in a bean method). */
    Object throughAPortALambdaImplements(TransactionTemplate transactions, CounterpartyScreeningProvider screening) {
        return transactions.execute(status -> screening.screen(null, null));
    }
}
