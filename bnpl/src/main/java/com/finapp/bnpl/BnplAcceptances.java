package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.creditmath.AllocationEngineV1;
import com.finapp.creditmath.AllocationLine;
import com.finapp.creditmath.AllocationRules;
import com.finapp.creditmath.DueItemId;
import com.finapp.creditmath.DueState;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.EntityId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The customer's acceptance of a BNPL offer - T-pur, the purchase authorisation (`P12-TSK-013`; {@code PHASE_12_PLAN.md}
 * sections 5.3-5.5, 7.1 T-pur, 7.2, 7.3 and 12.4, {@code BNPL_LIFECYCLES.md} sections 4.4-4.8; owner decisions B2, B6;
 * {@code INV-BNPL-02}, {@code -03}, {@code -04}, {@code -05}, {@code -10}, {@code INV-LND-13}, {@code INV-CRD-09},
 * {@code INV-IDEM-01}, {@code INV-IDN-04}; lending's {@code LoanAcceptances}). The decision consumed, the capital judged,
 * the agreement, its evidence, the purchase, the obligation, its schedule and accounts, the merchant financed, pay-in-4's
 * instalment 1 collected and the checkout session completed with its order: <strong>one transaction</strong> on the
 * caller's unit of work, which the door's claim ({@code bnpl.offer-acceptance:CUSTOMER:<id>}) wraps - a retried key
 * replays, and every refusal is thrown before or instead of the commit, so it writes nothing and rolls the claim back with
 * it. There is no committed-but-unfinanced state and so no compensating path (section 12.4).
 *
 * <h2>The order, which is the Phase 12 lock order (L0, B2, B3, L6, (P), L8)</h2>
 *
 * <ol>
 *   <li><strong>(L0)</strong> the party's credit profile {@code FOR UPDATE} ({@link BnplCreditDecisions#lockExposure}) -
 *       FIRST, before any bnpl row: the acceptance moves the party's exposure from credit's reservation to the
 *       obligation's principal, and credit's deciding transaction takes the same row first, so the two serialise and no
 *       instant sees neither ({@code INV-BNPL-10});
 *   <li><strong>(B2)</strong> the offer {@code FOR UPDATE}, owner-scoped; acted on only while {@code OFFERED} and, on the
 *       database clock read after the lock, unexpired; the echoed terms hash compared with the stored one (constant time);
 *   <li><strong>(B3)</strong> the checkout session row through {@link BnplCheckoutSessions#lockForPurchase},
 *       <strong>judged at once</strong> - not {@code OPEN} or past its deadline: {@link SessionNotPayable} before any
 *       further lock, nothing consumed (section 7.3 (c): no capture can be completing an {@code OPEN} session);
 *   <li>the party's standing re-read; the merchant's standing for the product ({@code FOR SHARE} on merchant's rows);
 *       credit's decision consumed for this agreement - credit re-takes L0 (re-entrant) and judges the lapse on its own
 *       clock under it; the consumption's party and amount held to the offer's;
 *   <li><strong>(L6)</strong> the currency's {@code LENDING_CAPITAL} row {@code FOR UPDATE} and headroom &ge; the gross
 *       ({@link BnplCapital#underLock}; {@code INV-LND-13} extended) - one pool with lending;
 *   <li>inserts: the purchase {@code AUTHORISED} (the database stamps its business date), the fee pinned and split
 *       ({@link BnplMerchants#pinFee}), the obligation, agreement version 1 (the offer's canonical terms copied), the
 *       evidence, schedule version 1 from the pinned engine over the purchase's business date, the six accounts;
 *   <li><strong>(P)</strong> the merchant's {@code MERCHANT_PAYABLE} and - pay-in-4 - the customer's
 *       {@code CUSTOMER_WALLET} rows {@code FOR UPDATE} <strong>as one element in {@code UUID.compareTo} order</strong> of
 *       the two account ids, the order payments' book rail takes for exactly this pair - never a fixed payable-then-wallet
 *       (section 7.3 (b)); pay-in-N takes the payable alone at this position; the wallet's available &ge; instalment 1
 *       judged under it ({@link InsufficientFunds});
 *   <li><strong>(L8)</strong> every projection row the transaction posts to, in one sorted call - the currency's shared
 *       {@code BNPL_MERCHANT_FEE_INCOME} row inside it, never before (P); then F1 (and for pay-in-4 F2 and F3) - every
 *       entry balanced and value-dated the purchase's business date, the obligation's floor;
 *   <li>the financing, billing and repayment rows with their entries; the session completed by financing
 *       ({@link BnplCheckoutSessions#completeByFinancing}, the order born with F1, re-judging the deadline); the purchase
 *       {@code FINANCED}; the offer {@code ACCEPTED} - conditional on its clock again, the expiry's complement, the act's
 *       last write; the histories, the events and the audit records.
 * </ol>
 *
 * <p>No lock is taken out of that order and none is held across anything but the database. No JVM state.
 */
@RequiredArgsConstructor
public final class BnplAcceptances {

    public static final String AGREEMENT_CREATED_EVENT = "bnpl.BnplAgreementCreated";
    public static final String MERCHANT_FINANCED_EVENT = "bnpl.MerchantFinanced";
    public static final String INSTALMENT_DUE_EVENT = "bnpl.InstalmentDue";
    public static final String INSTALMENT_PAID_EVENT = "bnpl.InstalmentPaid";

    public static final String AGREEMENT_AGGREGATE_TYPE = "bnpl_agreement";
    public static final String PURCHASE_AGGREGATE_TYPE = "purchase";
    public static final String OBLIGATION_AGGREGATE_TYPE = "bnpl_obligation";

    public static final String ACCEPTED_REASON = "CUSTOMER_ACCEPTED";
    public static final String FINANCED_REASON = "MERCHANT_FINANCED";

    /** F1's key prefix: one financing entry per purchase. */
    public static final String FINANCING_KEY_PREFIX = "bnpl.financing:";
    /** F2's key prefix: one billing entry per instalment. */
    public static final String BILLING_KEY_PREFIX = "bnpl.billing:";
    /** F3's key prefix: one repayment entry per repayment. */
    public static final String REPAYMENT_KEY_PREFIX = "bnpl.repayment:";

    @NonNull private final BnplOfferStore offers;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final BnplPurchaseStore purchases;
    @NonNull private final BnplCreditDecisions credit;
    @NonNull private final BnplPartyStanding standing;
    @NonNull private final BnplCheckoutSessions sessions;
    @NonNull private final BnplMerchants merchants;
    @NonNull private final BnplCapital capital;
    @NonNull private final BnplWallets wallets;
    @NonNull private final BnplBook book;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** What the customer sends: the terms hash they accept - the offer's, as its read showed it - and how they accepted. */
    public record Acceptance(String termsSha256, BnplAcceptanceEvidence evidence) {

        public Acceptance {
            Objects.requireNonNull(termsSha256, "termsSha256 must not be null");
            Objects.requireNonNull(evidence, "evidence must not be null");
        }

        @Override
        public String toString() {
            return "Acceptance[redacted]";
        }
    }

    /** What the acceptance committed: the agreement, the purchase, the obligation, checkout's order, the instant. */
    public record Accepted(BnplAgreement agreement, BnplPurchase purchase, BnplObligation obligation, UUID order,
            JournalEntryId financingEntry, Instant acceptedAt) {

        public Accepted {
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(purchase, "purchase must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(order, "order must not be null");
            Objects.requireNonNull(financingEntry, "financingEntry must not be null");
            Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        }

        @Override
        public String toString() {
            return "Accepted[" + agreement + "]";
        }
    }

    /**
     * Accepts {@code party}'s offer {@code id} - the purchase - on the caller's unit of work.
     *
     * @throws BnplOffers.OfferNotFound the party has no offer with this id (another party's included)
     * @throws NotAcceptable it is no longer {@code OFFERED}, has expired on the database clock, or credit would not
     *     consume its decision
     * @throws TermsChanged the echoed hash is not the offer's
     * @throws SessionNotPayable the checkout session is no longer {@code OPEN}, or past its deadline
     * @throws BnplApplications.NotInGoodStanding the party is not a verified customer in good standing now
     * @throws MerchantNotEnabled the merchant may not offer the product now, or its payable cannot be credited
     * @throws CapitalUnavailable the currency's recognised capital cannot fund the gross
     * @throws InsufficientFunds pay-in-4: the wallet cannot fund instalment 1
     */
    public Accepted accept(Connection unitOfWork, BnplOfferId id, UUID party, Acceptance acceptance, Actor actor,
            Correlation correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(acceptance, "acceptance");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        CorrelationId flow = correlation.correlationId();

        // (L0) The party's exposure, first.
        boolean profiled = credit.lockExposure(unitOfWork, party);
        // (B2) The offer, owner-scoped; judged on the database clock read after the lock.
        BnplOffer offer = offers.lockOwnedBy(unitOfWork, id, party).orElseThrow(BnplOffers.OfferNotFound::new);
        if (!profiled) {
            throw new IllegalStateException("an offered party has a credit profile: its decision was made under it");
        }
        if (offer.status() != BnplOfferStatus.OFFERED || !offer.expiresAt().isAfter(offers.databaseNow(unitOfWork))) {
            throw new NotAcceptable(offer);
        }
        BnplOfferStore.StoredTerms stored = offers.terms(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("an offer commits with its terms (bnpl V004)"));
        if (!sameHash(acceptance.termsSha256(), stored.termsSha256())) {
            throw new TermsChanged(offer);
        }
        // (B3) The session, judged at once - nothing further is locked for a session that cannot be paid.
        BnplCheckoutSessions.Session session = switch (sessions.lockForPurchase(unitOfWork, offer.checkoutSession())) {
            case BnplCheckoutSessions.NotPayable refused -> throw new SessionNotPayable(offer, refused.reason());
            case BnplCheckoutSessions.Payable payable -> payable.session();
        };
        Money gross = stored.amount();
        if (!session.gross().equals(gross)) {
            throw new IllegalStateException("an offer is for exactly its checkout session's gross (section 12.1)");
        }
        CurrencyCode currency = gross.currency();
        if (!standing.inGoodStanding(unitOfWork, party)) {
            throw new BnplApplications.NotInGoodStanding();
        }
        if (merchants.standing(unitOfWork, session.merchantRef(), offer.product()) != BnplMerchants.Standing.ENABLED) {
            throw new MerchantNotEnabled(offer);
        }
        BnplAgreementId agreementId = BnplAgreementId.next(ids);
        switch (credit.consume(unitOfWork, offer.decision(), agreementId)) {
            case BnplCreditDecisions.Consumption.Refused refused -> throw new NotAcceptable(offer);
            case BnplCreditDecisions.Consumption.Consumed consumed -> {
                if (!consumed.decision().equals(offer.decision()) || !consumed.party().equals(party)
                        || !consumed.amount().equals(gross)) {
                    throw new IllegalStateException("the consumed decision is the offer's own, for its party and exactly"
                            + " its purchase");
                }
            }
        }
        // (L6) The capital row, then headroom judged under it - for the gross (ADR-0104 decision 1).
        if (!capital.underLock(unitOfWork, currency).admits(gross)) {
            throw new CapitalUnavailable(offer);
        }

        // The inserts. The purchase's business date is the database's, after the locks: every fact below is dated by it.
        BnplTerms pinned = terms.version(unitOfWork, offer.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an offer's pinned terms version exists"))
                .terms();
        BnplPurchaseId purchaseId = BnplPurchaseId.next(ids);
        BnplPurchaseStore.Authorised authorised = purchases.insertPurchase(unitOfWork, new BnplPurchaseStore.NewPurchase(
                purchaseId, offer.id(), offer.checkoutSession(), party, session.merchantRef(), offer.product(),
                offer.plan(), currency, flow));
        LocalDate businessDate = authorised.businessDate();
        MerchantFeeSplit split = merchants.pinFee(unitOfWork, session.merchantRef(), offer.product(), purchaseId, gross);
        if (!split.gross().equals(gross)) {
            throw new IllegalStateException("the fee split is of exactly the purchase's gross (INV-BNPL-04)");
        }
        BnplObligationId obligationId = BnplObligationId.next(ids);
        Instant openedAt = purchases.insertObligation(unitOfWork, new BnplPurchaseStore.NewObligation(obligationId,
                purchaseId, agreementId, party, offer.decision(), offer.product(), offer.plan(), currency, flow));
        BnplAgreement agreement = BnplAgreement.versionOne(agreementId, obligationId, offer, stored, pinned,
                split.feeScheduleVersionId(), openedAt);
        agreement = agreement.stampedAt(purchases.insertAgreement(unitOfWork, agreement));
        Instant acceptedAt = purchases.insertAcceptance(unitOfWork, ids.next(), agreement, acceptance.evidence(), flow);
        BnplPurchaseStore.StoredSchedule schedule = scheduleVersionOne(obligationId, pinned, offer.plan(), gross,
                businessDate);
        if (!schedule.engine().equals(agreement.scheduleEngine())) {
            throw new IllegalStateException("schedule version 1 is projected by its agreement's pinned engine");
        }
        purchases.insertSchedule(unitOfWork, schedule);
        BnplLedgerAccounts accounts = book.openAccounts(unitOfWork, obligationId, currency);

        // (P) The payable and - pay-in-4 - the wallet, one element in UUID order.
        LedgerAccountId payable = merchants.payableAccount(unitOfWork, session.merchantRef(), currency)
                .orElseThrow(() -> new MerchantNotEnabled(offer));
        boolean collectsAtPurchase = offer.product() == BnplProduct.BNPL_PAY_IN_4;
        BnplPurchaseStore.StoredInstalment first = schedule.instalments().get(0);
        Optional<LedgerAccountId> wallet = collectsAtPurchase
                ? Optional.of(wallets.existingWallet(unitOfWork, party, currency)
                        .orElseThrow(() -> new InsufficientFunds(offer)))
                : Optional.empty();
        Map<LedgerAccountId, LedgerAccountStatus> locked = lockAsOneElement(unitOfWork, payable, wallet);
        if (locked.get(payable) != LedgerAccountStatus.ACTIVE) {
            throw new MerchantNotEnabled(offer);
        }
        if (wallet.isPresent() && (locked.get(wallet.get()) != LedgerAccountStatus.ACTIVE
                || book.availableUnderLock(unitOfWork, wallet.get()).compareTo(first.amount()) < 0)) {
            throw new InsufficientFunds(offer);
        }

        // (L8) Every projection row the transaction posts to, in the ledger's one order, before the first entry.
        LedgerAccountId feeIncome = book.operational(unitOfWork, AccountPurpose.BNPL_MERCHANT_FEE_INCOME, currency);
        Set<LedgerAccountId> touched = new LinkedHashSet<>(List.of(accounts.principal().id(), payable, feeIncome));
        if (wallet.isPresent()) {
            touched.add(accounts.principalDue().id());
            touched.add(wallet.get());
        }
        book.lockBalancesInOrder(unitOfWork, touched);

        // F1: the merchant financed, the receivable born.
        JournalEntryId financingEntry = book.post(unitOfWork, new PostingCommand(FINANCING_KEY_PREFIX + purchaseId.value(),
                businessDate, businessDate, purchaseId.value().toString(),
                financingLines(split, accounts.principal().id(), payable, feeIncome)));
        MerchantFinancing financing = new MerchantFinancing(purchaseId, session.merchantRef(), split, financingEntry);
        purchases.insertFinancing(unitOfWork, financing);

        Optional<Collected> collected = Optional.empty();
        if (wallet.isPresent()) {
            collected = Optional.of(collectFirstInstalment(unitOfWork, purchaseId, obligationId, agreement, pinned,
                    schedule, first, accounts, wallet.get(), businessDate));
        }

        // The session completed by financing - checkout's order born with F1, the deadline re-judged in the write.
        UUID order = switch (sessions.completeByFinancing(unitOfWork, offer.checkoutSession(), purchaseId, financingEntry,
                correlation)) {
            case BnplCheckoutSessions.NotPayable refused -> throw new SessionNotPayable(offer, refused.reason());
            case BnplCheckoutSessions.Completed completed -> completed.orderRef();
        };
        Instant financedAt = purchases.finance(unitOfWork, purchaseId, order)
                .orElseThrow(() -> new IllegalStateException("the purchase born in this transaction moved under it"));
        purchases.appendPurchaseEvent(unitOfWork, new BnplPurchaseStore.HistoryRow(ids.next(), purchaseId,
                Optional.empty(), BnplPurchaseStatus.AUTHORISED, ACCEPTED_REASON, actor, flow));
        purchases.appendPurchaseEvent(unitOfWork, new BnplPurchaseStore.HistoryRow(ids.next(), purchaseId,
                Optional.of(BnplPurchaseStatus.AUTHORISED), BnplPurchaseStatus.FINANCED, FINANCED_REASON, actor, flow));
        purchases.appendObligationEvent(unitOfWork, new BnplPurchaseStore.ObligationHistoryRow(ids.next(), obligationId,
                Optional.empty(), BnplObligationStatus.ACTIVE, ACCEPTED_REASON, actor, flow));
        // The offer's edge last: the expiry's complement on the clock read now - an offer that expired while the act
        // waited is not acceptable, and everything above rolls back with the refusal.
        if (!offers.accept(unitOfWork, id)) {
            throw new NotAcceptable(offer);
        }
        offers.appendEvent(unitOfWork, new BnplOfferStore.HistoryRow(ids.next(), id, Optional.of(BnplOfferStatus.OFFERED),
                BnplOfferStatus.ACCEPTED, ACCEPTED_REASON, actor, flow));

        BnplPurchase purchase = new BnplPurchase(purchaseId, offer.id(), offer.checkoutSession(), party,
                session.merchantRef(), offer.product(), offer.plan(), currency, BnplPurchaseStatus.FINANCED, businessDate,
                authorised.authorisedAt(), Optional.of(financedAt), Optional.of(order), flow);
        BnplObligation obligation = new BnplObligation(obligationId, purchaseId, agreementId, party, offer.decision(),
                offer.product(), offer.plan(), currency, BnplObligationStatus.ACTIVE, businessDate, openedAt,
                Optional.empty(), flow);
        publish(unitOfWork, agreement, purchase, financing, collected, flow);
        audited(unitOfWork, agreement, purchase, financing, actor, flow);
        return new Accepted(agreement, purchase, obligation, order, financingEntry, acceptedAt);
    }

    // ------------------------------------------------------------------ the pieces

    /**
     * (P): the payable and the wallet locked as ONE element, in {@code UUID.compareTo} order of their account ids - the
     * order {@code PaymentOutcomes.lockPairInFixedOrder} takes for the same pair, so a wallet-paid capture by the same
     * customer at the same merchant never closes a cycle with this act. The payable alone when there is no wallet.
     */
    Map<LedgerAccountId, LedgerAccountStatus> lockAsOneElement(Connection unitOfWork, LedgerAccountId payable,
            Optional<LedgerAccountId> wallet) {
        List<LedgerAccountId> order = new ArrayList<>(List.of(payable));
        wallet.filter(other -> !other.equals(payable)).ifPresent(other -> {
            if (other.value().compareTo(payable.value()) < 0) {
                order.add(0, other);
            } else {
                order.add(other);
            }
        });
        Map<LedgerAccountId, LedgerAccountStatus> locked = new java.util.LinkedHashMap<>();
        for (LedgerAccountId account : order) {
            locked.put(account, book.lockForUpdate(unitOfWork, account).orElseThrow(() -> new IllegalStateException(
                    "a resolved ledger account vanished - an invariant is already broken")));
        }
        return locked;
    }

    /** Schedule version 1 from the pinned engine, over exactly the gross, from the purchase's business date (A4-A6). */
    BnplPurchaseStore.StoredSchedule scheduleVersionOne(BnplObligationId obligation, BnplTerms pinned, BnplPlan plan,
            Money gross, LocalDate businessDate) {
        BnplIllustration projected = BnplIllustration.of(pinned, plan, gross, businessDate);
        List<BnplPurchaseStore.StoredInstalment> instalments = new ArrayList<>();
        for (BnplIllustration.Line line : projected.lines()) {
            instalments.add(new BnplPurchaseStore.StoredInstalment(ids.next(), line.sequence(), line.dueDate(),
                    line.principal(), line.interest(), line.amount()));
        }
        return new BnplPurchaseStore.StoredSchedule(ids.next(), obligation, 1, projected.engine(), gross, businessDate,
                instalments);
    }

    /**
     * F1 - the {@code MerchantSettlement.settle} four-line shape with the receivable in the clearing's place:
     * {@code DR BNPL_PRINCIPAL G; DR MERCHANT_PAYABLE f / CR MERCHANT_PAYABLE G; CR BNPL_MERCHANT_FEE_INCOME f}. A zero
     * fee writes no fee lines (a journal line asserts a positive amount).
     */
    static List<JournalLine> financingLines(MerchantFeeSplit split, LedgerAccountId principal, LedgerAccountId payable,
            LedgerAccountId feeIncome) {
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(principal, Direction.DEBIT, split.gross()));
        lines.add(new JournalLine(payable, Direction.CREDIT, split.gross()));
        if (split.fee().isPositive()) {
            lines.add(new JournalLine(payable, Direction.DEBIT, split.fee()));
            lines.add(new JournalLine(feeIncome, Direction.CREDIT, split.fee()));
        }
        return lines;
    }

    /** Pay-in-4's instalment 1 billed (F2) and collected from the wallet (F3), inside the purchase (A5, A7, B6). */
    private Collected collectFirstInstalment(Connection unitOfWork, BnplPurchaseId purchase, BnplObligationId obligation,
            BnplAgreement agreement, BnplTerms pinned, BnplPurchaseStore.StoredSchedule schedule,
            BnplPurchaseStore.StoredInstalment first, BnplLedgerAccounts accounts, LedgerAccountId wallet,
            LocalDate businessDate) {
        // F2: DR BNPL_PRINCIPAL_DUE a1 / CR BNPL_PRINCIPAL a1.
        JournalEntryId billingEntry = book.post(unitOfWork, new PostingCommand(BILLING_KEY_PREFIX + first.id(),
                businessDate, businessDate, obligation.value().toString(), List.of(
                        new JournalLine(accounts.principalDue().id(), Direction.DEBIT, first.principal()),
                        new JournalLine(accounts.principal().id(), Direction.CREDIT, first.principal()))));
        purchases.insertBilling(unitOfWork, new BnplPurchaseStore.Billing(ids.next(), first.id(), obligation, businessDate,
                first.principal(), first.interest(), billingEntry));

        // F3 allocated by the pinned allocation engine over the due state the purchase itself just created.
        Money zero = Money.ofPersisted(0, first.amount().currency(), first.amount().scale());
        DueState due = new DueState(List.of(new DueState.DueItem(DueItemId.of(first.id()), first.dueDate(), zero,
                first.interest(), first.principal())), schedule.principal().minus(first.principal()));
        AllocationRules rules = new AllocationRules(pinned.allocationEngine(), pinned.allocationSequence(),
                pinned.allocationOrder(), pinned.overpaymentTreatment());
        List<AllocationLine> lines = AllocationEngineV1.allocate(first.amount(), due, rules);
        UUID repaymentId = ids.next();
        List<JournalLine> entry = new ArrayList<>();
        entry.add(new JournalLine(wallet, Direction.DEBIT, first.amount()));
        Map<AllocationComponent, Money> perComponent = new EnumMap<>(AllocationComponent.class);
        List<BnplPurchaseStore.Allocation> allocations = new ArrayList<>();
        for (AllocationLine line : lines) {
            if (line.target() != AllocationLine.Target.DUE_ITEM) {
                throw new IllegalStateException("instalment 1's own amount settles exactly instalment 1 (INV-LND-04)");
            }
            perComponent.merge(line.component().orElseThrow(), line.amount(), Money::plus);
            allocations.add(new BnplPurchaseStore.Allocation(ids.next(), allocations.size() + 1, line.target().name(),
                    line.item().map(EntityId::value), line.component().map(Enum::name), line.amount()));
        }
        for (Map.Entry<AllocationComponent, Money> component : perComponent.entrySet()) {
            LedgerAccountId target = switch (component.getKey()) {
                case PRINCIPAL -> accounts.principalDue().id();
                case INTEREST -> accounts.interestDue().id();
                case FEES -> accounts.feesDue().id();
            };
            entry.add(new JournalLine(target, Direction.CREDIT, component.getValue()));
        }
        JournalEntryId repaymentEntry = book.post(unitOfWork, new PostingCommand(REPAYMENT_KEY_PREFIX + repaymentId,
                businessDate, businessDate, obligation.value().toString(), entry));
        purchases.insertRepayment(unitOfWork, new BnplPurchaseStore.Repayment(repaymentId, obligation,
                BnplPurchaseStore.Repayment.AT_PURCHASE, purchase.value().toString(), first.amount(), agreement.id(),
                agreement.version(), agreement.allocationEngine(), repaymentEntry, businessDate, allocations));
        return new Collected(first, repaymentId, billingEntry, repaymentEntry);
    }

    /** What pay-in-4's purchase collected. */
    private record Collected(BnplPurchaseStore.StoredInstalment instalment, UUID repayment, JournalEntryId billingEntry,
            JournalEntryId repaymentEntry) {}

    /** A constant-time comparison of the echoed hash with the stored one - a malformed echo is simply not the hash. */
    static boolean sameHash(String echoed, byte[] stored) {
        byte[] parsed;
        try {
            parsed = HexFormat.of().parseHex(echoed.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        return MessageDigest.isEqual(parsed, stored);
    }

    // ------------------------------------------------------------------ the events and the records

    private void publish(Connection unitOfWork, BnplAgreement agreement, BnplPurchase purchase,
            MerchantFinancing financing, Optional<Collected> collected, CorrelationId correlation) {
        write(unitOfWork, AGREEMENT_CREATED_EVENT, agreement.id(), AGREEMENT_AGGREGATE_TYPE, EventPayload.of()
                .with("agreementId", agreement.id().value().toString())
                .with("agreementVersion", Integer.toString(agreement.version()))
                .with("obligationId", agreement.obligation().value().toString())
                .with("purchaseId", purchase.id().value().toString())
                .with("checkoutSessionId", purchase.checkoutSession().toString())
                .with("orderId", purchase.order().orElseThrow().toString())
                .with("product", purchase.product().name())
                .with("plan", purchase.plan().name())
                .with("termsSha256", agreement.termsSha256Hex()), correlation);
        // The merchant's own figures only - never the customer's credit data (section 11).
        MerchantFeeSplit split = financing.split();
        write(unitOfWork, MERCHANT_FINANCED_EVENT, purchase.id(), PURCHASE_AGGREGATE_TYPE, EventPayload.of()
                .with("merchantId", financing.merchant().toString())
                .with("purchaseId", purchase.id().value().toString())
                .with("currency", split.gross().currency().code())
                .with("scale", Integer.toString(split.gross().scale()))
                .with("grossMinor", Long.toString(split.gross().minorUnits()))
                .with("feeMinor", Long.toString(split.fee().minorUnits()))
                .with("netMinor", Long.toString(split.net().minorUnits()))
                .with("journalEntryId", financing.entry().value().toString()), correlation);
        collected.ifPresent(paid -> {
            BnplPurchaseStore.StoredInstalment instalment = paid.instalment();
            write(unitOfWork, INSTALMENT_DUE_EVENT, agreement.obligation(), OBLIGATION_AGGREGATE_TYPE, EventPayload.of()
                    .with("obligationId", agreement.obligation().value().toString())
                    .with("instalmentId", instalment.id().toString())
                    .with("sequence", Integer.toString(instalment.sequence()))
                    .with("dueDate", instalment.dueDate().toString())
                    .with("currency", instalment.amount().currency().code())
                    .with("scale", Integer.toString(instalment.amount().scale()))
                    .with("principalMinor", Long.toString(instalment.principal().minorUnits()))
                    .with("interestMinor", Long.toString(instalment.interest().minorUnits()))
                    .with("journalEntryId", paid.billingEntry().value().toString()), correlation);
            write(unitOfWork, INSTALMENT_PAID_EVENT, agreement.obligation(), OBLIGATION_AGGREGATE_TYPE, EventPayload.of()
                    .with("obligationId", agreement.obligation().value().toString())
                    .with("instalmentId", instalment.id().toString())
                    .with("sequence", Integer.toString(instalment.sequence()))
                    .with("repaymentId", paid.repayment().toString())
                    .with("currency", instalment.amount().currency().code())
                    .with("scale", Integer.toString(instalment.amount().scale()))
                    .with("amountMinor", Long.toString(instalment.amount().minorUnits()))
                    .with("journalEntryId", paid.repaymentEntry().value().toString()), correlation);
        });
    }

    private void write(Connection unitOfWork, String type, EntityId aggregate, String aggregateType, EventPayload payload,
            CorrelationId correlation) {
        outbox.write(
                unitOfWork,
                new EventEnvelope(
                        EventId.next(ids),
                        type,
                        BnplTermsAdministration.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        aggregate,
                        aggregateType,
                        clock.instant(),
                        BnplTermsAdministration.PRODUCER,
                        correlation,
                        CausationId.of(correlation.value())),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /** {@code bnpl.OfferAccepted} and {@code bnpl.MerchantFinanced} - ids and versions; never an amount. */
    private void audited(Connection unitOfWork, BnplAgreement agreement, BnplPurchase purchase,
            MerchantFinancing financing, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.OFFER_ACCEPTED,
                PURCHASE_AGGREGATE_TYPE, purchase.id().value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("offer " + purchase.offer().value() + " accepted: agreement "
                        + agreement.id().value() + " version " + agreement.version() + ", purchase "
                        + purchase.id().value() + ", " + purchase.product() + " " + purchase.plan())));
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                BnplAuditAction.MERCHANT_FINANCED, PURCHASE_AGGREGATE_TYPE, purchase.id().value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation, Optional.of("purchase " + purchase.id().value()
                        + " financed by entry " + financing.entry().value() + ", fee schedule version "
                        + financing.split().feeScheduleVersionId())));
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** A refusal of the acceptance - its offer's product and plan, for the meter; never an amount in the message. */
    public abstract static class Refused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplProduct product;
        private final BnplPlan plan;

        Refused(BnplOffer offer, String message) {
            super(message);
            this.product = offer.product();
            this.plan = offer.plan();
        }

        /** The offer's product - for the meter. */
        public BnplProduct product() {
            return product;
        }

        /** The offer's plan - for the meter. */
        public BnplPlan plan() {
            return plan;
        }
    }

    /** The offer is no longer open to acceptance - declined, expired on the database clock, accepted, or unconsumable. */
    public static final class NotAcceptable extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotAcceptable(BnplOffer offer) {
            super(offer, "the BNPL offer is no longer open: it was declined, expired or accepted");
        }
    }

    /** The echoed terms hash is not the offer's. */
    public static final class TermsChanged extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TermsChanged(BnplOffer offer) {
            super(offer, "the terms accepted are not the BNPL offer's");
        }
    }

    /** The checkout session can no longer be paid by the purchase. */
    public static final class SessionNotPayable extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplCheckoutSessions.Reason reason;

        SessionNotPayable(BnplOffer offer, BnplCheckoutSessions.Reason reason) {
            super(offer, "the checkout session can no longer be paid by this BNPL offer");
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        /** Why - for the meter. */
        public BnplCheckoutSessions.Reason reason() {
            return reason;
        }
    }

    /** The merchant may not offer the product now, or its payable cannot be credited. */
    public static final class MerchantNotEnabled extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        MerchantNotEnabled(BnplOffer offer) {
            super(offer, "the merchant does not offer this BNPL product now");
        }
    }

    /** The currency's recognised lending capital cannot fund the purchase ({@code INV-LND-13}, {@code INV-BNPL-10}). */
    public static final class CapitalUnavailable extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CapitalUnavailable(BnplOffer offer) {
            super(offer, "there is not enough recognised lending capital for this purchase now");
        }
    }

    /** Pay-in-4: the wallet cannot fund instalment 1, or cannot be debited now. */
    public static final class InsufficientFunds extends Refused {
        @java.io.Serial private static final long serialVersionUID = 1L;

        InsufficientFunds(BnplOffer offer) {
            super(offer, "the wallet cannot fund the first instalment");
        }
    }
}
