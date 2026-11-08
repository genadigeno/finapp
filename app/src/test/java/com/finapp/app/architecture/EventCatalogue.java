package com.finapp.app.architecture;

import com.finapp.platform.testing.RepositoryPaths;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every event type, each version and its fields (`P15-TSK-027`, ADR-0122 decision 6).
 *
 * <p>Two of these exist at a test run: the one {@link EventFlows} reads from the compiled code, and
 * the committed {@value #RELEASED}, which stands for the last release. {@link #refusals} compares
 * them. The committed file is never edited by hand: it is the {@link #rolled} output of the release
 * procedure, which the test leaves under {@code build/}.
 *
 * <h2>What "additive" means here</h2>
 *
 * <p>Within a version, and from a version to the next one: no field removed, no field's kind
 * changed in a way a reader could notice, nothing every record carried made optional, no constant
 * value withdrawn from a literal. A field may be added: a reader on the previous release never
 * looks at it, and {@code EventPayload.fields} hands it over unread. Old records on the wire do not
 * carry an added field, so a reader on the new release must not require it either, which is why a
 * field added under an existing version becomes {@code required: false} when the catalogue is
 * rolled forward.
 *
 * <p>A version the code no longer writes stays in the catalogue: its records are still on the wire
 * until retention passes them, and a replay or a rollback must parse them. A change that cannot be
 * additive is a new event type, which consumers opt into by adding a handler, and the old type is
 * retired through the release record.
 *
 * <p><strong>The limit, said plainly.</strong> A field that keeps its name and its kind but starts
 * to carry a different quantity is invisible here. That is a change of meaning and needs a new
 * {@code eventVersion} and a reviewer; the build refuses only the meaning changes it can see: a
 * different enumeration behind a field, and a literal value withdrawn.
 */
public final class EventCatalogue {

    /** The committed catalogue of the last release. */
    public static final String RELEASED = "docs/events/event-catalogue.yaml";

    /** One field of one version. */
    public record Field(String kind, boolean required) {}

    /** One version of one event type. */
    public record Version(String producer, SortedMap<String, Field> fields) {}

    private final String release;
    private final SortedMap<String, SortedMap<Integer, Version>> events;

    private EventCatalogue(String release, SortedMap<String, SortedMap<Integer, Version>> events) {
        this.release = release;
        this.events = events;
    }

    public SortedMap<String, SortedMap<Integer, Version>> events() {
        return events;
    }

    public String release() {
        return release;
    }

    // -----------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------

    /** The catalogue the code writes: every closed write, joined per event type and version. */
    public static EventCatalogue fromProductionCode() {
        return of(flows().flows());
    }

    /** Everything {@link EventFlows} read from production, versions resolved reflectively. */
    static EventFlows.Result flows() {
        return EventFlows.inProductionCode(EventCatalogue::declaredVersion);
    }

    /** {@code <class>.of(<eventType>)}, the per-type declaration a site names, run for real. */
    static int declaredVersion(String declaringClass, String eventType) {
        try {
            Method of = Class.forName(declaringClass).getMethod("of", String.class);
            return (Integer) of.invoke(null, eventType);
        } catch (InvocationTargetException undeclared) {
            throw new IllegalStateException(eventType + " has no version in " + declaringClass, undeclared.getCause());
        } catch (ReflectiveOperationException unusable) {
            throw new IllegalStateException(declaringClass + " does not declare of(String)", unusable);
        }
    }

    /**
     * Every write whose version was not read through its own type's declaration, the sharing the
     * Phase 7 residue #49 named: a bare number, or the declaration of another type.
     */
    static SortedSet<String> sharedVersions(Collection<EventFlows.Flow> flows) {
        SortedSet<String> sharing = new TreeSet<>();
        for (EventFlows.Flow flow : flows) {
            if (!flow.eventType().equals(flow.versionDeclaredFor())) {
                sharing.add(flow.owner() + "#" + flow.method() + " writes " + flow.eventType() + " under "
                        + (flow.versionDeclaredFor() == null ? "a bare number" : "the version of " + flow.versionDeclaredFor()));
            }
        }
        return sharing;
    }

    static EventCatalogue of(Collection<EventFlows.Flow> flows) {
        SortedMap<String, List<EventFlows.Flow>> grouped = new TreeMap<>();
        for (EventFlows.Flow flow : flows) {
            grouped.computeIfAbsent(flow.eventType() + " " + flow.version(), ignored -> new ArrayList<>()).add(flow);
        }
        SortedMap<String, SortedMap<Integer, Version>> events = new TreeMap<>();
        grouped.values()
                .forEach(
                        sameVersion -> {
                            EventFlows.Flow first = sameVersion.get(0);
                            events.computeIfAbsent(first.eventType(), ignored -> new TreeMap<>())
                                    .put(first.version(), joined(sameVersion));
                        });
        return new EventCatalogue("the code", events);
    }

    /** One version from every path that writes it: a field missing from one path is not required. */
    private static Version joined(List<EventFlows.Flow> flows) {
        var producers = new TreeSet<String>();
        var names = new TreeSet<String>();
        for (EventFlows.Flow flow : flows) {
            producers.add(flow.producer());
            names.addAll(flow.fields().keySet());
        }
        if (producers.size() != 1) {
            throw new IllegalStateException(
                    flows.get(0).eventType() + " is produced by " + producers + ": one type has one owner and one topic");
        }
        SortedMap<String, Field> fields = new TreeMap<>();
        for (String name : names) {
            boolean required = true;
            var kinds = new TreeSet<String>();
            for (EventFlows.Flow flow : flows) {
                EventFlows.Field field = flow.fields().get(name);
                if (field == null) {
                    required = false;
                } else {
                    kinds.add(field.kind());
                    required &= field.required();
                }
            }
            fields.put(name, new Field(EventFlows.mergedKind(kinds), required));
        }
        return new Version(producers.first(), fields);
    }

    /** The committed catalogue. */
    public static EventCatalogue released() {
        return parse(RepositoryPaths.read(RELEASED));
    }

    @SuppressWarnings("unchecked")
    static EventCatalogue parse(String yaml) {
        Map<String, Object> document = (Map<String, Object>) new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
        SortedMap<String, SortedMap<Integer, Version>> events = new TreeMap<>();
        Map<String, Object> declared = (Map<String, Object>) document.get("events");
        declared.forEach(
                (type, versions) -> {
                    SortedMap<Integer, Version> parsed = new TreeMap<>();
                    ((Map<Integer, Object>) versions)
                            .forEach(
                                    (version, body) -> {
                                        Map<String, Object> shape = (Map<String, Object>) body;
                                        SortedMap<String, Field> fields = new TreeMap<>();
                                        ((Map<String, Object>) shape.get("fields"))
                                                .forEach(
                                                        (name, field) -> {
                                                            Map<String, Object> read = (Map<String, Object>) field;
                                                            fields.put(
                                                                    name,
                                                                    new Field(
                                                                            (String) read.get("kind"),
                                                                            (Boolean) read.get("required")));
                                                        });
                                        parsed.put(version, new Version((String) shape.get("producer"), fields));
                                    });
                    events.put(type, parsed);
                });
        return new EventCatalogue(Objects.toString(document.get("release")), events);
    }

    /** Deterministic text: one field to a line, so a release's diff reads as what changed. */
    public String render() {
        StringBuilder out = new StringBuilder();
        out.append("# The event catalogue of the last release (ADR-0122 decision 6; EVENT_ARCHITECTURE.md, Schema evolution).\n");
        out.append("# Generated: EventCatalogueTest leaves the next one under app/build/event-catalogue/. Never edit by hand.\n");
        out.append("release: \"").append(release).append("\"\n");
        out.append("events:\n");
        events.forEach(
                (type, versions) -> {
                    out.append("  \"").append(type).append("\":\n");
                    versions.forEach(
                            (number, version) -> {
                                out.append("    ").append(number).append(":\n");
                                out.append("      producer: \"").append(version.producer()).append("\"\n");
                                out.append("      fields:\n");
                                version.fields()
                                        .forEach(
                                                (name, field) ->
                                                        out.append("        \"")
                                                                .append(name)
                                                                .append("\": {kind: \"")
                                                                .append(field.kind())
                                                                .append("\", required: ")
                                                                .append(field.required())
                                                                .append("}\n"));
                            });
                });
        return out.toString();
    }

    // -----------------------------------------------------------------
    // Comparing
    // -----------------------------------------------------------------

    /**
     * Every way {@code current} is not an additive evolution of this, the last release's catalogue.
     *
     * <p>Empty means the previous release's consumers can read everything the code now writes.
     */
    public List<String> refusals(EventCatalogue current) {
        return refusals(current, Set.of());
    }

    /**
     * As {@link #refusals(EventCatalogue)}, with the types this release retires: named in the release
     * record, so a type's last producer cannot simply disappear. A type retired and still written is
     * refused, so a stale entry cannot pass for the next release's.
     */
    public List<String> refusals(EventCatalogue current, Set<String> retired) {
        List<String> refusals = new ArrayList<>();
        events.forEach(
                (type, releasedVersions) -> {
                    SortedMap<Integer, Version> currentVersions = current.events.get(type);
                    if (currentVersions == null) {
                        if (!retired.contains(type)) {
                            refusals.add(type + ": no producer writes it any more, and a consumer of the last release still expects it");
                        }
                        return;
                    }
                    if (retired.contains(type)) {
                        refusals.add(type + ": recorded as retired, and it is still written");
                    }
                    int newestReleased = releasedVersions.lastKey();
                    if (currentVersions.lastKey() < newestReleased) {
                        refusals.add(type + ": its newest version went backwards, from " + newestReleased + " to " + currentVersions.lastKey());
                    }
                    currentVersions.forEach(
                            (number, version) -> {
                                Version basis = releasedVersions.get(number);
                                if (basis != null) {
                                    compare(type + " v" + number, basis, version, refusals);
                                } else if (number < newestReleased) {
                                    refusals.add(type + " v" + number + ": a version below the last release's newest was never released");
                                } else {
                                    compare(type + " v" + number + " (over v" + newestReleased + ")",
                                            releasedVersions.get(newestReleased), version, refusals);
                                }
                            });
                });
        return refusals;
    }

    private static void compare(String label, Version released, Version current, List<String> refusals) {
        if (!released.producer().equals(current.producer())) {
            refusals.add(label + ": moved from producer " + released.producer() + " to " + current.producer()
                    + ": a consumer subscribes to the topic of the first");
        }
        released.fields()
                .forEach(
                        (name, field) -> {
                            Field now = current.fields().get(name);
                            if (now == null) {
                                refusals.add(label + ": field " + name + " was removed");
                                return;
                            }
                            if (!compatibleKinds(field.kind(), now.kind())) {
                                refusals.add(label + ": field " + name + " changed from " + field.kind() + " to " + now.kind());
                            }
                            if (field.required() && !now.required()) {
                                refusals.add(label + ": field " + name + " was always written and now is not");
                            }
                        });
    }

    /**
     * Whether a reader of {@code released} still reads {@code current}: the same kind; an {@code
     * identifier} narrowed to something more exact; or a literal that kept every value it had.
     */
    static boolean compatibleKinds(String released, String current) {
        if (released.equals(current) || released.equals("identifier")) {
            return true;
        }
        if (released.startsWith("literal(") && current.startsWith("literal(")) {
            return values(current).containsAll(values(released));
        }
        return false;
    }

    private static List<String> values(String literal) {
        return Arrays.asList(literal.substring("literal(".length(), literal.length() - 1).split("\\|", -1));
    }

    // -----------------------------------------------------------------
    // Rolling forward, at a release
    // -----------------------------------------------------------------

    /**
     * What the release procedure commits: this catalogue with {@code current} added to it.
     *
     * <p>A version the code stopped writing is kept, because its records are still on the wire. A
     * field added under a version that already existed is not required, because the records written
     * before it do not carry it.
     */
    public EventCatalogue rolled(EventCatalogue current, Set<String> retired, String label) {
        SortedMap<String, SortedMap<Integer, Version>> next = new TreeMap<>();
        events.forEach(
                (type, versions) -> {
                    if (!retired.contains(type)) {
                        next.put(type, new TreeMap<>(versions));
                    }
                });
        current.events.forEach(
                (type, versions) -> {
                    SortedMap<Integer, Version> into = next.computeIfAbsent(type, ignored -> new TreeMap<>());
                    versions.forEach(
                            (number, version) -> {
                                Version basis = into.get(number);
                                into.put(number, basis == null ? version : carried(basis, version));
                            });
                });
        return new EventCatalogue(label, next);
    }

    private static Version carried(Version basis, Version current) {
        SortedMap<String, Field> fields = new TreeMap<>();
        current.fields()
                .forEach(
                        (name, field) ->
                                fields.put(name, basis.fields().containsKey(name) ? field : new Field(field.kind(), false)));
        basis.fields().forEach(fields::putIfAbsent);
        return new Version(current.producer(), fields);
    }
}
