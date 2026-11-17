package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@link LedgerAccount} aggregate's own rules (`P3-TSK-002`).
 *
 * <p>Few and crucial: the two derivations, the coherence refusals, and the machine swept from
 * its own declaration rather than from a list somebody remembered to write.
 */
@DisplayName("the LedgerAccount aggregate (P3-TSK-002)")
class LedgerAccountTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    @Test
    @DisplayName("the normal balance derives from the type, for every type")
    void normalBalanceDerivesFromTheType() {
        // The whole derivation, not a sample: five members, fixed by accounting, and the test
        // is the readable statement of which side each grows on.
        assertThat(AccountType.ASSET.normalBalance()).isEqualTo(NormalBalance.DEBIT);
        assertThat(AccountType.EXPENSE.normalBalance()).isEqualTo(NormalBalance.DEBIT);
        assertThat(AccountType.LIABILITY.normalBalance()).isEqualTo(NormalBalance.CREDIT);
        assertThat(AccountType.EQUITY.normalBalance()).isEqualTo(NormalBalance.CREDIT);
        assertThat(AccountType.REVENUE.normalBalance()).isEqualTo(NormalBalance.CREDIT);

        // And the factory stores what the derivation says - a caller cannot choose.
        LedgerAccount wallet =
                LedgerAccount.owned(
                        IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, GBP,
                        UUID.randomUUID());
        assertThat(wallet.normalBalance()).isEqualTo(NormalBalance.CREDIT);
        assertThat(wallet.ownerKind()).isEqualTo(OwnerKind.CUSTOMER);
    }

    @Test
    @DisplayName("an owned account cannot be constructed for a platform purpose")
    void anOwnedAccountRefusesAPlatformPurpose() {
        // The purpose -> owner-kind derivation refusing at construction: FEE_REVENUE is the
        // platform's, and handing it a customer owner would hand the platform's money a
        // customer. The schema CHECK is the second layer for writers that never run this.
        assertThatThrownBy(
                        () ->
                                LedgerAccount.owned(
                                        IDS,
                                        CLOCK,
                                        AccountType.REVENUE,
                                        AccountPurpose.FEE_REVENUE,
                                        GBP,
                                        UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPERATIONAL");
    }

    @Test
    @DisplayName("every invalid status transition is rejected by the aggregate")
    void everyInvalidTransitionIsRejected() {
        // The cross-product, derived from the machine (INV-LIFE-02, the KycCase idiom): a
        // transition the machine does not permit throws, whatever list anybody remembered.
        for (LedgerAccountStatus from : LedgerAccountStatus.values()) {
            for (LedgerAccountStatus to : LedgerAccountStatus.values()) {
                LedgerAccount account = accountIn(from);
                if (from.canTransitionTo(to)) {
                    assertThat(moveTo(account, to).status()).isEqualTo(to);
                } else {
                    assertThatThrownBy(() -> moveTo(account, to))
                            .as("%s -> %s must be rejected by the aggregate", from, to)
                            .isInstanceOf(IllegalLedgerAccountTransitionException.class);
                }
            }
        }
        // The machine's shape, pinned: CLOSED is the one terminal, and it is terminal.
        assertThat(LedgerAccountStatus.CLOSED.isTerminal()).isTrue();
        assertThat(LedgerAccountStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(LedgerAccountStatus.POSTING_SUSPENDED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("a move on a clock trailing the account's latest stamp is stamped at that stamp, never before it; a"
            + " clock past it stamps its own read (X-TSK-006, ADR-0063 decision 2)")
    void aMoveOnATrailingClockIsStampedAtTheLatestStamp() {
        // Created at 09:00, suspended at 09:01 - by an instance whose clock led this one's.
        Instant created = Instant.parse("2026-09-04T09:00:00Z");
        Instant latest = created.plusSeconds(60);
        LedgerAccount fresh = accountIn(LedgerAccountStatus.ACTIVE);
        LedgerAccount suspended =
                LedgerAccount.rehydrate(
                        fresh.id(),
                        fresh.accountType(),
                        fresh.normalBalance(),
                        fresh.currency(),
                        fresh.ownerKind(),
                        fresh.ownerRef().orElseThrow(),
                        fresh.purpose(),
                        null,
                        LedgerAccountStatus.POSTING_SUSPENDED,
                        created,
                        latest);
        Clock trailing = Clock.offset(Clock.fixed(latest, ZoneOffset.UTC), Duration.ofSeconds(-5));
        assertThat(Instant.now(trailing))
                .as("precondition: the clock trails the latest stamp, and not only birth")
                .isBefore(latest)
                .isAfter(created);

        LedgerAccount resumed = suspended.resumePostings(trailing);
        assertThat(resumed.status()).isEqualTo(LedgerAccountStatus.ACTIVE);
        assertThat(resumed.statusChangedAt()).as("the predecessor's instant").isEqualTo(latest);
        assertThat(resumed.close(trailing).statusChangedAt()).isEqualTo(latest);

        // A floor, not a pin.
        Clock ahead = Clock.fixed(latest.plusSeconds(5), ZoneOffset.UTC);
        assertThat(suspended.resumePostings(ahead).statusChangedAt()).isEqualTo(latest.plusSeconds(5));
    }

    /** Drives an account into {@code from} through rehydrate, so every state is reachable. */
    private static LedgerAccount accountIn(LedgerAccountStatus from) {
        LedgerAccount fresh =
                LedgerAccount.owned(
                        IDS, CLOCK, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, GBP,
                        UUID.randomUUID());
        return LedgerAccount.rehydrate(
                fresh.id(),
                fresh.accountType(),
                fresh.normalBalance(),
                fresh.currency(),
                fresh.ownerKind(),
                fresh.ownerRef().orElseThrow(),
                fresh.purpose(),
                null,
                from,
                fresh.createdAt(),
                fresh.statusChangedAt());
    }

    /** One call per target, so the sweep drives the aggregate's methods and not an enum. */
    private static LedgerAccount moveTo(LedgerAccount account, LedgerAccountStatus to) {
        return switch (to) {
            case ACTIVE -> account.resumePostings(CLOCK);
            case POSTING_SUSPENDED -> account.suspendPostings(CLOCK);
            case CLOSED -> account.close(CLOCK);
        };
    }
}
