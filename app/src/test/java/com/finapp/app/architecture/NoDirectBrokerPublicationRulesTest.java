package com.finapp.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaMethodReference;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Nothing publishes to a broker except the outbox relay ({@code INV-EVT-01}, ADR-0005,
 * P0-TSK-019).
 *
 * <p><strong>Why a rule rather than a convention.</strong> There is no safe moment for domain
 * code to publish directly. Inside the transaction the broker cannot know whether it will
 * commit, so a rolled-back fact is announced as though it happened. After the transaction there
 * is a window in which the process dies having committed a fact nobody will ever hear about.
 * Both failures are silent, both are discovered later as a reconciliation break with no
 * explanation attached, and both look like perfectly ordinary code at review.
 *
 * <p>The outbox removes the window by making the fact and its publication record one commit.
 * That guarantee lasts exactly as long as nobody takes the shortcut — which is why the shortcut
 * fails the build.
 *
 * <p><strong>Matched by name, not by type.</strong> No broker client is on the classpath yet,
 * and a rule that only worked once someone added the dependency would be missing at precisely
 * the moment it is first needed. This is the same reasoning as the cross-module entity rule,
 * which matches the JPA annotation by name for the same reason.
 *
 * <p><strong>The exemption is the outbox package, not a module and not a class.</strong> The
 * original text here said "a module", and `P2-TSK-001` — the task that finally added the
 * exemption — narrowed it while keeping its reason: package granularity still lets the relay be
 * built out of several classes without anyone editing this rule, and a <em>module</em> exemption
 * would have let every platform concern — the audit writer, the API layer, the correlation
 * kernel — touch a broker client silently, which was never the intent. The relay <em>is</em> the
 * outbox package. Proven load-bearing in both directions below: a broker call in a sibling
 * platform package still fails, and one in the outbox package passes.
 */
@Tag("architecture")
// NOT a duplicate of the line above. ArchUnit runs @ArchTest fields under its OWN
// JUnit Platform engine, and that engine's descriptors read `ArchTag` - they cannot
// see JUnit's `Tag` at all. Without this every rule below carried no tag, so
// `unitTest` (which selects by EXCLUSION) took them and `architectureTest` (which
// selects by INCLUSION) got none. P1-TSK-025; TestTaxonomyTest now requires the pair.
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
class NoDirectBrokerPublicationRulesTest {

    /**
     * Broker client packages. Kafka is what ADR-0005 names; the others are here because "we
     * swapped the broker" must not silently remove the guarantee.
     */
    private static final List<String> BROKER_PACKAGES =
            List.of(
                    "org.apache.kafka.",
                    "org.springframework.kafka.",
                    "org.springframework.amqp.",
                    "com.rabbitmq.",
                    "software.amazon.awssdk.services.sns.",
                    "software.amazon.awssdk.services.sqs.",
                    "jakarta.jms.",
                    "javax.jms.");

    /**
     * The one package allowed to publish. Empty from P0-TSK-019 until `P2-TSK-001` built the
     * adapter — deliberately, so the exemption arrived with the component that needs it rather
     * than in advance of it.
     */
    private static final Set<String> PUBLISHING_PACKAGES =
            Set.of("com.finapp.platform.outbox");

    @ArchTest
    static void everyModuleWithProductionCodeIsAnalysed(JavaClasses imported) {
        Set<String> analysed =
                imported.stream()
                        .map(ProductionModules::of)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toUnmodifiableSet());

