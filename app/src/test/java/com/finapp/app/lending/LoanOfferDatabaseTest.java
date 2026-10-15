package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.line;
import static com.finapp.app.lending.LoanApplicationClient.loan;
import static com.finapp.app.lending.LoanApplicationClient.scalar;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.lending.LoanOrigination;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsAdministration;
import com.finapp.lending.LoanTermsStore;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The offer end to end - credit's real decision, the adapter's authoritative read, the sweep and the customer's doors
 * (`P11-TSK-011`; {@code PHASE_11_PLAN.md} sections 7.1 T-off and 9, {@code LENDING_LIFECYCLES.md} sections 4.3 and
 * 4.4; {@code INV-EVT-04}, {@code INV-LIFE-01}): an application submitted over HTTP, decided by credit's own progress and
 * deciding transaction, hinted by credit's event, offered by the sweep with exactly the approved figures, read and
 * declined by its owner and by no one else; a decline and a lost standing closing the application; both versions of
 * credit's event handled alike and an unknown version refused. The races and the database's ranks are
 * {@code lending}'s {@code LoanOriginationDatabaseTest}'s.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}, X-TSK-016): the suite brings credit's seeded
 * policies and scorecard into force and activates both products' lending terms, as {@link LoanApplicationDatabaseTest}
 * does.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the offer end to end (P11-TSK-011)")
class LoanOfferDatabaseTest {

    private static final String OFFERS = "/v1/me/loan-offers";

    @LocalServerPort private int port;
    @Autowired private LoanTermsAdministration termsAdministration;
    @Autowired private LoanTermsStore termsStore;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private LoanOrigination origination;
    @Autowired private LoanOfferMetrics offerMetrics;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private IdGenerator ids;

    private LoanApplicationClient client;
    private CreditFixtures customers;
    private LoanOriginationSchedule sweep;

    @BeforeEach
    void creditAndTermsInForce() {
        client = new LoanApplicationClient(port);
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
        // The production sweep, off in test contexts, constructed over the context's own beans and driven by hand.
        sweep = new LoanOriginationSchedule(origination, lendingTransactions, offerMetrics, Duration.ofSeconds(5), 50,
                Duration.ofHours(1));
    }

