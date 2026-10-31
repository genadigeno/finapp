package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.SourceProofs;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.fx.FxBooksProof;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * accounting's {@link SourceProofs} over the product proofs as their modules built them (`P14-TSK-010`; ADR-0117
 * decision 2; {@code PHASE_14_PLAN.md} section 13.3; {@code INV-ACC-12}) - in {@code app}, the one module that sees
 * them all: lending's {@link LendingProofs} (the {@link LoanSubledgerProof} and {@link LoanReplayProof} in one reading),
 * the {@link LendingCapitalProof}, {@link BnplProofs} (the {@link BnplSubledgerProof} and {@link BnplThreeWayProof}),
 * fx's {@link FxBooksProof}, reconciliation's {@link PositionProof}, merchant's {@link MerchantPayable} drill-down and the
 * ledger's {@link ProjectionVerification}. <strong>Each verdict as its proof states it - never re-implemented</strong>:
 * this adapter only reads a proof's failing verdicts into {@link SourceProofs.Discrepancy discrepancies}, naming the
 * proof's subject and its failing arms and counts - never an amount ({@code INV-AUD-02}).
 *
 * <p>Two proofs have no census of their own, so this adapter enumerates their subjects with one {@code SELECT} each over
 * the ledger's chart - the {@code LedgerMonitoredLedger} precedent of an {@code app} read over another schema: the
 * merchants holding a {@code MERCHANT_PAYABLE} (each payable's terms must sum to its position, {@code INV-MER-02}), and the
 * accounts with lines or a projection row ({@code ProjectionVerification}'s own definition of "posted", per account by
 * its own {@code verdictOf}, so no drifting account goes unnamed - its census names twenty). An account
 * {@code IN_FLIGHT} makes the projection reading incomplete, never clean.
 *
 * <p>Read-only, on the caller's {@code REPEATABLE READ READ ONLY} snapshot ({@code LendingProofs} and {@code BnplProofs}
 * refuse any other); each reader is a seam, so the hermetic suite proves the mapping with the proofs' own verdict types.
 */
public final class ProductSourceProofs implements SourceProofs {

    /** The merchants with a payable - the drill-down's census. */
    static final String MERCHANTS = "SELECT DISTINCT owner_ref FROM ledger.ledger_account"
            + " WHERE purpose = 'MERCHANT_PAYABLE' AND owner_ref IS NOT NULL ORDER BY owner_ref";

    /** The accounts with lines or a projection row - {@code ProjectionVerification}'s census. */
    static final String POSTED_ACCOUNTS = "SELECT ledger_account_id FROM ledger.journal_line"
            + " UNION SELECT ledger_account_id FROM ledger.account_balance ORDER BY 1";

    /** One merchant payable: whether its drill-down's terms sum to its position. */
    public record PayableTerms(UUID merchant, String currency, boolean sumHolds) {

        public PayableTerms {
            Objects.requireNonNull(merchant, "merchant");
            Objects.requireNonNull(currency, "currency");
        }
    }

    /** The projection verified per account: the drifting ones, and how many were in flight. */
    public record Projections(List<UUID> drifting, long inFlight) {

        public Projections {
            drifting = List.copyOf(drifting);
        }
    }

    private final Function<Connection, LendingProofs.Reading> lending;
    private final Function<Connection, LendingCapitalProof.Report> capital;
    private final Function<Connection, BnplProofs.Reading> bnpl;
    private final Function<Connection, FxBooksProof.Report> fx;
    private final Function<Connection, PositionProof.Report> position;
    private final Function<Connection, List<PayableTerms>> payables;
    private final Function<Connection, Projections> projections;

