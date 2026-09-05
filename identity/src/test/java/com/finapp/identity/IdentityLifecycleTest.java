package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Identity state machine, rejected <strong>by the aggregate</strong> (`INV-LIFE-02`).
 *
 * <p>Same method and same reasoning as {@code CustomerLifecycleTest}: every transition is
 * enumerated from the machine rather than listed by hand, and every assertion calls the aggregate
 * directly, because an API-level check protects no other caller.
 */
@DisplayName("Identity lifecycle (P1-TSK-005)")
class IdentityLifecycleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    @Test
    @DisplayName("an identity is created ACTIVE, unlike a Customer which opens PENDING")
    void createdActive() {
        // The asymmetry is deliberate. A Customer is PENDING because a decision about it has not
        // been taken; no such decision stands between creating a login and using it. Whether the
        // login is USEFUL depends on it having a credential, which is a different question and is
        // represented by there being no credential rather than by a state.
        Identity identity = Identity.create(IDS, CLOCK, UUID.randomUUID(), new LoginIdentifier("ada.l"));

        assertThat(identity.status()).isEqualTo(IdentityStatus.ACTIVE);
        assertThat(identity.canAuthenticate()).isTrue();
    }

    @Test
    @DisplayName("every state pair behaves exactly as the machine declares")
    void everyTransitionIsEnforced() {
        for (IdentityStatus from : IdentityStatus.values()) {
            for (IdentityStatus to : IdentityStatus.values()) {
                Identity identity = at(from);
                boolean permitted = from.permittedTransitions().contains(to);

                if (permitted) {
                    assertThat(move(identity, to).status())
                            .as("%s -> %s is declared permitted and must succeed", from, to)
                            .isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> move(identity, to))
                            .as("%s -> %s is not declared permitted and must be refused", from, to)
                            .isInstanceOf(IllegalIdentityTransitionException.class);
                }
            }
        }
    }

    @Test
    @DisplayName("CLOSED is terminal, and closing is not deletion")
    void closedIsTerminal() {
        Identity closed = at(IdentityStatus.CLOSED);

        assertThat(IdentityStatus.CLOSED.isTerminal()).isTrue();
        for (IdentityStatus target : IdentityStatus.values()) {
            assertThatThrownBy(() -> move(closed, target))
                    .as("CLOSED -> %s must be refused", target)
                    .isInstanceOf(IllegalIdentityTransitionException.class);
        }

        // The row survives, which is the point: audit records and past sessions reference it, and
        // INV-HIST-01 forbids rewriting the history that points at it.
        assertThat(closed.id()).isNotNull();
        assertThat(closed.partyId()).isNotNull();
    }

    @Test
    @DisplayName("only an ACTIVE identity may authenticate")
    void onlyActiveMayAuthenticate() {
        // Asserted against the status rather than against a boolean field, so a new state cannot be
        // added that silently defaults to being able to log in.
        for (IdentityStatus status : IdentityStatus.values()) {
            assertThat(at(status).canAuthenticate())
                    .as("%s may authenticate?", status)
                    .isEqualTo(status == IdentityStatus.ACTIVE);
        }
    }

    @Test
    @DisplayName("the machine has exactly one terminal state, and it is CLOSED")
    void exactlyOneTerminalState() {
        Set<IdentityStatus> terminal = EnumSet.noneOf(IdentityStatus.class);
        for (IdentityStatus status : IdentityStatus.values()) {
            if (status.isTerminal()) {
                terminal.add(status);
            }
        }
        assertThat(terminal).containsExactly(IdentityStatus.CLOSED);
    }

    // -----------------------------------------------------------------

    private static Identity at(IdentityStatus status) {
        Instant now = Instant.now(CLOCK);
        return Identity.rehydrate(
                IdentityId.next(IDS),
                UUID.randomUUID(),
                new LoginIdentifier("ada.l"),
                status,
                now,
                now);
    }

    private static Identity move(Identity identity, IdentityStatus target) {
        return switch (target) {
            case ACTIVE -> identity.reinstate(CLOCK);
            case SUSPENDED -> identity.suspend(CLOCK);
            case CLOSED -> identity.close(CLOCK);
        };
    }
}
