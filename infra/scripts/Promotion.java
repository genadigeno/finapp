import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The promotion's judgements over the repository (P15-TSK-017, ADR-0122 decision 4; OPERATIONS_RUNBOOK.md section 11.36). A
 * release is a pair of digests, the application's and the migrator's, built from one commit; an environment runs the pair its
 * overlay names. Under pull-based GitOps the merge of a change to that overlay is the deploy, so everything that must hold
 * before a release runs is judged here, before the change is written, and judged again by CI on the pull request.
 *
 * <p>{@code infra/scripts/promote.sh} verifies each digest's signature and both attestations against the environment's
 * authority first; this file never reaches a registry. {@code PromotionTest} holds every refusal below beside the same command
 * accepting the good case.
 *
 * <pre>
 *   check  ROOT ENVIRONMENT APP MIGRATOR REVISION     refuse, or say the pair may be written
 *   write  ROOT ENVIRONMENT APP MIGRATOR REVISION     check, then write the overlay's two digests and its release record
 *   smoke  ROOT ENVIRONMENT SNAPSHOT PROMETHEUS RECORD-DIRECTORY|-
 *                                                     judge what the cluster runs against what the overlay names
 *   held   ROOT ENVIRONMENT STEM VERSION              the environment's newest record shows every replica holding VERSION
 * </pre>
 *
 * <p>Exit status: 0 accepted; 1 refused, the reason printed; 2 the judgement could not be made (an unreadable history, a
 * snapshot in another shape), which is never a pass.
 */
public final class Promotion {

    static final Set<String> ENVIRONMENTS = Set.of("staging", "production");
    static final String ZERO_DIGEST = "sha256:" + "0".repeat(64);
    static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

    /** DATA_MIGRATIONS.md section 4.1, the form {@code MigrationLint.MARKER_FORM} holds; {@code PromotionTest} holds the two equal. */
    static final String MARKER_FORM =
            "^-- finapp-contract: first-conforming-release=(ANY|[0-9]+\\.[0-9]+\\.[0-9]+); waives=([A-Z_]+(?:,[A-Z_]+)*); reason=(\\S.*)$";
    static final Pattern MARKER = Pattern.compile(MARKER_FORM);
    /** Anything that reads like a marker, the lint's own test of one: a line it cannot read is refused, never passed over. */
    static final Pattern MARKER_LIKE = Pattern.compile("^--\\s*finapp-contract\\b.*$", Pattern.CASE_INSENSITIVE);

    /** Where the Gradle version lives: a release is named by it, less -SNAPSHOT (DATA_MIGRATIONS.md section 4.1). */
    static final String CONVENTIONS = "build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts";
    static final Pattern GRADLE_VERSION = Pattern.compile("(?m)^version = \"([0-9]+\\.[0-9]+\\.[0-9]+)(?:-SNAPSHOT)?\"\\s*$");
    static final Pattern MIGRATION = Pattern.compile("([a-z][a-z0-9]*)/src/main/resources/db/migration/[a-z0-9_]+/V([0-9]+(?:[._][0-9]+)*)__[^/]+\\.sql");

    static final String TRIAL_BALANCE_QUERY = "max by (currency) (finapp_ledger_trial_balance)";
    static final String[] VERDICTS = {"health", "build-identity", "trial-balance"};

    private Promotion() {}

    /** A refusal: the change must not merge, and the reason says what to do instead. */
    static final class Refused extends Exception {
        Refused(String reason) {
            super(reason);
        }
    }

    /** The judgement could not be made: never read as a pass. */
    static final class CannotJudge extends Exception {
        CannotJudge(String reason) {
            super(reason);
        }
    }

