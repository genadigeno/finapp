package com.finapp.app.api;

import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.ProblemDetail;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.Separators;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the published OpenAPI document from the running application.
 *
 * <h2>Why the generator lives in test sources</h2>
 *
 * <p>Because nothing about OpenAPI ships. springdoc <em>reads</em> the request mappings and never
 * changes them, so a document produced with it on the test classpath describes exactly the
 * application that is deployed - while the deployed application carries no documentation library,
 * no Swagger model classes and no {@code /v3/api-docs} endpoint for anyone to find.
 *
 * <p>The part that matters is that no contract is <em>authored</em> here. Everything this class
 * adds is derived from production types: the error responses from every declared {@link ErrorCode},
 * the problem-detail schema from {@link ProblemDetailBody} (which is where the wire format is
 * decided) and its required members from {@link ProblemDetail} (which is where optionality is
 * decided). Adding an error code changes the document without anyone editing it, and the committed
 * baseline then fails the build until a human accepts the new contract.
 *
 * <h2>What springdoc contributes</h2>
 *
 * <p>The paths - every mapped route, with its parameters and schemas. That set is empty today
 * because Phase 0 publishes no business endpoint, which is the point of Phase 0 rather than a gap
 * in this class; {@code ApiVersioningTest} proves the path generation works by mapping a probe
 * controller and finding it in the document, under {@code /v1}.
 */
final class OpenApiDocument {

    /** Where springdoc serves the document it derives from the running context. */
    static final String SPRINGDOC_PATH = "/v3/api-docs";

    /** The media type every error in this platform is returned as. */
    static final String PROBLEM_JSON = "application/problem+json";

    /** The response header carrying the correlation identifier, echoed on every response. */
    static final String CORRELATION_HEADER = CorrelationFilter.HEADER;

    /**
     * The response header carrying the caller's own value back (ADR-0034).
     *
     * <p>Published because a client is told to use it. It is set only when the caller supplied a
     * well-formed request header, so unlike {@link #CORRELATION_HEADER} it is not {@code required}.
     */
    static final String CLIENT_CORRELATION_HEADER = CorrelationFilter.CLIENT_HEADER;

    /**
     * The component name of the shared problem-detail schema.
     *
     * <p>One definition because it is written in two places - as a component key and as the
     * target of every response $ref - and renaming one of two literals leaves a document whose
     * references do not resolve, which no client generator can read.
     */
    static final String PROBLEM_SCHEMA = "ProblemDetail";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private OpenApiDocument() {}

    /**
     * Takes springdoc's raw output and returns the published document, canonically formatted.
     *
     * @param springdocJson the body of {@link #SPRINGDOC_PATH}
     */
    static String publish(String springdocJson) {
        ObjectNode document = (ObjectNode) MAPPER.readTree(springdocJson);

        // springdoc defaults to "OpenAPI definition" / "v0" - placeholders, and placeholders in a
        // published contract are how a document stops being read.
        document.set("info", info());

        // springdoc synthesises a `servers` entry from the request it is answering, which in a
        // test is http://127.0.0.1:<random port>. Publishing that would make the document differ
        // on every run and the comparison below useless noise - and on a real deployment it would
        // publish an internal address to every client. Removed rather than replaced: the version
        // is already in every path, so a server URL of /v1 as well would either double the prefix
        // or offer two ways to say the same thing.
        document.remove("servers");

        document.set("components", components());

        return canonicalJson(document);
    }

    /** Parses a published document back into a tree, for comparison. */
    static JsonNode parse(String json) {
        return MAPPER.readTree(json);
    }

    // -----------------------------------------------------------------

    private static ObjectNode info() {
        ObjectNode info = MAPPER.createObjectNode();
        info.put("title", "finapp platform API");
        // The contract version, and the same number that appears in every path. Not a release
        // number: a document that changed on every build would make the baseline comparison
        // useless noise.
        info.put("version", String.valueOf(ApiVersion.CURRENT));
        info.put(
                "description",
                "The public HTTP contract. Versioned in the path (ADR-0015); errors follow RFC 9457, "
                        + "catalogued in docs/architecture/ERROR_CONTRACT.md. Generated from the "
                        + "application on every build and compared against this committed copy, so a "
                        + "change to the contract cannot reach a client without being reviewed.");
        return info;
    }

    private static ObjectNode components() {
        ObjectNode components = MAPPER.createObjectNode();
        components.set("headers", headers());
        components.set("responses", errorResponses());
        components.set("schemas", schemas());
        return components;
    }

