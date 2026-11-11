package com.finapp.app.lending;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * {@code lending.LoanApplicationSubmitted} keeps its {@code partyId} (`P15-TSK-023`, deciding the privacy residue A5-07 through
 * {@code P15-TSK-027}'s catalogue), and this test holds the reason, so the decision is taken again the day the reason stops being
 * true.
 *
 * <p><strong>The reason.</strong> The pairing "this party applied for credit" is on the broker either way: the same event names
 * {@code decisionRequestId}, and {@code credit.CreditDecisionRequested} - which credit cannot decide without - carries that request
 * with its {@code partyId}. Dropping the field would publish a version 2 every consumer must tolerate and protect nothing a reader of
 * the topic could not join back in one step. The event never reaches telemetry: the relay and the receiver log a type and an id,
 * never a payload, and a span carries no payload field. If credit's event ever stops carrying the party, or this event stops naming
 * the request, the pairing would be this event's alone, and dropping it as a new version becomes worth its cost: this fails then.
 */
@DisplayName("LoanApplicationSubmitted keeps partyId because the broker carries the pairing anyway; the reason is held (P15-TSK-023)")
class ApplicantPartyOnTheBrokerTest {

    @Test
    @DisplayName("the submitted event names the party and the request, and credit's own event carries the same request with its party")
    void thePairingIsOnTheBrokerEitherWay() throws IOException {
        Map<String, Object> events = events();
        assertThat(fields(events, "lending.LoanApplicationSubmitted", 1)).containsKeys("partyId", "decisionRequestId");
        assertThat(fields(events, "credit.CreditDecisionRequested", 1)).containsKey("partyId");
        assertThat(versions(events, "lending.LoanApplicationSubmitted")).as("no second version was published").containsOnlyKeys(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> events, String type, int version) {
        Map<String, Object> declared = (Map<String, Object>) versions(events, type).get(version);
        assertThat(declared).as("%s version %s", type, version).isNotNull();
        return (Map<String, Object>) declared.get("fields");
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> versions(Map<String, Object> events, String type) {
        Map<Object, Object> versions = (Map<Object, Object>) events.get(type);
        assertThat(versions).as("%s is catalogued", type).isNotNull();
        return versions;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> events() throws IOException {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("docs/events/event-catalogue.yaml"))) {
            here = here.getParent();
        }
        assertThat(here).as("the catalogue").isNotNull();
        Map<String, Object> catalogue = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(here.resolve("docs/events/event-catalogue.yaml")));
        return (Map<String, Object>) catalogue.get("events");
    }
}
