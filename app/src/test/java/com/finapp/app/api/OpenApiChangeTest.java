package com.finapp.app.api;

import static com.finapp.app.api.OpenApiChange.Compatibility.BREAKING;
import static com.finapp.app.api.OpenApiChange.Compatibility.COMPATIBLE;
import static com.finapp.app.api.OpenApiChange.Kind.ADDED;
import static com.finapp.app.api.OpenApiChange.Kind.CHANGED;
import static com.finapp.app.api.OpenApiChange.Kind.REMOVED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The change classifier, exercised on the edits it exists to describe.
 *
 * <p>Hermetic and synthetic on purpose. Driving it through the real document would test one
 * document rather than the rules, and the edits that matter most - deleting an error code, changing
 * a status - are exactly the ones nobody will make to the real contract in order to test them.
 */
class OpenApiChangeTest {

    private static final String BEFORE =
            """
            {
              "openapi": "3.1.0",
              "info": { "title": "finapp platform API", "version": "1" },
              "paths": {
                "/v1/transfers": {
                  "post": {
                    "summary": "Move money",
                    "responses": { "201": { "description": "Created" } }
                  }
                }
              },
              "components": {
                "responses": { "api.Conflict": { "description": "[409] Conflict" } },
                "schemas": {
                  "Transfer": {
                    "type": "object",
                    "properties": {
                      "reference": { "type": "string" },
                      "state": { "type": "string", "enum": ["PENDING", "SETTLED"] }
                    },
                    "required": ["reference"]
                  }
                }
              }
            }
            """;

    @Test
    @DisplayName("an unchanged document produces no changes at all")
    void identicalDocumentsDoNotDiffer() {
        assertThat(changes(BEFORE, BEFORE)).isEmpty();
    }

    @Test
    @DisplayName("removing an error code is breaking, and the failure names the code")
    void removingAnErrorCodeIsBreaking() {
        // The change ERROR_CONTRACT.md §4 warns about: it fails at the customer end, in a `switch`
        // whose branch simply stops being reached.
        List<OpenApiChange> changes =
                changes(BEFORE, BEFORE.replace("\"api.Conflict\"", "\"api.Disagreement\""));

        assertThat(changes)
                .anySatisfy(
                        change -> {
                            assertThat(change.kind()).isEqualTo(REMOVED);
                            assertThat(change.pointer()).contains("api.Conflict");
                            assertThat(change.compatibility()).isEqualTo(BREAKING);
                        });
        assertThat(OpenApiChange.anyBreaking(changes)).isTrue();
    }

    @Test
    @DisplayName("changing a response status is breaking")
    void changingAStatusIsBreaking() {
        List<OpenApiChange> changes = changes(BEFORE, BEFORE.replace("\"201\"", "\"200\""));

        assertThat(OpenApiChange.anyBreaking(changes)).isTrue();
        assertThat(changes).anySatisfy(change -> assertThat(change.kind()).isEqualTo(REMOVED));
    }

    @Test
    @DisplayName("removing an endpoint is breaking")
    void removingAPathIsBreaking() {
        String after = BEFORE.replace("\"/v1/transfers\"", "\"/v1/payments\"");

        assertThat(OpenApiChange.anyBreaking(changes(BEFORE, after))).isTrue();
    }

    @Test
    @DisplayName("adding an endpoint is compatible")
    void addingAPathIsCompatible() {
        String after =
                BEFORE.replace(
                        "\"/v1/transfers\": {",
                        "\"/v1/payments\": { \"get\": { \"responses\": {} } }, \"/v1/transfers\": {");

        List<OpenApiChange> changes = changes(BEFORE, after);

        assertThat(changes).isNotEmpty().allSatisfy(change -> assertThat(change.kind()).isEqualTo(ADDED));
        assertThat(OpenApiChange.anyBreaking(changes)).isFalse();
    }

