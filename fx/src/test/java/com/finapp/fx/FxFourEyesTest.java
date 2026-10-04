package com.finapp.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The domain rank of both four-eyes machines, proven alone (`P9-TSK-007`, {@code INV-AUD-04}): the
 * stores here answer only the locked row and record every write, so a refusal can only be the
 * domain's own - the database {@code CHECK} is proven alone by the database suites. Also the
 * proposal's judgement, before anything is stored.
 */
@DisplayName("the FX four-eyes rules' domain rank, alone (P9-TSK-007)")
class FxFourEyesTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Actor PROPOSER = new Actor("proposer", ActorType.EMPLOYEE);
    private static final Actor SECOND = new Actor("second", ActorType.EMPLOYEE);
    private static final Connection UNIT = stub(Connection.class, new ArrayList<>(), null);

    @Test
    @DisplayName("a pricing policy's proposer cannot approve it: refused before any write")
    void aPolicyProposerCannotApprove() {
        List<String> writes = new ArrayList<>();
        PricingPolicyStore.VersionRow pending = new PricingPolicyStore.VersionRow(
                PricingPolicyId.next(IDS), 2, PricingPolicyStatus.PROPOSED, 5, PROPOSER.id(), Optional.empty());
        PricingPolicyAdministration administration = policies(writes, pending);

        assertThatThrownBy(() -> administration.approve(UNIT, pending.id(), PROPOSER, "mine", NOW, correlation()))
                .isInstanceOf(PricingPolicyAdministration.SelfApprovalRefused.class);
        assertThat(writes).as("nothing written, audited or published").isEmpty();
    }

    @Test
    @DisplayName("a second person's approval of the same row reaches the store - the refusal above is"
            + " the proposer's alone")
    void aSecondPersonReachesTheStore() {
        List<String> writes = new ArrayList<>();
        PricingPolicyStore.VersionRow pending = new PricingPolicyStore.VersionRow(
                PricingPolicyId.next(IDS), 2, PricingPolicyStatus.PROPOSED, 5, PROPOSER.id(), Optional.empty());

        policies(writes, pending).approve(UNIT, pending.id(), SECOND, "checked", NOW, correlation());
        assertThat(writes).contains("decide", "appendEvent", "append", "write");
    }

    @Test
    @DisplayName("an enable request's proposer cannot approve it: refused before any write")
    void anEnableProposerCannotApprove() {
        List<String> writes = new ArrayList<>();
        AvailabilityStore.RequestRow pending = new AvailabilityStore.RequestRow(
                UUID.randomUUID(), AvailabilitySubject.pair("EUR-USD"),
                AvailabilityStore.EnableRequestStatus.PROPOSED, PROPOSER.id(), Optional.empty());
        FxAvailability availability = availability(writes, pending);

        assertThatThrownBy(() -> availability.approveEnable(UNIT, pending.id(), PROPOSER, "mine", NOW, correlation()))
                .isInstanceOf(FxAvailability.EnableSelfApprovalRefused.class);
        assertThat(writes).as("nothing written, audited or published").isEmpty();

        availability.approveEnable(UNIT, pending.id(), SECOND, "checked", NOW, correlation());
        assertThat(writes).contains("decideRequest", "insertFact", "append", "write");
    }

    @Test
    @DisplayName("a disable is one person's act at once; an enabling never is")
    void disablingIsOnePersonEnablingNever() {
        List<String> writes = new ArrayList<>();
        FxAvailability availability = availability(writes, null);

        assertThat(availability.disable(UNIT, AvailabilitySubject.pair("EUR-USD"), PROPOSER, "outage", NOW,
                        correlation()).changed())
                .isTrue();
        assertThat(writes).contains("insertFact");
        writes.clear();
        assertThatThrownBy(() -> availability.proposeEnable(UNIT, AvailabilitySubject.pair("EUR-USD"), PROPOSER,
                        "back", NOW, correlation()))
                .as("the stub answers available, so there is nothing to enable")
                .isInstanceOf(FxAvailability.AlreadyAvailable.class);
        assertThat(writes).doesNotContain("insertFact");
    }

    @Test
    @DisplayName("a reason holding a card-number or account shape is refused at the domain, nothing"
            + " written - on the policy's decisions and on the kill switch alike")
    void aReasonIsScreenedFirst() {
        List<String> writes = new ArrayList<>();
        PricingPolicyStore.VersionRow pending = new PricingPolicyStore.VersionRow(
                PricingPolicyId.next(IDS), 2, PricingPolicyStatus.PROPOSED, 5, PROPOSER.id(), Optional.empty());
        String needle = "approved, card 4111 1111 1111 1111";
        assertThatThrownBy(() -> policies(writes, pending).approve(UNIT, pending.id(), SECOND, needle, NOW,
                        correlation()))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class)
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> availability(writes, null).disable(UNIT, AvailabilitySubject.pair("EUR-USD"),
                        PROPOSER, "pay to GB82 WEST 1234 5698 7654 32", NOW, correlation()))
                .isInstanceOf(FxAvailability.AvailabilityInvalid.class)
                .hasMessageNotContaining("GB82");
        assertThat(writes).isEmpty();
    }

    @Test
    @DisplayName("a proposal is judged before anything is stored: no pair, a cap out of 1..100, an"
            + " undeclared provider, a duplicated pair, a missing reason")
    void aProposalIsJudgedFirst() {
        PolicyPair pair = FxPolicyFixtures.eurUsd();
        Set<String> declared = FxPolicyFixtures.DECLARED;
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(), 5, "empty").validate(declared))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class);
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(pair), 0, "cap").validate(declared))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class);
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(pair), 101, "cap").validate(declared))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class);
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(pair), 5, "provider").validate(Set.of("other")))
                .isInstanceOf(PricingPolicyAdministration.ProviderNotDeclared.class);
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(pair, pair), 5, "twice").validate(declared))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class);
        assertThatThrownBy(() -> new PricingPolicyProposal(List.of(pair), 5, "").validate(declared))
                .isInstanceOf(PricingPolicyAdministration.PricingPolicyInvalid.class);
    }

    // -----------------------------------------------------------------

    private static PricingPolicyAdministration policies(List<String> writes, PricingPolicyStore.VersionRow locked) {
        PricingPolicyStore store = stub(PricingPolicyStore.class, writes, locked);
        return new PricingPolicyAdministration(store, audit(writes), outbox(writes), IDS, FxPolicyFixtures.DECLARED);
    }

    private static FxAvailability availability(List<String> writes, AvailabilityStore.RequestRow locked) {
        AvailabilityStore store = stub(AvailabilityStore.class, writes, locked);
        return new FxAvailability(store, audit(writes), outbox(writes), IDS);
    }

    private static AuditWriter<Connection> audit(List<String> writes) {
        return (unit, record) -> writes.add("append");
    }

    private static OutboxWriter<Connection> outbox(List<String> writes) {
        return (unit, envelope, payload, mediaType) -> writes.add("write");
    }

    /**
     * A store that answers {@code locked} to every lock, success to every guarded write, and records
     * each write's name; a subject reads available unless a request is in play (then it awaits one).
     */
    private static <T> T stub(Class<T> type, List<String> writes, Object locked) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            String name = method.getName();
            Class<?> returns = method.getReturnType();
            if (name.startsWith("lock")) {
                return name.equals("lockSubject") ? null : name.equals("lockActive") ? Optional.empty()
                        : Optional.ofNullable(locked);
            }
            if (name.equals("isAvailable")) {
                return locked == null;
            }
            if (name.equals("maxVersion")) {
                return 1;
            }
            if (name.equals("versions")) {
                return List.of();
            }
            if (name.equals("hashCode")) {
                return System.identityHashCode(proxy);
            }
            if (name.equals("equals")) {
                return proxy == args[0];
            }
            if (name.equals("toString")) {
                return type.getSimpleName() + " stub";
            }
            writes.add(name);
            return returns == boolean.class ? Boolean.TRUE : null;
        }));
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
