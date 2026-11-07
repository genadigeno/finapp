package com.finapp.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AdjustmentProposal;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentProposalStatus;
import com.finapp.ledger.AdjustmentProposalStore;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.BalanceProjection;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SelfApprovalRefusedException;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manual adjustment door's two refusals at the slice ({@code P14-DOC-001}, the Phase 14 exit review, routed by
 * `P14-TSK-026`'s fix round; {@code INV-AUD-04}, {@code INV-AUD-02}): <strong>a refused self-approval is recorded
 * {@code FAILED} in a transaction of its own, after the act's rolled back</strong>, and then refused as it always was
 * (`ApiErrorHandler` maps {@link SelfApprovalRefusedException} to its {@code 409}) - the projection rebuild's shape,
 * `ProjectionRebuildDeskTest`'s; and <strong>a reason or reference the command's screen refuses is the request's
 * {@code 422}</strong>, never the catch-all's {@code 500}, the text never echoed, no transaction begun.
 */
@DisplayName("the manual adjustment door records a refused self-approval in its own transaction and answers a refused"
        + " text 422 (P14-DOC-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class LedgerAdjustmentsRefusalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    private static final Actor INITIATOR = new Actor("ops-1", ActorType.EMPLOYEE);

    private final List<String> trail = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<AdjustmentProposal> inserted = new ArrayList<>();
    private final List<AdjustmentProposalStatus> decisions = new ArrayList<>();

    @Test
    @DisplayName("the initiator's own approval: the act's transaction rolls back, a second begins, records"
            + " ledger.SelfApprovalRefused FAILED - the one who tried the actor, the proposal the target - and commits; the"
            + " refusal then propagates to the 409, nothing decided")
    void aRefusedSelfApprovalIsRecordedInItsOwnTransaction() {
        AdjustmentProposal own = AdjustmentProposal.propose(AdjustmentProposalId.next(IDS), DAY, DAY, "ticket-77",
                "a duplicated fee reversed", INITIATOR.id(), lines(), CLOCK);

        assertThatThrownBy(() -> as(INITIATOR, () -> desk(own).approve(own.id().value().toString())))
                .isInstanceOf(SelfApprovalRefusedException.class);

        assertThat(trail).as("the act's transaction rolled back; the record's own began after it and committed")
                .containsExactly("begin", "rollback", "begin", "audit ledger.SelfApprovalRefused FAILED", "commit");
        assertThat(audits).singleElement().satisfies(record -> {
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.actor()).isEqualTo(INITIATOR);
            assertThat(record.targetType()).isEqualTo("adjustment_proposal");
            assertThat(record.targetId()).isEqualTo(own.id().value().toString());
            assertThat(record.changeSummary().orElseThrow()).contains("act=adjustment:approve").doesNotContain("15.00");
        });
        assertThat(decisions).as("nothing decided: the proposal stays PROPOSED for a second person").isEmpty();
    }

    @Test
    @DisplayName("a reason the body's @NotBlank lets through - ideographic whitespace only - and a reason or reference"
            + " holding a card number or an IBAN are the request's 422 VALIDATION_FAILED, the text never echoed, no"
            + " transaction begun, nothing proposed")
    void aRefusedTextIs422() {
        List<AdjustmentRequest> bodies = List.of(
                request("ticket-77", "　"),
                request("ticket-77", "card 4111 1111 1111 1111 posted twice"),
                request("ticket-77", "IBAN GB82 WEST 1234 5698 7654 32 in the memo"),
                request("GB82 WEST 1234 5698 7654 32", "a duplicated fee reversed"));
        int key = 0;
        for (AdjustmentRequest body : bodies) {
            String idempotencyKey = "k-" + key++;
            assertThatThrownBy(() -> as(INITIATOR, () -> desk(null).propose(body, idempotencyKey)))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.VALIDATION_FAILED);
                        assertThat(refused.errorCode().status()).isEqualTo(422);
                        assertThat(refused.clientDetail().orElseThrow()).doesNotContain("4111").doesNotContain("GB82");
                        assertThat(refused.getMessage()).doesNotContain("4111").doesNotContain("GB82");
                    });
        }
        assertThat(trail).as("refused before the transaction opens").isEmpty();
        assertThat(inserted).isEmpty();
        assertThat(audits).isEmpty();
    }

    // ------------------------------------------------------------------

    private static AdjustmentRequest request(String reference, String reason) {
        String account = LedgerAccountId.next(IDS).value().toString();
        String other = LedgerAccountId.next(IDS).value().toString();
        return new AdjustmentRequest(DAY, DAY, reference, reason, List.of(
                new AdjustmentRequest.Line(account, Direction.DEBIT, "15.00", "USD"),
                new AdjustmentRequest.Line(other, Direction.CREDIT, "15.00", "USD")));
    }

    private static <T> T as(Actor actor, Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    @SuppressWarnings("unchecked")
    private LedgerAdjustments desk(AdjustmentProposal held) {
        AdjustmentProposalStore<Connection> proposals = (AdjustmentProposalStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AdjustmentProposalStore.class}, (proxy, call, args) ->
                        switch (call.getName()) {
                            case "lockById" -> Optional.ofNullable(held);
                            case "insert" -> {
                                inserted.add((AdjustmentProposal) args[1]);
                                yield null;
                            }
                            case "decide" -> {
                                decisions.add((AdjustmentProposalStatus) args[2]);
                                yield true;
                            }
                            default -> throw new AssertionError("AdjustmentProposalStore." + call.getName());
                        });
        AdjustmentService service = new AdjustmentService(
                new IdempotentExecutor(refusing(IdempotencyRecordStore.class), CLOCK, Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                refusing(JournalEntryStore.class),
                proposals,
                (uow, record) -> {
                    audits.add(record);
                    trail.add("audit " + record.operation().code() + " " + record.outcome());
                },
                refusing(OutboxWriter.class),
                refusing(BalanceProjection.class),
                IDS,
                CLOCK,
                (outcome, elapsed) -> { },
                refusing(LedgerAccountStore.class));
        return new LedgerAdjustments(service, NoDatabase.transactions(trail), NoDatabase.oneConnection());
    }

    @SuppressWarnings("unchecked")
    private static <T> T refusing(Class<?> type) {
        return (T) Proxy.newProxyInstance(LedgerAdjustmentsRefusalTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, call, args) -> {
                    throw new AssertionError("not reached on a refused act: " + type.getSimpleName() + "." + call.getName());
                });
    }

    private static List<JournalLine> lines() {
        return List.of(new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD)));
    }
}
