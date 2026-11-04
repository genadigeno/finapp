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
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
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
        long wallet = BnplPurchaseWorld.creditBalanceOf(bought.wallet().value());
        Actor customer = new Actor(bought.party().toString(), ActorType.CUSTOMER);
        assertThatExceptionOfType(BnplWrittenOffRefused.class).isThrownBy(() -> as(customer, () -> transactions.inTransaction(uow ->
                repayments.repay(uow, bought.agreement(), bought.party(), new BnplRepayments.Request(BnplApplicationClient.key(), eur(1_00)),
                        customer, BnplApplicationClient.correlation()))));
        assertThat(BnplPurchaseWorld.creditBalanceOf(bought.wallet().value())).isEqualTo(wallet);
    }

    // ------------------------------------------------------------------ plumbing

    /** An accepted purchase: its obligation, agreement, party and the party's wallet. */
    private record Bought(BnplObligationId obligation, BnplAgreementId agreement, UUID party, LedgerAccountId wallet) {

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
        world.topUpWallet(owner.party(), eur(1_000_00));
        BnplPurchaseWorld.Offer offer = world.offered(owner, world.merchant(), GROSS, BnplApplicationClient::payIn4);
        HttpResponse<String> accepted = world.accept(owner.token(), offer, BnplApplicationClient.key());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(201);
        String agreement = BnplApplicationClient.field(accepted.body(), "agreementId");
        UUID obligation = UUID.fromString(BnplApplicationClient.scalar("SELECT id::text FROM bnpl.bnpl_obligation WHERE agreement_id = ?::uuid",
                agreement));
        Bought bought = new Bought(BnplObligationId.of(obligation), BnplAgreementId.of(UUID.fromString(agreement)), owner.party(),
                world.walletOf(owner.party()));
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
