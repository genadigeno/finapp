package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.risk.testing.RiskPolicyFixtures;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-eval's velocity steps over a recording fake store (`P13-TSK-006`; {@code PHASE_13_PLAN.md} sections 7.1, 7.2,
 * 12.4; {@code INV-RSK-03}, {@code INV-CON-03}): the cut is the database's instant read after every subject's lock;
 * the readings are the figures before the movement; a refused movement touches no row; a permitted one counts once in
 * every window, in sorted key order. The concurrency itself - ten instances, one subject - is the owed
 * {@code VelocityWindowDatabaseTest}'s.
 */
@DisplayName("velocity windows: read under the subject's lock, cut by the database clock, counted only when permitted"
        + " (P13-TSK-006)")
class VelocityWindowsTest {

    /** A database instant far from the build machine's clock, a microsecond before a day's end. */
    private static final Instant DATABASE = Instant.parse("2031-03-09T23:59:59.999999Z");

    private static final Connection UOW = StandInConnections.unused();
    private static final UUID PARTY = UUID.fromString("018f0000-0000-7000-8000-000000000001");
    private static final UUID SOURCE = UUID.fromString("018f0000-0000-7000-8000-000000000002");
    private static final UUID DESTINATION = UUID.fromString("018f0000-0000-7000-8000-000000000003");
    private static final DeviceHash DEVICE = DeviceHash.of("Mozilla/5.0 (a test device)");

    private final FakeVelocityWindowStore store = new FakeVelocityWindowStore(DATABASE);
    private final VelocityWindows windows = new VelocityWindows(store);

    @Test
    @DisplayName("the cut is the instant the database answered on the caller's connection - never the JVM's - and every"
            + " window start is computed from it")
    void theCutIsTheDatabaseClock() {
        WindowCut cut = windows.cut(UOW, transfer(eur(100_00)));
        assertThat(cut.at()).isEqualTo(DATABASE);
        assertThat(cut.keys()).allSatisfy(key ->
                assertThat(key.windowStart()).isEqualTo(WindowStarts.start(key.windowKind(), DATABASE)));
        assertThat(cut.key(VelocityDimension.PARTY, WindowKind.D1).orElseThrow().windowStart())
                .isEqualTo(Instant.parse("2031-03-09T00:00:00Z"));

        store.now = Instant.parse("2031-03-10T00:00:00Z");
        WindowCut next = windows.cut(UOW, transfer(eur(100_00)));
        assertThat(next.key(VelocityDimension.PARTY, WindowKind.D1).orElseThrow().windowStart())
                .as("one microsecond later on the database's clock, the next day's window")
                .isEqualTo(Instant.parse("2031-03-10T00:00:00Z"));
    }

