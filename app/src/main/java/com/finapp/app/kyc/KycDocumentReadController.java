package com.finapp.app.kyc;

import com.finapp.app.session.RequiresAssurance;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.PermissionName;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The KYC reviewer's document doors (`P17-TSK-125`; the owner's D-25 decision 2, finapp-admin-panel B6.6 item 2, gap G-62): a
 * case's documents, and one document's content to be saved. Under {@code KYC_DOCUMENT_READ}, held by {@code KYC_REVIEWER}
 * alone, each on a {@code MULTI_FACTOR} session - a person's passport is what a stolen password must least buy - and every
 * answer recorded, held at the answer by {@code UnrecordedReadAdvice} since the permission is one of {@code RecordedReads}'.
 *
 * <p><strong>The download is a file to save, never a page to render</strong> (the console's §20; {@code THREAT_MODEL.md} T51):
 * {@code Content-Disposition: attachment} under a file name that is the document's identifier; {@code X-Content-Type-Options:
 * nosniff}, set here as well as by the operator edge, so a browser takes the declared type at its word wherever the answer
 * comes from; and that declared type one of the allow-list's (PDF, JPEG, PNG). The {@code no-store} directive is
 * {@code OperatorResponsesAreNotStored}'s, as on every operator answer - nothing else in the application sets it. The record
 * ({@code kyc.DocumentContentRead}) commits before the body is returned. The handlers' names are distinctive (the springdoc
 * {@code operationId} rule).
 */
@RestController
@RequestMapping(path = "/operator")
@RequiredArgsConstructor
public class KycDocumentReadController {

    /** The header that stops a browser second-guessing the declared type (the WHATWG Fetch standard's one value). */
    static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    static final String NOSNIFF = "nosniff";

    /** What the download may produce: the allow-list's media types ({@code KycDocumentReadDeskTest} holds them equal). */
    static final String PDF = "application/pdf";

    static final String JPEG = "image/jpeg";

    static final String PNG = "image/png";

    @NonNull private final KycDocumentReadDesk desk;

    /**
     * A case's documents in the order received, the document id breaking a tie - a §7 collection, {@code limit} 50 unless named,
     * 200 at most - each its identifier, type, content type, size in bytes and received instant; never content. Every page
     * recorded {@code kyc.CaseDocumentsListed}; an unknown case {@code 404}.
     */
    @GetMapping(path = "/kyc/cases/{id}/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    @RequiresPermission(PermissionName.KYC_DOCUMENT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public KycDocumentReadDesk.CaseDocumentsPage listKycCaseDocuments(
            @PathVariable("id") String caseId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        return desk.page(caseId, cursor, limit);
    }

    /**
     * One document's content, as an attachment under its allow-listed type, over {@code DocumentAccess.read}: recorded
     * {@code kyc.DocumentContentRead} - the reviewer, the document, its case, the correlation - in a transaction committed
     * before any byte is served. An unknown document {@code 404}, recording nothing.
     */
    @GetMapping(path = "/kyc/documents/{id}/content", produces = {PDF, JPEG, PNG})
    @RequiresPermission(PermissionName.KYC_DOCUMENT_READ)
    @RequiresAssurance(AssuranceLevel.MULTI_FACTOR)
    public ResponseEntity<byte[]> readKycDocumentContent(@PathVariable("id") String documentId) {
        KycDocumentReadDesk.Download download = desk.content(documentId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.fileName()).build().toString())
                .header(CONTENT_TYPE_OPTIONS, NOSNIFF)
                .body(download.content());
    }
}
