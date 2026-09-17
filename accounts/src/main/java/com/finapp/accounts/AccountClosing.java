package com.finapp.accounts;

import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.DerivedBalance;
import com.finapp.ledger.Hold;
import com.finapp.ledger.HoldStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
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
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Closes a customer account product: the agreement ends, the accounting history does not
 * (`P3-TSK-014`, {@code INV-HIST-01}).
 *
 * <p><strong>The zero-balance check is a financial decision, so it derives from postings inside
 * the lock</strong> ({@code INV-BAL-05}, ADR-0039/0041): never the projection, and never a read
 * outside the {@code FOR UPDATE} this flow takes on the product's ledger accounts. The lock
 * mode is the race's whole answer — every in-flight posting holds {@code FOR KEY SHARE} on its
 * accounts (the FK, and `V007`'s trigger read), and {@code FOR UPDATE} is what conflicts with
 * it — so a posting that won the race is in the derivation this close judges, and a posting
 * that lost re-judges the status this close committed. Lock, then look (`P2-TSK-015`).
 *
 * <p><strong>A repeated close converges.</strong> The closers serialize on the product row's
 * own {@code FOR UPDATE}; one closes, and every other — a retry after a lost response, a
 * double-tap, the losers of a race — re-reads {@code CLOSED} under the lock and returns
 * converged with <strong>nothing written</strong>: one act, one record, one event
 * ({@code INV-KYC-03}'s discipline, the opening's own rule mirrored).
 *
 * <p><strong>Empty means empty of reservations too</strong> (`P3-TSK-015`): a standing hold
 * is value still reserved under the agreement, so the close refuses while one exists -
 * judged from the authoritative hold rows under the same lock, and released holds free the
 * close exactly as postings freed the balance.
 *
 * <p><strong>What closing never touches</strong>: a journal row. The history stays exactly as
 * posted — readable, immutable at {@code DB-PRIVILEGE} since `P3-TSK-005` — and the ledger
 * account row itself survives with its classification frozen; what changes is that `V007`
 * refuses it new lines. A successor agreement is a <em>new</em> aggregate: closure frees the
 * one-live slot ({@code INV-LIFE-04}'s asymmetry, decided by `P3-TSK-012`'s index).
 */
public final class AccountClosing {

    /** One fact, named once, in two registries — the audit action carries the same code. */
    static final String EVENT_TYPE = "accounts.AccountClosed";

    static final String PRODUCER = "accounts";
    static final int EVENT_VERSION = 1;
    static final String TARGET_TYPE = "customer_account";

    /** The result: the agreement as it now stands, and whether this call ended it. */
    public record Closure(CustomerAccount account, boolean closed) {}

    private final CustomerAccountStore<Connection> accounts;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final BalanceDerivation<Connection> derivation;
    private final HoldStore<Connection> holds;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public AccountClosing(
            CustomerAccountStore<Connection> accounts,
            LedgerAccountStore<Connection> ledgerAccounts,
            BalanceDerivation<Connection> derivation,
            HoldStore<Connection> holds,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.derivation = Objects.requireNonNull(derivation, "derivation must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * Ends the customer's agreement {@code accountId}, or converges on an already-ended one.
     * All writes on {@code unitOfWork}; a refusal thrown here rolls everything back, so a
     * refused close writes nothing, structurally.
     *
     * @return empty when no such account belongs to {@code customerId} — one answer for
     *     not-yours, does-not-exist and malformed alike, the surface's {@code 404}
     * @throws AccountNotEmptyException when any of the product's balances is non-zero, or
     *     any reservation still stands against one of its accounts (`P3-TSK-015`)
     * @throws IllegalCustomerAccountTransitionException for an agreement the machine does not
     *     let end from its current state ({@code SUSPENDED} — no producer this phase)
     */
    public Optional<Closure> close(
            Connection unitOfWork, UUID customerId, CustomerAccountId accountId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");

        // An unestablished actor is an error, never a default (ADR-0021).
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();

        // The closers' serialization point: FOR UPDATE on the product row, owner in the
        // statement. A loser blocks here and then reads what the winner committed.
        Optional<CustomerAccount> owned = accounts.lockOwnedBy(unitOfWork, accountId, customerId);
        if (owned.isEmpty()) {
            return Optional.empty();
        }
        CustomerAccount account = owned.get();
        if (account.status() == CustomerAccountStatus.CLOSED) {
            // Converged: the agreement is already ended, and a retry is not a second act.
            return Optional.of(new Closure(account, false));
        }

        Instant now = Instant.now(clock);
        // The machine's refusal for a state that cannot end (SUSPENDED) comes from moveTo
        // itself (INV-LIFE-02) - asked FIRST, before any money is looked at, because "you may
        // not close a frozen agreement" is true whatever its balance is.
        CustomerAccount closedAccount = account.moveTo(CustomerAccountStatus.CLOSED, clock);

        // Lock the money side, then look (P2-TSK-015): the derivation below runs in fresh
        // statements whose snapshots postdate the lock grant, so every posting that won the
        // race is in the number this decision judges.
        List<LedgerAccount> money =
                ledgerAccounts.lockOwnedForUpdate(unitOfWork, accountId.value());
        for (LedgerAccount ledgerAccount : money) {
            DerivedBalance balance =
                    derivation.derive(unitOfWork, ledgerAccount.id(), AsOf.latest());
            if (!balance.settled().isZero()) {
                throw new AccountNotEmptyException(accountId, ledgerAccount.currency());
            }
            // A standing hold is value still reserved under the agreement (P3-TSK-015):
            // postings are not gated by holds, so settled can reach zero while a
            // reservation stands, and closing then would strand it - the agreement is not
            // empty while any reservation is. Judged under the same lock, from the
            // authoritative hold rows (INV-BAL-05's discipline; never holds_minor).
            List<Hold> standing = holds.findActiveFor(unitOfWork, ledgerAccount.id());
            if (!standing.isEmpty()) {
                throw new AccountNotEmptyException(accountId, ledgerAccount.currency());
            }
        }

        // The ledger accounts stop accepting postings (V007 judges the status this commits).
        // The caller holds each row's lock, so a zero row count is an invariant already broken
        // - loud, never converged.
        for (LedgerAccount ledgerAccount : money) {
            if (!ledgerAccounts.moveStatus(
                    unitOfWork,
                    ledgerAccount.id(),
                    LedgerAccountStatus.ACTIVE,
                    LedgerAccountStatus.CLOSED,
                    now)) {
                throw new AccountsStorageException(
                        "a ledger account of a closing product was not ACTIVE under the"
                                + " product's own lock - an invariant is already broken");
            }
        }

        if (!accounts.moveStatus(
                unitOfWork,
                accountId,
                account.status(),
                CustomerAccountStatus.CLOSED,
                now)) {
            throw new AccountsStorageException(
                    "the product row moved under its own FOR UPDATE - an invariant is already"
                            + " broken");
        }

        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        now,
                        AccountsAuditAction.ACCOUNT_CLOSED,
                        TARGET_TYPE,
                        accountId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers and enumerated names - never a balance (INV-AUD-02).
                        Optional.of(
                                "account=" + accountId + ", product=" + account.productType()
                                        + ", customer=" + customerId
                                        + ", ledgerAccounts=" + money.size())));

        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        EVENT_TYPE,
                        EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        accountId,
                        TARGET_TYPE,
                        now,
                        PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("productType", account.productType().name())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);

        return Optional.of(new Closure(closedAccount, true));
    }

    /** The flow's correlation with the cause resolved — the {@code AccountOpening} idiom. */
    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an account closing must run inside a"
                                                        + " correlation scope: the audit record"
                                                        + " and the event both carry the"
                                                        + " identifier"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
