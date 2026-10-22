package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One BNPL obligation's whole record (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12; lending's {@code LoanRecord}):
 * every row of its purchase, financing, agreement versions and their pinned terms, the offer they copied, its schedule
 * versions, accruals, billings, applications, repayments, collection attempts, refunds, fees, conditions, payoff, disputes,
 * reversals, waivers and closure - each fact with the database instant it was written at under the obligation's lock (B4)
 * - and its ledger: the six accounts, every journal line on them, and the lines of its financing and refund entries on the
 * accounts it shares (the merchant's payable, the currency's merchant fee income). Read in ONE {@code REPEATABLE READ}
 * snapshot ({@link BnplRecordStore}), so every part is one state; the proofs are pure functions of it.
 *
 * <p>RESTRICTED-FINANCIAL: {@link #toString()} names the obligation and the counts only.
 */
public record BnplRecord(
        BnplObligation obligation,
        Optional<Instant> closedAt,
        BnplPurchase purchase,
        MerchantFinancing financing,
        Instant financedAt,
        Optional<BnplOffer> offer,
        Optional<BnplOfferStore.StoredTerms> offerTerms,
        List<Agreement> agreements,
        Map<BnplTermsVersionId, Optional<BnplTermsVersion>> termsVersions,
        List<Schedule> schedules,
        List<Billing> billings,
        List<Stamped<BnplInterestAccrual>> accruals,
        List<Stamped<BnplCreditBalanceApplication>> applications,
        List<Stamped<BnplPurchaseStore.Repayment>> repayments,
        List<Stamped<BnplCollectionAttempt>> attempts,
        List<Stamped<BnplRefund>> refunds,
        List<Stamped<BnplFeeAssessment>> fees,
        List<Stamped<BnplConditionEvent>> conditions,
        List<Stamped<BnplPayoffQuote>> quotes,
        Optional<Stamped<BnplPayoffExecution>> execution,
        List<BnplDispute> disputes,
        List<Reversal> reversals,
        List<BnplWaiver> waivers,
        Optional<Stamped<BnplClosure>> closure,
        List<LedgerAccountRow> ledgerAccounts,
        List<PurposeBalances.DatedLine> journalLines,
        List<EntryLines> sharedEntries,
        Optional<LedgerAccountId> payable,
        Optional<LedgerAccountId> merchantFeeIncome) {

    public BnplRecord {
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(financing, "financing must not be null");
        Objects.requireNonNull(financedAt, "financedAt must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        Objects.requireNonNull(offerTerms, "offerTerms must not be null");
        agreements = List.copyOf(agreements);
        termsVersions = Map.copyOf(termsVersions);
        schedules = List.copyOf(schedules);
        billings = List.copyOf(billings);
        accruals = List.copyOf(accruals);
        applications = List.copyOf(applications);
        repayments = List.copyOf(repayments);
        attempts = List.copyOf(attempts);
        refunds = List.copyOf(refunds);
        fees = List.copyOf(fees);
        conditions = List.copyOf(conditions);
        quotes = List.copyOf(quotes);
        Objects.requireNonNull(execution, "execution must not be null");
        disputes = List.copyOf(disputes);
        reversals = List.copyOf(reversals);
        waivers = List.copyOf(waivers);
        Objects.requireNonNull(closure, "closure must not be null");
        ledgerAccounts = List.copyOf(ledgerAccounts);
        journalLines = List.copyOf(journalLines);
        sharedEntries = List.copyOf(sharedEntries);
        Objects.requireNonNull(payable, "payable must not be null");
        Objects.requireNonNull(merchantFeeIncome, "merchantFeeIncome must not be null");
    }

    /** A fact and the database instant it was written at - its place in the obligation's history. */
    public record Stamped<T>(T row, Instant at) {

        public Stamped {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /** An agreement version, the refund that made it (version 2 on) and the schedule version it governs. */
    public record Agreement(BnplAgreement row, Optional<UUID> refund, int scheduleVersion) {

        public Agreement {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(refund, "refund must not be null");
        }
    }

    /** A schedule version, the refund that made it (version 2 on) and its instant. */
    public record Schedule(BnplPurchaseStore.StoredSchedule row, Optional<UUID> refund, Instant at) {

        public Schedule {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(refund, "refund must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /** A billing, the business date it was billed on, the payoff execution it was born beside (if early), its instant. */
    public record Billing(BnplBilling row, java.time.LocalDate businessDate, Optional<UUID> payoffExecution, Instant at) {

        public Billing {
            Objects.requireNonNull(row, "row must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(payoffExecution, "payoffExecution must not be null");
            Objects.requireNonNull(at, "at must not be null");
        }
    }

    /** A repayment reversal and its negating rows - one per allocation row of the repayment, with the amount it negates. */
    public record Reversal(BnplRepaymentReversal row, List<Negation> negations) {

        public Reversal {
            Objects.requireNonNull(row, "row must not be null");
            negations = List.copyOf(negations);
        }
    }

    /** One negating row. */
    public record Negation(UUID allocation, Money amount) {

        public Negation {
            Objects.requireNonNull(allocation, "allocation must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }
    }

    /** One of the obligation's ledger accounts. */
    public record LedgerAccountRow(LedgerAccountId id, AccountPurpose purpose, CurrencyCode currency) {

        public LedgerAccountRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(purpose, "purpose must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
        }
    }

    /** Every line of one entry the purchase's rows name on accounts it shares - its financing's, a refund's. */
    public record EntryLines(JournalEntryId entry, List<JournalLine> lines) {

        public EntryLines {
            Objects.requireNonNull(entry, "entry must not be null");
            lines = List.copyOf(lines);
        }
    }

    /** The obligation's id. */
    public BnplObligationId id() {
        return obligation.id();
    }

    /** Zero in the obligation's currency, at its scale. */
    public Money zero() {
        Money gross = financing.split().gross();
        return Money.ofPersisted(0, gross.currency(), gross.scale());
    }

    /** The account of {@code purpose} among the obligation's own, if there is exactly one. */
    public Optional<LedgerAccountRow> account(AccountPurpose purpose) {
        List<LedgerAccountRow> found = new ArrayList<>();
        ledgerAccounts.forEach(row -> {
            if (row.purpose() == purpose) {
                found.add(row);
            }
        });
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /** The lines of {@code entry} on the shared accounts, if the record read them. */
    public List<JournalLine> sharedLinesOf(JournalEntryId entry) {
        return sharedEntries.stream().filter(found -> found.entry().equals(entry)).findFirst()
                .map(EntryLines::lines).orElse(List.of());
    }

    /**
     * The dispute hold the record's disputes placed as a fact written at {@code at} met it - the rows' rule
     * ({@link BnplDisputeHold}) over the disputes opened before {@code at}, each lifted only if it was resolved before
     * {@code at}. A dispute opened later on the same business date held nothing the servicing step had already done that
     * morning, and one resolved later that day still held what the step skipped: judged by business date alone, a fee or a
     * collection the step rightly wrote before the dispute existed replayed {@code DIVERGED} (`P12-TST-002`'s finding).
     */
    public BnplDisputeHold disputeHoldAt(Instant at) {
        Objects.requireNonNull(at, "at");
        List<BnplDisputeHold.Window> windows = new ArrayList<>();
        for (BnplDispute dispute : disputes) {
            if (dispute.openedOn().isEmpty()) {
                continue;
            }
            if (dispute.openedAt().isEmpty()) {
                // A dispute dated but not stamped would otherwise hold nothing and hide a fee on a held instalment.
                throw new IllegalStateException("a dispute opened on a business date carries its opening instant");
            }
            if (!dispute.openedAt().get().isBefore(at)) {
                continue;
            }
            Optional<java.time.LocalDate> lifted = dispute.resolvedAt().filter(resolved -> resolved.isBefore(at))
                    .flatMap(resolved -> dispute.resolvedOn());
            // A withdrawal's lift never moves the clock (the Phase 12 -> 13 gate's A5-F2; bnpl V018's rule).
            boolean withdrawn = dispute.status() == BnplDisputeStatus.WITHDRAWN;
            for (BnplDispute.Held held : dispute.holds()) {
                windows.add(new BnplDisputeHold.Window(dispute.id(), held.dueDate(), dispute.openedOn().get(), lifted,
                        withdrawn));
            }
        }
        return new BnplDisputeHold(windows);
    }

    /** The obligation and the counts only ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplRecord[" + obligation.id().value() + ", " + agreements.size() + " agreement versions, "
                + billings.size() + " billings, " + repayments.size() + " repayments, " + refunds.size() + " refunds, "
                + journalLines.size() + " lines]";
    }
}
