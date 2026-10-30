package com.finapp.app.accounting;

import com.finapp.accounting.AccrualCompleteness;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * accounting's {@link AccrualCompleteness} over the two accruers' own reads (`P14-TSK-006`; owner decision C3 - "the
 * close requires accruals caught up"; {@code PHASE_14_PLAN.md} section 13.5's {@code ACCRUALS_COMPLETE}) - in {@code app},
 * the one module that sees accounting, lending and bnpl. It adds nothing but the shape: lending's
 * {@code AccrualLags#oldestUnaccruedByCurrency} (instalment loans and credit lines, the line arm's zero-principal days
 * excepted) and bnpl's {@code BnplAccrualStore#oldestUnaccruedByCurrency} (pay-in-N obligations within their span), each
 * the oldest elapsed date some account in servicing should have accrued and has not, per currency - on the caller's unit
 * of work, read-only and lock-free.
 *
 * <p>The sources are functions so the composition is proven without a database ({@code ServicingAccrualCompletenessTest});
 * the beans pass the two module reads (`AccountingBeans`), whose SQL is proven by the modules' database suites.
 */
public final class ServicingAccrualCompleteness implements AccrualCompleteness {

    private final Function<Connection, Map<CurrencyCode, LocalDate>> lending;
    private final Function<Connection, Map<CurrencyCode, LocalDate>> bnpl;

    public ServicingAccrualCompleteness(
            Function<Connection, Map<CurrencyCode, LocalDate>> lending,
            Function<Connection, Map<CurrencyCode, LocalDate>> bnpl) {
        this.lending = Objects.requireNonNull(lending, "lending must not be null");
        this.bnpl = Objects.requireNonNull(bnpl, "bnpl must not be null");
    }

    @Override
    public List<Lagging> lagging(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        List<Lagging> lagging = new ArrayList<>();
        add(lagging, Accruer.LENDING, lending.apply(unitOfWork));
        add(lagging, Accruer.BNPL, bnpl.apply(unitOfWork));
        lagging.sort(Comparator.comparing(Lagging::accruer).thenComparing(Lagging::currency));
        return List.copyOf(lagging);
    }

    private static void add(List<Lagging> lagging, Accruer accruer, Map<CurrencyCode, LocalDate> oldest) {
        Objects.requireNonNull(oldest, "an accruer's read answers a map, empty when caught up");
        oldest.forEach((currency, date) -> lagging.add(new Lagging(accruer, currency.code(), date)));
    }
}
