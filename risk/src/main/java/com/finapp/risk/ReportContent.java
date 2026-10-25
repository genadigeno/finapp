package com.finapp.risk;

import com.finapp.sharedkernel.security.InstrumentShapes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * What a regulatory report says - the content categories of {@code PHASE_13_PLAN.md} section 12.9, fixed when the report is
 * drafted and never edited (`P13-TSK-021`; {@code risk V016}'s content columns; ADR-0111 decisions 5, 7; owner decision
 * C4): the <strong>jurisdiction code</strong> and the <strong>format version</strong> the adapter speaks, the
 * <strong>reporting entity's reference</strong> with that authority, the <strong>subject</strong> (a party, by reference)
 * and its <strong>accounts</strong>, the <strong>transactions referenced</strong> (monitored transaction ids, the case's
 * evidence), the <strong>grounds</strong> - the case's reason codes (its alerts' rule codes) and the approved
 * <strong>narrative</strong> - and the <strong>attachments' references</strong>.
 *
 * <p>The categories are the platform's abstraction; every field a real jurisdiction adds is the adapter's data (the EU
 * reference's additions are {@code app}'s simulated adapter's). Everything is a reference or a closed code but the
 * narrative, which is a person's text: {@code RESTRICTED-PII}, 1 to {@link #NARRATIVE_BOUND} characters, screened for
 * card-number and account shapes ({@link InstrumentShapes}, {@code risk.holds_instrument_shape} beneath), readable only on
 * the AML desk's doors and never logged, audited or announced ({@code INV-RSK-08}, {@code INV-AUD-02}).
 * {@link #toString()} names counts only.
 */
public record ReportContent(
        String jurisdiction,
        int formatVersion,
        String reportingEntityReference,
        UUID subject,
        List<UUID> accounts,
        List<UUID> transactions,
        List<String> grounds,
        String narrative,
        List<UUID> attachments) {

    /** A narrative's longest length - {@code regulatory_report_narrative_screened}'s bound. */
    public static final int NARRATIVE_BOUND = 8000;

    /** The most accounts a report names - {@link MonitoredParties#BOUND}, a party's whole set. */
    public static final int ACCOUNTS_BOUND = MonitoredParties.BOUND;

    /** The most transactions a report references - one alert's evidence bound. */
    public static final int TRANSACTIONS_BOUND = AlertEvidence.BOUND;

    /** The most grounds a report names. */
    public static final int GROUNDS_BOUND = 50;

    /** The most attachments' references a report carries. */
    public static final int ATTACHMENTS_BOUND = 20;

    /** The highest format version a report carries - {@code regulatory_report_format_version_bounded}. */
    public static final int FORMAT_VERSION_BOUND = 999;

    /** A jurisdiction code - a region and an optional qualifier, {@code EU-REF} the reference one. */
    public static final Pattern JURISDICTION = Pattern.compile("[A-Z]{2,3}(-[A-Z0-9]{2,8})?");

    /** An opaque reference an authority or an adapter issues - the reporting entity's, the provider's. */
    public static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    /** A closed code - a ground, a rejection. */
    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    public ReportContent {
        Objects.requireNonNull(jurisdiction, "jurisdiction");
        Objects.requireNonNull(reportingEntityReference, "reportingEntityReference");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(narrative, "narrative");
        if (!JURISDICTION.matcher(jurisdiction).matches()) {
            throw new IllegalArgumentException("a jurisdiction is a region code and an optional qualifier");
        }
        if (formatVersion < 1 || formatVersion > FORMAT_VERSION_BOUND) {
            throw new IllegalArgumentException("a format version is 1 to " + FORMAT_VERSION_BOUND);
        }
        if (reportingEntityReference.length() > 64 || !REFERENCE.matcher(reportingEntityReference).matches()) {
            throw new IllegalArgumentException("the reporting entity's reference is an opaque reference of 1 to 64");
        }
        accounts = distinct(accounts, ACCOUNTS_BOUND, "accounts");
        transactions = distinct(transactions, TRANSACTIONS_BOUND, "transactions");
        attachments = distinct(attachments, ATTACHMENTS_BOUND, "attachments");
        Objects.requireNonNull(grounds, "grounds");
        if (grounds.size() > GROUNDS_BOUND) {
            throw new IllegalArgumentException("a report names at most " + GROUNDS_BOUND + " grounds");
        }
        for (String ground : grounds) {
            if (ground == null || !CODE.matcher(ground).matches()) {
                throw new IllegalArgumentException("a ground is a closed code");
            }
        }
        if (new LinkedHashSet<>(grounds).size() != grounds.size()) {
            throw new IllegalArgumentException("a ground is named once");
        }
        grounds = List.copyOf(grounds);
        narrated(narrative);
    }

    /**
     * {@code narrative}, screened - the approved text of the grounds.
     *
     * @throws RiskReasons.ReasonRequired a missing, blank or unbounded narrative, or one holding a card-number or
     *     bank-account shape
     */
    public static String narrated(String narrative) {
        if (narrative == null || narrative.isBlank() || narrative.length() > NARRATIVE_BOUND) {
            throw new RiskReasons.ReasonRequired("a report's narrative of 1 to " + NARRATIVE_BOUND + " characters is"
                    + " required");
        }
        if (InstrumentShapes.holdsAny(narrative)) {
            throw new RiskReasons.ReasonRequired("a report's narrative must not hold a card-number or bank-account"
                    + " shape");
        }
        return narrative;
    }

    private static List<UUID> distinct(List<UUID> references, int bound, String what) {
        Objects.requireNonNull(references, what);
        if (references.size() > bound) {
            throw new IllegalArgumentException("a report names at most " + bound + " " + what);
        }
        for (UUID reference : references) {
            Objects.requireNonNull(reference, "a reference among the " + what);
        }
        if (new LinkedHashSet<>(references).size() != references.size()) {
            throw new IllegalArgumentException("each of the " + what + " is named once");
        }
        return List.copyOf(references);
    }

    /** Codes and counts only - never the subject, a reference or the narrative ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "ReportContent[" + jurisdiction + " v" + formatVersion + ", accounts=" + accounts.size()
                + ", transactions=" + transactions.size() + ", grounds=" + grounds.size() + ", attachments="
                + attachments.size() + "]";
    }
}
