package com.finapp.accounting;

import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The product subledgers' existing proofs as close checks read them (`P14-TSK-001`; {@code PHASE_14_PLAN.md} sections
 * 3, 13.3; ADR-0117; {@code INV-ACC-12}) - lending's subledger and replay proofs, BNPL's three-way and subledger proofs,
 * fx's books proof, reconciliation's position proof, the merchant payable drill-down and the ledger's projection
 * verification: their verdicts and evidence, <strong>never re-implemented here</strong>. A finding becomes a
 * {@link BreakType#SOURCE_DISCREPANCY} break, never a correction.
 *
 * <p><strong>Read-only.</strong> Given its methods by `P14-TSK-010` (declared empty by `P14-TSK-001`); {@code app}'s
 * {@code ProductSourceProofs} implements it over the nine proofs. One call reads one {@link Source} - the proofs one
 * product computes in one reading (lending's subledger and replay; BNPL's subledger and three-way) - on the caller's
 * {@code REPEATABLE READ READ ONLY} snapshot, so the caller gives each source a snapshot of its own and one source's
 * failure blinds no other.
 *
 * <p><strong>Never a false pass.</strong> A source that cannot be read throws; a proof that could not judge everything
 * it covers answers {@link Reading#complete()} {@code false}; a proof missing from the answer is unread. Each of the three
 * makes {@code SOURCE_COMPLETENESS} {@code FAILED} ({@link SourceCompleteness}) - never assumed passing.
 */
public interface SourceProofs {

    /** One product's reading - the proofs computed together. */
    enum Source {
        LENDING,
        LENDING_CAPITAL,
        BNPL,
        FX_BOOKS,
        POSITION,
        MERCHANT_PAYABLE,
        PROJECTION;

        /** The proofs this source answers, in the catalogue's order. */
        public List<Proof> proofs() {
            return Arrays.stream(Proof.values()).filter(proof -> proof.source() == this).toList();
        }
    }

    /** The nine proofs the entry names - the catalogue {@code source-proofs:v1} judges by. */
    enum Proof {
        /** {@code LoanSubledgerProof}: each loan account's six balances explained by its rows ({@code INV-LND-01}). */
        LOAN_SUBLEDGER(Source.LENDING),
        /** {@code LoanReplayProof}: each loan's figures re-derived by its pinned engine ({@code INV-LND-05}, {@code -09}). */
        LOAN_REPLAY(Source.LENDING),
        /** {@code LendingCapitalProof}: lending capital complete and its headroom held, per currency ({@code INV-LND-13}). */
        LENDING_CAPITAL(Source.LENDING_CAPITAL),
        /** {@code BnplSubledgerProof}: each obligation's balances explained by its rows. */
        BNPL_SUBLEDGER(Source.BNPL),
        /** {@code BnplThreeWayProof}: merchant leg, customer leg, economic identity and ledger, per currency. */
        BNPL_THREE_WAY(Source.BNPL),
        /** {@code FxBooksProof}: each FX book per currency against the trades' and covers' rows ({@code INV-FX-06}). */
        FX_BOOKS(Source.FX_BOOKS),
        /** {@code PositionProof}: the clearing positions, suspense and cash explained by the register ({@code INV-REC-06}). */
        POSITION(Source.POSITION),
        /** The merchant payable drill-down: every payable's terms sum to its position ({@code INV-MER-02}). */
        MERCHANT_PAYABLE(Source.MERCHANT_PAYABLE),
        /** {@code ProjectionVerification}: every balance projection equal to its derivation ({@code INV-BAL-02}). */
        PROJECTION(Source.PROJECTION);

        private final Source source;

        Proof(Source source) {
            this.source = source;
        }

        /** The reading that computes this proof. */
        public Source source() {
            return source;
        }
    }

    /**
     * Every proof of {@code source}, read on {@code snapshot} - one {@link Reading} per proof of
     * {@link Source#proofs()}.
     *
     * @throws RuntimeException when the source cannot be read - its proofs are then unavailable, never passing
     */
    List<Reading> read(Connection snapshot, Source source);

    /**
     * One proof's verdict: whether it judged everything it covers, and each subject it found discrepant.
     *
     * @param complete false when the proof could not judge part of what it covers (an account in flight)
     */
    record Reading(Proof proof, boolean complete, List<Discrepancy> discrepancies) {

        public Reading {
            Objects.requireNonNull(proof, "proof");
            discrepancies = List.copyOf(discrepancies);
        }
    }

    /**
     * One discrepant subject of a proof.
     *
     * @param subject the proof's own subject - a loan, an obligation, a currency, a book, a position, a payable, an
     *     account - as ids and codes
     * @param evidence the proof's verdict - its failing arms by name and its counts, <strong>never an amount</strong>
     */
    record Discrepancy(String subject, String evidence) {

        public Discrepancy {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(evidence, "evidence");
            if (subject.isBlank()) {
                throw new IllegalArgumentException("a discrepancy names its subject");
            }
        }
    }
}
