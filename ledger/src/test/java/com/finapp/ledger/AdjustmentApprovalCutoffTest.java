package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A person's adjustment dated into a locked period is refused at approval and stays {@code PROPOSED} (`P14-TSK-006`;
 * owner decision C3, ratified 2026-10-30; rules {@code ADJ-MANUAL}, {@code RECON-WRITE-OFF}, {@code RECON-TRANSFER},
 * {@code RECON-GAIN} - reaction {@code REFUSED_AT_APPROVAL}; {@code INV-ACC-03}, {@code INV-AUD-04}) without a database:
 * both doors - the generic {@code MANUAL} approval and the reconciliation resolution's {@code approveOwned} - judge the
 * stored posting date against the cutoff, read under its shared key, <strong>before anything is written</strong>: no
 * entry, no decision, no record, the refusal counted at the cutoff. Never floored: the date is part of what was approved.
 * The database half - the proposal still {@code PROPOSED} after the rolled-back transaction, the trigger beneath - is
 * {@code PostingFloorDatabaseTest}'s (owed, C5).
 */
@DisplayName("an adjustment dated into a locked period is refused at approval and stays PROPOSED (P14-TSK-006, C3)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AdjustmentApprovalCutoffTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate IN_PERIOD = LocalDate.of(1999, 1, 20);
    private static final LocalDate CUT = LocalDate.of(1999, 1, 31);
    private static final LocalDate AFTER = LocalDate.of(1999, 2, 1);
    private static final Actor APPROVER = new Actor("cutoff-approver", ActorType.EMPLOYEE);

    private final List<JournalEntry> appended = new ArrayList<>();
    private final List<AdjustmentProposalStatus> decisions = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<PostingObserver.Outcome> outcomes = new ArrayList<>();
    private final List<PostingObserver.Command> refusals = new ArrayList<>();

    @Test
    @DisplayName("MANUAL: a proposal dated into the locked period is ledger.PeriodClosed before anything is written - no"
            + " entry, no decision, no record; counted refused and refused at the cutoff")
    void aManualProposalDatedIntoALockIsRefusedBeforeAnythingIsWritten() {
        AdjustmentProposal proposal = manual(IN_PERIOD);
        assertThatThrownBy(() -> approve(CutoffScript.at(CUT), proposal, AdjustmentService::approve))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("MANUAL adjustment proposal " + proposal.id())
                .hasMessageContaining("dated 1999-01-20")
                .hasMessageContaining("refused at approval and left PROPOSED")
                .hasMessageNotContaining("1500");
        assertThat(appended).as("no entry").isEmpty();
        assertThat(decisions).as("the proposal is not decided - it stays PROPOSED").isEmpty();
        assertThat(audited).as("no AdjustmentPosted record").isEmpty();
        assertThat(outcomes).containsExactly(PostingObserver.Outcome.REFUSED);
        assertThat(refusals).containsExactly(PostingObserver.Command.ADJUSTMENT);
    }

    @Test
    @DisplayName("RECONCILIATION: the resolution's approveOwned answers through the same guard - refused, undecided")
    void aReconciliationProposalAnswersTheSameGuard() {
        AdjustmentProposal proposal = owned(CUT);
        assertThatThrownBy(() -> approve(CutoffScript.at(CUT), proposal, AdjustmentService::approveOwned))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("RECONCILIATION adjustment proposal " + proposal.id());
        assertThat(appended).isEmpty();
        assertThat(decisions).isEmpty();
        assertThat(refusals).containsExactly(PostingObserver.Command.ADJUSTMENT);
    }

    @Test
    @DisplayName("dated in the open period, or with no lock, it posts on its own date - never floored - and is decided")
    void aProposalDatedInTheOpenPeriodPostsOnItsDate() {
        approve(CutoffScript.at(CUT), manual(AFTER), AdjustmentService::approve);
        approve(CutoffScript.unset(), owned(IN_PERIOD), AdjustmentService::approveOwned);
        assertThat(appended).extracting(JournalEntry::postingDate).containsExactly(AFTER, IN_PERIOD);
        assertThat(decisions).containsExactly(AdjustmentProposalStatus.APPROVED, AdjustmentProposalStatus.APPROVED);
        assertThat(audited).allSatisfy(record -> assertThat(record.changeSummary().orElseThrow())
                .doesNotContain("flooredFrom"));
        assertThat(refusals).isEmpty();
    }

    // ------------------------------------------------------------------

    private PostingResult approve(
            CutoffScript cutoff,
            AdjustmentProposal proposal,
            Approval door) {
        try (SecurityContext.Scope actor = SecurityContext.enter(APPROVER);
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return door.approve(service(proposal), cutoff.connection(), proposal.id());
        }
    }

    @FunctionalInterface
    private interface Approval {
        PostingResult approve(AdjustmentService service, Connection unitOfWork, AdjustmentProposalId id);
    }

    @SuppressWarnings("unchecked")
    private AdjustmentService service(AdjustmentProposal proposal) {
        AdjustmentProposalStore<Connection> proposals = (AdjustmentProposalStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AdjustmentProposalStore.class}, (proxy, call, args) ->
                        switch (call.getName()) {
                            case "lockById" -> Optional.of(proposal);
                            case "decide" -> {
                                decisions.add((AdjustmentProposalStatus) args[2]);
                                yield true;
                            }
                            default -> throw new AssertionError("AdjustmentProposalStore." + call.getName());
                        });
        LedgerAccountStore<Connection> accounts = (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, call, args) -> {
                    if (call.getName().equals("findAllById")) {
                        return List.of(); // the binding's re-check: no reconciled position named
                    }
                    throw new AssertionError("LedgerAccountStore." + call.getName());
                });
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class}, (proxy, call, args) -> {
                    throw new AssertionError("an approval carries no key: " + call.getName());
                });
        return new AdjustmentService(
                new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                recording(JournalEntryStore.class, "append", args -> appended.add((JournalEntry) args[1])),
                proposals,
                recording(AuditWriter.class, "append", args -> audited.add((AuditRecord) args[1])),
                recording(OutboxWriter.class, "write", args -> {}),
                recording(BalanceProjection.class, "apply", args -> {}),
                IDS,
                CLOCK,
                new PostingObserver() {
                    @Override
                    public void observe(Outcome outcome, Duration elapsed) {
                        outcomes.add(outcome);
                    }

                    @Override
                    public void refusedAtTheCutoff(Command command) {
                        refusals.add(command);
                    }
                },
                accounts);
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> type, String method, java.util.function.Consumer<Object[]> record) {
        return (T) Proxy.newProxyInstance(AdjustmentApprovalCutoffTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, call, args) -> {
                    if (!call.getName().equals(method)) {
                        throw new AssertionError(type.getSimpleName() + "." + call.getName());
                    }
                    record.accept(args);
                    return null;
                });
    }

    private static AdjustmentProposal manual(LocalDate date) {
        return proposal(date, (id, lines) -> AdjustmentProposal.propose(id, date, date, "cutoff-adjustment",
                "proposed before the lock", "cutoff-initiator", lines, CLOCK));
    }

    private static AdjustmentProposal owned(LocalDate date) {
        return proposal(date, (id, lines) -> AdjustmentProposal.proposeOwned(id, date, date, "resolution=r",
                "a write-off proposed before the lock", AdjustmentReasonCode.RECONCILIATION_WRITE_OFF,
                "cutoff-initiator", lines, CLOCK));
    }

    private static AdjustmentProposal proposal(
            LocalDate date, BiFunction<AdjustmentProposalId, List<JournalLine>, AdjustmentProposal> born) {
        return born.apply(AdjustmentProposalId.next(IDS), List.of(
                new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD))));
    }
}
