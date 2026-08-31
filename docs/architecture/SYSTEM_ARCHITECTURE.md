# System Architecture

## Baseline Stack

Backend:
- Java
- Spring Boot
- Spring Security
- Gradle

Transactional:
- PostgreSQL

Caching / ephemeral:
- Redis

Messaging:
- Kafka

Object storage:
- S3-compatible storage

Observability:
- OpenTelemetry
- Prometheus
- Grafana
- centralized structured logging
- distributed tracing

Infrastructure:
- Docker
- Kubernetes
- Terraform

Testing:
- JUnit
- AssertJ
- Testcontainers
- WireMock
- Gatling or k6

API:
- REST / OpenAPI
- gRPC where justified
- signed/idempotent webhooks

## Architectural Principles

1. Domain ownership before service decomposition.
2. Transactional truth before projections.
3. Explicit state machines for important financial lifecycles.
4. Asynchronous integration where it improves resilience without violating invariants.
5. Provider adapters at external boundaries.
6. Outbox/inbox patterns where reliable publication or deduplication requires them.
7. Observability across customer -> request -> domain operation -> provider -> ledger -> settlement -> reconciliation.
8. Regulatory-specific behavior behind policy/configuration/adapters.

## Preferred Evolution

Start with a modular monolith unless a hard requirement justifies distribution. Extract services only when independent scaling, isolation, ownership, deployment, or reliability warrants it.
