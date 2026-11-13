package com.finapp.app.restore;

import static com.finapp.app.restore.RestoreRehearsalSeed.APPROVER;
import static com.finapp.app.restore.RestoreRehearsalSeed.EUR;
import static com.finapp.app.restore.RestoreRehearsalSeed.PROPOSER;
import static com.finapp.app.restore.RestoreRehearsalSeed.as;
import static com.finapp.app.restore.RestoreRehearsalSeed.date;
import static com.finapp.app.restore.RestoreRehearsalSeed.inTransaction;

import com.finapp.app.settlement.SettlementFileUploadRequest;
import com.finapp.app.settlement.SettlementOperations;
import com.finapp.lending.CapitalContributionStatus;
import com.finapp.lending.CapitalContributionStore;
import com.finapp.lending.CapitalRecognitionStatus;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The simulated settlement bank's statements for the restore rehearsal's lending capital (`P16-TSK-024`): each the next in the EUR
 * account's chain (its sequence and opening read from the accepted statements), carrying one credit line under a contribution's
 * remittance reference, and sent through settlement intake as an operator sends one - uploaded through the upload door's service by one
 * person, attested by another, then the intake's parse and accept legs - so bank recognition matches the line to the contribution's
 * expectation and posts the capital (rule 0). The statement mode sends the contribution the main mode left awaiting, and writes the
 * exact bytes it sent: the bank's copy.
 */
final class RestoreRehearsalStatements {

    /** The one declared {@code BANK_STATEMENT} source. */
    static final String SOURCE = "simulated-bank.statement";
    /** The settlement account's reference for EUR, as {@code finapp.settlement.bank.account-reference.EUR} configures it by default. */
    static final String ACCOUNT = "SIMBANK-EUR-01";

    private RestoreRehearsalStatements() {}

    /** A statement as the bank renders it: its bytes and its place in the chain. */
    record Statement(byte[] content, long sequence, LocalDate businessDate) {}

    /** A statement sent: the file the upload landed, its status once the intake's legs ran, its sequence. */
    record Sent(UUID file, String status, long sequence) {}

    /** The next EUR statement after the accepted chain, with one credit of {@code minor} under {@code reference}, dated {@code today}. */
    static Statement compose(ConfigurableApplicationContext context, DataSource dataSource, LocalDate today, long minor,
            String reference) {
        @SuppressWarnings("unchecked")
        SettlementBatchStore<Connection> batches = context.getBean(SettlementBatchStore.class);
        Optional<SettlementBatchStore.StatementLink> last = inTransaction(dataSource, PROPOSER, batches::acceptedStatements).stream()
                .filter(link -> link.currency().equals(EUR))
                .max(Comparator.comparingLong(SettlementBatchStore.StatementLink::sequence));
        long sequence = last.map(link -> link.sequence() + 1).orElse(1L);
        long opening = last.map(SettlementBatchStore.StatementLink::closingMinor).orElse(0L);
        int scale = last.map(SettlementBatchStore.StatementLink::scale).orElse(EUR.minorUnits());
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        String text = ":20:RRSEED-STMT-" + sequence + "-" + HexFormat.of().withUpperCase().formatHex(random) + "\n"
                + ":25:" + ACCOUNT + "\n"
                + ":28C:" + sequence + "\n"
                + ":60F:" + balance(opening, scale, today) + "\n"
                + ":61:" + today + ",C," + amount(minor, scale) + "," + reference + "\n"
                + ":62F:" + balance(opening + minor, scale, today) + "\n";
        return new Statement(text.getBytes(StandardCharsets.UTF_8), sequence, today);
    }

