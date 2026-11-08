# The conventions, asserted (P15-TSK-013). This module uses no provider, so nothing here is mocked
# and nothing can reach a cloud.

run "staging_is_named_and_tagged" {
  command = plan

  variables {
    environment = "staging"
  }

  assert {
    condition     = output.name_prefix == "finapp-staging"
    error_message = "Every staging resource's name starts finapp-staging."
  }

  assert {
    condition = output.tags == {
      Project     = "finapp"
      Environment = "staging"
      ManagedBy   = "terraform"
    }
    error_message = "The convention's tags are exactly Project, Environment and ManagedBy."
  }
}

run "production_is_named_and_tagged" {
  command = plan

  variables {
    environment = "production"
  }

  assert {
    condition     = output.name_prefix == "finapp-production"
    error_message = "Every production resource's name starts finapp-production."
  }

  assert {
    condition = output.tags == {
      Project     = "finapp"
      Environment = "production"
      ManagedBy   = "terraform"
    }
    error_message = "The convention's tags are exactly Project, Environment and ManagedBy."
  }
}

run "an_environment_that_is_neither_is_refused" {
  command = plan

  variables {
    environment = "prod"
  }

  expect_failures = [var.environment]
}
