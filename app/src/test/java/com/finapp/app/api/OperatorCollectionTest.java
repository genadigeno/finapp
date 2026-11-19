package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An operator collection's request rules (`P17-TSK-107`, `API_CONVENTIONS.md` section 7): the limit defaulted, bounded and refused
 * over its maximum rather than reduced; the cursor read back only by the collection that wrote it.
 */
@DisplayName("an operator collection's limit and cursor (P17-TSK-107)")
class OperatorCollectionTest {

    @Test
    @DisplayName("the limit is the default when absent, the number when inside 1..maximum, and refused 422 otherwise - never reduced")
    void theLimitIsBoundedNotReduced() {
        assertThat(OperatorCollection.limit(null, 50, 200)).isEqualTo(50);
        assertThat(OperatorCollection.limit("1", 50, 200)).isEqualTo(1);
        assertThat(OperatorCollection.limit("200", 50, 200)).isEqualTo(200);
        for (String refused : List.of("0", "201", "-1", "abc", "", "1.5", "99999", " 5")) {
            assertThatThrownBy(() -> OperatorCollection.limit(refused, 50, 200)).as("limit %s", refused)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("a cursor reads back as the key it was written with, by its own collection; none is the first page")
    void aCursorRoundTrips() {
        String cursor = OperatorCollection.cursor("settlement.batches", "2026-11-18", "01a1ed80-1517-752e-90ec-f2563a5b7a6c");

        assertThat(OperatorCollection.key("settlement.batches", cursor, 2))
                .contains(List.of("2026-11-18", "01a1ed80-1517-752e-90ec-f2563a5b7a6c"));
        assertThat(OperatorCollection.key("settlement.batches", null, 2)).isEqualTo(Optional.empty());
        assertThat(cursor).as("URL-safe, nothing to escape in a query string").matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("another collection's cursor, another version's, a wrong arity, an empty part or a malformed one is refused 422")
    void aForeignOrMalformedCursorIsRefused() {
        String batches = OperatorCollection.cursor("settlement.batches", "2026-11-18", "x");
        List<String> refused = List.of(
                batches.substring(0, batches.length() - 2) + "!!",
                encode("settlement.batches|v2|2026-11-18|x"),
                encode("settlement.batches|v1|2026-11-18"),
                encode("settlement.batches|v1|2026-11-18|x|y"),
                encode("settlement.batches|v1||x"),
                encode("garbage"));
        assertThatThrownBy(() -> OperatorCollection.key("reconciliation.resolutions", batches, 2)).as("another collection's")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        for (String cursor : refused) {
            assertThatThrownBy(() -> OperatorCollection.key("settlement.batches", cursor, 2)).as("cursor %s", cursor)
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED));
        }
    }

    @Test
    @DisplayName("a key part that is empty or holds the separator is a programming error, never written")
    void aBadKeyIsNeverWritten() {
        assertThatThrownBy(() -> OperatorCollection.cursor("settlement.batches", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperatorCollection.cursor("settlement.batches", "a|b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OperatorCollection.cursor("Settlement Batches", "a")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String encode(String plain) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }
}
