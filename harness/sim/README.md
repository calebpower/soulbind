# `harness/sim/`

The simulated-user tier (§11 Tier 9). Actors take turns doing whatever they can
currently do, for hundreds of actions, while a shadow model records what should
be true and invariants diff it against core.

**Ships trimmed.** The shrinker and two of six nemesis classes are deferred —
README departures table entry 9, and `docs/DECISIONS.md` 9.1 for the reasoning
and the re-entry criteria.

## What is here so far

Deliverables (1) and (2) of §14 Phase 9: **the oracle self-test and the
invariants it grades**, then **the generator, actors and checker**. The ordering
is the plan's and it is deliberate — see below.

What is still missing is the executor: the thing that turns an `Action` into a
real call against a running core. Everything above is exercised in-process
against fakes, which is what makes it fast; the executor is what needs a
session.

| | |
|---|---|
| `CoreView` | What core says, reduced to what the invariants ask. The seam that makes the self-test possible. |
| `ShadowModel` | A deliberately *partial* second copy of the truth. |
| `Invariant` / `Invariants` | Six properties that must hold, each a separate object. |
| `OracleSelfTest` | Feeds each invariant a broken core and requires it to complain. |
| `Actor` / `World` | A simulated person spanning platforms, and the generator's scratchpad. |
| `ActionKind` / `Action` | The weighted pool, nemesis classes included. |
| `Generator` | Seeded, weighted, only ever proposing what is currently possible. |
| `Checker` | Runs the invariants periodically and at the end, deduplicating. |

## Why the self-test comes first

A simulated-user run that finds nothing is indistinguishable from a set of
invariants that *cannot* find anything — and the second is far more likely. It
is this repository's most-repeated defect, in the tier where it would be hardest
to notice, because "hundreds of actions, no violations" reads like success.

So every invariant is fed the response a broken core would send and must
complain, and is fed a healthy one and must stay silent. Both directions: an
invariant that fires on everything catches every fault and is worth nothing, and
without the control it would score identically to a good one.

## The seam that makes it fast

`CoreView` exists so that "what core said" is a **value a test can fabricate**.
The checker never touches a socket. One implementation will talk to a real core
through `connector-sdk`; the self-test supplies implementations that lie in
chosen ways, and the whole file runs in milliseconds without a server.

An invariant that cannot be made to complain against a liar would not have
complained about the real thing either.

## Hunting

```sh
SOULBIND_SIM_HUNT=50 ... soulbind-sim     # 50 fresh seeds, stop at the first finding
```

**Opt-in, and never part of the battery.** A hunt is nondeterministic in runtime
and outcome; a battery whose green depends on a dice roll is one people stop
believing, and the failure would present as flakiness rather than as a finding.
A test asserts `.reaper.toml` never sets it.

Seeds come from `SecureRandom` — outside every seeded stream by construction —
and **each is printed before it runs**, not after. If the JVM dies mid-seed, the
seed that did it is the most valuable thing in the output, and printing it
afterwards means not printing it at all.

It stops at the first finding: the budget is a bound, not a target, and once
there is something to fix, looking for a second thing delays the first.

**A hunt that finds nothing is the budget running out, not a clean bill of
health**, and the report says so in those words — there is a test on the
wording, because this is the one result in the tier most likely to be quoted as
something it is not.

Anything found prints the line to add to `seeds.txt`. Promotion is permanent and
is a human's job: a harness that could edit its own seed file would eventually
curate it, and the seeds it dropped would be the inconvenient ones.

## Two seeded-generator properties worth knowing about

**The per-run tag is drawn outside the seeded stream.** Anything that must vary
between runs — a tag distinguishing this process's rows from an earlier run's —
comes from the caller, never from the PRNG. If it came from the seeded stream,
replaying a seed would either collide with the original run's data or shift
every subsequent draw. That is the most common way a "reproducible" generator
turns out not to be, and it is invisible until somebody tries to replay one.
`GeneratorTest` asserts the action sequence is identical across two tags.

**Only applicable actions are proposed.** A draw spent on a redeem when no code
is outstanding is a draw the executor must refuse, so the weights quietly stop
meaning what they say and the run drifts toward whatever happens to be possible.

## Why the model is partial

A model that reimplemented core would be a second implementation with its own
defects, and the two agreeing would prove only that they shared a
misunderstanding. `ShadowModel` records what the actors *did* and what must
follow — these two accounts are on one subject, this code is spent, this many
mutations happened — and never how core arrived at anything.

So it can say "these accounts must share a subject" and cannot say which subject
id that is. Which is right: the id is core's to choose, and an invariant that
pinned it would be asserting an implementation detail.

## Dependencies

`connector-sdk`, `protocol`, `policy`. **Never `core`.**

The tier's claim is that a fleet of independent actors, each with its own
credential, cannot make core contradict itself. An actor that could import core
would be reaching past the door every real connector uses, and the first
invariant quietly satisfied by an in-process shortcut would be the one nobody
notices.

## Build and test

```sh
./gradlew :sim:test
```

## Release level

None. Nothing distributes it, so no runtime floor governs it — it is listed in
`ReleaseLevelGuardTest`'s exemption set beside `guards`, with that reason.
