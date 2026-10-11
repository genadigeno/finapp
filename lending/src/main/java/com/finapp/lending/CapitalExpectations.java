package com.finapp.lending;

import com.finapp.ledger.LedgerAccount;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The port through which an approved contribution opens its {@code LENDING_CAPITAL_CONTRIBUTION} expectation
 * (`P11-TSK-004`, ADR-0096 section 4): declared here, implemented in {@code app} over reconciliation's opener - lending
 * has no edge to {@code reconciliation} and reconciliation never calls lending. Called on the approval's own
 * connection, so the approval and its expectation commit together or not at all.
 */
public interface CapitalExpectations {

    /**
     * Opens - or converges on - the contribution's expectation: INBOUND on {@code capitalAccount}, under the settlement
     * bank's statement source, keyed by {@code reference}, for exactly {@code amount}; its window the bank source's
     * pinned funding lag from {@code approvedOn}.
     *
     * @return the expectation's reference - what the approval records, once ({@code UNIQUE (expectation_ref)})
     */
    String open(Connection unitOfWork, Opening opening);

    /** What the opening states. */
    record Opening(
            CapitalContributionId contribution,
            Money amount,
            String reference,
            LedgerAccount capitalAccount,
            LocalDate approvedOn,
            Actor approver,
            Instant at,
            CorrelationId correlation) {

        public Opening {
            Objects.requireNonNull(contribution, "contribution must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(capitalAccount, "capitalAccount must not be null");
            Objects.requireNonNull(approvedOn, "approvedOn must not be null");
            Objects.requireNonNull(approver, "approver must not be null");
            Objects.requireNonNull(at, "at must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (!capitalAccount.currency().equals(amount.currency())) {
                throw new IllegalArgumentException("a contribution opens on its own currency's capital account");
            }
        }

        /** Identifiers only - never the amount or the reference ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Opening[" + contribution.value() + "]";
        }
    }
}