    @Test
    @DisplayName("R2a: every subject the movement carries is serialised - each its own key, taken in the keys' ascending"
            + " order - before the clock is read and before any figure is, so a reader-then-writer of one subject is one"
            + " step on every instance")
    void theSubjectsAreSerialisedBeforeTheClockAndTheRead() {
        VelocityMovement movement = VelocityMovement.of(eur(50_00)).merchant(DESTINATION).device(DEVICE)
                .sourceAccount(SOURCE).party(PARTY).build();
        WindowCut cut = windows.cut(UOW, movement);
        windows.read(UOW, cut, Set.of(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1)));
        assertThat(store.calls).containsExactly("serialise", "serialise", "serialise", "serialise", "now", "find 1");
        assertThat(store.serialised).isSorted().containsExactlyInAnyOrder(
                VelocityWindows.lockKey(VelocityDimension.PARTY, PARTY.toString()),
                VelocityWindows.lockKey(VelocityDimension.SOURCE_ACCOUNT, SOURCE.toString()),
                VelocityWindows.lockKey(VelocityDimension.DEVICE, DEVICE.value()),
                VelocityWindows.lockKey(VelocityDimension.MERCHANT, DESTINATION.toString()));
    }

    @Test
    @DisplayName("a subject's lock key is a pure function of its role and reference - the same on every instance, and"
            + " one subject in two roles is two keys")
    void theLockKeyIsAPureFunction() {
        int party = VelocityWindows.lockKey(VelocityDimension.PARTY, PARTY.toString());
        assertThat(VelocityWindows.lockKey(VelocityDimension.PARTY, PARTY.toString())).isEqualTo(party);
        assertThat(VelocityWindows.lockKey(VelocityDimension.SOURCE_ACCOUNT, PARTY.toString())).isNotEqualTo(party);
        assertThat(VelocityWindows.lockKey(VelocityDimension.SOURCE_ACCOUNT, SOURCE.toString()))
                .isNotEqualTo(VelocityWindows.lockKey(VelocityDimension.DESTINATION, SOURCE.toString()));
    }

    @Test
    @DisplayName("the readings are the figures BEFORE the movement: an opened window its row, an unopened one PRESENT(0, 0),"
            + " a dimension the movement does not carry no reading at all (ABSENT to the engine)")
    void theReadingsAreTheFiguresBeforeTheMovement() {
        WindowCut cut = windows.cut(UOW, transfer(eur(100_00)));
        VelocityWindowKey partyDay = cut.key(VelocityDimension.PARTY, WindowKind.D1).orElseThrow();
        store.plant(new VelocityWindow(partyDay, 9, eur(4_900_00), DATABASE));

        Map<VelocityKey, VelocityReading> readings = windows.read(UOW, cut, Set.of(
                new VelocityKey(VelocityDimension.PARTY, WindowKind.D1),
                new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.H1),
                new VelocityKey(VelocityDimension.MERCHANT, WindowKind.D1)));
        assertThat(readings).containsExactly(
                Map.entry(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1), new VelocityReading(9, 4_900_00)),
                Map.entry(new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.H1), new VelocityReading(0, 0)));
        assertThat(store.neverWritten()).as("a read writes nothing").isTrue();
    }

    @Test
    @DisplayName("a refused movement - DENY, HOLD, REVIEW, an unsatisfied CHALLENGE - increments nothing: no window"
            + " answered, no statement sent")
    void aRefusedMovementIncrementsNothing() {
        WindowCut cut = windows.cut(UOW, transfer(eur(100_00)));
        for (RiskDecision refusal : List.of(RiskDecision.DENY, RiskDecision.HOLD, RiskDecision.REVIEW)) {
            assertThat(windows.increment(UOW, cut, refusal, true)).as(refusal.name()).isEmpty();
            assertThat(windows.increment(UOW, cut, refusal, false)).as(refusal.name()).isEmpty();
        }
        assertThat(windows.increment(UOW, cut, RiskDecision.CHALLENGE, false)).as("an unsatisfied challenge").isEmpty();
        assertThat(store.neverWritten()).isTrue();
        assertThat(store.rows).isEmpty();
        assertThat(VelocityWindows.permits(RiskDecision.ALLOW, false)).isTrue();
        assertThat(VelocityWindows.permits(RiskDecision.CHALLENGE, true)).isTrue();
        assertThat(VelocityWindows.permits(RiskDecision.CHALLENGE, false)).isFalse();
        assertThat(VelocityWindows.permits(RiskDecision.HOLD, true)).isFalse();
    }

    @Test
    @DisplayName("a permitted movement counts once in every window it falls in - each dimension it carries times each"
            + " window kind - one upsert per window, in sorted key order (R2)")
    void aPermittedMovementCountsOnceInEveryWindowInSortedOrder() {
        VelocityMovement movement = VelocityMovement.of(eur(250_00)).party(PARTY).sourceAccount(SOURCE)
                .destinationAccount(DESTINATION).device(DEVICE).build();
        WindowCut cut = windows.cut(UOW, movement);
        List<VelocityWindow> counted = windows.increment(UOW, cut, RiskDecision.ALLOW, false);

        assertThat(counted).hasSize(4 * WindowKind.values().length);
        assertThat(store.upserted).as("R2's order: dimension, subject, window kind, window start")
                .isSorted()
                .doesNotHaveDuplicates()
                .containsExactlyElementsOf(cut.keys());
        assertThat(store.upserted.getFirst().dimension()).isEqualTo(VelocityDimension.PARTY);
        assertThat(store.upserted.getLast().dimension()).isEqualTo(VelocityDimension.DEVICE);
        assertThat(counted).allSatisfy(window -> {
            assertThat(window.count()).isEqualTo(1);
            assertThat(window.sum()).isEqualTo(eur(250_00));
        });

        WindowCut challenged = windows.cut(UOW, movement);
        assertThat(windows.increment(UOW, challenged, RiskDecision.CHALLENGE, true))
                .as("a satisfied challenge counts")
                .allSatisfy(window -> {
                    assertThat(window.count()).isEqualTo(2);
                    assertThat(window.sum()).isEqualTo(eur(500_00));
                });
    }

    @Test
    @DisplayName("ten permitted movements of one subject count ten, and the eleventh reads ten - the reading the policy's"
            + " '10 per 24 h per source account' refuses at")
    void tenMovementsCountTen() {
        for (int i = 0; i < 10; i++) {
            WindowCut cut = windows.cut(UOW, transfer(eur(10_00)));
            windows.increment(UOW, cut, RiskDecision.ALLOW, false);
        }
        WindowCut eleventh = windows.cut(UOW, transfer(eur(10_00)));
        Map<VelocityKey, VelocityReading> readings = windows.read(UOW, eleventh,
                VelocityWindows.keysRead(RiskPolicyFixtures.v1(RiskOperation.TRANSFER)));
        assertThat(readings).containsEntry(new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1),
                new VelocityReading(10, 100_00));

        EvaluationInputs.Builder inputs = EvaluationInputs.builder(RiskOperation.TRANSFER).amount(eur(10_00));
        readings.forEach(inputs::velocity);
        Verdict verdict = RiskEngineV1.evaluate(RiskPolicyFixtures.v1(RiskOperation.TRANSFER), inputs.build());
        assertThat(verdict.decision()).as("the fixture's VELOCITY_COUNT decides REVIEW at a prior count of 10")
                .contains(RiskDecision.REVIEW);
    }

    @Test
    @DisplayName("the policy's rules name the readings T-eval takes: each velocity rule's (dimension, window), and the"
            + " sweep's dormant-then-active party sum")
    void theKeysAPolicyReads() {
        assertThat(VelocityWindows.keysRead(RiskPolicyFixtures.v1(RiskOperation.TRANSFER))).containsExactly(
                new VelocityKey(VelocityDimension.PARTY, WindowKind.D1),
                new VelocityKey(VelocityDimension.SOURCE_ACCOUNT, WindowKind.D1));
        assertThat(VelocityWindows.keysRead(RiskPolicyFixtures.v1(RiskOperation.MONITORING)))
                .contains(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1),
                        new VelocityKey(VelocityDimension.PARTY, WindowKind.D30));
    }

    @Test
    @DisplayName("a window holding another currency is never summed or compared - refused on the read and on the count")
    void anotherCurrencyIsRefused() {
        WindowCut cut = windows.cut(UOW, transfer(eur(100_00)));
        VelocityWindowKey partyDay = cut.key(VelocityDimension.PARTY, WindowKind.D1).orElseThrow();
        store.plant(new VelocityWindow(partyDay, 1, Money.ofMinorUnits(100_00, CurrencyCode.of("USD")), DATABASE));
        assertThatThrownBy(() -> windows.read(UOW, cut, Set.of(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1))))
                .isInstanceOf(VelocityWindows.CurrencyMismatch.class);
        assertThatThrownBy(() -> windows.increment(UOW, cut, RiskDecision.ALLOW, false))
                .isInstanceOf(VelocityWindows.CurrencyMismatch.class);
    }

    @Test
    @DisplayName("T-advise's peek reads without serialising - and writes nothing")
    void thePeekTakesNoLock() {
        windows.peek(UOW, transfer(eur(100_00)), Set.of(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1)));
        assertThat(store.calls).containsExactly("now", "find 1");
    }

    @Test
    @DisplayName("a cut's movement is counted once: a second increment of the same judgement is refused before any"
            + " statement, so a retry inside one T-eval can never count a movement twice")
    void aCutCountsOnce() {
        WindowCut cut = windows.cut(UOW, transfer(eur(100_00)));
        assertThat(windows.increment(UOW, cut, RiskDecision.DENY, false)).as("a refusal spends nothing").isEmpty();
        assertThat(windows.increment(UOW, cut, RiskDecision.ALLOW, false)).hasSize(3 * WindowKind.values().length);
        int written = store.upserted.size();
        assertThatThrownBy(() -> windows.increment(UOW, cut, RiskDecision.ALLOW, false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.upserted).hasSize(written);
        assertThat(store.rows.values()).allSatisfy(window -> assertThat(window.count()).isEqualTo(1));
    }

    @Test
    @DisplayName("a movement with no subject counts nowhere; the API's arguments are required")
    void theEdges() {
        WindowCut empty = windows.cut(UOW, VelocityMovement.of(eur(1)).build());
        assertThat(windows.increment(UOW, empty, RiskDecision.ALLOW, false)).isEmpty();
        assertThatThrownBy(() -> windows.cut(UOW, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> windows.increment(UOW, empty, null, false)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new VelocityWindows(null)).isInstanceOf(NullPointerException.class);
        List<String> before = new ArrayList<>(store.calls);
        assertThat(windows.read(UOW, empty, Set.of(new VelocityKey(VelocityDimension.PARTY, WindowKind.H1)))).isEmpty();
        assertThat(store.calls.subList(before.size(), store.calls.size())).containsExactly("find 0");
    }

    private static VelocityMovement transfer(Money amount) {
        return VelocityMovement.of(amount).party(PARTY).sourceAccount(SOURCE).destinationAccount(DESTINATION).build();
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, RiskPolicyFixtures.EUR);
    }
}
