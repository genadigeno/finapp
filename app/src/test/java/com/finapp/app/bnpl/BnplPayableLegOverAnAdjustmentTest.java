package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.ExposureCensus;
import com.finapp.bnpl.BnplProofReads;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.StatementDerivation;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LoanStore;
import com.finapp.merchant.MerchantPayable;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code BNPL_THREE_WAY}'s merchant leg beside a prior-period adjustment's lines (`P14-TSK-013`): {@link LedgerBnplProofReads#payableLeg} reads {@code MERCHANT_PAYABLE}, a purpose open to the adjustment, and holds because it compares only {@code bnpl.}-keyed lines and such a line
 * carries no key. The real leg and the real drill-down, the journal's two reads stood in; that the real statement gives such a line no key is {@code PriorPeriodAdjustmentDatabaseTest#whatAStatementShowsOfIt}, owed.
 */
@DisplayName("BNPL_THREE_WAY's merchant leg beside a prior-period adjustment (P14-TSK-013)")
class BnplPayableLegOverAnAdjustmentTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate ON = LocalDate.of(2026, 3, 10);
    private static final UUID MERCHANT = IDS.next();
    private static final LedgerAccount PAYABLE = LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE, EUR, MERCHANT);
    private static final Connection SNAPSHOT = unused(Connection.class);
    private static final String FINANCING = MerchantPayable.BNPL_FINANCING_NAMESPACE;

    @SuppressWarnings("unchecked")
    private static <T> T unused(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.getName().equals("toString") ? type.getSimpleName() : method.getName().equals("hashCode") ? (Object) 0 : (Object) (proxy == args[0]);
            }
            throw new AssertionError("the merchant leg does not read " + type.getSimpleName() + "." + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static LedgerAccountStore<Connection> accounts() {
        return (LedgerAccountStore<Connection>) Proxy.newProxyInstance(LedgerAccountStore.class.getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, method, args) -> switch (method.getName()) {
            case "findAllOwned" -> List.of(PAYABLE);
            case "findOwned" -> Optional.of(PAYABLE);
            default -> throw new AssertionError("the merchant leg reads the owner's accounts and nothing else: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static StatementDerivation<Connection> statements(List<StatementDerivation.StatementLine> lines) {
        return (StatementDerivation<Connection>) Proxy.newProxyInstance(StatementDerivation.class.getClassLoader(), new Class<?>[] {StatementDerivation.class}, (proxy, method, args) -> {
            if (method.getName().equals("statementsFor") && args.length == 5) {
                return List.of(new StatementDerivation.AccountStatement(PAYABLE.id(), eur(0), lines, eur(0)));
            }
            throw new AssertionError("the merchant leg reads one statement: " + method.getName());
        });
    }

    @SuppressWarnings("unchecked")
    private static Optional<BnplProofReads.PayableLeg> leg(List<PositionBreakdown.Bucket> buckets, List<StatementDerivation.StatementLine> lines) {
        PositionBreakdown<Connection> breakdown = (unitOfWork, account, counterparties, namespaces) -> {
            Money position = eur(0);
            for (PositionBreakdown.Bucket bucket : buckets) {
                position = bucket.direction() == Direction.CREDIT ? position.plus(bucket.total()) : position.minus(bucket.total());
            }
            return new PositionBreakdown.Breakdown(account, position, buckets);
        };
        LendingCapitalProof capital = new LendingCapitalProof(new LendingCapitalHeadroom(accounts(), unused(PurposeBalances.class), unused(LoanStore.class)), unused(PurposeBalances.class), unused(CapitalRecognitions.class));
        return new LedgerBnplProofReads(accounts(), new MerchantPayable(accounts(), breakdown), statements(lines), new TrialBalance(), capital, new ExposureCensus()).payableLeg(SNAPSHOT, MERCHANT, EUR);
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    private static PositionBreakdown.Bucket keyed(Direction side, long minor, String namespace) {
        return new PositionBreakdown.Bucket(side, Optional.empty(), eur(minor), false, Optional.of(namespace));
    }

    /** The adjustment's line in the breakdown, by the ledger's own rule: its origin read first, so no key and no counterparty. */
    private static PositionBreakdown.Bucket adjustmentFacingAWallet(Direction side, long minor) {
        Direction onTheWallet = side == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
        return PositionBreakdown.Bucket.of(side, eur(minor), Optional.of(com.finapp.ledger.AdjustmentOrigin.ACCOUNTING), Optional.of(new PositionBreakdown.Counterparty(AccountPurpose.CUSTOMER_WALLET, onTheWallet)), Optional.empty());
    }

    private static StatementDerivation.StatementLine line(Direction side, long minor, Optional<String> namespace) {
        return new StatementDerivation.StatementLine(JournalEntryId.next(IDS), ON, ON, namespace.isPresent() ? JournalEntryType.POSTING : JournalEntryType.ADJUSTMENT, "reference", side, eur(minor), false, namespace);
    }

    @Test
    @DisplayName("an adjustment's lines beside a financing: the equation holds, and they are not among the lines compared - they carry no key")
    void theLegHoldsAndDoesNotCompareThem() {
        Optional<BnplProofReads.PayableLeg> found = leg(
                List.of(keyed(Direction.CREDIT, 60_000, FINANCING), keyed(Direction.DEBIT, 2_400, FINANCING), adjustmentFacingAWallet(Direction.CREDIT, 1_500), adjustmentFacingAWallet(Direction.DEBIT, 700)),
                List.of(line(Direction.CREDIT, 60_000, Optional.of(FINANCING)), line(Direction.DEBIT, 2_400, Optional.of(FINANCING)), line(Direction.CREDIT, 1_500, Optional.empty()), line(Direction.DEBIT, 700, Optional.empty())));

        assertThat(found).hasValueSatisfying(leg -> {
            assertThat(leg.equationHolds()).isTrue();
            assertThat(leg.lines()).hasSize(2).allSatisfy(compared -> assertThat(compared.namespace()).isEqualTo(FINANCING));
        });
    }

    @Test
    @DisplayName("and the equation can fail: the same line carrying a key the drill-down's terms do not hold is compared, and refused - so the holding above is the missing key's doing")
    void aKeyedLineTheTermsDoNotHoldBreaksIt() {
        Optional<BnplProofReads.PayableLeg> found = leg(
                List.of(keyed(Direction.CREDIT, 60_000, FINANCING), keyed(Direction.DEBIT, 2_400, FINANCING), adjustmentFacingAWallet(Direction.CREDIT, 1_500)),
                List.of(line(Direction.CREDIT, 60_000, Optional.of(FINANCING)), line(Direction.DEBIT, 2_400, Optional.of(FINANCING)), line(Direction.CREDIT, 1_500, Optional.of(FINANCING))));

        assertThat(found).hasValueSatisfying(leg -> assertThat(leg.equationHolds()).isFalse());
    }
}
