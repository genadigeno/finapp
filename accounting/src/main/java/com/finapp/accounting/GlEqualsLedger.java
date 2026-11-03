package com.finapp.accounting;

import com.finapp.accounting.reporting.GlBalances;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GL = ledger - section 13.3's first level (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-06}, {@code INV-ACC-12}):
 * a period's GL as `P14-TSK-007`'s fold computed it ({@link GlBalances}, under the version the period pins or has in
 * force) judged for the two ways a line can fail to map exactly once. Pure: the balances arrive computed on a read-only
 * snapshot; nothing is read or written here.
 *
 * <ul>
 *   <li><strong>{@link BreakType#MAPPING_GAP}</strong> per unmapped key - the fold's explicit unmapped bucket, one finding
 *       per {@code (purpose, owner kind)} whatever its currencies (subject {@code key:<PURPOSE>/<OWNER_KIND>}): the gap
 *       is the version's, and one rule closes it.</li>
 *   <li>Per currency, the GL's mapped lines <em>plus</em> its unmapped lines against the ledger's own totals, read by a
 *       statement that never joins an account: every line counted once in one bucket or the other, so any remaining
 *       difference is a line counted twice ({@link BreakType#DOUBLE_MAPPING} - more lines or more value than the ledger)
 *       or dropped ({@link BreakType#MAPPING_GAP} - fewer), subject {@code currency:<CUR>}. A version holds one rule per
 *       key by construction ({@code GlMappingVersion}, {@code accounting V002}'s primary key) - and is still judged.</li>
 * </ul>
 *
 * Every finding is judged by {@code gl-mapping:v<n>} - the version the figures name. Evidence carries counts and the
 * version, never an amount.
 */
public final class GlEqualsLedger {

    /** The register version a period's GL is judged by: its mapping version. */
    public static String judgedBy(GlBalances balances) {
        return "gl-mapping:v" + balances.mapping().version();
    }

    /** Every discrepancy of {@code balances}, sorted by subject. */
    public List<BreakFinding> judge(GlBalances balances) {
        String judgedBy = judgedBy(balances);
        Map<String, List<GlBalances.Unmapped>> byKey = new TreeMap<>();
        for (GlBalances.Unmapped unmapped : balances.unmapped()) {
            byKey.computeIfAbsent(unmapped.key(), key -> new ArrayList<>()).add(unmapped);
        }
        List<BreakFinding> findings = new ArrayList<>();
        byKey.forEach((key, gaps) -> {
            StringBuilder currencies = new StringBuilder();
            // One rendering, whatever order the fold answered in (ADR-0117: a finding is compared with what a person decided on).
            for (GlBalances.Unmapped gap : gaps.stream().sorted(java.util.Comparator.comparing(GlBalances.Unmapped::currency)).toList()) {
                currencies.append(currencies.isEmpty() ? "" : ", ").append(gap.currency()).append(" (").append(gap.lines())
                        .append(" lines)");
            }
            findings.add(new BreakFinding(CloseCheckCode.GL_EQUALS_LEDGER, BreakType.MAPPING_GAP, "key:" + key,
                    "key=" + key + ", period=" + balances.mapping().period() + ", version=v" + balances.mapping().version()
                            + ", unmapped=[" + currencies + "]", judgedBy));
        });
        for (GlBalances.Agreement agreement : balances.agreement()) {
            long counted = agreement.glLines() + agreement.unmappedLines();
            int debits = agreement.glDebits().add(agreement.unmappedDebits()).compareTo(agreement.ledgerDebits());
            int credits = agreement.glCredits().add(agreement.unmappedCredits()).compareTo(agreement.ledgerCredits());
            if (counted == agreement.ledgerLines() && debits == 0 && credits == 0) {
                continue;
            }
            BreakType type = counted > agreement.ledgerLines()
                    || (counted == agreement.ledgerLines() && (debits > 0 || credits > 0))
                    ? BreakType.DOUBLE_MAPPING
                    : BreakType.MAPPING_GAP;
            findings.add(new BreakFinding(CloseCheckCode.GL_EQUALS_LEDGER, type, "currency:" + agreement.currency(),
                    "currency=" + agreement.currency() + ", period=" + balances.mapping().period() + ", version=v"
                            + balances.mapping().version() + ", glLines=" + agreement.glLines() + ", unmappedLines="
                            + agreement.unmappedLines() + ", ledgerLines=" + agreement.ledgerLines() + ", debits="
                            + side(debits) + ", credits=" + side(credits), judgedBy));
        }
        return findings;
    }

    /** How the GL's side compares with the ledger's - never the amount. */
    private static String side(int comparison) {
        return comparison == 0 ? "equal" : comparison > 0 ? "GL above ledger" : "GL below ledger";
    }
}
