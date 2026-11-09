package com.finapp.app.database;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Each schema's latest version as this build knows it (`P15-TSK-005`): written into the application by the build,
 * from the migrations actually on its runtime classpath ({@code app/build.gradle.kts}, {@code expectedSchemaVersions}).
 * There is no list of schemas or numbers in the source, and {@code ExpectedSchemaVersionsTest} holds the embedded file
 * equal to a scan of the classpath and a walk of the repository.
 *
 * <p>A build with no such file, or an empty one, is a defective artefact: {@link #embedded()} refuses it and the
 * instance does not start. "No expectation" must never read as "nothing is older".
 */
public final class ExpectedSchemaVersions {

    /** Beside this class, in the application's own resources. */
    static final String RESOURCE = "expected-schema-versions.properties";

    private final SortedMap<String, SchemaVersion> versions;

    ExpectedSchemaVersions(Map<String, SchemaVersion> versions) {
        if (Objects.requireNonNull(versions, "versions").isEmpty()) {
            throw new IllegalStateException("no schema is expected: the build embedded nothing");
        }
        this.versions = Collections.unmodifiableSortedMap(new TreeMap<>(versions));
    }

    /** What the build embedded. */
    public static ExpectedSchemaVersions embedded() {
        try (InputStream resource = ExpectedSchemaVersions.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(RESOURCE + " is not in this build: the schema-version guard has nothing to expect");
            }
            return parse(new InputStreamReader(resource, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    static ExpectedSchemaVersions parse(Reader text) throws IOException {
        Properties properties = new Properties();
        properties.load(text);
        SortedMap<String, SchemaVersion> parsed = new TreeMap<>();
        properties.stringPropertyNames().forEach(schema -> parsed.put(schema, SchemaVersion.parse(properties.getProperty(schema))));
        return new ExpectedSchemaVersions(parsed);
    }

    /** Each schema this build expects, with its latest version. */
    public SortedMap<String, SchemaVersion> versions() {
        return versions;
    }

    /**
     * The schemas behind this build, and only those. A schema <em>ahead</em> of it is not here: during a rollout or a
     * rollback a replica of release N − 1 runs on release N's newer schema and must stay ready. A schema with no applied
     * version at all is behind.
     *
     * @param applied each schema's highest applied version
     * @return one description per schema behind, for the log; empty when every schema is at or past what this build expects
     */
    List<String> behindIn(Map<String, SchemaVersion> applied) {
        List<String> behind = new ArrayList<>();
        versions.forEach((schema, expected) -> {
            SchemaVersion found = applied.get(schema);
            if (found == null) {
                behind.add(schema + " has no applied version, expected " + expected);
            } else if (found.compareTo(expected) < 0) {
                behind.add(schema + " is at " + found + ", expected " + expected);
            }
        });
        return List.copyOf(behind);
    }
}
