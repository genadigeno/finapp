package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.SettlementCycleReads.SeenCycle;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cycle worklist walks every keyset page (`P8-TSK-021`, the gate's find): a fixed first page
 * of cycle tokens would have starved every later cycle once the page's tokens were all received
 * - the `P8-TSK-019` keyset lesson, met again in the pull.
 */
@DisplayName("the pull sweep's cycle worklist: every keyset page walked (P8-TSK-021)")
class SettlementPullSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private static final int PAGE = 200;

    @Test
    @DisplayName("four hundred and fifty referenced cycles, the first page's all received: every"
            + " later cycle is still owed, in token order, and the walk stops at the short page")
    void everyPageIsWalked() {
        List<SeenCycle> referenced =
                IntStream.range(0, 450)
                        .mapToObj(i -> new SeenCycle(
                                String.format("CYC-%04d", i), NOW.minus(Duration.ofHours(3))))
                        .toList();
        Set<String> received = new HashSet<>();
        for (int i = 0; i < PAGE; i++) {
            received.add(referenced.get(i).cycle());
        }
        List<Optional<String>> asked = new ArrayList<>();
        SettlementPullSweep.CyclePages pages =
                new SettlementPullSweep.CyclePages() {
                    @Override
                    public List<SeenCycle> after(Optional<String> last) {
                        asked.add(last);
                        return referenced.stream()
                                .filter(seen -> last.isEmpty()
                                        || seen.cycle().compareTo(last.get()) > 0)
                                .limit(PAGE)
                                .toList();
                    }

                    @Override
                    public Set<String> accepted(List<String> named) {
                        return named.stream()
                                .filter(received::contains)
                                .collect(java.util.stream.Collectors.toSet());
                    }
                };

        List<String> owed = SettlementPullSweep.owedCycles(pages, NOW, Duration.ofHours(1), PAGE);

        assertThat(owed)
                .hasSize(250)
                .startsWith("CYC-0200")
                .endsWith("CYC-0449")
                .isSorted();
        assertThat(asked)
                .as("three pages, each after the last token of the one before")
                .containsExactly(
                        Optional.empty(), Optional.of("CYC-0199"), Optional.of("CYC-0399"));
    }

    @Test
    @DisplayName("no referenced cycle: one empty page, nothing owed, nothing asked of the batches")
    void anEmptyWorklistAsksOnce() {
        List<Optional<String>> asked = new ArrayList<>();
        SettlementPullSweep.CyclePages pages =
                new SettlementPullSweep.CyclePages() {
                    @Override
                    public List<SeenCycle> after(Optional<String> last) {
                        asked.add(last);
                        return List.of();
                    }

                    @Override
                    public Set<String> accepted(List<String> named) {
                        throw new AssertionError("no cycles, no read");
                    }
                };
        assertThat(SettlementPullSweep.owedCycles(pages, NOW, Duration.ofHours(1), PAGE))
                .isEmpty();
        assertThat(asked).containsExactly(Optional.empty());
    }
}
