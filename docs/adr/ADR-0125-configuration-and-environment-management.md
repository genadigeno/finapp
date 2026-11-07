# ADR-0125 — Configuration and environment management: environments differ by values, never by code path; every property registered and classed

Status: Proposed
Date: 2026-11-07
Phase: 15
Context: Platform (configuration) · every module (its `finapp.*` properties) · Security
Rests on: ADR-0016, ADR-0020, ADR-0023, ADR-0120, ADR-0122, ADR-0123; owner decision C8 of `PHASE_15_PLAN.md` §2.2
(recommended default awaiting ratification).

## Context

The application has one configuration file, `application.yaml`, and no Spring profile. That is deliberate: the guards
key on loopback, not on a profile name (`DatabaseCredentialGuard`), so a mislabelled profile cannot relax a control.
The test overlay disables relays, consumers and sweepers. Configuration that decides money is mostly versioned,
four-eyes data in PostgreSQL: fee schedules, terms, policies, rule sets and mapping versions. Some financial behaviour
is still set by properties, for example a lock grace, a staleness bound or a fail-safe timeout. Nothing lists every
property, its class or its source in each environment. Nothing would notice if two replicas ran with different values.

## Decision

1. **The environments are `local`, `test`, `ci`, `staging` and `production`.** `local` is compose dependencies with the
   application on the host. `test` is Testcontainers. `ci` is `kind` with three replicas and test-only state. `staging`
   and `production` are managed cloud environments, in separate accounts (C8). Production data never enters a
   non-production environment.
2. **One mechanism.** The application reads the environment. Kustomize overlays supply non-secret values as a
   ConfigMap, hash-named so that a change rolls the Deployment. The secret store supplies secrets (ADR-0123). **No
   Spring profile decides behaviour:** an environment differs by values, never by a code path.
3. **The configuration register.** One document lists every `finapp.*` property and every Spring property an overlay
   sets. For each it gives a class, the default and the source in each environment. The classes are:
   - `SECRET`: from the secret store, never in an overlay;
   - `FINANCIAL`: it changes a financial outcome, limit, price, route or risk judgement;
   - `OPERATIONAL`: anything else.

   A test holds the register both ways against the code.
4. **Financial configuration is versioned and approved.** Wherever possible, a value that decides money lives as
   versioned four-eyes data in PostgreSQL, as it already does. A property classed `FINANCIAL` changes only through the
   production overlay's two-person approval (ADR-0122), and it **must be safe while replicas hold mixed values**. During
   a rollout, one replica judges with the old value and another with the new one. A property for which that is not safe
   belongs in versioned database data, not in a property. Each replica publishes the fingerprint of its effective
   `FINANCIAL` configuration as `finapp.config.fingerprint`. An alert fires when replicas disagree **for longer than a
   rollout**: its `for:` exceeds the longest rolling update the overlay allows, so a rollout's expected disagreement
   never pages. *(Widened by the transition's review, 2026-11-07, its M-10.)*
5. **Startup refuses an unsafe configuration.** The existing guards (transport, credentials, the connection budget,
   providers) stay. The schema-version guard is added (ADR-0122). A missing required value fails startup, never
   readiness after traffic has begun.

## Alternatives Considered

### Spring profiles per environment
Cons: a profile name becomes a switch that can relax a control. The loopback-keyed guards exist to prevent exactly that.

### A configuration server
Cons: it is another runtime dependency on the startup path. Git plus overlays already gives versioning, review and audit.

### Moving every financial property into database-versioned data now
Cons: it is a domain change in many modules. The register and the approval boundary bound the risk first, and a
property can migrate to versioned data when its module next changes.

## Consequences

Positive: every value has an owner, a class and a source. Drift between replicas becomes visible. A change to financial
behaviour is reviewed by two people.
Negative: the register must be kept current, and the test enforces that.
Operational impact: a configuration change is a pull request against an overlay.
Security impact: secrets cannot slip into an overlay, and a profile cannot weaken a guard.
Financial impact: protective. Configuration that changes money is versioned, reviewed and identical across the fleet.

## Invariants / Constraints

ADR-0020: there is no committed secret, and the local default stays confined to loopback. Every replica of one release
runs one configuration.

## Follow-up

`P15-TSK-031` builds this ADR. It corrects `SYSTEM_ARCHITECTURE.md` §Baseline Stack to the stack in use.
