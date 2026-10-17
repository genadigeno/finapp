package com.finapp.bnpl;

import static com.finapp.bnpl.BnplTermsFixtures.EUR;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code BNPL_SCHEDULE_ENGINE_V1}'s properties over generated purchases (`P12-TSK-008`; ADR-0102 decision 2, A4, A5,
 * OQ-3; {@code INV-BNPL-05}, {@code INV-MON-01}): Σ principal = {@code P} exactly; instalments 2-4 equal; instalment 1
 * at least each of them and at most three minor units above; the due dates the purchase date and then exactly 14
 * calendar days apart - and the same bytes from a second JVM with another charset, locale and time zone.
 *
 * <p><strong>The oracle is this test's own arithmetic</strong>, never the engine's: {@code A} is the principal in
 * decimal divided by four to the currency's scale {@code FLOOR}, instalment 1 is {@code P - 3A} in decimal, and the
 * remainder is {@code P mod 4} in minor units; each due date is the purchase date's epoch day plus 14 (k - 1), and the
 * gap between neighbours is counted by {@link ChronoUnit#DAYS}. The count four and the interval fourteen are literals
 * here, deliberately not the engine's constants, so a change to either is a change this suite sees.
 *
 * <p><strong>The populations.</strong> Every purchase day of 2027 and 2028 (a leap day, two year ends, every weekday)
 * with eight principals each, walking every residue mod 4 from the engine's smallest to beyond credit's largest; every
 * principal from 4 to 40,000 minor units on one day; every day of a full leap cycle (2024-2031); and, for the two-JVM
 * digest, 40,000 purchases drawn from a seeded {@link SplittableRandom} - its sequence fixed by its specification, so
 * the population is the same in both JVMs.
 */
@DisplayName("BNPL schedule engine v1: properties over generated purchases, two JVMs (P12-TSK-008)")
class BnplScheduleEngineV1PropertiesTest {

    private static final BnplTerms PAY_IN_4 = BnplTermsFixtures.payIn4().build();
    private static final BigDecimal FOUR = BigDecimal.valueOf(4);

    // ------------------------------------------------------------------ the properties

    @Test
    @DisplayName("every purchase day of 2027 and 2028 x eight principals: Σ principal = P exactly, instalments 2-4 equal,"
            + " instalment 1 at least each and at most three minor units above, the remainder P mod 4 collected at"
            + " purchase, due dates D1, D1 + 14, + 28, + 42")
    void everyDayOfTwoYears() {
        int purchases = 0;
        int[] residues = new int[4];
        LocalDate first = LocalDate.of(2027, 1, 1);
        for (int offset = 0; offset < 731; offset++) {
            LocalDate day = first.plusDays(offset);
            for (long principal : ladder(offset)) {
                assertProperties(principal, day);
                residues[(int) (principal % 4)]++;
                purchases++;
            }
        }
        assertThat(purchases).isEqualTo(731 * 8);
        for (int residue = 0; residue < 4; residue++) {
            assertThat(residues[residue]).as("the remainder %d is met, many times", residue).isGreaterThan(1_000);
        }
    }

    @Test
    @DisplayName("every principal from 4 to 40,000 minor units: the properties hold for each")
    void everySmallPrincipal() {
        LocalDate day = LocalDate.of(2028, 2, 29);
        for (long principal = 4; principal <= 40_000; principal++) {
            assertProperties(principal, day);
        }
    }

    @Test
    @DisplayName("every day of a full leap cycle, 2024 to 2031: the due dates are the purchase date and then strictly 14"
            + " calendar days apart, by epoch day")
    void everyDayOfALeapCycle() {
        LocalDate day = LocalDate.of(2024, 1, 1);
        int days = 0;
        while (day.getYear() < 2032) {
            assertProperties(100_00 + days % 4, day);
            day = day.plusDays(1);
            days++;
        }
        assertThat(days).isEqualTo(2_922);
    }

    @Test
    @DisplayName("below four minor units nothing schedules - every such principal refused, none projected")
    void belowFourNothingSchedules() {
        for (long principal = -4; principal < 4; principal++) {
            assertThat(BnplScheduleEngineV1.project(PAY_IN_4, Money.ofMinorUnits(principal, EUR), LocalDate.of(2027, 6, 1)))
                    .as("%d minor units", principal).isInstanceOf(BnplScheduleOutcome.Refused.class);
        }
    }

    // ------------------------------------------------------------------ two JVMs

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other identity hash codes - produces every"
            + " schedule of 40,000 generated purchases byte for byte")
    void aSecondJvmProducesTheSameBytes() throws Exception {
        List<String> ours = Replay.digests();
        List<String> theirs = SecondJvm.run(Replay.class);
        assertThat(ours).as("the reference set: one digest per thousand schedules, then the total")
                .hasSize(Replay.SCHEDULES / Replay.CHUNK + 1);
        assertThat(theirs).as("compared byte for byte").isEqualTo(ours);
    }

    /** The second JVM's {@code main}: one SHA-256 per thousand schedules of a seeded population, then the total. */
    static final class Replay {

        static final int SCHEDULES = 40_000;
        static final int CHUNK = 1_000;
        static final long SEED = 0x5ca1ab1e_2028_0229L;

        public static void main(String[] arguments) throws Exception {
            SecondJvm.write(Path.of(arguments[0]), digests());
        }

        static List<String> digests() throws Exception {
            SplittableRandom random = new SplittableRandom(SEED);
            LocalDate epoch = LocalDate.of(2020, 1, 1);
            List<String> lines = new ArrayList<>();
            MessageDigest all = MessageDigest.getInstance("SHA-256");
            MessageDigest chunk = MessageDigest.getInstance("SHA-256");
            long instalments = 0;
            for (int index = 0; index < SCHEDULES; index++) {
                long principal = index % 2 == 0 ? random.nextLong(4, 1_000_00 + 1) : random.nextLong(4, 1_000_000_000_00L);
                LocalDate day = epoch.plusDays(random.nextInt(0, 20 * 366));
                BnplSchedule schedule = project(principal, day);
                byte[] bytes = canonical(schedule).getBytes(StandardCharsets.UTF_8);
                all.update(bytes);
                chunk.update(bytes);
                instalments += schedule.instalmentCount();
                if ((index + 1) % CHUNK == 0) {
                    lines.add((index + 1) + " " + HexFormat.of().formatHex(chunk.digest()));
                }
            }
            lines.add("schedules=" + SCHEDULES + " instalments=" + instalments + " all="
                    + HexFormat.of().formatHex(all.digest()));
            return lines;
        }

        /** Every figure of the schedule, in one canonical text. */
        static String canonical(BnplSchedule schedule) {
            StringBuilder text = new StringBuilder()
                    .append(schedule.engine()).append('|').append(schedule.currency().code()).append('|')
                    .append(schedule.principal().minorUnits()).append('|').append(schedule.purchaseDate()).append('\n');
            for (BnplSchedule.Instalment instalment : schedule.instalments()) {
                text.append(instalment.sequence()).append('|').append(instalment.dueDate()).append('|')
                        .append(instalment.principal().minorUnits()).append('|').append(instalment.principal().scale())
                        .append('\n');
            }
            return text.toString();
        }
    }

    // ------------------------------------------------------------------ the oracle

    /** Eight principals for a day: every residue mod 4, from the engine's smallest to beyond credit's largest. */
    private static long[] ladder(int offset) {
        return new long[] {
            4 + offset % 4,
            5_000 + offset,
            5_003 + 7L * offset,
            9_999 + 13L * offset,
            100_00 + offset % 4,
            99_999 - offset,
            1_000_00 + 3L * offset,
            123_456_789L + 31L * offset
        };
    }

    /** The engine's schedule of {@code principal} on {@code day}, every property re-derived by this test's arithmetic. */
    private static void assertProperties(long principal, LocalDate day) {
        BnplSchedule schedule = project(principal, day);
        List<BnplSchedule.Instalment> instalments = schedule.instalments();
        assertThat(instalments).hasSize(4);

        BigDecimal p = Money.ofMinorUnits(principal, EUR).toBigDecimal();
        BigDecimal a = p.divide(FOUR, EUR.minorUnits(), RoundingMode.FLOOR);
        BigDecimal first = p.subtract(a.multiply(BigDecimal.valueOf(3)));
        BigDecimal sum = BigDecimal.ZERO;
        for (BnplSchedule.Instalment instalment : instalments) {
            BigDecimal amount = instalment.principal().toBigDecimal();
            assertThat(amount).as("instalment %d of %d on %s", instalment.sequence(), principal, day)
                    .isEqualByComparingTo(instalment.sequence() == 1 ? first : a);
            sum = sum.add(amount);
        }
        assertThat(sum).as("Σ principal = P exactly").isEqualByComparingTo(p);
        long firstMinor = instalments.get(0).principal().minorUnits();
        long later = instalments.get(1).principal().minorUnits();
        assertThat(instalments.subList(1, 4)).as("instalments 2-4 equal")
                .allSatisfy(instalment -> assertThat(instalment.principal().minorUnits()).isEqualTo(later));
        assertThat(firstMinor - later).as("instalment 1 at least each, at most three minor units above: P mod 4")
                .isBetween(0L, 3L).isEqualTo(principal % 4);

        assertThat(instalments.get(0).dueDate()).as("instalment 1 on the purchase date").isEqualTo(day);
        for (int k = 1; k <= 4; k++) {
            assertThat(instalments.get(k - 1).dueDate()).as("D%d by epoch day", k)
                    .isEqualTo(LocalDate.ofEpochDay(day.toEpochDay() + 14L * (k - 1)));
            if (k > 1) {
                assertThat(ChronoUnit.DAYS.between(instalments.get(k - 2).dueDate(), instalments.get(k - 1).dueDate()))
                        .as("strictly 14 days apart").isEqualTo(14);
            }
        }
    }

    private static BnplSchedule project(long principal, LocalDate day) {
        BnplScheduleOutcome outcome = BnplScheduleEngineV1.project(PAY_IN_4, Money.ofMinorUnits(principal, EUR), day);
        assertThat(outcome).as(principal + " on " + day).isInstanceOf(BnplScheduleOutcome.Projected.class);
        return ((BnplScheduleOutcome.Projected) outcome).schedule();
    }
}
