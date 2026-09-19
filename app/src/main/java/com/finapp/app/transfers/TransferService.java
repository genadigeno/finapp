package com.finapp.app.transfers;

import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.party.Customer;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.transfers.Beneficiary;
import com.finapp.transfers.BeneficiaryId;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.FailureReason;
import com.finapp.transfers.IllegalTransferTransitionException;
import com.finapp.transfers.Transfer;
import com.finapp.transfers.TransferCommand;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferId;
import com.finapp.transfers.TransferReversal;
import com.finapp.transfers.TransferResult;
import com.finapp.transfers.TransferStore;
import com.finapp.transfers.TransfersErrorCode;
import com.finapp.transfers.UnknownTransferDestinationException;
import com.finapp.transfers.UnknownTransferSourceException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The `/v1/transfers` slice behind {@link TransferController} (`P4-TSK-008`).
 *
 * <h2>One idempotency claim, the command's</h2>
 *
 * <p>{@link TransferExecution} already claims at the financial boundary — scope
 * {@code transfer.execute}, the caller's key, the fingerprint binding the actor and the money's
 * meaning ({@code INV-IDEM-01/-03}) — so this surface deliberately adds <strong>no second
 * layer</strong>: the accounts precedent stacked an HTTP executor over a domain <em>converge</em>,
 * and stacking one over a domain <em>executor</em> would be two claims and two fingerprints for
 * one boundary. What makes the retried {@code POST} body byte-for-byte identical is that the
 * view is rendered from the replayed judgement ({@link TransferResult} — the <em>original</em>
 * status and reason, never a re-read, so `P4-TSK-009`'s reversal cannot leak into a replay) plus
 * row columns `V002`'s trigger freezes for every writer (the entry id, the instant, the amount,
 * the reference).
 *
 * <h2>The beneficiary arm is a per-decision authoritative read</h2>
 *
 * <p>{@code beneficiaryId} names the caller's own address-book entry: resolved through
 * {@code party_id = ?} and required {@code ACTIVE} inside the execution's own transaction —
 * <strong>a removed beneficiary refuses new transfers</strong> (M4.3's fourth acceptance
 * clause), with unknown, a stranger's, malformed and removed one byte-identical
 * {@code transfers.UnknownDestination}, so the endpoint is an oracle over nobody's saved
 * destinations. One recorded corner: a <em>retry</em> whose beneficiary was removed between the
 * original and the retry is refused rather than replayed — the resolution runs before the claim
 * can answer (the {@code ConsentGate} per-decision discipline), nothing is written, and
 * {@code INV-IDEM-01}'s financial half (never a second effect) holds absolutely; the judged
 * transfer stays readable through {@code GET /v1/transfers}.
 *
 * <h2>The caller can name nobody as an owner</h2>
 *
 * <p>The POST's source resolves through the caller's <em>live customer</em> inside the command
 * ({@code TransferParticipants.sourceOwnedBy} — unknown, not-yours and malformed one empty
 * answer, surfaced as one {@code transfers.UnknownSource}); the GETs resolve
 * {@code Session → Identity → Party → live Customer} (the {@code AccountService} chain) and read
 * through {@code customer_id = ?} in the statement, so not-yours, unknown and malformed are one
 * 404.
 */
public final class TransferService {

