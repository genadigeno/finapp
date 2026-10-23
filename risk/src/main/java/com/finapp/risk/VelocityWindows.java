package com.finapp.risk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The durable velocity windows, as T-eval reads and counts them (`P13-TSK-006`; {@code PHASE_13_PLAN.md} sections
 * 7.1, 7.2, 12.4; {@code RISK_LIFECYCLES.md} section 4.3; ADR-0108; {@code INV-RSK-03}, {@code INV-CON-03}). Stateless:
 * every figure is a {@code velocity_window} row in the caller's unit of work, every instant the database's - nothing in
 * process memory, nothing in Redis or a cache (ADR-0107 alternative 4), so any number of instances count one window.
 *
 * <h2>The contract `-004`'s T-eval follows, inside the judged command's transaction, after its money locks</h2>
 *
 * <pre>
 *   (R1)  pin the ACTIVE policy                                     RiskPolicies.pin            (-002)
 *   (R2a) WindowCut cut = windows.cut(uow, movement)                each subject's lock, sorted; then clock_timestamp()
 *         readings = windows.read(uow, cut, keysRead(policy))     the current figures, BEFORE this movement
 *         verdict  = RiskEngineV1.evaluate(policy, inputs)        (-003; the readings sealed in the inputs)
 *   (R2)  windows.increment(uow, cut, decision, satisfied)         one upsert per window, sorted - ONLY if permitted
 *   (R3)  insert the assessment                                                                 (-004)
 * </pre>
 *
 * <p><strong>Why the read is serialised (R2a) - a correction to the plan's R2.</strong> The plan read the figures
 * unlocked and relied on the caller's money lock. That lock serialises the source account; it does not serialise the
 * party, the device, the merchant or the destination: two transfers from two accounts of one party take two account
 * locks, both read the party's window at 9 against a threshold of 10, both pass, and the window ends at 11 - the race
 * ADR-0108's alternatives refuse and {@code INV-CON-03} forbids. A row lock cannot close it, because the first
 * movements of a window find no row to lock. So {@link #cut} takes one advisory lock per (dimension, subject) the
 * movement carries, in sorted order, before it reads the clock, and holds them to the caller's commit: every
 * reader-then-writer of one subject's windows is one serialised step, row or no row.
 *
 * <p><strong>A refused movement counts for nothing</strong> (OQ-1): {@link #increment} answers no window and touches no
 * row unless the decision {@linkplain #permits permits} the movement - {@code ALLOW}, or a {@code CHALLENGE} the caller
 * reports satisfied. {@code DENY}, {@code HOLD}, {@code REVIEW} and an unsatisfied {@code CHALLENGE} increment nothing.
 * A fail-safe {@code ALLOW} is a permitting decision: the money moves, so the windows count it.
 */
public final class VelocityWindows {

    /** A window holds another currency or scale than the movement's - never summed, never compared. */
    public static final class CurrencyMismatch extends IllegalStateException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CurrencyMismatch(VelocityWindowKey key) {
            super("a velocity window holds another currency or scale than the movement's: " + key);
        }
    }

    private final VelocityWindowStore store;

    public VelocityWindows(VelocityWindowStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * (R2a) serialises every subject {@code movement} carries - one lock per (dimension, subject), sorted, held to the
     * caller's commit - and then reads the database clock on the caller's connection: the instant the movement's
     * windows are cut at. Call once per T-eval, after the caller's money locks and the policy pin.
     */
    public WindowCut cut(Connection unitOfWork, VelocityMovement movement) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(movement, "movement");
        // The keys in their own ascending order - the one global order, whatever the subjects and however their keys
        // collide (a collision is one lock, taken once; two subjects can never be locked in two orders).
        SortedSet<Integer> keys = new TreeSet<>();
        for (Map.Entry<VelocityDimension, String> subject : movement.subjects().entrySet()) {
            keys.add(lockKey(subject.getKey(), subject.getValue()));
        }
        for (int key : keys) {
            store.serialise(unitOfWork, key);
        }
        return new WindowCut(movement, store.databaseNow(unitOfWork));
    }

    /**
     * R2a's key for one subject: the first four bytes of SHA-256 over {@code dimension ':' subject} - a pure function,
     * the same on every instance and in every JVM, so every writer orders a movement's locks identically.
     */
    public static int lockKey(VelocityDimension dimension, String subjectRef) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(subjectRef, "subjectRef");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((dimension.name() + ':' + subjectRef).getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Integer.BYTES).getInt();
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is a required JCA algorithm", absent);
        }
    }

    /**
     * The current figures - before this movement - of the windows {@code keys} name, for the movement {@code cut} holds:
     * a reading per key whose dimension the movement carries ({@code PRESENT(0, 0)} for a window not yet opened: read
     * and found nothing); a key whose dimension it does not carry is left out, which the engine reads as {@code ABSENT}.
     */
    public SortedMap<VelocityKey, VelocityReading> read(Connection unitOfWork, WindowCut cut, Set<VelocityKey> keys) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(cut, "cut");
        Objects.requireNonNull(keys, "keys");
        return readings(unitOfWork, cut, keys);
    }

    /**
     * T-advise's figures: {@link #read} without the serialisation - the database clock read now, no lock taken - for
     * the step-up answer at a door before the command exists. Never authoritative; T-eval reads again under R2a.
     */
    public SortedMap<VelocityKey, VelocityReading> peek(Connection unitOfWork, VelocityMovement movement,
            Set<VelocityKey> keys) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(movement, "movement");
        Objects.requireNonNull(keys, "keys");
        return readings(unitOfWork, new WindowCut(movement, store.databaseNow(unitOfWork)), keys);
    }

    /**
     * (R2) counts the movement {@code cut} holds in every window it falls in - each dimension it carries, each window
     * kind - one upsert per window in sorted key order, <strong>only when {@code decision} permits the movement</strong>;
     * answers the windows after the movement, or nothing when it was refused. Call at most once per cut, after the
     * engine's verdict, in the same transaction.
     */
    public List<VelocityWindow> increment(Connection unitOfWork, WindowCut cut, RiskDecision decision,
            boolean challengeSatisfied) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(cut, "cut");
        Objects.requireNonNull(decision, "decision");
        if (!permits(decision, challengeSatisfied)) {
            return List.of();
        }
        cut.countOnce();
        List<VelocityWindow> counted = new ArrayList<>();
        for (VelocityWindowKey key : new TreeSet<>(cut.keys())) {
            Optional<VelocityWindow> window = store.upsert(unitOfWork, key, cut.movement().amount());
            counted.add(window.orElseThrow(() -> new CurrencyMismatch(key)));
        }
        return Collections.unmodifiableList(counted);
    }

    /** True when {@code decision} lets the movement proceed - {@code ALLOW}, or a satisfied {@code CHALLENGE} (OQ-1). */
    public static boolean permits(RiskDecision decision, boolean challengeSatisfied) {
        Objects.requireNonNull(decision, "decision");
        return decision == RiskDecision.ALLOW || (decision == RiskDecision.CHALLENGE && challengeSatisfied);
    }

    /**
     * The readings {@code policy}'s rules judge - each {@code VELOCITY_COUNT}'s and {@code VELOCITY_SUM}'s (dimension,
     * window), and {@code DORMANT_THEN_ACTIVE}'s party sum - what T-eval passes to {@link #read}.
     */
    public static Set<VelocityKey> keysRead(RiskPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Set<VelocityKey> keys = new TreeSet<>();
        for (RiskRule rule : policy.rules()) {
            switch (rule.kind()) {
                case VELOCITY_COUNT, VELOCITY_SUM -> keys.add(new VelocityKey(rule.parameters().dimension("dimension"),
                        rule.parameters().window("window")));
                case DORMANT_THEN_ACTIVE -> keys.add(new VelocityKey(VelocityDimension.PARTY,
                        rule.parameters().window("window")));
                default -> {
                    // No other kind reads a velocity window.
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    private SortedMap<VelocityKey, VelocityReading> readings(Connection unitOfWork, WindowCut cut,
            Set<VelocityKey> keys) {
        Map<VelocityKey, VelocityWindowKey> wanted = new TreeMap<>();
        for (VelocityKey key : keys) {
            cut.key(key.dimension(), key.window()).ifPresent(window -> wanted.put(key, window));
        }
        Map<VelocityWindowKey, VelocityWindow> found = new TreeMap<>();
        for (VelocityWindow window : store.find(unitOfWork, new TreeSet<>(wanted.values()))) {
            if (!window.sameMoneyAs(cut.movement().amount())) {
                throw new CurrencyMismatch(window.key());
            }
            found.put(window.key(), window);
        }
        SortedMap<VelocityKey, VelocityReading> readings = new TreeMap<>();
        for (Map.Entry<VelocityKey, VelocityWindowKey> entry : wanted.entrySet()) {
            VelocityWindow window = found.get(entry.getValue());
            readings.put(entry.getKey(), window == null ? new VelocityReading(0, 0) : window.reading());
        }
        return Collections.unmodifiableSortedMap(readings);
    }
}
