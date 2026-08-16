# Status

Where the work actually stands. **This document is trusted over the
specification** (`soulbind-plan.md`) and over the README whenever they disagree.

Last updated: 2026-08-15, Phase 4 in progress.

---

## Phases

| Phase | What it is | State |
|---|---|---|
| 0 | Skeleton and guards | **Complete** — gate passed |
| 1 | Core skeleton: storage, config, registry, audit | **Complete** — gate passed |
| 2 | Identity graph and linking | **Complete** — gate passed: two connectors link in both directions, exactly-one-redeem proven under concurrency on both backends, vectors consumed under the hostile charset |
| 3 | Policy engine and decisions | **Complete** — gate passed |
| 4 | Events and effectors | **Complete** — gate passed: a connector down for 100 mutations receives all 100, in order, applied once by the effector's own reckoning |
| 5 | connector-velocity | **Complete** — gate passed: a real client is refused by the join gate, admitted by an override, runs /link, and the link completes, verified by reading the graph back |
| 6 | connector-discord | **In progress** — seam, scripted surface, connector, role effector and the client-library implementation landed; scripted-surface link flow green in the stack. The manual smoke against a real server is outstanding and is named as evidence, not a tier |
| 7 | connector-flarum | **In progress** — connector complete and the browser tier green against a real forum: gates refuse, admit, hold under an outage and recover, with the database confirming a refused registration created nothing. The settings UI, the code-entry link flow and the cross-engine run are outstanding, so the gate is **not** met |
| 8 | connector-plan + full-stack battery | Not started |
| 9 | Simulated users | Not started |
| 10 | Hardening and release | Not started |

## What runs today

`./gradlew build` compiles every Java module and runs both test tasks — the
ordinary one and `charsetHostilityTest` — across 832 tests, green, including the seeded fuzz tier. In a reaper session, where a real MariaDB is reachable, 304 run and both backends are exercised.

Real behaviour exists now, though nothing links anything yet — that is Phase 2.
A registered connector **can** hello and heartbeat, over both transports. What runs is the storage seam against SQLite
(MariaDB skips without `SOULBIND_TEST_MARIADB_URL`), link-code normalisation,
HMAC request signing, credential minting, the authorization matrix, and the
shared TOML configuration loader with core's schema on top of it.

**`charsetHostilityTest` re-runs the `charset`-tagged tests under
`-Dfile.encoding=ISO-8859-1`.** It exists because this JVM's default charset is
UTF-8, which made every "encoded UTF-8, never platform-default" assertion
unobservable — a mutation replacing the explicit encoding produced a green run.
See DECISIONS 1.13.

`reaper-manifest-validate .reaper.toml` passes. `reaper doctor` reports the site
healthy and the manifest valid.

## Phase 0 gate

The specification's gate is: *guards fire on their fixtures; `reaper test`
green; `reaper-manifest-validate` passes.*

| Gate item | Evidence |
|---|---|
| Guards fire on their fixtures | 9 tests in `guards/`, every guard paired with a deliberately-broken fixture; each mutation-checked by breaking the real tree and observing failure |
| `reaper-manifest-validate` passes | `ok    .reaper.toml  (1 guest)`, exit 0 |
| `reaper test` green | See "the pre-push loop", below |

## What was verified, not assumed

**Per-module release levels.** One Java 25 toolchain; class-file major versions
after a real build are 65 (Java 21) for `protocol`, `connector-sdk`,
`connector-velocity`, `connector-plan`, and 69 (Java 25) for `core`,
`connector-discord`. Asserted by a guard that checks both the declared
convention plugin and the emitted bytecode — two oracles, because the first
alone would pass if the convention plugin silently stopped setting
`options.release`.

**The guards actually fire.** Each was mutation-checked by breaking the real
tree, not only the fixture:

| Guard | Mutation | Result |
|---|---|---|
| Platform vocabulary | Fired unprompted on `(plan §7)` in a real placeholder javadoc | Code fixed, guard unchanged |
| Release level | Gave `connector-velocity` the wrong convention plugin | **Initially passed — see below** |
| Dependency graph | Declared a YAML parser in `core` | Failed as required |

## The defect Phase 0 found in itself

The release-level guard's first mutation check produced a **green run**. The
logic was correct; Gradle had marked `:guards:test` up-to-date and skipped it
entirely, because another module's build file is not one of the guards module's
declared inputs.

