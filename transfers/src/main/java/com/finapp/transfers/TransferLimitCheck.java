package com.finapp.transfers;

/**
 * The limit/velocity seam (`ROADMAP.md` §Refinement 2: introduced Phase 4, implemented
 * Phase 13). A <strong>required parameter</strong> of the execution command with no defaulted
 * overload — the {@code PostingObserver} compiler-enforced precedent: Phase 13's wiring must be
 * a decision, and a skipped control must not compile.
 *
 * <h2>The contract is in-lock evaluation, and the unit of work is how it is honoured</h2>
 *
 * <p><strong>The caller's obligation</strong>: the execution consults this only while the
 * source-account row's {@code FOR UPDATE} is held, in the execution's own transaction — a limit
 * checked outside the lock is a limit two instances pass together ({@code INV-CON-03}'s race),
 * and the in-lock contract is asserted by a decorator probe observing the lock held, not merely
 * stated here.
 *
 * <p><strong>The implementation's obligation</strong>: any state a verdict rests on lives in
 * durable rows read — and any counter written — through {@code unitOfWork}, so the judgement
 * and the movement it judges commit atomically or not at all. Never process memory
 * ({@code INV-CON-03}: an authoritative check is anchored to durable state; a cache may exist
 * only as explicitly non-authoritative — ADR-0024, `DISTRIBUTED_EXECUTION.md` §3's row for this
 * seam), never a clock read (a versioned policy artefact carries its own effective dates,
 * {@code INV-HIST-04}), and never a cached verdict: judged per call, like the consent gate.
 *
 * <p>Phase 4's implementation is {@link PermitAllUntilPhase13}, named for what it is; a refusal
 * commits {@code FAILED(LIMIT_REFUSED)} — reserved by `P4-TSK-010` so the Phase 13
 * implementation changes no contract anywhere.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface TransferLimitCheck<T> {

    /**
     * Judges {@code transfer} (an {@code INITIATED} aggregate, the caller's inputs resolved)
     * under the held source lock, on the execution's own {@code unitOfWork}.
     */
    SeamVerdict check(T unitOfWork, Transfer transfer);
}
