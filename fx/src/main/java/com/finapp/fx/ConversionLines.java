package com.finapp.fx;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A wallet conversion's journal lines, composed from the quote's frozen plan and nothing else
 * (`P9-TSK-009`; PHASE_9_PLAN.md section 12.4(a), (d), (e); ADR-0076; INV-FX-01, INV-FX-03,
 * INV-FX-07). Each currency balances on its own - the wallet against {@code FX_POSITION} - and
 * nothing is converted inside a line:
 *
 * <pre>
 *   S: DR CUSTOMER_WALLET(S) customer source  /  CR FX_POSITION(S) position source
 *   D: DR FX_POSITION(D) position destination /  CR CUSTOMER_WALLET(D) customer destination
 *   computed leg: CR FX_SPREAD_REVENUE margin (no line at zero);
 *                 ROUNDING_RESIDUAL - CR when positive (the platform kept the fraction), DR when
 *                 negative (the platform bears it), no line at zero
 * </pre>
 *
 * <p>The plan identity the quote's {@code CHECK} holds is exactly what makes each currency
 * balance. With {@code CoverLines} (`P9-TSK-012`) this is the only code that names the FX books
 * ({@code FxBooksHaveOnePosterTest}).
 */
public final class ConversionLines {

    private ConversionLines() {}

    /** The six accounts a conversion touches, resolved by the caller. */
    public record Accounts(
            LedgerAccountId sourceWallet,
            LedgerAccountId destinationWallet,
            LedgerAccountId sourcePosition,
            LedgerAccountId destinationPosition,
            LedgerAccountId spreadRevenue,
            LedgerAccountId roundingResidual) {

        public Accounts {
            Objects.requireNonNull(sourceWallet, "sourceWallet must not be null");
            Objects.requireNonNull(destinationWallet, "destinationWallet must not be null");
            Objects.requireNonNull(sourcePosition, "sourcePosition must not be null");
            Objects.requireNonNull(destinationPosition, "destinationPosition must not be null");
            Objects.requireNonNull(spreadRevenue, "spreadRevenue must not be null");
            Objects.requireNonNull(roundingResidual, "roundingResidual must not be null");
        }
    }

    /**
     * Resolves the platform's four accounts of the plan - the position in each currency, and the
     * spread revenue and rounding residual in the computed leg's - beside the customer's two
     * wallets. The only place a conversion names the FX books.
     */
    public static Accounts accounts(
            ChartOfAccounts<Connection> chart,
            Connection unitOfWork,
            QuoteStore.PlanRow plan,
            LedgerAccountId sourceWallet,
            LedgerAccountId destinationWallet) {
        return new Accounts(
                sourceWallet,
                destinationWallet,
                chart.resolve(unitOfWork, AccountPurpose.FX_POSITION, plan.source()).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_POSITION, plan.destination()).id(),
                chart.resolve(unitOfWork, AccountPurpose.FX_SPREAD_REVENUE, plan.computedCurrency()).id(),
                chart.resolve(unitOfWork, AccountPurpose.ROUNDING_RESIDUAL, plan.computedCurrency()).id());
    }

    /** The plan's lines, in a stable order: the source currency's, then the destination's. */
    public static List<JournalLine> compose(QuoteStore.PlanRow plan, Accounts accounts) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(accounts, "accounts must not be null");
        List<JournalLine> lines = new ArrayList<>();
        boolean sourceComputed = plan.fixedSide() == FixedSide.FIXED_DESTINATION;
        lines.add(new JournalLine(accounts.sourceWallet(), Direction.DEBIT, plan.customerSource()));
        lines.add(new JournalLine(accounts.sourcePosition(), Direction.CREDIT, plan.positionSource()));
        if (sourceComputed) {
            computedLeg(plan, accounts, lines);
        }
        lines.add(new JournalLine(accounts.destinationPosition(), Direction.DEBIT, plan.positionDestination()));
        lines.add(new JournalLine(accounts.destinationWallet(), Direction.CREDIT, plan.customerDestination()));
        if (!sourceComputed) {
            computedLeg(plan, accounts, lines);
        }
        return List.copyOf(lines);
    }

    /**
     * A cross-border completion's lines (`P9-TSK-020`, PHASE_9_PLAN.md section 12.4(g)): the plan's own, its
     * destination credited to the corridor's clearing (passed as {@code accounts.destinationWallet()}), and the
     * corridor fee - the source wallet debited the plan's source plus the fee, {@code FEE_REVENUE} credited the
     * fee - inside the one entry. A zero fee posts no fee line.
     */
    public static List<JournalLine> composeCrossBorder(
            QuoteStore.PlanRow plan, Accounts accounts, LedgerAccountId feeRevenue, Money fee) {
        Objects.requireNonNull(feeRevenue, "feeRevenue must not be null");
        Objects.requireNonNull(fee, "fee must not be null");
        if (!fee.currency().equals(plan.source())) {
            throw new IllegalArgumentException("the corridor fee is charged in the source currency");
        }
        List<JournalLine> lines = new ArrayList<>(compose(plan, accounts));
        if (fee.isPositive()) {
            lines.set(0, new JournalLine(accounts.sourceWallet(), Direction.DEBIT, plan.customerSource().plus(fee)));
            lines.add(1, new JournalLine(feeRevenue, Direction.CREDIT, fee));
        }
        return List.copyOf(lines);
    }

    private static void computedLeg(QuoteStore.PlanRow plan, Accounts accounts, List<JournalLine> lines) {
        if (plan.marginMinor() > 0) {
            lines.add(new JournalLine(accounts.spreadRevenue(), Direction.CREDIT,
                    Money.ofPersisted(plan.marginMinor(), plan.computedCurrency(), plan.computedScale())));
        }
        if (plan.residualMinor() != 0) {
            lines.add(new JournalLine(accounts.roundingResidual(),
                    plan.residualMinor() > 0 ? Direction.CREDIT : Direction.DEBIT,
                    Money.ofPersisted(Math.abs(plan.residualMinor()), plan.computedCurrency(), plan.computedScale())));
        }
    }
}
