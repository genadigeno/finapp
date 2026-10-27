package com.finapp.app.risk;

import static com.finapp.app.risk.RiskStormWorld.RANDOMNESS;
import static com.finapp.app.risk.RiskStormWorld.judged;
import static com.finapp.app.risk.RiskStormWorld.key;

import com.finapp.app.fx.RiskStormCrossBorder;
import com.finapp.app.risk.RiskStormTest.Instance;
import com.finapp.app.risk.RiskStormWorld.Answer;
import com.finapp.app.risk.RiskStormWorld.Audience;
import com.finapp.app.risk.RiskStormWorld.Customer;
import com.finapp.app.risk.RiskStormWorld.Merchant;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The latency budget, measured (`P13-TST-001`; {@code PHASE_13_PLAN.md} sections 7.6 and 13.3; M3's split trigger): T-eval
 * - the pin, the cut, the reads, the engine and the count - runs in-lock in every money movement, its budget p99 at most
 * 50 ms. Two phases of the same steady load on both instances - every judged operation, each thread its own payer so no
 * subject lock is contended but a merchant's (owner item 9's, measured) - one with the monitoring sweep running beside it
 * on both instances, one with it stopped on both; each assessment's sealed {@code latency_micros} (measured by T-eval on
 * its instance's clock) read per operation: p50, p99, max. <strong>M3's reading</strong>: a p99 over 50 ms with the
 * sweep on and under it with the sweep off is the finding (the sweep harms the synchronous budget - split it out).
 */
final class RiskStormLatencyReport {

    static final Duration PHASE = Duration.ofSeconds(90);
    static final long BUDGET_MICROS = 50_000;
    private static final List<String> OPERATIONS = List.of("TRANSFER", "WITHDRAWAL", "CARD_PAYMENT", "WALLET_PAYMENT",
            "CROSS_BORDER");

    private final RiskStormTest storm;
    /** phase -> operation -> command references the phase judged. */
    private final Map<String, Map<String, Set<String>>> refs = new LinkedHashMap<>();
    /** phase -> operation -> {count, p50, p99, max} in microseconds. */
    final Map<String, Map<String, long[]>> figures = new LinkedHashMap<>();
    final List<String> findings = new ArrayList<>();

    RiskStormLatencyReport(RiskStormTest storm) {
        this.storm = storm;
    }

