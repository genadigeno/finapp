# The registry's properties, asserted over a plan against a mock AWS provider. A mock provider holds no credential and calls
# no API, so this proves the configuration and nothing about the service.

mock_provider "aws" {
  override_during = plan
}

# The platform's two images and (P17-TSK-105) the Operator Console's, as each environment root passes them from its overlays.
variables {
  name_prefix = "finapp-staging"
  images      = ["finapp-app", "finapp-migrator", "finapp-console"]
}

run "there_is_one_repository_per_image_named_from_the_conventions_prefix" {
  command = plan

  assert {
    condition     = output.repository_names == { "finapp-app" = "finapp-staging-app", "finapp-migrator" = "finapp-staging-migrator", "finapp-console" = "finapp-staging-console" }
    error_message = "The repositories are not one per image, named from the conventions' prefix."
  }
}

# P17-TSK-105: the console's repository by name, as the admission policies' finapp-*-console glob and promote.sh console expect it,
# held to every setting the platform's repositories are held to - the runs above assert each over every repository; this one
# names the console's, so a console left out of the images, or given another resource, fails by its own message.
run "the_consoles_repository_is_held_as_the_platforms_are" {
  command = plan

  assert {
    condition     = lookup(output.repository_names, "finapp-console", "absent") == "finapp-staging-console"
    error_message = "The console has no repository named finapp-staging-console, the name the admission policies and promote.sh console read."
  }

  assert {
    condition     = aws_ecr_repository.this["finapp-console"].image_tag_mutability == "IMMUTABLE" && aws_ecr_repository.this["finapp-console"].force_delete == false
    error_message = "The console's repository's tags are not immutable, or a plan could delete it with its images."
  }

  assert {
    condition     = aws_ecr_repository.this["finapp-console"].image_scanning_configuration[0].scan_on_push == true
    error_message = "The console's repository does not scan on push."
  }

  assert {
    condition     = aws_ecr_repository.this["finapp-console"].encryption_configuration[0].encryption_type == "AES256"
    error_message = "The console's repository is not encrypted with the service's own key, as the platform's are."
  }

  assert {
    condition     = contains(keys(output.repository_urls), "finapp-console") && output.signature_repository_name != lookup(output.repository_names, "finapp-console", "absent")
    error_message = "The console's repository is not among the outputs a promotion copies to, or it is the signature repository."
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

run "the_signatures_live_in_a_repository_of_their_own_only_cosigns_tags_mutable" {
  command = plan

  assert {
    condition     = output.signature_repository_name == "finapp-staging-signatures"
    error_message = "The signature repository is not named from the conventions' prefix."
  }

  assert {
    condition     = aws_ecr_repository.signatures.image_tag_mutability == "IMMUTABLE_WITH_EXCLUSION"
    error_message = "The signature repository's tags are not immutable but for cosign's: a second attestation needs its tag mutable, and nothing else does."
  }

  assert {
    condition = (length(aws_ecr_repository.signatures.image_tag_mutability_exclusion_filter) == 1
      && aws_ecr_repository.signatures.image_tag_mutability_exclusion_filter[0].filter == "sha256-*"
    && aws_ecr_repository.signatures.image_tag_mutability_exclusion_filter[0].filter_type == "WILDCARD")
    error_message = "The signature repository's mutable tags are not exactly cosign's sha256-<digest> form."
  }

  assert {
    condition     = aws_ecr_repository.signatures.force_delete == false
    error_message = "A plan could delete the signature repository, and with it every digest's admission."
  }

  assert {
    condition     = !contains(values(output.repository_names), output.signature_repository_name)
    error_message = "The signature repository is one of the image repositories, every one of which must stay fully immutable."
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
