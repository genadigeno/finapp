package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.Money;
import java.util.Map;
import java.util.TreeMap;

/**
 * The position identities' residuals — what a suite judges when it claims the identity holds
 * over ITS OWN residue (`INV-REC-06`).
 *
 * <p>Every app database suite shares one database, so an absolute {@code explained()} over
 * the whole ledger judges every earlier suite's hygiene too: history a backfill has yet to
 * adopt — a quiet-double completion, a dispute suite's capture under its real attempt — fails
 * it, and the failure comes and goes with the class order. The residual is the identity's
 * gap: {@code DR-CR - (open remainders - open items)} per clearing position and currency, and
 * {@code CR-DR - (CREDIT - DEBIT + Phase 7 parkings)} per suspense currency — zero exactly
 * when explained. A suite whose own writes are explained leaves every residual where it found
 * it; one whose writes are not moves one, by the amount at fault. The whole-ledger claim at
 * rest, after a backfill, belongs to {@link ReconciledPositionResidueDatabaseTest}, judged
 * last.
 *
 * <p>Stated limit: a suite's error that exactly cancels a residual it did not create is
 * invisible to the difference — the sentinel's absolute reading is the backstop.
 */
public final class PositionResiduals {

    private PositionResiduals() {}

    /** Every residual in {@code report}, keyed {@code "PURPOSE CUR"}, zero where explained. */
    public static Map<String, Money> of(PositionProof.Report report) {
        Map<String, Money> residuals = new TreeMap<>();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            residuals.put(
                    verdict.purpose() + " " + verdict.currency(),
                    verdict.ledgerBalance()
                            .minus(verdict.openRemainders().minus(verdict.openItems())));
        }
        for (PositionProof.SuspenseVerdict verdict : report.suspenseVerdicts()) {
            residuals.put(
                    "SUSPENSE_UNMATCHED " + verdict.currency(),
                    verdict.ledgerBalance()
                            .minus(verdict.creditRemainders()
                                    .minus(verdict.debitRemainders())
                                    .plus(verdict.unadoptedParkings())));
        }
        return residuals;
    }

    /**
     * The suite's own writes are explained: every residual stands exactly where it stood
     * before them. The message names the position, its residual before and after.
     */
    public static void assertUnchanged(
            PositionProof.Report before, PositionProof.Report after, String context) {
        assertThat(of(after))
                .as("%s: every position identity's residual is unchanged by this suite's own"
                        + " writes - DR-CR - (open remainders - open items), and the suspense"
                        + " identity's (INV-REC-06)", context)
                .isEqualTo(of(before));
    }
}
