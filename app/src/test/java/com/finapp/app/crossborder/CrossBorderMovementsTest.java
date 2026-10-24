package com.finapp.app.crossborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.crossborder.BeneficiaryId;
import com.finapp.crossborder.CorridorKey;
import com.finapp.crossborder.CorridorPolicyId;
import com.finapp.crossborder.CrossBorderInstruction;
import com.finapp.crossborder.OfferStore;
import com.finapp.fx.ConversionParticipants;
import com.finapp.fx.QuoteStore;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cross-border movement's facts and their locks (`P13-TSK-011`; {@code PHASE_13_PLAN.md} section 7.2,
 * {@code DISTRIBUTED_EXECUTION.md} §3's Phase 9 and Phase 13 lock orders): the seam's read takes the money rows the
 * authorization will take - the quote {@code FOR UPDATE}, <em>then</em> the source wallet {@code FOR UPDATE}, Phase 9's
 * order (3) before (4) - before anything is returned to T-eval, so risk's rows (R2a, held to commit) come after every
 * money row the transaction ever takes; the wallet locked is the one fx's acceptance debits (the same port, the same
 * calls); the door's read takes no lock at all; and neither ever touches the connection itself.
 */
@DisplayName("the cross-border movement's facts: the quote and the wallet locked in Phase 9's order before T-eval;"
        + " the door's read lock-free (P13-TSK-011)")
class CrossBorderMovementsTest {

    private static final Clock CLOCK = RiskEvaluationWorld.CLOCK;
    private static final IdGenerator IDS = RiskEvaluationWorld.IDS;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CorridorKey EUR_USD_US = CorridorKey.parse("EUR-USD-US");

