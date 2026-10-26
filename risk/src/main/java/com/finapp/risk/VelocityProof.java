package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The velocity windows proven against what they count (`P13-TSK-006`; {@code PHASE_13_PLAN.md} sections 12.4, 15;
 * {@code RISK_LIFECYCLES.md} section 4.3; {@code INV-RSK-03}; F8): every live window of a provable dimension
 * recomputed from the {@link VelocityEvidence} and compared - <strong>a difference is reported, never corrected</strong>.
 * This class holds no write: the store's reads and the evidence's are all it calls, so a proof can never "repair" a
 * window into agreement and hide the defect that broke it (a lost increment, a double count, a window keyed on the
 * wrong clock). Its {@link Result} is the operational fact `-024` meters as {@code finapp.risk.velocity.proof{verdict}}
 * (the {@code VelocityDiverged} alert's input - no alert row, {@code RISK_LIFECYCLES.md} section 8) and `-023` serves in
 * the velocity report.
 *
 * <h2>The judgement, per live window</h2>
 *
 * <ul>
 *   <li><strong>Unprovable</strong> - its dimension is not one the evidence names ({@link VelocityEvidence#provable}),
 *       or the caller set it aside as one the evidence cannot speak for: counted, never judged.
 *   <li><strong>Pending</strong> - it was counted after the evidence is complete through, less the skew: the movement's
 *       evidence may not have been recorded yet, so it is not judged this run.
 *   <li>Otherwise <strong>judged</strong>: the evidence for its subject, currency and scale inside it gives a range -
 *       the movements certainly inside it, and those within {@link #SKEW} of either edge, which an instance clock
 *       stamping the evidence may have put on the other side of the database's cut. The window holds when its count
 *       and its sum each lie inside that range; otherwise it diverged.
 *   <li>And every movement the evidence places certainly inside a live window of a provable dimension that has
 *       <strong>no row</strong> in its currency is a divergence too - a count that was lost, not merely wrong.
 * </ul>
 *
 * <h2>Made whole (`P13-TSK-023`; {@code PHASE_13_PLAN.md} section 12.4; {@code DESIGN-023} D4)</h2>
 *
 * <p>{@link #prove} reads ONE snapshot and judges two links. <strong>The record</strong> - the windows against the
 * evidence of record ({@link AssessmentVelocityEvidence}: the counted assessments, every dimension, the same transaction
 * and database clock as the count). <strong>The journal</strong> - the windows against `-019`'s monitored transactions
 * ({@link JdbcVelocityEvidence}: the journal's transfers, the two account dimensions), judged only for the windows
 * <em>every counted movement of which is a transfer</em> by the record's own account: a window that also counts a
 * withdrawal, a payment or a cross-border movement - which post at their outcome, or never - cannot equal the journal's
 * transfers by construction, and is unprovable there. A window that holds against the record and diverges against the
 * journal is a seam that counted a transfer that never posted - named, never corrected. Each {@link Divergence} names its
 * currency and scale: a window counts in one currency, and a lost count in a second currency is its own divergence.
 */
public final class VelocityProof {

    /** The clock skew admitted at a window's edges - the ±5 s the plan names between instances (section 7.4). */
    public static final Duration SKEW = Duration.ofSeconds(5);

    /** The longest window: the evidence a run reads reaches back this far, plus the skew. */
    static final Duration LONGEST = WindowKind.D30.length();

    /** The proof's verdict - the {@code verdict} tag of {@code finapp.risk.velocity.proof}. */
    public enum Verdict {
        /** Every judged window equals what it counts. */
        HELD,
        /** At least one window does not - the alert's condition. */
        DIVERGED,
        /** Nothing was judged: no live window of a provable dimension, and no evidence placing one. */
        VACUOUS
    }

    /**
     * One window that does not equal what it counts: its recorded figures and the range the evidence admits, in minor
     * units of {@code currency} at {@code scale} - the row's money, or (a lost count) the movements'.
     */
    public record Divergence(VelocityWindowKey key, CurrencyCode currency, int scale, long recordedCount,
            long recordedSumMinor, long fromCount, long toCount, long fromSumMinor, long toSumMinor) {
        public Divergence {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(currency, "currency");
        }

        @Override
        public String toString() {
            // Figures and a key whose toString names no subject - still CONFIDENTIAL, never a log line (INV-RSK-08).
            return "Divergence[" + key + "]";
        }
    }

    /** One run's outcome. */
    public record Result(Verdict verdict, Instant asOf, Instant evidenceThrough, int judged, int pending, int unprovable,
            List<Divergence> divergences) {
        public Result {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(evidenceThrough, "evidenceThrough");
            divergences = List.copyOf(Objects.requireNonNull(divergences, "divergences"));
            if ((verdict == Verdict.DIVERGED) == divergences.isEmpty()) {
                throw new IllegalArgumentException("a proof diverges exactly when it names a divergence");
            }
        }

        @Override
        public String toString() {
            return "VelocityProof.Result[" + verdict + ", judged " + judged + ", pending " + pending + ", unprovable "
                    + unprovable + ", diverged " + divergences.size() + "]";
        }
    }

    /**
     * The proof made whole (`P13-TSK-023`): the windows against the record and against the journal, read on one snapshot.
     * {@code journal} is empty when the proof was built without the journal's cross-check.
     */
    public record Whole(Result record, Optional<Result> journal) {
        public Whole {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(journal, "journal");
        }

        /** {@code DIVERGED} when either link diverged; {@code HELD} when anything was judged; else {@code VACUOUS}. */
        public Verdict verdict() {
            List<Result> links = journal.map(found -> List.of(record, found)).orElse(List.of(record));
            if (links.stream().anyMatch(link -> link.verdict() == Verdict.DIVERGED)) {
                return Verdict.DIVERGED;
            }
            return links.stream().anyMatch(link -> link.verdict() == Verdict.HELD) ? Verdict.HELD : Verdict.VACUOUS;
        }

        /** How many divergences both links named. */
        public int diverged() {
            return record.divergences().size() + journal.map(found -> found.divergences().size()).orElse(0);
        }

        @Override
        public String toString() {
            return "VelocityProof.Whole[" + verdict() + ", record " + record
                    + journal.map(found -> ", journal " + found).orElse("") + "]";
        }
    }

    private final VelocityWindowStore windows;
    private final VelocityEvidence evidence;
    private final Optional<VelocityEvidence> journal;

    public VelocityProof(VelocityWindowStore windows, VelocityEvidence evidence) {
        this.windows = Objects.requireNonNull(windows, "windows");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.journal = Optional.empty();
    }

    /** The whole proof: {@code record} the evidence of record, {@code journal} the journal's cross-check (`-023`). */
    public VelocityProof(VelocityWindowStore windows, VelocityEvidence record, VelocityEvidence journal) {
        this.windows = Objects.requireNonNull(windows, "windows");
        this.evidence = Objects.requireNonNull(record, "record");
        this.journal = Optional.of(Objects.requireNonNull(journal, "journal"));
    }

    /**
     * One run in the caller's {@code REPEATABLE READ} transaction - one snapshot for the windows and the evidence, so a
     * movement committing mid-run is in both or neither. A risk-only read: no lock, no write. Refused in any other
     * isolation, where the two reads could see two different worlds.
     */
    public Result run(Connection snapshot) {
        requireRepeatableRead(snapshot);
        Instant asOf = windows.databaseNow(snapshot);
        List<VelocityWindow> live = windows.live(snapshot);
        VelocityEvidence.Record record = evidence.read(snapshot, asOf.minus(LONGEST).minus(SKEW));
        return judge(asOf, live, record, evidence.provable(), SKEW);
    }

    /**
     * The whole proof in the caller's {@code REPEATABLE READ} transaction (`P13-TSK-023`): the database's clock, the live
     * windows and both evidences read once, the record's link judged over every dimension it names and the journal's
     * over the windows of transfers alone. No lock, no write.
     */
    public Whole prove(Connection snapshot) {
        requireRepeatableRead(snapshot);
        Instant asOf = windows.databaseNow(snapshot);
        List<VelocityWindow> live = windows.live(snapshot);
        Instant from = asOf.minus(LONGEST).minus(SKEW);
        VelocityEvidence.Record record = evidence.read(snapshot, from);
        Result ofRecord = judge(asOf, live, record, evidence.provable(), SKEW);
        if (journal.isEmpty()) {
            return new Whole(ofRecord, Optional.empty());
        }
        VelocityEvidence.Record posted = journal.get().read(snapshot, from);
        Set<VelocityDimension> provable = journal.get().provable();
        return new Whole(ofRecord, Optional.of(judge(asOf, live, posted, provable, SKEW,
                notOnlyTransfers(record, provable))));
    }

    /**
     * The windows of {@code provable} dimensions that count a movement other than a transfer, by the record's account -
     * the journal's transfers cannot equal them. A movement whose operation the record does not name counts as other.
     */
    static Set<VelocityWindowKey> notOnlyTransfers(VelocityEvidence.Record record, Set<VelocityDimension> provable) {
        Set<VelocityWindowKey> keys = new HashSet<>();
        for (VelocityEvidence.Counted counted : record.movements()) {
            if (counted.operation().filter(operation -> operation == RiskOperation.TRANSFER).isPresent()) {
                continue;
            }
            for (VelocityDimension dimension : provable) {
                counted.movement().subject(dimension).ifPresent(subject -> {
                    for (WindowKind kind : WindowKind.values()) {
                        keys.add(new VelocityWindowKey(dimension, subject, kind, WindowStarts.start(kind, counted.at())));
                    }
                });
            }
        }
        return keys;
    }

    /** The pure judgement - {@link #run}'s, given its reads. */
    public static Result judge(Instant asOf, List<VelocityWindow> live, VelocityEvidence.Record record,
            Set<VelocityDimension> provable, Duration skew) {
        return judge(asOf, live, record, provable, skew, Set.of());
    }

    /**
     * The pure judgement with {@code unprovable} windows set aside - counted unprovable, never judged and never a lost
     * count: the evidence cannot speak for them (`P13-TSK-023`, the journal's link).
     */
    public static Result judge(Instant asOf, List<VelocityWindow> live, VelocityEvidence.Record record,
            Set<VelocityDimension> provable, Duration skew, Set<VelocityWindowKey> unprovable) {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(provable, "provable");
        Objects.requireNonNull(skew, "skew");
        Objects.requireNonNull(unprovable, "unprovable");
        Instant judgeable = record.completeThrough().minus(skew);
        Map<VelocityWindowKey, VelocityWindow> rows = new TreeMap<>();
        int judged = 0;
        int pending = 0;
        int unproven = 0;
        List<Divergence> divergences = new ArrayList<>();
        for (VelocityWindow window : live) {
            if (!window.key().windowEnd().isAfter(asOf)) {
                continue;
            }
            rows.put(window.key(), window);
            if (!provable.contains(window.key().dimension()) || unprovable.contains(window.key())) {
                unproven++;
                continue;
            }
            if (window.lastCountedAt().isAfter(judgeable)) {
                pending++;
                continue;
            }
            judged++;
            Range range = range(window, record.movements(), skew);
            if (!range.admits(window.count(), window.sum().minorUnits())) {
                divergences.add(range.divergence(window.key(), window.sum().currency(), window.sum().scale(),
                        window.count(), window.sum().minorUnits()));
            }
        }
        Map<LostKey, Range> lost = new TreeMap<>();
        for (VelocityEvidence.Counted counted : record.movements()) {
            for (VelocityDimension dimension : provable) {
                for (WindowKind kind : WindowKind.values()) {
                    counted.movement().subject(dimension).ifPresent(subject -> {
                        VelocityWindowKey key = new VelocityWindowKey(dimension, subject, kind,
                                WindowStarts.start(kind, counted.at()));
                        VelocityWindow row = rows.get(key);
                        boolean counts = row != null && row.sameMoneyAs(counted.movement().amount());
                        if (key.windowEnd().isAfter(asOf) && certainlyInside(key, counted.at(), skew) && !counts
                                && !unprovable.contains(key)) {
                            lost.computeIfAbsent(new LostKey(key, counted.movement().amount().currency().code(),
                                    counted.movement().amount().scale()), k -> new Range()).add(counted, true);
                        }
                    });
                }
            }
        }
        lost.forEach((key, range) -> divergences.add(range.divergence(key.key(), CurrencyCode.of(key.currency()),
                key.scale(), 0, 0)));
        Verdict verdict = !divergences.isEmpty() ? Verdict.DIVERGED
                : judged == 0 ? Verdict.VACUOUS : Verdict.HELD;
        return new Result(verdict, asOf, record.completeThrough(), judged, pending, unproven,
                Collections.unmodifiableList(divergences));
    }

    /** The proofs read one REPEATABLE READ snapshot - refused in any other isolation. */
    private static void requireRepeatableRead(Connection snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        try {
            if (snapshot.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ) {
                throw new IllegalStateException("the velocity proof reads one REPEATABLE READ snapshot");
            }
        } catch (SQLException failure) {
            throw new RiskStorageException(
                    "risk storage failed reading the proof's isolation: SQLSTATE " + failure.getSQLState());
        }
    }

    /** A lost count's identity: the window it belongs to, in the movement's own currency and scale. */
    private record LostKey(VelocityWindowKey key, String currency, int scale) implements Comparable<LostKey> {
        @Override
        public int compareTo(LostKey other) {
            int byKey = key.compareTo(other.key);
            if (byKey != 0) {
                return byKey;
            }
            int byCurrency = currency.compareTo(other.currency);
            return byCurrency != 0 ? byCurrency : Integer.compare(scale, other.scale);
        }
    }

    private static Range range(VelocityWindow window, List<VelocityEvidence.Counted> movements, Duration skew) {
        Range range = new Range();
        VelocityWindowKey key = window.key();
        Instant earliest = key.windowStart().minus(skew);
        Instant latest = key.windowEnd().plus(skew);
        for (VelocityEvidence.Counted counted : movements) {
            boolean sameSubject = counted.movement().subject(key.dimension()).filter(key.subjectRef()::equals).isPresent();
            if (!sameSubject || !window.sameMoneyAs(counted.movement().amount())
                    || counted.at().isBefore(earliest) || !counted.at().isBefore(latest)) {
                continue;
            }
            range.add(counted, certainlyInside(key, counted.at(), skew));
        }
        return range;
    }

    /** Inside the window and at least {@code skew} from either edge - inside on every instance's clock. */
    private static boolean certainlyInside(VelocityWindowKey key, Instant at, Duration skew) {
        return !at.isBefore(key.windowStart().plus(skew)) && at.isBefore(key.windowEnd().minus(skew));
    }

    /** The figures the evidence admits for one window: the certain movements, and the certain plus the edge ones. */
    private static final class Range {
        private long fromCount;
        private long toCount;
        private long fromSum;
        private long toSum;

        void add(VelocityEvidence.Counted counted, boolean certain) {
            long amount = counted.movement().amount().minorUnits();
            if (certain) {
                fromCount = Math.addExact(fromCount, 1);
                fromSum = Math.addExact(fromSum, amount);
            }
            toCount = Math.addExact(toCount, 1);
            toSum = Math.addExact(toSum, amount);
        }

        boolean admits(long count, long sum) {
            return count >= fromCount && count <= toCount && sum >= fromSum && sum <= toSum;
        }

        Divergence divergence(VelocityWindowKey key, CurrencyCode currency, int scale, long count, long sum) {
            return new Divergence(key, currency, scale, count, sum, fromCount, toCount, fromSum, toSum);
        }
    }
}
