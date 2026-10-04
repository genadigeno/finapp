package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.accounts.VerifiedAccountHolder;
import com.finapp.app.checkout.CheckoutPaymentParticipants;
import com.finapp.app.payments.JdbcPaymentParticipants;
import com.finapp.app.transfers.JdbcTransferParticipants;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.party.JdbcPartyStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.payments.PaymentParticipants;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.PermitAllUntilPhase13;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferStatus;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every flow's wallet resolution, for a customer holding two currencies, against a live
 * PostgreSQL (`P9-TSK-004`, ADR-0076 §6).
 *
 * <p>Before this task every resolver took "the" wallet with {@code findFirst()}: for a product
 * opened in USD that later added GBP, a GBP flow resolved the USD wallet (the lower id) and was
 * refused — or, had the order ever flipped, silently debited the wrong one. Each flow's resolver
 * is exercised here through the production class that flow calls, three times, in both of the
 * customer's currencies:
 *
 * <ul>
 *   <li>transfer — {@link JdbcTransferParticipants} (both sides);
 *   <li>pay-in (the wallet top-up) and withdrawal — {@link JdbcPaymentParticipants#walletOwnedBy},
 *       with the amount's currency ({@code PaymentCreation}, {@code WithdrawalService});
 *   <li>wallet payment — {@link JdbcPaymentParticipants#payerWalletOwnedBy};
 *   <li>checkout — {@link CheckoutPaymentParticipants#payerWalletOwnedBy}, the payer side the
 *       checkout wiring delegates.
 * </ul>
 *
 * <p>And end to end, a GBP transfer between two two-currency customers moves the GBP wallets and
 * leaves the USD wallets untouched, while a GBP transfer to a USD-only recipient commits
 * {@code FAILED(CURRENCY_MISMATCH)} carrying the recipient's real wallet — no implicit
 * conversion ({@code INV-MON-04}).
 */
@Tag("database")
@DisplayName("a two-currency customer's every flow resolves its wallet by currency (P9-TSK-004)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class MultiCurrencyWalletFlowsDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final CustomerAccountStore<Connection> products = new JdbcCustomerAccountStore();
    private final LedgerAccountStore<Connection> ledgerAccounts = new JdbcLedgerAccountStore();
    private final AccountHolderVerification<Connection> holders =
            new VerifiedAccountHolder(new JdbcPartyStore());
    private final TransferParticipants<Connection> transfers =
            new JdbcTransferParticipants(new JdbcPartyStore(), products, ledgerAccounts);
    private final JdbcPaymentParticipants payments =
            new JdbcPaymentParticipants(
                    new JdbcPartyStore(), products, ledgerAccounts, new JdbcPaymentMethodStore());

    @Test
    @DisplayName("transfer, pay-in, withdrawal, wallet payment and checkout each resolve the"
            + " wallet in the asked currency, the same answer every time")
    void everyFlowResolvesByCurrency() throws Exception {
        Holder holder = holder(USD, GBP);
        Holder usdOnly = holder(USD);
        PaymentParticipants<Connection> checkout =
                new CheckoutPaymentParticipants(ledgerAccounts, payments, IDS.next(), GBP);

        try (Connection app = DatabaseRoles.application()) {
            for (int asked = 0; asked < 3; asked++) {
                for (CurrencyCode currency : List.of(USD, GBP)) {
                    UUID expected = holder.wallet(currency);
                    // Transfer: the owned source and the destination.
                    assertThat(
                                    accountOf(
                                            () ->
                                                    transfers
                                                            .sourceOwnedBy(
                                                                    app,
                                                                    holder.partyId,
                                                                    holder.productId,
                                                                    currency)
                                                            .map(s -> s.side().account())))
                            .isEqualTo(expected);
                    assertThat(
                                    accountOf(
                                            () ->
                                                    transfers
                                                            .destination(
                                                                    app, holder.productId, currency)
                                                            .map(TransferParticipants.Side::account)))
                            .isEqualTo(expected);
                    // Pay-in and withdrawal: the wallet in the amount's currency.
                    assertThat(
                                    accountOf(
                                            () ->
                                                    payments
                                                            .walletOwnedBy(
                                                                    app, holder.partyId, currency)
                                                            .map(PaymentParticipants.Wallet::account)))
                            .isEqualTo(expected);
                    // Wallet payment: the payer's own wallet in the offer's currency.
                    assertThat(
                                    accountOf(
                                            () ->
                                                    payments
                                                            .payerWalletOwnedBy(
                                                                    app, holder.partyId, currency)
                                                            .map(PaymentParticipants.Wallet::account)))
                            .isEqualTo(expected);
                    // Checkout: the payer side the checkout wiring delegates.
                    assertThat(
                                    accountOf(
                                            () ->
                                                    checkout
                                                            .payerWalletOwnedBy(
                                                                    app, holder.partyId, currency)
                                                            .map(PaymentParticipants.Wallet::account)))
                            .isEqualTo(expected);
                }
            }

            // A currency the product does not hold answers its FIRST-OPENED wallet, in that
            // wallet's own currency, for every caller's currency judgement to refuse.
            PaymentParticipants.Wallet fallback =
                    payments.walletOwnedBy(app, holder.partyId, EUR).orElseThrow();
            assertThat(fallback.account().value()).isEqualTo(holder.wallet(USD));
            assertThat(fallback.currency()).isEqualTo(USD);
            TransferParticipants.Side recipient =
                    transfers.destination(app, usdOnly.productId, GBP).orElseThrow();
            assertThat(recipient.account().value()).isEqualTo(usdOnly.wallet(USD));
            assertThat(recipient.currency()).isEqualTo(USD);
            assertThat(transfers.destinationExists(app, usdOnly.productId)).isTrue();
            assertThat(transfers.destinationExists(app, IDS.next())).isFalse();
        }
    }

    @Test
    @DisplayName("a GBP transfer between two two-currency customers moves the GBP wallets and"
            + " leaves the USD wallets untouched")
    void aGbpTransferMovesTheGbpWallets() throws Exception {
        Holder source = holder(USD, GBP);
        Holder destination = holder(USD, GBP);
        fund(source, GBP, 10_00);
        fund(source, USD, 7_00);

        TransferResult result =
                asInstance(
                        source,
                        app -> {
                            TransferResult done =
                                    execution()
                                            .execute(
                                                    app,
                                                    command(source, destination, gbp(4_00)));
                            app.commit();
                            return done;
                        });
        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(settledOf(app, source.wallet(GBP))).isEqualTo(6_00);
            assertThat(settledOf(app, destination.wallet(GBP))).isEqualTo(4_00);
            assertThat(settledOf(app, source.wallet(USD))).isEqualTo(7_00);
            assertThat(settledOf(app, destination.wallet(USD))).isZero();
        }
    }

    @Test
    @DisplayName("a GBP transfer to a USD-only recipient commits FAILED(CURRENCY_MISMATCH)"
            + " carrying the recipient's real wallet, and nothing moves (INV-MON-04)")
    void aRecipientWithoutTheCurrencyIsAMismatch() throws Exception {
        Holder source = holder(USD, GBP);
        Holder usdOnly = holder(USD);
        fund(source, GBP, 10_00);

        TransferResult result =
                asInstance(
                        source,
                        app -> {
                            TransferResult done =
                                    execution().execute(app, command(source, usdOnly, gbp(4_00)));
                            app.commit();
                            return done;
                        });
        assertThat(result.status()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.failureReason()).contains(FailureReason.CURRENCY_MISMATCH);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(accountsOfTransfer(app, result.transferId().value()))
                    .containsExactly(source.wallet(GBP), usdOnly.wallet(USD));
            assertThat(settledOf(app, source.wallet(GBP))).isEqualTo(10_00);
            assertThat(settledOf(app, usdOnly.wallet(USD))).isZero();
        }
    }

    // ------------------------------------------------------------------
    // Wiring
    // ------------------------------------------------------------------

    private static IdempotentExecutor executor() {
        return new IdempotentExecutor(
                new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1),
                Duration.ofMinutes(5));
    }

    private static PostingService postingService() {
        return new PostingService(
                executor(),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    private TransferExecution execution() {
        return new TransferExecution(
                executor(),
                transfers,
                ledgerAccounts,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                postingService(),
                new JdbcTransferStore(),
                new PermitAllUntilPhase13<>(),
                new PermitAllUntilPhase13<>(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    private static TransferCommand command(Holder source, Holder destination, Money amount) {
        return new TransferCommand(
                "transfer-" + IDS.next(),
                source.partyId,
                source.productId,
                destination.productId,
                amount,
                "rent");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static final class Holder {
        final UUID partyId;
        final UUID customerId;
        final UUID productId;
        final java.util.Map<CurrencyCode, UUID> wallets = new java.util.HashMap<>();
        final Actor actor = new Actor(IDS.next().toString(), ActorType.CUSTOMER);

        Holder(UUID partyId, UUID customerId, UUID productId) {
            this.partyId = partyId;
            this.customerId = customerId;
            this.productId = productId;
        }

        UUID wallet(CurrencyCode currency) {
            return java.util.Objects.requireNonNull(wallets.get(currency), currency.code());
        }
    }

    /** A customer whose wallet agreement opened in {@code first}, then added the rest. */
    private Holder holder(CurrencyCode first, CurrencyCode... added) throws Exception {
        UUID party = IDS.next();
        UUID customer = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Two Currencies', now() - interval '2 hour')",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    customer,
                    party);
        }
        Holder holder;
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope =
                        SecurityContext.enter(new Actor(IDS.next().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            AccountOpening opening = opening();
            UUID product =
                    opening.open(app, party, ProductType.WALLET, first).account().id().value();
            app.commit();
            for (CurrencyCode currency : added) {
                opening.addCurrency(app, party, CustomerAccountId.of(product), currency)
                        .orElseThrow();
                app.commit();
            }
            holder = new Holder(party, customer, product);
            for (CurrencyCode currency : concat(first, added)) {
                holder.wallets.put(
                        currency,
                        ledgerAccounts
                                .findOwned(app, product, AccountPurpose.CUSTOMER_WALLET, currency)
                                .orElseThrow()
                                .id()
                                .value());
            }
        }
        return holder;
    }

    private AccountOpening opening() {
        return new AccountOpening(
                products,
                ledgerAccounts,
                holders,
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                IDS,
                CLOCK);
    }

    /** Credits {@code holder}'s wallet in {@code currency} from that currency's clearing. */
    private void fund(Holder holder, CurrencyCode currency, long minorUnits) throws Exception {
        asInstance(
                holder,
                app -> {
                    LedgerAccountId clearing =
                            ledgerAccounts
                                    .findOperational(
                                            app, AccountPurpose.SETTLEMENT_CLEARING, currency)
                                    .orElseThrow()
                                    .id();
                    LocalDate today = LocalDate.now(CLOCK);
                    Money amount = Money.ofMinorUnits(minorUnits, currency);
                    postingService()
                            .post(
                                    app,
                                    new PostingCommand(
                                            "fund-" + IDS.next(),
                                            today,
                                            today,
                                            "funding",
                                            List.of(
                                                    new JournalLine(
                                                            clearing, Direction.DEBIT, amount),
                                                    new JournalLine(
                                                            LedgerAccountId.of(
                                                                    holder.wallet(currency)),
                                                            Direction.CREDIT,
                                                            amount))));
                    app.commit();
                    return null;
                });
    }

    private static List<CurrencyCode> concat(CurrencyCode first, CurrencyCode... rest) {
        List<CurrencyCode> all = new java.util.ArrayList<>(List.of(first));
        all.addAll(List.of(rest));
        return all;
    }

    private static UUID accountOf(Supplier<Optional<LedgerAccountId>> resolution) {
        return resolution.get().orElseThrow().value();
    }

    private interface InTransaction<R> {
        R run(Connection app) throws Exception;
    }

    private <R> R asInstance(Holder caller, InTransaction<R> work) throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope scope = SecurityContext.enter(caller.actor);
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            return work.run(app);
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
    }

    private static Money gbp(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, GBP);
    }

    private static void execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    /** The transfer row's two accounts, source first — the committed refusal's evidence. */
    private static List<UUID> accountsOfTransfer(Connection connection, UUID transfer)
            throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT source_account_id, destination_account_id"
                                + " FROM transfers.transfer WHERE id = ?")) {
            read.setObject(1, transfer);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return List.of(row.getObject(1, UUID.class), row.getObject(2, UUID.class));
            }
        }
    }

    private static long settledOf(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement read =
                connection.prepareStatement(
                        "SELECT COALESCE(SUM(CASE WHEN direction = 'CREDIT' THEN amount_minor"
                                + " ELSE -amount_minor END), 0)"
                                + " FROM ledger.journal_line WHERE ledger_account_id = ?")) {
            read.setObject(1, accountId);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
