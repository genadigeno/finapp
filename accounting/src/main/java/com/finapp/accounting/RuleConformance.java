package com.finapp.accounting;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
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
 *       adjustment with no proposal) folded under {@link #UNNAMED}, and an identifier-like segment of a namespace - a
 *       UUID, or a segment of six or more characters holding a digit - folded to {@link #ID_PLACEHOLDER} (the
 *       review's M-12), so a stray key per entry cannot flood the register.
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

    /** What an identifier-like segment of an unregistered namespace is folded to. */
    public static final String ID_PLACEHOLDER = "{id}";

    /** A UUID anywhere in a namespace. */
    private static final java.util.regex.Pattern UUID_LIKE = java.util.regex.Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** A segment (between {@code .}, {@code -} and the colon) of six or more characters holding a digit. */
    private static final java.util.regex.Pattern ID_SEGMENT = java.util.regex.Pattern.compile(
            "(?<=^|[.-])(?=[a-z0-9]*[0-9])[a-z0-9]{6,}(?=[.-]|:$)");

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

    /**
     * A fresh tally for one run - the subjects {@code held} by a break compared with what a person decided on: an {@code OPEN} one or a
     * decided one unchanged counted by that break's status; a decided one that differs raised under its next generation and named
     * ({@link Tally#changed()}); new ones kept up to {@code cap}.
     */
    public Tally tally(Map<String, AccountingBreakStore.Held> held, int cap) {
        return new Tally(held, cap);
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
                    unregisteredSubject(entry), "type=" + entry.type() + ", namespace=" + folded(namespace)
                            + ", entries=" + entry.id() + " (" + entry.postingDate() + ")", judgedBy()));
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
     * namespaces folded with their counts and first entries. A subject a break already holds is counted by its status unless a person
     * decided on it and it now differs, which is a new raise at the next generation; past {@code cap} raises the tally only counts ({@link
     * #capped()}). Per run, never shared.
     */
    public final class Tally {

        private final Map<String, AccountingBreakStore.Held> held;
        private final int cap;
        private final Map<String, BreakFinding.Raise> kept = new LinkedHashMap<>();
        private final Map<String, Unregistered> namespaces = new LinkedHashMap<>();
        private final Map<String, BreakStatus> knownSeen = new TreeMap<>();
        private final Map<String, Unregistered> heldNamespaces = new LinkedHashMap<>();
        private long entries;
        private boolean capped;

        private Tally(Map<String, AccountingBreakStore.Held> held, int cap) {
            this.held = Map.copyOf(Objects.requireNonNull(held, "held"));
            if (cap < 1) {
                throw new IllegalArgumentException("a tally keeps at least one finding");
            }
            this.cap = cap;
        }

        /** One more entry judged. */
        public void add(JournalShapes.EntryShape entry) {
            entries++;
            for (BreakFinding finding : judge(entry)) {
                AccountingBreakStore.Held holder = held.get(finding.subject());
                if (holder != null) {
                    if (finding.type() == BreakType.UNREGISTERED_POSTING) {
                        // Its evidence is folded over the month, so it is compared when the walk has ended.
                        heldNamespaces.computeIfAbsent(finding.subject(),
                                subject -> new Unregistered(entry.type(), folded(namespaceOf(entry)))).add(entry);
                    } else if (!holder.changedBy(finding)) {
                        knownSeen.put(finding.subject(), holder.status());
                    } else if (!kept.containsKey(finding.subject()) && room()) {
                        kept.put(finding.subject(), new BreakFinding.Raise(finding, holder.nextGeneration()));
                    }
                    continue;
                }
                if (finding.type() == BreakType.UNREGISTERED_POSTING) {
                    Unregistered tallied = namespaces.get(finding.subject());
                    if (tallied == null) {
                        if (!room()) {
                            continue;
                        }
                        tallied = new Unregistered(entry.type(), folded(namespaceOf(entry)));
                        namespaces.put(finding.subject(), tallied);
                    }
                    tallied.add(entry);
                    continue;
                }
                if (!kept.containsKey(finding.subject()) && room()) {
                    kept.put(finding.subject(), new BreakFinding.Raise(finding, 1));
                }
            }
        }

        /** Every finding to raise, in the order first seen - each unregistered namespace once, with its count - and its generation. */
        public List<BreakFinding.Raise> raises() {
            List<BreakFinding.Raise> raises = new ArrayList<>(kept.values());
            namespaces.forEach((subject, folded) -> raises.add(new BreakFinding.Raise(unregistered(subject, folded), 1)));
            heldNamespaces.forEach((subject, folded) -> {
                BreakFinding finding = unregistered(subject, folded);
                if (held.get(subject).changedBy(finding)) {
                    raises.add(new BreakFinding.Raise(finding, held.get(subject).nextGeneration()));
                }
            });
            return raises;
        }

        /** Every finding to raise - {@link #raises()} without its generations. */
        public List<BreakFinding> findings() {
            return raises().stream().map(BreakFinding.Raise::finding).toList();
        }

        /** The subjects raised because they differ from what a person decided on - sorted. */
        public List<String> changed() {
            Set<String> changed = new TreeSet<>();
            raises().stream().filter(raise -> raise.generation() > 1).forEach(raise -> changed.add(raise.finding().subject()));
            return List.copyOf(changed);
        }

        private BreakFinding unregistered(String subject, Unregistered folded) {
            return new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.UNREGISTERED_POSTING, subject, folded.evidence(),
                    judgedBy());
        }

        /** Subjects found that a break already held and that are not raised again, counted per that break's status. */
        public Map<BreakStatus, Integer> known() {
            Map<BreakStatus, Integer> known = new java.util.EnumMap<>(BreakStatus.class);
            knownSeen.values().forEach(status -> known.merge(status, 1, Integer::sum));
            heldNamespaces.forEach((subject, folded) -> {
                if (!held.get(subject).changedBy(unregistered(subject, folded))) {
                    known.merge(held.get(subject).status(), 1, Integer::sum);
                }
            });
            return known;
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

    /** {@code namespace:<TYPE> <namespace>} - one subject per type and (folded) namespace. */
    static String unregisteredSubject(JournalShapes.EntryShape entry) {
        return BreakFinding.cut("namespace:" + entry.type() + " " + folded(namespaceOf(entry)), BreakFinding.MAX_SUBJECT);
    }

    /**
     * An unregistered namespace with its identifier-like parts folded to {@link #ID_PLACEHOLDER} - a UUID, or a segment
     * of six or more characters holding a digit - so keys that carry an id in their namespace position file under one
     * subject (the review's M-12). A registered namespace never reaches here; an origin has no such segment.
     */
    static String folded(String namespace) {
        return ID_SEGMENT.matcher(UUID_LIKE.matcher(namespace).replaceAll(ID_PLACEHOLDER)).replaceAll(ID_PLACEHOLDER);
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

    /**
     * One unregistered namespace folded over a run: how many entries, and the first few by {@code (posting date, id)} - whatever order
     * they were walked in, so the evidence a person decided on and the finding compared with it are the same text for the same entries
     * (ADR-0117's rule, `P14-TSK-012`).
     */
    private static final class Unregistered {
        private static final java.util.Comparator<JournalShapes.EntryShape> BY_DATE_THEN_ID =
                java.util.Comparator.comparing(JournalShapes.EntryShape::postingDate).thenComparing(JournalShapes.EntryShape::id,
                        JournalReads.LineCursor::compareIds);

        private final EntryType type;
        private final String namespace;
        private final java.util.TreeSet<JournalShapes.EntryShape> first = new java.util.TreeSet<>(BY_DATE_THEN_ID);
        private long count;
        private Optional<java.time.LocalDate> from = Optional.empty();
        private Optional<java.time.LocalDate> to = Optional.empty();

        Unregistered(EntryType type, String namespace) {
            this.type = type;
            this.namespace = namespace;
        }

        void add(JournalShapes.EntryShape entry) {
            count++;
            first.add(entry);
            if (first.size() > NAMED_ENTRIES) {
                first.pollLast();
            }
            from = Optional.of(from.filter(day -> day.isBefore(entry.postingDate())).orElse(entry.postingDate()));
            to = Optional.of(to.filter(day -> day.isAfter(entry.postingDate())).orElse(entry.postingDate()));
        }

        String evidence() {
            return "type=" + type + ", namespace=" + namespace + ", entries=" + count + ", postingDates=" + from.orElseThrow()
                    + ".." + to.orElseThrow() + ", first=" + first.stream().map(entry -> entry.id().toString()).toList();
        }
    }
}
