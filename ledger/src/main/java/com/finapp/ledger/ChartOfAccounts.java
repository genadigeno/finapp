package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Resolves the platform's own account for a purpose and currency (`P3-TSK-003`).
 *
 * <p>The question every posting flow will ask — <em>which account does the fee go to? where
 * does the residual land?</em> — answered from the seeded operational chart, unambiguous
 * because {@code V002}'s partial unique index admits one operational row per purpose and
 * currency.
 *
 * <p><strong>A missing account throws; it is never an empty answer.</strong> The chart is
 * seeded by migration, so a purpose+currency with no row is a deployment defect — a supported
 * currency whose seed never landed — and an {@code Optional} a caller forgets to check would
 * turn that defect into a posting silently routed nowhere. The caller that could handle absence
 * does not exist.
 *
 * <p><strong>An owned purpose is refused outright</strong>: {@code CUSTOMER_WALLET} accounts
 * belong to customer products and are resolved by owner (`LedgerAccountStore#findOwned`), so
 * asking the <em>operational</em> chart for one is a programming error, not a lookup miss.
 */
@RequiredArgsConstructor
public final class ChartOfAccounts<T> {

    @NonNull private final LedgerAccountStore<T> store;

    /** The platform's account for this purpose in this currency. Throws on any gap. */
    public LedgerAccount resolve(T unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (purpose.ownerKind() == OwnerKind.CUSTOMER) {
            throw new IllegalArgumentException(
                    purpose + " accounts belong to customer products and are resolved by owner,"
                            + " never from the operational chart");
        }
        return store.findOperational(unitOfWork, purpose, currency)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the operational chart has no " + purpose + " account in "
                                                + currency + " - the seed migration is"
                                                + " incomplete for a currency the platform"
                                                + " claims to support (P3-TSK-003)"));
    }
}
