package com.finapp.platform.inbox;

/**
 * A deliberate violation fixture for {@code NoDirectBrokerPublicationRulesTest}: a class in the
 * inbox <em>parent</em> package touching a broker consumer client. Its job is to prove the
 * consuming exemption is exactly {@code com.finapp.platform.inbox.kafka} and not the inbox
 * package it sits under — the exemption set matches package names exactly, and this fixture is
 * what keeps that a demonstrated property rather than an implementation detail. Never production
 * code; lives in test sources and is only ever imported by the rule's own teeth test.
 */
@SuppressWarnings("unused")
public final class ConsumesBesideTheInbox {
    void receive(org.apache.kafka.clients.consumer.Probe consumer) {
        consumer.poll(100L);
    }
}
