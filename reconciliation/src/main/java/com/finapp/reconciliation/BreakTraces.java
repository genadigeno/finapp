package com.finapp.reconciliation;

import com.finapp.reconciliation.BreakTrace.NodeKind;
import com.finapp.reconciliation.BreakTrace.Relation;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Walks a break's stored identifier chain (`P8-TSK-014`, ADR-0069 §7) breadth-first, from the
 * break to the raw settlement file and the journal entries, and on the internal side to the
 * provider evidence — every step a stored reference a previous step read, never a timestamp
 * join. Bounded: at most {@value #MAX_STEPS} steps ({@code truncated} says when more exist),
 * and an expectation's allocations fan out only when the expectation IS the break's subject
 * (a remittance can carry thousands). Only the traced break is expanded; a predecessor or an
 * owning break is a node the investigator traces on its own.
 */
@RequiredArgsConstructor
public final class BreakTraces {

    static final int MAX_STEPS = 500;

    static final int EXPECTATION_ALLOCATIONS = 50;

    @NonNull private final BreakInquiries inquiries;
    @NonNull private final TraceEvidence evidence;

    /** The trace, or empty for an unknown break. Run inside one REPEATABLE READ snapshot. */
    public Optional<BreakTrace> trace(Connection unitOfWork, UUID breakId) {
        Optional<BreakCaseStore.BreakRow> found = inquiries.breakById(unitOfWork, breakId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Walk walk = new Walk(unitOfWork, found.get());
        walk.run();
        return Optional.of(new BreakTrace(breakId, List.copyOf(walk.steps), walk.truncated));
    }

    private record Node(NodeKind kind, String id) {}

    private final class Walk {

        private final Connection unitOfWork;
        private final BreakCaseStore.BreakRow traced;
        private final Set<BreakTrace.Step> steps = new LinkedHashSet<>();
        private final Set<Node> seen = new HashSet<>();
        private final Deque<Node> queue = new ArrayDeque<>();
        private final Map<UUID, BreakInquiries.AllocationLink> allocations = new HashMap<>();
        private boolean truncated;

        Walk(Connection unitOfWork, BreakCaseStore.BreakRow traced) {
            this.unitOfWork = unitOfWork;
            this.traced = traced;
        }

        void run() {
            Node root = new Node(NodeKind.BREAK, traced.id().toString());
            seen.add(root);
            expandTracedBreak();
            while (!queue.isEmpty() && !truncated) {
                expand(queue.removeFirst());
            }
        }

        private void step(NodeKind fromKind, Object fromId, Relation relation,
                NodeKind toKind, Object toId) {
            if (truncated) {
                return;
            }
            BreakTrace.Step step =
                    new BreakTrace.Step(
                            fromKind, fromId.toString(), relation, toKind, toId.toString());
            if (steps.contains(step)) {
                return;
            }
            if (steps.size() >= MAX_STEPS) {
                truncated = true;
                return;
            }
            steps.add(step);
            Node target = new Node(toKind, toId.toString());
            if (seen.add(target)) {
                queue.addLast(target);
            }
        }

        private void expandTracedBreak() {
            String id = traced.id().toString();
            step(NodeKind.BREAK, id, Relation.SUBJECT, subjectKind(traced.subjectKind()),
                    traced.subjectId());
            for (BreakCaseStore.BreakRow predecessor :
                    inquiries.predecessors(unitOfWork, traced.id())) {
                step(NodeKind.BREAK, id, Relation.FOLLOWS, NodeKind.BREAK, predecessor.id());
            }
            for (BreakInquiries.NoteRow note : inquiries.notes(unitOfWork, traced.id())) {
                step(NodeKind.BREAK, id, Relation.NOTED, NodeKind.NOTE, note.id());
            }
            for (BreakInquiries.LinkRow link : inquiries.links(unitOfWork, traced.id())) {
                step(NodeKind.BREAK, id, Relation.LINKED, NodeKind.EVIDENCE_LINK, link.id());
            }
            for (BreakInquiries.ResolutionRow resolution :
                    inquiries.resolutions(unitOfWork, traced.id())) {
                step(NodeKind.BREAK, id, Relation.RESOLVED_BY, NodeKind.RESOLUTION,
                        resolution.id());
                resolution.adjustmentProposalId().ifPresent(proposal -> step(
                        NodeKind.RESOLUTION, resolution.id(), Relation.PROPOSED_AS,
                        NodeKind.ADJUSTMENT_PROPOSAL, proposal));
                resolution.journalEntryId().ifPresent(entry -> step(
                        NodeKind.RESOLUTION, resolution.id(), Relation.POSTED_AS,
                        NodeKind.JOURNAL_ENTRY, entry));
                resolution.parkId().ifPresent(park -> step(
                        NodeKind.RESOLUTION, resolution.id(), Relation.RELEASED_BY,
                        NodeKind.PARK, park));
                resolution.decisionId().ifPresent(decision -> step(
                        NodeKind.RESOLUTION, resolution.id(), Relation.DECIDED_BY,
                        NodeKind.DECISION, decision));
            }
        }

        private void expand(Node node) {
            switch (node.kind()) {
                case EXPECTATION -> expandExpectation(UUID.fromString(node.id()));
                case OPERATION -> expandOperation(node.id());
                case EXTERNAL_ITEM -> expandItem(UUID.fromString(node.id()));
                case SETTLEMENT_LINE -> evidence
                        .batchOfLine(unitOfWork, UUID.fromString(node.id()))
                        .ifPresent(batch -> step(NodeKind.SETTLEMENT_LINE, node.id(),
                                Relation.OF_BATCH, NodeKind.SETTLEMENT_BATCH, batch));
                case RUN -> inquiries
                        .batchOfRun(unitOfWork, UUID.fromString(node.id()))
                        .ifPresent(batch -> step(NodeKind.RUN, node.id(), Relation.OF_BATCH,
                                NodeKind.SETTLEMENT_BATCH, batch));
                case SETTLEMENT_BATCH -> evidence
                        .batch(unitOfWork, UUID.fromString(node.id()))
                        .ifPresent(facts -> {
                            step(NodeKind.SETTLEMENT_BATCH, node.id(), Relation.FROM_FILE,
                                    NodeKind.SETTLEMENT_FILE, facts.fileId());
                            facts.recognitionEntryId().ifPresent(entry -> step(
                                    NodeKind.SETTLEMENT_BATCH, node.id(),
                                    Relation.RECOGNISED_BY, NodeKind.JOURNAL_ENTRY, entry));
                        });
                case DECISION -> expandDecision(UUID.fromString(node.id()));
                case ALLOCATION -> expandAllocation(UUID.fromString(node.id()));
                case SUSPENSE_ITEM -> expandSuspense(UUID.fromString(node.id()));
                case PARK -> inquiries
                        .entryOfPark(unitOfWork, UUID.fromString(node.id()))
                        .ifPresent(entry -> step(NodeKind.PARK, node.id(), Relation.POSTED_AS,
                                NodeKind.JOURNAL_ENTRY, entry));
                // Leaves, and breaks other than the traced one (traced on their own).
                case BREAK, RESOLUTION, ADJUSTMENT_PROPOSAL, JOURNAL_ENTRY, SETTLEMENT_FILE,
                                PROVIDER_EVIDENCE, NOTE, EVIDENCE_LINK -> { }
            }
        }

        private void expandExpectation(UUID expectationId) {
            Optional<BreakInquiries.ExpectationLink> found =
                    inquiries.expectation(unitOfWork, expectationId);
            if (found.isEmpty()) {
                return;
            }
            BreakInquiries.ExpectationLink expectation = found.get();
            expectation.journalEntryId().ifPresent(entry -> step(
                    NodeKind.EXPECTATION, expectationId, Relation.OPENED_BY,
                    NodeKind.JOURNAL_ENTRY, entry));
            if (expectation.kind() == ExpectationKind.REMITTANCE) {
                // A remittance's operation IS its report batch (P8-TSK-009: operation_ref is
                // the batch id) - evidence's own promise, with no entry of its own.
                step(NodeKind.EXPECTATION, expectationId, Relation.OF_BATCH,
                        NodeKind.SETTLEMENT_BATCH, UUID.fromString(expectation.operationRef()));
            } else {
                step(NodeKind.EXPECTATION, expectationId, Relation.TRACKS, NodeKind.OPERATION,
                        expectation.kind().name() + ":" + expectation.operationRef());
            }
            if (traced.expectationId().filter(expectationId::equals).isPresent()) {
                for (BreakInquiries.AllocationLink allocation :
                        inquiries.allocationsOfExpectation(
                                unitOfWork, expectationId, EXPECTATION_ALLOCATIONS)) {
                    allocations.put(allocation.allocationId(), allocation);
                    step(NodeKind.EXPECTATION, expectationId, Relation.ALLOCATED,
                            NodeKind.ALLOCATION, allocation.allocationId());
                }
            }
        }

        private void expandOperation(String operation) {
            int colon = operation.indexOf(':');
            ExpectationKind kind = ExpectationKind.valueOf(operation.substring(0, colon));
            for (UUID provided :
                    evidence.providerEvidence(
                            unitOfWork, kind, operation.substring(colon + 1))) {
                step(NodeKind.OPERATION, operation, Relation.EVIDENCED_BY,
                        NodeKind.PROVIDER_EVIDENCE, provided);
            }
        }

        private void expandItem(UUID itemId) {
            Optional<BreakInquiries.ItemLink> found = inquiries.item(unitOfWork, itemId);
            if (found.isEmpty()) {
                return;
            }
            BreakInquiries.ItemLink item = found.get();
            step(NodeKind.EXTERNAL_ITEM, itemId, Relation.CARRIED_BY_LINE,
                    NodeKind.SETTLEMENT_LINE, item.settlementLineId());
            step(NodeKind.EXTERNAL_ITEM, itemId, Relation.IN_RUN, NodeKind.RUN, item.runId());
            for (UUID decision : inquiries.decisionsOfItem(unitOfWork, itemId)) {
                step(NodeKind.EXTERNAL_ITEM, itemId, Relation.DECIDED_BY, NodeKind.DECISION,
                        decision);
            }
            for (BreakInquiries.AllocationLink allocation :
                    inquiries.allocationsOfItem(unitOfWork, itemId)) {
                allocations.put(allocation.allocationId(), allocation);
                step(NodeKind.EXTERNAL_ITEM, itemId, Relation.ALLOCATED, NodeKind.ALLOCATION,
                        allocation.allocationId());
            }
            for (BreakInquiries.SuspenseLink suspense :
                    inquiries.suspenseOfItem(unitOfWork, itemId)) {
                step(NodeKind.EXTERNAL_ITEM, itemId, Relation.PARKED_AS,
                        NodeKind.SUSPENSE_ITEM, suspense.suspenseItemId());
            }
        }

        private void expandDecision(UUID decisionId) {
            inquiries.itemOfDecision(unitOfWork, decisionId).ifPresent(item -> step(
                    NodeKind.DECISION, decisionId, Relation.DECIDED, NodeKind.EXTERNAL_ITEM,
                    item));
            for (BreakInquiries.AllocationLink allocation :
                    inquiries.allocationsOfDecision(unitOfWork, decisionId)) {
                allocations.put(allocation.allocationId(), allocation);
                step(NodeKind.DECISION, decisionId, Relation.ALLOCATED, NodeKind.ALLOCATION,
                        allocation.allocationId());
            }
        }

        private void expandAllocation(UUID allocationId) {
            BreakInquiries.AllocationLink allocation = allocations.get(allocationId);
            if (allocation == null) {
                return;
            }
            step(NodeKind.ALLOCATION, allocationId, Relation.ALLOCATED_TO,
                    NodeKind.EXPECTATION, allocation.expectationId());
            step(NodeKind.ALLOCATION, allocationId, Relation.OF_ITEM, NodeKind.EXTERNAL_ITEM,
                    allocation.itemId());
        }

        private void expandSuspense(UUID suspenseItemId) {
            Optional<BreakInquiries.SuspenseLink> found =
                    inquiries.suspenseItem(unitOfWork, suspenseItemId);
            if (found.isEmpty()) {
                return;
            }
            BreakInquiries.SuspenseLink suspense = found.get();
            suspense.externalItemId().ifPresent(item -> step(
                    NodeKind.SUSPENSE_ITEM, suspenseItemId, Relation.OF_ITEM,
                    NodeKind.EXTERNAL_ITEM, item));
            if (suspense.parkId().isPresent()) {
                step(NodeKind.SUSPENSE_ITEM, suspenseItemId, Relation.PARKED_BY, NodeKind.PARK,
                        suspense.parkId().get());
            } else {
                // A Phase 7 parking's value entered suspense by payments' own posting.
                step(NodeKind.SUSPENSE_ITEM, suspenseItemId, Relation.POSTED_AS,
                        NodeKind.JOURNAL_ENTRY, suspense.entryId());
            }
            for (BreakInquiries.ParkLink release : inquiries.releasesOf(unitOfWork, suspenseItemId)) {
                step(NodeKind.SUSPENSE_ITEM, suspenseItemId, Relation.RELEASED_BY,
                        NodeKind.PARK, release.parkId());
                step(NodeKind.PARK, release.parkId(), Relation.POSTED_AS,
                        NodeKind.JOURNAL_ENTRY, release.entryId());
            }
            if (!suspense.breakId().equals(traced.id())) {
                step(NodeKind.SUSPENSE_ITEM, suspenseItemId, Relation.OWNED_BY, NodeKind.BREAK,
                        suspense.breakId());
            }
        }
    }

    private static NodeKind subjectKind(BreakSubjectKind subject) {
        return switch (subject) {
            case EXPECTATION -> NodeKind.EXPECTATION;
            case EXTERNAL_ITEM -> NodeKind.EXTERNAL_ITEM;
            case SUSPENSE_ITEM -> NodeKind.SUSPENSE_ITEM;
            case RUN -> NodeKind.RUN;
            case DECISION -> NodeKind.DECISION;
        };
    }
}
