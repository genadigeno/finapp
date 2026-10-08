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
import lombok.extern.slf4j.Slf4j;

/**
 * Credit data collection - the bureau's (`P10-TSK-006`) and the financial-data provider's (`P10-TSK-007`) on one
 * machinery (ADR-0085, {@code INV-CRD-03}, {@code INV-CRD-10}, {@code INV-LIFE-03},
 * {@code INV-AUD-01}, {@code INV-CRD-07}): a party's credit report retrieved under a recorded, current lawful basis,
 * once per reference, its evidence encrypted and retained, and outages, duplicates, lost responses and withdrawals
 * all safe.
 *
 * <h2>Two transactions, never a connection across the call (ADR-0081's screening shape)</h2>
 *
 * <p><strong>Open</strong> (Tx1, {@link #openWithin}) runs inside the caller's transaction - the decision request's,
 * under its row lock (lock-order element (2)): the gate for the source kind read authoritatively, the data request
 * born {@code REQUESTED} under a fresh reference with its windows stamped by the database, and
 * {@code credit.BureauDataRequested} or {@code credit.FinancialDataRequested} audited - the source kind's own act. Nothing is asked until the caller has committed; then {@link #ask}
 * pulls under the reference with no connection held. A crash in between leaves the request due: the sweep asks.
 *
 * <p><strong>Record</strong> (Tx2) locks the data request and acts only from {@code REQUESTED}. It re-reads the gate:
 * absent, the request is {@code CONSENT_WITHDRAWN} and the payload is discarded unread - the evidence row records only
 * that a response arrived. Present, a {@code Received} or {@code Partial} answer births the record and its attributes,
 * the evidence, the attempt, {@code RECEIVED} and {@code CreditDataCollected} in one transaction; an
 * {@code Unavailable} answer is {@code UNAVAILABLE}, its attempt and any bytes kept, the permit re-stamped. An answer
 * for a request that is no longer {@code REQUESTED} is evidence flagged duplicate - never a second record.
 *
 * <h2>Source selection (`P10-TSK-021`, ADR-0085 section 10)</h2>
 *
 * <p>Each kind has a configured provider order; the opening selects the first provider not disabled (else the kind's
 * fail-safe) and stamps it on the data request. Every ask - the first and each retry, on any instance - goes to that
 * provider, under the request's one reference: never a second provider under one reference, and a provider this
 * instance no longer configures is {@code Unavailable} without a call. Mid-request failover is refused (ADR-0085
 * section 10): an unavailable source reaches its deadline and the policy's fallback.
 *
 * <h2>Retries and the deadline</h2>
 *
 * <p>The sweep {@linkplain #claimDue claims} due requests on the database's clock and {@linkplain #retry re-asks} each
 * under the SAME reference after re-reading the gate (absent: {@code CONSENT_WITHDRAWN}, nothing asked) - the provider
 * dedupes, so a lost response costs one pull. Past its deadline an unavailable request is never asked again, and
 * {@link #reportOverdue} emits {@code CreditDataUnavailable} for it exactly once, by a conditional flag.
 */
@RequiredArgsConstructor
@Slf4j
public final class CreditDataCollection {

    public static final String COLLECTED_EVENT = "credit.CreditDataCollected";
    public static final String UNAVAILABLE_EVENT = "credit.CreditDataUnavailable";

    static final String TARGET_TYPE = "credit_data_request";
    static final String PRODUCER = "credit";
    static final int EVENT_VERSION = 1;
    private static final String REFERENCE_PREFIX = "CDR-";

    @NonNull private final CreditDataRequestStore store;
    @NonNull private final Sources sources;
    @NonNull private final CreditConsentGate<Connection> gate;
    @NonNull private final CreditEvidenceCipher cipher;
    @NonNull private final CreditDataObserver observer;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

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

