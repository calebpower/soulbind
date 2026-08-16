# `policy/`

The decision evaluator. A pure function, and nothing else.

## Why this module has no dependencies

Look at [`build.gradle.kts`](build.gradle.kts): the dependency block is empty,
and that is the design rather than an oversight. Evaluation is a pure function
of `(identity graph slice, rules, overrides, clock)` → `(effect, reason, ttl)`.
It has no storage, no transport, no JSON and no logging, so there is nothing for
a dependency to be *for*.

That emptiness is what makes the Tier 4 decision matrix possible. Every row
calls `PolicyEngine.decide` directly — no HTTP, no database, no fixture — so the
matrix can be **exhaustive** rather than representative. 253 rows run in
milliseconds.

A dependency appearing here is the signal that I/O has crept in.

## Precedence, and why it is in that order

1. **Overrides beat rules, and deny beats allow.** An operator saying "not this
   person" must not be undone by a rule they happen to satisfy. When two
   overrides disagree the restrictive one wins: wrongly denying costs a
   complaint, wrongly allowing costs the thing the gate existed to prevent.
2. **No rule means allow.** A gate nobody configured is a gate nobody asked for.
   Denying here would mean every new gate silently locks out everybody the
   moment a connector declares it.
3. **Grace, then requirements** — grace is a deliberate window before the gate
   closes, so it is checked before what it postpones. But a subject who already
   satisfies the rule is allowed *for that reason*, not for a grace period that
   happens to still be running: the distinction matters to whoever reads the
   decision log.

### The apparent contradiction in point 2

This layer allows when unconfigured; a **connector** that cannot reach core
*denies*. Those look contradictory and are not. Here, there is nothing to
enforce. There, enforcement has failed — and a gate that opens whenever the
dispatcher is down is a gate an attacker opens by taking the dispatcher down.

## Deadlines are exclusive, everywhere

A grace period closing exactly now is still open. So is an override expiring
exactly now, and a link code, and a cache entry. Consistency matters more than
which convention is chosen: an operator who learns one expects the others.

## Grace decisions carry a shortened TTL

A grace allowance is clamped so it cannot be cached past the moment grace ends.
Otherwise a connector caches "allow, because grace" for sixty seconds, grace
lapses ten seconds in, and the gate stays open for the remaining fifty — making
it advisory rather than enforced, and only intermittently, which is worse than
absent because somebody would have tested it and seen it work.

## Building and testing

```sh
../gradlew :policy:test          # the matrix
../gradlew :policy:latencyTest   # the measurement, informational
```

`latencyTest` is deliberately not part of `check`. It is informational per the
specification, and a number that fails a build is a number somebody will loosen
until it stops failing.

## Extension points

A new rule shape is a field on `Rule` and a row in the matrix. A new reason is a
constant on `Decision.Reason` — and adding one without a matrix row that
produces it is the kind of thing the exhaustive parameterisation is there to
make obvious.
