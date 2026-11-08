package com.finapp.app.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.inbox.InboxEventHandler;
import com.finapp.platform.inbox.ReceivedEvent;
import com.finapp.platform.testing.RepositoryPaths;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The replay's two inputs, as the application wires them (`P15-TSK-028`). */
@DisplayName("the replay reads the retention and the catalogue it is told to (P15-TSK-028)")
class DeadLetterWiringTest {

    private static final String BEANS = "app/src/main/java/com/finapp/app/eventing/EventingBeans.java";

    @Test
    @DisplayName("the dedupe window is the one retention in the application, read from the bean that dated the rows, not a copy")
    void theWindowIsTheInboxesOwn() throws IOException {
        String beans = Files.readString(RepositoryPaths.locate(BEANS), StandardCharsets.UTF_8);

        assertThat(beans.split("finapp\\.inbox\\.retention", -1)).as("one read of the property, the inbox consumer's").hasSize(2);
        assertThat(beans).contains("inboxConsumer.retention()");
    }

    @Test
    @DisplayName("past the window needs every consumer of the type marked; a type nobody consumes is not marked")
    void everyConsumerMustBeMarked() {
        assertThat(EventingBeans.everyConsumerIsMarked(List.of(handler("a.one", "t.Marked", true), handler("b.two", "t.Marked", true))).test("t.Marked")).isTrue();
        assertThat(EventingBeans.everyConsumerIsMarked(List.of(handler("a.one", "t.Mixed", true), handler("b.two", "t.Mixed", false))).test("t.Mixed")).isFalse();
        assertThat(EventingBeans.everyConsumerIsMarked(List.of(handler("a.one", "t.Other", true))).test("t.Nobody")).as("nothing was claimed").isFalse();
    }

    @Test
    @DisplayName("no handler claims natural idempotency today; a claim is a reviewed change, because the inbox cannot protect it past the window")
    void noHandlerClaimsIt() throws IOException {
        Path root = RepositoryPaths.locate("settings.gradle.kts").getParent();
        List<Path> claims = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> file.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(file -> !file.getFileName().toString().equals("InboxEventHandler.java"))
                    .forEach(file -> {
                        try {
                            if (Files.readString(file, StandardCharsets.UTF_8).contains("boolean naturallyIdempotent()")) {
                                claims.add(file);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        }
        assertThat(claims).as("a handler marking itself naturally idempotent lets a replay past the dedupe window reach it").isEmpty();
    }

    private static InboxEventHandler handler(String consumer, String type, boolean marked) {
        return new InboxEventHandler() {
            @Override
            public String consumerName() {
                return consumer;
            }

            @Override
            public String topic() {
                return "finapp.probe";
            }

            @Override
            public String eventType() {
                return type;
            }

            @Override
            public void handle(Connection unitOfWork, ReceivedEvent event) {}

            @Override
            public boolean naturallyIdempotent() {
                return marked;
            }
        };
    }
}
