package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Loan product terms as versioned data against a real database (`P11-TSK-005`; {@code lending V003}, ADR-0092
 * sections 1-3; {@code INV-LND-05}, {@code INV-LND-09}, {@code INV-HIST-04}, {@code INV-AUD-04}): every race counted -
 * ten proposers, ten approvers; four eyes, immutability, one active version per product and only implemented conventions
 * held for every writer by raw SQL; the version in force at any past instant answered from the rows over a history; an
 * activation waiting for the offer that pinned its predecessor; the effective periods clamped on a database clock that
 * stepped back.
 *
 * <p>The two products are shared rows in the suite's database, so every case first brings a product to the state it
 * needs through the domain itself - rejecting a pending proposal, proposing and activating its own versions - and never
 * assumes what an earlier case left.
 */
@Tag("database")
@DisplayName("loan product terms as versioned data (P11-TSK-005)")
class LoanTermsDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final JdbcLoanTermsStore STORE = new JdbcLoanTermsStore();
    private static final LoanTermsAdministration ADMINISTRATION =
            new LoanTermsAdministration(STORE, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, CLOCK);
    private static final LoanProduct LOAN = LoanProduct.PERSONAL_LOAN;
    private static final LoanProduct LINE = LoanProduct.CREDIT_LINE;

    // ------------------------------------------------------------------ four eyes

    @Test
    @DisplayName("self-activation is refused at the domain, and by the CHECK for a raw-SQL writer of either role -"
            + " each rank alone")
    void fourEyesAtBothRanks() throws SQLException {
        Actor proposer = employee();
        LoanTermsAdministration.Proposed proposed = propose(LOAN, "0.0701", proposer);
        assertThatExceptionOfType(LoanTermsAdministration.SelfApprovalRefused.class).isThrownBy(() -> inOneTransaction(
                uow -> ADMINISTRATION.approve(uow, proposed.id(), proposer, "my own", correlation())));
        String selfActivation = "UPDATE lending.loan_terms_version SET status = 'ACTIVE', decided_by = proposed_by,"
                + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'";
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, selfActivation, "23514");
        }
        try (Connection application = DatabaseRoles.application()) {
            refused(application, selfActivation, "23514");
        }
        assertThat(status(proposed.id())).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = '" + proposed.id().value()
                + "' AND operation <> 'lending.TermsVersionProposed'")).as("a refused approval records nothing").isZero();
        LoanTermsAdministration.Decided withdrawn = inOneTransaction(
                uow -> ADMINISTRATION.reject(uow, proposed.id(), proposer, "withdrawn by its proposer", correlation()));
        assertThat(withdrawn.status()).as("the proposer may withdraw").isEqualTo(LoanTermsVersionStatus.REJECTED);
        assertThat(count(audited("lending.TermsVersionRejected", proposed.id()))).isEqualTo(1);
    }

    // ------------------------------------------------------------------ one active

    @Test
    @DisplayName("one ACTIVE version per product - a raw-SQL second activation by a different person is refused by the"
            + " partial unique; activations retire, never stack")
    void oneActivePerProduct() throws SQLException {
        activate(LOAN, "0.0702");
        activate(LOAN, "0.0703");
        LoanTermsAdministration.Proposed second = propose(LOAN, "0.0704", employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE lending.loan_terms_version SET status = 'ACTIVE', decided_by = 'someone-else',"
                    + " decided_by_type = 'EMPLOYEE', decision_reason = 'raw' WHERE id = '" + second.id().value() + "'",
                    "23505");
        }
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'PROPOSED'")).as("and one PROPOSED").isEqualTo(1);
    }

    @Test
    @DisplayName("no version is born active - by insert or by migration - and a retirement never commits alone")
    void neverActivatedButByAPerson() throws SQLException {
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE proposed_by LIKE 'migration:%'"))
                .as("nothing is seeded").isZero();
        clearPending(LINE);
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "INSERT INTO lending.loan_terms_version (id, product, version, status, proposed_by,"
                    + " proposed_by_type, proposed_at, proposal_reason, decided_by, decided_by_type, decision_reason,"
                    + " correlation_id) VALUES (gen_random_uuid(), 'CREDIT_LINE', 9999, 'ACTIVE', 'a', 'EMPLOYEE', now(),"
                    + " 'r', 'b', 'EMPLOYEE', 'r', 'c')", "P0001");
            refused(owner, "INSERT INTO lending.loan_terms_version (id, product, version, status, proposed_by,"
                    + " proposed_by_type, proposed_at, proposal_reason, correlation_id) VALUES (gen_random_uuid(),"
                    + " 'CREDIT_LINE', 9998, 'PROPOSED', 'a', 'EMPLOYEE', now(), 'r', 'c')", "P0001");
        }
        LoanTermsVersionId active = activate(LINE, "0.1401");
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE lending.loan_terms_version SET status = 'RETIRED' WHERE id = '" + active.value() + "'",
                    "P0001");
        }
        assertThat(status(active)).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ the history

    @Test
    @DisplayName("over a generated history of eight activations, each switch instant and the microsecond before it are"
            + " answered exactly from the rows; the periods meet; the read returns the version's whole term set")
    void activeAtAnInstantOverAHistory() throws Exception {
        java.util.Random random = new java.util.Random(20_261_011L);
        List<LoanTermsVersionId> versions = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            versions.add(activate(LINE, "0.15" + i + "1"));
            Thread.sleep(random.nextInt(15));
        }
        for (int i = 0; i < versions.size(); i++) {
            Instant from = effective(versions.get(i), "effective_from");
            assertThat(activeAt(LINE, from)).as("switch %d belongs to its successor", i).contains(versions.get(i));
            if (i > 0) {
                assertThat(effective(versions.get(i - 1), "effective_to")).as("switch %d: the periods meet", i)
                        .isEqualTo(from);
                assertThat(activeAt(LINE, from.minusNanos(1000))).as("the microsecond before switch %d", i)
                        .contains(versions.get(i - 1));
            }
        }
        Instant last = effective(versions.get(7), "effective_from");
        assertThat(activeAt(LINE, last.plusSeconds(3600))).as("still in force").contains(versions.get(7));
        assertThat(activeAt(LINE, Instant.parse("2000-01-01T00:00:00Z"))).as("nothing before any activation").isEmpty();
        Instant thirdFrom = effective(versions.get(2), "effective_from");
        LoanTermsStore.LoanTermsVersion third =
                inOneTransaction(uow -> ADMINISTRATION.activeAt(uow, LINE, Optional.of(thirdFrom)));
        assertThat(third.row().id()).isEqualTo(versions.get(2));
        assertThat(third.row().status()).isEqualTo(LoanTermsVersionStatus.RETIRED);
        assertThat(third.terms()).as("the whole term set, exactly as proposed").isEqualTo(
                LoanTermsFixtures.creditLine("0.1521").build());
        assertThat(inOneTransaction(uow -> ADMINISTRATION.activeAt(uow, LINE, Optional.empty())).row().id())
                .as("with no instant, the version ACTIVE by status").isEqualTo(versions.get(7));
    }

    // ------------------------------------------------------------------ immutability

    @Test
    @DisplayName("a terms version is immutable once proposed, for every role: the term set, the identity, the history -"
            + " UPDATE, DELETE, TRUNCATE and a late term set refused, PROPOSED and again ACTIVE")
    void immutableForEveryRole() throws SQLException {
        LoanTermsAdministration.Proposed proposed = propose(LOAN, "0.0705", employee());
        LoanTerms stored = inOneTransaction(uow -> STORE.version(uow, proposed.id())).orElseThrow().terms();
        assertThat(stored).as("the term set round-trips exactly").isEqualTo(LoanTermsFixtures.personalLoan("0.0705").build());
        // A second term set for a PROPOSED version meets its one-per-version key; for an ACTIVE one, the trigger first.
        frozen(proposed.id(), "23505");
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "now active", correlation()));
        frozen(proposed.id(), "P0001");
        assertThat(inOneTransaction(uow -> STORE.version(uow, proposed.id())).orElseThrow().terms()).isEqualTo(stored);
    }

    private static void frozen(LoanTermsVersionId id, String lateTermSet) throws SQLException {
        String terms = "lending.loan_terms WHERE terms_version_id = '" + id.value() + "'";
        String version = "lending.loan_terms_version WHERE id = '" + id.value() + "'";
        String history = "lending.loan_terms_version_event WHERE terms_version_id = '" + id.value() + "'";
        try (Connection application = DatabaseRoles.application()) {
            refused(application, "UPDATE " + terms.replace(" WHERE", " SET late_fee_minor = 1 WHERE"), "42501");
            refused(application, "UPDATE " + terms.replace(" WHERE", " SET nominal_annual_rate = '0.5' WHERE"), "42501");
            refused(application, "DELETE FROM " + terms, "42501");
            refused(application, "UPDATE " + version.replace(" WHERE", " SET proposal_reason = 'x' WHERE"), "42501");
            refused(application, "UPDATE " + version.replace(" WHERE", " SET product = 'CREDIT_LINE' WHERE"), "42501");
            refused(application, "DELETE FROM " + version, "42501");
            refused(application, "UPDATE " + history.replace(" WHERE", " SET reason = 'x' WHERE"), "42501");
            refused(application, "TRUNCATE lending.loan_terms", "42501");
        }
        try (Connection owner = DatabaseRoles.migrator()) {
            refused(owner, "UPDATE " + terms.replace(" WHERE", " SET late_fee_minor = 1 WHERE"), "P0001");
            refused(owner, "UPDATE " + terms.replace(" WHERE", " SET day_count = 'ACT_365F' WHERE"), "P0001");
            refused(owner, "DELETE FROM " + terms, "P0001");
            refused(owner, "TRUNCATE lending.loan_terms CASCADE", "P0001");
            refused(owner, "UPDATE " + version.replace(" WHERE", " SET proposal_reason = 'x' WHERE"), "P0001");
            refused(owner, "UPDATE " + version.replace(" WHERE", " SET version = version + 100 WHERE"), "P0001");
            refused(owner, "DELETE FROM " + version, "P0001");
            refused(owner, "TRUNCATE lending.loan_terms_version CASCADE", "P0001");
            refused(owner, "UPDATE " + history.replace(" WHERE", " SET reason = 'x' WHERE"), "P0001");
            refused(owner, "DELETE FROM " + history, "P0001");
            refused(owner, "INSERT INTO lending.loan_terms SELECT * FROM " + terms, lateTermSet);
        }
    }

    // ------------------------------------------------------------------ the races

    @Test
    @DisplayName("ten proposers of one product: one proposal, nine ProposalPending - counted")
    void tenProposersOneProposal() throws Exception {
        clearPending(LINE);
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.propose(uow, LoanTermsFixtures.creditLine("0.1601").build(),
                        "racing", employee(), correlation()));
            } catch (LoanTermsAdministration.ProposalPending pending) {
                return pending;
            }
        });
        assertThat(outcomes.stream().filter(LoanTermsAdministration.Proposed.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(LoanTermsAdministration.ProposalPending.class::isInstance)).hasSize(9);
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE product = 'CREDIT_LINE'"
                + " AND status = 'PROPOSED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten approvers of one proposal: one activation, nine TermsStale - one ACTIVE, one event, one audit"
            + " record, counted")
    void tenApproversOneActivation() throws Exception {
        LoanTermsVersionId predecessor = activate(LOAN, "0.0706");
        LoanTermsAdministration.Proposed proposed = propose(LOAN, "0.0707", employee());
        List<Object> outcomes = race(10, () -> {
            try {
                return inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "racing", correlation()));
            } catch (LoanTermsAdministration.TermsStale stale) {
                return stale;
            }
        });
        assertThat(outcomes.stream().filter(LoanTermsAdministration.Decided.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(LoanTermsAdministration.TermsStale.class::isInstance)).hasSize(9);
        assertThat(status(proposed.id())).isEqualTo("ACTIVE");
        assertThat(status(predecessor)).isEqualTo("RETIRED");
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'ACTIVE'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'lending.LoanTermsVersionActivated'"
                + " AND aggregate_id = '" + proposed.id().value() + "'")).as("one event").isEqualTo(1);
        assertThat(count(audited("lending.TermsVersionActivated", proposed.id()))).as("losers record nothing").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version_event WHERE terms_version_id = '"
                + proposed.id().value() + "' AND to_status = 'ACTIVE'")).as("one history row").isEqualTo(1);
    }

    @Test
    @DisplayName("an activation waits - on the ACTIVE row's FOR UPDATE - for an offer holding the active version FOR SHARE"
            + " as its pin, then retires it; the offer pinned the old version, never neither")
    void anActivationWaitsOnAnOfferPinningTheTerms() throws Exception {
        LoanTermsVersionId pinned = activate(LOAN, "0.0708");
        LoanTermsAdministration.Proposed successor = propose(LOAN, "0.0709", employee());
        try (Connection offer = DatabaseRoles.application()) {
            offer.setAutoCommit(false);
            LoanTermsStore.LoanTermsVersion held = ADMINISTRATION.pinActive(offer, LOAN);
            assertThat(held.row().id()).as("the offer pins the version in force").isEqualTo(pinned);
            int offerPid;
            try (Statement pid = offer.createStatement(); ResultSet row = pid.executeQuery("SELECT pg_backend_pid()")) {
                row.next();
                offerPid = row.getInt(1);
            }
            CompletableFuture<LoanTermsAdministration.Decided> activation = CompletableFuture.supplyAsync(() ->
                    inOneTransaction(uow -> ADMINISTRATION.approve(uow, successor.id(), employee(), "waits", correlation())));
            String waiting = awaitAStatementBlockedBy(offerPid);
            assertThat(waiting).as("the activation waits on A2 - the ACTIVE row's lock - and nothing earlier")
                    .contains("FOR UPDATE").contains("status = 'ACTIVE'");
            assertThat(activation).as("blocked behind the pin").isNotDone();
            assertThat(status(pinned)).as("the pinned version stays in force while the offer is open").isEqualTo("ACTIVE");
            offer.commit();
            assertThat(activation.get(30, TimeUnit.SECONDS).retired()).contains(pinned);
        }
        assertThat(status(pinned)).isEqualTo("RETIRED");
        assertThat(status(successor.id())).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ the clock

    @Test
    @DisplayName("an activation on a database clock behind its predecessor's start commits: the predecessor ends one"
            + " microsecond after it began, the successor starts exactly there (credit V014's lesson)")
    void aBackwardClockIsClamped() throws SQLException {
        LoanTermsVersionId predecessor = activate(LOAN, "0.0710");
        LoanTermsAdministration.Proposed successor = propose(LOAN, "0.0711", employee());
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                // The clock behind: the predecessor's start planted an hour past this transaction's, by the owner with the
                // edge trigger disabled - inside a transaction rolled back, so no history keeps it.
                Instant planted = plantStartAnHourAhead(owner, predecessor);
                LoanTermsAdministration.Decided activated =
                        ADMINISTRATION.approve(owner, successor.id(), employee(), "a clock behind", correlation());
                try (Statement now = owner.createStatement()) {
                    now.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }
                assertThat(activated.retired()).contains(predecessor);
                Instant end = effectiveIn(owner, predecessor, "effective_to");
                assertThat(end).as("never at or before its own start").isEqualTo(planted.plusNanos(1_000));
                assertThat(effectiveIn(owner, successor.id(), "effective_from")).as("they meet").isEqualTo(end);
            } finally {
                owner.rollback();
            }
        }
        assertThat(status(predecessor)).as("the plant rolled back with it").isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------ the conventions

    @Test
    @DisplayName("a convention no engine implements is refused at the domain, and by V003's CHECKs for a raw-SQL writer:"
            + " a day count, a rounding mode, a rate type, a zone, an allocation order omitting a component")
    void anUnimplementedConventionIsRefused() throws SQLException {
        assertThatThrownBy(() -> LoanTermsFixtures.personalLoan("0.07").interestRounding(RoundingMode.HALF_UP).build())
                .isInstanceOf(LoanTerms.TermsInvalid.class);
        assertThatThrownBy(() -> LoanTermsFixtures.personalLoan("0.07").servicingZone("Europe/Berlin").build())
                .isInstanceOf(LoanTerms.TermsInvalid.class);
        clearPending(LOAN);
        rawProposalAccepted();
        for (Map.Entry<String, String> defect : Map.of(
                        "day_count", "'ACT_360'",
                        "interest_rounding", "'HALF_UP'",
                        "instalment_rounding", "'HALF_EVEN'",
                        "rate_type", "'VARIABLE'",
                        "servicing_zone", "'Europe/Berlin'",
                        "allocation_order", "ARRAY['FEES', 'PRINCIPAL']",
                        "nominal_annual_rate", "'0.0700'",
                        "overpayment_treatment", "'PAY_DOWN_PRINCIPAL'")
                .entrySet()) {
            rawProposalRefused(defect.getKey(), defect.getValue(), "23514");
        }
        assertThat(count("SELECT count(*) FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'"
                + " AND status = 'PROPOSED'")).as("every raw proposal rolled back").isZero();
    }

    /** The raw-SQL proposal with no defect commits - so each refusal below is its defect's alone. */
    private static void rawProposalAccepted() throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                UUID id = UUID.randomUUID();
                statement.execute(rawVersion(id));
                statement.execute(rawTerms(id, null, null));
                statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
            } finally {
                owner.rollback();
            }
        }
    }

    private static void rawProposalRefused(String column, String value, String sqlState) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                UUID id = UUID.randomUUID();
                statement.execute(rawVersion(id));
                assertThatThrownBy(() -> statement.execute(rawTerms(id, column, value)))
                        .as(column + " = " + value)
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).as(column)
                                .isEqualTo(sqlState));
            } finally {
                owner.rollback();
            }
        }
    }

    private static String rawVersion(UUID id) {
        return "INSERT INTO lending.loan_terms_version (id, product, version, status, proposed_by, proposed_by_type,"
                + " proposed_at, proposal_reason, correlation_id) VALUES ('" + id + "', 'PERSONAL_LOAN',"
                + " (SELECT COALESCE(max(version), 0) + 1 FROM lending.loan_terms_version WHERE product = 'PERSONAL_LOAN'),"
                + " 'PROPOSED', 'raw', 'EMPLOYEE', now(), 'a raw proposal', 'raw')";
    }

    /** A valid personal-loan term set for {@code id}, with {@code column} replaced by {@code value} when named. */
    private static String rawTerms(UUID id, String column, String value) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("terms_version_id", "'" + id + "'");
        values.put("product", "'PERSONAL_LOAN'");
        values.put("currency", "'EUR'");
        values.put("scale", "2");
        values.put("nominal_annual_rate", "'0.07'");
        values.put("rate_type", "'FIXED'");
        values.put("origination_fee_minor", "0");
        values.put("origination_fee_bps", "NULL");
        values.put("origination_fee_treatment", "'DEDUCTED'");
        values.put("late_fee_minor", "1500");
        values.put("late_fee_day", "5");
        values.put("late_fee_cap_minor", "6000");
        values.put("grace_days", "3");
        values.put("delinquency_bounds", "ARRAY[1, 30, 60, 90]");
        values.put("default_threshold_days", "90");
        values.put("allocation_sequence", "'OLDEST_DUE_FIRST'");
        values.put("allocation_order", "ARRAY['FEES', 'INTEREST', 'PRINCIPAL']");
        values.put("overpayment_treatment", "'HOLD_AS_CREDIT'");
        values.put("auto_collection", "true");
        values.put("partial_collection", "true");
        values.put("instalment_rounding", "'UP'");
        values.put("interest_rounding", "'HALF_EVEN'");
        values.put("day_count", "'ACT_365F'");
        values.put("servicing_zone", "'UTC'");
        values.put("allowed_days", "ARRAY[1, 15, 28]");
        values.put("min_first_period_days", "15");
        values.put("offer_validity_days", "14");
        values.put("payoff_horizon_days", "30");
        values.put("disbursement_deadline_hours", "24");
        values.put("schedule_engine_version", "1");
        values.put("accrual_engine_version", "1");
        values.put("allocation_engine_version", "1");
        values.put("agreement_template_id", "'PERSONAL_LOAN_AGREEMENT'");
        values.put("agreement_template_version", "1");
        if (column != null) {
            assertThat(values).as("the defect names a real column").containsKey(column);
            values.put(column, value);
        }
        return "INSERT INTO lending.loan_terms (" + String.join(", ", values.keySet()) + ") VALUES ("
                + String.join(", ", values.values()) + ")";
    }

    // ------------------------------------------------------------------ plumbing

    private static Actor employee() {
        return new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** Rejects the product's pending proposal, if there is one, so a case can propose. */
    private static void clearPending(LoanProduct product) {
        inOneTransaction(uow -> {
            try (PreparedStatement select = uow.prepareStatement(
                    "SELECT id FROM lending.loan_terms_version WHERE product = ? AND status = 'PROPOSED'")) {
                select.setString(1, product.name());
                try (ResultSet row = select.executeQuery()) {
                    if (row.next()) {
                        ADMINISTRATION.reject(uow, LoanTermsVersionId.of(row.getObject(1, UUID.class)), employee(),
                                "cleared for the next case", correlation());
                    }
                }
                return null;
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    /** A fresh proposal of the product's plan terms at {@code rate}, by {@code proposer}. */
    private static LoanTermsAdministration.Proposed propose(LoanProduct product, String rate, Actor proposer) {
        clearPending(product);
        return inOneTransaction(uow -> ADMINISTRATION.propose(uow, LoanTermsFixtures.of(product, rate).build(),
                "a case's proposal", proposer, correlation()));
    }

    /** A fresh version, proposed by one person and activated by another. */
    private static LoanTermsVersionId activate(LoanProduct product, String rate) {
        LoanTermsAdministration.Proposed proposed = propose(product, rate, employee());
        inOneTransaction(uow -> ADMINISTRATION.approve(uow, proposed.id(), employee(), "a case's activation", correlation()));
        return proposed.id();
    }

    private static Optional<LoanTermsVersionId> activeAt(LoanProduct product, Instant instant) {
        return inOneTransaction(uow -> STORE.activeAt(uow, product, instant));
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
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String audited(String operation, LoanTermsVersionId id) {
        return "SELECT count(*) FROM platform.audit_record WHERE operation = '" + operation + "' AND target_id = '"
                + id.value() + "' AND reason IS NOT NULL AND outcome = 'SUCCEEDED'";
    }

    private static String status(LoanTermsVersionId id) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(
                        "SELECT status FROM lending.loan_terms_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getString(1);
        }
    }

    private static Instant effective(LoanTermsVersionId id, String column) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator()) {
            return effectiveIn(migrator, id, column);
        }
    }

    private static Instant effectiveIn(Connection connection, LoanTermsVersionId id, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(
                "SELECT " + column + " FROM lending.loan_terms_version WHERE id = '" + id.value() + "'")) {
            row.next();
            return row.getTimestamp(1).toInstant();
        }
    }

    private static Instant plantStartAnHourAhead(Connection owner, LoanTermsVersionId id) throws SQLException {
        try (Statement plant = owner.createStatement()) {
            plant.execute("ALTER TABLE lending.loan_terms_version DISABLE TRIGGER loan_terms_version_permits_only_machine_edges");
            Instant planted;
            try (ResultSet row = plant.executeQuery("UPDATE lending.loan_terms_version SET effective_from ="
                    + " transaction_timestamp() + interval '1 hour' WHERE id = '" + id.value() + "' RETURNING effective_from")) {
                row.next();
                planted = row.getTimestamp(1).toInstant();
            }
            // The plant queued the deferred successor check (a no-op for an ACTIVE row); fire it so the table can be
            // altered again, then defer the checks once more for the activation under test.
            plant.execute("SET CONSTRAINTS ALL IMMEDIATE");
            plant.execute("ALTER TABLE lending.loan_terms_version ENABLE TRIGGER loan_terms_version_permits_only_machine_edges");
            plant.execute("SET CONSTRAINTS ALL DEFERRED");
            return planted;
        }
    }

    /**
     * Waits until a session is blocked by {@code blocker}, and answers the statement it waits in - read as the
     * application role, whose own sessions' statements it may see (another role reads "<insufficient privilege>").
     */
    private static String awaitAStatementBlockedBy(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Connection observer = DatabaseRoles.application(); Statement statement = observer.createStatement();
                    ResultSet row = statement.executeQuery("SELECT query FROM pg_stat_activity WHERE " + blocker
                            + " = ANY (pg_blocking_pids(pid))")) {
                if (row.next()) {
                    return row.getString(1);
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("no activation ever waited on the pinned version");
    }

    /** {@code sql}, run alone by {@code connection}, is refused with {@code sqlState}. */
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

    private static <T> List<T> race(int racers, Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return work.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
