package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.HandlerSet.Handler;
import com.finapp.app.architecture.HandlerSet.HandlerStep;
import com.finapp.app.architecture.HandlerSet.ReleaseRecord;
import com.finapp.app.architecture.HandlerSet.VersionStep;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The handler-set diff on planted sets (`P15-TSK-027`): a handler added or removed with neither a
 * recorded replay nor a new group with recorded offsets is refused.
 */
@DisplayName("the handler-set diff (P15-TSK-027)")
class HandlerSetDiffTest {

    private static final Handler EXISTING = new Handler("finapp.risk", "finapp.party", "party.CustomerOpened", "risk.signals");
    private static final Handler ADDED_TO_EXISTING_GROUP =
            new Handler("finapp.risk", "finapp.party", "party.PartyArchived", "risk.signals");
    private static final Handler IN_A_NEW_GROUP =
            new Handler("finapp.audit", "finapp.party", "party.CustomerOpened", "audit.trail");

    private static final ReleaseRecord NOTHING = new ReleaseRecord(List.of(), List.of());

    private static SortedSet<Handler> set(Handler... handlers) {
        return new TreeSet<>(List.of(handlers));
    }

    private static ReleaseRecord replay(Handler handler, boolean added, String type, String... windows) {
        return new ReleaseRecord(List.of(new HandlerStep(handler, added, type, Set.of(windows), null, false)), List.of());
    }

    private static ReleaseRecord offsets(Handler handler, String offsets, boolean idempotent) {
        return new ReleaseRecord(List.of(new HandlerStep(handler, true, null, Set.of(), offsets, idempotent)), List.of());
    }

    private static List<String> refusalsAdding(Handler handler, ReleaseRecord record) {
        return HandlerSet.refusals(set(EXISTING), set(EXISTING, handler), record);
    }

    @Test
    @DisplayName("an unchanged set needs no record")
    void nothingChanged() {
        assertThat(HandlerSet.refusals(set(EXISTING), set(EXISTING), NOTHING)).isEmpty();
    }

    @Test
    @DisplayName("a handler added to an existing group with no recorded replay is refused")
    void aPlantedHandlerIsRefused() {
        assertThat(refusalsAdding(ADDED_TO_EXISTING_GROUP, NOTHING))
                .singleElement()
                .asString()
                .contains("party.PartyArchived", "neither a recorded replay nor a new group");
    }

    @Test
    @DisplayName("recorded with a replay of its type over the rollout and rollback windows, it is accepted")
    void aRecordedReplayIsAccepted() {
        assertThat(
                        refusalsAdding(
                                ADDED_TO_EXISTING_GROUP,
                                replay(ADDED_TO_EXISTING_GROUP, true, "party.PartyArchived", "rollout", "rollback")))
                .isEmpty();
    }

    @Test
    @DisplayName("a replay of another type, or missing the rollback window, is refused")
    void anIncompleteReplayIsRefused() {
        assertThat(
                        refusalsAdding(
                                ADDED_TO_EXISTING_GROUP,
                                replay(ADDED_TO_EXISTING_GROUP, true, "party.CustomerOpened", "rollout", "rollback")))
                .singleElement()
                .asString()
                .contains("another type");
        assertThat(refusalsAdding(ADDED_TO_EXISTING_GROUP, replay(ADDED_TO_EXISTING_GROUP, true, "party.PartyArchived", "rollout")))
                .singleElement()
                .asString()
                .contains("rollback");
    }

    @Test
    @DisplayName("starting offsets do not stand in for a replay in a group the last release already had")
    void offsetsDoNotServeAnExistingGroup() {
        assertThat(refusalsAdding(ADDED_TO_EXISTING_GROUP, offsets(ADDED_TO_EXISTING_GROUP, "latest", false)))
                .singleElement()
                .asString()
                .contains("needs a recorded replay");
    }

    @Test
    @DisplayName("a handler in a new group needs recorded starting offsets")
    void aNewGroupNeedsOffsets() {
        assertThat(refusalsAdding(IN_A_NEW_GROUP, NOTHING)).singleElement().asString().contains("neither");
        assertThat(refusalsAdding(IN_A_NEW_GROUP, replay(IN_A_NEW_GROUP, true, "party.CustomerOpened", "rollout", "rollback")))
                .singleElement()
                .asString()
                .contains("starting offsets");
        assertThat(refusalsAdding(IN_A_NEW_GROUP, offsets(IN_A_NEW_GROUP, "latest", false))).isEmpty();
        assertThat(refusalsAdding(IN_A_NEW_GROUP, offsets(IN_A_NEW_GROUP, "2026-11-08T00:00:00Z", false))).isEmpty();
    }

    @Test
    @DisplayName("earliest is refused unless the handler is naturally idempotent, and an unreadable choice always")
    void earliestNeedsNaturalIdempotency() {
        assertThat(refusalsAdding(IN_A_NEW_GROUP, offsets(IN_A_NEW_GROUP, "earliest", false)))
                .singleElement()
                .asString()
                .contains("whole retention");
        assertThat(refusalsAdding(IN_A_NEW_GROUP, offsets(IN_A_NEW_GROUP, "earliest", true))).isEmpty();
        assertThat(refusalsAdding(IN_A_NEW_GROUP, offsets(IN_A_NEW_GROUP, "soon", true)))
                .singleElement()
                .asString()
                .contains("must be earliest, latest or an instant");
    }

