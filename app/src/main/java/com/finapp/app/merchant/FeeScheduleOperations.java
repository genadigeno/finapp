package com.finapp.app.merchant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.PendingDecisions;
import com.finapp.merchant.BackdatedFeeScheduleVersionException;
import com.finapp.merchant.BnplFeeAssignmentProposal;
import com.finapp.merchant.BnplPricing;
import com.finapp.merchant.FeeAssignmentProposal;
import com.finapp.merchant.FeeCurrencyMismatchException;
import com.finapp.merchant.FeeProposalDecision;
import com.finapp.merchant.FeeProposalRefused;
import com.finapp.merchant.FeeProposalStore;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeScheduleProposals;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeScheduleVersionProposal;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.merchant.UnknownFeeScheduleException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's fee-schedule surface behind the controllers (`P6-TSK-004`) — the
 * {@code MerchantOperations} shape: parse and refuse at the boundary, one transaction per
 * command, domain refusals translated to the registered codes.
 *
 * <p><strong>Views render the terms, and nothing else.</strong> A fee schedule is the
 * platform's own pricing; there is no tenant here and no merchant data in any of these
 * responses, which is why {@code INV-MER-01} has no subject in this task. A merchant reading
 * its own pricing is a real capability and it is {@code P6-TSK-009}'s, where it arrives with
 * the tenant predicate its own surface requires.
 *
 * <p><strong>By four eyes, keyed</strong> (`P17-TSK-123`; ADR-0154's shape, finapp-admin-panel ADR-C135 decisions 2 and 3,
 * ADR-0125 decision 4). A version and a merchant's assignment are now proposals - judged first by the effect's own rules,
 * answered {@code PROPOSED} - decided by a holder of {@code FEE_SCHEDULE_APPROVE} who did not propose them, the approval
 * performing the existing effect in the decision's transaction (merchant's {@link FeeScheduleProposals}); an assignment
 * already in place converges, {@code ASSIGNED}, nothing proposed. A schedule's creation stays one person's - a named
 * container carries no price - and is keyed. Every act is keyed per principal on the platform's {@link IdempotentExecutor}
 * in the act's own transaction ({@code merchant.fee-*:<keyedType>:<id>}): a lost answer replays the first, another request
 * under the key is {@code 409} ({@code INV-IDEM-01}, {@code INV-IDEM-03}). Each kind's proposals awaiting a decision are a
 * queue in `P17-TSK-040`'s one pending-decision shape ({@link PendingDecisions}; finapp-admin-panel ADR-C133 decision 3), and
 * one proposal of any kind - `P17-TSK-116`'s BNPL fee assignments too - is read by its identifier, with the schedule it
 * names, because the approver holds no {@code FEE_ADMINISTER} to read it otherwise. The reads are configuration:
 * unrecorded, as the fee reads beside them are.
 */
@RequiredArgsConstructor
public class FeeScheduleOperations {

    /** A schedule's creation: its key's scope (`P17-TSK-123`). */
    static final String SCHEDULE_SCOPE = "merchant.fee-schedule:";
    /** A version's proposal. */
    static final String VERSION_PROPOSAL_SCOPE = "merchant.fee-schedule-version:";
    /** A version proposal's approval. */
    static final String VERSION_APPROVAL_SCOPE = "merchant.fee-schedule-version-approval:";
    /** A version proposal's rejection. */
    static final String VERSION_REJECTION_SCOPE = "merchant.fee-schedule-version-rejection:";
    /** A merchant fee assignment's proposal. */
    static final String ASSIGNMENT_PROPOSAL_SCOPE = "merchant.fee-assignment:";
    /** A merchant fee assignment proposal's approval. */
    static final String ASSIGNMENT_APPROVAL_SCOPE = "merchant.fee-assignment-approval:";
    /** A merchant fee assignment proposal's rejection. */
    static final String ASSIGNMENT_REJECTION_SCOPE = "merchant.fee-assignment-rejection:";

