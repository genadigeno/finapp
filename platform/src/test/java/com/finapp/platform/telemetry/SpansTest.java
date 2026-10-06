package com.finapp.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The span port's one rule (`P8-TSK-024`, ADR-0072, `INV-AUD-02`): a span leaves the platform,
 * so only the six identifier keys with an identifier's shape may ride on it - never an amount,
 * a reference value or a name - and the tracer-less implementation runs the work as if untraced.
 */
@DisplayName("the domain span port admits identifiers only (P8-TSK-024)")
class SpansTest {

    private static final String AN_IDENTIFIER = "01a0e2bc-8200-7001-8000-000000000001";

    @Test
    @DisplayName("the identifier keys are exactly the twelve the plans name - Phase 8's six and Phase 9's six")
    void theKeysAreExactlyTheSix() {
        assertThat(Spans.IDENTIFIER_KEYS)
                .containsExactlyInAnyOrder(
                        "run.id", "batch.id", "file.id", "source.id", "break.id",
                        "resolution.id",
                        // P9-TSK-027: the FX and cross-border legs' identifiers.
                        "quote.id", "trade.id", "cover.id", "payment.id", "credit.id", "screening.id");
    }

    @Test
    @DisplayName("each of the six keys admits a canonical UUID")
    void eachKeyAdmitsAUuid() {
        for (String key : Spans.IDENTIFIER_KEYS) {
            assertThat(Spans.admits(key, AN_IDENTIFIER)).as("%s with a UUID", key).isTrue();
            assertThat(Spans.admits(key, UUID.randomUUID().toString()))
                    .as("%s with a random UUID", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("an amount, a reference value, a name, a narrative and an upper-case UUID are"
            + " refused under an identifier key")
    void nonIdentifierValuesAreRefused() {
        for (String value :
                Set.of(
                        "12.34",
                        "1234",
                        "PSP-REM-123456",
                        "Jane Doe",
                        "the PSP will never pay",
                        "",
                        AN_IDENTIFIER.toUpperCase(java.util.Locale.ROOT),
                        AN_IDENTIFIER + " ",
                        AN_IDENTIFIER.replace("-", ""))) {
            assertThat(Spans.admits("source.id", value)).as("source.id=%s", value).isFalse();
        }
    }

    @Test
    @DisplayName("a key outside the six is refused even with a UUID value")
    void anUndeclaredKeyIsRefused() {
        for (String key : Set.of("amount", "customer.id", "reference", "narrative", "id",
                "Source.id", "source.id ")) {
            assertThat(Spans.admits(key, AN_IDENTIFIER)).as("key %s", key).isFalse();
        }
    }

    @Test
    @DisplayName("a null key or a null value is refused, never a NullPointerException")
    void nullsAreRefused() {
        assertThat(Spans.admits(null, AN_IDENTIFIER)).isFalse();
        assertThat(Spans.admits("run.id", null)).isFalse();
        assertThat(Spans.admits(null, null)).isFalse();
    }

    @Test
    @DisplayName("NONE runs the work once and returns its value, whatever the identifiers")
    void noneRunsTheWork() {
        AtomicInteger runs = new AtomicInteger();

        String result =
                Spans.NONE.within(
                        "settlement.accept",
                        Map.of("file.id", AN_IDENTIFIER, "amount", "12.34"),
                        () -> {
                            runs.incrementAndGet();
                            return "done";
                        });

        assertThat(result).isEqualTo("done");
        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("NONE lets the work's own exception propagate unchanged")
    void nonePropagatesTheWorksFailure() {
        IllegalStateException failure = new IllegalStateException("the leg failed");

        assertThatThrownBy(
                        () -> Spans.NONE.within(
                                "reconciliation.chunk",
                                Map.of(),
                                () -> {
                                    throw failure;
                                }))
                .isSameAs(failure);
    }
}
