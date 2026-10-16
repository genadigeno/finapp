package com.finapp.lending;

import com.finapp.creditmath.testing.SecondJvm;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * The battery's second JVM (`P11-TST-002`; the backlog's "every figure replayed {@code IDENTICAL} in two JVMs"): the same
 * seeded population generated and replayed by a process with another default charset, locale and time zone and other
 * identity hash codes ({@link SecondJvm#CHILD_DEFAULTS}), one line per account - {@code kind index verdict replayed checks
 * recordDigest} - and a totals line. {@code AmortisationBatteryTest} computes the same lines in its own JVM and requires
 * them equal, every line: both JVMs replay the same stored bytes (the digests equal) to the same verdict.
 *
 * <p>Locale-free throughout: concatenation, {@link Long#toString}, {@link java.math.BigDecimal#toPlainString} and ISO dates
 * only - never a default-locale {@code String.format} - and every digest over explicit UTF-8.
 */
final class AmortisationInAnotherJvm {

    private AmortisationInAnotherJvm() {}

    /** {@code arguments}: the output file, the loans, the lines. */
    public static void main(String[] arguments) throws IOException {
        int loans = Integer.parseInt(arguments[1]);
        int lines = Integer.parseInt(arguments[2]);
        LoanReplayProof replay = new LoanReplayProof();
        List<Summary> summaries = IntStream.range(0, loans + lines).parallel().mapToObj(position -> {
            AmortisationWorld.Kind kind = position < loans ? AmortisationWorld.Kind.LOAN : AmortisationWorld.Kind.LINE;
            int index = position < loans ? position : position - loans;
            LoanRecord record = AmortisationWorld.generate(kind, index, AmortisationWorld.Arithmetic.STANDARD).record();
            return summary(kind, index, record, replay.replay(record));
        }).toList();
        SecondJvm.write(Path.of(arguments[0]), lines(summaries, loans, lines));
    }

    /** One account's line and the counts the totals line sums. */
    record Summary(String line, String digest, boolean identical, long accruals, long billings, long statements,
            long payments) {}

    /** The account's line: {@code kind index verdict replayed checks recordDigest}. */
    static Summary summary(AmortisationWorld.Kind kind, int index, LoanRecord record, LoanReplayProof.Replay replay) {
        String digest = digest(record);
        String checks = replay.checks().isEmpty() ? "-"
                : replay.checks().stream().map(Enum::name).collect(Collectors.joining(","));
        String line = kind.name() + " " + index + " " + replay.verdict().name() + " " + replay.replayed() + " " + checks
                + " " + digest;
        return new Summary(line, digest, replay.verdict() == LoanReplayProof.Verdict.IDENTICAL, record.accruals().size(),
                record.billings().size(), record.statements().size(), record.repayments().size());
    }

    /** Every account's line in index order, then the totals: counts and the SHA-256 of every record digest in order. */
    static List<String> lines(List<Summary> summaries, int loans, int lines) {
        List<String> out = new ArrayList<>(summaries.size() + 1);
        MessageDigest all = sha256();
        long identical = 0;
        long accruals = 0;
        long billings = 0;
        long statements = 0;
        long payments = 0;
        for (Summary summary : summaries) {
            out.add(summary.line());
            all.update(summary.digest().getBytes(StandardCharsets.UTF_8));
            identical += summary.identical() ? 1 : 0;
            accruals += summary.accruals();
            billings += summary.billings();
            statements += summary.statements();
            payments += summary.payments();
        }
        out.add("loans=" + loans + " lines=" + lines + " identical=" + identical + " accruals=" + accruals + " billings="
                + billings + " statements=" + statements + " payments=" + payments + " all="
                + HexFormat.of().formatHex(all.digest()));
        return out;
    }

    // ------------------------------------------------------------------ the record's digest

    /** The SHA-256 of every figure, id, date, number and byte of {@code record}, in one canonical text. */
    static String digest(LoanRecord record) {
        Text text = new Text();
        LoanRecord.Account account = record.loan();
        text.add("account").add(account.id().value()).add(account.party()).add(account.product()).add(account.kind())
                .add(account.status()).add(account.currency().code()).add(account.offer().value()).add(account.decision())
                .add(account.openedAt()).add(account.closedAt()).add(account.closureReason()).end();
        for (LoanRecord.Agreement agreement : record.agreements()) {
            text.add("agreement").add(agreement.id()).add(agreement.version()).add(agreement.offer())
                    .add(agreement.decision()).add(agreement.termsVersionId()).add(agreement.product())
                    .add(agreement.scale()).add(agreement.amountMinor()).add(agreement.termMonths())
                    .add(agreement.nominalAnnualRate()).add(agreement.repaymentDay()).add(agreement.scheduleEngine())
                    .add(agreement.accrualEngine()).add(agreement.allocationEngine()).add(agreement.statementEngine())
                    .add(agreement.templateId()).add(agreement.templateVersion()).add(agreement.canonicalFormat())
                    .add(agreement.canonicalTerms()).add(agreement.termsSha256Hex()).add(agreement.effectiveFrom())
                    .add(agreement.createdAt()).add(agreement.order()).end();
            record.termsVersions().get(agreement.termsVersionId()).ifPresent(version -> text.add("version")
                    .add(version.row().id().value()).add(version.row().version()).add(version.row().status())
                    .add(version.terms().nominalAnnualRate().toPlainString()).add(version.terms().servicingZone())
                    .end());
        }
        record.offer().ifPresent(offer -> {
            text.add("offer").add(offer.id().value()).add(offer.application().value()).add(offer.status())
                    .add(offer.offeredAt()).add(offer.expiresAt()).add(offer.terms().termsSha256Hex());
            offer.terms().illustration().ifPresent(shown -> text.add(shown.start()).add(shown.firstDueDate())
                    .add(shown.instalment()).add(shown.finalInstalment()).add(shown.totalInterest())
                    .add(shown.totalPayable()));
            offer.terms().minimumPayment().ifPresent(rule -> text.add(rule.floor()).add(rule.ratioBps())
                    .add(rule.paymentDueDays()));
            text.end();
        });
        record.disbursement().ifPresent(found -> text.add("disbursement").add(found.id()).add(found.status())
                .add(found.entry().map(entry -> entry.value().toString())).add(found.valueDate().map(Object::toString))
                .add(found.decidedAt()).add(found.createdAt()).end());
        record.accrualStart().ifPresent(found -> text.add("start").add(found.startDate()).add(found.origin())
                .add(found.entry().map(entry -> entry.value().toString())).add(found.createdAt()).end());
        for (LoanRecord.ScheduleVersion schedule : record.schedules()) {
            text.add("schedule").add(schedule.id()).add(schedule.version()).add(schedule.agreementId())
                    .add(schedule.engine()).add(schedule.principal()).add(schedule.instalment())
                    .add(schedule.finalInstalment()).add(schedule.totalInterest()).add(schedule.termMonths())
                    .add(schedule.startDate()).add(schedule.intendedDay()).add(schedule.createdAt()).add(schedule.order())
                    .end();
            for (LoanRecord.Instalment instalment : schedule.instalments()) {
                text.add("instalment").add(instalment.id()).add(instalment.sequence()).add(instalment.periodStart())
                        .add(instalment.dueDate()).add(instalment.days()).add(instalment.opening())
                        .add(instalment.interest()).add(instalment.principal()).add(instalment.amount())
                        .add(instalment.closing()).end();
            }
        }
        for (LoanRecord.Draw draw : record.draws()) {
            text.add("draw").add(draw.id()).add(draw.amount()).add(draw.entry().value()).add(draw.valueDate())
                    .add(draw.createdAt()).add(draw.order()).end();
        }
        for (LoanRecord.Accrual accrual : record.accruals()) {
            text.add("accrual").add(accrual.id()).add(accrual.date()).add(accrual.periodStart()).add(accrual.periodEnd())
                    .add(accrual.base()).add(accrual.cumulativeExact().toPlainString()).add(accrual.posted())
                    .add(accrual.engine()).add(accrual.entry().map(entry -> entry.value().toString()))
                    .add(accrual.createdAt()).add(accrual.order()).end();
        }
        for (LoanRecord.Billing billing : record.billings()) {
            text.add("billing").add(billing.id()).add(billing.kind()).add(billing.scheduleId())
                    .add(billing.instalment().map(Object::toString)).add(billing.sequence()).add(billing.periodStart())
                    .add(billing.dueDate()).add(billing.interestDue()).add(billing.principalDue())
                    .add(billing.scheduleEngine()).add(billing.accrualEngine())
                    .add(billing.entry().map(entry -> entry.value().toString())).add(billing.createdAt())
                    .add(billing.order()).end();
        }
        for (LoanRecord.StatementRow statement : record.statements()) {
            text.add("statement").add(statement.id()).add(statement.cycleStart()).add(statement.cycleEnd())
                    .add(statement.dueDate()).add(statement.interestBilled()).add(statement.feesCarried())
                    .add(statement.principalBilled()).add(statement.minimumPayment()).add(statement.statementEngine())
                    .add(statement.accrualEngine()).add(statement.entry().map(entry -> entry.value().toString()))
                    .add(statement.createdAt()).add(statement.order()).end();
        }
        for (LoanRecord.Application application : record.applications()) {
            text.add("application").add(application.id()).add(application.billingId()).add(application.amount())
                    .add(application.allocationEngine()).add(application.entry().value()).add(application.createdAt())
                    .add(application.order());
            for (LoanRecord.ApplicationLine line : application.lines()) {
                text.add(line.ordinal()).add(line.billingId()).add(line.component()).add(line.amount());
            }
            text.end();
        }
        for (LoanRecord.Payment payment : record.repayments()) {
            text.add("payment").add(payment.id()).add(payment.kind()).add(payment.amount())
                    .add(payment.allocationEngine()).add(payment.entry().value()).add(payment.valueDate())
                    .add(payment.createdAt()).add(payment.order());
            for (LoanRecord.PaymentLine line : payment.lines()) {
                text.add(line.id()).add(line.ordinal()).add(line.target()).add(line.billingId().map(Object::toString))
                        .add(line.component().map(Enum::name)).add(line.amount());
            }
            text.end();
        }
        for (LoanRecord.Fee fee : record.fees()) {
            text.add("fee").add(fee.id()).add(fee.dueItemId()).add(fee.kind()).add(fee.businessDate()).add(fee.amount())
                    .add(fee.entry().value()).add(fee.createdAt()).add(fee.order()).end();
        }
        record.closure().ifPresent(closure -> text.add("closure").add(closure.id()).add(closure.reason())
                .add(closure.refund()).add(closure.refundEntry().map(entry -> entry.value().toString()))
                .add(closure.repaymentId().map(Object::toString)).add(closure.createdAt()).add(closure.order()).end());
        for (LoanRecord.LedgerAccountRow owned : record.ledgerAccounts()) {
            text.add("ledger").add(owned.id().value()).add(owned.purpose()).add(owned.currency().code())
                    .add(owned.status()).end();
        }
        for (PurposeBalances.DatedLine line : record.journalLines()) {
            text.add("line").add(line.entry().value()).add(line.account().value()).add(line.valueDate())
                    .add(line.direction()).add(line.amount()).end();
        }
        return text.digest();
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform provides SHA-256", absent);
        }
    }

    /** A canonical text fed to SHA-256 in UTF-8 chunks - '|' between tokens, a newline after each row. */
    private static final class Text {

        private final MessageDigest digest = sha256();
        private final StringBuilder buffer = new StringBuilder(1 << 16);

        Text add(Object token) {
            String value = switch (token) {
                case Money money -> money.currency().code() + ":" + money.scale() + ":" + money.minorUnits();
                case Optional<?> optional -> optional.map(Object::toString).orElse("NONE");
                case Integer number -> Integer.toString(number);
                case Long number -> Long.toString(number);
                default -> token.toString();
            };
            buffer.append(value).append('|');
            return this;
        }

        Text end() {
            buffer.append('\n');
            if (buffer.length() > 1 << 15) {
                flush();
            }
            return this;
        }

        private void flush() {
            digest.update(buffer.toString().getBytes(StandardCharsets.UTF_8));
            buffer.setLength(0);
        }

        String digest() {
            flush();
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
