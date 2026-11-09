package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.ConfigurationRegister;
import com.finapp.ledger.SupportedCurrencies;
import com.finapp.platform.retention.RetentionSweep;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Every property the application reads is in the configuration register, and every row of the register names a property something
 * reads (`P15-TSK-031`, ADR-0125 decision 3, {@code THREAT_MODEL.md} T11).
 *
 * <p>The set of names is never written here. It is what {@link SecretInventoryIsCompleteTest} already reads - the source forms of
 * {@code @Value}, {@code Environment}, {@code Binder}, {@code @ConditionalOnProperty} and {@code _PROPERTY}, and every key of
 * {@code application.yaml}'s {@code finapp} tree - and three things its patterns cannot see: a name given to {@code Environment} as a
 * variable (the literals of a class doing that), a {@code @ConditionalOnProperty} in a shape the source pattern misses (by
 * reflection over every production class), and a name built from a prefix and a currency. Defaults are read the same way, so a
 * default built by concatenation is seen as the constant the compiler folded.
 *
 * <p>The class {@code SECRET} is not copied into the register: it <em>is</em> {@code SECRET_MANAGEMENT.md} section 3.2's inventory.
 * A row of the register may not carry that class or name one of its properties, so the two cannot disagree. Section 3.3's rows, which
 * ride in the same Secret and are not secrets, have rows here like anything else.
 *
 * <p>The sources per environment are read from the overlays' literals, the Deployment's {@code env}, the two ExternalSecrets and the
 * test overlay, each way round. What this cannot see is a value a person sets on a running pod, which is what {@code ConfigFingerprintDisagrees}
 * is for, and a rendered object an overlay's strategic patch creates: a patch touching an {@code env} list is refused here rather than
 * assumed not to exist. Rendered behaviour is {@code infra/scripts/manifest-check.sh}'s, and none of this has run against a cluster.
 */
@Tag("architecture")
@DisplayName("every property is registered, classed and sourced, and every row is read (P15-TSK-031)")
class ConfigurationRegisterIsCompleteTest {

    private static final String REGISTER = "docs/architecture/CONFIGURATION_REGISTER.md";
    private static final String BASE_KUSTOMIZATION = "deploy/base/kustomization.yaml";
    private static final String DEPLOYMENT = "deploy/base/deployment.yaml";
    private static final String APP_DELIVERY = "deploy/components/secret-delivery/externalsecret-app.yaml";
    private static final String TEST_OVERLAY = "app/src/test/resources/application.properties";

    private static final Map<String, String> OVERLAYS = new LinkedHashMap<>(
            Map.of("ci", "deploy/overlays/kind/kustomization.yaml", "staging", "deploy/overlays/staging/kustomization.yaml",
                    "production", "deploy/overlays/production/kustomization.yaml"));

    private static final Set<String> CLASSES = Set.of(ConfigurationRegister.FINANCIAL, ConfigurationRegister.OPERATIONAL);
    private static final String DEFAULT = "default";
    private static final String OVERLAY = "overlay";
    private static final String STORE = "store";
    private static final String GENERATED = "generated";
    private static final String POD = "pod";
    private static final String NOT_APPLICABLE = "-";

    /** A call naming its property through a variable: the literals of such a class are the names it reads. */
    private static final Pattern INDIRECT_READ = Pattern.compile("(?:[eE]nvironment\\.(?:getProperty|getRequiredProperty|containsProperty)|Binder\\.get\\([a-zA-Z]+\\)\\s*\\.bind)\\(\\s*[A-Za-z_]");

    private static final Pattern PROPERTY_LITERAL = Pattern.compile("\"(finapp\\.[a-zA-Z0-9._-]+)\"");

    /** {@code getProperty(name, default)} or {@code getProperty(name, Type.class, default)}, a name built from a literal prefix included. */
    private static final Pattern PROPERTY_DEFAULT = Pattern.compile(
            "getProperty\\(\\s*\"(finapp\\.[a-zA-Z0-9._-]+)\"\\s*(?:\\+\\s*[^,]+)?,\\s*(?:[A-Za-z.]+\\.class\\s*,\\s*)?(\"[^\"]*\"|-?[0-9]+L?)\\s*\\)");

