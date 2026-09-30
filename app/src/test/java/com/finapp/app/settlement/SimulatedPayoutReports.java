package com.finapp.app.settlement;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The simulated payout provider's daily report, rendered as `SIM_PAYOUT_CSV` v1 (`P8-TSK-018`)
 * from what {@code merchant.merchant_payout} records — each completed payout's provider reference,
 * our {@code pyo-} reference and its amount, read off the rows by the caller — with a payout
 * return beside the executions. A fixture: the format's own golden file freezes v1; this renders
 * the same shape for the app's end-to-end suites, the trailer's count and net computed the way the
 * provider computes them (Σ signed amounts − Σ fees).
 */
public final class SimulatedPayoutReports {

    /** One reported record; {@code amount} is signed from the platform's view. */
    public record Entry(
            String code,
            String amount,
            String fee,
            String providerRef,
            Optional<String> ourRef,
            String beneficiary) {

        public Entry {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(fee, "fee must not be null");
            Objects.requireNonNull(providerRef, "providerRef must not be null");
            Objects.requireNonNull(ourRef, "ourRef must not be null");
            Objects.requireNonNull(beneficiary, "beneficiary must not be null");
        }

        /** A payout the provider executed: money out, negative. */
        public static Entry settled(String amount, String fee, String providerRef, String ourRef) {
            return new Entry("SETTLED", "-" + amount, fee, providerRef, Optional.of(ourRef),
                    "Acme GmbH");
        }

        /** A payout the beneficiary bank returned: money back, positive. */
        public static Entry returned(String amount, String providerRef, String ourRef) {
            return new Entry("RETURNED", amount, "", providerRef, Optional.of(ourRef),
                    "Acme GmbH");
        }
    }

    private final String batchRef;
    private final String currency;
    private final String businessDate;
    private final String remittanceReference;
    private final List<Entry> entries = new ArrayList<>();

    public SimulatedPayoutReports(
            String batchRef, String currency, String businessDate, String remittanceReference) {
        this.batchRef = Objects.requireNonNull(batchRef);
        this.currency = Objects.requireNonNull(currency);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.remittanceReference = Objects.requireNonNull(remittanceReference);
    }

    public SimulatedPayoutReports with(Entry entry) {
        entries.add(Objects.requireNonNull(entry));
        return this;
    }

    /** The report's bytes: the header, one record per entry, and the trailer's totals. */
    public byte[] render() {
        BigDecimal net = BigDecimal.ZERO;
        StringBuilder csv =
                new StringBuilder()
                        .append("H,SIM_PAYOUT_CSV,1,").append(batchRef).append(',')
                        .append(currency).append(',').append(businessDate).append('\n');
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            net = net.add(new BigDecimal(entry.amount()));
            if (!entry.fee().isEmpty()) {
                net = net.subtract(new BigDecimal(entry.fee()));
            }
            csv.append("D,").append(i + 1).append(',').append(entry.code()).append(',')
                    .append(entry.amount()).append(',').append(entry.fee()).append(',')
                    .append(entry.providerRef()).append(',')
                    .append(entry.ourRef().orElse("")).append(',')
                    .append(entry.beneficiary()).append('\n');
        }
        csv.append("T,").append(entries.size()).append(',').append(net.toPlainString())
                .append(',').append(remittanceReference).append('\n');
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
}
