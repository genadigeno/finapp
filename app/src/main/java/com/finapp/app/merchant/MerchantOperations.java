package com.finapp.app.merchant;

import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantErrorCode;
import com.finapp.merchant.MerchantNotEligibleException;
import com.finapp.merchant.MerchantOnboarding;
import com.finapp.merchant.IllegalMerchantTransitionException;
import com.finapp.merchant.UnknownMerchantException;
import com.finapp.merchant.UnsupportedSettlementCurrencyException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's merchant surface behind the controller (`P6-TSK-003`) — the
 * {@code LedgerAdjustments} shape: parse and refuse at the boundary, one transaction per
 * command, domain refusals translated to the registered codes, views carrying identifiers
 * and enumerated names and never another tenant's anything.
 */
@RequiredArgsConstructor
public class MerchantOperations {

    /** The onboarding response: the merchant, its status — never the party's standing. */
    public record MerchantView(
            String merchantId,
            String displayName,
            String settlementCurrency,
            String status) {}

    @NonNull private final MerchantOnboarding onboarding;
    @NonNull private final MerchantAdministration administration;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** Onboards, or replays the recorded outcome for a retried key ({@code INV-IDEM-01}). */
    public MerchantView onboard(OnboardMerchantRequest body, String idempotencyKey) {
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        MerchantOnboarding.OnboardMerchantCommand command =
                new MerchantOnboarding.OnboardMerchantCommand(
                        idempotencyKey,
                        body.partyId(),
                        body.legalName(),
                        body.displayName(),
                        CurrencyCode.of(body.settlementCurrency()));
        try {
            MerchantOnboarding.OnboardingResult result =
                    inOneTransaction(unitOfWork -> onboarding.onboard(unitOfWork, command));
            return inOneTransaction(
                    unitOfWork ->
                            administration
                                    .find(unitOfWork, result.merchantId())
                                    .map(MerchantOperations::render)
                                    .orElseThrow(MerchantOperations::merchantNotFound));
        } catch (MerchantNotEligibleException refused) {
            throw new ApiException(
                    MerchantErrorCode.NOT_ELIGIBLE,
                    "An onboarding was refused by the eligibility gate",
                    "the party cannot be onboarded as a merchant.");
        } catch (UnsupportedSettlementCurrencyException refused) {
            throw new ApiException(
                    MerchantErrorCode.UNSUPPORTED_CURRENCY,
                    "An onboarding named a currency the chart does not serve",
                    "the settlement currency is not supported.");
        }
    }

    /** {@code ACTIVE → SUSPENDED}, reasoned; converges on an already-suspended merchant. */
    public MerchantView suspend(String rawId, MerchantStandingRequest body) {
        return move(rawId, body, administration::suspend);
    }

    /** {@code SUSPENDED → ACTIVE}, reasoned; converges on an already-active merchant. */
    public MerchantView reinstate(String rawId, MerchantStandingRequest body) {
        return move(rawId, body, administration::reinstate);
    }

    /** {@code ACTIVE → CLOSED}, terminal, reasoned. */
    public MerchantView close(String rawId, MerchantStandingRequest body) {
        return move(rawId, body, administration::close);
    }

    /** The merchant as it stands, or the one 404 for unknown-and-malformed alike. */
    public MerchantView view(String rawId) {
        MerchantId id = parsedOrAbsent(rawId);
        return inOneTransaction(
                unitOfWork ->
                        administration
                                .find(unitOfWork, id)
                                .map(MerchantOperations::render)
                                .orElseThrow(MerchantOperations::merchantNotFound));
    }

    private MerchantView move(String rawId, MerchantStandingRequest body, Move command) {
        Objects.requireNonNull(body, "body must not be null");
        MerchantId id = parsedOrAbsent(rawId);
        try {
            return render(
                    inOneTransaction(
                            unitOfWork -> command.apply(unitOfWork, id, body.reason())));
        } catch (UnknownMerchantException unknown) {
            throw merchantNotFound();
        } catch (IllegalMerchantTransitionException refused) {
            throw new ApiException(
                    MerchantErrorCode.ILLEGAL_TRANSITION,
                    "A merchant standing move was refused by the machine",
                    "the merchant's current status does not permit this change.");
        }
    }

    private static MerchantView render(Merchant merchant) {
        return new MerchantView(
                merchant.id().value().toString(),
                merchant.displayName(),
                merchant.settlementCurrency().code(),
                merchant.status().name());
    }

    /** Malformed equals absent — the one 404 ({@code INV-MER-01}'s oracle discipline). */
    private static MerchantId parsedOrAbsent(String rawId) {
        try {
            return MerchantId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw merchantNotFound();
        }
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

    private interface Move {
        Merchant apply(Connection unitOfWork, MerchantId id, String reason);
    }
}
