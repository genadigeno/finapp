# A person's access key, read on every plan. In a file of its own because a read waits for its user to exist: the first
# run creates only the users, in memory, so the runs after it read them at plan; and it creates no key, which a
# test could not destroy.

mock_provider "aws" {}

variables {
  name_prefix           = "finapp-staging"
  region                = "eu-central-1"
  account_id            = "111111111111"
  secret_inventory_path = "tests/fixture-inventory.md"
  external_secret_manifest_paths = {
    app      = "tests/fixture-externalsecret-app.yaml"
    migrator = "tests/fixture-externalsecret-migrator.yaml"
  }
  secret_store_manifest_path     = "tests/fixture-secret-store.yaml"
  workload_namespace             = "finapp"
  cluster_name                   = "finapp-staging"
  cluster_role_arn               = "arn:aws:iam::111111111111:role/finapp-staging-eks-cluster"
  workload_identity_provider_arn = "arn:aws:iam::111111111111:oidc-provider/oidc.example.invalid/id/mock"
  workload_identity_issuer       = "https://oidc.example.invalid/id/mock"
  master_user_secret_arn         = "arn:aws:secretsmanager:eu-central-1:111111111111:secret:rds!db-mock-AbCdEf"
  database_backup_copy_vault_arn = "arn:aws:backup:eu-central-1:999999999999:backup-vault:finapp-copies"
  human_users = {
    alice = { roles = ["read-only", "break-glass"] }
    bob   = { roles = ["read-only"] }
  }
}

run "the_people_exist" {
  command = apply

  plan_options {
    target = [aws_iam_user.human]
  }
}

run "a_person_with_no_access_key_passes" {
  command = plan

  plan_options {
    target = [data.aws_iam_access_keys.human]
  }

  assert {
    condition     = alltrue([for keys in data.aws_iam_access_keys.human : length(keys.access_keys) == 0])
    error_message = "The mock provider gave a person an access key nobody asked it to."
  }
}

run "a_person_holding_an_access_key_stops_the_plan" {
  command = plan

  plan_options {
    target = [data.aws_iam_access_keys.human]
  }

  override_data {
    target = data.aws_iam_access_keys.human["alice"]
    values = {
      access_keys = [{ access_key_id = "AKIAFAKEFAKEFAKEFAKE", create_date = "2026-11-09T00:00:00Z", status = "Active", user_name = "alice" }]
    }
  }

  expect_failures = [data.aws_iam_access_keys.human["alice"]]
}
