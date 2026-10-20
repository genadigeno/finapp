package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

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
 * The BNPL investigator's doors' contract (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 9): {@code GET
 * /v1/operator/bnpl-agreements/{id}} and {@code /explanation} and {@code POST .../replay} - behind {@code BNPL_INVESTIGATE}
 * ({@code 403} without it, the passing negative), each served audited ({@code bnpl.AgreementRead},
 * {@code bnpl.ExplanationRead}, {@code bnpl.Replayed}); an investigator of the agreement's own party refused {@code 403
 * bnpl.SelfDealingRefused} and recorded {@code FAILED}; an unknown or malformed agreement the uniform {@code 404}; a replay
 * without a reason {@code 422 bnpl.ReasonRequired} before anything is read.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}). Written by `P12-TSK-025` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL investigator's doors' contract (P12-TSK-025)")
class BnplProofApiTest {

    private static final long GROSS = 600_00L;
    private static final String REASON = "{\"reason\":\"quarterly reconciliation\"}";

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
    @DisplayName("without BNPL_INVESTIGATE every door is 403; with it the read, the explanation and the replay answer -"
            + " each recorded once, the explanation from its rows with every entry named, the replay IDENTICAL")
    void theDoorsArePermissionedAndAudited() throws Exception {
        Bought bought = bought();
        String outsider = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String base = "/v1/operator/bnpl-agreements/" + bought.agreement();
        assertThat(world.client.get(base, outsider).statusCode()).isEqualTo(403);
        assertThat(world.client.get(base + "/explanation", outsider).statusCode()).isEqualTo(403);
        assertThat(world.client.post(base + "/replay", REASON, outsider, null).statusCode()).isEqualTo(403);

        String officer = world.client.operatorSession(authorization, RoleName.BNPL_OFFICER);
        HttpResponse<String> read = world.client.get(base, officer);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"agreementVersion\":1", "\"subledgerVerdict\":\"HOLDS\"", "\"gross\":\"600.00\"");
        HttpResponse<String> explained = world.client.get(base + "/explanation", officer);
        assertThat(explained.statusCode()).as(explained.body()).isEqualTo(200);
        assertThat(explained.body()).contains("\"explainedBy\":\"FINANCING\"", "\"subledgerVerdict\":\"HOLDS\"")
                .doesNotContain("UNEXPLAINED");
        HttpResponse<String> replayed = world.client.post(base + "/replay", REASON, officer, null);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(replayed.body()).contains("\"verdict\":\"IDENTICAL\"");
        for (String operation : List.of("bnpl.AgreementRead", "bnpl.ExplanationRead", "bnpl.Replayed")) {
            assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation = ? AND"
                    + " target_id = ?", operation, bought.agreement())).as(operation).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("an investigator of the agreement's own party is 403 bnpl.SelfDealingRefused at every door, each refusal"
            + " recorded FAILED and nothing served")
    void selfDealingIsRefusedAndRecorded() throws Exception {
        Bought bought = bought();
        String insider = world.client.sessionWithRole(authorization, bought.owner().login(), RoleName.BNPL_OFFICER);
        String base = "/v1/operator/bnpl-agreements/" + bought.agreement();
        for (HttpResponse<String> refused : List.of(world.client.get(base, insider),
                world.client.get(base + "/explanation", insider), world.client.post(base + "/replay", REASON, insider,
                        null))) {
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
            assertThat(refused.body()).contains("bnpl.SelfDealingRefused");
        }
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfDealingRefused' AND outcome = 'FAILED' AND target_id = ?", bought.agreement())).isEqualTo(3);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation IN"
                + " ('bnpl.AgreementRead', 'bnpl.ExplanationRead', 'bnpl.Replayed') AND target_id = ?",
                bought.agreement())).isZero();
    }

    @Test
    @DisplayName("an unknown or malformed agreement is the uniform 404 bnpl.NotFound; a replay without a reason is 422"
            + " bnpl.ReasonRequired, nothing read or recorded")
    void unknownAgreementsAndMissingReasons() throws Exception {
        Bought bought = bought();
        String officer = world.client.operatorSession(authorization, RoleName.BNPL_OFFICER);
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> unknown = world.client.get("/v1/operator/bnpl-agreements/" + id + "/explanation",
                    officer);
            assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(404);
            assertThat(unknown.body()).contains("bnpl.NotFound");
        }
        HttpResponse<String> reasonless = world.client.post("/v1/operator/bnpl-agreements/" + bought.agreement()
                + "/replay", "{\"reason\":\" \"}", officer, null);
        assertThat(reasonless.statusCode()).as(reasonless.body()).isEqualTo(422);
        assertThat(reasonless.body()).contains("bnpl.ReasonRequired");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.Replayed' AND target_id = ?", bought.agreement())).isZero();
    }

    // ------------------------------------------------------------------

    record Bought(String purchase, String agreement, CreditFixtures.Customer owner) {}

    private Bought bought() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        return new Bought(BnplApplicationClient.field(accepted.body(), "purchaseId"),
                BnplApplicationClient.field(accepted.body(), "agreementId"), owner);
    }
}
