package com.finapp.accounting;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Rule conformance - section 13.3's second level (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-12}'s runtime half,
 * {@code PostingRuleRegisterTest} being its static half): every journal entry of a period judged against the posting rule
 * register as it stood at the entry's posting date. Pure: it reads nothing and writes nothing; the entries arrive through
 * {@link JournalShapes} on a read-only snapshot and the findings leave as {@link BreakFinding}s.
 *
 * <ul>
 *   <li><strong>{@link BreakType#UNREGISTERED_POSTING}</strong> - no rule of the entry's type is in force for its
 *       namespace (or adjustment origin) at its posting date. One finding per {@code (type, namespace)} - a registration
 *       gap is a namespace's fact: subject {@code namespace:<TYPE> <namespace>}, a key with no {@code ':'} (or an
 *       adjustment with no proposal) folded under {@link #UNNAMED}, so a stray key per entry cannot flood the register.
 *   <li><strong>{@link BreakType#SHAPE_MISMATCH}</strong> - rules are in force and the entry matches none of them: a rule
 *       matches when <em>every</em> line is admitted by one of its shape lines (the purpose is one the shape line names
 *       and the side one it takes) and <em>every required</em> shape line is present. Classification, not cardinality or
 *       amount (ADR-0117's follow-up). One finding per entry: subject {@code shape:<entry>} - each nonconforming entry
 *       is its own correction.
 *   <li><strong>{@link BreakType#UNBALANCED_ENTRY}</strong> - a currency (at a scale) whose debits differ from its
 *       credits. Impossible by the ledger's deferred trigger ({@code INV-LED-01}) - and still checked, because a check
 *       that trusts what it checks is not one. One per entry: subject {@code unbalanced:<entry>}.
 * </ul>
 *
 * Evidence names the entry, its type, namespace, posting date, the rules tried and the lines' purposes and sides -
 * never an amount. Every finding is judged by {@code posting-rules:v<catalogue version>}.
 */
public final class RuleConformance {

    /** The namespace an entry with no namespace is filed under. */
    public static final String UNNAMED = "(none)";

    /** How many entry ids an unregistered namespace's evidence names. */
    static final int NAMED_ENTRIES = 5;

    private final PostingRuleRegister register;

    public RuleConformance(PostingRuleRegister register) {
        this.register = Objects.requireNonNull(register, "register");
    }

    /** The register version every finding is judged by. */
    public String judgedBy() {
        return "posting-rules:v" + register.catalogueVersion();
    }

    /** A fresh tally for one run - {@code known} subjects counted, never re-raised; new ones kept up to {@code cap}. */
    public Tally tally(Predicate<String> known, int cap) {
        return new Tally(known, cap);
    }

    /** True when {@code rule}'s shape admits {@code lines} - every line admitted, every required line present. */
    public static boolean matches(PostingRule rule, List<JournalShapes.ShapeLine> lines) {
        List<LineShape> shape = rule.lines();
        boolean[] present = new boolean[shape.size()];
        for (JournalShapes.ShapeLine line : lines) {
            boolean admitted = false;
            for (int i = 0; i < shape.size(); i++) {
                LineShape candidate = shape.get(i);
                if (candidate.purposes().contains(line.purpose()) && admits(candidate.side(), line.side())) {
                    present[i] = true;
                    admitted = true;
                }
            }
            if (!admitted) {
                return false;
            }
        }
        for (int i = 0; i < shape.size(); i++) {
            if (shape.get(i).required() && !present[i]) {
                return false;
            }
        }
        return true;
    }

    /** The currencies (at a scale) whose debits and credits differ - empty for a balanced entry. */
    public static List<String> unbalanced(List<JournalShapes.ShapeLine> lines) {
        Map<String, BigInteger> net = new TreeMap<>();
        for (JournalShapes.ShapeLine line : lines) {
            BigInteger signed = line.side() == Side.DEBIT ? line.minorUnits() : line.minorUnits().negate();
            net.merge(line.currency() + "@" + line.scale(), signed, BigInteger::add);
        }
        return net.entrySet().stream().filter(entry -> entry.getValue().signum() != 0).map(Map.Entry::getKey).toList();
    }

    /** One entry's findings, judged on their own. */
    List<BreakFinding> judge(JournalShapes.EntryShape entry) {
        List<BreakFinding> findings = new ArrayList<>();
        String namespace = namespaceOf(entry);
        List<PostingRule> inForce = entry.namespace().isPresent()
                ? register.inForce(entry.type(), namespace, entry.postingDate())
                : List.of();
        if (inForce.isEmpty()) {
            findings.add(new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.UNREGISTERED_POSTING,
                    unregisteredSubject(entry), "type=" + entry.type() + ", namespace=" + namespace + ", entries="
                            + entry.id() + " (" + entry.postingDate() + ")", judgedBy()));
        } else if (inForce.stream().noneMatch(rule -> matches(rule, entry.lines()))) {
            findings.add(new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.SHAPE_MISMATCH,
                    "shape:" + entry.id(), "entry=" + entry.id() + ", type=" + entry.type() + ", namespace=" + namespace
                            + ", postingDate=" + entry.postingDate() + ", rules=" + rules(inForce) + ", lines="
                            + lines(entry.lines()), judgedBy()));
        }
        List<String> unbalanced = unbalanced(entry.lines());
        if (!unbalanced.isEmpty()) {
            findings.add(new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.UNBALANCED_ENTRY,
                    "unbalanced:" + entry.id(), "entry=" + entry.id() + ", type=" + entry.type() + ", namespace="
                            + namespace + ", postingDate=" + entry.postingDate() + ", currencies=" + unbalanced,
                    judgedBy()));
        }
        return findings;
    }

    /**
     * One run's findings over a period's entries, entry by entry: the per-entry findings kept as found, the unregistered
     * namespaces folded with their counts and first entries. Known subjects are counted, never kept; past {@code cap} new
     * subjects the tally only counts ({@link #capped()}). Per run, never shared.
     */
    public final class Tally {

        private final Predicate<String> known;
        private final int cap;
        private final Map<String, BreakFinding> kept = new LinkedHashMap<>();
        private final Map<String, Unregistered> namespaces = new LinkedHashMap<>();
        private final TreeSet<String> knownSeen = new TreeSet<>();
        private long entries;
        private boolean capped;

        private Tally(Predicate<String> known, int cap) {
            this.known = Objects.requireNonNull(known, "known");
            if (cap < 1) {
                throw new IllegalArgumentException("a tally keeps at least one finding");
            }
            this.cap = cap;
        }

        /** One more entry judged. */
        public void add(JournalShapes.EntryShape entry) {
            entries++;
            for (BreakFinding finding : judge(entry)) {
                if (known.test(finding.subject())) {
                    knownSeen.add(finding.subject());
                    continue;
                }
                if (finding.type() == BreakType.UNREGISTERED_POSTING) {
                    Unregistered folded = namespaces.get(finding.subject());
                    if (folded == null) {
                        if (!room()) {
                            continue;
                        }
                        folded = new Unregistered(entry.type(), namespaceOf(entry));
                        namespaces.put(finding.subject(), folded);
                    }
                    folded.add(entry);
                    continue;
                }
                if (!kept.containsKey(finding.subject()) && room()) {
                    kept.put(finding.subject(), finding);
                }
            }
        }

        /** Every new finding, in the order first seen - each unregistered namespace once, with its count. */
        public List<BreakFinding> findings() {
            List<BreakFinding> findings = new ArrayList<>(kept.values());
            namespaces.forEach((subject, folded) -> findings.add(new BreakFinding(CloseCheckCode.RULE_CONFORMANCE,
                    BreakType.UNREGISTERED_POSTING, subject, folded.evidence(), judgedBy())));
            return findings;
        }

        /** Subjects found that were already known - counted, not re-raised. */
        public int known() {
            return knownSeen.size();
        }

        /** Entries judged. */
        public long entries() {
            return entries;
        }

        /** True when more new subjects were found than the run keeps. */
        public boolean capped() {
            return capped;
        }

        private boolean room() {
            if (kept.size() + namespaces.size() < cap) {
                return true;
            }
            capped = true;
            return false;
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** The namespace an entry is judged under - its own, or {@link #UNNAMED}. */
    static String namespaceOf(JournalShapes.EntryShape entry) {
        return entry.namespace().filter(RuleConformance::named).orElse(UNNAMED);
    }

    /** {@code namespace:<TYPE> <namespace>} - one subject per type and namespace. */
    static String unregisteredSubject(JournalShapes.EntryShape entry) {
        return BreakFinding.cut("namespace:" + entry.type() + " " + namespaceOf(entry), BreakFinding.MAX_SUBJECT);
    }

    /** A key namespace ends in {@code ':'} and an origin is an upper-case word; anything else is folded. */
    private static boolean named(String namespace) {
        return PostingRule.KEY_NAMESPACE.matcher(namespace).matches() || PostingRule.ORIGIN.matcher(namespace).matches();
    }

    private static boolean admits(Side shape, Side line) {
        return shape == Side.EITHER || shape == line;
    }

    private static String rules(List<PostingRule> rules) {
        return rules.stream().map(rule -> rule.id() + " v" + rule.version()).collect(Collectors.joining(", ", "[", "]"));
    }

    private static String lines(List<JournalShapes.ShapeLine> lines) {
        return lines.stream().map(line -> line.side() + " " + line.purpose()).collect(Collectors.joining(", ", "[", "]"));
    }

    /** One unregistered namespace folded over a run: how many entries, and the first few. */
    private static final class Unregistered {
        private final EntryType type;
        private final String namespace;
        private final List<String> first = new ArrayList<>();
        private long count;
        private Optional<java.time.LocalDate> from = Optional.empty();
        private Optional<java.time.LocalDate> to = Optional.empty();

        Unregistered(EntryType type, String namespace) {
            this.type = type;
            this.namespace = namespace;
        }

        void add(JournalShapes.EntryShape entry) {
            count++;
            if (first.size() < NAMED_ENTRIES) {
                first.add(entry.id().toString());
            }
            from = Optional.of(from.filter(day -> day.isBefore(entry.postingDate())).orElse(entry.postingDate()));
            to = Optional.of(to.filter(day -> day.isAfter(entry.postingDate())).orElse(entry.postingDate()));
        }

        String evidence() {
            return "type=" + type + ", namespace=" + namespace + ", entries=" + count + ", postingDates=" + from.orElseThrow()
                    + ".." + to.orElseThrow() + ", first=" + first;
        }
    }
}
