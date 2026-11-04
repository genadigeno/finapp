package com.finapp.app.bnpl;

import static com.finapp.app.bnpl.BnplPurchaseWorld.eur;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.api.DecimalText;
import com.finapp.app.database.DateShifted;
import com.finapp.app.lending.LendingTransactions;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.app.credit.BnplCreditExposure;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplOrigination;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplRepayments;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplServicing;
import com.finapp.bnpl.BnplTermsAdministration;
import com.finapp.bnpl.BnplWrittenOffRefused;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingService;
import com.finapp.lending.LendingCapitalHeadroom;

import com.finapp.lending.LoanWallets;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeSchedules;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A BNPL write-off over the real ledger, through its own desk (`P14-TSK-021`; ADR-0103 F11, ADR-0104's Follow-up; {@code INV-LND-13},
 * {@code INV-BNPL-01}): the headroom unchanged by the loss for BNPL funded from lending capital, the proofs still holding, the credit
 * exposure still counting, and the keys - same key replays, another body conflicts, a retry after a lost response answers the recorded
 * receipt and posts nothing more. Instalments are aged by the owner-role date shift of {@code BnplCollectionLedgerDatabaseTest}.
 *
 * <p>OWED (C5): written and walked by hand, not yet run. It commits ledger lines, so it takes a container of its own.
 */
@DateShifted
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try") // The Scopes are used for their close side effect (the idiom).
@DisplayName("a BNPL write-off over the real ledger (P14-TSK-021)")
class BnplWriteOffLedgerDatabaseTest {

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
    @Autowired private BnplServicing servicing;
    @Autowired private BnplRepayments repayments;
    @Autowired private BnplProofs proofs;
    @Autowired private BnplCreditExposure exposure;
    @Autowired private LendingTransactions lendingTransactions;
    @Autowired private BnplWriteOffDesk desk;
    @Autowired private com.finapp.platform.audit.AuditWriter<Connection> auditWriter;
    @Autowired private com.finapp.sharedkernel.id.IdGenerator idGenerator;

    private BnplPurchaseWorld world;

    @BeforeEach
    void world() throws Exception {
        world = new BnplPurchaseWorld(port, authorization, enablements, pricing, feeSchedules, terms, transactions,
                origination, postingService, ledgerAccountStore, headroom, loanWallets).ready();
        world.headroomAtLeast(eur(100_000_00L));
    }

