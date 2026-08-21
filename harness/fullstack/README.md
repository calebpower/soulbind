# `harness/fullstack/`

The full-stack battery: core, a Paper backend and a Velocity proxy brought up
for real, with stages run against the live deployment.

```sh
SOULBIND_DB=sqlite  ./run.sh up migrate journeys sim fuzz down
SOULBIND_DB=mariadb ./run.sh up migrate journeys sim plan fuzz down
```

## Why a stage runner and not one script

`stack.sh` proves a single happy path end to end, and it is still what `up`
calls. But a gate that asks for several tiers against one live deployment needs
them separately nameable, separately reportable and separately re-runnable
against a stack that is already up. Bringing a Paper server and a proxy up once
per tier would dominate the run and quietly discourage adding tiers — which is
the same pressure that produces a battery nobody extends.

## The invariant this harness is built around

**A stage cannot report success for work it did not do.**

Every stage must emit a result. A stage that returns without emitting one fails
the run, loudly, naming itself. This is not defensive habit: a task reporting
green having executed nothing is a failure this repository has hit repeatedly —
`fuzzTest` discovering zero tests, `charsetHostilityTest` skipped as up-to-date
with its own guard inside it, a browser tier whose evidence died with the VM.

`journeys.sh` holds the same invariant one level down: a journey that emits no
transcript fails, because silence is not evidence.

There is deliberately **no skip result**. Every narrowing this project has
needed is expressed as a narrowing with a stated reason at the point that
narrows it, never as a green result carrying the word "skipped".

## Stages

| Stage | What it does |
|---|---|
| `up` | Brings the stack up via `stack.sh --keep` — which disarms its teardown trap on success, since `stack.sh` alone is a one-shot smoke that tears down even when it passes — then **probes core's port** before reporting, because a script finishing is not a stack existing. Takes the `@pristine` snapshot once it is healthy — stack-up, not end-of-run (§12), so a stage that dirties the databases can roll back to a working stack instead of an empty machine. |
| `migrate` | Migration idempotence against the **live, already-used** database. Core migrates on every `Storage.open`, so a deployment re-migrates on every restart; a second apply that is not a no-op is drift per restart, invisible to any test against a fresh database. |
| `journeys` | Tier 11 human evidence: a per-step transcript per linking journey, emitted so "would a newcomer understand this?" is answered from evidence rather than memory. |
| `sim` | Tier 9: the committed seed set, three actors each a separate principal with its own credential, four hundred weighted actions apiece against this live deployment. Invariants diff a partial shadow model against core periodically and at the end. Reports **what it did not check** before it reports the verdict — see `harness/sim/README.md`. |
| `fuzz` | Tier 7 against the **deployment**, not against a fresh embedded core. `:core:fuzzTest` already drives real HTTP and real signing against a core it starts itself; what it cannot do is drive them against a core the journeys and the simulated-user tier have already filled with subjects, identities, spent codes, rules and an audit log. Runs after both, deliberately. Oracle unchanged from Tier 7 — no 5xx, always an envelope, never `internal`, alive and answering correctly afterwards — because those four need no second implementation of any rule. Seed printed and replayable via `SOULBIND_FUZZ_SEED`. |
| `plan` | The gate's second clause: asks Plan's own HTTP API whether the soulbind extension rendered link data for a player the smoke linked through the real flow. Asserts on the extension's *values*, not on the plugin name — that appears whether or not a provider ever ran. **mariadb axis only**: Plan on a proxy supports MySQL, and the sqlite axis has no server for it. |
| `down` | Stops what `up` started. |

Adding a name to `STAGES` without a `stage_<name>` function is rejected by the
dispatcher before anything runs, and `FullstackStagesGuardTest` asserts the list,
the functions and this table agree.

## Java

`run.sh` exports `JAVA` to every stage. core targets Java 25, and a bare `java`
on a BSD workstation is dispatched to an older install — which used to surface
as a class-version error at `migrate`, after `up` had already succeeded.

```sh
JAVA=/usr/local/openjdk25/bin/java SOULBIND_DB=sqlite ./run.sh up migrate journeys down
```

## The backend axis

`SOULBIND_DB` selects core's storage. Nothing else in the stack changes — that
the proxy, the backend and the drivers cannot tell which one core uses is the
storage seam's claim, and running identical flows against either is what tests
it. Ports differ per backend so both axes can be up at once, which is the
collision the forum tier hit when both engines defaulted to one port.

`mariadb` expects `SOULBIND_MARIADB_URL` from the caller. This script does not
run a database server: the session harness does, and a second one here would be
another thing to wait for and another thing to blame.

## Where things live

| | |
|---|---|
| Mutable state | `$REAPER_STATE/run-<db>` — the only thing reaper's `reset` rolls back |
| Fetched jars, caches | `.cache/`, deliberately **outside** state, so a reset does not discard a hundred megabytes that would only be downloaded again |
| Results and evidence | `out/fullstack/<db>/` — reaper syncs back `out/` and nothing else, so anything written elsewhere is destroyed with the VM |

That last row is not a style preference. A red battery earlier in this project
left its only diagnostic trace on a machine scheduled for destruction.

## Pins

`pins.env` carries **checksums**, not just URLs. A URL is a promise somebody
else keeps; a checksum is one this repository keeps. Re-pinning is a deliberate
act with a dated note, never a side effect of re-running the script on a
Tuesday.

`MC_PROTOCOL` sits beside the jar pins because the harness client's supported
range is a real constraint on which Paper build can be used — whichever moves,
the others have to be checked.

## What this does not yet cover

Stated here rather than left to be inferred:

- `browser` (T5 against the real stack), `fuzz` (T7), and `scenarios` (T8) are
  not implemented. They are **not** listed in `STAGES`, so asking for one is an
  error rather than a silent pass.
- Of the three journeys the plan names, only `first-time-player` runs.
  `forum-first-user` and `bedrock-player` are recorded as uncovered in the
  generated `COVERAGE.md`, which is derived from the journey list rather than
  maintained by hand.
- No screenshots. The journeys covered so far are chat and protocol flows with
  no page to photograph; the forum-first journey is the one that will carry
  them.