    /** The version proposals' queue's collection name, inside its cursors (`P17-TSK-040`'s rule). */
    static final String VERSIONS_QUEUE = "pending.merchant.fee-schedule-versions";
    /** The assignment proposals' queue's collection name, inside its cursors. */
    static final String ASSIGNMENTS_QUEUE = "pending.merchant.fee-assignments";

    /** A schedule as an operator sees it listed. */
    public record FeeScheduleView(String feeScheduleId, String name, String currency) {}

    /** One immutable version's terms, exactly as they will price. */
    public record FeeScheduleVersionView(
            String feeScheduleVersionId,
            int version,
            String rate,
            long fixedAmountMinor,
            String currency,
            String roundingPolicy,
            String refundFeePolicy,
            String effectiveFrom) {}

    /** A schedule and every version of it, newest-effective first. */
    public record FeeScheduleDetailView(
            String feeScheduleId,
            String name,
            String currency,
            List<FeeScheduleVersionView> versions) {}

    /**
     * Which schedule prices - or, {@code PROPOSED}, is proposed to price - a merchant (`P17-TSK-123`, the BNPL view's
     * shape): {@code ASSIGNED} when the pointer is there, {@code PROPOSED} or {@code REJECTED} for a proposal,
     * {@code APPROVED} for one approved (the pointer moved), each naming its {@code proposalId}.
     */
    public record MerchantFeeScheduleView(String merchantId, String feeScheduleId, String status, String proposalId) {}

    /**
     * A fee schedule version proposal's receipt (`P17-TSK-123`): {@code PROPOSED}, {@code APPROVED} with the version it
     * minted, or {@code REJECTED}. Never a reason or a person.
     */
    public record FeeScheduleVersionProposalReceipt(
            String proposalId, String feeScheduleId, String status, String feeScheduleVersionId) {}

    /** A version proposal's terms, as {@link FeeScheduleVersionView} renders a version's; {@code effectiveFrom} absent is "at approval". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ProposedFeeTermsView(
            String rate,
            long fixedAmountMinor,
            String currency,
            String roundingPolicy,
            String refundFeePolicy,
            String effectiveFrom) {}

    /**
     * One fee proposal of any kind as its approver reads it (`P17-TSK-123`): what was proposed, by whom, when and why; its
     * decision when decided (who and when, never the decider's reason); the schedule it names with every version; for a
     * version proposal its terms and, once approved, the version it minted; for an assignment the merchant's current
     * pointer (and a BNPL product).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeeProposalView(
            String id,
            String kind,
            String status,
            String subjectKind,
            String subjectId,
            String proposedBy,
            String proposedAt,
            String reason,
            String decidedBy,
            String decidedAt,
            String product,
            String currentFeeScheduleId,
            ProposedFeeTermsView proposedTerms,
            String feeScheduleVersionId,
            FeeScheduleDetailView feeSchedule) {}

    @NonNull private final FeeSchedules feeSchedules;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final FeeScheduleProposals proposals;
    @NonNull private final BnplPricing bnplPricing;

    /** Creates a named pricing identity, keyed: a retry answers the schedule created first. */
    public FeeScheduleView create(String idempotencyKey, CreateFeeScheduleRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        CurrencyCode currency = CurrencyCode.of(body.currency());
        Actor actor = SecurityContext.require();
        // The name last, read back with a bounded split: a name may hold any character.
        String[] stored = keyed(SCHEDULE_SCOPE, actor, idempotencyKey, "create|" + currency.code() + "|" + body.name(),
                uow -> {
                    FeeSchedule schedule = feeSchedules.create(uow, body.name(), currency);
                    return String.join("|", schedule.id().value().toString(), schedule.currency().code(), schedule.name());
                }, 3);
        return new FeeScheduleView(stored[0], stored[2], stored[1]);
    }

    /** Every schedule, newest first. */
    public List<FeeScheduleView> list() {
        return inOneTransaction(
                unitOfWork ->
                        feeSchedules.list(unitOfWork).stream()
                                .map(FeeScheduleOperations::renderSchedule)
                                .toList());
    }

