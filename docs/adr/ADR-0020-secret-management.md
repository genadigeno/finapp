# ADR-0020 — Secrets are externalised, and the local default is confined to loopback

Status: Proposed

Date: 2026-09-02

## Context

`.claude/rules/security.md` opens with *never hard-code secrets*, and `DEFINITION_OF_DONE.md`
§1.8 requires that no secret appears in source, configuration, logs, events or API responses.
`P0-TSK-031`'s acceptance criterion states it in three clauses:

1. no secret value in the repository;
2. secret scanning green;
3. a deliberately committed dummy secret fails CI.

Phase 0 has no authentication, no provider credentials and no key material. It has exactly one
credential: the PostgreSQL password for `finapp_app` and `finapp_migrator`. That makes this the
right moment to decide the approach — the decision is cheap now and expensive once eight
integrations each have their own habits.

### What the probe found, and why it changed the decision

The obvious reading is that clause 2 delivers clause 1: run a scanner, keep it green, and no
secret is in the repository. That was tested rather than assumed, by committing four plausible
secrets to a throwaway repository and running the pinned gitleaks image over it.

| Committed | Result |
|---|---|
| `-----BEGIN RSA PRIVATE KEY-----` block | caught |
| `db.password=` followed by 32 random alphanumerics | caught |
| a real-shaped AWS access key pair | caught |
| `password: hunter2` in a YAML | **missed** |
| `POSTGRES_PASSWORD: correcthorse` in a Compose file | **missed** |

None of those values is written out in this repository, and that is not squeamishness. The first
draft of this ADR quoted the high-entropy one verbatim, and **the scan caught the ADR** - which is
the scanner working exactly as intended, since a scanner cannot tell a documented example from a
disclosure and must not try. The examples describe shapes; the procedure in
`SECRET_MANAGEMENT.md` §6 generates its dummy value at run time. Allowlisting the document instead
would have weakened the scan to accommodate prose about the scan.

gitleaks is an entropy-and-pattern detector, and it is good at it. It is structurally blind to a
memorable password on a key named `password`, because there is nothing about `hunter2` to detect.

**A memorable password is what a human commits**, and the two shapes it missed are the exact shape
this repository's own configuration already has. So clause 2 does not imply clause 1, and a
scanner cannot be the control for it.

### The failure mode of externalised configuration

Every credential here is already read from an environment variable with a marked local default:

```yaml
password: ${FINAPP_DB_APP_PASSWORD:local-development-only-not-a-secret}
```

That is the right shape and it has one silent failure mode: **the fallback is what you get when
nobody sets the variable.** The documented way around the entire scheme is to forget. A first
deployment made by someone who has only ever run this locally, against a database provisioned by
this repository's own init script, runs on a password published on the internet — and nothing says
so. The application starts, the pool connects, readiness reports UP.

`DOD-SEC` requires that no control is "bypassable by a documented path". Naming the value honestly
is not a control. A name stops nothing.

## Decision

**1. No credential literal in the repository, enforced by the build.**
`CommittedConfigurationHoldsNoSecretTest` walks the repository for configuration and fails the
build unless every value assigned to a credential-named key is a placeholder, or is exactly the
one marked local default. Default-deny, on ADR-0019's argument: a rule people must remember is
opt-in with extra steps. Files are **discovered**, never listed, so a new `application-prod.yaml`
is covered without anyone remembering.

**2. The scanner stays, as a net rather than as the control.** It covers what the build rule
cannot — a credential parked under an innocent key, when the value itself looks like a secret —
and it covers the whole of history, where a build rule only sees the tip. The two are blind in
different directions, which is the reason to run both.

**3. One marked local default, held in place by that same rule.** The value is written in six
files across YAML, Kotlin and SQL, none of which can import a Java constant. Rejecting any *other*
local default is what makes it single-sourced; a comment claiming it appeared in three places was
already wrong when it was written.

**4. The marked local default is usable only against loopback.** `DatabaseCredentialGuard` refuses
to start when that value is aimed at a database that is not on this machine. This is the control
that closes the "forget to set it" path. It reads the values the pool will actually connect with:
`spring.datasource.hikari.jdbc-url` is bound after the generic property and wins, so a guard
reading only `spring.datasource.url` inspects a value nothing connects with - which was a real
bypass, proven by starting the application against a remote host with the generic URL left on
loopback.

