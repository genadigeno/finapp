package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the acceptance's facts (`P12-TSK-013`, {@code bnpl V006}): the purchase and its history, the merchant
 * financing, the obligation and its history, the agreement and its evidence, schedule version 1 and its instalments, and
 * pay-in-4's billing and repayment at purchase. Every instant and the purchase's business date are the database's -
 * stamped by {@code bnpl V006}'s triggers after the act's locks, never written by an instance; every money row is
 * INSERT-only; every read a customer reaches is owner-scoped in the statement.
 */
public interface BnplPurchaseStore {

    // ------------------------------------------------------------------ the writes, each once

    /** Births the purchase {@code AUTHORISED} under its offer's lock; the database stamps its instant and business date. */
    Authorised insertPurchase(Connection unitOfWork, NewPurchase purchase);

    /**
     * {@code AUTHORISED -> FINANCED} with checkout's order, conditional on {@code AUTHORISED} - the instant the database
     * stamped, or empty when not there. The database refuses it without the purchase's merchant financing.
     */
    Optional<Instant> finance(Connection unitOfWork, BnplPurchaseId purchase, UUID order);

    /** Appends one purchase history row; the database stamps the time. */
    void appendPurchaseEvent(Connection unitOfWork, HistoryRow row);

    /** Births the merchant financing - once per purchase, INSERT-only. */
    void insertFinancing(Connection unitOfWork, MerchantFinancing financing);

    /** Births the obligation {@code ACTIVE} beside its purchase; the database stamps its opening and purchase date. */
    Instant insertObligation(Connection unitOfWork, NewObligation obligation);

    /** Appends one obligation history row; the database stamps the time. */
    void appendObligationEvent(Connection unitOfWork, ObligationHistoryRow row);

    /** Births an agreement version - INSERT-only, immutable; the database stamps its instant. */
    Instant insertAgreement(Connection unitOfWork, BnplAgreement agreement);

    /** Births the acceptance's evidence for {@code agreement}; the database stamps the instant. */
    Instant insertAcceptance(Connection unitOfWork, UUID id, BnplAgreement agreement, BnplAcceptanceEvidence evidence,
            CorrelationId correlation);

    /** Births a schedule version and its instalments - INSERT-only, conserving at commit. */
    void insertSchedule(Connection unitOfWork, StoredSchedule schedule);

    /**
     * Births an instalment's billing - once per instalment and per due date; {@code bnpl V008}'s trigger holds its
     * versions, its period, its interest (the period's posted accruals) and its principal to the rows for every writer.
     */
    void insertBilling(Connection unitOfWork, BnplBilling billing);

    /** Births a repayment and its allocation - once per key. */
    void insertRepayment(Connection unitOfWork, Repayment repayment);

    // ------------------------------------------------------------------ the owner's reads

    /** {@code party}'s agreements, newest first, at most {@code limit}. */
    List<Owned> ownedBy(Connection unitOfWork, UUID party, int limit);

    /** {@code party}'s agreement {@code id}; another party's is the same empty answer as an absent one. */
    Optional<Owned> agreementOwnedBy(Connection unitOfWork, BnplAgreementId id, UUID party);

    /** The obligation's schedule version {@code version}, its instalments in order. */
    Optional<StoredSchedule> schedule(Connection unitOfWork, BnplObligationId obligation, int version);

    /**
     * The obligation's billed items, oldest due date first: each billing's billed and paid figures per component (every
     * payer's, {@code bnpl.billing_component_paid}) - the one read the due state ({@link BnplReceivables}, under B4) and
     * the owner's schedule ({@link BnplObligations}) derive from (`P12-TSK-015`).
     */
    List<BilledItem> billedItems(Connection unitOfWork, BnplObligationId obligation);

    // ------------------------------------------------------------------ the merchant's read

    /**
     * The BNPL purchase {@code id} made at {@code merchant}, with its merchant financing, in one statement
     * (`P12-TSK-014`): {@code merchant_id = ?} in the statement, so another merchant's purchase and an absent one are the
     * same empty answer ({@code INV-MER-01}); the financing joined, so a purchase never reads without it
     * ({@code INV-BNPL-02}). Selects nothing of the customer's - no party, plan, decision or obligation column.
     */
    Optional<MerchantBnplPurchaseView> atMerchant(Connection unitOfWork, BnplPurchaseId id, UUID merchant);

