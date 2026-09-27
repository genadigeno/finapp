package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dispute machine (`P7-TSK-012`, ADR-0061 §2) — pinned edge by edge, and the order-blind
 * rule ({@link DisputeStage#pathTo}, {@link DisputeStage#openingPathTo}) held against a
 * brute-force enumeration of EVERY walk, for EVERY pair of stages: the rule may apply only the
 * stages the target implies, never one the network did not need to pass through.
 */
@DisplayName("DisputeStage (P7-TSK-012)")
class DisputeStageTest {

    @Test
    @DisplayName("the edges are ADR-0061 section 2's diagram, exactly")
    void exactlyTheEdges() {
        assertThat(DisputeStage.INQUIRY.permittedTransitions())
                .containsExactly(DisputeStage.CHARGED_BACK, DisputeStage.CLOSED);
        assertThat(DisputeStage.CHARGED_BACK.permittedTransitions())
                .containsExactly(
                        DisputeStage.REPRESENTED, DisputeStage.LOST, DisputeStage.ACCEPTED);
        assertThat(DisputeStage.REPRESENTED.permittedTransitions())
                .containsExactly(DisputeStage.WON, DisputeStage.LOST);
        for (DisputeStage terminal :
                EnumSet.of(
                        DisputeStage.WON,
                        DisputeStage.LOST,
                        DisputeStage.ACCEPTED,
                        DisputeStage.CLOSED)) {
            assertThat(terminal.permittedTransitions())
                    .as("INV-LIFE-04: %s is terminal - a second cycle is a new dispute", terminal)
                    .isEmpty();
            assertThat(terminal.isTerminal()).isTrue();
        }
    }

    @Test
    @DisplayName("terminal is exactly 'no edge', and every non-terminal stage has one")
    void terminalIsNoEdge() {
        for (DisputeStage stage : DisputeStage.values()) {
            assertThat(stage.isTerminal()).isEqualTo(stage.permittedTransitions().isEmpty());
            for (DisputeStage target : DisputeStage.values()) {
                assertThat(stage.canTransitionTo(target))
                        .isEqualTo(stage.permittedTransitions().contains(target));
            }
        }
    }

    @Test
    @DisplayName("a dispute is born at INQUIRY or CHARGED_BACK and nowhere else")
    void theEntryStages() {
        assertThat(DisputeStage.entryStages())
                .containsExactly(DisputeStage.INQUIRY, DisputeStage.CHARGED_BACK);
        for (DisputeStage stage : DisputeStage.values()) {
            assertThat(stage.isEntry()).isEqualTo(DisputeStage.entryStages().contains(stage));
        }
        assertThat(DisputeStage.entrySqlValueList()).isEqualTo("'INQUIRY', 'CHARGED_BACK'");
        // The funds are taken at CHARGED_BACK and every stage after it - the stages whose
        // dispute carries the chargeback's amount; an inquiry, open or closed, took nothing.
        for (DisputeStage stage : DisputeStage.values()) {
            assertThat(stage.isChargedBack())
                    .as("%s", stage)
                    .isEqualTo(stage != DisputeStage.INQUIRY && stage != DisputeStage.CLOSED);
            assertThat(stage.isChargedBack())
                    .as("%s: charged back exactly when CHARGED_BACK lies on the way to it", stage)
                    .isEqualTo(
                            DisputeStage.openingPathTo(stage)
                                    .contains(DisputeStage.CHARGED_BACK));
        }
        assertThat(DisputeStage.notChargedBackSqlValueList()).isEqualTo("'INQUIRY', 'CLOSED'");
        assertThat(DisputeStage.sqlValueList())
                .isEqualTo(
                        "'INQUIRY', 'CHARGED_BACK', 'REPRESENTED', 'WON', 'LOST', 'ACCEPTED',"
                                + " 'CLOSED'");
    }

    @Test
    @DisplayName("for EVERY pair, pathTo is a walk of real edges, the shortest one, and present"
            + " exactly when any walk exists")
    void pathToIsTheShortestWalkForEveryPair() {
        for (DisputeStage from : DisputeStage.values()) {
            for (DisputeStage to : DisputeStage.values()) {
                List<List<DisputeStage>> walks = walks(from, to);
                Optional<List<DisputeStage>> path = from.pathTo(to);
                if (from == to) {
                    assertThat(path).contains(List.of());
                    continue;
                }
                assertThat(path.isPresent())
                        .as("%s -> %s: present exactly when a walk exists", from, to)
                        .isEqualTo(!walks.isEmpty());
                if (path.isEmpty()) {
                    continue;
                }
                assertWalk(from, path.get(), to);
                int shortest = walks.stream().mapToInt(List::size).min().orElseThrow();
                assertThat(path.get()).as("%s -> %s is the shortest walk", from, to)
                        .hasSize(shortest);
                assertThat(from.canReach(to)).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the applied stages are exactly the IMPLIED ones: the shortest walk lies inside"
            + " every walk, for every pair - no stage is ever invented")
    void theShortestWalkLiesInsideEveryWalk() {
        for (DisputeStage from : DisputeStage.values()) {
            for (DisputeStage to : DisputeStage.values()) {
                Optional<List<DisputeStage>> path = from.pathTo(to);
                if (path.isEmpty() || from == to) {
                    continue;
                }
                for (List<DisputeStage> walk : walks(from, to)) {
                    assertThat(isSubsequence(path.get(), walk))
                            .as("%s -> %s: %s must lie inside %s", from, to, path.get(), walk)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("every stage opens by the shortest walk from an entry, which lies inside every"
            + " walk from every entry - a WON heard first opens at CHARGED_BACK")
    void everyStageOpensByItsImpliedWalk() {
        for (DisputeStage target : DisputeStage.values()) {
            List<DisputeStage> opening = DisputeStage.openingPathTo(target);
            assertThat(opening.get(0).isEntry()).as("%s opens at an entry", target).isTrue();
            assertThat(opening.get(opening.size() - 1)).isEqualTo(target);
            assertWalk(opening.get(0), opening.subList(1, opening.size()), target);
            for (DisputeStage entry : DisputeStage.entryStages()) {
                for (List<DisputeStage> tail : walks(entry, target)) {
                    List<DisputeStage> walk = new ArrayList<>();
                    walk.add(entry);
                    walk.addAll(tail);
                    assertThat(isSubsequence(opening, walk))
                            .as("opening %s must lie inside %s", opening, walk)
                            .isTrue();
                }
            }
        }
        // ADR-0061 section 2's own sentence, and its siblings.
        assertThat(DisputeStage.openingPathTo(DisputeStage.WON))
                .containsExactly(
                        DisputeStage.CHARGED_BACK, DisputeStage.REPRESENTED, DisputeStage.WON);
        assertThat(DisputeStage.openingPathTo(DisputeStage.LOST))
                .containsExactly(DisputeStage.CHARGED_BACK, DisputeStage.LOST);
        assertThat(DisputeStage.openingPathTo(DisputeStage.CLOSED))
                .containsExactly(DisputeStage.INQUIRY, DisputeStage.CLOSED);
        assertThat(DisputeStage.openingPathTo(DisputeStage.INQUIRY))
                .containsExactly(DisputeStage.INQUIRY);
        assertThat(DisputeStage.openingPathTo(DisputeStage.CHARGED_BACK))
                .containsExactly(DisputeStage.CHARGED_BACK);
        assertThat(DisputeStage.INQUIRY.pathTo(DisputeStage.WON))
                .contains(
                        List.of(
                                DisputeStage.CHARGED_BACK,
                                DisputeStage.REPRESENTED,
                                DisputeStage.WON));
    }

    @Test
    @DisplayName("late and contradictory are told apart by the graph: a stage behind the"
            + " current one reaches it; a contradiction neither reaches nor is reached")
    void lateAndContradictoryAreDistinct() {
        // Late: the network repeats an earlier stage the dispute has passed.
        assertThat(DisputeStage.REPRESENTED.canReach(DisputeStage.WON)).isTrue();
        assertThat(DisputeStage.INQUIRY.canReach(DisputeStage.LOST)).isTrue();
        // Contradictions: a second outcome, a chargeback on a closed inquiry, a
        // representment after acceptance.
        Map<DisputeStage, DisputeStage> contradictions =
                Map.of(
                        DisputeStage.WON, DisputeStage.LOST,
                        DisputeStage.CLOSED, DisputeStage.CHARGED_BACK,
                        DisputeStage.ACCEPTED, DisputeStage.REPRESENTED);
        contradictions.forEach(
                (current, notified) -> {
                    assertThat(current.canReach(notified)).isFalse();
                    assertThat(notified.canReach(current)).isFalse();
                });
    }

    // -----------------------------------------------------------------

    /** Every walk from {@code from} (exclusive) to {@code to} (inclusive), by brute force. */
    private static List<List<DisputeStage>> walks(DisputeStage from, DisputeStage to) {
        List<List<DisputeStage>> found = new ArrayList<>();
        extend(from, to, new ArrayList<>(), EnumSet.of(from), found);
        return found;
    }

    private static void extend(
            DisputeStage at,
            DisputeStage to,
            List<DisputeStage> walk,
            Set<DisputeStage> seen,
            List<List<DisputeStage>> found) {
        for (DisputeStage next : at.permittedTransitions()) {
            if (seen.contains(next)) {
                continue;
            }
            walk.add(next);
            if (next == to) {
                found.add(List.copyOf(walk));
            } else {
                seen.add(next);
                extend(next, to, walk, seen, found);
                seen.remove(next);
            }
            walk.remove(walk.size() - 1);
        }
    }

    private static void assertWalk(DisputeStage from, List<DisputeStage> walk, DisputeStage to) {
        DisputeStage at = from;
        for (DisputeStage next : walk) {
            assertThat(at.canTransitionTo(next)).as("%s -> %s is an edge", at, next).isTrue();
            at = next;
        }
        assertThat(at).isEqualTo(to);
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a chargeback STANDS exactly while the network holds the"
            + " funds, and is RESTORABLE exactly while a win is still reachable - both derived"
            + " from the machine, and their SQL lists generated from them (P7-TSK-013)")
    void standingAndRestorableAreTheMachinesOwn() {
        for (DisputeStage stage : DisputeStage.values()) {
            assertThat(stage.isStanding())
                    .as("%s stands", stage)
                    .isEqualTo(stage.isChargedBack() && stage != DisputeStage.WON);
            assertThat(stage.isRestorable())
                    .as("%s is restorable", stage)
                    .isEqualTo(stage.isStanding() && stage.canReach(DisputeStage.WON));
        }
        assertThat(java.util.Arrays.stream(DisputeStage.values()).filter(DisputeStage::isStanding))
                .containsExactly(
                        DisputeStage.CHARGED_BACK,
                        DisputeStage.REPRESENTED,
                        DisputeStage.LOST,
                        DisputeStage.ACCEPTED);
        assertThat(
                        java.util.Arrays.stream(DisputeStage.values())
                                .filter(DisputeStage::isRestorable))
                .containsExactly(DisputeStage.CHARGED_BACK, DisputeStage.REPRESENTED);
        assertThat(DisputeStage.standingSqlValueList())
                .isEqualTo("'CHARGED_BACK', 'REPRESENTED', 'LOST', 'ACCEPTED'");
        assertThat(DisputeStage.restorableSqlValueList())
                .isEqualTo("'CHARGED_BACK', 'REPRESENTED'");
    }

    private static boolean isSubsequence(List<DisputeStage> needle, List<DisputeStage> hay) {
        int matched = 0;
        for (DisputeStage stage : hay) {
            if (matched < needle.size() && needle.get(matched) == stage) {
                matched++;
            }
        }
        return matched == needle.size();
    }
}
