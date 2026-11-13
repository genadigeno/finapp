package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.LockoutPolicy;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

/**
 * The per-source limits carry what they were derived for (`P16-TSK-015`, ADR-0145; {@code CAPACITY_MODEL.md} section 4a;
 * {@code THREAT_MODEL.md} section 6 items 33 (b) and 67).
 *
 * <p>Every input is read, never copied: the limits from the file production ships and from every overlay under {@code deploy/} that sets
 * one, the edge's tiers from its own manifests, the lockout's threshold from the code, and the workload (W1-W4) and the shared-address
 * reading (R6) from the capacity model. So a changed assumption, a lowered limit or a smaller edge turns this red until the derivation is
 * made again. What it does not hold: that one device behind a shared address cannot spend the budget for everyone there. No number can,
 * and that is item 67 (a), the owner's.
 */
@DisplayName("the per-source limits carry what they were derived for: one person at every door, a shared address at sign-in's busiest"
        + " burst, and the edge's door, session and callback tiers their sources at W2's spike (P16-TSK-015)")
class PerSourceLimitsAreCalibratedTest {

    private static final StandardEnvironment SHIPPED = shipped();
    private static final String MODEL = RepositoryPaths.read("docs/architecture/CAPACITY_MODEL.md");
    private static final Pattern OVERRIDE = Pattern.compile("FINAPP_ABUSE_([A-Z_]+?)(_SITE)?_LIMIT[=:] *\"?([0-9]+)");
    // The doors' names in configuration, by the variable that sets them.
    private static final Map<String, String> DOORS = Map.of(
            "REGISTRATION", "registration",
            "SIGN_IN", "sign-in",
            "RECOVERY", "recovery",
            "RECOVERY_COMPLETION", "recovery-completion",
            "CHANNEL_VERIFICATION", "channel-verification");
    private static final Map<String, Integer> WORDS = Map.of("two", 2, "three", 3, "four", 4, "five", 5, "ten", 10);

