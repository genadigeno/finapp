package com.finapp.settlement;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

/**
 * The settlement module's published events (`P8-TSK-008`, `INV-EVT-01`) — its first: one
 * vocabulary class so the parse leg and the decline announce a rejection identically, through
 * the outbox, in the rejecting transaction, with the full envelope and identifiers only.
 */
public final class SettlementFileEvents {

    static final String PRODUCER = "settlement";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "settlement_file";
    static final String REJECTED_EVENT_TYPE = "settlement.SettlementFileRejected";
    static final String ACCEPTED_EVENT_TYPE = "settlement.SettlementBatchAccepted";

    private SettlementFileEvents() {}

    /**
     * Announced on the one {@code PARSED → ACCEPTED} edge (`P8-TSK-009`), in the acceptance
     * transaction: identifiers and counts only — never an amount or a reference — with the
     * source as its UUID (the rejected event's recorded deviation) and the entry id exactly
     * when a recognition posted ({@code posting_omitted} carries no field, honestly absent).
     */
    static void accepted(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID batchId,
            UUID fileId,
            UUID sourceId,
            long sourceSequence,
            int lineCount,
            java.util.Optional<UUID> journalEntryId,
            Instant occurredAt,
            Correlation correlation) {
        EventPayload payload =
                EventPayload.of()
                        .with("batchId", batchId.toString())
                        .with("fileId", fileId.toString())
                        .with("sourceId", sourceId.toString())
                        .with("sourceSequence", String.valueOf(sourceSequence))
                        .with("lineCount", String.valueOf(lineCount));
        journalEntryId.ifPresent(entry -> payload.with("journalEntryId", entry.toString()));
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACCEPTED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        SettlementFileId.of(fileId),
                        TARGET_TYPE,
                        occurredAt,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation
                                .cause()
                                .orElseGet(
                                        () ->
                                                CausationId.of(
                                                        correlation
                                                                .correlationId()
                                                                .value()))),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * Announced on EVERY edge into {@code REJECTED} — the parse leg's and the decline's.
     *
     * <p>The source rides as its UUID, not its code: {@code EventPayload}'s vocabulary is
     * identifiers and enumerated names ({@code INV-AUD-02}), and a dotted source code is
     * neither — the id names the same source, and a consumer resolves the code from the
     * register. (The backlog drafted {@code sourceCode}; this is the recorded deviation.)
     */
    static void rejected(
            OutboxWriter<Connection> outbox,
            Connection unitOfWork,
            IdGenerator ids,
            UUID fileId,
            UUID sourceId,
            RejectionCode code,
            Instant occurredAt,
            Correlation correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        REJECTED_EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        SettlementFileId.of(fileId),
                        TARGET_TYPE,
                        occurredAt,
                        PRODUCER,
                        correlation.correlationId(),
                        // The sweep's flow is a root: its own id stands in as the cause (the
                        // WithdrawalOutcomes.causeOf precedent).
                        correlation
                                .cause()
                                .orElseGet(
                                        () ->
                                                CausationId.of(
                                                        correlation
                                                                .correlationId()
                                                                .value()))),
                EventPayload.of()
                        .with("fileId", fileId.toString())
                        .with("sourceId", sourceId.toString())
                        .with("rejectionCode", code.name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }
}
