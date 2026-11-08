package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EnvelopeProducers.Site;
import com.finapp.app.eventing.EventingBeans;
import com.finapp.app.eventing.InboxConsumers;
import com.finapp.platform.inbox.kafka.KafkaEventReceiver;
import com.finapp.platform.outbox.BrokerConnection;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.testing.RepositoryPaths;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The topic catalogue is checked against the code that produces to it (`P15-TSK-026`).
 *
 * <h2>What is derived, and from where</h2>
 *
 * <ul>
 *   <li><strong>The produced topics</strong> - from the compiled production code: every
 *       {@code new EventEnvelope(...)} and the producer constant it names ({@link
 *       EnvelopeProducers}), mapped through the publisher's own {@link
 *       KafkaEventPublisher#topicFor}. A second reader, ArchUnit's import, must find exactly the
 *       same construction sites, so a scanner that silently missed some cannot pass.
 *   <li><strong>The dedupe window</strong> - from {@code EventingBeans}' own {@code @Value} on the
 *       inbox's retention, resolved against the committed {@code application.yaml}.
 *   <li><strong>The consumer's session timeout</strong> - {@link
 *       KafkaEventReceiver#SESSION_TIMEOUT}, the constant its client configuration is built from.
 *   <li><strong>The declarations</strong> - {@code infra/kafka/topics.yaml} and {@code
 *       infra/kafka/broker.properties}, the files {@code P15-TSK-015} and {@code P15-TSK-012}
 *       provision from.
 * </ul>
 *
 * <p>So a type produced to an undeclared topic is red, a declared topic nothing produces is red,
 * and a retention the inbox's dedupe cannot outlive is red - none of it held by a list kept beside
 * the catalogue.
 *
 * <p>"Production code" is {@link ProductionModules}' definition, shared with the monitor sweep: the
 * main outputs, and the modules' test-fixture jars with them. No fixture constructs an envelope
 * today; one that did would be read as a producer here, by both readers alike, and would need its
 * topic declared or the fixture moved.
 */
@Tag("architecture")
@DisplayName("the topic catalogue matches the code that produces to it (P15-TSK-026)")
class TopicCatalogueTest {

    private static final String CATALOGUE = "infra/kafka/topics.yaml";
    private static final String BROKER = "infra/kafka/broker.properties";
    private static final String APPLICATION = "app/src/main/resources/application.yaml";

    private static List<Site> sites;
    private static Map<String, Object> catalogue;
    private static Properties broker;

