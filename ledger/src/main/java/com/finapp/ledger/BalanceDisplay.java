package com.finapp.ledger;

import com.finapp.sharedkernel.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * The projection, read for <strong>display</strong> (`P3-TSK-013`) — the second of the two
 * readers `P3-TSK-009` named as decisions that must arrive and say what kind of number they
 * return (ADR-0041's consequence). The first was the verification comparison (`P3-TSK-010`,
 * verdicts and counts); this one returns balances, and says exactly what each is.
 *
 * <p><strong>What these numbers are</strong>: {@link DisplayedBalance#settled} is the projection
 * of posted lines ({@code account_balance.posted_minor} — transactional with every posting, so
 * current or absent along with the fact); {@link DisplayedBalance#holds} is the projection's
 * holds column (zero until `P3-TSK-015` populates it); {@link DisplayedBalance#available} is
 * {@code settled − holds}, which is {@code INV-BAL-04}'s <em>presentation</em> — the derived
 * answer to "what can I spend", shown, never enforced here.
 *
 * <p><strong>Display only, never a decision's input</strong> ({@code INV-BAL-05}). A hold, an
 * overdraft check or any other financial decision derives its number from the postings inside
 * the account lock (ADR-0039, ADR-0041 rule 2) — not from this read, which takes <strong>no
 * lock anywhere</strong>, precisely because a display must never contend with the write path it
 * mirrors (`P3-TSK-010`'s stance, restated by its sibling reader).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface BalanceDisplay<T> {

    /**
     * One product's balance in one currency. The three numbers are named for what they are
     * because a response that just said "balance" would silently mean whichever one its author
     * assumed — the collapse {@code PHASE_3_PLAN.md} §4 calls "how a platform authorises
     * against funds already committed".
     */
    record DisplayedBalance(Money settled, Money holds, Money available) {}

    /**
     * The balances of every ledger account owned by {@code ownerRef} — for a Customer Account,
     * one per currency (ADR-0042). A never-posted account has no projection row and answers
     * <strong>zero in its own currency</strong>, never a bare 0 ({@code INV-MON-02}) and never
     * an absence a caller must interpret.
     */
    List<DisplayedBalance> balancesFor(T unitOfWork, UUID ownerRef);
}
