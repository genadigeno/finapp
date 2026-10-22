package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplStormDatabaseTest.allRaw;
import static com.finapp.app.bnpl.BnplStormDatabaseTest.bothAtOnce;
import static com.finapp.app.bnpl.BnplStormDatabaseTest.firstInstalment;
import static com.finapp.app.bnpl.BnplStormWorld.AGREEMENTS;
import static com.finapp.app.bnpl.BnplStormWorld.APPLICATIONS;
import static com.finapp.app.bnpl.BnplStormWorld.CONFIRMATION;
import static com.finapp.app.bnpl.BnplStormWorld.LOAN_OFFERS;
import static com.finapp.app.bnpl.BnplStormWorld.MERCHANT_DISPUTES;
import static com.finapp.app.bnpl.BnplStormWorld.OFFERS;
import static com.finapp.app.bnpl.BnplStormWorld.OPERATOR_AGREEMENTS;
import static com.finapp.app.bnpl.BnplStormWorld.PAYOUTS;
import static com.finapp.app.bnpl.BnplStormWorld.PAYOUT_PATH;
import static com.finapp.app.bnpl.BnplStormWorld.PURCHASES;
import static com.finapp.app.bnpl.BnplStormWorld.count;
import static com.finapp.app.bnpl.BnplStormWorld.decimal;
import static com.finapp.app.bnpl.BnplStormWorld.judged;
import static com.finapp.app.bnpl.BnplStormWorld.key;
import static com.finapp.app.bnpl.BnplStormWorld.money;
import static com.finapp.app.bnpl.BnplStormWorld.refused;
import static com.finapp.app.bnpl.BnplStormWorld.row;
import static com.finapp.app.bnpl.BnplStormWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.bnpl.BnplStormDatabaseTest.Instance;
import com.finapp.app.bnpl.BnplStormWorld.Answer;
import com.finapp.app.bnpl.BnplStormWorld.Customer;
import com.finapp.app.bnpl.BnplStormWorld.Merchant;
import com.finapp.app.bnpl.BnplStormWorld.Tally;
import com.finapp.identity.RoleName;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.platform.outbox.EventPublisher;
import com.finapp.platform.outbox.OutboxRelay;
import com.finapp.platform.outbox.PendingEvent;
import com.finapp.platform.outbox.RetryPolicy;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The BNPL storm's catalogue and crash points (`P12-TST-001`; the lending storm's {@code LendingStormCatalogue} shape):
 * the eighteen mandatory scenarios of {@code PHASE_12_PLAN.md} section 13.2 and the contention scenes, each made to happen
 * ON PURPOSE across both instances at a barrier - chance does not find races, choreography does - and each followed by
 * every census; then the crash points, each re-driven by the other instance, each followed by every census. The coverage
 * ledger is incremented only from ROWS, never from the storm's intentions.
 */
@SuppressWarnings("try") // Scopes and faults are used for their close side effect (the established idiom).
final class BnplStormCatalogue {

    private static final Duration PATIENCE = Duration.ofSeconds(90);
    /** The far permit of an obligation a scene owns: no sweep services it until the scene hands it back. */
    static final String FAR = "'2999-01-01T00:00:00Z'::timestamptz";

    /** The coverage ledger's minimum per scenario - every one from rows. */
    static final Map<String, Integer> MINIMUM = minimum();

    private static Map<String, Integer> minimum() {
        Map<String, Integer> minimum = new LinkedHashMap<>();
        minimum.put("scenario 1 one acceptance key on both instances finances once", 2);
        minimum.put("scenario 1 ten keys finance once", 1);
        minimum.put("scenario 1 a second purchase on one session refused by its unique", 1);
        minimum.put("scenario 2 a card confirmation and an acceptance on one session complete it once", 3);
        minimum.put("scenario 2 a wallet confirmation and an acceptance on one session complete it once", 3);
        minimum.put("scenario 3 an acceptance and a decision for one party serialise", 2);
        minimum.put("scenario 4 ten sweepers collect once", 2);
        minimum.put("scenario 4 a sweep beside the customer's repayment collects once", 1);
        minimum.put("scenario 5 a lost payout response held, the retry replayed, concluded once", 2);
        minimum.put("scenario 5 wallet variant: a lost card top-up replayed, concluded once, the instalment collected once",
                1);
        minimum.put("scenario 6 two repayments on one instalment", 2);
        minimum.put("scenario 6 the raced instalment paid once, whatever the order", 4);
        minimum.put("scenario 7 a refund racing a collection: collected first", 1);
        minimum.put("scenario 7 a refund racing a collection: refunded first", 1);
        minimum.put("scenario 8 one refund key on both instances refunds once", 3);
        minimum.put("scenario 9 a refund before the instalments", 1);
        minimum.put("scenario 9 a refund during the instalments", 1);
        minimum.put("scenario 9 a refund after closure, all to the wallet", 1);
        minimum.put("scenario 9 a refund beyond what is owed: the excess to the customer", 1);
        minimum.put("scenario 9 a full refund closes with nothing stranded, the fee telescoped", 2);
        minimum.put("scenario 10 a dispute's refund and a merchant's refund share one bound", 2);
        minimum.put("scenario 10 a dispute resolved by the merchant's silence", 1);
        minimum.put("scenario 10 a dispute resolved by the operator under four eyes", 1);
        minimum.put("scenario 11 a payoff beside a refund: stale or closed, never wrong", 2);
        minimum.put("scenario 12 a reversal after allocation, a later repayment untouched", 2);
        minimum.put("scenario 13 no condition after closure", 2);
        minimum.put("A6-03 a fact after a closure, stamped before it, caught by the record's order", 1);
        minimum.put("scenario 14 a failure at a T-pur step leaves nothing, the retry finances once", 5);
        minimum.put("scenario 14 killed between posting and publication: nothing, then once", 1);
        minimum.put("scenario 14 the relay killed after the receive: the unacknowledged re-published", 1);
        minimum.put("scenario 15 the hint twice from both instances, one offer", 1);
        minimum.put("scenario 15 a session closed while awaiting the decision closes the application", 1);
        minimum.put("scenario 16 a repayment after a full refund is refused", 1);
        minimum.put("scenario 17 a late fee assessed once, released by the refund that cancels its instalment", 1);
        minimum.put("scenario 17 a late fee waived under four eyes across instances", 1);
        minimum.put("scenario 18 ten steps accrue every date once", 2);
        minimum.put("the last capital raced across BNPL and loans: one admitted", 2);
        minimum.put("(P) a BNPL acceptance beside a wallet-paid capture, payable first", 2);
        minimum.put("(P) a BNPL acceptance beside a wallet-paid capture, wallet first", 2);
        minimum.put("A2-F1 no income row held across a wallet wait", 3);
        minimum.put("A6-06 an obligation 30 days past due changed bucket", 1);
        minimum.put("A6-06 an obligation 90 days past due defaulted", 1);
        minimum.put("B7 a refund after payout left the payable below zero within its bound", 1);
        minimum.put("self-dealing refused", 1);
        minimum.put("crash points", 10);
        return minimum;
    }

    private final BnplStormDatabaseTest storm;
    private final Tally tally;
    private final BnplStormWorld world;

    BnplStormCatalogue(BnplStormDatabaseTest storm) {
        this.storm = storm;
        this.tally = storm.tally();
        this.world = storm.world();
    }

    private Instance a() {
        return storm.a();
    }

    private Instance b() {
        return storm.b();
    }

    // ================================================================== the scenes

    void run() throws Exception {
        storm.hotRows().nextRound("catalogue");
        play("lastCapital", this::lastCapital);
        String[] treasurers = storm.treasurers();
        world.tranche(a().door(), b().door(), treasurers[0], treasurers[1], 300_000_00, true, tally);
        play("namedLockWaits", this::namedLockWaits);
        play("incomeRowsFreeAcrossAWalletWait", this::incomeRowsFreeAcrossAWalletWait);
        play("sameAcceptanceTwice", this::sameAcceptanceTwice);
        play("bnplAgainstACardOrAWallet", this::bnplAgainstACardOrAWallet);
        play("acceptanceAndADecisionSerialise", this::acceptanceAndADecisionSerialise);
        play("thePairInBothOrders", this::thePairInBothOrders);
        play("tenSweepersCollectOnce", this::tenSweepersCollectOnce);
        play("lostPayoutResponses", this::lostPayoutResponses);
        play("lostCardTopUp", this::lostCardTopUp);
        play("twoRepaymentsOnOneInstalment", this::twoRepaymentsOnOneInstalment);
        play("refundRacingACollection", this::refundRacingACollection);
        play("sameRefundTwice", this::sameRefundTwice);
        play("refundsBeforeDuringAfter", this::refundsBeforeDuringAfter);
        play("disputesShareTheBound", this::disputesShareTheBound);
        play("payoffBesideARefund", this::payoffBesideARefund);
        play("reversalAfterAllocation", this::reversalAfterAllocation);
        play("noConditionAfterClosure", this::noConditionAfterClosure);
        play("lateFeeReleasedAndWaived", this::lateFeeReleasedAndWaived);
        play("accrualOncePerDate", this::accrualOncePerDate);
        play("hintsAndAClosedSession", this::hintsAndAClosedSession);
        play("delinquencyForcedByTheCalendar", this::delinquencyForcedByTheCalendar);
        play("refundAfterPayout", this::refundAfterPayout);
        play("selfDealing", this::selfDealing);
        storm.hotRows().nextRound("catalogue done");
    }

    /**
     * A scene, unless a development run named the scenes it wants ({@code FINAPP_BNPL_STORM_SCENES}) - and such a run fails
     * at its end by design: a partial storm proves nothing.
     */
    private void play(String scene, BnplStormDatabaseTest.Act act) throws Exception {
        if (BnplStormDatabaseTest.wanted(scene)) {
            act.run();
        }
    }

    // ---------------------------------------------------------------- the scenes' purchases

    /** A purchase a scene owns: its ids, its owner and its merchant. */
    record Bought(String agreement, String obligation, String purchase, Customer customer, Merchant merchant, long gross,
            String session) {}

    /** An offer a scene owns: the offer, its hash, the session it would pay and its token. */
    record Offer(String offer, String hash, String session, String sessionToken, Customer customer, Merchant merchant,
            long gross) {}

    /** A fresh customer - never one the movers know - reserved by party. */
    Customer freshCustomer() throws Exception {
        Customer customer = world.customer();
        storm.reserved().add(customer.party().toString());
        storm.byParty().put(customer.party(), customer);
        return customer;
    }

    Merchant merchant(int i) {
        return storm.merchants().get(Math.floorMod(i, storm.merchants().size()));
    }

    /** {@code customer} applies at a fresh session of {@code merchant} on A; credit's sweeps decide; the sweeps offer. */
    Offer offered(Customer customer, Merchant merchant, long gross, int months) throws Exception {
        String[] session = BnplStormWorld.openSession(a().door(), merchant, gross, tally);
        String body = months == 0 ? BnplStormWorld.payIn4(session[1]) : BnplStormWorld.payInN(session[1], months);
        Answer applied = a().door().post(APPLICATIONS, body, customer.token(), key());
        judged(tally, "scene application", applied);
        assertThat(applied.status()).as("a scene's application: %s", applied.code()).isEqualTo(202);
        Optional<String> offer = BnplStormWorld.awaitOffer(applied.field("applicationId"), PATIENCE);
        assertThat(offer).as("the scene's application was offered").isPresent();
        String hash = BnplStormWorld.termsHash(a().door(), customer.token(), offer.get());
        return new Offer(offer.get(), hash, session[0], session[1], customer, merchant, gross);
    }

    /** An offer accepted on B, pay-in-4's instalment 1 funded first by exactly it; the purchase reserved. */
    Bought bought(Customer customer, Merchant merchant, long gross, int months) throws Exception {
        Offer offer = offered(customer, merchant, gross, months);
        return accepted(offer, b());
    }

