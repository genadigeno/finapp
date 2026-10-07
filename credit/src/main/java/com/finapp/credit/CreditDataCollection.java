package com.finapp.credit;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Bureau data collection (`P10-TSK-006`; ADR-0085, {@code INV-CRD-03}, {@code INV-CRD-10}, {@code INV-LIFE-03},
 * {@code INV-AUD-01}, {@code INV-CRD-07}): a party's credit report retrieved under a recorded, current lawful basis,
 * once per reference, its evidence encrypted and retained, and outages, duplicates, lost responses and withdrawals
 * all safe.
 *
 * <h2>Two transactions, never a connection across the call (ADR-0081's screening shape)</h2>
 *
 * <p><strong>Open</strong> (Tx1, {@link #openWithin}) runs inside the caller's transaction - the decision request's,
 * under its row lock (lock-order element (2)): the gate for the source kind read authoritatively, the data request
 * born {@code REQUESTED} under a fresh reference with its windows stamped by the database, and
 * {@code credit.BureauDataRequested} audited. Nothing is asked until the caller has committed; then {@link #ask}
 * pulls under the reference with no connection held. A crash in between leaves the request due: the sweep asks.
 *
 * <p><strong>Record</strong> (Tx2) locks the data request and acts only from {@code REQUESTED}. It re-reads the gate:
 * absent, the request is {@code CONSENT_WITHDRAWN} and the payload is discarded unread - the evidence row records only
 * that a response arrived. Present, a {@code Received} or {@code Partial} answer births the record and its attributes,
 * the evidence, the attempt, {@code RECEIVED} and {@code CreditDataCollected} in one transaction; an
 * {@code Unavailable} answer is {@code UNAVAILABLE}, its attempt and any bytes kept, the permit re-stamped. An answer
 * for a request that is no longer {@code REQUESTED} is evidence flagged duplicate - never a second record.
 *
 * <h2>Retries and the deadline</h2>
 *
 * <p>The sweep {@linkplain #claimDue claims} due requests on the database's clock and {@linkplain #retry re-asks} each
 * under the SAME reference after re-reading the gate (absent: {@code CONSENT_WITHDRAWN}, nothing asked) - the provider
 * dedupes, so a lost response costs one pull. Past its deadline an unavailable request is never asked again, and
 * {@link #reportOverdue} emits {@code CreditDataUnavailable} for it exactly once, by a conditional flag.
 */
@RequiredArgsConstructor
public final class CreditDataCollection {

    public static final String COLLECTED_EVENT = "credit.CreditDataCollected";
    public static final String UNAVAILABLE_EVENT = "credit.CreditDataUnavailable";

    static final String TARGET_TYPE = "credit_data_request";
    static final String PRODUCER = "credit";
    static final int EVENT_VERSION = 1;
    private static final String REFERENCE_PREFIX = "CDR-";

    @NonNull private final CreditDataRequestStore store;
    @NonNull private final CreditBureau bureau;
    @NonNull private final CreditConsentGate<Connection> gate;
    @NonNull private final CreditEvidenceCipher cipher;
    @NonNull private final CreditDataObserver observer;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final Timing bureauTiming;

    /** A source kind's collection timing - configuration, frozen on each data request at its birth. */
    public record Timing(Duration retryCadence, Duration collectionWindow) {
        public Timing {
            Objects.requireNonNull(retryCadence, "retryCadence");
            Objects.requireNonNull(collectionWindow, "collectionWindow");
            if (retryCadence.isNegative() || retryCadence.isZero() || collectionWindow.compareTo(retryCadence) <= 0) {
                throw new IllegalArgumentException("a positive cadence inside a longer collection window");
            }
        }
    }

    /** What to open: the decision request it serves, the party, the product and the source kind. */
    public record Opening(UUID decisionRequestId, UUID partyId, CreditProduct product, CreditSourceKind kind) {
        public Opening {
            Objects.requireNonNull(decisionRequestId, "decisionRequestId");
            Objects.requireNonNull(partyId, "partyId");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** What an opening did. */
    public sealed interface Opened permits Opened.ConsentAbsent, Opened.Requested {

        /** No current basis for the source kind: nothing was born, nothing will be asked. */
        record ConsentAbsent() implements Opened {}

        /** The data request was born {@code REQUESTED}; ask it once the opening transaction has committed. */
        record Requested(CreditDataRequestId id) implements Opened {
            public Requested {
                Objects.requireNonNull(id, "id");
            }
        }
    }

    // ------------------------------------------------------------------ open

    /**
     * Tx1, in the caller's unit of work: the gate, the birth, the audit. The caller commits, then calls {@link #ask}.
     *
     * @throws UnsupportedOperationException for a source kind this collection does not pull yet
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public Opened openWithin(Connection uow, Opening opening, CorrelationId correlation) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(correlation, "correlation");
        if (opening.kind() != CreditSourceKind.BUREAU) {
            throw new UnsupportedOperationException("financial data collection is P10-TSK-007's");
        }
        if (!gate.permits(uow, opening.partyId(), opening.kind())) {
            return new Opened.ConsentAbsent();
        }
        CreditDataRequestId id = CreditDataRequestId.next(ids);
        store.insertRequested(uow, new CreditDataRequestStore.NewRequest(
                id, opening.decisionRequestId(), opening.partyId(), opening.product(), opening.kind(), bureau.code(),
                REFERENCE_PREFIX + id.value(), bureauTiming.retryCadence(), bureauTiming.collectionWindow()));
        Actor platform;
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            platform = SecurityContext.require();
        }
        audit.append(uow, new AuditRecord(
                AuditId.next(ids),
                platform,
                now(),
                CreditAuditAction.BUREAU_DATA_REQUESTED,
                TARGET_TYPE,
                id.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("dataRequest=" + id.value() + ", decisionRequest=" + opening.decisionRequestId()
                        + ", sourceKind=" + opening.kind().name() + ", provider=" + bureau.code())));
        return new Opened.Requested(id);
    }

    /** Tx1 in a transaction of its own, then the ask - for a caller with no transaction of its own. */
    public Opened open(Opening opening, CorrelationId correlation) {
        Opened opened = transactions.inTransaction(uow -> openWithin(uow, opening, correlation));
        if (opened instanceof Opened.Requested requested) {
            ask(requested.id(), correlation);
        }
        return opened;
    }

    // ------------------------------------------------------------------ ask and record

    /** Pulls the request under its reference, holding no connection, then records the answer (Tx2). */
    public CreditDataRequestStatus ask(CreditDataRequestId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(correlation, "correlation");
        CreditDataRequestStore.Row row = transactions.inTransaction(uow -> store.find(uow, id))
                .orElseThrow(() -> new IllegalArgumentException("no credit data request has this identifier"));
        if (row.status() != CreditDataRequestStatus.REQUESTED) {
            return row.status();
        }
        Instant started = Instant.now(clock);
        BureauAnswer answer = bureau.pull(new BureauRequest(row.reference(), row.partyId().toString(), row.product()));
        observer.called(row.kind(), row.providerCode(), Duration.between(started, Instant.now(clock)));
        return transactions.inTransaction(uow -> record(uow, id, answer, correlation));
    }

    private CreditDataRequestStatus record(
            Connection uow, CreditDataRequestId id, BureauAnswer answer, CorrelationId correlation) {
        CreditDataRequestStore.Row row = store.lock(uow, id)
                .orElseThrow(() -> new IllegalArgumentException("no credit data request has this identifier"));
        if (row.status() != CreditDataRequestStatus.REQUESTED) {
            // Answered already, withdrawn, or reclaimed: the bytes are kept as evidence, never a second record.
            evidenceOf(answer).ifPresent(bytes -> store.insertEvidence(uow, evidence(
                    row, Math.max(row.attempts(), 1), true,
                    row.status() == CreditDataRequestStatus.CONSENT_WITHDRAWN ? Optional.empty() : Optional.of(bytes))));
            observer.answered(row.kind(), row.providerCode(), CreditDataObserver.Outcome.DUPLICATE);
            return row.status();
        }
        int attempt = row.attempts() + 1;
        if (!gate.permits(uow, row.partyId(), row.kind())) {
            // Withdrawn in flight: the payload is discarded unread - only the fact that a response arrived is kept.
            requireMoved(store.withdraw(uow, id, CreditDataRequestStatus.REQUESTED, attempt));
            store.insertAttempt(uow, id, attempt, "CONSENT_WITHDRAWN");
            if (evidenceOf(answer).isPresent()) {
                store.insertEvidence(uow, evidence(row, attempt, false, Optional.empty()));
            }
            observer.answered(row.kind(), row.providerCode(), CreditDataObserver.Outcome.CONSENT_WITHDRAWN);
            return CreditDataRequestStatus.CONSENT_WITHDRAWN;
        }
        return switch (answer) {
            case BureauAnswer.Received received -> collected(uow, row, attempt, received.providerCode(),
                    received.normaliserVersion(), true, received.retrievedAt(), received.attributes(),
                    received.evidence().bytes(), "RECEIVED", correlation);
            case BureauAnswer.Partial partial -> collected(uow, row, attempt, partial.providerCode(),
                    partial.normaliserVersion(), false, partial.retrievedAt(), partial.attributes(),
                    partial.evidence().bytes(), "PARTIAL", correlation);
            case BureauAnswer.Unavailable unavailable -> {
                requireMoved(store.markUnavailable(uow, id, attempt));
                store.insertAttempt(uow, id, attempt, unavailable.cause().name());
                unavailable.evidence().ifPresent(bytes ->
                        store.insertEvidence(uow, evidence(row, attempt, false, Optional.of(bytes.bytes()))));
                observer.answered(row.kind(), row.providerCode(), CreditDataObserver.Outcome.UNAVAILABLE);
                yield CreditDataRequestStatus.UNAVAILABLE;
            }
        };
    }

    private CreditDataRequestStatus collected(
            Connection uow,
            CreditDataRequestStore.Row row,
            int attempt,
            String providerCode,
            int normaliserVersion,
            boolean complete,
            Instant retrievedAt,
            List<CreditAttribute> attributes,
            byte[] bytes,
            String outcome,
            CorrelationId correlation) {
        requireMoved(store.receive(uow, row.id(), attempt));
        store.insertAttempt(uow, row.id(), attempt, outcome);
        store.insertRecord(uow, CreditRecordId.next(ids), row, providerCode, normaliserVersion, complete, retrievedAt,
                attributes);
        store.insertEvidence(uow, evidence(row, attempt, false, Optional.of(bytes)));
        announce(uow, COLLECTED_EVENT, row.id(), EventPayload.of()
                .with("dataRequest", row.id().value().toString())
                .with("decisionRequest", row.decisionRequestId().toString())
                .with("sourceKind", row.kind().name())
                .with("provider", providerCode)
                // Epoch milliseconds: an event value is an identifier-shaped token, never free text (INV-AUD-02).
                .with("retrievedAtEpochMilli", Long.toString(retrievedAt.toEpochMilli())), correlation);
        observer.answered(row.kind(), providerCode, CreditDataObserver.Outcome.RECEIVED);
        return CreditDataRequestStatus.RECEIVED;
    }

    // ------------------------------------------------------------------ the sweep

    /** Claims at most {@code limit} due requests for this sweeper - concurrent sweepers claim disjoint sets. */
    public List<CreditDataRequestId> claimDue(int limit) {
        return transactions.inTransaction(uow -> store.claimDue(uow, limit));
    }

    /**
     * Re-asks one claimed request under its SAME reference, after re-reading the gate: absent, the request is
     * {@code CONSENT_WITHDRAWN} and nothing is asked.
     */
    public CreditDataRequestStatus retry(CreditDataRequestId id, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        CreditDataRequestStatus gated = transactions.inTransaction(uow -> {
            CreditDataRequestStore.Row row = store.lock(uow, id)
                    .orElseThrow(() -> new IllegalArgumentException("no credit data request has this identifier"));
            if (row.status() != CreditDataRequestStatus.REQUESTED) {
                return row.status();
            }
            if (!gate.permits(uow, row.partyId(), row.kind())) {
                int attempt = row.attempts() + 1;
                requireMoved(store.withdraw(uow, id, CreditDataRequestStatus.REQUESTED, attempt));
                store.insertAttempt(uow, id, attempt, "CONSENT_WITHDRAWN");
                observer.answered(row.kind(), row.providerCode(), CreditDataObserver.Outcome.CONSENT_WITHDRAWN);
                return CreditDataRequestStatus.CONSENT_WITHDRAWN;
            }
            return CreditDataRequestStatus.REQUESTED;
        });
        return gated == CreditDataRequestStatus.REQUESTED ? ask(id, correlation) : gated;
    }

    /** Emits {@code CreditDataUnavailable} once for each unavailable request past its deadline; returns how many. */
    public int reportOverdue(int limit, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        return transactions.inTransaction(uow -> {
            List<CreditDataRequestStore.Overdue> overdue = store.claimOverdue(uow, limit);
            for (CreditDataRequestStore.Overdue request : overdue) {
                announce(uow, UNAVAILABLE_EVENT, request.id(), EventPayload.of()
                        .with("dataRequest", request.id().value().toString())
                        .with("sourceKind", request.kind().name())
                        .with("provider", request.providerCode())
                        .with("attempts", Integer.toString(request.attempts())), correlation);
            }
            return overdue.size();
        });
    }

    // ------------------------------------------------------------------ plumbing

    private CreditDataRequestStore.NewEvidence evidence(
            CreditDataRequestStore.Row row, int attempt, boolean duplicate, Optional<byte[]> bytes) {
        CreditEvidenceId id = CreditEvidenceId.next(ids);
        return new CreditDataRequestStore.NewEvidence(
                id,
                row.id(),
                attempt,
                duplicate,
                bytes.map(content -> cipher.encrypt(id, content)),
                bytes.map(CreditDataCollection::sha256),
                bytes.map(content -> content.length).orElse(0),
                (int) row.product().evidenceRetention().toTotalMonths());
    }

    private static Optional<byte[]> evidenceOf(BureauAnswer answer) {
        return switch (answer) {
            case BureauAnswer.Received received -> Optional.of(received.evidence().bytes());
            case BureauAnswer.Partial partial -> Optional.of(partial.evidence().bytes());
            case BureauAnswer.Unavailable unavailable -> unavailable.evidence().map(CreditEvidence::bytes);
        };
    }

    private void announce(
            Connection uow, String type, CreditDataRequestId id, EventPayload payload, CorrelationId correlation) {
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        id,
                        TARGET_TYPE,
                        now(),
                        PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static void requireMoved(boolean moved) {
        if (!moved) {
            throw new IllegalStateException(
                    "the locked credit data request was moved by another writer: the FOR UPDATE protocol was bypassed");
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
