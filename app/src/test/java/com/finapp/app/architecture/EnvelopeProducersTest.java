package com.finapp.app.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.architecture.EnvelopeProducers.Site;
import com.finapp.app.architecture.topicprobe.EnvelopeFixtures;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scanner the topic catalogue's test rests on reads what it claims to (`P15-TSK-026`): a
 * constant producer by value, and anything else as unreadable - never as a wrong value.
 */
@DisplayName("the envelope producer scanner (P15-TSK-026)")
class EnvelopeProducersTest {

    private static final Map<String, Site> SITES =
            EnvelopeProducers.in("probe", TopicCatalogueTest.bytesOf(EnvelopeFixtures.class)).stream()
                    .collect(Collectors.toMap(Site::method, site -> site));

    @Test
    @DisplayName("a constant producer is read by value, with its constant event type")
    void aConstantIsRead() {
        assertThat(SITES.get("constant").producer()).isEqualTo("probe");
        assertThat(SITES.get("constant").eventType()).isEqualTo("probe.Happened");
    }

    @Test
    @DisplayName("a computed producer is unreadable, not guessed")
    void aComputedProducerIsUnreadable() {
        assertThat(SITES.get("computed").producer()).isNull();
    }

    @Test
    @DisplayName("a producer arriving by two branches is unreadable, not either branch")
    void aBranchedProducerIsUnreadable() {
        assertThat(SITES.get("branched").producer()).isNull();
    }

    @Test
    @DisplayName("long and double arguments around the producer do not shift it")
    void wideArgumentsKeepTheirSlots() {
        assertThat(SITES.get("wideArguments").producer()).isEqualTo("probe");
        assertThat(SITES.get("wideArguments").eventType())
                .as("concatenated at run time")
                .isNull();
    }

    @Test
    @DisplayName("a construction inside a lambda is found, in its synthetic method")
    void aLambdaIsFound() {
        List<Site> lambdas =
                SITES.values().stream().filter(site -> site.method().startsWith("lambda$")).toList();

        assertThat(lambdas).singleElement().satisfies(site -> {
            assertThat(site.producer()).isEqualTo("lambda-probe");
            assertThat(site.eventType()).isEqualTo("probe.Lambda");
        });
    }

    @Test
    @DisplayName("every construction in the fixture is found, and no other")
    void everySiteIsFound() {
        assertThat(SITES).hasSize(5);
    }
}
