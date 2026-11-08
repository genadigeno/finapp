package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.JdbcInboxRecordStore;
import com.finapp.platform.inbox.kafka.KafkaEventReceiver;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

/**
 * The consumers' poll budget (`P15-TSK-026`'s review fix round), computed from the configuration
 * each number comes from: the pool's wait from the committed {@code application.yaml}, the inbox
 * claim's wait from {@link JdbcInboxRecordStore#DEFAULT_CLAIM_WAIT} (the store {@code EventingBeans}
 * builds takes the default), and the poll interval, batch size and commit wait from
 * {@link KafkaEventReceiver}, whose own test pins each to the client configuration it renders. The
 * first version of this arithmetic copied 5 s and 0.5 s into a test, which a changed pool timeout
 * would have left green.
 *
 * <p>A budget, not a bound: the handlers' own statements have no timeout, so nothing here proves a
 * batch settles in time. What it holds is that the bounded waits alone cannot spend the interval,
 * and that a prompt commit leaves a handler seconds, not milliseconds. A batch that overruns is
 * evicted and redelivered into the dedupe.
 */
@DisplayName("the consumers' poll budget, from the real configuration (P15-TSK-026)")
class InboxPollBudgetTest {

    private static final String APPLICATION = "app/src/main/resources/application.yaml";

    @Test
    @DisplayName("ten records' bounded waits fit the poll interval, even beside a commit that waits its limit")
    void theBatchFitsThePollBudget() {
        Duration poolWait =
                Duration.ofMillis(
                        Long.parseLong(applicationProperty("${spring.datasource.hikari.connection-timeout}")));
        Duration boundedWaits = poolWait.plus(JdbcInboxRecordStore.DEFAULT_CLAIM_WAIT);
        int batch = KafkaEventReceiver.MAX_POLL_RECORDS;

        Duration promptCommit = KafkaEventReceiver.MAX_POLL_INTERVAL.dividedBy(batch);
        assertThat(promptCommit.minus(boundedWaits))
                .as("with a prompt commit, each record keeps at least five seconds for its handler's"
                        + " statements after a full pool wait and a full claim wait")
                .isGreaterThanOrEqualTo(Duration.ofSeconds(5));

        Duration slowestCommit =
                KafkaEventReceiver.MAX_POLL_INTERVAL.minus(KafkaEventReceiver.API_TIMEOUT).dividedBy(batch);
        assertThat(slowestCommit.minus(boundedWaits))
                .as("every bounded wait at its limit at once, the commit's included, still leaves time")
                .isPositive();
    }

    /** A placeholder resolved against the committed {@code application.yaml} alone. */
    private static String applicationProperty(String placeholder) {
        try {
            MutablePropertySources sources = new MutablePropertySources();
            new YamlPropertySourceLoader()
                    .load("application", new FileSystemResource(RepositoryPaths.locate(APPLICATION)))
                    .forEach(sources::addLast);
            return new PropertySourcesPropertyResolver(sources).resolveRequiredPlaceholders(placeholder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