    Bought accepted(Offer offer, Instance on) throws Exception {
        if (offer.gross() > 0 && isPayIn4(offer)) {
            BnplStormWorld.payIn(on.door(), storm.other(on).door(), offer.customer(), firstInstalment(offer.gross()),
                    key(), tally, null);
        }
        Answer accepted = on.door().post(OFFERS + "/" + offer.offer() + "/acceptance",
                BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), key());
        judged(tally, "scene acceptance", accepted);
        assertThat(accepted.status()).as("a scene's acceptance: %s", accepted.code()).isEqualTo(201);
        return bought(offer, accepted.field("agreementId"));
    }

    private static boolean isPayIn4(Offer offer) throws SQLException {
        return "BNPL_PAY_IN_4".equals(scalar("SELECT product FROM bnpl.bnpl_offer WHERE id = ?::uuid",
                UUID.fromString(offer.offer())));
    }

    private Bought bought(Offer offer, String agreement) throws SQLException {
        String[] ids = row("SELECT id::text, purchase_id::text FROM bnpl.bnpl_obligation WHERE agreement_id = ?::uuid",
                UUID.fromString(agreement));
        storm.reserved().add(ids[0]);
        storm.reserved().add(ids[1]);
        return new Bought(agreement, ids[0], ids[1], offer.customer(), offer.merchant(), offer.gross(), offer.session());
    }

    /** A pay-in-4 purchase of a fresh customer, aged {@code days} before today, held from the sweeps (far permit). */
    Bought agedP4(long gross, long days, int merchant) throws Exception {
        return aged(gross, days, 0, merchant);
    }

    /** A pay-in-N purchase of a fresh customer, aged {@code days} before today, held from the sweeps. */
    Bought agedPn(long gross, long days, int months, int merchant) throws Exception {
        return aged(gross, days, months, merchant);
    }

    /** A purchase born in the past - another bought when a sweep met the first before its ageing (its permit is its birth). */
    private Bought aged(long gross, long days, int months, int merchant) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Bought bought = bought(freshCustomer(), merchant(merchant), gross, months);
            if (BnplStormWorld.bornInThePast(bought.obligation(), days, FAR)) {
                return bought;
            }
            tally.count("a scene's purchase serviced before its ageing - another bought");
        }
        throw new IllegalStateException("five purchases in a row were serviced before their ageing");
    }

    /** {@code n} aged pay-in-4 purchases made at once. */
    List<Bought> agedP4s(int n, long gross, long days) throws Exception {
        List<Callable<Bought>> makers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int merchant = i;
            long each = gross + i;
            makers.add(() -> agedP4(each, days, merchant));
        }
        return BnplStormDatabaseTest.all(makers);
    }

    /** The unpaid billed amount of {@code obligation}'s instalment {@code sequence}, billed or not: its amount. */
    static long instalment(String obligation, int sequence) throws SQLException {
        return count("SELECT i.amount_minor FROM bnpl.bnpl_instalment i JOIN bnpl.bnpl_schedule s ON s.id = i.schedule_id"
                + " WHERE i.obligation_id = ?::uuid AND i.sequence = ? ORDER BY s.schedule_version DESC LIMIT 1",
                UUID.fromString(obligation), sequence);
    }

    /** The scene is over: its obligations - held from the sweeps by a far permit - handed back, then every census. */
    void censusAfter(String scene) throws Exception {
        BnplStormWorld.asBootstrap("UPDATE bnpl.bnpl_obligation SET next_servicing_at = statement_timestamp() WHERE"
                + " status = 'ACTIVE' AND next_servicing_at > statement_timestamp() + interval '1 year'");
        storm.restAndCensus(scene);
    }

    private Answer answer(Object raced) {
        return storm.answer(raced);
    }

    private Object serviceOn(Instance on, String obligation) {
        return storm.serviceOn(on, obligation);
    }

    private void fund(Customer customer, long minor) throws Exception {
        BnplStormWorld.payIn(a().door(), b().door(), customer, minor, key(), tally, null);
    }

    private Answer repay(Instance on, Bought bought, long minor, String repaymentKey) throws Exception {
        return on.door().post(AGREEMENTS + "/" + bought.agreement() + "/repayments", money(minor),
                bought.customer().token(), repaymentKey);
    }

    private Answer refund(Instance on, Bought bought, long minor, String refundKey) throws Exception {
        return on.door().post(PURCHASES + "/" + bought.purchase() + "/refunds", "{\"amount\":\"" + decimal(minor)
                + "\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}", bought.merchant().refundKey(), refundKey);
    }

    private static String status(String obligation) throws SQLException {
        return scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = ?::uuid", UUID.fromString(obligation));
    }

    // ---------------------------------------------------------------- the last capital (L6, INV-LND-13, INV-BNPL-10)

    /**
     * The last capital across BNPL and loans: capital topped up through a statement so the headroom fits exactly one of
     * ten offered amounts - six pay-in-N purchases and four loans - ten acceptances on A and B at a barrier; twice.
     */
    private void lastCapital() throws Exception {
        long amount = 1_500_00;
        String[] treasurers = storm.treasurers();
        for (int round = 0; round < 2; round++) {
            List<Callable<Callable<Answer>>> offering = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                boolean loan = i >= 6;
                Instance on = i % 2 == 0 ? a() : b();
                int merchant = i;
                offering.add(() -> {
                    Customer customer = freshCustomer();
                    if (loan) {
                        Answer submitted = a().door().post(BnplStormWorld.LOAN_APPLICATIONS,
                                BnplStormWorld.loanApplication(amount, 12, 10), customer.token(), key());
                        judged(tally, "last capital loan application", submitted);
                        Optional<String> offer = BnplStormWorld.awaitLoanOffer(submitted.field("applicationId"), PATIENCE);
                        assertThat(offer).as("the last capital's loan offered").isPresent();
                        String hash = a().door().get(LOAN_OFFERS + "/" + offer.get(), customer.token()).field("termsSha256");
                        return () -> on.door().post(LOAN_OFFERS + "/" + offer.get() + "/acceptance", "{\"termsSha256\":\""
                                + hash + "\"}", customer.token(), key());
                    }
                    Offer offer = offered(customer, merchant(merchant), amount, 6);
                    return () -> on.door().post(OFFERS + "/" + offer.offer() + "/acceptance",
                            BnplStormWorld.acceptance(offer.hash()), customer.token(), key());
                });
            }
            List<Callable<Answer>> racers = BnplStormDatabaseTest.all(offering);
            fillHeadroomBelow(amount);
            long headroom = storm.headroom();
            world.tranche(a().door(), b().door(), treasurers[0], treasurers[1], amount + amount / 3 - headroom, true,
                    tally);
            assertThat(storm.headroom()).as("the headroom fits exactly one of the ten").isBetween(amount, 2 * amount - 1);
            int admitted = 0;
            for (Object raced : allRaw(racers)) {
                Answer response = answer(raced);
                judged(tally, "last capital acceptance", response, "422 bnpl.CapitalUnavailable",
                        "422 lending.CapitalUnavailable");
                admitted += response.status() == 201 ? 1 : 0;
            }
            censusAfter("the last capital " + (round + 1));
            storm.coverage("the last capital raced across BNPL and loans: one admitted", admitted == 1 ? 1 : 0);
            tally.count("last capital admitted " + admitted);
        }
    }

    /** Pay-in-N purchases of fresh customers accepted until the headroom is below {@code amount} - real deployments. */
    private void fillHeadroomBelow(long amount) throws Exception {
        for (int guard = 0; guard < 120; guard++) {
            long headroom = storm.headroom();
            if (headroom < amount) {
                return;
            }
            long filler = Math.min(4_900_00, Math.max(200_00, headroom - amount / 2));
            bought(freshCustomer(), merchant(guard), filler, 12);
        }
        throw new AssertionError("the headroom could not be brought below the racers' amount");
    }

    // ---------------------------------------------------------------- every named lock wait (M3, C17)

    /**
     * Every named lock of the Phase 12 lock order held in turn by a session of the application role, the act taking it
     * observed waiting there - its OWN backend, identified by the transaction id its committed row carries - unanswered
     * while the lock was held and holding nothing later in the order: L0 (the profile), B3 (the session), L6 (the capital),
     * (P) in both UUID orders (the acceptance); B5 (the purchase, the refund); B4 (the obligation, the repayment).
     */
    private void namedLockWaits() throws Exception {
        String capital = BnplStormDatabaseTest.operational("LENDING_CAPITAL");
        for (int round = 0; round < 2; round++) {
            Customer one = freshCustomer();
            Offer l0 = offered(one, merchant(round), 300_00 + round, 3);
            watch(new BnplStormLocks.Watch("L0", "SELECT 1 FROM credit.credit_profile WHERE party_id = ? FOR UPDATE",
                    one.party(), "credit_profile", "SELECT 1 FROM bnpl.bnpl_offer WHERE id = ?::uuid FOR UPDATE NOWAIT",
                    UUID.fromString(l0.offer()), acceptOn(b(), l0), "SELECT xmin::text FROM bnpl.purchase WHERE offer_id ="
                            + " ?::uuid", UUID.fromString(l0.offer())), l0);
            Offer b3 = offered(freshCustomer(), merchant(round + 1), 310_00 + round, 3);
            watch(new BnplStormLocks.Watch("B3", "SELECT 1 FROM checkout.checkout_session WHERE id = ?::uuid FOR UPDATE",
                    UUID.fromString(b3.session()), "checkout_session", "SELECT 1 FROM ledger.ledger_account WHERE id ="
                            + " ?::uuid FOR UPDATE NOWAIT", UUID.fromString(capital), acceptOn(a(), b3),
                    "SELECT xmin::text FROM bnpl.purchase WHERE offer_id = ?::uuid", UUID.fromString(b3.offer())), b3);
            Offer l6 = offered(freshCustomer(), merchant(round + 2), 320_00 + round, 3);
            watch(new BnplStormLocks.Watch("L6", "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid FOR UPDATE",
                    UUID.fromString(capital), "ledger_account", "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid"
                            + " FOR UPDATE NOWAIT", UUID.fromString(l6.merchant().payable()), acceptOn(b(), l6),
                    "SELECT xmin::text FROM bnpl.purchase WHERE offer_id = ?::uuid", UUID.fromString(l6.offer())), l6);
            for (boolean payableFirst : List.of(true, false)) {
                Customer customer = freshCustomer();
                Merchant merchant = merchantSorting(customer, payableFirst);
                Offer p = offered(customer, merchant, 400_00 + round, 0);
                fund(customer, firstInstalment(400_00 + round));
                String first = payableFirst ? merchant.payable() : customer.wallet();
                // The later lock is the pair's OTHER element: blocked on the first in UUID order, the act holds nothing
                // of the pair - an act taking them in a fixed order would hold the second while it waits.
                String second = payableFirst ? customer.wallet() : merchant.payable();
                watch(new BnplStormLocks.Watch(payableFirst ? "(P) payable first" : "(P) wallet first",
                        "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid FOR UPDATE", UUID.fromString(first),
                        "ledger_account", "SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid FOR UPDATE NOWAIT",
                        UUID.fromString(second), acceptOn(a(), p),
                        "SELECT xmin::text FROM bnpl.purchase WHERE offer_id = ?::uuid", UUID.fromString(p.offer())), p);
            }
            Bought refunded = bought(freshCustomer(), merchant(round + 3), 500_00 + round, 3);
            String refundKey = key();
            watch(new BnplStormLocks.Watch("B5", "SELECT 1 FROM bnpl.purchase WHERE id = ?::uuid FOR UPDATE",
                    UUID.fromString(refunded.purchase()), "purchase", "SELECT 1 FROM bnpl.bnpl_obligation WHERE id ="
                            + " ?::uuid FOR UPDATE NOWAIT", UUID.fromString(refunded.obligation()),
                    () -> refund(a(), refunded, 100_00, refundKey), "SELECT xmin::text FROM bnpl.bnpl_refund WHERE"
                            + " refund_key = ?", refundKey), null);
            Bought repaid = bought(freshCustomer(), merchant(round + 4), 510_00 + round, 3);
            fund(repaid.customer(), 50_00);
            String repaymentKey = key();
            watch(new BnplStormLocks.Watch("B4", "SELECT 1 FROM bnpl.bnpl_obligation WHERE id = ?::uuid FOR UPDATE",
                    UUID.fromString(repaid.obligation()), "bnpl_obligation", "SELECT 1 FROM ledger.ledger_account WHERE id"
                            + " = ?::uuid FOR UPDATE NOWAIT", UUID.fromString(repaid.customer().wallet()),
                    () -> repay(b(), repaid, 50_00, repaymentKey), "SELECT xmin::text FROM bnpl.bnpl_repayment WHERE"
                            + " request_key = ?", repaymentKey), null);
        }
        censusAfter("every named lock wait");
    }

    private Callable<Answer> acceptOn(Instance on, Offer offer) {
        return () -> on.door().post(OFFERS + "/" + offer.offer() + "/acceptance", BnplStormWorld.acceptance(offer.hash()),
                offer.customer().token(), key());
    }

    private void watch(BnplStormLocks.Watch watch, Offer offer) throws Exception {
        BnplStormLocks.Sighting sighting = BnplStormLocks.observe(watch);
        judged(tally, "named lock " + watch.lock(), sighting.answer());
        System.out.println("P12-TST-001 LOCK " + sighting.toString().replaceAll(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "{id}"));
        if (sighting.holds()) {
            storm.lockWait(watch.lock());
        } else {
            tally.unexplained("the named lock wait " + watch.lock() + " was not the act's own backend, unanswered while"
                    + " held, holding nothing later: " + sighting.lock() + " waiters " + sighting.waiters().size()
                    + ", own " + sighting.own() + ", unanswered " + sighting.unanswered() + ", later free "
                    + sighting.laterFree());
        }
        if (offer != null && sighting.answer().status() == 201) {
            bought(offer, sighting.answer().field("agreementId"));
        }
    }

    /** A merchant whose payable sorts before ({@code payableFirst}) or after the customer's wallet - a new one if none. */
    private Merchant merchantSorting(Customer customer, boolean payableFirst) throws Exception {
        UUID wallet = UUID.fromString(customer.wallet());
        for (Merchant merchant : storm.merchants()) {
            if ((UUID.fromString(merchant.payable()).compareTo(wallet) < 0) == payableFirst) {
                return merchant;
            }
        }
        for (int attempt = 0; attempt < 10; attempt++) {
            Merchant made = world.merchant("0.04", 0);
            if ((UUID.fromString(made.payable()).compareTo(wallet) < 0) == payableFirst) {
                return made;
            }
        }
        throw new AssertionError("no merchant's payable sorts on the wanted side of the wallet");
    }

    // ---------------------------------------------------------------- A2-F1: no income row held across a wallet wait

    /**
     * The income rows free across a wallet wait (the Phase 11 gate's A2-F1, designed out by the Phase 12 lock order): the
     * customer's wallet held, the act seen waiting on it - and then the currency's income projection row it will post to
     * is free to {@code NOWAIT}, while the act's own obligation's projection row is held (the control): a pay-in-N
     * servicing step (interest income - T-acc committed alone), a pay-in-4 step on the late fee's day (fee income), a full
     * refund crediting an excess to the wallet (merchant fee income).
     */
    private void incomeRowsFreeAcrossAWalletWait() throws Exception {
        Bought pn = agedPn(900_00, 31, 3, 0);
        fund(pn.customer(), instalment(pn.obligation(), 1));
        incomeFree("T-svc's interest", pn, "BNPL_INTEREST_INCOME", () -> serviceOn(a(), pn.obligation()), true);
        Bought late = agedP4(400_00, 14 + 6, 1);
        incomeFree("T-svc's late fee", late, "BNPL_FEE_INCOME", () -> serviceOn(b(), late.obligation()), true);
        Bought whole = bought(freshCustomer(), merchant(2), 420_00, 0);
        incomeFree("T-ref's fee return", whole, "BNPL_MERCHANT_FEE_INCOME", () -> refund(a(), whole, 420_00, key()),
                false);
        censusAfter("A2-F1 no income row held across a wallet wait");
    }

    private void incomeFree(String what, Bought bought, String income, Callable<Object> act, boolean control)
            throws Exception {
        String incomeAccount = BnplStormDatabaseTest.operational(income);
        String principal = scalar("SELECT id::text FROM ledger.ledger_account WHERE owner_ref = ?::uuid AND purpose ="
                + " 'BNPL_PRINCIPAL'", UUID.fromString(bought.obligation()));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement("SELECT 1 FROM ledger.ledger_account WHERE id = ?::uuid"
                    + " FOR UPDATE")) {
                lock.setObject(1, UUID.fromString(bought.customer().wallet()));
                lock.executeQuery().close();
            }
            // The permit handed back only once the wallet is held: an instance's own sweep taking the step first then
            // waits on the wallet too, never completes it before the hold (run 2's flake: the sweep serviced the
            // obligation in the gap, and the act found nothing left to collect).
            if (control) {
                BnplStormWorld.permit(bought.obligation(), "statement_timestamp()");
            }
            Future<Object> acting = pool.submit(act);
            boolean waiting = awaitWaiter(BnplStormLocks.pid(holder), acting);
            boolean incomeFree = freeOrAbsent("ledger.account_balance", "ledger_account_id", incomeAccount);
            boolean principalHeld = !freeOrAbsent("ledger.account_balance", "ledger_account_id", principal);
            holder.rollback();
            Object done = acting.get(2, TimeUnit.MINUTES);
            if (done instanceof Answer response) {
                judged(tally, "A2-F1 " + what, response);
            }
            if (waiting && incomeFree && (!control || principalHeld)) {
                storm.coverage("A2-F1 no income row held across a wallet wait", 1);
            } else {
                tally.unexplained("A2-F1 " + what + ": waiting on the wallet " + waiting + ", the income row free "
                        + incomeFree + ", the control held " + principalHeld);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static boolean awaitWaiter(int blocker, Future<?> act) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !act.isDone()) {
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ?"
                            + " = ANY (pg_blocking_pids(pid)) AND state = 'active' AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet row = select.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return true;
                    }
                }
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static boolean freeOrAbsent(String table, String column, String id) throws SQLException {
        if (count("SELECT count(*) FROM " + table + " WHERE " + column + " = ?::uuid", UUID.fromString(id)) == 0) {
            return true;
        }
        return BnplStormLocks.lockableNoWait("SELECT 1 FROM " + table + " WHERE " + column + " = ?::uuid FOR UPDATE"
                + " NOWAIT", UUID.fromString(id));
    }

    // ---------------------------------------------------------------- scenario 1

    /** Scenario 1: one acceptance key on A and B at once - one purchase; ten keys on both - one purchase. */
    private void sameAcceptanceTwice() throws Exception {
        for (int i = 0; i < 3; i++) {
            Offer offer = offered(freshCustomer(), merchant(i), 250_00 + i, 0);
            fund(offer.customer(), firstInstalment(offer.gross()));
            String acceptanceKey = key();
            String body = BnplStormWorld.acceptance(offer.hash());
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(OFFERS + "/" + offer.offer() + "/acceptance", body, offer.customer().token(),
                            acceptanceKey),
                    () -> b().door().post(OFFERS + "/" + offer.offer() + "/acceptance", body, offer.customer().token(),
                            acceptanceKey));
            for (Object one : raced) {
                judged(tally, "scenario 1 one key", answer(one), "409 api.IdempotencyInProgress");
            }
            if (count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid", UUID.fromString(offer.offer())) == 1) {
                storm.coverage("scenario 1 one acceptance key on both instances finances once", 1);
                Answer replayed = a().door().post(OFFERS + "/" + offer.offer() + "/acceptance", body,
                        offer.customer().token(), acceptanceKey);
                judged(tally, "scenario 1 replayed", replayed);
                bought(offer, replayed.field("agreementId"));
            }
        }
        Offer ten = offered(freshCustomer(), merchant(4), 260_00, 3);
        List<Callable<Answer>> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instance on = i % 2 == 0 ? a() : b();
            keys.add(acceptOn(on, ten));
        }
        int financed = 0;
        for (Object one : allRaw(keys)) {
            Answer response = answer(one);
            judged(tally, "scenario 1 ten keys", response, "409 bnpl.OfferNotAcceptable");
            financed += response.status() == 201 ? 1 : 0;
        }
        if (financed == 1 && count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid",
                UUID.fromString(ten.offer())) == 1) {
            storm.coverage("scenario 1 ten keys finance once", 1);
            secondPurchaseOnASession(ten);
        }
        censusAfter("scenario 1, the same acceptance twice");
    }

    /**
     * The session unique's own reach (the `P11-TST-001` owner decision Q4, a writer that bypasses the doors): every door
     * refuses a second purchase on a session before any row - the application's one open per session, the session's
     * status under B3, the offer's unique - so only the owner, triggers suspended, can try one: a copy of the financed
     * purchase under a fresh id and offer. {@code purchase_checkout_session_unique} refuses it; were it gone, C7 ("one
     * session, one purchase") reads the copy at the scene's census.
     */
    private void secondPurchaseOnASession(Offer financed) throws Exception {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("CREATE TEMP TABLE planted ON COMMIT DROP AS SELECT * FROM bnpl.purchase WHERE offer_id ="
                        + " '" + financed.offer() + "'::uuid");
                statement.execute("UPDATE planted SET id = gen_random_uuid(), offer_id = gen_random_uuid()");
                if (statement.executeUpdate("INSERT INTO bnpl.purchase SELECT * FROM planted") != 1) {
                    throw new IllegalStateException("the financed purchase to copy is absent");
                }
                owner.commit();
                tally.unexplained("scenario 1: a second purchase planted on a financed session stood");
            } catch (SQLException refused) {
                owner.rollback();
                if ("23505".equals(refused.getSQLState())) {
                    storm.coverage("scenario 1 a second purchase on one session refused by its unique", 1);
                } else {
                    tally.unexplained("scenario 1: the planted second purchase failed " + refused.getSQLState());
                }
            }
        }
    }

    // ---------------------------------------------------------------- scenario 2

    /**
     * Scenario 2: one session raced by a BNPL acceptance and a card - or a wallet - confirmation, at a barrier, the first
     * two of each in a fixed order (the instrument first, then the acceptance first), the rest at one instant: one order,
     * paid by exactly one of them, whatever the order.
     */
    private void bnplAgainstACardOrAWallet() throws Exception {
        for (String instrument : List.of("card", "wallet")) {
            for (int i = 0; i < 4; i++) {
                Offer offer = offered(freshCustomer(), merchant(i), 280_00 + i, 0);
                fund(offer.customer(), firstInstalment(offer.gross()) + ("wallet".equals(instrument) ? offer.gross() : 0));
                long acceptanceLag = i == 0 ? 400 : 0;
                long confirmationLag = i == 1 ? 400 : 0;
                String confirmation = "{\"sessionToken\":\"" + offer.sessionToken() + "\"," + ("card".equals(instrument)
                        ? "\"paymentMethodId\":\"" + offer.customer().card() + "\"" : "\"instrument\":\"WALLET\"") + "}";
                List<Object> raced = bothAtOnce(
                        () -> {
                            Thread.sleep(acceptanceLag);
                            return b().door().post(OFFERS + "/" + offer.offer() + "/acceptance",
                                    BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), key());
                        },
                        () -> {
                            Thread.sleep(confirmationLag);
                            return a().door().post(CONFIRMATION, confirmation, offer.customer().token(), null);
                        });
                Answer accepted = answer(raced.get(0));
                Answer confirmed = answer(raced.get(1));
                judged(tally, "scenario 2 acceptance", accepted, "409 bnpl.SessionNotPayable");
                judged(tally, "scenario 2 " + instrument + " confirmation", confirmed, "409 checkout.NotConfirmable");
                long orders = count("SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?::uuid",
                        UUID.fromString(offer.session()));
                long purchases = count("SELECT count(*) FROM bnpl.purchase WHERE checkout_session_id = ?::uuid",
                        UUID.fromString(offer.session()));
                boolean bnplWon = accepted.status() == 201;
                boolean instrumentWon = confirmed.status() == 200 && "COMPLETED".equals(confirmed.optionalField("status")
                        .orElse(""));
                if (orders == 1 && purchases == (bnplWon ? 1 : 0) && bnplWon != instrumentWon) {
                    storm.coverage("scenario 2 a " + instrument + " confirmation and an acceptance on one session"
                            + " complete it once", 1);
                } else {
                    tally.unexplained("scenario 2 (" + instrument + "): orders " + orders + ", purchases " + purchases
                            + ", the acceptance " + accepted.status() + ", the confirmation " + confirmed.status());
                }
                if (bnplWon) {
                    bought(offer, accepted.field("agreementId"));
                }
            }
        }
        censusAfter("scenario 2, BNPL against a card or a wallet");
    }

    // ---------------------------------------------------------------- scenario 3

    /** Scenario 3: a BNPL acceptance on A racing a loan application - and its decision - on B for the same party. */
    private void acceptanceAndADecisionSerialise() throws Exception {
        for (int i = 0; i < 3; i++) {
            Offer offer = offered(freshCustomer(), merchant(i), 600_00, 3);
            List<Object> raced = bothAtOnce(acceptOn(a(), offer),
                    () -> b().door().post(BnplStormWorld.LOAN_APPLICATIONS, BnplStormWorld.loanApplication(2_000_00, 12,
                            10), offer.customer().token(), key()));
            Answer accepted = answer(raced.get(0));
            Answer applied = answer(raced.get(1));
            judged(tally, "scenario 3 acceptance", accepted, "422 bnpl.CapitalUnavailable");
            judged(tally, "scenario 3 loan application", applied, "409 credit.DecisionRequestOpen");
            if (accepted.status() == 201) {
                bought(offer, accepted.field("agreementId"));
                if (applied.status() == 202) {
                    BnplStormWorld.awaitDatabase(PATIENCE, "the party's loan application decided", "SELECT status NOT IN"
                            + " ('SUBMITTED', 'AWAITING_DECISION') FROM lending.loan_application WHERE id = ?::uuid",
                            UUID.fromString(applied.field("applicationId")));
                }
                storm.coverage("scenario 3 an acceptance and a decision for one party serialise", 1);
            }
        }
        censusAfter("scenario 3, an acceptance and a decision for one party");
    }

    // ---------------------------------------------------------------- (P) in both orders

    /**
     * (P), both UUID orders (`PHASE_12_PLAN.md` section 7.3 (b)): a pay-in-4 acceptance and a wallet-paid capture by the
     * same customer at the same merchant at a barrier - merchants whose payable sorts before and after the wallet - never
     * {@code 40P01} (the deadlock census), both answered.
     */
    private void thePairInBothOrders() throws Exception {
        for (boolean payableFirst : List.of(true, false)) {
            for (int round = 0; round < 3; round++) {
                Customer customer = freshCustomer();
                Merchant merchant = merchantSorting(customer, payableFirst);
                Offer offer = offered(customer, merchant, 300_00 + round, 0);
                String[] sale = BnplStormWorld.openSession(a().door(), merchant, 20_00, tally);
                fund(customer, firstInstalment(offer.gross()) + 20_00);
                List<Object> raced = bothAtOnce(acceptOn(a(), offer),
                        () -> b().door().post(CONFIRMATION, "{\"sessionToken\":\"" + sale[1] + "\",\"instrument\":"
                                + "\"WALLET\"}", customer.token(), null));
                Answer accepted = answer(raced.get(0));
                Answer captured = answer(raced.get(1));
                judged(tally, "(P) acceptance", accepted);
                judged(tally, "(P) wallet capture", captured);
                if (accepted.status() == 201 && captured.status() == 200) {
                    bought(offer, accepted.field("agreementId"));
                    storm.coverage("(P) a BNPL acceptance beside a wallet-paid capture, " + (payableFirst ? "payable"
                            : "wallet") + " first", 1);
                }
            }
        }
        censusAfter("(P) in both UUID orders");
    }

    // ---------------------------------------------------------------- scenario 4

    /**
     * Scenario 4: a pay-in-4 obligation aged so instalment 2 falls due today, its wallet funded with exactly the bill; ten
     * servicing steps - five on each instance - at a barrier: one attempt, one collection. And a sweep beside the customer's
     * own repayment of the same bill: conserved, one collection at most.
     */
    private void tenSweepersCollectOnce() throws Exception {
        for (Bought aged : agedP4s(3, 400_00, 14)) {
            fund(aged.customer(), instalment(aged.obligation(), 2));
            List<Callable<Object>> racers = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                Instance on = r % 2 == 0 ? a() : b();
                racers.add(() -> serviceOn(on, aged.obligation()));
            }
            for (Object done : allRaw(racers)) {
                if (done instanceof Throwable failure) {
                    tally.unexplained("scenario 4: a servicing step failed - " + failure.getClass().getSimpleName());
                }
            }
            if (count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid AND outcome ="
                    + " 'COLLECTED'", UUID.fromString(aged.obligation())) == 1 && count("SELECT count(*) FROM"
                    + " bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid", UUID.fromString(aged.obligation()))
                    == 1) {
                storm.coverage("scenario 4 ten sweepers collect once", 1);
            }
        }
        Bought raced = agedP4(410_00, 14, 3);
        long bill = instalment(raced.obligation(), 2);
        fund(raced.customer(), bill);
        List<Object> answers = bothAtOnce(() -> serviceOn(a(), raced.obligation()), () -> repay(b(), raced, bill, key()));
        if (answers.get(1) instanceof Answer repaid) {
            judged(tally, "scenario 4 the customer's repayment", repaid, "422 bnpl.InsufficientFunds");
        }
        if (count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid",
                UUID.fromString(raced.obligation())) <= 1) {
            storm.coverage("scenario 4 a sweep beside the customer's repayment collects once", 1);
        }
        censusAfter("scenario 4, ten sweepers");
    }

    // ---------------------------------------------------------------- scenario 5, both variants

    /**
     * Scenario 5 (the merchant variant): a payout of BNPL-financed money whose answer is lost - merchant UNKNOWN, the hold
     * standing; the merchant's retry under the same key on the other instance replays (no second dispatch); the inquiry
     * answered paid concludes it COMPLETED once; dispatches counted on both sides - one row, one send.
     */
    private void lostPayoutResponses() throws Exception {
        for (int i = 0; i < 2; i++) {
            Merchant merchant = world.merchant("0.04", 0);
            storm.merchants().add(merchant);
            bought(freshCustomer(), merchant, 800_00 + i, 3);
            storm.provider().losesTheResponseWhenTheBodyContains(PAYOUT_PATH, merchant.destination());
            String payoutKey = key();
            Answer lost = a().door().post(PAYOUTS, money(500_00), merchant.apiKey(), payoutKey);
            judged(tally, "scenario 5 payout", lost);
            String payout = lost.field("id");
            String held = lost.optionalField("status").orElse("?");
            for (int retry = 0; retry < 2; retry++) {
                Answer replayed = b().door().post(PAYOUTS, money(500_00), merchant.apiKey(), payoutKey);
                judged(tally, "scenario 5 payout retried", replayed);
                if (!payout.equals(replayed.optionalField("id").orElse(""))) {
                    tally.unexplained("scenario 5: the retried payout key answered another payout");
                }
            }
            Thread.sleep(1_500); // several resolution sweeps on both instances while it is UNKNOWN: never re-sent
            String reference = scalar("SELECT provider_idempotency_reference FROM merchant.merchant_payout WHERE id ="
                    + " ?::uuid", UUID.fromString(payout));
            storm.answerInquiries(false);
            BnplStormWorld.awaitDatabase(PATIENCE, "the lost payout concluded by inquiry", "SELECT status = 'COMPLETED'"
                    + " FROM merchant.merchant_payout WHERE id = ?::uuid", UUID.fromString(payout));
            long sends = storm.provider().headerValues(PAYOUT_PATH, "Idempotency-Key").stream()
                    .filter(reference::equals).count();
            long rows = count("SELECT count(*) FROM merchant.merchant_payout WHERE merchant_id = ?::uuid",
                    UUID.fromString(merchant.id()));
            long posted = count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope ="
                    + " 'ledger.post:merchant-payout:' || ?", payout);
            if ("UNKNOWN".equals(held) && sends == 1 && rows == 1 && posted == 1) {
                storm.coverage("scenario 5 a lost payout response held, the retry replayed, concluded once", 1);
            } else {
                tally.unexplained("scenario 5: held " + held + ", sends " + sends + ", rows " + rows + ", posted " + posted);
            }
        }
        censusAfter("scenario 5, a lost payout response");
    }

    /**
     * Scenario 5's wallet variant: a pay-in-4 obligation aged so instalment 2 falls due today, its wallet empty; the
     * customer tops up by card for exactly the bill - the PSP captures and the answer is lost; the retry under the same
     * key on the other instance replays the same payment; payments' sweep concludes it once from the PSP's truthful
     * inquiry; then the servicing step collects the instalment once.
     */
    private void lostCardTopUp() throws Exception {
        Bought aged = agedP4(436_00, 14, 0);
        long bill = instalment(aged.obligation(), 2);
        storm.provider().losesTheResponseWhenTheBodyContains(SimulatedCardPspAdapter.CAPTURES_PATH,
                "\"amountMinor\":\"" + bill + "\"");
        String payKey = key();
        String[] status = new String[1];
        String intent = BnplStormWorld.payIn(a().door(), b().door(), aged.customer(), bill, payKey, tally, status);
        Answer replayed = b().door().post("/v1/payments", "{\"paymentMethodId\":\"" + aged.customer().card()
                + "\",\"amount\":\"" + decimal(bill) + "\",\"currency\":\"EUR\"}", aged.customer().token(), payKey);
        judged(tally, "scenario 5w the pay-in key replayed", replayed);
        boolean same = intent.equals(replayed.optionalField("id").orElse(""));
        String reference = scalar("SELECT capture_reference FROM payments.payment_attempt WHERE intent_id = ?::uuid",
                UUID.fromString(intent));
        BnplStormWorld.awaitDatabase(PATIENCE, "the lost capture concluded by payments' inquiry", "SELECT EXISTS (SELECT"
                + " 1 FROM payments.payment_attempt WHERE intent_id = ?::uuid AND status = 'CAPTURED')"
                + " AND (SELECT answered FROM (SELECT ?::text AS answered) x) IS NOT NULL", UUID.fromString(intent),
                answerAndReturn(reference));
        storm.provider().succeedsWithMintedReference(SimulatedCardPspAdapter.CAPTURES_PATH, "psp_c");
        BnplStormWorld.permit(aged.obligation(), "statement_timestamp()");
        try {
            BnplStormWorld.awaitDatabase(PATIENCE, "the instalment collected", "SELECT EXISTS (SELECT 1 FROM"
                    + " bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid AND outcome = 'COLLECTED')",
                    UUID.fromString(aged.obligation()));
        } catch (AssertionError never) {
            try (Connection snapshot = DatabaseRoles.application()) {
                snapshot.setAutoCommit(false);
                BnplStormCensus.dump(snapshot, "scenario 5w: never collected (wallet " + aged.customer().wallet()
                        + ", intent " + intent + ")", UUID.fromString(aged.obligation()));
                snapshot.rollback();
            }
            throw never;
        }
        long captures = reference == null ? -1 : storm.provider().headerValues(SimulatedCardPspAdapter.CAPTURES_PATH,
                "Idempotency-Key").stream().filter(reference::equals).count();
        long collected = count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid AND"
                + " outcome = 'COLLECTED'", UUID.fromString(aged.obligation()));
        long intents = count("SELECT count(*) FROM payments.payment_intent i JOIN payments.payment_attempt a ON"
                + " a.intent_id = i.id WHERE i.id = ?::uuid AND a.status = 'CAPTURED'", UUID.fromString(intent));
        if (!"SUCCEEDED".equals(status[0]) && same && captures == 1 && collected == 1 && intents == 1) {
            storm.coverage("scenario 5 wallet variant: a lost card top-up replayed, concluded once, the instalment"
                    + " collected once", 1);
        } else {
            tally.unexplained("scenario 5w: confirmed " + status[0] + ", the same payment " + same + ", captures sent "
                    + captures + ", collected " + collected + ", captured attempts " + intents);
        }
        censusAfter("scenario 5, the wallet variant");
    }

    /** The PSP's truthful answer to the lost capture's inquiry, armed now; the reference back. */
    private String answerAndReturn(String reference) throws SQLException {
        storm.answerInquiries(false);
        return reference;
    }

    // ---------------------------------------------------------------- scenario 6

    /**
     * Scenario 6: two repayments of one instalment on A and B and a servicing step, at a barrier - every other race under a
     * PLANTED projection drift of the obligation's billed principal (the due state is derived from rows and lines, never
     * the projection, so nothing may notice; the drift put back after).
     */
    private void twoRepaymentsOnOneInstalment() throws Exception {
        List<Bought> aged = agedP4s(4, 420_00, 14);
        for (int i = 0; i < aged.size(); i++) {
            Bought one = aged.get(i);
            serviceOn(a(), one.obligation()); // bills instalment 2, the wallet empty: nothing collected
            long bill = instalment(one.obligation(), 2);
            // Exactly what the three acts could take between them: the two halves and the step's whole bill - whichever
            // order the race takes, nothing is refused for money and instalment 2 is paid once, the rest held as credit.
            fund(one.customer(), bill / 2 + (bill / 2 + 1) + bill);
            boolean drifted = i % 2 == 0;
            if (drifted) {
                BnplStormFaults.plantProjectionDrift(one.obligation(), "BNPL_PRINCIPAL_DUE", 1_00);
            }
            List<Object> answers;
            try {
                // The first two with the step half a second behind (the repayments share the instalment), the rest at
                // one instant.
                long stepLag = i < 2 ? 500 : 0;
                answers = allRaw(List.of(
                        () -> repay(a(), one, bill / 2, key()),
                        () -> repay(b(), one, bill / 2 + 1, key()),
                        () -> {
                            Thread.sleep(stepLag);
                            return serviceOn(b(), one.obligation());
                        }));
            } finally {
                if (drifted) {
                    BnplStormFaults.plantProjectionDrift(one.obligation(), "BNPL_PRINCIPAL_DUE", -1_00);
                }
            }
            for (Object done : answers) {
                if (done instanceof Answer response) {
                    judged(tally, "scenario 6 repayment", response);
                } else if (done instanceof Throwable failure) {
                    tally.unexplained("scenario 6: a step failed - " + failure.getClass().getSimpleName());
                }
            }
            if (count("SELECT count(*) FROM (SELECT a.instalment_id FROM bnpl.bnpl_repayment_allocation a JOIN"
                    + " bnpl.bnpl_repayment r ON r.id = a.repayment_id WHERE r.obligation_id = ?::uuid AND"
                    + " a.instalment_id IS NOT NULL AND r.kind <> 'AT_PURCHASE' GROUP BY a.instalment_id HAVING"
                    + " count(DISTINCT r.id) >= 2) shared", UUID.fromString(one.obligation())) >= 1) {
                storm.coverage("scenario 6 two repayments on one instalment", 1);
            }
            if (count("SELECT 1 FROM bnpl.bnpl_repayment_allocation a JOIN bnpl.bnpl_repayment r ON r.id ="
                    + " a.repayment_id JOIN bnpl.bnpl_instalment i ON i.id = a.instalment_id WHERE r.obligation_id ="
                    + " ?::uuid AND i.sequence = 2 HAVING sum(a.amount_minor) = ?", UUID.fromString(one.obligation()),
                    bill) == 1) {
                storm.coverage("scenario 6 the raced instalment paid once, whatever the order", 1);
            }
        }
        censusAfter("scenario 6, two repayments on one instalment");
    }

    // ---------------------------------------------------------------- scenario 7

    /**
     * Scenario 7: a refund racing the collection of the instalment due today, both orders - the first two with a head
     * start for one side, the rest at one instant; the order judged from the rows (the attempt's and the refund's stamps,
     * both written under B4): collected first - the excess to the customer; refunded first - nothing left to collect.
     */
    private void refundRacingACollection() throws Exception {
        List<Bought> aged = agedP4s(4, 440_00, 14);
        for (int i = 0; i < aged.size(); i++) {
            Bought one = aged.get(i);
            long bill = instalment(one.obligation(), 2);
            fund(one.customer(), bill);
            long refundLag = i == 0 ? 500 : 0;
            long stepLag = i == 1 ? 500 : 0;
            List<Object> answers = bothAtOnce(
                    () -> {
                        Thread.sleep(refundLag);
                        return refund(a(), one, 300_00, key());
                    },
                    () -> {
                        Thread.sleep(stepLag);
                        return serviceOn(b(), one.obligation());
                    });
            judged(tally, "scenario 7 refund", answer(answers.get(0)));
            if (answers.get(1) instanceof Throwable failure) {
                tally.unexplained("scenario 7: the step failed - " + failure.getClass().getSimpleName());
            }
            String order = scalar("SELECT CASE WHEN c.created_at < r.created_at THEN 'collected' ELSE 'refunded' END FROM"
                    + " bnpl.bnpl_refund r LEFT JOIN bnpl.bnpl_collection_attempt c ON c.obligation_id = r.obligation_id"
                    + " AND c.outcome = 'COLLECTED' WHERE r.obligation_id = ?::uuid", UUID.fromString(one.obligation()));
            if (order != null) {
                storm.coverage("scenario 7 a refund racing a collection: " + order + " first", 1);
            }
        }
        censusAfter("scenario 7, a refund racing a collection");
    }

    // ---------------------------------------------------------------- scenario 8

    /** Scenario 8: one refund key on A and B at once - one refund; the merchant's retry - the same refund. */
    private void sameRefundTwice() throws Exception {
        for (int i = 0; i < 3; i++) {
            Bought one = bought(freshCustomer(), merchant(i), 450_00 + i, 3);
            String refundKey = key();
            List<Object> raced = bothAtOnce(() -> refund(a(), one, 120_00, refundKey),
                    () -> refund(b(), one, 120_00, refundKey));
            for (Object done : raced) {
                judged(tally, "scenario 8 refund", answer(done), "409 api.IdempotencyInProgress");
            }
            Answer retried = refund(b(), one, 120_00, refundKey);
            judged(tally, "scenario 8 retried", retried);
            if (count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                    UUID.fromString(one.purchase())) == 1) {
                storm.coverage("scenario 8 one refund key on both instances refunds once", 1);
            }
        }
        censusAfter("scenario 8, the same refund twice");
    }

    // ---------------------------------------------------------------- scenarios 9 and 16

    /**
     * Scenario 9: a refund before the instalments (only instalment 1 paid), during them (instalment 2 collected), after the
     * obligation closed (all to the wallet), beyond what is owed (the excess to the customer), and full refunds split in
     * three awkward parts - closing with nothing stranded and the fee telescoped to exactly f; scenario 16: a repayment
     * after a full refund refused.
     */
    private void refundsBeforeDuringAfter() throws Exception {
        Bought before = bought(freshCustomer(), merchant(0), 480_00, 0);
        judged(tally, "scenario 9 before", refund(a(), before, 100_00, key()));
        if (count("SELECT count(*) FROM bnpl.bnpl_refund_adjustment a JOIN bnpl.bnpl_refund r ON r.id = a.refund_id WHERE"
                + " r.purchase_id = ?::uuid AND a.kind = 'UNBILLED'", UUID.fromString(before.purchase())) >= 1) {
            storm.coverage("scenario 9 a refund before the instalments", 1);
        }
        Bought during = agedP4(490_00, 14, 1);
        fund(during.customer(), instalment(during.obligation(), 2));
        serviceOn(b(), during.obligation());
        judged(tally, "scenario 9 during", refund(a(), during, 150_00, key()));
        if (count("SELECT count(*) FROM bnpl.bnpl_collection_attempt WHERE obligation_id = ?::uuid AND outcome ="
                + " 'COLLECTED'", UUID.fromString(during.obligation())) == 1 && count("SELECT count(*) FROM"
                + " bnpl.bnpl_refund WHERE purchase_id = ?::uuid", UUID.fromString(during.purchase())) == 1) {
            storm.coverage("scenario 9 a refund during the instalments", 1);
        }
        Bought after = bought(freshCustomer(), merchant(2), 300_00, 0);
        storm.payOff(a(), after.customer(), after.agreement());
        judged(tally, "scenario 9 after closure", refund(b(), after, 90_00, key()));
        if ("CLOSED".equals(status(after.obligation())) && count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id"
                + " = ?::uuid AND excess_minor = amount_minor", UUID.fromString(after.purchase())) == 1) {
            storm.coverage("scenario 9 a refund after closure, all to the wallet", 1);
        }
        Bought excess = agedP4(500_00, 14, 3);
        fund(excess.customer(), instalment(excess.obligation(), 2));
        serviceOn(a(), excess.obligation());
        judged(tally, "scenario 9 beyond what is owed", refund(b(), excess, 400_00, key()));
        if (count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid AND excess_minor > 0",
                UUID.fromString(excess.purchase())) == 1) {
            storm.coverage("scenario 9 a refund beyond what is owed: the excess to the customer", 1);
        }
        for (int i = 0; i < 2; i++) {
            long gross = 333_33 + 7 * i;
            Bought whole = bought(freshCustomer(), merchant(4 + i), gross, i == 0 ? 0 : 3);
            long first = gross / 3 + 1;
            long second = gross / 3 - 1;
            judged(tally, "scenario 9 full refund part 1", refund(a(), whole, first, key()));
            judged(tally, "scenario 9 full refund part 2", refund(b(), whole, second, key()));
            judged(tally, "scenario 9 full refund part 3", refund(a(), whole, gross - first - second, key()));
            String[] closed = row("SELECT o.status, (SELECT sum(r.fee_returned_minor) FROM bnpl.bnpl_refund r WHERE"
                    + " r.purchase_id = o.purchase_id)::text, (SELECT f.fee_minor FROM bnpl.merchant_financing f WHERE"
                    + " f.purchase_id = o.purchase_id)::text FROM bnpl.bnpl_obligation o WHERE o.id = ?::uuid",
                    UUID.fromString(whole.obligation()));
            if ("CLOSED".equals(closed[0]) && closed[1] != null && closed[1].equals(closed[2])) {
                storm.coverage("scenario 9 a full refund closes with nothing stranded, the fee telescoped", 1);
            }
            Answer afterAll = repay(b(), whole, 10_00, key());
            refused(tally, "scenario 16 a repayment after a full refund", afterAll, "409 bnpl.ObligationClosed");
            if (afterAll.is(409, "bnpl.ObligationClosed")) {
                storm.coverage("scenario 16 a repayment after a full refund is refused", 1);
            }
        }
        censusAfter("scenario 9, refunds before, during and after");
    }

    // ---------------------------------------------------------------- scenario 10

    /**
     * Scenario 10: a dispute's refund and a merchant's refund share one bound - the merchant's acceptance of the dispute on
     * A racing its own refund on B, together beyond the gross; the merchant's silence to the deadline; the operator's
     * resolution above the four-eyes threshold, proposed on one instance and approved on the other; an upheld dispute.
     */
    private void disputesShareTheBound() throws Exception {
        String[] agents = storm.agents();
        for (int i = 0; i < 3; i++) {
            Bought one = bought(freshCustomer(), merchant(i), 500_00 + i, 3);
            Answer opened = a().door().post(AGREEMENTS + "/" + one.agreement() + "/disputes", "{\"amount\":\"300.00\","
                    + "\"currency\":\"EUR\",\"category\":\"NOT_RECEIVED\",\"reason\":\"it never came\"}",
                    one.customer().token(), key());
            judged(tally, "scenario 10 dispute opened", opened);
            String dispute = opened.field("disputeId");
            storm.reserved().add(dispute);
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(MERCHANT_DISPUTES + "/" + dispute + "/response", "{\"decision\":\"ACCEPT\","
                            + "\"statement\":\"refund it\"}", one.merchant().refundKey(), key()),
                    () -> refund(b(), one, 300_00, key()));
            judged(tally, "scenario 10 acceptance", answer(raced.get(0)), "409 bnpl.DisputeNotActionable");
            judged(tally, "scenario 10 merchant refund", answer(raced.get(1)), "409 bnpl.DisputeOpen",
                    "422 bnpl.RefundExceedsPurchase");
            long refunded = count("SELECT coalesce(sum(amount_minor), 0) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                    UUID.fromString(one.purchase()));
            if (refunded <= one.gross()) {
                storm.coverage("scenario 10 a dispute's refund and a merchant's refund share one bound", 1);
            }
        }
        Bought silent = bought(freshCustomer(), merchant(3), 520_00, 3);
        Answer opened = b().door().post(AGREEMENTS + "/" + silent.agreement() + "/disputes", "{\"amount\":\"120.00\","
                + "\"currency\":\"EUR\",\"category\":\"NOT_RECEIVED\",\"reason\":\"it never came\"}",
                silent.customer().token(), key());
        judged(tally, "scenario 10 silent dispute opened", opened);
        String quiet = opened.field("disputeId");
        storm.reserved().add(quiet);
        BnplStormWorld.asBootstrap("UPDATE bnpl.bnpl_dispute SET response_deadline_at = statement_timestamp(),"
                + " next_sweep_at = statement_timestamp() WHERE id = '" + quiet + "'::uuid");
        BnplStormWorld.awaitDatabase(PATIENCE, "the silence resolved it", "SELECT status = 'RESOLVED_REFUND' FROM"
                + " bnpl.bnpl_dispute WHERE id = ?::uuid", UUID.fromString(quiet));
        storm.coverage("scenario 10 a dispute resolved by the merchant's silence", 1);
        for (String outcome : List.of("REFUND", "UPHOLD")) {
            Bought contested = bought(freshCustomer(), merchant(4), 600_00, 3);
            Answer contest = a().door().post(AGREEMENTS + "/" + contested.agreement() + "/disputes",
                    "{\"amount\":\"400.00\",\"currency\":\"EUR\",\"category\":\"NOT_AS_DESCRIBED\",\"reason\":\"not as"
                            + " shown\"}", contested.customer().token(), key());
            judged(tally, "scenario 10 contested dispute opened", contest);
            String id = contest.field("disputeId");
            storm.reserved().add(id);
            judged(tally, "scenario 10 contested", b().door().post(MERCHANT_DISPUTES + "/" + id + "/response",
                    "{\"decision\":\"CONTEST\",\"statement\":\"we shipped it\"}", contested.merchant().apiKey(), key()));
            storm.resolveDispute(a(), id, outcome, agents[0], agents[1]);
            String resolved = scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = ?::uuid", UUID.fromString(id));
            if (("REFUND".equals(outcome) ? "RESOLVED_REFUND" : "RESOLVED_UPHELD").equals(resolved)) {
                storm.coverage("scenario 10 a dispute resolved by the operator under four eyes", 1);
            }
        }
        censusAfter("scenario 10, disputes and refunds share one bound");
    }

    // ---------------------------------------------------------------- scenario 11

    /** Scenario 11: a payoff executed on A while a refund lands on B - stale or closed, never wrong. */
    private void payoffBesideARefund() throws Exception {
        for (int i = 0; i < 3; i++) {
            Bought one = bought(freshCustomer(), merchant(i), 540_00 + i, 3);
            Answer quoted = a().door().post(AGREEMENTS + "/" + one.agreement() + "/payoff-quotes", "{}",
                    one.customer().token(), key());
            judged(tally, "scenario 11 quote", quoted);
            long amount = BnplStormWorld.minor(quoted.field("amount"));
            BnplStormWorld.RENDERED_AMOUNTS.add(amount);
            fund(one.customer(), amount);
            List<Object> raced = bothAtOnce(
                    () -> a().door().post(AGREEMENTS + "/" + one.agreement() + "/payoff", "{\"quoteId\":\""
                            + quoted.field("quoteId") + "\"}", one.customer().token(), key()),
                    () -> refund(b(), one, 100_00, key()));
            Answer paidOff = answer(raced.get(0));
            judged(tally, "scenario 11 payoff", paidOff, "409 bnpl.PayoffQuoteStale");
            judged(tally, "scenario 11 refund", answer(raced.get(1)));
            String now = status(one.obligation());
            if ((paidOff.is(409, "bnpl.PayoffQuoteStale") && "ACTIVE".equals(now))
                    || (paidOff.status() == 201 && "CLOSED".equals(now))) {
                storm.coverage("scenario 11 a payoff beside a refund: stale or closed, never wrong", 1);
            }
        }
        censusAfter("scenario 11, a payoff beside a refund");
    }

    // ---------------------------------------------------------------- scenario 12

    /**
     * Scenario 12: a repayment reversed after it was allocated - a later repayment present - proposed on A, approved on B
     * racing a third repayment; the later repayment untouched.
     */
    private void reversalAfterAllocation() throws Exception {
        String[] agents = storm.agents();
        for (int i = 0; i < 3; i++) {
            Bought one = bought(freshCustomer(), merchant(i), 560_00 + i, 3);
            fund(one.customer(), 90_00);
            Answer first = repay(a(), one, 40_00, key());
            judged(tally, "scenario 12 first repayment", first);
            Answer later = repay(b(), one, 25_00, key());
            judged(tally, "scenario 12 later repayment", later);
            String reversal = OPERATOR_AGREEMENTS + "/" + one.agreement() + "/repayments/" + first.field("repaymentId")
                    + "/reversal";
            Answer proposed = a().door().post(reversal, "{\"reason\":\"the transfer was recalled\"}", agents[0], key());
            judged(tally, "scenario 12 propose", proposed);
            String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"matched\"}";
            List<Object> raced = bothAtOnce(() -> b().door().post(reversal + "/approval", decision, agents[1], key()),
                    () -> repay(a(), one, 25_00, key()));
            for (Object done : raced) {
                judged(tally, "scenario 12 race", answer(done), "422 bnpl.InsufficientFunds");
            }
            if (count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE id = ?::uuid AND status = 'APPROVED'",
                    UUID.fromString(proposed.field("reversalId"))) == 1 && count("SELECT count(*) FROM"
                    + " bnpl.bnpl_repayment_allocation_reversal n JOIN bnpl.bnpl_repayment_allocation x ON x.id ="
                    + " n.allocation_id WHERE x.repayment_id = ?::uuid", UUID.fromString(later.field("repaymentId")))
                    == 0) {
                storm.coverage("scenario 12 a reversal after allocation, a later repayment untouched", 1);
            }
        }
        censusAfter("scenario 12, a reversal after allocation");
    }

    // ---------------------------------------------------------------- scenario 13

    /** Scenario 13: a delinquent obligation's servicing step (the condition worker) on A against a payoff on B. */
    private void noConditionAfterClosure() throws Exception {
        for (Bought aged : agedP4s(3, 380_00, 14 + 10)) {
            serviceOn(b(), aged.obligation()); // past due, the wallet empty: the condition recorded
            List<Object> raced = bothAtOnce(() -> serviceOn(a(), aged.obligation()),
                    () -> storm.payOff(b(), aged.customer(), aged.agreement()));
            if (raced.get(0) instanceof Throwable failure) {
                tally.unexplained("scenario 13: the servicing step failed - " + failure.getClass().getSimpleName());
            }
            if ("CLOSED".equals(status(aged.obligation()))) {
                storm.coverage("scenario 13 no condition after closure", 1);
                closureCensusBites(aged.obligation());
            }
        }
        censusAfter("scenario 13, no condition after closure");
    }

    /**
     * A6-03's positive control, every run: on a CLOSED obligation, a condition planted by the owner (triggers suspended) in
     * ANOTHER transaction than the closure's, stamped a second BEFORE the closure - so a census judging "after" by the clock
     * with any tolerance would pass it - and the closure census's own statement, read inside that transaction, counts it by
     * the record's order (bnpl {@code V017}); rolled back, nothing kept.
     */
    private void closureCensusBites(String obligation) throws Exception {
        String census = BnplStormCensus.safety().get("C12 a condition written after its obligation's closure (another"
                + " transaction, the record's order)");
        assertThat(census).as("the closure census for conditions").isNotNull();
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                long before = BnplStormCensus.one(owner, census);
                statement.execute("CREATE TEMP TABLE planted ON COMMIT DROP AS SELECT * FROM bnpl.bnpl_condition_event"
                        + " WHERE obligation_id = '" + obligation + "'::uuid ORDER BY recorded_order DESC LIMIT 1");
                statement.execute("UPDATE planted SET id = gen_random_uuid(), business_date = business_date + 400,"
                        + " recorded_order = nextval('bnpl.record_order'), created_at = (SELECT c.created_at FROM"
                        + " bnpl.bnpl_obligation_closure c WHERE c.obligation_id = '" + obligation + "'::uuid)"
                        + " - interval '1 second'");
                int planted = statement.executeUpdate("INSERT INTO bnpl.bnpl_condition_event SELECT * FROM planted");
                long after = BnplStormCensus.one(owner, census);
                if (planted == 1 && after == before + 1) {
                    storm.coverage("A6-03 a fact after a closure, stamped before it, caught by the record's order", 1);
                } else {
                    tally.unexplained("A6-03: the closure census did not count a fact planted after the closure: planted "
                            + planted + ", census " + before + " -> " + after);
                }
            } finally {
                owner.rollback();
            }
        }
    }

    // ---------------------------------------------------------------- scenario 17

    /**
     * Scenario 17: a pay-in-4 instalment past its late fee's day with the wallet empty - the fee assessed once - then a
     * refund cancelling that instalment releases it; and a fee waived under four eyes across instances, its proposer's own
     * approval refused first.
     */
    private void lateFeeReleasedAndWaived() throws Exception {
        String[] agents = storm.agents();
        Bought released = agedP4(460_00, 14 + 6, 0);
        serviceOn(a(), released.obligation());
        serviceOn(b(), released.obligation());
        // Three quarters, latest first: instalments 4 and 3 (unbilled) and the late, billed instalment 2 - its fee released.
        judged(tally, "scenario 17 refund cancelling the late instalment", refund(a(), released, 460_00 * 3 / 4, key()));
        if (count("SELECT count(*) FROM bnpl.bnpl_fee_assessment WHERE obligation_id = ?::uuid",
                UUID.fromString(released.obligation())) == 1 && count("SELECT count(*) FROM bnpl.bnpl_refund WHERE"
                + " obligation_id = ?::uuid AND late_fee_released_minor > 0", UUID.fromString(released.obligation())) == 1) {
            storm.coverage("scenario 17 a late fee assessed once, released by the refund that cancels its instalment", 1);
        }
        Bought waived = agedP4(470_00, 14 + 6, 1);
        serviceOn(b(), waived.obligation());
        String fee = scalar("SELECT id::text FROM bnpl.bnpl_fee_assessment WHERE obligation_id = ?::uuid",
                UUID.fromString(waived.obligation()));
        if (fee == null) {
            tally.unexplained("scenario 17: no late fee was assessed on the waived obligation");
        } else {
            long amount = count("SELECT amount_minor FROM bnpl.bnpl_fee_assessment WHERE id = ?::uuid",
                    UUID.fromString(fee));
            String waivers = OPERATOR_AGREEMENTS + "/" + waived.agreement() + "/waivers";
            Answer proposed = a().door().post(waivers, "{\"assessmentId\":\"" + fee + "\",\"amount\":\"" + decimal(amount)
                    + "\",\"currency\":\"EUR\",\"reason\":\"the storm's goodwill\"}", agents[0], key());
            judged(tally, "scenario 17 waiver proposed", proposed);
            String waiver = waivers + "/" + proposed.field("waiverId");
            Answer self = b().door().post(waiver + "/approval", "{\"reason\":\"mine\"}", agents[0], key());
            if (refused(tally, "scenario 17 waiver self-approval", self, "403 bnpl.SelfApprovalRefused")
                    && self.is(403, "bnpl.SelfApprovalRefused")) {
                tally.count("bnpl self-approval refused");
            }
            Answer approved = b().door().post(waiver + "/approval", "{\"reason\":\"checked\"}", agents[1], key());
            judged(tally, "scenario 17 waiver approved", approved);
            if (approved.status() == 200 && count("SELECT count(*) FROM bnpl.bnpl_waiver_decision d JOIN bnpl.bnpl_waiver w"
                    + " ON w.id = d.waiver_id WHERE w.fee_assessment_id = ?::uuid AND d.outcome = 'APPROVED'",
                    UUID.fromString(fee)) == 1) {
                storm.coverage("scenario 17 a late fee waived under four eyes across instances", 1);
            }
        }
        censusAfter("scenario 17, late fees released and waived");
    }

    // ---------------------------------------------------------------- scenario 18

    /** Scenario 18: a pay-in-N obligation aged twenty days, ten servicing steps on both instances at a barrier. */
    private void accrualOncePerDate() throws Exception {
        for (int i = 0; i < 3; i++) {
            Bought aged = agedPn(1_200_00 + i, 20, 6, i);
            List<Callable<Object>> racers = new ArrayList<>();
            for (int r = 0; r < 10; r++) {
                Instance on = r % 2 == 0 ? a() : b();
                racers.add(() -> serviceOn(on, aged.obligation()));
            }
            for (Object done : allRaw(racers)) {
                if (done instanceof Throwable failure) {
                    tally.unexplained("scenario 18: a step failed - " + failure.getClass().getSimpleName());
                }
            }
            long dates = count("SELECT count(*) FROM bnpl.bnpl_interest_accrual WHERE obligation_id = ?::uuid",
                    UUID.fromString(aged.obligation()));
            long distinct = count("SELECT count(DISTINCT accrual_date) FROM bnpl.bnpl_interest_accrual WHERE"
                    + " obligation_id = ?::uuid", UUID.fromString(aged.obligation()));
            if (dates >= 19 && dates == distinct) {
                storm.coverage("scenario 18 ten steps accrue every date once", 1);
            }
        }
        censusAfter("scenario 18, ten steps accrue once per date");
    }

    // ---------------------------------------------------------------- scenario 15

    /**
     * Scenario 15: the origination hint for one decision delivered twice, late, from both instances - one offer; and a
     * session the merchant abandons while its application awaits the decision - the application closed, nothing offered.
     */
    private void hintsAndAClosedSession() throws Exception {
        for (int i = 0; i < 2; i++) {
            Customer customer = freshCustomer();
            String[] session = BnplStormWorld.openSession(a().door(), merchant(i), 350_00, tally);
            Answer applied = a().door().post(APPLICATIONS, BnplStormWorld.payInN(session[1], 3), customer.token(), key());
            judged(tally, "scenario 15 application", applied);
            String request = applied.field("decisionRequestId");
            for (int hint = 0; hint < 3; hint++) {
                bothAtOnce(() -> hintOn(a(), request), () -> hintOn(b(), request));
            }
            Optional<String> offer = BnplStormWorld.awaitOffer(applied.field("applicationId"), PATIENCE);
            for (int hint = 0; hint < 2; hint++) {
                bothAtOnce(() -> hintOn(a(), request), () -> hintOn(b(), request));
            }
            if (offer.isPresent() && count("SELECT count(*) FROM bnpl.bnpl_offer WHERE application_id = ?::uuid",
                    UUID.fromString(applied.field("applicationId"))) == 1) {
                storm.coverage("scenario 15 the hint twice from both instances, one offer", 1);
            }
        }
        for (int i = 0; i < 3; i++) {
            Customer customer = freshCustomer();
            Merchant merchant = merchant(i);
            String[] session = BnplStormWorld.openSession(a().door(), merchant, 360_00, tally);
            Answer applied = b().door().post(APPLICATIONS, BnplStormWorld.payInN(session[1], 3), customer.token(), key());
            judged(tally, "scenario 15 application before the abandonment", applied);
            Answer abandoned = a().door().post(BnplStormWorld.SESSIONS + "/" + session[0] + "/abandonment",
                    "{\"reason\":\"cancelled\"}", merchant.apiKey(), null);
            judged(tally, "scenario 15 abandoned", abandoned, "409 checkout.NotAbandonable", "409");
            BnplStormWorld.awaitDatabase(PATIENCE, "the application settles", "SELECT status NOT IN ('SUBMITTED',"
                    + " 'AWAITING_DECISION') FROM bnpl.bnpl_application WHERE id = ?::uuid",
                    UUID.fromString(applied.field("applicationId")));
            if ("CLOSED_UNDECIDED:SESSION_CLOSED".equals(scalar("SELECT status || ':' || coalesce(closure_reason, '-')"
                    + " FROM bnpl.bnpl_application WHERE id = ?::uuid", UUID.fromString(applied.field("applicationId"))))) {
                storm.coverage("scenario 15 a session closed while awaiting the decision closes the application", 1);
            }
        }
        censusAfter("scenario 15, hints and a closed session");
    }

    private boolean hintOn(Instance on, String request) {
        try (var actor = com.finapp.platform.security.SecurityContext.enterSystem();
                var scope = com.finapp.platform.correlation.CorrelationContext.enter(BnplStormWorld.flow())) {
            return on.transactions().inTransaction(uow -> on.beans().getBean(com.finapp.bnpl.BnplOrigination.class)
                    .hintRequest(uow, UUID.fromString(request)));
        }
    }

    // ---------------------------------------------------------------- A6-06: default and bucket by the calendar

    /**
     * Default and bucket coverage forced by the seeded calendar (A6-06), never the run date: pay-in-4 obligations aged so
     * instalment 2 is exactly 30 and exactly 90 days past due today - whole days before the database's business date, the
     * wallets empty - each serviced on both instances: the bucket moves to 30+ and the obligation defaults, once each.
     */
    private void delinquencyForcedByTheCalendar() throws Exception {
        Bought thirty = agedP4(390_00, 14 + 30, 2);
        Bought ninety = agedP4(395_00, 14 + 90, 3);
        for (Bought aged : List.of(thirty, ninety)) {
            bothAtOnce(() -> serviceOn(a(), aged.obligation()), () -> serviceOn(b(), aged.obligation()));
        }
        if (count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = ?::uuid AND kind = 'DELINQUENCY'"
                + " AND bucket = 'B2_30_59'", UUID.fromString(thirty.obligation())) == 1) {
            storm.coverage("A6-06 an obligation 30 days past due changed bucket", 1);
        }
        if (count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE obligation_id = ?::uuid AND kind = 'DEFAULT' AND"
                + " defaulted", UUID.fromString(ninety.obligation())) == 1) {
            storm.coverage("A6-06 an obligation 90 days past due defaulted", 1);
        }
        censusAfter("A6-06, delinquency forced by the calendar");
    }

    // ---------------------------------------------------------------- B7

    /**
     * A refund after the merchant was paid out (owner decision B7, `INV-MER-07` amended): the payable paid out to zero,
     * then a refund of the purchase - the payable below zero by no more than the purchase's own net (the census's B7 arm),
     * recovered from the next financing before any payout.
     */
    private void refundAfterPayout() throws Exception {
        Merchant merchant = world.merchant("0.04", 0);
        storm.merchants().add(merchant);
        Bought one = bought(freshCustomer(), merchant, 700_00, 3);
        long position = BnplStormWorld.minor(BnplStormWorld.field(a().door().get("/v1/merchant/payable",
                merchant.apiKey()).body(), "position"));
        Answer paid = a().door().post(PAYOUTS, money(position), merchant.apiKey(), key());
        judged(tally, "B7 payout", paid);
        BnplStormWorld.awaitDatabase(PATIENCE, "the payout concluded", "SELECT bool_and(status = 'COMPLETED') FROM"
                + " merchant.merchant_payout WHERE merchant_id = ?::uuid", UUID.fromString(merchant.id()));
        judged(tally, "B7 refund after payout", refund(b(), one, 200_00, key()));
        long after = BnplStormWorld.minor(BnplStormWorld.field(a().door().get("/v1/merchant/payable",
                merchant.apiKey()).body(), "position"));
        if (after < 0 && after >= -(700_00 - 28_00)) {
            storm.coverage("B7 a refund after payout left the payable below zero within its bound", 1);
        }
        bought(freshCustomer(), merchant, 300_00, 3);
        censusAfter("B7, a refund after payout");
    }

    // ---------------------------------------------------------------- self-dealing

    /** A customer who is also a servicing agent proposes a reversal of their own repayment: refused, recorded FAILED. */
    private void selfDealing() throws Exception {
        Bought own = bought(freshCustomer(), merchant(0), 380_00, 3);
        fund(own.customer(), 30_00);
        Answer repaid = repay(a(), own, 30_00, key());
        judged(tally, "self-dealing repayment", repaid);
        String insider = world.customerWithRole(own.customer().fixture(), RoleName.BNPL_SERVICING_AGENT);
        Answer refusedAct = b().door().post(OPERATOR_AGREEMENTS + "/" + own.agreement() + "/repayments/"
                + repaid.field("repaymentId") + "/reversal", "{\"reason\":\"my own\"}", insider, key());
        refused(tally, "self-dealing", refusedAct, "403 bnpl.SelfDealingRefused");
        if (refusedAct.is(403, "bnpl.SelfDealingRefused")) {
            tally.count("bnpl self-dealing refused");
            storm.coverage("self-dealing refused", 1);
        }
        censusAfter("self-dealing refused");
    }

    // ================================================================== the crash points

    void crashPoints() throws Exception {
        storm.hotRows().nextRound("crash points");
        play("refusedAtEveryPurchaseStep", this::refusedAtEveryPurchaseStep);
        play("killedBetweenPostingAndPublication", this::killedBetweenPostingAndPublication);
        play("refusedAtTheBilling", this::refusedAtTheBilling);
        play("killedAtTheAllocation", this::killedAtTheAllocation);
        play("refusedAtTheRefundAdjustment", this::refusedAtTheRefundAdjustment);
        play("refusedAtTheReversalApproval", this::refusedAtTheReversalApproval);
        play("theRelayKilledAfterTheReceive", this::theRelayKilledAfterTheReceive);
        storm.hotRows().nextRound("crash points done");
    }

    /**
     * Scenario 14's second half: T-pur refused (or its backend killed) after each of its steps - the purchase, the
     * decision's consumption, the fee pin, the financing posting, the order - on one instance; nothing written (no
     * purchase, consumption, pin, financing entry or order; the offer OFFERED, the session OPEN); then the same key on the
     * other instance finances once.
     */
    private void refusedAtEveryPurchaseStep() throws Exception {
        record Step(String name, String table, String condition, boolean kill) {}
        for (int i = 0; i < 5; i++) {
            Offer offer = offered(freshCustomer(), merchant(i), 330_00 + i, 0);
            fund(offer.customer(), firstInstalment(offer.gross()));
            String purchases = "(SELECT id FROM bnpl.purchase WHERE offer_id = '" + offer.offer() + "'::uuid)";
            Step step = List.of(
                    new Step("the purchase", "bnpl.purchase", "NEW.offer_id = '" + offer.offer() + "'::uuid", false),
                    new Step("the consumption", "credit.credit_decision_consumption", "NEW.decision_id = (SELECT"
                            + " decision_id FROM bnpl.bnpl_offer WHERE id = '" + offer.offer() + "'::uuid)", true),
                    new Step("the fee pin", "merchant.bnpl_fee_pin", "NEW.purchase_id IN " + purchases, false),
                    new Step("the financing posting", "ledger.journal_entry", "NEW.idempotency_scope IN (SELECT"
                            + " 'ledger.post:bnpl.financing:' || id FROM bnpl.purchase WHERE offer_id = '" + offer.offer()
                            + "'::uuid)", false),
                    new Step("the order", "checkout.checkout_order", "NEW.session_ref = '" + offer.session() + "'::uuid",
                            false)).get(i);
            String acceptanceKey = key();
            try (AutoCloseable fault = BnplStormFaults.refuse(step.table(), "INSERT", step.condition(), step.kill())) {
                Answer died = postSurviving(a(), OFFERS + "/" + offer.offer() + "/acceptance",
                        BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), acceptanceKey);
                tally.count("crash at " + step.name() + " answered " + died.status());
            }
            boolean nothing = nothingOf(offer);
            Answer again = sameKeyAgain(b(), OFFERS + "/" + offer.offer() + "/acceptance",
                    BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), acceptanceKey,
                    "crash at " + step.name() + ": the same key again");
            if (again.status() == 201) {
                bought(offer, again.field("agreementId"));
            }
            censusAfter("a failure at T-pur's " + step.name());
            if (nothing && count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid",
                    UUID.fromString(offer.offer())) == 1) {
                storm.coverage("scenario 14 a failure at a T-pur step leaves nothing, the retry finances once", 1);
                storm.coverage("crash points", 1);
            }
        }
    }

    /** Nothing of the offer's purchase: no purchase, consumption, order, pin or financing entry; the offer and session open. */
    private static boolean nothingOf(Offer offer) throws SQLException {
        UUID id = UUID.fromString(offer.offer());
        return count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid", id) == 0
                && count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN bnpl.bnpl_offer o ON o.decision_id"
                        + " = c.decision_id WHERE o.id = ?::uuid", id) == 0
                && count("SELECT count(*) FROM checkout.checkout_order WHERE session_ref = ?::uuid",
                        UUID.fromString(offer.session())) == 0
                && "OFFERED".equals(scalar("SELECT status FROM bnpl.bnpl_offer WHERE id = ?::uuid", id))
                && "OPEN".equals(scalar("SELECT status FROM checkout.checkout_session WHERE id = ?::uuid",
                        UUID.fromString(offer.session())));
    }

    /**
     * Scenario 14's first half: T-pur killed at its {@code bnpl.MerchantFinanced} outbox row - after its posting, before
     * its publication: neither the entry nor the event; then the same key on the other instance - one of each.
     */
    private void killedBetweenPostingAndPublication() throws Exception {
        Offer offer = offered(freshCustomer(), merchant(1), 345_00, 3);
        String acceptanceKey = key();
        try (AutoCloseable killed = BnplStormFaults.refuse("platform.outbox_event", "INSERT", "NEW.event_type ="
                + " 'bnpl.MerchantFinanced' AND NEW.aggregate_id IN (SELECT id FROM bnpl.purchase WHERE offer_id = '"
                + offer.offer() + "'::uuid)", true)) {
            Answer died = postSurviving(b(), OFFERS + "/" + offer.offer() + "/acceptance",
                    BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), acceptanceKey);
            tally.count("killed between posting and publication answered " + died.status());
        }
        boolean neither = nothingOf(offer) && count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'bnpl.MerchantFinanced' AND payload::text LIKE ?", "%" + offer.offer() + "%") == 0;
        Answer again = sameKeyAgain(a(), OFFERS + "/" + offer.offer() + "/acceptance",
                BnplStormWorld.acceptance(offer.hash()), offer.customer().token(), acceptanceKey,
                "killed between posting and publication: the same key again");
        if (again.status() == 201) {
            Bought bought = bought(offer, again.field("agreementId"));
            if (neither && count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'bnpl.MerchantFinanced'"
                    + " AND aggregate_id = ?::uuid", UUID.fromString(bought.purchase())) == 1) {
                storm.coverage("scenario 14 killed between posting and publication: nothing, then once", 1);
                storm.coverage("crash points", 1);
            }
        }
        censusAfter("killed between posting and publication");
    }

    /** A servicing step's billing refused beneath the instances' OWN sweeps - metered failed once - then billed once. */
    private void refusedAtTheBilling() throws Exception {
        Bought aged = agedP4(370_00, 14, 2);
        try (AutoCloseable refused = BnplStormFaults.refuse("bnpl.bnpl_billing", "INSERT", "NEW.obligation_id = '"
                + aged.obligation() + "'::uuid", false)) {
            failsOnceInTheSweep(aged.obligation(), "the billing refused");
        }
        serviceOn(b(), aged.obligation());
        censusAfter("refused at the billing");
        if (count("SELECT count(*) FROM bnpl.bnpl_billing WHERE obligation_id = ?::uuid AND payoff_execution_id IS NULL",
                UUID.fromString(aged.obligation())) == 2) {
            storm.coverage("crash points", 1);
        }
    }

    /** T-rep killed at its allocation: nothing posted; the same key on the other instance repays once. */
    private void killedAtTheAllocation() throws Exception {
        Bought one = bought(freshCustomer(), merchant(3), 390_00, 3);
        fund(one.customer(), 45_00);
        String repaymentKey = key();
        try (AutoCloseable killed = BnplStormFaults.refuse("bnpl.bnpl_repayment_allocation", "INSERT",
                "NEW.repayment_id IN (SELECT id FROM bnpl.bnpl_repayment WHERE obligation_id = '" + one.obligation()
                        + "'::uuid)", true)) {
            Answer died = postSurviving(a(), AGREEMENTS + "/" + one.agreement() + "/repayments", money(45_00),
                    one.customer().token(), repaymentKey);
            tally.count("the repayment killed answered " + died.status());
        }
        boolean nothing = count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = ?::uuid AND kind ="
                + " 'CUSTOMER'", UUID.fromString(one.obligation())) == 0;
        sameKeyAgain(b(), AGREEMENTS + "/" + one.agreement() + "/repayments", money(45_00), one.customer().token(),
                repaymentKey, "the repayment's same key again");
        censusAfter("killed at the allocation");
        if (nothing && count("SELECT count(*) FROM bnpl.bnpl_repayment WHERE obligation_id = ?::uuid AND kind ="
                + " 'CUSTOMER'", UUID.fromString(one.obligation())) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** T-ref refused at its adjustment: nothing; the same key on the other instance refunds once. */
    private void refusedAtTheRefundAdjustment() throws Exception {
        Bought one = bought(freshCustomer(), merchant(4), 410_00, 3);
        String refundKey = key();
        try (AutoCloseable refused = BnplStormFaults.refuse("bnpl.bnpl_refund_adjustment", "INSERT",
                "NEW.obligation_id = '" + one.obligation() + "'::uuid", false)) {
            Answer died = refund(a(), one, 150_00, refundKey);
            tally.count("the refund refused answered " + died.status());
        }
        boolean nothing = count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                UUID.fromString(one.purchase())) == 0;
        sameKeyAgain(b(), PURCHASES + "/" + one.purchase() + "/refunds", "{\"amount\":\"" + decimal(150_00)
                + "\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}", one.merchant().refundKey(), refundKey,
                "the refund's same key again");
        censusAfter("refused at the refund's adjustment");
        if (nothing && count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                UUID.fromString(one.purchase())) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /** T-rev refused at its APPROVED edge: approved again, one reversal. */
    private void refusedAtTheReversalApproval() throws Exception {
        String[] agents = storm.agents();
        Bought one = bought(freshCustomer(), merchant(5), 420_00, 3);
        fund(one.customer(), 35_00);
        Answer repaid = repay(a(), one, 35_00, key());
        judged(tally, "reversal crash repayment", repaid);
        String reversal = OPERATOR_AGREEMENTS + "/" + one.agreement() + "/repayments/" + repaid.field("repaymentId")
                + "/reversal";
        Answer proposed = a().door().post(reversal, "{\"reason\":\"recalled\"}", agents[0], key());
        judged(tally, "reversal crash propose", proposed);
        String decision = "{\"reversalId\":\"" + proposed.field("reversalId") + "\",\"reason\":\"matched\"}";
        try (AutoCloseable refused = BnplStormFaults.refuse("bnpl.bnpl_repayment_reversal", "UPDATE",
                "NEW.id = '" + proposed.field("reversalId") + "'::uuid AND NEW.status = 'APPROVED'", false)) {
            Answer died = b().door().post(reversal + "/approval", decision, agents[1], key());
            tally.count("the reversal approval refused answered " + died.status());
        }
        Answer again = a().door().post(reversal + "/approval", decision, agents[1], key());
        judged(tally, "the reversal approved again", again);
        censusAfter("refused at the reversal's approval");
        if (count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE repayment_id = ?::uuid AND status ="
                + " 'APPROVED'", UUID.fromString(repaid.field("repaymentId"))) == 1) {
            storm.coverage("crash points", 1);
        }
    }

    /**
     * Scenario 14's relay, killed AFTER the broker received (A6-07 - the lending storm's relay died before the receive, so
     * its at-least-once window was untested): a relay whose broker receives each watched event and then the relay dies
     * before acknowledging; a new relay re-publishes every unacknowledged event - those the broker already received
     * included (at least once) - and every pending event exactly once of its own.
     */
    private void theRelayKilledAfterTheReceive() throws Exception {
        Set<UUID> watched = Set.copyOf(BnplStormWorld.strings("SELECT aggregate_id::text FROM platform.outbox_event WHERE"
                + " event_type LIKE 'bnpl.%' AND published_at IS NULL ORDER BY occurred_at DESC LIMIT 20").stream()
                .map(UUID::fromString).toList());
        Recorder killed = new Recorder(watched, true);
        relay(killed).pollOnce();
        relay(killed).pollOnce();
        Set<String> pending = Set.copyOf(BnplStormWorld.strings("SELECT event_id::text FROM platform.outbox_event WHERE"
                + " aggregate_id = ANY (?) AND published_at IS NULL", (Object) watched.toArray(UUID[]::new)));
        Recorder restarted = new Recorder(watched, false);
        OutboxRelay relay = relay(restarted);
        for (int poll = 0; poll < 5_000 && relay.pollOnce().didWork(); poll++) {
            // drained
        }
        censusAfter("the relay killed after the receive");
        List<String> received = List.copyOf(restarted.received);
        boolean atLeastOnce = !killed.received.isEmpty() && received.containsAll(killed.received);
        if (!pending.isEmpty() && received.containsAll(pending) && Set.copyOf(received).size() == received.size()
                && atLeastOnce) {
            storm.coverage("scenario 14 the relay killed after the receive: the unacknowledged re-published", 1);
            storm.coverage("crash points", 1);
        } else {
            tally.unexplained("the relay killed after the receive: pending " + pending.size() + ", received before the"
                    + " kill " + killed.received.size() + ", re-published " + received.size() + ", at least once "
                    + atLeastOnce);
        }
    }

    private static OutboxRelay relay(EventPublisher publisher) {
        return new OutboxRelay(DatabaseRoles::application, publisher,
                new RetryPolicy(Duration.ofMillis(1), Duration.ofMillis(2), 50), 10_000, 200);
    }

    /** A broker recording what reaches it for the watched aggregates - or, killed, receiving it and then dying unacked. */
    private static final class Recorder implements EventPublisher {

        private final Set<UUID> watched;
        private final boolean killed;
        private final List<String> received = new CopyOnWriteArrayList<>();

        Recorder(Set<UUID> watched, boolean killed) {
            this.watched = watched;
            this.killed = killed;
        }

        @Override
        public void publish(PendingEvent event) {
            if (!watched.contains(event.aggregateId())) {
                return;
            }
            received.add(event.eventId().value().toString());
            if (killed) {
                throw new IllegalStateException("the relay died after the broker received, before it acknowledged");
            }
        }
    }

    // ---------------------------------------------------------------- the crash points' plumbing

    /** A request that may meet a killed backend: its answer, or a 0 when the connection itself was cut. */
    private Answer postSurviving(Instance on, String path, String body, String token, String key) {
        try {
            return on.door().post(path, body, token, key);
        } catch (Exception cut) {
            tally.count("a crash point's request cut: " + cut.getClass().getSimpleName());
            return new Answer(0, "", "cut");
        }
    }

    /**
     * The client's retry under the same key after a failed attempt: while the key's claim is still held by the attempt that
     * died ({@code 409 api.IdempotencyInProgress}), the client waits and asks again - then the act runs once.
     */
    private Answer sameKeyAgain(Instance on, String path, String body, String token, String key, String act)
            throws Exception {
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (true) {
            Answer again = on.door().post(path, body, token, key);
            if (!again.is(409, "api.IdempotencyInProgress") || System.nanoTime() > deadline) {
                judged(tally, act, again);
                return again;
            }
            tally.count(act + " waited for the dead attempt's claim");
            Thread.sleep(500);
        }
    }

    /**
     * The armed servicing fault taken by the instances' OWN servicing sweeps, so their meter counts it: the obligation -
     * held from the sweeps by a far permit - handed to them while the fault is armed, until exactly one sweep step on
     * either instance failed ({@code finapp.bnpl.servicing{outcome=failed}}), then held again before the claim's retry
     * cadence (2 s) could let a second step take it. One failure per armed fault.
     */
    private void failsOnceInTheSweep(String obligation, String what) throws Exception {
        double before = storm.servicingFailures();
        storm.servicingFaultsInjected().incrementAndGet();
        BnplStormWorld.permit(obligation, "statement_timestamp()");
        long deadline = System.nanoTime() + PATIENCE.toNanos();
        while (storm.servicingFailures() < before + 1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        BnplStormWorld.permit(obligation, FAR);
        Thread.sleep(500);
        double failed = storm.servicingFailures() - before;
        if (failed == 1) {
            tally.count(what + " in a sweep");
        } else {
            tally.unexplained(what + ": the sweeps failed " + failed + " steps under one armed fault, not one");
        }
    }
}
