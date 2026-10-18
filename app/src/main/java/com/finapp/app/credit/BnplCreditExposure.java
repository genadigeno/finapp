package com.finapp.app.credit;

import com.finapp.bnpl.BnplAccounts;
import com.finapp.credit.CreditStorageException;
import com.finapp.credit.PlatformCreditExposure;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.OwnerKind;
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
 * The platform's credit exposure to a party, version 3 (`P12-TSK-012`; PHASE_12_PLAN.md section 12.10, ADR-0104;
 * {@code INV-BNPL-10}, {@code INV-CRD-09}, {@code INV-LND-06}, {@code INV-BAL-05}): version 2's every unit of loan and
 * line principal ({@link LendingCreditExposure}'s table, unchanged) <strong>plus</strong>, per BNPL obligation of the
 * party in the currency, the DR - CR of its {@code BNPL_PRINCIPAL} and {@code BNPL_PRINCIPAL_DUE} journal lines - read
 * in ONE SQL statement over lending's definer view {@code lending.party_principal_exposure} (lending {@code V007}), bnpl's
 * {@code bnpl.party_principal_exposure} (bnpl {@code V007}) and the ledger's journal lines - never a balance column. It
 * replaces version 2 in credit's {@code SnapshotFreezer}; decisions frozen under versions 1 and 2 replay from their own
 * snapshots, which carry their version as provenance ({@code INV-CRD-01}).
 *
 * <pre>
 * a loan or a line:      as version 2 ({@link LendingCreditExposure.LoanPrincipal#figure})
 * a BNPL obligation:     DR - CR of its principal lines (not yet due + billed), whatever its status
 * outstandingFor         = the sum over the party's loans, lines and BNPL obligations in the currency
 * </pre>
 *
 * <p><strong>Principal only</strong> (lending A3): interest accrued or billed, late fees and the credit balance are not
 * credit a decision extended. <strong>No committed term for BNPL</strong>: a purchase is financed in the commit that
 * consumes its decision (section 12.4), so the obligation's principal lines exist from the instant the reservation ends.
 * <strong>Every obligation, whatever its status</strong>: a {@code CLOSED} obligation's principal is zero by the closure
 * rule, so it adds nothing - and a defect that left principal on a closed obligation is still exposure (lending's
 * as-built choice; the plan's "not {@code CLOSED}" is the same figure at rest). <strong>A principal below zero counts
 * zero for its obligation</strong>, never offsetting another's.
 *
 * <h2>Why one statement</h2>
 *
 * <p>A purchase (`P12-TSK-013`) consumes its decision - credit's reservation ends - and posts the obligation's principal
 * in one local transaction under L0. Credit's deciding transaction takes L0 and reads the reservation and this figure in
 * statements that begin after the lock: a decision that waited behind the purchase reads the principal here and no longer
 * the reservation; one that held L0 first read the reservation while the purchase waited. And within this read, one
 * statement reads one snapshot, so a loan disbursing or a purchase committing while it runs is counted once - never in
 * neither of two statements, never in both. (Views, not functions: a volatile PL/pgSQL body would take a fresh snapshot
 * per query under {@code READ COMMITTED}.)
 *
 * <p><strong>Failure</strong>: a read that fails throws ({@link CreditStorageException}, the SQLState only) and so fails
 * the deciding transaction - credit's sweep re-drives it; never zero. A row it cannot interpret - an unknown source, kind
 * or status, a loan without an agreement, an obligation with no principal line, two scales in one currency - is refused,
 * never skipped. Read-only and lock-free; no state, no cache: any number of instances read the same figure.
 */
public final class BnplCreditExposure implements PlatformCreditExposure<Connection> {

    /** This composition's version, recorded as the snapshot's provenance ({@code INV-CRD-01}). */
    public static final int VERSION = 3;

    /**
     * Per loan account and per BNPL obligation of the party in the currency, one row per scale its principal lines were
     * written at: the source, the subject, its kind (a loan's kind, an obligation's product), its status, a loan's latest
     * agreement amount and scale, and the net DR - CR of its principal lines (lending's two purposes and bnpl's two,
     * bound). ONE statement: both views and the ledger read in one snapshot.
     */
    static final String PER_SUBJECT = "SELECT 'LOAN' AS source, e.loan_id AS subject, e.kind, e.status,"
            + " e.agreement_scale, e.agreement_amount_minor, p.scale, p.net_minor"
            + " FROM lending.party_principal_exposure e"
            + " LEFT JOIN LATERAL (SELECT j.scale,"
            + " sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor END) AS net_minor"
            + " FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
            + " WHERE a.owner_kind = ? AND a.owner_ref = e.loan_id AND a.currency = e.currency"
            + " AND j.currency = e.currency AND a.purpose IN (?, ?)"
            + " GROUP BY j.scale) p ON true"
            + " WHERE e.party_id = ? AND e.currency = ?"
            + " UNION ALL"
            + " SELECT 'BNPL' AS source, b.obligation_id AS subject, b.product, b.status,"
            + " CAST(NULL AS smallint), CAST(NULL AS bigint), q.scale, q.net_minor"
            + " FROM bnpl.party_principal_exposure b"
            + " LEFT JOIN LATERAL (SELECT j.scale,"
            + " sum(CASE j.direction WHEN 'DEBIT' THEN j.amount_minor ELSE -j.amount_minor END) AS net_minor"
            + " FROM ledger.ledger_account a JOIN ledger.journal_line j ON j.ledger_account_id = a.id"
            + " WHERE a.owner_kind = ? AND a.owner_ref = b.obligation_id AND a.currency = b.currency"
            + " AND j.currency = b.currency AND a.purpose IN (?, ?)"
            + " GROUP BY j.scale) q ON true"
            + " WHERE b.party_id = ? AND b.currency = ?"
            + " ORDER BY 1, 2, 7";

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(currency, "currency");
        try (PreparedStatement select = unitOfWork.prepareStatement(PER_SUBJECT)) {
            int next = bindLoanArm(select, 1);
            select.setObject(next, partyId);
            select.setString(next + 1, currency.code());
            next = bindBnplArm(select, next + 2);
            select.setObject(next, partyId);
            select.setString(next + 1, currency.code());
            Money total = Money.zero(currency);
            for (Subject subject : subjects(select, currency)) {
                total = total.plus(subject.figure());
            }
            return total;
        } catch (SQLException failure) {
            throw new CreditStorageException(DatabaseFailure.describe("reading a party's platform credit exposure", failure));
        }
    }

    /** Binds the loan arm's owner kind and lending's two principal purposes from {@code index}; the next index. */
    static int bindLoanArm(PreparedStatement select, int index) throws SQLException {
        return bind(select, index, OwnerKind.LOAN, com.finapp.lending.LoanAccounts.principalPurposes());
    }

    /**
     * Binds the BNPL arm's owner kind and bnpl's two principal purposes ({@link BnplAccounts#principalPurposes} - bnpl's
     * declaration, never restated here, so {@code app} never names the BNPL book) from {@code index}; the next index.
     */
    static int bindBnplArm(PreparedStatement select, int index) throws SQLException {
        return bind(select, index, OwnerKind.BNPL_OBLIGATION, BnplAccounts.principalPurposes());
    }

    private static int bind(PreparedStatement select, int index, OwnerKind owner, List<AccountPurpose> purposes)
            throws SQLException {
        if (purposes.size() != 2) {
            throw new IllegalStateException("the exposure read binds exactly two principal purposes per arm");
        }
        select.setString(index, owner.name());
        select.setString(index + 1, purposes.get(0).name());
        select.setString(index + 2, purposes.get(1).name());
        return index + 3;
    }

    /** The rows of a {@link #PER_SUBJECT}-shaped statement (its first eight columns), folded per subject. */
    static List<Subject> subjects(PreparedStatement select, CurrencyCode currency) throws SQLException {
        Map<String, Subject> bySubject = new LinkedHashMap<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String source = rows.getString(1);
                UUID subject = rows.getObject(2, UUID.class);
                Subject known = bySubject.get(source + ":" + subject);
                if (known == null) {
                    known = switch (source) {
                        case "LOAN" -> {
                            long amount = rows.getLong(6);
                            if (rows.wasNull()) {
                                // No loan or obligation id in an exception message (INV-AUD-02, P11-DOC-001).
                                throw new IllegalStateException("a loan with no agreement - refused, never read as zero");
                            }
                            yield new Loan(new LendingCreditExposure.LoanPrincipal(subject, rows.getString(3),
                                    rows.getString(4), Money.ofPersisted(amount, currency, rows.getInt(5)),
                                    new ArrayList<>()));
                        }
                        case "BNPL" -> new Obligation(subject, rows.getString(3), rows.getString(4), currency,
                                new ArrayList<>());
                        default -> throw new IllegalStateException("an exposure row of an unknown source - refused");
                    };
                    bySubject.put(source + ":" + subject, known);
                }
                BigDecimal net = rows.getBigDecimal(8);
                if (net != null) {
                    known.lines().add(Money.ofPersisted(net.longValueExact(), currency, rows.getInt(7)));
                }
            }
        }
        return List.copyOf(bySubject.values());
    }

    /** One subject of a party's exposure: a loan account or a BNPL obligation. */
    sealed interface Subject permits Loan, Obligation {

        /** Its principal lines' nets, one per scale. */
        List<Money> lines();

        /** What it adds to the party's exposure. */
        Money figure();
    }

    /** A loan account - version 2's figure. */
    record Loan(LendingCreditExposure.LoanPrincipal principal) implements Subject {

        @Override
        public List<Money> lines() {
            return principal.drawnByScale();
        }

        @Override
        public Money figure() {
            return principal.figure();
        }

        /** The kind and status only - never a figure ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Loan[" + principal.kind() + ", " + principal.status() + "]";
        }
    }

    /**
     * A BNPL obligation: its product, status and principal lines' nets. Its figure is DR - CR of its principal, zero below
     * zero, for an {@code ACTIVE} or a {@code CLOSED} obligation alike.
     */
    record Obligation(UUID obligation, String product, String status, CurrencyCode currency, List<Money> lines)
            implements Subject {

        @Override
        public Money figure() {
            if (!"BNPL_PAY_IN_4".equals(product) && !"BNPL_PAY_IN_N".equals(product)) {
                throw unknown("of an unknown product");
            }
            if (!"ACTIVE".equals(status) && !"CLOSED".equals(status)) {
                throw unknown("in an unknown status");
            }
            if (lines.isEmpty()) {
                // The purchase posts F1 in the commit that births the obligation (INV-BNPL-02): no principal line at
                // all is a broken record, never "nothing owed".
                throw unknown("without a principal line");
            }
            Money principal = lines.get(0).minus(lines.get(0));
            for (Money net : lines) {
                principal = principal.plus(net);
            }
            return principal.isNegative() ? principal.minus(principal) : principal;
        }

        /** The product and status only - never a figure or the obligation ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Obligation[" + product + ", " + status + "]";
        }

        private IllegalStateException unknown(String what) {
            return new IllegalStateException("a BNPL obligation " + what
                    + " - not a state the exposure read knows; refused, never read as zero");
        }
    }
}
