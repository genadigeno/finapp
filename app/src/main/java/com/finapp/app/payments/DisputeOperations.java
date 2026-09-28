package com.finapp.app.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.MerchantId;
import com.finapp.payments.Dispute;
import com.finapp.payments.DisputeActor;
import com.finapp.payments.DisputeEvidence;
import com.finapp.payments.DisputeEvidenceAccess;
import com.finapp.payments.DisputeEvidenceContent;
import com.finapp.payments.DisputeEvidenceContentType;
import com.finapp.payments.DisputeEvidenceId;
import com.finapp.payments.DisputeEvidenceKind;
import com.finapp.payments.DisputeEvidenceStore;
import com.finapp.payments.DisputeId;
import com.finapp.payments.DisputeReads;
import com.finapp.payments.DisputeResponse;
import com.finapp.payments.DisputeResponseKeyReusedException;
import com.finapp.payments.DisputeResponseKind;
import com.finapp.payments.DisputeResponseRefusedException;
import com.finapp.payments.DisputeResponses;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.UnknownDisputeException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispute surfaces behind the controllers (`P7-TSK-012`, `P7-TSK-014`) — parse and refuse at
 * the boundary, the {@code RoutingPolicyOperations} shape.
 *
 * <h2>The merchant's tenant is its own payables, resolved from the key</h2>
 *
 * <p>A merchant's disputes are the disputes on payments that credited the merchant's payable.
 * The payables come from the ledger's owner-scoped read of the authenticated merchant
 * ({@code owner_ref = ?} in its statement, the {@code MerchantPayable} precedent), and ride every
 * dispute and evidence statement as its tenant predicate ({@code INV-MER-01}). Another merchant's
 * dispute, an unknown one and a malformed identifier are one 404 — for a read, an upload, an
 * evidence read and an answer alike. A wallet top-up's dispute credits a customer's wallet, so it
 * is on no merchant's list.
 *
 * <h2>The operator's reads are privileged and on the record; its acts are narrower</h2>
 *
 * <p>Under {@code DISPUTE_ADMINISTER}, across tenants by design; every dispute shown writes
 * {@code payments.DisputeRead} and every evidence content read {@code payments.DisputeEvidenceRead}.
 * The operator ACTS — evidence, representment, acceptance — only for a payment with no merchant
 * (ADR-0061 §7: the merchant owns its dispute posture), reasoned. Only the operator's view carries
 * the attempt, the network's dispute reference and the PSP's submission reference —
 * reconciliation's keys, nobody else's business.
 *
 * <h2>The chargeback ratio is a report, never a tag (`P7-TSK-015`)</h2>
 *
 * <p>A merchant's chargebacks over its card sales for a calendar month — the number the card
 * schemes' monitoring programmes judge — for every merchant at once, under
 * {@code MERCHANT_ADMINISTER}: the desk that rules on a merchant's standing reads it, while a
 * dispute's details stay behind {@code DISPUTE_ADMINISTER}. {@code payments} counts per credited
 * account (never learning what a merchant is) and puts the read on the record; this class
 * attributes each account to its merchant through the ledger's batch read and ranks them. A
 * merchant tag on a meter would be unbounded cardinality and a tenant's data in a system with
 * other access control (ADR-0018).
 */
@RequiredArgsConstructor
public class DisputeOperations {

    /** The merchant listing's bound: the newest this many, and {@code truncated} says when
     * there were more. */
    static final int MERCHANT_LISTING_BOUND = 100;

    /**
     * Where an operator may ACT (`P7-TSK-014`, ADR-0061 §7): a payment that credited a customer's
     * wallet — no merchant. {@code payments} learns only a set of account purposes, never what a
     * merchant is.
     */
    static final Set<AccountPurpose> OPERATOR_REACH = Set.of(AccountPurpose.CUSTOMER_WALLET);

    /** The chargeback-ratio report's bound (`P7-TSK-015`): the worst this many merchants, and
     * {@code truncated} says when there were more. */
    static final int RATIO_REPORT_BOUND = 100;

    /** A report period's shape: a calendar month, {@code YYYY-MM}. */
    private static final java.util.regex.Pattern MONTH =
            java.util.regex.Pattern.compile("[0-9]{4}-[0-9]{2}");

