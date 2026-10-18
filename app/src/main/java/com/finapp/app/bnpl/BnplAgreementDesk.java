package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAcceptanceEvidence;
import com.finapp.bnpl.BnplAcceptances;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplApplications;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplObligations;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplOffers;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.credit.CreditErrorCode;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.Session;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the BNPL acceptance and the agreement reads (`P12-TSK-013`; {@code PHASE_12_PLAN.md} section 9;
 * lending's {@code LoanAccountDesk}): the acceptance - keyed per principal ({@code bnpl.offer-acceptance:CUSTOMER:<id>}),
 * run in one transaction under the {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt
 * ({@code INV-IDEM-01}: the database committed and the response was lost - the retry answers the same agreement) and a
 * refusal, which writes nothing, rolls the claim back with it - and the owner's reads, each in one {@code REPEATABLE READ}
 * snapshot so an agreement's six balances are one instant's.
 *
 * <p><strong>The acceptance requires a {@code MULTI_FACTOR} session, always</strong> (A18): it creates credit, binds the
 * customer to an agreement and pays the merchant. The evidence it records is the session's own - the identity, the
 * session, its assurance, the socket's address and the request's agent - never the body's. <strong>What the reads
 * show</strong>: the agreement's state, product and plan, the version and its hash, the financed gross, the schedule and
 * the six balances derived from journal lines. Never a score, a reason, a decision's internals, the merchant or the party.
 * Another party's agreement is the same {@code 404} as an unknown or malformed id. The meter is counted after the
 * transaction - a replay counts nothing.
 */
@RequiredArgsConstructor
public final class BnplAgreementDesk {

    static final String ACCEPTANCE_SCOPE = "bnpl.offer-acceptance:";

    @NonNull private final BnplAcceptances acceptances;
    @NonNull private final BnplObligations obligations;
    @NonNull private final IdentityStore<Connection> identities;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplPurchaseMetrics metrics;

    /** What an acceptance answers - and replays: the agreement born, the purchase and checkout's order. */
    public record BnplAcceptedView(
            String agreementId,
            int agreementVersion,
            String purchaseId,
            String orderId,
            String product,
            String plan,
            String status,
            String termsSha256,
            String acceptedAt) {}

    /** One of the customer's agreements, listed. Amounts are exact decimal strings. */
    public record BnplAgreementSummaryView(
            String agreementId,
            String product,
            String plan,
            String status,
            String currency,
            String amount,
            String purchaseDate) {}

    /** The customer's agreements. */
    public record BnplAgreementListView(List<BnplAgreementSummaryView> agreements) {}

    /** An agreement as its customer sees it. */
    public record BnplAgreementView(
            String agreementId,
            int agreementVersion,
            String purchaseId,
            String orderId,
            String product,
            String plan,
            String status,
            String currency,
            String amount,
            String purchaseDate,
            String openedAt,
            String termsSha256,
            BnplBalancesView balances) {}

    /** The obligation's balances, derived from journal lines. */
    public record BnplBalancesView(
            String principalOutstanding,
            String principal,
            String principalDue,
            String interestAccrued,
            String interestDue,
            String feesDue,
            String creditBalance) {}

    /** An agreement's schedule. */
    public record BnplScheduleView(
            String agreementId,
            int scheduleVersion,
            String engine,
            String currency,
            String principal,
            String start,
            List<BnplScheduledInstalmentView> instalments) {}

    /**
     * One scheduled instalment: its projection, whether it has been billed and - once billed (`P12-TSK-015`) - what it
     * billed (principal and pay-in-N's accrued interest, which may differ from the projection), what has been paid of it
     * and what is still due; zeros while unbilled.
     */
    public record BnplScheduledInstalmentView(
            int sequence,
            String dueDate,
            String amount,
            String principal,
            String interest,
            boolean billed,
            String billedPrincipal,
            String billedInterest,
            String paid,
            String due) {}

    /** Accepts the caller's offer - the purchase, the merchant financing and the obligation in one transaction. */
    public BnplAcceptedView accept(Session current, String rawOfferId, String idempotencyKey,
            BnplOfferController.BnplAcceptanceBody body, Optional<String> clientIp, Optional<String> userAgent) {
        BnplOfferId id = offerId(rawOfferId);
        Actor actor = SecurityContext.require();
        Correlation correlation = correlation();
        IdempotencyKey key = new IdempotencyKey(ACCEPTANCE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(String.join("|", "bnpl.offer-acceptance",
                id.value().toString(), body.termsSha256()).getBytes(StandardCharsets.UTF_8));
        BnplAcceptanceEvidence evidence = new BnplAcceptanceEvidence(current.identityId().value(), current.id().value(),
                current.assurance().name(), clientIp, userAgent);
        BnplAcceptances.Acceptance acceptance = new BnplAcceptances.Acceptance(body.termsSha256(), evidence);
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> {
                UUID party = partyOf(unitOfWork, current);
                requireAssurance(current);
                return executor.execute(unitOfWork, key, fingerprint, claimed -> CommandResult.succeeded(
                        stored(acceptances.accept(claimed, id, party, acceptance, actor, correlation))));
            });
        } catch (BnplOffers.OfferNotFound absent) {
            throw notFound("BNPL offer");
        } catch (BnplAcceptances.NotAcceptable past) {
            throw refused(past, BnplPurchaseMetrics.Outcome.NOT_ACCEPTABLE, BnplErrorCode.OFFER_NOT_ACCEPTABLE,
                    "The BNPL offer can no longer be accepted");
        } catch (BnplAcceptances.TermsChanged changed) {
            throw refused(changed, BnplPurchaseMetrics.Outcome.TERMS_CHANGED, BnplErrorCode.TERMS_CHANGED,
                    "The terms accepted are not the BNPL offer's", "; read the offer and accept the terms it shows.");
        } catch (BnplAcceptances.SessionNotPayable notPayable) {
            throw refused(notPayable, BnplPurchaseMetrics.Outcome.SESSION_NOT_PAYABLE, BnplErrorCode.SESSION_NOT_PAYABLE,
                    "The checkout session can no longer be paid by this BNPL offer");
        } catch (BnplAcceptances.MerchantNotEnabled notEnabled) {
            throw refused(notEnabled, BnplPurchaseMetrics.Outcome.MERCHANT_NOT_ENABLED,
                    BnplErrorCode.MERCHANT_NOT_ENABLED, "The merchant does not offer this BNPL product");
        } catch (BnplAcceptances.CapitalUnavailable unavailable) {
            throw refused(unavailable, BnplPurchaseMetrics.Outcome.CAPITAL_UNAVAILABLE, BnplErrorCode.CAPITAL_UNAVAILABLE,
                    "The BNPL purchase cannot be funded now", "; choose another payment method.");
        } catch (BnplAcceptances.InsufficientFunds shortOf) {
            throw refused(shortOf, BnplPurchaseMetrics.Outcome.INSUFFICIENT_FUNDS, BnplErrorCode.INSUFFICIENT_FUNDS,
                    "The wallet cannot fund the first instalment", "; top up the wallet and accept again.");
        } catch (BnplApplications.NotInGoodStanding notEligible) {
            throw new ApiException(CreditErrorCode.APPLICANT_NOT_ELIGIBLE, "The applicant is not eligible");
        }
        BnplAcceptedView accepted =
                replayed(outcome.body().orElseThrow(() -> new IllegalStateException("an acceptance stores its receipt")));
        if (outcome.executed()) {
            metrics.record(BnplProduct.valueOf(accepted.product()), BnplPlan.valueOf(accepted.plan()),
                    BnplPurchaseMetrics.Outcome.FINANCED);
        }
        return accepted;
    }

    /** The caller's agreements, newest first. */
    public BnplAgreementListView list(Session current) {
        return transactions.inSnapshot(unitOfWork -> new BnplAgreementListView(
                obligations.list(unitOfWork, partyOf(unitOfWork, current)).stream().map(BnplAgreementDesk::summary)
                        .toList()));
    }

    /** The caller's own agreement; another party's is the same {@code 404} as an unknown or malformed id. */
    public BnplAgreementView read(Session current, String rawId) {
        BnplAgreementId id = agreementId(rawId);
        return transactions.inSnapshot(unitOfWork -> obligations.read(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(BnplAgreementDesk::view))
                .orElseThrow(() -> notFound("BNPL agreement"));
    }

    /** The caller's own agreement's schedule. */
    public BnplScheduleView schedule(Session current, String rawId) {
        BnplAgreementId id = agreementId(rawId);
        return transactions.inSnapshot(unitOfWork -> obligations.schedule(unitOfWork, id, partyOf(unitOfWork, current))
                        .map(BnplAgreementDesk::schedule))
                .orElseThrow(() -> notFound("BNPL agreement"));
    }

    // ------------------------------------------------------------------ the views

    static BnplAgreementSummaryView summary(BnplPurchaseStore.Owned owned) {
        return new BnplAgreementSummaryView(owned.agreement().id().value().toString(), owned.obligation().product().name(),
                owned.obligation().plan().name(), owned.obligation().status().name(), owned.gross().currency().code(),
                decimal(owned.gross()), owned.obligation().purchaseDate().toString());
    }

    static BnplAgreementView view(BnplObligations.Read read) {
        BnplPurchaseStore.Owned owned = read.owned();
        BnplObligations.Balances balances = read.balances();
        return new BnplAgreementView(
                owned.agreement().id().value().toString(),
                owned.agreement().version(),
                owned.purchase().id().value().toString(),
                owned.purchase().order().map(UUID::toString).orElse(null),
                owned.obligation().product().name(),
                owned.obligation().plan().name(),
                owned.obligation().status().name(),
                owned.gross().currency().code(),
                decimal(owned.gross()),
                owned.obligation().purchaseDate().toString(),
                owned.obligation().openedAt().toString(),
                owned.agreement().termsSha256Hex(),
                new BnplBalancesView(decimal(balances.principalOutstanding()), decimal(balances.principal()),
                        decimal(balances.principalDue()), decimal(balances.interestAccrued()),
                        decimal(balances.interestDue()), decimal(balances.feesDue()), decimal(balances.creditBalance())));
    }

    static BnplScheduleView schedule(BnplObligations.Schedule read) {
        BnplPurchaseStore.StoredSchedule schedule = read.schedule();
        return new BnplScheduleView(read.owned().agreement().id().value().toString(), schedule.version(),
                schedule.engine(), schedule.principal().currency().code(), decimal(schedule.principal()),
                schedule.start().toString(), schedule.instalments().stream()
                        .map(instalment -> instalment(instalment, read.billedItem(instalment.id())))
                        .toList());
    }

    private static BnplScheduledInstalmentView instalment(BnplPurchaseStore.StoredInstalment instalment,
            java.util.Optional<BnplPurchaseStore.BilledItem> billed) {
        Money zero = Money.ofPersisted(0, instalment.amount().currency(), instalment.amount().scale());
        return new BnplScheduledInstalmentView(instalment.sequence(), instalment.dueDate().toString(),
                decimal(instalment.amount()), decimal(instalment.principal()), decimal(instalment.interest()),
                billed.isPresent(),
                decimal(billed.map(BnplPurchaseStore.BilledItem::billedPrincipal).orElse(zero)),
                decimal(billed.map(BnplPurchaseStore.BilledItem::billedInterest).orElse(zero)),
                decimal(billed.map(BnplPurchaseStore.BilledItem::paidTotal).orElse(zero)),
                decimal(billed.map(BnplPurchaseStore.BilledItem::dueTotal).orElse(zero)));
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    /** An acceptance's recorded receipt. */
    static StoredResponse stored(BnplAcceptances.Accepted accepted) {
        String body = String.join("|", "OK", accepted.agreement().id().value().toString(),
                Integer.toString(accepted.agreement().version()), accepted.purchase().id().value().toString(),
                accepted.order().toString(), accepted.purchase().product().name(), accepted.purchase().plan().name(),
                accepted.purchase().status().name(), accepted.agreement().termsSha256Hex(),
                accepted.acceptedAt().toString());
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplAcceptedView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplAcceptedView(fields[1], Integer.parseInt(fields[2]), fields[3], fields[4], fields[5], fields[6],
                fields[7], fields[8], fields[9]);
    }

    // ------------------------------------------------------------------ plumbing

    private ApiException refused(BnplAcceptances.Refused refusal, BnplPurchaseMetrics.Outcome outcome,
            BnplErrorCode code, String title) {
        return refused(refusal, outcome, code, title, ".");
    }

    private ApiException refused(BnplAcceptances.Refused refusal, BnplPurchaseMetrics.Outcome outcome,
            BnplErrorCode code, String title, String advice) {
        metrics.record(refusal.product(), refusal.plan(), outcome);
        return new ApiException(code, title, refusal.getMessage() + advice);
    }

    /** An acceptance's assurance (A18): a {@code MULTI_FACTOR} session, always - enrolled or not. */
    private static void requireAssurance(Session current) {
        if (!current.assurance().atLeast(AssuranceLevel.MULTI_FACTOR)) {
            throw new ApiException(IdentityErrorCode.ASSURANCE_REQUIRED,
                    "A BNPL offer acceptance requires a MULTI_FACTOR session");
        }
    }

    private static BnplOfferId offerId(String raw) {
        try {
            return BnplOfferId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound("BNPL offer");
        }
    }

    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound("BNPL agreement");
        }
    }

    private static ApiException notFound(String what) {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No " + what + " matches the requested identifier",
                "no such " + what + ".");
    }

    private UUID partyOf(Connection unitOfWork, Session current) {
        return identities.findById(unitOfWork, current.identityId())
                .map(identity -> identity.partyId())
                .orElseThrow(() -> new IllegalStateException(
                        "A proven session resolved to no identity; registration should make this impossible"));
    }

    private static Correlation correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL acceptance runs inside a correlation scope"));
    }
}
