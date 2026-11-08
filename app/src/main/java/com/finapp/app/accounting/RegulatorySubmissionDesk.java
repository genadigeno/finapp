package com.finapp.app.accounting;

import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.FigurePath;
import com.finapp.accounting.RegulatoryForm;
import com.finapp.accounting.RegulatoryFormLine;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryReportingProvider;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissionStore;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.RegulatoryValidation;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the regulatory submission's doors (`P14-TSK-018`; {@code PHASE_14_PLAN.md} sections 10, 13.7; {@code RiskReportDesk}'s and {@code PriorPeriodAdjustmentDesk}'s shape): each act keyed per principal ({@code accounting.regulatory:<type>:<id>}) and run in one
 * transaction under its span; the domain's refusals translated onto the error contract, a refused self-approval or self-sending recorded {@code FAILED} in a transaction of its own first; every read recorded {@code regulatory.SubmissionRead} in a transaction of its own
 * <em>before</em> it is answered; and, after a sending's transaction has committed - and again on every replay of its key - the return driven to the provider as the platform.
 *
 * <p><strong>The key's stored answer is the submission's id, never its content</strong>: the same key with the same body replays, and the receipt is the return as it stands now; the same key with another body is the executor's conflict, the fingerprint covering the reason and what was
 * named. Beneath the key, the domain converges the same person's repeat.
 *
 * <p><strong>What each role can open</strong> ({@link RegulatoryLineageView}, {@link RegulatoryDrillDownView}). A {@code REGULATORY_REPORTER} and a {@code FINANCIAL_CONTROLLER} read each form line and the run's figure behind it, read back from bytes proved against the stored hash, under their
 * own permission here. <strong>Neither holds {@code ACCOUNTING_READ}</strong> and none is granted: the journal lines behind a figure are the accountant's, and the view names that route, the permission it asks and the roles that hold it - computed from {@link RoleName}, so a note cannot go
 * stale - with the run's id as the hand-off. An accountant reads the run and its lines and cannot read this.
 *
 * <p>What leaves: codes, amounts as minor units in strings with their currency and scale, the database's stamps, the provider's bytes as base64 and only as data, and to the approver alone the preparer's reason. Never a person. Every view's {@code toString} is overridden: a generated one
 * would carry a figure, a reason or a provider's text into a log line.
 */
@RequiredArgsConstructor
public final class RegulatorySubmissionDesk {

    static final String SCOPE = "accounting.regulatory:";

    static final String DRAFT_SPAN = "accounting.regulatory.draft";
    static final String VALIDATE_SPAN = "accounting.regulatory.validate";
    static final String APPROVE_SPAN = "accounting.regulatory.approve";
    static final String REFUSE_SPAN = "accounting.regulatory.refuse";
    static final String WITHDRAW_SPAN = "accounting.regulatory.withdraw";
    static final String SUBMIT_SPAN = "accounting.regulatory.submit";
    static final String UNDELIVERED_SPAN = "accounting.regulatory.undelivered";
    static final String READ_SPAN = "accounting.regulatory.read";

    /** What a reader of any return is told of its standing. */
    public static final String NOTICE = "A reference return, drawn by a simulated framework. No jurisdiction, channel or form it names is real, and it asserts nothing about any legal obligation.";

    /** The route holding a figure's journal lines, by GL account. */
    static final String LINES_ROUTE = "GET /v1/operator/accounting/report-runs/%s/lines?account=%s&currency=%s&month=%s";

    /** The route holding a run whole: its body's {@code drillDown} names the routes per figure. */
    static final String RUN_ROUTE = "GET /v1/operator/accounting/report-runs/%s";

    private static final Pattern GL_ACCOUNT = Pattern.compile("glAccount=([0-9]{4,8})");

    @NonNull private final RegulatorySubmissions submissions;
    @NonNull private final RegulatorySubmissionDriver driver;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AccountingFlowScope flow;
    @NonNull private final RegulatorySubmissionMetrics metrics;

    // ------------------------------------------------------------------ what leaves

    /** An act's receipt: the return as it stands now. No figure. */
    public record RegulatorySubmissionReceipt(String submissionId, String status, String statusMeaning, String standing, String contentSha256, int periodRevision, boolean monthMoved, Boolean validationPassed) {}

