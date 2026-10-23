package com.finapp.platform.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EventPayload}: the vocabulary it accepts, and the one it refuses (`P1-TSK-006`).
 *
 * <p>The refusals are the point. {@code INV-AUD-02} keeps personal data out of event payloads, and
 * a general object mapper would serialise {@code payload.put("displayName", name)} happily. Here
 * that is a failing test.
 */
@DisplayName("EventPayload (P1-TSK-006)")
class EventPayloadTest {

    @Test
    @DisplayName("identifiers and enumerated names serialise, in insertion order")
    void identifiersAndEnumNamesSerialise() {
        UUID id = UUID.fromString("0199c1f8-7a3d-7000-8000-0123456789ab");

        String json =
                new String(
                        EventPayload.of()
                                .with("partyId", id.toString())
                                .with("kind", "PERSON")
                                .toBytes(),
                        StandardCharsets.UTF_8);

        assertThat(json)
                .as("insertion order, so the bytes are reproducible for a given event")
                .isEqualTo("{\"partyId\":\"" + id + "\",\"kind\":\"PERSON\"}");
    }

    @Test
    @DisplayName("a person's name is refused, which is the whole reason this type exists")
    void freeTextIsRefused() {
        // The accident this class is built to make impossible. A name in an event payload reaches
        // every consumer and every system the stream is copied to (INV-AUD-02).
        assertThatThrownBy(() -> EventPayload.of().with("displayName", "Ada Lovelace"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventPayload.of().with("email", "ada@example.com"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventPayload.of().with("iban", "GB29 NWBK 6016 1331 9268 19"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the rejection does not repeat the value it refused")
    void theRejectionDoesNotEchoTheValue() {
        // Otherwise the guard would put the very text it refused to publish into a log line, which
        // is the same disclosure by another route.
        assertThatThrownBy(() -> EventPayload.of().with("displayName", "Ada Lovelace"))
                .hasMessageNotContaining("Ada")
                .hasMessageNotContaining("Lovelace");
    }

    @Test
    @DisplayName("nothing that reaches the output needs escaping, and that is enforced not assumed")
    void noValueCanBreakTheJson() {
        for (String hostile :
                new String[] {"a\"b", "a\\b", "a\nb", "a{b", "a b", "", "x".repeat(201)}) {
            assertThatThrownBy(() -> EventPayload.of().with("field", hostile))
                    .as("a value that would need escaping must never get in")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a field cannot be set twice, and an empty payload is a construction mistake")
    void mistakesAreRefused() {
        assertThatThrownBy(() -> EventPayload.of().with("partyId", "a").with("partyId", "b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EventPayload.of().toBytes()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EventPayload.of().with("not a field", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the values the platform actually publishes are accepted")
    void theRealVocabularyIsAccepted() {
        // A rule that rejected what the producers legitimately emit would be a rule somebody turns
        // off, which is ADR-0019's argument applied here.
        assertThatCode(
                        () ->
                                EventPayload.of()
                                        .with("identityId", UUID.randomUUID().toString())
                                        .with("status", "ACTIVE")
                                        .with("kind", "ORGANISATION")
                                        .toBytes())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("it never prints its values")
    void itNeverPrintsItsValues() {
        assertThat(EventPayload.of().with("partyId", "abc").toString())
                .doesNotContain("abc")
                .contains("partyId");
    }

    // -----------------------------------------------------------------
    // The reader (P13-TSK-005): exactly the writer's inverse.
    // -----------------------------------------------------------------

    @Test
    @DisplayName("what the writer wrote, the reader reads back - every field, in order")
    void theReaderIsTheWritersInverse() {
        UUID id = UUID.fromString("0199c1f8-7a3d-7000-8000-0123456789ab");
        byte[] bytes = EventPayload.of()
                .with("identityId", id.toString())
                .with("status", "SUSPENDED")
                .with("daysPastDue", "31")
                .with("defaulted", "false")
                .toBytes();

        assertThat(EventPayload.fields(bytes))
                .containsExactly(
                        java.util.Map.entry("identityId", id.toString()),
                        java.util.Map.entry("status", "SUSPENDED"),
                        java.util.Map.entry("daysPastDue", "31"),
                        java.util.Map.entry("defaulted", "false"));
    }

    @Test
    @DisplayName("the reader admits nothing the writer could not have produced")
    void theReaderRefusesWhatTheWriterRefuses() {
        // A reader looser than the writer is the general object mapper this class declines to be, met from the
        // consuming side: a free-text value, a nested object, a number, a list or an escaped string is not a payload
        // of ours, whatever produced it, and the consumer refuses the event rather than guessing at it.
        for (String hostile : new String[] {
                "{\"displayName\":\"Ada Lovelace\"}",
                "{\"device\":\"Chrome on Windows\"}",
                "{\"a\":{\"b\":\"c\"}}",
                "{\"a\":1}",
                "{\"a\":[\"b\"]}",
                "{\"a\":\"b\\\"c\"}",
                "{\"a\":\"" + "x".repeat(201) + "\"}",
                "{\"not a field\":\"x\"}",
                "{\"a\":\"b\",\"a\":\"c\"}",
                "{}",
                "",
                "[]",
                "{\"a\":\"b\",}",
                " {\"a\":\"b\"}"}) {
            assertThatThrownBy(() -> EventPayload.fields(hostile.getBytes(StandardCharsets.UTF_8)))
                    .as("refused: %s", hostile.replace("Ada Lovelace", "<name>").replace("Chrome on Windows", "<device>"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("the reader's refusal does not repeat the bytes it refused")
    void theReadersRefusalDoesNotEchoTheBytes() {
        assertThatThrownBy(() -> EventPayload.fields("{\"displayName\":\"Ada Lovelace\"}".getBytes(StandardCharsets.UTF_8)))
                .hasMessageNotContaining("Ada")
                .hasMessageNotContaining("Lovelace");
    }

    @Test
    @DisplayName("what the reader returns cannot be edited")
    void theFieldsAreReadOnly() {
        java.util.Map<String, String> fields =
                EventPayload.fields(EventPayload.of().with("kind", "PERSON").toBytes());
        assertThatThrownBy(() -> fields.put("kind", "ORGANISATION"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
