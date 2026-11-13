package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.accounting.AccountingBeans;
import com.finapp.app.database.ReportingConnections;
import com.finapp.app.database.ReportingPool;
import com.finapp.app.security.DatabaseEndpoint;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTag;
import com.tngtech.archunit.junit.ArchTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.springframework.context.annotation.Bean;

/**
 * <strong>One primary serves every door</strong> - {@code PHASE_GATES.md} §5 Phase 16 criterion 6, its first branch (`P16-TSK-012`,
 * ADR-0142; ADR-0129 decision 7): no read replica is routed, so no money-moving door, no balance check and no once-ness check can
 * read one. The second branch (a replica serving the reporting pool alone) is not built; ADR-0142 says what it needs first.
 *
 * <p>"One data source" is held in layers, each the way a second server could be reached, and each refused here:
 * <ol>
 *   <li><strong>an address read</strong>: no production class but {@link DatabaseEndpoint} names a database's address - a JDBC URL,
 *       or a property or environment key for a URL, a host or a replica, in a statement's constant or a {@code @Value}'s
 *       placeholder - read from the classes' bytes, every module's;</li>
 *   <li><strong>a connection built</strong>: no production class but {@link ReportingPool} depends on what turns an address into a
 *       connection - the pool (HikariCP), the driver ({@code org.postgresql}), the JDK's {@link DriverManager} and {@code Driver},
 *       a {@code javax.sql} source other than {@link DataSource} itself, Spring Boot's builders and Spring's driver-backed data
 *       sources;</li>
 *   <li><strong>a second pool declared</strong>: no production {@code @Bean} returns a {@link DataSource}, so the writer is Spring
 *       Boot's one auto-configured pool;</li>
 *   <li><strong>the reporting pool reached</strong>: only report generation's wiring depends on {@link ReportingConnections} or
 *       {@link ReportingPool} - the pool's own package and {@link AccountingBeans}' two methods that hand it to the run generator -
 *       so no money-moving door reaches that pool, whatever server it ever reaches.</li>
 * </ol>
 * The reporting pool's address is the writer's ({@code ReportingPoolEndpointTest}), and the driver may take only a primary from it
 * ({@code PrimaryOnlyGuardTest}). Each rule is shown its own violation, planted below, and seen to refuse it; each census shows the
 * rule read what it claims.
 */
