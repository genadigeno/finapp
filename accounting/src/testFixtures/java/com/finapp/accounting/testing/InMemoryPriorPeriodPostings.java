package com.finapp.accounting.testing;

import com.finapp.accounting.PriorPeriodPostings;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The ledger's half of a prior-period adjustment, in memory, as {@code LedgerPriorPeriodPostings} promises it: a chart of accounts, proposals with one date, a one-way machine, and a posting cutoff that refuses a date it covers - {@code V028} and
 * {@code PostingCutoffs.requireOpen} in one line, so a domain that dated an entry into a closed month is refused here as the ledger would refuse it ({@link LedgerRefused}).
 */
public final class InMemoryPriorPeriodPostings implements PriorPeriodPostings {

    /** The ledger's {@code ledger.PeriodClosed}, without its type. */
    public static final class LedgerRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public LedgerRefused(String message) {
            super(message);
        }
    }

    /** One proposal as the ledger holds it. */
    public record Held(UUID id, LocalDate date, String reference, String reason, List<Line> lines, String status, Optional<UUID> entry) {}

    private final Map<UUID, Classified> chart = new HashMap<>();
    private final Map<UUID, Held> proposals = new LinkedHashMap<>();
    private final List<String> calls = new ArrayList<>();
    private Optional<LocalDate> closedThrough = Optional.empty();
    private boolean answerReplayed;

    /** Opens an account of the purpose. */
    public UUID account(String purpose, String ownerKind) {
        UUID id = UUID.randomUUID();
        chart.put(id, new Classified(purpose, ownerKind));
        return id;
    }

    /** A release's stand-in: the account's purpose as it now classifies. */
    public void reclassify(UUID account, String purpose, String ownerKind) {
        chart.put(account, new Classified(purpose, ownerKind));
    }

    /** Where the ledger's cutoff stands. */
    public void closedThrough(LocalDate date) {
        closedThrough = Optional.of(date);
    }

    /** The next {@link #approve} answers as a ledger already approved by this person would. */
    public void answerReplayed() {
        answerReplayed = true;
    }

    /** Each call in order, by name. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    public List<Held> proposals() {
        return List.copyOf(proposals.values());
    }

    public Held proposal(UUID id) {
        return proposals.get(id);
    }

    @Override
    public Map<UUID, Classified> classify(Connection unitOfWork, Collection<UUID> ledgerAccounts) {
        calls.add("classify");
        Map<UUID, Classified> found = new HashMap<>();
        ledgerAccounts.stream().filter(chart::containsKey).forEach(account -> found.put(account, chart.get(account)));
        return found;
    }

    @Override
    public UUID propose(Connection unitOfWork, LocalDate date, String reference, String reason, List<Line> lines) {
        calls.add("propose");
        UUID id = UUID.randomUUID();
        proposals.put(id, new Held(id, date, reference, reason, List.copyOf(lines), "PROPOSED", Optional.empty()));
        return id;
    }

    @Override
    public Posted approve(Connection unitOfWork, UUID proposal) {
        calls.add("approve");
        Held held = proposals.get(proposal);
        if (answerReplayed) {
            return new Posted(UUID.randomUUID(), true);
        }
        if (!held.status().equals("PROPOSED")) {
            throw new IllegalStateException("the ledger's proposal is " + held.status());
        }
        if (closedThrough.isPresent() && !held.date().isAfter(closedThrough.get())) {
            throw new LedgerRefused("dated " + held.date() + ", on or before the posting cutoff " + closedThrough.get());
        }
        UUID entry = UUID.randomUUID();
        proposals.put(proposal, new Held(held.id(), held.date(), held.reference(), held.reason(), held.lines(), "APPROVED", Optional.of(entry)));
        return new Posted(entry, false);
    }

    @Override
    public void reject(Connection unitOfWork, UUID proposal) {
        calls.add("reject");
        Held held = proposals.get(proposal);
        proposals.put(proposal, new Held(held.id(), held.date(), held.reference(), held.reason(), held.lines(), "REJECTED", Optional.empty()));
    }

    @Override
    public List<Line> linesOf(Connection unitOfWork, UUID proposal) {
        calls.add("linesOf");
        return proposals.containsKey(proposal) ? proposals.get(proposal).lines() : List.of();
    }
}