**5. The scan is one definition.** `infra/scripts/secret-scan.sh` holds the pinned image and the
arguments; CI calls it and so does a developer. The workflow previously held both and claimed in a
comment that the same command could be run locally, which was true only for somebody willing to
retype a `sha256` digest.

**6. A dummy secret is never committed to this repository to test the scan.** History is scanned,
so the commit proving the scan works would make the scan red for ever and removing it would need a
history rewrite. The demonstration runs against a throwaway clone.

## Why loopback rather than a profile

A profile asks the deployment to declare itself, which is the same class of mechanism as the
environment variable it is meant to backstop — one more thing to forget, and forgetting it fails
open. The database's address is not a declaration. It is the fact of the matter about where the
data actually is, it is already configured, and it cannot be omitted.

The guard **fails closed**: if the password is the marked default and no host can be read from the
URL, it refuses. Failing open would make any URL shape the parser did not anticipate a documented
path around the control.

It checks **every** host, because PostgreSQL accepts a failover list and checking only the first
would let the second be anywhere at all. A private address is not a local one: who else is on the
VPC is not this guard's assumption to make.

## Alternatives rejected

**A secrets manager (Vault, AWS Secrets Manager, SOPS) now.** It is the right answer for a
deployed platform and the wrong one for Phase 0. There is no deployment, no key management and no
secret beyond a local database password, so it would be a dependency, a bootstrap credential and
an operational surface chosen without a single real requirement to shape them. The seam that
matters — *configuration is external, and the application reads it from the environment* — is what
this ADR establishes, and every manager fills it. Deferred to Phase 15 with deployment.

**Encrypted secrets committed to the repository (SOPS, git-crypt).** Trades a plaintext secret for
a key that must live somewhere else anyway, and puts ciphertext in history for ever, where it is
attackable offline and cannot be rotated by deletion. `INV-HIST-01`'s reasoning applied to the
wrong thing: history that cannot be edited is a virtue for financial records and a liability for
credentials.

**A gitleaks allowlist for the marked local default.** Unnecessary — it was verified not to trigger
gitleaks at all — and it would have weakened the scan for no benefit. If a genuine false positive
appears it is allowlisted as that one finding, narrowly; a rule is never disabled.

**Teaching `secretsAreWrapped` that a name ending in `VARIABLE` or `PROPERTY` refers to a secret
rather than holding one.** This came up immediately: the guard's field naming the environment
variable to set was called `APP_PASSWORD_VARIABLE`, and the rule rejected it as a false positive.
The exemption would have been true, and it would also have let `PASSWORD_PROPERTY = "hunter2"`
through for ever after. `secretsAreWrapped` has no exemption set at all, and the first exemption is
where default-deny ends. The field was renamed instead. `DEFINITION_OF_DONE.md` §3 is unambiguous:
a change is not done if a security control was weakened so a test would pass.

**Rejecting the local default outright and requiring the variable everywhere.** Every developer
would then need a setup step before the first build, and the value they chose would be
undocumented and different on each machine — which makes the local database credential a real
secret that people paste into chat. A marked, published, loopback-only default is safer than a
weak private one.

## Consequences

- A credential cannot reach committed configuration without failing the build, including in the
  low-entropy form the scanner cannot see.
- The marked local default cannot drift between the six files that carry it.
- A deployment that forgets `FINAPP_DB_APP_PASSWORD` fails to start with a message naming the
  variable, instead of running on a published password.
- Clause 3 is demonstrable on any machine with Docker, which matters because this repository has
  no git remote and CI has never executed on a runner.
- The build rule is name-based and inherits that limit: a credential under an innocent key has
  nothing to match on. Stated in `SECRET_MANAGEMENT.md` rather than glossed, because a control
  believed to be total is worse than one whose edges are known.
- Nothing here is a pattern for a deployed platform's secret storage. That is Phase 15.

## References

- `docs/architecture/SECRET_MANAGEMENT.md` — the approach, and how to add a secret
- ADR-0019 — default-deny redaction, whose argument this reuses for configuration
- `INV-AUD-02`, `DEFINITION_OF_DONE.md` §1.8 and §3
- `infra/scripts/secret-scan.sh`
