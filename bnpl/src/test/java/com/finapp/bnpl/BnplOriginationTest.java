package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The origination step and the offer's expiry, hermetic (`P12-TSK-011`; {@code PHASE_12_PLAN.md} sections 5.1, 5.2, 7.1
 * T-off, 7.4 and 12.1, {@code BNPL_LIFECYCLES.md} sections 4.2 and 4.3; {@code INV-BNPL-03}, {@code INV-LIFE-01},
 * {@code INV-LIFE-02}, {@code INV-CRD-04}, {@code INV-EVT-04}): exactly one offer per approved application for exactly the
 * purchase, never outliving its decision or its session; every other outcome closing the application with its reason;
 * the merchant learning one bit; hints converging but never deciding; decline and expiry exactly one at the boundary.
 * Over {@link BnplApplicationFakes} - an in-memory {@code bnpl V003}/{@code V004} with a rolling-back unit of work and a
 * database clock. The counted races across instances and the database's own ranks are {@code app}'s
 * {@code BnplOriginationDatabaseTest}'s.
 */
@DisplayName("the origination step: one offer per approved application, never outliving its decision or session (P12-TSK-011)")
class BnplOriginationTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-011-unit");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final Duration PERMIT = Duration.ofSeconds(30);
    private static final long GROSS = 600_00L;

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplTermsAdministration termsAdministration;
    private UUID party;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        world = new BnplApplicationFakes();
        termsAdministration = new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS, CLOCK,
                BnplTermsFixtures.BOUNDS);
        applications = new BnplApplications(world.store, termsAdministration, world.standing, world.sessions,
                world.merchants, BnplTermsFixtures.BOUNDS, world.credit, world.outbox, world.audit, IDS, CLOCK);
        origination = new BnplOrigination(world.store, world.offers, termsAdministration, world.credit, world.sessions,
                world.outbox, IDS, CLOCK);
        party = UUID.randomUUID();
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ one offer

    @Test
    @DisplayName("ten sweepers on one approved application offer once: one OFFERED, nine NOTHING - nothing written by the"
            + " losers - one offer, one terms row, one history row, one BnplOffered and one ELIGIBLE")
    void tenSweepersOfferOnce() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.events.clear();

        List<BnplOrigination.Outcome> outcomes = new ArrayList<>();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            // Each sweeper handed the id - as after a lapsed permit - and taking its step in a transaction of its own.
            outcomes.add(world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(),
                    SYSTEM)));
        }
        assertThat(outcomes.stream().filter(o -> o.kind() == BnplOrigination.Outcome.Kind.OFFERED)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> o.kind() == BnplOrigination.Outcome.Kind.NOTHING)).hasSize(9);
        assertThat(world.offers.rows).hasSize(1);
        assertThat(world.offers.terms).hasSize(1);
        assertThat(world.offers.history).hasSize(1);
        assertThat(types()).containsExactly(BnplOrigination.OFFERED_EVENT, BnplOrigination.ELIGIBILITY_EVENT);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.OFFERED);
    }

    @Test
    @DisplayName("ten claims at one instant take disjoint pages - the application claimed once, its permit re-stamped")
    void tenClaimsTakeDisjointPages() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        List<BnplApplicationId> claimed = new ArrayList<>();
        for (int sweeper = 0; sweeper < 10; sweeper++) {
            claimed.addAll(world.inTransaction(() -> origination.claimApplications(BnplApplicationFakes.UOW, 20,
                    PERMIT)));
        }
        assertThat(claimed).containsExactly(application.id());
        assertThat(world.store.permits.get(application.id().value())).isEqualTo(world.now.plus(PERMIT));
    }

    @Test
    @DisplayName("the step in T-off's order: B1 first, then credit's decision, the session, the terms pin, the database"
            + " clock, the offer before the application's OFFERED edge")
    void theStepTakesItsLocksInOrder() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.log.clear();
        originate(application);
        assertThat(world.log.stream().filter(entry -> !entry.startsWith("event")).toList()).containsExactly(
                "B1 " + application.id().value(), "credit decision", "session by ref", "terms pin BNPL_PAY_IN_4",
                "offer insert", "offer terms", "offer history OFFERED", "close OFFERED", "history OFFERED");
    }

    @Test
    @DisplayName("the offer is exactly the purchase: its terms the session's gross on the pinned engine from the offering"
            + " day, the canonical terms hashed over their bytes, the document hashed and naming the terms' hash")
    void theOfferIsExactlyThePurchaseHashedOverItsBytes() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        UUID decision = world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        BnplOrigination.Outcome outcome = originate(application);
        assertThat(outcome.kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
        assertThat(outcome.eligibility()).contains(BnplOrigination.Eligibility.ELIGIBLE);

        BnplOffer offer = world.offers.rows.values().iterator().next();
        BnplOfferStore.StoredTerms terms = world.offers.terms.get(offer.id().value());
        assertThat(offer.decision()).isEqualTo(decision);
        assertThat(offer.application()).isEqualTo(application.id());
        assertThat(offer.termsVersionId()).isEqualTo(world.terms.active.get(BnplProduct.BNPL_PAY_IN_4));
        assertThat(terms.amount()).isEqualTo(Money.ofMinorUnits(GROSS, BnplTermsFixtures.EUR));
        assertThat(terms.instalmentCount()).isEqualTo(4);
        assertThat(terms.illustrativeStart()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(terms.firstDueDate()).as("instalment 1 at purchase").isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(terms.totalPayable()).isEqualTo(terms.amount());
        assertThat(terms.termsSha256()).isEqualTo(BnplOfferTerms.sha256(terms.canonicalTerms()));
        assertThat(terms.canonicalTerms()).startsWith("{\"format\":\"1\",\"offer\":\"" + offer.id().value() + "\"")
                .contains("\"decision\":\"" + decision + "\"").contains("\"amountMinor\":\"" + GROSS + "\"")
                .contains("\"scheduleEngine\":\"BNPL_SCHEDULE_ENGINE_V1\"");
        BnplTermsVersion version = world.terms.versions.get(offer.termsVersionId());
        String document = BnplOfferDocument.render(offer.id(), version.terms(), BnplPlan.PAY_IN_4,
                BnplIllustration.of(version.terms(), BnplPlan.PAY_IN_4, terms.amount(), terms.illustrativeStart()),
                HexFormat.of().formatHex(terms.termsSha256()));
        assertThat(terms.documentSha256()).isEqualTo(BnplOfferTerms.sha256(document));
        assertThat(document).contains("Terms SHA-256: " + terms.termsSha256Hex());
        assertThat(payloadOf(BnplOrigination.OFFERED_EVENT)).contains("\"termsSha256\":\"" + terms.termsSha256Hex())
                .doesNotContain("amountMinor").doesNotContain("\"amount\"");
    }

    @Test
    @DisplayName("a pay-in-N offer illustrates the plan's months on SCHEDULE_ENGINE_V1 at the pinned rate - interest due,"
            + " the first instalment a month after the offering day")
    void aPayInNOfferIllustratesItsPlan() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        assertThat(originate(application).kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
        BnplOfferStore.StoredTerms terms = world.offers.terms.values().iterator().next();
        assertThat(terms.instalmentCount()).isEqualTo(6);
        assertThat(terms.scheduleEngine()).isEqualTo("SCHEDULE_ENGINE_V1");
        assertThat(terms.firstDueDate()).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(terms.totalInterest().isPositive()).isTrue();
        assertThat(terms.totalPayable()).isEqualTo(terms.amount().plus(terms.totalInterest()));
    }

    // ------------------------------------------------------------------ the expiry's bounds

    @Test
    @DisplayName("the offer never outlives the session or the decision: its expiry the earliest of the decision's validity,"
            + " the session's deadline and the 30-minute cap, each case the binding one")
    void theOfferNeverOutlivesTheSessionOrTheDecision() {
        Instant sessionFirst = world.now.plus(Duration.ofMinutes(10));
        BnplOffer bySession = offered(sessionFirst, world.now.plus(Duration.ofDays(1)));
        assertThat(bySession.expiresAt()).as("the session's deadline binds").isEqualTo(sessionFirst);

        Instant decisionFirst = world.now.plus(Duration.ofMinutes(5));
        BnplOffer byDecision = offered(world.now.plus(Duration.ofHours(1)), decisionFirst);
        assertThat(byDecision.expiresAt()).as("the decision's validity binds").isEqualTo(decisionFirst);

        BnplOffer byCap = offered(world.now.plus(Duration.ofHours(1)), world.now.plus(Duration.ofDays(1)));
        assertThat(byCap.expiresAt()).as("the pinned 30-minute validity binds").isEqualTo(
                world.now.plus(Duration.ofMinutes(30)));
    }

    // ------------------------------------------------------------------ every other outcome closes

    @Test
    @DisplayName("an approval below the purchase is not offered - CLOSED_UNDECIDED (APPROVED_BELOW_PURCHASE), no offer, the"
            + " merchant told NOT_ELIGIBLE only")
    void anApprovalBelowThePurchaseIsNotOffered() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS - 1, world.now.plus(Duration.ofDays(1)));
        world.events.clear();
        BnplOrigination.Outcome outcome = originate(application);
        assertThat(outcome.kind()).isEqualTo(BnplOrigination.Outcome.Kind.CLOSED_UNDECIDED);
        assertThat(outcome.reason()).contains(BnplApplicationClosureReason.APPROVED_BELOW_PURCHASE);
        assertThat(world.offers.rows).isEmpty();
        assertThat(row(application).closureReason()).contains(BnplApplicationClosureReason.APPROVED_BELOW_PURCHASE);
        assertThat(types()).containsExactly(BnplApplications.CLOSED_EVENT, BnplOrigination.ELIGIBILITY_EVENT);
        assertThat(payloadOf(BnplOrigination.ELIGIBILITY_EVENT)).contains("\"outcome\":\"NOT_ELIGIBLE\"");
    }

    @Test
    @DisplayName("a decline is DECLINED and assessed NOT_ELIGIBLE only - the eligibility event carries the session and the"
            + " outcome and nothing else: no reason, no score, no amount")
    void aDeclineIsAssessedNotEligibleOnly() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.decline(application.decisionRequest());
        world.events.clear();
        world.payloads.clear();
        BnplOrigination.Outcome outcome = originate(application);
        assertThat(outcome.kind()).isEqualTo(BnplOrigination.Outcome.Kind.DECLINED);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.DECLINED);
        assertThat(world.offers.rows).isEmpty();
        assertThat(payloadOf(BnplOrigination.ELIGIBILITY_EVENT)).isEqualTo("{\"checkoutSessionId\":\""
                + application.checkoutSession() + "\",\"outcome\":\"NOT_ELIGIBLE\"}");
    }

    @Test
    @DisplayName("no credit data to merchants (P12-DOC-001): the merchant-consumable eligibility event is exactly"
            + " {checkoutSessionId, outcome} - for an offer ELIGIBLE and for an approval below the purchase NOT_ELIGIBLE -"
            + " never a reason, a score, an amount or a schedule")
    void theEligibilityEventCarriesExactlyTheSessionAndTheOutcome() {
        BnplApplication offered = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(offered.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.events.clear();
        world.payloads.clear();
        assertThat(originate(offered).eligibility()).contains(BnplOrigination.Eligibility.ELIGIBLE);
        assertThat(payloadOf(BnplOrigination.ELIGIBILITY_EVENT)).isEqualTo("{\"checkoutSessionId\":\""
                + offered.checkoutSession() + "\",\"outcome\":\"ELIGIBLE\"}");

        BnplApplication below = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(below.decisionRequest(), GROSS - 1, world.now.plus(Duration.ofDays(1)));
        world.events.clear();
        world.payloads.clear();
        assertThat(originate(below).eligibility()).contains(BnplOrigination.Eligibility.NOT_ELIGIBLE);
        assertThat(payloadOf(BnplOrigination.ELIGIBILITY_EVENT)).isEqualTo("{\"checkoutSessionId\":\""
                + below.checkoutSession() + "\",\"outcome\":\"NOT_ELIGIBLE\"}");
    }

    @Test
    @DisplayName("credit still deciding and the session open: WAITING, nothing written")
    void creditStillDecidingWaits() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.events.clear();
        assertThat(originate(application).kind()).isEqualTo(BnplOrigination.Outcome.Kind.WAITING);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.events).isEmpty();
        assertThat(world.credit.requests.get(application.decisionRequest()).status()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("an approval with no terms version ACTIVE to pin WAITS - nothing written, no offer, no closure - and is"
            + " offered by a later step once one is (the Phase 12 transition gate's A3-I1; credit's DecisionProgress)")
    void anEmptyPinWaits() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        BnplTermsVersionId inForce = world.terms.active.remove(BnplProduct.BNPL_PAY_IN_4);
        world.events.clear();
        int history = world.store.history.size();

        BnplOrigination.Outcome step = originate(application);
        assertThat(step.kind()).as("never a terminal closure").isEqualTo(BnplOrigination.Outcome.Kind.WAITING);
        assertThat(step.reason()).isEmpty();
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.offers.rows).isEmpty();
        assertThat(world.events).isEmpty();
        assertThat(world.store.history).hasSize(history);

        world.terms.active.put(BnplProduct.BNPL_PAY_IN_4, inForce);
        assertThat(originate(application).kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
    }

    @Test
    @DisplayName("a session that expires while awaiting the decision closes the application SESSION_CLOSED and cancels"
            + " credit's request; once credit has evaluated, the request decides and its approval is never offered")
    void aSessionThatExpiresClosesTheApplication() {
        BnplApplication cancellable = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.now = Instant.parse("2026-10-17T11:00:00Z");
        BnplOrigination.Outcome closed = originate(cancellable);
        assertThat(closed.reason()).contains(BnplApplicationClosureReason.SESSION_CLOSED);
        assertThat(world.credit.requests.get(cancellable.decisionRequest()).status()).isEqualTo("CANCELLED");

        world.now = Instant.parse("2026-10-17T10:10:00Z");
        BnplApplication evaluated = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.evaluate(evaluated.decisionRequest());
        world.sessions.close(evaluated.checkoutSession());
        assertThat(originate(evaluated).reason()).contains(BnplApplicationClosureReason.SESSION_CLOSED);
        assertThat(world.credit.requests.get(evaluated.decisionRequest()).status()).as("past cancellation")
                .isEqualTo("EVALUATED");
        world.credit.approve(evaluated.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        assertThat(originate(evaluated).kind()).as("the late approval lapses unconsumed")
                .isEqualTo(BnplOrigination.Outcome.Kind.NOTHING);
        assertThat(world.offers.rows).isEmpty();
    }

    @Test
    @DisplayName("an approval whose session was paid by another instrument is not offered - SESSION_CLOSED")
    void anApprovalOnAClosedSessionIsNotOffered() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.sessions.close(application.checkoutSession());
        assertThat(originate(application).reason()).contains(BnplApplicationClosureReason.SESSION_CLOSED);
        assertThat(world.offers.rows).isEmpty();
    }

    @Test
    @DisplayName("credit's request closed without a decision closes the application with credit's closure as its reason")
    void aClosedCreditRequestClosesUndecided() {
        for (String closure : List.of("CANCELLED", "EXPIRED", "ABANDONED")) {
            BnplApplication application = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
            world.credit.status(application.decisionRequest(), closure);
            assertThat(originate(application).reason()).as(closure)
                    .contains(BnplApplicationClosureReason.valueOf("CREDIT_REQUEST_" + closure));
        }
    }

    @Test
    @DisplayName("a lapsed approval is not offered - DECISION_LAPSED, judged on the database clock read after the pin")
    void aLapsedDecisionIsNotOffered() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now);
        assertThat(originate(application).reason()).contains(BnplApplicationClosureReason.DECISION_LAPSED);
        assertThat(world.offers.rows).isEmpty();
    }

    @Test
    @DisplayName("a retired terms version offers nothing: the offer pins the successor ACTIVE now, and a successor that no"
            + " longer offers the plan closes the application PRODUCT_NOT_OFFERED")
    void aRetiredTermsVersionOffersNothing() {
        BnplApplication still = applied(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        BnplApplication dropped = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_12_MONTHS);
        BnplTermsVersionId recorded = still.termsVersionId();
        BnplTermsVersion successor = world.terms.activate(BnplTermsFixtures.payInN("0.1999")
                .payInN(Optional.of(BnplTermsFixtures.payInNPart(List.of(3, 6), 15))).build(), 4);
        world.credit.approve(still.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.credit.approve(dropped.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));

        assertThat(originate(still).kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
        BnplOffer offer = world.offers.rows.values().iterator().next();
        assertThat(offer.termsVersionId()).isEqualTo(successor.id()).isNotEqualTo(recorded);
        assertThat(offer.termsVersion()).isEqualTo(4);
        assertThat(originate(dropped).reason()).contains(BnplApplicationClosureReason.PRODUCT_NOT_OFFERED);
    }

    @Test
    @DisplayName("an approval of another party or product, above the purchase or for another term is a defect - the step"
            + " fails and writes nothing")
    void anImpossibleApprovalIsADefect() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS + 1, world.now.plus(Duration.ofDays(1)));
        assertThatThrownBy(() -> originate(application)).isInstanceOf(IllegalStateException.class);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.offers.rows).isEmpty();
    }

    @Test
    @DisplayName("a failure anywhere in the step leaves the application as it was and its permit to lapse - re-claimed")
    void aFailedStepLeavesNothing() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.events.clear();
        world.store.failAt = "close";
        assertThat(sweep()).as("the step failed").isZero();
        assertThat(world.offers.rows).isEmpty();
        assertThat(world.events).isEmpty();
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        world.store.failAt = "";
        world.now = world.now.plus(PERMIT);
        assertThat(sweep()).isEqualTo(1);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.OFFERED);
    }

    // ------------------------------------------------------------------ hints

    @Test
    @DisplayName("hints twice, late or never converge: a hint only pulls the permit forward - an unknown decision or a hint"
            + " for a moved application does nothing - and without any the next permit's sweep offers")
    void hintsTwiceLateOrNeverConverge() {
        BnplApplication application = applied(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThat(sweep()).as("the first look - credit undecided").isZero();
        UUID decision = world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        // The decision event, twice: the permit pulled to now once - the second finds it due already.
        assertThat(world.inTransaction(() -> origination.hintDecision(BnplApplicationFakes.UOW, decision))).isTrue();
        assertThat(world.inTransaction(() -> origination.hintDecision(BnplApplicationFakes.UOW, decision))).isFalse();
        assertThat(world.inTransaction(() -> origination.hintDecision(BnplApplicationFakes.UOW, UUID.randomUUID())))
                .as("an unknown decision").isFalse();
        assertThat(status(application)).as("a hint decides nothing").isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(sweep()).isEqualTo(1);
        assertThat(status(application)).isEqualTo(BnplApplicationStatus.OFFERED);
        assertThat(world.inTransaction(() -> origination.hintSession(BnplApplicationFakes.UOW,
                application.checkoutSession()))).as("a late hint for a moved application").isFalse();
        assertThat(world.offers.rows).hasSize(1);

        BnplApplication never = applied(UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThat(sweep()).isZero();
        world.credit.approve(never.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        assertThat(sweep()).as("no hint: the permit has not come").isZero();
        world.now = world.now.plus(PERMIT);
        assertThat(sweep()).as("no hint: offered once the permit lapses").isEqualTo(1);
        assertThat(status(never)).isEqualTo(BnplApplicationStatus.OFFERED);
    }

    // ------------------------------------------------------------------ expiry

    @Test
    @DisplayName("an offer expires at its expiry and not before: claimed early, its permit goes back to the expiry; at it,"
            + " EXPIRED with its history and BnplOfferClosed")
    void anOfferExpiresAtItsExpiry() {
        BnplOffer offer = offered(world.now.plus(Duration.ofHours(1)), world.now.plus(Duration.ofDays(1)));
        assertThat(world.inTransaction(() -> origination.expire(BnplApplicationFakes.UOW, offer.id(), SYSTEM)))
                .as("claimed early").isEmpty();
        assertThat(world.offers.permits.get(offer.id().value())).isEqualTo(offer.expiresAt());
        assertThat(world.inTransaction(() -> origination.claimOffers(BnplApplicationFakes.UOW, 20, PERMIT))).isEmpty();
        world.now = offer.expiresAt();
        world.events.clear();
        assertThat(world.inTransaction(() -> origination.claimOffers(BnplApplicationFakes.UOW, 20, PERMIT)))
                .containsExactly(offer.id());
        assertThat(world.inTransaction(() -> origination.expire(BnplApplicationFakes.UOW, offer.id(), SYSTEM)))
                .contains(BnplProduct.BNPL_PAY_IN_4);
        assertThat(world.offers.rows.get(offer.id().value()).status()).isEqualTo(BnplOfferStatus.EXPIRED);
        assertThat(types()).containsExactly(BnplOrigination.OFFER_CLOSED_EVENT);
        assertThat(world.inTransaction(() -> origination.expire(BnplApplicationFakes.UOW, offer.id(), SYSTEM)))
                .as("twice").isEmpty();
    }

    @Test
    @DisplayName("the customer's decline racing the expiry at the boundary under +/-5 s leaves exactly one of DECLINED and"
            + " EXPIRED, in both orders - judged on the database clock, never an instance's")
    void declineAndExpiryLeaveExactlyOne() {
        BnplOffers offers = new BnplOffers(world.offers, world.terms.store(), world.outbox, world.audit, IDS, CLOCK);
        for (int skew = -5; skew <= 5; skew++) {
            for (boolean declineFirst : List.of(true, false)) {
                world.now = Instant.parse("2026-10-17T10:10:00Z");
                BnplOffer offer = offered(world.now.plus(Duration.ofHours(1)), world.now.plus(Duration.ofDays(1)));
                world.now = offer.expiresAt().plusSeconds(skew);
                Runnable decline = () -> {
                    try {
                        world.inTransaction(() -> offers.decline(BnplApplicationFakes.UOW, offer.id(), offer.party(),
                                new Actor(offer.party().toString(), ActorType.CUSTOMER), CORRELATION));
                    } catch (BnplOffers.OfferNotAcceptable refused) {
                        // the other edge won, or the expiry has come
                    }
                };
                Runnable expire = () -> world.inTransaction(() -> origination.expire(BnplApplicationFakes.UOW,
                        offer.id(), SYSTEM));
                (declineFirst ? decline : expire).run();
                (declineFirst ? expire : decline).run();
                BnplOfferStatus status = world.offers.rows.get(offer.id().value()).status();
                assertThat(status).as("skew %d s, decline first %s", skew, declineFirst)
                        .isEqualTo(skew < 0 ? BnplOfferStatus.DECLINED : BnplOfferStatus.EXPIRED);
                assertThat(world.offers.history.stream().filter(row -> row.offer().equals(offer.id())
                        && row.from().isPresent())).hasSize(1);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** An application of a fresh party's, AWAITING_DECISION. */
    private BnplApplication applied(BnplProduct product, BnplPlan plan) {
        return applied(party, product, plan);
    }

    private BnplApplication applied(UUID who, BnplProduct product, BnplPlan plan) {
        String token = world.sessions.open(merchant, GROSS);
        return world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, who,
                new BnplApplications.Submission(Sensitive.of(token), product, plan),
                new Actor(who.toString(), ActorType.CUSTOMER), CORRELATION));
    }

    /** An offer on a session ending at {@code sessionExpiresAt} for a decision valid until {@code validUntil}. */
    private BnplOffer offered(Instant sessionExpiresAt, Instant validUntil) {
        UUID who = UUID.randomUUID();
        String token = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                Money.ofMinorUnits(GROSS, BnplTermsFixtures.EUR), sessionExpiresAt, true, false));
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, who,
                new BnplApplications.Submission(Sensitive.of(token), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4),
                new Actor(who.toString(), ActorType.CUSTOMER), CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, validUntil);
        assertThat(originate(application).kind()).isEqualTo(BnplOrigination.Outcome.Kind.OFFERED);
        return world.offers.rows.values().stream().filter(offer -> offer.application().equals(application.id()))
                .findFirst().orElseThrow();
    }

    private BnplOrigination.Outcome originate(BnplApplication application) {
        return world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(), SYSTEM));
    }

    /** One sweep as the schedule takes it: the claim, then each step in its own transaction; the steps that moved. */
    private int sweep() {
        List<BnplApplicationId> claimed = world.inTransaction(() -> origination.claimApplications(
                BnplApplicationFakes.UOW, 20, PERMIT));
        int moved = 0;
        for (BnplApplicationId id : claimed) {
            try {
                BnplOrigination.Outcome step = world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW,
                        id, SYSTEM));
                if (step.kind() != BnplOrigination.Outcome.Kind.WAITING
                        && step.kind() != BnplOrigination.Outcome.Kind.NOTHING) {
                    moved++;
                }
            } catch (RuntimeException failed) {
                // the permit lapses; a later sweep steps again
            }
        }
        return moved;
    }

    private BnplApplication row(BnplApplication application) {
        return world.store.rows.get(application.id().value());
    }

    private BnplApplicationStatus status(BnplApplication application) {
        return row(application).status();
    }

    private List<String> types() {
        return world.events.stream().map(EventEnvelope::eventType).toList();
    }

    private String payloadOf(String type) {
        for (int index = world.events.size() - 1; index >= 0; index--) {
            if (world.events.get(index).eventType().equals(type)) {
                return world.payloads.get(world.payloads.size() - world.events.size() + index);
            }
        }
        throw new AssertionError("no " + type + " event");
    }
}