    @Test
    @DisplayName("an approved application is offered end to end: credit decides, its event hints, the sweep offers"
            + " exactly the approved figures on the active terms; the owner reads the offer - illustrative schedule,"
            + " totals, expiry within the decision's validity, the document hashed - and declines it once, replayed by"
            + " its key; a second key is 409 lending.OfferNotAcceptable")
    void anApprovedApplicationIsOfferedEndToEnd() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), loan("10000.00", 36, 10), key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        String application = field(submitted.body(), "applicationId");
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        sweep.sweepOnce();
        assertThat(field(client.read(customer.token(), application).body(), "status"))
                .as("credit has not decided: the application waits").isEqualTo("AWAITING_DECISION");

        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        UUID decision = UUID.fromString(scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?",
                request));
        double offeredBefore = offered(LoanProduct.PERSONAL_LOAN);
        lendingTransactions.inTransaction(uow -> {
            hint(LoanOriginationHint.Kind.DECISION_RECORDED).handle(uow, event("credit.CreditDecisionRecorded", 2,
                    decision));
            return null;
        });
        sweep.sweepOnce();
        assertThat(offered(LoanProduct.PERSONAL_LOAN)).as("counted once, after the commit").isEqualTo(offeredBefore + 1);

        HttpResponse<String> read = client.read(customer.token(), application);
        assertThat(field(read.body(), "status")).isEqualTo("OFFERED");
        String offer = field(read.body(), "offerId");
        HttpResponse<String> shown = client.get(OFFERS + "/" + offer, customer.token());
        assertThat(shown.statusCode()).as(shown.body()).isEqualTo(200);
        assertThat(shown.body()).contains("\"status\":\"OFFERED\"", "\"amount\":\"10000.00\"", "\"termMonths\":36",
                "\"nominalAnnualRate\":\"0.0899\"", "\"repaymentDay\":10", "\"basis\":\"ILLUSTRATIVE",
                "\"totalPayable\":", "\"agreementTemplate\":\"PERSONAL_LOAN_AGREEMENT\"", "\"document\":\"LOAN OFFER ")
                .doesNotContain("score", "reasonCode", "CRD-", "partyId", customer.party().toString());
        assertThat(field(shown.body(), "termsSha256")).isEqualTo(scalar("SELECT encode(terms_sha256, 'hex')"
                + " FROM lending.loan_offer_terms WHERE offer_id = ?::uuid", offer));
        assertThat(scalar("SELECT (o.expires_at <= d.valid_until AND o.decision_id = d.id"
                + " AND t.amount_minor = d.approved_minor AND t.term_months = d.term_months)::text"
                + " FROM lending.loan_offer o JOIN lending.loan_offer_terms t ON t.offer_id = o.id"
                + " JOIN credit.credit_decision d ON d.id = o.decision_id WHERE o.id = ?::uuid", offer))
                .as("exactly the approved amount and term (A20); the expiry within the decision's validity")
                .isEqualTo("true");
        assertThat(count("SELECT count(*) FROM jsonb_array_elements(?::jsonb -> 'illustration' -> 'periods')",
                shown.body())).isEqualTo(36);

        String declineKey = key();
        HttpResponse<String> declined = client.post(OFFERS + "/" + offer + "/decline", null, customer.token(), declineKey);
        assertThat(declined.statusCode()).as(declined.body()).isEqualTo(200);
        assertThat(field(declined.body(), "status")).isEqualTo("DECLINED");
        assertThat(client.post(OFFERS + "/" + offer + "/decline", null, customer.token(), declineKey).body())
                .as("a lost answer replayed byte for byte").isEqualTo(declined.body());
        HttpResponse<String> again = client.post(OFFERS + "/" + offer + "/decline", null, customer.token(), key());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(again.body()).contains("lending.OfferNotAcceptable");
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ?::uuid"
                + " AND to_status = 'DECLINED'", offer)).isEqualTo(1);
    }

    @Test
    @DisplayName("ten declines under one key are one (INV-IDEM-01, the gate's idempotency clause): every answer the one"
            + " receipt or 409 api.IdempotencyInProgress - the offer DECLINED once, one offer event, credit's decision"
            + " never consumed")
    void tenDeclinesUnderOneKeyAreOne() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        String offer = offered(customer, line("2100.00"));
        String declineKey = key();
        HttpResponse<String> declined = OneKeyRace.tenUnderOneKey(200, () -> client.post(OFFERS + "/" + offer
                + "/decline", null, customer.token(), declineKey));
        assertThat(field(declined.body(), "status")).isEqualTo("DECLINED");
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer)).isEqualTo("DECLINED");
        assertThat(count("SELECT count(*) FROM lending.loan_offer_event WHERE offer_id = ?::uuid AND to_status ="
                + " 'DECLINED'", offer)).as("declined once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM credit.credit_decision_consumption c JOIN lending.loan_offer o"
                + " ON o.decision_id = c.decision_id WHERE o.id = ?::uuid", offer)).isZero();
    }

    @Test
    @DisplayName("another party's offer is the uniform 404 to read and to decline - nothing written")
    void anotherPartysOfferIsNotFound() throws Exception {
        CreditFixtures.Customer owner = customers.consentingCustomer();
        String offer = offered(owner, line("2000.00"));
        CreditFixtures.Customer stranger = customers.consentingCustomer();
        HttpResponse<String> read = client.get(OFFERS + "/" + offer, stranger.token());
        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(read.body()).contains("lending.NotFound");
        HttpResponse<String> decline = client.post(OFFERS + "/" + offer + "/decline", null, stranger.token(), key());
        assertThat(decline.statusCode()).isEqualTo(404);
        assertThat(scalar("SELECT status FROM lending.loan_offer WHERE id = ?::uuid", offer)).isEqualTo("OFFERED");
        HttpResponse<String> mine = client.get(OFFERS + "/" + offer, owner.token());
        assertThat(mine.body()).contains("\"amount\":\"2000.00\"", "\"minimumPayment\":{", "\"ratioBasisPoints\":300",
                "\"illustration\":null");
    }

    @Test
    @DisplayName("credit's decline closes the application DECLINED with credit's reasons in the customer's words; a lost"
            + " standing - credit's request ABANDONED - closes it CLOSED_UNDECIDED; neither offers")
    void creditsAnswersCloseTheApplication() throws Exception {
        CreditFixtures.Customer declined = customers.consentingCustomer();
        CreditFixtures.bureauBalance(declined.party(), 3_500_000L);
        HttpResponse<String> submitted = client.submit(declined.token(), loan("10000.00", 36), key());
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        assertThat(CreditFixtures.decide(request)).isEqualTo("DECLINED");
        sweep.sweepOnce();
        HttpResponse<String> read = client.read(declined.token(), field(submitted.body(), "applicationId"));
        assertThat(field(read.body(), "status")).isEqualTo("DECLINED");
        assertThat(read.body()).contains("\"reasons\":[").contains("\"offerId\":null");

        CreditFixtures.Customer lost = customers.consentingCustomer();
        HttpResponse<String> open = client.submit(lost.token(), line("2000.00"), key());
        UUID abandoned = UUID.fromString(field(open.body(), "decisionRequestId"));
        CreditFixtures.standingLost(lost.party());
        CreditFixtures.stepOnce(abandoned);
        assertThat(scalar("SELECT status FROM credit.decision_request WHERE id = ?", abandoned)).isEqualTo("ABANDONED");
        lendingTransactions.inTransaction(uow -> {
            hint(LoanOriginationHint.Kind.REQUEST_CLOSED).handle(uow, event("credit.CreditDecisionRequestClosed", 1,
                    abandoned));
            return null;
        });
        sweep.sweepOnce();
        assertThat(field(client.read(lost.token(), field(open.body(), "applicationId")).body(), "status"))
                .isEqualTo("CLOSED_UNDECIDED");
        assertThat(scalar("SELECT reason FROM lending.loan_application_event WHERE application_id = ?::uuid"
                + " AND to_status = 'CLOSED_UNDECIDED'", field(open.body(), "applicationId")))
                .isEqualTo("CREDIT_REQUEST_ABANDONED");
        assertThat(count("SELECT count(*) FROM lending.loan_offer WHERE party_id IN (?, ?)", declined.party(),
                lost.party())).isZero();
    }

    @Test
    @DisplayName("credit's event version 1 is handled exactly as version 2 - the payload never read - and a version this"
            + " build does not know is refused: acknowledged, no permit moved")
    void bothVersionsAreHintsAndAnUnknownOneIsRefused() throws Exception {
        CreditFixtures.Customer customer = customers.consentingCustomer();
        HttpResponse<String> submitted = client.submit(customer.token(), line("1500.00"), key());
        UUID request = UUID.fromString(field(submitted.body(), "decisionRequestId"));
        sweep.sweepOnce();
        assertThat(CreditFixtures.decide(request)).isEqualTo("APPROVED");
        UUID decision = UUID.fromString(scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?",
                request));
        String due = "SELECT (next_step_at <= statement_timestamp())::text FROM lending.loan_application"
                + " WHERE decision_request_id = ?";
        lendingTransactions.inTransaction(uow -> {
            hint(LoanOriginationHint.Kind.DECISION_RECORDED).handle(uow, event("credit.CreditDecisionRecorded", 3,
                    decision));
            return null;
        });
        assertThat(scalar(due, request)).as("an unknown version moves nothing").isEqualTo("false");
        lendingTransactions.inTransaction(uow -> {
            hint(LoanOriginationHint.Kind.DECISION_RECORDED).handle(uow, event("credit.CreditDecisionRecorded", 1,
                    decision));
            return null;
        });
        assertThat(scalar(due, request)).as("version 1 hints as version 2 does").isEqualTo("true");
        sweep.sweepOnce();
        assertThat(field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "status"))
                .isEqualTo("OFFERED");
    }

    // ------------------------------------------------------------------ plumbing

    /** {@code body} applied for by {@code customer}, approved by credit and offered by the sweep; the offer's id. */
    private String offered(CreditFixtures.Customer customer, String body) throws Exception {
        HttpResponse<String> submitted = client.submit(customer.token(), body, key());
        assertThat(submitted.statusCode()).as(submitted.body()).isEqualTo(202);
        assertThat(CreditFixtures.decide(UUID.fromString(field(submitted.body(), "decisionRequestId"))))
                .isEqualTo("APPROVED");
        lendingTransactions.inTransaction(uow -> origination.hintRequest(uow,
                UUID.fromString(field(submitted.body(), "decisionRequestId"))));
        sweep.sweepOnce();
        return field(client.read(customer.token(), field(submitted.body(), "applicationId")).body(), "offerId");
    }

    private LoanOriginationHint hint(LoanOriginationHint.Kind kind) {
        return new LoanOriginationHint(origination, kind);
    }

    private ReceivedEvent event(String type, int version, UUID aggregate) {
        return new ReceivedEvent(EventId.next(ids), type, version, 1, aggregate, "credit", Instant.now(), "credit",
                CorrelationId.generate(ids), CausationId.generate(ids), "application/json",
                // A forged figure the handler must never read.
                "{\"approvedMinor\":\"999999999\",\"outcome\":\"APPROVED\"}".getBytes(StandardCharsets.UTF_8));
    }

    private double offered(LoanProduct product) {
        return meterRegistry.find(LoanOfferMetrics.OFFER).tag("product", product.name()).tag("outcome", "offered")
                .counter().count();
    }

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }
}
