package com.finapp.app.merchant;

import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantNotTradingException;
import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayoutUnfundedException;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.NoEffectiveDestinationException;
import com.finapp.merchant.PayoutCurrencyMismatchException;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationStore;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.MonetaryException;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payout's HTTP surface (`P6-TSK-012`): parses the request, runs the command, maps its
 * refusals to the error contract, and renders the view.
 *
 * <h2>The command is NOT wrapped in a transaction</h2>
 *
 * <p>It runs its own dispatch transaction, wire call and outcome transaction (ADR-0046) — the
 * refund surface's discipline. The view is rendered afterwards, in a read of its own, from the
 * payout's frozen facts and the <strong>judged</strong> status the command returned: a replay
 * renders the judgement its request was given, never a resolution that arrived since, so the
 * replayed bytes are the original bytes.
 *
 * <h2>An unconfigured provider keeps the contract</h2>
 *
 * <p>The command exists only where {@code finapp.merchant.payout.provider.url} is configured;
 * without it an initiation answers the honest {@code merchant.PayoutProviderUnavailable} 503,
 * with nothing claimed, while a payout's read keeps working.
 */
@RequiredArgsConstructor
public class MerchantPayoutOperations {

    @NonNull private final ObjectProvider<MerchantPayouts> command;
    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final PayoutDestinationStore<Connection> destinations;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /**
     * One payout, as its merchant or an operator sees it. Every figure is a decimal string;
     * {@code status} is honest — {@code DISPATCHED} or {@code UNKNOWN} when it is.
     *
     * @param destinationSuffix the four characters an operator reads to recognise the account,
     *     never the account
     * @param failureReason present exactly when {@code status} is {@code FAILED}
     */
    public record PayoutView(
            String id,
            String status,
            String amount,
            String currency,
            String destinationSuffix,
            String failureReason,
            String createdAt) {}

    /** The merchant's own payout, asked with its API key. */
    public PayoutView initiateAsMerchant(
            AuthenticatedMerchant merchant, MerchantPayoutRequest body, String idempotencyKey) {
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(body, "body must not be null");
        return initiate(
                new MerchantPayouts.InitiateCommand(
                        merchant.merchantId(),
                        parsedAmount(body.amount(), body.currency()),
                        idempotencyKey,
                        Optional.empty(),
                        Optional.of(merchant.keyId())));
    }

    /** A payout an operator asked for on the merchant's behalf, reasoned. */
    public PayoutView initiateAsOperator(
            String rawMerchantId, OperatorPayoutRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        return initiate(
                new MerchantPayouts.InitiateCommand(
                        merchantOrAbsent(rawMerchantId),
                        parsedAmount(body.amount(), body.currency()),
                        idempotencyKey,
                        Optional.of(body.reason()),
                        Optional.empty()));
    }

    /** The merchant's payout, current. Another merchant's, unknown and malformed are one 404. */
    public PayoutView view(AuthenticatedMerchant merchant, String rawPayoutId) {
        Objects.requireNonNull(merchant, "merchant must not be null");
        MerchantPayoutId id = payoutOrAbsent(rawPayoutId);
        return inOneTransaction(
                unitOfWork ->
                        payouts.find(unitOfWork, merchant.merchantId(), id)
                                .map(payout -> render(unitOfWork, payout, payout.status()))
                                .orElseThrow(MerchantPayoutOperations::notFound));
    }

    private PayoutView initiate(MerchantPayouts.InitiateCommand initiate) {
        MerchantPayouts payoutCommand = command.getIfAvailable();
        if (payoutCommand == null) {
            throw new ApiException(
                    MerchantErrorCode.PAYOUT_PROVIDER_UNAVAILABLE,
                    "A payout arrived on a deployment with no payout provider configured");
        }
        MerchantPayouts.Initiated initiated;
        try {
            initiated = payoutCommand.initiate(initiate);
        } catch (MerchantPayoutUnfundedException unfunded) {
            throw new ApiException(
                    MerchantErrorCode.PAYOUT_UNFUNDED,
                    "A payout could not be held against the payable's available position"
                            + " (INV-MER-05)");
        } catch (NoEffectiveDestinationException nowhere) {
            throw new ApiException(
                    MerchantErrorCode.NO_EFFECTIVE_DESTINATION,
                    "A payout found no effective destination to pay to");
        } catch (MerchantNotTradingException notTrading) {
            throw new ApiException(
                    MerchantErrorCode.NOT_TRADING,
                    "A payout was refused by the merchant's standing");
        } catch (PayoutCurrencyMismatchException mismatch) {
            throw new ApiException(
                    MerchantErrorCode.PAYOUT_CURRENCY_MISMATCH,
                    "A payout named a currency other than the merchant's settlement currency");
        } catch (UnknownMerchantException unknown) {
            throw notFound();
        }
        return inOneTransaction(
                unitOfWork ->
                        payouts.find(unitOfWork, initiate.merchant(), initiated.payout())
                                .map(payout -> render(unitOfWork, payout, initiated.status()))
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a judged payout was not found to"
                                                                + " render")));
    }

    /** The row's frozen facts and the status to report — the judged one for an initiation. */
    private PayoutView render(
            Connection unitOfWork, MerchantPayout payout, MerchantPayoutStatus status) {
        String suffix =
                destinations.find(unitOfWork, payout.merchantId(), payout.destinationId())
                        .map(PayoutDestination::displaySuffix)
                        .orElse(null);
        return new PayoutView(
                payout.id().value().toString(),
                status.name(),
                payout.amount().toBigDecimal().toPlainString(),
                payout.amount().currency().code(),
                suffix,
                status == MerchantPayoutStatus.FAILED
                        ? payout.failureReason().map(Enum::name).orElse(null)
                        : null,
                payout.createdAt().toString());
    }

    /**
     * Exact, or the caller's 422 naming the field ({@code INV-MON-03} at the inbound boundary —
     * the refund surface's idiom): never a rounding, and a non-positive amount is refused here
     * so the aggregate's defence-in-depth refusal is never our 500.
     */
    private static Money parsedAmount(String raw, String currencyRaw) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(currencyRaw);
        } catch (IllegalArgumentException unusable) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payout named a currency the platform cannot express amounts in",
                    "'currency' must be an ISO 4217 currency with a minor unit.");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw);
        } catch (NumberFormatException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payout amount was not a decimal number",
                    "'amount' must be a decimal string such as \"25.00\".");
        }
        Money amount;
        try {
            amount = Money.of(decimal, currency);
        } catch (MonetaryException inexact) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payout amount was not representable at the currency's scale",
                    "'amount' is not representable at the currency's scale.");
        }
        if (!amount.isPositive()) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A payout amount was not strictly positive",
                    "'amount' must be strictly positive.");
        }
        return amount;
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

    /** Malformed equals absent — the one 404 ({@code INV-MER-01}'s oracle discipline). */
    private static MerchantId merchantOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static MerchantPayoutId payoutOrAbsent(String rawId) {
        try {
            return MerchantPayoutId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A merchant payout read found nothing at the identifier",
                "the requested resource does not exist.");
    }
}