    private static ObjectNode headers() {
        ObjectNode header = MAPPER.createObjectNode();
        header.put(
                "description",
                "The identifier of this request flow. Quote it when reporting a problem; it is what "
                        + "joins a client report to the record of what happened.");
        header.put("required", true);
        header.set("schema", MAPPER.createObjectNode().put("type", "string"));

        ObjectNode clientHeader = MAPPER.createObjectNode();
        clientHeader.put(
                "description",
                "The value supplied in the request's X-Correlation-Id header, returned unchanged. "
                        + "It is never adopted as the flow's identifier (ADR-0034) and reaches no "
                        + "log, span or stored record; it exists so a caller that no longer holds "
                        + "the connection can match a response to a request. Present only when a "
                        + "well-formed value was supplied.");
        clientHeader.put("required", false);
        clientHeader.set("schema", MAPPER.createObjectNode().put("type", "string"));

        ObjectNode headers = MAPPER.createObjectNode();
        headers.set(CORRELATION_HEADER, header);
        headers.set(CLIENT_CORRELATION_HEADER, clientHeader);
        return headers;
    }

    /**
     * One reusable response per declared error code, keyed by the code itself.
     *
     * <p>Keying on the code rather than on an invented component name means removing a code removes
     * a component with that exact name, so the failure message names the code a client would have
     * been switching on.
     */
    private static ObjectNode errorResponses() {
        ObjectNode responses = MAPPER.createObjectNode();
        Map<String, ErrorCode> byCode = new TreeMap<>();
        for (ErrorCode code : DeclaredErrorCodes.all()) {
            byCode.put(code.code(), code);
        }
        byCode.forEach((code, errorCode) -> responses.set(code, errorResponse(errorCode)));
        return responses;
    }

    private static ObjectNode errorResponse(ErrorCode code) {
        ObjectNode responseHeaders = MAPPER.createObjectNode();
        for (String name : new String[] {CORRELATION_HEADER, CLIENT_CORRELATION_HEADER}) {
            responseHeaders.set(
                    name,
                    MAPPER.createObjectNode().put("$ref", "#/components/headers/" + name));
        }

        ObjectNode media = MAPPER.createObjectNode();
        media.set("schema", problemSchemaFor(code));

        ObjectNode content = MAPPER.createObjectNode();
        content.set(PROBLEM_JSON, media);

        ObjectNode response = MAPPER.createObjectNode();
        // The title, not a second piece of prose: the code already carries the sentence a human is
        // shown, and two sources for one sentence is one source too many.
        response.put("description", "[" + code.status() + "] " + code.title());
        response.set("content", content);
        response.set("headers", responseHeaders);
        return response;
    }

    /**
     * The shared problem-detail schema, with the three members this code always carries pinned.
     *
     * <p><strong>Found by mutation.</strong> Without the {@code const} values the only place a
     * status appeared in the document was inside an English description, so changing {@code
     * api.Conflict} from 409 to 422 - a change that silently breaks every client switching on the
     * status - was reported as a compatible rewording. The exact-match gate still failed the build,
     * which is why the gate and the classifier are separate things; but advice pointing the wrong
     * way is worse than no advice, because it is advice somebody acts on.
     *
     * <p>The deeper defect was in the contract rather than in the classifier. {@code
     * ERROR_CONTRACT.md} §1 says the status and the type are fixed per code, and a client had no
     * machine-readable way to learn either. Now it does, and a change to either is visible as data.
     */
    private static ObjectNode problemSchemaFor(ErrorCode code) {
        ObjectNode pinned = MAPPER.createObjectNode();
        pinned.set("status", MAPPER.createObjectNode().put("const", code.status()));
        pinned.set("code", MAPPER.createObjectNode().put("const", code.code()));
        pinned.set(
                "type",
                MAPPER.createObjectNode().put("const", ProblemDetail.TYPE_PREFIX + code.code()));

        ObjectNode constants = MAPPER.createObjectNode();
        constants.set("properties", pinned);

        ArrayNode allOf = MAPPER.createArrayNode();
        allOf.add(
                MAPPER.createObjectNode().put("$ref", "#/components/schemas/" + PROBLEM_SCHEMA));
        allOf.add(constants);

        ObjectNode schema = MAPPER.createObjectNode();
        schema.set("allOf", allOf);
        return schema;
    }

