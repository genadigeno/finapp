package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.SourceProofs;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.fx.FxBooksProof;
import com.finapp.lending.LendingCapitalHeadroom;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * accounting's {@code SourceProofs} over the nine product proofs (`P14-TSK-010`; ADR-0117 decision 2;
 * {@code INV-ACC-12}): each proof's own failing verdict - built with the proof's own types - read into exactly one
 * discrepancy on the proof's subject, its failing arms and counts as evidence and <strong>never an amount</strong>; a
 * holding verdict into none; a projection account in flight into an incomplete reading, never a clean one; every source
 * answering exactly its proofs. Hermetic: the readers are the seams; {@code AccountingBreakDatabaseTest} reads the
 * database (owed, C5).
 */
@DisplayName("the product proofs as source completeness reads them - never re-implemented (P14-TSK-010)")
class ProductSourceProofsTest {

    private static final Connection SNAPSHOT = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    private static final com.finapp.sharedkernel.id.IdGenerator IDS = new com.finapp.sharedkernel.id.IdGenerator(
            java.time.Clock.systemUTC(), new java.security.SecureRandom());

    private final UUID loan = IDS.next();
    private final UUID obligation = IDS.next();
    private final UUID merchant = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    @Test
    @DisplayName("every failing verdict is one discrepancy on its proof's subject, with no amount in the evidence")
    void everyFailingVerdictIsOneDiscrepancy() {
        ProductSourceProofs proofs = proofs(false, 0);

        Map<SourceProofs.Proof, List<SourceProofs.Discrepancy>> found = new java.util.EnumMap<>(SourceProofs.Proof.class);
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            List<SourceProofs.Reading> readings = proofs.read(SNAPSHOT, source);
            assertThat(readings).extracting(SourceProofs.Reading::proof).as("%s answers exactly its proofs", source)
                    .containsExactlyElementsOf(source.proofs());
            readings.forEach(reading -> {
                assertThat(reading.complete()).isTrue();
                found.put(reading.proof(), reading.discrepancies());
            });
        }