    public static void main(String[] args) {
        try {
            if (args.length == 0) {
                throw new CannotJudge("usage: check|write|smoke|held ...");
            }
            switch (args[0]) {
                case "check" -> {
                    arity(args, 6);
                    check(Path.of(args[1]), args[2], args[3], args[4], args[5]).forEach(line -> System.out.println("ok   " + line));
                    System.out.println("ACCEPTED: " + args[2] + " may run " + args[3] + " and " + args[4]);
                }
                case "write" -> {
                    arity(args, 6);
                    check(Path.of(args[1]), args[2], args[3], args[4], args[5]).forEach(line -> System.out.println("ok   " + line));
                    write(Path.of(args[1]), args[2], args[3], args[4], args[5]);
                    System.out.println("WRITTEN: deploy/overlays/" + args[2] + " now names " + args[3] + " and " + args[4]);
                }
                case "smoke" -> {
                    arity(args, 6);
                    smoke(Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]), args[5]);
                }
                case "held" -> {
                    arity(args, 5);
                    System.out.println("ok   " + held(Path.of(args[1]), args[2], args[3], args[4]));
                }
                default -> throw new CannotJudge("unknown command " + args[0]);
            }
        } catch (Refused refused) {
            System.out.println("REFUSED: " + refused.getMessage());
            System.exit(1);
        } catch (CannotJudge cannot) {
            System.out.println("CANNOT JUDGE: " + cannot.getMessage());
            System.exit(2);
        } catch (IOException | RuntimeException failure) {
            System.out.println("CANNOT JUDGE: " + failure);
            System.exit(2);
        }
    }

    private static void arity(String[] args, int count) throws CannotJudge {
        if (args.length != count) {
            throw new CannotJudge(args[0] + " takes " + (count - 1) + " arguments, given " + (args.length - 1));
        }
    }

    // ------------------------------------------------------------------------------------------------- check

    static List<String> check(Path root, String environment, String app, String migrator, String revision)
            throws Refused, CannotJudge, IOException {
        List<String> said = new ArrayList<>();
        if (!ENVIRONMENTS.contains(environment)) {
            throw new Refused("'" + environment + "' is not an environment a promotion writes: staging or production");
        }
        digest("the application", app);
        digest("the migrator", migrator);
        said.add("both images are named by a digest, and neither is the placeholder");
        if (!REVISION.matcher(revision).matches()) {
            throw new Refused("the revision must be the full forty-character commit the images were built from, not '" + revision + "'");
        }
        if (git(root, true, "cat-file", "-e", revision + "^{commit}") == null) {
            throw new Refused("the revision " + revision + " is not a commit of this repository");
        }
        if (!ancestor(root, revision, "HEAD")) {
            throw new Refused("the revision " + revision + " is not on this branch's history: a release is built from the branch it is promoted on");
        }
        String release = releaseOf(root, revision);
        said.add("the revision is a commit of this branch, release " + release);

        if (environment.equals("production")) {
            said.add(stagingEvidence(root, app, migrator, revision));
        }
        said.add(contractMarkers(root, environment, revision, release));
        return said;
    }

    /** A digest and nothing else: a tag can be moved, and a name beside the digest would let the overlay say two things. */
    static void digest(String what, String value) throws Refused {
        if (value.equals(ZERO_DIGEST)) {
            throw new Refused(what + " is the placeholder digest, which no registry serves");
        }
        if (!DIGEST.matcher(value).matches()) {
            throw new Refused(what + " is named by '" + value + "', which is not a digest: a tag, or a name with one, is refused;"
                    + " promote sha256: and sixty-four lower-case hexadecimal characters");
        }
    }

    /** The release a revision was built as: its Gradle version, less -SNAPSHOT. */
    static String releaseOf(Path root, String revision) throws CannotJudge {
        String conventions = git(root, false, "show", revision + ":" + CONVENTIONS);
        Matcher version = GRADLE_VERSION.matcher(conventions);
        if (!version.find()) {
            throw new CannotJudge(CONVENTIONS + " at " + revision + " names no version in the form x.y.z");
        }
        return version.group(1);
    }

    // -------------------------------------------------------------------------------------- staging evidence

    static String stagingEvidence(Path root, String app, String migrator, String revision) throws Refused, CannotJudge, IOException {
        boolean ran = false;
        for (Map<String, String> record : releaseHistory(root, "staging")) {
            if (app.equals(record.get("app")) && migrator.equals(record.get("migrator")) && revision.equals(record.get("revision"))) {
                ran = true;
            }
        }
        if (!ran) {
            throw new Refused("staging never ran this pair: no release record of deploy/overlays/staging names " + app + ", " + migrator
                    + " and " + revision + ". Promote it to staging first");
        }
        for (Map.Entry<String, Map<String, String>> entry : records(root, "staging").entrySet()) {
            Map<String, String> record = entry.getValue();
            if (app.equals(record.get("app")) && migrator.equals(record.get("migrator")) && revision.equals(record.get("revision"))
                    && "staging".equals(record.get("environment")) && allPass(record)) {
                return "staging ran this pair and its smoke passed: deploy/evidence/staging/" + entry.getKey();
            }
        }
        throw new Refused("no staging evidence for this pair: no record under deploy/evidence/staging names " + app + ", " + migrator
                + " and " + revision + " with health, build-identity and trial-balance PASS. Run infra/scripts/post-deploy-smoke.sh"
                + " staging --record after staging synced it, and commit what it writes");
    }

    static boolean allPass(Map<String, String> record) {
        for (String verdict : VERDICTS) {
            if (!"PASS".equals(record.get(verdict))) {
                return false;
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------------------- contract markers

    /**
     * DATA_MIGRATIONS.md section 4.1, the promotion's rule: a rollback to release R is refused when any migration above R's own
     * newest version in its module carries a marker naming a release newer than R; ANY never refuses. The schema the environment
     * holds is the newest release it has ever run, because a schema is never rolled back.
     */
    static String contractMarkers(Path root, String environment, String target, String release) throws Refused, CannotJudge, IOException {
        Set<String> ran = new LinkedHashSet<>();
        for (Map<String, String> record : releaseHistory(root, environment)) {
            String revision = record.get("revision");
            if (revision != null && !revision.equals("none")) {
                if (!REVISION.matcher(revision).matches() || git(root, true, "cat-file", "-e", revision + "^{commit}") == null) {
                    throw new CannotJudge("deploy/overlays/" + environment + "/release.yaml once named '" + revision
                            + "', which is not a commit here: the schema it holds cannot be read");
                }
                ran.add(revision);
            }
        }
        if (ran.isEmpty()) {
            return environment + " has run no release: nothing it holds can exclude this one";
        }
        String newest = null;
        for (String candidate : ran) {
            boolean coversAll = true;
            for (String other : ran) {
                coversAll &= ancestor(root, other, candidate);
            }
            if (coversAll) {
                newest = candidate;
            }
        }
        if (newest == null) {
            throw new CannotJudge(environment + "'s releases are not one line of history, so the schema it holds cannot be named");
        }
        if (ancestor(root, newest, target)) {
            return "forward: " + target + " holds every migration " + environment + " has run (" + newest + ")";
        }
        if (!ancestor(root, target, newest)) {
            throw new Refused(target + " is neither before nor after " + newest + ", the newest release " + environment
                    + " has run: a release comes from one line of history");
        }

        Map<String, String> newestAtTarget = new HashMap<>();
        for (String path : migrations(root, target)) {
            Matcher migration = MIGRATION.matcher(path);
            migration.matches();
            newestAtTarget.merge(migration.group(1), migration.group(2), (a, b) -> compareVersions(a, b) >= 0 ? a : b);
        }
        int read = 0;
        for (String path : migrations(root, newest)) {
            Matcher migration = MIGRATION.matcher(path);
            migration.matches();
            String own = newestAtTarget.get(migration.group(1));
            if (own != null && compareVersions(migration.group(2), own) <= 0) {
                continue;
            }
            read++;
            List<String> markers = new ArrayList<>();
            for (String line : git(root, false, "show", newest + ":" + path).split("\r?\n")) {
                if (MARKER_LIKE.matcher(line).matches()) {
                    markers.add(line);
                }
            }
            if (markers.isEmpty()) {
                continue;
            }
            if (markers.size() > 1) {
                throw new Refused(path + " carries " + markers.size() + " lines that read as contract markers, so which one binds cannot be told");
            }
            Matcher marker = MARKER.matcher(markers.get(0));
            if (!marker.matches()) {
                throw new Refused(path + " carries a contract marker this script cannot read: " + markers.get(0));
            }
            String conforming = marker.group(1);
            if (!conforming.equals("ANY") && compareVersions(conforming, release) > 0) {
                throw new Refused("a rollback to " + target + " (release " + release + ") crosses " + path + ", whose contract marker names"
                        + " " + conforming + " as the first release whose code conforms to it. " + environment
                        + "'s schema holds it, and release " + release + " cannot serve that schema: past a contract the way back is a"
                        + " forward fix (DATA_MIGRATIONS.md section 4.1)");
            }
        }
        return "a rollback to " + target + " (release " + release + ") crosses no contract marker: " + read
                + " migration(s) above its own newest read in " + newest;
    }

    static List<String> migrations(Path root, String revision) throws CannotJudge {
        List<String> found = new ArrayList<>();
        for (String path : git(root, false, "ls-tree", "-r", "--name-only", revision).split("\r?\n")) {
            if (MIGRATION.matcher(path).matches()) {
                found.add(path);
            }
        }
        return found;
    }

    /** Versions compared as versions: 35 after 4, and 1.10 after 1.9. */
    static int compareVersions(String left, String right) {
        String[] a = left.split("[._]");
        String[] b = right.split("[._]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? Long.parseLong(a[i]) : 0;
            long y = i < b.length ? Long.parseLong(b[i]) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    // -------------------------------------------------------------------------------------- the release record

    /** Every release record this environment's overlay has held, committed and in the working tree. */
    static List<Map<String, String>> releaseHistory(Path root, String environment) throws CannotJudge, IOException {
        String file = "deploy/overlays/" + environment + "/release.yaml";
        List<Map<String, String>> history = new ArrayList<>();
        Path current = root.resolve(file);
        if (Files.exists(current)) {
            history.add(fields(Files.readString(current, StandardCharsets.UTF_8)));
        }
        String commits = git(root, false, "log", "--format=%H", "--", file);
        for (String commit : commits.split("\r?\n")) {
            if (!commit.isBlank()) {
                String text = git(root, true, "show", commit.strip() + ":" + file);
                if (text != null) {
                    history.add(fields(text));
                }
            }
        }
        return history;
    }

    /** The {@code key: value} lines of a flat record; comments and indented lines are not fields. */
    static Map<String, String> fields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : text.split("\r?\n")) {
            Matcher field = Pattern.compile("^([a-z][a-z-]*): *\"?([^\"#]*?)\"? *$").matcher(line);
            if (field.matches()) {
                fields.put(field.group(1), field.group(2));
            }
        }
        return fields;
    }

    static void write(Path root, String environment, String app, String migrator, String revision) throws CannotJudge, IOException {
        Path overlay = root.resolve("deploy/overlays/" + environment + "/kustomization.yaml");
        String text = Files.readString(overlay, StandardCharsets.UTF_8);
        for (String[] image : new String[][] {{"finapp-app", app}, {"finapp-migrator", migrator}}) {
            Pattern entry = Pattern.compile("(?m)^(  - name: " + image[0] + "\\R    digest: )sha256:[0-9a-f]{64}$");
            Matcher found = entry.matcher(text);
            if (!found.find() || found.find()) {
                throw new CannotJudge(overlay + " does not name " + image[0] + " exactly once in the form this script writes");
            }
            text = entry.matcher(text).replaceFirst("$1" + image[1]);
        }
        Path record = root.resolve("deploy/overlays/" + environment + "/release.yaml");
        StringBuilder header = new StringBuilder();
        for (String line : Files.readString(record, StandardCharsets.UTF_8).split("\r?\n")) {
            if (line.startsWith("#")) {
                header.append(line).append('\n');
            }
        }
        Files.writeString(overlay, text, StandardCharsets.UTF_8);
        Files.writeString(record, header + "release: \"" + releaseOf(root, revision) + "\"\nrevision: \"" + revision + "\"\napp: \"" + app
                + "\"\nmigrator: \"" + migrator + "\"\n", StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------------------- the smoke

    /**
     * The read-only post-deploy smoke's judgement, over what {@code post-deploy-smoke.sh} read from the cluster and from
     * Prometheus. Three verdicts, every one required for a record: health (the rollout complete, every replica ready and on one
     * ReplicaSet and one configuration, none terminating, the release's migrator Job succeeded), build identity (every
     * replica's image and the image the kubelet resolved are the overlay's application digest, and the migrator's are its
     * migrator digest: a digest compared with a digest, so the commit is the one the promotion verified the provenance of) and the
     * trial balance (every currency's verdict zero, at least one currency answered).
     */
    static void smoke(Path root, String environment, Path snapshot, Path prometheus, String recordDirectory)
            throws Refused, CannotJudge, IOException {
        if (!ENVIRONMENTS.contains(environment)) {
            throw new CannotJudge("'" + environment + "' is not an environment the smoke judges");
        }
        Map<String, String> release = fields(Files.readString(root.resolve("deploy/overlays/" + environment + "/release.yaml"), StandardCharsets.UTF_8));
        String app = release.get("app");
        String migrator = release.get("migrator");
        if (app == null || migrator == null || !DIGEST.matcher(app).matches() || !DIGEST.matcher(migrator).matches() || app.equals(ZERO_DIGEST)) {
            throw new CannotJudge("deploy/overlays/" + environment + "/release.yaml names no promoted pair, so there is nothing to compare with");
        }

        List<String[]> deployments = new ArrayList<>();
        List<String[]> pods = new ArrayList<>();
        List<String[]> jobs = new ArrayList<>();
        List<String[]> jobPods = new ArrayList<>();
        Map<String, String> configuration = new TreeMap<>();
        for (String line : Files.readAllLines(snapshot, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] field = line.split("\\|", -1);
            switch (field[0]) {
                case "deployment" -> deployments.add(shape(field, 8, line));
                case "pod" -> pods.add(shape(field, 8, line));
                case "job" -> jobs.add(shape(field, 4, line));
                case "jobpod" -> jobPods.add(shape(field, 3, line));
                case "configmap" -> configuration.put(shape(field, 3, line)[1], field[2]);
                default -> throw new CannotJudge("a snapshot line of no known kind: " + line);
            }
        }

        Map<String, String> verdicts = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();

        // Health.
        List<String> unhealthy = new ArrayList<>();
        String configMap = null;
        if (deployments.size() != 1) {
            unhealthy.add("the snapshot holds " + deployments.size() + " Deployments named finapp-app, not one");
        } else {
            String[] d = deployments.get(0);
            long generation = number(d[1]);
            long observed = number(d[2]);
            long wanted = number(d[3]);
            if (observed < generation) {
                unhealthy.add("the Deployment's controller has not observed generation " + generation + " (it has " + observed + ")");
            }
            if (wanted < 1 || number(d[4]) != wanted || number(d[5]) != wanted || number(d[6]) != wanted || number(d[7]) != wanted) {
                unhealthy.add("the rollout is not complete: wanted " + wanted + ", replicas " + d[4] + ", updated " + d[5] + ", ready " + d[6]
                        + ", available " + d[7]);
            }
            Set<String> owners = new LinkedHashSet<>();
            Set<String> configMaps = new LinkedHashSet<>();
            int running = 0;
            for (String[] pod : pods) {
                if (!pod[2].equals("running")) {
                    unhealthy.add("pod " + pod[1] + " is " + pod[2] + ": an old replica may still be consuming; run again once it is gone");
                    continue;
                }
                running++;
                owners.add(pod[3]);
                configMaps.add(pod[7]);
                if (!pod[4].equals("True")) {
                    unhealthy.add("pod " + pod[1] + " is not ready");
                }
            }
            if (running != wanted) {
                unhealthy.add(running + " replica(s) running where the Deployment wants " + wanted);
            }
            if (owners.size() > 1) {
                unhealthy.add("the replicas belong to " + owners.size() + " ReplicaSets: " + owners);
            }
            if (configMaps.size() != 1 || configMaps.contains("")) {
                unhealthy.add("the replicas do not all read one configuration: " + configMaps);
            } else {
                configMap = configMaps.iterator().next();
            }
        }
        if (jobs.size() != 1) {
            unhealthy.add("the snapshot holds " + jobs.size() + " migrator Jobs, not one: run the smoke while the release's Job is kept (one day)");
        } else if (number(jobs.get(0)[2]) < 1) {
            unhealthy.add("the migrator Job " + jobs.get(0)[1] + " has not succeeded");
        }
        verdict(verdicts, reasons, "health", unhealthy);

        // Build identity: what the manifest asked for and what the kubelet resolved, each the promoted digest.
        List<String> foreign = new ArrayList<>();
        for (String[] pod : pods) {
            if (!pod[5].endsWith("@" + app)) {
                foreign.add("pod " + pod[1] + " asks for " + pod[5] + ", not " + app);
            }
            if (!resolved(pod[6], app)) {
                foreign.add("pod " + pod[1] + " runs " + (pod[6].isEmpty() ? "nothing the kubelet named" : pod[6]) + ", not " + app);
            }
        }
        if (pods.isEmpty()) {
            foreign.add("no replica to identify");
        }
        for (String[] job : jobs) {
            if (!job[3].endsWith("@" + migrator)) {
                foreign.add("the migrator Job asks for " + job[3] + ", not " + migrator);
            }
        }
        if (jobPods.isEmpty()) {
            foreign.add("no migrator pod to identify");
        }
        for (String[] pod : jobPods) {
            if (!resolved(pod[2], migrator)) {
                foreign.add("migrator pod " + pod[1] + " ran " + (pod[2].isEmpty() ? "nothing the kubelet named" : pod[2]) + ", not " + migrator);
            }
        }
        verdict(verdicts, reasons, "build-identity", foreign);

        // The trial balance, as Prometheus answers it.
        verdict(verdicts, reasons, "trial-balance", trialBalance(Files.readString(prometheus, StandardCharsets.UTF_8)));

        for (Map.Entry<String, String> verdict : verdicts.entrySet()) {
            System.out.println(verdict.getValue() + " " + verdict.getKey());
        }
        reasons.forEach(reason -> System.out.println("     " + reason));
        if (!reasons.isEmpty()) {
            throw new Refused("the smoke did not pass, and nothing was recorded");
        }

        StringBuilder record = new StringBuilder();
        Instant checked = Clock.systemUTC().instant().truncatedTo(ChronoUnit.SECONDS);
        record.append("# What infra/scripts/post-deploy-smoke.sh saw in ").append(environment).append(", read-only, every verdict PASS.\n");
        record.append("# Written by the script, committed as it was written (OPERATIONS_RUNBOOK.md section 11.36).\n");
        record.append("environment: ").append(environment).append('\n');
        record.append("checked: \"").append(DateTimeFormatter.ISO_INSTANT.format(checked)).append("\"\n");
        record.append("release: \"").append(release.getOrDefault("release", "")).append("\"\n");
        record.append("revision: \"").append(release.getOrDefault("revision", "")).append("\"\n");
        record.append("app: \"").append(app).append("\"\n");
        record.append("migrator: \"").append(migrator).append("\"\n");
        record.append("replicas: ").append(deployments.get(0)[3]).append('\n');
        record.append("configuration: \"").append(configMap).append("\"\n");
        for (String name : VERDICTS) {
            record.append(name).append(": ").append(verdicts.get(name)).append('\n');
        }
        record.append("held:\n");
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            Matcher listing = Pattern.compile("(FINAPP_[A-Z0-9_]+)_RING_VERSIONS").matcher(entry.getKey());
            if (listing.matches()) {
                String stem = listing.group(1);
                String active = configuration.getOrDefault(stem + "_RING_ACTIVEVERSION", "");
                record.append("  - \"").append(stem).append(" listed ").append(entry.getValue().replace(" ", "")).append(" active ")
                        .append(active.isEmpty() ? "default" : active).append("\"\n");
            }
        }
        if (!recordDirectory.equals("-")) {
            Path directory = Path.of(recordDirectory);
            Files.createDirectories(directory);
            Path file = directory.resolve(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC).format(checked)
                    + "-" + app.substring("sha256:".length(), "sha256:".length() + 12) + ".yaml");
            Files.writeString(file, record.toString(), StandardCharsets.UTF_8);
            System.out.println("RECORDED: " + file);
        } else {
            System.out.print(record);
        }
    }

    private static String[] shape(String[] field, int count, String line) throws CannotJudge {
        if (field.length != count) {
            throw new CannotJudge("a snapshot line of " + field.length + " fields where its kind has " + count + ": " + line);
        }
        return field;
    }

    private static long number(String value) throws CannotJudge {
        if (value.isEmpty() || value.equals("<no value>")) {
            return 0;
        }
        if (!value.matches("[0-9]+")) {
            throw new CannotJudge("a count that is not a number: " + value);
        }
        return Long.parseLong(value);
    }

    /** The kubelet's imageID names the manifest digest after an @; an image ID alone (a config digest) cannot be compared, and fails. */
    static boolean resolved(String imageId, String digest) {
        return imageId.endsWith("@" + digest);
    }

    private static void verdict(Map<String, String> verdicts, List<String> reasons, String name, List<String> failures) {
        verdicts.put(name, failures.isEmpty() ? "PASS" : "FAIL");
        failures.forEach(failure -> reasons.add(name + ": " + failure));
    }

    /** Prometheus's instant-query answer for {@link #TRIAL_BALANCE_QUERY}: every series zero, at least one, nothing unread. */
    static List<String> trialBalance(String answer) {
        List<String> failures = new ArrayList<>();
        String compact = answer.replaceAll("\\s+", "");
        if (!compact.startsWith("{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[")) {
            failures.add("Prometheus did not answer a vector for the trial balance: " + compact.substring(0, Math.min(160, compact.length())));
            return failures;
        }
        Matcher series = Pattern.compile("\\{\"metric\":\\{([^{}]*)\\},\"value\":\\[([0-9.eE+-]+),\"([^\"]*)\"\\]\\}").matcher(compact);
        int read = 0;
        while (series.find()) {
            read++;
            String value = series.group(3);
            if (!value.equals("0")) {
                failures.add("the trial balance is not zero for " + (series.group(1).isEmpty() ? "a series" : series.group(1)) + ": verdict " + value);
            }
        }
        int answered = compact.split("\"metric\":", -1).length - 1;
        if (answered != read) {
            failures.add("Prometheus answered " + answered + " series and " + read + " could be read");
        }
        if (read == 0) {
            failures.add("no currency answered: a gauge nobody scraped is not a balanced journal");
        }
        return failures;
    }

    // -------------------------------------------------------------------------------------- held

    /**
     * THREAT_MODEL.md section 6 item 32 (c), as the delivery holds it: a key ring's version is activated in a GitOps environment
     * only once that environment's newest smoke record shows every replica on one configuration that lists the version.
     */
    static String held(Path root, String environment, String stem, String version) throws Refused, IOException {
        Map<String, Map<String, String>> records = records(root, environment);
        if (records.isEmpty()) {
            throw new Refused("no smoke record under deploy/evidence/" + environment + ": nothing shows the fleet holds " + stem
                    + " version " + version + ". List it, let the rollout finish, run infra/scripts/post-deploy-smoke.sh " + environment
                    + " --record, commit the record, then activate");
        }
        String newestName = null;
        Map<String, String> newest = null;
        for (Map.Entry<String, Map<String, String>> entry : records.entrySet()) {
            String checked = entry.getValue().getOrDefault("checked", "");
            if (newest == null || checked.compareTo(newest.getOrDefault("checked", "")) > 0
                    || (checked.equals(newest.getOrDefault("checked", "")) && entry.getKey().compareTo(newestName) > 0)) {
                newest = entry.getValue();
                newestName = entry.getKey();
            }
        }
        if (!environment.equals(newest.get("environment")) || !allPass(newest)) {
            throw new Refused("the newest record, deploy/evidence/" + environment + "/" + newestName + ", is not a passing smoke of "
                    + environment);
        }
        String listing = newest.getOrDefault("held:" + stem, "");
        for (String listed : listing.split(",")) {
            if (listed.equals(version)) {
                return "deploy/evidence/" + environment + "/" + newestName + " shows every replica on one configuration listing " + stem
                        + " version " + version;
            }
        }
        throw new Refused("the newest record, deploy/evidence/" + environment + "/" + newestName + ", shows " + stem + " listing '"
                + listing + "', not version " + version + ": the change that listed it has not been seen rolled out");
    }

    /** Every record of an environment, by file name, its held listings under {@code held:<stem>}. */
    static Map<String, Map<String, String>> records(Path root, String environment) throws IOException {
        Map<String, Map<String, String>> records = new TreeMap<>();
        Path directory = root.resolve("deploy/evidence/" + environment);
        if (!Files.isDirectory(directory)) {
            return records;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Map<String, String> record = fields(text);
                Matcher held = Pattern.compile("(?m)^  - \"(FINAPP_[A-Z0-9_]+) listed ([0-9,]+) active ([0-9]+|default)\"$").matcher(text);
                while (held.find()) {
                    record.put("held:" + held.group(1), held.group(2));
                }
                records.put(file.getFileName().toString(), record);
            }
        }
        return records;
    }

    // -------------------------------------------------------------------------------------- git

    static boolean ancestor(Path root, String older, String newer) throws CannotJudge {
        int status = status(root, "merge-base", "--is-ancestor", older, newer);
        if (status == 0 || status == 1) {
            return status == 0;
        }
        throw new CannotJudge("git could not tell whether " + older + " comes before " + newer);
    }

    /** git's output; null when {@code tolerate} and git refused, which the caller reads as "no such thing". */
    static String git(Path root, boolean tolerate, String... args) throws CannotJudge {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            process.getOutputStream().close();
            byte[] out = process.getInputStream().readAllBytes();
            process.getErrorStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CannotJudge("git " + String.join(" ", args) + " did not answer");
            }
            if (process.exitValue() != 0) {
                if (tolerate) {
                    return null;
                }
                throw new CannotJudge("git " + String.join(" ", args) + " failed with status " + process.exitValue());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new CannotJudge("git could not be run: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CannotJudge("interrupted while git ran");
        }
    }

    static int status(Path root, String... args) throws CannotJudge {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            process.getInputStream().readAllBytes();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new CannotJudge("git " + String.join(" ", args) + " did not answer");
            }
            return process.exitValue();
        } catch (IOException failure) {
            throw new CannotJudge("git could not be run: " + failure.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CannotJudge("interrupted while git ran");
        }
    }
}
