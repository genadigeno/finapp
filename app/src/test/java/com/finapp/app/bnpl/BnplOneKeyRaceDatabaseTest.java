package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplOneKeyRace.tenUnderOneKey;
import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplServicing;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Every keyed BNPL door under ten requests with one key (`P12-DOC-001`; the Phase 12 gate's Idempotency clause,
 * {@code PHASE_GATES.md} section 5; {@code PHASE_12_PLAN.md} section 9; {@code INV-IDEM-01}; lending's
 * {@code OneKeyRace} precedent): the application, its withdrawal, the acceptance, the decline, the repayment, the payoff
 * quote and the payoff, the dispute's opening and withdrawal, the merchant's refund and dispute response, the operator's
 * dispute resolution (at once, proposed and approved), the waiver and the reversal (proposed and approved) - each raced by
 * ten threads carrying one key, one body and one principal ({@link BnplOneKeyRace#tenUnderOneKey}): one effect counted in
 * its own table, every success answer the one receipt byte for byte, every other answer {@code 409
 * api.IdempotencyInProgress}, and a retry afterwards the same receipt. The terms door and the merchant payout are raced
 * the same way in {@code BnplTermsEndpointDatabaseTest} and {@code BnplMerchantPayoutDatabaseTest}.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it brings credit's BNPL policies and terms into force.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("every keyed BNPL door under ten requests with one key (P12-DOC-001)")
class BnplOneKeyRaceDatabaseTest {

    private static final long GROSS = 600_00L;
    private static final String DISPUTE = "{\"amount\":\"300.00\",\"currency\":\"EUR\",\"category\":\"NOT_RECEIVED\","
            + "\"reason\":\"it never came\"}";
    private static final String CONTEST = "{\"decision\":\"CONTEST\",\"statement\":\"we shipped it\"}";
    private static final String OPERATOR_AGREEMENTS = "/v1/operator/bnpl-agreements/";

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
    @Autowired private BnplServicing servicing;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    // ------------------------------------------------------------------ the customer's doors

    @Test
    @DisplayName("the application: ten under one key are one application, one 202 receipt")
    void theApplication() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        BnplApplicationClient.Opened session = world.client.openSession(world.merchant(), GROSS);
        String body = BnplApplicationClient.payIn4(session.token());
        String key = BnplApplicationClient.key();
        HttpResponse<String> receipt = tenUnderOneKey(202, () -> world.client.submit(customer.token(), body, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application WHERE party_id = ?",
                customer.party())).isEqualTo(1);
        assertThat(receipt.body()).contains(BnplApplicationClient.field(receipt.body(), "applicationId"));
    }

    @Test
    @DisplayName("the application's withdrawal: ten under one key withdraw it once")
    void theWithdrawal() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        BnplApplicationClient.Opened session = world.client.openSession(world.merchant(), GROSS);
        HttpResponse<String> applied = world.client.submit(customer.token(), BnplApplicationClient.payIn4(
                session.token()), BnplApplicationClient.key());
        assertThat(applied.statusCode()).as(applied.body()).isEqualTo(202);
        String application = BnplApplicationClient.field(applied.body(), "applicationId");
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.withdraw(customer.token(), application, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application_event WHERE application_id ="
                + " ?::uuid AND to_status = 'WITHDRAWN'", application)).isEqualTo(1);
    }

    @Test
    @DisplayName("the acceptance: ten under one key are one purchase, one 201 receipt")
    void theAcceptance() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.accept(owner.token(), offer, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.purchase WHERE offer_id = ?::uuid",
                offer.id())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o"
                + " ON o.id = r.obligation_id JOIN bnpl.purchase p ON p.id = o.purchase_id WHERE p.offer_id = ?::uuid",
                offer.id())).as("one instalment collected at purchase").isEqualTo(1);
    }

    @Test
    @DisplayName("the decline: ten under one key decline the offer once")
    void theDecline() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post(BnplPurchaseWorld.OFFERS + "/" + offer.id() + "/decline", null,
                owner.token(), key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_offer_event WHERE offer_id = ?::uuid AND"
                + " to_status = 'DECLINED'", offer.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("the repayment: ten under one key are one repayment")
    void theRepayment() throws Exception {
        Bought bought = bought(1_000_00);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/repayments", "{\"amount\":\"40.00\",\"currency\":\"EUR\"}", bought.owner().token(), key));
        assertThat(repayments(bought.agreement(), "CUSTOMER")).isEqualTo(1);
    }

    @Test
    @DisplayName("the payoff quote: ten under one key are one quote")
    void thePayoffQuote() throws Exception {
        Bought bought = bought(1_000_00);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/payoff-quotes", "{}", bought.owner().token(), key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_payoff_quote WHERE agreement_id ="
                + " ?::uuid", bought.agreement())).isEqualTo(1);
    }

    @Test
    @DisplayName("the payoff: ten under one key are one execution and one PAYOFF repayment")
    void thePayoff() throws Exception {
        Bought bought = bought(1_000_00);
        HttpResponse<String> quoted = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/payoff-quotes", "{}", bought.owner().token(), BnplApplicationClient.key());
        assertThat(quoted.statusCode()).as(quoted.body()).isEqualTo(201);
        String body = "{\"quoteId\":\"" + BnplApplicationClient.field(quoted.body(), "quoteId") + "\"}";
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/payoff", body, bought.owner().token(), key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_payoff_execution e JOIN"
                + " bnpl.bnpl_obligation o ON o.id = e.obligation_id WHERE o.agreement_id = ?::uuid", bought.agreement()))
                .isEqualTo(1);
        assertThat(repayments(bought.agreement(), "PAYOFF")).isEqualTo(1);
    }

    @Test
    @DisplayName("the dispute's opening: ten under one key open one dispute")
    void theDisputesOpening() throws Exception {
        Bought bought = bought(1_000_00);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/disputes", DISPUTE, bought.owner().token(), key));
        assertThat(disputes(bought.purchase())).isEqualTo(1);
    }

    @Test
    @DisplayName("the dispute's withdrawal: ten under one key withdraw it once")
    void theDisputesWithdrawal() throws Exception {
        Bought bought = bought(1_000_00);
        String dispute = opened(bought);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post("/v1/me/bnpl-disputes/" + dispute + "/withdrawal", null,
                bought.owner().token(), key));
        assertThat(disputeEvents(dispute, "WITHDRAWN")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the merchant's doors

    @Test
    @DisplayName("the merchant's refund: ten under one key are one refund")
    void theMerchantsRefund() throws Exception {
        Bought bought = bought(1_000_00);
        String scoped = scopedKey(bought.merchant());
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post("/v1/merchant/bnpl-purchases/" + bought.purchase() + "/refunds",
                "{\"amount\":\"100.00\",\"currency\":\"EUR\",\"reason\":\"returned\"}", scoped, key));
        assertThat(refunds(bought.purchase())).isEqualTo(1);
    }

    @Test
    @DisplayName("the merchant's dispute response: ten under one key answer it once")
    void theMerchantsDisputeResponse() throws Exception {
        Bought bought = bought(1_000_00);
        String dispute = opened(bought);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response", CONTEST,
                bought.merchant().apiKey(), key));
        assertThat(disputeEvents(dispute, "MERCHANT_RESPONDED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the operator's doors

    @Test
    @DisplayName("the operator's resolution at or below the threshold: ten under one key resolve it once")
    void theOperatorsResolution() throws Exception {
        Bought bought = bought(1_000_00);
        HttpResponse<String> opened = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/disputes", "{\"amount\":\"200.00\",\"currency\":\"EUR\",\"category\":\"NOT_AS_DESCRIBED\","
                + "\"reason\":\"not as shown\"}", bought.owner().token(), BnplApplicationClient.key());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String dispute = BnplApplicationClient.field(opened.body(), "disputeId");
        contested(bought, dispute);
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution",
                "{\"outcome\":\"UPHOLD\",\"reason\":\"the evidence holds\"}", agent, key));
        assertThat(disputeEvents(dispute, "RESOLVED_UPHELD")).isEqualTo(1);
    }

    @Test
    @DisplayName("the operator's resolution above the threshold: ten under one key are one proposal")
    void theOperatorsProposal() throws Exception {
        Bought bought = bought(1_000_00);
        String dispute = opened(bought);
        contested(bought, dispute);
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution",
                "{\"outcome\":\"REFUND\",\"reason\":\"the parcel never arrived\"}", agent, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute_proposal WHERE dispute_id ="
                + " ?::uuid", dispute)).isEqualTo(1);
    }

    @Test
    @DisplayName("the operator's approval of a proposed resolution: ten under one key approve it once - one refund")
    void theOperatorsApproval() throws Exception {
        Bought bought = bought(1_000_00);
        String dispute = opened(bought);
        contested(bought, dispute);
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> proposed = world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution",
                "{\"outcome\":\"REFUND\",\"reason\":\"the parcel never arrived\"}", proposer, BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        String decision = "{\"proposalId\":\"" + BnplApplicationClient.field(proposed.body(), "proposalId")
                + "\",\"reason\":\"checked\"}";
        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post("/v1/operator/bnpl-disputes/" + dispute + "/resolution/approval",
                decision, approver, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE dispute_id = ?::uuid",
                dispute)).isEqualTo(1);
        assertThat(disputeEvents(dispute, "RESOLVED_REFUND")).isEqualTo(1);
    }

    @Test
    @DisplayName("the waiver's proposal: ten under one key are one waiver")
    void theWaiversProposal() throws Exception {
        Owing owing = owing();
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(owing.waivers(), owing.waiver(), agent, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_waiver WHERE fee_assessment_id = ?::uuid",
                owing.fee())).isEqualTo(1);
    }

    @Test
    @DisplayName("the waiver's approval: ten under one key are one decision and one posting")
    void theWaiversApproval() throws Exception {
        Owing owing = owing();
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> proposed = world.client.post(owing.waivers(), owing.waiver(), proposer,
                BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String waiver = BnplApplicationClient.field(proposed.body(), "waiverId");
        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post(owing.waivers() + "/" + waiver + "/approval",
                "{\"reason\":\"checked\"}", approver, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_waiver_decision WHERE waiver_id ="
                + " ?::uuid AND outcome = 'APPROVED' AND journal_entry_id IS NOT NULL", waiver)).isEqualTo(1);
    }

    @Test
    @DisplayName("the reversal's proposal: ten under one key are one reversal")
    void theReversalsProposal() throws Exception {
        Bought bought = bought(1_000_00);
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(201, () -> world.client.post(reversal(bought), "{\"reason\":\"applied twice\"}", agent, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment_reversal WHERE repayment_id ="
                + " ?::uuid", atPurchase(bought))).isEqualTo(1);
    }

    @Test
    @DisplayName("the reversal's approval: ten under one key are one REVERSAL entry")
    void theReversalsApproval() throws Exception {
        Bought bought = bought(1_000_00);
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        HttpResponse<String> proposed = world.client.post(reversal(bought), "{\"reason\":\"applied twice\"}", proposer,
                BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String decision = "{\"reversalId\":\"" + BnplApplicationClient.field(proposed.body(), "reversalId")
                + "\",\"reason\":\"checked\"}";
        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String key = BnplApplicationClient.key();
        tenUnderOneKey(200, () -> world.client.post(reversal(bought) + "/approval", decision, approver, key));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_entry WHERE entry_type = 'REVERSAL'"
                + " AND reverses_entry_id = (SELECT journal_entry_id FROM bnpl.bnpl_repayment WHERE id = ?::uuid)",
                atPurchase(bought))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ plumbing

    /** A pay-in-4 purchase accepted by a fresh customer at a fresh merchant. */
    record Bought(String purchase, String agreement, BnplApplicationClient.Merchant merchant,
            CreditFixtures.Customer owner) {}

    private Bought bought(long walletMinor) throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(walletMinor));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"),
                BnplApplicationClient.field(accepted.body(), "agreementId"), merchant, owner);
    }

    /** An agreement owing a real late fee, and the waiver body forgiving all of it. */
    record Owing(String agreement, String fee) {

        String waivers() {
            return OPERATOR_AGREEMENTS + agreement + "/waivers";
        }

        String waiver() {
            return "{\"assessmentId\":\"" + fee + "\",\"amount\":\"5.00\",\"currency\":\"EUR\",\"reason\":\"goodwill\"}";
        }
    }

    private Owing owing() throws Exception {
        Bought bought = bought(150_00);
        return new Owing(bought.agreement(), BnplLateFee.assessed(transactions, servicing, bought.agreement()));
    }

    /** The customer's dispute of 300.00 - above the pinned four-eyes threshold of 250.00. */
    private String opened(Bought bought) throws Exception {
        HttpResponse<String> opened = world.client.post(BnplPurchaseWorld.AGREEMENTS + "/" + bought.agreement()
                + "/disputes", DISPUTE, bought.owner().token(), BnplApplicationClient.key());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return BnplApplicationClient.field(opened.body(), "disputeId");
    }

    private void contested(Bought bought, String dispute) throws Exception {
        HttpResponse<String> answered = world.client.post("/v1/merchant/bnpl-disputes/" + dispute + "/response",
                CONTEST, bought.merchant().apiKey(), BnplApplicationClient.key());
        assertThat(answered.statusCode()).as(answered.body()).isEqualTo(200);
    }

    private String scopedKey(BnplApplicationClient.Merchant merchant) throws Exception {
        // MERCHANT_ADMINISTER beside PAYMENT_REFUND (the Phase 12 -> 13 gate's A5-F1).
        String operator = world.client.refundKeyIssuerSession(authorization);
        HttpResponse<String> issued = world.client.post("/v1/operator/merchants/" + merchant.id()
                + "/api-keys?scope=BNPL_REFUND", null, operator, BnplApplicationClient.key());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return BnplApplicationClient.field(issued.body(), "keyId") + "." + BnplApplicationClient.field(issued.body(),
                "secret");
    }

    private static String atPurchase(Bought bought) throws Exception {
        return BnplApplicationClient.scalar("SELECT r.id::text FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o ON"
                + " o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = 'AT_PURCHASE'", bought.agreement());
    }

    private static String reversal(Bought bought) throws Exception {
        return OPERATOR_AGREEMENTS + bought.agreement() + "/repayments/" + atPurchase(bought) + "/reversal";
    }

    private static long repayments(String agreement, String kind) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment r JOIN bnpl.bnpl_obligation o ON"
                + " o.id = r.obligation_id WHERE o.agreement_id = ?::uuid AND r.kind = ?", agreement, kind);
    }

    private static long disputes(String purchase) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute WHERE purchase_id = ?::uuid",
                purchase);
    }

    private static long disputeEvents(String dispute, String to) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_dispute_event WHERE dispute_id = ?::uuid AND"
                + " to_status = ?", dispute, to);
    }

    private static long refunds(String purchase) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid",
                purchase);
    }
}
