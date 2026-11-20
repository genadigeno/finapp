package com.finapp.app.lending;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.lending.CreditLineStatements;
import com.finapp.lending.DelinquencyBucket;
import com.finapp.lending.LendingAuditAction;
import com.finapp.lending.LendingOperatorLists;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanActingParty;
import com.finapp.lending.LoanApplicationStatus;
import com.finapp.lending.LoanExplanations;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanOperatorReads;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanSchedules;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanTermsVersionStatus;
import com.finapp.lending.RepaymentReversals;
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
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Lending's operator collections and the reads an investigator and an approver lacked (`P17-TSK-035`, gaps G-82, G-84 and
 * G-85; finapp-admin-panel B8.2, B8.4, B8.5, ADR-C133): the loan applications and the loans under {@code LENDING_INVESTIGATE}
 * - the loans by party, state and the delinquency bucket the servicing step last recorded - one account's schedule versions
 * and statements, the views its borrower reads, under the same permission; the waivers, amendments and repayment reversals
 * awaiting a decision under {@code LOAN_SERVICE_APPROVE}, in ADR-C133's pending-decision shape naming the proposer; and the
 * terms versions under {@code LENDING_INVESTIGATE}, so a proposed version can be found by its approver. Each list a §7
 * collection ({@link OperatorCollection}: {@code limit} 50 unless named, 200 at most, refused rather than reduced; the cursor
 * its own collection's).
 *
 * <p><strong>The reader's own party</strong> is withheld by every list of a party's records - the write-off queue's rule - and
 * an account of it is never read: the schedule and the statements are judged as the explanation is, a list naming the
 * reader's own party - or read by an operator the platform cannot place in a party - is {@code 403 lending.SelfDealingRefused},
 * recorded {@code FAILED} in a transaction of its own.
 *
 * <p><strong>Every serving of a party's records is recorded</strong> in the read's own transaction ({@link RecordedReads};
 * ADR-C132 decision 5): {@code lending.ApplicationsRead}, {@code lending.LoansRead} and {@code lending.ServicingRequestsRead}
 * once per page, naming the filter and the count served; the schedule and the statements as {@code lending.LoanRead}. The
 * terms versions are the product's configuration, not a party's: unrecorded, as the terms in force already are
 * ({@code LoanTermsDesk.activeAt}).
 */
@RequiredArgsConstructor
public final class LendingListDesk {

    static final String APPLICATIONS = "lending.loan-applications";
    static final String LOANS = "lending.loans";
    static final String SERVICING_REQUESTS = "lending.servicing-requests";
    static final String TERMS_VERSIONS = "lending.terms-versions";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** The buckets a loan search names: {@code CURRENT} and each past-due band. */
    static final List<String> BUCKETS = Stream.concat(Stream.of("CURRENT"),
            Arrays.stream(DelinquencyBucket.values()).map(Enum::name)).toList();

    @NonNull private final LendingOperatorLists collections;
    @NonNull private final LoanOperatorReads accounts;
    @NonNull private final LoanExplanations explanations;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final RecordedReads reads;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final LendingTransactions transactions;

    // ------------------------------------------------------------------ the views

    /** A page of loan applications: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoanApplicationPage(List<LoanApplicationHeadView> items, String nextCursor) {}

    /** One loan application: its party and product, status, the decision request and terms version it names, its stamps. */
    public record LoanApplicationHeadView(
            String applicationId,
            String partyId,
            String product,
            String status,
            String decisionRequestId,
            String termsVersionId,
            String submittedAt,
            String closedAt) {}

    /** A page of loans: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoanPage(List<LoanHeadView> items, String nextCursor) {}

    /**
     * One loan: its party, product, kind, state, currency and stamps, and - for an account in servicing ({@code ACTIVE},
     * {@code CLOSING}) - the delinquency {@code bucket} and the {@code defaulted} flag as the daily servicing step last recorded
     * them ({@code CURRENT} when no change was ever recorded); null for any other state. Never a balance: the account's own read
     * derives those.
     */
    public record LoanHeadView(
            String loanId,
            String partyId,
            String product,
            String kind,
            String status,
            String currency,
            String openedAt,
            String closedAt,
            String bucket,
            Boolean defaulted) {}

    /** A page of the servicing proposals awaiting a decision: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoanServicingRequestPage(List<LoanServicingRequestView> items, String nextCursor) {}

    /**
     * One proposal awaiting a decision, in ADR-C133's pending-decision shape: its {@code id}, {@code kind}
     * ({@code lending.WAIVER}, {@code lending.AMENDMENT}, {@code lending.REPAYMENT_REVERSAL}), the loan it corrects
     * ({@code subjectKind} {@code LOAN}, {@code subjectId}), its proposer - and whether that is the caller, who may not decide
     * it - its stamp and the proposer's reason; for a repayment reversal the repayment its doors are addressed by.
     */
    public record LoanServicingRequestView(
            String id,
            String kind,
            String subjectKind,
            String subjectId,
            String proposedBy,
            boolean proposedByCaller,
            String proposedAt,
            String reasonSummary,
            String repaymentId) {}

    /** A page of terms versions: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LoanTermsVersionPage(List<LoanTermsVersionHeadView> items, String nextCursor) {}

    /**
     * A terms version's head: its product, number and status, its proposer - and whether that is the caller - the proposal's
     * stamp and reason, the decision's stamp and reason once decided, never the decider, and its period in force.
     */
    public record LoanTermsVersionHeadView(
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

    // ------------------------------------------------------------------ the investigator's lists

    /** A page of loan applications, submitted newest first - of one status when named - the reader's party withheld. */
    public LoanApplicationPage applications(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<LoanApplicationStatus> state = named("status", status, LoanApplicationStatus.class);
        Optional<LendingOperatorLists.Cursor> after = after(APPLICATIONS, cursor);
        String filter = "status=" + state.map(Enum::name).orElse("any");
        List<LendingOperatorLists.ApplicationHead> rows = listed("loan_applications", "all", filter, Optional.empty(),
                (uow, withheld) -> {
                    List<LendingOperatorLists.ApplicationHead> read = collections.applications(uow, state, withheld, after, size + 1);
                    reads.record(uow, LendingAuditAction.APPLICATIONS_READ, "loan_applications", "all",
                            filter + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
                    return read;
                });
        return page(rows, size, APPLICATIONS, LendingOperatorLists.ApplicationHead::submittedAt, row -> row.id().value(),
                LendingListDesk::applicationView, LoanApplicationPage::new);
    }

    /**
     * A page of loans, opened newest first - of one party, one state and one recorded bucket when named - the reader's party
     * withheld, and naming it refused.
     */
    public LoanPage loans(String partyId, String state, String bucket, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<UUID> party = Optional.ofNullable(partyId).map(LendingListDesk::party);
        Optional<LoanStatus> status = named("state", state, LoanStatus.class);
        if (bucket != null && !BUCKETS.contains(bucket)) {
            throw OperatorCollection.invalid("bucket is one of " + BUCKETS + ".");
        }
        LendingOperatorLists.LoanFilter filter = new LendingOperatorLists.LoanFilter(party, status, Optional.ofNullable(bucket));
        Optional<LendingOperatorLists.Cursor> after = after(LOANS, cursor);
        String target = party.map(UUID::toString).orElse("all");
        String summary = "party=" + target + ", state=" + status.map(Enum::name).orElse("any") + ", bucket="
                + Optional.ofNullable(bucket).orElse("any");
        List<LendingOperatorLists.LoanHead> rows = listed("loans", target, summary, party, (uow, withheld) -> {
            List<LendingOperatorLists.LoanHead> read = collections.loans(uow, filter, withheld, after, size + 1);
            reads.record(uow, LendingAuditAction.LOANS_READ, "loans", target,
                    summary + ", continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
            return read;
        });
        return page(rows, size, LOANS, LendingOperatorLists.LoanHead::openedAt, row -> row.id().value(),
                LendingListDesk::loanView, LoanPage::new);
    }

    // ------------------------------------------------------------------ one account, as its borrower reads it

    /**
     * One account's schedule version {@code rawVersion} - its latest when absent - as its borrower reads it; recorded
     * {@code lending.LoanRead}. A loan not yet disbursed, a version that does not exist and an unknown or malformed id are the
     * same {@code 404}; the investigator's own party's loan {@code 403}, recorded {@code FAILED}.
     */
    public LoanAccountDesk.ScheduleView schedule(String rawId, String rawVersion) {
        LoanId loan = loanId(rawId);
        Optional<Integer> version = version(rawVersion);
        Actor actor = SecurityContext.require();
        return judged(actor, () -> transactions.inTransaction(uow -> {
            LoanSchedules.Read read = accounts.schedule(uow, loan, version, actor).orElseThrow(() -> notFound("loan schedule"));
            reads.record(uow, LendingAuditAction.LOAN_READ, "loan", loan.value().toString(),
                    "loan=" + loan.value() + ", view=schedule, version=" + read.schedule().version());
            return LoanAccountDesk.view(read);
        }));
    }

    /**
     * One credit line's statements, oldest first, as its borrower reads them; recorded {@code lending.LoanRead}. An instalment
     * loan and an unknown or malformed id are the same {@code 404}; a line not yet stated answers an empty list; the
     * investigator's own party's line {@code 403}, recorded {@code FAILED}.
     */
    public LoanAccountDesk.StatementListView statements(String rawId) {
        LoanId loan = loanId(rawId);
        Actor actor = SecurityContext.require();
        return judged(actor, () -> transactions.inTransaction(uow -> {
            List<CreditLineStatements.StatementRead> read = accounts.statements(uow, loan, actor)
                    .orElseThrow(() -> notFound("credit line"));
            reads.record(uow, LendingAuditAction.LOAN_READ, "loan", loan.value().toString(),
                    "loan=" + loan.value() + ", view=statements, served=" + read.size());
            return new LoanAccountDesk.StatementListView(loan.value().toString(),
                    read.stream().map(LoanAccountDesk::view).toList());
        }));
    }

    // ------------------------------------------------------------------ the approver's queue

    /**
     * A page of the waivers, amendments and repayment reversals awaiting a decision, proposed oldest first - {@code status}
     * {@code PROPOSED} or absent - the reader's own party's loans withheld; recorded {@code lending.ServicingRequestsRead}.
     */
    public LoanServicingRequestPage servicingRequests(String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        if (status != null && !"PROPOSED".equals(status)) {
            throw OperatorCollection.invalid("status is PROPOSED: the queue holds the proposals awaiting a decision.");
        }
        Optional<LendingOperatorLists.Cursor> after = after(SERVICING_REQUESTS, cursor);
        String caller = SecurityContext.require().id();
        List<LendingOperatorLists.ServicingRequest> rows = listed("loan_servicing_requests", "all", "status=PROPOSED",
                Optional.empty(), (uow, withheld) -> {
                    List<LendingOperatorLists.ServicingRequest> read = collections.pendingServicing(uow, withheld, after, size + 1);
                    reads.record(uow, LendingAuditAction.SERVICING_REQUESTS_READ, "loan_servicing_requests", "all",
                            "status=PROPOSED, continued=" + after.isPresent() + ", served=" + Math.min(size, read.size()));
                    return read;
                });
        return page(rows, size, SERVICING_REQUESTS, LendingOperatorLists.ServicingRequest::proposedAt,
                LendingOperatorLists.ServicingRequest::id, row -> servicingView(row, caller), LoanServicingRequestPage::new);
    }

    // ------------------------------------------------------------------ the terms versions

    /** A page of terms versions, proposed newest first - of one product and one status when named. Unrecorded: configuration. */
    public LoanTermsVersionPage termsVersions(String product, String status, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<LoanProduct> named = named("product", product, LoanProduct.class);
        Optional<LoanTermsVersionStatus> state = named("status", status, LoanTermsVersionStatus.class);
        Optional<LendingOperatorLists.Cursor> after = after(TERMS_VERSIONS, cursor);
        String caller = SecurityContext.require().id();
        List<LendingOperatorLists.TermsVersionHead> rows = transactions.inTransaction(
                uow -> collections.termsVersions(uow, named, state, after, size + 1));
        return page(rows, size, TERMS_VERSIONS, LendingOperatorLists.TermsVersionHead::proposedAt, row -> row.id().value(),
                row -> termsView(row, caller), LoanTermsVersionPage::new);
    }

    // ------------------------------------------------------------------ the views

    private static LoanApplicationHeadView applicationView(LendingOperatorLists.ApplicationHead head) {
        return new LoanApplicationHeadView(head.id().value().toString(), head.party().toString(), head.product().name(),
                head.status().name(), head.decisionRequest().toString(), head.termsVersion().value().toString(),
                head.submittedAt().toString(), stamp(head.closedAt()));
    }

    private static LoanHeadView loanView(LendingOperatorLists.LoanHead head) {
        return new LoanHeadView(head.id().value().toString(), head.party().toString(), head.product().name(),
                head.kind().name(), head.status().name(), head.currency().code(), head.openedAt().toString(),
                stamp(head.closedAt()), head.condition().map(LendingOperatorLists.RecordedCondition::bucket).orElse(null),
                head.condition().map(LendingOperatorLists.RecordedCondition::defaulted).orElse(null));
    }

    private static LoanServicingRequestView servicingView(LendingOperatorLists.ServicingRequest request, String caller) {
        return new LoanServicingRequestView(request.id().toString(), "lending." + request.kind().name(), "LOAN",
                request.loan().value().toString(), request.proposedBy(), request.proposedBy().equals(caller),
                request.proposedAt().toString(), request.reason(), request.repayment().map(UUID::toString).orElse(null));
    }

    private static LoanTermsVersionHeadView termsView(LendingOperatorLists.TermsVersionHead head, String caller) {
        return new LoanTermsVersionHeadView(head.id().value().toString(), head.product().name(), head.version(),
                head.status().name(), head.proposedBy(), head.proposedBy().equals(caller), head.proposedAt().toString(),
                head.reason(), stamp(head.decidedAt()), head.decisionReason().orElse(null), stamp(head.effectiveFrom()),
                stamp(head.effectiveTo()));
    }

    private static String stamp(Optional<Instant> instant) {
        return instant.map(Instant::toString).orElse(null);
    }

    // ------------------------------------------------------------------ the reader's party

    @FunctionalInterface
    private interface Listing<R> {
        List<R> read(Connection unitOfWork, UUID withheldParty);
    }

    /** Why a list was refused before anything was read: the reader has no party the platform knows, or named their own. */
    private static final class ListRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ListRefused(String cause) {
            super(cause);
        }
    }

    /**
     * {@code listing} read with the reader's own party withheld, on one transaction; a reader the platform cannot place in a
     * party - or one naming their own party - refused before anything is read, the attempt recorded {@code FAILED} in a
     * transaction of its own, then answered {@code 403}.
     */
    private <R> List<R> listed(String collection, String target, String filter, Optional<UUID> named, Listing<R> listing) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            return transactions.inTransaction(uow -> {
                UUID withheld = actingParties.partyOf(uow, actor)
                        .orElseThrow(() -> new ListRefused("the reader is no identity the platform can place in a party"));
                if (named.isPresent() && named.get().equals(withheld)) {
                    throw new ListRefused("the list names the reader's own party");
                }
                return listing.read(uow, withheld);
            });
        } catch (ListRefused refused) {
            transactions.inTransaction(uow -> {
                audit.append(uow, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                        LendingAuditAction.SELF_DEALING_REFUSED, collection, target, Optional.empty(), AuditOutcome.FAILED,
                        correlation, Optional.of("act=list:" + collection + ", " + filter + " refused - " + refused.getMessage())));
                return null;
            });
            throw new ApiException(LendingErrorCode.SELF_DEALING_REFUSED, "A lending list was refused",
                    "an operator never lists their own party's records, nor lists when the platform cannot place them;"
                            + " nothing was served.");
        }
    }

    /** One account's read, its self-dealing refusal recorded {@code FAILED} in a transaction of its own and answered 403. */
    private <R> R judged(Actor actor, java.util.function.Supplier<R> read) {
        CorrelationId correlation = correlation();
        try {
            return read.get();
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                explanations.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            throw new ApiException(LendingErrorCode.SELF_DEALING_REFUSED, "A loan investigation was refused",
                    selfDealing.getMessage() + ".");
        } catch (LoanExplanations.LoanNotFound unknown) {
            throw notFound("loan");
        }
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

    private static Optional<LendingOperatorLists.Cursor> after(String collection, String cursor) {
        return OperatorCollection.key(collection, cursor, 2).map(key -> {
            try {
                return new LendingOperatorLists.Cursor(Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS),
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

    private static Optional<Integer> version(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            int version = Integer.parseInt(raw);
            if (version < 1) {
                throw notFound("loan schedule");
            }
            return Optional.of(version);
        } catch (NumberFormatException malformed) {
            throw notFound("loan schedule");
        }
    }

    /** A loan id is a UUIDv7 (ADR-0013): any other identifier names no loan - the uniform 404, never a 500. */
    private static LoanId loanId(String raw) {
        if (raw == null) {
            throw notFound("loan");
        }
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound("loan");
        }
    }

    private static ApiException notFound(String what) {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No " + what + " matches the requested identifier",
                "no such " + what + ".");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a lending read runs inside a correlation scope"))
                .correlationId();
    }
}
