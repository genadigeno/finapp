# Production applies only to production's account: production and non-production live in separate accounts
# (ADR-0121 decision 4), and allowed_account_ids refuses any other. Every resource carries the
# conventions' tags through default_tags.
provider "aws" {
  region              = var.region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = module.conventions.tags
  }
}

# Reaches the brokers themselves, so it needs a route into the private network and the administrator
# principal's SCRAM credential. Neither is here: KAFKA_SASL_USERNAME and KAFKA_SASL_PASSWORD come from the
# runner's environment, and with the stage at brokers nothing uses this provider at all
# (modules/kafka/README.md). Set explicitly so that the environment cannot turn TLS off or weaken the mechanism.
provider "kafka" {
  bootstrap_servers = split(",", module.kafka.bootstrap_brokers_sasl_scram)
  tls_enabled       = true
  skip_tls_verify   = false
  sasl_mechanism    = "scram-sha512"
}
