package com.finapp.app.telemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The configuration register, read as the application reads it (`P15-TSK-031`, ADR-0125 decision 3): {@code
 * docs/architecture/CONFIGURATION_REGISTER.md}, embedded in this build by {@code processResources} exactly as it stands in the
 * repository. One list, so the fingerprint's inputs are the rows a person reviewed rather than a copy of them that can fall behind;
 * {@code ConfigurationRegisterEmbeddedTest} holds the embedded bytes equal to the document.
 *
 * <p>A build whose register is missing or cannot be read refuses to start: a fingerprint over nothing would agree across the whole
 * fleet and say nothing.
 */
public final class ConfigurationRegister {

    /** A value that changes a financial outcome, limit, price, route or risk judgement. */
    public static final String FINANCIAL = "FINANCIAL";

    /** Anything else. {@code SECRET} is {@code SECRET_MANAGEMENT.md} section 3.2's inventory, and never a row here. */
    public static final String OPERATIONAL = "OPERATIONAL";

    /** The default of a property nothing sets and no code defaults: absent, a feature off. */
    public static final String NONE = "(none)";

    /** The default of a property whose code default is the empty string. */
    public static final String BLANK = "(blank)";

    /** A code default that is an address on this machine: not written out, because no endpoint is written in a document. */
    public static final String LOOPBACK = "(loopback)";

    /** On the classpath beside this class: written by {@code processResources}. */
    static final String RESOURCE = "configuration-register.md";

    private static final String HEADER = "| Property | Variable | Class | Default | local | test | ci | staging | production | A mixed fleet |";
    private static final int COLUMNS = 10;

    private ConfigurationRegister() {}

    /** One row of the table, each cell as written, backticks removed. */
    public record Row(String property, String variable, String klass, String defaultCell, String local, String test, String ci, String staging,
            String production, String mixedFleet) {

        /** The value that applies when nothing sets the property: empty for a property that then has none. */
        public Optional<String> defaultValue() {
            if (defaultCell.equals(NONE)) {
                return Optional.empty();
            }
            return Optional.of(defaultCell.equals(BLANK) ? "" : defaultCell);
        }
    }

    /** Every row of the register embedded in this build. */
    static List<Row> embedded() {
        try (InputStream resource = ConfigurationRegister.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException(RESOURCE + " is not in this build: the configuration fingerprint has nothing to read");
            }
            return rows(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + RESOURCE, e);
        }
    }

    /**
     * The rows of the register's table.
     *
     * @throws IllegalStateException if the document has no such table, or a row does not have every column
     */
    public static List<Row> rows(String document) {
        Objects.requireNonNull(document, "document must not be null");
        String[] lines = document.split("\\R", -1);
        int index = 0;
        while (index < lines.length && !lines[index].strip().equals(HEADER)) {
            index++;
        }
        if (index == lines.length) {
            throw new IllegalStateException("the configuration register has no table headed " + HEADER);
        }
        List<Row> rows = new ArrayList<>();
        for (index += 2; index < lines.length && lines[index].startsWith("|"); index++) {
            List<String> cells = cells(lines[index]);
            if (cells.size() != COLUMNS) {
                throw new IllegalStateException("a row of the configuration register does not have " + COLUMNS + " columns: " + lines[index]);
            }
            rows.add(new Row(cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5), cells.get(6), cells.get(7), cells.get(8),
                    cells.get(9)));
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("the configuration register's table has no rows");
        }
        return List.copyOf(rows);
    }

    private static List<String> cells(String line) {
        String trimmed = line.strip();
        if (!trimmed.startsWith("|") || !trimmed.endsWith("|")) {
            throw new IllegalStateException("a row of the configuration register is not a table row: " + line);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.substring(1, trimmed.length() - 1).split("\\|", -1)) {
            cells.add(cell.replace("`", "").strip());
        }
        return cells;
    }
}
