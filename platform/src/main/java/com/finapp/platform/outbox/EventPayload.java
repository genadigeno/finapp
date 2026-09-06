package com.finapp.platform.outbox;

import java.nio.charset.StandardCharsets;
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
