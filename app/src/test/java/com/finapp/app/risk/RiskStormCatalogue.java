package com.finapp.app.risk;

import static com.finapp.app.risk.RiskStormWorld.RANDOMNESS;
import static com.finapp.app.risk.RiskStormWorld.count;
import static com.finapp.app.risk.RiskStormWorld.judged;
import static com.finapp.app.risk.RiskStormWorld.key;
import static com.finapp.app.risk.RiskStormWorld.refused;
import static com.finapp.app.risk.RiskStormWorld.scalar;
import static com.finapp.app.risk.RiskStormWorld.strings;

import com.finapp.app.credit.RiskStormCredit;
import com.finapp.app.fx.RiskStormCrossBorder;
import com.finapp.app.risk.RiskStormTest.Instance;
import com.finapp.app.risk.RiskStormWorld.Answer;
import com.finapp.app.risk.RiskStormWorld.Audience;
import com.finapp.app.risk.RiskStormWorld.Customer;
import com.finapp.app.risk.RiskStormWorld.Merchant;
import com.finapp.app.risk.RiskStormWorld.Operator;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.ParameterValue;
import com.finapp.risk.RiskOperation;
import com.finapp.risk.RiskPolicy;
import com.finapp.risk.RiskRule;
import com.finapp.risk.RuleKind;
import com.finapp.risk.RuleParameters;
import com.finapp.risk.testing.RiskPolicyFixtures;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.assertj.core.api.SoftAssertions;