    void measure() throws Exception {
        // Each phase its own ten fresh payers (the same velocity history from zero - no phase judged against a day the
        // previous one filled), built in parallel, each transferring to the next (no DESTINATION lock shared); a
        // merchant per payer for the third phase.
        List<List<Customer>> payersOf = new ArrayList<>();
        ExecutorService builders = Executors.newFixedThreadPool(6);
        List<Merchant> own = new ArrayList<>();
        Customer payee;
        try {
            for (int phase = 0; phase < 3; phase++) {
                List<Future<Customer>> made = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    made.add(builders.submit(() -> {
                        Customer customer = storm.world.customer(RiskStormTest.FUNDING);
                        storm.reserved.add(customer.party);
                        storm.beneficiaries.put(customer.party, Map.of("US-USD",
                                storm.beneficiary(customer, "US", "USD")));
                        return customer;
                    }));
                }
                List<Customer> payers = new ArrayList<>();
                for (Future<Customer> customer : made) {
                    payers.add(customer.get(5, TimeUnit.MINUTES));
                }
                payersOf.add(payers);
            }
            payee = storm.world.customer(1_00);
            storm.reserved.add(payee.party);
            List<Future<Merchant>> shops = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                shops.add(builders.submit(() -> storm.world.merchant(storm.merchantAdministrator)));
            }
            for (Future<Merchant> shop : shops) {
                own.add(shop.get(5, TimeUnit.MINUTES));
            }
        } finally {
            builders.shutdown();
        }
        // Measured on the host's seconds: the tracked clocks hold still through the server's steps (RiskStormClock).
        RiskStormClock.atHostSpeed();
        storm.rest("latency: before");
        phase("sweep on", payersOf.get(0), payee,
                i -> storm.merchants.get(RANDOMNESS.nextInt(storm.merchants.size())));
        storm.rest("latency: between");
        for (Instance on : List.of(storm.a, storm.b)) {
            on.monitoring().stop();
        }
        try {
            phase("sweep off", payersOf.get(1), payee,
                    i -> storm.merchants.get(RANDOMNESS.nextInt(storm.merchants.size())));
        } finally {
            for (Instance on : List.of(storm.a, storm.b)) {
                on.monitoring().start();
            }
        }
        storm.rest("latency: between again");
        // The third phase isolates owner item 9's question: each payer its own merchant, so no MERCHANT subject lock (R2a)
        // is shared - the uncontended cost of the same T-eval, the sweep on.
        phase("sweep on, a merchant per payer", payersOf.get(2), payee, own::get);
        for (String phase : refs.keySet()) {
            Map<String, long[]> perOperation = new LinkedHashMap<>();
            for (String operation : OPERATIONS) {
                perOperation.put(operation, percentiles(refs.get(phase).getOrDefault(operation, Set.of()), operation));
            }
            figures.put(phase, perOperation);
        }
        for (String operation : OPERATIONS) {
            long on = figures.get("sweep on").get(operation)[2];
            long off = figures.get("sweep off").get(operation)[2];
            if (on > BUDGET_MICROS && off <= BUDGET_MICROS) {
                findings.add(operation + ": p99 " + on / 1000.0 + " ms with the sweep on, " + off / 1000.0
                        + " ms off - M3's split trigger");
            } else if (on > BUDGET_MICROS || off > BUDGET_MICROS) {
                findings.add(operation + ": p99 over the 50 ms budget with the sweep " + (on > BUDGET_MICROS
                        && off > BUDGET_MICROS ? "on and off" : on > BUDGET_MICROS ? "on only" : "off only") + " ("
                        + on / 1000.0 + " / " + off / 1000.0 + " ms, on / off) - over budget, not M3's trigger");
            }
            long alone = figures.get("sweep on, a merchant per payer").get(operation)[2];
            if (on > BUDGET_MICROS && alone <= BUDGET_MICROS) {
                findings.add(operation + ": within budget uncontended (p99 " + alone / 1000.0 + " ms) - the excess is"
                        + " the shared MERCHANT subject lock (owner item 9)");
            }
            storm.coverage.put("latency: " + operation + " judged with the sweep on",
                    figures.get("sweep on").get(operation)[0]);
            storm.coverage.put("latency: " + operation + " judged with the sweep off",
                    figures.get("sweep off").get(operation)[0]);
        }
        System.out.println("P13-TST-001 LATENCY TABLE: " + Runtime.getRuntime().availableProcessors() + " cores, "
                + PHASE.toSeconds() + " s a phase, " + storm.merchants.size() + " shared merchants, ten payers on two"
                + " instances, the instances' clocks at the host's speed");
        for (String row : table().strip().split("\\R")) {
            System.out.println("P13-TST-001 LATENCY ROW: " + row.strip());
        }
        System.out.println("P13-TST-001 LATENCY FINDINGS: " + findings);
        // The phases measure the budget; the over-budget rule firing at their rest is their reading, not a defect.
        storm.tolerates("the latency phases", "RiskEvaluationLatencyOverBudget");
        storm.restAndCensus("the latency phases");
    }

    private void phase(String name, List<Customer> payers, Customer payee,
            java.util.function.IntFunction<Merchant> merchantOf) throws Exception {
        Map<String, Set<String>> judgedRefs = new ConcurrentHashMap<>();
        refs.put(name, judgedRefs);
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService threads = Executors.newFixedThreadPool(payers.size() + 1);
        List<Future<?>> running = new ArrayList<>();
        try {
            running.add(threads.submit(() -> {
                while (!stop.get()) {
                    RiskStormCrossBorder.references();
                    Thread.sleep(5_000);
                }
                return null;
            }));
            for (int i = 0; i < payers.size(); i++) {
                Customer payer = payers.get(i);
                int index = i;
                Instance on = i % 2 == 0 ? storm.a : storm.b;
                running.add(threads.submit(() -> {
                    while (!stop.get()) {
                        oneMovement(on, payer, payers.get((index + 1) % payers.size()), judgedRefs,
                                merchantOf.apply(index));
                    }
                    return null;
                }));
            }
            Thread.sleep(PHASE.toMillis());
        } finally {
            stop.set(true);
            threads.shutdown();
            threads.awaitTermination(3, TimeUnit.MINUTES);
        }
        for (Future<?> task : running) {
            task.get();
        }
    }

    /** One judged movement of {@code payer}, in turn every operation: its command reference kept for the phase. */
    private void oneMovement(Instance on, Customer payer, Customer payee, Map<String, Set<String>> judgedRefs,
            Merchant merchant)
            throws Exception {
        int dice = RANDOMNESS.nextInt(5);
        RiskStormWorld.Tally tally = storm.tally;
        switch (dice) {
            case 0 -> {
                Answer posted = on.door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet,
                        payee.wallet, 1_00 + RANDOMNESS.nextInt(1_000)), payer.mfaToken, key(), Audience.SUBJECT);
                if (judged(tally, "latency: transfer", posted) && posted.ok()) {
                    keep(judgedRefs, "TRANSFER", posted.field("id"));
                }
            }
            case 1 -> {
                Answer answered = on.door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(payer,
                        1_00 + RANDOMNESS.nextInt(1_000)), payer.mfaToken, key(), Audience.SUBJECT);
                if (judged(tally, "latency: withdrawal", answered) && answered.ok()) {
                    keep(judgedRefs, "WITHDRAWAL", answered.field("id"));
                }
            }
            case 2, 3 -> {
                boolean card = dice == 2;

                String token = storm.checkoutSession(on, merchant, 1_00 + RANDOMNESS.nextInt(1_000));
                if (token == null) {
                    return;
                }
                Answer confirmed = on.door().post(RiskStormWorld.CONFIRMATION, RiskStormTest.confirmation(token,
                        card ? payer.card : null), payer.mfaToken, null, Audience.SUBJECT);
                if (judged(tally, "latency: confirmation", confirmed) && confirmed.ok()) {
                    confirmed.optionalField("paymentIntentId").ifPresent(intent -> keep(judgedRefs,
                            card ? "CARD_PAYMENT" : "WALLET_PAYMENT", intent));
                }
            }
            default -> {
                Answer quoted = on.door().post(RiskStormCrossBorder.QUOTES, "{\"beneficiaryId\":\""
                        + storm.beneficiaries.get(payer.party).get("US-USD") + "\",\"sourceCurrency\":\"EUR\","
                        + "\"fixedSide\":\"FIXED_SOURCE\",\"amount\":\"" + RiskStormWorld.decimal(1_000
                        + RANDOMNESS.nextInt(1_000)) + "\"}", payer.mfaToken, key(), Audience.SUBJECT);
                if (!judged(tally, "latency: quote", quoted, "429 fx.TooManyOpenQuotes", "503 fx.RateUnavailable")
                        || !quoted.ok()) {
                    return;
                }
                Answer paid = on.door().post(RiskStormCrossBorder.PAYMENTS, "{\"quoteId\":\"" + quoted.field("id")
                        + "\"}", payer.mfaToken, key(), Audience.SUBJECT);
                if (judged(tally, "latency: cross-border payment", paid, "422 crossborder.RiskRefused",
                        "422 crossborder.LimitRefused")) {
                    keep(judgedRefs, "CROSS_BORDER", quoted.field("id"));
                }
            }
        }
    }

    private static void keep(Map<String, Set<String>> refs, String operation, String ref) {
        refs.computeIfAbsent(operation, k -> ConcurrentHashMap.newKeySet()).add(ref);
    }

    /** {count, p50, p99, max} of the sealed latency of {@code operation}'s assessments of {@code commandRefs}. */
    private static long[] percentiles(Set<String> commandRefs, String operation) throws SQLException {
        if (commandRefs.isEmpty()) {
            return new long[] {0, 0, 0, 0};
        }
        String[] row = RiskStormWorld.asOwner(owner -> RiskStormWorld.row(owner, "SELECT count(*),"
                + " coalesce(percentile_disc(0.5) WITHIN GROUP (ORDER BY latency_micros), 0),"
                + " coalesce(percentile_disc(0.99) WITHIN GROUP (ORDER BY latency_micros), 0),"
                + " coalesce(max(latency_micros), 0) FROM risk.risk_assessment WHERE operation = ? AND command_ref ="
                + " ANY (?)", operation, owner.createArrayOf("text", commandRefs.toArray())));
        return new long[] {Long.parseLong(row[0]), Long.parseLong(row[1]), Long.parseLong(row[2]),
            Long.parseLong(row[3])};
    }

    /** The table: phase -> operation -> n, p50, p99, max (ms). */
    String table() {
        StringBuilder table = new StringBuilder();
        figures.forEach((phase, perOperation) -> {
            table.append("\n  ").append(phase).append(':');
            perOperation.forEach((operation, f) -> table.append(String.format("%n    %-15s n=%-5d p50=%7.2f ms"
                    + "  p99=%7.2f ms  max=%7.2f ms", operation, f[0], f[1] / 1000.0, f[2] / 1000.0, f[3] / 1000.0)));
        });
        return table.toString();
    }
}
