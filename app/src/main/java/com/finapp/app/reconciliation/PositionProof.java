package com.finapp.app.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationReadings;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSourceDescriptor;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The position proof and the completeness verifier, in one sweep (`P8-TSK-007`, ADR-0067 §9,
 * {@code INV-REC-06}) — the {@code TrialBalance} shape: lock-free, the scrape is the
 * schedule, <strong>report and never repair</strong>. A missing expectation is a defect whose
 * cause has to be found; a verifier that opened what it found missing would hide a broken
 * opener.
 *
 * <h2>The two verdicts</h2>
 *
 * <ul>
 *   <li><strong>The proof</strong>: per clearing position and currency, DR−CR of the ledger
 *       account equals the signed sum of open expectation remainders — {@code INBOUND}
 *       positive, {@code OUTBOUND} negative, the ledger's own sign (ADR-0067 §3). Folded
 *       through {@code Money}, never a SQL {@code SUM} (`P3-TSK-008`).
 *   <li><strong>Completeness</strong>: every {@code (entry, account)} line on a reconciled
 *       position is one the register knows — an expectation names it; the suspense item
 *       (`P8-TSK-010`/`-020`) and the Phase 8 records join the known list with their tasks.
 *       Until `-020` adopts Phase 7's parkings, {@code SUSPENSE_UNMATCHED} truthfully reads
 *       above zero (the transition's A7).
 * </ul>
 *
 * <p>The caller runs the sweep in <strong>one {@code REPEATABLE READ} transaction on one
 * connection</strong>, so the ledger's lines and reconciliation's rows are one snapshot; a
 * posting committed mid-sweep is wholly in or wholly out on both sides.
 */
@RequiredArgsConstructor
public final class PositionProof {

    /** The positions the proof's identity covers today — the external-item and remittance
     * terms join with `P8-TSK-009`. */
    public static final Set<AccountPurpose> PROVEN =
            Set.of(
                    AccountPurpose.SETTLEMENT_CLEARING,
                    AccountPurpose.PAYOUT_CLEARING,
                    AccountPurpose.INSTANT_CLEARING);

    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final BalanceDerivation<Connection> balances;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final ExpectationReadings<Connection> readings;
    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;

    /** One position-and-currency verdict: the identity's two sides, and whether they agree. */
    public record PositionVerdict(
            AccountPurpose purpose,
            CurrencyCode currency,
            Money ledgerBalance,
            Money openRemainders,
            long openCount,
            boolean explained) {}

    /** One sweep's whole answer — the gauges' and the report's one source. */
    public record Report(
            List<PositionVerdict> verdicts,
            Map<AccountPurpose, Long> unattributedByPurpose,
            Map<String, Long> openBySourceCode) {

        public Report {
            verdicts = List.copyOf(verdicts);
            unattributedByPurpose = Map.copyOf(unattributedByPurpose);
            openBySourceCode = Map.copyOf(openBySourceCode);
        }

        /** The proof gauge's value for {@code purpose}: how many currencies fail. */
        public long currenciesFailing(AccountPurpose purpose) {
            return verdicts.stream()
                    .filter(verdict -> verdict.purpose() == purpose && !verdict.explained())
                    .count();
        }
    }

    /** The sweep — on the caller's connection, inside the caller's snapshot. */
    public Report sweep(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");

        // The reconciled positions' seeded accounts, per purpose and currency.
        Map<AccountPurpose, Map<CurrencyCode, LedgerAccount>> positions =
                new EnumMap<>(AccountPurpose.class);
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            Map<CurrencyCode, LedgerAccount> byCurrency = new LinkedHashMap<>();
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                accounts.findOperational(unitOfWork, purpose, currency)
                        .ifPresent(account -> byCurrency.put(currency, account));
            }
            positions.put(purpose, byCurrency);
        }

        // THE PROOF: the signed Money fold of open remainders, per position and currency.
        Map<AccountPurpose, Map<CurrencyCode, Money>> folded = new EnumMap<>(AccountPurpose.class);
        Map<AccountPurpose, Map<CurrencyCode, Long>> openCounts =
                new EnumMap<>(AccountPurpose.class);
        for (ExpectationReadings.OpenRemainder remainder : readings.openRemainders(unitOfWork)) {
            CurrencyCode currency = remainder.remainder().currency();
            Map<CurrencyCode, Money> sums =
                    folded.computeIfAbsent(remainder.position(), p -> new HashMap<>());
            Money signed = remainder.remainder();
            Money current =
                    sums.getOrDefault(
                            currency, Money.ofPersisted(0, currency, signed.scale()));
            sums.put(
                    currency,
                    remainder.direction() == ExpectationDirection.INBOUND
                            ? current.plus(signed)
                            : current.minus(signed));
            openCounts
                    .computeIfAbsent(remainder.position(), p -> new HashMap<>())
                    .merge(currency, 1L, Long::sum);
        }

        List<PositionVerdict> verdicts = new ArrayList<>();
        for (AccountPurpose purpose : PROVEN) {
            for (Map.Entry<CurrencyCode, LedgerAccount> position :
                    positions.get(purpose).entrySet()) {
                Money balance =
                        balances.derive(unitOfWork, position.getValue().id(), AsOf.latest())
                                .settled();
                Money remainders =
                        Optional.ofNullable(folded.get(purpose))
                                .map(sums -> sums.get(position.getKey()))
                                .orElse(
                                        Money.ofPersisted(
                                                0, position.getKey(), balance.scale()));
                long open =
                        Optional.ofNullable(openCounts.get(purpose))
                                .map(counts -> counts.getOrDefault(position.getKey(), 0L))
                                .orElse(0L);
                verdicts.add(
                        new PositionVerdict(
                                purpose,
                                position.getKey(),
                                balance,
                                remainders,
                                open,
                                balance.equals(remainders)));
            }
        }

        // COMPLETENESS: every line on a reconciled position, against the known pairs.
        Map<LedgerAccountId, AccountPurpose> purposeOf = new HashMap<>();
        List<LedgerAccountId> reconciled = new ArrayList<>();
        for (Map.Entry<AccountPurpose, Map<CurrencyCode, LedgerAccount>> byPurpose :
                positions.entrySet()) {
            for (LedgerAccount account : byPurpose.getValue().values()) {
                purposeOf.put(account.id(), byPurpose.getKey());
                reconciled.add(account.id());
            }
        }
        Set<ExpectationReadings.KnownLine> known =
                new HashSet<>(readings.knownLines(unitOfWork));
        Map<AccountPurpose, Long> unattributed = new EnumMap<>(AccountPurpose.class);
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            unattributed.put(purpose, 0L);
        }
        for (JournalEntryStore.LineKey line : entries.lineKeysOn(unitOfWork, reconciled)) {
            boolean explained =
                    known.contains(
                            new ExpectationReadings.KnownLine(
                                    line.entry().value(), line.account().value()));
            if (!explained) {
                unattributed.merge(purposeOf.get(line.account()), 1L, Long::sum);
            }
        }

        // The open population per declared source code (the expectation.open gauge).
        Map<UUID, Long> openBySourceId = readings.openCountBySource(unitOfWork);
        Map<String, Long> openBySourceCode = new LinkedHashMap<>();
        for (SettlementSourceDescriptor declared : sources.declared()) {
            long count =
                    sourceRows
                            .sourceByCode(unitOfWork, declared.code())
                            .map(row -> openBySourceId.getOrDefault(row.id(), 0L))
                            .orElse(0L);
            openBySourceCode.put(declared.code(), count);
        }
        return new Report(verdicts, unattributed, openBySourceCode);
    }
}