    // ------------------------------------------------------------------ rows

    /** A purchase at birth: its references by value; the instant and the business date are the database's. */
    record NewPurchase(
            BnplPurchaseId id,
            BnplOfferId offer,
            UUID checkoutSession,
            UUID party,
            UUID merchant,
            BnplProduct product,
            BnplPlan plan,
            CurrencyCode currency,
            CorrelationId correlation) {

        public NewPurchase {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(merchant, "merchant must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewPurchase[" + id.value() + ", " + product + "]";
        }
    }

    /** What the database stamped at the purchase's birth: the instant and the business date in the pinned zone. */
    record Authorised(LocalDate businessDate, Instant authorisedAt) {

        public Authorised {
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(authorisedAt, "authorisedAt must not be null");
        }
    }

    /** An obligation at birth; its opening, purchase date and permit are the database's. */
    record NewObligation(
            BnplObligationId id,
            BnplPurchaseId purchase,
            BnplAgreementId agreement,
            UUID party,
            UUID decision,
            BnplProduct product,
            BnplPlan plan,
            CurrencyCode currency,
            CorrelationId correlation) {

        public NewObligation {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(purchase, "purchase must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewObligation[" + id.value() + ", " + product + "]";
        }
    }

    /** One edge of the purchase's history; {@code reason} a code, never free text. */
    record HistoryRow(UUID eventId, BnplPurchaseId purchase, Optional<BnplPurchaseStatus> from, BnplPurchaseStatus to,
            String reason, Actor actor, CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(purchase, "purchase must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /** One edge of the obligation's history. */
    record ObligationHistoryRow(UUID eventId, BnplObligationId obligation, Optional<BnplObligationStatus> from,
            BnplObligationStatus to, String reason, Actor actor, CorrelationId correlation) {

        public ObligationHistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /**
     * A schedule version as stored: its engine, the principal it schedules, its start (the purchase's business date for
     * version 1) and its instalments, in order. RESTRICTED-FINANCIAL.
     */
    record StoredSchedule(UUID id, BnplObligationId obligation, int version, String engine, Money principal,
            LocalDate start, List<StoredInstalment> instalments) {

        public StoredSchedule {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(engine, "engine must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            Objects.requireNonNull(start, "start must not be null");
            instalments = List.copyOf(Objects.requireNonNull(instalments, "instalments must not be null"));
        }

        @Override
        public String toString() {
            return "StoredSchedule[v" + version + ", " + engine + ", " + instalments.size() + " instalments]";
        }
    }

    /** One stored instalment. */
    record StoredInstalment(UUID id, int sequence, LocalDate dueDate, Money principal, Money interest, Money amount) {

        public StoredInstalment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(dueDate, "dueDate must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            Objects.requireNonNull(interest, "interest must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        @Override
        public String toString() {
            return "StoredInstalment[" + sequence + ", due " + dueDate + "]";
        }
    }

    /**
     * A billed item as the due state reads it (`P12-TSK-015`): the billing, its instalment and due date, what it billed and
     * what every payer has paid of each component. RESTRICTED-FINANCIAL.
     */
    record BilledItem(UUID billing, UUID instalment, LocalDate dueDate, Money billedFees, Money billedInterest,
            Money billedPrincipal, Money paidFees, Money paidInterest, Money paidPrincipal) {

        public BilledItem {
            Objects.requireNonNull(billing, "billing must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(dueDate, "dueDate must not be null");
            Objects.requireNonNull(billedFees, "billedFees must not be null");
            Objects.requireNonNull(billedInterest, "billedInterest must not be null");
            Objects.requireNonNull(billedPrincipal, "billedPrincipal must not be null");
            Objects.requireNonNull(paidFees, "paidFees must not be null");
            Objects.requireNonNull(paidInterest, "paidInterest must not be null");
            Objects.requireNonNull(paidPrincipal, "paidPrincipal must not be null");
        }

        /** What it billed of {@code component}. */
        public Money billed(com.finapp.creditmath.AllocationComponent component) {
            return switch (component) {
                case FEES -> billedFees;
                case INTEREST -> billedInterest;
                case PRINCIPAL -> billedPrincipal;
            };
        }

        /** What every payer has paid of {@code component}. */
        public Money paid(com.finapp.creditmath.AllocationComponent component) {
            return switch (component) {
                case FEES -> paidFees;
                case INTEREST -> paidInterest;
                case PRINCIPAL -> paidPrincipal;
            };
        }

        /** What it still has due of {@code component} - billed less paid. */
        public Money due(com.finapp.creditmath.AllocationComponent component) {
            return billed(component).minus(paid(component));
        }

        /** Everything it billed. */
        public Money billedTotal() {
            return billedFees.plus(billedInterest).plus(billedPrincipal);
        }

        /** Everything paid of it. */
        public Money paidTotal() {
            return paidFees.plus(paidInterest).plus(paidPrincipal);
        }

        /** Everything it still has due. */
        public Money dueTotal() {
            return billedTotal().minus(paidTotal());
        }

        @Override
        public String toString() {
            return "BilledItem[" + instalment + ", due " + dueDate + "]";
        }
    }

    /**
     * A repayment, its allocation in order, and the entry F3 that took it from the wallet - pay-in-4's instalment 1 at
     * purchase (`P12-TSK-013`), the customer's own (`P12-TSK-018`) and, later, a collection's and a payoff's. INSERT-only:
     * {@code REVERSED} is derived from `P12-TSK-024`'s approved reversal, never a column. RESTRICTED-FINANCIAL.
     */
    record Repayment(UUID id, BnplObligationId obligation, String kind, String requestKey, Money amount,
            BnplAgreementId agreement, int agreementVersion, String allocationEngine, JournalEntryId entry,
            LocalDate valueDate, List<Allocation> allocations) {

        /** Pay-in-4's instalment 1, collected inside the purchase. */
        public static final String AT_PURCHASE = "AT_PURCHASE";

        /** The customer's own, from the wallet, keyed by their idempotency key (`P12-TSK-018`). */
        public static final String CUSTOMER = "CUSTOMER";

        /** An automatic collection on the due date, keyed by its attempt (`P12-TSK-019`). */
        public static final String AUTO_COLLECTION = "AUTO_COLLECTION";

        /** A payoff's one repayment (`P12-TSK-022`). */
        public static final String PAYOFF = "PAYOFF";

        public Repayment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(requestKey, "requestKey must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            allocations = List.copyOf(Objects.requireNonNull(allocations, "allocations must not be null"));
            Money allocated = Money.ofPersisted(0, amount.currency(), amount.scale());
            for (Allocation line : allocations) {
                allocated = allocated.plus(line.amount());
            }
            if (!allocated.equals(amount)) {
                throw new IllegalArgumentException("a repayment is allocated whole: its lines sum to its amount");
            }
        }

        @Override
        public String toString() {
            return "Repayment[" + id + ", " + kind + ", redacted]";
        }
    }

    /**
     * One allocation line: its target ({@code DUE_ITEM}, {@code CREDIT_BALANCE}, {@code NOT_DUE_PRINCIPAL}), the instalment
     * and component a due item pays.
     */
    record Allocation(UUID id, int sequence, String target, Optional<UUID> instalment, Optional<String> component,
            Money amount) {

        public Allocation {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(target, "target must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(component, "component must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        @Override
        public String toString() {
            return "Allocation[" + sequence + ", " + target + "]";
        }
    }

    /** An agreement as its owner reads it: the obligation, the purchase, the agreement's latest version, the financing. */
    record Owned(BnplObligation obligation, BnplPurchase purchase, BnplAgreement agreement, Money gross) {

        public Owned {
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(purchase, "purchase must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(gross, "gross must not be null");
        }

        @Override
        public String toString() {
            return "Owned[" + agreement + "]";
        }
    }
}