A guard that reports success without having looked is worse than no guard,
because it is trusted. Fixed with `outputs.upToDateWhen { false }`; the mutation
check now fires without forcing. Recorded as `docs/DECISIONS.md` 0.8.

This is the methodology's mutation-check rule earning its place on the first
guard where it could have mattered.

## Narrowings in force

Both are recorded in the README departures table with the section they override.

1. **The run verb states there is no battery and exits 0.** Scoped to the run
   verb at this phase only. `build` runs the real compile, the real test task
   and every guard; a failure there fails the session.
2. **Storage, transport and capability guards land in Phase 1.** Their subject
   code does not exist, so their fixtures could not mean anything.

## Known gaps

- No protocol implementation. `docs/protocol.md` is a stub, and the structural
  test holding it to the code arrives with the first real operation.
- `vectors/` is empty. Vectors arrive with the surface they pin.
- `harness/` is empty. Each harness arrives with the tier it serves.
- The `[run]` verb is a placeholder until Phase 8.

---

## Phase 1 gate — not yet met

The specification's gate is: *fuzz clean on both backends; matrix green; a
registered connector can hello + heartbeat over both transports.*

| Gate item | State |
|---|---|
| Matrix green | **Met.** 220 rows: every operation × every capability, plus none, all, suspended, and no credential |
| Fuzz clean on both backends | **Met.** Clean on SQLite on the workstation, and clean on SQLite *and* MariaDB in a reaper session, where `reaper test` stands up a digest-pinned database. Both fuzz tiers print `backend=MARIADB` with their seeds |
| hello + heartbeat over both transports | **Met.** Asserted end-to-end against a running server on every available backend, over the socket and the signed request transport |

Outstanding Phase 1 deliverables: WebSocket and webhook/poll transports, `hello`/heartbeat, audit query API, `soulbind-admin`
bootstrap, `soulbind doctor`, T2 DTO wire conformance, the audit-immutability
guard, T6 migration idempotence on both backends, and the T7 fuzz harness.

## Decision latency

Measured by `./gradlew :policy:latencyTest`, informational per the
specification's §14 Phase 3 gate.

| | |
|---|---|
| p50 | 396 ns |
| p99 | **2,535 ns (0.003 ms)** |
| p99.9 | 8,921 ns |
| max | 247,005 ns |

Against a target of p99 < 50 ms in-process. The margin is four orders of
magnitude, which is what a pure function with no I/O should look like — the
number is worth recording precisely because a future regression would show up
as a change of scale rather than a percentage.

Warmed up before measuring, and measured over a realistic override distribution
rather than the empty list: measuring only the fast path and calling it the
budget would be the easy mistake.

## Outstanding, and needing the owner

Two items in the whole build cannot be done from here.

**`ext-xmlwriter` for PHP.** `composer install` in `connector-flarum` fails:
PHPUnit 11 requires `ext-xmlwriter`, and this PHP 8.4.24 does not have it. The
package exists — `php84-xmlwriter-8.4.24` — but installing it means touching the
system PHP, which is outside the directive that everything created stays inside
the repository.

**Nothing is blocked by it.** The vector checks were deliberately written
PHPUnit-free (`DECISIONS.md` 7.2) and run today, on the workstation and in a
pinned container inside `reaper test`, ordinary and hostile. Installing the
extension adds the PHPUnit entry point to the same checks; it does not add
coverage that is currently missing.

**The Phase 6 manual smoke.** The specification asks for one run against a real
chat platform in a throwaway server, recorded here — and names it as **evidence,
not a tier**, because a check that needs somebody to create an account and click
through a consent screen is not a check that runs.

It needs a bot token and a server, which means a human with an account. Nothing
else is blocked by it: the connector's logic, its refusal wording, its privacy
rule and its role effector are all covered against the scripted surface, and the
full link flow game↔chat runs green in the stack on every invocation.

What the manual run would add is the one thing a scripted surface cannot: that
the client library is wired to the seam correctly — command registration
reaching the platform, an interaction arriving as an invocation, a role actually
appearing on a member.

To run it: register a bot, invite it to a throwaway server with role-management
permission, put the token in `SOULBIND_PLATFORM_TOKEN`, point
`soulbind-discord.toml` at a core, and run `/link` and `/whoami`. Record what
happened here.

## Phase 7 so far

