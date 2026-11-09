package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.EmailAddress;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcContactChannelStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcRecoveryRequestStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.SessionStore;
import com.finapp.merchant.FeeRate;
import com.finapp.merchant.FeeSchedule;
import com.finapp.merchant.FeeScheduleStore;
import com.finapp.merchant.FeeScheduleVersion;
import com.finapp.merchant.JdbcFeeScheduleStore;
import com.finapp.merchant.JdbcPaymentFeePinStore;
import com.finapp.merchant.JdbcPayoutDestinationStore;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantStorageException;
import com.finapp.merchant.MerchantTransactionRunner;
import com.finapp.merchant.PaymentFeePin;
import com.finapp.merchant.PayoutDestinationEffectuation;
import com.finapp.merchant.RefundFeePolicy;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseClockFrame;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import com.finapp.sharedkernel.security.Sensitive;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The five stores' bounds on the database's clock when an instance's clock disagrees (`P15-TSK-038`, {@code X-TSK-008},
 * ADR-0014): the skewed-instance database cases, written and owed to {@code P15-TST-002} (C14).
 *
 * <p>Since the statements bind no instant, an instance's {@code Clock} has no way into any of these decisions, which
 * {@code StoredBoundsAreTheDatabasesTest} holds. What is left to prove on PostgreSQL is that the database's clock alone
 * decides: each case runs the real statement in a session whose database clock has been moved
 * ({@link DatabaseClockFrame}), and <strong>asserts first that the instance's clock and the database's clock disagree in the
 * dangerous direction</strong>, so a case cannot pass because the two happened to agree. Moved back to the instance's clock
 * deciding, each one fails.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@DisplayName("stored bounds across a skewed clock (P15-TSK-038)")
class StoredBoundsClockSkewDatabaseTest {

    /** The instance's clock: the JVM's, which every statement here must ignore. */
    private static final Clock INSTANCE = Clock.system(ZoneOffset.UTC);

