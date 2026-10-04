package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounts.WalletAccounts;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.sql.Connection;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every wallet resolver is keyed by currency (`P9-TSK-004`, ADR-0076 §6): <strong>no production
 * code picks a wallet with {@code findFirst()}</strong>.
 *
 * <p>Until this task each resolver read a product's owned accounts and took the first
 * {@code CUSTOMER_WALLET} — correct only while a product held one wallet. With n wallets per
 * product the pick is arbitrary in principle (and, in practice, the lowest id, so every flow in
 * a second currency resolved the first). The one resolution rule is
 * {@link WalletAccounts#resolve} — the wallet in the asked currency, else the first-opened by a
 * stated order — and this rule keeps the defect from coming back.
 *
 * <h2>What the rule sees</h2>
 *
 * <p>Bytecode, per code unit: a method or constructor that reads a product's owned accounts
 * ({@code LedgerAccountStore.findAllOwned} or {@code lockOwnedForUpdate}) or names
 * {@code AccountPurpose.CUSTOMER_WALLET} must not call {@code Stream.findFirst()} or
 * {@code Stream.findAny()}. A lambda's body is its own synthetic method, which is why the read
 * of the owned accounts — in the enclosing method, where the stream's terminal call also sits —
 * is the anchor. <strong>Stated limit</strong>: a pick split across two methods (one returns the
 * list, another takes its first) is not seen; the review question that leaves is "why is this
 * not {@code WalletAccounts.resolve}?".
 */
@Tag("architecture")
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("wallets are resolved by currency, never by findFirst() (P9-TSK-004)")
class WalletsAreResolvedByCurrencyTest {

    private static final String STORE = LedgerAccountStore.class.getName();
    private static final Set<String> OWNED_READS = Set.of("findAllOwned", "lockOwnedForUpdate");
    private static final Set<String> PICKS = Set.of("findFirst", "findAny");

    @ArchTest
    static final ArchRule noWalletIsPickedByFindFirst =
            classes()
                    .should(pickNoWalletByFindFirst())
                    .because(
                            "a product holds one wallet per currency (ADR-0076 section 6), so the"
                                + " first wallet of an owned-account read is an arbitrary one;"
                                + " resolve by currency through WalletAccounts.resolve");

    /** Non-vacuity: the rule's anchor really occurs in production, at the resolvers. */
    @ArchTest
    static void theAnchorIsReal(JavaClasses imported) {
        Set<String> readers = new TreeSet<>();
        for (JavaClass javaClass : imported) {
            for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                if (readsOwnedAccounts(unit)) {
                    readers.add(javaClass.getSimpleName());
                }
            }
        }
        assertThat(readers)
                .as("the resolvers read owned accounts, so the rule has something to judge")
                .contains("JdbcTransferParticipants", "JdbcPaymentParticipants");
    }

    @Test
    @DisplayName("a planted first-wallet pick is refused, and the keyed resolution is accepted")
    void aPlantedPickIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedFirstWallet.class);
        assertThatThrownBy(() -> noWalletIsPickedByFindFirst.check(planted))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(PlantedFirstWallet.class.getSimpleName());

        JavaClasses namedPurpose =
                new ClassFileImporter().importClasses(PlantedPurposePick.class);
        assertThatThrownBy(() -> noWalletIsPickedByFindFirst.check(namedPurpose))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(PlantedPurposePick.class.getSimpleName());

        JavaClasses keyed = new ClassFileImporter().importClasses(KeyedResolution.class);
        assertThatCode(() -> noWalletIsPickedByFindFirst.check(keyed))
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------

    private static ArchCondition<JavaClass> pickNoWalletByFindFirst() {
        return new ArchCondition<>("pick no wallet by findFirst()/findAny()") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                    if ((readsOwnedAccounts(unit) || namesTheWalletPurpose(unit)) && picks(unit)) {
                        events.add(
                                SimpleConditionEvent.violated(
                                        unit,
                                        unit.getFullName()
                                                + " picks a wallet by findFirst()/findAny()"));
                    }
                }
            }
        };
    }

    private static boolean readsOwnedAccounts(JavaCodeUnit unit) {
        return unit.getMethodCallsFromSelf().stream()
                .anyMatch(
                        call ->
                                OWNED_READS.contains(call.getTarget().getName())
                                        && call.getTarget().getOwner().isAssignableTo(STORE));
    }

    private static boolean namesTheWalletPurpose(JavaCodeUnit unit) {
        return unit.getFieldAccesses().stream()
                .anyMatch(
                        access ->
                                access.getTarget().getName().equals("CUSTOMER_WALLET")
                                        && access.getTarget()
                                                .getOwner()
                                                .isEquivalentTo(AccountPurpose.class));
    }

    private static boolean picks(JavaCodeUnit unit) {
        return unit.getMethodCallsFromSelf().stream()
                .anyMatch(
                        call ->
                                PICKS.contains(call.getTarget().getName())
                                        && call.getTarget()
                                                .getOwner()
                                                .isAssignableTo(java.util.stream.Stream.class));
    }

    // -----------------------------------------------------------------
    // Planted shapes — compiled for the importer, never called.
    // -----------------------------------------------------------------

    /** The shape this task removed, verbatim: the first wallet of the owned accounts. */
    static final class PlantedFirstWallet {
        Optional<LedgerAccount> walletOf(
                LedgerAccountStore<Connection> store, Connection uow, UUID product) {
            return store.findAllOwned(uow, product).stream()
                    .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET)
                    .findFirst();
        }
    }

    /** The same pick from a list obtained elsewhere, anchored on the purpose's name. */
    static final class PlantedPurposePick {
        Optional<LedgerAccount> walletOf(java.util.List<LedgerAccount> owned) {
            AccountPurpose wallet = AccountPurpose.CUSTOMER_WALLET;
            return owned.stream().filter(account -> account.purpose() == wallet).findAny();
        }
    }

    /** The keyed resolution the rule accepts. */
    static final class KeyedResolution {
        Optional<LedgerAccount> walletOf(
                LedgerAccountStore<Connection> store,
                Connection uow,
                UUID product,
                CurrencyCode currency) {
            return WalletAccounts.resolve(store.findAllOwned(uow, product), currency);
        }
    }
}
