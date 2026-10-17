package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The BNPL enablement's machine and four eyes in the domain rank (`P12-TSK-006`; OQ-8, {@code INV-AUD-04}): over
 * in-memory stores holding the JDBC statements' conditions ({@link BnplFakes}). The every-writer ranks - `V010`'s CHECK,
 * the one-live index, the edge trigger - and the counted races are {@code MerchantBnplEnablementDatabaseTest}'s.
 */
@DisplayName("merchant BNPL enablement under four eyes (P12-TSK-006)")
class BnplEnablementsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Connection UOW = BnplFakes.CONNECTION;
    private static final CorrelationId CORRELATION = CorrelationId.of("p12-tsk-006-test");

    private static final Actor ANNA = new Actor("operator-anna", ActorType.EMPLOYEE);
    private static final Actor BORIS = new Actor("operator-boris", ActorType.EMPLOYEE);

    private BnplFakes.Enablements store;
    private BnplFakes.Merchants merchants;
    private BnplFakes.Audit audit;
    private BnplEnablements enablements;
    private Merchant merchant;

    @BeforeEach
    void setUp() {
        store = new BnplFakes.Enablements(CLOCK);
        merchants = new BnplFakes.Merchants();
        audit = new BnplFakes.Audit();
        enablements = new BnplEnablements(store, merchants, audit, IDS, CLOCK);
        merchant = Merchant.onboard(IDS, CLOCK, IDS.next(), "Shop GmbH", "Shop", CurrencyCode.of("EUR"));
        merchants.insert(UOW, merchant);
    }

    @Test
    @DisplayName("a merchant is enabled only by two people: the proposer proposes, a second operator approves")
    void enablementIsFourEyes() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "merchant contract signed", ANNA, CORRELATION);
        assertThat(proposed.status()).isEqualTo(BnplEnablementStatus.PROPOSED);
        assertThat(store.activeForShare(UOW, merchant.id())).isEmpty();

        BnplEnablements.Decided approved =
                enablements.approve(UOW, merchant.id(), proposed.id(), "checked the contract", BORIS, CORRELATION);

        assertThat(approved.replayed()).isFalse();
        assertThat(approved.enablement().status()).isEqualTo(BnplEnablementStatus.ACTIVE);
        assertThat(approved.enablement().decidedBy()).contains(BORIS.id());
        assertThat(store.activeForShare(UOW, merchant.id())).isPresent();
        assertThat(audit.records).extracting(record -> record.operation())
                .containsExactly(MerchantAuditAction.BNPL_ENABLEMENT_PROPOSED,
                        MerchantAuditAction.BNPL_ENABLEMENT_APPROVED);
    }

    @Test
    @DisplayName("the proposer cannot approve: refused before anything is written, and the refusal recordable FAILED")
    void theProposerCannotApprove() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "contract signed", ANNA, CORRELATION);

        assertThatThrownBy(() -> enablements.approve(UOW, merchant.id(), proposed.id(), "mine", ANNA, CORRELATION))
                .isInstanceOfSatisfying(BnplEnablements.SelfApprovalRefused.class, refused -> {
                    assertThat(refused.target()).isEqualTo(proposed.id().value().toString());
                    enablements.recordRefusal(UOW, refused, ANNA, CORRELATION);
                });

        assertThat(store.rows.get(proposed.id()).status()).isEqualTo(BnplEnablementStatus.PROPOSED);
        assertThat(audit.records).last().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(MerchantAuditAction.BNPL_ENABLEMENT_APPROVAL_REFUSED);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
            assertThat(record.reason()).isEmpty();
        });
    }

    @Test
    @DisplayName("one live enablement per merchant: a second proposal, or one beside an active enablement, is pending")
    void oneLiveProposalPerMerchant() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "first", ANNA, CORRELATION);
        assertThatThrownBy(() -> enablements.propose(UOW, merchant.id(), "second", BORIS, CORRELATION))
                .isInstanceOf(BnplEnablements.Pending.class);
        enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION);
        assertThatThrownBy(() -> enablements.propose(UOW, merchant.id(), "third", BORIS, CORRELATION))
                .isInstanceOf(BnplEnablements.Pending.class);
        assertThat(store.rows).hasSize(1);
    }

    @Test
    @DisplayName("an approval after a rejection, and a rejection after an approval, find the decision not open")
    void approvalAndRejectionLeaveExactlyOne() {
        BnplEnablement first = enablements.propose(UOW, merchant.id(), "first", ANNA, CORRELATION);
        enablements.reject(UOW, merchant.id(), first.id(), "not yet", BORIS, CORRELATION);
        assertThatThrownBy(() -> enablements.approve(UOW, merchant.id(), first.id(), "ok", BORIS, CORRELATION))
                .isInstanceOf(BnplEnablements.NotOpen.class);

        BnplEnablement second = enablements.propose(UOW, merchant.id(), "again", ANNA, CORRELATION);
        enablements.approve(UOW, merchant.id(), second.id(), "ok", BORIS, CORRELATION);
        assertThatThrownBy(() -> enablements.reject(UOW, merchant.id(), second.id(), "no", ANNA, CORRELATION))
                .isInstanceOf(BnplEnablements.NotOpen.class);
        assertThat(store.rows.get(first.id()).status()).isEqualTo(BnplEnablementStatus.REJECTED);
        assertThat(store.rows.get(second.id()).status()).isEqualTo(BnplEnablementStatus.ACTIVE);
    }

    @Test
    @DisplayName("the same person's retry of a decision converges and records nothing more")
    void theSamePersonsRetryConverges() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "p", ANNA, CORRELATION);
        enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION);
        int recorded = audit.records.size();

        BnplEnablements.Decided again = enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION);

        assertThat(again.replayed()).isTrue();
        assertThat(audit.records).hasSize(recorded);
    }

    @Test
    @DisplayName("the proposer may reject their own proposal - a change of mind, not an approval")
    void theProposerMayReject() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "p", ANNA, CORRELATION);
        assertThat(enablements.reject(UOW, merchant.id(), proposed.id(), "withdrawn", ANNA, CORRELATION)
                .enablement().status()).isEqualTo(BnplEnablementStatus.REJECTED);
    }

    @Test
    @DisplayName("withdrawal is one operator's act on an ACTIVE enablement, and a withdrawn one offers nothing again")
    void aWithdrawnEnablementOffersNothing() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "p", ANNA, CORRELATION);
        assertThatThrownBy(() -> enablements.withdraw(UOW, merchant.id(), proposed.id(), "stop", ANNA, CORRELATION))
                .isInstanceOf(BnplEnablements.NotOpen.class);
        enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION);

        BnplEnablements.Decided withdrawn =
                enablements.withdraw(UOW, merchant.id(), proposed.id(), "fraud signals", ANNA, CORRELATION);

        assertThat(withdrawn.enablement().status()).isEqualTo(BnplEnablementStatus.WITHDRAWN);
        assertThat(store.activeForShare(UOW, merchant.id())).isEmpty();
        assertThat(audit.records).last().extracting(record -> record.operation())
                .isEqualTo(MerchantAuditAction.BNPL_ENABLEMENT_WITHDRAWN);
    }

    @Test
    @DisplayName("a closed merchant takes no proposal and no approval; an unknown one is unknown")
    void aClosedMerchantIsRefused() {
        BnplEnablement proposed = enablements.propose(UOW, merchant.id(), "p", ANNA, CORRELATION);
        merchants.insert(UOW, merchant.close(CLOCK));
        assertThatThrownBy(() -> enablements.approve(UOW, merchant.id(), proposed.id(), "ok", BORIS, CORRELATION))
                .isInstanceOf(MerchantClosedException.class);
        assertThatThrownBy(() -> enablements.propose(UOW, MerchantId.next(IDS), "p", ANNA, CORRELATION))
                .isInstanceOf(UnknownMerchantException.class);
        assertThatThrownBy(() -> enablements.approve(UOW, merchant.id(), BnplEnablementId.next(IDS), "ok", BORIS,
                CORRELATION)).isInstanceOf(BnplEnablements.Unknown.class);
    }

    @Test
    @DisplayName("every act is reasoned, before anything is written")
    void everyActIsReasoned() {
        assertThatThrownBy(() -> enablements.propose(UOW, merchant.id(), " ", ANNA, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> enablements.propose(UOW, merchant.id(), "x".repeat(1001), ANNA, CORRELATION))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.rows).isEmpty();
        assertThat(audit.records).isEmpty();
    }
}
