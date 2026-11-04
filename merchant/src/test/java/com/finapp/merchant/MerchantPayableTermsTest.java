package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentOrigin;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PositionBreakdown;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link MerchantPayable}'s real term logic under an adjustment's line (`P14-TSK-013`, `P14-TSK-022`; {@code PHASE_14_PLAN.md} section 13.5; {@code MERCHANT_PAYABLE} being one of the five purposes open to it). Since `P14-TSK-022` an {@code ACCOUNTING} line is classified by its origin, through the ledger's own {@link PositionBreakdown.Bucket#of}; a {@code MANUAL} one still reads by what it faces. Nothing classifies such a line: it carries no key and is not an
 * attribution, so it falls to whichever term its counterparty names. Whatever it reads as, the terms must still sum to the position, or {@code ProductSourceProofs}' {@code MERCHANT_PAYABLE} proof fails and the month it lands in cannot close.
 *
 * <p>The breakdown is stood in, one bucket for each shape {@code JdbcPositionBreakdown} can produce; the folding of real lines into buckets is {@code PriorPeriodAdjustmentDatabaseTest}'s, owed.
 */
@DisplayName("an adjustment's line on a merchant payable: the terms sum to the position (P14-TSK-013, P14-TSK-022)")
class MerchantPayableTermsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final UUID MERCHANT = IDS.next();
    private static final LedgerAccount PAYABLE = LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE, EUR, MERCHANT);

    @SuppressWarnings("unchecked")
    private static final LedgerAccountStore<Connection> ACCOUNTS = (LedgerAccountStore<Connection>) Proxy.newProxyInstance(LedgerAccountStore.class.getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, method, args) -> {
        if (method.getName().equals("findAllOwned")) {
            return List.of(PAYABLE);
        }
        throw new AssertionError("the drill-down reads the owner's accounts and nothing else: " + method.getName());
    });

    private static MerchantPayable.Payable payableOf(List<PositionBreakdown.Bucket> buckets) {
        PositionBreakdown<Connection> breakdown = (unitOfWork, account, counterparties, namespaces) -> {
            Money position = Money.ofMinorUnits(0L, EUR);
            for (PositionBreakdown.Bucket bucket : buckets) {
                position = bucket.direction() == Direction.CREDIT ? position.plus(bucket.total()) : position.minus(bucket.total());
            }
            return new PositionBreakdown.Breakdown(account, position, buckets);
        };
        return new MerchantPayable(ACCOUNTS, breakdown).payablesOf(UOW, MerchantId.of(MERCHANT)).get(0);
    }

    /** A line on the payable facing a purpose, on the side an adjustment of two lines leaves it: the counterparty on the other. */
    private static PositionBreakdown.Bucket facing(AccountPurpose purpose, Direction onThePayable, long minor) {
        Direction onTheCounterparty = onThePayable == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
        return new PositionBreakdown.Bucket(onThePayable, Optional.of(new PositionBreakdown.Counterparty(purpose, onTheCounterparty)), Money.ofMinorUnits(minor, EUR), false, Optional.empty());
    }

    /** A line with no counterparty the drill-down asks about: the other side's purpose is none of {@code COUNTERPARTIES}, or the other side is this very payable. */
    private static PositionBreakdown.Bucket facingNothing(Direction onThePayable, long minor) {
        return new PositionBreakdown.Bucket(onThePayable, Optional.empty(), Money.ofMinorUnits(minor, EUR), false, Optional.empty());
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** One line of an origin's proposal, through the ledger's own origin rule, facing {@code purpose}; the counterparty stands on the other side. */
    private static PositionBreakdown.Bucket ofOrigin(AdjustmentOrigin origin, AccountPurpose purpose, Direction onThePayable, long minor) {
        Direction onTheCounterparty = onThePayable == Direction.CREDIT ? Direction.DEBIT : Direction.CREDIT;
        return PositionBreakdown.Bucket.of(onThePayable, Money.ofMinorUnits(minor, EUR), Optional.of(origin), Optional.of(new PositionBreakdown.Counterparty(purpose, onTheCounterparty)), Optional.empty());
    }

    /** Every term but {@code accountingCorrections} and the position. */
    private static List<Money> everyOtherTerm(MerchantPayable.Payable payable) {
        return List.of(payable.captured(), payable.fees(), payable.refunded(), payable.feesReturned(), payable.paidOut(), payable.payoutsReturned(), payable.chargedBack(), payable.chargebacksReversed(), payable.reconciliationAttributed(),
                payable.bnplFinanced(), payable.bnplFees(), payable.bnplRefunded(), payable.bnplFeesReturned(), payable.other());
    }

    @Test
    @DisplayName("P14-TSK-022 (q): an ACCOUNTING line facing every counterparty the drill-down names, on either side, reads accountingCorrections, signed as the liability reads it, and no other term: no false captured, refunded or chargedBack, and the terms sum")
    void anAccountingLineFacingEveryCounterparty() {
        for (AccountPurpose purpose : MerchantPayable.COUNTERPARTIES) {
            for (Direction side : Direction.values()) {
                MerchantPayable.Payable payable = payableOf(List.of(ofOrigin(AdjustmentOrigin.ACCOUNTING, purpose, side, 1_500)));

                assertThat(payable.accountingCorrections()).as(purpose + " " + side).isEqualTo(side == Direction.CREDIT ? eur(1_500) : eur(-1_500));
                assertThat(everyOtherTerm(payable)).as(purpose + " " + side).allSatisfy(term -> assertThat(term).isEqualTo(eur(0)));
                assertThat(payable.terms()).as(purpose + " " + side).isEqualTo(payable.position());
                assertThat(payable.position()).as(purpose + " " + side).isEqualTo(side == Direction.CREDIT ? eur(1_500) : eur(-1_500));
            }
        }
    }

    @Test
    @DisplayName("P14-TSK-022: the origin rule is one statement: ACCOUNTING and RECONCILIATION drop what they face and any key; MANUAL, and an entry no proposal posted, keep both; a line can never be both origins")
    void theOriginRule() {
        PositionBreakdown.Counterparty wallet = new PositionBreakdown.Counterparty(AccountPurpose.CUSTOMER_WALLET, Direction.DEBIT);
        Optional<String> key = Optional.of(MerchantPayable.BNPL_REFUND_NAMESPACE);
        PositionBreakdown.Bucket accounting = PositionBreakdown.Bucket.of(Direction.CREDIT, eur(1), Optional.of(AdjustmentOrigin.ACCOUNTING), Optional.of(wallet), key);
        assertThat(accounting.accountingOrigin()).isTrue();
        assertThat(accounting.reconciliationAttributed()).isFalse();
        assertThat(accounting.counterparty()).isEmpty();
        assertThat(accounting.namespace()).isEmpty();
        PositionBreakdown.Bucket attributed = PositionBreakdown.Bucket.of(Direction.CREDIT, eur(1), Optional.of(AdjustmentOrigin.RECONCILIATION), Optional.of(wallet), key);
        assertThat(attributed.reconciliationAttributed()).isTrue();
        assertThat(attributed.accountingOrigin()).isFalse();
        assertThat(attributed.counterparty()).isEmpty();
        for (Optional<AdjustmentOrigin> keeps : List.of(Optional.of(AdjustmentOrigin.MANUAL), Optional.<AdjustmentOrigin>empty())) {
            PositionBreakdown.Bucket kept = PositionBreakdown.Bucket.of(Direction.CREDIT, eur(1), keeps, Optional.of(wallet), key);
            assertThat(kept.accountingOrigin()).isFalse();
            assertThat(kept.reconciliationAttributed()).isFalse();
            assertThat(kept.counterparty()).contains(wallet);
            assertThat(kept.namespace()).isEqualTo(key);
        }
        assertThatThrownBy(() -> new PositionBreakdown.Bucket(Direction.CREDIT, Optional.of(wallet), eur(1), false, Optional.empty(), true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PositionBreakdown.Bucket(Direction.CREDIT, Optional.empty(), eur(1), true, Optional.empty(), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("P14-TSK-022's review, M-3: an origin this build does not know reads as none: facing a wallet, by what it faces; the terms still sum, so an old instance's response and proofs go on answering under a rolling deploy")
    void anOriginThisBuildDoesNotKnow() {
        PositionBreakdown.Bucket later = PositionBreakdown.Bucket.of(Direction.CREDIT, eur(1_500), AdjustmentOrigin.named("A_LATER_RELEASES_ORIGIN"), Optional.of(new PositionBreakdown.Counterparty(AccountPurpose.CUSTOMER_WALLET, Direction.DEBIT)), Optional.empty());

        MerchantPayable.Payable payable = payableOf(List.of(later));

        assertThat(payable.captured()).isEqualTo(eur(1_500));
        assertThat(payable.accountingCorrections()).isEqualTo(eur(0));
        assertThat(payable.terms()).isEqualTo(payable.position());
    }

    @Test
    @DisplayName("P14-TSK-022: only ACCOUNTING moved: a MANUAL line facing a wallet still reads by its counterparty, as no ruling changed; a RECONCILIATION one is an attribution; the terms sum for each")
    void onlyAccountingMoved() {
        MerchantPayable.Payable manual = payableOf(List.of(ofOrigin(AdjustmentOrigin.MANUAL, AccountPurpose.CUSTOMER_WALLET, Direction.CREDIT, 1_500)));
        assertThat(manual.captured()).isEqualTo(eur(1_500));
        assertThat(manual.accountingCorrections()).isEqualTo(eur(0));
        assertThat(manual.terms()).isEqualTo(manual.position());
        MerchantPayable.Payable attributed = payableOf(List.of(ofOrigin(AdjustmentOrigin.RECONCILIATION, AccountPurpose.CUSTOMER_WALLET, Direction.DEBIT, 1_500)));
        assertThat(attributed.reconciliationAttributed()).isEqualTo(eur(-1_500));
        assertThat(attributed.accountingCorrections()).isEqualTo(eur(0));
        assertThat(attributed.terms()).isEqualTo(attributed.position());
    }

    @Test
    @DisplayName("every counterparty the drill-down names, on either side of the payable: the terms sum to the position")
    void facingEveryCounterparty() {
        for (AccountPurpose purpose : MerchantPayable.COUNTERPARTIES) {
            for (Direction side : Direction.values()) {
                MerchantPayable.Payable payable = payableOf(List.of(facing(purpose, side, 1_500)));

                assertThat(payable.terms()).as(purpose + " " + side).isEqualTo(payable.position());
                assertThat(payable.position()).as(purpose + " " + side).isEqualTo(side == Direction.CREDIT ? eur(1_500) : eur(-1_500));
            }
        }
    }

    @Test
    @DisplayName("a line of no ACCOUNTING origin, a MANUAL one say, reads by what it faces: facing a customer's wallet a credit is a captured sale and a debit a refund; facing a fee account, which no term names, other")
    void whatAMerchantIsShown() {
        assertThat(payableOf(List.of(facing(AccountPurpose.CUSTOMER_WALLET, Direction.CREDIT, 1_500))).captured()).isEqualTo(eur(1_500));
        assertThat(payableOf(List.of(facing(AccountPurpose.CUSTOMER_WALLET, Direction.DEBIT, 1_500))).refunded()).isEqualTo(eur(1_500));
        MerchantPayable.Payable facingFees = payableOf(List.of(facingNothing(Direction.DEBIT, 1_500)));
        assertThat(facingFees.other()).as("FEE_REVENUE is no counterparty, so never 'fees'").isEqualTo(eur(-1_500));
        assertThat(facingFees.fees()).isEqualTo(eur(0));
    }

    @Test
    @DisplayName("a line facing nothing it names, and both lines on the one payable: 'other', and the terms still sum")
    void facingNothingAndTheSameAccount() {
        MerchantPayable.Payable onlyCredit = payableOf(List.of(facingNothing(Direction.CREDIT, 700)));
        assertThat(onlyCredit.other()).isEqualTo(eur(700));
        assertThat(onlyCredit.terms()).isEqualTo(onlyCredit.position());

        MerchantPayable.Payable sameAccount = payableOf(List.of(facingNothing(Direction.CREDIT, 900), facingNothing(Direction.DEBIT, 900)));
        assertThat(sameAccount.position()).isEqualTo(eur(0));
        assertThat(sameAccount.terms()).isEqualTo(sameAccount.position());
    }

    @Test
    @DisplayName("beside a merchant's real lines, on every counterparty together: the terms still sum, and the identity would fail if a term dropped a side")
    void besideEverythingElse() {
        List<PositionBreakdown.Bucket> buckets = new ArrayList<>();
        buckets.add(new PositionBreakdown.Bucket(Direction.CREDIT, Optional.empty(), eur(60_000), false, Optional.of(MerchantPayable.BNPL_FINANCING_NAMESPACE)));
        buckets.add(new PositionBreakdown.Bucket(Direction.DEBIT, Optional.empty(), eur(2_400), false, Optional.of(MerchantPayable.BNPL_FINANCING_NAMESPACE)));
        buckets.add(new PositionBreakdown.Bucket(Direction.DEBIT, Optional.empty(), eur(1_000), false, Optional.of(MerchantPayable.BNPL_REFUND_NAMESPACE)));
        buckets.add(new PositionBreakdown.Bucket(Direction.CREDIT, Optional.empty(), eur(40), false, Optional.of(MerchantPayable.BNPL_REFUND_NAMESPACE)));
        buckets.add(new PositionBreakdown.Bucket(Direction.CREDIT, Optional.empty(), eur(33), true, Optional.empty()));
        long minor = 11;
        for (AccountPurpose purpose : MerchantPayable.COUNTERPARTIES) {
            buckets.add(facing(purpose, Direction.CREDIT, minor));
            buckets.add(facing(purpose, Direction.DEBIT, minor + 3));
            minor += 7;
        }
        buckets.add(facingNothing(Direction.CREDIT, 5));
        buckets.add(facingNothing(Direction.DEBIT, 2));
        buckets.add(ofOrigin(AdjustmentOrigin.ACCOUNTING, AccountPurpose.CUSTOMER_WALLET, Direction.CREDIT, 19));
        buckets.add(ofOrigin(AdjustmentOrigin.ACCOUNTING, AccountPurpose.DISPUTE_COSTS, Direction.DEBIT, 3));

        MerchantPayable.Payable payable = payableOf(buckets);

        assertThat(payable.accountingCorrections()).isEqualTo(eur(16));
        assertThat(payable.terms()).isEqualTo(payable.position());
    }
}
