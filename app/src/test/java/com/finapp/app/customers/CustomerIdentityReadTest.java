package com.finapp.app.customers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.app.api.RecordedReads;
import com.finapp.party.Customer;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyAuditAction;
import com.finapp.party.PartyId;
import com.finapp.party.PartyName;
import com.finapp.party.PartyStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A customer's name and country for operators (`P17-TSK-125`; the owner's D-25 decision 1): the desk over party's narrow read,
 * in memory. What the answer holds and what it never holds, its record, and its misses. The route's permission and factor are
 * {@code RoutePermissionRegisterTest}'s and {@code KycDocumentAndIdentityDoorsTest}'s; the same read on PostgreSQL is owed with
 * the database tier (the owner's ruling of 2026-11-20).
 */
@DisplayName("a customer's name and country: the two personal fields, read on their own and recorded, never the name in the"
        + " record (P17-TSK-125)")
@SuppressWarnings("try") // The scopes are used for their close side effect (the established idiom).
class CustomerIdentityReadTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor OPERATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
    private static final String NAME = "Ana María O'Brien-Nakamura";

    private final List<AuditRecord> written = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private final Map<CustomerId, Customer> customers = new HashMap<>();
    private final Map<PartyId, PartyName> names = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private final CustomerReadDesk desk = HermeticCustomerReads.desk(parties(),
            new RecordedReads((unitOfWork, record) -> {
                events.add("write:" + record.operation().code());
                written.add(record);
            }, IDS, CLOCK), events);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/operator/customers/x/identity");
    private CorrelationId correlation;
    private CorrelationContext.Scope flow;
    private SecurityContext.Scope actor;

    @BeforeEach
    void enter() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        correlation = CorrelationId.generate(IDS);
        flow = CorrelationContext.enter(Correlation.startingWith(correlation));
        actor = SecurityContext.enter(OPERATOR);
    }

    @AfterEach
    void leave() {
        actor.close();
        flow.close();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("the answer is the name the party registered under, said to be self-declared, and a country of residence"
            + " written as null - no module holds one - and nothing else")
    void theAnswerIsTheNameAndTheCountryAndNothingElse() {
        Customer customer = aCustomer();

        CustomerReadDesk.CustomerNameAndCountry answer = desk.nameAndCountry(customer.id().value().toString());

        assertThat(answer.legalName()).isEqualTo(NAME);
        assertThat(answer.legalNameSource()).as("given by the customer and changeable by them, never verified")
                .isEqualTo(CustomerReadDesk.CustomerNameAndCountry.SELF_DECLARED);
        assertThat(answer.countryOfResidence()).as("unresolved question #13: never inferred").isNull();

        JsonNode wire = JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(answer));
        assertThat(wire.propertyNames()).as("D-25: no other personal field")
                .containsExactlyInAnyOrder("legalName", "legalNameSource", "countryOfResidence");
        assertThat(wire.get("countryOfResidence").isNull()).as("written, not left out: it says 'not held'").isTrue();
        assertThat(calls).as("party's narrow read, never findById (SESSION_DERIVED)")
                .containsExactly("findCustomer", "registeredNameOf");
    }

    @Test
    @DisplayName("every read is recorded party.CustomerIdentityRead in the read's own transaction - the operator, the customer,"
            + " the party, the correlation - and the request marked; the name never in the record")
    void everyReadIsRecordedNeverTheName() {
        Customer customer = aCustomer();

        desk.nameAndCountry(customer.id().value().toString());

        assertThat(written).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo(PartyAuditAction.CUSTOMER_IDENTITY_READ);
            assertThat(record.operation().code()).isEqualTo("party.CustomerIdentityRead");
            assertThat(record.actor()).isEqualTo(OPERATOR);
            assertThat(record.targetType()).isEqualTo("party.Customer");
            assertThat(record.targetId()).isEqualTo(customer.id().value().toString());
            assertThat(record.correlationId()).isEqualTo(correlation);
            assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
            assertThat(record.changeSummary()).hasValue("party=" + customer.partyId().value()
                    + ", nameSource=SELF_DECLARED, country=absent");
            assertThat(record.toString() + record.changeSummary().orElseThrow()).doesNotContain(NAME, "O'Brien", "Ana");
        });
        assertThat(events).as("the read and its record, one transaction").containsExactly("begin",
                "write:party.CustomerIdentityRead", "commit");
        assertThat(request.getAttribute(RecordedReads.class.getName() + ".recorded")).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("an unknown, a malformed and a non-v7 customer id are one 404, recording nothing and reading no name")
    void aMissIsNotFoundAndUnrecorded() {
        for (String id : List.of(UUID.randomUUID().toString(), IDS.next().toString(), "not-an-id",
                IDS.next().toString().toUpperCase(java.util.Locale.ROOT))) {
            assertThatThrownBy(() -> desk.nameAndCountry(id)).as(id).isInstanceOfSatisfying(ApiException.class,
                    refused -> assertThat(refused.errorCode()).isEqualTo(PlatformErrorCode.NOT_FOUND));
        }
        assertThat(written).isEmpty();
        assertThat(calls).as("a name is read only for a customer that exists").doesNotContain("registeredNameOf");
    }

    @Test
    @DisplayName("the answer renders none of itself, and the profile still holds no name: the read is its own (D-25)")
    void theNameRendersNowhereElse() {
        Customer customer = aCustomer();
        CustomerReadDesk.CustomerNameAndCountry answer = desk.nameAndCountry(customer.id().value().toString());

        assertThat(answer.toString()).doesNotContain(NAME).isEqualTo("CustomerNameAndCountry[redacted]");
        assertThat(Arrays.stream(CustomerReadDesk.CustomerProfile.class.getRecordComponents()).map(RecordComponent::getName))
                .as("never folded into the profile")
                .noneMatch(component -> component.toLowerCase(java.util.Locale.ROOT).contains("name")
                        || component.toLowerCase(java.util.Locale.ROOT).contains("country"));
    }

    // -----------------------------------------------------------------

    private Customer aCustomer() {
        PartyId party = PartyId.of(IDS.next());
        Customer customer = Customer.rehydrate(CustomerId.of(IDS.next()), party, CustomerStatus.ACTIVE, CLOCK.instant(),
                CLOCK.instant());
        customers.put(customer.id(), customer);
        names.put(party, new PartyName(NAME));
        return customer;
    }

    /** party's port, in memory: the two reads this desk may make, and every other one refused. */
    @SuppressWarnings("unchecked")
    private PartyStore<Connection> parties() {
        return (PartyStore<Connection>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PartyStore.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "findCustomer" -> Optional.ofNullable(customers.get((CustomerId) args[1]));
                        case "registeredNameOf" -> Optional.ofNullable(names.get((PartyId) args[1]));
                        default -> throw new UnsupportedOperationException("PartyStore." + method.getName());
                    };
                });
    }
}
