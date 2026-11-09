package com.finapp.accounting;

import static com.finapp.accounting.testing.RegulatoryWorld.APPROVER;
import static com.finapp.accounting.testing.RegulatoryWorld.FEBRUARY;
import static com.finapp.accounting.testing.RegulatoryWorld.JANUARY;
import static com.finapp.accounting.testing.RegulatoryWorld.PREPARER;
import static com.finapp.accounting.testing.RegulatoryWorld.REASON;
import static com.finapp.accounting.testing.RegulatoryWorld.SENDER;
import static com.finapp.accounting.testing.RegulatoryWorld.UOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatoryReportingProvider.CallKind;
import com.finapp.accounting.RegulatorySubmissions.Acted;
import com.finapp.accounting.RegulatorySubmissions.Answered;
import com.finapp.accounting.RegulatorySubmissions.Attested;
import com.finapp.accounting.RegulatorySubmissions.BlockedBy;
import com.finapp.accounting.RegulatorySubmissions.Claimed;
import com.finapp.accounting.RegulatorySubmissions.Disposition;
import com.finapp.accounting.RegulatorySubmissions.Draft;
import com.finapp.accounting.RegulatorySubmissions.Recorded;
import com.finapp.accounting.RegulatorySubmissions.ResendPermit;
import com.finapp.accounting.RegulatorySubmissions.View;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.event.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The regulatory submission's machine, hermetically (`P14-TSK-018`; ADR-0118; {@code INV-ACC-13}, {@code INV-ACC-04}, {@code INV-HIST-02}, {@code INV-AUD-04}): only FINAL figures feed a draft; a status that round-trips is never mistaken for the books it read; four eyes, and the approver does not send; every state's
 * door out; one live return per form and month; a correction chained; the drive's first send, its inquiries, a resend only on a definite "not received"; a late or contrary answer kept and never an edge. Over in-memory stores holding {@code accounting V010}'s ranks; {@code
 * RegulatorySubmissionDatabaseTest} is the live half (owed, C5). A test here naming a race says which part is really raced: where the row's lock is the arbiter, this proves what a loser meets once it has been serialised.
 */
