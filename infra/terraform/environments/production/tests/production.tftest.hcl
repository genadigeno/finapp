# The production root, planned against a mock AWS provider (P15-TSK-013). A mock provider holds no
# credential and calls no API; TerraformIsPinnedAndCheckedTest refuses a test file that does not mock
# every provider its root requires, so no test here can reach a cloud.

mock_provider "aws" {}

variables {
  region     = "eu-central-1"
  account_id = "222222222222"
}

run "production_names_and_tags_itself_production" {
  command = plan

  assert {
    condition     = output.name_prefix == "finapp-production"
    error_message = "The production root names its resources finapp-production."
  }

  assert {
    condition     = output.tags["Environment"] == "production"
    error_message = "The production root tags its resources Environment = production."
  }
}

run "an_account_id_that_is_not_one_is_refused" {
  command = plan

  variables {
    account_id = "production"
  }

  expect_failures = [var.account_id]
}