    @Test
    @DisplayName("a removed handler needs a replay over the rollback window, since a rollback would have missed it")
    void aRemovedHandlerIsDiffedToo() {
        assertThat(HandlerSet.refusals(set(EXISTING, ADDED_TO_EXISTING_GROUP), set(EXISTING), NOTHING))
                .singleElement()
                .asString()
                .contains("removed", "rollback");
        assertThat(
                        HandlerSet.refusals(
                                set(EXISTING, ADDED_TO_EXISTING_GROUP),
                                set(EXISTING),
                                replay(ADDED_TO_EXISTING_GROUP, false, "party.PartyArchived", "rollback")))
                .isEmpty();
    }

    @Test
    @DisplayName("a consumer renamed is a removal and an addition, each needing its step")
    void aRenameIsBothDirections() {
        Handler renamed = new Handler("finapp.risk", "finapp.party", "party.CustomerOpened", "risk.intake");

        assertThat(HandlerSet.refusals(set(EXISTING), set(renamed), NOTHING)).hasSize(2);
    }

    @Test
    @DisplayName("a recorded step for a handler that did not change is refused, so a stale record cannot pass for the next release's")
    void aStaleRecordIsRefused() {
        assertThat(HandlerSet.refusals(set(EXISTING), set(EXISTING), replay(EXISTING, true, "party.CustomerOpened", "rollout", "rollback")))
                .singleElement()
                .asString()
                .contains("recorded as added, and it was not");
    }

    @Test
    @DisplayName("a handler on an undeclared topic, or for a type its topic never carries, consumes nothing")
    void aHandlerMustListenToSomethingReal() {
        Map<String, Set<String>> produced = Map.of("finapp.party", Set.of("party.CustomerOpened"));

        assertThat(HandlerSet.unsubscribable(set(EXISTING), Set.of("finapp.party"), produced)).isEmpty();
        assertThat(HandlerSet.unsubscribable(set(EXISTING), Set.of("finapp.other"), produced))
                .singleElement()
                .asString()
                .contains("topic catalogue");
        assertThat(HandlerSet.unsubscribable(set(ADDED_TO_EXISTING_GROUP), Set.of("finapp.party"), produced))
                .singleElement()
                .asString()
                .contains("nothing writes");
    }

    private static final String CATALOGUE_AT_ONE =
            """
            release: "planted"
            events:
              "party.CustomerOpened":
                1:
                  producer: "party"
                  fields:
                    "id": {kind: "uuid", required: true}
            """;

    private static EventCatalogue atVersionTwo() {
        return EventCatalogue.parse(CATALOGUE_AT_ONE.replace("    1:\n", "    2:\n"));
    }

    @Test
    @DisplayName("a new version of a consumed type is refused until the release records that its consumers learned it")
    void aConsumedTypeWaitsForItsConsumers() {
        EventCatalogue released = EventCatalogue.parse(CATALOGUE_AT_ONE);

        assertThat(HandlerSet.unlearnedVersions(released, atVersionTwo(), set(EXISTING), NOTHING))
                .singleElement()
                .asString()
                .contains("party.CustomerOpened v2");
        assertThat(
                        HandlerSet.unlearnedVersions(
                                released,
                                atVersionTwo(),
                                set(EXISTING),
                                new ReleaseRecord(List.of(), List.of(new VersionStep("party.CustomerOpened", 2, "2026.11")))))
                .isEmpty();
        assertThat(HandlerSet.unlearnedVersions(released, atVersionTwo(), set(IN_A_NEW_GROUP), NOTHING))
                .as("the same type consumed elsewhere is the same refusal")
                .hasSize(1);
        assertThat(HandlerSet.unlearnedVersions(released, atVersionTwo(), new TreeSet<>(), NOTHING))
                .as("a type nobody consumes may move")
                .isEmpty();
    }

    @Test
    @DisplayName("the release record parses both kinds of step")
    void theRecordParses() {
        ReleaseRecord record =
                ReleaseRecord.parse(
                        """
                        handlers:
                          - {group: finapp.risk, topic: finapp.party, eventType: party.PartyArchived, consumerName: risk.signals,
                             change: added, replay: {eventType: party.PartyArchived, windows: [rollout, rollback]}}
                          - {group: finapp.audit, topic: finapp.party, eventType: party.CustomerOpened, consumerName: audit.trail,
                             newGroup: {startingOffsets: earliest, naturallyIdempotent: true}}
                          - {group: finapp.other, topic: finapp.party, eventType: party.CustomerOpened, consumerName: other.trail,
                             newGroup: {startingOffsets: 2026-11-08T00:00:00Z}}
                        versions:
                          - {eventType: party.CustomerOpened, version: 2, consumersLearnedItIn: "2026.11"}
                        """);

        assertThat(record.handlers()).hasSize(3);
        assertThat(record.handlers().get(2).startingOffsets())
                .as("an unquoted instant is read as the instant it is")
                .isEqualTo("2026-11-08T00:00:00Z");
        assertThat(record.handlers().get(0).replayWindows()).containsExactlyInAnyOrder("rollout", "rollback");
        assertThat(record.handlers().get(1).startingOffsets()).isEqualTo("earliest");
        assertThat(record.handlers().get(1).naturallyIdempotent()).isTrue();
        assertThat(record.versions()).singleElement().extracting(VersionStep::version).isEqualTo(2);
    }
}
