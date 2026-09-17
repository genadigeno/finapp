package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.Session;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * `/v1/ledger/adjustments` over real HTTP (`P3-TSK-017` the write, `P3-TSK-021` the
 * four-eyes control; {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-AUD-03}): the
 * highest-risk financial action as two authenticated acts — a proposal that posts nothing,
 * and a <em>second</em> person's approval that posts the entry — with self-approval the
 * invariant's own named negative, and no request shape our {@code 500}.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the adjustment under four-eyes: propose, approve, refuse (P3-TSK-021)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class AdjustmentEndpointDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final Pattern ENTRY_ID = Pattern.compile("\"entryId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern PROPOSAL_ID =
            Pattern.compile("\"proposalId\"\\s*:\\s*\"([^\"]+)\"");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MeterRegistry registry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final LedgerAccountStore<Connection> accounts = new JdbcLedgerAccountStore();

    @Test
    @DisplayName("an operator proposes and a SECOND operator approves: nothing posts before"
            + " the approval, the entry names the approver, and both acts are audited"
            + " (INV-AUD-04)")
    void anOperatorPostsAnAdjustment() throws Exception {
        Operator initiator = givenAnOperator();
        Operator approver = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        // Act one: the proposal. NOTHING posts - the response carries no entry, because
        // there is none to carry.
        long entriesBefore = adjustmentEntryCount();
        HttpResponse<String> proposed =
                post(body(wallet, "5.00", "correcting settlement break INC-2041"),
                        initiator.token(), "adj-" + IDS.next());
        assertThat(proposed.statusCode()).isEqualTo(201);
        assertThat(proposed.body()).contains("\"status\":\"PROPOSED\"");
        assertThat(proposed.body()).doesNotContain("entryId");
        UUID proposal = proposalIdOf(proposed);
        assertThat(adjustmentEntryCount())
                .as("a proposal posts nothing: the entry is the approval's")
                .isEqualTo(entriesBefore);

        try (Connection app = DatabaseRoles.application()) {
            // The four-eyes trail's FIRST record: the initiator, with the justification,
            // at the moment they wrote it (INV-REV-04).
            assertThat(auditRowsFor(app, "ledger.AdjustmentProposed", proposal.toString()))
                    .isEqualTo(1);
            assertThat(auditColumnFor(app, "ledger.AdjustmentProposed", proposal.toString(),
                            "actor_id"))
                    .isEqualTo(initiator.identity().value().toString());
            assertThat(auditColumnFor(app, "ledger.AdjustmentProposed", proposal.toString(),
                            "reason"))
                    .isEqualTo("correcting settlement break INC-2041");
        }

        // The approver reads what they would approve: the proposal in full, lines and
        // reason included (the reviewer-sees-everything argument).
        HttpResponse<String> read = get(proposal.toString(), approver.token());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body())
                .contains("\"status\":\"PROPOSED\"")
                .contains("correcting settlement break INC-2041")
                .contains("\"amount\":\"5.00\"")
                .contains("\"proposedBy\":\"" + initiator.identity().value() + "\"");

        // Act two: a DIFFERENT person approves, and the entry posts in their transaction.
        HttpResponse<String> approvedResponse = approve(proposal.toString(), approver.token());
        assertThat(approvedResponse.statusCode()).isEqualTo(201);
        UUID entry = entryIdOf(approvedResponse);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(entryColumn(app, entry, "entry_type")).isEqualTo("ADJUSTMENT");
            assertThat(entryColumn(app, entry, "reason"))
                    .isEqualTo("correcting settlement break INC-2041");
            // The entry's actor is the APPROVER: the posting is their act (ADR-0021's
            // honesty rule); the initiator is one join away, on the proposal row - and the
            // entry's idempotency_scope carries the proposal, the investigator's join.
            assertThat(entryColumn(app, entry, "actor_id"))
                    .isEqualTo(approver.identity().value().toString());
            assertThat(entryColumn(app, entry, "idempotency_scope"))
                    .isEqualTo("ledger.adjust.approve:" + proposal);

            // The trail's SECOND record: ledger.AdjustmentPosted naming the approver, with
            // the adjustment's reason - two acts, two records, two people (INV-AUD-04).
            assertThat(auditRowsFor(app, "ledger.AdjustmentPosted", entry.toString()))
                    .isEqualTo(1);
            assertThat(auditColumnFor(app, "ledger.AdjustmentPosted", entry.toString(),
                            "actor_id"))
                    .isEqualTo(approver.identity().value().toString());
            assertThat(eventRowsFor(app, entry)).isEqualTo(1);
            assertThat(postedMinorOf(app, wallet)).isEqualTo(500);

            // The proposal row closed onto the entry: APPROVED, by the approver, linked.
            assertThat(proposalColumn(app, proposal, "status")).isEqualTo("APPROVED");
            assertThat(proposalColumn(app, proposal, "decided_by"))
                    .isEqualTo(approver.identity().value().toString());
            assertThat(proposalColumn(app, proposal, "journal_entry_id"))
                    .isEqualTo(entry.toString());
        }
    }

    @Test
    @DisplayName("the initiator cannot approve their own proposal: 409, and NOTHING is"
            + " written - the invariant's named negative (INV-AUD-04)")
    void selfApprovalIsRefused() throws Exception {
        Operator initiator = givenAnOperator();
        Operator secondPerson = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        UUID proposal =
                proposalIdOf(
                        post(body(wallet, "5.00", "self-approval probe"), initiator.token(),
                                "adj-" + IDS.next()));

        long entriesBefore = adjustmentEntryCount();
        HttpResponse<String> refused = approve(proposal.toString(), initiator.token());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("ledger.SelfApprovalRefused");

        try (Connection app = DatabaseRoles.application()) {
            // Nothing was written: no entry, no posted-audit, and the proposal still
            // stands - visibly PROPOSED, for a second person to decide.
            assertThat(adjustmentEntryCount()).isEqualTo(entriesBefore);
            assertThat(proposalColumn(app, proposal, "status")).isEqualTo("PROPOSED");
            assertThat(proposalColumn(app, proposal, "decided_by")).isNull();
        }

        // The positive control, so the refusal is not blanket: a second person approves
        // the very same proposal.
        assertThat(approve(proposal.toString(), secondPerson.token()).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("ten concurrent approvals produce exactly one entry, counted in the table -"
            + " and every response converges on it (INV-CON-02's shape)")
    void tenConcurrentApprovalsProduceOneEntry() throws Exception {
        Operator initiator = givenAnOperator();
        Operator approver = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        UUID proposal =
                proposalIdOf(
                        post(body(wallet, "9.00", "concurrent approval probe"),
                                initiator.token(), "adj-" + IDS.next()));

        int racers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<HttpResponse<String>>> outcomes = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                outcomes.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return approve(proposal.toString(), approver.token());
                                }));
            }
            start.countDown();

            UUID entry = null;
            for (Future<HttpResponse<String>> outcome : outcomes) {
                HttpResponse<String> response = outcome.get();
                // Every racer converges on the one recorded outcome (INV-IDEM-01 through
                // state): the winner posted, the losers resumed onto the FOR UPDATE lock,
                // saw APPROVED by themselves, and replayed the entry.
                assertThat(response.statusCode()).isEqualTo(201);
                UUID answered = entryIdOf(response);
                if (entry == null) {
                    entry = answered;
                }
                assertThat(answered).isEqualTo(entry);
            }

            try (Connection app = DatabaseRoles.application()) {
                // One effect, counted in the table, never inferred from responses: one
                // entry for the proposal's approval scope, one posted-audit, one event.
                assertThat(entriesForScope(app, "ledger.adjust.approve:" + proposal))
                        .isEqualTo(1);
                assertThat(auditRowsFor(app, "ledger.AdjustmentPosted", entry.toString()))
                        .isEqualTo(1);
                assertThat(eventRowsFor(app, entry)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the initiator withdraws their own proposal - and a decided proposal"
            + " refuses every further decision (INV-LIFE-04)")
    void aRejectionConvergesAndClosesTheProposal() throws Exception {
        Operator initiator = givenAnOperator();
        Operator approver = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        // Withdrawal is the initiator's own act, deliberately: removing an action needs no
        // second person - INV-AUD-04's clause governs the APPROVAL.
        UUID withdrawn =
                proposalIdOf(
                        post(body(wallet, "3.00", "withdrawal probe"), initiator.token(),
                                "adj-" + IDS.next()));
        assertThat(reject(withdrawn.toString(), initiator.token()).statusCode())
                .isEqualTo(204);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(proposalColumn(app, withdrawn, "status")).isEqualTo("REJECTED");
            assertThat(proposalColumn(app, withdrawn, "decided_by"))
                    .isEqualTo(initiator.identity().value().toString());
            assertThat(auditRowsFor(app, "ledger.AdjustmentRejected", withdrawn.toString()))
                    .isEqualTo(1);
        }

        // A repeated DELETE converges - and records no second act.
        assertThat(reject(withdrawn.toString(), approver.token()).statusCode())
                .isEqualTo(204);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(auditRowsFor(app, "ledger.AdjustmentRejected", withdrawn.toString()))
                    .isEqualTo(1);
        }

        // A rejected proposal cannot be approved: terminal is terminal.
        HttpResponse<String> lateApproval = approve(withdrawn.toString(), approver.token());
        assertThat(lateApproval.statusCode()).isEqualTo(409);
        assertThat(lateApproval.body()).contains("ledger.ProposalNotOpen");

        // And an APPROVED one cannot be rejected: the correction is a reversal, never an
        // un-decision.
        UUID approved =
                proposalIdOf(
                        post(body(wallet, "3.00", "approved-then-rejected probe"),
                                initiator.token(), "adj-" + IDS.next()));
        assertThat(approve(approved.toString(), approver.token()).statusCode()).isEqualTo(201);
        HttpResponse<String> lateRejection = reject(approved.toString(), approver.token());
        assertThat(lateRejection.statusCode()).isEqualTo(409);
        assertThat(lateRejection.body()).contains("ledger.ProposalNotOpen");
    }

    @Test
    @DisplayName("unknown and malformed proposal identifiers are one 404, on every surface")
    void anUnknownProposalIsOneNotFound() throws Exception {
        Operator operator = givenAnOperator();
        String unknown = IDS.next().toString();
        String malformed = "not-a-proposal";

        for (String id : new String[] {unknown, malformed}) {
            assertThat(get(id, operator.token()).statusCode()).isEqualTo(404);
            assertThat(reject(id, operator.token()).statusCode()).isEqualTo(404);
        }
        // The equality between the causes (P1-TSK-016): unknown and malformed answer
        // byte-identically but for the correlation identifier, so neither is an oracle.
        assertThat(withoutCorrelation(approve(unknown, operator.token()).body()))
                .isEqualTo(withoutCorrelation(approve(malformed, operator.token()).body()));
    }

    @Test
    @DisplayName("a session without the role is refused on every surface, and nothing is"
            + " written (INV-AUD-03's negative)")
    void aSessionWithoutTheRoleIsRefused() throws Exception {
        Operator operator = givenAnOperator();
        IdentityId person = givenAnIdentity();
        String token = givenASessionFor(person);
        LedgerAccount wallet = givenAWallet();
        UUID proposal =
                proposalIdOf(
                        post(body(wallet, "5.00", "role refusal probe"), operator.token(),
                                "adj-" + IDS.next()));

        long entriesBefore = adjustmentEntryCount();
        assertThat(post(body(wallet, "5.00", "attempted without the role"), token,
                        "adj-" + IDS.next())
                        .statusCode())
                .isEqualTo(403);
        assertThat(get(proposal.toString(), token).statusCode()).isEqualTo(403);
        assertThat(approve(proposal.toString(), token).statusCode()).isEqualTo(403);
        assertThat(reject(proposal.toString(), token).statusCode()).isEqualTo(403);

        try (Connection app = DatabaseRoles.application()) {
            assertThat(adjustmentEntryCount()).isEqualTo(entriesBefore);
            assertThat(proposalColumn(app, proposal, "status")).isEqualTo("PROPOSED");
        }
    }

    @Test
    @DisplayName("a missing reason is a 422, and nothing is written (INV-REV-04)")
    void aMissingReasonIs422() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        long proposalsBefore = proposalCount();
        String withoutReason =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"lines\":" + lines(wallet, "5.00") + "}";
        HttpResponse<String> response = post(withoutReason, operator.token(), "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.ValidationFailed");
        assertThat(proposalCount()).isEqualTo(proposalsBefore);
    }

    @Test
    @DisplayName("an unbalanced adjustment is a 422 - and the key survives to carry the"
            + " corrected request (validate before claim)")
    void anUnbalancedAdjustmentLeavesItsKeyUsable() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        String key = "adj-" + IDS.next();

        String unbalanced =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"reason\":\"unbalanced probe\","
                        + "\"lines\":[" + line(clearing(), "DEBIT", "5.00") + ","
                        + line(wallet, "CREDIT", "4.00") + "]}";
        HttpResponse<String> refused = post(unbalanced, operator.token(), key);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("ledger.UnbalancedAdjustment");

        // The refusal never consumed the key: the corrected request under the SAME key is
        // the retry the design promises the operator (P3-TSK-006's validate-then-claim).
        HttpResponse<String> corrected =
                post(body(wallet, "5.00", "corrected probe"), operator.token(), key);
        assertThat(corrected.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("the write path feeds finapp.ledger.posting at the APPROVAL - posted,"
            + " replayed, refused - and a proposal moves no meter, because it writes no"
            + " journal (P3-TSK-020, P3-TSK-021)")
    void theWritePathFeedsThePostingMeter() throws Exception {
        Operator initiator = givenAnOperator();
        Operator approver = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        double posted = postingOutcome("posted");
        double replayed = postingOutcome("replayed");
        double refused = postingOutcome("refused");
        double timed = registry.get("finapp.ledger.posting.latency").timer().count();

        UUID proposal =
                proposalIdOf(
                        post(body(wallet, "5.00", "meter probe INC-1"), initiator.token(),
                                "adj-" + IDS.next()));
        // A proposal is not a journal-write command: the meter's own description stays
        // true, and nothing moved.
        assertThat(postingOutcome("posted")).isEqualTo(posted);
        assertThat(postingOutcome("refused")).isEqualTo(refused);

        assertThat(approve(proposal.toString(), approver.token()).statusCode()).isEqualTo(201);
        assertThat(postingOutcome("posted")).isEqualTo(posted + 1);

        assertThat(approve(proposal.toString(), approver.token()).statusCode()).isEqualTo(201);
        assertThat(postingOutcome("replayed"))
                .as("a converged retry is never posted throughput (P2-TSK-020's discipline)")
                .isEqualTo(replayed + 1);
        assertThat(postingOutcome("posted")).isEqualTo(posted + 1);

        // The refused probe is the invariant's own negative: a self-approval.
        UUID selfProbe =
                proposalIdOf(
                        post(body(wallet, "5.00", "meter refusal probe"), initiator.token(),
                                "adj-" + IDS.next()));
        assertThat(approve(selfProbe.toString(), initiator.token()).statusCode())
                .isEqualTo(409);
        assertThat(postingOutcome("refused")).isEqualTo(refused + 1);

        // Every journal-write command is timed, whatever its outcome.
        assertThat((double) registry.get("finapp.ledger.posting.latency").timer().count())
                .isEqualTo(timed + 3);
    }

    private double postingOutcome(String outcome) {
        return registry.get("finapp.ledger.posting").tag("outcome", outcome).counter().count();
    }

    @Test
    @DisplayName("a retried key replays the original proposal; another operator's replay"
            + " conflicts")
    void aReplayIsTheOriginalAndAStrangersConflicts() throws Exception {
        Operator first = givenAnOperator();
        Operator second = givenAnOperator();
        LedgerAccount wallet = givenAWallet();
        String key = "adj-" + IDS.next();
        String body = body(wallet, "7.00", "replay probe");

        HttpResponse<String> original = post(body, first.token(), key);
        assertThat(original.statusCode()).isEqualTo(201);
        UUID proposal = proposalIdOf(original);

        HttpResponse<String> replay = post(body, first.token(), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(proposalIdOf(replay)).isEqualTo(proposal);
        try (Connection app = DatabaseRoles.application()) {
            assertThat(auditRowsFor(app, "ledger.AdjustmentProposed", proposal.toString()))
                    .isEqualTo(1);
        }

        // The fingerprint binds the ACTOR (ADR-0004's owning principal): a key is not a
        // secret, and a second operator replaying it must conflict, never inherit the
        // first's proposal.
        HttpResponse<String> stranger = post(body, second.token(), key);
        assertThat(stranger.statusCode()).isEqualTo(409);

        // And it binds the REASON (INV-IDEM-03): the justification is what makes an
        // adjustment defensible, so a key reused with a different one is a materially
        // different request - a conflict, never a silent collapse into one record.
        HttpResponse<String> differentReason =
                post(body(wallet, "7.00", "a different justification"), first.token(), key);
        assertThat(differentReason.statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("a line naming an unknown account is a 422 at PROPOSAL time, and nothing"
            + " is written")
    void anUnknownAccountIs422() throws Exception {
        Operator operator = givenAnOperator();

        long proposalsBefore = proposalCount();
        String unknownAccount =
                "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                        + "\"reference\":\"adj-probe\",\"reason\":\"unknown account probe\","
                        + "\"lines\":[" + line(clearing(), "DEBIT", "5.00") + ","
                        + "{\"accountId\":\"" + IDS.next() + "\",\"direction\":\"CREDIT\","
                        + "\"amount\":\"5.00\",\"currency\":\"USD\"}]}";
        HttpResponse<String> response = post(unknownAccount, operator.token(), "adj-" + IDS.next());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("ledger.UnknownAccount");
        assertThat(proposalCount()).isEqualTo(proposalsBefore);
    }

    @Test
    @DisplayName("a keyless proposal is refused before the handler - and the approval"
            + " deliberately needs no key: the machine is the idempotency")
    void aKeylessRequestIs422() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        HttpResponse<String> response = post(body(wallet, "5.00", "keyless probe"),
                operator.token(), null);
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("api.IdempotencyKeyRequired");
        // The approval carrying no key is proven by every approval in this suite: none
        // sends an Idempotency-Key header, and the converged retry is the machine's.
    }

    @Test
    @DisplayName("no request shape is our 500 - every refusal is the caller's 4xx")
    void noBodyShapeIsA500() throws Exception {
        Operator operator = givenAnOperator();
        LedgerAccount wallet = givenAWallet();

        String[] shapes = {
            // amount not a number
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "abc") + "," + line(wallet, "CREDIT", "abc") + "]}",
            // amount not representable at the currency's scale (INV-MON-03: never rounded)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "5.001") + "," + line(wallet, "CREDIT", "5.001")
                    + "]}",
            // zero amount (a line asserting nothing)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "0.00") + "," + line(wallet, "CREDIT", "0.00")
                    + "]}",
            // negative amount (a credit wearing a debit's clothes)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":["
                    + line(clearing(), "DEBIT", "-5.00") + "," + line(wallet, "CREDIT", "-5.00")
                    + "]}",
            // lowercase currency (the boundary's own pattern)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\""
                    + clearing().id().value() + "\",\"direction\":\"DEBIT\","
                    + "\"amount\":\"5.00\",\"currency\":\"usd\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // malformed account identifier
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\"not-a-uuid\","
                    + "\"direction\":\"DEBIT\",\"amount\":\"5.00\",\"currency\":\"USD\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // unknown direction (refused by the enum binding)
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[{\"accountId\":\""
                    + clearing().id().value() + "\",\"direction\":\"SIDEWAYS\","
                    + "\"amount\":\"5.00\",\"currency\":\"USD\"},"
                    + line(wallet, "CREDIT", "5.00") + "]}",
            // no lines at all
            "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\",\"reference\":\"r\","
                    + "\"reason\":\"probe\",\"lines\":[]}",
            // broken JSON
            "{\"postingDate\":",
        };
        for (String shape : shapes) {
            HttpResponse<String> response = post(shape, operator.token(), "adj-" + IDS.next());
            assertThat(response.statusCode())
                    .as("shape %s must be the caller's 4xx, never our 500", shape)
                    .isBetween(400, 499);
        }
    }

    // -----------------------------------------------------------------
    // Fixtures

    private record Operator(IdentityId identity, String token) {}

    private Operator givenAnOperator() throws SQLException {
        IdentityId identity = givenAnIdentity();
        givenTheRole(identity, RoleName.LEDGER_OPERATOR);
        return new Operator(identity, givenASessionFor(identity));
    }

    /** An owned USD wallet account — a legal target for an adjustment's customer side. */
    private LedgerAccount givenAWallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount wallet =
                    accounts.createOrConverge(
                                    app,
                                    LedgerAccount.owned(
                                            IDS,
                                            CLOCK,
                                            AccountType.LIABILITY,
                                            AccountPurpose.CUSTOMER_WALLET,
                                            USD,
                                            IDS.next()))
                            .account();
            app.commit();
            return wallet;
        }
    }

    private LedgerAccount clearing() {
        try (Connection app = DatabaseRoles.application()) {
            return accounts
                    .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, USD)
                    .orElseThrow();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String body(LedgerAccount wallet, String amount, String reason) {
        return "{\"postingDate\":\"2026-09-17\",\"valueDate\":\"2026-09-17\","
                + "\"reference\":\"adj-probe\",\"reason\":\"" + reason + "\","
                + "\"lines\":" + lines(wallet, amount) + "}";
    }

    private String lines(LedgerAccount wallet, String amount) {
        return "[" + line(clearing(), "DEBIT", amount) + "," + line(wallet, "CREDIT", amount)
                + "]";
    }

    private static String line(LedgerAccount account, String direction, String amount) {
        return "{\"accountId\":\"" + account.id().value() + "\",\"direction\":\"" + direction
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    private HttpResponse<String> post(String body, String token, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + "/v1/ledger/adjustments"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String proposal, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(proposalUri(proposal))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The approval deliberately carries no idempotency key: the machine converges. */
    private HttpResponse<String> approve(String proposal, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create(
                                        "http://localhost:" + port + "/v1/ledger/adjustments/"
                                                + proposal + "/approval"))
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> reject(String proposal, String token) throws Exception {
        return http.send(
                HttpRequest.newBuilder(proposalUri(proposal))
                        .header("Authorization", "Bearer " + token)
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI proposalUri(String proposal) {
        return URI.create("http://localhost:" + port + "/v1/ledger/adjustments/" + proposal);
    }

    private static UUID entryIdOf(HttpResponse<String> response) {
        Matcher matcher = ENTRY_ID.matcher(response.body());
        assertThat(matcher.find()).as("the response carries the entry id").isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private static UUID proposalIdOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(201);
        Matcher matcher = PROPOSAL_ID.matcher(response.body());
        assertThat(matcher.find()).as("the response carries the proposal id").isTrue();
        return UUID.fromString(matcher.group(1));
    }

    /** Strips the members that differ per request by design: correlation, and the URI. */
    private static String withoutCorrelation(String body) {
        return body.replaceAll("\"correlationId\"\\s*:\\s*\"[^\"]*\"", "\"correlationId\":\"\"")
                .replaceAll("\"instance\"\\s*:\\s*\"[^\"]*\"", "\"instance\":\"\"");
    }

    // -----------------------------------------------------------------
    // Fixtures shared with DenyByDefaultDatabaseTest's idioms

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Adjusting Operator', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at)"
                            + " VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    "adj" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private void givenTheRole(IdentityId identity, RoleName role) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        } finally {
            actor.close();
            correlation.close();
        }
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext =
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Session session =
                Session.issue(
                        IDS,
                        CLOCK,
                        identity,
                        SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD,
                        SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // -----------------------------------------------------------------
    // Counters - in the tables, never inferred

    private static String entryColumn(Connection app, UUID entry, String column)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT " + column + " FROM ledger.journal_entry WHERE id = ?")) {
            read.setObject(1, entry);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static String proposalColumn(Connection app, UUID proposal, String column)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT " + column + " FROM ledger.adjustment_proposal"
                                + " WHERE id = ?")) {
            read.setObject(1, proposal);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long auditRowsFor(Connection app, String operation, String target)
            throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = ? AND target_id = ?")) {
            count.setString(1, operation);
            count.setString(2, target);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String auditColumnFor(
            Connection app, String operation, String target, String column)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT " + column + " FROM platform.audit_record"
                                + " WHERE operation = ? AND target_id = ?")) {
            read.setString(1, operation);
            read.setString(2, target);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long eventRowsFor(Connection app, UUID entry) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM platform.outbox_event"
                                + " WHERE event_type = 'ledger.JournalEntryPosted'"
                                + " AND aggregate_id = ?")) {
            count.setObject(1, entry);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long entriesForScope(Connection app, String scope) throws SQLException {
        try (PreparedStatement count =
                app.prepareStatement(
                        "SELECT count(*) FROM ledger.journal_entry"
                                + " WHERE idempotency_scope = ?")) {
            count.setString(1, scope);
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long postedMinorOf(Connection app, LedgerAccount account)
            throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT posted_minor FROM ledger.account_balance"
                                + " WHERE ledger_account_id = ?")) {
            read.setObject(1, account.id().value());
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static long adjustmentEntryCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.journal_entry"
                                        + " WHERE entry_type = 'ADJUSTMENT'")) {
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long proposalCount() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count =
                        app.prepareStatement(
                                "SELECT count(*) FROM ledger.adjustment_proposal")) {
            try (ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
