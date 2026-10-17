package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentOnReconciledPositionException;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.OwnerKind;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The BNPL chart of accounts, live (`P12-TSK-003`; ledger `V027`, ADR-0103 decision 1, PHASE_12_PLAN.md
 * section 12.11; {@code INV-BNPL-01}, {@code INV-LED-06}, {@code INV-ACC-01}) - {@code LoanChartDatabaseTest}'s
 * shape for the second credit product.
 *
 * <p>Against the real migrated schema and the real ledger stores, so every guard named here is the one a
 * running instance meets: the generated {@code CHECK}s, the seed, the partial unique index the opener
 * converges on, and both ranks of the free-adjustment binding.
 */
@Tag("database")
@DisplayName("the BNPL chart of accounts is typed, seeded, guarded and openable per obligation (P12-TSK-003)")
class BnplChartDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The seed ceiling the lock-order rule rests on (`OperationalChartMigrationTest`). */
    private static final Instant SEED_CEILING = Instant.parse("2026-09-28T00:00:00Z");

    /** The four operational purposes `V027` seeds and their pinned types (ADR-0103 decision 1). */
    private static final Map<AccountPurpose, AccountType> SEEDED =
            Map.of(
                    AccountPurpose.BNPL_MERCHANT_FEE_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_INTEREST_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_FEE_INCOME, AccountType.REVENUE,
                    AccountPurpose.BNPL_WRITE_OFF_EXPENSE, AccountType.EXPENSE);

    private final LedgerAccountStore<Connection> store = new JdbcLedgerAccountStore();
    private final ChartOfAccounts<Connection> chart = new ChartOfAccounts<>(store);
    private final BnplAccounts bnplAccounts = new BnplAccounts(store, IDS, CLOCK);

    @Test
    @DisplayName("the four operational accounts are seeded once per supported currency, typed as the plan, below"
            + " the id ceiling, holding nothing")
    void theOperationalAccountsAreSeededPerCurrency() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            for (Map.Entry<AccountPurpose, AccountType> seeded : SEEDED.entrySet()) {
                for (CurrencyCode currency : SupportedCurrencies.ALL) {
                    LedgerAccount account = chart.resolve(app, seeded.getKey(), currency);
                    assertThat(account.accountType()).as("%s in %s", seeded.getKey(), currency).isEqualTo(seeded.getValue());
                    assertThat(account.normalBalance()).isEqualTo(seeded.getValue().normalBalance());
                    assertThat(account.ownerKind()).isEqualTo(OwnerKind.OPERATIONAL);
                    assertThat(account.ownerRef()).isEmpty();
                    assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
                    assertThat(Instant.ofEpochMilli(account.id().value().getMostSignificantBits() >>> 16))
                            .as("seed %s sorts before every runtime id", account.id().value())
                            .isBefore(SEED_CEILING);
                }
                assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE purpose = ?",
                                seeded.getKey().name()))
                        .as("exactly one %s account per supported currency, no stray row", seeded.getKey())
                        .isEqualTo(SupportedCurrencies.ALL.size());
            }
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'BNPL_OBLIGATION'"
                            + " AND id::text LIKE '01a0e2bc-8200-%'"))
                    .as("no obligation account is seeded")
                    .isZero();
        }
    }

    @Test
    @DisplayName("the six per-obligation accounts are opened per obligation with the declared types, owned by it,"
            + " in its one currency")
    void theSixAccountsAreOpenedPerObligation() throws Exception {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            BnplLedgerAccounts opened = bnplAccounts.openFor(app, obligation, EUR);
            app.commit();
            for (AccountPurpose purpose : BnplAccounts.purposes()) {
                LedgerAccount account = store.findOwned(app, obligation.value(), purpose, EUR).orElseThrow();
                AccountType expected =
                        purpose == AccountPurpose.BNPL_CREDIT_BALANCE ? AccountType.LIABILITY : AccountType.ASSET;
                assertThat(account.accountType()).as("%s", purpose).isEqualTo(expected);
                assertThat(account.normalBalance()).as("%s", purpose).isEqualTo(expected.normalBalance());
                assertThat(account.ownerKind()).isEqualTo(OwnerKind.BNPL_OBLIGATION);
                assertThat(account.ownerRef()).contains(obligation.value());
                assertThat(account.currency()).isEqualTo(EUR);
                assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
                assertThat(opened.all()).contains(account);
            }
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .isEqualTo(6);
        }
    }

    @Test
    @DisplayName("ten openers of one obligation, racing, produce one set of six - each opener handed the same"
            + " accounts")
    void tenOpenersConvergeOnOneSet() throws Exception {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        int openers = 10;
        CountDownLatch ready = new CountDownLatch(openers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(openers);
        List<Future<List<UUID>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < openers; i++) {
                results.add(pool.submit(() -> {
                    try (Connection app = DatabaseRoles.application()) {
                        app.setAutoCommit(false);
                        ready.countDown();
                        go.await();
                        BnplLedgerAccounts opened = bnplAccounts.openFor(app, obligation, EUR);
                        app.commit();
                        return opened.all().stream().map(account -> account.id().value()).toList();
                    }
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<List<UUID>> handed = new ArrayList<>();
            for (Future<List<UUID>> result : results) {
                handed.add(result.get(60, TimeUnit.SECONDS));
            }
            assertThat(handed).as("every opener was handed the one set")
                    .allSatisfy(ids -> assertThat(ids).isEqualTo(handed.get(0)));
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .as("one set of six, counted in the table")
                    .isEqualTo(6);
            assertThat(count(app, "SELECT count(DISTINCT purpose) FROM ledger.ledger_account WHERE owner_ref = ?"
                            + " AND currency = 'EUR' AND owner_kind = 'BNPL_OBLIGATION'", obligation.value()))
                    .as("each of the six purposes once, in the obligation's currency only")
                    .isEqualTo(6);
        }
    }

    @Test
    @DisplayName("the six commit with the caller's transaction or not at all")
    void theAccountsCommitWithTheCallerOrNotAtAll() throws Exception {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            bnplAccounts.openFor(app, obligation, EUR);
            app.rollback();
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .as("a rolled-back purchase leaves no obligation account behind")
                    .isZero();
            bnplAccounts.openFor(app, obligation, EUR);
            app.commit();
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .isEqualTo(6);
        }
    }

    @Test
    @DisplayName("an existing obligation account of another type is refused before any write, never adopted"
            + " (INV-LED-06)")
    void aConvergedAccountOfTheWrongTypeIsRefused() throws Exception {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            // A raw writer's credit balance opened as an ASSET - the shape a defect would leave.
            store.createOrConverge(app, LedgerAccount.owned(
                    IDS, CLOCK, AccountType.ASSET, AccountPurpose.BNPL_CREDIT_BALANCE, EUR, obligation.value()));
            app.commit();
            assertThatThrownBy(() -> bnplAccounts.openFor(app, obligation, EUR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("BNPL_CREDIT_BALANCE")
                    .hasMessageContaining("INV-LED-06")
                    .hasMessageNotContaining(obligation.value().toString());
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .as("refused before any write: only the planted account exists, inside the open transaction too")
                    .isEqualTo(1);
            app.rollback();
        }
    }

    @Test
    @DisplayName("an unsupported currency is refused before the ledger is asked, and nothing is written")
    void anUnsupportedCurrencyIsRefused() throws Exception {
        BnplObligationId obligation = BnplObligationId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThatThrownBy(() -> bnplAccounts.openFor(app, obligation, CurrencyCode.of("CHF")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("CHF");
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", obligation.value()))
                    .isZero();
            app.rollback();
        }
    }

    @Test
    @DisplayName("a MANUAL adjustment touching any BNPL account is refused at the domain and by the re-stated"
            + " trigger, while an open account is still admitted")
    void aManualAdjustmentCannotTouchABnplAccount() throws Exception {
        AdjustmentService adjustments = adjustments();
        BnplObligationId obligation = BnplObligationId.next(IDS);
        List<LedgerAccount> guarded = new ArrayList<>();
        LedgerAccount open;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            guarded.addAll(bnplAccounts.openFor(app, obligation, EUR).all());
            app.commit();
            for (AccountPurpose operational : SEEDED.keySet()) {
                guarded.add(chart.resolve(app, operational, EUR));
            }
            open = chart.resolve(app, AccountPurpose.FEE_REVENUE, EUR);
        }
        Set<AccountPurpose> covered = EnumSet.noneOf(AccountPurpose.class);
        guarded.forEach(account -> covered.add(account.purpose()));
        assertThat(covered).as("not vacuous: all ten purposes are probed").hasSize(10);

        for (LedgerAccount account : guarded) {
            try (Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                AdjustmentCommand command = new AdjustmentCommand(
                        "bnpl-chart-" + IDS.next(),
                        LocalDate.of(2026, 10, 17),
                        LocalDate.of(2026, 10, 17),
                        "bnpl-chart-probe",
                        "a free adjustment probe",
                        List.of(new JournalLine(account.id(), Direction.DEBIT, Money.ofMinorUnits(500, EUR)),
                                new JournalLine(open.id(), Direction.CREDIT, Money.ofMinorUnits(500, EUR))));
                assertThatThrownBy(() -> adjustments.propose(app, command))
                        .as("the domain refuses a MANUAL line on %s", account.purpose())
                        .isInstanceOf(AdjustmentOnReconciledPositionException.class);
                app.rollback();

                // The database rank, past every domain guard: V027's re-stated trigger.
                UUID proposal = plantManualProposal(app);
                assertThatThrownBy(() -> plantLine(app, proposal, account))
                        .as("the trigger refuses a raw MANUAL line on %s", account.purpose())
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("closed to free adjustments");
                app.rollback();
            }
        }
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID proposal = plantManualProposal(app);
            assertThatCode(() -> plantLine(app, proposal, open))
                    .as("a MANUAL line on FEE_REVENUE is still admitted by the trigger")
                    .doesNotThrowAnyException();
            app.rollback();
        }
    }

    @Test
    @DisplayName("the owner kind matches the purpose: every mismatched or unknown row is refused by its own CHECK")
    void theOwnerKindMatchesThePurpose() throws Exception {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            String obligation = IDS.next().toString();
            assertRefused(app, "ASSET", "DEBIT", "LOAN", obligation, "BNPL_PRINCIPAL",
                    "ledger_account_owner_kind_matches_purpose");
            assertRefused(app, "ASSET", "DEBIT", "BNPL_OBLIGATION", obligation, "LOAN_PRINCIPAL",
                    "ledger_account_owner_kind_matches_purpose");
            assertRefused(app, "REVENUE", "CREDIT", "BNPL_OBLIGATION", obligation, "BNPL_MERCHANT_FEE_INCOME",
                    "ledger_account_owner_kind_matches_purpose");
            assertRefused(app, "ASSET", "DEBIT", "BNPL_OBLIGATION", null, "BNPL_FEES_DUE",
                    "ledger_account_owner_ref_matches_kind");
            assertRefused(app, "ASSET", "DEBIT", "BNPL_CUSTOMER", obligation, "BNPL_PRINCIPAL",
                    "ledger_account_owner_kind_is_known");
            assertRefused(app, "ASSET", "DEBIT", "BNPL_OBLIGATION", obligation, "BNPL_PRINCIPAL_CLEARING",
                    "ledger_account_(owner_kind_matches_purpose|purpose_is_known)");
            assertRefused(app, "EQUITY", "DEBIT", "BNPL_OBLIGATION", obligation, "BNPL_CREDIT_BALANCE",
                    "ledger_account_normal_balance_matches_type");
            Savepoint admitted = app.setSavepoint();
            assertThatCode(() -> insertAccount(app, "LIABILITY", "CREDIT", "BNPL_OBLIGATION", obligation,
                            "BNPL_CREDIT_BALANCE"))
                    .doesNotThrowAnyException();
            app.rollback(admitted);
            app.rollback();
        }
    }

    // ------------------------------------------------------------------------------------------

    private static AdjustmentService adjustments() {
        return new AdjustmentService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAdjustmentProposalStore(),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE,
                new JdbcLedgerAccountStore());
    }

    private static UUID plantManualProposal(Connection app) throws SQLException {
        UUID proposal = IDS.next();
        try (PreparedStatement head = app.prepareStatement(
                "INSERT INTO ledger.adjustment_proposal (id, status, posting_date, value_date, reference,"
                        + " reason, proposed_by, proposed_at, reason_code, origin)"
                        + " VALUES (?, 'PROPOSED', '2026-10-17', '2026-10-17', 'raw-bnpl-probe', 'raw probe',"
                        + " 'op-raw', now(), 'MANUAL_CORRECTION', 'MANUAL')")) {
            head.setObject(1, proposal);
            head.executeUpdate();
        }
        return proposal;
    }

    private static void plantLine(Connection app, UUID proposal, LedgerAccount account) throws SQLException {
        try (PreparedStatement line = app.prepareStatement(
                "INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq, ledger_account_id, direction,"
                        + " amount_minor, currency, scale) VALUES (?, 0, ?, 'DEBIT', 500, 'EUR', 2)")) {
            line.setObject(1, proposal);
            line.setObject(2, account.id().value());
            line.executeUpdate();
        }
    }

    private static void assertRefused(Connection app, String type, String normal, String kind, String owner,
            String purpose, String constraint) throws SQLException {
        Savepoint before = app.setSavepoint();
        assertThatThrownBy(() -> insertAccount(app, type, normal, kind, owner, purpose))
                .as("%s/%s owned by %s (%s) is refused by %s", purpose, type, kind, owner, constraint)
                .isInstanceOf(SQLException.class)
                .hasMessageMatching("(?s).*\"" + constraint + "\".*");
        app.rollback(before);
    }

    private static void insertAccount(Connection app, String type, String normal, String kind, String owner,
            String purpose) throws SQLException {
        try (PreparedStatement insert = app.prepareStatement(
                "INSERT INTO ledger.ledger_account (id, account_type, normal_balance, currency, owner_kind,"
                        + " owner_ref, purpose, gl_code, status, created_at, status_changed_at)"
                        + " VALUES (?, ?, ?, 'EUR', ?, ?::uuid, ?, NULL, 'ACTIVE', now(), now())")) {
            insert.setObject(1, IDS.next());
            insert.setString(2, type);
            insert.setString(3, normal);
            insert.setString(4, kind);
            insert.setString(5, owner);
            insert.setString(6, purpose);
            insert.executeUpdate();
        }
    }

    private static long count(Connection app, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
