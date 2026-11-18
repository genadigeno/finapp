package com.finapp.reconciliation;

import static com.finapp.reconciliation.GateFixtures.APPROVER;
import static com.finapp.reconciliation.GateFixtures.FAR_FUTURE;
import static com.finapp.reconciliation.GateFixtures.IDS;
import static com.finapp.reconciliation.GateFixtures.PROPOSER;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The approver's queue's read (`P17-TSK-107`): the {@code PROPOSED} resolutions in the declared total order - proposed first,
 * first served, the id breaking the tie - walked page by page from a keyset cursor, each proposal once, and a decided one gone.
 * Through the real machine on PostgreSQL, so V026's partial index and the row comparison are the ones the door reads with.
 * The shared container holds other suites' proposals, so every assertion is over this suite's own, found in the whole walk.
 */
@Tag("database")
@DisplayName("the approver's queue of resolutions awaiting a decision (P17-TSK-107)")
class PendingResolutionQueueDatabaseTest {

    @Test
    @DisplayName("proposals are served oldest first, the id breaking the tie; a keyset walk serves each once; a decided one is gone")
    void theQueueIsTheProposalsInTheirDeclaredOrder() {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.seedRuleSet(source, ruleSet);
        ResolutionMachine machine = GateFixtures.machine();
        List<UUID> proposed = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            proposed.add(proposal(machine, source, ruleSet));
        }
        UUID decided = proposed.remove(2);
        GateFixtures.approve(machine, APPROVER, decided);

        List<ResolutionStore.PendingRow> walked = walk(new JdbcResolutionStore(), 2);
        List<UUID> ids = walked.stream().map(ResolutionStore.PendingRow::id).toList();

        assertThat(new HashSet<>(ids)).as("no proposal is served twice across the pages").hasSameSizeAs(ids);
        assertThat(ids).as("every proposal still awaiting a decision is in the queue").containsAll(proposed);
        assertThat(ids).as("a decided resolution has left the queue").doesNotContain(decided);
        assertThat(walked).as("the declared total order, (proposed_at, id) ascending, across every page")
                .isSortedAccordingTo(Comparator.comparing(ResolutionStore.PendingRow::proposedAt)
                        .thenComparing(ResolutionStore.PendingRow::id));
        List<UUID> mine = ids.stream().filter(proposed::contains).toList();
        assertThat(mine).as("this suite's proposals, in the order they were made").containsExactlyElementsOf(proposed);

        ResolutionStore.PendingRow first = walked.stream().filter(row -> row.id().equals(proposed.getFirst())).findFirst().orElseThrow();
        assertThat(first.kind()).isEqualTo(ResolutionKind.ACKNOWLEDGE);
        assertThat(first.reasonCode()).isEqualTo(ResolutionReasonCode.INTERNAL_PROCESSING_ERROR);
        assertThat(first.proposedBy()).isEqualTo(PROPOSER.id());
        assertThat(first.breakId()).as("a break's resolution names its break").isPresent();
        assertThat(first.settlementBatchId()).as("and no batch").isEmpty();
    }

    @Test
    @DisplayName("a page resumes strictly after its cursor: a proposal made after the cursor's is served, one before it never again")
    void aPageResumesStrictlyAfterItsCursor() {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.seedRuleSet(source, ruleSet);
        ResolutionMachine machine = GateFixtures.machine();
        UUID earlier = proposal(machine, source, ruleSet);
        JdbcResolutionStore store = new JdbcResolutionStore();
        ResolutionStore.PendingRow at = walk(store, 50).stream().filter(row -> row.id().equals(earlier)).findFirst().orElseThrow();
        UUID later = proposal(machine, source, ruleSet);

        List<UUID> after = GateFixtures.inCommittedTransaction(uow ->
                store.pending(uow, Optional.of(new ResolutionStore.PendingCursor(at.proposedAt(), at.id())), 1000)
                        .stream().map(ResolutionStore.PendingRow::id).toList());

        assertThat(after).contains(later).doesNotContain(earlier);
    }

    /** The whole queue, page by page, each page resuming from the last row of the one before. */
    private static List<ResolutionStore.PendingRow> walk(JdbcResolutionStore store, int pageSize) {
        List<ResolutionStore.PendingRow> walked = new ArrayList<>();
        Optional<ResolutionStore.PendingCursor> after = Optional.empty();
        Set<UUID> seen = new HashSet<>();
        while (true) {
            Optional<ResolutionStore.PendingCursor> from = after;
            List<ResolutionStore.PendingRow> page = GateFixtures.inCommittedTransaction(uow -> store.pending(uow, from, pageSize));
            assertThat(page.size()).as("a page never exceeds its limit").isLessThanOrEqualTo(pageSize);
            if (page.isEmpty()) {
                return walked;
            }
            for (ResolutionStore.PendingRow row : page) {
                assertThat(seen.add(row.id())).as("served once: %s", row.id()).isTrue();
            }
            walked.addAll(page);
            ResolutionStore.PendingRow tail = page.getLast();
            after = Optional.of(new ResolutionStore.PendingCursor(tail.proposedAt(), tail.id()));
            assertThat(walked.size()).as("the walk ends").isLessThan(100_000);
        }
    }

    /** A break of its own, and a four-eyes acknowledgement of it proposed through the machine. */
    private static UUID proposal(ResolutionMachine machine, UUID source, UUID ruleSet) {
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(source, ruleSet, "P17-107-" + UUID.randomUUID(), 9_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        UUID breakId = GateFixtures.raise(BreakType.DUPLICATE_INTERNAL, BreakCause.KEY_COLLISION,
                BreakRegister.Subject.expectation(expectation.id()), 9_00, source, ruleSet);
        ResolutionMachine.Proposed proposed = GateFixtures.propose(machine, PROPOSER, breakId, ResolutionKind.ACKNOWLEDGE,
                ResolutionReasonCode.INTERNAL_PROCESSING_ERROR, Optional.empty(), Optional.empty());
        return proposed.resolutionId();
    }
}