    private static final IdGenerator IDS = new IdGenerator(INSTANCE, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private final ContactChannelStore<Connection> channels = new JdbcContactChannelStore();
    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final AuditWriter<Connection> audit = new JdbcAuditWriter();
    private final OutboxWriter<Connection> outbox = new JdbcOutboxWriter();
    private final PasswordDeriver deriver = new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    private final ContactChannelService channelService = new ContactChannelService(channels, IDS, INSTANCE, RANDOMNESS, audit);
    private final RecoveryService recoveries =
            new RecoveryService(new JdbcRecoveryRequestStore(IDS), credentials, sessions, deriver, IDS, RANDOMNESS, audit, outbox);

    private final FeeScheduleStore<Connection> fees = new JdbcFeeScheduleStore();
    private final JdbcPaymentFeePinStore pins = new JdbcPaymentFeePinStore();
    private final JdbcPayoutDestinationStore destinations = new JdbcPayoutDestinationStore();

    @BeforeAll
    static void installTheMovedClock() {
        DatabaseClockFrame.install();
    }

    @AfterAll
    static void putTheClockBack() {
        DatabaseClockFrame.uninstall();
    }

    // ----------------------------------------------------------------- recovery

    @Test
    @DisplayName("a recovery token past its life on the database's clock is refused while this instance's clock says it is live")
    void aRecoveryTokenEndsOnTheDatabasesClock() throws Exception {
        LoginAndIdentity who = identityWithAVerifiedChannel();
        RecoveryService.Initiated initiated = initiate(who.login());
        Duration pastItsLife = RecoveryService.TOKEN_LIFETIME.plusMinutes(1);

        assertThat(Instant.now(INSTANCE))
                .as("precondition: this instance's clock still reads the token as live")
                .isBefore(initiated.request().expiresAt());
        assertThat(databaseNowAt(pastItsLife))
                .as("precondition: the database's clock, moved for this session, is past it")
                .isAfterOrEqualTo(initiated.request().expiresAt());

        assertThat(complete(pastItsLife, initiated))
                .as("expired on the clock that decides; one answer, as for every other refusal")
                .isFalse();
        assertThat(statusOf(initiated.request().id())).as("nothing consumed").isEqualTo("INITIATED");
        assertThat(complete(Duration.ZERO, initiated)).as("and on the real clock the token still spends once").isTrue();
        assertThat(complete(Duration.ZERO, initiated)).as("single-use, whatever the clock").isFalse();
    }

    @Test
    @DisplayName("a recovery request's bound is the database's: within one row exactly the lifetime, and the audit record its stamp")
    void aRecoveryBoundIsStampedByTheDatabase() throws Exception {
        LoginAndIdentity who = identityWithAVerifiedChannel();
        Duration anHourAhead = Duration.ofHours(1);

        RecoveryService.Initiated initiated = initiateAt(anHourAhead, who.login());

        assertThat(Duration.between(initiated.request().initiatedAt(), initiated.request().expiresAt()))
                .as("a bound minus its own row's instant: exact, and immune to a stepped clock")
                .isEqualTo(RecoveryService.TOKEN_LIFETIME);
        assertThat(Duration.between(Instant.now(INSTANCE), initiated.request().initiatedAt()))
                .as("stamped from the database's clock, an hour ahead of this instance's")
                .isGreaterThan(Duration.ofMinutes(59));

        assertThat(complete(anHourAhead, initiated)).isTrue();
        assertThat(completedAtEqualsTheAuditRecord(initiated.request().id()))
                .as("the row and the audit record carry the one instant")
                .isTrue();
    }

    // ----------------------------------------------------------------- contact channels

    @Test
    @DisplayName("a verification challenge past its life on the database's clock is refused while this instance's clock says it is live")
    void aChallengeEndsOnTheDatabasesClock() throws Exception {
        IdentityId identity = givenAnIdentity();
        ContactChannelService.Added added = addChannel(identity);
        Duration pastItsLife = ContactChannelService.CHALLENGE_LIFETIME.plusMinutes(1);

        assertThat(databaseNowAt(pastItsLife))
                .as("precondition: the database's clock is past the challenge's life, and this instance's is not")
                .isAfter(Instant.now(INSTANCE).plus(ContactChannelService.CHALLENGE_LIFETIME));

        assertThat(verify(pastItsLife, added)).as("one answer for every refusal").isEmpty();
        assertThat(verify(Duration.ZERO, added)).as("and live on the real clock").isPresent();
        assertThat(verify(Duration.ZERO, added)).as("single-use").isEmpty();
    }

    // ----------------------------------------------------------------- the card fee version

    @Test
    @DisplayName("the card fee version in force follows the database's clock, not an instance's: PI-002")
    void theVersionInForceIsTheDatabasesClock() throws Exception {
        Instant scheduled = Instant.now(INSTANCE).plus(Duration.ofMinutes(30));
        Priced priced = pricedMerchant(scheduled);
        Duration past = Duration.ofMinutes(31);

        assertThat(databaseNowAt(past))
                .as("precondition: the database's clock is past the scheduled version's start")
                .isAfter(scheduled);
        assertThat(Instant.now(INSTANCE)).as("precondition: this instance's is before it").isBefore(scheduled);

        assertThat(inForceAt(Duration.ZERO, priced.merchant())).as("the first version, on the real clock").contains(priced.first());
        assertThat(inForceAt(past, priced.merchant())).as("the scheduled one, on the moved clock").contains(priced.scheduled());
    }

    @Test
    @DisplayName("a version created to take effect immediately is in force from its commit even stamped ahead of the database")
    void anImmediateVersionIsInForceFromItsCommit() throws Exception {
        Duration fast = Duration.ofHours(2);
        MerchantId merchant = merchant();
        FeeSchedule schedule = FeeSchedule.create(IDS, "immediate " + UUID.randomUUID(), EUR, "fixture", INSTANCE);
        Clock aFastInstance = Clock.offset(INSTANCE, fast);
        FeeScheduleVersion immediate = version(schedule, 1, Optional.empty(), aFastInstance);
        store(schedule, immediate, merchant);

        assertThat(immediate.effectiveFrom())
                .as("precondition: stamped by an instance ahead of the database's clock")
                .isAfter(databaseNowAt(Duration.ZERO).plus(Duration.ofHours(1)));

        assertThat(inForceAt(Duration.ZERO, merchant)).contains(immediate);
    }

    @Test
    @DisplayName("V014: the pin is stamped by its own insert over any writer's value, and a version not yet in force is refused")
    void thePinIsBoundToTheDatabasesClock() throws Exception {
        Instant scheduled = Instant.now(INSTANCE).plus(Duration.ofHours(6));
        Priced priced = pricedMerchant(scheduled);
        UUID intent = IDS.next();
        Instant aForgedInstant = Instant.parse("2001-01-01T00:00:00Z");

        inTransactionAs(uow -> {
            assertThat(pins.insertIfAbsent(uow, pin(intent, priced.merchant(), priced.first(), aForgedInstant))).isTrue();
            assertThat(scalarBoolean(uow, "SELECT pinned_at >= transaction_timestamp() AND pinned_at <= clock_timestamp()"
                            + " FROM merchant.payment_fee_pin WHERE payment_intent_ref = ?", intent))
                    .as("the writer's value replaced by the insert's own stamp")
                    .isTrue();
            return null;
        });

        assertThatThrownBy(
                        () -> inTransactionAs(
                                uow -> pins.insertIfAbsent(uow, pin(IDS.next(), priced.merchant(), priced.scheduled(), Instant.now(INSTANCE)))))
                .as("a scheduled version is not in force by the pin's instant: refused for every writer")
                .isInstanceOf(MerchantStorageException.class);
    }

    // ----------------------------------------------------------------- the cooling-off

    @Test
    @DisplayName("a payout destination's cooling-off ends on the database's clock: an instance's cannot shorten or extend it")
    void aCoolingOffEndsOnTheDatabasesClock() throws Exception {
        MerchantId merchant = merchant();
        UUID approved = approvedCoolingOffFor(merchant, Duration.ofHours(1));
        Instant deadline = coolingOffUntil(approved);

        assertThat(Instant.now(INSTANCE))
                .as("precondition: this instance's clock is inside the cooling-off")
                .isBefore(deadline);
        effectuationAt(Duration.ZERO).sweep();
        assertThat(statusOfDestination(approved)).as("running on the database's clock").isEqualTo("APPROVED");

        Duration past = Duration.ofHours(1).plusMinutes(1);
        assertThat(databaseNowAt(past)).as("precondition: the database's clock is past the deadline").isAfterOrEqualTo(deadline);
        effectuationAt(past).sweep();
        assertThat(statusOfDestination(approved))
                .as("the deadline passed on the clock that decides, whatever this instance's said")
                .isEqualTo("EFFECTIVE");
    }

    // -----------------------------------------------------------------

    private record LoginAndIdentity(LoginIdentifier login, IdentityId identity) {}

    private record Priced(MerchantId merchant, FeeScheduleVersion first, FeeScheduleVersion scheduled) {}

    private PayoutDestinationEffectuation effectuationAt(Duration frame) {
        return new PayoutDestinationEffectuation(
                new MerchantTransactionRunner() {
                    @Override
                    public <R> R inTransaction(Function<Connection, R> work) {
                        return DatabaseClockFrame.inOneTransaction(frame, work);
                    }
                },
                destinations,
                audit,
                IDS,
                1000);
    }

    private RecoveryService.Initiated initiate(LoginIdentifier login) throws Exception {
        return initiateAt(Duration.ZERO, login);
    }

    private RecoveryService.Initiated initiateAt(Duration frame, LoginIdentifier login) throws Exception {
        return scoped(() -> DatabaseClockFrame.inOneTransaction(frame, uow -> recoveries.initiate(uow, login).orElseThrow()));
    }

    private boolean complete(Duration frame, RecoveryService.Initiated initiated) throws Exception {
        return scoped(
                () -> DatabaseClockFrame.inOneTransaction(
                        frame,
                        uow -> recoveries.complete(
                                uow,
                                initiated.request().id(),
                                initiated.token().presentedValue(),
                                new RawPassword(Sensitive.of("a-replacement-password")))));
    }

    private Optional<com.finapp.identity.ContactChannel> verify(Duration frame, ContactChannelService.Added added) throws Exception {
        return scoped(
                () -> DatabaseClockFrame.inOneTransaction(frame, uow -> channelService.verify(uow, added.challenge().presentedValue())));
    }

    private ContactChannelService.Added addChannel(IdentityId identity) throws Exception {
        return scoped(() -> DatabaseClockFrame.inOneTransaction(
                Duration.ZERO, uow -> channelService.add(uow, identity, freshAddress())));
    }

    private LoginAndIdentity identityWithAVerifiedChannel() throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        IdentityId id = identityNamed(login);
        ContactChannelService.Added added = addChannel(id);
        assertThat(verify(Duration.ZERO, added)).isPresent();
        return new LoginAndIdentity(new LoginIdentifier(login), id);
    }

