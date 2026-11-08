package com.finapp.platform.testing;

import com.finapp.platform.deadletter.DeadLetterStore;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DeadLetterStore} in memory (`P15-TSK-028`), judging an event's age against the retention it is given, as the SQL does.
 * The statements themselves, and the row locks, are {@code DeadLetterDatabaseTest}'s.
 */
public final class InMemoryDeadLetterStore implements DeadLetterStore {

    /** One outbox row. */
    public static final class Event {
        public final UUID id;
        public final String type;
        public boolean abandoned;
        public boolean discarded;
        public boolean published;
        public boolean replayed;
        public Duration anchorAge;
        public Instant firstPublished;
        public int requeues;
        public int withdrawals;
        public UUID aggregate;

        Event(UUID id, String type) {
            this.id = id;
            this.type = type;
            this.aggregate = id;
        }
    }

    /** One parked record. */
    public static final class Parked {
        public final UUID id;
        public final String consumer;
        public final String dedupeKey;
        public String state;

        Parked(UUID id, String consumer, String dedupeKey, String state) {
            this.id = id;
            this.consumer = consumer;
            this.dedupeKey = dedupeKey;
            this.state = state;
        }
    }

    private final Map<UUID, Event> events = new LinkedHashMap<>();
    private final Map<UUID, Parked> parked = new LinkedHashMap<>();
    private final Map<UUID, Proposal> proposals = new LinkedHashMap<>();
    private final Map<UUID, String[]> decisions = new HashMap<>();
    public final List<List<UUID>> extended = new ArrayList<>();
    public final List<Duration> extendedFor = new ArrayList<>();

    /** An abandoned event, pending. */
    public Event abandoned(UUID id) {
        Event event = new Event(id, "probe.SomethingHappened");
        event.abandoned = true;
        event.anchorAge = Duration.ofHours(1);
        events.put(id, event);
        return event;
    }

    /** A published event, its window anchor this long ago. */
    public Event published(UUID id, String type, Duration anchorAge, Instant firstPublished) {
        Event event = new Event(id, type);
        event.published = true;
        event.anchorAge = anchorAge;
        event.firstPublished = firstPublished;
        events.put(id, event);
        return event;
    }

    public Parked parked(UUID id, String consumer, String dedupeKey) {
        Parked row = new Parked(id, consumer, dedupeKey, "PARKED");
        parked.put(id, row);
        return row;
    }

    public Event event(UUID id) {
        return events.get(id);
    }

    public Parked parkedRow(UUID id) {
        return parked.get(id);
    }

    /** The decision recorded on a proposal: status and decided-by. */
    public String[] decision(UUID discardId) {
        return decisions.get(discardId);
    }

    public int standingCount() {
        return (int) proposals.values().stream().filter(proposal -> "PROPOSED".equals(proposal.status())).count();
    }

    @Override
    public Map<String, Long> parkedByGroup(Connection connection) {
        return Map.of();
    }

    @Override
    public List<AbandonedEvent> abandoned(Connection unitOfWork, int limit) {
        return events.values().stream()
                .filter(event -> event.abandoned && !event.discarded && !event.published)
                .limit(limit)
                .map(event -> new AbandonedEvent(event.id, UUID.randomUUID(), event.type, 8, Instant.EPOCH, "kafka.TimeoutException"))
                .toList();
    }

    @Override
    public List<ParkedRecord> parked(Connection unitOfWork, int limit) {
        return parked.values().stream()
                .filter(row -> "PARKED".equals(row.state))
                .limit(limit)
                .map(row -> new ParkedRecord(row.id, row.consumer, row.dedupeKey, "finapp.probe", "probe.Type", 5, Instant.EPOCH, "IllegalArgumentException"))
                .toList();
    }

    @Override
    public List<Proposal> standingProposals(Connection unitOfWork, int limit) {
        return proposals.values().stream().filter(proposal -> "PROPOSED".equals(proposal.status())).limit(limit).toList();
    }

    @Override
    public Optional<EventRow> lockEvent(Connection unitOfWork, UUID eventId, Duration retention) {
        Event event = events.get(eventId);
        return event == null ? Optional.empty() : Optional.of(new EventRow(event.id, event.type, event.abandoned, event.discarded, event.published, event.replayed, event.anchorAge.compareTo(retention) < 0));
    }

