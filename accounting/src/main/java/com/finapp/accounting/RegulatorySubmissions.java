package com.finapp.accounting;

import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.CallKind;
import com.finapp.accounting.RegulatoryReportingProvider.Outcome;
import com.finapp.accounting.RegulatorySubmissionStore.Act;
import com.finapp.accounting.RegulatorySubmissionStore.Flag;
import com.finapp.accounting.RegulatorySubmissionStore.Row;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The regulatory submission's machine (`P14-TSK-018`; ADR-0118; {@code PHASE_14_PLAN.md} sections 6, 8.3 row 13, 13.7; {@code INV-ACC-13}, {@code INV-ACC-04}, {@code INV-HIST-02}, {@code INV-AUD-01}, {@code INV-AUD-04}). It moves no money. Its worst
 * failures are about what a regulator is told: a submitted line no FINAL figure explains; a return drawn from a month since reopened and re-closed; one return sent twice, or never delivered and never noticed; a state no door leaves.
 *
 * <p><strong>{@code CLOSED} round-trips, so a status says nothing about which books were read.</strong> A month closed, reopened and re-closed reads {@code CLOSED} again, its FINAL superseded. {@link #stands} therefore compares the period's
 * <em>revision</em> and each run's <em>id and hash</em>, re-proving the run's bytes, at every act going forward: validate, approve, submit. Under a lock: (A1) the period {@code FOR SHARE}, then the obligation {@code FOR SHARE}, then (A4) the
 * submission {@code FOR UPDATE}. {@code accounting V010}'s {@code regulatory_submission_judge_stands} compares the same for every writer.
 *
 * <p><strong>A reopen's approval against a submit</strong>: the reopen holds the period {@code FOR UPDATE} and moves its revision, a submit holds it {@code FOR SHARE}, so they serialise. Submit first: {@code SUBMITTED} commits against the old
 * revision, and from then on the frozen return reads {@code monthMoved}. Reopen first: the submit reads the new revision and is {@link SubmissionStale}, nothing written. There is no third order.
 *
 * <p><strong>A month reopened after a return went</strong>: the return stands, frozen, disagreeing with the books. It is not edited, and it does not leave. {@code RegulatorySubmissionStore.Row.monthMoved} is on every read and list, and
 * {@code finapp.accounting.regulatory.attention{kind=overtaken}} counts it until a correction stands.
 *
 * <p><strong>Every state has a door out</strong> ({@link SubmissionStatus}). {@link #withdraw} a preparer's, {@link #refuse} a controller's: each asks nothing of the month, because each is how something overtaken is ended. {@link #record} is the
 * provider's answer. {@link #declareUndelivered} a person's, only on a definite "not received". A {@code SUBMITTED} row the provider answers {@code UNKNOWN} forever has no person's door: nobody here may assert what a regulator holds; the inquiry goes on
 * and it reads overdue.
 *
 * <p><strong>The drive, in three steps, the provider in no transaction</strong>: {@link #claim} commits one attempt and a lease; {@link #ask} calls - the first attempt sends, since no earlier call can have preceded it, every later one inquires -;
 * {@link #record} takes the answer. A definite "not received" lets the driver {@link #permitResend} (counted and committed before the call, {@code last_answer} {@code RESENDING}) and {@link #resend} under the same submission id: the provider's
 * idempotency doing its job, not a second submission. At most {@link #MAX_RESENDS}. An unknown answer is an inquiry, never a re-submit.
 *
 * <p><strong>Ack against rejection is a one-way edge.</strong> The row's lock serialises answers; the first definite one concludes it. Any answer after that is kept verbatim and flagged, {@link Flag#CONTRARY} when it disagrees, and is never an edge.
 *
 * <p>A reason is never logged, never in a summary, an event or an exception. Summaries and events carry ids and codes, never a figure and never a provider's text. No step here holds a connection across a call.
 */
@RequiredArgsConstructor
public final class RegulatorySubmissions {

    public static final String SUBMITTED_EVENT = "accounting.RegulatorySubmissionSubmitted";
    public static final String ACKNOWLEDGED_EVENT = "accounting.RegulatorySubmissionAcknowledged";
    /** The provider's rejection - never a controller's refusal. */
    public static final String REJECTED_EVENT = "accounting.RegulatorySubmissionRejected";
    /** A controller's refusal - never the provider's rejection. */
    public static final String REFUSED_EVENT = "accounting.RegulatorySubmissionRefused";
    public static final String WITHDRAWN_EVENT = "accounting.RegulatorySubmissionWithdrawn";
    public static final String UNDELIVERED_EVENT = "accounting.RegulatorySubmissionUndelivered";

    /** The audit records' and the events' target type. */
    public static final String TARGET_TYPE = "regulatory_submission";

    /** How many times one id is sent again after a definite "not received". */
    public static final int MAX_RESENDS = 5;

    /** The most rows one list answers. */
    public static final int LIST_LIMIT = 100;

    private static final String PRODUCER = "accounting";
    private static final int EVENT_VERSION = 1;

    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final RegulatorySubmissionStore store;
    @NonNull private final RegulatoryObligationStore obligations;
    @NonNull private final ReportRunStore runs;
    @NonNull private final RegulatoryAdapters adapters;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    /** From {@code SUBMITTED}, how long before a return with no answer reads overdue. */
    @NonNull private final Duration answerHorizon;

    // ------------------------------------------------------------------ inputs and outcomes

    /** What a preparer asks: a version in force, a month, what it corrects if anything, and why. */
    public record Draft(RegulatoryObligationId obligation, AccountingPeriodId period, Optional<RegulatorySubmissionId> corrects, String reason) {

        public Draft {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(corrects, "corrects");
        }

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "Draft[" + obligation.value() + ", " + period.value() + "]";
        }
    }

    /** What an approver or a sender read and now names. */
    public record Attested(String contentSha256, int periodRevision) {}

    /** An act's outcome; {@code replayed} when this person's act was already done and nothing was written. */
    public record Acted(RegulatorySubmissionId id, SubmissionStatus status, String contentSha256, int periodRevision, Optional<Boolean> validationPassed, boolean replayed) {

        static Acted of(Row row, SubmissionStatus status, Optional<Boolean> passed, boolean replayed) {
            return new Acted(row.id(), status, row.contentSha256(), row.periodRevision(), passed, replayed);
        }
    }

    /** Why a return no longer stands on the books it read. */
    public enum BlockedBy { MONTH_MOVED, OBLIGATION_RETIRED, RUN_SUPERSEDED, RUN_BYTES_DIFFER }

    /**
     * One form line as a reader follows it: the line, and the run's figure read back through its path from bytes proved against the stored hash.
     *
     * @param figure empty when the run's bytes do not prove, or the path no longer finds one figure
     * @param agrees the run's figure is this line's amount, currency and scale
     */
    public record LineView(RegulatoryFormLine line, Optional<RunStatus> runStatus, Optional<FigurePath.Figure> figure, boolean agrees) {

        /** An amount stays out of a log line. */
        @Override
        public String toString() {
            return "LineView[" + line.code() + ", " + line.currency() + ", agrees=" + agrees + "]";
        }
    }

    /**
     * What a reader reads.
     *
     * @param blockedBy empty when an act going forward would find it stands. Advisory: an act judges again under its locks. Of a return already sent, why it no longer agrees with the books
     * @param resendsExhausted a definite "not received" last and no resend left: a person's door is open
     */
    public record View(Row row, RegulatoryObligationStore.Row obligation, List<LineView> lines, Optional<RegulatoryValidation> validation, List<RegulatorySubmissionStore.AnswerRow> answers, Optional<BlockedBy> blockedBy,
            boolean resendsExhausted) {

        /** Whether the submission could still go forward on what it read. */
        public boolean stillStands() {
            return !row.status().terminal() && !row.status().immutable() && blockedBy.isEmpty();
        }
    }

    /** A claim's outcome: the row, and the text to send, read in the claim's transaction so no connection crosses the call. */
    public record Claimed(Row row, String content) {

        /** Figures stay out of a log line. */
        @Override
        public String toString() {
            return "Claimed[" + row + "]";
        }
    }

    /** A call's outcome: which call, and what came back. */
    public record Answered(CallKind call, Answer answer) {}

    /** What the record did with an answer. */
    public enum Disposition { ACKNOWLEDGED, REJECTED, AWAITING, NOT_RECEIVED, LATE, CONTRARY }

    /** An answer's record: what it did, and whether it decided nothing because it could not be believed. */
    public record Recorded(Disposition disposition, boolean anomalous) {}

    // ------------------------------------------------------------------ refusals

    /** No submission answers the request. */
    public static final class SubmissionNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionNotFound() {
            super("no regulatory submission answers this request");
        }
    }

    /** What the act names is no longer what stands: its status, its content, its revision, the month, the version or a run. */
    public static final class SubmissionStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionStale(String detail) {
            super(detail);
        }
    }

    /** A live return holds the form and month, or one of them was sent and this names no correction. */
    public static final class SubmissionLive extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionLive(String detail) {
            super(detail);
        }
    }

    /** The draft is not coherent: a correction of what it may not correct, an obligation that is no such thing. */
    public static final class SubmissionInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SubmissionInvalid(String detail) {
            super(detail);
        }
    }

    /** The month is not {@code CLOSED}, or a source has no FINAL run at its present revision. Names the month and the definition, never a figure. */
    public static final class SourceNotFinal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SourceNotFinal(String detail) {
            super(detail);
        }
    }

    /** A run's bytes are not the hash it stores or the hash this return named. An integrity failure, never a figure in the message. */
    public static final class RunBytesDiffer extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RunBytesDiffer(String definition) {
            super("the report run of " + definition + " does not prove against its stored hash; nothing was drawn or moved");
        }
    }

    /** A person tried to attest what they prepared, or to send what they attested ({@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final String target;
        private final String summary;

        SelfApprovalRefused(Row row, String act, String message) {
            super(message);
            this.target = row.id().value().toString();
            this.summary = "form=" + row.form() + ", formVersion=" + row.formVersion() + ", period=" + row.month() + ", act=" + act;
        }

        /** The refusal record's target. */
        public String target() {
            return target;
        }

        /** What the refusal record names. */
        public String summary() {
            return summary;
        }
    }

    // ================================================================== a person's acts

    /**
     * Drafts a return: every source's FINAL run of the month at its present revision, its bytes proved, every line's figure resolved. Born {@code DRAFT}, its lineage with it.
     *
     * @throws AccountingPeriods.PeriodNotFound no such period
     * @throws SourceNotFinal the month is not {@code CLOSED}, or a source has no FINAL run at its present revision
     * @throws RunBytesDiffer a run's bytes do not prove
     * @throws RegulatoryForm.FigureUnresolved a figure resolves to nothing, more than one, or no amount - never a zero
     * @throws SubmissionStale the version is not in force, or this build no longer holds its adapter
     * @throws SubmissionInvalid no such version; a correction of anything but a concluded return of this form and month, or of one already corrected
     * @throws SubmissionLive a live return holds the form and month, or one was sent and this names no correction
     */
    public Acted draft(Connection unitOfWork, Draft request, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(request.reason());
        // A1: the period FOR SHARE. Its month is frozen from birth, so the unlocked read only names the row.
        YearMonth month = periods.find(unitOfWork, request.period()).orElseThrow(AccountingPeriods.PeriodNotFound::new).month();
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, month).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        if (period.status() != PeriodStatus.CLOSED) {
            throw new SourceNotFinal("a return is drawn only from a CLOSED month, and " + month + " is " + period.status().name());
        }
        RegulatoryObligationStore.Row obligation = obligations.lockShared(unitOfWork, request.obligation()).orElseThrow(() -> new SubmissionInvalid("no regulatory obligation answers this draft"));
        if (obligation.status() != RegulatoryObligationStatus.ACTIVE) {
            throw new SubmissionStale("the obligation version is " + obligation.status().name() + ", not in force");
        }
        if (adapters.named(obligation.adapter()).filter(adapter -> adapter.declares(obligation.jurisdiction())).isEmpty()) {
            throw new SubmissionStale("this build holds no adapter for the obligation version");
        }
        judgeChain(unitOfWork, request, obligation, period);

        RegulatoryObligationConfiguration configuration = RegulatoryObligationConfiguration.parse(obligation.configuration());
        Map<String, RegulatoryForm.SourceRun> proved = new LinkedHashMap<>();
        for (RegulatoryObligationConfiguration.Source source : configuration.sources()) {
            proved.put(source.definition(), proved(unitOfWork, source, period));
        }
        List<RegulatoryFormLine> lines = RegulatoryForm.draw(configuration, proved);
        String content = RegulatoryForm.content(new RegulatoryForm.Header(obligation.id(), obligation.jurisdiction(), obligation.form(), obligation.formVersion(), obligation.configurationSha256(), month,
                period.revision(), request.corrects()), List.copyOf(proved.values()), lines);
        String sha256 = CanonicalJson.sha256(content);

        RegulatorySubmissionId id = RegulatorySubmissionId.next(ids);
        boolean born = store.insert(unitOfWork, new RegulatorySubmissionStore.NewSubmission(id, obligation.id(), obligation.jurisdiction(), obligation.form(), obligation.formVersion(), period.id(), period.revision(),
                request.corrects(), content, sha256, actor, reasoned, correlation));
        if (!born) {
            throw new SubmissionLive("a return of this form and month is live, or was sent and a later one names it");
        }
        for (RegulatoryForm.SourceRun run : proved.values()) {
            store.insertSource(unitOfWork, id, new RegulatorySubmissionStore.Source(run.run(), run.runSha256(), run.definition(), run.definitionVersion()));
        }
        store.insertLines(unitOfWork, id, lines);
        Row row = store.find(unitOfWork, id).orElseThrow(() -> new IllegalStateException("a return born here is read back"));
        history(unitOfWork, row, Act.DRAFTED, Optional.empty(), SubmissionStatus.DRAFT, actor, Optional.empty(), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_DRAFTED, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED,
                summary(row) + ", lines=" + lines.size() + ", sources=" + proved.size() + request.corrects().map(corrected -> ", corrects=" + corrected.value()).orElse(""), correlation);
        return Acted.of(row, SubmissionStatus.DRAFT, Optional.empty(), false);
    }

    /**
     * Validates a {@code DRAFT}: each rule of the closed vocabulary over its lines. Passing moves it to {@code VALIDATED}; failing leaves it {@code DRAFT} with the outcome recorded once, and the way on is a withdrawal and a new draft: the lines and rules are
     * frozen, so a repeat could only say the same. A repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SubmissionStale it is past {@code DRAFT}, or no longer stands
     * @throws RunBytesDiffer a run's bytes do not prove
     */
    public Acted validate(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        Held held = held(unitOfWork, id);
        Row row = held.row();
        if (row.status() == SubmissionStatus.DRAFT && row.validationPassed().isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (row.status() != SubmissionStatus.DRAFT) {
            if (row.validatedBy().filter(actor.id()::equals).isPresent() && row.validationPassed().filter(Boolean.TRUE::equals).isPresent()) {
                return Acted.of(row, row.status(), row.validationPassed(), true);
            }
            throw new SubmissionStale("the return is " + row.status().name() + ", no longer awaiting its validation");
        }
        stands(unitOfWork, held);
        RegulatoryValidation validation = RegulatoryValidation.of(RegulatoryObligationConfiguration.parse(held.obligation().configuration()), store.lines(unitOfWork, id));
        if (!store.markValidated(unitOfWork, id, actor, validation.canonical(), validation.passed())) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        SubmissionStatus reached = validation.passed() ? SubmissionStatus.VALIDATED : SubmissionStatus.DRAFT;
        history(unitOfWork, row, validation.passed() ? Act.VALIDATED : Act.VALIDATION_FAILED, Optional.of(SubmissionStatus.DRAFT), reached, actor, Optional.empty(), Optional.empty(), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_VALIDATED, row, Optional.empty(), validation.passed() ? AuditOutcome.SUCCEEDED : AuditOutcome.FAILED,
                summary(row) + ", passed=" + validation.passed() + ", findings=" + validation.findings().stream().filter(finding -> !finding.passed()).count(), correlation);
        return Acted.of(row, reached, Optional.of(validation.passed()), false);
    }

    /**
     * Approves a {@code VALIDATED} return - a second person's act, naming the content's hash and the revision they read. The same approver's repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SelfApprovalRefused the approver prepared it ({@code INV-AUD-04})
     * @throws SubmissionStale it is not {@code VALIDATED}; what is named is not what stands; it no longer stands on the books it read
     * @throws RunBytesDiffer a run's bytes do not prove
     */
    public Acted approve(Connection unitOfWork, RegulatorySubmissionId id, Attested read, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Held held = held(unitOfWork, id);
        Row row = held.row();
        if ((row.status() == SubmissionStatus.APPROVED || row.status().immutable()) && row.approvedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (row.status() != SubmissionStatus.VALIDATED) {
            throw new SubmissionStale("the return is " + row.status().name() + ", not awaiting an approval");
        }
        if (row.preparedBy().equals(actor.id())) {
            throw new SelfApprovalRefused(row, "regulatory-submission:approve", "a regulatory submission is approved by someone other than its preparer");
        }
        named(row, read);
        stands(unitOfWork, held);
        if (!store.markApproved(unitOfWork, id, actor, reasoned, row.contentSha256(), row.periodRevision())) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.APPROVED, Optional.of(SubmissionStatus.VALIDATED), SubmissionStatus.APPROVED, actor, Optional.of(row.preparedBy()), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_APPROVED, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED, summary(row) + ", contentSha256=" + row.contentSha256(), correlation);
        return Acted.of(row, SubmissionStatus.APPROVED, row.validationPassed(), false);
    }

    /**
     * A controller's refusal of a {@code VALIDATED} or {@code APPROVED} return: nothing was sent and nothing will be. Asks nothing of the month, because it is how something overtaken by a reopen is ended. May be a holder who prepared it. Distinct, in name, event
     * and audit, from the provider's rejection. The same person's repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SubmissionStale it is not {@code VALIDATED} or {@code APPROVED}
     */
    public Acted refuse(Connection unitOfWork, RegulatorySubmissionId id, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Row row = store.lock(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        if (row.status() == SubmissionStatus.REFUSED && row.closedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (row.status() != SubmissionStatus.VALIDATED && row.status() != SubmissionStatus.APPROVED) {
            throw new SubmissionStale("the return is " + row.status().name() + ", not awaiting a controller");
        }
        if (!store.markRefused(unitOfWork, id, actor, reasoned)) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.REFUSED, Optional.of(row.status()), SubmissionStatus.REFUSED, actor, Optional.empty(), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_REFUSED, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED, summary(row) + ", from=" + row.status().name(), correlation);
        publish(unitOfWork, REFUSED_EVENT, row, base(row), correlation);
        return Acted.of(row, SubmissionStatus.REFUSED, row.validationPassed(), false);
    }

    /**
     * A preparer takes back a return not yet sent. Asks nothing of the month. Frees the form and month for a new draft. The same person's repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SubmissionStale it was sent, or is already ended
     */
    public Acted withdraw(Connection unitOfWork, RegulatorySubmissionId id, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Row row = store.lock(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        if (row.status() == SubmissionStatus.WITHDRAWN && row.closedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (!row.status().canMoveTo(SubmissionStatus.WITHDRAWN)) {
            throw new SubmissionStale("the return is " + row.status().name() + ", which a preparer can no longer take back");
        }
        if (!store.markWithdrawn(unitOfWork, id, actor, reasoned)) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.WITHDRAWN, Optional.of(row.status()), SubmissionStatus.WITHDRAWN, actor, Optional.empty(), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_WITHDRAWN, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED, summary(row) + ", from=" + row.status().name(), correlation);
        publish(unitOfWork, WITHDRAWN_EVENT, row, base(row), correlation);
        return Acted.of(row, SubmissionStatus.WITHDRAWN, row.validationPassed(), false);
    }

    /**
     * Sends an {@code APPROVED} return: {@code SUBMITTED} commits, with its event, <strong>before</strong> any call. Names the content's hash and the revision read. The sender is never the approver: by role they cannot be, and one identity can hold two roles. The same
     * sender's repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SelfApprovalRefused the sender attested it
     * @throws SubmissionStale it is not {@code APPROVED}; what is named is not what stands; it no longer stands on the books it read; this build no longer holds its adapter
     * @throws RunBytesDiffer a run's bytes do not prove
     */
    public Acted submit(Connection unitOfWork, RegulatorySubmissionId id, Attested read, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(read, "read");
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Held held = held(unitOfWork, id);
        Row row = held.row();
        if (row.status().immutable() && row.submittedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (row.status() != SubmissionStatus.APPROVED) {
            throw new SubmissionStale("the return is " + row.status().name() + ", not awaiting its sending");
        }
        if (row.approvedBy().filter(actor.id()::equals).isPresent()) {
            throw new SelfApprovalRefused(row, "regulatory-submission:submit", "a regulatory submission is sent by someone other than its approver");
        }
        named(row, read);
        stands(unitOfWork, held);
        if (adapters.named(row.adapter()).filter(adapter -> adapter.declares(row.jurisdiction())).isEmpty()) {
            throw new SubmissionStale("this build holds no adapter for the return");
        }
        if (!store.markSubmitted(unitOfWork, id, actor, reasoned, answerHorizon)) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.SUBMITTED, Optional.of(SubmissionStatus.APPROVED), SubmissionStatus.SUBMITTED, actor, Optional.empty(), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_SUBMITTED, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED, summary(row) + ", contentSha256=" + row.contentSha256(), correlation);
        publish(unitOfWork, SUBMITTED_EVENT, row, base(row).with("contentSha256", row.contentSha256()), correlation);
        return Acted.of(row, SubmissionStatus.SUBMITTED, row.validationPassed(), false);
    }

    /**
     * A person concludes a return undelivered - only on the provider's definite "not received", and only once the resends are spent or the horizon has passed: never over an unknown answer, never over a send in flight. A correction may then reference it. The same
     * person's repeat converges.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SubmissionStale it is not {@code SUBMITTED}, the provider has not said it holds nothing, or the driver may still send
     */
    public Acted declareUndelivered(Connection unitOfWork, RegulatorySubmissionId id, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        person(actor);
        String reasoned = AccountingReasons.reasoned(reason);
        Row row = store.lock(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        if (row.status() == SubmissionStatus.UNDELIVERED && row.closedBy().filter(actor.id()::equals).isPresent()) {
            return Acted.of(row, row.status(), row.validationPassed(), true);
        }
        if (row.status() != SubmissionStatus.SUBMITTED || !store.markUndelivered(unitOfWork, id, actor, reasoned, MAX_RESENDS)) {
            throw new SubmissionStale("a return is concluded undelivered only while SUBMITTED, on the provider's definite not received, the resends spent or the answer horizon past");
        }
        history(unitOfWork, row, Act.UNDELIVERED, Optional.of(SubmissionStatus.SUBMITTED), SubmissionStatus.UNDELIVERED, actor, Optional.empty(), Optional.of(reasoned), correlation);
        append(unitOfWork, actor, AccountingAuditAction.REGULATORY_SUBMISSION_UNDELIVERED, row, Optional.of(reasoned), AuditOutcome.SUCCEEDED, summary(row) + ", attempts=" + row.attempts() + ", resends=" + row.resends(), correlation);
        publish(unitOfWork, UNDELIVERED_EVENT, row, base(row), correlation);
        return Acted.of(row, SubmissionStatus.UNDELIVERED, row.validationPassed(), false);
    }

    // ================================================================== the drive: the platform's

    /** Claims {@code id}'s drive when its permit has come: on {@code unitOfWork}, which the caller commits before any call; one attempt counted, the permit {@code lease} ahead. Empty when another holds it, its permit has not come, or it is concluded. */
    public Optional<Claimed> claim(Connection unitOfWork, RegulatorySubmissionId id, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.claim(unitOfWork, Objects.requireNonNull(id, "id"), positive(lease)).map(row -> claimed(unitOfWork, row));
    }

    /** The oldest due row claimed, as {@link #claim}: the inquiry leg's. */
    public Optional<Claimed> claimNextDue(Connection unitOfWork, Duration lease) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.claimNextDue(unitOfWork, positive(lease)).map(row -> claimed(unitOfWork, row));
    }

    private Claimed claimed(Connection unitOfWork, Row row) {
        return new Claimed(row, store.content(unitOfWork, row.id()).orElseThrow(() -> new IllegalStateException("a return's content is frozen from its birth")));
    }

    /**
     * {@code claimed} asked of the provider, on <strong>no connection</strong>: the first attempt sends, since no earlier call can have preceded it; every later one inquires, so a lost answer is asked for, never replaced.
     *
     * @throws RuntimeException whatever the adapter throws: nothing is recorded, the permit stands
     */
    public Answered ask(Claimed claimed) {
        Objects.requireNonNull(claimed, "claimed");
        RegulatoryReportingProvider adapter = adapterOf(claimed.row());
        if (claimed.row().attempts() == 1) {
            return new Answered(CallKind.SUBMIT, adapter.submit(request(claimed.row(), claimed.content())));
        }
        return new Answered(CallKind.INQUIRE, adapter.inquire(claimed.row().id()));
    }

    /**
     * One send again counted, on {@code unitOfWork}, which the caller commits before {@link #resend}: conditional on the attempt the caller holds, a definite "not received" being the last answer, and the bound. {@code last_answer} moves to {@code RESENDING}, so no person
     * can conclude the return undelivered over a send in flight.
     *
     * @return {@code false} when overtaken or the resends are spent: nothing may be sent
     */
    public boolean permitResend(Connection unitOfWork, Claimed claimed) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        return store.permitResend(unitOfWork, claimed.row().id(), claimed.row().attempts(), MAX_RESENDS);
    }

    /**
     * Sends again under the same submission id, on <strong>no connection</strong>: only after {@link #permitResend} committed. The provider deduplicates on the id, so this is the first send, not a second return.
     *
     * @throws RuntimeException whatever the adapter throws: nothing recorded, and the next claim inquires
     */
    public Answered resend(Claimed claimed) {
        Objects.requireNonNull(claimed, "claimed");
        return new Answered(CallKind.RESEND, adapterOf(claimed.row()).submit(request(claimed.row(), claimed.content())));
    }

    /**
     * Records {@code answered}, whatever it says, verbatim. While the row is {@code SUBMITTED}: the first definite answer takes the one-way edge, audited as the platform, whichever attempt it came from; an answer that decides nothing keeps the row, its permit
     * {@code inquiryInterval} ahead - conditional on the claimed attempt, so an overtaken worker moves no permit. Once it is not: the answer is kept, flagged {@link Flag#LATE} or, disagreeing, {@link Flag#CONTRARY}, and is never an edge.
     */
    public Recorded record(Connection unitOfWork, Claimed claimed, Answered answered, Duration inquiryInterval, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(claimed, "claimed");
        Objects.requireNonNull(answered, "answered");
        Objects.requireNonNull(correlation, "correlation");
        Actor platform = SecurityContext.require();
        if (platform.type() != ActorType.SYSTEM) {
            throw new IllegalStateException("a provider's answer is recorded by the platform, never a person");
        }
        RegulatoryAnswerReading reading = RegulatoryAnswerReading.of(answered.call(), answered.answer());
        Row row = store.lock(unitOfWork, claimed.row().id()).orElseThrow(() -> new IllegalStateException("a claimed return is never deleted"));
        boolean anomalous = reading.anomaly().isPresent();
        Optional<Flag> flag = flagOf(row, reading);
        store.appendAnswer(unitOfWork, new RegulatorySubmissionStore.NewAnswer(ids.next(), row.id(), claimed.row().attempts(), reading, flag, correlation));
        if (flag.isPresent()) {
            if (flag.get() == Flag.CONTRARY) {
                append(unitOfWork, platform, AccountingAuditAction.REGULATORY_SUBMISSION_CONTRARY_ANSWER, row, Optional.empty(), AuditOutcome.FAILED,
                        summary(row) + ", status=" + row.status().name() + ", read=" + reading.read().name(), correlation);
                return new Recorded(Disposition.CONTRARY, anomalous);
            }
            return new Recorded(Disposition.LATE, anomalous);
        }
        return switch (reading.read()) {
            case ACKNOWLEDGED -> acknowledged(unitOfWork, row, reading, platform, correlation);
            case REJECTED -> rejected(unitOfWork, row, reading, platform, correlation);
            case UNKNOWN -> {
                store.await(unitOfWork, row.id(), claimed.row().attempts(), positive(inquiryInterval), Outcome.UNKNOWN.name(), reading.providerReference());
                yield new Recorded(Disposition.AWAITING, anomalous);
            }
            case NOT_RECEIVED -> {
                store.await(unitOfWork, row.id(), claimed.row().attempts(), positive(inquiryInterval), Outcome.NOT_RECEIVED.name(), Optional.empty());
                yield new Recorded(Disposition.NOT_RECEIVED, anomalous);
            }
        };
    }

    private Recorded acknowledged(Connection unitOfWork, Row row, RegulatoryAnswerReading reading, Actor platform, CorrelationId correlation) {
        if (!store.markAcknowledged(unitOfWork, row.id(), reading.providerReference())) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.ACKNOWLEDGED, Optional.of(SubmissionStatus.SUBMITTED), SubmissionStatus.ACKNOWLEDGED, platform, Optional.empty(), Optional.empty(), correlation);
        append(unitOfWork, platform, AccountingAuditAction.REGULATORY_SUBMISSION_ACKNOWLEDGED, row, Optional.empty(), AuditOutcome.SUCCEEDED, summary(row) + ", providerReference=" + reading.providerReference().orElseThrow(), correlation);
        publish(unitOfWork, ACKNOWLEDGED_EVENT, row, base(row), correlation);
        return new Recorded(Disposition.ACKNOWLEDGED, false);
    }

    private Recorded rejected(Connection unitOfWork, Row row, RegulatoryAnswerReading reading, Actor platform, CorrelationId correlation) {
        String code = reading.rejectionCode().orElseThrow();
        if (!store.markRejected(unitOfWork, row.id(), reading.providerReference(), code)) {
            throw new IllegalStateException("the locked return moved under another writer: the FOR UPDATE protocol was bypassed");
        }
        history(unitOfWork, row, Act.REJECTED, Optional.of(SubmissionStatus.SUBMITTED), SubmissionStatus.REJECTED, platform, Optional.empty(), Optional.empty(), correlation);
        append(unitOfWork, platform, AccountingAuditAction.REGULATORY_SUBMISSION_REJECTED, row, Optional.empty(), AuditOutcome.SUCCEEDED, summary(row) + ", rejection=" + code, correlation);
        publish(unitOfWork, REJECTED_EVENT, row, base(row).with("rejectionCode", code), correlation);
        return new Recorded(Disposition.REJECTED, false);
    }

    /** Past the row's conclusion: an unknown answer or one agreeing is late; one saying otherwise is contrary. While {@code SUBMITTED}: none. */
    private static Optional<Flag> flagOf(Row row, RegulatoryAnswerReading reading) {
        if (row.status() == SubmissionStatus.SUBMITTED) {
            return Optional.empty();
        }
        boolean agrees = switch (reading.read()) {
            case UNKNOWN -> true;
            case ACKNOWLEDGED -> row.status() == SubmissionStatus.ACKNOWLEDGED;
            case REJECTED -> row.status() == SubmissionStatus.REJECTED;
            case NOT_RECEIVED -> row.status() == SubmissionStatus.UNDELIVERED;
        };
        return Optional.of(agrees ? Flag.LATE : Flag.CONTRARY);
    }

    // ================================================================== reads

    /**
     * What a reader reads: the row, the obligation version it pinned, every line followed back to its run's figure through bytes proved, the validation's outcome, the provider's answers, and whether it stands. No lock: an act judges again under its own.
     *
     * @throws SubmissionNotFound no such submission
     */
    public View view(Connection unitOfWork, RegulatorySubmissionId id) {
        Row row = store.find(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        RegulatoryObligationStore.Row obligation = obligations.find(unitOfWork, row.obligation()).orElseThrow(() -> new IllegalStateException("an obligation is never deleted"));
        Map<UUID, Optional<ReportRunStore.RunRow>> runRows = new HashMap<>();
        Map<UUID, Optional<CanonicalJson.Obj>> contents = new HashMap<>();
        Optional<BlockedBy> blockedBy = Optional.empty();
        if (row.periodRevisionNow() != row.periodRevision() || row.periodStatusNow() != PeriodStatus.CLOSED) {
            blockedBy = Optional.of(BlockedBy.MONTH_MOVED);
        } else if (obligation.status() != RegulatoryObligationStatus.ACTIVE) {
            blockedBy = Optional.of(BlockedBy.OBLIGATION_RETIRED);
        }
        for (RegulatorySubmissionStore.Source source : store.sources(unitOfWork, id)) {
            UUID run = source.run().value();
            Optional<ReportRunStore.RunRow> runRow = runs.find(unitOfWork, source.run());
            runRows.put(run, runRow);
            Optional<CanonicalJson.Obj> content = readProved(unitOfWork, source, runRow);
            contents.put(run, content);
            if (content.isEmpty()) {
                blockedBy = Optional.of(BlockedBy.RUN_BYTES_DIFFER);
            } else if (blockedBy.isEmpty() && runRow.filter(held -> held.status() == RunStatus.FINAL && held.periodRevision() == row.periodRevision()).isEmpty()) {
                blockedBy = Optional.of(BlockedBy.RUN_SUPERSEDED);
            }
        }
        List<LineView> lines = new ArrayList<>();
        for (RegulatoryFormLine line : store.lines(unitOfWork, id)) {
            lines.add(lineView(line, runRows.getOrDefault(line.run().value(), Optional.empty()), contents.getOrDefault(line.run().value(), Optional.empty())));
        }
        Optional<RegulatoryValidation> validation = row.validationOutcome().map(RegulatoryValidation::parse);
        boolean exhausted = row.status() == SubmissionStatus.SUBMITTED && row.lastAnswer().filter(Outcome.NOT_RECEIVED.name()::equals).isPresent() && row.resends() >= MAX_RESENDS;
        return new View(row, obligation, lines, validation, store.answers(unitOfWork, id), blockedBy, exhausted);
    }

    private static LineView lineView(RegulatoryFormLine line, Optional<ReportRunStore.RunRow> run, Optional<CanonicalJson.Obj> content) {
        Optional<RunStatus> status = run.map(ReportRunStore.RunRow::status);
        if (content.isEmpty()) {
            return new LineView(line, status, Optional.empty(), false);
        }
        List<FigurePath.Figure> found;
        try {
            found = new FigurePath(line.figure()).resolve(content.get());
        } catch (FigurePath.Unresolved | FigurePath.Malformed notFound) {
            return new LineView(line, status, Optional.empty(), false);
        }
        if (found.size() != 1) {
            return new LineView(line, status, Optional.empty(), false);
        }
        FigurePath.Figure figure = found.get(0);
        return new LineView(line, status, Optional.of(figure), figure.currency().equals(line.currency()) && figure.minor().equals(line.minor()) && figure.scale() == line.scale());
    }

    /** {@code id} as it stands now - the receipt of an act or a drive; no lock, no audit. */
    public Optional<Row> current(Connection unitOfWork, RegulatorySubmissionId id) {
        return store.find(unitOfWork, Objects.requireNonNull(id, "id"));
    }

    /** What a person must act on or know of, oldest first. */
    public List<Row> standing(Connection unitOfWork) {
        return store.standing(unitOfWork, LIST_LIMIT);
    }

    /** Those waiting on a decision {@code reader} could make: never their own - excluded in the statement, before its limit. Fail closed: only a person has such a list. */
    public List<Row> awaitingApproval(Connection unitOfWork, Actor reader) {
        person(reader);
        return store.awaitingApproval(unitOfWork, reader.id(), LIST_LIMIT);
    }

    /**
     * The document its adapter would send, for a person to read. Local: never a call.
     *
     * @throws SubmissionNotFound no such submission
     * @throws SubmissionStale this build holds no adapter for it
     */
    public RegulatoryReportingProvider.ReferenceExport export(Connection unitOfWork, RegulatorySubmissionId id) {
        Row row = store.find(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        RegulatoryReportingProvider adapter = adapters.named(row.adapter()).orElseThrow(() -> new SubmissionStale("this build holds no adapter for the return"));
        return adapter.export(request(row, store.content(unitOfWork, id).orElseThrow(() -> new IllegalStateException("a return's content is frozen from its birth"))));
    }

    /** Records an operator's read ({@code regulatory.SubmissionRead}) - before it is served, and never a figure. */
    public void recordRead(Connection unitOfWork, String target, String summary, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.REGULATORY_SUBMISSION_READ, TARGET_TYPE, target, Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of(summary)));
    }

    /** Records a refused self-approval or self-sending {@code FAILED} on {@code unitOfWork}: the caller's own transaction, after the act rolled back. */
    public void recordRefusal(Connection unitOfWork, SelfApprovalRefused refused, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), AccountingAuditAction.SELF_APPROVAL_REFUSED, TARGET_TYPE, refused.target(), Optional.empty(), AuditOutcome.FAILED, correlation,
                Optional.of(refused.summary())));
    }

    /** What stands, for {@code finapp.accounting.regulatory.attention}. */
    public RegulatorySubmissionStore.Attention attention(Connection connection) {
        return store.attention(connection, MAX_RESENDS);
    }

    // ================================================================== plumbing

    private record Held(AccountingPeriodStore.PeriodRow period, RegulatoryObligationStore.Row obligation, Row row) {}

    /** The locks every act going forward takes, in order: (A1) the period {@code FOR SHARE}, the obligation {@code FOR SHARE}, (A4) the row {@code FOR UPDATE}. The unlocked read only names what is frozen from birth. */
    private Held held(Connection unitOfWork, RegulatorySubmissionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Row named = store.find(unitOfWork, Objects.requireNonNull(id, "id")).orElseThrow(SubmissionNotFound::new);
        AccountingPeriodStore.PeriodRow period = periods.lockShared(unitOfWork, named.month()).orElseThrow(() -> new IllegalStateException("a period is never deleted"));
        RegulatoryObligationStore.Row obligation = obligations.lockShared(unitOfWork, named.obligation()).orElseThrow(() -> new IllegalStateException("an obligation is never deleted"));
        Row row = store.lock(unitOfWork, id).orElseThrow(SubmissionNotFound::new);
        return new Held(period, obligation, row);
    }

    /**
     * The return still stands on the books it read, judged under {@link #held}'s locks: the month {@code CLOSED} at the revision pinned, the version in force, every run {@code FINAL} of that month and revision, id and hash as pinned, its bytes proved.
     *
     * @throws SubmissionStale any of them moved
     * @throws RunBytesDiffer a run's bytes do not prove
     */
    private void stands(Connection unitOfWork, Held held) {
        Row row = held.row();
        if (held.period().status() != PeriodStatus.CLOSED || held.period().revision() != row.periodRevision()) {
            throw new SubmissionStale(row.month() + " moved: it is " + held.period().status().name() + " at revision " + held.period().revision() + ", and this return read revision " + row.periodRevision());
        }
        if (held.obligation().status() != RegulatoryObligationStatus.ACTIVE) {
            throw new SubmissionStale("the obligation version it pinned is " + held.obligation().status().name());
        }
        List<RegulatorySubmissionStore.Source> sources = store.sources(unitOfWork, row.id());
        if (sources.isEmpty()) {
            throw new IllegalStateException("a return is born with its sources");
        }
        for (RegulatorySubmissionStore.Source source : sources) {
            Optional<ReportRunStore.RunRow> run = runs.find(unitOfWork, source.run());
            if (run.filter(read -> read.status() == RunStatus.FINAL && read.basis() == RunBasis.FINAL && read.periodId().equals(row.period()) && read.periodRevision() == row.periodRevision()).isEmpty()) {
                throw new SubmissionStale("the run of " + source.definition() + " it read no longer stands FINAL at the revision it read");
            }
            if (readProved(unitOfWork, source, run).isEmpty()) {
                throw new RunBytesDiffer(source.definition());
            }
        }
    }

    /** The run's content parsed, when its bytes prove against the stored hash and the hash this return named. */
    private Optional<CanonicalJson.Obj> readProved(Connection unitOfWork, RegulatorySubmissionStore.Source source, Optional<ReportRunStore.RunRow> run) {
        Optional<ReportRunStore.Content> content = runs.content(unitOfWork, source.run());
        if (run.isEmpty() || content.isEmpty() || !source.runSha256().equals(content.get().sha256()) || !source.runSha256().equals(run.get().contentSha256().orElse(null))
                || !source.runSha256().equals(CanonicalJson.sha256(content.get().text()))) {
            return Optional.empty();
        }
        try {
            return Optional.of(RunContentParser.object(content.get().text()));
        } catch (RunContentParser.NotCanonical notCanonical) {
            return Optional.empty();
        }
    }

    /** The source's FINAL run of the month at its present revision, its bytes proved. Plain parameters only. */
    private RegulatoryForm.SourceRun proved(Connection unitOfWork, RegulatoryObligationConfiguration.Source source, AccountingPeriodStore.PeriodRow period) {
        ReportRunStore.RunKey key = new ReportRunStore.RunKey(source.definition(), source.version(), period.id(), RunParameters.none().hash());
        ReportRunStore.RunRow run = runs.latest(unitOfWork, key)
                .filter(found -> found.status() == RunStatus.FINAL && found.basis() == RunBasis.FINAL && found.periodRevision() == period.revision())
                .orElseThrow(() -> new SourceNotFinal("no FINAL run of " + source.definition() + " version " + source.version() + " is issued for " + period.month() + " at revision " + period.revision()
                        + "; an accountant issues it"));
        String stated = run.contentSha256().orElseThrow(() -> new IllegalStateException("a FINAL run holds its hash"));
        Optional<CanonicalJson.Obj> content = readProved(unitOfWork, new RegulatorySubmissionStore.Source(run.id(), stated, source.definition(), source.version()), Optional.of(run));
        return new RegulatoryForm.SourceRun(source.definition(), source.version(), run.id(), stated, content.orElseThrow(() -> new RunBytesDiffer(source.definition())));
    }

    /** A plain draft names what was sent, or finds nothing was; a correction names a concluded return of its form and month with none standing. The original {@code FOR UPDATE}, so two corrections serialise. */
    private void judgeChain(Connection unitOfWork, Draft request, RegulatoryObligationStore.Row obligation, AccountingPeriodStore.PeriodRow period) {
        if (request.corrects().isEmpty()) {
            if (store.anySent(unitOfWork, obligation.jurisdiction(), obligation.form(), period.id())) {
                throw new SubmissionLive("a return of this form and month was sent; a later one is a correction naming it");
            }
            return;
        }
        RegulatorySubmissionId named = request.corrects().get();
        Row original = store.lock(unitOfWork, named).orElseThrow(() -> new SubmissionInvalid("no regulatory submission answers the correction"));
        if (!original.jurisdiction().equals(obligation.jurisdiction()) || !original.form().equals(obligation.form()) || !original.period().equals(period.id())) {
            throw new SubmissionInvalid("a correction references a return of its own form and month");
        }
        if (!original.status().correctable()) {
            throw new SubmissionInvalid("a correction references a concluded return that reached for the provider, and this one is " + original.status().name());
        }
        if (store.correctionOf(unitOfWork, named).isPresent()) {
            throw new SubmissionLive("this return already has a correction standing; a later one corrects that");
        }
    }

    /** What an act names is what stands. */
    private static void named(Row row, Attested read) {
        if (!row.contentSha256().equals(read.contentSha256())) {
            throw new SubmissionStale("the content named is not the content that stands");
        }
        if (row.periodRevision() != read.periodRevision()) {
            throw new SubmissionStale("the revision named is not the revision this return read");
        }
    }

    private RegulatoryReportingProvider adapterOf(Row row) {
        return adapters.named(row.adapter()).orElseThrow(() -> new IllegalStateException("this build holds no adapter for the return"));
    }

    private static RegulatoryReportingProvider.Request request(Row row, String content) {
        return new RegulatoryReportingProvider.Request(row.id(), row.jurisdiction(), row.form(), row.formVersion(), row.month().toString(), content, row.contentSha256());
    }

    private static Duration positive(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("a permit moves a positive interval ahead");
        }
        return duration;
    }

    private static String summary(Row row) {
        return "jurisdiction=" + row.jurisdiction() + ", form=" + row.form() + ", formVersion=" + row.formVersion() + ", period=" + row.month() + ", revision=" + row.periodRevision();
    }

    private static EventPayload base(Row row) {
        return EventPayload.of()
                .with("obligationId", row.obligation().value().toString())
                .with("jurisdiction", row.jurisdiction())
                .with("form", row.form())
                .with("formVersion", Integer.toString(row.formVersion()))
                .with("period", row.month().toString())
                .with("periodRevision", Integer.toString(row.periodRevision()));
    }

    private void publish(Connection unitOfWork, String type, Row row, EventPayload payload, CorrelationId correlation) {
        outbox.write(unitOfWork, new EventEnvelope(EventId.next(ids), type, EVENT_VERSION, EventEnvelope.CURRENT_SCHEMA_VERSION, row.id(), TARGET_TYPE, clock.instant(), PRODUCER, correlation,
                CausationId.of(correlation.value())), payload.toBytes(), EventPayload.MEDIA_TYPE);
    }

    private void history(Connection unitOfWork, Row row, Act act, Optional<SubmissionStatus> from, SubmissionStatus to, Actor actor, Optional<String> preparedBy, Optional<String> reason, CorrelationId correlation) {
        store.appendEvent(unitOfWork, new RegulatorySubmissionStore.EventRow(ids.next(), row.id(), act, from, to, actor, preparedBy, reason, correlation));
    }

    private void append(Connection unitOfWork, Actor actor, AccountingAuditAction action, Row row, Optional<String> reason, AuditOutcome outcome, String summary, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE, row.id().value().toString(), reason, outcome, correlation, Optional.of(summary)));
    }

    /** An authenticated human: {@code CUSTOMER}-typed (every operator session) or {@code EMPLOYEE}; fail closed. */
    private static void person(Actor actor) {
        Objects.requireNonNull(actor, "actor");
        if (actor.type() != ActorType.CUSTOMER && actor.type() != ActorType.EMPLOYEE) {
            throw new IllegalArgumentException("a regulatory submission's act is a person's - never the platform's, a service's or a merchant's");
        }
    }
}
