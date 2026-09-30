package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.settlement.format.FormatDefect;
import com.finapp.settlement.format.ParsedBatch;
import com.finapp.settlement.format.ParsedLine;
import com.finapp.settlement.format.SettlementFormat;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The parse leg (`P8-TSK-008`, ADR-0066 §9, `INV-SET-07`): a {@code RECEIVED} file becomes
 * one whole canonical batch, or is rejected whole with its errors recorded — in ONE
 * transaction per file — and our own failure never rejects evidence.
 *
 * <h2>The three verdicts, and whose defect each one is</h2>
 *
 * <ul>
 *   <li><strong>{@code PARSED}</strong> — the batch, its lines, references, folded totals,
 *       the file's edge and its history commit together.
 *   <li><strong>{@code REJECTED}</strong> — the counterparty's defect: up to 100 error rows
 *       (codes, lines, field names — never a value), the verdict, the history, the audit
 *       record and {@code settlement.SettlementFileRejected} commit together.
 *   <li><strong>Left {@code RECEIVED}</strong> — OUR defect (an adapter exception, a missing
 *       format version, a stored file failing its checksum or associated data):
 *       {@code parse_failures + 1} and a backed-off {@code next_parse_at} commit in a second,
 *       small transaction that survives the first one's rollback, with a
 *       {@code RECEIVED → RECEIVED} history row — the file's own record of the platform's
 *       processing. The stuck file is loud on {@code finapp.settlement.file.age}.
 * </ul>
 *
 * <h2>Ten instances</h2>
 *
 * <p>Candidates are read without a lock; the claim is {@code FOR UPDATE SKIP LOCKED} on the
 * still-{@code RECEIVED} row inside each file's own transaction, and the conditional
 * {@code RECEIVED → PARSED} plus {@code batch.file_id UNIQUE} and
 * {@code UNIQUE (file_id, line_no)} arbitrate even without the lock. Two FILES declaring one
 * batch race on the live unique: the pre-check answers the common case, the unique's
 * violation ({@link SettlementBatchStore.LiveBatchConflict}) kills the loser's transaction,
 * and a fresh transaction re-claims the file and rejects it {@code CONFLICTING_BATCH} — the
 * file retained, its key never taken.
 */
@Slf4j
@RequiredArgsConstructor
public final class FileParsing {

    /** The sweep's pacing, refused mis-configured at construction. */
    public record Config(int filesPerSweep, Duration backoffBase, Duration backoffCap) {

        public Config {
            Objects.requireNonNull(backoffBase, "backoffBase must not be null");
            Objects.requireNonNull(backoffCap, "backoffCap must not be null");
            if (filesPerSweep < 1) {
                throw new IllegalArgumentException("filesPerSweep must be at least 1");
            }
            if (backoffBase.isZero() || backoffBase.isNegative()) {
                throw new IllegalArgumentException("backoffBase must be positive");
            }
            if (backoffCap.compareTo(backoffBase) < 0) {
                throw new IllegalArgumentException("backoffCap must be at least backoffBase");
            }
        }
    }

    @NonNull private final SettlementFileStore<Connection> files;
    @NonNull private final SettlementBatchStore<Connection> batches;

    /** Frozen versions by family; a file recorded under a version this map lacks is OUR gap. */
    @NonNull private final Map<SettlementFormatId, SettlementFormat> formats;

    @NonNull private final Config config;
    @NonNull private final IntakeOutcomeObserver observer;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final TransactionRunner transactions;

    /** The compiled register a bank line is attributed through (`P8-TSK-016`). */
    @NonNull private final SettlementSources sources;

    /** One tick's tally — telemetry, never the count of record. */
    public record SweepResult(int candidates, int parsed, int rejected, int failed) {}

