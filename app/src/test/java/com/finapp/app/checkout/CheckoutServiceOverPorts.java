package com.finapp.app.checkout;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code CheckoutCreateIsOneTransactionTest}'s service (`P16-TSK-016`): the real {@link CheckoutService} over mocked ports, its
 * transactions the test's manager, and a data source that hands out a fresh mocked connection per borrow and remembers each. A
 * top-level helper rather than a part of the suite, for {@code HeldConnectionSource}'s reason: {@code TestTier}'s database signature
 * reads {@code javax.sql.DataSource} in a test class's tree as "obtains a database", which is false here - nothing connects.
 */
final class CheckoutServiceOverPorts {

    /** Every connection the data source handed out, in order. */
    final List<Connection> handedOut = new ArrayList<>();

    @SuppressWarnings("unchecked") // Mockito's mocks of the generic ports are raw.
    CheckoutService service(CheckoutSessions sessions, PlatformTransactionManager transactions, IdGenerator ids, Clock clock)
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(ignored -> {
            Connection connection = mock(Connection.class);
            handedOut.add(connection);
            return connection;
        });
        return new CheckoutService(sessions, mock(com.finapp.app.telemetry.CheckoutMeters.class),
                mock(com.finapp.merchant.MerchantSettlement.class), mock(com.finapp.app.payments.PaymentService.class),
                mock(com.finapp.payments.PaymentParticipants.class), mock(com.finapp.ledger.LedgerAccountStore.class),
                mock(com.finapp.identity.IdentityStore.class), mock(com.finapp.platform.idempotency.IdempotentExecutor.class),
                mock(com.finapp.platform.audit.AuditWriter.class), mock(com.finapp.platform.outbox.OutboxWriter.class), ids, clock,
                new TransactionTemplate(transactions), dataSource, mock(com.finapp.identity.MfaEnrolmentStore.class),
                mock(CheckoutDoorStepUp.class));
    }
}
