package com.finapp.accounting;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The GL mapping versions' reads (`P14-TSK-003`; {@code PHASE_14_PLAN.md} section 10): one version whole, and a version
 * against another - by default the version in force - with the live judgement against the ledger's chart, which is what
 * the second person reviews before activating: what changes, and whether the activation would be refused. Reads only;
 * no lock, no write.
 */
@RequiredArgsConstructor
public final class GlMappings {

    @NonNull private final GlMappingStore store;
    @NonNull private final LedgerChart chart;

    /** A version: its machine row and its content as born. */
    public record Version(GlMappingStore.VersionRow row, GlMappingVersion content) {}

    /** A changed GL account: the code, before and after. */
    public record AccountChange(String code, GlAccount before, GlAccount after) {}

    /** A changed rule: the key, the GL account before and after. */
    public record RuleChange(String key, String before, String after) {}

    /**
     * A version against its base (empty when nothing is in force): accounts and rules added, removed and changed, and
     * the version judged against the chart now.
     */
    public record Diff(
            Version version,
            Optional<Version> base,
            List<GlAccount> accountsAdded,
            List<GlAccount> accountsRemoved,
            List<AccountChange> accountsChanged,
            List<MappingRule> rulesAdded,
            List<MappingRule> rulesRemoved,
            List<RuleChange> rulesChanged,
            MappingJudgement judgement,
            List<LedgerChart.ChartCounterparty> counterparties) {}

    /** One version by id, in any state. */
    public Version version(Connection unitOfWork, GlMappingVersionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        GlMappingStore.VersionRow row = store.find(unitOfWork, id)
                .orElseThrow(GlMappingAdministration.MappingNotFound::new);
        return new Version(row, store.content(unitOfWork, id));
    }

    /** {@code id} against {@code against}, or against the version in force when none is named. */
    public Diff diff(Connection unitOfWork, GlMappingVersionId id, Optional<GlMappingVersionId> against) {
        Version version = version(unitOfWork, id);
        Optional<Version> base = against.isPresent()
                ? Optional.of(version(unitOfWork, against.get()))
                : store.active(unitOfWork).map(row -> new Version(row, store.content(unitOfWork, row.id())));
        return between(version, base, chart.read(unitOfWork));
    }

    /** The pure half of {@link #diff}: every list in code or key order; the counterparties the chart's dimension. */
    static Diff between(Version version, Optional<Version> base, LedgerChart.Chart now) {
        MappingJudgement judgement = MappingJudgement.judge(version.content(), now);
        Map<String, GlAccount> before = new TreeMap<>();
        base.ifPresent(b -> b.content().accounts().forEach(account -> before.put(account.code(), account)));
        Map<String, GlAccount> after = new TreeMap<>();
        version.content().accounts().forEach(account -> after.put(account.code(), account));
        List<GlAccount> accountsAdded = new ArrayList<>();
        List<GlAccount> accountsRemoved = new ArrayList<>();
        List<AccountChange> accountsChanged = new ArrayList<>();
        for (String code : union(before.keySet(), after.keySet())) {
            GlAccount was = before.get(code);
            GlAccount is = after.get(code);
            if (was == null) {
                accountsAdded.add(is);
            } else if (is == null) {
                accountsRemoved.add(was);
            } else if (!was.equals(is)) {
                accountsChanged.add(new AccountChange(code, was, is));
            }
        }
        Map<String, MappingRule> rulesBefore = new TreeMap<>(base.map(b -> b.content().rulesByKey()).orElse(Map.of()));
        Map<String, MappingRule> rulesAfter = new TreeMap<>(version.content().rulesByKey());
        List<MappingRule> rulesAdded = new ArrayList<>();
        List<MappingRule> rulesRemoved = new ArrayList<>();
        List<RuleChange> rulesChanged = new ArrayList<>();
        for (String key : union(rulesBefore.keySet(), rulesAfter.keySet())) {
            MappingRule was = rulesBefore.get(key);
            MappingRule is = rulesAfter.get(key);
            if (was == null) {
                rulesAdded.add(is);
            } else if (is == null) {
                rulesRemoved.add(was);
            } else if (!was.glAccountCode().equals(is.glAccountCode())) {
                rulesChanged.add(new RuleChange(key, was.glAccountCode(), is.glAccountCode()));
            }
        }
        return new Diff(version, base, accountsAdded, accountsRemoved, accountsChanged, rulesAdded, rulesRemoved,
                rulesChanged, judgement, now.counterparties());
    }

    private static TreeSet<String> union(java.util.Set<String> left, java.util.Set<String> right) {
        TreeSet<String> all = new TreeSet<>(left);
        all.addAll(right);
        return all;
    }
}
