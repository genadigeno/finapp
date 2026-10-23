package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.app.risk.RiskWithdrawalStepUpAdvice;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStatus;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.Session;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.Withdrawals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyRecord;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.RiskOperation;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal door's step-up before the command (`P13-TSK-009`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108
 * decision 3; {@code INV-IDEM-01}, {@code INV-RSK-08}) - {@code TransferDoorAdviceTest}'s cases at the outbound door,
 * over the real {@link WithdrawalService} and {@link WithdrawalDoorStepUp}: a challenge the session cannot satisfy is
 * {@code 403 identity.AssuranceRequired} from the door's own transaction - the command never runs, nothing is written; a
 * recorded key is never advised (it replays); a wallet or an instrument that is not the caller's is never advised over;
 * the 403 names a second factor and nothing of risk; and the advice port, answered by T-advise, writes nothing. The
 * real door over HTTP is the owed {@code WithdrawalRiskSeamDatabaseTest#theDoorsStepUpWritesNothing} (C5).
 */
@DisplayName("the withdrawal door's step-up: 403 before the command, nothing written (P13-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class WithdrawalDoorAdviceTest {

    private static final Clock CLOCK = RiskEvaluationWorld.CLOCK;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final UUID party = RiskEvaluationWorld.IDS.next();
    private final UUID customer = RiskEvaluationWorld.IDS.next();
    private final IdentityId identity = IdentityId.of(RiskEvaluationWorld.IDS.next());
    private final UUID method = RiskEvaluationWorld.IDS.next();
    private final CustomerAccount product =
            CustomerAccount.open(RiskEvaluationWorld.IDS, CLOCK, customer, ProductType.WALLET);
    private final List<String> journal = new ArrayList<>();
    private final List<WithdrawalStepUpAdvice.Proposed> proposals = new ArrayList<>();
    private final List<IdempotencyKey> keysAsked = new ArrayList<>();

    private boolean recorded;
    private boolean walletOwned = true;
    private boolean instrumentOwned = true;
    private boolean enrolled = true;
    private boolean stepUp;

    // ------------------------------------------------------------------ the door's check

    @Test
    @DisplayName("a challenge the session cannot satisfy is 403 identity.AssuranceRequired from the door's one"
            + " transaction: the command never runs, nothing is written")
    void anUnsatisfiedChallengeIsAnsweredBeforeTheCommand() {
        stepUp = true;
        ApiException refused = catchThrowableOfType(ApiException.class, this::withdraw);
        assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(refused.errorCode().status()).isEqualTo(403);
        assertThat(journal).as("one transaction - the advice's - and never the command's").containsExactly("advice");
        assertThat(proposals).singleElement().satisfies(proposed -> {
            assertThat(proposed.party()).isEqualTo(party);
            assertThat(proposed.identity()).isEqualTo(identity.value());
            assertThat(proposed.sourceAccount()).as("the wallet's customer account").isEqualTo(product.id().value());
            assertThat(proposed.amount()).isEqualTo(Money.ofMinorUnits(600_00, EUR));
        });
        assertThat(keysAsked).singleElement().satisfies(key ->
                assertThat(key.scope()).isEqualTo(Withdrawals.claimScopeOf(customer)));
    }

    @Test
    @DisplayName("no step-up needed: the advice's transaction, then the command's")
    void noStepUpRunsTheCommand() {
        assertThatThrownBy(this::withdraw).isInstanceOf(CommandReached.class);
        assertThat(journal).containsExactly("advice", "command");
    }

    @Test
    @DisplayName("a retry of a recorded key is never advised (INV-IDEM-01): the command replays or conflicts")
    void aRetryIsNeverAdvised() {
        stepUp = true;
        recorded = true;
        assertThatThrownBy(this::withdraw).isInstanceOf(CommandReached.class);
        assertThat(proposals).isEmpty();
    }

    @Test
    @DisplayName("a wallet or an instrument that is not the caller's is never advised over: the command answers its"
            + " own 422 as always")
    void unownedMoneyIsNeverAdvisedOver() {
        stepUp = true;
        walletOwned = false;
        assertThatThrownBy(this::withdraw).isInstanceOf(CommandReached.class);
        walletOwned = true;
        instrumentOwned = false;
        assertThatThrownBy(this::withdraw).isInstanceOf(CommandReached.class);
        assertThat(proposals).isEmpty();
    }

    @Test
    @DisplayName("the 403 names the second factor and the step the customer takes - enrolled or not - and nothing of"
            + " risk (INV-RSK-08)")
    void theStepUpNamesNoRiskWord() {
        stepUp = true;
        ApiException enrolledRefusal = catchThrowableOfType(ApiException.class, this::withdraw);
        enrolled = false;
        ApiException unenrolledRefusal = catchThrowableOfType(ApiException.class, this::withdraw);
        assertThat(enrolledRefusal.clientDetail()).hasValueSatisfying(
                detail -> assertThat(detail).contains("step up with your enrolled factor"));
        assertThat(unenrolledRefusal.clientDetail()).hasValueSatisfying(
                detail -> assertThat(detail).contains("enrol one"));
        for (ApiException refusal : List.of(enrolledRefusal, unenrolledRefusal)) {
            String said = (refusal.getMessage() + " " + refusal.clientDetail().orElseThrow())
                    .toLowerCase(java.util.Locale.ROOT);
            for (String word : List.of("risk", "challenge", "deny", "review", "score", "rule", "assessment", "amount",
                    "velocity", "limit", "hold")) {
                assertThat(said).as(word).doesNotContain(word);
            }
        }
    }

    // ------------------------------------------------------------------ the advice port

    @Test
    @DisplayName("the advice port answered by T-advise: a recovered identity's withdrawal from a PASSWORD session needs"
            + " a step-up, from a MULTI_FACTOR one it does not, a held amount is not a step-up - and nothing is sealed,"
            + " counted or announced")
    void theAdviceWritesNothing() throws Exception {
        RiskEvaluationWorld world = new RiskEvaluationWorld();
        world.activate(RiskOperation.WITHDRAWAL);
        world.signalStore.add(new com.finapp.risk.RiskSignal(com.finapp.risk.RiskSignalId.next(RiskEvaluationWorld.IDS),
                com.finapp.risk.SignalKind.RECOVERY_COMPLETED,
                com.finapp.risk.SignalSubject.of(com.finapp.risk.SubjectKind.IDENTITY, identity.value()),
                new com.finapp.risk.RiskSignal.SourceEvent(com.finapp.sharedkernel.event.EventId.next(
                        RiskEvaluationWorld.IDS), "identity.RecoveryCompleted", 1, "identity",
                        CorrelationId.of("flow"), com.finapp.sharedkernel.correlation.CausationId.of("cause")),
                CLOCK.instant().minus(java.time.Duration.ofHours(10)),
                com.finapp.risk.SignalAttributes.of(com.finapp.risk.SignalKind.RECOVERY_COMPLETED,
                        java.util.Map.of("request_ref", RiskEvaluationWorld.IDS.next().toString(), "channel_ref",
                                RiskEvaluationWorld.IDS.next().toString()))));
        RiskWithdrawalStepUpAdvice advice = new RiskWithdrawalStepUpAdvice(world.evaluation);
        Actor actor = new Actor(identity.value().toString(), ActorType.CUSTOMER);
        Session password = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.PASSWORD, null);
        Session mfa = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.MULTI_FACTOR, null);
        world.sessions = new com.finapp.app.risk.IdentitySessionAssurance(
                RiskEvaluationWorld.liveSessions(new ArrayList<>(), password, mfa));
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(RiskEvaluationWorld.IDS)))) {
            try (AutoCloseable presented = RiskEvaluationWorld.presenting(password)) {
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(10_00))).isTrue();
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(2_500_00)))
                        .as("a HOLD is no challenge: the command holds it").isFalse();
            }
            try (AutoCloseable presented = RiskEvaluationWorld.presenting(mfa)) {
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(10_00))).isFalse();
            }
        }
        assertThat(world.assessmentStore.all()).as("no assessment").isEmpty();
        assertThat(world.windowStore.upserted).as("no count").isEmpty();
        assertThat(world.envelopes).as("no event").isEmpty();
        assertThat(world.cases).isEmpty();
    }

    // ------------------------------------------------------------------ the door, composed

    /** Reached when the command's own transaction opens: the command ran. */
    static final class CommandReached extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CommandReached() {
            super("the command's transaction opened");
        }
    }

    private WithdrawalService.WithdrawalView withdraw() {
        Session current = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.PASSWORD, null);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(identity.value().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(RiskEvaluationWorld.IDS)))) {
            return service().withdraw(current, new WithdrawalRequest(method.toString(), "600.00", "EUR"),
                    "key-" + RiskEvaluationWorld.IDS.next());
        }
    }

    private WithdrawalStepUpAdvice.Proposed proposed(long minor) {
        return new WithdrawalStepUpAdvice.Proposed(party, identity.value(), product.id().value(),
                Money.ofMinorUnits(minor, EUR));
    }

    private WithdrawalService service() {
        WithdrawalDoorStepUp stepUpCheck = new WithdrawalDoorStepUp((unitOfWork, proposed) -> {
            proposals.add(proposed);
            return stepUp;
        }, recordStore(), participants(), products(), enrolments());
        Withdrawals engine = WithdrawalDoorComposition.engine(() -> {
            journal.add("command");
            return new CommandReached();
        });
        return new WithdrawalService(stepUpCheck, WithdrawalDoorComposition.provider(engine), participants(),
                refusing(com.finapp.payments.WithdrawalStore.class), enrolments(), identities(),
                refusing(com.finapp.party.PartyStore.class), new com.finapp.payments.TransactionRunner() {
                    @Override
                    public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                        journal.add("advice");
                        return work.apply(RiskEvaluationWorld.UNTOUCHABLE);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private IdempotencyRecordStore<Connection> recordStore() {
        return (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IdempotencyRecordStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("find")) {
                        keysAsked.add((IdempotencyKey) args[1]);
                        return recorded
                                ? Optional.of(new IdempotencyRecord((IdempotencyKey) args[1],
                                        RequestFingerprint.sha256("original".getBytes(StandardCharsets.UTF_8)),
                                        IdempotencyState.COMPLETED, StoredResponse.empty(), CorrelationId.of("flow"),
                                        CLOCK.instant(), CLOCK.instant().plusSeconds(86_400)))
                                : Optional.empty();
                    }
                    throw new AssertionError("the door reads the key's record only: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private PaymentParticipants<Connection> participants() {
        return (PaymentParticipants<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {PaymentParticipants.class}, (proxy, invoked, args) -> {
                    switch (invoked.getName()) {
                        case "walletOwnedBy":
                            assertThat(args[1]).isEqualTo(party);
                            return walletOwned
                                    ? Optional.of(new PaymentParticipants.Wallet(customer,
                                            LedgerAccountId.of(RiskEvaluationWorld.IDS.next()), EUR))
                                    : Optional.empty();
                        case "bankDestinationOwnedBy":
                            assertThat(args[2]).isEqualTo(method);
                            return instrumentOwned
                                    ? Optional.of(new ProviderReference("dest-door-tester"))
                                    : Optional.empty();
                        default:
                            throw new AssertionError("the door resolves the wallet and the instrument only: "
                                    + invoked.getName());
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private CustomerAccountStore<Connection> products() {
        return (CustomerAccountStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CustomerAccountStore.class}, (proxy, invoked, args) -> {
                    if (invoked.getName().equals("findLive")) {
                        assertThat(args[1]).isEqualTo(customer);
                        assertThat(args[2]).isEqualTo(ProductType.WALLET);
                        return Optional.of(product);
                    }
                    throw new AssertionError("the door reads the wallet's product only: " + invoked.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private MfaEnrolmentStore<Connection> enrolments() {
        return (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MfaEnrolmentStore.class}, (proxy, invoked, args) -> {
                    if (invoked.getName().equals("findActive")) {
                        return enrolled ? Optional.of("an active TOTP factor") : Optional.empty();
                    }
                    throw new AssertionError("the door reads the active factor only: " + invoked.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private IdentityStore<Connection> identities() {
        return (IdentityStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, invoked, args) -> {
                    if (invoked.getName().equals("findById")) {
                        return Optional.of(Identity.rehydrate(identity, party, new LoginIdentifier("door-tester"),
                                IdentityStatus.ACTIVE, CLOCK.instant(), CLOCK.instant()));
                    }
                    throw new AssertionError("the door reads the caller's identity only: " + invoked.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T refusing(Class<?> type) {
        return (T) Proxy.newProxyInstance(WithdrawalDoorAdviceTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, invoked, args) -> {
                    throw new AssertionError("the door never reaches " + type.getSimpleName() + "."
                            + invoked.getName());
                });
    }
}
