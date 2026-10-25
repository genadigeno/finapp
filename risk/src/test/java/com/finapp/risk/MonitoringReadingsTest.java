package com.finapp.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.finapp.risk.testing.RiskPolicyFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The monitoring readings (`P13-TSK-020`; {@link MonitoredMovements}; {@code DESIGN-020} D3-D7; {@code INV-MON-01}):
 * exact integer arithmetic - the amounts sealed whole, the flows summed exactly, a bound exceeded refused and never cut
 * - the window cuts on the value date (the day, its ISO week, its thirty days from the velocity windows' anchor; an hour
 * never), the party's movements as it sees them (the counterparty side inverted, its own internal moves and the
 * record's corrections not movements of it, another currency outside the thresholds), the dormancy, `-006`'s committed
 * windows reused, and the merge into `-014`'s one value.
 */
@DisplayName("the monitoring readings: exact arithmetic and the window cuts on the value date (P13-TSK-020)")
class MonitoringReadingsTest {

    private static final LocalDate TUESDAY = LocalDate.parse("2026-10-20");

    private final MonitoringWorld world = new MonitoringWorld();
    private final MonitoredMovements reader = new MonitoredMovements(world.movements, world.parties,
            new VelocityWindows(world.velocity));

    @Test
    @DisplayName("the window cuts: D1 the value date, D7 its ISO week from Monday, D30 its thirty days from the anchor"
            + " (1970-01-05) - golden; an hour is never cut from a date")
    void theWindowCutsOnTheValueDate() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        for (WindowKind kind : List.of(WindowKind.D1, WindowKind.D7, WindowKind.D30, WindowKind.H1)) {
            world.movements.reads.clear();
            reader.readDay(MonitoringWorld.UOW, party, reader.accountsOf(MonitoringWorld.UOW, party),
                    structuring(kind), TUESDAY);
            String expected = switch (kind) {
                case D1 -> "movements 2026-10-20 2026-10-21 10001";
                case D7 -> "movements 2026-10-19 2026-10-26 10001";
                case D30 -> "movements 2026-10-08 2026-11-07 10001";
                case H1 -> null;
            };
            if (expected == null) {
                assertThat(world.movements.reads).as("H1: a value date has no hour - nothing read").isEmpty();
            } else {
                assertThat(world.movements.reads).containsExactly(expected);
            }
        }
        // The D30 golden, re-derived without WindowStarts: thirty-day steps from the Monday anchor.
        long since = ChronoUnit.DAYS.between(LocalDate.parse("1970-01-05"), TUESDAY);
        assertThat(LocalDate.parse("1970-01-05").plusDays(Math.floorDiv(since, 30) * 30))
                .isEqualTo(LocalDate.parse("2026-10-08"));
    }

    @Test
    @DisplayName("the movements as the party sees them: its own rows as stored, another customer's transfer to it"
            + " inverted to IN, its internal move and the record's corrections not movements of it, a boundary day in"
            + " exactly one bucket")
    void theMovementsAsThePartySeesThem() {
        UUID own = world.accountOfNewParty();
        UUID party = world.partyOf(own);
        UUID second = world.accountOf(party, Instant.parse("2025-02-01T00:00:00Z"));
        UUID stranger = world.accountOfNewParty();
        MonitoredTransaction paid = world.transfer(own, stranger, 400_00, TUESDAY);
        MonitoredTransaction received = world.transfer(stranger, own, 300_00, TUESDAY);
        MonitoredTransaction internal = world.transfer(own, second, 999_00, TUESDAY);
        MonitoredTransaction reversal = world.row(MonitoredKind.REVERSAL, own, MonitoredDirection.IN,
                MonitoredCounterpartyKind.CUSTOMER, Optional.of(stranger), 400_00, MonitoringWorld.EUR, TUESDAY);
        MonitoredTransaction nextDay = world.withdrawal(own, 10_00, TUESDAY.plusDays(1));
        world.movements.rows.addAll(List.of(paid, received, internal, reversal, nextDay));

        MonitoredMovements.Read read = reader.readDay(MonitoringWorld.UOW, party,
                reader.accountsOf(MonitoringWorld.UOW, party), MonitoringWorld.monitoringOf(new RiskPolicyFixtures.Rules()
                        .weighted(RuleKind.RAPID_IN_OUT, Map.of("window", RiskPolicyFixtures.window(WindowKind.D1),
                                "ratio_bps", RiskPolicyFixtures.bps(8_000)), 400)), TUESDAY);
        assertThat(read.window(WindowKind.D1)).extracting(MonitoredMovement::journalEntryId)
                .containsExactly(paid.journalEntryId(), received.journalEntryId());
        assertThat(read.window(WindowKind.D1)).extracting(MonitoredMovement::direction)
                .containsExactly(MonitoredDirection.OUT, MonitoredDirection.IN);
        assertThat(read.readings().flows()).containsExactlyEntriesOf(Map.of(WindowKind.D1, new Flow(300_00, 400_00)));
    }

    @Test
    @DisplayName("the amounts are sealed whole in the policy's money - another currency outside every threshold; the"
            + " flows are exact sums")
    void exactAmountsAndFlowsInThePolicysMoney() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        world.movements.rows.addAll(List.of(world.withdrawal(account, 900_00, TUESDAY),
                world.withdrawal(account, 950_00, TUESDAY), world.withdrawal(account, 800_00, TUESDAY),
                world.withdrawal(account, 900_00, MonitoringWorld.USD, TUESDAY),
                world.inflow(account, 1_000_01, TUESDAY)));

        MonitoredMovements.Read read = reader.readDay(MonitoringWorld.UOW, party,
                reader.accountsOf(MonitoringWorld.UOW, party), RiskPolicyFixtures.v1(RiskOperation.MONITORING), TUESDAY);
        assertThat(read.readings().amounts().get(WindowKind.D1).minor())
                .as("sorted, EUR only - the USD 900 is no EUR 900")
                .containsExactly(800_00L, 900_00L, 950_00L, 1_000_01L);
        assertThat(read.readings().flows().get(WindowKind.D1)).isEqualTo(new Flow(1_000_01, 2_650_00));
        assertThat(read.readings().flows().get(WindowKind.D1).outAtLeast(8_000)).as("2650/1000.01 >= 80 %").isTrue();
    }

    @Test
    @DisplayName("a bound exceeded is refused, never cut: a window of more than 10,000 movements, a flow past 10^14"
            + " minor units, a sum past a long")
    void boundsAreRefusals() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        List<MonitoredTransaction> many = new ArrayList<>();
        for (int i = 0; i <= MonitoredMovements.WINDOW_BOUND; i++) {
            many.add(world.withdrawal(account, 1_00, TUESDAY));
        }
        world.movements.rows.addAll(many);
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> reader.readDay(MonitoringWorld.UOW,
                party, reader.accountsOf(MonitoringWorld.UOW, party), RiskPolicyFixtures.v1(RiskOperation.MONITORING),
                TUESDAY)).withMessageContaining("refused, never cut");

        RiskPolicy policy = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        MonitoredMovement big = new MonitoredMovement(UUID.randomUUID(), 1, MonitoredKind.WITHDRAWAL,
                MonitoredDirection.OUT, 60_000_000_000_000L, 2, MonitoringWorld.EUR, TUESDAY);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> MonitoredMovements.flowOf(List.of(big, big), policy));
        MonitoredMovement huge = new MonitoredMovement(UUID.randomUUID(), 1, MonitoredKind.WITHDRAWAL,
                MonitoredDirection.OUT, Long.MAX_VALUE, 2, MonitoringWorld.EUR, TUESDAY);
        assertThatExceptionOfType(ArithmeticException.class)
                .isThrownBy(() -> MonitoredMovements.flowOf(List.of(huge, huge), policy));
    }

    @Test
    @DisplayName("the dormancy: the days strictly between the last movement and the window's first day; never moved -"
            + " since the first account opened; no account placed - ABSENT; capped at the engine's bound")
    void theDormancy() {
        RiskPolicy policy = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        UUID account = world.accountOf(UUID.randomUUID(), Instant.parse("2025-01-01T09:00:00Z"));
        UUID party = world.partyOf(account);
        assertThat(dormant(party, policy)).as("never moved: 2025-01-01 to 2026-10-20").contains(657);
        world.movements.rows.add(world.inflow(account, 50_00, LocalDate.parse("2026-06-01")));
        assertThat(dormant(party, policy)).as("2026-06-01 moved; 140 silent days before 2026-10-20").contains(140);
        world.movements.rows.add(world.inflow(account, 50_00, TUESDAY.minusDays(1)));
        assertThat(dormant(party, policy)).as("moved the day before").contains(0);

        assertThat(dormant(UUID.randomUUID(), policy)).as("no account placed: we did not look").isEmpty();

        UUID ancient = world.accountOf(UUID.randomUUID(), Instant.parse("1900-01-01T00:00:00Z"));
        assertThat(dormant(world.partyOf(ancient), policy)).contains(EvaluationInputs.MAX_DAYS);
    }

    @Test
    @DisplayName("`-006`'s windows reused: the PARTY window containing the day, never opened PRESENT(0, 0), another"
            + " currency ABSENT, another dimension ABSENT, an hour only as of an instant")
    void theVelocityWindowsReused() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        RiskPolicy policy = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        MonitoredMovements.Read none = reader.readDay(MonitoringWorld.UOW, party,
                reader.accountsOf(MonitoringWorld.UOW, party), policy, TUESDAY);
        assertThat(none.velocity()).as("never opened: read and found nothing")
                .containsEntry(new VelocityKey(VelocityDimension.PARTY, WindowKind.D30), new VelocityReading(0, 0))
                .containsEntry(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1), new VelocityReading(0, 0));

        world.partyWindow(party, WindowKind.D30, TUESDAY, 7, 51_000_00, MonitoringWorld.EUR);
        world.partyWindow(party, WindowKind.D1, TUESDAY, 1, 1_200_00, MonitoringWorld.USD);
        MonitoredMovements.Read read = reader.readDay(MonitoringWorld.UOW, party,
                reader.accountsOf(MonitoringWorld.UOW, party), policy, TUESDAY);
        assertThat(read.velocity()).containsEntry(new VelocityKey(VelocityDimension.PARTY, WindowKind.D30),
                new VelocityReading(7, 51_000_00));
        assertThat(read.velocity()).as("a USD window is not compared with EUR thresholds")
                .doesNotContainKey(new VelocityKey(VelocityDimension.PARTY, WindowKind.D1));

        RiskPolicy perAccount = MonitoringWorld.monitoringOf(new RiskPolicyFixtures.Rules()
                .weighted(RuleKind.VELOCITY_COUNT, Map.of("dimension",
                        new ParameterValue.Dimension(VelocityDimension.SOURCE_ACCOUNT), "window",
                        RiskPolicyFixtures.window(WindowKind.D1), "threshold_count", RiskPolicyFixtures.count(1)), 100)
                .weighted(RuleKind.VELOCITY_COUNT, Map.of("dimension",
                        new ParameterValue.Dimension(VelocityDimension.PARTY), "window",
                        RiskPolicyFixtures.window(WindowKind.H1), "threshold_count", RiskPolicyFixtures.count(1)), 100));
        assertThat(reader.readDay(MonitoringWorld.UOW, party, reader.accountsOf(MonitoringWorld.UOW, party),
                perAccount, TUESDAY).velocity()).as("no account singled out; no hour in a day").isEmpty();
        assertThat(reader.readAt(MonitoringWorld.UOW, party, perAccount, MonitoringWorld.NOW).velocity())
                .as("as of an instant the hour's window is read").containsOnlyKeys(
                        new VelocityKey(VelocityDimension.PARTY, WindowKind.H1));
    }

    @Test
    @DisplayName("CORRIDOR_HIGH_RISK reads ABSENT over the journal - it carries no corridor - and ROUND_AMOUNT reads"
            + " the movement's own amount in the policy's money")
    void theMovementKinds() {
        UUID account = world.accountOfNewParty();
        UUID party = world.partyOf(account);
        RiskPolicy policy = RiskPolicyFixtures.v1(RiskOperation.MONITORING);
        MonitoredTransaction round = world.withdrawal(account, 3_000_00, TUESDAY);
        world.movements.rows.add(round);
        MonitoredMovements.Read read = reader.readDay(MonitoringWorld.UOW, party,
                reader.accountsOf(MonitoringWorld.UOW, party), policy, TUESDAY);
        MonitoredMovement movement = read.window(WindowKind.D1).getFirst();
        Verdict verdict = RiskEngineV1.evaluate(policy, read.inputs(party, policy, Optional.of(movement)));
        assertThat(hit(verdict, RuleKind.CORRIDOR_HIGH_RISK).outcome()).isEqualTo(RuleOutcome.ABSENT);
        assertThat(hit(verdict, RuleKind.ROUND_AMOUNT).outcome()).isEqualTo(RuleOutcome.FIRED);

        MonitoredMovement dollars = new MonitoredMovement(UUID.randomUUID(), 1, MonitoredKind.WITHDRAWAL,
                MonitoredDirection.OUT, 3_000_00, 2, MonitoringWorld.USD, TUESDAY);
        Verdict usd = RiskEngineV1.evaluate(policy, read.inputs(party, policy, Optional.of(dollars)));
        assertThat(hit(usd, RuleKind.ROUND_AMOUNT).outcome()).as("no EUR amount sealed").isEqualTo(RuleOutcome.ABSENT);
    }

    @Test
    @DisplayName("the merge: the journal's readings join -014's ratio and BNPL maps in ONE value, and neither side may"
            + " replace a reading the other sealed")
    void theMergeNeverReplaces() {
        MonitoringReadings abuse = new MonitoringReadings(Reading.absent(), Map.of(), Map.of(),
                Map.of(WindowKind.D30, new Ratio(3, 10)), Map.of(), Map.of(WindowKind.D7, 2L));
        MonitoringReadings journal = MonitoringReadings.journal(Reading.of(120), Map.of(WindowKind.D1,
                new Flow(1_000_00, 900_00)), Map.of(WindowKind.D1, Amounts.of(900_00)));
        MonitoringReadings merged = abuse.withJournal(journal);
        assertThat(merged.refunds()).isEqualTo(abuse.refunds());
        assertThat(merged.bnplApplications()).isEqualTo(abuse.bnplApplications());
        assertThat(merged.dormantDays()).isEqualTo(Reading.of(120));
        assertThat(merged.flows()).isEqualTo(journal.flows());
        assertThat(merged.amounts()).isEqualTo(journal.amounts());
        assertThat(MonitoringReadings.none().withJournal(MonitoringReadings.none())).isEqualTo(MonitoringReadings.none());
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> journal.withJournal(journal))
                .withMessageContaining("never replaces");
    }

    // ------------------------------------------------------------------

    private Optional<Integer> dormant(UUID party, RiskPolicy policy) {
        return reader.readDay(MonitoringWorld.UOW, party, reader.accountsOf(MonitoringWorld.UOW, party), policy,
                TUESDAY).readings().dormantDays().value();
    }

    static RuleHit hit(Verdict verdict, RuleKind kind) {
        return verdict.hits().stream().filter(hit -> hit.kind() == kind).findFirst().orElseThrow();
    }

    private static RiskPolicy structuring(WindowKind window) {
        return MonitoringWorld.monitoringOf(new RiskPolicyFixtures.Rules().weighted(RuleKind.STRUCTURING, Map.of(
                "window", RiskPolicyFixtures.window(window), "below_minor", RiskPolicyFixtures.minor(1_000_00),
                "threshold_count", RiskPolicyFixtures.count(3), "sum_minor", RiskPolicyFixtures.minor(2_500_00)), 500));
    }
}
