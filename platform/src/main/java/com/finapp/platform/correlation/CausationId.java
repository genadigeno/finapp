package com.finapp.platform.correlation;

import com.finapp.sharedkernel.id.IdGenerator;
import java.util.Objects;

/**
 * The identifier of the thing that <em>directly</em> caused this one.
 *
 * <p><strong>Not the same as a correlation identifier, and the difference matters.</strong> A
 * correlation identifier is flat: every step in a flow shares one value, which tells you what
 * belongs together. A causation identifier is a link to the immediate parent, and the chain of
 * them tells you the <em>order and structure</em> of what happened — which command produced
 * which event, which event triggered which handler, which handler caused which posting.
 *
 * <p>With correlation alone, a flow that fanned out into six events and twelve handlers is a
 * bag of records sharing an identifier; you can see they are related but not what caused what.
 * With causation you can rebuild the tree. That is what makes "explain this balance" answerable
 * — {@code CLAUDE.md} requires a monetary change to be traceable as economic event → domain
 * operation → financial transaction → journal entry, and each of those arrows is a causation
 * link.
 *
 * <p>A distinct type from {@link CorrelationId} on purpose. The two are both opaque strings and
 * are trivially swappable if they share a type; swapping them produces a system where every
 * record claims to have caused itself and nothing can be ordered — a defect that no test of an
 * individual component would notice.
 *
 * <p>Validated identically to {@link CorrelationId}: a causation identifier can arrive on an
 * inbound event envelope, so it is untrusted input on the same path into logs and audit rows.
 */
public final class CausationId {

    /** The same bound as {@link CorrelationId#MAX_LENGTH}, for the same reasons. */
    public static final int MAX_LENGTH = CorrelationId.MAX_LENGTH;

    private final String value;

    private CausationId(String value) {
        this.value = value;
    }

    /**
     * Accepts an identifier supplied by an inbound request or event envelope.
     *
     * @throws NullPointerException if {@code value} is null
     * @throws IllegalArgumentException if it is blank, too long, or outside the token charset
     */
    public static CausationId of(String value) {
        return new CausationId(CorrelationTokens.validate(value, MAX_LENGTH, "causation id"));
    }

    /** Mints an identifier for a message this platform is originating. */
    public static CausationId generate(IdGenerator generator) {
        Objects.requireNonNull(generator, "generator must not be null");
        return new CausationId(generator.next().toString());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CausationId other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
