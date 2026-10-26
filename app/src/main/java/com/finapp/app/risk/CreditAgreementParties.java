package com.finapp.app.risk;

import com.finapp.bnpl.BnplObligationId;
import com.finapp.lending.LoanId;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * The party a credit agreement belongs to (`P13-TSK-025`) - the join a default's collections case needs: lending's and
 * bnpl's default events name the loan or the obligation, never the borrower ({@code INV-AUD-02}), and risk cannot see
 * either module, so {@code app} places the agreement's party for risk's accountable join
 * ({@link IdentitySubjectParties#accountablePartyOf}). A frozen fact of each module - an agreement's party never changes -
 * read by identifier on the caller's connection, lock-free, never cached. Empty when no such agreement exists.
 */
public interface CreditAgreementParties {

    /** Places nothing - the composition before `-025`, and the suites that need no agreement. */
    CreditAgreementParties NONE = new CreditAgreementParties() {
        @Override
        public Optional<UUID> partyOfLoan(Connection unitOfWork, LoanId loan) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> partyOfObligation(Connection unitOfWork, BnplObligationId obligation) {
            return Optional.empty();
        }
    };

    /** The borrower of lending's {@code loan}. */
    Optional<UUID> partyOfLoan(Connection unitOfWork, LoanId loan);

    /** The customer of bnpl's {@code obligation}. */
    Optional<UUID> partyOfObligation(Connection unitOfWork, BnplObligationId obligation);
}
