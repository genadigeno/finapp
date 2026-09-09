package com.finapp.app.kyc;

import com.finapp.kyc.DocumentBytes;
import com.finapp.kyc.DocumentContentType;
import com.finapp.kyc.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/me/kyc/documents}.
 *
 * <h2>The types are enums, not strings, and the boundary is where that is decided</h2>
 *
 * <p>Jackson refuses a value outside {@link DocumentType} or {@link DocumentContentType} before
 * the handler is entered, so a foreign type is {@code api.MalformedRequest} rather than an
 * exception rendered {@code api.InternalError} — and the permitted values land in the published
 * contract, so a client generator produces enums rather than free strings
 * ({@code RoleAssignmentRequest}'s reasoning, verbatim).
 *
 * <h2>The content is base64 in a JSON body, deliberately not multipart</h2>
 *
 * <p>Multipart would be a second body-parsing stack beside the bounded, validated one every other
 * endpoint uses — a new refusal surface for the error contract to cover, for no capability a
 * 512 KiB document needs. The base64 bound below is {@link DocumentBytes#MAX_BYTES} × 4/3 plus
 * padding slack, so the string bound and the byte bound cannot quietly diverge; the decoded bytes
 * are bounded again by {@code DocumentBytes} itself, which owns the real rule.
 *
 * @param documentType what the paper is
 * @param contentType what the bytes are
 * @param content the document, base64-encoded
 */
public record DocumentUploadRequest(
        @NotNull DocumentType documentType,
        @NotNull DocumentContentType contentType,
        @NotBlank @Size(max = MAX_BASE64_LENGTH) String content) {

    /** {@code MAX_BYTES} base64-inflated: ceil(524288 / 3) × 4 = 699052, padded up. */
    public static final int MAX_BASE64_LENGTH = ((DocumentBytes.MAX_BYTES + 2) / 3) * 4;
}
