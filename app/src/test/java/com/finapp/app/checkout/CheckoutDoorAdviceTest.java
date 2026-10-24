package com.finapp.app.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.checkout.CheckoutSession;
import com.finapp.checkout.CheckoutSessionId;
import com.finapp.checkout.CheckoutSessionStatus;
import com.finapp.checkout.CheckoutSessionToken;
import com.finapp.identity.IdentityErrorCode;
import com.finapp.identity.IdentityId;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.payments.InstrumentKind;
import com.finapp.payments.PaymentIntent;
import com.finapp.payments.PaymentIntentStatus;
import com.finapp.payments.PaymentIntentStore;
import com.finapp.payments.PaymentParticipants;
import com.finapp.platform.api.ApiException;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The checkout confirmation door's step-up advice (`P13-TSK-010`; {@code PHASE_13_PLAN.md} section 12.3; ADR-0108
 * decision 3; {@code INV-IDEM-01}, {@code INV-RSK-08}) over in-memory reads: a payment risk would challenge under a
 * session that cannot satisfy it is answered identity's {@code 403} before any row exists - with identity's words, the
 * factor to use and nothing of why - for the caller's own card or wallet; a payment already judged, an offer dead or
 * unknown, an instrument not the caller's and a pay-by-bank instrument are never advised (the command answers its own).
 * The real door in the real confirmation, and its {@code 403} writing nothing, are the owed
 * {@code PaymentRiskSeamDatabaseTest#theDoorsStepUpWritesNothing}'s (C5).
 */
@DisplayName("the checkout confirmation door's step-up advice (P13-TSK-010)")
class CheckoutDoorAdviceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final Money AMOUNT = Money.ofMinorUnits(600_00, EUR);

    private final UUID party = IDS.next();
    private final IdentityId identity = IdentityId.of(IDS.next());
    private final UUID merchant = IDS.next();
    private final UUID card = IDS.next();
    private final UUID walletCustomer = IDS.next();
    private final CustomerAccount walletProduct =
            CustomerAccount.open(IDS, CLOCK, walletCustomer, ProductType.WALLET);
    private final CheckoutSessionToken token = CheckoutSessionToken.of("tok-" + IDS.next());

    private final List<CheckoutStepUpAdvice.Proposed> asked = new ArrayList<>();
    private boolean challenges = true;
    private boolean enrolled = true;
    private final Map<UUID, InstrumentKind> instruments = new HashMap<>();
    private final Map<UUID, PaymentIntent> intents = new HashMap<>();
    private Optional<CheckoutSession> offer = Optional.empty();

    private final CheckoutDoorStepUp door = new CheckoutDoorStepUp(
            (unitOfWork, proposed) -> {
                asked.add(proposed);
                return challenges;
            },
            (unitOfWork, presented) -> presented.equals(token) ? offer : Optional.empty(),
            intentStore(),
            participants(),
            products(),
            enrolments(),
            CLOCK);

    // ------------------------------------------------------------------ the advice

    @Test
    @DisplayName("a fresh card confirmation risk would challenge: STEP_UP for an enrolled identity, ENROL_AND_STEP_UP"
            + " otherwise - asked with the caller's party, identity, the offer's merchant and amount, and no wallet")
    void aChallengedCardIsSteppedUp() {
        instruments.put(card, InstrumentKind.CARD_TOKEN);
        offer = Optional.of(session(CheckoutSessionStatus.OPEN, Optional.empty()));
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.STEP_UP);
        assertThat(asked).singleElement().satisfies(proposed -> {
            assertThat(proposed.party()).isEqualTo(party);
            assertThat(proposed.identity()).isEqualTo(identity.value());
            assertThat(proposed.merchant()).isEqualTo(merchant);
            assertThat(proposed.walletAccount()).isEmpty();
            assertThat(proposed.amount()).isEqualTo(AMOUNT);
        });
        enrolled = false;
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.ENROL_AND_STEP_UP);
        challenges = false;
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);
    }

    @Test
    @DisplayName("a wallet confirmation is advised over the caller's own wallet, named by its product's customer"
            + " account - the subject the confirmation's T-eval will count")
    void aWalletIsAdvisedByItsCustomerAccount() {
        offer = Optional.of(session(CheckoutSessionStatus.OPEN, Optional.empty()));
        assertThat(advise(Optional.empty())).isEqualTo(CheckoutDoorStepUp.Answer.STEP_UP);
        assertThat(asked).singleElement().satisfies(proposed ->
                assertThat(proposed.walletAccount()).contains(walletProduct.id().value()));
    }

    @Test
    @DisplayName("a session PAYMENT_PENDING over an intent still awaiting confirmation (its Tx1 rolled back, nothing"
            + " judged) is advised with the intent's own instrument")
    void anUndispatchedIntentIsAdvised() {
        instruments.put(card, InstrumentKind.CARD_TOKEN);
        PaymentIntent waiting = intent();
        offer = Optional.of(session(CheckoutSessionStatus.PAYMENT_PENDING, Optional.of(waiting.id().value())));
        assertThat(advise(Optional.empty())).as("the body's choice is not the intent's").isEqualTo(
                CheckoutDoorStepUp.Answer.STEP_UP);
        assertThat(asked).singleElement().satisfies(proposed -> assertThat(proposed.walletAccount()).isEmpty());
    }

    // ------------------------------------------------------------------ never advised

    @Test
    @DisplayName("a judged payment is never advised (INV-IDEM-01): an intent dispatched, refused or paid converges or"
            + " is refused by the command")
    void aJudgedPaymentIsNeverAdvised() {
        instruments.put(card, InstrumentKind.CARD_TOKEN);
        PaymentIntent dispatched = intent().confirm();
        intents.put(dispatched.id().value(), dispatched);
        offer = Optional.of(session(CheckoutSessionStatus.PAYMENT_PENDING, Optional.of(dispatched.id().value())));
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);

        PaymentIntent refused = intent().refuseByRisk();
        intents.put(refused.id().value(), refused);
        offer = Optional.of(session(CheckoutSessionStatus.PAYMENT_PENDING, Optional.of(refused.id().value())));
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);

        offer = Optional.of(session(CheckoutSessionStatus.COMPLETED, Optional.of(refused.id().value())));
        assertThat(advise(Optional.of(card))).isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);
        assertThat(asked).as("risk never asked").isEmpty();
    }

    @Test
    @DisplayName("no advice over an unknown token, an expired offer, a stranger's card, a bank instrument or a missing"
            + " wallet - the command answers its own refusal")
    void noAdviceIsAnOracle() {
        assertThat(advise(Optional.of(card))).as("unknown token").isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);

        offer = Optional.of(session(CheckoutSessionStatus.OPEN, Optional.empty(), CLOCK.instant().minusSeconds(1)));
        instruments.put(card, InstrumentKind.CARD_TOKEN);
        assertThat(advise(Optional.of(card))).as("expired offer").isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);

        offer = Optional.of(session(CheckoutSessionStatus.OPEN, Optional.empty()));
        assertThat(advise(Optional.of(IDS.next()))).as("not the caller's card")
                .isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);
        UUID bank = IDS.next();
        instruments.put(bank, InstrumentKind.BANK_ACCOUNT);
        assertThat(advise(Optional.of(bank))).as("a pay-by-bank checkout is not judged")
                .isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);
        offer = Optional.of(session(CheckoutSessionStatus.OPEN, Optional.empty(), CLOCK.instant().plusSeconds(600),
                Money.ofMinorUnits(600_00, CurrencyCode.of("USD"))));
        assertThat(advise(Optional.empty())).as("no wallet in the offer's currency")
                .isEqualTo(CheckoutDoorStepUp.Answer.PROCEED);
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("the refusal is identity's 403 with identity's words - which factor, never why (INV-RSK-08)")
    void theStepUpNamesNoRiskWord() {
        for (CheckoutDoorStepUp.Answer answer : List.of(
                CheckoutDoorStepUp.Answer.STEP_UP, CheckoutDoorStepUp.Answer.ENROL_AND_STEP_UP)) {
            ApiException refusal = CheckoutDoorStepUp.refusal(answer);
            assertThat(refusal.errorCode()).isEqualTo(IdentityErrorCode.ASSURANCE_REQUIRED);
            String words = (refusal.getMessage() + " " + refusal.clientDetail().orElse(""))
                    .toLowerCase(java.util.Locale.ROOT);
            for (String risk : List.of("risk", "rule", "score", "challenge", "fraud", "case", "alert", "velocity")) {
                assertThat(words).as(answer + " names no " + risk).doesNotContain(risk);
            }
        }
    }

    // ------------------------------------------------------------------ the reads

    private CheckoutDoorStepUp.Answer advise(Optional<UUID> paymentMethodId) {
        return door.advise(UNTOUCHED, party, identity, token, paymentMethodId);
    }

    private CheckoutSession session(CheckoutSessionStatus status, Optional<UUID> intent) {
        return session(status, intent, CLOCK.instant().plus(Duration.ofMinutes(10)));
    }

    private CheckoutSession session(CheckoutSessionStatus status, Optional<UUID> intent, Instant expiresAt) {
        return session(status, intent, expiresAt, AMOUNT);
    }

    private CheckoutSession session(
            CheckoutSessionStatus status, Optional<UUID> intent, Instant expiresAt, Money amount) {
        return CheckoutSession.rehydrate(CheckoutSessionId.next(IDS), merchant, amount, "one thing", IDS.next(),
                Sensitive.of("hash"), CheckoutSessionToken.ALGORITHM, intent, Optional.empty(), status, expiresAt,
                CLOCK.instant().minusSeconds(60), CLOCK.instant().minusSeconds(30));
    }

    private PaymentIntent intent() {
        PaymentIntent intent = PaymentIntent.create(IDS, CLOCK, party, walletCustomer, card,
                LedgerAccountId.of(IDS.next()), AMOUNT);
        intents.put(intent.id().value(), intent);
        return intent;
    }

    @SuppressWarnings("unchecked")
    private PaymentIntentStore<Connection> intentStore() {
        return (PaymentIntentStore<Connection>) proxy(PaymentIntentStore.class, (name, args) -> {
            if (!name.equals("findOwned")) {
                throw new AssertionError("the door reads the caller's own intent only: " + name);
            }
            PaymentIntent found = intents.get(((com.finapp.payments.PaymentIntentId) args[1]).value());
            return Optional.ofNullable(found).filter(row -> row.partyId().equals(args[2]));
        });
    }

    @SuppressWarnings("unchecked")
    private PaymentParticipants<Connection> participants() {
        return (PaymentParticipants<Connection>) proxy(PaymentParticipants.class, (name, args) -> switch (name) {
            case "instrumentKindOwnedBy" -> args[1].equals(party)
                    ? Optional.ofNullable(instruments.get(args[2])) : Optional.empty();
            case "payerWalletOwnedBy" -> args[1].equals(party) && args[2].equals(EUR)
                    ? Optional.of(new PaymentParticipants.Wallet(walletCustomer, LedgerAccountId.of(IDS.next()), EUR))
                    : Optional.empty();
            default -> throw new AssertionError("the door resolves the caller's card or wallet only: " + name);
        });
    }

    @SuppressWarnings("unchecked")
    private CustomerAccountStore<Connection> products() {
        return (CustomerAccountStore<Connection>) proxy(CustomerAccountStore.class, (name, args) -> {
            if (!name.equals("findLive")) {
                throw new AssertionError("the door reads the live wallet product only: " + name);
            }
            return args[1].equals(walletCustomer) && args[2] == ProductType.WALLET
                    ? Optional.of(walletProduct) : Optional.empty();
        });
    }

    @SuppressWarnings("unchecked")
    private MfaEnrolmentStore<Connection> enrolments() {
        return (MfaEnrolmentStore<Connection>) proxy(MfaEnrolmentStore.class, (name, args) -> {
            if (!name.equals("findActive")) {
                throw new AssertionError("the door reads the active enrolment only: " + name);
            }
            return enrolled ? Optional.of(new Object()) : Optional.empty();
        });
    }

    private interface Answering {
        Object answer(String name, Object[] args);
    }

    private static Object proxy(Class<?> type, Answering answering) {
        return Proxy.newProxyInstance(CheckoutDoorAdviceTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> method.getName().equals("toString")
                        ? type.getSimpleName()
                        : answering.answer(method.getName(), args));
    }

    /** The door's unit of work: every read here is in memory, so a direct use fails the suite. */
    private static final Connection UNTOUCHED = (Connection) Proxy.newProxyInstance(
            CheckoutDoorAdviceTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("toString")) {
                    return "UNTOUCHED";
                }
                throw new AssertionError("the door used the unit of work directly: " + method.getName());
            });
}
