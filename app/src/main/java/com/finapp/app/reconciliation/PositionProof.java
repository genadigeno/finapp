package com.finapp.app.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.NormalBalance;
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
 * <h2>The four verdicts</h2>
 *
 * <ul>
 *   <li><strong>The proof</strong>: per clearing position ({@link #PROVEN}) and currency,
 *       DR−CR of the ledger account equals the signed sum of open expectation remainders minus
 *       the signed sum of open item remainders (the items term, `P8-TSK-009`) —
 *       {@code INBOUND} positive, {@code OUTBOUND} negative, the ledger's own sign (ADR-0067
 *       §3). DR−CR whatever the account's normal balance: {@code PAYOUT_CLEARING} is
 *       CREDIT-normal, so its derived balance is negated before it is compared
 *       ({@code readFrom}). Folded through {@code Money}, never a SQL {@code SUM} (`P3-TSK-008`).
 *   <li><strong>Completeness</strong>: every {@code (entry, account)} line on every
 *       {@code AccountPurpose.reconciledPositions()} account — the three clearings,
 *       {@code CASH_AT_BANK}, {@code SUSPENSE_UNMATCHED}, {@code PROCESSING_COSTS},
 *       {@code RECONCILIATION_LOSSES} and {@code RECONCILIATION_GAINS} — is one the register
 *       knows: an expectation names it, or its entry is in a known-entry class — an accepted
 *       or repudiated batch's recognition, a park's entry, an entry a suspense item carries, or
 *       an approved resolution's journal entry.
 *   <li><strong>The suspense proof</strong> (`P8-TSK-010`, ADR-0070 §7): per currency, CR−DR of
 *       {@code SUSPENSE_UNMATCHED} equals the CREDIT remainders less the DEBIT remainders plus
 *       the named term for Phase 7 parkings no item adopts — zero at rest since `P8-TSK-020`'s
 *       backfill adopted them.
 *   <li><strong>The cash proof</strong> (`P8-TSK-016`, {@code INV-SET-06}): per currency, DR−CR
 *       of {@code CASH_AT_BANK} equals the summed head closing of every bank account's chain of
 *       accepted statements in that currency, and holds only while every such chain is
 *       unbroken — sequence 1 opening at zero, every later opening its predecessor's closing,
 *       no sequence missing. A gap or a mis-stitched opening fails the verdict loudly: the
 *       platform does not know its cash, and nothing is ever posted to make the chain fit.
 * </ul>
 *
 * <p><em>(Corrected 2026-10-01, `P8-DOC-001`: this read "the two verdicts", listed three, named
 * no suspense proof, stated the proof without its items term, gave the cash proof one chain's
 * head, and spoke of delivered work in the future tense.)</em>
 *
 * <p>The caller runs the sweep in <strong>one {@code REPEATABLE READ} transaction on one
 * connection</strong>, so the ledger's lines and reconciliation's rows are one snapshot; a
 * posting committed mid-sweep is wholly in or wholly out on both sides.
 */
@RequiredArgsConstructor
public final class PositionProof {

    /**
     * The purposes the proof's identity covers - every position a declared source discharges,
     * DERIVED from the composed register (`P9-TSK-010`, ADR-0078 section 8), so a counterparty
     * admitted with its source is proven by construction. Today exactly the three clearings,
     * with the items term since `P8-TSK-009` - the hard-coded list this replaced, verdict for
     * verdict. {@code CASH_AT_BANK} and {@code SUSPENSE_UNMATCHED} have verdicts of their own,
     * and completeness walks every account of every reconciled purpose.
     */
    public static Set<AccountPurpose> provenPurposes(SettlementSources sources) {
        Objects.requireNonNull(sources, "sources must not be null");
        Set<AccountPurpose> proven = new java.util.LinkedHashSet<>();
        sources.settledPositions().forEach(position -> proven.add(position.purpose()));
        return java.util.Collections.unmodifiableSet(proven);
    }

    @NonNull private final LedgerAccountStore<Connection> accounts;
    @NonNull private final BalanceDerivation<Connection> balances;
    @NonNull private final JournalEntryStore<Connection> entries;
    @NonNull private final ExpectationReadings<Connection> readings;
    @NonNull private final SettlementSources sources;
    @NonNull private final SettlementFileStore<Connection> sourceRows;

    /** The recognition entries' reader (`P8-TSK-009`) — appended last (the Lombok rule). */
    @NonNull private final com.finapp.settlement.SettlementBatchStore<Connection> batches;

    /** The suspense terms (`P8-TSK-010`, ADR-0070 §7) — appended after `-009`'s. */
    @NonNull private final com.finapp.reconciliation.SuspenseReadings suspense;

    /**
     * Phase 7's parkings, for the unadopted term — zero at rest since `P8-TSK-020`'s backfill
     * adopted them, kept so a parking no item adopts is named, never hidden.
     */
    @NonNull
    private final com.finapp.payments.UnmatchedConfirmationStore<Connection> parkings;

    /**
     * The counterparty registry (`P9-TSK-010`), naming each counterparty account's owner -
     * appended last (the Lombok rule).
     */
    @NonNull private final com.finapp.ledger.CounterpartyStore<Connection> counterparties;

    /**
     * One position-and-currency verdict: the identity's terms, and whether they agree.
     * Since `P8-TSK-009` the identity carries the items term ({@code INV-REC-06} extended
     * at acceptance): balance = open remainders − open item remainders, with every accepted
     * line's undisposed claim subtracted — fee items excluded, their effect being the
     * recognition entry itself. {@code ledgerBalance} is DR−CR on every position, never the
     * normal-signed settled balance.
     */
    public record PositionVerdict(
            AccountPurpose purpose,
            CurrencyCode currency,
            Money ledgerBalance,
            Money openRemainders,
            Money openItems,
            long openCount,
            long openItemCount,
            boolean explained,
            Optional<String> counterparty) {

        /** A shared position's verdict - every verdict before `P9-TSK-010`. */
        public PositionVerdict(
                AccountPurpose purpose,
                CurrencyCode currency,
                Money ledgerBalance,
                Money openRemainders,
                Money openItems,
                long openCount,
                long openItemCount,
                boolean explained) {
            this(purpose, currency, ledgerBalance, openRemainders, openItems, openCount,
                    openItemCount, explained, Optional.empty());
        }
    }

    /**
     * The suspense identity, per currency (`P8-TSK-010`, ADR-0070 §7):
     * CR−DR of {@code SUSPENSE_UNMATCHED} = Σ CREDIT remainders − Σ DEBIT remainders,
     * plus the NAMED term for Phase 7's parkings no suspense item's {@code origin_ref}
     * claims — exact before and after `-020`'s adoption, reading zero at rest once it runs.
     * {@code ledgerBalance} is read from the CREDIT side explicitly ({@code readFrom}),
     * never by leaning on the account being CREDIT-normal.
     */
    public record SuspenseVerdict(
            CurrencyCode currency,
            Money ledgerBalance,
            Money creditRemainders,
            Money debitRemainders,
            Money unadoptedParkings,
            long openItemCount,
            boolean explained) {}

    /**
     * The cash identity, per currency (`P8-TSK-016`, {@code INV-SET-06}): DR−CR of
     * {@code CASH_AT_BANK} (debit-normal, so its settled balance already reads DR−CR) equals
     * the summed head closing of every bank account's statement chain in that currency — and
     * only while each chain is UNBROKEN. {@code latestSequence} is the highest accepted
     * sequence (0 before the first statement), a count the gauge never publishes as money.
     */
    public record CashVerdict(
            CurrencyCode currency,
            Money ledgerBalance,
            Money chainClosing,
            long latestSequence,
            boolean unbroken,
            boolean explained) {}

    /** One sweep's whole answer — the gauges' and the report's one source. */
    public record Report(
            List<PositionVerdict> verdicts,
            Map<AccountPurpose, Long> unattributedByPurpose,
            Map<String, Long> openBySourceCode,
            List<SuspenseVerdict> suspenseVerdicts,
            Optional<java.time.LocalDate> oldestSuspenseOpenedOn,
            long suspenseUnowned,
            List<CashVerdict> cashVerdicts) {

        public Report {
            verdicts = List.copyOf(verdicts);
            unattributedByPurpose = Map.copyOf(unattributedByPurpose);
            openBySourceCode = Map.copyOf(openBySourceCode);
            suspenseVerdicts = List.copyOf(suspenseVerdicts);
            Objects.requireNonNull(
                    oldestSuspenseOpenedOn, "oldestSuspenseOpenedOn must not be null");
            cashVerdicts = List.copyOf(cashVerdicts);
        }

        /** A report without the cash term — the shape before `P8-TSK-016`. */
        public Report(
                List<PositionVerdict> verdicts,
                Map<AccountPurpose, Long> unattributedByPurpose,
                Map<String, Long> openBySourceCode,
                List<SuspenseVerdict> suspenseVerdicts,
                Optional<java.time.LocalDate> oldestSuspenseOpenedOn,
                long suspenseUnowned) {
            this(verdicts, unattributedByPurpose, openBySourceCode, suspenseVerdicts,
                    oldestSuspenseOpenedOn, suspenseUnowned, List.of());
        }

        /** The cash gauge's value: how many currencies' cash the chain does not explain. */
        public long cashCurrenciesFailing() {
            return cashVerdicts.stream().filter(verdict -> !verdict.explained()).count();
        }

        /** One currency's cash verdict, when that currency's cash account is seeded. */
        public Optional<CashVerdict> cashOf(CurrencyCode currency) {
            return cashVerdicts.stream()
                    .filter(verdict -> verdict.currency().equals(currency))
                    .findFirst();
        }

        /** Items with a remainder — {@code finapp.reconciliation.suspense.open}. */
        public long suspenseOpenItems() {
            return suspenseVerdicts.stream()
                    .mapToLong(SuspenseVerdict::openItemCount)
                    .sum();
        }

        /** The proof gauge's value for {@code purpose}: how many currencies fail. */
        public long currenciesFailing(AccountPurpose purpose) {
            if (purpose == AccountPurpose.SUSPENSE_UNMATCHED) {
                return suspenseVerdicts.stream()
                        .filter(verdict -> !verdict.explained())
                        .count();
            }
            return verdicts.stream()
                    .filter(verdict -> verdict.purpose() == purpose && !verdict.explained())
                    .count();
        }
    }

    /** The sweep — on the caller's connection, inside the caller's snapshot. */
    public Report sweep(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");

        // EVERY account of every reconciled purpose (P9-TSK-010, ADR-0078 section 8): the shared
        // positions per purpose and supported currency - the cash and suspense proofs read these
        // - and each counterparty's own, keyed (purpose, counterparty), so two counterparties on
        // one purpose are two positions and never one sum (INV-RAIL-04).
        Map<UUID, String> codeOf = new HashMap<>();
        counterparties.all(unitOfWork).forEach(registered -> codeOf.put(registered.id(), registered.code()));
        Map<AccountPurpose, Map<CurrencyCode, LedgerAccount>> positions =
                new EnumMap<>(AccountPurpose.class);
        Map<SettlementSources.Position, Map<CurrencyCode, LedgerAccount>> counterpartyPositions =
                new LinkedHashMap<>();
        List<LedgerAccount> population = new ArrayList<>();
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            List<LedgerAccount> ofPurpose = accounts.findAllOfPurpose(unitOfWork, purpose);
            population.addAll(ofPurpose);
            Map<CurrencyCode, LedgerAccount> byCurrency = new LinkedHashMap<>();
            for (CurrencyCode currency : SupportedCurrencies.ALL) {
                ofPurpose.stream()
                        .filter(account -> account.ownerRef().isEmpty() && account.currency().equals(currency))
                        .findFirst()
                        .ifPresent(account -> byCurrency.put(currency, account));
            }
            positions.put(purpose, byCurrency);
            for (LedgerAccount account : ofPurpose) {
                account.ownerRef().ifPresent(owner -> counterpartyPositions
                        .computeIfAbsent(
                                new SettlementSources.Position(purpose, Optional.of(
                                        Objects.requireNonNull(codeOf.get(owner),
                                                "a counterparty account names a registered counterparty (V021)"))),
                                key -> new LinkedHashMap<>())
                        .put(account.currency(), account));
            }
        }
        // Whose position a remainder sits on: a counterparty-owned purpose's remainder is its
        // source's counterparty's (one source per (purpose, counterparty), INV-SET-05).
        Map<UUID, Optional<String>> counterpartyBySource = new HashMap<>();
        for (SettlementSourceDescriptor declared : sources.declared()) {
            sourceRows.sourceByCode(unitOfWork, declared.code())
                    .ifPresent(row -> counterpartyBySource.put(row.id(), declared.settledCounterparty()));
        }
        java.util.function.BiFunction<AccountPurpose, UUID, SettlementSources.Position> positionOf =
                (purpose, sourceId) -> {
                    if (purpose.ownerKind() != com.finapp.ledger.OwnerKind.COUNTERPARTY) {
                        return new SettlementSources.Position(purpose, Optional.empty());
                    }
                    Optional<String> counterparty =
                            counterpartyBySource.getOrDefault(sourceId, Optional.empty());
                    if (counterparty.isEmpty()) {
                        throw new IllegalStateException(
                                "a remainder on the counterparty-owned " + purpose
                                        + " names no counterparty's source (INV-SET-05)");
                    }
                    return new SettlementSources.Position(purpose, counterparty);
                };

        // THE PROOF: the signed Money fold of open remainders, per position and currency.
        Map<SettlementSources.Position, Map<CurrencyCode, Money>> folded = new HashMap<>();
        Map<SettlementSources.Position, Map<CurrencyCode, Long>> openCounts = new HashMap<>();
        for (ExpectationReadings.OpenRemainder remainder : readings.openRemainders(unitOfWork)) {
            CurrencyCode currency = remainder.remainder().currency();
            SettlementSources.Position at = positionOf.apply(remainder.position(), remainder.sourceId());
            Map<CurrencyCode, Money> sums = folded.computeIfAbsent(at, p -> new HashMap<>());
            Money signed = remainder.remainder();
            Money current =
                    sums.getOrDefault(
                            currency, Money.ofPersisted(0, currency, signed.scale()));
            sums.put(
                    currency,
                    remainder.direction() == ExpectationDirection.INBOUND
                            ? current.plus(signed)
                            : current.minus(signed));
            openCounts.computeIfAbsent(at, p -> new HashMap<>()).merge(currency, 1L, Long::sum);
        }

        // THE ITEMS TERM (P8-TSK-009): every accepted, undisposed allocating line's claim,
        // folded the same way - INBOUND positive, OUTBOUND negative, never a SQL SUM.
        Map<SettlementSources.Position, Map<CurrencyCode, Money>> itemsFolded = new HashMap<>();
        Map<SettlementSources.Position, Map<CurrencyCode, Long>> itemCounts = new HashMap<>();
        for (ExpectationReadings.OpenItemRemainder item :
                readings.openItemRemainders(unitOfWork)) {
            CurrencyCode currency = item.remainder().currency();
            SettlementSources.Position at = positionOf.apply(item.position(), item.sourceId());
            Map<CurrencyCode, Money> sums = itemsFolded.computeIfAbsent(at, p -> new HashMap<>());
            Money signed = item.remainder();
            Money current =
                    sums.getOrDefault(
                            currency, Money.ofPersisted(0, currency, signed.scale()));
            sums.put(
                    currency,
                    item.direction() == ExpectationDirection.INBOUND
                            ? current.plus(signed)
                            : current.minus(signed));
            itemCounts.computeIfAbsent(at, p -> new HashMap<>()).merge(currency, 1L, Long::sum);
        }

        List<PositionVerdict> verdicts = new ArrayList<>();
        for (SettlementSources.Position proven : sources.settledPositions()) {
            AccountPurpose purpose = proven.purpose();
            Map<CurrencyCode, LedgerAccount> proofAccounts =
                    proven.counterparty().isEmpty()
                            ? positions.getOrDefault(purpose, Map.of())
                            : counterpartyPositions.getOrDefault(proven, Map.of());
            for (Map.Entry<CurrencyCode, LedgerAccount> position : proofAccounts.entrySet()) {
                Money balance =
                        readFrom(NormalBalance.DEBIT, unitOfWork, position.getValue());
                Money remainders =
                        Optional.ofNullable(folded.get(proven))
                                .map(sums -> sums.get(position.getKey()))
                                .orElse(
                                        Money.ofPersisted(
                                                0, position.getKey(), balance.scale()));
                Money items =
                        Optional.ofNullable(itemsFolded.get(proven))
                                .map(sums -> sums.get(position.getKey()))
                                .orElse(
                                        Money.ofPersisted(
                                                0, position.getKey(), balance.scale()));
                long open =
                        Optional.ofNullable(openCounts.get(proven))
                                .map(counts -> counts.getOrDefault(position.getKey(), 0L))
                                .orElse(0L);
                long openItems =
                        Optional.ofNullable(itemCounts.get(proven))
                                .map(counts -> counts.getOrDefault(position.getKey(), 0L))
                                .orElse(0L);
                verdicts.add(
                        new PositionVerdict(
                                purpose,
                                position.getKey(),
                                balance,
                                remainders,
                                items,
                                open,
                                openItems,
                                balance.equals(remainders.minus(items)),
                                proven.counterparty()));
            }
        }

        // COMPLETENESS: every line on a reconciled position, against the known pairs.
        // Every account of every reconciled purpose - the shared ones and each counterparty's
        // (P9-TSK-010) - never one per purpose.
        Map<LedgerAccountId, AccountPurpose> purposeOf = new HashMap<>();
        List<LedgerAccountId> reconciled = new ArrayList<>();
        for (LedgerAccount account : population) {
            purposeOf.put(account.id(), account.purpose());
            reconciled.add(account.id());
        }
        Set<ExpectationReadings.KnownLine> known =
                new HashSet<>(readings.knownLines(unitOfWork));
        // The second known-entry class (P8-TSK-009, ADR-0067 §9): a recognition entry's
        // every line - the position credit AND the PROCESSING_COSTS debit - is explained by
        // the acceptance that posted it.
        Set<UUID> recognitionEntries =
                new HashSet<>(batches.acceptedRecognitionEntries(unitOfWork));
        // The third and fourth known-entry classes (P8-TSK-010, ADR-0070 §7): a park's
        // entry, and every entry a suspense item owns - the rule by which Phase 7's
        // parking lines become known once the backfill adopts them (`-020`).
        recognitionEntries.addAll(suspense.knownEntries(unitOfWork));
        Map<AccountPurpose, Long> unattributed = new EnumMap<>(AccountPurpose.class);
        for (AccountPurpose purpose : AccountPurpose.reconciledPositions()) {
            unattributed.put(purpose, 0L);
        }
        for (JournalEntryStore.LineKey line : entries.lineKeysOn(unitOfWork, reconciled)) {
            boolean explained =
                    known.contains(
                                    new ExpectationReadings.KnownLine(
                                            line.entry().value(), line.account().value()))
                            || recognitionEntries.contains(line.entry().value());
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
        return new Report(
                verdicts,
                unattributed,
                openBySourceCode,
                suspenseVerdicts(unitOfWork, positions),
                suspense.oldestOpenedOn(unitOfWork),
                suspense.unownedCount(unitOfWork),
                cashVerdicts(unitOfWork, positions));
    }

    /**
     * THE CASH PROOF (`P8-TSK-016`, {@code INV-SET-06}), per currency: each bank account's
     * chain of ACCEPTED statements — read in this sweep's one snapshot — is unbroken when its
     * sequences run 1..n with no hole, sequence 1 opens at zero and every later opening is its
     * predecessor's closing; the verdict holds when every chain of the currency is unbroken
     * and {@code CASH_AT_BANK}'s DR−CR equals the sum of their head closings. Folded through
     * {@code Money}; the balances are the statement's own words, never recomputed.
     */
    private List<CashVerdict> cashVerdicts(
            Connection unitOfWork,
            Map<AccountPurpose, Map<CurrencyCode, LedgerAccount>> positions) {
        Map<CurrencyCode, Map<UUID, List<com.finapp.settlement.SettlementBatchStore.StatementLink>>>
                chains = new HashMap<>();
        for (com.finapp.settlement.SettlementBatchStore.StatementLink link :
                batches.acceptedStatements(unitOfWork)) {
            chains.computeIfAbsent(link.currency(), c -> new LinkedHashMap<>())
                    .computeIfAbsent(link.sourceId(), s -> new ArrayList<>())
                    .add(link);
        }
        List<CashVerdict> verdicts = new ArrayList<>();
        for (Map.Entry<CurrencyCode, LedgerAccount> position :
                positions.get(AccountPurpose.CASH_AT_BANK).entrySet()) {
            CurrencyCode currency = position.getKey();
            Money balance =
                    balances.derive(unitOfWork, position.getValue().id(), AsOf.latest())
                            .settled();
            Money closing = Money.ofPersisted(0, currency, balance.scale());
            boolean unbroken = true;
            long latest = 0;
            for (List<com.finapp.settlement.SettlementBatchStore.StatementLink> chain :
                    chains.getOrDefault(currency, Map.of()).values()) {
                // The store reads the chain ordered by sequence (acceptedStatements).
                long expected = 1;
                Money previousClosing = Money.ofPersisted(0, currency, balance.scale());
                Money head = previousClosing;
                for (com.finapp.settlement.SettlementBatchStore.StatementLink link : chain) {
                    Money opening =
                            Money.ofPersisted(link.openingMinor(), currency, link.scale());
                    if (link.sequence() != expected || !opening.equals(previousClosing)) {
                        unbroken = false;
                    }
                    previousClosing =
                            Money.ofPersisted(link.closingMinor(), currency, link.scale());
                    head = previousClosing;
                    expected = link.sequence() + 1;
                    latest = Math.max(latest, link.sequence());
                }
                closing = closing.plus(head);
            }
            verdicts.add(
                    new CashVerdict(
                            currency,
                            balance,
                            closing,
                            latest,
                            unbroken,
                            unbroken && balance.equals(closing)));
        }
        return List.copyOf(verdicts);
    }

    /**
     * THE SUSPENSE PROOF (`P8-TSK-010`, ADR-0070 §7), per currency:
     * CR−DR = Σ CREDIT remainders − Σ DEBIT remainders + Σ Phase 7 parkings not yet
     * adopted. The last is the parkings whose id no {@code UNMATCHED_CONFIRMATION} item's
     * {@code origin_ref} names — without it the proof would fail on any database holding a
     * Phase 7 parking until the backfill adopts them (`-020`). Folded through {@code Money}, gross,
     * CREDIT and DEBIT never netted inside a term.
     */
    private List<SuspenseVerdict> suspenseVerdicts(
            Connection unitOfWork,
            Map<AccountPurpose, Map<CurrencyCode, LedgerAccount>> positions) {
        Map<CurrencyCode, Money> credits = new HashMap<>();
        Map<CurrencyCode, Money> debits = new HashMap<>();
        Map<CurrencyCode, Long> openItems = new HashMap<>();
        for (com.finapp.reconciliation.SuspenseReadings.OpenSuspenseRemainder open :
                suspense.openRemainders(unitOfWork)) {
            CurrencyCode currency = open.remainder().currency();
            Map<CurrencyCode, Money> side =
                    open.side() == com.finapp.reconciliation.SuspenseSide.CREDIT
                            ? credits
                            : debits;
            side.merge(currency, open.remainder(), Money::plus);
            openItems.merge(currency, 1L, Long::sum);
        }

        // Phase 7's parkings the register does not own yet, paged through payments' own
        // read - all CREDIT-side (DR clearing / CR suspense), by their entries' shape.
        Set<String> owned =
                suspense.ownedOriginRefs(
                        unitOfWork,
                        com.finapp.reconciliation.SuspenseOrigin.UNMATCHED_CONFIRMATION);
        Map<CurrencyCode, Money> unadopted = new HashMap<>();
        UUID after = new UUID(0L, 0L);
        while (true) {
            List<com.finapp.payments.UnmatchedConfirmation> page =
                    parkings.page(unitOfWork, after, 200);
            if (page.isEmpty()) {
                break;
            }
            for (com.finapp.payments.UnmatchedConfirmation parking : page) {
                if (!owned.contains(parking.id().toString())) {
                    unadopted.merge(
                            parking.amount().currency(), parking.amount(), Money::plus);
                }
                after = parking.id();
            }
            if (page.size() < 200) {
                break;
            }
        }

        List<SuspenseVerdict> verdicts = new ArrayList<>();
        for (Map.Entry<CurrencyCode, LedgerAccount> position :
                positions.get(AccountPurpose.SUSPENSE_UNMATCHED).entrySet()) {
            CurrencyCode currency = position.getKey();
            Money balance = readFrom(NormalBalance.CREDIT, unitOfWork, position.getValue());
            Money zero = Money.ofPersisted(0, currency, balance.scale());
            Money credit = credits.getOrDefault(currency, zero);
            Money debit = debits.getOrDefault(currency, zero);
            Money phase7 = unadopted.getOrDefault(currency, zero);
            verdicts.add(
                    new SuspenseVerdict(
                            currency,
                            balance,
                            credit,
                            debit,
                            phase7,
                            openItems.getOrDefault(currency, 0L),
                            balance.equals(credit.minus(debit).plus(phase7))));
        }
        return List.copyOf(verdicts);
    }

    /**
     * {@code account}'s balance read from {@code side}: DR−CR for {@code DEBIT}, CR−DR for
     * {@code CREDIT}. The derivation signs by the account's <em>normal</em> balance
     * ({@link com.finapp.ledger.DerivedBalance}), so where the stored normal side differs
     * from the side an identity reads, its answer is negated — through {@code Money}, still
     * never a SQL {@code SUM}. Each identity names its side; neither leans on the chart's
     * classification. The clearing positions are not uniform: {@code SETTLEMENT_CLEARING}
     * and {@code INSTANT_CLEARING} are DEBIT-normal, {@code PAYOUT_CLEARING} CREDIT-normal
     * (ledger {@code V012}) — read unflipped, every open payout was judged against its own
     * negation.
     */
    private Money readFrom(NormalBalance side, Connection unitOfWork, LedgerAccount account) {
        Money settled = balances.derive(unitOfWork, account.id(), AsOf.latest()).settled();
        return account.normalBalance() == side ? settled : settled.negated();
    }
}
