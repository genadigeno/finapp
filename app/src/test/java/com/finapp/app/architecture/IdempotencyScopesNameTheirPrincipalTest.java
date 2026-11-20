package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Every idempotency claim's scope names its principal, or is recorded here with the reason it need not (`X-TSK-003`,
 * ADR-0004's amendment).
 *
 * <p>ADR-0004 decided that a scope "identifies the command type and the owning principal, so keys cannot collide across
 * different commands or across clients". Eleven scopes every client shared did not, so one client's key value refused
 * another's request; `X-TSK-003` moved them to {@code <scope>:<keyedType>:<id>}. This rule reads every production
 * {@code new IdempotencyKey(...)} - comments removed, literals emptied - and takes its scope argument. A scope that
 * names a person reads {@code keyedType()} in that argument; every other one is in {@link #RECORDED}, as a person named
 * through a helper or variable, an owner named by its id, the platform's own key, or a caller with no principal yet -
 * each with why. The register is held both ways: an unrecorded scope fails, and so does a record nothing builds.
 *
 * <p>The move's transition is held too. Each {@code movedFrom(...)} in production is one of {@link #MOVED}, the old
 * scope a command claims first for `X-TSK-003`'s release; `X-TSK-028` drops them, and this register empties with it.
 */
@Tag("architecture")
@DisplayName("every idempotency scope names its principal, or is recorded with the reason it need not (X-TSK-003)")
class IdempotencyScopesNameTheirPrincipalTest {

    private static final Pattern NEW_KEY = Pattern.compile("new\\s+IdempotencyKey\\s*\\(");
    private static final Pattern MOVED_FROM = Pattern.compile("\\.\\s*movedFrom\\s*\\(");

    /** Why a scope that does not read {@code keyedType()} in its argument is still scoped as ADR-0004 asks. */
    enum Basis {
        /** A person, named through a helper or a variable that reads {@code keyedType()}. */
        PERSON,
        /** The owning merchant or party, by its id: the principal the command acts for. */
        OWNER,
        /** The platform's own key, which no client chooses, so no two principals can meet on one. */
        SYSTEM,
        /** No principal exists yet; what stands behind the claim is named. */
        NO_PRINCIPAL_YET,
        /** The transition's own construction of the old key. */
        TRANSITION
    }

    private record Recorded(Basis basis, String why) {}

    /** {@code <file>: <scope argument>}, as this rule reads it, and why it is scoped. */
    private static final Map<String, Recorded> RECORDED = Map.ofEntries(
            Map.entry("ProjectionRebuilds.java: scope + \"\" + principal(actor)", new Recorded(Basis.PERSON,
                    "principal(actor) is the actor's keyedType() and id")),
            Map.entry("PaymentCreation.java: claimScope(actor)", new Recorded(Basis.PERSON,
                    "payment.create claims as the actor's keyedType() and id; the checkout's checkout.payment keeps its"
                            + " scope, keyed by the session the platform derived")),
            Map.entry("DisputeResponses.java: scope", new Recorded(Basis.PERSON,
                    "the requester's keyedType() and id, built in the statement above")),
            Map.entry("CheckoutSessions.java: IDEMPOTENCY_SCOPE_PREFIX + command.merchant().value()",
                    new Recorded(Basis.OWNER, "the merchant whose API key opens the session")),
            Map.entry("MerchantPayouts.java: IDEMPOTENCY_SCOPE_PREFIX + command.merchant().value()",
                    new Recorded(Basis.OWNER, "the merchant whose payable is paid out")),
            Map.entry("PaymentMethodService.java: REGISTER_SCOPE_PREFIX + partyId", new Recorded(Basis.OWNER,
                    "the party registering its own method")),
            Map.entry("Withdrawals.java: CLAIM_SCOPE_PREFIX + early.get().customerId()", new Recorded(Basis.OWNER,
                    "the customer whose wallet is withdrawn from")),
            Map.entry("Withdrawals.java: instruction.get().claimScope()", new Recorded(Basis.OWNER,
                    "the customer, CLAIM_SCOPE_PREFIX and the instruction's own customer part")),
            Map.entry("WithdrawalDoorStepUp.java: Withdrawals.claimScopeOf(wallet.get().customerId())",
                    new Recorded(Basis.OWNER, "the step-up door reading the customer's own withdrawal claim")),
            Map.entry("PostingService.java: IDEMPOTENCY_SCOPE", new Recorded(Basis.SYSTEM,
                    "ledger.post: every key is a posting the platform derives from its source fact; ledger V037's unique"
                            + " index and three readers key on ledger.post:<key>, so it never moves")),
            Map.entry("ReversalService.java: IDEMPOTENCY_SCOPE", new Recorded(Basis.SYSTEM,
                    "ledger.reverse: keyed by the entry reversed and its cause, under V037's index with the postings")),
            Map.entry("HeldPayments.java: scope", new Recorded(Basis.SYSTEM,
                    "the held payment's capture or void, keyed by the risk action that issued it")),
            Map.entry("Withdrawals.java: RELEASE_SCOPE", new Recorded(Basis.SYSTEM,
                    "a held withdrawal's release, keyed by the risk action that issued it")),
            Map.entry("Withdrawals.java: DENY_SCOPE", new Recorded(Basis.SYSTEM,
                    "a held withdrawal's denial, keyed by the risk action that issued it")),
            Map.entry("IdentityRiskActions.java: SCOPE", new Recorded(Basis.SYSTEM,
                    "the identity door's application of a risk action, keyed by the action")),
            Map.entry("MerchantRiskActions.java: SCOPE", new Recorded(Basis.SYSTEM,
                    "the merchant door's application of a risk action, keyed by the action")),
            // P17-TSK-028, recorded at P17-TSK-031 (the -028 gate's targeted run did not include this guard).
            Map.entry("AccountRiskActions.java: SCOPE", new Recorded(Basis.SYSTEM,
                    "the accounts door's application of a risk action, keyed by the action")),
            Map.entry("RegistrationService.java: SCOPE", new Recorded(Basis.NO_PRINCIPAL_YET,
                    "registration creates the principal; the login identifier's unique index stands behind the claim")),
            Map.entry("IdempotencyKey.java: scope", new Recorded(Basis.TRANSITION,
                    "movedFrom's construction of the old key, which this rule's MOVED register holds")));

    /**
     * {@code <file>: <movedFrom argument>}: the old scope each moved command claims first for `X-TSK-003`'s release - and
     * the transfer door's read of it - every one gone with `X-TSK-028`.
     */
    private static final Set<String> MOVED = Set.of(
            "TransferExecution.java: IDEMPOTENCY_SCOPE",
            "TransferDoorStepUp.java: TransferExecution.IDEMPOTENCY_SCOPE",
            "PaymentCreation.java: scope",
            "PaymentRefund.java: IDEMPOTENCY_SCOPE",
            "RoutingAdministration.java: IDEMPOTENCY_SCOPE",
            "AccountService.java: IDEMPOTENCY_SCOPE",
            "AccountService.java: ADD_CURRENCY_SCOPE",
            "MerchantOnboarding.java: IDEMPOTENCY_SCOPE",
            "MerchantApiKeys.java: IDEMPOTENCY_SCOPE",
            "PayoutDestinations.java: PROPOSE_SCOPE",
            "AdjustmentService.java: IDEMPOTENCY_SCOPE",
            "ProjectionRebuilds.java: scope");

    @Test
    @DisplayName("every production claim's scope reads keyedType(), or is recorded with why it is scoped, and every record"
            + " is still built")
    void everyScopeNamesItsPrincipal() throws IOException {
        Census census = census();
        assertThat(census.claims()).as("the census: the claims were found").isGreaterThan(100);
        Set<String> unrecorded = new TreeSet<>(census.unnamed());
        unrecorded.removeAll(RECORDED.keySet());
        assertThat(unrecorded)
                .as("a scope naming no person: claim as <scope>:<keyedType>:<id>, or record why it need not")
                .isEmpty();
        Set<String> stale = new TreeSet<>(RECORDED.keySet());
        stale.removeAll(census.unnamed());
        assertThat(stale).as("a record no production claim builds").isEmpty();
    }

    @Test
    @DisplayName("every movedFrom in production is X-TSK-003's transition, registered, and every registered one is still"
            + " there until X-TSK-028 removes it")
    void theTransitionIsRegistered() throws IOException {
        Census census = census();
        assertThat(new TreeSet<>(census.moved())).as("movedFrom calls, against the register")
                .isEqualTo(new TreeSet<>(MOVED));
    }

    @Test
    @DisplayName("the rule reads a scope argument as it is written: keyedType() names a person, a bare constant does not")
    void theReadingHasTeeth() {
        assertThat(scopes(read("new IdempotencyKey(SCOPE + actor.keyedType().name() + \":\" + actor.id(), key)")))
                .containsExactly("SCOPE + actor.keyedType().name() + \"\" + actor.id()");
        assertThat(scopes(read("new IdempotencyKey(\nIDEMPOTENCY_SCOPE ,\n key) // new IdempotencyKey(X, k)")))
                .containsExactly("IDEMPOTENCY_SCOPE");
        assertThat(scopes(read("x = new IdempotencyKey(of(a, b), key); y = key.movedFrom(OLD);")))
                .containsExactly("of(a, b)");
        assertThat(movedFrom(read("x = key.movedFrom(OLD_SCOPE); /* key.movedFrom(GONE) */")))
                .containsExactly("OLD_SCOPE");
    }

    private static String read(String source) {
        return IdempotencyScopesKeyThePersonTest.codeOnly(source);
    }

    // -----------------------------------------------------------------

    private record Census(int claims, Set<String> unnamed, Set<String> moved) {}

    private static Census census() throws IOException {
        List<Path> sources = productionSources();
        assertThat(sources).as("the census: every module's production sources were read").hasSizeGreaterThan(2000);
        int claims = 0;
        Set<String> unnamed = new LinkedHashSet<>();
        Set<String> moved = new LinkedHashSet<>();
        for (Path source : sources) {
            String code = IdempotencyScopesKeyThePersonTest.codeOnly(Files.readString(source));
            String file = source.getFileName().toString();
            for (String scope : scopes(code)) {
                claims++;
                if (!scope.contains("keyedType()")) {
                    unnamed.add(file + ": " + scope);
                }
            }
            for (String old : movedFrom(code)) {
                moved.add(file + ": " + old);
            }
        }
        return new Census(claims, unnamed, moved);
    }

    /** Each {@code new IdempotencyKey(...)}'s first argument, whitespace collapsed. */
    private static List<String> scopes(String code) {
        return firstArguments(code, NEW_KEY);
    }

    /** Each {@code .movedFrom(...)}'s argument, whitespace collapsed. */
    private static List<String> movedFrom(String code) {
        return firstArguments(code, MOVED_FROM);
    }

    private static List<String> firstArguments(String code, Pattern call) {
        List<String> arguments = new ArrayList<>();
        Matcher matcher = call.matcher(code);
        while (matcher.find()) {
            int depth = 0;
            int end = matcher.end();
            while (end < code.length()) {
                char c = code.charAt(end);
                if (c == '(' || c == '[' || c == '{') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    break;
                }
                end++;
            }
            arguments.add(code.substring(matcher.end(), end).trim().replaceAll("\\s+", " "));
        }
        return arguments;
    }

    private static List<Path> productionSources() throws IOException {
        Path root = EvidenceCitations.repositoryRoot();
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(main)) {
                    files.filter(path -> path.toString().endsWith(".java")).sorted().forEach(sources::add);
                }
            }
        }
        return sources;
    }
}