@Tag("architecture")
// ArchUnit's own engine reads ArchTag, not JUnit's Tag (P1-TSK-025; TestTaxonomyTest requires the pair).
@ArchTag("architecture")
@AnalyzeClasses(packages = "com.finapp", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("one primary data source serves every door, and only report generation reaches the reporting pool (P16-TSK-012)")
class OnePrimaryServesEveryDoorTest {

    /**
     * A database's address: a JDBC URL; Hikari's own URL key; a property under the writer's or the platform's database keys naming
     * a URL, a host, an endpoint or a replica; or an environment variable for one.
     */
    static final Pattern ADDRESS = Pattern.compile("(?i)^(?:jdbc:.*|jdbc-url"
            + "|(?:spring\\.datasource|finapp\\.database|finapp\\.db)(?:\\.[a-z0-9-]+)*\\.(?:url|jdbc-url|host|hosts|endpoint|replica[a-z0-9-]*)"
            + "|finapp_db_[a-z0-9_]*(?:url|host|hosts|endpoint|replica[a-z0-9_]*))$");

    /** A property placeholder's key, {@code ${key}} or {@code ${key:default}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

    /** The one class that reads the database's address: both pools connect with what it answers. */
    static final String ADDRESS_READER = "com/finapp/app/security/DatabaseEndpoint";

    /** Report generation's wiring: the classes allowed to depend on the reporting pool, the pool's own package among them. */
    static final Set<String> REPORTING_WIRING = Set.of(ReportingPool.class.getName(), ReportingConnections.class.getName(),
            "com.finapp.app.database.ReportingPoolBeans", AccountingBeans.class.getName());

    /** {@link AccountingBeans}' methods that hand the reporting pool to the run generator, and nothing else. */
    static final Set<String> GENERATION_METHODS = Set.of("reportRunGenerator", "generationSnapshots");

    /**
     * The build's test fixtures ({@code src/testFixtures}, packages {@code com.finapp.<module>.testing}): they reach the test
     * classpath as jars beside the modules' own, and open the test databases' roles by {@code DriverManager}, but no application
     * image holds them ({@code testImplementation(testFixtures(...))} only). {@link #theFixturesAreNoProductionCode} holds that no
     * main source set has such a package, so this exclusion cannot hide a door.
     */
    static final Pattern FIXTURE = Pattern.compile("^com[./]finapp[./][a-z]+[./]testing(?:[./].*)?$");

    private static boolean isFixture(String classOrPackage) {
        int last = Math.max(classOrPackage.lastIndexOf('/'), classOrPackage.lastIndexOf('.'));
        return FIXTURE.matcher(last < 0 ? classOrPackage : classOrPackage.substring(0, last)).matches();
    }

    @Test
    @DisplayName("the fixtures set aside are the build's test fixtures: no main source set holds a testing package")
    void theFixturesAreNoProductionCode() throws java.io.IOException {
        java.nio.file.Path root = EvidenceCitations.repositoryRoot();
        List<java.nio.file.Path> mainTesting;
        try (var modules = java.nio.file.Files.list(root)) {
            mainTesting = modules.map(module -> module.resolve("src/main/java/com/finapp").resolve(module.getFileName().toString())
                            .resolve("testing"))
                    .filter(java.nio.file.Files::exists).toList();
        }
        assertThat(mainTesting).as("a testing package in a main source set would be production code this guard sets aside").isEmpty();
        assertThat(isFixture("com/finapp/platform/testing/database/DatabaseRoles")).isTrue();
        assertThat(isFixture("com.finapp.platform.testing.RepositoryPaths")).isTrue();
        assertThat(isFixture("com/finapp/platform/testingx/Door")).as("a lookalike").isFalse();
        assertThat(isFixture("com.finapp.app.database.ReportingPool")).isFalse();
        assertThat(java.nio.file.Files.isDirectory(root.resolve("platform/src/testFixtures/java/com/finapp/platform/testing")))
                .as("the fixtures this exclusion is for are where the build keeps them").isTrue();
    }

    // ------------------------------------------------------------------ 1. an address read

    @Test
    @DisplayName("no production class but DatabaseEndpoint names a database's address, in any module's bytes")
    void onlyTheEndpointReadsADatabaseAddress() {
        Set<String> readers = new TreeSet<>();
        MonitorInstructions.forEachProductionClass((module, bytes) -> {
            String name = new ClassReader(bytes).getClassName();
            if (!name.equals(ADDRESS_READER) && !isFixture(name) && namesAnAddress(constantsOf(bytes))) {
                readers.add(module + ":" + name);
            }
        });
        assertThat(readers).as("a second reader of a database's address: a second server reachable (ADR-0142)").isEmpty();
    }

    @Test
    @DisplayName("the scan reads every module's classes, and finds the address where it is really read")
    void theAddressScanSeesWhatItClaims() {
        Set<String> holders = new TreeSet<>();
        MonitorInstructions.forEachProductionClass((module, bytes) -> {
            if (namesAnAddress(constantsOf(bytes))) {
                holders.add(new ClassReader(bytes).getClassName());
            }
        });
        assertThat(MonitorInstructions.modulesScanned()).isEqualTo(ProductionModules.onClasspathWithProductionClasses());
        assertThat(holders).as("the endpoint names the writer's URL keys, or this scan is reading nothing").contains(ADDRESS_READER);
    }

    @Test
    @DisplayName("an address in a constant, a concatenation or a @Value placeholder is caught; a credential's or a pool size's key is not")
    void theAddressTestHasTeeth() {
        assertThat(namesAnAddress(constantsOf(bytesOf(PlantedReplicaUrl.class)))).as("a literal URL").isTrue();
        assertThat(namesAnAddress(constantsOf(bytesOf(PlantedReplicaProperty.class)))).as("a property key").isTrue();
        assertThat(namesAnAddress(constantsOf(bytesOf(PlantedReplicaValue.class)))).as("a @Value placeholder").isTrue();
        assertThat(namesAnAddress(constantsOf(bytesOf(PlantedReplicaVariable.class)))).as("an environment variable").isTrue();

        assertThat(namesAnAddress(List.of("finapp.database.reporting.username", "finapp.database.reporting.pool-size",
                "finapp.database.instances", "FINAPP_DB_REPORTING_PASSWORD", "spring.datasource.hikari.",
                "no host could be read from spring.datasource.url, so this"))).isFalse();
    }

    // ------------------------------------------------------------------ 2. a connection built

    @ArchTest
    static void nothingButTheReportingPoolBuildsAConnection(JavaClasses imported) {
        assertThat(connectionBuilders(imported, candidate -> !candidate.getName().equals(ReportingPool.class.getName())
                        && !isFixture(candidate.getName())))
                .as("a class building a pool or a connection of its own reaches a server the writer's pool does not (ADR-0142)")
                .isEmpty();
    }

    @ArchTest
    static void theConnectionRuleSeesTheReportingPoolBuildOne(JavaClasses imported) {
        assertThat(connectionBuilders(imported, candidate -> true))
                .as("the reporting pool builds its HikariCP pool: the rule reads the dependency it refuses elsewhere")
                .anyMatch(found -> found.startsWith(ReportingPool.class.getName() + " -> " + HikariDataSource.class.getName()));
        assertThat(imported.stream().map(ProductionModules::of).filter(module -> module != null).distinct().sorted().toList())
                .as("every production module was imported").containsExactlyElementsOf(ProductionModules.onClasspathWithProductionClasses());
    }

    @Test
    @DisplayName("a class building a HikariCP pool, or a connection through DriverManager, is refused")
    void theConnectionRuleHasTeeth() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedSecondPool.class, PlantedDriverConnection.class,
                PlantedDoorOnTheWriter.class);
        assertThat(connectionBuilders(planted, candidate -> true))
                .anyMatch(found -> found.startsWith(PlantedSecondPool.class.getName() + " -> " + HikariDataSource.class.getName()))
                .anyMatch(found -> found.startsWith(PlantedDriverConnection.class.getName() + " -> " + DriverManager.class.getName()))
                .noneMatch(found -> found.startsWith(PlantedDoorOnTheWriter.class.getName()));
    }

    // ------------------------------------------------------------------ 3. a second pool declared

    @ArchTest
    static void noProductionBeanDeclaresADataSource(JavaClasses imported) {
        assertThat(dataSourceBeans(imported)).as("a declared DataSource replaces or joins the writer's one pool (ADR-0142)").isEmpty();
        assertThat(imported.stream().flatMap(candidate -> candidate.getMethods().stream())
                        .filter(method -> method.isAnnotatedWith(Bean.class))
                        .filter(method -> method.getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(DataSource.class))))
                .as("the census: the beans read, the writer's DataSource taken as a parameter by many").hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("a @Bean returning a DataSource, or a pool class, is refused")
    void theBeanRuleHasTeeth() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedDataSourceBeans.class);
        assertThat(dataSourceBeans(planted)).containsExactlyInAnyOrder(
                PlantedDataSourceBeans.class.getName() + ".replica", PlantedDataSourceBeans.class.getName() + ".hikari");
    }

    // ------------------------------------------------------------------ 4. the reporting pool reached

    @ArchTest
    static void onlyReportGenerationReachesTheReportingPool(JavaClasses imported) {
        assertThat(reportingPoolReach(imported)).as("a class or method outside report generation reaching the reporting pool:"
                + " no money-moving door may read through it (ADR-0129 decision 7, ADR-0142)").isEmpty();
    }

    @ArchTest
    static void theReachRuleSeesTheGeneratorsWiring(JavaClasses imported) {
        JavaClass beans = imported.get(AccountingBeans.class);
        assertThat(beans.getMethods().stream().filter(OnePrimaryServesEveryDoorTest::touchesTheReportingPool).map(JavaMethod::getName))
                .as("the census: the run generator's wiring is seen taking the reporting pool").contains("reportRunGenerator", "generationSnapshots");
    }

    @Test
    @DisplayName("the entry's probe: a money-moving door wired to the reporting pool is refused, and one on the writer is not")
    void aMoneyMovingDoorOnTheReportingPoolIsRefused() {
        JavaClasses planted = new ClassFileImporter().importClasses(PlantedTransferDoor.class, PlantedDoorOnTheWriter.class,
                PlantedGenerationBeans.class, ReportingConnections.class);
        assertThat(reportingPoolReach(planted))
                .anyMatch(found -> found.startsWith(PlantedTransferDoor.class.getName() + " -> " + ReportingConnections.class.getName()))
                .anyMatch(found -> found.equals(PlantedGenerationBeans.class.getName() + ".transferExecution touches the reporting pool"))
                .noneMatch(found -> found.contains("generationSnapshots"))
                .noneMatch(found -> found.contains(PlantedDoorOnTheWriter.class.getName()));
    }

    // ------------------------------------------------------------------ the rules

    static boolean namesAnAddress(List<String> constants) {
        for (String constant : constants) {
            if (ADDRESS.matcher(constant.trim()).matches()) {
                return true;
            }
            Matcher placeholder = PLACEHOLDER.matcher(constant);
            while (placeholder.find()) {
                if (ADDRESS.matcher(placeholder.group(1).trim()).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What turns an address into a connection: a target of this name is a way to a server of one's own. */
    static boolean buildsAConnection(String target) {
        return target.startsWith("com.zaxxer.hikari.") || target.startsWith("org.postgresql.")
                || target.equals("java.sql.DriverManager") || target.equals("java.sql.Driver")
                || (target.startsWith("javax.sql.") && !target.equals(DataSource.class.getName()))
                || target.startsWith("org.springframework.boot.jdbc.") || target.startsWith("org.springframework.boot.autoconfigure.jdbc.")
                || (target.startsWith("org.springframework.jdbc.datasource.") && target.endsWith("DataSource"));
    }

    private static List<String> connectionBuilders(JavaClasses classes, Predicate<JavaClass> judged) {
        List<String> found = new ArrayList<>();
        for (JavaClass candidate : classes) {
            if (!judged.test(candidate)) {
                continue;
            }
            for (Dependency dependency : candidate.getDirectDependenciesFromSelf()) {
                String target = dependency.getTargetClass().getName();
                if (buildsAConnection(target)) {
                    found.add(candidate.getName() + " -> " + target + ": " + dependency.getDescription());
                }
            }
        }
        return found;
    }

    private static List<String> dataSourceBeans(JavaClasses classes) {
        List<String> found = new ArrayList<>();
        for (JavaClass candidate : classes) {
            for (JavaMethod method : candidate.getMethods()) {
                if (method.isAnnotatedWith(Bean.class) && method.getRawReturnType().isAssignableTo(DataSource.class)) {
                    found.add(candidate.getName() + "." + method.getName());
                }
            }
        }
        return found;
    }

    private static boolean isReportingPoolType(JavaClass type) {
        return type.isEquivalentTo(ReportingConnections.class) || type.isEquivalentTo(ReportingPool.class);
    }

    /** A method that takes, returns or touches the reporting pool, a lambda's body counted as its enclosing method's. */
    private static boolean touchesTheReportingPool(JavaMethod method) {
        return method.getRawParameterTypes().stream().anyMatch(OnePrimaryServesEveryDoorTest::isReportingPoolType)
                || isReportingPoolType(method.getRawReturnType())
                || method.getAccessesFromSelf().stream().anyMatch(access -> isReportingPoolType(access.getTargetOwner()));
    }

    private static List<String> reportingPoolReach(JavaClasses classes) {
        List<String> found = new ArrayList<>();
        for (JavaClass candidate : classes) {
            if (candidate.getName().equals(AccountingBeans.class.getName()) || candidate.getSimpleName().equals("PlantedGenerationBeans")) {
                // Report generation's wiring class: only its two methods may touch the pool, its other beans being the writer's.
                for (JavaMethod method : candidate.getMethods()) {
                    String enclosing = method.getName().startsWith("lambda$") ? method.getName().split("\\$")[1] : method.getName();
                    if (touchesTheReportingPool(method) && !GENERATION_METHODS.contains(enclosing)) {
                        found.add(candidate.getName() + "." + method.getName() + " touches the reporting pool");
                    }
                }
                continue;
            }
            if (REPORTING_WIRING.contains(candidate.getName())) {
                continue;
            }
            for (Dependency dependency : candidate.getDirectDependenciesFromSelf()) {
                if (isReportingPoolType(dependency.getTargetClass())) {
                    found.add(candidate.getName() + " -> " + dependency.getTargetClass().getName() + ": " + dependency.getDescription());
                }
            }
        }
        return found;
    }

    /** Every {@code String} a class's code can load, its annotations' values and invokedynamic's concatenation recipes among them. */
    static List<String> constantsOf(byte[] bytes) {
        List<String> constants = new ArrayList<>();
        AnnotationVisitor annotations = new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String text) {
                    constants.add(text);
                }
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return this;
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                return this;
            }
        };
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return annotations;
            }

            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                if (value instanceof String text) {
                    constants.add(text);
                }
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return annotations;
                    }
                };
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                        return annotations;
                    }

                    @Override
                    public AnnotationVisitor visitParameterAnnotation(int parameter, String annotation, boolean visible) {
                        return annotations;
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String text) {
                            constants.add(text);
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String indy, String indyDescriptor, Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof String text) {
                                constants.add(text);
                            }
                        }
                    }
                };
            }
        }, 0);
        return constants;
    }

    private static byte[] bytesOf(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(resource)) {
            assertThat(in).as("the planted class's bytes").isNotNull();
            return in.readAllBytes();
        } catch (java.io.IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }

    // ------------------------------------------------------------------ the planted probes, never called

    /** A replica's URL written into a class. */
    static class PlantedReplicaUrl {
        static String url() {
            return "jdbc:postgresql://replica.internal:5432/finapp";
        }
    }

    /** A replica's address read from a property. */
    static class PlantedReplicaProperty {
        static String key() {
            return "finapp.database.reporting.url";
        }
    }

    /** A replica's address injected by a placeholder. */
    static class PlantedReplicaValue {
        @org.springframework.beans.factory.annotation.Value("${finapp.database.replica.url:}")
        String url;
    }

    /** A replica's address read from the environment. */
    static class PlantedReplicaVariable {
        static String variable() {
            return "FINAPP_DB_REPLICA_URL";
        }
    }

    /** A second pool of its own. */
    static class PlantedSecondPool {
        HikariDataSource pool() {
            return new HikariDataSource();
        }
    }

    /** A connection opened by the JDK's driver manager. */
    static class PlantedDriverConnection {
        Connection open(String url) throws SQLException {
            return DriverManager.getConnection(url);
        }
    }

    /** A door on the writer's pool: the shape every money-moving door has. */
    static class PlantedDoorOnTheWriter {
        private final DataSource writer;

        PlantedDoorOnTheWriter(DataSource writer) {
            this.writer = writer;
        }

        Connection unitOfWork() throws SQLException {
            return writer.getConnection();
        }
    }

    /** A transfer door reading a balance through report generation's pool. */
    static class PlantedTransferDoor {
        private final ReportingConnections reporting;

        PlantedTransferDoor(ReportingConnections reporting) {
            this.reporting = reporting;
        }

        Connection balanceCheck() throws SQLException {
            return reporting.open();
        }
    }

    /** A wiring class shaped like {@link AccountingBeans}: its generation method may take the pool; a door's bean may not. */
    static class PlantedGenerationBeans {
        Object generationSnapshots(ReportingConnections reporting) {
            return (java.util.function.Supplier<Object>) () -> reporting;
        }

        Object transferExecution(ReportingConnections reporting) {
            return reporting;
        }
    }

    /** Two beans that each add a pool beside the writer's. */
    static class PlantedDataSourceBeans {
        @Bean
        DataSource replica() {
            return null;
        }

        @Bean
        HikariDataSource hikari() {
            return null;
        }

        @Bean
        Object notAPool() {
            return null;
        }
    }
}
