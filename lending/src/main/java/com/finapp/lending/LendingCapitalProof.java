package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The capital proof (`P11-TSK-004`; ADR-0096 section 6, PHASE_11_PLAN.md section 12.9; {@code INV-LND-13}): per
 * supported currency, on the caller's connection - which the caller holds in ONE {@code REPEATABLE READ} read-only
 * snapshot, so the ledger's lines and reconciliation's recognitions are one state:
 *
 * <ol>
 *   <li><strong>Completeness</strong> - every journal line on the currency's {@code LENDING_CAPITAL} is a recognised
 *       contribution's: for every entry touching the account, its CR-DR there equals the sum of the recognitions
 *       recorded against that entry, and every recognition's entry touches it. A line no recognition explains - a raw
 *       SQL posting, a second poster - fails the currency; so does a recognition whose credit is missing.
 *   <li><strong>Headroom</strong> - recognised capital less committed and outstanding principal is at least zero at
 *       rest.
 *   <li><strong>The safeguarding adjustment</strong> - {@code lending_funded(c) = principal outstanding + committed}
 *       is published: the term by which a future safeguarding proof (Phase 14/15) subtracts loan-funded wallet money
 *       from customer-funded money. Published, not judged - an amount, so it leaves only through the audited capital
 *       report, never a metric (ADR-0072).
 * </ol>
 *
 * <p>Read-only and deciding nothing: a failure is an alert ({@code finapp.lending.capital.proof}), never a correction
 * - capital is never adjusted to fit.
 */
@RequiredArgsConstructor
public final class LendingCapitalProof {

    @NonNull private final LendingCapitalHeadroom headroom;
    @NonNull private final PurposeBalances<Connection> balances;
    @NonNull private final CapitalRecognitions recognitions;

    /** One currency's verdict: its terms, and which arms hold. */
    public record CurrencyVerdict(
            LendingCapitalHeadroom.Headroom terms, boolean complete, int unexplainedEntries, boolean headroomHolds) {

        public CurrencyVerdict {
            Objects.requireNonNull(terms, "terms must not be null");
        }

        public boolean holds() {
            return complete && headroomHolds;
        }

        public CurrencyCode currency() {
            return terms.currency();
        }

        /** The safeguarding adjustment {@code lending_funded(c)}. */
        public Money lendingFunded() {
            return terms.lendingFunded();
        }

        /** The verdict only - never a term ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "CurrencyVerdict[" + currency().code() + ", complete=" + complete + ", unexplained="
                    + unexplainedEntries + ", headroomHolds=" + headroomHolds + "]";
        }
    }

    /** The proof over every supported currency. */
    public record Report(List<CurrencyVerdict> currencies) {

        public Report {
            currencies = List.copyOf(currencies);
        }

        public boolean holds() {
            return currencies.stream().allMatch(CurrencyVerdict::holds);
        }

        public long failing() {
            return currencies.stream().filter(verdict -> !verdict.holds()).count();
        }

        public long holding() {
            return currencies.stream().filter(CurrencyVerdict::holds).count();
        }

        /**
         * How many currencies' headroom stands below its floor - {@code floors} in minor units by currency code, 0 when
         * absent - compared as longs here, so a gauge publishes the count and never touches an amount (ADR-0072).
         */
        public long belowFloor(Map<String, Long> floors) {
            return currencies.stream()
                    .filter(verdict -> verdict.terms().headroom().minorUnits()
                            < floors.getOrDefault(verdict.currency().code(), 0L))
                    .count();
        }
    }

    /** The proof, on the caller's snapshot. */
    public Report prove(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        List<CurrencyVerdict> verdicts = new ArrayList<>();
        for (CurrencyCode currency : SupportedCurrencies.ALL) {
            verdicts.add(prove(unitOfWork, currency));
        }
        return new Report(verdicts);
    }

    /** One currency's verdict, on the caller's snapshot. */
    public CurrencyVerdict prove(Connection unitOfWork, CurrencyCode currency) {
        LedgerAccount capital = headroom.capitalAccount(unitOfWork, currency);
        // What the ledger holds on the capital: CR-DR per entry.
        Map<JournalEntryId, Money> posted = new TreeMap<>(java.util.Comparator.comparing(JournalEntryId::value));
        for (PurposeBalances.EntryLine line : balances.linesOn(unitOfWork, capital.id())) {
            Money signed = line.direction() == Direction.CREDIT ? line.amount() : line.amount().negated();
            posted.merge(line.entry(), signed, LendingCapitalProof::sum);
        }
        // What the recognitions explain: the contributions recognised per entry.
        Map<JournalEntryId, Money> explained = new TreeMap<>(java.util.Comparator.comparing(JournalEntryId::value));
        for (CapitalRecognitions.Recognition recognition : recognitions.recognitions(unitOfWork, currency)) {
            explained.merge(recognition.entry(), recognition.amount(), LendingCapitalProof::sum);
        }
        int unexplained = 0;
        for (Map.Entry<JournalEntryId, Money> entry : posted.entrySet()) {
            Money recognised = explained.get(entry.getKey());
            if (recognised == null || !sameAmount(recognised, entry.getValue())) {
                unexplained++;
            }
        }
        for (JournalEntryId entry : explained.keySet()) {
            if (!posted.containsKey(entry)) {
                unexplained++;
            }
        }
        LendingCapitalHeadroom.Headroom terms = headroom.atRest(unitOfWork, currency);
        return new CurrencyVerdict(terms, unexplained == 0, unexplained, terms.headroom().minorUnits() >= 0);
    }

    private static Money sum(Money left, Money right) {
        if (left.isZero() && left.scale() != right.scale()) {
            return right;
        }
        if (right.isZero() && right.scale() != left.scale()) {
            return left;
        }
        return left.plus(right);
    }

    private static boolean sameAmount(Money left, Money right) {
        return left.currency().equals(right.currency()) && left.minorUnits() == right.minorUnits()
                && (left.scale() == right.scale() || left.isZero());
    }
}
