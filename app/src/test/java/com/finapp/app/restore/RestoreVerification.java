package com.finapp.app.restore;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.GlEqualsLedger;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.SourceCensus;
import com.finapp.accounting.SourceCensusStore;
import com.finapp.accounting.SourceFindingKind;
import com.finapp.accounting.SourceJoinCatalogue;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlBalances;
import com.finapp.accounting.reporting.PinnedMapping;
import com.finapp.app.FinappApplication;
import com.finapp.app.accounting.GlSnapshots;
import com.finapp.app.accounting.ProductSourceProofs;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.app.security.KeyRingRetirementGuard;
import com.finapp.app.restore.RestoreVerdicts.PeriodJudgement;
import com.finapp.app.restore.RestoreVerdicts.SourceReading;
import com.finapp.app.restore.RestoreVerdicts.Verdict;
import com.finapp.bnpl.BnplProofs;
import com.finapp.fx.FxBooksProof;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.security.KeyRing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The restore verification (`P15-TSK-030`; {@code OPERATIONS_RUNBOOK.md} section 11.35): the platform's own financial checks run against
 * one database, as the application wires them, and a report of one verdict per check. Run by {@code infra/scripts/restore-rehearsal.sh}
 * on the source before the backup and on every restore before any role the platform runs as may log in to it.
 *
 * <p><strong>The checks are the platform's, never restated here</strong>: the ledger's {@link TrialBalance}; GL = ledger for every
 * accounting period, {@link GeneralLedger#balances} under the version {@link GlPins#forPeriod} reads (a locked period's pin) judged by
 * {@link GlEqualsLedger}; the projection check and every product proof through the wired {@link SourceProofs}
 * ({@code ProductSourceProofs} over the nine proofs, {@code ProjectionVerification} among them), one call per
 * {@link SourceProofs.Source}; the source-join census, {@link SourceCensus}'s own walk and judgement over the whole journal from its
 * floor; and (`P16-TSK-024`, ADR-0128 decision 5) the evidence's key versions, every column the startup guard lists
 * ({@code KeyRingRetirementGuard.STAMPED_COLUMNS}) read for the versions its rows stand under, against the versions each ring of this
 * context holds, so a restore whose key store lost a version is refused before anyone reads evidence that cannot be decrypted.
 * {@link RestoreVerdicts} only decides whether each answer is a pass. {@code flywayValidate}, the row counts and the catalogue are the
 * script's, read against the backup's manifest.
 *
 * <p><strong>A drill's positive control</strong> ({@value #DRILL}, `P16-TSK-024`; {@code THREAT_MODEL.md} §6 item 44 (a)): a source built
 * to hold every product is also asked that each proof judged at least one subject, so a proof that passed over nothing fails the drill.
 * A restore of a real database is never asked it.
 *
 * <p><strong>It cannot write.</strong> The application context is the platform's own ({@link FinappApplication}, the test classpath's
 * {@code application.properties} turning every worker off), and the script hands it a database URL whose sessions default to
 * {@code default_transaction_read_only}: a component that started anyway could change nothing. That is checked before any check runs
 * ({@link #READ_ONLY}). Every read is one {@link GlSnapshots} snapshot, {@code REPEATABLE READ READ ONLY}, always rolled back. The census
 * walks with a head of its own in memory, so the restored database's census head is never moved by the verification.
 *
 * <p>It connects as the identity the script names in {@code FINAPP_DB_APP_USER}: the restore's administrator, because the platform's
 * roles cannot log in to a restored database until this has passed.
 *
 * <p>Exit {@code 0} every check passed, {@code 1} one failed, {@code 2} it could not run. After the verdicts, one {@code SUBJECTS|<proof>|…}
 * line per proof says how many subjects it judged: a description, which decides something only in a drill, through its own verdict. The
 * report's last line is {@code EXIT|<code>}, so a report cut short is seen to be.
 */
public final class RestoreVerification {

    /** Every statement of a read is bounded; generous, because a real journal is larger than the rehearsal's. */
    static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(10);

    /** The session default the script's URL must carry. */
    static final String READ_ONLY = "SELECT current_setting('default_transaction_read_only')";

    /** The census's floor, as accounting {@code V015} seeds its head. */
    static final JournalCensusWalk.Position FLOOR = new JournalCensusWalk.Position(0, UUID.fromString("00000000-0000-7000-8000-000000000000"));

    private RestoreVerification() {}

    /** The argument that asks a drill's checks ({@link RestoreVerdicts#DRILL_CHECKS}): its source was built to hold every product. */
    static final String DRILL = "--drill";

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !DRILL.equals(args[1]))) {
            System.err.println("usage: RestoreVerification <report file> [" + DRILL + "]");
            System.exit(2);
        }
        Path report = Path.of(args[0]);
        int exit;
        try {
            exit = run(report, args.length == 2);
        } catch (RuntimeException | Error failure) {
            // Classes and frames, never a message: a refusal's message can name a row's values.
            System.err.println("?? the verification could not run: " + failure.getClass().getName());
            for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
                System.err.println("   caused by " + cause.getClass().getName());
            }
            exit = 2;
        }
        append(report, List.of("EXIT|" + exit));
        System.exit(exit);
    }

    static int run(Path report, boolean drill) {
        List<String> catalogue = drill ? RestoreVerdicts.DRILL_CHECKS : RestoreVerdicts.CHECKS;
        write(report, List.of());
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(FinappApplication.class)
                // No web server: the command opens no port, so nothing can reach the context it holds as the administrator.
                .properties("spring.main.banner-mode=off", "spring.main.web-application-type=none")
                .run()) {
            DataSource dataSource = context.getBean(DataSource.class);
            if (!"on".equals(scalar(dataSource, READ_ONLY))) {
                System.err.println("?? the verification's sessions are not read-only by default: it refuses to run");
                return 2;
            }
            GlSnapshots snapshots = new GlSnapshots(dataSource::getConnection, STATEMENT_TIMEOUT);
            long journalEntries = snapshots.read(connection -> count(connection, "SELECT count(*) FROM ledger.journal_entry"));
            List<Verdict> verdicts = new ArrayList<>();
            verdicts.add(guarded(RestoreVerdicts.TRIAL_BALANCE, () -> RestoreVerdicts.trialBalance(snapshots.read(new TrialBalance()::sweep))));
            verdicts.add(guarded(RestoreVerdicts.GL_EQUALS_LEDGER, () -> RestoreVerdicts.glEqualsLedger(periods(context, snapshots), journalEntries)));
            List<SourceReading> proofs = proofs(context.getBean(SourceProofs.class), snapshots);
            verdicts.add(guarded(RestoreVerdicts.PROJECTION, () -> RestoreVerdicts.proofs(RestoreVerdicts.PROJECTION, RestoreVerdicts.projectionProofs(), proofs)));
            verdicts.add(guarded(RestoreVerdicts.PRODUCT_PROOFS, () -> RestoreVerdicts.proofs(RestoreVerdicts.PRODUCT_PROOFS, RestoreVerdicts.productProofs(), proofs)));
            verdicts.add(guarded(RestoreVerdicts.SOURCE_CENSUS, () -> census(context, snapshots, journalEntries)));
            verdicts.add(guarded(RestoreVerdicts.EVIDENCE_KEYS, () -> RestoreVerdicts.evidenceHeld(rings(context, snapshots))));
            Map<SourceProofs.Proof, Subjects> subjects = subjects(context, snapshots);
            if (drill) {
                Map<SourceProofs.Proof, OptionalLong> counts = new EnumMap<>(SourceProofs.Proof.class);
                subjects.forEach((proof, judged) -> counts.put(proof, judged.count()));
                verdicts.add(guarded(RestoreVerdicts.SUBJECTS, () -> RestoreVerdicts.subjects(counts)));
            }
            if (!verdicts.stream().map(Verdict::check).toList().equals(catalogue)) {
                System.err.println("?? the verdicts are not the catalogue's checks: it refuses to report them");
                return 2;
            }
            List<String> lines = new ArrayList<>();
            lines.add("CHECKS|" + catalogue.size());
            verdicts.stream().map(Verdict::line).forEach(lines::add);
            subjects.forEach((proof, judged) -> lines.add("SUBJECTS|" + proof.name() + "|" + judged.description()));
            lines.forEach(System.out::println);
            write(report, lines);
            return verdicts.stream().allMatch(Verdict::passed) ? 0 : 1;
        }
    }

    /** A check that throws fails; it never stops the others. */
    private static Verdict guarded(String check, java.util.function.Supplier<Verdict> judge) {
        try {
            return judge.get();
        } catch (RuntimeException failure) {
            return RestoreVerdicts.unreadable(check, failure);
        }
    }

    // ------------------------------------------------------------------ GL = ledger, every period

    private static List<PeriodJudgement> periods(ConfigurableApplicationContext context, GlSnapshots snapshots) {
        AccountingPeriodStore periods = context.getBean(AccountingPeriodStore.class);
        GlPins pins = context.getBean(GlPins.class);
        GeneralLedger ledger = context.getBean(GeneralLedger.class);
        GlEqualsLedger judge = new GlEqualsLedger();
        List<AccountingPeriodStore.PeriodRow> rows = snapshots.read(connection -> periods.recent(connection, Integer.MAX_VALUE));
        List<PeriodJudgement> judged = new ArrayList<>();
        for (AccountingPeriodStore.PeriodRow row : rows) {
            try {
                judged.add(snapshots.read(connection -> {
                    PinnedMapping mapping = pins.forPeriod(connection, row.month());
                    GlBalances balances = ledger.balances(connection, mapping, Optional.empty());
                    List<String> findings = judge.judge(balances).stream().map(BreakFinding::subject).toList();
                    return new PeriodJudgement(row.month(), row.status(), Optional.of(mapping.version()), Optional.empty(), findings);
                }));
            } catch (RuntimeException unreadable) {
                judged.add(new PeriodJudgement(row.month(), row.status(), Optional.empty(), Optional.of(unreadable.getClass().getName()),
                        List.of()));
            }
        }
        return judged;
    }

    // ------------------------------------------------------------------ the proofs

    private static List<SourceReading> proofs(SourceProofs proofs, GlSnapshots snapshots) {
        List<SourceReading> readings = new ArrayList<>();
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            try {
                readings.add(new SourceReading(source, snapshots.read(connection -> proofs.read(connection, source)), Optional.empty()));
            } catch (RuntimeException unreadable) {
                readings.add(new SourceReading(source, List.of(), Optional.of(unreadable.getClass().getName())));
            }
        }
        return readings;
    }

    /**
     * How many subjects each proof judged, which a verdict does not say: a proof complete with no discrepancy over no rows passes as
     * surely as one over thousands. Each product proof's own report, read once more on a snapshot of the same held database (nothing can
     * write to it), counted and never judged here; the projection's are the accounts its census covers, those with lines or a projection
     * row. Description only: a count that cannot be read is reported as such and decides nothing.
     */
    private static Map<SourceProofs.Proof, Subjects> subjects(ConfigurableApplicationContext context, GlSnapshots snapshots) {
        Map<SourceProofs.Proof, Subjects> counted = new EnumMap<>(SourceProofs.Proof.class);
        for (SourceProofs.Proof proof : SourceProofs.Proof.values()) {
            counted.put(proof, Subjects.UNREAD);
        }
        try {
            snapshots.read(connection -> {
                LendingProofs.Reading lending = context.getBean(LendingProofs.class).prove(connection);
                counted.put(SourceProofs.Proof.LOAN_SUBLEDGER, Subjects.of(lending.accounts().size(), "loan accounts"));
                counted.put(SourceProofs.Proof.LOAN_REPLAY, Subjects.of(lending.accounts().size(), "loan accounts"));
                counted.put(SourceProofs.Proof.LENDING_CAPITAL,
                        Subjects.of(context.getBean(LendingCapitalProof.class).prove(connection).currencies().size(), "currencies"));
                BnplProofs.Reading bnpl = context.getBean(BnplProofs.class).prove(connection);
                counted.put(SourceProofs.Proof.BNPL_SUBLEDGER, Subjects.of(bnpl.obligations().size(), "obligations"));
                counted.put(SourceProofs.Proof.BNPL_THREE_WAY, Subjects.of(bnpl.currencies().size(), "currencies"));
                counted.put(SourceProofs.Proof.FX_BOOKS,
                        Subjects.of(context.getBean(FxBooksProof.class).prove(connection).lines().size(), "book lines"));
                PositionProof.Report position = context.getBean(PositionProof.class).sweep(connection);
                counted.put(SourceProofs.Proof.POSITION, new Subjects(OptionalLong.of(position.verdicts().size()),
                        position.verdicts().size() + " positions, " + position.suspenseVerdicts().size() + " suspense and "
                                + position.cashVerdicts().size() + " cash verdicts"));
                counted.put(SourceProofs.Proof.MERCHANT_PAYABLE, Subjects.of(ProductSourceProofs.merchantPayables(
                        new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown())).apply(connection).size(),
                        "merchant payables"));
                counted.put(SourceProofs.Proof.PROJECTION, Subjects.of(count(connection, "SELECT count(*) FROM (SELECT ledger_account_id"
                        + " FROM ledger.journal_line UNION SELECT ledger_account_id FROM ledger.account_balance) accounts"), "ledger accounts"));
                return null;
            });
        } catch (RuntimeException unreadable) {
            // Every count stays unread, so a drill's positive control fails rather than passing over what it could not count.
            System.err.println("   the subjects could not all be counted: " + unreadable.getClass().getName());
            counted.replaceAll((proof, judged) -> Subjects.UNREAD);
        }
        return counted;
    }

    /** What one proof judged: its count, empty when unread, and the words the report prints. */
    record Subjects(OptionalLong count, String description) {

        static final Subjects UNREAD = new Subjects(OptionalLong.empty(), "unread");

        static Subjects of(long count, String unit) {
            return new Subjects(OptionalLong.of(count), count + " " + unit);
        }
    }

    // ------------------------------------------------------------------ the key rings

    /**
     * Every column a key version is stamped in, read on the snapshot as the restore's administrator: the rows under each version, against
     * the versions the ring this context was started with holds (ADR-0128 decision 5). The columns are the startup guard's own list
     * ({@code KeyRingRetirementGuard.STAMPED_COLUMNS}, held to every migration's {@code key_version} column and to the ring beans by its
     * test), so a column added later is read here without an edit. Credit's, which the application's role cannot read, is read here: this
     * runs as the administrator. A version column left null (a row with nothing encrypted) is not counted.
     */
    private static List<RestoreVerdicts.RingReading> rings(ConfigurableApplicationContext context, GlSnapshots snapshots) {
        Map<String, KeyRing> held = context.getBeansOfType(KeyRing.class);
        List<RestoreVerdicts.RingReading> readings = new ArrayList<>();
        new TreeMap<>(KeyRingRetirementGuard.STAMPED_COLUMNS).forEach((ringName, columns) -> {
            KeyRing ring = held.get(ringName);
            for (String column : columns) {
                if (ring == null) {
                    readings.add(RestoreVerdicts.RingReading.unreadable(ringName, column, "no ring of that name in the context"));
                    continue;
                }
                try {
                    Map<Integer, Long> versions = snapshots.read(connection -> versions(connection, column));
                    readings.add(new RestoreVerdicts.RingReading(ringName, column, versions, ring.heldVersions(), Optional.empty()));
                } catch (RuntimeException unreadable) {
                    readings.add(RestoreVerdicts.RingReading.unreadable(ringName, column, unreadable.getClass().getName()));
                }
            }
        });
        return readings;
    }

    /** {@code schema.table.column}'s rows by the version stamped in it. The name is the guard's constant, never input. */
    private static Map<Integer, Long> versions(Connection connection, String qualifiedColumn) {
        int columnStart = qualifiedColumn.lastIndexOf('.');
        String table = qualifiedColumn.substring(0, columnStart);
        String column = qualifiedColumn.substring(columnStart + 1);
        try (PreparedStatement read = connection.prepareStatement("SELECT " + column + ", count(*) FROM " + table + " WHERE " + column
                + " IS NOT NULL GROUP BY 1"); ResultSet rows = read.executeQuery()) {
            Map<Integer, Long> versions = new HashMap<>();
            while (rows.next()) {
                versions.put(rows.getInt(1), rows.getLong(2));
            }
            return versions;
        } catch (SQLException failure) {
            throw new IllegalStateException("reading a key version column failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    // ------------------------------------------------------------------ the census

    private static Verdict census(ConfigurableApplicationContext context, GlSnapshots snapshots, long journalEntries) {
        HeadInMemory head = new HeadInMemory();
        SourceCensus census = new SourceCensus(new SourceCensus.Units() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return snapshots.read(work);
            }
        }, head, context.getBean(JournalCensusWalk.class), context.getBean(SourceJoinCatalogue.class), JournalCensusWalk.MAX_LIMIT,
                STATEMENT_TIMEOUT);
        // Bounded: every batch either judges at least one entry or ends the walk, so the journal's size bounds the batches.
        long bound = journalEntries / JournalCensusWalk.MAX_LIMIT + 2;
        for (long batch = 0; batch < bound; batch++) {
            if (census.sweepBatch().judged() == 0) {
                break;
            }
        }
        Set<UUID> recorded = snapshots.read(connection -> ids(connection, "SELECT entry_id FROM accounting.source_census_finding"));
        return RestoreVerdicts.census(head.judged, journalEntries, head.found, recorded);
    }

    /**
     * The census's position kept in this process, from the floor: {@link SourceCensus} judges with its own walk, catalogue and comparison,
     * and nothing it decides is written to the database under verification.
     */
    static final class HeadInMemory implements SourceCensusStore {

        private JournalCensusWalk.Position position = FLOOR;
        private long judged;
        private final Map<UUID, SourceFindingKind> found = new HashMap<>();

        @Override
        public Optional<Head> claimHead(Connection connection) {
            return Optional.of(new Head(position, judged, false));
        }

        @Override
        public void insertFindings(Connection connection, List<Finding> findings) {
            findings.forEach(finding -> found.putIfAbsent(finding.entryId(), finding.kind()));
        }

        @Override
        public void advance(Connection connection, JournalCensusWalk.Position to, long entriesJudged) {
            position = to;
            judged += entriesJudged;
        }

        @Override
        public void touch(Connection connection) {
            // Nothing to stamp: this head is not the database's.
        }

        @Override
        public void markFailure(Connection connection, String failureClass) {
            // The failure is the verdict's (the batch throws); nothing is stamped in the database under verification.
        }

        @Override
        public Reading read(Connection connection, Duration standing, Duration recent, Duration staleAfter) {
            throw new UnsupportedOperationException("the verification's head is never read as a gauge");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static String scalar(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement read = connection.prepareStatement(sql);
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("reading the session's settings failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    private static long count(Connection connection, String sql) {
        try (PreparedStatement read = connection.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("counting failed (SQLState " + failure.getSQLState() + ")");
        }
    }

    private static Set<UUID> ids(Connection connection, String sql) {
        try (PreparedStatement read = connection.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            Set<UUID> ids = new HashSet<>();
            while (row.next()) {
                ids.add(row.getObject(1, UUID.class));
            }
            return ids;
        } catch (SQLException failure) {
            throw new IllegalStateException("reading ids failed (SQLState " + failure.getSQLState() + ")");
        }
    }

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
            Files.writeString(report, joined(lines), StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
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
