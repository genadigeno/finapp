package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.GlMappingVersionStatus;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.Side;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.ErrorCode;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The GL doors (`P14-TSK-007`; {@code PHASE_14_PLAN.md} sections 10, 12; {@code INV-ACC-02}, {@code INV-ACC-06},
 * {@code INV-ACC-14}): each answer computed in one verified read-only snapshot and <strong>recorded
 * {@code accounting.GlRead} before it is served</strong>, in a transaction of its own; a record that cannot be written
 * fails the read; an actor-less call reads and records nothing; {@code accounting.NoActiveMapping} 409 without a version,
 * {@code accounting.NotFound} 404 for an unknown GL account or entry, {@code api.ValidationFailed} 422 for a malformed
 * period, currency, page size or cursor; every route {@code ACCOUNTING_READ}, the accountant's alone (the interceptor's
 * refusal of a caller without it is {@code DenyByDefaultDatabaseTest}'s, the declarations pinned by
 * {@code RoutePermissionRegisterTest}). Hermetic: the journal is {@code InMemoryJournal}, the session a stand-in.
 */
@DisplayName("the GL doors: audited reads in one snapshot (P14-TSK-007)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class GeneralLedgerDeskTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-03T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

    private final InMemoryJournal journal = new InMemoryJournal();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final List<SnapshotSession> sessions = new ArrayList<>();
    private final List<String> units = new ArrayList<>();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final AccountingTransactions transactions = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            units.add("transaction");
            return work.apply(null);
        }
    };
    private final Actor accountant = new Actor("accountant-1", ActorType.EMPLOYEE);

    @Test
    @DisplayName("a period's GL is served from one snapshot and recorded once - SUCCEEDED, the period the target, the door,"
            + " currency, version and counts the summary, never an amount")
    void balancesAreServedAndRecorded() {
        activate();
        postNovember();
        GeneralLedgerDesk.GlBalancesView view = as(accountant, () -> desk().balances("2026-11", null));

        assertThat(view.period()).isEqualTo("2026-11");
        assertThat(view.periodStatus()).isEqualTo("NONE");
        assertThat(view.mappingVersion()).isEqualTo(1);
        assertThat(view.agrees()).isTrue();
        assertThat(view.figures()).isNotEmpty().allSatisfy(figure -> {
            assertThat(figure.mappingVersion()).as("every figure names its version").isEqualTo(1);
            assertThat(figure.mappingVersionId()).isEqualTo(view.mappingVersionId());
        });
        GeneralLedgerDesk.GlFigureView wallets = view.figures().stream()
                .filter(f -> f.glAccountCode().equals("2000") && f.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(wallets.credits()).isEqualTo("100.00");
        assertThat(wallets.debits()).isEqualTo("2.50");
        assertThat(wallets.netMovement()).isEqualTo("97.50");
        assertThat(wallets.normalBalance()).isEqualTo("CREDIT");
        assertThat(view.agreement()).extracting(GeneralLedgerDesk.GlAgreementView::currency).containsExactly("EUR", "USD");
        assertThat(sessions).as("one snapshot for the whole read").hasSize(1);
        assertThat(sessions.get(0).calls).startsWith("autoCommit=false").contains("readOnly=true", "verify")
                .endsWith("rollback", "close");
        assertThat(units).containsExactly("transaction");
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(AccountingAuditAction.GL_READ);
            assertThat(record.actor()).isEqualTo(accountant);
            assertThat(record.targetType()).isEqualTo("gl_period");
            assertThat(record.targetId()).isEqualTo("2026-11");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                    .contains("door=balances", "period=2026-11", "currency=ALL", "version=1")
                    .doesNotContain("100.00", "2.50", "97.50"));
            assertThat(record.occurredAt()).isEqualTo(CLOCK.instant());
        });
    }

    @Test
    @DisplayName("an account's activity is its figure and keyset pages of its lines - owner references, never a name -"
            + " recorded per page against the GL account")
    void activityIsPagedAndRecorded() {
        activate();
        postNovember();
        GeneralLedgerDesk.GlActivityView first = as(accountant, () -> desk().activity("2000", "2026-11", "EUR", null, 1));
        assertThat(first.figure().lines()).isEqualTo(2);
        assertThat(first.lines()).singleElement().satisfies(line -> {
            assertThat(line.purpose()).isEqualTo("CUSTOMER_WALLET");
            assertThat(line.ownerRef()).as("a reference, never a name").matches("[0-9a-f-]{36}");
            assertThat(line.glAccountCode()).isNull();
        });
        assertThat(first.next()).isNotNull();
        GeneralLedgerDesk.GlActivityView second = as(accountant,
                () -> desk().activity("2000", "2026-11", "EUR", first.next(), 1));
        assertThat(second.lines()).singleElement().satisfies(line ->
                assertThat(line.entryId()).isNotEqualTo(first.lines().get(0).entryId()));
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (GeneralLedgerDesk.GlLineView line : List.of(first.lines().get(0), second.lines().get(0))) {
            if (line.side().equals("DEBIT")) {
                debits = debits.add(new BigDecimal(line.amount()));
            } else {
                credits = credits.add(new BigDecimal(line.amount()));
            }
        }
        assertThat(debits).isEqualByComparingTo(first.figure().debits());
        assertThat(credits).isEqualByComparingTo(first.figure().credits());
        assertThat(audit).hasSize(2).allSatisfy(record -> {
            assertThat(record.targetType()).isEqualTo("gl_account");
            assertThat(record.targetId()).isEqualTo("2000");
        });
        assertThat(audit.get(0).changeSummary()).hasValueSatisfying(s -> assertThat(s).contains("page=first", "lines=1"));
        assertThat(audit.get(1).changeSummary()).hasValueSatisfying(s -> assertThat(s).contains("page=next"));
    }

    @Test
    @DisplayName("an entry's GL view maps each line under its posting month's version - never the entry's reference, free"
            + " text on a manual adjustment (the review's I-1) - recorded against the entry")
    void anEntryIsMappedAndRecorded() {
        activate();
        JournalReads.Entry entry = journal.post(LocalDate.of(2026, 11, 3), "EUR", 2, 10000, "SETTLEMENT_CLEARING",
                "OPERATIONAL", "CUSTOMER_WALLET", "CUSTOMER");
        GeneralLedgerDesk.GlEntryView view = as(accountant, () -> desk().entry(entry.id().toString()));
        assertThat(view.lines()).extracting(GeneralLedgerDesk.GlLineView::glAccountCode).containsExactly("1100", "2000");
        assertThat(view.period()).isEqualTo("2026-11");
        assertThat(view.mappingVersion()).isEqualTo(1);
        assertThat(view.namespace()).as("a reader that does not say serves none, never a guess").isNull();
        assertThat(audit).singleElement().satisfies(record -> {
            assertThat(record.targetType()).isEqualTo("journal_entry");
            assertThat(record.targetId()).isEqualTo(entry.id().toString());
        });

        // P14-DOC-001 (the gate's criterion 2): the entry names the namespace it is judged under, as the reader says it.
        UUID id = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 11, 4);
        JournalReads.Entry named = new JournalReads.Entry(id, "POSTING", day, day, Optional.empty(), List.of(
                InMemoryJournal.line(id, 0, day, "SETTLEMENT_CLEARING", "OPERATIONAL", Optional.empty(), Side.DEBIT, 500, "EUR", 2),
                InMemoryJournal.line(id, 1, day, "CUSTOMER_WALLET", "CUSTOMER", Optional.empty(), Side.CREDIT, 500, "EUR", 2)),
                Optional.of("transfer"));
        journal.post(named);
        assertThat(as(accountant, () -> desk().entry(named.id().toString())).namespace()).isEqualTo("transfer");
    }

    @Test
    @DisplayName("a record that cannot be written fails the read - nothing is served unrecorded")
    void anUnrecordableReadFails() {
        activate();
        postNovember();
        GeneralLedgerDesk desk = new GeneralLedgerDesk(new GeneralLedger(journal), pins(), snapshots(), transactions,
                (unitOfWork, record) -> {
                    throw new IllegalStateException("the audit store refused");
                }, AccountingFlowScope.NONE, IDS, CLOCK);
        assertThatThrownBy(() -> as(accountant, () -> desk.balances("2026-11", "EUR")))
                .hasMessageContaining("the audit store refused");
    }

    @Test
    @DisplayName("without an authenticated actor nothing is read or recorded")
    void anActorlessCallReadsNothing() {
        activate();
        postNovember();
        assertThatThrownBy(() -> desk().balances("2026-11", null)).isInstanceOf(RuntimeException.class);
        assertThat(journal.reads()).isZero();
        assertThat(sessions).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("no version pinned or in force: 409 accounting.NoActiveMapping, nothing recorded")
    void noVersionIsNoActiveMapping() {
        postNovember();
        refused(() -> as(accountant, () -> desk().balances("2026-11", null)), AccountingErrorCode.NO_ACTIVE_MAPPING, 409);
        activate();
        refused(() -> as(accountant, () -> desk().balances("2026-10", null)), AccountingErrorCode.NO_ACTIVE_MAPPING, 409);
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("an unknown or malformed GL account code or entry id: 404 accounting.NotFound")
    void unknownTargetsAreNotFound() {
        activate();
        postNovember();
        refused(() -> as(accountant, () -> desk().activity("9999", "2026-11", "EUR", null, null)),
                AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(accountant, () -> desk().activity("cash", "2026-11", "EUR", null, null)),
                AccountingErrorCode.NOT_FOUND, 404);
        refused(() -> as(accountant, () -> desk().entry(UUID.randomUUID().toString())), AccountingErrorCode.NOT_FOUND,
                404);
        refused(() -> as(accountant, () -> desk().entry("not-a-uuid")), AccountingErrorCode.NOT_FOUND, 404);
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("a malformed period, currency, page size or cursor - or an activity without a currency: 422"
            + " api.ValidationFailed, before anything is read")
    void malformedParametersAreInvalid() {
        activate();
        for (Supplier<?> call : List.<Supplier<?>>of(
                () -> desk().balances("2026-13", null),
                () -> desk().balances("Nov 2026", null),
                () -> desk().balances(null, null),
                () -> desk().balances("2026-11", "eur"),
                () -> desk().balances("2026-11", "XXX"),
                () -> desk().activity("2000", "2026-11", null, null, null),
                () -> desk().activity("2000", "2026-11", "EUR", null, 0),
                () -> desk().activity("2000", "2026-11", "EUR", null, 501),
                () -> desk().activity("2000", "2026-11", "EUR", "garbage!", null),
                // A well-formed cursor of another month, or of a year far outside the calendar (the review's M-2).
                () -> desk().activity("2000", "2026-11", "EUR", cursor(LocalDate.of(2026, 12, 1)), null),
                () -> desk().activity("2000", "2026-11", "EUR", cursor(LocalDate.of(999_999, 1, 1)), null))) {
            refused(() -> as(accountant, call), PlatformErrorCode.VALIDATION_FAILED, 422);
        }
        assertThat(journal.reads()).isZero();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("the routes are GET /operator/accounting/gl/... under ACCOUNTING_READ, which only the ACCOUNTANT holds")
    void theRoutesAreTheAccountants() throws NoSuchMethodException {
        assertThat(GeneralLedgerController.class.getAnnotation(RequestMapping.class).path())
                .containsExactly("/operator/accounting/gl");
        List<Method> handlers = List.of(
                GeneralLedgerController.class.getMethod("readGeneralLedgerBalances", String.class, String.class),
                GeneralLedgerController.class.getMethod("readGeneralLedgerAccountActivity", String.class, String.class,
                        String.class, String.class, Integer.class),
                GeneralLedgerController.class.getMethod("readGeneralLedgerEntry", String.class));
        assertThat(handlers).extracting(handler -> handler.getAnnotation(GetMapping.class).value()[0])
                .containsExactly("/balances", "/accounts/{code}/activity", "/entries/{entryId}");
        assertThat(handlers).allSatisfy(handler -> assertThat(handler.getAnnotation(RequiresPermission.class).value())
                .isEqualTo(PermissionName.ACCOUNTING_READ));
        assertThat(Arrays.stream(RoleName.values()).filter(role -> role.permissions().contains(
                PermissionName.ACCOUNTING_READ))).containsExactly(RoleName.ACCOUNTANT);
    }

    @Test
    @DisplayName("a statement the snapshot's timeout cancelled: 503 accounting.ReadTimedOut, the snapshot rolled back,"
            + " nothing recorded (the review's I-2)")
    void aTimedOutReadIsReadTimedOut() {
        activate();
        postNovember();
        JournalReads slow = new JournalReads() {
            @Override
            public List<Aggregate> aggregates(Connection snapshot, Range range) {
                throw new AccountingStorageException(DatabaseFailure.describe(
                        "reading the journal's aggregates for a posting-date range",
                        new SQLException("canceling statement due to statement timeout", "57014")));
            }

            @Override
            public List<Total> totals(Connection snapshot, Range range) {
                return journal.totals(snapshot, range);
            }

            @Override
            public List<Line> lines(Connection snapshot, LineQuery query) {
                return journal.lines(snapshot, query);
            }

            @Override
            public Optional<Entry> entry(Connection snapshot, UUID entryId) {
                return journal.entry(snapshot, entryId);
            }

            @Override
            public long countLines(Connection snapshot, java.util.Set<String> keys, LocalDate fromInclusive) {
                return journal.countLines(snapshot, keys, fromInclusive);
            }
        };
        GeneralLedgerDesk desk = new GeneralLedgerDesk(new GeneralLedger(slow), pins(), snapshots(), transactions,
                (unitOfWork, record) -> audit.add(record), AccountingFlowScope.NONE, IDS, CLOCK);
        refused(() -> as(accountant, () -> desk.balances("2026-11", null)), AccountingErrorCode.READ_TIMED_OUT, 503);
        assertThat(sessions).singleElement().satisfies(session -> assertThat(session.calls)
                .contains("timeout=" + GlSnapshots.STATEMENT_TIMEOUT.toMillis() + "ms").endsWith("rollback", "close"));
        assertThat(audit).isEmpty();
    }

    // ------------------------------------------------------------------

    private static String cursor(LocalDate day) {
        return new JournalReads.LineCursor(day, UUID.randomUUID(), 0).encoded();
    }

    private GeneralLedgerDesk desk() {
        return new GeneralLedgerDesk(new GeneralLedger(journal), pins(), snapshots(), transactions,
                (unitOfWork, record) -> audit.add(record), AccountingFlowScope.NONE, IDS, CLOCK);
    }

    private GlPins pins() {
        return new GlPins(mappings, (snapshot, month) -> Optional.empty());
    }

    private GlSnapshots snapshots() {
        return new GlSnapshots(() -> {
            SnapshotSession session = new SnapshotSession();
            sessions.add(session);
            return session.connection();
        }, GlSnapshots.STATEMENT_TIMEOUT);
    }

    private void activate() {
        GlMappingVersionId id = GlMappingVersionId.next(IDS);
        mappings.insertProposal(null, new GlMappingStore.NewProposal(id, 1, ReferenceGlMapping.version(NOVEMBER),
                accountant, "the reference", CorrelationId.of("flow-propose"), UUID.randomUUID()));
        mappings.decide(null, id, GlMappingVersionStatus.ACTIVE, new Actor("controller-1", ActorType.EMPLOYEE), "ok");
    }

    private void postNovember() {
        journal.post(LocalDate.of(2026, 11, 3), "EUR", 2, 10000, "SETTLEMENT_CLEARING", "OPERATIONAL", "CUSTOMER_WALLET",
                "CUSTOMER");
        journal.post(LocalDate.of(2026, 11, 5), "EUR", 2, 250, "CUSTOMER_WALLET", "CUSTOMER", "FEE_REVENUE",
                "OPERATIONAL");
        journal.post(LocalDate.of(2026, 11, 9), "USD", 2, 700, "SETTLEMENT_CLEARING", "OPERATIONAL", "CUSTOMER_WALLET",
                "CUSTOMER");
        journal.post(new JournalReads.Entry(UUID.randomUUID(), "POSTING", LocalDate.of(2026, 12, 1),
                LocalDate.of(2026, 12, 1), Optional.empty(), List.of(
                        InMemoryJournal.line(UUID.randomUUID(), 0, LocalDate.of(2026, 12, 1), "CUSTOMER_WALLET",
                                "CUSTOMER", Optional.empty(), Side.DEBIT, 5, "EUR", 2),
                        InMemoryJournal.line(UUID.randomUUID(), 1, LocalDate.of(2026, 12, 1), "FEE_REVENUE",
                                "OPERATIONAL", Optional.empty(), Side.CREDIT, 5, "EUR", 2))));
    }

    private static void refused(Runnable call, ErrorCode code, int status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.errorCode()).isEqualTo(code);
            assertThat(refusal.errorCode().status()).isEqualTo(status);
        });
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of("flow-" + UUID.randomUUID())))) {
            return work.get();
        }
    }
}
