package com.finapp.platform.sourcejoin;

import java.math.BigInteger;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One figure a record states about the entry that names it: on {@code side}, on the lines whose account purpose is one of
 * {@code purposes} (empty: every line of the side), in {@code currency}, the total is {@code minor} units at {@code scale} - or,
 * with {@link Bound#AT_MOST}, no more than that. Comparison is per currency, in minor units, at the stated scale: a line at
 * another scale is a mismatch, never a conversion.
 */
public record ExpectedLeg(Side side, Set<String> purposes, String currency, int scale, BigInteger minor, Bound bound) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** The side of an entry a figure speaks to. */
    public enum Side {
        DEBIT,
        CREDIT
    }

    /** How a figure binds. */
    public enum Bound {
        /** The lines total exactly this. */
        EXACT,
        /** The lines total no more than this (a share that moves under re-attribution, whose ceiling the row states). */
        AT_MOST
    }

    public ExpectedLeg {
        Objects.requireNonNull(side, "side");
        purposes = Set.copyOf(Objects.requireNonNull(purposes, "purposes"));
        Objects.requireNonNull(currency, "currency");
        if (!CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("a currency is an ISO 4217 code");
        }
        if (scale < 0 || scale > 9) {
            throw new IllegalArgumentException("a scale is 0 to 9");
        }
        Objects.requireNonNull(minor, "minor");
        if (minor.signum() < 0) {
            throw new IllegalArgumentException("a figure is not negative: a direction is the side's business");
        }
        Objects.requireNonNull(bound, "bound");
    }

    /** {@code minor} on {@code side}, on every line of the side. */
    public static ExpectedLeg exact(Side side, String currency, int scale, long minor) {
        return new ExpectedLeg(side, Set.of(), currency, scale, BigInteger.valueOf(minor), Bound.EXACT);
    }

    /** {@code minor} on {@code side}, on the lines of these purposes only. */
    public static ExpectedLeg exactOn(Side side, Set<String> purposes, String currency, int scale, long minor) {
        return new ExpectedLeg(side, purposes, currency, scale, BigInteger.valueOf(minor), Bound.EXACT);
    }

    /** At most {@code minor} on {@code side}: a figure the row can only state a ceiling for. */
    public static ExpectedLeg atMost(Side side, String currency, int scale, long minor) {
        return new ExpectedLeg(side, Set.of(), currency, scale, BigInteger.valueOf(minor), Bound.AT_MOST);
    }
}
