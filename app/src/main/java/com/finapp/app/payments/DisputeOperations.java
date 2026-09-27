package com.finapp.app.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.merchant.MerchantId;
import com.finapp.payments.Dispute;
import com.finapp.payments.DisputeId;
import com.finapp.payments.DisputeReads;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.PaymentIntentId;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispute read surfaces behind the controllers (`P7-TSK-012`) — parse and refuse at the
 * boundary, one transaction per read, the {@code RoutingPolicyOperations} shape.
 *
 * <h2>The merchant's tenant is its own payables, resolved from the key</h2>
 *
 * <p>A merchant's disputes are the disputes on payments that credited the merchant's payable.
 * The payables come from the ledger's owner-scoped read of the authenticated merchant
 * ({@code owner_ref = ?} in its statement, the {@code MerchantPayable} precedent), and ride the
 * dispute statement as its tenant predicate ({@code INV-MER-01}) — in the same transaction, so
 * nothing is load-then-compared. Another merchant's dispute, an unknown one and a malformed
 * identifier are one 404. A wallet top-up's dispute credits a customer's wallet, so it is on
 * no merchant's list.
 *
 * <h2>The operator's reads are privileged and on the record</h2>
 *
 * <p>Under {@code DISPUTE_ADMINISTER}, across tenants by design; every dispute shown writes
 * {@code payments.DisputeRead} in the read's own transaction (the domain's
 * {@code DisputeReads}). Only the operator's view carries the attempt and the network's
 * dispute reference — reconciliation's keys, classified {@code CONFIDENTIAL} and nobody else's
 * business.
 */
@RequiredArgsConstructor
public class DisputeOperations {

    /** The merchant listing's bound: the newest this many, and {@code truncated} says when
     * there were more. */
    static final int MERCHANT_LISTING_BOUND = 100;

    @NonNull private final DisputeReads reads;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;

    /** One move on a dispute's trail, in the platform's own stage names. */
    public record StageChangeView(String from, String to, String at) {}

    /**
     * A merchant's dispute, as its listing shows it. {@code chargebackAmount} is what the
     * network took, a decimal string — {@code null} (with its currency) while the dispute is an
     * inquiry that has taken nothing, or one closed without a chargeback.
     */
    public record MerchantDisputeSummary(
            String disputeId,
            String paymentIntentId,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String openedAt) {}

    /** The merchant's listing: newest first, at most 100 ({@code MERCHANT_LISTING_BOUND}). */
    public record MerchantDisputeList(List<MerchantDisputeSummary> disputes, boolean truncated) {}

    /** One of the merchant's disputes, with its trail. */
    public record MerchantDisputeView(
            String disputeId,
            String paymentIntentId,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String openedAt,
            List<StageChangeView> history) {}

    /** A dispute as the operator reads it: the merchant's view plus reconciliation's keys. */
    public record OperatorDisputeView(
            String disputeId,
            String paymentIntentId,
            String attemptId,
            String providerDisputeReference,
            String stage,
            String reason,
            String chargebackAmount,
            String chargebackCurrency,
            String openedAt,
            List<StageChangeView> history) {}

    /** One payment's disputes, oldest first. */
    public record OperatorDisputeList(List<OperatorDisputeView> disputes) {}

    /** The merchant's disputes, newest first. */
    public MerchantDisputeList listForMerchant(MerchantId merchant) {
        List<DisputeStore.Found> found =
                inOneTransaction(
                        unitOfWork ->
                                reads.listForCounterparties(
                                        unitOfWork,
                                        payablesOf(unitOfWork, merchant),
                                        MERCHANT_LISTING_BOUND + 1));
        boolean truncated = found.size() > MERCHANT_LISTING_BOUND;
        return new MerchantDisputeList(
                found.stream()
                        .limit(MERCHANT_LISTING_BOUND)
                        .map(DisputeOperations::summary)
                        .toList(),
                truncated);
    }