    /** The schedule and its versions, or the one 404 for unknown-and-malformed alike. */
    public FeeScheduleDetailView view(String rawId) {
        FeeScheduleId id = parsedScheduleOrAbsent(rawId);
        try {
            return inOneTransaction(unitOfWork -> renderDetail(feeSchedules.detail(unitOfWork, id)));
        } catch (UnknownFeeScheduleException unknown) {
            throw scheduleNotFound();
        }
    }

    /**
     * Proposes a version, effective forward, keyed (`P17-TSK-123`): judged now as the version would be minted - the
     * backdating refusal is the one an operator meets most, "effective from the first of the month", typed on the second -
     * and answered {@code PROPOSED}; a second person's approval mints it.
     */
    public FeeScheduleVersionProposalReceipt proposeVersion(
            String idempotencyKey, String rawId, CreateFeeScheduleVersionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        FeeScheduleId id = parsedScheduleOrAbsent(rawId);
        // Parsed at the boundary: an unknown policy name is the caller's mistake, refused as a
        // 422 rather than surfacing from inside the domain as our 500.
        RoundingPolicy rounding = parsedRounding(body.roundingPolicy());
        RefundFeePolicy refundFee = parsedRefundFee(body.refundFeePolicy());
        FeeRate rate = parsedRate(body.rate());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String fingerprinted = String.join("|", "propose", id.value().toString(), rate.toBigDecimal().toPlainString(),
                Long.toString(body.fixedAmountMinor()), rounding.policyName(), refundFee.name(),
                body.effectiveFrom() == null ? "" : body.effectiveFrom().toString(), digest(body.reason()));
        return versionReceipt(keyed(VERSION_PROPOSAL_SCOPE, actor, idempotencyKey, fingerprinted, uow -> {
            // The fixed part's currency is the SCHEDULE's - read here so the caller never states
            // a currency that can only be wrong.
            FeeSchedule schedule = feeSchedules.detail(uow, id).schedule();
            FeeSchedules.NewVersion terms = new FeeSchedules.NewVersion(rate,
                    Money.ofMinorUnits(body.fixedAmountMinor(), schedule.currency()), rounding, refundFee,
                    Optional.ofNullable(body.effectiveFrom()), body.reason());
            return storedVersion(proposals.proposeVersion(uow, id, terms, actor, correlation));
        }, 4));
    }

    /** Approves a version proposal the caller did not propose (`P17-TSK-123`): the version is minted. */
    public FeeScheduleVersionProposalReceipt approveVersion(String idempotencyKey, String rawProposalId, String reason) {
        FeeScheduleVersionProposal.Id id = FeeScheduleVersionProposal.Id.of(proposalOrAbsent(rawProposalId));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return versionReceipt(keyed(VERSION_APPROVAL_SCOPE, actor, idempotencyKey, "approve|" + id.value() + "|"
                + digest(reason), uow -> storedVersion(proposals.approveVersion(uow, id, reason, actor, correlation)
                        .orElseThrow(FeeScheduleOperations::proposalNotFound)), 4));
    }

    /** Rejects a version proposal the caller did not propose (`P17-TSK-123`): nothing is minted. */
    public FeeScheduleVersionProposalReceipt rejectVersion(String idempotencyKey, String rawProposalId, String reason) {
        FeeScheduleVersionProposal.Id id = FeeScheduleVersionProposal.Id.of(proposalOrAbsent(rawProposalId));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return versionReceipt(keyed(VERSION_REJECTION_SCOPE, actor, idempotencyKey, "reject|" + id.value() + "|"
                + digest(reason), uow -> storedVersion(proposals.rejectVersion(uow, id, reason, actor, correlation)
                        .orElseThrow(FeeScheduleOperations::proposalNotFound)), 4));
    }