    /** {@code statement} through settlement intake: uploaded by one operator, attested by another, parsed and accepted. */
    static Sent send(ConfigurableApplicationContext context, DataSource dataSource, Statement statement) {
        SettlementOperations operations = context.getBean(SettlementOperations.class);
        SettlementOperations.UploadAnswer uploaded = as(PROPOSER, () -> operations.upload(UUID.randomUUID().toString(),
                new SettlementFileUploadRequest(SOURCE, statement.businessDate(),
                        Base64.getEncoder().encodeToString(statement.content()))));
        if (uploaded.duplicateOf() != null) {
            throw new IllegalStateException("the statement's bytes were delivered before");
        }
        as(APPROVER, () -> operations.attest(uploaded.fileId()));
        context.getBean(FileParsing.class).sweep();
        context.getBean(BatchAcceptance.class).sweep();
        @SuppressWarnings("unchecked")
        SettlementFileStore<Connection> files = context.getBean(SettlementFileStore.class);
        UUID file = UUID.fromString(uploaded.fileId());
        String status = inTransaction(dataSource, PROPOSER, connection -> files.fileById(connection, file)
                .map(row -> row.status().name() + row.rejectionCode().map(code -> " " + code).orElse(""))
                .orElse("ABSENT"));
        return new Sent(file, status, statement.sequence());
    }

    /**
     * The statement mode: the seed's one contribution still awaiting its funds found by its reference prefix and its derived recognition,
     * its statement composed and sent, and - once accepted - the exact bytes sent written to {@code out}.
     */
    static void sendAwaitingStatement(ConfigurableApplicationContext context, Path out) {
        DataSource dataSource = context.getBean(DataSource.class);
        LocalDate today = inTransaction(dataSource, PROPOSER, connection -> date(connection));
        CapitalContributionStore store = context.getBean(CapitalContributionStore.class);
        CapitalRecognitions recognitions = context.getBean(CapitalRecognitions.class);
        List<CapitalContributionStore.ContributionRow> awaiting = inTransaction(dataSource, PROPOSER, connection -> {
            List<CapitalContributionStore.ContributionRow> approved = store.recent(connection, 100).stream()
                    .filter(row -> row.reference().startsWith(RestoreRehearsalProducts.CAPITAL_REFERENCE_PREFIX))
                    .filter(row -> row.status() == CapitalContributionStatus.APPROVED)
                    .toList();
            Map<String, CapitalRecognitions.ExpectationReading> readings = recognitions.expectations(connection,
                    approved.stream().map(row -> row.expectationRef().orElseThrow()).toList());
            return approved.stream()
                    .filter(row -> readings.get(row.expectationRef().orElseThrow()).status() == CapitalRecognitionStatus.AWAITING_FUNDS)
                    .toList();
        });
        if (awaiting.size() != 1) {
            throw new IllegalStateException("the seed leaves exactly one contribution awaiting its statement, found " + awaiting.size());
        }
        CapitalContributionStore.ContributionRow contribution = awaiting.get(0);
        Statement statement = compose(context, dataSource, today, contribution.amount().minorUnits(), contribution.reference());
        Sent sent = send(context, dataSource, statement);
        if (!"ACCEPTED".equals(sent.status())) {
            throw new IllegalStateException("the awaiting contribution's statement was not accepted: " + sent.status());
        }
        String recognised = inTransaction(dataSource, PROPOSER, connection -> {
            String expectation = contribution.expectationRef().orElseThrow();
            return recognitions.expectations(connection, List.of(expectation)).get(expectation).status().name();
        });
        try {
            Files.write(out, statement.content());
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        Map<CapitalRecognitionStatus, Integer> capital = RestoreRehearsalProducts.contributions(context, dataSource);
        System.out.println("statement: the bank statement " + sent.status() + ", sequence " + sent.sequence());
        System.out.println("statement: the contribution that awaited it " + recognised);
        System.out.println("statement: capital contributions: " + capital.getOrDefault(CapitalRecognitionStatus.RECOGNISED, 0)
                + " recognised, " + capital.getOrDefault(CapitalRecognitionStatus.AWAITING_FUNDS, 0) + " awaiting a statement");
        System.out.println("statement: the bank's copy written, " + statement.content().length + " bytes");
    }

    /** A balance as the statement states it: its mark, the date, the currency and the unsigned amount. */
    private static String balance(long minor, int scale, LocalDate date) {
        return (minor < 0 ? "D," : "C,") + date + "," + EUR.code() + "," + amount(Math.abs(minor), scale);
    }

    /** An unsigned amount at {@code scale}, as the bank writes one: {@code 1234.56}. */
    private static String amount(long minor, int scale) {
        return java.math.BigDecimal.valueOf(minor, scale).toPlainString();
    }
}