    /** The earliest month a report may name - before any record this platform can hold. */
    static final java.time.YearMonth EARLIEST_REPORT_MONTH = java.time.YearMonth.of(2000, 1);

    @NonNull private final DisputeReads reads;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** The evidence paths (`P7-TSK-014`) — last, so no positional argument moved. */
    @NonNull private final DisputeEvidenceAccess evidence;

    /** The response command, present where the card PSP is configured (`P7-TSK-014`). */
    @NonNull private final ObjectProvider<DisputeResponses> responses;

    /** The declared rails (`P7-TSK-015`): which rails can be charged back is their capability,
     * never a name. Last, so no positional argument moved. */
    @NonNull private final com.finapp.payments.PaymentRails rails;

    /** The report's default period is the clock's current UTC month (ADR-0014). */
    @NonNull private final java.time.Clock clock;

    /**
     * The chargeback-ratio report (`P7-TSK-015`): one calendar month, UTC, every merchant with a
     * card sale or a chargeback in it, worst first. {@code ratio} is chargebacks over sales to four
     * places, half up, and absent when there were no sales — chargebacks against no sales are
     * ranked first, never shown as zero or infinity.
     */
    public record ChargebackRatioReport(
            String month,
            String windowStart,
            String windowEnd,
            List<MerchantChargebackRatio> merchants,
            boolean truncated) {}

    /** One merchant's month: counts, and the ratio — never an amount, never a customer. */
    public record MerchantChargebackRatio(
            String merchantId, long sales, long chargebacks, String ratio) {}

    /** One move on a dispute's trail, in the platform's own stage names. */
    public record StageChangeView(String from, String to, String at) {}

    /**
     * One evidence document, as a dispute view lists it — never its content, which only the
     * audited evidence read returns.
     */
    public record EvidenceView(
            String evidenceId,
            String kind,
            String contentType,
            int contentLength,
            String uploadedAt) {}

    /** One document with its content, base64 — every such read on the record. */
    public record EvidenceContentView(
            String evidenceId,
            String kind,
            String contentType,
            int contentLength,
            String uploadedAt,
            String content) {}

    /**
     * One answer to the network, as the responder sees it: {@code SUBMITTED} means the PSP took
     * it — the dispute's own stage says what the network decided.
     */
    public record ResponseView(
            String responseId,
            String kind,
            String status,
            String failureReason,
            String createdAt) {}

    /**
     * The answer to a dispatched response — the honest status, {@code UNKNOWN} included, and the
     * failure class when it FAILED; a replayed key renders the same judgement.
     */
    public record ResponseAnswer(
            String responseId,
            String kind,
            String status,
            String failureReason) {}

    /** The operator's answer view: plus who answered and the PSP's submission reference. */
    public record OperatorResponseView(
            String responseId,
            String kind,
            String status,
            String failureReason,
            String requestedByType,
            String providerReference,
            String createdAt) {}

    /**
     * A merchant's dispute, as its listing shows it. {@code chargebackAmount} is what the
     * network took, a decimal string — {@code null} (with its currency) while the dispute is an
     * inquiry that has taken nothing, or one closed without a chargeback.
     */
    public record MerchantDisputeSummary(
            String disputeId,
            String paymentIntentId,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String openedAt) {}

    /** The merchant's listing: newest first, at most 100 ({@code MERCHANT_LISTING_BOUND}). */
    public record MerchantDisputeList(List<MerchantDisputeSummary> disputes, boolean truncated) {}

    /**
     * One of the merchant's disputes, with its trail — and, since `P7-TSK-014`, the network's
     * {@code respondBy} deadline ({@code null} until stated), its evidence and its answers.
     */
    public record MerchantDisputeView(
            String disputeId,
            String paymentIntentId,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String respondBy,
            String openedAt,
            List<StageChangeView> history,
            List<EvidenceView> evidence,
            List<ResponseView> responses) {}

