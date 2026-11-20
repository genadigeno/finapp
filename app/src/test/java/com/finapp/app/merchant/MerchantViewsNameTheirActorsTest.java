package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.MerchantApiKey;
import com.finapp.merchant.MerchantApiKeyScope;
import com.finapp.merchant.MerchantApiKeySecret;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.PayoutDestination;
import com.finapp.merchant.PayoutDestinationId;
import com.finapp.merchant.PayoutDestinationReference;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the merchant views name, without a database (`P17-TSK-114`, finapp-admin-panel B7.5): a payout destination change its
 * proposer, a key its issuer - each by identifier, and nothing else of the person - and the approver's read serving only the
 * open change it was named for. The routes, the permission and the masking over HTTP are
 * {@code PayoutDestinationEndpointDatabaseTest}'s and {@code MerchantApiKeyDatabaseTest}'s.
 */
@DisplayName("the merchant views name their actors (P17-TSK-114)")
class MerchantViewsNameTheirActorsTest {

    private static final Instant T0 = Instant.parse("2026-11-19T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final MerchantId MERCHANT = MerchantId.next(IDS);
    private static final String PROPOSER = "0193a3b2-0000-7000-8000-00000000c0de";
    private static final String APPROVER = "0193a3b2-0000-7000-8000-00000000a99e";
    private static final String REFERENCE = "pdr_never-served-1";
    private static final String REASON = "the merchant's bank letter of 18 November";

    @Test
    @DisplayName("a destination change names its proposer at every status, never its decider, reference or reason")
    void aDestinationNamesItsProposer() {
        PayoutDestination proposed = proposed();
        PayoutDestinationOperations.PayoutDestinationView view = PayoutDestinationOperations.render(proposed);
        assertThat(view.proposedBy()).isEqualTo(PROPOSER);
        assertThat(view.status()).isEqualTo("PROPOSED");

        PayoutDestination approved = proposed.approve(APPROVER, T0.plusSeconds(60), Duration.ofHours(72));
        PayoutDestinationOperations.PayoutDestinationView approvedView = PayoutDestinationOperations.render(approved);
        assertThat(approvedView.proposedBy())
                .as("the proposer still, after a second person approved it - the console's self-approval check is against"
                        + " the proposer alone")
                .isEqualTo(PROPOSER);
        assertThat(approvedView.toString())
                .doesNotContain(APPROVER)
                .doesNotContain(REFERENCE)
                .doesNotContain(REASON);
    }

    @Test
    @DisplayName("the approver's read serves the open change only when it is the one named")
    void theOpenChangeOnlyByItsOwnIdentifier() {
        PayoutDestination open = proposed();
        assertThat(PayoutDestinationOperations.openChange(Optional.of(open), open.id())).contains(open);
        assertThat(PayoutDestinationOperations.openChange(Optional.of(open), PayoutDestinationId.next(IDS)))
                .as("another change of the merchant - effective, superseded, ended - is not the open one")
                .isEmpty();
        assertThat(PayoutDestinationOperations.openChange(Optional.empty(), open.id()))
                .as("a change no longer open is not served")
                .isEmpty();
    }

    @Test
    @DisplayName("a key names who issued it, by identifier, beside its metadata - never a secret or a hash")
    void aKeyNamesItsIssuer() {
        MerchantApiKeySecret secret = MerchantApiKeySecret.issue(new SecureRandom());
        MerchantApiKey key =
                MerchantApiKey.issue(IDS, CLOCK, MERCHANT, secret, PROPOSER, Set.of(MerchantApiKeyScope.BNPL_REFUND));
        MerchantApiKeyOperations.KeyView view = MerchantApiKeyOperations.render(key);
        assertThat(view.issuedBy()).isEqualTo(PROPOSER);
        assertThat(view.keyId()).isEqualTo(key.id().value().toString());
        assertThat(view.scopes()).containsExactly("BNPL_REFUND");

        MerchantApiKeyOperations.KeyView revoked = MerchantApiKeyOperations.render(key.revoke(CLOCK));
        assertThat(revoked.issuedBy()).as("the issuer outlives the key's revocation").isEqualTo(PROPOSER);
        assertThat(revoked.status()).isEqualTo("REVOKED");
    }

    private static PayoutDestination proposed() {
        return PayoutDestination.propose(
                IDS, CLOCK, MERCHANT, PayoutDestinationReference.of(REFERENCE), "3000", PROPOSER, REASON);
    }
}
