package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.Side;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentOrigin;
import com.finapp.ledger.AdjustmentProposal;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentProposalStatus;
import com.finapp.ledger.AdjustmentProposalStore;
import com.finapp.ledger.AdjustmentReasonCode;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingObserver;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The port's one implementation (`P14-TSK-013`; ADR-0113 decision 4; {@code INV-ACC-10}): what accounting says reaches the ledger's accounting-owned door as the ledger's own types - one date for both of the entry's, the origin and its code - and what the ledger holds comes back in
 * accounting's words. The real door on a real database is {@code PriorPeriodAdjustmentDatabaseTest}'s (owed, C5). Beside it, what an {@code ACCOUNTING} entry is to the reads that judge a month: never "past the cutoff", because they read another scope.
 */
@DisplayName("the prior-period port over the ledger's door (P14-TSK-013)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class LedgerPriorPeriodPostingsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate DATE = LocalDate.of(2026, 3, 10);
    private static final Actor PERSON = new Actor("person-1", ActorType.CUSTOMER);

    private final Map<AdjustmentProposalId, AdjustmentProposal> held = new HashMap<>();
    private final List<AdjustmentProposalStatus> decisions = new ArrayList<>();
    private final List<JournalEntry> appended = new ArrayList<>();
    private final LedgerAccount wallet = LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next());
    private final LedgerPriorPeriodPostings port = new LedgerPriorPeriodPostings(service(), accounts());

    @Test
    @DisplayName("classify: the ledger's names as strings, with the owner kind a mapping rule keys by; an account the ledger does not hold is absent")
    void classify() {
        Map<UUID, PriorPeriodPostings.Classified> classified = port.classify(connection(), List.of(wallet.id().value(), IDS.next()));

        assertThat(classified).containsOnlyKeys(wallet.id().value());
        assertThat(classified.get(wallet.id().value()).key()).isEqualTo("CUSTOMER_WALLET/CUSTOMER");
    }

    @Test
    @DisplayName("propose: origin ACCOUNTING, reason PRIOR_PERIOD_ADJUSTMENT, the one date for both of the entry's; the lines' sides and amounts as stated; and linesOf gives back what was read")
    void proposeIsTheOwnedDoor() {
        UUID other = IDS.next();
        List<PriorPeriodPostings.Line> lines = List.of(new PriorPeriodPostings.Line(wallet.id().value(), Side.DEBIT, Money.ofMinorUnits(1500, EUR)), new PriorPeriodPostings.Line(other, Side.CREDIT, Money.ofMinorUnits(1500, EUR)));

        UUID proposal = as(() -> port.propose(connection(), DATE, "prior-period-adjustment:x", "identifiers only", lines));

        AdjustmentProposal stored = held.get(AdjustmentProposalId.of(proposal));
        assertThat(stored.origin()).isEqualTo(AdjustmentOrigin.ACCOUNTING);
        assertThat(stored.reasonCode()).isEqualTo(AdjustmentReasonCode.PRIOR_PERIOD_ADJUSTMENT);
        assertThat(stored.postingDate()).isEqualTo(DATE);
        assertThat(stored.valueDate()).isEqualTo(DATE);
        assertThat(stored.reference()).isEqualTo("prior-period-adjustment:x");
        assertThat(stored.reason()).isEqualTo("identifiers only");
        assertThat(port.linesOf(connection(), proposal)).containsExactlyElementsOf(lines);
        assertThat(port.linesOf(connection(), IDS.next())).as("a proposal it does not hold").isEmpty();
    }

    @Test
    @DisplayName("linesOf reads no other origin's proposal")
    void linesOfIsItsOriginOnly() {
        AdjustmentProposal manual = AdjustmentProposal.propose(AdjustmentProposalId.next(IDS), DATE, DATE, "manual", "a person's", "person-2",
                List.of(new com.finapp.ledger.JournalLine(wallet.id(), com.finapp.ledger.Direction.DEBIT, Money.ofMinorUnits(1, EUR)), new com.finapp.ledger.JournalLine(wallet.id(), com.finapp.ledger.Direction.CREDIT, Money.ofMinorUnits(1, EUR))), CLOCK);
        held.put(manual.id(), manual);

        assertThat(port.linesOf(connection(), manual.id().value())).isEmpty();
    }

    @Test
    @DisplayName("approve and reject reach its own door: the entry's id comes back, the date the one stored; a rejection decides the proposal")
    void approveAndReject() {
        UUID proposal = held(AdjustmentProposal.proposePriorPeriod(AdjustmentProposalId.next(IDS), DATE, "prior-period-adjustment:x", "identifiers only", "person-2", lines(), CLOCK));

        PriorPeriodPostings.Posted posted = as(() -> port.approve(connection(), proposal));

        assertThat(posted.replayed()).isFalse();
        assertThat(appended).singleElement().satisfies(entry -> {
            assertThat(entry.id().value()).isEqualTo(posted.entry());
            assertThat(entry.postingDate()).isEqualTo(DATE);
            assertThat(entry.valueDate()).isEqualTo(DATE);
        });

        UUID other = held(AdjustmentProposal.proposePriorPeriod(AdjustmentProposalId.next(IDS), DATE, "prior-period-adjustment:y", "identifiers only", "person-2", lines(), CLOCK));
        as(() -> {
            port.reject(connection(), other);
            return null;
        });
        assertThat(decisions).containsExactly(AdjustmentProposalStatus.APPROVED, AdjustmentProposalStatus.REJECTED);
    }

    @Test
    @DisplayName("RECONCILIATION_DISCLOSURE never reads an ACCOUNTING entry as floored or unrecorded: the read binds the posting door's scope, and an adjustment's is another; the accruals read names lending's and bnpl's only")
    void thePastTheCutoffReadDoesNotSeeIt() {
        List<Object> bound = new ArrayList<>();
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, call, args) -> call.getName().equals("prepareStatement")
                ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (statement, used, values) -> switch (used.getName()) {
                    case "setString" -> {
                        bound.add(values[1]);
                        yield null;
                    }
                    case "executeQuery" -> Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, (rows, asked, unused) -> asked.getName().equals("next") ? Boolean.FALSE : null);
                    default -> null;
                })
                : null);

        assertThat(new LedgerFlooredPostings().pastTheCutoff(connection, YearMonth.of(2026, 1).atEndOfMonth())).isEmpty();

        assertThat(bound).containsExactly("ledger.post:%");
        String scope = "ledger.adjust.approve:" + IDS.next();
        assertThat(scope).as("an adjustment's scope is not the posting door's").doesNotStartWith("ledger.post:");
        assertThat(LedgerFlooredPostings.PAST_THE_CUTOFF_SQL).contains("entry.idempotency_scope LIKE ?");
        assertThat(scope).doesNotStartWith(LedgerFlooredPostings.LENDING_SCOPE.replace("%", "")).doesNotStartWith(LedgerFlooredPostings.BNPL_SCOPE.replace("%", ""));
        assertThat(LedgerJournalShapes.namespace(com.finapp.accounting.EntryType.ADJUSTMENT, scope, "ACCOUNTING")).as("RULE_CONFORMANCE judges it by its origin").contains("ACCOUNTING");
    }

    @Test
    @DisplayName("the read for P14-TSK-016: a month's posted adjustments traced to what they correct; the reversals of entries dated before the month, by date, per namespace; an entry of the origin with no POSTED row counted, never assumed away")
    void theCorrectionsRead() {
        assertThat(LedgerPriorPeriodCorrections.ADJUSTMENTS_SQL).contains("a.status = 'POSTED'").contains("a.posting_date >= ? AND a.posting_date <= ?").contains("c.period_start AS corrected_start").contains("a.corrected_revision")
                .contains("ORDER BY a.posting_date, a.id").doesNotContain("reason").doesNotContain("proposed_by");
        assertThat(LedgerPriorPeriodCorrections.REVERSALS_SQL).contains("JOIN ledger.journal_entry original ON original.id = entry.reverses_entry_id")
                .contains("entry.posting_date >= ? AND entry.posting_date <= ? AND original.posting_date < ?").contains("GROUP BY 1, 2 ORDER BY 1, 2");
        assertThat(LedgerPriorPeriodCorrections.UNTRACED_SQL).contains("proposal.origin = 'ACCOUNTING' AND proposal.status = 'APPROVED'").contains("LEFT JOIN accounting.prior_period_adjustment adjustment")
                .contains("adjustment.status = 'POSTED'").contains("adjustment.id IS NULL");
        assertThat(new PriorPeriodCorrections.Corrections(List.of(), List.of(), 0).adjustments()).isEmpty();
    }

    // ------------------------------------------------------------------

    private UUID held(AdjustmentProposal proposal) {
        held.put(proposal.id(), proposal);
        return proposal.id().value();
    }

    private List<com.finapp.ledger.JournalLine> lines() {
        return List.of(new com.finapp.ledger.JournalLine(wallet.id(), com.finapp.ledger.Direction.DEBIT, Money.ofMinorUnits(1, EUR)), new com.finapp.ledger.JournalLine(com.finapp.ledger.LedgerAccountId.next(IDS), com.finapp.ledger.Direction.CREDIT, Money.ofMinorUnits(1, EUR)));
    }

    private static <T> T as(Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(PERSON);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    /** One connection answering the cutoff's two statements: the key's isolation, and a cutoff never set. */
    private Connection connection() {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, call, args) -> call.getName().equals("prepareStatement")
                ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (statement, used, values) -> used.getName().equals("executeQuery")
                        ? Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ResultSet.class}, (rows, asked, unused) -> switch (asked.getName()) {
                            case "next" -> Boolean.TRUE;
                            case "getString" -> "read committed";
                            default -> null;
                        })
                        : null)
                : null);
    }

    @SuppressWarnings("unchecked")
    private LedgerAccountStore<Connection> accounts() {
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, call, args) -> {
            if (call.getName().equals("findAllById")) {
                return List.of(wallet);
            }
            throw new AssertionError("LedgerAccountStore." + call.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private AdjustmentService service() {
        AdjustmentProposalStore<Connection> proposals = (AdjustmentProposalStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {AdjustmentProposalStore.class}, (proxy, call, args) -> switch (call.getName()) {
            case "insert" -> {
                AdjustmentProposal proposal = (AdjustmentProposal) args[1];
                held.put(proposal.id(), proposal);
                yield null;
            }
            case "findById", "lockById" -> Optional.ofNullable(held.get((AdjustmentProposalId) args[1]));
            case "decide" -> {
                decisions.add((AdjustmentProposalStatus) args[2]);
                yield true;
            }
            default -> throw new AssertionError("AdjustmentProposalStore." + call.getName());
        });
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class}, (proxy, call, args) -> {
            throw new AssertionError("an owned door carries no key: " + call.getName());
        });
        return new AdjustmentService(new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)), recording(JournalEntryStore.class, args -> {
            if (args[1] instanceof JournalEntry entry) {
                appended.add(entry);
            }
        }), proposals, recording(AuditWriter.class, args -> { }), recording(OutboxWriter.class, args -> { }), recording(BalanceProjection.class, args -> { }), IDS, CLOCK, PostingObserver.NONE, accounts());
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> type, java.util.function.Consumer<Object[]> record) {
        return (T) Proxy.newProxyInstance(LedgerPriorPeriodPostingsTest.class.getClassLoader(), new Class<?>[] {type}, (proxy, call, args) -> {
            record.accept(args);
            return null;
        });
    }
}
