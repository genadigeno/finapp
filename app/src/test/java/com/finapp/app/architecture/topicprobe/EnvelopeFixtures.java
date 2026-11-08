package com.finapp.app.architecture.topicprobe;

import com.finapp.sharedkernel.event.EventEnvelope;
import java.util.function.Supplier;

/**
 * Envelope construction shapes for {@code EnvelopeProducersTest} (`P15-TSK-026`): compiled, read
 * as bytes, never run - every reference argument is {@code null}, which the envelope would refuse.
 *
 * <p>Each method is one shape the scanner must read correctly: a constant producer, a computed
 * one, one that arrives by two branches, wide (long and double) arguments computed around the
 * producer, and a construction inside a lambda.
 */
public final class EnvelopeFixtures {

    static final String PRODUCER = "probe";

    private EnvelopeFixtures() {}

    static EventEnvelope constant() {
        return new EventEnvelope(
                null, "probe.Happened", 1, 1, null, "Probe", null, PRODUCER, null, null);
    }

    static EventEnvelope computed(String producer) {
        return new EventEnvelope(
                null, "probe.Happened", 1, 1, null, "Probe", null, producer, null, null);
    }

    static EventEnvelope branched(boolean flag) {
        return new EventEnvelope(
                null, "probe.Happened", 1, 1, null, "Probe", null, flag ? "left" : "right", null, null);
    }

    static EventEnvelope wideArguments(long version, double weight) {
        return new EventEnvelope(
                null,
                "probe." + version,
                (int) Math.max(1L, version),
                (int) (weight * 2.0d),
                null,
                "Probe",
                null,
                PRODUCER,
                null,
                null);
    }

    static Supplier<EventEnvelope> inALambda() {
        return () ->
                new EventEnvelope(
                        null, "probe.Lambda", 1, 1, null, "Probe", null, "lambda-probe", null, null);
    }
}
