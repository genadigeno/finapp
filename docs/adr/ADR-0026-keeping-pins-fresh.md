# ADR-0026 — A pin that nothing maintains is a pin that rots

Status: Accepted

Date: 2026-09-02

## Context

This repository pins everything: the Gradle distribution and wrapper jar by SHA-256, four GitHub
Actions by commit SHA, two scanner images by digest, every library by checksum and version
(ADR-0025), and every infrastructure image by tag with a build-enforced drift check.

Pinning removes one risk and creates another. A tag can be repointed by whoever controls the
source repository; a SHA cannot — but a SHA also *freezes* the thing. Without an update path the
pins rot, and a fix in `actions/checkout` or in a scanner is never picked up. The `P0-TSK-004`
review recorded this as trading one supply-chain risk for a quieter one.

**The scanners are the worst case**, and it is worth being explicit about why: an outdated scanner
does not fail. It reports nothing and looks exactly like a clean scan.

## Decision

**1. Dependabot proposes updates for what it can read**: the four SHA-pinned actions, the version
catalog, and `build-logic`'s own catalog. It rewrites the SHA and the trailing `# vX.Y.Z` comment,
so the pin stays readable.

**2. A Gradle pull request from Dependabot will fail its own build, and that is correct.** A
version bump leaves the verification metadata and the six lockfiles stale, and verification refuses
an artefact it has no checksum for (ADR-0025). The bot does the discovery; a person runs the two
regeneration commands and reviews the diff. The alternative is a bot with permission to write
checksums — which is a bot that can make the build trust an artefact nobody looked at.

**3. The scanner pins stay in `infra/scanner-pins.sh`, where Dependabot cannot read them**, and get
their own mechanism instead. Moving them into the workflow so a bot could see them would put the
same digest in two places and undo the single definition `P0-TSK-031` established — a developer and
CI must run the identical image.

**4. `infra/scripts/check-pinned-images.sh` asks two different questions and distinguishes them:**

| Finding | Meaning |
|---|---|
| the pinned digest no longer matches its tag | the tag was **moved**. The pin held; find out why it had to |
| a newer release exists | rot |

Run weekly by a scheduled CI job. A red scheduled run is the reviewable signal.

**5. Each pin is three facts, not one.** `scanner-pins.sh` records repository, version *and* digest.
The version was previously a comment beside the digest, and a comment cannot be checked — which is
what made "is this digest still v8.30.1?" an unanswerable question.

## Why a check rather than a bot that opens a pull request

A workflow could open a PR with the built-in token. It would be closer to the letter of the
acceptance criterion, and it would be **untestable here**: this repository has no git remote, so no
workflow has ever executed. Dependabot is in the same position — its configuration is written
against documented behaviour, not against anything observed.

The freshness check is the part that could be *proven*, and it was: pointed at a deliberately
altered digest it reports a moved tag; pinned to `v8.30.0` it reports that `v8.30.1` exists. Both
demonstrated before this decision was written.

Given a choice between a mechanism that satisfies the wording and cannot be exercised, and one that
satisfies the purpose and is demonstrated working, this project has consistently taken the second —
and said so. This is that trade, made deliberately.

## Alternatives rejected

**Invoking the scanners as `uses: docker://image@sha256:…`.** Dependabot updates those, so it would
bring the images under the same mechanism as the actions. It also puts the digest in the workflow,
leaving the developer script needing its own copy — the exact drift `P0-TSK-031` closed by making
CI call the script. Automation is not worth reintroducing a second definition.

**A one-line `Dockerfile` per scanner, purely so the `docker` ecosystem can read the `FROM`.** It
would work. It is a file whose only purpose is to be parsed by a bot, and it would sit beside a
script that already holds the same fact — the same duplication in a less obvious place.

**Failing the freshness check on every push.** A check that fires on every change for something
that changes monthly is a check people learn to ignore, which is the failure mode `P0-TSK-034` and
ADR-0019 both cite. Weekly, and scheduled.

**Grouping Dependabot updates into one pull request.** Fewer PRs, and a single diff mixing a
patch-level action bump with a framework bump is one nobody reads carefully. The pins are few
enough that separate proposals stay legible.

## Consequences

- An action update arrives as a reviewable pull request rather than as something to remember.
- A moved scanner tag or a stale scanner release arrives as a red weekly run naming the version.
- **Both scanners are now invoked through `infra/scripts/`**, so neither pin appears inline in the
  workflow and both are covered by one freshness check. Trivy's digest had been an `env:` value —
  a place nothing reads.
- The Dependabot configuration and the scheduled job are **unexercised**: no CI run has ever
  happened here. They join the standing limitation recorded in `CURRENT_STATE.md`.
- Making the dependency scan a script a developer can run had an immediate consequence: it was run,
  and it **fails** — three HIGH/CRITICAL CVEs in the Tomcat that Spring Boot 4.1.1 brings. That gate
  had never been executed. Recorded as a blocker; the fix is a dependency upgrade and belongs to its
  own change.
- The pin record is named `scanner-pins.sh`, not `scanners.env`, and that was not a style choice:
  `.gitignore` ignores `*.env` under its Secrets section, so the first version of this file was
  silently **never committed** — every CI job calling a scanner script would have failed sourcing a
  file that does not exist. Caught by inspecting what was actually staged. Renaming is the honest
  fix; adding an exception to a security-motivated ignore rule for a file holding no secret is not.

## References

- ADR-0025 — dependency verification and locking, and why a Gradle bump needs regeneration
- `P0-TSK-031` — the single-definition property this preserves
- `SYSTEM_ARCHITECTURE.md` §Continuous Integration; `README.md` §7b
