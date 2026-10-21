package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-pur, hermetic (`P12-TSK-013`; {@code PHASE_12_PLAN.md} sections 7.1-7.4 and 12.4, {@code BNPL_LIFECYCLES.md} sections
 * 4.4-4.8; {@code INV-BNPL-02}, {@code -03}, {@code -04}, {@code -05}, {@code -10}, {@code INV-LND-13}, {@code INV-CRD-09},
 * {@code INV-IDEM-01}): the acceptance consumes the decision, judges the capital, births the agreement, the purchase, the
 * obligation and its schedule, finances the merchant net of its fee, collects pay-in-4's instalment 1 from the wallet and
 * completes the checkout session - in the Phase 12 lock order, every refusal before or instead of the commit, and a failure
 * at every step leaving nothing. Over {@link BnplApplicationFakes} - in-memory {@code bnpl V003}/{@code V004}/{@code V006},
 * a ledger that refuses an unbalanced or a second posting, credit's lock and consumption, checkout's session, the merchant's
 * payable and fee pin, lending capital over the book's BNPL principal, and a unit of work that rolls every fake back. The
 * races across instances and the database's own ranks are {@code app}'s {@code BnplPurchaseDatabaseTest}'s and bnpl's
 * {@code BnplAcceptanceDatabaseTest}'s.
 */
