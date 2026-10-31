package com.finapp.accounting;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A valuation rate set's content, judged (`P14-TSK-014`; ADR-0116 decision 2; {@code PHASE_14_PLAN.md} section 13.4;
 * {@code INV-ACC-11}, {@code INV-FX-05}): for one accounting period, one {@link ValuationRate} per valued currency -
 * {@code EUR/<currency>}, the reference's last observation <strong>strictly before {@link #asOf}</strong> (the first
 * instant of the next month, UTC: at or before the period's last instant) and no older than the {@link
 * ValuationStaleness} bound before it - each with its provenance. Immutable from its judgement; {@code accounting V005}
 * holds the same window, the EUR base and the rate's column for every writer.
 *
 * <p>{@link #judge} is the whole decision and it is all or nothing: one valued currency without an admissible
 * observation refuses the capture ({@link ValuationRateStale}) and nothing is written. It trusts nothing the
 * {@link ValuationRates} port answers - the pair, the instant, the rate and the source are each re-judged - so a port
 * that read anything but the reference's EUR-based pairs before the period's end is refused, never stored.
 */
public record ValuationRateSet(
        AccountingPeriodId period, YearMonth month, Instant asOf, ValuationStaleness staleness, List<ValuationRate> rates) {

    public ValuationRateSet {
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(month, "month must not be null");
        Objects.requireNonNull(asOf, "asOf must not be null");
        Objects.requireNonNull(staleness, "staleness must not be null");
        Objects.requireNonNull(rates, "rates must not be null");
        if (!asOf.equals(asOf(month))) {
            throw new IllegalArgumentException("a set is as of its period's end: the first instant after " + month);
        }
        if (rates.isEmpty()) {
            throw new IllegalArgumentException("a valuation rate set values at least one currency");
        }
        Set<CurrencyCode> quotes = new HashSet<>();
        for (ValuationRate rate : rates) {
            if (!quotes.add(rate.quote())) {
                throw new IllegalArgumentException("a valuation rate set values each currency once: " + rate.pair());
            }
            if (!staleness.admits(rate.observedAt(), asOf)) {
                throw new IllegalArgumentException("a valuation rate set holds only admissible observations: "
                        + rate.pair());
            }
        }
        rates = List.copyOf(rates);
    }

    /**
     * A stale or missing pair refused the capture ({@code accounting.ValuationRateStale}). Names the pairs and the
     * instants - never a rate.
     */
    public static final class ValuationRateStale extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient List<String> pairs;

        ValuationRateStale(List<String> problems, List<String> pairs) {
            super("the reference holds no admissible observation for " + String.join("; ", problems)
                    + "; nothing was captured");
            this.pairs = List.copyOf(pairs);
        }

        /** The refused pairs, {@code EUR/GBP} - codes only. */
        public List<String> pairs() {
            return pairs;
        }
    }

    /** The instant a set for {@code month} is as of: the first instant of the next month, UTC - exclusive. */
    public static Instant asOf(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * Judges what the reference holds for the period: every currency of {@code valued} needs an observation of
     * {@code EUR/<currency>}, observed strictly before the period's {@link #asOf} and within {@code staleness} of it, its
     * rate positive and {@code NUMERIC(20,10)}-exact, its source a source's code.
     *
     * @param valued the currencies the set must value - never empty, never EUR, each once (the port's contract; a breach
     *     is a defect, refused before anything is judged)
     * @param observed what the reference holds, per currency
     * @throws ValuationRateStale when any valued pair is missing, stale, or anything but the EUR-based reference pair
     *     before the end
     */
    public static ValuationRateSet judge(
            AccountingPeriodId period,
            YearMonth month,
            ValuationStaleness staleness,
            List<CurrencyCode> valued,
            List<ValuationRates.Observed> observed) {
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(month, "month must not be null");
        Objects.requireNonNull(staleness, "staleness must not be null");
        Objects.requireNonNull(valued, "valued must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        if (valued.isEmpty() || valued.contains(ValuationRates.FUNCTIONAL) || Set.copyOf(valued).size() != valued.size()) {
            throw new IllegalStateException("the valued currencies are every posted currency but EUR, each once: "
                    + valued);
        }
        Instant asOf = asOf(month);
        List<String> problems = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        List<ValuationRate> rates = new ArrayList<>();
        for (CurrencyCode currency : valued) {
            String pair = ValuationRates.FUNCTIONAL.code() + "/" + currency.code();
            Optional<ValuationRates.Observation> found = observed.stream()
                    .filter(entry -> entry.currency().equals(currency))
                    .findFirst()
                    .flatMap(ValuationRates.Observed::observation);
            Optional<String> problem = found.isEmpty()
                    ? Optional.of(pair + " (no reference observation before " + asOf + ")")
                    : problem(pair, currency, found.get(), asOf, staleness);
            if (problem.isPresent()) {
                problems.add(problem.get());
                refused.add(pair);
                continue;
            }
            ValuationRates.Observation observation = found.get();
            rates.add(new ValuationRate(observation.base(), observation.quote(), observation.rate(), observation.source(),
                    observation.snapshotId(), observation.observedAt(), observation.receivedAt()));
        }
        if (!problems.isEmpty()) {
            throw new ValuationRateStale(problems, refused);
        }
        return new ValuationRateSet(period, month, asOf, staleness, rates);
    }

    private static Optional<String> problem(
            String pair,
            CurrencyCode currency,
            ValuationRates.Observation observation,
            Instant asOf,
            ValuationStaleness staleness) {
        if (!ValuationRates.FUNCTIONAL.equals(observation.base()) || !currency.equals(observation.quote())) {
            return Optional.of(pair + " (the reference answered " + observation.base() + "/" + observation.quote()
                    + ", not the EUR-based pair)");
        }
        if (!observation.observedAt().isBefore(asOf)) {
            return Optional.of(pair + " (observed " + observation.observedAt() + ", not before " + asOf + ")");
        }
        if (!staleness.admits(observation.observedAt(), asOf)) {
            return Optional.of(pair + " (last observed " + observation.observedAt() + ", more than " + staleness.hours()
                    + " hours before " + asOf + ")");
        }
        if (!ValuationRate.fits(observation.rate())) {
            return Optional.of(pair + " (the observation's rate is not a positive NUMERIC(20,10))");
        }
        if (!ValuationRate.SOURCE.matcher(observation.source()).matches()) {
            return Optional.of(pair + " (the observation names no reference source)");
        }
        return Optional.empty();
    }
}
