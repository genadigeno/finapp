package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for regulatory submissions (`P14-TSK-018`, {@code accounting V010}): born by insert under the one-live unique; every edge a conditional {@code UPDATE} the machine trigger judges again for every
 * writer, stamping the database's clock; the drive's claim one statement, {@code FOR UPDATE SKIP LOCKED} on a permit that has come. No statement names {@code content} in a {@code SET}, and none is granted.
 */
public interface RegulatorySubmissionStore {

    /**
     * Births the row {@code DRAFT}: {@code false} when a live row holds its form and month, a return of them was sent and this names no correction, or another correction of the original stands - {@code
     * regulatory_submission_one_live}, the trigger's own refusal under the same SQLSTATE, {@code regulatory_submission_one_correction}.
     */
    boolean insert(Connection unitOfWork, NewSubmission submission);

    /** Appends a source of a row born in this transaction. */
    void insertSource(Connection unitOfWork, RegulatorySubmissionId id, Source source);

    /** Appends the lines of a row born in this transaction. */
    void insertLines(Connection unitOfWork, RegulatorySubmissionId id, List<RegulatoryFormLine> lines);

    /** The row, unlocked. */
    Optional<Row> find(Connection unitOfWork, RegulatorySubmissionId id);

    /** The row {@code FOR UPDATE}. */
    Optional<Row> lock(Connection unitOfWork, RegulatorySubmissionId id);

    /** The text a person attested - apart from the row, so a list never reads it. */
    Optional<String> content(Connection unitOfWork, RegulatorySubmissionId id);

    /** Whether a row of the form and month has reached {@code SUBMITTED} or past it. */
    boolean anySent(Connection unitOfWork, String jurisdiction, String form, AccountingPeriodId period);

    /** The correction of {@code original} that stands: one not refused or withdrawn. */
    Optional<RegulatorySubmissionId> correctionOf(Connection unitOfWork, RegulatorySubmissionId original);

    List<Source> sources(Connection unitOfWork, RegulatorySubmissionId id);

    List<RegulatoryFormLine> lines(Connection unitOfWork, RegulatorySubmissionId id);

    /** The newest {@code limit} kept answers, oldest of them first: by the order they were recorded in, never by a stamp (`P14-REM-009`). */
    List<AnswerRow> answers(Connection unitOfWork, RegulatorySubmissionId id, int limit);

    /** How many answers are kept. */
    long answerCount(Connection unitOfWork, RegulatorySubmissionId id);

    /** A validation recorded: {@code passed} moves it to {@code VALIDATED}, a failure leaves it {@code DRAFT}. {@code false} unless {@code DRAFT} with none recorded. */
    boolean markValidated(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String outcome, boolean passed);

    /** {@code VALIDATED -> APPROVED}, naming the content and revision read. */
    boolean markApproved(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, String contentSha256, int periodRevision);

    /** {@code VALIDATED | APPROVED -> REFUSED}. */
    boolean markRefused(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason);

    /** {@code DRAFT | VALIDATED | APPROVED -> WITHDRAWN}. */
    boolean markWithdrawn(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason);

    /** {@code APPROVED -> SUBMITTED}, the permit due at once and the horizon {@code horizon} ahead on the database's clock. */
    boolean markSubmitted(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, Duration horizon);

    /** {@code SUBMITTED -> ACKNOWLEDGED}, the reference kept once given. */
    boolean markAcknowledged(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference);

    /** {@code SUBMITTED -> REJECTED}. */
    boolean markRejected(Connection unitOfWork, RegulatorySubmissionId id, Optional<String> reference, String code);

    /** {@code SUBMITTED -> UNDELIVERED}: only on a definite "not received", the resends spent, the horizon past on the database's clock, or the month's revision moved. */
    boolean markUndelivered(Connection unitOfWork, RegulatorySubmissionId id, Actor actor, String reason, int maxResends);

    /** Claims the row's drive when its permit has come: {@code SKIP LOCKED}, one attempt counted, the permit {@code lease} ahead. */
    Optional<Row> claim(Connection unitOfWork, RegulatorySubmissionId id, Duration lease);

    /** The oldest due row claimed, as {@link #claim}. */
    Optional<Row> claimNextDue(Connection unitOfWork, Duration lease);

    /** One resend counted, before its call: conditional on the claimed attempt, a definite "not received" last, the month's revision being the one read, and the bound; {@code last_answer} becomes {@code RESENDING}. */
    boolean permitResend(Connection unitOfWork, RegulatorySubmissionId id, int attempt, int maxResends);

    /**
     * An answer that decides nothing: the row stays {@code SUBMITTED}, the last answer named, the permit ahead on the database's clock: {@code interval} up to the horizon; past it the time since the horizon, so the gaps double; never more than {@code ceiling}. Conditional on the
     * claimed attempt.
     */
    boolean await(Connection unitOfWork, RegulatorySubmissionId id, int attempt, Duration interval, Duration ceiling, String lastAnswer, Optional<String> reference);

    void appendAnswer(Connection unitOfWork, NewAnswer answer);

    void appendEvent(Connection unitOfWork, EventRow event);

    /** What a person must act on or know of: everything live, a return rejected or undelivered with no correction, one sent or acknowledged and overtaken ({@link Row#overtaken}), and any holding a contrary answer. Oldest first by the order the rows were recorded in, from after {@code after}, a keyset on what is frozen (`P14-REM-009`). */
    List<Row> standing(Connection unitOfWork, Optional<RegulatorySubmissionId> after, int limit);

