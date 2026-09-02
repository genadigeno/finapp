# ADR-0016 — Liveness and readiness answer different questions, and only readiness consults dependencies

Status: Proposed

Date: 2026-09-02

## Context

The platform needs the three operational endpoints every orchestrator expects: is this process
alive, should it receive traffic, and which build is it running. `P0-TSK-027` requires that
"readiness must reflect real dependency state".

The obvious implementation — one health check, consulted by both probes — is wrong in a way that
is invisible until the day it matters, and it is the default shape of most deployments:

**If liveness consults the database, a database blip restarts the fleet.** A thirty-second
PostgreSQL failover makes every instance report unhealthy at once. The orchestrator kills all of
them simultaneously, and they come back together, reconnecting in a herd to a database that was
already struggling. A recoverable degradation becomes a total outage, caused by the health check
that existed to prevent one. Worse, the restarts destroy the state an operator needs to diagnose
the failure, and a crash-looping instance reports nothing about *which* dependency broke.

There is a second, quieter trap. Spring Boot's default `readiness` health group contains
`readinessState` alone. Adding the actuator and a `DataSource` therefore produces a readiness
endpoint that returns **UP while PostgreSQL is unreachable** — a plausible-looking endpoint that
answers the wrong question, and one nothing complains about.

A third question arose on the way: the application had **no `DataSource` at all**. Readiness
cannot check a dependency the application does not hold.

## Decision

**1. Liveness depends on nothing external.**

`/actuator/health/liveness` includes `livenessState` and nothing else. It answers one question —
*is this process broken in a way that only a restart can fix?* — and for an unreachable
dependency the answer is no.

**2. Readiness includes PostgreSQL.**

`/actuator/health/readiness` includes `readinessState` and `db`. An instance that cannot reach the
transactional source of truth can serve nothing financial, so it must stop receiving traffic. The
group is configured explicitly, because the framework default is the opposite.

**Kafka and Redis are deliberately excluded.** Kafka is transport, not truth (`INV-EVT-02`): the
outbox holds events durably in PostgreSQL, and a broker outage delays publication rather than
invalidating the instance. Refusing traffic because Kafka is down would convert a delay into an
outage. Redis is cache and coordination and never financial truth. Both become readiness
dependencies only if some capability is ever made to *require* them synchronously — which would
itself need an ADR.

**3. Readiness is checked through the pool the application uses.**

Not through a connection opened for the purpose. A health check with its own connection reports
healthy while the pool is exhausted, which is exactly the condition under which traffic must be
diverted.

**4. The application starts when PostgreSQL is unreachable.**

Deliberately. An application that refuses to boot without its database cannot report readiness at
all: the orchestrator sees a crash-loop instead of a NOT_READY instance, and the signal naming the
broken dependency is lost at the moment it is needed. Starting and reporting DOWN is strictly more
informative and strictly safer — the instance takes no traffic either way.

**5. The application gets a plain `DataSource`, and nothing more.**

`spring-boot-starter-jdbc` (HikariCP and `spring-jdbc`), not `spring-boot-starter-data-jpa`.
**This does not answer unresolved question 12.** The data-access mechanism belongs to Phase 3,
where Hibernate's dirty checking — which emits `UPDATE`s — has to be weighed against `INV-LED-03`
and `INV-HIST-01`, under which posted financial records are never updated and the application role
holds no `UPDATE` privilege at all. `MoneyColumns` was written mechanism-agnostic for this reason
and remains so.

The application connects as `finapp_app`, never the bootstrap superuser, so the check exercises
the privileges the application actually has.

**6. Every wait on the readiness path is explicitly bounded.**

Connection acquisition, validation and TCP connect all have explicit timeouts shorter than a
typical probe interval. A readiness check that hangs reports nothing, and what an orchestrator
concludes from a timed-out probe is its own business. `socketTimeout` is deliberately *not* set:
it bounds how long a query may run, and choosing that number before any real query exists would be
a guess that aborts legitimate work later.

**7. Status is published; detail is not.**

`show-details: never`, `show-components: never`, and an explicit allow-list exposing `health` and
`info` only. A detailed health body names the JDBC URL, the driver, the validation query and which
dependency failed; `/env` is the configuration, `/beans` and `/mappings` are a map of the
application, and `/heapdump` is every secret the process has ever held. None of it is behind
authentication, because there is no authentication yet. `when-authorized` becomes the right
setting once `P0-EPIC-10` provides an authority to be authorized against.

