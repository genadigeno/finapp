package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.creditmath.SchedulePeriod;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The offer against a real database (`P11-TSK-011`; {@code lending V005}, {@code PHASE_11_PLAN.md} sections 5.1, 5.2,
 * 7.1 T-off and 7.5, {@code LENDING_LIFECYCLES.md} sections 4.3 and 4.4; {@code INV-LND-05}, {@code INV-HIST-04},
 * {@code INV-EVT-04}, {@code INV-LIFE-01}): every race counted from the rows - ten sweepers, one offer; credit's event
 * twice, late, forged or never, one outcome, the read's; an activation waiting on the offer that pins its predecessor;
 * decline and expiry at the boundary, exactly one - and every rank of the machine held for every writer by raw SQL.
 *
 * <p><strong>Credit is scripted</strong>: lending has no build edge to {@code credit} (A16), so the port
 * {@link LoanCreditDecisions} is answered here by a test double each case steers - what credit's request says is
 * exactly what the case says it says. The adapter over credit's real tables is {@code app}'s and is proven end to end
 * there ({@code LoanOfferDatabaseTest}). The applications are born through the real store, in the submission's shape.
 *
 * <p>The products' terms are shared rows in the suite's database, so every case activates the version it needs through
 * the four-eyes administration itself and never assumes what an earlier case left.
 */
@Tag("database")
@DisplayName("the offer (P11-TSK-011)")
class LoanOriginationDatabaseTest {

    /** No case leaves a terms proposal awaiting a decision (the Phase 11 gate's R6): later suites' fixtures propose. */
    @org.junit.jupiter.api.AfterEach
    void noTermsProposalLeftPending() {
        DisbursementWorld.clearPendingTerms(LoanProduct.PERSONAL_LOAN);
        DisbursementWorld.clearPendingTerms(LoanProduct.CREDIT_LINE);
    }

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcLoanTermsStore TERMS_STORE = new JdbcLoanTermsStore();
    private static final LoanTermsAdministration ADMINISTRATION = new LoanTermsAdministration(TERMS_STORE,
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, LoanTermsFixtures.BOUNDS);
    private static final JdbcLoanApplicationStore APPLICATIONS = new JdbcLoanApplicationStore();
    private static final JdbcLoanOfferStore OFFER_STORE = new JdbcLoanOfferStore();
    private static final ScriptedCredit CREDIT = new ScriptedCredit();
    private static final LoanOrigination ORIGINATION = new LoanOrigination(APPLICATIONS, OFFER_STORE, ADMINISTRATION,
            CREDIT, new JdbcOutboxWriter(), IDS, CLOCK);
    private static final LoanOffers OFFERS =
            new LoanOffers(OFFER_STORE, TERMS_STORE, new JdbcOutboxWriter(), IDS, CLOCK);
    private static final Actor SYSTEM = new Actor("lending-origination", ActorType.SYSTEM);
    private static final LoanProduct LOAN = LoanProduct.PERSONAL_LOAN;
    private static final LoanProduct LINE = LoanProduct.CREDIT_LINE;
    private static final Duration HOUR = Duration.ofHours(1);

    // ------------------------------------------------------------------ the counted races

