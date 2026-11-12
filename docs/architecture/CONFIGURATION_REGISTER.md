# Configuration Register

Every `finapp.*` property the application reads and every Spring property an overlay sets: its class, its default, and where each
environment gets it (`P15-TSK-031`, ADR-0125 decision 3). An environment differs from another by values and never by a code path:
no Spring profile exists, and `NoSpringProfileDecidesBehaviourTest` holds that.

**The test is the contract, and it reads the code.** `ConfigurationRegisterIsCompleteTest` derives the set of names from what
`@Value`, `@ConditionalOnProperty`, `Environment`, `Binder` and `application.yaml` really read, and holds this table to it both ways:
an unregistered property fails the build, and so does a row nothing reads. It holds each row's default against the code's, each
variable against what Spring binds it to, and each environment's source against that environment's files.

**Adding a property is one line in the table below**, in any position. Nothing here is a compliance statement, and no value below has
run against a real environment.

## 1. The classes

- **`SECRET`**: from the secret store, never in an overlay. **This class is [`SECRET_MANAGEMENT.md`](SECRET_MANAGEMENT.md) section 3.2
  and is not repeated here**: a copy would be a second list that could only disagree. A row below may not carry this class or name one
  of that section's properties, and the test holds that. Each such secret's sources are the same: the marked local default on a
  developer's machine, confined to loopback; test-only values under test; generated per run on `kind`
  (`infra/scripts/kind-secrets.sh`); the store through the two ExternalSecrets in staging and production.
- **`FINANCIAL`**: it changes a financial outcome, limit, price, route or risk judgement. When in doubt a property is classed here,
  because over-classing costs a sentence and under-classing hides a decision from the fingerprint. Each row says why replicas holding
  different values during a rollout is safe, read from how the value is used. **Two people on every change is ADR-0122's decision and
  is not built yet**: `P15-TSK-017` delivers it. What exists today is a pull request, and section 1.1 says which.
- **`OPERATIONAL`**: anything else: pacing, batch sizes, timeouts, leases, switches, addresses that are not a route.

A value that decides money and is **not** safe mixed belongs in versioned database data, not here (section 4).

`finapp.config.fingerprint` is a SHA-256 over the effective `FINANCIAL` values: the property's value where one is set, the default
below where none is. Each replica publishes it as a label, so `ConfigFingerprintDisagrees` can count distinct configurations across the
fleet. No value leaves the application. Changing the class of a row changes the digest's inputs, so it rolls out like a value does.
`OPERATIONS_RUNBOOK.md` section 11.31 is how to change one.

### 1.1 How a `FINANCIAL` value changes

Three paths, none with its second person yet (`P15-TSK-017`'s `CODEOWNERS`; the branch rules are the owner's to apply).

| Rows | Path | What a reviewer sees |
|---|---|---|
| Any that is not a URL, by an overlay | A literal in the environment's overlay, and that environment's cell here set to `overlay` | The overlay and this file |
| Any that is not a URL, by its default | The code's default and this table's `Default` cell, in one pull request: `everyDefaultIsTheCodes` fails otherwise | The code and this file. A `CODEOWNERS` on the overlay alone would not see it, so it has to cover this file too |
| The `.url` rows | Never an overlay literal: the manifest policy refuses a `_URL` key. A real provider's URL becomes an entry of `deploy/components/secret-delivery/externalsecret-app.yaml` and a row of `SECRET_MANAGEMENT.md` section 3.3, and this table's cell becomes `store` | That pull request. **The value itself then changes in the store**, outside every pull request, under the store's own access control and audit |

## 2. Reading a row

**Default**: what the code uses when nothing sets the property, as it appears in the code. `(none)`: nothing, the feature it switches
is off or the value is required where read. `(blank)`: the empty string. `(loopback)`: an address on this machine, which is what the
code's own default is and all that is said here, because no endpoint is written in a document.

**The five sources.**

| Word | Meaning |
|---|---|
| `default` | Nothing sets it in this environment, so the default applies |
| `overlay` | The environment's committed overlay (or the base it merges) sets it as a ConfigMap literal; in `test`, `app/src/test/resources/application.properties` |
| `store` | The secret store delivers it, through `deploy/components/secret-delivery` |
| `generated` | On `kind` only: `infra/scripts/kind-secrets.sh` writes it per run into the same Secret |
| `pod` | The Deployment states it itself, from the downward API |

`local` is `compose.yaml`'s dependencies with the application on the host, and nothing but the defaults reaches it. `test` is
Testcontainers with the test overlay. `ci` is the `kind` overlay. `staging` and `production` are the managed environments.

The provider and report URLs are absent in every environment until a real provider exists (`SECRET_MANAGEMENT.md` section 3.3). A
placeholder value there is unsafe rather than harmless: `CreditBeans` refuses any non-blank `finapp.credit.bureau.url` at start.

## 3. The register

