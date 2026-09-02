package com.finapp.app.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * One difference between two OpenAPI documents, and whether it breaks a client.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>It is <strong>advice</strong>. The gate is exact equality: {@code OpenApiContractTest} fails
 * the build on <em>any</em> difference between the generated document and the committed one, and
 * that is what makes a breaking change impossible to ship unnoticed. This class then labels each
 * difference so the developer knows which decision they are making - publish a compatible change,
 * or open a new version.
 *
 * <p>Splitting it that way is deliberate. A classifier that <em>gated</em> would have to be right
 * about every possible OpenAPI edit, and a wrong "compatible" would let a breaking change through
 * silently - the worst failure available here, because it fails at the customer end rather than in
 * the build. A classifier that only advises can be small, and being occasionally wrong costs a
 * misleading label on a failure the developer is already reading.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><strong>Anything removed is breaking.</strong> A path, an operation, a response, a schema,
 *       a property, an enum value - every removal narrows what was promised, and some client is
 *       relying on the part that went.
 *   <li><strong>Anything changed is breaking.</strong> A status, a type, a {@code $ref}, a code -
 *       if the value a client reads is not the value it read before, its handling was written
 *       against something that no longer exists.
 *   <li><strong>Anything added is compatible</strong>, because a client that does not know about a
 *       new endpoint, field or error code carries on working - <em>except</em> an addition to a
 *       {@code required} list or an {@code enum}, which narrows what is accepted or widens what is
 *       sent. Those are breaking.
 *   <li><strong>Rewording prose is never breaking.</strong> A {@code description} or {@code
 *       summary} is for a human reading the document, and nothing switches on it. <em>Deleting</em>
 *       one is exempt only while its container survives: renaming a component removes every leaf
 *       under it, and the description is frequently the only leaf there is, so an unconditional
 *       exemption would report deleting an error code as a harmless rewording.
 * </ul>
 *
 * <p>The rules are stated on JSON-pointer shape rather than on an OpenAPI object model, which is
 * why they are this short. The cost is that they cannot tell a request schema from a response
 * schema, so an added {@code enum} value is called breaking in both directions when strictly it is
 * breaking in only one. Erring towards breaking is the correct direction for advice a human reads
 * before deciding.
 *
 * @param kind whether the pointer was added, removed, or given a different value
 * @param pointer the RFC 6901 JSON pointer at which the documents differ
 * @param before the value in the committed document, or {@code null} if it was not there
 * @param after the value in the generated document, or {@code null} if it is no longer there
 * @param compatibility whether a client written against {@code before} still works
 */
record OpenApiChange(
        Kind kind, String pointer, String before, String after, Compatibility compatibility) {

    enum Kind {
        ADDED,
        REMOVED,
        CHANGED
    }

    enum Compatibility {
        /** A client written against the previous document still works. */
        COMPATIBLE,
        /** It does not. This needs a new API version, or the change must be withdrawn. */
        BREAKING
    }

    /** Differences that take {@code before} to {@code after}, in pointer order. */
    static List<OpenApiChange> between(JsonNode before, JsonNode after) {
        Map<String, String> was = flatten(before);
        Map<String, String> is = flatten(after);

        Map<String, String> allPointers = new TreeMap<>(is);
        was.forEach(allPointers::putIfAbsent);

        List<OpenApiChange> changes = new ArrayList<>();
        for (String pointer : allPointers.keySet()) {
            String before1 = was.get(pointer);
            String after1 = is.get(pointer);
            if (Objects.equals(before1, after1)) {
                continue;
            }
            Kind kind = before1 == null ? Kind.ADDED : after1 == null ? Kind.REMOVED : Kind.CHANGED;
            changes.add(new OpenApiChange(kind, pointer, before1, after1, classify(kind, pointer, is)));
        }
        return changes;
    }

    /** True when any difference would break a client written against the earlier document. */
    static boolean anyBreaking(List<OpenApiChange> changes) {
        return changes.stream().anyMatch(change -> change.compatibility() == Compatibility.BREAKING);
    }

    @Override
    public String toString() {
        return switch (kind) {
            case ADDED -> "%-10s ADDED    %s = %s".formatted(compatibility, pointer, after);
            case REMOVED -> "%-10s REMOVED  %s (was %s)".formatted(compatibility, pointer, before);
            case CHANGED ->
                    "%-10s CHANGED  %s: %s -> %s".formatted(compatibility, pointer, before, after);
        };
    }

    // -----------------------------------------------------------------

    private static Compatibility classify(Kind kind, String pointer, Map<String, String> after) {
        return switch (kind) {
            case CHANGED -> isProse(pointer) ? Compatibility.COMPATIBLE : Compatibility.BREAKING;
            case ADDED ->
                    isProse(pointer) || !constrainsCallers(pointer)
                            ? Compatibility.COMPATIBLE
                            : Compatibility.BREAKING;
            // A removed description is only prose if there is still something it was describing.
            // Renaming a component removes every leaf under it, and the description is often the
            // only one - so exempting prose unconditionally would report deleting an error code as
            // a harmless rewording. That was not hypothetical: it is what this classifier did on
            // its first run, and four of its own tests caught it.
            case REMOVED ->
                    isProse(pointer) && containerSurvives(pointer, after)
                            ? Compatibility.COMPATIBLE
                            : Compatibility.BREAKING;
        };
    }

    private static boolean isProse(String pointer) {
        String leaf = pointer.substring(pointer.lastIndexOf('/') + 1);
        return leaf.equals("description") || leaf.equals("summary");
    }

    /** True when something else still sits under the same parent in the newer document. */
    private static boolean containerSurvives(String pointer, Map<String, String> after) {
        String parent = pointer.substring(0, pointer.lastIndexOf('/') + 1);
        return after.keySet().stream().anyMatch(candidate -> candidate.startsWith(parent));
    }

    /**
     * True for the two additions that are not additive: a new {@code required} member rejects
     * requests that were accepted, and a new {@code enum} value is a value the caller's own
     * validation does not know.
     */
    private static boolean constrainsCallers(String pointer) {
        for (String segment : pointer.split("/")) {
            if (segment.equals("required") || segment.equals("enum")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every scalar in the document, keyed by JSON pointer.
     *
     * <p>An empty object or array is recorded as a value of its own, so emptying {@code paths} is a
     * change rather than the silent disappearance of everything under it.
     */
    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> flat = new TreeMap<>();
        flatten(node, "", flat);
        return flat;
    }

    private static void flatten(JsonNode node, String pointer, Map<String, String> flat) {
        if (node.isObject()) {
            if (node.isEmpty()) {
                flat.put(pointer, "{}");
                return;
            }
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                flatten(property.getValue(), pointer + "/" + escape(property.getKey()), flat);
            }
        } else if (node.isArray()) {
            if (node.isEmpty()) {
                flat.put(pointer, "[]");
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), pointer + "/" + i, flat);
            }
        } else {
            // toString() rather than the text value, so the string "1" and the number 1 are not
            // reported as equal. A type change is one of the things this exists to catch.
            flat.put(pointer, node.toString());
        }
    }

    /** RFC 6901 pointer escaping. Error codes carry dots, which need none; be correct anyway. */
    private static String escape(String name) {
        return name.replace("~", "~0").replace("/", "~1");
    }
}
