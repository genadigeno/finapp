package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.security.ConfinedCredential.KeyLength;
import com.finapp.app.security.ConfinedCredential.KeySpec;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Every secret the application reads has a row in {@code SECRET_MANAGEMENT.md} section 3, every row is read, and every row is
 * delivered to the workload it names and to no other (`P15-TSK-018`, ADR-0123, {@code THREAT_MODEL.md} T6 and T15).
 *
 * <p>"Every secret, key, password or token property" by name alone is the wrong test twice over: it misses a secret named
 * something else, and it catches the eight {@code key-version} companions and the tokenisation endpoints, which are not secrets.
 * So the set is <strong>derived from what the code actually confines</strong>, the way the code itself knows a credential: a class
 * declaring a {@link KeySpec}, an {@code @Value} or a placeholder whose fallback is the marked local default, a password the
 * migrator reads. The name pattern is the second net, for what no confinement has claimed, with an explicit list of what is not a
 * secret that is held both ways too.
 *
 * <p>What this reads is what the code reads: the {@code @Value} expressions by reflection, so a default built by concatenation is
 * seen as the constant the compiler folded; every module's production source for the names; {@code application.yaml} as YAML. A
 * {@code @ConfigurationProperties} class would escape the source scan, so its absence is asserted. The manifests are read here on
 * every build; rendered behaviour is {@code infra/scripts/manifest-check.sh}'s, and none of this has run against a cluster or a
 * store.
 */
@Tag("architecture")
@DisplayName("every secret the application reads is inventoried and delivered, and every row is read (P15-TSK-018)")
class SecretInventoryIsCompleteTest {

    private static final String DOCUMENT = "docs/architecture/SECRET_MANAGEMENT.md";
    private static final String APPLICATION = "app/src/main/resources/application.yaml";
    private static final String APP_DELIVERY = "deploy/components/secret-delivery/externalsecret-app.yaml";
    private static final String MIGRATOR_DELIVERY = "deploy/components/secret-delivery/externalsecret-migrator.yaml";
    private static final String GENERATOR = "infra/scripts/kind-secrets.sh";
    private static final String OPERATOR_VALUES = "deploy/operators/external-secrets/values.yaml";
    private static final String DEPLOYMENT = "deploy/base/deployment.yaml";
    private static final String PINS = "infra/scanner-pins.sh";
    private static final String PIN_CHECK = "infra/scripts/check-pinned-images.sh";
    private static final String MANIFEST_CHECK = "infra/scripts/manifest-check.sh";

    private static final String APP_SECRET = "finapp-app-secrets";
    private static final String MIGRATOR_SECRET = "finapp-migrator-secrets";
    private static final String NOT_DELIVERED = "none";
    private static final String GENERATION = "finapp/rotation-generation";

    private static final List<String> INVENTORY_HEADER =
            List.of("Variable", "Property", "Class", "Owner", "Rotation", "Consumer", "Delivered through");
    private static final List<String> BESIDE_HEADER = List.of("Variable", "Property", "Why it is here", "Delivered through");
    private static final List<String> NOT_A_SECRET_HEADER = List.of("Property", "Why it is not a secret");
    private static final List<String> ROTATION_HEADER = List.of("Class", "Rotation", "How", "Today");

    /** The second net: anything read whose name says it might be a secret, whether or not any guard confines it. */
    private static final Pattern SECRET_SHAPED_NAME = Pattern.compile(
            "(?i)(secret|passw|passphrase|pepper|credential|token|hmac|api[-_.]?key|private|signing|salt|jwt|bearer"
                    + "|(?:^|[.-])key(?:$|[.-]))");

    /** A property some code reads, in each of the forms this codebase reads one. */
    private static final Pattern READ_PROPERTY = Pattern.compile(
            "\\$\\{(finapp\\.[a-zA-Z0-9._-]+)"
                    + "|getProperty\\(\\s*\"(finapp\\.[a-zA-Z0-9._-]+)\""
                    + "|ConditionalOnProperty\\(\\s*(?:name\\s*=\\s*)?\"(finapp\\.[a-zA-Z0-9._-]+)\""
                    + "|_PROPERTY\\s*=\\s*\"(finapp\\.[a-zA-Z0-9._-]+)\"");

    private static final Pattern PLACEHOLDER_VARIABLE = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

