package com.finapp.platform.idempotency;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of a command, stored so a retry receives what the first caller received.
 *
 * <p>Bytes rather than a parsed structure: {@code INV-IDEM-01} promises the <em>original</em>
 * outcome, and re-serialising can differ in field order, numeric formatting or encoding.
 * "Almost the same response" is not the promise.
 *
 * <p>An empty body is legitimate — a command can succeed with no content — but a body without
 * a media type cannot be replayed correctly, so the two travel together or not at all.
 */
public record StoredResponse(byte[] body, String mediaType) {

    public StoredResponse {
        if ((body == null) != (mediaType == null)) {
            throw new IllegalArgumentException(
                    "A response body and its media type must be present together or absent together");
        }
        body = body == null ? null : body.clone();
    }

    /** A command that completed with no content to replay. */
    public static StoredResponse empty() {
        return new StoredResponse(null, null);
    }

    public static StoredResponse of(byte[] body, String mediaType) {
        Objects.requireNonNull(body, "body must not be null; use empty() for no content");
        Objects.requireNonNull(mediaType, "mediaType must not be null when a body is present");
        return new StoredResponse(body, mediaType);
    }

    public Optional<byte[]> bodyBytes() {
        return Optional.ofNullable(body).map(byte[]::clone);
    }

    public Optional<String> contentType() {
        return Optional.ofNullable(mediaType);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StoredResponse other
                && Arrays.equals(body, other.body)
                && Objects.equals(mediaType, other.mediaType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(body) + Objects.hashCode(mediaType);
    }

    @Override
    public String toString() {
        return "StoredResponse[" + (body == null ? "no content" : body.length + " bytes " + mediaType) + "]";
    }
}
