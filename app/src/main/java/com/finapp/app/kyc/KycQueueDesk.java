package com.finapp.app.kyc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycInquiries;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reviewers' queues (`P17-TSK-029`, gaps G-60, G-64, G-65; finapp-admin-panel B6.4): the open KYC cases oldest first, a
 * customer's latest case, and the counterparty screenings awaiting a person. Each list a §7 collection from birth - at most
 * {@code limit} rows (50 unless named, 200 at most), the opaque {@code cursor} of the page before, {@code nextCursor} only while
 * a row remains - and a parameter outside its vocabulary or an unreadable cursor {@code 422 api.ValidationFailed}, nothing read
 * and nothing recorded.
 *
 * <p><strong>Every answer recorded in the transaction that read it</strong> ({@link RecordedReads}): a page as
 * {@code kyc.ReviewQueueRead} or {@code kyc.ScreeningBacklogRead} with its filter and count, once per page, never per row; the
 * latest case as {@code kyc.CaseRead}, as the case's own read is. A miss serves nothing and records nothing.
 *
 * <p><strong>What leaves</strong>: identifiers, kinds, statuses and instants. A case's file - checks, tasks, owners - is
 * {@code GET /v1/kyc/cases/{id}}'s. A screening row is its identifier, its status and when it was raised: never its subject, the
 * payee's verdict, the provider's answer or why it was raised. Nothing here reaches a customer surface (tipping-off).
 */
public final class KycQueueDesk {

    static final String QUEUE = "kyc.review-queue";
    static final String BACKLOG = "kyc.screening-backlog";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /**
     * The statuses a queue holds: every open one, a case awaiting its customer included (`P17-TSK-126`). A decided case is no
     * queue's.
     */
    static final Set<KycCaseStatus> OPEN = Set.copyOf(EnumSet.of(
            KycCaseStatus.OPEN, KycCaseStatus.CHECKS_IN_PROGRESS, KycCaseStatus.IN_REVIEW, KycCaseStatus.AWAITING_CUSTOMER,
            KycCaseStatus.READY_FOR_DECISION));

    /** The one status the backlog serves. */
    static final String AWAITING = "IN_REVIEW";

    private final KycInquiries inquiries;
    private final KycCaseStore<Connection> cases;
    private final RecordedReads recordedReads;
    private final KycUnitOfWork units;

