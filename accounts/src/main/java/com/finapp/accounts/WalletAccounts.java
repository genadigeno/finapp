package com.finapp.accounts;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The wallet side of a customer's agreement: one {@code CUSTOMER_WALLET} ledger account per
 * currency, opened race-free inside the caller's transaction and resolved by currency
 * (`P9-TSK-004`, ADR-0076 §6, D28).
 *
 * <h2>Opening, once, whoever asks</h2>
 *
 * <p>{@link #openIfAbsent} is the one code path that brings a wallet account into existence —
 * the first currency at opening, the add-currency door, and in later tasks the conversion and
 * return paths. It reads the agreement {@code FOR SHARE} with the ownership predicate in the
 * statement (openers do not block each other; a close's {@code FOR UPDATE} serialises with
 * every one of them, so no wallet is ever opened under an agreement being closed), then asks
 * the ledger to insert the account <em>if absent</em>: ten concurrent openers of one currency
 * make one account, the nine losers wait for the winner on the unique index and read its row,
 * and nobody aborts. The call whose insert returned the row — and only that call — writes
 * {@code accounts.WalletCurrencyAdded}, so every wallet account is announced exactly once.
 *
 * <h2>Resolution, by currency</h2>
 *
 * <p>{@link #resolve} answers "which of this product's accounts is its wallet in currency
 * {@code c}?" — that account, or, when the product holds none in {@code c}, its
 * <em>first-opened</em> wallet, chosen by an explicit {@code (created_at, id)} order. The
 * fallback exists for the callers' refusals, never for posting: each flow judges the resolved
 * wallet's currency against its amount and refuses a mismatch with the real account (a
 * transfer's committed {@code FAILED(CURRENCY_MISMATCH)} row must carry real accounts). A
 * {@code findFirst()} over an unordered set of wallets is the defect this replaces, and
 * {@code WalletsAreResolvedByCurrencyTest} keeps it out.
 */
@RequiredArgsConstructor
public final class WalletAccounts {

    /** One fact, named once, in two registries — the audit action carries the same code. */
    static final String EVENT_TYPE = "accounts.WalletCurrencyAdded";

    @NonNull private final CustomerAccountStore<Connection> accounts;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The agreement, its wallet in the asked currency, and whether this call opened it. */
    public record Opened(CustomerAccount product, LedgerAccount wallet, boolean created) {}

    /**
     * Ensures {@code customerId}'s agreement {@code productId} holds a wallet in
     * {@code currency}, opening it when absent — every write on {@code unitOfWork}, which must
     * be {@code READ COMMITTED} (the re-read after a lost race is a fresh statement).
     *
     * @return empty when the agreement does not exist or is not the customer's — one answer
     * @throws UnsupportedAccountCurrencyException when the currency is not postable
     * @throws AccountNotActiveException when the customer's own agreement is not {@code ACTIVE}
     */
    public Optional<Opened> openIfAbsent(
            Connection unitOfWork,
            UUID customerId,
            CustomerAccountId productId,
            CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        // Refused before any lookup: an account the chart cannot serve must not exist for a
        // millisecond (INV-BAL-03's residual account would not exist for it).
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new UnsupportedAccountCurrencyException(currency);
        }

        // FOR SHARE, ownership in the statement: the openers' rank beneath the close.
        Optional<CustomerAccount> owned =
                accounts.lockOwnedForShare(unitOfWork, productId, customerId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        CustomerAccount product = owned.get();
        if (product.status() != CustomerAccountStatus.ACTIVE) {
            throw new AccountNotActiveException(productId, product.status());
        }

        // A customer's stored value is the platform's LIABILITY (ADR-0042); the ledger sees
        // only owner_kind CUSTOMER with this agreement's id as the opaque owner_ref.
        LedgerAccountStore.Creation creation =
                ledgerAccounts.insertIfAbsent(
                        unitOfWork,
                        LedgerAccount.owned(
                                ids,
                                clock,
                                AccountType.LIABILITY,
                                AccountPurpose.CUSTOMER_WALLET,
                                currency,
                                productId.value()));

        if (creation.created()) {
            Correlation correlation = AccountOpening.resolvedCorrelation();
            outbox.write(
                    unitOfWork,
                    new EventEnvelope(
                            EventId.next(ids),
                            EVENT_TYPE,
                            AccountOpening.EVENT_VERSION,
                            EventEnvelope.CURRENT_SCHEMA_VERSION,
                            productId,
                            AccountOpening.TARGET_TYPE,
                            Instant.now(clock),
                            AccountOpening.PRODUCER,
                            correlation.correlationId(),
                            correlation.cause().orElseThrow()),
                    // Identifiers and an ISO code - never a balance (INV-AUD-02).
                    EventPayload.of()
                            .with("ledgerAccountId", creation.account().id().value().toString())
                            .with("currency", currency.code())
                            .toBytes(),
                    EventPayload.MEDIA_TYPE);
        }
        return Optional.of(new Opened(product, creation.account(), creation.created()));
    }

    /**
     * The product's wallet in {@code currency} among {@code owned} (one product's accounts),
     * or — when it holds none in {@code currency} — its first-opened wallet, for the caller's
     * own currency judgement to refuse. Empty only when the product holds no wallet at all.
     */
    public static Optional<LedgerAccount> resolve(
            List<LedgerAccount> owned, CurrencyCode currency) {
        Objects.requireNonNull(owned, "owned must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        for (LedgerAccount account : owned) {
            // At most one: the ledger's unique (owner_ref, purpose, currency).
            if (account.purpose() == AccountPurpose.CUSTOMER_WALLET
                    && account.currency().equals(currency)) {
                return Optional.of(account);
            }
        }
        return firstOpened(owned);
    }

    /**
     * The product's first-opened wallet among {@code owned}: the earliest {@code created_at},
     * the identifier breaking a tie — an order, stated, never an iteration's accident.
     */
    public static Optional<LedgerAccount> firstOpened(List<LedgerAccount> owned) {
        Objects.requireNonNull(owned, "owned must not be null");
        return owned.stream()
                .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET)
                .min(
                        Comparator.comparing(LedgerAccount::createdAt)
                                .thenComparing(account -> account.id().value()));
    }
}
