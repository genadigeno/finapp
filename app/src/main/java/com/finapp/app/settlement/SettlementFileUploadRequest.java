package com.finapp.app.settlement;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * The body of {@code POST /v1/operator/settlement/files} (`P8-TSK-003`, ADR-0066 §1): the
 * source the uploader claims the file comes from, the business date they declare it covers —
 * the counterparty's claim, recorded verbatim; the parse leg reads the file's own dates — and
 * the content, base64.
 *
 * <p><strong>The size rule lives in the domain, deliberately.</strong> This record's bound
 * only mirrors the route's transport bound ({@code RequestSizeLimitFilter}); a delivery whose
 * DECODED content exceeds 8 MiB or 50,000 records must reach {@code FileReception} and be
 * refused as {@code 413 settlement.FileTooLarge} with its audit record written — a validation
 * refusal here would leave no trace of the delivery at all (ADR-0066 §4).
 */
public record SettlementFileUploadRequest(
        @NotBlank @Size(max = 100) String sourceCode,
        @NotNull LocalDate businessDate,
        @NotBlank @Size(max = SettlementFileUploadRequest.MAX_BASE64_LENGTH) String content) {

    /** The transport envelope's own bound — base64 of 8 MiB is ~11.2 MiB; this admits it. */
    public static final int MAX_BASE64_LENGTH = 12_582_912;
}
