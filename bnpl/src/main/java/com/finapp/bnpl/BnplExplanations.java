package com.finapp.bnpl;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's BNPL agreement doors (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 9, behind
 * {@code BNPL_INVESTIGATE}; lending's {@code LoanExplanations}): the agreement read, its explanation from its rows alone, and
 * its replay - each audited, none writing anything else.
 *
 * <ul>
 *   <li><strong>The read and the explanation</strong> - the agreement's whole {@link BnplRecord} and its subledger proof's
 *       verdict, read in the caller's {@code REPEATABLE READ} transaction with its {@code bnpl.AgreementRead} or
 *       {@code bnpl.ExplanationRead} record appended to it, so nothing is served unrecorded.
 *   <li><strong>The replay</strong> - {@link BnplProofs} on the caller's read-only snapshot; the caller then records
 *       {@code bnpl.Replayed} with the investigator's reason in a transaction of its own before answering.
 * </ul>
 *
 * <p><strong>Never one's own party's agreement</strong> - unlike lending's operator read (Phase 11 owner item #18, not
 * repeated): an investigator whose party holds the agreement, or whom the platform cannot place in a party, is refused
 * ({@link BnplRepaymentReversals.SelfDealingRefused}, fail closed) before anything is read; the caller records the refusal
 * {@code FAILED} in a transaction of its own. An unknown agreement is {@link AgreementNotFound}, the uniform {@code 404}. The
 * record carries amounts: it is served, never logged ({@code INV-AUD-02}). Holds no state.
 */
@RequiredArgsConstructor
public final class BnplExplanations {

    /** The audit target type of every investigation. */
    public static final String TARGET_TYPE = "bnpl_agreement";

    @NonNull private final BnplCorrectionStore addressing;
    @NonNull private final BnplActingParty actingParties;
    @NonNull private final BnplRecordStore records;
    @NonNull private final BnplProofs proofs;
    @NonNull private final BnplSubledgerProof subledger;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** An agreement explained: its whole record and its subledger proof's verdict. */
    public record Explanation(BnplRecord record, BnplSubledgerProof.Verdict subledger) {

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
     * {@code agreement} read for {@code actor}, on the caller's {@code REPEATABLE READ} transaction, {@code bnpl.AgreementRead}
     * appended to it.
     *
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the investigator's own party's
     * @throws AgreementNotFound no such agreement
     */
    public Explanation read(Connection unitOfWork, BnplAgreementId agreement, Actor actor, CorrelationId correlation) {
        Explanation explanation = explained(unitOfWork, agreement, actor, "read");
        record(unitOfWork, BnplAuditAction.AGREEMENT_READ, explanation, Optional.empty(), actor, correlation);
        return explanation;
    }

    /**
     * {@code agreement} explained to {@code actor}, on the caller's {@code REPEATABLE READ} transaction,
     * {@code bnpl.ExplanationRead} appended to it.
     *
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the investigator's own party's
     * @throws AgreementNotFound no such agreement
     */
    public Explanation explain(Connection unitOfWork, BnplAgreementId agreement, Actor actor, CorrelationId correlation) {
        Explanation explanation = explained(unitOfWork, agreement, actor, "explanation");
        record(unitOfWork, BnplAuditAction.EXPLANATION_READ, explanation, Optional.empty(), actor, correlation);
        return explanation;
    }

    /**
     * {@code agreement} replayed for {@code actor} on the caller's read-only {@code REPEATABLE READ} snapshot - nothing
     * recorded here: {@link #recordReplay} records it, in a transaction of its own.
     *
     * @throws BnplRepaymentReversals.SelfDealingRefused the agreement is the investigator's own party's
     * @throws AgreementNotFound no such agreement
     */
    public BnplReplayProof.Replay replay(Connection snapshot, BnplAgreementId agreement, Actor actor) {
        Objects.requireNonNull(snapshot, "snapshot");
        BnplCorrectionStore.Addressed addressed = judged(snapshot, agreement, actor, "replay");
        return proofs.prove(snapshot, addressed.obligation()).orElseThrow(AgreementNotFound::new).replay();
    }

    /** Records {@code replay} - its verdict and the kinds that diverged, never a figure - with the investigator's reason. */
    public void recordReplay(Connection unitOfWork, BnplAgreementId agreement, BnplReplayProof.Replay replay, String reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(replay, "replay");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.REPLAYED,
                TARGET_TYPE, agreement.value().toString(), Optional.of(BnplReasons.reasoned(reason)),
                AuditOutcome.SUCCEEDED, correlation, Optional.of("agreement=" + agreement.value() + ", obligation="
                        + replay.obligation().value() + ", verdict=" + replay.verdict().name() + ", replayed="
                        + replay.replayed() + ", diverged=" + replay.checks())));
    }

    /** The investigator's reason, judged before anything is read: present, bounded, never an instrument's shape. */
    public static String reasoned(String reason) {
        return BnplReasons.reasoned(reason);
    }

    /**
     * Records a refused self-dealing investigation {@code FAILED} on {@code unitOfWork} - the caller's own transaction,
     * after the read was abandoned, so the attempt is kept.
     */
    public void recordRefusal(Connection unitOfWork, BnplRepaymentReversals.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        BnplRepaymentReversals.record(audit, ids, clock, unitOfWork, refused, actor, correlation);
    }

    // ------------------------------------------------------------------ plumbing

    private Explanation explained(Connection unitOfWork, BnplAgreementId agreement, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        BnplCorrectionStore.Addressed addressed = judged(unitOfWork, agreement, actor, act);
        BnplRecord record = records.read(unitOfWork, addressed.obligation()).orElseThrow(AgreementNotFound::new);
        return new Explanation(record, subledger.prove(record));
    }

    /** The agreement addressed, and an investigator of its own party - or of none the platform knows - refused. */
    private BnplCorrectionStore.Addressed judged(Connection unitOfWork, BnplAgreementId agreement, Actor actor,
            String act) {
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(actor, "actor");
        try {
            return BnplRepaymentReversals.judgeSelfDealing(addressing, actingParties, unitOfWork, agreement, actor,
                    "investigation:" + act);
        } catch (BnplRepaymentReversals.NotFound unknown) {
            throw new AgreementNotFound();
        }
    }

    private void record(Connection unitOfWork, BnplAuditAction action, Explanation explanation, Optional<String> reason,
            Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(correlation, "correlation");
        BnplRecord record = explanation.record();
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, TARGET_TYPE,
                record.obligation().agreement().value().toString(), reason, AuditOutcome.SUCCEEDED, correlation,
                Optional.of("agreement=" + record.obligation().agreement().value() + ", obligation="
                        + record.id().value() + ", versions=" + record.agreements().size() + ", schedules="
                        + record.schedules().size() + ", billings=" + record.billings().size() + ", repayments="
                        + record.repayments().size() + ", refunds=" + record.refunds().size() + ", entries="
                        + record.journalLines().stream().map(line -> line.entry()).distinct().count() + ", subledger="
                        + (explanation.subledger().holds() ? "HOLDS" : "FAILS"))));
    }

    /** No such agreement - the uniform {@code 404}. */
    public static final class AgreementNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        AgreementNotFound() {
            super("no such BNPL agreement");
        }
    }
}
