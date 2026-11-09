package com.finapp.platform.sourcejoin;

import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.CREDIT;
import static com.finapp.platform.sourcejoin.ExpectedLeg.Side.DEBIT;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a record found behind an entry states about that entry's lines: its legs, or - for a namespace whose record states no
 * figure of its own - that it exists, with the reason it cannot state one. Presence alone is a named, closed set that
 * {@code SourceJoinCoverageTest} holds equal; a record that is supposed to state a figure and has none (a {@code NULL} amount
 * on a row the entry already names) is {@link #noFigure}, read as a mismatch, never as a pass.
 */
public record SourceFigures(List<ExpectedLeg> legs, Optional<String> presenceOnly) {

    private static final String NO_FIGURE = "NO_FIGURE";

    public SourceFigures {
        legs = List.copyOf(Objects.requireNonNull(legs, "legs"));
        Objects.requireNonNull(presenceOnly, "presenceOnly");
        if (legs.isEmpty() == presenceOnly.isEmpty()) {
            throw new IllegalArgumentException("a record states legs, or says why it cannot, never both or neither");
        }
    }

    /** These legs, in order: at least one. */
    public static SourceFigures of(ExpectedLeg first, ExpectedLeg... more) {
        List<ExpectedLeg> legs = new ArrayList<>();
        legs.add(Objects.requireNonNull(first, "first"));
        legs.addAll(List.of(more));
        return new SourceFigures(legs, Optional.empty());
    }

    /** The figures of a record: at least one leg, or presence-only is a choice made elsewhere, not an empty list. */
    public static SourceFigures of(List<ExpectedLeg> legs) {
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("a record states at least one leg");
        }
        return new SourceFigures(legs, Optional.empty());
    }

    /** Debits and credits both total {@code minor} in {@code currency}: the common shape, the whole of the entry's two sides. */
    public static SourceFigures total(String currency, int scale, long minor) {
        return of(ExpectedLeg.exact(DEBIT, currency, scale, minor), ExpectedLeg.exact(CREDIT, currency, scale, minor));
    }

    /** No figure for this namespace, by design: the row's existence is the join, for {@code reason}. */
    public static SourceFigures presenceOnly(String reason) {
        if (Objects.requireNonNull(reason, "reason").isBlank()) {
            throw new IllegalArgumentException("presence-only says why");
        }
        return new SourceFigures(List.of(), Optional.of(reason));
    }

    /** A record that exists but cannot state the figure it should: the census reads it as a mismatch. */
    public static SourceFigures noFigure() {
        return new SourceFigures(List.of(), Optional.of(NO_FIGURE));
    }

    /** Whether this record is read as carrying no comparable figure when one was expected. */
    public boolean missingFigure() {
        return presenceOnly.filter(NO_FIGURE::equals).isPresent();
    }

    /** Whether the record states legs to compare. */
    public boolean compares() {
        return !legs.isEmpty();
    }
}
