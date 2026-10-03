package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountNotPostableException;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The break and resolution battery's matrix (`P8-TST-002`; ADR-0069 §2's per-type table, its code
 * twin {@link ResolutionTemplates}, ADR-0071; {@code INV-REC-01}, {@code -02}, {@code -03},
 * {@code -05}, {@code -07}, {@code -08}, {@code -09}, {@code INV-AUD-04}, {@code INV-REV-04}):
 * every break type crossed with every person kind its row admits, the complement refused with
 * NOTHING written, raced and duplicated - through {@link ResolutionMachine} against the real
 * schema and the real ledger.
 *
 * <p><strong>The matrix is derived, then pinned.</strong> The cells are read at runtime from
 * {@link ResolutionTemplates#admittedKinds} for each type's ordinary cause (so the battery grows
 * with the table) and the counts are asserted - 40 (type, kind) pairs; 44 table cells refused
 * over the six template kinds, and {@code REPUDIATE_BATCH}'s 14 refused by the shape screen (a
 * batch's kind, never a break's) - so neither can shrink silently ({@code ResolutionTemplatesTest}
 * pins the table itself against a hand transcription). A pair is split into one scenario per
 * subject shape its lines apply to - an INBOUND or OUTBOUND expectation remainder, a CREDIT or
 * DEBIT parked item, or nothing in a position (a fee's deviation, a decision's subject) - and
 * over every shape a type admits each kind is either crossed or refused by the direction rules,
 * never neither. Each cell's
 * lines are written down independently of the template and checked against
 * {@code ledger.adjustment_proposal_line} and {@code ledger.journal_line} exactly; the closing
 * effects (expectation, item, suspense release, break, event) are counted; and the four-eyes
 * refusal at the domain is asserted in every two-person cell.
 *
 * <p>Beside the matrix: the cause refinements (an explained duplicate never transferred, the
 * statement causes closing only by evidence, a diverged replay acknowledged four-eyes - the
 * battery's one production correction, `V014`); the direction rules; the threshold edges (a
 * zero-value timing acknowledgement one person's, one unit two people's; severity escalated at
 * exactly the pinned {@code high_value_minor} and not one unit below; {@code RECOGNISE_GAIN} at
 * the pinned 90 days and refused at 89, both read from the seeded rule set); write-off then
 * recovery; a correction offsetting a parked excess {@code EVIDENCED} over a pending proposal;
 * atomicity under a not-postable target; four-eyes at its three ranks; the races - ten proposers,
 * ten approvers, one approver retrying, approve against reject and against withdraw, evidence
 * against approval on parked value both ways and truly concurrent, an allocation between proposal
 * and approval, a suspense item an offset and the rematch can each release - each leaving ONE
 * effect; reclassification unable to escape a cause refinement or the one-person rule;
 * {@code PROCESSING_ERROR}'s own path (a blocked run requeued, an errored item reprocessed, each
 * closing its break {@code EVIDENCED}); the losses and gains positions posted only by approved
 * write-offs and gains (scoped to this suite's entries); and the schema-wide immutability scan:
 * every reconciliation table, read from the catalogue by the owner, undeletable by privilege for
 * the application role and by trigger for every writer, every column-narrowed table refusing its
 * frozen columns. The privilege floor covers the application role's {@code TRUNCATE}; the
 * owner's {@code TRUNCATE} fires no row trigger and is a known limit, not proven here.
 *
 * <p>The position, suspense and cash proofs ({@code PositionProof}) are the app's, not this
 * module's: they are demonstrated over real value by {@code ResolutionBatteryDatabaseTest}'s
 * crossings, which assert them absolutely after each one. This suite's subjects are fixtures
 * (items parked without a statement, expectations naming no completion entry) whose proofs it
 * does not claim.
 *
 * <p>The suite owns one private source whose rule set copies the seeded v1's pinned
 * {@code gain_min_age_days} and {@code high_value_minor} - read at seeding, asserted 90 and
 * 1,000.00 - and parks the matrix's items dated that many days before the DATABASE's own date, so
 * every gain cell is eligible on the clock the rule is judged on.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the break and resolution battery: the matrix (P8-TST-002)")
class ResolutionMatrixDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final Actor PROPOSER = new Actor("op-matrix-a", ActorType.EMPLOYEE);
    private static final Actor APPROVER = new Actor("op-matrix-b", ActorType.EMPLOYEE);
    private static final Actor THIRD = new Actor("op-matrix-c", ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final LocalDate FAR = LocalDate.parse("2027-12-31");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000 + 100_000);
    private static final AtomicLong AMOUNTS = new AtomicLong(301_00);

    /** The PSP's seeded rule set v1 (V002): the pinned terms this suite copies. */
    private static final UUID SEEDED_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");

    private static final UUID SOURCE = IDS.next();
    private static final UUID RULE_SET = IDS.next();
    /** A second private source whose runs a controller requeues and reprocesses. */
    private static final UUID OPERATED_SOURCE = IDS.next();
    private static final UUID OPERATED_RULE_SET = IDS.next();
    /** The P&L entries standing before this suite: the scan reads only the suite's own. */
    private static Set<String> lossAndGainEntriesBefore;

    /** The kinds a person may name - every kind but the platform's EVIDENCED. */
    private static final Set<ResolutionKind> PERSON_KINDS =
            EnumSet.complementOf(EnumSet.of(ResolutionKind.EVIDENCED));

    /** The causes that refine their type's row (ADR-0069 §§2, 9; P8-TSK-020, P8-TSK-022). */
    private static final Set<BreakCause> REFINEMENTS =
            EnumSet.of(BreakCause.EXECUTION_ALREADY_EXPLAINED, BreakCause.STATEMENT_GAP,
                    BreakCause.OPENING_BALANCE, BreakCause.REPLAY_DIVERGED);

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static JdbcBreakRegister register;
    private static JdbcMatchingStore matchingStore;
    private static Suspense suspense;
    private static ResolutionMachine machine;
    private static LocalDate today;
    private static int gainMinAgeDays;
    private static long highValueMinor;
    private static LocalDate agedOn;
    private static UUID clearing;
    private static UUID suspenseAccount;
    private static UUID losses;
    private static UUID gains;
    private static UUID wallet;
    /** A DEBIT item owned by an open break, named by complement offsets that never get far. */
    private static UUID sparePartnerItem;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        register = new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        matchingStore = new JdbcMatchingStore();
        suspense = new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        machine =
                new ResolutionMachine(
                        new JdbcResolutionStore(),
                        new JdbcBreakCaseStore(),
                        suspense,
                        matchingStore,
                        new JdbcRuleSets(),
                        ResolutionFixtures.adjustments(IDS, CLOCK),
                        new JdbcLedgerAccountStore(),
                        new JdbcOutboxWriter(),
                        new JdbcAuditWriter(),
                        IDS,
                        CLOCK,
                        ReconciliationTelemetry.NONE,
                        (unitOfWork, subject) ->
                                InternalReferenceLookup.InternalReference.unknown(),
                        ReturnedPayouts.NONE);
        today = (LocalDate) one("SELECT current_date");
        gainMinAgeDays = ((Number) one("SELECT gain_min_age_days FROM reconciliation.rule_set"
                + " WHERE id = ?", SEEDED_RULE_SET)).intValue();
        highValueMinor = ((Number) one("SELECT high_value_minor FROM"
                + " reconciliation.severity_threshold WHERE rule_set_id = ? AND currency ="
                + " 'EUR'", SEEDED_RULE_SET)).longValue();
        agedOn = today.minusDays(gainMinAgeDays);
        seedRuleSet(SOURCE, RULE_SET);
        seedRuleSet(OPERATED_SOURCE, OPERATED_RULE_SET);
        lossAndGainEntriesBefore = new java.util.HashSet<>(rows("SELECT DISTINCT"
                + " l.entry_id::text FROM ledger.journal_line l JOIN ledger.ledger_account a ON"
                + " a.id = l.ledger_account_id WHERE a.purpose IN ('RECONCILIATION_LOSSES',"
                + " 'RECONCILIATION_GAINS')"));
        clearing = operational(AccountPurpose.SETTLEMENT_CLEARING);
        suspenseAccount = operational(AccountPurpose.SUSPENSE_UNMATCHED);
        losses = operational(AccountPurpose.RECONCILIATION_LOSSES);
        gains = operational(AccountPurpose.RECONCILIATION_GAINS);
        wallet = openWallet();
        sparePartnerItem = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.DEBIT_ITEM, 9_99, agedOn).suspenseItemId().orElseThrow();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ================================================================= the matrix

    /** What a break's subject holds - the side a kind's lines need. */
    enum Shape {
        INBOUND_REMAINDER,
        OUTBOUND_REMAINDER,
        CREDIT_ITEM,
        DEBIT_ITEM,
        NOTHING
    }

    /** One counted scenario: a (type, cause) crossed with a kind over one subject shape. */
    record Cell(BreakType type, BreakCause cause, ResolutionKind kind, Shape shape) {
        @Override
        public String toString() {
            return type + "(" + cause + ") x " + kind + " over " + shape;
        }
    }

    /** The shapes a type's subject can take (ADR-0069 §2's Subject and Parked columns). */
    private static List<Shape> shapesOf(BreakType type, BreakCause cause) {
        if (cause == BreakCause.REPLAY_DIVERGED) {
            return List.of(Shape.NOTHING);
        }
        return switch (type) {
            case MISSING_EXTERNAL, DUPLICATE_INTERNAL ->
                    List.of(Shape.INBOUND_REMAINDER, Shape.OUTBOUND_REMAINDER);
            case AMOUNT_MISMATCH, SETTLEMENT_MISMATCH ->
                    List.of(Shape.INBOUND_REMAINDER, Shape.OUTBOUND_REMAINDER, Shape.CREDIT_ITEM,
                            Shape.DEBIT_ITEM);
            case FEE_MISMATCH, TIMING_DIFFERENCE -> List.of(Shape.NOTHING);
            default -> List.of(Shape.CREDIT_ITEM, Shape.DEBIT_ITEM);
        };
    }

    /**
     * Where a kind's lines apply (ADR-0071 §2) - written down here, apart from the template's
     * side rule, so the two are compared rather than one copied: a write-off of an INBOUND
     * remainder or a DEBIT item; a transfer of an OUTBOUND remainder or a CREDIT item; a gain of
     * a CREDIT item; an offset or a manual match of a parked item of either side; an
     * acknowledgement of anything (it disposes of nothing in a position). Over every shape a type
     * admits, each kind either applies or is refused by the direction rules - never neither
     * ({@link #cellsOf} asserts it).
     */
    private static boolean applies(ResolutionKind kind, Shape shape) {
        return switch (kind) {
            case WRITE_OFF -> shape == Shape.INBOUND_REMAINDER || shape == Shape.DEBIT_ITEM;
            case TRANSFER_TO_ACCOUNT ->
                    shape == Shape.OUTBOUND_REMAINDER || shape == Shape.CREDIT_ITEM;
            case RECOGNISE_GAIN -> shape == Shape.CREDIT_ITEM;
            case OFFSET_SUSPENSE, MANUAL_MATCH ->
                    shape == Shape.CREDIT_ITEM || shape == Shape.DEBIT_ITEM;
            case ACKNOWLEDGE -> true;
            default -> false;
        };
    }

    /** The direction rules: a kind the type admits, refused for its subject's side. */
    private static boolean sideRefused(ResolutionKind kind, Shape shape) {
        boolean remainder =
                shape == Shape.INBOUND_REMAINDER || shape == Shape.OUTBOUND_REMAINDER;
        return switch (kind) {
            case WRITE_OFF -> shape == Shape.OUTBOUND_REMAINDER || shape == Shape.CREDIT_ITEM;
            case TRANSFER_TO_ACCOUNT ->
                    shape == Shape.INBOUND_REMAINDER || shape == Shape.DEBIT_ITEM;
            case RECOGNISE_GAIN -> remainder || shape == Shape.DEBIT_ITEM;
            case OFFSET_SUSPENSE, MANUAL_MATCH -> remainder;
            default -> false;
        };
    }

    /** The first cause, in the enum's order, that raises {@code type} and refines nothing. */
    private static BreakCause ordinaryCause(BreakType type) {
        for (BreakCause cause : BreakCause.values()) {
            if (cause.raisesAs().contains(type) && !REFINEMENTS.contains(cause)) {
                return cause;
            }
        }
        throw new AssertionError("no ordinary detector raises " + type);
    }

    private static List<Cell> cellsOf(BreakType type, BreakCause cause) {
        List<Cell> cells = new ArrayList<>();
        for (ResolutionKind kind : ResolutionTemplates.admittedKinds(type, cause)) {
            for (Shape shape : shapesOf(type, cause)) {
                assertThat(applies(kind, shape) ^ sideRefused(kind, shape))
                        .as("%s(%s) x %s over %s is either crossed or refused by the direction"
                                + " rules, never neither or both", type, cause, kind, shape)
                        .isTrue();
                if (applies(kind, shape)) {
                    cells.add(new Cell(type, cause, kind, shape));
                }
            }
        }
        return cells;
    }

    // Counted as the matrix runs, asserted at its end.
    private static int crossings;
    private static int complementRefusals;
    private static int repudiationShapeRefusals;
    private static int refinedRefusals;
    private static int sideRefusals;
    private static int reasonRefusals;
    private static int selfApprovalRefusals;

    @Test
    @Order(1)
    @DisplayName("every (type, kind) pair the table admits, crossed over every subject shape its"
            + " lines apply to - lines exact against the ledger, effects counted, the proposer's"
            + " approval refused - and before each type's first crossing, the 44 table cells over"
            + " the six template kinds and REPUDIATE_BATCH's 14 shape refusals, each refused with"
            + " nothing written; derived from ResolutionTemplates, its counts pinned")
    void everyAdmittedCrossingAndTheComplement() throws Exception {
        int pairs = 0;
        int baseCells = 0;
        Map<BreakType, List<Cell>> byType = new LinkedHashMap<>();
        for (BreakType type : BreakType.values()) {
            BreakCause cause = ordinaryCause(type);
            pairs += ResolutionTemplates.admittedKinds(type, cause).size();
            List<Cell> cells = cellsOf(type, cause);
            byType.put(type, cells);
            baseCells += cells.size();
            for (ResolutionKind kind : ResolutionTemplates.admittedKinds(type, cause)) {
                assertThat(cells).as("every admitted pair has a scenario: %s x %s", type, kind)
                        .anyMatch(cell -> cell.kind() == kind);
            }
        }
        assertThat(pairs).as("ADR-0069 section 2's 40 (type, kind) pairs, derived").isEqualTo(40);
        assertThat((PERSON_KINDS.size() - 1) * BreakType.values().length - pairs)
                .as("the table cells over the six template kinds").isEqualTo(44);
        assertThat(baseCells).as("the pairs split by subject shape").isEqualTo(52);

        for (Map.Entry<BreakType, List<Cell>> row : byType.entrySet()) {
            boolean complementDone = false;
            for (Cell cell : row.getValue()) {
                Subject subject = seed(cell);
                if (!complementDone) {
                    complement(row.getKey(), ordinaryCause(row.getKey()), subject);
                    complementDone = true;
                }
                cross(cell, subject);
            }
        }
        assertThat(complementRefusals)
                .as("every table cell a type does not admit refused ResolutionKindNotAllowed,"
                        + " nothing written")
                .isEqualTo(44);
        assertThat(repudiationShapeRefusals)
                .as("REPUDIATE_BATCH refused by the shape screen on every type - a batch's kind,"
                        + " never a break's")
                .isEqualTo(14);
        assertThat(crossings).isEqualTo(52);
        assertThat(reasonRefusals)
                .as("a code outside each kind's subset, per crossing (a shape refusal)")
                .isEqualTo(52);
        assertThat(selfApprovalRefusals)
                .as("every crossing but the timing difference's one-person act refused its"
                        + " proposer (domain rank)")
                .isEqualTo(51);
        assertThat(sideRefusals).as("the direction rules, on every crossing's own subject")
                .isPositive();
    }

    @Test
    @Order(2)
    @DisplayName("the cause refinements: an explained duplicate is never transferred, a statement"
            + " cause admits no person kind at all, and a diverged replay admits ACKNOWLEDGE alone"
            + " - FOUR-EYES (the correction): each refined complement refused, each crossing made")
    void theCauseRefinements() throws Exception {
        int refinedCells = 0;
        for (BreakCause cause :
                List.of(BreakCause.EXECUTION_ALREADY_EXPLAINED, BreakCause.REPLAY_DIVERGED)) {
            BreakType type = cause.raisesAs().iterator().next();
            boolean complementDone = false;
            for (Cell cell : cellsOf(type, cause)) {
                Subject subject = seed(cell);
                if (!complementDone) {
                    refinedComplement(type, cause, subject);
                    complementDone = true;
                }
                cross(cell, subject);
                refinedCells++;
            }
        }
        assertThat(refinedCells)
                .as("EXECUTION_ALREADY_EXPLAINED: WRITE_OFF (DEBIT), OFFSET (both sides), GAIN;"
                        + " REPLAY_DIVERGED: ACKNOWLEDGE")
                .isEqualTo(5);

        for (BreakCause statement : List.of(BreakCause.STATEMENT_GAP, BreakCause.OPENING_BALANCE)) {
            UUID run = completedFeeRun().runId();
            UUID breakId = raise(BreakType.SETTLEMENT_MISMATCH, statement,
                    BreakRegister.Subject.run(run), 15_00);
            refinedComplement(BreakType.SETTLEMENT_MISMATCH, statement,
                    new Subject(breakId, Shape.NOTHING, 15_00, Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty()));
        }
        assertThat(refinedRefusals)
                .as("EAE x TRANSFER (1); REPLAY_DIVERGED x the six other person kinds (6); each"
                        + " statement cause x all seven (14)")
                .isEqualTo(21);
    }

    // ================================================================= one crossing

    private static void complement(BreakType type, BreakCause cause, Subject subject) {
        Set<ResolutionKind> admitted = ResolutionTemplates.admittedKinds(type, cause);
        for (ResolutionKind kind : PERSON_KINDS) {
            if (!admitted.contains(kind)) {
                refusedWithNothingWritten(subject.breakId(),
                        ResolutionMachine.ResolutionKindNotAllowed.class,
                        type + " x " + kind + (kind == ResolutionKind.REPUDIATE_BATCH
                                ? " (a shape refusal)" : " (a table cell)"),
                        () -> propose(PROPOSER, subject.breakId(), requestFor(kind,
                                kind.admittedReasonCodes().iterator().next())));
                if (kind == ResolutionKind.REPUDIATE_BATCH) {
                    repudiationShapeRefusals++;
                } else {
                    complementRefusals++;
                }
            }
        }
    }

    private static void refinedComplement(BreakType type, BreakCause cause, Subject subject) {
        Set<ResolutionKind> admitted = ResolutionTemplates.admittedKinds(type, cause);
        for (ResolutionKind kind : PERSON_KINDS) {
            if (!admitted.contains(kind)
                    && (cause != BreakCause.EXECUTION_ALREADY_EXPLAINED
                            || ResolutionTemplates.admittedKinds(type, ordinaryCause(type))
                                    .contains(kind))) {
                refusedWithNothingWritten(subject.breakId(),
                        ResolutionMachine.ResolutionKindNotAllowed.class,
                        type + "(" + cause + ") x " + kind + " (the refinement)",
                        () -> propose(PROPOSER, subject.breakId(), requestFor(kind,
                                kind.admittedReasonCodes().iterator().next())));
                refinedRefusals++;
            }
        }
    }

    private static void cross(Cell cell, Subject subject) throws SQLException {
        ResolutionKind kind = cell.kind();
        for (ResolutionKind other : ResolutionTemplates.admittedKinds(cell.type(), cell.cause())) {
            if (sideRefused(other, cell.shape())) {
                refusedWithNothingWritten(subject.breakId(),
                        ResolutionMachine.ResolutionKindNotAllowed.class,
                        cell + ": " + other + " is refused on this side (the direction rules)",
                        () -> propose(PROPOSER, subject.breakId(), requestFor(other,
                                other.admittedReasonCodes().iterator().next())));
                sideRefusals++;
            }
        }
        ResolutionReasonCode outside =
                EnumSet.complementOf(EnumSet.copyOf(kind.admittedReasonCodes())).iterator().next();
        refusedWithNothingWritten(subject.breakId(), ResolutionMachine.ReasonCodeNotAllowed.class,
                cell + " with " + outside,
                () -> propose(PROPOSER, subject.breakId(), requestFor(kind, outside, subject)));
        reasonRefusals++;

        ResolutionReasonCode code = kind.admittedReasonCodes().iterator().next();
        ResolutionMachine.Proposed proposed =
                propose(PROPOSER, subject.breakId(), requestFor(kind, code, subject));
        boolean onePerson = kind == ResolutionKind.ACKNOWLEDGE && subject.minor() == 0
                && cell.type() == BreakType.TIMING_DIFFERENCE;
        assertThat(proposed.fourEyes())
                .as("%s: four-eyes derived (one person only for a zero timing acknowledgement)",
                        cell)
                .isEqualTo(!onePerson);
        assertThat(string("SELECT kind || '/' || reason_code || '/' || four_eyes || '/' ||"
                        + " proposed_amount_minor FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId()))
                .as("%s: the frozen proposal - the whole remainder, never typed", cell)
                .isEqualTo(kind + "/" + code + "/" + !onePerson + "/" + subject.minor());
        List<String> expected = expectedLines(kind, subject);
        if (kind.postsAdjustment()) {
            assertThat(proposalLines(proposed.adjustmentProposalId().orElseThrow()))
                    .as("%s: the ledger proposal's lines, exactly the template", cell)
                    .containsExactlyElementsOf(expected);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                    proposed.resolutionId().toString()))
                    .as("%s: nothing posts at proposal", cell).isZero();
        } else {
            assertThat(proposed.adjustmentProposalId())
                    .as("%s posts no adjustment", cell).isEmpty();
        }

        Optional<UUID> entry;
        if (onePerson) {
            assertThat(proposed.status()).as("%s: born APPROVED", cell)
                    .isEqualTo(ResolutionStatus.APPROVED);
            entry = Optional.empty();
        } else {
            assertThat(proposed.status()).isEqualTo(ResolutionStatus.PROPOSED);
            assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                    subject.breakId())).isEqualTo("RESOLUTION_PROPOSED");
            refusedWithNothingWritten(subject.breakId(),
                    ResolutionMachine.SelfApprovalRefused.class,
                    cell + ": the proposer's own approval (the domain rank)",
                    () -> approve(PROPOSER, proposed.resolutionId()));
            selfApprovalRefusals++;
            ResolutionMachine.Decided approved = approve(APPROVER, proposed.resolutionId());
            assertThat(approved.status()).isEqualTo(ResolutionStatus.APPROVED);
            entry = approved.journalEntryId();
        }
        assertEffects(cell, subject, proposed.resolutionId(), entry, expected);
        crossings++;
    }

    /** The cell's lines, written down from the shape - never read from the template. */
    private static List<String> expectedLines(ResolutionKind kind, Subject subject) {
        long minor = subject.minor();
        return switch (kind) {
            case WRITE_OFF ->
                    List.of(losses + ">DEBIT>" + minor,
                            (subject.shape() == Shape.DEBIT_ITEM ? suspenseAccount : clearing)
                                    + ">CREDIT>" + minor);
            case TRANSFER_TO_ACCOUNT ->
                    List.of((subject.shape() == Shape.CREDIT_ITEM ? suspenseAccount : clearing)
                                    + ">DEBIT>" + minor,
                            wallet + ">CREDIT>" + minor);
            case RECOGNISE_GAIN ->
                    List.of(suspenseAccount + ">DEBIT>" + minor, gains + ">CREDIT>" + minor);
            // The manual match's unpark: the park's exact inverse.
            case MANUAL_MATCH -> subject.shape() == Shape.CREDIT_ITEM
                    ? List.of(suspenseAccount + ">DEBIT>" + minor, clearing + ">CREDIT>" + minor)
                    : List.of(clearing + ">DEBIT>" + minor, suspenseAccount + ">CREDIT>" + minor);
            default -> List.of();
        };
    }

    private static void assertEffects(
            Cell cell, Subject subject, UUID resolution, Optional<UUID> entry,
            List<String> expected) throws SQLException {
        ResolutionKind kind = cell.kind();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                subject.breakId())).as("%s: the break RESOLVED", cell).isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", subject.breakId()))
                .as("%s: one BreakResolved", cell).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", subject.breakId()))
                .as("%s: exactly one APPROVED resolution names the break", cell).isEqualTo(1);
        if (kind.postsAdjustment()) {
            UUID posted = entry.orElseThrow();
            assertThat(entryLines(posted)).as("%s: the entry's lines, exactly", cell)
                    .containsExactlyElementsOf(expected);
            assertThat(string("SELECT entry_type FROM ledger.journal_entry WHERE id = ?", posted))
                    .isEqualTo("ADJUSTMENT");
            assertThat(string("SELECT p.status || '/' || (p.journal_entry_id = r.journal_entry_id)"
                    + " || '/' || p.origin FROM reconciliation.resolution r JOIN"
                    + " ledger.adjustment_proposal p ON p.id = r.adjustment_proposal_id WHERE"
                    + " r.id = ?", resolution))
                    .as("%s: resolution, ledger proposal and entry one-to-one", cell)
                    .isEqualTo("APPROVED/true/RECONCILIATION");
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                    resolution.toString())).as("%s: one entry", cell).isEqualTo(1);
        } else if (kind == ResolutionKind.MANUAL_MATCH) {
            assertThat(entryLines(entry.orElseThrow()))
                    .as("%s: the unpark, the park's exact inverse", cell)
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                    subject.itemId().orElseThrow())).isEqualTo("MATCHED");
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                    subject.chosen().orElseThrow())).isEqualTo("SETTLED");
            assertThat(string("SELECT r.decision_id IS NOT NULL AND r.park_id IS NOT NULL FROM"
                    + " reconciliation.resolution r WHERE r.id = ?", resolution))
                    .as("%s: the resolution names its decision and unpark", cell).isEqualTo("true");
        } else {
            assertThat(entry).as("%s posts nothing", cell).isEmpty();
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                    resolution.toString())).isZero();
        }
        switch (subject.shape()) {
            case INBOUND_REMAINDER, OUTBOUND_REMAINDER -> {
                String expectation = string("SELECT status || '/' || resolved_minor FROM"
                        + " reconciliation.expectation WHERE id = ?",
                        subject.expectationId().orElseThrow());
                if (kind == ResolutionKind.ACKNOWLEDGE) {
                    assertThat(expectation).as("%s: an acknowledgement disposes of nothing", cell)
                            .isEqualTo("OPEN/0");
                } else {
                    assertThat(expectation).as("%s: the remainder resolved", cell)
                            .isEqualTo("RESOLVED_BY_ADJUSTMENT/" + subject.minor());
                }
            }
            case CREDIT_ITEM, DEBIT_ITEM -> {
                UUID item = subject.suspenseItemId().orElseThrow();
                assertThat(string("SELECT status || '/' || released_minor FROM"
                        + " reconciliation.suspense_item WHERE id = ?", item))
                        .as("%s: the suspense item released whole", cell)
                        .isEqualTo("RELEASED/" + subject.minor());
                String cause = switch (kind) {
                    case OFFSET_SUSPENSE -> "OFFSET_SUSPENSE";
                    case MANUAL_MATCH -> "UNPARK";
                    default -> "RESOLUTION";
                };
                assertThat(rows("SELECT cause || '/' || amount_minor || '/' || cause_ref FROM"
                        + " reconciliation.suspense_release WHERE item_id = ?", item))
                        .as("%s: released ONCE, naming the resolution", cell)
                        .containsExactly(cause + "/" + subject.minor() + "/resolution="
                                + resolution);
                if (kind != ResolutionKind.MANUAL_MATCH) {
                    assertThat(string("SELECT status FROM reconciliation.external_item WHERE"
                            + " id = ?", subject.itemId().orElseThrow()))
                            .as("%s: the item RESOLVED", cell).isEqualTo("RESOLVED");
                }
                if (kind == ResolutionKind.OFFSET_SUSPENSE) {
                    UUID partner = subject.partnerItem().orElseThrow();
                    assertThat(string("SELECT status || '/' || released_minor FROM"
                            + " reconciliation.suspense_item WHERE id = ?", partner))
                            .as("%s: the partner released whole too", cell)
                            .isEqualTo("RELEASED/" + subject.minor());
                    UUID partnerBreak = id("SELECT break_id FROM reconciliation.suspense_item"
                            + " WHERE id = ?", partner);
                    assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                            partnerBreak)).as("%s: both breaks close", cell)
                            .isEqualTo("RESOLVED");
                    assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE"
                            + " event_type = 'reconciliation.BreakResolved' AND aggregate_id ="
                            + " ?", partnerBreak)).isEqualTo(1);
                }
            }
            case NOTHING -> {
                // A fee's deviation or a decision's subject: nothing in a position to move.
            }
        }
    }

    // ================================================================= threshold edges

    @Test
    @Order(3)
    @DisplayName("threshold edges: one unit at issue makes an acknowledgement two people's even on"
            + " a timing difference; severity escalates at EXACTLY the pinned high_value_minor"
            + " (1,000.00) and not one unit below; a gain at the pinned 90 days is eligible and"
            + " at 89 refused - both pins read from the seeded rule set")
    void theThresholdEdges() throws Exception {
        assertThat(gainMinAgeDays).as("O5: the pinned minimum age").isEqualTo(90);
        assertThat(highValueMinor).as("O7: the pinned high value, 1,000.00").isEqualTo(1_000_00);

        Subject oneUnit = decisionSubject(BreakType.TIMING_DIFFERENCE, BreakCause.LATE_MATCH, 1);
        ResolutionMachine.Proposed valued = propose(PROPOSER, oneUnit.breakId(),
                requestFor(ResolutionKind.ACKNOWLEDGE, ResolutionReasonCode.TIMING_CONFIRMED));
        assertThat(valued.fourEyes()).as("one unit at issue is value at issue").isTrue();
        assertThat(valued.status()).isEqualTo(ResolutionStatus.PROPOSED);
        refusedWithNothingWritten(oneUnit.breakId(), ResolutionMachine.SelfApprovalRefused.class,
                "a one-unit acknowledgement refused without a second person",
                () -> approve(PROPOSER, valued.resolutionId()));
        assertThat(approve(APPROVER, valued.resolutionId()).status())
                .isEqualTo(ResolutionStatus.APPROVED);

        Seeded atThreshold = openExpectation(highValueMinor, ExpectationDirection.INBOUND);
        Seeded below = openExpectation(highValueMinor - 1, ExpectationDirection.INBOUND);
        BreakRegister.Raised escalated = raiseRaised(BreakType.MISSING_EXTERNAL,
                BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(atThreshold.id()), highValueMinor);
        BreakRegister.Raised base = raiseRaised(BreakType.MISSING_EXTERNAL,
                BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(below.id()), highValueMinor - 1);
        assertThat(escalated.severity()).as("MEDIUM one level up at exactly 1,000.00 (>=)")
                .isEqualTo(Severity.HIGH);
        assertThat(base.severity()).as("one unit below: the base grade").isEqualTo(Severity.MEDIUM);
        assertThat(string("SELECT severity FROM reconciliation.break WHERE id = ?",
                escalated.breakId())).isEqualTo("HIGH");

        Subject young = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), today.minusDays(gainMinAgeDays - 1L));
        refusedWithNothingWritten(young.breakId(), ResolutionMachine.GainNotYetEligible.class,
                "a gain one day short of the pinned 90",
                () -> propose(PROPOSER, young.breakId(), requestFor(
                        ResolutionKind.RECOGNISE_GAIN, ResolutionReasonCode.UNATTRIBUTABLE_AGED)));
        Subject aged = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), today.minusDays(gainMinAgeDays));
        ResolutionMachine.Proposed gain = propose(PROPOSER, aged.breakId(), requestFor(
                ResolutionKind.RECOGNISE_GAIN, ResolutionReasonCode.UNATTRIBUTABLE_AGED));
        UUID entry = approve(APPROVER, gain.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .as("exactly at 90 days: DR suspense / CR gains")
                .containsExactly(suspenseAccount + ">DEBIT>" + aged.minor(),
                        gains + ">CREDIT>" + aged.minor());
    }

    // ================================================================= offsets and recovery

    @Test
    @Order(4)
    @DisplayName("write-off then recovery: the late line finds its expectation disposed and parks"
            + " as DUPLICATE_EXTERNAL, never a second closure; too young for a gain, it is closed"
            + " four-eyes by a TRANSFER - DR suspense / CR the owner")
    void writeOffThenRecovery() throws Exception {
        long minor = nextAmount();
        Seeded expectation = openExpectation(minor, ExpectationDirection.INBOUND);
        UUID writtenOff = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), minor);
        ResolutionMachine.Proposed writeOff = propose(PROPOSER, writtenOff,
                requestFor(ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED));
        approve(APPROVER, writeOff.resolutionId());

        UUID lateRun = seedRun(new Line(1, ExternalLineType.CAPTURE, minor,
                ItemKeyKind.PSP_CAPTURE_REF, expectation.key()));
        matching().sweep();
        UUID lateItem = itemOf(lateRun, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                lateItem)).isEqualTo("PARKED");
        UUID recovery = id("SELECT id FROM reconciliation.break WHERE external_item_id = ? AND"
                + " type = 'DUPLICATE_EXTERNAL'", lateItem);
        assertThat(recovery).as("the late line parks as a recovery").isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                writtenOff)).as("the written-off break takes no second closure").isEqualTo(1);

        refusedWithNothingWritten(recovery, ResolutionMachine.GainNotYetEligible.class,
                "the recovery is younger than the pinned age",
                () -> propose(PROPOSER, recovery, requestFor(ResolutionKind.RECOGNISE_GAIN,
                        ResolutionReasonCode.UNATTRIBUTABLE_AGED)));
        ResolutionMachine.Proposed transfer = propose(PROPOSER, recovery,
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        refusedWithNothingWritten(recovery, ResolutionMachine.SelfApprovalRefused.class,
                "the recovery is four-eyes", () -> approve(PROPOSER, transfer.resolutionId()));
        UUID entry = approve(APPROVER, transfer.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(suspenseAccount + ">DEBIT>" + minor, wallet + ">CREDIT>" + minor);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", recovery))
                .isEqualTo("RESOLVED");
    }

    @Test
    @Order(5)
    @DisplayName("a CORRECTION offsetting a parked excess resolves it EVIDENCED: the person's"
            + " pending transfer is withdrawn by the platform with its ledger half rejected, the"
            + " release is CORRECTION_OFFSET, and the late approval finds nothing pending")
    void aCorrectionOffsetsAParkedExcessOverAPendingProposal() throws Exception {
        String key = "MX-COR-" + UUID.randomUUID();
        openExpectation(key, 60_00, ExpectationDirection.INBOUND, FAR);
        UUID capture = seedRun(new Line(1, ExternalLineType.CAPTURE, 100_00,
                ItemKeyKind.PSP_CAPTURE_REF, key));
        matching().sweep();
        UUID item = itemOf(capture, 1);
        UUID suspenseItem = id("SELECT id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);
        UUID excess = id("SELECT break_id FROM reconciliation.suspense_item WHERE id = ?",
                suspenseItem);
        assertThat(string("SELECT type || '/' || side || '/' || amount_minor FROM"
                + " reconciliation.break b JOIN reconciliation.suspense_item s ON s.break_id ="
                + " b.id WHERE s.id = ?", suspenseItem))
                .isEqualTo("AMOUNT_MISMATCH/CREDIT/4000");
        ResolutionMachine.Proposed pending = propose(PROPOSER, excess,
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));

        seedRun(new Line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT, 40_00,
                ItemKeyKind.ORIGINAL_REF, key));
        matching().sweep();
        assertThat(string("SELECT status || '/' || decided_by_type FROM"
                + " reconciliation.resolution WHERE id = ?", pending.resolutionId()))
                .as("evidence wins: the pending proposal withdrawn by the platform")
                .isEqualTo("WITHDRAWN/SYSTEM");
        assertThat(string("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                pending.adjustmentProposalId().orElseThrow())).isEqualTo("REJECTED");
        assertThat(string("SELECT kind || '/' || proposed_by_type FROM reconciliation.resolution"
                + " WHERE break_id = ? AND status = 'APPROVED'", excess))
                .isEqualTo("EVIDENCED/SYSTEM");
        assertThat(rows("SELECT cause || '/' || amount_minor FROM"
                + " reconciliation.suspense_release WHERE item_id = ?", suspenseItem))
                .containsExactly("CORRECTION_OFFSET/4000");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", excess))
                .isEqualTo("RESOLVED");
        refusedWithNothingWritten(excess, ResolutionMachine.ResolutionNotPending.class,
                "the late approval of the withdrawn proposal",
                () -> approve(APPROVER, pending.resolutionId()));
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                pending.resolutionId().toString())).isZero();
    }

    @Test
    @Order(6)
    @DisplayName("atomicity: a transfer target closed between proposal and approval fails the"
            + " approval at the ledger - the resolution stays PROPOSED, the item PARKED, the"
            + " suspense unreleased, nothing posted")
    void aNotPostableTargetLeavesTheProposalStanding() throws Exception {
        Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        UUID closing = openWallet();
        ResolutionMachine.Proposed proposed = propose(PROPOSER, owed.breakId(),
                new ResolutionMachine.ProposalRequest(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, "the owner's own funds",
                        Optional.of(closing), Optional.empty(), Optional.empty()));
        inCommittedTransaction(uow -> new JdbcLedgerAccountStore().moveStatus(
                uow, com.finapp.ledger.LedgerAccountId.of(closing), LedgerAccountStatus.ACTIVE,
                LedgerAccountStatus.CLOSED, Instant.now(CLOCK)));
        refusedWithNothingWritten(owed.breakId(), LedgerAccountNotPostableException.class,
                "a not-postable target", () -> approve(APPROVER, proposed.resolutionId()));
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId())).isEqualTo("PROPOSED");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                owed.itemId().orElseThrow())).isEqualTo("PARKED");
        assertThat(string("SELECT status || '/' || released_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?",
                owed.suspenseItemId().orElseThrow())).isEqualTo("OPEN/0");
        withdraw(PROPOSER, proposed.resolutionId());
    }

    // ================================================================= four-eyes, three ranks

    @Test
    @Order(7)
    @DisplayName("four-eyes at every rank, each proven alone: the domain refuses the proposer"
            + " (nothing written); the resolution CHECK refuses a raw self-approval of a"
            + " non-posting kind the ledger never sees; ledger V010 refuses the same person on a"
            + " resolution's own ledger proposal")
    void fourEyesAtEveryRank() throws Exception {
        // A non-posting four-eyes kind: only the domain and the resolution CHECK stand.
        Subject fee = feeSubject(nextAmount());
        ResolutionMachine.Proposed acknowledgement = propose(PROPOSER, fee.breakId(),
                requestFor(ResolutionKind.ACKNOWLEDGE,
                        ResolutionReasonCode.FEE_ACCEPTED_AS_CHARGED));
        refusedWithNothingWritten(fee.breakId(), ResolutionMachine.SelfApprovalRefused.class,
                "DOMAIN RANK: the proposer never approves their own",
                () -> approve(PROPOSER, acknowledgement.resolutionId()));
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "UPDATE reconciliation.resolution SET status ="
                    + " 'APPROVED', decided_by = proposed_by, decided_by_type = 'EMPLOYEE',"
                    + " decided_at = now(), status_changed_at = now() WHERE id = ?",
                    acknowledgement.resolutionId()))
                    .as("CHECK RANK: resolution_four_eyes_distinct binds a raw writer")
                    .hasStackTraceContaining("resolution_four_eyes_distinct");
            raw.rollback();
        }

        // A posting kind: the ledger proposal carries the same two people beneath.
        Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed transfer = propose(PROPOSER, owed.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "UPDATE ledger.adjustment_proposal SET"
                    + " status = 'APPROVED', decided_by = proposed_by, decided_at = now()"
                    + " WHERE id = ?", transfer.adjustmentProposalId().orElseThrow()))
                    .as("V010 RANK: the resolution's ledger proposal judges the same two people")
                    .hasStackTraceContaining("adjustment_proposal_approver_is_not_initiator");
            raw.rollback();
        }
        assertThat(approve(APPROVER, acknowledgement.resolutionId()).status())
                .isEqualTo(ResolutionStatus.APPROVED);
        assertThat(approve(APPROVER, transfer.resolutionId()).journalEntryId()).isPresent();
    }

    // ================================================================= races

    @Test
    @Order(8)
    @DisplayName("ten racing proposers of one break: ONE proposal, one ledger proposal, one"
            + " audit record - nine told it is already proposed")
    void tenProposersLeaveOneProposal() throws Exception {
        Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        long proposalsBefore = count("SELECT count(*) FROM ledger.adjustment_proposal");
        List<String> outcomes = race(10, racer -> {
            Actor proposer = new Actor("op-proposer-" + racer, ActorType.EMPLOYEE);
            try {
                propose(proposer, owed.breakId(), requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
                return "PROPOSED";
            } catch (ResolutionMachine.ResolutionAlreadyProposed lost) {
                return "ALREADY";
            }
        });
        assertThat(outcomes).filteredOn("PROPOSED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("ALREADY"::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                owed.breakId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.adjustment_proposal"))
                .as("one ledger proposal").isEqualTo(proposalsBefore + 1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.ResolutionProposed' AND target_id = (SELECT id::text FROM"
                + " reconciliation.resolution WHERE break_id = ?)", owed.breakId()))
                .isEqualTo(1);
    }

    @Test
    @Order(9)
    @DisplayName("ten racing approvers of a parked transfer: ONE entry, one release, one"
            + " BreakResolved; and one approver retrying ten times at once converges - one act,"
            + " nine replays, one entry")
    void tenApproversAndOneRetryingApprover() throws Exception {
        Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, owed.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        List<String> outcomes = race(10, racer -> {
            try {
                approve(new Actor("op-racer-" + racer, ActorType.EMPLOYEE),
                        proposed.resolutionId());
                return "APPROVED";
            } catch (ResolutionMachine.ResolutionNotPending lost) {
                return "NOT_PENDING";
            }
        });
        assertThat(outcomes).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(outcomes).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertOneEffect(proposed.resolutionId(), owed);

        Subject retried = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed again = propose(PROPOSER, retried.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        List<ResolutionMachine.Decided> decided =
                race(10, racer -> approve(APPROVER, again.resolutionId()));
        assertThat(decided).filteredOn(answer -> !answer.replayed()).hasSize(1);
        assertThat(decided).filteredOn(ResolutionMachine.Decided::replayed).hasSize(9);
        assertThat(decided.stream().map(ResolutionMachine.Decided::journalEntryId).distinct())
                .as("every retry answers the one entry").hasSize(1);
        assertOneEffect(again.resolutionId(), retried);
    }

    @Test
    @Order(10)
    @DisplayName("approve against reject, and approve against the proposer's withdrawal: ten"
            + " racers, ONE terminal decision each time - an approval posts one entry, a"
            + " rejection or withdrawal leaves the item parked and the ledger half rejected")
    void approveAgainstRejectAndWithdraw() throws Exception {
        for (String rival : List.of("REJECTED", "WITHDRAWN")) {
            Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                    Shape.CREDIT_ITEM, nextAmount(), agedOn);
            ResolutionMachine.Proposed proposed = propose(PROPOSER, owed.breakId(),
                    requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                            ResolutionReasonCode.FUNDS_ATTRIBUTED));
            List<String> outcomes = race(10, racer -> {
                try {
                    if (racer % 2 == 0) {
                        approve(new Actor("op-approver-" + racer, ActorType.EMPLOYEE),
                                proposed.resolutionId());
                        return "APPROVED";
                    }
                    if ("REJECTED".equals(rival)) {
                        reject(new Actor("op-rejecter-" + racer, ActorType.EMPLOYEE),
                                proposed.resolutionId(), "the owner is not evidenced");
                    } else {
                        withdraw(PROPOSER, proposed.resolutionId());
                    }
                    return rival;
                } catch (ResolutionMachine.ResolutionNotPending lost) {
                    return "NOT_PENDING";
                }
            });
            String status = string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                    proposed.resolutionId());
            assertThat(status).isIn("APPROVED", rival);
            if ("APPROVED".equals(status)) {
                assertThat(outcomes).as(rival + " race").filteredOn("APPROVED"::equals)
                        .hasSize(1);
                assertOneEffect(proposed.resolutionId(), owed);
            } else {
                assertThat(outcomes).as(rival + " race").doesNotContain("APPROVED");
                assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                        proposed.resolutionId().toString())).isZero();
                assertThat(string("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                        proposed.adjustmentProposalId().orElseThrow())).isEqualTo("REJECTED");
                assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                        owed.breakId())).isEqualTo("INVESTIGATING");
                assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                        owed.suspenseItemId().orElseThrow())).isEqualTo("OPEN");
            }
            assertThat(count("SELECT count(*) FROM reconciliation.resolution_event WHERE"
                    + " resolution_id = ? AND from_status = 'PROPOSED'", proposed.resolutionId()))
                    .as(rival + " race: one terminal edge").isEqualTo(1);
        }
    }

    @Test
    @Order(11)
    @DisplayName("evidence against approval on PARKED value, both ways and truly concurrent: the"
            + " rematch first withdraws the pending transfer and unparks EVIDENCED; the approval"
            + " first leaves the rematch nothing; raced, exactly one of the two - one release"
            + " either way")
    void evidenceAgainstApprovalOnParkedValue() throws Exception {
        // Evidence first.
        Subject first = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed pending = propose(PROPOSER, first.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        openExpectation(first.key().orElseThrow(), first.minor(), ExpectationDirection.INBOUND,
                FAR);
        matching().sweep();
        assertThat(string("SELECT status || '/' || decided_by_type FROM"
                + " reconciliation.resolution WHERE id = ?", pending.resolutionId()))
                .isEqualTo("WITHDRAWN/SYSTEM");
        assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", first.breakId())).isEqualTo("EVIDENCED");
        assertThat(rows("SELECT cause FROM reconciliation.suspense_release WHERE item_id = ?",
                first.suspenseItemId().orElseThrow())).containsExactly("UNPARK");
        refusedWithNothingWritten(first.breakId(), ResolutionMachine.ResolutionNotPending.class,
                "evidence first: the approval finds nothing pending",
                () -> approve(APPROVER, pending.resolutionId()));

        // Approval first.
        Subject second = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed transfer = propose(PROPOSER, second.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        approve(APPROVER, transfer.resolutionId());
        Seeded late = openExpectation(second.key().orElseThrow(), second.minor(),
                ExpectationDirection.INBOUND, FAR);
        matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                late.id())).as("approval first: the rematch finds no parked value").isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                second.breakId())).isEqualTo(1);
        assertThat(rows("SELECT cause FROM reconciliation.suspense_release WHERE item_id = ?",
                second.suspenseItemId().orElseThrow())).containsExactly("RESOLUTION");

        // Truly concurrent: one approval against four sweeps.
        Subject raced = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed contested = propose(PROPOSER, raced.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        Seeded racedExpectation = openExpectation(raced.key().orElseThrow(), raced.minor(),
                ExpectationDirection.INBOUND, FAR);
        List<String> outcomes = race(5, racer -> {
            if (racer == 0) {
                try {
                    approve(APPROVER, contested.resolutionId());
                    return "APPROVED";
                } catch (ResolutionMachine.ResolutionNotPending lost) {
                    return "NOT_PENDING";
                }
            }
            matching().sweep();
            return "SWEPT";
        });
        String winner = string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", raced.breakId());
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", raced.breakId())).as("one closure").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?",
                raced.suspenseItemId().orElseThrow())).as("one release").isEqualTo(1);
        if ("EVIDENCED".equals(winner)) {
            assertThat(outcomes).contains("NOT_PENDING");
            assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                    contested.resolutionId())).isEqualTo("WITHDRAWN");
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                    racedExpectation.id())).isEqualTo("SETTLED");
        } else {
            assertThat(winner).isEqualTo("TRANSFER_TO_ACCOUNT");
            assertThat(outcomes).contains("APPROVED");
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                    racedExpectation.id())).isEqualTo("OPEN");
        }
    }

    @Test
    @Order(12)
    @DisplayName("an allocation between proposal and approval - the real matcher's partial"
            + " arrival - refuses the approval as stale with nothing written; the proposer"
            + " withdraws and re-proposes over the new remainder; a residual_version moved with"
            + " no amount moved is stale too")
    void anAllocationBetweenProposalAndApprovalIsStale() throws Exception {
        Seeded expectation = openExpectation(60_00, ExpectationDirection.INBOUND);
        UUID overdue = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), 60_00);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, overdue,
                requestFor(ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED));
        seedRun(new Line(1, ExternalLineType.CAPTURE, 20_00, ItemKeyKind.PSP_CAPTURE_REF,
                expectation.key()));
        matching().sweep();
        refusedWithNothingWritten(overdue, ResolutionMachine.ResolutionStale.class,
                "a moved remainder (the stale check)",
                () -> approve(APPROVER, proposed.resolutionId()));
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId())).isEqualTo("PROPOSED");
        withdraw(PROPOSER, proposed.resolutionId());
        ResolutionMachine.Proposed again = propose(PROPOSER, overdue,
                requestFor(ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED));
        assertThat(string("SELECT proposed_amount_minor FROM reconciliation.resolution WHERE"
                + " id = ?", again.resolutionId())).isEqualTo("4000");
        UUID entry = approve(APPROVER, again.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(losses + ">DEBIT>4000", clearing + ">CREDIT>4000");

        // The residual version alone: a touch that moved no amount still refuses (the frozen
        // residual_version is the staleness rule, ADR-0069 section 1 - not the amount alone).
        Subject owed = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        ResolutionMachine.Proposed touched = propose(PROPOSER, owed.breakId(),
                requestFor(ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED));
        inCommittedTransaction(uow -> {
            execute(uow, "UPDATE reconciliation.break SET residual_version = residual_version"
                    + " + 1 WHERE id = ?", owed.breakId());
            return null;
        });
        refusedWithNothingWritten(owed.breakId(), ResolutionMachine.ResolutionStale.class,
                "a moved residual_version with the amount unchanged (the stale check)",
                () -> approve(APPROVER, touched.resolutionId()));
        withdraw(PROPOSER, touched.resolutionId());
    }

    @Test
    @Order(13)
    @DisplayName("one suspense item two paths can each release - an offset naming it, and the"
            + " rematch unparking it as evidence - truly raced: released ONCE, by whichever won;"
            + " the loser moves nothing; and an offset naming an item whose break already carries"
            + " a live proposal is refused at proposal")
    void aSuspenseItemIsReleasedOnce() throws Exception {
        long minor = nextAmount();
        Subject credit = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, minor, agedOn);
        Subject debit = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.DEBIT_ITEM, minor, agedOn);
        UUID debitItem = debit.suspenseItemId().orElseThrow();
        ResolutionMachine.Proposed offset = propose(PROPOSER, credit.breakId(),
                new ResolutionMachine.ProposalRequest(ResolutionKind.OFFSET_SUSPENSE,
                        ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY, "the two net",
                        Optional.empty(), Optional.of(debitItem), Optional.empty()));
        // The refund the DEBIT line belongs to opens: the rematch leg can now unpark it whole.
        Seeded refund = openExpectation(debit.key().orElseThrow(), minor,
                ExpectationDirection.OUTBOUND, FAR);
        List<String> outcomes = race(5, racer -> {
            if (racer == 0) {
                try {
                    approve(APPROVER, offset.resolutionId());
                    return "OFFSET";
                } catch (ResolutionMachine.ResolutionStale stale) {
                    return "STALE";
                }
            }
            matching().sweep();
            return "SWEPT";
        });
        List<String> releases = rows("SELECT cause FROM reconciliation.suspense_release WHERE"
                + " item_id = ?", debitItem);
        assertThat(releases).as("the DEBIT item released exactly once, by one path").hasSize(1);
        if ("OFFSET_SUSPENSE".equals(releases.get(0))) {
            assertThat(outcomes).contains("OFFSET");
            assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                    credit.suspenseItemId().orElseThrow())).isEqualTo("RELEASED");
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                    refund.id())).as("the rematch found nothing parked").isEqualTo("OPEN");
        } else {
            assertThat(releases.get(0)).isEqualTo("UNPARK");
            assertThat(outcomes).contains("STALE");
            assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                    offset.resolutionId())).as("the offset moved nothing").isEqualTo("PROPOSED");
            assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                    credit.suspenseItemId().orElseThrow())).isEqualTo("OPEN");
            assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                    + " status = 'APPROVED'", debit.breakId())).isEqualTo("EVIDENCED");
            withdraw(PROPOSER, offset.resolutionId());
        }
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                debit.breakId())).isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", debit.breakId()))
                .as("one closure of the DEBIT item's break").isEqualTo(1);

        Subject other = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, nextAmount(), agedOn);
        Subject proposedFirst = parked(BreakType.REFUND_MISMATCH,
                BreakCause.REFUND_CONTRADICTED, Shape.DEBIT_ITEM, other.minor(), agedOn);
        propose(THIRD, proposedFirst.breakId(), requestFor(ResolutionKind.WRITE_OFF,
                ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED));
        refusedWithNothingWritten(other.breakId(), ResolutionMachine.ResolutionTargetRefused.class,
                "an offset naming an item whose break carries a live proposal",
                () -> propose(PROPOSER, other.breakId(),
                        new ResolutionMachine.ProposalRequest(ResolutionKind.OFFSET_SUSPENSE,
                                ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY, "the two net",
                                Optional.empty(), proposedFirst.suspenseItemId(),
                                Optional.empty())));
    }

    // ================================================================= reclassification

    @Test
    @Order(14)
    @DisplayName("reclassification cannot escape a refinement: a diverged replay reclassified onto"
            + " TIMING_DIFFERENCE (both stand on a decision) admits ACKNOWLEDGE alone and keeps it"
            + " FOUR-EYES at the domain and at V014's trigger; an explained duplicate reclassified"
            + " onto UNKNOWN_EXTERNAL still refuses TRANSFER_TO_ACCOUNT - the cause is frozen")
    void reclassificationCannotEscapeARefinement() throws Exception {
        BreakCaseFile caseFile = new BreakCaseFile(new JdbcBreakCaseStore(),
                (unitOfWork, kind, id) -> true, (unitOfWork, principal) -> true,
                new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS, CLOCK);
        // One operator holding INVESTIGATE and RESOLVE: the reclassifier and the proposer.
        Subject diverged =
                decisionSubject(BreakType.PROCESSING_ERROR, BreakCause.REPLAY_DIVERGED, 0);
        as(PROPOSER, uow -> caseFile.reclassify(uow, diverged.breakId(),
                BreakType.TIMING_DIFFERENCE, "looks like a late match to me", PROPOSER,
                CorrelationId.generate(IDS)));
        assertThat(string("SELECT type || '/' || cause FROM reconciliation.break WHERE id = ?",
                diverged.breakId())).isEqualTo("TIMING_DIFFERENCE/REPLAY_DIVERGED");
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "INSERT INTO reconciliation.resolution (id,"
                    + " break_id, kind, status, reason_code, narrative, four_eyes,"
                    + " proposed_amount_minor, currency, scale, residual_version, rule_set_id,"
                    + " proposed_by, proposed_by_type, proposed_at, decided_by, decided_by_type,"
                    + " decided_at, created_at, status_changed_at, correlation_id) VALUES (?, ?,"
                    + " 'ACKNOWLEDGE', 'APPROVED', 'TIMING_CONFIRMED', 'raw probe', false, 0,"
                    + " 'EUR', 2, 0, ?, 'op-raw', 'EMPLOYEE', now(), 'op-raw', 'EMPLOYEE', now(),"
                    + " now(), now(), 'p8tst2')", IDS.next(), diverged.breakId(), RULE_SET))
                    .as("V014 TRIGGER RANK: a one-person acknowledgement of the reclassified"
                            + " replay is refused for a raw writer")
                    .hasStackTraceContaining("resolution_four_eyes_derived_from_the_break");
            raw.rollback();
        }
        for (ResolutionKind posting : List.of(ResolutionKind.WRITE_OFF,
                ResolutionKind.TRANSFER_TO_ACCOUNT)) {
            refusedWithNothingWritten(diverged.breakId(),
                    ResolutionMachine.ResolutionKindNotAllowed.class,
                    "the reclassified replay admits ACKNOWLEDGE alone, never " + posting,
                    () -> propose(PROPOSER, diverged.breakId(), requestFor(posting,
                            posting.admittedReasonCodes().iterator().next())));
        }
        ResolutionMachine.Proposed acknowledged = propose(PROPOSER, diverged.breakId(),
                requestFor(ResolutionKind.ACKNOWLEDGE, ResolutionReasonCode.TIMING_CONFIRMED));
        assertThat(acknowledged.fourEyes())
                .as("DOMAIN RANK: the reclassified replay's acknowledgement stays four-eyes")
                .isTrue();
        assertThat(acknowledged.status()).isEqualTo(ResolutionStatus.PROPOSED);
        refusedWithNothingWritten(diverged.breakId(), ResolutionMachine.SelfApprovalRefused.class,
                "the reclassifier-proposer never approves it",
                () -> approve(PROPOSER, acknowledged.resolutionId()));
        assertThat(approve(APPROVER, acknowledged.resolutionId()).status())
                .isEqualTo(ResolutionStatus.APPROVED);

        Subject explained = parked(BreakType.DUPLICATE_EXTERNAL,
                BreakCause.EXECUTION_ALREADY_EXPLAINED, Shape.CREDIT_ITEM, nextAmount(), agedOn);
        as(PROPOSER, uow -> caseFile.reclassify(uow, explained.breakId(),
                BreakType.UNKNOWN_EXTERNAL, "no execution explains it after all", PROPOSER,
                CorrelationId.generate(IDS)));
        assertThat(string("SELECT type || '/' || cause FROM reconciliation.break WHERE id = ?",
                explained.breakId())).isEqualTo("UNKNOWN_EXTERNAL/EXECUTION_ALREADY_EXPLAINED");
        refusedWithNothingWritten(explained.breakId(),
                ResolutionMachine.ResolutionKindNotAllowed.class,
                "an explained duplicate's value is never attributed twice, whatever its type",
                () -> propose(PROPOSER, explained.breakId(), requestFor(
                        ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED)));
        ResolutionMachine.Proposed gain = propose(PROPOSER, explained.breakId(),
                requestFor(ResolutionKind.RECOGNISE_GAIN,
                        ResolutionReasonCode.UNATTRIBUTABLE_AGED));
        assertThat(approve(APPROVER, gain.resolutionId()).journalEntryId()).isPresent();
    }

    // ================================================================= reprocess or requeue

    @Test
    @Order(15)
    @DisplayName("PROCESSING_ERROR's own path, counted: a blocked run requeued by a controller"
            + " completes and closes its RUN_BLOCKED break EVIDENCED; an ITEM_ERRORED item parked"
            + " by a poisoned lookup is re-decided by a reprocess run - allocated, unparked, its"
            + " break closed EVIDENCED - with no person's resolution in either")
    void processingErrorsReprocessOrRequeuePath() throws Exception {
        RunAdministration administration =
                new RunAdministration(matchingStore, runs, new JdbcAuditWriter(), IDS);
        Actor controller = new Actor("op-controller", ActorType.EMPLOYEE);

        // Requeue: a run blocked at its failure bound, then resumed by a person.
        String key = "MX-RQ-" + UUID.randomUUID();
        openExpectationIn(OPERATED_SOURCE, OPERATED_RULE_SET, key, 12_00,
                ExpectationDirection.INBOUND, FAR);
        UUID blocked = seedRunIn(OPERATED_SOURCE, OPERATED_RULE_SET,
                new Line(1, ExternalLineType.CAPTURE, 12_00, ItemKeyKind.PSP_CAPTURE_REF, key));
        UUID runBlocked = as(PLATFORM, uow -> {
            Instant now = Instant.now(CLOCK);
            for (int failure = 0; failure < 3; failure++) {
                matchingStore.bumpRunFailures(uow, blocked, now);
            }
            assertThat(matchingStore.blockRun(uow, blocked, PLATFORM, now)).isTrue();
            return register.raise(uow, new BreakRegister.NewBreak(
                    IDS.next(), BreakType.PROCESSING_ERROR, BreakCause.RUN_BLOCKED,
                    BreakRegister.Subject.run(blocked), OPERATED_SOURCE, OPERATED_RULE_SET,
                    Money.ofPersisted(0, EUR, 2), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    PLATFORM, now, CorrelationId.generate(IDS))).breakId();
        });
        matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                blocked)).as("nothing resumes a blocked run but a person").isEqualTo("BLOCKED");
        as(controller, uow -> administration.requeue(uow, blocked, controller,
                "the poisoned dependency is fixed", Instant.now(CLOCK),
                CorrelationId.generate(IDS)));
        for (int tick = 0; tick < 5 && !"COMPLETED".equals(string("SELECT status FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", blocked)); tick++) {
            matching().sweep();
        }
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE run_id = ?",
                blocked)).isEqualTo("MATCHED");
        assertThat(string("SELECT b.status || '/' || r.kind || '/' || r.proposed_by_type FROM"
                + " reconciliation.break b JOIN reconciliation.resolution r ON r.break_id = b.id"
                + " AND r.status = 'APPROVED' WHERE b.id = ?", runBlocked))
                .as("the requeued run's completion closes its RUN_BLOCKED break by evidence")
                .isEqualTo("RESOLVED/EVIDENCED/SYSTEM");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", runBlocked))
                .isEqualTo(1);

        // Reprocess: the item errors under a poisoned lookup and parks with its break.
        java.util.concurrent.atomic.AtomicBoolean poisoned =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Matching poisonable = matching((unitOfWork, subject) -> {
            if (poisoned.get()) {
                throw new IllegalStateException("planted poison");
            }
            return InternalReferenceLookup.InternalReference.unknown();
        });
        String poisonKey = "MX-RP-" + UUID.randomUUID();
        UUID erroredRun = seedRunIn(OPERATED_SOURCE, OPERATED_RULE_SET,
                new Line(1, ExternalLineType.REFUND, 9_00, ItemKeyKind.PSP_REFUND_REF,
                        poisonKey));
        for (int tick = 0; tick < 5 && !"COMPLETED".equals(string("SELECT status FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", erroredRun)); tick++) {
            poisonable.sweep();
        }
        UUID errored = itemOf(erroredRun, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                errored)).isEqualTo("PARKED");
        UUID erroredBreak = id("SELECT id FROM reconciliation.break WHERE external_item_id = ?"
                + " AND type = 'PROCESSING_ERROR' AND cause = 'ITEM_ERRORED'", errored);
        assertThat(erroredBreak).as("the errored item parked with its ITEM_ERRORED owner")
                .isNotNull();

        // The fix lands and the refund it names is known: a controller reprocesses. The open
        // REPROCESS run owns the source's residuals, so time's rematch waits for it - only the
        // reprocess leg re-decides the item here. *(Corrected 2026-10-02 by the Phase 8 -> 9
        // transition: this read that the rematch predicate, "opened AFTER the latest decision",
        // never reaches an expectation opened at the decision's own instant; the rematch now
        // judges a reach on rows, and yields to an open reprocess run.)*
        poisoned.set(false);
        openExpectationIn(OPERATED_SOURCE, OPERATED_RULE_SET, poisonKey, 9_00,
                ExpectationDirection.OUTBOUND, FAR);
        RunAdministration.Reprocessing opened = as(controller,
                uow -> administration.requestReprocessing(uow, OPERATED_SOURCE, controller,
                        "the lookup is repaired", Instant.now(CLOCK),
                        CorrelationId.generate(IDS)));
        for (int tick = 0; tick < 10 && !"COMPLETED".equals(string("SELECT status FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", opened.runId())); tick++) {
            poisonable.sweep();
        }
        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                opened.runId())).isEqualTo("COMPLETED");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                errored)).as("re-decided by the reprocess").isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND run_id = ? AND origin = 'REPROCESS' AND outcome ="
                + " 'MATCHED'", errored, opened.runId())).isEqualTo(1);
        assertThat(rows("SELECT cause FROM reconciliation.suspense_release s JOIN"
                + " reconciliation.suspense_item i ON i.id = s.item_id WHERE"
                + " i.external_item_id = ?", errored)).containsExactly("UNPARK");
        assertThat(string("SELECT b.status || '/' || r.kind || '/' || r.proposed_by_type FROM"
                + " reconciliation.break b JOIN reconciliation.resolution r ON r.break_id = b.id"
                + " AND r.status = 'APPROVED' WHERE b.id = ?", erroredBreak))
                .as("the reprocess's unpark closes the ITEM_ERRORED break by evidence")
                .isEqualTo("RESOLVED/EVIDENCED/SYSTEM");
    }

    // ================================================================= losses and gains

    @Test
    @Order(16)
    @DisplayName("RECONCILIATION_LOSSES and RECONCILIATION_GAINS posted only by approvals: every"
            + " line this suite put on either belongs to an APPROVED WRITE_OFF or RECOGNISE_GAIN"
            + " resolution's entry, one entry per such resolution - the gains half non-vacuous")
    void lossesAndGainsArePostedOnlyByApprovals() throws Exception {
        List<String> lines = rows("SELECT l.entry_id::text || '/' || a.purpose FROM"
                + " ledger.journal_line l JOIN ledger.ledger_account a ON a.id ="
                + " l.ledger_account_id WHERE a.purpose IN ('RECONCILIATION_LOSSES',"
                + " 'RECONCILIATION_GAINS')");
        List<String> suites = lines.stream()
                .filter(line -> !lossAndGainEntriesBefore.contains(line.split("/")[0]))
                .toList();
        assertThat(suites).filteredOn(line -> line.endsWith("RECONCILIATION_GAINS"))
                .as("this suite crossed every RECOGNISE_GAIN").isNotEmpty();
        assertThat(suites).filteredOn(line -> line.endsWith("RECONCILIATION_LOSSES"))
                .isNotEmpty();
        for (String line : suites) {
            String[] parts = line.split("/");
            assertThat(string("SELECT r.kind FROM reconciliation.resolution r WHERE"
                    + " r.journal_entry_id = ?::uuid AND r.status = 'APPROVED'", parts[0]))
                    .as("the %s line of entry %s belongs to an approved resolution", parts[1],
                            parts[0])
                    .isEqualTo(parts[1].equals("RECONCILIATION_GAINS")
                            ? "RECOGNISE_GAIN" : "WRITE_OFF");
        }
        long gains = count("SELECT count(*) FROM reconciliation.resolution r JOIN"
                + " reconciliation.break b ON b.id = r.break_id WHERE b.source_id = ? AND"
                + " r.kind = 'RECOGNISE_GAIN' AND r.status = 'APPROVED'", SOURCE);
        assertThat(suites.stream().filter(line -> line.endsWith("RECONCILIATION_GAINS"))
                .map(line -> line.split("/")[0]).distinct().count())
                .as("one gains entry per approved gain of this suite").isEqualTo(gains);
    }

    // ================================================================= the offset's partner

    @Test
    @Order(17)
    @DisplayName("T-1 (the Phase 8 -> 9 transition): CURRENCY_MISMATCH x OFFSET_SUSPENSE refuses"
            + " a partner of EQUAL minor units in another currency - SUSPENSE_UNMATCHED is one"
            + " account per currency, so only a same-currency pair nets - and a partner released"
            + " by ANOTHER resolution between proposal and approval is ResolutionStale, nothing"
            + " written either time")
    void anOffsetRefusesAForeignOrReleasedPartner() throws Exception {
        long minor = nextAmount();
        Subject subject = parked(BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS,
                Shape.CREDIT_ITEM, minor, agedOn);
        // The guard's scale clause has no reachable counterexample: within one currency the
        // park's own posting refuses another scale (ledger INV-MON-03, one scale per account
        // projection) - defence in depth, untestable through any real writer.
        UUID foreign = parkedMoney(minor, GBP);
        refusedWithNothingWritten(subject.breakId(),
                ResolutionMachine.ResolutionTargetRefused.class,
                "CURRENCY_MISMATCH x OFFSET_SUSPENSE: equal minor units in another currency"
                        + " are no pair (INV-MON-04, INV-REC-09)",
                () -> propose(PROPOSER, subject.breakId(), offsetRequest(foreign)));
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                foreign)).isEqualTo("OPEN");

        // The deterministic approval-time case: the partner is released by ANOTHER resolution
        // between proposal and approval, so the approval's re-derivation under the locks
        // answers ResolutionStale (aSuspenseItemIsReleasedOnce only races this branch).
        Subject partner = parked(BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED,
                Shape.DEBIT_ITEM, minor, agedOn);
        UUID partnerItem = partner.suspenseItemId().orElseThrow();
        ResolutionMachine.Proposed offset =
                propose(PROPOSER, subject.breakId(), offsetRequest(partnerItem));
        ResolutionMachine.Proposed writeOff = propose(THIRD, partner.breakId(),
                requestFor(ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED));
        approve(APPROVER, writeOff.resolutionId());
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                partnerItem)).isEqualTo("RELEASED");
        refusedWithNothingWritten(subject.breakId(), ResolutionMachine.ResolutionStale.class,
                "the approval finds the partner released by the write-off",
                () -> approve(APPROVER, offset.resolutionId()));
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                subject.suspenseItemId().orElseThrow()))
                .as("the subject's item moved nothing").isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE"
                + " item_id = ?", subject.suspenseItemId().orElseThrow())).isZero();
        withdraw(PROPOSER, offset.resolutionId());
    }

    @Test
    @Order(18)
    @DisplayName("T-7 (the Phase 8 -> 9 transition): the offset and the rematch, each winning"
            + " DETERMINISTICALLY - the rematch first leaves the late approval ResolutionStale"
            + " with nothing written; the offset first leaves the late rematch nothing parked -"
            + " one release either way")
    void offsetAndUnparkEachWinDeterministically() throws Exception {
        // The rematch first: the refund opens and the sweep unparks the partner before the
        // approval runs.
        long minor = nextAmount();
        Subject credit = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, minor, agedOn);
        Subject debit = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.DEBIT_ITEM, minor, agedOn);
        UUID debitItem = debit.suspenseItemId().orElseThrow();
        ResolutionMachine.Proposed offset =
                propose(PROPOSER, credit.breakId(), offsetRequest(debitItem));
        openExpectation(debit.key().orElseThrow(), minor, ExpectationDirection.OUTBOUND, FAR);
        matching().sweep();
        assertThat(rows("SELECT cause FROM reconciliation.suspense_release WHERE item_id = ?",
                debitItem)).as("the rematch unparked the partner").containsExactly("UNPARK");
        assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", debit.breakId())).isEqualTo("EVIDENCED");
        refusedWithNothingWritten(credit.breakId(), ResolutionMachine.ResolutionStale.class,
                "the approval after the rematch unparked the partner",
                () -> approve(APPROVER, offset.resolutionId()));
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                credit.suspenseItemId().orElseThrow())).isEqualTo("OPEN");
        withdraw(PROPOSER, offset.resolutionId());

        // The offset first: approved before the refund opens, the late sweep finds nothing
        // parked and the released item is never released again.
        long second = nextAmount();
        Subject credit2 = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.CREDIT_ITEM, second, agedOn);
        Subject debit2 = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                Shape.DEBIT_ITEM, second, agedOn);
        UUID debitItem2 = debit2.suspenseItemId().orElseThrow();
        ResolutionMachine.Proposed offset2 =
                propose(PROPOSER, credit2.breakId(), offsetRequest(debitItem2));
        approve(APPROVER, offset2.resolutionId());
        Seeded refund = openExpectation(debit2.key().orElseThrow(), second,
                ExpectationDirection.OUTBOUND, FAR);
        matching().sweep();
        assertThat(rows("SELECT cause FROM reconciliation.suspense_release WHERE item_id = ?",
                debitItem2))
                .as("released ONCE, by the offset; the late rematch found nothing parked")
                .containsExactly("OFFSET_SUSPENSE");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                refund.id())).isEqualTo("OPEN");
    }

    // ================================================================= nothing deletable

    @Test
    @Order(20)
    @DisplayName("nothing deletable, schema-wide: every one of the reconciliation schema's 32"
            + " tables refuses DELETE and TRUNCATE to the application by privilege and carries an"
            + " enabled BEFORE DELETE row trigger, a live owner DELETE refused on every table"
            + " holding a row; the seven column-narrowed tables refuse a frozen column to the"
            + " application by privilege and to the owner by trigger (the owner's TRUNCATE is a"
            + " known limit)")
    void nothingIsDeletableSchemaWide() throws Exception {
        Set<String> liveProbed = new TreeSet<>();
        try (Connection owner = DatabaseRoles.migrator();
                Connection app = DatabaseRoles.application()) {
            owner.setAutoCommit(false);
            app.setAutoCommit(false);
            // Read through the OWNER from the catalogue: information_schema through finapp_app
            // lists only the tables it was granted, so an ungranted table would slip past.
            List<String> tables = rowsOn(owner, "SELECT c.relname FROM pg_class c JOIN"
                    + " pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname ="
                    + " 'reconciliation' AND c.relkind IN ('r', 'p') AND c.relname <>"
                    + " 'flyway_schema_history' ORDER BY c.relname");
            // 32 since V016 (the Phase 8 -> 9 transition's match_reach).
            assertThat(tables).as("a new table cannot slip past this scan").hasSize(32);
            for (String table : tables) {
                String name = "reconciliation." + table;
                for (String privilege : List.of("DELETE", "TRUNCATE")) {
                    assertThat(scalar(owner, "SELECT has_table_privilege('finapp_app', '" + name
                            + "', '" + privilege + "')"))
                            .as("PRIVILEGE RANK: finapp_app holds no %s on %s", privilege, name)
                            .isEqualTo("f");
                }
                assertThat(sqlState(app, "DELETE FROM " + name + " WHERE false"))
                        .as("the application's DELETE on %s is refused by privilege", name)
                        .isEqualTo("42501");
                assertThat(sqlState(app, "TRUNCATE " + name))
                        .as("the application's TRUNCATE on %s is refused by privilege", name)
                        .isEqualTo("42501");
                assertThat(Long.parseLong(scalar(owner, "SELECT count(*) FROM pg_trigger WHERE"
                        + " tgrelid = '" + name + "'::regclass AND NOT tgisinternal AND"
                        + " tgenabled <> 'D' AND (tgtype & 1) = 1 AND (tgtype & 2) = 2 AND"
                        + " (tgtype & 8) = 8")))
                        .as("TRIGGER RANK: %s carries an enabled BEFORE DELETE row trigger", name)
                        .isPositive();
                if ("t".equals(scalar(owner, "SELECT EXISTS (SELECT 1 FROM " + name + ")"))) {
                    assertThat(sqlState(owner, "DELETE FROM " + name + " WHERE ctid = (SELECT"
                            + " ctid FROM " + name + " LIMIT 1)"))
                            .as("TRIGGER RANK: the owner's DELETE of a row of %s is refused by"
                                    + " its trigger (raise_exception)", name)
                            .isEqualTo("P0001");
                    liveProbed.add(table);
                }
            }
            assertThat(liveProbed)
                    .as("every table this battery writes was probed live")
                    .containsAll(List.of("rule_set", "rule", "tolerance", "severity_threshold",
                            "expectation", "expectation_event", "expectation_key",
                            "reconciliation_batch", "external_item", "external_item_key", "break",
                            "break_event", "park", "suspense_item", "suspense_release",
                            "match_decision", "match_candidate", "allocation", "resolution",
                            "resolution_event"));

            List<String> narrowed = rowsOn(owner, "SELECT DISTINCT table_name FROM"
                    + " information_schema.column_privileges WHERE table_schema ="
                    + " 'reconciliation' AND grantee = 'finapp_app' AND privilege_type = 'UPDATE'"
                    + " ORDER BY table_name");
            assertThat(narrowed)
                    .as("the eight column-narrowed UPDATE grants - expectation_key's one release"
                            + " edge since V017 (the Phase 8 -> 9 transition)")
                    .containsExactly("break", "expectation", "expectation_key", "external_item",
                            "reconciliation_batch", "resolution", "rule_set", "suspense_item");
            assertThat(rowsOn(owner, "SELECT table_name FROM information_schema.table_privileges WHERE"
                    + " table_schema = 'reconciliation' AND grantee = 'finapp_app' AND"
                    + " privilege_type IN ('UPDATE', 'DELETE', 'TRUNCATE')"))
                    .as("no table-wide UPDATE, DELETE or TRUNCATE anywhere").isEmpty();
            for (String table : narrowed) {
                String name = "reconciliation." + table;
                // The key index carries no correlation and no id of its own: its frozen fact is
                // the reference it binds (V017's one edge releases it, never rewrites it).
                boolean keyIndex = "expectation_key".equals(table);
                String frozen = keyIndex ? "key_value" : "correlation_id";
                String oneRow =
                        keyIndex
                                ? "ctid = (SELECT ctid FROM " + name + " LIMIT 1)"
                                : "id = (SELECT id FROM " + name + " LIMIT 1)";
                assertThat(sqlState(app, "UPDATE " + name
                        + " SET " + frozen + " = " + frozen + " WHERE false"))
                        .as("PRIVILEGE RANK: the frozen %s of %s", frozen, name)
                        .isEqualTo("42501");
                assertThat(sqlState(owner, "UPDATE " + name + " SET " + frozen + " ="
                        + " " + frozen + " || '-tampered' WHERE " + oneRow))
                        .as("TRIGGER RANK: the owner's write of %s's frozen %s is"
                                + " refused by its trigger (raise_exception)", name, frozen)
                        .isEqualTo("P0001");
            }
            owner.rollback();
            app.rollback();
        }
    }

    // ================================================================= seeding

    /** A seeded subject: its break, what it holds and the rows that hold it. */
    private record Subject(
            UUID breakId,
            Shape shape,
            long minor,
            Optional<UUID> expectationId,
            Optional<UUID> itemId,
            Optional<UUID> suspenseItemId,
            Optional<String> key,
            Optional<UUID> partnerItem,
            Optional<UUID> chosen) {

        Subject(UUID breakId, Shape shape, long minor, Optional<UUID> expectationId,
                Optional<UUID> itemId, Optional<UUID> suspenseItemId, Optional<String> key) {
            this(breakId, shape, minor, expectationId, itemId, suspenseItemId, key,
                    Optional.empty(), Optional.empty());
        }

        Subject withPartner(UUID partner) {
            return new Subject(breakId, shape, minor, expectationId, itemId, suspenseItemId, key,
                    Optional.of(partner), chosen);
        }
    }

    private record Seeded(UUID id, String key) {}

    private record Line(
            int lineNo, ExternalLineType type, long minor, ItemKeyKind keyKind, String key) {}

    private record FeeRun(UUID runId, UUID itemId) {}

    private static long nextAmount() {
        return AMOUNTS.getAndAdd(7);
    }

    private static Subject seed(Cell cell) throws SQLException {
        long minor = cell.shape() == Shape.NOTHING
                && (cell.type() == BreakType.TIMING_DIFFERENCE
                        || cell.cause() == BreakCause.REPLAY_DIVERGED)
                ? 0 : nextAmount();
        if (cell.kind() == ResolutionKind.MANUAL_MATCH) {
            return ambiguous(minor, cell.shape());
        }
        Subject subject = switch (cell.shape()) {
            case INBOUND_REMAINDER, OUTBOUND_REMAINDER -> {
                Seeded expectation = openExpectation(minor,
                        cell.shape() == Shape.INBOUND_REMAINDER
                                ? ExpectationDirection.INBOUND : ExpectationDirection.OUTBOUND);
                UUID breakId = raise(cell.type(), cell.cause(),
                        BreakRegister.Subject.expectation(expectation.id()), minor);
                yield new Subject(breakId, cell.shape(), minor, Optional.of(expectation.id()),
                        Optional.empty(), Optional.empty(), Optional.of(expectation.key()));
            }
            case CREDIT_ITEM, DEBIT_ITEM ->
                    parked(cell.type(), cell.cause(), cell.shape(), minor, agedOn);
            case NOTHING -> cell.type() == BreakType.FEE_MISMATCH
                    ? feeSubject(minor)
                    : decisionSubject(cell.type(), cell.cause(), minor);
        };
        if (cell.kind() == ResolutionKind.OFFSET_SUSPENSE) {
            Shape opposite = cell.shape() == Shape.CREDIT_ITEM ? Shape.DEBIT_ITEM : Shape.CREDIT_ITEM;
            Subject partner = parked(BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT,
                    opposite, minor, agedOn);
            return subject.withPartner(partner.suspenseItemId().orElseThrow());
        }
        return subject;
    }

    /** A parked item of {@code shape}'s side, owned by a break raised on it, the run completed. */
    private static Subject parked(BreakType type, BreakCause cause, Shape shape, long minor,
            LocalDate parkedOn) throws SQLException {
        boolean credit = shape == Shape.CREDIT_ITEM;
        String key = "MX-PK-" + UUID.randomUUID();
        UUID run = seedRun(new Line(1,
                credit ? ExternalLineType.CAPTURE : ExternalLineType.REFUND, minor,
                credit ? ItemKeyKind.PSP_CAPTURE_REF : ItemKeyKind.PSP_REFUND_REF, key));
        UUID item = itemOf(run, 1);
        UUID breakId = raise(type, cause, BreakRegister.Subject.externalItem(item), minor);
        as(PLATFORM, uow -> suspense.park(uow, new Suspense.ParkCommand(
                SOURCE, parkedOn,
                List.of(new Suspense.ParkedItem(item, breakId, Money.ofPersisted(minor, EUR, 2),
                        clearing)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
        completeRun(run);
        UUID suspenseItem = id("SELECT id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);
        assertThat(string("SELECT side || '/' || opened_on FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem))
                .isEqualTo((credit ? "CREDIT" : "DEBIT") + "/" + parkedOn);
        return new Subject(breakId, shape, minor, Optional.empty(), Optional.of(item),
                Optional.of(suspenseItem), Optional.of(key));
    }

    /**
     * A parked DEBIT item in another currency (T-1, the Phase 8 -> 9 transition): equal minor
     * units that are no offset pair.
     */
    private static UUID parkedMoney(long minor, CurrencyCode currency) throws SQLException {
        Money money = Money.ofPersisted(minor, currency, 2);
        String key = "MX-FX-" + UUID.randomUUID();
        UUID run = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            run, SOURCE, Optional.of(IDS.next()), RunKind.BATCH, RULE_SET,
                            SETTLED_ON, Optional.of(SEQUENCES.incrementAndGet()), 1,
                            Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            byte[] fingerprint = new byte[32];
            new SecureRandom().nextBytes(fingerprint);
            items.birthAll(app, PLATFORM, List.of(new ExternalItems.NewItem(
                    IDS.next(), run, SOURCE, IDS.next(), 1, ExternalLineType.REFUND,
                    ExpectationDirection.OUTBOUND, money, AccountPurpose.SETTLEMENT_CLEARING,
                    SETTLED_ON, Optional.of(SETTLED_ON), Optional.of(SETTLED_ON), fingerprint,
                    Map.of(ItemKeyKind.PSP_REFUND_REF, key), Instant.now(CLOCK),
                    CorrelationId.generate(IDS))));
            app.commit();
        }
        UUID item = itemOf(run, 1);
        UUID breakId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            register.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED,
                            BreakRegister.Subject.externalItem(item), SOURCE, RULE_SET, money,
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(), PLATFORM,
                            Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
        UUID position = inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .findOperational(uow, AccountPurpose.SETTLEMENT_CLEARING, currency)
                .orElseThrow()
                .id()
                .value());
        as(PLATFORM, uow -> suspense.park(uow, new Suspense.ParkCommand(
                SOURCE, agedOn,
                List.of(new Suspense.ParkedItem(item, breakId, money, position)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
        completeRun(run);
        return id("SELECT id FROM reconciliation.suspense_item WHERE external_item_id = ?",
                item);
    }

    /** A fee line beyond tolerance: value at issue, nothing parked (already expensed). */
    private static Subject feeSubject(long minor) throws SQLException {
        FeeRun fee = feeRun();
        UUID breakId = raise(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE,
                BreakRegister.Subject.externalItem(fee.itemId()), minor);
        checkAndComplete(fee);
        return new Subject(breakId, Shape.NOTHING, minor, Optional.empty(),
                Optional.of(fee.itemId()), Optional.empty(), Optional.empty());
    }

    /** A break standing on a stored decision - a timing difference's or a diverged replay's. */
    private static Subject decisionSubject(BreakType type, BreakCause cause, long minor)
            throws SQLException {
        FeeRun fee = feeRun();
        UUID decision = IDS.next();
        as(PLATFORM, uow -> {
            matchingStore.insertDecision(uow, new MatchingStore.NewDecision(
                    decision, fee.itemId(), fee.runId(), DecisionOrigin.RUN, RULE_SET,
                    Optional.of(1), Optional.of(Cardinality.ONE_TO_ONE),
                    Optional.of(KeyKind.PSP_CAPTURE_REF), DecisionOutcome.PARKED,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    PLATFORM, Instant.now(CLOCK), LocalDate.now(CLOCK),
                    CorrelationId.generate(IDS),
                    MatchingStore.Basis.match(
                            DecisionVerdict.AMBIGUOUS, JudgedStatus.PENDING, minor, false)));
            return null;
        });
        UUID breakId = raise(type, cause, BreakRegister.Subject.decision(decision), minor);
        checkAndComplete(fee);
        return new Subject(breakId, Shape.NOTHING, minor, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
    }

    /**
     * An ambiguous item of {@code shape}'s side - a CREDIT capture against two captures, a DEBIT
     * refund against two refunds: the engine never produces one (one expectation per key), so
     * the stored decision and its two candidates are planted as the engine would record them.
     */
    private static Subject ambiguous(long minor, Shape shape) throws SQLException {
        boolean credit = shape == Shape.CREDIT_ITEM;
        ExpectationDirection direction =
                credit ? ExpectationDirection.INBOUND : ExpectationDirection.OUTBOUND;
        KeyKind keyKind = credit ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF;
        Seeded chosen = openExpectation(minor, direction);
        Seeded other = openExpectation(minor, direction);
        UUID run = seedRun(new Line(1,
                credit ? ExternalLineType.CAPTURE : ExternalLineType.REFUND, minor,
                credit ? ItemKeyKind.PSP_CAPTURE_REF : ItemKeyKind.PSP_REFUND_REF,
                "MX-MM-" + UUID.randomUUID()));
        UUID item = itemOf(run, 1);
        as(PLATFORM, uow -> {
            UUID decision = IDS.next();
            matchingStore.insertDecision(uow, new MatchingStore.NewDecision(
                    decision, item, run, DecisionOrigin.RUN, RULE_SET,
                    Optional.of(credit ? 1 : 2), Optional.of(Cardinality.ONE_TO_ONE),
                    Optional.of(keyKind), DecisionOutcome.PARKED, Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), PLATFORM,
                    Instant.now(CLOCK), LocalDate.now(CLOCK), CorrelationId.generate(IDS),
                    MatchingStore.Basis.match(
                            DecisionVerdict.AMBIGUOUS, JudgedStatus.PENDING, minor, false)));
            matchingStore.insertCandidates(uow, decision, matchingStore.lockExpectations(uow,
                    List.of(chosen.id(), other.id()),
                    Map.of(chosen.id(), keyKind, other.id(), keyKind)));
            return null;
        });
        UUID breakId = raise(BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                BreakRegister.Subject.externalItem(item), minor);
        as(PLATFORM, uow -> suspense.park(uow, new Suspense.ParkCommand(
                SOURCE, agedOn,
                List.of(new Suspense.ParkedItem(item, breakId, Money.ofPersisted(minor, EUR, 2),
                        clearing)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
        completeRun(run);
        UUID suspenseItem = id("SELECT id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);
        return new Subject(breakId, shape, minor, Optional.empty(),
                Optional.of(item), Optional.of(suspenseItem), Optional.empty(), Optional.empty(),
                Optional.of(chosen.id()));
    }

    private static FeeRun feeRun() throws SQLException {
        UUID run = seedRun(new Line(1, ExternalLineType.PROCESSING_FEE, 1_00,
                ItemKeyKind.ORIGINAL_REF, "MX-FEE-" + UUID.randomUUID()));
        return new FeeRun(run, itemOf(run, 1));
    }

    private static FeeRun completedFeeRun() throws SQLException {
        FeeRun fee = feeRun();
        checkAndComplete(fee);
        return fee;
    }

    private static void checkAndComplete(FeeRun fee) {
        as(PLATFORM, uow -> matchingStore.markItemChecked(uow, fee.itemId(), PLATFORM,
                Instant.now(CLOCK), CorrelationId.generate(IDS)));
        completeRun(fee.runId());
    }

    private static void completeRun(UUID run) {
        as(PLATFORM, uow -> {
            matchingStore.markRunInProgress(uow, run, PLATFORM, Instant.now(CLOCK));
            matchingStore.completeRun(uow, run, PLATFORM, Instant.now(CLOCK));
            return null;
        });
    }

    private static UUID seedRun(Line... lines) throws SQLException {
        return seedRunIn(SOURCE, RULE_SET, lines);
    }

    private static UUID seedRunIn(UUID source, UUID ruleSet, Line... lines) throws SQLException {
        UUID newRun = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            newRun, source, Optional.of(IDS.next()), RunKind.BATCH, ruleSet,
                            SETTLED_ON, Optional.of(SEQUENCES.incrementAndGet()), lines.length,
                            Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                new SecureRandom().nextBytes(fingerprint);
                ExpectationDirection direction =
                        line.type() == ExternalLineType.CAPTURE
                                ? ExpectationDirection.INBOUND
                                : ExpectationDirection.OUTBOUND;
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(), newRun, source, IDS.next(), line.lineNo(),
                                line.type(), direction, Money.ofPersisted(line.minor(), EUR, 2),
                                AccountPurpose.SETTLEMENT_CLEARING, SETTLED_ON,
                                Optional.of(SETTLED_ON), Optional.of(SETTLED_ON), fingerprint,
                                Map.of(line.keyKind(), line.key()), Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            items.birthAll(app, PLATFORM, newItems);
            app.commit();
        }
        return newRun;
    }

    private static Seeded openExpectation(long minor, ExpectationDirection direction)
            throws SQLException {
        return openExpectation("MX-EX-" + UUID.randomUUID(), minor, direction, FAR);
    }

    private static Seeded openExpectation(
            String key, long minor, ExpectationDirection direction, LocalDate expectedBy)
            throws SQLException {
        return openExpectationIn(SOURCE, RULE_SET, key, minor, direction, expectedBy);
    }

    private static Seeded openExpectationIn(UUID source, UUID ruleSet,
            String key, long minor, ExpectationDirection direction, LocalDate expectedBy)
            throws SQLException {
        String operationRef = UUID.randomUUID().toString();
        ExpectationKind kind = direction == ExpectationDirection.INBOUND
                ? ExpectationKind.CARD_CAPTURE : ExpectationKind.CARD_REFUND;
        KeyKind keyKind = direction == ExpectationDirection.INBOUND
                ? KeyKind.PSP_CAPTURE_REF : KeyKind.PSP_REFUND_REF;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            expectations.open(
                    app,
                    new NewExpectation(
                            kind, operationRef, "p8tst2:" + operationRef, source,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing, direction,
                            Money.ofPersisted(minor, EUR, 2), Optional.of(IDS.next()),
                            SETTLED_ON, Optional.empty(), expectedBy, ruleSet,
                            List.of(new NewExpectation.ExpectationKey(keyKind, key)),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
        UUID id = (UUID) one("SELECT id FROM reconciliation.expectation WHERE operation_ref = ?"
                + " AND kind = ?", operationRef, kind.name());
        return new Seeded(id, key);
    }

    private static UUID raise(BreakType type, BreakCause cause, BreakRegister.Subject subject,
            long minor) throws SQLException {
        BreakRegister.Raised raised = raiseRaised(type, cause, subject, minor);
        assertThat(raised.created()).as("a fresh %s break", type).isTrue();
        return raised.breakId();
    }

    private static BreakRegister.Raised raiseRaised(BreakType type, BreakCause cause,
            BreakRegister.Subject subject, long minor) throws SQLException {
        return raiseIn(SOURCE, RULE_SET, type, cause, subject, minor);
    }

    private static BreakRegister.Raised raiseIn(UUID source, UUID ruleSet, BreakType type,
            BreakCause cause, BreakRegister.Subject subject, long minor) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            BreakRegister.Raised raised = register.raise(
                    app,
                    new BreakRegister.NewBreak(
                            IDS.next(), type, cause, subject, source, ruleSet,
                            Money.ofPersisted(minor, EUR, 2), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
            return raised;
        }
    }

    private static UUID openWallet() {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                        AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next()))
                .account()
                .id()
                .value());
    }

    private static UUID operational(AccountPurpose purpose) {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .findOperational(uow, purpose, EUR).orElseThrow().id().value());
    }

    /**
     * The private rule set: the seeded v1's pinned minimum age and high value, copied (never a
     * private figure), with the capture, refund and correction rules the matcher scenarios need.
     */
    private static void seedRuleSet(UUID source, UUID ruleSet) {
        inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, ?, ?, 'test', NULL,"
                            + " 'ResolutionMatrixDatabaseTest private rule set', now(),"
                            + " 'p8-tst-002-test')",
                    ruleSet, source, gainMinAgeDays, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 2, 'REFUND', 'PSP_REFUND_REF', 'CARD_REFUND',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 3, 'COUNTERPARTY_ADJUSTMENT', 'ORIGINAL_REF', NULL,"
                            + " 'CORRECTION', false, 48)",
                    ruleSet, ruleSet, ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2)",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', ?)",
                    ruleSet, highValueMinor);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by ="
                            + " 'test-activator', decided_at = now() WHERE id = ? AND status ="
                            + " 'PROPOSED'",
                    ruleSet);
            return null;
        });
    }

    private static Matching matching() {
        return matching((unitOfWork, subject) ->
                InternalReferenceLookup.InternalReference.unknown());
    }

    private static Matching matching(InternalReferenceLookup lookup) {
        return new Matching(
                matchingStore, new MatchingRules(), register, suspense,
                ResolutionFixtures.resolutions(IDS, CLOCK),
                lookup,
                new JdbcLedgerAccountStore(), new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS,
                CLOCK, new Matching.Config(200, 2), runner());
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(new JdbcIdempotencyRecordStore(), CLOCK,
                        Duration.ofDays(1), Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, CLOCK, PostingObserver.NONE);
    }

    private static TransactionRunner runner() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                return inCommittedTransaction(work::apply);
            }
        };
    }

    // ================================================================= the machine's doors

    /** A request for {@code kind} with its operands: a wallet, the spare partner, a candidate. */
    private static ResolutionMachine.ProposalRequest requestFor(
            ResolutionKind kind, ResolutionReasonCode code) {
        return new ResolutionMachine.ProposalRequest(kind, code, "the investigator's account",
                kind == ResolutionKind.TRANSFER_TO_ACCOUNT ? Optional.of(wallet) : Optional.empty(),
                kind == ResolutionKind.OFFSET_SUSPENSE
                        ? Optional.of(sparePartnerItem) : Optional.empty(),
                kind == ResolutionKind.MANUAL_MATCH ? Optional.of(IDS.next()) : Optional.empty());
    }

    /** The crossing's own request: its partner for an offset, its candidate for a match. */
    private static ResolutionMachine.ProposalRequest requestFor(
            ResolutionKind kind, ResolutionReasonCode code, Subject subject) {
        return new ResolutionMachine.ProposalRequest(kind, code, "the investigator's account",
                kind == ResolutionKind.TRANSFER_TO_ACCOUNT ? Optional.of(wallet) : Optional.empty(),
                kind == ResolutionKind.OFFSET_SUSPENSE
                        ? Optional.of(subject.partnerItem().orElse(sparePartnerItem))
                        : Optional.empty(),
                kind == ResolutionKind.MANUAL_MATCH
                        ? Optional.of(subject.chosen().orElse(IDS.next()))
                        : Optional.empty());
    }

    /** An offset's request naming its partner item (T-1, T-7). */
    private static ResolutionMachine.ProposalRequest offsetRequest(UUID partnerItem) {
        return new ResolutionMachine.ProposalRequest(ResolutionKind.OFFSET_SUSPENSE,
                ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY, "the two net",
                Optional.empty(), Optional.of(partnerItem), Optional.empty());
    }

    private static ResolutionMachine.Proposed propose(
            Actor actor, UUID breakId, ResolutionMachine.ProposalRequest request) {
        return as(actor, uow -> machine.propose(uow, breakId, request, actor,
                CorrelationContext.current().orElseThrow().correlationId()));
    }

    private static ResolutionMachine.Decided approve(Actor actor, UUID resolutionId) {
        return as(actor, uow -> machine.approve(uow, resolutionId, actor,
                CorrelationContext.current().orElseThrow().correlationId()));
    }

    private static ResolutionMachine.Decided reject(
            Actor actor, UUID resolutionId, String reason) {
        return as(actor, uow -> machine.reject(uow, resolutionId, reason, actor,
                CorrelationContext.current().orElseThrow().correlationId()));
    }

    private static ResolutionMachine.Decided withdraw(Actor actor, UUID resolutionId) {
        return as(actor, uow -> machine.withdraw(uow, resolutionId, actor,
                CorrelationContext.current().orElseThrow().correlationId()));
    }

    /** A race's one effect: one entry, one release, one BreakResolved, the break RESOLVED. */
    private static void assertOneEffect(UUID resolution, Subject subject) throws SQLException {
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                resolution.toString())).as("one entry").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?",
                subject.suspenseItemId().orElseThrow())).as("one release").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", subject.breakId()))
                .as("one BreakResolved").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.ResolutionApproved' AND target_id = ?", resolution.toString()))
                .as("one approval audited").isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                subject.breakId())).isEqualTo("RESOLVED");
    }

    // ================================================================= nothing written

    @FunctionalInterface
    private interface Act {
        void run() throws Exception;
    }

    /**
     * The refusal and its silence: {@code act} throws {@code refusal}, and every record a
     * resolution act could write - resolutions and their history, ledger proposals and entries,
     * audit records, outbox events, releases, and the break's own row and history - is exactly
     * as it was.
     */
    private static void refusedWithNothingWritten(
            UUID breakId, Class<? extends Throwable> refusal, String what, Act act) {
        String before = snapshot(breakId);
        assertThatThrownBy(act::run).as(what).isInstanceOf(refusal);
        assertThat(snapshot(breakId)).as("%s: nothing written", what).isEqualTo(before);
    }

    private static String snapshot(UUID breakId) {
        try {
            return string("SELECT (SELECT count(*) FROM reconciliation.resolution) || '|' ||"
                    + " (SELECT count(*) FROM reconciliation.resolution_event) || '|' ||"
                    + " (SELECT count(*) FROM ledger.adjustment_proposal) || '|' ||"
                    + " (SELECT count(*) FROM ledger.journal_entry) || '|' ||"
                    + " (SELECT count(*) FROM platform.audit_record) || '|' ||"
                    + " (SELECT count(*) FROM platform.outbox_event) || '|' ||"
                    + " (SELECT count(*) FROM reconciliation.suspense_release) || '|' ||"
                    + " (SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?)"
                    + " || '|' || (SELECT status || '/' || residual_version FROM"
                    + " reconciliation.break WHERE id = ?)", breakId, breakId);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not snapshot", failure);
        }
    }

    // ================================================================= plumbing

    @FunctionalInterface
    private interface Work<R> {
        R apply(Connection unitOfWork) throws SQLException;
    }

    /** One committed transaction as {@code actor}, inside its own correlation scope. */
    @SuppressWarnings("try")
    private static <R> R as(Actor actor, Work<R> work) {
        try (SecurityContext.Scope identity = SecurityContext.enter(actor);
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return inCommittedTransaction(work);
        }
    }

    private static <R> R inCommittedTransaction(Work<R> work) {
        try (Connection unitOfWork = DatabaseRoles.application()) {
            unitOfWork.setAutoCommit(false);
            try {
                R result = work.apply(unitOfWork);
                unitOfWork.commit();
                return result;
            } catch (RuntimeException | SQLException failure) {
                unitOfWork.rollback();
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new ReconciliationStorageException("test transaction failed", failure);
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("test transaction failed", failure);
        }
    }

    private static <R> List<R> race(int racers, java.util.function.IntFunction<R> act)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<R>> futures = new ArrayList<>();
            for (int racer = 0; racer < racers; racer++) {
                int index = racer;
                Callable<R> task = () -> {
                    start.await();
                    return act.apply(index);
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<R> outcomes = new ArrayList<>();
            for (Future<R> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String sqlState(Connection connection, String sql) throws SQLException {
        Savepoint before = connection.setSavepoint();
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.rollback(before);
            return null;
        } catch (SQLException refusal) {
            connection.rollback(before);
            return refusal.getSQLState() == null ? "refused" : refusal.getSQLState();
        }
    }

    private static List<String> rowsOn(Connection connection, String sql) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                found.add(result.getString(1));
            }
        }
        return found;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private static List<String> entryLines(UUID entry) throws SQLException {
        return rows("SELECT ledger_account_id || '>' || direction || '>' || amount_minor FROM"
                + " ledger.journal_line WHERE entry_id = ? ORDER BY seq", entry);
    }

    private static List<String> proposalLines(UUID proposal) throws SQLException {
        return rows("SELECT ledger_account_id || '>' || direction || '>' || amount_minor FROM"
                + " ledger.adjustment_proposal_line WHERE proposal_id = ? ORDER BY seq",
                proposal);
    }

    private static UUID itemOf(UUID run, int lineNo) throws SQLException {
        return id("SELECT id FROM reconciliation.external_item WHERE run_id = ? AND line_no = "
                + lineNo, run);
    }

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private static List<String> rows(String sql, Object... args) throws SQLException {
        application.rollback();
        List<String> found = new ArrayList<>();
        try (PreparedStatement read = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                read.setObject(i + 1, args[i]);
            }
            try (ResultSet result = read.executeQuery()) {
                while (result.next()) {
                    found.add(result.getString(1));
                }
            }
        }
        return found;
    }

    private static UUID id(String sql, Object... args) throws SQLException {
        return (UUID) one(sql, args);
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        Object value = one(sql, args);
        return value == null ? null : value.toString();
    }

    private static Object one(String sql, Object... args) throws SQLException {
        application.rollback();
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                Object value = row.getObject(1);
                return value instanceof java.sql.Date date ? date.toLocalDate() : value;
            }
        }
    }
}
