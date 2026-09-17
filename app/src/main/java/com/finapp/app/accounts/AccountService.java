package com.finapp.app.accounts;

import com.finapp.accounts.AccountClosing;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.ledger.StatementDerivation;
import com.finapp.party.Customer;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/me/accounts` slice behind {@link AccountController} (`P3-TSK-013`).
 *
 * <p><strong>The caller can name nobody.</strong> Every operation resolves
 * {@code Session → Identity → Party → live Customer} inside its own transaction — the
 * {@code ProfileService} chain one hop longer — so the customer whose accounts are listed,
 * whose balance is read and whose product is opened is always the proven caller's, never a
 * request's. The one path identifier ({@code {id}} on the balance read) is resolved through
 * {@link CustomerAccountStore#findOwnedBy}, whose ownership predicate is in the statement.
 *
 * <p><strong>The open is idempotent at two layers, blind in different directions.</strong> The
 * {@link IdempotentExecutor} replays the recorded outcome for a retried key and refuses a
 * reused key whose request differs ({@code INV-IDEM-01}, {@code INV-IDEM-03}) — the fingerprint
 * binds the <em>party</em> as well as the product and currency (ADR-0004's owning principal),
 * so a stranger replaying a key they saw in a log gets a conflict, never somebody else's
 * account. Underneath, `P3-TSK-012`'s {@code openOrConverge} makes any re-execution — a
 * different key, a racing instance — converge on the one live agreement.
 */
public final class AccountService {

    /** The idempotency scope (ADR-0004): one command type, one scope. */
    static final String IDEMPOTENCY_SCOPE = "accounts.open";

    private final AccountOpening opening;
    private final AccountClosing closing;
    private final CustomerAccountStore<Connection> accounts;
    private final BalanceDisplay<Connection> balances;
    private final StatementDerivation<Connection> statements;
    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final IdempotentExecutor executor;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public AccountService(
            AccountOpening opening,
            AccountClosing closing,
            CustomerAccountStore<Connection> accounts,
            BalanceDisplay<Connection> balances,
            StatementDerivation<Connection> statements,
            IdentityStore<Connection> identities,
            PartyStore<Connection> parties,
            IdempotentExecutor executor,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.opening = Objects.requireNonNull(opening, "opening must not be null");
        this.closing = Objects.requireNonNull(closing, "closing must not be null");
        this.accounts = Objects.requireNonNull(accounts, "accounts must not be null");
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
        this.statements = Objects.requireNonNull(statements, "statements must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** The rendered agreement — also the stored idempotent response, replayed verbatim. */
    public record AccountView(String id, String productType, String status, String openedAt) {

        static AccountView of(CustomerAccount account) {
            return new AccountView(
                    account.id().value().toString(),
                    account.productType().name(),
                    account.status().name(),
                    account.openedAt().toString());
        }
    }

    /**
     * Opens (or replays the opening of) the caller's account of {@code productType}.
     *
     * <p>The replay is the <strong>original outcome</strong> ({@code INV-IDEM-01}): the stored
     * response is the body the first call rendered, byte for byte — never a re-read, because a
     * retry must learn what its request did, not what the world looks like now.
     */
    public AccountView open(
            Session current, ProductType productType, CurrencyCode currency, String idempotencyKey) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(productType, "productType must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        IdempotencyKey key = new IdempotencyKey(IDEMPOTENCY_SCOPE, idempotencyKey);

        byte[] body =
                inOneTransaction(
                        unitOfWork -> {
                            UUID partyId = partyOf(unitOfWork, current);
                            // The party in the fingerprint (ADR-0004's owning principal): a
                            // reused key from another caller is a materially different
                            // request and must conflict, never replay a stranger's account.
                            RequestFingerprint fingerprint =
                                    RequestFingerprint.sha256(
                                            (IDEMPOTENCY_SCOPE + "|" + partyId + "|"
                                                            + productType.name() + "|"
                                                            + currency.code())
                                                    .getBytes(StandardCharsets.UTF_8));
                            IdempotentExecutor.ExecutionOutcome outcome =
                                    executor.execute(
                                            unitOfWork,
                                            key,
                                            fingerprint,
                                            uow ->
                                                    com.finapp.platform.idempotency.CommandResult
                                                            .succeeded(
                                                                    render(
                                                                            opening.open(
                                                                                            uow,
                                                                                            partyId,
                                                                                            productType,
                                                                                            currency)
                                                                                    .account())));
                            return outcome.body()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "a recorded opening outcome always"
                                                                    + " carries its body"));
                        });
        return parse(body);
    }

    /** The caller's products — every status, oldest first; empty for no live customer. */
    public List<AccountView> list(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .map(
                                        customer ->
                                                accounts
                                                        .findAllFor(
                                                                unitOfWork,
                                                                customer.id().value())
                                                        .stream()
                                                        .map(AccountView::of)
                                                        .toList())
                                .orElse(List.of()));
    }

    /**
     * The balances of the caller's account {@code accountId} — or empty, one answer for
     * not-yours, does-not-exist and a caller with no live customer alike.
     */
    public Optional<Balances> balance(Session current, CustomerAccountId accountId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                accounts.findOwnedBy(
                                                        unitOfWork,
                                                        accountId,
                                                        customer.id().value()))
                                .map(
                                        account ->
                                                new Balances(
                                                        account,
                                                        balances.balancesFor(
                                                                unitOfWork,
                                                                account.id().value()))));
    }

    /** An owned account and its per-currency displayed balances. */
    public record Balances(
            CustomerAccount account, List<BalanceDisplay.DisplayedBalance> perCurrency) {}

    /**
     * The statement of the caller's account {@code accountId} for {@code [from, to]} — or
     * empty, one answer for not-yours, does-not-exist and a caller with no live customer
     * alike (the balance read's own shape). The figures are <strong>derived from
     * postings</strong>, never the projection — {@link StatementDerivation}'s argument — and
     * the read stays available for a {@code CLOSED} product, because the agreement ended and
     * the accounting history did not (`P3-TSK-014`, {@code INV-HIST-01}).
     *
     * <p>Deliberately not audited: a person's own read of their own account is not a
     * privileged action (the {@code SessionQueries} stance, the balance read's precedent).
     * The insider read-audit regime belongs to the operator surfaces (`LEDGER_READ`, later
     * tasks).
     */
    public Optional<Statement> statement(
            Session current, CustomerAccountId accountId, LocalDate from, LocalDate to) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                accounts.findOwnedBy(
                                                        unitOfWork,
                                                        accountId,
                                                        customer.id().value()))
                                .map(
                                        account ->
                                                new Statement(
                                                        account,
                                                        statements.statementsFor(
                                                                unitOfWork,
                                                                account.id().value(),
                                                                from,
                                                                to))));
    }

    /** An owned account and its per-currency derived statements. */
    public record Statement(
            CustomerAccount account, List<StatementDerivation.AccountStatement> perCurrency) {}

    /**
     * Ends the caller's agreement {@code accountId} — or converges on one already ended, which
     * is the retry story of a lost {@code DELETE} response. Empty is the one answer for
     * not-yours, does-not-exist and a caller with no live customer alike (`P3-TSK-014`).
     */
    public Optional<AccountClosing.Closure> close(Session current, CustomerAccountId accountId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(accountId, "accountId must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                closing.close(
                                                        unitOfWork,
                                                        customer.id().value(),
                                                        accountId)));
    }

    // -----------------------------------------------------------------

    /**
     * The stored form of the original outcome: the view's four fields, pipe-joined — the
     * {@code PostingService} stored-id shape, four fields instead of one. Not JSON,
     * deliberately: the stored bytes are internal (the HTTP body is rendered from the typed
     * record by the web layer), every field's charset excludes the delimiter — a UUID, two
     * enum names and an ISO instant — and a JSON round-trip here would add a serialiser
     * dependency to reconstruct four strings.
     */
    private static StoredResponse render(CustomerAccount account) {
        AccountView view = AccountView.of(account);
        String joined =
                view.id() + "|" + view.productType() + "|" + view.status() + "|" + view.openedAt();
        return StoredResponse.of(joined.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static AccountView parse(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", 4);
        if (fields.length != 4) {
            throw new IllegalStateException(
                    "a stored opening body always carries the view's four fields");
        }
        return new AccountView(fields[0], fields[1], fields[2], fields[3]);
    }

    /** {@code Session → Identity → Party}: the {@code ProfileService} chain. */
    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A proven session resolved to no identity; registration"
                                                + " should make this impossible"));
    }

    private Optional<Customer> liveCustomerOf(Connection unitOfWork, Session current) {
        return parties.findLiveCustomerFor(unitOfWork, PartyId.of(partyOf(unitOfWork, current)));
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
