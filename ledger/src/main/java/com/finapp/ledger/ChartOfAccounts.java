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
 * belong to customer products and {@code MERCHANT_PAYABLE} accounts to merchants, each resolved
 * by owner (`LedgerAccountStore#findOwned`), so asking the <em>operational</em> chart for one is
 * a programming error, not a lookup miss. The predicate is the kind's own
 * {@link OwnerKind#requiresOwnerRef()} (`P6-DOC-001`): {@code == CUSTOMER} was correct while
 * exactly one kind had an owner, and `P6-TSK-003` retired that assumption from the seed's
 * guard but not from this one - so a merchant payable asked of the chart was answered as a
 * missing seed, the deployment defect below, rather than as the caller's mistake.
 *
 * <p><strong>A counterparty-owned purpose is resolved by counterparty, explicitly</strong>
 * (`P9-TSK-010`, ADR-0078 section 5): there is no "the" {@code FX_PROVIDER_CLEARING}, only
 * {@code fx-sim-a}'s - two providers on one purpose must never net ({@code INV-RAIL-04}) - so
 * the two-argument {@link #resolve(Object, AccountPurpose, CurrencyCode)} refuses one, naming the
 * four-argument form, and that form refuses every purpose that is not counterparty-owned.
 */
@RequiredArgsConstructor
public final class ChartOfAccounts<T> {

    @NonNull private final LedgerAccountStore<T> store;

    /**
     * The platform's account for this purpose in this currency, or empty where the chart has
     * none (the Phase 7 -&gt; 8 transition) — for a caller that must answer a delivery totally
     * rather than throw inside it: a door parking an external statement in a currency the
     * platform does not support refuses it as unmappable instead of rolling its evidence back.
     */
    public java.util.Optional<LedgerAccount> find(
            T unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (purpose.ownerKind().requiresOwnerRef()) {
            return java.util.Optional.empty();
        }
        return store.findOperational(unitOfWork, purpose, currency);
    }

    /** The platform's account for this purpose in this currency. Throws on any gap. */
    public LedgerAccount resolve(T unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (purpose.ownerKind() == OwnerKind.COUNTERPARTY) {
            throw new IllegalArgumentException(
                    purpose + " accounts belong to their " + purpose.ownerKind() + " and are"
                            + " resolved by counterparty - resolve(unitOfWork, purpose,"
                            + " counterpartyCode, currency) - never as one shared position"
                            + " (INV-RAIL-04, ADR-0078)");
        }
        if (purpose.ownerKind().requiresOwnerRef()) {
            throw new IllegalArgumentException(
                    purpose + " accounts belong to their " + purpose.ownerKind() + " owner and are"
                            + " resolved by owner, never from the operational chart");
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

    /**
     * One counterparty's account for a counterparty-owned purpose in this currency (`P9-TSK-010`,
     * ADR-0078 section 5). Throws on any gap: a declared counterparty × currency with no seeded
     * account is the deployment defect {@link CounterpartyChart#verify} refuses at startup, and
     * an undeclared one is a caller naming a counterparty the platform does not settle with.
     */
    public LedgerAccount resolve(
            T unitOfWork, AccountPurpose purpose, String counterpartyCode, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(counterpartyCode, "counterpartyCode must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        if (purpose.ownerKind() != OwnerKind.COUNTERPARTY) {
            throw new IllegalArgumentException(
                    purpose + " is " + purpose.ownerKind() + "-owned and has no counterparty"
                            + " account; resolve(unitOfWork, purpose, currency) or the owner's"
                            + " read serves it");
        }
        return store.findCounterpartyAccount(unitOfWork, purpose, counterpartyCode, currency)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "the chart has no " + purpose + " account of counterparty '"
                                                + counterpartyCode + "' in " + currency
                                                + " - a counterparty is seeded with every account"
                                                + " it settles, by the migration that admits it"
                                                + " (ADR-0078 section 4)"));
    }
}
