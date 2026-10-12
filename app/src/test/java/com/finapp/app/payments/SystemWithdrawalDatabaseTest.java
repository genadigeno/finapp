package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.payments.NoEligibleRailException;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.SystemWithdrawalRefusedException;
import com.finapp.payments.WithdrawalStatus;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Payments' system withdrawal entry (`P11-TSK-015`, payments {@code V032}; ADR-0097 point 5): a withdrawal on a recorded
 * instruction that ADOPTS the hold its instructor placed - over the REAL {@link SimulatedInstantSchemeAdapter} against the
 * stubbed scheme. The hold adopted, never duplicated, and released by payments' own outcome; the instruction the claim's
 * key, the dispatch key and the fingerprint's subject; a person's actor refused before anything is written; a hold of
 * another amount, a released hold or another hold under the same instruction refused; a person's key spelled like an
 * instruction never colliding with the platform's row; the routing refusal recorded under the instruction and replayed.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the wallet is funded by a fixture posting from
 * {@code CASH_AT_BANK} - never a path the platform has - so no shared proof should see it.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("payments' system withdrawal entry (P11-TSK-015)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class SystemWithdrawalDatabaseTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static SimulatedProvider provider;

    @Autowired private Withdrawals engine;
    @Autowired private HoldService holds;
    @Autowired private PostingService postings;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private IdGenerator ids;
    @Autowired private Clock clock;

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    @Test
    @DisplayName("a system withdrawal adopts the hold: COMPLETED with the instruction as its dispatch key and the"
            + " instructor's hold as its own - one hold on the wallet, released by payments' outcome; one entry DR wallet /"
            + " CR INSTANT_CLEARING; dispatched under the system actor; the instruction replayed without a second send;"
            + " another hold under it the key's conflict")
    void aSystemWithdrawalAdoptsTheHold() {
        Wallet wallet = fundedWallet(50_00);
        Hold hold = held(wallet, 30_00);
        String instruction = "lending:" + UUID.randomUUID();
        schemeAccepts();

        Withdrawals.Initiated initiated = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(30_00), hold.id()), resolver(wallet)));
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.COMPLETED);
        UUID id = initiated.id().value();
        assertThat(scalar("SELECT instruction_reference || '|' || dispatch_key || '|' || hold_reference::text"
                + " FROM payments.withdrawal WHERE id = ?", id))
                .isEqualTo(instruction + "|" + instruction + "|" + hold.id().value());
        assertThat(count("SELECT count(*) FROM ledger.hold WHERE ledger_account_id = ?", wallet.account().value()))
                .as("the hold adopted, never duplicated").isEqualTo(1);
        assertThat(scalar("SELECT status FROM ledger.hold WHERE id = ?", hold.id().value())).isEqualTo("RELEASED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:wallet-withdrawal:" + id)).isEqualTo(1);
        assertThat(scalar("SELECT actor_type FROM platform.audit_record WHERE operation = 'payments.WithdrawalDispatched'"
                + " AND target_id = ?", id.toString())).isEqualTo("SYSTEM");
        String reference = scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?", id);
        assertThat(sends(reference)).isEqualTo(1);

        Withdrawals.Initiated replayed = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(30_00), hold.id()), resolver(wallet)));
        assertThat(replayed.id().value()).isEqualTo(id);
        assertThat(replayed.replayed()).isTrue();
        assertThat(sends(reference)).as("a replay sends nothing").isEqualTo(1);

        Hold another = held(wallet, 10_00);
        assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(30_00), another.id()), resolver(wallet))))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", instruction))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a hold not adoptable is refused with nothing written: of another amount, released, or already adopted"
            + " by another instruction - the claim rolled back, the key unburned")
    void aHoldNotAdoptableIsRefused() {
        Wallet wallet = fundedWallet(60_00);
        schemeAccepts();
        Hold hold = held(wallet, 20_00);
        String instruction = "lending:" + UUID.randomUUID();
        assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(19_99), hold.id()), resolver(wallet))))
                .isInstanceOf(SystemWithdrawalRefusedException.class)
                .satisfies(refused -> assertThat(((SystemWithdrawalRefusedException) refused).reason())
                        .isEqualTo(SystemWithdrawalRefusedException.Reason.HOLD_NOT_ADOPTABLE));
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", instruction))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE scope = 'payments.withdrawal:lending'"
                + " AND idempotency_key = ?", instruction.substring("lending:".length())))
                .as("the key unburned").isZero();

        Withdrawals.Initiated first = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(20_00), hold.id()), resolver(wallet)));
        assertThat(first.status()).isEqualTo(WithdrawalStatus.COMPLETED);
        String other = "lending:" + UUID.randomUUID();
        assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(other, eur(20_00), hold.id()), resolver(wallet))))
                .as("the released (and adopted) hold is not adoptable again")
                .isInstanceOf(SystemWithdrawalRefusedException.class);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE hold_reference = ?", hold.id().value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a person cannot use the system entry: a customer's actor is refused before any transaction, nothing"
            + " written; a malformed instruction refused; a person's own key spelled like the instruction is the"
            + " person's own row, never the platform's")
    void aPersonCannotUseTheSystemEntry() {
        Wallet wallet = fundedWallet(40_00);
        schemeAccepts();
        Hold hold = held(wallet, 10_00);
        String instruction = "lending:" + UUID.randomUUID();
        Actor person = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        try (SecurityContext.Scope actor = SecurityContext.enter(person);
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            assertThatThrownBy(() -> engine.withdrawOnInstruction(
                    new Withdrawals.Instruction(instruction, eur(10_00), hold.id()), resolver(wallet)))
                    .isInstanceOf(SystemWithdrawalRefusedException.class)
                    .satisfies(refused -> assertThat(((SystemWithdrawalRefusedException) refused).reason())
                            .isEqualTo(SystemWithdrawalRefusedException.Reason.NOT_A_SYSTEM_ACTOR));
            // The person's own door, under a key spelled as the platform's instruction: a person's row of its own,
            // its own hold - the instruction stays free for the platform.
            Withdrawals.Initiated own = engine.withdraw(instruction, eur(5_00), resolver(wallet));
            assertThat(own.status()).isEqualTo(WithdrawalStatus.COMPLETED);
            assertThat(scalar("SELECT (instruction_reference IS NULL)::text || '|' || (hold_reference <> ?)::text"
                    + " FROM payments.withdrawal WHERE id = ?", hold.id().value(), own.id().value()))
                    .isEqualTo("true|true");
        }
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", instruction))
                .isZero();
        assertThatThrownBy(() -> new Withdrawals.Instruction("lending:not-a-uuid", eur(1_00), hold.id()))
                .isInstanceOf(SystemWithdrawalRefusedException.class);
        assertThatThrownBy(() -> new Withdrawals.Instruction(UUID.randomUUID() + ":" + UUID.randomUUID(), eur(1_00),
                hold.id())).as("a namespace is never a UUID - never a customer's scope")
                .isInstanceOf(SystemWithdrawalRefusedException.class);

        Withdrawals.Initiated platform = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(10_00), hold.id()), resolver(wallet)));
        assertThat(platform.status()).as("the platform's instruction, unharmed").isEqualTo(WithdrawalStatus.COMPLETED);
    }

    @Test
    @DisplayName("the routing refusal is recorded under the instruction and replayed: no withdrawal, the hold untouched -"
            + " the instructor's to release")
    void theRoutingRefusalIsRecordedUnderTheInstruction() throws Exception {
        Wallet wallet = fundedWallet(30_00);
        Hold hold = held(wallet, 30_00);
        String instruction = "lending:" + UUID.randomUUID();
        execute("INSERT INTO payments.rail_availability (rail, available, reason, changed_by, changed_at)"
                + " VALUES ('instant', false, 'suite: rail out', 'SystemWithdrawalDatabaseTest', now())"
                + " ON CONFLICT (rail) DO UPDATE SET available = false, reason = 'suite: rail out', changed_at = now()");
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                        new Withdrawals.Instruction(instruction, eur(30_00), hold.id()), resolver(wallet))))
                        .isInstanceOf(NoEligibleRailException.class);
            }
        } finally {
            execute("UPDATE payments.rail_availability SET available = true, reason = 'suite: rail back',"
                    + " changed_at = now() WHERE rail = 'instant'");
        }
        assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(30_00), hold.id()), resolver(wallet))))
                .as("the recorded refusal is the instruction's answer, even once the rail is back")
                .isInstanceOf(NoEligibleRailException.class);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", instruction))
                .isZero();
        assertThat(scalar("SELECT status FROM ledger.hold WHERE id = ?", hold.id().value())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the instructor's refusal is recorded under the instruction and replayed: a resolver answering"
            + " CUSTOMER_UNAVAILABLE refuses, and a later ask - whose resolver would serve it - is refused the same without"
            + " the resolver being consulted; no withdrawal is ever born under it")
    void theInstructorsRefusalIsRecordedAndReplayed() {
        Wallet wallet = fundedWallet(25_00);
        schemeAccepts();
        Hold hold = held(wallet, 25_00);
        String instruction = "lending:" + UUID.randomUUID();
        Withdrawals.Resolver unservable = unitOfWork -> {
            throw new SystemWithdrawalRefusedException(SystemWithdrawalRefusedException.Reason.CUSTOMER_UNAVAILABLE);
        };
        java.util.concurrent.atomic.AtomicInteger consulted = new java.util.concurrent.atomic.AtomicInteger();
        Withdrawals.Resolver servable = unitOfWork -> {
            consulted.incrementAndGet();
            return resolver(wallet).resolve(unitOfWork);
        };
        for (Withdrawals.Resolver asked : List.of(unservable, servable)) {
            assertThatThrownBy(() -> asSystem(() -> engine.withdrawOnInstruction(
                    new Withdrawals.Instruction(instruction, eur(25_00), hold.id()), asked)))
                    .isInstanceOf(SystemWithdrawalRefusedException.class)
                    .satisfies(refused -> assertThat(((SystemWithdrawalRefusedException) refused).reason())
                            .isEqualTo(SystemWithdrawalRefusedException.Reason.CUSTOMER_UNAVAILABLE));
        }
        assertThat(consulted).as("the recorded refusal answers before any resolver").hasValue(0);
        assertThat(count("SELECT count(*) FROM payments.withdrawal WHERE instruction_reference = ?", instruction))
                .isZero();
        assertThat(scalar("SELECT status FROM ledger.hold WHERE id = ?", hold.id().value())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a withdrawal born under the instruction is converged on without the resolver: once dispatched, a"
            + " re-ask whose resolver would refuse - the destination gone - answers the one withdrawal and sends nothing")
    void aWithdrawalBornUnderTheInstructionIsConvergedOnWithoutTheResolver() {
        Wallet wallet = fundedWallet(35_00);
        schemeAccepts();
        Hold hold = held(wallet, 35_00);
        String instruction = "lending:" + UUID.randomUUID();
        Withdrawals.Initiated first = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(35_00), hold.id()), resolver(wallet)));
        Withdrawals.Resolver refusing = unitOfWork -> {
            throw new SystemWithdrawalRefusedException(SystemWithdrawalRefusedException.Reason.DESTINATION_UNAVAILABLE);
        };
        Withdrawals.Initiated again = asSystem(() -> engine.withdrawOnInstruction(
                new Withdrawals.Instruction(instruction, eur(35_00), hold.id()), refusing));
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(sends(scalar("SELECT end_to_end_reference FROM payments.withdrawal WHERE id = ?",
                first.id().value()))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    private record Wallet(UUID party, UUID customer, LedgerAccountId account, UUID method) {}

    /** A fresh CUSTOMER_WALLET in EUR, funded by the fixture's posting from CASH_AT_BANK. */
    private Wallet fundedWallet(long minor) {
        UUID owner = UUID.randomUUID();
        return asSystem(() -> inTransaction(uow -> {
            LedgerAccountId account = ledgerAccountStore.insertIfAbsent(uow, LedgerAccount.owned(ids, clock,
                    AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, owner)).account().id();
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            postings.post(uow, new PostingCommand("p11t15-fund:" + UUID.randomUUID(), today, today, "the suite's fixture",
                    List.of(new JournalLine(ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                                    .orElseThrow().id(), Direction.DEBIT, eur(minor)),
                            new JournalLine(account, Direction.CREDIT, eur(minor)))));
            return new Wallet(UUID.randomUUID(), UUID.randomUUID(), account, UUID.randomUUID());
        }));
    }

    /** The instructor's hold, placed under the wallet's lock as lending's disbursement places it. */
    private Hold held(Wallet wallet, long minor) {
        return asSystem(() -> inTransaction(uow -> holds.place(uow, wallet.account(), eur(minor))));
    }

    private static Withdrawals.Resolver resolver(Wallet wallet) {
        return unitOfWork -> new Withdrawals.Resolved(wallet.party(), wallet.customer(), wallet.account(), EUR,
                wallet.method(), new ProviderReference("dest-sys-" + wallet.method().toString().substring(0, 8)));
    }

    private void schemeAccepts() {
        provider.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"sch-sys-{{request.headers.Idempotency-Key}}\","
                        + "\"cycle\":\"C1\"}");
    }

    private static long sends(String reference) {
        return provider.headerValues(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, "Idempotency-Key").stream()
                .filter(reference::equals).count();
    }

    private <R> R asSystem(java.util.function.Supplier<R> work) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return work.get();
        }
    }

    private Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t15"));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    private static <R> R inTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql, Object... parameters) {
        return Long.parseLong(scalar(sql, parameters));
    }

    private static String scalar(String sql, Object... parameters) {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement statement = migrator.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
