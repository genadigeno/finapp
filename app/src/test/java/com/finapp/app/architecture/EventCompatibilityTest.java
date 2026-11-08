package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What "additive" means, on planted catalogues (`P15-TSK-027`): every refusal a release can meet and
 * every change it is allowed. {@code EventCatalogueTest} holds the same rules on the real code.
 */
@DisplayName("the additive rule of the event catalogue (P15-TSK-027)")
class EventCompatibilityTest {

    private static final String RELEASED =
            """
            release: "planted"
            events:
              "probe.Event":
                1:
                  producer: "probe"
                  fields:
                    "id": {kind: "uuid", required: true}
                    "amountMinor": {kind: "integer", required: true}
                    "status": {kind: "enum(com.example.Status)", required: true}
                    "kind": {kind: "literal(A|B)", required: true}
                    "reference": {kind: "identifier", required: true}
                    "note": {kind: "identifier", required: false}
            """;

    private static EventCatalogue released() {
        return EventCatalogue.parse(RELEASED);
    }

    private static List<String> refusalsOf(String current) {
        return released().refusals(EventCatalogue.parse(current));
    }

    private static String current(String... replacements) {
        String text = RELEASED;
        for (int i = 0; i < replacements.length; i += 2) {
            assertThat(text).contains(replacements[i]);
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return text;
    }

    @Test
    @DisplayName("an identical catalogue is accepted")
    void nothingChanged() {
        assertThat(refusalsOf(RELEASED)).isEmpty();
    }

    @Test
    @DisplayName("a removed field is refused")
    void aRemovedFieldIsRefused() {
        String current = current("    \"note\": {kind: \"identifier\", required: false}\n", "");

        assertThat(refusalsOf(current)).singleElement().asString().contains("probe.Event v1", "note", "removed");
    }

    @Test
    @DisplayName("a changed kind is refused")
    void aTypeChangeIsRefused() {
        String current = current("\"amountMinor\": {kind: \"integer\"", "\"amountMinor\": {kind: \"decimal\"");

        assertThat(refusalsOf(current)).singleElement().asString().contains("amountMinor", "integer", "decimal");
    }

    @Test
    @DisplayName("another enumeration behind a field is a change of meaning, refused")
    void aDifferentEnumerationIsRefused() {
        String current = current("enum(com.example.Status)", "enum(com.example.OtherStatus)");

        assertThat(refusalsOf(current)).singleElement().asString().contains("status");
    }

    @Test
    @DisplayName("a literal value withdrawn is a change of meaning, refused; one added is accepted")
    void aWithdrawnLiteralIsRefused() {
        assertThat(refusalsOf(current("literal(A|B)", "literal(A)"))).singleElement().asString().contains("kind");
        assertThat(refusalsOf(current("literal(A|B)", "literal(A|B|C)"))).isEmpty();
    }

    @Test
    @DisplayName("an identifier narrowed to something more exact is accepted, and the reverse refused")
    void narrowingIsAcceptedAndWideningRefused() {
        assertThat(refusalsOf(current("\"reference\": {kind: \"identifier\"", "\"reference\": {kind: \"uuid\""))).isEmpty();
        assertThat(refusalsOf(current("\"id\": {kind: \"uuid\"", "\"id\": {kind: \"identifier\"")))
                .singleElement()
                .asString()
                .contains("id");
    }

    @Test
    @DisplayName("a field every record carried, now written only sometimes, is refused")
    void requiredMadeOptionalIsRefused() {
        String current = current("\"id\": {kind: \"uuid\", required: true}", "\"id\": {kind: \"uuid\", required: false}");

        assertThat(refusalsOf(current)).singleElement().asString().contains("id", "always written");
    }

    @Test
    @DisplayName("an added field is accepted under the same version")
    void anAddedFieldIsAccepted() {
        String current =
                current(
                        "    \"note\": {kind: \"identifier\", required: false}\n",
                        "    \"note\": {kind: \"identifier\", required: false}\n"
                                + "        \"scale\": {kind: \"integer\", required: true}\n");

        assertThat(refusalsOf(current)).isEmpty();
    }

    @Test
    @DisplayName("a new version that only adds is accepted, the old one kept on the wire")
    void anAdditiveVersionIsAccepted() {
        String newVersion =
                current(
                        "    1:\n", "    2:\n",
                        "    \"note\": {kind: \"identifier\", required: false}\n",
                        "    \"note\": {kind: \"identifier\", required: false}\n"
                                + "        \"scale\": {kind: \"integer\", required: true}\n");

        assertThat(refusalsOf(newVersion)).isEmpty();
    }

    @Test
    @DisplayName("a new version that removes a field is refused")
    void aBreakingVersionIsRefused() {
        String newVersion =
                current("    1:\n", "    2:\n", "    \"note\": {kind: \"identifier\", required: false}\n", "");

        assertThat(refusalsOf(newVersion)).singleElement().asString().contains("v2 (over v1)", "note", "removed");
    }

    @Test
    @DisplayName("a version going backwards is refused")
    void aVersionGoingBackwardsIsRefused() {
        EventCatalogue released = EventCatalogue.parse(current("    1:\n", "    2:\n"));

        assertThat(released.refusals(EventCatalogue.parse(RELEASED)))
                .anyMatch(refusal -> refusal.contains("went backwards"));
    }

    @Test
    @DisplayName("an event type nothing writes any more is refused")
    void aRemovedTypeIsRefused() {
        assertThat(refusalsOf(current("\"probe.Event\":", "\"probe.Another\":")))
                .singleElement()
                .asString()
                .contains("probe.Event", "no producer");
    }

    @Test
    @DisplayName("a type the release record retires is accepted, gone from the next catalogue, and refused while still written")
    void aRetiredTypeIsRecorded() {
        EventCatalogue withoutIt = EventCatalogue.parse(current("\"probe.Event\":", "\"probe.Another\":"));

        assertThat(released().refusals(withoutIt, Set.of("probe.Event"))).isEmpty();
        assertThat(released().rolled(withoutIt, Set.of("probe.Event"), "next").events()).doesNotContainKey("probe.Event");
        assertThat(released().refusals(released(), Set.of("probe.Event")))
                .singleElement()
                .asString()
                .contains("recorded as retired");
    }

    @Test
    @DisplayName("a new event type is accepted")
    void aNewTypeIsAccepted() {
        String withANewType =
                RELEASED + "  \"probe.New\":\n    1:\n      producer: \"probe\"\n      fields:\n        \"a\": {kind: \"uuid\", required: true}\n";

        assertThat(refusalsOf(withANewType)).isEmpty();
    }

    @Test
    @DisplayName("a type moved to another producer is refused: its consumers subscribe to the first topic")
    void aMovedProducerIsRefused() {
        assertThat(refusalsOf(current("producer: \"probe\"", "producer: \"elsewhere\"")))
                .singleElement()
                .asString()
                .contains("elsewhere");
    }

    @Test
    @DisplayName("rolled forward, an added field is not required, and a retired version stays for what is on the wire")
    void rollingForwardKeepsWhatIsOnTheWire() {
        String addedInPlace =
                current(
                        "    \"note\": {kind: \"identifier\", required: false}\n",
                        "    \"note\": {kind: \"identifier\", required: false}\n"
                                + "        \"scale\": {kind: \"integer\", required: true}\n");

        EventCatalogue rolled = released().rolled(EventCatalogue.parse(addedInPlace), Set.of(), "next");

        assertThat(rolled.events().get("probe.Event").get(1).fields().get("scale").required())
                .as("the records written before it do not carry it")
                .isFalse();

        EventCatalogue successor = released().rolled(EventCatalogue.parse(current("    1:\n", "    2:\n")), Set.of(), "next");

        assertThat(successor.events().get("probe.Event")).containsOnlyKeys(1, 2);
        assertThat(EventCatalogue.parse(successor.render()).events())
                .as("the rendered form reads back as what it renders")
                .isEqualTo(successor.events());
    }
}
