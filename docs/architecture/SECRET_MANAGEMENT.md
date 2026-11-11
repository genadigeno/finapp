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

## 3. Where a credential comes from, per environment — *Implemented* (`P15-TSK-018` for a deployment)

| Environment | Source |
|---|---|
| A developer's machine | the marked local default, unless the variable is set |
| A developer overriding it | the environment variable, exported in the shell or set in `GRADLE_USER_HOME` — **never** in a file inside the repository |
| CI | GitHub Actions secrets, injected as environment variables. No CI job needs one today |
| The `kind` rehearsal | Generated per run by `infra/scripts/kind-secrets.sh` into the two Secrets a deployment reads and the rehearsal's own two, and never written to the repository. `kind` has no store and no operator, and the manifest policy refuses an `ExternalSecret` rendered there |
| Staging and production | The environment's secret store, delivered by External Secrets Operator (§3.5). **Built as manifests and checked over them; nothing here has run against a cluster or a store.** The application still reads the environment (ADR-0020), so nothing in its code changed |

### 3.1 What only a developer's machine reads

`FINAPP_DB_NAME`, `FINAPP_DB_USER` and `FINAPP_DB_PASSWORD` (the local container's name and bootstrap superuser) are read by
`compose.yaml` and `platform/build.gradle.kts`, and by nothing that is deployed.

Each is read wherever its role is used, so an override of the database's name, address or superuser moves the container and
every tool together. **The ordinary roles are the exception**: `infra/postgres/initdb/00-roles.sql` creates `finapp_migrator`,
`finapp_app` and *(since `P15-TSK-021`)* `finapp_reporting` with the marked local default, because an init script cannot read the
environment. Overriding `FINAPP_DB_MIGRATOR_PASSWORD`, `FINAPP_DB_APP_PASSWORD` or `FINAPP_DB_REPORTING_PASSWORD` against the
local container therefore also means altering that role's password to match, or the tool refuses to connect.
`DatabaseCredentialGuard` confines the reporting role's marked default to loopback exactly as it does the application's, and its
refusal names `FINAPP_DB_REPORTING_PASSWORD`. *(This said an override moved everything together until the Phase 6 → 7 transition.)*

The local broker's `PLAINTEXT` never uses the SCRAM credential of §3.2, and `finapp.kafka.ssl.truststore-location` names a PEM
file of CA certificates, which is not a secret and needs no password.

The marked local default appears in six files across YAML, Kotlin and SQL, none of which can import a Java constant. It is
single-sourced by enforcement: the build rejects any *other* local default, so the six cannot drift apart.

### 3.2 The inventory: every secret the application reads — *Implemented* (`P15-TSK-018`)

The inventory holds **54** secrets, **28** of them declared by a key class (`ConfinedCredential.KeySpec`). A count here is a claim,
so `SecretInventoryIsCompleteTest` reads this sentence and fails when it is wrong. `PHASE_15_PLAN.md`'s 37 was a survey of names.

**This set is not a search for words.** A search misses a credential called something else and catches what is not one. It is
derived from what the code confines: every class declaring a `KeySpec`, every `@Value` and every `application.yaml` placeholder
whose fallback is the marked local default (the mark that is, by ADR-0020, how the code says "this is a credential"), and every
password the migrator reads. Two nets over that, each held both ways by the same test: **a new credential without a row is red,
and a row nothing reads is red**. The name pattern is the second net, for what no confinement has claimed yet (`pepper`, `hmac`,
`credential`, `jaas`, `sasl`, `auth`, an API key by another spelling), over every key of `application.yaml`, the code's read forms
(`${…}`, `getProperty`, `getRequiredProperty`, `containsProperty`, `bind`, `@ConditionalOnProperty`, a `…_PROPERTY` constant) and
any `FINAPP_…` literal or `getenv` name in any module. §3.4 lists what it matches and is not a secret, and an entry there that
no longer matches anything is red too. What it cannot see is a name built at run time, or passed as a constant none of those forms names.

The columns. *Class* decides the rotation (§5.1). *Owner* is the package of the application that owns the credential: for a key
class, the package that declares it. *Consumer* is the workload, with the code that reads the value. *Delivered through* is
the Secret that workload reads through `envFrom`; `none` means no Secret holds it, deliberately.

| Variable | Property | Class | Owner | Rotation | Consumer | Delivered through |
|---|---|---|---|---|---|---|
| `FINAPP_ABUSE_BUCKET_KEY` | `finapp.abuse.bucket.key` | source-bucket-key | `abuse` | reshuffle-once | `finapp-app` (`AbuseBeans`) | `finapp-app-secrets` |
| `FINAPP_MFA_KEY` | `finapp.mfa.key` | field-encryption-key | `mfa` | key-ring | `finapp-app` (`MfaBeans`) | `finapp-app-secrets` |
| `FINAPP_DOC_KEY` | `finapp.doc.key` | field-encryption-key | `kyc` | key-ring | `finapp-app` (`KycBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_EVIDENCE_KEY` | `finapp.payments.evidence.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEY` | `finapp.payments.dispute.evidence.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_MERCHANT_PAYOUT_EVIDENCE_KEY` | `finapp.merchant.payout.evidence.key` | field-encryption-key | `merchant` | key-ring | `finapp-app` (`MerchantPayoutBeans`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_FILE_KEY` | `finapp.settlement.file.key` | field-encryption-key | `settlement` | key-ring | `finapp-app` (`SettlementBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_EVIDENCE_KEY` | `finapp.fx.evidence.key` | field-encryption-key | `fx` | key-ring | `finapp-app` (`FxProviderBeans`) | `finapp-app-secrets` |
| `FINAPP_CREDIT_EVIDENCE_KEY` | `finapp.credit.evidence.key` | field-encryption-key | `credit` | key-ring | `finapp-app` (`CreditBeans`) | `finapp-app-secrets` |
| `FINAPP_MFA_V2_KEY` | `finapp.mfa.v2.key` | field-encryption-key | `mfa` | key-ring | `finapp-app` (`MfaKey`) | `finapp-app-secrets` |
| `FINAPP_MFA_V3_KEY` | `finapp.mfa.v3.key` | field-encryption-key | `mfa` | key-ring | `finapp-app` (`MfaKey`) | `finapp-app-secrets` |
| `FINAPP_DOC_V2_KEY` | `finapp.doc.v2.key` | field-encryption-key | `kyc` | key-ring | `finapp-app` (`DocumentKey`) | `finapp-app-secrets` |
| `FINAPP_DOC_V3_KEY` | `finapp.doc.v3.key` | field-encryption-key | `kyc` | key-ring | `finapp-app` (`DocumentKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_EVIDENCE_V2_KEY` | `finapp.payments.evidence.v2.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`PaymentEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_EVIDENCE_V3_KEY` | `finapp.payments.evidence.v3.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`PaymentEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_V2_KEY` | `finapp.payments.dispute.evidence.v2.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`DisputeEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_V3_KEY` | `finapp.payments.dispute.evidence.v3.key` | field-encryption-key | `payments` | key-ring | `finapp-app` (`DisputeEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_MERCHANT_PAYOUT_EVIDENCE_V2_KEY` | `finapp.merchant.payout.evidence.v2.key` | field-encryption-key | `merchant` | key-ring | `finapp-app` (`PayoutEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_MERCHANT_PAYOUT_EVIDENCE_V3_KEY` | `finapp.merchant.payout.evidence.v3.key` | field-encryption-key | `merchant` | key-ring | `finapp-app` (`PayoutEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_FILE_V2_KEY` | `finapp.settlement.file.v2.key` | field-encryption-key | `settlement` | key-ring | `finapp-app` (`SettlementFileKey`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_FILE_V3_KEY` | `finapp.settlement.file.v3.key` | field-encryption-key | `settlement` | key-ring | `finapp-app` (`SettlementFileKey`) | `finapp-app-secrets` |
| `FINAPP_FX_EVIDENCE_V2_KEY` | `finapp.fx.evidence.v2.key` | field-encryption-key | `fx` | key-ring | `finapp-app` (`FxEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_FX_EVIDENCE_V3_KEY` | `finapp.fx.evidence.v3.key` | field-encryption-key | `fx` | key-ring | `finapp-app` (`FxEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_CREDIT_EVIDENCE_V2_KEY` | `finapp.credit.evidence.v2.key` | field-encryption-key | `credit` | key-ring | `finapp-app` (`CreditEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_CREDIT_EVIDENCE_V3_KEY` | `finapp.credit.evidence.v3.key` | field-encryption-key | `credit` | key-ring | `finapp-app` (`CreditEvidenceKey`) | `finapp-app-secrets` |
| `FINAPP_KYC_CALLBACK_KEY` | `finapp.kyc.callback.key` | webhook-hmac | `kyc` | two-valid-window | `finapp-app` (`KycBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_WEBHOOK_KEY` | `finapp.payments.webhook.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_INSTANT_WEBHOOK_KEY` | `finapp.payments.instant.webhook.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_WEBHOOK_KEY` | `finapp.fx.webhook.key` | webhook-hmac | `fx` | two-valid-window | `finapp-app` (`FxCoverBeans`) | `finapp-app-secrets` |
| `FINAPP_CORRIDOR_WEBHOOK_KEY` | `finapp.corridor.webhook.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`CrossBorderOutcomeBeans`) | `finapp-app-secrets` |
| `FINAPP_KYC_CALLBACK_PREVIOUS_KEY` | `finapp.kyc.callback.previous.key` | webhook-hmac | `kyc` | two-valid-window | `finapp-app` (`CallbackKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_WEBHOOK_PREVIOUS_KEY` | `finapp.payments.webhook.previous.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`PaymentWebhookKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_INSTANT_WEBHOOK_PREVIOUS_KEY` | `finapp.payments.instant.webhook.previous.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`InstantWebhookKey`) | `finapp-app-secrets` |
| `FINAPP_FX_WEBHOOK_PREVIOUS_KEY` | `finapp.fx.webhook.previous.key` | webhook-hmac | `fx` | two-valid-window | `finapp-app` (`FxWebhookKey`) | `finapp-app-secrets` |
| `FINAPP_CORRIDOR_WEBHOOK_PREVIOUS_KEY` | `finapp.corridor.webhook.previous.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`CorridorWebhookKey`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_PROVIDER_KEY` | `finapp.payments.provider.key` | provider-api | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_INSTANT_KEY` | `finapp.payments.instant.key` | provider-api | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_MERCHANT_PAYOUT_PROVIDER_KEY` | `finapp.merchant.payout.provider.key` | provider-api | `merchant` | two-valid-window | `finapp-app` (`MerchantPayoutBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_REFERENCE_KEY` | `finapp.fx.reference.key` | provider-api | `fx` | two-valid-window | `finapp-app` (`FxReferenceBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_PROVIDER_KEY` | `finapp.fx.provider.key` | provider-api | `fx` | two-valid-window | `finapp-app` (`FxProviderBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_PROVIDER_B_KEY` | `finapp.fx.provider.b.key` | provider-api | `fx` | two-valid-window | `finapp-app` (`FxProviderBeans`) | `finapp-app-secrets` |
| `FINAPP_CORRIDOR_PROVIDER_KEY` | `finapp.corridor.provider.key` | provider-api | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_CORRIDOR_PROVIDER_B_KEY` | `finapp.corridor.provider.b.key` | provider-api | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_PSP_REPORT_KEY` | `finapp.settlement.psp.report.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_SCHEME_REPORT_KEY` | `finapp.settlement.scheme.report.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_PAYOUT_REPORT_KEY` | `finapp.settlement.payout.report.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_SETTLEMENT_BANK_STATEMENT_KEY` | `finapp.settlement.bank.statement.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_FX_REPORT_KEY` | `finapp.fx.report.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_CORRIDOR_REPORT_KEY` | `finapp.corridor.report.key` | report-pull | `settlement` | two-valid-window | `finapp-app` (`SettlementPullBeans`) | `finapp-app-secrets` |
| `FINAPP_DB_APP_PASSWORD` | `spring.datasource.password` | database-fleet-credential | `database` | alternating-users | `finapp-app` (`application.yaml`); bootstrap-step | `finapp-app-secrets` |
| `FINAPP_DB_REPORTING_PASSWORD` | `finapp.database.reporting.password` | database-fleet-credential | `database` | alternating-users | `finapp-app` (`ReportingPool`); bootstrap-step | `finapp-app-secrets` |
| `FINAPP_KAFKA_SASL_PASSWORD` | `finapp.kafka.sasl.password` | kafka-credential | `eventing` | add-roll-remove | `finapp-app` (`EventingBeans`) | `finapp-app-secrets` |
| `FINAPP_DB_MIGRATOR_PASSWORD` | none | database-job-credential | `database` | between-releases | `finapp-migrator` (`MigratorEnvironment`); bootstrap-step | `finapp-migrator-secrets` |
| `FINAPP_DB_MASTER_PASSWORD` | none | database-master | `database` | managed-by-the-database-service | bootstrap-step (`RoleBootstrapMain`) | none |

The test asks Spring, not a string transform, whether each variable reaches its property.

**The bootstrap step and the master credential.** `FINAPP_DB_MASTER_PASSWORD` is delivered through no Secret, on purpose: it is
needed once per database (`DATA_MIGRATIONS.md` §2.2), and a value kept in a cluster Secret between two uses of it is standing
exposure for nothing. **It is the managed database's own**: `modules/postgres` sets `manage_master_user_password`, so RDS generates
it and keeps it in Secrets Manager under a name of RDS's choosing and the environment's `secrets` key, and nothing in this
repository reads it. The person running that step reads it through a role that exists only for that step (`bootstrap_role_enabled`, off by
default, `infra/terraform/modules/security`), into the step's own environment for that run; no workload role may read it. Nothing records that read until
the account has a trail (`THREAT_MODEL.md` §6 item 31). It is the only value that step reads which no workload holds. The
manifest policy refuses it in either Secret.

### 3.3 Delivered beside the secrets, and not secrets

These ride in the same Secret for a reason of their own, stated per row. None of them is sensitive, which is why the kind
generator asks its caller for them rather than generating them.

| Variable | Property | Why it is here | Delivered through |
|---|---|---|---|
| `FINAPP_DB_URL` | `spring.datasource.url` | An endpoint. The manifest policy refuses one in any ConfigMap, in every overlay | `finapp-app-secrets`, `finapp-migrator-secrets` |
| `FINAPP_KAFKA_BOOTSTRAPSERVERS` | `finapp.kafka.bootstrap-servers` | The same, for the broker list | `finapp-app-secrets` |
| `FINAPP_DB_APP_USER` | `spring.datasource.username` | Rotation by alternating users changes the user and the password together, so they have to arrive together | `finapp-app-secrets` |
| `FINAPP_DB_REPORTING_USER` | `finapp.database.reporting.username` | The same | `finapp-app-secrets` |
| `FINAPP_KAFKA_SASL_USERNAME` | `finapp.kafka.sasl.username` | The SCRAM user, read with its password from the one entry the managed brokers read (§3.5) | `finapp-app-secrets` |
| `FINAPP_DB_MIGRATOR_USER` | none | The user beside the migrator's password | `finapp-migrator-secrets` |
| `FINAPP_DB_MASTER_USER` | none | The user beside the master credential, read by the bootstrap step alone | none |

**The provider and report URLs (`finapp.*.url`) are not delivered, and this is a decision.** No real provider exists, so each is absent,
which is what the code reads as a provider not configured. Filling them with a placeholder would be wrong rather than harmless:
`CreditBeans` refuses to start on any non-blank `finapp.credit.bureau.url`, and `CounterpartyScreeningBeans` parses
`finapp.kyc.provider.url` as an address. When a real provider is connected, its URL becomes an entry and a row here.

### 3.4 Names that read like secrets, and are not

Everything §3.2's second net matches that has no row, with the reason. A `key-version` is the number stamped on a row to say
which key encrypted it; the tokenisation entries are an address and a timeout for a service whose whole purpose is keeping card
data out of this platform.

| Property | Why it is not a secret |
|---|---|
| `finapp.credit.evidence.key-version` | The version stamped on what the key encrypts |
| `finapp.doc.key-version` | The version stamped on what the key encrypts |
| `finapp.fx.evidence.key-version` | The version stamped on what the key encrypts |
| `finapp.merchant.payout.evidence.key-version` | The version stamped on what the key encrypts |
| `finapp.mfa.key-version` | The version stamped on what the key encrypts |
| `finapp.payments.dispute.evidence.key-version` | The version stamped on what the key encrypts |
| `finapp.payments.evidence.key-version` | The version stamped on what the key encrypts |
| `finapp.settlement.file.key-version` | The version stamped on what the key encrypts |
| `finapp.merchant.payout-destination.tokenisation.url` | The tokenisation service's address |
| `finapp.merchant.payout-destination.tokenisation.timeout` | A timeout |
| `finapp.paymentmethods.tokenisation.url` | The tokenisation service's address |
| `finapp.paymentmethods.tokenisation.timeout` | A timeout |

### 3.5 How a value reaches a pod, and what was decided — *Implemented as manifests* (`P15-TSK-018`, ADR-0123)

`deploy/components/secret-delivery` holds two `ExternalSecret`s; staging's and production's overlays include it, each with its own
`secret-store.yaml`. The operator is installed from `deploy/operators/external-secrets/values.yaml`, at the digest and version
pinned in `infra/scanner-pins.sh`, which `infra/scripts/check-pinned-images.sh` watches.

| Decision | What is committed | Why, and what was refused |
|---|---|---|
| One `ExternalSecret` per workload, one entry per secret | `finapp-app-secrets` and `finapp-migrator-secrets`, the two Secrets the workloads read through `envFrom` | The operator writes a target in one operation, so no pod can read part of a generation. One `ExternalSecret` per secret would need `Merge` into a single target: thirty-odd independent writers of one object, and a Secret nothing owns |
| Each entry is named | `data`, never `dataFrom` | A `find` over the store would hand a pod whatever else lands under its path. A new entry is a reviewed change |
| When the store is read | `refreshPolicy: OnChange`, `refreshInterval: 0s`: the operator does not poll | Polling would rewrite the Secret on the operator's clock, and a crashed pod restarting afterwards would take the new value while the fleet held the old. **It still reads the store when the object's spec, labels or annotations change, a GitOps controller's tracking metadata included, and when its Secret is missing or altered** (read in its source: the reconcile skips only when nothing changed and the Secret is valid). Each of those can bring a value forward, so each rotation method keeps both valid (§5.1) |
| Deleting an `ExternalSecret` | `creationPolicy: Owner`: its Secret is garbage-collected | Running pods keep their environment; a new pod fails with `CreateContainerConfigError` until the object is restored. `Orphan` was refused because it leaves a credential in etcd that nothing owns |
| A store entry removed, or the store unreachable | `deletionPolicy: Retain` | The Secret keeps its last value and the `ExternalSecret` reports `Ready=False`. This is also what makes a sync all or nothing: under `Delete` or `Merge` the operator skips a missing entry and writes the rest, read from its source at the pinned version. A first sync that fails creates no Secret, so the pod does not start |
| Whose identity reads the store | A `SecretStore` per workload, namespaced, each through its own `…-secret-reader` ServiceAccount that runs no pod; and `finapp-app-kafka-store`, reached through the application's reader, for the one entry the managed brokers name | The application's role cannot read the migrator's entries. No workload may run as a reader and only the readers may carry a role, both refused by the policy, so no pod is given a cloud identity. `ClusterSecretStore` is refused |
| The broker's credential | The two `FINAPP_KAFKA_SASL_…` entries read the properties `username` and `password` of `AmazonMSK_finapp-<environment>-application`, the entry `modules/kafka` creates | The brokers dictate that name and that JSON, so the application reads the same entry. There is no second copy to keep equal |
| The store's address | A placeholder by convention: account `000000000000` and region `placeholder-region-0`, which no cloud serves, as sixty-four zeros are for a digest | No real account or endpoint is committed. The manifest policy refuses any other account number in an ARN, in any field of anything rendered. The entries and roles are `infra/terraform/modules/security`'s. **A real environment takes its role ARNs and region from that root's outputs and applies them as a Kustomize patch held with its own delivery configuration, outside this repository** (`P15-TSK-017`), so that nothing rendered from here ever names one |
| The operator's RBAC | `scopedRBAC` in namespace `finapp`, webhook and certificate controller off, no aggregation into the cluster's view, edit or admin roles | **It is the one non-human principal that writes a Secret**, and it does so in one namespace. `P15-TSK-010` denies humans `get secrets`, and no person is bound to this Role. What was read, once and by hand: this chart at the pinned version, rendered with these values, gives the controller's `Role` and `RoleBinding` in `finapp`, two unbound `view` and `edit` Roles beside them that name no Secret, a leader-election pair in its own namespace, one Deployment, and no `ClusterRole` or `ClusterRoleBinding`. *(Observed on one `kind` cluster, 2026-11-11, `OPERATIONS_RUNBOOK.md` §11.33: its ServiceAccount reads Secrets in `finapp` and in no other namespace, and lists none across the cluster)* |

**A Secret edited or deleted by hand is rewritten from the store's current value at the next reconcile**, which is one more reason
it is not done by hand.

**What failure looks like.** The Secret absent: the pod does not start (`envFrom`, required by the policy; an `optional` reference
would start it with no URL and every marked default). A key missing from it, which only a person can arrange, because the operator
writes every entry or none: the marked local default applies and the guards refuse at startup off loopback, for every credential
whose bean is built at startup. A provider's key is decoded only when that provider's URL is configured, so the application does not
need one with no provider connected, **but the sync does: under `Retain` a store entry that is missing or holds no value fails the
whole sync**, so the `provider-api` and `report-pull` entries need a generated value even for a provider that does not exist yet.

**What has been observed on a cluster** *(2026-11-11, one `kind` cluster, the operator from its pinned chart and image with these values, reading a store the rehearsal generates per run through the operator's `fake` provider; `OPERATIONS_RUNBOOK.md` §11.33)*: each `ExternalSecret` wrote its Secret once, owned by it, with exactly the store's values, and did not write it again unprompted; a value changed in the store changed nothing until `finapp/rotation-generation` moved, and then the Secret was written once; a pod started between that sync and the roll signed in with the new database credential while every pod not yet rolled kept the old, and the roll carried it to every replica; deleting the migrator's `ExternalSecret` removed its Secret, and restoring it wrote the Secret again; the operator's ServiceAccount reads Secrets in `finapp` and in no other namespace. Not observed: a cloud store's access control, versions or audit, a store that stops answering (`Retain`), a GitOps controller's tracking metadata.

**Ten replicas.** Each reads the Secret once, at its own start. No replica decides anything about it. Two operator replicas elect a
leader, and the API server's optimistic concurrency is what actually arbitrates the one write.

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

The broker's credential is confined the same way (`P15-TSK-026`): `KafkaTransportGuard` refuses to
start when the SCRAM password is the marked local default and any bootstrap server is off this
machine, naming `FINAPP_KAFKA_SASL_PASSWORD`; off this machine it also requires `SASL_SSL` with a
credential at all (`SECURITY_ARCHITECTURE.md` §Transport encryption).

Since the same task `CommittedConfigurationHoldsNoSecretTest` also reads a credential **inside** a
value: Kafka's SASL login is one property whose value carries the password
(`... ScramLoginModule required username="u" password="p";`), and a reading that stops at the first
assignment on a line sees only the innocent key in front of it.

## 5. Adding a secret, and rotating one

1. **Do not put the value in a file in this repository.** There is no encrypted-in-git scheme, on
   purpose: ADR-0020 explains why ciphertext in permanent history is the wrong trade.
2. Read it from an environment variable. If it needs a local default, use the marked one — the
   build rejects any other.
3. If it is held in Java, hold it in `Sensitive<T>`. The build requires this for any field or
   accessor whose name says it is a secret (ADR-0019).
4. Give it a name that says what it is. If `secretsAreWrapped` rejects the name, that is the rule
   working — wrap the value, or rename the field because it does not actually hold one. **Do not
   add an exemption**: the rule has none, and the first one is where default-deny ends.
5. **Give it a row in §3.2.** `SecretInventoryIsCompleteTest` fails until you do.
6. **Add its entry to the `ExternalSecret` of the workload that reads it**, under
   `deploy/components/secret-delivery`, with the store's entry of the same name. The same test fails until the two agree. Only
   the workload that reads it gets it: the migrator's Secret holds exactly the URL, its user and its password.
7. **The store's entry in each environment**, `finapp/<environment>/<app|migrator>/<VARIABLE>`, follows from the row and the
   `ExternalSecret`, with nothing else to edit: `infra/terraform/modules/security` reads both at plan, compares them, and refuses a
   mismatch. It creates the entry without a value. **Then give it a value** (`OPERATIONS_RUNBOOK.md` §11.7), a generated one if no
   provider exists yet: a sync reads every entry, so one missing or empty fails the sync and leaves the last good Secret in place. Two exceptions. The Kafka pair has no entry of its own: both read
   `AmazonMSK_finapp-<environment>-application`, which `modules/kafka` creates, and it must not be created again. The master
   credential is RDS's (§3.2) and is under no path of ours.
8. Run the build, and `infra/scripts/manifest-check.sh`. The configuration rule and the inventory run in `./gradlew build`; the
   manifest policy needs Docker.

### 5.1 Rotation, per class

A rotation changes a value for a fleet that is, for the length of a rolling update, running both. So each method keeps both valid
until the last old replica has gone, and the class decides how.

| Class | Rotation | How | Today |
|---|---|---|---|
| `field-encryption-key` | key-ring | A new version for new rows, retained versions for decryption, activated only once every replica holds it; `INSERT`-only evidence is never re-encrypted | **Built (`P15-TSK-019`).** Each of the eight keys is a ring: its base key and up to two further versions (`…_V2_KEY`, `…_V3_KEY`), each entry filled from the start, as a sync requires. `ring.versions` lists what a replica holds and it refuses to start without any of them; `ring.active-version` is the pointer, a separate change; `OPERATIONS_RUNBOOK.md` section 11.29 is the procedure. Absent any of this configuration nothing changes: the base key alone, as before. *(Since 2026-11-11:)* each held version's **check value** is published in `ring.check-values`, required for every one once two are listed, and a replica whose stored value for a version does not give it refuses to start; CI refuses a change to a listed version's. **Rehearsed on `kind`** (`OPERATIONS_RUNBOOK.md` §11.33): the MFA ring's two-step and its rollback, and a replaced value refused at a replica's start |
| `webhook-hmac` | two-valid-window | Verify against the current and the previous secret during a window, then retire the previous | **Built (`P15-TSK-019`).** Each of the five doors reads a previous key and an instant it stops being accepted: both must be set to open a window, and it closes itself at the instant, with nothing restarted. The instant may lie at most seven days after a replica starts, past which that replica refuses to start. `OPERATIONS_RUNBOOK.md` section 11.29 is the procedure. **Rehearsed on `kind`** (§11.33): the payment webhook door's window through two rolls with the provider still signing with the old secret, the window closing itself at its instant, and the rollback |
| `provider-api` | two-valid-window | The provider issues a second credential; store, sync, roll, then retire the first | Needs a real provider. Not rehearsed: the simulated provider verifies no credential, so a rotation against it would pass whatever was sent (`THREAT_MODEL.md` §6 item 36 (c)) |
| `report-pull` | two-valid-window | As above, for a report or statement source | Needs a real provider |
| `database-fleet-credential` | alternating-users | A second user holding exactly the first's grants; the store takes the user and the password together; sync, roll; the old user is retired once no replica uses it | **Built.** Two users per role, `finapp_app_a`/`_b` and `finapp_reporting_a`/`_b`, each holding its carrier's grants by membership alone, created by the bootstrap step with no password (`RoleBootstrap`): a rotation gives the inactive one a password, as a SCRAM verifier, and moves the store to it with its user; the old user is retired by removing its password. **Rehearsed on `kind`** (§11.33), with its rollback |
| `database-job-credential` | between-releases | The migrator runs as a Job per release and reads its Secret at its start. Change the role's password and the store between two releases, then sync; nothing needs rolling | Ready. The next Job takes it. Not rehearsed on `kind` (`THREAT_MODEL.md` §6 item 36 (d)) |
| `database-master` | managed-by-the-database-service | The managed database's own mechanism, recovered through the cloud account. The bootstrap step reads it for its run | The environment's |
| `source-bucket-key` | reshuffle-once | One value for every replica, so it cannot be changed one replica at a time without a source counting once per key while both are on the fleet. Change the store, sync, roll. The buckets reshuffle once: each source starts a fresh window in a new bucket, so the rotation costs at most one window's budget and nothing is unreadable | Ready. Nothing is signed or encrypted with it, so no earlier row needs it. Not rehearsed on `kind` (`THREAT_MODEL.md` §6 item 36 (d)) |
| `kafka-credential` | add-roll-remove | The brokers' ACLs name a SCRAM user, so the new credential is a second user with its grants (one more of `modules/kafka`'s `scram_users` and its own `AmazonMSK_…` entry). Point the two entries at it, sync, roll, then remove the old user | **Built** (`modules/kafka`'s `scram_user_slots`, staged per user: the new user's entry alone, to be filled; then associated with exactly the principal's ACLs, before the roll; then the old user removed, its entry deleted without a recovery window so that the name can come back at the next rotation; the module's README has the four applies; the application's reader may read the second entry). **Rehearsed on `kind`** against a test-only broker (§11.33), with its rollback; never on MSK: the staging and the recovery window are asserted over plans against mock providers |

### 5.2 What carries a rotation to the fleet — *Implemented as manifests; rehearsed on `kind`* (`OPERATIONS_RUNBOOK.md` §11.33)

**A rotation does not reach a process by itself.** A value read through `envFrom` is fixed when the container starts. A new value in
the store changes nothing in the cluster; a new value in the Secret changes nothing in a running pod. It takes three steps, and
each is an act somebody performs:

1. **Write the new version to the store.** Nothing else moves, by design: the operator is not polling.
2. **Sync.** A commit changes `finapp/rotation-generation` on that environment's `ExternalSecret`, by a patch in **its overlay**. The
   annotation's default is in the shared component, and editing it there would rotate every environment at once. Changing the object
   makes the operator read the store and rewrite the Secret once. From here, a replica that starts for any reason reads the new value, so the class's method
   (§5.1) must already accept both.
3. **Read that it worked, then roll.** `kubectl get externalsecret -n finapp` must show `Ready=True` with a refresh time after the
   commit in step 2. The kubelet's copy of a Secret can lag its write, which is the other reason this is two commits and not one.
   Then a second commit changes `finapp/rotation-generation` on the pod template, again by a patch in that overlay and not in
   `deploy/base/deployment.yaml`. That changes the pod template, and that, and nothing else, starts the rolling update, one surge at a time under the
   strategy `P15-TSK-009` set.

The manifest policy refuses a pod template whose generation is ahead of its `ExternalSecret`'s: a rollout before the sync would
restart the fleet onto the value it already had. It cannot tell that the two were in separate commits; the review of the change
does that. The migrator is not rolled: its next Job reads the Secret as it then stands. The delivery order between
the two (`ExternalSecret` first, Deployment after it) is `P15-TSK-017`'s to enforce once a GitOps controller applies these.

**To go back:** make the previous version current in the store and repeat steps 2 and 3. Nothing here is undone by editing a Secret.

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

This is how the clause is verified, and it remains the only safe way to verify it even now that
CI runs.

### The allowlist, and why it is one literal — *Implemented (2026-09-04)*

`P0-TSK-042` added a git remote, and the first CI run scanned **this repository's own 119
commits** — something no run had ever done, because the teeth of the scan were deliberately proven
against a throwaway clone instead. It produced exactly one finding, and it was a **false
positive**: `generic-api-key` fired on

```java
private static final String A_KEY = "<a UUID>";
```

a fixture idempotency key in `IdempotencyKeyHeaderTest`, shaped like the values real clients send.
The rule is an entropy-and-name detector, and `A_KEY` is a name it looks for.

**An idempotency key is not a secret**, and that is not a convenience invented to clear a build:
ADR-0019 settled it when choosing the vocabulary `secretsAreWrapped` matches on, and deliberately
left `key` out of it, because a rule with false positives is a rule somebody turns off. The same
reasoning applies to the scanner.

[`.gitleaks.toml`](../../.gitleaks.toml) therefore allowlists **the exact literal and nothing
else** — not the rule, which stays enabled everywhere; not the file; and not "UUIDs in test
sources", which would mask a genuine UUID-shaped API key committed into a test, a classic
real-world leak rather than a hypothetical one.

**Proven narrow, not asserted narrow.** A random high-entropy credential planted in the *same
file*, on the *same line*, under the *same rule*, in a throwaway clone, still fails with exit 1.
The first attempt at that demonstration used `AKIAIOSFODNN7EXAMPLE` and reported a pass — because
that is AWS's own documentation key and gitleaks allowlists it by default. The mutation had never
landed. Checking that a mutation actually applies is part of applying one.

## 7. What is deliberately not here

*(Corrected by `P15-TSK-001`, 2026-11-08, from the code. This table said key rotation "needs keys, of which there are
none", the Phase 0 position. Since `P1-TSK-017` the platform has held field-level encryption keys: eight today. It also
placed at-rest encryption with `P0-TSK-034`, which documented the transport hop and stated that nothing was encrypted
at rest.)*

| | Position today | Owner |
|---|---|---|
| A secrets manager | **Delivery is built as manifests, and the store's entries and keys as Terraform; no store exists.** The inventory, the two ExternalSecrets and the operator's values are §3, checked over the manifests. The entries, one per row delivered, are planned by `infra/terraform/modules/security` against a mock provider; nothing has run against a cloud store. The operator and four rotations of §5.1 (a field key, a callback secret, the fleet's database credential and the broker's) have run on one `kind` cluster against a store generated per run (§3.5, `OPERATIONS_RUNBOOK.md` §11.33); a provider's and a report source's credential, the migrator's, the master credential and the source-bucket key have not (`THREAT_MODEL.md` §6 item 36 (c) and (d)). The local fallback stays ADR-0020's marked default confined to loopback (§4). The decision is ADR-0123 (`Proposed`): a managed secret store and KMS per environment, delivered as the environment the application already reads | `infra/terraform/modules/security`, planned and never applied; a cloud store when an account exists |
| Key rotation, KMS, envelope encryption | **Rings exist; KMS does not.** Eight AES-256-GCM field-level keys, used by eight cipher classes built as nine instances (`EvidenceCipher` twice: payment and dispute evidence, two keys, so two rings; `DocumentCipher` and `CounterpartySubjectCipher` share `finapp.doc.key`, so one ring). Each key is a ring: an active version writes, retained versions decrypt only the rows stamped with them, and a version the ring does not hold is refused. Each version is an environment value decoding to exactly 32 bytes; `…key-version` is the base key's stamped number. No KMS key exists in any account: each field-level key's entry is planned under a KMS key of its own (`field_keys`, `infra/terraform/modules/security`), and nothing has rotated in a deployment. `SECURITY_ARCHITECTURE.md` §Encryption at rest lists each key and what it encrypts. Each held version carries a published check value a replica refuses to start without matching, and the MFA ring's rotation and rollback have run on one `kind` cluster (`OPERATIONS_RUNBOOK.md` §11.33) | Built (`P15-TSK-019`, the check value `P15-TSK-020`); a deployment's first rotation, a ring of one version with no check value, and an upgrade that meets two versions listed without them: `THREAT_MODEL.md` §6 item 36 (b) and (e) |
| Callback (HMAC) secret rotation | Five callback doors, each verifying against its current key and, during a bounded window, its previous one (§5.1). The payment webhook door's secret has rotated through its window on one `kind` cluster, with its rollback (`OPERATIONS_RUNBOOK.md` §11.33); nothing has rotated in a deployment | Built (`P15-TSK-019`), rehearsed (`P15-TSK-020`); a provider's own API credential: `THREAT_MODEL.md` §6 item 36 (c) |
| Transport encryption | Implemented for the hops that exist (`SECURITY_ARCHITECTURE.md` §Transport encryption) | `P0-TSK-034`; Kafka's TLS and SASL `P15-TSK-026` (built: `SASL_SSL` with SCRAM-SHA-512 required off loopback; the managed broker's side configured by `P15-TSK-015`, never applied); inbound TLS at the edge `P15-TSK-011` |
| At-rest encryption | The field-level ciphers above. Volumes, brokers and buckets are configured to encrypt under customer-managed keys, and nothing is deployed, so none of it holds yet. Each key is one of `infra/terraform/modules/security`'s, planned and never created | `P15-TSK-015`; a backup under a separate key: `THREAT_MODEL.md` §6 item 23 |
| Per-service identity, mTLS | Needs services: there is one deployable, so there is no service-to-service hop (`PHASE_15_PLAN.md` §9.3). Workload identities exist for the cloud APIs only, planned in `infra/terraform/modules/security` | mTLS Phase 16, if a service is extracted |
| Dependency artefact verification | A dependency is external input too, but a different supply chain | `P0-TSK-039` (implemented: ADR-0025) |

The threats these rows answer are registered in [`THREAT_MODEL.md`](THREAT_MODEL.md): T6 (secret disclosure), T7 and
T34 (the field-level keys), and T8 (the callback secrets).
