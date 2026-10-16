package com.finapp.lending;

import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanStore} over plain JDBC (`P11-TSK-012`, ADR-0033): every time the database's, every customer read
 * owner-scoped in the statement itself (ADR-0031), every failure its operation and SQLSTATE only
 * ({@link LendingStorageException}).
 */
public final class JdbcLoanStore implements LoanStore {

    /**
     * The offer's expiry refusals' own SQLSTATE ({@code lending V026}, `P11-TSK-034`) - the loan's birth and the offer's
     * customer-side edges past {@code expires_at}; every other refusal of those triggers is {@code P0001}.
     */
    static final String OFFER_EXPIRED_SQLSTATE = "LNEXP";

    /**
     * The amendment's lapse at its agreement version's birth - its own SQLSTATE ({@code lending V027}, `P11-TSK-035`), a
     * sibling of {@link #OFFER_EXPIRED_SQLSTATE}: the decision's {@code expires_at} passed on the database clock; every
     * other refusal of the agreement's trigger is {@code P0001}.
     */
    static final String AMENDMENT_LAPSED_SQLSTATE = "LNAEX";

    private static final String COLUMNS =
            "l.id, l.party_id, l.product, l.status, l.currency, l.offer_id, l.decision_id, l.opened_at, l.closed_at,"
                    + " l.correlation_id, a.id AS agreement_id, a.version, a.offer_id AS agreement_offer_id, a.amendment_id,"
                    + " a.prepayment_id, a.decision_id AS agreement_decision_id, a.terms_version_id, a.scale, a.amount_minor,"
                    + " a.term_months, a.nominal_annual_rate, a.repayment_day, a.schedule_engine_version,"
                    + " a.accrual_engine_version, a.allocation_engine_version, a.statement_engine_version,"
                    + " a.agreement_template_id, a.agreement_template_version, a.canonical_terms, a.terms_sha256,"
                    + " a.effective_from";

    /** The loan with the agreement version that governs it now - its highest. */
    private static final String FROM = " FROM lending.loan l"
            + " JOIN LATERAL (SELECT * FROM lending.loan_agreement x WHERE x.loan_id = l.id"
            + " ORDER BY x.version DESC LIMIT 1) a ON true";