    /** What every placeholder of application.yaml looks like when a key is read from a variable. */
    private static final Pattern YAML_VARIABLE = Pattern.compile("^\\$\\{([A-Z][A-Z0-9_]*)(?::(.*))?}$", Pattern.DOTALL);

    private static List<ConfigurationRegister.Row> rows;
    private static Map<String, ConfigurationRegister.Row> byProperty;
    private static Set<String> reads;
    private static Map<String, Default> defaults;
    private static Map<String, String> yaml;
    private static Map<String, Set<String>> literalsPerEnvironment;
    private static Set<String> podVariables;
    private static Set<String> deliveredVariables;
    private static Properties testOverlay;
    private static Set<String> secretProperties;

    /** A default that is an address on this machine, in the forms the code writes one. */
    private static final Pattern LOOPBACK_DEFAULT = Pattern.compile("^(?:jdbc:postgresql://)?(?:127\\.0\\.0\\.1|localhost)(?:[:/].*)?$");

    /** What the code says a property is when nothing sets it. {@code none}: it has no value at all, a feature off. */
    record Default(Optional<String> value, boolean stated) {
        static final Default NONE = new Default(Optional.empty(), false);

        String cell() {
            return value.map(text -> text.isEmpty() ? ConfigurationRegister.BLANK : LOOPBACK_DEFAULT.matcher(text).matches() ? ConfigurationRegister.LOOPBACK : text)
                    .orElse(ConfigurationRegister.NONE);
        }
    }

    @BeforeAll
    static void readEverything() {
        SecretInventoryIsCompleteTest.readEverything();
        String document = SecretInventoryIsCompleteTest.read(REGISTER);
        rows = ConfigurationRegister.rows(document);
        byProperty = new TreeMap<>();
        rows.forEach(row -> assertThat(byProperty.put(row.property(), row)).as("%s is registered twice", row.property()).isNull());

        yaml = new TreeMap<>();
        SecretInventoryIsCompleteTest.flatten(SecretInventoryIsCompleteTest.application).forEach((key, value) -> yaml.put(key, String.valueOf(value)));

        secretProperties = new TreeSet<>();
        SecretInventoryIsCompleteTest.inventory.stream()
                .map(SecretInventoryIsCompleteTest.Row::property)
                .filter(property -> !property.equals("none"))
                .forEach(secretProperties::add);

        defaults = readDefaults();
        reads = readProperties();

        literalsPerEnvironment = new LinkedHashMap<>();
        Set<String> base = literals(SecretInventoryIsCompleteTest.read(BASE_KUSTOMIZATION));
        OVERLAYS.forEach((environment, file) -> {
            Set<String> set = new TreeSet<>(base);
            set.addAll(literals(SecretInventoryIsCompleteTest.read(file)));
            literalsPerEnvironment.put(environment, set);
        });
        podVariables = podVariables();
        deliveredVariables = delivered();
        testOverlay = properties(SecretInventoryIsCompleteTest.read(TEST_OVERLAY));

        assertThat(rows).as("the register's table parsed").hasSizeGreaterThan(250);
        assertThat(reads).as("the names the application reads were read").hasSizeGreaterThan(280);
        assertThat(defaults).as("the defaults were read by reflection").hasSizeGreaterThan(250);
        assertThat(literalsPerEnvironment.get("production")).as("the production overlay's literals were read").contains("FINAPP_DB_INSTANCES");
        assertThat(podVariables).as("the Deployment's env was read").contains("FINAPP_MANAGEMENT_ADDRESS");
        assertThat(deliveredVariables).as("the ExternalSecret was read").contains("FINAPP_DB_URL");
    }

    // ---------------------------------------------------------------------------------------------- both ways

    @Test
    @DisplayName("every finapp property something reads is a row, or a secret of the inventory")
    void everyReadPropertyIsRegistered() {
        Set<String> missing = new TreeSet<>(reads);
        missing.removeIf(name -> !name.startsWith("finapp."));
        missing.removeAll(byProperty.keySet());
        missing.removeAll(secretProperties);
        assertThat(missing).as("give each a row in %s", REGISTER).isEmpty();
    }

