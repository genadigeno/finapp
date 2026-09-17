package com.finapp.transfers;

/**
 * The limit/velocity seam (`ROADMAP.md` §Refinement 2: introduced Phase 4, implemented
 * Phase 13). A <strong>required parameter</strong> of the execution command with no defaulted
 * overload — the {@code PostingObserver} compiler-enforced precedent: Phase 13's wiring must be
 * a decision, and a skipped control must not compile.
 *
 * <p><strong>The contract is in-lock evaluation</strong>: the execution consults this after the
 * source-account row lock is held, so a Phase 13 implementation inherits atomicity with the
 * movement it limits instead of discovering {@code INV-CON-03}'s race — a limit checked outside
 * the lock is a limit two instances pass together. Phase 4's implementation is
 * {@link PermitAllUntilPhase13}, named for what it is; `P4-TSK-010` hardens this contract and
 * reserves the refusal vocabulary.
 */
public interface TransferLimitCheck {

    /**
     * Judges {@code transfer} (an {@code INITIATED} aggregate, the caller's inputs resolved)
     * under the held source lock. Phase 4's implementation permits everything and throws
     * nothing.
     */
    void check(Transfer transfer);
}
