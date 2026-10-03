package com.finapp.app.reconciliation;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.reconciliation.SettlementBatchRepudiations;
import com.finapp.reconciliation.StatementChain;
import com.finapp.settlement.BatchRepudiation;
import com.finapp.settlement.BatchStatus;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Reconciliation's repudiation seam composed over settlement's {@link BatchRepudiation}
 * (`P8-TSK-023`, ADR-0064 §6): the two modules share no build edge, so {@code app} translates —
 * identifiers and state across, the caller's connection throughout, and the request's own
 * correlation (with its causation) when the flow runs inside one.
 */
@RequiredArgsConstructor
public class ComposedBatchRepudiations implements SettlementBatchRepudiations {

    @NonNull private final BatchRepudiation settlement;

    @Override
    public Optional<Batch> read(Connection unitOfWork, UUID batchId) {
        return settlement.read(unitOfWork, batchId)
                .map(
                        batch ->
                                new Batch(
                                        batch.batchId(),
                                        batch.sourceId(),
                                        batch.status() == BatchStatus.ACCEPTED,
                                        batch.recognitionEntryId(),
                                        batch.currency()));
    }

    @Override
    public Optional<StatementChain.Link> lockChainAndReadSuccessor(
            Connection unitOfWork, UUID batchId) {
        return settlement.lockSourceAndReadSuccessor(unitOfWork, batchId)
                .map(
                        link ->
                                new StatementChain.Link(
                                        link.batchId(),
                                        link.sequence(),
                                        Money.ofPersisted(
                                                link.openingMinor(), link.currency(),
                                                link.scale()),
                                        Money.ofPersisted(
                                                link.closingMinor(), link.currency(),
                                                link.scale())));
    }

    @Override
    public boolean markRepudiated(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        return settlement.markRepudiated(
                unitOfWork, batchId, resolutionId, actor, at, flow(correlation));
    }

    @Override
    public void announce(
            Connection unitOfWork,
            UUID batchId,
            UUID resolutionId,
            Optional<UUID> reversalEntryId,
            Actor actor,
            Instant at,
            CorrelationId correlation) {
        settlement.announce(
                unitOfWork, batchId, resolutionId, reversalEntryId, actor, at, flow(correlation));
    }

    /** The current flow when it is this one; otherwise a flow starting at this correlation. */
    private static Correlation flow(CorrelationId correlation) {
        return CorrelationContext.current()
                .filter(current -> current.correlationId().equals(correlation))
                .orElseGet(() -> Correlation.startingWith(correlation));
    }
}
