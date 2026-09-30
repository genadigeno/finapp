package com.finapp.app.settlement;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.ExternalItems;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationRuns;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.RunKind;
import com.finapp.platform.security.Actor;
import com.finapp.settlement.AcceptedBatchIntake;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one implementation of {@code settlement}'s {@link AcceptedBatchIntake}
 * (`P8-TSK-009`, ADR-0064 §6) — the second join two modules that cannot see each other
 * compose through (`ReconciliationExpectationRecorder` was the first): on the acceptance's
 * own connection it births the run ({@code OPEN}, kind {@code BATCH}, pinning the source's
 * ACTIVE rule set), one {@code PENDING} item per canonical line with its typed keys, and —
 * when the net is not zero — the {@code REMITTANCE} expectation of |N| on the source's own
 * position, {@code expected_by = value date + funding_lag_days} from the pinned rule set.
 *
 * <p>The settlement→reconciliation vocabulary crossing is BY NAME over the mirrored enums
 * ({@code ExternalLineType}, {@code ItemKeyKind}), which the mirror guard holds name-equal —
 * so a vocabulary drift fails the build here, never a mapping table silently.
 */
@RequiredArgsConstructor
public class ReconciliationIntake implements AcceptedBatchIntake {

    @NonNull private final ReconciliationRuns runs;
    @NonNull private final ExternalItems items;
    @NonNull private final ExpectationRegister register;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final IdGenerator ids;

    @Override
    public Intaken intake(Connection unitOfWork, AcceptedBatch batch) {
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, batch.sourceId());

        UUID runId = ids.next();
        runs.birth(
                unitOfWork,
                new ReconciliationRuns.NewRun(
                        runId,
                        batch.sourceId(),
                        Optional.of(batch.batchId()),
                        RunKind.BATCH,
                        ruleSet.id(),
                        batch.businessDate(),
                        Optional.of(batch.sourceSequence()),
                        batch.lines().size(),
                        Optional.empty(),
                        Optional.empty(),
                        batch.actor(),
                        batch.at(),
                        batch.correlation().correlationId()));

        List<ExternalItems.NewItem> newItems = new ArrayList<>(batch.lines().size());
        for (CanonicalLine line : batch.lines()) {
            Map<ItemKeyKind, String> keys = new EnumMap<>(ItemKeyKind.class);
            line.references()
                    .forEach(
                            (kind, value) ->
                                    keys.put(ItemKeyKind.valueOf(kind.name()), value));
            newItems.add(
                    new ExternalItems.NewItem(
                            ids.next(),
                            runId,
                            batch.sourceId(),
                            line.lineId(),
                            line.lineNo(),
                            ExternalLineType.valueOf(line.type().name()),
                            ExpectationDirection.valueOf(line.direction().name()),
                            line.amount(),
                            batch.positionPurpose(),
                            line.businessDate(),
                            line.settlementDate(),
                            line.valueDate(),
                            line.canonicalFingerprint(),
                            keys,
                            batch.at(),
                            batch.correlation().correlationId()));
        }
        items.birthAll(unitOfWork, batch.actor(), newItems);

        // The remittance: what the counterparty says it will pay, discharged by the bank
        // (hop 2, P8-TSK-016). A zero net opens none - there is nothing to discharge.
        boolean remittanceOpened =
                openRemittance(
                        unitOfWork,
                        batch.batchId(),
                        batch.sourceId(),
                        batch.positionPurpose(),
                        batch.net(),
                        batch.acceptedOn(),
                        batch.valueDate(),
                        batch.remittanceReference(),
                        batch.actor(),
                        batch.at(),
                        batch.correlation().correlationId());
        return new Intaken(runId, newItems.size(), remittanceOpened);
    }

    /**
     * Opens — or converges on, under the register's {@code ON CONFLICT DO NOTHING} — the
     * batch's {@code REMITTANCE} expectation. Shared by the live intake and the
     * opening-position backfill (ADR-0067 §8): the batch row keeps the validated net, both
     * dates and the counterparty's reference, so an emptied register re-derives every
     * remittance from the books alone through this one path. A zero net opens none.
     */
    public boolean openRemittance(
            Connection unitOfWork,
            UUID batchId,
            UUID sourceId,
            AccountPurpose positionPurpose,
            Money net,
            LocalDate acceptedOn,
            LocalDate valueDate,
            String remittanceReference,
            Actor actor,
            Instant at,
            CorrelationId correlationId) {
        if (net.minorUnits() == 0) {
            return false;
        }
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, sourceId);
        LedgerAccount position =
                accounts.findOperational(unitOfWork, positionPurpose, net.currency())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "the chart seeds every position per"
                                                        + " currency"));
        register.open(
                unitOfWork,
                new NewExpectation(
                        ExpectationKind.REMITTANCE,
                        batchId.toString(),
                        "settlement-batch:" + batchId,
                        sourceId,
                        positionPurpose,
                        position.id().value(),
                        net.minorUnits() > 0
                                ? ExpectationDirection.INBOUND
                                : ExpectationDirection.OUTBOUND,
                        net.minorUnits() > 0 ? net : net.negated(),
                        Optional.empty(),
                        acceptedOn,
                        Optional.empty(),
                        valueDate.plusDays(ruleSet.fundingLagDays()),
                        ruleSet.id(),
                        List.of(
                                new NewExpectation.ExpectationKey(
                                        KeyKind.REMITTANCE_REF, remittanceReference)),
                        actor,
                        at,
                        correlationId));
        return true;
    }
}