@DisplayName("the acceptance: purchase, merchant financing and the obligation, atomically (P12-TSK-013)")
class BnplAcceptancesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-013-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;
    private static final LocalDate PURCHASE_DATE = LocalDate.of(2026, 10, 17);

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplAcceptances acceptances;
    private UUID party;
    private UUID merchant;
    private LedgerAccountId payable;
    private LedgerAccountId wallet;

    @BeforeEach
    void setUp() {
        world(UUID.fromString("00000000-0000-7000-8000-00000000000a"), UUID.fromString("ffffffff-ffff-7fff-bfff-ffffffffffff"));
    }

    private void world(UUID payableId, UUID walletId) {
        world = new BnplApplicationFakes();
        BnplTermsAdministration terms = new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS,
                CLOCK, BnplTermsFixtures.BOUNDS);
        applications = new BnplApplications(world.store, terms, world.standing, world.sessions, world.merchants,
                BnplTermsFixtures.BOUNDS, world.credit, world.outbox, world.audit, IDS, CLOCK);
        origination = new BnplOrigination(world.store, world.offers, terms, world.credit, world.sessions, world.outbox,
                IDS, CLOCK);
        acceptances = new BnplAcceptances(world.offers, world.terms.store(), world.purchases, world.credit,
                world.standing, world.sessions, world.merchants, world.capital, world.wallets, world.book, world.outbox,
                world.audit, IDS, CLOCK);
        party = UUID.randomUUID();
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        payable = world.book.external("payable", payableId);
        world.merchants.payables.put(merchant, payable);
        wallet = world.book.external("wallet", walletId);
        world.book.funds.put(wallet, eur(1_000_00));
        world.wallets.byParty.put(party, wallet);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ the purchase

    @Test
    @DisplayName("a pay-in-4 acceptance finances the merchant net of its fee, bills and collects instalment 1 from the"
            + " wallet and completes the session - F1, F2, F3 balanced and dated the purchase's day, every BNPL figure a"
            + " ledger balance")
    void aPayIn4AcceptanceFinancesAtomically() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplAcceptances.Accepted accepted = accept(offer);

        assertThat(accepted.purchase().status()).isEqualTo(BnplPurchaseStatus.FINANCED);
        assertThat(accepted.purchase().businessDate()).isEqualTo(PURCHASE_DATE);
        assertThat(world.offers.rows.get(offer.id().value()).status()).isEqualTo(BnplOfferStatus.ACCEPTED);
        assertThat(world.sessions.financedBy).containsEntry(offer.checkoutSession(), accepted.purchase().id().value());
        assertThat(world.credit.consumptions).containsEntry(offer.decision(), accepted.agreement().id().value());

        // F1: DR BNPL_PRINCIPAL 600; DR MERCHANT_PAYABLE 24 / CR MERCHANT_PAYABLE 600; CR BNPL_MERCHANT_FEE_INCOME 24.
        assertThat(world.book.linesOf(BnplAcceptances.FINANCING_KEY_PREFIX)).hasSize(4);
        assertThat(world.book.balance(payable, false)).as("the payable rises by the net").isEqualTo(eur(576_00));
        assertThat(world.book.balance(feeIncome(), false)).as("the fee recognised (A9)").isEqualTo(eur(24_00));
        // F2 and F3: instalment 1 (600 - 3 x 150) billed and collected.
        BnplLedgerAccounts accounts = accountsOf(accepted);
        assertThat(world.book.balance(accounts.principal().id(), true)).isEqualTo(eur(450_00));
        assertThat(world.book.balance(accounts.principalDue().id(), true)).isEqualTo(eur(0));
        assertThat(world.book.balance(wallet, false)).as("the wallet's lines: instalment 1 taken").isEqualTo(eur(-150_00));
        assertThat(world.book.lines).allSatisfy(line -> assertThat(line.valueDate())
                .as("every principal fact dated the purchase's business date - the obligation's floor")
                .isEqualTo(PURCHASE_DATE));

        BnplPurchaseStore.Repayment repayment = world.purchases.repayments.values().iterator().next();
        assertThat(repayment.kind()).isEqualTo(BnplPurchaseStore.Repayment.AT_PURCHASE);
        assertThat(repayment.requestKey()).isEqualTo(accepted.purchase().id().value().toString());
        assertThat(repayment.allocations()).singleElement().satisfies(line -> {
            assertThat(line.target()).isEqualTo("DUE_ITEM");
            assertThat(line.component()).contains("PRINCIPAL");
            assertThat(line.amount()).isEqualTo(eur(150_00));
        });
        assertThat(world.purchases.billings).hasSize(1);
        assertThat(types()).containsExactly(BnplAcceptances.AGREEMENT_CREATED_EVENT,
                BnplAcceptances.MERCHANT_FINANCED_EVENT, BnplAcceptances.INSTALMENT_DUE_EVENT,
                BnplAcceptances.INSTALMENT_PAID_EVENT);
        assertThat(world.audited).extracting(record -> record.operation().code())
                .containsExactly("bnpl.OfferAccepted", "bnpl.MerchantFinanced");
    }

    @Test
    @DisplayName("P12-TSK-014: the merchant reads its own accepted purchase - FINANCED, the financing's gross, fee and net,"
            + " the pinned version and F1 - and another merchant, or an unknown id, reads nothing")
    void theMerchantReadsItsOwnPurchase() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplAcceptances.Accepted accepted = accept(offer);
        BnplPurchaseReads reads = new BnplPurchaseReads(world.purchases);
        BnplPurchaseId id = accepted.purchase().id();

        MerchantBnplPurchaseView view = reads.forMerchant(BnplApplicationFakes.UOW, merchant, id).orElseThrow();
        MerchantFeeSplit pinned = world.merchants.pins.get(id.value());
        assertThat(view.purchase()).isEqualTo(id);
        assertThat(view.checkoutSession()).isEqualTo(offer.checkoutSession());
        assertThat(view.order()).isEqualTo(accepted.order());
        assertThat(view.product()).isEqualTo(BnplProduct.BNPL_PAY_IN_4);
        assertThat(view.status()).isEqualTo(BnplPurchaseStatus.FINANCED);
        assertThat(view.gross()).isEqualTo(eur(600_00));
        assertThat(view.fee()).as("the pin's fee, 4% of the gross").isEqualTo(eur(24_00));
        assertThat(view.net()).as("what F1 credited the payable").isEqualTo(eur(576_00))
                .isEqualTo(world.book.balance(payable, false));
        assertThat(view.feeScheduleVersion()).isEqualTo(pinned.feeScheduleVersionId());
        assertThat(view.financingEntry()).isEqualTo(accepted.financingEntry());

        assertThat(reads.forMerchant(BnplApplicationFakes.UOW, UUID.randomUUID(), id))
                .as("another merchant's purchase is the same empty answer as an absent one").isEmpty();
        assertThat(reads.forMerchant(BnplApplicationFakes.UOW, merchant, BnplPurchaseId.next(IDS)))
                .isEmpty();
    }

    @Test
    @DisplayName("a pay-in-N acceptance finances the merchant and births its schedule on SCHEDULE_ENGINE_V1 - the payable"
            + " alone at (P), no wallet touched, nothing billed at purchase")
    void aPayInNAcceptanceTakesThePayableAlone() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        world.log.clear();
        BnplAcceptances.Accepted accepted = accept(offer);

        assertThat(world.log).contains("lock payable").doesNotContain("lock wallet")
                .noneMatch(entry -> entry.startsWith("available"));
        assertThat(world.book.keys).singleElement().asString().startsWith(BnplAcceptances.FINANCING_KEY_PREFIX);
        BnplPurchaseStore.StoredSchedule schedule = world.purchases.schedules.get(accepted.obligation().id().value());
        assertThat(schedule.engine()).isEqualTo("SCHEDULE_ENGINE_V1");
        assertThat(schedule.instalments()).hasSize(6);
        assertThat(schedule.instalments().get(0).dueDate()).as("a month after the purchase (A6)")
                .isEqualTo(PURCHASE_DATE.plusMonths(1));
        assertThat(accepted.agreement().accrualEngine()).contains("ACCRUAL_ENGINE_V1");
        assertThat(world.purchases.billings).isEmpty();
        assertThat(world.book.balance(accountsOf(accepted).principal().id(), true)).isEqualTo(eur(GROSS));
    }

    @Test
    @DisplayName("agreement version 1 is the offer's canonical terms byte for byte, pins the engines - the refund allocator"
            + " among them - and the fee schedule version; the evidence is the session's, MULTI_FACTOR")
    void theAgreementCopiesTheOffer() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplOfferStore.StoredTerms stored = world.offers.terms.get(offer.id().value());
        BnplAcceptances.Accepted accepted = accept(offer);
        BnplAgreement agreement = world.purchases.agreements.get(accepted.agreement().id().value());
        assertThat(agreement.version()).isEqualTo(1);
        assertThat(agreement.canonicalTerms()).isEqualTo(stored.canonicalTerms());
        assertThat(agreement.termsSha256()).isEqualTo(stored.termsSha256());
        assertThat(agreement.documentSha256()).isEqualTo(stored.documentSha256());
        assertThat(agreement.scheduleEngine()).isEqualTo("BNPL_SCHEDULE_ENGINE_V1");
        assertThat(agreement.accrualEngine()).isEmpty();
        assertThat(agreement.allocationEngine()).isEqualTo("ALLOCATION_ENGINE_V1");
        assertThat(agreement.refundAllocationEngine()).isEqualTo("REFUND_ALLOCATION_ENGINE_V1");
        assertThat(agreement.feeScheduleVersion()).isEqualTo(world.merchants.feeScheduleVersion);
        assertThat(world.purchases.acceptances.get(agreement.id().value()).assuranceLevel()).isEqualTo("MULTI_FACTOR");
    }

    @Test
    @DisplayName("the financing conserves the purchase: the receivable is exactly the gross, the payable credited exactly"
            + " G - f, the fee f, the financing row the entry's figures (INV-BNPL-04)")
    void theFinancingConservesThePurchase() {
        world.merchants.feeBasisPoints = 333;
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplAcceptances.Accepted accepted = accept(offer);
        MerchantFinancing financing = world.purchases.financings.get(accepted.purchase().id().value());
        MerchantFeeSplit split = financing.split();
        assertThat(split.fee()).as("HALF_EVEN of 600.00 x 3.33%").isEqualTo(eur(19_98));
        assertThat(split.fee().plus(split.net())).isEqualTo(split.gross()).isEqualTo(eur(GROSS));
        List<BnplApplicationFakes.Book.Line> f1 = world.book.linesOf(BnplAcceptances.FINANCING_KEY_PREFIX);
        assertThat(f1).extracting(BnplApplicationFakes.Book.Line::amount).containsExactly(split.gross(), split.gross(),
                split.fee(), split.fee());
        assertThat(world.book.balance(payable, false)).isEqualTo(split.net());
        assertThat(world.book.balance(accountsOf(accepted).principal().id(), true)).isEqualTo(split.gross());
        assertThat(world.sessions.financingEntries).containsEntry(offer.checkoutSession(), accepted.financingEntry());
        assertThat(financing.entry()).as("the order carries the financing entry").isEqualTo(accepted.financingEntry());
    }

    @Test
    @DisplayName("a zero merchant fee writes F1 as two lines - a journal line asserts a positive amount")
    void aZeroFeeWritesNoFeeLines() {
        LedgerAccountId principal = LedgerAccountId.of(IDS.next());
        LedgerAccountId income = LedgerAccountId.of(IDS.next());
        MerchantFeeSplit noFee = new MerchantFeeSplit(eur(GROSS), eur(0), eur(GROSS), UUID.randomUUID());
        assertThat(BnplAcceptances.financingLines(noFee, principal, payable, income)).hasSize(2)
                .noneMatch(line -> line.account().equals(income));
        MerchantFeeSplit fee = new MerchantFeeSplit(eur(GROSS), eur(1), eur(GROSS - 1), UUID.randomUUID());
        assertThat(BnplAcceptances.financingLines(fee, principal, payable, income))
                .extracting(line -> line.direction() + " " + (line.account().equals(payable) ? "payable"
                        : line.account().equals(principal) ? "principal" : "income"))
                .containsExactly("DEBIT principal", "CREDIT payable", "DEBIT payable", "CREDIT income");
    }

    // ------------------------------------------------------------------ the order

    @Test
    @DisplayName("T-pur takes the Phase 12 lock order: L0 first, then B2, the hash, B3, standing, the merchant, credit's"
            + " consumption, L6, the inserts, (P), L8, the postings, the session's completion, the offer's edge last")
    void theLocksAreTakenInTheOrder() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.log.clear();
        accept(offer);
        // java.util.UUID.compareTo - payments' book-rail comparison, signed - decides which of the pair is first.
        boolean walletFirst = wallet.value().compareTo(payable.value()) < 0;
        String first = walletFirst ? "lock wallet" : "lock payable";
        String second = walletFirst ? "lock payable" : "lock wallet";
        assertThat(world.log.stream().filter(entry -> !entry.startsWith("event") && !entry.startsWith("audit")
                && !entry.startsWith("offer history")).toList()).containsExactly(
                "L0", "B2 " + offer.id().value(), "B3", "standing", "merchant standing", "consume", "L6",
                "purchase insert", "fee pin", "merchant standing", "obligation insert", "agreement insert",
                "acceptance insert", "schedule insert", "accounts open", first, second,
                "available wallet", "L8", "post bnpl.financing:", "financing insert", "post bnpl.billing:",
                "billing insert", "post bnpl.repayment:", "repayment insert", "complete session", "purchase FINANCED",
                "offer accept", "offer ACCEPTED");
    }

    @Test
    @DisplayName("the acceptance and a credit decision serialise on the profile: L0 is the act's first lock and the"
            + " consumption is taken under it, never before")
    void acceptanceAndADecisionSerialiseOnTheProfile() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.log.clear();
        accept(offer);
        assertThat(world.log.get(0)).as("L0 before any bnpl row").isEqualTo("L0");
        assertThat(world.log.indexOf("consume")).isGreaterThan(world.log.indexOf("L0"))
                .isLessThan(world.log.indexOf("L6"));
    }

    @Test
    @DisplayName("(P) is one element in UUID order: the wallet first when its id sorts first, the payable first when its"
            + " id does - payments' book-rail order for the same pair, in both orders")
    void thePayableAndTheWalletAreLockedAsOneElementInUuidOrder() {
        UUID low = UUID.fromString("00000000-0000-7000-8000-00000000000a");
        UUID high = UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff");
        assertThat(low.compareTo(high)).as("not vacuous: the two ids in UUID.compareTo order").isNegative();
        world(high, low);
        accept(offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4));
        assertThat(world.log.indexOf("lock wallet")).as("the wallet's id sorts first").isLessThan(
                world.log.indexOf("lock payable"));

        world(low, high);
        accept(offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4));
        assertThat(world.log.indexOf("lock payable")).as("the payable's id sorts first").isLessThan(
                world.log.indexOf("lock wallet"));
    }

    @Test
    @DisplayName("the session is judged at once under B3: a session a card confirmation won, or past its deadline, is"
            + " refused SessionNotPayable before the standing, the consumption or the capital - nothing written")
    void theSessionIsJudgedAtOnce() {
        BnplOffer paid = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.sessions.paidByCard(paid.checkoutSession());
        world.log.clear();
        assertThatThrownBy(() -> accept(paid)).isInstanceOfSatisfying(BnplAcceptances.SessionNotPayable.class,
                refused -> assertThat(refused.reason()).isEqualTo(BnplCheckoutSessions.Reason.NOT_OPEN));
        assertThat(world.log).endsWith("B3").doesNotContain("consume", "L6", "standing", "merchant standing");
        assertNothingWritten(paid);

        BnplOffer late = offeredOn(Instant.parse("2026-10-17T10:20:00Z"));
        world.now = Instant.parse("2026-10-17T10:20:00Z");
        world.log.clear();
        assertThatThrownBy(() -> accept(late)).isInstanceOf(BnplAcceptances.NotAcceptable.class);
        assertThat(world.log).as("the offer expires with its session, judged under B2").doesNotContain("B3");
        assertNothingWritten(late);
    }

    @Test
    @DisplayName("acceptances racing for the last capital admit one: the second judged under L6 over the first's"
            + " principal is refused CapitalUnavailable, nothing written (INV-LND-13, INV-BNPL-10)")
    void acceptancesRacingForTheLastCapitalAdmitOne() {
        world.capital.recognised = eur(GROSS + 100_00);
        BnplOffer first = offered(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplOffer second = offeredFor(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        accept(first);
        world.events.clear();
        world.audited.clear();
        assertThatThrownBy(() -> accept(second, second.party())).isInstanceOf(BnplAcceptances.CapitalUnavailable.class);
        assertThat(world.purchases.rows).hasSize(1);
        assertThat(world.book.bnplPrincipal()).as("never over-deployed").isEqualTo(eur(GROSS));
        assertNothingWritten(second);
    }

    @Test
    @DisplayName("a failure at every step leaves nothing: no purchase, financing, obligation, agreement, schedule, account"
            + " or posting, no consumption, pin or order, the offer still OFFERED and the session still OPEN")
    void aFailureAtEveryStepLeavesNothing() {
        accept(offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4));
        List<String> steps = world.log.stream().filter(entry -> List.of("L0", "B3", "consume", "L6", "purchase insert",
                "fee pin", "obligation insert", "agreement insert", "acceptance insert", "schedule insert",
                "accounts open", "lock payable", "lock wallet", "L8", "post bnpl.financing:", "financing insert",
                "post bnpl.billing:", "billing insert", "post bnpl.repayment:", "repayment insert", "complete session",
                "purchase FINANCED", "offer accept").contains(entry)).distinct().toList();
        assertThat(steps).as("not vacuous: every step of the purchase was seen").hasSize(23);
        for (String step : steps) {
            setUp();
            BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
            world.failAt = step;
            assertThatThrownBy(() -> accept(offer)).as(step).isInstanceOf(BnplApplicationFakes.InjectedFailure.class);
            assertNothingWritten(offer);
            // The same offer is still acceptable once whatever failed is gone.
            world.failAt = null;
            assertThat(accept(offer).purchase().status()).as(step).isEqualTo(BnplPurchaseStatus.FINANCED);
        }
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    @DisplayName("pay-in-4's instalment 1 short in the wallet refuses InsufficientFunds and commits nothing; topped up,"
            + " the same offer is accepted (B6)")
    void instalmentOneShortRefusesAndCommitsNothing() {
        world.book.funds.put(wallet, eur(149_99));
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.InsufficientFunds.class);
        assertNothingWritten(offer);
        world.book.funds.put(wallet, eur(150_00));
        assertThat(accept(offer).purchase().status()).isEqualTo(BnplPurchaseStatus.FINANCED);
        assertThat(world.book.availableUnderLock(BnplApplicationFakes.UOW, wallet)).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a customer with no wallet in the currency cannot fund instalment 1: InsufficientFunds, nothing written")
    void noWalletRefuses() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.wallets.byParty.clear();
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.InsufficientFunds.class);
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("a mismatched or malformed hash refuses TermsChanged under B2, before the session is locked - nothing"
            + " written")
    void aMismatchedHashRefuses() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        for (String echoed : List.of("00".repeat(32), "not-hex", "")) {
            world.log.clear();
            assertThatThrownBy(() -> accept(offer, party, echoed)).isInstanceOf(BnplAcceptances.TermsChanged.class);
            assertThat(world.log).doesNotContain("B3");
            assertNothingWritten(offer);
        }
        String upper = world.offers.terms.get(offer.id().value()).termsSha256Hex().toUpperCase(java.util.Locale.ROOT);
        assertThat(accept(offer, party, upper).purchase().status()).as("the hash compared case-blind")
                .isEqualTo(BnplPurchaseStatus.FINANCED);
    }

    @Test
    @DisplayName("a suspended merchant refuses MerchantNotEnabled before the consumption - and a merchant whose payable"
            + " is closed under (P) refuses too; nothing written")
    void aSuspendedMerchantRefuses() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.merchants.standings.put(merchant, BnplMerchants.Standing.NOT_TRADING);
        world.log.clear();
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.MerchantNotEnabled.class);
        assertThat(world.log).doesNotContain("consume");
        assertNothingWritten(offer);

        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.book.statuses.put(payable, com.finapp.ledger.LedgerAccountStatus.CLOSED);
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.MerchantNotEnabled.class);
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("a merchant re-priced since the offer - its fee now the whole gross - refuses MerchantNotEnabled at the"
            + " pin, re-judged under its share locks; nothing written, never a 500")
    void aRepricedMerchantRefusesAtThePin() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.merchants.feeBasisPoints = 10_000;
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.MerchantNotEnabled.class);
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("standing lost refuses before the consumption - credit's ApplicantNotEligible at the door; nothing written")
    void standingLostRefuses() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.inGoodStanding = false;
        world.log.clear();
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplApplications.NotInGoodStanding.class);
        assertThat(world.log).doesNotContain("consume");
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("a decision credit will not consume - lapsed on its clock - is NotAcceptable, nothing written; the sweep"
            + " expires the offer, the refused key commits nothing")
    void aLapsedDecisionIsNotAcceptable() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, Instant.parse("2026-10-17T10:15:00Z"));
        world.now = Instant.parse("2026-10-17T10:14:59Z");
        world.at.put("consume", () -> world.now = Instant.parse("2026-10-17T10:15:00Z"));
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.NotAcceptable.class);
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("an offer that expires while the act waits is not acceptable at its edge, the act's last write - all of"
            + " it rolled back: exactly one of ACCEPTED and EXPIRED")
    void anOfferExpiringWhileTheActWaitsIsNotAcceptable() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.at.put("purchase FINANCED", () -> world.now = offer.expiresAt());
        assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.NotAcceptable.class);
        assertNothingWritten(offer);
        assertThat(world.inTransaction(() -> origination.expire(BnplApplicationFakes.UOW, offer.id(), SYSTEM)))
                .as("the sweep expires it").contains(BnplProduct.BNPL_PAY_IN_4);
        assertThat(world.offers.rows.get(offer.id().value()).status()).isEqualTo(BnplOfferStatus.EXPIRED);
    }

    @Test
    @DisplayName("the same offer accepted again - a second key, a second instance - finds it ACCEPTED: NotAcceptable, one"
            + " purchase, one financing, one consumption, one order")
    void theSameAcceptanceTwiceFinancesOnce() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        accept(offer);
        for (int instance = 0; instance < 9; instance++) {
            assertThatThrownBy(() -> accept(offer)).isInstanceOf(BnplAcceptances.NotAcceptable.class);
        }
        assertThat(world.purchases.rows).hasSize(1);
        assertThat(world.purchases.financings).hasSize(1);
        assertThat(world.credit.consumptions).hasSize(1);
        assertThat(world.sessions.financedBy).hasSize(1);
        assertThat(world.book.linesOf(BnplAcceptances.FINANCING_KEY_PREFIX)).hasSize(4);
    }

    @Test
    @DisplayName("another party's offer is the same not-found as an absent one, taken under its owner-scoped lock")
    void anotherPartysOfferIsNotFound() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThatThrownBy(() -> accept(offer, UUID.randomUUID())).isInstanceOf(BnplOffers.OfferNotFound.class);
        assertNothingWritten(offer);
    }

    @Test
    @DisplayName("no amount, merchant or party in an audit summary, an event's aggregate or a refusal's message"
            + " (INV-AUD-02); the merchant-facing event carries only the merchant's own figures")
    void noFigureLeaks() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        accept(offer);
        for (AuditRecord record : world.audited) {
            String summary = record.changeSummary().orElseThrow();
            assertThat(summary).doesNotContain(merchant.toString(), party.toString());
            // The amounts judged with the summary's ids masked: a time-ordered id's hex may hold "576" or "600" by chance
            // (a flake the Phase 12 exit review met - P12-DOC-001), which is not a figure leaking.
            assertThat(summary.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<id>"))
                    .doesNotContain("600", "576", "24.00");
        }
        String financed = world.payloads.get(types().indexOf(BnplAcceptances.MERCHANT_FINANCED_EVENT));
        assertThat(financed).contains("\"grossMinor\":\"60000\"").contains("\"netMinor\":\"57600\"")
                .doesNotContain(party.toString()).doesNotContain("decision");
    }

    // ------------------------------------------------------------------ plumbing

    /** An offer for {@code party} of {@code product} and {@code plan}, on a fresh session, credit approving the gross. */
    private BnplOffer offered(BnplProduct product, BnplPlan plan) {
        return offeredFor(party, product, plan);
    }

    private BnplOffer offered(BnplProduct product, BnplPlan plan, Instant validUntil) {
        return offer(party, product, plan, Instant.parse("2026-10-17T11:00:00Z"), validUntil);
    }

    /** A pay-in-4 offer on a session whose deadline is {@code sessionExpiresAt}. */
    private BnplOffer offeredOn(Instant sessionExpiresAt) {
        return offer(party, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, sessionExpiresAt,
                world.now.plus(Duration.ofDays(1)));
    }

    private BnplOffer offeredFor(UUID who, BnplProduct product, BnplPlan plan) {
        world.wallets.byParty.putIfAbsent(who, wallet);
        return offer(who, product, plan, Instant.parse("2026-10-17T11:00:00Z"), world.now.plus(Duration.ofDays(1)));
    }

    private BnplOffer offer(UUID who, BnplProduct product, BnplPlan plan, Instant sessionExpiresAt, Instant validUntil) {
        String token = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                Money.ofMinorUnits(GROSS, BnplTermsFixtures.EUR), sessionExpiresAt, true, false));
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, who,
                new BnplApplications.Submission(Sensitive.of(token), product, plan),
                new Actor(who.toString(), ActorType.CUSTOMER), CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, validUntil);
        BnplOrigination.Outcome outcome = world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW,
                application.id(), SYSTEM));
        assertThat(outcome.kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
        world.events.clear();
        world.payloads.clear();
        world.audited.clear();
        return world.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
    }

    private BnplAcceptances.Accepted accept(BnplOffer offer) {
        return accept(offer, party);
    }

    private BnplAcceptances.Accepted accept(BnplOffer offer, UUID who) {
        String hash = Optional.ofNullable(world.offers.terms.get(offer.id().value()))
                .map(BnplOfferStore.StoredTerms::termsSha256Hex).orElse("00");
        return accept(offer, who, hash);
    }

    private BnplAcceptances.Accepted accept(BnplOffer offer, UUID who, String hash) {
        BnplAcceptanceEvidence evidence = new BnplAcceptanceEvidence(UUID.randomUUID(), UUID.randomUUID(),
                "MULTI_FACTOR", Optional.of("192.0.2.1"), Optional.of("agent"));
        return world.inTransaction(() -> acceptances.accept(BnplApplicationFakes.UOW, offer.id(), who,
                new BnplAcceptances.Acceptance(hash, evidence), new Actor(who.toString(), ActorType.CUSTOMER),
                Correlation.startingWith(CORRELATION)));
    }

    /** Nothing of the offer's purchase anywhere: the rollback left every fake as it was. */
    private void assertNothingWritten(BnplOffer offer) {
        assertThat(world.purchases.rows.values()).noneMatch(row -> row.offer().equals(offer.id()));
        assertThat(world.credit.consumptions).doesNotContainKey(offer.decision());
        assertThat(world.sessions.financedBy).doesNotContainKey(offer.checkoutSession());
        assertThat(world.offers.rows.get(offer.id().value()).status()).isIn(BnplOfferStatus.OFFERED);
        List<UUID> purchases = new ArrayList<>(world.purchases.rows.keySet());
        assertThat(world.merchants.pins.keySet()).allMatch(purchases::contains);
        assertThat(world.purchases.financings.keySet()).allMatch(purchases::contains);
        assertThat(world.purchases.obligations.values()).allMatch(row -> purchases.contains(row.purchase().value()));
        assertThat(world.book.accounts.values()).allMatch(account -> world.purchases.obligations
                .containsKey(account.ownerRef().orElseThrow()));
        assertThat(world.book.keys).allMatch(key -> purchases.stream().anyMatch(id -> key.endsWith(id.toString()))
                || world.purchases.billings.values().stream().anyMatch(row -> key.endsWith(row.instalment().toString()))
                || world.purchases.repayments.keySet().stream().anyMatch(id -> key.endsWith(id.toString())));
        assertThat(types()).doesNotContain(BnplAcceptances.AGREEMENT_CREATED_EVENT);
        assertThat(world.audited).noneMatch(record -> record.operation().code().equals("bnpl.OfferAccepted"));
    }

    private BnplLedgerAccounts accountsOf(BnplAcceptances.Accepted accepted) {
        List<com.finapp.ledger.LedgerAccount> six = world.book.accountsOf(BnplApplicationFakes.UOW,
                accepted.obligation().id());
        return new BnplLedgerAccounts(six.get(0), six.get(1), six.get(2), six.get(3), six.get(4), six.get(5));
    }

    private LedgerAccountId feeIncome() {
        return world.book.operational.get(com.finapp.ledger.AccountPurpose.BNPL_MERCHANT_FEE_INCOME);
    }

    private List<String> types() {
        return world.events.stream().map(EventEnvelope::eventType).toList();
    }

    private static Money eur(long minorUnits) {
        return Money.ofMinorUnits(minorUnits, BnplTermsFixtures.EUR);
    }
}
