package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The monitoring readings' reader (`P13-TSK-020`; {@code PHASE_13_PLAN.md} sections 12.4, 12.7; ADR-0111 decision 3;
 * {@code DESIGN-020} D3-D7; {@code INV-MON-01}) - per party, per day and per policy, what the {@code MONITORING} kinds
 * the journal can feed judge: the windows' movements, the in/out flows ({@link RuleKind#RAPID_IN_OUT}), the amounts
 * ({@link RuleKind#STRUCTURING}), the dormancy ({@link RuleKind#DORMANT_THEN_ACTIVE}) and `-006`'s committed
 * {@code PARTY} windows ({@code VELOCITY_*} and the dormant sum - the reuse). Computed in the sweep on the caller's unit
 * of work, never cached; every figure exact ({@code long}, {@link Math#addExact}), every bound the engine's - a bound
 * exceeded is a refusal, never a cut.
 *
 * <h2>The windows are cut on the movement's value date</h2>
 *
 * <p>A tumbling bucket of the velocity windows' anchor and lengths ({@link WindowStarts}) containing the day - the day's
 * own for {@code D1}, its ISO week for {@code D7}, its thirty days for {@code D30} - over the movements whose
 * <em>value date</em> is in it: a date the journal records once and every run reads identically, so a reprocessing or a
 * late batch re-cuts the same windows (`-019` built {@code monitored_transaction_by_subject} on it for this task). A
 * date has no hour: an {@code H1} window over the journal reads {@code ABSENT} ("we did not look"), and an {@code H1}
 * velocity window is read only as of an instant (the profile's own recompute).
 *
 * <h2>What is read, per kind</h2>
 *
 * <ul>
 *   <li><strong>The amounts and the flows</strong> - the party's movements in the window in the <em>policy's currency</em>
 *       at its scale (a movement in another currency is outside every threshold: no conversion, recorded), all of them
 *       or the window refused ({@link #WINDOW_BOUND}); the flow's in and out sums exact.</li>
 *   <li><strong>The dormancy</strong> - the days without a movement before the first day of the first
 *       {@code DORMANT_THEN_ACTIVE} rule's window: since the last movement before it, or - when the party never moved
 *       money - since its earliest account opened (an account standing silent since it opened is dormant too, a party
 *       with no account placed is {@code ABSENT}).</li>
 *   <li><strong>The velocity readings</strong> - each {@code PARTY} key the policy reads ({@link VelocityWindows#keysRead})
 *       from the committed window containing the day ({@link VelocityWindows#committed}); a key of another dimension (the
 *       party evaluation singles out no account, device or merchant) or a window in another currency reads
 *       {@code ABSENT}.</li>
 *   <li><strong>The evidence</strong> - every window a fired rule may name: the amounts' and flows' windows whole, the
 *       others' first {@link #EVIDENCE_BOUND} movements.</li>
 * </ul>
 */
public final class MonitoredMovements {

    /** The most movements one window seals - the engine's {@link EvaluationInputs#MAX_AMOUNTS}. */
    public static final int WINDOW_BOUND = EvaluationInputs.MAX_AMOUNTS;

    /** The most movements an evidence-only window reads - an alert names at most this many. */
    public static final int EVIDENCE_BOUND = AlertEvidence.BOUND;

    private static final MonitoredMovements NONE = new MonitoredMovements(null, MonitoredParties.NONE, null);

    private final MonitoredMovementStore store;
    private final MonitoredParties parties;
    private final VelocityWindows windows;

    /**
     * @param store the monitored transactions' reads
     * @param parties the accounts behind a party
     * @param windows `-006`'s committed windows
     */
    public MonitoredMovements(MonitoredMovementStore store, MonitoredParties parties, VelocityWindows windows) {
        this.store = store;
        this.parties = Objects.requireNonNull(parties, "parties");
        this.windows = windows;
        if (store == null != (windows == null)) {
            throw new IllegalArgumentException("the reader reads the journal and the windows, or neither");
        }
    }

    /** A reader that reads nothing: every journal and velocity reading {@code ABSENT} - the earlier tasks' suites. */
    public static MonitoredMovements none() {
        return NONE;
    }

    /** The accounts {@code party} holds, through the port - the leg reads them once. */
    public MonitoredParties.Accounts accountsOf(Connection unitOfWork, UUID party) {
        return this == NONE ? MonitoredParties.Accounts.NONE : parties.accountsOf(unitOfWork, party);
    }

    /**
     * The readings as of the database's instant {@code at} - the profile's own recompute (`-007`'s permit leg and the
     * rule leg's last evaluation): the day {@code at} falls on, and the velocity windows containing {@code at}, the
     * hour's among them.
     */
    public Read readAt(Connection unitOfWork, UUID party, RiskPolicy policy, Instant at) {
        Objects.requireNonNull(at, "at");
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        if (this == NONE || !judgesTheJournal(policy)) {
            return Read.empty(day, at);
        }
        return read(unitOfWork, party, accountsOf(unitOfWork, party), policy, day, Optional.of(at));
    }

    /** The readings of {@code day}'s windows - the rule leg's, once per day a touched movement fell on. */
    public Read readDay(Connection unitOfWork, UUID party, MonitoredParties.Accounts accounts, RiskPolicy policy,
            LocalDate day) {
        Objects.requireNonNull(day, "day");
        if (this == NONE || !judgesTheJournal(policy)) {
            return Read.empty(day, dayStart(day));
        }
        return read(unitOfWork, party, accounts, policy, day, Optional.empty());
    }

    /** True when {@code policy} carries a kind this reader feeds. */
    static boolean judgesTheJournal(RiskPolicy policy) {
        return policy.rules().stream().anyMatch(rule -> MonitoringRules.KINDS.contains(rule.kind()));
    }

    private Read read(Connection unitOfWork, UUID party, MonitoredParties.Accounts accounts, RiskPolicy policy,
            LocalDate day, Optional<Instant> instant) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(accounts, "accounts");
        Objects.requireNonNull(policy, "policy");
        Instant at = instant.orElse(dayStart(day));
        Set<WindowKind> whole = EnumSet.noneOf(WindowKind.class);
        Set<WindowKind> evidence = EnumSet.noneOf(WindowKind.class);
        Set<WindowKind> amountWindows = EnumSet.noneOf(WindowKind.class);
        Set<WindowKind> flowWindows = EnumSet.noneOf(WindowKind.class);
        Optional<WindowKind> dormantWindow = Optional.empty();
        for (RiskRule rule : policy.rules()) {
            if (!MonitoringRules.windowed(rule.kind())) {
                continue;
            }
            WindowKind window = rule.parameters().window("window");
            if (window == WindowKind.H1) {
                continue; // A value date has no hour: the journal's H1 reading is ABSENT.
            }
            switch (rule.kind()) {
                case STRUCTURING -> {
                    whole.add(window);
                    amountWindows.add(window);
                }
                case RAPID_IN_OUT -> {
                    whole.add(window);
                    flowWindows.add(window);
                }
                case DORMANT_THEN_ACTIVE -> {
                    evidence.add(window);
                    if (dormantWindow.isEmpty()) {
                        dormantWindow = Optional.of(window);
                    }
                }
                default -> evidence.add(window);
            }
        }
        evidence.removeAll(whole);

        Map<WindowKind, List<MonitoredMovement>> read = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Flow> flows = new EnumMap<>(WindowKind.class);
        Map<WindowKind, Amounts> amounts = new EnumMap<>(WindowKind.class);
        Reading<Integer> dormantDays = Reading.absent();
        if (!accounts.ids().isEmpty()) {
            for (WindowKind window : whole) {
                List<MonitoredMovement> movements = window(unitOfWork, accounts, window, day, WINDOW_BOUND + 1);
                if (movements.size() > WINDOW_BOUND) {
                    throw new IllegalStateException("a monitoring window seals at most " + WINDOW_BOUND
                            + " movements - refused, never cut");
                }
                read.put(window, movements);
            }
            for (WindowKind window : evidence) {
                read.put(window, window(unitOfWork, accounts, window, day, EVIDENCE_BOUND));
            }
            for (WindowKind window : amountWindows) {
                amounts.put(window, amountsOf(read.get(window), policy));
            }
            for (WindowKind window : flowWindows) {
                flows.put(window, flowOf(read.get(window), policy));
            }
            if (dormantWindow.isPresent()) {
                dormantDays = Reading.of(dormantDays(unitOfWork, accounts, dormantWindow.get(), day));
            }
        }
        Map<VelocityKey, VelocityReading> velocity = new TreeMap<>();
        for (VelocityKey key : VelocityWindows.keysRead(policy)) {
            if (key.dimension() != VelocityDimension.PARTY || (instant.isEmpty() && key.window() == WindowKind.H1)) {
                continue;
            }
            windows.committed(unitOfWork, VelocityDimension.PARTY, party.toString(), key.window(), at,
                    policy.currency()).ifPresent(reading -> velocity.put(key, reading));
        }
        return new Read(day, at, MonitoringReadings.journal(dormantDays, flows, amounts), velocity, read);
    }

    private List<MonitoredMovement> window(Connection unitOfWork, MonitoredParties.Accounts accounts, WindowKind window,
            LocalDate day, int limit) {
        Instant start = WindowStarts.start(window, dayStart(day));
        LocalDate from = LocalDate.ofInstant(start, ZoneOffset.UTC);
        LocalDate until = LocalDate.ofInstant(WindowStarts.end(window, start), ZoneOffset.UTC);
        return store.movementsOf(unitOfWork, accounts.ids(), from, until, limit);
    }

    /** The amounts of {@code movements} in the policy's money - sealed whole: the "below" threshold is the policy's. */
    static Amounts amountsOf(List<MonitoredMovement> movements, RiskPolicy policy) {
        List<Long> minor = new ArrayList<>();
        for (MonitoredMovement movement : movements) {
            if (movement.inMoneyOf(policy.currency())) {
                minor.add(movement.amountMinor());
            }
        }
        return new Amounts(minor);
    }

    /** The in and out sums of {@code movements} in the policy's money - exact; past the engine's bound, refused. */
    static Flow flowOf(List<MonitoredMovement> movements, RiskPolicy policy) {
        long in = 0;
        long out = 0;
        for (MonitoredMovement movement : movements) {
            if (!movement.inMoneyOf(policy.currency())) {
                continue;
            }
            if (movement.direction() == MonitoredDirection.IN) {
                in = Math.addExact(in, movement.amountMinor());
            } else {
                out = Math.addExact(out, movement.amountMinor());
            }
        }
        return new Flow(in, out);
    }

    private int dormantDays(Connection unitOfWork, MonitoredParties.Accounts accounts, WindowKind window,
            LocalDate day) {
        LocalDate first = LocalDate.ofInstant(WindowStarts.start(window, dayStart(day)), ZoneOffset.UTC);
        Optional<LocalDate> last = store.lastMovementBefore(unitOfWork, accounts.ids(), first);
        long days;
        if (last.isPresent()) {
            // The days strictly between the last movement and the window's first day - the movement's own day moved.
            days = ChronoUnit.DAYS.between(last.get(), first) - 1;
        } else {
            // Never moved: silent since the first account opened (its opening day included).
            LocalDate opened = LocalDate.ofInstant(accounts.firstOpenedAt().orElseThrow(), ZoneOffset.UTC);
            days = ChronoUnit.DAYS.between(opened, first);
        }
        return (int) Math.min(Math.max(days, 0), EvaluationInputs.MAX_DAYS);
    }

    static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /**
     * One reading of a party's windows: the day and the instant it was cut at (the day's start, or the database's
     * instant for the profile's own recompute), the journal readings to seal, the velocity readings, and per window the
     * movements read - the evidence a fired rule names.
     */
    public static final class Read {

        private final LocalDate day;
        private final Instant at;
        private final MonitoringReadings readings;
        private final Map<VelocityKey, VelocityReading> velocity;
        private final Map<WindowKind, List<MonitoredMovement>> windows;

        Read(LocalDate day, Instant at, MonitoringReadings readings, Map<VelocityKey, VelocityReading> velocity,
                Map<WindowKind, List<MonitoredMovement>> windows) {
            this.day = Objects.requireNonNull(day, "day");
            this.at = Objects.requireNonNull(at, "at");
            this.readings = Objects.requireNonNull(readings, "readings");
            this.velocity = Collections.unmodifiableMap(new TreeMap<>(velocity));
            Map<WindowKind, List<MonitoredMovement>> copied = new EnumMap<>(WindowKind.class);
            windows.forEach((kind, movements) -> copied.put(kind, List.copyOf(movements)));
            this.windows = Collections.unmodifiableMap(copied);
        }

        /** Nothing read: every journal and velocity reading {@code ABSENT}. */
        static Read empty(LocalDate day, Instant at) {
            return new Read(day, at, MonitoringReadings.none(), Map.of(), Map.of());
        }

        public LocalDate day() {
            return day;
        }

        /** The instant the windows were cut at - an alert's window is the bucket containing it. */
        public Instant at() {
            return at;
        }

        /** The journal readings - the dormancy, the flows, the amounts - to merge into the sealed value. */
        public MonitoringReadings readings() {
            return readings;
        }

        /** The velocity readings, by key. */
        public Map<VelocityKey, VelocityReading> velocity() {
            return velocity;
        }

        /** The movements read for {@code window} - empty when it was not read. */
        public List<MonitoredMovement> window(WindowKind window) {
            return windows.getOrDefault(window, List.of());
        }

        /**
         * The sealed inputs of one evaluation of {@code party} over these readings - and, for the rule leg's
         * evaluation of a touched movement, that movement's amount when it is in the policy's money (the movement kinds
         * read it, {@link RuleKind#ROUND_AMOUNT}). Nothing else is read: the screening, the anchors and the abuse
         * readings are the profile's own evaluation's.
         */
        public EvaluationInputs inputs(UUID party, RiskPolicy policy, Optional<MonitoredMovement> movement) {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(movement, "movement");
            EvaluationInputs.Builder inputs = EvaluationInputs.builder(RiskOperation.MONITORING)
                    .subject(SignalSubject.of(SubjectKind.PARTY, party))
                    .monitoring(readings);
            velocity.forEach(inputs::velocity);
            movement.filter(moved -> moved.inMoneyOf(policy.currency())).ifPresent(moved ->
                    inputs.amount(com.finapp.sharedkernel.money.Money.ofMinorUnits(moved.amountMinor(),
                            policy.currency())));
            return inputs.build();
        }

        @Override
        public String toString() {
            // The day and the counts only: the movements are the party's.
            return "MonitoredMovements.Read[" + day + ", " + windows.size() + " windows]";
        }
    }
}
