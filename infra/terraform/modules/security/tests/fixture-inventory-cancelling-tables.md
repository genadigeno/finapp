# The small inventory with two rows this module cannot parse and a section with no table: summed, the two would cancel.

### 3.2 The inventory

| Variable | Property | Class | Owner | Rotation | Consumer | Delivered through |
|---|---|---|---|---|---|---|
| `FINAPP_MFA_KEY` | `finapp.mfa.key` | field-encryption-key | `mfa` | key-ring | `finapp-app` (`MfaBeans`) | `finapp-app-secrets` |
| `FINAPP_PAYMENTS_WEBHOOK_KEY` | `finapp.payments.webhook.key` | webhook-hmac | `payments` | two-valid-window | `finapp-app` (`PaymentBeans`) | `finapp-app-secrets` |
| `FINAPP_KAFKA_SASL_PASSWORD` | `finapp.kafka.sasl.password` | kafka-credential | `eventing` | add-roll-remove | `finapp-app` (`EventingBeans`) | `finapp-app-secrets` |
| `FINAPP_KAFKA_SASL_USERNAME` | `finapp.kafka.sasl.username` | kafka-credential | `eventing` | add-roll-remove | `finapp-app` (`EventingBeans`) | `finapp-app-secrets` |
| `FINAPP_DB_URL` | `spring.datasource.url` | endpoint | `database` | none | `finapp-app` | `finapp-app-secrets`, `finapp-migrator-secrets` |
| `FINAPP_DB_MIGRATOR_PASSWORD` | none | database-job-credential | `database` | between-releases | `finapp-migrator` (`MigratorEnvironment`); bootstrap-step | `finapp-migrator-secrets` |
| `FINAPP_DB_MIGRATOR_USER` | none | database-job-credential | `database` | between-releases | `finapp-migrator` | `finapp-migrator-secrets` |
| FINAPP_NOT_IN_BACKTICKS_ONE | none | webhook-hmac | `payments` | two-valid-window | `finapp-app` | none |
| FINAPP_NOT_IN_BACKTICKS_TWO | none | webhook-hmac | `payments` | two-valid-window | `finapp-app` | none |

### 3.3 Delivered beside the secrets

There is no table here.

### 3.4 Names that read like secrets
