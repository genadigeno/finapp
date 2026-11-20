package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finapp.accounts.AccountRestriction;
import com.finapp.accounts.AccountRestrictionId;
import com.finapp.accounts.AccountRestrictionStore;
import com.finapp.accounts.AccountRestrictions;
import com.finapp.accounts.AccountsAuditAction;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.RestrictionKind;
import com.finapp.accounts.RestrictionReason;
import com.finapp.accounts.RestrictionStatus;
import com.finapp.app.accounts.AccountRestrictionController;
import com.finapp.app.accounts.AccountRestrictionDesk;
import com.finapp.app.bnpl.BnplFeeAssignmentProposalController;
import com.finapp.app.bnpl.BnplRefundProposalController;
import com.finapp.app.bnpl.BnplTransactions;
import com.finapp.app.bnpl.MerchantBnplDesk;
import com.finapp.app.merchant.MerchantClosureController;
import com.finapp.app.merchant.MerchantClosureDesk;
import com.finapp.app.merchant.MerchantPayoutOperations;
import com.finapp.app.merchant.PayoutDesk;
import com.finapp.app.merchant.PayoutProposalController;
import com.finapp.app.payments.PaymentService;
import com.finapp.app.payments.RefundDesk;
import com.finapp.app.payments.RefundProposalController;
import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.app.telemetry.TransferMetrics;
import com.finapp.app.transfers.TransferReversalController;
import com.finapp.app.transfers.TransferReversalDesk;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import com.finapp.merchant.BnplEnablements;
import com.finapp.merchant.BnplFeeAssignmentProposal;
import com.finapp.merchant.BnplFeeAssignmentProposalStore;
import com.finapp.merchant.BnplFeeAssignments;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.ClosureProposalStatus;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantAuditAction;
import com.finapp.merchant.MerchantBnplProduct;
import com.finapp.merchant.MerchantClosureProposal;
import com.finapp.merchant.MerchantClosureProposalId;
import com.finapp.merchant.MerchantClosureProposalStore;
import com.finapp.merchant.MerchantClosures;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.MerchantStore;
import com.finapp.merchant.PayoutProposal;
import com.finapp.merchant.PayoutProposalStore;
import com.finapp.merchant.PayoutProposals;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentsAuditAction;
import com.finapp.payments.RefundId;
import com.finapp.payments.RefundProposal;
import com.finapp.payments.RefundProposalStore;
import com.finapp.payments.RefundProposals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.ReversalProposalStatus;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferId;
import com.finapp.transfers.TransferReversal;
import com.finapp.transfers.TransferReversalProposal;
import com.finapp.transfers.TransferReversalProposalId;
import com.finapp.transfers.TransferReversalProposalStore;
import com.finapp.transfers.TransferReversalProposals;
import com.finapp.transfers.TransferStatus;
import com.finapp.transfers.TransferStore;
import com.finapp.transfers.TransfersAuditAction;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The reads an approver lacks (`P17-TSK-124`, finapp-admin-panel B7.6 and B6.8 item 1), hermetic: each pending decision read by
 * its decider - under the very permission of its approval door, on a {@code MULTI_FACTOR} session, held at the answer
 * ({@code @RecordedRead}) - serving its subject, its amount exact with its currency where the act moves one, its reason,
 * {@code proposedBy}, whether the caller proposed it, {@code proposedAt} and status, and once decided the decision's instant and
 * reason but never its decider; every serving recorded in the read's own transaction with no amount in the record; an unknown
 * or malformed identifier the door's {@code 404} with nothing read for a malformed one and nothing recorded for either. The
 * modules' own reads compose the proposal with its subject and write nothing. The SQL over PostgreSQL is owed to the database
 * tier (the owner's ruling of 2026-11-20); the BNPL refund proposal's read is {@code BnplListDeskTest}'s, the dead letters'
 * {@code DeadLetterDeskTest}'s.
 */
@DisplayName("the reads an approver lacks: one pending decision, read by its decider and recorded (P17-TSK-124)")
@SuppressWarnings({"try", "unchecked"}) // Scopes are used for their close side effect (the established idiom); Mockito's answers.
class ApproverProposalReadsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant PROPOSED_AT = Instant.parse("2026-11-20T09:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-11-20T10:00:00Z");
    private static final String PROPOSER = "proposer-" + UUID.randomUUID();
    private static final String DECIDER = "decider-" + UUID.randomUUID();
    private static final Actor APPROVER = new Actor("approver-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(1_234_56L, EUR);
    private static final Money CUMULATIVE = Money.ofMinorUnits(2_000_01L, EUR);

    private final List<AuditRecord> written = new ArrayList<>();
    private final AuditWriter<Connection> writer = (unitOfWork, record) -> written.add(record);
    private final RecordedReads reads = new RecordedReads(writer, IDS, CLOCK);
    private final TransactionTemplate template = template();
    private final DataSource dataSource = dataSource();

    // ------------------------------------------------------------------ the declarations

    /** Each read beside its approval door: controller, read handler, approval handler. */
    private static final List<Object[]> READS = List.of(
            new Object[] {TransferReversalController.class, "readTransferReversalProposal", "approveTransferReversal",
                    PermissionName.TRANSFER_REVERSE_APPROVE},
            new Object[] {RefundProposalController.class, "readRefundProposal", "approveRefundProposal",
                    PermissionName.PAYMENT_REFUND_APPROVE},
            new Object[] {PayoutProposalController.class, "readPayoutProposal", "approvePayoutProposal",
                    PermissionName.MERCHANT_PAYOUT_APPROVE},
            new Object[] {MerchantClosureController.class, "readMerchantClosureProposal", "approveMerchantClosure",
                    PermissionName.MERCHANT_CLOSE_APPROVE},
            new Object[] {BnplRefundProposalController.class, "readBnplRefundProposal", "approveBnplRefundProposal",
                    PermissionName.PAYMENT_REFUND_APPROVE},
            new Object[] {BnplFeeAssignmentProposalController.class, "readBnplFeeAssignmentProposal",
                    "approveBnplFeeAssignment", PermissionName.FEE_SCHEDULE_APPROVE},
            new Object[] {AccountRestrictionController.class, "readAccountRestriction", "approveAccountRestriction",
                    PermissionName.ACCOUNT_RESTRICT_APPROVE});

    @Test
    @DisplayName("each read is a GET under the very permission of the approval door beside it - the decider's, never the"
            + " proposer's - on a MULTI_FACTOR session, and held at the answer (@RecordedRead)")
    void eachReadIsTheDecidersOwn() {
        for (Object[] read : READS) {
            Class<?> controller = (Class<?>) read[0];
            Method handler = method(controller, (String) read[1]);
            Method approval = method(controller, (String) read[2]);
            String name = controller.getSimpleName() + "#" + handler.getName();
            assertThat(handler.isAnnotationPresent(GetMapping.class)).as(name + " is a GET").isTrue();
            assertThat(approval.isAnnotationPresent(PostMapping.class)).as(read[2] + " is the approval door").isTrue();
            assertThat(handler.getAnnotation(RequiresPermission.class).value()).as(name)
                    .isEqualTo(read[3])
                    .isEqualTo(approval.getAnnotation(RequiresPermission.class).value());
            assertThat(handler.getAnnotation(RequiresAssurance.class).value()).as(name + " step-up")
                    .isEqualTo(AssuranceLevel.MULTI_FACTOR);
            assertThat(handler.isAnnotationPresent(RecordedRead.class)).as(name + " held at the answer").isTrue();
        }
    }

    // ------------------------------------------------------------------ a transfer reversal

    @Test
    @DisplayName("a transfer reversal proposal: the transfer's amount exact with its currency and its status, the proposer, the"
            + " reason; recorded transfers.ReversalProposalRead against the proposal, never an amount")
    void aTransferReversalProposal() {
        TransferReversalProposals proposals = mock(TransferReversalProposals.class);
        TransferReversalDesk desk = new TransferReversalDesk(proposals, mock(IdempotentExecutor.class), template, dataSource,
                mock(TransferMetrics.class), reads);
        TransferReversalProposal proposal = new TransferReversalProposal(TransferReversalProposalId.of(IDS.next()),
                TransferId.of(IDS.next()), "the customer was charged twice", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1",
                ReversalProposalStatus.PROPOSED, Optional.empty());
        Transfer transfer = transfer(proposal.transferId(), TransferStatus.COMPLETED);
        when(proposals.read(any(), any())).thenReturn(Optional.of(new TransferReversalProposals.Read(proposal, transfer)));

        TransferReversalDesk.TransferReversalProposalView view = as(() -> desk.read(proposal.id().value().toString()));

        assertThat(view.proposalId()).isEqualTo(proposal.id().value().toString());
        assertThat(view.transferId()).isEqualTo(proposal.transferId().value().toString());
        assertThat(view.amount()).isEqualTo("1234.56");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.transferStatus()).isEqualTo("COMPLETED");
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.reason()).isEqualTo("the customer was charged twice");
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        assertThat(view.proposedByCaller()).isFalse();
        assertThat(view.proposedAt()).isEqualTo(PROPOSED_AT.toString());
        assertThat(view.decidedAt()).isNull();
        assertThat(view.decisionReason()).isNull();
        recordedOnce(TransfersAuditAction.REVERSAL_PROPOSAL_READ, "TransferReversalProposal", proposal.id().value().toString(),
                "transfer=" + proposal.transferId().value());
    }

    @Test
    @DisplayName("a decided reversal serves its decision's instant and reason, never who decided; the proposer reading their own"
            + " is told so (proposedByCaller)")
    void aDecidedReversalNamesNoDecider() {
        TransferReversalProposals proposals = mock(TransferReversalProposals.class);
        TransferReversalDesk desk = new TransferReversalDesk(proposals, mock(IdempotentExecutor.class), template, dataSource,
                mock(TransferMetrics.class), reads);
        TransferReversalProposal proposal = new TransferReversalProposal(TransferReversalProposalId.of(IDS.next()),
                TransferId.of(IDS.next()), "charged twice", APPROVER.id(), "EMPLOYEE", PROPOSED_AT, "c-1",
                ReversalProposalStatus.REJECTED, Optional.of(new TransferReversalProposal.Decision(DECIDER, "EMPLOYEE", DECIDED_AT,
                        "the charge was a hold, not a capture", "c-2")));
        when(proposals.read(any(), any())).thenReturn(Optional.of(new TransferReversalProposals.Read(proposal,
                transfer(proposal.transferId(), TransferStatus.COMPLETED))));

        TransferReversalDesk.TransferReversalProposalView view = as(() -> desk.read(proposal.id().value().toString()));

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.decidedAt()).isEqualTo(DECIDED_AT.toString());
        assertThat(view.decisionReason()).isEqualTo("the charge was a hold, not a capture");
        assertThat(view.proposedByCaller()).isTrue();
        assertThat(view.toString()).doesNotContain(DECIDER);
    }

    @Test
    @DisplayName("an unknown reversal proposal is 404 recording nothing; a malformed or non-v7 identifier is 404 and nothing is"
            + " read")
    void anUnknownOrMalformedReversalProposal() {
        TransferReversalProposals proposals = mock(TransferReversalProposals.class);
        TransferReversalDesk desk = new TransferReversalDesk(proposals, mock(IdempotentExecutor.class), template, dataSource,
                mock(TransferMetrics.class), reads);
        when(proposals.read(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.read(IDS.next().toString()));
        notFound(() -> desk.read("not-a-proposal"));
        notFound(() -> desk.read(UUID.randomUUID().toString()));
        verify(proposals, org.mockito.Mockito.times(1)).read(any(), any());
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ a refund

    @Test
    @DisplayName("a refund proposal: the payment, the amount and the cumulative amount the threshold judged, exact, its threshold"
            + " version and an approval's refund; recorded payments.RefundProposalRead, never an amount; unknown 404 unrecorded")
    void aRefundProposal() {
        RefundProposals proposals = mock(RefundProposals.class);
        RefundDesk desk = new RefundDesk(mock(PaymentService.class), proposals, mock(IdempotentExecutor.class), template,
                dataSource, reads);
        RefundId refund = RefundId.of(IDS.next());
        RefundProposal proposal = new RefundProposal(RefundProposal.Id.of(IDS.next()), PaymentIntentId.of(IDS.next()), AMOUNT,
                CUMULATIVE, 3, "goods returned in full", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1", RefundProposal.Status.APPROVED,
                Optional.of(new RefundProposal.Decision(DECIDER, "EMPLOYEE", DECIDED_AT, "receipt checked", "c-2")),
                Optional.of(refund));
        when(proposals.find(any(), any())).thenReturn(Optional.of(proposal));

        RefundDesk.RefundProposalView view = as(() -> desk.read(proposal.id().value().toString()));

        assertThat(view.paymentId()).isEqualTo(proposal.intentId().value().toString());
        assertThat(view.amount()).isEqualTo("1234.56");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.cumulativeAmount()).isEqualTo("2000.01");
        assertThat(view.thresholdVersion()).isEqualTo(3);
        assertThat(view.refundId()).isEqualTo(refund.value().toString());
        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.reason()).isEqualTo("goods returned in full");
        assertThat(view.decisionReason()).isEqualTo("receipt checked");
        assertThat(view.toString()).doesNotContain(DECIDER);
        recordedOnce(PaymentsAuditAction.REFUND_PROPOSAL_READ, "RefundProposal", proposal.id().value().toString(),
                "payment=" + proposal.intentId().value());

        written.clear();
        when(proposals.find(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.read(IDS.next().toString()));
        notFound(() -> desk.read("12345"));
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ an operator payout

    @Test
    @DisplayName("an operator payout proposal: the merchant by name and status, the amount exact, the threshold version;"
            + " recorded merchant.PayoutProposalRead, never an amount; unknown 404 unrecorded")
    void aPayoutProposal() {
        PayoutProposals proposals = mock(PayoutProposals.class);
        PayoutDesk desk = new PayoutDesk(mock(MerchantPayoutOperations.class), proposals, mock(IdempotentExecutor.class),
                template, dataSource, reads);
        PayoutProposal proposal = new PayoutProposal(PayoutProposal.Id.of(IDS.next()), MerchantId.of(IDS.next()), AMOUNT, 2,
                "the month's settlement, early", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1", PayoutProposal.Status.PROPOSED,
                Optional.empty(), Optional.empty());
        when(proposals.read(any(), any())).thenReturn(Optional.of(new PayoutProposals.Read(proposal,
                merchant(proposal.merchantId(), MerchantStatus.ACTIVE))));

        PayoutDesk.PayoutProposalView view = as(() -> desk.read(proposal.id().value().toString()));

        assertThat(view.merchantId()).isEqualTo(proposal.merchantId().value().toString());
        assertThat(view.merchantName()).isEqualTo("Corner Shop");
        assertThat(view.merchantStatus()).isEqualTo("ACTIVE");
        assertThat(view.amount()).isEqualTo("1234.56");
        assertThat(view.currency()).isEqualTo("EUR");
        assertThat(view.thresholdVersion()).isEqualTo(2);
        assertThat(view.payoutId()).isNull();
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        recordedOnce(MerchantAuditAction.PAYOUT_PROPOSAL_READ, "PayoutProposal", proposal.id().value().toString(),
                "merchant=" + proposal.merchantId().value());

        written.clear();
        when(proposals.read(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.read(IDS.next().toString()));
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ a merchant closure

    @Test
    @DisplayName("a merchant closure proposal: the merchant by name and status - a closure moves no amount - the reason and the"
            + " proposer; recorded merchant.ClosureProposalRead; unknown 404 unrecorded")
    void aMerchantClosureProposal() {
        MerchantClosures proposals = mock(MerchantClosures.class);
        MerchantClosureDesk desk = new MerchantClosureDesk(proposals, mock(IdempotentExecutor.class), template, dataSource, reads);
        MerchantClosureProposal proposal = new MerchantClosureProposal(MerchantClosureProposalId.of(IDS.next()),
                MerchantId.of(IDS.next()), "the merchant asked to leave", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1",
                ClosureProposalStatus.PROPOSED, Optional.empty());
        when(proposals.read(any(), any())).thenReturn(Optional.of(new MerchantClosures.Read(proposal,
                merchant(proposal.merchantId(), MerchantStatus.SUSPENDED))));

        MerchantClosureDesk.MerchantClosureProposalView view = as(() -> desk.read(proposal.id().value().toString()));

        assertThat(view.merchantName()).isEqualTo("Corner Shop");
        assertThat(view.merchantStatus()).isEqualTo("SUSPENDED");
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.reason()).isEqualTo("the merchant asked to leave");
        assertThat(view.proposedAt()).isEqualTo(PROPOSED_AT.toString());
        recordedOnce(MerchantAuditAction.CLOSURE_PROPOSAL_READ, "MerchantClosureProposal", proposal.id().value().toString(),
                "merchant=" + proposal.merchantId().value());

        written.clear();
        when(proposals.read(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.read(IDS.next().toString()));
        notFound(() -> desk.read(""));
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ a BNPL fee assignment

    @Test
    @DisplayName("a BNPL fee assignment proposal: the merchant, the product and the schedule proposed - a price pointer, no"
            + " amount; recorded merchant.BnplFeeAssignmentProposalRead; unknown 404 unrecorded")
    void aBnplFeeAssignmentProposal() {
        BnplFeeAssignments assignments = mock(BnplFeeAssignments.class);
        MerchantBnplDesk desk = new MerchantBnplDesk(mock(BnplEnablements.class), mock(IdempotentExecutor.class),
                bnplTransactions(), assignments, reads);
        MerchantBnplProduct product = MerchantBnplProduct.values()[0];
        FeeScheduleId schedule = FeeScheduleId.of(IDS.next());
        BnplFeeAssignmentProposal proposal = new BnplFeeAssignmentProposal(BnplFeeAssignmentProposal.Id.of(IDS.next()),
                MerchantId.of(IDS.next()), product, schedule, "the negotiated rate", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1",
                BnplFeeAssignmentProposal.Status.PROPOSED, Optional.empty());
        when(assignments.read(any(), any())).thenReturn(Optional.of(new BnplFeeAssignments.Read(proposal,
                merchant(proposal.merchantId(), MerchantStatus.ACTIVE))));

        MerchantBnplDesk.BnplFeeAssignmentProposalView view = as(() -> desk.readFeeAssignment(proposal.id().value().toString()));

        assertThat(view.merchantId()).isEqualTo(proposal.merchantId().value().toString());
        assertThat(view.merchantName()).isEqualTo("Corner Shop");
        assertThat(view.product()).isEqualTo(product.name());
        assertThat(view.feeScheduleId()).isEqualTo(schedule.value().toString());
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.reason()).isEqualTo("the negotiated rate");
        recordedOnce(MerchantAuditAction.BNPL_FEE_ASSIGNMENT_PROPOSAL_READ, "BnplFeeAssignmentProposal",
                proposal.id().value().toString(), "product=" + product.name());

        written.clear();
        when(assignments.read(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.readFeeAssignment(IDS.next().toString()));
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ an account restriction

    @Test
    @DisplayName("an account restriction: the agreement and its status now, the kind and its reason code, the reason and the"
            + " proposer; recorded accounts.RestrictionRead, never the reason's text; the view logs no reason; unknown 404")
    void anAccountRestriction() {
        AccountRestrictions restrictions = mock(AccountRestrictions.class);
        AccountRestrictionDesk desk = new AccountRestrictionDesk(restrictions, mock(IdempotentExecutor.class), template,
                dataSource, reads);
        AccountRestriction restriction = new AccountRestriction(AccountRestrictionId.of(IDS.next()),
                CustomerAccountId.of(IDS.next()), UUID.randomUUID(), RestrictionKind.SUSPENSION, RestrictionReason.FRAUD_SUSPECTED,
                "three chargebacks this week", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c-1", RestrictionStatus.PROPOSED,
                Optional.empty());
        when(restrictions.read(any(), any())).thenReturn(Optional.of(new AccountRestrictions.Read(restriction,
                CustomerAccountStatus.ACTIVE)));

        AccountRestrictionDesk.AccountRestrictionView view = as(() -> desk.read(restriction.id().value().toString()));

        assertThat(view.accountId()).isEqualTo(restriction.accountId().value().toString());
        assertThat(view.accountStatus()).isEqualTo("ACTIVE");
        assertThat(view.kind()).isEqualTo("SUSPENSION");
        assertThat(view.reasonCode()).isEqualTo("FRAUD_SUSPECTED");
        assertThat(view.reason()).isEqualTo("three chargebacks this week");
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        assertThat(view.status()).isEqualTo("PROPOSED");
        assertThat(view.toString()).doesNotContain("chargebacks");
        recordedOnce(AccountsAuditAction.RESTRICTION_READ, "AccountRestriction", restriction.id().value().toString(),
                "account=" + restriction.accountId().value());
        assertThat(written.getFirst().changeSummary().orElseThrow()).doesNotContain("chargebacks");

        written.clear();
        when(restrictions.read(any(), any())).thenReturn(Optional.empty());
        notFound(() -> desk.read(IDS.next().toString()));
        notFound(() -> desk.read("not-a-restriction"));
        assertThat(written).isEmpty();
    }

    // ------------------------------------------------------------------ the modules' own reads

    @Test
    @DisplayName("the modules' reads compose the proposal with its subject as it stands, unlocked, writing nothing; an unknown"
            + " proposal is empty and its subject never read")
    void theModulesReadsComposeAndWriteNothing() {
        Connection uow = mock(Connection.class);

        TransferStore<Connection> transfers = mock(TransferStore.class);
        TransferReversalProposalStore<Connection> reversals = mock(TransferReversalProposalStore.class);
        TransferReversalProposals reversalProposals = new TransferReversalProposals(transfers, reversals,
                mock(TransferReversal.class), writer, IDS, CLOCK);
        TransferReversalProposal reversal = new TransferReversalProposal(TransferReversalProposalId.of(IDS.next()),
                TransferId.of(IDS.next()), "r", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c", ReversalProposalStatus.PROPOSED,
                Optional.empty());
        Transfer transfer = transfer(reversal.transferId(), TransferStatus.COMPLETED);
        when(reversals.find(uow, reversal.id())).thenReturn(Optional.of(reversal));
        when(transfers.findById(uow, reversal.transferId())).thenReturn(Optional.of(transfer));
        assertThat(reversalProposals.read(uow, reversal.id())).hasValueSatisfying(read -> {
            assertThat(read.proposal()).isEqualTo(reversal);
            assertThat(read.transfer()).isSameAs(transfer);
        });
        TransferReversalProposalId unknownReversal = TransferReversalProposalId.of(IDS.next());
        when(reversals.find(uow, unknownReversal)).thenReturn(Optional.empty());
        assertThat(reversalProposals.read(uow, unknownReversal)).isEmpty();
        verify(reversals, never()).lock(any(), any());

        MerchantStore<Connection> merchants = mock(MerchantStore.class);
        PayoutProposalStore<Connection> payouts = mock(PayoutProposalStore.class);
        PayoutProposals payoutProposals = new PayoutProposals(payouts, merchants, writer, IDS, CLOCK);
        PayoutProposal payout = new PayoutProposal(PayoutProposal.Id.of(IDS.next()), MerchantId.of(IDS.next()), AMOUNT, 1, "r",
                PROPOSER, "EMPLOYEE", PROPOSED_AT, "c", PayoutProposal.Status.REJECTED,
                Optional.of(new PayoutProposal.Decision(DECIDER, "EMPLOYEE", DECIDED_AT, "no", "c2")), Optional.empty());
        Merchant shop = merchant(payout.merchantId(), MerchantStatus.ACTIVE);
        when(payouts.find(uow, payout.id())).thenReturn(Optional.of(payout));
        when(merchants.findById(uow, payout.merchantId())).thenReturn(Optional.of(shop));
        assertThat(payoutProposals.read(uow, payout.id())).hasValueSatisfying(read ->
                assertThat(read.merchant()).isSameAs(shop));
        verify(payouts, never()).lock(any(), any());
        verify(merchants, never()).findByIdForUpdate(any(), any());

        MerchantClosureProposalStore<Connection> closures = mock(MerchantClosureProposalStore.class);
        MerchantClosures merchantClosures = new MerchantClosures(merchants, closures, mock(MerchantAdministration.class), writer,
                IDS, CLOCK);
        MerchantClosureProposal closure = new MerchantClosureProposal(MerchantClosureProposalId.of(IDS.next()),
                payout.merchantId(), "r", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c", ClosureProposalStatus.PROPOSED, Optional.empty());
        when(closures.find(uow, closure.id())).thenReturn(Optional.of(closure));
        assertThat(merchantClosures.read(uow, closure.id())).hasValueSatisfying(read ->
                assertThat(read.merchant()).isSameAs(shop));
        verify(closures, never()).lock(any(), any());

        BnplFeeAssignmentProposalStore<Connection> feeProposals = mock(BnplFeeAssignmentProposalStore.class);
        BnplFeeAssignments feeAssignments = new BnplFeeAssignments(feeProposals, merchants, mock(BnplPricing.class), writer, IDS,
                CLOCK);
        BnplFeeAssignmentProposal fee = new BnplFeeAssignmentProposal(BnplFeeAssignmentProposal.Id.of(IDS.next()),
                payout.merchantId(), MerchantBnplProduct.values()[0], FeeScheduleId.of(IDS.next()), "r", PROPOSER, "EMPLOYEE",
                PROPOSED_AT, "c", BnplFeeAssignmentProposal.Status.PROPOSED, Optional.empty());
        when(feeProposals.find(uow, fee.id())).thenReturn(Optional.of(fee));
        assertThat(feeAssignments.read(uow, fee.id())).hasValueSatisfying(read -> assertThat(read.merchant()).isSameAs(shop));
        BnplFeeAssignmentProposal.Id unknownFee = BnplFeeAssignmentProposal.Id.of(IDS.next());
        when(feeProposals.find(uow, unknownFee)).thenReturn(Optional.empty());
        assertThat(feeAssignments.read(uow, unknownFee)).isEmpty();
        verify(feeProposals, never()).lock(any(), any());

        CustomerAccountStore<Connection> accounts = mock(CustomerAccountStore.class);
        AccountRestrictionStore<Connection> restrictionStore = mock(AccountRestrictionStore.class);
        AccountRestrictions accountRestrictions = new AccountRestrictions(accounts, restrictionStore, writer, IDS, CLOCK);
        AccountRestriction restriction = new AccountRestriction(AccountRestrictionId.of(IDS.next()),
                CustomerAccountId.of(IDS.next()), UUID.randomUUID(), RestrictionKind.REINSTATEMENT,
                RestrictionReason.INVESTIGATION_CLEARED, "r", PROPOSER, "EMPLOYEE", PROPOSED_AT, "c", RestrictionStatus.PROPOSED,
                Optional.empty());
        CustomerAccount account = mock(CustomerAccount.class);
        when(account.status()).thenReturn(CustomerAccountStatus.SUSPENDED);
        when(restrictionStore.find(uow, restriction.id())).thenReturn(Optional.of(restriction));
        when(accounts.findById(uow, restriction.accountId())).thenReturn(Optional.of(account));
        assertThat(accountRestrictions.read(uow, restriction.id())).hasValueSatisfying(read ->
                assertThat(read.accountStatus()).isEqualTo(CustomerAccountStatus.SUSPENDED));
        verify(restrictionStore, never()).lock(any(), any());

        assertThat(written).as("a read writes nothing of its own: the desk records the serving").isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    private void recordedOnce(com.finapp.platform.audit.AuditableAction action, String targetType, String targetId,
            String summaryNames) {
        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(action);
            assertThat(record.targetType()).isEqualTo(targetType);
            assertThat(record.targetId()).isEqualTo(targetId);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.actor()).isEqualTo(APPROVER);
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary().orElseThrow()).contains(summaryNames).doesNotContain("1234.56", "2000.01", "123456");
        });
    }

    private static Method method(Class<?> controller, String name) {
        return Arrays.stream(controller.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(controller.getSimpleName() + " declares no " + name));
    }

    private static Transfer transfer(TransferId id, TransferStatus status) {
        Transfer transfer = mock(Transfer.class);
        when(transfer.id()).thenReturn(id);
        when(transfer.amount()).thenReturn(AMOUNT);
        when(transfer.status()).thenReturn(status);
        return transfer;
    }

    private static Merchant merchant(MerchantId id, MerchantStatus status) {
        Merchant merchant = mock(Merchant.class);
        when(merchant.id()).thenReturn(id);
        when(merchant.displayName()).thenReturn("Corner Shop");
        when(merchant.status()).thenReturn(status);
        return merchant;
    }

    private <T> T as(Supplier<T> read) {
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enter(APPROVER)) {
            return read.get();
        }
    }

    private void notFound(Runnable request) {
        assertThatThrownBy(() -> as(() -> {
            request.run();
            return null;
        })).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
    }

    private static TransactionTemplate template() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0)).doInTransaction(null));
        return template;
    }

    private static DataSource dataSource() {
        DataSource dataSource = mock(DataSource.class);
        try {
            when(dataSource.getConnection()).thenAnswer(call -> mock(Connection.class));
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        return dataSource;
    }

    private static BnplTransactions bnplTransactions() {
        BnplTransactions transactions = mock(BnplTransactions.class);
        when(transactions.inTransaction(any())).thenAnswer(call -> ((Function<Connection, Object>) call.getArgument(0)).apply(null));
        return transactions;
    }
}
