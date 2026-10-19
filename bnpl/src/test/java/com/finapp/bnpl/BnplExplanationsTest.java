package com.finapp.bnpl;

import static com.finapp.bnpl.BnplConditionWorld.DUE_2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The investigator's agreement doors (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 9; behind {@code BNPL_INVESTIGATE}):
 * the read, the explanation from rows alone and the replay - each audited ({@code bnpl.AgreementRead},
 * {@code bnpl.ExplanationRead}, {@code bnpl.Replayed}), never of the investigator's own party's agreement nor by one the
 * platform cannot place (self-dealing refused, fail closed), an unknown agreement the uniform 404. The record carries
 * amounts; no audit summary does.
 */
@DisplayName("the investigator's BNPL agreement doors: read, explanation, replay - audited, never one's own (P12-TSK-025)")
class BnplExplanationsTest {

    private final BnplProofWorld world = new BnplProofWorld();
    private final BnplExplanations explanations = new BnplExplanations(world.fakes.corrections,
            world.corrections.actingParties, world.records, world.proofs, new BnplSubledgerProof(), world.fakes.audit,
            BnplConditionWorld.IDS, BnplConditionWorld.CLOCK);

    @Test
    @DisplayName("the explanation is the agreement's whole record from its rows - every entry named - and its subledger"
            + " verdict, recorded bnpl.ExplanationRead with counts and never an amount")
    void theExplanationIsCompleteFromRows() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        world.stepOn(DUE_2, p4);
        world.repayDueOn(DUE_2, p4, "r2");
        world.base.refundOn(DUE_2.plusDays(1), p4, "refund-1", 150_00);
        BnplExplanations.Explanation explanation = explanations.explain(BnplProofWorld.SNAPSHOT, p4.agreement(),
                world.corrections.alice, BnplConditionWorld.CORRELATION);
        BnplRecord record = explanation.record();
        assertThat(record.agreements()).isNotEmpty();
        assertThat(record.billings()).isNotEmpty();
        assertThat(record.repayments()).hasSizeGreaterThan(1);
        assertThat(record.refunds()).hasSize(1);
        assertThat(explanation.subledger().holds()).isTrue();
        assertThat(BnplSubledgerProof.namedEntries(record).keySet()).as("every entry on the obligation's accounts is named")
                .containsAll(record.journalLines().stream().map(line -> line.entry()).toList());
        AuditRecord audited = last();
        assertThat(audited.operation()).isEqualTo(BnplAuditAction.EXPLANATION_READ);
        assertThat(audited.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(audited.targetId()).isEqualTo(p4.agreement().value().toString());
        // Whole tokens only: the summary carries UUIDv7 ids, whose hex may hold any digits.
        assertThat(audited.changeSummary().orElseThrow()).contains("subledger=HOLDS")
                .doesNotContainPattern("(?<![0-9a-f])15000(?![0-9a-f])").doesNotContain("150.00");
    }

    @Test
    @DisplayName("the read is audited bnpl.AgreementRead; the replay answers IDENTICAL and is recorded bnpl.Replayed with"
            + " its reason, the verdict and the kinds only")
    void theReadAndTheReplayAreAudited() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        explanations.read(BnplProofWorld.SNAPSHOT, p4.agreement(), world.corrections.alice,
                BnplConditionWorld.CORRELATION);
        assertThat(last().operation()).isEqualTo(BnplAuditAction.AGREEMENT_READ);
        BnplReplayProof.Replay replay = explanations.replay(BnplProofWorld.SNAPSHOT, p4.agreement(),
                world.corrections.alice);
        assertThat(replay.verdict()).isEqualTo(BnplReplayProof.Verdict.IDENTICAL);
        explanations.recordReplay(BnplProofWorld.SNAPSHOT, p4.agreement(), replay, "quarterly check",
                world.corrections.alice, BnplConditionWorld.CORRELATION);
        AuditRecord audited = last();
        assertThat(audited.operation()).isEqualTo(BnplAuditAction.REPLAYED);
        assertThat(audited.reason()).contains("quarterly check");
        assertThat(audited.changeSummary().orElseThrow()).contains("verdict=IDENTICAL");
        assertThatThrownBy(() -> BnplExplanations.reasoned(" ")).isInstanceOf(BnplReasons.ReasonRequired.class);
    }

    @Test
    @DisplayName("an investigator of the agreement's own party, and one the platform cannot place, are refused before"
            + " anything is read - each refusal recorded FAILED")
    void selfDealingIsRefusedAndRecorded() {
        BnplConditionWorld.Agreement p4 = world.base.payIn4();
        Actor stranger = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
        for (Actor refused : new Actor[] {world.corrections.insider, stranger}) {
            assertThatThrownBy(() -> explanations.explain(BnplProofWorld.SNAPSHOT, p4.agreement(), refused,
                    BnplConditionWorld.CORRELATION))
                    .isInstanceOfSatisfying(BnplRepaymentReversals.SelfDealingRefused.class, refusal ->
                            explanations.recordRefusal(BnplProofWorld.SNAPSHOT, refusal, refused,
                                    BnplConditionWorld.CORRELATION));
            AuditRecord audited = last();
            assertThat(audited.operation()).isEqualTo(BnplAuditAction.SELF_DEALING_REFUSED);
            assertThat(audited.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(audited.changeSummary().orElseThrow()).contains("investigation:explanation");
        }
        assertThatThrownBy(() -> explanations.replay(BnplProofWorld.SNAPSHOT, p4.agreement(), world.corrections.insider))
                .isInstanceOf(BnplRepaymentReversals.SelfDealingRefused.class);
        assertThat(world.fakes.audited).noneMatch(record -> record.operation() == BnplAuditAction.EXPLANATION_READ);
    }

    @Test
    @DisplayName("an unknown agreement is the uniform AgreementNotFound - nothing audited as served")
    void anUnknownAgreementIsNotFound() {
        world.base.payIn4();
        BnplAgreementId unknown = BnplAgreementId.of(BnplConditionWorld.IDS.next());
        assertThatThrownBy(() -> explanations.explain(BnplProofWorld.SNAPSHOT, unknown, world.corrections.alice,
                BnplConditionWorld.CORRELATION)).isInstanceOf(BnplExplanations.AgreementNotFound.class);
        assertThatThrownBy(() -> explanations.replay(BnplProofWorld.SNAPSHOT, unknown, world.corrections.alice))
                .isInstanceOf(BnplExplanations.AgreementNotFound.class);
        assertThat(world.fakes.audited).noneMatch(record -> record.operation() == BnplAuditAction.EXPLANATION_READ);
    }

    @Test
    @DisplayName("the proofs read only a read-only REPEATABLE READ snapshot - any other connection is refused")
    void theProofsRefuseAWritableConnection() {
        world.base.payIn4();
        assertThatThrownBy(() -> world.proofs.prove(BnplApplicationFakes.UOW))
                .isInstanceOf(UnsupportedOperationException.class);
        java.sql.Connection writable = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) ->
                        switch (method.getName()) {
                            case "isReadOnly" -> false;
                            case "getAutoCommit" -> false;
                            case "getTransactionIsolation" -> java.sql.Connection.TRANSACTION_REPEATABLE_READ;
                            default -> throw new UnsupportedOperationException();
                        });
        assertThatThrownBy(() -> world.proofs.prove(writable)).isInstanceOf(IllegalStateException.class);
    }

    private AuditRecord last() {
        return world.fakes.audited.get(world.fakes.audited.size() - 1);
    }
}