    private static ObjectNode schemas() {
        ObjectNode schemas = MAPPER.createObjectNode();
        schemas.set(PROBLEM_SCHEMA, problemDetailSchema());
        return schemas;
    }

    /**
     * The problem-detail schema, derived rather than written.
     *
     * <p>Members and their order come from {@link ProblemDetailBody}, which is the one place the
     * wire format is decided. Which of them are always present comes from {@link ProblemDetail},
     * where an optional member is already spelled {@code Optional}. Neither fact is restated here,
     * so neither can drift: the two records disagreeing is a failure, not a silent divergence
     * between the code and its published description.
     */
    private static ObjectNode problemDetailSchema() {
        List<RecordComponent> wire = List.of(ProblemDetailBody.class.getRecordComponents());
        List<RecordComponent> value = List.of(ProblemDetail.class.getRecordComponents());

        List<String> wireNames = wire.stream().map(RecordComponent::getName).toList();
        List<String> valueNames = value.stream().map(RecordComponent::getName).toList();
        if (!wireNames.equals(valueNames)) {
            throw new IllegalStateException(
                    "ProblemDetailBody and ProblemDetail describe different members - "
                            + wireNames
                            + " versus "
                            + valueNames
                            + ". One of them is no longer a faithful rendering of the other, and the "
                            + "published schema cannot be derived until they agree.");
        }

        ObjectNode properties = MAPPER.createObjectNode();
        for (RecordComponent component : wire) {
            properties.set(component.getName(), propertySchema(component));
        }

        ArrayNode required = MAPPER.createArrayNode();
        for (RecordComponent component : value) {
            if (!Optional.class.equals(component.getType())) {
                required.add(component.getName());
            }
        }

        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        schema.put(
                "description",
                "An RFC 9457 problem detail. Clients switch on `code`; `title` is prose and may be "
                        + "reworded, and several codes share a status. Absent members are absent, "
                        + "never null.");
        schema.set("properties", properties);
        schema.set("required", required);
        // Deliberately NOT `additionalProperties: false`. RFC 9457 allows extension members, and
        // forbidding them would make adding one - a validation-detail array, say - a breaking
        // change for every validating client, for no benefit to anyone. A client that ignores
        // members it does not know keeps working while the contract grows, which is the property
        // that makes compatible evolution possible at all.
        return schema;
    }

    private static ObjectNode propertySchema(RecordComponent component) {
        ObjectNode property = MAPPER.createObjectNode();
        Class<?> type = component.getType();
        if (type == String.class) {
            property.put("type", "string");
        } else if (type == int.class) {
            property.put("type", "integer");
            property.put("format", "int32");
        } else {
            // A new member type is a contract decision - how it is represented, what range it has,
            // whether it is nullable. Guessing here would publish that decision without anyone
            // making it.
            throw new IllegalStateException(
                    "No published representation is defined for "
                            + component.getName()
                            + " of type "
                            + type.getName()
                            + ". Decide how it appears on the wire and say so here.");
        }
        return property;
    }

    // -----------------------------------------------------------------

    /**
     * Serialises with every object key sorted and a fixed two-space, LF layout.
     *
     * <p>Without this the committed document would churn on whatever order springdoc happened to
     * build its maps in, and every such churn would be reported as a contract change. A baseline
     * that cries wolf is a baseline that gets re-copied without being read.
     */
    private static String canonicalJson(JsonNode node) {
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer =
                new DefaultPrettyPrinter()
                        .withObjectIndenter(indenter)
                        .withArrayIndenter(indenter)
                        // Jackson writes `"key" : value`; every other JSON tool writes `"key":
                        // value`. A committed document is read and diffed by people, so it is
                        // formatted the way they expect.
                        .withSeparators(
                                Separators.createDefaultInstance()
                                        .withObjectNameValueSpacing(Separators.Spacing.AFTER));
        return MAPPER.writer().with(printer).writeValueAsString(sortKeys(node)) + "\n";
    }

    private static JsonNode sortKeys(JsonNode node) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>(node.propertyNames().stream().toList());
            names.sort(String::compareTo);
            ObjectNode sorted = MAPPER.createObjectNode();
            for (String name : names) {
                sorted.set(name, sortKeys(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode sorted = MAPPER.createArrayNode();
            for (JsonNode element : node) {
                sorted.add(sortKeys(element));
            }
            return sorted;
        }
        return node;
    }
}
