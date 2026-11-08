# The staging root, planned against a mock AWS provider (P15-TSK-013). A mock provider holds no
# credential and calls no API; TerraformIsPinnedAndCheckedTest refuses a test file that does not mock
# every provider its root requires, so no test here can reach a cloud.

mock_provider "aws" {}

variables {
  region     = "eu-central-1"
  account_id = "111111111111"
}

run "staging_names_and_tags_itself_staging" {
  command = plan

  assert {
    condition     = output.name_prefix == "finapp-staging"
    error_message = "The staging root names its resources finapp-staging."
  }

  assert {
    condition     = output.tags["Environment"] == "staging"
    error_message = "The staging root tags its resources Environment = staging."
  }
}

run "an_account_id_that_is_not_one_is_refused" {
  command = plan

  variables {
    account_id = "staging"
  }

  expect_failures = [var.account_id]
}
