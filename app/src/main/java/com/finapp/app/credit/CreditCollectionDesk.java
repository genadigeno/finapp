package com.finapp.app.credit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.PendingDecisions;
import com.finapp.app.api.RecordedReads;
import com.finapp.credit.CreditAuditAction;
import com.finapp.credit.CreditCollections;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.CreditPolicyStatus;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditPolicyVersionId;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.DecisionRequestStatus;
import com.finapp.credit.Scorecard;
import com.finapp.credit.ScorecardModelVersionId;
import com.finapp.credit.ScorecardStatus;
import com.finapp.credit.ScorecardStore;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.security.SecurityContext;
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
 * Credit's operator collections and the reads an approver lacked (`P17-TSK-035`, gaps G-80 and G-81; finapp-admin-panel B8.1,
 * ADR-C133): the decision requests, the credit policy versions with one version by its identifier, and the scorecard model
 * versions with one version and its points table - <strong>until now a scorecard had no read at all</strong>: it could be
 * proposed, approved and rejected, and nobody could see the version they approved. Each list a §7 collection
 * ({@link OperatorCollection}: {@code limit} 50 unless named, 200 at most, refused rather than reduced; the cursor its own
 * collection's), behind {@code CREDIT_INVESTIGATE}.
 *
 * <p><strong>Every serving is recorded</strong> in the read's own transaction ({@link RecordedReads}; ADR-C132 decision 5):
 * the decision requests as {@code credit.DecisionRequestsRead}, the policy versions as {@code credit.PolicyRead} - the record
 * the policy in force already writes - and the scorecards as {@code credit.ScorecardRead}; a list once per page, naming its
 * filter and the count served, never per row. A version names its proposer and marks the caller's own proposals; it never
 * names who decided it (the {@code -109}/{@code -111} rule). A decision request serves its party, product, status, the amount
 * asked and the decision once recorded - never a declared income or expenditure, a score or an attribute: those stay the
 * explanation's, audited on their own.
 */
@RequiredArgsConstructor
public final class CreditCollectionDesk {

    static final String DECISION_REQUESTS = "credit.decision-requests";
    static final String POLICY_VERSIONS = "credit.policy-versions";
    static final String SCORECARDS = "credit.scorecards";
    static final String PENDING_POLICY_VERSIONS = "pending.credit.policy-versions";
    static final String PENDING_SCORECARD_VERSIONS = "pending.credit.scorecard-versions";
    static final String PENDING_SECOND_APPROVALS = "pending.credit.review-case-decisions";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    @NonNull private final CreditCollections collections;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final RecordedReads reads;
    @NonNull private final TransactionRunner transactions;

    // ------------------------------------------------------------------ the views

    /** A page of decision requests: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreditDecisionRequestPage(List<CreditDecisionRequestHeadView> items, String nextCursor) {}

    /**
     * One decision request: its party and product, the door it came through ({@code CUSTOMER_DOOR} or
     * {@code BNPL_APPLICATION}), its status and - when the platform closed it - why, the amount asked (an exact decimal string
     * in {@code currency}) and the term, its stamps, the versions it pinned, and the decision and its outcome once recorded.
     */
    public record CreditDecisionRequestHeadView(
            String requestId,
            String partyId,
            String product,
            String origin,
            String status,
            String closureReason,
            String currency,
            String requestedAmount,
            Integer termMonths,
            String submittedAt,
            String expiresAt,
            String pinnedPolicyVersionId,
            String pinnedModelVersionId,
            String decisionId,
            String outcome) {}

    /** A page of credit policy versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreditPolicyVersionPage(List<CreditPolicyVersionHeadView> items, String nextCursor) {}

    /**
     * A credit policy version's head: its product, number and status, its proposer - and whether that is the caller - the
     * proposal's stamp and reason, the decision's stamp and reason once decided, never the decider, and its period in force.
     */
    public record CreditPolicyVersionHeadView(
            String versionId,
            String product,
            int version,
            String status,
            String proposedBy,
            boolean proposedByCaller,
            String proposedAt,
            String reason,
            String decidedAt,
            String decisionReason,
            String effectiveFrom,
            String effectiveTo) {}

    /** One credit policy version: its head, and the whole policy as the policy in force is served (CONFIDENTIAL). */
    public record CreditPolicyVersionView(CreditPolicyVersionHeadView version, CreditPolicyDesk.CreditPolicyView policy) {}

    /** A page of scorecard model versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScorecardVersionPage(List<ScorecardVersionHeadView> items, String nextCursor) {}

    /** A scorecard model version's head - the {@link CreditPolicyVersionHeadView} shape, of a family instead of a product. */
    public record ScorecardVersionHeadView(
            String versionId,
            String family,
            int version,
            String status,
            String proposedBy,
            boolean proposedByCaller,
            String proposedAt,
            String reason,
            String decidedAt,
            String decisionReason,
            String effectiveFrom,
            String effectiveTo) {}

    /** One scorecard model version: its head, the base points and every attribute's bands in their stored order. */
    public record ScorecardVersionView(ScorecardVersionHeadView version, int basePoints, List<ScorecardAttributeView> attributes) {}

    /** One attribute's bands: the points when the attribute is absent, and each band in order. */
    public record ScorecardAttributeView(String code, int absentPoints, List<ScorecardBandView> bands) {}

    /** One band: a range ({@code lower} inclusive, {@code upper} exclusive, either open when null) or a code set, and its points. */
    public record ScorecardBandView(String kind, Long lower, Long upper, List<String> codes, int points) {}

    // ------------------------------------------------------------------ the decision requests

    /** A page of decision requests, submitted newest first - of one status and one product when named - read and recorded. */
    public CreditDecisionRequestPage decisionRequests(String status, String product, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<DecisionRequestStatus> state = named("status", status, DecisionRequestStatus.class);
        Optional<CreditProduct> named = named("product", product, CreditProduct.class);
        Optional<CreditCollections.Cursor> after = after(DECISION_REQUESTS, cursor);
        List<CreditCollections.DecisionRequestHead> rows = transactions.inTransaction(uow -> {
            List<CreditCollections.DecisionRequestHead> read = collections.decisionRequests(uow, state, named, after, size + 1);
            // On the read's own transaction: a page nobody recorded was never served.
            reads.record(uow, CreditAuditAction.DECISION_REQUESTS_READ, "credit_decision_requests", "all",
                    "status=" + state.map(Enum::name).orElse("any") + ", product=" + named.map(Enum::name).orElse("any")
                            + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, DECISION_REQUESTS, CreditCollections.DecisionRequestHead::submittedAt,
                row -> row.id().value(), CreditCollectionDesk::requestView, CreditDecisionRequestPage::new);
    }

    // ------------------------------------------------------------------ the policy versions

    /** A page of credit policy versions, proposed newest first - of one product and one status when named - recorded. */
    public CreditPolicyVersionPage policyVersions(String product, String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<CreditProduct> named = named("product", product, CreditProduct.class);
        Optional<CreditPolicyStatus> state = named("status", status, CreditPolicyStatus.class);
        Optional<CreditCollections.Cursor> after = after(POLICY_VERSIONS, cursor);
        String caller = SecurityContext.require().id();
        List<CreditCollections.VersionHead> rows = transactions.inTransaction(uow -> {
            List<CreditCollections.VersionHead> read = collections.policyVersions(uow, named, state, after, size + 1);
            reads.record(uow, CreditAuditAction.POLICY_READ, "credit_policy_versions", "all",
                    "product=" + named.map(Enum::name).orElse("any") + ", status=" + state.map(Enum::name).orElse("any")
                            + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, POLICY_VERSIONS, CreditCollections.VersionHead::proposedAt, CreditCollections.VersionHead::id,
                head -> policyHead(head, caller), CreditPolicyVersionPage::new);
    }

    /** One credit policy version with its whole policy, recorded; an unknown or malformed id the uniform {@code 404}. */
    public CreditPolicyVersionView policyVersion(String rawId) {
        CreditPolicyVersionId id = identified(rawId, CreditPolicyVersionId::of);
        String caller = SecurityContext.require().id();
        return transactions.inTransaction(uow -> {
            CreditCollections.VersionHead head = collections.policyVersion(uow, id).orElseThrow(CreditCollectionDesk::notFound);
            CreditPolicyStore.PolicyVersion version = policies.policy(uow, id).orElseThrow(CreditCollectionDesk::notFound);
            reads.record(uow, CreditAuditAction.POLICY_READ, "credit_policy_version", id.value().toString(),
                    "product=" + head.subject() + ", version=" + head.version() + ", status=" + head.status());
            return new CreditPolicyVersionView(policyHead(head, caller), CreditPolicyDesk.view(version));
        });
    }

    // ------------------------------------------------------------------ the scorecards

    /** A page of scorecard model versions, proposed newest first - of one status when named - recorded. */
    public ScorecardVersionPage scorecards(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<ScorecardStatus> state = named("status", status, ScorecardStatus.class);
        Optional<CreditCollections.Cursor> after = after(SCORECARDS, cursor);
        String caller = SecurityContext.require().id();
        List<CreditCollections.VersionHead> rows = transactions.inTransaction(uow -> {
            List<CreditCollections.VersionHead> read = collections.scorecardVersions(uow, state, after, size + 1);
            reads.record(uow, CreditAuditAction.SCORECARD_READ, "scorecard_model_versions", "all",
                    "status=" + state.map(Enum::name).orElse("any") + ", continued=" + after.isPresent() + ", served="
                            + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, SCORECARDS, CreditCollections.VersionHead::proposedAt, CreditCollections.VersionHead::id,
                head -> scorecardHead(head, caller), ScorecardVersionPage::new);
    }

    /** One scorecard model version with its points table, recorded; an unknown or malformed id the uniform {@code 404}. */
    public ScorecardVersionView scorecard(String rawId) {
        ScorecardModelVersionId id = identified(rawId, ScorecardModelVersionId::of);
        String caller = SecurityContext.require().id();
        return transactions.inTransaction(uow -> {
            CreditCollections.VersionHead head = collections.scorecardVersion(uow, id).orElseThrow(CreditCollectionDesk::notFound);
            ScorecardStore.ModelVersion model = scorecards.model(uow, id).orElseThrow(CreditCollectionDesk::notFound);
            reads.record(uow, CreditAuditAction.SCORECARD_READ, "scorecard_model_version", id.value().toString(),
                    "family=" + head.subject() + ", version=" + head.version() + ", status=" + head.status());
            Scorecard scorecard = model.scorecard();
            return new ScorecardVersionView(scorecardHead(head, caller), scorecard.basePoints(),
                    scorecard.attributes().stream().map(CreditCollectionDesk::attribute).toList());
        });
    }

    // ------------------------------------------------------------------ the approver's pending-decision queues (`P17-TSK-040`)

    /**
     * A page of the credit policy versions awaiting a decision, proposed oldest first, in the one pending-decision shape
     * ({@link PendingDecisions}); recorded {@code credit.PolicyRead}, as the version list is.
     */
    public PendingDecisions.PendingDecisionPage pendingPolicyVersions(String status, String cursor, String limit) {
        return pending(status, cursor, limit, PENDING_POLICY_VERSIONS, "credit.PolicyVersion", CreditAuditAction.POLICY_READ,
                "credit_policy_versions", collections::pendingPolicyVersions);
    }

    /**
     * A page of the scorecard model versions awaiting a decision, proposed oldest first, in the one pending-decision shape;
     * recorded {@code credit.ScorecardRead}, as the scorecard list is.
     */
    public PendingDecisions.PendingDecisionPage pendingScorecardVersions(String status, String cursor, String limit) {
        return pending(status, cursor, limit, PENDING_SCORECARD_VERSIONS, "credit.ScorecardVersion",
                CreditAuditAction.SCORECARD_READ, "scorecard_model_versions", collections::pendingScorecardVersions);
    }

    /**
     * A page of the review cases whose first decision awaits a different underwriter's second approval, first decided oldest
     * first, in the one pending-decision shape: the case its id (the second-approval door's), the first underwriter its
     * proposer, their reason its summary, the decision request its subject; {@code status} {@code AWAITING_SECOND} or absent.
     * Recorded {@code credit.ReviewCasesRead}, as the review queue is.
     */
    public PendingDecisions.PendingDecisionPage pendingSecondApprovals(String status, String cursor, String limit) {
        PendingDecisions.pendingOnly(status, "AWAITING_SECOND");
        int size = PendingDecisions.limit(limit);
        Optional<CreditCollections.Cursor> after = PendingDecisions.position(PENDING_SECOND_APPROVALS, cursor)
                .map(position -> new CreditCollections.Cursor(position.proposedAt(), position.id()));
        List<CreditCollections.PendingSecondApproval> rows = transactions.inTransaction(uow -> {
            List<CreditCollections.PendingSecondApproval> read = collections.pendingSecondApprovals(uow, after, size + 1);
            reads.record(uow, CreditAuditAction.REVIEW_CASES_READ, "review_queue", "AWAITING_SECOND", "status=AWAITING_SECOND,"
                    + " continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return PendingDecisions.page(PENDING_SECOND_APPROVALS, rows.stream()
                .map(row -> PendingDecisions.of(row.caseId(), "credit.ReviewCaseDecision", row.firstDecidedBy(),
                        row.firstDecidedAt(), row.firstReason(), row.decisionRequest()))
                .toList(), size);
    }

    @FunctionalInterface
    private interface PendingRead {
        List<CreditCollections.VersionHead> read(java.sql.Connection unitOfWork, Optional<CreditCollections.Cursor> after,
                int limit);
    }

    /**
     * One pending-decision page: its subject the product or family the version would govern, its proposer and reason the
     * proposal's.
     */
    private PendingDecisions.PendingDecisionPage pending(String status, String cursor, String limit, String collection,
            String kind, CreditAuditAction action, String targetType, PendingRead pending) {
        PendingDecisions.pendingOnly(status, "PROPOSED");
        int size = PendingDecisions.limit(limit);
        Optional<CreditCollections.Cursor> after = PendingDecisions.position(collection, cursor)
                .map(position -> new CreditCollections.Cursor(position.proposedAt(), position.id()));
        List<CreditCollections.VersionHead> rows = transactions.inTransaction(uow -> {
            List<CreditCollections.VersionHead> read = pending.read(uow, after, size + 1);
            reads.record(uow, action, targetType, "pending", "status=PROPOSED, continued=" + after.isPresent() + ", served="
                    + Math.min(size, read.size()));
            return read;
        });
        return PendingDecisions.page(collection, rows.stream()
                .map(head -> PendingDecisions.of(head.id(), kind, head.proposedBy(), head.proposedAt(), head.reason(),
                        head.subject()))
                .toList(), size);
    }

    // ------------------------------------------------------------------ the views

    private static CreditDecisionRequestHeadView requestView(CreditCollections.DecisionRequestHead head) {
        return new CreditDecisionRequestHeadView(
                head.id().value().toString(),
                head.party().toString(),
                head.product().name(),
                head.origin().name(),
                head.status().name(),
                head.closureReason().map(Enum::name).orElse(null),
                head.requested().currency().code(),
                head.requested().toBigDecimal().toPlainString(),
                head.termMonths().orElse(null),
                head.submittedAt().toString(),
                head.expiresAt().toString(),
                head.pinnedPolicy().map(id -> id.value().toString()).orElse(null),
                head.pinnedModel().map(id -> id.value().toString()).orElse(null),
                head.decision().map(id -> id.value().toString()).orElse(null),
                head.outcome().map(Enum::name).orElse(null));
    }

    private static CreditPolicyVersionHeadView policyHead(CreditCollections.VersionHead head, String caller) {
        return new CreditPolicyVersionHeadView(head.id().toString(), head.subject(), head.version(), head.status(),
                head.proposedBy(), head.proposedBy().equals(caller), head.proposedAt().toString(), head.reason(),
                stamp(head.decidedAt()), head.decisionReason().orElse(null), stamp(head.effectiveFrom()),
                stamp(head.effectiveTo()));
    }

    private static ScorecardVersionHeadView scorecardHead(CreditCollections.VersionHead head, String caller) {
        return new ScorecardVersionHeadView(head.id().toString(), head.subject(), head.version(), head.status(),
                head.proposedBy(), head.proposedBy().equals(caller), head.proposedAt().toString(), head.reason(),
                stamp(head.decidedAt()), head.decisionReason().orElse(null), stamp(head.effectiveFrom()),
                stamp(head.effectiveTo()));
    }

    private static ScorecardAttributeView attribute(Scorecard.AttributeBands attribute) {
        return new ScorecardAttributeView(attribute.code().name(), attribute.absentPoints(),
                attribute.bands().stream().map(CreditCollectionDesk::band).toList());
    }

    private static ScorecardBandView band(Scorecard.Band band) {
        if (band instanceof Scorecard.Codes codes) {
            return new ScorecardBandView("CODES", null, null, codes.sorted(), codes.points());
        }
        Scorecard.Range range = (Scorecard.Range) band;
        return new ScorecardBandView("RANGE", range.lower(), range.upper(), null, range.points());
    }

    private static String stamp(Optional<Instant> instant) {
        return instant.map(Instant::toString).orElse(null);
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

    private static Optional<CreditCollections.Cursor> after(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new CreditCollections.Cursor(Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS),
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

    /**
     * A version id is a UUIDv7 (ADR-0013): any other identifier - malformed, or a UUID of another version the id type
     * refuses - names no version, the uniform 404, never a 500.
     */
    private static <I> I identified(String raw, Function<UUID, I> type) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return type.apply(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No credit record answers the request", "no such record.");
    }
}
