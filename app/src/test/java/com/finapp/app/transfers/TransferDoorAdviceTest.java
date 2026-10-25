package com.finapp.app.transfers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finapp.app.risk.RiskEvaluationWorld;
import com.finapp.app.risk.RiskTransferStepUpAdvice;
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
import com.finapp.transfers.TransferParticipants;
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
 * The transfer door's step-up before the command (`P13-TSK-008`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108
 * decision 3; {@code INV-IDEM-01}, {@code INV-RSK-08}): a challenge the session cannot satisfy is
 * {@code 403 identity.AssuranceRequired} answered from the door's own transaction - the command's transaction never
 * opens, its key is never claimed, nothing is written; a retry of a recorded key is never advised (it replays); a
 * source that is not the caller's is never advised over; the 403 names a second factor and nothing of risk; and the
 * advice port, answered by T-advise, writes nothing. The real door over HTTP is the owed
 * {@code TransferRiskSeamDatabaseTest#anUnsatisfiedChallengeIsRefusedAtTheDoor} (C5).
 */
@DisplayName("the transfer door's step-up: 403 before the command, nothing written (P13-TSK-008)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class TransferDoorAdviceTest {

    private static final Clock CLOCK = RiskEvaluationWorld.CLOCK;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    /** Reached by the command's executor when it claims the key: the command ran. */
    static final class CommandReached extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CommandReached() {
            super("the command claimed its key");
        }
    }

    private final UUID party = RiskEvaluationWorld.IDS.next();
    private final IdentityId identity = IdentityId.of(RiskEvaluationWorld.IDS.next());
    private final UUID source = RiskEvaluationWorld.IDS.next();
    private final UUID destination = RiskEvaluationWorld.IDS.next();
    private final List<String> journal = new ArrayList<>();
    private final List<TransferStepUpAdvice.Proposed> proposals = new ArrayList<>();

    private boolean recorded;
    private boolean owned = true;
    private boolean enrolled = true;
    private boolean stepUp;

    // ------------------------------------------------------------------ the door's check

    @Test
    @DisplayName("a challenge the session cannot satisfy is 403 identity.AssuranceRequired from the door's one"
            + " transaction: the command's transaction never opens, its key is never claimed, nothing is written")
    void anUnsatisfiedChallengeIsAnsweredBeforeTheCommand() {
        stepUp = true;
        ApiException refused = catchThrowableOfType(ApiException.class, this::create);
        assertThat(refused.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
        assertThat(refused.errorCode().status()).isEqualTo(403);
        assertThat(journal).as("one transaction - the advice's - and no claim").containsExactly("begin", "commit");
        assertThat(proposals).singleElement().satisfies(proposed -> {
            assertThat(proposed.party()).isEqualTo(party);
            assertThat(proposed.identity()).isEqualTo(identity.value());
            assertThat(proposed.sourceAccount()).isEqualTo(source);
            assertThat(proposed.destinationAccount()).isEqualTo(destination);
            assertThat(proposed.amount()).isEqualTo(Money.ofMinorUnits(600_00, EUR));
        });
    }

    @Test
    @DisplayName("no step-up needed: the advice's transaction, then the command's - which claims its key")
    void noStepUpRunsTheCommand() {
        assertThatThrownBy(this::create).isInstanceOf(CommandReached.class);
        assertThat(journal).containsExactly("begin", "commit", "begin", "claim", "rollback");
    }

    @Test
    @DisplayName("a retry of a recorded key is never advised (INV-IDEM-01): the command replays or conflicts, whatever"
            + " the windows now say")
    void aRetryIsNeverAdvised() {
        stepUp = true;
        recorded = true;
        assertThatThrownBy(this::create).isInstanceOf(CommandReached.class);
        assertThat(proposals).isEmpty();
    }

    @Test
    @DisplayName("a source that is not the caller's is never advised over: the command answers UnknownSource as always")
    void anUnownedSourceIsNeverAdvisedOver() {
        stepUp = true;
        owned = false;
        assertThatThrownBy(this::create).isInstanceOf(CommandReached.class);
        assertThat(proposals).isEmpty();
    }

    @Test
    @DisplayName("the 403 names the second factor and the step the customer takes - enrolled or not - and nothing of"
            + " risk: no decision, rule, reason, score or assessment (INV-RSK-08)")
    void theStepUpNamesNoRiskWord() {
        stepUp = true;
        ApiException enrolledRefusal = catchThrowableOfType(ApiException.class, this::create);
        enrolled = false;
        ApiException unenrolledRefusal = catchThrowableOfType(ApiException.class, this::create);
        assertThat(enrolledRefusal.clientDetail()).hasValueSatisfying(
                detail -> assertThat(detail).contains("step up with your enrolled factor"));
        assertThat(unenrolledRefusal.clientDetail()).hasValueSatisfying(
                detail -> assertThat(detail).contains("enrol one"));
        for (ApiException refusal : List.of(enrolledRefusal, unenrolledRefusal)) {
            String said = (refusal.getMessage() + " " + refusal.clientDetail().orElseThrow())
                    .toLowerCase(java.util.Locale.ROOT);
            for (String word : List.of("risk", "challenge", "deny", "review", "score", "rule", "assessment", "amount",
                    "velocity", "limit")) {
                assertThat(said).as(word).doesNotContain(word);
            }
        }
    }

    // ------------------------------------------------------------------ the advice port

    @Test
    @DisplayName("the advice port answered by T-advise: a EUR 600 transfer from a PASSWORD session needs a step-up, from"
            + " a MULTI_FACTOR one it does not, under EUR 500 a password session only to a payee never paid (-013) -"
            + " and nothing is sealed, counted or announced")
    void theAdviceWritesNothing() throws Exception {
        RiskEvaluationWorld world = new RiskEvaluationWorld();
        world.activate(RiskOperation.TRANSFER);
        RiskTransferStepUpAdvice advice = new RiskTransferStepUpAdvice(world.evaluation);
        Actor actor = new Actor(identity.value().toString(), ActorType.CUSTOMER);
        Session password = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.PASSWORD, null);
        Session mfa = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.MULTI_FACTOR, null);
        world.sessions = new com.finapp.app.risk.IdentitySessionAssurance(
                RiskEvaluationWorld.liveSessions(new ArrayList<>(), password, mfa));
        try (SecurityContext.Scope scope = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(RiskEvaluationWorld.IDS)))) {
            try (AutoCloseable presented = RiskEvaluationWorld.presenting(password)) {
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(600_00))).isTrue();
                // P13-TSK-013: a payee never paid is NEW_PAYEE (250); with the password session's 100 the CHALLENGE band.
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(100_00)))
                        .as("a new payee from a password session").isTrue();
                world.windowStore.countedAt(com.finapp.risk.VelocityDimension.DESTINATION, destination.toString(),
                        RiskEvaluationWorld.CLOCK.instant().minus(java.time.Duration.ofDays(60)),
                        Money.ofMinorUnits(10_00, EUR));
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(100_00)))
                        .as("under EUR 500 to a payee paid before, nobody").isFalse();
            }
            try (AutoCloseable presented = RiskEvaluationWorld.presenting(mfa)) {
                assertThat(advice.stepUpRequired(RiskEvaluationWorld.UNTOUCHABLE, proposed(600_00))).isFalse();
            }
        }
        assertThat(world.assessmentStore.all()).as("no assessment").isEmpty();
        assertThat(world.windowStore.upserted).as("no count").isEmpty();
        assertThat(world.envelopes).as("no event").isEmpty();
        assertThat(world.cases).isEmpty();
    }

    // ------------------------------------------------------------------ the door, composed

    private TransferService.TransferView create() {
        Session current = RiskEvaluationWorld.session(identity.value(), AssuranceLevel.PASSWORD, null);
        try (SecurityContext.Scope scope = SecurityContext.enter(
                        new Actor(identity.value().toString(), ActorType.CUSTOMER));
                CorrelationContext.Scope flow = CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(RiskEvaluationWorld.IDS)))) {
            return service().create(current, new TransferCreateRequest(source.toString(), destination.toString(), null,
                    "600.00", "EUR", "rent"), "key-" + RiskEvaluationWorld.IDS.next());
        }
    }

    private TransferStepUpAdvice.Proposed proposed(long minor) {
        return new TransferStepUpAdvice.Proposed(party, identity.value(), source, destination,
                Money.ofMinorUnits(minor, EUR));
    }

    private TransferService service() {
        IdempotencyRecordStore<Connection> records = recordStore();
        TransferParticipants<Connection> participants = participants();
        TransferDoorStepUp stepUpCheck = new TransferDoorStepUp((unitOfWork, proposed) -> {
            proposals.add(proposed);
            return stepUp;
        }, records, participants, enrolments());
        return TransferDoorComposition.service(stepUpCheck, records, participants, identities(), journal, CLOCK);
    }

    @SuppressWarnings("unchecked")
    private IdempotencyRecordStore<Connection> recordStore() {
        return (IdempotencyRecordStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IdempotencyRecordStore.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> recorded
                            ? Optional.of(new IdempotencyRecord((IdempotencyKey) args[1],
                                    RequestFingerprint.sha256("original".getBytes(StandardCharsets.UTF_8)),
                                    IdempotencyState.COMPLETED, StoredResponse.empty(), CorrelationId.of("flow"),
                                    CLOCK.instant(), CLOCK.instant().plusSeconds(86_400)))
                            : Optional.empty();
                    case "claim" -> {
                        journal.add("claim");
                        throw new CommandReached();
                    }
                    default -> throw new AssertionError("the door reads the key's record only: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private TransferParticipants<Connection> participants() {
        return (TransferParticipants<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {TransferParticipants.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sourceOwnedBy")) {
                        assertThat(args[1]).isEqualTo(party);
                        assertThat(args[2]).isEqualTo(source);
                        return owned
                                ? Optional.of(new TransferParticipants.Source(RiskEvaluationWorld.IDS.next(),
                                        new TransferParticipants.Side(LedgerAccountId.of(RiskEvaluationWorld.IDS.next()),
                                                EUR, true)))
                                : Optional.empty();
                    }
                    throw new AssertionError("the door resolves the source only: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private MfaEnrolmentStore<Connection> enrolments() {
        return (MfaEnrolmentStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {MfaEnrolmentStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findActive")) {
                        // Only presence is read: an active factor exists, or none does.
                        return enrolled ? Optional.of("an active TOTP factor") : Optional.empty();
                    }
                    throw new AssertionError("the door reads the active factor only: " + method.getName());
                });
    }

    @SuppressWarnings("unchecked")
    private IdentityStore<Connection> identities() {
        return (IdentityStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {IdentityStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findById")) {
                        return Optional.of(Identity.rehydrate(identity, party, new LoginIdentifier("door-tester"),
                                IdentityStatus.ACTIVE, CLOCK.instant(), CLOCK.instant()));
                    }
                    throw new AssertionError("the door reads the caller's identity only: " + method.getName());
                });
    }
}
