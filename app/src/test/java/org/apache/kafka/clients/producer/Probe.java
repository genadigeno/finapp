package org.apache.kafka.clients.producer;

/**
 * A stand-in for a broker client, used only by {@code NoDirectBrokerPublicationRulesTest}.
 *
 * <p><strong>Why it declares a Kafka package it is not part of.</strong> No broker client is on
 * the classpath yet, and the rule it exercises matches broker types by package name for exactly
 * that reason — a rule that only worked once someone added the dependency would be missing at
 * the moment it is first needed. Testing that rule therefore needs a type that <em>looks</em>
 * like a broker client to the rule, and the only way to look like one is to be in the package.
 *
 * <p>It is a test class, so {@code DoNotIncludeTests} keeps it out of every production sweep;
 * only the teeth tests import it, explicitly. The alternative — asserting a
 * differently-configured copy of the condition — would test something other than the rule that
 * actually runs, which is the failure these architecture tests exist to avoid.
 */
@SuppressWarnings("unused")
public final class Probe {

    public void send(String topic, byte[] value) {
        // Deliberately empty. Being called is the violation, not what it does.
    }
}