    @Test
    @DisplayName("the headroom does not rise for BNPL funded from lending capital: the principal leaves the outstanding term and enters the"
            + " written-off term from the journal, the capital carries no line, safeguarding's figure stands, the proofs hold, and the"
            + " customer's exposure still counts what stands owed")
    void theHeadroomDoesNotRise() throws Exception {
        Bought bought = defaulted();
        LendingCapitalHeadroom.Headroom before = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, BnplPurchaseWorld.EUR));
        Money exposed = exposureOf(bought);
        BnplWriteOffDesk.WriteOffReceipt proposed = as(operator(), () -> desk.propose(bought.agreementText(), BnplApplicationClient.key(),
                "uncollectable after collections"));
        as(operator(), () -> desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed)));

        LendingCapitalHeadroom.Headroom after = lendingTransactions.inTransaction(uow -> headroom.atRest(uow, BnplPurchaseWorld.EUR));
        Money principal = Money.of(DecimalText.parse(proposed.principalTotal()), BnplPurchaseWorld.EUR);
        assertThat(principal.isPositive()).isTrue();
        assertThat(after.headroom()).as("unchanged by the loss").isEqualTo(before.headroom());
        assertThat(after.capital()).isEqualTo(before.capital());
        assertThat(before.outstanding().minus(after.outstanding())).isEqualTo(principal);
        assertThat(after.writtenOff().minus(before.writtenOff())).isEqualTo(principal);
        assertThat(after.lendingFunded()).isEqualTo(before.lendingFunded());
        assertThat(exposureOf(bought)).as("a write-off is not forgiveness: credit exposure v4 still counts it").isEqualTo(exposed);

        BnplProofs.Proven proven = transactions.inSnapshot(snapshot -> proofs.prove(snapshot, bought.obligation())).orElseThrow();
        assertThat(proven.subledger().holds()).as("%s", proven.subledger()).isTrue();
        assertThat(proven.replay().verdict()).as("%s", proven.replay().findings()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
    }

    @Test
    @DisplayName("the key: the same key replays the same receipt and posts once (a timeout's retry and a response lost after the commit are"
            + " this); the same key with another body conflicts; approval and rejection cannot both stand")
    void theKeyReplaysAndAnotherBodyConflicts() throws Exception {
        Bought bought = defaulted();
        Actor proposer = operator();
        String proposalKey = BnplApplicationClient.key();
        BnplWriteOffDesk.WriteOffReceipt proposed = as(proposer, () -> desk.propose(bought.agreementText(), proposalKey, "uncollectable"));
        assertThat(as(proposer, () -> desk.propose(bought.agreementText(), proposalKey, "uncollectable"))).isEqualTo(proposed);
        assertThatExceptionOfType(IdempotencyConflictException.class)
                .isThrownBy(() -> as(proposer, () -> desk.propose(bought.agreementText(), proposalKey, "another reason")));

        Actor approver = operator();
        String approvalKey = BnplApplicationClient.key();
        BnplWriteOffDesk.WriteOffReceipt approved = as(approver, () ->
                desk.approve(bought.agreementText(), proposed.writeOffId(), approvalKey, attesting(proposed)));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.journalEntryId()).isNotNull();
        assertThat(as(approver, () -> desk.approve(bought.agreementText(), proposed.writeOffId(), approvalKey, attesting(proposed))))
                .as("the retry after a lost response answers the recorded receipt").isEqualTo(approved);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = ?",
                "ledger.post:bnpl.write-off:" + bought.obligationText())).isEqualTo(1);
        assertThatExceptionOfType(IdempotencyConflictException.class).isThrownBy(() -> as(approver, () ->
                desk.approve(bought.agreementText(), proposed.writeOffId(), approvalKey, new BnplWriteOffController.BnplWriteOffApprovalRequest(
                        "EUR", proposed.principal(), proposed.principalDue(), proposed.interestAccrued(), proposed.interestDue(),
                        proposed.feesDue(), proposed.revision() + 1, "read and agreed"))));
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_write_off_decision WHERE write_off_id = ?::uuid",
                proposed.writeOffId())).isEqualTo(1);
    }

    @Test
    @DisplayName("after it, money arriving is refused into nowhere never: a repayment is refused and the wallet keeps it; the proofs hold")
    void aRepaymentAfterItLeavesTheWalletWhole() throws Exception {
        Bought bought = defaulted();
        BnplWriteOffDesk.WriteOffReceipt proposed = as(operator(), () -> desk.propose(bought.agreementText(), BnplApplicationClient.key(),
                "uncollectable after collections"));
        as(operator(), () -> desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed)));
        // Money the customer brings afterwards: defaulted() left the wallet empty so the step could not collect the debt.
        world.topUpWallet(bought.party(), eur(100_00));
        long wallet = BnplPurchaseWorld.creditBalanceOf(bought.wallet().value());
        Actor customer = new Actor(bought.party().toString(), ActorType.CUSTOMER);
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> as(customer, () -> transactions.inTransaction(uow ->
                repayments.repay(uow, bought.agreement(), bought.party(), new BnplRepayments.Request(BnplApplicationClient.key(), eur(1_00)),
                        customer, BnplApplicationClient.correlation()))));
        assertThat(BnplPurchaseWorld.creditBalanceOf(bought.wallet().value())).isEqualTo(wallet);
    }

    @Test
    @DisplayName("a merchant learns nothing of it over HTTP: its refund is the neutral 409 naming no cause, its purchase read says nothing of the"
            + " customer's agreement; the operator's refund door keeps bnpl.ObligationWrittenOff; the payable and the refund table do not move")
    void aMerchantLearnsNothingOfIt() throws Exception {
        Bought bought = defaulted();
        String scoped = scopedKey(bought.merchant());
        BnplWriteOffDesk.WriteOffReceipt proposed = as(operator(), () -> desk.propose(bought.agreementText(), BnplApplicationClient.key(),
                "uncollectable after collections"));
        as(operator(), () -> desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed)));
        String refund = "{\"amount\":\"10.00\",\"currency\":\"EUR\",\"reason\":\"goods returned\"}";
        long payable = BnplPurchaseWorld.creditBalanceOf(BnplPurchaseWorld.payableOf(bought.merchant()));

        HttpResponse<String> refused = world.client.post("/v1/merchant/bnpl-purchases/" + bought.purchase() + "/refunds", refund, scoped,
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("bnpl.RefundNotPostable");
        for (String word : List.of("written off", "WrittenOff", "WRITTEN", "default", "owed", "agreement", "retry")) {
            assertThat(refused.body()).as(word).doesNotContain(word);
        }
        HttpResponse<String> read = world.client.get("/v1/merchant/bnpl-purchases/" + bought.purchase(), scoped);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).doesNotContain("WRITTEN").doesNotContain("written").contains("FINANCED");

        String operatorSession = world.client.operatorSession(authorization, RoleName.LEDGER_OPERATOR);
        HttpResponse<String> operatorRefused = world.client.post("/v1/operator/bnpl-purchases/" + bought.purchase() + "/refunds", refund,
                operatorSession, BnplApplicationClient.key());
        assertThat(operatorRefused.statusCode()).as(operatorRefused.body()).isEqualTo(409);
        assertThat(operatorRefused.body()).contains("bnpl.ObligationWrittenOff");

        assertThat(BnplPurchaseWorld.creditBalanceOf(BnplPurchaseWorld.payableOf(bought.merchant()))).isEqualTo(payable);
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_refund WHERE purchase_id = ?::uuid", bought.purchase())).isZero();
    }

    @Test
    @DisplayName("the portfolio reads it as of its date: before the write-off the obligation is active, on its date it is written off, and an earlier"
            + " date's report does not move")
    void thePortfolioReadsItAsOfItsDate() throws Exception {
        Bought bought = defaulted();
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        BnplReports.BnplPortfolioRow yesterdayBefore = payIn4(today.minusDays(1));
        BnplReports.BnplPortfolioRow todayBefore = payIn4(today);
        BnplWriteOffDesk.WriteOffReceipt proposed = as(operator(), () -> desk.propose(bought.agreementText(), BnplApplicationClient.key(),
                "uncollectable after collections"));
        as(operator(), () -> desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed)));

        assertThat(payIn4(today.minusDays(1))).as("an earlier date is what it was").isEqualTo(yesterdayBefore);
        assertThat(yesterdayBefore.writtenOff()).as("nothing is written off before its date").isZero();
        BnplReports.BnplPortfolioRow todayAfter = payIn4(today);
        assertThat(todayAfter.writtenOff()).isEqualTo(todayBefore.writtenOff() + 1);
        assertThat(todayAfter.active()).isEqualTo(todayBefore.active() - 1);
    }

    @Test
    @DisplayName("a month locked over today refuses the approval with nothing written and the proposal standing; it posts once the lock is gone. The"
            + " platform's own moves never cover today (INV-ACC-08), so the lock is planted by the owner role: the refusal is the real trigger's")
    void aMonthLockedOverTodayRefusesAndTheProposalStands() throws Exception {
        Bought bought = defaulted();
        BnplWriteOffDesk.WriteOffReceipt proposed = as(operator(), () -> desk.propose(bought.agreementText(), BnplApplicationClient.key(),
                "uncollectable after collections"));
        Actor approver = operator();
        cutoff("ledger.posting_cutoff_month_end((now() AT TIME ZONE 'UTC')::date)");
        try {
            assertThatExceptionOfType(PeriodClosedException.class).isThrownBy(() -> as(approver, () ->
                    desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed))));
            assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_obligation WHERE id = ?::uuid", bought.obligationText())).isEqualTo("ACTIVE");
            assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_write_off_decision WHERE write_off_id = ?::uuid",
                    proposed.writeOffId())).isZero();
        } finally {
            cutoff("NULL");
        }
        assertThat(as(approver, () -> desk.approve(bought.agreementText(), proposed.writeOffId(), BnplApplicationClient.key(), attesting(proposed)))
                .status()).as("the proposal stood").isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ plumbing

    private BnplReports.BnplPortfolioRow payIn4(LocalDate day) {
        BnplReports reports = new BnplReports(BnplReports.over(transactions), new JdbcBnplReportReads(), proofs::prove, auditWriter, idGenerator,
                java.time.Clock.systemUTC());
        return as(new Actor("bnpl-investigator", ActorType.EMPLOYEE), () -> reports.portfolio(day.toString())).rows().stream()
                .filter(row -> row.product().equals("bnpl_pay_in_4")).findFirst().orElseThrow();
    }

    /** The owner role plants the cutoff row, its triggers off for this one transaction: the platform's own moves never cover today. */
    private static void cutoff(String closedThrough) throws Exception {
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE ledger.posting_cutoff SET closed_through = " + closedThrough + ", revision = revision + 1");
            }
            owner.commit();
        }
    }

    /** A key issued to {@code merchant} with the BNPL_REFUND scope - {@code BnplRefundApiTest}'s. */
    private String scopedKey(BnplApplicationClient.Merchant merchant) throws Exception {
        String operator = world.client.refundKeyIssuerSession(authorization);
        HttpResponse<String> issued = world.client.post("/v1/operator/merchants/" + merchant.id() + "/api-keys?scope=BNPL_REFUND", null, operator,
                BnplApplicationClient.key());
        assertThat(issued.statusCode()).as(issued.body()).isEqualTo(201);
        return BnplApplicationClient.field(issued.body(), "keyId") + "." + BnplApplicationClient.field(issued.body(), "secret");
    }

    /** An accepted purchase: its obligation, agreement, party, the party's wallet, its purchase and merchant. */
    private record Bought(BnplObligationId obligation, BnplAgreementId agreement, UUID party, LedgerAccountId wallet, String purchase,
            BnplApplicationClient.Merchant merchant) {

        String obligationText() {
            return obligation.value().toString();
        }

        String agreementText() {
            return agreement.value().toString();
        }
    }

    /** A purchase past its second instalment by ninety days, its default recorded by the real step. */
    private Bought defaulted() throws Exception {
        CreditFixtures.Customer owner = world.customers.consentingCustomer();
        // Exactly the first instalment: the suite's pay-in-4 terms auto-collect, and a wallet holding the rest would be collected and the
        // obligation closed REPAID - no default, nothing to write off.
        world.topUpWallet(owner.party(), eur(GROSS / 4));
        BnplApplicationClient.Merchant merchant = world.merchant();
        BnplPurchaseWorld.Offer offer = world.offered(owner, merchant, GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        UUID obligation = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id = ?::uuid",
                agreement));
        Bought bought = new Bought(BnplObligationId.of(obligation), BnplAgreementId.of(UUID.fromString(agreement)), owner.party(),
                world.walletOf(owner.party()), BnplApplicationClient.field(accepted.body(), "purchaseId"), merchant);
        assertThat(BnplPurchaseWorld.creditBalanceOf(bought.wallet().value())).as("nothing for the step to collect").isZero();
        toAge(bought, 2, 90);
        for (int step = 0; step < 8 && defaults(bought) == 0; step++) {
            try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
                transactions.inTransaction(uow -> servicing.service(uow, bought.obligation()));
            }
        }
        assertThat(defaults(bought)).as("defaulted at ninety").isEqualTo(1);
        return bought;
    }

    private static long defaults(Bought bought) throws Exception {
        return BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_condition_event WHERE kind = 'DEFAULT' AND defaulted AND"
                + " obligation_id = ?::uuid", bought.obligationText());
    }

    /** {@code BnplCollectionLedgerDatabaseTest}'s date shift: instalment {@code sequence} is {@code age} days past due today. */
    private static void toAge(Bought bought, int sequence, int age) throws Exception {
        String obligation = bought.obligationText();
        long days = age + BnplApplicationClient.count("SELECT i.due_date - (now() AT TIME ZONE 'UTC')::date FROM bnpl.bnpl_instalment i WHERE"
                + " i.obligation_id = ?::uuid AND i.sequence = ?", obligation, sequence);
        String o = "'" + obligation + "'";
        try (Connection owner = DatabaseRoles.bootstrap()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute("SET LOCAL session_replication_role = replica");
                statement.execute("UPDATE bnpl.purchase SET business_date = business_date - " + days + " WHERE id ="
                        + " (SELECT purchase_id FROM bnpl.bnpl_obligation WHERE id = " + o + ");"
                        + " UPDATE bnpl.bnpl_obligation SET purchase_date = purchase_date - " + days + " WHERE id = " + o
                        + "; UPDATE bnpl.bnpl_schedule SET start_date = start_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_instalment SET due_date = due_date - " + days + " WHERE obligation_id = "
                        + o + "; UPDATE bnpl.bnpl_billing SET value_date = value_date - " + days + ", period_start ="
                        + " period_start - " + days + ", business_date = business_date - " + days
                        + " WHERE obligation_id = " + o + "; UPDATE bnpl.bnpl_repayment SET value_date = value_date - "
                        + days + " WHERE obligation_id = " + o + "; UPDATE ledger.journal_entry SET posting_date ="
                        + " posting_date - " + days + ", value_date = value_date - " + days + " WHERE id IN (SELECT"
                        + " l.entry_id FROM ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                        + " l.ledger_account_id WHERE a.owner_ref = " + o + "::uuid)");
            }
            owner.commit();
        }
    }

    /** A person of their own party - a registered login, so the wired acting party can place them. */
    private Actor operator() throws Exception {
        CreditFixtures.Customer person = world.customers.consentingCustomer();
        String identity = BnplApplicationClient.scalar("SELECT id::text FROM identity.identity WHERE party_id = ?::uuid",
                person.party().toString());
        return new Actor(identity, ActorType.EMPLOYEE);
    }

    private Money exposureOf(Bought bought) {
        return lendingTransactions.inTransaction(uow -> exposure.outstandingFor(uow, bought.party(), BnplPurchaseWorld.EUR));
    }

    private static BnplWriteOffController.BnplWriteOffApprovalRequest attesting(BnplWriteOffDesk.WriteOffReceipt read) {
        return new BnplWriteOffController.BnplWriteOffApprovalRequest(read.currency(), read.principal(), read.principalDue(),
                read.interestAccrued(), read.interestDue(), read.feesDue(), read.revision(), "read and agreed");
    }

    private static <R> R as(Actor actor, Supplier<R> door) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(BnplPurchaseWorld.flow())) {
            return door.get();
        }
    }
}
