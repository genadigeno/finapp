package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplIllustration;
import com.finapp.bnpl.BnplOffer;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOffers;
import com.finapp.bnpl.BnplOptions;
import com.finapp.bnpl.BnplProduct;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.MfaFactorType;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
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
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the BNPL offer doors and the checkout's BNPL options (`P12-TSK-011`; lending's
 * {@code LoanOfferDesk}): the owner's read, the decline - keyed per principal ({@code bnpl.offer-decline:CUSTOMER:<id>}),
 * run in one transaction under the {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt and a
 * refusal, which writes nothing, rolls the claim back with it - and the options of the session a customer's token opens.
 * The machine and the clock-sided edges are the domain's and {@code bnpl V004}'s.
 *
 * <p><strong>The decline keeps the platform's conditional step-up</strong> (the withdrawal's precedent): declining moves
 * no money and commits nothing. <strong>What the read shows</strong>: exactly the offer's stored terms - the purchase
 * amount, the plan, the rate, the illustrative schedule labelled as such with its totals, the expiry, the template and the
 * two hashes, and the document whose bytes were hashed. Never a score, an attribute, a reason code, a decision's internals,
 * the session's token or the party. <strong>The options</strong> name no eligibility: they are what the products and the
 * merchant offer for the session's gross, illustrated, and nothing of the customer's is read.
 */
@RequiredArgsConstructor
public final class BnplOfferDesk {

    static final String DECLINE_SCOPE = "bnpl.offer-decline:";

    /** What the illustrative schedule is, said to the customer in the response itself. */
    static final String ILLUSTRATIVE =
            "ILLUSTRATIVE - as if purchased on the start date; the real schedule starts on the purchase's own day";

    @NonNull private final BnplOffers offers;
    @NonNull private final BnplOptions options;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final MfaEnrolmentStore<Connection> enrolments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplOriginationMetrics metrics;

    /** An offer as its customer sees it. Amounts and the rate are exact decimal strings. */
    public record BnplOfferView(
            String offerId,
            String applicationId,
            String product,
            String plan,
            String status,
            String currency,
            String amount,
            String nominalAnnualRate,
            int termsVersion,
            String offeredAt,
            String expiresAt,
            String closedAt,
            BnplIllustrationView illustration,
            String agreementTemplate,
            int agreementTemplateVersion,
            String termsSha256,
            String documentSha256,
            String document) {}

    /** An illustrative schedule - labelled, with its totals and its instalments. */
    public record BnplIllustrationView(
            String basis,
            String engine,
            String start,
            String firstDueDate,
            String lastDueDate,
            int instalmentCount,
            String firstInstalment,
            String regularInstalment,
            String finalInstalment,
            String totalInterest,
            String totalPayable,
            List<BnplInstalmentView> instalments) {}

    /** One illustrative instalment. */
    public record BnplInstalmentView(int sequence, String dueDate, String amount, String principal, String interest) {}

    /** What a decline answers - and replays. */
    public record BnplOfferDecision(String offerId, String product, String status, String closedAt) {}

    /** The options of a checkout session: each offered product, its terms version and one illustration per plan. */
    public record BnplOptionsView(List<BnplProductOptionView> products) {}

    /** One product's options. */
    public record BnplProductOptionView(String product, int termsVersion, List<BnplPlanOptionView> plans) {}

    /** One plan's illustration. */
    public record BnplPlanOptionView(String plan, BnplIllustrationView illustration) {}

    /** The caller's own offer; another party's is the same {@code 404} as an unknown or malformed id. */
    public BnplOfferView read(Session current, String rawId) {
        BnplOfferId id = offerId(rawId);
        return transactions.inTransaction(unitOfWork -> offers.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(BnplOfferDesk::view))
                .orElseThrow(BnplOfferDesk::notFound);
    }