    /**
     * Proposes pointing a merchant at a schedule, reasoned and keyed (`P17-TSK-123`): judged now, answered
     * {@code PROPOSED}; {@code ASSIGNED} with nothing proposed when the merchant is already on it.
     */
    public MerchantFeeScheduleView proposeAssignment(
            String idempotencyKey, String rawMerchantId, AssignFeeScheduleRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchantId = parsedMerchantOrAbsent(rawMerchantId);
        FeeScheduleId scheduleId;
        try {
            scheduleId = FeeScheduleId.of(body.feeScheduleId());
        } catch (IllegalArgumentException malformed) {
            throw scheduleNotFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return assignmentView(keyed(ASSIGNMENT_PROPOSAL_SCOPE, actor, idempotencyKey, String.join("|", "propose",
                merchantId.value().toString(), scheduleId.value().toString(), digest(body.reason())), uow ->
                proposals.proposeAssignment(uow, merchantId, scheduleId, body.reason(), actor, correlation).proposal()
                        .map(FeeScheduleOperations::storedAssignment)
                        .orElseGet(() -> String.join("|", merchantId.value().toString(), scheduleId.value().toString(),
                                "ASSIGNED", "")), 4));
    }

    /** Approves a merchant fee assignment proposal the caller did not propose (`P17-TSK-123`): the pointer moves. */
    public MerchantFeeScheduleView approveAssignment(String idempotencyKey, String rawProposalId, String reason) {
        FeeAssignmentProposal.Id id = FeeAssignmentProposal.Id.of(proposalOrAbsent(rawProposalId));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return assignmentView(keyed(ASSIGNMENT_APPROVAL_SCOPE, actor, idempotencyKey, "approve|" + id.value() + "|"
                + digest(reason), uow -> storedAssignment(proposals.approveAssignment(uow, id, reason, actor, correlation)
                        .orElseThrow(FeeScheduleOperations::proposalNotFound)), 4));
    }

    /** Rejects a merchant fee assignment proposal the caller did not propose (`P17-TSK-123`): nothing changes. */
    public MerchantFeeScheduleView rejectAssignment(String idempotencyKey, String rawProposalId, String reason) {
        FeeAssignmentProposal.Id id = FeeAssignmentProposal.Id.of(proposalOrAbsent(rawProposalId));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return assignmentView(keyed(ASSIGNMENT_REJECTION_SCOPE, actor, idempotencyKey, "reject|" + id.value() + "|"
                + digest(reason), uow -> storedAssignment(proposals.rejectAssignment(uow, id, reason, actor, correlation)
                        .orElseThrow(FeeScheduleOperations::proposalNotFound)), 4));
    }

    /** Which schedule prices this merchant, {@code ASSIGNED}, or the one 404 when none does. */
    public MerchantFeeScheduleView assignmentOf(String rawMerchantId) {
        MerchantId merchantId = parsedMerchantOrAbsent(rawMerchantId);
        return inOneTransaction(
                unitOfWork ->
                        feeSchedules
                                .assignmentOf(unitOfWork, merchantId)
                                .map(
                                        scheduleId ->
                                                new MerchantFeeScheduleView(
                                                        merchantId.value().toString(),
                                                        scheduleId.value().toString(),
                                                        "ASSIGNED",
                                                        null))
                                .orElseThrow(FeeScheduleOperations::scheduleNotFound));
    }

    // ----------------------------------------------------------------- the approver's reads

    /**
     * {@code GET /v1/operator/pending-decisions/merchant/fee-schedule-versions} (`P17-TSK-123`, in `P17-TSK-040`'s one
     * pending-decision shape; finapp-admin-panel ADR-C133 decision 3): the fee schedule version proposals awaiting a second
     * person, oldest first. Configuration: unrecorded, as the fee reads are.
     */
    public PendingDecisions.PendingDecisionPage pendingVersions(String status, String cursor, String limit) {
        return pending(FeeProposalStore.Kind.FEE_SCHEDULE_VERSION, VERSIONS_QUEUE, status, cursor, limit);
    }

    /** {@code GET /v1/operator/pending-decisions/merchant/fee-assignments}: the merchant fee assignment proposals, as above. */
    public PendingDecisions.PendingDecisionPage pendingAssignments(String status, String cursor, String limit) {
        return pending(FeeProposalStore.Kind.FEE_ASSIGNMENT, ASSIGNMENTS_QUEUE, status, cursor, limit);
    }

    /**
     * A kind's queue under the shared rules: {@code PROPOSED} or no status, proposed at ascending and id ascending, keyset
     * after the cursor's position, a look-ahead row naming the next cursor.
     */
    private PendingDecisions.PendingDecisionPage pending(FeeProposalStore.Kind kind, String collection, String status,
            String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<FeeProposalStore.PendingCursor> after = PendingDecisions.position(collection, cursor)
                .map(position -> new FeeProposalStore.PendingCursor(position.proposedAt(), position.id()));
        List<FeeProposalStore.Pending> rows = inOneTransaction(uow -> proposals.pending(uow, kind, after, size + 1));
        return PendingDecisions.page(collection, rows.stream().map(FeeScheduleOperations::pendingItem).toList(), size);
    }

    /** One open proposal in the pending-decision shape: its subject the schedule or the merchant its doors name. */
    static PendingDecisions.PendingDecision pendingItem(FeeProposalStore.Pending row) {
        return PendingDecisions.of(row.id(), row.kind().qualified(), row.proposedBy(), row.proposedAt(), row.reason(),
                row.subjectId());
    }

    /**
     * {@code GET /v1/operator/fee-schedule-proposals/{id}} (`P17-TSK-123`): one proposal of any kind, with the schedule it
     * names, so its approver decides what they can see. Unknown and malformed are the one 404. Configuration: unrecorded.
     */
    public FeeProposalView proposal(String rawProposalId) {
        UUID id = proposalOrAbsent(rawProposalId);
        return inOneTransaction(uow -> proposals.find(uow, id).map(found -> proposalView(uow, found)))
                .orElseThrow(FeeScheduleOperations::proposalNotFound);
    }

    private FeeProposalView proposalView(Connection uow, FeeProposalStore.Found found) {
        return switch (found) {
            case FeeProposalStore.FoundVersion(FeeScheduleVersionProposal proposal) -> {
                Optional<FeeProposalDecision> decision = proposal.decision();
                yield new FeeProposalView(proposal.id().value().toString(),
                        FeeProposalStore.Kind.FEE_SCHEDULE_VERSION.qualified(), proposal.status().name(),
                        FeeProposalStore.Kind.FEE_SCHEDULE_VERSION.subjectKind(),
                        proposal.feeScheduleId().value().toString(), proposal.proposedBy(),
                        proposal.proposedAt().toString(), proposal.proposalReason(),
                        decision.map(FeeProposalDecision::decidedBy).orElse(null),
                        decision.map(made -> made.decidedAt().toString()).orElse(null), null, null,
                        new ProposedFeeTermsView(proposal.rate().toBigDecimal().toPlainString(),
                                proposal.fixed().minorUnits(), proposal.fixed().currency().code(),
                                proposal.roundingPolicy().policyName(), proposal.refundFeePolicy().name(),
                                proposal.effectiveFrom().map(Instant::toString).orElse(null)),
                        proposal.mintedVersion().map(minted -> minted.value().toString()).orElse(null),
                        renderDetail(feeSchedules.detail(uow, proposal.feeScheduleId())));
            }
            case FeeProposalStore.FoundAssignment(FeeAssignmentProposal proposal) -> {
                Optional<FeeProposalDecision> decision = proposal.decision();
                yield new FeeProposalView(proposal.id().value().toString(),
                        FeeProposalStore.Kind.FEE_ASSIGNMENT.qualified(), proposal.status().name(),
                        FeeProposalStore.Kind.FEE_ASSIGNMENT.subjectKind(), proposal.merchantId().value().toString(),
                        proposal.proposedBy(), proposal.proposedAt().toString(), proposal.proposalReason(),
                        decision.map(FeeProposalDecision::decidedBy).orElse(null),
                        decision.map(made -> made.decidedAt().toString()).orElse(null), null,
                        feeSchedules.assignmentOf(uow, proposal.merchantId()).map(current -> current.value().toString())
                                .orElse(null),
                        null, null, renderDetail(feeSchedules.detail(uow, proposal.feeScheduleId())));
            }
            case FeeProposalStore.FoundBnplAssignment(BnplFeeAssignmentProposal proposal) -> {
                Optional<BnplFeeAssignmentProposal.Decision> decision = proposal.decision();
                yield new FeeProposalView(proposal.id().value().toString(),
                        FeeProposalStore.Kind.BNPL_FEE_ASSIGNMENT.qualified(), proposal.status().name(),
                        FeeProposalStore.Kind.BNPL_FEE_ASSIGNMENT.subjectKind(),
                        proposal.merchantId().value().toString(), proposal.proposedBy(),
                        proposal.proposedAt().toString(), proposal.proposalReason(),
                        decision.map(BnplFeeAssignmentProposal.Decision::decidedBy).orElse(null),
                        decision.map(made -> made.decidedAt().toString()).orElse(null), proposal.product().name(),
                        bnplPricing.assignmentOf(uow, proposal.merchantId(), proposal.product())
                                .map(current -> current.value().toString()).orElse(null),
                        null, null, renderDetail(feeSchedules.detail(uow, proposal.feeScheduleId())));
            }
        };
    }

    // -----------------------------------------------------------------

    /**
     * One keyed act in one transaction (`P17-TSK-123`, {@code MerchantBnplDesk}'s shape) - its answer stored as
     * {@code |}-joined text and read back in {@code parts} (the last part may hold any character); a lost answer replays
     * it; a refusal stores nothing.
     */
    private String[] keyed(String scope, Actor actor, String idempotencyKey, String fingerprinted,
            Function<Connection, String> act, int parts) {
        IdempotencyKey key = new IdempotencyKey(scope + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> inOneTransaction(unitOfWork ->
                executor.execute(unitOfWork, key, fingerprint, uow -> CommandResult.succeeded(StoredResponse.of(
                        act.apply(uow).getBytes(StandardCharsets.UTF_8), "text/plain")))));
        return new String(outcome.body()
                        .orElseThrow(() -> new IllegalStateException("a fee act stores its answer")),
                StandardCharsets.UTF_8).split("\\|", parts);
    }

    private static String storedVersion(FeeScheduleVersionProposal proposal) {
        return String.join("|", proposal.id().value().toString(), proposal.feeScheduleId().value().toString(),
                proposal.status().name(), proposal.mintedVersion().map(minted -> minted.value().toString()).orElse(""));
    }

    private static FeeScheduleVersionProposalReceipt versionReceipt(String[] stored) {
        return new FeeScheduleVersionProposalReceipt(stored[0], stored[1], stored[2], stored[3].isEmpty() ? null : stored[3]);
    }

    private static String storedAssignment(FeeAssignmentProposal proposal) {
        return String.join("|", proposal.merchantId().value().toString(), proposal.feeScheduleId().value().toString(),
                proposal.status().name(), proposal.id().value().toString());
    }

    private static MerchantFeeScheduleView assignmentView(String[] stored) {
        return new MerchantFeeScheduleView(stored[0], stored[1], stored[2], stored[3].isEmpty() ? null : stored[3]);
    }

    /** merchant's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (UnknownMerchantException unknown) {
            throw merchantNotFound();
        } catch (UnknownFeeScheduleException unknown) {
            throw scheduleNotFound();
        } catch (FeeProposalRefused refused) {
            MerchantErrorCode code = switch (refused.reason()) {
                case ALREADY_PROPOSED -> MerchantErrorCode.FEE_PROPOSAL_ALREADY_OPEN;
                case ALREADY_DECIDED -> MerchantErrorCode.FEE_PROPOSAL_ALREADY_DECIDED;
                case SELF_DECISION -> MerchantErrorCode.FEE_PROPOSAL_SELF_DECISION;
            };
            throw new ApiException(code, "A fee proposal act was refused", refused.getMessage());
        } catch (BackdatedFeeScheduleVersionException backdated) {
            throw new ApiException(
                    MerchantErrorCode.FEE_SCHEDULE_NOT_FORWARD,
                    "A fee schedule version would have taken effect in the past",
                    "a fee schedule version takes effect forward; it cannot be backdated.");
        } catch (FeeCurrencyMismatchException mismatch) {
            throw feeCurrencyMismatch();
        } catch (FeeScheduleProposals.ReasonRefused reason) {
            // The detail never repeats the reason (INV-AUD-02).
            throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "A reason was refused",
                    "a reason of 1 to " + AuditRecord.MAX_REASON_LENGTH + " characters, holding no card or account"
                            + " number, is required.");
        }
    }

    // NAMED RATHER THAN OVERLOADED, the Money.allocateEvenly/allocateByWeights reason: as
    // two `render` methods these resolved ambiguously through inOneTransaction's type
    // variable, and the compiler was right to refuse - an overload picked by inference is an
    // overload a reader cannot pick by eye.
    private static FeeScheduleView renderSchedule(FeeSchedule schedule) {
        return new FeeScheduleView(
                schedule.id().value().toString(), schedule.name(), schedule.currency().code());
    }

    private static FeeScheduleDetailView renderDetail(FeeSchedules.ScheduleDetail detail) {
        return new FeeScheduleDetailView(
                detail.schedule().id().value().toString(),
                detail.schedule().name(),
                detail.schedule().currency().code(),
                detail.versions().stream().map(FeeScheduleOperations::renderVersion).toList());
    }

    private static FeeScheduleVersionView renderVersion(FeeScheduleVersion version) {
        return new FeeScheduleVersionView(
                version.id().value().toString(),
                version.version(),
                // A string, never a JSON number: a rate rendered as a floating-point literal
                // is a rate a client may read back inexactly, and this value is the one a
                // merchant reproduces a statement from (INV-MON-01's reasoning at the wire).
                version.rate().toBigDecimal().toPlainString(),
                version.fixed().minorUnits(),
                version.currency().code(),
                version.roundingPolicy().policyName(),
                version.refundFeePolicy().name(),
                version.effectiveFrom().toString());
    }

    private static RoundingPolicy parsedRounding(String name) {
        try {
            return RoundingPolicy.ofName(name);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version named an unknown rounding policy",
                    "the rounding policy is not one this platform knows.");
        }
    }

    private static RefundFeePolicy parsedRefundFee(String name) {
        try {
            return RefundFeePolicy.ofName(name);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version named an unknown refund fee policy",
                    "the refund fee policy is not one this platform knows.");
        }
    }

    private static FeeRate parsedRate(java.math.BigDecimal rate) {
        try {
            return FeeRate.of(rate);
        } catch (IllegalArgumentException malformed) {
            // FeeRate's own bounds, restated by the request annotations; reachable only if the
            // two ever disagree, and a 422 is the honest answer either way.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version carried terms the domain refuses",
                    "the fee terms are not valid.");
        }
    }

    /** Malformed equals absent — the one 404. */
    private static FeeScheduleId parsedScheduleOrAbsent(String rawId) {
        try {
            return FeeScheduleId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw scheduleNotFound();
        }
    }

    private static MerchantId parsedMerchantOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw merchantNotFound();
        }
    }

    /** A canonical UUIDv7 (ADR-0013), or the uniform 404: anything else names no proposal. */
    private static UUID proposalOrAbsent(String raw) {
        try {
            UUID parsed = UUID.fromString(raw);
            if (parsed.toString().equals(raw) && parsed.version() == 7 && parsed.variant() == 2) {
                return parsed;
            }
        } catch (IllegalArgumentException | NullPointerException malformed) {
            // the uniform 404 below
        }
        throw proposalNotFound();
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a fee command runs inside a correlation scope"))
                .correlationId();
    }

    private static ApiException feeCurrencyMismatch() {
        return new ApiException(
                MerchantErrorCode.FEE_CURRENCY_MISMATCH,
                "A fee schedule was used for a currency it does not price",
                "the fee schedule's currency does not match.");
    }

    private static ApiException scheduleNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A fee schedule read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static ApiException merchantNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A merchant read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static ApiException proposalNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A fee proposal read found nothing at the identifier",
                "the requested resource does not exist.");
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