        assertThat(subjects(found, SourceProofs.Proof.LOAN_SUBLEDGER)).containsExactly(loan.toString());
        assertThat(found.get(SourceProofs.Proof.LOAN_SUBLEDGER).get(0).evidence())
                .contains("fails=[LOAN_PRINCIPAL, UNEXPLAINED_ENTRIES]").contains("status=ACTIVE");
        assertThat(subjects(found, SourceProofs.Proof.LOAN_REPLAY)).containsExactly(loan.toString());
        assertThat(found.get(SourceProofs.Proof.LOAN_REPLAY).get(0).evidence()).contains("diverged=[ALLOCATION]");
        assertThat(subjects(found, SourceProofs.Proof.LENDING_CAPITAL)).containsExactly("EUR");
        assertThat(found.get(SourceProofs.Proof.LENDING_CAPITAL).get(0).evidence()).contains("complete=false")
                .contains("unexplainedEntries=2");
        assertThat(subjects(found, SourceProofs.Proof.BNPL_SUBLEDGER)).containsExactly(obligation.toString());
        assertThat(subjects(found, SourceProofs.Proof.BNPL_THREE_WAY)).containsExactly("EUR");
        assertThat(found.get(SourceProofs.Proof.BNPL_THREE_WAY).get(0).evidence()).contains("TRIAL_BALANCE");
        assertThat(subjects(found, SourceProofs.Proof.FX_BOOKS)).containsExactly("FX_POSITION:EUR");
        assertThat(subjects(found, SourceProofs.Proof.POSITION)).containsExactly("SETTLEMENT_CLEARING:EUR",
                "SUSPENSE_UNMATCHED:EUR", "CASH_AT_BANK:EUR", "unattributed:CASH_AT_BANK");
        assertThat(subjects(found, SourceProofs.Proof.MERCHANT_PAYABLE)).containsExactly(merchant + ":EUR");
        assertThat(subjects(found, SourceProofs.Proof.PROJECTION)).containsExactly(account.toString());
        found.values().stream().flatMap(List::stream).forEach(discrepancy -> assertThat(discrepancy.evidence())
                .as("never an amount").doesNotContain("123").doesNotContain("1.23").doesNotContain("4567"));
    }

    @Test
    @DisplayName("holding verdicts are no discrepancy at all")
    void holdingVerdictsAreNothing() {
        ProductSourceProofs proofs = proofs(true, 0);
        for (SourceProofs.Source source : SourceProofs.Source.values()) {
            assertThat(proofs.read(SNAPSHOT, source)).allSatisfy(reading -> {
                assertThat(reading.complete()).isTrue();
                assertThat(reading.discrepancies()).as("%s holds", reading.proof()).isEmpty();
            });
        }
    }

    @Test
    @DisplayName("a projection account in flight leaves the reading incomplete - never clean")
    void anAccountInFlightIsIncomplete() {
        assertThat(proofs(true, 1).read(SNAPSHOT, SourceProofs.Source.PROJECTION))
                .singleElement().satisfies(reading -> assertThat(reading.complete()).isFalse());
    }

    @Test
    @DisplayName("the two censuses read the ledger's chart only, SELECT only")
    void theCensusesAreReads() {
        assertThat(ProductSourceProofs.MERCHANTS).startsWith("SELECT").contains("purpose = 'MERCHANT_PAYABLE'")
                .doesNotContain("UPDATE").doesNotContain("INSERT");
        assertThat(ProductSourceProofs.POSTED_ACCOUNTS).startsWith("SELECT")
                .contains("ledger.journal_line").contains("ledger.account_balance");
    }

    // ------------------------------------------------------------------ the proofs' own verdicts

    private ProductSourceProofs proofs(boolean holding, long inFlight) {
        Money amount = Money.ofMinorUnits(holding ? 4567 : 123, EUR);
        Money other = Money.ofMinorUnits(4567, EUR);
        LoanSubledgerProof.Verdict loanVerdict = new LoanSubledgerProof.Verdict(LoanId.of(loan), LoanKind.INSTALMENT,
                LoanStatus.ACTIVE, List.of(new LoanSubledgerProof.Figure(AccountPurpose.LOAN_PRINCIPAL, amount, other)),
                true, holding ? 0 : 3, true, true, true);
        LoanReplayProof.Replay replay = holding
                ? new LoanReplayProof.Replay(LoanId.of(loan), LoanReplayProof.Verdict.IDENTICAL, List.of(), 4)
                : new LoanReplayProof.Replay(LoanId.of(loan), LoanReplayProof.Verdict.DIVERGED,
                        List.of(new LoanReplayProof.Finding(LoanReplayProof.Check.ALLOCATION, UUID.randomUUID())), 4);
        LendingProofs.Reading lending = new LendingProofs.Reading(List.of(new LendingProofs.Proven(loanVerdict, replay)));

        LendingCapitalProof.Report capital = new LendingCapitalProof.Report(List.of(new LendingCapitalProof.CurrencyVerdict(
                new LendingCapitalHeadroom.Headroom(EUR, other, amount, amount), holding, holding ? 0 : 2, true)));

        BnplSubledgerProof.Verdict bnplVerdict = new BnplSubledgerProof.Verdict(BnplObligationId.of(obligation),
                BnplProduct.BNPL_PAY_IN_4, BnplObligationStatus.ACTIVE, List.of(), holding, 0, true, true, true);
        BnplProofs.Reading bnpl = new BnplProofs.Reading(
                List.of(new BnplProofs.Proven(bnplVerdict, new BnplReplayProof.Replay(BnplObligationId.of(obligation),
                        BnplReplayProof.Verdict.IDENTICAL, List.of(), 1))),
                List.of(new BnplThreeWayProof.CurrencyVerdict(EUR, List.of(), List.of(), List.of(), holding, true, true)));

        FxBooksProof.Report fx = new FxBooksProof.Report(List.of(
                new FxBooksProof.Line(AccountPurpose.FX_POSITION, EUR, 123, holding ? 123 : 4567),
                new FxBooksProof.Line(AccountPurpose.FX_SPREAD_REVENUE, EUR, 9, 9)));

        PositionProof.Report position = new PositionProof.Report(
                List.of(new PositionProof.PositionVerdict(AccountPurpose.SETTLEMENT_CLEARING, EUR, amount, other, other,
                        2, 1, holding)),
                holding ? Map.of() : Map.of(AccountPurpose.CASH_AT_BANK, 5L, AccountPurpose.SUSPENSE_UNMATCHED, 0L),
                Map.of(),
                List.of(new PositionProof.SuspenseVerdict(EUR, amount, other, other, other, 1, holding)),
                Optional.empty(), 0,
                List.of(new PositionProof.CashVerdict(EUR, amount, other, 3, holding, holding)));

        List<ProductSourceProofs.PayableTerms> payables =
                List.of(new ProductSourceProofs.PayableTerms(merchant, "EUR", holding));
        ProductSourceProofs.Projections projections =
                new ProductSourceProofs.Projections(holding ? List.of() : List.of(account), inFlight);

        return new ProductSourceProofs(snapshot -> lending, snapshot -> capital, snapshot -> bnpl, snapshot -> fx,
                snapshot -> position, snapshot -> payables, snapshot -> projections);
    }

    private static List<String> subjects(Map<SourceProofs.Proof, List<SourceProofs.Discrepancy>> found,
            SourceProofs.Proof proof) {
        return found.get(proof).stream().map(SourceProofs.Discrepancy::subject).toList();
    }
}
