package com.finapp.accounting;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A valuation rate set's content, judged (`P14-TSK-014`; ADR-0116 decision 2; {@code PHASE_14_PLAN.md} section 13.4;
 * {@code INV-ACC-11}): for one accounting period, one {@link ValuationRate} per valued currency -
 * {@code EUR/<currency>}, the reference's last observation <strong>strictly before {@link #asOf}</strong> (the first
 * instant of the next month, UTC: at or before the period's last instant) and no older than the {@link
 * ValuationStaleness} bound before it - each with its provenance. Immutable from its judgement; {@code accounting V005}
 * holds the same window, the EUR base and the rate's column for every writer.
 *
 * <p>{@link #judge} is the whole decision and it is all or nothing: one valued currency without an admissible
 * observation refuses the capture ({@link ValuationRateStale}) and nothing is written. It trusts nothing the
 * {@link ValuationRates} port answers - the pair, the instant, the rate and the source are each re-judged - so a port
 * that read anything but the reference's EUR-based pairs before the period's end is refused, never stored.
 *
 * <p><strong>A reference gap's way on</strong> (`P14-TSK-026`, ADR-0119 decision 2, superseding ADR-0116 decision 2 for
 * this case): {@link #judgeAcrossGap} admits, for each pair a person names with its snapshot id, the reference's last
 * observation before the end <em>although</em> it is older than the bound - flagged {@link ValuationRate#beyondBound()} and
 * counted in {@link #referenceGaps()} - provided it is beyond the bound (a pair with an admissible observation is no gap),
 * it is the named snapshot (so no person picks an older observation they prefer), and everything else about it holds as
 * {@link #judge} holds it. Every unnamed pair is judged by the bound as ever; a pair the reference never observed before the
 * end stays refused - there is no real word to admit, and a typed rate is not one.
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
            boolean admitted = rate.beyondBound()
                    ? beyondTheBound(rate.observedAt(), asOf, staleness)
                    : staleness.admits(rate.observedAt(), asOf);
            if (!admitted) {
                throw new IllegalArgumentException("a valuation rate set holds only admissible observations, or ones a"
                        + " reference-gap exception flagged beyond the bound: " + rate.pair());
            }
        }
        rates = List.copyOf(rates);
    }

    /** How many of its rates a reference-gap exception admitted beyond the bound - zero for an ordinary capture. */
    public int referenceGaps() {
        return (int) rates.stream().filter(ValuationRate::beyondBound).count();
    }

    /** A pair a person names for a reference-gap exception, with the snapshot id of the observation they mean. */
    public record ReferenceGap(CurrencyCode currency, UUID snapshotId) {

        public ReferenceGap {
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        }

        /** The pair, {@code EUR/JPY}. */
        public String pair() {
            return ValuationRates.FUNCTIONAL.code() + "/" + currency.code();
        }
    }

    /**
     * A stale or missing pair refused the capture ({@code accounting.ValuationRateStale}). Names the pairs, the instants and,
     * for a pair the reference did observe before the end, that observation's snapshot id - what a reference-gap exception
     * names (`P14-TSK-026`). Never a rate.
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

    /**
     * A reference-gap exception named a pair that is not a gap, or an observation that is not the reference's last before
     * the end - at the capture, or again at the approval (`P14-TSK-026`; {@code accounting.ReferenceGapRefused}, a
     * {@code 409}). Names the pairs, the instants and the snapshot ids - never a rate. Nothing was written.
     */
    public static final class ReferenceGapRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient List<String> pairs;

        ReferenceGapRefused(List<String> problems, List<String> pairs) {
            super("a reference-gap exception admits only the reference's last observation before the end, older than the"
                    + " bound and named by its snapshot: " + String.join("; ", problems) + "; nothing was written");
            this.pairs = List.copyOf(pairs);
        }

        /** The refused pairs, {@code EUR/JPY} - codes only. */
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
        return judgeAcrossGap(period, month, staleness, valued, observed, List.of());
    }

    /**
     * {@link #judge}, with each pair of {@code gaps} admitted at the reference's last observation before the end although
     * it is older than the bound (`P14-TSK-026`, ADR-0119 decision 2): provided that observation is beyond the bound, is
     * the snapshot named, and is otherwise what {@link #judge} admits. Every unnamed pair is judged by the bound.
     *
     * @param gaps the pairs excepted, each once and each a valued currency - empty for an ordinary capture
     * @throws IllegalArgumentException a gap names a currency the set does not value, or one twice
     * @throws ReferenceGapRefused a named pair has an admissible observation, none before the end at all, or a last
     *     observation that is not the snapshot named, or is otherwise not admissible
     * @throws ValuationRateStale an unnamed pair is missing or stale (judged after every named one held)
     */
    public static ValuationRateSet judgeAcrossGap(
            AccountingPeriodId period,
            YearMonth month,
            ValuationStaleness staleness,
            List<CurrencyCode> valued,
            List<ValuationRates.Observed> observed,
            List<ReferenceGap> gaps) {
        Objects.requireNonNull(period, "period must not be null");
        Objects.requireNonNull(month, "month must not be null");
        Objects.requireNonNull(staleness, "staleness must not be null");
        Objects.requireNonNull(valued, "valued must not be null");
        Objects.requireNonNull(observed, "observed must not be null");
        Objects.requireNonNull(gaps, "gaps must not be null");
        if (valued.isEmpty() || valued.contains(ValuationRates.FUNCTIONAL) || Set.copyOf(valued).size() != valued.size()) {
            throw new IllegalStateException("the valued currencies are every posted currency but EUR, each once: "
                    + valued);
        }
        Map<CurrencyCode, UUID> named = new TreeMap<>(java.util.Comparator.comparing(CurrencyCode::code));
        for (ReferenceGap gap : gaps) {
            if (!valued.contains(gap.currency())) {
                throw new IllegalArgumentException("a reference-gap exception names a pair the set values, not " + gap.pair());
            }
            if (named.put(gap.currency(), gap.snapshotId()) != null) {
                throw new IllegalArgumentException("a reference-gap exception names each pair once: " + gap.pair());
            }
        }
        Instant asOf = asOf(month);
        List<String> staleProblems = new ArrayList<>();
        List<String> stalePairs = new ArrayList<>();
        List<String> gapProblems = new ArrayList<>();
        List<String> gapPairs = new ArrayList<>();
        List<ValuationRate> rates = new ArrayList<>();
        for (CurrencyCode currency : valued) {
            String pair = ValuationRates.FUNCTIONAL.code() + "/" + currency.code();
            Optional<ValuationRates.Observation> found = observed.stream()
                    .filter(entry -> entry.currency().equals(currency))
                    .findFirst()
                    .flatMap(ValuationRates.Observed::observation);
            boolean excepted = named.containsKey(currency);
            Optional<String> problem = found.isEmpty()
                    ? Optional.of(pair + " (no reference observation before " + asOf + ")")
                    : excepted ? gapProblem(pair, currency, found.get(), named.get(currency), asOf, staleness)
                            : problem(pair, currency, found.get(), asOf, staleness);
            if (problem.isPresent()) {
                (excepted ? gapProblems : staleProblems).add(problem.get());
                (excepted ? gapPairs : stalePairs).add(pair);
                continue;
            }
            ValuationRates.Observation observation = found.get();
            rates.add(new ValuationRate(observation.base(), observation.quote(), observation.rate(), observation.source(),
                    observation.snapshotId(), observation.observedAt(), observation.receivedAt(), excepted));
        }
        if (!gapProblems.isEmpty()) {
            throw new ReferenceGapRefused(gapProblems, gapPairs);
        }
        if (!staleProblems.isEmpty()) {
            throw new ValuationRateStale(staleProblems, stalePairs);
        }
        return new ValuationRateSet(period, month, asOf, staleness, rates);
    }

    /**
     * The approval's re-read (`P14-TSK-026`): every rate this set flags beyond the bound must still be the reference's last
     * observation of its pair before the end, in {@code observed} - a newer one (inside the bound, or merely newer) means
     * the gap is not what the capturer named.
     *
     * @throws ReferenceGapRefused naming each pair whose last observation moved
     */
    public static void requireStillTheLast(List<ValuationRate> rates, List<ValuationRates.Observed> observed) {
        List<String> problems = new ArrayList<>();
        List<String> pairs = new ArrayList<>();
        for (ValuationRate rate : rates) {
            if (!rate.beyondBound()) {
                continue;
            }
            Optional<UUID> last = observed.stream().filter(entry -> entry.currency().equals(rate.quote())).findFirst()
                    .flatMap(ValuationRates.Observed::observation).map(ValuationRates.Observation::snapshotId);
            if (!last.equals(Optional.of(rate.snapshotId()))) {
                problems.add(rate.pair() + " (the reference's last observation before the end is now "
                        + last.map(UUID::toString).orElse("none") + ", not snapshot " + rate.snapshotId() + ")");
                pairs.add(rate.pair());
            }
        }
        if (!problems.isEmpty()) {
            throw new ReferenceGapRefused(problems, pairs);
        }
    }

    /** True when {@code observedAt} is strictly before {@code asOf} and older than the bound - a gap's observation. */
    static boolean beyondTheBound(Instant observedAt, Instant asOf, ValuationStaleness staleness) {
        return observedAt.isBefore(asOf) && observedAt.isBefore(staleness.oldestAdmitted(asOf));
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
            return Optional.of(pair + " (last observed " + observation.observedAt() + " as snapshot "
                    + observation.snapshotId() + ", more than " + staleness.hours() + " hours before " + asOf + ")");
        }
        return shape(pair, observation);
    }

    /** A named pair's judgement: the bound inverted, the snapshot the one named, the rest as {@link #problem}. */
    private static Optional<String> gapProblem(
            String pair,
            CurrencyCode currency,
            ValuationRates.Observation observation,
            UUID named,
            Instant asOf,
            ValuationStaleness staleness) {
        if (!ValuationRates.FUNCTIONAL.equals(observation.base()) || !currency.equals(observation.quote())) {
            return Optional.of(pair + " (the reference answered " + observation.base() + "/" + observation.quote()
                    + ", not the EUR-based pair)");
        }
        if (!observation.observedAt().isBefore(asOf)) {
            return Optional.of(pair + " (observed " + observation.observedAt() + ", not before " + asOf + ")");
        }
        if (staleness.admits(observation.observedAt(), asOf)) {
            return Optional.of(pair + " (observed " + observation.observedAt() + " as snapshot " + observation.snapshotId()
                    + ", inside the " + staleness.hours() + "-hour bound: no gap - capture without the exception)");
        }
        if (!observation.snapshotId().equals(named)) {
            return Optional.of(pair + " (the reference's last observation before the end is snapshot "
                    + observation.snapshotId() + ", not " + named + ")");
        }
        return shape(pair, observation);
    }

    private static Optional<String> shape(String pair, ValuationRates.Observation observation) {
        if (!ValuationRate.fits(observation.rate())) {
            return Optional.of(pair + " (the observation's rate is not a positive NUMERIC(20,10))");
        }
        if (!ValuationRate.SOURCE.matcher(observation.source()).matches()) {
            return Optional.of(pair + " (the observation names no reference source)");
        }
        return Optional.empty();
    }
}
