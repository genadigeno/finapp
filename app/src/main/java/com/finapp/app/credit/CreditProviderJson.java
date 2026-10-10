package com.finapp.app.credit;

import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The credit adapters' one reading of a provider body (the Phase 10 -> 11 transition; ADR-0085 section 2): a strict
 * JSON parse, so "malformed" means what {@code CreditDataAnswer} says it means - the body is not one well-formed JSON
 * object, wholly - and never a pattern's guess.
 *
 * <p>Refused, each as malformed: anything but exactly one object (two concatenated reports, an array, a bare value),
 * a token after the object, and a key written twice at any depth (a parser that keeps the first or the last would choose
 * between two reports silently). Only the top-level object's own fields are read; a field nested inside another object
 * is not that field.
 *
 * <p>No provider vocabulary lives here ({@code CreditProviderVocabularyIsConfinedTest}): each adapter names its own
 * fields. Stateless; the mapper is immutable and thread-safe.
 */
final class CreditProviderJson {

    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    private CreditProviderJson() {}

    /** The body's one top-level object, or empty when the body is anything else - malformed, wholly. */
    static Optional<JsonNode> object(byte[] body) {
        Objects.requireNonNull(body, "body");
        try {
            JsonNode node = STRICT.readTree(body);
            return node != null && node.isObject() ? Optional.of(node) : Optional.empty();
        } catch (RuntimeException unreadable) {
            // Jackson 3's exceptions are unchecked; whatever the parser refused, nothing of the body is data.
            return Optional.empty();
        }
    }

    /** Whether {@code object} has a top-level field {@code name}, in any form. */
    static boolean has(JsonNode object, String name) {
        return object.has(name);
    }

    /** The top-level field {@code name} when it is a JSON string; empty when absent or in any other form. */
    static Optional<String> text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        return value != null && value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }
}
