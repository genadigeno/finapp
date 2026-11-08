package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.HandlerSet.Handler;
import com.finapp.app.architecture.HandlerSet.ReleaseRecord;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.outbox.KafkaEventPublisher;
import com.finapp.platform.testing.RepositoryPaths;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * No record is lost because a release changed which handlers a consumer group holds
 * (`P15-TSK-027`, ADR-0122 decision 6, the transition's I-2).
 *
 * <p>The handler set is read from the code: every {@code @Bean} method returning an {@link
 * InboxEventHandler} is invoked with collaborators that do nothing, and the handler it composes is
 * asked its topic, type and consumer name; the group comes from {@code InboxConsumers}' own
 * derivation. Nothing here lists the handlers.
 *
 * <p>The diff's rules are held on planted sets by {@code HandlerSetDiffTest}; this class holds them
 * on the real code.
 */
@Tag("architecture")
@DisplayName("the handler set is catalogued from the code, and every change to it is recorded (P15-TSK-027)")
class HandlerSetCatalogueTest {

    private static final String TOPICS = "infra/kafka/topics.yaml";

    static final Path GENERATED = Path.of("build", "event-catalogue", "handler-catalogue.yaml");

    private static Production production;
    private static SortedSet<Handler> current;
    private static EventCatalogue events;

    /** The production import and the bean methods found in it. */
    private record Production(List<Method> beanMethods, Set<String> handlerClasses, Set<String> composedClasses) {}

    @BeforeAll
    static void read() throws IOException {
        production = imported();
        List<InboxEventHandler> composed = new ArrayList<>();
        Set<String> composedClasses = new TreeSet<>();
        for (Method method : production.beanMethods()) {
            InboxEventHandler handler = HandlerSet.composedBy(method);
            composed.add(handler);
            composedClasses.add(handler.getClass().getName());
        }
        production = new Production(production.beanMethods(), production.handlerClasses(), composedClasses);
        current = HandlerSet.of(composed);
        events = EventCatalogue.fromProductionCode();
        Files.createDirectories(GENERATED.getParent());
        Files.writeString(GENERATED, HandlerSet.render(current, "the next release"), StandardCharsets.UTF_8);
    }

    private static Production imported() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.finapp");
        List<Method> beanMethods = new ArrayList<>();
        Set<String> handlerClasses = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.isAssignableTo(InboxEventHandler.class) && !javaClass.isInterface()) {
                handlerClasses.add(javaClass.getName());
            }
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.isAnnotatedWith(Bean.class) && method.getRawReturnType().isAssignableTo(InboxEventHandler.class)) {
                    beanMethods.add(method.reflect());
                }
            }
        }
        return new Production(beanMethods, handlerClasses, Set.of());
    }

    @Test
    @DisplayName("every handler class in production is composed by a bean, and there are real ones")
    void everyHandlerClassIsComposed() {
        assertThat(production.handlerClasses()).as("the handler classes production holds").isNotEmpty();
        assertThat(production.composedClasses())
                .as("a handler class no bean composes consumes nothing, and this diff could not see it")
                .containsExactlyInAnyOrderElementsOf(production.handlerClasses());
        assertThat(current).as("one consumption per bean").hasSize(production.beanMethods().size());
        assertThat(current.stream().map(Handler::group).collect(Collectors.toSet()))
                .as("the groups, one per consuming module")
                .contains("finapp.kyc", "finapp.risk", "finapp.bnpl", "finapp.lending");
    }

    @Test
    @DisplayName("what the code registers differs from the last release only by steps the release records")
    void everyChangeToTheHandlerSetIsRecorded() {
        assertThat(HandlerSet.refusals(HandlerSet.released(), current, ReleaseRecord.committed()))
                .as("a handler added or removed without a safe way through the rollout and any rollback;"
                        + " see EVENT_ARCHITECTURE.md, Adding a handler")
                .isEmpty();
    }

    @Test
    @DisplayName("every handler subscribes to a declared topic that carries its type")
    void everyHandlerListensToSomethingReal() {
        Map<String, Set<String>> typesByTopic = new TreeMap<>();
        events.events()
                .forEach(
                        (type, versions) ->
                                versions.values()
                                        .forEach(
                                                version ->
                                                        typesByTopic
                                                                .computeIfAbsent(
                                                                        KafkaEventPublisher.topicFor(version.producer()),
                                                                        ignored -> new TreeSet<>())
                                                                .add(type)));

        assertThat(HandlerSet.unsubscribable(current, declaredTopics(), typesByTopic))
                .as("a handler naming an undeclared topic, or a type its topic never carries, consumes nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("a new version of a type a released handler consumes waits until the consumer has learned it")
    void noConsumedTypeOutrunsItsConsumers() {
        assertThat(HandlerSet.unlearnedVersions(EventCatalogue.released(), events, HandlerSet.released(), ReleaseRecord.committed()))
                .as("during a rollout an N - 1 replica meets N's records")
                .isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> declaredTopics() {
        Map<String, Object> catalogue =
                (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(RepositoryPaths.read(TOPICS));
        return ((Map<String, String>) catalogue.get("topics")).keySet();
    }
}
