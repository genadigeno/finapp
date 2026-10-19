package com.finapp.bnpl;

import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.correlation;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.customer;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.employee;
import static com.finapp.bnpl.BnplAcceptanceDatabaseTest.inOneTransaction;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The condition and late-fee database suites' world (`P12-TSK-020`): bnpl's JDBC stores on a database clock moved by
 * {@link BnplClockFrame} (the date-shifted owner-role fixture of the lending precedent: the clock moves, the rows stay),
 * the scripted ports, {@link BnplBillingDatabaseTest}'s in-memory book behind one lock; T-pur, T-svc (collection,
 * closure, condition and late fee), T-rep and T-ref. Terms with the plan's late fee are activated for one purchase and the
 * suite's default restored at once, so every other suite services as before.
 */
final class BnplConditionDatabaseWorld {

    static final Clock CLOCK = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CurrencyCode EUR = BnplTermsFixtures.EUR;
    static final JdbcBnplOfferStore OFFERS = new JdbcBnplOfferStore();
    static final JdbcBnplPurchaseStore PURCHASES = new JdbcBnplPurchaseStore();
    static final JdbcBnplServicingStore SERVICING = new JdbcBnplServicingStore();
    static final JdbcBnplRepaymentStore REPAYMENTS = new JdbcBnplRepaymentStore();
    static final JdbcBnplTermsStore TERMS_STORE = new JdbcBnplTermsStore();
    static final JdbcBnplRefundStore REFUNDS = new JdbcBnplRefundStore();
    static final JdbcBnplConditionStore CONDITIONS = new JdbcBnplConditionStore();

    /** One purchased obligation. */
    record Purchased(BnplPurchaseId purchase, BnplObligationId obligation, UUID party, UUID merchant,
            LocalDate purchaseDate, BnplPurchaseStore.StoredSchedule schedule, BnplAgreementId agreement) {

        String id() {
            return obligation.value().toString();
        }

        LocalDate due(int sequence) {
            return schedule.instalments().get(sequence - 1).dueDate();
        }

        UUID instalment(int sequence) {
            return schedule.instalments().get(sequence - 1).id();
        }
    }

    /** A step of a concurrent race. */
    @FunctionalInterface
    interface Racer<R> {
        R run(int index) throws Exception;
    }