| Property | Variable | Class | Default | local | test | ci | staging | production | A mixed fleet |
|---|---|---|---|---|---|---|---|---|---|
| `finapp.abuse.channel-verification.limit` | `FINAPP_ABUSE_CHANNEL_VERIFICATION_LIMIT` | OPERATIONAL | `20` | default | overlay | default | default | default | - |
| `finapp.abuse.channel-verification.window` | `FINAPP_ABUSE_CHANNEL_VERIFICATION_WINDOW` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.abuse.max-concurrent-counts` | `FINAPP_ABUSE_MAX_CONCURRENT_COUNTS` | OPERATIONAL | `3` | default | overlay | default | default | default | - |
| `finapp.abuse.permit-wait` | `FINAPP_ABUSE_PERMIT_WAIT` | OPERATIONAL | `PT0.1S` | default | default | default | default | default | - |
| `finapp.abuse.recovery-completion.limit` | `FINAPP_ABUSE_RECOVERY_COMPLETION_LIMIT` | OPERATIONAL | `20` | default | overlay | default | default | default | - |
| `finapp.abuse.recovery-completion.window` | `FINAPP_ABUSE_RECOVERY_COMPLETION_WINDOW` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.abuse.recovery.limit` | `FINAPP_ABUSE_RECOVERY_LIMIT` | OPERATIONAL | `10` | default | overlay | default | default | default | - |
| `finapp.abuse.recovery.site-limit` | `FINAPP_ABUSE_RECOVERY_SITE_LIMIT` | OPERATIONAL | `100` | default | overlay | default | default | default | - |
| `finapp.abuse.recovery.window` | `FINAPP_ABUSE_RECOVERY_WINDOW` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.abuse.registration.limit` | `FINAPP_ABUSE_REGISTRATION_LIMIT` | OPERATIONAL | `10` | default | overlay | overlay | default | default | - |
| `finapp.abuse.registration.site-limit` | `FINAPP_ABUSE_REGISTRATION_SITE_LIMIT` | OPERATIONAL | `100` | default | overlay | overlay | default | default | - |
| `finapp.abuse.registration.window` | `FINAPP_ABUSE_REGISTRATION_WINDOW` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.abuse.sign-in.limit` | `FINAPP_ABUSE_SIGN_IN_LIMIT` | OPERATIONAL | `100` | default | overlay | overlay | default | default | - |
| `finapp.abuse.sign-in.site-limit` | `FINAPP_ABUSE_SIGN_IN_SITE_LIMIT` | OPERATIONAL | `1000` | default | overlay | overlay | default | default | - |
| `finapp.abuse.sign-in.window` | `FINAPP_ABUSE_SIGN_IN_WINDOW` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.abuse.sweep.batch-size` | `FINAPP_ABUSE_SWEEP_BATCHSIZE` | OPERATIONAL | `1000` | default | default | default | default | default | - |
| `finapp.abuse.sweep.grace` | `FINAPP_ABUSE_SWEEP_GRACE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.abuse.sweep.interval` | `FINAPP_ABUSE_SWEEP_INTERVAL` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.accounting.adjustment.standing-after` | `FINAPP_ACCOUNTING_ADJUSTMENT_STANDINGAFTER` | OPERATIONAL | `P7D` | default | default | default | default | default | - |
| `finapp.accounting.break.aged-after` | `FINAPP_ACCOUNTING_BREAK_AGEDAFTER` | OPERATIONAL | `P7D` | default | default | default | default | default | - |
| `finapp.accounting.checks.statement-timeout` | `FINAPP_ACCOUNTING_CHECKS_STATEMENTTIMEOUT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.close.checks-batch` | `FINAPP_ACCOUNTING_CLOSE_CHECKSBATCH` | OPERATIONAL | `18` | default | default | default | default | default | - |
| `finapp.accounting.close.checks-poll` | `FINAPP_ACCOUNTING_CLOSE_CHECKSPOLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.close.deferral` | `FINAPP_ACCOUNTING_CLOSE_DEFERRAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.close.inquiry-poll` | `FINAPP_ACCOUNTING_CLOSE_INQUIRYPOLL` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.accounting.close.lease` | `FINAPP_ACCOUNTING_CLOSE_LEASE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.accounting.close.max-reclaims` | `FINAPP_ACCOUNTING_CLOSE_MAXRECLAIMS` | OPERATIONAL | `3` | default | default | default | default | default | - |
| `finapp.accounting.close.max-run` | `FINAPP_ACCOUNTING_CLOSE_MAXRUN` | OPERATIONAL | `PT2H` | default | default | default | default | default | - |
| `finapp.accounting.close.open-poll` | `FINAPP_ACCOUNTING_CLOSE_OPENPOLL` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.accounting.close.orphans-batch` | `FINAPP_ACCOUNTING_CLOSE_ORPHANSBATCH` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.accounting.close.orphans-poll` | `FINAPP_ACCOUNTING_CLOSE_ORPHANSPOLL` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.accounting.close.renew-every` | `FINAPP_ACCOUNTING_CLOSE_RENEWEVERY` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.close.run-stalled-polls` | `FINAPP_ACCOUNTING_CLOSE_RUNSTALLEDPOLLS` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.accounting.gauges.statement-timeout` | `FINAPP_ACCOUNTING_GAUGES_STATEMENTTIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.accounting.gl.statement-timeout` | `FINAPP_ACCOUNTING_GL_STATEMENTTIMEOUT` | OPERATIONAL | `PT10S` | default | default | default | default | default | - |
| `finapp.accounting.period.lock-grace-business-days` | `FINAPP_ACCOUNTING_PERIOD_LOCKGRACEBUSINESSDAYS` | FINANCIAL | `2` | default | default | default | default | default | Decides only the first day a period may lock (`LockGrace`). The lock is one transition on the locked period row, so a fleet of two values still locks once, and the earlier day applies for at most a rollout. The constructor refuses any value under the two business days that keep a western zone's today out of a lock, so neither value can cover a posting still being made. |
| `finapp.accounting.regulatory.answer-horizon` | `FINAPP_ACCOUNTING_REGULATORY_ANSWERHORIZON` | OPERATIONAL | `P1D` | default | default | default | default | default | - |
| `finapp.accounting.regulatory.inquiry-interval` | `FINAPP_ACCOUNTING_REGULATORY_INQUIRYINTERVAL` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.accounting.regulatory.lease` | `FINAPP_ACCOUNTING_REGULATORY_LEASE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.accounting.regulatory.simulation` | `FINAPP_ACCOUNTING_REGULATORY_SIMULATION` | FINANCIAL | `ACKNOWLEDGE` | default | default | default | default | default | Scripts the simulated regulator, the only one that exists. Each answer is recorded once on the row it concerns, under that row's own claim, so a mix records answers of either script, each complete. No overlay sets it. |
| `finapp.accounting.regulatory.submissions-per-tick` | `FINAPP_ACCOUNTING_REGULATORY_SUBMISSIONSPERTICK` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.accounting.report.generation-timeout` | `FINAPP_ACCOUNTING_REPORT_GENERATIONTIMEOUT` | OPERATIONAL | `PT4M` | default | default | default | default | default | - |
| `finapp.accounting.report.lease` | `FINAPP_ACCOUNTING_REPORT_LEASE` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.accounting.report.max-attempts` | `FINAPP_ACCOUNTING_REPORT_MAXATTEMPTS` | OPERATIONAL | `5` | default | default | default | default | default | - |
| `finapp.accounting.report.operational-permits` | `FINAPP_ACCOUNTING_REPORT_OPERATIONALPERMITS` | OPERATIONAL | `2` | default | default | default | default | default | - |
| `finapp.accounting.report.operational-timeout` | `FINAPP_ACCOUNTING_REPORT_OPERATIONALTIMEOUT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.report.provisional-freshness` | `FINAPP_ACCOUNTING_REPORT_PROVISIONALFRESHNESS` | FINANCIAL | `PT15M` | default | default | overlay | default | default | Decides only whether a `PROVISIONAL` run is reused or run again. A provisional report is labelled with its own cut and is never an input to a posting, a close or a `FINAL` return, so a replica with the shorter bound runs once more and one with the longer serves an older cut that says so. |
| `finapp.accounting.source-census.batch` | `FINAPP_ACCOUNTING_SOURCECENSUS_BATCH` | OPERATIONAL | `200` | default | default | default | default | default | - |
| `finapp.accounting.source-census.enabled` | `FINAPP_ACCOUNTING_SOURCECENSUS_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.accounting.source-census.interval` | `FINAPP_ACCOUNTING_SOURCECENSUS_INTERVAL` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.accounting.source-census.recent` | `FINAPP_ACCOUNTING_SOURCECENSUS_RECENT` | OPERATIONAL | `P30D` | default | default | default | default | default | - |
| `finapp.accounting.source-census.stale-after` | `FINAPP_ACCOUNTING_SOURCECENSUS_STALEAFTER` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.accounting.source-census.standing` | `FINAPP_ACCOUNTING_SOURCECENSUS_STANDING` | OPERATIONAL | `PT24H` | default | default | default | default | default | - |
| `finapp.accounting.source-census.statement-timeout` | `FINAPP_ACCOUNTING_SOURCECENSUS_STATEMENTTIMEOUT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.accounting.sweeper.enabled` | `FINAPP_ACCOUNTING_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.accounting.valuation.max-age-hours` | `FINAPP_ACCOUNTING_VALUATION_MAXAGEHOURS` | FINANCIAL | `96` | default | default | default | default | default | Judged once per rate set against the period's end, which is data, and stored on the set (`JdbcValuationRateSetStore`), immutable from that judgement. Two replicas may capture under different bounds and each set still states the bound that admitted it, and a set is used only once it is approved. |
| `finapp.api.max-request-bytes` | `FINAPP_API_MAXREQUESTBYTES` | OPERATIONAL | `1048576` | default | default | default | default | default | - |
| `finapp.api.settlement-upload-max-request-bytes` | `FINAPP_API_SETTLEMENTUPLOADMAXREQUESTBYTES` | OPERATIONAL | `12582912` | default | default | default | default | default | - |
| `finapp.bnpl.dispute.batch` | `FINAPP_BNPL_DISPUTE_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.bnpl.dispute.poll` | `FINAPP_BNPL_DISPUTE_POLL` | OPERATIONAL | `PT60S` | default | default | default | default | default | - |
| `finapp.bnpl.dispute.retry` | `FINAPP_BNPL_DISPUTE_RETRY` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.bnpl.dispute.sweeper.enabled` | `FINAPP_BNPL_DISPUTE_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.bnpl.origination.batch` | `FINAPP_BNPL_ORIGINATION_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.bnpl.origination.permit` | `FINAPP_BNPL_ORIGINATION_PERMIT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.bnpl.origination.poll` | `FINAPP_BNPL_ORIGINATION_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.bnpl.origination.sweeper.enabled` | `FINAPP_BNPL_ORIGINATION_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.bnpl.proofs.refresh` | `FINAPP_BNPL_PROOFS_REFRESH` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.bnpl.servicing.batch` | `FINAPP_BNPL_SERVICING_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.bnpl.servicing.poll` | `FINAPP_BNPL_SERVICING_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.bnpl.servicing.retry` | `FINAPP_BNPL_SERVICING_RETRY` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.bnpl.servicing.sweeper.enabled` | `FINAPP_BNPL_SERVICING_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.checkout.sweeper.batch` | `FINAPP_CHECKOUT_SWEEPER_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.checkout.sweeper.enabled` | `FINAPP_CHECKOUT_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.checkout.sweeper.payment-grace` | `FINAPP_CHECKOUT_SWEEPER_PAYMENTGRACE` | FINANCIAL | `PT10M` | default | default | default | default | default | Decides when a `PAYMENT_PENDING` session past its deadline is expired, judged on the session's own row by the sweep that locks it. The shorter value applies for at most a rollout, and a payment completing after that expiry is the counted `COMPLETED_LATE` path, neither lost nor doubled. |
| `finapp.checkout.sweeper.poll-interval` | `FINAPP_CHECKOUT_SWEEPER_POLLINTERVAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.corridor.provider.b.timeout` | `FINAPP_CORRIDOR_PROVIDER_B_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.corridor.provider.b.url` | `FINAPP_CORRIDOR_PROVIDER_B_URL` | FINANCIAL | (none) | default | default | default | default | default | A second provider, chosen by the routing the code holds. Set on one replica and not another, the replica without it simply has one provider fewer to offer, and a command it cannot route is refused and retried. Absent in every environment until a real provider exists. |
| `finapp.corridor.provider.timeout` | `FINAPP_CORRIDOR_PROVIDER_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.corridor.provider.url` | `FINAPP_CORRIDOR_PROVIDER_URL` | FINANCIAL | (none) | default | default | default | default | default | A rail exists on a replica exactly when this is set. Set on one replica and not another, a command reaching the one without it is refused and retried, and a callback there is refused and redelivered, so nothing moves on half a configuration, and the provider's idempotency key covers a retry that reaches the other replica. Absent in every environment until a real provider exists. |
| `finapp.corridor.report.url` | `FINAPP_CORRIDOR_REPORT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.corridor.webhook.previous.valid-until` | `FINAPP_CORRIDOR_WEBHOOK_PREVIOUS_VALIDUNTIL` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.corridor.webhook.tolerance` | `FINAPP_CORRIDOR_WEBHOOK_TOLERANCE` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.credit.bureau.collection-window` | `FINAPP_CREDIT_BUREAU_COLLECTIONWINDOW` | FINANCIAL | `PT30M` | default | default | default | default | default | Written onto each request row when the request is created (`JdbcCreditDataRequestStore`), and the request is judged by its own row from then on. A rollout changes only the requests it creates, and each row states its own window. |
| `finapp.credit.bureau.disabled` | `FINAPP_CREDIT_BUREAU_DISABLED` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditSourceOrder`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.bureau.providers` | `FINAPP_CREDIT_BUREAU_PROVIDERS` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditSourceOrder`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.bureau.retry-cadence` | `FINAPP_CREDIT_BUREAU_RETRYCADENCE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.credit.bureau.url` | `FINAPP_CREDIT_BUREAU_URL` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditBeans`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.data.retry.batch` | `FINAPP_CREDIT_DATA_RETRY_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.credit.data.retry.poll` | `FINAPP_CREDIT_DATA_RETRY_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.credit.data.retry.sweeper.enabled` | `FINAPP_CREDIT_DATA_RETRY_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.credit.evidence.key-version` | `FINAPP_CREDIT_EVIDENCE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.credit.evidence.ring.active-version` | `FINAPP_CREDIT_EVIDENCE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.credit.evidence.ring.check-values` | `FINAPP_CREDIT_EVIDENCE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.credit.evidence.ring.retired-versions` | `FINAPP_CREDIT_EVIDENCE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.credit.evidence.ring.versions` | `FINAPP_CREDIT_EVIDENCE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.credit.findata.collection-window` | `FINAPP_CREDIT_FINDATA_COLLECTIONWINDOW` | FINANCIAL | `PT30M` | default | default | default | default | default | Written onto each request row when the request is created (`JdbcCreditDataRequestStore`), and the request is judged by its own row from then on. A rollout changes only the requests it creates, and each row states its own window. |
| `finapp.credit.findata.disabled` | `FINAPP_CREDIT_FINDATA_DISABLED` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditSourceOrder`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.findata.providers` | `FINAPP_CREDIT_FINDATA_PROVIDERS` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditSourceOrder`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.findata.retry-cadence` | `FINAPP_CREDIT_FINDATA_RETRYCADENCE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.credit.findata.url` | `FINAPP_CREDIT_FINDATA_URL` | FINANCIAL | (blank) | default | default | default | default | default | Refused at start while non-blank (`CreditBeans`), so blank is the only value a replica can run with and a fleet cannot hold two. |
| `finapp.credit.progress.batch` | `FINAPP_CREDIT_PROGRESS_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.credit.progress.permit` | `FINAPP_CREDIT_PROGRESS_PERMIT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.credit.progress.poll` | `FINAPP_CREDIT_PROGRESS_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.credit.progress.sweeper.enabled` | `FINAPP_CREDIT_PROGRESS_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.database.instances` | `FINAPP_DB_INSTANCES` | OPERATIONAL | `10` | default | default | overlay | overlay | overlay | - |
| `finapp.database.reporting.pool-size` | `FINAPP_DB_REPORTING_POOL_SIZE` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.database.reporting.username` | `FINAPP_DB_REPORTING_USER` | OPERATIONAL | `finapp_reporting` | default | default | generated | store | store | - |
| `finapp.database.reserved-connections` | `FINAPP_DB_RESERVED_CONNECTIONS` | OPERATIONAL | `12` | default | default | overlay | overlay | overlay | - |
| `finapp.database.server-max-connections` | `FINAPP_DB_SERVER_MAX_CONNECTIONS` | OPERATIONAL | `100` | default | default | overlay | overlay | overlay | - |
| `finapp.doc.key-version` | `FINAPP_DOC_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.doc.ring.active-version` | `FINAPP_DOC_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.doc.ring.check-values` | `FINAPP_DOC_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.doc.ring.retired-versions` | `FINAPP_DOC_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.doc.ring.versions` | `FINAPP_DOC_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.cover.batch` | `FINAPP_FX_COVER_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.fx.cover.nudge.enabled` | `FINAPP_FX_COVER_NUDGE_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.fx.cover.nudge.queue` | `FINAPP_FX_COVER_NUDGE_QUEUE` | OPERATIONAL | `256` | default | default | default | default | default | - |
| `finapp.fx.cover.nudge.threads` | `FINAPP_FX_COVER_NUDGE_THREADS` | OPERATIONAL | `2` | default | default | default | default | default | - |
| `finapp.fx.cover.poll` | `FINAPP_FX_COVER_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.fx.cover.requote-base` | `FINAPP_FX_COVER_REQUOTEBASE` | FINANCIAL | `PT30S` | default | default | default | default | default | Paces re-quoting a refused cover, judged on the row's own permit in the claim (`claimDue`). A mix changes only when a re-quote is attempted, at that moment's data; the cover row's lifecycle keeps it to one cover. |
| `finapp.fx.cover.resend-after` | `FINAPP_FX_COVER_RESENDAFTER` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.fx.cover.sweeper.enabled` | `FINAPP_FX_COVER_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.fx.evidence.key-version` | `FINAPP_FX_EVIDENCE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.fx.evidence.ring.active-version` | `FINAPP_FX_EVIDENCE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.evidence.ring.check-values` | `FINAPP_FX_EVIDENCE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.evidence.ring.retired-versions` | `FINAPP_FX_EVIDENCE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.evidence.ring.versions` | `FINAPP_FX_EVIDENCE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.provider.b.timeout` | `FINAPP_FX_PROVIDER_B_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.fx.provider.b.url` | `FINAPP_FX_PROVIDER_B_URL` | FINANCIAL | (none) | default | default | default | default | default | A second provider, chosen by the routing the code holds. Set on one replica and not another, the replica without it simply has one provider fewer to offer, and a command it cannot route is refused and retried. Absent in every environment until a real provider exists. |
| `finapp.fx.provider.timeout` | `FINAPP_FX_PROVIDER_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | overlay | default | default | - |
| `finapp.fx.provider.url` | `FINAPP_FX_PROVIDER_URL` | FINANCIAL | (none) | default | default | default | default | default | A rail exists on a replica exactly when this is set. Set on one replica and not another, a command reaching the one without it is refused and retried, and a callback there is refused and redelivered, so nothing moves on half a configuration, and the provider's idempotency key covers a retry that reaches the other replica. Absent in every environment until a real provider exists. |
| `finapp.fx.quote.expiry.batch` | `FINAPP_FX_QUOTE_EXPIRY_BATCH` | OPERATIONAL | `200` | default | default | default | default | default | - |
| `finapp.fx.quote.expiry.poll` | `FINAPP_FX_QUOTE_EXPIRY_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.fx.quote.expiry.sweeper.enabled` | `FINAPP_FX_QUOTE_EXPIRY_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.fx.rate.sweeper.enabled` | `FINAPP_FX_RATE_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.fx.reference.poll` | `FINAPP_FX_REFERENCE_POLL` | OPERATIONAL | `PT15S` | default | default | default | default | default | - |
| `finapp.fx.reference.timeout` | `FINAPP_FX_REFERENCE_TIMEOUT` | OPERATIONAL | `PT5S` | default | default | overlay | default | default | - |
| `finapp.fx.reference.url` | `FINAPP_FX_REFERENCE_URL` | FINANCIAL | (none) | default | default | default | default | default | The source of reference rates. A replica without it stores no snapshot, and the pricing policy's staleness bound, which is versioned data, refuses a price from a rate too old, so a mix prices from whichever snapshot is in force or refuses. Absent in every environment until a real source exists. |
| `finapp.fx.reference.window` | `FINAPP_FX_REFERENCE_WINDOW` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.fx.report.url` | `FINAPP_FX_REPORT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.fx.webhook.previous.valid-until` | `FINAPP_FX_WEBHOOK_PREVIOUS_VALIDUNTIL` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.fx.webhook.tolerance` | `FINAPP_FX_WEBHOOK_TOLERANCE` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.gauges.read-at-start` | `FINAPP_GAUGES_READATSTART` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.gauges.stale-after` | `FINAPP_GAUGES_STALEAFTER` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.inbox.consumer.enabled` | `FINAPP_INBOX_CONSUMER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.inbox.failure-backoff` | `FINAPP_INBOX_FAILUREBACKOFF` | OPERATIONAL | `PT1S` | default | default | default | default | default | - |
| `finapp.inbox.park-after-attempts` | `FINAPP_INBOX_PARKAFTERATTEMPTS` | OPERATIONAL | `5` | default | default | default | default | default | - |
| `finapp.inbox.park-after-failing-for` | `FINAPP_INBOX_PARKAFTERFAILINGFOR` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.inbox.poll-timeout` | `FINAPP_INBOX_POLLTIMEOUT` | OPERATIONAL | `PT1S` | default | default | default | default | default | - |
| `finapp.inbox.retention` | `FINAPP_INBOX_RETENTION` | FINANCIAL | `P14D` | default | default | default | default | default | Stamped on each dedupe row when it is written, and refused at start at or below the oldest record the brokers can redeliver (`RetentionSweep`), so every running value is above the redelivery horizon. Two replicas' rows expire at different times, each still later than any redelivery, so no redelivery outlives its record and no value of a fleet admits a second effect. |
| `finapp.kafka.ack-timeout` | `FINAPP_KAFKA_ACKTIMEOUT` | OPERATIONAL | `PT10S` | default | default | default | default | default | - |
| `finapp.kafka.bootstrap-servers` | `FINAPP_KAFKA_BOOTSTRAPSERVERS` | OPERATIONAL | (loopback) | default | default | generated | store | store | - |
| `finapp.kafka.sasl.username` | `FINAPP_KAFKA_SASL_USERNAME` | OPERATIONAL | (blank) | default | default | generated | store | store | - |
| `finapp.kafka.security-protocol` | `FINAPP_KAFKA_SECURITYPROTOCOL` | OPERATIONAL | `PLAINTEXT` | default | default | overlay | overlay | overlay | - |
| `finapp.kafka.ssl.truststore-location` | `FINAPP_KAFKA_SSL_TRUSTSTORELOCATION` | OPERATIONAL | (blank) | default | default | overlay | default | default | - |
| `finapp.kyc.callback.previous.valid-until` | `FINAPP_KYC_CALLBACK_PREVIOUS_VALIDUNTIL` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.kyc.counterparty.batch` | `FINAPP_KYC_COUNTERPARTY_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.kyc.counterparty.poll` | `FINAPP_KYC_COUNTERPARTY_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.kyc.counterparty.sweeper.enabled` | `FINAPP_KYC_COUNTERPARTY_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.kyc.provider.timeout` | `FINAPP_KYC_PROVIDER_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.kyc.provider.url` | `FINAPP_KYC_PROVIDER_URL` | FINANCIAL | (blank) | default | overlay | default | default | default | Where an identity check is sent. A replica without it carries none of the provider's adapters, so a check it cannot make is never recorded as made, and a mix leaves it to a replica that can. Absent in every deployed environment until a real provider exists. |
| `finapp.lending.capital.headroom-floor.BHD` | `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_BHD` | FINANCIAL | `0` | default | default | default | default | default | Read only by the gauge `finapp.lending.capital.headroom.low`, never by a lending decision or a limit check, and no amount leaves the application. Mixed values make two replicas' gauges differ for at most a rollout, and `LendingCapitalHeadroomLow` reads the fleet's maximum. |
| `finapp.lending.capital.headroom-floor.EUR` | `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_EUR` | FINANCIAL | `0` | default | default | default | default | default | Read only by the gauge `finapp.lending.capital.headroom.low`, never by a lending decision or a limit check, and no amount leaves the application. Mixed values make two replicas' gauges differ for at most a rollout, and `LendingCapitalHeadroomLow` reads the fleet's maximum. |
| `finapp.lending.capital.headroom-floor.GBP` | `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_GBP` | FINANCIAL | `0` | default | default | default | default | default | Read only by the gauge `finapp.lending.capital.headroom.low`, never by a lending decision or a limit check, and no amount leaves the application. Mixed values make two replicas' gauges differ for at most a rollout, and `LendingCapitalHeadroomLow` reads the fleet's maximum. |
| `finapp.lending.capital.headroom-floor.JPY` | `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_JPY` | FINANCIAL | `0` | default | default | default | default | default | Read only by the gauge `finapp.lending.capital.headroom.low`, never by a lending decision or a limit check, and no amount leaves the application. Mixed values make two replicas' gauges differ for at most a rollout, and `LendingCapitalHeadroomLow` reads the fleet's maximum. |
| `finapp.lending.capital.headroom-floor.USD` | `FINAPP_LENDING_CAPITAL_HEADROOM_FLOOR_USD` | FINANCIAL | `0` | default | default | default | default | default | Read only by the gauge `finapp.lending.capital.headroom.low`, never by a lending decision or a limit check, and no amount leaves the application. Mixed values make two replicas' gauges differ for at most a rollout, and `LendingCapitalHeadroomLow` reads the fleet's maximum. |
| `finapp.lending.disbursement.batch` | `FINAPP_LENDING_DISBURSEMENT_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.lending.disbursement.permit` | `FINAPP_LENDING_DISBURSEMENT_PERMIT` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.lending.disbursement.poll` | `FINAPP_LENDING_DISBURSEMENT_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.lending.disbursement.sweeper.enabled` | `FINAPP_LENDING_DISBURSEMENT_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.lending.origination.batch` | `FINAPP_LENDING_ORIGINATION_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.lending.origination.permit` | `FINAPP_LENDING_ORIGINATION_PERMIT` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.lending.origination.poll` | `FINAPP_LENDING_ORIGINATION_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.lending.origination.sweeper.enabled` | `FINAPP_LENDING_ORIGINATION_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.lending.payout.batch` | `FINAPP_LENDING_PAYOUT_BATCH` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.lending.payout.permit` | `FINAPP_LENDING_PAYOUT_PERMIT` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.lending.payout.poll` | `FINAPP_LENDING_PAYOUT_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.lending.payout.sweeper.enabled` | `FINAPP_LENDING_PAYOUT_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.lending.proofs.refresh` | `FINAPP_LENDING_PROOFS_REFRESH` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.lending.servicing.batch` | `FINAPP_LENDING_SERVICING_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.lending.servicing.poll` | `FINAPP_LENDING_SERVICING_POLL` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.lending.servicing.retry` | `FINAPP_LENDING_SERVICING_RETRY` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.lending.servicing.sweeper.enabled` | `FINAPP_LENDING_SERVICING_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.merchant.payout-destination.cooling-off` | `FINAPP_MERCHANT_PAYOUTDESTINATION_COOLINGOFF` | FINANCIAL | `PT72H` | default | default | default | default | default | Copied into the approval's own `cooling_off_until` when it is approved (`PayoutDestination.approve`), so every approval carries the value that judged it, changing this never alters one already given, and the effectuation reads the row rather than the property. |
| `finapp.merchant.payout-destination.effectuation.batch` | `FINAPP_MERCHANT_PAYOUTDESTINATION_EFFECTUATION_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.merchant.payout-destination.effectuation.enabled` | `FINAPP_MERCHANT_PAYOUTDESTINATION_EFFECTUATION_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.merchant.payout-destination.effectuation.poll-interval` | `FINAPP_MERCHANT_PAYOUTDESTINATION_EFFECTUATION_POLLINTERVAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.merchant.payout-destination.tokenisation.timeout` | `FINAPP_MERCHANT_PAYOUTDESTINATION_TOKENISATION_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.merchant.payout-destination.tokenisation.url` | `FINAPP_MERCHANT_PAYOUTDESTINATION_TOKENISATION_URL` | FINANCIAL | (none) | default | default | default | default | default | Where a card or payout destination is tokenised. A replica without it answers the honest unavailable error and takes no data, so a mix refuses on one replica what another completes; a retry reaches a replica that can. Absent in every environment until a real provider exists. |
| `finapp.merchant.payout.evidence.key-version` | `FINAPP_MERCHANT_PAYOUT_EVIDENCE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.merchant.payout.evidence.ring.active-version` | `FINAPP_MERCHANT_PAYOUT_EVIDENCE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.merchant.payout.evidence.ring.check-values` | `FINAPP_MERCHANT_PAYOUT_EVIDENCE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.merchant.payout.evidence.ring.retired-versions` | `FINAPP_MERCHANT_PAYOUT_EVIDENCE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.merchant.payout.evidence.ring.versions` | `FINAPP_MERCHANT_PAYOUT_EVIDENCE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.merchant.payout.provider.timeout` | `FINAPP_MERCHANT_PAYOUT_PROVIDER_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | overlay | default | default | - |
| `finapp.merchant.payout.provider.url` | `FINAPP_MERCHANT_PAYOUT_PROVIDER_URL` | FINANCIAL | (none) | default | overlay | default | default | default | A rail exists on a replica exactly when this is set. Set on one replica and not another, a command reaching the one without it is refused and retried, and a callback there is refused and redelivered, so nothing moves on half a configuration, and the provider's idempotency key covers a retry that reaches the other replica. Absent in every environment until a real provider exists. |
| `finapp.merchant.payout.return.sweeper.batch` | `FINAPP_MERCHANT_PAYOUT_RETURN_SWEEPER_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.merchant.payout.return.sweeper.enabled` | `FINAPP_MERCHANT_PAYOUT_RETURN_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.merchant.payout.return.sweeper.poll-interval` | `FINAPP_MERCHANT_PAYOUT_RETURN_SWEEPER_POLLINTERVAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.merchant.payout.sweeper.batch` | `FINAPP_MERCHANT_PAYOUT_SWEEPER_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.merchant.payout.sweeper.dispatched-age` | `FINAPP_MERCHANT_PAYOUT_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT10M` | default | default | default | default | default | - |
| `finapp.merchant.payout.sweeper.enabled` | `FINAPP_MERCHANT_PAYOUT_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.merchant.payout.sweeper.poll-interval` | `FINAPP_MERCHANT_PAYOUT_SWEEPER_POLLINTERVAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.merchant.payout.sweeper.unknown-age` | `FINAPP_MERCHANT_PAYOUT_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.mfa.key-version` | `FINAPP_MFA_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.mfa.ring.active-version` | `FINAPP_MFA_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.mfa.ring.check-values` | `FINAPP_MFA_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.mfa.ring.retired-versions` | `FINAPP_MFA_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.mfa.ring.versions` | `FINAPP_MFA_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.outbox.poll-interval` | `FINAPP_OUTBOX_POLLINTERVAL` | OPERATIONAL | `PT1S` | default | default | default | default | default | - |
| `finapp.outbox.relay.enabled` | `FINAPP_OUTBOX_RELAY_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.outbox.retention` | `FINAPP_OUTBOX_RETENTION` | OPERATIONAL | `P30D` | default | default | default | default | default | - |
| `finapp.paymentmethods.tokenisation.timeout` | `FINAPP_PAYMENTMETHODS_TOKENISATION_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | overlay | default | default | - |
| `finapp.paymentmethods.tokenisation.url` | `FINAPP_PAYMENTMETHODS_TOKENISATION_URL` | FINANCIAL | (none) | default | default | default | default | default | Where a card or payout destination is tokenised. A replica without it answers the honest unavailable error and takes no data, so a mix refuses on one replica what another completes; a retry reaches a replica that can. Absent in every environment until a real provider exists. |
| `finapp.payments.callback-hint.batch` | `FINAPP_PAYMENTS_CALLBACKHINT_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.payments.callback-hint.first-ask` | `FINAPP_PAYMENTS_CALLBACKHINT_FIRSTASK` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.callback-hint.give-up-after` | `FINAPP_PAYMENTS_CALLBACKHINT_GIVEUPAFTER` | FINANCIAL | `P7D` | default | default | default | default | default | Abandons a hint older than this, loudly and counted, in one conditional update on the row's own age (`expire`). The shorter value applies for at most a rollout; nothing financial hangs on a hint, since the provider's own report and reconciliation still arrive. |
| `finapp.payments.callback-hint.inquiry-permits` | `FINAPP_PAYMENTS_CALLBACKHINT_INQUIRYPERMITS` | OPERATIONAL | `16` | default | default | default | default | default | - |
| `finapp.payments.callback-hint.refute-after.clearing` | `FINAPP_PAYMENTS_CALLBACKHINT_REFUTEAFTER_CLEARING` | FINANCIAL | `PT30M` | default | default | default | default | default | A provider's denial older than this refutes the hint, in one conditional update on the hint's own row (`concludeRefuted`). The shorter value applies for at most a rollout; a refutation is loud and counted, and a clearing moves no money and settlement reconciliation catches it. |
| `finapp.payments.callback-hint.refute-after.dispute` | `FINAPP_PAYMENTS_CALLBACKHINT_REFUTEAFTER_DISPUTE` | FINANCIAL | `PT2H` | default | default | default | default | default | A provider's denial older than this refutes the hint, in one conditional update on the hint's own row (`concludeRefuted`). The shorter value applies for at most a rollout; a refutation is loud and counted, and this bound is the longest because nothing else creates a dispute's row, so shortening it is the direction a reviewer must justify. |
| `finapp.payments.callback-hint.refute-after.execution` | `FINAPP_PAYMENTS_CALLBACKHINT_REFUTEAFTER_EXECUTION` | FINANCIAL | `PT15M` | default | default | default | default | default | A provider's denial older than this refutes the hint, in one conditional update on the hint's own row (`concludeRefuted`). The shorter value applies for at most a rollout; a refutation is loud and counted, and the instant rail declares a final answer within ninety seconds, ten times shorter than this. |
| `finapp.payments.callback-hint.retry-after` | `FINAPP_PAYMENTS_CALLBACKHINT_RETRYAFTER` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.callback-hint.retry-cap` | `FINAPP_PAYMENTS_CALLBACKHINT_RETRYCAP` | OPERATIONAL | `PT6H` | default | default | default | default | default | - |
| `finapp.payments.callback-hint.sweeper.enabled` | `FINAPP_PAYMENTS_CALLBACKHINT_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.callback-hint.sweeper.poll` | `FINAPP_PAYMENTS_CALLBACKHINT_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.dispute-response.sweeper.batch` | `FINAPP_PAYMENTS_DISPUTERESPONSE_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.dispute-response.sweeper.dispatched-age` | `FINAPP_PAYMENTS_DISPUTERESPONSE_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.payments.dispute-response.sweeper.enabled` | `FINAPP_PAYMENTS_DISPUTERESPONSE_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.dispute-response.sweeper.poll` | `FINAPP_PAYMENTS_DISPUTERESPONSE_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.dispute-response.sweeper.unknown-age` | `FINAPP_PAYMENTS_DISPUTERESPONSE_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.dispute.deadline-alarm-window` | `FINAPP_PAYMENTS_DISPUTE_DEADLINEALARMWINDOW` | OPERATIONAL | `P3D` | default | default | default | default | default | - |
| `finapp.payments.dispute.evidence.key-version` | `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.payments.dispute.evidence.ring.active-version` | `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.dispute.evidence.ring.check-values` | `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.dispute.evidence.ring.retired-versions` | `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.dispute.evidence.ring.versions` | `FINAPP_PAYMENTS_DISPUTE_EVIDENCE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.evidence.key-version` | `FINAPP_PAYMENTS_EVIDENCE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.payments.evidence.ring.active-version` | `FINAPP_PAYMENTS_EVIDENCE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.evidence.ring.check-values` | `FINAPP_PAYMENTS_EVIDENCE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.evidence.ring.retired-versions` | `FINAPP_PAYMENTS_EVIDENCE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.evidence.ring.versions` | `FINAPP_PAYMENTS_EVIDENCE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.instant.timeout` | `FINAPP_PAYMENTS_INSTANT_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | default | default | default | - |
| `finapp.payments.instant.url` | `FINAPP_PAYMENTS_INSTANT_URL` | FINANCIAL | (none) | default | overlay | default | default | default | A rail exists on a replica exactly when this is set. Set on one replica and not another, a command reaching the one without it is refused and retried, and a callback there is refused and redelivered, so nothing moves on half a configuration, and the provider's idempotency key covers a retry that reaches the other replica. Absent in every environment until a real provider exists. |
| `finapp.payments.instant.webhook.previous.valid-until` | `FINAPP_PAYMENTS_INSTANT_WEBHOOK_PREVIOUS_VALIDUNTIL` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.instant.webhook.tolerance` | `FINAPP_PAYMENTS_INSTANT_WEBHOOK_TOLERANCE` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.payments.outbound.return.sweeper.batch` | `FINAPP_PAYMENTS_OUTBOUND_RETURN_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.outbound.return.sweeper.enabled` | `FINAPP_PAYMENTS_OUTBOUND_RETURN_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.outbound.return.sweeper.poll` | `FINAPP_PAYMENTS_OUTBOUND_RETURN_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.batch` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.delivery-age` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_DELIVERYAGE` | OPERATIONAL | `PT10M` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.dispatched-age` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.enabled` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.outbound.sweeper.margin` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_MARGIN` | FINANCIAL | `PT5M` | default | default | default | default | default | Added to the rail's declared deadline before the provider's explicit `UNRECOGNISED` concludes the credit, judged on the locked row by one conditional transition. The conclusion still needs the provider's own answer, and either value absorbs the clock skew and delivery lag it exists for (zero is refused at construction). |
| `finapp.payments.outbound.sweeper.poll` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.received-age` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_RECEIVEDAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.payments.outbound.sweeper.unknown-age` | `FINAPP_PAYMENTS_OUTBOUND_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.payin.sweeper.batch` | `FINAPP_PAYMENTS_PAYIN_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.payin.sweeper.enabled` | `FINAPP_PAYMENTS_PAYIN_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.payin.sweeper.initiation-age` | `FINAPP_PAYMENTS_PAYIN_SWEEPER_INITIATIONAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.payments.payin.sweeper.poll` | `FINAPP_PAYMENTS_PAYIN_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.provider.timeout` | `FINAPP_PAYMENTS_PROVIDER_TIMEOUT` | OPERATIONAL | `PT2S` | default | default | overlay | default | default | - |
| `finapp.payments.provider.url` | `FINAPP_PAYMENTS_PROVIDER_URL` | FINANCIAL | (none) | default | overlay | default | default | default | A rail exists on a replica exactly when this is set. Set on one replica and not another, a command reaching the one without it is refused and retried, and a callback there is refused and redelivered, so nothing moves on half a configuration, and the provider's idempotency key covers a retry that reaches the other replica. Absent in every environment until a real provider exists. |
| `finapp.payments.return.sweeper.batch` | `FINAPP_PAYMENTS_RETURN_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.return.sweeper.dispatched-age` | `FINAPP_PAYMENTS_RETURN_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.payments.return.sweeper.enabled` | `FINAPP_PAYMENTS_RETURN_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.return.sweeper.poll` | `FINAPP_PAYMENTS_RETURN_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.return.sweeper.unknown-age` | `FINAPP_PAYMENTS_RETURN_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.sweeper.batch` | `FINAPP_PAYMENTS_SWEEPER_BATCH` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.payments.sweeper.dispatched-age` | `FINAPP_PAYMENTS_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT10M` | default | default | default | default | default | - |
| `finapp.payments.sweeper.enabled` | `FINAPP_PAYMENTS_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.sweeper.poll-interval` | `FINAPP_PAYMENTS_SWEEPER_POLLINTERVAL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.sweeper.unknown-age` | `FINAPP_PAYMENTS_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.payments.webhook.previous.valid-until` | `FINAPP_PAYMENTS_WEBHOOK_PREVIOUS_VALIDUNTIL` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.payments.webhook.tolerance` | `FINAPP_PAYMENTS_WEBHOOK_TOLERANCE` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.payments.withdrawal.sweeper.batch` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_BATCH` | OPERATIONAL | `25` | default | default | default | default | default | - |
| `finapp.payments.withdrawal.sweeper.dispatched-age` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_DISPATCHEDAGE` | OPERATIONAL | `PT10M` | default | default | default | default | default | - |
| `finapp.payments.withdrawal.sweeper.enabled` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.payments.withdrawal.sweeper.margin` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_MARGIN` | FINANCIAL | `PT5M` | default | default | default | default | default | Added to the rail's declared deadline before the provider's explicit `UNRECOGNISED` concludes the withdrawal, judged on the locked row by one conditional transition. The conclusion still needs the provider's own answer, and either value absorbs the clock skew and delivery lag it exists for (zero is refused at construction). |
| `finapp.payments.withdrawal.sweeper.poll` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_POLL` | OPERATIONAL | `PT30S` | default | default | default | default | default | - |
| `finapp.payments.withdrawal.sweeper.unknown-age` | `FINAPP_PAYMENTS_WITHDRAWAL_SWEEPER_UNKNOWNAGE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.reconciliation.matching.block-after` | `FINAPP_RECONCILIATION_MATCHING_BLOCKAFTER` | OPERATIONAL | `3` | default | default | default | default | default | - |
| `finapp.reconciliation.matching.chunk` | `FINAPP_RECONCILIATION_MATCHING_CHUNK` | OPERATIONAL | `200` | default | default | default | default | default | - |
| `finapp.reconciliation.matching.poll` | `FINAPP_RECONCILIATION_MATCHING_POLL` | OPERATIONAL | `PT15S` | default | default | default | default | default | - |
| `finapp.reconciliation.matching.sweeper.enabled` | `FINAPP_RECONCILIATION_MATCHING_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.reconciliation.sweep.batch` | `FINAPP_RECONCILIATION_SWEEP_BATCH` | OPERATIONAL | `200` | default | default | default | default | default | - |
| `finapp.reconciliation.sweep.enabled` | `FINAPP_RECONCILIATION_SWEEP_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.reconciliation.sweep.poll` | `FINAPP_RECONCILIATION_SWEEP_POLL` | OPERATIONAL | `PT60S` | default | default | default | default | default | - |
| `finapp.retention.sweep.batch-size` | `FINAPP_RETENTION_SWEEP_BATCHSIZE` | OPERATIONAL | `500` | default | default | default | default | default | - |
| `finapp.retention.sweep.enabled` | `FINAPP_RETENTION_SWEEP_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.retention.sweep.interval` | `FINAPP_RETENTION_SWEEP_INTERVAL` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.risk.action.actions-per-tick` | `FINAPP_RISK_ACTION_ACTIONSPERTICK` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.risk.action.lease` | `FINAPP_RISK_ACTION_LEASE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.risk.action.poll` | `FINAPP_RISK_ACTION_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.risk.action.sweeper.enabled` | `FINAPP_RISK_ACTION_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.risk.case.folds-per-tick` | `FINAPP_RISK_CASE_FOLDSPERTICK` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.risk.case.prohibitions-per-tick` | `FINAPP_RISK_CASE_PROHIBITIONSPERTICK` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.risk.filing.inquiry-interval` | `FINAPP_RISK_FILING_INQUIRYINTERVAL` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.risk.filing.lease` | `FINAPP_RISK_FILING_LEASE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.risk.filing.reporting-entity` | `FINAPP_RISK_FILING_REPORTINGENTITY` | FINANCIAL | `FINAPP-EU-REF-0001` | default | default | default | default | default | The reference a filing carries to the simulated authority, rendered for each submission and kept with it. A change of entity that a rollout straddles yields filings under each identity, each complete and naming its own. |
| `finapp.risk.filing.reports-per-tick` | `FINAPP_RISK_FILING_REPORTSPERTICK` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.risk.filing.simulation` | `FINAPP_RISK_FILING_SIMULATION` | FINANCIAL | `ACKNOWLEDGE` | default | default | default | default | default | Scripts the simulated authority, the only one that exists. Each answer is recorded once on the row it concerns, under that row's own claim, so a mix records answers of either script, each complete. No overlay sets it. |
| `finapp.risk.monitoring.batch` | `FINAPP_RISK_MONITORING_BATCH` | OPERATIONAL | `200` | default | default | default | default | default | - |
| `finapp.risk.monitoring.batches-per-tick` | `FINAPP_RISK_MONITORING_BATCHESPERTICK` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.risk.monitoring.horizon` | `FINAPP_RISK_MONITORING_HORIZON` | FINANCIAL | `PT60S` | default | default | default | default | default | How recent an entry the intake may read. One claim moves the watermark and it only moves past what it has read, so a mix reads at the shorter horizon for at most a rollout. Safe exactly while every value in the rollout exceeds the longest money transaction plus clock skew, which no code can check: shortening it is the direction a reviewer must refuse without that measurement. |
| `finapp.risk.monitoring.idle` | `FINAPP_RISK_MONITORING_IDLE` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.risk.monitoring.poll` | `FINAPP_RISK_MONITORING_POLL` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.risk.monitoring.subjects-per-claim` | `FINAPP_RISK_MONITORING_SUBJECTSPERCLAIM` | OPERATIONAL | `100` | default | default | default | default | default | - |
| `finapp.risk.monitoring.sweeper.enabled` | `FINAPP_RISK_MONITORING_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.risk.profile.cadence` | `FINAPP_RISK_PROFILE_CADENCE` | FINANCIAL | `P1D` | default | default | default | default | default | How soon a party is re-assessed, stamped on the party's row by the recompute that holds its claim. A mix moves only the next due time; each recompute is complete under the policy version it pins, and a signal pulls a party forward whichever value stamped it. |
| `finapp.risk.profile.claims-per-tick` | `FINAPP_RISK_PROFILE_CLAIMSPERTICK` | OPERATIONAL | `50` | default | default | default | default | default | - |
| `finapp.risk.proofs.refresh` | `FINAPP_RISK_PROOFS_REFRESH` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.risk.proofs.statement-timeout` | `FINAPP_RISK_PROOFS_STATEMENTTIMEOUT` | OPERATIONAL | `PT5S` | default | default | default | default | default | - |
| `finapp.risk.rescreening.enqueue-batch` | `FINAPP_RISK_RESCREENING_ENQUEUEBATCH` | OPERATIONAL | `500` | default | default | default | default | default | - |
| `finapp.risk.rescreening.enqueue-batches-per-tick` | `FINAPP_RISK_RESCREENING_ENQUEUEBATCHESPERTICK` | OPERATIONAL | `4` | default | default | default | default | default | - |
| `finapp.risk.rescreening.first-backoff` | `FINAPP_RISK_RESCREENING_FIRSTBACKOFF` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.risk.rescreening.lease` | `FINAPP_RISK_RESCREENING_LEASE` | OPERATIONAL | `PT2M` | default | default | default | default | default | - |
| `finapp.risk.rescreening.max-attempts` | `FINAPP_RISK_RESCREENING_MAXATTEMPTS` | FINANCIAL | `12` | default | default | default | default | default | How many attempts exhaust a rescreening permit, judged on the permit's own count. An exhausted permit fails `UNCONCLUDED`, recorded and counted for a person, and infers neither a hit nor a clearance: the shorter value gives up sooner for at most a rollout. |
| `finapp.risk.rescreening.max-backoff` | `FINAPP_RISK_RESCREENING_MAXBACKOFF` | OPERATIONAL | `PT6H` | default | default | default | default | default | - |
| `finapp.risk.rescreening.permits-per-tick` | `FINAPP_RISK_RESCREENING_PERMITSPERTICK` | OPERATIONAL | `20` | default | default | default | default | default | - |
| `finapp.schema-version.read-at-start` | `FINAPP_SCHEMAVERSION_READATSTART` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.settlement.accept.batch` | `FINAPP_SETTLEMENT_ACCEPT_BATCH` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.settlement.bank.account-reference.BHD` | `FINAPP_SETTLEMENT_BANK_ACCOUNT_REFERENCE_BHD` | FINANCIAL | `SIMBANK-BHD-01` | default | default | default | default | default | A statement whose account record differs from this is refused `MALFORMED` (`INV-RAIL-03`). During a change of account one replica may refuse what another would accept: a refused file is a rejected batch a person re-ingests, and no replica can accept a statement for an account that is neither value. |
| `finapp.settlement.bank.account-reference.EUR` | `FINAPP_SETTLEMENT_BANK_ACCOUNT_REFERENCE_EUR` | FINANCIAL | `SIMBANK-EUR-01` | default | default | default | default | default | A statement whose account record differs from this is refused `MALFORMED` (`INV-RAIL-03`). During a change of account one replica may refuse what another would accept: a refused file is a rejected batch a person re-ingests, and no replica can accept a statement for an account that is neither value. |
| `finapp.settlement.bank.account-reference.GBP` | `FINAPP_SETTLEMENT_BANK_ACCOUNT_REFERENCE_GBP` | FINANCIAL | `SIMBANK-GBP-01` | default | default | default | default | default | A statement whose account record differs from this is refused `MALFORMED` (`INV-RAIL-03`). During a change of account one replica may refuse what another would accept: a refused file is a rejected batch a person re-ingests, and no replica can accept a statement for an account that is neither value. |
| `finapp.settlement.bank.account-reference.JPY` | `FINAPP_SETTLEMENT_BANK_ACCOUNT_REFERENCE_JPY` | FINANCIAL | `SIMBANK-JPY-01` | default | default | default | default | default | A statement whose account record differs from this is refused `MALFORMED` (`INV-RAIL-03`). During a change of account one replica may refuse what another would accept: a refused file is a rejected batch a person re-ingests, and no replica can accept a statement for an account that is neither value. |
| `finapp.settlement.bank.account-reference.USD` | `FINAPP_SETTLEMENT_BANK_ACCOUNT_REFERENCE_USD` | FINANCIAL | `SIMBANK-USD-01` | default | default | default | default | default | A statement whose account record differs from this is refused `MALFORMED` (`INV-RAIL-03`). During a change of account one replica may refuse what another would accept: a refused file is a rejected batch a person re-ingests, and no replica can accept a statement for an account that is neither value. |
| `finapp.settlement.bank.statement.url` | `FINAPP_SETTLEMENT_BANK_STATEMENT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.settlement.file.key-version` | `FINAPP_SETTLEMENT_FILE_KEYVERSION` | OPERATIONAL | `1` | default | default | default | default | default | - |
| `finapp.settlement.file.ring.active-version` | `FINAPP_SETTLEMENT_FILE_RING_ACTIVEVERSION` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.settlement.file.ring.check-values` | `FINAPP_SETTLEMENT_FILE_RING_CHECKVALUES` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.settlement.file.ring.retired-versions` | `FINAPP_SETTLEMENT_FILE_RING_RETIREDVERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.settlement.file.ring.versions` | `FINAPP_SETTLEMENT_FILE_RING_VERSIONS` | OPERATIONAL | (none) | default | default | default | default | default | - |
| `finapp.settlement.intake.backoff-base` | `FINAPP_SETTLEMENT_INTAKE_BACKOFFBASE` | OPERATIONAL | `PT1M` | default | default | default | default | default | - |
| `finapp.settlement.intake.backoff-cap` | `FINAPP_SETTLEMENT_INTAKE_BACKOFFCAP` | OPERATIONAL | `PT1H` | default | default | default | default | default | - |
| `finapp.settlement.intake.batch` | `FINAPP_SETTLEMENT_INTAKE_BATCH` | OPERATIONAL | `10` | default | default | default | default | default | - |
| `finapp.settlement.intake.poll` | `FINAPP_SETTLEMENT_INTAKE_POLL` | OPERATIONAL | `PT15S` | default | default | default | default | default | - |
| `finapp.settlement.intake.sweeper.enabled` | `FINAPP_SETTLEMENT_INTAKE_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.settlement.payout.report.url` | `FINAPP_SETTLEMENT_PAYOUT_REPORT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.settlement.psp.report.url` | `FINAPP_SETTLEMENT_PSP_REPORT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.settlement.pull.cutoff` | `FINAPP_SETTLEMENT_PULL_CUTOFF` | FINANCIAL | `PT6H` | default | default | default | default | default | When a daily report is first owed, derived on every replica from accepted batches (`ExpectedArrivals`). The replica with the earlier cut-off asks sooner and a provider without it yet answers so, and a key stays owed until a batch for it is accepted, so neither value loses a report. |
| `finapp.settlement.pull.cycle-lag` | `FINAPP_SETTLEMENT_PULL_CYCLELAG` | FINANCIAL | `PT1H` | default | default | default | default | default | How long after a settlement cycle is first seen its report is owed, on the same derived worklist. The shorter value asks sooner, and a key stays owed until a batch for it is accepted. |
| `finapp.settlement.pull.lookback-days` | `FINAPP_SETTLEMENT_PULL_LOOKBACKDAYS` | FINANCIAL | `7` | default | default | default | default | default | How many days back a daily key is still owed. Two values differ only at the oldest key, for at most a rollout. Shortening it stops a replica asking for a report that has not arrived, so that is the direction a reviewer must justify; lengthening it only asks for more. |
| `finapp.settlement.pull.poll` | `FINAPP_SETTLEMENT_PULL_POLL` | OPERATIONAL | `PT5M` | default | default | default | default | default | - |
| `finapp.settlement.pull.sweeper.enabled` | `FINAPP_SETTLEMENT_PULL_SWEEPER_ENABLED` | OPERATIONAL | `true` | default | overlay | default | default | default | - |
| `finapp.settlement.pull.timeout` | `FINAPP_SETTLEMENT_PULL_TIMEOUT` | OPERATIONAL | `PT10S` | default | default | default | default | default | - |
| `finapp.settlement.pull.window` | `FINAPP_SETTLEMENT_PULL_WINDOW` | OPERATIONAL | `PT15M` | default | default | default | default | default | - |
| `finapp.settlement.scheme.report.url` | `FINAPP_SETTLEMENT_SCHEME_REPORT_URL` | FINANCIAL | (none) | default | default | default | default | default | A settlement pull source. The worklist is derived on every replica from accepted batches and each key is pulled through a permit, so a replica with no source skips the key, one with a source pulls it, and a key stays owed until a batch for it is accepted. Absent in every environment until a real provider exists. |
| `finapp.telemetry.logs-endpoint` | `FINAPP_TELEMETRY_LOGSENDPOINT` | OPERATIONAL | (none) | default | default | pod | pod | pod | - |
| `finapp.telemetry.traces-endpoint` | `FINAPP_TELEMETRY_TRACESENDPOINT` | OPERATIONAL | (none) | default | default | pod | pod | pod | - |
| `management.server.address` | `FINAPP_MANAGEMENT_ADDRESS` | OPERATIONAL | (loopback) | default | default | pod | pod | pod | - |
| `server.tomcat.remoteip.internal-proxies` | `FINAPP_EDGE_PROXIES` | OPERATIONAL | (blank) | default | default | overlay | overlay | overlay | - |
| `spring.datasource.url` | `FINAPP_DB_URL` | OPERATIONAL | (loopback) | default | default | generated | store | store | - |
| `spring.datasource.username` | `FINAPP_DB_APP_USER` | OPERATIONAL | `finapp_app` | default | default | generated | store | store | - |

## 4. What is versioned data, and what moved there

The configuration that decides money wherever it can is already versioned, four-eyes data in PostgreSQL, each with its own "which
version judged this" pin: fee schedules, the FX pricing policies, loan and BNPL terms, the credit policies and scorecards, the risk
policies and rule sets, the reconciliation rule sets, the corridor policies and the GL mapping versions.

**No property was moved there by this task**, which that move is excluded from. Every `FINANCIAL` row above has its own answer to the
mixed-fleet question, and none of them needs two replicas to agree for a single decision to be right. A property for which that stops
being true goes on this list and into versioned data.

## 5. Database role settings

A role's setting is a cluster object, like the role, so neither is Flyway's (`DATA_MIGRATIONS.md` section 6): `RoleBootstrap` sets it on
a deployed role and `infra/postgres/initdb/00-roles.sql` on a laptop's and every test database's. `DatabaseRoleSettingsAreSafeTest` holds
this row against both and the value between the bounds below. *(Since `P15-TSK-020`:)* `finapp_app`'s two alternating users,
`finapp_app_a` and `finapp_app_b`, carry the same setting, set by the same two scripts: the fleet signs in as one of them after its first
rotation, and a role setting belongs to the user a session signs in as, never to a role it is a member of. `RoleBootstrapTest` and
`infra/scripts/migrator-verify.sh` hold that the three carry it and no other role does.

| Role | Setting | Value | Why this value |
|---|---|---|---|
| `finapp_app` | `idle_in_transaction_session_timeout` | `5min` | A transaction left idle holding an xid stops the source-join census's walk, and only a page an hour later says so. Above the waits `DatabaseRoleSettingsAreSafeTest` names inside one transaction on this role, a hand list and not a census of every transaction: the read-only snapshot a desk or a close check holds, armed at the report budget of four minutes (report generation itself runs as `finapp_reporting`), an operational report's door, the outbox relay's wait for a broker acknowledgement (10 s), a thread waiting on the pool for a second connection (5 s). Below the census's one-hour stall page, so the source ends before a person is woken for it. |

`transaction_timeout` was decided against: it would bound every long legitimate transaction, and a value safe for all of them would be
too long to bound the stall. `idle_in_transaction_session_timeout` reaches only a session that has stopped sending statements while a
transaction stands open. **A long-running statement is not bounded by this**, and an idle pool connection is not touched. The server
ends such a session and its transaction rolls back whole, the pool discards the connection, and the caller fails and retries under
its idempotency key: no partial effect. The migrator's role has its own lock timeout, and the reporting role reads only and can hold no
xid, so neither carries this. On a role that already exists it takes effect at the next run of the bootstrap step and each session's next
connection, and the procedure requires that run when a setting changes (`DATA_MIGRATIONS.md` section 2.2).

## 6. Constants that behave like configuration

**`SessionPolicy`, the idle timeout** (`X-TSK-008`'s remainder, routed here by the owner). Not a property: `SessionPolicy.current()`, thirty
minutes idle and twelve hours absolute, so no overlay can change it and a fleet cannot hold two values unless a release does. During
such a release `touch` extends a live session by the idle timeout of the instance that touched it (`JdbcSessionStore.touch`). `GREATEST`
keeps the extension monotonic and `LEAST` keeps it under the absolute bound copied at issue, which no touch can exceed. So a release that
lengthens the idle timeout lengthens sessions already issued, up to that absolute bound, and ADR-0030's "a change never extends a session"
holds for the absolute bound alone. A release that shortens it reaches sessions already issued within one old idle window: a touch
cannot pull an expiry back, but it stops extending one past the new bound. No financial outcome or limit depends on it. Storing the idle bound on the session at issue would close
it and needs an identity migration, which `THREAT_MODEL.md` section 6 item 34 puts to the owner.

## 7. What the test also holds

- **`finapp.inbox.retention`'s floor.** Every topic's `retention.ms` plus `segment.ms` plus the broker's retention check must stay below it,
  or a redelivery outlives its dedupe record. `RetentionSweep.BROKER_REDELIVERY_HORIZON` is that figure (`TopicCatalogueTest` holds it to the
  catalogue) and refuses an instance below it at start. The test holds the default and any overlay's `FINAPP_INBOX_RETENTION` above it.
- **Kafka off loopback.** `FINAPP_KAFKA_SECURITYPROTOCOL=SASL_SSL` in staging and production, judged by `KafkaTransportGuard` itself, or it
  refuses every pod. `finapp.kafka.ssl.truststore-location` stays unset: the JVM's trust store is in charge, because a managed broker's
  certificate chains to a public root. `kind`'s Kafka keys are the rehearsal's (`P15-TSK-012`).
- **No endpoint, secret or host in a ConfigMap.** The manifest policy refuses each in every overlay; the test holds that what the store
  delivers is never also a literal there.
