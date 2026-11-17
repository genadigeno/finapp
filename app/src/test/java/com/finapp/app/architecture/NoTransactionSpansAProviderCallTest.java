package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.HeldConnectionCalls.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * No transaction spans a provider call, and no connection is held during one (ADR-0046 §1 and its owed "structural assertion";
 * ADR-0008; {@code MODULE_ARCHITECTURE.md} section 6, Transaction boundary; `X-TSK-023`).
 *
 * <p>A transaction held open across a provider call pins a connection for the provider's worst latency and buys nothing: the
 * provider's effect is not in the transaction, and a rollback erases the record that anything was asked. Until `X-TSK-023` the
 * rule was held by review alone (`DISTRIBUTED_EXECUTION.md` section 4b, question 5): the payments suite asserted it at the wire for
 * its own flows, and nothing held it for the other twelve modules that call a provider. It fails quietly - a call moved one line,
 * into the lambda beside it, compiles, passes every functional test, and holds a connection for as long as the provider likes.
 *
 * <p>What runs held and what reaches the network are read from the compiled classes ({@link HeldConnectionCalls}): every method
 * handed a transaction or a connection (each module's {@code inTransaction(Function<Connection, R>)} body, Spring's
 * {@code TransactionStatus} callbacks, every store method on the caller's connection), every {@code @Transactional} method, every
 * method that turns auto-commit off itself; and everything those call, through interfaces, lambdas, and the functional values
 * they are handed or hold. The network is {@code HttpClient.send}, which only {@code OutboundExchange.send} calls
 * ({@code OutboundCallsAreBoundedTest}). Each member refuses one way of letting the rule go:
 *
 * <ol>
 *   <li>no held code reaches the network, with the path printed when one does;
 *   <li>every functional value held code runs is followed to where it was made, or stands in the reviewed list beside the bodies a
 *       reader found it holds, and those must reach no provider;
 *   <li>the guard sees every provider port ADR-0008 names reach the network, and reads at least what the code held today - a scan
 *       that stops finding cannot pass as a smaller codebase;
 *   <li>it sees ADR-0046's own shape where the code has it - transactions made beside a call that reaches the network - and lets
 *       it pass, a planted copy of it included;
 *   <li>planted beside production, each way to hold a connection around a provider call is found, and its path runs through
 *       production's own adapters;
 *   <li>a functional value planted where the guard cannot follow it is listed, not passed.
 * </ol>
 *
 * <p>Hermetic: it reads class files only. What a class file cannot show - reflection, a method handle built at runtime - is beyond
 * it, and the code uses neither to reach a provider.
 */
@Tag("architecture")
@DisplayName("no transaction spans a provider call, and no connection is held during one (ADR-0046, X-TSK-023)")
class NoTransactionSpansAProviderCallTest {

    /**
     * Functional values held code runs that the guard cannot trace to where they were made, each beside the bodies a reader found
     * the value can be (owner and method-name prefix). Each must exist and reach no provider; a new entry is a reviewed change.
     */
    private static final Map<String, List<String>> STAND_INS = Map.of(
            "com.finapp.lending.Payoffs#quote: the argument 4 of com.finapp.lending.Payoffs#projection is the result of"
                    + " java.util.Optional#get",
            // LoanServicing#periodsOf maps the accrual start through periods(...), whose one function reads the loan's
            // statement terms: the Optional holds that function and nothing else.
            List.of("com/finapp/lending/LoanServicing#lambda$periods$"));

    /** The ports ADR-0008 puts in front of each provider, by an operation of each: every one must be seen to reach the network. */
    private static final List<String> PROVIDER_PORTS = List.of(
            "com/finapp/payments/PaymentProvider#authorize",
            "com/finapp/payments/PaymentProvider#capture",
            "com/finapp/payments/PaymentProvider#refund",
            "com/finapp/payments/PaymentProvider#query",
            "com/finapp/payments/PushRail#send",
            "com/finapp/payments/PushRail#inquire",
            "com/finapp/payments/CorridorRail#send",
            "com/finapp/payments/CorridorRail#inquire",
            "com/finapp/fx/FxProvider#firmQuote",
            "com/finapp/fx/FxProvider#execute",
            "com/finapp/fx/RateSource#fetch",
            "com/finapp/merchant/PayoutProvider#dispatch",
            "com/finapp/merchant/PayoutProvider#query",
            "com/finapp/merchant/PayoutDestinationTokenisation#exchange",
            "com/finapp/paymentmethods/TokenisationProvider#exchange",
            "com/finapp/kyc/CounterpartyScreeningProvider#screen",
            "com/finapp/kyc/VerificationProvider#verify",
            "com/finapp/credit/CreditDataSource#pull",
            "com/finapp/settlement/SettlementReportCollector#collect",
            "com/finapp/platform/audit/archive/AuditArchiveStore#putIfAbsent");

    /** Floors, at the code's own counts on 2026-11-15 (7,079 scopes, 25,631 held methods, 329 reaching the network, 77 beside). */
    private static final int MIN_SCOPES = 7_000;

    private static final int MIN_HELD = 25_000;
    private static final int MIN_REACHING = 300;
    private static final int MIN_BESIDE = 70;

    private static final String SEND = "com.finapp.platform.outbound.OutboundExchange#send";

    private static final String PROBE = "com/finapp/app/architecture/ProviderCallsHeldProbe";
    private static final String SHAPE = "com/finapp/app/architecture/DispatchBeforeCallProbe";

    private static Map<String, ClassNode> production;
    private static HeldConnectionCalls real;
    private static HeldConnectionCalls planted;

    @BeforeAll
    static void readTheCode() {
        production = HeldConnectionCalls.production();
        real = new HeldConnectionCalls(production);
        Map<String, ClassNode> withProbes = new HashMap<>(production);
        for (Class<?> probe : List.of(ProviderCallsHeldProbe.class, DispatchBeforeCallProbe.class)) {
            ClassNode node = HeldConnectionCalls.classOf(probe);
            withProbes.put(node.name, node);
        }
        planted = new HeldConnectionCalls(withProbes);
    }

    @Test
    @DisplayName("no code that runs while a connection is held can reach a provider: every transaction body, every method handed the"
            + " connection, and everything they call")
    void noHeldCodeReachesTheNetwork() {
        assertThat(described(real.violations()))
                .as("ADR-0046: state is committed before the call and the outcome applied in a separate transaction - the call itself"
                        + " holds no connection. Each entry is a held scope and its path to the network; move the call out of the"
                        + " transaction, between two of them")
                .isEmpty();
    }

    @Test
    @DisplayName("every functional value held code runs is followed to where it was made, or stands reviewed beside the bodies it can"
            + " be, and those reach no provider")
    void everyFunctionalValueHeldCodeRunsIsFollowed() {
        assertThat(real.unfollowed())
                .as("a value the guard cannot follow is a value it cannot vouch for: extend HeldConnectionCalls to follow it, or name"
                        + " the bodies it can be in STAND_INS after reading where it is made")
                .containsExactlyInAnyOrderElementsOf(STAND_INS.keySet());
        STAND_INS.forEach((entry, bodies) -> {
            for (String body : bodies) {
                String owner = body.substring(0, body.indexOf('#'));
                String prefix = body.substring(body.indexOf('#') + 1);
                Set<Method> matched = real.methods().stream()
                        .filter(m -> m.owner().equals(owner) && m.name().startsWith(prefix))
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(matched).as("the stand-in %s for %s still exists", body, entry).isNotEmpty();
                assertThat(matched).as("the stand-in %s reaches no provider", body).noneMatch(real::reachesTheNetwork);
            }
        });
    }

    @Test
    @DisplayName("the guard sees every provider port ADR-0008 names reach the network, and reads at least what the code held today")
    void theGuardSeesTheNetworkThroughEveryProviderPort() {
        List<String> blind = PROVIDER_PORTS.stream().filter(port -> !portReaches(port)).toList();
        assertThat(blind).as("a port the guard does not see reaching the network is a port it would let a transaction span").isEmpty();
        assertThat(real.heldScopes()).as("methods handed a transaction or a connection").hasSizeGreaterThanOrEqualTo(MIN_SCOPES);
        assertThat(real.heldCount()).as("methods that run while a connection is held").isGreaterThanOrEqualTo(MIN_HELD);
        assertThat(real.methods().stream().filter(real::reachesTheNetwork).count())
                .as("methods that can reach the network").isGreaterThanOrEqualTo(MIN_REACHING);
        assertThat(real.heldScopes().keySet()).as("the modules' runner bodies are among the scopes")
                .anyMatch(method -> method.owner().equals("com/finapp/payments/PaymentSweeper") && method.name().startsWith("lambda$"));
    }

    @Test
    @DisplayName("ADR-0046's shape - transactions made beside a call that reaches the network - is seen where the code has it, and"
            + " passes, a planted copy included")
    void theGuardSeesAdr0046sShapeAndLetsItPass() {
        Set<Method> beside = real.callingBesideATransaction();
        assertThat(beside).as("the methods that call a provider between or beside their transactions")
                .hasSizeGreaterThanOrEqualTo(MIN_BESIDE)
                .anyMatch(m -> m.owner().equals("com/finapp/payments/PaymentSweeper"))
                .anyMatch(m -> m.owner().equals("com/finapp/payments/PaymentConfirmation"));

        Method confirm = only(planted.methods(), SHAPE, "confirm");
        assertThat(planted.reachesTheNetwork(confirm)).as("the guard sees the provider call between the transactions").isTrue();
        assertThat(planted.held(confirm)).as("the method itself holds nothing").isFalse();
        Set<Method> bodies = planted.heldScopes().keySet().stream()
                .filter(m -> m.owner().equals(SHAPE) && m.name().startsWith("lambda$confirm$"))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(bodies).as("both transactions' bodies are held").hasSize(2);
        assertThat(planted.callingBesideATransaction()).as("and the shape is read as ADR-0046's").contains(confirm);
        assertThat(planted.violations().keySet()).as("dispatch-before-call is no violation").noneMatch(m -> m.owner().equals(SHAPE));
    }

    @Test
    @DisplayName("planted beside production, each way to hold a connection around a provider call is found, through production's"
            + " own adapters")
    void everyWayToHoldAConnectionAroundAProviderCallIsFound() {
        Map<Method, List<Method>> found = new TreeMap<>(planted.violations());
        Map<Method, List<Method>> probes = new TreeMap<>();
        found.forEach((scope, path) -> {
            if (scope.owner().equals(PROBE)) {
                probes.put(scope, path);
            }
        });
        assertThat(probes.keySet().stream().map(m -> m.name().replaceAll("\\$\\d+$", "")).distinct().toList())
                .as("one violation per way, and nothing else of the probe's")
                .containsExactlyInAnyOrder(
                        "lambda$insideTheTemplate",
                        "lambda$insideTheModulesRunner",
                        "runsWhileHeld",
                        "throughAField",
                        "whileReadingARow",
                        "opensItsOwn",
                        "annotated",
                        "lambda$throughAPortALambdaImplements");
        probes.forEach((scope, path) -> assertThat(path.stream().map(Method::toString).toList())
                .as("%s reaches the network through production's adapters and OutboundExchange", scope)
                .contains(SEND));
        Map<Method, List<Method>> runners = new TreeMap<>(found);
        runners.keySet().removeAll(probes.keySet());
        assertThat(runners.keySet().stream().map(m -> m.owner() + "#" + m.name().replaceAll("\\$\\d+$", "")).toList())
                .as("beyond the probe's own, the one violation is payments' runner body, which runs the work the probe hands it")
                .containsExactly("com/finapp/app/payments/PaymentBeans$1#lambda$inTransaction");
        runners.forEach((runner, path) -> assertThat(path)
                .as("%s reaches the network by running the probe's work", runner)
                .anyMatch(m -> m.owner().equals(PROBE) && m.name().startsWith("lambda$insideTheModulesRunner$")));
    }

    @Test
    @DisplayName("a functional value planted where the guard cannot follow it is listed, not passed")
    void aValueTheGuardCannotFollowIsListed() {
        assertThat(planted.unfollowed())
                .as("the planted map's function is listed, beside the reviewed stand-ins")
                .anyMatch(entry -> entry.startsWith("com.finapp.app.architecture.DispatchBeforeCallProbe#runsAValueFromAMap:")
                        && entry.endsWith("the result of java.util.Map#get"))
                .containsAll(STAND_INS.keySet());
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static boolean portReaches(String port) {
        String owner = port.substring(0, port.indexOf('#'));
        String name = port.substring(port.indexOf('#') + 1);
        ClassNode node = production.get(owner);
        if (node == null) {
            return false;
        }
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && real.callReaches(owner, name, method.desc)) {
                return true;
            }
        }
        return false;
    }

    private static Method only(Set<Method> methods, String owner, String name) {
        List<Method> matched = methods.stream().filter(m -> m.owner().equals(owner) && m.name().equals(name)).toList();
        assertThat(matched).as("%s#%s", owner, name).hasSize(1);
        return matched.get(0);
    }

    private static List<String> described(Map<Method, List<Method>> violations) {
        return violations.entrySet().stream()
                .map(entry -> real.heldScopes().get(entry.getKey()) + " " + entry.getKey().full() + "\n    reaches the network by "
                        + entry.getValue().stream().map(Method::toString).collect(Collectors.joining(" -> ")))
                .toList();
    }
}
