package com.finapp.app.lending;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanOffer;
import com.finapp.lending.LoanOfferId;
import com.finapp.lending.LoanOffers;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.SchedulePeriod;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the loan offer doors (`P11-TSK-011`): the owner's read and the decline - keyed per principal
 * ({@code lending.offer-decline:CUSTOMER:<id>}), run in one transaction under the {@link IdempotentExecutor} claim, so a
 * retried key replays the recorded receipt and a refusal, which writes nothing, rolls the claim back with it. The machine
 * and the clock-sided edges are the domain's and {@code lending V005}'s.
 *
 * <p><strong>The decline keeps the platform's conditional step-up</strong> (the withdrawal's precedent): an identity with
 * an active factor declines from a {@code MULTI_FACTOR} session - declining moves no money and commits nothing.
 * <strong>What the read shows</strong>: exactly the offer's stored terms - the approved amount and term, the rate, the
 * loan's illustrative schedule labelled as such with its totals (A5) or the line's minimum-payment rule, the expiry, the
 * template and the two hashes, and the document whose bytes were hashed. Never a score, an attribute, a reason code, a
 * decision's internals or the party.
 */
@RequiredArgsConstructor
public final class LoanOfferDesk {

    static final String DECLINE_SCOPE = "lending.offer-decline:";

    /** What the illustrative schedule is, said to the customer in the response itself (A5). */
    static final String ILLUSTRATIVE =
            "ILLUSTRATIVE - computed as if interest started on the offering day; the schedule starts at payout";

    @NonNull private final LoanOffers offers;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final LoanOfferMetrics metrics;

    /** An offer as its customer sees it. Amounts and the rate are exact decimal strings. */
    public record LoanOfferView(
            String offerId,
            String applicationId,
            String product,
            String status,
            String currency,
            String amount,
            Integer termMonths,
            String nominalAnnualRate,
            int repaymentDay,
            int termsVersion,
            String offeredAt,
            String expiresAt,
            String closedAt,
            IllustrationView illustration,
            MinimumPaymentView minimumPayment,
            String agreementTemplate,
            int agreementTemplateVersion,
            String termsSha256,
            String documentSha256,
            String document) {}

    /** A loan offer's illustrative schedule (A5) - labelled, with its totals and its periods. */
    public record IllustrationView(
            String basis,
            String start,
            String firstDueDate,
            String instalment,
            String finalInstalment,
            String totalInterest,
            String totalPayable,
            List<PeriodView> periods) {}

    /** One illustrative instalment. */
    public record PeriodView(
            int sequence, String dueDate, String instalment, String interest, String principal, String closingBalance) {}

    /** A credit line offer's minimum-payment rule (A23, A24). */
    public record MinimumPaymentView(String floor, int ratioBasisPoints, int paymentDueDays) {}

    /** What a decline answers - and replays. */
    public record LoanOfferDecision(String offerId, String product, String status, String closedAt) {}

    /** The caller's own offer; another party's is the same {@code 404} as an unknown or malformed id. */
    public LoanOfferView read(Session current, String rawId) {
        LoanOfferId id = offerId(rawId);
        return transactions.inTransaction(unitOfWork -> offers.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(LoanOfferDesk::view))
                .orElseThrow(LoanOfferDesk::notFound);
    }

    public LoanOfferDecision decline(Session current, String rawId, String idempotencyKey) {
        LoanOfferId id = offerId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(DECLINE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("lending.offer-decline|" + id.value()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(offers.decline(claimed, id, party, actor, correlation))));
            });
        } catch (LoanOffers.OfferNotFound absent) {
            throw notFound();
        } catch (LoanOffers.OfferNotAcceptable past) {
            metrics.record(past.product(), LoanOfferMetrics.Outcome.NOT_ACCEPTABLE);
            throw new ApiException(LendingErrorCode.OFFER_NOT_ACCEPTABLE, "The offer can no longer be declined",
                    past.getMessage() + ".");
        }
        LoanOfferDecision decision =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a decline stores its receipt")));
        if (outcome.executed()) {
            metrics.record(LoanProduct.valueOf(decision.product()), LoanOfferMetrics.Outcome.DECLINED);
        }
        return decision;
    }

    // ------------------------------------------------------------------ the view

    static LoanOfferView view(LoanOffers.Read read) {
        LoanOffer offer = read.offer();
        LoanOffer.Terms terms = offer.terms();
        return new LoanOfferView(
                offer.id().value().toString(),
                offer.application().value().toString(),
                offer.product().name(),
                offer.status().name(),
                terms.amount().currency().code(),
                decimal(terms.amount()),
                terms.termMonths().orElse(null),
                terms.nominalAnnualRate().toPlainString(),
                terms.repaymentDay(),
                offer.termsVersion(),
                offer.offeredAt().toString(),
                offer.expiresAt().toString(),
                offer.closedAt().map(Instant::toString).orElse(null),
                terms.illustration().map(illustration -> new IllustrationView(ILLUSTRATIVE,
                        illustration.start().toString(), illustration.firstDueDate().toString(),
                        decimal(illustration.instalment()), decimal(illustration.finalInstalment()),
                        decimal(illustration.totalInterest()), decimal(illustration.totalPayable()),
                        read.illustrativePeriods().stream().map(LoanOfferDesk::period).toList())).orElse(null),
                terms.minimumPayment().map(rule -> new MinimumPaymentView(decimal(rule.floor()), rule.ratioBps(),
                        rule.paymentDueDays())).orElse(null),
                terms.template().id(),
                terms.template().version(),
                terms.termsSha256Hex(),
                terms.documentSha256Hex(),
                read.document());
    }

    private static PeriodView period(SchedulePeriod period) {
        return new PeriodView(period.sequence(), period.dueDate().toString(), decimal(period.amount()),
                decimal(period.interest()), decimal(period.principal()), decimal(period.closingBalance()));
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** A decline's recorded receipt: the offer as it stood then. */
    private static StoredResponse stored(LoanOffer offer) {
        String body = String.join("|", "OK", offer.id().value().toString(), offer.product().name(),
                offer.status().name(), offer.closedAt().map(Instant::toString).orElse(""));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static LoanOfferDecision replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new LoanOfferDecision(fields[1], fields[2], fields[3], fields[4].isEmpty() ? null : fields[4]);
    }

    // ------------------------------------------------------------------ plumbing

    /** A decline's assurance: the platform's conditional step-up. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A loan offer decline from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    private static LoanOfferId offerId(String raw) {
        try {
            return LoanOfferId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan offer matches the requested identifier",
                "no such loan offer.");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan offer command runs inside a correlation scope"))
                .correlationId();
    }
}