The browser tier is **green**, and the stack it runs against is real: MariaDB,
core in the toolchain image, Flarum 1.8.19 installed from its skeleton with this
extension staged as a release would ship it, and Chromium driving the forum.

```
✓ @refused   an unlinked account is refused, in core's own words
✓ @admitted  the account is admitted once the rule allows it
✓ @outage    a dead core denies, and blames the system rather than the person
✓ @recovery  the next attempt simply works, with no intervention

accounts created by the allowing passes: 2
accounts created by the refusing passes: 0
```

That last pair is the assertion the browser could not make: a refused
registration created **nothing**. A gate that shows a refusal and lets the row
through would look like it was working.

`@outage` runs with core genuinely stopped, and asserts the person is told the
system is at fault rather than that they are not allowed — the sentence this
connector has carried deliberately through the cache, the client, the gate, the
exception handler, two error types and a frontend bundle.

### The gate, item by item

| Gate item | State |
|---|---|
| Vectors green in both languages | **Met.** 54 checks, two entry points, both charsets, and PHPUnit running in a pinned container |
| T5 injection suite green **cross-engine** | **Partly.** The suite is green; it runs against core on SQLite only. "Cross-engine" is not yet honoured |
| A forum account **links via code entry** against a real core | **Not met.** The link flow is not built |

### What is left

| Deliverable | State |
|---|---|
| Settings UI — link status, code entry and display in user settings | **Not built.** Only the admin settings page exists |
| T5 cross-engine | The forum tier needs a second run with core on MariaDB |
| T3 message-key guard extended to the extension | Not done for PHP |

Phase 7 is **not complete**, and the browser tier being green does not make it
so. What is proven is that the gates work end to end against a real forum; what
is missing is the half of the extension a member actually uses.

## The defect Phase 7 found in shipped code, on both sides

Link-code normalisation uppercases before it validates. Unicode case mapping
does not stay inside its input set, so the repair step could turn a character
that is **not** in the alphabet into one that is:

| Input | Game side | Forum side |
|---|---|---|
| `U+017F` long s | `S` — **accepted** | `S` — **accepted** |
| `U+00DF` sharp s | rejected | `SS` — **accepted** |
| `U+FB00`, `U+FB05`, `U+FB06` ligatures | rejected | `FF`, `ST`, `ST` — **accepted** |

Typing a long s where somebody's code began with `S` redeemed **their** code,
with no error anybody could see — the precise harm the reject-never-repair rule
exists to prevent, committed by the repair step itself. The two sides also
disagreed, so a code one connector accepted the other refused.

Both now fold ASCII `a`–`z` and nothing else. An exhaustive sweep of all
1,112,064 code points, in both languages, asserts that the set of characters
normalising to non-null is exactly the 28 alphabet characters plus the 20 ASCII
lowercase letters — with the expected set written out by hand rather than
derived from the code it checks.

**It was not found by a test.** The corpus passed, the hand-written tests
passed, and the hostile-charset run passed. It surfaced when the charset
handling was mutated and *every mutant survived*: the corpus held no character
whose case mapping leaves ASCII, so there was nothing to distinguish right from
wrong. The blindness was the finding. Full account in `DECISIONS.md` 7.3.

## Guards in force

| Guard | Holds |
|---|---|
| Platform vocabulary | No platform is named where the dispatcher can see it |
| Release level | Declared convention *and* emitted bytecode, per module |
| Dependency graph | No YAML parser; no copyleft artifact shaded |
| Storage seam | No SQL, JDBC type or backend-conditional branch outside `core/storage` |
| Transport seam | No HTTP or WebSocket type outside the transport packages |
| TOML entry point | Exactly one module declares a TOML parser |
| Release-level coverage | Every module in `settings.gradle.kts` has a declared release level |
| Protocol doc sync | `docs/protocol.md`'s operation and capability tables match the code, both directions |
| Check-then-act | No unreviewed read-then-write in any storage write path |
| Event doc sync | Every EventType appears in the document and vice versa |
| Audit immutability | Nothing in production source or any migration mutates the audit table, and the repository declares no mutating method |
| Harness pins | Every `harness/*/pins.env` escapes the `*.env` rule, so a clone can still reproduce a stack run |

Every one is paired with a deliberately-broken fixture and has been
mutation-checked against the real tree, not only the fixture.

Their module coverage is derived from `settings.gradle.kts` rather than
hand-listed, so a new module is guarded the day it is created and has to be
excluded deliberately, with a reason, rather than by omission.
