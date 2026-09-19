package com.finapp.transfers;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two seams (`P4-TSK-010`): the Phase 4 implementation permits everything, and the seams'
 * <strong>size</strong> is held as an assertion — "no Phase 13 logic anywhere, verified by the
 * seams' size" is the backlog's own accept clause, mechanised so a behaviour leaking early is a
 * failing test rather than a review observation. The in-lock contract is the database suite's
 * to assert ({@code TransferSeamDatabaseTest}'s decorator probe); what is hermetic here is the
 * vocabulary and the emptiness.
 */
@DisplayName("the limit and risk seams (P4-TSK-010)")
class TransferSeamsTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("PermitAllUntilPhase13 permits through both ports, ignoring the unit of work")
    void theDefaultPermitsEverything() {
        Transfer transfer =
                Transfer.initiate(
                        IDS,
                        CLOCK,
                        IDS.next(),
                        LedgerAccountId.next(IDS),
                        LedgerAccountId.next(IDS),
                        Money.ofMinorUnits(12_50, CurrencyCode.of("EUR")),
                        null,
                        IDS.next());
        // A null unit of work on purpose: the default must not touch it - Phase 13's
        // implementations are the ones the contract obliges to read durable state through it.
        TransferLimitCheck<Void> limits = new PermitAllUntilPhase13<>();
        TransferRiskDecision<Void> risk = new PermitAllUntilPhase13<>();
        assertThat(limits.check(null, transfer)).isEqualTo(SeamVerdict.PERMIT);
        assertThat(risk.check(null, transfer)).isEqualTo(SeamVerdict.PERMIT);
    }

    @Test
    @DisplayName("the seams' size IS the no-Phase-13-logic assertion: no state, one method each")
    void theSeamsCarryNoLogic() {
        // The default holds no field: a counter, a cache or a policy handle appearing here is
        // Phase 13 behaviour arriving early (ROADMAP.md section Refinement 2: a seam is a
        // documented interface, not a stub of the later domain).
        assertThat(PermitAllUntilPhase13.class.getDeclaredFields())
                .as("PermitAllUntilPhase13 must hold no state of any kind")
                .isEmpty();
        // Each port declares exactly one method: a second operation on a seam is a contract
        // Phase 13 did not ask for and Phase 4 cannot design.
        assertThat(declaredMethodsOf(TransferLimitCheck.class)).hasSize(1);
        assertThat(declaredMethodsOf(TransferRiskDecision.class)).hasSize(1);
        // And the verdict vocabulary is exactly permit-or-refuse: a third value would be a
        // richer result shape than the execution's fixed reason mapping can honour.
        assertThat(SeamVerdict.values())
                .containsExactly(SeamVerdict.PERMIT, SeamVerdict.REFUSE);
    }

    private static Method[] declaredMethodsOf(Class<?> port) {
        return java.util.Arrays.stream(port.getDeclaredMethods())
                .filter(method -> !method.isSynthetic())
                .toArray(Method[]::new);
    }
}
