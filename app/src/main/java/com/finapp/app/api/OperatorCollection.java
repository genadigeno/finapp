package com.finapp.app.api;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An operator collection's two request rules (`API_CONVENTIONS.md` section 7, finapp-admin-panel ADR-C133 decision 1; first used by
 * `P17-TSK-107`): the {@code limit}, defaulted and bounded per endpoint, refused rather than reduced over its maximum; and the
 * opaque {@code cursor}, which encodes the last row a page served under its collection's name and refuses anything else.
 *
 * <p>The cursor is opaque by contract, not by cryptography: a client never constructs, parses or keeps one, and a forged one buys
 * nothing, because every page is read under the caller's own permission and a cursor only says where in an already-permitted order
 * to resume. Naming the collection inside it means one collection's cursor handed to another is refused, never misread as a
 * position in a different order. Every refusal is {@code 422 api.ValidationFailed}, nothing read.
 */
public final class OperatorCollection {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,4}");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z.-]{0,63}");

    private OperatorCollection() {}

    /** The page size asked for, or the endpoint's default; outside {@code 1..maximum}, or not a number, refused. */
    public static int limit(String requested, int defaultLimit, int maximum) {
        if (requested == null) {
            return defaultLimit;
        }
        if (!DIGITS.matcher(requested).matches()) {
            throw invalid("limit is a whole number from 1 to " + maximum + ".");
        }
        int limit = Integer.parseInt(requested);
        if (limit < 1 || limit > maximum) {
            throw invalid("limit is a whole number from 1 to " + maximum + "; " + limit + " is outside it, and is not reduced.");
        }
        return limit;
    }

    /** The cursor that resumes {@code collection} after the row whose order key is {@code key}. */
    public static String cursor(String collection, String... key) {
        if (!NAME.matcher(collection).matches()) {
            throw new IllegalArgumentException("a collection's name is lowercase words, not " + collection);
        }
        for (String part : key) {
            if (part.isEmpty() || part.contains(SEPARATOR)) {
                throw new IllegalArgumentException("a cursor's key part is non-empty and holds no separator");
            }
        }
        String plain = collection + SEPARATOR + VERSION + SEPARATOR + String.join(SEPARATOR, key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The order key {@code cursor} carries for {@code collection}, its {@code arity} parts; empty for the first page (no cursor).
     * Anything this class did not write for this collection - malformed, another collection's, another version's, the wrong
     * number of parts - is refused.
     */
    public static Optional<List<String>> key(String collection, String cursor, int arity) {
        if (cursor == null) {
            return Optional.empty();
        }
        String plain;
        try {
            plain = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw invalidCursor();
        }
        List<String> parts = Arrays.asList(plain.split(Pattern.quote(SEPARATOR), -1));
        if (parts.size() != arity + 2 || !parts.get(0).equals(collection) || !parts.get(1).equals(VERSION)
                || parts.stream().anyMatch(String::isEmpty)) {
            throw invalidCursor();
        }
        return Optional.of(List.copyOf(parts.subList(2, parts.size())));
    }

    /** A cursor whose parts would not parse as the key they should be: refused as any other unreadable cursor. */
    public static ApiException invalidCursor() {
        return invalid("cursor is not a cursor this collection served; start again without one.");
    }

    public static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "A collection was asked for with an invalid parameter", detail);
    }
}