    /**
     * One source kind's configured providers and its collection timing (`P10-TSK-021`, ADR-0085 section 10): the
     * providers in their configured order, the codes disabled, and the fail-safe a kind falls to when every provider is
     * disabled - a source whose every pull is {@code Unavailable}, so the request still reaches its deadline and the
     * policy's fallback, never data.
     *
     * <p><strong>Selection is a pure function of this configuration, applied once, at birth</strong>: the first provider
     * in order not disabled, else the fail-safe. The provider chosen is stamped on the data request and is the only one
     * ever asked under its reference - disabling it later stops new births, never an open request's retries.
     */
    public record Configured(
            List<CreditDataSource> order, java.util.Set<String> disabled, Optional<CreditDataSource> failSafe, Timing timing) {
        public Configured {
            order = List.copyOf(Objects.requireNonNull(order, "order"));
            disabled = java.util.Set.copyOf(Objects.requireNonNull(disabled, "disabled"));
            Objects.requireNonNull(failSafe, "failSafe");
            Objects.requireNonNull(timing, "timing");
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (CreditDataSource source : order) {
                if (!codes.add(source.code())) {
                    throw new IllegalArgumentException(source.code() + " is configured twice");
                }
            }
            if (failSafe.isPresent() && codes.contains(failSafe.get().code())) {
                throw new IllegalArgumentException(failSafe.get().code() + " is both a provider and the fail-safe");
            }
            if (!codes.containsAll(disabled)) {
                // A disabled code naming no provider is a typo that would silently disable nothing.
                throw new IllegalArgumentException("a disabled provider is not in the configured order");
            }
            if (failSafe.isEmpty() && codes.equals(disabled)) {
                throw new IllegalArgumentException("every provider is disabled and no fail-safe is configured");
            }
        }

        /** A single provider, never disabled - the shape before selection existed. */
        public Configured(CreditDataSource source, Timing timing) {
            this(List.of(Objects.requireNonNull(source, "source")), java.util.Set.of(), Optional.empty(), timing);
        }

        /** The provider a data request born now names: the first in order not disabled, else the fail-safe. */
        public CreditDataSource select() {
            for (CreditDataSource source : order) {
                if (!disabled.contains(source.code())) {
                    return source;
                }
            }
            return failSafe.orElseThrow(); // unreachable: the constructor refuses every provider disabled without one
        }

        /** The configured provider of {@code code}, disabled or not - the one a born request is asked of. */
        public Optional<CreditDataSource> provider(String code) {
            Objects.requireNonNull(code, "code");
            return java.util.stream.Stream.concat(order.stream(), failSafe.stream())
                    .filter(source -> source.code().equals(code))
                    .findFirst();
        }

        private void requireKind(CreditSourceKind kind) {
            java.util.stream.Stream.concat(order.stream(), failSafe.stream()).forEach(source -> {
                if (source.kind() != kind) {
                    throw new IllegalArgumentException(source.code() + " is not a " + kind + " source");
                }
            });
        }
    }

    /**
     * The configured sources per kind (`P10-TSK-007`; an order per kind since `P10-TSK-021`): each source declares its
     * own kind, and a kind with no source cannot be opened.
     */
    public record Sources(java.util.Map<CreditSourceKind, Configured> byKind) {
        public Sources {
            Objects.requireNonNull(byKind, "byKind");
            byKind = java.util.Map.copyOf(byKind);
            byKind.forEach((kind, configured) -> configured.requireKind(kind));
        }

        /** The configured source of {@code kind}. */
        public Configured of(CreditSourceKind kind) {
            Configured configured = byKind.get(kind);
            if (configured == null) {
                throw new UnsupportedOperationException("no " + kind + " source is configured");
            }
            return configured;
        }

        /** Sources for a bureau and a financial-data provider, each with its timing. */
        public static Sources of(CreditBureau bureau, Timing bureauTiming, FinancialDataProvider financialData,
                Timing financialDataTiming) {
            return new Sources(java.util.Map.of(
                    CreditSourceKind.BUREAU, new Configured(bureau, bureauTiming),
                    CreditSourceKind.FINANCIAL_DATA, new Configured(financialData, financialDataTiming)));
        }

