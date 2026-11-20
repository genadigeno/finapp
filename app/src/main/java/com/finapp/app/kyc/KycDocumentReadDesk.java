package com.finapp.app.kyc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.DocumentAccess;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentId;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDocument;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The KYC reviewer's documents (`P17-TSK-125`; the owner's D-25 decision 2, finapp-admin-panel B6.6 item 2, gap G-62): a case's
 * documents listed by identifier, type, size and when each was received, and one document's content served to be saved - never
 * rendered (the console's §20: no client-side rendering of an untrusted file).
 *
 * <p><strong>The content is read through {@link DocumentAccess} and nothing else</strong> ({@code INV-KYC-06}), which writes
 * {@code kyc.DocumentContentRead} - the reviewer, the document, the case, the correlation - in the read's own unit of work. That
 * record <em>is</em> this read's: a second one beside it would split the trail of who looked across two codes. The transaction
 * commits before {@link #content} returns, so no byte reaches the wire unless its record committed first; a record that cannot
 * be written, a commit that fails, a checksum that does not verify, a ciphertext that does not decrypt and a declared type off
 * the allow-list all end the same way: nothing served, nothing recorded.
 *
 * <p><strong>The allow-list.</strong> A document is served only under a declared type the platform names - PDF, JPEG, PNG - with
 * the media type and the file extension it is saved under. {@link DocumentContentType} is closed today, so nothing else can be
 * stored; the list exists so that widening what an upload accepts does not silently widen what a reviewer's browser is handed.
 *
 * <p>Each answer is one {@code REPEATABLE READ} transaction of its own ({@code operatorReadTransactions}): the case's existence
 * and its page describe one instant, and the page's record ({@code kyc.CaseDocumentsListed}, {@link RecordedReads}) commits
 * with it. A malformed or unknown identifier is {@code 404}, recording nothing - nothing was served.
 */
public final class KycDocumentReadDesk {

    static final String DOCUMENTS = "kyc.case-documents";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** The declared types a document is served under, each with its media type and file extension: PDF, JPEG, PNG. */
    static final Map<DocumentContentType, Served> ALLOW_LIST = Map.of(
            DocumentContentType.PDF, new Served("application/pdf", "pdf"),
            DocumentContentType.JPEG, new Served("image/jpeg", "jpg"),
            DocumentContentType.PNG, new Served("image/png", "png"));

    private final DocumentStore<Connection> documents;
    private final DocumentAccess documentAccess;
    private final KycCaseStore<Connection> cases;
    private final RecordedReads recordedReads;
    private final KycUnitOfWork units;
    private final Map<DocumentContentType, Served> allowList;

    public KycDocumentReadDesk(
            DocumentStore<Connection> documentStore,
            DocumentAccess documentAccess,
            KycCaseStore<Connection> kycCaseStore,
            RecordedReads recordedReads,
            TransactionTemplate operatorReadTransactions,
            DataSource dataSource) {
        this(documentStore, documentAccess, kycCaseStore, recordedReads, operatorReadTransactions, dataSource, ALLOW_LIST);
    }

    /** With an allow-list of the caller's: a suite shows a type off it refused. Production's is {@link #ALLOW_LIST}. */
    KycDocumentReadDesk(
            DocumentStore<Connection> documentStore,
            DocumentAccess documentAccess,
            KycCaseStore<Connection> kycCaseStore,
            RecordedReads recordedReads,
            TransactionTemplate operatorReadTransactions,
            DataSource dataSource,
            Map<DocumentContentType, Served> allowList) {
        this.documents = Objects.requireNonNull(documentStore, "documentStore must not be null");
        this.documentAccess = Objects.requireNonNull(documentAccess, "documentAccess must not be null");
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.recordedReads = Objects.requireNonNull(recordedReads, "recordedReads must not be null");
        this.units = new KycUnitOfWork(
                Objects.requireNonNull(operatorReadTransactions, "operatorReadTransactions must not be null"),
                Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.allowList = Map.copyOf(Objects.requireNonNull(allowList, "allowList must not be null"));
    }

    // ------------------------------------------------------------------ views

    /** How a declared type is served: its media type, and the extension of the file it is saved as. */
    record Served(String mediaType, String extension) {
        Served {
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(extension, "extension must not be null");
        }
    }

    /** A page of a case's documents: {@code nextCursor} absent on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseDocumentsPage(List<CaseDocument> items, String nextCursor) {}

    /**
     * One document of a case, as metadata: its identifier, what the paper is ({@code PASSPORT}, ...), what the bytes are
     * ({@code PDF}, {@code JPEG}, {@code PNG}), how many bytes, and when it was received. Never its content or checksum.
     */
    public record CaseDocument(String documentId, String documentType, String contentType, int sizeBytes, Instant receivedAt) {

        static CaseDocument of(KycDocument document) {
            return new CaseDocument(document.id().value().toString(), document.type().name(), document.contentType().name(),
                    document.contentLength(), document.uploadedAt());
        }
    }

    /**
     * A document's content, to be saved: the media type and file name it is served under, and the bytes. A per-call value,
     * never retained; {@code toString} renders no byte ({@code INV-AUD-02}).
     */
    public record Download(String documentId, String mediaType, String fileName, byte[] content) {

        public Download {
            Objects.requireNonNull(documentId, "documentId must not be null");
            Objects.requireNonNull(mediaType, "mediaType must not be null");
            Objects.requireNonNull(fileName, "fileName must not be null");
            content = Objects.requireNonNull(content, "content must not be null").clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        /** Metadata only: the bytes are a person's identity document. */
        @Override
        public String toString() {
            return "Download[" + documentId + ", " + mediaType + ", " + content.length + " bytes]";
        }
    }

    // ------------------------------------------------------------------ a case's documents

    /**
     * A page of a case's documents in the order they were received, the document id breaking a tie - a §7 collection,
     * {@code limit} 50 unless named, 200 at most, the opaque {@code cursor} of the page before. Recorded once per page,
     * {@code kyc.CaseDocumentsListed}, the case the target. An unknown case is {@code 404}, recording nothing; a case with no
     * document answers an empty page, recorded.
     */
    public CaseDocumentsPage page(String rawCaseId, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<DocumentStore.DocumentPosition> after = OperatorCollection.key(DOCUMENTS, cursor, 2).map(key -> {
            try {
                return new DocumentStore.DocumentPosition(instant(key.get(0)), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        KycCaseId caseId = KycCaseId.of(uuid(rawCaseId).orElseThrow(() -> notFound("No KYC case matched the given identifier")));
        Optional<List<KycDocument>> rows = units.inTransaction(unitOfWork -> cases.findById(unitOfWork, caseId).map(found -> {
            List<KycDocument> page = documents.documentsOf(unitOfWork, caseId, after, size + 1);
            recordedReads.record(unitOfWork, KycAuditAction.CASE_DOCUMENTS_LISTED, "KycCase", caseId.value().toString(),
                    "continued=" + after.isPresent() + ", served=" + Math.min(size, page.size()));
            return page;
        }));
        List<KycDocument> page = rows.orElseThrow(() -> notFound("No KYC case matched the given identifier"));
        List<KycDocument> served = page.subList(0, Math.min(size, page.size()));
        String next = null;
        if (page.size() > size) {
            KycDocument tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(DOCUMENTS, micros(tail.uploadedAt()), tail.id().value().toString());
        }
        return new CaseDocumentsPage(served.stream().map(CaseDocument::of).toList(), next);
    }

    // ------------------------------------------------------------------ one document's content

    /**
     * One document's content, read through {@link DocumentAccess#read} - its {@code kyc.DocumentContentRead} written in this
     * transaction, which commits before the bytes are returned - and served under its allow-listed type. An unknown document is
     * {@code 404}, recording nothing. A declared type off the allow-list throws inside the transaction, so its record rolls back
     * and nothing is served.
     */
    public Download content(String rawDocumentId) {
        DocumentId documentId = DocumentId.of(uuid(rawDocumentId)
                .orElseThrow(() -> notFound("No KYC document matched the given identifier")));
        Optional<Download> read = units.inTransaction(unitOfWork -> documentAccess.read(unitOfWork, documentId).map(found -> {
            KycDocument document = found.document();
            Served served = allowList.get(document.contentType());
            if (served == null) {
                // Names the document and its declared type, never a byte: an exception's message reaches a log line.
                throw new IllegalStateException("document " + document.id() + " is declared " + document.contentType()
                        + ", which is not on the download's allow-list: nothing is served");
            }
            recordedReads.recordedByItsModule(KycAuditAction.DOCUMENT_CONTENT_READ);
            String id = document.id().value().toString();
            return new Download(id, served.mediaType(), id + "." + served.extension(), found.content());
        }));
        return read.orElseThrow(() -> notFound("No KYC document matched the given identifier"));
    }

    // ------------------------------------------------------------------ plumbing

    /** A canonical UUIDv7, or nothing: a value that is not one names no case and no document (ADR-0013) - a 404, never a 500. */
    private static Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && parsed.version() == 7 && parsed.variant() == 2
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    /** An instant as a cursor carries it: whole microseconds since the epoch - the column's own precision, so none is lost. */
    private static String micros(Instant instant) {
        return Long.toString(ChronoUnit.MICROS.between(Instant.EPOCH, instant));
    }

    private static Instant instant(String micros) {
        return Instant.EPOCH.plus(Long.parseLong(micros), ChronoUnit.MICROS);
    }

    private static ApiException notFound(String detail) {
        return new ApiException(PlatformErrorCode.NOT_FOUND, detail);
    }
}
