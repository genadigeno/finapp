package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.credit.CreditErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.lending.CreditLineDraw;
import com.finapp.lending.CreditLineDraws;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanAcceptances;
import com.finapp.lending.LoanApplications;
import com.finapp.lending.LoanId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the draw door (`P11-TSK-021`; {@code PHASE_11_PLAN.md} sections 9 and 11, ADR-0100 point 3):
 * keyed per principal ({@code lending.draw:CUSTOMER:<id>}), run in one transaction under the {@link IdempotentExecutor}
 * claim - a retried key replays the recorded receipt, and a refusal, which writes nothing, rolls the claim back with it.
 *
 * <p><strong>A draw requires a {@code MULTI_FACTOR} session, always</strong> (A19): it moves money into the wallet against
 * the platform's capital - the acceptance's rule, enrolled or not. The customer names the amount and its currency; the
 * line's own currency and scale are the domain's to judge. <strong>No operator can draw</strong>: the door is
 * {@code /v1/me}, the line the session's own party's (owner-scoped in the lock's statement).
 */
@RequiredArgsConstructor
public final class CreditLineDrawDesk {

    static final String DRAW_SCOPE = "lending.draw:";

    @NonNull private final CreditLineDraws draws;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final CreditLineDrawMetrics metrics;

    /** What a draw answers - and replays. Amounts are exact decimal strings, the value date ISO. */
    public record CreditLineDrawView(
            String drawId,
            String loanId,
            String amount,
            String currency,
            String valueDate,
            int agreementVersion,
            String drawnAt) {}

    /** Draws on the caller's own line to their wallet: the posting, the draw and its event in one transaction. */
    public CreditLineDrawView draw(
            Session current, String rawLoanId, String idempotencyKey, LoanController.CreditLineDrawBody body) {
        LoanId id = loanId(rawLoanId);
        Money amount = amount(body);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(DRAW_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "lending.draw",
                        id.value().toString(), Long.toString(amount.minorUnits()), Integer.toString(amount.scale()),
                        amount.currency().code())
                .getBytes(StandardCharsets.UTF_8));
        CreditLineDraws.Request request = new CreditLineDraws.Request(idempotencyKey, amount);
        AtomicBoolean converged = new AtomicBoolean();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> {
                    CreditLineDraws.Drawn drawn = draws.draw(claimed, id, party, request, actor, correlation);
                    converged.set(drawn.replayed());
                    return CommandResult.succeeded(stored(drawn.draw()));
                });
            });
        } catch (CreditLineDraws.LineNotFound absent) {
            throw notFound();
        } catch (CreditLineDraws.DrawKeyReused reused) {
            throw new ApiException(PlatformErrorCode.CONFLICT, "The key was already used",
                    "This " + IdempotencyKeyHeader.NAME + " was already used for a different request. Use a new key for"
                            + " a new action, or resend the original request unchanged.");
        } catch (CreditLineDraws.AmountInvalid invalid) {
            throw invalid("the amount must be a positive amount in the line's currency, at its minor units.");
        } catch (CreditLineDraws.LineNotDrawable notDrawable) {
            metrics.record(CreditLineDrawMetrics.Outcome.NOT_DRAWABLE);
            throw new ApiException(LendingErrorCode.LINE_NOT_DRAWABLE, "The account cannot be drawn",
                    notDrawable.getMessage() + ".");
        } catch (CreditLineDraws.DrawsSuspended suspended) {
            metrics.record(CreditLineDrawMetrics.Outcome.SUSPENDED);
            throw new ApiException(LendingErrorCode.DRAWS_SUSPENDED, "The line's draws are suspended",
                    suspended.getMessage() + " while an amount is past due or the line is in default.");
        } catch (CreditLineDraws.LimitExceeded exceeded) {
            metrics.record(CreditLineDrawMetrics.Outcome.LIMIT_EXCEEDED);
            throw new ApiException(LendingErrorCode.LIMIT_EXCEEDED, "The draw exceeds the available limit",
                    exceeded.getMessage() + "; read the line for its available limit.");
        } catch (LoanAcceptances.CapitalUnavailable unavailable) {
            metrics.record(CreditLineDrawMetrics.Outcome.CAPITAL_UNAVAILABLE);
            throw new ApiException(LendingErrorCode.CAPITAL_UNAVAILABLE, "The draw cannot be funded now",
                    "there is not enough recognised lending capital for this draw.");
        } catch (CreditLineDraws.WalletUnavailable unavailable) {
            metrics.record(CreditLineDrawMetrics.Outcome.WALLET_UNAVAILABLE);
            throw new ApiException(LendingErrorCode.WALLET_UNAVAILABLE, "The wallet cannot receive the draw now",
                    unavailable.getMessage() + ".");
        } catch (LoanApplications.NotInGoodStanding notEligible) {
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        }
        CreditLineDrawView drawn =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a draw stores its receipt")));
        if (outcome.executed() && !converged.get()) {
            metrics.record(CreditLineDrawMetrics.Outcome.DRAWN);
        }
        return drawn;
    }

    // ------------------------------------------------------------------ the receipt

    private static StoredResponse stored(CreditLineDraw draw) {
        String body = String.join("|", "OK", draw.id().toString(), draw.loan().value().toString(),
                draw.amount().toBigDecimal().toPlainString(), draw.amount().currency().code(), draw.valueDate().toString(),
                Integer.toString(draw.agreementVersion()), draw.drawnAt().toString());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static CreditLineDrawView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new CreditLineDrawView(fields[1], fields[2], fields[3], fields[4], fields[5], Integer.parseInt(fields[6]),
                fields[7]);
    }

    // ------------------------------------------------------------------ plumbing

    /** A draw's assurance (A19): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireAssurance(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED, "A credit line draw requires a MULTI_FACTOR session");
        }
    }

    /** The body's amount in its named currency, exact at the currency's minor units - the line's are the domain's. */
    private static Money amount(LoanController.CreditLineDrawBody body) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(body.currency());
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            // The shape first: an exponent never reaches Money.of's rescale.
            return Money.of(DecimalText.parse(body.amount()), currency);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan matches the requested identifier", "no such loan.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The draw was not valid", detail);
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan command runs inside a correlation scope"))
                .correlationId();
    }
}
