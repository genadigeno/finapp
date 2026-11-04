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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting-owned door (`P14-TSK-013`; ADR-0071 section 6, ADR-0113 decision 4; {@code INV-ACC-10}, {@code INV-REV-04}, {@code INV-AUD-04}) without a database: <strong>each of the three owners refuses the other two's proposals</strong>, a proposal of this origin is held
 * to the purposes open to free adjustment at both acts, has one date, and dated on or before the cutoff is refused and never floored. The schema's half (`V030`) is {@code AdjustmentProposalMigrationTest}'s text and {@code PriorPeriodAdjustmentDatabaseTest}'s behaviour (owed, C5).
 */
@DisplayName("the accounting-owned adjustment door (P14-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AccountingOriginDoorTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate CUT = LocalDate.of(1999, 1, 31);
    private static final LocalDate AFTER = LocalDate.of(1999, 3, 10);
    private static final Actor APPROVER = new Actor("door-approver", ActorType.EMPLOYEE);
    private static final String INITIATOR = "door-initiator";

    private final List<JournalEntry> appended = new ArrayList<>();
    private final List<AdjustmentProposalStatus> decisions = new ArrayList<>();
    private final List<AdjustmentProposal> inserted = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private List<LedgerAccount> accountsNamed = List.of();

    @FunctionalInterface
    private interface Door {
        Object act(AdjustmentService service, Connection unitOfWork, AdjustmentProposalId id);
    }

    @Test
    @DisplayName("each door refuses every other origin's proposal, approval and rejection alike, before anything is written: the generic door refuses ACCOUNTING; reconciliation cannot decide a prior-period adjustment, nor accounting a resolution")
    void eachOwnerRefusesTheOthers() {
        Map<AdjustmentOrigin, AdjustmentProposal> proposals = Map.of(
                AdjustmentOrigin.MANUAL, manual(AFTER),
                AdjustmentOrigin.RECONCILIATION, reconciliation(AFTER),
                AdjustmentOrigin.ACCOUNTING, accounting(AFTER));
        Map<AdjustmentOrigin, List<Door>> doors = Map.of(
                AdjustmentOrigin.MANUAL, List.<Door>of(AdjustmentService::approve, AdjustmentService::reject),
                AdjustmentOrigin.RECONCILIATION, List.<Door>of(AdjustmentService::approveOwned, AdjustmentService::rejectOwned),
                AdjustmentOrigin.ACCOUNTING, List.<Door>of(AdjustmentService::approvePriorPeriod, AdjustmentService::rejectPriorPeriod));
        int refused = 0;
        for (AdjustmentOrigin owner : AdjustmentOrigin.values()) {
            for (AdjustmentOrigin origin : AdjustmentOrigin.values()) {
                if (owner == origin) {
                    continue;
                }
                for (Door door : doors.get(owner)) {
                    assertThatThrownBy(() -> through(door, proposals.get(origin), CutoffScript.unset()))
                            .as(owner + "'s door, a " + origin + " proposal")
                            .isInstanceOfSatisfying(AdjustmentOriginMismatchException.class, mismatch -> assertThat(mismatch.origin()).isEqualTo(origin));
                    refused++;
                }
            }
        }
        assertThat(refused).as("three owners, two others each, two acts each").isEqualTo(12);
        assertThat(appended).isEmpty();
        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("its own door posts on the date the proposal stored, both of the entry's dates, never floored, decided; a rejection decides it too")
    void itsOwnDoorPostsOnTheStoredDate() {
        PostingResult posted = (PostingResult) through(AdjustmentService::approvePriorPeriod, accounting(AFTER), CutoffScript.at(CUT));

        assertThat(posted.replayed()).isFalse();
        assertThat(appended).singleElement().satisfies(entry -> {
            assertThat(entry.postingDate()).isEqualTo(AFTER);
            assertThat(entry.valueDate()).isEqualTo(AFTER);
        });
        assertThat(decisions).containsExactly(AdjustmentProposalStatus.APPROVED);
        assertThat(audited).singleElement().satisfies(record -> assertThat(record.changeSummary().orElseThrow())
                .contains("origin=ACCOUNTING").contains("reasonCode=PRIOR_PERIOD_ADJUSTMENT").doesNotContain("flooredFrom"));

        assertThat(through(AdjustmentService::rejectPriorPeriod, accounting(AFTER), CutoffScript.at(CUT))).isEqualTo(true);
        assertThat(decisions).containsExactly(AdjustmentProposalStatus.APPROVED, AdjustmentProposalStatus.REJECTED);
    }

    @Test
    @DisplayName("dated on or before the cutoff - into the month it corrects - the ledger refuses it at the approval, ledger.PeriodClosed, before anything is written; never floored, and it stays PROPOSED")
    void datedIntoTheCorrectedPeriodTheLedgerRefuses() {
        for (LocalDate covered : List.of(CUT, CUT.minusDays(10))) {
            assertThatThrownBy(() -> through(AdjustmentService::approvePriorPeriod, accounting(covered), CutoffScript.at(CUT)))
                    .isInstanceOf(PeriodClosedException.class)
                    .hasMessageContaining("ACCOUNTING adjustment proposal")
                    .hasMessageContaining("left PROPOSED");
        }
        assertThat(appended).isEmpty();
        assertThat(decisions).isEmpty();
    }

    @Test
    @DisplayName("the closed set binds it at the proposal and again at the approval, a release having possibly widened it since; a resolution alone is exempt")
    void theClosedSetBindsIt() {
        accountsNamed = List.of(account(AccountType.ASSET, AccountPurpose.LOAN_PRINCIPAL, OwnerKind.LOAN));

        assertThatThrownBy(() -> proposing(AdjustmentService::proposePriorPeriod, command(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT, AFTER, AFTER)))
                .isInstanceOfSatisfying(AdjustmentOnReconciledPositionException.class, refused -> assertThat(refused.position()).isEqualTo(AccountPurpose.LOAN_PRINCIPAL));
        assertThat(inserted).isEmpty();
        assertThatThrownBy(() -> through(AdjustmentService::approvePriorPeriod, accounting(AFTER), CutoffScript.at(CUT)))
                .isInstanceOf(AdjustmentOnReconciledPositionException.class);
        assertThat(appended).isEmpty();

        through(AdjustmentService::approveOwned, reconciliation(AFTER), CutoffScript.at(CUT));
        assertThat(appended).as("a resolution's own lines are its to name").hasSize(1);
    }

    @Test
    @DisplayName("each door proposes its own origin: proposeOwned refuses the accounting code, proposePriorPeriod every other; the command refuses a MANUAL code; two dates are refused; what it records is ACCOUNTING, PRIOR_PERIOD_ADJUSTMENT")
    void eachDoorProposesItsOwnOrigin() {
        assertThatThrownBy(() -> proposing(AdjustmentService::proposeOwned, command(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT, AFTER, AFTER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposing(AdjustmentService::proposePriorPeriod, command(AdjustmentReasonCode.RECONCILIATION_WRITE_OFF, AFTER, AFTER)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PRIOR_PERIOD_ADJUSTMENT");
        assertThatThrownBy(() -> command(AdjustmentReasonCode.MANUAL_CORRECTION, AFTER, AFTER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> command(AdjustmentReasonCode.UNCODED, AFTER, AFTER)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposing(AdjustmentService::proposePriorPeriod, command(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT, AFTER, CUT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one date");
        assertThat(inserted).isEmpty();

        proposing(AdjustmentService::proposePriorPeriod, command(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT, AFTER, AFTER));

        assertThat(inserted).singleElement().satisfies(proposal -> {
            assertThat(proposal.origin()).isEqualTo(AdjustmentOrigin.ACCOUNTING);
            assertThat(proposal.reasonCode()).isEqualTo(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT);
            assertThat(proposal.status()).isEqualTo(AdjustmentProposalStatus.PROPOSED);
            assertThat(proposal.proposedBy()).isEqualTo(APPROVER.id());
        });
        assertThat(audited).singleElement().satisfies(record -> assertThat(record.changeSummary().orElseThrow()).contains("origin=ACCOUNTING"));
    }

    @Test
    @DisplayName("the ledger's own four eyes at this door: the proposer is refused; the same approver's repeat converges on the recorded entry; a decided proposal is not decided again")
    void theLedgersFourEyes() {
        AdjustmentProposal own = AdjustmentProposal.proposePriorPeriod(AdjustmentProposalId.next(IDS), AFTER, "ppa", "identifiers only", APPROVER.id(), lines(), CLOCK);
        assertThatThrownBy(() -> through(AdjustmentService::approvePriorPeriod, own, CutoffScript.at(CUT))).isInstanceOf(SelfApprovalRefusedException.class);

        JournalEntryId entry = JournalEntryId.next(IDS);
        AdjustmentProposal approved = new AdjustmentProposal(AdjustmentProposalId.next(IDS), AdjustmentProposalStatus.APPROVED, AFTER, AFTER, "ppa", "identifiers only", AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT,
                AdjustmentOrigin.ACCOUNTING, INITIATOR, CLOCK.instant(), lines(), Optional.of(APPROVER.id()), Optional.of(CLOCK.instant()), Optional.of(entry));
        assertThat(through(AdjustmentService::approvePriorPeriod, approved, CutoffScript.at(CUT))).isEqualTo(new PostingResult(entry, true));
        assertThat(appended).isEmpty();
    }

    @Test
    @DisplayName("the factory: origin ACCOUNTING, its one code, one date for both; the pairing holds at the constructor")
    void theFactory() {
        AdjustmentProposal proposal = accounting(AFTER);
        assertThat(proposal.postingDate()).isEqualTo(proposal.valueDate()).isEqualTo(AFTER);
        assertThat(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT.origin()).isEqualTo(AdjustmentOrigin.ACCOUNTING);
        assertThatThrownBy(() -> new AdjustmentProposal(AdjustmentProposalId.next(IDS), AdjustmentProposalStatus.PROPOSED, AFTER, AFTER, "ppa", "reason", AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT,
                AdjustmentOrigin.RECONCILIATION, INITIATOR, CLOCK.instant(), lines(), Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pairs with origin ACCOUNTING");
    }

    // ------------------------------------------------------------------

    private Object through(Door door, AdjustmentProposal proposal, CutoffScript cutoff) {
        try (SecurityContext.Scope actor = SecurityContext.enter(APPROVER);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return door.act(service(proposal), cutoff.connection(), proposal.id());
        }
    }

    @FunctionalInterface
    private interface Proposing {
        AdjustmentProposalId act(AdjustmentService service, Connection unitOfWork, OwnedAdjustmentCommand command);
    }

    private void proposing(Proposing door, OwnedAdjustmentCommand command) {
        try (SecurityContext.Scope actor = SecurityContext.enter(APPROVER);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            door.act(service(accounting(AFTER)), CutoffScript.unset().connection(), command);
        }
    }

    private static OwnedAdjustmentCommand command(AdjustmentReasonCode code, LocalDate posting, LocalDate value) {
        return new OwnedAdjustmentCommand(posting, value, "prior-period-adjustment:x", "identifiers only", code, lines());
    }

    @SuppressWarnings("unchecked")
    private AdjustmentService service(AdjustmentProposal proposal) {
        AdjustmentProposalStore<Connection> proposals = (AdjustmentProposalStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AdjustmentProposalStore.class}, (proxy, call, args) ->
                switch (call.getName()) {
                    case "lockById" -> Optional.of(proposal);
                    case "insert" -> {
                        inserted.add((AdjustmentProposal) args[1]);
                        yield null;
                    }
                    case "decide" -> {
                        decisions.add((AdjustmentProposalStatus) args[2]);
                        yield true;
                    }
                    default -> throw new AssertionError("AdjustmentProposalStore." + call.getName());
                });
        LedgerAccountStore<Connection> accounts = (LedgerAccountStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, call, args) -> {
            if (call.getName().equals("findAllById")) {
                return accountsNamed;
            }
            throw new AssertionError("LedgerAccountStore." + call.getName());
        });
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class}, (proxy, call, args) -> {
            throw new AssertionError("an owned door carries no key: " + call.getName());
        });
        return new AdjustmentService(
                new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                recording(JournalEntryStore.class, "append", args -> appended.add((JournalEntry) args[1])),
                proposals,
                recording(AuditWriter.class, "append", args -> audited.add((AuditRecord) args[1])),
                recording(OutboxWriter.class, "write", args -> { }),
                recording(BalanceProjection.class, "apply", args -> { }),
                IDS,
                CLOCK,
                new PostingObserver() {
                    @Override
                    public void observe(Outcome outcome, Duration elapsed) {
                    }

                    @Override
                    public void refusedAtTheCutoff(Command command) {
                    }
                },
                accounts);
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> type, String method, java.util.function.Consumer<Object[]> record) {
        return (T) Proxy.newProxyInstance(AccountingOriginDoorTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, call, args) -> {
            if (!call.getName().equals(method)) {
                throw new AssertionError(type.getSimpleName() + "." + call.getName());
            }
            record.accept(args);
            return null;
        });
    }

    private static List<JournalLine> lines() {
        return List.of(new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)), new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD)));
    }

    private static AdjustmentProposal manual(LocalDate date) {
        return AdjustmentProposal.propose(AdjustmentProposalId.next(IDS), date, date, "manual", "a person's", INITIATOR, lines(), CLOCK);
    }

    private static AdjustmentProposal reconciliation(LocalDate date) {
        return AdjustmentProposal.proposeOwned(AdjustmentProposalId.next(IDS), date, date, "resolution=r", "identifiers only", AdjustmentReasonCode.RECONCILIATION_WRITE_OFF, INITIATOR, lines(), CLOCK);
    }

    private static AdjustmentProposal accounting(LocalDate date) {
        return AdjustmentProposal.proposePriorPeriod(AdjustmentProposalId.next(IDS), date, "prior-period-adjustment:x", "identifiers only", INITIATOR, lines(), CLOCK);
    }

    private static LedgerAccount account(AccountType type, AccountPurpose purpose, OwnerKind unused) {
        return LedgerAccount.owned(IDS, CLOCK, type, purpose, USD, UUID.randomUUID());
    }
}
