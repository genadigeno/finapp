package com.finapp.app.reconciliation;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.SettlementExpectations;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.RuleSets;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The composition root's implementation of the expectation-opening ports (`P8-TSK-004`,
 * ADR-0067 §1): {@code payments} (and, with `-005`/`-019`, {@code merchant}) call it on the
 * completing connection, and it delegates to {@code reconciliation.ExpectationRegister} —
 * the join neither module may compile against (ADR-0064).
 *
 * <h2>Derived, never declared</h2>
 *
 * <ul>
 *   <li><strong>The source</strong> is resolved from the declared position through the same
 *       compiled register the postings' declarations built ({@code SettlementSources},
 *       `INV-SET-05`): exactly one source discharges each settling position, proven total at
 *       build time, so the lookup cannot miss at runtime — a miss is a wiring defect and
 *       throws, rolling the completion back (ADR-0067 §6).
 *   <li><strong>Amount and direction</strong> are read off the posted entry's clearing line
 *       ({@code JournalEntryStore.findById}, same connection): a DEBIT on the position is
 *       {@code INBOUND}, a CREDIT {@code OUTBOUND} — so the expectation cannot contradict
 *       the ledger, and the position proof's sign is the ledger's own (ADR-0067 §3, §4).
 *   <li><strong>The dating</strong> comes from the source's {@code ACTIVE} rule set, read
 *       lock-free and pinned on the row ({@code INV-HIST-04}).
 * </ul>
 */
@RequiredArgsConstructor
public class ReconciliationExpectationRecorder implements SettlementExpectations {

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final ExpectationRegister register;
    @NonNull private final Clock clock;

    @Override
    public void open(Connection unitOfWork, Opening opening) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(opening, "opening must not be null");

        UUID sourceId = sourceIdFor(unitOfWork, opening.position());
        ClearingLine line =
                clearingLineOf(unitOfWork, opening.journalEntryId(), opening.clearingAccount());
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, sourceId);
        ExpectationKind kind = ExpectationKind.valueOf(opening.kind().name());
        Instant now = clock.instant();

        register.open(
                unitOfWork,
                new NewExpectation(
                        kind,
                        opening.operationRef(),
                        opening.postingKey(),
                        sourceId,
                        opening.position(),
                        opening.clearingAccount().value(),
                        line.direction(),
                        line.amount(),
                        java.util.Optional.of(opening.journalEntryId().value()),
                        opening.postingDate(),
                        opening.settlementCycle(),
                        opening.postingDate().plusDays(ruleSet.lagDaysFor(kind)),
                        ruleSet.id(),
                        opening.keys().stream()
                                .map(
                                        key ->
                                                new NewExpectation.ExpectationKey(
                                                        KeyKind.valueOf(key.kind().name()),
                                                        key.value()))
                                .toList(),
                        SecurityContext.require(),
                        now,
                        opening.correlation().correlationId()));
    }

    @Override
    public void alias(Connection unitOfWork, AliasRegistration registration) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(registration, "registration must not be null");
        register.registerAlias(
                unitOfWork,
                new ExpectationRegister.NewAlias(
                        sourceIdFor(unitOfWork, registration.position()),
                        KeyKind.valueOf(registration.kind().name()),
                        registration.value(),
                        KeyKind.valueOf(registration.anchorKind().name()),
                        registration.anchorValue(),
                        SecurityContext.require(),
                        clock.instant(),
                        registration.correlation().correlationId()));
    }

    private UUID sourceIdFor(Connection unitOfWork, com.finapp.ledger.AccountPurpose position) {
        SettlementSourceDescriptor declared =
                sources.dischargedBy(position)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "no settlement source discharges " + position
                                                        + ": the composition's coverage check"
                                                        + " (INV-SET-05) makes this a wiring"
                                                        + " defect, never a runtime case"));
        return sourceRows
                .sourceByCode(unitOfWork, declared.code())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "source '" + declared.code()
                                                + "' is declared but not seeded: V002 seeds"
                                                + " every declared source"))
                .id();
    }

    /** The one line of {@code entryId} on the clearing account — amount and direction. */
    private ClearingLine clearingLineOf(
            Connection unitOfWork,
            com.finapp.ledger.JournalEntryId entryId,
            LedgerAccountId clearingAccount) {
        JournalEntryStore.PostedEntry posted =
                entries.findById(unitOfWork, entryId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "entry " + entryId + " does not read back on"
                                                        + " its own connection: the opener"
                                                        + " runs after the posting"));
        List<JournalLine> onPosition =
                posted.entry().lines().stream()
                        .filter(line -> line.account().equals(clearingAccount))
                        .toList();
        if (onPosition.size() != 1) {
            throw new IllegalStateException(
                    "entry " + entryId + " holds " + onPosition.size() + " lines on the"
                            + " clearing account where exactly one clearing line belongs"
                            + " (ADR-0067 §4): the expectation is that line's copy");
        }
        JournalLine line = onPosition.get(0);
        return new ClearingLine(
                line.amount(),
                line.direction() == Direction.DEBIT
                        ? ExpectationDirection.INBOUND
                        : ExpectationDirection.OUTBOUND);
    }

    private record ClearingLine(
            com.finapp.sharedkernel.money.Money amount, ExpectationDirection direction) {}
}
