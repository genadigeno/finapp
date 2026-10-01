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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * The person's resolution machine against the real schema (`P8-TSK-015`, ADR-0071): every kind's
 * template posted exactly as derived and checked line for line against {@code ledger.journal_line},
 * the closing effects on the expectation, the item and the suspense, the refusals at the domain
 * and at the database, the ten-way approval race, evidence against a pending proposal both ways,
 * the stale approval, and the late line after a write-off parking as a recovery.
 *
 * <p>The suite owns private sources — one for the templates, two for the gain's minimum age, whose
 * {@code gain_min_age_days} are computed from the DATABASE's own date at seeding so "one day short"
 * and "exactly at" are judged on the clock the rule is judged on.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the resolution machine (P8-TSK-015)")
class ResolutionMachineDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final Actor PROPOSER = new Actor("op-resolver-a", ActorType.EMPLOYEE);
    private static final Actor APPROVER = new Actor("op-resolver-b", ActorType.EMPLOYEE);
    private static final Actor THIRD = new Actor("op-resolver-c", ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final LocalDate FAR = LocalDate.parse("2027-12-31");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000);

    // Private per run: a reused container never carries a stale gain age into this run.
    private static final UUID SOURCE = IDS.next();
    private static final UUID RULE_SET = IDS.next();
    private static final UUID GAIN_AT_SOURCE = IDS.next();
    private static final UUID GAIN_AT_RULE_SET = IDS.next();
    private static final UUID GAIN_SHORT_SOURCE = IDS.next();
    private static final UUID GAIN_SHORT_RULE_SET = IDS.next();

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static JdbcBreakRegister register;
    private static JdbcMatchingStore matchingStore;
    private static Suspense suspense;
    private static ResolutionMachine machine;
    private static LocalDate parkedOn;
    private static UUID clearing;
    private static UUID suspenseAccount;
    private static UUID losses;
    private static UUID gains;
    private static UUID wallet;

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
                        ResolutionFixtures.resolutions(IDS, CLOCK),
                        new JdbcRuleSets(),
                        ResolutionFixtures.adjustments(IDS, CLOCK),
                        new JdbcLedgerAccountStore(),
                        new JdbcOutboxWriter(),
                        new JdbcAuditWriter(),
                        IDS,
                        CLOCK);
        LocalDate today = (LocalDate) one("SELECT current_date");
        parkedOn = today.minusDays(10);
        seedRuleSet(SOURCE, RULE_SET, 90);
        seedRuleSet(GAIN_AT_SOURCE, GAIN_AT_RULE_SET, 10);
        seedRuleSet(GAIN_SHORT_SOURCE, GAIN_SHORT_RULE_SET, 11);
        clearing = operational(AccountPurpose.SETTLEMENT_CLEARING);
        suspenseAccount = operational(AccountPurpose.SUSPENSE_UNMATCHED);
        losses = operational(AccountPurpose.RECONCILIATION_LOSSES);
        gains = operational(AccountPurpose.RECONCILIATION_GAINS);
        wallet = openWallet(EUR);
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ----------------------------------------------------------------- write-off

    @Test
    @Order(1)
    @DisplayName("a write-off of an INBOUND remainder: proposed by one, refused to the same"
            + " person, approved by a second - DR losses / CR the position exactly, the"
            + " expectation RESOLVED_BY_ADJUSTMENT, the break RESOLVED; a retry converges")
    void aWriteOffPostsTheTemplateUnderFourEyes() throws Exception {
        Seeded expectation = openExpectation("WO", 100_00, ExpectationDirection.INBOUND);
        UUID breakId = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), 100_00, SOURCE, RULE_SET);

        ResolutionMachine.Proposed proposed =
                propose(PROPOSER, breakId, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        assertThat(proposed.status()).isEqualTo(ResolutionStatus.PROPOSED);
        assertThat(proposed.fourEyes()).isTrue();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .isEqualTo("RESOLUTION_PROPOSED");
        UUID proposal = proposed.adjustmentProposalId().orElseThrow();
        assertThat(string("SELECT origin || '/' || reason_code || '/' || status FROM"
                + " ledger.adjustment_proposal WHERE id = ?", proposal))
                .isEqualTo("RECONCILIATION/RECONCILIATION_WRITE_OFF/PROPOSED");
        assertThat(string("SELECT reason FROM ledger.adjustment_proposal WHERE id = ?",
                proposal))
                .as("the ledger's reason is identifiers and codes - never the narrative")
                .isEqualTo(ResolutionMachine.ledgerReason(proposed.resolutionId(),
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED));
        assertThat(proposalLines(proposal))
                .containsExactly(losses + ">DEBIT>10000", clearing + ">CREDIT>10000");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                proposed.resolutionId().toString()))
                .as("nothing posts at proposal").isZero();

        assertThatThrownBy(() -> propose(THIRD, breakId, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .isInstanceOf(ResolutionMachine.ResolutionAlreadyProposed.class);
        assertThatThrownBy(() -> approve(PROPOSER, proposed.resolutionId()))
                .as("the proposer never approves their own")
                .isInstanceOf(ResolutionMachine.SelfApprovalRefused.class);
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId())).isEqualTo("PROPOSED");

        ResolutionMachine.Decided approved = approve(APPROVER, proposed.resolutionId());
        UUID entry = approved.journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(losses + ">DEBIT>10000", clearing + ">CREDIT>10000");
        assertThat(string("SELECT entry_type FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo("ADJUSTMENT");
        assertThat(string("SELECT status || '/' || resolved_minor FROM"
                + " reconciliation.expectation WHERE id = ?", expectation.id()))
                .isEqualTo("RESOLVED_BY_ADJUSTMENT/10000");
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'RESOLVED'", expectation.id()))
                .isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .isEqualTo("RESOLVED");
        assertThat(string("SELECT status || '/' || decided_by || '/' || journal_entry_id FROM"
                + " reconciliation.resolution WHERE id = ?", proposed.resolutionId()))
                .isEqualTo("APPROVED/" + APPROVER.id() + "/" + entry);
        assertThat(string("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                proposal)).isEqualTo("APPROVED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", breakId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation IN ('reconciliation.ResolutionProposed',"
                + " 'reconciliation.ResolutionApproved')",
                proposed.resolutionId().toString())).isEqualTo(2);

        ResolutionMachine.Decided retried = approve(APPROVER, proposed.resolutionId());
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.journalEntryId()).contains(entry);
        assertThatThrownBy(() -> approve(THIRD, proposed.resolutionId()))
                .isInstanceOf(ResolutionMachine.ResolutionNotPending.class);
        assertThatThrownBy(() -> propose(PROPOSER, breakId, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .isInstanceOf(BreakCaseFile.BreakTerminal.class);
    }

    // ----------------------------------------------------------------- transfer

    @Test
    @Order(2)
    @DisplayName("a transfer credits only an owned, same-currency, postable target - refused"
            + " otherwise at proposal; a target closed before approval fails at the ledger"
            + " with nothing written")
    void aTransferCreditsOnlyAnOwnedTarget() throws Exception {
        Seeded outbound = openExpectation("TR", 40_00, ExpectationDirection.OUTBOUND);
        UUID breakId = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(outbound.id()), 40_00, SOURCE, RULE_SET);

        assertThatThrownBy(() -> propose(PROPOSER, breakId, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, Optional.of(clearing)))
                .as("an operational position is never a transfer's target")
                .isInstanceOf(ResolutionMachine.ResolutionTargetRefused.class);
        UUID dollars = openWallet(CurrencyCode.of("USD"));
        assertThatThrownBy(() -> propose(PROPOSER, breakId, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, Optional.of(dollars)))
                .as("never across currencies (INV-MON-04)")
                .isInstanceOf(ResolutionMachine.ResolutionTargetRefused.class);
        assertThatThrownBy(() -> propose(PROPOSER, breakId, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, Optional.of(IDS.next())))
                .isInstanceOf(ResolutionMachine.ResolutionTargetRefused.class);
        assertThatThrownBy(() -> propose(PROPOSER, breakId, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .as("an OUTBOUND remainder is never written off to losses")
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .as("every refusal wrote nothing").isEqualTo("OPEN");

        ResolutionMachine.Proposed proposed =
                propose(PROPOSER, breakId, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, Optional.of(wallet));
        UUID entry = approve(APPROVER, proposed.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(clearing + ">DEBIT>4000", wallet + ">CREDIT>4000");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                outbound.id())).isEqualTo("RESOLVED_BY_ADJUSTMENT");

        // A target closed between proposal and approval: the ledger refuses the posting.
        Seeded second = openExpectation("TR2", 15_00, ExpectationDirection.OUTBOUND);
        UUID secondBreak = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(second.id()), 15_00, SOURCE, RULE_SET);
        UUID closing = openWallet(EUR, IDS.next());
        ResolutionMachine.Proposed toClosing =
                propose(PROPOSER, secondBreak, ResolutionKind.TRANSFER_TO_ACCOUNT,
                        ResolutionReasonCode.FUNDS_ATTRIBUTED, Optional.of(closing));
        inCommittedTransaction(uow -> new JdbcLedgerAccountStore().moveStatus(
                uow, com.finapp.ledger.LedgerAccountId.of(closing), LedgerAccountStatus.ACTIVE,
                LedgerAccountStatus.CLOSED, Instant.now(CLOCK)));
        assertThatThrownBy(() -> approve(APPROVER, toClosing.resolutionId()))
                .isInstanceOf(LedgerAccountNotPostableException.class);
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                toClosing.resolutionId())).isEqualTo("PROPOSED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", secondBreak))
                .isEqualTo("RESOLUTION_PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                toClosing.resolutionId().toString())).isZero();
        withdraw(PROPOSER, toClosing.resolutionId());
    }

    // ----------------------------------------------------------------- parked value

    @Test
    @Order(3)
    @DisplayName("parked CREDIT value: a gain only at the pinned age on the database clock"
            + " (one day short refused), DR suspense / CR gains, the item RESOLVED and its"
            + " suspense released naming the resolution; never written off")
    void aParkedCreditIsAGainOnlyWhenAged() throws Exception {
        Parked short1 = parked(GAIN_SHORT_SOURCE, GAIN_SHORT_RULE_SET,
                ExternalLineType.CAPTURE, 12_00, BreakType.UNKNOWN_EXTERNAL,
                BreakCause.PARKED_ON_RECEIPT);
        assertThatThrownBy(() -> propose(PROPOSER, short1.breakId(),
                        ResolutionKind.RECOGNISE_GAIN, ResolutionReasonCode.UNATTRIBUTABLE_AGED,
                        Optional.empty()))
                .isInstanceOf(ResolutionMachine.GainNotYetEligible.class);
        assertThatThrownBy(() -> propose(PROPOSER, short1.breakId(), ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .as("a CREDIT item is never written off: that would be a gain")
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);

        Parked aged = parked(GAIN_AT_SOURCE, GAIN_AT_RULE_SET, ExternalLineType.CAPTURE,
                33_00, BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT);
        ResolutionMachine.Proposed gain = propose(PROPOSER, aged.breakId(),
                ResolutionKind.RECOGNISE_GAIN, ResolutionReasonCode.UNATTRIBUTABLE_AGED,
                Optional.empty());
        UUID entry = approve(APPROVER, gain.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(suspenseAccount + ">DEBIT>3300", gains + ">CREDIT>3300");
        assertThat(string("SELECT status || '/' || released_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?", aged.suspenseItemId()))
                .isEqualTo("RELEASED/3300");
        assertThat(string("SELECT cause || '/' || cause_ref FROM"
                + " reconciliation.suspense_release WHERE item_id = ?", aged.suspenseItemId()))
                .isEqualTo("RESOLUTION/resolution=" + gain.resolutionId());
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                aged.itemId())).isEqualTo("RESOLVED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                aged.breakId())).isEqualTo("RESOLVED");

        // The same parked CREDIT value may instead be attributed to its owner.
        Parked owed = parked(SOURCE, RULE_SET, ExternalLineType.CAPTURE, 21_00,
                BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT);
        ResolutionMachine.Proposed transfer = propose(PROPOSER, owed.breakId(),
                ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionReasonCode.FUNDS_ATTRIBUTED,
                Optional.of(wallet));
        UUID transferred =
                approve(APPROVER, transfer.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(transferred))
                .containsExactly(suspenseAccount + ">DEBIT>2100", wallet + ">CREDIT>2100");
    }

    @Test
    @Order(4)
    @DisplayName("a DEBIT item is written off DR losses / CR suspense; a gain is outside a"
            + " refund break's row")
    void aDebitItemIsWrittenOff() throws Exception {
        Parked debit = parked(SOURCE, RULE_SET, ExternalLineType.REFUND, 9_00,
                BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED);
        assertThatThrownBy(() -> propose(PROPOSER, debit.breakId(),
                        ResolutionKind.RECOGNISE_GAIN, ResolutionReasonCode.UNATTRIBUTABLE_AGED,
                        Optional.empty()))
                .as("the value is a customer's or a merchant's: never the platform's gain")
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);
        ResolutionMachine.Proposed writeOff = propose(PROPOSER, debit.breakId(),
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.COUNTERPARTY_ERROR_CONFIRMED,
                Optional.empty());
        UUID entry = approve(APPROVER, writeOff.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(losses + ">DEBIT>900", suspenseAccount + ">CREDIT>900");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                debit.suspenseItemId())).isEqualTo("RELEASED");
    }

    @Test
    @Order(5)
    @DisplayName("an offset pairs two breaks' items of equal amount and opposite sides: both"
            + " released, both breaks RESOLVED, nothing posted; unequal or same-side refused")
    void anOffsetClosesBothBreaks() throws Exception {
        Parked credit = parked(SOURCE, RULE_SET, ExternalLineType.CAPTURE, 20_00,
                BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT);
        Parked debit = parked(SOURCE, RULE_SET, ExternalLineType.REFUND, 20_00,
                BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED);
        Parked unequal = parked(SOURCE, RULE_SET, ExternalLineType.REFUND, 19_99,
                BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED);
        Parked sameSide = parked(SOURCE, RULE_SET, ExternalLineType.CAPTURE, 20_00,
                BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT);
        for (UUID refused : List.of(unequal.suspenseItemId(), sameSide.suspenseItemId(),
                credit.suspenseItemId())) {
            assertThatThrownBy(() -> proposeOffset(PROPOSER, credit.breakId(), refused))
                    .isInstanceOf(ResolutionMachine.ResolutionTargetRefused.class);
        }
        ResolutionMachine.Proposed offset =
                proposeOffset(PROPOSER, credit.breakId(), debit.suspenseItemId());
        ResolutionMachine.Decided approved = approve(APPROVER, offset.resolutionId());
        assertThat(approved.journalEntryId()).as("SUSPENSE_UNMATCHED already nets them")
                .isEmpty();
        for (Parked side : List.of(credit, debit)) {
            assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                    side.suspenseItemId())).isEqualTo("RELEASED");
            assertThat(string("SELECT cause FROM reconciliation.suspense_release WHERE"
                    + " item_id = ?", side.suspenseItemId())).isEqualTo("OFFSET_SUSPENSE");
            assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                    side.breakId())).isEqualTo("RESOLVED");
            assertThat(string("SELECT detail FROM reconciliation.break_event WHERE break_id = ?"
                    + " AND event_type = 'RESOLVED'", side.breakId()))
                    .contains("resolution=" + offset.resolutionId());
            assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                    side.itemId())).isEqualTo("RESOLVED");
        }
    }

    // ----------------------------------------------------------------- acknowledgement

    @Test
    @Order(6)
    @DisplayName("a zero-value timing difference is one person's acknowledgement, born"
            + " APPROVED; a duplicate internal of value is two people's; a code outside the"
            + " kind's subset and a kind outside the type's row are refused")
    void acknowledgementsAndTheClosedVocabulary() throws Exception {
        Seeded late = openExpectation("TIME", 25_00, ExpectationDirection.INBOUND,
                SETTLED_ON.minusDays(10));
        UUID run = seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 25_00,
                ItemKeyKind.PSP_CAPTURE_REF, late.key()));
        matching().sweep();
        UUID timingBreak = id("SELECT b.id FROM reconciliation.break b JOIN"
                + " reconciliation.match_decision d ON d.id = b.decision_id WHERE"
                + " d.external_item_id = ? AND b.type = 'TIMING_DIFFERENCE'", itemOf(run, 1));
        ResolutionMachine.Proposed acknowledged = propose(PROPOSER, timingBreak,
                ResolutionKind.ACKNOWLEDGE, ResolutionReasonCode.TIMING_CONFIRMED,
                Optional.empty());
        assertThat(acknowledged.status()).isEqualTo(ResolutionStatus.APPROVED);
        assertThat(acknowledged.fourEyes()).isFalse();
        assertThat(acknowledged.adjustmentProposalId()).isEmpty();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", timingBreak))
                .isEqualTo("RESOLVED");
        assertThat(string("SELECT reason FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.ResolutionApproved'",
                acknowledged.resolutionId().toString()))
                .as("one act, one reasoned record")
                .isEqualTo("kind=ACKNOWLEDGE, reasonCode=TIMING_CONFIRMED");

        Seeded collided = openExpectation("DUP", 50_00, ExpectationDirection.INBOUND);
        UUID duplicate = raise(BreakType.DUPLICATE_INTERNAL, BreakCause.KEY_COLLISION,
                BreakRegister.Subject.expectation(collided.id()), 50_00, SOURCE, RULE_SET);
        assertThatThrownBy(() -> propose(PROPOSER, duplicate, ResolutionKind.ACKNOWLEDGE,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .isInstanceOf(ResolutionMachine.ReasonCodeNotAllowed.class);
        ResolutionMachine.Proposed twoPeople = propose(PROPOSER, duplicate,
                ResolutionKind.ACKNOWLEDGE, ResolutionReasonCode.INTERNAL_PROCESSING_ERROR,
                Optional.empty());
        assertThat(twoPeople.status()).isEqualTo(ResolutionStatus.PROPOSED);
        assertThat(twoPeople.fourEyes()).isTrue();
        assertThat(approve(APPROVER, twoPeople.resolutionId()).journalEntryId()).isEmpty();
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                collided.id())).as("an acknowledgement disposes of nothing").isEqualTo("OPEN");

        // The value-bearing acknowledgement the backlog names: a fee beyond tolerance, whose
        // deviation is value at issue although nothing of it sits in a position.
        UUID feeRun = seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.PROCESSING_FEE,
                2_00, ItemKeyKind.ORIGINAL_REF, "FEE-" + UUID.randomUUID()));
        UUID feeItem = itemOf(feeRun, 1);
        UUID feeBreak = raise(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE,
                BreakRegister.Subject.externalItem(feeItem), 2_00, SOURCE, RULE_SET);
        as(PLATFORM, uow -> matchingStore.markItemChecked(uow, feeItem, PLATFORM,
                Instant.now(CLOCK), CorrelationId.generate(IDS)));
        completeRun(feeRun);
        ResolutionMachine.Proposed fee = propose(PROPOSER, feeBreak, ResolutionKind.ACKNOWLEDGE,
                ResolutionReasonCode.FEE_ACCEPTED_AS_CHARGED, Optional.empty());
        assertThat(fee.status()).as("a fee's deviation is value at issue: two people")
                .isEqualTo(ResolutionStatus.PROPOSED);
        assertThatThrownBy(() -> approve(PROPOSER, fee.resolutionId()))
                .isInstanceOf(ResolutionMachine.SelfApprovalRefused.class);
        assertThat(approve(APPROVER, fee.resolutionId()).status())
                .isEqualTo(ResolutionStatus.APPROVED);

        UUID statement = raise(BreakType.SETTLEMENT_MISMATCH, BreakCause.STATEMENT_GAP,
                BreakRegister.Subject.run(run), 0, SOURCE, RULE_SET);
        assertThatThrownBy(() -> propose(PROPOSER, statement, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .as("a statement cause closes only by evidence")
                .isInstanceOf(ResolutionMachine.ResolutionKindNotAllowed.class);
    }

    // ----------------------------------------------------------------- the database rank

    @Test
    @Order(7)
    @DisplayName("V007 binds every raw writer: four-eyes derived and distinct, the pairing and"
            + " the vocabulary closed, the machine's edges, the frozen payload, the entry"
            + " named once, no delete, and a break's type frozen under a proposal")
    void theDatabaseRankBindsRawSql() throws Exception {
        Seeded expectation = openExpectation("RAW", 7_00, ExpectationDirection.INBOUND);
        UUID breakId = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), 7_00, SOURCE, RULE_SET);
        String insert = "INSERT INTO reconciliation.resolution (id, break_id, kind, status,"
                + " reason_code, narrative, four_eyes, proposed_amount_minor, currency, scale,"
                + " residual_version, rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                + " decided_by, decided_by_type, decided_at, created_at, status_changed_at,"
                + " correlation_id) VALUES (?, ?, ?, ?, ?, 'raw probe', ?, ?, 'EUR', 2, 0, ?,"
                + " 'op-1', 'EMPLOYEE', now(), ?, ?, ?, now(), now(), 'corr')";
        record Raw(String kind, String status, String reason, boolean fourEyes, long amount,
                String decidedBy, String expected) {}
        List<Raw> refused = List.of(
                new Raw("ACKNOWLEDGE", "APPROVED", "TIMING_CONFIRMED", true, 5_00, "op-1",
                        "resolution_four_eyes_distinct"),
                new Raw("ACKNOWLEDGE", "PROPOSED", "TIMING_CONFIRMED", false, 5_00, null,
                        "resolution_four_eyes_derived"),
                new Raw("ACKNOWLEDGE", "PROPOSED", "LOSS_ACCEPTED", true, 5_00, null,
                        "resolution_kind_reason_pairing"),
                new Raw("REPUDIATE_BATCH", "PROPOSED", "EVIDENCE_REPUDIATED", true, 5_00, null,
                        "resolution_"),
                new Raw("WRITE_OFF", "PROPOSED", "LOSS_ACCEPTED", true, 5_00, null,
                        "resolution_posting_kind_names_proposal"),
                new Raw("TRANSFER_TO_ACCOUNT", "PROPOSED", "FUNDS_ATTRIBUTED", true, 5_00,
                        null, "resolution_"),
                new Raw("MANUAL_MATCH", "PROPOSED", "AMBIGUITY_RESOLVED_BY_EVIDENCE", true,
                        5_00, null, "resolution_manual_match_names_candidate"));
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            for (Raw row : refused) {
                assertThatThrownBy(() -> execute(raw, insert, IDS.next(), breakId, row.kind(),
                        row.status(), row.reason(), row.fourEyes(), row.amount(), RULE_SET,
                        row.decidedBy(), row.decidedBy() == null ? null : "EMPLOYEE",
                        row.decidedBy() == null ? null : java.sql.Timestamp.from(
                                Instant.now(CLOCK))))
                        .as(row.kind() + "/" + row.status() + "/" + row.reason())
                        .hasStackTraceContaining(row.expected());
                raw.rollback();
            }
        }

        ResolutionMachine.Proposed proposed = propose(PROPOSER, breakId,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw, "UPDATE reconciliation.break SET type ="
                    + " 'DUPLICATE_INTERNAL' WHERE id = ?", breakId))
                    .as("a proposal's frozen lines depend on the type")
                    .hasStackTraceContaining("reclassified only while OPEN or INVESTIGATING");
            raw.rollback();
            assertThatThrownBy(() -> execute(raw, "UPDATE reconciliation.resolution SET"
                    + " journal_entry_id = ?, status = 'REJECTED', decided_by = 'op-9',"
                    + " decided_by_type = 'EMPLOYEE', decided_at = now() WHERE id = ?",
                    IDS.next(), proposed.resolutionId()))
                    .hasStackTraceContaining("names what its approval produced once");
            raw.rollback();
            assertThatThrownBy(() -> execute(raw, "UPDATE reconciliation.resolution SET"
                    + " narrative = 'edited' WHERE id = ?", proposed.resolutionId()))
                    .as("the application holds no UPDATE on the payload")
                    .hasStackTraceContaining("permission denied");
            raw.rollback();
            // The third rank, for a posting kind: ledger V010 judges the SAME two people on
            // the resolution's own ledger proposal, whoever writes.
            assertThatThrownBy(() -> execute(raw, "UPDATE ledger.adjustment_proposal SET"
                    + " status = 'APPROVED', decided_by = proposed_by, decided_at = now()"
                    + " WHERE id = ?", proposed.adjustmentProposalId().orElseThrow()))
                    .hasStackTraceContaining("adjustment_proposal_approver_is_not_initiator");
            raw.rollback();
        }
        withdraw(PROPOSER, proposed.resolutionId());
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (Map.Entry<String, String> probe : List.of(
                    Map.entry("UPDATE reconciliation.resolution SET narrative = 'edited'"
                            + " WHERE id = '" + proposed.resolutionId() + "'",
                            "frozen when proposed"),
                    Map.entry("UPDATE reconciliation.resolution SET status = 'PROPOSED',"
                            + " decided_by = NULL, decided_by_type = NULL, decided_at = NULL"
                            + " WHERE id = '" + proposed.resolutionId() + "'",
                            "not a resolution edge"),
                    Map.entry("DELETE FROM reconciliation.resolution WHERE id = '"
                            + proposed.resolutionId() + "'", "never deleted"))) {
                assertThatThrownBy(() -> execute(migrator, probe.getKey()))
                        .as(probe.getKey())
                        .hasStackTraceContaining(probe.getValue());
                migrator.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- races

    @Test
    @Order(8)
    @DisplayName("ten racing approvers: one entry, one BreakResolved, nine told it is no"
            + " longer pending - the break row, then the resolution row, serialise them")
    void tenApproversPostOnce() throws Exception {
        Seeded expectation = openExpectation("RACE", 70_00, ExpectationDirection.INBOUND);
        UUID breakId = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), 70_00, SOURCE, RULE_SET);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, breakId,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.IMMATERIAL_DIFFERENCE,
                Optional.empty());
        List<Future<String>> outcomes = race(10, racer -> {
            Actor approver = new Actor("op-racer-" + racer, ActorType.EMPLOYEE);
            try {
                approve(approver, proposed.resolutionId());
                return "APPROVED";
            } catch (ResolutionMachine.ResolutionNotPending lost) {
                return "NOT_PENDING";
            }
        });
        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(results).filteredOn("APPROVED"::equals).hasSize(1);
        assertThat(results).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                proposed.resolutionId().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", breakId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'RESOLVED'", expectation.id()))
                .isEqualTo(1);
    }

    @Test
    @Order(9)
    @DisplayName("evidence first withdraws the pending proposal and rejects its ledger half;"
            + " approval first leaves the evidence nothing to close")
    void evidenceWinsAgainstAPendingProposal() throws Exception {
        Seeded first = openExpectation("EVI", 25_00, ExpectationDirection.INBOUND);
        UUID pendingBreak = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(first.id()), 25_00, SOURCE, RULE_SET);
        ResolutionMachine.Proposed pending = propose(PROPOSER, pendingBreak,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 25_00,
                ItemKeyKind.PSP_CAPTURE_REF, first.key()));
        matching().sweep();
        assertThat(string("SELECT status || '/' || decided_by_type FROM"
                + " reconciliation.resolution WHERE id = ?", pending.resolutionId()))
                .isEqualTo("WITHDRAWN/SYSTEM");
        assertThat(string("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                pending.adjustmentProposalId().orElseThrow())).isEqualTo("REJECTED");
        assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                + " status = 'APPROVED'", pendingBreak)).isEqualTo("EVIDENCED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", pendingBreak))
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.ResolutionWithdrawn'",
                pending.resolutionId().toString())).isEqualTo(1);
        assertThatThrownBy(() -> approve(APPROVER, pending.resolutionId()))
                .isInstanceOf(ResolutionMachine.ResolutionNotPending.class);

        // Approval first: the late line finds the expectation disposed and parks as a
        // recovery - DUPLICATE_EXTERNAL, closed four-eyes later - never a second closure.
        Seeded second = openExpectation("LATE", 40_00, ExpectationDirection.INBOUND);
        UUID writtenOff = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(second.id()), 40_00, SOURCE, RULE_SET);
        ResolutionMachine.Proposed writeOff = propose(PROPOSER, writtenOff,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        approve(APPROVER, writeOff.resolutionId());
        UUID lateRun = seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 40_00,
                ItemKeyKind.PSP_CAPTURE_REF, second.key()));
        matching().sweep();
        UUID lateItem = itemOf(lateRun, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                lateItem)).isEqualTo("PARKED");
        assertThat(string("SELECT type FROM reconciliation.break WHERE external_item_id = ?",
                lateItem)).isEqualTo("DUPLICATE_EXTERNAL");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                writtenOff)).as("the written-off break takes no second closure").isEqualTo(1);
    }

    @Test
    @Order(10)
    @DisplayName("a moved residual refuses the approval as stale with nothing written; only the"
            + " proposer withdraws, another person rejects with a reason, and the break returns"
            + " to investigation each time")
    void staleRejectAndWithdraw() throws Exception {
        Parked owed = parked(SOURCE, RULE_SET, ExternalLineType.CAPTURE, 18_00,
                BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, owed.breakId(),
                ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionReasonCode.FUNDS_ATTRIBUTED,
                Optional.of(wallet));
        inCommittedTransaction(uow -> {
            execute(uow, "UPDATE reconciliation.break SET residual_version ="
                    + " residual_version + 1 WHERE id = ?", owed.breakId());
            return null;
        });
        assertThatThrownBy(() -> approve(APPROVER, proposed.resolutionId()))
                .isInstanceOf(ResolutionMachine.ResolutionStale.class);
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId())).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                proposed.resolutionId().toString())).isZero();

        assertThatThrownBy(() -> withdraw(APPROVER, proposed.resolutionId()))
                .isInstanceOf(ResolutionMachine.NotTheProposer.class);
        ResolutionMachine.Decided withdrawn = withdraw(PROPOSER, proposed.resolutionId());
        assertThat(withdrawn.status()).isEqualTo(ResolutionStatus.WITHDRAWN);
        assertThat(withdraw(PROPOSER, proposed.resolutionId()).replayed()).isTrue();
        assertThat(string("SELECT status FROM ledger.adjustment_proposal WHERE id = ?",
                proposed.adjustmentProposalId().orElseThrow())).isEqualTo("REJECTED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                owed.breakId())).isEqualTo("INVESTIGATING");

        ResolutionMachine.Proposed again = propose(PROPOSER, owed.breakId(),
                ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionReasonCode.FUNDS_ATTRIBUTED,
                Optional.of(wallet));
        assertThatThrownBy(() -> reject(PROPOSER, again.resolutionId(), "not mine to judge"))
                .isInstanceOf(ResolutionMachine.SelfApprovalRefused.class);
        assertThatThrownBy(() -> reject(THIRD, again.resolutionId(), " "))
                .isInstanceOf(ResolutionMachine.ResolutionRefused.class);
        ResolutionMachine.Decided rejected =
                reject(THIRD, again.resolutionId(), "the owner is not evidenced");
        assertThat(rejected.status()).isEqualTo(ResolutionStatus.REJECTED);
        assertThat(string("SELECT reason FROM reconciliation.resolution_event WHERE"
                + " resolution_id = ? AND to_status = 'REJECTED'", again.resolutionId()))
                .isEqualTo("the owner is not evidenced");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                owed.breakId())).isEqualTo("INVESTIGATING");
        assertThatThrownBy(() -> approve(APPROVER, again.resolutionId()))
                .isInstanceOf(ResolutionMachine.ResolutionNotPending.class);
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                owed.suspenseItemId())).as("nothing was disposed of").isEqualTo("OPEN");
    }

    @Test
    @Order(11)
    @DisplayName("a manual match chooses only a stored candidate and applies as the engine"
            + " would: a MANUAL decision, the allocation, the item MATCHED and the park's"
            + " exact inverse, the resolution naming all three")
    void aManualMatchStandsInForTheEngine() throws Exception {
        Seeded chosen = openExpectation("MM-A", 30_00, ExpectationDirection.INBOUND);
        Seeded other = openExpectation("MM-B", 30_00, ExpectationDirection.INBOUND);
        Seeded stranger = openExpectation("MM-C", 30_00, ExpectationDirection.INBOUND);
        UUID run = seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 30_00,
                ItemKeyKind.PSP_CAPTURE_REF, "MM-ITEM-" + UUID.randomUUID()));
        UUID item = itemOf(run, 1);
        // AMBIGUOUS_MATCH is unproduced by the engine (one expectation per key): the fixture
        // plants the stored decision with its two candidates, as the engine would record it.
        as(PLATFORM, uow -> {
            UUID decision = IDS.next();
            matchingStore.insertDecision(uow, new MatchingStore.NewDecision(
                    decision, item, run, DecisionOrigin.RUN, RULE_SET, Optional.of(1),
                    Optional.of(Cardinality.ONE_TO_ONE), Optional.of(KeyKind.PSP_CAPTURE_REF),
                    DecisionOutcome.PARKED, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), PLATFORM, Instant.now(CLOCK),
                    LocalDate.now(CLOCK), CorrelationId.generate(IDS),
                    MatchingStore.Basis.match(
                            DecisionVerdict.AMBIGUOUS, JudgedStatus.PENDING, 30_00, false)));
            matchingStore.insertCandidates(uow, decision, matchingStore.lockExpectations(uow,
                    List.of(chosen.id(), other.id()),
                    Map.of(chosen.id(), KeyKind.PSP_CAPTURE_REF, other.id(),
                            KeyKind.PSP_CAPTURE_REF)));
            return null;
        });
        UUID ambiguous = raise(BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES,
                BreakRegister.Subject.externalItem(item), 30_00, SOURCE, RULE_SET);
        park(SOURCE, item, ambiguous, 30_00);
        completeRun(run);
        UUID suspenseItem = id("SELECT id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);

        assertThatThrownBy(() -> proposeManual(PROPOSER, ambiguous, stranger.id()))
                .as("only a candidate the stored snapshot saw")
                .isInstanceOf(ResolutionMachine.ResolutionTargetRefused.class);
        assertThatThrownBy(() -> propose(PROPOSER, owedBreakOf(item), ResolutionKind.RECOGNISE_GAIN,
                        ResolutionReasonCode.UNATTRIBUTABLE_AGED, Optional.empty()))
                .isInstanceOf(ResolutionMachine.GainNotYetEligible.class);
        ResolutionMachine.Proposed manual = proposeManual(PROPOSER, ambiguous, chosen.id());
        assertThat(manual.adjustmentProposalId()).isEmpty();
        ResolutionMachine.Decided approved = approve(APPROVER, manual.resolutionId());

        UUID decision = id("SELECT decision_id FROM reconciliation.resolution WHERE id = ?",
                manual.resolutionId());
        assertThat(string("SELECT origin || '/' || outcome FROM reconciliation.match_decision"
                + " WHERE id = ?", decision)).isEqualTo("MANUAL/MATCHED");
        assertThat(string("SELECT expectation_id || '/' || amount_minor FROM"
                + " reconciliation.allocation WHERE decision_id = ?", decision))
                .isEqualTo(chosen.id() + "/3000");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                chosen.id())).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                item)).isEqualTo("MATCHED");
        assertThat(string("SELECT status || '/' || released_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?", suspenseItem))
                .isEqualTo("RELEASED/3000");
        UUID unpark = approved.journalEntryId().orElseThrow();
        assertThat(entryLines(unpark))
                .as("the park's exact inverse: DR suspense / CR the position")
                .containsExactly(suspenseAccount + ">DEBIT>3000", clearing + ">CREDIT>3000");
        assertThat(string("SELECT kind FROM reconciliation.park WHERE id = (SELECT park_id"
                + " FROM reconciliation.resolution WHERE id = ?)", manual.resolutionId()))
                .isEqualTo("UNPARK");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", ambiguous))
                .isEqualTo("RESOLVED");
    }

    @Test
    @Order(12)
    @DisplayName("a partial arrival's shortfall and ageing's overdue break answer for ONE"
            + " remainder: one live proposal across both, and the disposal closes both,"
            + " the sibling's history naming the resolution")
    void theRemainderSiblingsCloseTogether() throws Exception {
        Seeded partial = openExpectation("SIB", 80_00, ExpectationDirection.INBOUND);
        // Ageing's own raise, stood in for, BEFORE the money: the value at issue frozen at the
        // whole 80.00 - a typed figure the template must never post.
        UUID overdue = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(partial.id()), 80_00, SOURCE, RULE_SET);
        seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 30_00,
                ItemKeyKind.PSP_CAPTURE_REF, partial.key()));
        matching().sweep();
        UUID shortfall = id("SELECT id FROM reconciliation.break WHERE expectation_id = ? AND"
                + " type = 'AMOUNT_MISMATCH'", partial.id());
        assertThat(shortfall).as("the partial allocation recorded the shortfall").isNotNull();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .as("a partial arrival resolves nothing (P8-TSK-013's L1)")
                .isNotEqualTo("RESOLVED");

        ResolutionMachine.Proposed writeOff = propose(PROPOSER, overdue,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        assertThat(string("SELECT proposed_amount_minor FROM reconciliation.resolution WHERE"
                + " id = ?", writeOff.resolutionId()))
                .as("the CURRENT remainder, never the break's frozen value at issue")
                .isEqualTo("5000");
        assertThat(proposalLines(writeOff.adjustmentProposalId().orElseThrow()))
                .containsExactly(losses + ">DEBIT>5000", clearing + ">CREDIT>5000");
        assertThatThrownBy(() -> propose(THIRD, shortfall, ResolutionKind.WRITE_OFF,
                        ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty()))
                .as("one live proposal per remainder, across its breaks")
                .isInstanceOf(ResolutionMachine.ResolutionAlreadyProposed.class);

        UUID entry = approve(APPROVER, writeOff.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(losses + ">DEBIT>5000", clearing + ">CREDIT>5000");
        assertThat(string("SELECT status || '/' || allocated_minor || '/' || resolved_minor"
                + " FROM reconciliation.expectation WHERE id = ?", partial.id()))
                .isEqualTo("RESOLVED_BY_ADJUSTMENT/3000/5000");
        for (UUID closed : List.of(overdue, shortfall)) {
            assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", closed))
                    .isEqualTo("RESOLVED");
            assertThat(string("SELECT detail FROM reconciliation.break_event WHERE"
                    + " break_id = ? AND event_type = 'RESOLVED'", closed))
                    .contains("resolution=" + writeOff.resolutionId());
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                    + " 'reconciliation.BreakResolved' AND aggregate_id = ?", closed))
                    .isEqualTo(1);
        }
    }

    @Test
    @Order(13)
    @DisplayName("the transfer target is held FOR SHARE until the approval commits: a plain"
            + " status change - FOR NO KEY UPDATE, which a posting's own key-share lock does not"
            + " exclude - cannot slip past it")
    @SuppressWarnings("try")
    void theTransferTargetIsShareLockedUntilCommit() throws Exception {
        Seeded outbound = openExpectation("LOCK", 11_00, ExpectationDirection.OUTBOUND);
        UUID breakId = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(outbound.id()), 11_00, SOURCE, RULE_SET);
        UUID target = openWallet(EUR);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, breakId,
                ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionReasonCode.FUNDS_ATTRIBUTED,
                Optional.of(target));
        try (SecurityContext.Scope identity = SecurityContext.enter(APPROVER);
                CorrelationContext.Scope scope = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
                Connection approving = DatabaseRoles.application()) {
            approving.setAutoCommit(false);
            machine.approve(approving, proposed.resolutionId(), APPROVER,
                    CorrelationContext.current().orElseThrow().correlationId());
            try (Connection writer = DatabaseRoles.application()) {
                writer.setAutoCommit(false);
                assertThatThrownBy(() -> execute(writer, "SELECT id FROM ledger.ledger_account"
                        + " WHERE id = ? FOR NO KEY UPDATE NOWAIT", target))
                        .as("the approval holds the target FOR SHARE (ADR-0071 section 9, step 5)")
                        .hasStackTraceContaining("could not obtain lock");
                writer.rollback();
            }
            approving.rollback();
        }
        withdraw(PROPOSER, proposed.resolutionId());
    }

    @Test
    @Order(14)
    @DisplayName("an allocation between proposal and approval - the real matcher's partial"
            + " arrival - moves the residual, and the approval is refused as stale with nothing"
            + " written; the proposer re-proposes over the new remainder")
    void anAllocationBetweenProposalAndApprovalIsStale() throws Exception {
        Seeded expectation = openExpectation("STALE", 60_00, ExpectationDirection.INBOUND);
        UUID overdue = raise(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                BreakRegister.Subject.expectation(expectation.id()), 60_00, SOURCE, RULE_SET);
        ResolutionMachine.Proposed proposed = propose(PROPOSER, overdue,
                ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        seedRun(SOURCE, RULE_SET, new Line(1, ExternalLineType.CAPTURE, 20_00,
                ItemKeyKind.PSP_CAPTURE_REF, expectation.key()));
        matching().sweep();
        assertThatThrownBy(() -> approve(APPROVER, proposed.resolutionId()))
                .isInstanceOf(ResolutionMachine.ResolutionStale.class);
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                proposed.resolutionId())).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                proposed.resolutionId().toString())).isZero();

        withdraw(PROPOSER, proposed.resolutionId());
        ResolutionMachine.Proposed again = propose(PROPOSER, overdue, ResolutionKind.WRITE_OFF,
                ResolutionReasonCode.LOSS_ACCEPTED, Optional.empty());
        assertThat(string("SELECT proposed_amount_minor FROM reconciliation.resolution WHERE"
                + " id = ?", again.resolutionId())).isEqualTo("4000");
        UUID entry = approve(APPROVER, again.resolutionId()).journalEntryId().orElseThrow();
        assertThat(entryLines(entry))
                .containsExactly(losses + ">DEBIT>4000", clearing + ">CREDIT>4000");
        assertThat(string("SELECT status || '/' || allocated_minor || '/' || resolved_minor"
                + " FROM reconciliation.expectation WHERE id = ?", expectation.id()))
                .isEqualTo("RESOLVED_BY_ADJUSTMENT/2000/4000");
    }

    // ----------------------------------------------------------------- seeding

    private record Seeded(UUID id, String operationRef, String key) {}

    private record Parked(UUID runId, UUID itemId, UUID breakId, UUID suspenseItemId) {}

    private record Line(
            int lineNo, ExternalLineType type, long minor, ItemKeyKind keyKind, String key) {}

    private static ResolutionMachine.Proposed propose(
            Actor actor,
            UUID breakId,
            ResolutionKind kind,
            ResolutionReasonCode code,
            Optional<UUID> target) {
        return as(actor, uow -> machine.propose(uow, breakId,
                new ResolutionMachine.ProposalRequest(kind, code, "the investigator's account",
                        target, Optional.empty(), Optional.empty()),
                actor, CorrelationContext.current().orElseThrow().correlationId()));
    }

    private static ResolutionMachine.Proposed proposeOffset(
            Actor actor, UUID breakId, UUID offsetItem) {
        return as(actor, uow -> machine.propose(uow, breakId,
                new ResolutionMachine.ProposalRequest(ResolutionKind.OFFSET_SUSPENSE,
                        ResolutionReasonCode.DUPLICATE_BY_COUNTERPARTY, "the two net",
                        Optional.empty(), Optional.of(offsetItem), Optional.empty()),
                actor, CorrelationContext.current().orElseThrow().correlationId()));
    }

    private static ResolutionMachine.Proposed proposeManual(
            Actor actor, UUID breakId, UUID chosen) {
        return as(actor, uow -> machine.propose(uow, breakId,
                new ResolutionMachine.ProposalRequest(ResolutionKind.MANUAL_MATCH,
                        ResolutionReasonCode.AMBIGUITY_RESOLVED_BY_EVIDENCE, "the remittance"
                                + " advice names the capture",
                        Optional.empty(), Optional.empty(), Optional.of(chosen)),
                actor, CorrelationContext.current().orElseThrow().correlationId()));
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

    /** The AMBIGUOUS fixture's break id — the one this item's subject carries. */
    private static UUID owedBreakOf(UUID item) throws SQLException {
        return id("SELECT id FROM reconciliation.break WHERE external_item_id = ?", item);
    }

    private static Parked parked(UUID source, UUID ruleSet, ExternalLineType type, long minor,
            BreakType breakType, BreakCause cause) throws SQLException {
        ItemKeyKind keyKind = type == ExternalLineType.REFUND
                ? ItemKeyKind.PSP_REFUND_REF : ItemKeyKind.PSP_CAPTURE_REF;
        UUID run = seedRun(source, ruleSet,
                new Line(1, type, minor, keyKind, "PK-" + UUID.randomUUID()));
        UUID item = itemOf(run, 1);
        UUID breakId = raise(breakType, cause, BreakRegister.Subject.externalItem(item), minor,
                source, ruleSet);
        park(source, item, breakId, minor);
        completeRun(run);
        return new Parked(run, item, breakId, id("SELECT id FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", item));
    }

    private static void park(UUID source, UUID item, UUID breakId, long minor) {
        as(PLATFORM, uow -> suspense.park(uow, new Suspense.ParkCommand(
                source, parkedOn,
                List.of(new Suspense.ParkedItem(item, breakId, Money.ofPersisted(minor, EUR, 2),
                        clearing)),
                PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS))));
    }

    private static void completeRun(UUID run) {
        as(PLATFORM, uow -> {
            matchingStore.markRunInProgress(uow, run, PLATFORM, Instant.now(CLOCK));
            matchingStore.completeRun(uow, run, PLATFORM, Instant.now(CLOCK));
            return null;
        });
    }

    private static UUID seedRun(UUID source, UUID ruleSet, Line... lines) throws SQLException {
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

    private static Seeded openExpectation(
            String prefix, long minor, ExpectationDirection direction) throws SQLException {
        return openExpectation(prefix, minor, direction, FAR);
    }

    private static Seeded openExpectation(
            String prefix, long minor, ExpectationDirection direction, LocalDate expectedBy)
            throws SQLException {
        String key = prefix + "-" + UUID.randomUUID();
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
                            kind, operationRef, "p8t15:" + operationRef, SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING, clearing, direction,
                            Money.ofPersisted(minor, EUR, 2), Optional.of(IDS.next()),
                            SETTLED_ON, Optional.empty(), expectedBy, RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(keyKind, key)),
                            PLATFORM, Instant.now(CLOCK), CorrelationId.generate(IDS)));
            app.commit();
        }
        UUID id = (UUID) one("SELECT id FROM reconciliation.expectation WHERE operation_ref = ?"
                + " AND kind = ?", operationRef, kind.name());
        return new Seeded(id, operationRef, key);
    }

    private static UUID raise(BreakType type, BreakCause cause, BreakRegister.Subject subject,
            long minor, UUID source, UUID ruleSet) throws SQLException {
        UUID breakId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            register.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId, type, cause, subject, source, ruleSet,
                            Money.ofPersisted(minor, EUR, 2), Optional.empty(), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty(),
                            Optional.empty(), PLATFORM, Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
        }
        return breakId;
    }

    private static UUID openWallet(CurrencyCode currency) {
        return openWallet(currency, IDS.next());
    }

    private static UUID openWallet(CurrencyCode currency, UUID owner) {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .createOrConverge(uow, LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                        AccountPurpose.CUSTOMER_WALLET, currency, owner))
                .account()
                .id()
                .value());
    }

    private static UUID operational(AccountPurpose purpose) {
        return inCommittedTransaction(uow -> new JdbcLedgerAccountStore()
                .findOperational(uow, purpose, EUR).orElseThrow().id().value());
    }

    private static void seedRuleSet(UUID source, UUID ruleSet, int gainMinAgeDays) {
        inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, ?, ?, 'test', NULL,"
                            + " 'ResolutionMachineDatabaseTest private rule set', now(),"
                            + " 'p8-tsk-015-test')",
                    ruleSet, source, gainMinAgeDays, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48)",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2)",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)",
                    ruleSet);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                            + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                    ruleSet);
            return null;
        });
    }

    private static Matching matching() {
        return new Matching(
                matchingStore, new MatchingRules(), register, suspense,
                ResolutionFixtures.resolutions(IDS, CLOCK),
                (unitOfWork, subject) -> InternalReferenceLookup.InternalReference.unknown(),
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

    // ----------------------------------------------------------------- plumbing

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

    private static <R> List<Future<R>> race(int racers, java.util.function.IntFunction<R> act)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<R>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < racers; racer++) {
                int index = racer;
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return act.apply(index);
                }));
            }
            start.countDown();
            for (Future<R> outcome : outcomes) {
                outcome.get();
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<String> entryLines(UUID entry) throws SQLException {
        return lines("SELECT ledger_account_id || '>' || direction || '>' || amount_minor FROM"
                + " ledger.journal_line WHERE entry_id = ? ORDER BY seq", entry);
    }

    private static List<String> proposalLines(UUID proposal) throws SQLException {
        return lines("SELECT ledger_account_id || '>' || direction || '>' || amount_minor FROM"
                + " ledger.adjustment_proposal_line WHERE proposal_id = ? ORDER BY seq",
                proposal);
    }

    private static List<String> lines(String sql, UUID id) throws SQLException {
        application.rollback();
        List<String> found = new ArrayList<>();
        try (PreparedStatement read = application.prepareStatement(sql)) {
            read.setObject(1, id);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
        }
        return found;
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