    public ProductSourceProofs(
            Function<Connection, LendingProofs.Reading> lending,
            Function<Connection, LendingCapitalProof.Report> capital,
            Function<Connection, BnplProofs.Reading> bnpl,
            Function<Connection, FxBooksProof.Report> fx,
            Function<Connection, PositionProof.Report> position,
            Function<Connection, List<PayableTerms>> payables,
            Function<Connection, Projections> projections) {
        this.lending = Objects.requireNonNull(lending, "lending");
        this.capital = Objects.requireNonNull(capital, "capital");
        this.bnpl = Objects.requireNonNull(bnpl, "bnpl");
        this.fx = Objects.requireNonNull(fx, "fx");
        this.position = Objects.requireNonNull(position, "position");
        this.payables = Objects.requireNonNull(payables, "payables");
        this.projections = Objects.requireNonNull(projections, "projections");
    }

    @Override
    public List<Reading> read(Connection snapshot, Source source) {
        Objects.requireNonNull(snapshot, "snapshot");
        return switch (Objects.requireNonNull(source, "source")) {
            case LENDING -> lendingReadings(lending.apply(snapshot));
            case LENDING_CAPITAL -> List.of(capitalReading(capital.apply(snapshot)));
            case BNPL -> bnplReadings(bnpl.apply(snapshot));
            case FX_BOOKS -> List.of(fxReading(fx.apply(snapshot)));
            case POSITION -> List.of(positionReading(position.apply(snapshot)));
            case MERCHANT_PAYABLE -> List.of(payableReading(payables.apply(snapshot)));
            case PROJECTION -> List.of(projectionReading(projections.apply(snapshot)));
        };
    }

    // ------------------------------------------------------------------ each proof's failing verdicts

    static List<Reading> lendingReadings(LendingProofs.Reading reading) {
        List<Discrepancy> subledger = reading.failures().stream()
                .map(verdict -> new Discrepancy(verdict.loan().value().toString(), "kind=" + verdict.kind() + ", status="
                        + verdict.status() + ", fails=" + verdict.failures()))
                .toList();
        List<Discrepancy> replay = reading.divergences().stream()
                .map(replayed -> new Discrepancy(replayed.loan().value().toString(), "diverged="
                        + replayed.findings().stream().map(finding -> finding.check().name()).distinct().sorted()
                                .toList() + ", findings=" + replayed.findings().size()))
                .toList();
        return List.of(new Reading(Proof.LOAN_SUBLEDGER, true, subledger),
                new Reading(Proof.LOAN_REPLAY, true, replay));
    }

    static Reading capitalReading(LendingCapitalProof.Report report) {
        return new Reading(Proof.LENDING_CAPITAL, true, report.currencies().stream()
                .filter(verdict -> !verdict.holds())
                .map(verdict -> new Discrepancy(verdict.currency().code(), "complete=" + verdict.complete()
                        + ", unexplainedEntries=" + verdict.unexplainedEntries() + ", headroomHolds="
                        + verdict.headroomHolds()))
                .toList());
    }

    static List<Reading> bnplReadings(BnplProofs.Reading reading) {
        List<Discrepancy> subledger = reading.failures().stream()
                .map(verdict -> new Discrepancy(verdict.obligation().value().toString(), "product=" + verdict.product()
                        + ", status=" + verdict.status() + ", fails=" + verdict.failures()))
                .toList();
        List<Discrepancy> threeWay = reading.currencies().stream()
                .filter(verdict -> !verdict.holds())
                .map(verdict -> new Discrepancy(verdict.currency().code(), "fails=" + verdict.failures()
                        + ", merchantLegsFailing=" + verdict.merchantLegs().stream().filter(leg -> !leg.holds()).count()
                        + ", identitiesFailing=" + verdict.identities().stream().filter(id -> !id.holds()).count()))
                .toList();
        return List.of(new Reading(Proof.BNPL_SUBLEDGER, true, subledger),
                new Reading(Proof.BNPL_THREE_WAY, true, threeWay));
    }

    static Reading fxReading(FxBooksProof.Report report) {
        return new Reading(Proof.FX_BOOKS, true, report.lines().stream()
                .filter(line -> !line.holds())
                .map(line -> new Discrepancy(line.book().name() + ":" + line.currency().code(),
                        "book=" + line.book().name() + ", currency=" + line.currency().code() + ", holds=false"))
                .toList());
    }