        assertThat(analysed)
                .as("every module with production classes must be within reach of the broker rule")
                .containsAll(ProductionModules.onClasspathWithProductionClasses());
    }

    @ArchTest
    static final ArchRule nothingPublishesToABrokerDirectly =
            everyProductionClassShould(touchNoBrokerClient())
                    .because(
                            "a fact and its publication record must commit together (INV-EVT-01); "
                                + "publishing directly either announces a rolled-back fact or "
                                + "loses a committed one, and both failures are silent");

    // ---------------------------------------------------------------------
    // Teeth
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the rule rejects a direct publish, however it is written")
    void ruleRejectsDirectPublication() {
        assertRejects(PublishesViaKafka.class);
        assertRejects(ConstructsAProducer.class);
        assertRejects(ReferencesSendAsAMethodReference.class);
    }

    @Test
    @DisplayName("writing to the outbox is allowed, so the rule does not forbid the right answer")
    void ruleAcceptsTheOutbox() {
        JavaClasses clean = new ClassFileImporter().importClasses(WritesToTheOutbox.class);

        assertThatCode(() -> nothingPublishesToABrokerDirectly.check(clean)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the exemption is one package, proven in both directions")
    void theExemptionIsExactlyTheOutboxPackage() {
        // The adapter itself must pass - the exemption is load-bearing, not decorative...
        assertThatCode(
                        () ->
                                nothingPublishesToABrokerDirectly.check(
                                        new ClassFileImporter()
                                                .importClasses(
                                                        com.finapp.platform.outbox
                                                                .KafkaEventPublisher.class)))
                .doesNotThrowAnyException();

        // ...and a broker call one package sideways must still fail, or "the outbox package" has
        // quietly become "the platform module" - the widening the P2-TSK-001 narrowing exists to
        // prevent. The fixture declares a platform sibling package; the rule keys on the package
        // name, which is exactly why this is provable from test sources.
        assertRejects(com.finapp.platform.brokerprobe.PublishesFromAPlatformSibling.class);
    }

    private static void assertRejects(Class<?> violation) {
        JavaClasses violating = new ClassFileImporter().importClasses(violation);

        assertThatThrownBy(() -> nothingPublishesToABrokerDirectly.check(violating))
                .as("a direct publish in %s must fail the build", violation.getSimpleName())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(violation.getSimpleName());
    }

    // ---------------------------------------------------------------------
    // Condition
    // ---------------------------------------------------------------------

    private static ArchRule everyProductionClassShould(ArchCondition<JavaClass> condition) {
        return classes().that().resideInAPackage("com.finapp..").should(condition);
    }

    private static ArchCondition<JavaClass> touchNoBrokerClient() {
        return new ArchCondition<>("not reach a message broker directly") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (PUBLISHING_PACKAGES.contains(javaClass.getPackageName())) {
                    return;
                }
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    report(events, javaClass, call.getTargetOwner().getFullName(), call.getDescription());
                }
                // A method reference is an invokedynamic rather than a call, so the loop above
                // cannot see it. P0-TSK-013's review found a rule bypassed by exactly this, and
                // a rule one syntax away from being bypassed is not enforcement.
                for (JavaMethodReference reference : javaClass.getMethodReferencesFromSelf()) {
                    report(events, javaClass, reference.getTargetOwner().getFullName(),
                            reference.getDescription());
                }
                for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                    report(events, javaClass, call.getTargetOwner().getFullName(), call.getDescription());
                }
                for (com.tngtech.archunit.core.domain.JavaField field : javaClass.getFields()) {
                    report(events, javaClass, field.getRawType().getFullName(),
                            "field " + field.getFullName() + " is " + field.getRawType().getName());
                }
            }
        };
    }

    private static void report(
            ConditionEvents events, JavaClass origin, String targetType, String description) {
        if (BROKER_PACKAGES.stream().anyMatch(targetType::startsWith)) {
            events.add(
                    SimpleConditionEvent.violated(
                            origin, description + " — publish through the outbox (INV-EVT-01)"));
        }
    }

    // ---------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------

    /** Stands in for a broker client, which is not on the classpath yet. */
    static final class FakeKafkaProducer {
        void send(String topic, byte[] value) {
            // A stand-in. The rule matches on package name, so the fixtures below use types in
            // a package named like a broker client rather than the real dependency.
        }
    }

    @SuppressWarnings("unused")
    static final class PublishesViaKafka {
        void announce(org.apache.kafka.clients.producer.Probe producer) {
            producer.send("transfers", new byte[0]);
        }
    }

    @SuppressWarnings("unused")
    static final class ConstructsAProducer {
        Object create() {
            return new org.apache.kafka.clients.producer.Probe();
        }
    }

    @SuppressWarnings("unused")
    static final class ReferencesSendAsAMethodReference {
        java.util.function.BiConsumer<String, byte[]> publisher(
                org.apache.kafka.clients.producer.Probe producer) {
            return producer::send;
        }
    }

    /** The shape the platform actually uses. */
    @SuppressWarnings("unused")
    static final class WritesToTheOutbox {
        void announce(
                com.finapp.platform.outbox.OutboxWriter<java.sql.Connection> outbox,
                java.sql.Connection unitOfWork,
                com.finapp.sharedkernel.event.EventEnvelope envelope) {
            outbox.write(unitOfWork, envelope, new byte[0], "application/json");
        }
    }
}
