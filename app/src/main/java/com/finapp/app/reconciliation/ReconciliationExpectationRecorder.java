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
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ParkedConfirmations;
import com.finapp.reconciliation.RuleSets;
import com.finapp.reconciliation.SuspenseSide;
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
 *
 * <p>Since `P8-TSK-020` it also gives a parking's value its owner
 * ({@link #parked}): the suspense item's side, amount and {@code opened_on} read off the
 * parking entry's one {@code SUSPENSE_UNMATCHED} line the same way, the owning break's source
 * the one that discharges the rail's position — so the scheme's evidence and the break answer
 * to one source.
 */
@RequiredArgsConstructor
public class ReconciliationExpectationRecorder
        implements SettlementExpectations, PayoutSettlementExpectations,
                com.finapp.fx.FxSettlementExpectations {

    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final RuleSets ruleSets;
    @NonNull private final ExpectationRegister register;
    @NonNull private final Clock clock;
    @NonNull private final ParkedConfirmations parkedConfirmations;

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
                opening.counterparty(),
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
                Optional.empty(),
                opening.keys().stream()
                        .map(
                                key ->
                                        new NewExpectation.ExpectationKey(
                                                KeyKind.valueOf(key.kind().name()), key.value()))
                        .toList(),
                opening.correlation());
    }

    /**
     * A cover leg (`P9-TSK-011`): on the provider's OWN position, so the source is the one
     * discharging (purpose, counterparty) - never the purpose's, which a counterparty-owned purpose
     * does not have ({@code INV-SET-05} per counterparty, ADR-0078).
     */
    @Override
    public void open(Connection unitOfWork, com.finapp.fx.FxSettlementExpectations.Opening opening) {
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
                // A cover announces no cycle: its leg's value date is the comparison.
                Optional.empty(),
                Optional.of(opening.counterparty()),
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

    @Override
    public void parked(Connection unitOfWork, SettlementExpectations.ParkedValue parked) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(parked, "parked must not be null");
        UUID sourceId = sourceIdFor(unitOfWork, parked.position());
        SuspenseLine line =
                suspenseLineOf(unitOfWork, parked.journalEntryId(), parked.suspenseAccount());
        RuleSets.ActiveRuleSet ruleSet = ruleSets.activeFor(unitOfWork, sourceId);
        boolean explained = parked.explainedBy().isPresent();
        parkedConfirmations.open(
                unitOfWork,
                new ParkedConfirmations.Opening(
                        parked.parkingId(),
                        sourceId,
                        ruleSet.id(),
                        line.side(),
                        line.amount(),
                        line.postingDate(),
                        parked.journalEntryId().value(),
                        explained
                                ? BreakCause.EXECUTION_ALREADY_EXPLAINED
                                : BreakCause.PARKED_ON_RECEIPT,
                        // What the parking knew, frozen on its owner: nothing named
                        // (UNKNOWN), a concluded or mismatched attempt named (TERMINAL), or a
                        // credit already explaining the execution (COMPLETED).
                        explained
                                ? InternalClassification.COMPLETED
                                : parked.attempt().isPresent()
                                        ? InternalClassification.TERMINAL
                                        : InternalClassification.UNKNOWN,
                        explained
                                ? parked.explainedBy()
                                : parked.attempt().map(attempt -> attempt.value().toString()),
                        parked.cause().name(),
                        SecurityContext.require(),
                        clock.instant(),
                        parked.correlation().correlationId()));
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
            Optional<String> counterparty,
            List<NewExpectation.ExpectationKey> keys,
            Correlation correlation) {
        UUID sourceId = sourceIdFor(unitOfWork, position, counterparty);
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
                        expectedBy(kind, line, ruleSet),
                        ruleSet.id(),
                        keys,
                        SecurityContext.require(),
                        clock.instant(),
                        correlation.correlationId()));
    }

    /**
     * When the counterparty is expected to settle the line: the entry's date plus the rule set's
     * lag - except a cover leg, which the provider confirmed for its own value date, carried as
     * the cover entry's value date (`P9-TSK-013`, PHASE_9_PLAN.md section 12.9.3): the timing
     * verdict compares the report's value date with what the provider stated, not with a lag.
     */
    private static LocalDate expectedBy(ExpectationKind kind, ClearingLine line, RuleSets.ActiveRuleSet ruleSet) {
        return kind == ExpectationKind.FX_SELL_LEG || kind == ExpectationKind.FX_BUY_LEG
                ? line.valueDate()
                : line.postingDate().plusDays(ruleSet.lagDaysFor(kind));
    }

    private UUID sourceIdFor(Connection unitOfWork, AccountPurpose position) {
        return sourceIdFor(unitOfWork, position, Optional.empty());
    }

    /** The one source discharging the position - a counterparty's own when one is named. */
    private UUID sourceIdFor(Connection unitOfWork, AccountPurpose position, Optional<String> counterparty) {
        SettlementSourceDescriptor declared =
                (counterparty.isPresent()
                                ? sources.dischargedBy(position, counterparty.get())
                                : sources.dischargedBy(position))
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
        PostedLine line = lineOf(unitOfWork, entryId, clearingAccount);
        return new ClearingLine(
                line.amount(),
                line.direction() == Direction.DEBIT
                        ? ExpectationDirection.INBOUND
                        : ExpectationDirection.OUTBOUND,
                line.postingDate(),
                line.valueDate());
    }

    /** The one line of {@code entryId} on {@code account}, as the ledger posted it. */
    private PostedLine lineOf(
            Connection unitOfWork, JournalEntryId entryId, LedgerAccountId account) {
        JournalEntryStore.PostedEntry posted =
                entries.findById(unitOfWork, entryId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "entry " + entryId + " does not read back on"
                                                        + " its own connection: the opener"
                                                        + " runs after the posting"));
        List<JournalLine> onAccount =
                posted.entry().lines().stream()
                        .filter(line -> line.account().equals(account))
                        .toList();
        if (onAccount.size() != 1) {
            throw new IllegalStateException(
                    "entry " + entryId + " holds " + onAccount.size() + " lines on the"
                            + " account where exactly one line belongs (ADR-0067 §4): the"
                            + " expectation, or the suspense item, is that line's copy");
        }
        JournalLine line = onAccount.get(0);
        return new PostedLine(
                line.amount(), line.direction(), posted.entry().postingDate(), posted.entry().valueDate());
    }

    private record PostedLine(Money amount, Direction direction, LocalDate postingDate, LocalDate valueDate) {}

    private record ClearingLine(
            Money amount, ExpectationDirection direction, LocalDate postingDate, LocalDate valueDate) {}

    /**
     * The one line of {@code entryId} on the suspense account — side, amount, date. The side is
     * the line's OWN: a CREDIT to suspense is a CREDIT item, value parked from an INBOUND
     * remainder — never through the clearing mapping, which reads a CREDIT on a position as
     * OUTBOUND (the tests agent's find, before any test ran).
     */
    private SuspenseLine suspenseLineOf(
            Connection unitOfWork, JournalEntryId entryId, LedgerAccountId suspenseAccount) {
        PostedLine line = lineOf(unitOfWork, entryId, suspenseAccount);
        return new SuspenseLine(
                line.amount(),
                line.direction() == Direction.CREDIT ? SuspenseSide.CREDIT : SuspenseSide.DEBIT,
                line.postingDate());
    }

    private record SuspenseLine(Money amount, SuspenseSide side, LocalDate postingDate) {}
}
