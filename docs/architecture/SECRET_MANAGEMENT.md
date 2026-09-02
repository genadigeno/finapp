# Secret Management

How this platform handles credentials, and how to add one. The decision and its reasoning are
[ADR-0020](../adr/ADR-0020-secret-management.md); this document is the operational half.

Every section is labelled **Implemented** or **Decided, not yet implemented**, with the owning
task — the same convention `API_CONVENTIONS.md` uses, and for the same reason: a security document
that quietly mixes what is true with what is intended is worse than none, because it is believed.

---

## 1. The rule — *Implemented* (`P0-TSK-031`)

**No credential value lives in this repository.** Configuration is externalised: every credential
is read from an environment variable.

One exception, and it is not a secret: a single **marked local default**, published deliberately so
that a clean clone builds and runs with no setup step.

```
local-development-only-not-a-secret
```

It is named to say what it is. A value that cannot be mistaken for a secret cannot silently become
one — but a name is not a control, which is why §4 exists.

## 2. Two mechanisms, blind in different directions — *Implemented*

| | `CommittedConfigurationHoldsNoSecretTest` | `infra/scripts/secret-scan.sh` (gitleaks) |
|---|---|---|
| Runs in | `./gradlew build`, every build | CI, and on demand |
| Sees | the working tree | the whole of git history |
| Detects by | the **key**'s name | the **value**'s shape |
| Catches | `password: hunter2` | `-----BEGIN RSA PRIVATE KEY-----` |
| Misses | a credential under `value:` | `password: hunter2` |

File types scanned by the build rule: `.yaml`, `.yml`, `.properties`, `.sql`, `.kts`, `.env`,
`.conf`, `.ini`, `.sh`. That list exists twice - here in the rule, and as the `committedConfiguration`
input filter in `app/build.gradle.kts` - and a test fails the build if the two disagree, because a
file type only one side knows about would leave `:app:test` up to date and the check silently
unrun.

**Neither is the control on its own.** This was established by probing rather than reasoning:
four plausible secrets were committed to a throwaway repository and scanned. gitleaks caught the
private key, a high-entropy token and a real-shaped AWS key pair; it missed `password: hunter2` and
`POSTGRES_PASSWORD: correcthorse`, because there is nothing about a memorable password to detect.
A memorable password is what a human commits, and those two shapes are exactly the shape this
repository's configuration already has.

So "secret scanning green" and "no secret in the repository" are different claims. The build rule
is the control for the second; the scanner is a net for what gets past it, over history a build
rule never sees.

### What neither covers

The build rule reads **keys**. A credential parked under an innocent key — `value:`, `arg:`,
`item:` — has nothing to match on, and gitleaks only covers that when the value itself looks like a
secret. This is the same limit `SECURITY_ARCHITECTURE.md` records for `secretsAreWrapped`, from
which the vocabulary is borrowed. Stated here rather than glossed: a control believed to be total
is more dangerous than one whose edges are known.

## 3. Where a credential comes from, per environment — *Implemented*

| Environment | Source |
|---|---|
| A developer's machine | the marked local default, unless the variable is set |
| A developer overriding it | the environment variable, exported in the shell or set in `GRADLE_USER_HOME` — **never** in a file inside the repository |
| CI | GitHub Actions secrets, injected as environment variables. No CI job needs one today |
| A deployment | *Decided, not yet implemented* — Phase 15, with deployment. The seam is that the application reads its configuration from the environment, which every secret manager fills |

The variables, and the four files that read them:

| Variable | Read by |
|---|---|
| `FINAPP_DB_NAME`, `FINAPP_DB_URL` | `compose.yaml`, `application.yaml`, `platform/build.gradle.kts` |
| `FINAPP_DB_USER`, `FINAPP_DB_PASSWORD` | `compose.yaml`, `platform/build.gradle.kts` (bootstrap superuser) |
| `FINAPP_DB_MIGRATOR_USER`, `FINAPP_DB_MIGRATOR_PASSWORD` | `platform/build.gradle.kts` (Flyway) |
| `FINAPP_DB_APP_USER`, `FINAPP_DB_APP_PASSWORD` | `application.yaml`, `platform/build.gradle.kts` |

They are the *same* variables everywhere, so an override moves the container, the migration tool
and the application together rather than only one of them.

