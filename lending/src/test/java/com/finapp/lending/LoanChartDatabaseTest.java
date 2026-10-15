package com.finapp.lending;

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
 * The loan chart of accounts and the lending-capital account, live (`P11-TSK-003`; ledger `V026`,
 * ADR-0096 section 1, PHASE_11_PLAN.md sections 12.8 and 12.9; {@code INV-LED-06},
 * {@code INV-LND-01}, {@code INV-LND-13}).
 *
 * <p>Against the real migrated schema and the real ledger stores - the reconciliation module's
 * {@code ResolutionFixtures} wiring of {@link AdjustmentService} - so every guard named here is the
 * one a running instance meets: the generated {@code CHECK}s, the seed, the partial unique index the
 * opener converges on, and both ranks of the free-adjustment binding.
 */
@Tag("database")
@DisplayName("the loan chart of accounts and the lending capital are typed, seeded, guarded and openable (P11-TSK-003)")
class LoanChartDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** The seed ceiling the lock-order rule rests on (`OperationalChartMigrationTest`). */
    private static final Instant SEED_CEILING = Instant.parse("2026-09-28T00:00:00Z");

    /** The four operational purposes `V026` seeds and their pinned types (ADR-0096 section 1). */
    private static final Map<AccountPurpose, AccountType> SEEDED =
            Map.of(
                    AccountPurpose.LOAN_INTEREST_INCOME, AccountType.REVENUE,
                    AccountPurpose.LOAN_FEE_INCOME, AccountType.REVENUE,
                    AccountPurpose.LOAN_WRITE_OFF_EXPENSE, AccountType.EXPENSE,
                    AccountPurpose.LENDING_CAPITAL, AccountType.EQUITY);

    private final LedgerAccountStore<Connection> store = new JdbcLedgerAccountStore();
    private final ChartOfAccounts<Connection> chart = new ChartOfAccounts<>(store);
    private final LoanAccounts loanAccounts = new LoanAccounts(store, IDS, CLOCK);

    @Test
    @DisplayName("every purpose is typed as the account plan: the four operational ones seeded once per"
            + " supported currency below the id ceiling, the six per-loan ones opened per loan and never seeded")
    void everyPurposeIsTypedAndSeededPerCurrency() throws Exception {
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
            assertThat(AccountPurpose.LENDING_CAPITAL.ownerKind()).isEqualTo(OwnerKind.OPERATIONAL);
            assertThat(chart.resolve(app, AccountPurpose.LENDING_CAPITAL, EUR).normalBalance().name())
                    .as("the capital is credit-normal EQUITY - a source of funds, never a platform liability")
                    .isEqualTo("CREDIT");

            // The six per-loan purposes: never seeded, opened per loan with the declared type.
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_kind = 'LOAN'"
                            + " AND id::text LIKE '01a0e2bc-8200-%'"))
                    .as("no loan account is seeded")
                    .isZero();
            LoanId loan = LoanId.next(IDS);
            app.setAutoCommit(false);
            LoanLedgerAccounts opened = loanAccounts.openFor(app, loan, EUR);
            app.commit();
            for (AccountPurpose purpose : LoanAccounts.purposes()) {
                LedgerAccount account = store.findOwned(app, loan.value(), purpose, EUR).orElseThrow();
                AccountType expected = purpose == AccountPurpose.LOAN_CREDIT_BALANCE ? AccountType.LIABILITY : AccountType.ASSET;
                assertThat(account.accountType()).as("%s", purpose).isEqualTo(expected);
                assertThat(account.normalBalance()).as("%s", purpose).isEqualTo(expected.normalBalance());
                assertThat(account.ownerKind()).isEqualTo(OwnerKind.LOAN);
                assertThat(account.ownerRef()).contains(loan.value());
                assertThat(account.currency()).isEqualTo(EUR);
                assertThat(account.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
                assertThat(opened.all()).contains(account);
            }
        }
    }

    @Test
    @DisplayName("ten openers of one loan, racing, produce one set of six - each opener handed the same accounts")
    void tenOpenersOneSet() throws Exception {
        LoanId loan = LoanId.next(IDS);
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
                        LoanLedgerAccounts opened = loanAccounts.openFor(app, loan, EUR);
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
            assertThat(handed).as("every opener was handed the one set").allSatisfy(ids -> assertThat(ids).isEqualTo(handed.get(0)));
        } finally {
            pool.shutdownNow();
        }
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", loan.value()))
                    .as("one set of six, counted in the table")
                    .isEqualTo(6);
            assertThat(count(app, "SELECT count(DISTINCT purpose) FROM ledger.ledger_account WHERE owner_ref = ?"
                            + " AND currency = 'EUR' AND owner_kind = 'LOAN'", loan.value()))
                    .as("each of the six purposes once, in the loan's currency only")
                    .isEqualTo(6);
            // A later retry of the same intent converges too.
            app.setAutoCommit(false);
            List<UUID> again = loanAccounts.openFor(app, loan, EUR).all().stream().map(account -> account.id().value()).toList();
            app.commit();
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", loan.value())).isEqualTo(6);
            assertThat(again).hasSize(6).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("the six commit with the caller's transaction or not at all")
    void theAccountsCommitWithTheCallerOrNotAtAll() throws Exception {
        LoanId loan = LoanId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            loanAccounts.openFor(app, loan, EUR);
            app.rollback();
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", loan.value()))
                    .as("a rolled-back acceptance leaves no loan account behind")
                    .isZero();
            loanAccounts.openFor(app, loan, EUR);
            app.commit();
            assertThat(count(app, "SELECT count(*) FROM ledger.ledger_account WHERE owner_ref = ?", loan.value()))
                    .isEqualTo(6);
        }
    }

    @Test
    @DisplayName("an existing loan account of another type is refused, never adopted (INV-LED-06)")
    void aConvergedAccountOfTheWrongTypeIsRefused() throws Exception {
        LoanId loan = LoanId.next(IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            // A raw writer's credit balance opened as an ASSET - the shape a defect would leave.
            store.createOrConverge(app, LedgerAccount.owned(
                    IDS, CLOCK, AccountType.ASSET, AccountPurpose.LOAN_CREDIT_BALANCE, EUR, loan.value()));
            app.commit();
            assertThatThrownBy(() -> loanAccounts.openFor(app, loan, EUR))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("LOAN_CREDIT_BALANCE")
                    .hasMessageContaining("INV-LED-06")
                    // No loan id in an exception message (the gate's Security criterion; P11-DOC-001).
                    .hasMessageNotContaining(loan.value().toString());
            app.rollback();
        }
    }

    @Test
    @DisplayName("a MANUAL adjustment touching any loan or capital account is refused at the domain and by the"
            + " re-stated trigger, while an open account is still admitted")
    void aManualAdjustmentCannotTouchALoanOrCapitalAccount() throws Exception {
        AdjustmentService adjustments = adjustments();
        LoanId loan = LoanId.next(IDS);
        List<LedgerAccount> guarded = new ArrayList<>();
        LedgerAccount open;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            guarded.addAll(loanAccounts.openFor(app, loan, EUR).all());
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
                // The domain rank: judged before the idempotency claim and before any actor is read.
                AdjustmentCommand command = new AdjustmentCommand(
                        "loan-chart-" + IDS.next(),
                        LocalDate.of(2026, 10, 10),
                        LocalDate.of(2026, 10, 10),
                        "loan-chart-probe",
                        "a free adjustment probe",
                        List.of(new JournalLine(account.id(), Direction.DEBIT, Money.ofMinorUnits(500, EUR)),
                                new JournalLine(open.id(), Direction.CREDIT, Money.ofMinorUnits(500, EUR))));
                assertThatThrownBy(() -> adjustments.propose(app, command))
                        .as("the domain refuses a MANUAL line on %s", account.purpose())
                        .isInstanceOf(AdjustmentOnReconciledPositionException.class);
                app.rollback();

                // The database rank, past every domain guard: V026's re-stated trigger.
                UUID proposal = plantManualProposal(app);
                assertThatThrownBy(() -> plantLine(app, proposal, 0, account))
                        .as("the trigger refuses a raw MANUAL line on %s", account.purpose())
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("closed to free adjustments");
                app.rollback();
            }
        }
        // The control: the binding names its purposes; it does not refuse every line.
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID proposal = plantManualProposal(app);
            assertThatCode(() -> plantLine(app, proposal, 0, open))
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
            String loan = UUID.fromString(IDS.next().toString()).toString();
            assertRefused(app, "ASSET", "DEBIT", "CUSTOMER", loan, "LOAN_PRINCIPAL",
                    "ledger_account_owner_kind_matches_purpose");
            assertRefused(app, "EQUITY", "CREDIT", "LOAN", loan, "LENDING_CAPITAL",
                    "ledger_account_owner_kind_matches_purpose");
            assertRefused(app, "ASSET", "DEBIT", "LOAN", null, "LOAN_FEES_DUE",
                    "ledger_account_owner_ref_matches_kind");
            // An unknown purpose matches no clause of the coherence rule either, and PostgreSQL
            // tests CHECKs in name order - "owner_kind_matches_purpose" before "purpose_is_known" -
            // so either name is the refusal of an unknown purpose.
            assertRefused(app, "REVENUE", "CREDIT", "OPERATIONAL", null, "LOAN_PENALTY_INCOME",
                    "ledger_account_(owner_kind_matches_purpose|purpose_is_known)");
            assertRefused(app, "ASSET", "DEBIT", "BORROWER", loan, "LOAN_PRINCIPAL",
                    "ledger_account_owner_kind_is_known");
            assertRefused(app, "EQUITY", "DEBIT", "LOAN", loan, "LOAN_CREDIT_BALANCE",
                    "ledger_account_normal_balance_matches_type");
            // The control: the well-formed shape is admitted, so each refusal above is its own rule's.
            Savepoint admitted = app.setSavepoint();
            assertThatCode(() -> insertAccount(app, "LIABILITY", "CREDIT", "LOAN", loan, "LOAN_CREDIT_BALANCE"))
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
        UUID proposal = UUID.fromString(IDS.next().toString());
        try (PreparedStatement head = app.prepareStatement(
                "INSERT INTO ledger.adjustment_proposal (id, status, posting_date, value_date, reference,"
                        + " reason, proposed_by, proposed_at, reason_code, origin)"
                        + " VALUES (?, 'PROPOSED', '2026-10-10', '2026-10-10', 'raw-loan-probe', 'raw probe',"
                        + " 'op-raw', now(), 'MANUAL_CORRECTION', 'MANUAL')")) {
            head.setObject(1, proposal);
            head.executeUpdate();
        }
        return proposal;
    }

    private static void plantLine(Connection app, UUID proposal, int seq, LedgerAccount account) throws SQLException {
        try (PreparedStatement line = app.prepareStatement(
                "INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq, ledger_account_id, direction,"
                        + " amount_minor, currency, scale) VALUES (?, ?, ?, 'DEBIT', 500, 'EUR', 2)")) {
            line.setObject(1, proposal);
            line.setInt(2, seq);
            line.setObject(3, account.id().value());
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
            insert.setObject(1, UUID.fromString(IDS.next().toString()));
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
