package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.merchant.FeeScheduleVersionId;
import com.finapp.merchant.FeeSchedules;
import com.finapp.merchant.MerchantId;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The versioned fee schedule over real HTTP and against the real schema (`P6-TSK-004`,
 * ADR-0050) — every acceptance clause of this task driven through the door it will be used
 * through:
 *
 * <ol>
 *   <li>recomputation under a pinned version reproduces to the minor unit;
 *   <li>a new version reprices nothing;
 *   <li>the split conserves (the property itself is hermetic — this is the stored half);
 *   <li>mutation of a frozen version is refused <strong>at the database</strong>.
 * </ol>
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the versioned fee schedule (P6-TSK-004)")
class FeeScheduleDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private FeeSchedules feeSchedules;

    // ----------------------------------------------------------------- acceptance

    @Test
    @DisplayName("INV-MER-03: recomputing under the PINNED version reproduces the amount to"
            + " the minor unit, from what the database holds")
    void recomputationUnderThePinnedVersionReproduces() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Standard " + suffix());
        String version = addVersion(admin, schedule, "0.029", 30L, "HALF_EVEN", "RETAINED", null);

        Money gross = Money.ofMinorUnits(10_000L, EUR);
        var assessment =
                inTransaction(
                        unitOfWork ->
                                feeSchedules.recompute(
                                        unitOfWork,
                                        gross,
                                        FeeScheduleVersionId.of(UUID.fromString(version))));

        // 10000 x 0.029 = 290, + 30 fixed = 320; net 9680. Read back from the stored row, not
        // from the object that wrote it.
        assertThat(assessment.fee().minorUnits()).isEqualTo(320L);
        assertThat(assessment.net().minorUnits()).isEqualTo(9_680L);
        assertThat(assessment.fee().plus(assessment.net())).isEqualTo(gross);
        assertThat(assessment.version().value().toString()).isEqualTo(version);

        // Twice, because "deterministic" means the second answer too. Bound to a local
        // first: assertThat is overloaded, and an inTransaction call nested directly inside
        // it resolves ambiguously through the type variable rather than through the value.
        var again =
                inTransaction(
                        unitOfWork ->
                                feeSchedules.recompute(
                                        unitOfWork,
                                        gross,
                                        FeeScheduleVersionId.of(UUID.fromString(version))));
        assertThat(again).isEqualTo(assessment);
    }

    @Test
    @DisplayName("INV-MER-03: a NEW version reprices nothing - the pinned one still prices"
            + " what it priced, and the merchant's new captures price at the new rate")
    void aNewVersionRepricesNothing() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin);
        String schedule = createSchedule(admin, "Repricing " + suffix());
        // v1 immediately (an omitted instant), v2 comfortably in the future: a named instant
        // has to outlast the round trip, or the server correctly refuses it as backdated.
        String v1 = addVersion(admin, schedule, "0.029", 30L, "HALF_EVEN", "RETAINED", null);
        assign(admin, merchant, schedule, "initial terms");

        Money gross = Money.ofMinorUnits(10_000L, EUR);
        MerchantId merchantId = MerchantId.of(UUID.fromString(merchant));

        var priced = inTransaction(u -> feeSchedules.assess(u, merchantId, gross, now()));
        assertThat(priced).isPresent();
        assertThat(priced.get().version().value().toString()).isEqualTo(v1);
        assertThat(priced.get().fee().minorUnits()).isEqualTo(320L);

        // The price changes, effective forward.
        Instant later = now().plus(Duration.ofHours(1));
        String v2 =
                addVersion(admin, schedule, "0.049", 50L, "HALF_EVEN", "RETAINED", later);

        // The ASSESSMENT ALREADY MADE is untouched: recomputing under its pin gives the same
        // number it always gave, which is what a merchant statement dispute rests on.
        var recomputed =
                inTransaction(
                        u ->
                                feeSchedules.recompute(
                                        u, gross, FeeScheduleVersionId.of(UUID.fromString(v1))));
        assertThat(recomputed.fee().minorUnits()).isEqualTo(320L);

        // And a capture priced AFTER the change gets the new version.
        var afterwards =
                inTransaction(u -> feeSchedules.assess(u, merchantId, gross, later.plusSeconds(1)));
        assertThat(afterwards).isPresent();
        assertThat(afterwards.get().version().value().toString()).isEqualTo(v2);
        assertThat(afterwards.get().fee().minorUnits()).isEqualTo(540L);
    }

    @Test
    @DisplayName("INV-MER-04: the stored version's split conserves the capture exactly")
    void theStoredSplitConserves() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Conserving " + suffix());
        // A rate and amounts chosen so the variable part is NOT a whole minor unit - the case
        // an independently-rounded net would strand.
        String version = addVersion(admin, schedule, "0.0175", 25L, "HALF_EVEN", "RETURNED", null);
        FeeScheduleVersionId pinned = FeeScheduleVersionId.of(UUID.fromString(version));

        for (long minor : new long[] {1L, 7L, 99L, 333L, 1_234L, 99_999L, 1_000_000L}) {
            Money gross = Money.ofMinorUnits(minor, EUR);
            var assessment = inTransaction(u -> feeSchedules.recompute(u, gross, pinned));
            assertThat(assessment.fee().plus(assessment.net()))
                    .as("conservation at %d minor units, from the stored version", minor)
                    .isEqualTo(gross);
        }
    }

    @Test
    @DisplayName("MUTATION OF A FROZEN VERSION IS REFUSED AT THE DATABASE - for the"
            + " application role by a withheld grant, and for the MIGRATOR by the trigger")
    void afrozenVersionCannotBeChanged() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Frozen " + suffix());
        String version = addVersion(admin, schedule, "0.029", 30L, "HALF_EVEN", "RETAINED", null);
        UUID versionId = UUID.fromString(version);

        // Rank one: the application role holds no UPDATE grant at all.
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE merchant.fee_schedule_version SET rate ="
                                                    + " 0.999 WHERE id = ?",
                                            versionId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }

        // Rank two: THE MIGRATOR, deliberately - the only writer the grants cannot bind, and
        // therefore the only one that proves the trigger rather than the privilege
        // (`P6-TSK-003`'s recorded lesson).
        try (Connection migrator = DatabaseRoles.migrator()) {
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE merchant.fee_schedule_version SET rate ="
                                                    + " 0.999 WHERE id = ?",
                                            versionId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "DELETE FROM merchant.fee_schedule_version WHERE"
                                                    + " id = ?",
                                            versionId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            migrator,
                                            "UPDATE merchant.fee_schedule SET name = 'renamed'"
                                                    + " WHERE id = ?",
                                            UUID.fromString(schedule)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("immutable");
        }

        // The row still prices exactly what it priced.
        assertThat(
                        inTransaction(
                                        u ->
                                                feeSchedules.recompute(
                                                        u,
                                                        Money.ofMinorUnits(10_000L, EUR),
                                                        FeeScheduleVersionId.of(versionId)))
                                .fee()
                                .minorUnits())
                .isEqualTo(320L);
    }

    // ----------------------------------------------------------------- INV-MER-03's other half

    @Test
    @DisplayName("a BACKDATED version is refused - 422, and the database would refuse it too")
    void backdatingIsRefused() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Backdated " + suffix());
        long before = countVersions(schedule);

        HttpResponse<String> refused =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        versionBody("0.029", 30L, "HALF_EVEN", "RETAINED",
                                now().minus(Duration.ofDays(30))),
                        admin,
                        null);

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("merchant.FeeScheduleNotForward");
        assertThat(countVersions(schedule)).as("the refusal wrote nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("ties on effective_from are legal and the greater version number wins - an"
            + " operator can supersede a mistyped future rate at the same instant")
    void aSupersedingVersionAtTheSameInstantWins() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin);
        String schedule = createSchedule(admin, "Superseded " + suffix());
        assign(admin, merchant, schedule, "initial terms");

        Instant firstOfNextMonth = now().plus(Duration.ofDays(9));
        addVersion(admin, schedule, "0.290", 30L, "HALF_EVEN", "RETAINED", firstOfNextMonth);
        String corrected =
                addVersion(admin, schedule, "0.029", 30L, "HALF_EVEN", "RETAINED",
                        firstOfNextMonth);

        var priced =
                inTransaction(
                        u ->
                                feeSchedules.assess(
                                        u,
                                        MerchantId.of(UUID.fromString(merchant)),
                                        Money.ofMinorUnits(10_000L, EUR),
                                        firstOfNextMonth));
        assertThat(priced).isPresent();
        assertThat(priced.get().version().value().toString())
                .as("the mistyped rate never priced anything, and stays in the record")
                .isEqualTo(corrected);
        assertThat(priced.get().fee().minorUnits()).isEqualTo(320L);
    }

    // ----------------------------------------------------------------- concurrency

    @Test
    @DisplayName("TEN INSTANCES adding a version to one schedule mint ten DISTINCT numbers -"
            + " the schedule row's lock, with the unique index behind it")
    void tenConcurrentVersionsMintTenNumbers() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Contended " + suffix());
        int racers = 10;

        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return post(
                                            "/v1/operator/fee-schedules/" + schedule + "/versions",
                                            versionBody("0.029", 30L, "HALF_EVEN", "RETAINED",
                                                    now().plus(Duration.ofDays(1))),
                                            admin,
                                            null);
                                }));
            }
            start.countDown();
            for (Future<HttpResponse<String>> result : results) {
                assertThat(result.get(60, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(countVersions(schedule)).isEqualTo(racers);
        assertThat(distinctVersionNumbers(schedule))
                .as("ten racers, ten numbers - no duplicate, no gap, nothing lost")
                .isEqualTo(racers);
        assertThat(maxVersionNumber(schedule)).isEqualTo(racers);
    }

    @Test
    @DisplayName("assignment CONVERGES: the repeat writes no second history row and no second"
            + " audit record")
    void repeatedAssignmentConverges() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin);
        String schedule = createSchedule(admin, "Converging " + suffix());

        assertThat(assign(admin, merchant, schedule, "initial terms").statusCode()).isEqualTo(200);
        assertThat(assign(admin, merchant, schedule, "same again").statusCode()).isEqualTo(200);

        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_fee_schedule_event WHERE"
                                        + " merchant_id = ?",
                                UUID.fromString(merchant)))
                .as("one act, however many operators asked")
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT count(*) FROM platform.audit_record WHERE operation ="
                                        + " 'merchant.MerchantFeeScheduleAssigned' AND target_id"
                                        + " = ?",
                                merchant))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a real move records from -> to with the operator's reason verbatim"
            + " (INV-AUD-03)")
    void aMoveIsRecordedWithItsReason() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin);
        String first = createSchedule(admin, "First " + suffix());
        String second = createSchedule(admin, "Second " + suffix());

        assign(admin, merchant, first, "initial terms");
        String reason = "negotiated volume tier " + suffix();
        assertThat(assign(admin, merchant, second, reason).statusCode()).isEqualTo(200);

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT from_fee_schedule_id, to_fee_schedule_id, reason FROM"
                                        + " merchant.merchant_fee_schedule_event WHERE"
                                        + " merchant_id = ? ORDER BY id DESC LIMIT 1")) {
            read.setObject(1, UUID.fromString(merchant));
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getObject(1, UUID.class)).isEqualTo(UUID.fromString(first));
                assertThat(row.getObject(2, UUID.class)).isEqualTo(UUID.fromString(second));
                assertThat(row.getString(3)).isEqualTo(reason);
            }
        }
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_fee_schedule WHERE"
                                        + " merchant_id = ? AND fee_schedule_id = ?",
                                UUID.fromString(merchant),
                                UUID.fromString(second)))
                .as("one live pointer, moved")
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- refusals

    @Test
    @DisplayName("a schedule that does not price the merchant's settlement currency is"
            + " refused - 422, nothing assigned")
    void aForeignCurrencyScheduleIsRefused() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin); // settles in EUR
        String usd = createSchedule(admin, "Dollars " + suffix(), "USD");

        HttpResponse<String> refused = assign(admin, merchant, usd, "should not work");
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("merchant.FeeCurrencyMismatch");
        assertThat(
                        count(
                                "SELECT count(*) FROM merchant.merchant_fee_schedule WHERE"
                                        + " merchant_id = ?",
                                UUID.fromString(merchant)))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("the fee surfaces are behind FEE_ADMINISTER: the permissionless session and"
            + " the LEDGER OPERATOR are both 403 with nothing written")
    void theFeeRoutesArePrivileged() throws Exception {
        long before = count("SELECT count(*) FROM merchant.fee_schedule");

        String plain = tokenFrom(authenticate(registered()).body());
        assertThat(
                        post("/v1/operator/fee-schedules", scheduleBody("Sneaky " + suffix(),
                                "EUR"), plain, null)
                                .statusCode())
                .isEqualTo(403);

        // THE NAMED NEGATIVE: a ledger operator commands the platform's money and still
        // cannot set its prices. Pricing is a commercial trust decision, not a money-operating
        // one - which is the whole reason FEE_ADMINISTER exists as its own permission.
        String ledgerOperator = sessionWith(RoleName.LEDGER_OPERATOR);
        assertThat(
                        post("/v1/operator/fee-schedules", scheduleBody("Also sneaky " + suffix(),
                                "EUR"), ledgerOperator, null)
                                .statusCode())
                .isEqualTo(403);

        assertThat(count("SELECT count(*) FROM merchant.fee_schedule")).isEqualTo(before);
    }

    @Test
    @DisplayName("an unknown schedule and a malformed identifier are the SAME 404")
    void unknownAndMalformedAreOneAnswer() throws Exception {
        String admin = administrator();
        assertThat(get("/v1/operator/fee-schedules/" + UUID.randomUUID(), admin).statusCode())
                .isEqualTo(404);
        assertThat(get("/v1/operator/fee-schedules/not-a-uuid", admin).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an unknown rounding policy is a 422, never a silent default")
    void anUnknownRoundingPolicyIsRefused() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Unknown policy " + suffix());
        long before = countVersions(schedule);

        // HALF_DOWN is a real JDK RoundingMode that this platform deliberately does NOT offer.
        // Accepting it silently as HALF_UP would make a stored version price differently from
        // what its operator asked for, forever.
        HttpResponse<String> refused =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        versionBody("0.029", 30L, "HALF_DOWN", "RETAINED", null),
                        admin,
                        null);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(countVersions(schedule)).isEqualTo(before);
    }

    @Test
    @DisplayName("a rate at or above 1 is refused at the door")
    void anImpossibleRateIsRefused() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Impossible " + suffix());
        HttpResponse<String> refused =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        versionBody("1.0", 30L, "HALF_EVEN", "RETAINED", null),
                        admin,
                        null);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(countVersions(schedule)).isZero();
    }

    @Test
    @DisplayName("EACH merchant resolves to ITS OWN pricing, and an unassigned one resolves"
            + " empty WHILE OTHER ASSIGNMENTS EXIST (INV-MER-01, in the statement)")
    void pricingIsResolvedPerTenant() throws Exception {
        // ADDED BY THE COMPLETION GATE, because a probe survived. Dropping `merchant_id = ?`
        // from the resolution join left anUnpricedMerchantResolvesEmpty green: that test
        // onboards a merchant with no assignment and asserts empty, which an unscoped query
        // also answers whenever no OTHER assignment happens to be committed at that moment -
        // so the assertion depended on execution order rather than on the predicate. The
        // three tenants here are established in ONE test, so the unscoped query has something
        // wrong to return and must return it.
        String admin = administrator();
        String cheap = createSchedule(admin, "Cheap " + suffix());
        String dear = createSchedule(admin, "Dear " + suffix());
        addVersion(admin, cheap, "0.010", 10L, "HALF_EVEN", "RETAINED", null);
        addVersion(admin, dear, "0.050", 50L, "HALF_EVEN", "RETAINED", null);

        String onCheap = onboarded(admin);
        String onDear = onboarded(admin);
        String unpriced = onboarded(admin);
        assign(admin, onCheap, cheap, "volume tier");
        assign(admin, onDear, dear, "standard tier");

        Money gross = Money.ofMinorUnits(10_000L, EUR);
        var cheapPrice = inTransaction(u -> feeSchedules.assess(u, merchantId(onCheap), gross, now()));
        var dearPrice = inTransaction(u -> feeSchedules.assess(u, merchantId(onDear), gross, now()));
        var nonePrice =
                inTransaction(u -> feeSchedules.assess(u, merchantId(unpriced), gross, now()));

        assertThat(cheapPrice).isPresent();
        assertThat(dearPrice).isPresent();
        assertThat(cheapPrice.get().fee().minorUnits())
                .as("10000 x 0.010 + 10")
                .isEqualTo(110L);
        assertThat(dearPrice.get().fee().minorUnits())
                .as("10000 x 0.050 + 50 - one merchant's terms are not the other's")
                .isEqualTo(550L);
        assertThat(nonePrice)
                .as("an unassigned merchant resolves EMPTY even though other merchants are"
                        + " assigned - which is what the tenant predicate in the statement buys")
                .isEmpty();
    }

    @Test
    @DisplayName("a merchant with no assignment prices as EMPTY - honest, not a default")
    void anUnpricedMerchantResolvesEmpty() throws Exception {
        String admin = administrator();
        String merchant = onboarded(admin);
        var priced =
                inTransaction(
                        u ->
                                feeSchedules.assess(
                                        u,
                                        MerchantId.of(UUID.fromString(merchant)),
                                        Money.ofMinorUnits(1_000L, EUR),
                                        now()));
        assertThat(priced)
                .as("fabricating a schedule would price a capture at a number nobody agreed")
                .isEmpty();
    }

    @Test
    @DisplayName("version creation is audited with its terms and the operator's reason")
    void versionCreationIsAudited() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Audited " + suffix());
        String reason = "annual repricing " + suffix();
        HttpResponse<String> created =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        versionBody("0.029", 30L, "HALF_EVEN", "RETAINED", null, reason),
                        admin,
                        null);
        assertThat(created.statusCode()).isEqualTo(201);
        String version = field(created.body(), "feeScheduleVersionId");

        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT reason, change_summary FROM platform.audit_record WHERE"
                                        + " operation = 'merchant.FeeScheduleVersionCreated' AND"
                                        + " target_id = ?")) {
            read.setString(1, version);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(reason);
                assertThat(row.getString(2))
                        .contains("rate=0.029")
                        .contains("rounding=HALF_EVEN")
                        .contains("refundFee=RETAINED");
            }
        }
    }

    @Test
    @DisplayName("the version's terms survive the round trip exactly - rate, fixed part,"
            + " policies and instant")
    void theStoredTermsRoundTripExactly() throws Exception {
        String admin = administrator();
        String schedule = createSchedule(admin, "Round trip " + suffix());
        Instant from = now().plus(Duration.ofDays(1));
        String version = addVersion(admin, schedule, "0.0175", 25L, "CEILING", "RETURNED", from);

        HttpResponse<String> read = get("/v1/operator/fee-schedules/" + schedule, admin);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body())
                .contains("\"feeScheduleVersionId\":\"" + version + "\"")
                // A STRING on the wire, never a JSON number: this is the value a merchant
                // reproduces a statement from.
                .contains("\"rate\":\"0.017500\"")
                .contains("\"fixedAmountMinor\":25")
                .contains("\"roundingPolicy\":\"CEILING\"")
                .contains("\"refundFeePolicy\":\"RETURNED\"");

        // And the stored row prices with the policy it recorded rather than a default:
        // 10000 x 0.0175 = 175.0 exactly, so pick an amount with a residue instead.
        var assessment =
                inTransaction(
                        u ->
                                feeSchedules.recompute(
                                        u,
                                        Money.ofMinorUnits(101L, EUR),
                                        FeeScheduleVersionId.of(UUID.fromString(version))));
        // 101 x 0.0175 = 1.7675 -> CEILING -> 2, + 25 = 27.
        assertThat(assessment.fee().minorUnits()).isEqualTo(27L);
        assertThat(assessment.net().minorUnits()).isEqualTo(74L);
    }

    // ----------------------------------------------------------------- fixtures

    private <R> R inTransaction(java.util.function.Function<Connection, R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            return work.apply(app);
        }
    }

    private String createSchedule(String admin, String name) throws Exception {
        return createSchedule(admin, name, "EUR");
    }

    private String createSchedule(String admin, String name, String currency) throws Exception {
        HttpResponse<String> created =
                post("/v1/operator/fee-schedules", scheduleBody(name, currency), admin, null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return field(created.body(), "feeScheduleId");
    }

    private String addVersion(
            String admin,
            String schedule,
            String rate,
            long fixed,
            String rounding,
            String refundFee,
            Instant effectiveFrom)
            throws Exception {
        HttpResponse<String> created =
                post(
                        "/v1/operator/fee-schedules/" + schedule + "/versions",
                        versionBody(rate, fixed, rounding, refundFee, effectiveFrom),
                        admin,
                        null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return field(created.body(), "feeScheduleVersionId");
    }

    private HttpResponse<String> assign(
            String admin, String merchant, String schedule, String reason) throws Exception {
        return put(
                "/v1/operator/merchants/" + merchant + "/fee-schedule",
                "{\"feeScheduleId\":\"" + schedule + "\",\"reason\":\"" + reason + "\"}",
                admin);
    }

    private static String scheduleBody(String name, String currency) {
        return "{\"name\":\"" + name + "\",\"currency\":\"" + currency + "\"}";
    }

    private static String versionBody(
            String rate, long fixed, String rounding, String refundFee, Instant effectiveFrom) {
        return versionBody(rate, fixed, rounding, refundFee, effectiveFrom, "scheduled repricing");
    }

    private static String versionBody(
            String rate,
            long fixed,
            String rounding,
            String refundFee,
            Instant effectiveFrom,
            String reason) {
        // A NULL effectiveFrom is omitted entirely, which means "immediately" - and it is the
        // only way a caller can say now: the server stamps createdAt after the round trip, so
        // any instant the test computes beforehand is already past and correctly refused.
        return "{\"rate\":"
                + rate
                + ",\"fixedAmountMinor\":"
                + fixed
                + ",\"roundingPolicy\":\""
                + rounding
                + "\",\"refundFeePolicy\":\""
                + refundFee
                + "\""
                + (effectiveFrom == null ? "" : ",\"effectiveFrom\":\"" + effectiveFrom + "\"")
                + ",\"reason\":\""
                + reason
                + "\"}";
    }

    private static Instant now() {
        return Instant.now(CLOCK);
    }

    private static MerchantId merchantId(String raw) {
        return MerchantId.of(UUID.fromString(raw));
    }

    private String onboarded(String admin) throws Exception {
        UUID party = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES"
                            + " (?, 'ORGANISATION', 'Acme Holdings', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO party.customer (id, party_id, status, opened_at,"
                            + " status_changed_at) VALUES (?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now())",
                    IDS.next(),
                    party);
        }
        HttpResponse<String> created =
                post(
                        "/v1/operator/merchants",
                        "{\"partyId\":\"" + party + "\",\"legalName\":\"Acme GmbH\","
                                + "\"displayName\":\"Acme\",\"settlementCurrency\":\"EUR\"}",
                        admin,
                        someKey());
        assertThat(created.statusCode()).isEqualTo(201);
        return field(created.body(), "merchantId");
    }

    private String administrator() throws Exception {
        return sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = registered();
        UUID identity;
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                identity = row.getObject("id", UUID.class);
            }
        }
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(
                    app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        return tokenFrom(authenticate(login).body());
    }

    private String registered() throws Exception {
        String login = "fee." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        return login;
    }

    private HttpResponse<String> register(String login) throws Exception {
        return post(
                "/v1/registrations",
                "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                        + "\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        return post(
                "/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}",
                null,
                someKey());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return send(request.build());
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        return send(request.build());
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        return send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build());
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long countVersions(String schedule) throws SQLException {
        return count(
                "SELECT count(*) FROM merchant.fee_schedule_version WHERE fee_schedule_id = ?",
                UUID.fromString(schedule));
    }

    private static long distinctVersionNumbers(String schedule) throws SQLException {
        return count(
                "SELECT count(DISTINCT version) FROM merchant.fee_schedule_version WHERE"
                        + " fee_schedule_id = ?",
                UUID.fromString(schedule));
    }

    private static long maxVersionNumber(String schedule) throws SQLException {
        return count(
                "SELECT COALESCE(MAX(version), 0) FROM merchant.fee_schedule_version WHERE"
                        + " fee_schedule_id = ?",
                UUID.fromString(schedule));
    }

    private static long count(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                read.setObject(i + 1, arguments[i]);
            }
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String field(String body, String name) {
        Matcher matcher =
                Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }

    private static String tokenFrom(String body) {
        return field(body, "sessionToken");
    }
}
