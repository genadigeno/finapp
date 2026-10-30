package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.DatingClass;
import com.finapp.accounting.EntryType;
import com.finapp.accounting.LineShape;
import com.finapp.accounting.PostingRule;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.Side;
import com.finapp.ledger.AccountPurpose;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The posting rule register against the code that posts (`P14-TSK-002`; ADR-0117 decision 1; {@code INV-ACC-12}'s
 * static half, {@code INV-LED-05}) - in {@code app}, the one module that sees the register, the ledger's vocabulary and
 * every poster: <strong>an unregistered posting-key prefix fails the build</strong> (the {@code *HasOnePosterTest} and
 * {@code PostingKeyNamespaceRegisterTest} precedents).
 *
 * <ul>
 *   <li><strong>Prefixes, both ways.</strong> Every posting-key prefix a production source declares - a
 *       {@code *_KEY_PREFIX}, {@code POSTING_PREFIX} or {@code *_KEY} constant, a {@code postingKey} local, or the literal
 *       at a {@code new PostingCommand(} or {@code new ReversalCommand(} within the command's next eight code lines -
 *       is a registered namespace, or is named in {@link #NOT_A_POSTING_NAMESPACE} with why nothing posts under it; and
 *       every registered key namespace is still declared somewhere, so the register carries no dead row.
 *   <li><strong>Posters, both ways.</strong> Every production class that constructs a ledger command - a
 *       {@code PostingCommand}, {@code ReversalCommand}, {@code AdjustmentCommand} or {@code OwnedAdjustmentCommand} -
 *       is a registered rule's poster, and every registered poster constructs the command of its rule's type.
 *   <li><strong>Purposes.</strong> Every purpose a rule names is an {@link AccountPurpose}; the manual adjustment admits
 *       exactly the purposes open to free adjustment; every clearing a settlement source discharges is a reconciled
 *       position.
 *   <li><strong>The back-dating posters are named</strong>: {@code ECONOMIC_DATE} is lending's accrual
 *       ({@code LoanServicing}) and the manual adjustment ({@code LedgerAdjustments}), no other.
 * </ul>
 *
 * <p>A source scan, not a classpath rule: ArchUnit cannot read a constant's value, and the namespace is a value. Each
 * check is proven able to fail in-suite by planted sources and a planted rule.
 */
@Tag("architecture")
@DisplayName("the posting rule register knows every namespace and poster the code posts with (P14-TSK-002)")
class PostingRuleRegisterTest {

    private static final PostingRuleRegister REGISTER = PostingRuleRegister.CURRENT;

    /** A constant or local that carries a posting key's prefix. */
    private static final Pattern DECLARED_PREFIX = Pattern.compile(
            "(?:KEY_PREFIX|POSTING_PREFIX|_KEY|\\bpostingKey)\\s*=\\s*\"([a-z][a-z0-9.-]*:)\"");

    /** The literal a command key is built from: a prefix concatenated with the operation's identity. */
    private static final Pattern INLINE_PREFIX = Pattern.compile("\"([a-z][a-z0-9.-]*:)\"\\s*\\+");

    /** A ledger command's construction - the posting site. */
    private static final Pattern COMMAND = Pattern.compile(
            "new (PostingCommand|ReversalCommand|AdjustmentCommand|OwnedAdjustmentCommand)\\(");

    /** How far below the construction the key literal may sit (comments skipped) - the capture's sits five below. */
    private static final int KEY_WINDOW = 8;

    /**
     * Prefixes with a posting key's shape that no entry is written under - each one a decision, with its reason. The
     * register describes what posts; these are named so the scan's net stays wide.
     */
    private static final Map<String, String> NOT_A_POSTING_NAMESPACE = Map.of(
            "lending.capital-contribution:",
            "ReconciliationCapitalExpectations: a capital contribution's expectation family (ADR-0096 section 4) - the"
                    + " approval posts nothing; rule 0 posts under settlement-batch: when the statement recognises the cash",
            "lending.accrual-correction:", "LoanServicing: reserved for a correction run never built; nothing posts",
            "bnpl.accrual-correction:", "BnplAccruals: reserved for a correction run never built (LEDGER_MODEL.md 11)",
            "risk.action:", "RiskActions: risk's command idempotency scope - risk has no ledger edge and posts nothing",
            "risk.rescreen:", "PartyRescreening: risk's rescreening idempotency scope - nothing posts");

    // ------------------------------------------------------------------ prefixes

    @Test
    @DisplayName("every posting-key prefix the source declares is registered or named non-posting; every registered"
            + " key namespace is declared")
    void theRegisterAndTheSourceAgreeOnPrefixes() {
        Map<String, String> sources = mainSources();
        TreeMap<String, String> declared = declaredPrefixes(sources);
        assertThat(declared).as("the scan must find the namespaces the platform posts under, or it guards nothing")
                .hasSizeGreaterThan(45);

        assertThat(unregistered(declared, REGISTER))
                .as("a posting-key prefix the register does not know is an entry nobody can judge (INV-ACC-12): register"
                        + " its rule in PostingRuleCatalogue, or name it in NOT_A_POSTING_NAMESPACE with why nothing"
                        + " posts under it")
                .isEmpty();

        Set<String> registered = keyNamespaces(REGISTER);
        TreeSet<String> dead = new TreeSet<>(registered);
        dead.removeAll(declared.keySet());
        assertThat(dead).as("a registered namespace no production source declares is a dead row that reads as coverage")
                .isEmpty();

        TreeSet<String> misfiled = new TreeSet<>(NOT_A_POSTING_NAMESPACE.keySet());
        misfiled.retainAll(registered);
        assertThat(misfiled).as("a prefix is a registered namespace or a named non-posting one, never both").isEmpty();
        TreeSet<String> staleExemption = new TreeSet<>(NOT_A_POSTING_NAMESPACE.keySet());
        staleExemption.removeAll(declared.keySet());
        assertThat(staleExemption).as("an exemption naming nothing the source declares silently stops applying")
                .isEmpty();
    }

    // ------------------------------------------------------------------ posters

    @Test
    @DisplayName("every class constructing a ledger command is a registered poster, and every registered poster"
            + " constructs its rule's command")
    void theRegisterAndTheSourceAgreeOnPosters() {
        Map<String, String> sources = mainSources();
        Map<String, Set<String>> constructed = constructions(sources);
        assertThat(constructed).as("the scan must see the posting sites").hasSizeGreaterThan(35);

        assertThat(unregisteredPosters(constructed, REGISTER))
                .as("a class constructing a ledger command that no rule names posts something the register cannot"
                        + " explain: register its rule")
                .isEmpty();

        for (PostingRule rule : REGISTER.rules()) {
            for (String poster : rule.posters()) {
                assertThat(constructed).as("%s's poster %s constructs a ledger command", rule.id(), poster)
                        .containsKey(poster);
                assertThat(constructed.get(poster)).as("%s's poster %s constructs a %s command", rule.id(), poster,
                        rule.type()).containsAnyElementsOf(commandsOf(rule.type()));
            }
        }
    }

    // ------------------------------------------------------------------ purposes

    @Test
    @DisplayName("every registered purpose is an AccountPurpose; MANUAL admits exactly the purposes open to free"
            + " adjustment; every source's clearing is a reconciled position")
    void everyPurposeIsTheLedgers() {
        assertThat(unknownPurposes(REGISTER.rules()))
                .as("a purpose the ledger does not have names no account an entry could ever post to").isEmpty();

        Set<String> open = EnumSet.complementOf(EnumSet.copyOf(AccountPurpose.closedToFreeAdjustments())).stream()
                .map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        PostingRule manual = REGISTER.inForce(EntryType.ADJUSTMENT, "MANUAL", java.time.LocalDate.of(2026, 10, 30)).get(0);
        assertThat(manual.lines()).allSatisfy(line ->
                assertThat(new TreeSet<>(line.purposes())).as("a manual line names a purpose the ledger leaves open"
                        + " (V015): every one, and no closed one").isEqualTo(open));

        Set<String> reconciled = AccountPurpose.reconciledPositions().stream().map(Enum::name)
                .collect(Collectors.toSet());
        PostingRule report = REGISTER.rule("SETTLEMENT-REPORT", 1).orElseThrow();
        assertThat(report.purposes()).as("hop 1 posts only reconciled positions").allMatch(reconciled::contains);
        PostingRule statement = REGISTER.rule("SETTLEMENT-STATEMENT", 1).orElseThrow();
        assertThat(statement.purposes()).as("hop 2 posts only reconciled positions").allMatch(reconciled::contains);
    }

    // ------------------------------------------------------------------ dating

    @Test
    @DisplayName("the back-dating posters are named: ECONOMIC_DATE is LoanServicing's accrual and LedgerAdjustments'"
            + " manual adjustment, no other; every rule carries a dating class")
    void theBackDatingPostersAreNamed() {
        Map<String, List<String>> economic = new TreeMap<>();
        REGISTER.rules().stream().filter(rule -> rule.dating() == DatingClass.ECONOMIC_DATE)
                .forEach(rule -> economic.put(rule.id(), rule.posters()));
        assertThat(economic).containsExactly(
                Map.entry("ADJ-MANUAL", List.of("LedgerAdjustments")),
                Map.entry("LND-2", List.of("LoanServicing")));
        assertThat(REGISTER.rules()).allSatisfy(rule -> assertThat(rule.dating()).isNotNull());
        assertThat(REGISTER.rules().stream().filter(PostingRule::mayBackDate).map(PostingRule::id))
                .containsExactlyInAnyOrder("ADJ-MANUAL", "LND-2", "SETTLEMENT-REPORT", "SETTLEMENT-STATEMENT",
                        "MERCHANT-PAYOUT-RETURN", "RECON-SUSPENSE", "RECON-WRITE-OFF", "RECON-TRANSFER", "RECON-GAIN",
                        "FX-TRADE", "FX-COVER");
    }

    // ------------------------------------------------------------------ the guards proven able to fail

    @Test
    @DisplayName("a planted unregistered prefix is caught - as a constant, inline at a posting and at a reversal - and a"
            + " commented one is not; its class is caught as an unregistered poster")
    void aPlantedUnregisteredPrefixIsCaught() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("x/payments/src/main/java/com/finapp/payments/RogueFee.java", """
                static final String POSTING_KEY_PREFIX = "rogue.constant:";
                PostingResult posted = postings.post(unitOfWork, new PostingCommand(
                        // a comment line, skipped
                        "rogue.inline:" + id.value(),
                        today, today, reference, lines));
                """);
        planted.put("x/fx/src/main/java/com/finapp/fx/RogueReversal.java", """
                ledgerReversals.reverse(unitOfWork, new ReversalCommand(
                        "rogue.reversal:" + id, original, today, today, reference, mirror));
                """);
        planted.put("x/app/src/main/java/com/finapp/app/Prose.java", """
                // String key = "rogue.comment:" + id; new PostingCommand( in prose
                """);
        planted.put("x/transfers/src/main/java/com/finapp/transfers/TransferExecution.java", """
                new PostingCommand(
                        "transfer:" + initiated.id().value(), today, today, reference, lines)
                """);

        assertThat(unregistered(declaredPrefixes(planted), REGISTER).keySet())
                .containsExactly("rogue.constant:", "rogue.inline:", "rogue.reversal:");
        assertThat(unregisteredPosters(constructions(planted), REGISTER))
                .containsExactly("RogueFee", "RogueReversal");
    }

    @Test
    @DisplayName("a planted unknown purpose is caught; the register as built names none")
    void aPlantedUnknownPurposeIsCaught() {
        PostingRule planted = new PostingRule("PLANTED", 1, PostingRuleRegister.ORIGIN, EntryType.POSTING, "planted:",
                "payments", List.of("Planted"), "a planted event", "a planted record", DatingClass.TODAY_CLOCK,
                List.of(LineShape.required(Side.DEBIT, "CUSTOMER_WALLET"),
                        LineShape.required(Side.CREDIT, "CUSTOMER_WALLETS", "MERCHANT_PAYABLE")),
                "planted");
        assertThat(unknownPurposes(List.of(planted))).containsExactly("PLANTED: CUSTOMER_WALLETS");
        assertThat(unknownPurposes(REGISTER.rules())).isEmpty();
    }

    @Test
    @DisplayName("the scan reads what a reviewer reads: a constant, a local, and the literal at a posting and a reversal"
            + " even five comment lines below the construction")
    void theScanIsNotVacuous() {
        Map<String, String> sample = Map.of("x/sample/src/main/java/Sample.java", """
                static final String POSTING_KEY_PREFIX = "sample.posting:";
                static final String FEE_KEY = "sample.fee:";
                String postingKey = "sample.local:" + id;
                PostingResult posted = postings.post(unitOfWork, new PostingCommand(
                        // one
                        // two
                        // three
                        // four
                        "sample.inline:" + attemptId.value(),
                        today, today, reference, lines));
                reversals.reverse(unitOfWork, new ReversalCommand("sample.reversal:" + id, entry, today, today, r, l));
                String unrelated = "not-a-key";
                """);
        assertThat(declaredPrefixes(sample).keySet()).containsExactly(
                "sample.fee:", "sample.inline:", "sample.local:", "sample.posting:", "sample.reversal:");
        assertThat(constructions(sample)).containsOnlyKeys("Sample");
        assertThat(constructions(sample).get("Sample")).containsExactlyInAnyOrder("PostingCommand", "ReversalCommand");
    }

    // ------------------------------------------------------------------ the checks

    private static TreeMap<String, String> unregistered(Map<String, String> declared, PostingRuleRegister register) {
        Set<String> registered = keyNamespaces(register);
        TreeMap<String, String> unknown = new TreeMap<>();
        declared.forEach((namespace, where) -> {
            if (!registered.contains(namespace) && !NOT_A_POSTING_NAMESPACE.containsKey(namespace)) {
                unknown.put(namespace, where);
            }
        });
        return unknown;
    }

    private static TreeSet<String> unregisteredPosters(Map<String, Set<String>> constructed,
            PostingRuleRegister register) {
        Set<String> posters = register.rules().stream().flatMap(rule -> rule.posters().stream())
                .collect(Collectors.toSet());
        TreeSet<String> unknown = new TreeSet<>(constructed.keySet());
        unknown.removeAll(posters);
        return unknown;
    }

    private static List<String> unknownPurposes(List<PostingRule> rules) {
        Set<String> purposes = Arrays.stream(AccountPurpose.values()).map(Enum::name).collect(Collectors.toSet());
        return rules.stream().flatMap(rule -> rule.purposes().stream().filter(purpose -> !purposes.contains(purpose))
                .map(purpose -> rule.id() + ": " + purpose)).toList();
    }

    private static Set<String> keyNamespaces(PostingRuleRegister register) {
        Set<String> namespaces = new TreeSet<>(register.namespaces(EntryType.POSTING));
        namespaces.addAll(register.namespaces(EntryType.REVERSAL));
        return namespaces;
    }

    private static Set<String> commandsOf(EntryType type) {
        return switch (type) {
            case POSTING -> Set.of("PostingCommand");
            case REVERSAL -> Set.of("ReversalCommand");
            case ADJUSTMENT -> Set.of("AdjustmentCommand", "OwnedAdjustmentCommand");
        };
    }

    // ------------------------------------------------------------------ the scan

    /** Prefix -> where it was first declared. */
    private static TreeMap<String, String> declaredPrefixes(Map<String, String> sources) {
        TreeMap<String, String> found = new TreeMap<>();
        sources.forEach((path, text) -> {
            List<String> lines = text.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (comment(line)) {
                    continue;
                }
                Matcher declared = DECLARED_PREFIX.matcher(line);
                while (declared.find()) {
                    found.putIfAbsent(declared.group(1), path + ":" + (i + 1));
                }
                Matcher command = COMMAND.matcher(line);
                if (command.find() && !command.group(1).endsWith("AdjustmentCommand")) {
                    String rest = line.substring(command.end());
                    int seen = 0;
                    for (int j = i; j < lines.size() && seen <= KEY_WINDOW; j++) {
                        String candidate = j == i ? rest : lines.get(j);
                        if (j > i && comment(candidate)) {
                            continue;
                        }
                        seen++;
                        Matcher inline = INLINE_PREFIX.matcher(candidate);
                        if (inline.find()) {
                            found.putIfAbsent(inline.group(1), path + ":" + (j + 1));
                            break;
                        }
                    }
                }
            }
        });
        return found;
    }

    /** Class simple name -> the ledger commands it constructs. */
    private static Map<String, Set<String>> constructions(Map<String, String> sources) {
        Map<String, Set<String>> found = new TreeMap<>();
        sources.forEach((path, text) -> text.lines().filter(line -> !comment(line)).forEach(line -> {
            Matcher command = COMMAND.matcher(line);
            while (command.find()) {
                String file = Path.of(path).getFileName().toString();
                found.computeIfAbsent(file.substring(0, file.length() - ".java".length()), k -> new TreeSet<>())
                        .add(command.group(1));
            }
        }));
        return found;
    }

    private static boolean comment(String line) {
        String stripped = line.stripLeading();
        return stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*");
    }

    /**
     * Every module's production source except {@code accounting}'s - the register's own text is the expectation, not a
     * poster - keyed by its path from the repository root.
     */
    private static Map<String, String> mainSources() {
        Path root = repositoryRoot();
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src").resolve("main").resolve("java");
                if (!Files.isDirectory(main) || module.getFileName().toString().equals("accounting")) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    for (Path source : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                        sources.put(root.relativize(source).toString().replace('\\', '/'),
                                Files.readString(source, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("the scan must see the codebase").hasSizeGreaterThan(1000);
        return sources;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("the repository root (settings.gradle.kts) is not above the working directory");
        }
        return current;
    }
}