    private final List<String> calls = new ArrayList<>();
    private final UUID party = IDS.next();
    private final UUID customer = IDS.next();
    private final UUID quote = IDS.next();
    private final CustomerAccount product = CustomerAccount.open(IDS, CLOCK, customer, ProductType.WALLET);
    private final LedgerAccount wallet = LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
            AccountPurpose.CUSTOMER_WALLET, EUR, product.id().value());
    private final OfferStore.OfferRow offer = new OfferStore.OfferRow(IDS.next(), IDS.next(), quote, party,
            BeneficiaryId.of(IDS.next()), CorridorPolicyId.of(IDS.next()), EUR_USD_US, Money.ofMinorUnits(2_50, EUR),
            Money.ofMinorUnits(600_00, EUR), Money.ofMinorUnits(602_50, EUR),
            Money.ofMinorUnits(650_00, CurrencyCode.of("USD")), 24, CLOCK.instant());
    private boolean hasWallet = true;

    private final CrossBorderMovements movements = CrossBorderMovements.over(offers(), quotes(), participants(),
            ledgerAccounts(), products());

    @Test
    @DisplayName("the seam's read: the quote FOR UPDATE, then the wallet fx resolves FOR UPDATE, then the frozen offer -"
            + " the movement's customer account the locked row's owner_ref, its figures the quote's")
    void theSeamLocksTheQuoteThenTheWallet() {
        CrossBorderMovements.Movement movement = movements.locked(RiskEvaluationWorld.UNTOUCHABLE, instruction());
        assertThat(calls).containsExactly("quote FOR UPDATE", "activeCustomer", "wallet EUR", "wallet FOR UPDATE",
                "offer");
        assertThat(movement.sourceAccount()).contains(product.id().value());
        assertThat(movement.beneficiary()).isEqualTo(offer.beneficiary().value());
        assertThat(movement.source()).as("the quote's source, not the total debit").isEqualTo(offer.source());
        assertThat(movement.destination()).isEqualTo(offer.destination());
        assertThat(movement.quote()).isEqualTo(quote);
        assertThat(movement.party()).isEqualTo(party);
        assertThat(movement.toString()).doesNotContain(party.toString()).doesNotContain("600");
    }

    @Test
    @DisplayName("no wallet in the source currency: the quote locked, no wallet locked, no source account - the"
            + " acceptance refuses it after the seams")
    void noWalletIsNoLockAndNoSource() {
        hasWallet = false;
        CrossBorderMovements.Movement movement = movements.locked(RiskEvaluationWorld.UNTOUCHABLE, instruction());
        assertThat(calls).containsExactly("quote FOR UPDATE", "activeCustomer", "wallet EUR", "offer");
        assertThat(movement.sourceAccount()).isEmpty();
    }

    @Test
    @DisplayName("an offer gone from under the authorization's own read is an invariant broken, loudly")
    void aMissingOfferIsLoud() {
        CrossBorderInstruction another = new CrossBorderInstruction(party, IDS.next(), EUR_USD_US,
                Money.ofMinorUnits(602_50, EUR));
        assertThatThrownBy(() -> movements.locked(RiskEvaluationWorld.UNTOUCHABLE, another))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("the door's read takes no lock: the caller's offer, and the live WALLET product as the source only when"
            + " a wallet in the source currency exists; another party's quote is empty")
    void theDoorsReadTakesNoLock() {
        CrossBorderMovements.Movement movement = movements.owned(RiskEvaluationWorld.UNTOUCHABLE, party, quote)
                .orElseThrow();
        assertThat(calls).noneMatch(call -> call.contains("FOR UPDATE"));
        assertThat(movement.sourceAccount()).contains(product.id().value());
        assertThat(movements.owned(RiskEvaluationWorld.UNTOUCHABLE, IDS.next(), quote)).isEmpty();
        hasWallet = false;
        assertThat(movements.owned(RiskEvaluationWorld.UNTOUCHABLE, party, quote).orElseThrow().sourceAccount())
                .isEmpty();
    }

    private CrossBorderInstruction instruction() {
        return new CrossBorderInstruction(party, quote, EUR_USD_US, offer.totalDebit());
    }

    // ------------------------------------------------------------------ the recording collaborators

    private OfferStore offers() {
        return proxy(OfferStore.class, (name, args) -> {
            if (name.equals("offerOwned")) {
                calls.add("offer");
                return Optional.of(offer).filter(o -> o.quoteId().equals(args[1]) && o.owner().equals(args[2]));
            }
            throw new AssertionError("the movement reads the offer only: " + name);
        });
    }

    private QuoteStore quotes() {
        return proxy(QuoteStore.class, (name, args) -> {
            if (name.equals("lockOwned")) {
                calls.add("quote FOR UPDATE");
                return Optional.empty();
            }
            throw new AssertionError("the movement locks the quote only: " + name);
        });
    }

    private ConversionParticipants participants() {
        return proxy(ConversionParticipants.class, (name, args) -> switch (name) {
            case "activeCustomer" -> {
                calls.add("activeCustomer");
                yield args[1].equals(party) ? Optional.of(customer) : Optional.empty();
            }
            case "wallet" -> {
                calls.add("wallet " + ((CurrencyCode) args[2]).code());
                yield hasWallet ? Optional.of(wallet.id()) : Optional.empty();
            }
            default -> throw new AssertionError("the movement never opens a wallet: " + name);
        });
    }

    @SuppressWarnings("unchecked")
    private LedgerAccountStore<Connection> ledgerAccounts() {
        return proxy(LedgerAccountStore.class, (name, args) -> {
            if (name.equals("lockForUpdate") && args[1].equals(wallet.id())) {
                calls.add("wallet FOR UPDATE");
                return Optional.of(wallet);
            }
            throw new AssertionError("the movement locks the wallet only: " + name);
        });
    }

    @SuppressWarnings("unchecked")
    private CustomerAccountStore<Connection> products() {
        return proxy(CustomerAccountStore.class, (name, args) -> {
            if (name.equals("findLive") && args[1].equals(customer) && args[2] == ProductType.WALLET) {
                calls.add("product");
                return Optional.of(product);
            }
            throw new AssertionError("the door reads the live WALLET product only: " + name);
        });
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String name, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<?> type, Answer answer) {
        return (T) Proxy.newProxyInstance(CrossBorderMovementsTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> switch (method.getName()) {
                    case "toString" -> type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    default -> answer.answer(method.getName(), args);
                });
    }
}
