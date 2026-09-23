package com.finapp.app.kyc;

import com.finapp.app.session.RequiresSession;
import com.finapp.app.session.SessionAuthenticationInterceptor;
import com.finapp.identity.Session;
import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.KycErrorCode;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Base64;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Uploading a document onto one's own open KYC case (`P2-TSK-008`).
 *
 * <h2>No identifier, anywhere in the request</h2>
 *
 * <p>No path variable, no query parameter, no body field names a case or a customer — the
 * {@code /v1/me} shape (`P1-TSK-030`): the chain is derived from the proven session, so an
 * attacker has nothing to point at somebody else's case. See {@link DocumentUploadService}.
 *
 * <h2>There is no download endpoint, and that is the plan rather than a gap</h2>
 *
 * <p>The customer holds the original; returning stored content to them would be a read surface
 * with no consumer need. The one read path is {@code DocumentAccess}, audited per read
 * ({@code INV-KYC-06}), whose first HTTP caller is the reviewer surface (`P2-TSK-012`).
 */
@RestController
@RequestMapping(path = "/me/kyc/documents", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresSession
@RequiredArgsConstructor
public class KycDocumentController {

    @NonNull private final DocumentUploadService uploads;

    /**
     * Uploads a document.
     *
     * <p><strong>{@code 201} for the created and the converged upload alike.</strong> A retry
     * after a lost response and a first upload are the same intent — "ensure this document is on
     * my case" — and distinguishing them tells a caller nothing actionable
     * ({@code openOrConverge}'s semantics at the contract).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentUploadResponse uploadDocument(
            @Valid @RequestBody DocumentUploadRequest body, HttpServletRequest request) {
        return switch (uploads.upload(
                current(request), body.documentType(), body.contentType(), decode(body.content()))) {
            case DocumentUploadService.Result.Uploaded uploaded ->
                    new DocumentUploadResponse(uploaded.document().id().value().toString());
            case DocumentUploadService.Result.NoOpenCase ignored ->
                    throw new ApiException(
                            KycErrorCode.NO_OPEN_CASE,
                            "A document upload found no open case for the caller's customer");
            case DocumentUploadService.Result.NotResolvable ignored ->
                    // The MeController not-resolvable case: a data defect, never producible by
                    // registration, logged as ours because a customer cannot fix it.
                    throw new ApiException(
                            PlatformErrorCode.NOT_FOUND,
                            "A proven session resolved to no live customer; registration should"
                                    + " make this impossible",
                            "no verification case exists for this session");
        };
    }

    // -----------------------------------------------------------------

    /**
     * Decodes and bounds the content, mapping each refusal to a {@code 422} naming the field.
     *
     * <p>The client detail is written here rather than passed through from the exception, so a
     * future change to a message cannot become a change to what a stranger is told
     * (`P1-TSK-026`'s rule).
     */
    private static DocumentBytes decode(String base64) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException notBase64) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A document upload carried content that is not valid base64",
                    "content must be base64-encoded");
        }
        try {
            return DocumentBytes.of(bytes);
        } catch (IllegalArgumentException outOfBounds) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A document upload was refused by the DocumentBytes bounds",
                    "content must decode to between 1 byte and "
                            + DocumentBytes.MAX_BYTES
                            + " bytes");
        }
    }

    private static Session current(HttpServletRequest request) {
        Object session = request.getAttribute(SessionAuthenticationInterceptor.CURRENT_SESSION);
        if (session instanceof Session authenticated) {
            return authenticated;
        }
        // Unreachable while the interceptor is registered and the class carries @RequiresSession.
        throw new IllegalStateException(
                "No authenticated session on the request: /v1/me/kyc/documents is reachable"
                        + " without SessionAuthenticationInterceptor having run");
    }

    /** What an upload publishes: the document's identifier, for the caller's own records. */
    public record DocumentUploadResponse(String documentId) {}
}
