package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.credit.ExposureCensus;
import com.finapp.app.credit.ExposureCensusMetrics;
import com.finapp.app.credit.BnplCreditExposure;
import com.finapp.app.database.DateShifted;
import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CanonicalSnapshot;
import com.finapp.credit.CreditAttribute;
import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionMaking;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionRequestId;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Platform credit exposure version 2 against the real paths (`P11-TSK-013`; PHASE_11_PLAN.md section 12.10, ADR-0091
 * points 5 to 9; {@code INV-CRD-09}, {@code INV-LND-06}, {@code INV-CRD-01}, {@code INV-BAL-05}): the application's
 * wired {@code LendingCreditExposure} - the figure credit's own deciding transaction reads - over loans and lines
 * *(re-pointed by `P12-TSK-012` at the wired version 3, {@code BnplCreditExposure}: version 2's table plus the BNPL arm,
 * which these loan-and-line cases hold at zero - so its ten cases stand unchanged and now prove version 3's loan arm)*
 * accepted end to end through {@code LoanAcceptances} and {@code LoanAccountDesk}, credit's real decision, consumption
 * and profile lock beneath.
 *
 * <p><strong>The disbursement is the real one</strong> (`P11-TSK-014`): the loan's ACTIVE edge and its principal posting
 * through {@link LoanDisbursements} - one transaction under L0 - in {@code #aDisbursementDuringTheReadIsNeverMissed},
 * {@code #principalRepaidStopsCounting}, {@code #interestAndFeesAreNotExposure} and the census case.
 * <strong>The line's draw is the real one</strong> (`P11-TSK-021`): {@code #anOpenLinesLimitIsCounted} and
 * {@code #aClosingLineCountsItsDrawnPrincipal} draw through the door. <strong>The other outstanding arms stay owner-role
 * fixtures</strong>: no path the platform has yet bills, repays, closes or cancels a line, or draws one past its limit
 * (`-018`, `-020`, `-023`); this suite plants those facts directly - a status moved by the
 * bootstrap superuser with the database's triggers suspended for that one transaction
 * ({@code session_replication_role = replica}), and principal, interest and fee lines posted through the ledger's own
 * {@link PostingService} against {@code CASH_AT_BANK}. Those figures are re-asserted through the real paths by `-018`,
 * `-020`, `-023` and the storm.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings credit's seeded policies into
 * force, activates both products' terms and plants EUR lending capital, as {@code LoanAccountDatabaseTest} does - and
 * then breaks the exposure census on purpose.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("platform credit exposure v2 against the real paths (P11-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LendingCreditExposureDatabaseTest {

    private static final String APPLICATIONS = "/v1/me/loan-applications";
    private static final String OFFERS = "/v1/me/loan-offers";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private BnplCreditExposure exposure;
    @Autowired private ExposureCensusMetrics census;
    @Autowired private DecisionMaking deciding;
    @Autowired private DecisionReplayer replayer;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator ids;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanDisbursements disbursements;

    private CreditFixtures customers;
    private LoanOriginationSchedule sweep;

    @BeforeEach
    void creditTermsAndCapitalInForce() {
        customers = new CreditFixtures(port);
        CreditFixtures.seedsInForce();
        for (LoanProduct product : LoanProduct.values()) {
            lendingTransactions.inTransaction(uow -> {
                if (termsStore.active(uow, product).isEmpty()) {
                    var proposed = termsAdministration.propose(uow, LoanApplicationDatabaseTest.terms(product),
                            "the suite's terms", employee(), CorrelationId.generate(ids));
                    termsAdministration.approve(uow, proposed.id(), employee(), "reviewed", CorrelationId.generate(ids));
                }
                return null;
            });
        }
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1));
        Money free = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, EUR)).headroom();
        if (free.minorUnits() < 60_000_00) {
            post(List.of(line(cash(), Direction.DEBIT, 100_000_00),
                    line(lendingTransactions.inTransaction(uow -> headroom.capitalAccount(uow, EUR).id()), Direction.CREDIT,
                            100_000_00)));
        }
    }

    // ================================================================== the race on L0

    @Test
    @DisplayName("an acceptance and a credit decision for the party serialise on its profile lock, in both orders: the"
            + " decision that held L0 first read the approval reserved and the acceptance waited on credit_profile; the"
            + " decision that waited behind the acceptance read the commitment - never neither, never both")
    void acceptanceAndADecisionForThePartySerialise() throws Exception {
        for (boolean decisionFirst : List.of(true, false)) {
            CreditFixtures.Customer customer = customers.consentingCustomer();
            String offer = offered(customer, loan("8000.00"));
            String hash = field(get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
            UUID second = CreditFixtures.requestFor(customer.party(), CreditProduct.CREDIT_LINE, eur(1_000_00));
            assertThat(CreditFixtures.stepUntil(second, "EVALUATED")).isEqualTo("EVALUATED");
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try (Connection holder = DatabaseRoles.application()) {
                holder.setAutoCommit(false);
                Future<HttpResponse<String>> accepting;
                Future<?> decided;
                if (decisionFirst) {
                    // The decision takes L0, then waits on its request row - held here - holding L0.
                    lock(holder, "SELECT 1 FROM credit.decision_request WHERE id = ? FOR UPDATE", second);
                    decided = pool.submit(() -> decide(second));
                    Blocked decider = awaitBlockedBy(pid(holder));
                    assertThat(decider.query()).as("the decision holds L0 and waits on its request").contains("decision_request");
                    accepting = pool.submit(() -> accept(customer.token(), offer, hash));
                    assertThat(awaitBlockedBy(decider.pid()).query())
                            .as("the acceptance waits on credit's profile row behind the decision").contains("credit_profile");
                } else {
                    // The acceptance takes L0, then waits on the offer - held here - holding L0.
                    lock(holder, "SELECT 1 FROM lending.loan_offer WHERE id = ?::uuid FOR UPDATE", offer);
                    accepting = pool.submit(() -> accept(customer.token(), offer, hash));
                    Blocked acceptance = awaitBlockedBy(pid(holder));
                    assertThat(acceptance.query()).as("the acceptance holds L0 and waits on the offer").contains("loan_offer");
                    decided = pool.submit(() -> decide(second));
                    assertThat(awaitBlockedBy(acceptance.pid()).query())
                            .as("the decision waits on credit's profile row behind the acceptance").contains("credit_profile");
                }
                holder.rollback();
                HttpResponse<String> accepted = accepting.get(2, TimeUnit.MINUTES);
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
                decided.get(2, TimeUnit.MINUTES);
            } finally {
                pool.shutdownNow();
            }
            assertThat(LendingScalar.of("SELECT status FROM credit.decision_request WHERE id = ?", second))
                    .as("the second request was decided (or referred) on that basis").isIn("DECIDED", "IN_REVIEW");
            Money reserved = moneyOf(latest(second, CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE));
            CreditAttribute platform = latest(second, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
            if (decisionFirst) {
                assertThat(reserved).as("the approval still reserved when the decision held L0").isEqualTo(eur(8_000_00));
                assertThat(moneyOf(platform)).isEqualTo(eur(0));
            } else {
                assertThat(reserved).as("consumed before the decision read it").isEqualTo(eur(0));
                assertThat(moneyOf(platform)).as("the commitment, read by version 2").isEqualTo(eur(8_000_00));
                assertThat(platform.provenance()).isEqualTo(new AttributeProvenance.Port("platform-exposure", BnplCreditExposure.VERSION));
            }
            assertThat(reserved.plus(moneyOf(platform))).as("exactly once - never neither, never both").isEqualTo(eur(8_000_00));
            assertThat(v2(customer.party())).as("after both: the commitment").isEqualTo(eur(8_000_00));
        }
    }

    // ================================================================== the one statement

    @Test
    @DisplayName("a disbursement committing during the read is never missed and never counted twice: the read's one"
            + " statement sees the loan pending with no principal line, the next sees it active with its principal")
    void aDisbursementDuringTheReadIsNeverMissed() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID loan = acceptedLoan(customer, loan("7500.00"));
        AtomicBoolean disbursed = new AtomicBoolean();
        try (Connection reader = DatabaseRoles.application()) {
            reader.setAutoCommit(false);
            // The disbursement commits on another connection the moment the read's first statement has executed - so a
            // read split into two statements would read the committed arm before it and the outstanding arm after.
            Connection interleaved = interleaving(reader, () -> {
                disburse(loan);
                disbursed.set(true);
            });
            assertThat(exposure.outstandingFor(interleaved, customer.party(), EUR))
                    .as("one snapshot: pending, the principal committed once").isEqualTo(eur(7_500_00));
            assertThat(disbursed).as("the disbursement committed during the read").isTrue();
            assertThat(exposure.outstandingFor(reader, customer.party(), EUR))
                    .as("the next statement: active, the principal outstanding once").isEqualTo(eur(7_500_00));
            reader.rollback();
        }
        assertThat(loanStatusOf(loan)).isEqualTo("ACTIVE");
    }

    // ================================================================== the arms

    @Test
    @DisplayName("an open line's limit is counted, drawn or not - and the wired freezer hands it to credit's deciding"
            + " transaction: a second product's decision for the party reads the limit under version 2")
    void anOpenLinesLimitIsCounted() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID line = acceptedLoan(customer, line("2500.00"));
        assertThat(v2(customer.party())).as("undrawn: the whole limit").isEqualTo(eur(2_500_00));
        // The real draw (P11-TSK-021): rule 1 to the borrower's wallet through the door - exposure does not move.
        draw(customer, line, "1000.00");
        assertThat(v2(customer.party())).as("drawn within it: still the limit").isEqualTo(eur(2_500_00));
        draw(customer, line, "1500.00");
        assertThat(v2(customer.party())).as("drawn to the limit: still the limit").isEqualTo(eur(2_500_00));

        UUID second = CreditFixtures.requestFor(customer.party(), CreditProduct.PERSONAL_LOAN, eur(1_000_00));
        assertThat(CreditFixtures.stepUntil(second, "EVALUATED")).isEqualTo("EVALUATED");
        decide(second);
        CreditAttribute platform = latest(second, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
        assertThat(moneyOf(platform)).as("two products for one party - the open line's limit counted")
                .isEqualTo(eur(2_500_00));
        assertThat(platform.provenance()).isEqualTo(new AttributeProvenance.Port("platform-exposure", BnplCreditExposure.VERSION));
    }

    @Test
    @DisplayName("an open line drawn beyond its limit (a paydown reversed past a re-draw) counts its drawn principal")
    void anOpenLineDrawnBeyondItsLimitCountsTheDrawnPrincipal() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID line = acceptedLoan(customer, line("2500.00"));
        post(List.of(line(account(line, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, 2_000_00),
                line(account(line, AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.DEBIT, 1_000_00),
                line(cash(), Direction.CREDIT, 3_000_00)));
        assertThat(v2(customer.party())).as("principal and principal due, beyond the limit").isEqualTo(eur(3_000_00));
    }

    @Test
    @DisplayName("a closing line counts its drawn principal, no longer its limit - re-asserted through the real closure"
            + " door (P11-TSK-023): the request moves the line CLOSING without the profile lock, the figure falls, the census"
            + " holds")
    void aClosingLineCountsItsDrawnPrincipal() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID line = acceptedLoan(customer, line("2500.00"));
        draw(customer, line, "700.00");
        assertThat(v2(customer.party())).isEqualTo(eur(2_500_00));
        HttpResponse<String> closing = post("/v1/me/loans/" + line + "/closure", "", customer.token());
        assertThat(closing.statusCode()).as(closing.body()).isEqualTo(201);
        assertThat(closing.body()).contains("\"outcome\":\"CLOSING\"");
        assertThat(v2(customer.party())).as("no draw is possible: the promise is what is drawn").isEqualTo(eur(700_00));
        assertThat(census.readNow().failingParties()).doesNotContain(customer.party());
    }

    @Test
    @DisplayName("a cancelled loan counts nothing: its commitment released, and no line on it counted")
    void aCancelledLoanCountsNothing() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID loan = acceptedLoan(customer, loan("6000.00"));
        assertThat(v2(customer.party())).as("committed while pending").isEqualTo(eur(6_000_00));
        asOwner(owner -> update(owner, "UPDATE lending.loan SET status = 'CANCELLED', closed_at = statement_timestamp(),"
                + " cancellation_reason = 'DISBURSEMENT_FAILED' WHERE id = ?", loan));
        assertThat(v2(customer.party())).isEqualTo(eur(0));
        post(List.of(line(account(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, 1_00),
                line(cash(), Direction.CREDIT, 1_00)));
        assertThat(v2(customer.party())).as("a cancelled loan's lines - none should exist - are not exposure").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("principal repaid stops counting; principal billed (moved to principal due) still counts")
    void principalRepaidStopsCounting() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID loan = acceptedLoan(customer, loan("5000.00"));
        disburse(loan);
        assertThat(v2(customer.party())).isEqualTo(eur(5_000_00));
        post(List.of(line(account(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, 1_000_00),
                line(account(loan, AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.DEBIT, 1_000_00)));
        assertThat(v2(customer.party())).as("billed: still owed").isEqualTo(eur(5_000_00));
        post(List.of(line(cash(), Direction.DEBIT, 1_000_00),
                line(account(loan, AccountPurpose.LOAN_PRINCIPAL_DUE), Direction.CREDIT, 1_000_00)));
        assertThat(v2(customer.party())).isEqualTo(eur(4_000_00));
        post(List.of(line(cash(), Direction.DEBIT, 4_000_00),
                line(account(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.CREDIT, 4_000_00)));
        assertThat(v2(customer.party())).as("repaid in full").isEqualTo(eur(0));
    }

    @Test
    @DisplayName("interest accrued and billed, fees and a credit balance are not exposure - principal only (A3)")
    void interestAndFeesAreNotExposure() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        UUID loan = acceptedLoan(customer, loan("4000.00"));
        disburse(loan);
        post(List.of(line(account(loan, AccountPurpose.LOAN_INTEREST_ACCRUED), Direction.DEBIT, 50_00),
                line(account(loan, AccountPurpose.LOAN_INTEREST_DUE), Direction.DEBIT, 30_00),
                line(account(loan, AccountPurpose.LOAN_FEES_DUE), Direction.DEBIT, 25_00),
                line(cash(), Direction.CREDIT, 105_00)));
        post(List.of(line(cash(), Direction.DEBIT, 10_00),
                line(account(loan, AccountPurpose.LOAN_CREDIT_BALANCE), Direction.CREDIT, 10_00)));
        assertThat(v2(customer.party())).isEqualTo(eur(4_000_00));
    }

    // ================================================================== replay and the census

    @Test
    @DisplayName("a decision frozen under version 1 replays IDENTICAL after its own loan is accepted: its snapshot still"
            + " carries version 1's zero while version 2 now reads the commitment (INV-CRD-01)")
    void replayOfV1DecisionsStaysIdentical() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, loan("7000.00"));
        UUID decision = UUID.fromString(LendingScalar.of("SELECT decision_id FROM lending.loan_offer WHERE id = ?::uuid", offer));
        CreditAttribute frozen = attributeOfDecision(decision, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT);
        assertThat(frozen.provenance()).isEqualTo(new AttributeProvenance.Port("platform-exposure", 1));
        assertThat(moneyOf(frozen)).isEqualTo(eur(0));
        String hash = field(get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        assertThat(accept(customer.token(), offer, hash).statusCode()).isEqualTo(201);
        assertThat(v2(customer.party())).as("version 2 now reads the commitment").isEqualTo(eur(7_000_00));
        DecisionReplayer.Replay replay = lendingTransactions.inTransaction(uow ->
                replayer.replay(uow, CreditDecisionId.of(decision)).orElseThrow());
        assertThat(replay.verdict()).as(replay.divergences().toString()).isEqualTo(DecisionReplayer.Verdict.IDENTICAL);
        assertThat(attributeOfDecision(decision, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT)).isEqualTo(frozen);
    }

    @Test
    @DisplayName("the census holds for a party within its limit and finds a party whose principal exceeds the limit its"
            + " policy declared - FAILS counted on finapp.credit.exposure.census, no figure or id in a tag")
    void theCensusFindsAnOverLimitParty() throws Exception {
        CreditFixtures.Customer within = customers.consentingCustomer();
        acceptedLoan(within, loan("3000.00"));
        CreditFixtures.Customer beyond = customers.consentingCustomer();
        UUID loan = acceptedLoan(beyond, loan("3500.00"));
        ExposureCensus.Report before = census.readNow();
        assertThat(before.failingParties()).doesNotContain(within.party(), beyond.party());
        long limit = Long.parseLong(LendingScalar.of("SELECT p.maximum_exposure_minor FROM lending.loan l"
                + " JOIN credit.credit_decision d ON d.id = l.decision_id"
                + " JOIN credit.credit_policy_version p ON p.id = d.policy_version_id WHERE l.id = ?::uuid", loan.toString()));
        // Disbursed, and then principal no decision ever approved - a fixture breach the census must name.
        disburse(loan);
        post(List.of(line(account(loan, AccountPurpose.LOAN_PRINCIPAL), Direction.DEBIT, limit - 3_500_00 + 1),
                line(cash(), Direction.CREDIT, limit - 3_500_00 + 1)));
        ExposureCensus.Report after = census.readNow();
        assertThat(after.failingParties()).contains(beyond.party()).doesNotContain(within.party());
        assertThat(after.failing()).isPositive();
        assertThat(meterRegistry.find(ExposureCensusMetrics.CENSUS).tag("verdict", "FAILS").gauge().value())
                .isEqualTo(after.failing());
        assertThat(meterRegistry.find(ExposureCensusMetrics.CENSUS).tag("verdict", "HOLDS").gauge().value())
                .isEqualTo(after.holding());
        assertThat(meterRegistry.find(ExposureCensusMetrics.CENSUS).gauges()).allSatisfy(gauge ->
                assertThat(gauge.getId().getTags()).allSatisfy(tag -> assertThat(tag.getKey()).isEqualTo("verdict")));
    }

    // ------------------------------------------------------------------ plumbing

    private Money v2(UUID party) {
        return lendingTransactions.inTransaction(uow -> exposure.outstandingFor(uow, party, EUR));
    }

    private void decide(UUID request) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            deciding.decide(DecisionRequestId.of(request), CorrelationId.generate(ids));
        }
    }

    /** {@code body} applied for, approved by credit (version 1's world), offered by the sweep; the offer's id. */
    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = post(APPLICATIONS, body, customer.token());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow, request));
        sweep.sweepOnce();
        return field(get(APPLICATIONS + "/" + field(submitted.body(), "applicationId"), customer.token()).body(), "offerId");
    }

    /** {@code body} offered and accepted end to end; the loan account's id. */
    private UUID acceptedLoan(CreditFixtures.Customer customer, String body) throws Exception {
        String offer = offered(customer, body);
        String hash = field(get(OFFERS + "/" + offer, customer.token()).body(), "termsSha256");
        HttpResponse<String> accepted = accept(customer.token(), offer, hash);
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return UUID.fromString(field(accepted.body(), "loanId"));
    }

    private HttpResponse<String> accept(String token, String offer, String hash) throws Exception {
        return post(OFFERS + "/" + offer + "/acceptance", "{\"termsSha256\":\"" + hash + "\"}", token);
    }

    /**
     * The real disbursement (`P11-TSK-014`, T-dis): the loan's ACTIVE edge and posting rule 1 - DR LOAN_PRINCIPAL / CR the
     * borrower's wallet - committed in ONE transaction under L0, through the wired {@link LoanDisbursements} and its
     * wallet port over accounts. Until `-014` this suite planted the edge and the posting as an owner-role fixture.
     */
    private void disburse(UUID loan) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            LoanDisbursements.Outcome outcome = lendingTransactions.inTransaction(uow ->
                    disbursements.disburse(uow, LoanId.of(loan), SecurityContext.require()));
            assertThat(outcome.kind()).as("disbursed").isEqualTo(LoanDisbursements.Outcome.Kind.POSTED);
        }
    }

    /** The real draw (`P11-TSK-021`, T-drw) through the door, from the customer's MULTI_FACTOR session. */
    private void draw(CreditFixtures.Customer customer, UUID line, String amount) throws Exception {
        HttpResponse<String> drawn = post("/v1/me/loans/" + line + "/draws",
                "{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"}", customer.token());
        assertThat(drawn.statusCode()).as(drawn.body()).isEqualTo(201);
    }

    /** {@code work} as the bootstrap superuser, the database's triggers suspended for this one transaction. */
    private static void asOwner(SqlWork work) {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
            }
            work.run(owner);
            owner.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    private static void update(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setObject(1, id);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private void post(List<JournalLine> lines) {
        lendingTransactions.inTransaction(uow -> {
            post(uow, lines);
            return null;
        });
    }

    private void post(Connection uow, List<JournalLine> lines) {
        try (SecurityContext.Scope system = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(ids)).causing(CausationId.of("p11t13")))) {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            postingService.post(uow, new PostingCommand("p11t13:" + UUID.randomUUID(), today, today,
                    "the exposure suite's fixture", lines));
        }
    }

    private static JournalLine line(LedgerAccountId account, Direction direction, long minor) {
        return new JournalLine(account, direction, eur(minor));
    }

    private LedgerAccountId cash() {
        return lendingTransactions.inTransaction(uow ->
                ledgerAccountStore.findOperational(uow, AccountPurpose.CASH_AT_BANK, EUR).orElseThrow().id());
    }

    private static LedgerAccountId account(UUID loan, AccountPurpose purpose) {
        return LedgerAccountId.of(UUID.fromString(LendingScalar.of("SELECT id FROM ledger.ledger_account"
                + " WHERE owner_ref = ?::uuid AND purpose = ?", loan.toString(), purpose.name())));
    }

    private static String loanStatusOf(UUID loan) {
        return LendingScalar.of("SELECT status FROM lending.loan WHERE id = ?::uuid", loan.toString());
    }

    /** The request's latest snapshot's attribute - the basis it was decided (or referred) on. */
    private static CreditAttribute latest(UUID request, CreditAttributeCode code) {
        return CanonicalSnapshot.parse(LendingScalar.of("SELECT canonical FROM credit.decision_snapshot"
                + " WHERE decision_request_id = ?::uuid ORDER BY sequence DESC LIMIT 1", request.toString())).attribute(code);
    }

    private static CreditAttribute attributeOfDecision(UUID decision, CreditAttributeCode code) {
        return CanonicalSnapshot.parse(LendingScalar.of("SELECT s.canonical FROM credit.credit_decision d"
                + " JOIN credit.decision_snapshot s ON s.id = d.snapshot_id WHERE d.id = ?::uuid", decision.toString()))
                .attribute(code);
    }

    private static Money moneyOf(CreditAttribute attribute) {
        assertThat(attribute.value()).isInstanceOf(AttributeValue.MoneyValue.class);
        return ((AttributeValue.MoneyValue) attribute.value()).value();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    private static String loan(String amount) {
        return "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"termMonths\":24,"
                + "\"repaymentDay\":10,\"repaymentMethod\":\"WALLET\",\"payoutPreference\":\"WALLET\","
                + "\"declaredMonthlyIncome\":\"3200.00\",\"declaredMonthlyExpenditure\":\"1400.00\"}";
    }

    private static String line(String amount) {
        return "{\"product\":\"CREDIT_LINE\",\"amount\":\"" + amount + "\",\"currency\":\"EUR\",\"repaymentDay\":5,"
                + "\"repaymentMethod\":\"WALLET\"}";
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + bearer).GET().build());
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + bearer)
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    /**
     * {@code real}, with {@code between} run once, right after the first statement executed on it returns - the instant
     * a second statement of the same read would begin.
     */
    private static Connection interleaving(Connection real, Runnable between) {
        AtomicBoolean ran = new AtomicBoolean();
        ClassLoader loader = Connection.class.getClassLoader();
        return (Connection) Proxy.newProxyInstance(loader, new Class<?>[] {Connection.class}, (proxy, method, args) -> {
            Object result = invoke(method, real, args);
            if (result instanceof Statement statement) {
                Class<?> shape = result instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                return Proxy.newProxyInstance(loader, new Class<?>[] {shape}, (inner, call, values) -> {
                    Object answer = invoke(call, statement, values);
                    if (call.getName().startsWith("execute") && ran.compareAndSet(false, true)) {
                        between.run();
                    }
                    return answer;
                });
            }
            return result;
        });
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }

    private static void lock(Connection holder, String sql, Object id) throws SQLException {
        try (PreparedStatement lock = holder.prepareStatement(sql)) {
            lock.setObject(1, id);
            try (ResultSet row = lock.executeQuery()) {
                assertThat(row.next()).as("the row to hold exists").isTrue();
            }
        }
    }

    private static int pid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT pg_backend_pid()")) {
            row.next();
            return row.getInt(1);
        }
    }

    private record Blocked(int pid, String query) {}

    /** Waits until a session is blocked by {@code blocker}; its pid and the statement it waits in. */
    private static Blocked awaitBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT pid, query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))"
                            // Only a row that itself shows the session active and waiting on a lock carries the statement
                            // that waits (P11-TSK-035's gate; this observer lacked it until P11-DOC-001).
                            + " AND state = 'active' AND wait_event_type = 'Lock'")) {
                if (row.next()) {
                    return new Blocked(row.getInt(1), row.getString(2));
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("nothing ever waited behind session " + blocker);
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    /** The owner's scalar reads - the migrator, which reads every schema. */
    private static final class LendingScalar {
        static String of(String sql, Object... parameters) {
            try (Connection owner = DatabaseRoles.migrator(); PreparedStatement select = owner.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    select.setObject(i + 1, parameters[i]);
                }
                try (ResultSet row = select.executeQuery()) {
                    assertThat(row.next()).as("one row from: %s", sql).isTrue();
                    return row.getString(1);
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