    private final TransferExecution execution;
    private final TransferReversal reversal;
    private final TransferStore<Connection> transfers;
    private final BeneficiaryStore<Connection> beneficiaries;
    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public TransferService(
            TransferExecution execution,
            TransferReversal reversal,
            TransferStore<Connection> transfers,
            BeneficiaryStore<Connection> beneficiaries,
            IdentityStore<Connection> identities,
            PartyStore<Connection> parties,
            TransactionTemplate transferTransactions,
            DataSource dataSource) {
        this.execution = Objects.requireNonNull(execution, "execution must not be null");
        this.reversal = Objects.requireNonNull(reversal, "reversal must not be null");
        this.transfers = Objects.requireNonNull(transfers, "transfers must not be null");
        this.beneficiaries =
                Objects.requireNonNull(beneficiaries, "beneficiaries must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
        this.transactions =
                Objects.requireNonNull(transferTransactions, "transferTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * The rendered judgement — the asynchronous-outcome contract shape: {@code status} always,
     * {@code failureReason} exactly when {@code FAILED}, {@code journalEntryId} exactly when
     * money moved (the chain walk's key: transfer → entry → statement line, `INV-ACC-02`'s
     * drill-down one identifier earlier). <strong>Deliberately no account identifiers</strong>:
     * the row stores <em>ledger</em> accounts — internal accounting vocabulary, and the
     * destination's belongs to a third party (the `P3-TSK-018` no-counterparty rule); the
     * commanded pair is the caller's own knowledge, echoed nowhere. The amount is a decimal
     * string (`P3-TSK-013`'s reasoning), disclosed only to the customer it belongs to.
     * {@code reversalEntryId} and {@code reversedAt} exist exactly when {@code REVERSED}
     * (`P4-TSK-009` — the reversal's own chain-walk key beside {@code journalEntryId});
     * <strong>the operator's identity is deliberately not disclosed</strong> — who reversed a
     * customer's transfer is the audit trail's fact, not the customer's view's.
     */
    public record TransferView(
            String id,
            String status,
            String failureReason,
            String amount,
            String currency,
            String reference,
            String journalEntryId,
            String createdAt,
            String reversalEntryId,
            String reversedAt) {}

    /**
     * Executes (or replays) the caller's transfer and answers the judgement — a {@code FAILED}
     * outcome is a {@code 201} whose body says so, never an HTTP error (`PHASE_4_PLAN.md` §9:
     * the command was accepted and its domain outcome recorded).
     */
    public TransferView create(Session current, TransferCreateRequest body, String idempotencyKey) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        // Exactly one destination arm, decided before any transaction: both and neither are the
        // caller's own correctable mistake, named specifically (unlike the identifiers below,
        // whose refusals must disclose nothing).
        boolean hasAccountArm = hasText(body.destinationAccountId());
        boolean hasBeneficiaryArm = hasText(body.beneficiaryId());
        if (hasAccountArm == hasBeneficiaryArm) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A transfer request did not name exactly one destination arm",
                    "exactly one of 'destinationAccountId' and 'beneficiaryId' must be present.");
        }

        Money amount = parsedAmount(body.amount(), body.currency());

        // Malformed folds into the source refusal (malformed-equals-absent): the resolution
        // port answers unknown and not-yours with one empty, and the fold keeps malformed
        // indistinguishable from both.
        UUID sourceRef = parsedOr(body.sourceAccountId(), TransferService::unknownSource);

        return inOneTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    UUID destinationRef =
                            hasBeneficiaryArm
                                    ? resolvedBeneficiaryDestination(
                                            unitOfWork, partyId, body.beneficiaryId())
                                    : parsedOr(
                                            body.destinationAccountId(),
                                            TransferService::unknownDestination);
                    TransferResult result;
                    try {
                        result =
                                execution.execute(
                                        unitOfWork,
                                        new TransferCommand(
                                                idempotencyKey,
                                                partyId,
                                                sourceRef,
                                                destinationRef,
                                                amount,
                                                normalisedReference(body.reference())));
                    } catch (UnknownTransferSourceException unknown) {
                        throw unknownSource();
                    } catch (UnknownTransferDestinationException unknown) {
                        throw unknownDestination();
                    }
                    Transfer row =
                            transfers
                                    .findById(unitOfWork, result.transferId())
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "a judged transfer has a row: the"
                                                                + " execution inserted or"
                                                                + " replayed it in this very"
                                                                + " transaction"));
                    // Status and reason from the RESULT, not the row - and no reversal
                    // columns, ever: a replay must render the original judgement byte for
                    // byte, whatever the reversal (P4-TSK-009) has done to the row since.
                    return view(row, result.status().name(), result.failureReason(), null, null);
                });
    }

    /**
     * The caller's transfer {@code transfer} — or empty, one answer for not-yours,
     * does-not-exist and a caller with no live customer alike.
     */
    public Optional<TransferView> find(Session current, TransferId transfer) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(transfer, "transfer must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .flatMap(
                                        customer ->
                                                transfers.findOwned(
                                                        unitOfWork,
                                                        transfer,
                                                        customer.id().value()))
                                .map(TransferService::currentView));
    }

    /** The caller's transfers, newest first; empty for no live customer. */
    public List<TransferView> list(Session current) {
        Objects.requireNonNull(current, "current must not be null");
        return inOneTransaction(
                unitOfWork ->
                        liveCustomerOf(unitOfWork, current)
                                .map(
                                        customer ->
                                                transfers
                                                        .listFor(
                                                                unitOfWork,
                                                                customer.id().value())
                                                        .stream()
                                                        .map(TransferService::currentView)
                                                        .toList())
                                .orElse(List.of()));
    }

    /**
     * Reverses the transfer as the acting operator (`P4-TSK-009`) — the operator surface on a
     * customer slice, deliberately distinct in shape: no session-derived customer chain,
     * because the subject is <em>somebody else's</em> transfer named from the URL, and the
     * standing checks are {@code @RequiresPermission(TRANSFER_REVERSE)} at the boundary plus
     * the actor {@code SecurityContext} carries into the command and its audit record
     * (the {@code ADMINISTERED} class, `P1-TSK-028`).
     *
     * <p>The machine's refusal — {@code FAILED}, already-{@code REVERSED}, or the loser of a
     * concurrent race — maps to the one {@code 409 transfers.NotReversible}, with nothing
     * written (the thrown refusal rolls the transaction back, and it is thrown before any
     * ledger work anyway). An unknown identifier answers empty for the controller's one 404.
     */
    public Optional<TransferView> reverse(TransferId transfer, String reason) {
        Objects.requireNonNull(transfer, "transfer must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        return inOneTransaction(
                unitOfWork -> {
                    try {
                        return reversal
                                .reverse(unitOfWork, transfer, reason)
                                .map(TransferService::currentView);
                    } catch (IllegalTransferTransitionException refused) {
                        throw new ApiException(
                                TransfersErrorCode.NOT_REVERSIBLE,
                                "A reversal was refused by the transfer's state ("
                                        + refused.from() + ")",
                                "the transfer is " + refused.from()
                                        + " and only a COMPLETED transfer can be reversed.");
                    }
                });
    }

    // -----------------------------------------------------------------

    /**
     * The beneficiary arm: the caller's own {@code ACTIVE} entry, or the one refusal —
     * unknown, a stranger's, malformed and <strong>removed</strong> byte-identical
     * ({@code party_id = ?} in the statement; the live requirement is M4.3's fourth clause).
     */
    private UUID resolvedBeneficiaryDestination(
            Connection unitOfWork, UUID partyId, String beneficiaryRaw) {
        UUID id = parsedOr(beneficiaryRaw, TransferService::unknownDestination);
        return beneficiaries
                .findOwned(unitOfWork, BeneficiaryId.of(id), partyId)
                // "Live" is the machine's own non-terminal definition - the same one the
                // store's findLive and V003's index predicate are generated from - so this
                // check cannot drift from theirs if the machine ever grows a third state.
                .filter(owned -> !owned.status().isTerminal())
                .map(Beneficiary::destinationAccountId)
                .orElseThrow(TransferService::unknownDestination);
    }

    /**
     * Exact, or the caller's 422 naming the field ({@code INV-MON-03} at the inbound boundary
     * — the {@code LedgerAdjustments} idiom): never a rounding, and a non-positive amount is
     * refused here so the aggregate's defence-in-depth refusal is never our 500.
     */
    private static Money parsedAmount(String raw, String currencyRaw) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(currencyRaw);
        } catch (IllegalArgumentException unusable) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A transfer named a currency the platform cannot express amounts in",
                    "'currency' must be an ISO 4217 currency with a minor unit.");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw);
        } catch (NumberFormatException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A transfer amount was not a decimal number",
                    "'amount' must be a decimal string such as \"12.50\".");
        }
        Money amount;
        try {
            amount = Money.of(decimal, currency);
        } catch (MonetaryException inexact) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A transfer amount was not representable at the currency's scale",
                    "'amount' is not representable at the currency's scale.");
        }
        if (!amount.isPositive()) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A transfer amount was not strictly positive",
                    "'amount' must be strictly positive.");
        }
        return amount;
    }

    /**
     * The GET view: the row's CURRENT state — what the world looks like now, by design — the
     * reversal columns included when they exist. The POST path deliberately does not render
     * them: a replayed POST must be byte-for-byte the original body, and the reversal columns
     * are exactly the four `V002` leaves writable (`P4-TSK-009` must not leak into a replay).
     */
    private static TransferView currentView(Transfer row) {
        return view(
                row,
                row.status().name(),
                Optional.ofNullable(row.failureReason()),
                row.reversalEntryId() == null ? null : row.reversalEntryId().value().toString(),
                row.reversedAt() == null ? null : row.reversedAt().toString());
    }

    private static TransferView view(
            Transfer row,
            String status,
            Optional<FailureReason> reason,
            String reversalEntryId,
            String reversedAt) {
        return new TransferView(
                row.id().value().toString(),
                status,
                reason.map(Enum::name).orElse(null),
                row.amount().toBigDecimal().toPlainString(),
                row.amount().currency().code(),
                row.reference(),
                row.journalEntryId() == null ? null : row.journalEntryId().value().toString(),
                row.initiatedAt().toString(),
                reversalEntryId,
                reversedAt);
    }

    /** An absent reference and a blank one are the same absence. */
    private static String normalisedReference(String reference) {
        return (reference == null || reference.isBlank()) ? null : reference;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static UUID parsedOr(String raw, Supplier<ApiException> refusal) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw refusal.get();
        }
    }

    private static ApiException unknownSource() {
        return new ApiException(
                TransfersErrorCode.UNKNOWN_SOURCE,
                "A transfer source resolved to no product of the caller's");
    }

    private static ApiException unknownDestination() {
        return new ApiException(
                TransfersErrorCode.UNKNOWN_DESTINATION,
                "A transfer destination resolved to no customer wallet");
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
