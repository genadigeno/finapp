package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.session.RequiresPermission;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplExplanations;
import com.finapp.bnpl.BnplObligation;
import com.finapp.bnpl.BnplObligationId;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplOfferId;
import com.finapp.bnpl.BnplPlan;
import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplPurchase;
import com.finapp.bnpl.BnplPurchaseId;
import com.finapp.bnpl.BnplPurchaseStatus;
import com.finapp.bnpl.BnplRecord;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.MerchantFeeSplit;
import com.finapp.bnpl.MerchantFinancing;
import com.finapp.identity.PermissionName;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The investigator's doors' boundary, hermetic (`P12-TSK-025`): every door behind {@code BNPL_INVESTIGATE} and nothing
 * else; the explanation's view built from rows alone - every journal entry on the obligation's accounts with the row that
 * names it, an unnamed one {@code UNEXPLAINED} beside a {@code FAILS} verdict; the views closed against a reason, a party,
 * a wallet or a credential. The doors' behaviour over the database (self-dealing {@code 403} recorded, the uniform
 * {@code 404}, the audit records) is {@code BnplProofApiTest}'s; the domain's is {@code BnplExplanationsTest}'s.
 */
@DisplayName("the BNPL investigator's doors' boundary (P12-TSK-025)")
class BnplInvestigationDeskTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("every door is behind BNPL_INVESTIGATE - the read, the explanation and the replay - and no other")
    void everyDoorIsBehindBnplInvestigate() {
        List<Method> doors = Arrays.stream(BnplInvestigationController.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())).toList();
        assertThat(doors).extracting(Method::getName).containsExactlyInAnyOrder("investigateBnplAgreement",
                "explainBnplAgreement", "replayBnplAgreement");
        assertThat(doors).allSatisfy(door -> assertThat(door.getAnnotation(RequiresPermission.class).value())
                .isEqualTo(PermissionName.BNPL_INVESTIGATE));
    }

    @Test
    @DisplayName("the explanation names every entry's row - an entry no row names is UNEXPLAINED, its verdict FAILS,"
            + " its six balances ledger beside rows")
    void theExplanationNamesEveryEntryFromRows() {
        Fixture fixture = new Fixture();
        JournalEntryId stray = JournalEntryId.of(IDS.next());
        BnplRecord record = fixture.record(List.of(
                new PurposeBalances.DatedLine(fixture.financing.entry(), fixture.principal, fixture.date, Direction.DEBIT,
                        eur(600_00)),
                new PurposeBalances.DatedLine(stray, fixture.principal, fixture.date, Direction.CREDIT, eur(1_00))));
        BnplSubledgerProof.Verdict verdict = new BnplSubledgerProof().prove(record);
        BnplInvestigationDesk.BnplExplanationView view = BnplInvestigationDesk.view(
                new BnplExplanations.Explanation(record, verdict));
        assertThat(view.journalEntries()).extracting(BnplInvestigationDesk.BnplExplainedEntryView::explainedBy)
                .containsExactly("FINANCING", "UNEXPLAINED");
        assertThat(view.subledgerVerdict()).isEqualTo("FAILS");
        assertThat(view.subledgerFailures()).contains("UNEXPLAINED_ENTRIES", "BNPL_PRINCIPAL");
        assertThat(view.balances()).hasSize(6).extracting(BnplInvestigationDesk.BnplExplainedBalanceView::account)
                .startsWith("BNPL_PRINCIPAL");
        assertThat(view.financing().gross()).isEqualTo("600.00");
        assertThat(view.agreementId()).isEqualTo(record.obligation().agreement().value().toString());
    }

    @Test
    @DisplayName("the views are closed: no member names a reason, a party, a wallet, a key or a proposer")
    void theViewsAreClosed() {
        for (Class<?> view : BnplInvestigationDesk.class.getDeclaredClasses()) {
            if (!view.isRecord()) {
                continue;
            }
            assertThat(view.getSimpleName()).as("a Bnpl-prefixed OpenAPI schema").startsWith("Bnpl");
            assertThat(Arrays.stream(view.getRecordComponents()).map(RecordComponent::getName))
                    .as(view.getSimpleName())
                    // closureReason is the obligation's closure CODE (REPAID, REFUNDED, ...), never a person's text.
                    .noneMatch(name -> (name.toLowerCase(java.util.Locale.ROOT).contains("reason")
                            && !name.equals("closureReason")) || name.contains("party") || name.contains("wallet")
                            || name.contains("requestKey") || name.contains("proposedBy") || name.contains("decidedBy"));
        }
    }

    // ------------------------------------------------------------------

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }

    /** A financed pay-in-4 obligation's minimal record. */
    private static final class Fixture {

        final LocalDate date = LocalDate.of(2026, 10, 17);
        final BnplObligationId obligation = BnplObligationId.of(IDS.next());
        final BnplPurchaseId purchase = BnplPurchaseId.of(IDS.next());
        final BnplAgreementId agreement = BnplAgreementId.of(IDS.next());
        final UUID merchant = IDS.next();
        final LedgerAccountId principal = LedgerAccountId.of(IDS.next());
        final MerchantFinancing financing = new MerchantFinancing(purchase, merchant, new MerchantFeeSplit(eur(600_00),
                eur(24_00), eur(576_00), IDS.next()), JournalEntryId.of(IDS.next()));

        BnplRecord record(List<PurposeBalances.DatedLine> lines) {
            UUID party = IDS.next();
            CorrelationId correlation = CorrelationId.of("p12-tsk-025-desk");
            Instant at = Instant.parse("2026-10-17T10:10:00Z");
            BnplObligation row = new BnplObligation(obligation, purchase, agreement, party, IDS.next(),
                    BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, EUR, BnplObligationStatus.ACTIVE, date, at,
                    Optional.empty(), correlation);
            BnplPurchase bought = new BnplPurchase(purchase, BnplOfferId.of(IDS.next()), IDS.next(), party, merchant,
                    BnplProduct.BNPL_PAY_IN_4, BnplPlan.PAY_IN_4, EUR, BnplPurchaseStatus.FINANCED, date, at,
                    Optional.of(at), Optional.of(IDS.next()), correlation);
            return new BnplRecord(row, Optional.empty(), bought, financing, at, Optional.empty(), Optional.empty(),
                    List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of(), List.of(), List.of(), Optional.empty(), List.of(), List.of(), List.of(), Optional.empty(),
                    List.of(new BnplRecord.LedgerAccountRow(principal, AccountPurpose.BNPL_PRINCIPAL, EUR)), lines,
                    List.of(), Optional.empty(), Optional.empty());
        }
    }
}
