package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CheckId;
import com.finapp.kyc.CheckStatus;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.DocumentCipher;
import com.finapp.kyc.JdbcCheckStore;
import com.finapp.kyc.JdbcKycCaseStore;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.VerificationCheck;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.JdbcFeeScheduleStore;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.JdbcPaymentAttemptStore;
import com.finapp.payments.JdbcRoutingStore;
import com.finapp.payments.PaymentDirection;
import com.finapp.payments.PaymentIntentId;
import com.finapp.payments.RailId;
import com.finapp.payments.RoutingPolicyVersion;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.JdbcRepudiationStore;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reads `P15-TSK-037` re-ranked, on PostgreSQL (`THREAT_MODEL.md` T13), each with a stamp written the wrong way round as a step or a skew would leave it, each
 * precondition asserted, every case in one transaction on a bootstrap connection rolled back at its end (a new version number or a planted stamp is seen by no other
 * suite). A stamp a trigger writes is planted with triggers off for that statement alone ({@code RiskStampOrderDatabaseTest}'s reason: the clock functions stay out of
 * it, so {@code P15-TSK-040} cannot make this fixture lie). <strong>Written and owed</strong> (C14): {@code P15-TST-002}.
 */
@Tag("database")
@DisplayName("the re-ranked reads answer by the rows, whatever a stepped or skewed clock wrote (P15-TSK-037)")
class StampRanksDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Instant T = Instant.parse("2031-05-06T10:00:00Z");
    private static final Clock AT_T = Clock.fixed(T, ZoneOffset.UTC);
    private static final Clock BEHIND_T = Clock.fixed(T.minus(Duration.ofSeconds(2)), ZoneOffset.UTC);
    private static final JdbcKycCaseStore KYC_CASES = new JdbcKycCaseStore();
    private static final CheckStore<Connection> KYC_CHECKS = new JdbcCheckStore(new DocumentCipher(new byte[32], 1, new SecureRandom()));
    private static final JdbcPaymentAttemptStore ATTEMPTS = new JdbcPaymentAttemptStore();
    private static final JdbcRoutingStore ROUTING = new JdbcRoutingStore();
    private static final JdbcFeeScheduleStore FEES = new JdbcFeeScheduleStore();
    private static final JdbcRepudiationStore REPUDIATIONS = new JdbcRepudiationStore();

    @Test
    @DisplayName("a customer's latest KYC case is ranked by case_order, not the UUIDv7 that an instance's clock minted the wrong way round")
    void theLatestCaseIsTheSequences() throws SQLException {
        rolledBack(uow -> {
            UUID customer = IDS.next();
            UUID earlierCaseWithAHigherId = UUID.fromString("ffffffff-ffff-7fff-8fff-ffffffffffff");
            UUID laterCaseWithALowerId = UUID.fromString("00000000-0000-7000-8000-000000000001");
            insertCase(uow, earlierCaseWithAHigherId, customer, "APPROVED");
            insertCase(uow, laterCaseWithALowerId, customer, "APPROVED");

            assertThat(KYC_CASES.findLatestFor(uow, customer)).map(found -> found.id().value()).contains(laterCaseWithALowerId);
        });
    }

    @Test
    @DisplayName("the newest onboarding check answers by check_order: a CLEAR asked after an INDETERMINATE converges though its stamp reads earlier")
    void theNewestCheckIsTheSequences() throws SQLException {
        rolledBack(uow -> {
            UUID kycCase = insertCase(uow, IDS.next(), IDS.next(), "OPEN");
            UUID indeterminate = IDS.next();
            UUID clear = IDS.next();
            insertCheck(uow, indeterminate, kycCase, "SANCTIONS", "INDETERMINATE", T, null);
            insertCheck(uow, clear, kycCase, "SANCTIONS", "CLEAR", T.minus(Duration.ofSeconds(2)), null);
            assertThat(truth(uow, "SELECT i.requested_at > c.requested_at FROM kyc.verification_check i, kyc.verification_check c WHERE i.id = '" + indeterminate + "' AND c.id = '"
                    + clear + "'")).as("precondition: the earlier-asked check carries the later stamp").isTrue();

            VerificationCheck fresh = VerificationCheck.request(IDS, AT_T, KycCaseId.of(kycCase), CheckType.SANCTIONS);
            CheckStore.Requested answer = KYC_CHECKS.requestOrConverge(uow, fresh);

            assertThat(answer.created()).as("the newest check (the CLEAR) is not retryable, so nothing new is asked").isFalse();
            assertThat(answer.check().id()).isEqualTo(CheckId.of(clear));
        });
    }

    @Test
    @DisplayName("the latest decided screening answers by check_order: a later ask's HIT is the answer though its stamp reads before an earlier ask's CLEAR")
    void theLatestDecidedScreeningIsTheSequences() throws SQLException {
        rolledBack(uow -> {
            UUID kycCase = insertCase(uow, IDS.next(), IDS.next(), "OPEN");
            UUID olderClear = IDS.next();
            UUID laterHit = IDS.next();
            insertCheck(uow, olderClear, kycCase, "SANCTIONS", "CLEAR", T, null);
            insertCheck(uow, laterHit, kycCase, "SANCTIONS", "HIT", T.minus(Duration.ofSeconds(2)), "rescreen-key-" + laterHit);
            assertThat(truth(uow, "SELECT c.check_order < h.check_order AND c.requested_at > h.requested_at FROM kyc.verification_check c, kyc.verification_check h"
                    + " WHERE c.id = '" + olderClear + "' AND h.id = '" + laterHit + "'")).as("precondition: the later ask carries the earlier stamp").isTrue();

            List<VerificationCheck> latest = KYC_CHECKS.latestDecidedScreenings(uow, KycCaseId.of(kycCase));

            assertThat(latest).extracting(VerificationCheck::status).containsExactly(CheckStatus.HIT);
        });
    }

    @Test
    @DisplayName("an intent's attempt is the highest attempt_order: the later attempt answers though its created_at reads earlier")
    void theAttemptIsTheSequences() throws SQLException {
        rolledBack(uow -> {
            UUID intent = IDS.next();
            UUID first = IDS.next();
            UUID second = IDS.next();
            insertIntent(uow, intent);
            insertAttempt(uow, first, intent, T);
            insertAttempt(uow, second, intent, T.minus(Duration.ofSeconds(2)));
            assertThat(truth(uow, "SELECT f.attempt_order < s.attempt_order AND f.created_at > s.created_at FROM payments.payment_attempt f, payments.payment_attempt s WHERE f.id = '"
                    + first + "' AND s.id = '" + second + "'")).as("precondition: the later attempt carries the earlier stamp").isTrue();

            assertThat(ATTEMPTS.findForIntent(uow, PaymentIntentId.of(intent))).map(attempt -> attempt.id().value()).contains(second);
        });
    }

    @Test
    @DisplayName("the routing decision read is the chosen one, though a refused one was stamped after it")
    void theChosenDecisionAnswers() throws SQLException {
        rolledBack(uow -> {
            UUID intent = IDS.next();
            insertIntent(uow, intent);
            RoutingPolicyVersion version = routingVersion(uow, ROUTING.nextVersionNumber(uow), AT_T);
            UUID chosen = IDS.next();
            UUID refused = IDS.next();
            insertDecision(uow, chosen, intent, version.id().value(), "card", 0, T.minus(Duration.ofSeconds(2)));
            // A chosen rail is the trail's own CHOSEN step, denormalised: RoutingDecision refuses the row without it.
            execute(uow, "INSERT INTO payments.routing_decision_step (decision_id, step_index, rail, verdict, rejection, rail_available, descriptor_version)"
                    + " VALUES (?, 0, 'card', 'CHOSEN', NULL, true, 1)", chosen);
            insertDecision(uow, refused, intent, version.id().value(), null, null, T);
            assertThat(truth(uow, "SELECT r.created_at > c.created_at FROM payments.routing_decision r, payments.routing_decision c WHERE r.id = '" + refused + "' AND c.id = '" + chosen + "'"))
                    .as("precondition: the refused decision carries the later stamp").isTrue();

            assertThat(ROUTING.findLatestDecisionForIntent(uow, PaymentIntentId.of(intent))).map(found -> found.id().value()).contains(chosen);
        });
    }

    @Test
    @DisplayName("an immediate routing version minted on a stepped or skewed clock starts at its immediate predecessor's, and supersedes it for good")
    void aVersionMintedBehindStillSupersedes() throws SQLException {
        rolledBack(uow -> {
            int number = ROUTING.nextVersionNumber(uow);
            RoutingPolicyVersion ahead = routingVersion(uow, number, AT_T);
            RoutingPolicyVersion askedBehind = routingCandidate(number + 1, BEHIND_T);
            assertThat(askedBehind.effectiveFrom()).as("precondition: the later version was minted on a clock behind the earlier one's").isBefore(ahead.effectiveFrom());

            RoutingPolicyVersion stored = ROUTING.insertVersionIfNumberIsFree(uow, askedBehind).orElseThrow();

            assertThat(stored.effectiveFrom()).as("the row's start is floored, and the insert hands the stored instant back").isEqualTo(ahead.effectiveFrom());
            assertThat(ROUTING.findVersionInForce(uow, T.plusSeconds(1))).map(RoutingPolicyVersion::version).contains(stored.version());
        });
    }

    @Test
    @DisplayName("an immediate fee version minted on a stepped or skewed clock starts at its immediate predecessor's: the newest rate is in force, not the older one")
    void aFeeVersionMintedBehindStillSupersedes() throws SQLException {
        rolledBack(uow -> {
            FeeSchedule schedule = FeeSchedule.create(IDS, "floored", CurrencyCode.of("EUR"), "op", CLOCK);
            FEES.insertSchedule(uow, schedule);
            FeeScheduleVersion ahead = FEES.insertVersionIfNumberIsFree(uow, feeVersion(schedule, 1, AT_T, Optional.empty())).orElseThrow();
            FeeScheduleVersion askedBehind = feeVersion(schedule, 2, BEHIND_T, Optional.empty());
            assertThat(askedBehind.effectiveFrom()).as("precondition: the later version was minted on a clock behind the earlier one's").isBefore(ahead.effectiveFrom());

            FeeScheduleVersion stored = FEES.insertVersionIfNumberIsFree(uow, askedBehind).orElseThrow();

            assertThat(stored.effectiveFrom()).as("the row's start is floored, and the insert hands the stored instant back").isEqualTo(ahead.effectiveFrom());
            assertThat(FEES.findEffectiveVersion(uow, schedule.id(), T.plusSeconds(1))).map(FeeScheduleVersion::version).contains(2);
        });
    }

    @Test
    @DisplayName("a scheduled fee version asked to start below an immediate predecessor's stored start is stored at that start, not shadowed by it, and the insert says so")
    void aScheduledStartBelowAnImmediatePredecessorIsFloored() throws SQLException {
        rolledBack(uow -> {
            FeeSchedule schedule = FeeSchedule.create(IDS, "floored scheduled", CurrencyCode.of("EUR"), "op", CLOCK);
            FEES.insertSchedule(uow, schedule);
            FeeScheduleVersion immediate = FEES.insertVersionIfNumberIsFree(uow, feeVersion(schedule, 1, AT_T, Optional.empty())).orElseThrow();
            Instant askedStart = T.minus(Duration.ofSeconds(1));
            FeeScheduleVersion scheduled = feeVersion(schedule, 2, BEHIND_T, Optional.of(askedStart));
            assertThat(scheduled.effectiveFrom()).as("precondition: a scheduled start, ahead of its own clock, below the immediate version's").isEqualTo(askedStart).isBefore(immediate.effectiveFrom());

            FeeScheduleVersion stored = FEES.insertVersionIfNumberIsFree(uow, scheduled).orElseThrow();

            assertThat(stored.effectiveFrom()).as("stored at the immediate version's start: asked and stored differ").isEqualTo(immediate.effectiveFrom());
            assertThat(FEES.findEffectiveVersion(uow, schedule.id(), T.plusSeconds(1))).map(FeeScheduleVersion::version).as("not shadowed").contains(2);
        });
    }

    @Test
    @DisplayName("the last resolved break on an expectation is the chain's tail whatever resolved_at says")
    void theResolvedBreakIsTheChainsTail() throws SQLException {
        rolledBack(uow -> {
            UUID expectation = IDS.next();
            UUID older = IDS.next();
            UUID newer = IDS.next();
            plant(uow, resolvedBreak(older, expectation, null, "clock_timestamp() + interval '1 hour'") + "; "
                    + resolvedBreak(newer, expectation, older, "clock_timestamp()"));
            assertThat(truth(uow, "SELECT o.resolved_at > n.resolved_at FROM reconciliation.break o, reconciliation.break n WHERE o.id = '" + older + "' AND n.id = '" + newer + "'"))
                    .as("precondition: the older break carries the later stamp").isTrue();

            assertThat(REPUDIATIONS.lastResolvedBreakOn(uow, expectation, BreakType.MISSING_EXTERNAL)).contains(newer);
        });
    }

    private static String resolvedBreak(UUID id, UUID expectation, UUID follows, String resolved) {
        return "INSERT INTO reconciliation.break (id, type, cause, status, severity, source_id, rule_set_id, expectation_id, value_at_issue_minor, currency, scale,"
                + " follows_break_id, raised_at, resolved_at, status_changed_at, correlation_id) VALUES ('" + id + "', 'MISSING_EXTERNAL', 'EXPECTATION_OVERDUE',"
                + " 'RESOLVED', 'LOW', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + expectation + "', 1, 'EUR', 2, "
                + (follows == null ? "NULL" : "'" + follows + "'") + ", clock_timestamp(), " + resolved + ", clock_timestamp(), 'c')";
    }

    private static UUID insertCase(Connection uow, UUID id, UUID customer, String status) throws SQLException {
        execute(uow, "INSERT INTO kyc.kyc_case (id, customer_id, case_kind, status, policy_version, opened_at, status_changed_at)"
                + " VALUES (?, ?, 'KYC', ?, 'p', ?, ?)", id, customer, status, Timestamp.from(T), Timestamp.from(T));
        return id;
    }

    private static void insertCheck(Connection uow, UUID id, UUID kycCase, String type, String status, Instant requestedAt, String rescreenKey) throws SQLException {
        execute(uow, "INSERT INTO kyc.verification_check (id, case_id, check_type, status, requested_at, status_changed_at, purpose, rescreen_key)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)", id, kycCase, type, status, Timestamp.from(requestedAt), Timestamp.from(requestedAt),
                rescreenKey == null ? "ONBOARDING" : "RESCREEN", rescreenKey);
    }

    private static void insertIntent(Connection uow, UUID intent) throws SQLException {
        execute(uow, "INSERT INTO payments.payment_intent (id, party_id, customer_id, payment_method_id, credit_account_id, amount_minor, currency, scale, status, created_at, capture_mode)"
                + " VALUES (?, ?, ?, ?, ?, 1000, 'EUR', 2, 'SUCCEEDED', ?, 'AUTOMATIC')", intent, IDS.next(), IDS.next(), IDS.next(), IDS.next(), Timestamp.from(T));
    }

    private static void insertAttempt(Connection uow, UUID attempt, UUID intent, Instant createdAt) throws SQLException {
        execute(uow, "INSERT INTO payments.payment_attempt (id, intent_id, auth_reference, capture_reference, auth_provider_reference, capture_provider_reference, authorized_amount_minor,"
                + " authorized_currency, authorized_scale, captured_amount_minor, captured_currency, captured_scale, status, created_at, rail, interaction_model)"
                + " VALUES (?, ?, ?, ?, ?, ?, 1000, 'EUR', 2, 1000, 'EUR', 2, 'CAPTURED', ?, 'card', 'TWO_STEP')",
                attempt, intent, "auth-" + IDS.next(), "cap-" + IDS.next(), "psp-a-" + IDS.next(), "psp-c-" + IDS.next(), Timestamp.from(createdAt));
    }

    private static RoutingPolicyVersion routingCandidate(int number, Clock clock) {
        return RoutingPolicyVersion.create(IDS, number, List.of(new RoutingPolicyVersion.NewRule(PaymentDirection.PAY_IN, InstrumentKind.CARD_TOKEN,
                Optional.empty(), Optional.empty(), List.of(RailId.of("card")))), Optional.empty(), "op-1", "the suite's reason", clock);
    }

    private static RoutingPolicyVersion routingVersion(Connection uow, int number, Clock clock) {
        return ROUTING.insertVersionIfNumberIsFree(uow, routingCandidate(number, clock)).orElseThrow();
    }

    private static FeeScheduleVersion feeVersion(FeeSchedule schedule, int number, Clock clock, Optional<Instant> effectiveFrom) {
        return FeeScheduleVersion.create(IDS, schedule, number, FeeRate.of(new BigDecimal("0.029")), Money.ofMinorUnits(30, CurrencyCode.of("EUR")),
                RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETAINED, effectiveFrom, "op", clock);
    }

    private static void insertDecision(Connection uow, UUID id, UUID intent, UUID version, String chosen, Integer matched, Instant createdAt) throws SQLException {
        execute(uow, "INSERT INTO payments.routing_decision (id, intent_id, policy_version_id, direction, instrument_kind, amount_minor, currency, scale, matched_rule_index, chosen_rail,"
                + " created_at) VALUES (?, ?, ?, 'PAY_IN', 'CARD_TOKEN', 1000, 'EUR', 2, ?, ?, ?)", id, intent, version, matched, chosen, Timestamp.from(createdAt));
    }

    private static void execute(Connection uow, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = uow.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    /** Planted rows: triggers off for the statements (a stamp's writer, the machines, the foreign keys), the transaction rolled back by its caller. */
    private static void plant(Connection uow, String sql) throws SQLException {
        try (Statement statement = uow.createStatement()) {
            statement.execute("SET LOCAL session_replication_role = replica; " + sql + "; SET LOCAL session_replication_role = origin");
        }
    }

    private static boolean truth(Connection uow, String sql) throws SQLException {
        try (Statement statement = uow.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getBoolean(1);
        }
    }

    private interface Work {
        void run(Connection uow) throws SQLException;
    }

    /** The case on one bootstrap connection, rolled back: planted rows, future-dated versions and a version number nothing else sees. */
    private static void rolledBack(Work work) throws SQLException {
        try (Connection uow = DatabaseRoles.bootstrap()) {
            uow.setAutoCommit(false);
            try {
                work.run(uow);
            } finally {
                uow.rollback();
            }
        }
    }
}
