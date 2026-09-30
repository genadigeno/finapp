package com.finapp.app.settlement;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Renders `SIM_PSP_CSV` v1 — what the simulated PSP did (`P8-TSK-008`) — for the suites that
 * feed settlement evidence from the payments world: a caller reads its captures, refunds and
 * dispute stages off the payments tables and states them here as line specs, and the render
 * is a byte-exact report of that history, with the trailer's count and net computed the way
 * the counterparty computes them.
 *
 * <p><strong>The parse-level faults</strong> are switches, one per defect family the parse
 * leg rejects, so a later suite (`P8-TSK-009`…) can drive each rejection over HTTP from an
 * otherwise-genuine report. The matching-level faults (amount, fee and date deltas) land
 * with their consumers, by the backlog's own scope. {@code SimulatedSettlementReportsTest}
 * pins that each fault produces exactly the defect it claims.
 */
public final class SimulatedSettlementReports {

    /** One parse-level defect, injected into an otherwise-genuine report. */
    public enum Fault {
        MALFORMED_FIELD,
        BAD_TRAILER_COUNT,
        BAD_TRAILER_NET,
        UNKNOWN_LINE,
        DUPLICATE_LINE,
        WRONG_CURRENCY,
        PAN_IN_FREE_TEXT,
        IBAN_IN_FREE_TEXT
    }

    /** One reported transaction, as the payments tables state it. */
    public record Line(
            String providerType,
            String amount,
            String fee,
            String primaryRef,
            String acquirerRef,
            String ourRef,
            String descriptor) {

        public static Line capture(
                String pspCaptureRef, String acquirerRef, String ourRef, String amount,
                String fee) {
            return new Line("SALE", amount, fee, pspCaptureRef, acquirerRef, ourRef,
                    "Card capture");
        }

        public static Line refund(String pspRefundRef, String ourRef, String amount) {
            return new Line("REFUND", "-" + amount, "", pspRefundRef, "", ourRef,
                    "Card refund");
        }

        public static Line chargeback(String disputeRef, String amount) {
            return new Line("CHARGEBACK", "-" + amount, "", disputeRef, "", "", "Chargeback");
        }

        public static Line unknown(String reference, String signedAmount) {
            return new Line("PROMO_BONUS", signedAmount, "", reference, "", "",
                    "Unclassified by the platform");
        }
    }

    private final String batchRef;
    private final String currency;
    private final LocalDate businessDate;
    private final String remittanceReference;
    private final List<Line> lines = new ArrayList<>();
    private final Set<Fault> faults = EnumSet.noneOf(Fault.class);

    public SimulatedSettlementReports(
            String batchRef, String currency, LocalDate businessDate,
            String remittanceReference) {
        this.batchRef = Objects.requireNonNull(batchRef);
        this.currency = Objects.requireNonNull(currency);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.remittanceReference = Objects.requireNonNull(remittanceReference);
    }

    public SimulatedSettlementReports with(Line line) {
        lines.add(Objects.requireNonNull(line));
        return this;
    }

    public SimulatedSettlementReports faulted(Fault fault) {
        faults.add(Objects.requireNonNull(fault));
        return this;
    }

    /** The report's bytes — UTF-8, LF, a trailing newline, the trailer's fold the PSP's. */
    public byte[] render() {
        StringBuilder report = new StringBuilder();
        report.append("H,SIM_PSP_CSV,1,").append(batchRef).append(',').append(currency)
                .append(',').append(businessDate).append('\n');
        long netMinor = 0;
        int seq = 0;
        for (Line line : lines) {
            seq++;
            int recordSeq = faults.contains(Fault.DUPLICATE_LINE) && seq == lines.size() && seq > 1
                    ? seq - 1
                    : seq;
            String amount =
                    faults.contains(Fault.MALFORMED_FIELD) && seq == 1
                            ? "not-a-number"
                            : line.amount();
            String lineCurrency =
                    faults.contains(Fault.WRONG_CURRENCY) && seq == 1 ? "GBP" : currency;
            String descriptor = line.descriptor();
            if (faults.contains(Fault.PAN_IN_FREE_TEXT) && seq == 1) {
                descriptor = "cardholder quoted 4111 1111 1111 1111";
            }
            if (faults.contains(Fault.IBAN_IN_FREE_TEXT) && seq == 1) {
                descriptor = "pay instead to DE89370400440532013000";
            }
            report.append("D,").append(recordSeq).append(',').append(line.providerType())
                    .append(',').append(amount).append(',').append(line.fee())
                    .append(',').append(lineCurrency).append(',').append(businessDate)
                    .append(",,,").append(line.primaryRef()).append(',')
                    .append(line.acquirerRef()).append(",,").append(line.ourRef())
                    .append(',').append(descriptor).append('\n');
            netMinor += minorOf(line.amount()) - (line.fee().isEmpty() ? 0
                    : minorOf(line.fee()));
        }
        long declaredCount = lines.size() + (faults.contains(Fault.BAD_TRAILER_COUNT) ? 1 : 0);
        long declaredNet = netMinor + (faults.contains(Fault.BAD_TRAILER_NET) ? 1 : 0);
        if (faults.contains(Fault.UNKNOWN_LINE)) {
            report.append("X,unrecognised-record\n");
        }
        report.append("T,").append(declaredCount).append(',').append(decimalOf(declaredNet))
                .append(',').append(remittanceReference).append('\n');
        return report.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Two-decimal money as the format writes it — a fixture, so the scale is the report's. */
    private static long minorOf(String decimal) {
        boolean negative = decimal.startsWith("-");
        String[] parts = (negative ? decimal.substring(1) : decimal).split("\\.", 2);
        long minor = Long.parseLong(parts[0]) * 100
                + (parts.length == 2 ? Long.parseLong((parts[1] + "00").substring(0, 2)) : 0);
        return negative ? -minor : minor;
    }

    private static String decimalOf(long minor) {
        long magnitude = Math.abs(minor);
        return (minor < 0 ? "-" : "") + (magnitude / 100) + "."
                + String.format("%02d", magnitude % 100);
    }
}
