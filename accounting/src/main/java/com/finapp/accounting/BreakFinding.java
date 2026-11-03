package com.finapp.accounting;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What one of the three break-raising checks found (`P14-TSK-010`; ADR-0117 decisions 2-3; {@code PHASE_14_PLAN.md}
 * section 13.3; {@code INV-ACC-12}): a classified discrepancy on one subject, with its evidence and the register version
 * it was judged by - never a correction. {@link AccountingBreaks#raise} turns it into a break born once per
 * {@code (period, check, subject, generation)}.
 *
 * @param check the check that found it - one of {@link #CHECKS}
 * @param type its classification, one of its check's types ({@link #checkOf})
 * @param subject what the discrepancy is about, unique within the period and check: {@code shape:<entry>},
 *     {@code unbalanced:<entry>}, {@code namespace:<TYPE> <namespace>}, {@code key:<PURPOSE>/<OWNER_KIND>},
 *     {@code currency:<CUR>}, {@code <PROOF>:<proof subject>}
 * @param evidence what was seen - codes, ids and counts, <strong>never an amount</strong>, a name or an entry's free-text
 *     reference; cut to {@link #MAX_EVIDENCE}
 * @param judgedBy the register version: {@code posting-rules:v<n>}, {@code gl-mapping:v<n>} or {@code source-proofs:v<n>}
 */
public record BreakFinding(CloseCheckCode check, BreakType type, String subject, String evidence, String judgedBy) {

    /** The checks that raise breaks; the close run's other six read them or judge elsewhere (`P14-TSK-011`). */
    public static final Set<CloseCheckCode> CHECKS = EnumSet.of(
            CloseCheckCode.GL_EQUALS_LEDGER, CloseCheckCode.RULE_CONFORMANCE, CloseCheckCode.SOURCE_COMPLETENESS);

    /** {@code accounting V006}'s {@code accounting_break_subject_bounded}. */
    public static final int MAX_SUBJECT = 300;

    /** {@code accounting V006}'s {@code accounting_break_evidence_bounded}. */
    public static final int MAX_EVIDENCE = 2000;

    /** {@code accounting V006}'s {@code accounting_break_judged_by_a_version}. */
    public static final Pattern JUDGED_BY = Pattern.compile("(posting-rules|gl-mapping|source-proofs):v[1-9][0-9]{0,8}");

    public BreakFinding {
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(judgedBy, "judgedBy");
        if (checkOf(type) != check) {
            throw new IllegalArgumentException("a " + type + " break is " + checkOf(type) + "'s, not " + check + "'s");
        }
        if (subject.isBlank() || subject.length() > MAX_SUBJECT || !printable(subject)) {
            throw new IllegalArgumentException("a break's subject is 1 to " + MAX_SUBJECT + " printable characters");
        }
        evidence = evidence.isBlank() ? "-" : cut(evidence, MAX_EVIDENCE);
        if (!JUDGED_BY.matcher(judgedBy).matches()) {
            throw new IllegalArgumentException("a break names the register version it was judged by: " + judgedBy);
        }
    }

    /** A finding to raise, and the generation of its subject it is raised at (`P14-TSK-012`, ADR-0117). */
    public record Raise(BreakFinding finding, int generation) {

        public Raise {
            Objects.requireNonNull(finding, "finding");
            if (generation < 1) {
                throw new IllegalArgumentException("a break's generation starts at 1");
            }
        }
    }

    /** The check that raises breaks of {@code type} - section 13.3's three levels. */
    public static CloseCheckCode checkOf(BreakType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case MAPPING_GAP, DOUBLE_MAPPING -> CloseCheckCode.GL_EQUALS_LEDGER;
            case UNREGISTERED_POSTING, SHAPE_MISMATCH, UNBALANCED_ENTRY -> CloseCheckCode.RULE_CONFORMANCE;
            case SOURCE_DISCREPANCY, SOURCE_DELAYED -> CloseCheckCode.SOURCE_COMPLETENESS;
        };
    }

    /** {@code text} cut to {@code bound} characters - evidence and subjects are bounded by their columns. */
    static String cut(String text, int bound) {
        return text.length() <= bound ? text : text.substring(0, bound);
    }

    private static boolean printable(String text) {
        return text.chars().noneMatch(Character::isISOControl);
    }
}