        /** Sources for both kinds, each its own order (`P10-TSK-021`). */
        public static Sources of(Configured bureau, Configured financialData) {
            return new Sources(java.util.Map.of(
                    CreditSourceKind.BUREAU, bureau, CreditSourceKind.FINANCIAL_DATA, financialData));
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
     * @throws UnsupportedOperationException for a source kind with no configured source
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect (the established idiom).
    public Opened openWithin(Connection uow, Opening opening, CorrelationId correlation) {
        Objects.requireNonNull(uow, "uow");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(correlation, "correlation");
        Configured configured = sources.of(opening.kind());
        if (!gate.permits(uow, opening.partyId(), opening.kind())) {
            return new Opened.ConsentAbsent();
        }
        // Selected once, here, from configuration alone: the provider is fixed on the request at its birth.
        CreditDataSource provider = configured.select();
        CreditDataRequestId id = CreditDataRequestId.next(ids);
        store.insertRequested(uow, new CreditDataRequestStore.NewRequest(
                id, opening.decisionRequestId(), opening.partyId(), opening.product(), opening.kind(),
                provider.code(), REFERENCE_PREFIX + id.value(), configured.timing().retryCadence(),
                configured.timing().collectionWindow()));
        Actor platform;
        try (SecurityContext.Scope system = SecurityContext.enterSystem()) {
            platform = SecurityContext.require();
        }
        audit.append(uow, new AuditRecord(
                AuditId.next(ids),
                platform,
                now(),
                opening.kind() == CreditSourceKind.BUREAU
                        ? CreditAuditAction.BUREAU_DATA_REQUESTED
                        : CreditAuditAction.FINANCIAL_DATA_REQUESTED,
                TARGET_TYPE,
                id.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("dataRequest=" + id.value() + ", decisionRequest=" + opening.decisionRequestId()
                        + ", sourceKind=" + opening.kind().name() + ", provider=" + provider.code())));
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
        // Asked of the provider the request was born naming - never another under its reference (P10-TSK-021).
        Optional<CreditDataSource> provider = sources.of(row.kind()).provider(row.providerCode());
        CreditDataAnswer answer;
        if (provider.isEmpty()) {
            // Configured out since the birth (a redeploy, or an instance configured otherwise): unavailable, nothing
            // asked, no substitute - the deadline and the policy's fallback do the rest.
            log.warn("A credit data request names provider {}, which this instance does not configure", row.providerCode());
            answer = new CreditDataAnswer.Unavailable(CreditDataAnswer.UnavailableCause.PROVIDER_ERROR, Optional.empty());
        } else {
            Instant started = Instant.now(clock);
            answer = provider.get().pull(new CreditDataPull(row.reference(), row.partyId().toString(), row.product()));
            observer.called(row.kind(), row.providerCode(), Duration.between(started, Instant.now(clock)));
        }
        return transactions.inTransaction(uow -> record(uow, id, answer, correlation));
    }

    private CreditDataRequestStatus record(
            Connection uow, CreditDataRequestId id, CreditDataAnswer answer, CorrelationId correlation) {
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
            case CreditDataAnswer.Received received -> collected(uow, row, attempt, received.providerCode(),
                    received.normaliserVersion(), true, received.retrievedAt(), received.attributes(),
                    received.evidence().bytes(), "RECEIVED", correlation);
            case CreditDataAnswer.Partial partial -> collected(uow, row, attempt, partial.providerCode(),
                    partial.normaliserVersion(), false, partial.retrievedAt(), partial.attributes(),
                    partial.evidence().bytes(), "PARTIAL", correlation);
            case CreditDataAnswer.Unavailable unavailable -> {
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
        if (!providerCode.equals(row.providerCode())) {
            // INV-CRD-07: the record names the provider its request was born naming, or nothing is recorded.
            throw new IllegalStateException("an answer from provider " + providerCode + " for a request born naming "
                    + row.providerCode());
        }
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

    private static Optional<byte[]> evidenceOf(CreditDataAnswer answer) {
        return switch (answer) {
            case CreditDataAnswer.Received received -> Optional.of(received.evidence().bytes());
            case CreditDataAnswer.Partial partial -> Optional.of(partial.evidence().bytes());
            case CreditDataAnswer.Unavailable unavailable -> unavailable.evidence().map(CreditEvidence::bytes);
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
