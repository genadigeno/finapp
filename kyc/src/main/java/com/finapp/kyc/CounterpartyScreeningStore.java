package com.finapp.kyc;

import com.finapp.kyc.CounterpartyScreeningVocabulary.EntityType;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.sharedkernel.money.CountryCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where counterparty screenings rest (`P9-TSK-016`, {@code kyc V009}). Every write is a conditional
 * the database arbitrates - the request on its unique reference, a decision on the expected status
 * under the row lock, an attempt on its primary key, a claim on {@code FOR UPDATE SKIP LOCKED} - so
 * ten instances writing one screening produce one effect.
 */
public interface CounterpartyScreeningStore {

    /** A screening as stored; the subject stays encrypted until the service needs the name. */
    record Row(
            CounterpartyScreeningId id,
            String requestReference,
            CounterpartySubjectCipher.Encrypted subject,
            CountryCode country,
            EntityType entityType,
            PayeeVerdict payeeVerdict,
            CounterpartyScreeningStatus status,
            Optional<ReviewReason> reviewReason,
            Optional<DecisionBasis> basis,
            Optional<String> policyVersion,
            Optional<Instant> decidedAt,
            Optional<String> decidedBy,
            Optional<ReasonCode> reasonCode,
            int attempts,
            Optional<Instant> nextAttemptAt,
            Instant requestedAt) {
        public Row {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(requestReference, "requestReference must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(payeeVerdict, "payeeVerdict must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(reviewReason, "reviewReason must not be null");
            Objects.requireNonNull(basis, "basis must not be null");
            Objects.requireNonNull(policyVersion, "policyVersion must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        }
    }

    /** A new screening, born {@code REQUESTED} and due at {@code dueAt}. */
    record NewScreening(
            CounterpartyScreeningId id,
            String requestReference,
            CounterpartySubjectCipher.Encrypted subject,
            CountryCode country,
            EntityType entityType,
            PayeeVerdict payeeVerdict,
            Instant requestedAt,
            Instant dueAt) {
        public NewScreening {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(requestReference, "requestReference must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(country, "country must not be null");
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(payeeVerdict, "payeeVerdict must not be null");
            Objects.requireNonNull(requestedAt, "requestedAt must not be null");
            Objects.requireNonNull(dueAt, "dueAt must not be null");
        }
    }

    /** An automatic outcome: the provider's verdict as kyc decided it. */
    record AutomaticOutcome(
            CounterpartyScreeningStatus status,
            Optional<ReviewReason> reviewReason,
            int attempts,
            String policyVersion,
            Instant decidedAt,
            Optional<Instant> nextAttemptAt) {
        public AutomaticOutcome {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(reviewReason, "reviewReason must not be null");
            Objects.requireNonNull(policyVersion, "policyVersion must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(nextAttemptAt, "nextAttemptAt must not be null");
        }
    }

    /** A person's outcome on a screening in review. */
    record ReviewerOutcome(
            CounterpartyScreeningStatus status,
            String decidedBy,
            ReasonCode reasonCode,
            String narrative,
            String policyVersion,
            Instant decidedAt) {
        public ReviewerOutcome {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null");
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
            Objects.requireNonNull(narrative, "narrative must not be null");
            Objects.requireNonNull(policyVersion, "policyVersion must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        }

        @Override
        public String toString() {
            return "ReviewerOutcome[" + status + ", " + reasonCode + "]";
        }
    }

    /** One provider attempt; the evidence, when an answer arrived, already sealed. */
    record Attempt(
            CounterpartyScreeningId screening,
            int attempt,
            Verdict verdict,
            Optional<CounterpartySubjectCipher.Encrypted> evidence,
            Optional<byte[]> checksum,
            int evidenceLength,
            Instant answeredAt) {
        public Attempt {
            Objects.requireNonNull(screening, "screening must not be null");
            Objects.requireNonNull(verdict, "verdict must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
            Objects.requireNonNull(checksum, "checksum must not be null");
            Objects.requireNonNull(answeredAt, "answeredAt must not be null");
            if (evidence.isPresent() != checksum.isPresent()) {
                throw new IllegalArgumentException("evidence and its checksum travel together");
            }
        }
    }

    /** How many screenings wait for a person, and since when the oldest has. */
    record ReviewBacklog(long pending, Optional<Instant> oldest) {}

    /** Inserts {@code fresh}; false when its request reference is already screened. */
    boolean insertRequested(Connection unitOfWork, NewScreening fresh);

    Optional<Row> byRequest(Connection unitOfWork, String requestReference);

    Optional<Row> find(Connection unitOfWork, CounterpartyScreeningId id);

    /** The row under {@code FOR UPDATE}; every decision is taken against it. */
    Optional<Row> lock(Connection unitOfWork, CounterpartyScreeningId id);

    /** Appends an attempt; the primary key refuses a second writer of the same number. */
    void insertAttempt(Connection unitOfWork, Attempt attempt);

    /** Moves the screening from {@code expected} to the automatic outcome; false when it was not there. */
    boolean decideAutomatically(
            Connection unitOfWork, CounterpartyScreeningId id, CounterpartyScreeningStatus expected, AutomaticOutcome outcome);

    /** Moves the screening from {@code IN_REVIEW} to the person's outcome; false when it was not there. */
    boolean decideByReviewer(Connection unitOfWork, CounterpartyScreeningId id, ReviewerOutcome outcome);

    /**
     * Claims at most {@code limit} due screenings in one statement, judged and moved on the database's
     * clock: each one's permit moves {@code permit} past {@code statement_timestamp()}, and rows another
     * sweeper holds are skipped, so concurrent sweepers claim disjoint sets and no instance's clock decides.
     */
    List<CounterpartyScreeningId> claimDue(Connection unitOfWork, java.time.Duration permit, int limit);

    ReviewBacklog reviewBacklog(Connection unitOfWork);
}
