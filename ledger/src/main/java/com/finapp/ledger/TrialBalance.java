package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The trial balance (`P3-TSK-019`, {@code INV-ACC-01}): across all postings, total debits
 * equal total credits <strong>per currency</strong>, at all times — the system-level
 * expression of {@code INV-LED-01}, and the primary continuous correctness signal.
 *
 * <h2>What a non-zero reading means</h2>
 *
 * <p>Every committed entry balances per currency (V004's deferred constraint triggers), so by
 * induction every committed journal does. This job verifies the induction against reality —
 * the writer the triggers never met, the migration that dropped them, the corruption nobody
 * planned — because an invariant enforced but unobserved is one whose first violation is
 * found at reconciliation, if at all. <strong>A finding is reported, never repaired</strong>
 * (the drift job's rule, {@code PHASE_3_PLAN.md} §14.12): this class issues exactly one
 * {@code SELECT}, and repair is a reasoned adjustment (`P3-TSK-017`), because a ledger that
 * corrected itself would destroy the evidence of what went wrong.
 *
 * <h2>Why there is no {@code IN_FLIGHT} verdict</h2>
 *
 * <p>One SQL statement reads one snapshot, and a snapshot can never contain half an entry —
 * entries commit atomically, and every committed entry balances at COMMIT. So every snapshot
 * of a healthy journal balances <strong>exactly</strong>, under any concurrency, with no
 * watermark, no bracket and no tolerance window: the sibling verification job needed
 * {@code IN_FLIGHT} because its two reads could interleave with a commit; this job's one
 * read cannot. A sweep racing ten posting instances reads zero every time, or something is
 * genuinely wrong.
 *
 * <h2>Where this deviates from the {@code Money} fold, and why that is admissible</h2>
 *
 * <p>`P3-TSK-008`'s argument against SQL aggregates names two failure modes, and both are
 * structurally closed at this one statement: cross-scale addition cannot occur because
 * {@code scale} is a <strong>grouping key</strong> (no sum crosses a scale boundary in SQL),
 * and silent widening cannot occur because {@code SUM(bigint)} is {@code numeric} —
 * arbitrary precision, read back as {@link BigDecimal} exactly. The per-currency totals then
 * combine as <strong>exact decimal arithmetic</strong>: no rounding exists to be implicit in
 * decimal addition ({@code INV-MON-03} has no subject), and no currencies mix — the currency
 * is the bucket key ({@code INV-MON-04}). {@code Money} itself is deliberately not used: a
 * per-group system-wide sum can legitimately exceed {@code long}, and {@code Money}'s
 * overflow refusal would turn a large <em>balanced</em> ledger into a false incident — the
 * one failure a monitoring job must not produce. Cross-scale totals within one currency are
 * a legal <em>system-wide</em> state (unlike per-account, where the derivation refuses a
 * mixed history), and exact decimal addition neither refuses a legal state nor rescales.
 *
 * <h2>What leaves this class</h2>
 *
 * <p>Verdicts and currency codes, <strong>never an amount</strong> — the
 * {@code ProjectionVerification} stance: an imbalance magnitude is a financial figure, and
 * nothing read from a monitoring sweep may become a decision's input or reach telemetry
 * ({@code INV-AUD-02}).
 */
public final class TrialBalance {

    /**
     * One sweep's answer: how many currencies were verified, and which of them are out of
     * balance. An empty {@code outOfBalance} over a non-empty journal is the healthy state;
     * a currency with no lines at all is vacuously balanced and simply not counted.
     */
    public record Report(long currenciesVerified, List<CurrencyCode> outOfBalance) {
        public Report {
            outOfBalance = List.copyOf(outOfBalance);
        }
    }

    /**
     * Sweeps the whole journal in one statement — one snapshot — and answers per currency.
     * Read-only and idempotent: every instance may sweep independently and all reach the
     * same fleet-wide answer.
     */
    public Report sweep(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        // Per (currency, scale): the signed decimal total, debits positive. Grouping by
        // scale in SQL is what keeps every SQL-side addition single-scale; the exact
        // decimal combination across scales happens here, per currency.
        Map<String, BigDecimal> differenceByCurrency = new LinkedHashMap<>();
        try (PreparedStatement read =
                        unitOfWork.prepareStatement(
                                "SELECT currency, scale, direction, SUM(amount_minor) AS total"
                                        + " FROM ledger.journal_line"
                                        + " GROUP BY currency, scale, direction"
                                        + " ORDER BY currency, scale, direction");
                ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                String currency = rows.getString("currency").stripTrailing();
                int scale = rows.getInt("scale");
                BigDecimal total = rows.getBigDecimal("total").movePointLeft(scale);
                BigDecimal signed =
                        Direction.valueOf(rows.getString("direction")) == Direction.DEBIT
                                ? total
                                : total.negate();
                differenceByCurrency.merge(currency, signed, BigDecimal::add);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("sweeping the trial balance", failure));
        }

        List<CurrencyCode> outOfBalance = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> currency : differenceByCurrency.entrySet()) {
            if (currency.getValue().compareTo(BigDecimal.ZERO) != 0) {
                outOfBalance.add(CurrencyCode.of(currency.getKey()));
            }
        }
        return new Report(differenceByCurrency.size(), outOfBalance);
    }
}
