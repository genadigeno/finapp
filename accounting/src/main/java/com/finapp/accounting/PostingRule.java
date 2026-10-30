package com.finapp.accounting;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One registered posting rule (`P14-TSK-002`; ADR-0117 decision 1; {@code PHASE_14_PLAN.md} section 13.2): how one kind
 * of economic event posts, read from the code that posts it - never a treatment of accounting's own. Several rules may
 * share a type and namespace when one key family carries several events ({@code payment-capture:} a top-up or a
 * merchant-bound capture; {@code lending.waiver:} a fee refund or an interest waiver); an entry conforms when it matches
 * one rule in force for its type and namespace at its posting date ({@code INV-ACC-12}; the check is `P14-TSK-010`'s).
 *
 * @param id the rule's stable identity across versions ({@code LND-2}, {@code BNPL-F5}, {@code CARD-CAPTURE-MERCHANT}) -
 *     the source catalogue's own number where it has one (ADR-0096 section 2, ADR-0103)
 * @param version the rule's version, from 1; a changed rule is a new version, never an edit of the old one
 * @param effectiveFrom the first posting date the version judges
 * @param type the ledger door the rule writes through
 * @param namespace for {@link EntryType#POSTING} and {@link EntryType#REVERSAL} the command key up to and including its
 *     first {@code ':'}; for {@link EntryType#ADJUSTMENT} the proposal's origin
 * @param sourceModule the module whose transaction posts it
 * @param posters the classes that construct its command, by simple name - {@code PostingRuleRegisterTest} holds them to
 *     the source both ways
 * @param economicEvent what happened, in the domain's words
 * @param authoritativeReference the source record the entry answers to ({@code INV-LED-05}: every posting attributable)
 * @param dating where the posting date comes from
 * @param lines the line shape
 * @param catalogue the document the shape is read against
 */
public record PostingRule(
        String id,
        int version,
        LocalDate effectiveFrom,
        EntryType type,
        String namespace,
        String sourceModule,
        List<String> posters,
        String economicEvent,
        String authoritativeReference,
        DatingClass dating,
        List<LineShape> lines,
        String catalogue) {

    /** A posting key's namespace: lower-case segments joined by {@code .} or {@code -}, ending in {@code ':'}. */
    public static final Pattern KEY_NAMESPACE = Pattern.compile("[a-z][a-z0-9]*([.-][a-z0-9]+)*:");

    /** An adjustment origin: the ledger's {@code AdjustmentOrigin} name. */
    public static final Pattern ORIGIN = Pattern.compile("[A-Z][A-Z_]*[A-Z]");

    public PostingRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(sourceModule, "sourceModule");
        posters = List.copyOf(Objects.requireNonNull(posters, "posters"));
        Objects.requireNonNull(economicEvent, "economicEvent");
        Objects.requireNonNull(authoritativeReference, "authoritativeReference");
        Objects.requireNonNull(dating, "dating");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        Objects.requireNonNull(catalogue, "catalogue");
        if (!id.matches("[A-Z][A-Z0-9]*(-[A-Za-z0-9]+)*")) {
            throw new IllegalArgumentException("a rule id is an upper-case code: " + id);
        }
        if (version < 1) {
            throw new IllegalArgumentException(id + ": a version counts from 1");
        }
        Pattern form = type.keyedByNamespace() ? KEY_NAMESPACE : ORIGIN;
        if (!form.matcher(namespace).matches()) {
            throw new IllegalArgumentException(id + ": a " + type + " rule's namespace must match " + form + ": "
                    + namespace);
        }
        if (posters.isEmpty() || posters.stream().anyMatch(poster -> !poster.matches("[A-Z][A-Za-z0-9]*"))) {
            throw new IllegalArgumentException(id + ": a rule names its posters by class: " + posters);
        }
        if (economicEvent.isBlank() || authoritativeReference.isBlank() || catalogue.isBlank()
                || sourceModule.isBlank()) {
            throw new IllegalArgumentException(id + ": a rule states its source, event, reference and catalogue");
        }
        // A balanced entry has a debit and a credit: a shape no line of which may take one side is no entry at all.
        if (lines.stream().noneMatch(line -> line.side().admitsDebit())
                || lines.stream().noneMatch(line -> line.side().admitsCredit())) {
            throw new IllegalArgumentException(id + ": a shape admits a debit and a credit");
        }
    }

    /** Every purpose any line of the shape may name, in first-seen order. */
    public List<String> purposes() {
        return lines.stream().flatMap(line -> line.purposes().stream()).distinct().toList();
    }

    /** True when the rule may date an entry before today ({@link DatingClass#mayBackDate()}). */
    public boolean mayBackDate() {
        return dating.mayBackDate();
    }
}