    @BeforeAll
    static void read() {
        sites = EnvelopeProducers.inProductionCode();
        catalogue = yaml(RepositoryPaths.read(CATALOGUE));
        broker = new Properties();
        try {
            broker.load(new StringReader(RepositoryPaths.read(BROKER)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------
    // The topics, both ways
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every event names its producer as a constant the build can read")
    void everyProducerIsReadable() {
        List<String> unreadable =
                sites.stream()
                        .filter(site -> site.producer() == null)
                        .map(site -> site.owner() + "#" + site.method())
                        .toList();

        assertThat(unreadable)
                .as("a producer computed at run time names a topic this test cannot check against"
                        + " the catalogue; name it as a static final String constant")
                .isEmpty();
    }

    @Test
    @DisplayName("every produced topic is declared")
    void everyProducedTopicIsDeclared() {
        Map<String, Set<String>> undeclared = new TreeMap<>();
        for (Site site : sites) {
            if (site.producer() == null) {
                continue; // everyProducerIsReadable's failure, not this one's
            }
            String topic = KafkaEventPublisher.topicFor(site.producer());
            if (!topics().containsKey(topic)) {
                undeclared
                        .computeIfAbsent(topic, ignored -> new TreeSet<>())
                        .add(site.owner() + " (" + (site.eventType() == null ? "a computed type" : site.eventType()) + ")");
            }
        }

        assertThat(undeclared)
                .as("a deployed broker creates no topic on first use: an event produced to an"
                        + " undeclared topic stalls its aggregate until the outbox dead-letters it."
                        + " Declare it in %s",
                        CATALOGUE)
                .isEmpty();
    }

    @Test
    @DisplayName("every declared topic is produced to")
    void everyDeclaredTopicIsProduced() {
        Set<String> produced =
                sites.stream()
                        .map(Site::producer)
                        .filter(java.util.Objects::nonNull)
                        .map(KafkaEventPublisher::topicFor)
                        .collect(Collectors.toCollection(TreeSet::new));

        Set<String> unproduced = new TreeSet<>(topics().keySet());
        unproduced.removeAll(produced);

        // One direction only: a produced topic the catalogue lacks is everyProducedTopicIsDeclared's.
        assertThat(produced).as("the code produces to some topic at all").isNotEmpty();
        assertThat(unproduced)
                .as("a declared topic nothing produces to is a catalogue that has drifted from"
                        + " the code")
                .isEmpty();
    }

    @Test
    @DisplayName("the scan saw every construction site a second reader sees, and real ones")
    void theScanIsNotVacuous() {
        // A second, independent reader of the same production code: ArchUnit's import. If the
        // stack simulation silently skipped a class, a jar or a method, the two would disagree.
        JavaClasses imported =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.finapp");
        Map<String, Integer> byArchUnit = new TreeMap<>();
        imported.stream()
                .flatMap(type -> type.getConstructorCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isEquivalentTo(EventEnvelope.class))
                .map(TopicCatalogueTest::siteOf)
                .forEach(site -> byArchUnit.merge(site, 1, Integer::sum));
        Map<String, Integer> byScanner = new TreeMap<>();
        sites.forEach(site -> byScanner.merge(site.owner() + "#" + site.method(), 1, Integer::sum));

        assertThat(byScanner)
                .as("the bytecode simulation and ArchUnit must find the same construction sites")
                .isEqualTo(byArchUnit);
        assertThat(byScanner).as("and there are some").isNotEmpty();
        assertThat(sites.stream().map(Site::module).collect(Collectors.toSet()))
                .as("in more than one module: every business module that announces a fact")
                .hasSizeGreaterThan(10);
    }

    // -----------------------------------------------------------------
    // Retention, below the inbox's dedupe window
    // -----------------------------------------------------------------

    @Test
    @DisplayName("every topic's retention, plus one segment and a retention check, is below the dedupe window")
    void everyRetentionIsBelowTheDedupeWindow() {
        Duration window = inboxRetention();
        long checkInterval = Long.parseLong(required(broker, "log.retention.check.interval.ms"));
        Map<String, String> breaches = new TreeMap<>();
        topics().forEach(
                (topic, className) -> {
                    Map<String, String> config = configOf(className);
                    long retention = Long.parseLong(config.get("retention.ms"));
                    long segment = Long.parseLong(config.get("segment.ms"));
                    // The broker deletes whole segments, a segment only once its NEWEST record is
                    // past retention, and only when its next check runs: so the oldest record the
                    // broker can still hand a consumer is this old.
                    Duration redeliverable = Duration.ofMillis(retention + segment + checkInterval);
                    if (redeliverable.compareTo(window) >= 0) {
                        breaches.put(topic, redeliverable + " >= " + window);
                    }
                });

        assertThat(window).as("the dedupe window read from the code").isEqualTo(Duration.ofDays(14));
        assertThat(breaches)
                .as("the inbox must remember an event for longer than the broker can redeliver it,"
                        + " or a redelivery is a second effect (INV-IDEM-04)")
                .isEmpty();
    }

    @Test
    @DisplayName("every class deletes rather than compacts, and measures retention on the broker's clock")
    void retentionIsTimeBasedOnTheBrokersClock() {
        for (String className : classes().keySet()) {
            Map<String, String> config = configOf(className);
            assertThat(config.get("cleanup.policy"))
                    .as("%s: compaction keeps a key's last record past any window", className)
                    .isEqualTo("delete");
            assertThat(config.get("message.timestamp.type"))
                    .as("%s: retention must not depend on a producing replica's clock", className)
                    .isEqualTo("LogAppendTime");
        }
    }

    @Test
    @DisplayName("every class keeps three copies, two in sync, and partitions enough for the fleet")
    void everyClassIsReplicated() {
        int instances = Integer.parseInt(applicationProperty("${finapp.database.instances}"));
        for (Map.Entry<String, Map<String, Object>> entry : classes().entrySet()) {
            Map<String, Object> declared = entry.getValue();
            assertThat(declared.get("replication-factor")).as(entry.getKey()).isEqualTo(3);
            assertThat(configOf(entry.getKey()).get("min.insync.replicas"))
                    .as(entry.getKey())
                    .isEqualTo("2");
            assertThat((Integer) declared.get("partitions"))
                    .as("%s: as many partitions as the connection budget admits instances", entry.getKey())
                    .isGreaterThanOrEqualTo(instances);
        }
    }

    // -----------------------------------------------------------------
    // The broker's posture and the clients' settings agree
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the broker creates no topic, elects no unclean leader, and admits only SCRAM and ACLs")
    void theBrokerPostureHolds() {
        assertThat(required(broker, "auto.create.topics.enable")).isEqualTo("false");
        assertThat(required(broker, "unclean.leader.election.enable")).isEqualTo("false");
        assertThat(required(broker, "default.replication.factor")).isEqualTo("3");
        assertThat(required(broker, "min.insync.replicas")).isEqualTo("2");
        assertThat(required(broker, "allow.everyone.if.no.acl.found")).isEqualTo("false");
        assertThat(required(broker, "sasl.enabled.mechanisms"))
                .as("the mechanism the clients are built with")
                .isEqualTo(BrokerConnection.SASL_MECHANISM);
    }

    @Test
    @DisplayName("the consumers' session timeout is one the broker admits")
    void theSessionTimeoutIsAdmitted() {
        long minimum = Long.parseLong(required(broker, "group.min.session.timeout.ms"));
        long maximum = Long.parseLong(required(broker, "group.max.session.timeout.ms"));

        assertThat(KafkaEventReceiver.SESSION_TIMEOUT.toMillis()).isBetween(minimum, maximum);
    }

    // -----------------------------------------------------------------
    // Permissions, least privilege
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the application may produce and consume the catalogued topics, and nothing more")
    void theApplicationHoldsLeastPrivilege() {
        Map<String, Object> application = principal("application");

        assertThat(strings(application.get("topics")))
                .containsExactlyInAnyOrder("Describe", "Read", "Write");
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) application.get("groups");
        assertThat(strings(groups.get("operations"))).containsExactlyInAnyOrder("Describe", "Read");
        assertThat(InboxConsumers.groupIdOf("kyc"))
                .as("the groups the consumers join are the groups the permission covers")
                .startsWith((String) groups.get("prefix"));
        assertThat(strings(application.get("cluster")))
                .as("no cluster operation: it creates, alters and deletes nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("only the two principals exist, and neither may delete a topic or hold All")
    void noPrincipalMayDelete() {
        assertThat(principals().keySet()).containsExactlyInAnyOrder("application", "administrator");
        principals()
                .forEach(
                        (name, grant) -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> permissions = (Map<String, Object>) grant;
                            assertThat(strings(permissions.get("topics")))
                                    .as(name)
                                    .doesNotContain("Delete", "All");
                            assertThat(strings(permissions.get("cluster")))
                                    .as(name)
                                    .doesNotContain("All");
                        });
    }

    // -----------------------------------------------------------------

    /** EventingBeans' @Value on the inbox's retention, resolved as the application would. */
    private static Duration inboxRetention() {
        for (Method method : EventingBeans.class.getDeclaredMethods()) {
            for (Parameter parameter : method.getParameters()) {
                Value value = parameter.getAnnotation(Value.class);
                if (value != null && value.value().startsWith("${finapp.inbox.retention")) {
                    return Duration.parse(applicationProperty(value.value()));
                }
            }
        }
        throw new AssertionError("EventingBeans no longer reads finapp.inbox.retention");
    }

    /**
     * A placeholder resolved against the committed {@code application.yaml} alone - never this
     * machine's environment, which a deployment would supply and this test cannot see.
     */
    private static String applicationProperty(String placeholder) {
        try {
            MutablePropertySources sources = new MutablePropertySources();
            new YamlPropertySourceLoader()
                    .load("application", new FileSystemResource(RepositoryPaths.locate(APPLICATION)))
                    .forEach(sources::addLast);
            return new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(placeholder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> topics() {
        return (Map<String, String>) catalogue.get("topics");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> classes() {
        return (Map<String, Map<String, Object>>) catalogue.get("classes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> configOf(String className) {
        Map<String, Object> declared = classes().get(className);
        assertThat(declared).as("the class %s is declared", className).isNotNull();
        return (Map<String, String>) declared.get("config");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> principals() {
        return (Map<String, Object>) catalogue.get("principals");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> principal(String name) {
        return (Map<String, Object>) principals().get(name);
    }

    private static List<String> strings(Object list) {
        List<String> values = new ArrayList<>();
        for (Object value : (List<?>) list) {
            values.add((String) value);
        }
        return values;
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        assertThat(value).as("%s declares %s", BROKER, key).isNotNull();
        return value.trim();
    }

    private static String siteOf(JavaConstructorCall call) {
        return call.getOriginOwner().getName() + "#" + call.getOrigin().getName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String text) {
        return (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
    }

    /** For the scanner's own tests: a compiled fixture's bytes. */
    static byte[] bytesOf(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
