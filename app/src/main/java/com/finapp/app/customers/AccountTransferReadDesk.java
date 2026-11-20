package com.finapp.app.customers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.accounts.AccountsAuditAction;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.LedgerAuditAction;
import com.finapp.ledger.LedgerCollections;
import com.finapp.party.CustomerId;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferId;
import com.finapp.transfers.TransferStatus;
import com.finapp.transfers.TransferStore;
import com.finapp.transfers.TransfersAuditAction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's account and transfer reads, composed in {@code app} from each owning module's own query (`P17-TSK-027`;
 * ADR-C132 decision 1): accounts' agreements, the ledger's displayed balances and lines, transfers' rows, party's customer for
 * the existence check. One {@code REPEATABLE READ} transaction per answer - the reads and the answer's record together - so an
 * agreement's three balances, and the instant they are stated as of, are one snapshot's while every replica posts.
 *
 * <p><strong>The balances are the ledger's own</strong> ({@code INV-BAL-*}): {@code BalanceDisplay}'s projection, read in one
 * statement - posted, held and available per currency - exactly as the customer's own balance read serves them. This desk adds,
 * subtracts and rounds nothing. {@code asOf} is the database's {@code statement_timestamp()} of the snapshot's first statement,
 * never an instance's clock (ADR-0063).
 */
@RequiredArgsConstructor
public class AccountTransferReadDesk {

    static final String AGREEMENTS = "accounts.agreements";
    static final String LINES = "ledger.account-lines";
    static final String TRANSFERS = "transfers.window";
    static final int DEFAULT_LIMIT = 50;
    static final int MAXIMUM_LIMIT = 200;

    /** The longest window a transfer page may span. */
    static final Duration TRANSFER_WINDOW = Duration.ofDays(92);

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    /** The snapshot's first statement: its instant, the database's, which every balance below is read as of. */
    static final String AS_OF = "SELECT statement_timestamp()";

    @NonNull private final PartyStore<Connection> partyStore;
    @NonNull private final CustomerAccountStore<Connection> customerAccountStore;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccountStore;
    @NonNull private final BalanceDisplay<Connection> balanceDisplay;
    @NonNull private final LedgerCollections ledgerCollections;
    @NonNull private final TransferStore<Connection> transferStore;
    @NonNull private final RecordedReads recordedReads;
    @NonNull private final TransactionTemplate operatorReadTransactions;
    @NonNull private final DataSource dataSource;