    private IdentityId givenAnIdentity() throws SQLException {
        return identityNamed("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
    }

    private IdentityId identityNamed(String login) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Ada', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now(), now())", identity, party, login);
        }
        return IdentityId.of(identity);
    }

    private static EmailAddress freshAddress() {
        return new EmailAddress("ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@example.com");
    }

    private Priced pricedMerchant(Instant scheduled) throws Exception {
        MerchantId merchant = merchant();
        FeeSchedule schedule = FeeSchedule.create(IDS, "skew " + UUID.randomUUID(), EUR, "fixture", INSTANCE);
        FeeScheduleVersion first = version(schedule, 1, Optional.empty(), INSTANCE);
        FeeScheduleVersion later = version(schedule, 2, Optional.of(scheduled), INSTANCE);
        store(schedule, first, merchant);
        insertVersion(later);
        return new Priced(merchant, first, later);
    }

    private static FeeScheduleVersion version(FeeSchedule schedule, int number, Optional<Instant> effectiveFrom, Clock clock) {
        return FeeScheduleVersion.create(
                IDS, schedule, number, FeeRate.of(new BigDecimal("0.029")), Money.ofMinorUnits(30L, EUR),
                RoundingPolicy.HALF_EVEN, RefundFeePolicy.RETAINED, effectiveFrom, "fixture", clock);
    }

    private void store(FeeSchedule schedule, FeeScheduleVersion version, MerchantId merchant) throws Exception {
        inTransactionAs(uow -> {
            fees.insertSchedule(uow, schedule);
            assertThat(fees.insertVersionIfNumberIsFree(uow, version)).isPresent();
            fees.assign(uow, merchant, Optional.empty(), schedule.id(), "terms", Instant.now(INSTANCE));
            return null;
        });
    }

    private void insertVersion(FeeScheduleVersion version) throws Exception {
        inTransactionAs(uow -> {
            assertThat(fees.insertVersionIfNumberIsFree(uow, version)).isPresent();
            return null;
        });
    }

    private Optional<FeeScheduleVersion> inForceAt(Duration frame, MerchantId merchant) throws Exception {
        return scoped(() -> DatabaseClockFrame.inOneTransaction(frame, uow -> fees.findEffectiveVersionFor(uow, merchant)));
    }

    private static PaymentFeePin pin(UUID intent, MerchantId merchant, FeeScheduleVersion version, Instant pinnedAt) {
        return new PaymentFeePin(intent, merchant, version.id(), Money.ofMinorUnits(10_00L, EUR), pinnedAt, "fixture");
    }

    private static MerchantId merchant() throws SQLException {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO merchant.merchant (id, party_ref, legal_name, display_name, settlement_currency, status,"
                    + " created_at, status_changed_at) VALUES (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())", id, UUID.randomUUID());
        }
        return MerchantId.of(id);
    }

    private static UUID approvedCoolingOffFor(MerchantId merchant, Duration remaining) throws SQLException {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO merchant.payout_destination (id, merchant_id, destination_reference, display_suffix, status,"
                    + " proposed_by, proposed_at, proposal_reason, approved_by, approved_at, cooling_off_until) VALUES"
                    + " (?, ?, ?, '4000', 'APPROVED', 'fixture-a', now() - interval '5 days', 'fixture', 'fixture-b',"
                    + " now() - interval '4 days', now() + (? * interval '1 microsecond'))",
                    id, merchant.value(), "pdr_skew" + id.toString().substring(0, 8), remaining.toNanos() / 1_000);
        }
        return id;
    }

    private static Instant coolingOffUntil(UUID destination) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read = app.prepareStatement(
                        "SELECT cooling_off_until FROM merchant.payout_destination WHERE id = ?")) {
            read.setObject(1, destination);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static String statusOfDestination(UUID destination) throws SQLException {
        return text("SELECT status FROM merchant.payout_destination WHERE id = ?", destination);
    }

    private static String statusOf(RecoveryRequestId id) throws SQLException {
        return text("SELECT status FROM identity.recovery_request WHERE id = ?", id.value());
    }

    private static boolean completedAtEqualsTheAuditRecord(RecoveryRequestId id) throws SQLException {
        return "t".equals(text(
                "SELECT (r.completed_at = a.occurred_at) FROM identity.recovery_request r JOIN platform.audit_record a"
                        + " ON a.target_id = r.id::text AND a.operation = 'identity.RecoveryCompleted' WHERE r.id = ?",
                id.value()));
    }

    /** The database's clock in a session moved by {@code frame}: the precondition every case asserts first. */
    private static Instant databaseNowAt(Duration frame) {
        return DatabaseClockFrame.inOneTransaction(frame, uow -> {
            try (PreparedStatement read = uow.prepareStatement("SELECT statement_timestamp()"); ResultSet row = read.executeQuery()) {
                row.next();
                return row.getObject(1, OffsetDateTime.class).toInstant();
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private <R> R inTransactionAs(Function<Connection, R> work) throws Exception {
        return scoped(() -> DatabaseClockFrame.inOneTransaction(Duration.ZERO, work));
    }

    private interface Work<R> {
        R run() throws Exception;
    }

    private static <R> R scoped(Work<R> work) throws Exception {
        try (CorrelationContext.Scope flow =
                        CorrelationContext.enter(Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
                SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            return work.run();
        }
    }

    private static boolean scalarBoolean(Connection uow, String sql, Object argument) {
        try (PreparedStatement read = uow.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String text(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(sql)) {
            read.setObject(1, argument);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