    public KycQueueDesk(
            KycInquiries kycInquiries,
            KycCaseStore<Connection> kycCaseStore,
            RecordedReads recordedReads,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.inquiries = Objects.requireNonNull(kycInquiries, "kycInquiries must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.recordedReads = Objects.requireNonNull(recordedReads, "recordedReads must not be null");
        this.units = new KycUnitOfWork(
                Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    // ------------------------------------------------------------------ views

    /** A page of the review queue: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReviewQueuePage(List<KycCaseHead> items, String nextCursor) {}

    /** A case's head: its identifiers, kind, status, regime and instants. Its file is {@code GET /v1/kyc/cases/{id}}'s. */
    public record KycCaseHead(
            String caseId,
            String customerId,
            String kind,
            String status,
            String policyVersion,
            Instant openedAt,
            Instant statusChangedAt) {

        static KycCaseHead of(KycCase kycCase) {
            return new KycCaseHead(kycCase.id().value().toString(), kycCase.customerId().toString(), kycCase.kind().name(),
                    kycCase.status().name(), kycCase.policyVersion().value(), kycCase.openedAt(), kycCase.statusChangedAt());
        }
    }

    /** A page of the counterparty screenings awaiting a person: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScreeningBacklogPage(List<ScreeningAwaitingReview> items, String nextCursor) {}

    /** One screening awaiting a person: its identifier, its status and when it was raised for review. Nothing else. */
    public record ScreeningAwaitingReview(String screeningId, String status, Instant raisedAt) {

        static ScreeningAwaitingReview of(KycInquiries.AwaitingReview awaiting) {
            return new ScreeningAwaitingReview(
                    awaiting.id().value().toString(), awaiting.status().name(), awaiting.raisedAt());
        }
    }

    // ------------------------------------------------------------------ the review queue (KYC_REVIEW)

    /** A page of the open cases, oldest opening first; {@code status} one open status, or every open one when absent. */
    public ReviewQueuePage queue(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Set<KycCaseStatus> statuses = statuses(status);
        Optional<KycInquiries.QueuePosition> after = OperatorCollection.key(QUEUE, cursor, 2).map(key -> {
            try {
                return new KycInquiries.QueuePosition(instant(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        String filter = status == null ? "open" : status;
        List<KycCase> rows = units.inTransaction(unitOfWork -> {
            List<KycCase> page = inquiries.openCases(unitOfWork, statuses, after, size + 1);
            recordedReads.record(unitOfWork, KycAuditAction.REVIEW_QUEUE_READ, "kyc_review_queue", filter,
                    "status=" + filter + ", continued=" + after.isPresent() + ", served=" + Math.min(size, page.size()));
            return page;
        });
        List<KycCase> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            KycCase tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(QUEUE, micros(tail.openedAt()), tail.id().value().toString());
        }
        return new ReviewQueuePage(served.stream().map(KycCaseHead::of).toList(), next);
    }

    // ------------------------------------------------------------------ a customer's latest case (KYC_REVIEW)

    /**
     * A customer's latest case, open or decided - kyc's own ranking ({@link KycCaseStore#findLatestFor}). A malformed
     * identifier, an unknown customer and one who never had a case are one {@code 404}, recording nothing: nothing was served.
     */
    public KycCaseHead latestOf(String rawCustomerId) {
        UUID customerId;
        try {
            customerId = UUID.fromString(rawCustomerId);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        Optional<KycCaseHead> latest = units.inTransaction(unitOfWork ->
                cases.findLatestFor(unitOfWork, customerId).map(found -> {
                    recordedReads.record(unitOfWork, KycAuditAction.KYC_CASE_READ, "KycCase", found.id().value().toString(),
                            "customer=" + customerId + ", latest-of-customer");
                    return KycCaseHead.of(found);
                }));
        return latest.orElseThrow(KycQueueDesk::notFound);
    }

    // ------------------------------------------------------------------ the screening backlog (COUNTERPARTY_SCREENING_REVIEW)

    /** A page of the screenings {@code IN_REVIEW}, oldest raised first; {@code status} is {@code IN_REVIEW} or absent. */
    public ScreeningBacklogPage backlog(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        if (status != null && !AWAITING.equals(status)) {
            throw OperatorCollection.invalid("status is IN_REVIEW: the screenings awaiting a person.");
        }
        Optional<KycInquiries.BacklogPosition> after = OperatorCollection.key(BACKLOG, cursor, 2).map(key -> {
            try {
                return new KycInquiries.BacklogPosition(instant(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        List<KycInquiries.AwaitingReview> rows = units.inTransaction(unitOfWork -> {
            List<KycInquiries.AwaitingReview> page = inquiries.screeningsAwaitingReview(unitOfWork, after, size + 1);
            recordedReads.record(unitOfWork, KycAuditAction.SCREENING_BACKLOG_READ, "counterparty_screening_backlog", AWAITING,
                    "continued=" + after.isPresent() + ", served=" + Math.min(size, page.size()));
            return page;
        });
        List<KycInquiries.AwaitingReview> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            KycInquiries.AwaitingReview tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(BACKLOG, micros(tail.raisedAt()), tail.id().value().toString());
        }
        return new ScreeningBacklogPage(served.stream().map(ScreeningAwaitingReview::of).toList(), next);
    }

    // ------------------------------------------------------------------ plumbing

    private static Set<KycCaseStatus> statuses(String status) {
        if (status == null) {
            return OPEN;
        }
        KycCaseStatus named = Arrays.stream(KycCaseStatus.values())
                .filter(candidate -> candidate.name().equals(status))
                .findFirst()
                .orElseThrow(() -> OperatorCollection.invalid("status is one of " + open() + "."));
        if (!OPEN.contains(named)) {
            throw OperatorCollection.invalid("status is an open status, one of " + open() + ": a decided case is no queue's.");
        }
        return Set.of(named);
    }

    private static List<String> open() {
        return OPEN.stream().map(Enum::name).sorted().toList();
    }

    /** An instant as a cursor carries it: whole microseconds since the epoch - the column's own precision, so none is lost. */
    private static String micros(Instant instant) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
    }

    private static Instant instant(String micros) {
        return Instant.EPOCH.plus(Long.parseLong(micros), ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No KYC case matched the given customer");
    }
}