    public BnplOfferDecision decline(Session current, String rawId, String idempotencyKey) {
        BnplOfferId id = offerId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(DECLINE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(("bnpl.offer-decline|" + id.value()).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireConditionalAssurance(unitOfWork, current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(offers.decline(claimed, id, party, actor, correlation))));
            });
        } catch (BnplOffers.OfferNotFound absent) {
            throw notFound();
        } catch (BnplOffers.OfferNotAcceptable past) {
            metrics.record(past.product(), BnplOriginationMetrics.Outcome.NOT_ACCEPTABLE);
            throw new ApiException(BnplErrorCode.OFFER_NOT_ACCEPTABLE, "The BNPL offer can no longer be declined",
                    past.getMessage() + ".");
        }
        BnplOfferDecision decision =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a decline stores its receipt")));
        if (outcome.executed()) {
            metrics.record(BnplProduct.valueOf(decision.product()), BnplOriginationMetrics.Outcome.DECLINED);
        }
        return decision;
    }

    /**
     * The options of the session the token opens - a read, unkeyed; an unknown, closed or expired session is one
     * {@code 422 bnpl.SessionNotEligible}, the token never answered.
     */
    public BnplOptionsView options(BnplOptionsController.BnplOptionsBody body) {
        if (body == null || body.sessionToken() == null) {
            throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The BNPL options request was not valid",
                    "sessionToken is required.");
        }
        try {
            return transactions.inTransaction(unitOfWork -> new BnplOptionsView(
                    options.of(unitOfWork, body.sessionToken()).stream().map(BnplOfferDesk::option).toList()));
        } catch (BnplApplications.SessionNotEligible notEligible) {
            throw new ApiException(BnplErrorCode.SESSION_NOT_ELIGIBLE, "The checkout session is not eligible for BNPL",
                    notEligible.getMessage() + ".");
        }
    }

    // ------------------------------------------------------------------ the views

    static BnplOfferView view(BnplOffers.Read read) {
        BnplOffer offer = read.offer();
        return new BnplOfferView(
                offer.id().value().toString(),
                offer.application().value().toString(),
                offer.product().name(),
                offer.plan().name(),
                offer.status().name(),
                read.terms().amount().currency().code(),
                decimal(read.terms().amount()),
                read.terms().nominalAnnualRate().toPlainString(),
                offer.termsVersion(),
                offer.offeredAt().toString(),
                offer.expiresAt().toString(),
                offer.closedAt().map(Instant::toString).orElse(null),
                illustration(read.illustration()),
                read.template().id(),
                read.template().version(),
                read.terms().termsSha256Hex(),
                read.terms().documentSha256Hex(),
                read.document());
    }

    static BnplIllustrationView illustration(BnplIllustration illustration) {
        return new BnplIllustrationView(ILLUSTRATIVE, illustration.engine(), illustration.start().toString(),
                illustration.firstDueDate().toString(), illustration.lastDueDate().toString(),
                illustration.instalmentCount(), decimal(illustration.firstInstalment()),
                decimal(illustration.regularInstalment()), decimal(illustration.finalInstalment()),
                decimal(illustration.totalInterest()), decimal(illustration.totalPayable()),
                illustration.lines().stream().map(line -> new BnplInstalmentView(line.sequence(), line.dueDate().toString(),
                        decimal(line.amount()), decimal(line.principal()), decimal(line.interest()))).toList());
    }

    private static BnplProductOptionView option(BnplOptions.ProductOption option) {
        return new BnplProductOptionView(option.product().name(), option.termsVersion(), option.plans().stream()
                .map(plan -> new BnplPlanOptionView(plan.plan().name(), illustration(plan.illustration()))).toList());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** A decline's recorded receipt: the offer as it stood then. */
    static StoredResponse stored(BnplOffer offer) {
        String body = String.join("|", "OK", offer.id().value().toString(), offer.product().name(),
                offer.status().name(), offer.closedAt().map(Instant::toString).orElse(""));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplOfferDecision replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplOfferDecision(fields[1], fields[2], fields[3], fields[4].isEmpty() ? null : fields[4]);
    }

    // ------------------------------------------------------------------ plumbing

    /** A decline's assurance: the platform's conditional step-up. */
    private void requireConditionalAssurance(Connection unitOfWork, Session current) {
        boolean hasFactor = enrolments.findActive(unitOfWork, current.identityId(), MfaFactorType.TOTP).isPresent();
        if (hasFactor && !current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A BNPL offer decline from an MFA-enrolled identity requires a MULTI_FACTOR session");
        }
    }

    private static BnplOfferId offerId(String raw) {
        try {
            return BnplOfferId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL offer matches the requested identifier",
                "no such BNPL offer.");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL offer command runs inside a correlation scope"))
                .correlationId();
    }
}
