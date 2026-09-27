package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;

/**
 * What a dispute evidence document's bytes are (`P7-TSK-014`) — the closed set of formats the
 * platform accepts, {@code DocumentContentType}'s set and reasoning restated for disputes: a
 * store that accepts any declared media type accepts {@code text/html} and
 * {@code image/svg+xml}, and the first component that renders a document to a reviewer then
 * executes whatever was uploaded.
 *
 * <p><strong>The declaration is not verified against the bytes</strong>, recorded rather than
 * implied (the KYC precedent): the platform never renders or executes evidence; it stores it
 * encrypted, returns it to the authorised, and forwards it to the network labelled.
 */
@RequiredArgsConstructor
public enum DisputeEvidenceContentType {
    JPEG("image/jpeg"),
    PNG("image/png"),
    PDF("application/pdf");

    private final String mediaType;

    /** The IANA media type — the representment wire's label for the bytes. */
    public String mediaType() {
        return mediaType;
    }

    /** The quoted, comma-separated value list `V022`'s {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
