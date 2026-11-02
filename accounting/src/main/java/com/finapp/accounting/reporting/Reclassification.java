package com.finapp.accounting.reporting;

import com.finapp.accounting.GlMappingVersion;
import com.finapp.accounting.GlMappingVersionId;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.MappingRule;
import com.finapp.accounting.Side;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The reclassification bridge (`P14-TSK-017`; `P14-TSK-009`'s review, M-5; ADR-0115 As built): when the period before pins another
 * GL mapping version, a period's opening under its own version <em>restates</em> the previous closing - the per-currency totals agree,
 * the per-account figures move. This is that move, stated: every purpose whose account changed, with the balance it carried across, and
 * per GL account the previous closing beside this opening.
 *
 * <p><strong>Exact, and pure.</strong> The lines dated before this period's first day are the very lines through the previous period's
 * last, so one set of aggregates ({@code TrialBalanceReads.before}) folded through both versions' rules is both sides; nothing is read
 * here and nothing is rounded. Per currency the accounts' differences foot to zero ({@link #footsToZero()}): a reclassification moves
 * value between accounts and creates none. A purpose neither version maps stays unmapped, and is no move.
 *
 * <p><strong>Per counterparty</strong> (`P14-TSK-017`'s review, I-1): {@code fx-sim-a} at +1,000 and {@code fx-sim-b} at -1,000 moved are
 * two moves. Summed under their purpose they would net to no move at all, and the bridge would say nothing moved. So the aggregates are
 * handed over per counterparty ({@code PurposeRows.perCounterparty}); an account's bridge row is the account's, whoever's its parts are.
 *
 * <p>{@code GL_ACTIVITY} prints it. {@code P14-TSK-016}'s statements owe the same bridge and may call {@link #between} as it stands, handing
 * it the same aggregates.
 *
 * @param previous the version the previous period pins
 * @param version this run's version - empty when none was pinned (every line unmapped)
 * @param moves per purpose, owner kind, counterparty and currency whose account differs, with a balance: sorted, never a zero
 * @param accounts per GL account (or the unmapped bucket) and currency whose figure differs: sorted
 */
public record Reclassification(
        GlMappingVersionId previous,
        Optional<GlMappingVersionId> version,
        List<Move> moves,
        List<Bridge> accounts) {

    public Reclassification {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(version, "version");
        moves = List.copyOf(moves);
        accounts = List.copyOf(accounts);
    }

    /** One purpose's balance, carried from one account to another; an empty account is the unmapped bucket. */
    public record Move(String purpose, String ownerKind, Optional<String> counterparty, String currency, Optional<String> from,
            Optional<String> to, BigDecimal balance) {

        public Move {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(balance, "balance");
        }
    }

    /** One GL account in one currency: the previous closing under the previous version, this opening under this one. */
    public record Bridge(Optional<String> account, String currency, BigDecimal previousClosing, BigDecimal opening) {

        public Bridge {
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(previousClosing, "previousClosing");
            Objects.requireNonNull(opening, "opening");
        }

        /** What the version change moved in or out of this account. */
        public BigDecimal difference() {
            return opening.subtract(previousClosing);
        }
    }

    /**
     * The bridge between {@code previousContent} and {@code versionContent} over the opening's {@code aggregates}.
     *
     * @param versionContent this run's content - present exactly when {@code version} is
     */
    public static Reclassification between(GlMappingVersionId previous, GlMappingVersion previousContent,
            Optional<GlMappingVersionId> version, Optional<GlMappingVersion> versionContent,
            List<JournalReads.Aggregate> aggregates) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(previousContent, "previousContent");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(versionContent, "versionContent");
        if (version.isPresent() != versionContent.isPresent()) {
            throw new IllegalArgumentException("a pinned version names its id and its content together");
        }
        Map<String, MappingRule> before = previousContent.rulesByKey();
        Map<String, MappingRule> after = versionContent.map(GlMappingVersion::rulesByKey).orElse(Map.of());
        Map<String, Balance> byKey = new TreeMap<>();
        for (JournalReads.Aggregate aggregate : aggregates) {
            byKey.computeIfAbsent(aggregate.key() + "\u0000" + aggregate.counterparty().orElse("") + "\u0000" + aggregate.currency(),
                    ignored -> new Balance(aggregate)).add(aggregate);
        }
        List<Move> moves = new ArrayList<>();
        Map<String, Building> bridges = new TreeMap<>();
        for (Balance balance : byKey.values()) {
            Optional<String> from = accountOf(before, balance.key());
            Optional<String> to = accountOf(after, balance.key());
            bridges.computeIfAbsent(from.orElse("") + "\u0000" + balance.currency,
                    ignored -> new Building(from, balance.currency)).previousClosing.add(balance.net);
            bridges.computeIfAbsent(to.orElse("") + "\u0000" + balance.currency,
                    ignored -> new Building(to, balance.currency)).opening.add(balance.net);
            if (!from.equals(to) && balance.net.signum() != 0) {
                moves.add(new Move(balance.purpose, balance.ownerKind, balance.counterparty, balance.currency, from, to,
                        balance.net));
            }
        }
        List<Bridge> accounts = bridges.values().stream()
                .map(Building::toBridge)
                .filter(bridge -> bridge.difference().signum() != 0)
                .toList();
        return new Reclassification(previous, version, moves, accounts);
    }

    /** Per currency, every account's difference sums to zero: a version change moves value and creates none. */
    public boolean footsToZero() {
        Map<String, BigDecimal> sums = new TreeMap<>();
        accounts.forEach(bridge -> sums.merge(bridge.currency(), bridge.difference(), BigDecimal::add));
        return sums.values().stream().allMatch(sum -> sum.signum() == 0);
    }

    /** The largest scale any of its amounts carries in {@code currency}. */
    int scaleIn(String currency) {
        int scale = 0;
        for (Move move : moves) {
            if (move.currency().equals(currency)) {
                scale = Math.max(scale, move.balance().scale());
            }
        }
        for (Bridge bridge : accounts) {
            if (bridge.currency().equals(currency)) {
                scale = Math.max(scale, Math.max(bridge.previousClosing().scale(), bridge.opening().scale()));
            }
        }
        return scale;
    }

    /** The currencies it holds a figure in, sorted. */
    List<String> currencies() {
        Map<String, Boolean> seen = new TreeMap<>();
        moves.forEach(move -> seen.put(move.currency(), true));
        accounts.forEach(bridge -> seen.put(bridge.currency(), true));
        return List.copyOf(seen.keySet());
    }

    /** The content's {@code reclassification} member, each currency at the one scale {@code scales} names. */
    CanonicalJson.Obj asValue(Map<String, Integer> scales) {
        CanonicalJson.Arr movesOut = CanonicalJson.array();
        for (Move move : moves) {
            CanonicalJson.Obj out = PurposeRows.withCounterparty(CanonicalJson.object()
                    .put("purpose", move.purpose())
                    .put("ownerKind", move.ownerKind())
                    .put("currency", move.currency())
                    .put("balance", TrialBalance.money(move.balance(), move.currency(), scales.get(move.currency()))),
                    move.counterparty());
            accountOrNull(out, "from", move.from());
            accountOrNull(out, "to", move.to());
            movesOut.add(out);
        }
        CanonicalJson.Arr accountsOut = CanonicalJson.array();
        for (Bridge bridge : accounts) {
            int scale = scales.get(bridge.currency());
            CanonicalJson.Obj out = CanonicalJson.object()
                    .put("currency", bridge.currency())
                    .put("previousClosing", TrialBalance.money(bridge.previousClosing(), bridge.currency(), scale))
                    .put("opening", TrialBalance.money(bridge.opening(), bridge.currency(), scale))
                    .put("difference", TrialBalance.money(bridge.difference(), bridge.currency(), scale));
            accountOrNull(out, "account", bridge.account());
            accountsOut.add(out);
        }
        CanonicalJson.Obj out = CanonicalJson.object()
                .put("previousPeriodPin", previous.value().toString())
                .put("moves", movesOut)
                .put("accounts", accountsOut)
                .put("footsToZero", footsToZero());
        if (version.isPresent()) {
            return out.put("version", version.get().value().toString());
        }
        return out.putNull("version");
    }

    private static void accountOrNull(CanonicalJson.Obj out, String member, Optional<String> account) {
        if (account.isPresent()) {
            out.put(member, account.get());
        } else {
            out.putNull(member);
        }
    }

    private static Optional<String> accountOf(Map<String, MappingRule> rules, String key) {
        return Optional.ofNullable(rules.get(key)).map(MappingRule::glAccountCode);
    }

    /** One purpose's net in one currency, one counterparty's where it has them; exact. */
    private static final class Balance {
        private final String purpose;
        private final String ownerKind;
        private final Optional<String> counterparty;
        private final String currency;
        private BigDecimal net = BigDecimal.ZERO;

        Balance(JournalReads.Aggregate first) {
            this.purpose = first.purpose();
            this.ownerKind = first.ownerKind();
            this.counterparty = first.counterparty();
            this.currency = first.currency();
        }

        void add(JournalReads.Aggregate aggregate) {
            net = aggregate.side() == Side.DEBIT ? net.add(aggregate.amount()) : net.subtract(aggregate.amount());
        }

        String key() {
            return purpose + "/" + ownerKind;
        }
    }

    /** A running sum - a mutable BigDecimal's worth, so one bridge row folds both sides. */
    private static final class Sum {
        private BigDecimal value = BigDecimal.ZERO;

        void add(BigDecimal amount) {
            value = value.add(amount);
        }
    }

    /** One account's two sides being folded. */
    private static final class Building {
        private final Optional<String> account;
        private final String currency;
        private final Sum previousClosing = new Sum();
        private final Sum opening = new Sum();

        Building(Optional<String> account, String currency) {
            this.account = account;
            this.currency = currency;
        }

        Bridge toBridge() {
            return new Bridge(account, currency, previousClosing.value, opening.value);
        }
    }
}
