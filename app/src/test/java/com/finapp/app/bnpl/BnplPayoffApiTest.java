package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
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
 * The payoff doors' contract over HTTP (`P12-TSK-022`; {@code PHASE_12_PLAN.md} section 9; A18, ADR-0031): no session
 * 401; no key, an open body, a malformed date refused before anything; the quote under the conditional step-up (an
 * MFA-enrolled customer on a password session 403), the payoff {@code MULTI_FACTOR} always (403 on a password session,
 * nothing posted); the owner quotes (201) and pays off (201, {@code PAID_OFF_EARLY}), the same key replays the same payoff,
 * a second key on the same quote converges on it, a quote after it is {@code 409 bnpl.ObligationClosed}; another party's
 * agreement - and an unknown or malformed id, and a quote of another agreement - one 404 on both doors, nothing locked or
 * posted.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-022` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL payoff doors' contract (P12-TSK-022)")
class BnplPayoffApiTest {

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
    @DisplayName("no session is 401; no key, an open body and a malformed date are refused before anything")
    void theSessionTheKeyAndTheBody() throws Exception {
        // A well-formed agreement id (UUIDv7, as every BNPL id is) that names nothing: a random v4 id is MALFORMED -
        // the uniform 404 before the body is read - so it could never show the body judged first (P12-TSK-028).
        String agreement = BnplPurchaseWorld.AGREEMENTS + "/" + new com.finapp.sharedkernel.id.IdGenerator(
                java.time.Clock.systemUTC(), new java.security.SecureRandom()).next();
        assertThat(world.client.post(agreement + "/payoff-quotes", "{}", null, BnplApplicationClient.key()).statusCode())
                .isEqualTo(401);
        assertThat(world.client.post(agreement + "/payoff", "{\"quoteId\":\"" + UUID.randomUUID() + "\"}", null,
                BnplApplicationClient.key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        HttpResponse<String> unkeyed = world.client.post(agreement + "/payoff-quotes", "{}", customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> open = world.client.post(agreement + "/payoff-quotes", "{\"amount\":\"1.00\"}",
                customer.token(), BnplApplicationClient.key());
        assertThat(open.statusCode()).as(open.body()).isEqualTo(422);
        HttpResponse<String> malformed = world.client.post(agreement + "/payoff-quotes", "{\"goodThrough\":\"tomorrow\"}",
                customer.token(), BnplApplicationClient.key());
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("the owner quotes and pays off: 201 with the quote, 403 on a password session for the payoff (MULTI_FACTOR"
            + " always) and nothing posted, 201 PAID_OFF_EARLY; the same key replays, another key converges, a quote after"
            + " is 409 ObligationClosed")
    void theOwnerPaysOffUnderMultiFactor() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        String quotes = BnplPurchaseWorld.AGREEMENTS + "/" + agreement + "/payoff-quotes";
        String payoff = BnplPurchaseWorld.AGREEMENTS + "/" + agreement + "/payoff";

        String passwordOnly = world.customers.login(owner);
        HttpResponse<String> steppedUp = world.client.post(quotes, "{}", passwordOnly, BnplApplicationClient.key());
        assertThat(steppedUp.statusCode()).as(steppedUp.body()).isEqualTo(403);
        assertThat(steppedUp.body()).contains("identity.AssuranceRequired");

        HttpResponse<String> quoted = world.client.post(quotes, "{}", owner.token(), BnplApplicationClient.key());
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        assertThat(quoted.body()).contains("\"amount\":\"450.00\"", "\"refund\":\"0.00\"", "\"projectedDays\":0")
                .doesNotContain(owner.party().toString());
        String quoteId = BnplApplicationClient.field(quoted.body(), "quoteId");
        String body = "{\"quoteId\":\"" + quoteId + "\"}";

        HttpResponse<String> refused = world.client.post(payoff, body, passwordOnly, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_payoff_execution e JOIN"
                + " bnpl.bnpl_obligation o ON o.id = e.obligation_id WHERE o.agreement_id = ?::uuid", agreement)).isZero();

        String key = BnplApplicationClient.key();
        HttpResponse<String> paid = world.client.post(payoff, body, owner.token(), key);
        assertThat(paid.statusCode()).as(paid.body()).isEqualTo(201);
        assertThat(paid.body()).contains("\"closureReason\":\"PAID_OFF_EARLY\"", "\"paid\":\"450.00\"",
                "\"refunded\":\"0.00\"").doesNotContain(key);
        String execution = BnplApplicationClient.field(paid.body(), "executionId");
        HttpResponse<String> again = world.client.post(payoff, body, owner.token(), key);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(201);
        assertThat(BnplApplicationClient.field(again.body(), "executionId")).isEqualTo(execution);
        HttpResponse<String> converged = world.client.post(payoff, body, owner.token(), BnplApplicationClient.key());
        assertThat(converged.statusCode()).as(converged.body()).isEqualTo(201);
        assertThat(BnplApplicationClient.field(converged.body(), "executionId")).isEqualTo(execution);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o"
                + " ON o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = 'PAYOFF'", agreement))
                .isEqualTo(1);
        HttpResponse<String> after = world.client.post(quotes, "{}", owner.token(), BnplApplicationClient.key());
        assertThat(after.statusCode()).as(after.body()).isEqualTo(409);
        assertThat(after.body()).contains("bnpl.ObligationClosed");
    }

    @Test
    @DisplayName("another party's agreement - and an unknown or malformed id, and another agreement's quote - is one 404"
            + " bnpl.NotFound on both doors, nothing quoted or posted (ADR-0031's negative)")
    void anotherPartysAgreementIsNotFound() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        HttpResponse<String> quoted = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + agreement + "/payoff-quotes",
                "{}", owner.token(), BnplApplicationClient.key());
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        String quoteId = BnplApplicationClient.field(quoted.body(), "quoteId");

        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        world.topUpWallet(stranger.party(), eur(1_000_00));
        for (String id : List.of(agreement, UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> quote = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + id + "/payoff-quotes",
                    "{}", stranger.token(), BnplApplicationClient.key());
            assertThat(quote.statusCode()).as(id + ": " + quote.body()).isEqualTo(404);
            assertThat(quote.body()).contains("bnpl.NotFound");
            HttpResponse<String> pay = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + id + "/payoff",
                    "{\"quoteId\":\"" + quoteId + "\"}", stranger.token(), BnplApplicationClient.key());
            assertThat(pay.statusCode()).as(id + ": " + pay.body()).isEqualTo(404);
            assertThat(pay.body()).contains("bnpl.NotFound");
        }
        // The stranger's own agreement, executing the owner's quote: not its quote - the same 404.
        BnplPurchaseWorld.Offer own = world.offered(stranger, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> mine = world.accept(stranger.token(), own, BnplApplicationClient.key());
        assertThat(mine.statusCode()).as(mine.body()).isEqualTo(201);
        String strangers = BnplApplicationClient.field(mine.body(), "agreementId");
        HttpResponse<String> foreign = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + strangers + "/payoff",
                "{\"quoteId\":\"" + quoteId + "\"}", stranger.token(), BnplApplicationClient.key());
        assertThat(foreign.statusCode()).as(foreign.body()).isEqualTo(404);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_payoff_execution e JOIN"
                + " bnpl.bnpl_obligation o ON o.id = e.obligation_id WHERE o.agreement_id IN (?::uuid, ?::uuid)",
                agreement, strangers)).isZero();
    }
}
