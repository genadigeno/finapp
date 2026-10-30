package com.finapp.accounting;

import java.util.List;
import java.util.Objects;

/**
 * One line of a registered rule's shape (`P14-TSK-002`; ADR-0117 decision 1): the ledger purposes it may name (any one
 * of them - a rule's counterparty is a wallet <em>or</em> a payable, a rail's clearing is the rail's declared one), the
 * side it takes, and whether every entry under the rule carries it. Purposes are strings: {@code accounting} has no
 * ledger edge, and {@code app}'s {@code PostingRuleRegisterTest} holds every one to a real {@code AccountPurpose}.
 *
 * @param purposes the ledger purposes the line may name - at least one, distinct, in the order the catalogue reads them
 * @param side the line's side
 * @param required true when every entry under the rule carries the line; false when it appears only for a non-zero
 *     figure or a branch (a fee, a residual, an allocation's component)
 */
public record LineShape(List<String> purposes, Side side, boolean required) {

    public LineShape {
        purposes = List.copyOf(Objects.requireNonNull(purposes, "purposes"));
        Objects.requireNonNull(side, "side");
        if (purposes.isEmpty()) {
            throw new IllegalArgumentException("a line names at least one purpose");
        }
        if (purposes.stream().distinct().count() != purposes.size()) {
            throw new IllegalArgumentException("a line names each purpose once: " + purposes);
        }
        for (String purpose : purposes) {
            if (!purpose.matches("[A-Z][A-Z_]*[A-Z]")) {
                throw new IllegalArgumentException("a purpose is a ledger purpose's name: " + purpose);
            }
        }
    }

    /** A line every entry carries. */
    public static LineShape required(Side side, String... purposes) {
        return new LineShape(List.of(purposes), side, true);
    }

    /** A line an entry carries only for a non-zero figure or a branch. */
    public static LineShape optional(Side side, String... purposes) {
        return new LineShape(List.of(purposes), side, false);
    }

    /** A line every entry carries, naming any one of a list of purposes. */
    public static LineShape required(Side side, List<String> purposes) {
        return new LineShape(purposes, side, true);
    }

    /** An optional line naming any one of a list of purposes. */
    public static LineShape optional(Side side, List<String> purposes) {
        return new LineShape(purposes, side, false);
    }
}