@DisplayName("the regulatory submission's machine (P14-TSK-018)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class RegulatorySubmissionsTest {

    private final ReportRunsTest.MovableClock clock = new ReportRunsTest.MovableClock(Instant.parse("2096-03-10T09:00:00Z"));
    private final RegulatoryWorld world = new RegulatoryWorld(clock);
    private final RegulatorySubmissions machine = world.submissions;
    private RegulatoryObligationId obligation;

    @BeforeEach
    void januaryClosedAndIssued() {
        world.closed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
        obligation = world.active();
    }

    private static Attested read(Acted acted) {
        return new Attested(acted.contentSha256(), acted.periodRevision());
    }

    private RegulatorySubmissionStore.Row row(Acted acted) {
        return world.store.find(UOW, acted.id()).orElseThrow();
    }

    /** What the non-delivery door hands the act: the provider's answer of a moment ago. */
    private static final Optional<Recorded> ASKED = Optional.of(new Recorded(Disposition.NOT_RECEIVED, false));

    private ResendPermit permit(Claimed claimed) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return machine.permitResend(UOW, claimed, world.correlation());
        }
    }

    private void answered(Claimed claimed, Answer answer, CallKind call) {
        asThePlatform(() -> machine.record(UOW, claimed, new Answered(call, answer), Duration.ofMinutes(15), world.correlation()));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> codes() {
        return world.audit.stream().map(record -> record.operation().code()).toList();
    }

    // ------------------------------------------------------------------ the draft

    @Test
    @DisplayName("a draft is drawn from the month's FINAL run: REFERENCE, one line per currency, each with its run, its hash and its resolved path, amounts in minor units")
    void aDraftIsTracedLineByLine() {
        Acted drafted = world.drafted(obligation, JANUARY);

        assertThat(drafted.status()).isEqualTo(SubmissionStatus.DRAFT);
        List<RegulatoryFormLine> lines = world.store.lines(UOW, drafted.id());
        assertThat(lines).extracting(RegulatoryFormLine::code, RegulatoryFormLine::currency, RegulatoryFormLine::figure).containsExactly(
                org.assertj.core.groups.Tuple.tuple("ASSETS", "EUR", "body.equation[currency=EUR].assets"),
                org.assertj.core.groups.Tuple.tuple("ASSETS", "USD", "body.equation[currency=USD].assets"),
                org.assertj.core.groups.Tuple.tuple("LIABILITIES", "EUR", "body.equation[currency=EUR].liabilities"),
                org.assertj.core.groups.Tuple.tuple("LIABILITIES", "USD", "body.equation[currency=USD].liabilities"));
        ReportRunStore.RunRow run = world.runs.all().get(0);
        assertThat(lines).allSatisfy(line -> {
            assertThat(line.run()).isEqualTo(run.id());
            assertThat(line.runSha256()).isEqualTo(run.contentSha256().orElseThrow());
            assertThat(line.scale()).isEqualTo(2);
        });
        assertThat(lines.get(0).minor().longValueExact()).isEqualTo(100_00);
        String content = world.store.content(UOW, drafted.id()).orElseThrow();
        assertThat(content).contains("\"standing\":\"REFERENCE\"").contains("\"minor\":\"10000\"").contains(run.id().value().toString());
        assertThat(CanonicalJson.sha256(content)).isEqualTo(drafted.contentSha256());
        assertThat(codes()).contains("regulatory.SubmissionDrafted");
        assertThat(lines).extracting(line -> line.minor().longValueExact()).containsExactly(100_00L, 50_00L, 100_00L, 50_00L);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
    }

    @Test
    @DisplayName("only FINAL figures feed a draft: an OPEN month's PROVISIONAL run, a LOCKED month not yet closed, a CLOSED month with no run, a run superseded - each refused, nothing born")
    void onlyFinalRunsFeedADraft() {
        // An OPEN month with a PROVISIONAL run of the same question.
        world.open(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        assertThat(world.runs.all().stream().filter(run -> run.period().equals(FEBRUARY)).findFirst().orElseThrow().status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThatThrownBy(() -> world.drafted(obligation, FEBRUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class);

        // A LOCKED month, its run FINAL: a return asks after the close.
        AccountingPeriodStore.PeriodRow february = world.row(FEBRUARY);
        world.periods.markLocked(UOW, february.id(), february.revision(), PREPARER, Optional.empty());
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        assertThatThrownBy(() -> world.drafted(obligation, FEBRUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class).hasMessageContaining("CLOSED");

        // A CLOSED month with no FINAL run of the form's source.
        RegulatoryWorld empty = new RegulatoryWorld(Clock.fixed(Instant.parse("2096-03-10T09:00:00Z"), java.time.ZoneOffset.UTC));
        empty.closed(JANUARY);
        RegulatoryObligationId version = empty.active();
        assertThatThrownBy(() -> empty.drafted(version, JANUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class).hasMessageContaining("an accountant issues it");

        // A month reopened: refused while OPEN; re-closed, its FINAL is superseded and no new one issued, so still refused though it reads CLOSED.
        empty.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));
        empty.reopened(JANUARY);
        assertThatThrownBy(() -> empty.drafted(version, JANUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class).hasMessageContaining("OPEN");
        empty.reclosed(JANUARY);
        assertThat(empty.row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThatThrownBy(() -> empty.drafted(version, JANUARY)).isInstanceOf(RegulatorySubmissions.SourceNotFinal.class).hasMessageContaining("revision 2");

        assertThat(world.store.standing(UOW, Optional.empty(), 100)).isEmpty();
        assertThat(empty.store.standing(UOW, Optional.empty(), 100)).isEmpty();
    }

    @Test
    @DisplayName("a figure path resolving to nothing, to more than one, or to no amount refuses, and is never a zero")
    void aFigureThatIsNotOneAmountRefuses() {
        RegulatoryObligationId nothing = world.active(2, form("body.equation[currency=CHF].assets"));
        assertThatThrownBy(() -> world.drafted(nothing, JANUARY)).isInstanceOf(RegulatoryForm.FigureUnresolved.class)
                .satisfies(refused -> assertThat(((RegulatoryForm.FigureUnresolved) refused).why()).isEqualTo(FigurePath.Why.NOTHING));
        RegulatoryObligationId many = world.active(3, form("body.rows[glAccount=1000,currency=EUR].closing"));
        assertThatThrownBy(() -> world.drafted(many, JANUARY)).isInstanceOf(RegulatoryForm.FigureUnresolved.class)
                .satisfies(refused -> assertThat(((RegulatoryForm.FigureUnresolved) refused).why()).isEqualTo(FigurePath.Why.MORE_THAN_ONE));
        RegulatoryObligationId notAnAmount = world.active(4, form("body.equation[currency=EUR].currency"));
        assertThatThrownBy(() -> world.drafted(notAnAmount, JANUARY)).isInstanceOf(RegulatoryForm.FigureUnresolved.class)
                .satisfies(refused -> assertThat(((RegulatoryForm.FigureUnresolved) refused).why()).isEqualTo(FigurePath.Why.NOT_AN_AMOUNT));
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).isEmpty();
    }

    private static RegulatoryObligationConfiguration form(String figure) {
        return new RegulatoryObligationConfiguration(List.of(new RegulatoryObligationConfiguration.Source(RegulatoryWorld.SOURCE, 1)),
                List.of(new RegulatoryObligationConfiguration.Line("ONLY", "The only line", RegulatoryWorld.SOURCE, figure)),
                List.of(new RegulatoryObligationConfiguration.Rule("POSITIVE", RegulatoryRuleType.NON_NEGATIVE, List.of("ONLY"), List.of())));
    }

    @Test
    @DisplayName("a run's bytes are proved when it is drawn from: a mismatch with its stored hash refuses")
    void aRunsBytesAreProvedWhenDrawn() {
        ReportRunStore.RunRow run = world.runs.all().get(0);
        world.runs.corruptContentForTest(run.id(), "{\"body\":{}}");

        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).isInstanceOf(RegulatorySubmissions.RunBytesDiffer.class)
                .hasMessageNotContaining("10000");
    }

    @Test
    @DisplayName("one live return per form and month; a withdrawal frees it; a retired version or an adapter no longer held refuses; a plain draft after a sent return refuses")
    void oneLiveReturnPerFormAndMonth() {
        Acted first = world.drafted(obligation, JANUARY);
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).isInstanceOf(RegulatorySubmissions.SubmissionLive.class);

        machine.withdraw(UOW, first.id(), REASON, PREPARER, world.correlation());
        Acted second = world.drafted(obligation, JANUARY);
        assertThat(second.id()).isNotEqualTo(first.id());

        Acted sent = world.submitted(world.approved(world.validated(second)));
        assertThat(sent.status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThatThrownBy(() -> world.drafted(obligation, JANUARY)).isInstanceOf(RegulatorySubmissions.SubmissionLive.class).hasMessageContaining("correction");

        // Another month, so nothing above answers: a retired version, then this build holding no adapter for the one in force.
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
        RegulatoryObligationId inForce = world.active(2, RegulatoryWorld.configuration());
        assertThatThrownBy(() -> world.drafted(obligation, FEBRUARY)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("not in force");
        RegulatorySubmissions noAdapter = new RegulatorySubmissions(world.periods, world.store, world.obligationStore, world.runs, new RegulatoryAdapters(List.of()), (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, world.ids, world.clock,
                Duration.ofDays(1));
        assertThatThrownBy(() -> noAdapter.draft(UOW, new Draft(inForce, world.row(FEBRUARY).id(), Optional.empty(), REASON), PREPARER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("no adapter");
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).extracting(RegulatorySubmissionStore.Row::month).doesNotContain(FEBRUARY);
    }

    @Test
    @DisplayName("ten threads draft one form and month: one row, nine refused - the fixture's arbiter raced; V010's unique racing is RegulatorySubmissionDatabaseTest#tenDraftsOneLiveRow")
    void tenDraftsOneRow() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            outcomes.add(pool.submit(() -> {
                start.await();
                try {
                    world.drafted(obligation, JANUARY);
                    return true;
                } catch (RegulatorySubmissions.SubmissionLive refused) {
                    return false;
                }
            }));
        }
        start.countDown();
        long born = 0;
        for (Future<Boolean> outcome : outcomes) {
            born += outcome.get(30, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdownNow();
        assertThat(born).isEqualTo(1);
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).hasSize(1);
    }

    @Test
    @DisplayName("a correction references the original: only a concluded return that reached for the provider, of its own form and month, once; a refused or withdrawn correction frees it")
    void aCorrectionReferencesTheOriginal() {
        Acted original = world.sent(obligation, JANUARY);
        AccountingPeriodId january = world.row(JANUARY).id();

        // Not concluded yet.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);

        rejected(original);
        Acted correction = machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation());
        assertThat(row(correction).corrects()).contains(original.id());
        assertThat(world.store.content(UOW, correction.id()).orElseThrow()).contains("\"correctsSubmissionId\":\"" + original.id().value() + "\"");
        assertThat(world.store.find(UOW, original.id()).orElseThrow().correctedBy()).contains(correction.id());
        assertThat(world.store.find(UOW, original.id()).orElseThrow().status()).isEqualTo(SubmissionStatus.REJECTED);

        // A second correction of the one original: refused while the first stands, by the machine's own judgement under the original's lock, the unique only a backstop.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionLive.class).hasMessageContaining("already has a correction");

        // Withdrawn, it frees the original for another.
        machine.withdraw(UOW, correction.id(), REASON, PREPARER, world.correlation());
        Acted again = machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation());
        assertThat(row(again).corrects()).contains(original.id());

        // Refused, it frees the original too.
        machine.refuse(UOW, world.validated(again).id(), REASON, APPROVER, world.correlation());
        Acted third = machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation());
        assertThat(row(third).corrects()).contains(original.id());

        // A refused or withdrawn return never reached for the provider: nothing corrects it.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(again.id()), REASON), PREPARER, world.correlation()))
                .as("refused").isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
        machine.withdraw(UOW, third.id(), REASON, PREPARER, world.correlation());
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(third.id()), REASON), PREPARER, world.correlation()))
                .as("withdrawn").isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);

        // Another form's.
        RegulatoryObligations.Acted otherForm = world.obligations.propose(UOW, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, "REF-TEST-OTHER", 1, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON, PREPARER, world.correlation());
        world.obligations.activate(UOW, otherForm.id(), otherForm.configurationSha256(), REASON, APPROVER, world.correlation());
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(otherForm.id(), january, Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);

        // Unknown, and another month's.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(RegulatorySubmissionId.next(world.ids)), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(FEBRUARY).id(), Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
    }

    @Test
    @DisplayName("the fixture holds a line to V010's regulatory_submission_line_bounded, so a path past 256 characters fails here as it does there")
    void aLineIsBoundedByTheStoreToo() {
        Acted draft = world.drafted(obligation, JANUARY);
        RegulatoryFormLine held = world.store.lines(UOW, draft.id()).get(0);
        RegulatoryFormLine tooLong = new RegulatoryFormLine(1, held.code(), held.label(), held.currency(), held.minor(), held.scale(), held.run(), held.runSha256(), held.source(), "body." + "x".repeat(252));
        assertThatThrownBy(() -> world.store.insertLines(UOW, draft.id(), List.of(tooLong))).isInstanceOf(IllegalStateException.class).hasMessageContaining("regulatory_submission_line_bounded");
    }

    private void rejected(Acted sent) {
        asThePlatform(() -> {
            Claimed claimed = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
            machine.record(UOW, claimed, new Answered(CallKind.SUBMIT, Answer.rejected(Optional.of("PROVIDER-1"), "FORM_REFUSED", "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation());
        });
    }

    private static void asThePlatform(Runnable work) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            work.run();
        }
    }

    // ------------------------------------------------------------------ a status that round-trips

    @Test
    @DisplayName("a month closed, reopened and re-closed reads CLOSED and is not the month a return read: validate, approve and submit each refuse, though the month and the return read as they did")
    void aRoundTrippedMonthIsNotTheMonthItWas() {
        Acted draft = world.drafted(obligation, JANUARY);
        roundTrip();
        assertThat(world.row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(world.row(JANUARY).revision()).isEqualTo(2);
        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("revision");
        assertThat(row(draft).status()).isEqualTo(SubmissionStatus.DRAFT);
        machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation());

        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        roundTrip();
        assertThat(world.row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThatThrownBy(() -> world.approved(validated)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(validated).status()).isEqualTo(SubmissionStatus.VALIDATED);
        machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation());

        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));
        roundTrip();
        assertThat(world.row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThatThrownBy(() -> world.submitted(approved)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(approved).status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(row(approved).monthMoved()).isTrue();
        // Named by the present revision it is still refused: it read the old one.
        assertThatThrownBy(() -> machine.submit(UOW, approved.id(), new Attested(approved.contentSha256(), world.row(JANUARY).revision()), REASON, SENDER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
    }

    @Test
    @DisplayName("each judgement holds alone: a month that moved while its run reads FINAL; a run no longer FINAL in a month that has not moved")
    void eachJudgementHoldsAlone() {
        // The month alone. A reopen supersedes its runs in one transaction, so only a defect or a raw writer could leave this: that is why the month is compared as well as the run.
        Acted january = world.drafted(obligation, JANUARY);
        AccountingPeriodStore.PeriodRow row = world.row(JANUARY);
        assertThat(world.periods.markReopened(UOW, row.id(), row.revision())).isTrue();
        world.reclosed(JANUARY);
        assertThat(world.row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(world.runs.all().stream().filter(run -> run.period().equals(JANUARY)).findFirst().orElseThrow().status()).isEqualTo(RunStatus.FINAL);
        assertThatThrownBy(() -> world.validated(january)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("moved");

        // The run alone, in a month of its own, whose revision has not moved.
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
        Acted february = world.drafted(obligation, FEBRUARY);
        int revision = world.row(FEBRUARY).revision();
        world.runs.supersedeFinal(UOW, world.row(FEBRUARY).id(), world.correlation());
        assertThat(world.row(FEBRUARY).revision()).isEqualTo(revision);
        assertThatThrownBy(() -> world.validated(february)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("no longer stands FINAL");
    }

    /** Reopened, closed again, a run issued again under the new revision. */
    private void roundTrip() {
        world.reopened(JANUARY);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00));
    }

    @Test
    @DisplayName("a month only reopened, not yet re-closed: every act going forward refuses")
    void anOpenMonthRefusesEveryActGoingForward() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        world.reopened(JANUARY);
        assertThatThrownBy(() -> world.approved(validated)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("OPEN");
        machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation());
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));

        Acted draft = world.drafted(obligation, JANUARY);
        world.reopened(JANUARY);
        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("OPEN");
        machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation());
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));

        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));
        world.reopened(JANUARY);
        assertThatThrownBy(() -> world.submitted(approved)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("OPEN");
    }

    @Test
    @DisplayName("a return sent, then its month reopened: frozen, standing, and flagged on the read - the books moved, not the return")
    void aReturnSentThenReopenedStandsFrozenAndIsFlagged() {
        Acted sent = world.sent(obligation, JANUARY);
        String before = world.store.content(UOW, sent.id()).orElseThrow();
        world.reopened(JANUARY);

        View view = machine.view(UOW, sent.id());
        assertThat(view.row().status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(view.row().monthMoved()).isTrue();
        assertThat(view.row().periodRevision()).isEqualTo(1);
        assertThat(view.row().periodRevisionNow()).isEqualTo(2);
        assertThat(view.blockedBy()).contains(BlockedBy.MONTH_MOVED);
        assertThat(world.store.content(UOW, sent.id())).contains(before);
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isEqualTo(1);

        // Answered, it stays frozen and flagged.
        asThePlatform(() -> machine.record(UOW, machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(), new Answered(CallKind.SUBMIT, Answer.acknowledged("PROVIDER-1", "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isEqualTo(1);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(90_00, 90_00, 50_00, 50_00));

        // A draft of a correction clears nothing: the regulator still holds the figure.
        Acted correction = machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.of(sent.id()), REASON), PREPARER, world.correlation());
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isEqualTo(1);
        assertThat(row(sent).overtaken()).isTrue();

        // Nor does one rejected.
        Acted rejectedCorrection = world.submitted(world.approved(world.validated(correction)));
        rejected(rejectedCorrection);
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isEqualTo(1);
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());

        // Only an acknowledged return of the form at the present revision does, down a chain of corrections.
        Acted second = world.submitted(world.approved(world.validated(machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.of(rejectedCorrection.id()), REASON), PREPARER, world.correlation()))));
        answered(machine.claim(UOW, second.id(), Duration.ofMinutes(2)).orElseThrow(), Answer.acknowledged("PROVIDER-3", bytes("{}")), CallKind.SUBMIT);
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isZero();
        assertThat(row(sent).overtaken()).isFalse();
        assertThat(row(sent).monthMoved()).as("the month still moved: only the alarm cleared").isTrue();
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).extracting(RegulatorySubmissionStore.Row::id).doesNotContain(sent.id());
    }

    // ------------------------------------------------------------------ a month that moved before the send

    @Test
    @DisplayName("SUBMITTED with nothing sent, the month reopened and re-closed: the first attempt only asks, the provider is handed nothing, and a definite not received permits no send")
    void aMonthThatMovedBeforeTheSendIsNeverSent() {
        Acted sent = world.sent(obligation, JANUARY);
        world.reopened(JANUARY);
        world.reclosed(JANUARY);

        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        assertThat(first.row().attempts()).isEqualTo(1);
        assertThat(first.row().monthMoved()).as("read in the claim's own transaction").isTrue();
        Answered asked = machine.ask(first);
        assertThat(asked.call()).isEqualTo(CallKind.INQUIRE);
        assertThat(world.provider.submits()).isZero();
        assertThat(world.provider.inquiries()).isEqualTo(1);

        asThePlatform(() -> assertThat(machine.record(UOW, first, asked, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.NOT_RECEIVED));
        assertThat(permit(first)).isEqualTo(ResendPermit.MONTH_MOVED);
        assertThat(row(sent).resends()).isZero();
        assertThat(row(sent).lastAnswer()).contains("NOT_RECEIVED");
        assertThat(codes()).doesNotContain("regulatory.SubmissionResent");
    }

    @Test
    @DisplayName("a definite not received, then the month reopens before the permit: no resend, and named for why; the fixture's rank, standing for the statement, refuses it too")
    void aResendStopsOnceTheMonthMoves() {
        Acted sent = world.sent(obligation, JANUARY);
        machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(); // The worker died here, before its call.
        clock.advance(Duration.ofMinutes(3));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        answered(second, Answer.notReceived(bytes("{}")), CallKind.INQUIRE);
        world.reopened(JANUARY);

        assertThat(permit(second)).isEqualTo(ResendPermit.MONTH_MOVED);
        assertThat(world.store.permitResend(UOW, sent.id(), second.row().attempts(), RegulatorySubmissions.MAX_RESENDS)).isFalse();
        assertThat(row(sent).resends()).isZero();
    }

    @Test
    @DisplayName("once the month moved a definite not received opens the person's door with every resend left; the return ends UNDELIVERED and a correction goes at the new revision")
    void aMovedMonthOpensThePersonsDoor() {
        Acted sent = world.sent(obligation, JANUARY);
        world.reopened(JANUARY);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(90_00, 90_00, 50_00, 50_00));
        assertThat(machine.view(UOW, sent.id()).nonDeliveryOpen()).as("nothing asked yet").isFalse();

        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        answered(first, Answer.notReceived(bytes("{}")), CallKind.INQUIRE);
        assertThat(row(sent).resends()).isZero();
        assertThat(machine.view(UOW, sent.id()).nonDeliveryOpen()).isTrue();

        assertThat(machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation()).status()).isEqualTo(SubmissionStatus.UNDELIVERED);
        Acted correction = world.validated(machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.of(sent.id()), REASON), PREPARER, world.correlation()));
        assertThat(row(correction).periodRevision()).isEqualTo(world.row(JANUARY).revision());
        assertThat(row(correction).corrects()).contains(sent.id());
        assertThat(row(correction).status()).isEqualTo(SubmissionStatus.VALIDATED);
    }

    @Test
    @DisplayName("a stale not received concludes nothing: only what the provider answered at this act does")
    void aStaleNotReceivedConcludesNothing() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        answered(first, Answer.notReceived(bytes("{}")), CallKind.INQUIRE);
        world.store.horizonPassedForTest(sent.id());
        assertThat(machine.view(UOW, sent.id()).nonDeliveryOpen()).isTrue();

        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), Optional.empty(), REASON, SENDER, world.correlation())).as("not asked").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), Optional.of(new Recorded(Disposition.AWAITING, false)), REASON, SENDER, world.correlation())).as("asked, and it said otherwise").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation()).status()).isEqualTo(SubmissionStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("the door's answer is kept whatever it says: a not received stands; an unknown closes the door; over a resend in flight it opens nothing and overwrites nothing; an acknowledgement takes its edge; a person cannot record it")
    void theDoorsAnswerIsKept() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        answered(first, Answer.notReceived(bytes("first")), CallKind.INQUIRE);
        world.store.horizonPassedForTest(sent.id());
        Answered notReceived = new Answered(CallKind.INQUIRE, Answer.notReceived(bytes("at the door")));
        Answered unknown = new Answered(CallKind.INQUIRE, Answer.unknown(Optional.empty(), bytes("unsure")));

        assertThat(atTheDoor(sent, notReceived)).isEqualTo(Disposition.NOT_RECEIVED);
        assertThat(row(sent).lastAnswer()).contains("NOT_RECEIVED");
        assertThat(new String(world.store.answers(UOW, sent.id(), 100).get(1).raw(), StandardCharsets.UTF_8)).isEqualTo("at the door");

        assertThat(atTheDoor(sent, unknown)).isEqualTo(Disposition.AWAITING);
        assertThat(row(sent).lastAnswer()).contains("UNKNOWN");
        assertThat(machine.view(UOW, sent.id()).nonDeliveryOpen()).isFalse();

        answered(first, Answer.notReceived(bytes("again")), CallKind.INQUIRE);
        assertThat(permit(first)).isEqualTo(ResendPermit.PERMITTED);
        assertThat(atTheDoor(sent, notReceived)).as("a send is in flight").isEqualTo(Disposition.AWAITING);
        assertThat(atTheDoor(sent, unknown)).isEqualTo(Disposition.AWAITING);
        assertThat(row(sent).lastAnswer()).as("never overwritten").contains("RESENDING");

        assertThatThrownBy(() -> {
            try (SecurityContext.Scope person = SecurityContext.enter(PREPARER)) {
                machine.recordAtTheDoor(UOW, sent.id(), notReceived, Duration.ofMinutes(15), world.correlation());
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("platform");

        assertThat(atTheDoor(sent, new Answered(CallKind.INQUIRE, Answer.acknowledged("PROVIDER-1", bytes("held"))))).isEqualTo(Disposition.ACKNOWLEDGED);
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.ACKNOWLEDGED_EVENT);
        assertThat(world.store.answers(UOW, sent.id(), 100)).extracting(answer -> new String(answer.raw(), StandardCharsets.UTF_8)).as("every one, in order, verbatim").containsExactly("first", "at the door", "unsure", "again", "at the door", "unsure", "held");
    }

    private Disposition atTheDoor(Acted sent, Answered answered) {
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            return machine.recordAtTheDoor(UOW, sent.id(), answered, Duration.ofMinutes(15), world.correlation()).disposition();
        }
    }

    @Test
    @DisplayName("a resend is an act: audited as the platform, with no figure and no provider's text, and a writer refusing fails the permit, so it is one transaction; and only the platform permits one")
    void aResendIsAudited() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        answered(first, Answer.notReceived(bytes("PROVIDER-TEXT")), CallKind.INQUIRE);
        assertThatThrownBy(() -> {
            try (SecurityContext.Scope person = SecurityContext.enter(PREPARER)) {
                machine.permitResend(UOW, first, world.correlation());
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("platform");

        assertThat(permit(first)).isEqualTo(ResendPermit.PERMITTED);
        AuditRecord resent = world.audit.get(world.audit.size() - 1);
        assertThat(resent.operation().code()).isEqualTo("regulatory.SubmissionResent");
        assertThat(resent.actor().type()).isEqualTo(ActorType.SYSTEM);
        assertThat(resent.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(resent.targetId()).isEqualTo(sent.id().value().toString());
        assertThat(resent.changeSummary().orElseThrow()).contains("resend=1").doesNotContain("PROVIDER-TEXT").doesNotContain("10000");

        answered(first, Answer.notReceived(bytes("again")), CallKind.INQUIRE);
        world.auditRefuses = true;
        assertThatThrownBy(() -> permit(first)).isInstanceOf(IllegalStateException.class).hasMessageContaining("audit");
        world.auditRefuses = false;
    }

    @Test
    @DisplayName("an answer that decides nothing waits the interval up to the horizon; past it the time since the horizon, so the gaps double; never more than a day - the fixture's arithmetic: the statement's is pinned in the statements suite and run by the database suite")
    void theInquiryBacksOffPastTheHorizon() {
        assertThat(RegulatorySubmissions.INQUIRY_CEILING).isEqualTo(Duration.ofDays(1));
        Acted sent = world.sent(obligation, JANUARY);
        Answer unknown = Answer.unknown(Optional.empty(), bytes("{}"));

        answered(machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(), unknown, CallKind.SUBMIT);
        assertThat(row(sent).nextInquiryAt()).contains(clock.instant().plus(Duration.ofMinutes(15)));

        clock.advance(Duration.ofDays(1).plusHours(2));
        answered(machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow(), unknown, CallKind.INQUIRE);
        assertThat(row(sent).nextInquiryAt()).as("two hours past the horizon: two hours").contains(clock.instant().plus(Duration.ofHours(2)));

        clock.advance(Duration.ofHours(2));
        answered(machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow(), unknown, CallKind.INQUIRE);
        assertThat(row(sent).nextInquiryAt()).as("four hours past it: the gap doubled").contains(clock.instant().plus(Duration.ofHours(4)));

        clock.advance(Duration.ofDays(3));
        answered(machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow(), unknown, CallKind.INQUIRE);
        assertThat(row(sent).nextInquiryAt()).as("the ceiling").contains(clock.instant().plus(RegulatorySubmissions.INQUIRY_CEILING));
    }

    @Test
    @DisplayName("a kept answer says when it arrived, not when it was read")
    void anAnswerKeepsWhenItArrived() {
        Acted sent = world.sent(obligation, JANUARY);
        Instant arrived = clock.instant();
        answered(machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(), Answer.unknown(Optional.empty(), bytes("{}")), CallKind.SUBMIT);
        clock.advance(Duration.ofHours(1));
        assertThat(world.store.answers(UOW, sent.id(), 100).get(0).receivedAt()).isEqualTo(arrived);
    }

    @Test
    @DisplayName("a read serves the newest answers and says how many are kept; every one stays kept, verbatim - the statement's limit is pinned in the statements suite")
    void aReadLoadsTheNewestAnswersOnly() {
        assertThat(RegulatorySubmissions.ANSWERS_SERVED).isEqualTo(20);
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        int asked = RegulatorySubmissions.ANSWERS_SERVED + 5;
        for (int i = 0; i < asked; i++) {
            answered(first, Answer.unknown(Optional.empty(), bytes("answer-" + i)), CallKind.INQUIRE);
        }

        View view = machine.view(UOW, sent.id());
        assertThat(view.answersKept()).isEqualTo(asked);
        assertThat(view.answers()).hasSize(RegulatorySubmissions.ANSWERS_SERVED);
        assertThat(new String(view.answers().get(0).raw(), StandardCharsets.UTF_8)).isEqualTo("answer-5");
        assertThat(new String(view.answers().get(view.answers().size() - 1).raw(), StandardCharsets.UTF_8)).isEqualTo("answer-" + (asked - 1));
        assertThat(world.store.keptAnswers()).hasSize(asked);
        for (int i = 0; i < asked; i++) {
            assertThat(new String(world.store.keptAnswers().get(i).reading().retained(), StandardCharsets.UTF_8)).isEqualTo("answer-" + i);
        }
    }

    @Test
    @DisplayName("what stands comes a page at a time, oldest first, and says truthfully when more exist; a page out of bounds and a cursor no return answers are refused")
    void whatStandsIsPaged() {
        java.time.YearMonth march = java.time.YearMonth.of(2096, 3);
        world.closed(FEBRUARY);
        world.closed(march);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(march, RegulatoryWorld.body(1, 1, 1, 1));
        RegulatorySubmissionId oldest = world.drafted(obligation, JANUARY).id();
        clock.advance(Duration.ofMinutes(1));
        RegulatorySubmissionId middle = world.drafted(obligation, FEBRUARY).id();
        clock.advance(Duration.ofMinutes(1));
        List<RegulatorySubmissionId> drafted = List.of(oldest, middle, world.drafted(obligation, march).id());

        RegulatorySubmissions.Standing first = machine.standing(UOW, Optional.empty(), 2);
        assertThat(first.rows()).hasSize(2);
        assertThat(first.nextAfter()).contains(first.rows().get(1).id());
        RegulatorySubmissions.Standing second = machine.standing(UOW, first.nextAfter(), 2);
        assertThat(second.rows()).hasSize(1);
        assertThat(second.nextAfter()).as("no more exist").isEmpty();
        assertThat(java.util.stream.Stream.concat(first.rows().stream(), second.rows().stream()).map(RegulatorySubmissionStore.Row::id)).containsExactlyElementsOf(drafted);

        assertThat(machine.standing(UOW, Optional.empty(), 3).nextAfter()).as("exactly a page: no more exist").isEmpty();
        assertThatThrownBy(() -> machine.standing(UOW, Optional.empty(), 0)).isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
        assertThatThrownBy(() -> machine.standing(UOW, Optional.empty(), RegulatorySubmissions.LIST_LIMIT + 1)).isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
        assertThatThrownBy(() -> machine.standing(UOW, Optional.of(RegulatorySubmissionId.next(world.ids)), 2)).isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
    }

    @Test
    @DisplayName("a walk of what stands follows the order the rows were recorded in: a return born on a clock stepped back comes after the one before it, and the cursor loses neither (P14-REM-009; V014's order, proven on the database by RegulatorySubmissionDatabaseTest)")
    void aWalkOfWhatStandsFollowsTheRecordedOrder() {
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        RegulatorySubmissions.Acted earlier = world.drafted(obligation, JANUARY);
        clock.advance(Duration.ofMinutes(-5));
        RegulatorySubmissions.Acted later = world.drafted(obligation, FEBRUARY);
        assertThat(row(later).preparedAt()).as("the precondition: the later return is stamped before the earlier one").isBefore(row(earlier).preparedAt());

        RegulatorySubmissions.Standing first = machine.standing(UOW, Optional.empty(), 1);
        RegulatorySubmissions.Standing second = machine.standing(UOW, first.nextAfter(), 1);

        assertThat(first.rows()).extracting(RegulatorySubmissionStore.Row::id).containsExactly(earlier.id());
        assertThat(second.rows()).extracting(RegulatorySubmissionStore.Row::id).containsExactly(later.id());
    }

    @Test
    @DisplayName("a run's bytes differing at an act refuse it, apart from staleness: an integrity failure")
    void aRunsBytesAreReprovedAtEveryAct() {
        java.time.YearMonth march = java.time.YearMonth.of(2096, 3);
        world.closed(FEBRUARY);
        world.closed(march);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(march, RegulatoryWorld.body(1, 1, 1, 1));
        Acted draft = world.drafted(obligation, JANUARY);
        Acted validated = world.validated(world.drafted(obligation, FEBRUARY));
        Acted approved = world.approved(world.validated(world.drafted(obligation, march)));
        for (ReportRunStore.RunRow run : world.runs.all()) {
            world.runs.corruptContentForTest(run.id(), "{\"body\":{}}");
        }

        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.RunBytesDiffer.class);
        assertThatThrownBy(() -> world.approved(validated)).isInstanceOf(RegulatorySubmissions.RunBytesDiffer.class);
        assertThatThrownBy(() -> world.submitted(approved)).isInstanceOf(RegulatorySubmissions.RunBytesDiffer.class);
        View view = machine.view(UOW, draft.id());
        assertThat(view.blockedBy()).contains(BlockedBy.RUN_BYTES_DIFFER);
        assertThat(view.lines()).allSatisfy(line -> {
            assertThat(line.agrees()).isFalse();
            assertThat(line.figure()).isEmpty();
        });
    }

    @Test
    @DisplayName("an obligation version retired under a return refuses each act going forward; a door out asks nothing of it")
    void aRetiredVersionRefusesActsGoingForward() {
        java.time.YearMonth march = java.time.YearMonth.of(2096, 3);
        world.closed(FEBRUARY);
        world.closed(march);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(march, RegulatoryWorld.body(1, 1, 1, 1));
        Acted draft = world.drafted(obligation, JANUARY);
        Acted validated = world.validated(world.drafted(obligation, FEBRUARY));
        Acted approved = world.approved(world.validated(world.drafted(obligation, march)));
        world.active(2, RegulatoryWorld.configuration());

        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("RETIRED");
        assertThatThrownBy(() -> world.approved(validated)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("RETIRED");
        assertThatThrownBy(() -> world.submitted(approved)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("RETIRED");
        assertThat(machine.view(UOW, draft.id()).blockedBy()).contains(BlockedBy.OBLIGATION_RETIRED);
        assertThat(machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation()).status()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation()).status()).isEqualTo(SubmissionStatus.REFUSED);
        assertThat(machine.refuse(UOW, approved.id(), REASON, APPROVER, world.correlation()).status()).isEqualTo(SubmissionStatus.REFUSED);
    }

    // ------------------------------------------------------------------ four eyes, and what a person names

    @Test
    @DisplayName("the proposer cannot approve: the same person is refused, recorded FAILED by the caller; the fixture's ranks, standing for V010's CHECKs on the row and the history, hold the same")
    void theProposerCannotApprove() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));

        assertThatThrownBy(() -> machine.approve(UOW, validated.id(), read(validated), REASON, PREPARER, world.correlation())).isInstanceOf(RegulatorySubmissions.SelfApprovalRefused.class)
                .satisfies(refused -> {
                    RegulatorySubmissions.SelfApprovalRefused self = (RegulatorySubmissions.SelfApprovalRefused) refused;
                    machine.recordRefusal(UOW, self, PREPARER, world.correlation());
                });
        AuditRecord recorded = world.audit.get(world.audit.size() - 1);
        assertThat(recorded.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
        assertThat(recorded.outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(recorded.targetId()).isEqualTo(validated.id().value().toString());
        assertThat(recorded.changeSummary().orElseThrow()).contains("regulatory-submission:approve").doesNotContain(REASON);
        assertThat(row(validated).status()).isEqualTo(SubmissionStatus.VALIDATED);

        // The fixture's ranks, standing for V010's CHECKs, refuse a writer past the domain.
        assertThatThrownBy(() -> world.store.markApproved(UOW, validated.id(), PREPARER, REASON, validated.contentSha256(), validated.periodRevision()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("regulatory_submission_four_eyes");
        assertThatThrownBy(() -> world.store.appendEvent(UOW, new RegulatorySubmissionStore.EventRow(java.util.UUID.randomUUID(), validated.id(), RegulatorySubmissionStore.Act.APPROVED, Optional.of(SubmissionStatus.VALIDATED), SubmissionStatus.APPROVED, PREPARER,
                Optional.of(PREPARER.id()), Optional.of(REASON), world.correlation()))).isInstanceOf(IllegalStateException.class).hasMessageContaining("regulatory_submission_event_four_eyes");
    }

    @Test
    @DisplayName("the approver cannot send: one identity can hold two roles, so it is held at the act; a preparer may")
    void theApproverCannotSend() {
        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));

        assertThatThrownBy(() -> machine.submit(UOW, approved.id(), read(approved), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatorySubmissions.SelfApprovalRefused.class)
                .satisfies(refused -> assertThat(((RegulatorySubmissions.SelfApprovalRefused) refused).summary()).contains("regulatory-submission:submit"));
        assertThat(machine.submit(UOW, approved.id(), read(approved), REASON, PREPARER, world.correlation()).status()).isEqualTo(SubmissionStatus.SUBMITTED);
    }

    @Test
    @DisplayName("what an approver or a sender names is what stands: a differing hash or revision is Stale, nothing moved")
    void whatIsNamedIsWhatStands() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        assertThatThrownBy(() -> machine.approve(UOW, validated.id(), new Attested("0".repeat(64), validated.periodRevision()), REASON, APPROVER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> machine.approve(UOW, validated.id(), new Attested(validated.contentSha256(), 9), REASON, APPROVER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        Acted approved = world.approved(validated);
        assertThatThrownBy(() -> machine.submit(UOW, approved.id(), new Attested("0".repeat(64), approved.periodRevision()), REASON, SENDER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(approved).status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThatThrownBy(() -> machine.submit(UOW, approved.id(), new Attested(approved.contentSha256(), 9), REASON, SENDER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(approved).status()).isEqualTo(SubmissionStatus.APPROVED);
    }

    @Test
    @DisplayName("every act is a person's, with a reason where one is required; the platform, a service or a merchant is refused; a blank or instrument-bearing reason is refused")
    void everyActIsAPersons() {
        for (ActorType type : ActorType.values()) {
            if (type != ActorType.CUSTOMER && type != ActorType.EMPLOYEE) {
                assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), REASON), new Actor("not-a-person", type), world.correlation())).as(type.name()).isInstanceOf(IllegalArgumentException.class);
            }
        }
        Actor service = new Actor("svc", ActorType.SERVICE);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), REASON), service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), " "), PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), "card 4111111111111111"), PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);

        // Each act, not only the draft.
        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        assertThatThrownBy(() -> machine.validate(UOW, validated.id(), service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.approve(UOW, validated.id(), read(validated), REASON, service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.refuse(UOW, validated.id(), REASON, service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.withdraw(UOW, validated.id(), REASON, service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.submit(UOW, validated.id(), read(validated), REASON, service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, validated.id(), ASKED, REASON, service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.approve(UOW, validated.id(), read(validated), " ", APPROVER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.refuse(UOW, validated.id(), " ", APPROVER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.withdraw(UOW, validated.id(), " ", PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.submit(UOW, validated.id(), read(validated), " ", SENDER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, validated.id(), ASKED, " ", SENDER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
    }

    // ------------------------------------------------------------------ doors out

    @Test
    @DisplayName("a failed validation stays DRAFT, recorded once, audited FAILED; a repeat says the same and writes nothing; the way on is a withdrawal and a new draft")
    void aFailedValidationStaysDraft() {
        world.reopened(JANUARY);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(100_00, 99_00, 50_00, 50_00));
        Acted draft = world.drafted(obligation, JANUARY);

        Acted failed = machine.validate(UOW, draft.id(), PREPARER, world.correlation());
        assertThat(failed.status()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(failed.validationPassed()).contains(false);
        assertThat(world.audit.get(world.audit.size() - 1).outcome()).isEqualTo(AuditOutcome.FAILED);
        assertThat(machine.view(UOW, draft.id()).validation().orElseThrow().findings()).anySatisfy(finding -> {
            assertThat(finding.rule()).isEqualTo("BALANCED");
            assertThat(finding.currency()).isEqualTo("EUR");
            assertThat(finding.passed()).isFalse();
        });

        int records = world.audit.size();
        int history = world.store.history().size();
        int events = world.events.size();
        Acted repeat = machine.validate(UOW, draft.id(), PREPARER, world.correlation());
        assertThat(repeat.replayed()).isTrue();
        assertThat(repeat.status()).isEqualTo(SubmissionStatus.DRAFT);
        assertThat(repeat.validationPassed()).contains(false);
        assertThat(world.audit).hasSize(records);
        assertThat(world.store.history()).hasSize(history);
        assertThat(world.events).hasSize(events);
        assertThatThrownBy(() -> machine.approve(UOW, draft.id(), read(draft), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation());
        assertThat(world.drafted(obligation, JANUARY).status()).isEqualTo(SubmissionStatus.DRAFT);
    }

    @Test
    @DisplayName("every state not sent has a door out that asks nothing of the month: a stale DRAFT, VALIDATED and APPROVED each withdrawn or refused after a reopen")
    void everyUnsentStateHasADoorOut() {
        Acted draft = world.drafted(obligation, JANUARY);
        world.reopened(JANUARY);
        assertThat(machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation()).status()).isEqualTo(SubmissionStatus.WITHDRAWN);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.WITHDRAWN_EVENT);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));

        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        world.reopened(JANUARY);
        assertThat(machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation()).status()).isEqualTo(SubmissionStatus.REFUSED);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));

        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));
        world.reopened(JANUARY);
        assertThat(machine.refuse(UOW, approved.id(), REASON, APPROVER, world.correlation()).status()).isEqualTo(SubmissionStatus.REFUSED);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.REFUSED_EVENT);

        // Nothing leaves a terminal status, and a DRAFT is not a controller's to refuse.
        assertThatThrownBy(() -> machine.withdraw(UOW, approved.id(), REASON, PREPARER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> world.approved(approved)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(1, 1, 1, 1));
        Acted fresh = world.drafted(obligation, JANUARY);
        assertThatThrownBy(() -> machine.refuse(UOW, fresh.id(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
    }

    @Test
    @DisplayName("what was sent cannot be withdrawn or refused, whether it is SUBMITTED, ACKNOWLEDGED, REJECTED or UNDELIVERED: the provider holds it, or may")
    void whatWasSentCannotBeTakenBack() {
        java.time.YearMonth march = java.time.YearMonth.of(2096, 3);
        java.time.YearMonth april = java.time.YearMonth.of(2096, 4);
        world.closed(FEBRUARY);
        world.closed(march);
        world.closed(april);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(march, RegulatoryWorld.body(1, 1, 1, 1));
        world.issued(april, RegulatoryWorld.body(1, 1, 1, 1));
        Acted submitted = world.sent(obligation, JANUARY);
        Acted acknowledged = world.sent(obligation, FEBRUARY);
        answered(machine.claim(UOW, acknowledged.id(), Duration.ofMinutes(2)).orElseThrow(), Answer.acknowledged("PROVIDER-1", bytes("{}")), CallKind.SUBMIT);
        Acted rejected = world.sent(obligation, march);
        rejected(rejected);
        Acted undelivered = world.sent(obligation, april);
        answered(machine.claim(UOW, undelivered.id(), Duration.ofMinutes(2)).orElseThrow(), Answer.notReceived(bytes("{}")), CallKind.INQUIRE);
        world.store.horizonPassedForTest(undelivered.id());
        machine.declareUndelivered(UOW, undelivered.id(), ASKED, REASON, SENDER, world.correlation());

        for (Acted sent : List.of(submitted, acknowledged, rejected, undelivered)) {
            assertThatThrownBy(() -> machine.withdraw(UOW, sent.id(), REASON, PREPARER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
            assertThatThrownBy(() -> machine.refuse(UOW, sent.id(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        }
    }

    @Test
    @DisplayName("a controller's refusal and a provider's rejection differ in name, event, audit and what a reader is told")
    void aRefusalIsNotARejection() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));
        machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation());
        assertThat(row(validated).status()).isEqualTo(SubmissionStatus.REFUSED);
        assertThat(row(validated).status().meaning()).contains("controller").contains("Nothing was sent");
        assertThat(codes()).contains("regulatory.SubmissionRefused").doesNotContain("regulatory.SubmissionRejected");

        Acted sent = world.sent(obligation, JANUARY);
        rejected(sent);
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(row(sent).status().meaning()).contains("provider");
        assertThat(row(sent).rejectionCode()).contains("FORM_REFUSED");
        assertThat(codes()).contains("regulatory.SubmissionRejected");
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.REFUSED_EVENT, RegulatorySubmissions.REJECTED_EVENT);
    }

    // ------------------------------------------------------------------ the drive

    @Test
    @DisplayName("a retried submit submits once: the same sender's repeat converges with one event; a claim holds the lease; the first attempt sends and the second only asks; the provider holds one return")
    void aRetriedSubmitSubmitsOnce() {
        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));
        Acted sent = world.submitted(approved);
        Acted repeat = world.submitted(approved);
        assertThat(repeat.replayed()).isTrue();
        assertThat(world.events.stream().filter(event -> event.eventType().equals(RegulatorySubmissions.SUBMITTED_EVENT))).hasSize(1);

        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        assertThat(first.row().attempts()).isEqualTo(1);
        assertThat(machine.claim(UOW, sent.id(), Duration.ofMinutes(2))).as("held by a lease").isEmpty();
        assertThat(machine.claimNextDue(UOW, Duration.ofMinutes(2))).as("held by a lease").isEmpty();

        Answered answered = machine.ask(first);
        assertThat(answered.call()).isEqualTo(CallKind.SUBMIT);

        // The answer was lost: nothing recorded. The lease lapses and the next claim asks, never sends.
        clock.advance(Duration.ofMinutes(3));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        assertThat(second.row().attempts()).isEqualTo(2);
        Answered asked = machine.ask(second);
        assertThat(asked.call()).isEqualTo(CallKind.INQUIRE);
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.returnsHeld(sent.id())).isEqualTo(1);

        asThePlatform(() -> assertThat(machine.record(UOW, second, asked, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.ACKNOWLEDGED));
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(row(sent).providerReference()).contains(ScriptedRegulatoryProvider.reference(sent.id()));
    }

    @Test
    @DisplayName("SUBMITTED commits before any call: nothing reaches the provider until a claim has committed and the provider is asked")
    void submittedCommitsBeforeAnyCall() {
        Acted sent = world.sent(obligation, JANUARY);
        assertThat(world.provider.submits()).isZero();
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(row(sent).attempts()).isZero();
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.SUBMITTED_EVENT);
        assertThat(world.provider.inquiries()).isZero();
        Claimed claimed = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        assertThat(world.provider.submits()).as("a claim calls nobody").isZero();
        machine.ask(claimed);
        assertThat(world.provider.submits()).isEqualTo(1);
    }

    @Test
    @DisplayName("a crash between the commit and the call: the next claim asks, the provider definitely holds nothing, and the driver may send again under the same id - counted first")
    void aSendThatNeverArrivedIsSentAgainUnderItsOwnId() {
        Acted sent = world.sent(obligation, JANUARY);
        machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(); // The worker died here, before its call.

        clock.advance(Duration.ofMinutes(3));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        Answered asked = machine.ask(second);
        asThePlatform(() -> assertThat(machine.record(UOW, second, asked, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.NOT_RECEIVED));
        assertThat(row(sent).lastAnswer()).contains("NOT_RECEIVED");

        assertThat(permit(second)).isEqualTo(ResendPermit.PERMITTED);
        assertThat(row(sent).lastAnswer()).as("no person concludes over a send in flight").contains("RESENDING");
        assertThat(row(sent).resends()).isEqualTo(1);
        assertThat(permit(second)).as("one permit per definite answer").isEqualTo(ResendPermit.OVERTAKEN);

        Answered resent = machine.resend(second);
        assertThat(resent.call()).isEqualTo(CallKind.RESEND);
        asThePlatform(() -> machine.record(UOW, second, resent, Duration.ofMinutes(15), world.correlation()));
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.returnsHeld(sent.id())).isEqualTo(1);
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("an unknown answer is an inquiry, never a re-send: no permit is given, the row's permit moves ahead, the next attempt asks, and an overtaken worker moves no permit")
    void anUnknownAnswerIsAnInquiry() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        Answered unknown = new Answered(CallKind.SUBMIT, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        asThePlatform(() -> assertThat(machine.record(UOW, first, unknown, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.AWAITING));

        assertThat(permit(first)).isEqualTo(ResendPermit.OVERTAKEN);
        assertThat(row(sent).lastAnswer()).contains("UNKNOWN");
        Instant permit = row(sent).nextInquiryAt().orElseThrow();
        assertThat(permit).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));

        // The lease lapses, another claims; the first worker's late unknown moves no permit.
        clock.advance(Duration.ofMinutes(16));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        Instant held = row(sent).nextInquiryAt().orElseThrow();
        asThePlatform(() -> machine.record(UOW, first, unknown, Duration.ofDays(5), world.correlation()));
        assertThat(row(sent).nextInquiryAt()).contains(held);
        assertThat(second.row().attempts()).isEqualTo(2);
        assertThat(machine.ask(second).call()).isEqualTo(CallKind.INQUIRE);
        assertThat(world.provider.submits()).isZero();
    }

    @Test
    @DisplayName("the resends are bounded: past five the driver may not send, and it goes on asking; the row reads exhausted, and a person concludes it before the horizon")
    void theResendsAreBounded() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed claimed = null;
        for (int i = 0; i <= RegulatorySubmissions.MAX_RESENDS; i++) {
            clock.advance(Duration.ofMinutes(16));
            claimed = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
            Claimed held = claimed;
            asThePlatform(() -> machine.record(UOW, held, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
            if (i < RegulatorySubmissions.MAX_RESENDS) {
                assertThat(permit(held)).as("resend " + i).isEqualTo(ResendPermit.PERMITTED);
                asThePlatform(() -> machine.record(UOW, held, new Answered(CallKind.RESEND, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
            }
        }
        assertThat(permit(claimed)).isEqualTo(ResendPermit.SPENT);
        assertThat(row(sent).resends()).isEqualTo(RegulatorySubmissions.MAX_RESENDS);
        assertThat(machine.view(UOW, sent.id()).resendsExhausted()).isTrue();
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).resendsExhausted()).isEqualTo(1);
        assertThat(machine.claimNextDue(UOW, Duration.ofMinutes(2))).as("not yet due").isEmpty();
        clock.advance(Duration.ofMinutes(16));
        assertThat(machine.view(UOW, sent.id()).nonDeliveryOpen()).isTrue();
        assertThat(RegulatorySubmissions.MAX_RESENDS).isEqualTo(5);
        Claimed asking = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        assertThat(machine.ask(asking).call()).as("it goes on asking").isEqualTo(CallKind.INQUIRE);
        assertThat(row(sent).pastHorizon()).isFalse();
        assertThat(machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation()).status()).isEqualTo(SubmissionStatus.UNDELIVERED);
    }

    @Test
    @DisplayName("UNDELIVERED only on a definite not received, past the horizon here: refused over an unknown answer and over a send in flight; a repeat converges and a correction may reference it")
    void undeliveredOnlyOnADefiniteNotReceived() {
        Acted sent = world.sent(obligation, JANUARY);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation())).as("nothing asked yet").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> machine.record(UOW, first, new Answered(CallKind.SUBMIT, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        world.store.horizonPassedForTest(sent.id());
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation())).as("an unknown answer past the horizon").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        clock.advance(Duration.ofMinutes(16));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> machine.record(UOW, second, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        permit(second);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation())).as("a send in flight").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        asThePlatform(() -> machine.record(UOW, second, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));

        Acted undelivered = machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation());
        assertThat(undelivered.status()).isEqualTo(SubmissionStatus.UNDELIVERED);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.UNDELIVERED_EVENT);
        assertThat(machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation()).replayed()).isTrue();

        // A correction may reference it.
        Acted correction = machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.of(sent.id()), REASON), PREPARER, world.correlation());
        assertThat(row(correction).corrects()).contains(sent.id());
    }

    @Test
    @DisplayName("before the horizon, a definite not received with resends left is not a person's to conclude: the driver may still send")
    void theDriverSendsBeforeAPersonConcludes() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> machine.record(UOW, first, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));

        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), ASKED, REASON, SENDER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(permit(first)).as("the driver still may").isEqualTo(ResendPermit.PERMITTED);
    }

    @Test
    @DisplayName("ack against rejection is one way, each way round: the first definite answer takes the edge whichever attempt it came from; a later one is kept verbatim, flagged, audited, never an edge")
    void aContraryAnswerIsKeptAndNeverAnEdge() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        clock.advance(Duration.ofMinutes(3));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();

        asThePlatform(() -> assertThat(machine.record(UOW, second, new Answered(CallKind.INQUIRE, Answer.acknowledged("PROVIDER-1", "first".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()).disposition())
                .isEqualTo(Disposition.ACKNOWLEDGED));
        asThePlatform(() -> assertThat(machine.record(UOW, first, new Answered(CallKind.SUBMIT, Answer.rejected(Optional.of("PROVIDER-2"), "FORM_REFUSED", "second".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()).disposition())
                .isEqualTo(Disposition.CONTRARY));

        RegulatorySubmissionStore.Row row = row(sent);
        assertThat(row.status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(row.providerReference()).contains("PROVIDER-1");
        assertThat(row.rejectionCode()).isEmpty();
        assertThat(row.contraryAnswers()).isEqualTo(1);
        List<RegulatorySubmissionStore.AnswerRow> kept = world.store.answers(UOW, sent.id(), 100);
        assertThat(kept).hasSize(2);
        assertThat(new String(kept.get(1).raw(), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(kept.get(1).flag()).contains(RegulatorySubmissionStore.Flag.CONTRARY);
        assertThat(codes()).contains("regulatory.SubmissionContraryAnswer");
        assertThat(world.events.stream().filter(event -> event.eventType().equals(RegulatorySubmissions.REJECTED_EVENT))).isEmpty();
        assertThat(world.store.standing(UOW, Optional.empty(), 100)).as("so that a person can find which return").extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());

        // One agreeing, after the conclusion: kept, flagged late.
        asThePlatform(() -> assertThat(machine.record(UOW, first, new Answered(CallKind.INQUIRE, Answer.acknowledged("PROVIDER-1", "third".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()).disposition())
                .isEqualTo(Disposition.LATE));
        assertThat(row(sent).contraryAnswers()).isEqualTo(1);

        // The other way round, and from an attempt no longer the latest.
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        Acted other = world.sent(obligation, FEBRUARY);
        Claimed earlier = machine.claim(UOW, other.id(), Duration.ofMinutes(2)).orElseThrow();
        clock.advance(Duration.ofMinutes(3));
        Claimed later = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> assertThat(machine.record(UOW, earlier, new Answered(CallKind.SUBMIT, Answer.rejected(Optional.of("PROVIDER-4"), "FORM_REFUSED", bytes("r"))), Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.REJECTED));
        asThePlatform(() -> assertThat(machine.record(UOW, later, new Answered(CallKind.INQUIRE, Answer.acknowledged("PROVIDER-5", bytes("a"))), Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.CONTRARY));
        assertThat(row(other).status()).isEqualTo(SubmissionStatus.REJECTED);
    }

    @Test
    @DisplayName("a provider's answer is recorded by the platform and nobody else")
    void anAnswerIsThePlatformsToRecord() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed claimed = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        Answered answered = machine.ask(claimed);
        assertThatThrownBy(() -> {
            try (SecurityContext.Scope person = SecurityContext.enter(PREPARER)) {
                machine.record(UOW, claimed, answered, Duration.ofMinutes(15), world.correlation());
            }
        }).isInstanceOf(IllegalStateException.class).hasMessageContaining("platform");
        for (ActorType type : ActorType.values()) {
            if (type != ActorType.SYSTEM) {
                assertThatThrownBy(() -> {
                    try (SecurityContext.Scope someone = SecurityContext.enter(new Actor("not-the-platform", type))) {
                        machine.record(UOW, claimed, answered, Duration.ofMinutes(15), world.correlation());
                    }
                }).as(type.name()).isInstanceOf(IllegalStateException.class).hasMessageContaining("platform");
            }
        }
        asThePlatform(() -> assertThat(machine.record(UOW, claimed, answered, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.ACKNOWLEDGED));
    }

    // ------------------------------------------------------------------ what a reader sees

    @Test
    @DisplayName("a reader follows every line to its run's figure through bytes proved; the agreement is real: a line differing from the run reads false")
    void aReaderFollowsEachLineToItsFigure() {
        Acted draft = world.drafted(obligation, JANUARY);
        View view = machine.view(UOW, draft.id());

        assertThat(view.lines()).hasSize(4).allSatisfy(line -> {
            assertThat(line.agrees()).isTrue();
            assertThat(line.runStatus()).contains(RunStatus.FINAL);
            assertThat(line.figure().orElseThrow().minor()).isEqualTo(line.line().minor());
            assertThat(line.figure().orElseThrow().path()).isEqualTo(line.line().figure());
        });
        assertThat(view.stillStands()).isTrue();
        assertThat(view.blockedBy()).isEmpty();
        assertThat(view.obligation().id()).isEqualTo(obligation);

        // A line its run does not explain reads as not agreeing, whatever put it there, and the run's own figure is beside it.
        world.store.forgeLineForTest(draft.id(), 1, java.math.BigInteger.valueOf(999_999));
        List<RegulatorySubmissions.LineView> forged = machine.view(UOW, draft.id()).lines();
        assertThat(forged.get(0).agrees()).isFalse();
        assertThat(forged.get(0).figure().orElseThrow().minor()).isNotEqualTo(forged.get(0).line().minor());
        assertThat(forged.subList(1, forged.size())).allSatisfy(line -> assertThat(line.agrees()).isTrue());

        // Bytes that no longer prove serve no figure at all.
        world.runs.corruptContentForTest(world.runs.all().get(0).id(), "{\"body\":{}}");
        assertThat(machine.view(UOW, draft.id()).lines()).allSatisfy(line -> {
            assertThat(line.figure()).isEmpty();
            assertThat(line.agrees()).isFalse();
        });
    }

    @Test
    @DisplayName("a pending list excludes what its reader cannot decide: their own; a person's only - the statement's filter is pinned in the statements suite")
    void aPendingListExcludesWhatItsReaderCannotDecide() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));

        assertThat(machine.awaitingApproval(UOW, APPROVER)).extracting(RegulatorySubmissionStore.Row::id).containsExactly(validated.id());
        assertThat(machine.awaitingApproval(UOW, PREPARER)).isEmpty();
        assertThatThrownBy(() -> machine.awaitingApproval(UOW, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("events carry ids and codes only, through the platform's envelope; audit summaries no figure and no reason; a toString no reason, figure or provider text")
    void nothingCarriesAFigureAReasonOrAProvidersText() {
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1_234_567, 1_234_567, 7_654_321, 7_654_321));
        Acted sent = world.sent(obligation, FEBRUARY);
        answered(machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(), Answer.rejected(Optional.of("PROVIDER-1"), "FORM_REFUSED", bytes("PROVIDER-TEXT-ONLY")), CallKind.SUBMIT);

        java.util.Set<String> allowed = java.util.Set.of("standing", "obligationId", "jurisdiction", "form", "formVersion", "period", "periodRevision", "contentSha256", "rejectionCode");
        assertThat(world.events.stream().filter(event -> event.eventType().contains("Regulatory"))).hasSize(2);
        for (int at = 0; at < world.events.size(); at++) {
            if (world.events.get(at).eventType().contains("Regulatory")) {
                String text = new String(world.payloads.get(at), StandardCharsets.UTF_8);
                assertThat(text).as("a simulated acknowledgement never reads as a regulator's").contains("\"standing\"").contains("REFERENCE");
                java.util.regex.Matcher keys = java.util.regex.Pattern.compile("\"([A-Za-z0-9]+)\"\\s*:").matcher(text);
                while (keys.find()) {
                    assertThat(allowed).as("ids and codes only").contains(keys.group(1));
                }
            }
        }
        for (byte[] payload : world.payloads) {
            String text = new String(payload, StandardCharsets.UTF_8);
            assertThat(text).doesNotContain(REASON).doesNotContain("1234567").doesNotContain("7654321").doesNotContain("PROVIDER-1").doesNotContain("PROVIDER-TEXT-ONLY");
        }
        assertThat(world.events.stream().filter(event -> event.eventType().contains("Regulatory"))).allSatisfy(event -> {
            assertThat(event.aggregateId().value()).isEqualTo(sent.id().value());
            assertThat(event.aggregateType()).isEqualTo(RegulatorySubmissions.TARGET_TYPE);
            assertThat(event.producer()).isEqualTo("accounting");
        });
        assertThat(world.audit).allSatisfy(record -> assertThat(record.changeSummary().orElse("")).doesNotContain(REASON).doesNotContain("1234567").doesNotContain("7654321").doesNotContain("PROVIDER-TEXT-ONLY"));
        assertThat(new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), REASON).toString()).doesNotContain(REASON);
        assertThat(world.store.find(UOW, sent.id()).orElseThrow().toString()).doesNotContain(REASON);
        assertThat(new RegulatoryReportingProvider.Request(sent.id(), "REF", "F", 1, "2096-01", "SECRET-CONTENT", "h").toString()).doesNotContain("SECRET-CONTENT");
        assertThat(Answer.acknowledged("R", "PROVIDER-TEXT".getBytes(StandardCharsets.UTF_8)).toString()).doesNotContain("PROVIDER-TEXT");
        assertThat(world.store.lines(UOW, sent.id()).get(0).toString()).doesNotContain("1234567");
        assertThat(world.store.answers(UOW, sent.id(), 100).get(0).toString()).doesNotContain("FORM_REFUSED").doesNotContain("PROVIDER-TEXT-ONLY");
    }
}
