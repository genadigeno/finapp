package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A customer's BNPL offers and a checkout's BNPL options, hermetic (`P12-TSK-011`; {@code BNPL_LIFECYCLES.md} section
 * 4.3, {@code PHASE_12_PLAN.md} section 9): the owner-scoped read re-deriving the illustration, the canonical terms and
 * the document from the pinned version and refusing to show bytes that are not the ones hashed; the decline under B2,
 * only while {@code OFFERED} and before the expiry, writing its history, event and audit record - another party's the
 * same answer as an unknown one; and the options read illustrating every plan the merchant offers for the session's
 * gross without asking credit anything.
 */
@DisplayName("the customer's BNPL offers and the checkout's options (P12-TSK-011)")
class BnplOffersTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-011-offers");
    private static final Actor SYSTEM = new Actor("platform", ActorType.SYSTEM);
    private static final long GROSS = 600_00L;

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private BnplOrigination origination;
    private BnplOffers offers;
    private BnplOptions options;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        world = new BnplApplicationFakes();
        BnplTermsAdministration terms = new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS,
                CLOCK, BnplTermsFixtures.BOUNDS);
        applications = new BnplApplications(world.store, terms, world.standing, world.sessions, world.merchants,
                BnplTermsFixtures.BOUNDS, world.credit, world.outbox, world.audit, IDS, CLOCK);
        origination = new BnplOrigination(world.store, world.offers, terms, world.credit, world.sessions, world.outbox,
                IDS, CLOCK);
        offers = new BnplOffers(world.offers, world.terms.store(), world.outbox, world.audit, IDS, CLOCK);
        options = new BnplOptions(world.sessions, world.merchants, BnplTermsFixtures.BOUNDS, terms, world.offers);
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    @Test
    @DisplayName("the owner reads the plan, the re-derived schedule, the totals, the expiry and the document; another party"
            + " reads nothing")
    void theOwnerReadsTheOffer() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS);
        BnplOffers.Read read = offers.read(BnplApplicationFakes.UOW, offer.id(), offer.party()).orElseThrow();
        assertThat(read.illustration().instalmentCount()).isEqualTo(3);
        assertThat(read.illustration().totalPayable()).isEqualTo(read.terms().totalPayable());
        assertThat(read.document()).contains("Plan: PAY_IN_3_MONTHS").contains("Instalment 3 due")
                .contains("Terms SHA-256: " + read.terms().termsSha256Hex());
        assertThat(read.offer().expiresAt()).isEqualTo(offer.expiresAt());
        assertThat(offers.read(BnplApplicationFakes.UOW, offer.id(), UUID.randomUUID())).isEmpty();
        assertThat(offers.read(BnplApplicationFakes.UOW, BnplOfferId.next(IDS), offer.party())).isEmpty();
    }

    @Test
    @DisplayName("the read refuses to show terms that no longer re-derive from the pinned version - a stored canonical form"
            + " or document hash not the bytes it renders is a defect, never a silently different document")
    void theReadRefusesTermsThatDoNotReDerive() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        BnplOfferStore.StoredTerms stored = world.offers.terms.get(offer.id().value());
        String tampered = stored.canonicalTerms().replace("\"graceDays\":\"3\"", "\"graceDays\":\"9\"");
        world.offers.terms.put(offer.id().value(), new BnplOfferStore.StoredTerms(stored.product(), stored.plan(),
                stored.amount(), stored.nominalAnnualRate(), stored.scheduleEngine(), stored.illustrativeStart(),
                stored.instalmentCount(), stored.firstDueDate(), stored.lastDueDate(), stored.firstInstalment(),
                stored.regularInstalment(), stored.finalInstalment(), stored.totalInterest(), stored.totalPayable(),
                stored.canonicalFormat(), tampered, BnplOfferTerms.sha256(tampered), stored.documentSha256()));
        assertThatThrownBy(() -> offers.read(BnplApplicationFakes.UOW, offer.id(), offer.party()))
                .isInstanceOf(IllegalStateException.class);
        world.offers.terms.put(offer.id().value(), new BnplOfferStore.StoredTerms(stored.product(), stored.plan(),
                stored.amount(), stored.nominalAnnualRate(), stored.scheduleEngine(), stored.illustrativeStart(),
                stored.instalmentCount(), stored.firstDueDate(), stored.lastDueDate(), stored.firstInstalment(),
                stored.regularInstalment(), stored.finalInstalment(), stored.totalInterest(), stored.totalPayable(),
                stored.canonicalFormat(), stored.canonicalTerms(), stored.termsSha256(), new byte[32]));
        assertThatThrownBy(() -> offers.read(BnplApplicationFakes.UOW, offer.id(), offer.party()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the decline: B2, OFFERED -> DECLINED with its history row, bnpl.BnplOfferClosed and bnpl.OfferDeclined;"
            + " twice is 409 bnpl.OfferNotAcceptable, nothing written")
    void theCustomerDeclines() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.events.clear();
        world.audited.clear();
        BnplOffer declined = decline(offer, offer.party());
        assertThat(declined.status()).isEqualTo(BnplOfferStatus.DECLINED);
        assertThat(world.events).singleElement().satisfies(event ->
                assertThat(event.eventType()).isEqualTo(BnplOrigination.OFFER_CLOSED_EVENT));
        assertThat(world.payloads.get(world.payloads.size() - 1)).contains("\"status\":\"DECLINED\"")
                .contains("\"reason\":\"CUSTOMER_DECLINED\"");
        assertThat(world.audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(BnplAuditAction.OFFER_DECLINED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.targetId()).isEqualTo(offer.id().value().toString());
        });
        int history = world.offers.history.size();
        assertThatThrownBy(() -> decline(offer, offer.party())).isInstanceOf(BnplOffers.OfferNotAcceptable.class);
        assertThat(world.offers.history).hasSize(history);
        assertThat(world.audited).hasSize(1);
    }

    @Test
    @DisplayName("an offer past its expiry on the database clock cannot be declined - 409, the sweep's to expire - and another"
            + " party's offer is not found, nothing moved")
    void anExpiredOrAnotherPartysOfferIsRefused() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThatThrownBy(() -> decline(offer, UUID.randomUUID())).isInstanceOf(BnplOffers.OfferNotFound.class);
        world.now = offer.expiresAt();
        assertThatThrownBy(() -> decline(offer, offer.party())).isInstanceOf(BnplOffers.OfferNotAcceptable.class);
        assertThat(world.offers.rows.get(offer.id().value()).status()).isEqualTo(BnplOfferStatus.OFFERED);
    }

    @Test
    @DisplayName("an offered application holds its session for good: after a decline no second BNPL application opens on"
            + " it (one decision per purchase, B5) - the session stays for another instrument")
    void aDeclinedOfferDoesNotFreeTheSession() {
        BnplOffer offer = offered(BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        decline(offer, offer.party());
        String token = world.sessions.byToken.entrySet().stream()
                .filter(entry -> entry.getValue().sessionRef().equals(offer.checkoutSession()))
                .map(java.util.Map.Entry::getKey).findFirst().orElseThrow();
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, other,
                new BnplApplications.Submission(Sensitive.of(token), BnplProduct.BNPL_PAY_IN_N,
                        BnplPlan.PAY_IN_3_MONTHS), new Actor(other.toString(), ActorType.CUSTOMER), CORRELATION)))
                .isInstanceOf(BnplApplications.ApplicationOpen.class);
    }

    // ------------------------------------------------------------------ the options

    @Test
    @DisplayName("the options illustrate every plan each offered product's active terms offer for the session's gross -"
            + " no credit asked, nothing written")
    void theOptionsIllustrateEveryOfferedPlan() {
        String token = world.sessions.open(merchant, GROSS);
        world.log.clear();
        List<BnplOptions.ProductOption> answer = options.of(BnplApplicationFakes.UOW, Sensitive.of(token));
        assertThat(answer).extracting(BnplOptions.ProductOption::product)
                .containsExactly(BnplProduct.BNPL_PAY_IN_4, BnplProduct.BNPL_PAY_IN_N);
        assertThat(answer.get(1).plans()).extracting(BnplOptions.PlanOption::plan).containsExactly(
                BnplPlan.PAY_IN_3_MONTHS, BnplPlan.PAY_IN_6_MONTHS, BnplPlan.PAY_IN_12_MONTHS);
        assertThat(answer.get(0).plans().get(0).illustration().amount())
                .isEqualTo(Money.ofMinorUnits(GROSS, BnplTermsFixtures.EUR));
        assertThat(world.log).noneMatch(entry -> entry.startsWith("credit")).noneMatch(entry -> entry.startsWith("insert"));
        assertThat(world.credit.requests).isEmpty();
    }

    @Test
    @DisplayName("a product outside its bounds, a merchant not enabled or no active terms is simply absent; an unknown or"
            + " closed session is SessionNotEligible")
    void theOptionsOmitWhatIsNotOffered() {
        String small = world.sessions.open(merchant, 100_00);
        assertThat(options.of(BnplApplicationFakes.UOW, Sensitive.of(small))).extracting(BnplOptions.ProductOption::product)
                .as("below pay-in-N's bounds").containsExactly(BnplProduct.BNPL_PAY_IN_4);
        UUID notEnabled = UUID.randomUUID();
        assertThat(options.of(BnplApplicationFakes.UOW, Sensitive.of(world.sessions.open(notEnabled, GROSS)))).isEmpty();
        assertThatThrownBy(() -> options.of(BnplApplicationFakes.UOW, Sensitive.of("cs_unknown")))
                .isInstanceOf(BnplApplications.SessionNotEligible.class);
    }

    // ------------------------------------------------------------------ plumbing

    private BnplOffer offered(BnplProduct product, BnplPlan plan) {
        UUID party = UUID.randomUUID();
        String token = world.sessions.open(merchant, GROSS);
        BnplApplication application = world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, party,
                new BnplApplications.Submission(Sensitive.of(token), product, plan),
                new Actor(party.toString(), ActorType.CUSTOMER), CORRELATION));
        world.credit.approve(application.decisionRequest(), GROSS, world.now.plus(Duration.ofDays(1)));
        world.inTransaction(() -> origination.originate(BnplApplicationFakes.UOW, application.id(), SYSTEM));
        return world.offers.ofApplication(BnplApplicationFakes.UOW, application.id()).orElseThrow();
    }

    private BnplOffer decline(BnplOffer offer, UUID who) {
        return world.inTransaction(() -> offers.decline(BnplApplicationFakes.UOW, offer.id(), who,
                new Actor(who.toString(), ActorType.CUSTOMER), CORRELATION));
    }
}
