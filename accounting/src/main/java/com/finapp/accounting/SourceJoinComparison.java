package com.finapp.accounting;

import com.finapp.platform.sourcejoin.ExpectedLeg;
import com.finapp.platform.sourcejoin.SourceFigures;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The comparison itself (`P15-TSK-035`): one entry's lines against a record's figures, per currency, in minor units. Arithmetic only,
 * so a hermetic suite can hold every case, and pure, so a replica judges an entry the way any other would.
 *
 * <p><strong>Currency is the comparison, not a label on it.</strong> A figure is read in its own currency: lines in another currency
 * count for nothing against it, so an entry moving the right number of the wrong currency is a mismatch, and a figure's scale that
 * is not the lines' scale is a mismatch - never a conversion. An {@code AT_MOST} ceiling needs a line in its currency: zero of the
 * right currency is not "within the ceiling".
 */
public final class SourceJoinComparison {

    private SourceJoinComparison() {}

    /** Why these lines disagree with these figures, or empty when they agree. The reason names a side, a currency and purposes: never an amount. */
    public static Optional<String> mismatch(List<JournalShapes.ShapeLine> lines, SourceFigures figures) {
        if (figures.missingFigure()) {
            return Optional.of("the record states no figure for what this entry moved");
        }
        for (ExpectedLeg leg : figures.legs()) {
            Optional<String> reason = legMismatch(lines, leg);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> legMismatch(List<JournalShapes.ShapeLine> lines, ExpectedLeg leg) {
        BigInteger total = BigInteger.ZERO;
        for (JournalShapes.ShapeLine line : lines) {
            if (!onThisLeg(line, leg)) {
                continue;
            }
            if (line.scale() != leg.scale()) {
                return Optional.of(describe(leg, "a line is at a different scale in this currency"));
            }
            total = total.add(line.minorUnits());
        }
        boolean agrees = switch (leg.bound()) {
            case EXACT -> total.equals(leg.minor());
            case AT_MOST -> total.signum() > 0 && total.compareTo(leg.minor()) <= 0;
        };
        return agrees ? Optional.empty() : Optional.of(describe(leg, "the lines disagree with the record"));
    }

    private static boolean onThisLeg(JournalShapes.ShapeLine line, ExpectedLeg leg) {
        return line.currency().equals(leg.currency())
                && line.side().name().equals(leg.side().name())
                && (leg.purposes().isEmpty() || leg.purposes().contains(line.purpose()));
    }

    private static String describe(ExpectedLeg leg, String what) {
        return leg.side() + " " + leg.currency() + (leg.purposes().isEmpty() ? "" : " on " + new TreeSet<>(leg.purposes())) + ": " + what;
    }
}
