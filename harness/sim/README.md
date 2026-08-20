# `harness/sim/`

The simulated-user tier (§11 Tier 9). Actors take turns doing whatever they can
currently do, for hundreds of actions, while a shadow model records what should
be true and invariants diff it against core.

**Ships trimmed.** The shrinker and two of six nemesis classes are deferred —
README departures table entry 9, and `docs/DECISIONS.md` 9.1 for the reasoning
and the re-entry criteria.

## What is here so far

Deliverable (1) of §14 Phase 9: **the oracle self-test, and the invariants it
grades.** The generator, actors and checker come next; the ordering is the
plan's and it is deliberate.

| | |
|---|---|
| `CoreView` | What core says, reduced to what the invariants ask. The seam that makes the self-test possible. |
| `ShadowModel` | A deliberately *partial* second copy of the truth. |
| `Invariant` / `Invariants` | Six properties that must hold, each a separate object. |
| `OracleSelfTest` | Feeds each invariant a broken core and requires it to complain. |

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
