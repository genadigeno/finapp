package com.finapp.risk;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What an alert points at (`P13-TSK-015`; {@code PHASE_13_PLAN.md} section 11 "evidence protection"; ADR-0110 decision
 * 4; {@code risk V010}'s {@code risk_alert_evidence_bounded}) - <strong>references only</strong>: the signals, the sealed
 * assessments and the ledger journal entries the rule read, each resolved through an audited read, never copied. A
 * journal entry is named rather than its monitored transaction because the journal is the financial truth and the two
 * are one-to-one ({@code UNIQUE (journal_entry_id)}, {@code INV-RSK-09}).
 *
 * <p>Bounded: each list at most {@link #BOUND} references, distinct, in the order the raiser names them - the rule's
 * first evidence, not a log of every detection (a later detection of the same subject, rule and window is the same
 * alert, its evidence as born). A raiser holding more names its first {@code BOUND}; the rest are re-read from the
 * rule's window.
 */
public record AlertEvidence(List<RiskSignalId> signals, List<RiskAssessmentId> assessments, List<UUID> journalEntries) {

    /** The most references of each kind an alert carries - the {@code CHECK}'s 100. */
    public static final int BOUND = 100;

    /** No reference at all - only an operational alert may carry none. */
    public static final AlertEvidence NONE = new AlertEvidence(List.of(), List.of(), List.of());

    public AlertEvidence {
        signals = bounded(signals, "signal");
        assessments = bounded(assessments, "assessment");
        journalEntries = bounded(journalEntries, "journal entry");
    }

    /** One sealed assessment - T-eval's evidence. */
    public static AlertEvidence assessment(RiskAssessmentId assessment) {
        return new AlertEvidence(List.of(), List.of(Objects.requireNonNull(assessment, "assessment")), List.of());
    }

    /** Signals - the retrospective fraud rules' evidence. */
    public static AlertEvidence signals(List<RiskSignalId> signals) {
        return new AlertEvidence(signals, List.of(), List.of());
    }

    /** Journal entries - the monitoring rules' evidence. */
    public static AlertEvidence journalEntries(List<UUID> journalEntries) {
        return new AlertEvidence(List.of(), List.of(), journalEntries);
    }

    /** True when the alert points at nothing. */
    public boolean isEmpty() {
        return signals.isEmpty() && assessments.isEmpty() && journalEntries.isEmpty();
    }

    /** The references in all - never more than three times the bound. */
    public int size() {
        return signals.size() + assessments.size() + journalEntries.size();
    }

    private static <T> List<T> bounded(List<T> references, String what) {
        Objects.requireNonNull(references, what + " references");
        List<T> copy = List.copyOf(references);
        if (copy.size() > BOUND) {
            throw new IllegalArgumentException("an alert names at most " + BOUND + " " + what + " references");
        }
        if (new HashSet<>(copy).size() != copy.size()) {
            throw new IllegalArgumentException("an alert names each " + what + " once");
        }
        return copy;
    }

    @Override
    public String toString() {
        // Counts only: the references are CONFIDENTIAL evidence.
        return "AlertEvidence[signals=" + signals.size() + ", assessments=" + assessments.size() + ", journalEntries="
                + journalEntries.size() + "]";
    }
}
