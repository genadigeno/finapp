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
 * overlay names. Under pull-based GitOps the merge of a change to that overlay is the deploy, so everything the repository can
 * hold is judged here, before the change is written, and again by CI on the pull request against what was merged before it.
 * What the repository cannot hold (that the digests were built from the revision named) promote.sh reads from the registry, and the
 * approver reads again.
 *
 * <p>{@code infra/scripts/promote.sh} verifies each digest's signature and both attestations against the environment's
 * authority first; this file never reaches a registry. {@code PromotionTest} holds every refusal below beside the same command
 * accepting the good case.
 *
 * <pre>
 *   check     ROOT ENVIRONMENT APP MIGRATOR REVISION  refuse, or say the pair may be written (staging's evidence read as committed)
 *   write     ROOT ENVIRONMENT APP MIGRATOR REVISION  check, then write the overlay's two digests and its release record
 *   recheck   ROOT BASE-REF                           CI: each overlay against its record, staging's evidence read at the merge base
 *   smoke     ROOT ENVIRONMENT SNAPSHOT PROMETHEUS RECORD-DIRECTORY|-
 *                                                     judge what the cluster runs against what the overlay names
 *   held      TREE ENVIRONMENT STEM VERSION           the environment's newest record in TREE shows every replica holding VERSION
 *   statement TYPE DIGEST REVISION|-                  one verified in-toto statement, on standard input, read as its structure
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

    /**
     * DATA_MIGRATIONS.md section 4.1, the form {@code MigrationLint.MARKER_FORM} holds; {@code PromotionTest} holds the two equal.
     * Its optional {@code data-fix=} (2026-11-12, `P15-TSK-032`) is the lint's alone: a rollback reads the first group only.
     */
    static final String MARKER_FORM =
            "^-- finapp-contract: first-conforming-release=(ANY|[0-9]+\\.[0-9]+\\.[0-9]+); waives=([A-Z_]+(?:,[A-Z_]+)*)(?:; data-fix=V([0-9]+(?:[._][0-9]+)*))?; reason=(\\S.*)$";
    static final Pattern MARKER = Pattern.compile(MARKER_FORM);
    /** Anything that reads like a marker, the lint's own test of one: a line it cannot read is refused, never passed over. */
    static final Pattern MARKER_LIKE = Pattern.compile("^--\\s*finapp-contract\\b.*$", Pattern.CASE_INSENSITIVE);

    /**
     * Where the Gradle version lives: a release is named by it, {@code -SNAPSHOT} kept (DATA_MIGRATIONS.md section 4.1, its 2026-11-12
     * note). A marker names a release without the suffix, and a snapshot comes before the release it leads to.
     */
    static final String CONVENTIONS = "build-logic/src/main/kotlin/finapp.java-conventions.gradle.kts";
    static final Pattern GRADLE_VERSION = Pattern.compile("(?m)^version = \"([0-9]+\\.[0-9]+\\.[0-9]+(?:-SNAPSHOT)?)\"\\s*$");
    static final Pattern RELEASE = Pattern.compile("([0-9]+)\\.([0-9]+)\\.([0-9]+)(-SNAPSHOT)?");
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
                throw new CannotJudge("usage: check|write|recheck|smoke|held|statement ...");
            }
            switch (args[0]) {
                case "check" -> {
                    arity(args, 6);
                    Judgement judged = check(Path.of(args[1]), args[2], args[3], args[4], args[5], "HEAD");
                    judged.said().forEach(line -> System.out.println("ok   " + line));
                    System.out.println("MIGRATOR-REVISION: " + judged.migratorRevision());
                    System.out.println("ACCEPTED: " + args[2] + " may run " + args[3] + " and " + args[4]);
                }
                case "write" -> {
                    arity(args, 6);
                    Judgement judged = check(Path.of(args[1]), args[2], args[3], args[4], args[5], "HEAD");
                    judged.said().forEach(line -> System.out.println("ok   " + line));
                    write(Path.of(args[1]), args[2], args[3], args[4], args[5], judged.migratorRevision());
                    System.out.println("WRITTEN: deploy/overlays/" + args[2] + " now names " + args[3] + " and " + args[4]);
                }
                case "recheck" -> {
                    arity(args, 3);
                    recheck(Path.of(args[1]), args[2]).forEach(line -> System.out.println("ok   " + line));
                    System.out.println("ACCEPTED: every environment's overlay names what its release record does, and the record passes"
                            + " against what was merged before this change");
                }
                case "smoke" -> {
                    arity(args, 6);
                    smoke(Path.of(args[1]), args[2], Path.of(args[3]), Path.of(args[4]), args[5]);
                }
                case "held" -> {
                    arity(args, 5);
                    System.out.println("ok   " + held(Path.of(args[1]), args[2], args[3], args[4]));
                }
                case "statement" -> {
                    arity(args, 4);
                    System.out.println("ok   " + statement(args[1], args[2], args[3], new String(System.in.readAllBytes(), StandardCharsets.UTF_8)));
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

    /** What the check said, and the commit the migrator digest must have been built from: the release's own, or on a rollback the newest's. */
    record Judgement(List<String> said, String migratorRevision) {}

    /**
     * Every refusal the repository can make. {@code evidenceRef} is where staging's history and evidence are read for a production
     * pair: a commit already merged, so that the change under judgement cannot bring its own evidence (CI passes the merge base with
     * the branch it merges into; promote.sh passes {@code HEAD}, what is committed).
     */
    static Judgement check(Path root, String environment, String app, String migrator, String revision, String evidenceRef)
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
        Line line = Line.of(root);
        if (!line.holds(revision)) {
            throw new Refused("the revision " + revision + " is not on this branch's first-parent history: a release is built from a"
                    + " commit of the branch itself, never from one a side branch carried in");
        }
        String release = releaseOf(root, revision);
        said.add("the revision is a commit of this branch, release " + release);

        String[] schema = contractMarkers(root, environment, revision, release, migrator, line);
        said.add(schema[0]);
        if (environment.equals("production")) {
            said.add(stagingEvidence(root, app, migrator, revision, evidenceRef));
        }
        return new Judgement(said, schema[1]);
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

    /**
     * The release a revision was built as: its Gradle version, {@code -SNAPSHOT} kept. Every commit before a release is cut carries the
     * snapshot of the version it leads to, so dropping the suffix would make a commit from before the code stopped reading a column
     * the same release as the one that stopped, and a rollback to it would pass a marker naming that release.
     */
    static String releaseOf(Path root, String revision) throws CannotJudge {
        String conventions = git(root, false, "show", revision + ":" + CONVENTIONS);
        Matcher version = GRADLE_VERSION.matcher(conventions);
        if (!version.find()) {
            throw new CannotJudge(CONVENTIONS + " at " + revision + " names no version in the form x.y.z");
        }
        return version.group(1);
    }

    /** Releases compared as versions, a snapshot before the release it leads to: 1.3.0-SNAPSHOT before 1.3.0, after 1.2.0. */
    static int compareReleases(String left, String right) {
        Matcher a = RELEASE.matcher(left);
        Matcher b = RELEASE.matcher(right);
        if (!a.matches() || !b.matches()) {
            throw new IllegalArgumentException("not a release name: " + left + " or " + right);
        }
        for (int group = 1; group <= 3; group++) {
            int compared = Long.compare(Long.parseLong(a.group(group)), Long.parseLong(b.group(group)));
            if (compared != 0) {
                return compared;
            }
        }
        return Boolean.compare(a.group(4) == null, b.group(4) == null);
    }

    // -------------------------------------------------------------------------------------- staging evidence

    static String stagingEvidence(Path root, String app, String migrator, String revision, String evidenceRef)
            throws Refused, CannotJudge, IOException {
        boolean ran = false;
        for (Map<String, String> record : releaseHistory(root, "staging", evidenceRef, false)) {
            if (app.equals(record.get("app")) && migrator.equals(record.get("migrator")) && revision.equals(record.get("revision"))) {
                ran = true;
            }
        }
        if (!ran) {
            throw new Refused("staging never ran this pair: no release record of deploy/overlays/staging merged before this change ("
                    + evidenceRef + ") names " + app + ", " + migrator + " and " + revision + ". Promote it to staging first, in a change"
                    + " of its own");
        }
        for (Map.Entry<String, Map<String, String>> entry : recordsAt(root, evidenceRef, "staging").entrySet()) {
            Map<String, String> record = entry.getValue();
            if (app.equals(record.get("app")) && migrator.equals(record.get("migrator")) && revision.equals(record.get("revision"))
                    && "staging".equals(record.get("environment")) && allPass(record)) {
                return "staging ran this pair and its smoke passed, recorded before this change: deploy/evidence/staging/" + entry.getKey();
            }
        }
        throw new Refused("no staging evidence for this pair merged before this change (" + evidenceRef + "): no record under"
                + " deploy/evidence/staging names " + app + ", " + migrator + " and " + revision + " with health, build-identity and"
                + " trial-balance PASS. Run infra/scripts/post-deploy-smoke.sh staging --record after staging synced it, and merge what it"
                + " writes before the production promotion");
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
     *
     * <p>A rollback moves the application alone. The migrator stays the newest release's, whose run on its own schema is a no-op
     * ({@code MigratorMatchesTheBuildTest}): an older migrator on a newer schema is a path no release was ever run on, and under GitOps
     * the migrator runs again on every sync of a release. Returns what was said and the commit the migrator digest must come from.
     */
    static String[] contractMarkers(Path root, String environment, String target, String release, String migrator, Line line)
            throws Refused, CannotJudge, IOException {
        List<Map<String, String>> history = releaseHistory(root, environment, "HEAD", true);
        String newest = null;
        for (Map<String, String> record : history) {
            String revision = record.get("revision");
            if (revision != null && !revision.equals("none")) {
                if (!REVISION.matcher(revision).matches() || !line.holds(revision)) {
                    throw new CannotJudge("deploy/overlays/" + environment + "/release.yaml once named '" + revision
                            + "', which is not a commit of this branch's first-parent history: the schema it holds cannot be read");
                }
                if (newest == null || line.newer(revision, newest)) {
                    newest = revision;
                }
            }
        }
        if (newest == null) {
            return new String[] {environment + " has run no release: nothing it holds can exclude this one", target};
        }
        if (!line.newer(newest, target)) {
            return new String[] {"forward: " + target + " holds every migration " + environment + " has run (" + newest + ")", target};
        }
        Set<String> schemaMigrators = new LinkedHashSet<>();
        for (Map<String, String> record : history) {
            if (newest.equals(record.get("revision")) && record.get("migrator") != null) {
                schemaMigrators.add(record.get("migrator"));
            }
        }
        if (!schemaMigrators.contains(migrator)) {
            throw new Refused("a rollback moves the application alone: the migrator stays " + schemaMigrators + ", the one " + environment
                    + " ran with " + newest + ", whose run on its own schema is a no-op. An older migrator on a newer schema is a path no"
                    + " release was run on, and a schema is never rolled back");
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
            for (String text : git(root, false, "show", newest + ":" + path).split("\r?\n")) {
                if (MARKER_LIKE.matcher(text).matches()) {
                    markers.add(text);
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
            if (!conforming.equals("ANY") && compareReleases(conforming, release) > 0) {
                throw new Refused("a rollback to " + target + " (release " + release + ") crosses " + path + ", whose contract marker names"
                        + " " + conforming + " as the first release whose code conforms to it. " + environment
                        + "'s schema holds it, and release " + release + " cannot serve that schema: past a contract the way back is a"
                        + " forward fix (DATA_MIGRATIONS.md section 4.1)");
            }
        }
        return new String[] {"a rollback to " + target + " (release " + release + ") crosses no contract marker: " + read
                + " migration(s) above its own newest read in " + newest + "; the migrator stays " + newest + "'s", newest};
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

    /** Migration versions compared as versions: 35 after 4, and 1.10 after 1.9. */
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

    /**
     * The branch's own line: {@code HEAD}'s first-parent history, read once. A commit a side branch carried in never ran anywhere, and
     * a release is built from the line, so "newer" is a position on it, never a walk of every pair.
     */
    record Line(Map<String, Integer> position) {
        static Line of(Path root) throws CannotJudge {
            Map<String, Integer> position = new HashMap<>();
            int index = 0;
            for (String commit : git(root, false, "rev-list", "--first-parent", "HEAD").split("\r?\n")) {
                if (!commit.isBlank()) {
                    position.put(commit.strip(), index++);
                }
            }
            return new Line(position);
        }

        boolean holds(String revision) {
            return position.containsKey(revision);
        }

        /** {@code left} came after {@code right} on the line. */
        boolean newer(String left, String right) {
            return position.get(left) < position.get(right);
        }
    }

    // -------------------------------------------------------------------------------------- the release record

    /**
     * Every release record an environment's overlay has held on the line up to {@code ref} ({@code --first-parent}: an intermediate
     * state a side branch carried never ran), and with {@code workingTree} the file as it stands.
     */
    static List<Map<String, String>> releaseHistory(Path root, String environment, String ref, boolean workingTree)
            throws CannotJudge, IOException {
        String file = "deploy/overlays/" + environment + "/release.yaml";
        List<Map<String, String>> history = new ArrayList<>();
        Path current = root.resolve(file);
        if (workingTree && Files.exists(current)) {
            history.add(fields(Files.readString(current, StandardCharsets.UTF_8)));
        }
        String commits = git(root, false, "log", "--first-parent", "--format=%H", ref, "--", file);
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

    static void write(Path root, String environment, String app, String migrator, String revision, String migratorRevision)
            throws CannotJudge, IOException {
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
                + "\"\nmigrator: \"" + migrator + "\"\nmigrator-revision: \"" + migratorRevision + "\"\n", StandardCharsets.UTF_8);
    }

    /**
     * What CI judges on a pull request, with no registry and no credential: each GitOps overlay's {@code images:} names exactly the
     * pair its release record names, by digest alone, and that record passes the same check a promotion makes, its staging evidence
     * read at the merge base with {@code baseRef}, so the change cannot bring its own. What it cannot see: that the digests are the
     * ones built from the revision the record names (the signatures and the provenance are read from the registry, by promote.sh and
     * by the approver re-running its verify), and an image named anywhere but {@code images:} (the manifest policy's
     * {@code release-images} rule holds the rendered overlay to the record).
     */
    static List<String> recheck(Path root, String baseRef) throws Refused, CannotJudge, IOException {
        String base = git(root, true, "merge-base", "HEAD", baseRef);
        if (base == null || !REVISION.matcher(base.strip()).matches()) {
            throw new CannotJudge("no merge base with " + baseRef + ": what was merged before this change cannot be read");
        }
        base = base.strip();
        List<String> said = new ArrayList<>();
        said.add("staging's evidence is read as merged at " + base);
        for (String environment : List.of("staging", "production")) {
            String overlay = Files.readString(root.resolve("deploy/overlays/" + environment + "/kustomization.yaml"), StandardCharsets.UTF_8);
            Map<String, String> record = fields(Files.readString(root.resolve("deploy/overlays/" + environment + "/release.yaml"), StandardCharsets.UTF_8));
            // The images: section, its lines other than blanks and comments: exactly two names, each with a digest and nothing else.
            List<String> lines = new ArrayList<>();
            boolean inside = false;
            for (String line : overlay.split("\r?\n")) {
                if (line.equals("images:")) {
                    inside = true;
                } else if (inside && !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#")) {
                    inside = false;
                } else if (inside && !line.isBlank() && !line.strip().startsWith("#")) {
                    lines.add(line);
                }
            }
            Map<String, String> digests = new HashMap<>();
            for (int i = 0; i + 1 < lines.size(); i += 2) {
                Matcher name = Pattern.compile("  - name: (finapp-app|finapp-migrator)").matcher(lines.get(i));
                Matcher digest = Pattern.compile("    digest: (\\S+)").matcher(lines.get(i + 1));
                if (name.matches() && digest.matches()) {
                    digests.put(name.group(1), digest.group(1));
                }
            }
            if (lines.size() != 4 || digests.size() != 2) {
                throw new Refused("deploy/overlays/" + environment + "'s images are not the two names with a digest each and nothing else: a tag,"
                        + " a new name or a third image is never written by a promotion: " + lines);
            }
            if (!digests.get("finapp-app").equals(record.get("app")) || !digests.get("finapp-migrator").equals(record.get("migrator"))) {
                throw new Refused("deploy/overlays/" + environment + " names " + digests + " and its release record " + record.get("app") + " and "
                        + record.get("migrator") + ": the two are written together, by infra/scripts/promote.sh");
            }
            if ("none".equals(record.get("revision")) && digests.get("finapp-app").equals(ZERO_DIGEST) && digests.get("finapp-migrator").equals(ZERO_DIGEST)) {
                said.add(environment + " has nothing promoted: the placeholders");
                continue;
            }
            Judgement judged = check(root, environment, record.get("app"), record.get("migrator"), record.getOrDefault("revision", ""), base);
            if (!judged.migratorRevision().equals(record.getOrDefault("migrator-revision", record.get("revision")))) {
                throw new Refused("deploy/overlays/" + environment + "/release.yaml says its migrator was built from "
                        + record.get("migrator-revision") + ", where the promotion's rule wants " + judged.migratorRevision());
            }
            judged.said().forEach(line -> said.add(environment + ": " + line));
        }
        return said;
    }

    // -------------------------------------------------------------------------------------- an attestation's statement

    /**
     * One in-toto statement cosign verified, read as the structure it is rather than searched as text: every subject is the digest
     * promoted, the predicate is the type asked for, and a provenance names the revision as its git source's commit, in that field and
     * no other. promote.sh hands each verified payload here, decoded.
     */
    static String statement(String type, String digest, String revision, String json) throws Refused, CannotJudge {
        Object parsed;
        try {
            parsed = new Json(json).document();
        } catch (IllegalArgumentException unreadable) {
            throw new CannotJudge("the statement is not JSON this reader can parse: " + unreadable.getMessage());
        }
        if (!(parsed instanceof Map<?, ?> statement)) {
            throw new Refused("the statement is not an object");
        }
        String predicateType = switch (type) {
            case "slsaprovenance1" -> "https://slsa.dev/provenance/v1";
            case "cyclonedx" -> "https://cyclonedx.org/bom";
            default -> throw new CannotJudge("no predicate type is known for " + type);
        };
        if (!predicateType.equals(statement.get("predicateType"))) {
            throw new Refused("a " + type + " attestation's predicate is " + statement.get("predicateType") + ", not " + predicateType);
        }
        if (!(statement.get("subject") instanceof List<?> subjects) || subjects.isEmpty()) {
            throw new Refused("a " + type + " attestation names no subject");
        }
        String hex = digest.substring("sha256:".length());
        for (Object subject : subjects) {
            Object named = subject instanceof Map<?, ?> entry && entry.get("digest") instanceof Map<?, ?> digests ? digests.get("sha256") : null;
            if (!hex.equals(named)) {
                throw new Refused("a " + type + " attestation names another subject (sha256 " + named + "), not " + digest);
            }
        }
        if (type.equals("slsaprovenance1") && !revision.equals("-")) {
            Object definition = statement.get("predicate") instanceof Map<?, ?> predicate ? predicate.get("buildDefinition") : null;
            Object dependencies = definition instanceof Map<?, ?> build ? build.get("resolvedDependencies") : null;
            List<Object> sources = new ArrayList<>();
            if (dependencies instanceof List<?> list) {
                for (Object dependency : list) {
                    if (dependency instanceof Map<?, ?> entry && String.valueOf(entry.get("uri")).startsWith("git+")) {
                        sources.add(entry.get("digest") instanceof Map<?, ?> digests ? digests.get("gitCommit") : null);
                    }
                }
            }
            if (sources.isEmpty() || !sources.stream().allMatch(revision::equals)) {
                throw new Refused("the provenance's git source names " + sources + " as the commit it was built from, not " + revision
                        + " (predicate.buildDefinition.resolvedDependencies[].digest.gitCommit)");
            }
        }
        return "a " + type + " statement of " + digest + (type.equals("slsaprovenance1") && !revision.equals("-") ? ", built from " + revision : "");
    }

    /** JSON, enough of it to read a statement: objects, arrays, strings with their escapes, numbers, true, false and null. */
    static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object document() {
            Object value = value();
            space();
            if (at != text.length()) {
                throw new IllegalArgumentException("text after the document at " + at);
            }
            return value;
        }

        private Object value() {
            space();
            if (at >= text.length()) {
                throw new IllegalArgumentException("the document ends early");
            }
            char c = text.charAt(at);
            if (c == '{') {
                Map<String, Object> object = new LinkedHashMap<>();
                at++;
                space();
                if (peek('}')) {
                    return object;
                }
                do {
                    space();
                    String key = string();
                    space();
                    expect(':');
                    if (object.put(key, value()) != null) {
                        throw new IllegalArgumentException("a key twice: " + key);
                    }
                    space();
                } while (peek(','));
                expect('}');
                return object;
            }
            if (c == '[') {
                List<Object> array = new ArrayList<>();
                at++;
                space();
                if (peek(']')) {
                    return array;
                }
                do {
                    array.add(value());
                    space();
                } while (peek(','));
                expect(']');
                return array;
            }
            if (c == '"') {
                return string();
            }
            for (String literal : List.of("true", "false", "null")) {
                if (text.startsWith(literal, at)) {
                    at += literal.length();
                    return literal.equals("null") ? null : Boolean.valueOf(literal);
                }
            }
            Matcher number = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matcher(text).region(at, text.length());
            if (number.lookingAt()) {
                at = number.end();
                return number.group();
            }
            throw new IllegalArgumentException("an unexpected character at " + at);
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("an unknown escape at " + at);
                }
            }
            throw new IllegalArgumentException("a string never closed");
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
                at++;
            }
        }

        private boolean peek(char c) {
            if (at < text.length() && text.charAt(at) == c) {
                at++;
                return true;
            }
            return false;
        }

        private void expect(char c) {
            if (!peek(c)) {
                throw new IllegalArgumentException("expected '" + c + "' at " + at);
            }
        }
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
    static String held(Path tree, String environment, String stem, String version) throws Refused, IOException {
        Map<String, Map<String, String>> records = recordsIn(tree, environment);
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

    /** Every record of an environment in a tree on disk, by file name, its held listings under {@code held:<stem>}. */
    static Map<String, Map<String, String>> recordsIn(Path tree, String environment) throws IOException {
        Map<String, Map<String, String>> records = new TreeMap<>();
        Path directory = tree.resolve("deploy/evidence/" + environment);
        if (!Files.isDirectory(directory)) {
            return records;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                records.put(file.getFileName().toString(), record(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return records;
    }

    /** Every record of an environment as committed at {@code ref}: what a change cannot have written itself. */
    static Map<String, Map<String, String>> recordsAt(Path root, String ref, String environment) throws CannotJudge {
        Map<String, Map<String, String>> records = new TreeMap<>();
        String listed = git(root, true, "ls-tree", "--name-only", ref + ":deploy/evidence/" + environment);
        if (listed == null) {
            return records;
        }
        for (String name : listed.split("\r?\n")) {
            if (name.endsWith(".yaml")) {
                records.put(name, record(git(root, false, "show", ref + ":deploy/evidence/" + environment + "/" + name)));
            }
        }
        return records;
    }

    static Map<String, String> record(String text) {
        Map<String, String> record = fields(text);
        Matcher held = Pattern.compile("(?m)^  - \"(FINAPP_[A-Z0-9_]+) listed ([0-9,]+) active ([0-9]+|default)\"$").matcher(text);
        while (held.find()) {
            record.put("held:" + held.group(1), held.group(2));
        }
        return record;
    }

    // -------------------------------------------------------------------------------------- git

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
}
