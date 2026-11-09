# The registry's properties, asserted over a plan against a mock AWS provider. A mock provider holds no credential and calls
# no API, so this proves the configuration and nothing about the service.

mock_provider "aws" {
  override_during = plan
}

variables {
  name_prefix = "finapp-staging"
  images      = ["finapp-app", "finapp-migrator"]
}

run "there_is_one_repository_per_image_named_from_the_conventions_prefix" {
  command = plan

  assert {
    condition     = output.repository_names == { "finapp-app" = "finapp-staging-app", "finapp-migrator" = "finapp-staging-migrator" }
    error_message = "The repositories are not one per image, named from the conventions' prefix."
  }
}

run "every_tag_is_immutable" {
  command = plan

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : repository.image_tag_mutability == "IMMUTABLE"])
    error_message = "A repository's tags are not immutable, so one tag could come to mean two images."
  }

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : repository.force_delete == false])
    error_message = "A plan could delete a repository with its images."
  }
}

run "every_push_is_scanned" {
  command = plan

  assert {
    condition     = alltrue([for repository in aws_ecr_repository.this : repository.image_scanning_configuration[0].scan_on_push == true])
    error_message = "A repository does not scan on push."
  }
}

run "no_image_is_refused" {
  command = plan

  variables {
    images = []
  }

  expect_failures = [var.images]
}

run "an_image_not_named_for_the_platform_is_refused" {
  command = plan

  variables {
    images = ["ledger"]
  }

  expect_failures = [var.images]
}
