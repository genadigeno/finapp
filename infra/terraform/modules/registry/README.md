# `registry`

ECR repositories for the images an environment runs (`PHASE_15_PLAN.md` C7). Called by each environment root with the images its
overlay names, so a new image is a new repository without an edit here. **Never applied**: there is no account in Phase 15, so every
property below is asserted by `tests/registry.tftest.hcl` over a plan against a mock provider. That proves the configuration,
not the service's behaviour.

## What it builds

| Property | Held by the run |
|---|---|
| One repository per image, named `finapp-<environment>-<name>` | `there_is_one_repository_per_image_named_from_the_conventions_prefix` |
| Tags are immutable, and a plan cannot delete a repository with its images | `every_tag_is_immutable` |
| Every push is scanned | `every_push_is_scanned` |
| No image, or one not named for the platform, is refused | the `..._refused` runs |

**Nothing expires yet, deliberately.** An untagged image is not safely a stray. A promotion that copies a digest with no tag would leave a running
image, and the rollback target it becomes, untagged, and an expiry rule would remove it within a day of its age. AWS protects an image a manifest list refers to,
and expires a referrer with its subject, and says nothing that would protect a deployed digest. An expiry rule comes back once a promotion lands under an
immutable tag and signature storage is decided (`P15-TSK-017`, `P15-TSK-007`). Until then the repositories only grow.

**Encrypted with the service's own key.** The images hold no credential and are scanned for one, so a customer-managed key would
protect nothing the scans do not.

## Inputs and outputs

Inputs: `name_prefix`, `images`. Outputs: `repository_urls` (where a promotion copies a digest to), `repository_names`.

## Operating it

Production and non-production are in separate accounts (C8), so each has its own repositories, and a promotion copies a digest
between them (`P15-TSK-017`). Run the checks before relying on this (`OPERATIONS_RUNBOOK.md` §11.13).

## What it does not do

No push credential or repository policy (no CI credential reaches production), no replication or pull-through cache (`THREAT_MODEL.md` T42),
and no apply.
