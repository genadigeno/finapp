package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The three-way proof (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12, gate criterion 5; ADR-0103, ADR-0105;
 * {@code INV-BNPL-01}, {@code -04}, {@code -06}, {@code -09}, {@code INV-MER-02}, {@code INV-BAL-02}, {@code INV-ACC-01},
 * {@code INV-LED-04}): every purchase's figures proven three ways, per currency, on ONE read-only {@code REPEATABLE READ}
 * snapshot.
 *
 * <ol>
 *   <li><strong>The merchant leg</strong> - per merchant: Σ over its purchases {@code (G - f)} less Σ its refunds' payable
 *       debits ({@code R - r_f}) equals the net of every line on its {@code MERCHANT_PAYABLE} under a {@code bnpl.} key, each
 *       such entry named by one of its financings or refunds and carrying exactly that row's net; and the payable still
 *       satisfies {@code INV-MER-02}'s equation with the BNPL terms ({@code -016}).
 *   <li><strong>The customer leg</strong> - every obligation's {@link BnplSubledgerProof} verdict.
 *   <li><strong>The economic identity</strong> - per purchase: {@code G = principal repaid + principal cancelled by refunds
 *       + principal outstanding} (+ written off, none in Phase 12); {@code Σ refunds = Σ principal cancelled + Σ excess}
 *       (a dispute's refund, door {@code DISPUTE}, among them - `-023`'s hand-off); fee recognised less fee returned equals
 *       the purchase's lines on {@code BNPL_MERCHANT_FEE_INCOME} - and the fee arm: the fee and every return recomputed
 *       under the purchase's pinned fee schedule version by merchant's one arithmetic ({@link BnplMerchants#returnedFee},
 *       which telescopes to {@code f} across a full refund - `-006`'s hand-off).
 *   <li><strong>The ledger</strong> - the trial balance zero in the currency; and, read in the same snapshot, lending's
 *       capital proof with its BNPL term ({@code -005}) and credit's exposure census ({@code -012}).
 * </ol>
 *
 * <p>Read-only: a {@code FAILS} is an alert, nothing is repaired. Each verdict names arms and ids, never an amount
 * ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public final class BnplThreeWayProof {

    @NonNull private final BnplProofReads reads;
    @NonNull private final BnplMerchants merchants;

    /** One merchant's leg in one currency. */
    public record MerchantLeg(UUID merchant, boolean payable, boolean sumHolds, int unexplainedEntries,
            int mismatchedEntries, boolean equationHolds) {

        /** Every arm holds. */
        public boolean holds() {
            return payable && sumHolds && unexplainedEntries == 0 && mismatchedEntries == 0 && equationHolds;
        }

        @Override
        public String toString() {
            return "MerchantLeg[" + merchant + ", " + (holds() ? "HOLDS" : "FAILS") + "]";
        }
    }

    /** One purchase's economic identity. */
    public record Identity(BnplPurchaseId purchase, boolean principalHolds, boolean refundsHold,
            boolean feeIncomeHolds, boolean feeArmHolds) {

        /** Every identity holds. */
        public boolean holds() {
            return principalHolds && refundsHold && feeIncomeHolds && feeArmHolds;
        }

        /** The identities that fail, by name. */
        public List<String> failures() {
            List<String> failures = new ArrayList<>();
            if (!principalHolds) {
                failures.add("PRINCIPAL");
            }
            if (!refundsHold) {
                failures.add("REFUNDS");
            }
            if (!feeIncomeHolds) {
                failures.add("FEE_INCOME");
            }
            if (!feeArmHolds) {
                failures.add("FEE_ARM");
            }
            return List.copyOf(failures);
        }

        @Override
        public String toString() {
            return "Identity[" + purchase.value() + ", " + (holds() ? "HOLDS" : "FAILS " + failures()) + "]";
        }
    }

    /** One currency's verdict: its four legs. */
    public record CurrencyVerdict(
            CurrencyCode currency,
            List<MerchantLeg> merchantLegs,
            List<BnplSubledgerProof.Verdict> customerLeg,
            List<Identity> identities,
            boolean trialBalanced,
            boolean capitalHolds,
            boolean exposureHolds) {

        public CurrencyVerdict {
            Objects.requireNonNull(currency, "currency");
            merchantLegs = List.copyOf(merchantLegs);
            customerLeg = List.copyOf(customerLeg);
            identities = List.copyOf(identities);
        }

        /** Every leg holds. */
        public boolean holds() {
            return failures().isEmpty();
        }

        /** The arms that fail, by name - never an amount. */
        public List<String> failures() {
            List<String> failures = new ArrayList<>();
            if (merchantLegs.stream().anyMatch(leg -> !leg.holds())) {
                failures.add("MERCHANT_LEG");
            }
            if (customerLeg.stream().anyMatch(verdict -> !verdict.holds())) {
                failures.add("CUSTOMER_LEG");
            }
            if (identities.stream().anyMatch(identity -> !identity.holds())) {
                failures.add("ECONOMIC_IDENTITY");
            }
            if (!trialBalanced) {
                failures.add("TRIAL_BALANCE");
            }
            if (!capitalHolds) {
                failures.add("CAPITAL");
            }
            if (!exposureHolds) {
                failures.add("EXPOSURE");
            }
            return List.copyOf(failures);
        }

        @Override
        public String toString() {
            return "CurrencyVerdict[" + currency.code() + ", " + (holds() ? "HOLDS" : "FAILS " + failures()) + "]";
        }
    }

    /**
     * Every currency of {@code records}, on {@code snapshot}, each obligation's subledger verdict given in
     * {@code subledger} (the customer leg, proven once for both proofs).
     */
    public List<CurrencyVerdict> prove(Connection snapshot, List<BnplRecord> records,
            Map<BnplObligationId, BnplSubledgerProof.Verdict> subledger) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(records, "records");
        Objects.requireNonNull(subledger, "subledger");
        Map<CurrencyCode, List<BnplRecord>> byCurrency = new TreeMap<>(java.util.Comparator.comparing(CurrencyCode::code));
        records.forEach(record -> byCurrency.computeIfAbsent(record.obligation().currency(), any -> new ArrayList<>())
                .add(record));
        boolean exposure = reads.exposureHolds(snapshot);
        List<CurrencyVerdict> verdicts = new ArrayList<>();
        byCurrency.forEach((currency, inCurrency) -> {
            Map<UUID, List<BnplRecord>> byMerchant = new LinkedHashMap<>();
            inCurrency.forEach(record -> byMerchant.computeIfAbsent(record.purchase().merchant(), any -> new ArrayList<>())
                    .add(record));
            List<MerchantLeg> legs = new ArrayList<>();
            byMerchant.forEach((merchant, ofMerchant) -> legs.add(merchantLeg(snapshot, merchant, currency, ofMerchant)));
            List<BnplSubledgerProof.Verdict> customer = new ArrayList<>();
            List<Identity> identities = new ArrayList<>();
            for (BnplRecord record : inCurrency) {
                BnplSubledgerProof.Verdict verdict = subledger.get(record.id());
                customer.add(verdict != null ? verdict : new BnplSubledgerProof().prove(record));
                identities.add(identity(snapshot, record));
            }
            verdicts.add(new CurrencyVerdict(currency, legs, customer, identities, reads.trialBalanced(snapshot, currency),
                    reads.capitalHolds(snapshot, currency), exposure));
        });
        return List.copyOf(verdicts);
    }

    // ------------------------------------------------------------------ the merchant leg

    /** {@code merchant}'s leg over its purchases {@code records} in {@code currency}. */
    MerchantLeg merchantLeg(Connection snapshot, UUID merchant, CurrencyCode currency, List<BnplRecord> records) {
        Money zero = records.get(0).zero();
        Optional<BnplProofReads.PayableLeg> leg = reads.payableLeg(snapshot, merchant, currency);
        if (leg.isEmpty()) {
            return new MerchantLeg(merchant, false, false, 0, 0, false);
        }
        // What the rows say each named entry left on the payable: CR (G - f) for a financing, DR (R - r_f) for a refund.
        Map<JournalEntryId, Money> expected = new HashMap<>();
        Money rows = zero;
        for (BnplRecord record : records) {
            MerchantFeeSplit split = record.financing().split();
            expected.put(record.financing().entry(), split.gross().minus(split.fee()));
            rows = rows.plus(split.gross().minus(split.fee()));
            for (BnplRecord.Stamped<BnplRefund> refund : record.refunds()) {
                expected.put(refund.row().entry(), refund.row().payableDebit().negated());
                rows = rows.minus(refund.row().payableDebit());
            }
        }
        // What the ledger holds on the payable under a bnpl. key, per entry (CR - DR).
        Map<JournalEntryId, Money> posted = new LinkedHashMap<>();
        Money ledger = zero;
        for (BnplProofReads.KeyedLine line : leg.get().lines()) {
            Money signed = line.direction() == Direction.CREDIT ? line.amount() : line.amount().negated();
            posted.merge(line.entry(), signed, Money::plus);
            ledger = ledger.plus(signed);
        }
        int unexplained = 0;
        int mismatched = 0;
        for (Map.Entry<JournalEntryId, Money> entry : posted.entrySet()) {
            Money row = expected.get(entry.getKey());
            if (row == null) {
                unexplained++;
            } else if (!row.equals(entry.getValue())) {
                mismatched++;
            }
        }
        for (Map.Entry<JournalEntryId, Money> entry : expected.entrySet()) {
            if (!posted.containsKey(entry.getKey()) && !entry.getValue().isZero()) {
                mismatched++; // a row whose entry left nothing on the payable
            }
        }
        return new MerchantLeg(merchant, true, rows.equals(ledger), unexplained, mismatched, leg.get().equationHolds());
    }

    // ------------------------------------------------------------------ the economic identity

    /** {@code record}'s purchase's identities. */
    Identity identity(Connection snapshot, BnplRecord record) {
        Money zero = record.zero();
        MerchantFeeSplit split = record.financing().split();
        Money gross = split.gross();
        Map<UUID, Money> negated = BnplSubledgerProof.negated(record);
        Money repaid = zero;
        for (BnplRecord.Stamped<BnplPurchaseStore.Repayment> repayment : record.repayments()) {
            for (BnplPurchaseStore.Allocation line : repayment.row().allocations()) {
                if ("DUE_ITEM".equals(line.target())
                        && AllocationComponent.PRINCIPAL.name().equals(line.component().orElse(""))) {
                    repaid = repaid.plus(line.amount()).minus(negated.getOrDefault(line.id(), zero));
                }
            }
        }
        for (BnplRecord.Stamped<BnplCreditBalanceApplication> application : record.applications()) {
            for (BnplCreditBalanceApplication.Line line : application.row().lines()) {
                if (line.component() == AllocationComponent.PRINCIPAL) {
                    repaid = repaid.plus(line.amount());
                }
            }
        }
        Money cancelled = zero;
        Money refunded = zero;
        Money excess = zero;
        Money returned = zero;
        for (BnplRecord.Stamped<BnplRefund> refund : record.refunds()) {
            cancelled = cancelled.plus(refund.row().cancelledUnbilled()).plus(refund.row().cancelledBilled());
            refunded = refunded.plus(refund.row().amount());
            excess = excess.plus(refund.row().excess());
            returned = returned.plus(refund.row().feeReturned());
        }
        Map<AccountPurpose, Money> ledger = BnplSubledgerProof.ledger(record);
        Money outstanding = ledger.get(AccountPurpose.BNPL_PRINCIPAL).plus(ledger.get(AccountPurpose.BNPL_PRINCIPAL_DUE));
        boolean principal = gross.equals(repaid.plus(cancelled).plus(outstanding));
        boolean refunds = refunded.equals(cancelled.plus(excess));

        // The purchase's lines on the currency's merchant fee income: CR - DR over its financing's and refunds' entries.
        boolean feeIncome = false;
        if (record.merchantFeeIncome().isPresent()) {
            LedgerAccountId income = record.merchantFeeIncome().get();
            Set<JournalEntryId> entries = new HashSet<>();
            entries.add(record.financing().entry());
            record.refunds().forEach(refund -> entries.add(refund.row().entry()));
            Money net = zero;
            for (JournalEntryId entry : entries) {
                for (JournalLine line : record.sharedLinesOf(entry)) {
                    if (line.account().equals(income)) {
                        net = line.direction() == Direction.CREDIT ? net.plus(line.amount()) : net.minus(line.amount());
                    }
                }
            }
            feeIncome = net.equals(split.fee().minus(returned));
        }
        return new Identity(record.purchase().id(), principal, refunds, feeIncome, feeArm(snapshot, record));
    }

    /**
     * The fee arm: the financing's fee and every refund's return recomputed under the purchase's pinned version by
     * merchant's cumulative arithmetic - {@code returnedFee(0, G) = f} (it telescopes), each refund's
     * {@code returnedFee(refunded before, R)} its {@code r_f}, its refunded-before the Σ of the refunds before it.
     */
    boolean feeArm(Connection snapshot, BnplRecord record) {
        Money zero = record.zero();
        MerchantFeeSplit split = record.financing().split();
        try {
            if (!merchants.returnedFee(snapshot, record.purchase().id(), zero, split.gross()).equals(split.fee())) {
                return false;
            }
            Money before = zero;
            for (BnplRecord.Stamped<BnplRefund> refund : record.refunds().stream()
                    .sorted(java.util.Comparator.comparing(BnplRecord.Stamped::at)).toList()) {
                if (!refund.row().refundedBefore().equals(before) || !merchants.returnedFee(snapshot,
                        record.purchase().id(), before, refund.row().amount()).equals(refund.row().feeReturned())) {
                    return false;
                }
                before = before.plus(refund.row().amount());
            }
            return true;
        } catch (RuntimeException unpriced) {
            return false; // no pin, or a refund beyond the gross: the rows are not what merchant's arithmetic admits
        }
    }
}
