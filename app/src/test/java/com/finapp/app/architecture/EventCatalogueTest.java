package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No release changes an event in a way the previous release's consumers cannot read
 * (`P15-TSK-027`, ADR-0122 decision 6).
 *
 * <p>The catalogue is read from the compiled code ({@link EventFlows}) and compared with the
 * committed one, which stands for the last release ({@link EventCatalogue#RELEASED}). There is no
 * list here of what exists: a type, a field or a version is in the catalogue because some method
 * writes it.
 *
 * <p>The comparison's own rules are held on planted catalogues by {@code EventCompatibilityTest};
 * this class holds them on the real code.
 */
@Tag("architecture")
@DisplayName("every event is catalogued from the code, and additive over the last release (P15-TSK-027)")
class EventCatalogueTest {

    /** Where the next catalogue is left, so that the release procedure can commit it. */
    static final Path GENERATED = Path.of("build", "event-catalogue", "event-catalogue.yaml");

    private static final String MINOR = "Minor";

    /** The types {@code X-TSK-014} and the Phase 9 residue moved to an additive version 2. */
    private static final Set<String> ADDITIVE_VERSION_TWO =
            Set.of("merchant.FeeAssessed", "merchant.FeeReturned", "crossborder.CrossBorderPaymentReturned");

    private static EventFlows.Result read;
    private static EventCatalogue current;
    private static EventCatalogue released;
    private static Set<String> retired;

    @BeforeAll
    static void read() throws IOException {
        read = EventCatalogue.flows();
        current = EventCatalogue.of(read.flows());
        Files.createDirectories(GENERATED.getParent());
        released = EventCatalogue.released();
        retired = HandlerSet.ReleaseRecord.committed().retired();
        Files.writeString(
                GENERATED,
                released.rolled(current, retired, "the next release").render(),
                StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------
    // The reading is whole
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every write to the outbox is read to a closed producer, type, version and payload")
    void everyWriteIsReadable() {
        assertThat(read.unreadable())
                .as("name the type and the producer as constants, and build the payload through EventPayload:"
                        + " a contract the build cannot read is a contract it cannot hold")
                .isEmpty();
    }

    @Test
    @DisplayName("it reads every construction site the producer scanner sees, and the scanner's own constants agree")
    void theReadingCoversEveryConstructionSite() {
        Set<String> byThisReader =
                read.flows().stream().map(flow -> flow.owner() + "#" + flow.method()).collect(Collectors.toCollection(TreeSet::new));
        var sites = EnvelopeProducers.inProductionCode();

        assertThat(byThisReader)
                .as("the second, independent reading of the same production code")
                .containsExactlyInAnyOrderElementsOf(
                        sites.stream().map(site -> site.owner() + "#" + site.method()).collect(Collectors.toCollection(TreeSet::new)));

        // Where the scanner saw a constant type at the site, this reader must name that type there.
        for (EnvelopeProducers.Site site : sites) {
            if (site.eventType() != null) {
                assertThat(read.flows())
                        .as("%s#%s writes %s", site.owner(), site.method(), site.eventType())
                        .anyMatch(flow -> flow.owner().equals(site.owner())
                                && flow.method().equals(site.method())
                                && flow.eventType().equals(site.eventType())
                                && flow.producer().equals(site.producer()));
            }
        }
    }

    @Test
    @DisplayName("the catalogue is not empty: event types from every producing module, with fields")
    void theCatalogueIsNotVacuous() {
        assertThat(current.events()).as("event types").hasSizeGreaterThan(100);
        assertThat(read.flows().stream().map(EventFlows.Flow::module).collect(Collectors.toSet()))
                .as("every business module that announces a fact")
                .hasSizeGreaterThan(10);
        assertThat(current.events().get("merchant.FeeAssessed").lastEntry().getValue().fields())
                .as("a payload built in the same method as its envelope")
                .containsKeys("paymentIntentId", "grossMinor", "feeMinor", "netMinor", "currency");
        assertThat(current.events().get("crossborder.CrossBorderPaymentDelivered").lastEntry().getValue().fields())
                .as("a payload built by a caller and finished by a helper")
                .containsKeys("payment", "status");
    }

    // -----------------------------------------------------------------
    // Against the last release
    // -----------------------------------------------------------------

    @Test
    @DisplayName("what the code writes is additive over the last release")
    void onlyAdditiveChangesSinceTheLastRelease() {
        assertThat(released.refusals(current, retired))
                .as("a change the last release's consumers cannot read; see EVENT_ARCHITECTURE.md, Schema evolution")
                .isEmpty();
    }

    @Test
    @DisplayName("one event type has one live version")
    void oneLiveVersionPerType() {
        Map<String, Set<Integer>> split = new TreeMap<>();
        for (EventFlows.Flow flow : read.flows()) {
            split.computeIfAbsent(flow.eventType(), ignored -> new TreeSet<>()).add(flow.version());
        }
        split.values().removeIf(versions -> versions.size() == 1);

        assertThat(split).as("two versions of one type written side by side").isEmpty();
    }

    @Test
    @DisplayName("the additive version 2s are accepted over their released version 1, and carry what they add")
    void theAdditiveVersionsAreAccepted() {
        for (String type : ADDITIVE_VERSION_TWO) {
            assertThat(released.events().get(type)).as("%s: version 1 stays catalogued, its records on the wire", type).containsKey(1);
            assertThat(current.events().get(type).lastKey()).as("%s is written at version 2 or later", type).isGreaterThanOrEqualTo(2);
        }
        assertThat(current.events().get("merchant.FeeAssessed").get(2).fields()).containsKey("scale");
        assertThat(current.events().get("merchant.FeeReturned").get(2).fields()).containsKey("scale");
        assertThat(current.events().get("crossborder.CrossBorderPaymentReturned").get(2).fields())
                .containsKeys("returnedMinor", "returnedCurrency", "returnedScale");
    }

    // -----------------------------------------------------------------
    // Money on the wire
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every <name>Minor field is integer minor units beside an explicit currency and scale (DOD-EVENT)")
    void everyAmountHasItsCurrencyAndScale() {
        Map<String, String> defects = new TreeMap<>();
        current.events()
                .forEach(
                        (type, versions) ->
                                versions.forEach(
                                        (number, version) ->
                                                version.fields()
                                                        .forEach(
                                                                (name, field) -> {
                                                                    if (!name.endsWith(MINOR)) {
                                                                        return;
                                                                    }
                                                                    String defect = amountDefect(name, field, version);
                                                                    if (defect != null) {
                                                                        defects.put(type + " v" + number + " " + name, defect);
                                                                    }
                                                                })));

        assertThat(defects).as("amounts on the wire").isEmpty();
    }

    private static String amountDefect(String name, EventCatalogue.Field field, EventCatalogue.Version version) {
        boolean integer =
                field.kind().equals("integer")
                        || (field.kind().startsWith("literal(") && field.kind().matches("literal\\([0-9|]+\\)"));
        if (!integer) {
            return "not integer minor units: " + field.kind();
        }
        String stem = name.substring(0, name.length() - MINOR.length());
        var fields = version.fields();
        if (!fields.containsKey(stem + "Currency") && !fields.containsKey(stem + "CurrencyCode") && !fields.containsKey("currency")) {
            return "no currency beside it";
        }
        if (!fields.containsKey(stem + "Scale") && !fields.containsKey("scale")) {
            return "no scale beside it";
        }
        return null;
    }

    @Test
    @DisplayName("no field anywhere is written from a floating-point or decimal value")
    void noFloatingPointOnTheWire() {
        Map<String, String> decimals = new TreeMap<>();
        current.events()
                .forEach(
                        (type, versions) ->
                                versions.forEach(
                                        (number, version) ->
                                                version.fields()
                                                        .forEach(
                                                                (name, field) -> {
                                                                    if (field.kind().equals("decimal")) {
                                                                        decimals.put(type + " v" + number, name);
                                                                    }
                                                                })));

        assertThat(decimals).as("a float or a double never reaches an event (CLAUDE.md rule 1)").isEmpty();
    }

    // -----------------------------------------------------------------
    // One version per payments type (Phase 7 residue #49)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every payments event takes its version from its own type's declaration")
    void everyPaymentsTypeVersionsItself() {
        List<EventFlows.Flow> payments =
                read.flows().stream().filter(flow -> flow.producer().equals("payments")).toList();

        assertThat(payments).as("payments announces facts").hasSizeGreaterThan(10);
        assertThat(EventCatalogue.sharedVersions(payments))
                .as("a type bumped through a version another type also reads moves both on the wire")
                .isEmpty();
    }

    /** The committed file exists and reads back as a catalogue with a release label. */
    @Test
    @DisplayName("the committed catalogue names the release it stands for")
    void theCommittedCatalogueNamesItsRelease() {
        assertThat(RepositoryPaths.locate(EventCatalogue.RELEASED)).exists();
        assertThat(released.release()).as("the last release this file stands for").isNotBlank();
    }
}
