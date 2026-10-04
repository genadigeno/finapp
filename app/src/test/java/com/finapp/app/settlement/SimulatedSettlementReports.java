package com.finapp.app.settlement;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * otherwise-genuine report. The matching-level faults are stated as the lines themselves
 * (`P8-TST-001`): an amount or fee delta is a line whose amount or fee differs from the record,
 * a late date is a line {@linkplain Line#settledOn settled} past its window, a duplicate is the
 * same line twice, a counterparty correction is an {@linkplain Line#adjustment adjustment} line
 * naming its original, and a wrong currency is a whole report constructed in another currency
 * (the header's currency is every line's - the line-level {@link Fault#WRONG_CURRENCY} is a
 * parse defect, kept as one). {@code SimulatedSettlementReportsTest} pins that each fault
 * produces exactly the defect it claims.
 *
 * <p>Amounts are the caller's decimal strings, written as given; the trailer's net is folded
 * and written at the REPORT CURRENCY'S own minor units (`P9-TSK-003`: JPY's 0, BHD's 3, two
 * for EUR, GBP and USD, whose renders are byte for byte what they were).
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

    /**
     * One reported transaction, as the payments tables state it. An empty
     * {@code settlementDate} renders its column empty, so the line settles on the report's own
     * business date.
     */
    public record Line(
            String providerType,
            String amount,
            String fee,
            String primaryRef,
            String acquirerRef,
            String ourRef,
            String descriptor,
            Optional<LocalDate> settlementDate) {

        public Line {
            Objects.requireNonNull(settlementDate, "settlementDate must not be null");
        }

        /** A line settling on the report's own business date. */
        public Line(
                String providerType,
                String amount,
                String fee,
                String primaryRef,
                String acquirerRef,
                String ourRef,
                String descriptor) {
            this(providerType, amount, fee, primaryRef, acquirerRef, ourRef, descriptor,
                    Optional.empty());
        }

        /** The same line settled on {@code date} - the late-date fault, past its window. */
        public Line settledOn(LocalDate date) {
            return new Line(providerType, amount, fee, primaryRef, acquirerRef, ourRef,
                    descriptor, Optional.of(date));
        }

        /** The same record with no fee of its own - a repeated line, its fee not repeated. */
        public Line withoutFee() {
            return new Line(providerType, amount, "", primaryRef, acquirerRef, ourRef,
                    descriptor, settlementDate);
        }

        /**
         * A counterparty correction ({@code COUNTERPARTY_ADJUSTMENT}) naming its original
         * line's PSP reference, signed from the platform's view: the claw-back of an
         * over-payment is negative.
         */
        public static Line adjustment(String originalRef, String signedAmount) {
            return new Line("ADJUSTMENT", signedAmount, "", originalRef, "", "",
                    "Counterparty correction");
        }

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
    private final int scale;

    public SimulatedSettlementReports(
            String batchRef, String currency, LocalDate businessDate,
            String remittanceReference) {
        this.batchRef = Objects.requireNonNull(batchRef);
        this.currency = Objects.requireNonNull(currency);
        this.businessDate = Objects.requireNonNull(businessDate);
        this.remittanceReference = Objects.requireNonNull(remittanceReference);
        this.scale = CurrencyCode.of(currency).minorUnits();
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
                    .append(',')
                    .append(line.settlementDate().map(LocalDate::toString).orElse(""))
                    .append(",,").append(line.primaryRef()).append(',')
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

    /**
     * A decimal string as minor units at the report currency's scale - a fixture, so a digit
     * past the scale is cut (toward zero) as the two-decimal reading always cut it.
     */
    private long minorOf(String decimal) {
        return new BigDecimal(decimal).setScale(scale, RoundingMode.DOWN).unscaledValue()
                .longValueExact();
    }

    /** Minor units as the format writes them: the currency's scale, a sign when negative. */
    private String decimalOf(long minor) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
