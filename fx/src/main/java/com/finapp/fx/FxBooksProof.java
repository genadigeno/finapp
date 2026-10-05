package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The FX books proof (`P9-TSK-013`; PHASE_9_PLAN.md section 12.9.4, {@code INV-FX-06}): per book
 * and currency, the ledger's balance - read through ledger's own {@link BalanceDerivation}, its
 * normal side's sense - against what fx's rows say it must be ({@link FxProofStore#expected}):
 *
 * <pre>
 *   FX_POSITION        DR-CR = trades' position legs (CR source, DR destination) + executed covers'
 *                              plan legs (DR sold, CR bought) - 0 at rest once every cover executed
 *   FX_SPREAD_REVENUE  CR-DR = the trades' margins, in each computed leg's currency
 *   ROUNDING_RESIDUAL  DR-CR = minus the trades' residuals, in each computed leg's currency
 *   FX_REALISED_GAINS  CR-DR = the executions' positive realised results, per leg currency
 *   FX_REALISED_LOSSES DR-CR = the executions' negative realised results, per leg currency
 * </pre>
 *
 * Report-only and never repairing: the caller runs it in ONE {@code REPEATABLE READ} snapshot so
 * the two sides are one instant. The reversal term arrives with the trade reversal
 * (`P9-TSK-025`), the unwind term with the unwind (`P9-TSK-021`); until then both are zero by
 * construction. It reads the FX books, never posts to them ({@code FxBooksHaveOnePosterTest}
 * permits it as their one reader).
 */
public final class FxBooksProof {

    /** The books the proof reads, in report order. */
    public static final List<AccountPurpose> BOOKS = List.of(
            AccountPurpose.FX_POSITION,
            AccountPurpose.FX_SPREAD_REVENUE,
            AccountPurpose.ROUNDING_RESIDUAL,
            AccountPurpose.FX_REALISED_GAINS,
            AccountPurpose.FX_REALISED_LOSSES);

    /** One book in one currency: what the rows say, what the ledger holds. */
    public record Line(AccountPurpose book, CurrencyCode currency, long expectedMinor, long ledgerMinor) {
        public boolean holds() {
            return expectedMinor == ledgerMinor;
        }
    }

    /** Every book in every supported currency. */
    public record Report(List<Line> lines) {
        public Report {
            lines = List.copyOf(lines);
        }

        /** How many currencies fail {@code book}'s identity - the gauge's value, which must be 0. */
        public long failing(AccountPurpose book) {
            return lines.stream().filter(line -> line.book() == book && !line.holds()).count();
        }

        public boolean clean() {
            return lines.stream().allMatch(Line::holds);
        }
    }

    private final FxProofStore store;
    private final ChartOfAccounts<Connection> chart;
    private final BalanceDerivation<Connection> balances;

    public FxBooksProof(FxProofStore store, ChartOfAccounts<Connection> chart, BalanceDerivation<Connection> balances) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.balances = Objects.requireNonNull(balances, "balances must not be null");
    }

    /** The proof, in the caller's snapshot. */
    public Report prove(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        FxProofStore.Expected expected = store.expected(unitOfWork);
        List<Line> lines = new ArrayList<>();
        for (AccountPurpose book : BOOKS) {
            Map<String, Long> sums = switch (book) {
                case FX_POSITION -> expected.position();
                case FX_SPREAD_REVENUE -> expected.margin();
                case ROUNDING_RESIDUAL -> expected.residual();
                case FX_REALISED_GAINS -> expected.gains();
                case FX_REALISED_LOSSES -> expected.losses();
                default -> throw new IllegalStateException("not an FX book: " + book);
            };
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                long ledger = balances.derive(unitOfWork, chart.resolve(unitOfWork, book, currency).id(), AsOf.latest())
                        .settled().minorUnits();
                lines.add(new Line(book, currency, sums.getOrDefault(currency.code(), 0L), ledger));
            }
        }
        return new Report(lines);
    }
}
