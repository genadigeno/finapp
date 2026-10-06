package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.PayoutSettlementExpectations;
import com.finapp.payments.SettlementExpectations;
import com.finapp.reconciliation.ExpectationKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE EXPECTATION-OPENER REGISTER (`P8-TSK-005`, ADR-0067 §9, `INV-SET-02`'s Verify line): the
 * claim "every externally settling completion opens its expectation" as a build fact, not a
 * sentence.
 *
 * <h2>What it enumerates</h2>
 *
 * <p>Every posting-key prefix production code declares: the string literals of the posting-key
 * shape ({@code lowercase-words:}) in each main source file that constructs a ledger posting
 * input ({@code new PostingCommand(} or {@code new ReversalCommand(}), comments stripped — the
 * `P1-TSK-021` lesson that a rule matching prose reports a control it does not have.
 *
 * <h2>How each prefix must be classified — exactly one of three</h2>
 *
 * <ul>
 *   <li><strong>OPENS</strong> named expectation kinds, each with the database test proving it:
 *       a real {@code @Test} method whose body calls
 *       {@link ClearingLineCopies#assertOpensItsClearingLinesCopy} with that
 *       {@code ExpectationKind} — the helper asserts the copy's amount, entry, account, sign,
 *       date and dating against the LEDGER;
 *   <li><strong>TOUCHES_NO_RECONCILED_POSITION</strong>, with the reason and a test proving
 *       nothing opens — {@link ClearingLineCopies#assertOpensNothing} naming the prefix, or
 *       {@link ClearingLineCopies#assertEveryClearingLineIsCopied} over a scope holding it;
 *   <li><strong>PHASE_8_RECORD</strong>: a record the completeness verifier knows by its own row
 *       (a recognition, a park, a resolution, a repudiation — ADR-0067 §9's third class). It
 *       copies no clearing line, so it names no kind HERE — what its transaction opens through
 *       reconciliation's own register (`P8-TSK-009`'s {@code REMITTANCE}) is evidence's promise,
 *       proven by its own suites, never by the copy helper. First row: {@code settlement-batch:}.
 * </ul>
 *
 * <p>A new prefix fails the build until it is classified; a row whose prefix no code declares is
 * stale and fails too (the `P1-TSK-015` rule — a permit naming nothing silently stops applying);
 * a row naming a missing test, or a test that proves nothing, fails; and every kind either port
 * can open must have exactly one proving row. The checks are applied to planted violations in
 * {@link #theRegisterRejectsItsViolations}, beside the real register.
 *
 * <p>Stated limits: a posting key assembled somewhere the scanner does not look — in a helper
 * class that constructs no posting input, or from fragments — escapes the enumeration. A generic
 * adjustment's key is the operator's own, not a declared prefix; reconciled positions are closed
 * to it by `P8-TSK-006` at both ranks. And the register proves the openers; the completeness
 * verifier (`P8-TSK-007`) is the every-writer backstop, raw SQL included.
 */
@DisplayName("the expectation-opener register (P8-TSK-005, ADR-0067 section 9, INV-SET-02)")
class ExpectationOpenerRegisterTest {

    /** The three classes of ADR-0067 §9. */
    enum Classification {
        OPENS,
        TOUCHES_NO_RECONCILED_POSITION,
        PHASE_8_RECORD
    }

    /** "Opens nothing" as a proof's claim. */
    static final String NOTHING = "NOTHING";

    /**
     * One test's claim about a prefix.
     *
     * @param opens an {@code ExpectationKind} name, or {@link #NOTHING}
     * @param test the test class, relative to {@code com.finapp.app}
     * @param method the {@code @Test} method proving it
     */
    record Proof(String opens, String test, String method) {}

    record Row(Classification classification, String reason, List<Proof> proofs) {}

    private static Row opens(String reason, Proof... proofs) {
        return new Row(Classification.OPENS, reason, List.of(proofs));
    }

    private static Row touchesNothing(String reason, Proof... proofs) {
        return new Row(Classification.TOUCHES_NO_RECONCILED_POSITION, reason, List.of(proofs));
    }

    private static Row phase8Record(String reason) {
        return new Row(Classification.PHASE_8_RECORD, reason, List.of());
    }

    private static Proof proof(String opens, String test, String method) {
        return new Proof(opens, test, method);
    }

    private static final String CARDS = "reconciliation.SettlementExpectationDatabaseTest";
    private static final String PAY_BY_BANK = "payments.PayByBankDatabaseTest";
    private static final String CHARGEBACKS = "payments.ChargebackAccountingDatabaseTest";
    private static final String BATTERY = "payments.DisputeBatteryDatabaseTest";
    private static final String STORM = "payments.MultiRailConservationStormDatabaseTest";

    /** THE REGISTER: every declared posting-key prefix, classified. */
    static final Map<String, Row> REGISTER = register();

    private static Map<String, Row> register() {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("payment-capture:",
                opens("a card capture's DR clearing line (P8-TSK-004)",
                        proof("CARD_CAPTURE", CARDS, "aCaptureOpensItsClearingLinesCopy")));
        rows.put("payment-refund:",
                opens("the refunded rail's DECLARED refund mode: a provider refund, a return"
                                + " payment; a book refund's counterpart is the payer's wallet",
                        proof("CARD_REFUND", CARDS, "aCardRefundOpensItsOutboundCopy"),
                        proof("PUSH_RETURN", PAY_BY_BANK, "theReturnChainHolds"),
                        proof(NOTHING, CARDS, "aBookMovementOpensNothing")));
        rows.put("payment-execution:",
                opens("a push pay-in's DR clearing line; a book payment's counterpart is the"
                                + " payer's wallet (SettlementModel.NONE)",
                        proof("PUSH_PAY_IN", PAY_BY_BANK, "theWalletChainHolds"),
                        proof(NOTHING, CARDS, "aBookMovementOpensNothing")));
        rows.put("unmatched-confirmation:",
                opens("a parking's DR clearing line (its suspense item is P8-TSK-020's)",
                        proof("UNMATCHED_CONFIRMATION", PAY_BY_BANK,
                                "anUnattributableConfirmationParksOnce")));
        rows.put("wallet-withdrawal:",
                opens("a withdrawal's CR clearing line",
                        proof("PUSH_WITHDRAWAL", "payments.WithdrawalDatabaseTest",
                                "theAcceptanceChainHolds")));
        rows.put("dispute-chargeback:",
                opens("the network's take: the chargeback's CR clearing line",
                        proof("CHARGEBACK", CHARGEBACKS, "tenFreshIdChargebacksPostOnce")));
        rows.put("dispute-won:",
                opens("the network's return: the win's DR clearing line",
                        proof("CHARGEBACK_REVERSAL", CHARGEBACKS,
                                "tenFreshIdChargebacksPostOnce")));
        rows.put("dispute-fee:",
                opens("the PSP's fee, netted from settlement: the fee's CR clearing line",
                        proof("DISPUTE_FEE", CHARGEBACKS, "tenFreshIdChargebacksPostOnce")));
        rows.put("merchant-payout:",
                opens("a completed payout's CR PAYOUT_CLEARING line, through merchant's port",
                        proof("MERCHANT_PAYOUT", "merchant.MerchantPayoutDatabaseTest",
                                "aPaidPayoutReleasesAndPosts")));
        rows.put("merchant-payout-return:",
                opens("a returned payout's DR PAYOUT_CLEARING line, through merchant's port with"
                                + " no key of its own - the operation-anchored rule reaches it"
                                + " (P8-TSK-019, ADR-0073)",
                        proof("PAYOUT_RETURN", "merchant.PayoutReturnDatabaseTest",
                                "theRoutineReturnIsAppliedOnceAndRematched")));
        rows.put("dispute-attribution:",
                touchesNothing("the counterparty against CHARGEBACK_RECOVERABLE",
                        proof(NOTHING, CHARGEBACKS, "tenFreshIdChargebacksPostOnce")));
        rows.put("dispute-restoration:",
                touchesNothing("CHARGEBACK_RECOVERABLE back to the counterparty",
                        proof(NOTHING, CHARGEBACKS, "tenFreshIdChargebacksPostOnce")));
        // Each "opens nothing" proof names a test that CERTAINLY posts the key (the gate's find:
        // these two first rested on the battery's scope, which no scenario forces to hold a
        // re-attribution - a proof that could be vacuous). The battery's scope reading stays
        // the load-level backstop for both.
        rows.put("dispute-loss:",
                touchesNothing("DISPUTE_COSTS against CHARGEBACK_RECOVERABLE",
                        proof(NOTHING, CHARGEBACKS, "aLossWritesOffOnlyTheExcess"),
                        proof(NOTHING, BATTERY, "theDisputeBatteryHoldsUnderLoad")));
        rows.put("dispute-reattribution:",
                touchesNothing("freed headroom between the counterparty, the recoverable and"
                                + " DISPUTE_COSTS",
                        proof(NOTHING, CHARGEBACKS, "aWinGivesASiblingsExcessBack"),
                        proof(NOTHING, BATTERY, "theDisputeBatteryHoldsUnderLoad")));
        rows.put("fx-trade:",
                touchesNothing("a wallet conversion (P9-TSK-009, ADR-0076): the customer's two wallets"
                                + " against FX_POSITION, FX_SPREAD_REVENUE and ROUNDING_RESIDUAL - none a"
                                + " reconciled position; the provider's cover opens the legs (fx-cover:)",
                        proof(NOTHING, "fx.FxConversionDatabaseTest", "theWorkedExamplesPostExactly")));
        rows.put("fx-cover:",
                opens("an executed cover (P9-TSK-012, ADR-0077 section 6): FX_POSITION closed onto the"
                                + " provider's OWN FX_PROVIDER_CLEARING - the sold currency's CR line and the"
                                + " bought currency's DR line, each copied into its leg's expectation through"
                                + " fx's FxSettlementExpectations port, keyed COVER_REF",
                        proof("FX_SELL_LEG", "fx.FxCoverDatabaseTest", "theCoverExecutesAtThePlan"),
                        proof("FX_BUY_LEG", "fx.FxCoverDatabaseTest", "theCoverExecutesAtThePlan")));
        rows.put("outbound-credit:",
                opens("a cross-border outbound credit's completion (P9-TSK-020, PHASE_9_PLAN.md section 12.4(g)):"
                                + " the instructed amount credited to the corridor's OWN CORRIDOR_CLEARING, copied"
                                + " into CROSSBORDER_PAYOUT keyed END_TO_END_REF and the provider's reference",
                        proof("CROSSBORDER_PAYOUT", "fx.OutboundCreditResolutionDatabaseTest",
                                "theCompletionPostsExactlyAndOpensItsExpectation")));
        rows.put("crossborder-return:",
                opens("a cross-border return applied from evidence (P9-TSK-023, PHASE_9_PLAN.md section 12.4(i)):"
                                + " the returned credit debited from the corridor's OWN CORRIDOR_CLEARING, copied into"
                                + " CROSSBORDER_RETURN, keyless - operation-anchored on the credit's CROSSBORDER_PAYOUT",
                        proof("CROSSBORDER_RETURN", "fx.CrossBorderReturnDatabaseTest",
                                "anExactReturnAppliesOnTheInquiryChannel")));
        rows.put("crossborder-return-fee:",
                touchesNothing("a resolved cross-border return's fee refund (P9-TSK-023, T-g): FEE_REVENUE to the"
                                + " customer's source wallet - neither a reconciled position; the park already moved"
                                + " the principal off CORRIDOR_CLEARING",
                        proof(NOTHING, "fx.CrossBorderReturnDatabaseTest", "aParkedReturnIsResolvedByAPerson")));
        rows.put("transfer:",
                touchesNothing("wallet to wallet and its reversal - FINAL_ON_POSTING, nothing"
                                + " external settles (the storm's scope asserted to hold"
                                + " transfer entries)",
                        proof(NOTHING, STORM, "conservationHoldsAcrossEveryRail")));
        rows.put("settlement-batch:",
                phase8Record("hop 1's fee recognition (ADR-0065, P8-TSK-009): the entry is the"
                        + " completeness verifier's own known row (acceptedRecognitionEntries),"
                        + " and the REMITTANCE its acceptance opens through reconciliation's"
                        + " register is evidence's promise, not a clearing line's copy - proven"
                        + " by BatchAcceptanceDatabaseTest and SettlementAcceptanceDatabaseTest"));
        rows.put("recon-suspense:",
                phase8Record("the park and its exact inverse (ADR-0070, P8-TSK-010): the"
                        + " entry is the completeness verifier's own known row"
                        + " (SuspenseReadings.knownEntries - park.journal_entry_id and every"
                        + " suspense_item.entry_id), and what it moves is owned by the break"
                        + " raised in the same transaction (INV-REC-09), never an expectation"
                        + " - proven by BreakAndSuspenseDatabaseTest and"
                        + " ReconciliationSuspenseDatabaseTest"));
        return rows;
    }

    // -----------------------------------------------------------------

    @Test
    @DisplayName("every declared posting-key prefix is classified, every row is live, every"
            + " proof is a real test calling the ledger-backed helper, and every kind either port"
            + " can open has its one proving row")
    void everyPostingKeyIsClassifiedAndProven() {
        Set<String> declared = declaredPostingKeyPrefixes();
        assertThat(declared)
                .as("the scan is not vacuous: it sees the capture's key and a dozen more")
                .contains("payment-capture:", "merchant-payout:", "dispute-chargeback:")
                .hasSizeGreaterThanOrEqualTo(12);
        assertThat(violations(declared, REGISTER, ExpectationOpenerRegisterTest::checkProof))
                .as("the expectation-opener register (ADR-0067 section 9)")
                .isEmpty();
    }

    @Test
    @DisplayName("the register rejects its violations: a planted clearing key, a stale row, a"
            + " missing test, a test proving nothing, and a port kind with no row")
    void theRegisterRejectsItsViolations() {
        Set<String> declared = declaredPostingKeyPrefixes();
        BiFunction<Proof, String, Optional<String>> real =
                ExpectationOpenerRegisterTest::checkProof;

        // A new posting key nobody classified - the planted probe the backlog names.
        Set<String> planted = new TreeSet<>(declared);
        planted.add("planted-clearing:");
        assertThat(violations(planted, REGISTER, real))
                .anySatisfy(violation -> assertThat(violation).contains("planted-clearing:"));

        // A row whose prefix no code declares any more.
        Map<String, Row> stale = new LinkedHashMap<>(REGISTER);
        stale.put("retired-key:", touchesNothing("gone",
                proof(NOTHING, STORM, "conservationHoldsAcrossEveryRail")));
        assertThat(violations(declared, stale, real))
                .anySatisfy(violation -> assertThat(violation).contains("retired-key:"));

        // A proof naming a test method that does not exist.
        Map<String, Row> missing = new LinkedHashMap<>(REGISTER);
        missing.put("wallet-withdrawal:", opens("a withdrawal",
                proof("PUSH_WITHDRAWAL", "payments.WithdrawalDatabaseTest", "noSuchTest")));
        assertThat(violations(declared, missing, real))
                .anySatisfy(violation -> assertThat(violation).contains("noSuchTest"));

        // A proof naming a real test that never calls the helper for its kind.
        Map<String, Row> hollow = new LinkedHashMap<>(REGISTER);
        hollow.put("payment-capture:", opens("a capture",
                proof("CARD_CAPTURE", CARDS, "theArnAliasRegistersInEitherOrder")));
        assertThat(violations(declared, hollow, real))
                .anySatisfy(violation -> assertThat(violation)
                        .contains("theArnAliasRegistersInEitherOrder")
                        .contains("CARD_CAPTURE"));

        // A kind a port can open with no proving row at all.
        Map<String, Row> uncovered = new LinkedHashMap<>(REGISTER);
        uncovered.put("merchant-payout:", touchesNothing("mislabelled",
                proof(NOTHING, STORM, "conservationHoldsAcrossEveryRail")));
        assertThat(violations(declared, uncovered, real))
                .anySatisfy(violation -> assertThat(violation).contains("MERCHANT_PAYOUT"));
    }

    @Test
    @DisplayName("the scanner reads posting-key literals from code and ignores prose")
    void theScannerReadsCodeNotProse() {
        assertThat(postingKeyLiterals(
                        "new PostingCommand(\"payment-capture:\" + id, today, today, r, lines);"))
                .containsExactly("payment-capture:");
        assertThat(postingKeyLiterals(
                        "// \"commented-key:\"\n/* \"blocked-key:\" */ String s = \"a sentence:\";"))
                .as("comments are prose; a literal with spaces is no posting key")
                .isEmpty();
        assertThat(postingKeyLiterals("static final String KEY = \"dispute-reattribution:\";"))
                .containsExactly("dispute-reattribution:");
    }

    // ----------------------------------------------------------------- the rules

    /** Every violation of the register's rules, as a sentence naming its subject. */
    static List<String> violations(
            Set<String> declared, Map<String, Row> register,
            BiFunction<Proof, String, Optional<String>> checkProof) {
        List<String> violations = new ArrayList<>();
        for (String prefix : declared) {
            if (!register.containsKey(prefix)) {
                violations.add("posting key '" + prefix + "' is declared by production code but"
                        + " unclassified: it opens a named expectation kind (with the test"
                        + " proving it), touches no reconciled position (with the reason), or is"
                        + " a Phase 8 record - ADR-0067 section 9");
            }
        }
        Map<String, Integer> kindRows = new TreeMap<>();
        for (Map.Entry<String, Row> entry : register.entrySet()) {
            String prefix = entry.getKey();
            Row row = entry.getValue();
            if (!declared.contains(prefix)) {
                violations.add("row '" + prefix + "' names a posting key no production code"
                        + " declares: a stale row silently stops applying");
            }
            if (row.reason() == null || row.reason().isBlank()) {
                violations.add("row '" + prefix + "' states no reason");
            }
            boolean opensSomething =
                    row.proofs().stream().anyMatch(proof -> !NOTHING.equals(proof.opens()));
            switch (row.classification()) {
                case OPENS -> {
                    if (!opensSomething) {
                        violations.add("row '" + prefix + "' is OPENS but names no kind");
                    }
                }
                case TOUCHES_NO_RECONCILED_POSITION -> {
                    if (opensSomething || row.proofs().isEmpty()) {
                        violations.add("row '" + prefix + "' touches no reconciled position, so"
                                + " its proofs must each show that nothing opens");
                    }
                }
                case PHASE_8_RECORD -> {
                    if (opensSomething) {
                        violations.add("row '" + prefix + "' is a Phase 8 record and names no"
                                + " kind here: what it opens is not a clearing line's copy");
                    }
                }
            }
            for (Proof proof : row.proofs()) {
                if (!NOTHING.equals(proof.opens())) {
                    if (Arrays.stream(ExpectationKind.values())
                            .noneMatch(kind -> kind.name().equals(proof.opens()))) {
                        violations.add("row '" + prefix + "' opens '" + proof.opens()
                                + "', which is no ExpectationKind");
                    }
                    kindRows.merge(proof.opens(), 1, Integer::sum);
                }
                checkProof.apply(proof, prefix)
                        .ifPresent(problem -> violations.add("row '" + prefix + "': " + problem));
            }
        }
        for (String kind : portKinds()) {
            int rows = kindRows.getOrDefault(kind, 0);
            if (rows != 1) {
                violations.add("the ports can open " + kind + " but " + rows + " rows prove it:"
                        + " every kind has exactly one proving row");
            }
        }
        return violations;
    }

    /** Every kind {@code payments}' and {@code merchant}'s ports can open. */
    static Set<String> portKinds() {
        Set<String> kinds = new TreeSet<>();
        Arrays.stream(SettlementExpectations.Kind.values()).forEach(kind -> kinds.add(kind.name()));
        Arrays.stream(PayoutSettlementExpectations.Kind.values())
                .forEach(kind -> kinds.add(kind.name()));
        return kinds;
    }

    /**
     * The real proof check: the test class and its {@code @Test} method exist, and the method's
     * body calls the ledger-backed helper for its claim about {@code prefix}.
     */
    static Optional<String> checkProof(Proof proof, String prefix) {
        String className = "com.finapp.app." + proof.test();
        Class<?> type;
        try {
            type = Class.forName(className, false, ExpectationOpenerRegisterTest.class
                    .getClassLoader());
        } catch (ClassNotFoundException absent) {
            return Optional.of("its proving test class " + className + " does not exist");
        }
        Optional<Method> method =
                Arrays.stream(type.getDeclaredMethods())
                        .filter(candidate -> candidate.getName().equals(proof.method()))
                        .filter(candidate -> candidate.isAnnotationPresent(Test.class))
                        .findFirst();
        if (method.isEmpty()) {
            return Optional.of("its proving test " + proof.test() + "#" + proof.method()
                    + " is not a @Test method of that class");
        }
        Path source = repositoryRoot().resolve(
                "app/src/test/java/" + className.replace('.', '/') + ".java");
        String body = bodyOf(read(source), proof.method());
        if (body == null) {
            return Optional.of("the body of " + proof.test() + "#" + proof.method()
                    + " could not be read from " + source);
        }
        if (NOTHING.equals(proof.opens())) {
            // Whitespace-tolerant: a formatter may break the call before its argument.
            boolean named =
                    Pattern.compile("assertOpensNothing\\(\\s*\"" + Pattern.quote(prefix))
                            .matcher(body)
                            .find();
            boolean scoped = body.contains("assertEveryClearingLineIsCopied(");
            return named || scoped
                    ? Optional.empty()
                    : Optional.of(proof.test() + "#" + proof.method() + " claims " + prefix
                            + " opens nothing but neither calls assertOpensNothing(\"" + prefix
                            + "...) nor proves a scope with assertEveryClearingLineIsCopied");
        }
        return body.contains("assertOpensItsClearingLinesCopy(")
                        && body.contains("ExpectationKind." + proof.opens())
                ? Optional.empty()
                : Optional.of(proof.test() + "#" + proof.method() + " is named as the proof of "
                        + proof.opens() + " but never calls assertOpensItsClearingLinesCopy with"
                        + " ExpectationKind." + proof.opens());
    }

    // ----------------------------------------------------------------- the scan

    /** A whole literal of the posting-key shape: lowercase words joined by hyphens, a colon. */
    private static final Pattern POSTING_KEY = Pattern.compile("[a-z][a-z0-9]*(?:-[a-z0-9]+)*:");

    static Set<String> declaredPostingKeyPrefixes() {
        Set<String> prefixes = new TreeSet<>();
        for (Path source : mainSources()) {
            String text = read(source);
            String code = codeOf(text);
            if (!code.contains("new PostingCommand(") && !code.contains("new ReversalCommand(")) {
                continue;
            }
            prefixes.addAll(postingKeyLiterals(text));
        }
        return prefixes;
    }

    static List<String> postingKeyLiterals(String source) {
        return Arrays.stream(scan(source, true).split("\n"))
                .filter(literal -> POSTING_KEY.matcher(literal).matches())
                .distinct()
                .collect(Collectors.toList());
    }

    private static List<Path> mainSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repositoryRoot())) {
            for (Path module : modules.toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(file -> file.toString().endsWith(".java")).forEach(sources::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(sources).as("the scan must see the codebase").hasSizeGreaterThan(100);
        return sources;
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        assertThat(current).as("the repository root must be findable").isNotNull();
        return current;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The body of {@code void method(...)} in {@code source}, braces matched outside string,
     * char and text-block literals and comments (a test body is full of JSON in strings), or
     * {@code null} when no such method is declared.
     */
    static String bodyOf(String source, String method) {
        int declaration = source.indexOf("void " + method + "(");
        if (declaration < 0) {
            return null;
        }
        int open = source.indexOf('{', declaration);
        int depth = 0;
        int i = open;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? length : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return source.substring(open, i + 1);
                    }
                }
                i++;
            }
        }
        return null;
    }

    /** String literals, one per line, comments stripped — the `OwnershipIsScopedTest` scanner. */
    private static String scan(String source, boolean keepLiterals) {
        StringBuilder kept = new StringBuilder();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? length : end;
                if (keepLiterals) {
                    kept.append(source, i + 3, end).append('\n');
                }
                i = Math.min(length, end + 3);
            } else if (c == '"') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                if (keepLiterals) {
                    kept.append(source, i + 1, Math.min(j, length)).append('\n');
                }
                i = j + 1;
            } else if (c == '\'') {
                int j = i + 1;
                while (j < length && source.charAt(j) != '\'') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                if (!keepLiterals) {
                    kept.append(c);
                }
                i++;
            }
        }
        return kept.toString();
    }

    /** The code with comments AND literals stripped — what a construction lives in. */
    private static String codeOf(String source) {
        return scan(source, false);
    }
}