    /** One return in a list - read one to act on it. No figure, no person. */
    public record RegulatorySubmissionSummaryView(String submissionId, String standing, String status, String statusMeaning, String jurisdictionCode, String formCode, int formVersion, String period, int periodRevision, int periodRevisionNow,
            boolean monthMoved, boolean overtaken, boolean overdue, boolean resendsExhausted, boolean nonDeliveryOpen, long contraryAnswers, String correctsSubmissionId, String correctedBySubmissionId, String preparedAt) {}

    /** One page of what stands. {@code nextAfter} is the cursor of the next, and null only when no more exist. */
    public record RegulatorySubmissionStandingView(List<RegulatorySubmissionSummaryView> submissions, String nextAfter) {}

    /** The route that holds a figure's journal lines, what it asks, and who holds that: said plainly, because the reader of this view may hold none of them. */
    public record RegulatoryDrillDownView(String route, String requiresPermission, List<String> heldByRoles, String note) {}

    /**
     * A form line's way back: the run, its hash and the resolved path, and the run's figure read back from bytes proved.
     *
     * @param runFigureMinorUnits null when the run's bytes do not prove, or the path finds no one figure
     * @param agreesWithTheRun the run's figure is this line's amount, currency and scale
     */
    public record RegulatoryLineageView(String runId, String runSha256, String runStatus, String definition, String figurePath, String runFigureMinorUnits, String runFigureCurrency, Integer runFigureScale, boolean agreesWithTheRun,
            RegulatoryDrillDownView drillDown) {

        /** A figure stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryLineageView[" + runId + ", agrees=" + agreesWithTheRun + "]";
        }
    }

    /** One form line: the amount in minor units as a string, its currency and scale. Per currency, never a float. */
    public record RegulatoryFormLineView(int ordinal, String code, String label, String currency, String minorUnits, int scale, RegulatoryLineageView lineage) {

        /** A figure stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryFormLineView[" + code + ", " + currency + "]";
        }
    }

    /** One rule's judgement in one currency: codes, never an amount. */
    public record RegulatoryValidationFindingView(String rule, String type, String currency, boolean passed, List<String> detail) {}

    /** The validation as recorded. */
    public record RegulatoryValidationView(boolean passed, List<RegulatoryValidationFindingView> findings) {}

    /**
     * A provider's answer, as data: the bytes as base64, and what was read off them. Untrusted.
     *
     * @param readOutcome what the record took it to say; an {@code anomaly} says why it decided nothing
     * @param flag {@code LATE} or {@code CONTRARY} when it came after the return's conclusion: kept, never an edge
     * @param truncated what is held is the first bytes of {@code receivedBytes}, and {@code rawSha256} is of the whole
     */
    public record RegulatoryProviderAnswerView(String answerId, int attempt, String call, String statedOutcome, String readOutcome, String providerReference, String rejectionCode, String anomaly, String flag, String rawBase64, String rawSha256,
            long receivedBytes, boolean truncated, String receivedAt) {

        /** A provider's text stays out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryProviderAnswerView[" + answerId + ", " + readOutcome + "]";
        }
    }

    /**
     * A return as its reader reads it.
     *
     * @param monthMoved the month's revision is no longer the one read: it may read {@code CLOSED} again and still not be that month. A return already sent stands, frozen, and disagrees with the books
     * @param overtaken sent or acknowledged, the month moved, and no return of the form acknowledged at the month's present revision: the regulator holds, or may hold, a figure the books no longer say. A draft or a rejected correction does not clear it
     * @param answersKept how many of the provider's answers are kept: {@code answers} is the newest of them, an older one not served by this read
     * @param nonDeliveryOpen the non-delivery door would ask the provider now: a definite "not received" last, and the resends spent, the horizon past or the month moved
     * @param stillStands an act going forward would find it stands. No promise: the act judges again under its locks
     * @param blockedBy why it does not, when it does not
     * @param preparationReason the preparer's reason: to the approver's door alone
     */
    public record RegulatorySubmissionView(String submissionId, String standing, String notice, String status, String statusMeaning, String obligationId, String obligationStatus, String configurationSha256, String adapterName, String jurisdictionCode,
            String formCode, int formVersion, String period, int periodRevision, int periodRevisionNow, String periodStatusNow, boolean monthMoved, boolean overtaken, boolean stillStands, String blockedBy, String contentSha256, RegulatoryValidationView validation,
            List<RegulatoryFormLineView> lines, List<RegulatoryProviderAnswerView> answers, long answersKept, String correctsSubmissionId, String correctedBySubmissionId, String providerReference, String rejectionCode, int attempts, int resends, String lastAnswer,
            boolean overdue, boolean resendsExhausted, boolean nonDeliveryOpen, long contraryAnswers, String preparedAt, String validatedAt, String approvedAt, String submittedAt, String closedAt, String concludedAt, String answerHorizonAt, String preparationReason) {

        /** Figures, a reason and a provider's text stay out of a log line. */
        @Override
        public String toString() {
            return "RegulatorySubmissionView[" + submissionId + ", " + status + "]";
        }
    }

    /** The document the adapter would send, whatever it is: what it says of itself first. */
    public record RegulatoryReferenceExportView(String submissionId, String standing, String channel, String notice, String sha256, String document) {

        /** Figures stay out of a log line. */
        @Override
        public String toString() {
            return "RegulatoryReferenceExportView[" + submissionId + ", " + standing + ", " + channel + "]";
        }
    }

    // ------------------------------------------------------------------ acts

    public RegulatorySubmissionReceipt draft(String idempotencyKey, String rawObligation, String rawPeriod, String rawCorrects, String reason) {
        RegulatoryObligationId obligation = named(rawObligation, RegulatoryObligationId::of, "the obligation is named by its identifier");
        AccountingPeriodId period = named(rawPeriod, AccountingPeriodId::of, "the period is named by its identifier");
        Optional<RegulatorySubmissionId> corrects = Optional.ofNullable(rawCorrects).map(raw -> named(raw, RegulatorySubmissionId::of, "a correction names the return it corrects by its identifier"));
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String stored = flow.door(DRAFT_SPAN, () -> keyed(actor, idempotencyKey, "submission-draft|" + obligation.value() + "|" + period.value() + "|" + rawCorrects + "|" + reason,
                uow -> submissions.draft(uow, new RegulatorySubmissions.Draft(obligation, period, corrects, reason), actor, correlation)));
        return receipt(RegulatorySubmissionId.of(UUID.fromString(stored)));
    }

    public RegulatorySubmissionReceipt validate(String idempotencyKey, String rawId) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(VALIDATE_SPAN, () -> keyed(actor, idempotencyKey, "submission-validation|" + id.value(), uow -> submissions.validate(uow, id, actor, correlation)));
        return receipt(id);
    }

    public RegulatorySubmissionReceipt approve(String idempotencyKey, String rawId, String contentSha256, int periodRevision, String reason) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(APPROVE_SPAN, () -> keyed(actor, idempotencyKey, "submission-approval|" + id.value() + "|" + contentSha256 + "|" + periodRevision + "|" + reason,
                uow -> submissions.approve(uow, id, new RegulatorySubmissions.Attested(contentSha256, periodRevision), reason, actor, correlation)));
        return receipt(id);
    }

    public RegulatorySubmissionReceipt refuse(String idempotencyKey, String rawId, String reason) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(REFUSE_SPAN, () -> keyed(actor, idempotencyKey, "submission-refusal|" + id.value() + "|" + reason, uow -> submissions.refuse(uow, id, reason, actor, correlation)));
        return receipt(id);
    }

