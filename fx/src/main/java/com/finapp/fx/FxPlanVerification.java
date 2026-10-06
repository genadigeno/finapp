package com.finapp.fx;

import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.ExchangeRate;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The FX plan replay (`P9-TSK-013`; PHASE_9_PLAN.md section 12.9.4, {@code INV-FX-05}, the
 * ADR-0068 section 9 discipline applied to FX): every booked trade's plan recomputed from its
 * quote's stored inputs ALONE - the provider's rate and stated counter, the frozen spread, markup
 * and roundings, the pinned policy version's bounds - through the same pure
 * {@link ConversionPlan#compute}, and compared with the stored trade, the stored internal rate,
 * the disclosed margin recomputed against the stored reference, and the posted entry line by line
 * ({@link ConversionLines#compose}). The spread is no reconciliation break - no external evidence
 * states it - so a divergence is OUR defect: logged CRITICAL, the verdict gauge set. Report-only,
 * in the caller's {@code REPEATABLE READ} snapshot; never repairing.
 */
@Slf4j
public final class FxPlanVerification {

    /** One trade the replay could not reproduce, and what differed - never an amount. */
    public record Divergence(FxTradeId trade, String what) {}

    /** The replay's verdict. */
    public record Report(int verified, List<Divergence> divergences) {
        public Report {
            divergences = List.copyOf(divergences);
        }

        public boolean clean() {
            return divergences.isEmpty();
        }
    }

    private final FxProofStore store;
    private final QuoteStore quotes;
    private final PricingPolicyStore policies;
    private final JournalEntryStore<Connection> entries;
    private final ChartOfAccounts<Connection> chart;

    public FxPlanVerification(
            FxProofStore store,
            QuoteStore quotes,
            PricingPolicyStore policies,
            JournalEntryStore<Connection> entries,
            ChartOfAccounts<Connection> chart) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
        this.policies = Objects.requireNonNull(policies, "policies must not be null");
        this.entries = Objects.requireNonNull(entries, "entries must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
    }

    /** Replays every booked trade, in the caller's snapshot. */
    public Report verify(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        List<Divergence> divergences = new ArrayList<>();
        List<FxProofStore.ReplayRow> rows = store.replayRows(unitOfWork);
        for (FxProofStore.ReplayRow row : rows) {
            replay(unitOfWork, row).ifPresent(what -> {
                log.error("CRITICAL: FX trade {} does not replay: {} (INV-FX-05)", row.tradeId().value(), what);
                divergences.add(new Divergence(row.tradeId(), what));
            });
        }
        return new Report(rows.size(), divergences);
    }

    /** What differs, or nothing. */
    Optional<String> replay(Connection unitOfWork, FxProofStore.ReplayRow row) {
        CurrencyCode source = CurrencyCode.of(row.sourceCurrency());
        CurrencyCode destination = CurrencyCode.of(row.destinationCurrency());
        // Priced under the purpose the quote was issued for - a cross-border quote's terms are its own (P9-TSK-018).
        PricingPurpose purpose = quotes.plan(unitOfWork, row.quoteId()).map(QuoteStore.PlanRow::purpose)
                .orElse(PricingPurpose.CONVERSION);
        Optional<PolicyPair> pinned = policies.version(unitOfWork, row.version())
                .flatMap(version -> QuoteIssuance.termsFor(version, purpose, source, destination));
        if (pinned.isEmpty()) {
            return Optional.of("its pinned policy version no longer prices the pair");
        }
        PricingPair bounds = pinned.get().pricing();
        PricingPair frozen = new PricingPair(source, destination, new Margin(row.spread()), new Margin(row.markup()),
                row.rateScale(), RoundingPolicy.valueOf(row.rateRounding()), RoundingPolicy.valueOf(row.amountRounding()),
                RoundingPolicy.valueOf(row.marginRounding()), bounds.sourceBounds(), bounds.destinationBounds());
        boolean bySource = row.fixedSide() == FixedSide.FIXED_SOURCE;
        Money fixed = bySource
                ? Money.ofPersisted(row.customerSourceMinor(), source, row.sourceScale())
                : Money.ofPersisted(row.customerDestinationMinor(), destination, row.destinationScale());
        Money statedCounter = bySource
                ? Money.ofPersisted(row.positionDestinationMinor(), destination, row.destinationScale())
                : Money.ofPersisted(row.positionSourceMinor(), source, row.sourceScale());
        ProviderQuote providerQuote = new ProviderQuote(
                ExchangeRate.of(source, destination, row.providerRate()), statedCounter,
                Duration.ofMillis(row.providerValidForMillis()));
        ConversionPlan.Result result;
        try {
            result = ConversionPlan.compute(row.fixedSide(), fixed, providerQuote, frozen);
        } catch (IllegalArgumentException unreplayable) {
            return Optional.of("its stored inputs are not a computable plan");
        }
        if (!(result instanceof ConversionPlan.Priced priced)) {
            return Optional.of("the replay refused: " + result.getClass().getSimpleName());
        }
        ConversionPlan.Plan plan = priced.plan();
        List<String> differs = new ArrayList<>();
        compare(differs, "customer source", plan.customerPays().minorUnits(), row.customerSourceMinor());
        compare(differs, "customer destination", plan.customerReceives().minorUnits(), row.customerDestinationMinor());
        compare(differs, "position source", plan.positionSource().minorUnits(), row.positionSourceMinor());
        compare(differs, "position destination", plan.positionDestination().minorUnits(), row.positionDestinationMinor());
        compare(differs, "margin", plan.margin().minorUnits(), row.marginMinor());
        compare(differs, "spread margin", plan.spreadMargin().minorUnits(), row.spreadMarginMinor());
        compare(differs, "markup margin", plan.markupMargin().minorUnits(), row.markupMarginMinor());
        compare(differs, "residual", plan.residual().minorUnits(), row.residualMinor());
        compareRate(differs, "customer rate", plan.customerRate().value(), row.storedCustomerRate());
        compareRate(differs, "internal rate", plan.internalRate().value(), row.storedInternalRate());
        ReferencePair canonical = QuoteIssuance.referencePair(source, destination);
        BigDecimal disclosed = ConversionPlan.disclosedMarginOverMid(plan.customerRate(),
                ExchangeRate.of(canonical.base(), canonical.quote(), row.referenceRate()));
        compareRate(differs, "disclosed margin", disclosed, row.storedDisclosedMargin());
        entryDiffers(unitOfWork, row).ifPresent(differs::add);
        return differs.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", differs) + " differ");
    }

    /** The posted entry against the stored plan's lines, the two wallets read off the entry itself. */
    private Optional<String> entryDiffers(Connection unitOfWork, FxProofStore.ReplayRow row) {
        if (row.journalEntryId() == null) {
            return Optional.of("the entry");
        }
        QuoteStore.PlanRow plan = quotes.plan(unitOfWork, row.quoteId()).orElse(null);
        Optional<JournalEntryStore.PostedEntry> posted = entries.findById(unitOfWork, JournalEntryId.of(row.journalEntryId()));
        if (plan == null || posted.isEmpty()) {
            return Optional.of("the entry");
        }
        List<JournalLine> lines = posted.get().entry().lines();
        ConversionLines.Accounts books = ConversionLines.accounts(chart, unitOfWork, plan,
                LedgerAccountId.of(row.journalEntryId()), LedgerAccountId.of(row.journalEntryId()));
        // A cross-border completion (P9-TSK-020) also charges the corridor fee into FEE_REVENUE and credits the
        // corridor's clearing in place of a destination wallet: the fee line is read off the entry, the rest replayed.
        boolean crossBorder = plan.purpose() == PricingPurpose.CROSS_BORDER;
        Optional<LedgerAccountId> feeRevenue = crossBorder
                ? Optional.of(chart.resolve(unitOfWork, com.finapp.ledger.AccountPurpose.FEE_REVENUE, plan.source()).id())
                : Optional.empty();
        Set<LedgerAccountId> bookAccounts = new java.util.HashSet<>(Set.of(books.sourcePosition(),
                books.destinationPosition(), books.spreadRevenue(), books.roundingResidual()));
        feeRevenue.ifPresent(bookAccounts::add);
        Optional<LedgerAccountId> sourceWallet = lines.stream()
                .filter(line -> !bookAccounts.contains(line.account()) && line.direction() == Direction.DEBIT
                        && line.amount().currency().equals(plan.source()))
                .map(JournalLine::account).findFirst();
        Optional<LedgerAccountId> destinationWallet = lines.stream()
                .filter(line -> !bookAccounts.contains(line.account()) && line.direction() == Direction.CREDIT
                        && line.amount().currency().equals(plan.destination()))
                .map(JournalLine::account).findFirst();
        if (sourceWallet.isEmpty() || destinationWallet.isEmpty()) {
            return Optional.of("the entry's wallets");
        }
        ConversionLines.Accounts accounts = ConversionLines.accounts(
                chart, unitOfWork, plan, sourceWallet.get(), destinationWallet.get());
        List<JournalLine> expected;
        if (crossBorder) {
            Money fee = lines.stream()
                    .filter(line -> line.account().equals(feeRevenue.get()) && line.direction() == Direction.CREDIT)
                    .map(JournalLine::amount).findFirst()
                    .orElse(Money.ofPersisted(0, plan.source(), plan.sourceScale()));
            expected = ConversionLines.composeCrossBorder(plan, accounts, feeRevenue.get(), fee);
        } else {
            expected = ConversionLines.compose(plan, accounts);
        }
        return expected.equals(lines) ? Optional.empty() : Optional.of("the entry's lines");
    }

    private static void compare(List<String> differs, String what, long replayed, long stored) {
        if (replayed != stored) {
            differs.add(what);
        }
    }

    private static void compareRate(List<String> differs, String what, BigDecimal replayed, BigDecimal stored) {
        if (replayed.compareTo(stored) != 0) {
            differs.add(what);
        }
    }
}
