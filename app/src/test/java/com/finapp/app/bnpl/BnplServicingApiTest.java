package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
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
import com.finapp.platform.testing.database.DatabaseRoles;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The BNPL servicing corrections' doors over the real ledger (`P12-TSK-024`; {@code PHASE_12_PLAN.md} section 9;
 * {@code INV-REV-01}, {@code INV-REV-02}, {@code INV-AUD-04}, {@code INV-CRD-09}): the route register's negatives - a
 * session without {@code BNPL_SERVICE} or {@code BNPL_SERVICE_APPROVE} is {@code 403} on every correction door, nothing
 * written; pay-in-4's instalment 1 collected at purchase reversed under four eyes - the proposer's own approval
 * {@code 403 bnpl.SelfApprovalRefused} recorded {@code FAILED}, another agent's approval posting the ledger's
 * {@code REVERSAL} of F3 exactly, line for line, the wallet credited back on the real ledger, the receipt replayed under the
 * same key; an unknown agreement the uniform {@code 404}; and L0 taken first - with the party's real credit profile held
 * by a rival the approval waits holding no obligation row.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}). Written by `P12-TSK-024` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL servicing correction doors over the real ledger (P12-TSK-024)")
class BnplServicingApiTest {

    private static final long GROSS = 600_00L;
    private static final String AGREEMENTS = "/v1/operator/bnpl-agreements/";

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

    /** A pay-in-4 purchase: its agreement, the instalment 1 repayment collected at purchase, and the customer's party. */
    record Bought(String agreement, String repayment, String obligation, UUID party) {}