    private static StandardEnvironment shipped() {
        try {
            StandardEnvironment environment = new StandardEnvironment();
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load("shipped", new FileSystemResource(RepositoryPaths.locate("app/src/main/resources/application.yaml")))) {
                environment.getPropertySources().addLast(source);
            }
            return environment;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /**
     * One person's need at a door in its window. Sign-in: the identity lockout's threshold of failures and the success after them, read
     * from the code; past it the person is locked whatever the source limit says. Every other door has no identity bound: an attempt and
     * two retries under its key, each of which counts ({@code OPERATIONS_RUNBOOK.md} section 11.30).
     */
    private static int onePerson(String door) {
        return "sign-in".equals(door) ? LockoutPolicy.current().threshold() + 1 : 3;
    }

    @Test
    @DisplayName("no door refuses one person doing nothing wrong: every limit, shipped or set by an overlay, at least that person's need")
    void noDoorRefusesOnePerson() {
        for (String door : DOORS.values()) {
            int limit = Integer.parseInt(SHIPPED.getRequiredProperty("finapp.abuse." + door + ".limit"));
            assertThat(limit).as("%s's shipped limit against one person's need of %d", door, onePerson(door)).isGreaterThanOrEqualTo(onePerson(door));
            String site = SHIPPED.getProperty("finapp.abuse." + door + ".site-limit");
            if (site != null) {
                assertThat(Integer.parseInt(site)).as("%s's shipped limit per /48", door).isGreaterThanOrEqualTo(onePerson(door));
            }
        }
        List<String> read = overrides();
        assertThat(read).as("the overlays' own limits were read (the kind overlay sets four): a scan that found none proves nothing").isNotEmpty();
        for (String override : read) {
            String[] part = override.split("\\|");
            assertThat(DOORS).as("%s names a door this derivation knows", part[0]).containsKey(part[1]);
            assertThat(Integer.parseInt(part[2])).as("%s against one person's need at %s", part[0], DOORS.get(part[1]))
                    .isGreaterThanOrEqualTo(onePerson(DOORS.get(part[1])));
        }
    }

    @Test
    @DisplayName("sign-in carries R6's shared address in the busiest hour with W2's burst inside its window, every sign-in retried once:"
            + " W2, W3 and R6 read from the capacity model")
    void signInCarriesASharedAddressAtTheBusiestBurst() {
        Duration window = Duration.parse(SHIPPED.getRequiredProperty("finapp.abuse.sign-in.window"));
        assertThat(window).as("the derivation reads a window inside the busiest hour").isLessThanOrEqualTo(Duration.ofHours(1));
        double need = sharedAddress() * signInsADay() * busiestShare() / 3_600
                * (window.toSeconds() + (burstFactor() - 1) * burstSeconds()) * 2;
        assertThat(need).as("the derivation's own need is not empty").isGreaterThan(1);
        int shipped = Integer.parseInt(SHIPPED.getRequiredProperty("finapp.abuse.sign-in.limit"));
        assertThat((double) shipped).as("sign-in's shipped limit per %s against R6's %.0f active customers' need of %.1f",
                window, sharedAddress(), need).isGreaterThanOrEqualTo(need);
        for (String override : overrides()) {
            String[] part = override.split("\\|");
            if ("SIGN_IN".equals(part[1])) {
                assertThat(Double.parseDouble(part[2])).as("%s against R6's need of %.1f", part[0], need).isGreaterThanOrEqualTo(need);
            }
        }
    }

    @Test
    @DisplayName("the edge's door and session tiers carry an R6 address, and its callback tier every provider callback, at W2's spike:"
            + " each tier times the public controllers, its in-flight hold against the callbacks' measured p99")
    void theEdgeTiersCarryTheirSources() {
        String edge = RepositoryPaths.read("deploy/edge/base/public-edge.yaml");
        Matcher controllers = Pattern.compile("name: finapp-edge-public\\nspec:\\n  replicas: ([0-9]+)")
                .matcher(RepositoryPaths.read("deploy/edge/base/controllers.yaml"));
        assertThat(controllers.find()).as("the public edge's controller count").isTrue();
        int count = Integer.parseInt(controllers.group(1));
        double spike = spikeFactor();
        double perSecond = busiestShare() / 3_600;

        double door = sharedAddress() * signInsADay() * perSecond * spike;
        assertThat((double) middleware(edge, "source-door", "rateLimit", "average") * count)
                .as("the door tier across %d controllers against an R6 address's sign-ins at the spike, %.2f a second", count, door)
                .isGreaterThanOrEqualTo(door);

        double session = sharedAddress() * w3(1, 2, 3, 4) * perSecond * spike;
        assertThat((double) middleware(edge, "source-session", "rateLimit", "average") * count)
                .as("the session tier against an R6 address's transfers, payments, top-ups and reads at the spike, %.2f a second", session)
                .isGreaterThanOrEqualTo(session);

        double callbacks = callbacksPerPayment() * activeADay() * w3(2) * perSecond * spike;
        assertThat((double) middleware(edge, "source-callback", "rateLimit", "average") * count)
                .as("the callback tier against every provider callback at the spike, %.1f a second", callbacks).isGreaterThanOrEqualTo(callbacks);
        Matcher p99 = Pattern.compile("\\| Provider callbacks \\|[^\\n]*?\\| 20 \\(the sender's\\) \\| ([0-9]+) \\| ([0-9]+) \\|").matcher(MODEL);
        assertThat(p99.find()).as("the callbacks' measured p99, CAPACITY_MODEL.md section 3.4").isTrue();
        double inFlight = callbacks * Integer.parseInt(p99.group(2)) / 1_000.0;
        assertThat((double) middleware(edge, "hold-callback", "inFlightReq", "amount") * count)
                .as("the callback tier's hold against the spike's callbacks in flight at their p99, %.1f", inFlight).isGreaterThanOrEqualTo(inFlight);
    }

    // ---------------------------------------------------------------------------------------------- the readings

    /** Every abuse limit an overlay or another file under deploy/ sets: "file|DOOR|value". */
    private static List<String> overrides() {
        List<String> found = new ArrayList<>();
        // The deploy tree, found by a file it always holds: RepositoryPaths finds files, not directories.
        Path deploy = RepositoryPaths.locate("deploy/base/kustomization.yaml").getParent().getParent();
        try (Stream<Path> files = Files.walk(deploy)) {
            for (Path file : files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml")).toList()) {
                Matcher limit = OVERRIDE.matcher(Files.readString(file));
                while (limit.find()) {
                    found.add(file.getFileName() + " " + limit.group(0) + "|" + limit.group(1) + "|" + limit.group(3));
                }
            }
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return found;
    }

    private static int middleware(String edge, String name, String kind, String field) {
        Matcher value = Pattern.compile("\\n    " + Pattern.quote(name) + ":\\n      " + kind + ":\\n(?:        [^\\n]*\\n)*?        "
                + field + ": ([0-9]+)\\n").matcher(edge);
        assertThat(value.find()).as("the edge's %s %s %s", name, kind, field).isTrue();
        return Integer.parseInt(value.group(1));
    }

    private static double sharedAddress() {
        return number("\\*\\*R6 \\(W1, W3\\)\\*\\*[^:]*: at most \\*\\*([0-9,]+)\\*\\*", "R6");
    }

    private static double activeADay() {
        return number("\\*\\*W1\\*\\* [0-9,]+ registered customers; [0-9]+% active on a given day \\(([0-9,]+)\\)", "W1's active customers");
    }

    private static double busiestShare() {
        return number("\\*\\*W2\\*\\* The busiest hour carries ([0-9]+)% of a day's volume", "W2's busiest hour") / 100;
    }

    private static double burstFactor() {
        return word("A \\*\\*burst\\*\\* is ([a-z]+) times the busiest hour's rate", "W2's burst");
    }

    private static double burstSeconds() {
        return number("A \\*\\*burst\\*\\* is [a-z]+ times the busiest hour's rate for ([0-9]+) seconds", "W2's burst length");
    }

    private static double spikeFactor() {
        return word("A \\*\\*spike\\*\\* is ([a-z]+) times it", "W2's spike");
    }

    private static double callbacksPerPayment() {
        return word("\\*\\*W4\\*\\* [0-9,]+ merchants; ([a-z]+) provider callbacks per payment", "W4's callbacks");
    }

    private static double signInsADay() {
        return w3(5);
    }

    /**
     * W3's figures per active customer a day, summed over the groups named (1 transfers, 2 card or checkout payments, 3 top-ups or
     * withdrawals, 4 reads, 5 sign-ins).
     */
    private static double w3(int... groups) {
        Matcher w3 = Pattern.compile("\\*\\*W3\\*\\* Per active customer per day: ([0-9.]+) internal transfers, ([0-9.]+) card or checkout"
                + " payment, ([0-9.]+) wallet top-ups or withdrawals,\\s+([0-9.]+) balance or account reads, ([0-9.]+) sign-ins").matcher(MODEL);
        assertThat(w3.find()).as("W3, read from the capacity model").isTrue();
        double sum = 0;
        for (int group : groups) {
            sum += Double.parseDouble(w3.group(group));
        }
        return sum;
    }

    private static double number(String regex, String what) {
        Matcher found = Pattern.compile(regex).matcher(MODEL);
        assertThat(found.find()).as("%s, read from the capacity model", what).isTrue();
        return Double.parseDouble(found.group(1).replace(",", ""));
    }

    private static double word(String regex, String what) {
        Matcher found = Pattern.compile(regex).matcher(MODEL);
        assertThat(found.find()).as("%s, read from the capacity model", what).isTrue();
        assertThat(WORDS).as("%s's word '%s'", what, found.group(1)).containsKey(found.group(1));
        return WORDS.get(found.group(1));
    }
}
