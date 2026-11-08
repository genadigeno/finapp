package com.finapp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A person is keyed once (`P15-TSK-021`): the capacity a request types a person in never reaches an idempotency scope,
 * so a retry that crosses a role change or the release that introduced the typing finds its record.
 */
@DisplayName("an actor's keyed type names a person once (P15-TSK-021)")
class ActorKeyedTypeTest {

    @Test
    @DisplayName("an operator and the same identity as a customer key alike; every other population keys as itself")
    void aPersonIsKeyedOnce() {
        assertThat(new Actor("id-1", ActorType.EMPLOYEE).keyedType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(new Actor("id-1", ActorType.CUSTOMER).keyedType()).isEqualTo(ActorType.CUSTOMER);
        assertThat(new Actor("m-1", ActorType.MERCHANT).keyedType()).isEqualTo(ActorType.MERCHANT);
        assertThat(new Actor("s-1", ActorType.SERVICE).keyedType()).isEqualTo(ActorType.SERVICE);
        assertThat(Actor.SYSTEM.keyedType()).isEqualTo(ActorType.SYSTEM);
    }

    @Test
    @DisplayName("the audit type is untouched: an operator is still recorded EMPLOYEE")
    void theAuditTypeIsTheCapacity() {
        assertThat(new Actor("id-1", ActorType.EMPLOYEE).type()).isEqualTo(ActorType.EMPLOYEE);
        assertThat(new Actor("id-1", ActorType.EMPLOYEE).toString()).isEqualTo("EMPLOYEE:id-1");
    }
}
