package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.SourceProofs;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplPurchaseId;
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
                .contains("fails=[LOAN_PRINCIPAL, UNEXPLAINED_ENTRIES]").as("P14-TSK-025: no lifecycle status").doesNotContain("status=");
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

    // ------------------------------------------------------------------ P14-TSK-025: only what is failing

    @Test
    @DisplayName("P14-TSK-025 (a): the same failures under moving traffic read byte-identical evidence through SOURCE_COMPLETENESS - a loan's and an obligation's status, a replay's"
            + " diverged facts of the same kind, the capital's and the positions' amounts, the open expectations and items, the cash chain's latest sequence, merchants and"
            + " purchases that hold - so a decided break is held, not chased")
    void theSameFailureUnderTrafficReadsTheSame() {
        Book book = new Book();
        Map<String, String> before = findings(book);
        assertThat(before).as("every failing proof of the four moving sources has its subject").hasSize(9);

        Book traffic = book.copy();
        traffic.loanStatus = LoanStatus.CLOSING;
        traffic.replayFacts = 7;
        traffic.capitalMinor = 98_765;
        traffic.obligationStatus = BnplObligationStatus.CLOSED;
        traffic.holdingMerchants = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        traffic.holdingPurchases = List.of(IDS.next(), IDS.next());
        traffic.openExpectations = 41;
        traffic.openItems = 17;
        traffic.suspenseOpenItems = 9;
        traffic.latestSequence = 58;
        traffic.positionMinor = 55_555;

        assertThat(findings(traffic)).isEqualTo(before);
    }

    @Test
    @DisplayName("P14-TSK-025 (a): a new or different failure reads changed, each on its own subject - another arm, another diverged kind, a second unexplained capital entry, one merchant"
            + " or purchase fixed and another broken at the same count, a merchant's leg failing another arm, the cash chain broken, another unattributed line")
    void aDifferentFailureReadsChanged() {
        Book book = new Book();
        Map<String, String> before = findings(book);
        Map<String, java.util.function.Consumer<Book>> changes = new java.util.LinkedHashMap<>();
        changes.put("LOAN_SUBLEDGER:" + book.loan, changed -> changed.loanNegative = true);
        changes.put("LOAN_REPLAY:" + book.loan, changed -> changed.replayChecks = List.of(LoanReplayProof.Check.ALLOCATION, LoanReplayProof.Check.ACCRUAL));
        changes.put("LENDING_CAPITAL:EUR", changed -> changed.capitalUnexplained = 3);
        changes.put("LENDING_CAPITAL:EUR ", changed -> changed.capitalHeadroom = false);
        changes.put("BNPL_SUBLEDGER:" + book.obligation, changed -> changed.obligationNegative = true);
        changes.put("BNPL_THREE_WAY:EUR", changed -> changed.failingMerchants = List.of(UUID.fromString("00000000-0000-0000-0000-00000000000b")));
        changes.put("BNPL_THREE_WAY:EUR ", changed -> changed.merchantEquationFails = true);
        changes.put("BNPL_THREE_WAY:EUR  ", changed -> changed.failingPurchases = List.of(IDS.next()));
        changes.put("POSITION:CASH_AT_BANK:EUR", changed -> changed.cashUnbroken = false);
        changes.put("POSITION:unattributed:CASH_AT_BANK", changed -> changed.unattributed = 6);
        changes.forEach((key, change) -> {
            String subject = key.strip();
            Book changed = book.copy();
            change.accept(changed);
            Map<String, String> after = findings(changed);
            assertThat(after.get(subject)).as("%s reads changed", key).isNotNull().isNotEqualTo(before.get(subject));
            after.keySet().stream().filter(other -> !other.equals(subject))
                    .forEach(other -> assertThat(after.get(other)).as("%s alone moved, not %s", subject, other).isEqualTo(before.get(other)));
        });
    }

    @Test
    @DisplayName("P14-TSK-025 (a): what still reads the same, stated - a failure differing only in an amount (evidence holds none, the owner's limit), a position's or suspense's"
            + " new open expectations and items once acknowledged (one fixed string a subject), and a replay divergence of a kind already named on another fact of the"
            + " same loan; LENDING_CAPITAL keeps its count, its proof's only name for a failing entry")
    void whatStillReadsTheSame() {
        Book book = new Book();
        Map<String, String> before = findings(book);
        Book amounts = book.copy();
        amounts.positionMinor = 1;
        amounts.capitalMinor = 2;
        amounts.openExpectations = 900;
        amounts.openItems = 400;
        amounts.suspenseOpenItems = 300;
        assertThat(findings(amounts)).isEqualTo(before);
        assertThat(before.get("LENDING_CAPITAL:EUR")).contains("unexplainedEntries=2");
        assertThat(before.get("BNPL_THREE_WAY:EUR")).contains("merchantLegsFailing=1, identitiesFailing=1, failingSetSha256=")
                .contains("merchantLegs=[" + book.failingMerchants.get(0) + ":[SUM]]")
                .contains("identities=[" + book.failingPurchases.get(0) + ":[PRINCIPAL]]");
        assertThat(before.get("POSITION:SETTLEMENT_CLEARING:EUR")).endsWith("position explained=false");
        assertThat(before.get("POSITION:SUSPENSE_UNMATCHED:EUR")).endsWith("suspense explained=false");
        assertThat(before.get("POSITION:CASH_AT_BANK:EUR")).endsWith("cash explained=false, unbroken=true");
        assertThat(before.get("LOAN_REPLAY:" + book.loan)).endsWith("diverged=[ALLOCATION]");
    }

    @Test
    @DisplayName("P14-TSK-025's fix round, I-1: BNPL_THREE_WAY's names run past the 2000-character cut and the newest id is last, so the counts and the failing set's digest"
            + " stand before the names: a 46th failing purchase, newer than 45, reads changed, and so does one swapped for another at the same count, both past the cut")
    void aFailureBeyondTheCutStillReadsChanged() {
        Book book = new Book();
        // Forty-six UUIDv7 ids in their sort order, the creation order the ids keep across milliseconds: the last is the newest.
        List<UUID> ids = java.util.stream.Stream.generate(IDS::next).limit(46).sorted(java.util.Comparator.comparing(UUID::toString)).toList();
        List<UUID> purchases = ids.subList(0, 45);
        UUID newest = ids.get(45);
        book.failingPurchases = List.copyOf(purchases);
        String before = findings(book).get("BNPL_THREE_WAY:EUR");
        assertThat(before).as("the names reach the cut").hasSize(com.finapp.accounting.BreakFinding.MAX_EVIDENCE)
                .doesNotContain(purchases.get(44).toString()).contains("identitiesFailing=45, failingSetSha256=");

        Book grown = book.copy();
        grown.failingPurchases = java.util.stream.Stream.concat(purchases.stream(), java.util.stream.Stream.of(newest)).toList();
        String afterGrowth = findings(grown).get("BNPL_THREE_WAY:EUR");
        assertThat(afterGrowth).as("a new failure past the cut").doesNotContain(newest.toString()).isNotEqualTo(before);

        Book swapped = book.copy();
        List<UUID> swappedPurchases = new java.util.ArrayList<>(purchases.subList(0, 44));
        swappedPurchases.add(newest);
        swapped.failingPurchases = List.copyOf(swappedPurchases);
        String afterSwap = findings(swapped).get("BNPL_THREE_WAY:EUR");
        assertThat(afterSwap).as("one failure for another at the same count, both past the cut").contains("identitiesFailing=45, ")
                .doesNotContain(newest.toString()).isNotEqualTo(before);

        Book traffic = book.copy();
        traffic.holdingPurchases = List.of(IDS.next(), IDS.next(), IDS.next());
        traffic.holdingMerchants = List.of(UUID.randomUUID());
        assertThat(findings(traffic).get("BNPL_THREE_WAY:EUR")).as("traffic around the same failing set moves nothing").isEqualTo(before);
    }

    @Test
    @DisplayName("P14-TSK-025's fix round, I-1: every other reworded proof is bounded by its arms' names - a loan and an obligation failing every figure the ledger has and"
            + " every rule, a replay diverging on every kind - and stays under the 2000-character cut, so only BNPL_THREE_WAY needs the digest")
    void everyOtherProofFitsUnderTheCut() {
        Money one = Money.ofMinorUnits(1, EUR);
        Money two = Money.ofMinorUnits(2, EUR);
        List<LoanSubledgerProof.Figure> loanFigures = java.util.Arrays.stream(AccountPurpose.values())
                .map(purpose -> new LoanSubledgerProof.Figure(purpose, one, two)).toList();
        List<BnplSubledgerProof.Figure> bnplFigures = java.util.Arrays.stream(AccountPurpose.values())
                .map(purpose -> new BnplSubledgerProof.Figure(purpose, one, two)).toList();
        LoanSubledgerProof.Verdict loanVerdict = new LoanSubledgerProof.Verdict(LoanId.of(loan), LoanKind.INSTALMENT, LoanStatus.ACTIVE, loanFigures,
                false, 9, false, false, false);
        List<LoanReplayProof.Finding> every = java.util.Arrays.stream(LoanReplayProof.Check.values())
                .map(check -> new LoanReplayProof.Finding(check, UUID.randomUUID())).toList();
        LendingProofs.Reading lending = new LendingProofs.Reading(List.of(new LendingProofs.Proven(loanVerdict,
                new LoanReplayProof.Replay(LoanId.of(loan), LoanReplayProof.Verdict.DIVERGED, every, every.size()))));
        BnplSubledgerProof.Verdict obligationVerdict = new BnplSubledgerProof.Verdict(BnplObligationId.of(obligation), BnplProduct.BNPL_PAY_IN_4,
                BnplObligationStatus.ACTIVE, bnplFigures, false, 9, false, false, false);
        BnplProofs.Reading bnpl = new BnplProofs.Reading(List.of(new BnplProofs.Proven(obligationVerdict, new BnplReplayProof.Replay(
                BnplObligationId.of(obligation), BnplReplayProof.Verdict.IDENTICAL, List.of(), 1))), List.of());
        com.finapp.accounting.SourceCompleteness completeness = new com.finapp.accounting.SourceCompleteness();
        List<String> evidence = new java.util.ArrayList<>();
        completeness.judge(SourceProofs.Source.LENDING, ProductSourceProofs.lendingReadings(lending)).findings().forEach(found -> evidence.add(found.evidence()));
        completeness.judge(SourceProofs.Source.BNPL, ProductSourceProofs.bnplReadings(bnpl)).findings().forEach(found -> evidence.add(found.evidence()));
        assertThat(evidence).hasSize(3).allSatisfy(text -> assertThat(text.length()).as(text.substring(0, 40))
                .isLessThan(com.finapp.accounting.BreakFinding.MAX_EVIDENCE));
    }

    /** One failing book - each moving proof failing once - and the traffic around it, as the proofs' own verdict types state it. */
    private static final class Book {
        UUID loan = IDS.next();
        UUID obligation = IDS.next();
        LoanStatus loanStatus = LoanStatus.ACTIVE;
        boolean loanNegative;
        List<LoanReplayProof.Check> replayChecks = List.of(LoanReplayProof.Check.ALLOCATION);
        int replayFacts = 1;
        int capitalUnexplained = 2;
        boolean capitalHeadroom = true;
        long capitalMinor = 4_567;
        BnplObligationStatus obligationStatus = BnplObligationStatus.ACTIVE;
        boolean obligationNegative;
        List<UUID> failingMerchants = List.of(UUID.fromString("00000000-0000-0000-0000-00000000000a"));
        boolean merchantEquationFails;
        List<UUID> holdingMerchants = List.of();
        List<UUID> failingPurchases = List.of(IDS.next());
        List<UUID> holdingPurchases = List.of();
        long openExpectations = 2;
        long openItems = 1;
        long suspenseOpenItems = 1;
        long latestSequence = 3;
        boolean cashUnbroken = true;
        long unattributed = 5;
        long positionMinor = 123;

        Book copy() {
            Book copy = new Book();
            copy.loan = loan;
            copy.obligation = obligation;
            copy.loanStatus = loanStatus;
            copy.loanNegative = loanNegative;
            copy.replayChecks = replayChecks;
            copy.replayFacts = replayFacts;
            copy.capitalUnexplained = capitalUnexplained;
            copy.capitalHeadroom = capitalHeadroom;
            copy.capitalMinor = capitalMinor;
            copy.obligationStatus = obligationStatus;
            copy.obligationNegative = obligationNegative;
            copy.failingMerchants = failingMerchants;
            copy.merchantEquationFails = merchantEquationFails;
            copy.holdingMerchants = holdingMerchants;
            copy.failingPurchases = failingPurchases;
            copy.holdingPurchases = holdingPurchases;
            copy.openExpectations = openExpectations;
            copy.openItems = openItems;
            copy.suspenseOpenItems = suspenseOpenItems;
            copy.latestSequence = latestSequence;
            copy.cashUnbroken = cashUnbroken;
            copy.unattributed = unattributed;
            copy.positionMinor = positionMinor;
            return copy;
        }
    }

    /** Each failing subject's evidence as SOURCE_COMPLETENESS raises it - the string a decided break is held to, exactly. */
    private static Map<String, String> findings(Book book) {
        Money amount = Money.ofMinorUnits(book.positionMinor, EUR);
        Money other = Money.ofMinorUnits(book.positionMinor + 1, EUR);
        LoanSubledgerProof.Verdict loanVerdict = new LoanSubledgerProof.Verdict(LoanId.of(book.loan), LoanKind.INSTALMENT, book.loanStatus,
                List.of(new LoanSubledgerProof.Figure(AccountPurpose.LOAN_PRINCIPAL, amount, other)), true, 0, !book.loanNegative, true, true);
        List<LoanReplayProof.Finding> facts = new java.util.ArrayList<>();
        for (LoanReplayProof.Check check : book.replayChecks) {
            for (int fact = 0; fact < book.replayFacts; fact++) {
                facts.add(new LoanReplayProof.Finding(check, UUID.randomUUID()));
            }
        }
        LendingProofs.Reading lending = new LendingProofs.Reading(List.of(new LendingProofs.Proven(loanVerdict,
                new LoanReplayProof.Replay(LoanId.of(book.loan), LoanReplayProof.Verdict.DIVERGED, facts, 40 + book.replayFacts))));

        Money capital = Money.ofMinorUnits(book.capitalMinor, EUR);
        LendingCapitalProof.Report capitalReport = new LendingCapitalProof.Report(List.of(new LendingCapitalProof.CurrencyVerdict(
                new LendingCapitalHeadroom.Headroom(EUR, capital, capital, capital, Money.zero(EUR)), book.capitalUnexplained == 0, book.capitalUnexplained,
                book.capitalHeadroom)));

        BnplSubledgerProof.Verdict obligationVerdict = new BnplSubledgerProof.Verdict(BnplObligationId.of(book.obligation), BnplProduct.BNPL_PAY_IN_4,
                book.obligationStatus, List.of(), false, 0, !book.obligationNegative, true, true);
        List<BnplThreeWayProof.MerchantLeg> legs = new java.util.ArrayList<>();
        book.holdingMerchants.forEach(merchant -> legs.add(new BnplThreeWayProof.MerchantLeg(merchant, true, true, 0, 0, true)));
        book.failingMerchants.forEach(merchant -> legs.add(new BnplThreeWayProof.MerchantLeg(merchant, true, false, 0, 0, !book.merchantEquationFails)));
        List<BnplThreeWayProof.Identity> identities = new java.util.ArrayList<>();
        book.holdingPurchases.forEach(purchase -> identities.add(new BnplThreeWayProof.Identity(BnplPurchaseId.of(purchase), true, true, true, true)));
        book.failingPurchases.forEach(purchase -> identities.add(new BnplThreeWayProof.Identity(BnplPurchaseId.of(purchase), false, true, true, true)));
        BnplProofs.Reading bnpl = new BnplProofs.Reading(
                List.of(new BnplProofs.Proven(obligationVerdict, new BnplReplayProof.Replay(BnplObligationId.of(book.obligation),
                        BnplReplayProof.Verdict.IDENTICAL, List.of(), 1))),
                List.of(new BnplThreeWayProof.CurrencyVerdict(EUR, legs, List.of(), identities, true, true, true)));

        PositionProof.Report position = new PositionProof.Report(
                List.of(new PositionProof.PositionVerdict(AccountPurpose.SETTLEMENT_CLEARING, EUR, amount, other, other, book.openExpectations, book.openItems,
                        false)),
                Map.of(AccountPurpose.CASH_AT_BANK, book.unattributed), Map.of(),
                List.of(new PositionProof.SuspenseVerdict(EUR, amount, other, other, other, book.suspenseOpenItems, false)),
                Optional.empty(), 0,
                List.of(new PositionProof.CashVerdict(EUR, amount, other, book.latestSequence, book.cashUnbroken, false)));

        Map<String, String> found = new java.util.TreeMap<>();
        com.finapp.accounting.SourceCompleteness completeness = new com.finapp.accounting.SourceCompleteness();
        for (Map.Entry<SourceProofs.Source, List<SourceProofs.Reading>> reading : Map.of(
                SourceProofs.Source.LENDING, ProductSourceProofs.lendingReadings(lending),
                SourceProofs.Source.LENDING_CAPITAL, List.of(ProductSourceProofs.capitalReading(capitalReport)),
                SourceProofs.Source.BNPL, ProductSourceProofs.bnplReadings(bnpl),
                SourceProofs.Source.POSITION, List.of(ProductSourceProofs.positionReading(position))).entrySet()) {
            com.finapp.accounting.SourceCompleteness.Judged judged = completeness.judge(reading.getKey(), reading.getValue());
            assertThat(judged.unavailable()).isEmpty();
            judged.findings().forEach(finding -> found.put(finding.subject(), finding.evidence()));
        }
        return found;
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
                new LendingCapitalHeadroom.Headroom(EUR, other, amount, amount, Money.zero(EUR)), holding, holding ? 0 : 2, true)));

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