    @Test
    @DisplayName("ten sweepers on one approved application - five claiming, five handed its id after a lapsed permit -"
            + " leave one offer, one terms row, one OFFERED history row and one LoanOffered; nothing thrown; and a raw"
            + " writer's second offer for one application meets UNIQUE (application_id)")
    void tenSweepersOneOffer() throws Exception {
        activate(LOAN, "0.0811");
        Application application = application(LOAN, 10);
        UUID decision = approve(application, 10_000_00, Optional.of(36), Duration.ofDays(30));
        List<List<LoanOrigination.Outcome>> outcomes = race(10, index -> index % 2 == 0
                ? sweep(1, HOUR).stream().filter(step -> step.kind() != LoanOrigination.Outcome.Kind.WAITING).toList()
                : List.<LoanOrigination.Outcome>of(originate(application.id())));
        List<LoanOrigination.Outcome.Kind> kinds =
                outcomes.stream().flatMap(List::stream).map(LoanOrigination.Outcome::kind).toList();
        assertThat(kinds).filteredOn(kind -> kind == LoanOrigination.Outcome.Kind.OFFERED).hasSize(1);
        assertThat(kinds).as("every loser found the application moved and wrote nothing")
                .filteredOn(kind -> kind != LoanOrigination.Outcome.Kind.OFFERED)
                .containsOnly(LoanOrigination.Outcome.Kind.NOTHING);
        assertThat(applicationStatus(application.id())).isEqualTo("OFFERED");
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?", application.id().value()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE decision_id = ?", decision)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_offer_terms t JOIN lending.loan_offer o ON o.id = t.offer_id"
                + " WHERE o.application_id = ?", application.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_application_event WHERE application_id = ?"
                + " AND to_status = 'OFFERED'", application.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event e JOIN lending.loan_offer o ON o.id = e.aggregate_id"
                + " WHERE o.application_id = ? AND e.event_type = 'lending.LoanOffered'", application.id().value()))
                .isEqualTo(1);

        // The third rank, alone: a raw writer births two offers for one application still AWAITING_DECISION.
        Application another = application(LOAN, 10);
        UUID version = activeVersion(LOAN);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                rawOffer(owner, another, version);
                assertThatThrownBy(() -> rawOffer(owner, another, version))
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("23505"));
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("credit's decision event delivered twice before the sweep and once after it offers once: each hint makes"
            + " the application due and nothing else; the sweep's read decides; a hint after the offer changes nothing")
    void aDecisionEventTwiceOffersOnce() throws Exception {
        activate(LOAN, "0.0812");
        Application application = application(LOAN, 15);
        sweepUntilClaimed(application, HOUR);
        UUID decision = approve(application, 7_500_00, Optional.of(24), Duration.ofDays(30));
        assertThat(permitDue(application)).as("the claim pushed the permit an hour out").isFalse();
        assertThat(hintDecision(decision)).isTrue();
        assertThat(hintDecision(decision))
                .as("the second delivery finds the application already due").isFalse();
        assertThat(applicationStatus(application.id())).as("a hint moves no state").isEqualTo("AWAITING_DECISION");
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(hintDecision(decision))
                .as("a late delivery finds nothing awaiting").isFalse();
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?", application.id().value()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("no event at all still offers: the permit an earlier claim stamped lapses on the database clock and the"
            + " next sweep reads credit's approval - within one sweep interval")
    void noEventStillOffers() throws Exception {
        activate(LOAN, "0.0813");
        Application application = application(LOAN, 20);
        Duration permit = Duration.ofMillis(800);
        assertThat(sweepUntilClaimed(application, permit)).isEqualTo(LoanOrigination.Outcome.Kind.WAITING);
        approve(application, 5_000_00, Optional.of(12), Duration.ofDays(30));
        awaitDatabase("SELECT next_step_at <= statement_timestamp() FROM lending.loan_application WHERE id = ?",
                application.id().value());
        assertThat(sweepUntilClaimed(application, permit)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(applicationStatus(application.id())).isEqualTo("OFFERED");
    }

    @Test
    @DisplayName("a forged or late event changes nothing the read would not: a forged closure of an open request leaves"
            + " the application waiting; an unknown decision hints nothing; an APPROVED event for a request credit"
            + " declined still declines; a late closure after the offer touches nothing")
    void aForgedOrLateEventChangesNothing() throws Exception {
        activate(LOAN, "0.0814");
        Application open = application(LOAN, 25);
        assertThat(hintRequest(open.decisionRequest()))
                .as("the forged closure pulls a permit, nothing more").isFalse();
        assertThat(sweepUntilClaimed(open, HOUR)).as("credit's request is open: the read says wait")
                .isEqualTo(LoanOrigination.Outcome.Kind.WAITING);
        assertThat(applicationStatus(open.id())).isEqualTo("AWAITING_DECISION");
        assertThat(hintDecision(UUID.randomUUID()))
                .as("a decision credit does not know resolves to nothing").isFalse();

        Application declined = application(LOAN, 25);
        UUID decision = UUID.randomUUID();
        CREDIT.decide(declined.decisionRequest(), new LoanCreditDecisions.CreditOutcome.Declined(decision), decision);
        // An event claiming approval for it carries nothing the step reads: it resolves to the request, and only that.
        assertThat(hintDecision(decision)).isFalse();
        assertThat(sweepUntilClaimed(declined, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.DECLINED);
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?", declined.id().value()))
                .isZero();

        Application offered = application(LOAN, 25);
        approve(offered, 3_000_00, Optional.of(12), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(offered, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(hintRequest(offered.decisionRequest()))
                .as("a late closure finds nothing awaiting").isFalse();
        assertThat(applicationStatus(offered.id())).isEqualTo("OFFERED");
        assertThat(offerStatus(offered)).isEqualTo("OFFERED");
    }

    // ------------------------------------------------------------------ the exits

    @Test
    @DisplayName("credit's decline closes the application DECLINED - its history CREDIT_DECLINED, LoanApplicationClosed"
            + " published, no offer")
    void aDeclineClosesTheApplication() throws Exception {
        activate(LINE, "0.1611");
        Application application = application(LINE, 5);
        UUID decision = UUID.randomUUID();
        CREDIT.decide(application.decisionRequest(), new LoanCreditDecisions.CreditOutcome.Declined(decision), decision);
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.DECLINED);
        assertThat(applicationStatus(application.id())).isEqualTo("DECLINED");
        assertThat(scalar("SELECT reason FROM lending.loan_application_event WHERE application_id = ?"
                + " AND to_status = 'DECLINED'", application.id().value())).isEqualTo("CREDIT_DECLINED");
        assertThat(scalar("SELECT closed_at IS NOT NULL FROM lending.loan_application WHERE id = ?",
                application.id().value())).isEqualTo("t");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanApplicationClosed'", application.id().value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?", application.id().value()))
                .isZero();
    }

    @Test
    @DisplayName("credit's request CANCELLED, EXPIRED or ABANDONED closes the application CLOSED_UNDECIDED with the"
            + " closure named in its reason; none offers")
    void aClosedCreditRequestClosesUndecided() throws Exception {
        activate(LOAN, "0.0815");
        for (LoanCreditDecisions.CreditOutcome.Closure closure : LoanCreditDecisions.CreditOutcome.Closure.values()) {
            Application application = application(LOAN, 1);
            CREDIT.decide(application.decisionRequest(), new LoanCreditDecisions.CreditOutcome.Closed(closure), null);
            assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED);
            assertThat(applicationStatus(application.id())).isEqualTo("CLOSED_UNDECIDED");
            assertThat(scalar("SELECT reason FROM lending.loan_application_event WHERE application_id = ?"
                    + " AND to_status = 'CLOSED_UNDECIDED'", application.id().value()))
                    .isEqualTo("CREDIT_REQUEST_" + closure.name());
            assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?",
                    application.id().value())).isZero();
        }
    }

    @Test
    @DisplayName("an approval with no ACTIVE terms, on a day the active version no longer offers, or already lapsed closes"
            + " the application CLOSED_UNDECIDED with its reason - no offer is born")
    void anApprovalThatCannotBeOfferedClosesUndecided() throws Exception {
        activate(LOAN, "0.0816");
        Application lapsed = application(LOAN, 5);
        CREDIT.approve(lapsed, Money.ofMinorUnits(2_000_00, LoanTermsFixtures.EUR), Optional.of(12),
                Instant.now().minus(Duration.ofMinutes(1)));
        assertThat(sweepUntilClaimed(lapsed, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED);
        assertThat(lastReason(lapsed)).isEqualTo("DECISION_LAPSED");

        Application onTheFifth = application(LOAN, 5);
        approve(onTheFifth, 2_000_00, Optional.of(12), Duration.ofDays(30));
        activate(LOAN, LoanTermsFixtures.personalLoan("0.0817").allowedDays(List.of(10, 20)).build());
        assertThat(sweepUntilClaimed(onTheFifth, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED);
        assertThat(lastReason(onTheFifth)).isEqualTo("REPAYMENT_DAY_NOT_OFFERED");
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id IN (?, ?)",
                lapsed.id().value(), onTheFifth.id().value())).isZero();
    }

    @Test
    @DisplayName("a specific offer whose own schedule would not amortise - a term beyond the bounds the terms door judged"
            + " (credit's product widened after the terms were proposed) - closes the application CLOSED_UNDECIDED"
            + " (TERMS_NOT_AMORTISING), no offer")
    void aNonAmortisingOfferClosesTheApplicationUndecided() throws Exception {
        // 23.09 % amortises across credit's 6-60 months (the door's judgement) - and not over 600, where the first
        // period's interest outgrows the level instalment.
        activate(LOAN, "0.2309");
        Application application = application(LOAN, 28);
        approve(application, 20_000_00, Optional.of(600), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED);
        assertThat(lastReason(application)).isEqualTo("TERMS_NOT_AMORTISING");
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE application_id = ?", application.id().value()))
                .isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanApplicationClosed'", application.id().value())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the pin

    @Test
    @DisplayName("the offer pins the version ACTIVE at the offering instant, not the one recorded at submission: its rate,"
            + " engines and template in the terms row and the canonical terms, hashed; a raw writer pinning a retired"
            + " version is refused (INV-HIST-04)")
    void theOfferPinsTheVersionActiveAtTheOfferingInstant() throws Exception {
        UUID submittedUnder = activate(LOAN, "0.0818").value();
        Application application = application(LOAN, 10);
        UUID offeredUnder = activate(LOAN, "0.0919").value();
        approve(application, 12_000_00, Optional.of(48), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(scalar("SELECT terms_version_id::text FROM lending.loan_application WHERE id = ?",
                application.id().value())).as("the application's record is informational").isEqualTo(
                submittedUnder.toString());
        assertThat(scalar("SELECT terms_version_id::text FROM lending.loan_offer WHERE application_id = ?",
                application.id().value())).isEqualTo(offeredUnder.toString());
        assertThat(scalar("SELECT t.nominal_annual_rate || '|' || t.schedule_engine_version || '|'"
                + " || t.agreement_template_id || '|' || t.term_months || '|' || t.amount_minor"
                + " FROM lending.loan_offer_terms t JOIN lending.loan_offer o ON o.id = t.offer_id"
                + " WHERE o.application_id = ?", application.id().value()))
                .isEqualTo("0.0919|1|PERSONAL_LOAN_AGREEMENT|48|1200000");
        String canonical = scalar("SELECT t.canonical_terms FROM lending.loan_offer_terms t JOIN lending.loan_offer o"
                + " ON o.id = t.offer_id WHERE o.application_id = ?", application.id().value());
        assertThat(canonical).contains("\"id\":\"" + offeredUnder + "\"", "\"nominalAnnualRate\":\"0.0919\"",
                "\"amount\":\"1200000\"", "\"termMonths\":\"48\"", "\"schedule\":\"SCHEDULE_ENGINE_V1\"");
        assertThat(scalar("SELECT encode(t.terms_sha256, 'hex') FROM lending.loan_offer_terms t JOIN lending.loan_offer o"
                + " ON o.id = t.offer_id WHERE o.application_id = ?", application.id().value()))
                .isEqualTo(HexFormat.of().formatHex(CanonicalOfferTerms.sha256(canonical)));

        Application raw = application(LOAN, 10);
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                assertThatThrownBy(() -> rawOffer(owner, raw, submittedUnder))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("ACTIVE terms version");
            } finally {
                owner.rollback();
            }
        }
    }

    @Test
    @DisplayName("an activation waits on an offer being built: the offering transaction holds the ACTIVE version FOR"
            + " SHARE, the activation's waiting statement is its FOR UPDATE of the ACTIVE row, and the offer commits on"
            + " the version it pinned")
    void anActivationWaitsOnAnOfferBeingBuilt() throws Exception {
        UUID pinned = activate(LOAN, "0.0820").value();
        Application application = application(LOAN, 10);
        approve(application, 9_000_00, Optional.of(36), Duration.ofDays(30));
        LoanTermsAdministration.Proposed successor = propose(LOAN, "0.0821");
        try (Connection building = DatabaseRoles.application()) {
            building.setAutoCommit(false);
            assertThat(ORIGINATION.originate(building, application.id(), SYSTEM).kind())
                    .isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
            int builder;
            try (Statement pid = building.createStatement(); ResultSet row = pid.executeQuery("SELECT pg_backend_pid()")) {
                row.next();
                builder = row.getInt(1);
            }
            CompletableFuture<LoanTermsAdministration.Decided> activation = CompletableFuture.supplyAsync(() ->
                    inOneTransaction(uow -> ADMINISTRATION.approve(uow, successor.id(), employee(), "waits", correlation())));
            String waiting = awaitAStatementBlockedBy(builder);
            assertThat(waiting).as("A2 - the ACTIVE row's lock - waits on the offer's pin")
                    .contains("FOR UPDATE").contains("status = 'ACTIVE'");
            assertThat(activation).isNotDone();
            building.commit();
            assertThat(activation.get(30, TimeUnit.SECONDS).retired()).contains(LoanTermsVersionId.of(pinned));
        }
        assertThat(scalar("SELECT terms_version_id::text FROM lending.loan_offer WHERE application_id = ?",
                application.id().value())).isEqualTo(pinned.toString());
    }

    // ------------------------------------------------------------------ the expiry

    @Test
    @DisplayName("the offer expires at the earlier of its validity and the decision's - both on the database clock -"
            + " and its permit is due exactly then")
    void theOfferExpiresAtTheEarlierOfValidityAndDecision() throws Exception {
        activate(LOAN, LoanTermsFixtures.personalLoan("0.0822").offerValidityDays(7).build());
        Application longDecision = application(LOAN, 10);
        approve(longDecision, 4_000_00, Optional.of(12), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(longDecision, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(scalar("SELECT (expires_at = offered_at + interval '7 days' AND next_step_at = expires_at)::text"
                + " FROM lending.loan_offer WHERE application_id = ?", longDecision.id().value()))
                .as("the version's validity, the earlier").isEqualTo("true");

        Application shortDecision = application(LOAN, 10);
        Instant validUntil = Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MILLIS);
        CREDIT.approve(shortDecision, Money.ofMinorUnits(4_000_00, LoanTermsFixtures.EUR), Optional.of(12), validUntil);
        assertThat(sweepUntilClaimed(shortDecision, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        assertThat(scalar("SELECT (expires_at = decision_valid_until AND expires_at = ?::timestamptz)::text"
                + " FROM lending.loan_offer WHERE application_id = ?", validUntil.toString(), shortDecision.id().value()))
                .as("the decision's validity, the earlier").isEqualTo("true");
    }

    @Test
    @DisplayName("decline and expiry at the boundary leave exactly one - racing on the instant over five offers; a"
            + " decline after the instant is refused though no sweep has run, an expiry before it only moves the permit;"
            + " raw SQL on the wrong side of the clock is refused for every role")
    void declineAndExpiryAtTheBoundaryLeaveExactlyOne() throws Exception {
        activate(LINE, "0.1612");
        for (int round = 0; round < 5; round++) {
            Application application = application(LINE, 10);
            CREDIT.approve(application, Money.ofMinorUnits(1_500_00, LoanTermsFixtures.EUR), Optional.empty(),
                    DisbursementWorld.databaseNow().plusMillis(1_500));
            assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
            LoanOfferId offer = offerOf(application);
            List<String> outcomes = race(2, index -> {
                // Both released on the same database instant, a few milliseconds before the expiry.
                awaitDatabase("SELECT expires_at - interval '3 milliseconds' <= statement_timestamp()"
                        + " FROM lending.loan_offer WHERE id = ?", offer.value());
                if (index == 0) {
                    try {
                        inOneTransaction(uow -> OFFERS.decline(uow, offer, application.party(), customer(application),
                                correlation()));
                        return "DECLINED";
                    } catch (LoanOffers.OfferNotAcceptable refused) {
                        return "-";
                    }
                }
                return expire(offer).isPresent() ? "EXPIRED" : "-";
            });
            List<String> moved = outcomes.stream().filter(outcome -> !outcome.equals("-")).toList();
            assertThat(moved).as("round %d: never both", round).hasSizeLessThanOrEqualTo(1);
            if (moved.isEmpty()) {
                // The expiry ran a moment early and the decline a moment late: neither moved; the next sweep expires.
                awaitDatabase("SELECT expires_at <= statement_timestamp() FROM lending.loan_offer WHERE id = ?",
                        offer.value());
                assertThat(expire(offer)).isPresent();
                moved = List.of("EXPIRED");
            }
            assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?", offer.value()))
                    .as("round %d", round).isEqualTo(moved.get(0));
            assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ? AND from_status = 'OFFERED'",
                    offer.value())).as("round %d: exactly one edge out of OFFERED", round).isEqualTo(1);
        }

        Application unswept = application(LINE, 10);
        // Valid a second on the DATABASE's clock - the clock the origination judges the lapse on (P11-TSK-036): planted
        // on this JVM's clock, which the test VM's database clock runs up to 650 ms ahead of (ADR-0063), a 600 ms validity
        // had lapsed before the step read it and the application closed CLOSED_UNDECIDED.
        CREDIT.approve(unswept, Money.ofMinorUnits(1_500_00, LoanTermsFixtures.EUR), Optional.empty(),
                DisbursementWorld.databaseNow().plusMillis(1_000));
        assertThat(sweepUntilClaimed(unswept, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId expiring = offerOf(unswept);
        assertThat(expire(expiring))
                .as("not yet expired: nothing").isEmpty();
        assertThat(scalar("SELECT (next_step_at = expires_at)::text FROM lending.loan_offer WHERE id = ?",
                expiring.value())).as("the permit set back to the expiry").isEqualTo("true");
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE lending.loan_offer SET status = 'EXPIRED' WHERE id = '" + expiring.value() + "'",
                    "P0001");
        }
        awaitDatabase("SELECT expires_at <= statement_timestamp() FROM lending.loan_offer WHERE id = ?", expiring.value());
        assertThatExceptionOfType(LoanOffers.OfferNotAcceptable.class).isThrownBy(() -> inOneTransaction(uow ->
                OFFERS.decline(uow, expiring, unswept.party(), customer(unswept), correlation())));
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                // The customer's side past the instant: lending V026's own SQLSTATE (P11-TSK-034).
                refused(writer, "UPDATE lending.loan_offer SET status = 'DECLINED' WHERE id = '" + expiring.value() + "'",
                        JdbcLoanStore.OFFER_EXPIRED_SQLSTATE);
            }
        }
        assertThat(offerStatus(unswept)).as("an expired offer is not declined; the sweep expires it").isEqualTo("OFFERED");
        assertThat(expire(expiring)).contains(LINE);
        assertThat(offerStatus(unswept)).isEqualTo("EXPIRED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanOfferClosed'", expiring.value())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the customer

    @Test
    @DisplayName("the customer declines their own offer - DECLINED, its history and LoanOfferClosed - once; a second"
            + " decline is OfferNotAcceptable and another party's is OfferNotFound, each writing nothing")
    void theCustomerDeclinesTheOffer() throws Exception {
        activate(LINE, "0.1613");
        Application application = application(LINE, 15);
        approve(application, 2_000_00, Optional.empty(), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId offer = offerOf(application);
        assertThatExceptionOfType(LoanOffers.OfferNotFound.class).isThrownBy(() -> inOneTransaction(uow ->
                OFFERS.decline(uow, offer, UUID.randomUUID(), customer(application), correlation())));
        LoanOffer declined = inOneTransaction(uow ->
                OFFERS.decline(uow, offer, application.party(), customer(application), correlation()));
        assertThat(declined.status()).isEqualTo(LoanOfferStatus.DECLINED);
        assertThat(declined.closedAt()).isPresent();
        assertThatExceptionOfType(LoanOffers.OfferNotAcceptable.class).isThrownBy(() -> inOneTransaction(uow ->
                OFFERS.decline(uow, offer, application.party(), customer(application), correlation())));
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ? AND to_status = 'DECLINED'"
                + " AND reason = 'CUSTOMER_DECLINED' AND actor_type = 'CUSTOMER'", offer.value())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE aggregate_id = ?"
                + " AND event_type = 'lending.LoanOfferClosed'", offer.value())).isEqualTo(1);
    }

    @Test
    @DisplayName("the owner's read shows the stored terms, the document whose bytes were hashed and - for a loan - the"
            + " illustrative periods the pinned engine re-derives to the stored totals; another party reads nothing")
    void theReadShowsWhatWasHashed() throws Exception {
        activate(LOAN, "0.0823");
        Application application = application(LOAN, 10);
        approve(application, 10_000_00, Optional.of(36), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        LoanOfferId offer = offerOf(application);
        LoanOffers.Read read = read(offer, application.party()).orElseThrow();
        LoanOffer.Terms terms = read.offer().terms();
        assertThat(terms.amount()).isEqualTo(Money.ofMinorUnits(10_000_00, LoanTermsFixtures.EUR));
        assertThat(terms.termMonths()).contains(36);
        assertThat(CanonicalOfferTerms.sha256(read.document())).isEqualTo(terms.documentSha256());
        assertThat(read.document()).contains("ILLUSTRATIVE SCHEDULE", terms.termsSha256Hex(), "10000.00 EUR");
        assertThat(read.illustrativePeriods()).hasSize(36);
        LoanOffer.Illustration illustration = terms.illustration().orElseThrow();
        Money interest = read.illustrativePeriods().stream().map(SchedulePeriod::interest)
                .reduce(Money.zero(LoanTermsFixtures.EUR), Money::plus);
        assertThat(interest).isEqualTo(illustration.totalInterest());
        assertThat(illustration.totalPayable()).isEqualTo(terms.amount().plus(interest));
        assertThat(illustration.start()).as("from the offering day, on the database clock in the pinned zone")
                .isEqualTo(read.offer().offeredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate());
        assertThat(read(offer, UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a terms version east of UTC is offerable end to end (P11-TSK-031): +14:00 and +05:30 activated, approved"
            + " and offered in canonical format 2 spelling the zone, the stored format the bytes' own, the illustration"
            + " from the offering day in the pinned zone; raw SQL holds every writer to V011's format CHECK")
    void aVersionEastOfUtcIsOfferable() throws Exception {
        List<UUID> east = new ArrayList<>();
        try {
            for (String zone : List.of("+14:00", "+05:30")) {
                activate(LOAN, LoanTermsFixtures.of(LOAN, "0.0824").servicingZone(zone).build());
                Application application = application(LOAN, 10);
                approve(application, 10_000_00, Optional.of(36), Duration.ofDays(30));
                assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
                LoanOfferId offer = offerOf(application);
                east.add(offer.value());
                LoanOffers.Read read = read(offer, application.party()).orElseThrow();
                LoanOffer.Terms terms = read.offer().terms();
                assertThat(terms.canonicalTerms()).startsWith("{\"format\":\"2\",")
                        .contains("\"servicingZone\":\"" + zone + "\"");
                assertThat(CanonicalOfferTerms.sha256(terms.canonicalTerms())).isEqualTo(terms.termsSha256());
                assertThat(scalar("SELECT canonical_format::text FROM lending.loan_offer_terms WHERE offer_id = ?",
                        offer.value())).isEqualTo("2");
                assertThat(terms.illustration().orElseThrow().start())
                        .as("from the offering day, on the database clock in the pinned zone")
                        .isEqualTo(read.offer().offeredAt().atZone(java.time.ZoneOffset.of(zone)).toLocalDate());
            }
        } finally {
            activate(LOAN, "0.0824"); // the suite's later cases, and its siblings', find a UTC version
        }
        Application utc = application(LOAN, 10);
        approve(utc, 5_000_00, Optional.of(24), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(utc, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        UUID plain = offerOf(utc).value();
        assertThat(scalar("SELECT canonical_format::text FROM lending.loan_offer_terms WHERE offer_id = ?", plain))
                .as("a zone format 1 could spell is still written in format 2").isEqualTo("2");

        // The CHECK alone, as rawSqlCannotSkipAnEdgeForAnyRole's: the born-once trigger admits a second terms row for an
        // OFFERED offer and the CHECK is judged before the primary key - so 23505 means the CHECK admitted the row.
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, rawTermsCopy(east.get(0), "canonical_format", "canonical_terms"), "23505");
            refused(owner, rawTermsCopy(plain, "canonical_format", "canonical_terms"), "23505");
            // A '+' under format 1, the bytes declaring 1 and their hash recomputed: format 1's token set refuses it.
            refused(owner, rawTermsCopy(east.get(0), "1::smallint",
                    "replace(canonical_terms, '{\"format\":\"2\"', '{\"format\":\"1\"')"), "23514");
            // The column disagreeing with the bytes it describes - format 1 claimed over format-2 bytes with no '+'.
            refused(owner, rawTermsCopy(plain, "1::smallint", "canonical_terms"), "23514");
            // A format this build does not write, declared by both the column and the bytes.
            refused(owner, rawTermsCopy(plain, "3::smallint",
                    "replace(canonical_terms, '{\"format\":\"2\"', '{\"format\":\"3\"')"), "23514");
            // Format 2's token set is format 1's and '+', nothing more.
            refused(owner, rawTermsCopy(east.get(0), "canonical_format", "replace(canonical_terms, '+', ' ')"), "23514");
        }
    }

    /**
     * An INSERT copying {@code offer}'s terms row with {@code format} and {@code canonical} as its canonical columns and
     * the hash recomputed over {@code canonical} - so only the canonical-shape CHECK can refuse it before the key.
     */
    private static String rawTermsCopy(UUID offer, String format, String canonical) {
        return "INSERT INTO lending.loan_offer_terms SELECT offer_id, product, currency, scale,"
                + " amount_minor, term_months, nominal_annual_rate, repayment_day, illustrative_start, first_due_date,"
                + " instalment_minor, final_instalment_minor, total_interest_minor, total_payable_minor,"
                + " minimum_payment_floor_minor, minimum_payment_ratio_bps, payment_due_days,"
                + " schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                + " statement_engine_version, agreement_template_id, agreement_template_version, " + format + ", "
                + canonical + ", sha256(convert_to(" + canonical + ", 'UTF8')), document_sha256"
                + " FROM lending.loan_offer_terms WHERE offer_id = '" + offer + "'";
    }

    // ------------------------------------------------------------------ the starvation lesson

    @Test
    @DisplayName("a starved page re-stamps its permit: waiting applications and one whose step fails never hold the"
            + " page - an approved application behind them is offered within the claimed pages, and the failing one is"
            + " retried after its permit lapses")
    void aStarvedPageRestampsItsPermit() throws Exception {
        activate(LOAN, "0.0824");
        List<Application> waiting = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            waiting.add(application(LOAN, 10));
        }
        Application failing = application(LOAN, 10);
        CREDIT.fail(failing.decisionRequest());
        Application approved = application(LOAN, 10);
        approve(approved, 6_000_00, Optional.of(24), Duration.ofDays(30));
        // Ahead of it in the claim's order: every application awaiting with an earlier permit (or the same, a lower id) -
        // other cases' leftovers in the shared database included. Each page re-stamps an hour out, so none returns.
        long ahead = count("SELECT count(*) FROM lending.loan_application a, lending.loan_application me"
                + " WHERE me.id = ? AND a.status = 'AWAITING_DECISION' AND (a.next_step_at, a.id) < (me.next_step_at, me.id)",
                approved.id().value());
        int batch = 3;
        long bound = ahead / batch + 1;
        long pages = 0;
        while (!"OFFERED".equals(applicationStatus(approved.id())) && pages <= bound) {
            sweep(batch, HOUR);
            pages++;
        }
        assertThat(applicationStatus(approved.id())).as("offered within ceil((ahead + 1) / batch) pages")
                .isEqualTo("OFFERED");
        assertThat(pages).isLessThanOrEqualTo(bound);
        assertThat(permitDue(failing)).as("the failed step's permit was re-stamped by its claim, not held").isFalse();
        assertThat(waiting).allSatisfy(application -> assertThat(permitDue(application)).isFalse());
        assertThat(applicationStatus(failing.id())).isEqualTo("AWAITING_DECISION");
        CREDIT.decide(failing.decisionRequest(), new LoanCreditDecisions.CreditOutcome.Closed(
                LoanCreditDecisions.CreditOutcome.Closure.EXPIRED), null);
        assertThat(sweepUntilClaimed(failing, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.CLOSED_UNDECIDED);
    }

    // ------------------------------------------------------------------ the ranks, for every writer

    @Test
    @DisplayName("the offering instant is the database's for every writer (P11-TSK-036, lending V028): a raw offer naming"
            + " one in 2999 or in 2001 is born at the insert's own clock - GREATEST(statement_timestamp(),"
            + " transaction_timestamp()) - never at the writer's value; the expiry and the permit from it")
    void theOfferingInstantIsTheDatabasesForEveryWriter() throws Exception {
        activate(LOAN, "0.0815");
        Application application = application(LOAN, 10);
        UUID version = activeVersion(LOAN);
        for (String named : List.of("2999-01-01T00:00:00Z", "2001-01-01T00:00:00Z")) {
            try (Connection owner = DatabaseRoles.migrator()) {
                owner.setAutoCommit(false);
                try (PreparedStatement insert = owner.prepareStatement(
                        "INSERT INTO lending.loan_offer (id, application_id, party_id, product, status, decision_id,"
                                + " terms_version_id, offered_at, decision_valid_until, expires_at, next_step_at,"
                                + " correlation_id) VALUES (gen_random_uuid(), ?, ?, ?, 'OFFERED', gen_random_uuid(), ?,"
                                + " ?::timestamptz, statement_timestamp() + interval '30 days', ?::timestamptz,"
                                + " ?::timestamptz, 'raw') RETURNING (offered_at = GREATEST(statement_timestamp(),"
                                + " transaction_timestamp()) AND expires_at = LEAST(decision_valid_until, offered_at"
                                + " + interval '14 days') AND next_step_at = expires_at)::text")) {
                    insert.setObject(1, application.id().value());
                    insert.setObject(2, application.party());
                    insert.setString(3, application.product().name());
                    insert.setObject(4, version);
                    insert.setString(5, named);
                    insert.setString(6, named);
                    insert.setString(7, named);
                    try (ResultSet row = insert.executeQuery()) {
                        row.next();
                        assertThat(row.getString(1)).as("a writer naming %s", named).isEqualTo("true");
                    }
                } finally {
                    owner.rollback(); // an offer commits only beside its application's edge
                }
            }
        }
    }

    /**
     * The offer here is OFFERED: an ACCEPTED edge without its loan and a moved expiry are refused. Nothing out of a
     * terminal offer - EXPIRED, DECLINED, ACCEPTED, for both roles - is {@code
     * LoanAcceptanceDatabaseTest#nothingLeavesATerminalOfferForAnyRole}, where real terminal offers are made.
     */
    @Test
    @DisplayName("raw SQL cannot skip an edge for any role: no ACCEPTED here without its loan, the expiry never moved, an"
            + " application never OFFERED without its offer, an offer never committed without its application's edge,"
            + " the terms immutable and their hash the bytes', nothing deleted")
    void rawSqlCannotSkipAnEdgeForAnyRole() throws Exception {
        activate(LINE, "0.1614");
        Application application = application(LINE, 20);
        approve(application, 2_500_00, Optional.empty(), Duration.ofDays(30));
        assertThat(sweepUntilClaimed(application, HOUR)).isEqualTo(LoanOrigination.Outcome.Kind.OFFERED);
        UUID offer = offerOf(application).value();
        Application bare = application(LINE, 20);
        for (Connection writer : List.of(DatabaseRoles.migrator(), DatabaseRoles.application())) {
            try (writer) {
                refused(writer, "UPDATE lending.loan_offer SET status = 'ACCEPTED' WHERE id = '" + offer + "'", "P0001");
                refused(writer, "UPDATE lending.loan_offer SET expires_at = expires_at + interval '1 day' WHERE id = '"
                        + offer + "'", writer.getMetaData().getUserName().contains("migrator") ? "P0001" : "42501");
                refused(writer, "DELETE FROM lending.loan_offer WHERE id = '" + offer + "'",
                        writer.getMetaData().getUserName().contains("migrator") ? "P0001" : "42501");
                refused(writer, "UPDATE lending.loan_offer_terms SET amount_minor = 1 WHERE offer_id = '" + offer + "'",
                        writer.getMetaData().getUserName().contains("migrator") ? "P0001" : "42501");
                refused(writer, "UPDATE lending.loan_application SET status = 'OFFERED' WHERE id = '"
                        + bare.id().value() + "'", "P0001");
                refused(writer, "UPDATE lending.loan_application SET next_step_at = now() WHERE id = '"
                        + application.id().value() + "'", "P0001");
            }
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            refused(owner, "UPDATE lending.loan_offer_event SET reason = 'FORGED' WHERE offer_id = '" + offer + "'",
                    "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_offer SET closed_at = now() WHERE id = '" + offer + "'", "P0001");
            owner.rollback();
            refused(owner, "UPDATE lending.loan_application SET closed_at = now() WHERE id = '" + bare.id().value() + "'",
                    "P0001");
            owner.rollback();
            refused(owner, "TRUNCATE lending.loan_offer_terms, lending.loan_offer_event, lending.loan_offer CASCADE",
                    "P0001");
            owner.rollback();
            rawOffer(owner, bare, activeVersion(LINE));
            assertThatThrownBy(owner::commit).as("an offer commits only beside its application's OFFERED edge")
                    .isInstanceOf(SQLException.class).hasMessageContaining("OFFERED edge");
            owner.rollback();
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            // The CHECK alone: the born-once trigger admits a row for this OFFERED offer, and the CHECK is judged before
            // the primary key's second entry.
            refused(owner, "INSERT INTO lending.loan_offer_terms SELECT offer_id, product, currency, scale,"
                    + " amount_minor, term_months, nominal_annual_rate, repayment_day, illustrative_start, first_due_date,"
                    + " instalment_minor, final_instalment_minor, total_interest_minor, total_payable_minor,"
                    + " minimum_payment_floor_minor, minimum_payment_ratio_bps, payment_due_days,"
                    + " schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                    + " statement_engine_version, agreement_template_id, agreement_template_version, canonical_format,"
                    + " canonical_terms, sha256('forged'::bytea), document_sha256 FROM lending.loan_offer_terms"
                    + " WHERE offer_id = '" + offer + "'", "23514");
        }
        assertThat(offerStatus(application)).isEqualTo("OFFERED");
        assertThat(applicationStatus(bare.id())).isEqualTo("AWAITING_DECISION");
    }

    // ------------------------------------------------------------------ plumbing

    /** An application as the submission births it - SUBMITTED then AWAITING_DECISION, on the product's ACTIVE terms. */
    private record Application(LoanApplicationId id, UUID party, LoanProduct product, UUID decisionRequest) {}

    private static Application application(LoanProduct product, int day) {
        Application application = new Application(LoanApplicationId.next(IDS), UUID.randomUUID(), product,
                UUID.randomUUID());
        inOneTransaction(uow -> {
            LoanTermsStore.LoanTermsVersion version = ADMINISTRATION.pinActive(uow, product);
            Actor customer = customer(application);
            CorrelationId correlation = correlation();
            APPLICATIONS.insert(uow, new LoanApplicationStore.NewApplication(application.id(), application.party(),
                    product, application.decisionRequest(), version.row().id(), day, RepaymentMethod.WALLET,
                    PayoutPreference.WALLET, customer, correlation));
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application.id(),
                    Optional.empty(), LoanApplicationStatus.SUBMITTED, "APPLICATION_SUBMITTED", customer, correlation));
            APPLICATIONS.transition(uow, application.id(), LoanApplicationStatus.SUBMITTED,
                    LoanApplicationStatus.AWAITING_DECISION);
            APPLICATIONS.appendEvent(uow, new LoanApplicationStore.HistoryRow(IDS.next(), application.id(),
                    Optional.of(LoanApplicationStatus.SUBMITTED), LoanApplicationStatus.AWAITING_DECISION,
                    "CREDIT_REQUEST_OPENED", customer, correlation));
            return null;
        });
        return application;
    }

    /**
     * Credit approves {@code application} for {@code minor} (and a term), valid {@code validity} from now on the database's
     * clock - the clock the origination judges the lapse on (P11-TSK-036), never this JVM's.
     */
    private static UUID approve(Application application, long minor, Optional<Integer> term, Duration validity) {
        return CREDIT.approve(application, Money.ofMinorUnits(minor, LoanTermsFixtures.EUR), term,
                DisbursementWorld.databaseNow().plus(validity));
    }

    private static boolean hintDecision(UUID decision) {
        Boolean hinted = inOneTransaction(uow -> ORIGINATION.hintDecision(uow, decision));
        return hinted;
    }

    private static boolean hintRequest(UUID request) {
        Boolean hinted = inOneTransaction(uow -> ORIGINATION.hintRequest(uow, request));
        return hinted;
    }

    private static Optional<LoanProduct> expire(LoanOfferId offer) {
        Optional<LoanProduct> expired = inOneTransaction(uow -> ORIGINATION.expire(uow, offer, SYSTEM));
        return expired;
    }

    private static Optional<LoanOffers.Read> read(LoanOfferId offer, UUID party) {
        Optional<LoanOffers.Read> read = inOneTransaction(uow -> OFFERS.read(uow, offer, party));
        return read;
    }

    private static LoanOrigination.Outcome originate(LoanApplicationId id) {
        return inOneTransaction(uow -> ORIGINATION.originate(uow, id, SYSTEM));
    }

    /** One sweep, the schedule's shape: the claim committed, then each claimed application's step in its own. */
    private static List<LoanOrigination.Outcome> sweep(int batch, Duration permit) {
        List<LoanApplicationId> claimed = inOneTransaction(uow -> ORIGINATION.claimApplications(uow, batch, permit));
        List<LoanOrigination.Outcome> outcomes = new ArrayList<>();
        for (LoanApplicationId id : claimed) {
            try {
                outcomes.add(originate(id));
            } catch (RuntimeException failure) {
                // The schedule contains a failed step: its permit lapses, the page moves on.
            }
        }
        return outcomes;
    }

    /**
     * Sweeps until {@code application}'s own step ran - other cases' leftovers in the shared database claimed beside it -
     * and answers what that step did. Made due first, as a hint would.
     */
    private static LoanOrigination.Outcome.Kind sweepUntilClaimed(Application application, Duration permit) {
        hintRequest(application.decisionRequest());
        for (int attempt = 0; attempt < 200; attempt++) {
            List<LoanApplicationId> claimed =
                    inOneTransaction(uow -> ORIGINATION.claimApplications(uow, 50, permit));
            LoanOrigination.Outcome.Kind mine = null;
            for (LoanApplicationId id : claimed) {
                try {
                    LoanOrigination.Outcome step = originate(id);
                    if (id.equals(application.id())) {
                        mine = step.kind();
                    }
                } catch (RuntimeException failure) {
                    if (id.equals(application.id())) {
                        throw failure;
                    }
                }
            }
            if (mine != null) {
                return mine;
            }
            if (!"AWAITING_DECISION".equals(applicationStatus(application.id()))) {
                return LoanOrigination.Outcome.Kind.NOTHING;
            }
        }
        throw new AssertionError("the application was never claimed");
    }

    private static boolean permitDue(Application application) {
        return "t".equals(scalar("SELECT next_step_at <= statement_timestamp() FROM lending.loan_application WHERE id = ?",
                application.id().value()));
    }

    private static LoanOfferId offerOf(Application application) {
        return LoanOfferId.of(UUID.fromString(scalar("SELECT id::text FROM lending.loan_offer WHERE application_id = ?",
                application.id().value())));
    }

    private static String offerStatus(Application application) {
        return scalar("SELECT status FROM lending.loan_offer WHERE application_id = ?", application.id().value());
    }

    private static String applicationStatus(LoanApplicationId id) {
        return scalar("SELECT status FROM lending.loan_application WHERE id = ?", id.value());
    }

    private static String lastReason(Application application) {
        return scalar("SELECT reason FROM lending.loan_application_event WHERE application_id = ?"
                + " AND from_status = 'AWAITING_DECISION'", application.id().value());
    }

    private static UUID activeVersion(LoanProduct product) {
        return UUID.fromString(scalar("SELECT id::text FROM lending.loan_terms_version WHERE product = ?"
                + " AND status = 'ACTIVE'", product.name()));
    }

    /** A raw offer insert by {@code connection}, born at this statement's instant, on {@code version}. */
    private static void rawOffer(Connection connection, Application application, UUID version) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO lending.loan_offer (id, application_id, party_id, product, status, decision_id,"
                        + " terms_version_id, offered_at, decision_valid_until, expires_at, next_step_at, correlation_id)"
                        + " VALUES (gen_random_uuid(), ?, ?, ?, 'OFFERED', gen_random_uuid(), ?, statement_timestamp(),"
                        + " statement_timestamp() + interval '30 days', statement_timestamp() + interval '1 day',"
                        + " statement_timestamp(), 'raw')")) {
            insert.setObject(1, application.id().value());
            insert.setObject(2, application.party());
            insert.setString(3, application.product().name());
            insert.setObject(4, version);
            insert.executeUpdate();
        }
    }

    private static LoanTermsVersionId activate(LoanProduct product, String rate) {
        return activate(product, LoanTermsFixtures.of(product, rate).build());
    }

    /** A fresh version of {@code terms}, proposed by one person and activated by another. */
    private static LoanTermsVersionId activate(LoanProduct product, LoanTerms terms) {
        LoanTermsAdministration.Proposed proposed = propose(product, terms);
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "a case's activation",
                correlation()));
        return proposed.id();
    }

    private static LoanTermsAdministration.Proposed propose(LoanProduct product, String rate) {
        return propose(product, LoanTermsFixtures.of(product, rate).build());
    }

    private static LoanTermsAdministration.Proposed propose(LoanProduct product, LoanTerms terms) {
        DisbursementWorld.clearPendingTerms(product);
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, terms, "a case's proposal", employee(), correlation()));
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static Actor customer(Application application) {
        return new Actor(application.party().toString(), ActorType.CUSTOMER);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
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

    private static long count(String sql, Object... parameters) {
        return Long.parseLong(scalar(sql, parameters));
    }

    private static String scalar(String sql, Object... parameters) {
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

    /** Waits until the database itself says {@code booleanQuery} - never the JVM's clock. */
    private static void awaitDatabase(String booleanQuery, Object... parameters) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if ("t".equals(scalar(booleanQuery, parameters))) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("the database never said: " + booleanQuery);
    }

    /** Waits until a session is blocked by {@code blocker}, and answers the statement it waits in. */
    private static String awaitAStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))"
                            // The activity row is a snapshot and the blocking pids are live: only a row that itself shows
                            // the session active and waiting on a lock carries the statement that waits (P11-TSK-035's gate).
                            + " AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no activation ever waited on the offer's pin");
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }

    /** Racer {@code i} of {@code racers} runs {@code work.apply(i)}, all released at once. */
    private static <T> List<T> race(int racers, IndexedWork<T> work) throws Exception {
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
    private interface IndexedWork<T> {
        T apply(int index) throws Exception;
    }

    /** Credit's request as each case says it stands - open unless decided or closed here. */
    private static final class ScriptedCredit implements LoanCreditDecisions {

        private final Map<UUID, CreditOutcome> answers = new ConcurrentHashMap<>();
        private final Map<UUID, UUID> requestOfDecision = new ConcurrentHashMap<>();
        private final Map<UUID, Boolean> failing = new ConcurrentHashMap<>();

        UUID approve(Application application, Money amount, Optional<Integer> term, Instant validUntil) {
            UUID decision = UUID.randomUUID();
            decide(application.decisionRequest(), new CreditOutcome.Approved(decision, application.party(),
                    application.product(), amount, term, validUntil), decision);
            return decision;
        }

        void decide(UUID request, CreditOutcome outcome, UUID decision) {
            failing.remove(request);
            answers.put(request, outcome);
            if (decision != null) {
                requestOfDecision.put(decision, request);
            }
        }

        void fail(UUID request) {
            failing.put(request, true);
        }

        @Override
        public UUID open(Connection unitOfWork, UUID party, CreditTerms terms, Actor actor, CorrelationId correlation) {
            throw new UnsupportedOperationException("the offer opens no request");
        }

        @Override
        public void cancel(Connection unitOfWork, UUID decisionRequest, UUID party, Actor actor,
                CorrelationId correlation) {
            throw new UnsupportedOperationException("the offer cancels no request");
        }

        @Override
        public List<String> reasonTexts(Connection unitOfWork, UUID decisionRequest) {
            return List.of();
        }

        @Override
        public CreditOutcome decisionFor(Connection unitOfWork, UUID decisionRequest, UUID party) {
            if (failing.containsKey(decisionRequest)) {
                throw new IllegalStateException("credit's read failed for this request");
            }
            return answers.getOrDefault(decisionRequest, new CreditOutcome.Pending());
        }

        @Override
        public Optional<UUID> requestOf(Connection unitOfWork, UUID decision) {
            return Optional.ofNullable(requestOfDecision.get(decision));
        }

        @Override
        public boolean lockExposure(Connection unitOfWork, UUID party) {
            throw new UnsupportedOperationException("the offer takes no exposure lock");
        }

        @Override
        public Consumption consume(Connection unitOfWork, UUID decision, LoanAgreementId agreement) {
            throw new UnsupportedOperationException("the offer consumes no decision");
        }
    }
}