    final BnplAcceptanceDatabaseTest.Scripted ports = new BnplAcceptanceDatabaseTest.Scripted();
    final BnplApplicationFakes fakes = new BnplApplicationFakes();
    final BnplBillingDatabaseTest.SharedBook book = new BnplBillingDatabaseTest.SharedBook(fakes.book);
    final BnplClosures closures = new BnplClosures(REPAYMENTS, SERVICING, PURCHASES, ports, book, new JdbcOutboxWriter(),
            IDS, CLOCK);
    final BnplRepayments repayments = new BnplRepayments(REPAYMENTS, SERVICING, PURCHASES, TERMS_STORE, ports, book,
            closures, new JdbcOutboxWriter(), IDS, CLOCK);
    final BnplConditions conditions = new BnplConditions(CONDITIONS, PURCHASES, TERMS_STORE, book,
            new JdbcOutboxWriter(), IDS, CLOCK);
    final BnplServicing servicing = new BnplServicing(SERVICING, PURCHASES, TERMS_STORE, book, closures,
            new BnplCollections(new JdbcBnplCollectionStore(), repayments, PURCHASES, ports, book, new JdbcOutboxWriter(),
                    IDS, CLOCK),
            conditions, new JdbcOutboxWriter(), IDS, CLOCK);
    final BnplRefunds refunds = new BnplRefunds(REFUNDS, SERVICING, PURCHASES, TERMS_STORE, ports, ports, book, closures,
            (uow, actor) -> Optional.empty(), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
    private final BnplAcceptances acceptances = new BnplAcceptances(OFFERS, TERMS_STORE, PURCHASES, ports,
            (uow, party) -> true, ports, ports, ports, ports, book, new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS,
            CLOCK);

    BnplConditionDatabaseWorld() {
        fakes.book.external("payable", ports.payable.value());
        fakes.book.external("wallet", ports.wallet.value());
        fakes.book.funds.put(ports.wallet, Money.ofMinorUnits(1_000_000_00L, EUR));
    }

    /** A pay-in-4 purchase under {@code wanted} - the suite's default terms (no fee, no collection) restored after. */
    Purchased purchaseUnder(BnplTerms wanted) {
        activate(wanted);
        try {
            return purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        } finally {
            activate(BnplTermsFixtures.payIn4().build());
        }
    }

    /** A pay-in-4 purchase whose terms carry the plan's late fee (EUR 5.00 on day 5, grace 3, cap 15.00). */
    Purchased feeBearing() {
        return purchaseUnder(BnplTermsFixtures.payIn4().lateFee(eur(5_00)).build());
    }

    /** A pay-in-4 purchase under the suite's default terms - no fee. */
    Purchased plain() {
        return purchase(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
    }

    private static void activate(BnplTerms wanted) {
        BnplTermsAdministration terms = new BnplTermsAdministration(TERMS_STORE, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);
        BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> terms.propose(uow, wanted,
                "the condition suite's terms", employee(), correlation()));
        inOneTransaction(uow -> terms.approve(uow, proposed.id(), employee(), "in force", correlation()));
    }

    private Purchased purchase(BnplProduct product, BnplPlan plan) {
        BnplAcceptanceDatabaseTest.Offered offered = BnplAcceptanceDatabaseTest.offered(product, plan);
        ports.session = offered.session();
        ports.merchant = offered.merchant();
        String hash = inOneTransaction(uow -> OFFERS.terms(uow, offered.offer()).orElseThrow().termsSha256Hex());
        BnplAcceptances.Accepted accepted = inOneTransaction(uow -> acceptances.accept(uow, offered.offer(),
                offered.party(), new BnplAcceptances.Acceptance(hash, new BnplAcceptanceEvidence(UUID.randomUUID(),
                        UUID.randomUUID(), "MULTI_FACTOR", Optional.empty(), Optional.empty())),
                customer(offered.party()), Correlation.startingWith(correlation())));
        BnplPurchaseStore.StoredSchedule schedule = inOneTransaction(uow -> PURCHASES.schedule(uow,
                accepted.obligation().id(), 1)).orElseThrow();
        return new Purchased(accepted.purchase().id(), accepted.obligation().id(), offered.party(), offered.merchant(),
                accepted.purchase().businessDate(), schedule, accepted.agreement().id());
    }

    Duration frameOn(LocalDate date) {
        return BnplClockFrame.frameTo(date.atTime(12, 0).toInstant(ZoneOffset.UTC));
    }

    @SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
    ServicingOutcome serviceOn(LocalDate date, Purchased bought) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            return BnplClockFrame.inOneTransaction(frameOn(date), uow -> servicing.service(uow, bought.obligation()));
        }
    }

    BnplRepayments.Repaid repayOn(LocalDate date, Purchased bought, String key, long minor) {
        return BnplClockFrame.inOneTransaction(frameOn(date), uow -> repayments.repay(uow, bought.agreement(),
                bought.party(), new BnplRepayments.Request(key, eur(minor)), customer(bought.party()), correlation()));
    }

    BnplRefunds.Refunded refundOn(LocalDate date, Purchased bought, String key, long minor) {
        Actor merchant = new Actor(bought.merchant().toString(), ActorType.MERCHANT);
        return BnplClockFrame.inOneTransaction(frameOn(date), uow -> refunds.atMerchant(uow, bought.purchase(),
                bought.merchant(), new BnplRefunds.Request(key, eur(minor), "goods returned",
                        Optional.of("key-under-test")), merchant, correlation()));
    }

    Money balance(Purchased bought, com.finapp.ledger.AccountPurpose purpose) {
        return book.balanceOf(null, book.accountsOf(null, bought.obligation()).stream()
                .filter(account -> account.purpose() == purpose).findFirst().orElseThrow());
    }

    <R> List<R> concurrently(int racers, Racer<R> racer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<R>> running = new ArrayList<>();
            for (int index = 0; index < racers; index++) {
                int which = index;
                running.add(pool.submit((Callable<R>) () -> {
                    start.await();
                    return racer.run(which);
                }));
            }
            start.countDown();
            List<R> results = new ArrayList<>();
            for (Future<R> result : running) {
                results.add(result.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