    private static final Pattern MIGRATOR_VARIABLE = Pattern.compile("\"(FINAPP_[A-Z0-9_]+)\"");

    private static Path root;
    private static List<Row> inventory;
    private static List<Beside> beside;
    private static Map<String, String> notSecrets;
    private static Map<String, String> sources;
    private static Set<String> readProperties;
    private static Map<String, Object> application;
    private static String applicationText;
    private static String document;

    record Row(String variable, String property, String secretClass, String owner, String rotation, String consumer, List<String> delivered) {
    }

    record Beside(String variable, String property, List<String> delivered) {
    }

    @BeforeAll
    static void readEverything() {
        root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        document = read(DOCUMENT);
        applicationText = read(APPLICATION);
        application = loadAll(applicationText).get(0);
        sources = productionSources();
        inventory = table("### 3.2 ", INVENTORY_HEADER).stream()
                .map(cells -> new Row(cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4), cells.get(5), delivered(cells.get(6))))
                .toList();
        beside = table("### 3.3 ", BESIDE_HEADER).stream()
                .map(cells -> new Beside(cells.get(0), cells.get(1), delivered(cells.get(3))))
                .toList();
        notSecrets = new TreeMap<>();
        table("### 3.4 ", NOT_A_SECRET_HEADER).forEach(cells -> notSecrets.put(cells.get(0), cells.get(1)));
        readProperties = readProperties();

