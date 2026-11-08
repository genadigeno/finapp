package com.finapp.app.architecture.eventprobe;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;

/**
 * The shapes a producer takes, for {@code EventFlowsTest} (`P15-TSK-027`): compiled, read as bytes,
 * never run. Every reference the envelope would refuse is {@code null}.
 */
public final class EventFlowFixtures {

    static final String PRODUCER = "probe";

    /** A status to write by name. */
    public enum Status {
        OPEN
    }

    private final OutboxWriter<Connection> outbox = null;

    private static EventEnvelope envelope(String type, int version) {
        return new EventEnvelope(null, type, version, 1, null, "Probe", null, PRODUCER, null, null);
    }

    public void direct(UUID id, long amount, Status status, String reference) {
        outbox.write(
                null,
                new EventEnvelope(null, "probe.Direct", 3, 1, null, "Probe", null, PRODUCER, null, null),
                EventPayload.of()
                        .with("id", id.toString())
                        .with("amountMinor", Long.toString(amount))
                        .with("status", status.name())
                        .with("reference", reference)
                        .with("kind", "FIXED")
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    public void callerA(UUID id) {
        publish("probe.HelperA", EventPayload.of().with("fromA", id.toString()));
    }

    public void callerB(UUID id) {
        publish("probe.HelperB", EventPayload.of().with("fromB", id.toString()));
    }

    private void publish(String type, EventPayload payload) {
        outbox.write(null, envelope(type, 1), payload.with("always", "YES").toBytes(), EventPayload.MEDIA_TYPE);
    }

    public void ternaryType(boolean flag) {
        outbox.write(null, envelope(flag ? "probe.Left" : "probe.Right", 1), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    public void conditional(String maybe) {
        EventPayload payload = EventPayload.of().with("first", "ONE");
        if (maybe != null) {
            payload = payload.with("maybe", "THERE");
        }
        outbox.write(null, envelope("probe.Conditional", 1), payload.with("last", "TWO").toBytes(), "x");
    }

    public void numbered(List<String> reasons) {
        EventPayload payload = EventPayload.of().with("count", Integer.toString(reasons.size()));
        for (int i = 0; i < reasons.size(); i++) {
            payload = payload.with("reason" + (i + 1), reasons.get(i));
        }
        outbox.write(null, envelope("probe.Numbered", 1), payload.toBytes(), "x");
    }

    public void helpers(long minor) {
        EventPayload payload = base("ONE");
        amounts(payload, minor);
        outbox.write(null, envelope("probe.Helpers", 1), payload.toBytes(), "x");
    }

    private static EventPayload base(String value) {
        return EventPayload.of().with("base", value);
    }

    private static EventPayload amounts(EventPayload payload, long minor) {
        return payload.with("minor", Long.toString(minor));
    }

    public void lambdaCaller() {
        inLambda("probe.Lambda");
    }

    private void inLambda(String type) {
        Runnable write = () -> outbox.write(null, envelope(type, 1), EventPayload.of().with("a", "B").toBytes(), "x");
        write.run();
    }

    public void floating(double rate, float other) {
        outbox.write(
                null,
                envelope("probe.Floating", 1),
                EventPayload.of().with("rate", Double.toString(rate)).with("other", String.valueOf(other)).toBytes(),
                "x");
    }

    public void computedType(String computed) {
        outbox.write(null, envelope(computed.trim(), 1), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    public void noPayloadBuilder() {
        outbox.write(null, envelope("probe.NoBuilder", 1), new byte[0], "x");
    }

    public void uncalledParameter(String nobodyNames) {
        outbox.write(null, envelope(nobodyNames, 1), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    public void handedToAnInterface(Enrichment enrichment) {
        EventPayload payload = EventPayload.of().with("a", "B");
        enrichment.enrich(payload);
        outbox.write(null, envelope("probe.Handed", 1), payload.toBytes(), "x");
    }

    /** A port a payload could be handed to, whose implementation this reader cannot pick. */
    public interface Enrichment {
        void enrich(EventPayload payload);
    }

    public void reboundInALoop(List<String> values) {
        String type = "probe.First";
        for (String value : values) {
            outbox.write(null, envelope(type, 1), EventPayload.of().with("a", "B").toBytes(), "x");
            type = "probe.Second";
        }
    }

    public void concatenatedDecimal(double rate) {
        outbox.write(null, envelope("probe.Concatenated", 1), EventPayload.of().with("rate", "r" + rate).toBytes(), "x");
    }

    public void ownVersion() {
        outbox.write(null, envelope("probe.Own", ProbeEventVersions.of("probe.Own")), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    public void sharedVersion() {
        outbox.write(null, envelope("probe.Sharing", ProbeEventVersions.of("probe.Own")), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    public void bareVersion() {
        outbox.write(null, envelope("probe.Bare", 1), EventPayload.of().with("a", "B").toBytes(), "x");
    }

    /** The per-type declaration's shape: {@code <anything>EventVersions.of(eventType)}. */
    public static final class ProbeEventVersions {
        private ProbeEventVersions() {}

        public static int of(String eventType) {
            return eventType.length();
        }
    }
}
