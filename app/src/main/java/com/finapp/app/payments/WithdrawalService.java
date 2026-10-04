package com.finapp.app.payments;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.payments.NoEligibleRailException;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.PaymentsErrorCode;
import com.finapp.payments.TransactionRunner;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalCurrencyMismatchedException;
import com.finapp.payments.WithdrawalId;
import com.finapp.payments.WithdrawalStore;
import com.finapp.payments.WithdrawalUnfundedException;
import com.finapp.payments.Withdrawals;
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
import org.springframework.beans.factory.ObjectProvider;

/**
 * The `/v1/me/withdrawals` slice behind {@link WithdrawalController} (`P7-TSK-008`,
 * ADR-0062 §6) — the boundary's half of the withdrawal: parse exactly, resolve the caller's
 * participants INSIDE the engine's dispatch transaction (the {@code Resolver} runs where the
 * claim lives, so a step-up or ownership refusal rolls the claim back and the key stays
 * unburned — the `P7-TSK-007` idiom, handed to `payments` as a callback because identity is
 * this layer's to see), and translate the engine's refusals onto the error contract.
 *
 * <p>The step-up is the beneficiary pattern (`P4-TSK-007`): {@code MULTI_FACTOR} required
 * exactly of an identity that has an active factor, an authoritative read per decision —
 * money leaving the platform is exactly where an account takeover monetises.
 */
public final class WithdrawalService {

    private final ObjectProvider<Withdrawals> engine;
    private final PaymentParticipants<Connection> participants;
    private final WithdrawalStore<Connection> withdrawals;
    private final MfaEnrolmentStore<Connection> enrolments;
    private final IdentityStore<Connection> identities;
    private final PartyStore<Connection> parties;
    private final TransactionRunner transactions;

