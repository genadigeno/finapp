package com.finapp.payments;

import com.finapp.ledger.JournalLine;
import java.util.List;

/**
 * Composes the journal lines an approved capture posts (`P6-TSK-005`, ADR-0050 §6) — the seam
 * that lets a capture settle two ways without {@code payments} learning a second vocabulary.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Until this task {@link PaymentOutcomes} <em>wrote</em> the capture's two lines itself:
 * {@code DR SETTLEMENT_CLEARING / CR wallet}, correct for Phase 5's top-up and correct for
 * nothing else. A merchant-bound capture posts four (ADR-0050 §3) — the gross to the
 * merchant's payable and the platform's fee out of it — and composing those here would mean
 * this module knowing what a merchant is, what a fee is and which schedule version priced it.
 * It must not: provider adapters stay fee-blind and fee vocabulary never enters this domain
 * model, which is {@code INV-PAY-03}'s discipline applied to a second vocabulary.
 *
 * <p>So the flow that <em>created</em> the intent supplies the lines, through this port, and
 * the capture posts what it is handed.
 *
 * <h2>What an implementation may and may not do</h2>
 *
 * <p>It runs <strong>inside the capture's own transaction</strong>, on the caller's
 * connection, after the conditional transition has already been won — so it runs exactly once
 * per capture however many resolvers raced. It may therefore write: records it must commit
 * with the posting, and outbox rows announcing what it composed. It must <strong>not</strong>
 * swallow a failure: a composition that cannot price this capture has to throw, because the
 * alternative is posting fewer lines than the money owes and calling the capture done. There
 * is no savepoint around it, deliberately — {@code PaymentCapture}'s recorded stance for the
 * posting itself, inherited rather than re-decided.
 *
 * <p>The lines it returns must balance per currency; {@code PostingService} refuses them
 * otherwise ({@code INV-LED-01}), which is the third rank behind the composer's own arithmetic
 * and the test that asserts the entry by {@code DIRECTION:PURPOSE}.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CaptureComposition<T> {

    /**
     * The lines this capture posts.
     *
     * @throws RuntimeException if the capture cannot be composed — which fails the whole
     *     capture transaction, on purpose
     */
    List<JournalLine> settle(T unitOfWork, CaptureSettlement settlement);

    /**
     * The entry landed, and here is its identifier — the composing flow's chance to record
     * what that means, <strong>in the same transaction</strong> (`P6-TSK-007`).
     *
     * <p><strong>Why a second moment rather than one.</strong> {@link #settle} runs BEFORE the
     * posting, because its answer is what gets posted; but a flow whose consequence references
     * the entry — a checkout order carrying the journal entry that paid for it — cannot know
     * the identifier until afterwards. Folding both into one call would mean either composing
     * after the fact (impossible) or recording against an entry that does not exist yet
     * (wrong). Two moments, one transaction, and {@code payments} still names nothing it
     * cannot see: it says <em>these lines</em>, then <em>that entry</em>.
     *
     * <p>Runs on the caller's connection, inside the capture's own transaction, after the
     * conditional transition has been won — so exactly once per capture however many
     * resolvers raced. It may write; it must not swallow a failure, for {@link #settle}'s
     * reason.
     *
     * @param entryRef the journal entry the posting produced or converged on
     */
    void settled(T unitOfWork, CaptureSettlement settlement, java.util.UUID entryRef);
}
