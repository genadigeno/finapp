package com.finapp.kyc;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one audited read path for document content (`P2-TSK-008`, {@code INV-KYC-06}).
 *
 * <h2>The trail of who looked is the control</h2>
 *
 * <p>Identity documents are the most sensitive bytes the platform holds before card data, and
 * the threat a permission wall cannot answer is the <em>legitimate</em> reader: a reviewer with
 * every right to read documents, browsing them. {@code INV-KYC-06}'s answer is that every read of
 * content produces an audit record naming the actor — so the deterrent is not that looking is
 * impossible but that looking is <strong>on the record</strong>, permanently, under
 * {@code INV-HIST-03}.
 *
 * <h2>The record commits with the read, or neither happens</h2>
 *
 * <p>The audit write rides the same unit of work as the read. A read whose record could fail to
 * commit afterwards would be exactly the unrecorded look the invariant forbids; here the
 * transaction that would deliver content without its trail cannot commit the trail's absence —
 * the caller's transaction either holds both or is rolled back.
 *
 * <h2>The actor is whoever is looking, never the platform</h2>
 *
 * <p>{@code SecurityContext.require()} rather than {@code enterSystem()}: reading a document is
 * always somebody's act — a reviewer's, in `P2-TSK-012`'s surface — and a record naming the
 * platform would be the trail losing the one fact it exists to hold. A caller with no established
 * actor is refused, which is {@code P0-TSK-032}'s design doing its job.
 *
 * <h2>No production caller yet, and that is the plan</h2>
 *
 * <p>The reviewer surface (`P2-TSK-012`) is this class's first HTTP caller; the customer gets no
 * download endpoint at all (no plan row gives them one). Building the read path <em>here</em> is
 * what makes "content readable only through the audited path" true from the first day content
 * exists, rather than a property retrofitted around an unaudited read somebody already shipped.
 */
@RequiredArgsConstructor
public final class DocumentAccess {

    @NonNull private final DocumentStore<Connection> documents;
    @NonNull private final AuditWriter<Connection> auditWriter;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * Reads document content, writing the {@code kyc.DocumentContentRead} record in the same unit
     * of work.
     *
     * <p>A read of a document that does not exist writes <strong>no record</strong>: there is no
     * content to account for, and a trail entry for a guessed identifier would put identifiers
     * that were never real into the permanent record (`P1-TSK-014`'s revocation reasoning).
     */
    public Optional<DocumentStore.DocumentContent> read(Connection unitOfWork, DocumentId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        return documents
                .readContent(unitOfWork, id)
                .map(
                        content -> {
                            audit(unitOfWork, content.document());
                            return content;
                        });
    }

    private void audit(Connection unitOfWork, KycDocument document) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.DOCUMENT_CONTENT_READ,
                        "Document",
                        document.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        CorrelationContext.current()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a document read must run inside a"
                                                            + " correlation scope: the record"
                                                            + " carries the flow's identifier"))
                                .correlationId(),
                        // Which case the document belongs to, so an investigator reads one row
                        // rather than joining. Identifiers only, never content (INV-AUD-02).
                        Optional.of("case=" + document.caseId())));
    }
}
