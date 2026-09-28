package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The operational chart's refusal, partitioned by the owner kind's own predicate
 * (`P6-DOC-001`).
 *
 * <p>Every purpose is exactly one of two things: owned, and so refused before the store is
 * asked, or unowned, and so asked of the store. {@code == CUSTOMER} made that partition wrong
 * the day {@code MERCHANT} became a second owning kind (`P6-TSK-003`): a merchant payable asked
 * of the chart fell through to the store and came back as the missing-seed defect. Iterating
 * {@link AccountPurpose#values()} rather than naming purposes is the point - a third owning
 * kind lands on the correct side, or this fails naming it.
 */
@DisplayName("the operational chart refuses every owned purpose and asks for every other")
class ChartOfAccountsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final RecordingStore store = new RecordingStore();
    private final ChartOfAccounts<Object> chart = new ChartOfAccounts<>(store);
    private final Object unitOfWork = new Object();

    @Test
    @DisplayName("an owned purpose - a wallet or a payable - is refused before the store is asked")
    void everyOwnedPurposeIsRefusedOutright() {
        List<AccountPurpose> owned =
                Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> purpose.ownerKind().requiresOwnerRef())
                        .toList();
        assertThat(owned)
                .as("the partition is not vacuous: both owning kinds are represented")
                .contains(AccountPurpose.CUSTOMER_WALLET, AccountPurpose.MERCHANT_PAYABLE);

        for (AccountPurpose purpose : owned) {
            assertThatThrownBy(() -> chart.resolve(unitOfWork, purpose, EUR))
                    .as("%s is resolved by its owner; asking the operational chart is a"
                            + " programming error, never a missing seed", purpose)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(purpose.name())
                    .hasMessageContaining(purpose.ownerKind().name());
        }
        assertThat(store.asked).as("no owned purpose ever reached the store").isEmpty();
    }

    @Test
    @DisplayName("every unowned purpose, the suspense seam included, is asked of the store")
    void everyUnownedPurposeIsAskedOfTheStore() {
        List<AccountPurpose> unowned =
                Arrays.stream(AccountPurpose.values())
                        .filter(purpose -> !purpose.ownerKind().requiresOwnerRef())
                        .toList();
        assertThat(unowned)
                .as("SUSPENSE is unowned and seeded: a filter of 'not OPERATIONAL' would drop it")
                .contains(AccountPurpose.SUSPENSE_UNMATCHED, AccountPurpose.PAYOUT_CLEARING);

        for (AccountPurpose purpose : unowned) {
            assertThatThrownBy(() -> chart.resolve(unitOfWork, purpose, EUR))
                    .as("an empty store is the missing-seed defect for %s, loud", purpose)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(purpose.name());
        }
        assertThat(store.asked).containsExactlyElementsOf(unowned);
    }

    /** Records what the chart asked for and answers every question with nothing. */
    private static final class RecordingStore implements LedgerAccountStore<Object> {

        private final List<AccountPurpose> asked = new ArrayList<>();

        @Override
        public Optional<LedgerAccount> findOperational(
                Object unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
            asked.add(purpose);
            return Optional.empty();
        }

        @Override
        public Creation createOrConverge(Object unitOfWork, LedgerAccount fresh) {
            throw new UnsupportedOperationException("the chart never creates");
        }

        @Override
        public Optional<LedgerAccount> findOwned(
                Object unitOfWork, UUID ownerRef, AccountPurpose purpose, CurrencyCode currency) {
            throw new UnsupportedOperationException("the chart never reads by owner");
        }

        @Override
        public List<LedgerAccount> findAllOwned(Object unitOfWork, UUID ownerRef) {
            throw new UnsupportedOperationException("the chart never reads by owner");
        }

        @Override
        public List<LedgerAccount> lockOwnedForUpdate(Object unitOfWork, UUID ownerRef) {
            throw new UnsupportedOperationException("the chart never locks");
        }

        @Override
        public Optional<LedgerAccount> lockForUpdate(
                Object unitOfWork, LedgerAccountId accountId) {
            throw new UnsupportedOperationException("the chart never locks");
        }

        @Override
        public Optional<LedgerAccount> lockForShare(
                Object unitOfWork, LedgerAccountId accountId) {
            throw new UnsupportedOperationException("the chart never locks");
        }

        @Override
        public boolean moveStatus(
                Object unitOfWork,
                LedgerAccountId accountId,
                LedgerAccountStatus from,
                LedgerAccountStatus to,
                Instant at) {
            throw new UnsupportedOperationException("the chart never moves a status");
        }
    }
}