    /** A §7 page of a customer's agreements, newest opening first. An unknown customer is {@code 404}, recording nothing. */
    public AgreementPage agreements(String rawCustomerId, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        Optional<CustomerAccountStore.AgreementCursor> after = OperatorCollection.key(AGREEMENTS, cursor, 2).map(key -> {
            try {
                return new CustomerAccountStore.AgreementCursor(
                        Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        UUID customerId = uuid(rawCustomerId).orElseThrow(() -> notFound("customer"));
        List<CustomerAccount> rows = inOneSnapshot(unitOfWork -> {
            if (partyStore.findCustomer(unitOfWork, CustomerId.of(customerId)).isEmpty()) {
                return null;
            }
            List<CustomerAccount> page = customerAccountStore.pageFor(unitOfWork, customerId, after, size + 1);
            recordedReads.record(unitOfWork, AccountsAuditAction.CUSTOMER_ACCOUNTS_LISTED, "party.Customer", customerId.toString(),
                    "after=" + after.isPresent() + ", served=" + Math.min(size, page.size()));
            return page;
        });
        if (rows == null) {
            throw notFound("customer");
        }
        List<CustomerAccount> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            CustomerAccount tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(AGREEMENTS, Long.toString(micros(tail.openedAt())), tail.id().value().toString());
        }
        return new AgreementPage(served.stream().map(AgreementRow::of).toList(), next);
    }

    /** One agreement with its balances, as of the snapshot's instant. An unknown agreement is {@code 404}, recording nothing. */
    public AccountDetail agreement(String rawAccountId) {
        CustomerAccountId accountId = uuid(rawAccountId).map(CustomerAccountId::of).orElseThrow(() -> notFound("account"));
        return Optional.ofNullable(inOneSnapshot(unitOfWork -> {
            Instant asOf = statementInstant(unitOfWork);
            return customerAccountStore.findById(unitOfWork, accountId).map(account -> {
                List<BalanceDisplay.DisplayedBalance> balances = balanceDisplay.balancesFor(unitOfWork, accountId.value());
                recordedReads.record(unitOfWork, AccountsAuditAction.ACCOUNT_READ, "accounts.CustomerAccount",
                        accountId.value().toString(), "customer=" + account.customerId() + ", asOf=" + asOf
                                + ", currencies=" + balances.size());
                return AccountDetail.of(account, asOf, balances);
            }).orElse(null);
        })).orElseThrow(() -> notFound("account"));
    }

    /**
     * A §7 page of an agreement's ledger lines in {@code currency}, newest entry first. {@code currency} is required and must be
     * one the agreement holds; an unknown agreement or currency is {@code 404}, recording nothing.
     */
    public LinePage lines(String rawAccountId, String currency, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw OperatorCollection.invalid("currency is required: an ISO 4217 code the account holds.");
        }
        Optional<LedgerCollections.LineCursor> after = OperatorCollection.key(LINES, cursor, 2).map(key -> {
            try {
                return new LedgerCollections.LineCursor(UUID.fromString(key.get(0)), Integer.parseInt(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        CustomerAccountId accountId = uuid(rawAccountId).map(CustomerAccountId::of).orElseThrow(() -> notFound("account"));
        List<LedgerCollections.AccountLine> rows = inOneSnapshot(unitOfWork -> {
            if (customerAccountStore.findById(unitOfWork, accountId).isEmpty()) {
                return null;
            }
            // The agreement's wallet in exactly that currency - resolved by currency, never the first of a list (ADR-0076 6).
            Optional<LedgerAccount> ledgerAccount = currencyCode(currency).flatMap(code -> ledgerAccountStore.findOwned(
                    unitOfWork, accountId.value(), AccountPurpose.CUSTOMER_WALLET, code));
            if (ledgerAccount.isEmpty()) {
                return null;
            }
            List<LedgerCollections.AccountLine> page =
                    ledgerCollections.accountLines(unitOfWork, ledgerAccount.get().id(), after, size + 1);
            recordedReads.record(unitOfWork, LedgerAuditAction.ACCOUNT_LINES_LISTED, "accounts.CustomerAccount",
                    accountId.value().toString(), "currency=" + currency + ", after=" + after.isPresent() + ", served="
                            + Math.min(size, page.size()));
            return page;
        });
        if (rows == null) {
            throw notFound("account");
        }
        List<LedgerCollections.AccountLine> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            LedgerCollections.AccountLine tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(LINES, tail.entryId().toString(), Integer.toString(tail.seq()));
        }
        return new LinePage(served.stream().map(LineRow::of).toList(), next);
    }

    /**
     * A §7 page of a customer's transfers initiated in {@code [from, to)}, newest first. {@code customerId}, {@code from} and
     * {@code to} are required, the window at most {@link #TRANSFER_WINDOW}; an unknown customer is {@code 404}, recording nothing.
     */
    public TransferPage transfers(String rawCustomerId, String status, String from, String to, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, DEFAULT_LIMIT, MAXIMUM_LIMIT);
        if (rawCustomerId == null) {
            throw OperatorCollection.invalid("customerId is required: a customer's transfers, never the platform's.");
        }
        Instant fromInstant = instant("from", from);
        Instant toInstant = instant("to", to);
        if (!fromInstant.isBefore(toInstant) || Duration.between(fromInstant, toInstant).compareTo(TRANSFER_WINDOW) > 0) {
            throw OperatorCollection.invalid("[from, to) is a window of at most 92 days, from before to.");
        }
        Optional<TransferStatus> statusFilter = Optional.ofNullable(status).map(value -> {
            try {
                return TransferStatus.valueOf(value);
            } catch (IllegalArgumentException unknown) {
                throw OperatorCollection.invalid("status is one of " + Arrays.toString(TransferStatus.values()) + ".");
            }
        });
        Optional<TransferStore.TransferCursor> after = OperatorCollection.key(TRANSFERS, cursor, 2).map(key -> {
            try {
                return new TransferStore.TransferCursor(
                        Instant.EPOCH.plus(Long.parseLong(key.get(0)), ChronoUnit.MICROS), UUID.fromString(key.get(1)));
            } catch (RuntimeException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        UUID customerId = uuid(rawCustomerId).orElseThrow(() -> notFound("customer"));
        List<Transfer> rows = inOneSnapshot(unitOfWork -> {
            if (partyStore.findCustomer(unitOfWork, CustomerId.of(customerId)).isEmpty()) {
                return null;
            }
            List<Transfer> page = transferStore.pageFor(unitOfWork, customerId, fromInstant, toInstant, statusFilter, after,
                    size + 1);
            recordedReads.record(unitOfWork, TransfersAuditAction.TRANSFERS_LISTED, "party.Customer", customerId.toString(),
                    "from=" + fromInstant + ", to=" + toInstant + ", status=" + statusFilter.map(Enum::name).orElse("any")
                            + ", after=" + after.isPresent() + ", served=" + Math.min(size, page.size()));
            return page;
        });
        if (rows == null) {
            throw notFound("customer");
        }
        List<Transfer> served = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (rows.size() > size) {
            Transfer tail = served.get(served.size() - 1);
            next = OperatorCollection.cursor(TRANSFERS, Long.toString(micros(tail.initiatedAt())), tail.id().value().toString());
        }
        return new TransferPage(served.stream().map(TransferRow::of).toList(), next);
    }

    /** One transfer with its journal entry's identifier. An unknown transfer is {@code 404}, recording nothing. */
    public TransferDetail transfer(String rawTransferId) {
        TransferId transferId = uuid(rawTransferId).map(TransferId::of).orElseThrow(() -> notFound("transfer"));
        return Optional.ofNullable(inOneSnapshot(unitOfWork -> transferStore.findById(unitOfWork, transferId).map(found -> {
            recordedReads.record(unitOfWork, TransfersAuditAction.TRANSFER_READ, "transfers.Transfer",
                    transferId.value().toString(), "customer=" + found.customerId() + ", status=" + found.status().name());
            return TransferDetail.of(found);
        }).orElse(null))).orElseThrow(() -> notFound("transfer"));
    }

    // -----------------------------------------------------------------

    private static Instant statementInstant(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement(AS_OF); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw new IllegalStateException("reading the snapshot's instant (SQLSTATE " + failure.getSQLState() + ")");
        }
    }

    private static Instant instant(String parameter, String value) {
        if (value == null) {
            throw OperatorCollection.invalid(parameter + " is required: an instant, ISO 8601 with its offset.");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException malformed) {
            throw OperatorCollection.invalid(parameter + " is an instant, ISO 8601 with its offset.");
        }
    }

    private static long micros(Instant instant) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, instant);
    }

    /** An ISO 4217 code the platform knows, or nothing: a currency it does not know is one no agreement holds - a 404. */
    private static Optional<CurrencyCode> currencyCode(String code) {
        try {
            return Optional.of(CurrencyCode.of(code));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** A canonical UUIDv7 (ADR-0013), or nothing: anything else names nothing - a 404, never the identifier types' 500. */
    private static Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) && parsed.version() == 7 && parsed.variant() == 2
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    private static ApiException notFound(String what) {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No " + what + " matched the given identifier");
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private <R> R inOneSnapshot(Function<Connection, R> work) {
        return operatorReadTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    /** A §7 page of agreements. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AgreementPage(List<AgreementRow> items, String nextCursor) {}

    /** One agreement in a customer's list: identifiers, product, status and instants - no balance. */
    public record AgreementRow(String accountId, String productType, String status, Instant openedAt, Instant statusChangedAt) {
        static AgreementRow of(CustomerAccount account) {
            return new AgreementRow(account.id().value().toString(), account.productType().name(), account.status().name(),
                    account.openedAt(), account.statusChangedAt());
        }
    }

    /** One agreement with its balances per currency, stated as of the snapshot's instant. */
    public record AccountDetail(
            String accountId,
            String customerId,
            String productType,
            String status,
            Instant openedAt,
            Instant statusChangedAt,
            Instant asOf,
            List<BalanceView> balances) {
        static AccountDetail of(CustomerAccount account, Instant asOf, List<BalanceDisplay.DisplayedBalance> balances) {
            return new AccountDetail(account.id().value().toString(), account.customerId().toString(),
                    account.productType().name(), account.status().name(), account.openedAt(), account.statusChangedAt(), asOf,
                    balances.stream().map(BalanceView::of).toList());
        }
    }

    /** One currency's three balances, each named for what it is, decimal strings - the ledger's own projection. */
    public record BalanceView(String currency, String posted, String held, String available) {
        static BalanceView of(BalanceDisplay.DisplayedBalance balance) {
            return new BalanceView(balance.settled().currency().code(), decimal(balance.settled()), decimal(balance.holds()),
                    decimal(balance.available()));
        }
    }

    /** A §7 page of ledger lines. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LinePage(List<LineRow> items, String nextCursor) {}

    /** One ledger line: its entry, the entry's kind and dates, the direction and the amount. Never a reference or a reason. */
    public record LineRow(
            String entryId,
            int line,
            String entryType,
            java.time.LocalDate postingDate,
            java.time.LocalDate valueDate,
            Instant postedAt,
            String direction,
            String amount,
            String currency) {
        static LineRow of(LedgerCollections.AccountLine line) {
            return new LineRow(line.entryId().toString(), line.seq(), line.entryType().name(), line.postingDate(),
                    line.valueDate(), line.postedAt(), line.direction(), line.amount().toPlainString(), line.currency());
        }
    }

    /** A §7 page of transfers. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferPage(List<TransferRow> items, String nextCursor) {}

    /** One transfer in a list: identifiers, amount, status and when it was initiated - never the customer's reference text. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferRow(
            String transferId,
            String sourceAccountId,
            String destinationAccountId,
            String amount,
            String currency,
            String status,
            String failureReason,
            Instant initiatedAt) {
        static TransferRow of(Transfer transfer) {
            return new TransferRow(transfer.id().value().toString(), transfer.sourceAccount().value().toString(),
                    transfer.destinationAccount().value().toString(), decimal(transfer.amount()),
                    transfer.amount().currency().code(), transfer.status().name(),
                    transfer.failureReason() == null ? null : transfer.failureReason().name(), transfer.initiatedAt());
        }
    }

    /**
     * One transfer: its identifiers, amount, status and instants, with the journal entry that posted it and the one that reversed
     * it, when either exists. Never its reference - a person's free text - nor who initiated or reversed it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransferDetail(
            String transferId,
            String customerId,
            String sourceAccountId,
            String destinationAccountId,
            String amount,
            String currency,
            String status,
            String failureReason,
            String journalEntryId,
            String reversalEntryId,
            Instant initiatedAt,
            Instant reversedAt) {
        static TransferDetail of(Transfer transfer) {
            return new TransferDetail(transfer.id().value().toString(), transfer.customerId().toString(),
                    transfer.sourceAccount().value().toString(), transfer.destinationAccount().value().toString(),
                    decimal(transfer.amount()), transfer.amount().currency().code(), transfer.status().name(),
                    transfer.failureReason() == null ? null : transfer.failureReason().name(),
                    transfer.journalEntryId() == null ? null : transfer.journalEntryId().value().toString(),
                    transfer.reversalEntryId() == null ? null : transfer.reversalEntryId().value().toString(),
                    transfer.initiatedAt(), transfer.reversedAt());
        }
    }
}
