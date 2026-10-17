package com.finapp.bnpl;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code BNPL_SCHEDULE_ENGINE_V1} - pay-in-4's schedule: four interest-free instalments, the first at purchase, then
 * every 14 days (`P12-TSK-008`; ADR-0102 decision 2, {@code PHASE_12_PLAN.md} section 12.2, A4, A5, OQ-3;
 * {@code INV-BNPL-05}, {@code INV-MON-01}).
 *
 * <p><strong>A pure function.</strong> No clock, no database, no locale, no hash ordering, no floating point and no
 * state: the same terms, principal and purchase date give the same schedule, byte for byte, on every instance and every
 * JVM, years later - which is the whole of its multi-instance answer ({@code BnplScheduleEngineV1PropertiesTest}'s
 * two-JVM digest). A semantic change is version 2 beside this one, which stays for as long as an agreement pins it
 * ({@link BnplEngine}).
 *
 * <p><strong>The amounts</strong> (A4, OQ-3). {@code A = floor(P / 4)} to the minor unit - {@link #INSTALMENT_ROUNDING}
 * {@code DOWN}, the one division - is instalments 2 to 4, and instalment 1 is {@code P - 3A}: the remainder, at most
 * three minor units, is collected at purchase, so the three open instalments are equal. Principal is conserved exactly by
 * construction, and asserted by {@link BnplSchedule}'s. Deliberately NOT {@code Money.allocateEvenly}, which spreads the
 * remainder one unit at a time over the earliest parts (EUR 100.03 as 25.01, 25.01, 25.01, 25.00): pay-in-4's rule puts
 * all of it on instalment 1 (25.03, 25.00, 25.00, 25.00).
 *
 * <p><strong>The dates</strong> (A5). Instalment k falls due {@code D1 + 14 (k - 1)} calendar days, {@code D1} the
 * purchase's business date (the servicing zone's date on the database clock - the caller's, ADR-0102 decision 2): 29
 * February is an ordinary day and there is no business-day adjustment.
 *
 * <p><strong>It refuses any other shape</strong> rather than approximating ({@link BnplScheduleRefusal}): terms that
 * are not pay-in-4's own part on this version - another instalment count, interval, engine version, rate, frequency or
 * rounding. {@link BnplTerms} judges a pay-in-4 set to exactly this shape as it is built, so through a judged set the
 * refusal is met only by another product's terms; the engine checks every dimension itself all the same, because a
 * terms bound may be widened by a later build ({@link BnplTerms}' re-judgement only ever widens) while version 1 means
 * what it meant. Its constants are the terms' bounds' source ({@code BnplTerms.PAY_IN_4_INTERVAL_DAYS} and the
 * pay-in-4 rounding read them) - one statement of the shape.
 *
 * <p><strong>It does not recalculate.</strong> A refund's or a payoff's schedule version n+1 is the refund allocator's
 * cancellations recorded as a new schedule version (`P12-TSK-009`, `-021`), never a re-projection here.
 */
public final class BnplScheduleEngineV1 {

    /** The version every schedule this engine produces is named for. */
    public static final BnplEngine VERSION = BnplEngine.BNPL_SCHEDULE_ENGINE_V1;

    /** Pay-in-4's instalments, {@code n} (A4) - {@link BnplPlan#PAY_IN_4}'s count, held equal by the engine's tests. */
    public static final int INSTALMENTS = 4;

    /** The calendar days between two instalments' due dates (A5). */
    public static final int INTERVAL_DAYS = 14;

    /** The one division's rounding: {@code A = floor(P / 4)}, the remainder on instalment 1 (A4, OQ-3). */
    public static final RoundingMode INSTALMENT_ROUNDING = RoundingMode.DOWN;

    /** The last year a due date may fall in - the calendar a stored {@code date} and ISO-8601 text both hold. */
    public static final int LATEST_YEAR = 9_999;

    private static final BigDecimal DIVISOR = BigDecimal.valueOf(INSTALMENTS);

    private BnplScheduleEngineV1() {}

    // ------------------------------------------------------------------ the entry points

    /**
     * The schedule of a new pay-in-4 purchase - the agreement's version 1 (`P12-TSK-013`) and the offer's illustration
     * (`P12-TSK-011`).
     *
     * @param terms the pinned pay-in-4 terms
     * @param principal the principal {@code P} - the purchase's gross - in the terms' currency at its scale
     * @param purchaseDate {@code D1}, the purchase's business date
     */
    public static BnplScheduleOutcome project(BnplTerms terms, Money principal, LocalDate purchaseDate) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(purchaseDate, "purchaseDate");
        Optional<BnplScheduleOutcome.Refused> refused = shape(terms)
                .or(() -> principal(terms.currency(), principal))
                .or(() -> calendar(purchaseDate));
        if (refused.isPresent()) {
            return refused.get();
        }
        CurrencyCode currency = terms.currency();
        long regular = BigDecimal.valueOf(principal.minorUnits())
                .divide(DIVISOR, 0, INSTALMENT_ROUNDING).longValueExact();
        Money later = Money.ofMinorUnits(regular, currency);
        Money first = principal.minus(later.times(INSTALMENTS - 1));
        List<BnplSchedule.Instalment> instalments = new ArrayList<>(INSTALMENTS);
        for (int sequence = 1; sequence <= INSTALMENTS; sequence++) {
            instalments.add(new BnplSchedule.Instalment(sequence, dueDate(purchaseDate, sequence),
                    sequence == 1 ? first : later));
        }
        return new BnplScheduleOutcome.Projected(
                new BnplSchedule(VERSION, currency, principal, purchaseDate, instalments));
    }

    /**
     * Whether every purchase of at least {@code smallestPrincipal} schedules under {@code terms} - the terms door's
     * judgement against credit's smallest purchase ({@code BnplTermsAdministration}), any purchase date. Sound and
     * tight: the smallest instalment {@code floor(P / 4)} never falls as {@code P} rises and the engine has no upper
     * bound on {@code P}, so the smallest principal schedules exactly when every larger one does.
     */
    public static Optional<BnplScheduleOutcome.Refused> judgeSchedulable(BnplTerms terms, Money smallestPrincipal) {
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(smallestPrincipal, "smallestPrincipal");
        return shape(terms).or(() -> principal(terms.currency(), smallestPrincipal));
    }

    // ------------------------------------------------------------------ the judgements

    /** The shape version 1 computes - every dimension, each in its own words. */
    static Optional<BnplScheduleOutcome.Refused> shape(BnplTerms terms) {
        if (terms.payIn4().isEmpty()) {
            return shapeNot("the terms are not pay-in-4's - version 1 schedules pay-in-4 only");
        }
        BnplTerms.PayIn4Terms own = terms.payIn4().get();
        if (own.scheduleEngine() != VERSION) {
            return shapeNot("the terms pin another schedule engine version");
        }
        if (own.instalments() != INSTALMENTS || own.intervalDays() != INTERVAL_DAYS) {
            return shapeNot("version 1 schedules " + INSTALMENTS + " instalments " + INTERVAL_DAYS + " days apart");
        }
        if (terms.frequency() != InstalmentFrequency.BIWEEKLY) {
            return shapeNot("version 1 schedules every two weeks");
        }
        if (terms.nominalAnnualRate().signum() != 0) {
            return shapeNot("version 1 charges no interest");
        }
        if (terms.instalmentRounding() != INSTALMENT_ROUNDING) {
            return shapeNot("version 1 rounds the instalment " + INSTALMENT_ROUNDING + ", the remainder first");
        }
        return Optional.empty();
    }

    private static Optional<BnplScheduleOutcome.Refused> principal(CurrencyCode currency, Money principal) {
        if (!principal.currency().equals(currency) || principal.scale() != currency.minorUnits()) {
            return refused(BnplScheduleRefusal.INVALID_REQUEST, "the principal is in the terms' currency, at its scale");
        }
        if (!principal.isPositive()) {
            return refused(BnplScheduleRefusal.PRINCIPAL_NOT_POSITIVE, "the principal is positive");
        }
        if (principal.minorUnits() < INSTALMENTS) {
            return refused(BnplScheduleRefusal.INSTALMENT_OF_NOTHING, "a pay-in-4 purchase of fewer than " + INSTALMENTS
                    + " minor units would have an instalment of nothing");
        }
        return Optional.empty();
    }

    private static Optional<BnplScheduleOutcome.Refused> calendar(LocalDate purchaseDate) {
        if (purchaseDate.getYear() < 1 || purchaseDate.isAfter(LocalDate.of(LATEST_YEAR, 12, 31)
                .minusDays((long) INTERVAL_DAYS * (INSTALMENTS - 1)))) {
            return refused(BnplScheduleRefusal.INVALID_REQUEST, "every due date falls within the years 1 to "
                    + LATEST_YEAR);
        }
        return Optional.empty();
    }

    private static LocalDate dueDate(LocalDate purchaseDate, int sequence) {
        return purchaseDate.plusDays((long) INTERVAL_DAYS * (sequence - 1));
    }

    private static Optional<BnplScheduleOutcome.Refused> shapeNot(String detail) {
        return refused(BnplScheduleRefusal.SHAPE_NOT_IMPLEMENTED, detail);
    }

    private static Optional<BnplScheduleOutcome.Refused> refused(BnplScheduleRefusal reason, String detail) {
        return Optional.of(new BnplScheduleOutcome.Refused(reason, detail));
    }
}
