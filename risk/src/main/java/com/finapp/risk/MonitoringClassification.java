package com.finapp.risk;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The classification of one journal entry into its monitored transaction (`P13-TSK-019`; ADR-0111 decision 2;
 * {@code INV-RSK-09}) - a pure function of the entry, the same on every instance, that never refuses an entry: the
 * kind from the entry's type or its posting-key namespace ({@link MonitoredKind}, {@code UNCLASSIFIED} when unknown),
 * the subject from the first line on a customer account (else a merchant's), the direction and the judged amount as
 * seen from that owner's accounts in the subject line's currency (credits less debits, the sign the direction), the
 * counterparty from the first line off the subject's accounts; an entry with no owned side has no subject and carries
 * its debit total in its first line's currency against its first line's owner.
 *
 * <p>Deterministic by construction: line order is the journal's (the entry's {@code seq}), so two instances classify
 * one entry into byte-identical rows - which is what lets ten sweepers' inserts converge on one row and a reprocessing
 * create nothing new.
 */
public final class MonitoringClassification {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String MERCHANT = "MERCHANT";

    private MonitoringClassification() {
    }

    /** The monitored transaction {@code entry} becomes, under the identifier {@code id}. */
    public static MonitoredTransaction classify(MonitoredTransactionId id, MonitoredEntry entry) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entry, "entry");
        String namespace = bounded(MonitoredKind.namespaceOf(entry.postingKey()));
        MonitoredKind kind = switch (entry.entryType()) {
            case REVERSAL -> MonitoredKind.REVERSAL;
            case ADJUSTMENT -> MonitoredKind.ADJUSTMENT;
            case POSTING -> MonitoredKind.ofNamespace(namespace).orElse(MonitoredKind.UNCLASSIFIED);
        };
        List<MonitoredEntry.Line> lines = entry.lines();
        Optional<MonitoredEntry.Line> subject = ownedLine(lines, CUSTOMER).or(() -> ownedLine(lines, MERCHANT));

        Optional<MonitoredSubjectKind> subjectKind = Optional.empty();
        Optional<UUID> subjectRef = Optional.empty();
        Optional<MonitoredDirection> direction = Optional.empty();
        MonitoredCounterpartyKind counterpartyKind;
        Optional<UUID> counterpartyRef = Optional.empty();
        Optional<String> counterpartyPurpose = Optional.empty();
        long amount;
        int scale;
        var currency = lines.getFirst().currency();

        if (subject.isPresent()) {
            MonitoredEntry.Line first = subject.get();
            UUID owner = first.ownerRef().orElseThrow();
            subjectKind = Optional.of(CUSTOMER.equals(first.ownerKind())
                    ? MonitoredSubjectKind.ACCOUNT : MonitoredSubjectKind.MERCHANT);
            subjectRef = Optional.of(owner);
            currency = first.currency();
            scale = first.scale();
            long credits = 0;
            long debits = 0;
            for (MonitoredEntry.Line line : lines) {
                if (ownedBy(line, first.ownerKind(), owner) && line.currency().equals(currency)
                        && line.scale() == scale) {
                    if (line.side() == MonitoredEntry.Side.CREDIT) {
                        credits = Math.addExact(credits, line.amountMinor());
                    } else {
                        debits = Math.addExact(debits, line.amountMinor());
                    }
                }
            }
            long net = credits - debits;
            direction = Optional.of(net > 0 || (net == 0 && first.side() == MonitoredEntry.Side.CREDIT)
                    ? MonitoredDirection.IN : MonitoredDirection.OUT);
            amount = Math.abs(net);
            Optional<MonitoredEntry.Line> other = lines.stream()
                    .filter(line -> !ownedBy(line, first.ownerKind(), owner))
                    .findFirst();
            if (other.isPresent()) {
                counterpartyKind = MonitoredCounterpartyKind.ofOwnerKind(other.get().ownerKind());
                counterpartyRef = other.get().ownerRef();
                counterpartyPurpose = Optional.of(bounded(other.get().purpose()));
            } else {
                counterpartyKind = MonitoredCounterpartyKind.NONE;
            }
        } else {
            MonitoredEntry.Line first = lines.getFirst();
            scale = first.scale();
            long debits = 0;
            for (MonitoredEntry.Line line : lines) {
                if (line.side() == MonitoredEntry.Side.DEBIT && line.currency().equals(currency)
                        && line.scale() == scale) {
                    debits = Math.addExact(debits, line.amountMinor());
                }
            }
            amount = debits;
            counterpartyKind = MonitoredCounterpartyKind.ofOwnerKind(first.ownerKind());
            counterpartyRef = first.ownerRef();
            counterpartyPurpose = Optional.of(bounded(first.purpose()));
        }

        return new MonitoredTransaction(id, entry.journalEntryId(), entry.sequence(), entry.entryType(), namespace, kind,
                subjectKind, subjectRef, direction, counterpartyKind, counterpartyRef, counterpartyPurpose, amount, scale,
                currency, entry.valueDate(), entry.postedAt(), entry.reverses());
    }

    private static Optional<MonitoredEntry.Line> ownedLine(List<MonitoredEntry.Line> lines, String ownerKind) {
        return lines.stream()
                .filter(line -> ownerKind.equals(line.ownerKind()) && line.ownerRef().isPresent())
                .findFirst();
    }

    private static boolean ownedBy(MonitoredEntry.Line line, String ownerKind, UUID owner) {
        return ownerKind.equals(line.ownerKind()) && line.ownerRef().filter(owner::equals).isPresent();
    }

    /** The schema's bound on a namespace or a purpose code - a longer one is cut, never refused: nothing passes unseen. */
    private static String bounded(String code) {
        if (code.isBlank()) {
            return "?";
        }
        return code.length() <= 64 ? code : code.substring(0, 64);
    }
}
