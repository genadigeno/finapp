package com.finapp.app.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The accounting battery's world (`P14-TST-002`; {@code PHASE_14_PLAN.md} section 14.3): a seeded journal of at least
 * 10,000 entries over five currencies at three scales and the twelve months of 2001, the chart the oracle reads it under,
 * the month's reference rates and the battery's nine kinds of entry with their registered shapes.
 *
 * <p><strong>JDK types only</strong>, and so is {@link AccountingBatteryOracle}: neither names a class of the code under test,
 * which {@code AccountingBatteryHermeticTest#theOracleSharesNoCode} holds by reading both class files' constant pools. The
 * chart is written out here, not read from {@code ReferenceGlMapping}, and the census holds the two equal - a second statement
 * of the mapping, checked rather than trusted.
 *
 * <p><strong>Nothing passes by construction.</strong> A value date is drawn apart from its posting date, a fifth of them in the
 * month before, so a fold reading the wrong date moves a figure; a quarter of the posting dates are a month's first or last day;
 * amounts run from one minor unit to ten million, so a translation meets ties and parts below a cent; entries hold two, three or
 * four lines. The same {@code (seed, entries)} is the same journal in every JVM: {@link Random}'s sequence is specified, nothing
 * here reads a clock, a locale, a hash order or a default charset.
 */
final class AccountingBatteryWorld {

    /** The battery's seed. A failing seed is reproduced with {@code FINAPP_ACCOUNTING_BATTERY_SEED}. */
    static final long SEED = 20_261_106L;

    /** The seed in use - {@link #SEED} unless a diagnosis names another. A reseeded run fails the pinned fingerprint by design. */
    static final long SEED_IN_USE = longEnv("FINAPP_ACCOUNTING_BATTERY_SEED", SEED);

    /** The size: {@code FINAPP_ACCOUNTING_BATTERY_ENTRIES} for a probe's quick run only - a shrunk run fails the census. */
    static final int ENTRIES = (int) longEnv("FINAPP_ACCOUNTING_BATTERY_ENTRIES", 10_000);

    /** The twelve periods. */
    static final List<YearMonth> MONTHS = months();

    /** The month the restated mapping takes effect from - the second version, four eyes in the database half. */
    static final YearMonth RESTATED_FROM = YearMonth.of(2001, 7);

    /** The month the rate and the planted shape perturb. */
    static final YearMonth PERTURBED = YearMonth.of(2001, 6);

    /** Every entry whose index is a multiple of this is posted a second time under its key - the replay at volume. */
    static final int REPLAYED = 97;

    /** A currency and the one scale its lines carry - its minor units, the ledger's pinned ones. */
    record Unit(String currency, int scale) {}

    static final List<Unit> UNITS = List.of(new Unit("EUR", 2), new Unit("USD", 2), new Unit("GBP", 2), new Unit("JPY", 0),
            new Unit("BHD", 3));

    // ------------------------------------------------------------------ the purposes

    static final String CASH = "CASH_AT_BANK";
    static final String SETTLEMENT = "SETTLEMENT_CLEARING";
    static final String INSTANT = "INSTANT_CLEARING";
    static final String CHARGEBACK = "CHARGEBACK_RECOVERABLE";
    static final String FX_POSITION = "FX_POSITION";
    static final String WALLET = "CUSTOMER_WALLET";
    static final String PAYOUT = "PAYOUT_CLEARING";
    static final String SUSPENSE = "SUSPENSE_UNMATCHED";
    static final String CAPITAL = "LENDING_CAPITAL";
    static final String FEES = "FEE_REVENUE";
    static final String INTEREST = "LOAN_INTEREST_INCOME";
    static final String FX_GAINS = "FX_REALISED_GAINS";
    static final String RECON_GAINS = "RECONCILIATION_GAINS";
    static final String PROCESSING = "PROCESSING_COSTS";
    static final String DISPUTES = "DISPUTE_COSTS";
    static final String FX_LOSSES = "FX_REALISED_LOSSES";
    static final String RESIDUAL = "ROUNDING_RESIDUAL";
    static final String RECON_LOSSES = "RECONCILIATION_LOSSES";

    /** How many customers' wallets each currency has. */
    static final int WALLETS = 4;

    // ------------------------------------------------------------------ the chart

    /** One purpose's rule under a version: its account's code, classification and statement section. */
    record ChartRow(String purpose, String ownerKind, String code, String name, String classification, String section) {

        String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /** Which chart: the battery's own, or the perturbation's (a purpose moved between two sections). */
    enum Chart {
        BASE,
        MOVED
    }

    /** The restated fee account from {@link #RESTATED_FROM}: same section, another code - the version change in the year. */
    static final String RESTATED_FEES = "4090";

    /** The perturbation: chargebacks recoverable moved from other assets to the clearing receivables, under a new code. */
    static final String MOVED_PURPOSE = CHARGEBACK;
    static final String MOVED_FROM = "1130";
    static final String MOVED_TO = "1190";
    static final String MOVED_FROM_SECTION = "OTHER_ASSETS";
    static final String MOVED_TO_SECTION = "CLEARING_AND_SETTLEMENT_RECEIVABLES";

    /** The reference version's rows for the battery's eighteen purposes, as {@code ReferenceGlMapping} states them. */
    static final List<ChartRow> CHART = List.of(
            new ChartRow(CASH, "OPERATIONAL", "1000", "Cash at bank", "ASSET", "CASH_AND_BANK"),
            new ChartRow(SETTLEMENT, "OPERATIONAL", "1100", "Card settlement clearing", "ASSET", "CLEARING_AND_SETTLEMENT_RECEIVABLES"),
            new ChartRow(INSTANT, "OPERATIONAL", "1110", "Instant payment clearing", "ASSET", "CLEARING_AND_SETTLEMENT_RECEIVABLES"),
            new ChartRow(CHARGEBACK, "OPERATIONAL", "1130", "Chargebacks recoverable", "ASSET", "OTHER_ASSETS"),
            new ChartRow(FX_POSITION, "OPERATIONAL", "1140", "FX position", "ASSET", "OTHER_ASSETS"),
            new ChartRow(WALLET, "CUSTOMER", "2000", "Customer funds", "LIABILITY", "CUSTOMER_FUNDS"),
            new ChartRow(PAYOUT, "OPERATIONAL", "2200", "Payout clearing", "LIABILITY", "CLEARING_AND_SETTLEMENT_PAYABLES"),
            new ChartRow(SUSPENSE, "SUSPENSE", "2900", "Suspense - unmatched", "LIABILITY", "SUSPENSE"),
            new ChartRow(CAPITAL, "OPERATIONAL", "3000", "Lending capital", "EQUITY", "CAPITAL"),
            new ChartRow(FEES, "OPERATIONAL", "4000", "Fee income", "REVENUE", "FEE_AND_COMMISSION_INCOME"),
            new ChartRow(INTEREST, "OPERATIONAL", "4100", "Loan interest income", "REVENUE", "INTEREST_INCOME"),
            new ChartRow(FX_GAINS, "OPERATIONAL", "4210", "FX realised gains", "REVENUE", "FX_RESULT"),
            new ChartRow(RECON_GAINS, "OPERATIONAL", "4300", "Reconciliation gains", "REVENUE", "OTHER_INCOME"),
            new ChartRow(PROCESSING, "OPERATIONAL", "5000", "Processing costs", "EXPENSE", "PROCESSING_AND_DISPUTE_COSTS"),
            new ChartRow(DISPUTES, "OPERATIONAL", "5010", "Dispute costs", "EXPENSE", "PROCESSING_AND_DISPUTE_COSTS"),
            new ChartRow(FX_LOSSES, "OPERATIONAL", "5200", "FX realised losses", "EXPENSE", "FX_RESULT"),
            new ChartRow(RESIDUAL, "OPERATIONAL", "5210", "Rounding residual", "EXPENSE", "FX_RESULT"),
            new ChartRow(RECON_LOSSES, "OPERATIONAL", "5300", "Reconciliation losses", "EXPENSE", "OTHER_EXPENSES"));

    /** The rows {@code month} reads under {@code chart}: the version change from July, and the perturbation's move. */
    static List<ChartRow> chart(Chart chart, YearMonth month) {
        List<ChartRow> rows = new ArrayList<>();
        for (ChartRow row : CHART) {
            ChartRow read = row;
            if (row.purpose().equals(FEES) && !month.isBefore(RESTATED_FROM)) {
                read = new ChartRow(row.purpose(), row.ownerKind(), RESTATED_FEES, "Fee income - restated", row.classification(),
                        row.section());
            }
            if (chart == Chart.MOVED && row.purpose().equals(MOVED_PURPOSE)) {
                read = new ChartRow(row.purpose(), row.ownerKind(), MOVED_TO, "Chargebacks recoverable - moved", row.classification(),
                        MOVED_TO_SECTION);
            }
            rows.add(read);
        }
        return List.copyOf(rows);
    }

    /** The version number {@code month} pins: the first, and the restated one from July. */
    static int versionOf(YearMonth month) {
        return month.isBefore(RESTATED_FROM) ? 1 : 2;
    }

    // ------------------------------------------------------------------ the kinds

    /** One line of a registered shape: the side it takes, the purposes it admits, whether an entry must have it. */
    record ShapeLine(String side, List<String> purposes, boolean required) {}

    /** One kind of entry: its key namespace and its registered shape. */
    record Kind(String id, String namespace, List<ShapeLine> shape) {}

    static final Kind FEE = kind("BATTERY-FEE", "battery.fee:", required("DEBIT", WALLET), required("CREDIT", FEES));
    static final Kind SETTLE = kind("BATTERY-SETTLEMENT", "battery.settlement:", required("DEBIT", SETTLEMENT),
            optional("DEBIT", PROCESSING), required("CREDIT", WALLET));
    static final Kind PAY_OUT = kind("BATTERY-PAYOUT", "battery.payout:", required("DEBIT", WALLET), required("CREDIT", PAYOUT));
    static final Kind BANK = kind("BATTERY-BANK", "battery.bank:", required("DEBIT", CASH, PAYOUT),
            required("CREDIT", CASH, SETTLEMENT, INSTANT));
    static final Kind FUNDING = kind("BATTERY-CAPITAL", "battery.capital:", required("DEBIT", CASH), required("CREDIT", CAPITAL));
    static final Kind FX = kind("BATTERY-FX", "battery.fx:", required("DEBIT", FX_POSITION, FX_LOSSES),
            optional("DEBIT", RESIDUAL), required("CREDIT", FX_POSITION, FX_GAINS));
    static final Kind DISPUTE = kind("BATTERY-DISPUTE", "battery.dispute:", required("DEBIT", CHARGEBACK, CASH),
            optional("DEBIT", DISPUTES), required("CREDIT", SETTLEMENT, CHARGEBACK));
    static final Kind RECON = kind("BATTERY-RECONCILIATION", "battery.recon:", required("DEBIT", RECON_LOSSES, SUSPENSE),
            required("CREDIT", SUSPENSE, RECON_GAINS));
    static final Kind LOAN_INTEREST = kind("BATTERY-INTEREST", "battery.interest:", required("DEBIT", CASH),
            required("CREDIT", INTEREST));

    static final List<Kind> KINDS = List.of(FEE, SETTLE, PAY_OUT, BANK, FUNDING, FX, DISPUTE, RECON, LOAN_INTEREST);

    private static Kind kind(String id, String namespace, ShapeLine... shape) {
        return new Kind(id, namespace, List.of(shape));
    }

    private static ShapeLine required(String side, String... purposes) {
        return new ShapeLine(side, List.of(purposes), true);
    }

    private static ShapeLine optional(String side, String... purposes) {
        return new ShapeLine(side, List.of(purposes), false);
    }

    // ------------------------------------------------------------------ the journal

    /** One line: its account's purpose, owner kind and owner (a wallet's customer, else 0), its side and amount. */
    record Line(String purpose, String ownerKind, int owner, boolean debit, long minor) {

        String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /** One entry: its index, key, namespace, two dates, currency and scale, and its lines in order. */
    record Entry(int index, String key, String namespace, LocalDate postingDate, LocalDate valueDate, String currency, int scale,
            List<Line> lines) {

        Entry {
            lines = List.copyOf(lines);
        }

        /** The same entry under another namespace - the planted shape's relabel, its lines and dates untouched. */
        Entry under(String other) {
            return new Entry(index, other + key.substring(key.indexOf(':') + 1), other, postingDate, valueDate, currency, scale, lines);
        }
    }

    /** The journal of {@code (seed, entries)}. */
    static List<Entry> generate(long seed, int entries) {
        Random random = new Random(seed);
        List<Entry> journal = new ArrayList<>(entries);
        LocalDate first = MONTHS.get(0).atDay(1);
        int days = (int) (MONTHS.get(MONTHS.size() - 1).atEndOfMonth().toEpochDay() - first.toEpochDay()) + 1;
        for (int index = 0; index < entries; index++) {
            LocalDate posting;
            if (random.nextInt(4) == 0) {
                YearMonth month = MONTHS.get(random.nextInt(MONTHS.size()));
                posting = random.nextBoolean() ? month.atDay(1) : month.atEndOfMonth();
            } else {
                posting = first.plusDays(random.nextInt(days));
            }
            LocalDate value = random.nextInt(5) == 0 ? posting.minusDays(1 + random.nextInt(31))
                    : random.nextBoolean() ? posting : posting.minusDays(random.nextInt(3));
            Unit unit = UNITS.get(random.nextInt(UNITS.size()));
            Kind kind = KINDS.get(random.nextInt(KINDS.size()));
            String key = kind.namespace() + "s" + seed + "-e" + index;
            journal.add(new Entry(index, key, kind.namespace(), posting, value, unit.currency(), unit.scale(),
                    lines(kind, random)));
        }
        return List.copyOf(journal);
    }

    /** An amount from one minor unit to ten million, spread over seven decades. */
    private static long amount(Random random) {
        long decade = 1;
        for (int i = random.nextInt(7); i > 0; i--) {
            decade *= 10;
        }
        return 1 + random.nextLong(decade * 10);
    }

    private static List<Line> lines(Kind kind, Random random) {
        long a = amount(random);
        long b = amount(random);
        int customer = random.nextInt(WALLETS);
        List<Line> lines = new ArrayList<>();
        if (kind == FEE) {
            lines.add(debit(WALLET, "CUSTOMER", customer, a));
            lines.add(credit(FEES, "OPERATIONAL", 0, a));
        } else if (kind == SETTLE) {
            if (random.nextBoolean()) {
                lines.add(debit(SETTLEMENT, "OPERATIONAL", 0, a));
                lines.add(debit(PROCESSING, "OPERATIONAL", 0, b));
                lines.add(credit(WALLET, "CUSTOMER", customer, a + b));
            } else {
                lines.add(debit(SETTLEMENT, "OPERATIONAL", 0, a));
                lines.add(credit(WALLET, "CUSTOMER", customer, a));
            }
        } else if (kind == PAY_OUT) {
            lines.add(debit(WALLET, "CUSTOMER", customer, a));
            lines.add(credit(PAYOUT, "OPERATIONAL", 0, a));
        } else if (kind == BANK) {
            switch (random.nextInt(3)) {
                case 0 -> {
                    lines.add(debit(CASH, "OPERATIONAL", 0, a));
                    lines.add(credit(SETTLEMENT, "OPERATIONAL", 0, a));
                }
                case 1 -> {
                    lines.add(debit(PAYOUT, "OPERATIONAL", 0, a));
                    lines.add(credit(CASH, "OPERATIONAL", 0, a));
                }
                default -> {
                    lines.add(debit(CASH, "OPERATIONAL", 0, a));
                    lines.add(debit(CASH, "OPERATIONAL", 0, b));
                    lines.add(credit(SETTLEMENT, "OPERATIONAL", 0, a));
                    lines.add(credit(INSTANT, "OPERATIONAL", 0, b));
                }
            }
        } else if (kind == FUNDING) {
            lines.add(debit(CASH, "OPERATIONAL", 0, a));
            lines.add(credit(CAPITAL, "OPERATIONAL", 0, a));
        } else if (kind == FX) {
            if (random.nextBoolean()) {
                lines.add(debit(FX_POSITION, "OPERATIONAL", 0, a));
                lines.add(credit(FX_GAINS, "OPERATIONAL", 0, a));
            } else {
                lines.add(debit(FX_LOSSES, "OPERATIONAL", 0, a));
                lines.add(debit(RESIDUAL, "OPERATIONAL", 0, 1 + random.nextInt(9)));
                lines.add(credit(FX_POSITION, "OPERATIONAL", 0, a + lines.get(1).minor()));
            }
        } else if (kind == DISPUTE) {
            if (random.nextBoolean()) {
                lines.add(debit(CHARGEBACK, "OPERATIONAL", 0, a));
                lines.add(debit(DISPUTES, "OPERATIONAL", 0, b));
                lines.add(credit(SETTLEMENT, "OPERATIONAL", 0, a + b));
            } else {
                lines.add(debit(CASH, "OPERATIONAL", 0, a));
                lines.add(credit(CHARGEBACK, "OPERATIONAL", 0, a));
            }
        } else if (kind == RECON) {
            if (random.nextBoolean()) {
                lines.add(debit(RECON_LOSSES, "OPERATIONAL", 0, a));
                lines.add(credit(SUSPENSE, "SUSPENSE", 0, a));
            } else {
                lines.add(debit(SUSPENSE, "SUSPENSE", 0, a));
                lines.add(credit(RECON_GAINS, "OPERATIONAL", 0, a));
            }
        } else {
            lines.add(debit(CASH, "OPERATIONAL", 0, a));
            lines.add(credit(INTEREST, "OPERATIONAL", 0, a));
        }
        return lines;
    }

    private static Line debit(String purpose, String ownerKind, int owner, long minor) {
        return new Line(purpose, ownerKind, owner, true, minor);
    }

    private static Line credit(String purpose, String ownerKind, int owner, long minor) {
        return new Line(purpose, ownerKind, owner, false, minor);
    }

    // ------------------------------------------------------------------ the rates

    /** In odd months {@code EUR/GBP} is exactly this: a pound translates to 1.25 euros, so a quarter of the pound figures fall on a tie. */
    static final BigDecimal TIED_GBP = new BigDecimal("0.8000");

    /**
     * The reference's {@code EUR/<currency>} rates as of {@code month}'s end - a function of the seed and the month alone, so a
     * shrunk journal reads the same rates. Four to six places: {@code NUMERIC(20,10)} holds them exactly. In odd months the
     * pound is {@link #TIED_GBP}, so the rounding of a tie - half-even, never half-up - is met every such month, not by luck.
     */
    static SortedMap<String, BigDecimal> rates(long seed, YearMonth month) {
        Random random = new Random(seed * 31 + month.getYear() * 12L + month.getMonthValue());
        SortedMap<String, BigDecimal> rates = new TreeMap<>();
        rates.put("USD", new BigDecimal("1.0800").add(BigDecimal.valueOf(random.nextInt(1001) - 500, 4)));
        BigDecimal pound = new BigDecimal("0.8600").add(BigDecimal.valueOf(random.nextInt(601) - 300, 4));
        rates.put("GBP", month.getMonthValue() % 2 == 1 ? TIED_GBP : pound);
        rates.put("JPY", new BigDecimal("157.00").add(BigDecimal.valueOf(random.nextInt(2001) - 1000, 2)));
        rates.put("BHD", new BigDecimal("0.410000").add(BigDecimal.valueOf(random.nextInt(10001) - 5000, 6)));
        return rates;
    }

    /** {@code month}'s rates with June's {@code EUR/USD} moved - the rate perturbation. */
    static SortedMap<String, BigDecimal> movedRates(long seed, YearMonth month) {
        SortedMap<String, BigDecimal> rates = new TreeMap<>(rates(seed, month));
        if (month.equals(PERTURBED)) {
            rates.put("USD", rates.get("USD").add(new BigDecimal("0.0731")));
        }
        return rates;
    }

    // ------------------------------------------------------------------ the perturbations' journals

    /** The planted shape's entry: the first funding entry dated in {@link #PERTURBED} - its lines are no fee's. */
    static int plantedIndex(List<Entry> entries) {
        return entries.stream()
                .filter(entry -> entry.namespace().equals(FUNDING.namespace()) && YearMonth.from(entry.postingDate()).equals(PERTURBED))
                .mapToInt(Entry::index).findFirst().orElseThrow();
    }

    /** {@code entries} with the planted entry relabelled a fee - a registered namespace whose shape does not admit it. */
    static List<Entry> planted(List<Entry> entries) {
        int planted = plantedIndex(entries);
        return entries.stream().map(entry -> entry.index() == planted ? entry.under(FEE.namespace()) : entry).toList();
    }

    // ------------------------------------------------------------------ the fingerprint

    /** One entry as text: index, key, dates, currency and scale, then every line - locale-free, explicit. */
    static String canonical(Entry entry) {
        StringBuilder text = new StringBuilder().append(entry.index()).append('|').append(entry.key()).append('|')
                .append(entry.postingDate()).append('|').append(entry.valueDate()).append('|').append(entry.currency()).append('|')
                .append(entry.scale());
        for (Line line : entry.lines()) {
            text.append('|').append(line.key()).append('#').append(line.owner()).append(line.debit() ? ":D:" : ":C:")
                    .append(line.minor());
        }
        return text.toString();
    }

    /** The journal's SHA-256 over every entry's {@link #canonical} text, newline-terminated, UTF-8. */
    static String fingerprint(List<Entry> entries) {
        MessageDigest digest = sha256();
        for (Entry entry : entries) {
            digest.update((canonical(entry) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static List<YearMonth> months() {
        List<YearMonth> months = new ArrayList<>();
        for (int month = 1; month <= 12; month++) {
            months.add(YearMonth.of(2001, month));
        }
        return List.copyOf(months);
    }

    private static long longEnv(String name, long fallback) {
        return Optional.ofNullable(System.getenv(name)).filter(value -> !value.isBlank()).map(Long::parseLong).orElse(fallback);
    }

    /** The kind a namespace names, if any. */
    static Optional<Kind> kindOf(String namespace) {
        return KINDS.stream().filter(kind -> kind.namespace().equals(namespace)).findFirst();
    }

    /** The unit of {@code currency}. */
    static Unit unit(String currency) {
        return UNITS.stream().filter(unit -> unit.currency().equals(Objects.requireNonNull(currency))).findFirst().orElseThrow();
    }
}