    static Reading positionReading(PositionProof.Report report) {
        List<Discrepancy> found = new ArrayList<>();
        for (PositionProof.PositionVerdict verdict : report.verdicts()) {
            if (!verdict.explained()) {
                String subject = verdict.purpose().name() + ":" + verdict.currency().code()
                        + verdict.counterparty().map(code -> ":" + code).orElse("");
                found.add(new Discrepancy(subject, "position explained=false, openExpectations=" + verdict.openCount()
                        + ", openItems=" + verdict.openItemCount()));
            }
        }
        for (PositionProof.SuspenseVerdict verdict : report.suspenseVerdicts()) {
            if (!verdict.explained()) {
                found.add(new Discrepancy("SUSPENSE_UNMATCHED:" + verdict.currency().code(),
                        "suspense explained=false, openItems=" + verdict.openItemCount()));
            }
        }
        for (PositionProof.CashVerdict verdict : report.cashVerdicts()) {
            if (!verdict.explained()) {
                found.add(new Discrepancy("CASH_AT_BANK:" + verdict.currency().code(), "cash explained=false, unbroken="
                        + verdict.unbroken() + ", latestSequence=" + verdict.latestSequence()));
            }
        }
        report.unattributedByPurpose().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> found.add(new Discrepancy("unattributed:" + entry.getKey().name(),
                        "lines no register row explains=" + entry.getValue())));
        return new Reading(Proof.POSITION, true, found);
    }

    static Reading payableReading(List<PayableTerms> payables) {
        return new Reading(Proof.MERCHANT_PAYABLE, true, payables.stream()
                .filter(payable -> !payable.sumHolds())
                .map(payable -> new Discrepancy(payable.merchant() + ":" + payable.currency(),
                        "termsSumToPosition=false"))
                .toList());
    }

    static Reading projectionReading(Projections projections) {
        return new Reading(Proof.PROJECTION, projections.inFlight() == 0, projections.drifting().stream()
                .map(account -> new Discrepancy(account.toString(), "verdict=DRIFTING"))
                .toList());
    }

    // ------------------------------------------------------------------ the two censuses

    /** Every merchant payable's drill-down on the snapshot: does its terms' sum equal its position? */
    public static Function<Connection, List<PayableTerms>> merchantPayables(MerchantPayable drillDown) {
        Objects.requireNonNull(drillDown, "drillDown");
        return snapshot -> {
            List<PayableTerms> payables = new ArrayList<>();
            for (UUID merchant : ids(snapshot, MERCHANTS, "reading the merchants with a payable")) {
                for (MerchantPayable.Payable payable : drillDown.payablesOf(snapshot, MerchantId.of(merchant))) {
                    payables.add(new PayableTerms(merchant, payable.position().currency().code(),
                            payable.terms().equals(payable.position())));
                }
            }
            return payables;
        };
    }

    /** Every posted account's projection verdict on the snapshot, by the verification's own per-account protocol. */
    public static Function<Connection, Projections> projections(ProjectionVerification verification) {
        Objects.requireNonNull(verification, "verification");
        return snapshot -> {
            List<UUID> drifting = new ArrayList<>();
            long inFlight = 0;
            for (UUID account : ids(snapshot, POSTED_ACCOUNTS, "reading the posted accounts")) {
                switch (verification.verdictOf(snapshot, LedgerAccountId.of(account))) {
                    case DRIFTING -> drifting.add(account);
                    case IN_FLIGHT -> inFlight++;
                    case CLEAN -> { }
                }
            }
            return new Projections(drifting, inFlight);
        };
    }

    private static List<UUID> ids(Connection snapshot, String sql, String operation) {
        try (PreparedStatement read = snapshot.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            List<UUID> ids = new ArrayList<>();
            while (row.next()) {
                ids.add(row.getObject(1, UUID.class));
            }
            return ids;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe(operation, failure));
        }
    }
}
