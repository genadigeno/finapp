package com.finapp.app.customers;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.api.RecordedReads;
import com.finapp.consent.ConsentAuditAction;
import com.finapp.consent.ConsentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.PartyAuditAction;
import com.finapp.party.PartyId;
import com.finapp.party.PartyKind;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import java.sql.Connection;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator's customer reads, composed in {@code app} from each owning module's own query (`P17-TSK-026`; ADR-C132 decision
 * 1): party's customer and kind, identity's identities and their statuses, kyc's latest case, accounts' agreement for a lookup,
 * consent's history. No statement reads another module's schema; every statement of one answer runs in one {@code REPEATABLE
 * READ} transaction, so the profile's parts describe one instant, and the answer's record ({@link RecordedReads}) commits with
 * it.
 *
 * <p><strong>Identifier-first</strong> (ADR-C132 decision 3, D-5): nothing served names a person - no display name, no login
 * identifier, no contact detail, no document. A lookup records what it resolved to and how it was asked, never the value.
 */
@RequiredArgsConstructor
public class CustomerReadDesk {

    /** The consent history's collection name, inside its cursors. */
    static final String CONSENTS = "consent.history";
    static final int CONSENTS_DEFAULT_LIMIT = 50;
    static final int CONSENTS_MAXIMUM_LIMIT = 200;

    /** At most this many identities of one party are served: a party holds one in practice, and the bound keeps the read bounded. */
    static final int IDENTITIES_SERVED = 10;

    /** The shape {@code identity.identity} admits (identity V002), matched as given - no trimming, no case folding. */
    private static final Pattern LOGIN_IDENTIFIER = Pattern.compile("[a-z0-9._-]{3,64}");

    /** What a lookup may be asked by. */
    public enum LookupKind {
        CUSTOMER_ID,
        PARTY_ID,
        LOGIN_IDENTIFIER,
        ACCOUNT_ID
    }

    @NonNull private final PartyStore<Connection> partyStore;
    @NonNull private final IdentityStore<Connection> identityStore;
    @NonNull private final KycCaseStore<Connection> kycCaseStore;
    @NonNull private final CustomerAccountStore<Connection> customerAccountStore;
    @NonNull private final ConsentStore<Connection> consentStore;
    @NonNull private final RecordedReads recordedReads;
    @NonNull private final TransactionTemplate operatorReadTransactions;
    @NonNull private final DataSource dataSource;

    /**
     * The customer {@code value} names as a {@code kind}, exactly - recorded whether it matched or not, because a probe that found
     * nothing is still a probe. A value no such identifier could be matches nothing and is not looked for. Not found is
     * {@code 404}, after the record commits.
     */
    public CustomerLookupAnswer lookup(String kind, String value) {
        LookupKind asked = named(kind);
        Optional<CustomerId> found = inOneSnapshot(unitOfWork -> {
            Optional<CustomerId> match = resolve(unitOfWork, asked, value);
            recordedReads.record(unitOfWork, PartyAuditAction.CUSTOMER_LOOKED_UP, "party.Customer",
                    match.map(id -> id.value().toString()).orElse("no-match"),
                    "kind=" + asked.name() + ", matched=" + match.isPresent());
            return match;
        });
        return found.map(id -> new CustomerLookupAnswer(id.value().toString())).orElseThrow(CustomerReadDesk::notFound);
    }

    /** One customer's profile, recorded with the customer as the target; an unknown or malformed id is {@code 404}, recording nothing. */
    public CustomerProfile profile(String rawCustomerId) {
        Optional<UUID> id = uuid(rawCustomerId);
        if (id.isEmpty()) {
            throw notFound();
        }
        CustomerId customerId = CustomerId.of(id.get());
        return inOneSnapshot(unitOfWork -> partyStore.findCustomer(unitOfWork, customerId).map(customer -> {
            PartyKind kind = partyStore.kindOf(unitOfWork, customer.partyId()).orElseThrow(
                    () -> new IllegalStateException("a customer's party exists: party.customer references it"));
            List<CustomerIdentity> identities = identityStore
                    .findIdsByParty(unitOfWork, customer.partyId().value(), IDENTITIES_SERVED).stream()
                    .flatMap(identity -> identityStore.findById(unitOfWork, identity).stream())
                    .map(identity -> new CustomerIdentity(identity.id().value().toString(), identity.status().name()))
                    .toList();
            Optional<KycCase> latest = kycCaseStore.findLatestFor(unitOfWork, customerId.value());
            recordedReads.record(unitOfWork, PartyAuditAction.CUSTOMER_READ, "party.Customer", customerId.value().toString(),
                    "party=" + customer.partyId().value() + ", identities=" + identities.size() + ", kycCase="
                            + latest.map(kycCase -> kycCase.id().value().toString()).orElse("none"));
            return CustomerProfile.of(customer, kind, identities, latest);
        })).orElseThrow(CustomerReadDesk::notFound);
    }

