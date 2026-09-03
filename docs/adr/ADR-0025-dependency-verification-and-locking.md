# ADR-0025 — Every resolved artefact is checksum-verified and version-locked

Status: Accepted

Date: 2026-09-02

## Context

`P0-TSK-001` pinned the Gradle *distribution* and the wrapper jar by SHA-256, and CI pins every
third-party action to a commit SHA and every scanner to an image digest. Every **library** the build
resolves was trusted implicitly.

That is the largest remaining hole in this repository's supply chain, and it is not a small one: a
build-time dependency executes with full build privileges — it can read the source, the environment
and any credential the build holds, and it can alter the artefact that is produced.
`.claude/rules/security.md` requires external input to be treated as untrusted, and a dependency is
external input.

## Decision

**Two files, two different controls, both enforced on every build.**

| File | Refuses | Does not catch |
|---|---|---|
| `gradle/verification-metadata.xml` | an artefact whose bytes do not match the recorded SHA-256 | a *version* change to something it already records |
| six `gradle.lockfile`s | a configuration resolving a version other than the locked one | a substituted artefact at the locked version |

The lockfiles are one per project (`app`, `platform`, `sharedkernel`), one for the settings
buildscript, and two for `build-logic` (its own project and its own settings) — see the
consequence about included builds below.

Both proven by mutation: altering one recorded checksum fails the build naming the artefact and the
repository it came from; changing a locked version fails with *"Did not resolve … which is part of
the dependency lock state"*.

### Why both, when one looked sufficient

The first analysis was that locking is redundant — verification already refuses any artefact it does
not know, which was confirmed: bumping a pinned version failed the build immediately.

That analysis was wrong, and measuring the generated file is what showed it. On its **first**
generation, verification recorded **69 of 342 modules at more than one version** — `jackson-bom` at
five, `jackson-databind` at three — because the buildscript, plugin, compile and test classpaths
legitimately resolve different versions of the same module. Verification therefore cannot
distinguish a deliberate resolution from drift *between versions it already trusts*: both pass.

The lockfile can, and it earns its place for a second reason: **thirteen dependencies take their
version from the Spring Boot BOM and that version is written down nowhere in this repository.** A
BOM bump moves them silently today. The lockfile is the only artefact in which those versions
appear at all.

## The limit that matters: trust on first use

The checksums record what **this machine downloaded on the day they were generated**. They detect a
substitution afterwards. They cannot detect that the first download was already compromised — the
compromise would simply be recorded as the expected value.

This is inherent to checksum pinning and is stated rather than glossed, because a control believed
to be total is more dangerous than one whose edges are known. It is the same reasoning that records
what `secretsAreWrapped` cannot see (ADR-0019) and what the transport guard cannot read (ADR-0023).

## Alternatives rejected

**PGP signature verification instead of, or as well as, checksums.** This is the answer to
trust-on-first-use: a signature verifies the *publisher*, not merely what arrived. It was measured
rather than reasoned about — generating `pgp,sha256` over a single narrow slice of the graph
(`:sharedkernel:compileJava`) produced **11 signed artefacts and required 49 trusted keys** to be
recorded. Extrapolating that keyring across the whole 456-component graph is a substantial trust
decision in its own right: every key admitted is a publisher whose future releases are then trusted
automatically, and every *unsigned* artefact still needs a checksum, so the checksum file does not
go away. Deferred to Phase 15 with the rest of supply-chain and provenance work, where the SBOM
question already lives.

**Locking only `runtimeClasspath`.** Cheaper and it leaves the build and test classpaths — where a
compromised artefact has the *most* privilege — unlocked. `lockAllConfigurations()` is the coherent
choice for a control whose whole argument is that build-time code is dangerous.

**A guard asserting no module appears at two versions.** Considered as a way to close the
stale-entry hole, and abandoned on the evidence above: 69 modules legitimately do. A rule producing
69 false positives on the day it is written is a rule that gets deleted.

**Not doing it, because the dependency set is small.** It is 456 components. The set being small is
what makes verification cheap here, not what makes it unnecessary.

## Consequences

- A substituted artefact fails the build, naming the artefact and the repository.
- An unexplained version change is a diff in a lockfile rather than a silent resolution.
- **Changing a dependency is one command with both flags**, documented in `README.md` §7a. It was
  written here as two separate invocations, and `P0-TSK-035` found that neither order works: the
  lock refuses a version it does not know, so metadata generation cannot resolve, and verification
  refuses an artefact it has no checksum for, so lock generation cannot resolve. Each control blocks
  the other's regeneration, which is only discoverable by adding a dependency. The acceptance criterion asks that this not be
  "regenerate everything and hope", and it is not: regeneration **merges**, so existing entries
  survive a narrow run — verified — and the diff is what the reviewer reads.
- **Gradle never prunes.** Removing a dependency leaves its entries trusted, so superseded entries
  are deleted by hand in the same change. Recorded in the procedure; the lockfile is what shows
  which ones.
- **`build-logic` is locked separately.** It is an included build with its own settings, so a root
  `--write-locks` does not reach it - verified. Its artefacts were already covered by the root
  verification metadata, because verification is Gradle-wide and reaches included builds; only the
  version record was missing, and it matters as much as any: a precompiled script plugin runs in
  every build with full privileges. The extra command is in the procedure.
- Generation must cover every configuration. A narrow run records only what it resolved, so a later
  full build fails with a *missing* checksum — loud, and in the safe direction.
- **Generation must also cover a cold cache, and this was missed until CI existed.** Over a warm
  `GRADLE_USER_HOME` Gradle does not re-read metadata descriptors it has already parsed, so
  generation records fewer artefacts than a cold resolution needs. The first CI run — the first
  cold resolution this project ever had — failed on `kotlinx-coroutines-bom:1.8.0.pom`. Regenerating
  against an empty home added **10 components and 23 artefacts**, every one a parent POM or a BOM
  `.module`. The file had been complete for one machine and incomplete for every other, a new
  developer included. This is the same shape as the criterion-7 failure that surfaced it: a control
  verified only where it was written is a control with an untested precondition. The procedure in
  `README.md` §7a now regenerates against a temporary home.
- CI gains the control for free: verification is enforced by Gradle itself, so the existing `build`
  job fails on a bad artefact with no workflow change.

## References

- `SYSTEM_ARCHITECTURE.md` §Continuous Integration — the pinning this completes
- `README.md` §7a — the update procedure
- ADR-0020 — secret management; the same "measured, not assumed" treatment of a scanner's reach
- `P0-TSK-040` — the update path for the pins CI holds, still outstanding
