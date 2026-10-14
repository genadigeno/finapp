package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link RepaymentStore} over plain JDBC (`P11-TSK-018`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never the key or an amount.
 */
public final class JdbcRepaymentStore implements RepaymentStore {

    private static final String COLUMNS = "r.id, r.loan_id, r.kind, r.request_key, r.currency, r.scale, r.amount_minor,"
            + " r.agreement_id, r.agreement_version, r.allocation_engine_version, r.journal_entry_id, r.value_date,"
            + " r.created_at";

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public Optional<Repayment> findByKey(Connection unitOfWork, LoanId loan, RepaymentKind kind, String requestKey) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(requestKey, "requestKey");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + " FROM lending.repayment r"
                + " WHERE r.loan_id = ? AND r.kind = ? AND r.request_key = ?")) {
            select.setObject(1, loan.value());
            select.setString(2, kind.name());
            select.setString(3, requestKey);
            return repayments(unitOfWork, select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a repayment by its key", failure);
        }
    }

    @Override
    public Optional<Repayment> findById(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + " FROM lending.repayment r"
                + " WHERE r.loan_id = ? AND r.id = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, id);
            return repayments(unitOfWork, select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a repayment by its id", failure);
        }
    }

    @Override
    public Instant insert(Connection unitOfWork, Repayment repayment, CorrelationId correlation) {
        Objects.requireNonNull(repayment, "repayment");
        Objects.requireNonNull(correlation, "correlation");
        Money amount = repayment.amount();
        Instant receivedAt;
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.repayment (id, loan_id, kind, request_key, currency, scale, amount_minor, agreement_id,"
                        + " agreement_version, allocation_engine_version, journal_entry_id, value_date, created_at,"
                        + " correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?) RETURNING created_at")) {
            int i = 0;
            insert.setObject(++i, repayment.id());
            insert.setObject(++i, repayment.loan().value());
            insert.setString(++i, repayment.kind().name());
            insert.setString(++i, repayment.requestKey());
            insert.setString(++i, amount.currency().code());
            insert.setInt(++i, amount.scale());
            insert.setLong(++i, amount.minorUnits());
            insert.setObject(++i, repayment.agreement().value());
            insert.setInt(++i, repayment.agreementVersion());
            insert.setInt(++i, repayment.allocationEngine().number());
            insert.setObject(++i, repayment.journalEntry().value());
            insert.setObject(++i, repayment.valueDate());
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                receivedAt = row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a repayment", failure);
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.repayment_allocation (id, repayment_id, ordinal, target, billing_id, component,"
                        + " currency, scale, amount_minor) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (RepaymentAllocation row : repayment.allocations()) {
                AllocationLine line = row.line();
                int i = 0;
                insert.setObject(++i, row.id());
                insert.setObject(++i, repayment.id());
                insert.setInt(++i, row.ordinal());
                insert.setString(++i, line.target().name());
                insert.setObject(++i, line.item().map(DueItemId::value).orElse(null));
                insert.setString(++i, line.component().map(Enum::name).orElse(null));
                insert.setString(++i, line.amount().currency().code());
                insert.setInt(++i, line.amount().scale());
                insert.setLong(++i, line.amount().minorUnits());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("inserting a repayment's allocations", failure);
        }
        return receivedAt;
    }

    @Override
    public List<Repayment> ownedBy(Connection unitOfWork, LoanId loan, UUID party, int limit) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one repayment");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + " FROM lending.repayment r"
                + " JOIN lending.loan l ON l.id = r.loan_id WHERE r.loan_id = ? AND l.party_id = ?"
                + " ORDER BY r.created_at DESC, r.id DESC LIMIT ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, party);
            select.setInt(3, limit);
            return repayments(unitOfWork, select);
        } catch (SQLException failure) {
            throw failed("reading a party's loan's repayments", failure);
        }
    }

    @Override
    public Optional<LoanClosure> closedBy(Connection unitOfWork, UUID repayment) {
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT id, loan_id, reason, currency, scale,"
                + " refund_minor, refund_journal_entry_id FROM lending.loan_closure WHERE repayment_id = ?")) {
            select.setObject(1, repayment);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID entry = row.getObject("refund_journal_entry_id", UUID.class);
                return Optional.of(new LoanClosure(
                        row.getObject("id", UUID.class),
                        LoanId.of(row.getObject("loan_id", UUID.class)),
                        ClosureReason.valueOf(row.getString("reason")),
                        Money.ofPersisted(row.getLong("refund_minor"), CurrencyCode.of(row.getString("currency").strip()),
                                row.getInt("scale")),
                        Optional.ofNullable(entry).map(JournalEntryId::of),
                        Optional.of(repayment)));
            }
        } catch (SQLException failure) {
            throw failed("reading a repayment's closure", failure);
        }
    }

    @Override
    public Optional<Rescheduled> rescheduledBy(Connection unitOfWork, LoanId loan, UUID repayment) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT a.version, s.schedule_version, s.term_months,"
                + " s.currency, s.scale, s.instalment_minor, s.final_instalment_minor,"
                + " (SELECT i.due_date FROM lending.loan_instalment i WHERE i.schedule_id = s.id AND i.sequence = 1)"
                + " AS first_due FROM lending.loan_agreement a JOIN lending.repayment_schedule s ON s.agreement_id = a.id"
                + " WHERE a.loan_id = ? AND a.prepayment_id = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, repayment);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                int scale = row.getInt("scale");
                return Optional.of(new Rescheduled(row.getInt("version"), row.getInt("schedule_version"),
                        row.getInt("term_months"), Money.ofPersisted(row.getLong("instalment_minor"), currency, scale),
                        Money.ofPersisted(row.getLong("final_instalment_minor"), currency, scale),
                        row.getObject("first_due", LocalDate.class)));
            }
        } catch (SQLException failure) {
            throw failed("reading a prepayment's re-schedule", failure);
        }
    }

    // ------------------------------------------------------------------ the collection attempts (P11-TSK-019)

    @Override
    public Set<DueItemId> attemptedOn(Connection unitOfWork, LoanId loan, LocalDate attemptDate) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(attemptDate, "attemptDate");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT due_item_id FROM lending.collection_attempt"
                + " WHERE loan_id = ? AND attempt_date = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, attemptDate);
            Set<DueItemId> attempted = new HashSet<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    attempted.add(DueItemId.of(row.getObject(1, UUID.class)));
                }
            }
            return attempted;
        } catch (SQLException failure) {
            throw failed("reading a loan's collection attempts of a date", failure);
        }
    }

    @Override
    public void insertAttempt(Connection unitOfWork, CollectionAttempt attempt, CorrelationId correlation) {
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.collection_attempt (id, loan_id, due_item_id, attempt_date, outcome, currency, scale,"
                        + " due_minor, collected_minor, repayment_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, attempt.id());
            insert.setObject(++i, attempt.loan().value());
            insert.setObject(++i, attempt.dueItem().value());
            insert.setObject(++i, attempt.attemptDate());
            insert.setString(++i, attempt.outcome().name());
            insert.setString(++i, attempt.due().currency().code());
            insert.setInt(++i, attempt.due().scale());
            insert.setLong(++i, attempt.due().minorUnits());
            insert.setLong(++i, attempt.collected().minorUnits());
            insert.setObject(++i, attempt.repayment().orElse(null));
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a collection attempt", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** The repayments {@code select} answers, each with its allocations read in ONE further statement. */
    private static List<Repayment> repayments(Connection unitOfWork, PreparedStatement select) throws SQLException {
        List<Head> heads = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                heads.add(head(row));
            }
        }
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<RepaymentAllocation>> allocations = new HashMap<>();
        try (PreparedStatement lines = unitOfWork.prepareStatement(
                "SELECT repayment_id, id, ordinal, target, billing_id, component, currency, scale, amount_minor"
                        + " FROM lending.repayment_allocation WHERE repayment_id = ANY (?) ORDER BY repayment_id, ordinal")) {
            lines.setArray(1, unitOfWork.createArrayOf("uuid", heads.stream().map(Head::id).toArray()));
            try (ResultSet row = lines.executeQuery()) {
                while (row.next()) {
                    allocations.computeIfAbsent(row.getObject("repayment_id", UUID.class), id -> new ArrayList<>())
                            .add(allocation(row));
                }
            }
        }
        List<Repayment> repayments = new ArrayList<>();
        for (Head head : heads) {
            repayments.add(new Repayment(head.id(), head.loan(), head.kind(), head.requestKey(), head.amount(),
                    head.agreement(), head.agreementVersion(), head.engine(), allocations.getOrDefault(head.id(), List.of()),
                    head.entry(), head.valueDate(), head.receivedAt()));
        }
        return repayments;
    }

    private record Head(
            UUID id,
            LoanId loan,
            RepaymentKind kind,
            String requestKey,
            Money amount,
            LoanAgreementId agreement,
            int agreementVersion,
            EngineVersion engine,
            JournalEntryId entry,
            LocalDate valueDate,
            Instant receivedAt) {}

    private static Head head(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        return new Head(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                RepaymentKind.valueOf(row.getString("kind")),
                row.getString("request_key"),
                Money.ofPersisted(row.getLong("amount_minor"), currency, row.getInt("scale")),
                LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                row.getInt("agreement_version"),
                EngineVersion.of(LendingEngine.ALLOCATION, row.getInt("allocation_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a repayment names an engine version this build holds")),
                JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                row.getObject("value_date", LocalDate.class),
                row.getTimestamp("created_at").toInstant());
    }

    private static RepaymentAllocation allocation(ResultSet row) throws SQLException {
        Money amount = Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("currency").strip()),
                row.getInt("scale"));
        AllocationLine line = switch (AllocationLine.Target.valueOf(row.getString("target"))) {
            case DUE_ITEM -> AllocationLine.due(DueItemId.of(row.getObject("billing_id", UUID.class)),
                    AllocationComponent.valueOf(row.getString("component")), amount);
            case NOT_DUE_PRINCIPAL -> AllocationLine.notDuePrincipal(amount);
            case CREDIT_BALANCE -> AllocationLine.creditBalance(amount);
        };
        return new RepaymentAllocation(row.getObject("id", UUID.class), row.getInt("ordinal"), line);
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