    @Test
    @DisplayName("every row names a property something reads")
    void everyRowNamesARealProperty() {
        Set<String> real = new TreeSet<>(reads);
        real.addAll(yaml.keySet());
        Set<String> invented = new TreeSet<>(byProperty.keySet());
        invented.removeAll(real);
        assertThat(invented).as("a row with nothing reading it is an inventory of something that is not there").isEmpty();
    }

    @Test
    @DisplayName("every variable the overlays, the Deployment or the store deliver names a row or a secret: an overlay cannot set what nobody registered")
    void everyDeliveredVariableIsRegistered() {
        Set<String> registered = new TreeSet<>();
        rows.forEach(row -> registered.add(row.variable()));
        SecretInventoryIsCompleteTest.inventory.forEach(row -> registered.add(row.variable()));
        SecretInventoryIsCompleteTest.beside.forEach(row -> registered.add(row.variable()));

        Set<String> set = new TreeSet<>();
        literalsPerEnvironment.values().forEach(set::addAll);
        set.addAll(podVariables);
        set.addAll(deliveredVariables);

        Set<String> unregistered = new TreeSet<>(set);
        unregistered.removeAll(registered);
        assertThat(unregistered).as("an overlay sets a variable the register does not name").isEmpty();
    }

    @Test
    @DisplayName("a key of the test overlay is a row: a switch left behind names nothing")
    void everyTestOverlayKeyIsRegistered() {
        Set<String> stale = new TreeSet<>();
        for (String key : testOverlay.stringPropertyNames()) {
            if (key.startsWith("finapp.") && !byProperty.containsKey(key) && !secretProperties.contains(key)) {
                stale.add(key);
            }
        }
        assertThat(stale).as("the test overlay sets a property the register does not name").isEmpty();
    }

    @ParameterizedTest
    @DisplayName("the net catches a name given to Environment as a variable, in the class where the constant is")
    @ValueSource(strings = {
        "static final String X = \"finapp.x.y\"; Object o = environment.getProperty(X);",
        "for (String p : List.of(\"finapp.x.y\")) environment.getProperty(p);",
        "Binder.get(environment).bind(X, String.class); String X = \"finapp.x.y\";"
    })
    void theNetCatchesAnIndirectRead(String source) {
        assertThat(indirectLiterals(source)).containsExactly("finapp.x.y");
    }