    @SuppressWarnings("try") // The Scopes are used for their close side effects (the idiom).
    public SweepResult sweep() {
        Instant now = clock.instant();
        List<UUID> candidates =
                transactions.inTransaction(
                        uow -> files.dueForParse(uow, now, config.filesPerSweep()));
        int parsed = 0;
        int rejected = 0;
        int failed = 0;
        for (UUID fileId : candidates) {
            // Per-file containment: one file's failure never starves the rest.
            try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                    CorrelationContext.Scope scope =
                            CorrelationContext.enter(
                                    Correlation.startingWith(CorrelationId.generate(ids)))) {
                switch (parseOne(fileId)) {
                    case PARSED -> parsed++;
                    case REJECTED -> rejected++;
                    case SKIPPED -> {}
                    case FAILED -> failed++;
                }
            }
        }
        return new SweepResult(candidates.size(), parsed, rejected, failed);
    }

    private enum Outcome {
        PARSED,
        REJECTED,
        SKIPPED,
        FAILED
    }

    private Outcome parseOne(UUID fileId) {
        try {
            return transactions.inTransaction(uow -> handleClaimed(uow, fileId));
        } catch (SettlementBatchStore.LiveBatchConflict conflict) {
            // The unique spoke where the pre-check could not see: the loser's transaction is
            // dead, and a fresh one finds the standing winner and answers honestly.
            return transactions.inTransaction(
                    uow -> rejectClaimed(uow, fileId, RejectionCode.CONFLICTING_BATCH, List.of()));
        } catch (RuntimeException ourDefect) {
            // OUR failure never rejects evidence (ADR-0066 §9). The class only: an adapter
            // or JDBC message can carry file text.
            log.warn(
                    "parse leg could not process settlement file {}: {}",
                    fileId,
                    ourDefect.getClass().getSimpleName());
            transactions.inTransaction(uow -> recordOurFailure(uow, fileId, ourDefect));
            return Outcome.FAILED;
        }
    }

    private Outcome handleClaimed(Connection uow, UUID fileId) {
        Instant now = clock.instant();
        Optional<SettlementFileStore.FileRow> claimed = files.lockDueById(uow, fileId, now);
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED; // Another instance holds it, or it already moved.
        }
        SettlementFileStore.FileRow file = claimed.get();
        SettlementFormat format = formats.get(file.formatId());
        if (format == null || format.version() != file.formatVersion()) {
            // A recorded version this build cannot parse is OUR gap, not the evidence's:
            // the exception path leaves the file RECEIVED and loudly stuck.
            throw new IllegalStateException(
                    "no compiled format parses " + file.formatId() + " v"
                            + file.formatVersion());
        }
        byte[] content = files.readContent(uow, fileId); // Verified, or a loud throw.

        SettlementFormat.Result result = format.parse(content);
        if (result instanceof SettlementFormat.Result.Rejected rejection) {
            return rejectClaimed(uow, fileId, rejection.code(), rejection.defects());
        }
        ParsedBatch parsed = ((SettlementFormat.Result.Parsed) result).batch();
        if (batches.liveBatchStands(
                uow, file.sourceId(), parsed.externalBatchRef(),
                parsed.declaredNet().currency())) {
            return rejectClaimed(uow, fileId, RejectionCode.CONFLICTING_BATCH, List.of());
        }
        if (parsed.statement().isPresent()
                && batches.liveStatementStands(
                        uow, file.sourceId(), parsed.declaredNet().currency(),
                        parsed.statement().get().sequence())) {
            // A second statement of a sequence already live (ADR-0066 §5): retained, refused.
            return rejectClaimed(uow, fileId, RejectionCode.CONFLICTING_BATCH, List.of());
        }
        batches.insertParsedBatch(
                uow,
                new SettlementBatchStore.NewBatch(
                        ids.next(),
                        fileId,
                        file.sourceId(),
                        file.formatId(),
                        file.formatVersion(),
                        parsed,
                        totalsOf(parsed),
                        SecurityContext.require(),
                        now,
                        correlation().correlationId(),
                        attributionsOf(uow, parsed)));
        if (!files.markParsed(uow, fileId, now)) {
            // Unreachable while we hold the claim; stated so a lock-less probe still
            // converges instead of writing a batch beside a moved file.
            throw new SettlementStorageException(
                    "settlement file " + fileId + " moved under a held claim");
        }
        files.appendFileEvent(
                uow,
                fileId,
                FileStatus.RECEIVED,
                FileStatus.PARSED,
                SecurityContext.require(),
                Optional.empty(),
                now,
                correlation().correlationId());
        Duration sinceReceipt = Duration.between(file.receivedAt(), now);
        observer.parsed(file.sourceCode(), sinceReceipt);
        return Outcome.PARSED;
    }

    /** The rejecting transaction: errors, verdict, history, audit, event — together. */
    private Outcome rejectClaimed(
            Connection uow, UUID fileId, RejectionCode code, List<FormatDefect> defects) {
        Instant now = clock.instant();
        Optional<SettlementFileStore.FileRow> claimed = files.lockDueById(uow, fileId, now);
        if (claimed.isEmpty()) {
            return Outcome.SKIPPED;
        }
        SettlementFileStore.FileRow file = claimed.get();
        if (!defects.isEmpty()) {
            files.recordIngestionErrors(uow, fileId, defects);
        }
        if (!files.markRejected(
                uow, fileId, FileStatus.RECEIVED, code, detailOf(code, defects), now)) {
            return Outcome.SKIPPED;
        }
        Actor actor = SecurityContext.require();
        Correlation correlation = correlation();
        files.appendFileEvent(
                uow,
                fileId,
                FileStatus.RECEIVED,
                FileStatus.REJECTED,
                actor,
                Optional.of(code.name()),
                now,
                correlation.correlationId());
        // Acting-only, the platform's own verdict (INV-AUD-04's process rank).
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        SettlementAuditAction.SETTLEMENT_FILE_REJECTED,
                        "settlement_file",
                        fileId.toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "source=" + file.sourceCode()
                                        + ", code=" + code
                                        + ", errors=" + defects.size())));
        SettlementFileEvents.rejected(
                outbox, uow, ids, fileId, file.sourceId(), code, now, correlation);
        observer.rejected(
                file.sourceCode(), code, Duration.between(file.receivedAt(), now));
        return Outcome.REJECTED;
    }

    /** The failure's own record — a second, small transaction after the rollback. */
    private Outcome recordOurFailure(Connection uow, UUID fileId, RuntimeException ourDefect) {
        int failures = files.bumpParseFailures(uow, fileId);
        long factor = 1L << Math.min(failures - 1, 20);
        Duration backoff = config.backoffBase().multipliedBy(factor);
        if (backoff.compareTo(config.backoffCap()) > 0) {
            backoff = config.backoffCap();
        }
        Instant next = clock.instant().plus(backoff);
        files.scheduleNextParse(uow, fileId, next);
        files.appendFileEvent(
                uow,
                fileId,
                FileStatus.RECEIVED,
                FileStatus.RECEIVED,
                SecurityContext.require(),
                Optional.of("parse failed: " + ourDefect.getClass().getSimpleName()),
                clock.instant(),
                correlation().correlationId());
        return Outcome.FAILED;
    }

    /**
     * Each bank credit's and debit's attributed source by line number (`P8-TSK-016`, ADR-0065
     * §3): the unique declared source whose compiled remittance pattern fully matches the line's
     * {@code REMITTANCE_REF}. A line zero or two patterns match — or with no reference — is left
     * out: unattributed, parked owned at acceptance. A report has nothing to attribute.
     */
    private Map<Integer, UUID> attributionsOf(Connection uow, ParsedBatch parsed) {
        if (parsed.statement().isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> sourceIds = new java.util.HashMap<>();
        files.sources(uow).forEach(source -> sourceIds.put(source.code(), source.id()));
        Map<Integer, UUID> attributions = new java.util.HashMap<>();
        for (ParsedLine line : parsed.lines()) {
            if (line.type() != SettlementLineType.BANK_CREDIT
                    && line.type() != SettlementLineType.BANK_DEBIT) {
                continue;
            }
            Optional.ofNullable(line.references().get(LineReferenceKind.REMITTANCE_REF))
                    .flatMap(sources::attribute)
                    .ifPresent(
                            source -> {
                                UUID sourceId = sourceIds.get(source.code());
                                if (sourceId == null) {
                                    // A declared source without its seeded row is OUR gap: the
                                    // file stays RECEIVED, loudly (ADR-0066 §9).
                                    throw new IllegalStateException(
                                            "declared source " + source.code()
                                                    + " has no settlement.source row");
                                }
                                attributions.put(line.lineNo(), sourceId);
                            });
        }
        return Map.copyOf(attributions);
    }

    /** The `Money` fold per (type, direction) — never a SQL {@code SUM} (`INV-MON-01`). */
    public static List<SettlementBatchStore.TotalRow> totalsOf(ParsedBatch parsed) {
        Map<SettlementLineType, Map<LineDirection, Money>> sums =
                new EnumMap<>(SettlementLineType.class);
        Map<SettlementLineType, Map<LineDirection, Long>> counts =
                new EnumMap<>(SettlementLineType.class);
        for (ParsedLine line : parsed.lines()) {
            sums.computeIfAbsent(line.type(), t -> new EnumMap<>(LineDirection.class))
                    .merge(line.direction(), line.amount(), Money::plus);
            counts.computeIfAbsent(line.type(), t -> new EnumMap<>(LineDirection.class))
                    .merge(line.direction(), 1L, Long::sum);
        }
        List<SettlementBatchStore.TotalRow> totals = new ArrayList<>();
        sums.forEach(
                (type, byDirection) ->
                        byDirection.forEach(
                                (direction, amount) ->
                                        totals.add(
                                                new SettlementBatchStore.TotalRow(
                                                        type,
                                                        direction,
                                                        counts.get(type).get(direction),
                                                        amount.minorUnits(),
                                                        amount.scale()))));
        return List.copyOf(totals);
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("the sweep enters a correlation"));
    }

    private static Optional<String> detailOf(RejectionCode code, List<FormatDefect> defects) {
        if (defects.isEmpty()) {
            return Optional.of(code.name());
        }
        // Codes, lines and field NAMES only - never a value (ADR-0066 §9).
        FormatDefect first = defects.get(0);
        return Optional.of(
                code.name()
                        + ": "
                        + defects.size()
                        + " error(s), first at line "
                        + first.lineNo().map(String::valueOf).orElse("-")
                        + first.field().map(field -> " (" + field + ")").orElse(""));
    }
}
