package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import java.util.Objects;
import java.util.UUID;

/**
 * A BNPL purchase's merchant financing (`P12-TSK-013`, {@code bnpl V006}'s {@code merchant_financing}; owner decision B2,
 * {@code INV-BNPL-02}, {@code INV-BNPL-04}): the merchant paid upfront, net of its fee - the split merchant's pinned BNPL
 * fee schedule version priced ({@link MerchantFeeSplit}: {@code G = f + net}, conserved by construction) and the entry F1
 * that credited the merchant's payable the net and recognised the fee. INSERT-only: once born, the merchant leg never
 * changes; a refund is its own fact (`P12-TSK-021`).
 *
 * @param entry the financing entry F1 ({@code bnpl.financing:<purchase>})
 */
public record MerchantFinancing(BnplPurchaseId purchase, UUID merchant, MerchantFeeSplit split, JournalEntryId entry) {

    public MerchantFinancing {
        Objects.requireNonNull(purchase, "purchase must not be null");
        Objects.requireNonNull(merchant, "merchant must not be null");
        Objects.requireNonNull(split, "split must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
    }

    /** The purchase and the entry only - never a figure or the merchant ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "MerchantFinancing[" + purchase.value() + ", entry " + entry.value() + "]";
    }
}
