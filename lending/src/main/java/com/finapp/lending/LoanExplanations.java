package com.finapp.lending;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's loan doors (`P11-TSK-029`; PHASE_11_PLAN.md section 9; ADR-0092 points 8-9, ADR-0096 point 3; behind
 * {@code LENDING_INVESTIGATE}): a loan account's explanation from its rows alone, and its replay - each audited, neither
 * writing anything else.
 *
 * <ul>
 *   <li><strong>The explanation</strong> - the account's whole {@link LoanRecord} and its subledger proof's verdict, read in
 *       the caller's {@code REPEATABLE READ} transaction with its {@code lending.ExplanationRead} record appended to it, so
 *       no explanation is served unrecorded.
 *   <li><strong>The replay</strong> - {@link LendingProofs} on the caller's read-only snapshot; the caller then records
 *       {@code lending.LoanReplayed} with the investigator's reason in a transaction of its own before answering.
 * </ul>
 *
 * <p><strong>Never one's own party's loan</strong> - an investigator whose party holds the loan, or whom the platform
 * cannot place in a party, is refused ({@link RepaymentReversals.SelfDealingRefused}) before anything is read; the caller
 * records the refusal {@code FAILED} in a transaction of its own. An unknown loan is {@link LoanNotFound}. The record
 * carries amounts: it is served, never logged ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public final class LoanExplanations {

    @NonNull private final LoanRecordStore records;
    @NonNull private final LendingProofs proofs;
    @NonNull private final LoanSubledgerProof subledger;
    @NonNull private final LoanStore loans;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** A loan explained: its whole record and its subledger proof's verdict. */
    public record Explanation(LoanRecord record, LoanSubledgerProof.Verdict subledger) {

        public Explanation {
            Objects.requireNonNull(record, "record");
            Objects.requireNonNull(subledger, "subledger");
        }

        @Override
        public String toString() {
            return "Explanation[" + record + ", " + subledger + "]";
        }
    }

    /**
     * {@code loan} explained to {@code actor}, on the caller's {@code REPEATABLE READ} transaction, its audit record appended
     * to it.
     *
     * @throws RepaymentReversals.SelfDealingRefused the loan is the investigator's own party's
     * @throws LoanNotFound no such loan
     */
    public Explanation explain(Connection unitOfWork, LoanId loan, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, "explanation", LoanNotFound::new);
        LoanRecord record = records.read(unitOfWork, loan).orElseThrow(LoanNotFound::new);
        LoanSubledgerProof.Verdict verdict = subledger.prove(record);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.EXPLANATION_READ, "loan", loan.value().toString(), Optional.empty(),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("loan=" + loan.value() + ", agreements="
                        + record.agreements().size() + ", schedules=" + record.schedules().size() + ", accruals="
                        + record.accruals().size() + ", billings=" + record.billings().size() + ", statements="
                        + record.statements().size() + ", repayments=" + record.repayments().size() + ", entries="
                        + record.journalLines().stream().map(line -> line.entry()).distinct().count() + ", subledger="
                        + (verdict.holds() ? "HOLDS" : "FAILS"))));
        return new Explanation(record, verdict);
    }

    /**
     * {@code loan} replayed for {@code actor} on the caller's read-only {@code REPEATABLE READ} snapshot - nothing recorded
     * here: {@link #recordReplay} records it, in a transaction of its own.
     *
     * @throws RepaymentReversals.SelfDealingRefused the loan is the investigator's own party's
     * @throws LoanNotFound no such loan
     */
    public LoanReplayProof.Replay replay(Connection snapshot, LoanId loan, Actor actor) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(actor, "actor");
        LoanSelfDealing.judge(loans, actingParties, snapshot, loan, actor, "replay", LoanNotFound::new);
        return proofs.prove(snapshot, loan).orElseThrow(LoanNotFound::new).replay();
    }

    /** Records {@code replay} - its verdict and the kinds that diverged, never a figure - with the investigator's reason. */
    public void recordReplay(Connection unitOfWork, LoanReplayProof.Replay replay, String reason, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(replay, "replay");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.LOAN_REPLAYED, "loan", replay.loan().value().toString(),
                Optional.of(LendingCapitalAdministration.reasoned(reason)), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("loan=" + replay.loan().value() + ", verdict=" + replay.verdict().name() + ", replayed="
                        + replay.replayed() + ", diverged=" + replay.checks())));
    }

    /** The investigator's reason, judged before anything is read: present, bounded, never an instrument's shape. */
    public static String reasoned(String reason) {
        return LendingCapitalAdministration.reasoned(reason);
    }

    /**
     * Records a refused self-dealing investigation {@code FAILED} ({@link LendingAuditAction#SELF_DEALING_REFUSED}) on
     * {@code unitOfWork} - the caller's own transaction, after the read was abandoned, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, RepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(refused, "refused");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(),
                LendingAuditAction.SELF_DEALING_REFUSED, "loan", refused.loan().value().toString(), Optional.empty(),
                AuditOutcome.FAILED, correlation, Optional.of("loan=" + refused.loan().value() + ", act=investigation:"
                        + refused.act() + " refused - the loan is the investigator's own party's")));
    }

    /** No such loan - the uniform {@code 404}. */
    public static final class LoanNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        LoanNotFound() {
            super("no such loan");
        }
    }
}