    /** One of the merchant's disputes; another merchant's, unknown and malformed are one 404. */
    public MerchantDisputeView readForMerchant(MerchantId merchant, String rawDisputeId) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        DisputeReads.Read read =
                inOneTransaction(
                                unitOfWork ->
                                        reads.readForCounterparties(
                                                unitOfWork, id, payablesOf(unitOfWork, merchant)))
                        .orElseThrow(DisputeOperations::disputeNotFound);
        Dispute dispute = read.found().dispute();
        return new MerchantDisputeView(
                dispute.id().value().toString(),
                read.found().intentId().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                dispute.openedAt().toString(),
                history(read.history()));
    }

    /** The operator's read of one dispute — audited; unknown and malformed are one 404. */
    public OperatorDisputeView readForOperator(String rawDisputeId) {
        DisputeId id = parsedDisputeOrAbsent(rawDisputeId);
        DisputeReads.Read read =
                inOneTransaction(unitOfWork -> reads.readForOperator(unitOfWork, id))
                        .orElseThrow(DisputeOperations::disputeNotFound);
        return operatorView(read.found(), read.history());
    }

    /**
     * The operator's read of one payment's disputes — each dispute shown audited; an unknown
     * payment is the 404, a payment nobody disputed an empty list.
     */
    public OperatorDisputeList listForPaymentForOperator(String rawIntentId) {
        PaymentIntentId intent = parsedIntentOrAbsent(rawIntentId);
        List<DisputeReads.Read> found =
                inOneTransaction(unitOfWork -> reads.listForPaymentForOperator(unitOfWork, intent))
                        .orElseThrow(DisputeOperations::paymentNotFound);
        return new OperatorDisputeList(
                found.stream().map(read -> operatorView(read.found(), read.history())).toList());
    }

    // -----------------------------------------------------------------

    /**
     * The merchant's payables, from the ledger's OWNER-SCOPED read of the authenticated
     * merchant: every account here is provably this merchant's, which is what makes the
     * dispute statement's predicate a tenant predicate.
     */
    private Set<LedgerAccountId> payablesOf(Connection unitOfWork, MerchantId merchant) {
        return ledgerAccounts.findAllOwned(unitOfWork, merchant.value()).stream()
                .filter(account -> account.purpose() == AccountPurpose.MERCHANT_PAYABLE)
                .map(LedgerAccount::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static MerchantDisputeSummary summary(DisputeStore.Found found) {
        Dispute dispute = found.dispute();
        return new MerchantDisputeSummary(
                dispute.id().value().toString(),
                found.intentId().value().toString(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                dispute.openedAt().toString());
    }

    private static OperatorDisputeView operatorView(
            DisputeStore.Found found, List<DisputeStore.StageChange> trail) {
        Dispute dispute = found.dispute();
        return new OperatorDisputeView(
                dispute.id().value().toString(),
                found.intentId().value().toString(),
                dispute.attemptId().value().toString(),
                dispute.providerReference().value(),
                dispute.stage().name(),
                dispute.reason().name(),
                chargebackAmount(dispute),
                chargebackCurrency(dispute),
                dispute.openedAt().toString(),
                history(trail));
    }

    /** What the network took, as a decimal string - null until the funds are taken. */
    private static String chargebackAmount(Dispute dispute) {
        return dispute.chargeback()
                .map(amount -> amount.toBigDecimal().toPlainString())
                .orElse(null);
    }

    private static String chargebackCurrency(Dispute dispute) {
        return dispute.chargeback().map(amount -> amount.currency().code()).orElse(null);
    }

    private static List<StageChangeView> history(List<DisputeStore.StageChange> trail) {
        return trail.stream()
                .map(
                        change ->
                                new StageChangeView(
                                        change.from().name(),
                                        change.to().name(),
                                        change.at().toString()))
                .toList();
    }

    private static DisputeId parsedDisputeOrAbsent(String raw) {
        try {
            return DisputeId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            // Malformed folds into unknown: one 404, and the shape rule leaks nothing.
            throw disputeNotFound();
        }
    }

    private static PaymentIntentId parsedIntentOrAbsent(String raw) {
        try {
            return PaymentIntentId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException malformed) {
            throw paymentNotFound();
        }
    }

    private static ApiException disputeNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No dispute visible to the caller matches the requested identifier",
                "no such dispute.");
    }

    private static ApiException paymentNotFound() {
        return new ApiException(
                PlatformErrorCode.NOT_FOUND,
                "No payment matches the requested identifier",
                "no such payment.");
    }

    private <R> R inOneTransaction(Function<Connection, R> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