    private Bought bought() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        String obligation = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id ="
                + " ?::uuid", agreement);
        String repayment = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_repayment WHERE obligation_id ="
                + " ?::uuid AND kind = 'AT_PURCHASE'", obligation);
        return new Bought(agreement, repayment, obligation, owner.party());
    }

    @Test
    @DisplayName("the route register's negatives: a session without BNPL_SERVICE or BNPL_SERVICE_APPROVE is 403 on every"
            + " correction door, nothing written")
    void theDoorsAreGuarded() throws Exception {
        Bought bought = bought();
        String unprivileged = world.client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        String reversal = AGREEMENTS + bought.agreement() + "/repayments/" + bought.repayment() + "/reversal";
        String waivers = AGREEMENTS + bought.agreement() + "/waivers";
        String decision = "{\"reversalId\":\"" + UUID.randomUUID() + "\",\"reason\":\"r\"}";
        for (String[] door : List.of(
                new String[] {reversal, "{\"reason\":\"r\"}"},
                new String[] {reversal + "/approval", decision},
                new String[] {reversal + "/rejection", decision},
                new String[] {waivers, "{\"assessmentId\":\"" + UUID.randomUUID()
                        + "\",\"amount\":\"1.00\",\"currency\":\"EUR\",\"reason\":\"r\"}"},
                new String[] {waivers + "/" + UUID.randomUUID() + "/approval", "{\"reason\":\"r\"}"},
                new String[] {waivers + "/" + UUID.randomUUID() + "/rejection", "{\"reason\":\"r\"}"})) {
            HttpResponse<String> refused = world.client.post(door[0], door[1], unprivileged, BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(door[0] + " " + refused.body()).isEqualTo(403);
        }
        assertThat(BnplApplicationClient.count("SELECT (SELECT count(*) FROM bnpl.bnpl_repayment_reversal)"
                + " + (SELECT count(*) FROM bnpl.bnpl_waiver) WHERE true")).isZero();
    }

    @Test
    @DisplayName("instalment 1 reversed under four eyes over the real ledger: the proposer's own approval 403 and recorded"
            + " FAILED; another agent's approval posts the REVERSAL of F3 line for line, the wallet credited back, the"
            + " instalment due again; the same key replays the receipt")
    void aReversalOverTheRealLedger() throws Exception {
        Bought bought = bought();
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String reversal = AGREEMENTS + bought.agreement() + "/repayments/" + bought.repayment() + "/reversal";
        long walletBefore = BnplPurchaseWorld.creditBalanceOf(world.walletOf(bought.party()).value());
        HttpResponse<String> proposed = world.client.post(reversal, "{\"reason\":\"applied twice\"}", proposer,
                BnplApplicationClient.key());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String id = BnplApplicationClient.field(proposed.body(), "reversalId");
        String decision = "{\"reversalId\":\"" + id + "\",\"reason\":\"checked\"}";
        HttpResponse<String> own = world.client.post(reversal + "/approval", decision, proposer,
                BnplApplicationClient.key());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("bnpl.SelfApprovalRefused");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'bnpl.SelfApprovalRefused' AND outcome = 'FAILED' AND target_id = ?", id)).isEqualTo(1);
        String key = BnplApplicationClient.key();
        HttpResponse<String> approved = world.client.post(reversal + "/approval", decision, approver, key);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"");
        String entry = BnplApplicationClient.field(approved.body(), "journalEntryId");
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_entry WHERE id = ?::uuid AND"
                + " entry_type = 'REVERSAL' AND reverses_entry_id = (SELECT journal_entry_id FROM bnpl.bnpl_repayment"
                + " WHERE id = ?::uuid)", entry, bought.repayment())).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_line r JOIN ledger.journal_line o ON"
                + " o.ledger_account_id = r.ledger_account_id AND o.amount_minor = r.amount_minor AND o.direction <> r.direction"
                + " WHERE r.entry_id = ?::uuid AND o.entry_id = (SELECT journal_entry_id FROM bnpl.bnpl_repayment WHERE id"
                + " = ?::uuid)", entry, bought.repayment())).isEqualTo(2);
        assertThat(BnplPurchaseWorld.creditBalanceOf(world.walletOf(bought.party()).value()))
                .isEqualTo(walletBefore + 150_00);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_repayment_allocation_reversal n"
                + " JOIN bnpl.bnpl_repayment_allocation a ON a.id = n.allocation_id WHERE a.repayment_id = ?::uuid",
                bought.repayment())).isEqualTo(1);
        HttpResponse<String> replayed = world.client.post(reversal + "/approval", decision, approver, key);
        assertThat(replayed.statusCode()).isEqualTo(200);
        assertThat(BnplApplicationClient.field(replayed.body(), "journalEntryId")).isEqualTo(entry);
    }

    @Test
    @DisplayName("an unknown or malformed agreement, and another agreement's repayment, are the uniform 404")
    void unknownTargetsAreNotFound() throws Exception {
        Bought bought = bought();
        Bought other = bought();
        String agent = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        for (String path : List.of(AGREEMENTS + UUID.randomUUID() + "/repayments/" + bought.repayment() + "/reversal",
                AGREEMENTS + "not-a-uuid/repayments/" + bought.repayment() + "/reversal",
                AGREEMENTS + other.agreement() + "/repayments/" + bought.repayment() + "/reversal")) {
            HttpResponse<String> absent = world.client.post(path, "{\"reason\":\"r\"}", agent, BnplApplicationClient.key());
            assertThat(absent.statusCode()).as(path + " " + absent.body()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("L0 first: with the party's real credit profile held by a rival, the approval waits holding no obligation"
            + " row (a NOWAIT lock of it succeeds), and completes once the rival commits")
    void reversalTakesTheProfileFirst() throws Exception {
        Bought bought = bought();
        String proposer = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String approver = world.client.operatorSession(authorization, RoleName.BNPL_SERVICING_AGENT);
        String reversal = AGREEMENTS + bought.agreement() + "/repayments/" + bought.repayment() + "/reversal";
        String id = BnplApplicationClient.field(world.client.post(reversal, "{\"reason\":\"applied twice\"}", proposer,
                BnplApplicationClient.key()).body(), "reversalId");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection rival = DatabaseRoles.application()) {
            rival.setAutoCommit(false);
            try (Statement lock = rival.createStatement()) {
                lock.execute("SELECT 1 FROM credit.credit_profile WHERE party_id = '" + bought.party() + "' FOR UPDATE");
            }
            Future<HttpResponse<String>> approving = pool.submit(() -> world.client.post(reversal + "/approval",
                    "{\"reversalId\":\"" + id + "\",\"reason\":\"checked\"}", approver, BnplApplicationClient.key()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (BnplApplicationClient.count("SELECT count(*) FROM pg_locks l JOIN pg_class c ON c.oid = l.relation"
                    + " WHERE c.relname = 'credit_profile' AND NOT l.granted") == 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            try (Connection probe = DatabaseRoles.application(); Statement nowait = probe.createStatement()) {
                probe.setAutoCommit(false);
                nowait.execute("SELECT 1 FROM bnpl.bnpl_obligation WHERE id = '" + bought.obligation()
                        + "' FOR UPDATE NOWAIT");
                probe.rollback();
            }
            assertThat(approving.isDone()).isFalse();
            rival.commit();
            assertThat(approving.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
    }
}
