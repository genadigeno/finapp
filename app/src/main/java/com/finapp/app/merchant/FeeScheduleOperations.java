package com.finapp.app.merchant;

import com.finapp.merchant.BackdatedFeeScheduleVersionException;
import com.finapp.merchant.FeeCurrencyMismatchException;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleId;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.merchant.UnknownFeeScheduleException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's fee-schedule surface behind the controller (`P6-TSK-004`) — the
 * {@code MerchantOperations} shape: parse and refuse at the boundary, one transaction per
 * command, domain refusals translated to the registered codes.
 *
 * <p><strong>Views render the terms, and nothing else.</strong> A fee schedule is the
 * platform's own pricing; there is no tenant here and no merchant data in any of these
 * responses, which is why {@code INV-MER-01} has no subject in this task. A merchant reading
 * its own pricing is a real capability and it is {@code P6-TSK-009}'s, where it arrives with
 * the tenant predicate its own surface requires.
 */
public class FeeScheduleOperations {

    /** A schedule as an operator sees it listed. */
    public record FeeScheduleView(String feeScheduleId, String name, String currency) {}

    /** One immutable version's terms, exactly as they will price. */
    public record FeeScheduleVersionView(
            String feeScheduleVersionId,
            int version,
            String rate,
            long fixedAmountMinor,
            String currency,
            String roundingPolicy,
            String refundFeePolicy,
            String effectiveFrom) {}

    /** A schedule and every version of it, newest-effective first. */
    public record FeeScheduleDetailView(
            String feeScheduleId,
            String name,
            String currency,
            List<FeeScheduleVersionView> versions) {}

    /** Which schedule prices a merchant. */
    public record MerchantFeeScheduleView(String merchantId, String feeScheduleId) {}

    private final FeeSchedules feeSchedules;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;

    public FeeScheduleOperations(
            FeeSchedules feeSchedules,
            TransactionTemplate transactions,
            DataSource dataSource) {
        this.feeSchedules = Objects.requireNonNull(feeSchedules, "feeSchedules must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    /** Creates a named pricing identity. */
    public FeeScheduleView create(CreateFeeScheduleRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        CurrencyCode currency = CurrencyCode.of(body.currency());
        return renderSchedule(
                inOneTransaction(
                        unitOfWork -> feeSchedules.create(unitOfWork, body.name(), currency)));
    }

    /** Every schedule, newest first. */
    public List<FeeScheduleView> list() {
        return inOneTransaction(
                unitOfWork ->
                        feeSchedules.list(unitOfWork).stream()
                                .map(FeeScheduleOperations::renderSchedule)
                                .toList());
    }

    /** The schedule and its versions, or the one 404 for unknown-and-malformed alike. */
    public FeeScheduleDetailView view(String rawId) {
        FeeScheduleId id = parsedScheduleOrAbsent(rawId);
        try {
            FeeSchedules.ScheduleDetail detail =
                    inOneTransaction(unitOfWork -> feeSchedules.detail(unitOfWork, id));
            return new FeeScheduleDetailView(
                    detail.schedule().id().value().toString(),
                    detail.schedule().name(),
                    detail.schedule().currency().code(),
                    detail.versions().stream()
                            .map(FeeScheduleOperations::renderVersion)
                            .toList());
        } catch (UnknownFeeScheduleException unknown) {
            throw scheduleNotFound();
        }
    }

    /**
     * Adds a version, effective forward. The backdating refusal is the one an operator meets
     * most — "effective from the first of the month", typed on the second.
     */
    public FeeScheduleVersionView addVersion(String rawId, CreateFeeScheduleVersionRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        FeeScheduleId id = parsedScheduleOrAbsent(rawId);
        // Parsed at the boundary: an unknown policy name is the caller's mistake, refused as a
        // 422 rather than surfacing from inside the domain as our 500.
        RoundingPolicy rounding = parsedRounding(body.roundingPolicy());
        RefundFeePolicy refundFee = parsedRefundFee(body.refundFeePolicy());
        try {
            FeeSchedules.NewVersion request =
                    inOneTransaction(
                            unitOfWork -> {
                                // The fixed part's currency is the SCHEDULE's - read here so
                                // the caller never states a currency that can only be wrong.
                                FeeSchedule schedule =
                                        feeSchedules.detail(unitOfWork, id).schedule();
                                return new FeeSchedules.NewVersion(
                                        FeeRate.of(body.rate()),
                                        Money.ofMinorUnits(
                                                body.fixedAmountMinor(), schedule.currency()),
                                        rounding,
                                        refundFee,
                                        java.util.Optional.ofNullable(
                                                body.effectiveFrom()),
                                        body.reason());
                            });
            return renderVersion(
                    inOneTransaction(
                            unitOfWork -> feeSchedules.addVersion(unitOfWork, id, request)));
        } catch (UnknownFeeScheduleException unknown) {
            throw scheduleNotFound();
        } catch (BackdatedFeeScheduleVersionException backdated) {
            throw new ApiException(
                    MerchantErrorCode.FEE_SCHEDULE_NOT_FORWARD,
                    "A fee schedule version would have taken effect in the past",
                    "a fee schedule version takes effect forward; it cannot be backdated.");
        } catch (FeeCurrencyMismatchException mismatch) {
            throw feeCurrencyMismatch();
        } catch (IllegalArgumentException malformed) {
            // FeeRate's own bounds, restated by the request annotations; reachable only if the
            // two ever disagree, and a 422 is the honest answer either way.
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version carried terms the domain refuses",
                    "the fee terms are not valid.");
        }
    }

    /** Points a merchant at a schedule, reasoned; converges when it is already there. */
    public MerchantFeeScheduleView assign(String rawMerchantId, AssignFeeScheduleRequest body) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId merchantId = parsedMerchantOrAbsent(rawMerchantId);
        FeeScheduleId scheduleId = FeeScheduleId.of(body.feeScheduleId());
        try {
            FeeScheduleId assigned =
                    inOneTransaction(
                            unitOfWork ->
                                    feeSchedules.assign(
                                            unitOfWork, merchantId, scheduleId, body.reason()));
            return new MerchantFeeScheduleView(
                    merchantId.value().toString(), assigned.value().toString());
        } catch (UnknownMerchantException unknown) {
            throw merchantNotFound();
        } catch (UnknownFeeScheduleException unknown) {
            throw scheduleNotFound();
        } catch (FeeCurrencyMismatchException mismatch) {
            throw feeCurrencyMismatch();
        }
    }

