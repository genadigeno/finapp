package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting break's machine (`P14-TSK-010`; ADR-0117 decision 3; {@code INV-ACC-12}, {@code INV-ACC-05},
 * {@code INV-AUD-04}): born once per {@code (period, check, subject)} - a second raise writes nothing, not even an audit
 * record or an event; acknowledged only by a second person's approval of a disclosed proposal; resolved only by reference
 * to an existing entry other than its subject; persons only; every act a case-file row and an audit record. Hermetic:
 * the store holds the unique key and the four-eyes rank in memory; the live ranks are
 * {@code AccountingBreakDatabaseTest}'s (owed, C5).
 */
@DisplayName("the accounting break's machine: raise once, acknowledge four-eyes, resolve by reference (P14-TSK-010)")
class AccountingBreaksTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);

    private final InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
    private final InMemoryJournal journal = new InMemoryJournal();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private final AccountingBreaks breaks = new AccountingBreaks(store, journal, (unitOfWork, record) -> audit.add(record),
            (unitOfWork, envelope, payload, mediaType) -> {
                events.add(envelope);
                payloads.add(payload);
            },
            IDS, Clock.fixed(Instant.parse("2026-10-31T09:00:00Z"), ZoneOffset.UTC));

    private final Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
    private final Actor controller = new Actor("controller-1", ActorType.CUSTOMER);

    @Test
    @DisplayName("two raises, one break: the second finds the subject held and writes nothing - no case-file row, audit"
            + " record or event; the first wrote all three with the break, ids and codes only")
    void twoRaisesOneBreak() {
        BreakFinding finding = shapeMismatch(UUID.randomUUID());

        assertThat(breaks.raise(UOW, MARCH, finding, Actor.SYSTEM, correlation())).isTrue();
        assertThat(breaks.raise(UOW, MARCH, finding, Actor.SYSTEM, correlation())).isFalse();

        assertThat(store.rows()).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo(BreakStatus.OPEN);
            assertThat(row.period()).isEqualTo(MARCH);
            assertThat(row.judgedBy()).isEqualTo(finding.judgedBy());
        });
        assertThat(store.history()).extracting(AccountingBreakStore.EventRow::act)
                .containsExactly(AccountingBreakStore.Act.RAISED);
        assertThat(audit).extracting(record -> record.operation().code()).containsExactly("accounting.BreakRaised");
        assertThat(audit.get(0).changeSummary()).hasValueSatisfying(summary -> assertThat(summary)
                .contains("check=RULE_CONFORMANCE").contains("type=SHAPE_MISMATCH").contains("judgedBy=posting-rules:v2"));
        assertThat(events).extracting(EventEnvelope::eventType).containsExactly(AccountingBreaks.RAISED_EVENT);
        assertThat(new String(payloads.get(0), StandardCharsets.UTF_8)).contains("2097-03").contains("SHAPE_MISMATCH")
                .contains("\"register\":\"posting-rules\"").contains("\"registerVersion\":\"2\"")
                .doesNotContain(finding.subject());
        // The same subject in another period is another break; another subject in the same period too.
        assertThat(breaks.raise(UOW, MARCH.plusMonths(1), finding, Actor.SYSTEM, correlation())).isTrue();
        assertThat(breaks.raise(UOW, MARCH, shapeMismatch(UUID.randomUUID()), Actor.SYSTEM, correlation())).isTrue();
        assertThat(store.rows()).hasSize(3);
    }

    @Test
    @DisplayName("acknowledgement is four-eyes: the proposal moves nothing, the proposer cannot approve - refused before"
            + " anything is written - and a second person's approval acknowledges with the disclosure kept")
    void theProposerCannotAcknowledge() {
        AccountingBreakId id = raised();

        AccountingBreaks.Acted proposed = breaks.proposeAcknowledgement(UOW, id, accountant,
                "the transfer poster's fee line, corrected in the next period", correlation());
        assertThat(proposed.status()).isEqualTo(BreakStatus.OPEN);
        assertThat(proposed.acknowledgementPending()).isTrue();

        assertThatThrownBy(() -> breaks.approveAcknowledgement(UOW, id, accountant, "my own", correlation()))
                .isInstanceOf(AccountingBreaks.SelfApprovalRefused.class);
        assertThat(store.find(UOW, id)).hasValueSatisfying(row -> assertThat(row.status()).isEqualTo(BreakStatus.OPEN));

        AccountingBreaks.Acted acknowledged = breaks.approveAcknowledgement(UOW, id, controller, "disclosed at close",
                correlation());
        assertThat(acknowledged.status()).isEqualTo(BreakStatus.ACKNOWLEDGED);
        assertThat(store.find(UOW, id)).hasValueSatisfying(row -> {
            assertThat(row.disclosure()).contains("the transfer poster's fee line, corrected in the next period");
            assertThat(row.ackProposedBy()).contains("accountant-1");
            assertThat(row.ackApprovedBy()).contains("controller-1");
        });
        assertThat(store.history()).extracting(AccountingBreakStore.EventRow::act).containsExactly(
                AccountingBreakStore.Act.RAISED, AccountingBreakStore.Act.ACKNOWLEDGEMENT_PROPOSED,
                AccountingBreakStore.Act.ACKNOWLEDGED);
        assertThat(store.history().get(2).proposedBy()).contains("accountant-1");
        assertThat(audit).extracting(record -> record.operation().code()).containsExactly("accounting.BreakRaised",
                "accounting.BreakAcknowledgementProposed", "accounting.BreakAcknowledged");
        assertThat(audit.get(1).reason()).contains("the transfer poster's fee line, corrected in the next period");
    }

    @Test
    @DisplayName("a refused self-approval is recorded FAILED in its own transaction, naming the break and the act")
    void aRefusedSelfApprovalIsRecorded() {
        AccountingBreakId id = raised();
        breaks.proposeAcknowledgement(UOW, id, accountant, "a disclosure", correlation());
        AccountingBreaks.SelfApprovalRefused refused = org.junit.jupiter.api.Assertions.assertThrows(
                AccountingBreaks.SelfApprovalRefused.class,
                () -> breaks.approveAcknowledgement(UOW, id, accountant, "mine", correlation()));

        breaks.recordRefusal(UOW, refused, accountant, correlation());

        assertThat(audit).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.SelfApprovalRefused");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(id.value().toString());
            assertThat(record.changeSummary()).contains("period=2097-03, act=break:acknowledgement");
        });
    }

    @Test
    @DisplayName("the acts converge and refuse what is stale: a repeat of the same person's act replays, a second"
            + " proposer or an approval with none pending is BreakStale, a disclosure must be reasoned")
    void theActsConvergeAndRefuseWhatIsStale() {
        AccountingBreakId id = raised();
        assertThatThrownBy(() -> breaks.approveAcknowledgement(UOW, id, controller, "none proposed", correlation()))
                .isInstanceOf(AccountingBreaks.BreakStale.class);
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, id, accountant, " ", correlation()))
                .isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, id, accountant, "card 4111111111111111",
                correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);

        breaks.proposeAcknowledgement(UOW, id, accountant, "a disclosure", correlation());
        assertThat(breaks.proposeAcknowledgement(UOW, id, accountant, "a disclosure", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, id, controller, "another", correlation()))
                .isInstanceOf(AccountingBreaks.BreakStale.class);
        breaks.approveAcknowledgement(UOW, id, controller, "approved", correlation());
        assertThat(breaks.approveAcknowledgement(UOW, id, controller, "approved", correlation()).replayed()).isTrue();
        assertThatThrownBy(() -> breaks.approveAcknowledgement(UOW, id, new Actor("controller-2", ActorType.CUSTOMER),
                "late", correlation())).isInstanceOf(AccountingBreaks.BreakStale.class);
        assertThat(store.history()).hasSize(3);
        assertThatThrownBy(() -> breaks.raise(UOW, MARCH, shapeMismatch(UUID.randomUUID()),
                new Actor("svc", ActorType.SERVICE), correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, raised(), Actor.SYSTEM, "the platform's",
                correlation())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("resolution is by reference only: an existing entry other than the subject - OPEN or ACKNOWLEDGED ->"
            + " RESOLVED, the entry named on the row, the case file and the audit summary; nothing posted")
    void resolutionIsByReferenceOnly() {
        UUID subjectEntry = UUID.randomUUID();
        AccountingBreakId id = raised(shapeMismatch(subjectEntry));
        JournalReads.Entry correcting = journal.post(LocalDate.of(2097, 4, 2), "EUR", 2, 100, "CUSTOMER_WALLET",
                "CUSTOMER", "FEE_REVENUE", "OPERATIONAL");
        int reads = journal.reads();

        assertThatThrownBy(() -> breaks.resolve(UOW, id, controller, UUID.randomUUID(), "no such entry", correlation()))
                .isInstanceOf(AccountingBreaks.CorrectingEntryInvalid.class);
        assertThatThrownBy(() -> breaks.resolve(UOW, id, controller, subjectEntry, "the subject itself", correlation()))
                .isInstanceOf(AccountingBreaks.CorrectingEntryInvalid.class);

        AccountingBreaks.Acted resolved = breaks.resolve(UOW, id, controller, correcting.id(), "reversed and re-posted",
                correlation());

        assertThat(resolved.status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(store.find(UOW, id)).hasValueSatisfying(row ->
                assertThat(row.resolvingEntry()).contains(correcting.id()));
        assertThat(store.history()).last().satisfies(event -> {
            assertThat(event.act()).isEqualTo(AccountingBreakStore.Act.RESOLVED);
            assertThat(event.from()).contains(BreakStatus.OPEN);
            assertThat(event.resolvingEntry()).contains(correcting.id());
        });
        assertThat(audit).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.BreakResolved");
            assertThat(record.changeSummary()).hasValueSatisfying(summary ->
                    assertThat(summary).contains("correctingEntry=" + correcting.id()).contains("from=OPEN"));
        });
        assertThat(journal.reads()).as("the resolution read the entry; the journal port has no write")
                .isGreaterThan(reads);
        assertThat(breaks.resolve(UOW, id, controller, correcting.id(), "reversed and re-posted", correlation())
                .replayed()).isTrue();
        assertThatThrownBy(() -> breaks.resolve(UOW, id, accountant, correcting.id(), "again", correlation()))
                .isInstanceOf(AccountingBreaks.BreakStale.class);
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, id, accountant, "late", correlation()))
                .isInstanceOf(AccountingBreaks.BreakStale.class);

        // An acknowledged break resolves too.
        AccountingBreakId other = raised();
        breaks.proposeAcknowledgement(UOW, other, accountant, "disclosed", correlation());
        breaks.approveAcknowledgement(UOW, other, controller, "approved", correlation());
        assertThat(breaks.resolve(UOW, other, controller, correcting.id(), "corrected", correlation()).status())
                .isEqualTo(BreakStatus.RESOLVED);
        assertThat(store.history()).last().satisfies(event -> assertThat(event.from()).contains(BreakStatus.ACKNOWLEDGED));
    }

    @Test
    @DisplayName("a resolution must be a plausible correction (the review's I-2): an entry posted before the subject"
            + " entry, before the period for another subject, or named in the break's evidence is refused - nothing"
            + " changed, the refusal recordable FAILED")
    void aResolutionMustBeAPlausibleCorrection() {
        JournalReads.Entry subject = journal.post(LocalDate.of(2097, 3, 20), "EUR", 2, 100, "CUSTOMER_WALLET",
                "CUSTOMER", "FEE_REVENUE", "OPERATIONAL");
        AccountingBreakId entryBreak = raised(shapeMismatch(subject.id()));
        JournalReads.Entry earlier = journal.post(LocalDate.of(2097, 3, 10), "EUR", 2, 100, "FEE_REVENUE",
                "OPERATIONAL", "CUSTOMER_WALLET", "CUSTOMER");
        AccountingBreaks.CorrectingEntryInvalid beforeSubject = org.junit.jupiter.api.Assertions.assertThrows(
                AccountingBreaks.CorrectingEntryInvalid.class,
                () -> breaks.resolve(UOW, entryBreak, controller, earlier.id(), "an earlier entry", correlation()));
        assertThat(beforeSubject.summary()).contains("refused=BEFORE_SUBJECT").contains(earlier.id().toString());

        JournalReads.Entry listed = journal.post(LocalDate.of(2097, 4, 1), "EUR", 2, 7, "CUSTOMER_WALLET", "CUSTOMER",
                "FEE_REVENUE", "OPERATIONAL");
        AccountingBreakId namespaceBreak = raised(new BreakFinding(CloseCheckCode.RULE_CONFORMANCE,
                BreakType.UNREGISTERED_POSTING, "namespace:POSTING probe:", "type=POSTING, namespace=probe:, entries=3,"
                        + " first=[" + listed.id() + "]", "posting-rules:v2"));
        assertThatThrownBy(() -> breaks.resolve(UOW, namespaceBreak, controller, listed.id(), "one of its own",
                correlation())).isInstanceOfSatisfying(AccountingBreaks.CorrectingEntryInvalid.class, refused ->
                assertThat(refused.summary()).contains("refused=IN_EVIDENCE"));

        AccountingBreakId keyBreak = raised(new BreakFinding(CloseCheckCode.GL_EQUALS_LEDGER, BreakType.MAPPING_GAP,
                "key:PROBE/OPERATIONAL", "key=PROBE/OPERATIONAL", "gl-mapping:v1"));
        JournalReads.Entry february = journal.post(LocalDate.of(2097, 2, 27), "EUR", 2, 1, "CUSTOMER_WALLET",
                "CUSTOMER", "FEE_REVENUE", "OPERATIONAL");
        assertThatThrownBy(() -> breaks.resolve(UOW, keyBreak, controller, february.id(), "before the period",
                correlation())).isInstanceOfSatisfying(AccountingBreaks.CorrectingEntryInvalid.class, refused ->
                assertThat(refused.summary()).contains("refused=BEFORE_SUBJECT"));

        assertThat(store.rows()).extracting(AccountingBreakStore.BreakRow::status).containsOnly(BreakStatus.OPEN);
        assertThat(store.history()).extracting(AccountingBreakStore.EventRow::act)
                .containsOnly(AccountingBreakStore.Act.RAISED);

        breaks.recordRefusal(UOW, beforeSubject, controller, correlation());
        assertThat(audit).last().satisfies(record -> {
            assertThat(record.operation().code()).isEqualTo("accounting.BreakResolved");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.targetId()).isEqualTo(entryBreak.value().toString());
            assertThat(record.reason()).contains("an earlier entry");
        });
        // The same day as the subject, and any later day, is a correction's.
        JournalReads.Entry sameDay = journal.post(LocalDate.of(2097, 3, 20), "EUR", 2, 100, "FEE_REVENUE",
                "OPERATIONAL", "CUSTOMER_WALLET", "CUSTOMER");
        assertThat(breaks.resolve(UOW, entryBreak, controller, sameDay.id(), "reversed the same day", correlation())
                .status()).isEqualTo(BreakStatus.RESOLVED);
    }

    @Test
    @DisplayName("a repeated proposal carrying another disclosure is refused as stale - never answered as done while"
            + " the earlier text stands (the review's M-6)")
    void aRepeatWithAnotherDisclosureIsStale() {
        AccountingBreakId id = raised();
        breaks.proposeAcknowledgement(UOW, id, accountant, "the first disclosure", correlation());
        assertThatThrownBy(() -> breaks.proposeAcknowledgement(UOW, id, accountant, "a second disclosure",
                correlation())).isInstanceOf(AccountingBreaks.BreakStale.class);
        assertThat(breaks.proposeAcknowledgement(UOW, id, accountant, "the first disclosure", correlation()).replayed())
                .isTrue();
        assertThat(store.find(UOW, id)).hasValueSatisfying(row ->
                assertThat(row.disclosure()).contains("the first disclosure"));
    }

    @Test
    @DisplayName("the reads: a period's breaks, the subjects a run counts, the open ones per type")
    void theReads() {
        AccountingBreakId first = raised();
        raised();
        assertThat(breaks.list(UOW, MARCH, java.util.Optional.empty(), java.util.Optional.empty(), 10)).hasSize(2);
        // Keyset pages: one at a time, the second after the first, none after the last (the review's M-4).
        java.util.List<AccountingBreakStore.BreakRow> firstPage =
                breaks.list(UOW, MARCH, java.util.Optional.empty(), java.util.Optional.empty(), 1);
        java.util.List<AccountingBreakStore.BreakRow> secondPage = breaks.list(UOW, MARCH, java.util.Optional.empty(),
                java.util.Optional.of(firstPage.get(0).id()), 1);
        assertThat(secondPage).singleElement().satisfies(row -> assertThat(row.id()).isNotEqualTo(firstPage.get(0).id()));
        assertThat(breaks.list(UOW, MARCH, java.util.Optional.empty(), java.util.Optional.of(secondPage.get(0).id()), 1))
                .isEmpty();
        assertThat(breaks.held(UOW, MARCH, CloseCheckCode.RULE_CONFORMANCE)).hasSize(2).containsValue(BreakStatus.OPEN);
        assertThat(breaks.held(UOW, MARCH, CloseCheckCode.GL_EQUALS_LEDGER)).isEmpty();
        assertThat(breaks.openByType(UOW)).containsEntry(BreakType.SHAPE_MISMATCH, 2L);
        breaks.resolve(UOW, first, controller, journal.post(LocalDate.of(2097, 4, 2), "EUR", 2, 1, "CUSTOMER_WALLET",
                "CUSTOMER", "FEE_REVENUE", "OPERATIONAL").id(), "corrected", correlation());
        assertThat(breaks.openByType(UOW)).containsEntry(BreakType.SHAPE_MISMATCH, 1L);
        assertThat(breaks.list(UOW, MARCH, java.util.Optional.of(BreakStatus.RESOLVED), java.util.Optional.empty(), 10)).hasSize(1);
    }

    // ------------------------------------------------------------------ fixtures

    private AccountingBreakId raised() {
        return raised(shapeMismatch(UUID.randomUUID()));
    }

    private AccountingBreakId raised(BreakFinding finding) {
        assertThat(breaks.raise(UOW, MARCH, finding, Actor.SYSTEM, correlation())).isTrue();
        return store.rows().get(store.rows().size() - 1).id();
    }

    private static BreakFinding shapeMismatch(UUID entry) {
        return new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH, "shape:" + entry,
                "entry=" + entry + ", type=POSTING, namespace=transfer:", "posting-rules:v2");
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
