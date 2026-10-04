package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The published contract's half of {@code INV-FX-02} (`P9-TSK-008`): in
 * {@code docs/api/openapi.json}, no request body under the {@code fx} and cross-border doors -
 * nor any schema it reaches - has a property named like a rate or a price, or typed as a JSON
 * number. A planted document proves the guard bites.
 */
@DisplayName("the published fx request schemas carry no rate (P9-TSK-008)")
class FxRequestSchemasCarryNoRateTest {

    private static final List<String> PREFIXES =
            List.of("/v1/me/fx", "/v1/operator/fx", "/v1/me/cross-border", "/v1/operator/cross-border");

    @Test
    @DisplayName("every fx and cross-border request schema is rate-free and number-free")
    void theContractCarriesNoRate() throws IOException {
        JsonNode contract = new ObjectMapper().readTree(
                Files.readString(repository().resolve("docs/api/openapi.json"), StandardCharsets.UTF_8));
        List<String> scanned = new ArrayList<>();
        List<String> violations = violations(contract, scanned);
        assertThat(scanned).as("not vacuous").contains("QuoteRequestBody", "PricingPolicyRequest", "PairRequest");
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("the guard bites: a rate property and a number-typed amount are each refused")
    void thePlantedDocumentIsRefused() {
        JsonNode planted = new ObjectMapper().readTree("""
                {"paths": {"/v1/me/fx/planted": {"post": {"requestBody": {"content": {"application/json":
                  {"schema": {"$ref": "#/components/schemas/Planted"}}}}}}},
                 "components": {"schemas": {
                   "Planted": {"properties": {"rate": {"type": "string"},
                                              "leg": {"$ref": "#/components/schemas/Leg"}}},
                   "Leg": {"properties": {"amount": {"type": "number"}}}}}}
                """);
        assertThat(violations(planted, new ArrayList<>()))
                .containsExactlyInAnyOrder("Planted.rate is rate-named", "Leg.amount is a JSON number");
    }

    // -----------------------------------------------------------------

    static List<String> violations(JsonNode contract, List<String> scanned) {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, JsonNode> path : contract.path("paths").properties()) {
            if (PREFIXES.stream().noneMatch(path.getKey()::startsWith)) {
                continue;
            }
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                for (JsonNode media : operation.getValue().path("requestBody").path("content")) {
                    walk(contract, media.path("schema"), "body", seen, scanned, found);
                }
            }
        }
        return found;
    }

    private static void walk(JsonNode contract, JsonNode schema, String name, Set<String> seen, List<String> scanned,
            List<String> found) {
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asString();
            String target = ref.substring(ref.lastIndexOf('/') + 1);
            if (seen.add(target)) {
                scanned.add(target);
                walk(contract, contract.path("components").path("schemas").path(target), target, seen, scanned, found);
            }
            return;
        }
        if (schema.has("items")) {
            walk(contract, schema.get("items"), name, seen, scanned, found);
        }
        for (String combinator : List.of("allOf", "anyOf", "oneOf")) {
            for (JsonNode part : schema.path(combinator)) {
                walk(contract, part, name, seen, scanned, found);
            }
        }
        for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
            String lower = property.getKey().toLowerCase(Locale.ROOT);
            if (lower.endsWith("rate") || lower.contains("price")) {
                found.add(name + "." + property.getKey() + " is rate-named");
            }
            if ("number".equals(property.getValue().path("type").asString(""))) {
                found.add(name + "." + property.getKey() + " is a JSON number");
            }
            walk(contract, property.getValue(), name, seen, scanned, found);
        }
    }

    private static Path repository() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("docs/api/openapi.json"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException("docs/api/openapi.json not found above the working directory");
        }
        return here;
    }
}