    /**
     * A §7 page of a party's consent history, newest first by the history's own {@code seq} - the order no instance's clock
     * decides. A party that does not exist is {@code 404}, recording nothing; one with no consent answers an empty page, recorded.
     */
    public ConsentHistoryPage consents(String rawPartyId, String cursor, String limit) {
        int size = OperatorCollection.limit(limit, CONSENTS_DEFAULT_LIMIT, CONSENTS_MAXIMUM_LIMIT);
        Optional<Long> before = OperatorCollection.key(CONSENTS, cursor, 1).map(key -> {
            try {
                return Long.parseLong(key.get(0));
            } catch (NumberFormatException unreadable) {
                throw OperatorCollection.invalidCursor();
            }
        });
        Optional<UUID> id = uuid(rawPartyId);
        if (id.isEmpty()) {
            throw notFound();
        }
        PartyId partyId = PartyId.of(id.get());
        Optional<List<ConsentStore.HistoryEntry>> rows = inOneSnapshot(unitOfWork -> {
            if (partyStore.kindOf(unitOfWork, partyId).isEmpty()) {
                return Optional.empty();
            }
            List<ConsentStore.HistoryEntry> page = consentStore.historyFor(unitOfWork, partyId.value(), before, size + 1);
            recordedReads.record(unitOfWork, ConsentAuditAction.CONSENT_HISTORY_READ, "party.Party", partyId.value().toString(),
                    "after=" + before.isPresent() + ", served=" + Math.min(size, page.size()));
            return Optional.of(page);
        });
        List<ConsentStore.HistoryEntry> page = rows.orElseThrow(CustomerReadDesk::notFound);
        List<ConsentStore.HistoryEntry> served = page.subList(0, Math.min(size, page.size()));
        String next = page.size() > size
                ? OperatorCollection.cursor(CONSENTS, Long.toString(served.get(served.size() - 1).seq()))
                : null;
        return new ConsentHistoryPage(served.stream().map(ConsentEntry::of).toList(), next);
    }

    // -----------------------------------------------------------------

    private Optional<CustomerId> resolve(Connection unitOfWork, LookupKind kind, String value) {
        return switch (kind) {
            case CUSTOMER_ID -> uuid(value)
                    .flatMap(id -> partyStore.findCustomer(unitOfWork, CustomerId.of(id)))
                    .map(Customer::id);
            case PARTY_ID -> uuid(value)
                    .flatMap(id -> partyStore.latestCustomerFor(unitOfWork, PartyId.of(id)))
                    .map(Customer::id);
            case LOGIN_IDENTIFIER -> LOGIN_IDENTIFIER.matcher(value).matches()
                    ? identityStore.findByLoginIdentifier(unitOfWork, new LoginIdentifier(value))
                            .flatMap(identity -> partyStore.latestCustomerFor(unitOfWork, PartyId.of(identity.partyId())))
                            .map(Customer::id)
                    : Optional.empty();
            case ACCOUNT_ID -> uuid(value)
                    .flatMap(id -> customerAccountStore.findById(unitOfWork, CustomerAccountId.of(id)))
                    .map(CustomerAccount::customerId)
                    .flatMap(owner -> partyStore.findCustomer(unitOfWork, CustomerId.of(owner)))
                    .map(Customer::id);
        };
    }

    private static LookupKind named(String kind) {
        try {
            return LookupKind.valueOf(kind);
        } catch (IllegalArgumentException unknown) {
            throw OperatorCollection.invalid("kind is one of " + Arrays.toString(LookupKind.values()) + ".");
        }
    }

    /** A canonical UUIDv7, or nothing: a value that is not one names no customer, party or account. */
    private static Optional<UUID> uuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            // Canonical and a UUIDv7 (ADR-0013): every identifier the platform mints is one, so anything else names nothing -
            // a 404, never the 500 the identifier types' own refusal would become.
            return parsed.toString().equals(value) && parsed.version() == 7 && parsed.variant() == 2
                    ? Optional.of(parsed)
                    : Optional.empty();
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    private static ApiException notFound() {
        return new ApiException(PlatformErrorCode.NOT_FOUND, "No customer or party matched the given identifier");
    }

    private <R> R inOneSnapshot(Function<Connection, R> work) {
        return operatorReadTransactions.execute(status -> {
            Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
            try {
                return work.apply(unitOfWork);
            } finally {
                DataSourceUtils.releaseConnection(unitOfWork, dataSource);
            }
        });
    }

    /** What a lookup answers: the customer's id, and nothing else. */
    public record CustomerLookupAnswer(String customerId) {}

    /**
     * One customer, identifier-first: the relationship's id, status and instants, the party's id and kind (a party holds no
     * status of its own), each identity of the party with its status, and the latest KYC case's id and status. Never a name,
     * login identifier, contact detail or document.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustomerProfile(
            String customerId,
            String status,
            Instant openedAt,
            Instant statusChangedAt,
            String partyId,
            String partyKind,
            List<CustomerIdentity> identities,
            CustomerKycCase latestKycCase) {

        static CustomerProfile of(Customer customer, PartyKind kind, List<CustomerIdentity> identities, Optional<KycCase> latest) {
            return new CustomerProfile(customer.id().value().toString(), customer.status().name(), customer.openedAt(),
                    customer.statusChangedAt(), customer.partyId().value().toString(), kind.name(), identities,
                    latest.map(CustomerKycCase::of).orElse(null));
        }
    }

    /** An identity of the customer's party: its id and status. */
    public record CustomerIdentity(String identityId, String status) {}

    /** The customer's latest KYC case - open or decided - by id, kind and status. */
    public record CustomerKycCase(String caseId, String kind, String status, Instant statusChangedAt) {
        static CustomerKycCase of(KycCase kycCase) {
            return new CustomerKycCase(kycCase.id().value().toString(), kycCase.kind().name(), kycCase.status().name(),
                    kycCase.statusChangedAt());
        }
    }

    /** A §7 page: {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConsentHistoryPage(List<ConsentEntry> items, String nextCursor) {}

    /** One fact of the history: what was granted or withdrawn, against which text, when. */
    public record ConsentEntry(String consentRecordId, String purpose, String action, int textVersion, Instant recordedAt) {
        static ConsentEntry of(ConsentStore.HistoryEntry entry) {
            return new ConsentEntry(entry.record().id().value().toString(), entry.record().purpose().name(),
                    entry.record().action().name(), entry.record().textVersion(), entry.record().recordedAt());
        }
    }
}
