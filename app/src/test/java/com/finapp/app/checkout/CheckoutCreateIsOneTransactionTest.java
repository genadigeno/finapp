package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The checkout's create cannot answer {@code 500} after its session committed (`P16-TSK-016`, routed from `P16-TSK-007`'s E30 and
 * dev4; {@code INV-IDEM-01}): the session is opened and read back in one transaction, so a failure before the commit rolls the session
 * back with its claim, and the retry under the key is a first attempt that mints a token.
 *
 * <p>Over the service's ports, the transaction manager counting what it was asked: the unit of work each step was handed and the
 * transactions begun, committed and rolled back. The open itself - the claim, the row, the audit record - is
 * {@code CheckoutSessionDatabaseTest}'s; the pool's 5 s bound reached in the read is E52's, on a cluster, owed under
 * {@code POSTPONED_ISSUES.md} PI-025.
 */
@DisplayName("the checkout's create is one transaction: opened and read back together, or not at all (P16-TSK-016)")
class CheckoutCreateIsOneTransactionTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private final Transactions transactions = new Transactions();
    private final CheckoutSessions sessions = mock(CheckoutSessions.class);
    private final CheckoutServiceOverPorts ports = new CheckoutServiceOverPorts();
    private final MerchantId merchant = MerchantId.of(IDS.next());
    private final AuthenticatedMerchant caller = new AuthenticatedMerchant(merchant, MerchantApiKeyId.of(IDS.next()), Set.of(),
            Optional.empty());

    private CheckoutService service() throws Exception {
        return ports.service(sessions, transactions, IDS, CLOCK);
    }

    private CheckoutSession open() {
        return CheckoutSession.open(IDS, Instant.now(CLOCK), merchant.value(), Money.ofMinorUnits(2_00L, CurrencyCode.of("EUR")),
                "The load harness order", UUID.randomUUID(), CheckoutSessionToken.issue(new SecureRandom()),
                Instant.now(CLOCK).plusSeconds(1800));
    }

    private static CreateSessionRequest body() {
        return new CreateSessionRequest(2_00L, "EUR", "The load harness order");
    }

    @Test
    @DisplayName("the open and the read share one unit of work and one transaction, committed once; the token is in the answer")
    void theOpenAndTheReadAreOneTransaction() throws Exception {
        CheckoutService service = service();
        CheckoutSession session = open();
        when(sessions.open(any(), any())).thenReturn(
                new CheckoutSessions.OpenedSession(session.id(), Optional.of(Sensitive.of("the-token")), false));
        when(sessions.ownedBy(any(), eq(merchant), eq(session.id()))).thenReturn(Optional.of(session));

        CheckoutService.CreatedSessionView created = service.create(caller, body(), "key-1");

        assertThat(transactions.begun).as("one transaction for the open and its read").isEqualTo(1);
        assertThat(transactions.committed).isEqualTo(1);
        assertThat(transactions.rolledBack).isZero();
        assertThat(ports.handedOut).as("one connection, handed to both steps").hasSize(1);
        org.mockito.Mockito.verify(sessions).open(eq(ports.handedOut.get(0)), any());
        org.mockito.Mockito.verify(sessions).ownedBy(ports.handedOut.get(0), merchant, session.id());
        assertThat(created.checkoutId()).isEqualTo(session.id().value().toString());
        assertThat(created.sessionToken()).isEqualTo("the-token");
        assertThat(created.alreadyCreated()).isFalse();
    }

    @Test
    @DisplayName("a read that fails rolls the open back with it: nothing committed, so the retry under the key is a first attempt")
    void aFailedReadRollsTheOpenBack() throws Exception {
        CheckoutService service = service();
        CheckoutSessionId id = CheckoutSessionId.of(IDS.next());
        when(sessions.open(any(), any())).thenReturn(new CheckoutSessions.OpenedSession(id, Optional.of(Sensitive.of("lost")), false));
        when(sessions.ownedBy(any(), any(), any())).thenThrow(new IllegalStateException("the pool refused a borrow"));

        assertThatThrownBy(() -> service.create(caller, body(), "key-2")).isInstanceOf(IllegalStateException.class);

        assertThat(transactions.begun).isEqualTo(1);
        assertThat(transactions.committed).as("the session never committed without its answer").isZero();
        assertThat(transactions.rolledBack).isEqualTo(1);
    }

    @Test
    @DisplayName("a replay is read in the same transaction and carries no token, as before")
    void aReplayCarriesNoToken() throws Exception {
        CheckoutService service = service();
        CheckoutSession session = open();
        when(sessions.open(any(), any())).thenReturn(new CheckoutSessions.OpenedSession(session.id(), Optional.empty(), true));
        when(sessions.ownedBy(any(), eq(merchant), eq(session.id()))).thenReturn(Optional.of(session));

        CheckoutService.CreatedSessionView replayed = service.create(caller, body(), "key-3");

        assertThat(transactions.begun).isEqualTo(1);
        assertThat(replayed.sessionToken()).isNull();
        assertThat(replayed.alreadyCreated()).isTrue();
    }

    /** A transaction manager that counts what it is asked, and holds nothing. */
    static final class Transactions implements PlatformTransactionManager {

        int begun;
        int committed;
        int rolledBack;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            rolledBack++;
        }
    }
}
