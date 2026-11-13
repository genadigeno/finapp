package com.finapp.app.restore;

import static com.finapp.app.restore.RestoreRehearsalSeed.APPROVER;
import static com.finapp.app.restore.RestoreRehearsalSeed.PROPOSER;
import static com.finapp.app.restore.RestoreRehearsalSeed.as;

import com.finapp.app.FinappApplication;
import com.finapp.app.settlement.SettlementFileUploadRequest;
import com.finapp.app.settlement.SettlementOperations;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.ReconciliationSweep;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.FileParsing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The post-recovery gate's second step (`P16-TSK-024`; ADR-0128 decision 3, ADR-0140; {@code OPERATIONS_RUNBOOK.md} sections 11.35 and
 * 11.55): the reconciliation of the window after the restore point, <strong>with money still held</strong>. A restore loses every file a
 * provider delivered after its point; the provider still holds each, so each is delivered again, through the platform's own intake, and
 * reconciliation runs over the result. The breaks it raises are the recovery's findings, for a person to explain or escalate before the
 * release.
 *
 * <p><strong>Money stays held while it runs, by the database.</strong> The platform's three roles are still {@code NOLOGIN}, so no
 * replica can open a connection and no door, schedule, relay or consumer moves anything. This command connects as the restore's
 * administrator and acts as {@code finapp_app} for the whole session ({@code options=-c role=finapp_app} in the URL the script hands it):
 * every statement runs under the application's own grants, never the administrator's, while the role itself still cannot log in. It
 * refuses to run otherwise: a session that is not acting as {@code finapp_app}, or any of the three roles able to log in, exits {@code 2}
 * before anything is delivered.
 *
 * <p><strong>What it delivers.</strong> The provider's file on standard input (the drill keeps the simulated bank's copy, encrypted, as
 * the bank keeps its own), for the source named, with the business date its opening balance line states: uploaded by one operator and
 * attested by another through the operators' upload service, then the intake's parse and accept legs as the schedules run them, then
 * reconciliation's matching until idle and its time sweep once. A file the restore already holds is answered as a duplicate and
 * delivers nothing: it was not in the window.
 *
 * <p>Report lines: {@code HOLD|…}, {@code FILE|<status>|…}, {@code SWEEPS|…}, {@code BREAKS|<raised since the window's start>|<by type>},
 * and last {@code EXIT|<code>}. Exit {@code 0} the file is accepted or already held; {@code 1} it is neither (rejected, or left
 * unparsed); {@code 2} it could not run. Never an amount, never a line of the file: only statuses, counts and identifiers.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
public final class RestoreReconciliation {

    /** The role this command must be acting as, and the three that must still be unable to log in. */
    static final String APPLICATION_ROLE = "finapp_app";

    static final List<String> PLATFORM_ROLES = List.of("finapp_app", "finapp_migrator", "finapp_reporting");

    /** The simulated bank's statement states its business date on its opening balance line: {@code :60F:C,2026-11-13,EUR,…}. */
    static final Pattern OPENING_BALANCE = Pattern.compile("(?m)^:60F:[CD],(\\d{4}-\\d{2}-\\d{2}),");

    /** Matching runs chunk by chunk; bounded, because every pass either decides something or ends the loop. */
    static final int MATCHING_PASSES = 20;

    private RestoreReconciliation() {}

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: RestoreReconciliation <report file> <window start, an ISO instant> <source code> < <provider's file>");
            System.exit(2);
        }
        Path report = Path.of(args[0]);
        int exit;
        try {
            exit = run(report, instant(args[1]), args[2], System.in.readAllBytes());
        } catch (IOException | RuntimeException | Error failure) {
            // Classes only, never a message: a refusal's message can name a row's values.
            System.err.println("?? the reconciliation of the window could not run: " + failure.getClass().getName());
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("   caused by " + cause.getClass().getName());
            }
            exit = 2;
        }
        append(report, List.of("EXIT|" + exit));
        System.exit(exit);
    }

    static int run(Path report, OffsetDateTime windowStart, String source, byte[] content) {
        write(report, List.of());
        LocalDate businessDate = businessDate(new String(content, StandardCharsets.UTF_8));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FinappApplication.class)
                // No web server: the command opens no port, so nothing but this method reaches the context it holds.
                .properties("spring.main.banner-mode=off", "spring.main.web-application-type=none")
                .run()) {
            DataSource dataSource = context.getBean(DataSource.class);
            String hold = hold(dataSource);
            if (hold == null) {
                return 2;
            }
            List<String> lines = new ArrayList<>();
            lines.add("HOLD|" + hold);

            SettlementOperations operations = context.getBean(SettlementOperations.class);
            SettlementOperations.UploadAnswer uploaded = as(PROPOSER, () -> operations.upload(UUID.randomUUID().toString(),
                    new SettlementFileUploadRequest(source, businessDate, Base64.getEncoder().encodeToString(content))));
            if (uploaded.duplicateOf() != null) {
                lines.add("FILE|ALREADY_HELD|the restore holds this file already (" + uploaded.duplicateOf() + "): it was not in the window");
                return report(report, lines, dataSource, windowStart, 0);
            }
            as(APPROVER, () -> operations.attest(uploaded.fileId()));
            FileParsing.SweepResult parsed = context.getBean(FileParsing.class).sweep();
            BatchAcceptance.SweepResult accepted = context.getBean(BatchAcceptance.class).sweep();
            Matching matching = context.getBean(Matching.class);
            int decided = 0;
            int completedRuns = 0;
            for (int pass = 0; pass < MATCHING_PASSES; pass++) {
                Matching.SweepResult swept = matching.sweep();
                decided += swept.decided();
                completedRuns += swept.completedRuns();
                if (swept.chunks() == 0 && swept.decided() == 0) {
                    break;
                }
            }
            ReconciliationSweep.SweepResult observed = context.getBean(ReconciliationSweep.class).sweep();
            String status = operations.viewFile(uploaded.fileId()).status();
            lines.add("FILE|" + status + "|" + uploaded.fileId() + ", source " + source + ", business date " + businessDate
                    + ", uploaded by " + PROPOSER.id() + " and attested by " + APPROVER.id());
            lines.add("SWEEPS|parsed " + parsed.parsed() + ", rejected " + parsed.rejected() + "; accepted " + accepted.accepted()
                    + ", rejected " + accepted.rejected() + "; matching decided " + decided + " in " + completedRuns + " completed runs;"
                    + " aged " + observed.aged() + ", escalated " + observed.escalated() + ", blocked " + observed.blocked());
            return report(report, lines, dataSource, windowStart, "ACCEPTED".equals(status) ? 0 : 1);
        }
    }

    /**
     * The hold, read before anything is delivered: this session acting as {@code finapp_app} and not logged in as it, and none of the
     * platform's roles able to log in. The words for the report, or null when it refuses.
     */
    private static String hold(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement read = connection.prepareStatement("SELECT current_user, session_user,"
                        + " (SELECT count(*) FROM pg_roles WHERE rolname = ANY (?) AND rolcanlogin)")) {
            read.setArray(1, connection.createArrayOf("text", PLATFORM_ROLES.toArray()));
            try (ResultSet row = read.executeQuery()) {
                row.next();
                String acting = row.getString(1);
                String session = row.getString(2);
                long open = row.getLong(3);
                if (!APPLICATION_ROLE.equals(acting) || APPLICATION_ROLE.equals(session)) {
                    System.err.println("?? the session is not the administrator acting as " + APPLICATION_ROLE + ": it refuses to run");
                    return null;
                }
                if (open != 0) {
                    System.err.println("?? " + open + " of the platform's roles can log in: money is not held, and it refuses to run");
                    return null;
                }
                return "acting as " + acting + ", logged in as the restore's administrator; " + PLATFORM_ROLES + " unable to log in";
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("reading the hold failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    /** The breaks raised since the window's start, by type, then the report written. */
    private static int report(Path report, List<String> lines, DataSource dataSource, OffsetDateTime windowStart, int exit) {
        Map<String, Long> raised = new TreeMap<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement read = connection.prepareStatement(
                        "SELECT type, count(*) FROM reconciliation.break WHERE raised_at >= ? GROUP BY type")) {
            read.setObject(1, windowStart);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    raised.put(rows.getString(1), rows.getLong(2));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("reading the breaks failed (SQLState " + failure.getSQLState() + ")");
        }
        lines.add("BREAKS|" + raised.values().stream().mapToLong(Long::longValue).sum() + "|" + raised);
        lines.forEach(System.out::println);
        write(report, lines);
        return exit;
    }

    /** psql's text for a {@code timestamptz}, as the script reads the restore point and the backup's instant: {@code 2026-11-13 10:15:30.123456+00}. */
    static final DateTimeFormatter PSQL_INSTANT = new DateTimeFormatterBuilder().appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).appendOffset("+HH:mm", "+00").toFormatter();

    /** The window's start, as psql prints it or in ISO-8601; anything else refuses to run. */
    static OffsetDateTime instant(String text) {
        try {
            return OffsetDateTime.parse(text, PSQL_INSTANT);
        } catch (DateTimeParseException notPsql) {
            return OffsetDateTime.parse(text);
        }
    }

    /** The business date the file's opening balance states; a file that states none is refused before anything is delivered. */
    static LocalDate businessDate(String content) {
        Matcher opening = OPENING_BALANCE.matcher(content);
        if (!opening.find()) {
            throw new IllegalArgumentException("the file states no opening balance line, so no business date");
        }
        return LocalDate.parse(opening.group(1));
    }

    // ------------------------------------------------------------------ plumbing

    /** LF, whatever the platform: the script reads the lines with POSIX tools. */
    private static void write(Path report, List<String> lines) {
        try {
            Files.writeString(report, joined(lines), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void append(Path report, List<String> lines) {
        try {
            Files.writeString(report, joined(lines), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            System.err.println("?? the report could not be written: " + failure.getClass().getName());
        }
    }

    private static String joined(List<String> lines) {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append('\n'));
        return text.toString();
    }
}