    /**
     * A dispute as the operator reads it: the merchant's view plus reconciliation's keys and,
     * since `P7-TSK-013`, the chargeback's attribution (ADR-0061 §3–§5) — what the
     * counterparty was charged ({@code counterpartyShare}), what is the counterparty's but
     * parked because its account took no postings ({@code parkedShare}, an operator's to
     * recover), and the excess the platform bears ({@code excess}), each a decimal string in
     * {@code chargebackCurrency} and {@code null} until the funds are taken; and the PSP's
     * {@code disputeFee}, {@code null} until reported. Since `P7-TSK-014`: the deadline, the
     * evidence and the answers.
     */
    public record OperatorDisputeView(
            String disputeId,
            String paymentIntentId,
            String attemptId,
            String providerDisputeReference,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String counterpartyShare,
            String parkedShare,
            String excess,
            String disputeFee,
            String respondBy,
            String openedAt,
            List<StageChangeView> history,
            List<EvidenceView> evidence,
            List<OperatorResponseView> responses) {}

    /** One payment's disputes, oldest first. */
    public record OperatorDisputeList(List<OperatorDisputeView> disputes) {}

    /** The merchant's disputes, newest first. */
    public MerchantDisputeList listForMerchant(MerchantId merchant) {
        List<DisputeStore.Found> found =
                inOneTransaction(
                        unitOfWork ->
                                reads.listForCounterparties(
                                        unitOfWork,
                                        payablesOf(unitOfWork, merchant),
                                        MERCHANT_LISTING_BOUND + 1));
        boolean truncated = found.size() > MERCHANT_LISTING_BOUND;
        return new MerchantDisputeList(
                found.stream()
                        .limit(MERCHANT_LISTING_BOUND)
                        .map(DisputeOperations::summary)
                        .toList(),
                truncated);
    }

