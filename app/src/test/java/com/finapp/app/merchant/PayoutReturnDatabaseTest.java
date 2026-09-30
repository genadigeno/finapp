package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.reconciliation.ClearingLineCopies;
import com.finapp.app.reconciliation.OpeningPosition;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.app.settlement.SimulatedPayoutReports;
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
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.TrialBalance;
import com.finapp.merchant.Merchant;
import com.finapp.merchant.MerchantAdministration;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantNotSettledException;
import com.finapp.merchant.MerchantPayable;
import com.finapp.merchant.MerchantPayoutId;
import com.finapp.merchant.MerchantPayoutOutcomes;
import com.finapp.merchant.MerchantPayoutResolution;
import com.finapp.merchant.MerchantPayoutStatus;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.merchant.MerchantPayouts;
import com.finapp.merchant.MerchantStatus;
import com.finapp.merchant.PayoutEvidenceStore;
import com.finapp.merchant.PayoutReturnStore;
import com.finapp.merchant.PayoutReturns;
import com.finapp.merchant.PayoutSettlementExpectations;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.payments.SettlementExpectations;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.WaitingPayoutReturns;
import com.finapp.settlement.BatchAcceptance;
import com.finapp.settlement.DeliveryChannel;
import com.finapp.settlement.FileParsing;
import com.finapp.settlement.FileReception;
import com.finapp.settlement.SettlementAuditAction;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.TransactionRunner;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The payout return worker over the REAL composition (`P8-TSK-019`, ADR-0073): payouts made
 * through the real merchant flow against the simulated provider, the provider's report rendered
 * from {@code merchant.merchant_payout} and accepted, the {@code RETURNED} line waiting
 * {@code UNMATCHED} with no break — and {@link PayoutReturnSweep} applying it: the payout row
 * locked, the payable read {@code FOR SHARE}, the return's own posting
 * {@code merchant-payout-return:<payoutId>} DR {@code PAYOUT_CLEARING} / CR the payable, the
 * append-only fact, the keyless {@code PAYOUT_RETURN} expectation, the event and the audit
 * record, in one transaction; then the NEXT matcher sweep allocating the waiting line through the
 * operation-anchored clause of the rematch predicate, under origin {@code REMATCH}.
 *
 * <p><strong>The races, each held open deterministically</strong>: the worker's uncommitted
 * transaction against the grace leg's {@code FOR UPDATE} on the item, and against a merchant
 * close's {@code FOR UPDATE} on the payable — both orders each, the loser proven blocked through
 * {@code pg_stat_activity} before the winner commits. Ten workers over ten duplicate lines apply
 * the return once.
 *
 * <p><strong>No bank statement</strong>, and each merchant's funding opens its capture's
 * expectation through the live port, so every position here is explained: every proof assertion
 * is BASELINE-RELATIVE per currency — {@code PAYOUT_CLEARING}'s identity difference (balance −
 * (open remainders − open items)) moved by exactly zero across each flow (the `P8-TSK-017` /
 * `-015` precedent) — plus the trial balance.
 *
 * <p><strong>Runs in its own container</strong>, as the payout and scheme cash suites do: its
 * fallback cases park on {@code SUSPENSE_UNMATCHED}, which the conservation storm's at-rest count
 * reads as Phase 7 parkings alone, and its fixture captures cannot be re-derived by the register
 * rebuild {@code SettlementAcceptanceDatabaseTest} performs — both suites assume no such suite ran
 * before them in one database (recorded as debt, `P8-TSK-019`).
 *
 * <p><strong>The sweep tallies are asserted exactly</strong>: every case leaves each of its
 * {@code RETURNED} lines MATCHED or PARKED before it ends, and no other suite leaves one waiting,
 * so a tick's candidates are the case's own. Every count of record is read from the tables.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the payout return worker: applied once, rematched, raced (P8-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class PayoutReturnDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final String PAYOUT_SOURCE = "simulated-payout.settlement";
    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    private static final int RACERS = 10;

    /** ADR-0073 §2's spelling, restated here rather than read off the production constant. */
    private static final String RETURN_KEY = "merchant-payout-return:";

    private static final String RETURNED_EVENT = "merchant.MerchantPayoutReturned";
    private static final String RETURN_AUDIT = "merchant.PayoutReturnApplied";

    /**
     * The provider mints its own reference per payout — letters only, so no digit run of card
     * length can ever meet the format's reference class by chance.
     */
    private static final String PAID =
            "{\"status\":\"paid\",\"reference\":\"po_{{randomValue length=12"
                    + " type='ALPHABETIC'}}\"}";

    /** A word the payout wire does not know: indeterminate, never success - UNKNOWN. */
    private static final String PENDING = "{\"status\":\"pending\"}";

    private static final String DECLINED = "{\"status\":\"declined\"}";

    /** The six records one application writes, and every refusal writes none of. */
    private static final List<String> RECORDS =
            List.of("payout_return rows", "return entries", "posting claims",
                    "PAYOUT_RETURN expectations", "returned events", "applied audits");

    private static SimulatedProvider provider;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private MerchantPayouts payouts;
    @Autowired private MerchantAdministration administration;
    @Autowired private MerchantPayoutStore<Connection> merchantPayoutStore;
    @Autowired private MerchantPayoutOutcomes outcomes;
    @Autowired private PayoutEvidenceStore<Connection> evidence;
    @Autowired private PayoutReturnStore<Connection> payoutReturnStore;
    @Autowired private PayoutReturns payoutReturns;
    @Autowired private PayoutReturnSweep payoutReturnSweep;
    @Autowired private WaitingPayoutReturns waitingPayoutReturns;
    @Autowired private SettlementBatchStore<Connection> settlementBatchStore;
    @Autowired private FileReception<Connection> reception;
    @Autowired private FileParsing parsing;
    @Autowired private BatchAcceptance acceptance;
    @Autowired private Matching matching;
    @Autowired private PositionProof proof;
    @Autowired private OpeningPosition openingPosition;
    @Autowired private PostingService postingService;
    @Autowired private SettlementExpectations settlementExpectations;
    @Autowired private LedgerAccountStore<Connection> ledgerAccountStore;
    @Autowired private AuditWriter<Connection> auditWriter;
    @Autowired private OutboxWriter<Connection> outboxWriter;
    @Autowired private TransactionRunner settlementTransactionRunner;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();

    @BeforeAll
    static void startProvider() {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @DynamicPropertySource
    static void providerUrl(DynamicPropertyRegistry registry) {
        if (provider == null) {
            provider = SimulatedProvider.start();
        }
        registry.add("finapp.merchant.payout.provider.url", () -> provider.baseUrl());
    }

    @BeforeEach
    void pays() {
        provider.reset();
        provider.succeedsWith(PATH, 200, PAID);
    }

    // ----------------------------------------------------------------- the routine return

    @Test
    @Order(1)
    @DisplayName("the routine return: the worker posts DR PAYOUT_CLEARING / CR payable once, dated"
            + " from stored evidence, records the fact, opens its keyless PAYOUT_RETURN, announces"
            + " and audits it - and the next sweep's rematch leg allocates the waiting line by the"
            + " operation-anchored rule; the payout still COMPLETED")
    void theRoutineReturnIsAppliedOnceAndRematched() throws Exception {
        Money baseline = unexplained(EUR);
        long unattributed = unattributedOnPayoutClearing();
        Waiting waiting = waitingReturn(EUR, "100.00", "30.00");
        UUID payout = waiting.payout();
        UUID item = waiting.item();

        assertThat(payoutReturnSweep.sweep())
                .as("one waiting return, applied")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 1, 0, 0, 0));
        assertThat(returnRecords(payout)).as("one of each").isEqualTo(records(1));

        // THE FACT: the payout's own money, the item that caused it, dated from stored evidence.
        assertThat(one("SELECT amount_minor::text || ':' || currency || ':' || scale::text FROM"
                        + " merchant.payout_return WHERE payout_id = ?", payout))
                .isEqualTo(one("SELECT amount_minor::text || ':' || currency || ':' ||"
                        + " scale::text FROM merchant.merchant_payout WHERE id = ?", payout))
                .isEqualTo("3000:EUR:2");
        assertThat(one("SELECT external_item_ref FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(item);
        LocalDate acceptedOn =
                date("SELECT accepted_on FROM settlement.batch WHERE id = ?", waiting.batch());
        LocalDate settlementDate =
                date("SELECT COALESCE(settlement_date, business_date) FROM"
                        + " reconciliation.external_item WHERE id = ?", item);
        assertThat(date("SELECT returned_on FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .as("the posting date is the batch's stored accepted_on, never a clock")
                .isEqualTo(acceptedOn);
        assertThat(date("SELECT value_date FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .as("the value date is the item's settlement date")
                .isEqualTo(settlementDate);

        // THE POSTING: its own key, the declared position debited, THIS merchant's payable
        // credited, dated exactly as the fact.
        UUID entry = returnEntry(payout);
        assertThat(one("SELECT journal_entry_id FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(entry);
        assertThat(entryLines(entry))
                .containsExactlyInAnyOrder("PAYOUT_CLEARING:DEBIT:3000",
                        "MERCHANT_PAYABLE:CREDIT:3000");
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ? AND"
                        + " ledger_account_id = ? AND direction = 'CREDIT'",
                entry, waiting.merchant().payable().value()))
                .isEqualTo(1);
        assertThat(date("SELECT posting_date FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo(acceptedOn);
        assertThat(date("SELECT value_date FROM ledger.journal_entry WHERE id = ?", entry))
                .isEqualTo(settlementDate);

        // THE EXPECTATION: the return's PAYOUT_CLEARING line's copy, read back from the ledger,
        // INBOUND - and no key of its own: only the operation-anchored rule reaches it.
        ClearingLineCopies.Opened opened =
                ClearingLineCopies.assertOpensItsClearingLinesCopy(
                        ExpectationKind.PAYOUT_RETURN, payout.toString(), RETURN_KEY + payout,
                        ExpectationDirection.INBOUND);
        assertThat(opened.amountMinor()).isEqualTo(30_00);
        assertThat(opened.journalEntryId()).isEqualTo(entry);
        assertThat(opened.settlementCycle()).as("a payout announces no cycle").isEmpty();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                        + " expectation_id = ?", opened.id()))
                .as("ZERO keys: the operation-anchored rule is the only way in")
                .isZero();

        // THE EVENT AND THE AUDIT RECORD: identifiers only, the item's own flow continued.
        String itemFlow = (String) one("SELECT correlation_id FROM reconciliation.external_item"
                + " WHERE id = ?", item);
        Object[] event = row("SELECT payload, correlation_id, causation_id FROM"
                + " platform.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                RETURNED_EVENT, payout);
        String payload = new String((byte[]) event[0], StandardCharsets.UTF_8);
        assertThat(fieldNames(payload))
                .as("identifiers only - never an amount (INV-AUD-02)")
                .containsExactlyInAnyOrder("payoutId", "merchantId", "journalEntryId");
        assertThat(payload).contains(payout.toString(),
                waiting.merchant().id().value().toString(), entry.toString());
        assertThat(new Object[] {event[1], event[2]})
                .as("the item's flow continues, the item its cause (ADR-0073 section 4)")
                .containsExactly(itemFlow, item.toString());
        assertThat(row("SELECT actor_type, correlation_id FROM platform.audit_record WHERE"
                        + " operation = ? AND target_id = ?", RETURN_AUDIT, payout.toString()))
                .as("the platform acted, in the item's flow")
                .containsExactly("SYSTEM", itemFlow);

        assertThat(itemStatus(item)).as("the worker allocates nothing").isEqualTo("UNMATCHED");
        assertThat(payoutStatus(payout)).isEqualTo(MerchantPayoutStatus.COMPLETED.name());

        // THE REMATCH: the next sweep reaches the keyless expectation through the item's anchor.
        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("MATCHED");
        assertThat(returnAllocation(item))
                .as("allocated by the rematch leg through the anchor's provider reference")
                .isEqualTo("REMATCH:PAYOUT_PROVIDER_REF:3000");
        assertThat(expectationStatus(ExpectationKind.PAYOUT_RETURN, payout)).isEqualTo("SETTLED");
        assertThat(breaksOn(item)).as("a routine return raises nothing").isZero();
        assertThat(payoutStatus(payout))
                .as("the return is a new fact beside the payout (INV-LIFE-04)")
                .isEqualTo(MerchantPayoutStatus.COMPLETED.name());

        MerchantPayable.Payable payable = payableOf(waiting.merchant());
        assertThat(payable.captured().minorUnits()).isEqualTo(100_00);
        assertThat(payable.paidOut().minorUnits()).isEqualTo(30_00);
        assertThat(payable.payoutsReturned().minorUnits())
                .as("the payable credited back, labelled as the return it is")
                .isEqualTo(30_00);
        assertThat(payable.reconciliationAttributed().isZero()).isTrue();
        assertThat(payable.other().isZero()).isTrue();
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(payable.position().minorUnits()).isEqualTo(100_00);

        assertThat(unexplained(EUR))
                .as("PAYOUT_CLEARING's identity moved by exactly zero across the flow")
                .isEqualTo(baseline);
        assertThat(unattributedOnPayoutClearing())
                .as("the return's clearing line is a known line: its expectation names it")
                .isEqualTo(unattributed);
        assertTrialBalance();
    }

    @Test
    @Order(2)
    @DisplayName("a retry of an applied return - the same evidence, or read on a later day -"
            + " converges ALREADY_RETURNED on the payout row, and the worker's own next tick skips"
            + " it: still one fact, one entry, one expectation, one event, one record")
    void aRetryOfAnAppliedReturnConverges() throws Exception {
        Money baseline = unexplained(GBP);
        Waiting waiting = waitingReturn(GBP, "50.00", "12.00");
        UUID payout = waiting.payout();
        assertThat(payoutReturnSweep.sweep())
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 1, 0, 0, 0));
        UUID entry = returnEntry(payout);

        for (PayoutReturns.ReturnEvidence retry :
                List.of(evidenceOf(waiting, 0), evidenceOf(waiting, 1))) {
            PayoutReturns.Applied again = asPlatform(uow -> payoutReturns.apply(uow, retry));
            assertThat(again.outcome()).isEqualTo(PayoutReturns.Outcome.ALREADY_RETURNED);
            assertThat(again.payout().map(MerchantPayoutId::value)).contains(payout);
            assertThat(again.entry()).as("nothing posted").isEmpty();
        }
        assertThat(returnRecords(payout)).isEqualTo(records(1));
        assertThat(returnEntry(payout)).isEqualTo(entry);

        assertThat(payoutReturnSweep.sweep())
                .as("still waiting for the matcher, already returned: skipped, nothing written")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 0, 0, 1, 0));
        assertThat(returnRecords(payout)).isEqualTo(records(1));

        matchUntilQuiet();
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        MerchantPayable.Payable payable = payableOf(waiting.merchant());
        assertThat(payable.payoutsReturned().minorUnits()).as("credited once").isEqualTo(12_00);
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(unexplained(GBP)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- ten workers

    @Test
    @Order(3)
    @DisplayName("ten workers at once over a report carrying the same RETURNED line ten times: the"
            + " matcher parks lines 2..10 as DUPLICATE_EXTERNAL, the first waits - ONE return, one"
            + " entry, one expectation, one event, one record; the first line MATCHED, the payable"
            + " credited exactly once")
    void tenWorkersOverTenDuplicateLinesApplyOnce() throws Exception {
        Money baseline = unexplained(USD);
        Funded merchant = funded(USD, "100.00");
        UUID payout = paid(merchant, "40.00");
        settled(USD, payout);
        SimulatedPayoutReports tenfold = report(USD);
        for (int i = 0; i < RACERS; i++) {
            tenfold.with(returnedLine(payout));
        }
        UUID batch = accepted(tenfold);
        matchUntilQuiet();
        List<UUID> items = returnedItems(batch);
        assertThat(items).hasSize(RACERS);
        UUID first = items.get(0);
        assertThat(itemStatus(first)).isEqualTo("UNMATCHED");
        assertThat(breaksOn(first)).isZero();
        for (UUID repeated : items.subList(1, RACERS)) {
            assertThat(itemStatus(repeated)).isEqualTo("PARKED");
            assertThat(one("SELECT type || ':' || cause FROM reconciliation.break WHERE"
                            + " external_item_id = ?", repeated))
                    .isEqualTo("DUPLICATE_EXTERNAL:REPEATED_FINGERPRINT");
        }

        List<PayoutReturnSweep.SweepResult> ticks = race(RACERS, payoutReturnSweep::sweep);
        assertThat(ticks.stream().mapToInt(PayoutReturnSweep.SweepResult::applied).sum())
                .as("ten workers, ONE application - the payout row serialises them")
                .isEqualTo(1);
        assertThat(ticks.stream().mapToInt(PayoutReturnSweep.SweepResult::skipped).sum())
                .as("nine converged on the standing return")
                .isEqualTo(RACERS - 1);
        assertThat(ticks.stream().mapToInt(PayoutReturnSweep.SweepResult::notApplicable).sum())
                .isZero();
        assertThat(ticks.stream().mapToInt(PayoutReturnSweep.SweepResult::failedRows).sum())
                .as("no racer failed: the losers wait on the row, never collide on a unique")
                .isZero();
        assertThat(returnRecords(payout)).as("one of each, counted").isEqualTo(records(1));
        assertThat(entryLines(returnEntry(payout)))
                .containsExactlyInAnyOrder("PAYOUT_CLEARING:DEBIT:4000",
                        "MERCHANT_PAYABLE:CREDIT:4000");
        assertThat(one("SELECT external_item_ref FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .as("only the waiting line was ever a worker's candidate")
                .isEqualTo(first);

        matchUntilQuiet();
        assertThat(itemStatus(first)).isEqualTo("MATCHED");
        for (UUID repeated : items.subList(1, RACERS)) {
            assertThat(itemStatus(repeated))
                    .as("a duplicate never takes the exhausted return")
                    .isEqualTo("PARKED");
        }
        assertThat(expectationStatus(ExpectationKind.PAYOUT_RETURN, payout)).isEqualTo("SETTLED");
        MerchantPayable.Payable payable = payableOf(merchant);
        assertThat(payable.payoutsReturned().minorUnits())
                .as("credited exactly once")
                .isEqualTo(40_00);
        assertThat(payable.position().minorUnits()).isEqualTo(100_00);
        assertThat(payable.terms()).isEqualTo(payable.position());
        assertThat(unexplained(USD)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- a closed payable

    @Test
    @Order(4)
    @DisplayName("a return to a merchant closed since writes nothing and waits; at grace it parks"
            + " as RETURN_NOT_APPLICABLE, and a four-eyes transfer credits the payable the desk"
            + " names - read as a reconciliation attribution, never as a payout return")
    void aClosedPayableSendsTheReturnToAPerson() throws Exception {
        Money baseline = unexplained(EUR);
        Funded closing = funded(EUR, "18.00");
        UUID payout = paid(closing, "18.00");
        settled(EUR, payout);
        assertThat(asOperator(uow -> administration.close(uow, closing.id(),
                        "the merchant ended the relationship")).status())
                .isEqualTo(MerchantStatus.CLOSED);
        assertThat(accountStatus(closing.payable())).isEqualTo("CLOSED");

        UUID batch = accepted(report(EUR).with(returnedLine(payout)));
        matchUntilQuiet();
        UUID item = onlyReturnedItem(batch);
        assertThat(itemStatus(item)).isEqualTo("UNMATCHED");
        assertThat(breaksOn(item)).isZero();

        assertThat(payoutReturnSweep.sweep())
                .as("the payable is not postable: not applicable, retried while it waits")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 0, 1, 0, 0));
        assertNothingWritten(payout);
        assertThat(itemStatus(item)).isEqualTo("UNMATCHED");

        expireGrace(item);
        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("PARKED");
        Object[] raised = row("SELECT id, type, cause, value_at_issue_minor,"
                + " internal_classification, internal_operation_ref FROM reconciliation.break"
                + " WHERE external_item_id = ?", item);
        assertThat(new Object[] {raised[1], raised[2], raised[3], raised[4], raised[5]})
                .containsExactly("REVERSAL_MISMATCH", "RETURN_NOT_APPLICABLE", 18_00L,
                        "COMPLETED", payout.toString());

        Funded receiving = funded(EUR, "5.00");
        UUID transfer = fourEyesTransfer((UUID) raised[0], receiving.payable());
        assertThat(entryLines(transfer))
                .as("the parked value to the payable the desk named, four eyes on it")
                .containsExactlyInAnyOrder("SUSPENSE_UNMATCHED:DEBIT:1800",
                        "MERCHANT_PAYABLE:CREDIT:1800");
        assertThat(count("SELECT count(*) FROM ledger.journal_line WHERE entry_id = ? AND"
                        + " ledger_account_id = ? AND direction = 'CREDIT'",
                transfer, receiving.payable().value()))
                .isEqualTo(1);

        MerchantPayable.Payable credited = payableOf(receiving);
        assertThat(credited.reconciliationAttributed().minorUnits())
                .as("classified first, by its RECONCILIATION origin")
                .isEqualTo(18_00);
        assertThat(credited.payoutsReturned().isZero())
                .as("a person's transfer is not the return's own posting")
                .isTrue();
        assertThat(credited.captured().minorUnits()).isEqualTo(5_00);
        assertThat(credited.position().minorUnits()).isEqualTo(23_00);
        assertThat(credited.terms()).isEqualTo(credited.position());

        MerchantPayable.Payable closed = payableOf(closing);
        assertThat(closed.paidOut().minorUnits()).isEqualTo(18_00);
        assertThat(closed.payoutsReturned().isZero()).isTrue();
        assertThat(closed.position().isZero()).isTrue();
        assertNothingWritten(payout);
        assertThat(payoutStatus(payout)).isEqualTo(MerchantPayoutStatus.COMPLETED.name());
        assertThat(unexplained(EUR)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- not this fact

    @Test
    @Order(5)
    @DisplayName("evidence of another amount or currency is AMOUNT_DIFFERS, and references naming"
            + " no payout are NO_PAYOUT - each writing nothing at all")
    void anUnequalOrUnknownReturnWritesNothing() throws Exception {
        Funded merchant = funded(EUR, "60.00");
        UUID payout = paid(merchant, "25.00");
        String theirs = (String) one("SELECT provider_reference FROM merchant.merchant_payout"
                + " WHERE id = ?", payout);

        PayoutReturns.ReturnEvidence twenty =
                looseEvidence(Optional.of(theirs), Optional.empty(), money("20.00", EUR));
        PayoutReturns.Applied fewer = asPlatform(uow -> payoutReturns.apply(uow, twenty));
        assertThat(fewer.outcome()).isEqualTo(PayoutReturns.Outcome.AMOUNT_DIFFERS);
        assertThat(fewer.payout().map(MerchantPayoutId::value)).contains(payout);
        assertThat(fewer.entry()).isEmpty();

        PayoutReturns.ReturnEvidence pounds =
                looseEvidence(Optional.of(theirs), Optional.empty(), money("25.00", GBP));
        PayoutReturns.Applied otherCurrency =
                asPlatform(uow -> payoutReturns.apply(uow, pounds));
        assertThat(otherCurrency.outcome())
                .as("the same digits in another currency are another amount")
                .isEqualTo(PayoutReturns.Outcome.AMOUNT_DIFFERS);
        assertThat(otherCurrency.entry()).isEmpty();

        PayoutReturns.ReturnEvidence unknown =
                looseEvidence(Optional.of("po_nobody" + letters()),
                        Optional.of("pyo-" + IDS.next()), money("25.00", EUR));
        PayoutReturns.Applied nobody = asPlatform(uow -> payoutReturns.apply(uow, unknown));
        assertThat(nobody.outcome()).isEqualTo(PayoutReturns.Outcome.NO_PAYOUT);
        assertThat(nobody.payout()).isEmpty();
        assertThat(nobody.entry()).isEmpty();

        assertNothingWritten(payout);
        MerchantPayable.Payable payable = payableOf(merchant);
        assertThat(payable.payoutsReturned().isZero()).isTrue();
        assertThat(payable.position().minorUnits()).isEqualTo(35_00);
    }

    // ----------------------------------------------------------------- an unknown payout

    @Test
    @Order(6)
    @DisplayName("a return reported while its payout is UNKNOWN is PAYOUT_NOT_COMPLETED and writes"
            + " nothing; once the resolution sweep completes the payout within grace, the next"
            + " tick applies it - found by our reference - and the rematch allocates it")
    void aReturnReportedBeforeItsPayoutCompletedWaitsForIt() throws Exception {
        Money baseline = unexplained(GBP);
        Funded merchant = funded(GBP, "80.00");
        UUID payout = answered(merchant, "22.00", PENDING, MerchantPayoutStatus.UNKNOWN);
        Object[] references = row("SELECT provider_reference, provider_idempotency_reference"
                + " FROM merchant.merchant_payout WHERE id = ?", payout);
        assertThat(references[0]).as("an UNKNOWN payout holds no provider reference").isNull();
        String ours = (String) references[1];

        // The provider's reference names no payout the platform holds; ours names this one.
        UUID batch = accepted(report(GBP).with(
                SimulatedPayoutReports.Entry.returned("22.00", "po_pending" + letters(), ours)));
        matchUntilQuiet();
        UUID item = onlyReturnedItem(batch);
        assertThat(itemStatus(item)).isEqualTo("UNMATCHED");
        assertThat(breaksOn(item)).as("an in-flight payout's return waits").isZero();

        assertThat(payoutReturnSweep.sweep())
                .as("PAYOUT_NOT_COMPLETED: nothing written, retried")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 0, 1, 0, 0));
        assertNothingWritten(payout);

        String theirs = "po_q" + letters();
        provider.succeedsWith(PATH + "/" + ours, 200,
                "{\"status\":\"paid\",\"reference\":\"" + theirs + "\"}");
        assertThat(resolution().sweep().resolved()).isGreaterThanOrEqualTo(1);
        assertThat(payoutStatus(payout)).isEqualTo(MerchantPayoutStatus.COMPLETED.name());
        assertThat(ClearingLineCopies.expectationsOf(
                        ExpectationKind.MERCHANT_PAYOUT, payout.toString()))
                .isEqualTo(1);

        assertThat(payoutReturnSweep.sweep())
                .as("completed now: applied, found by our reference")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 1, 0, 0, 0));
        assertThat(returnRecords(payout)).isEqualTo(records(1));
        assertThat(one("SELECT external_item_ref FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(item);

        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("MATCHED");
        assertThat(returnAllocation(item))
                .as("the anchor reached by our reference - the provider's quoted one names none")
                .isEqualTo("REMATCH:OUR_REF:2200");
        assertThat(expectationStatus(ExpectationKind.PAYOUT_RETURN, payout)).isEqualTo("SETTLED");

        // The execution's own line, late: the payout's expectation settles too.
        accepted(report(GBP).with(SimulatedPayoutReports.Entry.settled("22.00", "0.25", theirs,
                ours)));
        matchUntilQuiet();
        assertThat(expectationStatus(ExpectationKind.MERCHANT_PAYOUT, payout))
                .isEqualTo("SETTLED");
        assertThat(payableOf(merchant).payoutsReturned().minorUnits()).isEqualTo(22_00);
        assertThat(unexplained(GBP)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- one transaction

    @Test
    @Order(7)
    @DisplayName("a failure after the posting - the expectation port refusing - rolls the whole"
            + " return back: no fact, no entry, no posting claim, no expectation; the real applier"
            + " then applies the same return")
    void aFailureAfterThePostingRollsTheWholeReturnBack() throws Exception {
        Money baseline = unexplained(EUR);
        Waiting waiting = waitingReturn(EUR, "40.00", "16.00");
        UUID payout = waiting.payout();
        AtomicLong standingInside = new AtomicLong(-1);
        AtomicReference<PayoutSettlementExpectations.Opening> asked = new AtomicReference<>();
        PayoutSettlementExpectations refusing =
                (unitOfWork, opening) -> {
                    asked.set(opening);
                    standingInside.set(countOn(unitOfWork,
                            "SELECT count(*) FROM merchant.payout_return r JOIN"
                                    + " ledger.journal_entry e ON e.id = r.journal_entry_id"
                                    + " WHERE r.payout_id = ?",
                            payout));
                    throw new IllegalStateException(
                            "the expectation port failed after the posting");
                };
        PayoutReturns failing =
                new PayoutReturns(
                        merchantPayoutStore,
                        payoutReturnStore,
                        postingService,
                        new ChartOfAccounts<>(ledgerAccountStore),
                        ledgerAccountStore,
                        refusing,
                        auditWriter,
                        outboxWriter,
                        IDS,
                        CLOCK);
        PayoutReturns.ReturnEvidence returned = evidenceOf(waiting, 0);

        assertThatThrownBy(() -> asPlatform(uow -> failing.apply(uow, returned)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("the expectation port failed after the posting");
        assertThat(standingInside.get())
                .as("the posting and the fact stood inside the transaction - not vacuous")
                .isEqualTo(1);
        assertThat(asked.get().kind()).isEqualTo(PayoutSettlementExpectations.Kind.PAYOUT_RETURN);
        assertThat(asked.get().keys()).as("no key of its own").isEmpty();
        assertNothingWritten(payout);
        assertThat(itemStatus(waiting.item())).isEqualTo("UNMATCHED");
        assertThat(payableOf(waiting.merchant()).payoutsReturned().isZero()).isTrue();

        assertThat(payoutReturnSweep.sweep())
                .as("the real applier: the rolled-back claim never blocks the retry")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 1, 0, 0, 0));
        assertThat(returnRecords(payout)).isEqualTo(records(1));
        matchUntilQuiet();
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        MerchantPayable.Payable payable = payableOf(waiting.merchant());
        assertThat(payable.payoutsReturned().minorUnits()).isEqualTo(16_00);
        assertThat(payable.position().minorUnits()).isEqualTo(40_00);
        assertThat(unexplained(EUR)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- against the grace leg

    @Test
    @Order(8)
    @DisplayName("the worker first: its uncommitted application holds the item FOR SHARE, the grace"
            + " leg waits on its FOR UPDATE, and once the return commits the grace leg - on the"
            + " locked row - finds the return's expectation and allocates it, never parking")
    void theWorkerFirstHoldsTheGraceLegOff() throws Exception {
        Money baseline = unexplained(USD);
        Waiting waiting = waitingReturn(USD, "70.00", "21.00");
        UUID item = waiting.item();
        expireGrace(item);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection worker = DatabaseRoles.application()) {
            worker.setAutoCommit(false);
            try {
                assertThat(applyHeld(worker, item)).contains(PayoutReturns.Outcome.APPLIED);
                Future<?> grace = pool.submit(() -> {
                    matching.sweep();
                    return null;
                });
                awaitWaiting("ORDER BY i.grace_until, i.id");
                assertThat(grace.isDone())
                        .as("the grace leg waits on the worker's share lock")
                        .isFalse();
                worker.commit();
                grace.get(2, TimeUnit.MINUTES);
            } finally {
                worker.rollback();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(itemStatus(item)).isEqualTo("MATCHED");
        assertThat(returnAllocation(item))
                .as("the grace leg's own allocation (it decides under origin RUN), judged on the"
                        + " locked row - never parked and later unparked by a rematch")
                .isEqualTo("RUN:PAYOUT_PROVIDER_REF:2100");
        assertThat(breaksOn(item)).as("no RETURN_NOT_APPLICABLE, no break at all").isZero();
        assertThat(suspenseOn(item)).as("nothing parked beside the return").isZero();
        assertThat(returnRecords(waiting.payout())).isEqualTo(records(1));
        assertThat(expectationStatus(ExpectationKind.PAYOUT_RETURN, waiting.payout()))
                .isEqualTo("SETTLED");
        assertThat(unexplained(USD)).isEqualTo(baseline);
        assertTrialBalance();
    }

    @Test
    @Order(9)
    @DisplayName("the grace leg first: the item parks as RETURN_NOT_APPLICABLE, and the worker then"
            + " writes nothing - the page no longer reads it, and the locked re-read finds it no"
            + " longer waiting")
    void theGraceLegFirstLeavesTheWorkerNothing() throws Exception {
        Money baseline = unexplained(EUR);
        Waiting waiting = waitingReturn(EUR, "35.00", "14.00");
        UUID item = waiting.item();
        expireGrace(item);
        matchUntilQuiet();
        assertThat(itemStatus(item)).isEqualTo("PARKED");
        assertThat(one("SELECT type || ':' || cause FROM reconciliation.break WHERE"
                        + " external_item_id = ?", item))
                .isEqualTo("REVERSAL_MISMATCH:RETURN_NOT_APPLICABLE");

        assertThat(payoutReturnSweep.sweep())
                .as("a parked line is no longer a waiting return")
                .isEqualTo(new PayoutReturnSweep.SweepResult(0, 0, 0, 0, 0));
        try (Connection worker = DatabaseRoles.application()) {
            worker.setAutoCommit(false);
            try {
                assertThat(applyHeld(worker, item))
                        .as("the share-locked re-read: not waiting, so nothing at all")
                        .isEmpty();
            } finally {
                worker.rollback();
            }
        }
        assertNothingWritten(waiting.payout());
        assertThat(payableOf(waiting.merchant()).payoutsReturned().isZero())
                .as("a person's transfer is the only way back now - never both")
                .isTrue();
        assertThat(unexplained(EUR)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- against a close

    @Test
    @Order(10)
    @DisplayName("the return first: its uncommitted application holds the payable FOR SHARE, a"
            + " merchant close waits on its FOR UPDATE, and once the return commits the close"
            + " refuses - the payable is owed the return - and stays ACTIVE, credited")
    void theReturnFirstRefusesTheMerchantClose() throws Exception {
        Money baseline = unexplained(GBP);
        Waiting waiting = waitingReturn(GBP, "27.00", "27.00");
        Funded merchant = waiting.merchant();
        assertThat(payableOf(merchant).position().isZero())
                .as("paid out to zero: closable, but for the return")
                .isTrue();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection worker = DatabaseRoles.application()) {
            worker.setAutoCommit(false);
            try {
                assertThat(applyHeld(worker, waiting.item()))
                        .contains(PayoutReturns.Outcome.APPLIED);
                Future<?> close = pool.submit(() -> asOperator(uow -> administration.close(uow,
                        merchant.id(), "the merchant asked to close")));
                awaitWaiting("FROM ledger.ledger_account");
                assertThat(close.isDone())
                        .as("the close waits on the worker's share lock on the payable")
                        .isFalse();
                worker.commit();
                assertThatThrownBy(() -> close.get(2, TimeUnit.MINUTES))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(MerchantNotSettledException.class);
            } finally {
                worker.rollback();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(one("SELECT status FROM merchant.merchant WHERE id = ?", merchant.id().value()))
                .isEqualTo(MerchantStatus.ACTIVE.name());
        assertThat(accountStatus(merchant.payable())).isEqualTo("ACTIVE");
        MerchantPayable.Payable payable = payableOf(merchant);
        assertThat(payable.payoutsReturned().minorUnits()).isEqualTo(27_00);
        assertThat(payable.position().minorUnits()).isEqualTo(27_00);
        assertThat(returnRecords(waiting.payout())).isEqualTo(records(1));

        matchUntilQuiet();
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        assertThat(unexplained(GBP)).isEqualTo(baseline);
        assertTrialBalance();
    }

    @Test
    @Order(11)
    @DisplayName("the close first: its uncommitted close holds the payable FOR UPDATE, the worker"
            + " waits on its FOR SHARE, and once the close commits the worker finds the payable"
            + " CLOSED - not applicable, nothing written, the line still waiting for grace")
    void theCloseFirstLeavesTheWorkerNothing() throws Exception {
        Money baseline = unexplained(USD);
        Waiting waiting = waitingReturn(USD, "19.00", "19.00");
        Funded merchant = waiting.merchant();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Callable<PayoutReturnSweep.SweepResult> tick = payoutReturnSweep::sweep;
        PayoutReturnSweep.SweepResult swept;
        try (Connection closer = DatabaseRoles.application()) {
            closer.setAutoCommit(false);
            try {
                assertThat(closeHeld(closer, merchant).status()).isEqualTo(MerchantStatus.CLOSED);
                Future<PayoutReturnSweep.SweepResult> worker = pool.submit(tick);
                awaitWaiting("FROM ledger.ledger_account");
                assertThat(worker.isDone())
                        .as("the worker waits on the close's lock on the payable")
                        .isFalse();
                closer.commit();
                swept = worker.get(2, TimeUnit.MINUTES);
            } finally {
                closer.rollback();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(swept)
                .as("PAYABLE_NOT_POSTABLE on the row the close committed")
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 0, 1, 0, 0));
        assertNothingWritten(waiting.payout());
        assertThat(itemStatus(waiting.item())).isEqualTo("UNMATCHED");
        assertThat(one("SELECT status FROM merchant.merchant WHERE id = ?", merchant.id().value()))
                .isEqualTo(MerchantStatus.CLOSED.name());
        assertThat(accountStatus(merchant.payable())).isEqualTo("CLOSED");
        assertThat(payableOf(merchant).position().isZero()).isTrue();

        // The fallback, so the line leaves nothing waiting: a person's, at grace.
        expireGrace(waiting.item());
        matchUntilQuiet();
        assertThat(itemStatus(waiting.item())).isEqualTo("PARKED");
        assertThat(one("SELECT type || ':' || cause FROM reconciliation.break WHERE"
                        + " external_item_id = ?", waiting.item()))
                .isEqualTo("REVERSAL_MISMATCH:RETURN_NOT_APPLICABLE");
        assertThat(unexplained(USD)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- the walk

    @Test
    @Order(12)
    @DisplayName("with a page of ONE, a waiting return that cannot apply (NO_PAYOUT) first in"
            + " claimant order never starves the routine one behind it: one tick walks both -"
            + " candidates 2, applied 1, not applicable 1")
    void aReturnThatCannotApplyNeverStarvesTheOneBehindIt() throws Exception {
        Money baseline = unexplained(EUR);
        Funded merchant = funded(EUR, "90.00");
        UUID payout = paid(merchant, "33.00");
        settled(EUR, payout);
        UUID batch = accepted(report(EUR)
                .with(SimulatedPayoutReports.Entry.returned("12.34", "po_nobody" + letters(),
                        "pyo-" + IDS.next()))
                .with(returnedLine(payout)));
        matchUntilQuiet();
        List<UUID> items = returnedItems(batch);
        assertThat(items).hasSize(2);
        UUID nobody = items.get(0);
        UUID routine = items.get(1);
        assertThat(itemStatus(nobody)).isEqualTo("UNMATCHED");
        assertThat(itemStatus(routine)).isEqualTo("UNMATCHED");

        PayoutReturnSweep narrow =
                new PayoutReturnSweep(
                        new MerchantTransactions(
                                new TransactionTemplate(transactionManager), dataSource),
                        waitingPayoutReturns,
                        settlementBatchStore,
                        payoutReturns,
                        1);
        assertThat(narrow.sweep())
                .as("the keyset walk passes the head it cannot apply")
                .isEqualTo(new PayoutReturnSweep.SweepResult(2, 1, 1, 0, 0));
        assertThat(returnRecords(payout)).isEqualTo(records(1));
        assertThat(one("SELECT external_item_ref FROM merchant.payout_return WHERE payout_id = ?",
                        payout))
                .isEqualTo(routine);
        assertThat(itemStatus(nobody)).isEqualTo("UNMATCHED");

        matchUntilQuiet();
        assertThat(itemStatus(routine)).isEqualTo("MATCHED");
        expireGrace(nobody);
        matchUntilQuiet();
        assertThat(itemStatus(nobody)).isEqualTo("PARKED");
        assertThat(one("SELECT type || ':' || cause FROM reconciliation.break WHERE"
                        + " external_item_id = ?", nobody))
                .as("a return naming nothing the platform knows is the desk's")
                .isEqualTo("UNKNOWN_EXTERNAL:GRACE_EXPIRED");
        assertThat(unexplained(EUR)).isEqualTo(baseline);
        assertTrialBalance();
    }

    // ----------------------------------------------------------------- the schema's ranks

    // ----------------------------------------------------------------- the register rebuilds

    @Test
    @Order(13)
    @DisplayName("the register rebuilds from the books alone: a return's PAYOUT_RETURN emptied"
            + " as the platform's root comes back from the opening-position backfill"
            + " substance-equal and keyless, a second run adds nothing, and the rematch then"
            + " allocates its waiting line")
    void theReturnsExpectationRebuildsFromTheBooks() throws Exception {
        Money baseline = unexplained(GBP);
        Waiting waiting = waitingReturn(GBP, "60.00", "16.00");
        String payout = waiting.payout().toString();
        assertThat(payoutReturnSweep.sweep())
                .isEqualTo(new PayoutReturnSweep.SweepResult(1, 1, 0, 0, 0));
        String substance =
                "SELECT kind || ':' || posting_key || ':' || source_id::text || ':'"
                        + " || position_purpose || ':' || ledger_account_id::text || ':'"
                        + " || direction || ':' || amount_minor::text || ':' || currency"
                        + " || ':' || scale::text || ':' || posting_date::text || ':'"
                        + " || expected_by::text || ':' || rule_set_id::text || ':' || status"
                        + " FROM reconciliation.expectation"
                        + " WHERE kind = 'PAYOUT_RETURN' AND operation_ref = ?";
        Object before = one(substance, payout);
        assertThat(before).isNotNull();
        Object expectation =
                one("SELECT id FROM reconciliation.expectation WHERE kind = 'PAYOUT_RETURN'"
                        + " AND operation_ref = ?", payout);

        // Emptied as the platform's own root - history's shape, not a production path
        // (SettlementAcceptanceDatabaseTest's block). Unheld: nothing allocated it yet.
        try (Connection root = DatabaseRoles.bootstrap()) {
            root.setAutoCommit(false);
            executeOn(root, "ALTER TABLE reconciliation.expectation_event DISABLE TRIGGER"
                    + " expectation_event_is_append_only");
            executeOn(root, "ALTER TABLE reconciliation.expectation DISABLE TRIGGER"
                    + " expectation_is_never_deleted");
            try {
                executeOn(root, "DELETE FROM reconciliation.expectation_event WHERE"
                        + " expectation_id = ?", expectation);
                executeOn(root, "DELETE FROM reconciliation.expectation WHERE id = ?",
                        expectation);
            } finally {
                executeOn(root, "ALTER TABLE reconciliation.expectation ENABLE TRIGGER"
                        + " expectation_is_never_deleted");
                executeOn(root, "ALTER TABLE reconciliation.expectation_event ENABLE TRIGGER"
                        + " expectation_event_is_append_only");
            }
            root.commit();
        }
        String present = "SELECT count(*) FROM reconciliation.expectation WHERE kind ="
                + " 'PAYOUT_RETURN' AND operation_ref = ?";
        assertThat(count(present, payout)).isZero();

        OpeningPosition.Adopted adopted = backfill();
        assertThat(adopted.returns()).as("every recorded return walked").isPositive();
        assertThat(one(substance, payout))
                .as("re-derived from the return's own posting, exactly as the applier opened it")
                .isEqualTo(before);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key k JOIN"
                        + " reconciliation.expectation e ON e.id = k.expectation_id WHERE"
                        + " e.kind = 'PAYOUT_RETURN' AND e.operation_ref = ?", payout))
                .as("keyless, as live: only the operation-anchored rule reaches it")
                .isZero();
        backfill();
        assertThat(count(present, payout)).as("a second run adds nothing").isEqualTo(1);

        matchUntilQuiet();
        assertThat(itemStatus(waiting.item())).isEqualTo("MATCHED");
        assertThat(expectationStatus(ExpectationKind.PAYOUT_RETURN, waiting.payout()))
                .isEqualTo("SETTLED");
        assertThat(unexplained(GBP)).isEqualTo(baseline);
        assertTrialBalance();
    }

    /** The opening-position backfill, as the platform, under a fresh key. */
    private OpeningPosition.Adopted backfill() {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return openingPosition.record(
                    "rebuild-" + UUID.randomUUID(), "the register rebuilt from the books");
        }
    }

    @Test
    @Order(14)
    @DisplayName("the schema refuses for every writer what the applier refuses: another amount"
            + " (the composite key), a payout not COMPLETED (the trigger), a second return (the"
            + " unique), and any edit or delete (the grant, then the append-only trigger)")
    void theSchemaRefusesWhatTheApplierRefuses() throws Exception {
        Funded merchant = funded(EUR, "50.00");
        UUID completed = paid(merchant, "11.00");
        UUID failed = answered(merchant, "7.00", DECLINED, MerchantPayoutStatus.FAILED);
        UUID standing;
        try (Connection app = DatabaseRoles.application()) {
            DatabaseRoles.assertCannotBypassPrivileges(app);
            assertThatThrownBy(() -> insertReturn(app, completed, 10_99))
                    .as("the composite key: another amount is not this payout's return")
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23503");
            assertThatThrownBy(() -> insertReturn(app, failed, 7_00))
                    .as("the trigger: only a COMPLETED payout returns, its money notwithstanding")
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23514");
            standing = insertReturn(app, completed, 11_00);
            assertThatThrownBy(() -> insertReturn(app, completed, 11_00))
                    .as("one return per payout")
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "23505");
            assertThatThrownBy(() -> executeOn(app, "UPDATE merchant.payout_return SET"
                            + " returned_on = returned_on + 1 WHERE id = ?", standing))
                    .as("the application role holds SELECT and INSERT only")
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
            assertThatThrownBy(() -> executeOn(app,
                            "DELETE FROM merchant.payout_return WHERE id = ?", standing))
                    .isInstanceOf(SQLException.class)
                    .hasFieldOrPropertyWithValue("SQLState", "42501");
        }
        // The trigger binds the writers the grant does not: the schema's owner.
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(() -> executeOn(migrator, "UPDATE merchant.payout_return SET"
                            + " returned_on = returned_on + 1 WHERE id = ?", standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("born once and never edited or deleted");
            assertThatThrownBy(() -> executeOn(migrator,
                            "DELETE FROM merchant.payout_return WHERE id = ?", standing))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("born once and never edited or deleted");
        }
        assertThat(one("SELECT amount_minor::text || ':' || (returned_on = value_date)::text"
                        + " FROM merchant.payout_return WHERE id = ?", standing))
                .as("the admitted fact stands exactly as born - neither refused edit landed")
                .isEqualTo("1100:true");
    }

    // ----------------------------------------------------------------- merchants and payouts

    private record Funded(MerchantId id, LedgerAccountId payable, CurrencyCode currency) {}

    /** A payout's return waiting for its worker: the payout settled, its RETURNED line read. */
    private record Waiting(Funded merchant, UUID payout, UUID batch, UUID item) {}

    /**
     * A merchant with an effective destination and a funded payable — the funding a
     * capture-shaped entry (DR settlement clearing / CR payable), as the payout suites fund
     * their merchants.
     */
    private Funded funded(CurrencyCode currency, String amount) throws Exception {
        MerchantId merchant = MerchantId.next(IDS);
        OffsetDateTime created =
                OffsetDateTime.ofInstant(
                        Instant.now(CLOCK).truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        execute("INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name,"
                        + " settlement_currency, status, created_at, status_changed_at) VALUES"
                        + " (?, ?, 'Acme GmbH', 'Acme', ?, 'ACTIVE', ?, ?)",
                merchant.value(), UUID.randomUUID(), currency.code(), created, created);
        execute("INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference,"
                        + " display_suffix, status, proposed_by, proposed_at, proposal_reason,"
                        + " approved_by, approved_at, cooling_off_until, effective_at) VALUES"
                        + " (?, ?, ?, '3000', 'EFFECTIVE', 'fixture-a', now() - interval '4 days',"
                        + " 'fixture', 'fixture-b', now() - interval '4 days', now() - interval"
                        + " '1 day', now() - interval '1 hour')",
                IDS.next(), merchant.value(),
                "pdr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        LedgerAccountId payable =
                asOperator(uow -> ledgerAccountStore
                        .createOrConverge(
                                uow,
                                LedgerAccount.owned(IDS, CLOCK, AccountType.LIABILITY,
                                        AccountPurpose.MERCHANT_PAYABLE, currency,
                                        merchant.value()))
                        .account()
                        .id());
        Money funding = money(amount, currency);
        asOperator(uow -> {
            LedgerAccount clearing =
                    new ChartOfAccounts<>(ledgerAccountStore)
                            .resolve(uow, AccountPurpose.SETTLEMENT_CLEARING, currency);
            LocalDate today = LocalDate.now(CLOCK);
            UUID reference = UUID.randomUUID();
            PostingResult posted =
                    postingService.post(
                            uow,
                            new PostingCommand(
                                    "payout-return-fixture:" + reference, today, today,
                                    reference.toString(),
                                    List.of(new JournalLine(
                                                    clearing.id(), Direction.DEBIT, funding),
                                            new JournalLine(
                                                    payable, Direction.CREDIT, funding))));
            // A capture's shape opens a capture's expectation, through the live port: the
            // clearing line explained, so this suite may share a container with every suite
            // that asserts SETTLEMENT_CLEARING absolutely. The key is letters only - no digit
            // run of card length can meet the screen by chance.
            settlementExpectations.open(
                    uow,
                    new SettlementExpectations.Opening(
                            SettlementExpectations.Kind.CARD_CAPTURE,
                            reference.toString(),
                            "payout-return-fixture:" + reference,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            clearing.id(),
                            posted.entryId(),
                            Optional.empty(),
                            List.of(new SettlementExpectations.Key(
                                    SettlementExpectations.ReferenceKind.PSP_CAPTURE_REF,
                                    "fx_" + letters())),
                            Correlation.startingWith(CorrelationId.generate(IDS))));
            return posted;
        });
        return new Funded(merchant, payable, currency);
    }

    /** A payout through the REAL flow, paid by the simulated provider: COMPLETED, expected. */
    private UUID paid(Funded merchant, String amount) {
        return answered(merchant, amount, PAID, MerchantPayoutStatus.COMPLETED);
    }

    /** A payout through the REAL flow, the provider answering {@code answer}. */
    private UUID answered(
            Funded merchant, String amount, String answer, MerchantPayoutStatus expected) {
        provider.reset();
        provider.succeedsWith(PATH, 200, answer);
        try {
            MerchantPayouts.Initiated initiated =
                    asMerchant(merchant, () -> payouts.initiate(
                            new MerchantPayouts.InitiateCommand(
                                    merchant.id(), money(amount, merchant.currency()),
                                    "pay-" + UUID.randomUUID(), Optional.empty(),
                                    Optional.empty())));
            assertThat(initiated.status()).isEqualTo(expected);
            return initiated.payout().value();
        } finally {
            provider.reset();
            provider.succeedsWith(PATH, 200, PAID);
        }
    }

    /**
     * The resolution sweep, answering UNKNOWN payouts at once and leaving every DISPATCHED one
     * alone — a takeover here would re-send another suite's crashed payout.
     */
    private MerchantPayoutResolution resolution() {
        return new MerchantPayoutResolution(
                new MerchantTransactions(new TransactionTemplate(transactionManager), dataSource),
                merchantPayoutStore,
                new SimulatedPayoutProvider(
                        URI.create(provider.baseUrl()), Duration.ofSeconds(2), new byte[32]),
                outcomes,
                evidence,
                IDS,
                CLOCK,
                Duration.ofDays(3650),
                Duration.ZERO,
                1000);
    }

    /**
     * The routine setup: a merchant funded, a payout paid, its SETTLED line accepted and matched,
     * then its RETURNED line accepted and matched — waiting, with no break.
     */
    private Waiting waitingReturn(CurrencyCode currency, String funding, String amount)
            throws Exception {
        Funded merchant = funded(currency, funding);
        UUID payout = paid(merchant, amount);
        settled(currency, payout);
        UUID batch = accepted(report(currency).with(returnedLine(payout)));
        matchUntilQuiet();
        UUID item = onlyReturnedItem(batch);
        assertThat(itemStatus(item))
                .as("no PAYOUT_RETURN stands yet: the return waits")
                .isEqualTo("UNMATCHED");
        assertThat(breaksOn(item)).as("no break raised by the matcher").isZero();
        assertNothingWritten(payout);
        return new Waiting(merchant, payout, batch, item);
    }

    /** Each payout's SETTLED line accepted and matched: its MERCHANT_PAYOUT settled. */
    private void settled(CurrencyCode currency, UUID... executed) throws SQLException {
        SimulatedPayoutReports report = report(currency);
        for (UUID payout : executed) {
            report.with(settledLine(payout, "0.25"));
        }
        accepted(report);
        matchUntilQuiet();
        for (UUID payout : executed) {
            assertThat(expectationStatus(ExpectationKind.MERCHANT_PAYOUT, payout))
                    .isEqualTo("SETTLED");
        }
    }

    /** Evidence as the worker reads it off the item, its batch and the payout's references. */
    private PayoutReturns.ReturnEvidence evidenceOf(Waiting waiting, int daysLater)
            throws SQLException {
        Object[] stored = row("SELECT provider_reference, provider_idempotency_reference,"
                + " amount_minor, currency, scale FROM merchant.merchant_payout WHERE id = ?",
                waiting.payout());
        LocalDate acceptedOn =
                date("SELECT accepted_on FROM settlement.batch WHERE id = ?", waiting.batch());
        LocalDate settlementDate =
                date("SELECT COALESCE(settlement_date, business_date) FROM"
                        + " reconciliation.external_item WHERE id = ?", waiting.item());
        return new PayoutReturns.ReturnEvidence(
                waiting.item(),
                Optional.of((String) stored[0]),
                Optional.of((String) stored[1]),
                Money.ofPersisted(
                        ((Number) stored[2]).longValue(),
                        CurrencyCode.of(((String) stored[3]).strip()),
                        ((Number) stored[4]).intValue()),
                acceptedOn.plusDays(daysLater),
                settlementDate.plusDays(daysLater),
                flow());
    }

    /** Evidence no item carries, dated today — for the direct applier's refusals. */
    private static PayoutReturns.ReturnEvidence looseEvidence(
            Optional<String> theirs, Optional<String> ours, Money amount) {
        LocalDate today = LocalDate.now(CLOCK);
        return new PayoutReturns.ReturnEvidence(
                UUID.randomUUID(), theirs, ours, amount, today, today, flow());
    }

    /** The worker's item transaction on the caller's OPEN connection, as the platform. */
    private Optional<PayoutReturns.Outcome> applyHeld(Connection worker, UUID item) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return payoutReturnSweep.applyOne(worker, item);
        }
    }

    /** A merchant close on the caller's OPEN connection, as an operator. */
    private Merchant closeHeld(Connection closer, Funded merchant) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER))) {
            return administration.close(closer, merchant.id(), "the merchant asked to close");
        }
    }

    /** The report's line for a payout, read off {@code merchant.merchant_payout}. */
    private static SimulatedPayoutReports.Entry settledLine(UUID payout, String fee)
            throws SQLException {
        Object[] stored = row("SELECT provider_reference, provider_idempotency_reference,"
                + " amount_minor, scale FROM merchant.merchant_payout WHERE id = ?", payout);
        return SimulatedPayoutReports.Entry.settled(
                decimal(stored), fee, (String) stored[0], (String) stored[1]);
    }

    private static SimulatedPayoutReports.Entry returnedLine(UUID payout) throws SQLException {
        Object[] stored = row("SELECT provider_reference, provider_idempotency_reference,"
                + " amount_minor, scale FROM merchant.merchant_payout WHERE id = ?", payout);
        return SimulatedPayoutReports.Entry.returned(
                decimal(stored), (String) stored[0], (String) stored[1]);
    }

    private static String decimal(Object[] stored) {
        return BigDecimal.valueOf(((Number) stored[2]).longValue(), ((Number) stored[3]).intValue())
                .setScale(((Number) stored[3]).intValue(), RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    private static String payoutStatus(UUID payout) throws SQLException {
        return (String) one("SELECT status FROM merchant.merchant_payout WHERE id = ?", payout);
    }

    private static String accountStatus(LedgerAccountId account) throws SQLException {
        return (String) one("SELECT status FROM ledger.ledger_account WHERE id = ?",
                account.value());
    }

    private static Money money(String amount, CurrencyCode currency) {
        return Money.of(new BigDecimal(amount), currency);
    }

    private static MerchantPayable.Payable payableOf(Funded merchant) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown())
                    .payablesOf(app, merchant.id()).stream()
                    .filter(payable -> payable.position().currency().equals(merchant.currency()))
                    .findFirst()
                    .orElseThrow();
        }
    }

    // ----------------------------------------------------------------- the return's records

    /** The six records of one payout's return, each counted in its own table. */
    private static Map<String, Long> returnRecords(UUID payout) throws SQLException {
        String postingKey = RETURN_KEY + payout;
        List<Long> counted =
                List.of(
                        count("SELECT count(*) FROM merchant.payout_return WHERE payout_id = ?",
                                payout),
                        count("SELECT count(*) FROM ledger.journal_entry WHERE"
                                        + " idempotency_scope = ?",
                                PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey),
                        count("SELECT count(*) FROM platform.idempotency_record WHERE scope = ?"
                                        + " AND idempotency_key = ?",
                                PostingService.IDEMPOTENCY_SCOPE, postingKey),
                        ClearingLineCopies.expectationsOf(
                                ExpectationKind.PAYOUT_RETURN, payout.toString()),
                        count("SELECT count(*) FROM platform.outbox_event WHERE event_type = ?"
                                        + " AND aggregate_id = ?",
                                RETURNED_EVENT, payout),
                        count("SELECT count(*) FROM platform.audit_record WHERE operation = ?"
                                        + " AND target_id = ?",
                                RETURN_AUDIT, payout.toString()));
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

    private static void assertNothingWritten(UUID payout) throws SQLException {
        assertThat(returnRecords(payout))
                .as("an outcome other than APPLIED writes nothing at all")
                .isEqualTo(records(0));
    }

    private static UUID returnEntry(UUID payout) throws SQLException {
        UUID entry = (UUID) one("SELECT id FROM ledger.journal_entry WHERE idempotency_scope = ?",
                PostingService.IDEMPOTENCY_SCOPE + ":" + RETURN_KEY + payout);
        assertThat(entry).as("the return's own entry").isNotNull();
        return entry;
    }

    private static List<String> fieldNames(String json) {
        Matcher names = Pattern.compile("\"([A-Za-z]+)\"\\s*:").matcher(json);
        List<String> found = new ArrayList<>();
        while (names.find()) {
            found.add(names.group(1));
        }
        return found;
    }

    private static UUID insertReturn(Connection connection, UUID payout, long amountMinor)
            throws SQLException {
        UUID id = IDS.next();
        executeOn(connection,
                "INSERT INTO merchant.payout_return (id, payout_id, amount_minor, currency,"
                        + " scale, external_item_ref, journal_entry_id, returned_on, value_date,"
                        + " recorded_at) VALUES (?, ?, ?, 'EUR', 2, ?, ?, CURRENT_DATE,"
                        + " CURRENT_DATE, now())",
                id, payout, amountMinor, UUID.randomUUID(), UUID.randomUUID());
        return id;
    }

    // ----------------------------------------------------------------- evidence and matching

    private static SimulatedPayoutReports report(CurrencyCode currency) {
        return new SimulatedPayoutReports("PAYDAY-" + marker(), currency.code(),
                LocalDate.now(CLOCK).toString(), "PAY-REM-19" + digits());
    }

    private FileReception.Result receive(byte[] bytes) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem();
                CorrelationContext.Scope scope = CorrelationContext.enter(flow())) {
            return settlementTransactionRunner.inTransaction(
                    uow ->
                            reception.receive(
                                    uow,
                                    new FileReception.Delivery(
                                            PAYOUT_SOURCE,
                                            DeliveryChannel.PULL,
                                            bytes,
                                            Optional.empty(),
                                            Actor.SYSTEM,
                                            SettlementAuditAction.SETTLEMENT_FILE_UPLOADED,
                                            CorrelationContext.current().orElseThrow())));
        }
    }

    private UUID accepted(SimulatedPayoutReports report) throws SQLException {
        FileReception.Result result = receive(report.render());
        assertThat(result).isInstanceOf(FileReception.Result.New.class);
        UUID fileId = ((FileReception.Result.New) result).fileId();
        parsing.sweep();
        acceptance.sweep();
        assertThat(one("SELECT status || ':' || coalesce(rejection_code, '-') FROM"
                        + " settlement.file WHERE id = ?", fileId))
                .isEqualTo("ACCEPTED:-");
        return (UUID) one("SELECT id FROM settlement.batch WHERE file_id = ?", fileId);
    }

    private static List<UUID> returnedItems(UUID batch) throws SQLException {
        List<UUID> items = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT i.id FROM reconciliation.external_item i JOIN"
                                        + " reconciliation.reconciliation_batch r ON r.id ="
                                        + " i.run_id WHERE r.batch_id = ? AND i.line_type ="
                                        + " 'PAYOUT_RETURNED' ORDER BY i.line_no")) {
            read.setObject(1, batch);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    items.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return items;
    }

    private static UUID onlyReturnedItem(UUID batch) throws SQLException {
        List<UUID> items = returnedItems(batch);
        assertThat(items).as("the report's one RETURNED line").hasSize(1);
        return items.get(0);
    }

    private static String itemStatus(UUID item) throws SQLException {
        return (String) one("SELECT status FROM reconciliation.external_item WHERE id = ?", item);
    }

    private static long breaksOn(UUID item) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?", item);
    }

    private static long suspenseOn(UUID item) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);
    }

    private static String expectationStatus(ExpectationKind kind, UUID operation)
            throws SQLException {
        return (String) one("SELECT status FROM reconciliation.expectation WHERE kind = ? AND"
                + " operation_ref = ?", kind.name(), operation.toString());
    }

    /** The ONE allocation of the item to a PAYOUT_RETURN: its decision's origin and key. */
    private static String returnAllocation(UUID item) throws SQLException {
        String sql = " FROM reconciliation.allocation a JOIN reconciliation.match_decision d ON"
                + " d.id = a.decision_id JOIN reconciliation.expectation e ON e.id ="
                + " a.expectation_id WHERE a.external_item_id = ? AND e.kind = 'PAYOUT_RETURN'";
        assertThat(count("SELECT count(*)" + sql, item))
                .as("one allocation to the return")
                .isEqualTo(1);
        return (String) one("SELECT d.origin || ':' || d.matched_key_kind || ':' ||"
                + " a.amount_minor::text" + sql, item);
    }

    private void matchUntilQuiet() {
        for (int i = 0; i < 4; i++) {
            matching.sweep();
        }
    }

    /** The stored window moved, never the clock: expiry stays a database-clock fact. */
    private static void expireGrace(UUID item) throws SQLException {
        execute("UPDATE reconciliation.external_item SET grace_until = now() - interval '1 hour'"
                + " WHERE id = ?", item);
    }

    private static List<String> entryLines(UUID entryId) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT a.purpose, l.direction, l.amount_minor FROM"
                                        + " ledger.journal_line l JOIN ledger.ledger_account a"
                                        + " ON a.id = l.ledger_account_id WHERE"
                                        + " l.entry_id = ?")) {
            read.setObject(1, entryId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    lines.add(rows.getString(1) + ":" + rows.getString(2) + ":"
                            + rows.getLong(3));
                }
            }
        }
        return lines;
    }

    // ----------------------------------------------------------------- the proofs

    private PositionProof.Report proven() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                return proof.sweep(app);
            } finally {
                app.rollback();
            }
        }
    }

    /** PAYOUT_CLEARING's identity difference: balance − (open remainders − open items). */
    private Money unexplained(CurrencyCode currency) throws SQLException {
        PositionProof.PositionVerdict verdict =
                proven().verdicts().stream()
                        .filter(v -> v.purpose() == AccountPurpose.PAYOUT_CLEARING
                                && v.currency().equals(currency))
                        .findFirst()
                        .orElseThrow();
        return verdict.ledgerBalance()
                .minus(verdict.openRemainders().minus(verdict.openItems()));
    }

    private long unattributedOnPayoutClearing() throws SQLException {
        return proven().unattributedByPurpose().getOrDefault(AccountPurpose.PAYOUT_CLEARING, 0L);
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

    // ----------------------------------------------------------------- the desk

    /** A four-eyes TRANSFER_TO_ACCOUNT of the break's parked value; the approved entry. */
    private UUID fourEyesTransfer(UUID breakId, LedgerAccountId target) throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session approver = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        HttpResponse<String> proposed =
                post(proposer.token(), "/breaks/" + breakId + "/resolutions",
                        "pay-" + UUID.randomUUID(),
                        "{\"kind\":\"TRANSFER_TO_ACCOUNT\",\"reasonCode\":\"FUNDS_ATTRIBUTED\","
                                + "\"narrative\":\"the returned payout goes to the merchant the"
                                + " desk named\",\"targetAccountId\":\"" + target.value() + "\"}");
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        HttpResponse<String> approved =
                post(approver.token(), "/resolutions/" + field(proposed.body(), "resolutionId")
                        + "/approval", null, null);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        return UUID.fromString(field(approved.body(), "journalEntryId"));
    }

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/v1/operator/reconciliation" + path))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as(name + " in " + json).isTrue();
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
                        + " VALUES (?, 'PERSON', 'Payout Return Desk Person', now())",
                party);
        execute("INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                        + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                        + " now(), now())",
                identity, party,
                "pr" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session.Draft session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    private <R> R asMerchant(Funded merchant, Supplier<R> work) {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(merchant.id().value().toString(), ActorType.MERCHANT))) {
            return work.get();
        }
    }

    private <R> R asOperator(Function<Connection, R> work) throws SQLException {
        try (CorrelationContext.Scope scope = CorrelationContext.enter(flow());
                SecurityContext.Scope acting =
                        SecurityContext.enter(
                                new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER))) {
            return committed(work);
        }
    }

    private <R> R asPlatform(Function<Connection, R> work) throws SQLException {
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
            } catch (RuntimeException refused) {
                app.rollback();
                throw refused;
            }
        }
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t19-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t19-cause"));
    }

    /** Letters and digits, a letter first: never a digit run of card length. */
    private static String marker() {
        return "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 9).toUpperCase();
    }

    private static String digits() {
        return String.valueOf(10_000_000 + RANDOMNESS.nextInt(89_999_999));
    }

    /** Letters only: a provider reference no digit run can make look like an instrument. */
    private static String letters() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
        StringBuilder letters = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            letters.append(alphabet.charAt(RANDOMNESS.nextInt(alphabet.length())));
        }
        return letters.toString();
    }

    /**
     * Waits until some session is blocked on a lock while running a statement containing
     * {@code fragment} — the deterministic way to know a racer reached its lock, never a sleep
     * standing in for one.
     */
    private static void awaitWaiting(String fragment) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query LIKE ?",
                    "%" + fragment + "%") > 0) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no session waited on a lock running: " + fragment);
    }

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return work.call();
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

    private static LocalDate date(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("a row for " + sql).isTrue();
                return row.getObject(1, LocalDate.class);
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            executeOn(app, sql, args);
        }
    }

    private static void executeOn(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    /** A count on the caller's own connection, from inside a port - unchecked for the lambda. */
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

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static Object[] row(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("a row for " + sql).isTrue();
                Object[] values = new Object[result.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = result.getObject(i + 1);
                }
                return values;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            statement.setObject(i + 1, args[i]);
        }
    }
}