    /** Those {@code VALIDATED} and prepared by anyone but {@code reader}, oldest first: the filter is the statement's, before its limit. */
    List<Row> awaitingApproval(Connection unitOfWork, String reader, int limit);

    /** The counts behind {@code finapp.accounting.regulatory.attention}, on the database's clock, one statement. */
    Attention attention(Connection connection, int maxResends);

    // ------------------------------------------------------------------ rows

    /** The history's acts - each on its edge ({@code regulatory_submission_event_act_is_an_edge}). */
    enum Act {
        DRAFTED,
        VALIDATION_FAILED,
        VALIDATED,
        APPROVED,
        REFUSED,
        WITHDRAWN,
        SUBMITTED,
        ACKNOWLEDGED,
        REJECTED,
        UNDELIVERED
    }

    /** A late or contrary answer: kept, flagged, never an edge. */
    enum Flag { LATE, CONTRARY }

    /** A row to birth. */
    record NewSubmission(RegulatorySubmissionId id, RegulatoryObligationId obligation, String jurisdiction, String form, int formVersion, AccountingPeriodId period, int periodRevision,
            Optional<RegulatorySubmissionId> corrects, String content, String contentSha256, Actor preparedBy, String reason, CorrelationId correlation) {

        public NewSubmission {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(corrects, "corrects");
        }

        /** Figures and the reason stay out of a log line. */
        @Override
        public String toString() {
            return "NewSubmission[" + id.value() + ", " + form + "/" + formVersion + "]";
        }
    }

    /** A source run of a submission. */
    record Source(ReportRunId run, String runSha256, String definition, int definitionVersion) {}

    /**
     * A submission's row, without its content.
     *
     * @param pastHorizon whether its answer horizon is past on the database's clock, as read
     * @param periodRevisionNow the month's revision as read: for a reader. An act judges under the period row's lock, never by this
     * @param correctedBy as read: for a reader. A draft judges {@link #correctionOf} under the original's lock, never by this
     * @param overtaken as read: sent or acknowledged, its month's revision moved, and no acknowledged return of its form stands at the month's present revision. The regulator holds, or may hold, a figure the books no longer say. A draft or a rejected correction does not clear it
     */
    record Row(RegulatorySubmissionId id, RegulatoryObligationId obligation, String jurisdiction, String form, int formVersion, String adapter, AccountingPeriodId period, YearMonth month, int periodRevision,
            int periodRevisionNow, PeriodStatus periodStatusNow, Optional<RegulatorySubmissionId> corrects, Optional<RegulatorySubmissionId> correctedBy, String contentSha256, SubmissionStatus status,
            Optional<String> validationOutcome, Optional<Boolean> validationPassed, Optional<String> validatedBy, Optional<Instant> validatedAt, String preparedBy, Instant preparedAt, String preparationReason,
            Optional<String> approvedBy, Optional<Instant> approvedAt, Optional<String> submittedBy, Optional<Instant> submittedAt, Optional<String> closedBy, Optional<Instant> closedAt, int attempts,
            int resends, Optional<Instant> nextInquiryAt, Optional<Instant> answerHorizonAt, boolean pastHorizon, Optional<String> lastAnswer, Optional<String> providerReference,
            Optional<String> rejectionCode, Optional<Instant> concludedAt, long contraryAnswers, boolean overtaken, CorrelationId correlation) {

        /** The month's revision moved since this was drawn: it may read {@code CLOSED} again and still not be the month it read. */
        public boolean monthMoved() {
            return periodRevisionNow != periodRevision;
        }

        /** The reason and the outcome stay out of a log line. */
        @Override
        public String toString() {
            return "Row[" + id.value() + ", " + form + "/" + formVersion + ", " + month + "#" + periodRevision + ", " + status + "]";
        }
    }

    /** An answer to keep. */
    record NewAnswer(UUID id, RegulatorySubmissionId submission, int attempt, RegulatoryAnswerReading reading, Optional<Flag> flag, CorrelationId correlation) {}

    /** A kept answer. */
    record AnswerRow(UUID id, int attempt, RegulatoryReportingProvider.CallKind call, RegulatoryReportingProvider.Outcome stated, RegulatoryReportingProvider.Outcome read,
            Optional<String> providerReference, Optional<String> rejectionCode, Optional<RegulatoryAnswerReading.Anomaly> anomaly, Optional<Flag> flag, byte[] raw, String rawSha256, long receivedBytes,
            boolean truncated, Instant receivedAt) {

        public AnswerRow {
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }

        /** The provider's text is never logged. */
        @Override
        public String toString() {
            return "AnswerRow[" + id + ", " + call + ", read " + read + ", " + receivedBytes + " bytes]";
        }
    }

    /** One history row. {@code preparedBy} on an approval only. */
    record EventRow(UUID id, RegulatorySubmissionId submission, Act act, Optional<SubmissionStatus> from, SubmissionStatus to, Actor actor, Optional<String> preparedBy, Optional<String> reason, CorrelationId correlation) {

        /** The reason stays out of a log line. */
        @Override
        public String toString() {
            return "EventRow[" + submission.value() + ", " + act + "]";
        }
    }

    /**
     * What stands needing someone's eye.
     *
     * @param overdue {@code SUBMITTED} past its answer horizon
     * @param resendsExhausted {@code SUBMITTED}, a definite "not received" last and no resend left: a person's door is open
     * @param overtaken {@link Row#overtaken}: it clears only once an acknowledged return of that form stands at the month's present revision
     * @param staleUnsent not yet sent, its month's revision moved: withdrawn or refused is its way out
     */
    record Attention(long overdue, long resendsExhausted, long overtaken, long staleUnsent) {}
}
