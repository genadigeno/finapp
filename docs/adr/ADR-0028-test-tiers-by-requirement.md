# ADR-0028 — A test tier is what the test needs, not what it proves

Status: Proposed

Date: 2026-09-03

## Context

`P0-TSK-036` asks for a taxonomy — "unit / slice / integration / contract / architecture tiers,
naming, tagging, and which tier a given concern belongs to" — because *without a taxonomy,
integration coverage of financial behaviour is claimed but not achieved*.

Two tiers already existed in fact: the hermetic `test` task and `databaseTest`, split so
`./gradlew build` stays green with nothing running. Nothing else was named, and 78 test classes
had grown up under one tag.

## Decision

**A tier is defined by what a test needs in order to run, and by nothing else.**

| Tier | Needs | Tag | Task |
|---|---|---|---|
| unit | nothing beyond the JVM | *(none — the default)* | `unitTest` |
| architecture | the compiled classes of every module | `architecture` | `architectureTest` |
| slice | a Spring application context | `slice` | `sliceTest` |
| database | a real PostgreSQL | `database` | `databaseTest` |

**Why this axis and no other.** A tier decides which task a test runs in, so a tier that mixes
requirements produces a task costing what its heaviest member costs and failing wherever that
member's infrastructure is absent. Grouping by *intent* — unit, contract, acceptance — reads
better and cannot be decided mechanically: two people classify the same test differently and
nothing notices. Grouping by *requirement* can be checked, and is.

**The default tier takes everything no other tier claims.** `unitTest` selects by **excluding**
the other tiers' tags rather than including a `unit` tag of its own. A set of `includeTags`-only
tasks lets a test belong to no tier at all, and that failure is silent: the test compiles, is
never selected, reports nothing, and is believed to be running. Excluding makes the worst case a
test in a tier heavier than it needs.

**The heaviest requirement wins.** Tiers are ordered, and a test that needs both a context and a
database is in `database`, because that is the requirement that decides where it can run.

**`contract` is deliberately not a tier.** A contract test reconciles a committed artefact against
the code — that is a *kind*, not a requirement. `OpenApiContractTest` needs a Spring context and
`ColumnClassificationTest` needs a database; putting them in one task would group two different
requirements under one name, which is the one thing a tier must not do. `integration` is likewise
not used: `database` says what is integrated with, so a Kafka client gets its own tier rather than
being folded into a word that would then mean two things.

**Detection is one-directional, and over-declaration is permitted.** `TestTier.requiredBy` reports
the heaviest requirement visible in a test class's own bytecode, and the enforced rule is
"declared at least as heavy as detected" — never equality. Declaring a heavier tier is the
supported way to say "this needs more than you can see".

**Detection keys on acquisition, not mention.** The database signature is `DriverManager`, a
`DataSource`, a container or the shared harness — not `java.sql` wholesale.

## Consequences

- Four tasks instead of one. `unitTest` is roughly 14 seconds against `build`'s minute, because it
  starts no Spring context; that is the inner loop the split exists for.
- The tiers **partition** the hermetic suite exactly: 418 + 54 + 68 = 540, which is `test`.
  `build` still runs all three, so nothing left CI's coverage as a side effect of the split.
- Twelve Spring-context tests turned out to be sitting in the default tier, and nine ArchUnit
  suites with them. That was invisible before there was anything to be in the wrong tier *of*.
- Splitting one task into four multiplies the ways to make the `:platform:databaseTest` mistake
  the `P0-TSK-027` review found — a module-qualified task name is a list of one. The split
  therefore ships with `TestTaxonomyTest`, which fails the build if CI stops invoking a tier or
  starts invoking one qualified.
- The thirteen test classes that opened connections through private helpers now use
  `DatabaseRoles`, so the property names and the driver call have one definition. What they were
  copying was a connection as the **superuser**, which is the wrong default for this repository.
- Test support is shared through `platform`'s `testFixtures`, so `sharedkernel`, which sits below
  it, cannot use it. That is the dependency direction working rather than a gap: `sharedkernel`
  has no database tests and must not acquire the ability to have one casually.

## Alternatives rejected

**Leaving it at two tiers and writing the taxonomy down.** It is what existed, and the document
would have described the suite as it was on the day it was written. The acceptance criterion asks
for tiers *runnable independently*.

**A source set per tier.** Total isolation, and it moves 78 files and makes a test's tier a
function of which directory somebody dropped it in — with no way to detect a wrong choice.

**An explicit tag on every test class, with no default tier.** Uniform, and it introduces the
silent "runs in no tier" failure the moment a tag is misspelled.

**Tiers by intent (unit / contract / acceptance).** The names the backlog reaches for, and they
cannot be checked. `contract` and `integration` are handled above.

**Enforcing equality between declared and detected tier.** Stricter, and immediately wrong: a
`@SpringBootTest` reaching PostgreSQL through the application's own `DataSource` has nothing in
its bytecode to detect, so the rule would demand its tag be *removed* and put a database test in
the hermetic suite.

## References

- `docs/project/TESTING.md` — the conventions this records the decision behind
- ADR-0027 (tests bring their own database); ADR-0014 and `DISTRIBUTED_EXECUTION.md` §5
- `.claude/rules/testing.md`; `DEFINITION_OF_DONE.md` §3
