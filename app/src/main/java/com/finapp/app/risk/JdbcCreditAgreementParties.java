package com.finapp.app.risk;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.lending.LoanId;
import com.finapp.risk.RiskStorageException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditAgreementParties} over the two modules' published definer views (`P13-TSK-025`):
 * {@code lending.party_principal_exposure} (lending {@code V007}) and {@code bnpl.party_principal_exposure} (bnpl
 * {@code V007}) - the read surfaces lending and bnpl already grant the application role for the platform's exposure read
 * ({@code BnplCreditExposure}), each a row per agreement with its party. One statement per arm, by the agreement's id,
 * the party alone selected: never a table of either module, never a lock, never a figure. Neither module changes.
 *
 * <p>A read by identifier alone: the identifier is a reference the platform recorded itself - the agreement a default
 * signal's subject named, carried by risk's alert - never a request's; {@code OwnershipIsScopedTest} classifies both
 * reads {@code SYSTEM_JOIN}, called by {@link IdentitySubjectParties} alone. A failure names the operation and the
 * SQLSTATE only.
 */
public final class JdbcCreditAgreementParties implements CreditAgreementParties {

    static final String LOAN = "SELECT party_id FROM lending.party_principal_exposure WHERE loan_id = ?";

    static final String OBLIGATION = "SELECT party_id FROM bnpl.party_principal_exposure WHERE obligation_id = ?";

    @Override
    public Optional<UUID> partyOfLoan(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(LOAN)) {
            select.setObject(1, loan.value());
            return party(select);
        } catch (SQLException failure) {
            throw failed("reading a loan's party", failure);
        }
    }

    @Override
    public Optional<UUID> partyOfObligation(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(OBLIGATION)) {
            select.setObject(1, obligation.value());
            return party(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL obligation's party", failure);
        }
    }

    /** The one row's party, or empty; two rows for one id would be a view no longer keyed by its agreement. */
    private static Optional<UUID> party(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            UUID party = Objects.requireNonNull(row.getObject(1, UUID.class), "an agreement names its party");
            if (row.next()) {
                throw new IllegalStateException("an agreement's view answered two rows for one id");
            }
            return Optional.of(party);
        }
    }

    private static RiskStorageException failed(String operation, SQLException failure) {
        return new RiskStorageException("risk's party join failed " + operation + ": SQLSTATE "
                + failure.getSQLState());
    }
}
