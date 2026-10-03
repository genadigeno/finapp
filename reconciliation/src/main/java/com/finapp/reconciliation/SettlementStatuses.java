package com.finapp.reconciliation;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An operation's settlement status with its identifier trail (`P8-TSK-014`): expectation →
 * allocations → items → report batches → remittances → bank items, every hop a stored
 * reference in reconciliation's own schema (a remittance's {@code operation_ref} is its batch
 * id, `P8-TSK-009`), the status derived by {@link SettlementStatus#derive} and never stored.
 * Run inside one {@code REPEATABLE READ} snapshot so the status and its trail agree.
 *
 * <p><strong>Standing allocations only</strong> (the Phase 8 -> 9 transition, SET-3): a
 * counter-allocation and the original it reverses (a batch repudiation's, `P8-TSK-023`) net to
 * nothing, so neither is settlement evidence - both are dropped from every hop, the operation's
 * own and each remittance's bank allocations alike. An operation re-settled by the genuine batch
 * after a repudiation derives from that batch alone, and reaches {@code CASH_CONFIRMED} when its
 * remittance is settled; the trail never names the repudiated batch, its remittance or a bank
 * item whose allocation was countered.
 */
@RequiredArgsConstructor
public final class SettlementStatuses {

    /** Each hop's bound: an operation settles in a handful of lines, never thousands. */
    static final int BOUND = 100;

    @NonNull private final ExpectationInquiries inquiries;

    public record Trail(
            UUID expectationId,
            ExpectationKind kind,
            String operationRef,
            SettlementStatus status,
            List<UUID> allocationIds,
            List<UUID> itemIds,
            List<UUID> batchIds,
            List<UUID> remittanceIds,
            List<UUID> bankItemIds,
            boolean truncated) {

        public Trail {
            allocationIds = List.copyOf(allocationIds);
            itemIds = List.copyOf(itemIds);
            batchIds = List.copyOf(batchIds);
            remittanceIds = List.copyOf(remittanceIds);
            bankItemIds = List.copyOf(bankItemIds);
        }
    }

    /**
     * The operation's trail, or empty when no expectation tracks it (an operation that
     * settles internally opens none).
     *
     * @throws IllegalArgumentException for {@code REMITTANCE}: a report's promise, not an
     *     operation — read it as an expectation
     */
    public Optional<Trail> of(Connection unitOfWork, ExpectationKind kind, String operationRef) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(operationRef, "operationRef must not be null");
        if (kind == ExpectationKind.REMITTANCE) {
            throw new IllegalArgumentException(
                    "a remittance is a report's promise, not an operation");
        }
        Optional<ExpectationInquiries.ExpectationRow> found =
                inquiries.expectationByOperation(unitOfWork, kind, operationRef);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ExpectationInquiries.ExpectationRow expectation = found.get();
        List<ExpectationInquiries.AllocationRow> allocations =
                inquiries.allocations(unitOfWork, expectation.id(), BOUND + 1);
        boolean truncated = allocations.size() > BOUND;
        List<UUID> allocationIds = new ArrayList<>();
        Set<UUID> itemIds = new LinkedHashSet<>();
        for (ExpectationInquiries.AllocationRow allocation :
                standing(allocations.stream().limit(BOUND).toList())) {
            allocationIds.add(allocation.id());
            itemIds.add(allocation.externalItemId());
        }
        Set<UUID> batchIds = new LinkedHashSet<>();
        for (UUID item : itemIds) {
            inquiries.itemRun(unitOfWork, item)
                    .flatMap(ExpectationInquiries.ItemRun::batchId)
                    .ifPresent(batchIds::add);
        }
        List<UUID> remittanceIds = new ArrayList<>();
        Set<UUID> bankItemIds = new LinkedHashSet<>();
        List<Optional<ExpectationStatus>> remittanceStatuses = new ArrayList<>();
        for (UUID batch : batchIds) {
            Optional<ExpectationInquiries.ExpectationRow> remittance =
                    inquiries.remittanceOfBatch(unitOfWork, batch);
            remittanceStatuses.add(remittance.map(ExpectationInquiries.ExpectationRow::status));
            remittance.ifPresent(row -> {
                remittanceIds.add(row.id());
                List<ExpectationInquiries.AllocationRow> bank =
                        inquiries.allocations(unitOfWork, row.id(), BOUND + 1);
                standing(bank.stream().limit(BOUND).toList())
                        .forEach(line -> bankItemIds.add(line.externalItemId()));
            });
        }
        return Optional.of(
                new Trail(
                        expectation.id(),
                        expectation.kind(),
                        expectation.operationRef(),
                        SettlementStatus.derive(
                                expectation.status(),
                                expectation.overdueSince().isPresent(),
                                remittanceStatuses),
                        allocationIds,
                        List.copyOf(itemIds),
                        List.copyOf(batchIds),
                        remittanceIds,
                        List.copyOf(bankItemIds),
                        truncated));
    }

    /**
     * The allocations that stand: neither a counter-allocation nor an original a counter in the
     * same read reverses - the pair nets to nothing (`INV-REC-07`'s mirror).
     */
    static List<ExpectationInquiries.AllocationRow> standing(
            List<ExpectationInquiries.AllocationRow> allocations) {
        Set<UUID> countered = new HashSet<>();
        allocations.forEach(row -> row.reversesAllocationId().ifPresent(countered::add));
        return allocations.stream()
                .filter(row -> row.reversesAllocationId().isEmpty())
                .filter(row -> !countered.contains(row.id()))
                .toList();
    }
}
