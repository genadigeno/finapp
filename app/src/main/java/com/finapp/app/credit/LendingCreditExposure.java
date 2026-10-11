package com.finapp.app.credit;

import com.finapp.credit.CreditStorageException;
import com.finapp.credit.PlatformCreditExposure;
import com.finapp.ledger.AccountPurpose;
import com.finapp.lending.LoanAccounts;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The platform's credit exposure to a party, version 2 (`P11-TSK-013`; PHASE_11_PLAN.md section 12.10, ADR-0091 points
 * 5 to 7; {@code INV-CRD-09}, {@code INV-LND-06}, {@code INV-BAL-05}): every unit of principal the platform has
 * committed, lent or promised on an open line, read in ONE SQL statement over lending's definer view
 * {@code lending.party_principal_exposure} (lending {@code V007}) and the ledger's journal lines - never a balance
 * column. It replaces Phase 10's zero ({@code NoLoansUntilPhase11}, version 1) in credit's {@code SnapshotFreezer}.
 *
 * <pre>
 * a loan (INSTALMENT):  committed   = its latest agreement's principal, while PENDING_DISBURSEMENT
 *                       outstanding = DR - CR of its principal lines (not yet due + billed), unless CANCELLED
 * a line (REVOLVING):   ACTIVE      = GREATEST(its latest agreement's limit, its drawn principal)
 *                       otherwise   = its drawn principal (CLOSING; CLOSED holds zero by the closure rule)
 * outstandingFor        = the sum over the party's loan accounts in the currency
 * </pre>
 *
 * <p><strong>Principal only</strong> (A3): interest accrued or billed, fees and the credit balance are not credit the
 * platform extended by a decision, and the bureau's total balance - the policy's comparable external figure - is
 * principal too. <strong>An open line counts its limit</strong>: its holder can draw to it without any decision, so the
 * whole limit is promised; drawn exceeds the limit only after a paydown is reversed past a re-draw (`-020`, `-023`),
 * and then the drawn principal is what is owed.
 *
 * <h2>Why one statement, and why that is the whole of the "never torn" argument</h2>
 *
 * <p>A disbursement (`P11-TSK-014`) moves a loan from committed to outstanding in one local transaction: the loan's
 * {@code PENDING_DISBURSEMENT -> ACTIVE} edge and its principal posting commit together. A read of the committed arm and
 * a read of the outstanding arm in two statements could straddle that commit - the committed arm read before it (the
 * principal) and the outstanding arm after (the principal again): counted twice; or in the other order, counted in
 * neither. One statement reads one snapshot, so the loan is either pending with no principal line or active with its
 * principal line - counted exactly once. (A view is read inside the statement's snapshot; a function would not be: a
 * volatile PL/pgSQL body takes a fresh snapshot per query under READ COMMITTED, which is why this is a view.)
 *
 * <h2>Where it is read, and why that is the whole of the "no gap" argument</h2>
 *
 * <p>Inside credit's deciding transaction, under the party's {@code credit_profile} row lock (L0), beside the reserved
 * exposure ({@code DecisionMaking.basisWithin}: both re-read; a change in either freezes a successor snapshot). The
 * acceptance (`P11-TSK-012`) takes L0 first and consumes the decision and commits the loan in one transaction, so a
 * decision that waited on it reads the commitment here and no longer reads the reservation - and one that held L0 first
 * read the reservation and the acceptance waited: the decision sees one or the other, never neither and never both.
 *
 * <p><strong>Failure</strong>: a read that fails throws ({@link CreditStorageException}, the SQLState only) and so fails
 * the deciding transaction - credit's sweep re-drives it. It never answers zero, and a row it cannot interpret (an
 * agreement amount absent, an unknown kind or status, two scales in one currency) is refused, never skipped. A
 * principal balance below zero - a ledger defect its own guards refuse - counts zero for that loan, never offsetting
 * another's.
 *
 * <p>Read-only and lock-free: it takes no lock of its own (L0 is the caller's), holds no state and caches nothing, so
 * any number of instances read the same figure from the same rows.
 */
public final class LendingCreditExposure implements PlatformCreditExposure<Connection> {

    /** This composition's version, recorded as the snapshot's provenance ({@code INV-CRD-01}). */
    public static final int VERSION = 2;

    /**
     * Per loan account of the party in the currency: its kind, status, latest agreement amount and scale, and - one row
     * per scale its principal lines were written at (one, unless a defect) - the net DR - CR of its journal lines on
     * lending's two principal purposes (bound, {@link #bindPrincipalPurposes}). ONE statement: the view and the ledger
     * read in one snapshot.
     */
    static final String PER_LOAN = "SELECT e.loan_id, e.kind, e.status, e.agreement_scale, e.agreement_amount_minor,"
            + " p.scale, p.net_minor"
            + " FROM lending.party_principal_exposure e"
            + " LEFT JOIN LATERAL (SELECT j.scale,"
            + " sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor END) AS net_minor"
            + " FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
            + " WHERE a.owner_kind = 'LOAN' AND a.owner_ref = e.loan_id AND a.currency = e.currency"
            + " AND j.currency = e.currency AND a.purpose IN (?, ?)"
            + " GROUP BY j.scale) p ON true"
            + " WHERE e.party_id = ? AND e.currency = ?"
            + " ORDER BY e.loan_id, p.scale";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(currency, "currency");
        try (PreparedStatement select = unitOfWork.prepareStatement(PER_LOAN)) {
            int next = bindPrincipalPurposes(select);
            select.setObject(next, partyId);
            select.setString(next + 1, currency.code());
            Money total = Money.zero(currency);
            for (LoanPrincipal loan : loans(select, currency)) {
                total = total.plus(loan.figure());
            }
            return total;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a party's platform credit exposure", failure));
        }
    }

    /**
     * Binds lending's principal purposes ({@link LoanAccounts#principalPurposes}) to the statement's first two
     * parameters - lending's declaration of which accounts hold principal, never restated here; the next index.
     */
    static int bindPrincipalPurposes(PreparedStatement select) throws SQLException {
        List<AccountPurpose> purposes = LoanAccounts.principalPurposes();
        if (purposes.size() != 2) {
            throw new IllegalStateException("the exposure read binds exactly the two principal purposes");
        }
        select.setString(1, purposes.get(0).name());
        select.setString(2, purposes.get(1).name());
        return 3;
    }

    /** The rows of a {@link #PER_LOAN}-shaped statement (its first seven columns), folded per loan account. */
    static List<LoanPrincipal> loans(PreparedStatement select, CurrencyCode currency) throws SQLException {
        Map<UUID, LoanPrincipal> byLoan = new LinkedHashMap<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                UUID loan = rows.getObject(1, UUID.class);
                LoanPrincipal known = byLoan.get(loan);
                if (known == null) {
                    long amount = rows.getLong(5);
                    if (rows.wasNull()) {
                        throw new IllegalStateException("loan " + loan + " has no agreement - refused, never read as zero");
                    }
                    known = new LoanPrincipal(loan, rows.getString(2), rows.getString(3),
                            Money.ofPersisted(amount, currency, rows.getInt(4)), new ArrayList<>());
                    byLoan.put(loan, known);
                }
                BigDecimal net = rows.getBigDecimal(7);
                if (net != null) {
                    known.drawnByScale().add(Money.ofPersisted(net.longValueExact(), currency, rows.getInt(6)));
                }
            }
        }
        return List.copyOf(byLoan.values());
    }

    /** One loan account's exposure inputs: its kind, status, latest agreement amount and its principal lines' nets. */
    record LoanPrincipal(UUID loan, String kind, String status, Money agreed, List<Money> drawnByScale) {

        /** DR - CR of its principal accounts; two scales in one currency refuse loudly (Money). Zero when below zero. */
        Money drawn() {
            Money drawn = Money.ofPersisted(0, agreed.currency(), agreed.scale());
            for (Money net : drawnByScale) {
                drawn = drawn.plus(net);
            }
            return drawn.isNegative() ? Money.ofPersisted(0, agreed.currency(), agreed.scale()) : drawn;
        }

        /** What this loan account adds to the party's exposure (the class comment's table). */
        Money figure() {
            Money drawn = drawn();
            return switch (kind) {
                case "INSTALMENT" -> switch (status) {
                    case "PENDING_DISBURSEMENT" -> agreed.plus(drawn);
                    case "ACTIVE", "CLOSED" -> drawn;
                    case "CANCELLED" -> Money.ofPersisted(0, agreed.currency(), agreed.scale());
                    default -> throw unknown();
                };
                case "REVOLVING" -> switch (status) {
                    case "ACTIVE" -> drawn.compareTo(agreed) > 0 ? drawn : agreed;
                    case "CLOSING", "CLOSED" -> drawn;
                    default -> throw unknown();
                };
                default -> throw unknown();
            };
        }

        private IllegalStateException unknown() {
            return new IllegalStateException("loan " + loan + " is a " + kind + " " + status
                    + " - not a state the exposure read knows; refused, never read as zero");
        }
    }
}
