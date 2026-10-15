package com.finapp.lending;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The customer's acceptance of a loan offer - T-acc (`P11-TSK-012`; {@code PHASE_11_PLAN.md} sections 5.2, 5.3, 7.1,
 * 7.2, 11, 12.9 and 12.10, {@code LENDING_LIFECYCLES.md} sections 4.4 to 4.6; ADR-0091 point 4, ADR-0092 points 5 and 6,
 * ADR-0096 section 5; {@code INV-LND-05}, {@code INV-LND-06}, {@code INV-LND-13}, {@code INV-CRD-09},
 * {@code INV-IDEM-01}, {@code INV-IDN-04}). The contract committed, the decision consumed, the exposure and - for a loan -
 * the capital committed and the account opened: <strong>one transaction</strong>, on the caller's unit of work, which the
 * door's idempotency claim ({@code lending.offer-acceptance:CUSTOMER:<id>}) wraps - a retried key replays, and every
 * refusal is thrown before or instead of the commit, so it writes nothing and rolls the claim back with it.
 *
 * <h2>The order, which is the lock order (L0, L2, L6)</h2>
 *
 * <ol>
 *   <li><strong>(L0)</strong> the party's credit profile {@code FOR UPDATE} through {@link LoanCreditDecisions#lockExposure}
 *       - FIRST, before any lending row: the acceptance moves the party's exposure from credit's reservation to lending's
 *       commitment, and a credit decision for the same party takes the same row first, so the two serialise and no
 *       instant sees neither the reservation nor the commitment ({@code INV-LND-06}, {@code INV-CRD-09});
 *   <li><strong>(L2)</strong> the offer {@code FOR UPDATE}, owner-scoped; acted on only while {@code OFFERED} and, on the
 *       database clock read after the lock, unexpired - and the loan's birth re-judges the expiry on its own statement's
 *       clock, so an offer that expires during the waits below is refused {@code OfferNotAcceptable} there too
 *       ({@code lending V026}'s {@code LNEXP}, `P11-TSK-034`);
 *   <li>the echoed terms hash compared with the offer's ({@code TermsChanged}); the payout destination verified
 *       ({@code PayoutDestinationInvalid}); the party's standing re-read ({@code NotInGoodStanding}) - every refusal
 *       before any write;
 *   <li>credit's decision consumed for the agreement - credit re-takes L0 (re-entrant) and judges the lapse on its own
 *       clock read under it; any answer but {@code Consumed} is {@code OfferNotAcceptable};
 *   <li><strong>(L6, a loan only)</strong> the currency's {@code LENDING_CAPITAL} row {@code FOR UPDATE} and headroom
 *       &ge; principal ({@code CapitalUnavailable}) - a line commits no capital: its principal arrives by draws (A28);
 *   <li>then only inserts: the loan, its birth in its history, agreement version 1 (the offer's terms copied), the
 *       evidence, a loan's disbursement {@code PENDING}, the six ledger accounts; the offer {@code ACCEPTED} (conditional
 *       on its clock again - the expiry's complement) and its history; {@code lending.LoanAccepted}; the audit record.
 * </ol>
 *
 * <p>No lock is taken out of that order and none is held across anything but the database: the acceptance never locks
 * the application (it reads it), and nothing that holds L2 or L6 ever waits on L0. No money moves: the first posting is
 * the disbursement's (`P11-TSK-014`).
 */
@RequiredArgsConstructor
public final class LoanAcceptances {

    public static final String ACCEPTED_EVENT = "lending.LoanAccepted";
    public static final String LOAN_AGGREGATE_TYPE = "loan";
    public static final String ACCEPTED_REASON = "CUSTOMER_ACCEPTED";

    @NonNull private final LoanOfferStore offers;
    @NonNull private final LoanApplicationStore applications;
    @NonNull private final LoanStore loans;
    @NonNull private final LoanCreditDecisions credit;
    @NonNull private final LoanPartyStanding standing;
    @NonNull private final LoanPayoutDestinations destinations;
    @NonNull private final LendingCapitalHeadroom headroom;
    @NonNull private final LoanAccounts accounts;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * What the customer sends: the terms hash they accept - the offer's, in lowercase hexadecimal, as the offer read
     * showed it - the payout method of path X when the application preferred an external account, and how they
     * accepted.
     */
    public record Acceptance(String termsSha256, Optional<UUID> payoutMethod, AcceptanceEvidence evidence) {

        public Acceptance {
            Objects.requireNonNull(termsSha256, "termsSha256 must not be null");
            Objects.requireNonNull(payoutMethod, "payoutMethod must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }

        @Override
        public String toString() {
            return "Acceptance[redacted]";
        }
    }

    /** What the acceptance committed - the loan, its agreement, the instant, its six accounts. */
    public record Accepted(Loan loan, LoanAgreement agreement, Instant acceptedAt, LoanLedgerAccounts ledgerAccounts) {

        public Accepted {
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
            Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        }

        @Override
        public String toString() {
            return "Accepted[" + loan + "]";
        }
    }

    /**
     * Accepts {@code party}'s offer {@code id}, on the caller's unit of work.
     *
     * @throws LoanOffers.OfferNotFound the party has no offer with this id (another party's included)
     * @throws LoanOffers.OfferNotAcceptable it is no longer {@code OFFERED}, has expired on the database clock, or credit
     *     would not consume its decision
     * @throws TermsChanged the echoed hash is not the offer's
     * @throws PayoutDestinationInvalid the destination is missing, unexpected or unverified
     * @throws LoanApplications.NotInGoodStanding the party is not a verified customer in good standing now
     * @throws CapitalUnavailable a loan whose principal the currency's recognised capital cannot cover
     */
    public Accepted accept(
            Connection unitOfWork, LoanOfferId id, UUID party, Acceptance acceptance, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");

        // (L0) The party's exposure, first.
        boolean profiled = credit.lockExposure(unitOfWork, party);
        // (L2) The offer, owner-scoped.
        LoanOffer offer = offers.lockOwnedBy(unitOfWork, id, party).orElseThrow(LoanOffers.OfferNotFound::new);
        if (!profiled) {
            throw new IllegalStateException("an offered party has a credit profile: its decision was made under it");
        }
        if (offer.status() != LoanOfferStatus.OFFERED || !offer.expiresAt().isAfter(offers.databaseNow(unitOfWork))) {
            throw LoanOffers.OfferNotAcceptable.of(offer);
        }
        if (!sameHash(acceptance.termsSha256(), offer.terms().termsSha256())) {
            throw new TermsChanged(offer.product());
        }
        Optional<UUID> payout = payoutDestination(unitOfWork, offer, party, acceptance.payoutMethod());
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new LoanApplications.NotInGoodStanding();
        }

        LoanAgreementId agreementId = LoanAgreementId.next(ids);
        switch (credit.consume(unitOfWork, offer.decision(), agreementId)) {
            case LoanCreditDecisions.Consumption.Refused refused ->
                    throw LoanOffers.OfferNotAcceptable.unconsumable(offer.product(), refused.refusal());
            case LoanCreditDecisions.Consumption.Consumed consumed -> {
                if (!consumed.party().equals(party) || !consumed.amount().equals(offer.terms().amount())) {
                    throw new IllegalStateException("the consumed decision is the offer's own party's and amount's (A20)");
                }
            }
        }
        if (offer.product().kind() == LoanKind.INSTALMENT) {
            // (L6) The capital row, then headroom judged under it (INV-LND-13).
            if (!headroom.underLock(unitOfWork, offer.terms().amount().currency()).admits(offer.terms().amount())) {
                throw new CapitalUnavailable(offer.product());
            }
        }

        LoanId loanId = LoanId.next(ids);
        LoanStore.NewLoan born = new LoanStore.NewLoan(loanId, party, offer.product(),
                offer.terms().amount().currency(), offer.id(), offer.decision(), correlation);
        Instant openedAt;
        try {
            openedAt = loans.insert(unitOfWork, born);
        } catch (LoanStore.OfferExpired expired) {
            // The clock crossed expires_at while this acceptance waited on credit's consumption or L6: the birth's
            // backstop refused it in its own SQLSTATE - the customer's 409, exactly as at the check after L2
            // (P11-TSK-034). Any other refusal of the birth is a breach and stays a storage failure.
            throw LoanOffers.OfferNotAcceptable.of(offer);
        }
        loans.appendEvent(unitOfWork, new LoanStore.HistoryRow(ids.next(), loanId, Optional.empty(), born.bornStatus(),
                ACCEPTED_REASON, actor, correlation));
        LoanAgreement unstamped = LoanAgreement.versionOne(agreementId, loanId, offer, openedAt);
        LoanAgreement agreement = LoanAgreement.versionOne(agreementId, loanId, offer,
                loans.insertAgreement(unitOfWork, unstamped));
        Instant acceptedAt = loans.insertAcceptance(unitOfWork, ids.next(), agreement, offer.terms().documentSha256(),
                acceptance.evidence(), correlation);
        if (offer.product().kind() == LoanKind.INSTALMENT) {
            loans.insertDisbursement(unitOfWork, ids.next(), loanId, payout, correlation);
        }
        LoanLedgerAccounts opened = accounts.openFor(unitOfWork, loanId, offer.terms().amount().currency());
        if (!offers.accept(unitOfWork, id)) {
            // The offer's own clock crossed its expiry between the read and the edge: the expiry's side won.
            throw LoanOffers.OfferNotAcceptable.of(offer);
        }
        offers.appendEvent(unitOfWork, new LoanOfferStore.HistoryRow(ids.next(), id,
                Optional.of(LoanOfferStatus.OFFERED), LoanOfferStatus.ACCEPTED, ACCEPTED_REASON, actor, correlation));
        Loan loan = new Loan(loanId, party, offer.product(), born.bornStatus(), born.currency(), offer.id(),
                offer.decision(), openedAt, Optional.empty(), correlation);
        publish(unitOfWork, loan, agreement, correlation);
        audited(unitOfWork, loan, agreement, actor, correlation);
        return new Accepted(loan, agreement, acceptedAt, opened);
    }

    // ------------------------------------------------------------------ the checks

    /** A constant-time comparison of the echoed hash with the stored one - a malformed echo is simply not the hash. */
    private static boolean sameHash(String echoed, byte[] stored) {
        byte[] parsed;
        try {
            parsed = HexFormat.of().parseHex(echoed.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(parsed, stored);
    }

    /**
     * Path X exactly when the application preferred an external account (A25, ADR-0097): then the customer's own live
     * bank account, verified through the port, by opaque reference; a wallet payout and a credit line name none.
     */
    private Optional<UUID> payoutDestination(
            Connection unitOfWork, LoanOffer offer, UUID party, Optional<UUID> payoutMethod) {
        PayoutPreference preference = applications.ownedBy(unitOfWork, offer.application(), party)
                .orElseThrow(() -> new IllegalStateException("an offer's application is its own party's, never deleted"))
                .payoutPreference();
        if (preference != PayoutPreference.EXTERNAL_ACCOUNT) {
            if (payoutMethod.isPresent()) {
                throw new PayoutDestinationInvalid(offer.product());
            }
            return Optional.empty();
        }
        return Optional.of(destinations.verify(unitOfWork, party,
                        payoutMethod.orElseThrow(() -> new PayoutDestinationInvalid(offer.product())))
                .orElseThrow(() -> new PayoutDestinationInvalid(offer.product())));
    }

    // ------------------------------------------------------------------ the event and the record

    /** {@code lending.LoanAccepted} - ids, the kind and the agreement's hash; never a figure. */
    private void publish(Connection unitOfWork, Loan loan, LoanAgreement agreement, CorrelationId correlation) {
        EventPayload payload = EventPayload.of()
                .with("loanId", loan.id().value().toString())
                .with("kind", loan.kind().name())
                .with("product", loan.product().name())
                .with("status", loan.status().name())
                .with("agreementId", agreement.id().value().toString())
                .with("agreementVersion", Integer.toString(agreement.version()))
                .with("termsSha256", agreement.termsSha256Hex())
                .with("decisionId", loan.decision().toString())
                .with("offerId", loan.offer().value().toString());
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        ACCEPTED_EVENT,
                        LoanTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        loan.id(),
                        LOAN_AGGREGATE_TYPE,
                        clock.instant(),
                        LoanTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** {@code lending.OfferAccepted} - the offer, the loan, the agreement version; never an amount. */
    private void audited(Connection unitOfWork, Loan loan, LoanAgreement agreement, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                LendingAuditAction.OFFER_ACCEPTED,
                LOAN_AGGREGATE_TYPE,
                loan.id().value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("offer " + loan.offer().value() + " accepted: loan " + loan.id().value() + ", "
                        + loan.product() + ", agreement version " + agreement.version())));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** The echoed terms hash is not the offer's. */
    public static final class TermsChanged extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        TermsChanged(LoanProduct product) {
            super("the terms accepted are not the offer's");
            this.product = product;
        }

        /** The offer's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }

    /** The payout destination is missing, unexpected, or not the customer's own verified bank account. */
    public static final class PayoutDestinationInvalid extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        PayoutDestinationInvalid(LoanProduct product) {
            super("the payout destination is not a verified bank account of the customer's expected here");
            this.product = product;
        }

        /** The offer's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }

    /** The currency's recognised lending capital cannot cover the loan's principal ({@code INV-LND-13}). */
    public static final class CapitalUnavailable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        CapitalUnavailable(LoanProduct product) {
            super("there is not enough recognised lending capital for this loan");
            this.product = product;
        }

        /** The offer's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }
}
