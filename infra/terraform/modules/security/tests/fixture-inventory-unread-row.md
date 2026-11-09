# The small inventory with one row this module's reader cannot parse: its name lost its backticks.

### 3.2 The inventory

| Variable | Property | Class | Owner | Rotation | Consumer | Delivered through |
|---|---|---|---|---|---|---|
| `FINAPP_MFA_KEY` | `finapp.mfa.key` | field-encryption-key | `mfa` | key-ring | `finapp-app` (`MfaBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_WEBHOOK_KEY` | `finapp.payments.webhook.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| FINAPP_NOT_IN_BACKTICKS | none | webhook-hmac | `payments` | two-valid-window | `finapp-app` | none |
| `FINAPP_KAFKA_SASL_PASSWORD` | `finapp.kafka.sasl.password` | kafka-credential | `eventing` | add-roll-remove | `finapp-app` (`EventingBeans`) | `finapp-app-secrets` |
| `FINAPP_DB_MIGRATOR_PASSWORD` | none | database-job-credential | `database` | between-releases | `finapp-migrator` (`MigratorEnvironment`); bootstrap-step | `finapp-migrator-secrets` |
| `FINAPP_DB_MASTER_PASSWORD` | none | database-master | `database` | managed-by-the-database-service | bootstrap-step (`RoleBootstrapMain`) | none |

### 3.3 Delivered beside the secrets

| Variable | Property | Why it is here | Delivered through |
|---|---|---|---|
| `FINAPP_DB_URL` | `spring.datasource.url` | An endpoint | `finapp-app-secrets`, `finapp-migrator-secrets` |
| `FINAPP_KAFKA_SASL_USERNAME` | `finapp.kafka.sasl.username` | The SCRAM user | `finapp-app-secrets` |
| `FINAPP_DB_MIGRATOR_USER` | none | The user beside the password | `finapp-migrator-secrets` |

### 3.4 Names that read like secrets
