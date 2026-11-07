package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The manual adjustment door's two gaps the Phase 14 exit review closed ({@code P14-DOC-001}, routed by `P14-TSK-026`'s
 * fix round; {@code INV-AUD-04}, {@code INV-AUD-02}, {@code INV-REV-04}), without a database: <strong>a refused
 * self-approval is recorded</strong> - {@link AdjustmentService#recordRefusal} appends {@code ledger.SelfApprovalRefused}
 * {@code FAILED}, naming the proposal and the act and never an amount, for the caller to write in a transaction of its own
 * after the refused act rolled back (`LedgerAdjustmentsRefusalTest` holds that order) - and <strong>a reason or a reference holding
 * an instrument shape, or a blank reason, is unconstructible</strong>, typed so the door answers {@code 422}.
 */
@DisplayName("the manual adjustment door records a refused self-approval and screens its texts (P14-DOC-001)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class ManualAdjustmentDoorTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 7);
    private static final Actor INITIATOR = new Actor("adjust-initiator", ActorType.EMPLOYEE);

    private final List<JournalEntry> appended = new ArrayList<>();
    private final List<AdjustmentProposalStatus> decisions = new ArrayList<>();
    private final List<AuditRecord> audited = new ArrayList<>();

    @Test
    @DisplayName("a blank reason (an ideographic space included), and a reason or a reference holding a card number or an"
            + " IBAN, are refused at the command - an IllegalArgumentException, typed, the text never echoed; a plain one"
            + " constructs")
    void theScreenRefusesAtTheCommand() {
        for (String reason : List.of("　", "　 ", "card 4111 1111 1111 1111 posted twice",
                "IBAN GB82 WEST 1234 5698 7654 32 in the memo")) {
            assertThatThrownBy(() -> command("ticket-77", reason))
                    .as("reason %s", reason.isBlank() ? "(blank)" : reason)
                    .isInstanceOf(IllegalArgumentException.class)
                    .isInstanceOfSatisfying(AdjustmentReasonRefusedException.class, refused -> assertThat(
                            refused.getMessage()).contains("reason").doesNotContain("4111").doesNotContain("GB82"));
        }
        for (String reference : List.of("card 4111 1111 1111 1111", "GB82 WEST 1234 5698 7654 32")) {
            assertThatThrownBy(() -> command(reference, "a duplicated fee reversed"))
                    .isInstanceOfSatisfying(AdjustmentReasonRefusedException.class, refused -> assertThat(
                            refused.getMessage()).contains("reference").doesNotContain("4111").doesNotContain("GB82"));
        }
        assertThatCode(() -> command("ticket-77", "a duplicated fee reversed")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the initiator's own approval is refused writing nothing; recordRefusal then appends one"
            + " ledger.SelfApprovalRefused FAILED - the one who tried the actor, the proposal the target, the proposal and the"
            + " act in the summary, no reason and no amount")
    void aRefusedSelfApprovalIsRecordedFailed() {
        AdjustmentProposal own = AdjustmentProposal.propose(AdjustmentProposalId.next(IDS), DAY, DAY, "ticket-77",
                "a duplicated fee reversed", INITIATOR.id(), lines(), CLOCK);
        AdjustmentService service = service(own);

        SelfApprovalRefusedException refused = as(INITIATOR, () -> {
            try {
                service.approve(NO_CONNECTION, own.id());
                throw new AssertionError("the initiator's approval was not refused");
            } catch (SelfApprovalRefusedException self) {
                return self;
            }
        });
        assertThat(appended).isEmpty();
        assertThat(decisions).isEmpty();
        assertThat(audited).as("the refused act itself writes nothing; the record is the caller's next transaction").isEmpty();

        as(INITIATOR, () -> {
            service.recordRefusal(NO_CONNECTION, refused);
            return null;
        });

        assertThat(audited).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(LedgerAuditAction.SELF_APPROVAL_REFUSED);
            assertThat(record.operation().code()).isEqualTo("ledger.SelfApprovalRefused");
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.actor()).isEqualTo(INITIATOR);
            assertThat(record.targetType()).isEqualTo("adjustment_proposal");
            assertThat(record.targetId()).isEqualTo(own.id().value().toString());
            assertThat(record.reason()).isEmpty();
            assertThat(record.changeSummary().orElseThrow())
                    .contains(own.id().toString())
                    .contains("act=adjustment:approve")
                    .doesNotContain("15.00")
                    .doesNotContain("duplicated fee");
        });
        assertThat(appended).isEmpty();
        assertThat(decisions).isEmpty();
    }

    // ------------------------------------------------------------------

    private static final Connection NO_CONNECTION = (Connection) Proxy.newProxyInstance(
            ManualAdjustmentDoorTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, call, args) -> {
                throw new AssertionError("the stores are in memory: Connection." + call.getName());
            });

    private static AdjustmentCommand command(String reference, String reason) {
        return new AdjustmentCommand("key-1", DAY, DAY, reference, reason, lines());
    }

    private static <T> T as(Actor actor, java.util.function.Supplier<T> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    @SuppressWarnings("unchecked")
    private AdjustmentService service(AdjustmentProposal proposal) {
        AdjustmentProposalStore<Connection> proposals = (AdjustmentProposalStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AdjustmentProposalStore.class}, (proxy, call, args) ->
                        switch (call.getName()) {
                            case "lockById" -> Optional.of(proposal);
                            case "decide" -> {
                                decisions.add((AdjustmentProposalStatus) args[2]);
                                yield true;
                            }
                            default -> throw new AssertionError("AdjustmentProposalStore." + call.getName());
                        });
        LedgerAccountStore<Connection> accounts = (LedgerAccountStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {LedgerAccountStore.class}, (proxy, call, args) -> {
                    throw new AssertionError("LedgerAccountStore." + call.getName());
                });
        IdempotencyRecordStore<Connection> claims = (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {IdempotencyRecordStore.class}, (proxy, call, args) -> {
                    throw new AssertionError("an approval carries no key: " + call.getName());
                });
        return new AdjustmentService(
                new IdempotentExecutor(claims, CLOCK, Duration.ofDays(1), Duration.ofMinutes(5)),
                recording(JournalEntryStore.class, "append", args -> appended.add((JournalEntry) args[1])),
                proposals,
                recording(AuditWriter.class, "append", args -> audited.add((AuditRecord) args[1])),
                recording(OutboxWriter.class, "write", args -> { }),
                recording(BalanceProjection.class, "apply", args -> { }),
                IDS,
                CLOCK,
                (outcome, elapsed) -> { },
                accounts);
    }

    @SuppressWarnings("unchecked")
    private static <T> T recording(Class<?> type, String method, java.util.function.Consumer<Object[]> record) {
        return (T) Proxy.newProxyInstance(ManualAdjustmentDoorTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, call, args) -> {
                    if (!call.getName().equals(method)) {
                        throw new AssertionError(type.getSimpleName() + "." + call.getName());
                    }
                    record.accept(args);
                    return null;
                });
    }

    private static List<JournalLine> lines() {
        return List.of(new JournalLine(LedgerAccountId.next(IDS), Direction.DEBIT, Money.ofMinorUnits(1500, USD)),
                new JournalLine(LedgerAccountId.next(IDS), Direction.CREDIT, Money.ofMinorUnits(1500, USD)));
    }
}
