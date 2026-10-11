package com.finapp.app.lending;

import com.finapp.lending.CapitalExpectations;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.settlement.SourceKind;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * lending's {@link CapitalExpectations}, over reconciliation's opener (`P11-TSK-004`, ADR-0096 section 4): the
 * approved contribution's {@code LENDING_CAPITAL_CONTRIBUTION} expectation, opened on the approval's own connection.
 *
 * <ul>
 *   <li><strong>Under the settlement bank's statement source</strong> - the declared {@code BANK_STATEMENT} source, of
 *       which the platform holds exactly one: the expectation's key is judged in that source's scope, so only that
 *       bank's statement can recognise it.
 *   <li><strong>Keyed {@code REMITTANCE_REF}</strong> = the contribution's reference - what the corporate transfer
 *       carries.
 *   <li><strong>Its window</strong> - the approval's database date plus the bank source's ACTIVE rule set's
 *       {@code funding_lag_days}, the rule set pinned on the row ({@code INV-HIST-04}): the versioned, four-eyes fact
 *       "how long funds take to land". Past it the contribution is lapsed; recognition refuses it.
 *   <li><strong>No posting of ours</strong> - the approval posts nothing.
 * </ul>
 *
 * <p>The opener converges on a standing row ({@code UNIQUE (kind, operation_ref)}): the operation reference is the
 * contribution's id, so one contribution has one expectation however its approval is retried.
 */
@RequiredArgsConstructor
public final class ReconciliationCapitalExpectations implements CapitalExpectations {

    /** The expectation's posting-key family - the act that opened it, which posted nothing. */
    static final String POSTING_KEY_PREFIX = "lending.capital-contribution:";

    @NonNull private final ExpectationRegister register;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;

    @Override
    public String open(Connection unitOfWork, Opening opening) {
        UUID bankSource = bankSource(unitOfWork);
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, bankSource);
        String operationRef = opening.contribution().value().toString();
        register.open(
                unitOfWork,
                new NewExpectation(
                        ExpectationKind.LENDING_CAPITAL_CONTRIBUTION,
                        operationRef,
                        POSTING_KEY_PREFIX + operationRef,
                        bankSource,
                        opening.capitalAccount().purpose(),
                        opening.capitalAccount().id().value(),
                        ExpectationDirection.INBOUND,
                        opening.amount(),
                        Optional.empty(),
                        opening.approvedOn(),
                        Optional.empty(),
                        opening.approvedOn().plusDays(ruleSet.fundingLagDays()),
                        ruleSet.id(),
                        List.of(new NewExpectation.ExpectationKey(KeyKind.REMITTANCE_REF, opening.reference())),
                        opening.approver(),
                        opening.at(),
                        opening.correlation()));
        return operationRef;
    }

    /** The one declared {@code BANK_STATEMENT} source's seeded row. */
    private UUID bankSource(Connection unitOfWork) {
        List<SettlementSourceDescriptor> banks =
                sources.declared().stream().filter(source -> source.kind() == SourceKind.BANK_STATEMENT).toList();
        if (banks.size() != 1) {
            throw new IllegalStateException(
                    "a capital contribution is expected on the settlement bank's statement: exactly one BANK_STATEMENT"
                            + " source is declared, found " + banks.size());
        }
        return sourceRows.sourceByCode(unitOfWork, banks.get(0).code())
                .orElseThrow(() -> new IllegalStateException("the declared bank source has its seeded row (INV-SET-05)"))
                .id();
    }
}
