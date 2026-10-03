package com.finapp.settlement;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.AuditableAction;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one door settlement evidence enters through (`P8-TSK-002`, ADR-0066 §1) — reached by
 * upload (`P8-TSK-003`) and pull (`P8-TSK-021`), both live. A readmission (`P8-TSK-022`) does
 * not pass through it: {@link FileReadmission} re-reads the original's retained bytes and
 * applies the door's own screen for the source's current format version itself. *(Corrected
 * 2026-10-01, `P8-DOC-001`: this read that readmission reaches the door and that no channel was
 * open yet.)*
 *
 * <h2>The fixed order, and what each step may write</h2>
 *
 * <ol>
 *   <li><strong>The source is known and ACTIVE</strong> — otherwise a plain refusal
 *       ({@link SettlementSourceUnknown}, {@link SettlementSourceRetired}) that writes nothing:
 *       there is no evidence to chain a refusal row to, and the caller mis-spoke.
 *   <li><strong>The bounds hold</strong> — decoded content within 1..8 MiB and 50,000 records
 *       (the screen's own walk counts them). An over-bound delivery stores nothing but its
 *       audit record: the bounds exist because parse and acceptance are each one transaction.
 *   <li><strong>The screen passes</strong> — in memory, before anything is stored
 *       ({@link ConservativeScreen} until the source's format version brings its own,
 *       field-class screen). A dirty delivery writes exactly one
 *       {@code settlement.refused_delivery} metadata row and its audit record — never the
 *       value ({@code INV-PAY-02} and {@code INV-RAIL-03} outrank {@code INV-HIST-02} here).
 *   <li><strong>The content address decides</strong> — the file row, its encrypted chunks, a
 *       {@code NEW} receipt, the birth event and the channel's own audit action commit
 *       together, or the delivery converges on the standing file and appends a
 *       {@code DUPLICATE} receipt and nothing else.
 * </ol>
 *
 * <p><strong>A refusal is a result, never an exception</strong>: it commits rows, and an
 * exception would roll its own evidence back. The caller answers the wire after commit.
 *
 * <h2>Ten instances</h2>
 *
 * <p>The content unique is the arbiter; each loser converges and writes a receipt. Nothing
 * here reads process state, takes a lease or elects anything.
 */
@RequiredArgsConstructor
public final class FileReception<T> {

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<T> store;

    /** `P8-TSK-008`'s seam: a format version's own field-class screen, when one exists. */
    @NonNull private final Map<SettlementFormatId, DeliveryScreen> formatScreens;

    @NonNull private final ReceptionOutcomeObserver observer;
    @NonNull private final AuditWriter<T> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What a channel delivers: the bytes, who delivered them, and the channel's audit word. */
    public record Delivery(
            String sourceCode,
            DeliveryChannel channel,
            byte[] content,
            Optional<LocalDate> businessDate,
            Actor deliveredBy,
            AuditableAction receptionAction,
            Correlation correlation) {

        public Delivery {
            Objects.requireNonNull(sourceCode, "sourceCode must not be null");
            Objects.requireNonNull(channel, "channel must not be null");
            Objects.requireNonNull(content, "content must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(deliveredBy, "deliveredBy must not be null");
            Objects.requireNonNull(receptionAction, "receptionAction must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        /** Identifiers only. */
        @Override
        public String toString() {
            return "Delivery[" + sourceCode + ", " + channel + "]";
        }
    }

    /** The door's verdict — each variant already committed exactly what it names. */
    public sealed interface Result {
        /** The file landed: row, chunks, receipt, birth event and audit, one transaction. */
        record New(UUID fileId) implements Result {}

        /** The bytes already stand: one {@code DUPLICATE} receipt appended, nothing else. */
        record Duplicate(UUID existingFileId) implements Result {}

        /**
         * The door refused: the named reason's rows committed, the value nowhere. The
         * position — present exactly for a screen finding — is what the caller may tell the
         * client (`P8-TSK-003`: the line and field, never the value); an over-bound refusal
         * has none.
         */
        record Refused(
                RefusalReason reason, Optional<Integer> lineNo, Optional<String> fieldName)
                implements Result {
            public Refused {
                Objects.requireNonNull(reason, "reason must not be null");
                Objects.requireNonNull(lineNo, "lineNo must not be null");
                Objects.requireNonNull(fieldName, "fieldName must not be null");
            }
        }
    }

    public Result receive(T unitOfWork, Delivery delivery) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(delivery, "delivery must not be null");
        return observer.spans().within(
                "settlement.receive", Map.of(), () -> received(unitOfWork, delivery));
    }

    private Result received(T unitOfWork, Delivery delivery) {

        // 1. The source: compiled declaration AND seeded identity, or nothing is written.
        SettlementSourceDescriptor declared =
                sources.byCode(delivery.sourceCode())
                        .orElseThrow(() -> new SettlementSourceUnknown(delivery.sourceCode()));
        SettlementFileStore.SourceRow source =
                store.sourceByCode(unitOfWork, delivery.sourceCode())
                        .orElseThrow(
                                () ->
                                        new SettlementStorageException(
                                                "source '" + delivery.sourceCode()
                                                        + "' is declared but not seeded: V002"
                                                        + " seeds every declared source"));
        if (!source.active()) {
            throw new SettlementSourceRetired(delivery.sourceCode());
        }

        byte[] content = delivery.content();
        Instant now = clock.instant();

        // 2. The size bound - before the screen walks anything.
        if (content.length < 1 || content.length > SettlementFile.MAX_CONTENT_LENGTH) {
            return refusedOverBound(
                    unitOfWork, delivery, RefusalReason.FILE_TOO_LARGE, content.length, now);
        }

        // 3. The screen - and its walk is the line count's one source.
        DeliveryScreen screen =
                formatScreens.getOrDefault(declared.format(), ConservativeScreen.INSTANCE);
        DeliveryScreen.Screening screening = screen.screen(content);
        if (screening.lineCount() > SettlementFile.MAX_LINES) {
            return refusedOverBound(
                    unitOfWork, delivery, RefusalReason.TOO_MANY_LINES, content.length, now);
        }
        byte[] contentSha256 = sha256(content);
        if (screening.finding().isPresent()) {
            DeliveryScreen.Finding finding = screening.finding().get();
            store.recordRefusal(
                    unitOfWork,
                    new RefusedDelivery(
                            ids.next(),
                            source.id(),
                            contentSha256,
                            content.length,
                            declared.format(),
                            declared.formatVersion(),
                            finding.reason(),
                            Optional.of(finding.lineNo()),
                            finding.fieldName(),
                            delivery.channel(),
                            delivery.deliveredBy(),
                            now,
                            delivery.correlation().correlationId()));
            auditRefusal(
                    unitOfWork,
                    delivery,
                    now,
                    finding.reason(),
                    "source=" + delivery.sourceCode()
                            + ", reason=" + finding.reason()
                            + ", sha256=" + HexFormat.of().formatHex(contentSha256)
                            + ", line=" + finding.lineNo()
                            + finding.fieldName().map(field -> ", field=" + field).orElse(""));
            observer.refused(delivery.sourceCode(), finding.reason());
            return new Result.Refused(
                    finding.reason(), Optional.of(finding.lineNo()), finding.fieldName());
        }

        // 4. The content address decides.
        SettlementFile file =
                new SettlementFile(
                        ids.next(),
                        source.id(),
                        delivery.channel(),
                        delivery.businessDate(),
                        declared.format(),
                        declared.formatVersion(),
                        contentSha256,
                        content.length,
                        screening.lineCount(),
                        delivery.channel() == DeliveryChannel.PULL
                                ? Optional.empty()
                                : Optional.of(delivery.deliveredBy()),
                        now,
                        delivery.correlation().correlationId());
        SettlementFileStore.Stored stored = store.insert(unitOfWork, file, content);
        if (stored instanceof SettlementFileStore.Stored.Duplicate duplicate) {
            store.appendReceipt(
                    unitOfWork,
                    ids.next(),
                    duplicate.existingFileId(),
                    SettlementFileStore.ReceiptOutcome.DUPLICATE,
                    delivery.channel(),
                    delivery.deliveredBy(),
                    now,
                    delivery.correlation().correlationId());
            observer.received(
                    delivery.sourceCode(), SettlementFileStore.ReceiptOutcome.DUPLICATE);
            return new Result.Duplicate(duplicate.existingFileId());
        }
        UUID fileId = ((SettlementFileStore.Stored.New) stored).fileId();
        store.appendReceipt(
                unitOfWork,
                ids.next(),
                fileId,
                SettlementFileStore.ReceiptOutcome.NEW,
                delivery.channel(),
                delivery.deliveredBy(),
                now,
                delivery.correlation().correlationId());
        store.appendBirthEvent(
                unitOfWork, fileId, delivery.deliveredBy(), now, delivery.correlation().correlationId());
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        delivery.deliveredBy(),
                        now,
                        delivery.receptionAction(),
                        "settlement_file",
                        fileId.toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        delivery.correlation().correlationId(),
                        // Identifiers and the address - never a value, never the business
                        // date's claim (INV-AUD-02).
                        Optional.of(
                                "source=" + delivery.sourceCode()
                                        + ", channel=" + delivery.channel()
                                        + ", sha256="
                                        + HexFormat.of().formatHex(contentSha256))));
        observer.received(delivery.sourceCode(), SettlementFileStore.ReceiptOutcome.NEW);
        return new Result.New(fileId);
    }

    /** Over a bound: the audit record alone — nothing else is built from the bytes. */
    private Result refusedOverBound(
            T unitOfWork, Delivery delivery, RefusalReason reason, int length, Instant now) {
        auditRefusal(
                unitOfWork,
                delivery,
                now,
                reason,
                "source=" + delivery.sourceCode() + ", reason=" + reason + ", length=" + length);
        observer.refused(delivery.sourceCode(), reason);
        return new Result.Refused(reason, Optional.empty(), Optional.empty());
    }

    private void auditRefusal(
            T unitOfWork, Delivery delivery, Instant now, RefusalReason reason, String summary) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        delivery.deliveredBy(),
                        now,
                        SettlementAuditAction.SETTLEMENT_DELIVERY_REFUSED,
                        "settlement_source",
                        delivery.sourceCode(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        delivery.correlation().correlationId(),
                        Optional.of(summary)));
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", impossible);
        }
    }

    /** The caller named a source this build does not declare — nothing was written. */
    public static final class SettlementSourceUnknown extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;
        SettlementSourceUnknown(String code) {
            super("no settlement source declares '" + code + "' (settlement.SourceUnknown)");
        }
    }

    /** The source is retired: it stopped reporting, and its door is closed — nothing written. */
    public static final class SettlementSourceRetired extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;
        SettlementSourceRetired(String code) {
            super("settlement source '" + code + "' is retired (settlement.SourceRetired)");
        }
    }
}
