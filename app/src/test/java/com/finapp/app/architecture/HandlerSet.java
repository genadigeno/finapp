package com.finapp.app.architecture;

import com.finapp.app.eventing.InboxConsumers;
import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.testing.RepositoryPaths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.objenesis.ObjenesisStd;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Who listens to what, and what a release must record when that changes (`P15-TSK-027`, ADR-0122
 * decision 6, the transition's I-2).
 *
 * <h2>Why payload compatibility does not cover this</h2>
 *
 * <p>{@code KafkaEventReceiver} acknowledges a record no registered handler wants ({@code
 * UNHANDLED}). Groups are per module and every topic carries every type its producer writes. So
 * while N - 1 and N share a group, an N - 1 replica commits, unseen, a record of a type only N
 * handles; and after a rollback every such record is committed unseen. A handler <em>removed</em>
 * in N opens the same hole from the other side: N acknowledges the type unseen, and a rollback to N
 * - 1 never gets those records back. Hence the diff below runs both ways.
 *
 * <p>A release that changes the set records, in {@value #RECORD}, either the audited replay of the
 * type over the rollout window and any rollback window (the inbox deduplicates what N applied) or,
 * for a handler in a new group, the starting offsets chosen for it. <strong>The build proves the
 * release recorded the step; it cannot prove the step was run.</strong> The replay's own audit
 * record is that, and {@code P15-TSK-012}'s handler-window case is the rehearsal of it.
 */
public final class HandlerSet {

    /** The committed handler set of the last release. */
    public static final String RELEASED = "docs/events/handler-catalogue.yaml";

    /** What the coming release records. Emptied when the catalogues are rolled at a release. */
    public static final String RECORD = "docs/events/release-record.yaml";

    /** One registered handler. */
    public record Handler(String group, String topic, String eventType, String consumerName)
            implements Comparable<Handler> {

        @Override
        public int compareTo(Handler other) {
            return toString().compareTo(other.toString());
        }

        @Override
        public String toString() {
            return group + " " + topic + " " + eventType + " " + consumerName;
        }
    }

    /** An added or removed handler's recorded step. */
    public record HandlerStep(
            Handler handler,
            boolean added,
            String replayedType,
            Set<String> replayWindows,
            String startingOffsets,
            boolean naturallyIdempotent) {}

    /** A new {@code eventVersion} of a consumed type, and the release whose consumers learned it. */
    public record VersionStep(String eventType, int version, String consumersLearnedItIn) {}

    /** What the coming release records. */
    public record ReleaseRecord(List<HandlerStep> handlers, List<VersionStep> versions) {

        public static ReleaseRecord committed() {
            return parse(RepositoryPaths.read(RECORD));
        }

        @SuppressWarnings("unchecked")
        public static ReleaseRecord parse(String yaml) {
            Map<String, Object> document = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            List<HandlerStep> handlers = new ArrayList<>();
            for (Map<String, Object> entry : (List<Map<String, Object>>) document.get("handlers")) {
                Map<String, Object> replay = (Map<String, Object>) entry.get("replay");
                Map<String, Object> newGroup = (Map<String, Object>) entry.get("newGroup");
                handlers.add(
                        new HandlerStep(
                                new Handler(
                                        (String) entry.get("group"),
                                        (String) entry.get("topic"),
                                        (String) entry.get("eventType"),
                                        (String) entry.get("consumerName")),
                                !"removed".equals(entry.get("change")),
                                replay == null ? null : (String) replay.get("eventType"),
                                replay == null
                                        ? Set.of()
                                        : Set.copyOf((List<String>) replay.getOrDefault("windows", List.of())),
                                newGroup == null ? null : Objects.toString(newGroup.get("startingOffsets"), null),
                                newGroup != null && Boolean.TRUE.equals(newGroup.get("naturallyIdempotent"))));
            }
            List<VersionStep> versions = new ArrayList<>();
            for (Map<String, Object> entry : (List<Map<String, Object>>) document.get("versions")) {
                versions.add(
                        new VersionStep(
                                (String) entry.get("eventType"),
                                (Integer) entry.get("version"),
                                Objects.toString(entry.get("consumersLearnedItIn"), "")));
            }
            return new ReleaseRecord(List.copyOf(handlers), List.copyOf(versions));
        }
    }

    private HandlerSet() {}

    // -----------------------------------------------------------------
    // Reading the two sets
    // -----------------------------------------------------------------

    /** The handlers these bean methods compose, built for real and asked for their identity. */
    static SortedSet<Handler> of(Collection<InboxEventHandler> handlers) {
        SortedSet<Handler> set = new TreeSet<>();
        InboxConsumers.byModule(List.copyOf(handlers))
                .forEach(
                        (module, moduleHandlers) ->
                                moduleHandlers.forEach(
                                        handler ->
                                                set.add(
                                                        new Handler(
                                                                InboxConsumers.groupIdOf(module),
                                                                handler.topic(),
                                                                handler.eventType(),
                                                                handler.consumerName()))));
        return set;
    }

    /** One bean method invoked with collaborators that do nothing: a handler's identity needs none. */
    static InboxEventHandler composedBy(Method beanMethod) {
        try {
            beanMethod.setAccessible(true);
            var configuration = beanMethod.getDeclaringClass();
            Object beans = stub(configuration);
            Object[] arguments = new Object[beanMethod.getParameterCount()];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = stub(beanMethod.getParameterTypes()[i]);
            }
            return (InboxEventHandler) beanMethod.invoke(beans, arguments);
        } catch (InvocationTargetException failed) {
            throw new IllegalStateException(beanMethod + " could not be composed for its identity", failed.getCause());
        } catch (ReflectiveOperationException unusable) {
            throw new IllegalStateException(beanMethod + " could not be invoked", unusable);
        }
    }

    private static Object stub(Class<?> type) {
        if (type.isPrimitive()) {
            return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0);
        }
        if (type.isInterface()) {
            return Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> null);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type == Clock.class) {
            return Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        }
        return new ObjenesisStd().newInstance(type);
    }

    /** The committed handler set. */
    public static SortedSet<Handler> released() {
        return parse(RepositoryPaths.read(RELEASED));
    }

    @SuppressWarnings("unchecked")
    static SortedSet<Handler> parse(String yaml) {
        Map<String, Object> document = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        SortedSet<Handler> set = new TreeSet<>();
        for (Map<String, String> entry : (List<Map<String, String>>) document.get("handlers")) {
            set.add(new Handler(entry.get("group"), entry.get("topic"), entry.get("eventType"), entry.get("consumerName")));
        }
        return set;
    }

    /** Deterministic text: one handler to a line. */
    static String render(SortedSet<Handler> handlers, String release) {
        StringBuilder out = new StringBuilder();
        out.append("# The handler set of the last release (ADR-0122 decision 6; EVENT_ARCHITECTURE.md, Adding a handler).\n");
        out.append("# Generated: HandlerSetCatalogueTest leaves the next one under app/build/event-catalogue/. Never edit by hand.\n");
        out.append("release: \"").append(release).append("\"\n");
        out.append("handlers:\n");
        handlers.forEach(
                handler ->
                        out.append("  - {group: \"")
                                .append(handler.group())
                                .append("\", topic: \"")
                                .append(handler.topic())
                                .append("\", eventType: \"")
                                .append(handler.eventType())
                                .append("\", consumerName: \"")
                                .append(handler.consumerName())
                                .append("\"}\n"));
        return out.toString();
    }

    // -----------------------------------------------------------------
    // The diff
    // -----------------------------------------------------------------

    /**
     * Every change to the handler set this release has not recorded a safe way through.
     *
     * <p>A step recorded for a handler that did not change is refused too: a stale record would
     * otherwise pass for the next release's.
     */
    static List<String> refusals(SortedSet<Handler> released, SortedSet<Handler> current, ReleaseRecord record) {
        List<String> refusals = new ArrayList<>();
        Set<String> releasedGroups = released.stream().map(Handler::group).collect(Collectors.toSet());
        Set<Handler> added = new TreeSet<>(current);
        added.removeAll(released);
        Set<Handler> removed = new TreeSet<>(released);
        removed.removeAll(current);

        for (Handler handler : added) {
            HandlerStep step = stepFor(record, handler, true);
            if (step == null) {
                refusals.add(handler + ": added with neither a recorded replay nor a new group with recorded offsets");
            } else if (releasedGroups.contains(handler.group())) {
                checkReplay(handler, step, Set.of("rollout", "rollback"), refusals);
            } else {
                checkNewGroup(handler, step, refusals);
            }
        }
        for (Handler handler : removed) {
            HandlerStep step = stepFor(record, handler, false);
            if (step == null) {
                refusals.add(handler + ": removed with no recorded replay over a rollback window"
                        + " (a rollback to the last release would never see what this one acknowledged)");
            } else {
                checkReplay(handler, step, Set.of("rollback"), refusals);
            }
        }
        for (HandlerStep step : record.handlers()) {
            if (!(step.added() ? added : removed).contains(step.handler())) {
                refusals.add(step.handler() + ": recorded as " + (step.added() ? "added" : "removed") + ", and it was not");
            }
        }
        return refusals;
    }

    private static HandlerStep stepFor(ReleaseRecord record, Handler handler, boolean added) {
        return record.handlers().stream()
                .filter(step -> step.added() == added && step.handler().equals(handler))
                .findFirst()
                .orElse(null);
    }

    private static void checkReplay(Handler handler, HandlerStep step, Set<String> windows, List<String> refusals) {
        if (step.replayedType() == null) {
            refusals.add(handler + ": in a group the last release already had, so it needs a recorded replay");
            return;
        }
        if (!handler.eventType().equals(step.replayedType())) {
            refusals.add(handler + ": the recorded replay is of " + step.replayedType() + ", another type");
        }
        if (!step.replayWindows().containsAll(windows)) {
            refusals.add(handler + ": the recorded replay must cover " + new TreeSet<>(windows) + ", and covers "
                    + new TreeSet<>(step.replayWindows()));
        }
    }

    private static void checkNewGroup(Handler handler, HandlerStep step, List<String> refusals) {
        String offsets = step.startingOffsets();
        if (offsets == null) {
            refusals.add(handler + ": in a new group, which needs its starting offsets recorded");
            return;
        }
        if (offsets.equals("earliest")) {
            if (!step.naturallyIdempotent()) {
                refusals.add(handler + ": earliest replays the whole retention, refused unless the handler is naturally idempotent");
            }
        } else if (!offsets.equals("latest") && !isInstant(offsets)) {
            refusals.add(handler + ": starting offsets must be earliest, latest or an instant, not " + offsets);
        }
    }

    private static boolean isInstant(String value) {
        try {
            Instant.parse(value);
            return true;
        } catch (DateTimeParseException notOne) {
            return false;
        }
    }

    /**
     * A handler that would subscribe to nothing: its topic undeclared in the topic catalogue, or
     * its type never written to that topic.
     */
    static List<String> unsubscribable(
            SortedSet<Handler> current, Set<String> declaredTopics, Map<String, Set<String>> typesByTopic) {
        List<String> refusals = new ArrayList<>();
        for (Handler handler : current) {
            if (!declaredTopics.contains(handler.topic())) {
                refusals.add(handler + ": its topic is not in the topic catalogue");
            } else if (!typesByTopic.getOrDefault(handler.topic(), Set.of()).contains(handler.eventType())) {
                refusals.add(handler + ": nothing writes that type to that topic");
            }
        }
        return refusals;
    }

    /**
     * A new {@code eventVersion} of a type some handler of the last release consumes.
     *
     * <p>Such a handler either refuses a version it does not know ({@code RiskSignalIntake} throws,
     * stalling its partition) or ignores it (the origination hints). During a rollout an N - 1 replica
     * meets N's records, so the consumer has to have learned the version one release earlier.
     */
    static List<String> unlearnedVersions(
            EventCatalogue released, EventCatalogue current, SortedSet<Handler> releasedHandlers, ReleaseRecord record) {
        Set<String> consumed = releasedHandlers.stream().map(Handler::eventType).collect(Collectors.toSet());
        List<String> refusals = new ArrayList<>();
        current.events()
                .forEach(
                        (type, versions) -> {
                            var basis = released.events().get(type);
                            if (basis == null || !consumed.contains(type)) {
                                return;
                            }
                            versions.keySet().stream()
                                    .filter(version -> version > basis.lastKey())
                                    .filter(version -> record.versions().stream()
                                            .noneMatch(step -> step.eventType().equals(type)
                                                    && step.version() == version
                                                    && !step.consumersLearnedItIn().isBlank()))
                                    .forEach(version -> refusals.add(type + " v" + version
                                            + ": a handler of the last release consumes this type and has not learned the version"));
                        });
        return refusals;
    }
}
