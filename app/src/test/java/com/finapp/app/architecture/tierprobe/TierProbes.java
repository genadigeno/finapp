package com.finapp.app.architecture.tierprobe;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Deliberate examples of each tier's signature, so {@code TestTaxonomyTest} can prove the
 * detection actually fires rather than asserting that it did not object to anything.
 *
 * <p>None of these carries a test method, which is what keeps them out of the sweep they exist to
 * exercise: the taxonomy guard selects classes by the presence of a JUnit test annotation, never
 * by name. They live in their own package rather than beside the {@code com.finapp.ledger} rule
 * fixtures because those are imported wholesale by other rule suites, and a probe that quietly
 * changed another suite's violation count would be a poor way to find that out.
 */
public final class TierProbes {

    private TierProbes() {}

    /** Needs a Spring application context, and says so in the strongest way a class can. */
    @SpringBootTest
    public static final class NeedsASpringContext {}

    /** Needs a database: it opens a connection. */
    public static final class NeedsADatabase {
        Connection open() throws SQLException {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:1/never-dialled");
        }
    }

    /**
     * Mentions a connection without obtaining one, exactly as
     * {@code NoDirectBrokerPublicationRulesTest}'s fixture does. Detection must NOT fire on this.
     */
    public static final class MentionsAConnection {
        @SuppressWarnings("unused")
        void announce(Connection unitOfWork) {}
    }

    /** Needs the compiled classes of every module. */
    public static final class NeedsTheCompiledClasses {
        ClassFileImporter importer = new ClassFileImporter();
    }

    /** Needs nothing beyond the JVM. The control: detection must NOT fire on this. */
    public static final class NeedsNothing {
        int value;
    }
}
