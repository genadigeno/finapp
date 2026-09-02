# ADR-0023 — A database off this machine is reached with verified TLS, or not at all

Status: Proposed

Date: 2026-09-02

## Context

`P0-TSK-034` asks for TLS and at-rest encryption expectations to be documented and applied where
locally applicable, with the acceptance criterion that **local setup does not normalise insecure
defaults into later environments**.

The local stack has no TLS anywhere: PostgreSQL runs with `ssl = off`, Kafka's listeners are
explicitly `PLAINTEXT`, and Redis has neither TLS nor a password. All of it is bound to loopback,
which is what makes it acceptable.

The risk is not the local stack. It is that the *configuration* is the same file a deployment
inherits, and the driver's default hides the difference.

### What the driver actually does, measured

Against this repository's own container:

| `sslmode` | Result |
|---|---|
| unset | connects, **unencrypted**, silently |
| `prefer` | connects, **unencrypted**, silently |
| `require` | refused |
| `verify-full` | refused |

The platform sets no `sslmode`. Locally that is correct. In a deployment it is a plaintext
connection to a remote database carrying every credential, amount and account identifier in the
clear — and **nothing reports it**: the pool connects, readiness returns UP, logs are quiet.

`prefer` is the worst of the available defaults precisely because it looks like it is trying.

## Decision

**1. A database that is not on loopback must be reached with `sslmode=verify-full`.**
`TransportSecurityGuard` refuses to start otherwise. This is the same shape as ADR-0021's actor
rule and ADR-0020's credential guard: the unsafe thing is not discouraged, it is unreachable.

**2. `verify-full`, not `require`.** `require` encrypts and verifies nothing — it stops passive
eavesdropping and does not stop an active attacker presenting their own certificate, which is the
threat on the network path to a financial database. `verify-ca` checks the issuer but not the
hostname, so it accepts a valid certificate issued for a different host. Only `verify-full` checks
both. The cost is that a deployment must supply a trust anchor, and that cost is the point: it is
the deployment asserting which database it trusts.

**3. Loopback is exempt.** A loopback connection does not leave the host. Requiring TLS there would
mean every developer provisioning certificates for a container, and a setup step that elaborate is
one people work around — which costs more security than it buys.

**4. Every configured source must agree, rather than trusting a precedence.** `sslmode` can be set
in the JDBC URL's query string or as a data-source property. The URL was *measured* to win in both
directions (`url=require, props=disable` is refused; `url=disable, props=require` connects), and
relying on that would make the control correct only for as long as a driver implementation detail
holds. Requiring that no configured source is weaker cannot be wrong about which one the driver
picks.

**5. Kafka, Redis and inbound HTTP are documented, not guarded.** There is no Kafka or Redis client
on the classpath and the application is never the TLS endpoint for inbound traffic. A guard for a
connection that does not exist would be guarding nothing; the expectations are in
`SECURITY_ARCHITECTURE.md` and become enforceable with their first client.

**6. Nothing is encrypted at rest, and nothing needs to be yet.** Phase 0 holds no customer data, no
money and no credentials. At-rest protection is a property of provisioned infrastructure and of
columns that actually carry restricted data — neither exists. The expectations and their owning
phases are recorded so that the absence is a decision rather than an oversight.

## Alternatives rejected

**Requiring TLS everywhere, including loopback.** Consistent, and it makes a clean clone unable to
build without certificate provisioning. `DOD-BUILD` requires reproducibility from a clean clone
with no developer-machine-specific setup, and a security control that makes the first build fail is
a control that gets disabled on day one.

**Enabling TLS in the local PostgreSQL container.** Tempting, because then local and deployed would
be identical — which is the argument this project accepted for pinning images and for ECS logging
locally. Rejected because it needs a certificate committed to the repository or generated at
startup: a committed key is exactly what ADR-0020 forbids, and a generated self-signed one can only
be used with `verify-ca`/`verify-full` if its CA is also trusted, so it would teach a *weaker* mode
than the one being required. The asymmetry is honest: the local stack is exempt, not simulated.

**Setting `sslmode=verify-full` in `application.yaml`.** It would break every local run, since the
container offers no TLS. Setting `prefer` would be worse than nothing — it is the silent-fallback
default given a name, and writing it down would make it look deliberate.

**A startup warning instead of a refusal.** A warning in a log nobody reads during a deployment is
indistinguishable from no warning. The failure it prevents is silent and permanent — traffic in the
clear — so the response has to be loud and immediate.

## Consequences

- A deployment cannot reach a remote database without verified TLS; the failure names the setting.
- Local development is unchanged and needs no setup.
- The guard shares `DatabaseEndpoint` with `DatabaseCredentialGuard`, so "is this database on this
  machine?" has one definition. Two copies would drift.
- The guard checks configuration, not the established connection. It cannot detect a server that
  negotiates a weak cipher or a trust store containing something it should not — those are
  deployment properties, and a Phase 15 concern.
- **The driver has one configuration source this guard cannot read: a libpq service file**
  (`?service=name` plus `pg_service.conf`, resolved through `PgServiceConfParser`, which is the
  only driver class that consults the environment). Checked during review, and it fails in the safe
  direction only: a service file that sets a *weak* mode is still refused, because the guard sees no
  verifying mode in the sources it can read. A service file that sets `verify-full` produces a false
  refusal. That is the correct trade — the alternative is trusting a file the application cannot
  see. Verified that the driver reads `sslmode` from **no** environment variable, so there is no
  silent override.
- The set of modes treated as insufficient is **derived from the driver's own `SslMode` enum** at
  test time rather than listed, so a driver upgrade that adds a mode fails the build and asks
  whether it verifies the server. The list had happened to match the driver exactly and nothing
  checked that — the stale-list defect this repository has met in CI, in a coverage guard and in a
  privilege check.
- Kafka and Redis remain plaintext with no enforcement until a client exists. Recorded as debt.

## References

- `SECURITY_ARCHITECTURE.md` §Transport encryption, §Encryption at rest
- ADR-0020 (marked local default confined to loopback — the same shape), ADR-0021
- `DATA_CLASSIFICATION.md` — what would be crossing the wire
