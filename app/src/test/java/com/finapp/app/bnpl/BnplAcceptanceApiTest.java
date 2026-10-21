package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.identity.Authorization;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The acceptance door's and the agreement reads' contract over HTTP (`P12-TSK-013`; {@code PHASE_12_PLAN.md} section 9;
 * A18, ADR-0031): no session 401; no key, an open body and a missing hash refused before anything; a password-only
 * session 403 {@code identity.AssuranceRequired} - the acceptance is MULTI_FACTOR always; an unknown, a malformed and
 * another party's offer one 404; an agreement read by its owner - state, plan, the derived balances, the schedule with
 * instalment 1 billed - and another party's agreement the same 404 as an unknown one.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-013` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL acceptance door's and the agreement reads' contract (P12-TSK-013)")
class BnplAcceptanceApiTest {

    private static final long GROSS = 600_00L;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private BnplEnablements enablements;
    @Autowired private BnplPricing pricing;
    @Autowired private FeeSchedules feeSchedules;
    @Autowired private BnplTermsAdministration terms;
    @Autowired private BnplTransactions transactions;
    @Autowired private BnplOrigination origination;
    @Autowired private PostingService postingService;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private LendingCapitalHeadroom headroom;
    @Autowired private LoanWallets loanWallets;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("no session is 401; no key, an open body and a missing hash are refused before anything")
    void theSessionTheKeyAndTheBody() throws Exception {
        String id = UUID.randomUUID().toString();
        String acceptance = BnplPurchaseWorld.OFFERS + "/" + id + "/acceptance";
        assertThat(world.client.post(acceptance, "{\"termsSha256\":\"00\"}", null, BnplApplicationClient.key())
                .statusCode()).isEqualTo(401);
        assertThat(world.client.get(BnplPurchaseWorld.AGREEMENTS, null).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        HttpResponse<String> unkeyed = world.client.post(acceptance, "{\"termsSha256\":\"00\"}", customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> open = world.client.post(acceptance, "{\"termsSha256\":\"00\",\"amount\":\"1.00\"}",
                customer.token(), BnplApplicationClient.key());
        assertThat(open.statusCode()).as(open.body()).isEqualTo(422);
        HttpResponse<String> missing = world.client.post(acceptance, "{}", customer.token(), BnplApplicationClient.key());
        assertThat(missing.statusCode()).as(missing.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("a password-only session is 403 identity.AssuranceRequired - the acceptance is MULTI_FACTOR always (A18)"
            + " - and nothing is consumed")
    void theAcceptanceIsMultiFactor() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.topUpWallet(customer.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(customer, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        String passwordOnly = world.customers.login(customer);
        HttpResponse<String> refused = world.accept(passwordOnly, offer, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid", offer.id()))
                .isZero();
        assertThat(world.accept(customer.token(), offer, BnplApplicationClient.key()).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("an unknown, a malformed and another party's offer id are one 404 bnpl.NotFound on the acceptance")
    void theUniformNotFoundOnTheAcceptance() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS,
                token -> BnplApplicationClient.payInN(token, 3));
        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid", offer.id())) {
            HttpResponse<String> refused = world.accept(stranger.token(), id, offer.hash(), BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
            assertThat(refused.body()).contains("bnpl.NotFound");
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid", offer.id()))
                .isZero();
    }

    @Test
    @DisplayName("the owner reads the agreement - FINANCED, the plan, the amount, the derived balances - its schedule"
            + " with instalment 1 billed, and its list; another party's agreement is the same 404 as an unknown or"
            + " malformed one (ADR-0031's negative)")
    void anotherPartysAgreementIsNotFound() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        assertThat(accepted.body()).contains("\"status\":\"FINANCED\"").doesNotContain("600.00");
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");

        HttpResponse<String> read = world.client.get(BnplPurchaseWorld.AGREEMENTS + "/" + agreement, owner.token());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"status\":\"ACTIVE\"", "\"plan\":\"PAY_IN_4\"", "\"amount\":\"600.00\"",
                "\"principalOutstanding\":\"450.00\"", "\"principalDue\":\"0.00\"");
        HttpResponse<String> schedule = world.client.get(BnplPurchaseWorld.AGREEMENTS + "/" + agreement + "/schedule",
                owner.token());
        assertThat(schedule.statusCode()).as(schedule.body()).isEqualTo(200);
        assertThat(schedule.body()).contains("\"billed\":true").contains("\"engine\":\"BNPL_SCHEDULE_ENGINE_V1\"");
        assertThat(world.client.get(BnplPurchaseWorld.AGREEMENTS, owner.token()).body()).contains(agreement);

        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        for (String id : List.of(agreement, UUID.randomUUID().toString(), "not-a-uuid")) {
            for (String path : List.of(BnplPurchaseWorld.AGREEMENTS + "/" + id,
                    BnplPurchaseWorld.AGREEMENTS + "/" + id + "/schedule")) {
                HttpResponse<String> refused = world.client.get(path, stranger.token());
                assertThat(refused.statusCode()).as(path + ": " + refused.body()).isEqualTo(404);
                assertThat(refused.body()).contains("bnpl.NotFound");
            }
        }
        assertThat(world.client.get(BnplPurchaseWorld.AGREEMENTS, stranger.token()).body()).doesNotContain(agreement);
    }
}
