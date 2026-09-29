package com.finapp.app.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.merchant.PayoutSettlementExpectations;
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
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The composition root's implementation of the expectation-opening ports (`P8-TSK-004`,
 * `P8-TSK-005`, ADR-0067 §1): {@code payments}' {@link SettlementExpectations} and
 * {@code merchant}'s {@link PayoutSettlementExpectations} are ONE recorder — the appliers call
 * it on the completing connection, and it delegates to {@code reconciliation.ExpectationRegister},
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
 *   <li><strong>Amount, direction and posting date</strong> are read off the posted entry and
 *       its clearing line ({@code JournalEntryStore.findById}, same connection): a DEBIT on the
 *       position is {@code INBOUND}, a CREDIT {@code OUTBOUND}, and the date is the entry's —
 *       "copied from the entry and never re-read from the clock" — so the expectation cannot
 *       contradict the ledger and the position proof's sign is the ledger's own (ADR-0067 §3,
 *       §4).
 *   <li><strong>The dating</strong> comes from the source's {@code ACTIVE} rule set, read
 *       lock-free and pinned on the row ({@code INV-HIST-04}).
 * </ul>
 */
@RequiredArgsConstructor
public class ReconciliationExpectationRecorder
        implements SettlementExpectations, PayoutSettlementExpectations {

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final ExpectationRegister register;
    @NonNull private final Clock clock;

    @Override
    public void open(Connection unitOfWork, SettlementExpectations.Opening opening) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(opening, "opening must not be null");
        record(
                unitOfWork,
                ExpectationKind.valueOf(opening.kind().name()),
                opening.operationRef(),
                opening.postingKey(),
                opening.position(),
                opening.clearingAccount(),
                opening.journalEntryId(),
                opening.settlementCycle(),
                opening.keys().stream()
                        .map(
                                key ->
                                        new NewExpectation.ExpectationKey(
                                                KeyKind.valueOf(key.kind().name()), key.value()))
                        .toList(),
                opening.correlation());
    }

    @Override
    public void open(Connection unitOfWork, PayoutSettlementExpectations.Opening opening) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(opening, "opening must not be null");
        record(
                unitOfWork,
                ExpectationKind.valueOf(opening.kind().name()),
                opening.operationRef(),
                opening.postingKey(),
                opening.position(),
                opening.clearingAccount(),
                opening.journalEntryId(),
                // A payout announces no cycle: its report's own dating is the comparison.
                Optional.empty(),
                opening.keys().stream()
                        .map(
                                key ->
                                        new NewExpectation.ExpectationKey(
                                                KeyKind.valueOf(key.kind().name()), key.value()))
                        .toList(),
                opening.correlation());
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

    /** Both ports' one path: source, line and rule set resolved, then the register's insert. */
    private void record(
            Connection unitOfWork,
            ExpectationKind kind,
            String operationRef,
            String postingKey,
            AccountPurpose position,
            LedgerAccountId clearingAccount,
            JournalEntryId entryId,
            Optional<String> settlementCycle,
            List<NewExpectation.ExpectationKey> keys,
            Correlation correlation) {
        UUID sourceId = sourceIdFor(unitOfWork, position);
        ClearingLine line = clearingLineOf(unitOfWork, entryId, clearingAccount);
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, sourceId);
        register.open(
                unitOfWork,
                new NewExpectation(
                        kind,
                        operationRef,
                        postingKey,
                        sourceId,
                        position,
                        clearingAccount.value(),
                        line.direction(),
                        line.amount(),
                        Optional.of(entryId.value()),
                        line.postingDate(),
                        settlementCycle,
                        line.postingDate().plusDays(ruleSet.lagDaysFor(kind)),
                        ruleSet.id(),
                        keys,
                        SecurityContext.require(),
                        clock.instant(),
                        correlation.correlationId()));
    }

    private UUID sourceIdFor(Connection unitOfWork, AccountPurpose position) {
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

    /** The one line of {@code entryId} on the clearing account — amount, direction, date. */
    private ClearingLine clearingLineOf(
            Connection unitOfWork, JournalEntryId entryId, LedgerAccountId clearingAccount) {
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
                        : ExpectationDirection.OUTBOUND,
                posted.entry().postingDate());
    }

    private record ClearingLine(
            Money amount, ExpectationDirection direction, LocalDate postingDate) {}
}
