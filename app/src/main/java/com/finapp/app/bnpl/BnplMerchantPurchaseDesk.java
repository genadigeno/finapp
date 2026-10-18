package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseReads;
import com.finapp.bnpl.MerchantBnplPurchaseView;
import com.finapp.merchant.AuthenticatedMerchant;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * The boundary half of the merchant's BNPL purchase read (`P12-TSK-014`; {@code PHASE_12_PLAN.md} sections 9 and 11;
 * ADR-0052, ADR-0031; {@code INV-MER-01}, {@code INV-MER-03}, {@code INV-CRD-04}): the tenant is the authenticated key's
 * merchant and nothing else - never a path, body or header value - bound into bnpl's statement; the read one
 * {@code REPEATABLE READ} read-only snapshot. Another merchant's purchase, an unknown one and a malformed id are one
 * {@code 404 bnpl.NotFound}, the same title and detail, so existence is never revealed.
 *
 * <p><strong>What the merchant sees</strong> is {@link BnplMerchantPurchaseView}'s closed set, mapped field for field from
 * bnpl's closed {@link MerchantBnplPurchaseView}: the purchase, checkout's session ({@code checkoutId}, the name checkout's
 * own merchant reads give it) and order, the product, the status, the gross, the fee and the net in exact decimal strings,
 * the fee schedule version the purchase pinned (never the merchant's current one) and the financing entry its transaction
 * read names. Nothing of the customer's credit. A read of one's own record is not audited (the merchant's payable and
 * transaction reads' precedent) and meters nothing of its own - the platform's HTTP series carries the route template.
 */
public final class BnplMerchantPurchaseDesk {

    /** Where the read runs: {@link BnplTransactions#inSnapshot}, one read-only {@code REPEATABLE READ} snapshot. */
    @FunctionalInterface
    interface Snapshot {
        <R> R inSnapshot(Function<Connection, R> work);
    }

    private final BnplPurchaseReads reads;
    private final Snapshot snapshot;

    public BnplMerchantPurchaseDesk(BnplPurchaseReads reads, BnplTransactions transactions) {
        this(reads, Objects.requireNonNull(transactions, "transactions")::inSnapshot);
    }

    BnplMerchantPurchaseDesk(BnplPurchaseReads reads, Snapshot snapshot) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }

    /**
     * A BNPL purchase as its merchant sees it - closed: nothing of the customer's credit has a field here
     * ({@code BnplMerchantPurchaseDeskTest#theViewIsClosed}). Amounts are exact decimal strings in {@code currency}.
     */
    public record BnplMerchantPurchaseView(
            String purchaseId,
            String checkoutId,
            String orderId,
            String product,
            String status,
            String currency,
            String gross,
            String fee,
            String net,
            String feeScheduleVersionId,
            String entryId,
            String financedAt) {}

    /** {@code merchant}'s BNPL purchase {@code rawId}; another merchant's is the same {@code 404} as an unknown one. */
    public BnplMerchantPurchaseView read(AuthenticatedMerchant merchant, String rawId) {
        BnplPurchaseId id = purchaseId(rawId);
        UUID tenant = merchant.merchantId().value();
        return snapshot.inSnapshot(unitOfWork -> reads.forMerchant(unitOfWork, tenant, id)
                        .map(BnplMerchantPurchaseDesk::view))
                .orElseThrow(BnplMerchantPurchaseDesk::notFound);
    }

    static BnplMerchantPurchaseView view(MerchantBnplPurchaseView purchase) {
        return new BnplMerchantPurchaseView(
                purchase.purchase().value().toString(),
                purchase.checkoutSession().toString(),
                purchase.order().toString(),
                purchase.product().name(),
                purchase.status().name(),
                purchase.gross().currency().code(),
                decimal(purchase.gross()),
                decimal(purchase.fee()),
                decimal(purchase.net()),
                purchase.feeScheduleVersion().toString(),
                purchase.financingEntry().value().toString(),
                purchase.financedAt().toString());
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private static BnplPurchaseId purchaseId(String raw) {
        try {
            return BnplPurchaseId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL purchase matches the requested identifier",
                "no such BNPL purchase.");
    }
}
