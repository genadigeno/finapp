package com.finapp.accounting;

import com.finapp.accounting.reporting.ReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.ReportInputs;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * An operational report, answered and never stored (`P14-TSK-017`; ADR-0115 As built; {@code INV-ACC-04}, {@code
 * INV-ACC-14}): the machine's half of the door an {@code OperationalReportDefinition} is read at. A report that reads what keeps
 * moving after a lock can be neither a FINAL run (it would not regenerate, and would answer forever) nor a PROVISIONAL one of a
 * locked period ({@code accounting V004} refuses it for every writer), so it is no run at all: generated on the caller's verified
 * read-only snapshot by the same {@link ReportGeneration} - the same canonical form, hash and time budget - and handed back.
 *
 * <p><strong>The instant it is as of</strong> is the database's, read first on that snapshot; the definition measures every age
 * from it and has no clock of its own. <strong>The read is recorded</strong> ({@link #recordRead}) in a transaction of its own
 * before the answer is served. Nothing else is written. Ten instances, ten snapshots, no shared state.
 */
@RequiredArgsConstructor
public final class OperationalReports {

    /** The audit record's target type: the question is a period's, and no run exists to name. */
    public static final String TARGET_TYPE = "accounting_period";

    /** The database's instant. */
    static final String AS_OF_SQL = "SELECT clock_timestamp()";

    @NonNull private final ReportDefinitions definitions;
    @NonNull private final AccountingPeriodStore periods;
    @NonNull private final ReportGeneration generation;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The definition is a run's, not this door's. */
    public static final class NotOperational extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NotOperational() {
            super("this report definition is asked as a report run; it is not read at the operational door");
        }
    }

    /** An answer: what was asked, the instant it is as of, and the content with its sha256. */
    public record Answer(String definition, int version, AccountingPeriodId periodId, YearMonth month, Instant asOf,
            ReportGeneration.Generated generated) {

        public Answer {
            Objects.requireNonNull(definition, "definition");
            Objects.requireNonNull(periodId, "periodId");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(generated, "generated");
        }
    }

    /**
     * {@code code}'s answer for the period, on {@code snapshot} - a read-only {@code REPEATABLE READ} transaction the caller
     * opened and rolls back.
     *
     * @throws ReportRuns.DefinitionUnknown when the build holds no definition of {@code code}
     * @throws NotOperational when the definition is a run's
     * @throws AccountingPeriods.PeriodNotFound when no period has this id
     */
    public Answer answer(Connection snapshot, String code, AccountingPeriodId id) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(id, "id");
        ReportDefinition definition = definitions.current(code).orElseThrow(ReportRuns.DefinitionUnknown::new);
        if (!definition.operational()) {
            throw new NotOperational();
        }
        AccountingPeriodStore.PeriodRow period = periods.find(snapshot, id).orElseThrow(AccountingPeriods.PeriodNotFound::new);
        Instant asOf = databaseInstant(snapshot);
        // PROVISIONAL inputs: no lock to re-check, no pin, the cut the instant. The content's basis says OPERATIONAL.
        ReportInputs inputs = new ReportInputs(id, period.month(), period.revision(), RunBasis.PROVISIONAL, Optional.empty(),
                Optional.empty(), Optional.empty(), asOf, RunParameters.none());
        return new Answer(definition.code(), definition.version(), id, period.month(), asOf,
                generation.generate(snapshot, definition, inputs));
    }

    /** Records the read ({@code accounting.OperationalReportRead}) - before it is served. Never the content, never a figure. */
    public void recordRead(Connection unitOfWork, Answer answer, Actor actor, CorrelationId correlation) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                AccountingAuditAction.OPERATIONAL_REPORT_READ, TARGET_TYPE, answer.periodId().value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("definition=" + answer.definition() + ", version=" + answer.version() + ", period="
                        + answer.month() + ", contentSha256=" + answer.generated().sha256())));
    }

    private static Instant databaseInstant(Connection snapshot) {
        try (PreparedStatement read = snapshot.prepareStatement(AS_OF_SQL); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading the database's instant", failure));
        }
    }
}