**8. Operational endpoints are not versioned.**

They are consumed by orchestrators and scrapers whose configuration is deployment-scoped, not by
clients holding a contract; moving them on a version bump would break a liveness probe for no
benefit. ADR-0015 asserted this would fall out of the mechanism rather than needing an exception —
actuator is served by its own handler mapping, untouched by `PathMatchConfigurer`. Nothing could
verify that when it was written. It is now asserted by test, in both directions.

**9. Build identity carries no timestamp.**

`/actuator/info` publishes group, artifact, name and version. The build time is excluded because
`isPreserveFileTimestamps = false` and `isReproducibleFileOrder = true` make archives
reproducible — two builds of the same source produce identical bytes — and an embedded build time
defeats exactly that, for information the version and, later, the commit carry better.
Reproducibility is a supply-chain property; "when was this built" is not worth losing it for.

## Alternatives Considered

### A. One health endpoint used by both probes
Pros: Simplest; one thing to configure.
Cons: The failure described in Context. It is the default shape of most deployments and the reason
dependency blips become outages.

### B. Liveness checks a shallow internal signal (thread pool, deadlock detector)
Pros: Would catch a genuinely wedged process, which `livenessState` alone does not.
Cons: Nothing in the platform can currently wedge in a way a restart fixes — there is no business
workload. Speculative, and a wrong liveness signal restarts healthy instances. Revisit when there
is a workload to observe.

### C. Readiness also checks Kafka and Redis
Pros: "All dependencies healthy" is easy to explain.
Cons: Converts a broker delay into a traffic outage, and contradicts `INV-EVT-02` — treating
transport as though it were truth. The outbox exists precisely so that a broker outage is
survivable.

### D. `show-details: always`
Pros: An operator sees which dependency failed without consulting logs.
Cons: Unauthenticated reconnaissance: the JDBC URL, the host, the database name, the driver
version and an exception message. The aggregate status is what an orchestrator acts on; detail
belongs behind authentication, and there is none yet.

### E. A separate management port
Pros: Detail could be exposed on an internal-only port, giving both properties.
Cons: A port is not an authentication mechanism, and treating it as one is how internal endpoints
end up reachable. It is also a deployment-topology decision that belongs with Phase 15. Revisit
there, alongside `when-authorized`.

## Consequences

Positive:
- A database outage degrades the platform to "no traffic served" rather than "no instances
  running", and the instances remain available to say why.
- Which dependency gates traffic is a written decision rather than a framework default.
- The health path is bounded, so a blackholed database produces a negative answer rather than no
  answer.

Negative:
- Detail is not available to an operator without authentication, so the *first* diagnostic step
  after a readiness failure is logs and metrics rather than the endpoint. Accepted until
  `P0-EPIC-10`.
- The application now carries a connection pool and a driver it does not yet use for anything but
  a health check. That is the cost of checking the real pool rather than a synthetic connection.

Operational impact:
- `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`, unversioned.
- Readiness returns 503 when PostgreSQL is unreachable, which is what a load balancer acts on — a
  DOWN body behind a 200 is not.

Security impact:
- Allow-list exposure; no detail, no components, no environment, no JVM or OS identification.
- The application connects with the least-privileged role, so the health check cannot pass on
  privileges the application would not otherwise have.

Financial impact:
- None directly. Indirectly: an instance that cannot reach the ledger's database must not accept a
  money-moving request, and readiness is the mechanism that stops it being sent one.

## Invariants / Constraints

- `INV-EVT-02` — Kafka is transport, not the accounting source of truth. It is therefore not a
  readiness dependency.
- `INV-AUD-02` — no credential, host, URL or internal identifier in a response. The health and
  info bodies are asserted against this directly.
- `ADR-0011` — migrations never run at application startup. Flyway is absent from the
  application's runtime classpath, so this is structural rather than a setting, and is asserted.
- Unresolved question 12 (data-access mechanism) remains open. A `DataSource` is not an ORM.

## Follow-up

- **`show-details: when-authorized`** once `P0-EPIC-10` provides an authority. Until then the
  aggregate status is all that is published.
- **A separate management port**, with Phase 15's deployment topology.
- **Kafka and Redis health**, if and only if some capability is made to require them
  synchronously — which needs its own decision, not a default.
- **A liveness signal with substance** once there is a workload that can wedge.
- **`socketTimeout`** with Phase 3, when there are real queries to measure.
