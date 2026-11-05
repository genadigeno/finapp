package com.finapp.accounting.reporting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.ReportKind;
import com.finapp.accounting.TrialBalanceReads;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The {@code SAFEGUARDING_POSITION} report definition, version 1 (`P14-TSK-019`; ADR-0118 decision 4, ADR-0096 section 6; owner decision C13; {@code INV-ACC-13}, {@code INV-ACC-04}, {@code INV-ACC-14}, {@code INV-RAIL-04}): per currency, cash and clearing receivables against customer and merchant liabilities, {@code lending_funded} subtracted. A <strong>reference figure</strong>: it asserts no legal
 * obligation, and an obligation version draws from it only as {@code REFERENCE}.
 *
 * <pre>
 * assets                    = cash at bank + clearing receivables
 * liabilities               = customer wallets + merchant payables + credit balances + clearing payables + suspense held
 * lendingFunded             = principal outstanding + committed + written off
 * customerFundedLiabilities = liabilities - lendingFunded
 * position                  = assets - customerFundedLiabilities
 * </pre>
 *
 * <p><strong>FINAL, because every read is frozen.</strong> The journal's reads are the trial balance's own statements, by posting date, and the ledger refuses a line dated in a locked period; {@code committed} and {@code writtenOff} come through {@link LendingFundedAsOfCutoff}, never lending's live proof. A later disbursement, cancellation, acceptance or write-off changes no byte of a month already locked. A reopen
 * supersedes the run; its new revision holds every journal line dated in the month, and {@code committed} does not move, because no fact behind it did.
 *
 * <p><strong>Mapped by ledger purpose, under this version</strong> ({@link #DISPOSITIONS}), never through a GL mapping's sections: a new mapping version can move no figure here. The version a run pins decides only each row's {@code glAccount}, the drill-down key. {@code SafeguardingPurposeCensusTest} holds the table total over the ledger's enum, so a purpose a later phase adds fails until someone disposes of it.
 *
 * <p><strong>Nothing nets between counterparties</strong> ({@code INV-RAIL-04}): each counterparty of a {@code COUNTERPARTY}-owned purpose is a row of its own, its debit balance a clearing receivable and its credit balance a clearing payable, never set against another's. The position is the same either way; the two lines are gross. An operational clearing account is one account per currency: a figure against its normal side reads negative and is never moved.
 *
 * <p><strong>A shortfall is a figure, never a refusal.</strong> A position reads negative, unfloored; {@code shortfall} says so beside it. A form's rule on it would make the return impossible to validate in exactly the month it matters.
 *
 * <p><strong>A zero is a read that succeeded.</strong> Each element of {@code currencies} holds every term: a currency with wallets and no lending carries zero terms from this run's own reads, and a read that fails throws, so no FINAL exists and the draft is refused. A currency nothing touched has no element: no scale exists without an amount, and no JDK table decides one ({@code ReportingNeverWritesTest}).
 *
 * <p>Report code: it reads through its ports on the snapshot it is handed and writes nothing. No clock, no random; every list sorted. No rate set.
 */
@RequiredArgsConstructor
public final class SafeguardingPositionDefinition implements ReportDefinition {

    /** The definition's code - {@code ReportKind.SAFEGUARDING_POSITION}. */
    public static final String CODE = ReportKind.SAFEGUARDING_POSITION.name();

    /** The definition's version: what {@link #generate} writes. */
    public static final int VERSION = 1;

    /** What a purpose does in this return. */
    public enum Treatment {
        /** An asset: cash. */
        CASH,
        /** An asset: a clearing receivable. */
        CLEARING_RECEIVABLE,
        /** A counterparty's clearing position: its debit balance a receivable, its credit balance a payable, each counterparty on its own. */
        COUNTERPARTY_CLEARING,
        /** A liability: a customer's stored value. */
        CUSTOMER_WALLET,
        /** A liability: a merchant's. */
        MERCHANT_PAYABLE,
        /** A liability: money a borrower or BNPL customer is owed back. */
        CREDIT_BALANCE,
        /** A liability: money already owed onward, the cash not yet gone. */
        CLEARING_PAYABLE,
        /** A liability at its net credit only. */
        SUSPENSE,
        /** The outstanding term of {@code lending_funded}. */
        PRINCIPAL,
        /** Out of the position, its figure served beside it. */
        MEMO,
        /** Out of the position: shown as a row, the route to a write-off's entries, and never a figure. */
        WRITE_OFF_ROUTE,
        /** Out of the return. */
        OUT
    }

    /** One purpose's place in this return, and why: {@code PURPOSE/OWNER_KIND}, the ledger's names. */
    public record Disposition(String key, Treatment treatment, String reason) {

        public Disposition {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(treatment, "treatment");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private static final String INCOME = "the income statement's, no asset or liability here";
    private static final String RECEIVABLE = "income earned and not collected: not cash or clearing, and capital never funded it (A29)";

    /** Every ledger purpose, in or out, with why. What a change here produces is a new {@link #VERSION}. */
    public static final List<Disposition> DISPOSITIONS = List.of(
            new Disposition("CASH_AT_BANK/OPERATIONAL", Treatment.CASH, "the platform's cash, which only the bank's statement posts"),
            new Disposition("SETTLEMENT_CLEARING/OPERATIONAL", Treatment.CLEARING_RECEIVABLE, "what the card rail owes, in flight to cash"),
            new Disposition("INSTANT_CLEARING/OPERATIONAL", Treatment.CLEARING_RECEIVABLE, "what the instant scheme owes, in flight to cash"),
            new Disposition("FX_PROVIDER_CLEARING/COUNTERPARTY", Treatment.COUNTERPARTY_CLEARING, "what an FX provider owes, or is owed: each counterparty on its own side"),
            new Disposition("CORRIDOR_CLEARING/COUNTERPARTY", Treatment.COUNTERPARTY_CLEARING, "what a corridor provider is owed, or owes: the wallet was debited and the cash has not left"),
            new Disposition("CUSTOMER_WALLET/CUSTOMER", Treatment.CUSTOMER_WALLET, "a customer's stored value"),
            new Disposition("MERCHANT_PAYABLE/MERCHANT", Treatment.MERCHANT_PAYABLE, "what the platform owes a merchant, a BNPL purchase's financing among it"),
            new Disposition("LOAN_CREDIT_BALANCE/LOAN", Treatment.CREDIT_BALANCE, "money received beyond every receivable, owed back to a borrower"),
            new Disposition("BNPL_CREDIT_BALANCE/BNPL_OBLIGATION", Treatment.CREDIT_BALANCE, "money received beyond every receivable, owed back to a BNPL customer"),
            new Disposition("PAYOUT_CLEARING/OPERATIONAL", Treatment.CLEARING_PAYABLE, "a payout instructed: the payable was debited and the cash has not left, so out the position would swing by every payout in flight"),
            new Disposition("SUSPENSE_UNMATCHED/SUSPENSE", Treatment.SUSPENSE, "money received and not attributed may be a customer's, so a net credit is held as owed; a net debit is unexplained money gone, never a negative liability hiding a shortfall"),
            new Disposition("LOAN_PRINCIPAL/LOAN", Treatment.PRINCIPAL, "lending_funded's outstanding term; not an asset here, because a loan is not cash"),
            new Disposition("LOAN_PRINCIPAL_DUE/LOAN", Treatment.PRINCIPAL, "lending_funded's outstanding term: billed principal, still capital's"),
            new Disposition("BNPL_PRINCIPAL/BNPL_OBLIGATION", Treatment.PRINCIPAL, "lending_funded's outstanding term: a BNPL purchase created a payable with no cash entering"),
            new Disposition("BNPL_PRINCIPAL_DUE/BNPL_OBLIGATION", Treatment.PRINCIPAL, "lending_funded's outstanding term: billed principal, still capital's"),
            new Disposition("CHARGEBACK_RECOVERABLE/OPERATIONAL", Treatment.MEMO, "what a network took and nobody has been charged: neither cash nor a clearing receivable"),
            new Disposition("FX_POSITION/OPERATIONAL", Treatment.MEMO, "the platform's own currency inventory: a conversion not yet covered is a true shortfall in its currency, and this figure says why"),
            new Disposition("LOAN_WRITE_OFF_EXPENSE/OPERATIONAL", Treatment.WRITE_OFF_ROUTE, "an expense, and its entries are how the principal written off is found"),
            new Disposition("BNPL_WRITE_OFF_EXPENSE/OPERATIONAL", Treatment.WRITE_OFF_ROUTE, "an expense, and its entries are how the principal written off is found"),
            new Disposition("LOAN_INTEREST_ACCRUED/LOAN", Treatment.OUT, RECEIVABLE),
            new Disposition("LOAN_INTEREST_DUE/LOAN", Treatment.OUT, RECEIVABLE),
            new Disposition("LOAN_FEES_DUE/LOAN", Treatment.OUT, RECEIVABLE),
            new Disposition("BNPL_INTEREST_ACCRUED/BNPL_OBLIGATION", Treatment.OUT, RECEIVABLE),
            new Disposition("BNPL_INTEREST_DUE/BNPL_OBLIGATION", Treatment.OUT, RECEIVABLE),
            new Disposition("BNPL_FEES_DUE/BNPL_OBLIGATION", Treatment.OUT, RECEIVABLE),
            new Disposition("LENDING_CAPITAL/OPERATIONAL", Treatment.OUT, "equity, owed to no customer: the cash it brought is what a surplus is"),
            new Disposition("DISPUTE_COSTS/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("PROCESSING_COSTS/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("RECONCILIATION_LOSSES/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("RECONCILIATION_GAINS/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("FEE_REVENUE/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("ROUNDING_RESIDUAL/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("FX_SPREAD_REVENUE/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("FX_REALISED_GAINS/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("FX_REALISED_LOSSES/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("LOAN_INTEREST_INCOME/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("LOAN_FEE_INCOME/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("BNPL_MERCHANT_FEE_INCOME/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("BNPL_INTEREST_INCOME/OPERATIONAL", Treatment.OUT, INCOME),
            new Disposition("BNPL_FEE_INCOME/OPERATIONAL", Treatment.OUT, INCOME));

    private static final Map<String, Treatment> TREATMENTS = DISPOSITIONS.stream().collect(Collectors.toMap(Disposition::key, Disposition::treatment));

    /** What the journal's reads are folded over: every purpose but those out of the return. */
    private static final Set<String> READ_KEYS = DISPOSITIONS.stream().filter(held -> held.treatment() != Treatment.OUT).map(Disposition::key).collect(Collectors.toCollection(TreeSet::new));

    private static final ReportStatement STATEMENT = new ReportStatement(
            "The ledger's journal lines by posting date, each read with its ledger account's purpose and owner kind, a counterparty's account also with the counterparty's code; lending's frozen facts of an instalment loan's opening, agreement principal and disbursement outcome; the pinned GL mapping version for the drill-down key."
                    + " Never a loan's status, lending's live capital proof, a projection, a cache or a gauge.",
            "Per currency: assets are cash at bank and clearing receivables; liabilities are customer wallets, merchant payables, credit balances, clearing payables and suspense at its net credit; lendingFunded is principal outstanding plus committed plus written off; position is assets less liabilities plus lendingFunded, and positionBeforeLendingFunded assets less liabilities."
                    + " Each purpose's place is in mapping, under this version, never a GL mapping's. A reference figure: no legal obligation is asserted, and nothing here states that one is met.",
            "A journal line counts by its posting date through the period's last day. A loan counts as committed by its own servicing zone's business date: opened on or before the last day, and neither its disbursement's value date, which is its journal line's posting date, nor its cancellation's date on or before it, so it crosses to outstanding on one date. A FINAL run is as of its"
                    + " period's lock. A PROVISIONAL one is as of its cut, entries created and loan facts recorded at or before its claim.",
            "Per currency, one scale: the largest its own amounts carry, an exact rescale, no JDK currency table. Never summed across currencies, and no counterparty's credit balance set against another's debit balance. Minor units in strings beside currency and scale; a row debit-positive, a currency's figure positive on its own side, a position negative when short and never floored. Not translated: no run takes a valuation rate set.",
            "FINAL for a period no longer OPEN: byte-identical on regeneration while its lock stands, superseded by an unlock or a reopen. PROVISIONAL for an OPEN period: labelled, reused within its freshness window, never issued.");

    private static final String LINES_ROUTE = "GET /v1/operator/accounting/report-runs/{runId}/lines?account={glAccount}&currency={currency}&month={month} (ACCOUNTING_READ, audited), under this run's version, walking back a month at a time until the row's openingLines are counted";
    private static final String ENTRY_ROUTE = "GET /v1/operator/accounting/gl/entries/{entryId} (ACCOUNTING_READ, audited)";
    private static final String FOOTS = "The sum of the figures it names; each of those is drilled by its own member here.";

    @NonNull private final TrialBalanceReads reads;
    @NonNull private final CounterpartyPositionReads positions;
    @NonNull private final LendingFundedAsOfCutoff lending;

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public ReportStatement statement() {
        return STATEMENT;
    }

    @Override
    public CanonicalJson.Obj generate(Connection snapshot, ReportInputs inputs) {
        JournalWindow read = JournalWindow.of(reads, snapshot, inputs);
        LocalDate cutoff = read.range().to();
        List<JournalReads.Aggregate> opening = PurposeRows.perCounterparty(reads.before(snapshot, inputs.start(), read.cut()), positions.before(snapshot, inputs.start(), read.cut()));
        List<JournalReads.Aggregate> movement = PurposeRows.perCounterparty(reads.within(snapshot, read.range(), read.cut()), positions.within(snapshot, read.range(), read.cut()));
        List<PurposeRows.Row> rows = PurposeRows.fold(opening, movement, READ_KEYS);
        // A failure of either read throws here, and no body is written: never a zero for what could not be read.
        List<LendingFundedAsOfCutoff.Committed> committed = lending.committed(snapshot, cutoff, read.cut());
        List<LendingFundedAsOfCutoff.WrittenOff> writtenOff = lending.writtenOff(snapshot, cutoff, read.cut());

        Map<String, Integer> scales = new TreeMap<>(PurposeRows.scales(rows));
        committed.forEach(held -> scales.merge(held.currency(), held.scale(), Math::max));
        writtenOff.forEach(held -> scales.merge(held.currency(), held.scale(), Math::max));

        Map<String, Figures> figures = new TreeMap<>();
        scales.forEach((currency, scale) -> figures.put(currency, new Figures(currency, scale)));
        rows.forEach(row -> figures.get(row.currency()).add(row));
        committed.forEach(held -> figures.get(held.currency()).committed(held));
        writtenOff.forEach(held -> figures.get(held.currency()).writtenOff(held));

        CanonicalJson.Arr currencies = CanonicalJson.array();
        figures.values().forEach(figure -> currencies.add(figure.asValue()));
        CanonicalJson.Arr written = CanonicalJson.array();
        rows.forEach(row -> written.add(rowOf(row, scales.get(row.currency()), read)));
        CanonicalJson.Arr mapping = CanonicalJson.array();
        DISPOSITIONS.forEach(held -> mapping.add(CanonicalJson.object().put("key", held.key()).put("treatment", held.treatment().name()).put("reason", held.reason())));
        return read.ranges(CanonicalJson.object()
                .put("statement", STATEMENT.asValue())
                .put("standing", "REFERENCE")
                .put("rowsSign", TrialBalance.SIGN)
                .put("currencies", currencies)
                .put("rows", written)
                .put("mapping", mapping)
                .put("drillDown", drillDown()));
    }

    private static CanonicalJson.Obj rowOf(PurposeRows.Row row, int scale, JournalWindow read) {
        CanonicalJson.Obj out = PurposeRows.withCounterparty(CanonicalJson.object()
                .put("purpose", row.purpose())
                .put("ownerKind", row.ownerKind())
                .put("currency", row.currency())
                .put("treatment", TREATMENTS.get(row.key()).name())
                .put("opening", TrialBalance.money(row.openingBalance(), row.currency(), scale))
                .put("debits", TrialBalance.money(row.movement().debits(), row.currency(), scale))
                .put("credits", TrialBalance.money(row.movement().credits(), row.currency(), scale))
                .put("closing", TrialBalance.money(row.closingBalance(), row.currency(), scale))
                .put("lines", row.movement().lines())
                .put("openingLines", row.opening().lines()), row.counterparty());
        read.glAccountOf(row.key()).ifPresentOrElse(account -> out.put("glAccount", account), () -> out.putNull("glAccount"));
        return out;
    }

    /** Each figure a line can draw has a member of its own name, saying what stands behind it: a route, or plainly none. */
    private static CanonicalJson.Obj drillDown() {
        return CanonicalJson.object()
                .put("cashAtBank", rowsOf(Treatment.CASH))
                .put("clearingReceivables", rowsOf(Treatment.CLEARING_RECEIVABLE) + " And the rows of treatment " + Treatment.COUNTERPARTY_CLEARING + " standing in debit, each counterparty on its own.")
                .put("assets", FOOTS)
                .put("customerWallets", rowsOf(Treatment.CUSTOMER_WALLET))
                .put("merchantPayables", rowsOf(Treatment.MERCHANT_PAYABLE))
                .put("creditBalances", rowsOf(Treatment.CREDIT_BALANCE))
                .put("clearingPayables", rowsOf(Treatment.CLEARING_PAYABLE) + " And the rows of treatment " + Treatment.COUNTERPARTY_CLEARING + " standing in credit, each counterparty on its own.")
                .put("suspenseHeld", rowsOf(Treatment.SUSPENSE) + " Held at its net credit: SUSPENSE's own report shows the register's items gross, per side.")
                .put("liabilities", FOOTS)
                .put("principalOutstanding", rowsOf(Treatment.PRINCIPAL))
                .put("principalCommitted", "No journal line stands behind this figure, because an accepted loan awaiting its disbursement has posted nothing. It rests on lending's frozen rows: each instalment loan's opening, its agreement version 1's principal, and its disbursement's value date and outcome. No route under ACCOUNTING_READ lists those loans, and principalCommittedLoans is how many there are."
                        + " Lending's GET /v1/operator/reports/lending/capital?date={lastDay} states this term for the same date under LENDING_INVESTIGATE, which ACCOUNTING_READ does not carry.")
                .put("principalWrittenOff", "The rows of treatment " + Treatment.WRITE_OFF_ROUTE + " name the expense accounts: their lines by " + LINES_ROUTE + "; each line's entry by " + ENTRY_ROUTE + ". This figure is the credits less the debits, on the outstanding term's purposes, of those entries. The expense itself is larger: it also holds interest and fees, which capital never funded.")
                .put("lendingFunded", FOOTS)
                .put("customerFundedLiabilities", "liabilities less lendingFunded; each is drilled by its own member here.")
                .put("position", "assets less customerFundedLiabilities; each is drilled by its own member here. Negative is a shortfall, reported and never floored.")
                .put("positionBeforeLendingFunded", "assets less liabilities; each is drilled by its own member here. Nothing is subtracted, so a reader can see what the adjustment did.")
                .put("chargebackRecoverable", rowsOf(Treatment.MEMO) + " Out of the position.")
                .put("fxPosition", rowsOf(Treatment.MEMO) + " Out of the position.")
                .put("suspenseNetDebit", rowsOf(Treatment.SUSPENSE) + " Out of the position: unexplained money gone, never a negative liability.")
                .put("glAccountNull", "A row whose glAccount is null: the run's version has no rule for its purpose, or the run has no version. The routes above are keyed by an account, so such a row has none from here; a month with one does not close (GL_EQUALS_LEDGER).");
    }

    private static String rowsOf(Treatment treatment) {
        return "This currency's rows of treatment " + treatment + ": each row's lines by " + LINES_ROUTE + ".";
    }

    /** One currency's figures being folded, exact decimals on minor units, never floating point. */
    private static final class Figures {
        private final String currency;
        private final int scale;
        private BigDecimal cash = BigDecimal.ZERO;
        private BigDecimal clearingReceivables = BigDecimal.ZERO;
        private BigDecimal wallets = BigDecimal.ZERO;
        private BigDecimal merchants = BigDecimal.ZERO;
        private BigDecimal creditBalances = BigDecimal.ZERO;
        private BigDecimal clearingPayables = BigDecimal.ZERO;
        private BigDecimal suspense = BigDecimal.ZERO;
        private BigDecimal outstanding = BigDecimal.ZERO;
        private BigDecimal committed = BigDecimal.ZERO;
        private long committedLoans;
        private BigDecimal writtenOff = BigDecimal.ZERO;
        private BigDecimal chargebacks = BigDecimal.ZERO;
        private BigDecimal fxPosition = BigDecimal.ZERO;

        Figures(String currency, int scale) {
            this.currency = currency;
            this.scale = scale;
        }

        void add(PurposeRows.Row row) {
            BigDecimal closing = row.closingBalance();
            switch (TREATMENTS.get(row.key())) {
                case CASH -> cash = cash.add(closing);
                case CLEARING_RECEIVABLE -> clearingReceivables = clearingReceivables.add(closing);
                case COUNTERPARTY_CLEARING -> {
                    if (closing.signum() >= 0) {
                        clearingReceivables = clearingReceivables.add(closing);
                    } else {
                        clearingPayables = clearingPayables.add(closing.negate());
                    }
                }
                case CUSTOMER_WALLET -> wallets = wallets.add(closing.negate());
                case MERCHANT_PAYABLE -> merchants = merchants.add(closing.negate());
                case CREDIT_BALANCE -> creditBalances = creditBalances.add(closing.negate());
                case CLEARING_PAYABLE -> clearingPayables = clearingPayables.add(closing.negate());
                case SUSPENSE -> suspense = suspense.add(closing.negate());
                case PRINCIPAL -> outstanding = outstanding.add(closing);
                case MEMO -> {
                    if ("CHARGEBACK_RECOVERABLE".equals(row.purpose())) {
                        chargebacks = chargebacks.add(closing);
                    } else {
                        fxPosition = fxPosition.add(closing);
                    }
                }
                case WRITE_OFF_ROUTE, OUT -> { }
            }
        }

        void committed(LendingFundedAsOfCutoff.Committed held) {
            committed = committed.add(held.amount());
            committedLoans += held.loans();
        }

        void writtenOff(LendingFundedAsOfCutoff.WrittenOff held) {
            writtenOff = writtenOff.add(held.amount());
        }

        CanonicalJson.Obj asValue() {
            BigDecimal suspenseHeld = suspense.max(BigDecimal.ZERO);
            BigDecimal assets = cash.add(clearingReceivables);
            BigDecimal liabilities = wallets.add(merchants).add(creditBalances).add(clearingPayables).add(suspenseHeld);
            BigDecimal lendingFunded = LendingFundedAsOfCutoff.lendingFunded(outstanding, committed, writtenOff);
            BigDecimal customerFunded = liabilities.subtract(lendingFunded);
            BigDecimal position = assets.subtract(customerFunded);
            return CanonicalJson.object()
                    .put("currency", currency)
                    .put("scale", scale)
                    .put("cashAtBank", amount(cash))
                    .put("clearingReceivables", amount(clearingReceivables))
                    .put("assets", amount(assets))
                    .put("customerWallets", amount(wallets))
                    .put("merchantPayables", amount(merchants))
                    .put("creditBalances", amount(creditBalances))
                    .put("clearingPayables", amount(clearingPayables))
                    .put("suspenseHeld", amount(suspenseHeld))
                    .put("liabilities", amount(liabilities))
                    .put("principalOutstanding", amount(outstanding))
                    .put("principalCommitted", amount(committed))
                    .put("principalCommittedLoans", committedLoans)
                    .put("principalWrittenOff", amount(writtenOff))
                    .put("lendingFunded", amount(lendingFunded))
                    .put("customerFundedLiabilities", amount(customerFunded))
                    .put("position", amount(position))
                    .put("positionBeforeLendingFunded", amount(assets.subtract(liabilities)))
                    .put("shortfall", position.signum() < 0)
                    .put("chargebackRecoverable", amount(chargebacks))
                    .put("fxPosition", amount(fxPosition))
                    .put("suspenseNetDebit", amount(suspense.negate().max(BigDecimal.ZERO)));
        }

        private CanonicalJson.Obj amount(BigDecimal figure) {
            return TrialBalance.money(figure, currency, scale);
        }
    }
}
