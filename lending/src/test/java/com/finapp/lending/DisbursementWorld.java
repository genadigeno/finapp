package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPurposeBalances;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * The disbursement suites' world (`P11-TSK-014`): loans offered by the real origination step and accepted by the real
 * acceptance, then disbursed by the real {@link LoanDisbursements} over the real ledger - its {@link PostingService}, its
 * accounts, its projections - with three doubles where lending has no build edge (A16):
 *
 * <ul>
 *   <li><strong>credit</strong>, scripted as {@code LoanAcceptanceDatabaseTest}'s: the exposure lock a
 *       transaction-scoped advisory lock on the party in a namespace this world alone uses - the profile row's stand-in,
 *       with its serialisation and its release at commit or rollback; credit's real row is {@code app}'s suites';
 *   <li><strong>the standing</strong>, a map a case flips;
 *   <li><strong>the wallet</strong> ({@link LoanWallets}), a real {@code CUSTOMER_WALLET} ledger account per party opened
 *       through the ledger's own {@code insertIfAbsent} - accounts' real door is {@code app}'s suites'; a party a case
 *       marks unreachable answers empty;
 *   <li><strong>payments</strong> ({@link LoanPayouts}, `P11-TSK-015`), {@link Payments}: a scripted answer per loan, every
 *       dispatch counted - payments' real system withdrawal is {@code app}'s suites'. The hold, the wallet and the ledger
 *       are real ({@link HoldService}).
 * </ul>
 *
 * <p>Capital is planted by a posting from {@code CASH_AT_BANK} - the fixture's, never a path the platform has.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class DisbursementWorld {

    static final Clock CLOCK = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode EUR = LoanTermsFixtures.EUR;
    static final Actor SYSTEM = new Actor("lending-disbursement", ActorType.SYSTEM);

    static final JdbcLoanTermsStore TERMS_STORE = new JdbcLoanTermsStore();
    static final LoanTermsAdministration ADMINISTRATION = new LoanTermsAdministration(TERMS_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, LoanTermsFixtures.BOUNDS);
    static final JdbcLoanApplicationStore APPLICATIONS = new JdbcLoanApplicationStore();
    static final JdbcLoanOfferStore OFFER_STORE = new JdbcLoanOfferStore();
    static final JdbcLoanStore LOAN_STORE = new JdbcLoanStore();
    static final JdbcLoanDisbursementStore DISBURSEMENT_STORE = new JdbcLoanDisbursementStore();
    static final LedgerAccountStore<Connection> LEDGER_ACCOUNTS = new JdbcLedgerAccountStore();
    static final JdbcPurposeBalances BALANCES = new JdbcPurposeBalances();
    static final LendingCapitalHeadroom HEADROOM = new LendingCapitalHeadroom(LEDGER_ACCOUNTS, BALANCES, LOAN_STORE);
    static final Credit CREDIT = new Credit();
    static final Map<UUID, Boolean> STANDING = new ConcurrentHashMap<>();
    static final Set<UUID> UNREACHABLE_WALLETS = ConcurrentHashMap.newKeySet();
    static final LoanPartyStanding STANDING_PORT = (unitOfWork, party) -> STANDING.getOrDefault(party, true);
    static final LoanWallets WALLETS = (unitOfWork, party, currency) -> UNREACHABLE_WALLETS.contains(party)
            ? Optional.empty()
            : Optional.of(LEDGER_ACCOUNTS.insertIfAbsent(unitOfWork, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                    AccountPurpose.CUSTOMER_WALLET, currency, party)).account().id());
    static final LoanOrigination ORIGINATION = new LoanOrigination(APPLICATIONS, OFFER_STORE, ADMINISTRATION,
            CREDIT, new JdbcOutboxWriter(), IDS, CLOCK);
    static final LoanAcceptances ACCEPTANCES = new LoanAcceptances(OFFER_STORE, APPLICATIONS, LOAN_STORE, CREDIT,
            STANDING_PORT, (unitOfWork, party, method) -> Optional.of(method), HEADROOM,
            new LoanAccounts(LEDGER_ACCOUNTS, IDS, CLOCK), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
    static final PostingService POSTINGS = new PostingService(
            new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
            new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(), new JdbcBalanceProjection(),
            IDS, CLOCK, PostingObserver.NONE);
    static final JdbcHoldStore HOLD_STORE = new JdbcHoldStore();
    static final HoldService HOLDS = new HoldService(LEDGER_ACCOUNTS, new JdbcBalanceDerivation(), HOLD_STORE,
            new JdbcBalanceProjection(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    static final JdbcLoanPayoutStore PAYOUT_STORE = new JdbcLoanPayoutStore();
    static final LoanDisbursements DISBURSEMENTS = disbursements(new JdbcOutboxWriter(), new JdbcAuditWriter());
    static final LoanSchedules SCHEDULES = new LoanSchedules(DISBURSEMENT_STORE);
    static final Payments PAYMENTS = new Payments();
    static final LoanPayoutSteps PAYOUT_STEPS = new LoanPayoutSteps(LOAN_STORE, PAYOUT_STORE, DISBURSEMENT_STORE,
            TERMS_STORE, PAYMENTS, HOLDS, new JdbcOutboxWriter(), IDS, CLOCK);
    static final LendingUnitOfWork UNIT_OF_WORK = new LendingUnitOfWork() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            return inOneTransaction(work);
        }
    };

    private DisbursementWorld() {}

    /** T-dis over the real stores and ledger, with this outbox and audit writer. */
    static LoanDisbursements disbursements(OutboxWriter<Connection> outbox, AuditWriter<Connection> audit) {
        return new LoanDisbursements(LOAN_STORE, DISBURSEMENT_STORE, TERMS_STORE, CREDIT, STANDING_PORT, WALLETS,
                LEDGER_ACCOUNTS, POSTINGS, HOLDS, PAYOUT_STORE, outbox, audit, IDS, CLOCK);
    }

    // ------------------------------------------------------------------ the loans

    /** An accepted loan awaiting its disbursement; path X's payout method when it has one. */
    record Accepted(LoanId loan, UUID party, Money principal, Optional<UUID> payoutMethod) {}

    /**
     * A fresh party's {@code minor} loan over {@code term} months, offered by the origination step and accepted for a
     * wallet payout, capital planted as needed; the terms in force are the product's ACTIVE version.
     */
    static Accepted acceptedLoan(long minor, int term) {
        return acceptedLoan(minor, term, PayoutPreference.WALLET);
    }

    /** As {@link #acceptedLoan(long, int)}, for {@code payout} - an external account names a fresh payout method. */
    static Accepted acceptedLoan(long minor, int term, PayoutPreference payout) {
        ensureActive();
        fund(Money.ofMinorUnits(minor, EUR));
        return accepted(LoanProduct.PERSONAL_LOAN, minor, Optional.of(term), payout);
    }

    /**
     * A fresh party's credit line of limit {@code minor}, offered and accepted (`P11-TSK-016`) - born {@code ACTIVE}; a
     * line commits no capital (A28). The terms in force are the line's ACTIVE version.
     */
    static Accepted acceptedLine(long minor) {
        Boolean active = inOneTransaction(uow -> TERMS_STORE.active(uow, LoanProduct.CREDIT_LINE).isPresent());
        if (!active) {
            activate(LoanTermsFixtures.of(LoanProduct.CREDIT_LINE, "0.1299").build());
        }
        return accepted(LoanProduct.CREDIT_LINE, minor, Optional.empty(), PayoutPreference.WALLET);
    }

    private static Accepted accepted(LoanProduct product, long minor, Optional<Integer> term, PayoutPreference payout) {
        Money amount = Money.ofMinorUnits(minor, EUR);
        LoanApplicationId application = LoanApplicationId.next(IDS);
        UUID party = UUID.randomUUID();
        UUID request = UUID.randomUUID();
        inOneTransaction(uow -> {
            LoanTermsStore.LoanTermsVersion version = ADMINISTRATION.pinActive(uow, product);
            Actor customer = customer(party);
            CorrelationId correlation = correlation();
            APPLICATIONS.insert(uow, new LoanApplicationStore.NewApplication(application, party,
                    product, request, version.row().id(), 10, RepaymentMethod.WALLET,
                    payout, customer, correlation));
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.empty(), LoanApplicationStatus.SUBMITTED, "APPLICATION_SUBMITTED", customer, correlation));
            APPLICATIONS.transition(uow, application, LoanApplicationStatus.SUBMITTED,
                    LoanApplicationStatus.AWAITING_DECISION);
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application,
                    Optional.of(LoanApplicationStatus.SUBMITTED), LoanApplicationStatus.AWAITING_DECISION,
                    "CREDIT_REQUEST_OPENED", customer, correlation));
            return null;
        });
        CREDIT.approve(request, party, product, amount, term, databaseNow().plus(Duration.ofDays(30)));
        LoanOrigination.Outcome outcome = inOneTransaction(uow -> ORIGINATION.originate(uow, application, SYSTEM));
        assertThat(outcome.kind()).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId offer = LoanOfferId.of(UUID.fromString(scalar(
                "SELECT id::text FROM lending.loan_offer WHERE application_id = ?", application.value())));
        String hash = scalar("SELECT encode(terms_sha256, 'hex') FROM lending.loan_offer_terms WHERE offer_id = ?",
                offer.value());
        Optional<UUID> method = payout == PayoutPreference.EXTERNAL_ACCOUNT
                ? Optional.of(UUID.randomUUID())
                : Optional.empty();
        LoanAcceptances.Accepted accepted = inOneTransaction(uow -> ACCEPTANCES.accept(uow, offer, party,
                new LoanAcceptances.Acceptance(hash, method, new AcceptanceEvidence(UUID.randomUUID(),
                        UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                customer(party), correlation()));
        return new Accepted(accepted.loan().id(), party, amount, method);
    }

    /** The product's terms in force: a fee-free 8.99 % version unless a case activated another. */
    static void ensureActive() {
        Boolean active = inOneTransaction(uow -> TERMS_STORE.active(uow, LoanProduct.PERSONAL_LOAN).isPresent());
        if (!active) {
            activate(LoanTermsFixtures.of(LoanProduct.PERSONAL_LOAN, "0.0899").build());
        }
    }

    /** Proposes and activates {@code terms} for the personal loan, retiring the version in force. */
    static void activate(LoanTerms terms) {
        LoanTermsAdministration.Proposed proposed = inOneTransaction(uow -> ADMINISTRATION.propose(uow, terms,
                "the suite's terms", employee(), correlation()));
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "reviewed", correlation()));
    }

    /** One disbursement step for {@code loan}, in its own transaction, as the sweep takes it. */
    static LoanDisbursements.Outcome disburse(LoanId loan) {
        return disburse(DISBURSEMENTS, loan);
    }

    static LoanDisbursements.Outcome disburse(LoanDisbursements disbursements, LoanId loan) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return inOneTransaction(uow -> disbursements.disburse(uow, loan, SYSTEM));
        }
    }

    // ------------------------------------------------------------------ the payout (P11-TSK-015)

    /** One payout sweep's claim, committed - as the sweep takes it. */
    static List<LoanPayoutStore.Claimed> claimPayouts(Duration permit) {
        return inOneTransaction(uow -> PAYOUT_STEPS.claim(uow, 100, permit));
    }

    /** The claim for {@code loan}, from a sweep's claim (empty when this claim did not take it). */
    static Optional<LoanPayoutStore.Claimed> claimed(List<LoanPayoutStore.Claimed> claims, LoanId loan) {
        return claims.stream().filter(claim -> claim.loan().equals(loan)).findFirst();
    }

    /**
     * One payout sweep as the schedule takes it: the claim committed, then EVERY claimed payout stepped - so no claim
     * leaves another case's payout parked on a permit. Answers each claimed loan's effects.
     */
    static Map<LoanId, List<LoanPayoutSteps.Outcome.Kind>> sweepPayouts(Duration permit) {
        Map<LoanId, List<LoanPayoutSteps.Outcome.Kind>> effects = new java.util.LinkedHashMap<>();
        for (LoanPayoutStore.Claimed claim : claimPayouts(permit)) {
            effects.put(claim.loan(), payoutStep(claim).stream().map(LoanPayoutSteps.Outcome::kind).toList());
        }
        return effects;
    }

    /** One claimed payout's step under the system actor, each part its own transaction. */
    static List<LoanPayoutSteps.Outcome> payoutStep(LoanPayoutStore.Claimed claimed) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return PAYOUT_STEPS.step(UNIT_OF_WORK, claimed, SYSTEM);
        }
    }

    /** A path X loan disbursed: accepted for an external payout and posted, its payout born PENDING. */
    static Accepted disbursedExternally(long minor, int term) {
        Accepted loan = acceptedLoan(minor, term, PayoutPreference.EXTERNAL_ACCOUNT);
        assertThat(disburse(loan.loan()).kind()).isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        return loan;
    }

    /** The available balance of {@code account} - settled less its standing holds - as the ledger derives it. */
    static Money available(LedgerAccountId account) {
        return inOneTransaction(uow -> new com.finapp.ledger.AvailableBalance<>(new JdbcBalanceDerivation(), HOLD_STORE)
                .underLock(uow, account));
    }

    /**
     * Payments as the world needs it: a scripted dispatch answer per loan (default: accepted, one stable withdrawal id per
     * loan), every dispatch counted, an optional probe run while payments "holds the wire", and a scripted outcome per
     * loan (default: undecided).
     */
    static final class Payments implements LoanPayouts {

        final Map<LoanId, Dispatch> answers = new ConcurrentHashMap<>();
        final Map<LoanId, Outcome> outcomes = new ConcurrentHashMap<>();
        final Map<LoanId, UUID> withdrawals = new ConcurrentHashMap<>();
        final Map<LoanId, java.util.concurrent.atomic.AtomicInteger> dispatches = new ConcurrentHashMap<>();
        final Map<LoanId, Instruction> instructions = new ConcurrentHashMap<>();
        volatile java.util.function.Consumer<Instruction> duringDispatch = instruction -> { };

        @Override
        public Dispatch dispatch(Instruction instruction) {
            dispatches.computeIfAbsent(instruction.loan(), loan -> new java.util.concurrent.atomic.AtomicInteger())
                    .incrementAndGet();
            instructions.put(instruction.loan(), instruction);
            duringDispatch.accept(instruction);
            return answers.getOrDefault(instruction.loan(),
                    new Dispatch.Accepted(withdrawals.computeIfAbsent(instruction.loan(), loan -> UUID.randomUUID())));
        }

        @Override
        public Outcome outcome(Connection unitOfWork, LoanId loan, UUID withdrawal) {
            return outcomes.getOrDefault(loan, new Outcome.Undecided());
        }

        int dispatchesOf(LoanId loan) {
            java.util.concurrent.atomic.AtomicInteger count = dispatches.get(loan);
            return count == null ? 0 : count.get();
        }
    }

    // ------------------------------------------------------------------ the money

    /** Capital headroom for {@code principal}: what is already free plus a planted contribution covering the rest. */
    static void fund(Money principal) {
        Money missing = principal.minus(inOneTransaction(uow -> HEADROOM.atRest(uow, EUR)).headroom());
        if (missing.isPositive()) {
            post("p11t14-capital:" + UUID.randomUUID(), List.of(
                    new JournalLine(cashAtBank(), Direction.DEBIT, missing),
                    new JournalLine(inOneTransaction(uow -> HEADROOM.capitalAccount(uow, EUR).id()), Direction.CREDIT,
                            missing)));
        }
    }

    static void post(String key, List<JournalLine> lines) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(correlation()).causing(CausationId.of("p11t14")))) {
            inOneTransaction(uow -> {
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                POSTINGS.post(uow, new PostingCommand(key, today, today, "the suite's fixture", lines));
                return null;
            });
        }
    }

    static LedgerAccountId cashAtBank() {
        return inOneTransaction(uow -> LEDGER_ACCOUNTS.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR)
                .orElseThrow().id());
    }

    /** The party's wallet account (the double's), opened if absent. */
    static LedgerAccountId walletOf(UUID party) {
        return inOneTransaction(uow -> WALLETS.walletFor(uow, party, EUR)).orElseThrow();
    }

    /** DR - CR over one account's journal lines, folded through Money. */
    static Money netDebit(LedgerAccountId account) {
        return inOneTransaction(uow -> {
            Money net = Money.zero(EUR);
            for (var line : BALANCES.linesOn(uow, account)) {
                net = line.direction() == Direction.DEBIT ? net.plus(line.amount()) : net.minus(line.amount());
            }
            return net;
        });
    }

    static LedgerAccountId loanAccount(LoanId loan, AccountPurpose purpose) {
        return inOneTransaction(uow -> LEDGER_ACCOUNTS.findOwned(uow, loan.value(), purpose, EUR).orElseThrow().id());
    }

    static LedgerAccountId feeIncome() {
        return inOneTransaction(uow -> LEDGER_ACCOUNTS.findOperational(uow, AccountPurpose.LOAN_FEE_INCOME, EUR)
                .orElseThrow().id());
    }

    // ------------------------------------------------------------------ plumbing

    static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    static Instant databaseNow() {
        return Instant.parse(scalar("SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC',"
                + " 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')"));
    }

    static <R> R inOneTransaction(Function<Connection, R> work) {
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

    static long count(String sql, Object... parameters) {
        return Long.parseLong(scalar(sql, parameters));
    }

    static String scalar(String sql, Object... parameters) {
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

    /** {@code sql} as the bootstrap superuser with the triggers suspended - the fixture's clock moving, never a path. */
    static void asOwner(String sql) {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute(sql);
            }
            owner.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    /** Waits until a session is blocked by {@code blocker}, and answers its pid and the statement it waits in. */
    static Blocked awaitBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT pid, query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))")) {
                if (row.next()) {
                    return new Blocked(row.getInt(1), row.getString(2));
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind the held lock");
    }

    record Blocked(int pid, String query) {}

    /** Racer {@code i} of {@code racers} runs {@code work.apply(i)}, all released at once. */
    static <T> List<T> race(int racers, IndexedWork<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                int index = i;
                pending.add(pool.submit((Callable<T>) () -> {
                    start.await();
                    return work.apply(index);
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    interface IndexedWork<T> {
        T apply(int index) throws Exception;
    }

    /**
     * Credit as the world needs it: the approvals the origination step reads, the exposure lock as a transaction-scoped
     * advisory lock on the party (credit's profile row's stand-in), and the consumption judging the lapse on the
     * database clock, as credit's definer function does.
     */
    static final class Credit implements LoanCreditDecisions {

        /** An advisory namespace this world alone uses - the profile row's stand-in. */
        static final int PROFILE_STAND_IN = 1_114;

        private final Map<UUID, CreditOutcome.Approved> byRequest = new ConcurrentHashMap<>();
        private final Map<UUID, CreditOutcome.Approved> byDecision = new ConcurrentHashMap<>();

        void approve(UUID request, UUID party, Money amount, Optional<Integer> term, Instant validUntil) {
            approve(request, party, LoanProduct.PERSONAL_LOAN, amount, term, validUntil);
        }

        void approve(UUID request, UUID party, LoanProduct product, Money amount, Optional<Integer> term,
                Instant validUntil) {
            UUID decision = UUID.randomUUID();
            CreditOutcome.Approved approved = new CreditOutcome.Approved(decision, party, product, amount, term,
                    validUntil);
            byRequest.put(request, approved);
            byDecision.put(decision, approved);
        }

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
            throw new UnsupportedOperationException("the world opens no request");
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the world cancels no request");
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return List.of();
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            CreditOutcome.Approved approved = byRequest.get(decisionRequest);
            return approved == null ? new CreditOutcome.Pending() : approved;
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return Optional.empty();
        }

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
                lock.setInt(1, PROFILE_STAND_IN);
                lock.setString(2, party.toString());
                lock.executeQuery().close();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
            return true;
        }

        @Override
        public Consumption consume(Connection unitOfWork, UUID decision, LoanAgreementId agreement) {
            CreditOutcome.Approved approved = byDecision.get(decision);
            if (approved == null) {
                return new Consumption.Refused(Consumption.Refusal.NOT_FOUND);
            }
            return new Consumption.Consumed(decision, approved.party(), approved.amount());
        }
    }
}