    @Override
    public void returnToRelay(Connection unitOfWork, UUID eventId) {
        Event event = events.get(eventId);
        if (event.abandoned && !event.discarded && !event.published) {
            event.abandoned = false;
        }
    }

    @Override
    public void discardEvent(Connection unitOfWork, UUID eventId) {
        events.get(eventId).discarded = true;
    }

    @Override
    public Optional<ParkedRow> lockParked(Connection unitOfWork, UUID parkId) {
        Parked row = parked.get(parkId);
        return row == null ? Optional.empty() : Optional.of(new ParkedRow(row.id, row.consumer, row.dedupeKey, "finapp.probe", "probe.Type", row.state));
    }

    @Override
    public void releaseParked(Connection unitOfWork, UUID parkId) {
        parked.get(parkId).state = "FAILING";
    }

    @Override
    public void discardParked(Connection unitOfWork, UUID parkId) {
        parked.get(parkId).state = "DISCARDED";
    }

    @Override
    public boolean hasStandingDiscard(Connection unitOfWork, SubjectKind kind, UUID subjectId) {
        return proposals.values().stream()
                .anyMatch(proposal -> proposal.kind() == kind && proposal.subjectId().equals(subjectId) && "PROPOSED".equals(proposal.status()));
    }

    @Override
    public void insertProposal(Connection unitOfWork, UUID discardId, SubjectKind kind, UUID subjectId, String proposedBy, String reason) {
        proposals.put(discardId, new Proposal(discardId, kind, subjectId, "PROPOSED", proposedBy, Instant.EPOCH, reason));
    }

    @Override
    public Optional<Proposal> lockProposal(Connection unitOfWork, UUID discardId) {
        return Optional.ofNullable(proposals.get(discardId));
    }

    @Override
    public void decide(Connection unitOfWork, UUID discardId, String status, String decidedBy, String reason) {
        Proposal proposal = proposals.get(discardId);
        proposals.put(discardId, new Proposal(discardId, proposal.kind(), proposal.subjectId(), status, proposal.proposedBy(), proposal.proposedAt(), proposal.reason()));
        decisions.put(discardId, new String[] {status, decidedBy});
    }

    @Override
    public List<ReplayCandidate> lockForReplay(Connection unitOfWork, String eventType, Instant from, Instant to, Duration retention, int limit) {
        return events.values().stream()
                .filter(event -> event.published && event.type.equals(eventType) && event.firstPublished != null)
                .filter(event -> !event.firstPublished.isBefore(from) && event.firstPublished.isBefore(to))
                .sorted(Comparator.comparing(event -> event.id))
                .limit(limit)
                .map(event -> new ReplayCandidate(event.id, event.anchorAge.compareTo(retention) < 0))
                .toList();
    }

    @Override
    public void extendDedupe(Connection unitOfWork, List<UUID> eventIds, Duration retention) {
        if (eventIds.isEmpty()) {
            return; // As the statement: nothing to extend, nothing run.
        }
        extended.add(List.copyOf(eventIds));
        extendedFor.add(retention);
    }

    @Override
    public void requeue(Connection unitOfWork, List<UUID> eventIds) {
        eventIds.forEach(id -> {
            Event event = events.get(id);
            event.published = false;
            event.replayed = true;
            event.requeues++;
        });
    }

    @Override
    public List<PendingReplay> lockPendingReplays(Connection unitOfWork, UUID eventId, Duration retention) {
        UUID aggregate = events.get(eventId).aggregate;
        return events.values().stream()
                .filter(event -> event.aggregate.equals(aggregate) && !event.id.equals(eventId))
                .filter(event -> event.replayed && !event.published && !event.discarded)
                .map(event -> new PendingReplay(event.id, event.type, event.anchorAge.compareTo(retention) < 0))
                .toList();
    }

    @Override
    public void withdrawReplays(Connection unitOfWork, List<UUID> eventIds) {
        eventIds.forEach(id -> {
            Event event = events.get(id);
            event.published = true;
            event.withdrawals++;
        });
    }
}
