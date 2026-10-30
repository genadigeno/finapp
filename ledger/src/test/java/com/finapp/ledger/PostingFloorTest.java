package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The posting command's floor at the first open date (`P14-TSK-006`; owner decision C3, ratified 2026-10-30; ADR-0114
 * decision 6; {@code INV-ACC-03}, {@code -08}, {@code INV-IDEM-01}, {@code -03}) without a database: a
 * {@link PostingCommand.Dating#FLOOR_AT_FIRST_OPEN} command posts on its stated date, or - when the cutoff covers it - on
 * the day after the cutoff, the value date, lines and key unchanged; the cutoff is read under the shared key first, then
 * plainly under READ COMMITTED and {@code FOR SHARE} otherwise; an {@code AS_STATED} command never reads it; the
 * fingerprint binds the stated date, so a replay converges whatever the cutoff did. The serialisation itself - a poster
 * racing a lock never refused - is {@code PostingFloorDatabaseTest}'s (owed, C5).
 */
@DisplayName("a system poster's posting date floored at the first open date (P14-TSK-006, C3)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PostingFloorTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate STATED = LocalDate.of(1999, 1, 20);
    private static final LocalDate VALUE = LocalDate.of(1999, 1, 18);
    private static final LocalDate CUT = LocalDate.of(1999, 1, 31);
    private static final LocalDate FIRST_OPEN = LocalDate.of(1999, 2, 1);

    private final List<JournalEntry> appended = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();
    private final List<PostingObserver.Command> floors = new ArrayList<>();
    private final List<PostingObserver.Outcome> outcomes = new ArrayList<>();

    @Test
    @DisplayName("the cutoff covering the stated date: posted on the day after it, value date and lines kept, the stated"
            + " date named on the audit record, the floor observed - the key's shared lock read before the cutoff")
    void aCoveredDateIsFlooredToTheFirstOpenDate() {
        CutoffScript cutoff = CutoffScript.at(CUT);
        PostingCommand command = floored(STATED);
        PostingResult result = post(cutoff, command);

        assertThat(result.replayed()).isFalse();
        assertThat(appended).singleElement().satisfies(entry -> {
            assertThat(entry.postingDate()).as("the first open date").isEqualTo(FIRST_OPEN);
            assertThat(entry.valueDate()).as("the economic date, untouched").isEqualTo(VALUE);
            assertThat(entry.lines()).isEqualTo(command.lines());
            assertThat(entry.id()).isEqualTo(result.entryId());
        });
        assertThat(audited).singleElement().satisfies(record -> assertThat(record.changeSummary()).hasValueSatisfying(
                summary -> assertThat(summary).contains("flooredFrom=1999-01-20").doesNotContain("1500")));
        assertThat(floors).containsExactly(PostingObserver.Command.POSTING);
        assertThat(outcomes).containsExactly(PostingObserver.Outcome.POSTED);
        assertThat(cutoff.statements).as("the shared key, then the cutoff - two statements, READ COMMITTED")
                .containsExactly(PostingCutoffs.SHARED_READ_LOCK, PostingCutoffs.READ);
        assertThat(PostingCutoffs.SHARED_READ_LOCK).contains(PostingCutoffs.SHARED_LOCK)
                .contains("pg_advisory_xact_lock_shared(14, hashtext('cutoff'))");
    }

    @Test
    @DisplayName("the cutoff day itself is in the locked period: floored to the next day")
    void theCutoffDayItselfIsFloored() {
        post(CutoffScript.at(CUT), floored(CUT));
        assertThat(appended).singleElement().extracting(JournalEntry::postingDate).isEqualTo(FIRST_OPEN);
        assertThat(floors).hasSize(1);
    }

    @Test
    @DisplayName("a timely date keeps itself: the cutoff unset, or the date after it - no annotation, no floor observed")
    void aTimelyDateKeepsItsDate() {
        post(CutoffScript.unset(), floored(STATED));
        post(CutoffScript.at(CUT), floored(FIRST_OPEN));
        assertThat(appended).extracting(JournalEntry::postingDate).containsExactly(STATED, FIRST_OPEN);
        assertThat(audited).allSatisfy(record -> assertThat(record.changeSummary().orElseThrow())
                .doesNotContain("flooredFrom"));
        assertThat(floors).isEmpty();
    }

    @Test
    @DisplayName("under REPEATABLE READ or SERIALIZABLE the cutoff is read FOR SHARE - a move after the snapshot is 40001,"
            + " never a stale floor")
    void aSnapshotIsolationReadsForShare() {
        CutoffScript repeatable = CutoffScript.at(CUT, "repeatable read");
        post(repeatable, floored(STATED));
        assertThat(repeatable.statements).containsExactly(PostingCutoffs.SHARED_READ_LOCK,
                PostingCutoffs.READ + " FOR SHARE");
        CutoffScript serializable = CutoffScript.at(CUT, "serializable");
        post(serializable, floored(STATED));
        assertThat(serializable.statements).last().isEqualTo(PostingCutoffs.READ + " FOR SHARE");
        assertThat(appended).extracting(JournalEntry::postingDate).containsOnly(FIRST_OPEN);
    }

    @Test
    @DisplayName("an AS_STATED command never reads the cutoff - its refusal stays the trigger's (ledger.PeriodClosed)")
    void anAsStatedCommandNeverReadsTheCutoff() {
        CutoffScript cutoff = CutoffScript.at(CUT);
        PostingCommand stated = new PostingCommand("floor-" + IDS.next(), STATED, VALUE, "an as-stated posting", lines());
        assertThat(stated.dating()).as("the five-argument constructor").isEqualTo(PostingCommand.Dating.AS_STATED);
        post(cutoff, stated);
        assertThat(cutoff.statements).isEmpty();
        assertThat(appended).singleElement().extracting(JournalEntry::postingDate).isEqualTo(STATED);
        assertThat(floors).isEmpty();
    }

    @Test
    @DisplayName("the key's fingerprint binds the stated date and not the dating: a floored command is the same request as"
            + " its AS_STATED twin, so a replay after any cutoff move converges on the first entry")
    void theFingerprintBindsTheStatedDate() {
        String key = "floor-" + IDS.next();
        List<JournalLine> lines = lines();
        PostingCommand flooredCommand = new PostingCommand(key, STATED, VALUE, "ref", lines,
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN);
        PostingCommand asStated = new PostingCommand(key, STATED, VALUE, "ref", lines);
        assertThat(PostingService.canonicalForm(flooredCommand)).isEqualTo(PostingService.canonicalForm(asStated));
        assertThat(new String(PostingService.canonicalForm(flooredCommand), java.nio.charset.StandardCharsets.UTF_8))
                .contains("|1999-01-20|1999-01-18|").doesNotContain("1999-02-01").doesNotContain("FLOOR");
        assertThat(PostingService.canonicalForm(new PostingCommand(key, FIRST_OPEN, VALUE, "ref", lines,
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN)))
                .as("a different stated date is a different request (INV-IDEM-03)")
                .isNotEqualTo(PostingService.canonicalForm(flooredCommand));
    }

    @Test
    @DisplayName("a missing cutoff row fails closed: nothing appended")
    void aMissingCutoffRowFailsClosed() {
        assertThatThrownBy(() -> post(CutoffScript.missingRow(), floored(STATED)))
                .isInstanceOf(LedgerStorageException.class).hasMessageContaining("posting cutoff row is missing");
        assertThat(appended).isEmpty();
    }

    @Test
    @DisplayName("the approval guard: a date the cutoff covers is ledger.PeriodClosed naming the subject and the dates;"
            + " a date after it, or an unset cutoff, passes")
    void theApprovalGuardRefusesACoveredDate() {
        CutoffScript cutoff = CutoffScript.at(CUT);
        assertThatThrownBy(() -> PostingCutoffs.requireOpen(cutoff.connection(), STATED, "MANUAL adjustment proposal x"))
                .isInstanceOf(PeriodClosedException.class)
                .hasMessageContaining("MANUAL adjustment proposal x is dated 1999-01-20")
                .hasMessageContaining("posting cutoff 1999-01-31")
                .hasMessageContaining("left PROPOSED");
        assertThatThrownBy(() -> PostingCutoffs.requireOpen(cutoff.connection(), CUT, "s"))
                .isInstanceOf(PeriodClosedException.class);
        PostingCutoffs.requireOpen(cutoff.connection(), FIRST_OPEN, "s");
        PostingCutoffs.requireOpen(CutoffScript.unset().connection(), STATED, "s");
        assertThat(PostingCutoffs.firstOpenOnOrAfter(CutoffScript.at(CUT).connection(), STATED)).isEqualTo(FIRST_OPEN);
        assertThat(PostingCutoffs.firstOpenOnOrAfter(CutoffScript.unset().connection(), STATED)).isEqualTo(STATED);
    }

    @Test
    @DisplayName("a floor moves a date forward only, before the entry is written - the same entry otherwise")
    void aFloorMovesForwardOnly() {
        JournalEntry entry = JournalEntry.balanced(IDS, CLOCK, STATED, VALUE, lines());
        JournalEntry floored = entry.postedOn(FIRST_OPEN);
        assertThat(floored.id()).isEqualTo(entry.id());
        assertThat(floored.valueDate()).isEqualTo(VALUE);
        assertThat(floored.createdAt()).isEqualTo(entry.createdAt());
        assertThat(floored.lines()).isEqualTo(entry.lines());
        assertThatThrownBy(() -> entry.postedOn(STATED.minusDays(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(floored(STATED).toString()).contains("floored at the first open date").doesNotContain("1500");
    }

    // ------------------------------------------------------------------

    private PostingResult post(CutoffScript cutoff, PostingCommand command) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return service().post(cutoff.connection(), command);
        }
    }

    private PostingService service() {
        return new PostingService(
                new IdempotentExecutor(claims(), CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                recording(JournalEntryStore.class, "append", args -> appended.add((JournalEntry) args[1])),
                recording(AuditWriter.class, "append", args -> audited.add((AuditRecord) args[1])),
                recording(OutboxWriter.class, "write", args -> {}),
                recording(BalanceProjection.class, "apply", args -> {}),
                IDS,
                CLOCK,
                new PostingObserver() {
                    @Override
                    public void observe(Outcome outcome, Duration elapsed) {
                        outcomes.add(outcome);
                    }

                    @Override
                    public void flooredAtTheCutoff(Command command) {
                        floors.add(command);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static IdempotencyRecordStore<Connection> claims() {
        return (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(PostingFloorTest.class.getClassLoader(),
                new Class<?>[] {IdempotencyRecordStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "claim" -> IdempotencyRecordStore.ClaimOutcome.CLAIMED;
                    case "complete" -> true;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> type, String method, java.util.function.Consumer<Object[]> record) {
        return (T) Proxy.newProxyInstance(PostingFloorTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, call, args) -> {
                    if (!call.getName().equals(method)) {
                        throw new AssertionError(type.getSimpleName() + "." + call.getName());
                    }
                    record.accept(args);
                    return null;
                });
    }

    private static PostingCommand floored(LocalDate stated) {
        return new PostingCommand("floor-" + IDS.next(), stated, VALUE, "a floored posting", lines(),
                PostingCommand.Dating.FLOOR_AT_FIRST_OPEN);
    }

    private static List<JournalLine> lines() {
        return List.of(
                new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD)));
    }
}
