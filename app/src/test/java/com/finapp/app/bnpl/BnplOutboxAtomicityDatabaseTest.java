package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.bnpl.BnplAcceptances;
import com.finapp.bnpl.BnplApplications;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The purchase's events are its state change's (`P12-TSK-013`; {@code INV-BNPL-02}, {@code INV-EVT-04}): every event of
 * an acceptance - {@code bnpl.BnplAgreementCreated}, {@code bnpl.MerchantFinanced}, pay-in-4's {@code bnpl.InstalmentDue}
 * and {@code bnpl.InstalmentPaid}, checkout's {@code checkout.OrderPaid} and the ledger's posting events - is an outbox
 * row in the purchase's one transaction, so a crash between the commit and the relay's publication loses nothing (the
 * relay re-reads unpublished rows) and a refused acceptance leaves none. (A failure injected after the postings is
 * {@code BnplPurchaseDatabaseTest#aFailureAtEveryStepLeavesNothing}'s.)
 *
 * <p><strong>A database of its own</strong> ({@code own-container}). Written by `P12-TSK-013` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction for the pair).
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the purchase's events are in its one transaction (P12-TSK-013)")
class BnplOutboxAtomicityDatabaseTest {

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
        world.headroomAtLeast(eur(1_000_000_00L));
    }

    @Test
    @DisplayName("a crash between posting and publication loses nothing: the acceptance's events are unpublished outbox"
            + " rows committed with the purchase - the agreement, the financing, instalment 1 due and paid, the order -"
            + " and a refused acceptance after its postings leaves no row of its own")
    void aCrashBetweenPostingAndPublicationLosesNothing() throws Exception {
        CreditFixtures.Customer customer = world.customers.consentingCustomer();
        world.walletOf(customer.party());
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(customer, merchant, GROSS, BnplApplicationClient::payIn4);

        // Refused at (P): the wallet holds nothing - the whole act rolls back, its events with it. The origination
        // before it committed its own events naming the session (the application, its eligibility and the offer), so
        // the refusal is judged by what it adds: no row of any type, and none of the acceptance's (P12-TSK-028).
        long before = outboxRows();
        long naming = sessionEvents(offer);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type LIKE 'bnpl.%'"
                + " AND event_type NOT IN (?, ?, ?) AND convert_from(payload, 'UTF8') LIKE ?",
                BnplApplications.SUBMITTED_EVENT, BnplOrigination.ELIGIBILITY_EVENT, BnplOrigination.OFFERED_EVENT,
                "%" + offer.session() + "%")).as("before the acceptance, only the origination's events name the session")
                .isZero();
        HttpResponse<String> refused = world.accept(customer.token(), offer, BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(outboxRows()).as("a refused acceptance leaves no outbox row of its own").isEqualTo(before);
        assertThat(sessionEvents(offer)).as("no event naming the session beyond the origination's").isEqualTo(naming);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type IN (?, ?, ?,"
                + " ?) AND convert_from(payload, 'UTF8') LIKE ?", BnplAcceptances.AGREEMENT_CREATED_EVENT,
                BnplAcceptances.MERCHANT_FINANCED_EVENT, BnplAcceptances.INSTALMENT_DUE_EVENT,
                BnplAcceptances.INSTALMENT_PAID_EVENT, "%" + offer.session() + "%")).isZero();

        world.topUpWallet(customer.party(), eur(1_000_00));
        HttpResponse<String> accepted = world.accept(customer.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        String purchase = BnplApplicationClient.field(accepted.body(), "purchaseId");
        String obligation = BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id ="
                + " ?::uuid", agreement);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                + " aggregate_id = ?::uuid", BnplAcceptances.AGREEMENT_CREATED_EVENT, agreement)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ? AND"
                + " aggregate_id = ?::uuid", BnplAcceptances.MERCHANT_FINANCED_EVENT, purchase)).isEqualTo(1);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type IN (?, ?)"
                + " AND aggregate_id = ?::uuid", BnplAcceptances.INSTALMENT_DUE_EVENT,
                BnplAcceptances.INSTALMENT_PAID_EVENT, obligation)).isEqualTo(2);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'checkout.OrderPaid' AND convert_from(payload, 'UTF8') LIKE ?", "%FINANCED%")).isPositive();
    }

    private static long outboxRows() throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event");
    }

    private static long sessionEvents(BnplPurchaseWorld.Offer offer) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM platform.outbox_event WHERE event_type LIKE 'bnpl.%' AND"
                + " convert_from(payload, 'UTF8') LIKE ?", "%" + offer.session() + "%");
    }
}
