package com.finapp.settlement;

import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The accept leg (`P8-TSK-009`, ADR-0065 §2, ADR-0066 §9's acceptance half): an eligible
 * {@code PARSED} batch is recognised ONCE, from its own stored evidence, in ONE transaction —
 * the gapless source sequence, the run, items, keys and remittance expectation through
 * {@link AcceptedBatchIntake}, the recognition posting, then the batch's and file's
 * {@code ACCEPTED} edges, the audit record and {@code settlement.SettlementBatchAccepted}.
 * All or nothing: a crash leaves the file {@code PARSED} for any instance to re-claim, and a
 * rolled-back acceptance releases its sequence number, which is what makes the sequence
 * gapless.
 *
 * <h2>Once, by four arbiters</h2>
 *
 * <p>The conditional {@code PARSED → ACCEPTED}, {@code UNIQUE run(batch_id)},
 * {@code UNIQUE external_item(settlement_line_id)} and the posting key
 * {@code settlement-batch:<batchId>} — whose fingerprint binds only STORED dates
 * ({@code posting_date = accepted_on}, {@code value_date} = the batch's business date), so a
 * replay on a later clock day CONVERGES instead of conflicting ({@code INV-SET-04},
 * {@code INV-IDEM-02}).
 *
 * <h2>The posting's seat (the design's D3, recorded)</h2>
 *
 * <p>The recognition posts BEFORE the batch's accepting {@code UPDATE}, because `V004`'s
 * honesty {@code CHECK} requires the entry id in that same statement. The posting stays the
 * last CONTENDED write: every row after it — the batch, the file — is one this transaction
 * already exclusively claimed, so no new lock edge exists and the multi-entry order holds in
 * substance (`DISTRIBUTED_EXECUTION.md` §3's row records this).
 *
 * <h2>Eligibility is authentication</h2>
 *
 * <p>A pull is authenticated by its channel; an upload moves money only past its second
 * person — the predicate in the claim query, this class's re-read, and `V002`'s
 * {@code CHECK}s (`INV-SET-07`). A source retired since receipt rejects the file
 * {@code SOURCE_RETIRED}, RETAINED, under the same source lock the sequence uses.
 */
@Slf4j
@RequiredArgsConstructor
public final class BatchAcceptance {

    /** The recognition's idempotency-key prefix — `settlement-batch:<batchId>`. */
    public static final String POSTING_KEY_PREFIX = "settlement-batch:";

    /** The leg's pacing, refused mis-configured at construction. */
    public record Config(int filesPerSweep) {
        public Config {
            if (filesPerSweep < 1) {
                throw new IllegalArgumentException("filesPerSweep must be at least 1");
            }
        }
    }

    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;
    @NonNull private final SettlementSources sources;
    @NonNull private final AcceptedBatchIntake intake;
    @NonNull private final PostingService posting;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final Config config;
    @NonNull private final IntakeOutcomeObserver observer;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionRunner transactions;

    /** One tick's tally — telemetry, never the count of record. */
    public record SweepResult(int candidates, int accepted, int rejected, int failed) {}

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        List<UUID> candidates =
                transactions.inTransaction(
                        uow -> files.dueForAccept(uow, config.filesPerSweep()));
        int accepted = 0;
        int rejected = 0;
        int failed = 0;
        for (UUID fileId : candidates) {
            // Per-file containment: one file's failure never starves the rest, and each
            // file's whole acceptance runs inside ITS OWN stored correlation (D8) - the
            // entry, the audit records and the events carry the file's chain.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
                switch (acceptOne(fileId)) {
                    case ACCEPTED -> accepted++;
                    case REJECTED -> rejected++;
                    case SKIPPED -> {}
                    case FAILED -> failed++;
                }
            }
        }
        return new SweepResult(candidates.size(), accepted, rejected, failed);
    }

    private enum Outcome {
        ACCEPTED,
        REJECTED,
        SKIPPED,
        FAILED
    }

    private Outcome acceptOne(UUID fileId) {
        try {
            return transactions.inTransaction(uow -> handleClaimed(uow, fileId));
        } catch (RuntimeException ourDefect) {
            // Contained per file: the transaction rolled back whole, the file stays PARSED
            // and visibly ages (ADR-0066 §9's stance at the accept leg). The class only.
            log.warn(
                    "accept leg could not process settlement file {}: {}",
                    fileId,
                    ourDefect.getClass().getSimpleName());
            return Outcome.FAILED;
        }
    }

    @SuppressWarnings("try")
    private Outcome handleClaimed(Connection uow, UUID fileId) {
        Optional<SettlementFileStore.FileRow> claimed = files.lockEligibleById(uow, fileId);
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED; // Another instance holds it, or eligibility lapsed.
        }
        SettlementFileStore.FileRow file = claimed.get();
        SettlementBatchStore.BatchRow batch =
                batches.batchByFileId(uow, fileId)
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "a PARSED file has a batch (INV-SET-07):"
                                                        + " none found for " + fileId));
        if (batch.status() != BatchStatus.PARSED) {
            return Outcome.SKIPPED;
        }
        // The file's own chain (D8): correlation the stored one, causation the file.
        Correlation correlation =
                new Correlation(
                        CorrelationId.of(file.correlation().value()),
                        CausationId.of(fileId.toString()));
        try (CorrelationContext.Scope scope = CorrelationContext.enter(correlation)) {
            return accept(uow, file, batch, correlation);
        }
    }

    private Outcome accept(
            Connection uow,
            SettlementFileStore.FileRow file,
            SettlementBatchStore.BatchRow batch,
            Correlation correlation) {
        Instant now = clock.instant();
        Actor actor = SecurityContext.require();

        // The source, under the sequence's own lock: retirement is judged here too.
        SettlementFileStore.SourceRow source =
                files.sourceByIdForUpdate(uow, file.sourceId())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "source " + file.sourceId()
                                                        + " is seeded (V002)"));
        if (!source.active()) {
            return rejectRetired(uow, file, batch, actor, now, correlation);
        }
        SettlementSourceDescriptor declared =
                sources.byCode(source.code())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "source '" + source.code()
                                                        + "' is declared (INV-SET-05)"));

        long sequence = files.claimNextSequence(uow, file.sourceId());
        LocalDate acceptedOn = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate valueDate = batch.businessDate(); // D2: the batch's stored value date.

        // The evidence, whole, and its arithmetic - deterministic over stored rows.
        List<SettlementBatchStore.LineRow> lines = batches.linesOf(uow, batch.id());
        var positionAccount =
                accounts.findOperational(
                                uow,
                                declared.settledPosition()
                                        .orElseThrow(
                                                () ->
                                                        new SettlementStorageException(
                                                                "a report source declares"
                                                                        + " its position"
                                                                        + " (INV-SET-05)")),
                                batch.currency())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "the chart seeds every position per"
                                                        + " currency"));
        var costsAccount =
                accounts.findOperational(
                                uow,
                                com.finapp.ledger.AccountPurpose.PROCESSING_COSTS,
                                batch.currency())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "V016 seeds PROCESSING_COSTS per currency"));
        BatchRecognition.Recognition recognition =
                BatchRecognition.recognise(
                        lines,
                        batch.currency(),
                        batch.netScale(),
                        costsAccount.id(),
                        positionAccount.id());

        // The intake: run, items, keys and the remittance, on this same connection.
        Money net = Money.ofPersisted(batch.netMinor(), batch.currency(), batch.netScale());
        AcceptedBatchIntake.Intaken intaken =
                intake.intake(
                        uow,
                        new AcceptedBatchIntake.AcceptedBatch(
                                batch.id(),
                                file.id(),
                                file.sourceId(),
                                declared.settledPosition().orElseThrow(),
                                batch.businessDate(),
                                valueDate,
                                acceptedOn,
                                sequence,
                                batch.remittanceReference(),
                                net,
                                canonicalLines(lines),
                                actor,
                                now,
                                correlation));

        // The recognition - the last CONTENDED write (D3); dates STORED, never the clock's
        // beyond accepted_on, so a later-day replay converges (INV-SET-04).
        Optional<UUID> entryId = Optional.empty();
        if (!recognition.postingOmitted()) {
            PostingResult posted =
                    posting.post(
                            uow,
                            new PostingCommand(
                                    POSTING_KEY_PREFIX + batch.id(),
                                    acceptedOn,
                                    valueDate,
                                    batch.id().toString(),
                                    recognition.entryLines()));
            entryId = Optional.of(posted.entryId().value());
        }

        // The two edges, on rows this transaction already claimed.
        if (!batches.markAccepted(uow, batch.id(), sequence, acceptedOn, entryId, now)) {
            throw new SettlementStorageException(
                    "batch " + batch.id() + " moved under a held claim");
        }
        batches.appendBatchEvent(
                uow,
                batch.id(),
                BatchStatus.PARSED,
                BatchStatus.ACCEPTED,
                actor,
                Optional.empty(),
                now,
                correlation.correlationId());
        if (!files.markFileAccepted(uow, file.id(), now)) {
            throw new SettlementStorageException(
                    "file " + file.id() + " moved under a held claim");
        }
        files.appendFileEvent(
                uow,
                file.id(),
                FileStatus.PARSED,
                FileStatus.ACCEPTED,
                actor,
                Optional.empty(),
                now,
                correlation.correlationId());

        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        SettlementAuditAction.SETTLEMENT_BATCH_ACCEPTED,
                        "settlement_batch",
                        batch.id().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and counts only - never an amount (INV-AUD-02).
                        Optional.of(
                                "source=" + source.code()
                                        + ", sequence=" + sequence
                                        + ", items=" + intaken.items()
                                        + ", remittance=" + intaken.remittanceOpened()
                                        + ", postingOmitted="
                                        + recognition.postingOmitted())));
        SettlementFileEvents.accepted(
                outbox,
                uow,
                ids,
                batch.id(),
                file.id(),
                file.sourceId(),
                sequence,
                lines.size(),
                entryId,
                now,
                correlation);
        return Outcome.ACCEPTED;
    }

    /** The retired source's verdict: rejected, RETAINED, under the same source lock. */
    private Outcome rejectRetired(
            Connection uow,
            SettlementFileStore.FileRow file,
            SettlementBatchStore.BatchRow batch,
            Actor actor,
            Instant now,
            Correlation correlation) {
        if (!files.markRejected(
                uow,
                file.id(),
                FileStatus.PARSED,
                RejectionCode.SOURCE_RETIRED,
                Optional.of(RejectionCode.SOURCE_RETIRED.name()),
                now)) {
            return Outcome.SKIPPED;
        }
        if (!batches.markBatchRejected(
                uow, batch.id(), actor, Optional.empty(), now,
                correlation.correlationId())) {
            throw new SettlementStorageException(
                    "batch " + batch.id() + " moved under a held claim");
        }
        files.appendFileEvent(
                uow,
                file.id(),
                FileStatus.PARSED,
                FileStatus.REJECTED,
                actor,
                Optional.of(RejectionCode.SOURCE_RETIRED.name()),
                now,
                correlation.correlationId());
        SettlementFileEvents.rejected(
                outbox,
                uow,
                ids,
                file.id(),
                file.sourceId(),
                RejectionCode.SOURCE_RETIRED,
                now,
                correlation);
        observer.rejected(
                file.sourceCode(),
                RejectionCode.SOURCE_RETIRED,
                java.time.Duration.between(file.receivedAt(), now));
        return Outcome.REJECTED;
    }

    private static List<AcceptedBatchIntake.CanonicalLine> canonicalLines(
            List<SettlementBatchStore.LineRow> lines) {
        return lines.stream()
                .map(
                        line ->
                                new AcceptedBatchIntake.CanonicalLine(
                                        line.id(),
                                        line.lineNo(),
                                        line.lineType(),
                                        line.direction(),
                                        Money.ofPersisted(
                                                line.amountMinor(),
                                                line.currency(),
                                                line.scale()),
                                        line.businessDate(),
                                        line.settlementDate(),
                                        line.valueDate(),
                                        line.canonicalFingerprint(),
                                        line.references()))
                .toList();
    }

}
