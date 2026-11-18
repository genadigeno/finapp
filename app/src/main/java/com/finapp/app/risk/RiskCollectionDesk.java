package com.finapp.app.risk;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AssessmentSearch;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCollections;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicyStore;
import com.finapp.risk.RiskPolicyVersionStatus;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.ScreeningList;
import com.finapp.risk.ScreeningListStore;
import com.finapp.risk.ScreeningListVersion;
import com.finapp.risk.ScreeningListVersionId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The risk desks' §7 collections (`P17-TSK-020`, gaps G-91 and G-92; finapp-admin-panel B4.2, B4.3, ADR-C133): the analyst's
 * assessment search, and the risk officer's lists of policy versions and screening list versions with one list version's
 * metadata. Each a page of at most {@code limit} rows (50 unless named, 200 at most), newest first, the opaque {@code cursor}
 * of the page before; a parameter outside its vocabulary, an unreadable cursor or a window out of bounds {@code 422
 * api.ValidationFailed}.
 *
 * <p><strong>The assessment search</strong> reads a required window of at most 31 days ({@code [from, to)}, the audit trail's
 * bound, ADR-C134) and is {@link AssessmentSearch}'s: the reader's own party's assessments withheld by the read, a search
 * naming that party - or by a reader the platform cannot place - {@code 403 risk.SelfDealingRefused} recorded {@code FAILED},
 * every page recorded {@code risk.AssessmentsSearched} in the read's own transaction. Its items are heads: an assessment's
 * inputs, hits and reasons stay the one-assessment read's, audited on their own.
 *
 * <p><strong>The two lists</strong> serve metadata and never a person: who proposed, decided, recorded or activated stays
 * withheld, as the version reads withhold it; and finapp holds no screening list's entries - a list's content is the
 * provider's, kyc's - so none is served (ADR-C132 decision 3 leaves whether staff may read entries to the owner). They are not
 * recorded, as the policy reads they sit beside are not.
 */
public final class RiskCollectionDesk {

    static final String ASSESSMENTS = "risk.assessments";
    static final String POLICY_VERSIONS = "risk.policy-versions";
    static final String SCREENING_LISTS = "risk.screening-lists";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;
    /** The widest window one search covers - the audit trail's bound (ADR-C134): the window, not the table, bounds a read. */
    static final Duration MAXIMUM_WINDOW = Duration.ofDays(31);

