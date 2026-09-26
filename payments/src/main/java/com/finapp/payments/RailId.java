package com.finapp.payments;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The stable name of a payment rail (`P7-TSK-001`, ADR-0059 §1) — a way money travels, never
 * the company operating on it ({@link PaymentProvider#providerName} is that) and never the
 * customer's instrument.
 *
 * <h2>Minted by declarations, carried as data</h2>
 *
 * <p>A rail id is born in exactly one place: the adapter that declares the rail, beside its
 * {@link RailCapabilities}. Everywhere else it is <em>data</em> — read off the attempt row the
 * dispatch stamped, or off a declaration — because the moment core code writes a rail's name it
 * has started branching on it, which is the leak {@code INV-RAIL-01} exists to prevent
 * ({@code INV-PAY-03}'s reasoning, one level up). {@code RailVocabularyIsConfinedTest} holds
 * the build to it.
 *
 * <p><strong>The charset is a design property</strong>: the value is stored in a constrained
 * column, becomes a bounded meter tag (`P7-TSK-015`) and reaches audit summaries, so
 * {@code [a-z0-9-]} keeps log forging and tag-cardinality accidents structurally unreachable —
 * the {@link ProviderIdempotencyReference} lesson at a smaller alphabet.
 */
public record RailId(String value) {

    /** Bounded because the value becomes a column under a CHECK and a meter tag. */
    public static final int MAX_LENGTH = 32;

    private static final Pattern SHAPE =
            Pattern.compile("[a-z][a-z0-9-]{0," + (MAX_LENGTH - 1) + "}");

    public RailId {
        Objects.requireNonNull(value, "a rail id must not be null");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a rail id is 1-" + MAX_LENGTH + " characters of [a-z0-9-], starting with a"
                            + " letter; the offered value is not (not echoed - it reaches logs"
                            + " and tags only once it is valid)");
        }
    }

    /** The declaring idiom, matching every other identifier on the platform. */
    public static RailId of(String value) {
        return new RailId(value);
    }
}
