package com.finapp.app.bnpl;

import com.finapp.app.credit.ExposureCensus;
import com.finapp.bnpl.BnplProofReads;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.StatementDerivation;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.merchant.MerchantId;
import com.finapp.merchant.MerchantPayable;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplProofReads} over merchant, the ledger, lending and credit (`P12-TSK-025`; {@code PHASE_12_PLAN.md}
 * section 12.12) - the three-way proof's reads outside bnpl's rows, every one on the caller's ONE read-only
 * {@code REPEATABLE READ} snapshot:
 *
 * <ul>
 *   <li><strong>the payable's BNPL-keyed lines</strong> through the ledger's statement derivation over the merchant's own
 *       accounts ({@code owner_ref} the merchant), each line labelled with the first posting-key namespace its entry's key
 *       begins with - {@code bnpl.financing:}, {@code bnpl.refund:} (merchant's {@link MerchantPayable#NAMESPACES}, `-016`)
 *       and, last, {@code bnpl.} itself, so a raw posting under any other {@code bnpl.} key is seen too;
 *   <li><strong>{@code INV-MER-02}'s equation</strong> through merchant's own drill-down: its terms sum to its position, and
 *       its four BNPL terms equal the net of the financing's and refunds' lines;
 *   <li>the trial balance ({@link TrialBalance}), lending's capital proof with its BNPL term ({@link LendingCapitalProof},
 *       `-005`) and credit's exposure census ({@link ExposureCensus}, `-012`).
 * </ul>
 *
 * <p>Read-only; holds no state. The census and the trial balance sweep the whole book once per call - the reading is taken
 * behind the gauges' refresh floor.
 */
@RequiredArgsConstructor
final class LedgerBnplProofReads implements BnplProofReads {

    /** Every posting-key namespace a BNPL line on a payable is read under, most significant first. */
    static final List<String> NAMESPACES = List.of(MerchantPayable.BNPL_FINANCING_NAMESPACE,
            MerchantPayable.BNPL_REFUND_NAMESPACE, "bnpl.");

    private static final LocalDate FIRST = LocalDate.of(1970, 1, 1);
    private static final LocalDate LAST = LocalDate.of(9999, 12, 31);

    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final MerchantPayable merchantPayable;
    @NonNull private final StatementDerivation<Connection> statements;
    @NonNull private final TrialBalance trialBalance;
    @NonNull private final LendingCapitalProof capitalProof;
    @NonNull private final ExposureCensus census;

    @Override
    public Optional<PayableLeg> payableLeg(Connection snapshot, UUID merchant, CurrencyCode currency) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(merchant, "merchant");
        Objects.requireNonNull(currency, "currency");
        MerchantId merchantId;
        try {
            merchantId = MerchantId.of(merchant);
        } catch (IllegalArgumentException notAMerchant) {
            return Optional.empty();
        }
        Optional<MerchantPayable.Payable> payable = merchantPayable.payablesOf(snapshot, merchantId).stream()
                .filter(found -> found.position().currency().equals(currency)).findFirst();
        Optional<LedgerAccountId> account = ledgerAccounts.findOwned(snapshot, merchant, AccountPurpose.MERCHANT_PAYABLE,
                currency).map(LedgerAccount::id);
        if (payable.isEmpty() || account.isEmpty()) {
            return Optional.empty();
        }
        Optional<StatementDerivation.AccountStatement> statement = statements.statementsFor(snapshot, merchant, FIRST,
                LAST, NAMESPACES).stream().filter(found -> found.account().equals(account.get())).findFirst();
        if (statement.isEmpty()) {
            return Optional.empty();
        }
        List<KeyedLine> lines = new ArrayList<>();
        Money keyed = Money.ofMinorUnits(0, currency);
        for (StatementDerivation.StatementLine line : statement.get().lines()) {
            if (line.namespace().isEmpty()) {
                continue;
            }
            lines.add(new KeyedLine(line.entry(), line.direction(), line.amount(), line.namespace().get()));
            if (!line.namespace().get().equals("bnpl.")) {
                keyed = line.direction() == Direction.CREDIT ? keyed.plus(line.amount()) : keyed.minus(line.amount());
            }
        }
        MerchantPayable.Payable found = payable.get();
        Money bnplTerms = found.bnplFinanced().minus(found.bnplFees()).minus(found.bnplRefunded())
                .plus(found.bnplFeesReturned());
        boolean equation = found.terms().equals(found.position()) && bnplTerms.equals(keyed);
        return Optional.of(new PayableLeg(account.get(), lines, equation));
    }

    @Override
    public boolean trialBalanced(Connection snapshot, CurrencyCode currency) {
        return !trialBalance.sweep(snapshot).outOfBalance().contains(currency);
    }

    @Override
    public boolean capitalHolds(Connection snapshot, CurrencyCode currency) {
        return capitalProof.prove(snapshot, currency).holds();
    }

    @Override
    public boolean exposureHolds(Connection snapshot) {
        return census.take(snapshot).failing() == 0;
    }
}
