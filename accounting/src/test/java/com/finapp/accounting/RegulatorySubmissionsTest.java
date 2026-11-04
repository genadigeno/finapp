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

        assertThat(world.store.standing(UOW, 100)).isEmpty();
        assertThat(empty.store.standing(UOW, 100)).isEmpty();
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
        assertThat(world.store.standing(UOW, 100)).isEmpty();
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
    }

    @Test
    @DisplayName("ten threads draft one form and month: one row, nine refused - the one-live arbiter really raced")
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
        assertThat(world.store.standing(UOW, 100)).hasSize(1);
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

        // A second correction of the one original: refused while the first stands.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionLive.class);

        // Withdrawn, it frees the original for another.
        machine.withdraw(UOW, correction.id(), REASON, PREPARER, world.correlation());
        Acted again = machine.draft(UOW, new Draft(obligation, january, Optional.of(original.id()), REASON), PREPARER, world.correlation());
        assertThat(row(again).corrects()).contains(original.id());

        // A refused or withdrawn return never reached for the provider: nothing corrects it.
        machine.withdraw(UOW, again.id(), REASON, PREPARER, world.correlation());
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(again.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);

        // Unknown, and another month's.
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, january, Optional.of(RegulatorySubmissionId.next(world.ids)), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
        world.closed(FEBRUARY);
        world.issued(FEBRUARY, RegulatoryWorld.body(1, 1, 1, 1));
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(FEBRUARY).id(), Optional.of(original.id()), REASON), PREPARER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionInvalid.class);
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
    @DisplayName("a month closed, reopened and re-closed reads CLOSED and is not the month a return read: validate, approve and submit each refuse, though every status looks right")
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
        assertThatThrownBy(() -> world.approved(validated)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(validated).status()).isEqualTo(SubmissionStatus.VALIDATED);
        machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation());

        Acted approved = world.approved(world.validated(world.drafted(obligation, JANUARY)));
        roundTrip();
        assertThatThrownBy(() -> world.submitted(approved)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThat(row(approved).status()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(row(approved).monthMoved()).isTrue();
        // Named by the present revision it is still refused: it read the old one.
        assertThatThrownBy(() -> machine.submit(UOW, approved.id(), new Attested(approved.contentSha256(), world.row(JANUARY).revision()), REASON, SENDER, world.correlation()))
                .isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
    }

    /** Reopened, a run re-issued under the new revision, closed again. */
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

        // Answered, it stays frozen and flagged; a correction standing ends the count.
        asThePlatform(() -> machine.record(UOW, machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(), new Answered(CallKind.SUBMIT, Answer.acknowledged("PROVIDER-1", "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        assertThat(world.store.standing(UOW, 100)).extracting(RegulatorySubmissionStore.Row::id).contains(sent.id());
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isEqualTo(1);
        world.reclosed(JANUARY);
        world.issued(JANUARY, RegulatoryWorld.body(90_00, 90_00, 50_00, 50_00));
        machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.of(sent.id()), REASON), PREPARER, world.correlation());
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).overtaken()).isZero();
    }

    @Test
    @DisplayName("a run's bytes differing at an act refuse it, apart from staleness: an integrity failure")
    void aRunsBytesAreReprovedAtEveryAct() {
        Acted draft = world.drafted(obligation, JANUARY);
        world.runs.corruptContentForTest(world.runs.all().get(0).id(), "{\"body\":{}}");

        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.RunBytesDiffer.class);
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
        Acted draft = world.drafted(obligation, JANUARY);
        world.active(2, RegulatoryWorld.configuration());

        assertThatThrownBy(() -> world.validated(draft)).isInstanceOf(RegulatorySubmissions.SubmissionStale.class).hasMessageContaining("RETIRED");
        assertThat(machine.view(UOW, draft.id()).blockedBy()).contains(BlockedBy.OBLIGATION_RETIRED);
        assertThat(machine.withdraw(UOW, draft.id(), REASON, PREPARER, world.correlation()).status()).isEqualTo(SubmissionStatus.WITHDRAWN);
    }

    // ------------------------------------------------------------------ four eyes, and what a person names

    @Test
    @DisplayName("the proposer cannot approve: a holder of both roles is refused, recorded FAILED by the caller, and the row and the history's rank hold the same")
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
    }

    @Test
    @DisplayName("every act is a person's, with a reason where one is required; the platform, a service or a merchant is refused; a blank or instrument-bearing reason is refused")
    void everyActIsAPersons() {
        Actor service = new Actor("svc", ActorType.SERVICE);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), REASON), service, world.correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), " "), PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> machine.draft(UOW, new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), "card 4111111111111111"), PREPARER, world.correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
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
        Acted repeat = machine.validate(UOW, draft.id(), PREPARER, world.correlation());
        assertThat(repeat.replayed()).isTrue();
        assertThat(world.audit).hasSize(records);
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
        assertThat(machine.refuse(UOW, validated.id(), REASON, APPROVER, world.correlation()).status()).isEqualTo(SubmissionStatus.REFUSED);

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
    @DisplayName("what was sent cannot be withdrawn or refused: the provider holds it, or may")
    void whatWasSentHasNoPersonsDoorButTheOne() {
        Acted sent = world.sent(obligation, JANUARY);
        assertThatThrownBy(() -> machine.withdraw(UOW, sent.id(), REASON, PREPARER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        assertThatThrownBy(() -> machine.refuse(UOW, sent.id(), REASON, APPROVER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
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
    @DisplayName("SUBMITTED commits before any call: nothing reaches the provider until a claim commits")
    void submittedCommitsBeforeAnyCall() {
        Acted sent = world.sent(obligation, JANUARY);
        assertThat(world.provider.submits()).isZero();
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(row(sent).attempts()).isZero();
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.SUBMITTED_EVENT);
    }

    @Test
    @DisplayName("a crash between the commit and the call: the next claim asks, the provider definitely holds nothing, and the driver may send again under the same id - counted first, bounded")
    void aSendThatNeverArrivedIsSentAgainUnderItsOwnId() {
        Acted sent = world.sent(obligation, JANUARY);
        machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow(); // The worker died here, before its call.

        clock.advance(Duration.ofMinutes(3));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        Answered asked = machine.ask(second);
        asThePlatform(() -> assertThat(machine.record(UOW, second, asked, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.NOT_RECEIVED));
        assertThat(row(sent).lastAnswer()).contains("NOT_RECEIVED");

        assertThat(machine.permitResend(UOW, second)).isTrue();
        assertThat(row(sent).lastAnswer()).as("no person concludes over a send in flight").contains("RESENDING");
        assertThat(row(sent).resends()).isEqualTo(1);
        assertThat(machine.permitResend(UOW, second)).as("one permit per definite answer").isFalse();

        Answered resent = machine.resend(second);
        assertThat(resent.call()).isEqualTo(CallKind.RESEND);
        asThePlatform(() -> machine.record(UOW, second, resent, Duration.ofMinutes(15), world.correlation()));
        assertThat(world.provider.submits()).isEqualTo(1);
        assertThat(world.provider.returnsHeld(sent.id())).isEqualTo(1);
        assertThat(row(sent).status()).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
    }

    @Test
    @DisplayName("an unknown answer is an inquiry, never a re-send: no permit is given, the row's permit moves ahead, and an overtaken worker moves nothing")
    void anUnknownAnswerIsAnInquiry() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        Answered unknown = new Answered(CallKind.SUBMIT, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8)));
        asThePlatform(() -> assertThat(machine.record(UOW, first, unknown, Duration.ofMinutes(15), world.correlation()).disposition()).isEqualTo(Disposition.AWAITING));

        assertThat(machine.permitResend(UOW, first)).isFalse();
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
    }

    @Test
    @DisplayName("the resends are bounded: past five the driver may not send, and it goes on asking; the row reads exhausted and a person's door opens")
    void theResendsAreBounded() {
        Acted sent = world.sent(obligation, JANUARY);
        Claimed claimed = null;
        for (int i = 0; i <= RegulatorySubmissions.MAX_RESENDS; i++) {
            clock.advance(Duration.ofMinutes(16));
            claimed = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
            Claimed held = claimed;
            asThePlatform(() -> machine.record(UOW, held, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
            if (i < RegulatorySubmissions.MAX_RESENDS) {
                assertThat(machine.permitResend(UOW, held)).as("resend " + i).isTrue();
                asThePlatform(() -> machine.record(UOW, held, new Answered(CallKind.RESEND, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
            }
        }
        assertThat(machine.permitResend(UOW, claimed)).isFalse();
        assertThat(row(sent).resends()).isEqualTo(RegulatorySubmissions.MAX_RESENDS);
        assertThat(machine.view(UOW, sent.id()).resendsExhausted()).isTrue();
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).resendsExhausted()).isEqualTo(1);
        assertThat(machine.claimNextDue(UOW, Duration.ofMinutes(2))).as("not yet due").isEmpty();
        clock.advance(Duration.ofMinutes(16));
        assertThat(machine.claimNextDue(UOW, Duration.ofMinutes(2))).as("it goes on asking").isPresent();
    }

    @Test
    @DisplayName("UNDELIVERED only on a definite not received, the resends spent or the horizon past: refused over an unknown answer, over a send in flight, and while the driver may still send")
    void undeliveredOnlyOnADefiniteNotReceived() {
        Acted sent = world.sent(obligation, JANUARY);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation())).as("nothing asked yet").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        Claimed first = machine.claim(UOW, sent.id(), Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> machine.record(UOW, first, new Answered(CallKind.SUBMIT, Answer.unknown(Optional.empty(), "{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        world.store.horizonPassedForTest(sent.id());
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation())).as("an unknown answer past the horizon").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);

        clock.advance(Duration.ofMinutes(16));
        Claimed second = machine.claimNextDue(UOW, Duration.ofMinutes(2)).orElseThrow();
        asThePlatform(() -> machine.record(UOW, second, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));
        machine.permitResend(UOW, second);
        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation())).as("a send in flight").isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
        asThePlatform(() -> machine.record(UOW, second, new Answered(CallKind.INQUIRE, Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()));

        Acted undelivered = machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation());
        assertThat(undelivered.status()).isEqualTo(SubmissionStatus.UNDELIVERED);
        assertThat(world.events).extracting(EventEnvelope::eventType).contains(RegulatorySubmissions.UNDELIVERED_EVENT);
        assertThat(machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation()).replayed()).isTrue();

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

        assertThatThrownBy(() -> machine.declareUndelivered(UOW, sent.id(), REASON, SENDER, world.correlation())).isInstanceOf(RegulatorySubmissions.SubmissionStale.class);
    }

    @Test
    @DisplayName("ack against rejection is one way: the first definite answer takes the edge whichever attempt it came from; a later one is kept verbatim, flagged, audited, never an edge")
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
        List<RegulatorySubmissionStore.AnswerRow> kept = world.store.answers(UOW, sent.id());
        assertThat(kept).hasSize(2);
        assertThat(new String(kept.get(1).raw(), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(kept.get(1).flag()).contains(RegulatorySubmissionStore.Flag.CONTRARY);
        assertThat(codes()).contains("regulatory.SubmissionContraryAnswer");
        assertThat(world.events.stream().filter(event -> event.eventType().equals(RegulatorySubmissions.REJECTED_EVENT))).isEmpty();
        assertThat(world.store.attention(UOW, RegulatorySubmissions.MAX_RESENDS).contrary()).isEqualTo(1);

        // One agreeing, after the conclusion: kept, flagged late.
        asThePlatform(() -> assertThat(machine.record(UOW, first, new Answered(CallKind.INQUIRE, Answer.acknowledged("PROVIDER-1", "third".getBytes(StandardCharsets.UTF_8))), Duration.ofMinutes(15), world.correlation()).disposition())
                .isEqualTo(Disposition.LATE));
        assertThat(row(sent).contraryAnswers()).isEqualTo(1);
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
    }

    @Test
    @DisplayName("a pending list excludes what its reader cannot decide, in the statement: their own; a person's only")
    void aPendingListExcludesWhatItsReaderCannotDecide() {
        Acted validated = world.validated(world.drafted(obligation, JANUARY));

        assertThat(machine.awaitingApproval(UOW, APPROVER)).extracting(RegulatorySubmissionStore.Row::id).containsExactly(validated.id());
        assertThat(machine.awaitingApproval(UOW, PREPARER)).isEmpty();
        assertThatThrownBy(() -> machine.awaitingApproval(UOW, Actor.SYSTEM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("events carry ids and codes only, through the platform's envelope; audit summaries no figure and no reason; a toString no reason, figure or provider text")
    void nothingCarriesAFigureAReasonOrAProvidersText() {
        Acted sent = world.sent(obligation, JANUARY);
        rejected(sent);

        for (byte[] payload : world.payloads) {
            String text = new String(payload, StandardCharsets.UTF_8);
            assertThat(text).doesNotContain(REASON).doesNotContain("10000").doesNotContain("PROVIDER-1");
        }
        assertThat(world.events.stream().filter(event -> event.eventType().contains("Regulatory"))).hasSize(2).allSatisfy(event -> {
            assertThat(event.aggregateId().value()).isEqualTo(sent.id().value());
            assertThat(event.aggregateType()).isEqualTo(RegulatorySubmissions.TARGET_TYPE);
            assertThat(event.producer()).isEqualTo("accounting");
        });
        assertThat(world.audit).allSatisfy(record -> assertThat(record.changeSummary().orElse("")).doesNotContain(REASON).doesNotContain("10000"));
        assertThat(new Draft(obligation, world.row(JANUARY).id(), Optional.empty(), REASON).toString()).doesNotContain(REASON);
        assertThat(world.store.find(UOW, sent.id()).orElseThrow().toString()).doesNotContain(REASON);
        assertThat(new RegulatoryReportingProvider.Request(sent.id(), "REF", "F", 1, "2096-01", "SECRET-CONTENT", "h").toString()).doesNotContain("SECRET-CONTENT");
        assertThat(Answer.acknowledged("R", "PROVIDER-TEXT".getBytes(StandardCharsets.UTF_8)).toString()).doesNotContain("PROVIDER-TEXT");
        assertThat(world.store.lines(UOW, sent.id()).get(0).toString()).doesNotContain("10000");
        assertThat(world.store.answers(UOW, sent.id()).get(0).toString()).doesNotContain("FORM_REFUSED");
    }
}
