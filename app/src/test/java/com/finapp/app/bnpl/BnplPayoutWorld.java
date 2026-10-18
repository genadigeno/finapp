package com.finapp.app.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.DerivedBalance;
import com.finapp.ledger.Direction;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldId;
import com.finapp.ledger.HoldService;
import com.finapp.ledger.HoldStatus;
import com.finapp.ledger.HoldStore;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.NormalBalance;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.ledger.PostingAttribution;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.StatementDerivation;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.MerchantTransactionRunner;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationId;
import com.finapp.merchant.PayoutDestinationReference;
import com.finapp.merchant.PayoutDestinationStatus;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.PayoutEvidenceKind;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.PayoutAnswer;
import com.finapp.merchant.PayoutQueryAnswer;
import com.finapp.merchant.PayoutReference;
import com.finapp.merchant.PayoutSettlementExpectations;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The merchant payout of BNPL-financed money, composed hermetically (`P12-TSK-016`): the REAL {@link MerchantPayouts},
 * {@link MerchantPayoutOutcomes}, {@link MerchantPayoutResolution}, ledger {@link HoldService} and {@link PostingService},
 * the platform's {@link IdempotentExecutor}, merchant's {@link MerchantPayable} and the REAL
 * {@link SimulatedPayoutProvider} adapter over the {@link SimulatedProvider} scheme on loopback - only the stores are in
 * memory. So the payout path the database suites prove ({@code BnplMerchantPayoutDatabaseTest}) runs here end to end on
 * the merchant's real code, and a deliberate break of it is observed red without a database.
 *
 * <h2>What the fakes hold, and what they cannot</h2>
 *
 * <ul>
 *   <li><strong>Transactions</strong>: {@link #transactions} snapshots every store before the work and restores it if the
 *       work throws - the rollback the dispatch transaction's refusals rely on ("nothing held, nothing written, the key
 *       unspent").
 *   <li><strong>The database clock</strong>: the fake connection answers {@code SELECT statement_timestamp()} from the
 *       world's own clock ({@code DatabaseTime}), and issues no other SQL - a store that leaked to JDBC is loud.
 *   <li><strong>The statements' conditionals</strong>: each fake store keeps its JDBC twin's {@code WHERE}: the payout's
 *       {@code status = ?} edge, the permit's renewal, the hold's {@code ACTIVE} release, the claim's single row.
 *   <li><strong>No locks and one thread</strong>: the races are the database suites'. Here the orders are driven
 *       sequentially, both ways.
 *   <li><strong>The ledger's two readings</strong>: {@link #breakdowns} and {@link #statements} fold the in-memory journal
 *       by {@code JdbcPositionBreakdown}'s and {@code JdbcStatementDerivation}'s rules - the counterparty by the caller's
 *       precedence then line sequence, the namespace by the caller's order against {@code ledger.post:}-scoped keys only.
 * </ul>
 *
 * <p>Both sides of a dispatch are counted (the Phase 11 exit review's owner item #16 applied forward): the platform's -
 * {@code merchant_payout} rows and the adapter's send calls through {@link Counting} - and the provider's, the scheme's
 * received requests on {@link SimulatedPayoutProvider#PAYOUTS_PATH}.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class BnplPayoutWorld implements AutoCloseable {

    static final CurrencyCode EUR = CurrencyCode.of("EUR");
    static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    static final String PAID = "{\"status\":\"paid\",\"reference\":\"po-{{request.headers.Idempotency-Key}}\"}";
    private static final String POSTING_SCOPE = PostingService.IDEMPOTENCY_SCOPE + ":";

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"));
    final IdGenerator ids = new IdGenerator(clock, new SecureRandom());
    final State state = new State();
    final Connection connection = databaseClock(clock);
    final SimulatedProvider scheme = SimulatedProvider.start();

    final Accounts accounts = new Accounts();
    final Journal journal = new Journal();
    final Holds holdStore = new Holds();
    final Claims claims = new Claims();
    final Payouts payoutStore = new Payouts();
    final Merchants merchants = new Merchants();
    final Destinations destinations = new Destinations();
    final Recorded recorded = new Recorded();
    final Breakdowns breakdowns = new Breakdowns();
    final Statements statements = new Statements();

    final MerchantTransactionRunner transactions = this::inTransaction;
    final IdempotentExecutor executor = new IdempotentExecutor(claims, clock, Duration.ofDays(1), Duration.ofSeconds(30));
    final HoldService holds;
    final PostingService postings;
    final MerchantPayoutOutcomes outcomes;
    final Counting wire;
    final MerchantPayouts payouts;
    final MerchantPayable payables;

    BnplPayoutWorld() {
        BalanceProjection<Connection> noProjection = new BalanceProjection<>() {
            @Override
            public void apply(Connection unitOfWork, JournalEntry entry) {}

            @Override
            public void adjustHolds(Connection unitOfWork, LedgerAccountId account, Money delta, Instant at) {}

            @Override
            public void lockInOrder(Connection unitOfWork, Collection<LedgerAccountId> rows) {}
        };
        holds = new HoldService(accounts, journal, holdStore, noProjection, recorded, recorded, ids, clock);
        postings = new PostingService(executor, journal, recorded, recorded, noProjection, ids, clock, PostingObserver.NONE);
        outcomes = new MerchantPayoutOutcomes(payoutStore, holds, postings, new ChartOfAccounts<>(accounts), accounts,
                recorded, recorded, ids, clock, recorded);
        wire = new Counting(new SimulatedPayoutProvider(URI.create(scheme.baseUrl()), Duration.ofMillis(700), new byte[32]));
        payouts = new MerchantPayouts(transactions, executor, merchants, destinations, accounts, holds, payoutStore, wire,
                outcomes, recorded, recorded, ids, clock);
        payables = new MerchantPayable(accounts, breakdowns);
        for (AccountPurpose purpose : List.of(AccountPurpose.PAYOUT_CLEARING, AccountPurpose.SETTLEMENT_CLEARING,
                AccountPurpose.BNPL_MERCHANT_FEE_INCOME, AccountPurpose.CASH_AT_BANK)) {
            operational(purpose, purpose == AccountPurpose.BNPL_MERCHANT_FEE_INCOME ? AccountType.REVENUE
                    : purpose == AccountPurpose.PAYOUT_CLEARING ? AccountType.LIABILITY : AccountType.ASSET);
        }
    }

    @Override
    public void close() {
        scheme.close();
    }

    /** The resolution sweep as one instance would run it - its own object, the world's stores. */
    MerchantPayoutResolution resolver() {
        return new MerchantPayoutResolution(transactions, payoutStore, wire, outcomes, recorded, ids, clock,
                Duration.ofSeconds(30), Duration.ZERO, 50);
    }

    // ------------------------------------------------------------------ the fixtures

    /** A trading merchant: the merchant row, its EUR payable and an effective destination. */
    record Shop(MerchantId id, LedgerAccountId payable, PayoutDestinationId destination) {}

    Shop shop() {
        Merchant merchant = Merchant.onboard(ids, clock, ids.next(), "BNPL Shop Ltd", "BNPL Shop", EUR);
        LedgerAccount payable = LedgerAccount.owned(ids, clock, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE,
                EUR, merchant.id().value());
        // Proposed and approved two days ago, effective after a day's cooling-off - long before any payout here.
        Instant proposed = clock.instant().minus(Duration.ofDays(2));
        Instant coolingOffEnds = proposed.plus(Duration.ofDays(1));
        PayoutDestination destination = PayoutDestination.rehydrate(PayoutDestinationId.next(ids), merchant.id(),
                PayoutDestinationReference.of("pdr_bnpl-16"), "0016", PayoutDestinationStatus.EFFECTIVE, "operator-a",
                proposed, "the merchant's bank account", Optional.of("operator-b"), Optional.of(proposed),
                Optional.of(coolingOffEnds), Optional.of(coolingOffEnds), Optional.empty(), Optional.empty(),
                Optional.empty());
        state.merchants.put(merchant.id(), merchant);
        state.accounts.put(payable.id(), payable);
        state.destinations.put(destination.id(), destination);
        return new Shop(merchant.id(), payable.id(), destination.id());
    }

    /**
     * F1 as bnpl posts it (`PHASE_12_PLAN.md` §6, {@code BnplAcceptances.financingLines}): {@code DR BNPL_PRINCIPAL G;
     * DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f}, keyed {@code bnpl.financing:<p>},
     * through the real {@link PostingService}. Returns the purchase id (the entry's reference).
     */
    UUID financed(Shop shop, long grossMinor, long feeMinor) {
        UUID purchase = ids.next();
        LedgerAccount principal = LedgerAccount.owned(ids, clock, AccountType.ASSET, AccountPurpose.BNPL_PRINCIPAL, EUR,
                ids.next());
        state.accounts.put(principal.id(), principal);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(principal.id(), Direction.DEBIT, eur(grossMinor)));
        lines.add(new JournalLine(shop.payable(), Direction.CREDIT, eur(grossMinor)));
        if (feeMinor > 0) {
            lines.add(new JournalLine(shop.payable(), Direction.DEBIT, eur(feeMinor)));
            lines.add(new JournalLine(operational(AccountPurpose.BNPL_MERCHANT_FEE_INCOME), Direction.CREDIT,
                    eur(feeMinor)));
        }
        post(MerchantPayable.BNPL_FINANCING_NAMESPACE + purchase, purchase.toString(), lines);
        return purchase;
    }

    /**
     * F5's shape under its key (`P12-TSK-021` posts it; the owner-role journal fixture stands in for it here, as the
     * backlog says): {@code DR MERCHANT_PAYABLE R / CR BNPL_PRINCIPAL (R - E); CR CUSTOMER_WALLET E}, and the fee returned
     * {@code DR BNPL_MERCHANT_FEE_INCOME r_f / CR MERCHANT_PAYABLE r_f}. The excess line faces the customer's wallet -
     * the book rail's sale counterparty - which is the whole point of the namespace rule.
     */
    UUID refundedByBnpl(Shop shop, long refundMinor, long excessMinor, long feeReturnedMinor) {
        UUID refund = ids.next();
        LedgerAccount principal = LedgerAccount.owned(ids, clock, AccountType.ASSET, AccountPurpose.BNPL_PRINCIPAL, EUR,
                ids.next());
        LedgerAccount wallet = LedgerAccount.owned(ids, clock, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR,
                ids.next());
        state.accounts.put(principal.id(), principal);
        state.accounts.put(wallet.id(), wallet);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(shop.payable(), Direction.DEBIT, eur(refundMinor)));
        if (refundMinor - excessMinor > 0) {
            lines.add(new JournalLine(principal.id(), Direction.CREDIT, eur(refundMinor - excessMinor)));
        }
        if (excessMinor > 0) {
            lines.add(new JournalLine(wallet.id(), Direction.CREDIT, eur(excessMinor)));
        }
        if (feeReturnedMinor > 0) {
            lines.add(new JournalLine(operational(AccountPurpose.BNPL_MERCHANT_FEE_INCOME), Direction.DEBIT,
                    eur(feeReturnedMinor)));
            lines.add(new JournalLine(shop.payable(), Direction.CREDIT, eur(feeReturnedMinor)));
        }
        post(MerchantPayable.BNPL_REFUND_NAMESPACE + refund, refund.toString(), lines);
        return refund;
    }

    /** A wallet-paid capture's refund (the book rail, `P7-TSK-011`): {@code DR MERCHANT_PAYABLE / CR CUSTOMER_WALLET}. */
    void walletRefund(Shop shop, long refundMinor) {
        LedgerAccount wallet = LedgerAccount.owned(ids, clock, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR,
                ids.next());
        state.accounts.put(wallet.id(), wallet);
        post("payment-refund:" + ids.next(), ids.next().toString(), List.of(
                new JournalLine(shop.payable(), Direction.DEBIT, eur(refundMinor)),
                new JournalLine(wallet.id(), Direction.CREDIT, eur(refundMinor))));
    }

    /** A card capture (ADR-0050 §3): {@code DR SETTLEMENT_CLEARING G / CR MERCHANT_PAYABLE G; DR payable f / CR fees}. */
    void captured(Shop shop, long grossMinor, long feeMinor) {
        LedgerAccountId clearing = operational(AccountPurpose.SETTLEMENT_CLEARING);
        post("payment-execution:" + ids.next(), ids.next().toString(), List.of(
                new JournalLine(clearing, Direction.DEBIT, eur(grossMinor)),
                new JournalLine(shop.payable(), Direction.CREDIT, eur(grossMinor)),
                new JournalLine(shop.payable(), Direction.DEBIT, eur(feeMinor)),
                new JournalLine(operational(AccountPurpose.CASH_AT_BANK), Direction.CREDIT, eur(feeMinor))));
    }

    /** One posting through the real {@link PostingService}, as the platform. */
    void post(String key, String reference, List<JournalLine> lines) {
        asPlatform(() -> transactions.inTransaction(uow -> {
            LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
            return postings.post(uow, new PostingCommand(key, today, today, reference, lines));
        }));
    }

    /** The merchant's own key asking for a payout - the merchant route's actor and a fresh request correlation. */
    MerchantPayouts.Initiated payOut(Shop shop, long amountMinor, String key) {
        try (SecurityContext.Scope merchant = SecurityContext.enter(
                        new com.finapp.platform.security.Actor(shop.id().value().toString(),
                                com.finapp.platform.security.ActorType.MERCHANT));
                CorrelationContext.Scope flow = CorrelationContext.enter(flow())) {
            return payouts.initiate(new MerchantPayouts.InitiateCommand(shop.id(), eur(amountMinor), key,
                    Optional.empty(), Optional.empty()));
        }
    }

    /** The scheme answers a query for {@code payout}'s reference with {@code word}. */
    void queryAnswers(MerchantPayoutId payout, String word) {
        String reference = state.payouts.get(payout).reference().value();
        scheme.succeedsWith(PATH + "/" + reference, 200, word.equals("paid")
                ? "{\"status\":\"paid\",\"reference\":\"po-q-" + reference + "\"}"
                : "{\"status\":\"" + word + "\"}");
    }

    MerchantPayable.Payable payable(Shop shop) {
        return transactions.inTransaction(uow -> payables.payablesOf(uow, shop.id())).get(0);
    }

    long positionMinor(Shop shop) {
        return payable(shop).position().minorUnits();
    }

    /** The payable's available: the settled position less every standing hold - INV-MER-05's arbiter's own read. */
    long availableMinor(Shop shop) {
        long held = state.holds.values().stream()
                .filter(hold -> hold.account().equals(shop.payable()) && hold.status() == HoldStatus.ACTIVE)
                .mapToLong(hold -> hold.amount().minorUnits()).sum();
        return positionMinor(shop) - held;
    }

    List<HoldStatus> holdStatuses(Shop shop) {
        return state.holds.values().stream().filter(hold -> hold.account().equals(shop.payable()))
                .map(Hold::status).toList();
    }

    /** The lines of the entry posted under {@code key}, as {@code DIRECTION:PURPOSE:minor}. */
    List<String> entryLines(String key) {
        return state.entries.stream().filter(posted -> posted.attribution().idempotencyScope().equals(POSTING_SCOPE + key))
                .flatMap(posted -> posted.entry().lines().stream())
                .map(line -> line.direction() + ":" + state.accounts.get(line.account()).purpose() + ":"
                        + line.amount().minorUnits())
                .toList();
    }

    long entriesUnder(String keyPrefix) {
        return state.entries.stream()
                .filter(posted -> posted.attribution().idempotencyScope().startsWith(POSTING_SCOPE + keyPrefix)).count();
    }

    long payoutRows(Shop shop) {
        return state.payouts.values().stream().filter(payout -> payout.merchantId().equals(shop.id())).count();
    }

    MerchantPayout stored(MerchantPayoutId id) {
        return state.payouts.get(id);
    }

    long audited(String action, MerchantPayoutId payout) {
        return state.audit.stream().filter(record -> record.operation().code().equals(action)
                && record.targetId().equals(payout.value().toString())).count();
    }

    long events(String type, MerchantPayoutId payout) {
        return state.outbox.stream().filter(envelope -> envelope.eventType().equals(type)
                && envelope.aggregateId().value().equals(payout.value())).count();
    }

    LedgerAccountId operational(AccountPurpose purpose) {
        return state.accounts.values().stream().filter(a -> a.purpose() == purpose && a.ownerRef().isEmpty())
                .findFirst().orElseThrow().id();
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    Correlation flow() {
        return Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p12t16"));
    }

    private <R> R asPlatform(Supplier<R> work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return work.get();
        }
    }

    private void operational(AccountPurpose purpose, AccountType type) {
        Instant now = clock.instant();
        LedgerAccount account = LedgerAccount.rehydrate(LedgerAccountId.next(ids), type, type.normalBalance(), EUR,
                purpose.ownerKind(), null, purpose, null, LedgerAccountStatus.ACTIVE, now, now);
        state.accounts.put(account.id(), account);
    }

    private <R> R inTransaction(Function<Connection, R> work) {
        State saved = state.copy();
        try {
            return work.apply(connection);
        } catch (RuntimeException rolledBack) {
            state.restore(saved);
            throw rolledBack;
        }
    }

    // ------------------------------------------------------------------ the counting decorator

    /** The platform's side of the dispatch count: every call the payout made on its adapter, sends and queries apart. */
    static final class Counting implements PayoutProvider {

        final AtomicInteger sends = new AtomicInteger();
        final AtomicInteger queries = new AtomicInteger();
        private final PayoutProvider delegate;

        Counting(PayoutProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public PayoutAnswer dispatch(PayoutRequest request) {
            sends.incrementAndGet();
            return delegate.dispatch(request);
        }

        @Override
        public PayoutQueryAnswer query(PayoutReference ourReference) {
            queries.incrementAndGet();
            return delegate.query(ourReference);
        }
    }

    // ------------------------------------------------------------------ the state

    record Posted(JournalEntry entry, PostingAttribution attribution) {}

    /** Every store's rows, in one place, so a transaction can be rolled back as a whole. */
    static final class State {

        Map<LedgerAccountId, LedgerAccount> accounts = new LinkedHashMap<>();
        List<Posted> entries = new ArrayList<>();
        Map<HoldId, Hold> holds = new LinkedHashMap<>();
        Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
        Map<MerchantPayoutId, MerchantPayout> payouts = new LinkedHashMap<>();
        Map<String, MerchantPayoutId> dispatchKeys = new HashMap<>();
        Map<MerchantPayoutId, Instant> enteredAt = new HashMap<>();
        Map<MerchantId, Merchant> merchants = new HashMap<>();
        Map<PayoutDestinationId, PayoutDestination> destinations = new LinkedHashMap<>();
        List<AuditRecord> audit = new ArrayList<>();
        List<EventEnvelope> outbox = new ArrayList<>();
        List<PayoutSettlementExpectations.Opening> expectations = new ArrayList<>();
        List<PayoutEvidenceKind> evidence = new ArrayList<>();

        State copy() {
            State copy = new State();
            copy.accounts = new LinkedHashMap<>(accounts);
            copy.entries = new ArrayList<>(entries);
            copy.holds = new LinkedHashMap<>(holds);
            copy.claims = new HashMap<>(claims);
            copy.payouts = new LinkedHashMap<>(payouts);
            copy.dispatchKeys = new HashMap<>(dispatchKeys);
            copy.enteredAt = new HashMap<>(enteredAt);
            copy.merchants = new HashMap<>(merchants);
            copy.destinations = new LinkedHashMap<>(destinations);
            copy.audit = new ArrayList<>(audit);
            copy.outbox = new ArrayList<>(outbox);
            copy.expectations = new ArrayList<>(expectations);
            copy.evidence = new ArrayList<>(evidence);
            return copy;
        }

        void restore(State saved) {
            accounts = saved.accounts;
            entries = saved.entries;
            holds = saved.holds;
            claims = saved.claims;
            payouts = saved.payouts;
            dispatchKeys = saved.dispatchKeys;
            enteredAt = saved.enteredAt;
            merchants = saved.merchants;
            destinations = saved.destinations;
            audit = saved.audit;
            outbox = saved.outbox;
            expectations = saved.expectations;
            evidence = saved.evidence;
        }
    }

    // ------------------------------------------------------------------ the ledger's stores

    final class Accounts implements LedgerAccountStore<Connection> {

        @Override
        public Creation createOrConverge(Connection unitOfWork, LedgerAccount fresh) {
            return insertIfAbsent(unitOfWork, fresh);
        }

        @Override
        public Creation insertIfAbsent(Connection unitOfWork, LedgerAccount fresh) {
            state.accounts.put(fresh.id(), fresh);
            return new Creation(fresh, true);
        }

        @Override
        public Optional<LedgerAccount> findOwned(
                Connection unitOfWork, UUID ownerRef, AccountPurpose purpose, CurrencyCode currency) {
            return state.accounts.values().stream().filter(a -> a.ownerRef().equals(Optional.of(ownerRef))
                    && a.purpose() == purpose && a.currency().equals(currency)).findFirst();
        }

        @Override
        public Optional<LedgerAccount> findOperational(Connection unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
            return state.accounts.values().stream().filter(a -> a.ownerRef().isEmpty() && a.purpose() == purpose
                    && a.currency().equals(currency)).findFirst();
        }

        @Override
        public Optional<LedgerAccount> findCounterpartyAccount(
                Connection unitOfWork, AccountPurpose purpose, String counterpartyCode, CurrencyCode currency) {
            throw new UnsupportedOperationException("no counterparty account in the payout world");
        }

        @Override
        public List<LedgerAccount> findAllOfPurpose(Connection unitOfWork, AccountPurpose purpose) {
            return state.accounts.values().stream().filter(a -> a.purpose() == purpose).toList();
        }

        @Override
        public List<LedgerAccount> findAllOwned(Connection unitOfWork, UUID ownerRef) {
            // owner_ref = ? in the ledger's statement: the owner-scoped read MerchantPayable trusts.
            return state.accounts.values().stream().filter(a -> a.ownerRef().equals(Optional.of(ownerRef))).toList();
        }

        @Override
        public List<LedgerAccount> findAllById(Connection unitOfWork, Collection<LedgerAccountId> ids) {
            return ids.stream().map(state.accounts::get).filter(Objects::nonNull).toList();
        }

        @Override
        public List<LedgerAccount> lockOwnedForUpdate(Connection unitOfWork, UUID ownerRef) {
            return findAllOwned(unitOfWork, ownerRef);
        }

        @Override
        public Optional<LedgerAccount> lockForUpdate(Connection unitOfWork, LedgerAccountId accountId) {
            return Optional.ofNullable(state.accounts.get(accountId));
        }

        @Override
        public Optional<LedgerAccount> lockForShare(Connection unitOfWork, LedgerAccountId accountId) {
            return Optional.ofNullable(state.accounts.get(accountId));
        }

        @Override
        public boolean moveStatus(Connection unitOfWork, LedgerAccountId accountId, LedgerAccountStatus from,
                LedgerAccountStatus to, Instant at) {
            throw new UnsupportedOperationException("no account moves in the payout world");
        }
    }

    /** The journal and its definition of a settled balance: every line folded, signed by the normal balance. */
    final class Journal implements JournalEntryStore<Connection>, BalanceDerivation<Connection> {

        @Override
        public void append(Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
            if (state.entries.stream().anyMatch(p -> p.attribution().idempotencyScope()
                    .equals(attribution.idempotencyScope()))) {
                throw new IllegalStateException("journal_entry.idempotency_scope is UNIQUE");
            }
            state.entries.add(new Posted(entry, attribution));
        }

        @Override
        public Optional<PostedEntry> findById(Connection unitOfWork, JournalEntryId entryId) {
            return state.entries.stream().filter(p -> p.entry().id().equals(entryId)).findFirst()
                    .map(p -> new PostedEntry(p.entry(), p.attribution()));
        }

        @Override
        public List<JournalLine> reversalLinesOf(Connection unitOfWork, JournalEntryId original) {
            return List.of();
        }

        @Override
        public Optional<JournalEntryId> findByIdempotencyScope(Connection unitOfWork, String scope) {
            return state.entries.stream().filter(p -> p.attribution().idempotencyScope().equals(scope)).findFirst()
                    .map(p -> p.entry().id());
        }

        @Override
        public List<LineKey> lineKeysOn(Connection unitOfWork, Collection<LedgerAccountId> onAccounts) {
            throw new UnsupportedOperationException("no line keys in the payout world");
        }

        @Override
        public DerivedBalance derive(Connection unitOfWork, LedgerAccountId account, AsOf asOf) {
            return new DerivedBalance(account, settled(account, state.entries));
        }
    }

    Money settled(LedgerAccountId account, List<Posted> entries) {
        LedgerAccount owner = state.accounts.get(account);
        Money debits = Money.zero(owner.currency());
        Money credits = Money.zero(owner.currency());
        for (Posted posted : entries) {
            for (JournalLine line : posted.entry().lines()) {
                if (line.account().equals(account)) {
                    if (line.direction() == Direction.DEBIT) {
                        debits = debits.plus(line.amount());
                    } else {
                        credits = credits.plus(line.amount());
                    }
                }
            }
        }
        return owner.normalBalance() == NormalBalance.DEBIT ? debits.minus(credits) : credits.minus(debits);
    }

    /** {@code JdbcPositionBreakdown}'s rules over the in-memory journal. */
    final class Breakdowns implements PositionBreakdown<Connection> {

        @Override
        public Breakdown breakdown(Connection unitOfWork, LedgerAccountId account, List<AccountPurpose> precedence,
                List<String> namespaces) {
            List<String> prefixes = PositionBreakdown.checkedNamespaces(namespaces);
            Map<String, Bucket> buckets = new LinkedHashMap<>();
            for (Posted posted : state.entries) {
                for (JournalLine line : posted.entry().lines()) {
                    if (!line.account().equals(account)) {
                        continue;
                    }
                    Optional<Counterparty> other = counterpartyOf(posted, precedence);
                    Optional<String> namespace = namespaceOf(posted, prefixes);
                    String key = line.direction() + "|" + namespace.orElse("") + "|"
                            + other.map(c -> c.purpose() + ":" + c.direction()).orElse("NONE");
                    Bucket so = buckets.get(key);
                    buckets.put(key, new Bucket(line.direction(), other,
                            so == null ? line.amount() : so.total().plus(line.amount()), false, namespace));
                }
            }
            return new Breakdown(account, settled(account, state.entries), new ArrayList<>(buckets.values()));
        }

        private Optional<Counterparty> counterpartyOf(Posted posted, List<AccountPurpose> precedence) {
            JournalLine best = null;
            int rank = Integer.MAX_VALUE;
            for (JournalLine line : posted.entry().lines()) {
                int at = precedence.indexOf(state.accounts.get(line.account()).purpose());
                if (at >= 0 && at < rank) {
                    rank = at;
                    best = line;
                }
            }
            return best == null ? Optional.empty()
                    : Optional.of(new Counterparty(state.accounts.get(best.account()).purpose(), best.direction()));
        }
    }

    static Optional<String> namespaceOf(Posted posted, List<String> prefixes) {
        String scope = posted.attribution().idempotencyScope();
        return prefixes.stream().filter(prefix -> scope.startsWith(POSTING_SCOPE + prefix)).findFirst();
    }

    /** {@code JdbcStatementDerivation}'s rules over the in-memory journal: every owned account, every line in the period. */
    final class Statements implements StatementDerivation<Connection> {

        @Override
        public List<AccountStatement> statementsFor(Connection unitOfWork, UUID ownerRef, LocalDate from, LocalDate to,
                List<String> namespaces) {
            List<String> prefixes = PositionBreakdown.checkedNamespaces(namespaces);
            List<AccountStatement> statements = new ArrayList<>();
            for (LedgerAccount owned : accounts.findAllOwned(unitOfWork, ownerRef)) {
                List<Posted> before = state.entries.stream().filter(p -> p.entry().postingDate().isBefore(from)).toList();
                Money opening = settled(owned.id(), before);
                List<StatementLine> lines = new ArrayList<>();
                List<Posted> during = state.entries.stream().filter(p -> !p.entry().postingDate().isBefore(from)
                        && !p.entry().postingDate().isAfter(to)).toList();
                for (Posted posted : during) {
                    for (JournalLine line : posted.entry().lines()) {
                        if (line.account().equals(owned.id())) {
                            lines.add(new StatementLine(posted.entry().id(), posted.entry().postingDate(),
                                    posted.entry().valueDate(), JournalEntryType.POSTING, posted.attribution().reference(),
                                    line.direction(), line.amount(), false, namespaceOf(posted, prefixes)));
                        }
                    }
                }
                statements.add(new AccountStatement(owned.id(), opening, lines,
                        opening.plus(settled(owned.id(), during))));
            }
            return statements;
        }
    }

    final class Holds implements HoldStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, Hold hold) {
            state.holds.put(hold.id(), hold);
        }

        @Override
        public Optional<Hold> findById(Connection unitOfWork, HoldId id) {
            return Optional.ofNullable(state.holds.get(id));
        }

        @Override
        public List<Hold> findActiveFor(Connection unitOfWork, LedgerAccountId account) {
            return state.holds.values().stream()
                    .filter(hold -> hold.account().equals(account) && hold.status() == HoldStatus.ACTIVE).toList();
        }

        @Override
        public long countActive(Connection unitOfWork) {
            return state.holds.values().stream().filter(hold -> hold.status() == HoldStatus.ACTIVE).count();
        }

        @Override
        public boolean moveToReleased(Connection unitOfWork, HoldId id, Instant at) {
            Hold hold = state.holds.get(id);
            if (hold == null || hold.status() != HoldStatus.ACTIVE) {
                return false; // WHERE status = 'ACTIVE'
            }
            state.holds.put(id, hold.release(clock));
            return true;
        }
    }

    // ------------------------------------------------------------------ the platform's stores

    /** The idempotency claim's statements: one row per key, the lease measured on the world's clock. */
    final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint,
                CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (state.claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            state.claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS,
                    StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(state.claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState,
                StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = state.claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false; // WHERE state = 'IN_PROGRESS'
            }
            state.claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response,
                    held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId,
                Instant now, Instant expiresAt, Duration lease) {
            IdempotencyRecord held = state.claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS
                    || held.createdAt().plus(lease).isAfter(now)) {
                return false;
            }
            state.claims.put(key, new IdempotencyRecord(key, held.fingerprint(), IdempotencyState.IN_PROGRESS,
                    held.response(), correlationId, now, expiresAt));
            return true;
        }
    }

    /** The audit trail, the outbox, the payout's evidence and its expectation seam - appended, never read back. */
    final class Recorded implements AuditWriter<Connection>, OutboxWriter<Connection>, PayoutEvidenceStore<Connection>,
            PayoutSettlementExpectations {

        @Override
        public void append(Connection unitOfWork, AuditRecord record) {
            state.audit.add(record);
        }

        @Override
        public void write(Connection unitOfWork, EventEnvelope envelope, byte[] payload, String payloadMediaType) {
            state.outbox.add(envelope);
        }

        @Override
        public void append(Connection unitOfWork, MerchantPayoutId payout, PayoutEvidenceKind kind, byte[] payload,
                Instant recordedAt) {
            state.evidence.add(kind);
        }

        @Override
        public void open(Connection unitOfWork, Opening opening) {
            state.expectations.add(opening);
        }
    }

    // ------------------------------------------------------------------ merchant's stores

    /** {@code JdbcMerchantPayoutStore}'s statements: the dispatch key UNIQUE per merchant, every edge conditional. */
    final class Payouts implements MerchantPayoutStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, MerchantPayout dispatched, String dispatchKey) {
            String unique = dispatched.merchantId().value() + "|" + dispatchKey;
            if (state.dispatchKeys.containsKey(unique)) {
                throw new IllegalStateException("merchant_payout (merchant_id, dispatch_key) is UNIQUE");
            }
            state.dispatchKeys.put(unique, dispatched.id());
            state.payouts.put(dispatched.id(), dispatched);
            state.enteredAt.put(dispatched.id(), dispatched.createdAt());
        }

        @Override
        public Optional<MerchantPayout> find(Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
            return Optional.ofNullable(state.payouts.get(id)).filter(payout -> payout.merchantId().equals(merchant));
        }

        @Override
        public Optional<MerchantPayout> findForUpdate(Connection unitOfWork, MerchantId merchant, MerchantPayoutId id) {
            return find(unitOfWork, merchant, id);
        }

        @Override
        public Optional<MerchantPayout> findByDispatchKey(Connection unitOfWork, MerchantId merchant, String dispatchKey) {
            return Optional.ofNullable(state.dispatchKeys.get(merchant.value() + "|" + dispatchKey))
                    .map(state.payouts::get);
        }

        @Override
        public Optional<MerchantPayout> findByProviderReference(Connection unitOfWork, String providerReference) {
            return state.payouts.values().stream().filter(payout -> payout.providerReference()
                    .map(theirs -> theirs.value().equals(providerReference)).orElse(false)).findFirst();
        }

        @Override
        public Optional<MerchantPayout> findByReference(Connection unitOfWork, String reference) {
            return state.payouts.values().stream().filter(payout -> payout.reference().value().equals(reference))
                    .findFirst();
        }

        @Override
        public Optional<MerchantPayout> lockByProviderReference(Connection unitOfWork, String providerReference) {
            return findByProviderReference(unitOfWork, providerReference);
        }

        @Override
        public Optional<MerchantPayout> lockByReference(Connection unitOfWork, String reference) {
            return findByReference(unitOfWork, reference);
        }

        @Override
        public boolean renewSendPermit(Connection unitOfWork, MerchantPayout before) {
            MerchantPayout row = state.payouts.get(before.id());
            if (row == null || !row.status().isResolvable() || row.lastDispatchedAt().isAfter(before.lastDispatchedAt())) {
                return false;
            }
            Instant renewed = clock.instant().isAfter(row.lastDispatchedAt()) ? clock.instant()
                    : row.lastDispatchedAt().plusNanos(1_000);
            state.payouts.put(row.id(), row.withSendPermit(renewed));
            return true;
        }

        @Override
        public boolean transition(Connection unitOfWork, MerchantPayout before, MerchantPayout after, Instant at) {
            MerchantPayout row = state.payouts.get(before.id());
            if (row == null || row.status() != before.status()) {
                return false; // WHERE status = ?
            }
            state.payouts.put(after.id(), after);
            state.enteredAt.put(after.id(), at);
            return true;
        }

        @Override
        public List<MerchantPayout> pageCompleted(Connection unitOfWork, UUID after, int limit) {
            return List.of();
        }

        @Override
        public List<MerchantPayout> findSweepable(Connection unitOfWork, Instant dispatchedBefore, Instant unknownBefore,
                int limit) {
            return state.payouts.values().stream()
                    .filter(p -> (p.status() == MerchantPayoutStatus.DISPATCHED
                                    && !p.lastDispatchedAt().isAfter(dispatchedBefore))
                            || (p.status() == MerchantPayoutStatus.UNKNOWN
                                    && !state.enteredAt.get(p.id()).isAfter(unknownBefore)))
                    .limit(limit).toList();
        }

        @Override
        public UnknownReading unknownReading(Connection unitOfWork, Duration dispatchedBound) {
            return new UnknownReading(0, 0);
        }
    }

    final class Merchants implements MerchantStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, Merchant merchant) {
            state.merchants.put(merchant.id(), merchant);
        }

        @Override
        public Optional<Merchant> findById(Connection unitOfWork, MerchantId id) {
            return Optional.ofNullable(state.merchants.get(id));
        }

        @Override
        public Optional<Merchant> findByIdForUpdate(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public Optional<Merchant> findByIdForShare(Connection unitOfWork, MerchantId id) {
            return findById(unitOfWork, id);
        }

        @Override
        public boolean transition(Connection unitOfWork, Merchant before, Merchant transitioned) {
            throw new UnsupportedOperationException("no merchant moves in the payout world");
        }
    }

    final class Destinations implements PayoutDestinationStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, PayoutDestination proposed) {
            state.destinations.put(proposed.id(), proposed);
        }

        @Override
        public Optional<PayoutDestination> find(Connection unitOfWork, MerchantId merchant, PayoutDestinationId id) {
            return Optional.ofNullable(state.destinations.get(id)).filter(d -> d.merchantId().equals(merchant));
        }

        @Override
        public Optional<PayoutDestination> findForUpdate(Connection unitOfWork, MerchantId merchant,
                PayoutDestinationId id) {
            return find(unitOfWork, merchant, id);
        }

        @Override
        public Optional<PayoutDestination> findOpen(Connection unitOfWork, MerchantId merchant) {
            return Optional.empty();
        }

        @Override
        public Optional<PayoutDestination> findEffective(Connection unitOfWork, MerchantId merchant) {
            return state.destinations.values().stream().filter(d -> d.merchantId().equals(merchant)
                    && d.status() == PayoutDestinationStatus.EFFECTIVE).findFirst();
        }

        @Override
        public Optional<PayoutDestination> findEffectiveForUpdate(Connection unitOfWork, MerchantId merchant) {
            return findEffective(unitOfWork, merchant);
        }

        @Override
        public Optional<PayoutDestination> findEffectiveForShare(Connection unitOfWork, MerchantId merchant) {
            return findEffective(unitOfWork, merchant);
        }

        @Override
        public List<PayoutDestination> findDue(Connection unitOfWork, Instant now, int limit) {
            return List.of();
        }

        @Override
        public boolean transition(Connection unitOfWork, PayoutDestination before, PayoutDestination after) {
            throw new UnsupportedOperationException("no destination moves in the payout world");
        }

        @Override
        public long countOpen(Connection unitOfWork) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ the database clock

    /**
     * A connection that answers the database clock and nothing else: {@code DatabaseTime.now} reads
     * {@code SELECT statement_timestamp()} through it, from the world's own clock.
     */
    private static Connection databaseClock(Clock clock) {
        return (Connection) Proxy.newProxyInstance(BnplPayoutWorld.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "createStatement" -> statement(clock);
                    case "toString" -> "BnplPayoutWorld.CONNECTION";
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the in-memory stores issue no SQL: " + method.getName());
                });
    }

    private static Statement statement(Clock clock) {
        return (Statement) Proxy.newProxyInstance(BnplPayoutWorld.class.getClassLoader(), new Class<?>[] {Statement.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "executeQuery" -> {
                        if (!"SELECT statement_timestamp()".equals(args[0])) {
                            throw new UnsupportedOperationException("only the database clock: " + args[0]);
                        }
                        yield now(clock);
                    }
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static ResultSet now(Clock clock) {
        boolean[] read = {false};
        return (ResultSet) Proxy.newProxyInstance(BnplPayoutWorld.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> {
                        boolean first = !read[0];
                        read[0] = true;
                        yield first;
                    }
                    case "getTimestamp" -> Timestamp.from(clock.instant());
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** A clock the world moves on purpose - never ambient time. */
    static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    // ------------------------------------------------------------------ the merchant transaction report

    /**
     * {@code GET /v1/merchant/transactions}' report over the world's statement reading, the other modules' stores as the
     * test supplies them. Built here, not in the test class, so the hermetic suite names no {@code DataSource} - the
     * taxonomy's database signature - for the one-connection stand-in Spring's {@code DataSourceUtils} is handed.
     */
    com.finapp.app.merchant.MerchantTransactionReport transactionReport(
            com.finapp.payments.PaymentAttemptStore<Connection> attempts, com.finapp.payments.RefundStore<Connection> refunds,
            com.finapp.checkout.CheckoutSessionStore<Connection> sessions, com.finapp.checkout.OrderStore<Connection> orders) {
        return new com.finapp.app.merchant.MerchantTransactionReport(statements, attempts, refunds, sessions, orders,
                new org.springframework.transaction.support.TransactionTemplate(new NoTransactions()),
                new OneConnection(connection));
    }


    /** A transaction manager with nothing to manage: the world's stores roll back on their own. */
    private static final class NoTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {

        private static final long serialVersionUID = 1L;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {}

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {}
    }

    /** The world's one connection, handed to {@code DataSourceUtils}. */
    private record OneConnection(Connection connection) implements javax.sql.DataSource {

        @Override
        public Connection getConnection() {
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) {
            return connection;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
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