    public RegulatorySubmissionReceipt withdraw(String idempotencyKey, String rawId, String reason) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(WITHDRAW_SPAN, () -> keyed(actor, idempotencyKey, "submission-withdrawal|" + id.value() + "|" + reason, uow -> submissions.withdraw(uow, id, reason, actor, correlation)));
        return receipt(id);
    }

    public RegulatorySubmissionReceipt submit(String idempotencyKey, String rawId, String contentSha256, int periodRevision, String reason) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        flow.door(SUBMIT_SPAN, () -> keyed(actor, idempotencyKey, "submission-sending|" + id.value() + "|" + contentSha256 + "|" + periodRevision + "|" + reason,
                uow -> submissions.submit(uow, id, new RegulatorySubmissions.Attested(contentSha256, periodRevision), reason, actor, correlation)));
        // SUBMITTED has committed, or its key replayed it: now, outside every transaction and as the platform, the return to the provider - again on every retry, which claims only a permit
        // that has come, so it never sends twice. A failure is the driver's to count; the return stands and the leg asks.
        driver.submitIfDue(id);
        return receipt(id);
    }

    /**
     * Concludes a return undelivered on what the provider says now: {@code last_answer} can be an interval old. The provider is asked once, on no transaction, when the row says the door is open; what it says is kept whatever then happens; the person's act then judges that answer.
     * <strong>When the call fails, or its answer cannot be kept, nothing is concluded</strong>: {@code 503 accounting.RegulatoryProviderUnavailable}, the row unmoved, the key unspent, and the person asks again. The reason is judged first, since a refusal this door can make alone must cost
     * the provider no question and the record no row. A key reused with another body is judged only inside the act, so that refusal does follow a question and a kept answer: the platform's executor offers no read of a key before its claim.
     */
    public RegulatorySubmissionReceipt declareUndelivered(String idempotencyKey, String rawId, String reason) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        guarded(() -> AccountingReasons.reasoned(reason));
        Optional<RegulatorySubmissions.Recorded> asked = flow.door(UNDELIVERED_SPAN, () -> askedAtTheDoor(id, correlation));
        flow.door(UNDELIVERED_SPAN, () -> keyed(actor, idempotencyKey, "submission-non-delivery|" + id.value() + "|" + reason, uow -> submissions.declareUndelivered(uow, id, asked, reason, actor, correlation)));
        return receipt(id);
    }

    /** Empty when the row says the door is not open, or the return has already left {@code SUBMITTED}: the person's act then refuses or converges, and nothing was asked. */
    private Optional<RegulatorySubmissions.Recorded> askedAtTheDoor(RegulatorySubmissionId id, CorrelationId correlation) {
        RegulatorySubmissionStore.Row row = transactions.inTransaction(uow -> submissions.current(uow, id))
                .orElseThrow(() -> new ApiException(AccountingErrorCode.NOT_FOUND, "No regulatory submission answers the request", "no such record."));
        if (!RegulatorySubmissions.nonDeliveryOpen(row)) {
            return Optional.empty();
        }
        return Optional.of(driver.askBeforeNonDelivery(row, correlation).orElseThrow(() -> new ApiException(AccountingErrorCode.REGULATORY_PROVIDER_UNAVAILABLE, "A regulatory submission command was refused",
                "the provider could not be asked at this act, so nothing was concluded; the return stands SUBMITTED; ask again")));
    }

    // ------------------------------------------------------------------ reads

    /** A reporter's read: the draft and its figures, under {@code REGULATORY_REPORT_PREPARE}. Recorded before it is served. */
    public RegulatorySubmissionView read(String rawId) {
        return served(submissionId(rawId), "preparer", false);
    }

    /** An approver's read of what they attest, under {@code REGULATORY_REPORT_APPROVE}. Recorded before it is served. */
    public RegulatorySubmissionView attestation(String rawId) {
        return served(submissionId(rawId), "approver", true);
    }

    private RegulatorySubmissionView served(RegulatorySubmissionId id, String door, boolean withReason) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RegulatorySubmissions.View view = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> submissions.view(uow, id))));
        recordRead(id.value().toString(), "door=" + door + ", status=" + view.row().status().name() + ", stillStands=" + view.stillStands() + ", monthMoved=" + view.row().monthMoved() + ", lines=" + view.lines().size()
                + ", answers=" + view.answers().size(), actor, correlation);
        return view(view, withReason);
    }

    /** What a preparer must act on or know of, one page, oldest first. Recorded before it is served. */
    public RegulatorySubmissionStandingView standing(String rawAfter, Integer rawLimit) {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Optional<RegulatorySubmissionId> after = Optional.ofNullable(rawAfter).map(raw -> named(raw, RegulatorySubmissionId::of, "after is the cursor of a previous page"));
        int limit = rawLimit == null ? RegulatorySubmissions.LIST_LIMIT : rawLimit;
        RegulatorySubmissions.Standing page = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> submissions.standing(uow, after, limit))));
        recordRead("standing", "door=standing, rows=" + page.rows().size() + ", page=" + (after.isPresent() ? "next" : "first") + ", more=" + page.nextAfter().isPresent(), actor, correlation);
        return new RegulatorySubmissionStandingView(page.rows().stream().map(RegulatorySubmissionDesk::summary).toList(), id(page.nextAfter()));
    }

    /** What this controller can decide: never their own. Recorded before it is served. */
    public List<RegulatorySubmissionSummaryView> awaitingApproval() {
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        List<RegulatorySubmissionStore.Row> rows = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> submissions.awaitingApproval(uow, actor))));
        recordRead("awaiting-approval", "door=awaiting-approval, rows=" + rows.size(), actor, correlation);
        return rows.stream().map(RegulatorySubmissionDesk::summary).toList();
    }

    /** The document its adapter would send. Recorded before it is served. */
    public RegulatoryReferenceExportView referenceExport(String rawId) {
        RegulatorySubmissionId id = submissionId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RegulatoryReportingProvider.ReferenceExport export = flow.door(READ_SPAN, () -> guarded(() -> transactions.inTransaction(uow -> submissions.export(uow, id))));
        recordRead(id.value().toString(), "door=reference-export, standing=" + export.standing() + ", channel=" + export.channel(), actor, correlation);
        return new RegulatoryReferenceExportView(id.value().toString(), export.standing(), export.channel(), export.notice(), export.sha256(), export.document());
    }

    private void recordRead(String target, String summary, Actor actor, CorrelationId correlation) {
        transactions.inTransaction(uow -> {
            submissions.recordRead(uow, target, summary, actor, correlation);
            return null;
        });
    }

    // ------------------------------------------------------------------ views

    private static RegulatorySubmissionView view(RegulatorySubmissions.View view, boolean withReason) {
        RegulatorySubmissionStore.Row row = view.row();
        return new RegulatorySubmissionView(row.id().value().toString(), RegulatoryForm.REFERENCE, NOTICE, row.status().name(), row.status().meaning(), row.obligation().value().toString(), view.obligation().status().name(),
                view.obligation().configurationSha256(), row.adapter(), row.jurisdiction(), row.form(), row.formVersion(), row.month().toString(), row.periodRevision(), row.periodRevisionNow(), row.periodStatusNow().name(), row.monthMoved(), row.overtaken(),
                view.stillStands(), view.blockedBy().map(Enum::name).orElse(null), row.contentSha256(), view.validation().map(RegulatorySubmissionDesk::validation).orElse(null),
                view.lines().stream().map(line -> line(line, row)).toList(), view.answers().stream().map(RegulatorySubmissionDesk::answer).toList(), view.answersKept(), id(row.corrects()), id(row.correctedBy()), row.providerReference().orElse(null),
                row.rejectionCode().orElse(null), row.attempts(), row.resends(), row.lastAnswer().orElse(null), overdue(row), view.resendsExhausted(), view.nonDeliveryOpen(), row.contraryAnswers(), row.preparedAt().toString(), instant(row.validatedAt()),
                instant(row.approvedAt()), instant(row.submittedAt()), instant(row.closedAt()), instant(row.concludedAt()), instant(row.answerHorizonAt()), withReason ? row.preparationReason() : null);
    }

    private static boolean overdue(RegulatorySubmissionStore.Row row) {
        return row.status() == SubmissionStatus.SUBMITTED && row.pastHorizon();
    }

    private static RegulatoryValidationView validation(RegulatoryValidation validation) {
        return new RegulatoryValidationView(validation.passed(), validation.findings().stream()
                .map(finding -> new RegulatoryValidationFindingView(finding.rule(), finding.type().name(), finding.currency(), finding.passed(), finding.detail())).toList());
    }

    private static RegulatoryFormLineView line(RegulatorySubmissions.LineView view, RegulatorySubmissionStore.Row row) {
        RegulatoryFormLine line = view.line();
        Optional<FigurePath.Figure> figure = view.figure();
        return new RegulatoryFormLineView(line.ordinal(), line.code(), line.label(), line.currency(), line.minor().toString(), line.scale(),
                new RegulatoryLineageView(line.run().value().toString(), line.runSha256(), view.runStatus().map(RunStatus::name).orElse(null), line.source(), line.figure(), figure.map(found -> found.minor().toString()).orElse(null),
                        figure.map(FigurePath.Figure::currency).orElse(null), figure.map(FigurePath.Figure::scale).orElse(null), view.agrees(), drillDown(line, row)));
    }

    /** The route that really holds this figure's lines, the permission it really asks, and who really holds that. */
    static RegulatoryDrillDownView drillDown(RegulatoryFormLine line, RegulatorySubmissionStore.Row row) {
        Matcher account = GL_ACCOUNT.matcher(line.figure());
        String run = line.run().value().toString();
        String route = account.find() ? String.format(LINES_ROUTE, run, account.group(1), line.currency(), row.month()) : String.format(RUN_ROUTE, run);
        List<String> holders = Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(PermissionName.ACCOUNTING_READ)).map(Enum::name).sorted().toList();
        return new RegulatoryDrillDownView(route, PermissionName.ACCOUNTING_READ.name(), holders,
                "This route asks " + PermissionName.ACCOUNTING_READ.name() + ", which a regulatory reporter's and a financial controller's roles do not carry. The roles named here open it, by the run's id.");
    }

    private static RegulatoryProviderAnswerView answer(RegulatorySubmissionStore.AnswerRow answer) {
        return new RegulatoryProviderAnswerView(answer.id().toString(), answer.attempt(), answer.call().name(), answer.stated().name(), answer.read().name(), answer.providerReference().orElse(null), answer.rejectionCode().orElse(null),
                answer.anomaly().map(Enum::name).orElse(null), answer.flag().map(Enum::name).orElse(null), Base64.getEncoder().encodeToString(answer.raw()), answer.rawSha256(), answer.receivedBytes(), answer.truncated(), answer.receivedAt().toString());
    }

    private static RegulatorySubmissionSummaryView summary(RegulatorySubmissionStore.Row row) {
        boolean exhausted = row.status() == SubmissionStatus.SUBMITTED && row.lastAnswer().filter("NOT_RECEIVED"::equals).isPresent() && row.resends() >= RegulatorySubmissions.MAX_RESENDS;
        return new RegulatorySubmissionSummaryView(row.id().value().toString(), RegulatoryForm.REFERENCE, row.status().name(), row.status().meaning(), row.jurisdiction(), row.form(), row.formVersion(), row.month().toString(), row.periodRevision(), row.periodRevisionNow(),
                row.monthMoved(), row.overtaken(), overdue(row), exhausted, RegulatorySubmissions.nonDeliveryOpen(row), row.contraryAnswers(), id(row.corrects()), id(row.correctedBy()), row.preparedAt().toString());
    }

    private static String id(Optional<RegulatorySubmissionId> id) {
        return id.map(found -> found.value().toString()).orElse(null);
    }

    private static String instant(Optional<Instant> at) {
        return at.map(Instant::toString).orElse(null);
    }

    // ------------------------------------------------------------------ plumbing

    /** One keyed act in one transaction: a lost response replays the stored id, and a refusal stores nothing. Counted after the commit, only when it did something. */
    private String keyed(Actor actor, String idempotencyKey, String fingerprinted, Function<Connection, RegulatorySubmissions.Acted> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.keyedType().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<RegulatorySubmissions.Acted> ran = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome = guarded(() -> selfApprovalRecorded(actor, () -> transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
            RegulatorySubmissions.Acted acted = act.apply(uow);
            ran.set(acted);
            return CommandResult.succeeded(StoredResponse.of(acted.id().value().toString().getBytes(StandardCharsets.UTF_8), "text/plain"));
        }))));
        RegulatorySubmissions.Acted acted = ran.get();
        if (acted != null && !acted.replayed()) {
            metrics.count(outcomeOf(acted));
        }
        return new String(outcome.body().orElseThrow(() -> new IllegalStateException("a regulatory submission's act stores its id")), StandardCharsets.UTF_8);
    }

    private static String outcomeOf(RegulatorySubmissions.Acted acted) {
        return switch (acted.status()) {
            case DRAFT -> acted.validationPassed().isPresent() ? "validation_failed" : "drafted";
            case VALIDATED -> "validated";
            case APPROVED -> "approved";
            case SUBMITTED -> "submitted";
            case REFUSED -> "refused";
            case WITHDRAWN -> "withdrawn";
            case UNDELIVERED -> "undelivered";
            case ACKNOWLEDGED -> "acknowledged";
            case REJECTED -> "rejected";
        };
    }

    /** The receipt of an act just performed or replayed: the return as it stands now. */
    private RegulatorySubmissionReceipt receipt(RegulatorySubmissionId id) {
        RegulatorySubmissionStore.Row row = transactions.inTransaction(uow -> submissions.current(uow, id)).orElseThrow(() -> new IllegalStateException("a return this door acted on is not found"));
        return new RegulatorySubmissionReceipt(id.value().toString(), row.status().name(), row.status().meaning(), RegulatoryForm.REFERENCE, row.contentSha256(), row.periodRevision(), row.monthMoved(), row.validationPassed().orElse(null));
    }

    private <R> R selfApprovalRecorded(Actor actor, Supplier<R> work) {
        try {
            return work.get();
        } catch (RegulatorySubmissions.SelfApprovalRefused self) {
            CorrelationId correlation = correlation();
            transactions.inTransaction(uow -> {
                submissions.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            throw self;
        }
    }

    /** The domain's refusals, in the API's words. A detail names codes and months, never a figure or a reason. */
    private <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (RegulatorySubmissions.SubmissionNotFound | AccountingPeriods.PeriodNotFound unknown) {
            throw new ApiException(AccountingErrorCode.NOT_FOUND, "No regulatory submission answers the request", "no such record.");
        } catch (RegulatorySubmissions.SelfApprovalRefused self) {
            metrics.count("self_approval_refused");
            throw refused(AccountingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RegulatorySubmissions.SubmissionStale stale) {
            metrics.count("stale");
            throw refused(AccountingErrorCode.REGULATORY_SUBMISSION_STALE, stale);
        } catch (RegulatorySubmissions.SubmissionLive live) {
            throw refused(AccountingErrorCode.REGULATORY_SUBMISSION_LIVE, live);
        } catch (RegulatorySubmissions.SubmissionInvalid invalid) {
            throw refused(AccountingErrorCode.REGULATORY_SUBMISSION_INVALID, invalid);
        } catch (RegulatorySubmissions.SourceNotFinal notFinal) {
            metrics.count("source_not_final");
            throw refused(AccountingErrorCode.REGULATORY_SOURCE_NOT_FINAL, notFinal);
        } catch (RegulatorySubmissions.RunBytesDiffer differ) {
            metrics.count("run_bytes_differ");
            throw refused(AccountingErrorCode.REGULATORY_RUN_BYTES_DIFFER, differ);
        } catch (RegulatoryForm.FigureUnresolved unresolved) {
            metrics.count("figure_unresolved");
            throw refused(AccountingErrorCode.REGULATORY_FIGURE_UNRESOLVED, unresolved);
        } catch (AccountingReasons.ReasonRequired reason) {
            throw refused(AccountingErrorCode.REASON_REQUIRED, reason);
        }
    }

    private static ApiException refused(AccountingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A regulatory submission command was refused", cause.getMessage());
    }

    private static RegulatorySubmissionId submissionId(String raw) {
        try {
            return RegulatorySubmissionId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.NOT_FOUND, "No regulatory submission answers the request", "no such record.");
        }
    }

    private static <T> T named(String raw, Function<UUID, T> of, String detail) {
        try {
            return of.apply(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw new ApiException(AccountingErrorCode.REGULATORY_SUBMISSION_INVALID, "A regulatory submission command was refused", detail);
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a regulatory submission command runs inside a correlation scope"))
                .correlationId();
    }
}
