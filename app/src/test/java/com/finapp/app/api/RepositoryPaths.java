package com.finapp.app.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates a repository file from a test, whatever the working directory happens to be.
 *
 * <p>Gradle runs a test with the module directory as its working directory; an IDE may use the
 * repository root or something else again. A path relative to an assumed working directory
 * therefore fails in a way that reads as "the document is missing" rather than "the test is
 * misconfigured", which is exactly the wrong diagnosis. Walking upward removes the assumption.
 */
final class RepositoryPaths {

    private RepositoryPaths() {}

    /** @throws IllegalStateException if the file is nowhere above the working directory */
    static Path locate(String relativePath) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relativePath);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException(
                "Could not find " + relativePath + " above " + Path.of("").toAbsolutePath());
    }

    static String read(String relativePath) {
        Path path = locate(relativePath);
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + path, e);
        }
    }
}