    private final AssessmentSearch search;
    private final RiskCollections collections;
    private final ScreeningListStore lists;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskCollectionDesk(AssessmentSearch search, RiskCollections collections, ScreeningListStore lists,
            RiskTransactions transactions, RiskFlowScope flow) {
        this.search = Objects.requireNonNull(search, "search");
        this.collections = Objects.requireNonNull(collections, "collections");
        this.lists = Objects.requireNonNull(lists, "lists");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    // ------------------------------------------------------------------ views

    /** A page of the assessment search: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskAssessmentPage(List<RiskAssessmentHeadView> items, String nextCursor) {}

    /**
     * One sealed assessment's head: when, which operation, the decision and score, the policy version it was judged under
     * (its id and number; both absent for an evaluation that pinned none), the party. Instants ISO-8601 UTC.
     */
    public record RiskAssessmentHeadView(String assessmentId, String assessedAt, String operation, String decision, int score,
            String policyVersionId, Integer policyVersion, String partyId) {}

    /** A page of policy versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskPolicyVersionPage(List<RiskPolicyVersionHeadView> items, String nextCursor) {}

    /**
     * One policy version's head - its content is {@code GET /v1/operator/risk/policies/{versionId}}'s: the operation, the number,
     * the engine, the status, and the stamps of its proposal, its decision and its effective span - never who.
     */
    public record RiskPolicyVersionHeadView(String versionId, String operation, int version, String engine, String status,
            String proposedAt, String decidedAt, String effectiveFrom, String effectiveTo) {}

    /** A page of screening list versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RiskScreeningListPage(List<RiskScreeningListVersionView> items, String nextCursor) {}

    /**
     * One screening list version's metadata: the list, the provider's label, its ordinal within the list, its status and its
     * stamps - never who recorded or activated it, and never an entry.
     */
    public record RiskScreeningListVersionView(String id, String list, String version, int ordinal, String status,
            String recordedAt, String activatedAt, String retiredAt) {}

    // ------------------------------------------------------------------ the assessment search

    /** A page of the sealed assessments of {@code [from, to)}, newest first, read and recorded. */
    public RiskAssessmentPage assessments(String from, String to, String partyId, String operation, String decision,
            String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Instant start = instant("from", from);
        Instant end = instant("to", to);
        if (!start.isBefore(end)) {
            throw OperatorCollection.invalid("from is before to: the window is [from, to).");
        }
        if (Duration.between(start, end).compareTo(MAXIMUM_WINDOW) > 0) {
            throw OperatorCollection.invalid("the window is at most " + MAXIMUM_WINDOW.toDays() + " days; narrow it.");
        }
        RiskCollections.AssessmentFilter filter = new RiskCollections.AssessmentFilter(start, end,
                Optional.ofNullable(partyId).map(RiskCollectionDesk::party),
                named("operation", operation, RiskOperation.class), named("decision", decision, RiskDecision.class));
        Optional<RiskCollections.Cursor> after = after(ASSESSMENTS, cursor);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<RiskCollections.AssessmentHead> rows;
        try {
            rows = flow.door(RiskSpans.QUEUE, () -> transactions.inTransaction(uow -> {
                List<RiskCollections.AssessmentHead> read = search.search(uow, filter, after, size + 1, actor);
                // On the read's own transaction: a page nobody recorded was never served.
                search.record(uow, filter, after.isPresent(), Math.min(size, read.size()), actor, correlation);
                return read;
            }));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                search.recordRefusal(uow, filter, refused, actor, correlation);
                return null;
            });
            throw selfDealing();
        }
        return page(rows, size, ASSESSMENTS, RiskCollections.AssessmentHead::assessedAt, head -> head.id().value(),
                RiskCollectionDesk::assessmentHead, RiskAssessmentPage::new);
    }

    // ------------------------------------------------------------------ the risk officer's lists

    /** A page of policy versions, newest first by proposal - of one operation and one status when named. */
    public RiskPolicyVersionPage policyVersions(String operation, String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<RiskOperation> named = named("operation", operation, RiskOperation.class);
        Optional<RiskPolicyVersionStatus> state = named("status", status, RiskPolicyVersionStatus.class);
        Optional<RiskCollections.Cursor> after = after(POLICY_VERSIONS, cursor);
        List<RiskPolicyStore.VersionRow> rows = flow.door(RiskSpans.POLICY_READ, () -> transactions.inTransaction(
                uow -> collections.policyVersions(uow, named, state, after, size + 1)));
        return page(rows, size, POLICY_VERSIONS, RiskPolicyStore.VersionRow::proposedAt, row -> row.id().value(),
                RiskCollectionDesk::policyHead, RiskPolicyVersionPage::new);
    }

    /** A page of screening list versions, newest first by recording - of one list when named. */
    public RiskScreeningListPage screeningLists(String list, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<ScreeningList> named = named("list", list, ScreeningList.class);
        Optional<RiskCollections.Cursor> after = after(SCREENING_LISTS, cursor);
        List<ScreeningListVersion> rows = flow.door(RiskSpans.LIST, () -> transactions.inTransaction(
                uow -> collections.screeningListVersions(uow, named, after, size + 1)));
        return page(rows, size, SCREENING_LISTS, ScreeningListVersion::recordedAt, row -> row.id().value(),
                RiskCollectionDesk::listView, RiskScreeningListPage::new);
    }

    /** One screening list version's metadata; an unknown or malformed id the uniform {@code 404 risk.NotFound}. */
    public RiskScreeningListVersionView screeningList(String rawId) {
        ScreeningListVersionId id;
        try {
            id = ScreeningListVersionId.of(UUID.fromString(Objects.requireNonNull(rawId, "rawId")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        return flow.door(RiskSpans.LIST, () -> transactions.inTransaction(uow -> lists.find(uow, id)))
                .map(RiskCollectionDesk::listView).orElseThrow(RiskCollectionDesk::notFound);
    }

    // ------------------------------------------------------------------ the views

    private static RiskAssessmentHeadView assessmentHead(RiskCollections.AssessmentHead head) {
        return new RiskAssessmentHeadView(
                head.id().value().toString(),
                head.assessedAt().toString(),
                head.operation().name(),
                head.decision().name(),
                head.score(),
                head.policyVersionId().map(version -> version.value().toString()).orElse(null),
                head.policyVersion().orElse(null),
                head.partyId().toString());
    }

    private static RiskPolicyVersionHeadView policyHead(RiskPolicyStore.VersionRow row) {
        return new RiskPolicyVersionHeadView(
                row.id().value().toString(),
                row.operation().name(),
                row.version(),
                row.engine(),
                row.status().name(),
                row.proposedAt().toString(),
                row.decidedAt().map(Instant::toString).orElse(null),
                row.effectiveFrom().map(Instant::toString).orElse(null),
                row.effectiveTo().map(Instant::toString).orElse(null));
    }

    private static RiskScreeningListVersionView listView(ScreeningListVersion version) {
        return new RiskScreeningListVersionView(
                version.id().value().toString(),
                version.list().name(),
                version.version(),
                version.ordinal(),
                version.status().name(),
                version.recordedAt().toString(),
                version.activatedAt().map(Instant::toString).orElse(null),
                version.retiredAt().map(Instant::toString).orElse(null));
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

    private static Optional<RiskCollections.Cursor> after(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new RiskCollections.Cursor(Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS),
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

    private static UUID party(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException malformed) {
            throw OperatorCollection.invalid("partyId is a party's identifier, a UUID.");
        }
    }

    private static Instant instant(String name, String value) {
        if (value == null) {
            throw OperatorCollection.invalid(name + " is required: an assessment search names its window.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException unreadable) {
            throw OperatorCollection.invalid(name + " is an instant, e.g. 2026-11-18T00:00:00Z.");
        }
    }

    private static ApiException selfDealing() {
        return new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk read was refused",
                "a holder never searches the assessments of their own party, nor searches when the platform cannot place"
                        + " them; nothing was served.");
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk read runs inside a correlation scope"))
                .correlationId();
    }
}
