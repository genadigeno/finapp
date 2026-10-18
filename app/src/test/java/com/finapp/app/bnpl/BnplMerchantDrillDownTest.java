package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPayoutWorld.PAID;
import static com.finapp.app.bnpl.BnplPayoutWorld.PATH;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.MerchantTransactionReport;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.checkout.OrderStore;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantApiKeyId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.RefundStore;
import com.finapp.sharedkernel.money.Money;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The merchant leg as the merchant sees it (`P12-TSK-016`, {@code INV-MER-02} amended): every BNPL financing and every
 * BNPL refund named in the payable's drill-down and in the merchant's transactions, classified by the entry's
 * posting-key namespace BEFORE any counterparty rule (ADR-0073 §6's origin-rule precedent) - because F5's excess line
 * credits {@code CUSTOMER_WALLET}, already the book rail's sale counterparty - and the position equal to its terms by
 * construction. Hermetic, over {@link BnplPayoutWorld}'s journal and its readings of {@code JdbcPositionBreakdown}'s and
 * {@code JdbcStatementDerivation}'s rules; the real-schema run is {@code BnplMerchantPayoutDatabaseTest}'s.
 *
 * <p>The refund pair is empty in production until `P12-TSK-021` posts one: it is exercised here by the journal fixture
 * under a {@code bnpl.refund:} key in F5's shape, and re-asserted through real refunds by `-021` (carried).
 */
@DisplayName("the merchant payable drill-down and transactions with BNPL lines (P12-TSK-016)")
class BnplMerchantDrillDownTest {

    private BnplPayoutWorld world;

    @BeforeEach
    void world() {
        world = new BnplPayoutWorld();
    }

    @AfterEach
    void close() {
        world.close();
    }

    @Test
    @DisplayName("BNPL lines are classified by their namespace: a financing is bnplFinanced/bnplFees, a BNPL refund"
            + " whose excess credits the customer's wallet is bnplRefunded/bnplFeesReturned and never a wallet"
            + " capture's refund - while a real wallet refund beside it stays 'refunded'")
    void bnplLinesAreClassifiedByTheirNamespace() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.captured(shop, 100_00L, 3_00L);
        world.walletRefund(shop, 20_00L);
        world.financed(shop, 600_00L, 24_00L);
        world.refundedByBnpl(shop, 200_00L, 50_00L, 8_00L);

        MerchantPayable.Payable payable = world.payable(shop);

        assertThat(payable.captured().minorUnits()).isEqualTo(100_00L);
        assertThat(payable.fees().minorUnits()).isEqualTo(3_00L);
        assertThat(payable.refunded().minorUnits()).as("only the wallet capture's own refund").isEqualTo(20_00L);
        assertThat(payable.feesReturned().minorUnits()).isZero();
        assertThat(payable.bnplFinanced().minorUnits()).isEqualTo(600_00L);
        assertThat(payable.bnplFees().minorUnits()).as("the entry's own fee line").isEqualTo(24_00L);
        assertThat(payable.bnplRefunded().minorUnits()).isEqualTo(200_00L);
        assertThat(payable.bnplFeesReturned().minorUnits()).isEqualTo(8_00L);
        assertThat(payable.other().minorUnits()).as("nothing unexplained").isZero();
        assertThat(payable.position().minorUnits()).isEqualTo(100_00L - 3_00L - 20_00L + 600_00L - 24_00L - 200_00L + 8_00L);
        assertThat(payable.terms()).isEqualTo(payable.position());
    }

    @Test
    @DisplayName("the key decides, not the counterparty: a refund with no excess (it faces only the receivable) is a"
            + " BNPL refund too, and a line facing BNPL_PRINCIPAL under no BNPL key is 'other', never financed")
    void theKeyDecidesNotTheCounterparty() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.financed(shop, 300_00L, 12_00L);
        world.refundedByBnpl(shop, 100_00L, 0L, 4_00L);
        UUID obligation = world.ids.next();
        com.finapp.ledger.LedgerAccount principal = com.finapp.ledger.LedgerAccount.owned(world.ids, world.clock,
                com.finapp.ledger.AccountType.ASSET, AccountPurpose.BNPL_PRINCIPAL, BnplPayoutWorld.EUR, obligation);
        world.state.accounts.put(principal.id(), principal);
        world.post("operator-fixture:" + world.ids.next(), "not a bnpl posting", List.of(
                new JournalLine(principal.id(), Direction.DEBIT, eur(5_00L)),
                new JournalLine(shop.payable(), Direction.CREDIT, eur(5_00L))));

        MerchantPayable.Payable payable = world.payable(shop);

        assertThat(payable.bnplFinanced().minorUnits()).isEqualTo(300_00L);
        assertThat(payable.bnplFees().minorUnits()).isEqualTo(12_00L);
        assertThat(payable.bnplRefunded().minorUnits()).isEqualTo(100_00L);
        assertThat(payable.bnplFeesReturned().minorUnits()).isEqualTo(4_00L);
        assertThat(payable.other().minorUnits()).isEqualTo(5_00L);
        assertThat(payable.captured().minorUnits()).isZero();
        assertThat(payable.terms()).isEqualTo(payable.position());
    }

    @Test
    @DisplayName("the terms sum to the position with every kind of line: captures, a wallet refund, financings, BNPL"
            + " refunds and a payout of the financed money")
    void theTermsSumToThePosition() {
        BnplPayoutWorld.Shop shop = world.shop();
        world.captured(shop, 250_00L, 7_50L);
        world.walletRefund(shop, 50_00L);
        world.financed(shop, 999_99L, 40_00L);
        world.financed(shop, 12_34L, 0L);
        world.scheme.succeedsWith(PATH, 200, PAID);
        world.payOut(shop, 500_00L, "payout-" + world.ids.next());
        world.refundedByBnpl(shop, 333_33L, 111_11L, 13_33L);

        MerchantPayable.Payable payable = world.payable(shop);

        assertThat(payable.paidOut().minorUnits()).isEqualTo(500_00L);
        assertThat(payable.bnplFinanced().minorUnits()).isEqualTo(999_99L + 12_34L);
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(payable.position().minorUnits())
                .isEqualTo(250_00L - 7_50L - 50_00L + 999_99L + 12_34L - 40_00L - 500_00L - 333_33L + 13_33L);
    }

    @Test
    @DisplayName("GET /v1/merchant/transactions' report names BNPL_FINANCING (gross, fee, net, the purchase) and"
            + " BNPL_REFUND (the refund) - never REFUND for the wallet-facing BNPL refund - a payout stays OTHER, and"
            + " the nets reconcile to closing - opening")
    void bnplFinancingAndRefundKindsAreListed() {
        BnplPayoutWorld.Shop shop = world.shop();
        UUID purchase = world.financed(shop, 600_00L, 24_00L);
        UUID refund = world.refundedByBnpl(shop, 200_00L, 50_00L, 8_00L);
        world.scheme.succeedsWith(PATH, 200, PAID);
        world.payOut(shop, 100_00L, "payout-" + world.ids.next());
        LocalDate today = LocalDate.ofInstant(world.clock.instant(), ZoneOffset.UTC);

        List<MerchantTransactionReport.Section> sections = report().report(
                new AuthenticatedMerchant(shop.id(), MerchantApiKeyId.next(world.ids)), today, today);

        assertThat(sections).hasSize(1);
        List<MerchantTransactionReport.Movement> movements = sections.get(0).movements();
        assertThat(movements).extracting(MerchantTransactionReport.Movement::kind).containsExactly(
                MerchantTransactionReport.Kind.BNPL_FINANCING, MerchantTransactionReport.Kind.BNPL_REFUND,
                MerchantTransactionReport.Kind.OTHER);
        MerchantTransactionReport.Movement financing = movements.get(0);
        assertThat(financing.gross()).contains(eur(600_00L));
        assertThat(financing.fee()).contains(eur(24_00L));
        assertThat(financing.net()).isEqualTo(eur(576_00L));
        assertThat(financing.purchaseId()).contains(purchase);
        assertThat(financing.checkoutId()).isEmpty();
        assertThat(financing.refundId()).isEmpty();
        MerchantTransactionReport.Movement refunded = movements.get(1);
        assertThat(refunded.gross()).contains(eur(200_00L));
        assertThat(refunded.fee()).contains(eur(8_00L));
        assertThat(refunded.net()).isEqualTo(eur(-192_00L));
        assertThat(refunded.refundId()).contains(refund);
        assertThat(refunded.purchaseId()).isEmpty();
        assertThat(movements.get(2).net()).isEqualTo(eur(-100_00L));
        Money nets = movements.stream().map(MerchantTransactionReport.Movement::net).reduce(eur(0), Money::plus);
        assertThat(nets).isEqualTo(sections.get(0).closing().minus(sections.get(0).opening()));
    }

    /** The report over the world's statement reading; payments' and checkout's stores know none of these references. */
    private MerchantTransactionReport report() {
        return new MerchantTransactionReport(world.statements, absent(PaymentAttemptStore.class),
                absent(RefundStore.class), absent(CheckoutSessionStore.class), absent(OrderStore.class),
                new TransactionTemplate(new NoTransactions()), new OneConnection(world.connection));
    }

    /** A store whose every lookup finds nothing: a BNPL reference names no attempt, refund, session or order. */
    @SuppressWarnings("unchecked")
    private static <S> S absent(Class<?> store) {
        return (S) Proxy.newProxyInstance(store.getClassLoader(), new Class<?>[] {store}, (proxy, method, args) -> {
            if (method.getReturnType() == Optional.class) {
                return Optional.empty();
            }
            if (method.getName().equals("toString")) {
                return "absent " + store.getSimpleName();
            }
            throw new UnsupportedOperationException(store.getSimpleName() + "." + method.getName());
        });
    }

    private static Money eur(long minor) {
        return BnplPayoutWorld.eur(minor);
    }

    /** A transaction manager with nothing to manage: the world's stores roll back on their own. */
    private static final class NoTransactions extends AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    /** The world's one connection, handed to {@code DataSourceUtils}. */
    private record OneConnection(Connection connection) implements DataSource {

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException("unwrap");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
