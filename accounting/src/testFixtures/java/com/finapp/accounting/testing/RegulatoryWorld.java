package com.finapp.accounting.testing;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryObligationConfiguration;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryRuleType;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The regulatory framework over in-memory stores (`P14-TSK-018`): shared by accounting's suites and app's, so a door's suite and the machine's read one world. Its source definition is {@code TRIAL_BALANCE} by code - a test's body under the code the owed database suite issues - and its
 * form is {@code REF-TEST-RETURN}: a test fixture, naming no real jurisdiction. Test code: it ships in no jar.
 */
public final class RegulatoryWorld {

    public static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
        throw new UnsupportedOperationException("the in-memory stores never touch the connection");
    });
    public static final YearMonth JANUARY = YearMonth.of(2096, 1);
    public static final YearMonth FEBRUARY = YearMonth.of(2096, 2);
    public static final Actor PREPARER = new Actor("preparer-1", ActorType.CUSTOMER);
    public static final Actor APPROVER = new Actor("controller-1", ActorType.CUSTOMER);
    public static final Actor SENDER = new Actor("sender-1", ActorType.CUSTOMER);
    public static final String SOURCE = "TRIAL_BALANCE";
    public static final String FORM = "REF-TEST-RETURN";
    public static final String REASON = "the secret reason: a person's words";

    public final Clock clock;
    public final IdGenerator ids;
    public final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    public final InMemoryReportRunStore runs;
    public final InMemoryRegulatoryObligationStore obligationStore;
    public final InMemoryRegulatorySubmissionStore store;
    public final ScriptedRegulatoryProvider provider = new ScriptedRegulatoryProvider();
    public final List<AuditRecord> audit = Collections.synchronizedList(new ArrayList<>());
    public final List<EventEnvelope> events = Collections.synchronizedList(new ArrayList<>());
    public final List<byte[]> payloads = Collections.synchronizedList(new ArrayList<>());
    public final ReportDefinitions definitions;
    public final ReportRuns reportRuns;
    public final RegulatoryObligations obligations;
    public final RegulatorySubmissions submissions;
    private final OutboxWriter<Connection> outbox;

    public RegulatoryWorld(Clock clock) {
        this.clock = clock;
        this.ids = new IdGenerator(clock, new SecureRandom());
        this.runs = new InMemoryReportRunStore(clock, periods);
        this.obligationStore = new InMemoryRegulatoryObligationStore(clock);
        this.store = new InMemoryRegulatorySubmissionStore(clock, periods, obligationStore);
        this.definitions = ReportDefinitions.of(new TestReportDefinition(SOURCE, 1, Set.of(), false, (snapshot, inputs) -> CanonicalJson.object()));
        this.outbox = (uow, envelope, payload, mediaType) -> {
            events.add(envelope);
            payloads.add(payload);
        };
        this.reportRuns = new ReportRuns(runs, periods, new InMemoryGlMappingStore(), definitions, ReportRuns.Leases.STANDARD, (uow, record) -> { }, outbox, ids, clock);
        RegulatoryAdapters adapters = new RegulatoryAdapters(List.of(provider));
        this.obligations = new RegulatoryObligations(obligationStore, adapters, definitions, (uow, record) -> audit.add(record), ids, clock);
        this.submissions = new RegulatorySubmissions(periods, store, obligationStore, runs, adapters, (uow, record) -> audit.add(record), outbox, ids, clock, Duration.ofDays(1));
    }

    public CorrelationId correlation() {
        return CorrelationId.generate(ids);
    }

    // ------------------------------------------------------------------ the months

    /** {@code month} exists, {@code OPEN}. */
    public AccountingPeriodId open(YearMonth month) {
        periods.insertIfAbsent(UOW, new AccountingPeriodStore.NewPeriod(AccountingPeriodId.next(ids), month, correlation()));
        return row(month).id();
    }

    /** {@code month} exists, {@code CLOSED}, by the fixture's stand-in. */
    public AccountingPeriodId closed(YearMonth month) {
        AccountingPeriodId id = open(month);
        periods.closeForTest(month);
        return id;
    }

    public AccountingPeriodStore.PeriodRow row(YearMonth month) {
        return periods.month(month).orElseThrow();
    }

    /** A reopen's approval as far as a submission can tell: every FINAL run superseded, the revision moved, {@code OPEN}. */
    public void reopened(YearMonth month) {
        AccountingPeriodStore.PeriodRow row = row(month);
        runs.supersedeFinal(UOW, row.id(), correlation());
        if (!periods.markReopened(UOW, row.id(), row.revision())) {
            throw new IllegalStateException("the month was not CLOSED");
        }
    }

    /** {@code OPEN} back to {@code CLOSED} at the same revision, by the machine's store edges: a re-close reads {@code CLOSED} again. */
    public void reclosed(YearMonth month) {
        AccountingPeriodStore.PeriodRow row = row(month);
        if (row.status() != PeriodStatus.OPEN) {
            throw new IllegalStateException("the month is not OPEN");
        }
        periods.markLocked(UOW, row.id(), row.revision(), PREPARER, Optional.empty());
        periods.markCloseProposed(UOW, row.id(), row.revision());
        periods.markClosed(UOW, row.id(), row.revision());
    }

    // ------------------------------------------------------------------ the runs

    /** A FINAL run of {@link #SOURCE} for {@code month} with {@code body}, as a generator would complete it. */
    public ReportRunId issued(YearMonth month, CanonicalJson.Obj body) {
        // Born through the store, as ReportRuns.claim would: only the door and the close's one trial balance claim a run (ReportingNeverWritesTest), and this class is imported beside them.
        AccountingPeriodStore.PeriodRow period = row(month);
        RunBasis basis = RunBasis.of(period.status());
        ReportRunStore.RunKey key = new ReportRunStore.RunKey(SOURCE, 1, period.id(), RunParameters.none().hash());
        Optional<ReportRunStore.RunRow> latest = runs.latest(UOW, key);
        if (latest.filter(held -> held.status() == RunStatus.FINAL && held.periodRevision() == period.revision()).isPresent()) {
            throw new IllegalStateException("the run was already issued");
        }
        int revision = latest.map(held -> held.revision() + 1).orElse(1);
        Optional<AccountingPeriodStore.Pin> pin = basis == RunBasis.FINAL ? period.pinned() : Optional.empty();
        runs.insertIfAbsent(UOW, new ReportRunStore.NewRun(ReportRunId.next(ids), key, revision, month, period.revision(), RunParameters.none(), basis, basis == RunBasis.FINAL ? period.lockedAt() : Optional.empty(),
                pin.map(AccountingPeriodStore.Pin::id), pin.map(AccountingPeriodStore.Pin::version), PREPARER, ReportRuns.Leases.STANDARD.lease(), correlation()));
        ReportRunStore.RunRow run = runs.at(UOW, key, revision).orElseThrow();
        String content = CanonicalJson.text(CanonicalJson.object().put("definition", SOURCE).put("period", month.toString()).put("body", body));
        reportRuns.complete(UOW, run.id(), run.attempt(), new ReportGeneration.Generated(content, CanonicalJson.sha256(content), Optional.empty()), PREPARER);
        return run.id();
    }

    /** The body the standard form draws from: assets and liabilities per currency, equal unless told otherwise. */
    public static CanonicalJson.Obj body(long eurAssets, long eurLiabilities, long usdAssets, long usdLiabilities) {
        return CanonicalJson.object()
                .put("equation", CanonicalJson.array()
                        .add(CanonicalJson.object().put("currency", "EUR").put("assets", money("EUR", eurAssets)).put("liabilities", money("EUR", eurLiabilities)))
                        .add(CanonicalJson.object().put("currency", "USD").put("assets", money("USD", usdAssets)).put("liabilities", money("USD", usdLiabilities))))
                .put("rows", CanonicalJson.array()
                        .add(CanonicalJson.object().put("glAccount", "1000").put("currency", "EUR").put("closing", money("EUR", eurAssets)))
                        .add(CanonicalJson.object().put("glAccount", "1000").put("currency", "EUR").put("closing", money("EUR", 1))));
    }

    public static CanonicalJson.Obj money(String currency, long minor) {
        return CanonicalJson.object().put("currency", currency).put("minor", minor).put("scale", 2);
    }

    // ------------------------------------------------------------------ the obligation

    /** The standard form: assets and liabilities, one line per currency, equal. */
    public static RegulatoryObligationConfiguration configuration() {
        return new RegulatoryObligationConfiguration(
                List.of(new RegulatoryObligationConfiguration.Source(SOURCE, 1)),
                List.of(new RegulatoryObligationConfiguration.Line("ASSETS", "Total assets", SOURCE, "body.equation[currency=*].assets"),
                        new RegulatoryObligationConfiguration.Line("LIABILITIES", "Total liabilities", SOURCE, "body.equation[currency=*].liabilities")),
                List.of(new RegulatoryObligationConfiguration.Rule("BALANCED", RegulatoryRuleType.EQUALS, List.of("ASSETS"), List.of("LIABILITIES"))));
    }

    /** {@code configuration} proposed by one person and activated by another, as form version {@code version}. */
    public RegulatoryObligationId active(int version, RegulatoryObligationConfiguration configuration) {
        RegulatoryObligations.Acted proposed = obligations.propose(UOW, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, FORM, version, ScriptedRegulatoryProvider.NAME, configuration), REASON, PREPARER, correlation());
        obligations.activate(UOW, proposed.id(), proposed.configurationSha256(), REASON, APPROVER, correlation());
        return proposed.id();
    }

    public RegulatoryObligationId active() {
        return active(1, configuration());
    }

    // ------------------------------------------------------------------ a return

    /** A return of {@code month} drafted by {@link #PREPARER}. */
    public RegulatorySubmissions.Acted drafted(RegulatoryObligationId obligation, YearMonth month) {
        return submissions.draft(UOW, new RegulatorySubmissions.Draft(obligation, row(month).id(), Optional.empty(), REASON), PREPARER, correlation());
    }

    /** {@code acted} validated by {@link #PREPARER}. */
    public RegulatorySubmissions.Acted validated(RegulatorySubmissions.Acted acted) {
        return submissions.validate(UOW, acted.id(), PREPARER, correlation());
    }

    /** {@code acted} approved by {@link #APPROVER}, naming what it stands at. */
    public RegulatorySubmissions.Acted approved(RegulatorySubmissions.Acted acted) {
        return submissions.approve(UOW, acted.id(), new RegulatorySubmissions.Attested(acted.contentSha256(), acted.periodRevision()), REASON, APPROVER, correlation());
    }

    /** {@code acted} committed {@code SUBMITTED} by {@link #SENDER}. */
    public RegulatorySubmissions.Acted submitted(RegulatorySubmissions.Acted acted) {
        return submissions.submit(UOW, acted.id(), new RegulatorySubmissions.Attested(acted.contentSha256(), acted.periodRevision()), REASON, SENDER, correlation());
    }

    /** A return of {@code month} all the way to {@code SUBMITTED}, no call made. */
    public RegulatorySubmissions.Acted sent(RegulatoryObligationId obligation, YearMonth month) {
        return submitted(approved(validated(drafted(obligation, month))));
    }
}
