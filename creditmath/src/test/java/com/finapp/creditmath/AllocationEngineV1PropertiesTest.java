package com.finapp.creditmath;

import static com.finapp.creditmath.AllocationEngineV1Test.eur;
import static com.finapp.creditmath.AllocationEngineV1Test.item;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.creditmath.DueState.DueItem;
import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.sharedkernel.money.Money;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ALLOCATION_ENGINE_V1}'s properties over generated inputs (`P11-TSK-008`; {@code INV-LND-04},
 * {@code INV-MON-01}): under every order, amount and treatment the lines conserve exactly, no component is paid above
 * what it had due, the money reaches billed items oldest first and each item's components in the pinned order before
 * anything overflows, the overflow follows the treatment, and the allocation is a function of its inputs alone - of
 * the set of items, not their order, and the same in a second JVM with other defaults. Seeded, so a failure
 * reproduces.
 *
 * <p>Named {@code ...PropertiesTest}, not the backlog's {@code AllocationEngineV1Properties}: every executed test class
 * ends in {@code Test} ({@code TestTaxonomyTest}; the {@code AffordabilityPropertiesTest} precedent).
 */
@DisplayName("ALLOCATION_ENGINE_V1's properties (P11-TSK-008)")
class AllocationEngineV1PropertiesTest {

    static final int CASES = 2_000;
    static final long SEED = 11_008L;

    @Test
    @DisplayName("the lines sum to the amount exactly, under every order, amount and treatment")
    void conserves() {
        for (Case c : cases(SEED, CASES)) {
            Money total = eur(0);
            for (AllocationLine line : c.allocate()) {
                total = total.plus(line.amount());
            }
            assertThat(total).as(c.toString()).isEqualTo(c.amount);
        }
    }

    @Test
    @DisplayName("no line exceeds what its component had due, nor principal not yet due; one line per component")
    void neverAboveDue() {
        for (Case c : cases(SEED + 1, CASES)) {
            List<String> seen = new ArrayList<>();
            for (AllocationLine line : c.allocate()) {
                assertThat(line.amount().isPositive()).as(c.toString()).isTrue();
                String key = line.target() + "/" + line.item() + "/" + line.component();
                assertThat(seen).as(c + " - one line per component").doesNotContain(key);
                seen.add(key);
                switch (line.target()) {
                    case DUE_ITEM -> assertThat(line.amount()).as(c.toString())
                            .isLessThanOrEqualTo(c.itemOf(line).due(line.component().orElseThrow()));
                    case NOT_DUE_PRINCIPAL -> assertThat(line.amount()).as(c.toString())
                            .isLessThanOrEqualTo(c.state.notDuePrincipal());
                    case CREDIT_BALANCE -> { }
                }
            }
        }
    }

    @Test
    @DisplayName("oldest due first, the pinned order within each item, then the treatment: every earlier bucket full")
    void respectsTheOrderAndTheTreatment() {
        for (Case c : cases(SEED + 2, CASES)) {
            // The buckets in the rule's own order, written out independently of the engine: each item oldest first,
            // its components in the pinned order; then principal not yet due under PAY_DOWN_PRINCIPAL; then credit.
            List<String> buckets = new ArrayList<>();
            List<Money> capacities = new ArrayList<>();
            List<DueItem> byDate = new ArrayList<>(c.state.items());
            byDate.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
            for (DueItem due : byDate) {
                for (AllocationComponent component : c.rules.order()) {
                    buckets.add(AllocationLine.Target.DUE_ITEM + "/" + due.id().value() + "/" + component);
                    capacities.add(due.due(component));
                }
            }
            if (c.rules.overpaymentTreatment() == OverpaymentTreatment.PAY_DOWN_PRINCIPAL) {
                buckets.add(AllocationLine.Target.NOT_DUE_PRINCIPAL.name());
                capacities.add(c.state.notDuePrincipal());
            }
            buckets.add(AllocationLine.Target.CREDIT_BALANCE.name());
            capacities.add(null);

            List<AllocationLine> lines = c.allocate();
            int previous = -1;
            for (int i = 0; i < lines.size(); i++) {
                AllocationLine line = lines.get(i);
                String key = line.target() == AllocationLine.Target.DUE_ITEM
                        ? line.target() + "/" + line.item().orElseThrow().value() + "/" + line.component().orElseThrow()
                        : line.target().name();
                int index = buckets.indexOf(key);
                assertThat(index).as(c + " - " + key + " is a bucket the rule and the treatment admit").isNotNegative();
                assertThat(index).as(c + " - lines in the rule's order").isGreaterThan(previous);
                for (int earlier = previous + 1; earlier < index; earlier++) {
                    assertThat(capacities.get(earlier).isZero()).as(c + " - skipped " + buckets.get(earlier)
                            + " though it had something due").isTrue();
                }
                if (previous >= 0) {
                    assertThat(lines.get(i - 1).amount()).as(c + " - " + buckets.get(previous) + " filled before " + key)
                            .isEqualTo(capacities.get(previous));
                }
                previous = index;
            }
        }
    }

