package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The BNPL application's store against a real database (`P12-TSK-010`; {@code bnpl V003}, {@code BNPL_LIFECYCLES.md}
 * section 4.2; {@code INV-BNPL-03}, {@code INV-LIFE-01}, {@code INV-LIFE-02}, {@code INV-HIST-01}): the machine's three
 * ranks - the store's conditional edges, the every-writer edge trigger and the status {@code CHECK} - and its history,
 * held for every role by raw SQL: born {@code SUBMITTED} on the product's {@code ACTIVE} terms version with a plan it
 * offers, never committed {@code SUBMITTED}, only the machine's edges, terminal states final, the identity frozen, a
 * permit moved only while awaiting a decision, the closure reason said exactly for {@code CLOSED_UNDECIDED}, one open
 * application per session and per party and product, one per credit request, every stamp the database's, the history
 * append-only. The domain's races and credit's real door are {@code app}'s {@code BnplApplicationDatabaseTest}'s.
 *
 * <p>Written by `P12-TSK-010` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction for
 * the task). Every case makes its own sessions, parties and credit request ids, and brings each product's terms into
 * force through the administration itself (one person proposes, another activates) - never assuming an earlier case's.
 */
@Tag("database")
@DisplayName("the BNPL application's store: three ranks and the history (P12-TSK-010)")
class BnplApplicationStoreDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcBnplApplicationStore STORE = new JdbcBnplApplicationStore();
    private static final BnplTermsAdministration TERMS = new BnplTermsAdministration(new JdbcBnplTermsStore(),
            new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK, BnplTermsFixtures.BOUNDS);

    private static BnplTermsVersionId payIn4;
    private static BnplTermsVersionId payInN;

    @BeforeAll
    static void termsInForce() {
        payIn4 = activate(BnplTermsFixtures.payIn4().build());
        payInN = activate(BnplTermsFixtures.payInN("0.1999")
                .payInN(Optional.of(BnplTermsFixtures.payInNPart(List.of(3, 6), 15))).build());
    }

    @Test
    @DisplayName("born SUBMITTED and moved AWAITING_DECISION in one transaction, both history rows, every stamp the"
            + " database's - the permit due at birth; read back owner-scoped, another party's empty")
    void bornSubmittedMovedAwaiting() throws SQLException {
        UUID party = UUID.randomUUID();
        BnplApplicationId id = born(party, UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4);
        BnplApplication read = inOneTransaction(uow -> STORE.ownedBy(uow, id, party)).orElseThrow();
        assertThat(read.status()).isEqualTo(BnplApplicationStatus.AWAITING_DECISION);
        assertThat(read.termsVersionId()).isEqualTo(payIn4);
        assertThat(read.closedAt()).isEmpty();
        Optional<BnplApplication> stranger = inOneTransaction(uow -> STORE.ownedBy(uow, id, UUID.randomUUID()));
        assertThat(stranger).isEmpty();
        assertThat(scalar("SELECT string_agg(coalesce(from_status, '-') || '>' || to_status, ',' ORDER BY occurred_at)"
                + " FROM bnpl.bnpl_application_event WHERE application_id = '" + id.value() + "'"))
                .isEqualTo("->SUBMITTED,SUBMITTED>AWAITING_DECISION");
        assertThat(scalar("SELECT (next_step_at = submitted_at AND submitted_at <= clock_timestamp())::text"
                + " FROM bnpl.bnpl_application WHERE id = '" + id.value() + "'")).isEqualTo("true");
    }

    @Test
    @DisplayName("a row still SUBMITTED never commits - the deferred trigger refuses it for every writer")
    void neverCommittedSubmitted() {
        assertThatThrownBy(() -> inOneTransaction(uow -> STORE.insert(uow, application(UUID.randomUUID(),
                UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4))))
                .hasRootCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("one open application per session - SUBMITTED, AWAITING_DECISION and OFFERED hold it, a terminal one frees"
            + " it - and per party and product, and one per credit request: a conflict is nothing written")
    void theArbiters() throws SQLException {
        UUID session = UUID.randomUUID();
        UUID party = UUID.randomUUID();
        BnplApplicationId first = born(party, session, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4);
        assertThat(inserted(application(UUID.randomUUID(), session,
                BnplProduct.BNPL_PAY_IN_N, BnplPlan.PAY_IN_3_MONTHS, payInN))).as("another party, the same session")
                .isFalse();
        assertThat(inserted(application(party, UUID.randomUUID(),
                BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4))).as("the same party and product").isFalse();
        UUID request = UUID.randomUUID();
        born(UUID.randomUUID(), UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4, request);
        assertThat(inserted(new BnplApplicationStore.NewApplication(
                BnplApplicationId.next(IDS), UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4,
                UUID.randomUUID(), UUID.randomUUID(), request, payIn4, customer(UUID.randomUUID()), correlation())))
                .as("one application per credit request").isFalse();

        // bnpl V004 (P12-TSK-011): -> OFFERED only beside its offer, so the offer is born with it.
        offered(first);
        assertThat(inserted(application(UUID.randomUUID(), session,
                BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4))).as("an offer holds its session").isFalse();
        UUID freed = UUID.randomUUID();
        BnplApplicationId declined = born(UUID.randomUUID(), freed, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4);
        raw("UPDATE bnpl.bnpl_application SET status = 'DECLINED' WHERE id = '" + declined.value() + "'");
        born(UUID.randomUUID(), freed, BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, payIn4);
        assertThat(count("SELECT count(*) FROM bnpl.bnpl_application WHERE checkout_session_id = '" + freed + "'"))
                .as("a decline frees the session for a new application").isEqualTo(2);
    }

    @Test
    @DisplayName("only the machine's edges, for every role: SUBMITTED is never skipped, a terminal state never moves, the"
            + " identity is frozen, a permit moves only while awaiting, the closure reason only beside CLOSED_UNDECIDED")
    void onlyTheMachinesEdges() throws SQLException {
        BnplApplicationId id = born(UUID.randomUUID(), UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_N,
                BnplPlan.PAY_IN_6_MONTHS, payInN);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE bnpl.bnpl_application SET status = 'SUBMITTED' WHERE id = '" + id.value() + "'",
                    "P0001");
            refused(owner, "UPDATE bnpl.bnpl_application SET checkout_session_id = gen_random_uuid() WHERE id = '"
                    + id.value() + "'", "P0001");
            refused(owner, "UPDATE bnpl.bnpl_application SET status = 'CLOSED_UNDECIDED' WHERE id = '" + id.value()
                    + "'", "23514");
            refused(owner, "DELETE FROM bnpl.bnpl_application WHERE id = '" + id.value() + "'", "P0001");
            refused(owner, "TRUNCATE bnpl.bnpl_application_event", "P0001");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE bnpl.bnpl_application SET plan = 'PAY_IN_3_MONTHS' WHERE id = '" + id.value()
                    + "'", "42501");
            refused(application, "DELETE FROM bnpl.bnpl_application WHERE id = '" + id.value() + "'", "42501");
            refused(application, "UPDATE bnpl.bnpl_application_event SET reason = 'X_X_X' WHERE application_id = '"
                    + id.value() + "'", "42501");
        }
        raw("UPDATE bnpl.bnpl_application SET next_step_at = clock_timestamp() + interval '1 minute' WHERE id = '"
                + id.value() + "'");
        assertThat(withdrawn(id)).isTrue();
        assertThat(withdrawn(id)).as("the loser finds it moved").isFalse();
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE bnpl.bnpl_application SET status = 'AWAITING_DECISION' WHERE id = '" + id.value()
                    + "'", "P0001");
            refused(owner, "UPDATE bnpl.bnpl_application SET next_step_at = clock_timestamp() WHERE id = '" + id.value()
                    + "'", "P0001");
        }
        assertThat(scalar("SELECT (closed_at >= submitted_at)::text FROM bnpl.bnpl_application WHERE id = '"
                + id.value() + "'")).as("the closure stamped by the database").isEqualTo("true");
    }

    @Test
    @DisplayName("born only on its product's ACTIVE terms version with a plan that version offers")
    void bornOnTheActiveTermsWithAnOfferedPlan() {
        // The trigger refuses the insert itself (P0001), inside the store - which carries the operation and the SQLSTATE,
        // never the driver's exception (INV-AUD-02, BnplStorageException; P12-TSK-027).
        String refusal = "bnpl storage failed inserting a BNPL application: SQLSTATE P0001";
        assertThatThrownBy(() -> born(UUID.randomUUID(), UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_N,
                BnplPlan.PAY_IN_12_MONTHS, payInN)).as("the version offers 3 and 6 months")
                .isInstanceOf(BnplStorageException.class).hasMessage(refusal).hasNoCause();
        BnplTermsVersionId retired = payIn4;
        payIn4 = activate(BnplTermsFixtures.payIn4().build());
        assertThatThrownBy(() -> born(UUID.randomUUID(), UUID.randomUUID(), BnplProduct.BNPL_PAY_IN_4,
                BnplPlan.PAY_IN_4, retired)).as("a retired version").isInstanceOf(BnplStorageException.class)
                .hasMessage(refusal).hasNoCause();
    }

    // ------------------------------------------------------------------ plumbing

    private static BnplApplicationId born(UUID party, UUID session, BnplProduct product, BnplPlan plan,
            BnplTermsVersionId version) {
        return born(party, session, product, plan, version, UUID.randomUUID());
    }

    private static BnplApplicationId born(UUID party, UUID session, BnplProduct product, BnplPlan plan,
            BnplTermsVersionId version, UUID request) {
        BnplApplicationStore.NewApplication row = new BnplApplicationStore.NewApplication(BnplApplicationId.next(IDS),
                party, product, plan, session, UUID.randomUUID(), request, version, customer(party), correlation());
        return inOneTransaction(uow -> {
            if (!STORE.insert(uow, row)) {
                throw new IllegalStateException("an arbiter refused the case's own application");
            }
            STORE.appendEvent(uow, new BnplApplicationStore.HistoryRow(IDS.next(), row.id(), Optional.empty(),
                    BnplApplicationStatus.SUBMITTED, "APPLICATION_SUBMITTED", customer(party), correlation()));
            STORE.transition(uow, row.id(), BnplApplicationStatus.SUBMITTED, BnplApplicationStatus.AWAITING_DECISION);
            STORE.appendEvent(uow, new BnplApplicationStore.HistoryRow(IDS.next(), row.id(),
                    Optional.of(BnplApplicationStatus.SUBMITTED), BnplApplicationStatus.AWAITING_DECISION,
                    "CREDIT_REQUEST_OPENED", customer(party), correlation()));
            return row.id();
        });
    }

    /**
     * The origination step's OFFERED edge as {@code bnpl V004} admits it (`P12-TSK-011`): under B1, the offer born on the
     * product's terms version ACTIVE now with its terms, then the application moved - one transaction.
     */
    private static void offered(BnplApplicationId id) {
        inOneTransaction(uow -> {
            BnplApplication application = STORE.lockClaimed(uow, id).orElseThrow();
            BnplTermsVersion version = TERMS.pinActive(uow, application.product());
            JdbcBnplOfferStore offers = new JdbcBnplOfferStore();
            BnplOfferId offer = BnplOfferId.next(IDS);
            BnplOfferStore.Offered born = offers.insert(uow, new BnplOfferStore.NewOffer(offer, id,
                    application.party(), application.product(), application.plan(), UUID.randomUUID(), version.id(),
                    application.checkoutSession(), java.time.Instant.now().plus(java.time.Duration.ofDays(1)),
                    java.time.Instant.now().plus(java.time.Duration.ofHours(1)), correlation()));
            BnplIllustration illustration = BnplIllustration.of(version.terms(), application.plan(),
                    com.finapp.sharedkernel.money.Money.ofMinorUnits(600_00, BnplTermsFixtures.EUR),
                    java.time.LocalDate.ofInstant(born.offeredAt(), java.time.ZoneOffset.UTC));
            offers.insertTerms(uow, offer, BnplOfferStore.StoredTerms.of(application.plan(),
                    version.terms().nominalAnnualRate(), illustration,
                    "{\"format\":\"1\",\"offer\":\"" + offer.value() + "\"}", new byte[32]));
            if (!STORE.close(uow, id, BnplApplicationStatus.OFFERED, Optional.empty())) {
                throw new IllegalStateException("the application was awaiting a decision");
            }
            return null;
        });
    }

    private static boolean inserted(BnplApplicationStore.NewApplication row) {
        Boolean answer = inOneTransaction(uow -> STORE.insert(uow, row));
        return answer;
    }

    private static boolean withdrawn(BnplApplicationId id) {
        Boolean answer = inOneTransaction(uow -> STORE.transition(uow, id, BnplApplicationStatus.AWAITING_DECISION,
                BnplApplicationStatus.WITHDRAWN));
        return answer;
    }

    private static BnplApplicationStore.NewApplication application(UUID party, UUID session, BnplProduct product,
            BnplPlan plan, BnplTermsVersionId version) {
        return new BnplApplicationStore.NewApplication(BnplApplicationId.next(IDS), party, product, plan, session,
                UUID.randomUUID(), UUID.randomUUID(), version, customer(party), correlation());
    }

    private static BnplTermsVersionId activate(BnplTerms terms) {
        BnplTermsAdministration.Proposed proposed = inOneTransaction(uow -> TERMS.propose(uow, terms,
                "the application store's terms", employee(), correlation()));
        inOneTransaction(uow -> TERMS.approve(uow, proposed.id(), employee(), "in force for the case", correlation()));
        return proposed.id();
    }

    private static Actor customer(UUID party) {
        return new Actor(party.toString(), ActorType.CUSTOMER);
    }

    private static Actor employee() {
        return new Actor("employee-" + UUID.randomUUID(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    private static <R> R inOneTransaction(Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            } catch (SQLException commitRefused) {
                connection.rollback();
                throw new IllegalStateException(commitRefused);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void raw(String sql) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private static void refused(Connection connection, String sql, String sqlState) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState));
    }
}
