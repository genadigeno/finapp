package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL application at checkout, hermetic (`P12-TSK-010`; {@code BNPL_LIFECYCLES.md} section 4.2,
 * {@code PHASE_12_PLAN.md} section 7.1 T-app; {@code INV-BNPL-03}, {@code INV-LIFE-01}, {@code INV-CRD-04}): one credit
 * request through the BNPL origin's port for exactly the session's gross and the plan's term; the judgements in T-app's
 * order, each refusal writing nothing - the credit request rolled back with the application; one open application per
 * session and per party and product; the withdrawal under B1 cancelling credit's request; the owner-scoped read. Over
 * {@link BnplApplicationFakes} - the database's ranks, the races counted across instances and credit's real door are
 * {@code BnplApplicationStoreDatabaseTest}'s and {@code app}'s {@code BnplApplicationDatabaseTest}'s.
 */
@DisplayName("the BNPL application at checkout: one session, one open application, one credit request (P12-TSK-010)")
class BnplApplicationsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-010-unit");

    private BnplApplicationFakes world;
    private BnplApplications applications;
    private UUID party;
    private Actor customer;
    private UUID merchant;

    @BeforeEach
    void setUp() {
        world = new BnplApplicationFakes();
        applications = new BnplApplications(world.store,
                new BnplTermsAdministration(world.terms.store(), world.audit, world.outbox, IDS, CLOCK,
                        BnplTermsFixtures.BOUNDS),
                world.standing, world.sessions, world.merchants, BnplTermsFixtures.BOUNDS, world.credit, world.outbox,
                world.audit, IDS, CLOCK);
        party = UUID.randomUUID();
        customer = new Actor(party.toString(), ActorType.CUSTOMER);
        merchant = UUID.randomUUID();
        world.merchants.standings.put(merchant, BnplMerchants.Standing.ENABLED);
        world.terms.activate(BnplTermsFixtures.payIn4().build(), 1);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999").build(), 3);
    }

    // ------------------------------------------------------------------ the submission

    @Test
    @DisplayName("a pay-in-4 application opens exactly one credit request for the session's gross, no term, the customer"
            + " acting - born SUBMITTED, moved AWAITING_DECISION, both history rows, one event, one audit record")
    void aSubmissionOpensExactlyOneCreditRequestForTheSessionGross() {
        String token = world.sessions.open(merchant, 600_03);
        BnplApplication application = submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);

        assertThat(application.status()).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.credit.requests).hasSize(1);
        BnplApplicationFakes.Credit.Request request = world.credit.requests.get(application.decisionRequest());
        assertThat(request.amount()).as("exactly the session's gross").isEqualTo(Money.ofMinorUnits(600_03,
                BnplTermsFixtures.EUR));
        assertThat(request.termMonths()).as("a fixed plan sends no term").isEqualTo(OptionalInt.empty());
        assertThat(request.product()).isEqualTo(BnplProduct.BNPL_PAY_IN_4);
        assertThat(request.party()).isEqualTo(party);
        assertThat(request.actor()).as("the customer's own act").isEqualTo(customer);
        assertThat(application.checkoutSession()).isEqualTo(world.sessions.byToken.get(token).sessionRef());
        assertThat(application.merchant()).isEqualTo(merchant);
        assertThat(application.termsVersion()).as("the version ACTIVE at submission, recorded").isEqualTo(1);
        assertThat(world.store.history).extracting(BnplApplicationStore.HistoryRow::to)
                .containsExactly(BnplApplicationStatus.SUBMITTED, BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.events).extracting(e -> e.eventType()).containsExactly(BnplApplications.SUBMITTED_EVENT);
        assertThat(world.audited).extracting(AuditRecord::operation).containsExactly(BnplAuditAction.APPLICATION_SUBMITTED);
        assertThat(world.audited.get(0).outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(world.audited.get(0).actor()).isEqualTo(customer);
    }

    @Test
    @DisplayName("a pay-in-N application sends its plan's months as the term, and records its product's own version")
    void aPayInNApplicationSendsThePlansTerm() {
        String token = world.sessions.open(merchant, 1_200_00);
        BnplApplication application = submit(token, BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS);
        assertThat(world.credit.requests.get(application.decisionRequest()).termMonths()).isEqualTo(OptionalInt.of(6));
        assertThat(application.plan()).isEqualTo(BnplPlan.PAY_IN_6_MONTHS);
        assertThat(application.termsVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("the judgements in T-app's order: standing, the session, the merchant and its fee, the terms pin, credit's"
            + " request, then the rows, the event and the audit record")
    void theJudgementsComeInTAppsOrder() {
        String token = world.sessions.open(merchant, 600_00);
        submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        assertThat(world.log).containsSubsequence("standing", "session read", "merchant standing", "merchant fee",
                "terms pin BNPL_PAY_IN_4", "credit open", "insert", "history SUBMITTED",
                "transition SUBMITTED -> AWAITING_DECISION", "history AWAITING_DECISION",
                "event " + BnplApplications.SUBMITTED_EVENT, "audit bnpl.ApplicationSubmitted");
        assertThat(world.log.indexOf("credit open")).as("credit is asked only once bnpl has judged everything")
                .isGreaterThan(world.log.indexOf("terms pin BNPL_PAY_IN_4"));
    }

    @Test
    @DisplayName("a session not open is not eligible - an unknown token, a session no longer OPEN, one past its deadline"
            + " on the database clock, one in another currency - and nothing is written")
    void aSessionNotOpenIsNotEligible() {
        String closed = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                euros(600_00), Instant.parse("2026-10-17T11:00:00Z"), false, false));
        String expired = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                euros(600_00), Instant.parse("2026-10-17T09:59:00Z"), true, true));
        String dollars = world.sessions.put(new BnplCheckoutSessions.Session(UUID.randomUUID(), merchant,
                Money.ofMinorUnits(600_00, com.finapp.sharedkernel.money.CurrencyCode.of("USD")),
                Instant.parse("2026-10-17T11:00:00Z"), true, false));
        for (String token : List.of("cs_unknown", closed, expired, dollars)) {
            assertThatThrownBy(() -> submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4))
                    .as(token).isInstanceOf(BnplApplications.SessionNotEligible.class);
        }
        assertNothingWritten();
    }

    @Test
    @DisplayName("a gross outside credit's bounds is AmountOutOfRange before credit is asked; credit's own bound refusal"
            + " is AmountOutOfRange too - nothing written")
    void anAmountOutsideCreditsBoundsIsOutOfRange() {
        for (long gross : List.of(49_99L, 1_000_01L)) {
            String token = world.sessions.open(merchant, gross);
            assertThatThrownBy(() -> submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4))
                    .as(Long.toString(gross)).isInstanceOf(BnplApplications.AmountOutOfRange.class)
                    .hasMessageNotContaining(Long.toString(gross));
        }
        assertThat(world.log).doesNotContain("credit open");
        world.credit.outOfBounds = Optional.of("the term is 3, 6 or 12 months");
        String token = world.sessions.open(merchant, 600_00);
        assertThatThrownBy(() -> submit(token, BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS))
                .isInstanceOf(BnplApplications.AmountOutOfRange.class);
        assertNothingWritten();
    }

    @Test
    @DisplayName("a merchant not enabled refuses - not trading, not enabled, not priced - before the terms and credit")
    void aMerchantNotEnabledRefuses() {
        for (BnplMerchants.Standing standing : List.of(BnplMerchants.Standing.NOT_TRADING,
                BnplMerchants.Standing.NOT_ENABLED, BnplMerchants.Standing.NOT_PRICED)) {
            world.merchants.standings.put(merchant, standing);
            String token = world.sessions.open(merchant, 600_00);
            assertThatThrownBy(() -> submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4))
                    .as(standing.name())
                    .isInstanceOfSatisfying(BnplApplications.MerchantNotEnabled.class,
                            refused -> assertThat(refused.standing()).isEqualTo(standing));
        }
        assertThat(world.log).doesNotContain("credit open").noneMatch(entry -> entry.startsWith("terms pin"));
        assertNothingWritten();
    }

    @Test
    @DisplayName("a sale below its fee is not eligible - nothing pinned, nothing written")
    void aSaleBelowItsFeeIsNotEligible() {
        world.merchants.coversFrom = 600_01;
        String token = world.sessions.open(merchant, 600_00);
        assertThatThrownBy(() -> submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4))
                .isInstanceOfSatisfying(BnplApplications.SessionNotEligible.class, refused -> assertThat(
                        refused.reason()).isEqualTo(BnplApplications.SessionNotEligible.Cause.BELOW_FEE));
        assertNothingWritten();
        assertThat(submit(world.sessions.open(merchant, 600_01), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4)
                .status()).as("one cent of net is enough").isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
    }

    @Test
    @DisplayName("without ACTIVE terms the product is not offered; a plan the active version does not offer is not"
            + " offered either - before credit is asked")
    void withoutActiveTermsTheProductIsNotOffered() {
        world.terms.active.remove(BnplProduct.BNPL_PAY_IN_4);
        assertThatThrownBy(() -> submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4)).isInstanceOf(BnplApplications.ProductNotOffered.class);
        world.terms.activate(BnplTermsFixtures.payInN("0.1999")
                .payInN(Optional.of(BnplTermsFixtures.payInNPart(List.of(3, 6), 15))).build(), 4);
        assertThatThrownBy(() -> submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_N,
                BnplPlan.PAY_IN_12_MONTHS)).isInstanceOf(BnplApplications.ProductNotOffered.class)
                .hasMessageContaining("plan");
        assertThat(world.log).doesNotContain("credit open");
        assertNothingWritten();
        assertThat(submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_6_MONTHS)
                .termsVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("standing lost refuses before the session is read - nothing written")
    void standingLostRefuses() {
        world.inGoodStanding = false;
        assertThatThrownBy(() -> submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4)).isInstanceOf(BnplApplications.NotInGoodStanding.class);
        assertThat(world.log).containsExactly("standing");
        assertNothingWritten();
    }

    @Test
    @DisplayName("a plan of another product is a malformed submission")
    void aPlanOfAnotherProductIsInvalid() {
        assertThatThrownBy(() -> new BnplApplications.Submission(Sensitive.of("cs_x"), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_3_MONTHS)).isInstanceOf(BnplApplications.SubmissionInvalid.class);
        assertThat(new BnplApplications.Submission(Sensitive.of("cs_secret"), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4).toString()).doesNotContain("cs_secret");
    }

    // ------------------------------------------------------------------ one open application

    @Test
    @DisplayName("a second application for the session - another customer's included - is ApplicationOpen, its credit"
            + " request rolled back with it; a withdrawal frees the session for a new application")
    void aSecondApplicationForTheSessionIsApplicationOpen() {
        String token = world.sessions.open(merchant, 600_00);
        BnplApplication first = submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        UUID other = UUID.randomUUID();
        for (BnplProduct product : BnplProduct.values()) {
            BnplPlan plan = product.plans().get(0);
            assertThatThrownBy(() -> submit(other, token, product, plan))
                    .as(product.name()).isInstanceOf(BnplApplications.ApplicationOpen.class);
        }
        assertThat(world.credit.requests).as("each loser's credit request rolled back with it").hasSize(1);
        assertThat(world.store.rows).hasSize(1);

        withdraw(first.id(), party);
        assertThat(submit(other, token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4).status())
                .as("a withdrawn application frees its session").isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
    }

    @Test
    @DisplayName("an OFFERED application still holds its session; a DECLINED or CLOSED_UNDECIDED one frees it")
    void anOfferedApplicationHoldsItsSession() {
        String token = world.sessions.open(merchant, 600_00);
        BnplApplication first = submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        world.store.put(world.store.rows.get(first.id().value()), BnplApplicationStatus.OFFERED);
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> submit(other, token, BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS))
                .isInstanceOf(BnplApplications.ApplicationOpen.class);
        world.store.put(world.store.rows.get(first.id().value()), BnplApplicationStatus.DECLINED);
        assertThat(submit(other, token, BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS).status())
                .isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
    }

    @Test
    @DisplayName("one open application per party and product: a second session's application for the product meets"
            + " credit's one open request; another product is free; bnpl's own backstop holds once credit's request closed")
    void oneOpenApplicationPerPartyAndProduct() {
        BnplApplication first = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4);
        assertThatThrownBy(() -> submit(world.sessions.open(merchant, 700_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4)).isInstanceOf(BnplApplications.ApplicationOpen.class);
        assertThat(submit(world.sessions.open(merchant, 700_00), BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS)
                .status()).as("another product").isEqualTo(BnplApplicationStatus.AWAITING_DECISION);

        // Credit's request lapsed while the application awaits the sweep's closure (P12-TSK-011): the backstop.
        world.credit.expire(first.decisionRequest());
        int requests = world.credit.requests.size();
        assertThatThrownBy(() -> submit(world.sessions.open(merchant, 800_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4)).isInstanceOf(BnplApplications.ApplicationOpen.class);
        assertThat(world.credit.requests).as("the request credit opened rolled back with the refusal").hasSize(requests);
    }

    // ------------------------------------------------------------------ atomicity

    @Test
    @DisplayName("the credit request and the application commit together: a failure after credit's insert, after the"
            + " application's, at its move or its history leaves neither")
    void theCreditRequestAndTheApplicationCommitTogether() {
        for (String failAt : List.of("insert", "transition", "appendEvent")) {
            world.store.failAt = failAt;
            assertThatThrownBy(() -> submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                    BnplPlan.PAY_IN_4)).as(failAt).isInstanceOf(BnplStorageException.class);
            assertNothingWritten();
        }
        world.store.failAt = "";
        assertThat(submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4).status())
                .as("and the retry applies").isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(world.credit.requests).hasSize(1);
    }

    // ------------------------------------------------------------------ the withdrawal

    @Test
    @DisplayName("a withdrawal locks the application first (B1), cancels its credit request from the port, moves it"
            + " WITHDRAWN with its history row, the Closed event and the audit record")
    void aWithdrawalCancelsTheCreditRequest() {
        BnplApplication application = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4);
        world.log.clear();
        BnplApplication withdrawn = withdraw(application.id(), party);

        assertThat(withdrawn.status()).isEqualTo(BnplApplicationStatus.WITHDRAWN);
        assertThat(world.credit.requests.get(application.decisionRequest()).status()).isEqualTo("CANCELLED");
        assertThat(world.log).containsSubsequence("B1 " + application.id().value(), "credit cancel",
                "transition AWAITING_DECISION -> WITHDRAWN", "history WITHDRAWN", "event " + BnplApplications.CLOSED_EVENT,
                "audit bnpl.ApplicationWithdrawn");
        assertThat(world.log.get(0)).as("B1 before anything").startsWith("B1 ");
        assertThat(world.payloads.get(world.payloads.size() - 1)).contains("WITHDRAWN");
        assertThat(world.audited).extracting(AuditRecord::operation).containsExactly(
                BnplAuditAction.APPLICATION_SUBMITTED, BnplAuditAction.APPLICATION_WITHDRAWN);
    }

    @Test
    @DisplayName("only an application awaiting a decision, whose credit request credit has not evaluated, is withdrawable -"
            + " a refusal writes nothing")
    void onlyAwaitingDecisionIsWithdrawable() {
        BnplApplication evaluated = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4);
        world.credit.evaluate(evaluated.decisionRequest());
        assertThatThrownBy(() -> withdraw(evaluated.id(), party))
                .isInstanceOf(BnplApplications.ApplicationNotWithdrawable.class);
        assertThat(world.store.rows.get(evaluated.id().value()).status())
                .isEqualTo(BnplApplicationStatus.AWAITING_DECISION);

        BnplApplication offered = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_N,
                BnplPlan.PAY_IN_3_MONTHS);
        world.store.put(world.store.rows.get(offered.id().value()), BnplApplicationStatus.OFFERED);
        int writes = world.store.history.size();
        world.log.clear();
        assertThatThrownBy(() -> withdraw(offered.id(), party))
                .isInstanceOf(BnplApplications.ApplicationNotWithdrawable.class);
        assertThat(world.log).as("credit is not asked for a terminal application").doesNotContain("credit cancel");
        assertThat(world.credit.requests.get(offered.decisionRequest()).status()).isEqualTo("SUBMITTED");
        assertThat(world.store.history).hasSize(writes);
    }

    @Test
    @DisplayName("another party's application is not found - read and withdrawal alike, nothing moved")
    void anotherPartysApplicationIsNotFound() {
        BnplApplication application = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4);
        UUID stranger = UUID.randomUUID();
        assertThat(applications.read(BnplApplicationFakes.UOW, application.id(), stranger)).isEmpty();
        assertThatThrownBy(() -> withdraw(application.id(), stranger))
                .isInstanceOf(BnplApplications.ApplicationNotFound.class);
        assertThat(world.credit.requests.get(application.decisionRequest()).status()).isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("the owner reads credit's reasons in the customer's words once declined, and none before")
    void aDeclinedApplicationReadsCreditsReasons() {
        BnplApplication application = submit(world.sessions.open(merchant, 600_00), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4);
        assertThat(applications.read(BnplApplicationFakes.UOW, application.id(), party).orElseThrow().reasonTexts())
                .isEmpty();
        world.store.put(world.store.rows.get(application.id().value()), BnplApplicationStatus.DECLINED);
        assertThat(applications.read(BnplApplicationFakes.UOW, application.id(), party).orElseThrow().reasonTexts())
                .containsExactlyElementsOf(world.credit.reasons);
    }

    @Test
    @DisplayName("the events and the audit records name no amount and never the token")
    void theRecordsNameNoAmountAndNeverTheToken() {
        String token = world.sessions.open(merchant, 734_56);
        BnplApplication application = submit(token, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4);
        withdraw(application.id(), party);
        assertThat(world.payloads).allSatisfy(payload -> assertThat(payload).doesNotContain(token)
                .doesNotContain("73456").doesNotContain("734.56"));
        assertThat(world.audited).allSatisfy(record -> assertThat(record.changeSummary().orElseThrow())
                .doesNotContain(token).doesNotContain("73456").doesNotContain("734.56"));
        assertThat(application.toString()).doesNotContain(party.toString());
    }

    // ------------------------------------------------------------------ plumbing

    private BnplApplication submit(String token, BnplProduct product, BnplPlan plan) {
        return submit(party, token, product, plan);
    }

    private BnplApplication submit(UUID who, String token, BnplProduct product, BnplPlan plan) {
        Actor actor = who.equals(party) ? customer : new Actor(who.toString(), ActorType.CUSTOMER);
        return world.inTransaction(() -> applications.submit(BnplApplicationFakes.UOW, who,
                new BnplApplications.Submission(Sensitive.of(token), product, plan), actor, CORRELATION));
    }

    private BnplApplication withdraw(BnplApplicationId id, UUID who) {
        return world.inTransaction(() -> applications.withdraw(BnplApplicationFakes.UOW, id, who,
                new Actor(who.toString(), ActorType.CUSTOMER), CORRELATION));
    }

    private void assertNothingWritten() {
        assertThat(world.credit.requests).as("no credit request").isEmpty();
        assertThat(world.store.rows).as("no application").isEmpty();
        assertThat(world.store.history).as("no history").isEmpty();
        assertThat(world.events).as("no event").isEmpty();
        assertThat(world.audited).as("no audit record").isEmpty();
    }

    private static Money euros(long minor) {
        return Money.ofMinorUnits(minor, BnplTermsFixtures.EUR);
    }
}
