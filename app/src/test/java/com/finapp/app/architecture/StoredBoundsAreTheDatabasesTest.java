package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.checkout.CheckoutExpirySweeper;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionStore;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.RecoveryRequest;
import com.finapp.identity.RecoveryRequestStore;
import com.finapp.merchant.FeeScheduleStore;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationEffectuation;
import com.finapp.merchant.PayoutDestinationStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * No stored bound is judged against an instance's clock (`P15-TSK-038`, {@code X-TSK-008}'s first family, ADR-0014).
 *
 * <p>{@code SessionTimeIsTheDatabasesTest}'s shape, for the five stores that still judged a stored bound against an instant
 * one instance had read from its own {@code Clock}: recovery tokens, contact-channel verification, checkout expiry, the card
 * fee version in force and payout-destination cooling-off. A predicate on a caller's clock invites the assumption straight
 * back ("for testability"), so the shape itself is refused, in the stores, on the ports and in the services above them.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li><strong>The ports.</strong> A temporal parameter on one of the five ports must be one of the two classified here.
 *   <li><strong>The statements.</strong> No named bound of the five is compared with a bind parameter, and each judging or
 *       stamping statement reads the database's clock in the statement.
 *   <li><strong>The answers.</strong> The services above the stores do not read a {@code Clock} where the statement decided
 *       on the database's: an aggregate's judgement takes the reading, the sweepers hold no clock at all, and a reading the
 *       aggregate needs is taken after the row's lock.
 *   <li><strong>Every store.</strong> Every production file's comparison of a timestamp column with a bind parameter is in
 *       the {@link #CENSUS} with what it is, so a new one fails until somebody has asked whose instant that {@code ?} is.
 *       Timestamp columns are read from the migrations, statements from the string literals (the {@code OwnershipIsScopedTest}
 *       lesson: prose matched, so comments do not count).
 * </ul>
 *
 * <h2>What this rule cannot see</h2>
 *
 * <ul>
 *   <li><strong>Whose instant a {@code ?} is.</strong> Text cannot say. The census records a person's reading of each caller,
 *       per class and column, not per statement, so a second statement in a class on a column already censused is not asked
 *       about, and a caller changed from a database reading to an instance's clock is not seen.
 *   <li><strong>A bound judged in Java</strong> outside the named services here: an aggregate method taking an {@code Instant}
 *       is only as good as the {@code Instant} its caller read.
 *   <li><strong>Equality, and SQL it cannot read.</strong> Ordering comparisons and {@code BETWEEN} only, not {@code =} (an
 *       assignment reads the same); a statement assembled from non-literal text; a comparison inside a trigger or function.
 *   <li><strong>Behaviour.</strong> That PostgreSQL does what the text says, under skew and after a lock wait, is the database
 *       cases' claim ({@code StoredBoundsClockSkewDatabaseTest}), owed to {@code P15-TST-002} (C14).
 * </ul>
 */
@Tag("architecture")
@DisplayName("stored bounds are the database's (P15-TSK-038, X-TSK-008)")
class StoredBoundsAreTheDatabasesTest {

    private static final List<Class<?>> TEMPORAL =
            List.of(
                    Instant.class,
                    Clock.class,
                    InstantSource.class,
                    OffsetDateTime.class,
                    ZonedDateTime.class,
                    LocalDateTime.class,
                    java.util.Date.class,
                    java.sql.Timestamp.class);

    private static final List<Class<?>> PORTS =
            List.of(
                    RecoveryRequestStore.class,
                    ContactChannelStore.class,
                    CheckoutSessionStore.class,
                    FeeScheduleStore.class,
                    PayoutDestinationStore.class);

    /** The only temporal parameters the five ports keep, each with what it is. */
    private static final Map<String, String> PORT_INSTANTS =
            Map.of(
                    "FeeScheduleStore#findEffectiveVersion",
                    "BNPL's resolution: BnplPricing hands it its own databaseNow, read after the standing's locks",
                    "FeeScheduleStore#assign",
                    "business time, recorded as assigned_at and compared with nothing");

    /** The five stores and the columns that are a bound, a judgement on either being the defect. */
    private static final Map<String, Set<String>> STORES =
            Map.of(
                    "com.finapp.identity.JdbcRecoveryRequestStore", Set.of("expires_at", "initiated_at"),
                    "com.finapp.identity.JdbcContactChannelStore", Set.of("verification_expires_at"),
                    "com.finapp.checkout.JdbcCheckoutSessionStore", Set.of("expires_at"),
                    "com.finapp.merchant.JdbcFeeScheduleStore", Set.of("effective_from"),
                    "com.finapp.merchant.JdbcPayoutDestinationStore", Set.of("cooling_off_until"));

    /** The method in {@code JdbcFeeScheduleStore} that binds the reading BNPL's caller took on the database's clock. */
    private static final String BNPL_RESOLUTION = "findEffectiveVersion";

    private enum Kind {
        /** A period or cutoff somebody asked about (a report's window, a run's recorded cut, an as-of instant): history read. */
        ASKED_ABOUT,
        /** The value bound is the database's own clock, read in the same unit of work, or the row's own stored value. */
        THE_DATABASES,
        /** An instance's clock picks candidates or a meter's horizon, or paces a retry; a conditional write re-decides. */
        PACING,
        /** A rolling look-back from the deciding instance's clock over evidence: a count, and not a stored bound. */
        LOOKBACK,
        /** Decided elsewhere and named there, not this task's. */
        ELSEWHERE
    }

    /**
     * Every production comparison of a timestamp column with a bind parameter, by file and column, read from its callers.
     *
     * <p>A key this map does not hold, or a key nothing compares any more, turns {@link #everyStoreIsInTheCensus} red.
     */
    private static final Map<String, Kind> CENSUS = census();

    private static Map<String, Kind> census() {
        Map<String, Kind> census = new TreeMap<>();
        for (String asked :
                List.of(
                        // A report's or proof's window; a valuation or close run's own recorded cut; an as-of a person chose.
                        "FxReferenceValuationRates#observed_at",
                        "LedgerCounterpartyPositionReads#created_at",
                        "LedgerTrialBalanceReads#created_at",
                        "LendingFundedAsOfCutoffReads#created_at",
                        "Phase10Reports#decided_at",
                        "Phase10Reports#opened_at",
                        "Phase10Reports#requested_at",
                        "Phase9Reports#booked_at",
                        "Phase9Reports#created_at",
                        "Phase9Reports#recorded_at",
                        "PlatformJudgedCommands#occurred_at",
                        "JdbcBnplTermsStore#effective_from",
                        "JdbcBnplTermsStore#effective_to",
                        "JdbcCreditPolicyStore#effective_from",
                        "JdbcCreditPolicyStore#effective_to",
                        "JdbcScorecardStore#effective_from",
                        "JdbcScorecardStore#effective_to",
                        "JdbcLoanTermsStore#effective_from",
                        "JdbcLoanTermsStore#effective_to",
                        "JdbcDisputeStore#occurred_at",
                        "JdbcReportReadings#decided_at",
                        "JdbcReportReadings#raised_at",
                        "JdbcReportReadings#released_at",
                        "JdbcReportReadings#resolved_at",
                        "JdbcReportReadings#status_changed_at",
                        "JdbcRiskProofReads#disposed_at",
                        "JdbcRiskProofReads#retired_at",
                        // The velocity proof's own window start: reconciling what was counted, not a deadline.
                        "JdbcVelocityEvidence#posted_at",
                        "JdbcRiskAssessmentStore#velocity_cut_at",
                        // An operator's replay window over first publication; a window's or as-of's own stated limit.
                        "JdbcDeadLetterStore#first_published_at",
                        "JdbcDeadLetterStore#published_at",
                        "JdbcDisputeStore#opened_at",
                        "LendingFundedAsOfCutoffReads#opened_at",
                        "LendingReports#occurred_at")) {
            census.put(asked, Kind.ASKED_ABOUT);
        }
        for (String databases :
                List.of(
                        // BnplPricing's reading after its locks.
                        "JdbcFeeScheduleStore#effective_from",
                        // DatabaseTime.now(uow) minus the age (X-TSK-013), or the stored permit the caller read, compare-and-swap.
                        "JdbcMerchantPayoutStore#last_dispatched_at",
                        "JdbcPaymentAttemptStore#last_dispatched_at",
                        "JdbcRefundStore#last_dispatched_at",
                        "JdbcWithdrawalStore#last_dispatched_at",
                        "JdbcDisputeResponseStore#send_permit")) {
            census.put(databases, Kind.THE_DATABASES);
        }
        for (String pacing :
                List.of(
                        // The UNKNOWN arm of a sweep: an instance's cutoff over the row's own transition stamp (candidates).
                        "JdbcMerchantPayoutStore#created_at",
                        "JdbcPaymentAttemptStore#created_at",
                        "JdbcRefundStore#created_at",
                        "JdbcDisputeResponseStore#created_at",
                        "JdbcWithdrawalStore#created_at",
                        // A retry's backoff (settlement's own pacing), and a sweep's recent window.
                        "JdbcSettlementFileStore#next_parse_at",
                        "JdbcSettlementFileStore#next_accept_at",
                        "JdbcSettlementCycleReads#created_at",
                        "JdbcSettlementCycleReads#received_at",
                        // A gauge's horizon: how many deadlines fall inside it, a reading and no decision.
                        "JdbcDisputeStore#respond_by")) {
            census.put(pacing, Kind.PACING);
        }
        census.put("JdbcRiskSignalStore#observed_at", Kind.LOOKBACK);
        // X-TSK-005's: "the routing store is X-TSK-005's, not this item's" (BACKLOG, X-TSK-008).
        census.put("JdbcRoutingStore#effective_from", Kind.ELSEWHERE);
        return Collections.unmodifiableMap(census);
    }

    private static final Pattern BIND_RIGHT =
            Pattern.compile("([A-Za-z_][A-Za-z_0-9.]*)\\s*(<=|>=|<|>)\\s*\\?");
    private static final Pattern BIND_LEFT =
            Pattern.compile("\\?(?:::[A-Za-z]+)?\\s*(<=|>=|<|>)\\s*([A-Za-z_][A-Za-z_0-9.]*)");
    private static final Pattern BIND_BETWEEN =
            Pattern.compile("([A-Za-z_][A-Za-z_0-9.]*)\\s+BETWEEN\\s+\\?", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPRESSION_BOUND =
            Pattern.compile("\\)\\s*(<=|>=|<|>)\\s*\\?");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    /** A name immediately before the type: a column, or a function's parameter, which only widens what is read. */
    private static final Pattern TIMESTAMP_COLUMN =
            Pattern.compile(
                    "\\b([a-z_][a-z0-9_]*)\\s+timestamp(?:tz|\\s+with\\s+time\\s+zone)?\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> NOT_A_COLUMN = Set.of("as", "returns", "type", "of", "at", "time", "zone", "default");

    // ----------------------------------------------------------------- the ports

    @Test
    @DisplayName("every temporal parameter on the five ports is classified, and a bound is handed none")
    void thePortsHandNoBoundACallersClock() {
        Set<String> kept = new TreeSet<>();
        for (Class<?> port : PORTS) {
            for (Method method : port.getDeclaredMethods()) {
                if (method.isSynthetic() || temporalParametersOf(method).isEmpty()) {
                    continue;
                }
                String key = port.getSimpleName() + "#" + method.getName();
                assertThat(PORT_INSTANTS)
                        .as(key + " hands a store a temporal parameter nobody has classified: it decides or stamps a"
                                + " bound (then the database's clock does it) or it is business time, and say so here")
                        .containsKey(key);
                kept.add(key);
            }
        }
        assertThat(kept).as("the classification names nothing the ports no longer hold").isEqualTo(new TreeSet<>(PORT_INSTANTS.keySet()));
    }

    @Test
    @DisplayName("the aggregates' deadline edges take the database's reading, never a Clock")
    void theAggregatesTakeNoClockForADeadline() {
        for (String method : List.of("open", "confirm", "expire", "hasExpired")) {
            assertThat(parameterTypes(CheckoutSession.class, method))
                    .as("CheckoutSession." + method + " judges or stamps a deadline the database stamped")
                    .doesNotContain(Clock.class);
        }
        for (String method : List.of("approve", "effect", "supersede")) {
            assertThat(parameterTypes(PayoutDestination.class, method))
                    .as("PayoutDestination." + method + " judges or pins a cooling-off the database's clock decides")
                    .doesNotContain(Clock.class);
        }
        assertThat(Arrays.stream(RecoveryRequest.class.getDeclaredMethods())
                        .filter(method -> !method.isSynthetic())
                        .filter(method -> !temporalParametersOf(method).isEmpty())
                        .map(Method::getName))
                .as("a recovery request has no Java predicate on a caller's clock: X-TSK-007 removed Session's twin for this reason")
                .isEmpty();
    }

    // ----------------------------------------------------------------- the statements

    @Test
    @DisplayName("no bound in the five stores' SQL is compared with a bind parameter")
    void noBoundIsComparedWithACallersInstant() {
        for (Map.Entry<String, Set<String>> store : STORES.entrySet()) {
            String source = readSourceOf(store.getKey());
            if (store.getKey().endsWith("JdbcFeeScheduleStore")) {
                // BNPL's: it binds the reading its one caller took on the database's clock, pinned below.
                source = source.replace(methodSource(source, BNPL_RESOLUTION), "");
            }
            String literals = literalsIn(source);
            assertThat(literals).as(store.getKey() + ": the rule read real statements").containsIgnoringCase("select");
            Set<String> bounds = new TreeSet<>();
            for (Comparison comparison : comparisonsIn(literals)) {
                if (store.getValue().contains(comparison.column())) {
                    bounds.add(comparison.text());
                }
            }
            assertThat(bounds)
                    .as(store.getKey() + ": a bound compared with a bind parameter is a caller's clock deciding it,"
                            + " the defect P15-TSK-038 removed")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("BNPL's bound parameter stays the database's: findEffectiveVersion's one caller binds its own databaseNow")
    void theOneBoundParameterIsTheDatabasesReading() {
        String pricing = readSourceOf("com.finapp.merchant.BnplPricing");
        String judged = methodSource(pricing, "judged");
        assertThat(judged)
                .as("the instant BNPL resolves at is read from the database after its share locks")
                .contains("pricing.databaseNow(unitOfWork)")
                .contains("findEffectiveVersion(unitOfWork, id, at)");
        assertThat(judged.indexOf("activeForShare"))
                .as("the enablement's share lock precedes the reading")
                .isLessThan(judged.indexOf("databaseNow"));
    }

    @Test
    @DisplayName("each judging or stamping statement reads the database's clock in the statement")
    void eachStatementReadsTheDatabasesClock() {
        statement("com.finapp.identity.JdbcRecoveryRequestStore", "consume")
                .literals(" AND expires_at > ", " SET status = 'COMPLETED', completed_at = ", " RETURNING ", ", completed_at")
                .usesConstant("DATABASE_NOW", "GREATEST(clock_timestamp(), transaction_timestamp())");
        statement("com.finapp.identity.JdbcRecoveryRequestStore", "initiate")
                .literals(", now(), now() + ", "AND r.initiated_at > now() - ");
        statement("com.finapp.identity.JdbcRecoveryRequestStore", "cancelLiveFor")
                .literals("cancelled_at = now()");
        statement("com.finapp.identity.JdbcContactChannelStore", "verify")
                .literals(" AND verification_expires_at > ", " SET verified_at = ")
                .usesConstant("DATABASE_NOW", "GREATEST(clock_timestamp(), transaction_timestamp())");
        statement("com.finapp.checkout.JdbcCheckoutSessionStore", "findExpirable")
                .literals("status = 'OPEN' AND expires_at <= now()", "expires_at <= now() - (? * INTERVAL '1 millisecond')");
        statement("com.finapp.merchant.JdbcFeeScheduleStore", "findEffectiveVersionFor")
                .literals(
                        "v.effective_from <= GREATEST(clock_timestamp(),",
                        " transaction_timestamp())",
                        "OR v.effective_from = v.created_at");
        statement("com.finapp.merchant.JdbcPayoutDestinationStore", "findDue")
                .literals("status = 'APPROVED' AND cooling_off_until <= now()");
        for (String binds :
                List.of(
                        "com.finapp.identity.JdbcRecoveryRequestStore#consume",
                        "com.finapp.identity.JdbcRecoveryRequestStore#initiate",
                        "com.finapp.identity.JdbcRecoveryRequestStore#cancelLiveFor",
                        "com.finapp.identity.JdbcContactChannelStore#verify",
                        "com.finapp.checkout.JdbcCheckoutSessionStore#findExpirable",
                        "com.finapp.merchant.JdbcFeeScheduleStore#findEffectiveVersionFor",
                        "com.finapp.merchant.JdbcPayoutDestinationStore#findDue")) {
            String[] parts = binds.split("#");
            assertThat(methodSource(readSourceOf(parts[0]), parts[1]))
                    .as(binds + " binds no timestamp at all")
                    .doesNotContain("setTimestamp");
        }
    }

    @Test
    @DisplayName("a channel's challenge is stamped by the database, and the only timestamp bound is added_at")
    void aChallengeIsStampedByTheDatabase() {
        String add = methodSource(readSourceOf("com.finapp.identity.JdbcContactChannelStore"), "add");
        assertThat(literalsIn(add)).contains("now() + (? * INTERVAL '1 millisecond')");
        assertThat(Pattern.compile("setTimestamp\\(").matcher(add).results().count())
                .as("added_at, business time, and nothing else")
                .isEqualTo(1L);
        assertThat(add).contains("insert.setTimestamp(7, Timestamp.from(channel.addedAt()))");
    }

    // ----------------------------------------------------------------- the answers

    @Test
    @DisplayName("the sweepers hold no clock, and each reads the database's after the row's lock")
    void theSweepersHoldNoClock() {
        for (Class<?> sweeper : List.of(CheckoutExpirySweeper.class, PayoutDestinationEffectuation.class)) {
            assertThat(fieldsOf(sweeper))
                    .as(sweeper.getSimpleName() + ": a clock field is how an instance's skew gets back in")
                    .doesNotContain(Clock.class);
        }
        String sweep = methodSource(readSourceOf("com.finapp.checkout.CheckoutExpirySweeper"), "expireOneOrRollBack");
        assertThat(sweep.indexOf("findByIdForUpdate")).as("the row's lock first").isNotNegative();
        assertThat(sweep.indexOf("sessions.databaseNow(uow)"))
                .as("the re-judge's reading is taken after the lock, never fixed before the wait")
                .isGreaterThan(sweep.indexOf("findByIdForUpdate"));

        String effect = methodSource(readSourceOf("com.finapp.merchant.PayoutDestinationEffectuation"), "effectOne");
        assertThat(effect.indexOf("destinations.databaseNow(uow)"))
                .as("the cooling-off is re-judged on a reading taken after the approved row's lock")
                .isGreaterThan(effect.indexOf("findForUpdate"));
        assertThat(effect).contains("approved.effect(databaseNow)").contains("supersede(databaseNow)");
    }

    @Test
    @DisplayName("the callers above the stores stamp and report what the database decided, on no second clock")
    void theAnswerAgrees() {
        String[][] noInstanceClockIn = {
            {"com.finapp.identity.RecoveryService", "initiate"},
            {"com.finapp.identity.RecoveryService", "complete"},
            {"com.finapp.identity.ContactChannelService", "verify"},
            {"com.finapp.app.checkout.CheckoutSessions", "open"},
            {"com.finapp.app.checkout.CheckoutSessions", "accept"},
            {"com.finapp.app.checkout.CheckoutSessions", "paymentOpened"},
            {"com.finapp.merchant.PayoutDestinations", "approve"},
        };
        for (String[] site : noInstanceClockIn) {
            String body = methodSource(readSourceOf(site[0]), site[1]);
            assertThat(body)
                    .as(site[0] + "." + site[1] + ": the statement decided on the database's clock, so a second clock"
                            + " here is two clocks in one operation")
                    .doesNotContain("Instant.now(clock)")
                    .doesNotContain("clock.instant()")
                    .doesNotContain("confirm(clock")
                    .doesNotContain("approve(actor.id(), clock");
        }
        assertThat(readSourceOf("com.finapp.app.checkout.CheckoutService"))
                .as("the confirmation judges the offer's deadline on the database's clock")
                .doesNotContain("hasExpired(clock)")
                .contains("hasExpired(checkout.databaseNow(unitOfWork))");
        assertThat(readSourceOf("com.finapp.app.checkout.CheckoutDoorStepUp"))
                .as("the door judges the offer's deadline as the confirmation does")
                .doesNotContain("hasExpired(clock)")
                .doesNotContain("java.time.Clock");
        String complete = methodSource(readSourceOf("com.finapp.identity.RecoveryService"), "complete");
        assertThat(complete)
                .as("one reading for the whole completion")
                .contains("consumed.get().completedAt()")
                .contains("Clock.fixed(at, ZoneOffset.UTC)");
    }

    @Test
    @DisplayName("the card fee version is resolved after the merchant's share lock, and an instance's clock is not an input")
    void theFeeVersionIsResolvedAfterItsLocks() {
        String open = methodSource(readSourceOf("com.finapp.app.checkout.CheckoutSessions"), "open");
        assertThat(open.indexOf("requireTrading(unitOfWork, command.merchant().value())")).isNotNegative();
        assertThat(open.indexOf(".effectiveVersionFor(unitOfWork, command.merchant())"))
                .as("the version in force is read after the share lock the trading check takes")
                .isGreaterThan(open.indexOf("requireTrading(unitOfWork, command.merchant().value())"));
        assertThat(open.indexOf("sessions.databaseNow(unitOfWork)"))
                .as("and the offer's stamps too")
                .isGreaterThan(open.indexOf(".effectiveVersionFor(unitOfWork, command.merchant())"));
    }

    @Test
    @DisplayName("the migration binds the card fee pin: its instant the insert's own stamp, its version in force by it")
    void thePinIsBoundToTheDatabasesClock() {
        String v014 =
                readRepositoryFile(
                        "merchant/src/main/resources/db/migration/merchant/V014__the_card_fee_pin_on_the_database_clock.sql");
        assertThat(v014)
                .contains("BEFORE INSERT ON merchant.payment_fee_pin")
                .contains("NEW.pinned_at := GREATEST(pg_catalog.clock_timestamp(), pg_catalog.transaction_timestamp());")
                .contains("IF pinned.effective_from > NEW.pinned_at AND pinned.effective_from <> pinned.created_at THEN");
        assertThat(v014.indexOf("NEW.pinned_at := GREATEST"))
                .as("stamped before the in-force rank judges it")
                .isLessThan(v014.indexOf("IF pinned.effective_from > NEW.pinned_at"));
    }

    // ----------------------------------------------------------------- every store

    @Test
    @DisplayName("every production comparison of a timestamp column with a bind parameter is in the census, and none is stale")
    void everyStoreIsInTheCensus() {
        Set<String> columns = timestampColumns();
        assertThat(columns)
                .as("the columns were read from the migrations, so a green rule is not a parse that saw none")
                .hasSizeGreaterThan(100)
                .contains("expires_at", "cooling_off_until", "effective_from", "created_at");

        Map<String, List<String>> found = new TreeMap<>();
        forEveryProductionSource((name, source) -> {
            for (Comparison comparison : comparisonsIn(literalsIn(source))) {
                if (columns.contains(comparison.column())) {
                    found.computeIfAbsent(name + "#" + comparison.column(), key -> new ArrayList<>()).add(comparison.text());
                }
            }
        });
        assertThat(found)
                .as("the sources were really scanned: a comparison this rule classifies was found")
                .containsKey("JdbcSettlementFileStore#next_parse_at");

        Set<String> unclassified = new TreeSet<>(found.keySet());
        unclassified.removeAll(CENSUS.keySet());
        assertThat(unclassified)
                .as("a timestamp column compared with a bind parameter nobody has classified. Whose instant is that ?"
                        + " - a stored bound judged on an instance's clock is the defect; add it to CENSUS only with its"
                        + " kind, or judge it on the database's clock")
                .isEmpty();

        Set<String> stale = new TreeSet<>(CENSUS.keySet());
        stale.removeAll(found.keySet());
        assertThat(stale).as("the census names a comparison nothing makes any more").isEmpty();
    }

    @Test
    @DisplayName("the census rule sees a planted caller's instant, ignores prose and the database's clock, and is not derailed")
    void theRuleSeesAPlantedComparison() {
        String planted =
                "class Planted {\n"
                        + "  // a.expires_at > ? in a comment is prose, not a statement\n"
                        + "  /* cooling_off_until <= ? here too */\n"
                        + "  char quote = '\"';\n"
                        + "  String one = \"UPDATE x SET y = ? WHERE a.expires_at > ?\";\n"
                        + "  String two = \"SELECT 1 WHERE ? >= cooling_off_until\";\n"
                        + "  String three = \"SELECT 1 WHERE COALESCE(h.entered, r.created_at) <= ?::timestamptz\";\n"
                        + "  String four = \"SELECT 1 WHERE effective_from BETWEEN ? AND ?\";\n"
                        + "  String five = \"SELECT 1 WHERE verification_expires_at > now()\";\n"
                        + "  String six = \"SELECT 1 WHERE live_from > clock_timestamp()\";\n"
                        + "}\n";
        Set<String> columns =
                comparisonsIn(literalsIn(planted)).stream().map(Comparison::column).collect(Collectors.toCollection(TreeSet::new));
        assertThat(columns)
                .as("a qualified column, a reversed comparison, an expression and BETWEEN are each seen")
                .containsExactly("cooling_off_until", "created_at", "effective_from", "entered", "expires_at", "h", "r");
        assertThat(comparisonsIn(literalsIn(planted)).stream().map(Comparison::text))
                .as("the database's clock on the right is nothing the rule should flag")
                .noneMatch(text -> text.contains("verification_expires_at") || text.contains("live_from"));
        assertThat(CENSUS).as("a planted key is not in the census, so the rule would fail it").doesNotContainKey("Planted#expires_at");
    }

    // -----------------------------------------------------------------

    private record Comparison(String column, String text) {}

    /** Every ordering comparison with a bind parameter in joined string literals. */
    private static List<Comparison> comparisonsIn(String literals) {
        List<Comparison> comparisons = new ArrayList<>();
        Matcher right = BIND_RIGHT.matcher(literals);
        while (right.find()) {
            comparisons.add(new Comparison(lastSegment(right.group(1)), right.group()));
        }
        Matcher left = BIND_LEFT.matcher(literals);
        while (left.find()) {
            comparisons.add(new Comparison(lastSegment(left.group(2)), left.group()));
        }
        Matcher between = BIND_BETWEEN.matcher(literals);
        while (between.find()) {
            comparisons.add(new Comparison(lastSegment(between.group(1)), between.group()));
        }
        // COALESCE(h.entered, p.created_at) <= ? - every identifier inside the parentheses.
        Matcher expression = EXPRESSION_BOUND.matcher(literals);
        while (expression.find()) {
            int close = expression.start();
            int depth = 0;
            int open = -1;
            for (int i = close; i >= 0; i--) {
                char c = literals.charAt(i);
                if (c == ')') {
                    depth++;
                } else if (c == '(' && --depth == 0) {
                    open = i;
                    break;
                }
            }
            if (open >= 0) {
                Matcher identifier = IDENTIFIER.matcher(literals.substring(open, close));
                while (identifier.find()) {
                    comparisons.add(new Comparison(identifier.group().toLowerCase(), literals.substring(open, expression.end())));
                }
            }
        }
        return comparisons;
    }

    private static String lastSegment(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1).toLowerCase();
    }

    /** The timestamp columns the migrations declare, by name. */
    private static Set<String> timestampColumns() {
        Set<String> columns = new TreeSet<>();
        try (Stream<Path> files = Files.walk(repositoryRoot())) {
            files.filter(StoredBoundsAreTheDatabasesTest::isMigration).forEach(file -> {
                String sql;
                try {
                    sql = Files.readString(file).replaceAll("--[^\\n]*", "");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Matcher declared = TIMESTAMP_COLUMN.matcher(sql);
                while (declared.find()) {
                    String name = declared.group(1).toLowerCase();
                    if (!NOT_A_COLUMN.contains(name)) {
                        columns.add(name);
                    }
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return columns;
    }

    private static boolean isMigration(Path path) {
        String text = relativeToTheRepository(path);
        return text.contains("/src/main/resources/db/migration/") && text.endsWith(".sql") && !isBuildOutput(text);
    }

    /** Relative to the repository root, with a leading slash: the checkout itself may live under a {@code .claude} worktree. */
    private static String relativeToTheRepository(Path path) {
        return "/" + repositoryRoot().relativize(path).toString().replace('\\', '/');
    }

    private static boolean isBuildOutput(String text) {
        return text.contains("/build/") || text.contains("/.claude/") || text.contains("/.gradle/");
    }

    private interface SourceVisitor {
        void visit(String simpleName, String source);
    }

    private static void forEveryProductionSource(SourceVisitor visitor) {
        try (Stream<Path> files = Files.walk(repositoryRoot())) {
            files.filter(path -> {
                        String text = relativeToTheRepository(path);
                        return text.contains("/src/main/java/") && text.endsWith(".java") && !isBuildOutput(text);
                    })
                    .sorted()
                    .forEach(path -> {
                        String file = path.getFileName().toString();
                        try {
                            visitor.visit(file.substring(0, file.length() - ".java".length()), Files.readString(path));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Statement statement(String type, String method) {
        return new Statement(type, method);
    }

    /** Asserts one method's statement, read from its literals and the constants it names. */
    private static final class Statement {
        private final String source;
        private final String body;
        private final String where;

        Statement(String type, String method) {
            this.source = readSourceOf(type);
            this.body = methodSource(source, method);
            this.where = type + "." + method;
        }

        Statement literals(String... fragments) {
            String literals = literalsIn(body);
            assertThat(literals).as(where + " states its judgement in the statement").contains(fragments);
            return this;
        }

        Statement usesConstant(String name, String value) {
            assertThat(namesIdentifier(body, name)).as(where + " judges with " + name).isTrue();
            assertThat(constantOf(source, name)).as(name + " reads the database's clock").isEqualTo(value);
            return this;
        }
    }

    private static List<Class<?>> temporalParametersOf(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .filter(type -> TEMPORAL.stream().anyMatch(temporal -> temporal.isAssignableFrom(type)))
                .collect(Collectors.toList());
    }

    private static List<Class<?>> parameterTypes(Class<?> type, String method) {
        List<Class<?>> parameters = new ArrayList<>();
        boolean declared = false;
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(method) && !candidate.isSynthetic()) {
                declared = true;
                parameters.addAll(Arrays.asList(candidate.getParameterTypes()));
            }
        }
        assertThat(declared).as(type.getSimpleName() + "." + method + " still exists").isTrue();
        return parameters;
    }

    private static List<Class<?>> fieldsOf(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields()).map(Field::getType).collect(Collectors.toList());
    }

    /** The value of one {@code static final String} constant made of literals. */
    private static String constantOf(String source, String name) {
        Matcher declared =
                Pattern.compile(
                                "static final String\\s+" + Pattern.quote(name) + "\\s*=\\s*"
                                        + "((?:\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"\\s*\\+?\\s*)+);")
                        .matcher(source);
        assertThat(declared.find()).as("the constant " + name + " is declared").isTrue();
        return literalsIn(declared.group(1));
    }

    /**
     * Every string literal of {@code source}, comments and character literals skipped, text blocks read, joined by a
     * space: a tokeniser rather than a regular expression, so a {@code '"'} cannot derail what is read after it.
     */
    private static String literalsIn(String source) {
        List<String> literals = new ArrayList<>();
        int length = source.length();
        int i = 0;
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    throw new IllegalStateException("Unterminated text block");
                }
                literals.add(source.substring(i + 3, end));
                i = end + 3;
            } else if (c == '"') {
                int end = i + 1;
                while (end < length && source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                literals.add(source.substring(i + 1, Math.min(end, length)));
                i = end + 1;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < length && source.charAt(end) != '\'') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return String.join(" ", literals);
    }

    /** Word-boundary match, so a constant is not found inside another identifier. */
    private static boolean namesIdentifier(String body, String name) {
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(body).find();
    }

    /** The body of the method DECLARED with this name, never a call site of it. */
    private static String methodSource(String source, String method) {
        int from = 0;
        int signature = -1;
        while (signature < 0) {
            int hit = source.indexOf(method + "(", from);
            if (hit < 0) {
                throw new IllegalStateException("No declaration of " + method);
            }
            int lineStart = source.lastIndexOf(10, hit) + 1;
            String line = source.substring(lineStart, hit).trim();
            if (line.startsWith("public ") || line.startsWith("private ") || line.startsWith("static ")
                    || line.startsWith("Optional<") || line.startsWith("List<")) {
                signature = hit;
            }
            from = hit + 1;
        }
        int open = source.indexOf('{', signature);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char character = source.charAt(i);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("Unbalanced braces reading " + method);
    }

    private static String readSourceOf(String type) {
        String module = type.substring("com.finapp.".length());
        module = module.substring(0, module.indexOf('.'));
        return readRepositoryFile(module + "/src/main/java/" + type.replace('.', '/') + ".java");
    }

    private static String readRepositoryFile(String relative) {
        try {
            return Files.readString(repositoryRoot().resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + relative, e);
        }
    }

    /** The {@code DomainGlossaryTest} idiom - the working directory differs between IDE and Gradle. */
    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("No settings.gradle.kts above " + Path.of("").toAbsolutePath());
    }
}