    /** One of the merchant's disputes; another merchant's, unknown and malformed are one 404. */
    public MerchantDisputeView readForMerchant(MerchantId merchant, String rawDisputeId) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        DisputeReads.Read read =
                inOneTransaction(
                                unitOfWork ->
                                        reads.readForCounterparties(
                                                unitOfWork, id, payablesOf(unitOfWork, merchant)))
                        .orElseThrow(DisputeOperations::disputeNotFound);
        Dispute dispute = read.found().dispute();
        return new MerchantDisputeView(
                dispute.id().value().toString(),
                read.found().intentId().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                respondBy(dispute),
                dispute.openedAt().toString(),
                history(read.history()),
                evidenceViews(read.evidence()),
                read.responses().stream().map(DisputeOperations::responseView).toList());
    }

    /** The operator's read of one dispute — audited; unknown and malformed are one 404. */
    public OperatorDisputeView readForOperator(String rawDisputeId) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        DisputeReads.Read read =
                inOneTransaction(unitOfWork -> reads.readForOperator(unitOfWork, id))
                        .orElseThrow(DisputeOperations::disputeNotFound);
        return operatorView(read);
    }

    /**
     * The operator's read of one payment's disputes — each dispute shown audited; an unknown
     * payment is the 404, a payment nobody disputed an empty list.
     */
    public OperatorDisputeList listForPaymentForOperator(String rawIntentId) {
        PaymentIntentId intent = parsedIntentOrAbsent(rawIntentId);
        List<DisputeReads.Read> found =
                inOneTransaction(unitOfWork -> reads.listForPaymentForOperator(unitOfWork, intent))
                        .orElseThrow(DisputeOperations::paymentNotFound);
        return new OperatorDisputeList(
                found.stream().map(DisputeOperations::operatorView).toList());
    }

    // ----------------------------------------------------------------- the acts (P7-TSK-014)

    /** The merchant attaches a document to one of its disputes. */
    public EvidenceView uploadAsMerchant(
            MerchantId merchant, String rawDisputeId, DisputeEvidenceUploadRequest body) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        return upload(counterparty(merchant), id, body.kind(), body.contentType(), body.content());
    }

    /** The operator attaches a document for a payment with no merchant, reasoned. */
    public EvidenceView uploadAsOperator(
            String rawDisputeId, OperatorDisputeEvidenceUploadRequest body) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        return upload(
                new DisputeActor.Operator(Optional.of(body.reason()), OPERATOR_REACH),
                id,
                body.kind(),
                body.contentType(),
                body.content());
    }

    /** One of the merchant's documents with its content — on the record. */
    public EvidenceContentView readEvidenceAsMerchant(
            MerchantId merchant, String rawDisputeId, String rawEvidenceId) {
        return readEvidence(counterparty(merchant), rawDisputeId, rawEvidenceId);
    }

    /** Any dispute's document with its content, for an operator — on the record. */
    public EvidenceContentView readEvidenceAsOperator(String rawDisputeId, String rawEvidenceId) {
        return readEvidence(
                new DisputeActor.Operator(Optional.empty(), OPERATOR_REACH),
                rawDisputeId,
                rawEvidenceId);
    }

    /** The merchant answers one of its disputes — keyed per merchant. */
    public ResponseAnswer respondAsMerchant(
            MerchantId merchant,
            String rawDisputeId,
            DisputeResponseKind kind,
            String idempotencyKey) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        return respond(counterparty(merchant), id, kind, idempotencyKey);
    }

    /** The operator answers a dispute on a payment with no merchant, reasoned — keyed per
     * operator. */
    public ResponseAnswer respondAsOperator(
            String rawDisputeId,
            DisputeResponseKind kind,
            OperatorDisputeResponseRequest body,
            String idempotencyKey) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        return respond(
                new DisputeActor.Operator(Optional.of(body.reason()), OPERATOR_REACH),
                id,
                kind,
                idempotencyKey);
    }

    private EvidenceView upload(
            DisputeActor actor,
            DisputeId id,
            DisputeEvidenceKind kind,
            DisputeEvidenceContentType contentType,
            String base64) {
        DisputeEvidenceContent content = decode(base64);
        try {
            // NOT wrapped in a transaction: the domain runs its own (the lock order lives there).
            DisputeEvidenceAccess.Uploaded uploaded =
                    evidence.upload(actor, id, kind, contentType, content);
            return evidenceView(uploaded.evidence());
        } catch (UnknownDisputeException unknown) {
            throw disputeNotFound();
        } catch (DisputeResponseRefusedException refused) {
            throw refusal(refused);
        }
    }

    private EvidenceContentView readEvidence(
            DisputeActor actor, String rawDisputeId, String rawEvidenceId) {
        DisputeId dispute = parsedDisputeOrAbsent(rawDisputeId);
        DisputeEvidenceId id = parsedEvidenceOrAbsent(rawEvidenceId);
        DisputeEvidenceStore.Content read =
                evidence.read(actor, dispute, id).orElseThrow(DisputeOperations::evidenceNotFound);
        DisputeEvidence document = read.evidence();
        return new EvidenceContentView(
                document.id().value().toString(),
                document.kind().name(),
                document.contentType().name(),
                document.contentLength(),
                document.uploadedAt().toString(),
                Base64.getEncoder().encodeToString(read.content().value()));
    }

    private ResponseAnswer respond(
            DisputeActor actor, DisputeId id, DisputeResponseKind kind, String idempotencyKey) {
        DisputeResponses command = responses.getIfAvailable();
        if (command == null) {
            throw new ApiException(
                    PaymentsErrorCode.PROVIDER_UNAVAILABLE,
                    "A dispute answer arrived on a deployment with no card PSP configured");
        }
        DisputeResponses.ResponseResult result;
        try {
            // NOT wrapped in a transaction: the command runs its own Tx1 / call / Tx2.
            result = command.respond(actor, id, kind, idempotencyKey);
        } catch (UnknownDisputeException unknown) {
            throw disputeNotFound();
        } catch (DisputeResponseRefusedException refused) {
            throw refusal(refused);
        } catch (DisputeResponseKeyReusedException reused) {
            // The kernel's own words for a reused key, as the refund door answers it.
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "A dispute response key already carries a different response (INV-IDEM-03)",
                    "This " + IdempotencyKeyHeader.NAME
                            + " was already used for a different request. Use a new key for a"
                            + " new action, or resend the original request unchanged.");
        }
        return new ResponseAnswer(
                result.response().value().toString(),
                kind.name(),
                result.status().name(),
                result.failure().map(Enum::name).orElse(null));
    }

    // -----------------------------------------------------------------

    private DisputeActor.Counterparty counterparty(MerchantId merchant) {
        return new DisputeActor.Counterparty(
                inOneTransaction(unitOfWork -> payablesOf(unitOfWork, merchant)));
    }

    /**
     * The merchant's payables, from the ledger's OWNER-SCOPED read of the authenticated
     * merchant: every account here is provably this merchant's, which is what makes the
     * dispute statement's predicate a tenant predicate.
     */
    private Set<LedgerAccountId> payablesOf(Connection unitOfWork, MerchantId merchant) {
        return ledgerAccounts.findAllOwned(unitOfWork, merchant.value()).stream()
                .filter(account -> account.purpose() == AccountPurpose.MERCHANT_PAYABLE)
                .map(LedgerAccount::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Decodes and bounds the content, each refusal a {@code 422} naming the field — the KYC
     * upload's decode, the client detail written here rather than passed through.
     */
    private static DisputeEvidenceContent decode(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A dispute evidence upload carried content that is not valid base64",
                    "content must be base64-encoded");
        }
        try {
            return DisputeEvidenceContent.of(bytes);
        } catch (IllegalArgumentException outOfBounds) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A dispute evidence upload was refused by the content bounds",
                    "content must decode to between 1 byte and "
                            + DisputeEvidenceContent.MAX_BYTES
                            + " bytes");
        }
    }

    private static ApiException refusal(DisputeResponseRefusedException refused) {
        PaymentsErrorCode code =
                switch (refused.refusal()) {
                    case NOT_RESPONDABLE -> PaymentsErrorCode.DISPUTE_NOT_RESPONDABLE;
                    case DEADLINE_PASSED -> PaymentsErrorCode.DISPUTE_DEADLINE_PASSED;
                    case ALREADY_ANSWERED -> PaymentsErrorCode.DISPUTE_ALREADY_ANSWERED;
                    case EVIDENCE_REQUIRED -> PaymentsErrorCode.DISPUTE_EVIDENCE_REQUIRED;
                    case EVIDENCE_LIMIT_REACHED -> PaymentsErrorCode.DISPUTE_EVIDENCE_LIMIT_REACHED;
                    case COUNTERPARTY_ANSWERS -> PaymentsErrorCode.DISPUTE_ANSWERED_BY_ITS_MERCHANT;
                };
        return new ApiException(code, "A dispute act was refused: " + refused.refusal());
    }

    private static MerchantDisputeSummary summary(DisputeStore.Found found) {
        Dispute dispute = found.dispute();
        return new MerchantDisputeSummary(
                dispute.id().value().toString(),
                found.intentId().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                dispute.openedAt().toString());
    }

    private static OperatorDisputeView operatorView(DisputeReads.Read read) {
        DisputeStore.Found found = read.found();
        Dispute dispute = found.dispute();
        return new OperatorDisputeView(
                dispute.id().value().toString(),
                found.intentId().value().toString(),
                dispute.attemptId().value().toString(),
                dispute.providerReference().value(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                decimal(dispute.split().map(com.finapp.payments.ChargebackSplit::counterpartyShare)),
                decimal(dispute.split().map(com.finapp.payments.ChargebackSplit::parkedShare)),
                decimal(dispute.split().map(com.finapp.payments.ChargebackSplit::excess)),
                decimal(dispute.fee()),
                respondBy(dispute),
                dispute.openedAt().toString(),
                history(read.history()),
                evidenceViews(read.evidence()),
                read.responses().stream().map(DisputeOperations::operatorResponseView).toList());
    }

    private static List<EvidenceView> evidenceViews(List<DisputeEvidence> documents) {
        return documents.stream().map(DisputeOperations::evidenceView).toList();
    }

    private static EvidenceView evidenceView(DisputeEvidence document) {
        return new EvidenceView(
                document.id().value().toString(),
                document.kind().name(),
                document.contentType().name(),
                document.contentLength(),
                document.uploadedAt().toString());
    }

    private static ResponseView responseView(DisputeResponse response) {
        return new ResponseView(
                response.id().value().toString(),
                response.kind().name(),
                response.status().name(),
                response.failure().map(Enum::name).orElse(null),
                response.createdAt().toString());
    }

    private static OperatorResponseView operatorResponseView(DisputeResponse response) {
        return new OperatorResponseView(
                response.id().value().toString(),
                response.kind().name(),
                response.status().name(),
                response.failure().map(Enum::name).orElse(null),
                response.requestedByType(),
                response.providerReference().map(com.finapp.payments.ProviderReference::value)
                        .orElse(null),
                response.createdAt().toString());
    }

    /** What the network took, as a decimal string - null until the funds are taken. */
    private static String chargebackAmount(Dispute dispute) {
        return decimal(dispute.chargeback());
    }

    /** A decimal string, or null for an absent amount - never a zero standing for "unknown". */
    private static String decimal(Optional<com.finapp.sharedkernel.money.Money> amount) {
        return amount.map(money -> money.toBigDecimal().toPlainString()).orElse(null);
    }

    private static String chargebackCurrency(Dispute dispute) {
        return dispute.chargeback().map(amount -> amount.currency().code()).orElse(null);
    }

    /** The network's deadline, or null until stated - never a guess. */
    private static String respondBy(Dispute dispute) {
        return dispute.respondBy().map(Object::toString).orElse(null);
    }

    private static List<StageChangeView> history(List<DisputeStore.StageChange> trail) {
        return trail.stream()
                .map(
                        change ->
                                new StageChangeView(
                                        change.from().name(),
                                        change.to().name(),
                                        change.at().toString()))
                .toList();
    }

    private static DisputeId parsedDisputeOrAbsent(String raw) {
        try {
            return DisputeId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            // Malformed folds into unknown: one 404, and the shape rule leaks nothing.
            throw disputeNotFound();
        }
    }

    private static DisputeEvidenceId parsedEvidenceOrAbsent(String raw) {
        try {
            return DisputeEvidenceId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw evidenceNotFound();
        }
    }

    private static PaymentIntentId parsedIntentOrAbsent(String raw) {
        try {
            return PaymentIntentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw paymentNotFound();
        }
    }

    private static ApiException disputeNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No dispute visible to the caller matches the requested identifier",
                "no such dispute.");
    }

    private static ApiException evidenceNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No dispute evidence visible to the caller matches the requested identifiers",
                "no such dispute evidence.");
    }

    /**
     * The chargeback-ratio report for {@code month} ({@code YYYY-MM}; the clock's current UTC month
     * when absent), read and put on the record in one transaction (`P7-TSK-015`). Only rails whose
     * declared dispute model is card chargebacks count, as data ({@code INV-RAIL-01}); only accounts
     * whose purpose is a merchant's payable are a merchant's (a wallet top-up's chargeback is the
     * operator's desk, not a merchant's ratio).
     */
    public ChargebackRatioReport chargebackRatioReport(String month) {
        java.time.YearMonth period = month == null ? currentMonth() : parsedMonth(month);
        java.time.Instant from = period.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        java.time.Instant to =
                period.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        Set<com.finapp.payments.RailId> chargebackRails =
                rails.declaredIds().stream()
                        .filter(
                                rail ->
                                        rails.capabilitiesOf(rail).disputes()
                                                == com.finapp.payments.RailCapabilities
                                                        .DisputeModel.CARD_SCHEME_CHARGEBACKS)
                        .collect(Collectors.toSet());
        return inOneTransaction(
                unitOfWork -> {
                    List<DisputeStore.CreditedCounts> counts =
                            reads.chargebackCountsForOperator(
                                    unitOfWork, chargebackRails, from, to, period.toString());
                    java.util.Map<LedgerAccountId, UUID> merchantOf =
                            ledgerAccounts
                                    .findAllById(
                                            unitOfWork,
                                            counts.stream()
                                                    .map(DisputeStore.CreditedCounts::creditAccount)
                                                    .toList())
                                    .stream()
                                    .filter(
                                            account ->
                                                    account.purpose()
                                                                    == AccountPurpose.MERCHANT_PAYABLE
                                                            && account.ownerRef().isPresent())
                                    .collect(
                                            Collectors.toMap(
                                                    LedgerAccount::id,
                                                    account -> account.ownerRef().get()));
                    // A merchant may hold one payable per currency: its month is their sum.
                    java.util.Map<UUID, long[]> byMerchant = new java.util.HashMap<>();
                    for (DisputeStore.CreditedCounts account : counts) {
                        UUID merchant = merchantOf.get(account.creditAccount());
                        if (merchant == null) {
                            continue;
                        }
                        long[] sums = byMerchant.computeIfAbsent(merchant, key -> new long[2]);
                        sums[0] += account.sales();
                        sums[1] += account.chargebacks();
                    }
                    List<MerchantChargebackRatio> ranked =
                            byMerchant.entrySet().stream()
                                    .map(
                                            entry ->
                                                    new MerchantChargebackRatio(
                                                            entry.getKey().toString(),
                                                            entry.getValue()[0],
                                                            entry.getValue()[1],
                                                            ratioOf(
                                                                    entry.getValue()[1],
                                                                    entry.getValue()[0])))
                                    .sorted(DisputeOperations::worstFirst)
                                    .toList();
                    return new ChargebackRatioReport(
                            period.toString(),
                            from.toString(),
                            to.toString(),
                            ranked.subList(0, Math.min(ranked.size(), RATIO_REPORT_BOUND)),
                            ranked.size() > RATIO_REPORT_BOUND);
                });
    }

    private java.time.YearMonth currentMonth() {
        return java.time.YearMonth.now(clock.withZone(java.time.ZoneOffset.UTC));
    }

    /**
     * The period asked for, bounded at the boundary (the gate's own find): a calendar month from
     * {@link #EARLIEST_REPORT_MONTH} to the current one. A month that has not happened yet holds
     * nothing to report, and a year the regex admits but no record can carry (9999-12's window ends
     * in year 10000) must be refused here, never discovered by the database driver.
     */
    private java.time.YearMonth parsedMonth(String month) {
        if (MONTH.matcher(month).matches()) {
            try {
                java.time.YearMonth period = java.time.YearMonth.parse(month);
                if (!period.isBefore(EARLIEST_REPORT_MONTH) && !period.isAfter(currentMonth())) {
                    return period;
                }
            } catch (java.time.format.DateTimeParseException unparseable) {
                // Falls through to the refusal: 2026-13 has the shape and no meaning.
            }
        }
        throw new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "A chargeback-ratio report was asked for a period that is not a reportable"
                        + " calendar month",
                "'month' must be a calendar month, YYYY-MM, from " + EARLIEST_REPORT_MONTH
                        + " to the current month.");
    }

    /** Chargebacks over sales to four places, half up — BigDecimal, never a double; none without sales. */
    private static String ratioOf(long chargebacks, long sales) {
        if (sales == 0) {
            return null;
        }
        return java.math.BigDecimal.valueOf(chargebacks)
                .divide(java.math.BigDecimal.valueOf(sales), 4, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }

    /**
     * Worst first: chargebacks against no sales, then the highest ratio, then the most chargebacks,
     * then the merchant id — a total order, so the bound cuts the same merchants every time.
     */
    private static int worstFirst(MerchantChargebackRatio left, MerchantChargebackRatio right) {
        boolean leftUndefined = left.ratio() == null && left.chargebacks() > 0;
        boolean rightUndefined = right.ratio() == null && right.chargebacks() > 0;
        if (leftUndefined != rightUndefined) {
            return leftUndefined ? -1 : 1;
        }
        java.math.BigDecimal leftRatio =
                left.ratio() == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(left.ratio());
        java.math.BigDecimal rightRatio =
                right.ratio() == null
                        ? java.math.BigDecimal.ZERO
                        : new java.math.BigDecimal(right.ratio());
        int byRatio = rightRatio.compareTo(leftRatio);
        if (byRatio != 0) {
            return byRatio;
        }
        int byChargebacks = Long.compare(right.chargebacks(), left.chargebacks());
        if (byChargebacks != 0) {
            return byChargebacks;
        }
        return left.merchantId().compareTo(right.merchantId());
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No payment matches the requested identifier",
                "no such payment.");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
