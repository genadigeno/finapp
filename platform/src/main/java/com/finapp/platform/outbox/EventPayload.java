package com.finapp.platform.outbox;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A domain event's payload: a flat JSON object of identifiers and enumerated names, and nothing
 * else.
 *
 * <p><strong>Why this exists rather than a serialiser.</strong> The first events this platform
 * publishes (`P1-TSK-006`) carry identifiers and enum constants — no names, no login identifiers,
 * no free text of any kind, because an event stream reaches systems with different access control
 * and {@code INV-AUD-02} forbids unnecessary PII in event payloads. That is not a coincidence of
 * the first three events; it is the rule {@code PHASE_1_PLAN.md} §4 states for all of them.
 *
 * <p>So the payload builder <strong>enforces the rule instead of trusting it</strong>: a value
 * that is not an identifier or an enumerated name is rejected, which makes the accidental
 * {@code payload.put("displayName", name)} a failing test rather than a disclosure. A general
 * object mapper would serialise that happily.
 *
 * <p><strong>The limit, stated.</strong> This is deliberately not a general event serialiser and
 * must not become one. An event that genuinely needs richer structure — a nested object, a
 * monetary amount, a list — needs the wire-format decision {@code EVENT_ARCHITECTURE.md} defers,
 * and it needs it taken rather than worked around here. When that happens this class is replaced,
 * not extended.
 */
public final class EventPayload {

    /** What the outbox records these bytes as. */
    public static final String MEDIA_TYPE = "application/json";

    /**
     * UUIDs, enum constant names and small integers — the whole permitted vocabulary.
     *
     * <p>No spaces, no punctuation beyond {@code -} and {@code _}, so no value can carry a name,
     * an address, an email, a login identifier or anything else a person typed. It is also why no
     * escaping is required: nothing that reaches here can contain a quote or a backslash, and that
     * is asserted rather than assumed.
     */
    private static final Pattern PERMITTED_VALUE = Pattern.compile("[A-Za-z0-9_-]{1,200}");

    private static final Pattern PERMITTED_FIELD = Pattern.compile("[a-zA-Z][A-Za-z0-9]{0,63}");

    private final Map<String, String> fields = new LinkedHashMap<>();

    public static EventPayload of() {
        return new EventPayload();
    }

    /**
     * Adds one field.
     *
     * @throws IllegalArgumentException if the value is not an identifier or an enumerated name.
     *     The message never quotes the value — a rejection that echoed the free text somebody
     *     tried to publish would put it in a log line, which is the disclosure this class exists
     *     to prevent
     */
    public EventPayload with(String field, String value) {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(value, "value must not be null");
        if (!PERMITTED_FIELD.matcher(field).matches()) {
            throw new IllegalArgumentException("Not a usable event payload field name: " + field);
        }
        if (!PERMITTED_VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "The value for '" + field + "' is not an identifier or an enumerated name. "
                            + "Event payloads carry neither free text nor personal data "
                            + "(INV-AUD-02); if this event needs richer structure, the wire format "
                            + "decision has to be taken rather than worked around");
        }
        if (fields.putIfAbsent(field, value) != null) {
            throw new IllegalArgumentException("Field '" + field + "' was already set");
        }
        return this;
    }

    /**
     * One field of a payload as this class writes it: {@code "field":"value"}, each side the grammar above.
     *
     * <p>Named groups rather than positions so the reader below cannot drift from the writer: the field and value
     * patterns are the same two constants {@link #with} enforces, so whatever the writer admits the reader admits,
     * and nothing else.
     */
    private static final Pattern WRITTEN_FIELD =
            Pattern.compile("\"(?<field>[a-zA-Z][A-Za-z0-9]{0,63})\":\"(?<value>[A-Za-z0-9_-]{1,200})\"");

    /**
     * The fields of a payload these bytes were written by {@link #toBytes()} - the inverse, and <strong>exactly</strong>
     * the inverse (`P13-TSK-005`).
     *
     * <p>Until Phase 13 no consumer read a payload: every handler reacted to the envelope's aggregate and asked the
     * owning module through a port (the metadata-only stance, `P2-TSK-007`). A risk signal is different - it records
     * what the event <em>said</em>, and nothing read from the producer's tables (`PHASE_13_PLAN.md` section 12.5) -
     * so a consumer must read the bytes the producer committed. This reader accepts the one shape {@link #toBytes()}
     * produces - a flat object of the writer's field names and values - and refuses everything else: a nested object,
     * a number, a list, an escaped string, a free-text value, a value past the bound. A reader looser than the writer
     * would be the general object mapper the class above declines to be, met from the other side; a payload the
     * writer could not have produced is not one of ours, and the caller refuses the event rather than guessing.
     *
     * @return the fields in the order written; never empty
     * @throws IllegalArgumentException if the bytes are not a payload this class writes. The message never repeats
     *     the bytes: a refusal that echoed a malformed payload would put whatever it carried into a log line
     */
    public static Map<String, String> fields(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes must not be null");
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (json.length() < 2 || json.charAt(0) != '{' || json.charAt(json.length() - 1) != '}') {
            throw new IllegalArgumentException("Not an event payload this platform writes: not a flat object");
        }
        String body = json.substring(1, json.length() - 1);
        Map<String, String> fields = new LinkedHashMap<>();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Not an event payload this platform writes: no field");
        }
        for (String member : body.split(",", -1)) {
            var matcher = WRITTEN_FIELD.matcher(member);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        "Not an event payload this platform writes: a member is not a written field of an"
                                + " identifier or an enumerated name");
            }
            if (fields.putIfAbsent(matcher.group("field"), matcher.group("value")) != null) {
                throw new IllegalArgumentException(
                        "Not an event payload this platform writes: a field appears twice");
            }
        }
        // Insertion order kept (a LinkedHashMap), read-only: a consumer reads a payload, never edits one.
        return Collections.unmodifiableMap(fields);
    }

    /** The payload as UTF-8 JSON, in insertion order so the bytes are reproducible. */
    public byte[] toBytes() {
        if (fields.isEmpty()) {
            // An event whose payload is empty carries nothing a consumer can act on, and the
            // envelope already carries the aggregate. Almost certainly a construction mistake.
            throw new IllegalStateException("An event payload must carry at least one field");
        }
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(field.getKey()).append("\":\"").append(field.getValue()).append('"');
        }
        return json.append('}').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Never prints the values: what they are is the caller's business, not a log line's. */
    @Override
    public String toString() {
        return "EventPayload" + fields.keySet();
    }
}
