package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The hermetic world of the condition and late-fee suites (`P12-TSK-020`): {@link BnplApplicationFakes} wired with T-pur,
 * T-svc (its collection and its condition and late-fee slot), T-rep and T-ref, a customer with a wallet, a merchant with
 * its payable - and pay-in-4 terms that carry the plan's late fee (EUR 5.00 on day 5 after 3 days' grace, capped at
 * EUR 15.00; {@link BnplTermsFixtures} carries none, so every earlier suite services as it did). Pay-in-4 of
 * {@link #GROSS} bought on {@link #PURCHASE_DATE}: four instalments of 150.00, the first paid at purchase, then due on
 * {@link #DUE_2}, {@link #DUE_3} and {@link #DUE_4}.
 */
@SuppressWarnings("try") // The SecurityContext Scope is used for its close side effect (the idiom).
final class BnplConditionWorld {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-020-unit");
    static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    static final long GROSS = 600_00L;
    static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);
    static final LocalDate DUE_2 = LocalDate.of(2026, 10, 31);
    static final LocalDate DUE_3 = LocalDate.of(2026, 11, 14);
    static final LocalDate DUE_4 = LocalDate.of(2026, 11, 28);

    final BnplApplicationFakes fakes = new BnplApplicationFakes();
    final BnplApplications applications;
    final BnplOrigination origination;
    final BnplAcceptances acceptances;
    final BnplClosures closures;
    final BnplConditions conditions;
    final BnplServicing servicing;
    final BnplRepayments repayments;
    final BnplRefunds refunds;
    final UUID party = UUID.randomUUID();
    final Actor customer = new Actor(party.toString(), ActorType.CUSTOMER);
    final UUID merchant = UUID.randomUUID();
    final Actor merchantActor = new Actor(merchant.toString(), ActorType.MERCHANT);
    final LedgerAccountId payable;
    final LedgerAccountId wallet;
    private int termsVersion = 20;

    /** An accepted purchase: its id, agreement and obligation. */
    record Agreement(BnplPurchaseId purchase, BnplAgreementId agreement, BnplObligationId obligation) {}

    BnplConditionWorld() {
        BnplTermsAdministration terms = new BnplTermsAdministration(fakes.terms.store(), fakes.audit, fakes.outbox, IDS,
                CLOCK, BnplTermsFixtures.BOUNDS);
        applications = new BnplApplications(fakes.store, terms, fakes.standing, fakes.sessions, fakes.merchants,
                BnplTermsFixtures.BOUNDS, fakes.credit, fakes.outbox, fakes.audit, IDS, CLOCK);
        origination = new BnplOrigination(fakes.store, fakes.offers, terms, fakes.credit, fakes.sessions, fakes.outbox,
                IDS, CLOCK);
        acceptances = new BnplAcceptances(fakes.offers, fakes.terms.store(), fakes.purchases, fakes.credit,
                fakes.standing, fakes.sessions, fakes.merchants, fakes.capital, fakes.wallets, fakes.book, fakes.outbox,
                fakes.audit, IDS, CLOCK);
        closures = new BnplClosures(fakes.repayments, fakes.servicing, fakes.purchases, fakes.wallets, fakes.book,
                fakes.outbox, IDS, CLOCK);
        conditions = fakes.conditionsOver(IDS, CLOCK);
        servicing = new BnplServicing(fakes.servicing, fakes.purchases, fakes.terms.store(), fakes.book, closures,
                fakes.collectionsOver(closures, IDS, CLOCK), conditions, fakes.outbox, IDS, CLOCK);
        repayments = new BnplRepayments(fakes.repayments, fakes.servicing, fakes.purchases, fakes.terms.store(),
                fakes.wallets, fakes.book, closures, fakes.outbox, IDS, CLOCK);
        refunds = new BnplRefunds(fakes.refunds, fakes.servicing, fakes.purchases, fakes.terms.store(), fakes.merchants,
                fakes.wallets, fakes.book, closures, (uow, actor) -> Optional.empty(), fakes.outbox, fakes.audit, IDS,
                CLOCK);
        fakes.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        payable = fakes.book.external("payable", IDS.next());
        fakes.merchants.payables.put(merchant, payable);
        wallet = fakes.book.external("wallet", IDS.next());
        fakes.book.funds.put(wallet, eur(1_000_00));
        fakes.wallets.byParty.put(party, wallet);
        withFees(BnplTermsFixtures.payIn4());
        fakes.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    /** Pay-in-4 terms from {@code terms} with the plan's late fee (EUR 5.00), for the next purchases. */
    void withFees(BnplTerms.BnplTermsBuilder terms) {
        fakes.terms.activate(terms.lateFee(eur(5_00)).build(), termsVersion++);
    }

    /** Pay-in-4 terms exactly {@code terms}, for the next purchases. */
    void activate(BnplTerms terms) {
        fakes.terms.activate(terms, termsVersion++);
    }

    Agreement payIn4() {
        return accept(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
    }

    Agreement accept(BnplProduct product, BnplPlan plan) {
        fakes.now = Instant.parse("2026-10-17T10:10:00Z");
        String token = fakes.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant, eur(GROSS),
                Instant.parse("2026-10-17T11:00:00Z"), true, false));
        BnplApplication application = fakes.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, party,
                new BnplApplications.Submission(Sensitive.of(token), product, plan), customer, CORRELATION));
        fakes.credit.approve(application.decisionRequest(), GROSS, fakes.now.plus(Duration.ofDays(1)));
        fakes.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(), SYSTEM));
        BnplOffer offer = fakes.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
        String hash = fakes.offers.terms.get(offer.id().value()).termsSha256Hex();
        BnplAcceptanceEvidence evidence = new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "MULTI_FACTOR", Optional.empty(), Optional.empty());
        BnplAcceptances.Accepted accepted = fakes.inTransaction(() -> acceptances.accept(BnplApplicationFakes.UOW,
                offer.id(), party, new BnplAcceptances.Acceptance(hash, evidence), customer,
                Correlation.startingWith(CORRELATION)));
        return new Agreement(accepted.purchase().id(), accepted.agreement().id(), accepted.obligation().id());
    }

    /** Pay-in-N over twelve months - long enough for a cure that is no closure. */
    Agreement payIn12() {
        return accept(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_12_MONTHS);
    }

    /**
     * Pay-in-N's step on {@code date}: the owner-role accrual fixture (T-acc's record, 10 cents a day) through the date's
     * eve first, as the sweep runs T-acc before T-svc - then the step.
     */
    ServicingOutcome serviceAccruedOn(LocalDate date, Agreement agreement) {
        LocalDate from = fakes.servicing.latestAccrualDate(BnplApplicationFakes.UOW, agreement.obligation())
                .map(latest -> latest.plusDays(1)).orElse(PURCHASE_DATE);
        for (LocalDate day = from; day.isBefore(date); day = day.plusDays(1)) {
            fakes.servicing.accrue(agreement.obligation(), day, eur(10));
        }
        return serviceOn(date, agreement);
    }

    /** The customer repays exactly what every billed instalment has due on {@code date}. */
    BnplRepayments.Repaid repayEverythingDueOn(LocalDate date, Agreement agreement) {
        long due = 0;
        for (BnplPurchaseStore.BilledItem item : fakes.purchases.billedItems(BnplApplicationFakes.UOW,
                agreement.obligation())) {
            due += item.dueTotal().minorUnits();
        }
        return repayOn(date, agreement, "everything due", due);
    }

    /** One servicing step at 09:00 UTC on {@code date}, the sweep's system actor in scope. */
    ServicingOutcome serviceOn(LocalDate date, Agreement agreement) {
        fakes.now = date.atTime(9, 0).toInstant(ZoneOffset.UTC);
        try (SecurityContext.Scope system = SecurityContext.enter(SYSTEM)) {
            return fakes.inTransaction(() -> servicing.service(BnplApplicationFakes.UOW, agreement.obligation()));
        }
    }

    BnplRepayments.Repaid repayOn(LocalDate date, Agreement agreement, String key, long minor) {
        fakes.now = date.atTime(8, 0).toInstant(ZoneOffset.UTC);
        return fakes.inTransaction(() -> repayments.repay(BnplApplicationFakes.UOW, agreement.agreement(), party,
                new BnplRepayments.Request(key, eur(minor)), customer, CORRELATION));
    }

    BnplRefunds.Refunded refundOn(LocalDate date, Agreement agreement, String key, long minor) {
        fakes.now = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        return fakes.inTransaction(() -> refunds.atMerchant(BnplApplicationFakes.UOW, agreement.purchase(), merchant,
                new BnplRefunds.Request(key, eur(minor), "the goods came back", Optional.of("merchant-key-1")),
                merchantActor, CORRELATION));
    }

    /** The obligation's recorded changes as {@code date kind dpd bucket defaulted [previous]}, in order. */
    List<String> history(Agreement agreement) {
        return fakes.conditions.of(agreement.obligation()).stream()
                .map(row -> row.businessDate() + " " + row.kind() + " " + row.state().daysPastDue() + " "
                        + row.state().bucketName() + " " + row.state().defaulted()
                        + (row.kind() == BnplConditionKind.DELINQUENCY ? " from " + row.previousBucketName() : ""))
                .toList();
    }

    /** The obligation's fees as {@code sequence-of-instalment date amount}, in order. */
    List<String> fees(Agreement agreement) {
        return fakes.conditions.feesOf(agreement.obligation()).stream()
                .map(row -> instalmentSequence(agreement, row.instalment()) + " " + row.businessDate() + " "
                        + row.amount().minorUnits())
                .toList();
    }

    int instalmentSequence(Agreement agreement, UUID instalment) {
        return fakes.purchases.billings.get(instalment).sequence();
    }

    BnplPurchaseStore.StoredInstalment instalment(Agreement agreement, int sequence) {
        return fakes.purchases.schedules.get(agreement.obligation().value()).instalments().stream()
                .filter(row -> row.sequence() == sequence).findFirst().orElseThrow();
    }

    BnplObligation obligation(Agreement agreement) {
        return fakes.purchases.obligations.get(agreement.obligation().value());
    }

    BnplLedgerAccounts accounts(Agreement agreement) {
        return new BnplReceivables(fakes.purchases, fakes.book).accounts(BnplApplicationFakes.UOW, agreement.obligation());
    }

    Money balance(com.finapp.ledger.LedgerAccount account) {
        return fakes.book.balanceOf(BnplApplicationFakes.UOW, account);
    }

    LedgerAccountId feeIncome() {
        return fakes.book.operational(BnplApplicationFakes.UOW, AccountPurpose.BNPL_FEE_INCOME, BnplTermsFixtures.EUR);
    }

    List<String> labelled(List<BnplApplicationFakes.Book.Line> lines) {
        return lines.stream().map(line -> fakes.book.labels.get(line.account()) + " " + line.direction() + " "
                + line.amount().minorUnits()).toList();
    }

    /** The wallet's available balance set to exactly {@code minor}. */
    void availableTo(long minor) {
        Money now = fakes.book.availableUnderLock(BnplApplicationFakes.UOW, wallet);
        fakes.book.funds.put(wallet, fakes.book.funds.get(wallet).plus(eur(minor).minus(now)));
    }

    static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