    /** Which schedule prices this merchant, or the one 404 when none does. */
    public MerchantFeeScheduleView assignmentOf(String rawMerchantId) {
        MerchantId merchantId = parsedMerchantOrAbsent(rawMerchantId);
        return inOneTransaction(
                unitOfWork ->
                        feeSchedules
                                .assignmentOf(unitOfWork, merchantId)
                                .map(
                                        scheduleId ->
                                                new MerchantFeeScheduleView(
                                                        merchantId.value().toString(),
                                                        scheduleId.value().toString()))
                                .orElseThrow(FeeScheduleOperations::scheduleNotFound));
    }

    // -----------------------------------------------------------------

    // NAMED RATHER THAN OVERLOADED, the Money.allocateEvenly/allocateByWeights reason: as
    // two `render` methods these resolved ambiguously through inOneTransaction's type
    // variable, and the compiler was right to refuse - an overload picked by inference is an
    // overload a reader cannot pick by eye.
    private static FeeScheduleView renderSchedule(FeeSchedule schedule) {
        return new FeeScheduleView(
                schedule.id().value().toString(), schedule.name(), schedule.currency().code());
    }

    private static FeeScheduleVersionView renderVersion(FeeScheduleVersion version) {
        return new FeeScheduleVersionView(
                version.id().value().toString(),
                version.version(),
                // A string, never a JSON number: a rate rendered as a floating-point literal
                // is a rate a client may read back inexactly, and this value is the one a
                // merchant reproduces a statement from (INV-MON-01's reasoning at the wire).
                version.rate().toBigDecimal().toPlainString(),
                version.fixed().minorUnits(),
                version.currency().code(),
                version.roundingPolicy().policyName(),
                version.refundFeePolicy().name(),
                version.effectiveFrom().toString());
    }

    private static RoundingPolicy parsedRounding(String name) {
        try {
            return RoundingPolicy.ofName(name);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version named an unknown rounding policy",
                    "the rounding policy is not one this platform knows.");
        }
    }

    private static RefundFeePolicy parsedRefundFee(String name) {
        try {
            return RefundFeePolicy.ofName(name);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A fee schedule version named an unknown refund fee policy",
                    "the refund fee policy is not one this platform knows.");
        }
    }

    /** Malformed equals absent — the one 404. */
    private static FeeScheduleId parsedScheduleOrAbsent(String rawId) {
        try {
            return FeeScheduleId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw scheduleNotFound();
        }
    }

    private static MerchantId parsedMerchantOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw merchantNotFound();
        }
    }

    private static ApiException feeCurrencyMismatch() {
        return new ApiException(
                MerchantErrorCode.FEE_CURRENCY_MISMATCH,
                "A fee schedule was used for a currency it does not price",
                "the fee schedule's currency does not match.");
    }

    private static ApiException scheduleNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A fee schedule read found nothing at the identifier",
                "the requested resource does not exist.");
    }

    private static ApiException merchantNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "A merchant read found nothing at the identifier",
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
