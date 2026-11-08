package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EventCatalogue;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.risk.SignalKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The one consumer that reads a payload parses every shape the catalogue holds (`P15-TSK-027`).
 *
 * <p>{@link SignalMappings} is the only reader of event payloads: every other handler reacts to the
 * envelope and asks the owning module. So "a consumer can parse the old and the new version" is, for
 * each event it reads, this test: a payload is synthesised from the catalogue's own field list, the
 * released shape and the current one, and handed to the real mapping. An unknown field is added to
 * each, because that is what a later release's additive change looks like to this release.
 */
@Tag("architecture")
@DisplayName("risk's signal mapping parses every catalogued shape of what it consumes (P15-TSK-027)")
class RiskSignalsReadTheCatalogueTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final UUID SAME = UUID.fromString("0199b3c1-0000-7000-8000-000000000001");

    @Test
    @DisplayName("each mapped event parses at its released shape and at its current shape, with a field it has never seen")
    void everyShapeParses() {
        EventCatalogue released = EventCatalogue.released();
        EventCatalogue current = EventCatalogue.fromProductionCode();
        int checked = 0;

        for (RiskSignalIntake.Source source : RiskSignalIntake.Source.values()) {
            if (source.isHint()) {
                continue;
            }
            SignalKind kind = source.kind().orElseThrow();
            for (EventCatalogue catalogue : List.of(released, current)) {
                var versions = catalogue.events().get(source.eventType());
                assertThat(versions).as("%s is catalogued", source.eventType()).isNotNull();
                versions.forEach(
                        (number, version) -> {
                            assertThat(number)
                                    .as("%s: a version the intake does not accept would stall its partition", source.eventType())
                                    .isLessThanOrEqualTo(RiskSignalIntake.Source.NEWEST_VERSION);
                            assertThat(parsesInSomeForm(kind, source.eventType(), number, version.fields()))
                                    .as("%s v%s", source.eventType(), number)
                                    .isTrue();
                        });
                checked++;
            }
        }

        assertThat(checked).as("both catalogues, every source that reads a payload").isGreaterThanOrEqualTo(26);
    }

    /** An attributed event is read for one specific value of its one enumerated field, so each is tried. */
    private static boolean parsesInSomeForm(
            SignalKind kind, String eventType, int version, Map<String, EventCatalogue.Field> fields) {
        for (Map<String, String> payload : payloads(fields)) {
            payload.put("addedByALaterRelease", "IGNORED");
            ReceivedEvent event = received(eventType, version, payload);
            try {
                if (SignalMappings.ATTRIBUTED.contains(kind)) {
                    assertThat(SignalMappings.intentOf(kind, event)).isNotNull();
                } else {
                    assertThat(SignalMappings.map(kind, event, IDS).kind()).isEqualTo(kind);
                }
                return true;
            } catch (IllegalArgumentException refusedThisValue) {
                // the next constant of the enumerated field; refused by all of them fails the test
            }
        }
        return false;
    }

    private static List<Map<String, String>> payloads(Map<String, EventCatalogue.Field> fields) {
        List<String> constants = List.of("");
        String enumerated = null;
        for (var field : fields.entrySet()) {
            if (field.getValue().kind().startsWith("enum(")) {
                enumerated = field.getKey();
                constants = constantsOf(field.getValue().kind());
                break;
            }
        }
        List<Map<String, String>> payloads = new ArrayList<>();
        for (String constant : constants) {
            Map<String, String> payload = new LinkedHashMap<>();
            fields.forEach((name, field) -> payload.put(name.replace("#", "1"), valueOf(field)));
            if (enumerated != null) {
                payload.put(enumerated, constant);
            }
            payloads.add(payload);
        }
        return payloads;
    }

    private static List<String> constantsOf(String kind) {
        String className = kind.substring("enum(".length(), kind.length() - 1);
        try {
            List<String> names = new ArrayList<>();
            for (Object constant : Class.forName(className).getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return names;
        } catch (ClassNotFoundException gone) {
            throw new AssertionError(kind + " names a class that no longer exists", gone);
        }
    }

    /** A value of the field's catalogued kind, one that the bounds of an attribute of that kind admit. */
    private static String valueOf(EventCatalogue.Field field) {
        String kind = field.kind();
        if (kind.startsWith("enum(")) {
            return constantsOf(kind).get(0);
        }
        if (kind.startsWith("literal(")) {
            return kind.substring("literal(".length(), kind.length() - 1).split("\\|", -1)[0];
        }
        return switch (kind) {
            case "uuid" -> SAME.toString();
            case "integer" -> "5";
            case "boolean" -> "true";
            case "date" -> "2026-11-08";
            case "currency" -> "EUR";
            // A code and a country code share this spelling; a free reference is the producer's own.
            default -> "DE";
        };
    }

    private static ReceivedEvent received(String eventType, int version, Map<String, String> payload) {
        StringBuilder json = new StringBuilder("{");
        payload.forEach(
                (name, value) -> {
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append('"').append(name).append("\":\"").append(value).append('"');
                });
        return new ReceivedEvent(
                EventId.next(IDS),
                eventType,
                version,
                1,
                SAME,
                "Probe",
                CLOCK.instant(),
                eventType.substring(0, eventType.indexOf('.')),
                CorrelationId.of(SAME.toString()),
                CausationId.of(SAME.toString()),
                "application/json",
                json.append('}').toString().getBytes(StandardCharsets.UTF_8));
    }
}
