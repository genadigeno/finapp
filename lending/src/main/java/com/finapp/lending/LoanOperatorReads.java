package com.finapp.lending;

import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's reads of one account's schedule and statements (`P17-TSK-035`, gap G-82) - the views its borrower reads
 * ({@link LoanSchedules}, {@link CreditLineStatements}), served to a holder of {@code LENDING_INVESTIGATE}. The self-dealing
 * judgement every investigation of an account makes comes first ({@link LoanSelfDealing}, the explanation's rule): an
 * investigator of the loan's own party - or one the platform cannot place in a party - is refused
 * ({@link RepaymentReversals.SelfDealingRefused}) before anything is read; recording the refusal, and the serving, is the
 * caller's. Reads only, on the caller's unit of work.
 */
@RequiredArgsConstructor
public final class LoanOperatorReads {

    @NonNull private final LoanStore loans;
    @NonNull private final LoanActingParty actingParties;
    @NonNull private final LoanSchedules schedules;
    @NonNull private final CreditLineStatements statements;

    /**
     * {@code loan}'s schedule version {@code version} - its latest when absent - with its billed items; empty for a loan not yet
     * disbursed or a version that does not exist.
     *
     * @throws RepaymentReversals.SelfDealingRefused the loan is the investigator's own party's
     * @throws LoanExplanations.LoanNotFound no such loan
     */
    public Optional<LoanSchedules.Read> schedule(Connection unitOfWork, LoanId loan, Optional<Integer> version, Actor actor) {
        UUID party = judged(unitOfWork, loan, actor, "schedule");
        return schedules.read(unitOfWork, loan, party, version);
    }

    /**
     * {@code loan}'s statements, oldest first; empty for an instalment loan - a line not yet stated answers an empty list.
     *
     * @throws RepaymentReversals.SelfDealingRefused the loan is the investigator's own party's
     * @throws LoanExplanations.LoanNotFound no such loan
     */
    public Optional<List<CreditLineStatements.StatementRead>> statements(Connection unitOfWork, LoanId loan, Actor actor) {
        UUID party = judged(unitOfWork, loan, actor, "statements");
        return statements.read(unitOfWork, loan, party);
    }

    private UUID judged(Connection unitOfWork, LoanId loan, Actor actor, String act) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(actor, "actor");
        return LoanSelfDealing.judge(loans, actingParties, unitOfWork, loan, actor, act, LoanExplanations.LoanNotFound::new);
    }
}
