package com.finapp.accounts;

import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.NonNull;

/**
 * Opens a customer account product: the agreement and its ledger account, in one transaction
 * (`P3-TSK-012`, ADR-0042).
 *
 * <p><strong>The gate is a per-decision authoritative read inside this unit of work.</strong>
 * {@link AccountHolderVerification} answers "may this party hold accounts, and as which
 * customer?" from {@code party.customer.status = ACTIVE} — Phase 2's projection consumed, never
 * recomputed ({@code INV-KYC-05}) — and the customer identifier the account records is that
 * answer, never a caller's.
 *
 * <p><strong>One accepted race, stated</strong> (the {@code P2-TSK-008} class): an opening
 * racing a concurrent customer closure can commit an account for a customer closed milliseconds
 * later. The gate's claim is about the decision moment against authoritative state; the harm is
 * bounded because the product carries no balance, nothing moves money through it this phase,
 * and money-moving flows gate at their own lock (Phase 4, ADR-0039). A cross-module
 * {@code SELECT … FOR UPDATE} on the party row was considered and rejected: a cross-context
 * lock to freeze another module's aggregate for a product agreement is the coupling the
 * boundary exists to prevent.
 *
 * <p><strong>Only the creating call audits and announces.</strong> A converged open — a retry,
 * a double-tap, the losers of a race — is not a second act: one agreement, one record, one
 * event, however many times it was asked for ({@code INV-KYC-03}'s discipline). The converged
 * caller gets the existing live product <em>unchanged</em>, including when it asked in a
 * different currency: adding a currency is a different act, {@link #addCurrency}
 * (`P9-TSK-004`), never smuggled in here.
 *
 * <p><strong>Every wallet account opens through {@link WalletAccounts#openIfAbsent}</strong>
 * (`P9-TSK-004`, D28) — the first currency here, every later one at {@link #addCurrency} — so
 * each wallet account is announced by exactly one {@code accounts.WalletCurrencyAdded}, written
 * by the call whose insert created it, whichever act that was.
 */
public final class AccountOpening {

    /** One fact, named once, in two registries — the audit action carries the same code. */
    static final String EVENT_TYPE = "accounts.AccountOpened";

    static final String PRODUCER = "accounts";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "customer_account";

    private final CustomerAccountStore<Connection> accounts;
    private final AccountHolderVerification<Connection> holders;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    /** The one door every wallet account opens through, built from this act's own stores. */
    private final WalletAccounts wallets;

    /**
     * Explicit rather than generated: the wallet door is <em>derived</em> from the stores, so
     * every caller of this act opens wallets through the same code path without a second
     * constructor argument to forget. Parameter names are the bean names (`AccountsBeans`).
     */
    public AccountOpening(
            @NonNull CustomerAccountStore<Connection> accounts,
            @NonNull LedgerAccountStore<Connection> ledgerAccounts,
            @NonNull AccountHolderVerification<Connection> holders,
            @NonNull AuditWriter<Connection> audit,
            @NonNull OutboxWriter<Connection> outbox,
            @NonNull IdGenerator ids,
            @NonNull Clock clock) {
        this.accounts = accounts;
        this.holders = holders;
        this.audit = audit;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.wallets = new WalletAccounts(accounts, ledgerAccounts, outbox, ids, clock);
    }

