package com.finapp.app.bnpl;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What BNPL's operations reports read (`P12-TSK-026`) - bnpl's rows and the ledger's lines as of one business date, every
 * method on the caller's ONE read-only {@code REPEATABLE READ} snapshot, so a report's rows and its ties are one state. A
 * seam of its own so {@link BnplReports}' assembly - the sums, the ties, the empty unknown date, the audit - is proven
 * without a database ({@code BnplReportsTest}); {@link JdbcBnplReportReads} is the statements
 * ({@code BnplReportsDatabaseTest}). Read-only: nothing here decides or writes.
 *
 * <p>Amounts are minor units at their persisted scale, summed in SQL over {@code bigint} into {@code numeric} and read back
 * as {@link BigDecimal} exactly; signed sums are {@code DR - CR} unless a record says otherwise.
 */
public interface BnplReportReads {

    /** Whether {@code day} is a business date the platform has had: on or after the earliest, begun in some zone. */
    boolean reportable(Connection snapshot, LocalDate day);

    /** Obligations born on or before {@code day}, by product, currency and their status at its end. */
    List<ObligationCount> obligations(Connection snapshot, LocalDate day);

    /** The lines on bnpl's obligations' accounts whose entry's value date is on or before {@code day}, by product. */
    List<Balance> obligationBalances(Connection snapshot, LocalDate day);

    /** The same from the ledger's side: every {@code BNPL_OBLIGATION}-owned account, whoever names it. */
    List<Balance> bookBalances(Connection snapshot, LocalDate day);

    /** Whether DR - CR over every line through {@code day} is zero, per currency - the scales combined exactly. */
    Map<String, Boolean> trialBalance(Connection snapshot, LocalDate day);

    /** The merchant financings of purchases on or before {@code day}, and the refunds valued by then, per currency. */
    List<Financing> financing(Connection snapshot, LocalDate day);

    /** CR - DR on every {@code MERCHANT_PAYABLE} of the financings' and refunds' entries valued by {@code day}. */
    List<Balance> payableLines(Connection snapshot, LocalDate day);

    /** The refunds valued ON {@code day}, by product, currency, kind and door, each tied to its own entry. */
    List<RefundSum> refunds(Connection snapshot, LocalDate day);

    /** {@code ACTIVE} obligations at the end of {@code day} by product, currency and recorded bucket, and the defaulted. */
    List<DelinquencyCount> delinquency(Connection snapshot, LocalDate day);

    /** Their due balances through {@code day} - principal, interest and fees due - by product, currency and bucket. */
    List<Balance> dues(Connection snapshot, LocalDate day);

    /** Each named obligation's agreement - the explanation door's identifier. */
    Map<UUID, UUID> agreementsOf(Connection snapshot, Collection<UUID> obligations);

    // ------------------------------------------------------------------ rows

    /** How many obligations of {@code product} in {@code currency} stood in {@code status}. */
    record ObligationCount(String product, String currency, String status, long count) {

        public ObligationCount {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * A signed sum of lines on {@code purpose} at one scale - {@code group} the row's grouping (a product, a bucket key, or
     * empty for the currency's whole book).
     */
    record Balance(String group, String currency, int scale, String purpose, BigDecimal signed) {

        public Balance {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(purpose, "purpose");
            signed = signed == null ? BigDecimal.ZERO : signed;
        }
    }

    /** One currency's merchant financings and the refunds against them. */
    record Financing(
            String currency, int scale, long purchases, BigDecimal gross, BigDecimal fee, BigDecimal net,
            long refunds, BigDecimal refunded, BigDecimal feeReturned, BigDecimal payableDebit) {

        public Financing {
            Objects.requireNonNull(currency, "currency");
        }
    }

    /**
     * One group of a day's refunds - what their rows say they did and what their entries did to the obligations'
     * receivables: {@code ledgerReceivables} CR - DR on principal, principal due, interest accrued and interest due,
     * {@code ledgerFeesReleased} CR - DR on fees due (the late fees cancelled instalments carried).
     */
    record RefundSum(
            String product, String currency, int scale, String kind, String door, long refunds, BigDecimal amount,
            BigDecimal principalCancelled, BigDecimal interestReleased, BigDecimal excessToWallet,
            BigDecimal excessToCreditBalance, BigDecimal feeReturned, BigDecimal payableDebit, BigDecimal ledgerReceivables,
            BigDecimal ledgerFeesReleased) {

        public RefundSum {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(door, "door");
        }
    }

    /** How many {@code ACTIVE} obligations stood in {@code bucket}, and how many of them were defaulted. */
    record DelinquencyCount(String product, String currency, String bucket, long obligations, long defaulted) {

        public DelinquencyCount {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(bucket, "bucket");
        }
    }
}