    public WithdrawalService(
            ObjectProvider<Withdrawals> engine,
            PaymentParticipants<Connection> participants,
            WithdrawalStore<Connection> withdrawals,
            MfaEnrolmentStore<Connection> enrolments,
            IdentityStore<Connection> identities,
            PartyStore<Connection> parties,
            TransactionRunner transactions) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.participants = Objects.requireNonNull(participants, "participants must not be null");
        this.withdrawals = Objects.requireNonNull(withdrawals, "withdrawals must not be null");
        this.enrolments = Objects.requireNonNull(enrolments, "enrolments must not be null");
        this.identities = Objects.requireNonNull(identities, "identities must not be null");
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
        this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    }

    /** The rendered withdrawal — the caller's own record: status honestly {@code UNKNOWN}
     * when the scheme's answer is missing, the amount their own instruction. */
    public record WithdrawalView(
            String id, String status, String failureReason, String amount, String currency,
            String createdAt) {}

    /**
     * Dispatches (or replays) the caller's withdrawal — {@code 201} with the honest judged
     * status: {@code COMPLETED}, {@code FAILED} with its reason, or {@code UNKNOWN} with
     * the amount still held ({@code INV-LIFE-03}).
     */
    public WithdrawalView withdraw(Session current, WithdrawalRequest body, String idempotencyKey) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(body, "body must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Money amount = parsedAmount(body.amount(), body.currency());
        UUID methodId = parsedOr(body.paymentMethodId(), WithdrawalService::unknownInstrument);
        Withdrawals command = engine.getIfAvailable();
        if (command == null) {
            throw new ApiException(
                    PaymentsErrorCode.PROVIDER_UNAVAILABLE,
                    "A withdrawal arrived on a deployment with no push rail configured");
        }
        Withdrawals.Initiated initiated;
        try {
            initiated =
                    command.withdraw(
                            idempotencyKey,
                            amount,
                            unitOfWork ->
                                    resolved(unitOfWork, current, methodId, amount.currency()));
        } catch (WithdrawalUnfundedException unfunded) {
            throw new ApiException(
                    PaymentsErrorCode.WITHDRAWAL_UNFUNDED,
                    "A withdrawal was refused under the wallet's lock: the available balance"
                            + " cannot cover it");
        } catch (WithdrawalCurrencyMismatchedException mismatched) {
            throw new ApiException(
                    PaymentsErrorCode.WITHDRAWAL_CURRENCY_MISMATCHED,
                    "A withdrawal was refused: it is priced in a currency its wallet does"
                            + " not hold");
        } catch (NoEligibleRailException refused) {
            // The refusal IS recorded - a decision with no chosen rail - and the wallet is
            // untouched, deliberately retryable under a new key after an operator acts
            // (P7-TSK-003's reasoning at the outbound door).
            throw new ApiException(
                    PaymentsErrorCode.NO_ELIGIBLE_RAIL,
                    "A withdrawal was refused: no payment rail can carry it right now");
        }
        return new WithdrawalView(
                initiated.id().value().toString(),
                initiated.status().name(),
                initiated.failureReason().map(Enum::name).orElse(null),
                amount.toBigDecimal().toPlainString(),
                amount.currency().code(),
                initiated.createdAt().toString());
    }

    /** The caller's withdrawal — unknown, not-yours and malformed are one 404. */
    public Optional<WithdrawalView> read(Session current, String rawId) {
        Objects.requireNonNull(current, "current must not be null");
        Objects.requireNonNull(rawId, "rawId must not be null");
        UUID id;
        try {
            id = UUID.fromString(rawId);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        return transactions.inTransaction(
                unitOfWork -> {
                    UUID partyId = partyOf(unitOfWork, current);
                    // The owner, not a wallet (P9-TSK-004): a read keyed by the withdrawal's
                    // id asks whose record it is, and a wallet is per currency - the party's
                    // live ACTIVE customer is the owning principal the record stores.
                    return parties
                            .findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                            .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                            .flatMap(
                                    customer ->
                                            withdrawals.findOwned(
                                                    unitOfWork,
                                                    WithdrawalId.of(id),
                                                    customer.id().value()))
                            .map(WithdrawalService::viewOf);
                });
    }

    // -----------------------------------------------------------------

    /** The engine's resolver: runs inside the dispatch transaction, refusals and all. */
    private Withdrawals.Resolved resolved(
            Connection unitOfWork, Session current, UUID methodId, CurrencyCode currency) {
        UUID partyId = partyOf(unitOfWork, current);
        requireConditionalAssurance(unitOfWork, current);
        PaymentParticipants.Wallet wallet =
                participants
                        .walletOwnedBy(unitOfWork, partyId, currency)
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                PaymentsErrorCode.NO_WALLET,
                                                "A withdrawal was refused: the caller has no"
                                                        + " wallet to withdraw from"));
        return new Withdrawals.Resolved(
                partyId,
                wallet.customerId(),
                wallet.account(),
                wallet.currency(),
                methodId,
                participants
                        .bankDestinationOwnedBy(unitOfWork, partyId, methodId)
                        // Unknown, not-yours, detached and card-kind fold into ONE refusal:
                        // the endpoint is no oracle over instruments (the create door's own
                        // stance).
                        .orElseThrow(WithdrawalService::unknownInstrument));
    }

    private static WithdrawalView viewOf(Withdrawal withdrawal) {
        return new WithdrawalView(
                withdrawal.id().value().toString(),
                withdrawal.status().name(),
                withdrawal.failureReason().map(Enum::name).orElse(null),
                withdrawal.amount().toBigDecimal().toPlainString(),
                withdrawal.amount().currency().code(),
                withdrawal.createdAt().toString());
    }

    /** The `P4-TSK-007` conditional, verbatim: an authoritative read per decision. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor =
                enrolments
                        .findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP)
                        .isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(
                    IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A withdrawal from an MFA-enrolled identity requires a MULTI_FACTOR"
                            + " session");
        }
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities
                .findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "A proven session resolved to no identity;"
                                                + " registration should make this impossible"));
    }

    private static Money parsedAmount(String raw, String currencyRaw) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(currencyRaw);
        } catch (IllegalArgumentException unusable) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A withdrawal named a currency the platform cannot express amounts in",
                    "'currency' must be an ISO 4217 currency with a minor unit.");
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw);
        } catch (NumberFormatException malformed) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A withdrawal amount was not a decimal number",
                    "'amount' must be a decimal string such as \"12.50\".");
        }
        Money amount;
        try {
            amount = Money.of(decimal, currency);
        } catch (MonetaryException inexact) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A withdrawal amount was not representable at the currency's scale",
                    "'amount' is not representable at the currency's scale.");
        }
        if (!amount.isPositive()) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A withdrawal amount was not strictly positive",
                    "'amount' must be strictly positive.");
        }
        return amount;
    }

    private static UUID parsedOr(
            String raw, java.util.function.Supplier<ApiException> refusal) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw refusal.get();
        }
    }

    private static ApiException unknownInstrument() {
        return new ApiException(
                PaymentsErrorCode.UNKNOWN_INSTRUMENT,
                "A withdrawal instrument resolved to no active bank account of the caller's");
    }
}
