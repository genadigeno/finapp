package com.finapp.app.merchant;

import com.finapp.merchant.IllegalPayoutDestinationTransitionException;
import com.finapp.merchant.MerchantClosedException;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationChangePendingException;
import com.finapp.merchant.PayoutDestinationGrant;
import com.finapp.merchant.PayoutDestinationId;
import com.finapp.merchant.PayoutDestinationTokenisation;
import com.finapp.merchant.PayoutDestinations;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.merchant.UnknownPayoutDestinationException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator surface over {@link PayoutDestinations} (`P6-TSK-011`, ADR-0056): the
 * transactions, the tokenisation exchange and the error mapping.
 *
 * <h2>The proposal is two transactions around a connectionless exchange</h2>
 *
 * <p>The {@code PaymentMethodService.attach} shape: the grant's shape is judged first (bank
 * details refused before anything else happens); a read-only transaction fails fast (the merchant
 * exists); the provider exchange runs holding <strong>no database connection</strong> (the
 * {@code P1-TSK-026} discipline — a slow provider must not hold a pool slot); and the keyed
 * proposal commits in a second transaction.
 *
 * <h2>Step-up on both sides, unconditionally</h2>
 *
 * <p>The proposer and the approver each need a {@code MULTI_FACTOR} session: the controller
 * declares it ({@code PRIVILEGED_ACCESS_REVIEW.md} §6, `P15-TSK-039`), so the interceptor refuses
 * a weaker session before anything here runs. It was the {@code P4-TSK-007} conditional until a
 * money-redirecting role came to need a factor at all. Rejecting and withdrawing need none:
 * stopping a change is the fail-safe direction.
 *
 * <h2>A self-approval commits its refusal</h2>
 *
 * <p>{@link PayoutDestinations#approve} returns the refusal instead of throwing it, so the
 * transaction commits the {@code DENIED} audit record and nothing else; the {@code 409} is raised
 * here, after the commit.
 */
public final class PayoutDestinationOperations {

    /** A destination as the operator reads it: masked, and never the provider reference. */
    public record PayoutDestinationView(
            String id,
            String status,
            String displaySuffix,
            String proposedAt,
            String approvedAt,
            String coolingOffUntil,
            String effectiveAt) {}

    /** The merchant's destination arrangement: what payouts go to, and the open change. */
    public record PayoutDestinationsView(
            PayoutDestinationView effective, PayoutDestinationView pending) {}

    private final PayoutDestinations destinations;
    private final ObjectProvider<PayoutDestinationTokenisation> tokenisation;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public PayoutDestinationOperations(
            PayoutDestinations destinations,
            ObjectProvider<PayoutDestinationTokenisation> tokenisation,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.destinations = Objects.requireNonNull(destinations, "destinations must not be null");
        this.tokenisation = Objects.requireNonNull(tokenisation, "tokenisation must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /**
     * Proposes the destination the grant tokenises to, or replays the recorded proposal for a
     * retried key.
     */
    public PayoutDestinationView propose(
            String rawMerchantId,
            ProposePayoutDestinationRequest body,
            String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        MerchantId merchant = merchantOrAbsent(rawMerchantId);

        // The grant's shape - including the refusal of account-number-shaped values - decided
        // before any transaction or provider call. Names the field, never the value (INV-AUD-02).
        PayoutDestinationGrant grant;
        try {
            grant = new PayoutDestinationGrant(body.destinationToken());
        } catch (IllegalArgumentException refused) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payout destination grant was refused by the domain rule",
                    "destinationToken must be a payout provider grant, not bank details.");
        }

        // Tx1: fail fast, read-only - a refused caller costs no exchange and writes nothing.
        inOneTransaction(
                unitOfWork -> {
                    if (!destinations.merchantExists(unitOfWork, merchant)) {
                        throw notFound();
                    }
                    return null;
                });

        // The exchange, holding no database connection. An absent provider is unavailable: an
        // unconfigured deployment keeps a stable contract and answers the honest 503.
        PayoutDestinationTokenisation provider = tokenisation.getIfAvailable();
        PayoutDestinationTokenisation.Exchange exchange =
                provider == null
                        ? PayoutDestinationTokenisation.Exchange.unavailable()
                        : provider.exchange(grant);
        PayoutDestinationTokenisation.TokenisedDestination tokenised =
                switch (exchange.outcome()) {
                    case TOKENISED -> exchange.destination().orElseThrow();
                    case REFUSED ->
                            throw new ApiException(
                                    MerchantErrorCode.DESTINATION_NOT_TOKENISED,
                                    "The payout provider refused the destination grant");
                    case UNAVAILABLE ->
                            throw new ApiException(
                                    MerchantErrorCode.DESTINATION_TOKENISATION_UNAVAILABLE,
                                    "The destination exchange could not be completed");
                };

        // Tx2: the keyed proposal. An ApiException aborts the transaction, so every refusal here
        // commits nothing.
        try {
            return inOneTransaction(
                    unitOfWork -> {
                        PayoutDestinationId proposed =
                                destinations
                                        .propose(
                                                unitOfWork,
                                                new PayoutDestinations.ProposeCommand(
                                                        idempotencyKey,
                                                        merchant,
                                                        tokenised,
                                                        body.reason()))
                                        .destinationId();
                        return destinations
                                .find(unitOfWork, merchant, proposed)
                                .map(PayoutDestinationOperations::render)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a recorded proposal resolved to no"
                                                                + " destination under its"
                                                                + " merchant"));
                    });
        } catch (UnknownMerchantException unknown) {
            throw notFound();
        } catch (MerchantClosedException closed) {
            throw new ApiException(
                    MerchantErrorCode.ILLEGAL_TRANSITION,
                    "A payout destination was proposed for a closed merchant",
                    "the merchant's current status does not permit this change.");
        } catch (PayoutDestinationChangePendingException pending) {
            throw new ApiException(
                    MerchantErrorCode.DESTINATION_CHANGE_PENDING,
                    "A payout destination change is already open for the merchant");
        }
    }

    /** What the merchant's payouts go to, and the open change — both masked. */
    public PayoutDestinationsView list(String rawMerchantId) {
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        return inOneTransaction(
                unitOfWork -> {
                    if (!destinations.merchantExists(unitOfWork, merchant)) {
                        throw notFound();
                    }
                    return new PayoutDestinationsView(
                            destinations
                                    .effectiveFor(unitOfWork, merchant)
                                    .map(PayoutDestinationOperations::render)
                                    .orElse(null),
                            destinations
                                    .pendingFor(unitOfWork, merchant)
                                    .map(PayoutDestinationOperations::render)
                                    .orElse(null));
                });
    }

    /**
     * Approves as the acting operator — who must not be the proposer ({@code INV-AUD-04}). The
     * self-approval's {@code DENIED} record commits before the {@code 409} is raised.
     */
    public PayoutDestinationView approve(
            String rawMerchantId,
            String rawDestinationId,
            PayoutDestinationDecisionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        PayoutDestinationId id = destinationOrAbsent(rawDestinationId);
        PayoutDestinations.Approval outcome =
                decided(
                        () ->
                                inOneTransaction(
                                        unitOfWork -> {
                                            return destinations.approve(
                                                    unitOfWork, merchant, id, body.reason());
                                        }));
        // The transaction committed whatever the outcome - the refusal's evidence included.
        return switch (outcome) {
            case PayoutDestinations.Approved approved -> render(approved.destination());
            case PayoutDestinations.SelfApprovalRefused refused ->
                    throw new ApiException(
                            MerchantErrorCode.SELF_APPROVAL_REFUSED,
                            "A payout destination's proposer tried to approve it");
        };
    }

    /** The second pair of eyes says no. Converges on an already-rejected change. */
    public PayoutDestinationView reject(
            String rawMerchantId, String rawDestinationId, PayoutDestinationDecisionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        PayoutDestinationId id = destinationOrAbsent(rawDestinationId);
        return render(
                decided(
                        () ->
                                inOneTransaction(
                                        unitOfWork ->
                                                destinations.reject(
                                                        unitOfWork, merchant, id, body.reason()))));
    }

    /**
     * Withdraws the change before it takes effect — the cooling-off's teeth. Converges on an
     * already-withdrawn change.
     */
    public PayoutDestinationView withdraw(
            String rawMerchantId, String rawDestinationId, PayoutDestinationDecisionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchant = merchantOrAbsent(rawMerchantId);
        PayoutDestinationId id = destinationOrAbsent(rawDestinationId);
        return render(
                decided(
                        () ->
                                inOneTransaction(
                                        unitOfWork ->
                                                destinations.withdraw(
                                                        unitOfWork, merchant, id, body.reason()))));
    }

    /** The decisions' shared error mapping: one 404, and the machine's refusal as a 409. */
    private static <R> R decided(java.util.function.Supplier<R> decision) {
        try {
            return decision.get();
        } catch (UnknownPayoutDestinationException unknown) {
            throw notFound();
        } catch (IllegalPayoutDestinationTransitionException notOpen) {
            throw new ApiException(
                    MerchantErrorCode.DESTINATION_CHANGE_NOT_OPEN,
                    "A payout destination decision was refused by the machine");
        }
    }

    private static PayoutDestinationView render(PayoutDestination destination) {
        return new PayoutDestinationView(
                destination.id().value().toString(),
                destination.status().name(),
                destination.displaySuffix(),
                destination.proposedAt().toString(),
                text(destination.approvedAt()),
                text(destination.coolingOffUntil()),
                text(destination.effectiveAt()));
    }

    private static String text(Optional<Instant> instant) {
        return instant.map(Instant::toString).orElse(null);
    }

    /** Malformed equals absent — the one 404 ({@code INV-MER-01}'s oracle discipline). */
    private static MerchantId merchantOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static PayoutDestinationId destinationOrAbsent(String rawId) {
        try {
            return PayoutDestinationId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A payout destination read found nothing at the identifier",
                "the requested resource does not exist.");
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
