package com.finapp.merchant;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A payout the beneficiary bank returned (`P8-TSK-019`, ADR-0073 §1) — a merchant fact born once
 * beside a payout that stays {@code COMPLETED}. {@code RECORDED} is its only state, so it has no
 * machine: the row exists or it does not. Its money is exactly the payout's (merchant `V008`'s
 * composite foreign key), and its posting, {@code merchant-payout-return:<payoutId>}, credits the
 * payable back from {@code PAYOUT_CLEARING}.
 *
 * @param externalItemRef the reconciliation item whose evidence won the payout row — a copy, by
 *     value (ADR-0064)
 * @param returnedOn the posting date: the stored {@code accepted_on} of the item's batch
 * @param valueDate the item's settlement date
 */
public record PayoutReturn(
        UUID id,
        MerchantPayoutId payoutId,
        MerchantId merchantId,
        Money amount,
        UUID externalItemRef,
        JournalEntryId journalEntryId,
        LocalDate returnedOn,
        LocalDate valueDate,
        Instant recordedAt) {

    public PayoutReturn {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(payoutId, "payoutId must not be null");
        Objects.requireNonNull(merchantId, "merchantId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(externalItemRef, "externalItemRef must not be null");
        Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
        Objects.requireNonNull(returnedOn, "returnedOn must not be null");
        Objects.requireNonNull(valueDate, "valueDate must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        if (amount.isZero() || amount.isNegative()) {
            throw new IllegalArgumentException(
                    "a payout return carries the payout's positive amount");
        }
    }
}
