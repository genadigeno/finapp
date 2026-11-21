package com.finapp.app.bnpl;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisionKind;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedReads;
import com.finapp.bnpl.BnplActingParty;
import com.finapp.bnpl.BnplApplicationStatus;
import com.finapp.bnpl.BnplAuditAction;
import com.finapp.bnpl.BnplDisputes;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplOperatorLists;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplRefundProposal;
import com.finapp.bnpl.BnplRefundProposals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * BNPL's operator lists and the reads an investigator and an approver lacked (`P17-TSK-035`, gaps G-83 and G-84;
 * finapp-admin-panel B8.3, B8.4, ADR-C133): the applications, the agreements - by status and merchant - and one purchase's
 * refunds under {@code BNPL_INVESTIGATE}; the waivers and repayment reversals awaiting a decision under
 * {@code BNPL_SERVICE_APPROVE}, in ADR-C133's pending-decision shape naming the proposer. Each a §7 collection
 * ({@link OperatorCollection}: {@code limit} 50 unless named, 200 at most, refused rather than reduced; the cursor its own
 * collection's).
 *
 * <p><strong>The reader's own party</strong> is withheld by every list of a party's records - the write-off and dispute
 * queues' rule - and a purchase of it is never read: a purchase's refunds of the reader's own party - or any list or read by an
 * operator the platform cannot place in a party - is {@code 403 bnpl.SelfDealingRefused}, recorded {@code FAILED} in a
 * transaction of its own.
 *
 * <p><strong>Every serving is recorded</strong> in the read's own transaction ({@link RecordedReads}; ADR-C132 decision 5):
 * {@code bnpl.ApplicationsRead}, {@code bnpl.AgreementsRead}, {@code bnpl.RefundsRead} and {@code bnpl.ServicingRequestsRead},
 * once per page, naming the filter and the count served - never an amount.
 */
@RequiredArgsConstructor
public final class BnplListDesk {

    static final String APPLICATIONS = "bnpl.applications";
    static final String AGREEMENTS = "bnpl.agreements";
    static final String REFUNDS = "bnpl.purchase-refunds";
    static final String SERVICING_REQUESTS = "pending.bnpl.servicing-requests";
    static final String PENDING_TERMS_VERSIONS = "pending.bnpl.terms-versions";
    static final String PENDING_REFUND_PROPOSALS = "pending.bnpl.refund-proposals";
    static final String PENDING_DISPUTE_RESOLUTIONS = "pending.bnpl.dispute-resolutions";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    @NonNull private final BnplOperatorLists lists;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final RecordedReads reads;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplRefundProposals refundProposals;

    // ------------------------------------------------------------------ the views

    /** A page of BNPL applications: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BnplApplicationPage(List<BnplApplicationHeadView> items, String nextCursor) {}

    /**
     * One BNPL application: its party, product and plan, its status and - closed by the platform - why, the merchant it was
     * made at, the decision request and terms version it names, its stamps.
     */
    public record BnplApplicationHeadView(
            String applicationId,
            String partyId,
            String product,
            String plan,
            String status,
            String closureReason,
            String merchantId,
            String decisionRequestId,
            String termsVersionId,
            String submittedAt,
            String closedAt) {}

    /** A page of BNPL agreements: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BnplAgreementPage(List<BnplAgreementHeadView> items, String nextCursor) {}

    /**
     * One BNPL agreement: its obligation and purchase, its party and merchant, the product and plan, its currency and
     * obligation's status ({@code ACTIVE}, {@code CLOSED}, {@code WRITTEN_OFF}), opened and - closed - when and why. Never an
     * amount: the agreement's own read derives those.
     */
    public record BnplAgreementHeadView(
            String agreementId,
            String obligationId,
            String purchaseId,
            String partyId,
            String merchantId,
            String product,
            String plan,
            String currency,
            String status,
            String openedAt,
            String closedAt,
            String closureReason) {}

    /** A page of one purchase's refunds: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BnplPurchaseRefundPage(List<BnplPurchaseRefundView> items, String nextCursor) {}

    /**
     * One applied refund: its door ({@code MERCHANT} or {@code OPERATOR}) and who asked, the amount and what was refunded
     * before it, the fee returned and the merchant payable debited, what it cancelled unbilled and billed, any excess and where
     * it went ({@code WALLET}, {@code CREDIT_BALANCE}), the interest released, the agreement and schedule versions it wrote, its
     * reason, journal entry, value date and stamp. Amounts exact decimal strings in {@code currency}.
     */
    public record BnplPurchaseRefundView(
            String refundId,
            String purchaseId,
            String door,
            String requestedBy,
            String currency,
            String amount,
            String refundedBefore,
            String feeReturned,
            String payableDebit,
            String cancelledUnbilled,
            String cancelledBilled,
            String excess,
            String excessTo,
            String interestAccruedReleased,
            String interestDueReleased,
            String agreementId,
            int agreementVersion,
            int scheduleVersion,
            String reason,
            String journalEntryId,
            String valueDate,
            String createdAt) {}

    // ------------------------------------------------------------------ the investigator's lists

    /** A page of BNPL applications, submitted newest first - of one status when named - the reader's party withheld. */
    public BnplApplicationPage applications(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<BnplApplicationStatus> state = named("status", status, BnplApplicationStatus.class);
        Optional<BnplOperatorLists.Cursor> after = after(APPLICATIONS, cursor);
        String filter = "status=" + state.map(Enum::name).orElse("any");
        List<BnplOperatorLists.ApplicationHead> rows = listed("bnpl_applications", filter, (uow, withheld) -> {
            List<BnplOperatorLists.ApplicationHead> read = lists.applications(uow, state, withheld, after, size + 1);
            reads.record(uow, BnplAuditAction.APPLICATIONS_READ, "bnpl_applications", "all",
                    filter + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, APPLICATIONS, BnplOperatorLists.ApplicationHead::submittedAt, row -> row.id().value(),
                BnplListDesk::applicationView, BnplApplicationPage::new);
    }

    /** A page of BNPL agreements, opened newest first - of one status and one merchant when named - the reader's party withheld. */
    public BnplAgreementPage agreements(String status, String merchantId, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<BnplObligationStatus> state = named("status", status, BnplObligationStatus.class);
        Optional<UUID> merchant = Optional.ofNullable(merchantId).map(BnplListDesk::merchant);
        Optional<BnplOperatorLists.Cursor> after = after(AGREEMENTS, cursor);
        String filter = "status=" + state.map(Enum::name).orElse("any") + ", merchant="
                + merchant.map(UUID::toString).orElse("any");
        List<BnplOperatorLists.AgreementHead> rows = listed("bnpl_agreements", filter, (uow, withheld) -> {
            List<BnplOperatorLists.AgreementHead> read = lists.agreements(uow, state, merchant, withheld, after, size + 1);
            reads.record(uow, BnplAuditAction.AGREEMENTS_READ, "bnpl_agreements", "all",
                    filter + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, AGREEMENTS, BnplOperatorLists.AgreementHead::openedAt, row -> row.obligation().value(),
                BnplListDesk::agreementView, BnplAgreementPage::new);
    }

    /**
     * A page of one purchase's refunds, oldest first; recorded {@code bnpl.RefundsRead}. An unknown or malformed purchase is
     * the uniform {@code 404}; the investigator's own party's purchase - or an investigator the platform cannot place in a
     * party - {@code 403 bnpl.SelfDealingRefused}, recorded {@code FAILED}.
     */
    public BnplPurchaseRefundPage refunds(String rawPurchaseId, String cursor, String limit) {
        BnplPurchaseId purchase = purchaseId(rawPurchaseId);
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<BnplOperatorLists.Cursor> after = after(REFUNDS, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<BnplOperatorLists.RefundHead> rows;
        try {
            rows = transactions.inTransaction(uow -> {
                UUID party = lists.purchaseParty(uow, purchase).orElseThrow(BnplListDesk::notFound);
                Optional<UUID> readers = actingParties.partyOf(uow, actor);
                if (readers.isEmpty() || readers.get().equals(party)) {
                    throw new Refused(readers.isEmpty() ? "the reader is no identity the platform can place in a party"
                            : "the purchase is the reader's own party's");
                }
                List<BnplOperatorLists.RefundHead> read = lists.refunds(uow, purchase, after, size + 1);
                reads.record(uow, BnplAuditAction.REFUNDS_READ, "bnpl_purchase", purchase.value().toString(),
                        "purchase=" + purchase.value() + ", continued=" + after.isPresent() + ", served="
                                + Math.min(size, read.size()));
                return read;
            });
        } catch (Refused refused) {
            throw recorded(actor, correlation, "bnpl_purchase", purchase.value().toString(),
                    "purchase=" + purchase.value() + ", act=investigation:refunds", refused);
        }
        return page(rows, size, REFUNDS, BnplOperatorLists.RefundHead::createdAt, BnplOperatorLists.RefundHead::id,
                BnplListDesk::refundView, BnplPurchaseRefundPage::new);
    }

    // ------------------------------------------------------------------ a refund proposal, read by its approver

    /**
     * A BNPL refund proposal as its decider reads it (`P17-TSK-124`): the purchase, the refund's amount exact with its
     * currency, the purchase's refunds with this one ({@code cumulativeAmount}, what the threshold judged) and the threshold's
     * version, who proposed it, when and why, whether the caller did, and once decided the decision's instant and reason and
     * an approval's refund ({@code null} until then). Never who decided.
     */
    public record BnplRefundProposalView(
            String proposalId, String purchaseId, String amount, String currency, String cumulativeAmount,
            int thresholdVersion, String refundId, String status, String reason, String proposedBy, boolean proposedByCaller,
            String proposedAt, String decidedAt, String decisionReason) {}

    /**
     * Proposal {@code rawProposalId}, recorded {@code bnpl.RefundProposalRead} in the read's own transaction (`P17-TSK-124`).
     * An unknown or malformed identifier is the uniform {@code 404}, nothing recorded; a proposal of the reader's own party's
     * purchase - or a reader the platform cannot place in a party - {@code 403 bnpl.SelfDealingRefused}, recorded
     * {@code FAILED} in a transaction of its own, as BNPL's other operator reads (`P17-TSK-035`, `-116`).
     */
    public BnplRefundProposalView refundProposal(String rawProposalId) {
        BnplRefundProposal.Id id = refundProposalId(rawProposalId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplRefundProposal proposal;
        try {
            proposal = transactions.inTransaction(uow -> {
                BnplRefundProposal found = refundProposals.find(uow, id).orElseThrow(BnplListDesk::noRefundProposal);
                UUID party = lists.purchaseParty(uow, found.purchaseId()).orElseThrow(BnplListDesk::noRefundProposal);
                Optional<UUID> readers = actingParties.partyOf(uow, actor);
                if (readers.isEmpty() || readers.get().equals(party)) {
                    throw new Refused(readers.isEmpty() ? "the reader is no identity the platform can place in a party"
                            : "the purchase is the reader's own party's");
                }
                reads.record(uow, BnplAuditAction.REFUND_PROPOSAL_READ, "BnplRefundProposal", id.value().toString(),
                        "purchase=" + found.purchaseId().value() + ", status=" + found.status());
                return found;
            });
        } catch (Refused refused) {
            throw recorded(actor, correlation, "BnplRefundProposal", id.value().toString(),
                    "proposal=" + id.value() + ", act=refund-proposal:read", refused);
        }
        return new BnplRefundProposalView(proposal.id().value().toString(), proposal.purchaseId().value().toString(),
                decimal(proposal.amount()), proposal.amount().currency().code(), decimal(proposal.cumulative()),
                proposal.thresholdVersion(), proposal.refundId().map(UUID::toString).orElse(null), proposal.status().name(),
                proposal.proposalReason(), proposal.proposedBy(), proposal.proposedBy().equals(actor.id()),
                proposal.proposedAt().toString(),
                proposal.decision().map(BnplRefundProposal.Decision::decidedAt).map(Instant::toString).orElse(null),
                proposal.decision().map(BnplRefundProposal.Decision::reason).orElse(null));
    }

    // ------------------------------------------------------------------ the approver's queue

    /**
     * A page of the waivers and repayment reversals awaiting a decision, proposed oldest first - {@code status}
     * {@code PROPOSED} or absent - the reader's own party's agreements withheld, in the one pending-decision shape
     * (`P17-TSK-040`; until then a page of its own items naming {@code subjectKind}, {@code proposedByCaller} and
     * {@code repaymentId}, which is now its {@code relatedId}); recorded {@code bnpl.ServicingRequestsRead}.
     */
    public PendingDecisions.PendingDecisionPage servicingRequests(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<BnplOperatorLists.Cursor> after = pendingAfter(SERVICING_REQUESTS, cursor);
        List<BnplOperatorLists.ServicingRequest> rows = listed("bnpl_servicing_requests", "status=PROPOSED",
                (uow, withheld) -> {
                    List<BnplOperatorLists.ServicingRequest> read = lists.pendingServicing(uow, withheld, after, size + 1);
                    reads.record(uow, BnplAuditAction.SERVICING_REQUESTS_READ, "bnpl_servicing_requests", "all",
                            "status=PROPOSED, continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
                    return read;
                });
        return PendingDecisions.page(SERVICING_REQUESTS, rows.stream()
                .map(row -> PendingDecisions.of(row.id(), switch (row.kind()) {
                    case WAIVER -> PendingDecisionKind.BNPL_WAIVER;
                    case REPAYMENT_REVERSAL -> PendingDecisionKind.BNPL_REPAYMENT_REVERSAL;
                }, row.proposedBy(), row.proposedAt(), row.reason(), row.agreement().value(), row.repayment().orElse(null)))
                .toList(), size);
    }

    // ------------------------------------------------------------------ the pending-decision queues (`P17-TSK-040`)

    /**
     * A page of the BNPL terms versions awaiting a decision, proposed oldest first, in the one pending-decision shape; each one's
     * subject the product it would govern. Unrecorded, as the terms reads are: configuration, not a party's records.
     */
    public PendingDecisions.PendingDecisionPage pendingTermsVersions(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<BnplOperatorLists.Cursor> after = pendingAfter(PENDING_TERMS_VERSIONS, cursor);
        List<BnplOperatorLists.PendingProposal> rows = transactions.inTransaction(
                uow -> lists.pendingTermsVersions(uow, after, size + 1));
        return pendingPage(PENDING_TERMS_VERSIONS, PendingDecisionKind.BNPL_TERMS_VERSION, rows, size);
    }

    /**
     * A page of the operator refunds proposed above the four-eyes threshold and awaiting a decision, proposed oldest first, in
     * the one pending-decision shape - each one's subject the purchase - the reader's own party's purchases withheld; recorded
     * {@code bnpl.RefundsRead}, as a purchase's refunds are.
     */
    public PendingDecisions.PendingDecisionPage pendingRefundProposals(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<BnplOperatorLists.Cursor> after = pendingAfter(PENDING_REFUND_PROPOSALS, cursor);
        List<BnplOperatorLists.PendingProposal> rows = listed("bnpl_refund_proposals", "status=PROPOSED",
                (uow, withheld) -> {
                    List<BnplOperatorLists.PendingProposal> read = lists.pendingRefundProposals(uow, withheld, after, size + 1);
                    reads.record(uow, BnplAuditAction.REFUNDS_READ, "bnpl_refund_proposals", "pending",
                            "status=PROPOSED, continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
                    return read;
                });
        return pendingPage(PENDING_REFUND_PROPOSALS, PendingDecisionKind.BNPL_REFUND_PROPOSAL, rows, size);
    }

    /**
     * A page of the disputes whose resolution awaits a second operator's approval, proposed oldest first, in the one
     * pending-decision shape - the id the dispute's, whose doors decide it, the subject its purchase - the reader's own party's
     * disputes withheld; recorded {@code bnpl.DisputeQueueRead}, as the dispute queue is.
     */
    public PendingDecisions.PendingDecisionPage pendingDisputeResolutions(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<BnplOperatorLists.Cursor> after = pendingAfter(PENDING_DISPUTE_RESOLUTIONS, cursor);
        List<BnplOperatorLists.PendingProposal> rows = listed(BnplDisputes.QUEUE_TARGET, "status=PROPOSED",
                (uow, withheld) -> {
                    List<BnplOperatorLists.PendingProposal> read =
                            lists.pendingDisputeResolutions(uow, withheld, after, size + 1);
                    reads.record(uow, BnplAuditAction.DISPUTE_QUEUE_READ, BnplDisputes.QUEUE_TARGET, "PROPOSED",
                            "status=PROPOSED, continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
                    return read;
                });
        return pendingPage(PENDING_DISPUTE_RESOLUTIONS, PendingDecisionKind.BNPL_DISPUTE_RESOLUTION, rows, size);
    }

    private static PendingDecisions.PendingDecisionPage pendingPage(String collection, PendingDecisionKind kind,
            List<BnplOperatorLists.PendingProposal> rows, int size) {
        return PendingDecisions.page(collection, rows.stream()
                .map(row -> PendingDecisions.of(row.id(), kind, row.proposedBy(), row.proposedAt(), row.reason(), row.subject()))
                .toList(), size);
    }

    /** A pending-decision queue's cursor ({@link PendingDecisions#position}) in the store's words. */
    private static Optional<BnplOperatorLists.Cursor> pendingAfter(String collection, String cursor) {
        return PendingDecisions.position(collection, cursor)
                .map(position -> new BnplOperatorLists.Cursor(position.proposedAt(), position.id()));
    }

    // ------------------------------------------------------------------ the views

    private static BnplApplicationHeadView applicationView(BnplOperatorLists.ApplicationHead head) {
        return new BnplApplicationHeadView(head.id().value().toString(), head.party().toString(), head.product(), head.plan(),
                head.status().name(), head.closureReason().orElse(null), head.merchant().toString(),
                head.decisionRequest().toString(), head.termsVersion().value().toString(), head.submittedAt().toString(),
                head.closedAt().map(Instant::toString).orElse(null));
    }

    private static BnplAgreementHeadView agreementView(BnplOperatorLists.AgreementHead head) {
        return new BnplAgreementHeadView(head.agreement().value().toString(), head.obligation().value().toString(),
                head.purchase().value().toString(), head.party().toString(), head.merchant().toString(), head.product(),
                head.plan(), head.currency().code(), head.status().name(), head.openedAt().toString(),
                head.closedAt().map(Instant::toString).orElse(null), head.closureReason().orElse(null));
    }

    private static BnplPurchaseRefundView refundView(BnplOperatorLists.RefundHead head) {
        return new BnplPurchaseRefundView(head.id().toString(), head.purchase().value().toString(), head.door(),
                head.requestedBy(), head.amount().currency().code(), decimal(head.amount()), decimal(head.refundedBefore()),
                decimal(head.feeReturned()), decimal(head.payableDebit()), decimal(head.cancelledUnbilled()),
                decimal(head.cancelledBilled()), decimal(head.excess()), head.excessTo(),
                decimal(head.interestAccruedReleased()), decimal(head.interestDueReleased()),
                head.agreement().value().toString(), head.agreementVersion(), head.scheduleVersion(), head.reason(),
                head.journalEntry().toString(), head.valueDate().toString(), head.createdAt().toString());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    // ------------------------------------------------------------------ the reader's party

    @FunctionalInterface
    private interface Listing<R> {
        List<R> read(Connection unitOfWork, UUID withheldParty);
    }

    /** Why a read was refused before anything was served: the reader has no party the platform knows, or it is the subject's. */
    private static final class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        Refused(String cause) {
            super(cause);
        }
    }

    /**
     * {@code listing} read with the reader's own party withheld, on one transaction; a reader the platform cannot place in a
     * party refused before anything is read, the attempt recorded {@code FAILED} in a transaction of its own, then {@code 403}.
     */
    private <R> List<R> listed(String collection, String filter, Listing<R> listing) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            return transactions.inTransaction(uow -> listing.read(uow, actingParties.partyOf(uow, actor)
                    .orElseThrow(() -> new Refused("the reader is no identity the platform can place in a party"))));
        } catch (Refused refused) {
            throw recorded(actor, correlation, collection, "all", "act=list:" + collection + ", " + filter, refused);
        }
    }

    /** The refusal recorded {@code FAILED} in a transaction of its own, then answered {@code 403}. */
    private ApiException recorded(Actor actor, CorrelationId correlation, String targetType, String targetId, String act,
            Refused refused) {
        transactions.inTransaction(uow -> {
            audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), BnplAuditAction.SELF_DEALING_REFUSED,
                    targetType, targetId, Optional.empty(), AuditOutcome.FAILED, correlation,
                    Optional.of(act + " refused - " + refused.getMessage())));
            return null;
        });
        return new ApiException(BnplErrorCode.SELF_DEALING_REFUSED, "A BNPL read was refused",
                "an operator never reads their own party's BNPL records, nor reads when the platform cannot place them;"
                        + " nothing was served.");
    }

    // ------------------------------------------------------------------ plumbing

    @FunctionalInterface
    private interface Paged<V, P> {
        P of(List<V> items, String nextCursor);
    }

    /** The first {@code size} of {@code rows} - read one beyond - and the cursor after the last, when more remain. */
    private static <R, V, P> P page(List<R> rows, int size, String collection, Function<R, Instant> stamp, Function<R, UUID> id,
            Function<R, V> view, Paged<V, P> paged) {
        List<R> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            R tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(collection,
                    Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, stamp.apply(tail))), id.apply(tail).toString());
        }
        return paged.of(served.stream().map(view).toList(), next);
    }

    private static Optional<BnplOperatorLists.Cursor> after(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new BnplOperatorLists.Cursor(Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS),
                        UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
    }

    private static <E extends Enum<E>> Optional<E> named(String name, String value, Class<E> vocabulary) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(vocabulary, value));
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid(name + " is one of " + Arrays.toString(vocabulary.getEnumConstants()) + ".");
        }
    }

    private static UUID merchant(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw OperatorCollection.invalid("merchantId is a merchant's identifier, a UUID.");
        }
    }

    /** A purchase id is a UUIDv7 (ADR-0013): any other identifier names no purchase - the uniform 404, never a 500. */
    private static BnplPurchaseId purchaseId(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return BnplPurchaseId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL purchase matches the requested identifier",
                "no such record.");
    }

    /** A canonical UUIDv7 (ADR-0013), or the uniform 404 - anything else names no proposal, as the decision doors answer. */
    private static BnplRefundProposal.Id refundProposalId(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (parsed.toString().equals(raw) && parsed.version() == 7 && parsed.variant() == 2) {
                return BnplRefundProposal.Id.of(parsed);
            }
        } catch (IllegalArgumentException | NullPointerException malformed) {
            // the uniform 404 below
        }
        throw noRefundProposal();
    }

    private static ApiException noRefundProposal() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL refund proposal matches the requested identifier",
                "no such BNPL refund proposal.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL read runs inside a correlation scope"))
                .correlationId();
    }
}