    @Test
    @DisplayName("the allocation is a function of the set of items: any input order gives the same lines")
    void independentOfInputOrder() {
        Random shuffler = new Random(SEED + 3);
        for (Case c : cases(SEED + 3, CASES)) {
            List<DueItem> shuffled = new ArrayList<>(c.state.items());
            Collections.shuffle(shuffled, shuffler);
            DueState reordered = new DueState(shuffled, c.state.notDuePrincipal());
            assertThat(AllocationEngineV1.allocate(c.amount, reordered, c.rules)).as(c.toString()).isEqualTo(c.allocate());
        }
    }

    @Test
    @DisplayName("a prepayment conserves in one line to principal not yet due, or is refused")
    void prepaymentConserves() {
        for (Case c : cases(SEED + 4, CASES)) {
            if (c.rules.overpaymentTreatment() != OverpaymentTreatment.HOLD_AS_CREDIT || c.state.anythingBilledDue()
                    || c.amount.compareTo(c.state.notDuePrincipal()) > 0) {
                continue;
            }
            List<AllocationLine> lines = AllocationEngineV1.allocatePrepayment(c.amount, c.state, c.rules);
            assertThat(lines).as(c.toString()).isEqualTo(
                    c.amount.isZero() ? List.of() : List.of(AllocationLine.notDuePrincipal(c.amount)));
        }
    }

    @Test
    @DisplayName("a second JVM - another default charset, locale and time zone, other hash codes - allocates identically")
    void aSecondJvmAllocatesIdentically() throws Exception {
        List<String> theirs = SecondJvm.run(InAnotherJvm.class);
        List<String> ours = rendered(cases(SEED + 5, CASES));
        assertThat(theirs).hasSize(CASES);
        assertThat(theirs).isEqualTo(ours);
    }

    /** The second JVM: the same seeded cases, each allocation rendered as one line. */
    static final class InAnotherJvm {
        public static void main(String[] arguments) throws Exception {
            SecondJvm.write(Path.of(arguments[0]), rendered(cases(SEED + 5, CASES)));
        }
    }

    static List<String> rendered(List<Case> cases) {
        List<String> lines = new ArrayList<>();
        for (Case c : cases) {
            StringBuilder out = new StringBuilder();
            for (AllocationLine line : c.allocate()) {
                out.append(line.target()).append(' ').append(line.item().map(id -> id.value().toString()).orElse("-"))
                        .append(' ').append(line.component().map(Enum::name).orElse("-")).append(' ')
                        .append(line.amount().minorUnits()).append(';');
            }
            lines.add(out.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------------ the generator

    /** One generated allocation: an amount, a due state and rules. */
    record Case(Money amount, DueState state, AllocationRules rules) {

        List<AllocationLine> allocate() {
            return AllocationEngineV1.allocate(amount, state, rules);
        }

        DueItem itemOf(AllocationLine line) {
            return state.items().stream().filter(i -> i.id().equals(line.item().orElseThrow())).findFirst().orElseThrow();
        }

        @Override
        public String toString() {
            return "amount " + amount + ", " + state + ", " + rules;
        }
    }

    /**
     * {@code count} cases from {@code seed}: up to six billed items on distinct dates, some fully paid, some with a
     * zero component; principal not yet due, sometimes zero; every order and both treatments; and amounts from zero
     * through partial to well beyond everything owed.
     */
    static List<Case> cases(long seed, int count) {
        Random random = new Random(seed);
        List<List<AllocationComponent>> orders = AllocationEngineV1Test.everyOrder();
        List<Case> cases = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            int items = random.nextInt(7);
            List<DueItem> due = new ArrayList<>();
            List<Integer> offsets = new ArrayList<>();
            long owed = 0;
            for (int i = 0; i < items; i++) {
                int offset;
                do {
                    offset = random.nextInt(400);
                } while (offsets.contains(offset));
                offsets.add(offset);
                long fees = random.nextInt(4) == 0 ? random.nextInt(60_00) : 0;
                long interest = random.nextInt(5) == 0 ? 0 : random.nextInt(80_00);
                long principal = random.nextInt(5) == 0 ? 0 : random.nextInt(400_00);
                owed += fees + interest + principal;
                due.add(new DueItem(item(1_000L * n + i + 1), LocalDate.of(2027, 1, 1).plusDays(offset), eur(fees),
                        eur(interest), eur(principal)));
            }
            long notDue = random.nextInt(4) == 0 ? 0 : random.nextInt(5_000_00);
            long amount = switch (random.nextInt(4)) {
                case 0 -> 0;
                case 1 -> owed == 0 ? random.nextInt(100_00) : (long) random.nextInt((int) Math.min(owed, Integer.MAX_VALUE) + 1);
                case 2 -> owed + random.nextInt(6_000_00);
                default -> random.nextInt(8_000_00);
            };
            OverpaymentTreatment treatment = random.nextBoolean()
                    ? OverpaymentTreatment.HOLD_AS_CREDIT : OverpaymentTreatment.PAY_DOWN_PRINCIPAL;
            AllocationRules rules = new AllocationRules(EngineVersion.ALLOCATION_ENGINE_V1,
                    AllocationSequence.OLDEST_DUE_FIRST, orders.get(random.nextInt(orders.size())), treatment);
            cases.add(new Case(eur(amount), new DueState(due, eur(notDue)), rules));
        }
        return cases;
    }
}