    /**
     * Ensures the party's live account of {@code productType} exists, creating it — and its
     * ledger account in {@code currency} — when it does not. All writes on {@code unitOfWork};
     * the transaction boundary is the caller's, so a refusal thrown here rolls everything back
     * and a refused opening writes nothing, structurally.
     *
     * @throws AccountOpeningRefusedException when the party holds no {@code ACTIVE} customer
     * @throws UnsupportedAccountCurrencyException when the currency is not postable
     */
    public CustomerAccountStore.Creation open(
            Connection unitOfWork, UUID partyId, ProductType productType, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(productType, "productType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        // An unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // Refused before any lookup: the currency is the caller's own value, and an account the
        // chart cannot serve must not exist for a millisecond (INV-BAL-03's residual account
        // would not exist for it).
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new UnsupportedAccountCurrencyException(currency);
        }

        // The gate, and the customer identifier, in one authoritative read (INV-KYC-05).
        UUID customerId =
                holders.eligibleCustomer(unitOfWork, partyId)
                        .orElseThrow(AccountOpeningRefusedException::new);

        CustomerAccountStore.Creation creation =
                accounts.openOrConverge(
                        unitOfWork, CustomerAccount.open(ids, clock, customerId, productType));
        if (!creation.created()) {
            return creation;
        }

        CustomerAccount account = creation.account();

        Instant now = Instant.now(clock);
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        AccountsAuditAction.ACCOUNT_OPENED,
                        TARGET_TYPE,
                        account.id().value().toString(),
                        java.util.Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never a balance (INV-AUD-02).
                        java.util.Optional.of(
                                "account=" + account.id() + ", product=" + productType
                                        + ", customer=" + customerId)));

        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        account.id(),
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                // Enumerated names only (INV-AUD-02, plan section 10).
                EventPayload.of().with("productType", productType.name()).toBytes(),
                EventPayload.MEDIA_TYPE);

        // The product's money side, in the same transaction (ADR-0042), through the one wallet
        // door (P9-TSK-004) - after the agreement is announced, so a consumer meets
        // AccountOpened before the wallet it holds. The agreement row is this transaction's
        // own, so its FOR SHARE is granted at once; the door announces the wallet
        // (accounts.WalletCurrencyAdded).
        wallets.openIfAbsent(unitOfWork, customerId, account.id(), currency)
                .orElseThrow(
                        () ->
                                new AccountsStorageException(
                                        "an agreement this transaction created is not visible"
                                                + " to its own wallet door - an invariant is"
                                                + " already broken"));

        return creation;
    }

    /** The agreement and its wallet in the asked currency, and whether this act opened it. */
    public record CurrencyAddition(CustomerAccount account, LedgerAccount wallet, boolean added) {}

    /**
     * Adds a wallet in {@code currency} to the party's agreement {@code accountId}, or converges
     * on the wallet it already holds (`P9-TSK-004`). Every write on {@code unitOfWork}; a
     * refusal thrown here writes nothing.
     *
     * <p>The gate is the opening's own authoritative read ({@code INV-KYC-05}, unchanged): a
     * party with no {@code ACTIVE} customer adds nothing. Ownership is the agreement lock's
     * statement predicate inside {@link WalletAccounts#openIfAbsent}; only the call whose
     * insert created the wallet audits, as only the creating opening does.
     *
     * @return empty when the agreement does not exist or is not the party's customer's
     * @throws AccountOpeningRefusedException when the party holds no {@code ACTIVE} customer
     * @throws UnsupportedAccountCurrencyException when the currency is not postable
     * @throws AccountNotActiveException when the agreement is not {@code ACTIVE}
     */
    public java.util.Optional<CurrencyAddition> addCurrency(
            Connection unitOfWork, UUID partyId, CustomerAccountId accountId,
            CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(currency, "currency must not be null");

        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // Refused before any lookup, as the opening refuses it.
        if (!SupportedCurrencies.ALL.contains(currency)) {
            throw new UnsupportedAccountCurrencyException(currency);
        }
        UUID customerId =
                holders.eligibleCustomer(unitOfWork, partyId)
                        .orElseThrow(AccountOpeningRefusedException::new);

        java.util.Optional<WalletAccounts.Opened> opened =
                wallets.openIfAbsent(unitOfWork, customerId, accountId, currency);
        if (opened.isEmpty()) {
            return java.util.Optional.empty();
        }
        WalletAccounts.Opened wallet = opened.get();
        if (wallet.created()) {
            audit.append(
                    unitOfWork,
                    new AuditRecord(
                            AuditId.next(ids),
                            actor,
                            Instant.now(clock),
                            AccountsAuditAction.WALLET_CURRENCY_ADDED,
                            TARGET_TYPE,
                            accountId.value().toString(),
                            java.util.Optional.empty(),
                            AuditOutcome.SUCCEEDED,
                            correlation.correlationId(),
                            // Identifiers and an ISO code - never a balance (INV-AUD-02).
                            java.util.Optional.of(
                                    "account=" + accountId + ", currency=" + currency.code()
                                            + ", ledgerAccount=" + wallet.wallet().id())));
        }
        return java.util.Optional.of(
                new CurrencyAddition(wallet.product(), wallet.wallet(), wallet.created()));
    }

    /**
     * The flow's correlation, with the cause resolved: at a flow root the request is the cause —
     * the {@code PostingService}/{@code OrganisationRegistration} idiom, because the envelope's
     * causation field is mandatory ({@code INV-EVT-03}). Shared with {@link WalletAccounts},
     * whose event joins whichever act's flow opened the wallet.
     */
    static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an account opening must run inside a correlation"
                                                    + " scope: the audit record and the event"
                                                    + " both carry the identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
