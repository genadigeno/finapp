package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LoanServicingStore.LineAwaitingAccrual;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lending's accrued-through per currency (`P14-TSK-006`; owner decision C3 - "the close requires accruals caught up"; the
 * lending arm of accounting's {@code AccrualCompleteness}) without a database: the instalment arm's oldest unaccrued date
 * per currency merged with each lagging credit line's first unaccrued date with principal outstanding - the earlier
 * winning; a line paid down to zero is not lagging. The SQL of both arms is {@code LoanAccrualCutoffDatabaseTest}'s
 * (owed, C5).
 */
@DisplayName("lending's oldest unaccrued date per currency - the instalment and line arms merged (P14-TSK-006, C3)")
class AccrualLagsTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    /** Each principal account the ledger fake opened, and the loan that owns it. */
    private final Map<LedgerAccountId, UUID> owners = new ConcurrentHashMap<>();

    @Test
    @DisplayName("the instalment arm per currency, a lagging line earlier in its currency wins, a later one does not, a"
            + " line in a new currency adds it, and a line at zero drawn is not lagging")
    void theTwoArmsMergeTheEarlierWinning() {
        LoanId earlyEur = LoanId.of(IDS.next());
        LoanId lateUsd = LoanId.of(IDS.next());
        LoanId gbpLine = LoanId.of(IDS.next());
        LoanId zeroLine = LoanId.of(IDS.next());
        AccrualLags lags = new AccrualLags(
                store(Map.of(EUR, LocalDate.of(2026, 10, 30), USD, LocalDate.of(2026, 10, 25)), List.of(
                        line(earlyEur, EUR, LocalDate.of(2026, 10, 28)),
                        line(lateUsd, USD, LocalDate.of(2026, 11, 1)),
                        line(gbpLine, GBP, LocalDate.of(2026, 11, 3)),
                        line(zeroLine, EUR, LocalDate.of(2026, 10, 1)))),
                accounts(),
                balances(Map.of(earlyEur.value(), 100_00L, lateUsd.value(), 50_00L, gbpLine.value(), 10_00L),
                        LocalDate.of(2026, 9, 1)));

        assertThat(lags.oldestUnaccruedByCurrency(unused())).containsExactly(
                Map.entry(EUR, LocalDate.of(2026, 10, 28)),
                Map.entry(GBP, LocalDate.of(2026, 11, 3)),
                Map.entry(USD, LocalDate.of(2026, 10, 25)));
    }

    @Test
    @DisplayName("a line drawn only after its next accrual date lags from the date its principal was first outstanding;"
            + " caught up, nothing")
    void aLineLagsFromItsFirstOutstandingDate() {
        LoanId line = LoanId.of(IDS.next());
        AccrualLags lags = new AccrualLags(store(Map.of(), List.of(line(line, EUR, LocalDate.of(2026, 10, 20)))),
                accounts(), balances(Map.of(line.value(), 75_00L), LocalDate.of(2026, 10, 24)));
        assertThat(lags.oldestUnaccruedByCurrency(unused())).containsExactly(
                Map.entry(EUR, LocalDate.of(2026, 10, 24)));
        assertThat(new AccrualLags(store(Map.of(), List.of()), accounts(), balances(Map.of(), TODAY))
                .oldestUnaccruedByCurrency(unused())).isEmpty();
    }

    // ------------------------------------------------------------------

    private static LineAwaitingAccrual line(LoanId loan, CurrencyCode currency, LocalDate next) {
        return new LineAwaitingAccrual(loan, currency, next, TODAY, "UTC");
    }

    private static LoanServicingStore store(Map<CurrencyCode, LocalDate> instalments, List<LineAwaitingAccrual> lines) {
        return (LoanServicingStore) Proxy.newProxyInstance(AccrualLagsTest.class.getClassLoader(),
                new Class<?>[] {LoanServicingStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "oldestUnaccruedByCurrency" -> instalments;
                    case "linesAwaitingAccrual" -> lines;
                    default -> throw new AssertionError("LoanServicingStore." + method.getName());
                });
    }

    /** Every owned account exists, owned by the loan asked about - remembered so the journal fake can find its lines. */
    @SuppressWarnings("unchecked")
    private LedgerAccountStore<Connection> accounts() {
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(AccrualLagsTest.class.getClassLoader(),
                new Class<?>[] {LedgerAccountStore.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("findOwned")) {
                        throw new AssertionError("LedgerAccountStore." + method.getName());
                    }
                    LedgerAccount account = LedgerAccount.owned(IDS, CLOCK, AccountType.ASSET,
                            (AccountPurpose) args[2], (CurrencyCode) args[3], (UUID) args[1]);
                    owners.put(account.id(), (UUID) args[1]);
                    return Optional.of(account);
                });
    }

    /** One principal line per drawn loan, valued {@code drawnOn}; a loan not drawn (or at zero) has none. */
    @SuppressWarnings("unchecked")
    private PurposeBalances<Connection> balances(Map<UUID, Long> drawn, LocalDate drawnOn) {
        return (PurposeBalances<Connection>) Proxy.newProxyInstance(AccrualLagsTest.class.getClassLoader(),
                new Class<?>[] {PurposeBalances.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("datedLinesOn")) {
                        throw new AssertionError("PurposeBalances." + method.getName());
                    }
                    Set<LedgerAccountId> asked = (Set<LedgerAccountId>) args[1];
                    LedgerAccountId principal = asked.iterator().next();
                    long minor = drawn.getOrDefault(owners.get(principal), 0L);
                    return minor == 0 ? List.of() : List.of(new PurposeBalances.DatedLine(JournalEntryId.next(IDS),
                            principal, drawnOn, Direction.DEBIT, Money.ofMinorUnits(minor, EUR)));
                });
    }

    private static Connection unused() {
        return (Connection) Proxy.newProxyInstance(AccrualLagsTest.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    throw new AssertionError("the reads are the store's and the ledger's: Connection." + method.getName());
                });
    }
}