/**
 * The risk storm's catalogue (`P13-TST-001`): the fifteen mandatory scenarios of {@code PHASE_13_PLAN.md} section 13.2 -
 * one method each, named after it - the named lock waits, the held withdrawals' races, the fail-safe's measured windows,
 * the investigators and officers of the load, and the crash points; each scene choreographed on both instances at a
 * barrier on subjects it owns ({@link RiskStormTest#reserved}) and followed by the full census at rest. Every count is
 * read from rows into the coverage ledger, which the end asserts against its minimums.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class RiskStormCatalogue {

    private final RiskStormTest storm;

    RiskStormCatalogue(RiskStormTest storm) {
        this.storm = storm;
    }

    private RiskStormWorld world() {
        return storm.world;
    }

    private Instance a() {
        return storm.a;
    }

    private Instance b() {
        return storm.b;
    }

    private RiskStormWorld.Tally tally() {
        return storm.tally;
    }

    private void covered(String what, long count) {
        storm.coverage.merge(what, count, Long::sum);
    }

    /** A customer the scene owns - funded, reserved from every mover. */
    private Customer fresh(long minor) throws Exception {
        Customer customer = world().customer(minor);
        storm.reserved.add(customer.party);
        return customer;
    }

    private Instance either() {
        return RANDOMNESS.nextBoolean() ? a() : b();
    }

    // ================================================================== v2 - the mid-storm versions

    /**
     * {@code operation}'s mid-storm version: v1 with every {@code VELOCITY_COUNT}'s threshold four times as high - a
     * different version the pin must keep apart from its predecessor (an assessment under v1 and one under v2 judge one
     * reading differently), and a load that stays judgeable past v1's ten a day.
     */
    static RiskPolicy v2(RiskOperation operation) {
        RiskPolicy v1 = RiskPolicyFixtures.v1(operation);
        List<RiskRule> rules = new ArrayList<>();
        for (RiskRule rule : v1.rules()) {
            if (rule.kind() == RuleKind.VELOCITY_COUNT
                    && rule.parameters().values().get("threshold_count") instanceof ParameterValue.Count count) {
                Map<String, ParameterValue> values = new LinkedHashMap<>(rule.parameters().values());
                values.put("threshold_count", new ParameterValue.Count(count.value() * 4));
                rules.add(new RiskRule(rule.ordinal(), rule.kind(), RuleParameters.of(rule.kind(), values),
                        rule.weight(), rule.hardDecision()));
            } else {
                rules.add(rule);
            }
        }
        return new RiskPolicy(v1.operation(), v1.engine(), v1.currency(), rules, v1.scoreBands(), v1.ratingBands(),
                v1.failSafeBands(), v1.challengeAssurance(), v1.overrideThreshold(), v1.holdValidity());
    }

    // ================================================================== scenario 9's first half - no policy

    /**
     * Before any activation (the production default, `INV-RSK-05`): a measured window in which each judged operation is
     * driven by fresh customers - every seal {@code ALLOW(NO_POLICY_ACTIVE)}, counted, the movement committed.
     */
    void noPolicyWindow() throws Exception {
        long before = count("SELECT count(*) FROM risk.risk_assessment");
        Customer payer = fresh(10_000_00);
        Customer payee = fresh(1_00);
        Merchant merchant = storm.merchants.get(0);
        for (int i = 0; i < 3; i++) {
            Instance on = i % 2 == 0 ? a() : b();
            judged(tally(), "no policy: transfer", on.door().post(RiskStormWorld.TRANSFERS,
                    RiskStormTest.transferBody(payer.wallet, payee.wallet, 10_00 + i), payer.mfaToken, key(),
                    Audience.SUBJECT));
            judged(tally(), "no policy: withdrawal", on.door().post(RiskStormWorld.WITHDRAWALS,
                    RiskStormTest.withdrawalBody(payer, 10_00 + i), payer.mfaToken, key(), Audience.SUBJECT));
            String card = storm.checkoutSession(on, merchant, 10_00 + i);
            judged(tally(), "no policy: card confirmation", on.door().post(RiskStormWorld.CONFIRMATION,
                    RiskStormTest.confirmation(card, payer.card), payer.mfaToken, null, Audience.SUBJECT));
            String wallet = storm.checkoutSession(on, merchant, 11_00 + i);
            judged(tally(), "no policy: wallet confirmation", on.door().post(RiskStormWorld.CONFIRMATION,
                    RiskStormTest.confirmation(wallet, null), payer.mfaToken, null, Audience.SUBJECT));
        }
        storm.beneficiaries.put(payer.party, Map.of("US-USD", storm.beneficiary(payer, "US", "USD")));
        RiskStormCrossBorder.references();
        Answer quoted = a().door().post(RiskStormCrossBorder.QUOTES, "{\"beneficiaryId\":\""
                + storm.beneficiaries.get(payer.party).get("US-USD") + "\",\"sourceCurrency\":\"EUR\",\"fixedSide\":"
                + "\"FIXED_SOURCE\",\"amount\":\"20.00\"}", payer.mfaToken, key(), Audience.SUBJECT);
        if (judged(tally(), "no policy: cross-border quote", quoted) && quoted.ok()) {
            judged(tally(), "no policy: cross-border payment", b().door().post(RiskStormCrossBorder.PAYMENTS,
                    "{\"quoteId\":\"" + quoted.field("id") + "\"}", payer.mfaToken, key(), Audience.SUBJECT));
        }
        for (String operation : List.of("TRANSFER", "WITHDRAWAL", "CARD_PAYMENT", "WALLET_PAYMENT", "CROSS_BORDER")) {
            long sealed = count("SELECT count(*) FROM risk.risk_assessment WHERE operation = ? AND party_id = ? AND"
                    + " decision = 'ALLOW' AND fail_safe_reason = 'NO_POLICY_ACTIVE' AND velocity_outcome = 'COUNTED'",
                    operation, payer.party);
            covered("scenario 9 (no policy): " + operation + " sealed ALLOW(NO_POLICY_ACTIVE) and counted", sealed);
        }
        covered("scenario 9 (no policy): assessments in the window",
                count("SELECT count(*) FROM risk.risk_assessment") - before);
        storm.restAndCensus("scenario 9 - no policy");
    }

    // ================================================================== scenario 9's second half - the timeout window

    /**
     * The fail-safe forced in a measured window (round 2): four movers' PARTY subject locks (R2a) held past the 250 ms
     * budget while their movements are judged - each sealed the band's fail-safe decision with cause TIMEOUT, the
     * movement committing that outcome, the transaction never aborted; the {@code RiskEvaluationUnavailable} rule must
     * fire at the round's rest.
     */
    void failSafeWindow(Duration length) throws Exception {
        List<Customer> held = new ArrayList<>();
        for (Customer customer : storm.pool) {
            if (held.size() < 4 && !storm.reserved.contains(customer.party)) {
                held.add(customer);
            }
        }
        held.forEach(customer -> storm.blocked.add(customer.party));
        held.forEach(customer -> storm.forced.add(customer.party.toString()));
        Thread.sleep(1_000); // the movers in flight finish under the old set
        long before = count("SELECT count(*) FROM risk.risk_assessment WHERE unavailable_cause = 'TIMEOUT'");
        try (AutoCloseable locks = RiskStormTest.holdPartyLocks(held.stream().map(c -> c.party).toList())) {
            long end = System.nanoTime() + length.toNanos();
            int i = 0;
            while (System.nanoTime() < end) {
                Customer payer = held.get(i % held.size());
                Customer payee = held.get((i + 1) % held.size());
                Instance on = i % 2 == 0 ? a() : b();
                long amount = i % 3 == 0 ? 1_000_00 : 99_99;
                judged(tally(), "fail-safe window: transfer", on.door().post(RiskStormWorld.TRANSFERS,
                        RiskStormTest.transferBody(payer.wallet, payee.wallet, amount), payer.mfaToken, key(),
                        Audience.SUBJECT));
                judged(tally(), "fail-safe window: withdrawal", on.door().post(RiskStormWorld.WITHDRAWALS,
                        RiskStormTest.withdrawalBody(payer, 10_00), payer.mfaToken, key(), Audience.SUBJECT));
                i++;
            }
        } finally {
            held.forEach(customer -> storm.blocked.remove(customer.party));
        }
        long timeouts = count("SELECT count(*) FROM risk.risk_assessment WHERE unavailable_cause = 'TIMEOUT'") - before;
        covered("scenario 9 (timeout): fail-safe decisions sealed TIMEOUT in the window", timeouts);
        covered("scenario 9 (timeout): transfers under EUR 1,000 ALLOW(RISK_UNAVAILABLE) and committed", count(
                "SELECT count(*) FROM risk.risk_assessment a JOIN transfers.transfer t ON t.id::text = a.command_ref WHERE"
                        + " a.operation = 'TRANSFER' AND a.unavailable_cause = 'TIMEOUT' AND a.decision = 'ALLOW' AND"
                        + " t.status = 'COMPLETED'"));
        covered("scenario 9 (timeout): transfers at EUR 1,000 DENY(RISK_UNAVAILABLE), FAILED(RISK_REFUSED)", count(
                "SELECT count(*) FROM risk.risk_assessment a JOIN transfers.transfer t ON t.id::text = a.command_ref WHERE"
                        + " a.operation = 'TRANSFER' AND a.unavailable_cause = 'TIMEOUT' AND a.decision = 'DENY' AND"
                        + " t.failure_reason = 'RISK_REFUSED'"));
        covered("scenario 9 (timeout): withdrawals DENY at every band, FAILED with the hold released", count(
                "SELECT count(*) FROM risk.risk_assessment a JOIN payments.withdrawal w ON w.id::text = a.command_ref"
                        + " JOIN ledger.hold h ON h.id = w.hold_reference WHERE a.operation = 'WITHDRAWAL' AND"
                        + " a.unavailable_cause = 'TIMEOUT' AND a.decision = 'DENY' AND w.status = 'FAILED' AND"
                        + " h.status = 'RELEASED'"));
        storm.breaks("round 2", "RiskEvaluationUnavailable");
    }

    // ================================================================== the load's investigators and officers

    /**
     * An investigator: the oldest open case of the queue taken (the revision read), noted, and closed - a false
     * positive by one person, or a confirmation proposed and approved by a second analyst from a MULTI_FACTOR session.
     */
    void investigateOnce(Instance on) throws Exception {
        Thread.sleep(800 + RANDOMNESS.nextInt(1_200));
        Operator analyst = storm.analysts[RANDOMNESS.nextInt(2)];
        Answer page = on.door().get(RiskStormWorld.CASES + "?status=OPEN&limit=10", analyst.token(), Audience.OPERATOR);
        if (!judged(tally(), "case queue", page) || !page.body().contains("\"caseId\"")) {
            return;
        }
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher found = java.util.regex.Pattern.compile("\"caseId\":\"([^\"]+)\"").matcher(page.body());
        while (found.find()) {
            ids.add(found.group(1));
        }
        String caseId = ids.get(RANDOMNESS.nextInt(ids.size()));
        String[] head = RiskStormWorld.row("SELECT revision, subject_ref, kind FROM risk.risk_case WHERE id = ?::uuid",
                caseId);
        if (head == null || isReservedSubject(head[1])) {
            return;
        }
        int revision = Integer.parseInt(head[0]);
        Answer taken = on.door().post(RiskStormWorld.CASES + "/" + caseId + "/actions", "{\"act\":\"TAKE\",\"revision\":"
                + revision + "}", analyst.token(), key(), Audience.OPERATOR);
        if (!judged(tally(), "case take", taken, "409 risk.CaseRevisionStale", "409 risk.CaseNotOpen",
                "403 risk.SelfDealingRefused", "409 risk.CaseActNotAdmitted")) {
            return;
        }
        if (!taken.ok()) {
            return;
        }
        revision = (int) taken.number("revision");
        Answer noted = storm.other(on).door().post(RiskStormWorld.CASES + "/" + caseId + "/actions", "{\"act\":\"NOTE\","
                + "\"revision\":" + revision + ",\"text\":\"looked at the movements\"}", analyst.token(), key(),
                Audience.OPERATOR);
        if (!judged(tally(), "case note", noted, "409 risk.CaseRevisionStale") || !noted.ok()) {
            return;
        }
        revision = (int) noted.number("revision");
        if ("COLLECTIONS".equals(head[2]) || RANDOMNESS.nextInt(4) > 0) {
            String disposition = "COLLECTIONS".equals(head[2]) ? "CLOSED_NO_ACTION" : "CLOSED_FALSE_POSITIVE";
            Answer closed = on.door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                    + revision + ",\"disposition\":\"" + disposition + "\",\"reason\":\"explained by the activity\"}",
                    analyst.token(), key(), Audience.OPERATOR);
            judged(tally(), "case closed by one person", closed, "409 risk.CaseRevisionStale");
            return;
        }
        // A confirmation: four eyes - the proposer's own approval refused (scenario 15), a second analyst approves.
        Answer proposed = on.door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                + revision + ",\"disposition\":\"" + ("AML".equals(head[2]) ? "CONFIRMED_SUSPICIOUS" : "CONFIRMED_FRAUD")
                + "\",\"reason\":\"the pattern is confirmed\"}", analyst.token(), key(), Audience.OPERATOR);
        if (!judged(tally(), "case confirmation proposed", proposed, "409 risk.CaseRevisionStale") || !proposed.ok()) {
            return;
        }
        String dispositionId = proposed.field("dispositionId");
        int proposedRevision = (int) proposed.number("revision");
        String approval = RiskStormWorld.CASES + "/" + caseId + "/dispositions/" + dispositionId + "/approval";
        // Refused either way: the case's CAS is read before the second person (a fold from the load's movements may
        // have moved the revision since the proposal - a stale self-approval is a 409, never an approval).
        refused(tally(), "scenario 15: a disposition approved by its proposer", storm.other(on).door().post(approval,
                "{\"revision\":" + proposedRevision + ",\"reason\":\"mine\"}", analyst.token(), key(),
                Audience.OPERATOR), "403 risk.SelfApprovalRefused", "409 risk.CaseRevisionStale");
        Operator second = storm.analysts[2 + RANDOMNESS.nextInt(2)];
        judged(tally(), "case confirmation approved", storm.other(on).door().post(approval, "{\"revision\":"
                + proposedRevision + ",\"reason\":\"agreed\"}", second.token(), key(), Audience.OPERATOR),
                "409 risk.CaseRevisionStale");
    }

    private boolean isReservedSubject(String subjectRef) {
        try {
            return storm.reserved.contains(UUID.fromString(subjectRef));
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    /**
     * An officer: a HELD withdrawal of a mover overridden - released (PROCEED) or denied (REFUSE) - proposed by one
     * officer, the proposer's own approval refused (scenario 15), approved by a second from a MULTI_FACTOR session; the
     * action dispatched by the platform.
     */
    void overrideOnce() throws Exception {
        Thread.sleep(2_000 + RANDOMNESS.nextInt(2_000));
        String[] held = RiskStormWorld.row("SELECT a.id::text, w.party_id::text FROM payments.withdrawal w JOIN"
                + " risk.risk_assessment a ON a.operation = 'WITHDRAWAL' AND a.command_ref = w.id::text WHERE w.status ="
                + " 'HELD' AND NOT EXISTS (SELECT 1 FROM risk.decision_override o WHERE o.assessment_id = a.id) ORDER BY"
                + " random() LIMIT 1");
        if (held == null || storm.reserved.contains(UUID.fromString(held[1]))) {
            return;
        }
        override(held[0], RANDOMNESS.nextInt(3) > 0, "the load's override");
    }

    /** One override of {@code assessment}, four eyes across the instances; the override's id, or null when refused. */
    String override(String assessment, boolean release, String why) throws Exception {
        Operator proposer = storm.officers[RANDOMNESS.nextInt(storm.officers.length)];
        Operator approver = storm.officers[(List.of(storm.officers).indexOf(proposer) + 1) % storm.officers.length];
        Instance on = either();
        Answer proposed = on.door().post(RiskStormWorld.ASSESSMENTS + "/" + assessment + "/overrides",
                "{\"reasonCode\":\"" + (release ? "CUSTOMER_VERIFIED" : "RISK_CONFIRMED") + "\",\"reason\":\"" + why
                        + "\",\"resolution\":\"" + (release ? "PROCEED" : "REFUSE") + "\"}", proposer.token(), key(),
                Audience.OPERATOR);
        if (!judged(tally(), "override proposed", proposed, "409 risk.OverrideStale")
                || !proposed.ok()) {
            return null;
        }
        String overrideId = proposed.field("overrideId");
        String approval = RiskStormWorld.ASSESSMENTS + "/" + assessment + "/overrides/" + overrideId + "/approval";
        if (proposed.body().contains("\"requiresSecondPerson\":true")) {
            // C7: at or above the pinned threshold, or for a DENY - four eyes; the proposer refused, recorded FAILED.
            refused(tally(), "scenario 15: an override approved by its proposer", storm.other(on).door().post(approval,
                    "{\"reason\":\"mine\"}", proposer.token(), key(), Audience.OPERATOR), "403 risk.SelfApprovalRefused");
            judged(tally(), "override approved by a second officer", storm.other(on).door().post(approval,
                    "{\"reason\":\"a second pair of eyes\"}", approver.token(), key(), Audience.OPERATOR),
                    "409 risk.OverrideStale");
        } else {
            // C7: below the threshold the proposer's own reasoned approval completes it (audited as such).
            judged(tally(), "override approved by its proposer below the threshold", storm.other(on).door().post(
                    approval, "{\"reason\":\"below the threshold\"}", proposer.token(), key(), Audience.OPERATOR),
                    "409 risk.OverrideStale");
        }
        return overrideId;
    }

    // ================================================================== mid-load acts

    /** A reprocessing ordered from an earlier watermark mid-load (round 5): the officer's door, keyed. */
    void reprocessMidLoad() throws Exception {
        long sequence = Long.parseLong(scalar("SELECT last_sequence FROM risk.monitoring_watermark WHERE source ="
                + " 'ledger.journal'"));
        long from = Math.max(0, sequence - 60_000);
        Answer ordered = either().door().post(RiskStormWorld.REPROCESSING, "{\"fromSequence\":" + from
                + ",\"reason\":\"the storm re-reads a minute of history\"}", storm.officers[0].token(), key(),
                Audience.OPERATOR);
        judged(tally(), "reprocessing mid-load", ordered, "422 risk.ReprocessingNotBehind");
        if (ordered.ok()) {
            covered("a reprocessing from an earlier watermark mid-load", 1);
        }
    }

    // ================================================================== the catalogue

    void run() throws Exception {
        if (wanted("s01")) {
            s01TheSameEventThreeTimesDelayedAndReorderedIsOneSignalOneAlertOneCase();
        }
        if (wanted("s02")) {
            s02TwoInstancesRaiseTheSameAlertConcurrentlyOneRow();
        }
        if (wanted("s03")) {
            s03APolicyActivatedWhileTenEvaluationsRunPinsOneVersionEach();
        }
        if (wanted("s04")) {
            s04AnActionTimingOutAtTheOwningDomainIsRetriedForOneEffect();
        }
        if (wanted("s05")) {
            s05TwoInvestigatorsUpdatingOneCaseKeepBothActsAndOne409();
        }
        if (wanted("s06")) {
            s06ADelayedEventAfterItsCaseIsDisposedOpensANewCase();
        }
        if (wanted("s07")) {
            s07ADownstreamFailureAfterTheDecisionLeavesTheDecisionStanding();
        }
        if (wanted("s08")) {
            s08HistoryReplayedFromAnEarlierWatermarkCreatesNothingNew();
        }
        if (wanted("s09")) {
            s09RiskUnavailableCommitsTheBandsOutcome();
        }
        if (wanted("s10")) {
            s10AHeldWithdrawalReleasedOnceDeniedOnceAndRacingItsExpiry();
        }
        if (wanted("s11")) {
            s11AnAccountTakeoverSequenceIsHeldAndCased();
        }
        if (wanted("s12")) {
            s12AStructuringSequenceIsAlertedOncePerWindowAndCased();
        }
        if (wanted("s13")) {
            s13AProfileChangeAfterACreditDecisionReplaysIdentical();
        }
        if (wanted("s14")) {
            s14TenConcurrentMovementsOfOneSubjectCountTenARefusalZero();
        }
        if (wanted("s15")) {
            s15EveryFourEyesActIsRefusedToItsProposerAndRecordedFailed();
        }
        if (wanted("filing")) {
            aFilingWithALostResponseIsAcknowledgedAndNeverFiledTwice();
        }
        if (wanted("locks")) {
            theNamedLockWaitsAreCounted();
        }
    }

    private static boolean wanted(String scene) {
        return RiskStormTest.wanted(scene);
    }

    // ---------------------------------------------------------------- scenario 1

    /**
     * Scenario 1: the same default event (a seeded loan's {@code lending.LoanDefaulted}) delivered three times - two
     * at once on A and B, a third delayed until after a later event - and an older delinquency event of the agreement
     * reordered behind it: one signal of the default, one {@code OBLIGATION_DEFAULTED} alert, one {@code COLLECTIONS}
     * case.
     */
    void s01TheSameEventThreeTimesDelayedAndReorderedIsOneSignalOneAlertOneCase() throws Exception {
        Customer borrower = fresh(1_00);
        UUID loan = seededLoan(borrower.party);
        String today = scalar("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD')");
        var defaulted = RiskStormRelay.synthesized("lending.LoanDefaulted", loan, "loan", Instant.now(), "lending",
                condition("loanId", loan, today, true).toBytes());
        // lending's delinquency change names the bucket it left (LoanConditions: previousBucket) - without it risk's
        // mapping refuses the event, and the reordered copy would test nothing (the counted run's relay found it).
        var delinquent = RiskStormRelay.synthesized("lending.LoanDelinquencyChanged", loan, "loan",
                Instant.now().minusSeconds(3_600), "lending", condition("loanId", loan, today, false)
                        .with("previousBucket", "B1_1_29").toBytes());
        List<Object> raced = RiskStormLocks.race(List.of(
                () -> storm.relay.deliver(new RiskStormRelay.Target("A", a().beans()), defaulted),
                () -> storm.relay.deliver(new RiskStormRelay.Target("B", b().beans()), defaulted)));
        storm.relay.deliver(new RiskStormRelay.Target("B", b().beans()), delinquent); // older, reordered behind
        storm.relay.deliver(new RiskStormRelay.Target("A", a().beans()), defaulted); // the third copy, delayed
        long signals = count("SELECT count(*) FROM risk.risk_signal WHERE source_event_id = ?",
                defaulted.eventId().value());
        long alerts = count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind ="
                + " 'OBLIGATION_DEFAULTED'", loan.toString());
        long cases = count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind = 'COLLECTIONS'",
                borrower.party.toString());
        long attached = count("SELECT count(*) FROM risk.risk_alert a JOIN risk.risk_case c ON c.id = a.attached_case_id"
                + " WHERE a.subject_ref = ? AND c.subject_ref = ?", loan.toString(), borrower.party.toString());
        expect("scenario 1: one signal of the event delivered three times", signals, 1);
        expect("scenario 1: the older delinquency, reordered behind the default, recorded once", count("SELECT count(*)"
                + " FROM risk.risk_signal WHERE source_event_id = ?", delinquent.eventId().value()), 1);
        expect("scenario 1: one OBLIGATION_DEFAULTED alert", alerts, 1);
        expect("scenario 1: one COLLECTIONS case", cases, 1);
        expect("scenario 1: the alert attached to the case", attached, 1);
        covered("scenario 1: deliveries of one event (raced on A and B, then delayed)", raced.size() + 1);
        storm.restAndCensus("scenario 1");
    }

    // ---------------------------------------------------------------- scenario 2

    /**
     * Scenario 2: two instances raise one alert concurrently - a party's credential change recorded, then two withdrawals
     * from two of its wallets at once, one on A and one on B, each judged HOLD with the account-takeover raise in its
     * T-eval: one {@code ACCOUNT_TAKEOVER} row, one {@code risk.AlertRaised}.
     */
    void s02TwoInstancesRaiseTheSameAlertConcurrentlyOneRow() throws Exception {
        Customer victim = fresh(20_000_00);
        credentialChange(victim);
        storm.relay.drain(5); // the change recorded before the movements (the relay owns delivery)
        List<Object> raced = RiskStormLocks.race(List.of(
                () -> a().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(victim, 10_00),
                        victim.mfaToken, key(), Audience.SUBJECT),
                () -> b().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(victim, 11_00),
                        victim.mfaToken, key(), Audience.SUBJECT)));
        for (Object answer : raced) {
            judged(tally(), "scenario 2: a withdrawal after a credential change", (Answer) answer);
        }
        long alerts = count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'ACCOUNT_TAKEOVER'",
                victim.party.toString());
        long raised = count("SELECT count(*) FROM platform.outbox_event e JOIN risk.risk_alert a ON a.id ="
                + " e.aggregate_id WHERE e.event_type = 'risk.AlertRaised' AND a.subject_ref = ? AND a.kind ="
                + " 'ACCOUNT_TAKEOVER'", victim.party.toString());
        long heldOrRefused = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND operation ="
                + " 'WITHDRAWAL' AND decision IN ('HOLD', 'DENY')", victim.party);
        expect("scenario 2: one ACCOUNT_TAKEOVER row raised by two instances", alerts, 1);
        expect("scenario 2: one risk.AlertRaised", raised, 1);
        expect("scenario 2: both withdrawals judged HOLD or DENY", heldOrRefused, 2);
        storm.restAndCensus("scenario 2");
    }

    // ---------------------------------------------------------------- scenario 3

    /**
     * Scenario 3: a TRANSFER version activated while ten transfers are judged, five on each instance, all at one
     * barrier - every assessment pins exactly one version (the predecessor or the successor), none "neither"; the
     * activation never waits on a money row (its backend sampled: only risk relations); R1's waits counted.
     */
    void s03APolicyActivatedWhileTenEvaluationsRunPinsOneVersionEach() throws Exception {
        String predecessor = scalar("SELECT id::text FROM risk.risk_policy_version WHERE operation = 'TRANSFER' AND"
                + " status = 'ACTIVE'");
        Operator proposer = storm.officers[0];
        Operator activator = storm.officers[1];
        Answer proposed = a().door().post(RiskStormWorld.POLICIES, RiskStormWorld.proposal(v2(RiskOperation.TRANSFER),
                "scenario 3's successor"), proposer.token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 3: successor proposed", proposed);
        String successor = proposed.field("id");
        List<Customer> payers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            payers.add(fresh(1_000_00));
        }
        Customer payee = fresh(1_00);
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Customer payer = payers.get(i);
            Instance on = i % 2 == 0 ? a() : b();
            calls.add(() -> on.door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet,
                    payee.wallet, 25_00), payer.mfaToken, key(), Audience.SUBJECT));
        }
        calls.add(() -> b().door().post(RiskStormWorld.POLICIES + "/" + successor + "/activation",
                "{\"reason\":\"scenario 3\"}", activator.token(), key(), Audience.OPERATOR));
        ActivationWaits waits = new ActivationWaits();
        List<Object> answers;
        try (waits) {
            answers = RiskStormLocks.race(calls);
        }
        for (Object answer : answers) {
            judged(tally(), "scenario 3: a racer", (Answer) answer);
        }
        long pinned = 0;
        long neither = 0;
        for (Customer payer : payers) {
            String pin = scalar("SELECT coalesce(policy_version_id::text, 'NONE') FROM risk.risk_assessment WHERE"
                    + " operation = 'TRANSFER' AND party_id = ?", payer.party);
            if (predecessor.equals(pin) || successor.equals(pin)) {
                pinned++;
            } else {
                neither++;
            }
        }
        expect("scenario 3: ten assessments, each pinning the predecessor or the successor", pinned, 10);
        expect("scenario 3: none neither", neither, 0);
        expect("scenario 3: the activation waited on no money row", waits.moneyWaits(), 0);
        covered("scenario 3: R1 waits (an evaluation's FOR SHARE behind the activation, or the activation behind it)",
                waits.policyWaits());
        covered("scenario 3: pinned the successor", count("SELECT count(*) FROM risk.risk_assessment WHERE"
                + " policy_version_id = ?::uuid", successor));
        theA3I1WindowHeldOpen(proposer, activator);
        storm.restAndCensus("scenario 3");
    }

    /**
     * Scenario 3's second half - the A3-I1 window held open, every time. Ten transfers, five on each instance, each to a
     * payee of its own (no two share a money row), are parked at their money locks - the payers' wallet rows held
     * {@code FOR UPDATE} by the scene, so each has passed its door's step-up advice and stands inside its command's
     * transaction, before T-eval. Then a further TRANSFER version is activated with its retirement of the predecessor
     * sleeping 1.5 s under the predecessor's row lock (a slow commit: the window a real activation has for microseconds),
     * and the wallets are released into that second - every pin queues {@code FOR SHARE} behind the activation, re-checks
     * the row it waited on and finds it {@code RETIRED}. The shared re-read must then find the successor: ten pinned the
     * successor, none "neither". The natural race above never put an evaluation's pin in the window (the probe that
     * removed the re-read survived it; so did a first version of this half, whose pins the door's advice had already
     * absorbed); this half cannot miss it.
     */
    private void theA3I1WindowHeldOpen(Operator proposer, Operator activator) throws Exception {
        String predecessor = scalar("SELECT id::text FROM risk.risk_policy_version WHERE operation = 'TRANSFER' AND"
                + " status = 'ACTIVE'");
        Answer proposed = a().door().post(RiskStormWorld.POLICIES, RiskStormWorld.proposal(v2(RiskOperation.TRANSFER),
                "scenario 3's successor in the held window"), proposer.token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 3 (held window): successor proposed", proposed);
        String successor = proposed.field("id");
        List<Customer> payers = new ArrayList<>();
        List<Customer> payees = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            payers.add(fresh(1_000_00));
            payees.add(fresh(1_00));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(11);
        List<Object> answers = new ArrayList<>();
        Answer activated;
        try (AutoCloseable slow = RiskStormFaults.delay("risk.risk_policy_version", "UPDATE", "NEW.id = '"
                + predecessor + "' AND NEW.status = 'RETIRED'", 1.5);
                Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            for (Customer payer : payers) {
                try (PreparedStatement hold = holder.prepareStatement("SELECT 1 FROM ledger.ledger_account WHERE id ="
                        + " ?::uuid FOR UPDATE")) {
                    hold.setString(1, payer.walletLedger.toString());
                    hold.executeQuery().close();
                }
            }
            List<java.util.concurrent.Future<Answer>> sent = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Customer payer = payers.get(i);
                Customer payee = payees.get(i);
                Instance on = i % 2 == 0 ? a() : b();
                sent.add(pool.submit(() -> on.door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(
                        payer.wallet, payee.wallet, 26_00), payer.mfaToken, key(), Audience.SUBJECT)));
            }
            awaitBlockedBehind(RiskStormLocks.pid(holder), 10);
            java.util.concurrent.Future<Answer> activation = pool.submit(() -> b().door().post(
                    RiskStormWorld.POLICIES + "/" + successor + "/activation", "{\"reason\":\"scenario 3, held\"}",
                    activator.token(), key(), Audience.OPERATOR));
            awaitTheActivationAsleep(activation);
            holder.rollback();
            for (java.util.concurrent.Future<Answer> transfer : sent) {
                answers.add(transfer.get(2, java.util.concurrent.TimeUnit.MINUTES));
            }
            activated = activation.get(2, java.util.concurrent.TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        judged(tally(), "scenario 3 (held window): the activation", activated);
        for (Object answer : answers) {
            judged(tally(), "scenario 3 (held window): a transfer in the window", (Answer) answer);
        }
        long pinnedSuccessor = 0;
        long neither = 0;
        for (Customer payer : payers) {
            String pin = scalar("SELECT coalesce(policy_version_id::text, 'NONE') FROM risk.risk_assessment WHERE"
                    + " operation = 'TRANSFER' AND party_id = ?", payer.party);
            if (successor.equals(pin)) {
                pinnedSuccessor++;
            } else if (!predecessor.equals(pin)) {
                neither++;
            }
        }
        expect("scenario 3 (held window): none neither", neither, 0);
        expect("scenario 3 (held window): the ten queued behind the activation pinned the successor", pinnedSuccessor,
                10);
        covered("scenario 3 (held window): pins re-read past a retired row (A3-I1)", pinnedSuccessor);
        // The ten waited out the activation's slow commit inside T-eval's pin: their latency is the window's.
        storm.tolerates("scenario 3", "RiskEvaluationLatencyOverBudget");
    }

    /**
     * True once two backends wait on R2a's namespace together - then the scene lets go at once, well inside the 250 ms
     * budget; false when the first has waited 150 ms alone (its partner late - let go before the budget would cancel it),
     * or the pair finished, or 5 s passed, without ever queueing together.
     */
    private static boolean bothQueuedOnSubjectLocks(java.util.concurrent.Future<?> raced) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        long alone = Long.MAX_VALUE;
        try (Connection observer = DatabaseRoles.bootstrap();
                PreparedStatement waiting = observer.prepareStatement("SELECT count(DISTINCT pid) FROM pg_locks WHERE"
                        + " locktype = 'advisory' AND classid = 131 AND NOT granted")) {
            while (System.nanoTime() < deadline && System.nanoTime() < alone && !raced.isDone()) {
                try (var row = waiting.executeQuery()) {
                    row.next();
                    long queued = row.getLong(1);
                    if (queued >= 2) {
                        return true;
                    }
                    if (queued == 1 && alone == Long.MAX_VALUE) {
                        alone = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(150);
                    }
                }
                Thread.sleep(2);
            }
        }
        return false;
    }

    /** Until {@code count} backends wait behind {@code holder}'s locks - read as the bootstrap role. */
    private static void awaitBlockedBehind(int holder, int count) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.bootstrap();
                PreparedStatement blocked = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE ? ="
                        + " ANY (pg_blocking_pids(pid))")) {
            blocked.setInt(1, holder);
            while (System.nanoTime() < deadline) {
                try (var row = blocked.executeQuery()) {
                    row.next();
                    if (row.getLong(1) >= count) {
                        return;
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new IllegalStateException("fewer than " + count + " transfers parked at their money locks");
    }

    /** Until the activation's backend sleeps in the delay under the predecessor's lock - read as the bootstrap role. */
    private static void awaitTheActivationAsleep(java.util.concurrent.Future<?> activation) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        try (Connection observer = DatabaseRoles.bootstrap();
                PreparedStatement asleep = observer.prepareStatement("SELECT count(*) FROM pg_stat_activity WHERE"
                        + " wait_event = 'PgSleep' AND query LIKE '%risk_policy_version%'")) {
            while (System.nanoTime() < deadline && !activation.isDone()) {
                try (var row = asleep.executeQuery()) {
                    row.next();
                    if (row.getLong(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(10);
            }
        }
        throw new IllegalStateException("the activation never slept under the predecessor's row lock");
    }

    /**
     * The scene's lock sampler: every 10 ms, every ungranted lock - the activation's backend (its statement on
     * {@code risk_policy_version}) waiting on a relation outside {@code risk} is a money wait; any backend waiting on a
     * {@code risk_policy_version} tuple or relation is an R1 wait.
     */
    private static final class ActivationWaits implements AutoCloseable {
        private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(
                true);
        private final java.util.Set<String> money = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<String> policy = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final Thread thread = new Thread(this::sample, "risk-storm-activation-waits");

        ActivationWaits() {
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            try (Connection observer = DatabaseRoles.bootstrap();
                    PreparedStatement select = observer.prepareStatement("SELECT a.pid, a.query_start::text,"
                            + " coalesce(l.relation::regclass::text, l.locktype), a.query FROM pg_locks l JOIN"
                            + " pg_stat_activity a ON a.pid = l.pid WHERE NOT l.granted")) {
                while (running.get()) {
                    try (var row = select.executeQuery()) {
                        while (row.next()) {
                            String wait = row.getInt(1) + "@" + row.getString(2);
                            String relation = row.getString(3);
                            String query = String.valueOf(row.getString(4));
                            boolean activation = query.contains("risk_policy_version") && (query.startsWith("UPDATE")
                                    || query.contains("FOR UPDATE"));
                            if (activation && relation != null && !relation.startsWith("risk.")
                                    && !relation.equals("advisory") && !relation.equals("transactionid")
                                    && !relation.equals("tuple")) {
                                money.add(wait + "@" + relation);
                            }
                            if (query.contains("risk_policy_version")) {
                                policy.add(wait);
                            }
                        }
                    }
                    Thread.sleep(10);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (SQLException ignored) {
                // the scene's sampler: a failed read is a missed sample, never a pass
            }
        }

        long moneyWaits() {
            return money.size();
        }

        long policyWaits() {
            return policy.size();
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }

    // ---------------------------------------------------------------- scenario 4

    /**
     * Scenario 4: an action times out at the owning domain and is retried - a party's identity suspended by an approved
     * action whose first dispatch reaches identity, commits, and loses the answer: the action stays DISPATCHED, its lease
     * lapses, the platform re-dispatches under the same key {@code risk.action:<id>} and identity replays - one effect.
     * Beside it the domain failing: a merchant's suspension refused twice before it reaches merchant, then done - one
     * effect.
     */
    void s04AnActionTimingOutAtTheOwningDomainIsRetriedForOneEffect() throws Exception {
        Customer suspect = fresh(1_00);
        String caseId = openAndTake(storm.analysts[0], "ATO", "PARTY", suspect.party.toString());
        RiskStormFaults.arm(suspect.party.toString(), RiskStormFaults.Fault.LOSE_RESPONSE, 1);
        String action = proposeAndApproveAction(caseId, "SUSPEND_IDENTITY", null);
        awaitAction(action, "DONE");
        expect("scenario 4: identity reached twice (the lost answer and the re-drive)",
                RiskStormFaults.reached("suspend", suspect.party.toString()), 2);
        expect("scenario 4: one answer lost", RiskStormFaults.lost("suspend", suspect.party.toString()), 1);
        expect("scenario 4: the owner's key recorded once", count("SELECT count(*) FROM platform.idempotency_record"
                + " WHERE idempotency_key = ?", "risk.action:" + action), 1);
        expect("scenario 4: one suspension (identity's audit)", count("SELECT count(*) FROM platform.audit_record WHERE"
                + " operation = 'identity.IdentitySuspended' AND outcome = 'SUCCEEDED' AND target_id = ?",
                suspect.identity.toString()), 1);
        expect("scenario 4: the action attempted twice", Long.parseLong(scalar("SELECT attempts FROM risk.risk_action"
                + " WHERE id = ?::uuid", action)), 2);
        // The domain down twice, then up: one effect.
        Merchant merchant = world().merchant(storm.merchantAdministrator);
        String merchantCase = openAndTake(storm.analysts[1], "MERCHANT_ABUSE", "MERCHANT", merchant.id());
        RiskStormFaults.arm(merchant.id(), RiskStormFaults.Fault.FAIL_BEFORE, 2);
        String suspension = proposeAndApproveAction(merchantCase, "SUSPEND_MERCHANT", null);
        awaitAction(suspension, "DONE");
        expect("scenario 4 (failing domain): refused before merchant twice",
                RiskStormFaults.refusedBefore("suspend", merchant.id()), 2);
        expect("scenario 4 (failing domain): merchant reached once", RiskStormFaults.reached("suspend", merchant.id()),
                1);
        storm.breaks("scenario 4", "RiskActionDispatchFailing");
        storm.restAndCensus("scenario 4");
    }

    // ---------------------------------------------------------------- scenario 5

    /**
     * Scenario 5: two investigators update one case at once, each from the revision their screen read, on A and on B -
     * one 200, one {@code 409 risk.CaseRevisionStale}; the loser reads again and its act is kept too; no update lost
     * (the history is one row per revision). R4 counted as the act's own backend behind a held case row.
     */
    void s05TwoInvestigatorsUpdatingOneCaseKeepBothActsAndOne409() throws Exception {
        Customer subject = fresh(1_00);
        String caseId = openAndTake(storm.analysts[0], "FRAUD", "PARTY", subject.party.toString());
        int revision = revision(caseId);
        String door = RiskStormWorld.CASES + "/" + caseId + "/actions";
        List<Object> raced = RiskStormLocks.race(List.of(
                () -> a().door().post(door, "{\"act\":\"NOTE\",\"revision\":" + revision + ",\"text\":\"first view\"}",
                        storm.analysts[0].token(), key(), Audience.OPERATOR),
                () -> b().door().post(door, "{\"act\":\"NOTE\",\"revision\":" + revision + ",\"text\":\"second view\"}",
                        storm.analysts[1].token(), key(), Audience.OPERATOR)));
        int won = 0;
        int stale = 0;
        for (Object answer : raced) {
            Answer one = (Answer) answer;
            judged(tally(), "scenario 5: a concurrent case act", one, "409 risk.CaseRevisionStale",
                    "409 risk.CaseActNotAdmitted", "422 risk.CaseNotTaken");
            if (one.ok()) {
                won++;
            } else if (one.is(409, "risk.CaseRevisionStale")) {
                stale++;
                Operator loser = raced.indexOf(answer) == 0 ? storm.analysts[0] : storm.analysts[1];
                Answer again = either().door().post(door, "{\"act\":\"NOTE\",\"revision\":" + revision(caseId)
                        + ",\"text\":\"read again\"}", loser.token(), key(), Audience.OPERATOR);
                judged(tally(), "scenario 5: the loser's act after reading again", again);
            }
        }
        expect("scenario 5: one act won", won, 1);
        expect("scenario 5: one 409 risk.CaseRevisionStale", stale, 1);
        expect("scenario 5: both acts kept", count("SELECT count(*) FROM risk.risk_case_action WHERE case_id = ?::uuid"
                + " AND kind = 'NOTE'", caseId), 2);
        // R4: the case row held, a note sent - its backend seen waiting on risk_case, its act committed after the release.
        int current = revision(caseId);
        RiskStormLocks.Sighting r4 = RiskStormLocks.observe("R4 a case act behind the case row",
                "SELECT id FROM risk.risk_case WHERE id = ?::uuid FOR UPDATE", caseId, "risk_case",
                () -> b().door().post(door, "{\"act\":\"NOTE\",\"revision\":" + current + ",\"text\":\"behind the lock\"}",
                        storm.analysts[0].token(), key(), Audience.OPERATOR),
                answer -> ((Answer) answer).ok() ? caseId : null,
                "SELECT xmin::text FROM risk.risk_case_event WHERE case_id = ?::uuid ORDER BY revision DESC LIMIT 1");
        covered("R4 waits seen as the act's own backend", r4.holds() ? 1 : 0);
        if (!r4.holds()) {
            tally().unexplained("R4 not seen as the act's own backend: " + r4);
        }
        storm.restAndCensus("scenario 5");
    }

    // ---------------------------------------------------------------- scenario 6

    /**
     * Scenario 6: a delayed default after its collections case is disposed - the disposed case untouched (status,
     * revision), the late event's alert opening a new case that names the disposed one as its predecessor.
     */
    void s06ADelayedEventAfterItsCaseIsDisposedOpensANewCase() throws Exception {
        Customer borrower = fresh(1_00);
        UUID obligation = seededObligation(borrower.party);
        String today = scalar("SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD')");
        String yesterday = scalar("SELECT to_char((now() - interval '1 day') AT TIME ZONE 'UTC', 'YYYY-MM-DD')");
        storm.relay.deliver(new RiskStormRelay.Target("A", a().beans()), RiskStormRelay.synthesized("bnpl.BnplDefaulted",
                obligation, "bnpl_obligation", Instant.now(), "bnpl", condition("obligationId", obligation, today, true)
                        .toBytes()));
        String first = scalar("SELECT id::text FROM risk.risk_case WHERE subject_ref = ? AND kind = 'COLLECTIONS'",
                borrower.party.toString());
        take(storm.analysts[0], first);
        Answer closed = a().door().post(RiskStormWorld.CASES + "/" + first + "/dispositions", "{\"revision\":"
                + revision(first) + ",\"disposition\":\"CLOSED_NO_ACTION\",\"reason\":\"paid in full\"}",
                storm.analysts[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 6: the collections case disposed", closed);
        String[] disposed = RiskStormWorld.row("SELECT status, revision FROM risk.risk_case WHERE id = ?::uuid", first);
        // The delayed event: an earlier default of the same obligation (yesterday's business date), arriving now.
        storm.relay.deliver(new RiskStormRelay.Target("B", b().beans()), RiskStormRelay.synthesized("bnpl.BnplDefaulted",
                obligation, "bnpl_obligation", Instant.now().minusSeconds(86_400), "bnpl",
                condition("obligationId", obligation, yesterday, true).toBytes()));
        String[] after = RiskStormWorld.row("SELECT status, revision FROM risk.risk_case WHERE id = ?::uuid", first);
        expect("scenario 6: the disposed case untouched", disposed[0].equals(after[0]) && disposed[1].equals(after[1])
                ? 1 : 0, 1);
        expect("scenario 6: a new open case naming the disposed one", count("SELECT count(*) FROM risk.risk_case WHERE"
                + " subject_ref = ? AND kind = 'COLLECTIONS' AND status <> 'DISPOSED' AND predecessor_case_id ="
                + " ?::uuid", borrower.party.toString(), first), 1);
        storm.restAndCensus("scenario 6");
    }

    // ---------------------------------------------------------------- scenario 7

    /**
     * Scenario 7: a downstream operation fails after the decision - the scheme refuses a permitted withdrawal (quiesced:
     * no other withdrawal in flight): the assessment stands ALLOW and COUNTED, the withdrawal FAILED with its hold
     * released, and risk retries nothing (one assessment, no action).
     */
    void s07ADownstreamFailureAfterTheDecisionLeavesTheDecisionStanding() throws Exception {
        Customer customer = fresh(1_000_00);
        world().schemeRejects();
        Answer answered;
        try {
            answered = a().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(customer, 40_00),
                    customer.mfaToken, key(), Audience.SUBJECT);
        } finally {
            world().schemeAccepts();
        }
        judged(tally(), "scenario 7: a withdrawal the scheme refuses", answered);
        String id = answered.field("id");
        awaitDatabase("the refused withdrawal concluded", "SELECT status IN ('FAILED') FROM payments.withdrawal WHERE"
                + " id = ?::uuid", id);
        expect("scenario 7: the decision stands ALLOW and COUNTED", count("SELECT count(*) FROM risk.risk_assessment"
                + " WHERE operation = 'WITHDRAWAL' AND command_ref = ? AND decision = 'ALLOW' AND velocity_outcome ="
                + " 'COUNTED'", id), 1);
        expect("scenario 7: risk retried nothing", count("SELECT count(*) FROM risk.risk_action WHERE target_ref ="
                + " ?::uuid", id), 0);
        storm.restAndCensus("scenario 7");
    }

    // ---------------------------------------------------------------- scenario 8

    /**
     * Scenario 8: history replayed from an earlier watermark (quiesced) - the monitored transactions, alerts, cases and
     * actions counted before; the watermark moved back to where the first {@code MONITORING} version took effect (every
     * entry after it judged when first claimed); the intake re-reads to the head - nothing new.
     *
     * <p><strong>Judged history only</strong> (the storm's finding, recorded for the owner): replayed from the storm's
     * start, the leg judged the fundings claimed before any {@code MONITORING} version was active - {@code NOT_JUDGED}
     * then - under the version now in force, and raised their {@code ROUND_AMOUNT} alerts and cases (21 in the second
     * full run). The leg pins the version in force by design; "nothing new" is a claim about history already judged.
     */
    void s08HistoryReplayedFromAnEarlierWatermarkCreatesNothingNew() throws Exception {
        storm.rest("scenario 8 before");
        long[] before = scenarioEightCounts();
        long from = Math.max(0, storm.judgedSince);
        long sequence = Long.parseLong(scalar("SELECT last_sequence FROM risk.monitoring_watermark WHERE source ="
                + " 'ledger.journal'"));
        Answer ordered = b().door().post(RiskStormWorld.REPROCESSING, "{\"fromSequence\":" + Math.min(from, sequence)
                + ",\"reason\":\"scenario 8 re-reads the storm\"}", storm.officers[2].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 8: the reprocessing ordered", ordered);
        awaitDatabase("the intake re-read to the head", "SELECT w.last_sequence >= ? FROM risk.monitoring_watermark w"
                + " WHERE w.source = 'ledger.journal'", sequence);
        storm.rest("scenario 8 after");
        long[] after = scenarioEightCounts();
        if (after[1] > before[1]) {
            for (String[] born : RiskStormWorld.rows("SELECT a.kind, a.rule_code, a.subject_kind, a.window_kind,"
                    + " a.window_start::text, coalesce(v.operation || ' v' || v.version, 'none'), a.raised_at::text,"
                    + " a.status, (SELECT string_agg(o.window_kind || '@' || o.window_start::text || '/' || o.status || '/'"
                    + " || coalesce(ov.version::text, '-'), ', ') FROM risk.risk_alert o LEFT JOIN"
                    + " risk.risk_policy_version ov ON ov.id = o.policy_version_id WHERE o.subject_ref = a.subject_ref"
                    + " AND o.rule_code = a.rule_code AND o.id <> a.id), cardinality(a.evidence_journal_entry_ids)"
                    + " FROM risk.risk_alert a LEFT JOIN risk.risk_policy_version v ON v.id = a.policy_version_id"
                    + " ORDER BY a.raised_at DESC LIMIT ?", after[1] - before[1])) {
                System.out.println("P13-TST-001 S08 NEW ALERT: " + String.join(" | ", born));
            }
        }
        String[] names = {"monitored transactions", "alerts", "cases", "actions"};
        for (int i = 0; i < names.length; i++) {
            expect("scenario 8: no new " + names[i], after[i] - before[i], 0);
        }
        covered("scenario 8: journal entries re-read", count("SELECT count(*) FROM risk.monitored_transaction"));
        storm.restAndCensus("scenario 8");
    }

    private static long[] scenarioEightCounts() throws SQLException {
        return new long[] {count("SELECT count(*) FROM risk.monitored_transaction"),
            count("SELECT count(*) FROM risk.risk_alert"), count("SELECT count(*) FROM risk.risk_case"),
            count("SELECT count(*) FROM risk.risk_action")};
    }

    // ---------------------------------------------------------------- scenario 9

    /**
     * Scenario 9, choreographed (beside the load's window): one party's subject lock held past the budget - a transfer
     * of EUR 999.99 COMPLETED under ALLOW(RISK_UNAVAILABLE), EUR 1,000 FAILED(RISK_REFUSED) under DENY, a withdrawal
     * FAILED at every band with its hold released, a card payment of EUR 100 completed and one of EUR 300 refused (C1's
     * EUR 250 band) - each sealed TIMEOUT, no transaction aborted; and the retirement of a version without its successor
     * refused by the database (V002's deferred trigger) - the "no policy" path is reachable only before an operation's
     * first activation (the storm's opening window).
     */
    void s09RiskUnavailableCommitsTheBandsOutcome() throws Exception {
        Customer payer = fresh(20_000_00);
        storm.forced.add(payer.party.toString());
        Customer payee = fresh(1_00);
        Merchant merchant = storm.merchants.get(1);
        List<String> answered = new ArrayList<>();
        try (AutoCloseable lock = RiskStormTest.holdPartyLocks(List.of(payer.party))) {
            answered.add(a().door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet, payee.wallet,
                    999_99), payer.mfaToken, key(), Audience.SUBJECT).field("id"));
            answered.add(b().door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet, payee.wallet,
                    1_000_00), payer.mfaToken, key(), Audience.SUBJECT).field("id"));
            Answer small = a().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(payer, 1),
                    payer.mfaToken, key(), Audience.SUBJECT);
            judged(tally(), "scenario 9: a withdrawal while unavailable", small);
            String card = storm.checkoutSession(b(), merchant, 100_00);
            judged(tally(), "scenario 9: EUR 100 by card while unavailable", b().door().post(RiskStormWorld.CONFIRMATION,
                    RiskStormTest.confirmation(card, payer.card), payer.mfaToken, null, Audience.SUBJECT));
            String large = storm.checkoutSession(a(), merchant, 300_00);
            judged(tally(), "scenario 9: EUR 300 by card while unavailable", a().door().post(RiskStormWorld.CONFIRMATION,
                    RiskStormTest.confirmation(large, payer.card), payer.mfaToken, null, Audience.SUBJECT));
        }
        expect("scenario 9: EUR 999.99 COMPLETED under ALLOW(RISK_UNAVAILABLE, TIMEOUT)", count("SELECT count(*) FROM"
                + " risk.risk_assessment a JOIN transfers.transfer t ON t.id::text = a.command_ref WHERE a.command_ref = ?"
                + " AND a.decision = 'ALLOW' AND a.fail_safe_reason = 'RISK_UNAVAILABLE' AND a.unavailable_cause ="
                + " 'TIMEOUT' AND t.status = 'COMPLETED'", answered.get(0)), 1);
        expect("scenario 9: EUR 1,000 FAILED(RISK_REFUSED) under DENY(RISK_UNAVAILABLE, TIMEOUT)", count("SELECT"
                + " count(*) FROM risk.risk_assessment a JOIN transfers.transfer t ON t.id::text = a.command_ref WHERE"
                + " a.command_ref = ? AND a.decision = 'DENY' AND a.unavailable_cause = 'TIMEOUT' AND t.failure_reason ="
                + " 'RISK_REFUSED'", answered.get(1)), 1);
        expect("scenario 9: the withdrawal DENY, its hold released", count("SELECT count(*) FROM risk.risk_assessment a"
                + " JOIN payments.withdrawal w ON w.id::text = a.command_ref JOIN ledger.hold h ON h.id ="
                + " w.hold_reference WHERE a.party_id = ? AND a.operation = 'WITHDRAWAL' AND a.decision = 'DENY' AND"
                + " a.unavailable_cause = 'TIMEOUT' AND h.status = 'RELEASED'", payer.party), 1);
        expect("scenario 9: card EUR 100 ALLOW and EUR 300 DENY (the C1 band)", count("SELECT count(*) FROM"
                + " risk.risk_assessment WHERE party_id = ? AND operation = 'CARD_PAYMENT' AND unavailable_cause ="
                + " 'TIMEOUT' AND ((amount_minor = 10000 AND decision = 'ALLOW') OR (amount_minor = 30000 AND decision ="
                + " 'DENY'))", payer.party), 2);
        // A retirement without its successor is refused at commit (risk V002) - the "neither" window cannot open.
        boolean refusedAtCommit = false;
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement retire = owner.createStatement()) {
                retire.executeUpdate("UPDATE risk.risk_policy_version SET status = 'RETIRED', effective_to = now() WHERE"
                        + " operation = 'TRANSFER' AND status = 'ACTIVE'");
                owner.commit();
            } catch (SQLException refused) {
                refusedAtCommit = true;
                owner.rollback();
            }
        }
        expect("scenario 9: a retirement without its successor refused by the database", refusedAtCommit ? 1 : 0, 1);
        // The fail-safes are the reading's deliberate break. Every forced evaluation waited out its 250 ms budget, but its
        // latency is measured on its instance's tracked clock, which holds still through the database's clock steps - a
        // wait inside a step reads zero, so the over-budget rule may or may not fire: tolerated, never required.
        storm.breaks("scenario 9", "RiskEvaluationUnavailable");
        storm.tolerates("scenario 9", "RiskEvaluationLatencyOverBudget");
        storm.restAndCensus("scenario 9");
    }

    // ---------------------------------------------------------------- scenario 10

    /**
     * Scenario 10: a held withdrawal released by an approved override - dispatched once (one send); one denied - its
     * hold released once, nothing sent; and both races with the expiry (held_until moved to the next seconds by the
     * owner, the dates shifted - A6-04): exactly one exit from HELD, the loser recorded.
     */
    void s10AHeldWithdrawalReleasedOnceDeniedOnceAndRacingItsExpiry() throws Exception {
        Customer customer = fresh(50_000_00);
        String released = heldWithdrawal(customer, 2_600_00);
        int sendsBefore = world().schemeSends();
        String releaseOverride = override(assessmentOf(released), true, "scenario 10's release");
        awaitDatabase("the released withdrawal left HELD", "SELECT status <> 'HELD' FROM payments.withdrawal WHERE id ="
                + " ?::uuid", released);
        awaitAction(actionOf(releaseOverride), "DONE");
        expect("scenario 10: released - one exit from HELD", exits(released), 1);
        expect("scenario 10: released - dispatched once", world().schemeSends() - sendsBefore, 1);

        String denied = heldWithdrawal(customer, 2_700_00);
        int sendsBeforeDeny = world().schemeSends();
        String denyOverride = override(assessmentOf(denied), false, "scenario 10's denial");
        awaitAction(actionOf(denyOverride), "DONE");
        expect("scenario 10: denied - FAILED(RISK_REFUSED), the hold released once", count("SELECT count(*) FROM"
                + " payments.withdrawal w JOIN ledger.hold h ON h.id = w.hold_reference WHERE w.id = ?::uuid AND"
                + " w.status = 'FAILED' AND w.failure_reason = 'RISK_REFUSED' AND h.status = 'RELEASED'", denied), 1);
        expect("scenario 10: denied - nothing sent", world().schemeSends() - sendsBeforeDeny, 0);

        // Released twice under two keys: the HOLD's own FRAUD case proposes a RELEASE_HELD_WITHDRAWAL action (keyed by
        // its action's id) while an officer's override releases it (keyed by the override's) - both approved at one
        // barrier on the two instances: one exit from HELD, one send, the other dispatch payments' recorded ALREADY.
        String twice = heldWithdrawal(customer, 2_900_00);
        String twiceAssessment = assessmentOf(twice);
        String holdCase = scalar("SELECT id::text FROM risk.risk_case WHERE opening_assessment_id = ?::uuid",
                twiceAssessment);
        if (holdCase == null) {
            holdCase = openAndTake(storm.analysts[0], "FRAUD", "PARTY", customer.party.toString());
        } else {
            take(storm.analysts[0], holdCase);
        }
        Answer caseProposal = a().door().post(RiskStormWorld.CASES + "/" + holdCase + "/risk-actions", "{\"revision\":"
                + revision(holdCase) + ",\"kind\":\"RELEASE_HELD_WITHDRAWAL\",\"targetRef\":\"" + twice
                + "\",\"reason\":\"the case releases it\"}", storm.analysts[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 10: a case's release proposed", caseProposal);
        Answer overrideProposal = b().door().post(RiskStormWorld.ASSESSMENTS + "/" + twiceAssessment + "/overrides",
                "{\"reasonCode\":\"CUSTOMER_VERIFIED\",\"reason\":\"the override releases it\",\"resolution\":"
                + "\"PROCEED\"}", storm.officers[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 10: an override's release proposed", overrideProposal);
        String caseAction = caseProposal.field("actionId");
        String overrideOfTwice = overrideProposal.field("overrideId");
        int sendsBeforeTwice = world().schemeSends();
        String finalHoldCase = holdCase;
        List<Object> both = RiskStormLocks.race(List.of(
                () -> a().door().post(RiskStormWorld.CASES + "/" + finalHoldCase + "/risk-actions/" + caseAction
                        + "/approval", "{\"revision\":" + revision(finalHoldCase) + ",\"reason\":\"confirmed\"}",
                        storm.analysts[1].token(), key(), Audience.OPERATOR),
                () -> b().door().post(RiskStormWorld.ASSESSMENTS + "/" + twiceAssessment + "/overrides/"
                        + overrideOfTwice + "/approval", "{\"reason\":\"a second pair of eyes\"}",
                        storm.officers[1].token(), key(), Audience.OPERATOR)));
        for (Object answer : both) {
            judged(tally(), "scenario 10: one of two releases approved", (Answer) answer);
        }
        awaitAction(caseAction, "DONE");
        awaitAction(actionOf(overrideOfTwice), "DONE");
        expect("scenario 10: released twice under two keys - one exit from HELD", exits(twice), 1);
        expect("scenario 10: released twice under two keys - one send", world().schemeSends() - sendsBeforeTwice, 1);
        expect("scenario 10: released twice under two keys - one of the two answered ALREADY", count("SELECT count(*)"
                + " FROM risk.risk_action WHERE id IN (?::uuid, ?::uuid) AND outcome = 'ALREADY'", caseAction,
                actionOf(overrideOfTwice)), 1);

        // An expiry winning after the override's approval is payments' refusal of the action (past held_until on the
        // database clock: refused, the expiry leg fails it) - recorded FAILED, the rule's ticket, not a defect.
        storm.tolerates("scenario 10", "RiskActionRefused");
        // Three races with the expiry: a release and a denial into a window ending a moment ahead (either may win), and a
        // release into a window already elapsed on the database's clock - held on B, whose clock runs 5 s behind the
        // database's, the window ended at its start: payments refuses a late release, so the expiry is the one exit.
        for (int race = 0; race < 3; race++) {
            boolean release = race != 1;
            boolean elapsed = race == 2;
            String label = elapsed ? "a release racing an elapsed window" : release ? "release" : "denial";
            String racing = elapsed ? heldWithdrawal(customer, 2_800_00, b()) : heldWithdrawal(customer, 2_800_00);
            // The expiry armed first, while still HELD (a denial or release already through owns the row: its window is
            // no longer the owner's to move) - then the override runs into it. held_at is the holding instance's clock
            // (A runs 5 s ahead of the database): the window still ends after it.
            RiskStormWorld.asBootstrap(elapsed ? "UPDATE payments.withdrawal SET held_until = held_at + interval"
                    + " '1 millisecond' WHERE id = ?::uuid AND status = 'HELD'" : "UPDATE payments.withdrawal SET"
                    + " held_until = greatest(clock_timestamp() + interval '1500 milliseconds', held_at + interval"
                    + " '1 millisecond') WHERE id = ?::uuid AND status = 'HELD'", UUID.fromString(racing));
            String overrideId = override(assessmentOf(racing), release, "scenario 10's race with the expiry");
            awaitDatabase("the racing withdrawal left HELD", "SELECT status <> 'HELD' FROM payments.withdrawal WHERE"
                    + " id = ?::uuid", racing);
            // An expiry ahead of the proposal or its approval answers 409 risk.OverrideStale: no action is born.
            if (overrideId != null && scalar("SELECT id::text FROM risk.risk_action WHERE override_id = ?::uuid",
                    overrideId) != null) {
                awaitDatabase("the racing action concluded", "SELECT status IN ('DONE', 'FAILED') FROM"
                        + " risk.risk_action WHERE override_id = ?::uuid", overrideId);
            }
            expect("scenario 10: " + label + " racing the expiry - one exit from HELD", exits(racing), 1);
            expect("scenario 10: " + label + " racing the expiry - the hold released or the money dispatched, once",
                    count("SELECT count(*) FROM payments.withdrawal w JOIN ledger.hold h ON h.id = w.hold_reference"
                    + " WHERE w.id = ?::uuid AND ((w.status = 'FAILED' AND h.status = 'RELEASED') OR w.status IN"
                    + " ('DISPATCHED', 'COMPLETED', 'UNKNOWN'))", racing), 1);
            String winner = scalar("SELECT CASE WHEN w.failure_detail = 'HOLD_EXPIRED' THEN 'expiry' ELSE 'override'"
                    + " END FROM payments.withdrawal w WHERE w.id = ?::uuid", racing);
            covered("scenario 10: races with the expiry won by the " + winner, 1);
            if (elapsed) {
                expect("scenario 10: a release racing an elapsed window - the expiry is the one exit",
                        "expiry".equals(winner) ? 1 : 0, 1);
            }
        }
        storm.restAndCensus("scenario 10");
    }

    /** A withdrawal of {@code minor} (EUR 2,500 or more: the HOLD) - its id, asserted HELD. */
    private String heldWithdrawal(Customer customer, long minor) throws Exception {
        return heldWithdrawal(customer, minor, either());
    }

    /** {@link #heldWithdrawal(Customer, long)} held on {@code on} - its window stamped by that instance's clock. */
    private String heldWithdrawal(Customer customer, long minor, Instance on) throws Exception {
        Answer answered = on.door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(customer, minor),
                customer.mfaToken, key(), Audience.SUBJECT);
        judged(tally(), "a withdrawal held", answered);
        String id = answered.field("id");
        expect("a withdrawal of EUR 2,500+ is HELD", "HELD".equals(answered.field("status")) ? 1 : 0, 1);
        return id;
    }

    private static String assessmentOf(String withdrawal) throws SQLException {
        return scalar("SELECT id::text FROM risk.risk_assessment WHERE operation = 'WITHDRAWAL' AND command_ref = ?",
                withdrawal);
    }

    private static String actionOf(String override) throws SQLException {
        return scalar("SELECT id::text FROM risk.risk_action WHERE override_id = ?::uuid", override);
    }

    private static long exits(String withdrawal) throws SQLException {
        return count("SELECT count(*) FROM payments.withdrawal_event WHERE withdrawal_id = ?::uuid AND from_status ="
                + " 'HELD'", withdrawal);
    }

    // ---------------------------------------------------------------- scenario 11

    /**
     * Scenario 11: an account-takeover sequence - the password changed through identity's door (its
     * {@code identity.CredentialChanged} relayed twice at once on A and B, again later, and an older synthesized change
     * reordered behind it), a new payee (a bank account registered), a withdrawal: HELD with its hold kept, one
     * {@code ACCOUNT_TAKEOVER} alert attached to one {@code ATO} case; the retry replays; a second withdrawal holds again
     * - still one alert, one case.
     */
    void s11AnAccountTakeoverSequenceIsHeldAndCased() throws Exception {
        Customer victim = fresh(10_000_00);
        String changeEvent = credentialChange(victim);
        var changed = storm.relay.reread(changeEvent);
        RiskStormLocks.race(List.of(
                () -> storm.relay.deliver(new RiskStormRelay.Target("A", a().beans()), changed),
                () -> storm.relay.deliver(new RiskStormRelay.Target("B", b().beans()), changed)));
        storm.relay.deliver(new RiskStormRelay.Target("A", a().beans()), RiskStormRelay.synthesized(
                "identity.CredentialChanged", victim.identity, "Identity", Instant.now().minusSeconds(5 * 3_600),
                "identity", EventPayload.of().with("identityId", victim.identity.toString()).toBytes()));
        storm.relay.deliver(new RiskStormRelay.Target("B", b().beans()), changed);
        victim.bank = world().bankAccount(victim);
        String withdrawalKey = key();
        Answer held = a().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(victim, 10_00),
                victim.mfaToken, withdrawalKey, Audience.SUBJECT);
        judged(tally(), "scenario 11: the withdrawal of the sequence", held);
        expect("scenario 11: HELD", "HELD".equals(held.field("status")) ? 1 : 0, 1);
        Answer retry = b().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(victim, 10_00),
                victim.mfaToken, withdrawalKey, Audience.SUBJECT);
        judged(tally(), "scenario 11: the retry", retry);
        expect("scenario 11: the retry replays", held.field("id").equals(retry.field("id")) ? 1 : 0, 1);
        Answer again = b().door().post(RiskStormWorld.WITHDRAWALS, RiskStormTest.withdrawalBody(victim, 20_00),
                victim.mfaToken, key(), Audience.SUBJECT);
        judged(tally(), "scenario 11: a second withdrawal", again);
        expect("scenario 11: one credential-change signal per event", count("SELECT count(*) FROM risk.risk_signal WHERE"
                + " subject_ref = ? AND kind = 'CREDENTIAL_CHANGED'", victim.identity.toString()), 2);
        expect("scenario 11: one ACCOUNT_TAKEOVER alert", count("SELECT count(*) FROM risk.risk_alert WHERE subject_ref ="
                + " ? AND kind = 'ACCOUNT_TAKEOVER'", victim.party.toString()), 1);
        storm.rest("scenario 11 folded");
        expect("scenario 11: one ATO case, the alert attached", count("SELECT count(*) FROM risk.risk_case c JOIN"
                + " risk.risk_alert a ON a.attached_case_id = c.id WHERE c.subject_ref = ? AND c.kind = 'ATO' AND a.kind ="
                + " 'ACCOUNT_TAKEOVER'", victim.party.toString()), 1);
        expect("scenario 11: one ATO case", count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ? AND kind ="
                + " 'ATO'", victim.party.toString()), 1);
        storm.restAndCensus("scenario 11");
    }

    /** The customer's password changed through identity's door from its MULTI_FACTOR session - the event's id. */
    private String credentialChange(Customer customer) throws Exception {
        Answer changed = either().door().post("/v1/me/credential", "{\"currentPassword\":\"" + customer.password
                + "\",\"newPassword\":\"" + RiskStormWorld.NEW_PASSWORD + "\"}", customer.mfaToken, null,
                Audience.SUBJECT);
        judged(tally(), "a password change", changed);
        customer.password = RiskStormWorld.NEW_PASSWORD;
        customer.mfaToken = changed.field("sessionToken");
        return scalar("SELECT event_id::text FROM platform.outbox_event WHERE event_type = 'identity.CredentialChanged'"
                + " AND aggregate_id = ?::uuid ORDER BY event_id DESC LIMIT 1", customer.identity.toString());
    }

    // ---------------------------------------------------------------- scenario 12

    /**
     * Scenario 12: a structuring sequence - five transfers of EUR 900 out of one party within its day (sum EUR 4,500,
     * each below EUR 1,000, at least three) - alerted once in its window by the monitoring sweep whichever instance
     * claims, more of the same raising nothing new; the party's AML case folds every AML alert of the party.
     */
    void s12AStructuringSequenceIsAlertedOncePerWindowAndCased() throws Exception {
        Customer structurer = fresh(50_000_00);
        Customer payee = fresh(1_00);
        for (int i = 0; i < 5; i++) {
            judged(tally(), "scenario 12: a transfer below the threshold", (i % 2 == 0 ? a() : b()).door().post(
                    RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(structurer.wallet, payee.wallet, 900_00 + i),
                    structurer.mfaToken, key(), Audience.SUBJECT));
        }
        storm.rest("scenario 12 monitored");
        expect("scenario 12: one STRUCTURING alert in the window", count("SELECT count(*) FROM risk.risk_alert WHERE"
                + " subject_ref = ? AND rule_code = 'STRUCTURING'", structurer.party.toString()), 1);
        for (int i = 0; i < 3; i++) {
            judged(tally(), "scenario 12: more of the same", either().door().post(RiskStormWorld.TRANSFERS,
                    RiskStormTest.transferBody(structurer.wallet, payee.wallet, 910_00 + i), structurer.mfaToken, key(),
                    Audience.SUBJECT));
        }
        storm.rest("scenario 12 monitored again");
        expect("scenario 12: still one STRUCTURING alert in the window", count("SELECT count(*) FROM risk.risk_alert"
                + " WHERE subject_ref = ? AND rule_code = 'STRUCTURING'", structurer.party.toString()), 1);
        expect("scenario 12: the AML case folds every AML alert of the party", count("SELECT count(*) FROM"
                + " risk.risk_alert WHERE subject_ref = ? AND kind = 'AML' AND (attached_case_id IS NULL OR"
                + " attached_case_id NOT IN (SELECT id FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML'))",
                structurer.party.toString(), structurer.party.toString()), 0);
        covered("scenario 12: AML alerts folded into the party's case", count("SELECT count(*) FROM risk.risk_alert WHERE"
                + " subject_ref = ? AND kind = 'AML'", structurer.party.toString()));
        storm.structurer = structurer;
        storm.restAndCensus("scenario 12");
    }

    // ---------------------------------------------------------------- scenario 13

    /**
     * Scenario 13: a credit line decided through instance A's own {@code CreditRiskSignal} (risk's seam at version 2)
     * for a storm party whose profile the sweep rated - sealed; then the profile moves (a confirmed fraud disposition
     * prohibiting the party, four eyes) - the decision replays {@code IDENTICAL} from the same bytes.
     */
    void s13AProfileChangeAfterACreditDecisionReplaysIdentical() throws Exception {
        RiskStormCredit.inForce();
        Customer applicant = fresh(1_000_00);
        @SuppressWarnings("unchecked")
        com.finapp.credit.CreditRiskSignal<Connection> seam = a().beans().getBean(
                com.finapp.credit.CreditRiskSignal.class);
        UUID request = RiskStormCredit.decided(applicant.party, seam);
        String sealed = RiskStormCredit.sealed(request);
        long versionBefore = count("SELECT coalesce(max(version), 0) FROM risk.customer_risk_profile_version WHERE"
                + " party_id = ?", applicant.party);
        String caseId = openAndTake(storm.analysts[0], "FRAUD", "PARTY", applicant.party.toString());
        Answer proposed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                + revision(caseId) + ",\"disposition\":\"CONFIRMED_FRAUD\",\"prohibit\":true,\"reason\":\"a mule\"}",
                storm.analysts[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 13: a prohibiting disposition proposed", proposed);
        judged(tally(), "scenario 13: a prohibiting disposition approved", b().door().post(RiskStormWorld.CASES + "/"
                + caseId + "/dispositions/" + proposed.field("dispositionId") + "/approval", "{\"revision\":"
                + revision(caseId) + ",\"reason\":\"agreed\"}", storm.analysts[2].token(), key(), Audience.OPERATOR));
        expect("scenario 13: the profile moved after the decision", count("SELECT count(*) FROM"
                + " risk.customer_risk_profile_version WHERE party_id = ? AND version > ? AND rating = 'PROHIBITED'",
                applicant.party, versionBefore), 1);
        expect("scenario 13: the decision replays IDENTICAL", "IDENTICAL".equals(RiskStormCredit.replay(request)) ? 1 : 0,
                1);
        expect("scenario 13: the same bytes", sealed.equals(RiskStormCredit.sealed(request)) ? 1 : 0, 1);
        expect("scenario 13: sealed with risk's seam at version 2", sealed.contains("\"port\":\"risk-signal\","
                + "\"version\":\"2\"") ? 1 : 0, 1);
        storm.restAndCensus("scenario 13");
    }

    // ---------------------------------------------------------------- scenario 14

    /**
     * Scenario 14: ten concurrent movements of one subject - ten transfers of one party from ten of its wallets, five on
     * each instance, at one barrier: the party's D1 window counts exactly ten (R2a), each sealed reading of it distinct;
     * then a refused movement (EUR 10,000, the limit's DENY) counts nothing.
     */
    void s14TenConcurrentMovementsOfOneSubjectCountTenARefusalZero() throws Exception {
        Customer payer = fresh(1_000_00);
        Customer payee = fresh(1_00);
        List<UUID> wallets = new ArrayList<>();
        wallets.add(payer.wallet);
        for (int i = 1; i < 10; i++) {
            // EUR 100 each from the treasury: below the amount and velocity weights, so each funding transfer is an ALLOW.
            wallets.add(world().anotherWallet(payer, 100_00));
        }
        String window = "SELECT coalesce(sum(count), 0) FROM risk.velocity_window WHERE dimension = 'PARTY' AND"
                + " subject_ref = ? AND window_kind = 'D1'";
        long before = count(window, payer.party.toString());
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID source = wallets.get(i);
            Instance on = i % 2 == 0 ? a() : b();
            calls.add(() -> on.door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(source, payee.wallet,
                    10_00), payer.mfaToken, key(), Audience.SUBJECT));
        }
        for (Object answer : RiskStormLocks.race(calls)) {
            judged(tally(), "scenario 14: one of ten", (Answer) answer);
        }
        long counted = count("SELECT count(*) FROM risk.risk_assessment WHERE party_id = ? AND velocity_outcome ="
                + " 'COUNTED'", payer.party);
        expect("scenario 14: ten counted", counted, 10);
        expect("scenario 14: the window counts ten", count(window, payer.party.toString()) - before, 10);
        expect("scenario 14: ten distinct readings of the party's D1 window", count("SELECT count(DISTINCT (v ->>"
                + " 'count')) FROM risk.risk_assessment a JOIN risk.risk_assessment_input i ON i.assessment_id = a.id"
                + " CROSS JOIN LATERAL jsonb_array_elements(i.inputs_canonical::jsonb -> 'velocity') v WHERE a.party_id ="
                + " ? AND v ->> 'dimension' = 'PARTY' AND v ->> 'window' = 'D1'", payer.party), 10);
        Answer refusedOne = a().door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet,
                payee.wallet, 10_000_00), payer.mfaToken, key(), Audience.SUBJECT);
        judged(tally(), "scenario 14: the refused movement", refusedOne);
        expect("scenario 14: the refusal counted nothing", count(window, payer.party.toString()) - before, 10);
        storm.restAndCensus("scenario 14");
    }

    // ---------------------------------------------------------------- scenario 15

    /**
     * Scenario 15: an override, a disposition and a policy activation are each refused to their proposer and recorded
     * FAILED - counted from the audit (the load's and the scenes' refusals), each kind at least once.
     */
    void s15EveryFourEyesActIsRefusedToItsProposerAndRecordedFailed() throws Exception {
        // A dedicated disposition refusal, so the count never rests on the load's dice.
        Customer subject = fresh(1_00);
        String caseId = openAndTake(storm.analysts[1], "FRAUD", "PARTY", subject.party.toString());
        Answer proposed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                + revision(caseId) + ",\"disposition\":\"CONFIRMED_FRAUD\",\"reason\":\"confirmed\"}",
                storm.analysts[1].token(), key(), Audience.OPERATOR);
        judged(tally(), "scenario 15: a disposition proposed", proposed);
        refused(tally(), "scenario 15: a disposition approved by its proposer", b().door().post(RiskStormWorld.CASES + "/"
                + caseId + "/dispositions/" + proposed.field("dispositionId") + "/approval", "{\"revision\":"
                + revision(caseId) + ",\"reason\":\"mine\"}", storm.analysts[1].token(), key(), Audience.OPERATOR),
                "403 risk.SelfApprovalRefused");
        for (String[] kind : new String[][] {{"risk_policy_version", "a policy activation"},
            {"decision_override", "an override"}, {"risk_case", "a disposition"}}) {
            long failed = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                    + " AND outcome = 'FAILED' AND target_type = ?", kind[0]);
            covered("scenario 15: " + kind[1] + " refused to its proposer, recorded FAILED", failed);
            expect("scenario 15: " + kind[1] + " refused to its proposer and recorded FAILED", failed > 0 ? 1 : 0, 1);
        }
        storm.restAndCensus("scenario 15");
    }

    // ---------------------------------------------------------------- the filing with a lost response

    /**
     * A filing with a lost response: the structuring party's AML case confirmed suspicious by two analysts, its report
     * drafted by one AML officer, approved by a second from a MULTI_FACTOR session (the drafter refused), filed by a
     * third - the authority receives it and the answer is lost; the report stays FILED, its permit lapses, the inquiry
     * leg asks by the reference and records the acknowledgement: filed once, never twice.
     */
    void aFilingWithALostResponseIsAcknowledgedAndNeverFiledTwice() throws Exception {
        Customer subject = storm.structurer != null ? storm.structurer : fresh(1_00);
        String caseId = scalar("SELECT id::text FROM risk.risk_case WHERE subject_ref = ? AND kind = 'AML' AND status <>"
                + " 'DISPOSED'", subject.party.toString());
        if (caseId == null) {
            caseId = openAndTake(storm.analysts[0], "AML", "PARTY", subject.party.toString());
        } else if (scalar("SELECT taken_by FROM risk.risk_case WHERE id = ?::uuid", caseId) == null) {
            take(storm.analysts[0], caseId);
        }
        String taker = scalar("SELECT taken_by FROM risk.risk_case WHERE id = ?::uuid", caseId);
        Operator investigator = storm.analysts[0].identity().toString().equals(taker) ? storm.analysts[0]
                : storm.analysts[1];
        Answer proposed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                + revision(caseId) + ",\"disposition\":\"CONFIRMED_SUSPICIOUS\",\"reason\":\"structured outflows\"}",
                investigator.token(), key(), Audience.OPERATOR);
        judged(tally(), "the filing: suspicion proposed", proposed);
        judged(tally(), "the filing: suspicion approved", b().door().post(RiskStormWorld.CASES + "/" + caseId
                + "/dispositions/" + proposed.field("dispositionId") + "/approval", "{\"revision\":" + revision(caseId)
                + ",\"reason\":\"agreed\"}", storm.analysts[3].token(), key(), Audience.OPERATOR));
        String reports = RiskStormWorld.CASES + "/" + caseId + "/reports";
        Answer drafted = a().door().post(reports, "{\"jurisdiction\":\"EU-REF\",\"narrative\":\"Funds were split below"
                + " the threshold and moved out within a day.\",\"reason\":\"confirmed suspicious\"}",
                storm.amlOfficers[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "the filing: drafted", drafted);
        String report = drafted.field("reportId");
        refused(tally(), "the filing: approved by its drafter", b().door().post(reports + "/" + report + "/approval",
                "{\"reason\":\"mine\"}", storm.amlOfficers[0].token(), key(), Audience.OPERATOR),
                "403 risk.SelfApprovalRefused");
        judged(tally(), "the filing: approved", b().door().post(reports + "/" + report + "/approval",
                "{\"reason\":\"the narrative is accurate\"}", storm.amlOfficers[1].token(), key(), Audience.OPERATOR));
        RiskStormFaults.arm(report, RiskStormFaults.Fault.LOSE_RESPONSE, 1);
        Answer filed = a().door().post(reports + "/" + report + "/filing", "{\"reason\":\"file it\"}",
                storm.amlOfficers[2].token(), key(), Audience.OPERATOR);
        judged(tally(), "the filing: filed, the answer lost", filed);
        awaitDatabase("the report acknowledged by inquiry", "SELECT status = 'ACKNOWLEDGED' FROM risk.regulatory_report"
                + " WHERE id = ?::uuid", report);
        expect("the filing: filed at the authority once", RiskStormFaults.reached("file", report), 1);
        expect("the filing: its answer lost once", RiskStormFaults.lost("file", report), 1);
        expect("the filing: inquired at least once", RiskStormFaults.reached("inquire", report) > 0 ? 1 : 0, 1);
        storm.breaks("the filing", "RiskFilingCallsFailing");
        storm.restAndCensus("the filing");
    }

    // ---------------------------------------------------------------- the named lock waits

    /**
     * The named lock waits, each as the act's own backend: R1 - the TRANSFER version row held FOR UPDATE (an
     * activation's lock) while a transfer is judged: its pin waits FOR SHARE, then pins; R7 - the watermark held FOR
     * UPDATE while both instances' sweeps run: none waits (SKIP LOCKED), nothing is ingested while it is held, and once
     * released every entry is ingested exactly once. R4 is scenario 5's.
     */
    void theNamedLockWaitsAreCounted() throws Exception {
        Customer payer = fresh(1_000_00);
        Customer payee = fresh(1_00);
        String version = scalar("SELECT id::text FROM risk.risk_policy_version WHERE operation = 'TRANSFER' AND status ="
                + " 'ACTIVE'");
        RiskStormLocks.Sighting r1 = RiskStormLocks.observe("R1 an evaluation's FOR SHARE behind an activation's row",
                "SELECT id FROM risk.risk_policy_version WHERE id = ?::uuid FOR UPDATE", version, "risk_policy_version",
                () -> a().door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(payer.wallet, payee.wallet,
                        12_00), payer.mfaToken, key(), Audience.SUBJECT),
                answer -> ((Answer) answer).ok() ? ((Answer) answer).field("id") : null,
                "SELECT xmin::text FROM risk.risk_assessment WHERE operation = 'TRANSFER' AND command_ref = ?",
                RiskStormTest.A_APPLICATION_NAME);
        covered("R1 waits seen as the act's own backend", r1.holds() ? 1 : 0);
        if (!r1.holds()) {
            tally().unexplained("R1 not seen as the act's own backend: " + r1);
        }
        theSubjectLocksTakeOneOrder();
        // R7: the claim held.
        storm.rest("R7 before");
        long monitoredBefore = count("SELECT count(*) FROM risk.monitored_transaction");
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (Statement hold = holder.createStatement()) {
                hold.execute("SELECT * FROM risk.monitoring_watermark WHERE source = 'ledger.journal' FOR UPDATE");
            }
            for (int i = 0; i < 4; i++) {
                judged(tally(), "R7: a movement while the claim is held", either().door().post(RiskStormWorld.TRANSFERS,
                        RiskStormTest.transferBody(payer.wallet, payee.wallet, 13_00 + i), payer.mfaToken, key(),
                        Audience.SUBJECT));
            }
            RiskStormTest.sleep(RiskStormTest.HORIZON.plusSeconds(5));
            long waiting = count("SELECT count(*) FROM pg_locks l WHERE NOT l.granted AND l.relation ="
                    + " 'risk.monitoring_watermark'::regclass");
            expect("R7: no sweeper waits on the held watermark (SKIP LOCKED)", waiting, 0);
            expect("R7: nothing ingested while the claim is held", count("SELECT count(*) FROM"
                    + " risk.monitored_transaction") - monitoredBefore, 0);
            holder.rollback();
        }
        storm.rest("R7 after");
        long ingested = count("SELECT count(*) FROM risk.monitored_transaction") - monitoredBefore;
        covered("R7 claims: entries ingested once after the held claim", ingested);
        expect("R7: the held claim's entries ingested once released", ingested >= 4 ? 1 : 0, 1);
        storm.restAndCensus("the named lock waits");
    }

    /**
     * R2a's one global order, raced where nothing upstream serialises it - and with the two acquisitions overlapping every
     * time (the entry's first probe survived the load, and a first version of this scene: two movements sharing two
     * subjects in the load always share a money row too and queue on it before R2a, and two racers' sub-millisecond
     * acquisitions almost never interleave by chance). One payer's two card payments to one merchant from one session:
     * a warm-up payment alone names the pair's subjects (the windows it counted - party, merchant, session), then the
     * scene holds the subject lock in the middle of their key order (namespace 131, as R2a takes it) while the pair is
     * confirmed at one barrier on A and on B, until both queue, and lets go within the budget. Taken in one ascending
     * order the two queue one behind the other and both are judged; taken in two orders each holds a key the other
     * waits for - a cycle the 250 ms budget cancels into an unforced TIMEOUT fail-safe. Fifteen pairs: thirty judged,
     * none a fail-safe, the deadlock count unchanged.
     */
    private void theSubjectLocksTakeOneOrder() throws Exception {
        Merchant merchant = storm.merchants.get(0);
        List<Customer> payers = new ArrayList<>();
        long overlapped = 0;
        java.util.concurrent.ExecutorService racing = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            for (int pair = 0; pair < 15; pair++) {
                Customer payer = fresh(1_000_00);
                payers.add(payer);
                String warmUp = storm.checkoutSession(a(), merchant, 9_00 + pair);
                String first = storm.checkoutSession(a(), merchant, 10_00 + pair);
                String second = storm.checkoutSession(b(), merchant, 11_00 + pair);
                if (warmUp == null || first == null || second == null) {
                    continue;
                }
                Instant since = RiskStormWorld.databaseNow();
                judged(tally(), "R2a: the warm-up payment naming the pair's subjects", a().door().post(
                        RiskStormWorld.CONFIRMATION, RiskStormTest.confirmation(warmUp, payer.card), payer.mfaToken, null,
                        Audience.SUBJECT));
                List<Integer> keys = new ArrayList<>(new java.util.TreeSet<>(RiskStormWorld.rows("SELECT DISTINCT"
                        + " dimension, subject_ref FROM risk.velocity_window WHERE last_counted_at >= ?",
                        java.sql.Timestamp.from(since)).stream().map(row -> com.finapp.risk.VelocityWindows.lockKey(
                                com.finapp.risk.VelocityDimension.valueOf(row[0]), row[1])).toList()));
                if (keys.size() < 3) {
                    tally().unexplained("R2a: the warm-up counted " + keys.size() + " subjects, not party, merchant and"
                            + " session");
                    continue;
                }
                int held = keys.get(keys.size() / 2);
                List<Object> both;
                try (Connection holder = DatabaseRoles.bootstrap()) {
                    holder.setAutoCommit(false);
                    try (PreparedStatement hold = holder.prepareStatement("SELECT pg_advisory_xact_lock(131, ?)")) {
                        hold.setInt(1, held);
                        hold.executeQuery().close();
                    }
                    java.util.concurrent.Future<List<Object>> raced = racing.submit(() -> RiskStormLocks.race(List.of(
                            () -> a().door().post(RiskStormWorld.CONFIRMATION, RiskStormTest.confirmation(first,
                                    payer.card), payer.mfaToken, null, Audience.SUBJECT),
                            () -> b().door().post(RiskStormWorld.CONFIRMATION, RiskStormTest.confirmation(second,
                                    payer.card), payer.mfaToken, null, Audience.SUBJECT))));
                    overlapped += bothQueuedOnSubjectLocks(raced) ? 1 : 0;
                    holder.rollback();
                    both = raced.get(2, java.util.concurrent.TimeUnit.MINUTES);
                }
                for (Object answer : both) {
                    judged(tally(), "R2a: one payer's card payment to one merchant, raced on both instances",
                            (Answer) answer);
                }
            }
        } finally {
            racing.shutdownNow();
        }
        long judged = 0;
        long failSafes = 0;
        for (Customer payer : payers) {
            judged += count("SELECT count(*) FROM risk.risk_assessment WHERE operation = 'CARD_PAYMENT' AND party_id = ?"
                    + " AND fail_safe_reason IS NULL", payer.party);
            failSafes += count("SELECT count(*) FROM risk.risk_assessment WHERE operation = 'CARD_PAYMENT' AND party_id ="
                    + " ? AND fail_safe_reason IS NOT NULL", payer.party);
        }
        expect("R2a: the raced card payments sealed no fail-safe", failSafes, 0);
        expect("R2a: forty-five card payments judged (a warm-up and a raced pair each)", judged, 45);
        // A pair whose partner arrived more than 150 ms late was let go alone (judged, never cycled): most must overlap.
        expect("R2a: at least twelve of the fifteen pairs queued on the subject locks together", overlapped >= 12 ? 1 : 0,
                1);
        covered("R2a: pairs raced sharing party, merchant and session subjects across instances", overlapped);
        // Each pair serialises on its shared subject locks by design - the second waits out the first's T-eval - on top
        // of the card judgement's own cost (the latency report's finding).
        storm.tolerates("the named lock waits", "RiskEvaluationLatencyOverBudget");
    }

    // ================================================================== the crash points

    /**
     * A crash at every write step of T-eval, T-mon, T-case, T-act and T-rep - each a row-conditioned trigger on the
     * scene's own subject that refuses the write (a forced rollback) or terminates its backend (a killed backend); the
     * act answered within its closed set; the trigger lifted; the act re-driven (the same key on the other instance, or
     * the platform's next claim); and the census: nothing half-written, everything once.
     */
    void crashPoints() throws Exception {
        if (!RiskStormTest.wanted("crash")) {
            return;
        }
        // ---- T-eval
        for (String[] step : new String[][] {
            {"risk.velocity_window", "INSERT OR UPDATE", "NEW.subject_ref = '%s'"},
            {"risk.risk_assessment", "INSERT", "NEW.party_id = '%s'"},
            {"risk.risk_assessment_input", "INSERT", "EXISTS (SELECT 1 FROM risk.risk_assessment a WHERE a.id ="
                    + " NEW.assessment_id AND a.party_id = '%s')"},
            {"risk.risk_rule_hit", "INSERT", "EXISTS (SELECT 1 FROM risk.risk_assessment a WHERE a.id = NEW.assessment_id"
                    + " AND a.party_id = '%s')"},
            {"risk.risk_reason", "INSERT", "EXISTS (SELECT 1 FROM risk.risk_assessment a WHERE a.id = NEW.assessment_id"
                    + " AND a.party_id = '%s')"},
            // The announcement names no party (INV-RSK-08): its aggregate is the assessment the same transaction sealed.
            {"platform.outbox_event", "INSERT", "NEW.event_type = 'risk.RiskDecisionMade' AND EXISTS (SELECT 1 FROM"
                    + " risk.risk_assessment a WHERE a.id = NEW.aggregate_id AND a.party_id = '%s')"}}) {
            for (boolean kill : new boolean[] {false, true}) {
                crashATransfer("T-eval " + step[0] + " " + step[1] + (kill ? " killed" : " refused"), step[0], step[1],
                        step[2], kill);
            }
        }
        crashThePin();
        crashTheTakeoverRaise();
        crashTheCaseOpening("T-eval risk.risk_case INSERT (the HOLD's case) refused", false);
        crashTheCaseOpening("T-eval risk.risk_case INSERT (the HOLD's case) killed", true);
        // ---- T-mon
        crashTheSweep("T-mon risk.monitored_transaction INSERT refused", "risk.monitored_transaction", "INSERT", false);
        crashTheSweep("T-mon risk.monitoring_watermark UPDATE killed", "risk.monitoring_watermark", "UPDATE", true);
        crashTheRuleLeg("T-mon the rule leg's AML alert refused", "risk.risk_alert", "NEW.subject_ref = '%s'");
        crashTheRuleLeg("T-mon the profile's version refused", "risk.customer_risk_profile_version",
                "NEW.party_id = '%s'");
        // ---- T-case
        crashACaseAct("T-case risk.risk_case_action INSERT refused", "risk.risk_case_action", "INSERT", false);
        crashACaseAct("T-case risk.risk_case_event INSERT killed", "risk.risk_case_event", "INSERT", true);
        crashACaseAct("T-case risk.risk_case UPDATE refused", "risk.risk_case", "UPDATE", false);
        // ---- T-act
        crashAnAction("T-act risk.risk_action UPDATE (the claim) refused", "status = 'DISPATCHED'", false);
        crashAnAction("T-act risk.risk_action UPDATE (the outcome) killed", "status = 'DONE'", true);
        // ---- T-rep
        crashAReport("T-rep risk.regulatory_report INSERT (the draft) refused", "DRAFT", false);
        crashAReport("T-rep risk.regulatory_report UPDATE (the approval) refused", "APPROVED", false);
        crashAReport("T-rep risk.regulatory_report UPDATE (the filing act) killed", "FILED", true);
        crashAReport("T-rep risk.regulatory_report UPDATE (the drive's claim) killed", "CLAIM", true);
        crashAReport("T-rep risk.regulatory_report UPDATE (the acknowledgement) killed", "ACKNOWLEDGED", true);
        // What the crash points broke on purpose, each of which must fire at their rest: the T-eval writes refused
        // behind the budget's savepoint (an unavailable fail-safe), the sweeps' claims refused or killed, the action's
        // claim and outcome, and the authority's answer whose record was killed.
        storm.breaks("the crash points", "RiskEvaluationUnavailable", "RiskSweeperClaimFailing",
                "RiskActionDispatchFailing", "RiskFilingCallsFailing", "RiskAlertsUnraised",
                "RiskMonitoringSubjectFailing");
        storm.tolerates("the crash points", "RiskEvaluationLatencyOverBudget");
        storm.restAndCensus("the crash points");
    }

    /** A transfer of a fresh party crashed at {@code table}'s {@code operation}, then re-driven under its key. */
    private void crashATransfer(String name, String table, String operation, String condition, boolean kill)
            throws Exception {
        Customer payer = fresh(1_000_00);
        storm.forced.add(payer.party.toString());
        Customer payee = fresh(1_00);
        String transferKey = key();
        String body = RiskStormTest.transferBody(payer.wallet, payee.wallet, 15_00);
        Answer crashed;
        try (AutoCloseable fault = RiskStormFaults.refuse(table, operation, String.format(condition, payer.party), kill)) {
            crashed = a().door().post(RiskStormWorld.TRANSFERS, body, payer.mfaToken, transferKey, Audience.SUBJECT);
        }
        judged(tally(), "crash point: " + name, crashed, "500 api.InternalError", "503");
        // The fault bit: the money transaction died with it, or - a step behind T-eval's timed savepoint - the step was
        // undone alone and sealed as the band's fail-safe. An answer that is neither never met the fault.
        expect("crash point " + name + ": the fault bit", !crashed.ok() || count("SELECT count(*) FROM"
                + " risk.risk_assessment WHERE party_id = ? AND fail_safe_reason IS NOT NULL", payer.party) == 1 ? 1 : 0,
                1);
        Answer redriven = b().door().post(RiskStormWorld.TRANSFERS, body, payer.mfaToken, transferKey, Audience.SUBJECT);
        judged(tally(), "crash point re-driven: " + name, redriven);
        expect("crash point " + name + ": one transfer", count("SELECT count(*) FROM transfers.transfer t JOIN"
                + " party.customer c ON c.id = t.customer_id WHERE c.party_id = ?", payer.party), 1);
        expect("crash point " + name + ": one assessment", count("SELECT count(*) FROM risk.risk_assessment WHERE"
                + " party_id = ?", payer.party), 1);
        expect("crash point " + name + ": one posting", count("SELECT count(*) FROM ledger.journal_entry e WHERE"
                + " e.reference IN (SELECT t.id::text FROM transfers.transfer t JOIN party.customer c ON c.id ="
                + " t.customer_id WHERE c.party_id = ?)", payer.party), redriven.ok()
                && "COMPLETED".equals(redriven.optionalField("status").orElse("")) ? 1 : 0);
        covered("crash points", 1);
    }

    /** The monitoring claim crashed at {@code table}: the batch rolls back whole, the next claim ingests once. */
    private void crashTheSweep(String name, String table, String operation, boolean kill) throws Exception {
        Customer payer = fresh(1_000_00);
        Customer payee = fresh(1_00);
        storm.rest(name + " before");
        String condition = table.equals("risk.monitored_transaction") ? "true" : "NEW.last_entry_id <> OLD.last_entry_id";
        try (AutoCloseable fault = RiskStormFaults.refuse(table, operation, condition, kill)) {
            judged(tally(), "crash point " + name + ": a movement to ingest", a().door().post(RiskStormWorld.TRANSFERS,
                    RiskStormTest.transferBody(payer.wallet, payee.wallet, 16_00), payer.mfaToken, key(),
                    Audience.SUBJECT));
            RiskStormTest.sleep(RiskStormTest.HORIZON.plusSeconds(6));
            expect("crash point " + name + ": the entry not ingested while the claim fails", count("SELECT count(*) FROM"
                    + " risk.monitored_transaction m JOIN ledger.journal_entry e ON e.id = m.journal_entry_id WHERE"
                    + " e.reference IN (SELECT t.id::text FROM transfers.transfer t JOIN party.customer c ON c.id ="
                    + " t.customer_id WHERE c.party_id = ?)", payer.party), 0);
        }
        storm.rest(name + " after");
        expect("crash point " + name + ": ingested once after", count("SELECT count(*) FROM risk.monitored_transaction m"
                + " JOIN ledger.journal_entry e ON e.id = m.journal_entry_id WHERE e.reference IN (SELECT t.id::text FROM"
                + " transfers.transfer t JOIN party.customer c ON c.id = t.customer_id WHERE c.party_id = ?)",
                payer.party), 1);
        storm.breaks(name, "RiskSweeperClaimFailing");
        covered("crash points", 1);
    }

    /** A case act crashed at {@code table}: refused (or its backend killed), then sent again - kept once. */
    private void crashACaseAct(String name, String table, String operation, boolean kill) throws Exception {
        Customer subject = fresh(1_00);
        String caseId = openAndTake(storm.analysts[2], "FRAUD", "PARTY", subject.party.toString());
        int revision = revision(caseId);
        String condition = table.equals("risk.risk_case") ? "NEW.id = '" + caseId + "'" : "NEW.case_id = '" + caseId + "'";
        String noteKey = key();
        String body = "{\"act\":\"NOTE\",\"revision\":" + revision + ",\"text\":\"a crashed note\"}";
        Answer crashed;
        try (AutoCloseable fault = RiskStormFaults.refuse(table, operation, condition, kill)) {
            crashed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/actions", body, storm.analysts[2].token(),
                    noteKey, Audience.OPERATOR);
        }
        judged(tally(), "crash point: " + name, crashed, "500 api.InternalError", "503");
        expect("crash point " + name + ": the fault bit", crashed.ok() ? 0 : 1, 1);
        Answer redriven = b().door().post(RiskStormWorld.CASES + "/" + caseId + "/actions", body,
                storm.analysts[2].token(), noteKey, Audience.OPERATOR);
        judged(tally(), "crash point re-driven: " + name, redriven);
        expect("crash point " + name + ": the note kept once", count("SELECT count(*) FROM risk.risk_case_action WHERE"
                + " case_id = ?::uuid AND kind = 'NOTE'", caseId), 1);
        expect("crash point " + name + ": the revision moved once", revision(caseId) - revision, 1);
        covered("crash points", 1);
    }

    /** An approved action's claim or outcome crashed: the platform re-drives it - one effect, DONE once. */
    private void crashAnAction(String name, String newStatus, boolean kill) throws Exception {
        Customer suspect = fresh(1_00);
        String caseId = openAndTake(storm.analysts[3], "ATO", "PARTY", suspect.party.toString());
        String condition = "NEW.target_ref = '" + suspect.party + "' AND NEW." + newStatus;
        String action;
        try (AutoCloseable fault = RiskStormFaults.refuse("risk.risk_action", "UPDATE", condition, kill)) {
            action = proposeAndApproveAction(caseId, "REVOKE_SESSIONS", null);
            RiskStormTest.sleep(Duration.ofSeconds(3));
        }
        awaitAction(action, "DONE");
        expect("crash point " + name + ": the owner's key once", count("SELECT count(*) FROM platform.idempotency_record"
                + " WHERE idempotency_key = ?", "risk.action:" + action), 1);
        storm.breaks(name, "RiskSweeperClaimFailing", "RiskActionDispatchFailing");
        covered("crash points", 1);
    }

    /**
     * A report's step crashed - the draft refused, the approval refused, the officer's filing act killed, the drive's
     * claim killed, or the authority's acknowledgement killed - and re-driven (the officer's same key on the other
     * instance, or the drive's next claim and its inquiry): every status reached once ({@code DRAFT}, {@code APPROVED},
     * {@code FILED}, {@code ACKNOWLEDGED} - four events), one report for the case, filed at the authority at most once.
     */
    private void crashAReport(String name, String step, boolean kill) throws Exception {
        Customer subject = fresh(1_00);
        String caseId = openAndTake(storm.analysts[0], "AML", "PARTY", subject.party.toString());
        Answer proposed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/dispositions", "{\"revision\":"
                + revision(caseId) + ",\"disposition\":\"CONFIRMED_SUSPICIOUS\",\"reason\":\"confirmed\"}",
                storm.analysts[0].token(), key(), Audience.OPERATOR);
        judged(tally(), "crash point " + name + ": suspicion proposed", proposed);
        judged(tally(), "crash point " + name + ": suspicion approved", b().door().post(RiskStormWorld.CASES + "/"
                + caseId + "/dispositions/" + proposed.field("dispositionId") + "/approval", "{\"revision\":"
                + revision(caseId) + ",\"reason\":\"agreed\"}", storm.analysts[1].token(), key(), Audience.OPERATOR));
        String reports = RiskStormWorld.CASES + "/" + caseId + "/reports";
        String draft = "{\"jurisdiction\":\"EU-REF\",\"narrative\":\"A crash point's narrative.\",\"reason\":"
                + "\"confirmed\"}";
        String draftKey = key();
        Answer drafted;
        if (step.equals("DRAFT")) {
            try (AutoCloseable fault = RiskStormFaults.refuse("risk.regulatory_report", "INSERT", "NEW.case_id = '"
                    + caseId + "'", kill)) {
                judged(tally(), "crash point: " + name, a().door().post(reports, draft, storm.amlOfficers[0].token(),
                        draftKey, Audience.OPERATOR), "500 api.InternalError", "503");
            }
            expect("crash point " + name + ": nothing drafted while refused", count("SELECT count(*) FROM"
                    + " risk.regulatory_report WHERE case_id = ?::uuid", caseId), 0);
            drafted = b().door().post(reports, draft, storm.amlOfficers[0].token(), draftKey, Audience.OPERATOR);
            judged(tally(), "crash point re-driven: " + name, drafted);
        } else {
            drafted = a().door().post(reports, draft, storm.amlOfficers[0].token(), draftKey, Audience.OPERATOR);
            judged(tally(), "crash point " + name + ": drafted", drafted);
        }
        String report = drafted.field("reportId");
        String approval = reports + "/" + report + "/approval";
        String approvalKey = key();
        if (step.equals("APPROVED")) {
            try (AutoCloseable fault = RiskStormFaults.refuse("risk.regulatory_report", "UPDATE", "NEW.id = '" + report
                    + "' AND NEW.status = 'APPROVED'", kill)) {
                judged(tally(), "crash point: " + name, b().door().post(approval, "{\"reason\":\"accurate\"}",
                        storm.amlOfficers[1].token(), approvalKey, Audience.OPERATOR), "500 api.InternalError", "503");
            }
            judged(tally(), "crash point re-driven: " + name, a().door().post(approval, "{\"reason\":\"accurate\"}",
                    storm.amlOfficers[1].token(), approvalKey, Audience.OPERATOR));
        } else {
            judged(tally(), "crash point " + name + ": approved", b().door().post(approval, "{\"reason\":\"accurate\"}",
                    storm.amlOfficers[1].token(), approvalKey, Audience.OPERATOR));
        }
        String filing = reports + "/" + report + "/filing";
        String filingKey = key();
        String condition = switch (step) {
            // The officer's act alone: APPROVED -> FILED (the drive's claims keep FILED and move attempts).
            case "FILED" -> "NEW.id = '" + report + "' AND NEW.status = 'FILED' AND OLD.status = 'APPROVED'";
            case "CLAIM" -> "NEW.id = '" + report + "' AND NEW.attempts <> OLD.attempts";
            case "ACKNOWLEDGED" -> "NEW.id = '" + report + "' AND NEW.status = 'ACKNOWLEDGED'";
            default -> null;
        };
        if (condition == null) {
            judged(tally(), "crash point " + name + ": filed", a().door().post(filing, "{\"reason\":\"file\"}",
                    storm.amlOfficers[2].token(), filingKey, Audience.OPERATOR));
        } else {
            try (AutoCloseable fault = RiskStormFaults.refuse("risk.regulatory_report", "UPDATE", condition, kill)) {
                judged(tally(), "crash point: " + name + " (the filing act)", a().door().post(filing,
                        "{\"reason\":\"file\"}", storm.amlOfficers[2].token(), filingKey, Audience.OPERATOR),
                        "500 api.InternalError", "503");
                RiskStormTest.sleep(Duration.ofSeconds(3));
            }
            if (step.equals("FILED")) {
                judged(tally(), "crash point re-driven: " + name, b().door().post(filing, "{\"reason\":\"file\"}",
                        storm.amlOfficers[2].token(), filingKey, Audience.OPERATOR));
            }
        }
        awaitDatabase("the crashed report acknowledged", "SELECT status = 'ACKNOWLEDGED' FROM risk.regulatory_report WHERE"
                + " id = ?::uuid", report);
        expect("crash point " + name + ": one report for the case", count("SELECT count(*) FROM risk.regulatory_report"
                + " WHERE case_id = ?::uuid", caseId), 1);
        expect("crash point " + name + ": filed at the authority at most once",
                RiskStormFaults.reached("file", report) <= 1 ? 1 : 0, 1);
        if (step.equals("ACKNOWLEDGED")) {
            // The fault bit: the authority's answer was killed with its record, so the drive had to ask for it.
            expect("crash point " + name + ": the lost acknowledgement inquired for",
                    RiskStormFaults.reached("inquire", report) > 0 ? 1 : 0, 1);
        }
        expect("crash point " + name + ": DRAFT, APPROVED, FILED, ACKNOWLEDGED each reached once", count("SELECT"
                + " count(*) FROM risk.regulatory_report_event WHERE report_id = ?::uuid AND status IN ('DRAFT',"
                + " 'APPROVED', 'FILED', 'ACKNOWLEDGED')", report), 4);
        covered("crash points", 1);
    }

    /**
     * T-eval's pin killed while it waits (R1): the TRANSFER version's row held {@code FOR UPDATE} as an activation holds
     * it, a transfer on A queued on its {@code FOR SHARE} (the pin is outside the budget - it waits, unbounded), that
     * backend terminated mid-wait; the holder released; re-driven on B under its key - one transfer, one assessment.
     */
    private void crashThePin() throws Exception {
        String name = "T-eval the pin (R1 FOR SHARE) killed while it waits";
        Customer payer = fresh(1_000_00);
        Customer payee = fresh(1_00);
        String version = scalar("SELECT id::text FROM risk.risk_policy_version WHERE operation = 'TRANSFER' AND status ="
                + " 'ACTIVE'");
        String transferKey = key();
        String body = RiskStormTest.transferBody(payer.wallet, payee.wallet, 17_00);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        int killed = 0;
        Answer crashed;
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement hold = holder.prepareStatement("SELECT id FROM risk.risk_policy_version WHERE id ="
                    + " ?::uuid FOR UPDATE")) {
                hold.setString(1, version);
                hold.executeQuery().close();
            }
            java.util.concurrent.Future<Object> act = pool.submit(() -> a().door().post(RiskStormWorld.TRANSFERS, body,
                    payer.mfaToken, transferKey, Audience.SUBJECT));
            for (RiskStormLocks.Waiter waiter : RiskStormLocks.awaitBlockedBy(RiskStormLocks.pid(holder),
                    "risk_policy_version", act)) {
                killed += RiskStormFaults.terminate(waiter.pid()) ? 1 : 0;
            }
            holder.rollback();
            crashed = (Answer) act.get(2, java.util.concurrent.TimeUnit.MINUTES);
        } finally {
            pool.shutdownNow();
        }
        expect("crash point " + name + ": the waiting backend killed", killed > 0 ? 1 : 0, 1);
        judged(tally(), "crash point: " + name, crashed, "500 api.InternalError", "503");
        // The fault bit: the money transaction died with it, or - a step behind T-eval's timed savepoint - the step was
        // undone alone and sealed as the band's fail-safe. An answer that is neither never met the fault.
        expect("crash point " + name + ": the fault bit", !crashed.ok() || count("SELECT count(*) FROM"
                + " risk.risk_assessment WHERE party_id = ? AND fail_safe_reason IS NOT NULL", payer.party) == 1 ? 1 : 0,
                1);
        Answer redriven = b().door().post(RiskStormWorld.TRANSFERS, body, payer.mfaToken, transferKey, Audience.SUBJECT);
        judged(tally(), "crash point re-driven: " + name, redriven);
        expect("crash point " + name + ": one transfer", count("SELECT count(*) FROM transfers.transfer t JOIN"
                + " party.customer c ON c.id = t.customer_id WHERE c.party_id = ?", payer.party), 1);
        expect("crash point " + name + ": one assessment", count("SELECT count(*) FROM risk.risk_assessment WHERE"
                + " party_id = ?", payer.party), 1);
        covered("crash points", 1);
    }

    /**
     * T-eval's account-takeover raise refused behind its savepoint - armed before the party's credential change, so the
     * profile leg's retrospective raise of the same sequence is refused with it: the withdrawal's decision stands sealed,
     * no alert is born ({@code RiskAlertsUnraised} counts it); the fault lifted, the party's next withdrawal raises the
     * sequence's one alert.
     */
    private void crashTheTakeoverRaise() throws Exception {
        String name = "T-eval risk.risk_alert INSERT (the account-takeover raise) refused";
        Customer victim = fresh(10_000_00);
        String takeovers = "SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND kind = 'ACCOUNT_TAKEOVER'";
        try (AutoCloseable fault = RiskStormFaults.refuse("risk.risk_alert", "INSERT", "NEW.subject_ref = '"
                + victim.party + "' AND NEW.kind = 'ACCOUNT_TAKEOVER'", false)) {
            credentialChange(victim);
            storm.relay.drain(5);
            judged(tally(), "crash point: " + name, a().door().post(RiskStormWorld.WITHDRAWALS,
                    RiskStormTest.withdrawalBody(victim, 10_00), victim.mfaToken, key(), Audience.SUBJECT));
            expect("crash point " + name + ": the decision sealed", count("SELECT count(*) FROM risk.risk_assessment"
                    + " WHERE party_id = ? AND operation = 'WITHDRAWAL' AND decision IN ('HOLD', 'DENY')", victim.party), 1);
            expect("crash point " + name + ": no alert born", count(takeovers, victim.party.toString()), 0);
        }
        judged(tally(), "crash point re-driven: " + name, b().door().post(RiskStormWorld.WITHDRAWALS,
                RiskStormTest.withdrawalBody(victim, 11_00), victim.mfaToken, key(), Audience.SUBJECT));
        expect("crash point " + name + ": the next withdrawal raises the sequence's one alert", count(takeovers,
                victim.party.toString()), 1);
        covered("crash points", 1);
    }

    /**
     * T-eval's case step crashed: a withdrawal judged HOLD opens its case in the money's own transaction and never
     * commits without it (fail closed), so the case's refused or killed birth takes the withdrawal with it; the same key
     * on the other instance re-drives - HELD once, one withdrawal, one assessment, one case.
     */
    private void crashTheCaseOpening(String name, boolean kill) throws Exception {
        Customer customer = fresh(50_000_00);
        String withdrawalKey = key();
        String body = RiskStormTest.withdrawalBody(customer, 2_600_00);
        Answer crashed;
        try (AutoCloseable fault = RiskStormFaults.refuse("risk.risk_case", "INSERT", "NEW.subject_ref = '"
                + customer.party + "'", kill)) {
            crashed = a().door().post(RiskStormWorld.WITHDRAWALS, body, customer.mfaToken, withdrawalKey,
                    Audience.SUBJECT);
        }
        judged(tally(), "crash point: " + name, crashed, "500 api.InternalError", "503");
        expect("crash point " + name + ": the fault bit", crashed.ok() ? 0 : 1, 1);
        Answer redriven = b().door().post(RiskStormWorld.WITHDRAWALS, body, customer.mfaToken, withdrawalKey,
                Audience.SUBJECT);
        judged(tally(), "crash point re-driven: " + name, redriven);
        expect("crash point " + name + ": HELD once", "HELD".equals(redriven.optionalField("status").orElse("")) ? 1 : 0,
                1);
        expect("crash point " + name + ": one withdrawal", count("SELECT count(*) FROM payments.withdrawal WHERE"
                + " party_id = ?", customer.party), 1);
        expect("crash point " + name + ": one assessment", count("SELECT count(*) FROM risk.risk_assessment WHERE"
                + " party_id = ? AND operation = 'WITHDRAWAL'", customer.party), 1);
        expect("crash point " + name + ": one case", count("SELECT count(*) FROM risk.risk_case WHERE subject_ref = ?",
                customer.party.toString()), 1);
        covered("crash points", 1);
    }

    /**
     * T-mon's rule leg crashed for one party - its AML alert or its profile's version refused, armed before the party's
     * funding is monitored: the leg rolls back alone behind its savepoint (the run goes on;
     * {@code RiskMonitoringSubjectFailing} counts it), the movements stay monitored once, the party unjudged; the fault
     * lifted, the party's next movement runs the leg again - the STRUCTURING alert raised once, version 1 born once.
     */
    private void crashTheRuleLeg(String name, String table, String condition) throws Exception {
        Customer structurer = fresh(50_000_00);
        Customer payee = fresh(1_00);
        String party = structurer.party.toString();
        String structuring = "SELECT count(*) FROM risk.risk_alert WHERE subject_ref = ? AND rule_code = 'STRUCTURING'";
        String versions = "SELECT count(*) FROM risk.customer_risk_profile_version WHERE party_id = ?";
        try (AutoCloseable fault = RiskStormFaults.refuse(table, "INSERT", String.format(condition, party), false)) {
            for (int i = 0; i < 5; i++) {
                judged(tally(), "crash point " + name + ": a transfer below the threshold", (i % 2 == 0 ? a() : b())
                        .door().post(RiskStormWorld.TRANSFERS, RiskStormTest.transferBody(structurer.wallet,
                                payee.wallet, 900_00 + i), structurer.mfaToken, key(), Audience.SUBJECT));
            }
            storm.rest(name + " during");
            expect("crash point " + name + ": the movements monitored once", count("SELECT count(*) FROM"
                    + " risk.monitored_transaction m JOIN ledger.journal_entry e ON e.id = m.journal_entry_id WHERE"
                    + " e.reference IN (SELECT t.id::text FROM transfers.transfer t JOIN party.customer c ON c.id ="
                    + " t.customer_id WHERE c.party_id = ?)", structurer.party), 5);
            expect("crash point " + name + ": no STRUCTURING alert while the leg fails", count(structuring, party), 0);
            if (table.equals("risk.customer_risk_profile_version")) {
                // The funding's own leg fails here too (version 1 is the party's first rating); the alert's crash
                // fails only the structuring leg - the funding's rating was born before the rule could fire.
                expect("crash point " + name + ": no profile version while the leg fails", count(versions,
                        structurer.party), 0);
            }
        }
        judged(tally(), "crash point re-driven: " + name, either().door().post(RiskStormWorld.TRANSFERS,
                RiskStormTest.transferBody(structurer.wallet, payee.wallet, 905_00), structurer.mfaToken, key(),
                Audience.SUBJECT));
        storm.rest(name + " after");
        expect("crash point " + name + ": the STRUCTURING alert raised once", count(structuring, party), 1);
        expect("crash point " + name + ": the profile's version 1 born once", count(versions + " AND version = 1",
                structurer.party), 1);
        covered("crash points", 1);
    }

    // ================================================================== case and action helpers

    /** A case opened by hand by {@code analyst} on another instance's door and taken - its id. */
    String openAndTake(Operator analyst, String kind, String subjectKind, String subjectRef) throws Exception {
        String existing = scalar("SELECT id::text FROM risk.risk_case WHERE subject_ref = ? AND kind = ? AND status <>"
                + " 'DISPOSED'", subjectRef, kind);
        String caseId = existing;
        if (caseId == null) {
            Answer opened = either().door().post(RiskStormWorld.CASES, "{\"kind\":\"" + kind + "\",\"subjectKind\":\""
                    + subjectKind + "\",\"subjectRef\":\"" + subjectRef + "\",\"reason\":\"the storm's scene\"}",
                    analyst.token(), key(), Audience.OPERATOR);
            judged(tally(), "case opened by hand", opened);
            caseId = opened.field("caseId");
        }
        take(analyst, caseId);
        return caseId;
    }

    void take(Operator analyst, String caseId) throws Exception {
        Answer taken = either().door().post(RiskStormWorld.CASES + "/" + caseId + "/actions", "{\"act\":\"TAKE\","
                + "\"revision\":" + revision(caseId) + "}", analyst.token(), key(), Audience.OPERATOR);
        judged(tally(), "case taken", taken);
    }

    static int revision(String caseId) throws SQLException {
        return Integer.parseInt(scalar("SELECT revision FROM risk.risk_case WHERE id = ?::uuid", caseId));
    }

    /** {@code kind} proposed on the case by its taker, approved by a second analyst from a MULTI_FACTOR session - its id. */
    String proposeAndApproveAction(String caseId, String kind, String targetRef) throws Exception {
        String taker = scalar("SELECT taken_by FROM risk.risk_case WHERE id = ?::uuid", caseId);
        Operator proposer = null;
        for (Operator analyst : storm.analysts) {
            if (analyst.identity().toString().equals(taker)) {
                proposer = analyst;
            }
        }
        if (proposer == null) {
            proposer = storm.analysts[0];
        }
        Operator approver = storm.analysts[(List.of(storm.analysts).indexOf(proposer) + 1) % storm.analysts.length];
        Answer proposed = a().door().post(RiskStormWorld.CASES + "/" + caseId + "/risk-actions", "{\"revision\":"
                + revision(caseId) + ",\"kind\":\"" + kind + "\"" + (targetRef == null ? "" : ",\"targetRef\":\""
                + targetRef + "\"") + ",\"reason\":\"the storm's action\"}", proposer.token(), key(), Audience.OPERATOR);
        judged(tally(), "risk action proposed", proposed);
        String action = proposed.field("actionId");
        refused(tally(), "an action approved by its proposer", b().door().post(RiskStormWorld.CASES + "/" + caseId
                + "/risk-actions/" + action + "/approval", "{\"revision\":" + revision(caseId) + ",\"reason\":\"mine\"}",
                proposer.token(), key(), Audience.OPERATOR), "403 risk.SelfApprovalRefused");
        judged(tally(), "risk action approved", b().door().post(RiskStormWorld.CASES + "/" + caseId + "/risk-actions/"
                + action + "/approval", "{\"revision\":" + revision(caseId) + ",\"reason\":\"confirmed\"}",
                approver.token(), key(), Audience.OPERATOR));
        return action;
    }

    void awaitAction(String action, String status) throws Exception {
        awaitDatabase("the action " + status, "SELECT status = ? FROM risk.risk_action WHERE id = ?::uuid", status,
                action);
    }

    void awaitDatabase(String what, String predicate, Object... parameters) throws Exception {
        RiskStormWorld.awaitDatabase(Duration.ofSeconds(90), what, predicate, parameters);
    }

    // ================================================================== the coverage ledger

    private final Map<String, long[]> expectations = new LinkedHashMap<>();

    /** A scene's exact expectation, counted from rows: recorded, and judged at the end. */
    void expect(String what, long seen, long wanted) {
        expectations.put(what, new long[] {seen, wanted});
        covered(what, seen);
        if (seen != wanted) {
            tally().unexplained(what + ": " + seen + " where " + wanted + " was due");
        }
    }

    /** At the end: every expectation met, and every scene's minimum exercised (unless a development run). */
    void atTheEnd(SoftAssertions softly) {
        expectations.forEach((what, pair) -> softly.assertThat(pair[0]).as(what).isEqualTo(pair[1]));
        if (RiskStormTest.partial()) {
            return;
        }
        for (String minimum : List.of("scenario 1: one signal of the event delivered three times",
                "scenario 2: one ACCOUNT_TAKEOVER row raised by two instances",
                "scenario 3: ten assessments, each pinning the predecessor or the successor",
                "scenario 4: one answer lost", "scenario 5: one 409 risk.CaseRevisionStale",
                "scenario 6: a new open case naming the disposed one",
                "scenario 7: the decision stands ALLOW and COUNTED", "scenario 8: no new alerts",
                "scenario 9: EUR 999.99 COMPLETED under ALLOW(RISK_UNAVAILABLE, TIMEOUT)",
                "scenario 10: released - dispatched once", "scenario 11: one ACCOUNT_TAKEOVER alert",
                "scenario 12: one STRUCTURING alert in the window", "scenario 13: the decision replays IDENTICAL",
                "scenario 14: ten counted", "scenario 15: a disposition refused to its proposer and recorded FAILED",
                "the filing: filed at the authority once", "R1 waits seen as the act's own backend",
                "R4 waits seen as the act's own backend", "crash points")) {
            // An expectation judged (whatever its wanted figure, zero included) or a count exercised.
            boolean exercised = expectations.containsKey(minimum) || storm.coverage.getOrDefault(minimum, 0L) > 0;
            softly.assertThat(exercised).as("coverage: %s", minimum).isTrue();
        }
    }

    // ================================================================== the seeded agreements

    /** A loan of {@code party} seeded into lending's table (triggers suspended for the seed - CollectionsCaseDatabaseTest's). */
    static UUID seededLoan(UUID party) throws SQLException {
        UUID loan = RiskStormWorld.IDS.next();
        RiskStormWorld.asBootstrap("INSERT INTO lending.loan (id, party_id, product, kind, status, currency, offer_id,"
                + " decision_id, opened_at, closed_at, closure_reason, correlation_id, next_servicing_at) VALUES (?, ?,"
                + " 'PERSONAL_LOAN', 'INSTALMENT', 'CLOSED', 'EUR', gen_random_uuid(), gen_random_uuid(),"
                + " clock_timestamp(), clock_timestamp(), 'REPAID', 'p13-tst-001-seed', clock_timestamp())", loan, party);
        return loan;
    }

    /** A BNPL obligation of {@code party} seeded into bnpl's table (triggers suspended for the seed). */
    static UUID seededObligation(UUID party) throws SQLException {
        UUID obligation = RiskStormWorld.IDS.next();
        RiskStormWorld.asBootstrap("INSERT INTO bnpl.bnpl_obligation (id, purchase_id, agreement_id, party_id,"
                + " decision_id, product, plan, currency, status, servicing_zone, purchase_date, opened_at,"
                + " next_servicing_at, closed_at, closure_reason, correlation_id) VALUES (?, gen_random_uuid(),"
                + " gen_random_uuid(), ?, gen_random_uuid(), 'BNPL_PAY_IN_4', 'PAY_IN_4', 'EUR', 'CLOSED',"
                + " 'Europe/Berlin', current_date, clock_timestamp(), clock_timestamp(), clock_timestamp(), 'REPAID',"
                + " 'p13-tst-001-seed')", obligation, party);
        return obligation;
    }

    /** A delinquency condition's payload (lending's and bnpl's shape - CollectionsCaseDatabaseTest's). */
    static EventPayload condition(String idField, UUID id, String businessDate, boolean defaulted) {
        return EventPayload.of()
                .with(idField, id.toString())
                .with("product", "PERSONAL_LOAN")
                .with("businessDate", businessDate)
                .with("daysPastDue", defaulted ? "91" : "31")
                .with("bucket", defaulted ? "B4_90_PLUS" : "B2_30_59")
                .with("defaulted", Boolean.toString(defaulted))
                .with("agreementVersion", "1");
    }

    static List<String> ids(String sql, Object... parameters) throws SQLException {
        return strings(sql, parameters);
    }
}