The marked local default appears in six files across YAML, Kotlin and SQL, none of which can import
a Java constant. It is single-sourced by enforcement: the build rejects any *other* local default,
so the six cannot drift apart.

## 4. The default is confined to loopback — *Implemented* (`P0-TSK-031`)

Externalised configuration has one silent failure mode: **the fallback is what you get when nobody
sets the variable.** The documented way around the whole scheme is to forget.

`DatabaseCredentialGuard` refuses to start when the marked local default is aimed at a database
that is not on this machine. It reads what the pool will **actually** connect with, not just
`spring.datasource.url`: `spring.datasource.hikari.jdbc-url` is bound afterwards and wins, and a
guard inspecting a value nothing connects with is not a guard.

```
Refusing to start: the marked local-development database credential is in use against a
database that is not on this machine [db.internal]. That credential is published in this
repository and is not a secret. Set FINAPP_DB_APP_PASSWORD.
```

It **fails closed** — a URL whose host cannot be read is refused rather than assumed harmless —
and it checks **every** host in a failover list. A private address is not a local one.

## 5. Adding a secret

1. **Do not put the value in a file in this repository.** There is no encrypted-in-git scheme, on
   purpose: ADR-0020 explains why ciphertext in permanent history is the wrong trade.
2. Read it from an environment variable. If it needs a local default, use the marked one — the
   build rejects any other.
3. If it is held in Java, hold it in `Sensitive<T>`. The build requires this for any field or
   accessor whose name says it is a secret (ADR-0019).
4. Give it a name that says what it is. If `secretsAreWrapped` rejects the name, that is the rule
   working — wrap the value, or rename the field because it does not actually hold one. **Do not
   add an exemption**: the rule has none, and the first one is where default-deny ends.
5. Run the build. The configuration rule runs in `./gradlew build`.

## 6. Running the scan

```bash
./infra/scripts/secret-scan.sh
```

Needs Docker. This is the same script CI runs, so the image pin and the arguments exist once. It
scans **history**, not the working tree: a secret committed and later removed is still disclosed,
because it remains in the objects anyone can clone.

### Proving the scan still fails — *Implemented*

`P0-TSK-031`'s third acceptance clause is "a deliberately committed dummy secret fails CI".

**Never commit a dummy secret to this repository to test it.** History is scanned, so the commit
that proved the scan works would make the scan red for ever, and undoing it needs a history
rewrite. Use a throwaway clone:

```bash
git clone . /tmp/scan-proof
cd /tmp/scan-proof
# Generated, never written down. See the note below.
printf 'aws_secret_access_key = %s\n' \
  "$(head -c 30 /dev/urandom | base64 | tr -d '=+/')" > leaked.ini
git add leaked.ini && git commit -m "dummy"
cd - && ./infra/scripts/secret-scan.sh /tmp/scan-proof
```

Exit status 1, `leaks found: 1`, which is what fails the CI job. Deleting the file and committing
again does **not** clear it — which is the property being demonstrated, not a side note.

**The dummy value is generated rather than written into this document, and that is a finding
rather than a style choice.** The first drafts of this page and of ADR-0020 quoted realistic
examples, and the scan caught both documents. That is the scanner working: it cannot tell a
documented example from a disclosure, and it must not try. The alternative - allowlisting the
documentation about the scanner - would have weakened the scan to make room for prose about the
scan. So the tables above describe shapes, and the only key-shaped string this procedure produces
exists for the few seconds it takes to prove the point.

This is how the clause is verified today, because the repository has no git remote and CI has
never executed on a runner. That limitation is recorded in `CURRENT_STATE.md`; it closes on the
first successful run after a remote is added.

## 7. What is deliberately not here

| | Why | Owning phase |
|---|---|---|
| A secrets manager | No deployment, no key management, and one local database password. Choosing a manager with no real requirement to shape it is how you get the wrong one | Phase 15 |
| Key rotation, KMS, envelope encryption | Needs keys, of which there are none | Phase 15 |
| TLS and at-rest encryption | Its own task | `P0-TSK-034` |
| Per-service identity, mTLS | Needs services | Phase 16 |
| Dependency artefact verification | A dependency is external input too, but a different supply chain | `P0-TSK-039` |
