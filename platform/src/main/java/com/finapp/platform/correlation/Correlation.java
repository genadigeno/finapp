package com.finapp.platform.correlation;

import java.util.Objects;
import java.util.Optional;

/**
 * The tracing context of one step in a flow: what it belongs to, and what caused it.
 *
 * @param correlationId stable for the whole flow
 * @param causationId the immediate cause, absent only at the root of a flow
 */
public record Correlation(CorrelationId correlationId, CausationId causationId) {

    public Correlation {
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        // causationId is deliberately nullable: the first step of a flow has no cause, and
        // inventing a self-referential one would make the root indistinguishable from a cycle.
    }

    /** The root of a new flow: correlated, but caused by nothing. */
    public static Correlation startingWith(CorrelationId correlationId) {
        return new Correlation(correlationId, null);
    }

    /** Absent at the root of a flow. */
    public Optional<CausationId> cause() {
        return Optional.ofNullable(causationId);
    }

    /**
     * The context a message emitted from here should carry.
     *
     * <p>The correlation identifier is inherited unchanged — it is what makes the whole flow one
     * flow. The causation identifier becomes the identifier of <em>this</em> message, because
     * from the emitted message's point of view, this one is its cause. Getting that backwards
     * (inheriting the parent's causation instead of replacing it) produces a chain where every
     * record points at the same ancestor and the tree structure is lost, which reads as working
     * because correlation still ties the records together.
     *
     * @param thisMessageId the identifier of the message being emitted from this context
     */
    public Correlation causing(CausationId thisMessageId) {
        Objects.requireNonNull(thisMessageId, "the causing message id must not be null");
        return new Correlation(correlationId, thisMessageId);
    }

    @Override
    public String toString() {
        return "Correlation[correlationId=" + correlationId + ", causationId=" + causationId + "]";
    }
}
