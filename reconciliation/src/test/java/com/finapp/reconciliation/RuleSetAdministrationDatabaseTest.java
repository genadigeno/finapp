package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
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
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Rule set administration against the real schema (`P8-TSK-022`, ADR-0068 §8, `V012`;
 * {@code INV-HIST-04}, {@code INV-AUD-04}, {@code INV-REC-08}): a second person's activation
 * retiring its predecessor in the same transaction, the content read back exactly as proposed,
 * the four-eyes rule at the domain and at the {@code CHECK}, the member tables closed to every
 * insert but the proposal's own, the machine's edges for any writer — the deferred
 * "retires only beside its successor" rule judged at commit — one proposal per source, the
 * same person's retry converging, the unrepresentable amount tolerance, and the two ten-way
 * races, counted.
 *
 * <p>Every case owns a fresh private source whose version 1 is seeded the `V012` way — born
 * {@code PROPOSED} with its members in one transaction, then activated by
 * {@code test-activator} — so no case sees another's versions.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("rule set administration (P8-TSK-022)")
class RuleSetAdministrationDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-10-01T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant PROPOSED_AT = Instant.parse("2026-10-01T09:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-10-01T10:30:00Z");
    private static final LocalDate SEEDED_ON = LocalDate.parse("2026-09-29");
    private static final Actor PROPOSER = new Actor("op-controller-a", ActorType.EMPLOYEE);
    private static final Actor APPROVER = new Actor("op-controller-b", ActorType.EMPLOYEE);
    private static final Actor THIRD = new Actor("op-controller-c", ActorType.EMPLOYEE);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String RAISED = "P0001";
    /** A source whose evidence settles an FX provider's cover legs - its first version must date both. */
    private static final UUID SETTLING_FX = UUID.randomUUID();

    private static Connection application;
    private static RuleSetAdministration administration;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        administration =
                new RuleSetAdministration(new JdbcRuleSetStore(), new JdbcAuditWriter(), IDS,
                        (unitOfWork, sourceId) -> sourceId.equals(SETTLING_FX)
                                ? java.util.EnumSet.of(ExpectationKind.FX_SELL_LEG, ExpectationKind.FX_BUY_LEG)
                                : java.util.Set.of());
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    // ----------------------------------------------------------------- (1) activation

    @Test
    @Order(1)
    @DisplayName("a second person activates the proposal: v2 ACTIVE, v1 RETIRED keeping its"
            + " decision, both history rows, one reasoned audit record, and the content read"
            + " back exactly as proposed")
    void aSecondPersonActivatesAndThePredecessorRetires() throws SQLException {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        RuleSetProposal proposal =
                proposal(source, "Widen the refund lag after the PSP moved its refund cycle");
        RuleSetAdministration.Proposed proposed = propose(PROPOSER, proposal);
        assertThat(proposed.version()).isEqualTo(2);
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?",
                proposed.ruleSetId())).isEqualTo("PROPOSED");

        String reason = "Reviewed against the PSP's published terms for October";
        RuleSetAdministration.Decided decided = approve(APPROVER, proposed.ruleSetId(), reason);

        assertThat(decided.ruleSetId()).isEqualTo(proposed.ruleSetId());
        assertThat(decided.version()).isEqualTo(2);
        assertThat(decided.status()).isEqualTo(RuleSetStatus.ACTIVE);
        assertThat(decided.retiredRuleSetId()).contains(v1);
        assertThat(decided.replayed()).isFalse();
        assertThat(string("SELECT status || '/' || decided_by FROM reconciliation.rule_set"
                + " WHERE id = ?", v1))
                .as("a retirement keeps the activation's decision")
                .isEqualTo("RETIRED/test-activator");
        assertThat(string("SELECT status || '/' || decided_by FROM reconciliation.rule_set"
                + " WHERE id = ?", proposed.ruleSetId()))
                .isEqualTo("ACTIVE/" + APPROVER.id());
        assertThat(lines("SELECT coalesce(e.from_status, '-') || '>' || e.to_status || '>'"
                + " || e.actor FROM reconciliation.rule_set_event e"
                + " JOIN reconciliation.rule_set r ON r.id = e.rule_set_id"
                + " WHERE r.source_id = ? ORDER BY e.seq", source))
                .containsExactly(
                        "->PROPOSED>" + PROPOSER.id(),
                        "ACTIVE>RETIRED>" + APPROVER.id(),
                        "PROPOSED>ACTIVE>" + APPROVER.id());
        assertThat(string("SELECT reason FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ? AND to_status = 'RETIRED'", v1))
                .isEqualTo("retired by version 2's activation: " + reason);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'",
                proposed.ruleSetId().toString())).isEqualTo(1);
        assertThat(lines("SELECT reason || '|' || change_summary FROM platform.audit_record"
                + " WHERE target_id = ? AND operation = 'reconciliation.RuleSetActivated'",
                proposed.ruleSetId().toString()))
                .containsExactly(reason + "|source=" + source + ", activated=v2, retired=v1");
        assertThat(string("SELECT reason FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetProposed'",
                proposed.ruleSetId().toString())).isEqualTo(proposal.reason());

        List<RuleSetStore.VersionView> views = versions(source, 10);
        assertThat(views.stream().map(view -> view.row().id()).toList())
                .containsExactly(proposed.ruleSetId(), v1);
        RuleSetStore.VersionView view = views.get(0);
        RuleSetStore.VersionRow row = view.row();
        assertThat(row.sourceId()).isEqualTo(source);
        assertThat(row.version()).isEqualTo(2);
        assertThat(row.status()).isEqualTo(RuleSetStatus.ACTIVE);
        assertThat(row.proposedBy()).isEqualTo(PROPOSER.id());
        assertThat(row.decidedBy()).contains(APPROVER.id());
        assertThat(row.decidedAt()).contains(DECIDED_AT);
        assertThat(row.reason()).isEqualTo(proposal.reason());
        assertThat(row.createdAt()).isEqualTo(PROPOSED_AT);
        assertThat(row.fundingLagDays()).isEqualTo(proposal.fundingLagDays());
        assertThat(row.gainMinAgeDays()).isEqualTo(proposal.gainMinAgeDays());
        assertThat(row.effectiveFrom()).isEqualTo(LocalDate.ofInstant(PROPOSED_AT, ZoneOffset.UTC));
        assertThat(view.lagDays()).isEqualTo(proposal.lagDays());
        assertThat(view.rules()).containsExactlyElementsOf(proposal.rules());
        assertThat(view.tolerances()).containsExactlyInAnyOrderElementsOf(proposal.tolerances());
        assertThat(view.feeSchedules())
                .containsExactlyInAnyOrderElementsOf(proposal.feeSchedules());
        assertThat(view.severityThresholds()).isEqualTo(proposal.severityThresholds());
        assertThat(views.get(1).row().status()).isEqualTo(RuleSetStatus.RETIRED);
        assertThat(views.get(1).row().decidedBy()).contains("test-activator");
        assertThat(versions(source, 1).stream().map(only -> only.row().id()).toList())
                .as("bounded, newest version first")
                .containsExactly(proposed.ruleSetId());
    }

    // ----------------------------------------------------------------- (2) four eyes

    @Test
    @Order(2)
    @DisplayName("self-activation is refused at the domain with nothing written, and at the"
            + " CHECK for any writer (rule_set_activation_is_four_eyes)")
    void activationIsFourEyesAtBothRanks() throws SQLException {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        RuleSetAdministration.Proposed proposed =
                propose(PROPOSER, proposal(source, "Shorten the capture grace window"));

        assertThatThrownBy(() -> approve(PROPOSER, proposed.ruleSetId(), "my own proposal"))
                .isInstanceOf(RuleSetAdministration.RuleSetActivationBySameActor.class);
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?",
                proposed.ruleSetId())).isEqualTo("PROPOSED");
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?", v1))
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id IN (?, ?) AND from_status IS NOT NULL",
                proposed.ruleSetId(), v1)).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'",
                proposed.ruleSetId().toString())).isZero();

        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            // The predecessor retired first, exactly as an activation does, so what refuses is
            // the four-eyes CHECK and not the one-active unique.
            execute(raw, "UPDATE reconciliation.rule_set SET status = 'RETIRED' WHERE id = ?",
                    v1);
            SQLException selfActivated =
                    refused(raw, "UPDATE reconciliation.rule_set SET status = 'ACTIVE',"
                            + " decided_by = proposed_by, decided_at = now() WHERE id = ?",
                            proposed.ruleSetId());
            assertThat(selfActivated.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(selfActivated.getMessage()).contains("rule_set_activation_is_four_eyes");
        }
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?", v1))
                .isEqualTo("ACTIVE");
    }

    // ----------------------------------------------------------------- (3) the members

    @Test
    @Order(3)
    @DisplayName("a member joins only its own proposal's transaction: refused into an ACTIVE"
            + " version and into a PROPOSED one from a second transaction; an existing key's"
            + " re-insert with ON CONFLICT DO NOTHING converges")
    void membersJoinOnlyTheirOwnProposal() throws SQLException {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        RuleSetProposal proposal = proposal(source, "Add the dispute fee lag");
        UUID v2 = propose(PROPOSER, proposal).ruleSetId();

        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            SQLException intoActive =
                    refused(raw, "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                            + " expectation_kind, lag_days) VALUES (?, 'PUSH_PAY_IN', 1)", v1);
            assertThat(intoActive.getSQLState()).isEqualTo(RAISED);
            assertThat(intoActive.getMessage()).contains("written with its proposal");

            for (String appended : List.of(
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type, key_kind,"
                            + " expectation_kind, cardinality, operation_anchored, grace_hours)"
                            + " VALUES (?, 99, 'CAPTURE', 'OUR_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48)",
                    "INSERT INTO reconciliation.rule_set_lag (rule_set_id, expectation_kind,"
                            + " lag_days) VALUES (?, 'CHARGEBACK', 3)",
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'PROCESSING_FEE_PER_LINE',"
                            + " 'GBP', 2, NULL)",
                    "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id, line_type,"
                            + " currency, rate, fixed_minor, scale, rounding_policy) VALUES"
                            + " (?, 'PROCESSING_FEE', 'GBP', 0.015000, 25, 2, 'HALF_UP')",
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'USD', 100000)")) {
                SQLException afterReview = refused(raw, appended, v2);
                assertThat(afterReview.getSQLState()).as(appended).isEqualTo(RAISED);
                assertThat(afterReview.getMessage()).as(appended)
                        .contains("written with its proposal");
            }

            SQLException plainDuplicate =
                    refused(raw, "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                            + " expectation_kind, lag_days) VALUES (?, 'CARD_CAPTURE', 3)", v1);
            assertThat(plainDuplicate.getSQLState())
                    .as("an existing key is left to its unique")
                    .isEqualTo(UNIQUE_VIOLATION);

            execute(raw, "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                    + " expectation_kind, lag_days) VALUES (?, 'CARD_CAPTURE', 3)"
                    + " ON CONFLICT DO NOTHING", v1);
            execute(raw, "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                    + " key_kind, expectation_kind, cardinality, operation_anchored,"
                    + " grace_hours) VALUES (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF',"
                    + " 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING", v2);
            raw.commit();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_lag WHERE"
                + " rule_set_id = ?", v1)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_lag WHERE"
                + " rule_set_id = ?", v2)).isEqualTo(proposal.lagDays().size());
        assertThat(count("SELECT count(*) FROM reconciliation.rule WHERE rule_set_id = ?", v2))
                .isEqualTo(proposal.rules().size());
        assertThat(count("SELECT count(*) FROM reconciliation.tolerance WHERE"
                + " rule_set_id = ?", v2)).isEqualTo(proposal.tolerances().size());
        assertThat(count("SELECT count(*) FROM reconciliation.provider_fee_schedule WHERE"
                + " rule_set_id = ?", v2)).isEqualTo(proposal.feeSchedules().size());
        assertThat(count("SELECT count(*) FROM reconciliation.severity_threshold WHERE"
                + " rule_set_id = ?", v2)).isEqualTo(proposal.severityThresholds().size());
    }

    // ----------------------------------------------------------------- (4) the machine

    @Test
    @Order(4)
    @DisplayName("the version moves only along its machine, for any writer: no edge back, no"
            + " revival, a retirement only beside its successor (judged at COMMIT), content"
            + " immutable, never deleted")
    void theVersionMovesOnlyAlongItsMachine() throws SQLException {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        UUID v2 = propose(PROPOSER, proposal(source, "A draft the reviewer will refuse"))
                .ruleSetId();
        reject(APPROVER, v2, "The fee terms do not match the signed schedule");

        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            SQLException backToProposed =
                    refused(raw, "UPDATE reconciliation.rule_set SET status = 'PROPOSED',"
                            + " decided_by = NULL, decided_at = NULL WHERE id = ?", v1);
            assertThat(backToProposed.getSQLState()).isEqualTo(RAISED);
            assertThat(backToProposed.getMessage()).contains("not a rule set edge");

            SQLException revived =
                    refused(raw, "UPDATE reconciliation.rule_set SET status = 'ACTIVE'"
                            + " WHERE id = ?", v2);
            assertThat(revived.getSQLState()).isEqualTo(RAISED);
            assertThat(revived.getMessage()).contains("not a rule set edge");

            SQLException redecided =
                    refused(raw, "UPDATE reconciliation.rule_set SET status = 'RETIRED',"
                            + " decided_by = 'someone-else' WHERE id = ?", v1);
            assertThat(redecided.getMessage()).contains("keeps the activation");

            SQLException edited =
                    refused(raw, "UPDATE reconciliation.rule_set SET reason = 'edited'"
                            + " WHERE id = ?", v1);
            assertThat(edited.getSQLState())
                    .as("the application holds no UPDATE on the content")
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);
            SQLException deleted =
                    refused(raw, "DELETE FROM reconciliation.rule_set WHERE id = ?", v1);
            assertThat(deleted.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE);

            // The statement passes - the edge exists - and the deferred rule refuses the
            // transaction at COMMIT: no successor stands ACTIVE beside the retirement.
            execute(raw, "UPDATE reconciliation.rule_set SET status = 'RETIRED' WHERE id = ?",
                    v1);
            SQLException orphaned = refusedAtCommit(raw);
            assertThat(orphaned.getSQLState()).isEqualTo(RAISED);
            assertThat(orphaned.getMessage()).contains("retires only in its successor");
        }
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?", v1))
                .isEqualTo("ACTIVE");

        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            SQLException edited =
                    refused(migrator, "UPDATE reconciliation.rule_set SET reason = 'edited'"
                            + " WHERE id = ?", v1);
            assertThat(edited.getMessage()).contains("immutable from PROPOSED");
            SQLException lagEdited =
                    refused(migrator, "UPDATE reconciliation.rule_set_lag SET lag_days = 9"
                            + " WHERE rule_set_id = ?", v1);
            assertThat(lagEdited.getMessage()).contains("immutable");
            SQLException deleted =
                    refused(migrator, "DELETE FROM reconciliation.rule_set WHERE id = ?", v1);
            assertThat(deleted.getMessage()).contains("never deleted");
            SQLException historyErased =
                    refused(migrator, "DELETE FROM reconciliation.rule_set_event WHERE"
                            + " rule_set_id = ?", v2);
            assertThat(historyErased.getMessage()).contains("append-only");
        }
    }

    // ----------------------------------------------------------------- (5) one at a time

    @Test
    @Order(5)
    @DisplayName("one proposal per source; the proposer may withdraw it; a decided version is"
            + " not pending; the same person's retry converges and writes nothing twice")
    void oneProposalPerSourceAndTheSamePersonsRetryConverges() throws SQLException {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        RuleSetAdministration.Proposed first =
                propose(PROPOSER, proposal(source, "First draft of the October terms"));
        assertThat(first.version()).isEqualTo(2);
        assertThatThrownBy(() -> propose(THIRD, proposal(source, "A competing draft")))
                .isInstanceOf(RuleSetAdministration.RuleSetProposalPending.class);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                source)).isEqualTo(2);

        String withdrawal = "Withdrawn: the PSP re-issued its October schedule";
        RuleSetAdministration.Decided withdrawn = reject(PROPOSER, first.ruleSetId(), withdrawal);
        assertThat(withdrawn.status()).isEqualTo(RuleSetStatus.REJECTED);
        assertThat(withdrawn.replayed()).isFalse();
        assertThat(withdrawn.retiredRuleSetId()).isEmpty();
        assertThat(string("SELECT status || '/' || decided_by FROM reconciliation.rule_set"
                + " WHERE id = ?", first.ruleSetId())).isEqualTo("REJECTED/" + PROPOSER.id());
        assertThat(string("SELECT change_summary FROM platform.audit_record WHERE"
                + " target_id = ? AND operation = 'reconciliation.RuleSetRejected'",
                first.ruleSetId().toString()))
                .isEqualTo("source=" + source + ", rejected=v2, byProposer=true");
        assertThat(reject(PROPOSER, first.ruleSetId(), withdrawal).replayed())
                .as("the withdrawal's retry converges")
                .isTrue();

        RuleSetAdministration.Proposed second =
                propose(THIRD, proposal(source, "The re-issued October terms"));
        assertThat(second.version()).isEqualTo(3);

        assertThatThrownBy(() -> approve(APPROVER, first.ruleSetId(), "too late"))
                .isInstanceOf(RuleSetAdministration.RuleSetNotPending.class);
        assertThatThrownBy(() -> reject(APPROVER, v1, "rejecting the version in force"))
                .isInstanceOf(RuleSetAdministration.RuleSetNotPending.class);

        String reason = "Matches the re-issued schedule line for line";
        RuleSetAdministration.Decided activated = approve(APPROVER, second.ruleSetId(), reason);
        assertThat(activated.replayed()).isFalse();
        assertThat(activated.retiredRuleSetId()).contains(v1);
        RuleSetAdministration.Decided retried = approve(APPROVER, second.ruleSetId(), reason);
        assertThat(retried.replayed()).isTrue();
        assertThat(retried.status()).isEqualTo(RuleSetStatus.ACTIVE);
        assertThat(retried.version()).isEqualTo(3);
        assertThat(retried.retiredRuleSetId()).as("a converged retry wrote nothing").isEmpty();
        assertThatThrownBy(() -> approve(THIRD, second.ruleSetId(), reason))
                .as("anyone else is told it is no longer pending")
                .isInstanceOf(RuleSetAdministration.RuleSetNotPending.class);

        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ? AND to_status = 'ACTIVE'", second.ruleSetId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ? AND to_status = 'RETIRED'", v1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ? AND to_status = 'REJECTED'", first.ruleSetId()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'",
                second.ruleSetId().toString())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetRejected'",
                first.ruleSetId().toString())).isEqualTo(1);
        assertThat(lines("SELECT version || ':' || status FROM reconciliation.rule_set WHERE"
                + " source_id = ? ORDER BY version", source))
                .containsExactly("1:RETIRED", "2:REJECTED", "3:ACTIVE");
    }

    // ----------------------------------------------------------------- (6) tolerances

    @Test
    @Order(6)
    @DisplayName("an amount tolerance is refused before anything is written (INV-REC-08), and"
            + " the database stores neither it nor a second date window (NULLS NOT DISTINCT)")
    void anAmountToleranceIsUnrepresentable() throws SQLException {
        UUID source = IDS.next();
        seedVersionOne(source);
        RuleSetProposal amount =
                proposal(source, "Absorb small principal differences", List.of(
                        dateWindow(2),
                        new RuleSetProposal.Tolerance(
                                "PRINCIPAL_AMOUNT", Optional.of(EUR), Optional.of(100L),
                                Optional.empty())));
        assertThatThrownBy(() -> propose(PROPOSER, amount))
                .isInstanceOf(RuleSetAdministration.ToleranceNotPermitted.class);
        RuleSetProposal twoWindows =
                proposal(source, "Two date windows", List.of(dateWindow(2), dateWindow(3)));
        assertThatThrownBy(() -> propose(PROPOSER, twoWindows))
                .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                .hasMessageContaining("tolerance_once");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                source)).as("nothing written").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event e"
                + " JOIN reconciliation.rule_set r ON r.id = e.rule_set_id"
                + " WHERE r.source_id = ?", source)).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetProposed' AND change_summary LIKE ?",
                "source=" + source + ",%")).isZero();

        UUID looseSource = IDS.next();
        UUID loose = IDS.next();
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            insertBareProposal(raw, loose, looseSource);
            execute(raw, "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                    + " currency, absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS',"
                    + " NULL, NULL, 2)", loose);
            SQLException secondWindow =
                    refused(raw, "INSERT INTO reconciliation.tolerance (rule_set_id,"
                            + " comparison, currency, absolute_minor, days) VALUES"
                            + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 3)", loose);
            assertThat(secondWindow.getSQLState()).isEqualTo(UNIQUE_VIOLATION);
            assertThat(secondWindow.getMessage()).contains("tolerance_once");

            insertBareProposal(raw, loose, looseSource);
            SQLException principal =
                    refused(raw, "INSERT INTO reconciliation.tolerance (rule_set_id,"
                            + " comparison, currency, absolute_minor, days) VALUES"
                            + " (?, 'PRINCIPAL_AMOUNT', 'EUR', 100, NULL)", loose);
            assertThat(principal.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(principal.getMessage()).contains("tolerance_comparison");
        }
    }

    // ----------------------------------------------------------------- (7) races

    @Test
    @Order(7)
    @DisplayName("ten racing approvers of one proposal: one activation, one retirement, two"
            + " history rows, nine told it is no longer pending - counted")
    void tenApproversActivateOnce() throws Exception {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        UUID pending =
                propose(PROPOSER, proposal(source, "The version ten reviewers race to approve"))
                        .ruleSetId();
        List<Future<String>> outcomes = race(10, racer -> {
            Actor approver = new Actor("op-approver-" + racer, ActorType.EMPLOYEE);
            try {
                approve(approver, pending, "Reviewed by racer " + racer);
                return "ACTIVATED";
            } catch (RuleSetAdministration.RuleSetNotPending lost) {
                return "NOT_PENDING";
            }
        });
        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(results).filteredOn("ACTIVATED"::equals).hasSize(1);
        assertThat(results).filteredOn("NOT_PENDING"::equals).hasSize(9);
        assertThat(lines("SELECT version || ':' || status FROM reconciliation.rule_set WHERE"
                + " source_id = ? ORDER BY version", source))
                .containsExactly("1:RETIRED", "2:ACTIVE");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event e"
                + " JOIN reconciliation.rule_set r ON r.id = e.rule_set_id"
                + " WHERE r.source_id = ? AND e.from_status IS NOT NULL", source))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ? AND to_status = 'RETIRED'", v1)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'", pending.toString()))
                .isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("ten racing proposals for one source: one PROPOSED version with its members,"
            + " nine RuleSetProposalPending - counted")
    void tenProposersLeaveOneProposal() throws Exception {
        UUID source = IDS.next();
        UUID v1 = seedVersionOne(source);
        RuleSetProposal shape = proposal(source, "shape");
        List<Future<String>> outcomes = race(10, racer -> {
            Actor proposer = new Actor("op-proposer-" + racer, ActorType.EMPLOYEE);
            try {
                propose(proposer, proposal(source, "Raced proposal " + racer));
                return "PROPOSED";
            } catch (RuleSetAdministration.RuleSetProposalPending pending) {
                return "PENDING";
            }
        });
        List<String> results = new ArrayList<>();
        for (Future<String> outcome : outcomes) {
            results.add(outcome.get());
        }
        assertThat(results).filteredOn("PROPOSED"::equals).hasSize(1);
        assertThat(results).filteredOn("PENDING"::equals).hasSize(9);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?"
                + " AND status = 'PROPOSED'", source)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                source)).isEqualTo(2);
        UUID winner = id("SELECT id FROM reconciliation.rule_set WHERE source_id = ? AND"
                + " status = 'PROPOSED'", source);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ?", winner)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule WHERE rule_set_id = ?",
                winner)).isEqualTo(shape.rules().size());
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_lag WHERE"
                + " rule_set_id = ?", winner)).isEqualTo(shape.lagDays().size());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetProposed' AND change_summary LIKE ?",
                "source=" + source + ",%")).isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?", v1))
                .isEqualTo("ACTIVE");
    }

    // ------------------------------------------- (9) the first version (P9-TSK-011, D26)

    @Test
    @Order(9)
    @DisplayName("the Phase 9 -> 10 transition: a first version that dates fewer kinds than its source settles is refused"
            + " RuleSetInvalid - an opener of the missing kind would roll back every money movement it opens - and nothing"
            + " is written")
    void aFirstVersionMustDateEveryKindItsSourceSettles() throws SQLException {
        assertThatThrownBy(() -> propose(PROPOSER, proposal(SETTLING_FX, "An FX source's first version, undated legs")))
                .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                .hasMessageContaining("FX_SELL_LEG").hasMessageContaining("FX_BUY_LEG");
        application.rollback();
        assertThat(lines("SELECT version || ':' || status FROM reconciliation.rule_set WHERE source_id = ?", SETTLING_FX))
                .as("nothing written").isEmpty();
    }


    @Test
    @Order(9)
    @DisplayName("a source with no ACTIVE version is admitted its FIRST version, activated by a second"
            + " person retiring nothing - one history edge, one audit naming it the first; until then"
            + " the opener's read is the typed RuleSetMissing")
    void aFirstVersionIsActivatedRetiringNothing() throws SQLException {
        UUID source = IDS.next();
        JdbcRuleSets ruleSets = new JdbcRuleSets();
        assertThatThrownBy(() -> ruleSets.activeFor(application, source))
                .isInstanceOf(RuleSetMissing.class)
                .satisfies(missing -> assertThat(((RuleSetMissing) missing).sourceId()).isEqualTo(source));
        assertThat(ruleSets.hasActive(application, source)).isFalse();
        application.rollback();

        RuleSetAdministration.Proposed proposed = propose(PROPOSER, proposal(source, "The new source's first version"));
        assertThat(proposed.version()).isEqualTo(1);
        RuleSetAdministration.Decided decided = approve(APPROVER, proposed.ruleSetId(), "Reviewed: first version");
        assertThat(decided.status()).isEqualTo(RuleSetStatus.ACTIVE);
        assertThat(decided.retiredRuleSetId()).as("nothing to retire").isEmpty();
        assertThat(lines("SELECT version || ':' || status FROM reconciliation.rule_set WHERE source_id = ?"
                + " ORDER BY version", source)).containsExactly("1:ACTIVE");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event e JOIN reconciliation.rule_set r"
                + " ON r.id = e.rule_set_id WHERE r.source_id = ? AND e.from_status IS NOT NULL", source))
                .as("one edge, PROPOSED -> ACTIVE").isEqualTo(1);
        assertThat(string("SELECT change_summary FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'", proposed.ruleSetId().toString()))
                .contains("activated=v1").contains("the source's first version");
        assertThat(ruleSets.activeFor(application, source).version()).isEqualTo(1);
        assertThat(ruleSets.hasActive(application, source)).isTrue();
        application.rollback();
    }

    @Test
    @Order(10)
    @DisplayName("the first version is four-eyes at both ranks: the proposer's approval refused, and a raw"
            + " self-activation refused by the CHECK")
    void aFirstVersionIsFourEyesAtBothRanks() throws SQLException {
        UUID source = IDS.next();
        RuleSetAdministration.Proposed proposed = propose(PROPOSER, proposal(source, "A first version"));
        assertThatThrownBy(() -> approve(PROPOSER, proposed.ruleSetId(), "my own first version"))
                .isInstanceOf(RuleSetAdministration.RuleSetActivationBySameActor.class);
        assertThat(string("SELECT status FROM reconciliation.rule_set WHERE id = ?", proposed.ruleSetId()))
                .isEqualTo("PROPOSED");
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            SQLException selfActivated = refused(raw, "UPDATE reconciliation.rule_set SET status = 'ACTIVE',"
                    + " decided_by = proposed_by, decided_at = now() WHERE id = ?", proposed.ruleSetId());
            assertThat(selfActivated.getSQLState()).isEqualTo(CHECK_VIOLATION);
            assertThat(selfActivated.getMessage()).contains("rule_set_activation_is_four_eyes");
        }
    }

    @Test
    @Order(11)
    @DisplayName("ten racing first-version proposers leave one proposal; ten racing approvers activate it once"
            + " - counted")
    void tenFirstVersionRacersActOnce() throws Exception {
        UUID source = IDS.next();
        List<String> proposals = new ArrayList<>();
        for (Future<String> outcome : race(10, racer -> {
            try {
                propose(new Actor("op-first-" + racer, ActorType.EMPLOYEE), proposal(source, "First " + racer));
                return "PROPOSED";
            } catch (RuleSetAdministration.RuleSetProposalPending pending) {
                return "PENDING";
            }
        })) {
            proposals.add(outcome.get());
        }
        assertThat(proposals).filteredOn("PROPOSED"::equals).hasSize(1);
        UUID pending = id("SELECT id FROM reconciliation.rule_set WHERE source_id = ? AND status = 'PROPOSED'", source);
        List<String> approvals = new ArrayList<>();
        for (Future<String> outcome : race(10, racer -> {
            try {
                approve(new Actor("op-approver-first-" + racer, ActorType.EMPLOYEE), pending, "Reviewed " + racer);
                return "ACTIVATED";
            } catch (RuleSetAdministration.RuleSetNotPending lost) {
                return "NOT_PENDING";
            } catch (RuleSetAdministration.RuleSetActivationBySameActor self) {
                return "SELF";
            }
        })) {
            approvals.add(outcome.get());
        }
        assertThat(approvals).filteredOn("ACTIVATED"::equals).hasSize(1);
        assertThat(lines("SELECT version || ':' || status FROM reconciliation.rule_set WHERE source_id = ?"
                + " ORDER BY version", source)).containsExactly("1:ACTIVE");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ? AND"
                + " operation = 'reconciliation.RuleSetActivated'", pending.toString())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the commands

    private static RuleSetAdministration.Proposed propose(Actor actor, RuleSetProposal proposal) {
        return inCommittedTransaction(
                unitOfWork ->
                        administration.propose(
                                unitOfWork, proposal, actor, PROPOSED_AT,
                                CorrelationId.generate(IDS)));
    }

    private static RuleSetAdministration.Decided approve(
            Actor actor, UUID ruleSetId, String reason) {
        return inCommittedTransaction(
                unitOfWork ->
                        administration.approve(
                                unitOfWork, ruleSetId, actor, reason, DECIDED_AT,
                                CorrelationId.generate(IDS)));
    }

    private static RuleSetAdministration.Decided reject(
            Actor actor, UUID ruleSetId, String reason) {
        return inCommittedTransaction(
                unitOfWork ->
                        administration.reject(
                                unitOfWork, ruleSetId, actor, reason, DECIDED_AT,
                                CorrelationId.generate(IDS)));
    }

    private static List<RuleSetStore.VersionView> versions(UUID source, int limit) {
        return inCommittedTransaction(
                unitOfWork -> administration.versions(unitOfWork, source, limit));
    }

    // ----------------------------------------------------------------- the content

    /** A well-formed successor of the seeded v1: it dates both of v1's kinds, and more. */
    private static RuleSetProposal proposal(UUID source, String reason) {
        return proposal(source, reason, List.of(
                new RuleSetProposal.Tolerance(
                        "PROCESSING_FEE_PER_BATCH", Optional.of(EUR), Optional.of(40L),
                        Optional.empty()),
                new RuleSetProposal.Tolerance(
                        "PROCESSING_FEE_PER_LINE", Optional.of(EUR), Optional.of(3L),
                        Optional.empty()),
                dateWindow(1)));
    }

    private static RuleSetProposal proposal(
            UUID source, String reason, List<RuleSetProposal.Tolerance> tolerances) {
        return new RuleSetProposal(
                source,
                3,
                120,
                Map.of(
                        ExpectationKind.CARD_CAPTURE, 2,
                        ExpectationKind.CARD_REFUND, 4,
                        ExpectationKind.DISPUTE_FEE, 3),
                List.of(
                        rule(1, ExternalLineType.CAPTURE, "PSP_CAPTURE_REF",
                                ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE, 48),
                        rule(2, ExternalLineType.CAPTURE, "ACQUIRER_REF",
                                ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE, 48),
                        rule(3, ExternalLineType.REFUND, "PSP_REFUND_REF",
                                ExpectationKind.CARD_REFUND, Cardinality.ONE_TO_ONE, 24),
                        rule(4, ExternalLineType.PROCESSING_FEE, "ORIGINAL_REF", null,
                                Cardinality.CHECK, 48)),
                tolerances,
                // Scale 6, as NUMERIC(7, 6) reads it back: the record's BigDecimal equality is
                // scale-sensitive.
                List.of(new RuleSetProposal.FeeTerms(
                        ExternalLineType.PROCESSING_FEE, EUR, new BigDecimal("0.017500"), 30, 2,
                        "HALF_UP")),
                Map.of(EUR, 250_000L, GBP, 200_000L),
                reason);
    }

    private static RuleSetProposal.Rule rule(
            int priority,
            ExternalLineType lineType,
            String keyKind,
            ExpectationKind kind,
            Cardinality cardinality,
            int graceHours) {
        return new RuleSetProposal.Rule(
                priority, lineType, Optional.ofNullable(keyKind), Optional.ofNullable(kind),
                cardinality, false, graceHours);
    }

    private static RuleSetProposal.Tolerance dateWindow(int days) {
        return new RuleSetProposal.Tolerance(
                "SETTLEMENT_DATE_DAYS", Optional.empty(), Optional.empty(), Optional.of(days));
    }

    // ----------------------------------------------------------------- seeding

    /**
     * The source's version 1, the `V012` way: born {@code PROPOSED} with its members in ONE
     * transaction, then activated by {@code test-activator} - dating CARD_CAPTURE and
     * CARD_REFUND, so a successor must date both.
     */
    private static UUID seedVersionOne(UUID source) {
        UUID ruleSet = IDS.next();
        inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at,"
                            + " correlation_id) VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?,"
                            + " 'test', NULL, 'RuleSetAdministrationDatabaseTest private"
                            + " rule set v1', now(), 'p8-tsk-022-test')",
                    ruleSet, source, java.sql.Date.valueOf(SEEDED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule_set_lag (rule_set_id, expectation_kind,"
                            + " lag_days) VALUES (?, 'CARD_CAPTURE', 3), (?, 'CARD_REFUND', 3)",
                    ruleSet, ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality,"
                            + " operation_anchored, grace_hours) VALUES (?, 1, 'CAPTURE',"
                            + " 'PSP_CAPTURE_REF', 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48)",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                            + " currency, absolute_minor, days) VALUES"
                            + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2)",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                            + " currency, high_value_minor) VALUES (?, 'EUR', 100000)",
                    ruleSet);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE',"
                            + " decided_by = 'test-activator', decided_at = now()"
                            + " WHERE id = ? AND status = 'PROPOSED'",
                    ruleSet);
            return null;
        });
        return ruleSet;
    }

    /** A bare PROPOSED version row in the caller's (uncommitted) transaction. */
    private static void insertBareProposal(Connection raw, UUID ruleSet, UUID source) {
        execute(raw,
                "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                        + " funding_lag_days, gain_min_age_days, effective_from, proposed_by,"
                        + " reason, created_at, correlation_id) VALUES (?, ?, 1, 'PROPOSED',"
                        + " 2, 90, ?, 'test', 'a bare proposal', now(), 'p8-tsk-022-test')",
                ruleSet, source, java.sql.Date.valueOf(SEEDED_ON));
    }

    // ----------------------------------------------------------------- plumbing

    @FunctionalInterface
    private interface Work<R> {
        R apply(Connection unitOfWork) throws SQLException;
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

    private static <R> List<Future<R>> race(int racers, IntFunction<R> act) throws Exception {
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

    /** Runs a statement the database must refuse; returns the refusal, the transaction gone. */
    private static SQLException refused(Connection unitOfWork, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            bind(statement, args);
            statement.execute();
        } catch (SQLException refusal) {
            unitOfWork.rollback();
            return refusal;
        }
        unitOfWork.rollback();
        throw new AssertionError("the database accepted what it must refuse: " + sql);
    }

    /** Commits a transaction a deferred rule must refuse; returns the refusal. */
    private static SQLException refusedAtCommit(Connection unitOfWork) throws SQLException {
        try {
            unitOfWork.commit();
        } catch (SQLException refusal) {
            unitOfWork.rollback();
            return refusal;
        }
        throw new AssertionError("the commit was accepted; the deferred rule did not refuse it");
    }

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            bind(statement, args);
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }

    private static List<String> lines(String sql, Object... args) throws SQLException {
        application.rollback();
        List<String> found = new ArrayList<>();
        try (PreparedStatement read = application.prepareStatement(sql)) {
            bind(read, args);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
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
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }
}
