package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.merchant.MerchantPayoutBeans;
import com.finapp.platform.persistence.SweepBackoff;
import com.finapp.platform.testing.RepositoryPaths;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * The backoff is bounded so a recovered row is retried within its flow's page (`P16-TSK-017`, the entry's workload
 * assumption): the cap plus the slowest sweep's poll stays inside the unknown-age bound after which {@code PaymentUnknownAged},
 * {@code WithdrawalUnknownAged} and {@code MerchantPayoutUnknownAged} page. Both sides are read where they live - the polls from
 * the beans' own placeholders, the bounds from the committed rule file - never copied here.
 */
@DisplayName("the sweeps' backoff cap plus a poll stays inside each flow's unknown-age page, both read from where they live (P16-TSK-017)")
class SweepBackoffBoundTest {

    /** The six resolution sweeps' poll placeholders, as the beans declare them. */
    private static final Pattern POLL = Pattern.compile(
            "\\$\\{(finapp\\.payments\\.(?:withdrawal\\.|payin\\.|return\\.|dispute-response\\.)?sweeper\\.poll(?:-interval)?"
                    + "|finapp\\.merchant\\.payout\\.sweeper\\.poll-interval):(PT[0-9A-Z.]+)}");

    private static final Pattern PAGE = Pattern.compile(
            "- alert: (PaymentUnknownAged|WithdrawalUnknownAged|MerchantPayoutUnknownAged)\\n(?:[^\\n]*\\n)*?\\s+expr: [^\\n]*> (\\d+)\\n");

    @Test
    @DisplayName("the cap plus the slowest poll is inside every unknown-age page, and both were found")
    void theCapIsInsideTheUnknownAgePage() throws Exception {
        Map<String, Duration> polls = new TreeMap<>();
        for (Class<?> beans : new Class<?>[] {PaymentBeans.class, MerchantPayoutBeans.class}) {
            for (Method method : beans.getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    Value value = parameter.getAnnotation(Value.class);
                    if (value != null) {
                        Matcher poll = POLL.matcher(value.value());
                        if (poll.matches()) {
                            polls.put(poll.group(1), Duration.parse(poll.group(2)));
                        }
                    }
                }
            }
        }
        assertThat(polls).as("the six resolution sweeps' polls, read from the beans").hasSize(6);

        String rules = RepositoryPaths.read("infra/prometheus/rules/flows.yml").replace("\r\n", "\n");
        Map<String, Long> pages = new TreeMap<>();
        Matcher page = PAGE.matcher(rules);
        while (page.find()) {
            pages.put(page.group(1), Long.parseLong(page.group(2)));
        }
        assertThat(pages).as("the three unknown-age pages, read from the rule file").hasSize(3);

        Duration slowest = polls.values().stream().max(Duration::compareTo).orElseThrow();
        Duration retried = SweepBackoff.CAP.plus(slowest);
        assertThat(pages).allSatisfy((alert, seconds) -> assertThat(retried.toSeconds())
                .as("%s pages after %s s; a recovered row is retried within %s", alert, seconds, retried)
                .isLessThan(seconds));
        assertThat(SweepBackoff.BASE).as("a floor: the first wait is never shorter than a poll").isGreaterThanOrEqualTo(slowest);
        assertThat(SweepBackoff.CAP).isGreaterThan(SweepBackoff.BASE);
        assertThat(SweepBackoff.REMATCH_CAP).as("a residual is a break aged in days; the cap stays well inside BreakAgeHigh's one")
                .isLessThan(Duration.ofDays(1)).isGreaterThan(SweepBackoff.CAP);
    }
}
