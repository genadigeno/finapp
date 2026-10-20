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
 * The repayment doors' contract over HTTP (`P12-TSK-018`; {@code PHASE_12_PLAN.md} section 9; A18, ADR-0031): no session
 * 401; no key, an open body, a malformed amount refused before anything; an MFA-enrolled customer on a password session
 * 403 {@code identity.AssuranceRequired} - the conditional step-up - and nothing posted; the owner repays (201, the
 * allocation and the value date), the same key replays the same repayment, the history lists it; another party's agreement
 * - and an unknown or malformed id - one 404 on both doors, nothing locked or posted.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-018` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL repayment doors' contract (P12-TSK-018)")
class BnplRepaymentApiTest {

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
    @DisplayName("no session is 401; no key, an open body and a malformed amount are refused before anything")
    void theSessionTheKeyAndTheBody() throws Exception {
        // A well-formed agreement id (UUIDv7, as every BNPL id is) that names nothing: a random v4 id is MALFORMED -
        // the uniform 404 before the body is read - so it could never show the body judged first (P12-TSK-028).
        String repayments = BnplPurchaseWorld.AGREEMENTS + "/" + new com.finapp.sharedkernel.id.IdGenerator(
                java.time.Clock.systemUTC(), new java.security.SecureRandom()).next() + "/repayments";
        String body = "{\"amount\":\"10.00\",\"currency\":\"EUR\"}";
        assertThat(world.client.post(repayments, body, null, BnplApplicationClient.key()).statusCode()).isEqualTo(401);
        assertThat(world.client.get(repayments, null).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        HttpResponse<String> unkeyed = world.client.post(repayments, body, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> open = world.client.post(repayments,
                "{\"amount\":\"10.00\",\"currency\":\"EUR\",\"wallet\":\"x\"}", customer.token(),
                BnplApplicationClient.key());
        assertThat(open.statusCode()).as(open.body()).isEqualTo(422);
        HttpResponse<String> exponent = world.client.post(repayments, "{\"amount\":\"1e3\",\"currency\":\"EUR\"}",
                customer.token(), BnplApplicationClient.key());
        assertThat(exponent.statusCode()).as(exponent.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("the owner repays: 201 with the allocation; the same key replays the same repayment; the history lists"
            + " it; an MFA-enrolled customer on a password session is 403 and posts nothing (the conditional step-up)")
    void theOwnerRepaysUnderTheStepUp() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        String repayments = BnplPurchaseWorld.AGREEMENTS + "/" + agreement + "/repayments";
        String body = "{\"amount\":\"40.00\",\"currency\":\"EUR\"}";

        String passwordOnly = world.customers.login(owner);
        HttpResponse<String> refused = world.client.post(repayments, body, passwordOnly, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o"
                + " ON o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = 'CUSTOMER'", agreement)).isZero();

        String key = BnplApplicationClient.key();
        HttpResponse<String> repaid = world.client.post(repayments, body, owner.token(), key);
        assertThat(repaid.statusCode()).as(repaid.body()).isEqualTo(201);
        assertThat(repaid.body()).contains("\"kind\":\"CUSTOMER\"", "\"status\":\"ALLOCATED\"", "\"amount\":\"40.00\"",
                "\"target\":\"CREDIT_BALANCE\"").doesNotContain(owner.party().toString()).doesNotContain(key);
        HttpResponse<String> again = world.client.post(repayments, body, owner.token(), key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(BnplApplicationClient.field(again.body(), "repaymentId"))
                .isEqualTo(BnplApplicationClient.field(repaid.body(), "repaymentId"));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o"
                + " ON o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = 'CUSTOMER'", agreement))
                .isEqualTo(1);
        HttpResponse<String> history = world.client.get(repayments, owner.token());
        assertThat(history.statusCode()).as(history.body()).isEqualTo(200);
        assertThat(history.body()).contains(BnplApplicationClient.field(repaid.body(), "repaymentId"), "AT_PURCHASE");
    }

    @Test
    @DisplayName("another party's agreement - and an unknown or malformed id - is one 404 bnpl.NotFound on the repayment"
            + " and its history, nothing posted (ADR-0031's negative)")
    void anotherPartysAgreementIsNotFound() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");

        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        world.topUpWallet(stranger.party(), eur(1_000_00));
        for (String id : List.of(agreement, UUID.randomUUID().toString(), "not-a-uuid")) {
            String repayments = BnplPurchaseWorld.AGREEMENTS + "/" + id + "/repayments";
            HttpResponse<String> repay = world.client.post(repayments, "{\"amount\":\"10.00\",\"currency\":\"EUR\"}",
                    stranger.token(), BnplApplicationClient.key());
            assertThat(repay.statusCode()).as(id + ": " + repay.body()).isEqualTo(404);
            assertThat(repay.body()).contains("bnpl.NotFound");
            HttpResponse<String> read = world.client.get(repayments, stranger.token());
            assertThat(read.statusCode()).as(id + ": " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("bnpl.NotFound");
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o"
                + " ON o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = 'CUSTOMER'", agreement)).isZero();
    }
}
