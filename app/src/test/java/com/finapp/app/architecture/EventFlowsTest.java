package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EventFlows.Flow;
import com.finapp.app.architecture.eventprobe.EventFlowFixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The reader the event catalogue rests on reads what it claims to (`P15-TSK-027`): each shape a
 * producer takes, and anything else as unreadable - never as a wrong value.
 */
@DisplayName("the event flow reader (P15-TSK-027)")
class EventFlowsTest {

    private static final EventFlows.Result READ =
            EventFlows.in((type, eventType) -> 7, List.of(TopicCatalogueTest.bytesOf(EventFlowFixtures.class)));

    private static final Map<String, Flow> FLOWS =
            READ.flows().stream().collect(Collectors.toMap(Flow::eventType, flow -> flow, (a, b) -> a));

    @Test
    @DisplayName("a payload built beside its envelope is read with each value's kind")
    void aDirectPayloadIsRead() {
        Flow flow = FLOWS.get("probe.Direct");

        assertThat(flow.producer()).isEqualTo("probe");
        assertThat(flow.version()).isEqualTo(3);
        assertThat(flow.fields().get("id").kind()).isEqualTo("uuid");
        assertThat(flow.fields().get("amountMinor").kind()).isEqualTo("integer");
        assertThat(flow.fields().get("status").kind()).startsWith("enum(").endsWith("EventFlowFixtures$Status)");
        assertThat(flow.fields().get("kind").kind()).isEqualTo("literal(FIXED)");
        assertThat(flow.fields().get("reference").kind())
                .as("a parameter no caller names: a string the build knows nothing more about")
                .isEqualTo("identifier");
        assertThat(flow.fields().values()).allMatch(EventFlows.Field::required);
    }

    @Test
    @DisplayName("a type and payload handed to a helper are read per caller, the helper's own field included")
    void aHelperIsReadPerCaller() {
        assertThat(FLOWS.get("probe.HelperA").fields()).containsOnlyKeys("fromA", "always");
        assertThat(FLOWS.get("probe.HelperB").fields()).containsOnlyKeys("fromB", "always");
    }

    @Test
    @DisplayName("a type chosen by a ternary is both of its types")
    void aTernaryIsBothTypes() {
        assertThat(FLOWS).containsKeys("probe.Left", "probe.Right");
    }

    @Test
    @DisplayName("a field set on only one path is not required; the fields around it are")
    void aBranchMakesAFieldOptional() {
        Map<String, EventFlows.Field> fields = FLOWS.get("probe.Conditional").fields();

        assertThat(fields.get("first").required()).isTrue();
        assertThat(fields.get("last").required()).isTrue();
        assertThat(fields.get("maybe").required()).isFalse();
    }

    @Test
    @DisplayName("a numbered field written in a loop is catalogued by its stem, never required")
    void aLoopedFieldIsNumbered() {
        Map<String, EventFlows.Field> fields = FLOWS.get("probe.Numbered").fields();

        assertThat(fields).containsKey("reason#");
        assertThat(fields.get("reason#").required()).isFalse();
        assertThat(fields.get("count").required()).isTrue();
    }

    @Test
    @DisplayName("a payload made and widened by helpers carries what they add")
    void payloadHelpersAreFollowed() {
        Map<String, EventFlows.Field> fields = FLOWS.get("probe.Helpers").fields();

        assertThat(fields).containsOnlyKeys("base", "minor");
        assertThat(fields.get("minor").kind()).isEqualTo("integer");
    }

    @Test
    @DisplayName("a write inside a lambda takes its type from what the lambda captured")
    void aLambdaIsFollowed() {
        assertThat(FLOWS).containsKey("probe.Lambda");
    }

    @Test
    @DisplayName("a floating-point value reads as a decimal, whichever way it was turned into text")
    void floatingPointIsSeen() {
        Map<String, EventFlows.Field> fields = FLOWS.get("probe.Floating").fields();

        assertThat(fields.get("rate").kind()).isEqualTo("decimal");
        assertThat(fields.get("other").kind()).isEqualTo("decimal");
        assertThat(FLOWS.get("probe.Concatenated").fields().get("rate").kind())
                .as("concatenated into the text")
                .isEqualTo("decimal");
    }

    @Test
    @DisplayName("what cannot be read is reported, never guessed")
    void theUnreadableIsReported() {
        assertThat(READ.unreadable())
                .anyMatch(line -> line.contains("computedType"))
                .anyMatch(line -> line.contains("noPayloadBuilder"))
                .anyMatch(line -> line.contains("uncalledParameter"))
                .anyMatch(line -> line.contains("handedToAnInterface"))
                .anyMatch(line -> line.contains("reboundInALoop"))
                .hasSize(5);
    }

    @Test
    @DisplayName("a per-type version is read with the type it names; sharing one, or a bare number, is refused")
    void aSharedVersionIsRefused() {
        assertThat(FLOWS.get("probe.Own").versionDeclaredFor()).isEqualTo("probe.Own");
        assertThat(FLOWS.get("probe.Own").version()).as("resolved through the declaration").isEqualTo(7);
        assertThat(FLOWS.get("probe.Sharing").versionDeclaredFor()).isEqualTo("probe.Own");
        assertThat(FLOWS.get("probe.Bare").versionDeclaredFor()).isNull();

        assertThat(EventCatalogue.sharedVersions(List.of(FLOWS.get("probe.Own")))).isEmpty();
        assertThat(EventCatalogue.sharedVersions(List.of(FLOWS.get("probe.Sharing"))))
                .singleElement()
                .asString()
                .contains("probe.Sharing", "the version of probe.Own");
        assertThat(EventCatalogue.sharedVersions(List.of(FLOWS.get("probe.Bare"))))
                .singleElement()
                .asString()
                .contains("a bare number");
    }
}
