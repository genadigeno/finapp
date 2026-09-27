package com.finapp.app.payments;

import com.finapp.payments.DisputeEvidenceContent;
import com.finapp.payments.DisputeEvidenceContentType;
import com.finapp.payments.DisputeEvidenceKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /v1/merchant/disputes/'{disputeId}'/evidence} (`P7-TSK-014`) — the KYC
 * upload's shape restated ({@code DocumentUploadRequest}'s reasoning): the types are enums, so a
 * foreign kind or format is {@code api.MalformedRequest} before the handler runs and the
 * permitted values land in the published contract; the content is base64 in a JSON body, bounded
 * as a string by {@link DisputeEvidenceContent#MAX_BYTES} × 4/3 and again, decoded, by the value
 * object that owns the real rule.
 *
 * @param kind what the document is
 * @param contentType what the bytes are
 * @param content the document, base64-encoded
 */
public record DisputeEvidenceUploadRequest(
        @NotNull DisputeEvidenceKind kind,
        @NotNull DisputeEvidenceContentType contentType,
        @NotBlank @Size(max = DisputeEvidenceUploadRequest.MAX_BASE64_LENGTH) String content) {

    /** {@code MAX_BYTES} base64-inflated: ceil(524288 / 3) × 4 = 699052, padded up. */
    public static final int MAX_BASE64_LENGTH = ((DisputeEvidenceContent.MAX_BYTES + 2) / 3) * 4;
}
