package com.finapp.app.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.APPROVER;
import static com.finapp.accounting.testing.RegulatoryWorld.PREPARER;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static com.finapp.accounting.testing.ReportBodies.minor;
import static com.finapp.accounting.testing.ReportBodies.object;
import static com.finapp.accounting.testing.ReportBodies.objects;
import static com.finapp.accounting.testing.ReportBodies.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.FigurePath;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RegulatoryForm;
import com.finapp.accounting.RegulatoryFormLine;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunContentParser;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.SafeguardingBooks;
import com.finapp.app.lending.LendingReportController;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The reference safeguarding return, whole (`P14-TSK-019`; ADR-0118 decision 4; {@code INV-ACC-13}, {@code INV-ACC-04}): <strong>the configuration the runbook documents, read from the runbook</strong>, through the real doors, over the real definition's body, through the real machine, in memory. So the document cannot drift, the figures are proven on what a FINAL run really writes, and what the database adds is
 * {@code SafeguardingReturnDatabaseTest}'s (owed, C5). Months of 2096, each closed by the machine's own store edges with a version pinned.
 */
@DisplayName("the reference safeguarding return (P14-TSK-019)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class SafeguardingReturnTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2096-06-10T09:00:00Z"), ZoneOffset.UTC);
    private static final YearMonth JANUARY = RegulatoryWorld.JANUARY;
    private static final YearMonth FEBRUARY = RegulatoryWorld.FEBRUARY;
    private static final LocalDate DAY_ONE = JANUARY.atDay(1);
    private static final LocalDate LAST_DAY = JANUARY.atEndOfMonth();
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final List<String> CLAIMS = List.of("complian", "statutory", "certified", "regulator approved", "filed with", "legal basis", "officially");
    private static final String RUNBOOK = "docs/architecture/OPERATIONS_RUNBOOK.md";
    private static final String HEADING = "#### The reference safeguarding position return: its configuration";

    private final SafeguardingBooks books = new SafeguardingBooks();
    private final SafeguardingPositionDefinition definition = new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books);
    private final RegulatoryWorld world = new RegulatoryWorld(CLOCK, List.of(new SimulatedRegulatoryReportingProvider(SimulatedRegulatoryReportingProvider.Behaviour.ACKNOWLEDGE)), List.of(definition));
    private final ReportGeneration generation = new ReportGeneration((snapshot, id) -> world.periods.find(snapshot, id).map(row -> new ReportGeneration.PeriodState(row.status(), row.lockedAt())), Duration.ofMinutes(4), () -> 0L);
    private final GlMappingVersionId version = GlMappingVersionId.next(world.ids);
    private final Map<IdempotencyKey, IdempotencyRecord> claims = new HashMap<>();
    private final RegulatoryObligationController doors = new RegulatoryObligationController(new RegulatoryObligationDesk(world.obligations,
            new IdempotentExecutor(new Claims(), CLOCK, Duration.ofDays(1), Duration.ofSeconds(30)), new AccountingTransactions() {
                @Override
                public <R> R inTransaction(Function<Connection, R> work) {
                    return work.apply(UOW);
                }
            }, AccountingFlowScope.NONE));

    private RegulatoryObligationId obligation;

    @BeforeEach
    void theDocumentedFormInForce() throws Exception {
        books.journal.version(version, ReferenceGlMapping.version(JANUARY));
        world.open(JANUARY);
        world.open(FEBRUARY);
        obligation = inForce(documented());
    }

    // ------------------------------------------------------------------ the document

    /** The block under the runbook's heading, exactly as a person would send it. */
    private static String block() throws Exception {
        String runbook = Files.readString(RepositoryPaths.locate(RUNBOOK));
        int heading = runbook.indexOf(HEADING);
        assertThat(heading).as("the runbook holds the configuration").isGreaterThan(0);
        int start = runbook.indexOf("```json\n", heading);
        int end = runbook.indexOf("\n```", start);
        return runbook.substring(start + "```json\n".length(), end);
    }

    private static RegulatoryObligationController.RegulatoryObligationRequest documented() throws Exception {
        return new ObjectMapper().readValue(block(), RegulatoryObligationController.RegulatoryObligationRequest.class);
    }

    private RegulatoryObligationId inForce(RegulatoryObligationController.RegulatoryObligationRequest request) {
        RegulatoryObligationDesk.RegulatoryObligationReceipt proposed = as(PREPARER, () -> doors.proposeRegulatoryObligation("proposal", request));
        RegulatoryObligationDesk.RegulatoryObligationReceipt active = as(APPROVER, () -> doors.activateRegulatoryObligation("activation", proposed.obligationId(),
                new RegulatoryObligationController.RegulatoryObligationActivationRequest(proposed.configurationSha256(), REASON)));
        assertThat(active.status()).isEqualTo("ACTIVE");
        return RegulatoryObligationId.of(UUID.fromString(active.obligationId()));
    }

    private <R> R as(Actor actor, Supplier<R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor); CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(world.ids)))) {
            return work.get();
        }
    }

    // ------------------------------------------------------------------ the month

    /** {@code month} locked and closed by the machine's store edges, this suite's version pinned. */
    private void closed(YearMonth month) {
        AccountingPeriodStore.PeriodRow row = world.row(month);
        world.periods.markLocked(UOW, row.id(), row.revision(), ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        world.periods.markCloseProposed(UOW, row.id(), row.revision());
        world.periods.markClosed(UOW, row.id(), row.revision());
        books.journal.period(new TrialBalanceReads.Period(row.id(), month.atDay(1), month.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(version)));
    }

    /** What an accountant's door does: the run claimed, generated by the real definition, completed. */
    private ReportRunStore.RunRow issued(YearMonth month) {
        AccountingPeriodId period = world.row(month).id();
        ReportRuns.Claimed claimed = world.reportRuns.claim(UOW, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), ACCOUNTANT, world.correlation());
        ReportRunStore.RunRow held = claimed.run();
        ReportRunStore.RunRow done = world.reportRuns.complete(UOW, held.id(), held.attempt(), generation.generate(UOW, definition, held.inputs()), ACCOUNTANT).run();
        assertThat(done.status()).isEqualTo(RunStatus.FINAL);
        return done;
    }

    private String textOf(ReportRunStore.RunRow run) {
        return world.runs.content(UOW, run.id()).orElseThrow().text();
    }

    private CanonicalJson.Obj bodyOf(ReportRunStore.RunRow run) {
        return object(RunContentParser.object(textOf(run)), "body");
    }

    private static CanonicalJson.Obj figures(CanonicalJson.Obj body, String currency) {
        return objects(body, "currencies").stream().filter(element -> text(element, "currency").equals(currency)).findFirst().orElseThrow();
    }

    private List<RegulatoryFormLine> linesOf(RegulatorySubmissions.Acted acted) {
        return world.submissions.view(UOW, acted.id()).lines().stream().map(RegulatorySubmissions.LineView::line).toList();
    }

    private static long line(List<RegulatoryFormLine> lines, String code, String currency) {
        return lines.stream().filter(held -> held.code().equals(code) && held.currency().equals(currency)).findFirst().orElseThrow().minor().longValueExact();
    }

    private void anOrdinaryMonth() {
        books.deposit(DAY_ONE, "EUR", 2, 100_00).capital(DAY_ONE, "EUR", 2, 50_00).accepted("loan", DAY_ONE, "EUR", 2, 30_00).disbursed("loan", JANUARY.atDay(3))
                .accepted("waiting", JANUARY.atDay(20), "EUR", 2, 7_00);
        books.deposit(DAY_ONE, "GBP", 2, 40_00);
    }

    // ------------------------------------------------------------------ the configuration

    @Test
    @DisplayName("the exact documented configuration passes the desk's validation: a REFERENCE obligation, the simulated adapter, its one source, through four eyes, no migration and no seed")
    void theDocumentedConfigurationPassesTheDoors() throws Exception {
        RegulatoryObligationController.RegulatoryObligationRequest request = documented();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
        assertThat(request.jurisdictionCode()).isEqualTo(SimulatedRegulatoryReportingProvider.JURISDICTION);
        assertThat(request.adapterName()).isEqualTo(SimulatedRegulatoryReportingProvider.NAME);
        assertThat(request.sources()).singleElement().satisfies(source -> {
            assertThat(source.definition()).isEqualTo(SafeguardingPositionDefinition.CODE);
            assertThat(source.version()).isEqualTo(SafeguardingPositionDefinition.VERSION);
        });
        RegulatoryObligationDesk.RegulatoryObligationView read = as(PREPARER, () -> doors.readRegulatoryObligation(obligation.value().toString()));
        assertThat(read.standing()).isEqualTo(RegulatoryForm.REFERENCE);
        assertThat(read.status()).isEqualTo("ACTIVE");
        assertThat(read.adapterHeld()).isTrue();
    }

    @Test
    @DisplayName("question 3: every rule is arithmetic that must hold in any month: no rule judges the position, so nothing refuses a shortfall")
    void everyRuleIsArithmetic() throws Exception {
        RegulatoryObligationController.RegulatoryObligationRequest request = documented();

        assertThat(request.rules()).allSatisfy(rule -> assertThat(rule.type()).as(rule.code()).isEqualTo("EQUALS"));
        assertThat(request.rules().stream().filter(rule -> rule.code().equals("POSITION_FOOT")).findFirst().orElseThrow().left()).containsExactly("POSITION", "CUSTOMER_FUNDED");
        assertThat(request.rules()).extracting(RegulatoryObligationController.RegulatoryObligationRuleRequest::code).contains("CUSTOMER_WALLETS_FOOT", "MERCHANT_PAYABLES_FOOT", "CREDIT_BALANCES_FOOT");
    }

    @Test
    @DisplayName("question 6: the document, the labels, the mapping and the words the run writes claim nothing: REFERENCE, and no obligation met")
    void nothingClaimsAnything() throws Exception {
        anOrdinaryMonth();
        closed(JANUARY);
        String everything = (block() + textOf(issued(JANUARY))).toLowerCase(Locale.ROOT);

        for (String claim : CLAIMS) {
            assertThat(everything).as(claim).doesNotContain(claim);
        }
        assertThat(everything).contains("no legal obligation is asserted").contains("\"standing\":\"reference\"");
    }

    // ------------------------------------------------------------------ the figures, drawn

    @Test
    @DisplayName("the return produced from FINAL figures with the subtraction: every line of the form, per currency, the figure the run wrote, VALIDATED")
    void theReturnIsDrawnFromFinalFigures() {
        anOrdinaryMonth();
        closed(JANUARY);
        ReportRunStore.RunRow run = issued(JANUARY);

        RegulatorySubmissions.Acted validated = world.validated(world.drafted(obligation, JANUARY));
        List<RegulatoryFormLine> lines = linesOf(validated);

        assertThat(validated.status()).isEqualTo(SubmissionStatus.VALIDATED);
        assertThat(lines).hasSize(25 * 2);
        assertThat(lines).allSatisfy(held -> assertThat(held.run()).isEqualTo(run.id()));
        assertThat(line(lines, "ASSETS", "EUR")).isEqualTo(150_00);
        assertThat(line(lines, "LIABILITIES", "EUR")).isEqualTo(130_00);
        assertThat(line(lines, "PRINCIPAL_OUTSTANDING", "EUR")).isEqualTo(30_00);
        assertThat(line(lines, "PRINCIPAL_COMMITTED", "EUR")).isEqualTo(7_00);
        assertThat(line(lines, "LENDING_FUNDED", "EUR")).isEqualTo(37_00);
        assertThat(line(lines, "POSITION", "EUR")).as("subtracted").isEqualTo(57_00);
        assertThat(line(lines, "POSITION_BEFORE", "EUR")).isEqualTo(20_00);
    }

    @Test
    @DisplayName("question 3: a shortfall month reaches VALIDATED, APPROVED and SUBMITTED, the rules all passed, a currency at a time: short on POSITION in one, short on POSITION_BEFORE and positive on POSITION in another, and the run's flags say each its own")
    void aShortfallMonthIsReportable() {
        books.deposit(DAY_ONE, "EUR", 2, 100_00);
        books.post(JANUARY.atDay(2), "EUR", 2, 130_00, SafeguardingBooks.CHARGEBACK, SafeguardingBooks.CASH);
        books.deposit(DAY_ONE, "GBP", 2, 40_00);
        books.deposit(DAY_ONE, "USD", 2, 100_00).capital(DAY_ONE, "USD", 2, 30_00).accepted("masking", JANUARY.atDay(5), "USD", 2, 30_00);
        books.post(JANUARY.atDay(2), "USD", 2, 50_00, SafeguardingBooks.CHARGEBACK, SafeguardingBooks.CASH);
        closed(JANUARY);
        CanonicalJson.Obj body = bodyOf(issued(JANUARY));
        assertThat(com.finapp.accounting.testing.ReportBodies.flag(figures(body, "USD"), "positionNegative")).isFalse();
        assertThat(com.finapp.accounting.testing.ReportBodies.flag(figures(body, "USD"), "positionBeforeLendingFundedNegative")).isTrue();
        assertThat(com.finapp.accounting.testing.ReportBodies.flag(figures(body, "EUR"), "positionNegative")).isTrue();

        RegulatorySubmissions.Acted validated = world.validated(world.drafted(obligation, JANUARY));

        assertThat(validated.status()).isEqualTo(SubmissionStatus.VALIDATED);
        assertThat(validated.validationPassed()).contains(true);
        List<RegulatoryFormLine> lines = linesOf(validated);
        assertThat(line(lines, "POSITION", "EUR")).isEqualTo(-130_00);
        assertThat(line(lines, "MEMO_CHARGEBACKS", "EUR")).as("the memo says why").isEqualTo(130_00);
        assertThat(line(lines, "POSITION", "GBP")).as("never met by another currency").isZero();
        assertThat(line(lines, "POSITION", "USD")).as("positive").isEqualTo(10_00);
        assertThat(line(lines, "POSITION_BEFORE", "USD")).as("and short").isEqualTo(-20_00);
        assertThat(world.submitted(world.approved(validated)).status()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    @DisplayName("I-1: a wallet and a payable in debit: gross on the form, the debt a memo line out of the position, the footings passing, the month reading short")
    void debtIsGrossOnTheFormAndOutOfThePosition() {
        books.depositFor("A", DAY_ONE, "EUR", 2, 100_00).withdrawFor("B", JANUARY.atDay(2), "EUR", 2, 30_00);
        books.postFor("M1", DAY_ONE, "EUR", 2, 50_00, SafeguardingBooks.CASH, SafeguardingBooks.MERCHANT).postFor("M2", JANUARY.atDay(2), "EUR", 2, 20_00, SafeguardingBooks.MERCHANT, SafeguardingBooks.CASH);
        closed(JANUARY);
        issued(JANUARY);

        RegulatorySubmissions.Acted validated = world.validated(world.drafted(obligation, JANUARY));
        List<RegulatoryFormLine> lines = linesOf(validated);

        assertThat(validated.status()).isEqualTo(SubmissionStatus.VALIDATED);
        assertThat(line(lines, "CUSTOMER_WALLETS", "EUR")).isEqualTo(100_00);
        assertThat(line(lines, "MEMO_WALLET_DEBT", "EUR")).isEqualTo(30_00);
        assertThat(line(lines, "CUSTOMER_WALLETS_NET", "EUR")).isEqualTo(70_00);
        assertThat(line(lines, "MERCHANT_PAYABLES", "EUR")).isEqualTo(50_00);
        assertThat(line(lines, "MEMO_MERCHANT_DEBT", "EUR")).isEqualTo(20_00);
        assertThat(line(lines, "MERCHANT_PAYABLES_NET", "EUR")).isEqualTo(30_00);
        assertThat(line(lines, "ASSETS", "EUR")).isEqualTo(100_00);
        assertThat(line(lines, "LIABILITIES", "EUR")).isEqualTo(150_00);
        assertThat(line(lines, "POSITION_BEFORE", "EUR")).isEqualTo(-50_00);
    }

    @Test
    @DisplayName("I-1: the per-account read and the trial balance drifting apart on one snapshot fail each of the three footings and no other rule: the return stays DRAFT")
    void aDriftBetweenTheTwoReadsFailsValidation() {
        anOrdinaryMonth();
        books.postFor("M", DAY_ONE, "EUR", 2, 5_00, SafeguardingBooks.CASH, SafeguardingBooks.MERCHANT).postFor("L", DAY_ONE, "EUR", 2, 3_00, SafeguardingBooks.CASH, SafeguardingBooks.CREDIT_BALANCE);
        books.plantedDrift = 1;
        closed(JANUARY);
        issued(JANUARY);

        RegulatorySubmissions.Acted validated = world.validated(world.drafted(obligation, JANUARY));

        assertThat(validated.status()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(validated.validationPassed()).contains(false);
        assertThat(world.submissions.view(UOW, validated.id()).validation().orElseThrow().findings()).filteredOn(finding -> !finding.passed())
                .extracting(com.finapp.accounting.RegulatoryValidation.Finding::rule).containsOnly("CUSTOMER_WALLETS_FOOT", "MERCHANT_PAYABLES_FOOT", "CREDIT_BALANCES_FOOT");
    }

    @Test
    @DisplayName("I-2: the words an approver attests and the run's say a positive POSITION proves no cover, and no line or flag says shortfall of it")
    void thePositionsWordsSayItProvesNoCover() throws Exception {
        anOrdinaryMonth();
        closed(JANUARY);
        CanonicalJson.Obj body = bodyOf(issued(JANUARY));

        RegulatoryFormLine position = linesOf(world.drafted(obligation, JANUARY)).stream().filter(held -> held.code().equals("POSITION")).findFirst().orElseThrow();

        assertThat(position.label()).contains("Positive does not prove cover").contains("rises with an accepted loan").contains("POSITION_BEFORE");
        assertThat(text(object(body, "drillDown"), "position")).contains("A positive figure proves no cover");
        assertThat(CanonicalJson.text(body)).doesNotContain("\"shortfall\"");
    }

    @Test
    @DisplayName("question 5: wallets and no lending validates on true zero lines, in every currency and at its own scale, the run's own - no MISSING at a rule")
    void aCurrencyWithNoLendingValidates() {
        books.deposit(DAY_ONE, "EUR", 2, 100_00).accepted("loan", DAY_ONE, "EUR", 2, 5_00);
        books.deposit(DAY_ONE, "GBP", 2, 40_00);
        books.deposit(DAY_ONE, "JPY", 0, 900);
        books.deposit(DAY_ONE, "BHD", 3, 12_345);
        closed(JANUARY);
        issued(JANUARY);

        RegulatorySubmissions.Acted validated = world.validated(world.drafted(obligation, JANUARY));
        List<RegulatoryFormLine> lines = linesOf(validated);

        assertThat(validated.status()).isEqualTo(SubmissionStatus.VALIDATED);
        for (String currency : List.of("GBP", "JPY", "BHD")) {
            assertThat(line(lines, "LENDING_FUNDED", currency)).as(currency).isZero();
            assertThat(line(lines, "PRINCIPAL_COMMITTED", currency)).as(currency).isZero();
            assertThat(line(lines, "PRINCIPAL_WRITTEN_OFF", currency)).as(currency).isZero();
        }
        assertThat(lines.stream().filter(held -> held.code().equals("POSITION")).map(RegulatoryFormLine::scale)).containsExactlyInAnyOrder(3, 2, 2, 0);
    }

    @Test
    @DisplayName("question 5: a currency's zero and an unreadable proof do not look alike: the read fails, the generation fails, no FINAL stands and the draft is refused, across every currency, where a month before validated")
    void anUnreadableProofRefusesTheDraft() {
        books.deposit(DAY_ONE, "EUR", 2, 100_00);
        books.deposit(DAY_ONE, "GBP", 2, 40_00);
        closed(JANUARY);
        issued(JANUARY);
        assertThat(world.validated(world.drafted(obligation, JANUARY)).status()).isEqualTo(SubmissionStatus.VALIDATED);

        closed(FEBRUARY);
        books.unreadable = true;
        ReportRuns.Claimed claimed = world.reportRuns.claim(UOW, SafeguardingPositionDefinition.CODE, world.row(FEBRUARY).id(), RunParameters.none(), ACCOUNTANT, world.correlation());
        assertThatThrownBy(() -> generation.generate(UOW, definition, claimed.run().inputs())).isInstanceOf(AccountingStorageException.class);

        assertThatThrownBy(() -> world.drafted(obligation, FEBRUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);
    }

    @Test
    @DisplayName("a month with no line in any currency cannot be drawn: a figure that is not there is refused, never a zero")
    void aMonthWithNothingCannotBeDrawn() {
        closed(JANUARY);
        issued(JANUARY);

        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).isInstanceOfSatisfying(RegulatoryForm.FigureUnresolved.class, refused -> assertThat(refused.why()).isEqualTo(FigurePath.Why.NOTHING));
    }

    @Test
    @DisplayName("only FINAL runs of CLOSED months: not a LOCKED month's, not a CLOSED month with no run, and after a reopen not the superseded run; a new revision's own FINAL draws")
    void onlyFinalRunsOfClosedMonths() {
        anOrdinaryMonth();
        AccountingPeriodStore.PeriodRow row = world.row(JANUARY);
        world.periods.markLocked(UOW, row.id(), row.revision(), ACCOUNTANT, Optional.of(new AccountingPeriodStore.Pin(version, 1)));
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        world.periods.markCloseProposed(UOW, row.id(), row.revision());
        world.periods.markClosed(UOW, row.id(), row.revision());
        books.journal.period(new TrialBalanceReads.Period(row.id(), JANUARY.atDay(1), JANUARY.atEndOfMonth(), PeriodStatus.CLOSED, Optional.of(version)));
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).as("no FINAL run yet").isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        issued(JANUARY);
        world.reopened(JANUARY);
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).as("the month is OPEN").isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        closed(JANUARY);
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).as("the old revision's run answers nothing").isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        issued(JANUARY);
        assertThat(world.drafted(obligation, JANUARY).periodRevision()).isEqualTo(world.row(JANUARY).revision()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ INV-ACC-04

    @Test
    @DisplayName("INV-ACC-04: regenerating an earlier month is byte-identical, IDENTICAL by the machine's own hash, after the next month disbursed, cancelled, accepted and wrote off")
    void regeneratingAnEarlierMonthIsIdentical() {
        anOrdinaryMonth();
        books.accepted("failing", JANUARY.atDay(21), "EUR", 2, 9_00);
        closed(JANUARY);
        ReportRunStore.RunRow january = issued(JANUARY);
        long committed = minor(figures(bodyOf(january), "EUR"), "principalCommitted").longValueExact();
        assertThat(committed).isEqualTo(16_00);

        LocalDate february = FEBRUARY.atDay(2);
        books.disbursed("waiting", february).cancelled("failing", february).accepted("later", february, "EUR", 2, 77_00).writtenOff(february, "EUR", 2, 30_00, 1_00);
        closed(FEBRUARY);
        ReportRunStore.RunRow second = issued(FEBRUARY);

        ReportGeneration.Generated regenerated = generation.generate(UOW, definition, world.runs.find(UOW, january.id()).orElseThrow().inputs());
        assertThat(regenerated.content()).isEqualTo(textOf(january));
        assertThat(regenerated.sha256()).isEqualTo(january.contentSha256().orElseThrow());
        CanonicalJson.Obj next = figures(bodyOf(second), "EUR");
        assertThat(minor(next, "principalCommitted").longValueExact()).as("not vacuous: the next month reads what moved").isEqualTo(77_00);
        assertThat(minor(next, "principalWrittenOff").longValueExact()).isEqualTo(30_00);
    }

    @Test
    @DisplayName("a reopened month's new revision: the first FINAL superseded and its bytes kept; the new one holds the adjustment, the position moved by exactly that, committed unmoved")
    void aReopenedMonthsNewRevision() {
        anOrdinaryMonth();
        closed(JANUARY);
        ReportRunStore.RunRow first = issued(JANUARY);
        String firstBytes = textOf(first);

        world.reopened(JANUARY);
        books.post(LAST_DAY, "EUR", 2, 2_00, SafeguardingBooks.WALLET, "FEE_REVENUE/OPERATIONAL");
        books.disbursed("waiting", FEBRUARY.atDay(1));
        closed(JANUARY);
        ReportRunStore.RunRow second = issued(JANUARY);

        assertThat(world.runs.find(UOW, first.id()).orElseThrow().status()).isEqualTo(RunStatus.SUPERSEDED);
        assertThat(textOf(first)).isEqualTo(firstBytes);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.periodRevision()).isEqualTo(first.periodRevision() + 1);
        CanonicalJson.Obj before = figures(object(RunContentParser.object(firstBytes), "body"), "EUR");
        CanonicalJson.Obj after = figures(bodyOf(second), "EUR");
        assertThat(minor(after, "position").subtract(minor(before, "position")).longValueExact()).isEqualTo(2_00);
        assertThat(minor(after, "principalCommitted")).isEqualTo(minor(before, "principalCommitted"));
    }

    @Test
    @DisplayName("a second claim of one question answers the one run, and a second instance generating it writes the same bytes: two definitions, one content")
    void aSecondClaimAnswersOneRunOfOneContent() {
        anOrdinaryMonth();
        closed(JANUARY);
        AccountingPeriodId period = world.row(JANUARY).id();
        ReportRuns.Claimed firstClaim = world.reportRuns.claim(UOW, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), ACCOUNTANT, world.correlation());
        ReportRuns.Claimed secondClaim = world.reportRuns.claim(UOW, SafeguardingPositionDefinition.CODE, period, RunParameters.none(), ACCOUNTANT, world.correlation());
        assertThat(secondClaim.run().id()).isEqualTo(firstClaim.run().id());
        assertThat(secondClaim.mine()).isFalse();

        SafeguardingPositionDefinition anotherInstance = new SafeguardingPositionDefinition(books.journal, books.journal.counterparties(), books);
        ReportGeneration.Generated one = generation.generate(UOW, definition, firstClaim.run().inputs());
        ReportGeneration.Generated other = generation.generate(UOW, anotherInstance, secondClaim.run().inputs());

        assertThat(other.content()).isEqualTo(one.content());
        ReportRuns.Concluded done = world.reportRuns.complete(UOW, firstClaim.run().id(), firstClaim.run().attempt(), one, ACCOUNTANT);
        assertThat(done.moved()).isTrue();
        assertThat(world.reportRuns.complete(UOW, secondClaim.run().id(), secondClaim.run().attempt(), other, ACCOUNTANT).moved()).as("the second converges, writing nothing").isFalse();
        assertThat(world.runs.find(UOW, firstClaim.run().id()).orElseThrow().status()).isEqualTo(RunStatus.FINAL);
    }

    @Test
    @DisplayName("a DIVERGED run is seen: a line planted behind a locked month changes the regenerated hash, which the machine would answer DIVERGED, and the meter holds this definition's own series from the start")
    void aPlantedLineWouldDiverge() {
        anOrdinaryMonth();
        closed(JANUARY);
        ReportRunStore.RunRow run = issued(JANUARY);
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ReportRunMetrics metrics = new ReportRunMetrics(registry, world.definitions);
        assertThat(registry.get(ReportRunMetrics.DIVERGED).tag("definition", SafeguardingPositionDefinition.CODE).counter().count()).isZero();

        books.post(LAST_DAY, "EUR", 2, 1, SafeguardingBooks.CASH, SafeguardingBooks.WALLET);

        assertThat(generation.generate(UOW, definition, run.inputs()).sha256()).isNotEqualTo(run.contentSha256().orElseThrow());
        metrics.diverged(run);
        assertThat(registry.get(ReportRunMetrics.DIVERGED).tag("definition", SafeguardingPositionDefinition.CODE).counter().count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ question 7

    @Test
    @DisplayName("every line drills down: its run, the run's hash and its path, agreeing with the run's own bytes; the view names a route that opens under ACCOUNTING_READ, who holds it, and the run's body says what stands behind that figure")
    void everyLineDrillsDown() {
        anOrdinaryMonth();
        closed(JANUARY);
        ReportRunStore.RunRow run = issued(JANUARY);
        CanonicalJson.Obj body = bodyOf(run);

        RegulatorySubmissions.Acted drafted = world.drafted(obligation, JANUARY);
        RegulatorySubmissions.View view = world.submissions.view(UOW, drafted.id());

        assertThat(view.lines()).hasSize(25 * 2).allSatisfy(held -> {
            RegulatoryFormLine line = held.line();
            assertThat(line.run()).isEqualTo(run.id());
            assertThat(line.runSha256()).isEqualTo(run.contentSha256().orElseThrow());
            assertThat(line.figure()).startsWith("body.currencies[currency=" + line.currency() + "].");
            assertThat(held.agrees()).as(line.code()).isTrue();
            RegulatorySubmissionDesk.RegulatoryDrillDownView route = RegulatorySubmissionDesk.drillDown(line, view.row());
            assertThat(route.route()).isEqualTo("GET /v1/operator/accounting/report-runs/" + run.id().value());
            assertThat(route.requiresPermission()).isEqualTo(PermissionName.ACCOUNTING_READ.name());
            assertThat(route.heldByRoles()).contains(RoleName.ACCOUNTANT.name());
            assertThat(text(object(body, "drillDown"), line.figure().substring(line.figure().lastIndexOf('.') + 1))).as(line.code()).isNotBlank();
        });
    }

    @Test
    @DisplayName("no line names a door its reader cannot open: each route the run's words name is a real mapping asking the permission they say, and committed's own door is one ACCOUNTING_READ's holders do not hold, as it says")
    void theRoutesItNamesAreRealDoors() throws Exception {
        anOrdinaryMonth();
        closed(JANUARY);
        CanonicalJson.Obj drillDown = object(bodyOf(issued(JANUARY)), "drillDown");
        Set<String> named = new TreeSet<>();
        Matcher routes = Pattern.compile("GET (/v1/[^ ?]+)").matcher(CanonicalJson.text(drillDown));
        while (routes.find()) {
            named.add(routes.group(1));
        }
        assertThat(named).containsExactlyInAnyOrder("/v1/operator/accounting/report-runs/{runId}/lines", "/v1/operator/accounting/gl/entries/{entryId}", "/v1/operator/reports/lending/capital");

        assertThat(permissionOf(ReportRunLinesController.class, "/v1/operator/accounting/report-runs", "/{runId}/lines")).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(permissionOf(GeneralLedgerController.class, "/v1/operator/accounting/gl", "/entries/{entryId}")).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(permissionOf(ReportRunController.class, "/v1/operator/accounting/report-runs", "/{runId}")).isEqualTo(PermissionName.ACCOUNTING_READ);
        assertThat(permissionOf(LendingReportController.class, "/v1/operator/reports/lending", "/capital")).isEqualTo(PermissionName.LENDING_INVESTIGATE);
        assertThat(Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(PermissionName.ACCOUNTING_READ)).filter(role -> role.permissions().contains(PermissionName.LENDING_INVESTIGATE)).toList())
                .as("the note says ACCOUNTING_READ does not carry it").isEmpty();
        assertThat(text(drillDown, "principalCommitted")).contains("/v1/operator/reports/lending/capital").contains("LENDING_INVESTIGATE");
    }

    /** The permission {@code path} asks, the route's prefix checked against the controller's own mapping (the {@code /v1} is the application's). */
    private static PermissionName permissionOf(Class<?> controller, String prefix, String path) {
        assertThat("/v1" + controller.getAnnotation(RequestMapping.class).path()[0]).isEqualTo(prefix);
        for (Method method : controller.getDeclaredMethods()) {
            GetMapping mapping = method.getAnnotation(GetMapping.class);
            if (mapping != null && (Arrays.asList(mapping.value()).contains(path) || Arrays.asList(mapping.path()).contains(path))) {
                return method.getAnnotation(RequiresPermission.class).value();
            }
        }
        throw new AssertionError(controller.getSimpleName() + " has no GET " + path);
    }

    // ------------------------------------------------------------------ the key store

    private final class Claims implements IdempotencyRecordStore<Connection> {

        @Override
        public ClaimOutcome claim(Connection unitOfWork, IdempotencyKey key, RequestFingerprint fingerprint, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            if (claims.containsKey(key)) {
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            claims.put(key, new IdempotencyRecord(key, fingerprint, IdempotencyState.IN_PROGRESS, StoredResponse.empty(), correlationId, now, expiresAt));
            return ClaimOutcome.CLAIMED;
        }

        @Override
        public Optional<IdempotencyRecord> find(Connection unitOfWork, IdempotencyKey key) {
            return Optional.ofNullable(claims.get(key));
        }

        @Override
        public boolean complete(Connection unitOfWork, IdempotencyKey key, IdempotencyState terminalState, StoredResponse response, Instant completedAt) {
            IdempotencyRecord held = claims.get(key);
            if (held == null || held.state() != IdempotencyState.IN_PROGRESS) {
                return false;
            }
            claims.put(key, new IdempotencyRecord(key, held.fingerprint(), terminalState, response, held.correlationId(), held.createdAt(), held.expiresAt()));
            return true;
        }

        @Override
        public boolean reclaimIfLeaseExpired(Connection unitOfWork, IdempotencyKey key, CorrelationId correlationId, Instant now, Instant expiresAt, Duration lease) {
            return false;
        }
    }
}
