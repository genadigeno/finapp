package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.DerivedBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.NormalBalance;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.UnderivableBalanceException;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The balance derivation against a live PostgreSQL (`P3-TSK-008`): replay from zero over real
 * posted rows, with the expected value recomputed through <strong>independent
 * {@code BigDecimal} arithmetic over raw SQL</strong> rather than through {@code Money} — the
 * `P3-TSK-004` rule that the definition must not certify the kernel with the kernel.
 *
 * <p>{@code EQUITY} is deliberately absent here: no {@link AccountPurpose} produces one, so no
 * account of that type can exist to post to. The definition's sign convention is swept over
 * all five types hermetically ({@code BalanceDerivationTest}); this tier covers the four
 * reachable ones over rows. The seam accounts ({@code FX_POSITION}, {@code SUSPENSE_UNMATCHED})
 * are never posted to — the `P3-TSK-003` seam test counts their lines globally.
 */
@Tag("database")
@DisplayName("the balance derivation over real postings (P3-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class BalanceDerivationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DATE = LocalDate.of(2026, 9, 14);

    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();
    private final BalanceDerivation<Connection> derivation = new JdbcBalanceDerivation();

    @Test
    @DisplayName("replay from zero agrees with independent arithmetic for every reachable type")
    void replayAgreesWithIndependentArithmeticForEveryReachableType() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app, USD); // LIABILITY
            LedgerAccount clearing = operational(app, AccountPurpose.SETTLEMENT_CLEARING);
            LedgerAccount fees = operational(app, AccountPurpose.FEE_REVENUE);
            LedgerAccount residual = operational(app, AccountPurpose.ROUNDING_RESIDUAL);
            // The four reachable types, by their derived normal balances: ASSET and EXPENSE
            // debit-normal, LIABILITY and REVENUE credit-normal (the aggregate exposes the
            // derivation's product, not the input - P3-TSK-002's no-free-choices rule).
            assertThat(clearing.normalBalance()).isEqualTo(NormalBalance.DEBIT);
            assertThat(residual.normalBalance()).isEqualTo(NormalBalance.DEBIT);
            assertThat(fees.normalBalance()).isEqualTo(NormalBalance.CREDIT);
            assertThat(wallet.normalBalance()).isEqualTo(NormalBalance.CREDIT);

            post(app, entry(clearing, Direction.DEBIT, wallet, Direction.CREDIT, 5000));
            post(app, entry(residual, Direction.DEBIT, fees, Direction.CREDIT, 300));
            // Both directions on one account, so the fold's subtraction is exercised on rows.
            post(app, entry(wallet, Direction.DEBIT, clearing, Direction.CREDIT, 1200));
            app.commit();

            // The fresh account's number is pinned literally; every account - the shared
            // operational rows included, whatever other tests have posted - is held to the
            // independent recomputation, which is the property under test.
            assertThat(derivation.derive(app, wallet.id(), AsOf.latest()).settled())
                    .isEqualTo(Money.ofMinorUnits(3800, USD));
            for (LedgerAccount account : List.of(wallet, clearing, fees, residual)) {
                DerivedBalance derived = derivation.derive(app, account.id(), AsOf.latest());
                assertThat(derived.settled().currency())
                        .as("%s: the balance is in the account's own currency", account.id())
                        .isEqualTo(account.currency());
                assertThat(derived.settled().toBigDecimal())
                        .as("%s (normal %s): derivation equals independent BigDecimal replay",
                                account.id(), account.normalBalance())
                        .isEqualByComparingTo(independentReplay(app, account));
            }
        }
    }

    @Test
    @DisplayName("posted against its normal side, an account reads negative - legally")
    void postedAgainstTheNormalSideReadsNegative() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount drawn = wallet(app, USD);
            LedgerAccount funded = wallet(app, USD);
            post(app, entry(drawn, Direction.DEBIT, funded, Direction.CREDIT, 700));
            app.commit();

            // A LIABILITY debited without a prior credit: negative in its own terms, refused
            // by nothing - refusing it would be overdraft policy (P3-TSK-015's), not
            // accounting.
            assertThat(derivation.derive(app, drawn.id(), AsOf.latest()).settled())
                    .isEqualTo(Money.ofMinorUnits(-700, USD));
            assertThat(derivation.derive(app, funded.id(), AsOf.latest()).settled())
                    .isEqualTo(Money.ofMinorUnits(700, USD));
        }
    }

    @Test
    @DisplayName("an account with no postings is zero in its own currency, never a bare 0")
    void anAccountWithNoPostingsIsZeroInItsOwnCurrency() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount yen = wallet(app, CurrencyCode.of("JPY"));
            LedgerAccount sterling = wallet(app, CurrencyCode.of("GBP"));
            app.commit();

            // The empty answer still says what kind of number it is (INV-MON-02): JPY at its
            // own scale 0 and GBP at 2 - not one hardcoded zero shared by both.
            Money yenZero = derivation.derive(app, yen.id(), AsOf.latest()).settled();
            assertThat(yenZero.isZero()).isTrue();
            assertThat(yenZero.currency()).isEqualTo(CurrencyCode.of("JPY"));
            assertThat(yenZero.scale()).isZero();
            Money sterlingZero = derivation.derive(app, sterling.id(), AsOf.latest()).settled();
            assertThat(sterlingZero.isZero()).isTrue();
            assertThat(sterlingZero.currency()).isEqualTo(CurrencyCode.of("GBP"));
            assertThat(sterlingZero.scale()).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("as-of posting date is inclusive of the day and excludes later days")
    void asOfPostingDateIsInclusive() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app, USD);
            LedgerAccount counter = wallet(app, USD);
            LocalDate firstDay = LocalDate.of(2026, 9, 10);
            LocalDate secondDay = firstDay.plusDays(1);
            post(app, entryOn(firstDay, counter, Direction.DEBIT,
                    wallet, Direction.CREDIT, 1000));
            post(app, entryOn(secondDay, counter, Direction.DEBIT,
                    wallet, Direction.CREDIT, 500));
            app.commit();

            assertThat(derivation
                            .derive(app, wallet.id(), AsOf.postingDate(firstDay))
                            .settled())
                    .as("'as of the 10th' means with the 10th's postings in it")
                    .isEqualTo(Money.ofMinorUnits(1000, USD));
            assertThat(derivation
                            .derive(app, wallet.id(), AsOf.postingDate(secondDay))
                            .settled())
                    .isEqualTo(Money.ofMinorUnits(1500, USD));
            assertThat(derivation
                            .derive(app, wallet.id(), AsOf.postingDate(firstDay.minusDays(1)))
                            .settled())
                    .isEqualTo(Money.zero(USD));
        }
    }

    @Test
    @DisplayName("the entry cut replays up to and including the named entry")
    void throughEntryCutsTheReplayAtTheEntry() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount wallet = wallet(app, USD);
            LedgerAccount counter = wallet(app, USD);
            // Sequential posts through one generator: UUIDv7 mint order IS the entry order
            // here, which is exactly the replay-boundary case the cut is for (AsOf's javadoc
            // owns the concurrent-commit caveat, and P3-TSK-009 owns confronting it).
            PostingResult first =
                    post(app, entry(counter, Direction.DEBIT, wallet, Direction.CREDIT, 1000));
            PostingResult second =
                    post(app, entry(counter, Direction.DEBIT, wallet, Direction.CREDIT, 500));
            app.commit();

            assertThat(derivation
                            .derive(app, wallet.id(), AsOf.throughEntry(first.entryId()))
                            .settled())
                    .isEqualTo(Money.ofMinorUnits(1000, USD));
            assertThat(derivation
                            .derive(app, wallet.id(), AsOf.throughEntry(second.entryId()))
                            .settled())
                    .isEqualTo(Money.ofMinorUnits(1500, USD));
        }
    }

    @Test
    @DisplayName("an uncommitted posting is invisible to another connection's derivation")
    void anUncommittedPostingIsInvisible() throws Exception {
        try (Connection poster = DatabaseRoles.application();
                Connection reader = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            poster.setAutoCommit(false);
            LedgerAccount wallet = wallet(poster, USD);
            LedgerAccount counter = wallet(poster, USD);
            poster.commit();

            post(poster, entry(counter, Direction.DEBIT, wallet, Direction.CREDIT, 900));
            // Not yet committed: the derivation reads the authoritative record, and the
            // authoritative record is what has COMMITTED - a balance including somebody's
            // maybe would be a number nobody can defend. Deterministic, no timing.
            assertThat(derivation.derive(reader, wallet.id(), AsOf.latest()).settled())
                    .isEqualTo(Money.zero(USD));
            poster.commit();
            assertThat(derivation.derive(reader, wallet.id(), AsOf.latest()).settled())
                    .isEqualTo(Money.ofMinorUnits(900, USD));
        }
    }

    @Test
    @DisplayName("a mixed-scale history refuses loudly, naming the fact and never a sum")
    void aMixedScaleHistoryRefusesLoudly() throws Exception {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = flow()) {
            app.setAutoCommit(false);
            LedgerAccount mixed = wallet(app, USD);
            LedgerAccount counter = wallet(app, USD);
            LedgerAccount persistedOnly = wallet(app, USD);
            post(app, entry(counter, Direction.DEBIT, mixed, Direction.CREDIT, 1500));
            app.commit();

            // The raw-SQL writer the domain never sees (the P3-TSK-005 plant): an entry whose
            // lines sit at scale 3 - balanced at ONE scale, so every trigger admits it. It
            // credits `mixed` (whose history is at scale 2) and debits a FRESH account, so
            // one account becomes genuinely mixed and the other holds a purely
            // persisted-scale history.
            plantScaleThreeEntry(app, persistedOnly.id(), mixed.id(), 777);
            app.commit();

            // `mixed` now holds USD credits at scale 2 AND scale 3: summing raw units across
            // scales is meaningless, normalising is the implicit rounding INV-MON-03 forbids,
            // so the only honest answer is a loud refusal - naming the account, the currency
            // and the fact, never a sum (INV-AUD-02: this message reaches logs).
            assertThatThrownBy(() -> derivation.derive(app, mixed.id(), AsOf.latest()))
                    .isInstanceOf(UnderivableBalanceException.class)
                    .hasMessageContaining("mixes scales")
                    .hasMessageContaining("USD")
                    .satisfies(refusal -> assertThat(refusal.getMessage())
                            .doesNotContain("1500")
                            .doesNotContain("777"));

            // `persistedOnly` is NOT mixed: its whole history is one debit at scale 3, and
            // the zero it settles against has no scale of its own - the scale-aware zero
            // identity answers rather than refusing, which is INV-MON-05 on real rows: a
            // stored amount reads back, and settles, as the amount it was.
            assertThat(derivation.derive(app, persistedOnly.id(), AsOf.latest()).settled())
                    .as("a one-sided persisted-scale history is a balance, not a refusal")
                    .isEqualTo(Money.ofPersisted(-777, USD, 3));
        }
    }

    // ------------------------------------------------------------------ fixtures

    private PostingService service() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK);
    }

    private PostingResult post(Connection app, PostingCommand command) {
        return service().post(app, command);
    }

    private LedgerAccount wallet(Connection app, CurrencyCode currency) {
        return accounts
                .createOrConverge(
                        app,
                        LedgerAccount.owned(
                                IDS, CLOCK, AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET, currency, IDS.next()))
                .account();
    }

    private LedgerAccount operational(Connection app, AccountPurpose purpose) {
        return accounts.findOperational(app, purpose, USD).orElseThrow();
    }

    private static PostingCommand entry(
            LedgerAccount first, Direction firstSide,
            LedgerAccount second, Direction secondSide, long minorUnits) {
        return entryOn(DATE, first, firstSide, second, secondSide, minorUnits);
    }

    private static PostingCommand entryOn(
            LocalDate postingDate,
            LedgerAccount first, Direction firstSide,
            LedgerAccount second, Direction secondSide, long minorUnits) {
        return new PostingCommand(
                "balance-" + IDS.next(), postingDate, postingDate, "probe-event",
                List.of(
                        new JournalLine(first.id(), firstSide,
                                Money.ofMinorUnits(minorUnits, USD)),
                        new JournalLine(second.id(), secondSide,
                                Money.ofMinorUnits(minorUnits, USD))));
    }

    /**
     * The expected balance recomputed WITHOUT {@code Money}: raw rows, {@code BigDecimal}
     * arithmetic, the account's stored {@code normal_balance} - so the derivation is checked
     * against an independent implementation rather than against itself.
     */
    private static BigDecimal independentReplay(Connection app, LedgerAccount account)
            throws SQLException {
        NormalBalance normal;
        try (PreparedStatement select = app.prepareStatement(
                "SELECT normal_balance FROM ledger.ledger_account WHERE id = ?")) {
            select.setObject(1, account.id().value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                normal = NormalBalance.valueOf(row.getString("normal_balance"));
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        try (PreparedStatement select = app.prepareStatement(
                "SELECT direction, amount_minor, scale FROM ledger.journal_line"
                        + " WHERE ledger_account_id = ?")) {
            select.setObject(1, account.id().value());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    BigDecimal amount =
                            BigDecimal.valueOf(row.getLong("amount_minor"),
                                    row.getShort("scale"));
                    boolean onNormalSide =
                            Direction.valueOf(row.getString("direction")).name()
                                    .equals(normal.name());
                    total = onNormalSide ? total.add(amount) : total.subtract(amount);
                }
            }
        }
        return total;
    }

    /**
     * The writer the domain never sees: a balanced scale-3 USD entry by raw SQL - one scale
     * per currency within the entry, so the deferred triggers admit it, while the accounts'
     * wider history becomes mixed-scale.
     */
    private static void plantScaleThreeEntry(
            Connection app, LedgerAccountId debit, LedgerAccountId credit, long minorAtThree)
            throws SQLException {
        Object entryId = IDS.next();
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO ledger.journal_entry (id, posting_date, value_date, entry_type,"
                        + " reference, reason, actor_id, correlation_id, causation_id,"
                        + " idempotency_scope, created_at)"
                        + " VALUES (?, ?, ?, 'POSTING', 'probe-event', NULL, 'probe', ?, ?,"
                        + " ?, ?)")) {
            insert.setObject(1, entryId);
            insert.setObject(2, DATE);
            insert.setObject(3, DATE);
            insert.setString(4, CorrelationId.generate(IDS).value());
            insert.setString(5, CorrelationId.generate(IDS).value());
            insert.setString(6, "probe:" + IDS.next());
            insert.setTimestamp(7, Timestamp.from(Instant.now(CLOCK)));
            insert.executeUpdate();
        }
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO ledger.journal_line (id, entry_id, ledger_account_id, direction,"
                        + " amount_minor, currency, scale, seq)"
                        + " VALUES (?, ?, ?, ?, ?, 'USD', 3, ?)")) {
            insert.setObject(1, IDS.next());
            insert.setObject(2, entryId);
            insert.setObject(3, debit.value());
            insert.setString(4, "DEBIT");
            insert.setLong(5, minorAtThree);
            insert.setInt(6, 0);
            insert.addBatch();
            insert.setObject(1, IDS.next());
            insert.setObject(2, entryId);
            insert.setObject(3, credit.value());
            insert.setString(4, "CREDIT");
            insert.setLong(5, minorAtThree);
            insert.setInt(6, 1);
            insert.addBatch();
            insert.executeBatch();
        }
    }

    private static CorrelationContext.Scope flow() {
        return CorrelationContext.enter(
                Correlation.startingWith(CorrelationId.generate(IDS)));
    }
}
