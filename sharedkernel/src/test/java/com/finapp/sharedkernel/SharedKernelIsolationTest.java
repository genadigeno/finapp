package com.finapp.sharedkernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.util.Arrays;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies the two structural guarantees {@code sharedkernel} exists to provide
 * (P0-TSK-002 acceptance criteria).
 *
 * <p>These assertions are about the <em>build</em>, not about behaviour. They fail if
 * someone adds a Spring dependency or an upward module dependency to
 * {@code sharedkernel/build.gradle.kts} — which is the moment the damage is cheap to undo,
 * rather than months later when the financial kernel can no longer be tested without a
 * container.
 *
 * <p>P0-TSK-007 adds ArchUnit rules covering what Gradle cannot express (cross-module
 * internals, entity references). This test covers what the classpath can prove.
 */
class SharedKernelIsolationTest {

    @Test
    @DisplayName("no Spring type is loadable from sharedkernel")
    void hasNoSpringOnTheClasspath() {
        List<String> springTypes = List.of(
                "org.springframework.context.ApplicationContext",
                "org.springframework.core.env.Environment",
                "org.springframework.beans.factory.BeanFactory",
                "org.springframework.boot.SpringApplication");

        for (String type : springTypes) {
            assertThatThrownBy(() -> Class.forName(type))
                    .as("sharedkernel must not see %s", type)
                    .isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    @DisplayName("no Spring artefact is on the classpath at all")
    void hasNoSpringArtefact() {
        // Class.forName above proves the well-known entry points are absent. This is the
        // broader check: no Spring jar of any kind, including one arriving transitively.
        assertThat(classpathEntries())
                .as("classpath entries resembling a Spring artefact")
                .noneMatch(entry -> {
                    Path fileName = Path.of(entry).getFileName();
                    return fileName != null && fileName.toString().startsWith("spring-");
                });
    }

    @Test
    @DisplayName("sharedkernel depends on no module above it")
    void dependsOnNoModuleAboveIt() {
        // sharedkernel is the bottom of the graph. Seeing platform or app here would mean
        // the dependency direction had been inverted.
        assertThat(classpathEntries())
                .as("classpath must not contain output of modules above sharedkernel")
                .noneMatch(entry -> isBuildOutputOf(entry, "platform") || isBuildOutputOf(entry, "app"));
    }

    /**
     * True when a classpath entry is build output of the named Gradle module.
     *
     * <p>Matches on path <em>elements</em> rather than substrings. Substring matching is
     * unreliable here: {@code java.class.path} arrives with doubled separators on this
     * platform, and a plain {@code contains} would also match an unrelated directory such
     * as {@code sharedkernel-backup}. {@link Path} normalises separators and lets us assert
     * on the real structure {@code .../<module>/build/...}.
     */
    private static boolean isBuildOutputOf(String classpathEntry, String module) {
        Path path = Path.of(classpathEntry);
        for (int i = 0; i < path.getNameCount() - 1; i++) {
            if (path.getName(i).toString().equals(module)
                    && path.getName(i + 1).toString().equals("build")) {
                return true;
            }
        }
        return false;
    }

    /** Entries of {@code java.class.path}. */
    private static List<String> classpathEntries() {
        String classpath = System.getProperty("java.class.path");
        assertThat(classpath).as("java.class.path").isNotBlank();
        return Arrays.stream(classpath.split(File.pathSeparator)).toList();
    }
}
