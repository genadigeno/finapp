package com.finapp.kyc;

import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Counterparty screening (`P9-TSK-016`, ADR-0081; {@code INV-KYC-01}, {@code INV-KYC-04},
 * {@code INV-KYC-05}): kyc screens a payee abroad and <strong>decides</strong> - the provider's verdict
 * is evidence, never the outcome. A hit, an indeterminate answer, or a provider {@code CLEAR} beside a
 * payee check that did not {@code MATCH} always waits for a person ({@link #route}); a provider that
 * cannot be asked is {@code UNAVAILABLE}, retried, and nothing is cleared.
 *
 * <h2>Three transactions, never a connection across the call</h2>
 *
 * <p>The request is born {@code REQUESTED}, idempotent on its reference, holding a permit
 * ({@link #IN_FLIGHT_PERMIT}) so the retry sweeper leaves the caller's own call alone. The provider is
 * asked with no connection held. The decision (T-e) locks the row and decides only from an unanswered
 * status - the attempt, its sealed evidence, the transition, the reasoned audit record, the outbox
 * event {@code kyc.CounterpartyScreeningDecided} and the {@link ScreeningOutcomeListener} all commit
 * together or not at all. A crash between the call and T-e leaves the row due; the sweeper asks again.
 *
 * <h2>The person's decision</h2>
 *
 * <p>{@link #review} moves {@code IN_REVIEW -> RELEASED | BLOCKED} under the same lock, with a reason
 * code that must justify the decision and a narrative screened like every person-written reason; the
 * same person's retry converges, anyone else's second decision is refused.
 */
@RequiredArgsConstructor
public final class CounterpartyScreenings {

    /** The event every decision publishes - identifiers and the outcome, never the name. */
    public static final String DECIDED_EVENT = "kyc.CounterpartyScreeningDecided";

    static final String TARGET_TYPE = "kyc_counterparty_screening";
    static final String PRODUCER = "kyc";
    static final int EVENT_VERSION = 1;

    /** How long a caller's own call holds the screening before the sweeper may ask again. */
    public static final Duration IN_FLIGHT_PERMIT = Duration.ofMinutes(2);

    /** The first retry's wait after an unavailable answer; it doubles, up to {@link #MAX_BACKOFF}. */
    static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);

    static final Duration MAX_BACKOFF = Duration.ofHours(1);

    /** A narrative's bound - the audit record's reason bound. */
    public static final int MAX_NARRATIVE = AuditRecord.MAX_REASON_LENGTH;

    @NonNull private final CounterpartyScreeningStore store;
    @NonNull private final CounterpartySubjectCipher cipher;
    @NonNull private final CounterpartyScreeningProvider provider;
    @NonNull private final ScreeningOutcomeListener listener;
    @NonNull private final CounterpartyScreeningObserver observer;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ requests and outcomes

    /**
     * A screening request - the caller's reference, the counterparty, its payee check, and the actor who asked for it:
     * the one person who may never decide its review (the Phase 9 to 10 transition gate; INV-AUD-04, {@code kyc V010}).
     */
    public record Request(
            String requestReference, CounterpartySubject subject, PayeeVerdict payeeVerdict, String requestedBy) {
        public Request {
            Objects.requireNonNull(requestReference, "requestReference must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(payeeVerdict, "payeeVerdict must not be null");
            Objects.requireNonNull(requestedBy, "requestedBy must not be null");
            if (requestReference.isBlank() || requestReference.length() > 200) {
                throw new IllegalArgumentException("a request reference is 1..200 characters");
            }
            if (requestedBy.isBlank() || requestedBy.length() > 200) {
                throw new IllegalArgumentException("a requester is 1..200 characters");
            }
        }
    }

    /** Where a screening stands; {@code replayed} when this call found it rather than decided it. */
    public record Screening(
            CounterpartyScreeningId id,
            String requestReference,
            CounterpartyScreeningStatus status,
            Optional<ReviewReason> reviewReason,
            Optional<Instant> decidedAt,
            boolean replayed) {}

    /** A screening's clearance: whether it clears, and since when - the caller judges currency. */
    public record Clearance(CounterpartyScreeningId id, CounterpartyScreeningStatus status, Optional<Instant> decidedAt) {
        public boolean clears() {
            return status.clears();
        }
    }

    /** What kyc decides from a provider verdict and the payee check (the pure rule). */
    public record Routed(CounterpartyScreeningStatus status, Optional<ReviewReason> reviewReason) {}

    // ------------------------------------------------------------------ refusals

    /** No screening has this id. */
    public static final class ScreeningNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ScreeningNotFound() {
            super("no counterparty screening has this identifier");
        }
    }

    /** A review of a screening that is not waiting for one. */
    public static final class ScreeningNotInReview extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ScreeningNotInReview(CounterpartyScreeningStatus status) {
            super("the counterparty screening is not in review: it is " + status.name());
        }
    }

    /** A malformed review - the message names the defect. */
    public static final class ScreeningReviewInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public ScreeningReviewInvalid(String defect) {
            super(defect);
        }
    }

    /** A request reference already screened for a different counterparty. */
    public static final class RequestConflict extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        RequestConflict() {
            super("this request reference was already screened for a different counterparty");
        }
    }

    // ------------------------------------------------------------------ the rule

    /**
     * kyc's decision from the provider's verdict and the payee check ({@code INV-KYC-04} extended): only
     * a provider {@code CLEAR} beside a payee {@code MATCH} clears; a {@code CLEAR} beside an unverified
     * payee, a hit and an indeterminate answer each wait for a person; an unavailable provider decides
     * nothing but the retry.
     */
    public static Routed route(Verdict verdict, PayeeVerdict payee) {
        Objects.requireNonNull(verdict, "verdict must not be null");
        Objects.requireNonNull(payee, "payee must not be null");
        return switch (verdict) {
            case CLEAR -> payee == PayeeVerdict.MATCH
                    ? new Routed(CounterpartyScreeningStatus.CLEAR, Optional.empty())
                    : new Routed(CounterpartyScreeningStatus.IN_REVIEW, Optional.of(ReviewReason.PAYEE_UNVERIFIED));
            case HIT -> new Routed(CounterpartyScreeningStatus.IN_REVIEW, Optional.of(ReviewReason.HIT));
            case INDETERMINATE -> new Routed(CounterpartyScreeningStatus.IN_REVIEW, Optional.of(ReviewReason.INDETERMINATE));
            case UNAVAILABLE -> new Routed(CounterpartyScreeningStatus.UNAVAILABLE, Optional.empty());
        };
    }

    /** The wait before attempt {@code attempt + 1}, doubling from {@link #FIRST_BACKOFF} to {@link #MAX_BACKOFF}. */
    static Duration backoff(int attempt) {
        int doublings = Math.min(Math.max(attempt - 1, 0), 10);
        Duration wait = FIRST_BACKOFF.multipliedBy(1L << doublings);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    // ------------------------------------------------------------------ screening

    /**
     * Screens {@code request}'s counterparty. A reference already screened converges on its screening
     * (the same counterparty) or is refused ({@link RequestConflict}).
     */
    public Screening screen(Request request, CorrelationId correlation) {
        Objects.requireNonNull(request, "request must not be null");
        return screen(request, Optional.of(request.requestedBy()), correlation);
    }

    private Screening screen(Request request, Optional<String> requester, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation must not be null");
        Instant now = now();
        CounterpartyScreeningId id = CounterpartyScreeningId.next(ids);
        CounterpartySubject subject = request.subject();
        CounterpartySubjectCipher.Encrypted sealed = cipher.encrypt(id, subject.name());
        boolean inserted = transactions.inTransaction(uow -> store.insertRequested(uow, new CounterpartyScreeningStore.NewScreening(
                id, request.requestReference(), sealed, subject.country(), subject.entityType(), request.payeeVerdict(),
                requester, now.plus(IN_FLIGHT_PERMIT))));
        if (!inserted) {
            CounterpartyScreeningStore.Row existing = transactions
                    .inTransaction(uow -> store.byRequest(uow, request.requestReference()))
                    .orElseThrow(() -> new KycStorageException(
                            "a counterparty screening request was refused as a duplicate but none is visible; retry"));
            if (!subjectOf(existing).equals(subject) || existing.payeeVerdict() != request.payeeVerdict()) {
                throw new RequestConflict();
            }
            return view(existing, true);
        }
        return ask(id, subject, correlation);
    }

    /**
     * Requests a screening in the caller's unit of work (`P9-TSK-017`) - the request commits with the
     * caller's transaction, holding the caller's permit ({@link #IN_FLIGHT_PERMIT}); the caller then asks
     * through {@link #retry}, and a crash in between leaves the screening due for the sweeper. A reference
     * already screened converges on its screening (the same counterparty) or is refused.
     */
    public Screening requestWithin(Connection uow, Request request) {
        Objects.requireNonNull(request, "request must not be null");
        return requestWithin(uow, request, Optional.of(request.requestedBy()));
    }

    private Screening requestWithin(Connection uow, Request request, Optional<String> requester) {
        Objects.requireNonNull(uow, "uow must not be null");
        Instant now = now();
        CounterpartyScreeningId id = CounterpartyScreeningId.next(ids);
        CounterpartySubject subject = request.subject();
        if (store.insertRequested(uow, new CounterpartyScreeningStore.NewScreening(
                id, request.requestReference(), cipher.encrypt(id, subject.name()), subject.country(),
                subject.entityType(), request.payeeVerdict(), requester, now.plus(IN_FLIGHT_PERMIT)))) {
            return new Screening(id, request.requestReference(), CounterpartyScreeningStatus.REQUESTED,
                    Optional.empty(), Optional.empty(), false);
        }
        CounterpartyScreeningStore.Row existing = store.byRequest(uow, request.requestReference())
                .orElseThrow(() -> new KycStorageException(
                        "a counterparty screening request was refused as a duplicate but none is visible; retry"));
        if (!subjectOf(existing).equals(subject) || existing.payeeVerdict() != request.payeeVerdict()) {
            throw new RequestConflict();
        }
        return view(existing, true);
    }

    /**
     * Requests a re-screen of {@code previous}'s counterparty in the caller's unit of work (`P9-TSK-018`) - the
     * stored subject, payee check and requester under {@code requestReference}, held by the caller's permit. The
     * requester is inherited: whoever registered the counterparty never reviews any of its screenings.
     */
    public Screening rescreenWithin(Connection uow, CounterpartyScreeningId previous, String requestReference) {
        Objects.requireNonNull(uow, "uow must not be null");
        Objects.requireNonNull(previous, "previous must not be null");
        CounterpartyScreeningStore.Row row = store.find(uow, previous).orElseThrow(ScreeningNotFound::new);
        return requestWithin(uow, inherited(row, requestReference), row.requestedBy());
    }

    /**
     * Screens {@code previous}'s counterparty again under a new reference - the stored subject and payee
     * check, so the caller never needs the name (ADR-0081 point 3's quote-time re-screen).
     */
    public Screening rescreen(CounterpartyScreeningId previous, String requestReference, CorrelationId correlation) {
        Objects.requireNonNull(previous, "previous must not be null");
        CounterpartyScreeningStore.Row row =
                transactions.inTransaction(uow -> store.find(uow, previous)).orElseThrow(ScreeningNotFound::new);
        return screen(inherited(row, requestReference), row.requestedBy(), correlation);
    }

    /** The re-screen's request: the stored subject and payee check; the requester travels beside it, as stored. */
    private Request inherited(CounterpartyScreeningStore.Row row, String requestReference) {
        // A screening requested before kyc V010 names no requester: the placeholder is never stored (the Optional
        // beside it is), and it only satisfies the record's shape.
        return new Request(requestReference, subjectOf(row), row.payeeVerdict(), row.requestedBy().orElse("unrecorded"));
    }

    /** Claims at most {@code limit} due screenings for this sweeper - concurrent sweepers claim disjoint sets. */
    public List<CounterpartyScreeningId> claimDue(int limit) {
        return transactions.inTransaction(uow -> store.claimDue(uow, IN_FLIGHT_PERMIT, limit));
    }

    /** Asks the provider again for one claimed screening; an answered one is left as it stands. */
    public Screening retry(CounterpartyScreeningId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id must not be null");
        CounterpartyScreeningStore.Row row = transactions.inTransaction(uow -> store.find(uow, id)).orElseThrow(ScreeningNotFound::new);
        if (!unanswered(row.status())) {
            return view(row, true);
        }
        return ask(id, subjectOf(row), correlation);
    }

    private Screening ask(CounterpartyScreeningId id, CounterpartySubject subject, CorrelationId correlation) {
        CounterpartyScreeningProvider.Answer answer = provider.screen(id, subject);
        Screening decided = transactions.inTransaction(uow -> decide(uow, id, answer, correlation));
        if (!decided.replayed()) {
            observer.decided(decided.status());
        }
        return decided;
    }

    /**
     * T-e: one transaction for the attempt, the evidence, the outcome, its audit, its event and the listener.
     * The outcome is the platform's - an enumerated {@code enterSystem()} site.
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    private Screening decide(
            Connection uow, CounterpartyScreeningId id, CounterpartyScreeningProvider.Answer answer, CorrelationId correlation) {
        CounterpartyScreeningStore.Row row = store.lock(uow, id).orElseThrow(ScreeningNotFound::new);
        if (!unanswered(row.status())) {
            return view(row, true);
        }
        Instant at = now();
        int attempt = row.attempts() + 1;
        Optional<byte[]> evidence = answer.evidence();
        store.insertAttempt(uow, new CounterpartyScreeningStore.Attempt(
                id,
                attempt,
                answer.verdict(),
                evidence.map(bytes -> cipher.seal(id, bytes)),
                evidence.map(KycDocument::checksumOf),
                evidence.map(bytes -> bytes.length).orElse(0),
                at));
        Routed routed = route(answer.verdict(), row.payeeVerdict());
        Optional<Instant> next = routed.status() == CounterpartyScreeningStatus.UNAVAILABLE
                ? Optional.of(at.plus(backoff(attempt)))
                : Optional.empty();
        String policy = KycPolicyVersion.CURRENT.value();
        if (!store.decideAutomatically(uow, id, row.status(), new CounterpartyScreeningStore.AutomaticOutcome(
                routed.status(), routed.reviewReason(), attempt, policy, next))) {
            throw new IllegalStateException(
                    "the locked counterparty screening was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        String reason = "the provider answered " + answer.verdict().name() + " and the payee check was "
                + row.payeeVerdict().name() + ": decided " + routed.status().name()
                + routed.reviewReason().map(why -> " (" + why.name() + ")").orElse("");
        Actor platform;
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            platform = SecurityContext.require();
        }
        record(uow, platform, at, id, reason, summary(id, routed.status(), DecisionBasis.AUTOMATIC, policy, attempt), correlation);
        announce(uow, id, row.requestReference(), routed.status(), DecisionBasis.AUTOMATIC, at, correlation);
        listener.decided(uow, new ScreeningOutcomeListener.Outcome(id, row.requestReference(), routed.status(), at, correlation));
        return new Screening(id, row.requestReference(), routed.status(), routed.reviewReason(), Optional.of(at), false);
    }

    // ------------------------------------------------------------------ the person

    /**
     * A person releases or blocks a screening in review, in the caller's unit of work (the door keys
     * it). The code must justify the decision; the narrative is 1..{@value #MAX_NARRATIVE} characters
     * and holds no instrument shape. The same person's identical retry converges.
     *
     * @throws ScreeningNotFound when no screening has this id
     * @throws ScreeningNotInReview when it is not {@code IN_REVIEW}
     * @throws ScreeningReviewInvalid when the code or the narrative is not acceptable
     */
    public Screening review(
            Connection uow,
            CounterpartyScreeningId id,
            Actor actor,
            Decision decision,
            ReasonCode code,
            String narrative,
            CorrelationId correlation) {
        Objects.requireNonNull(uow, "uow must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(narrative, "narrative must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        CounterpartyScreeningStore.Row row = store.lock(uow, id).orElseThrow(ScreeningNotFound::new);
        CounterpartyScreeningStatus target =
                decision == Decision.RELEASE ? CounterpartyScreeningStatus.RELEASED : CounterpartyScreeningStatus.BLOCKED;
        if (row.status() == target
                && row.decidedBy().filter(actor.id()::equals).isPresent()
                && row.reasonCode().filter(code::equals).isPresent()) {
            return view(row, true);
        }
        if (row.status() != CounterpartyScreeningStatus.IN_REVIEW) {
            throw new ScreeningNotInReview(row.status());
        }
        if (row.requestedBy().filter(actor.id()::equals).isPresent()) {
            // Four eyes are two persons (INV-AUD-04; the Phase 9 to 10 transition gate): the person who registered
            // the counterparty never releases or blocks its screening. kyc V010's CHECK is the rank beneath.
            throw new ScreeningReviewInvalid("a screening is reviewed by someone other than the person who requested it");
        }
        if (!code.justifies(decision)) {
            throw new ScreeningReviewInvalid("reason code " + code.name() + " cannot justify " + decision.name());
        }
        if (narrative.isBlank() || narrative.length() > MAX_NARRATIVE) {
            throw new ScreeningReviewInvalid("a review is reasoned: the narrative must be 1.." + MAX_NARRATIVE + " characters");
        }
        if (InstrumentShapes.holdsAny(narrative)) {
            throw new ScreeningReviewInvalid("the narrative must not hold a card-number or bank-account shape");
        }
        Instant at = now();
        String policy = KycPolicyVersion.CURRENT.value();
        if (!store.decideByReviewer(uow, id, new CounterpartyScreeningStore.ReviewerOutcome(
                target, actor.id(), code, narrative, policy))) {
            throw new IllegalStateException(
                    "the locked counterparty screening was decided by another writer: the FOR UPDATE protocol was bypassed");
        }
        record(uow, actor, at, id, bounded(code.name() + ": " + narrative),
                summary(id, target, DecisionBasis.REVIEWER, policy, row.attempts()) + ", code=" + code.name(), correlation);
        announce(uow, id, row.requestReference(), target, DecisionBasis.REVIEWER, at, correlation);
        listener.decided(uow, new ScreeningOutcomeListener.Outcome(id, row.requestReference(), target, at, correlation));
        return new Screening(id, row.requestReference(), target, row.reviewReason(), Optional.of(at), false);
    }

    /** Tells the observer a review committed - the door calls this after its transaction. */
    public void committed(Screening reviewed) {
        Objects.requireNonNull(reviewed, "reviewed must not be null");
        if (!reviewed.replayed()) {
            observer.decided(reviewed.status());
        }
    }

    // ------------------------------------------------------------------ reads

    /** {@code id}'s clearance - whether it clears and since when. */
    public Optional<Clearance> clearance(Connection uow, CounterpartyScreeningId id) {
        return store.find(uow, id).map(row -> new Clearance(row.id(), row.status(), row.decidedAt()));
    }

    /** How many screenings wait for a person, and since when the oldest has. */
    public CounterpartyScreeningStore.ReviewBacklog reviewBacklog() {
        return transactions.inTransaction(store::reviewBacklog);
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean unanswered(CounterpartyScreeningStatus status) {
        return status == CounterpartyScreeningStatus.REQUESTED || status == CounterpartyScreeningStatus.UNAVAILABLE;
    }

    private CounterpartySubject subjectOf(CounterpartyScreeningStore.Row row) {
        return new CounterpartySubject(cipher.decrypt(row.id(), row.subject()), row.country(), row.entityType());
    }

    private static Screening view(CounterpartyScreeningStore.Row row, boolean replayed) {
        return new Screening(row.id(), row.requestReference(), row.status(), row.reviewReason(), row.decidedAt(), replayed);
    }

    private static String summary(
            CounterpartyScreeningId id, CounterpartyScreeningStatus status, DecisionBasis basis, String policy, int attempts) {
        return "screening=" + id.value() + ", status=" + status.name() + ", basis=" + basis.name() + ", policy=" + policy
                + ", attempts=" + attempts;
    }

    private static String bounded(String reason) {
        if (reason.length() <= MAX_NARRATIVE) {
            return reason;
        }
        int end = MAX_NARRATIVE;
        if (Character.isHighSurrogate(reason.charAt(end - 1))) {
            end--;
        }
        return reason.substring(0, end);
    }

    private void record(
            Connection uow,
            Actor actor,
            Instant at,
            CounterpartyScreeningId id,
            String reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        KycAuditAction.COUNTERPARTY_SCREENING_DECIDED,
                        TARGET_TYPE,
                        id.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }

    private void announce(
            Connection uow,
            CounterpartyScreeningId id,
            String requestReference,
            CounterpartyScreeningStatus status,
            DecisionBasis basis,
            Instant at,
            CorrelationId correlation) {
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        DECIDED_EVENT,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        TARGET_TYPE,
                        at,
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                EventPayload.of()
                        .with("screening", id.value().toString())
                        .with("request", requestReference)
                        .with("status", status.name())
                        .with("basis", basis.name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
