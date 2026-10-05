package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A cover's journal lines, composed from the plan's position legs and the provider's execution
 * and nothing else (`P9-TSK-012`; PHASE_9_PLAN.md sections 12.4(b)/(f); ADR-0077 section 6;
 * INV-FX-06, INV-FX-08). The cover ALWAYS closes exactly what the conversion opened on
 * {@code FX_POSITION}, moving it onto the provider's own clearing at the executed amounts; any
 * difference is the platform's realised result in that leg's currency - never netted between the
 * gain and the loss, never converted:
 *
 * <pre>
 *   sold (the plan's source):        DR FX_POSITION plan sold   / CR provider clearing executed sold
 *                                    realised = plan - executed: CR FX_REALISED_GAINS when positive
 *                                    (the platform delivered less), DR FX_REALISED_LOSSES when negative
 *   bought (the plan's destination): DR provider clearing executed bought / CR FX_POSITION plan bought
 *                                    realised = executed - plan: CR GAINS when positive, DR LOSSES when negative
 * </pre>
 *
 * <p>With {@code ConversionLines} this is the only code that names the FX books
 * ({@code FxBooksHaveOnePosterTest}); the provider's clearing purpose is read off its declaration
 * ({@code CounterpartyClearingIsNamedByDeclarationsTest}), never named here.
 */
public final class CoverLines {

    private CoverLines() {}

    /** The plan's position legs the cover closes: what the conversion credited and debited. */
    public record Plan(Money sold, Money bought, FixedSide fixedSide) {
        public Plan {
            Objects.requireNonNull(sold, "sold must not be null");
            Objects.requireNonNull(bought, "bought must not be null");
            Objects.requireNonNull(fixedSide, "fixedSide must not be null");
            if (sold.currency().equals(bought.currency())) {
                throw new IllegalArgumentException("a cover converts between two currencies");
            }
        }

        /** The plan a quote froze: its position source sold, its position destination bought. */
        public static Plan of(QuoteStore.PlanRow plan) {
            return new Plan(plan.positionSource(), plan.positionDestination(), plan.fixedSide());
        }
    }

    /** What the provider executed, in the plan's two currencies. */
    public record Execution(Money sold, Money bought) {
        public Execution {
            Objects.requireNonNull(sold, "sold must not be null");
            Objects.requireNonNull(bought, "bought must not be null");
        }
    }

    /** The realised result per leg, signed (positive a gain), and whether the fixed leg deviated. */
    public record Realised(long soldMinor, long boughtMinor, boolean offPlan) {}

    /** Whether {@code execution} is in the plan's currencies, sold for sold and bought for bought. */
    public static boolean coherent(Plan plan, Execution execution) {
        return execution.sold().currency().equals(plan.sold().currency())
                && execution.bought().currency().equals(plan.bought().currency())
                && execution.sold().scale() == plan.sold().scale()
                && execution.bought().scale() == plan.bought().scale()
                && execution.sold().isPositive()
                && execution.bought().isPositive();
    }

    /** The realised result: the sold leg kept {@code plan - executed}, the bought leg gained {@code executed - plan}. */
    public static Realised realised(Plan plan, Execution execution) {
        requireCoherent(plan, execution);
        long sold = plan.sold().minorUnits() - execution.sold().minorUnits();
        long bought = execution.bought().minorUnits() - plan.bought().minorUnits();
        boolean offPlan = plan.fixedSide() == FixedSide.FIXED_SOURCE ? sold != 0 : bought != 0;
        return new Realised(sold, bought, offPlan);
    }

    /** The eight accounts a cover can touch, resolved by the caller. */
    public record Accounts(
            LedgerAccountId soldPosition,
            LedgerAccountId boughtPosition,
            LedgerAccountId soldClearing,
            LedgerAccountId boughtClearing,
            LedgerAccountId soldGains,
            LedgerAccountId soldLosses,
            LedgerAccountId boughtGains,
            LedgerAccountId boughtLosses) {

        public Accounts {
            Objects.requireNonNull(soldPosition, "soldPosition must not be null");
            Objects.requireNonNull(boughtPosition, "boughtPosition must not be null");
            Objects.requireNonNull(soldClearing, "soldClearing must not be null");
            Objects.requireNonNull(boughtClearing, "boughtClearing must not be null");
            Objects.requireNonNull(soldGains, "soldGains must not be null");
            Objects.requireNonNull(soldLosses, "soldLosses must not be null");
            Objects.requireNonNull(boughtGains, "boughtGains must not be null");
            Objects.requireNonNull(boughtLosses, "boughtLosses must not be null");
        }
    }

    /**
     * Resolves the platform's accounts of a cover: the position and the P&L books in each
     * currency, and the provider's OWN clearing account in each ({@code INV-RAIL-04}) - its purpose
     * the provider's declaration's.
     */
    public static Accounts accounts(
            ChartOfAccounts<Connection> chart,
            Connection unitOfWork,
            AccountPurpose clearingPurpose,
            String providerCode,
            CurrencyCode sold,
            CurrencyCode bought) {
        return new Accounts(
                chart.resolve(unitOfWork, AccountPurpose.FX_POSITION, sold).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_POSITION, bought).id(),
                chart.resolve(unitOfWork, clearingPurpose, providerCode, sold).id(),
                chart.resolve(unitOfWork, clearingPurpose, providerCode, bought).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_REALISED_GAINS, sold).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_REALISED_LOSSES, sold).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_REALISED_GAINS, bought).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_REALISED_LOSSES, bought).id());
    }

    /** The cover's lines, in a stable order: the sold currency's, then the bought currency's. */
    public static List<JournalLine> compose(Plan plan, Execution execution, Accounts accounts) {
        Objects.requireNonNull(accounts, "accounts must not be null");
        Realised realised = realised(plan, execution);
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(accounts.soldPosition(), Direction.DEBIT, plan.sold()));
        lines.add(new JournalLine(accounts.soldClearing(), Direction.CREDIT, execution.sold()));
        result(realised.soldMinor(), plan.sold(), accounts.soldGains(), accounts.soldLosses(), lines);
        lines.add(new JournalLine(accounts.boughtClearing(), Direction.DEBIT, execution.bought()));
        lines.add(new JournalLine(accounts.boughtPosition(), Direction.CREDIT, plan.bought()));
        result(realised.boughtMinor(), plan.bought(), accounts.boughtGains(), accounts.boughtLosses(), lines);
        return List.copyOf(lines);
    }

    private static void result(long minor, Money leg, LedgerAccountId gains, LedgerAccountId losses, List<JournalLine> lines) {
        if (minor > 0) {
            lines.add(new JournalLine(gains, Direction.CREDIT, Money.ofPersisted(minor, leg.currency(), leg.scale())));
        } else if (minor < 0) {
            lines.add(new JournalLine(losses, Direction.DEBIT, Money.ofPersisted(-minor, leg.currency(), leg.scale())));
        }
    }

    private static void requireCoherent(Plan plan, Execution execution) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        if (!coherent(plan, execution)) {
            throw new IllegalArgumentException("an execution in other currencies than its plan's closes nothing");
        }
    }
}
