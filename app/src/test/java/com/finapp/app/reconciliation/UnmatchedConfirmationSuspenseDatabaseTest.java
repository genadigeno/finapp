package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.payments.PaymentCreation;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.RailOutcomeObserver;
import com.finapp.payments.SchemeExecutionClaim;
import com.finapp.payments.SchemeExecutionClaimStore;
import com.finapp.payments.SettlementExpectations;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.UnmatchedConfirmation;
import com.finapp.payments.UnmatchedConfirmationStore;
import com.finapp.payments.UnmatchedConfirmations;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 7's unmatched confirmations join suspense (`P8-TSK-020`, ADR-0070 section 2's
 * {@code UNMATCHED_CONFIRMATION} row and point 8, ADR-0069 section 2): over the app's own
 * composition and real HTTP, the value every parking puts into {@code SUSPENSE_UNMATCHED} gets
 * its CREDIT suspense item and the break that owns it in the parking's own transaction
 * ({@code INV-REC-09}), and leaves suspense only by a person's resolution ({@code INV-REC-05}).
 *
 * <ul>
 *   <li><strong>Live</strong>: an unattributed confirmation through the SIGNED instant door is
 *       born with exactly one item - origin {@code UNMATCHED_CONFIRMATION}, keyed by the
 *       parking's id, dated from the parking's entry, no external item, park or position - and
 *       one {@code UNKNOWN_EXTERNAL} owner raised {@code PARKED_ON_RECEIPT}, with one
 *       {@code ReconciliationBreakRaised}; ten fresh-id deliveries racing open nothing twice;
 *       an {@code ATTEMPT_CONCLUDED} and an {@code AMOUNT_MISMATCH} parking name their attempt
 *       ({@code TERMINAL}) and trace over HTTP from the item through its expectation and
 *       operation to the parking's retained statement.
 *   <li><strong>History</strong>: parkings planted as Phase 7 left them - an entry 100 days old,
 *       their own claim, no expectation and no item - are adopted by ten racing backfills (the
 *       platform's and controllers') while live parkings land, each exactly once and exactly as
 *       old as its entry, so an aged gain is admitted where a young one is refused; a parking
 *       payments {@code V023}'s backfill left UNCLAIMED (its execution a credited pay-in already
 *       explains) is owned as the duplicate it is - {@code DUPLICATE_EXTERNAL} raised
 *       {@code EXECUTION_ALREADY_EXPLAINED} - and refused a transfer with nothing written.
 *   <li><strong>Release, proofs, failure</strong>: a four-eyes transfer is the one way out; the
 *       position proof reads nothing unowned and no parking line unknown once history is
 *       adopted; a recorder failing inside the parking takes the parking, its claim, its posting,
 *       its expectation, its item and its owner back together.
 * </ul>
 *
 * <p><strong>Runs in its own container</strong>, as the payout return and cash suites do: it
 * parks on {@code SUSPENSE_UNMATCHED} and plants history the conservation storm's at-rest count
 * and the register rebuild do not expect. Every count is scoped to the rows a case created or
 * read against a baseline the case took itself; {@code suspenseUnowned} alone is asserted zero
 * outright, because an open item without an open owner is never legitimate.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("unmatched confirmations join suspense: owned at birth, adopted once, released only"
        + " by a person (P8-TSK-020)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class UnmatchedConfirmationSuspenseDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final RailId RAIL = SimulatedInstantSchemeAdapter.RAIL.id();
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final byte[] WEBHOOK_KEY =
            "an-instant-webhook-key-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final String BASE = "/v1/operator/reconciliation";
    private static final int RACERS = 10;
    private static final int HISTORIC_AGE_DAYS = 100;

    /** ADR-0070 section 2's spelling, restated here rather than read off the production key. */
    private static final String PARKING_KEY = "unmatched-confirmation:";

    private static final String BREAK_RAISED = "reconciliation.ReconciliationBreakRaised";
    private static final String BREAK_RESOLVED = "reconciliation.BreakResolved";
    private static final String PARKED_AUDIT = "payments.UnmatchedConfirmationParked";
    private static final String RECORDER_FAILURE = "the recorder failed inside the parking";

    private static final String ITEM =
            "SELECT id, break_id, origin, origin_ref, side, amount_minor, currency,"
                    + " released_minor, status, opened_on, entry_id, external_item_id, park_id,"
                    + " position_account_id FROM reconciliation.suspense_item";

    private static final String OWNER =
            "SELECT id, type, cause, status, suspense_item_id, expectation_id, external_item_id,"
                    + " value_at_issue_minor, currency, internal_classification,"
                    + " internal_operation_ref, internal_state FROM reconciliation.break";

    private static final String GAIN =
            "{\"kind\":\"RECOGNISE_GAIN\",\"reasonCode\":\"UNATTRIBUTABLE_AGED\","
                    + "\"narrative\":\"no owner came forward within the pinned minimum age\"}";

    /** Every record one parking writes, and a rolled-back parking writes none of. */
    private static final List<String> RECORDS =
            List.of("parking rows", "execution claims", "parking entries", "posting claims",
                    "UNMATCHED_CONFIRMATION expectations", "scheme keys", "suspense items",
                    "owning breaks", "raised events", "parked audits");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private PositionProof positionProof;
    @Autowired private OpeningPosition openingPosition;
    @Autowired private PostingService postingService;
    @Autowired private UnmatchedConfirmationStore<Connection> unmatchedConfirmationStore;
    @Autowired private SchemeExecutionClaimStore<Connection> schemeExecutionClaimStore;
    @Autowired private UnmatchedConfirmations unmatchedConfirmations;
    @Autowired private ReconciliationExpectationRecorder recorder;
    @Autowired private PaymentRails paymentRails;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private IdGenerator idGenerator;
    @Autowired private Clock clock;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        // One stub server, every provider (the PayByBankDatabaseTest wiring): the instant
        // scheme exchanges the bank account's grant and takes the initiations, and its
        // confirmations arrive through the SIGNED door under this suite's key.
        registry.add("finapp.paymentmethods.tokenisation.url", () -> provider.baseUrl());
        registry.add("finapp.paymentmethods.tokenisation.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.provider.url", () -> provider.baseUrl());
        registry.add("finapp.payments.provider.timeout", () -> "PT0.7S");
        registry.add("finapp.payments.instant.url", () -> provider.baseUrl());
        registry.add("finapp.payments.instant.timeout", () -> "PT0.7S");
        registry.add(
                "finapp.payments.instant.webhook.key",
                () -> Base64.getEncoder().encodeToString(WEBHOOK_KEY));
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    // ----------------------------------------------------------------- live parkings

    @Test
    @Order(1)
    @DisplayName("a live unattributed confirmation through the signed door is born with ONE"
            + " CREDIT suspense item - origin UNMATCHED_CONFIRMATION, keyed by the parking, dated"
            + " from its entry - and ONE UNKNOWN_EXTERNAL / PARKED_ON_RECEIPT owner and one"
            + " BreakRaised; nothing unowned, no parking line unknown")
    void aLiveUnattributedParkingIsBornWithItsItemAndOwner() throws Exception {
        PositionProof.Report before = proofs();
        long raisedBefore = raisedEvents();
        String scheme = scheme();

        assertThat(callback(orphanStatement(scheme, letters(), "7.50"))).isEqualTo(204);

        ParkingRow parking = parkingOf(scheme);
        assertThat(parking.amountMinor()).as("the parked amount").isEqualTo(7_50);
        assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN", null,
                "UNATTRIBUTED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE entry_id = ?",
                        parking.entry()))
                .as("the parking entry's value is owned by one item, whatever its origin_ref")
                .isEqualTo(1);
        assertThat(raisedEvents() - raisedBefore)
                .as("the parking raised exactly one break, and nothing else raised any")
                .isEqualTo(1);

        PositionProof.Report after = proofs();
        assertThat(after.suspenseUnowned()).as("no open item without an open owner").isZero();
        assertThat(unknownLines(after, AccountPurpose.SUSPENSE_UNMATCHED))
                .as("the parking's suspense line is known the moment it posts")
                .isEqualTo(unknownLines(before, AccountPurpose.SUSPENSE_UNMATCHED));
        assertThat(unknownLines(after, AccountPurpose.INSTANT_CLEARING))
                .as("and its clearing line, through its expectation")
                .isEqualTo(unknownLines(before, AccountPurpose.INSTANT_CLEARING));
        assertThat(suspense(after).unadoptedParkings())
                .as("a live parking is never Phase 7's unadopted term")
                .isEqualTo(suspense(before).unadoptedParkings());
        assertThat(suspenseGap(after))
                .as("the suspense identity moved by nothing: the item is the CREDIT the entry"
                        + " posted (ADR-0070 section 7)")
                .isEqualTo(suspenseGap(before));
    }

    @Test
    @Order(2)
    @DisplayName("ten fresh-id deliveries of ONE confirmation racing: one parking, one item, one"
            + " owner and one BreakRaised - the claim's winner alone opens them (counted)")
    void tenFreshIdDeliveriesOfOneConfirmationOpenOneOwner() throws Exception {
        String scheme = scheme();
        String named = letters();
        long raisedBefore = raisedEvents();

        List<Integer> answers =
                race(RACERS,
                        () -> deliverUntilAcknowledged(orphanStatement(scheme, named, "5.40")));

        assertThat(answers).as("every racer acknowledged - never a 5xx").containsOnly(204);
        ParkingRow parking = parkingOf(scheme);
        Owner owner = assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN",
                null, "UNATTRIBUTED");
        assertThat(parkingRecords(scheme, parking.id(), owner.breakId()))
                .as("ten deliveries, one of every record the winner writes")
                .isEqualTo(records(1));
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE entry_id = ?",
                        parking.entry()))
                .isEqualTo(1);
        assertThat(raisedEvents() - raisedBefore)
                .as("one BreakRaised under ten racing deliveries")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM payments.provider_evidence"
                        + " WHERE unmatched_confirmation_id = ?", parking.id()))
                .as("every delivery's bytes rest addressed to the one parking")
                .isEqualTo(RACERS);
    }

    @Test
    @Order(3)
    @DisplayName("an ATTEMPT_CONCLUDED parking's owner names the failed attempt - TERMINAL,"
            + " internal_state ATTEMPT_CONCLUDED - and its trace over HTTP walks the item"
            + " EXPECTED_AS its expectation, to the operation and the parking's own statement")
    void anAttemptConcludedParkingNamesItsAttemptAndTracesToItsStatement() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates();
        String failed = attemptIdOf(field(confirmedPayment(f, "8.00").body(), "id"));
        assertThat(callback(word(referenceOf(failed), "expired"))).isEqualTo(204);
        assertThat(one("SELECT status FROM payments.payment_attempt WHERE id = ?",
                        UUID.fromString(failed)))
                .isEqualTo("FAILED");
        String late = scheme();
        assertThat(executedCallback(referenceOf(failed), late, "C3", "8.00")).isEqualTo(204);

        ParkingRow parking = parkingOf(late);
        assertThat(one("SELECT cause || '|' || attempt_id::text FROM"
                        + " payments.unmatched_confirmation WHERE id = ?", parking.id()))
                .isEqualTo("ATTEMPT_CONCLUDED|" + failed);
        Owner owner = assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "TERMINAL",
                failed, "ATTEMPT_CONCLUDED");
        assertTracesToItsStatement(parking, owner);
    }

    @Test
    @Order(4)
    @DisplayName("an AMOUNT_MISMATCH parking's owner names the waiting attempt it failed -"
            + " TERMINAL, internal_state AMOUNT_MISMATCH, the EXECUTED amount - and its trace"
            + " over HTTP walks the item EXPECTED_AS its expectation to the parking's statement")
    void anAmountMismatchParkingNamesItsAttemptAndTracesToItsStatement() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates();
        String mismatched = attemptIdOf(field(confirmedPayment(f, "5.00").body(), "id"));
        String executed = scheme();
        assertThat(executedCallback(referenceOf(mismatched), executed, "C1", "4.99"))
                .isEqualTo(204);
        assertThat(one("SELECT status || '|' || failure_reason FROM payments.payment_attempt"
                        + " WHERE id = ?", UUID.fromString(mismatched)))
                .isEqualTo("FAILED|DECLINED");

        ParkingRow parking = parkingOf(executed);
        assertThat(parking.amountMinor()).as("the value the scheme executed").isEqualTo(4_99);
        assertThat(one("SELECT cause || '|' || attempt_id::text FROM"
                        + " payments.unmatched_confirmation WHERE id = ?", parking.id()))
                .isEqualTo("AMOUNT_MISMATCH|" + mismatched);
        Owner owner = assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "TERMINAL",
                mismatched, "AMOUNT_MISMATCH");
        assertTracesToItsStatement(parking, owner);
    }

    // ----------------------------------------------------------------- history adopted

    @Test
    @Order(5)
    @DisplayName("parkings history left - an entry 100 days old, their own claim, no item - are"
            + " adopted by TEN racing backfills (the platform and controllers) while live"
            + " parkings land: each owned exactly once, dated from its entry and never today, so"
            + " an aged gain is admitted where a young one is refused (counted)")
    void historicParkingsAreAdoptedOnceUnderTenRacingBackfills() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate old = today.minusDays(HISTORIC_AGE_DAYS);
        List<ParkingRow> history = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            history.add(historicParking(scheme(), 4_10 + i, old, true));
        }
        for (ParkingRow planted : history) {
            assertThat(count("SELECT count(*) FROM reconciliation.suspense_item"
                            + " WHERE origin_ref = ?", planted.id().toString()))
                    .as("history left no item")
                    .isZero();
            assertThat(ClearingLineCopies.expectationsOf(
                            ExpectationKind.UNMATCHED_CONFIRMATION, operation(planted.scheme())))
                    .as("nor an expectation: the pre-Phase-8 world")
                    .isZero();
        }
        List<String> live = List.of(scheme(), scheme(), scheme());
        List<Session> controllers = new ArrayList<>();
        for (int i = 0; i < RACERS / 2; i++) {
            controllers.add(sessionWith(RoleName.RECONCILIATION_CONTROLLER));
        }
        // What the backfill can adopt: every posted parking no item owns - this case's three
        // in its own container, and anything unowned a co-located suite left beside them.
        long adoptable = adoptableParkings();
        assertThat(adoptable).isGreaterThanOrEqualTo(history.size());
        long raisedBefore = raisedEvents();

        List<Callable<Integer>> racers = new ArrayList<>();
        for (int i = 0; i < RACERS; i++) {
            if (i % 2 == 0) {
                Session controller = controllers.get(i / 2);
                racers.add(() -> backfillOverHttp(controller.token(),
                                "adopting the parkings history left")
                        .statusCode());
            } else {
                racers.add(() -> {
                    backfill("adopting the parkings history left");
                    return 200;
                });
            }
        }
        for (String scheme : live) {
            racers.add(() -> deliverUntilAcknowledged(orphanStatement(scheme, letters(), "2.75")));
        }
        List<Integer> answers = race(racers);

        assertThat(answers.subList(0, RACERS))
                .as("every backfill recorded its run, whoever ran it")
                .containsOnly(200);
        assertThat(answers.subList(RACERS, answers.size()))
                .as("every live delivery acknowledged")
                .containsOnly(204);
        for (ParkingRow planted : history) {
            assertOwned(planted, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN", null,
                    "UNATTRIBUTED");
            assertThat(LocalDate.parse(
                            (String) one("SELECT opened_on::text FROM reconciliation.suspense_item"
                                    + " WHERE origin_ref = ?", planted.id().toString())))
                    .as("an adopted parking is exactly as old as its entry, never as young as its"
                            + " adoption (ADR-0070 section 2)")
                    .isEqualTo(old)
                    .isBefore(today);
            ClearingLineCopies.assertOpensItsClearingLinesCopy(
                    ExpectationKind.UNMATCHED_CONFIRMATION,
                    operation(planted.scheme()),
                    PARKING_KEY + operation(planted.scheme()),
                    ExpectationDirection.INBOUND);
        }
        List<Owner> liveOwners = new ArrayList<>();
        for (String scheme : live) {
            liveOwners.add(assertOwned(parkingOf(scheme), "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT",
                    "UNKNOWN", null, "UNATTRIBUTED"));
        }
        assertThat(raisedEvents() - raisedBefore)
                .as("one raise per parking - each adoptable one and each live one - under ten"
                        + " backfills")
                .isEqualTo(adoptable + live.size());
        assertThat(adoptableParkings()).as("nothing posted is left unowned").isZero();

        // THE AGE, judged on the item's opened_on: a young parking's gain is refused, an adopted
        // one's admitted and approved by a second person.
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Owner young = liveOwners.get(0);
        HttpResponse<String> refused = desk(proposer.token(),
                "/breaks/" + young.breakId() + "/resolutions", key(), GAIN);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("reconciliation.GainNotYetEligible");
        assertThat(resolutionsOf(young.breakId())).as("a refusal writes nothing").isZero();
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", young.breakId()))
                .isEqualTo("OPEN");

        ParkingRow agedParking = history.get(0);
        Owner aged = ownerOf(agedParking);
        HttpResponse<String> proposed = desk(proposer.token(),
                "/breaks/" + aged.breakId() + "/resolutions", key(), GAIN);
        assertThat(proposed.statusCode()).as("not refused for age: %s", proposed.body())
                .isEqualTo(201);
        assertThat(proposed.body())
                .contains("\"status\":\"PROPOSED\"")
                .contains("\"fourEyes\":true");
        HttpResponse<String> approved = desk(approver.token(),
                "/resolutions/" + field(proposed.body(), "resolutionId") + "/approval", null,
                null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        UUID entry = UUID.fromString(field(approved.body(), "journalEntryId"));
        assertThat(entryLines(entry))
                .isEqualTo("CREDIT:RECONCILIATION_GAINS,DEBIT:SUSPENSE_UNMATCHED");
        assertThat(row("SELECT status, released_minor FROM reconciliation.suspense_item"
                        + " WHERE id = ?", aged.item()))
                .containsEntry("status", "RELEASED")
                .containsEntry("released_minor", Long.toString(agedParking.amountMinor()));
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", aged.breakId()))
                .isEqualTo("RESOLVED");
        assertTrialBalance();
    }

    @Test
    @Order(6)
    @DisplayName("a parking payments V023 left UNCLAIMED - its execution a credited pay-in"
            + " already explains - is owned as the duplicate it is: DUPLICATE_EXTERNAL /"
            + " EXECUTION_ALREADY_EXPLAINED, COMPLETED, naming the pay-in's claim; a transfer is"
            + " refused with nothing written and an aged gain admitted (ADR-0070 point 8)")
    void anUnclaimedParkingIsOwnedAsTheDuplicateItIs() throws Exception {
        Fixture f = bankFixture();
        schemeInitiates();
        String attemptId = attemptIdOf(field(confirmedPayment(f, "3.00").body(), "id"));
        String credited = scheme();
        assertThat(executedCallback(referenceOf(attemptId), credited, "C7", "3.00"))
                .isEqualTo(204);
        String claimSql = "SELECT subject_kind || ':' || subject_id::text FROM"
                + " payments.scheme_execution_claim WHERE rail = ? AND scheme_reference = ?";
        String claim = (String) one(claimSql, RAIL.value(), credited);
        assertThat(claim).as("the credited pay-in holds the execution").isEqualTo("PAY_IN:"
                + attemptId);

        // History's defect, planted: the same execution parked beside its credit, with its own
        // posting, and no claim of its own - V023's backfill gave the claim to the credit.
        ParkingRow unclaimed = historicParking(credited, 3_00,
                LocalDate.now(ZoneOffset.UTC).minusDays(HISTORIC_AGE_DAYS), false);
        assertThat(one(claimSql, RAIL.value(), credited))
                .as("the claim stays the pay-in's")
                .isEqualTo(claim);

        backfill("adopting a parking V023 left unclaimed");

        Owner owner = assertOwned(unclaimed, "DUPLICATE_EXTERNAL", "EXECUTION_ALREADY_EXPLAINED",
                "COMPLETED", claim, "UNATTRIBUTED");

        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        UUID wallet = openWallet();
        long proposalsBefore = count("SELECT count(*) FROM ledger.adjustment_proposal");
        String transferKey = key();
        HttpResponse<String> transfer = desk(proposer.token(),
                "/breaks/" + owner.breakId() + "/resolutions", transferKey, transferTo(wallet));
        assertThat(transfer.statusCode())
                .as("value already attributed once is never transferred again: %s",
                        transfer.body())
                .isEqualTo(422);
        assertThat(transfer.body()).contains("reconciliation.ResolutionKindNotAllowed");
        assertThat(resolutionsOf(owner.breakId())).as("nothing written").isZero();
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", owner.breakId()))
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM platform.idempotency_record"
                        + " WHERE idempotency_key = ?", transferKey))
                .as("the refusal rolled the key's claim back with it")
                .isZero();
        assertThat(count("SELECT count(*) FROM ledger.adjustment_proposal"))
                .as("no ledger proposal recorded")
                .isEqualTo(proposalsBefore);
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE ledger_account_id = ?",
                        wallet))
                .isZero();

        HttpResponse<String> gain = desk(proposer.token(),
                "/breaks/" + owner.breakId() + "/resolutions", key(), GAIN);
        assertThat(gain.statusCode()).as(gain.body()).isEqualTo(201);
        assertThat(gain.body())
                .contains("\"kind\":\"RECOGNISE_GAIN\"")
                .contains("\"status\":\"PROPOSED\"");
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", owner.breakId()))
                .isEqualTo("RESOLUTION_PROPOSED");
    }

    // ----------------------------------------------------------------- release and proofs

    @Test
    @Order(7)
    @DisplayName("a live parking leaves suspense only by a person's resolution: a four-eyes"
            + " TRANSFER_TO_ACCOUNT to an ACTIVE wallet - self-approval refused, a second"
            + " operator approving - releases the item whole, resolves its owner and credits the"
            + " wallet; a later backfill re-opens nothing; nothing unowned, the identity holding")
    void onlyAFourEyesTransferReleasesALiveParking() throws Exception {
        PositionProof.Report before = proofs();
        String scheme = scheme();
        assertThat(callback(orphanStatement(scheme, letters(), "6.25"))).isEqualTo(204);
        ParkingRow parking = parkingOf(scheme);
        Owner owner = assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN",
                null, "UNATTRIBUTED");

        UUID wallet = openWallet();
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed = desk(proposer.token(),
                "/breaks/" + owner.breakId() + "/resolutions", key(), transferTo(wallet));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        assertThat(proposed.body())
                .contains("\"status\":\"PROPOSED\"")
                .contains("\"fourEyes\":true");
        String resolution = field(proposed.body(), "resolutionId");

        HttpResponse<String> self =
                desk(proposer.token(), "/resolutions/" + resolution + "/approval", null, null);
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.SelfApprovalRefused");
        assertThat(row("SELECT status, released_minor FROM reconciliation.suspense_item"
                        + " WHERE id = ?", owner.item()))
                .as("a proposal releases nothing")
                .containsEntry("status", "OPEN")
                .containsEntry("released_minor", "0");

        HttpResponse<String> approved =
                desk(approver.token(), "/resolutions/" + resolution + "/approval", null, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"APPROVED\"");
        UUID entry = UUID.fromString(field(approved.body(), "journalEntryId"));
        assertThat(entryLines(entry)).isEqualTo("CREDIT:CUSTOMER_WALLET,DEBIT:SUSPENSE_UNMATCHED");
        assertThat(row("SELECT status, released_minor FROM reconciliation.suspense_item"
                        + " WHERE id = ?", owner.item()))
                .as("released whole, by the resolution")
                .containsEntry("status", "RELEASED")
                .containsEntry("released_minor", Long.toString(parking.amountMinor()));
        assertThat(one("SELECT status FROM reconciliation.break WHERE id = ?", owner.breakId()))
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                        + " AND aggregate_id = ?", BREAK_RESOLVED, owner.breakId()))
                .isEqualTo(1);
        assertThat(row("SELECT direction, amount_minor, entry_id FROM ledger.journal_line"
                        + " WHERE ledger_account_id = ?", wallet))
                .as("the wallet credited once, by the approved entry")
                .containsEntry("direction", "CREDIT")
                .containsEntry("amount_minor", Long.toString(parking.amountMinor()))
                .containsEntry("entry_id", entry.toString());

        // A later rebuild converges on the released item: it never re-opens released value.
        backfill("a rebuild after the release");
        assertThat(row("SELECT count(*) AS items, min(status) AS status FROM"
                        + " reconciliation.suspense_item WHERE origin_ref = ?",
                        parking.id().toString()))
                .containsEntry("items", "1")
                .containsEntry("status", "RELEASED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE suspense_item_id = ?",
                        owner.item()))
                .isEqualTo(1);

        PositionProof.Report after = proofs();
        assertThat(after.suspenseUnowned()).isZero();
        assertThat(suspense(after).explained())
                .as("the suspense identity holds: CR-DR = CREDIT - DEBIT remainders + unadopted")
                .isTrue();
        assertThat(suspenseGap(after)).isEqualTo(suspenseGap(before));
        assertThat(unknownLines(after, AccountPurpose.SUSPENSE_UNMATCHED))
                .as("the parking's and the resolution's suspense lines are both known")
                .isEqualTo(unknownLines(before, AccountPurpose.SUSPENSE_UNMATCHED));
        assertTrialBalance();
    }

    @Test
    @Order(8)
    @DisplayName("the proofs once history is adopted: planted parkings read as Phase 7's named"
            + " unadopted term and unknown lines, and after one backfill NOTHING is unowned, no"
            + " parking line unknown, the unadopted term back to its baseline and"
            + " INSTANT_CLEARING's identity unmoved")
    void theProofsReadNothingUnownedOnceHistoryIsAdopted() throws Exception {
        PositionProof.Report baseline = proofs();
        LocalDate old = LocalDate.now(ZoneOffset.UTC).minusDays(HISTORIC_AGE_DAYS);
        ParkingRow first = historicParking(scheme(), 3_33, old, true);
        ParkingRow second = historicParking(scheme(), 1_01, old, true);
        Money planted = Money.ofMinorUnits(3_33 + 1_01, USD);

        PositionProof.Report unadopted = proofs();
        assertThat(unknownLines(unadopted, AccountPurpose.SUSPENSE_UNMATCHED))
                .as("each planted entry's suspense line is unknown until adopted")
                .isEqualTo(unknownLines(baseline, AccountPurpose.SUSPENSE_UNMATCHED) + 2);
        assertThat(unknownLines(unadopted, AccountPurpose.INSTANT_CLEARING))
                .isEqualTo(unknownLines(baseline, AccountPurpose.INSTANT_CLEARING) + 2);
        assertThat(suspense(unadopted).unadoptedParkings())
                .isEqualTo(suspense(baseline).unadoptedParkings().plus(planted));
        assertThat(suspenseGap(unadopted))
                .as("the named term keeps the identity exact before the adoption")
                .isEqualTo(suspenseGap(baseline));

        backfill("adopting the planted parkings");

        PositionProof.Report adopted = proofs();
        assertThat(adopted.suspenseUnowned()).as("nothing unowned").isZero();
        assertThat(unknownLines(adopted, AccountPurpose.SUSPENSE_UNMATCHED))
                .as("no parking line unknown: zero relative to the case's baseline")
                .isEqualTo(unknownLines(baseline, AccountPurpose.SUSPENSE_UNMATCHED));
        assertThat(unknownLines(adopted, AccountPurpose.INSTANT_CLEARING))
                .isEqualTo(unknownLines(baseline, AccountPurpose.INSTANT_CLEARING));
        assertThat(suspense(adopted).unadoptedParkings())
                .as("the unadopted term back where the case found it")
                .isEqualTo(suspense(baseline).unadoptedParkings());
        assertThat(suspense(adopted).creditRemainders())
                .as("the planted value is now CREDIT remainders, gross")
                .isEqualTo(suspense(baseline).creditRemainders().plus(planted));
        assertThat(suspenseGap(adopted)).isEqualTo(suspenseGap(baseline));
        assertThat(instantGap(adopted))
                .as("INSTANT_CLEARING's identity: the planted debits now explained")
                .isEqualTo(instantGap(baseline));
        assertThat(adopted.oldestSuspenseOpenedOn())
                .hasValueSatisfying(oldest -> assertThat(oldest).isBeforeOrEqualTo(old));
        assertOwned(first, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN", null,
                "UNATTRIBUTED");
        assertOwned(second, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN", null,
                "UNATTRIBUTED");
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- one transaction

    @Test
    @Order(9)
    @DisplayName("the recorder failing inside the parking rolls the delivery back whole: the"
            + " parking row, its claim, its posting and posting claim, its expectation, its item"
            + " and its owner - each seen on the parking's own connection before the failure -"
            + " are gone after it; the real bean then parks the same statement once")
    void aRecorderFailingInsideTheParkingRollsTheDeliveryBackWhole() throws Exception {
        String scheme = scheme();
        long minor = 9_15;
        AtomicReference<UUID> doomedParking = new AtomicReference<>();
        AtomicReference<UUID> doomedOwner = new AtomicReference<>();
        AtomicLong parkingRowsSeen = new AtomicLong(-1);
        AtomicLong expectationsSeen = new AtomicLong(-1);
        AtomicLong itemsSeen = new AtomicLong(-1);
        AtomicLong ownersSeen = new AtomicLong(-1);
        SettlementExpectations failing =
                new SettlementExpectations() {
                    @Override
                    public void open(
                            Connection unitOfWork, SettlementExpectations.Opening opening) {
                        recorder.open(unitOfWork, opening);
                    }

                    @Override
                    public void alias(
                            Connection unitOfWork,
                            SettlementExpectations.AliasRegistration registration) {
                        recorder.alias(unitOfWork, registration);
                    }

                    @Override
                    public void parked(
                            Connection unitOfWork, SettlementExpectations.ParkedValue parked) {
                        doomedParking.set(parked.parkingId());
                        parkingRowsSeen.set(countOn(unitOfWork,
                                "SELECT count(*) FROM payments.unmatched_confirmation"
                                        + " WHERE id = ?", parked.parkingId()));
                        expectationsSeen.set(countOn(unitOfWork,
                                "SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                                        + " 'UNMATCHED_CONFIRMATION' AND journal_entry_id = ?",
                                parked.journalEntryId().value()));
                        // The real owner is written too, so its absence after the rollback is
                        // a finding, not a vacuous truth.
                        recorder.parked(unitOfWork, parked);
                        itemsSeen.set(countOn(unitOfWork,
                                "SELECT count(*) FROM reconciliation.suspense_item"
                                        + " WHERE origin_ref = ?", parked.parkingId().toString()));
                        doomedOwner.set(uuidOn(unitOfWork,
                                "SELECT break_id FROM reconciliation.suspense_item"
                                        + " WHERE origin_ref = ?", parked.parkingId().toString()));
                        ownersSeen.set(countOn(unitOfWork,
                                "SELECT count(*) FROM reconciliation.break WHERE id = ?",
                                doomedOwner.get()));
                        throw new IllegalStateException(RECORDER_FAILURE);
                    }
                };
        UnmatchedConfirmations engine =
                new UnmatchedConfirmations(
                        unmatchedConfirmationStore,
                        paymentRails,
                        new ChartOfAccounts<>(ledgerAccountStore),
                        postingService,
                        auditWriter,
                        idGenerator,
                        clock,
                        schemeExecutionClaimStore,
                        RailOutcomeObserver.NONE,
                        failing);

        assertThatThrownBy(() -> asPlatform(uow -> engine.park(uow, statement(scheme, minor))))
                .as("the recorder's failure propagates out of the parking")
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(RECORDER_FAILURE);
        assertThat(new long[] {
                        parkingRowsSeen.get(), expectationsSeen.get(), itemsSeen.get(),
                        ownersSeen.get()})
                .as("inside the doomed transaction the parking row, its expectation, its item"
                        + " and its owner all stood")
                .containsExactly(1L, 1L, 1L, 1L);
        assertThat(parkingRecords(scheme, doomedParking.get(), doomedOwner.get()))
                .as("one transaction: the failure took every record with it")
                .isEqualTo(records(0));
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE id = ?",
                        doomedParking.get()))
                .isZero();

        UnmatchedConfirmations.Parked parked =
                asPlatform(uow -> unmatchedConfirmations.park(uow, statement(scheme, minor)));
        assertThat(parked.acting()).as("the same statement parks afresh").isTrue();
        UUID landed = parked.parking().orElseThrow();
        assertThat(landed).isNotEqualTo(doomedParking.get());
        ParkingRow parking = parkingOf(scheme);
        assertThat(parking.id()).isEqualTo(landed);
        Owner owner = assertOwned(parking, "UNKNOWN_EXTERNAL", "PARKED_ON_RECEIPT", "UNKNOWN",
                null, "UNATTRIBUTED");
        assertThat(parkingRecords(scheme, landed, owner.breakId()))
                .as("the real bean's parking: every record exactly once")
                .isEqualTo(records(1));
    }

    // ----------------------------------------------------------------- parkings and owners

    /** A parking as its row and entry hold it. */
    private record ParkingRow(
            String scheme, UUID id, UUID entry, LocalDate postedOn, long amountMinor) {}

    /** A parking's suspense item and the break owning it. */
    private record Owner(UUID item, UUID breakId) {}

    private static ParkingRow parkingOf(String scheme) throws SQLException {
        assertThat(count("SELECT count(*) FROM payments.unmatched_confirmation WHERE rail = ?"
                        + " AND scheme_reference = ?", RAIL.value(), scheme))
                .as("one parking for %s", scheme)
                .isEqualTo(1);
        Map<String, String> found =
                row("SELECT u.id, u.entry_ref, e.posting_date, u.amount_minor"
                                + " FROM payments.unmatched_confirmation u"
                                + " JOIN ledger.journal_entry e ON e.id = u.entry_ref"
                                + " WHERE u.rail = ? AND u.scheme_reference = ?",
                        RAIL.value(), scheme);
        return new ParkingRow(
                scheme,
                UUID.fromString(found.get("id")),
                UUID.fromString(found.get("entry_ref")),
                LocalDate.parse(found.get("posting_date")),
                Long.parseLong(found.get("amount_minor")));
    }

    private static Owner ownerOf(ParkingRow parking) throws SQLException {
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE origin_ref = ?",
                        parking.id().toString()))
                .as("exactly one suspense item owns parking %s (%s) by its origin_ref",
                        parking.id(), parking.scheme())
                .isEqualTo(1);
        Map<String, String> item =
                row("SELECT id, break_id FROM reconciliation.suspense_item WHERE origin_ref = ?",
                        parking.id().toString());
        return new Owner(UUID.fromString(item.get("id")), UUID.fromString(item.get("break_id")));
    }

    /**
     * The parking's item and its one owning break, field by field: the item the CREDIT its entry
     * put into suspense, the break standing on the item with the parking's stored knowledge
     * frozen on it, and one {@code ReconciliationBreakRaised}.
     */
    private static Owner assertOwned(
            ParkingRow parking,
            String type,
            String cause,
            String classification,
            String operationRef,
            String state)
            throws SQLException {
        Owner owner = ownerOf(parking);
        String amount = Long.toString(parking.amountMinor());
        assertThat(row(ITEM + " WHERE id = ?", owner.item()))
                .as("the suspense item of parking %s", parking.scheme())
                .containsEntry("break_id", owner.breakId().toString())
                .containsEntry("origin", "UNMATCHED_CONFIRMATION")
                .containsEntry("origin_ref", parking.id().toString())
                .containsEntry("side", "CREDIT")
                .containsEntry("amount_minor", amount)
                .containsEntry("currency", "USD")
                .containsEntry("released_minor", "0")
                .containsEntry("status", "OPEN")
                .containsEntry("opened_on", parking.postedOn().toString())
                .containsEntry("entry_id", parking.entry().toString())
                .containsEntry("external_item_id", null)
                .containsEntry("park_id", null)
                .containsEntry("position_account_id", null);
        assertThat(row(OWNER + " WHERE suspense_item_id = ?", owner.item()))
                .as("the one break owning parking %s's item", parking.scheme())
                .containsEntry("id", owner.breakId().toString())
                .containsEntry("type", type)
                .containsEntry("cause", cause)
                .containsEntry("status", "OPEN")
                .containsEntry("expectation_id", null)
                .containsEntry("external_item_id", null)
                .containsEntry("value_at_issue_minor", amount)
                .containsEntry("currency", "USD")
                .containsEntry("internal_classification", classification)
                .containsEntry("internal_operation_ref", operationRef)
                .containsEntry("internal_state", state);
        assertThat(raisedFor(owner))
                .as("one ReconciliationBreakRaised for parking %s's owner", parking.scheme())
                .isEqualTo(1);
        return owner;
    }

    /**
     * The owner's trace over HTTP: break -> its item -> the parking's entry and, EXPECTED_AS, the
     * expectation the entry's clearing line opened -> the operation -> the parking's statement,
     * found through payments' fifth evidence subject by stored identifier.
     */
    private void assertTracesToItsStatement(ParkingRow parking, Owner owner) throws Exception {
        String operation = "UNMATCHED_CONFIRMATION:" + operation(parking.scheme());
        UUID expectation = (UUID) one("SELECT id FROM reconciliation.expectation WHERE kind ="
                + " 'UNMATCHED_CONFIRMATION' AND journal_entry_id = ?", parking.entry());
        assertThat(expectation).as("the parking entry's expectation").isNotNull();
        assertThat(count("SELECT count(*) FROM payments.provider_evidence"
                        + " WHERE unmatched_confirmation_id = ?", parking.id()))
                .as("the delivery that parked value rests addressed to its parking (payments"
                        + " V023's fifth evidence subject)")
                .isEqualTo(1);
        UUID statement = (UUID) one("SELECT id FROM payments.provider_evidence"
                + " WHERE unmatched_confirmation_id = ?", parking.id());

        HttpResponse<String> trace = deskGet(sessionWith(RoleName.RECONCILIATION_OPERATOR).token(),
                "/breaks/" + owner.breakId() + "/trace");
        assertThat(trace.statusCode()).as(trace.body()).isEqualTo(200);
        String body = trace.body();
        assertStep(body, "BREAK", owner.breakId(), "SUBJECT", "SUSPENSE_ITEM", owner.item());
        assertStep(body, "SUSPENSE_ITEM", owner.item(), "POSTED_AS", "JOURNAL_ENTRY",
                parking.entry());
        assertStep(body, "SUSPENSE_ITEM", owner.item(), "EXPECTED_AS", "EXPECTATION",
                expectation);
        assertStep(body, "EXPECTATION", expectation, "TRACKS", "OPERATION", operation);
        assertStep(body, "OPERATION", operation, "EVIDENCED_BY", "PROVIDER_EVIDENCE", statement);
    }

    /** One step of a rendered trace, its five fields in any order. */
    private static void assertStep(
            String body, String fromKind, Object fromId, String relation, String toKind,
            Object toId) {
        Matcher steps = Pattern.compile("\\{[^{}]*\\}").matcher(body);
        boolean found = false;
        while (steps.find() && !found) {
            String step = steps.group();
            found = step.contains("\"fromKind\":\"" + fromKind + "\"")
                    && step.contains("\"fromId\":\"" + fromId + "\"")
                    && step.contains("\"relation\":\"" + relation + "\"")
                    && step.contains("\"toKind\":\"" + toKind + "\"")
                    && step.contains("\"toId\":\"" + toId + "\"");
        }
        assertThat(found)
                .as("the trace holds %s %s -%s-> %s %s: %s", fromKind, fromId, relation, toKind,
                        toId, body)
                .isTrue();
    }

    /**
     * A parking as Phase 7 left it: its posting DR the rail's clearing / CR suspense under the
     * parking's own key, dated {@code postedOn}; its row naming that entry; its own claim when
     * {@code claimed} (payments {@code V023}'s backfill gave every execution one, but a credit's
     * before a parking's) - and no expectation, item or break.
     */
    private ParkingRow historicParking(
            String scheme, long minor, LocalDate postedOn, boolean claimed) throws SQLException {
        Money amount = Money.ofMinorUnits(minor, USD);
        String postingKey = PARKING_KEY + operation(scheme);
        return asPlatform(uow -> {
            LedgerAccount clearing =
                    ledgerAccountStore
                            .findOperational(uow, AccountPurpose.INSTANT_CLEARING, USD)
                            .orElseThrow();
            LedgerAccount suspense =
                    ledgerAccountStore
                            .findOperational(uow, AccountPurpose.SUSPENSE_UNMATCHED, USD)
                            .orElseThrow();
            UUID entry =
                    postingService
                            .post(uow, new PostingCommand(postingKey, postedOn, postedOn, scheme,
                                    List.of(new JournalLine(clearing.id(), Direction.DEBIT,
                                                    amount),
                                            new JournalLine(suspense.id(), Direction.CREDIT,
                                                    amount))))
                            .entryId()
                            .value();
            UUID parking = IDS.next();
            Instant receivedAt = postedOn.atStartOfDay(ZoneOffset.UTC).toInstant()
                    .plusSeconds(9 * 3600);
            ProviderReference reference = new ProviderReference(scheme);
            if (!unmatchedConfirmationStore.insert(
                    uow,
                    new UnmatchedConfirmation(
                            parking, RAIL, reference, amount, receivedAt, entry,
                            UnmatchedConfirmation.Attribution.unattributed(
                                    Optional.empty(), Optional.empty())))) {
                throw new IllegalStateException("the planted parking is new: " + scheme);
            }
            if (claimed) {
                SchemeExecutionClaim standing =
                        schemeExecutionClaimStore.claim(
                                uow,
                                new SchemeExecutionClaim(
                                        RAIL, reference, SchemeExecutionClaim.Subject.UNMATCHED,
                                        parking, receivedAt));
                if (!standing.heldBy(SchemeExecutionClaim.Subject.UNMATCHED, parking)) {
                    throw new IllegalStateException("the planted claim is the parking's own");
                }
            }
            return new ParkingRow(scheme, parking, entry, postedOn, minor);
        });
    }

    /** One unattributed statement on the instant rail, as the door would hand the parking. */
    private static UnmatchedConfirmations.Parking statement(String scheme, long minor) {
        return new UnmatchedConfirmations.Parking(
                RAIL,
                new ProviderReference(scheme),
                Money.ofMinorUnits(minor, USD),
                UnmatchedConfirmation.Attribution.unattributed(Optional.empty(), Optional.empty()),
                PaymentCreation.resolvedCorrelation());
    }

    /** Every record one parking of {@code scheme} writes, each counted in its own table. */
    private static Map<String, Long> parkingRecords(String scheme, UUID parking, UUID owner)
            throws SQLException {
        String postingKey = PARKING_KEY + operation(scheme);
        List<Long> counted =
                List.of(
                        count("SELECT count(*) FROM payments.unmatched_confirmation"
                                + " WHERE rail = ? AND scheme_reference = ?", RAIL.value(),
                                scheme),
                        count("SELECT count(*) FROM payments.scheme_execution_claim"
                                + " WHERE rail = ? AND scheme_reference = ?", RAIL.value(),
                                scheme),
                        count("SELECT count(*) FROM ledger.journal_entry"
                                + " WHERE idempotency_scope = ?",
                                PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey),
                        count("SELECT count(*) FROM platform.idempotency_record WHERE scope = ?"
                                + " AND idempotency_key = ?",
                                PostingService.IDEMPOTENCY_SCOPE, postingKey),
                        ClearingLineCopies.expectationsOf(
                                ExpectationKind.UNMATCHED_CONFIRMATION, operation(scheme)),
                        count("SELECT count(*) FROM reconciliation.expectation_key"
                                + " WHERE key_kind = 'SCHEME_REF' AND key_value = ?", scheme),
                        count("SELECT count(*) FROM reconciliation.suspense_item"
                                + " WHERE origin_ref = ?", parking.toString()),
                        count("SELECT count(*) FROM reconciliation.break WHERE id = ?", owner),
                        count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                                + " AND aggregate_id = ?", BREAK_RAISED, owner),
                        count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                + " AND target_id = ?", PARKED_AUDIT, scheme));
        Map<String, Long> records = new LinkedHashMap<>();
        for (int i = 0; i < RECORDS.size(); i++) {
            records.put(RECORDS.get(i), counted.get(i));
        }
        return records;
    }

    private static Map<String, Long> records(long each) {
        Map<String, Long> records = new LinkedHashMap<>();
        for (String name : RECORDS) {
            records.put(name, each);
        }
        return records;
    }

    /** Raises on the owner, by aggregate - and no other raise on its item, by payload. */
    private static long raisedFor(Owner owner) throws SQLException {
        long byAggregate = count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                + " AND aggregate_id = ?", BREAK_RAISED, owner.breakId());
        long bySubject = count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                + " AND convert_from(payload, 'UTF8') LIKE ?", BREAK_RAISED,
                "%" + owner.item() + "%");
        assertThat(bySubject).as("no other break was raised on item %s", owner.item())
                .isEqualTo(byAggregate);
        return byAggregate;
    }

    private static long raisedEvents() throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?",
                BREAK_RAISED);
    }

    /** Parkings whose entry posted under the parking key and whose value no item owns yet. */
    private static long adoptableParkings() throws SQLException {
        return count("SELECT count(*) FROM payments.unmatched_confirmation u"
                + " WHERE NOT EXISTS (SELECT 1 FROM reconciliation.suspense_item i"
                + "   WHERE i.origin_ref = u.id::text)"
                + " AND EXISTS (SELECT 1 FROM ledger.journal_entry e WHERE e.idempotency_scope"
                + "   = ? || u.rail || ':' || u.scheme_reference)",
                PostingService.IDEMPOTENCY_SCOPE + ":" + PARKING_KEY);
    }

    private static long resolutionsOf(UUID breakId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?", breakId);
    }

    private static String entryLines(UUID entry) throws SQLException {
        return (String) one("SELECT string_agg(l.direction || ':' || a.purpose, ',' ORDER BY"
                + " l.direction, a.purpose) FROM ledger.journal_line l"
                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                + " WHERE l.entry_id = ?", entry);
    }

    /** An ACTIVE customer wallet in USD - a transfer's owned target. */
    private static UUID openWallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID account =
                    new JdbcLedgerAccountStore()
                            .createOrConverge(app, LedgerAccount.owned(IDS, CLOCK,
                                    AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, USD,
                                    IDS.next()))
                            .account()
                            .id()
                            .value();
            app.commit();
            return account;
        }
    }

    private static String transferTo(UUID wallet) {
        return "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                + "\"narrative\":\"the payer is identified as the owner of this wallet\","
                + "\"targetAccountId\":\"" + wallet + "\"}";
    }

    // ----------------------------------------------------------------- the backfill

    /** The opening-position backfill, as the platform, under a fresh key. */
    private OpeningPosition.Adopted backfill(String reason) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return openingPosition.record("ucs-open-" + UUID.randomUUID(), reason);
        }
    }

    /** The same backfill through its door, as a controller, under a fresh key. */
    private HttpResponse<String> backfillOverHttp(String token, String reason) throws Exception {
        return desk(token, "/opening-position", "ucs-open-" + UUID.randomUUID(),
                "{\"reason\":\"" + reason + "\"}");
    }

    // ----------------------------------------------------------------- the proofs

    private PositionProof.Report proofs() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return positionProof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    private static long unknownLines(PositionProof.Report report, AccountPurpose purpose) {
        return report.unattributedByPurpose().getOrDefault(purpose, 0L);
    }

    private static PositionProof.SuspenseVerdict suspense(PositionProof.Report report) {
        return report.suspenseVerdicts().stream()
                .filter(verdict -> verdict.currency().equals(USD))
                .findFirst()
                .orElseThrow();
    }

    /** The suspense identity's difference: CR-DR - (CREDIT - DEBIT remainders + unadopted). */
    private static Money suspenseGap(PositionProof.Report report) {
        PositionProof.SuspenseVerdict verdict = suspense(report);
        return verdict.ledgerBalance()
                .minus(verdict.creditRemainders()
                        .minus(verdict.debitRemainders())
                        .plus(verdict.unadoptedParkings()));
    }

    /** INSTANT_CLEARING's identity difference: balance - (open remainders - open items). */
    private static Money instantGap(PositionProof.Report report) {
        PositionProof.PositionVerdict verdict =
                report.verdicts().stream()
                        .filter(v -> v.purpose() == AccountPurpose.INSTANT_CLEARING
                                && v.currency().equals(USD))
                        .findFirst()
                        .orElseThrow();
        return verdict.ledgerBalance().minus(verdict.openRemainders().minus(verdict.openItems()));
    }

    private static void assertTrialBalance() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            assertThat(new TrialBalance().sweep(app).outOfBalance())
                    .as("the trial balance holds (INV-ACC-01)")
                    .isEmpty();
            app.rollback();
        }
    }

    // ----------------------------------------------------------------- the instant door

    private record Fixture(String token, String methodId) {}

    /** A verified customer with a USD wallet and a registered bank account. */
    private Fixture bankFixture() throws Exception {
        String token = verifiedCustomer("ucs." + letters());
        openAccount(token);
        return new Fixture(token, registerBankAccount(token));
    }

    /** Creates and confirms a pay-by-bank payment; answers the confirmation response. */
    private HttpResponse<String> confirmedPayment(Fixture f, String amount) throws Exception {
        HttpResponse<String> created = customerPost("/v1/payments",
                "{\"paymentMethodId\":\"" + f.methodId() + "\",\"amount\":\"" + amount
                        + "\",\"currency\":\"USD\"}",
                f.token(), true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String paymentId = field(created.body(), "id");
        HttpResponse<String> confirmed = customerPost(
                "/v1/payments/" + paymentId + "/confirmation", null, f.token(), false);
        assertThat(confirmed.statusCode()).as(confirmed.body()).isEqualTo(200);
        return confirmed;
    }

    /** The initiation opens with a handle minted per request (the stub's dedupe shape). */
    private static void schemeInitiates() {
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH,
                200,
                "{\"status\":\"initiated\",\"handle\":\"https://payer-psp.example/authorize/"
                        + letters() + "\"}");
    }

    private int executedCallback(String reference, String scheme, String cycle, String amount) {
        return callback("{\"eventId\":\"evt_" + letters() + "\",\"reference\":\"" + reference
                + "\",\"status\":\"executed\",\"schemeReference\":\"" + scheme
                + "\",\"settlementCycle\":\"" + cycle + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"USD\"}");
    }

    /** A statement naming nothing we made, carrying money - a fresh event id every call. */
    private static String orphanStatement(String scheme, String named, String amount) {
        return "{\"eventId\":\"evt_" + letters() + "\",\"reference\":\"" + named
                + "\",\"status\":\"executed\",\"schemeReference\":\"" + scheme
                + "\",\"amount\":\"" + amount + "\",\"currency\":\"USD\"}";
    }

    /** A moneyless word on our own reference ("expired", "rejected"). */
    private static String word(String reference, String status) {
        return "{\"eventId\":\"evt_" + letters() + "\",\"reference\":\"" + reference
                + "\",\"status\":\"" + status + "\"}";
    }

    private int callback(String body) {
        return provider.deliverTimestampSignedCallback(
                door(), body, WEBHOOK_KEY, Instant.now(CLOCK).getEpochSecond(), 1);
    }

    /** The door's one 409 is a contended inbox record: redelivered, as the scheme would. */
    private int deliverUntilAcknowledged(String body) throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            int status = callback(body);
            if (status != 409) {
                return status;
            }
            Thread.sleep(20);
        }
        return 409;
    }

    private URI door() {
        return URI.create(
                "http://localhost:" + port + "/v1/providers/payments/instant/webhooks");
    }

    private static String attemptIdOf(String paymentId) throws SQLException {
        return (String) one("SELECT id::text FROM payments.payment_attempt WHERE intent_id = ?",
                UUID.fromString(paymentId));
    }

    private static String referenceOf(String attemptId) throws SQLException {
        return (String) one("SELECT end_to_end_reference FROM payments.payment_attempt"
                + " WHERE id = ?", UUID.fromString(attemptId));
    }

    private String verifiedCustomer(String login) throws Exception {
        HttpResponse<String> registered = customerPost("/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null, true);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        execute("UPDATE party.customer SET status = 'ACTIVE',"
                        + " status_changed_at = GREATEST(now(), opened_at)"
                        + " WHERE party_id = (SELECT party_id FROM identity.identity"
                        + " WHERE login_identifier = ?)",
                login);
        HttpResponse<String> authenticated = customerPost("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null, false);
        return field(authenticated.body(), "sessionToken");
    }

    private void openAccount(String token) throws Exception {
        HttpResponse<String> opened = customerPost("/v1/me/accounts",
                "{\"productType\":\"WALLET\",\"currency\":\"USD\"}", token, true);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
    }

    /** Registers a bank account through the real exchange; returns the method id. */
    private String registerBankAccount(String token) throws Exception {
        provider.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH,
                200,
                "{\"status\":\"exchanged\",\"destination\":\"dest-ucs-" + letters()
                        + "\",\"suffix\":\"6819\",\"payee\":\"match\"}");
        HttpResponse<String> registered = customerPost("/v1/me/payment-methods/bank-accounts",
                "{\"grant\":\"blg-" + letters() + "\",\"acknowledgeNoMatch\":false}", token,
                true);
        assertThat(registered.statusCode()).as(registered.body()).isEqualTo(201);
        return field(registered.body(), "id");
    }

    private HttpResponse<String> customerPost(
            String path, String body, String token, boolean keyed) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (keyed) {
            request.header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ----------------------------------------------------------------- the desk

    private HttpResponse<String> desk(String token, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deskGet(String token, String path) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"")
                .matcher(json);
        assertThat(matcher.find()).as("%s in %s", name, json).isTrue();
        return matcher.group(1);
    }

    private record Session(IdentityId identity, String token) {}

    private Session sessionWith(RoleName role) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        return new Session(identity, givenASessionFor(identity));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        execute("INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Suspense Desk Person', now())",
                party);
        execute("INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                        + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                        + " now(), now())",
                identity, party, "uc" + letters() + letters());
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    private static <R> R asPlatform(Function<Connection, R> work) throws SQLException {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return committed(work);
        }
    }

    /** One transaction on a fresh connection: committed, or rolled back and rethrown. */
    private static <R> R committed(Function<Connection, R> work) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                R result = work.apply(app);
                app.commit();
                return result;
            } catch (RuntimeException | Error refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t20-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t20-cause"));
    }

    private static String key() {
        return "ucs-" + UUID.randomUUID();
    }

    /** A scheme reference in letters: no digit run a card-number screen could ever refuse. */
    private static String scheme() {
        return "sch-" + letters();
    }

    /** The operation a parking's posting key names: the scheme execution on its rail. */
    private static String operation(String scheme) {
        return RAIL.value() + ":" + scheme;
    }

    /** Letters only: an external reference no digit run can make look like an instrument. */
    private static String letters() {
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            letters.append(alphabet.charAt(RANDOMNESS.nextInt(alphabet.length())));
        }
        return letters.toString();
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        List<Callable<T>> all = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            all.add(work);
        }
        return race(all);
    }

    /** Every racer released by one latch; the answers in the racers' order. */
    private static <T> List<T> race(List<Callable<T>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> racer : racers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(3, TimeUnit.MINUTES));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One row as column label -> text (SQL NULL as null), asserting it is the only row. */
    private static Map<String, String> row(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for %s", sql).isTrue();
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 1; i <= result.getMetaData().getColumnCount(); i++) {
                    values.put(result.getMetaData().getColumnLabel(i), result.getString(i));
                }
                assertThat(result.next()).as("exactly one row for %s", sql).isFalse();
                return values;
            }
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    /** A count on the caller's own connection, from inside a port - unchecked for the port. */
    private static long countOn(Connection connection, String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : -1;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the probe could not read its own transaction",
                    failure);
        }
    }

    /** A UUID on the caller's own connection, from inside a port - unchecked for the port. */
    private static UUID uuidOn(Connection connection, String sql, Object... args) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1, UUID.class) : null;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("the probe could not read its own transaction",
                    failure);
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }
}