        assertThat(inventory).as("the inventory table parsed").hasSizeGreaterThan(30);
        assertThat(beside).as("the table of what travels beside the secrets parsed").hasSizeGreaterThan(4);
        assertThat(notSecrets).as("the table of names that are not secrets parsed").hasSizeGreaterThan(8);
        assertThat(sources).as("every module's production source was read").hasSizeGreaterThan(1000);
        assertThat(readProperties).as("the names the application reads were read").hasSizeGreaterThan(200);
    }

    // ---------------------------------------------------------------------------------------------- both ways

    @Test
    @DisplayName("a credential the code confines has a row: every KeySpec, every marked default, every password the migrator reads")
    void everyCredentialTheCodeConfinesHasARow() {
        Set<String> confined = new TreeSet<>();
        confined.addAll(keySpecClasses().keySet());
        confined.addAll(markedDefaultVariables());
        confined.addAll(placeholdersWithTheMarkedDefault());
        confined.addAll(migratorPasswords());

        assertThat(keySpecClasses()).as("the key classes were found").hasSizeGreaterThan(20);
        assertThat(placeholdersWithTheMarkedDefault()).as("application.yaml's own confined credentials were found").isNotEmpty();
        assertThat(migratorPasswords()).as("the migrator's passwords were found").isNotEmpty();
        assertThat(markedDefaultVariables()).as("a marked default under @Value reaches the Kafka password").contains("FINAPP_KAFKA_SASL_PASSWORD");

        Set<String> missing = new TreeSet<>(confined);
        missing.removeAll(inventoryVariables());
        assertThat(missing).as("every credential the code confines is a row of %s section 3.2", DOCUMENT).isEmpty();
    }

    @Test
    @DisplayName("a row is read by something: a property a class reads, a placeholder application.yaml reads, or a variable the migrator reads")
    void everyRowIsReadByTheCode() {
        Set<String> read = new TreeSet<>();
        readProperties.forEach(property -> read.add(environmentFormOf(property)));
        read.addAll(placeholders());
        read.addAll(migratorVariables());

        Set<String> unread = new TreeSet<>(inventoryVariables());
        beside.forEach(row -> unread.add(row.variable()));
        unread.removeAll(read);
        assertThat(unread).as("a row with nothing reading it is an inventory of something that is not there").isEmpty();

        for (Row row : inventory) {
            if (!row.property().equals(NOT_DELIVERED)) {
                assertThat(propertyIsRead(row.property())).as("%s names a property something reads", row.variable()).isTrue();
            }
        }
    }

    @Test
    @DisplayName("the second net: a name that reads like a secret is either a row or listed as not one")
    void everySecretShapedNameIsAccountedFor() {
        assertThat(sources.values()).as("the source scan does not see @ConfigurationProperties, so none may exist")
                .noneSatisfy(source -> assertThat(source).contains("@ConfigurationProperties"));

        Set<String> inventoried = new TreeSet<>();
        inventory.forEach(row -> inventoried.add(row.property()));
        beside.forEach(row -> inventoried.add(row.property()));

        Set<String> unaccounted = new TreeSet<>();
        for (String property : readProperties) {
            if (SECRET_SHAPED_NAME.matcher(property).find() && !inventoried.contains(property) && !notSecrets.containsKey(property)) {
                unaccounted.add(property);
            }
        }
        assertThat(unaccounted).as("give it a row in %s section 3.2, or a line in section 3.4 saying why it is not a secret", DOCUMENT).isEmpty();
    }

    @Test
    @DisplayName("every name listed as not a secret is read, reads like one and has no row: the list cannot outlive what it excuses")
    void theListOfWhatIsNotASecretIsHeldBothWays() {
        for (String property : notSecrets.keySet()) {
            assertThat(readProperties).as("section 3.4 excuses a name nothing reads").contains(property);
            assertThat(SECRET_SHAPED_NAME.matcher(property).find()).as("%s does not need excusing", property).isTrue();
            assertThat(inventoryVariables()).as("%s is excused and inventoried", property).doesNotContain(environmentFormOf(property));
        }
    }

    // ---------------------------------------------------------------------------------------------- the rows against the code

    @Test
    @DisplayName("a row's class, owner and consumer are the code's: a field key is exactly an AES key with a version companion, a webhook key a callback door")
    void theRowsAreTheCodes() {
        Map<String, Class<?>> classes = keySpecClasses();
        for (Row row : inventory) {
            Class<?> declaring = classes.get(row.variable());
            assertThat(Files.isDirectory(root.resolve("app/src/main/java/com/finapp/app/" + row.owner())))
                    .as("%s's owner is a package of the application", row.variable()).isTrue();
            assertConsumerReads(row);
            assertThat(row.delivered().contains(APP_SECRET)).as("%s reaches the application's Secret exactly if the application reads it", row.variable())
                    .isEqualTo(row.consumer().contains("finapp-app"));
            assertThat(row.delivered().contains(MIGRATOR_SECRET)).as("%s reaches the migrator's Secret exactly if the migrator reads it", row.variable())
                    .isEqualTo(row.consumer().contains("finapp-migrator"));
            assertThat(row.delivered().contains(NOT_DELIVERED)).as("%s reaches no Secret exactly if only the bootstrap step reads it", row.variable())
                    .isEqualTo(!row.consumer().contains("finapp-app") && !row.consumer().contains("finapp-migrator"));
            if (declaring == null) {
                continue;
            }
            assertThat(row.owner()).as("%s is owned where its key class is declared", row.variable()).isEqualTo(lastSegment(declaring.getPackageName()));

            boolean fieldKey = row.secretClass().equals("field-encryption-key");
            assertThat(specOf(declaring).length() == KeyLength.EXACTLY_32).as("%s: a field key is exactly the AES-256 keys", row.variable()).isEqualTo(fieldKey);
            assertThat(readProperties.contains(row.property() + "-version")).as("%s: a field key is exactly what has a key-version companion", row.variable()).isEqualTo(fieldKey);

            boolean callback = row.variable().contains("WEBHOOK") || row.variable().contains("CALLBACK");
            assertThat(row.secretClass().equals("webhook-hmac")).as("%s: the webhook class is exactly the callback doors", row.variable()).isEqualTo(callback);
            boolean pull = row.variable().contains("REPORT") || row.variable().contains("BANK_STATEMENT");
            assertThat(row.secretClass().equals("report-pull")).as("%s: the report class is exactly the pulls", row.variable()).isEqualTo(pull);
        }
    }

    @Test
    @DisplayName("each delivered variable reaches its property: Spring is asked, for the ones it binds by name, and application.yaml for the rest")
    void everyVariableReachesItsProperty() {
        List<String[]> pairs = new ArrayList<>();
        inventory.forEach(row -> pairs.add(new String[] {row.variable(), row.property()}));
        beside.forEach(row -> pairs.add(new String[] {row.variable(), row.property()}));
        Map<String, Object> flat = flatten(application);

        for (String[] pair : pairs) {
            String variable = pair[0];
            String property = pair[1];
            if (property.equals(NOT_DELIVERED)) {
                assertThat(migratorVariables()).as("%s has no property, so the migrator reads it by name", variable).contains(variable);
                continue;
            }
            if (flat.containsKey(property)) {
                assertThat(String.valueOf(flat.get(property))).as("application.yaml reads %s through %s", property, variable).contains("${" + variable);
                continue;
            }
            // Under its own name, because Spring Boot applies its environment mapping only to the source called this.
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(variable, "delivered")));
            ConfigurationPropertySources.attach(environment);
            assertThat(environment.getProperty(property)).as("Spring binds %s to %s", variable, property).isEqualTo("delivered");
        }
    }

    @Test
    @DisplayName("each class has one rotation, stated in section 5, and every row carries its class's")
    void theRotationIsTheClasss() {
        Map<String, String> rotations = new TreeMap<>();
        table("### 5.1 ", ROTATION_HEADER).forEach(cells -> rotations.put(cells.get(0), cells.get(1)));

        Set<String> used = new TreeSet<>();
        for (Row row : inventory) {
            used.add(row.secretClass());
            assertThat(rotations).as("%s's class is in section 5.1", row.variable()).containsKey(row.secretClass());
            assertThat(row.rotation()).as("%s rotates as its class does", row.variable()).isEqualTo(rotations.get(row.secretClass()));
        }
        assertThat(rotations.keySet()).as("a rotation no row uses describes nothing").isEqualTo(used);
    }

    @Test
    @DisplayName("the counts the document states are the counts: a row more or a key class more turns this red")
    void theDocumentsCountsAreTheCount() {
        Matcher counts = Pattern.compile("The inventory holds \\*\\*(\\d+)\\*\\* secrets, \\*\\*(\\d+)\\*\\* of them declared by a key class").matcher(document);
        assertThat(counts.find()).as("section 3.2 states its counts").isTrue();
        assertThat(Integer.parseInt(counts.group(1))).as("the rows").isEqualTo(inventory.size());
        assertThat(Integer.parseInt(counts.group(2))).as("the key classes").isEqualTo(keySpecClasses().size());
    }

    @Test
    @DisplayName("the document holds no account number and no endpoint")
    void theDocumentNamesNothingReal() {
        assertThat(document.replace("000000000000", "")).doesNotContainPattern("\\b[0-9]{12}\\b").doesNotContain("://");
    }

    // ---------------------------------------------------------------------------------------------- delivery

    @Test
    @DisplayName("each ExternalSecret writes exactly the rows delivered through it, and the reverse")
    void eachExternalSecretWritesExactlyItsRows() {
        assertThat(entries(APP_DELIVERY)).as("%s against section 3", APP_SECRET).isEqualTo(deliveredThrough(APP_SECRET));
        assertThat(entries(MIGRATOR_DELIVERY)).as("%s against section 3", MIGRATOR_SECRET).isEqualTo(deliveredThrough(MIGRATOR_SECRET));
        assertThat(entries(APP_DELIVERY)).as("the application's Secret was read").hasSizeGreaterThan(30);
        assertThat(entries(MIGRATOR_DELIVERY)).as("the migrator's Secret was read").hasSize(3);
    }

    @Test
    @DisplayName("the migrator's Secret carries none of the application's keys, the application's none of the migrator's, the master credential neither")
    void theTwoSecretsCarryOnlyTheirOwn() {
        Set<String> app = entries(APP_DELIVERY);
        Set<String> migrator = entries(MIGRATOR_DELIVERY);

        Set<String> shared = new TreeSet<>(app);
        shared.retainAll(migrator);
        assertThat(shared).as("the one value both read is the database's address, which is not a secret").containsExactly("FINAPP_DB_URL");

        assertThat(app).filteredOn(name -> name.contains("MIGRATOR") || name.contains("MASTER")).isEmpty();
        assertThat(migrator).filteredOn(name -> name.contains("MASTER")).isEmpty();
        assertThat(migrator).containsExactlyInAnyOrder("FINAPP_DB_URL", "FINAPP_DB_MIGRATOR_USER", "FINAPP_DB_MIGRATOR_PASSWORD");
    }

    @Test
    @DisplayName("only finapp-app reads finapp-app-secrets and only the migrator its own: every workload under deploy/ is read")
    void onlyEachWorkloadReadsItsSecret() {
        Map<String, String> readers = Map.of(APP_SECRET, "finapp-app", MIGRATOR_SECRET, "finapp-migrator");
        Set<String> workloads = Set.of("Deployment", "StatefulSet", "DaemonSet", "ReplicaSet", "Job", "CronJob", "Pod", "ReplicationController");
        Set<String> seenReading = new TreeSet<>();

        for (Path file : deployFiles()) {
            if (!isYaml(file)) {
                continue;
            }
            for (Map<String, Object> doc : loadAll(readFile(file))) {
                if (!workloads.contains(String.valueOf(doc.get("kind")))) {
                    continue;
                }
                String name = String.valueOf(asMap(doc.get("metadata")).get("name"));
                Set<String> strings = new TreeSet<>();
                collectStrings(doc, strings);
                for (Map.Entry<String, String> reader : readers.entrySet()) {
                    if (strings.contains(reader.getKey())) {
                        assertThat(name).as("%s reads %s", name, reader.getKey()).isEqualTo(reader.getValue());
                        seenReading.add(reader.getValue());
                    }
                }
            }
        }
        assertThat(seenReading).as("both readers were found, so the walk read them").containsExactlyInAnyOrder("finapp-app", "finapp-migrator");
    }

    @Test
    @DisplayName("nothing under deploy/ commits a Secret or generates one, in any file, including one no overlay names")
    void nothingUnderDeployCommitsASecret() {
        List<String> offences = new ArrayList<>();
        int read = 0;
        for (Path file : deployFiles()) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            String text = readFile(file);
            if (!isYaml(file)) {
                if (Pattern.compile("(?m)^\\s*(-\\s+)?\"?kind\"?\\s*:\\s*[\"']?Secret[\"']?\\s*(,|$)").matcher(text).find() || text.contains("secretGenerator")) {
                    offences.add(relative);
                }
                continue;
            }
            read++;
            for (Map<String, Object> doc : loadAll(text)) {
                if (containsASecretObject(doc)) {
                    offences.add(relative + " commits a Secret");
                }
                if (doc.containsKey("secretGenerator")) {
                    offences.add(relative + " generates a Secret");
                }
            }
        }
        assertThat(read).as("the deploy tree was read").isGreaterThan(10);
        assertThat(offences).isEmpty();
    }

    @Test
    @DisplayName("the two ExternalSecrets state the decisions and read one store each; an overlay with a store renders them, kind does not")
    void theDeliveryDecisionsAreStated() {
        for (String[] pair : new String[][] {{APP_DELIVERY, "finapp-app-store"}, {MIGRATOR_DELIVERY, "finapp-migrator-store"}}) {
            Map<String, Object> delivery = loadAll(read(pair[0])).get(0);
            Map<String, Object> spec = asMap(delivery.get("spec"));
            assertThat(spec).containsEntry("refreshPolicy", "OnChange").containsEntry("refreshInterval", "0s").doesNotContainKey("dataFrom");
            assertThat(asMap(spec.get("target"))).containsEntry("creationPolicy", "Owner").containsEntry("deletionPolicy", "Retain");
            assertThat(asMap(spec.get("secretStoreRef"))).containsEntry("kind", "SecretStore").containsEntry("name", pair[1]);
            assertThat(asMap(asMap(delivery.get("metadata")).get("annotations"))).containsEntry(GENERATION, "0");
            assertThat(delivery.get("kind")).isEqualTo("ExternalSecret");
        }
        Map<String, Object> deployment = loadAll(read(DEPLOYMENT)).get(0);
        Map<String, Object> template = asMap(asMap(deployment.get("spec")).get("template"));
        assertThat(asMap(asMap(template.get("metadata")).get("annotations"))).as("what carries a rotated secret to the fleet").containsEntry(GENERATION, "0");

        for (String overlay : List.of("staging", "production")) {
            String kustomization = read("deploy/overlays/" + overlay + "/kustomization.yaml");
            assertThat(kustomization).contains("../../components/secret-delivery").contains("secret-store.yaml");
            assertThat(read("deploy/overlays/" + overlay + "/secret-store.yaml"))
                    .contains("prefix: finapp/" + overlay + "/app/").contains("prefix: finapp/" + overlay + "/migrator/")
                    .contains("region: placeholder-region-0").contains("arn:aws:iam::000000000000:role/");
        }
        assertThat(read("deploy/overlays/kind/kustomization.yaml")).as("kind has no store").doesNotContain("secret-delivery");
    }

    @Test
    @DisplayName("the operator is pinned by digest, watched by the weekly job and namespaced: no cluster role, no webhook, nothing that reaches a human's role")
    void theOperatorIsPinnedAndNamespaced() {
        String pins = read(PINS);
        String repository = pin(pins, "ESO_REPOSITORY");
        String version = pin(pins, "ESO_VERSION");
        String digest = pin(pins, "ESO_DIGEST");
        assertThat(digest).matches("sha256:[0-9a-f]{64}");
        assertThat(pin(pins, "ESO_CRD_SCHEMAS_COMMIT")).matches("[0-9a-f]{40}");

        Map<String, Object> values = loadAll(read(OPERATOR_VALUES)).get(0);
        Map<String, Object> image = asMap(values.get("image"));
        assertThat(image).containsEntry("repository", repository).containsEntry("tag", version + "@" + digest);
        assertThat(values).containsEntry("scopedRBAC", true).containsEntry("scopedNamespace", "finapp").containsEntry("leaderElect", true)
                .containsEntry("processClusterExternalSecret", false).containsEntry("processClusterPushSecret", false)
                .containsEntry("processClusterStore", false).containsEntry("processClusterGenerator", false).containsEntry("processPushSecret", false);
        assertThat(asMap(values.get("webhook"))).containsEntry("create", false);
        assertThat(asMap(values.get("certController"))).containsEntry("create", false);
        assertThat(asMap(values.get("rbac"))).containsEntry("aggregateToView", false).containsEntry("aggregateToEdit", false).containsEntry("aggregateToAdmin", false);
        assertThat(asMap(asMap(values.get("rbac")).get("servicebindings"))).containsEntry("create", false);
        assertThat(((Number) values.get("replicaCount")).intValue()).as("N is never 1").isGreaterThan(1);

        assertThat(read(PIN_CHECK)).contains("\"$ESO_REPOSITORY\"").contains("\"$ESO_DIGEST\"");
        assertThat(read(MANIFEST_CHECK)).contains("${ESO_CRD_SCHEMAS_COMMIT}").doesNotContain("-ignore-missing-schemas");
    }

    @Test
    @DisplayName("kind's generator has no list of its own, its suffix rule is the inventory's, it is exercised by the manifest check and committed executable")
    void theKindGeneratorReadsTheExternalSecrets() throws IOException, InterruptedException {
        String generator = read(GENERATOR);
        assertThat(generator).contains("keys_of externalsecret-app.yaml").contains("keys_of externalsecret-migrator.yaml");
        assertThat(generator).as("no inventoried name is written here, but the bootstrap's").doesNotContain("FINAPP_MFA_KEY");
        assertThat(read(MANIFEST_CHECK)).contains("in_kustomize kind-secrets").contains("kind-secrets.sh render");

        for (Row row : inventory) {
            assertThat(row.variable().endsWith("_KEY") || row.variable().endsWith("_PASSWORD"))
                    .as("the generator makes a value for %s by its suffix", row.variable()).isTrue();
        }
        for (Beside row : beside) {
            assertThat(row.variable().endsWith("_KEY") || row.variable().endsWith("_PASSWORD"))
                    .as("the generator asks the caller for %s, which is not a secret", row.variable()).isFalse();
        }

        Process git = new ProcessBuilder("git", "ls-files", "-s", GENERATOR).directory(root.toFile()).redirectErrorStream(true).start();
        String index = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(git.waitFor()).as("git answered: %s", index).isZero();
        assertThat(index).as("the mode the index holds").startsWith("100755 ");
    }

    // ---------------------------------------------------------------------------------------------- reading

    private static void assertConsumerReads(Row row) {
        Matcher named = Pattern.compile("\\(([A-Za-z.]+)\\)").matcher(row.consumer());
        assertThat(named.find()).as("%s names the code that reads it: %s", row.variable(), row.consumer()).isTrue();
        String reader = named.group(1);
        String text = reader.equals("application.yaml") ? applicationText : sourceOf(reader);
        assertThat(text).as("%s claims to read %s", reader, row.variable())
                .containsAnyOf(row.variable(), row.property().equals(NOT_DELIVERED) ? row.variable() : row.property());
    }

    private static String sourceOf(String simpleName) {
        return sources.entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("/" + simpleName + ".java"))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no production class named " + simpleName));
    }

    private static Set<String> inventoryVariables() {
        Set<String> variables = new TreeSet<>();
        inventory.forEach(row -> variables.add(row.variable()));
        return variables;
    }

    private static Set<String> deliveredThrough(String secret) {
        Set<String> variables = new TreeSet<>();
        inventory.stream().filter(row -> row.delivered().contains(secret)).forEach(row -> variables.add(row.variable()));
        beside.stream().filter(row -> row.delivered().contains(secret)).forEach(row -> variables.add(row.variable()));
        return variables;
    }

    private static List<String> delivered(String cell) {
        return Stream.of(cell.split(",")).map(String::trim).toList();
    }

    private static boolean propertyIsRead(String property) {
        return readProperties.contains(property) || flatten(application).containsKey(property);
    }

    /** Each entry's name, refusing an entry whose store key differs (the policy refuses it too; this needs no Docker). */
    private static Set<String> entries(String file) {
        Map<String, Object> delivery = loadAll(read(file)).get(0);
        Set<String> names = new TreeSet<>();
        for (Object entry : (List<?>) asMap(delivery.get("spec")).get("data")) {
            Map<String, Object> map = asMap(entry);
            assertThat(asMap(map.get("remoteRef")).get("key")).as("%s reads the store entry of its own name", map.get("secretKey")).isEqualTo(map.get("secretKey"));
            assertThat(names.add(String.valueOf(map.get("secretKey")))).as("%s is written twice", map.get("secretKey")).isTrue();
        }
        return names;
    }

    private static Map<String, Class<?>> keySpecClasses() {
        Map<String, Class<?>> classes = new TreeMap<>();
        for (Class<?> type : ConfinedCredentialVariablesTest.applicationClasses()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == KeySpec.class) {
                    classes.put(specOf(type).environmentVariable(), type);
                }
            }
        }
        return classes;
    }

    private static KeySpec specOf(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == KeySpec.class) {
                try {
                    field.setAccessible(true);
                    return (KeySpec) field.get(null);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new AssertionError(type + " declares no KeySpec");
    }

    /** {@code @Value("${property:<the marked default>}")}: the mark of a credential, as the compiler folded it. */
    private static Set<String> markedDefaultVariables() {
        Pattern marked = Pattern.compile("^\\$\\{([a-zA-Z0-9._-]+):" + Pattern.quote(ConfinedCredential.MARKED_LOCAL_DEFAULT) + "}$");
        Set<String> variables = new TreeSet<>();
        for (String expression : ConfinedCredentialVariablesTest.valueExpressions()) {
            Matcher found = marked.matcher(expression);
            if (found.matches()) {
                variables.add(environmentFormOf(found.group(1)));
            }
        }
        return variables;
    }

    private static Set<String> placeholdersWithTheMarkedDefault() {
        Pattern marked = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):" + Pattern.quote(ConfinedCredential.MARKED_LOCAL_DEFAULT) + "}");
        Set<String> variables = new TreeSet<>();
        Matcher found = marked.matcher(applicationText);
        while (found.find()) {
            variables.add(found.group(1));
        }
        return variables;
    }

    private static Set<String> placeholders() {
        Set<String> variables = new TreeSet<>();
        Matcher found = PLACEHOLDER_VARIABLE.matcher(applicationText);
        while (found.find()) {
            variables.add(found.group(1));
        }
        return variables;
    }

    private static Set<String> migratorVariables() {
        Set<String> variables = new TreeSet<>();
        sources.forEach((path, source) -> {
            if (path.startsWith("migrator/")) {
                Matcher found = MIGRATOR_VARIABLE.matcher(source);
                while (found.find()) {
                    variables.add(found.group(1));
                }
            }
        });
        return variables;
    }

    private static Set<String> migratorPasswords() {
        Set<String> passwords = new TreeSet<>(migratorVariables());
        passwords.removeIf(name -> !name.endsWith("_PASSWORD"));
        return passwords;
    }

    /** Every property a class reads, in any module, and every key of application.yaml's {@code finapp} tree. */
    private static Set<String> readProperties() {
        Set<String> properties = new TreeSet<>();
        for (String source : sources.values()) {
            Matcher found = READ_PROPERTY.matcher(source);
            while (found.find()) {
                for (int group = 1; group <= found.groupCount(); group++) {
                    if (found.group(group) != null) {
                        properties.add(found.group(group));
                    }
                }
            }
        }
        flatten(application).keySet().stream().filter(key -> key.startsWith("finapp.")).forEach(properties::add);
        return properties;
    }

    private static Map<String, Object> flatten(Map<String, Object> tree) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", tree, flat);
        return flat;
    }

    private static void flatten(String prefix, Map<String, Object> tree, Map<String, Object> flat) {
        tree.forEach((key, value) -> {
            String name = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?>) {
                flatten(name, asMap(value), flat);
            } else {
                flat.put(name, value);
            }
        });
    }

    /** Spring Boot's canonical mapping, the one {@link #everyVariableReachesItsProperty} asks Spring itself about. */
    private static String environmentFormOf(String property) {
        return property.replace("-", "").replace('.', '_').toUpperCase(Locale.ROOT);
    }

    private static String lastSegment(String packageName) {
        return packageName.substring(packageName.lastIndexOf('.') + 1);
    }

    private static String pin(String pins, String name) {
        Matcher found = Pattern.compile("(?m)^" + name + "=(\\S+)\\s*$").matcher(pins);
        assertThat(found.find()).as("%s is pinned", name).isTrue();
        return found.group(1);
    }

    /** A markdown table under the heading starting {@code heading}, its header required to be {@code header}. */
    private static List<List<String>> table(String heading, List<String> header) {
        int start = -1;
        String[] lines = document.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (lines[index].startsWith(heading)) {
                start = index;
                break;
            }
        }
        assertThat(start).as("%s has a section %s", DOCUMENT, heading).isNotNegative();
        int index = start + 1;
        while (index < lines.length && !lines[index].startsWith("|")) {
            assertThat(lines[index].startsWith("#")).as("section %s has a table before the next heading", heading).isFalse();
            index++;
        }
        assertThat(cells(lines[index])).as("the table's header under %s", heading).isEqualTo(header);
        assertThat(lines[index + 1]).as("the header's separator").startsWith("|---");
        List<List<String>> rows = new ArrayList<>();
        for (index += 2; index < lines.length && lines[index].startsWith("|"); index++) {
            List<String> row = cells(lines[index]);
            assertThat(row).as("a row of %s has every column: %s", heading, lines[index]).hasSameSizeAs(header);
            rows.add(row);
        }
        return rows;
    }

    private static List<String> cells(String line) {
        String trimmed = line.trim();
        assertThat(trimmed).as("a table row").startsWith("|").endsWith("|");
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.substring(1, trimmed.length() - 1).split("\\|", -1)) {
            cells.add(cell.replace("`", "").trim());
        }
        return cells;
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.equals("Kustomization");
    }

    private static boolean containsASecretObject(Object node) {
        if (node instanceof Map<?, ?> map) {
            if ("Secret".equals(map.get("kind"))) {
                return true;
            }
            return map.values().stream().anyMatch(SecretInventoryIsCompleteTest::containsASecretObject);
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(SecretInventoryIsCompleteTest::containsASecretObject);
        }
        return false;
    }

    private static void collectStrings(Object node, Set<String> strings) {
        if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> collectStrings(value, strings));
        } else if (node instanceof List<?> list) {
            list.forEach(value -> collectStrings(value, strings));
        } else if (node instanceof String text) {
            strings.add(text);
        }
    }

    private static List<Path> deployFiles() {
        try (Stream<Path> walk = Files.walk(root.resolve("deploy"))) {
            return walk.filter(Files::isRegularFile).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every module's {@code src/main/java}, by path relative to the repository's root. */
    private static Map<String, String> productionSources() {
        Map<String, String> found = new TreeMap<>();
        try (Stream<Path> modules = Files.list(root)) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(main)) {
                    for (Path file : walk.filter(path -> path.toString().endsWith(".java")).toList()) {
                        found.put(root.relativize(file).toString().replace('\\', '/'), Files.readString(file, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(String path) {
        return readFile(root.resolve(path));
    }

    private static List<Map<String, Object>> loadAll(String text) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object doc : yaml.loadAll(text)) {
            if (doc instanceof Map<?, ?>) {
                documents.add(asMap(doc));
            }
        }
        return documents;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        assertThat(value).as("a mapping").isInstanceOf(Map.class);
        return (Map<String, Object>) value;
    }

    private static String readFile(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + file, e);
        }
    }
}