    @Test
    @DisplayName("the net does not read a metric name or a message out of a class that only passes Environment along")
    void theNetDoesNotReadALiteralOfAClassThatReadsNothingIndirectly() {
        assertThat(indirectLiterals("static final String METER = \"finapp.x.meter\"; new Thing(environment); environment.getProperty(\"finapp.y\");")).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------- the rows against the code

    @Test
    @DisplayName("every row's default is the code's: read from the @Value, the condition or the placeholder, and no two sites disagree")
    void everyDefaultIsTheCodes() {
        List<String> wrong = new ArrayList<>();
        for (ConfigurationRegister.Row row : rows) {
            String expected = expectedDefault(row.property()).cell();
            if (!expected.equals(row.defaultCell())) {
                wrong.add(row.property() + ": the register says " + row.defaultCell() + ", the code " + expected);
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("each variable reaches its property: application.yaml's placeholder, or Spring asked")
    void everyVariableReachesItsProperty() {
        for (ConfigurationRegister.Row row : rows) {
            String declared = yaml.get(row.property());
            if (declared != null) {
                assertThat(declared).as("application.yaml reads %s through %s", row.property(), row.variable()).contains("${" + row.variable());
                continue;
            }
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(row.variable(), "delivered")));
            ConfigurationPropertySources.attach(environment);
            assertThat(environment.getProperty(row.property())).as("Spring binds %s to %s", row.variable(), row.property()).isEqualTo("delivered");
        }
    }

    @Test
    @DisplayName("each environment's source is what that environment's files say, both ways round")
    void everySourceIsTheEnvironments() {
        List<String> wrong = new ArrayList<>();
        for (ConfigurationRegister.Row row : rows) {
            if (!row.local().equals(DEFAULT)) {
                wrong.add(row.property() + " local: nothing but the defaults reaches a developer's machine");
            }
            String test = testOverlay.containsKey(row.property()) ? OVERLAY : DEFAULT;
            if (!row.test().equals(test)) {
                wrong.add(row.property() + " test: the register says " + row.test() + ", the test overlay " + test);
            }
            compare(wrong, row, "ci", row.ci(), GENERATED);
            compare(wrong, row, "staging", row.staging(), STORE);
            compare(wrong, row, "production", row.production(), STORE);
        }
        assertThat(wrong).isEmpty();
    }

    private static void compare(List<String> wrong, ConfigurationRegister.Row row, String environment, String cell, String deliveredThrough) {
        String actual;
        if (literalsPerEnvironment.get(environment).contains(row.variable())) {
            actual = OVERLAY;
        } else if (podVariables.contains(row.variable())) {
            actual = POD;
        } else if (deliveredVariables.contains(row.variable())) {
            actual = deliveredThrough;
        } else {
            actual = DEFAULT;
        }
        if (!cell.equals(actual)) {
            wrong.add(row.property() + " " + environment + ": the register says " + cell + ", the environment's files " + actual);
        }
    }

    @Test
    @DisplayName("an overlay never patches an env list: the sources above read literals, and a patch would be a source they cannot see")
    void noOverlayPatchesAnEnvironmentVariable() {
        for (Map.Entry<String, String> overlay : OVERLAYS.entrySet()) {
            assertThat(SecretInventoryIsCompleteTest.read(overlay.getValue()))
                    .as(overlay.getKey()).doesNotContainPattern("path:\\s*/spec/template/spec/containers/\\d+/env");
        }
    }

    // ---------------------------------------------------------------------------------------------- the classes

    @Test
    @DisplayName("the class SECRET is the inventory: no row carries it, and no row names a property of the inventory")
    void theSecretClassIsTheInventory() {
        assertThat(rows).allSatisfy(row -> assertThat(CLASSES).as("%s's class", row.property()).contains(row.klass()));
        Set<String> named = new TreeSet<>(byProperty.keySet());
        named.retainAll(secretProperties);
        assertThat(named).as("a secret has one home, the inventory").isEmpty();
        assertThat(SecretInventoryIsCompleteTest.read(REGISTER)).as("and the register says where that home is").contains("SECRET_MANAGEMENT.md");
    }

    @Test
    @DisplayName("what rides beside the secrets is a row, delivered through the store, in every deployed environment")
    void whatRidesBesideTheSecretsIsARow() {
        for (SecretInventoryIsCompleteTest.Beside beside : SecretInventoryIsCompleteTest.beside) {
            if (beside.property().equals("none")) {
                continue;
            }
            ConfigurationRegister.Row row = byProperty.get(beside.property());
            assertThat(row).as("%s rides beside the secrets and has no row", beside.property()).isNotNull();
            assertThat(row.variable()).as(beside.property()).isEqualTo(beside.variable());
            assertThat(row.staging()).as(beside.property()).isEqualTo(STORE);
            assertThat(row.production()).as(beside.property()).isEqualTo(STORE);
        }
    }

    @Test
    @DisplayName("a FINANCIAL row says why a mixed fleet is safe, an OPERATIONAL one says nothing, and no statement is the empty answer")
    void everyFinancialRowAnswersTheMixedFleetQuestion() {
        List<String> wrong = new ArrayList<>();
        for (ConfigurationRegister.Row row : rows) {
            boolean financial = row.klass().equals(ConfigurationRegister.FINANCIAL);
            String statement = row.mixedFleet();
            if (financial && statement.length() < 60) {
                wrong.add(row.property() + " answers in " + statement.length() + " characters");
            }
            if (!financial && !statement.equals(NOT_APPLICABLE)) {
                wrong.add(row.property() + " is OPERATIONAL and states a mixed-fleet answer: class it FINANCIAL or remove it");
            }
            if (statement.toLowerCase(java.util.Locale.ROOT).contains("only a config")) {
                wrong.add(row.property() + " gives the answer the register exists to refuse");
            }
        }
        assertThat(wrong).isEmpty();
    }

    @Test
    @DisplayName("not vacuous: what the plan names as financial is classed FINANCIAL")
    void whatThePlanNamesIsFinancial() {
        assertThat(financial()).contains("finapp.accounting.period.lock-grace-business-days", "finapp.accounting.valuation.max-age-hours",
                "finapp.merchant.payout-destination.cooling-off", "finapp.settlement.bank.account-reference.EUR",
                "finapp.lending.capital.headroom-floor.EUR", "finapp.risk.monitoring.horizon");
        assertThat(financial()).as("the provider endpoints decide a route").contains("finapp.payments.provider.url", "finapp.fx.provider.b.url");
    }

    @Test
    @DisplayName("a family built from a prefix has one row per supported currency and no other")
    void aPrefixFamilyHasOneRowPerCurrency() {
        String family = "finapp.lending.capital.headroom-floor.";
        Set<String> members = new TreeSet<>(byProperty.keySet());
        members.removeIf(name -> !name.startsWith(family));
        Set<String> expected = new TreeSet<>();
        SupportedCurrencies.ALL.stream().map(CurrencyCode::code).forEach(code -> expected.add(family + code));
        assertThat(members).isEqualTo(expected);
    }

    // ---------------------------------------------------------------------------------------------- what the routed notes asked

    @Test
    @DisplayName("no secret or endpoint of the store is a literal in any overlay")
    void noOverlayCarriesAStoreValue() {
        Set<String> carried = new TreeSet<>();
        literalsPerEnvironment.values().forEach(carried::addAll);
        Set<String> storeOnly = new TreeSet<>(deliveredVariables);
        storeOnly.retainAll(carried);
        assertThat(storeOnly).as("what the store delivers never sits in a committed overlay").isEmpty();
        assertThat(carried).as("none of the inventory's names either").doesNotContainAnyElementsOf(
                SecretInventoryIsCompleteTest.inventory.stream().map(SecretInventoryIsCompleteTest.Row::variable).toList());
    }

    @Test
    @DisplayName("finapp.inbox.retention stays above the oldest record the brokers can redeliver, in the code's default and in any overlay that sets it")
    void theInboxRetentionIsAboveItsFloorEverywhere() {
        assertThat(Duration.parse(byProperty.get("finapp.inbox.retention").defaultCell()))
                .as("the default").isGreaterThan(RetentionSweep.BROKER_REDELIVERY_HORIZON);
        for (Map.Entry<String, String> overlay : OVERLAYS.entrySet()) {
            String text = SecretInventoryIsCompleteTest.read(overlay.getValue());
            Matcher found = Pattern.compile("(?m)^\\s+- FINAPP_INBOX_RETENTION=(\\S+)\\s*$").matcher(text);
            while (found.find()) {
                assertThat(aboveTheRedeliveryFloor(found.group(1))).as("%s's retention", overlay.getKey()).isTrue();
            }
        }
    }

    @ParameterizedTest
    @DisplayName("the floor refuses a retention at or below the redelivery horizon, whatever its unit")
    @ValueSource(strings = {"P8D", "PT192H", "P7D", "PT1H"})
    void theFloorRefusesAShortRetention(String value) {
        assertThat(aboveTheRedeliveryFloor(value)).as(value).isFalse();
    }

    @ParameterizedTest
    @DisplayName("the floor admits a retention above the redelivery horizon")
    @ValueSource(strings = {"P14D", "P9D", "PT193H"})
    void theFloorAdmitsALongRetention(String value) {
        assertThat(aboveTheRedeliveryFloor(value)).as(value).isTrue();
    }

    private static boolean aboveTheRedeliveryFloor(String value) {
        return Duration.parse(value).compareTo(RetentionSweep.BROKER_REDELIVERY_HORIZON) > 0;
    }

    @Test
    @DisplayName("each deployed overlay's Kafka security protocol is one KafkaTransportGuard admits off loopback, and kind's is not this task's")
    void everyDeployedOverlayPassesTheBrokerGuard() {
        for (String overlay : List.of("staging", "production")) {
            String text = SecretInventoryIsCompleteTest.read(OVERLAYS.get(overlay));
            Matcher found = Pattern.compile("(?m)^\\s+- FINAPP_KAFKA_SECURITYPROTOCOL=(\\S+)\\s*$").matcher(text);
            assertThat(found.find()).as("%s declares the protocol, or the guard refuses every pod off loopback", overlay).isTrue();
            String protocol = found.group(1);
            // The guard itself judges, with the real shape of an off-loopback bootstrap list: not a comparison with a string written here.
            new KafkaTransportGuard("broker-1.invalid:9096,broker-2.invalid:9096", protocol, "a-user", "not-the-marked-default");
            assertThat(text).as("%s leaves the JVM's trust store in charge: the managed brokers' certificate chains to a public root", overlay)
                    .doesNotContain("FINAPP_KAFKA_SSL_TRUSTSTORELOCATION");
        }
    }

    // ---------------------------------------------------------------------------------------------- reading

    /** Every name read, in every form this codebase reads one. */
    private static Set<String> readProperties() {
        Set<String> names = new TreeSet<>(SecretInventoryIsCompleteTest.readProperties);
        SecretInventoryIsCompleteTest.sources.values().forEach(source -> names.addAll(indirectLiterals(source)));
        names.addAll(defaults.keySet());
        Set<String> expanded = new TreeSet<>();
        for (String name : names) {
            if (name.endsWith(".")) {
                SupportedCurrencies.ALL.forEach(currency -> expanded.add(name + currency.code()));
            } else {
                expanded.add(name);
            }
        }
        return expanded;
    }

    static Set<String> indirectLiterals(String source) {
        Set<String> found = new TreeSet<>();
        if (!INDIRECT_READ.matcher(source).find()) {
            return found;
        }
        Matcher literal = PROPERTY_LITERAL.matcher(source);
        while (literal.find()) {
            found.add(literal.group(1));
        }
        return found;
    }

    /** Every {@code @Value} and {@code @ConditionalOnProperty} of the application, by reflection. */
    static Map<String, Default> readDefaults() {
        Map<String, Set<Default>> seen = new TreeMap<>();
        for (Class<?> type : ConfinedCredentialVariablesTest.applicationClasses()) {
            readConditions(type, seen);
            for (Field field : type.getDeclaredFields()) {
                Value value = field.getAnnotation(Value.class);
                if (value != null) {
                    readExpression(value.value(), seen);
                }
                readConditions(field, seen);
            }
            List<Executable> executables = new ArrayList<>(List.of(type.getDeclaredMethods()));
            executables.addAll(List.of(type.getDeclaredConstructors()));
            for (Executable executable : executables) {
                readConditions(executable, seen);
                for (Annotation[] parameter : executable.getParameterAnnotations()) {
                    for (Annotation annotation : parameter) {
                        if (annotation instanceof Value value) {
                            readExpression(value.value(), seen);
                        }
                    }
                }
            }
        }
        SecretInventoryIsCompleteTest.sources.values().forEach(source -> readEnvironmentDefaults(source, seen));
        Map<String, Default> resolved = new TreeMap<>();
        seen.forEach((name, candidates) -> resolved.put(name, resolve(name, candidates)));
        return resolved;
    }

    /** A property read twice must not carry two defaults: which one a run gets would depend on the site. */
    private static Default resolve(String name, Set<Default> candidates) {
        Set<Optional<String>> stated = new java.util.HashSet<>();
        candidates.stream().filter(Default::stated).map(Default::value).forEach(stated::add);
        assertThat(stated).as("%s is read with more than one default", name).hasSizeLessThanOrEqualTo(1);
        return stated.isEmpty() ? Default.NONE : new Default(stated.iterator().next(), true);
    }

    /** The default an {@code Environment} call supplies: a prefix's family takes it once per member. */
    private static void readEnvironmentDefaults(String source, Map<String, Set<Default>> seen) {
        Matcher found = PROPERTY_DEFAULT.matcher(source);
        while (found.find()) {
            String written = found.group(2);
            String value = written.startsWith("\"") ? written.substring(1, written.length() - 1) : written.replaceAll("L$", "");
            String name = found.group(1);
            List<String> members = name.endsWith(".")
                    ? SupportedCurrencies.ALL.stream().map(currency -> name + currency.code()).toList()
                    : List.of(name);
            members.forEach(member -> seen.computeIfAbsent(member, key -> new java.util.HashSet<>()).add(new Default(Optional.of(value), true)));
        }
    }

    private static void readExpression(String expression, Map<String, Set<Default>> seen) {
        if (!expression.startsWith("${finapp.")) {
            return;
        }
        String body = expression.substring(2, expression.length() - 1);
        int colon = body.indexOf(':');
        String name = colon < 0 ? body : body.substring(0, colon);
        seen.computeIfAbsent(name, key -> new java.util.HashSet<>()).add(colon < 0 ? Default.NONE : new Default(Optional.of(body.substring(colon + 1)), true));
    }

    private static void readConditions(AnnotatedElement element, Map<String, Set<Default>> seen) {
        for (ConditionalOnProperty condition : element.getAnnotationsByType(ConditionalOnProperty.class)) {
            String prefix = condition.prefix().isEmpty() ? "" : condition.prefix().endsWith(".") ? condition.prefix() : condition.prefix() + ".";
            List<String> names = new ArrayList<>(List.of(condition.name()));
            names.addAll(List.of(condition.value()));
            for (String name : names) {
                if (!(prefix + name).startsWith("finapp.")) {
                    continue;
                }
                Default found = condition.matchIfMissing()
                        ? new Default(Optional.of(condition.havingValue().isEmpty() ? "true" : condition.havingValue()), true)
                        : Default.NONE;
                seen.computeIfAbsent(prefix + name, key -> new java.util.HashSet<>()).add(found);
            }
        }
    }

    private static Default expectedDefault(String property) {
        String declared = yaml.get(property);
        if (declared != null) {
            Matcher placeholder = YAML_VARIABLE.matcher(declared);
            if (placeholder.matches()) {
                return placeholder.group(2) == null ? Default.NONE : new Default(Optional.of(placeholder.group(2)), true);
            }
            return new Default(Optional.of(declared), true);
        }
        return defaults.getOrDefault(property, Default.NONE);
    }

    private static Set<String> financial() {
        Set<String> names = new TreeSet<>();
        rows.stream().filter(row -> row.klass().equals(ConfigurationRegister.FINANCIAL)).forEach(row -> names.add(row.property()));
        return names;
    }

    /** The {@code KEY=value} literals of every {@code configMapGenerator} of one kustomization. */
    @SuppressWarnings("unchecked")
    private static Set<String> literals(String kustomization) {
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> document : SecretInventoryIsCompleteTest.loadAll(kustomization)) {
            for (Object generator : (List<Object>) document.getOrDefault("configMapGenerator", List.of())) {
                for (Object literal : (List<Object>) ((Map<String, Object>) generator).getOrDefault("literals", List.of())) {
                    String text = String.valueOf(literal);
                    int equals = text.indexOf('=');
                    assertThat(equals).as("a literal is KEY=value: %s", text).isPositive();
                    names.add(text.substring(0, equals));
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> podVariables() {
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> document : SecretInventoryIsCompleteTest.loadAll(SecretInventoryIsCompleteTest.read(DEPLOYMENT))) {
            Map<String, Object> spec = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) document.get("spec")).get("template")).get("spec");
            for (Object container : (List<Object>) spec.get("containers")) {
                for (Object env : (List<Object>) ((Map<String, Object>) container).getOrDefault("env", List.of())) {
                    names.add(String.valueOf(((Map<String, Object>) env).get("name")));
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> delivered() {
        Set<String> names = new TreeSet<>();
        for (Map<String, Object> document : SecretInventoryIsCompleteTest.loadAll(SecretInventoryIsCompleteTest.read(APP_DELIVERY))) {
            for (Object entry : (List<Object>) ((Map<String, Object>) document.get("spec")).get("data")) {
                names.add(String.valueOf(((Map<String, Object>) entry).get("secretKey")));
            }
        }
        return names;
    }

    private static Properties properties(String text) {
        Properties properties = new Properties();
        try {
            properties.load(new StringReader(text));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