    @Test
    @DisplayName("adding an optional field is compatible; making it required is not")
    void addingARequiredFieldIsBreaking() {
        String optional = BEFORE.replace("\"reference\": { \"type\": \"string\" }",
                "\"reference\": { \"type\": \"string\" }, \"memo\": { \"type\": \"string\" }");
        assertThat(OpenApiChange.anyBreaking(changes(BEFORE, optional)))
                .as("a field a client may ignore does not break it")
                .isFalse();

        String required = optional.replace("\"required\": [\"reference\"]",
                "\"required\": [\"reference\", \"memo\"]");
        assertThat(changes(optional, required))
                .as("a new mandatory field rejects requests that used to be accepted")
                .anySatisfy(
                        change -> {
                            assertThat(change.pointer()).contains("/required/");
                            assertThat(change.kind()).isEqualTo(ADDED);
                            assertThat(change.compatibility()).isEqualTo(BREAKING);
                        });
    }

    @Test
    @DisplayName("adding an enum value is breaking, because a client has never seen it")
    void addingAnEnumValueIsBreaking() {
        String after = BEFORE.replace("[\"PENDING\", \"SETTLED\"]", "[\"PENDING\", \"SETTLED\", \"RETURNED\"]");

        assertThat(changes(BEFORE, after))
                .anySatisfy(
                        change -> {
                            assertThat(change.kind()).isEqualTo(ADDED);
                            assertThat(change.compatibility()).isEqualTo(BREAKING);
                        });
    }

    @Test
    @DisplayName("rewording prose is compatible, whether added, removed or changed")
    void proseIsNeverBreaking() {
        String reworded = BEFORE.replace("\"Move money\"", "\"Initiate a transfer\"")
                .replace("\"[409] Conflict\"", "\"[409] The request conflicts.\"");

        List<OpenApiChange> changes = changes(BEFORE, reworded);

        assertThat(changes).isNotEmpty().allSatisfy(change -> {
            assertThat(change.kind()).isEqualTo(CHANGED);
            assertThat(change.compatibility()).isEqualTo(COMPATIBLE);
        });
        assertThat(OpenApiChange.anyBreaking(changes)).isFalse();
    }

    @Test
    @DisplayName("deleting prose from a container that survives is compatible")
    void deletingProseFromASurvivingContainerIsCompatible() {
        // The other half of the removal rule. Dropping a summary from an operation that still has
        // its responses takes nothing away from a client - and calling it breaking would train
        // people to read the label as noise, which is how a real BREAKING gets waved through.
        String after = BEFORE.replace("\"summary\": \"Move money\",", "");

        List<OpenApiChange> changes = changes(BEFORE, after);

        assertThat(changes)
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.kind()).isEqualTo(REMOVED);
                            assertThat(change.compatibility()).isEqualTo(COMPATIBLE);
                        });
    }

    @Test
    @DisplayName("a string and a number at the same pointer are not the same value")
    void aTypeChangeIsSeen() {
        // "1" and 1 render identically through a text accessor, and a client parsing one and
        // receiving the other fails at run time. Flattening keeps the JSON representation so the
        // difference survives.
        String before = "{ \"info\": { \"version\": \"1\" } }";
        String after = "{ \"info\": { \"version\": 1 } }";

        assertThat(changes(before, after))
                .singleElement()
                .satisfies(
                        change -> {
                            assertThat(change.kind()).isEqualTo(CHANGED);
                            assertThat(change.compatibility()).isEqualTo(BREAKING);
                        });
    }

    @Test
    @DisplayName("emptying a container is a change, not a silent disappearance")
    void emptyingAContainerIsSeen() {
        String before = "{ \"paths\": { \"/v1/transfers\": { \"get\": {} } } }";
        String after = "{ \"paths\": {} }";

        List<OpenApiChange> changes = changes(before, after);

        assertThat(changes).isNotEmpty();
        assertThat(OpenApiChange.anyBreaking(changes)).isTrue();
    }

    @Test
    @DisplayName("the report says what changed, so the failure can be acted on without a diff tool")
    void theReportIsReadable() {
        String after = BEFORE.replace("\"api.Conflict\"", "\"api.Disagreement\"");

        assertThat(changes(BEFORE, after))
                .extracting(Object::toString)
                .anySatisfy(line -> assertThat(line).contains("BREAKING").contains("api.Conflict"));
    }

    // -----------------------------------------------------------------

    private static List<OpenApiChange> changes(String before, String after) {
        JsonNode left = OpenApiDocument.parse(before);
        JsonNode right = OpenApiDocument.parse(after);
        return OpenApiChange.between(left, right);
    }
}
