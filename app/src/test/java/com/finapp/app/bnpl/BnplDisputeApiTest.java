package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
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
 * The dispute doors' contract over HTTP (`P12-TSK-023`; ADR-0106, {@code PHASE_12_PLAN.md} section 9; ADR-0031,
 * {@code INV-MER-01}, {@code INV-AUD-03}): the customer opens a dispute of their own agreement (201) - another party's
 * agreement and another party's dispute are one 404; the merchant lists and answers only its own disputes - another
 * merchant's is the 404 and its list shows none; the operator's doors refuse a session without {@code BNPL_DISPUTE_RESOLVE}
 * (403) and admit one with it; a merchant refund while the dispute is open is {@code 409 bnpl.DisputeOpen}; the merchant's
 * acceptance is a refund of door {@code DISPUTE}.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 * Written by `P12-TSK-023` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL dispute doors' contract (P12-TSK-023)")
class BnplDisputeApiTest {

    private static final long GROSS = 600_00L;
    private static final String BODY =
            "{\"amount\":\"300.00\",\"currency\":\"EUR\",\"category\":\"NOT_RECEIVED\",\"reason\":\"it never came\"}";
    private static final String CONTEST = "{\"decision\":\"CONTEST\",\"statement\":\"we shipped it\"}";
    private static final String ACCEPT = "{\"decision\":\"ACCEPT\",\"statement\":\"refund it\"}";

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
    @Autowired private com.finapp.accounts.AccountClosing accountClosing;
    @Autowired private com.finapp.party.PartyStore<Connection> partyStore;
    @Autowired private com.finapp.accounts.CustomerAccountStore<Connection> customerAccountStore;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("the customer opens a dispute of their own agreement (201, the hold placed); another party's agreement is"
            + " the uniform 404 and opens nothing (ADR-0031's negative)")
    void anotherPartysAgreementIsNotFound() throws Exception {
        Bought bought = bought();
        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        HttpResponse<String> refused = world.client.post("/v1/me/bnpl-agreements/" + bought.agreement() + "/disputes",
                BODY, stranger.token(), BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(404);
        assertThat(refused.body()).contains("bnpl.NotFound");
        assertThat(disputes(bought.purchase())).isZero();
        HttpResponse<String> opened = open(bought);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        assertThat(opened.body()).contains("\"status\":\"OPEN\"", "\"heldInstalments\":2").doesNotContain("never came");
        assertThat(disputes(bought.purchase())).isEqualTo(1);
    }

    @Test
    @DisplayName("another party's dispute is the uniform 404 at the withdrawal - nothing moves; the owner withdraws it")
    void anotherPartysDisputeIsNotFound() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        CreditFixtures.Customer stranger = world.customers.consentingCustomer();
        for (String id : List.of(dispute, UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> refused = world.client.post("/v1/me/bnpl-disputes/" + id + "/withdrawal", null,
                    stranger.token(), BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(id + ": " + refused.body()).isEqualTo(404);
        }
        assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_dispute WHERE id = ?::uuid", dispute))
                .isEqualTo("OPEN");
        HttpResponse<String> withdrawn = world.client.post("/v1/me/bnpl-disputes/" + dispute + "/withdrawal", null,
                bought.owner().token(), BnplApplicationClient.key());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(withdrawn.body()).contains("\"status\":\"WITHDRAWN\"");
    }

    @Test
    @DisplayName("another merchant's dispute - and an unknown or malformed id - is one 404 at the response, nothing moved;"
            + " a merchant refund while it is open is 409 bnpl.DisputeOpen; the owner's acceptance is a refund of door"
            + " DISPUTE")
    void anotherMerchantsDisputeIsNotFound() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        BnplApplicationClient.Merchant stranger = world.merchant();
        for (String id : List.of(dispute, UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> refused = world.client.post("/v1/merchant/bnpl-disputes/" + id + "/response", ACCEPT,
                    stranger.apiKey(), BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(id + ": " + refused.body()).isEqualTo(404);
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                bought.purchase())).isZero();
        HttpResponse<String> accepted = world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response",
                ACCEPT, bought.merchant().apiKey(), BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(accepted.body()).contains("\"status\":\"RESOLVED_REFUND\"").doesNotContain("heldInstalments");
        assertThat(BnplApplicationClient.scalar("SELECT door || '|' || amount_minor FROM bnpl.bnpl_refund"
                + " WHERE dispute_id = ?::uuid", dispute)).isEqualTo("DISPUTE|30000");
    }

    @Test
    @DisplayName("another merchant sees none of the disputes; the owner's list shows its open one - never the customer's"
            + " reason")
    void anotherMerchantSeesNoneOfTheDisputes() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        BnplApplicationClient.Merchant stranger = world.merchant();
        HttpResponse<String> theirs = world.client.get("/v1/merchant/bnpl-disputes", stranger.apiKey());
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(200);
        assertThat(theirs.body()).doesNotContain(dispute);
        HttpResponse<String> mine = world.client.get("/v1/merchant/bnpl-disputes", bought.merchant().apiKey());
        assertThat(mine.body()).contains(dispute, "NOT_RECEIVED").doesNotContain("never came");
    }

    @Test
    @DisplayName("the operator's doors: without BNPL_DISPUTE_RESOLVE 403 on the read and the resolution; with it the read is"
            + " audited and the resolution of an answered dispute at or below the threshold resolves")
    void theOperatorsDoorsRequireThePermission() throws Exception {
        Bought bought = bought();
        String opening = "{\"amount\":\"200.00\",\"currency\":\"EUR\",\"category\":\"NOT_AS_DESCRIBED\","
                + "\"reason\":\"not as shown\"}";
        String dispute = BnplApplicationClient.field(world.client.post("/v1/me/bnpl-agreements/" + bought.agreement()
                + "/disputes", opening, bought.owner().token(), BnplApplicationClient.key()).body(), "disputeId");
        assertThat(world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response", CONTEST,
                bought.merchant().apiKey(), BnplApplicationClient.key()).statusCode()).isEqualTo(200);
        String outsider = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        assertThat(world.client.get("/v1/operator/bnpl-disputes/" + dispute, outsider).statusCode()).isEqualTo(403);
        String resolution = "{\"outcome\":\"UPHOLD\",\"reason\":\"the evidence holds\"}";
        assertThat(world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution", resolution, outsider,
                BnplApplicationClient.key()).statusCode()).isEqualTo(403);
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> read = world.client.get("/v1/operator/bnpl-disputes/" + dispute, agent);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("not as shown", "we shipped it");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.DisputeRead' AND target_id = ?", dispute)).isEqualTo(1);
        HttpResponse<String> upheld = world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution",
                resolution, agent, BnplApplicationClient.key());
        assertThat(upheld.statusCode()).as(upheld.body()).isEqualTo(200);
        assertThat(upheld.body()).contains("\"status\":\"RESOLVED_UPHELD\"");
    }

    @Test
    @DisplayName("a merchant refund while a dispute is open is 409 bnpl.DisputeOpen, nothing written")
    void aRefundWhileADisputeIsOpenIsRefused() throws Exception {
        Bought bought = bought();
        open(bought);
        String operator = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        HttpResponse<String> issued = world.client.post("/v1/operator/merchants/" + bought.merchant().id()
                + "/api-keys?scope=BNPL_REFUND", null, operator, BnplApplicationClient.key());
        String scoped = BnplApplicationClient.field(issued.body(), "keyId") + "." + BnplApplicationClient.field(
                issued.body(), "secret");
        HttpResponse<String> refused = world.client.post("/v1/merchant/bnpl-purchases/" + bought.purchase() + "/refunds",
                "{\"amount\":\"100.00\",\"currency\":\"EUR\",\"reason\":\"returned\"}", scoped,
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("bnpl.DisputeOpen");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                bought.purchase())).isZero();
    }

    @Test
    @DisplayName("four eyes through the API (INV-AUD-04): above the threshold the answered dispute's resolution is a"
            + " proposal; no session is 401 and a role without BNPL_DISPUTE_RESOLVE 403 on the approval and the rejection;"
            + " the proposer's own approval is 403 bnpl.SelfApprovalRefused, recorded FAILED once against the proposal,"
            + " the dispute unchanged; another operator's approval resolves it")
    void aProposersOwnApprovalIs403AndRecordedFailed() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        assertThat(world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response", CONTEST,
                bought.merchant().apiKey(), BnplApplicationClient.key()).statusCode()).isEqualTo(200);
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String resolutions = "/v1/operator/bnpl-disputes/" + dispute + "/resolution";
        HttpResponse<String> proposed = world.client.post(resolutions, "{\"outcome\":\"REFUND\",\"reason\":\"the"
                + " parcel never arrived\"}", proposer, BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        assertThat(proposed.body()).contains("\"status\":\"MERCHANT_RESPONDED\"", "\"proposalStatus\":\"PROPOSED\"");
        String proposal = BnplApplicationClient.field(proposed.body(), "proposalId");
        String decision = "{\"proposalId\":\"" + proposal + "\",\"reason\":\"checked\"}";

        String outsider = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        for (String door : List.of(resolutions + "/approval", resolutions + "/rejection")) {
            assertThat(world.client.post(door, decision, null, BnplApplicationClient.key()).statusCode())
                    .as("no session at " + door).isEqualTo(401);
            assertThat(world.client.post(door, decision, outsider, BnplApplicationClient.key()).statusCode())
                    .as("no BNPL_DISPUTE_RESOLVE at " + door).isEqualTo(403);
        }

        HttpResponse<String> own = world.client.post(resolutions + "/approval", decision, proposer,
                BnplApplicationClient.key());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("bnpl.SelfApprovalRefused");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfApprovalRefused' AND outcome = 'FAILED' AND target_id = ?", proposal)).isEqualTo(1);
        assertThat(BnplApplicationClient.scalar("SELECT d.status || '|' || p.status || '|' || (p.decided_by IS NULL)"
                + " FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_dispute_proposal p ON p.dispute_id = d.id WHERE p.id ="
                + " ?::uuid", proposal)).as("the dispute and its proposal unchanged")
                .isEqualTo("MERCHANT_RESPONDED|PROPOSED|true");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                bought.purchase())).isZero();

        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> approved = world.client.post(resolutions + "/approval", decision, approver,
                BnplApplicationClient.key());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"RESOLVED_REFUND\"", "\"proposalStatus\":\"APPROVED\"");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid AND"
                + " door = 'DISPUTE'", bought.purchase())).isEqualTo(1);
    }

    @Test
    @DisplayName("self-dealing through the API: an operator whose identity is the dispute's own party is 403"
            + " bnpl.SelfDealingRefused at the resolution, the approval and the rejection - each recorded FAILED against"
            + " the dispute, nothing written: no proposal of theirs, the other operator's proposal undecided, no refund")
    void anInsidersResolutionIsSelfDealing() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        assertThat(world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response", CONTEST,
                bought.merchant().apiKey(), BnplApplicationClient.key()).statusCode()).isEqualTo(200);
        String insider = world.client.sessionWithRole(authorization, bought.owner().login(),
                RoleName.BNPL_SERVICING_AGENT);
        String resolutions = "/v1/operator/bnpl-disputes/" + dispute + "/resolution";
        HttpResponse<String> resolved = world.client.post(resolutions, "{\"outcome\":\"REFUND\",\"reason\":\"mine\"}",
                insider, BnplApplicationClient.key());
        assertThat(resolved.statusCode()).as(resolved.body()).isEqualTo(403);
        assertThat(resolved.body()).contains("bnpl.SelfDealingRefused");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute_proposal WHERE dispute_id ="
                + " ?::uuid", dispute)).as("the insider proposed nothing").isZero();

        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String proposal = BnplApplicationClient.field(world.client.post(resolutions, "{\"outcome\":\"REFUND\","
                + "\"reason\":\"the evidence holds\"}", agent, BnplApplicationClient.key()).body(), "proposalId");
        String decision = "{\"proposalId\":\"" + proposal + "\",\"reason\":\"mine\"}";
        for (String door : List.of(resolutions + "/approval", resolutions + "/rejection")) {
            HttpResponse<String> refused = world.client.post(door, decision, insider, BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(door + " " + refused.body()).isEqualTo(403);
            assertThat(refused.body()).contains("bnpl.SelfDealingRefused");
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfDealingRefused' AND outcome = 'FAILED' AND target_id = ?", dispute)).isEqualTo(3);
        assertThat(BnplApplicationClient.scalar("SELECT d.status || '|' || p.status || '|' || (p.decided_by IS NULL)"
                + " FROM bnpl.bnpl_dispute d JOIN bnpl.bnpl_dispute_proposal p ON p.dispute_id = d.id WHERE p.id ="
                + " ?::uuid", proposal)).isEqualTo("MERCHANT_RESPONDED|PROPOSED|true");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute_proposal WHERE dispute_id ="
                + " ?::uuid", dispute)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                bought.purchase())).isZero();
    }

    @Test
    @DisplayName("self-dealing through the API at the operator refund door: an operator holding PAYMENT_REFUND whose"
            + " identity is the purchase's own party is 403 bnpl.SelfDealingRefused, recorded FAILED once against the"
            + " purchase, no refund written")
    void anInsidersOperatorRefundIsSelfDealing() throws Exception {
        Bought bought = bought();
        String insider = world.client.sessionWithRole(authorization, bought.owner().login(), RoleName.LEDGER_OPERATOR);
        HttpResponse<String> refused = world.client.post("/v1/operator/bnpl-purchases/" + bought.purchase()
                + "/refunds", "{\"amount\":\"100.00\",\"currency\":\"EUR\",\"reason\":\"returned\"}", insider,
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("bnpl.SelfDealingRefused");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfDealingRefused' AND outcome = 'FAILED' AND target_id = ?", bought.purchase())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                bought.purchase())).isZero();
    }

    // ------------------------------------------------------------------ the Phase 12 -> 13 gate's repairs (R1)

    @Test
    @DisplayName("a wallet cannot close while an open BNPL dispute may refund to it (the gate's A7-1): the customer's wallet"
            + " emptied by the purchase's first instalment, the close is refused AccountNotEmpty while the dispute is open"
            + " - accounts' real close, the composition root's BNPL arm - and closes once it is withdrawn")
    void aWalletCannotCloseWhileADisputeMayRefundToIt() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(150_00)); // exactly the first instalment: the wallet ends at zero
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        Bought bought = new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"),
                BnplApplicationClient.field(accepted.body(), "agreementId"), merchant, owner);
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");

        assertThatThrownBy(() -> closeWallet(owner)).as("an open dispute may still refund to it")
                .isInstanceOf(com.finapp.accounts.AccountNotEmptyException.class);
        HttpResponse<String> withdrawn = world.client.post("/v1/me/bnpl-disputes/" + dispute + "/withdrawal", null,
                owner.token(), BnplApplicationClient.key());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(closeWallet(owner)).as("nothing may refund to it any more").isTrue();
    }

    @Test
    @DisplayName("the operator's queue over HTTP (the gate's A7-2): a contest publishes bnpl.BnplDisputeContested; the queue"
            + " needs BNPL_DISPUTE_RESOLVE (403 otherwise), lists the contested dispute with no free text, refuses an"
            + " unknown status 422, never shows an insider their own party's dispute, and records every page")
    void theContestedQueue() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        assertThat(world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response", CONTEST,
                bought.merchant().apiKey(), BnplApplicationClient.key()).statusCode()).isEqualTo(200);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'bnpl.BnplDisputeContested' AND aggregate_id = ?::uuid", dispute)).isEqualTo(1);

        String queue = "/v1/operator/bnpl-disputes?status=MERCHANT_RESPONDED";
        String outsider = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        assertThat(world.client.get(queue, outsider).statusCode()).isEqualTo(403);
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> page = world.client.get(queue, agent);
        assertThat(page.statusCode()).as(page.body()).isEqualTo(200);
        assertThat(page.body()).contains(dispute, "\"status\":\"MERCHANT_RESPONDED\"", "merchantRespondedAt")
                .doesNotContain("it never came").doesNotContain("we shipped it");
        assertThat(world.client.get("/v1/operator/bnpl-disputes?status=WITHDRAWN", agent).statusCode()).isEqualTo(422);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.DisputeQueueRead' AND target_id = 'MERCHANT_RESPONDED'")).isPositive();

        String insider = world.client.sessionWithRole(authorization, bought.owner().login(),
                RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> theirs = world.client.get(queue, insider);
        assertThat(theirs.statusCode()).as(theirs.body()).isEqualTo(200);
        assertThat(theirs.body()).as("never one's own party's").doesNotContain(dispute);
    }

    @Test
    @DisplayName("self-dealing at the operator's read (the gate's A5-F4): an operator whose identity is the dispute's own"
            + " party is 403 bnpl.SelfDealingRefused at GET /v1/operator/bnpl-disputes/{id}, recorded FAILED against the"
            + " dispute, nothing read (no bnpl.DisputeRead)")
    void anInsidersReadIsSelfDealing() throws Exception {
        Bought bought = bought();
        String dispute = BnplApplicationClient.field(open(bought).body(), "disputeId");
        String insider = world.client.sessionWithRole(authorization, bought.owner().login(),
                RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> refused = world.client.get("/v1/operator/bnpl-disputes/" + dispute, insider);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("bnpl.SelfDealingRefused").doesNotContain("it never came");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfDealingRefused' AND outcome = 'FAILED' AND target_id = ?", dispute)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.DisputeRead' AND target_id = ?", dispute)).isZero();
    }

    /** The owner's wallet agreement closed by accounts' real close, as the owner - true when this call ended it. */
    @SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
    private boolean closeWallet(CreditFixtures.Customer owner) {
        try (com.finapp.platform.security.SecurityContext.Scope actor = com.finapp.platform.security.SecurityContext
                .enter(new com.finapp.platform.security.Actor(owner.party().toString(),
                        com.finapp.platform.security.ActorType.CUSTOMER));
                com.finapp.platform.correlation.CorrelationContext.Scope flow = com.finapp.platform.correlation
                        .CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return transactions.inTransaction(uow -> {
                java.util.UUID customer = partyStore.findLiveCustomerFor(uow, com.finapp.party.PartyId.of(owner.party()))
                        .orElseThrow().id().value();
                com.finapp.accounts.CustomerAccount wallet = customerAccountStore.findLive(uow, customer,
                        com.finapp.accounts.ProductType.WALLET).orElseThrow();
                return accountClosing.close(uow, customer, wallet.id()).orElseThrow().closed();
            });
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** A pay-in-4 purchase accepted by a fresh customer at a fresh merchant. */
    record Bought(String purchase, String agreement, BnplApplicationClient.Merchant merchant,
            CreditFixtures.Customer owner) {}

    private Bought bought() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"),
                BnplApplicationClient.field(accepted.body(), "agreementId"), merchant, owner);
    }

    private HttpResponse<String> open(Bought bought) throws Exception {
        return world.client.post("/v1/me/bnpl-agreements/" + bought.agreement() + "/disputes", BODY,
                bought.owner().token(), BnplApplicationClient.key());
    }

    private static long disputes(String purchase) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = ?::uuid",
                purchase);
    }
}