    @Override
    public Instant insert(Connection unitOfWork, NewLoan loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan (id, party_id, product, kind, status, currency, offer_id, decision_id,"
                        // The opening instant is the trigger's; the placeholder is overwritten.
                        + " opened_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)"
                        + " RETURNING opened_at")) {
            insert.setObject(1, loan.id().value());
            insert.setObject(2, loan.party());
            insert.setString(3, loan.product().name());
            insert.setString(4, loan.product().kind().name());
            insert.setString(5, loan.bornStatus().name());
            insert.setString(6, loan.currency().code());
            insert.setObject(7, loan.offer().value());
            insert.setObject(8, loan.decision());
            insert.setString(9, loan.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            if (OFFER_EXPIRED_SQLSTATE.equals(failure.getSQLState())) {
                throw new OfferExpired();
            }
            throw failed("inserting a loan", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_event (id, loan_id, from_status, to_status, reason, actor, actor_type,"
                        + " occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            insert.setObject(1, row.eventId());
            insert.setObject(2, row.loan().value());
            insert.setString(3, row.from().map(Enum::name).orElse(null));
            insert.setString(4, row.to().name());
            insert.setString(5, row.reason());
            insert.setString(6, row.actor().id());
            insert.setString(7, row.actor().type().name());
            insert.setString(8, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending a loan event", failure);
        }
    }

    @Override
    public Instant insertAgreement(Connection unitOfWork, LoanAgreement agreement) {
        Objects.requireNonNull(agreement, "agreement");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_agreement (id, loan_id, version, offer_id, amendment_id, prepayment_id,"
                        + " decision_id,"
                        + " terms_version_id, product, currency, scale, amount_minor, term_months, nominal_annual_rate,"
                        + " repayment_day, schedule_engine_version, accrual_engine_version, allocation_engine_version,"
                        + " statement_engine_version, agreement_template_id, agreement_template_version,"
                        + " canonical_format, canonical_terms, terms_sha256, effective_from, created_at)"
                        // The two instants are the trigger's; the placeholders are overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                        + " statement_timestamp(), statement_timestamp()) RETURNING effective_from")) {
            int i = 0;
            insert.setObject(++i, agreement.id().value());
            insert.setObject(++i, agreement.loan().value());
            insert.setInt(++i, agreement.version());
            insert.setObject(++i, agreement.offer().map(LoanOfferId::value).orElse(null));
            insert.setObject(++i, agreement.amendment().orElse(null));
            insert.setObject(++i, agreement.prepayment().orElse(null));
            insert.setObject(++i, agreement.decision());
            insert.setObject(++i, agreement.termsVersionId().value());
            insert.setString(++i, agreement.product().name());
            insert.setString(++i, agreement.amount().currency().code());
            insert.setInt(++i, agreement.amount().scale());
            insert.setLong(++i, agreement.amount().minorUnits());
            setInt(insert, ++i, agreement.termMonths());
            insert.setString(++i, agreement.nominalAnnualRate().toPlainString());
            insert.setInt(++i, agreement.repaymentDay());
            setInt(insert, ++i, agreement.scheduleEngine().map(EngineVersion::number));
            insert.setInt(++i, agreement.accrualEngine().number());
            insert.setInt(++i, agreement.allocationEngine().number());
            setInt(insert, ++i, agreement.statementEngine().map(EngineVersion::number));
            insert.setString(++i, agreement.template().id());
            insert.setInt(++i, agreement.template().version());
            // The format the bytes declare - an agreement copies its offer's, whichever it was (P11-TSK-031).
            insert.setInt(++i, CanonicalOfferTerms.formatOf(agreement.canonicalTerms()));
            insert.setString(++i, agreement.canonicalTerms());
            insert.setBytes(++i, agreement.termsSha256());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            if (AMENDMENT_LAPSED_SQLSTATE.equals(failure.getSQLState())) {
                throw new AmendmentExpired();
            }
            throw failed("inserting a loan agreement", failure);
        }
    }

    @Override
    public Instant insertAcceptance(
            Connection unitOfWork,
            UUID id,
            LoanAgreement agreement,
            byte[] documentSha256,
            AcceptanceEvidence evidence,
            CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(agreement, "agreement");
        Objects.requireNonNull(documentSha256, "documentSha256");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_acceptance (id, loan_id, agreement_id, agreement_version, offer_id,"
                        + " terms_sha256, agreement_template_id, agreement_template_version, document_sha256,"
                        + " identity_id, session_id, assurance_level, accepted_at, channel, client_ip, user_agent,"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?,"
                        + " CAST(? AS inet), ?, ?) RETURNING accepted_at")) {
            int i = 0;
            insert.setObject(++i, id);
            insert.setObject(++i, agreement.loan().value());
            insert.setObject(++i, agreement.id().value());
            insert.setInt(++i, agreement.version());
            insert.setObject(++i, agreement.offer().map(LoanOfferId::value).orElse(null));
            insert.setBytes(++i, agreement.termsSha256());
            insert.setString(++i, agreement.template().id());
            insert.setInt(++i, agreement.template().version());
            insert.setBytes(++i, documentSha256);
            insert.setObject(++i, evidence.identity());
            insert.setObject(++i, evidence.session());
            insert.setString(++i, evidence.assuranceLevel());
            insert.setString(++i, AcceptanceEvidence.CHANNEL);
            insert.setString(++i, evidence.clientIp().orElse(null));
            insert.setString(++i, evidence.userAgent().orElse(null));
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a loan acceptance", failure);
        }
    }

    @Override
    public void insertDisbursement(
            Connection unitOfWork, UUID id, LoanId loan, Optional<UUID> payoutMethod, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(payoutMethod, "payoutMethod");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_disbursement (id, loan_id, status, payout_method_id, created_at, deadline_at,"
                        // The three instants are the trigger's; the placeholders are overwritten.
                        + " next_attempt_at, correlation_id) VALUES (?, ?, 'PENDING', ?, statement_timestamp(),"
                        + " statement_timestamp() + interval '1 hour', statement_timestamp(), ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, loan.value());
            insert.setObject(3, payoutMethod.orElse(null));
            insert.setString(4, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a loan disbursement", failure);
        }
    }

    @Override
    public Optional<Owned> ownedBy(Connection unitOfWork, LoanId id, UUID party) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE l.id = ? AND l.party_id = ?")) {
            select.setObject(1, id.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(owned(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a loan", failure);
        }
    }

    @Override
    public List<Owned> listOwnedBy(Connection unitOfWork, UUID party, int limit) {
        Objects.requireNonNull(party, "party");
        if (limit < 1) {
            throw new IllegalArgumentException("a listing reads at least one loan");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE l.party_id = ? ORDER BY l.opened_at DESC, l.id LIMIT ?")) {
            select.setObject(1, party);
            select.setInt(2, limit);
            List<Owned> loans = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    loans.add(owned(rows));
                }
            }
            return loans;
        } catch (SQLException failure) {
            throw failed("listing a party's loans", failure);
        }
    }

    @Override
    public Money committedPrincipal(Connection unitOfWork, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT a.amount_minor, a.scale" + FROM + " WHERE l.status = 'PENDING_DISBURSEMENT' AND l.currency = ?")) {
            select.setString(1, currency.code());
            Money committed = null;
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    Money principal = Money.ofPersisted(rows.getLong(1), currency, rows.getInt(2));
                    // Folded through Money, never a SQL SUM (INV-MON-01): two scales in one currency refuse loudly.
                    committed = committed == null ? principal : committed.plus(principal);
                }
            }
            return committed == null ? Money.zero(currency) : committed;
        } catch (SQLException failure) {
            throw failed("reading the committed principal", failure);
        }
    }

    @Override
    public Optional<UUID> partyOf(Connection unitOfWork, LoanId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT party_id FROM lending.loan WHERE id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getObject(1, UUID.class)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's party", failure);
        }
    }

    @Override
    public Optional<Owned> lock(Connection unitOfWork, LoanId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT l.id FROM lending.loan l WHERE l.id = ? FOR UPDATE OF l")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
            }
        } catch (SQLException failure) {
            throw failed("locking a loan", failure);
        }
        return lockedRead(unitOfWork, id);
    }

    @Override
    public LocalDate principalValueDate(Connection unitOfWork, LoanId loan, LocalDate today) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(today, "today");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT GREATEST(?::date, lending.principal_value_floor(?))")) {
            select.setObject(1, today);
            select.setObject(2, loan.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getObject(1, LocalDate.class);
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's principal value date", failure);
        }
    }

    /**
     * The loan just locked and its governing agreement version, read by a statement of its own AFTER the lock
     * (`P11-TSK-028`): a statement that waits on a row lock re-reads the locked row but reads every other table - the
     * agreement's versions - in the snapshot it took before it waited, so the lock's own statement could answer a version
     * an act committed while it waited (an amendment's acceptance, a prepayment) had already superseded. Under READ
     * COMMITTED this statement's snapshot follows the lock, and every writer of a version holds the same lock.
     */
    private Optional<Owned> lockedRead(Connection unitOfWork, LoanId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS + FROM + " WHERE l.id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(owned(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a locked loan", failure);
        }
    }

    @Override
    public boolean activate(Connection unitOfWork, LoanId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan SET status = 'ACTIVE' WHERE id = ? AND status = 'PENDING_DISBURSEMENT'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("activating a loan", failure);
        }
    }

    @Override
    public boolean cancel(Connection unitOfWork, LoanId id, CancellationReason reason) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // The terminal instant is the trigger's.
                "UPDATE lending.loan SET status = 'CANCELLED', cancellation_reason = ?"
                        + " WHERE id = ? AND status = 'PENDING_DISBURSEMENT'")) {
            update.setString(1, reason.name());
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("cancelling a loan", failure);
        }
    }

    @Override
    public Optional<Owned> lockOwnedBy(Connection unitOfWork, LoanId id, UUID party) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT l.id FROM lending.loan l WHERE l.id = ? AND l.party_id = ? FOR UPDATE OF l")) {
            select.setObject(1, id.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
            }
        } catch (SQLException failure) {
            throw failed("locking a loan", failure);
        }
        return lockedRead(unitOfWork, id);
    }

    @Override
    public boolean close(Connection unitOfWork, LoanClosure closure, LoanStatus from, CorrelationId correlation) {
        Objects.requireNonNull(closure, "closure");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_closure (id, loan_id, reason, currency, scale, refund_minor,"
                        + " refund_journal_entry_id, repayment_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, closure.id());
            insert.setObject(++i, closure.loan().value());
            insert.setString(++i, closure.reason().name());
            insert.setString(++i, closure.refund().currency().code());
            insert.setInt(++i, closure.refund().scale());
            insert.setLong(++i, closure.refund().minorUnits());
            insert.setObject(++i, closure.refundEntry().map(com.finapp.ledger.JournalEntryId::value).orElse(null));
            insert.setObject(++i, closure.repayment().orElse(null));
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a loan closure", failure);
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // The terminal instant is the trigger's.
                "UPDATE lending.loan SET status = 'CLOSED', closure_reason = ? WHERE id = ? AND status = ?")) {
            update.setString(1, closure.reason().name());
            update.setObject(2, closure.loan().value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("closing a loan", failure);
        }
    }

    @Override
    public boolean beginClosing(Connection unitOfWork, LoanId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan SET status = 'CLOSING' WHERE id = ? AND status = 'ACTIVE' AND kind = 'REVOLVING'")) {
            update.setObject(1, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving a credit line to closing", failure);
        }
    }

    @Override
    public java.util.Map<LoanProduct, java.util.Map<LoanStatus, Long>> countsByProductAndStatus(Connection unitOfWork) {
        java.util.Map<LoanProduct, java.util.Map<LoanStatus, Long>> counts = new java.util.EnumMap<>(LoanProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT product, status, count(*) FROM lending.loan GROUP BY product, status");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.computeIfAbsent(LoanProduct.valueOf(rows.getString(1)),
                        product -> new java.util.EnumMap<>(LoanStatus.class))
                        .put(LoanStatus.valueOf(rows.getString(2)), rows.getLong(3));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting loan accounts", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Owned owned(ResultSet row) throws SQLException {
        LoanProduct product = LoanProduct.valueOf(row.getString("product"));
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        LoanId loanId = LoanId.of(row.getObject("id", UUID.class));
        Timestamp closed = row.getTimestamp("closed_at");
        Loan loan = new Loan(
                loanId,
                row.getObject("party_id", UUID.class),
                product,
                LoanStatus.valueOf(row.getString("status")),
                currency,
                LoanOfferId.of(row.getObject("offer_id", UUID.class)),
                row.getObject("decision_id", UUID.class),
                row.getTimestamp("opened_at").toInstant(),
                Optional.ofNullable(closed).map(Timestamp::toInstant),
                CorrelationId.of(row.getString("correlation_id")));
        UUID agreementOffer = row.getObject("agreement_offer_id", UUID.class);
        LoanAgreement agreement = new LoanAgreement(
                LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                loanId,
                row.getInt("version"),
                Optional.ofNullable(agreementOffer).map(LoanOfferId::of),
                Optional.ofNullable(row.getObject("amendment_id", UUID.class)),
                Optional.ofNullable(row.getObject("prepayment_id", UUID.class)),
                row.getObject("agreement_decision_id", UUID.class),
                LoanTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                product,
                Money.ofPersisted(row.getLong("amount_minor"), currency, row.getInt("scale")),
                optionalInt(row, "term_months"),
                new BigDecimal(row.getString("nominal_annual_rate")),
                row.getInt("repayment_day"),
                optionalInt(row, "schedule_engine_version").map(n -> engine(LendingEngine.SCHEDULE, n)),
                engine(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version")),
                engine(LendingEngine.ALLOCATION, row.getInt("allocation_engine_version")),
                optionalInt(row, "statement_engine_version").map(n -> engine(LendingEngine.STATEMENT, n)),
                AgreementTemplate.of(row.getString("agreement_template_id"), row.getInt("agreement_template_version"))
                        .orElseThrow(() -> new IllegalStateException("an agreement names a template this build registers")),
                row.getString("canonical_terms"),
                row.getBytes("terms_sha256"),
                row.getTimestamp("effective_from").toInstant());
        return new Owned(loan, agreement);
    }

    private static EngineVersion engine(LendingEngine engine, int number) {
        return EngineVersion.of(engine, number)
                .orElseThrow(() -> new IllegalStateException("an agreement pins an engine version this build holds"));
    }

    private static Optional<Integer> optionalInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? Optional.empty() : Optional.of(value);
    }

    private static void setInt(PreparedStatement statement, int index, Optional<Integer> value) throws SQLException {
        if (value.isPresent()) {
            statement.setInt(index, value.get());
        } else {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
