package com.finapp.app.settlement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The simulated instant scheme's cycle report, rendered as `SIM_SCHEME_JSON` v1 (`P8-TSK-017`) from
 * the completions a scenario seeded — each entry naming the scheme reference, end-to-end reference
 * or our reference the platform's expectation is keyed by. A fixture: the format's own golden file
 * freezes v1; this renders the same shape for the app's end-to-end suites.
 *
 * <p><strong>The cycle-shift fault</strong> is simply a report rendered under a cycle other than
 * the one a completion announced: the matcher must still allocate and raise a zero-value
 * {@code TIMING_DIFFERENCE}, never refuse.
 */
public final class SimulatedSchemeReports {

    /** One reported execution. {@code dir} is the scheme's: {@code C} into the platform. */
    public record Entry(
            String code,
            String dir,
            String amount,
            String fee,
            String schemeRef,
            Optional<String> endToEndRef,
            Optional<String> ourRef) {

        public Entry {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(dir, "dir must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            Objects.requireNonNull(schemeRef, "schemeRef must not be null");
            Objects.requireNonNull(endToEndRef, "endToEndRef must not be null");
            Objects.requireNonNull(ourRef, "ourRef must not be null");
        }

        /** A pay-in the scheme credited. */
        public static Entry payIn(String amount, String fee, String schemeRef, String endToEnd) {
            return new Entry("CT", "C", amount, fee, schemeRef, Optional.of(endToEnd),
                    Optional.empty());
        }

        /** A withdrawal the scheme debited. */
        public static Entry withdrawal(
                String amount, String fee, String schemeRef, String endToEnd) {
            return new Entry("CT", "D", amount, fee, schemeRef, Optional.of(endToEnd),
                    Optional.empty());
        }

        /** A return the scheme debited, named by our reference. */
        public static Entry returned(String amount, String fee, String schemeRef, String ourRef) {
            return new Entry("RT", "D", amount, fee, schemeRef, Optional.empty(),
                    Optional.of(ourRef));
        }
    }

    private final String cycle;
    private final String currency;
    private final String businessDate;
    private final String remittanceReference;
    private final List<Entry> entries = new ArrayList<>();

    public SimulatedSchemeReports(
            String cycle, String currency, String businessDate, String remittanceReference) {
        this.cycle = Objects.requireNonNull(cycle);
        this.currency = Objects.requireNonNull(currency);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.remittanceReference = Objects.requireNonNull(remittanceReference);
    }

    public SimulatedSchemeReports with(Entry entry) {
        entries.add(Objects.requireNonNull(entry));
        return this;
    }

    /** The report's bytes: credits − debits − fees as the net, the entries counted. */
    public byte[] render() {
        BigDecimal net = BigDecimal.ZERO;
        StringBuilder json =
                new StringBuilder()
                        .append("{\n")
                        .append("  \"format\": \"SIM_SCHEME_JSON\",\n")
                        .append("  \"version\": 1,\n")
                        .append("  \"cycle\": \"").append(cycle).append("\",\n")
                        .append("  \"currency\": \"").append(currency).append("\",\n")
                        .append("  \"businessDate\": \"").append(businessDate).append("\",\n")
                        .append("  \"remittanceReference\": \"").append(remittanceReference)
                        .append("\",\n")
                        .append("  \"entries\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            BigDecimal amount = new BigDecimal(entry.amount());
            BigDecimal fee = new BigDecimal(entry.fee());
            net = net.add("C".equals(entry.dir()) ? amount : amount.negate()).subtract(fee);
            json.append("    {\"seq\": ").append(i + 1)
                    .append(", \"code\": \"").append(entry.code())
                    .append("\", \"dir\": \"").append(entry.dir())
                    .append("\", \"amount\": \"").append(entry.amount()).append('"');
            if (fee.signum() != 0) {
                json.append(", \"fee\": \"").append(entry.fee()).append('"');
            }
            json.append(", \"schemeRef\": \"").append(entry.schemeRef()).append('"');
            entry.endToEndRef().ifPresent(
                    ref -> json.append(", \"endToEndRef\": \"").append(ref).append('"'));
            entry.ourRef().ifPresent(ref -> json.append(", \"ourRef\": \"").append(ref).append('"'));
            json.append('}').append(i + 1 < entries.size() ? ",\n" : "\n");
        }
        json.append("  ],\n")
                .append("  \"net\": \"").append(net.toPlainString()).append("\",\n")
                .append("  \"entryCount\": ").append(entries.size()).append('\n')
                .append("}\n");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }
}
