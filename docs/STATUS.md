# Status

Where the work actually stands. **This document is trusted over the
specification** (`soulbind-plan.md`) and over the README whenever they disagree.

Last updated: 2026-08-16, Phase 8 in progress.

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
| 7 | connector-flarum | **Complete** — gate passed: vectors green in both languages, the T5 injection suite green cross-engine (five specs against core on each backend), and a forum account linked by code entry against a real core, confirmed by asking core rather than the page |
| 8 | connector-plan + full-stack battery | **Complete — gate passed.** `reaper test` green on both backends in one session (run 13), and Plan renders link data for a player linked through the real flow. Battery covers: the latin1 axis asserted rather than assumed, astral-plane text round-tripped and compared, T7 fuzz against a populated deployment, T8 concurrency re-run in-session on both backends, T11 transcripts for `first-time-player` and `forum-first-user`, and the T5 browser suite with the 5xx watchdog armed on every non-injection pass. `bedrock-player` declined on the plan's own conditional — departure 10 |
| 9 | Simulated users | **Trimmed tier complete; gate met and now meaningful.** Run 12 green on both backends: three seeds × 400 actions, each reporting real work (6–8 links made, ~100 correctly refused), identical counts on both axes. Four-byte UTF-8 survives a round trip through a latin1 server. Shrinker and two nemesis classes deferred (departure 9). **One open lead:** `decisions-follow-the-rules` excluded pending diagnosis — DECISIONS 9.10 |
| 10 | Hardening and release | Not started |

## Mutation coverage

Added 2026-08-20, because every vacuous assertion this project has found was
found by breaking the covered code by hand — the operation a mutation tool
performs exhaustively.

| Tier | Tool | Where it runs |
|---|---|---|
| Java | PIT, invoked directly | `./gradlew :<module>:mutationTest`, workstation |
| PHP | Infection, pinned PHAR | `reaper test`, session only — this machine has no coverage driver |
| Shell | recorded fixtures + replay | `harness/fullstack/mutation/run.sh`, workstation |

**First sweep: 1,630 mutants, 1,015 killed, 416 never executed by any test, 199
executed by a test that did not notice.** After the first round of fixes: 1,632
/ 1,039 / 411 / 182, test strength 85%.

The 182 are the number that matters — a test ran the line, its behaviour
changed, nothing failed. What has been fixed and what has not is in
DECISIONS 8.20; the short version is that the security- and correctness-relevant
survivors are dealt with (replay protection, link-code entropy, platform-kind
learning, the asymmetric link path, outage reporting) and the tail is not.

Two caveats on the numbers, both of which make them flattering rather than
harsh: MariaDB-only paths never run on this machine, and the seeded fuzz tier is
excluded by tag, so a mutant killed only by fuzzing is counted here as
surviving.

Neither tool is gated on a threshold and neither is wired into `check`.

## What runs today

`./gradlew build` compiles every Java module and runs both test tasks — the
ordinary one and `charsetHostilityTest` — across 970 tests, green, including the seeded fuzz tier. In a reaper session, where a real MariaDB is reachable, 304 run and both backends are exercised.

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

**A failed session leaves the previous run's results on the workstation.**
reaper's backward sync merges rather than mirrors, so when `reaper test` fails
before the run stage, `out/fullstack/` still holds whatever the last successful
run put there — eight green stage XMLs, in the case that surfaced this. The exit
status is right and the artefacts contradict it. `run.sh` clears `$OUT` at the
start of every invocation for exactly this reason, but that is guest-side and
cannot reach a workstation copy for a run that never happened. Each result now
carries a `timestamp` attribute, which is the only thing distinguishing them, and
nothing enforces reading it. Logged against reaper in `reaper_bugs.md`.

**No MariaDB is reachable from the workstation**, so the storage
parameterisations and the `migrate` stage's MariaDB half can only be exercised
in a session. They are: the run verb invokes `harness/fullstack/run.sh` on both
axes, and `migrate` has passed against MariaDB in consecutive sessions, with the
fingerprint's identifier quoting and catalog scoping verified there. What
remains true is that a local `./gradlew build` proves neither — 402 tests here
against 471 on the guest — so a claim about the second backend is only ever a
claim about the last session. The figures that differ are `:core:test` alone —
402 here against 471 on the guest; a whole local `./gradlew build` is 983.

**No storage-backend evidence survives a session.** The battery runs both
backends — the parameterised names say `SQLITE` and `MARIADB`, and the counts
differ (471 tests on the guest against 402 on the workstation) — but the JUnit
XML that proves it lives under `build/`, which `[sync].exclude` keeps out of the
copy back. The only evidence that reaches the workstation is the fuzz tier's
seed line, which names its backend because that task alone sets
`showStandardStreams`. Removing `@Tag("fuzz")` from the dispatcher fuzz test
would take that away and leave the battery green with nothing showing the second
backend ever ran. Symmetric with the browser-evidence gap that
`keep_browser_evidence` now closes; the storage half is outstanding.

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

## Phase 7 — the gate, met

`reaper test` green, with the browser tier run twice: once with core on SQLite,
once on MariaDB.

| Gate item | Evidence |
|---|---|
| Vectors green in both languages | 61 checks, two entry points, both charsets; PHPUnit `OK (61 tests, 61 assertions)` twice in its pinned container |
| T5 injection suite green **cross-engine** | 10 browser passes — five specs against each backend, including a pass with core genuinely stopped |
| A forum account **links via code entry** against a real core | A code minted for a game identity, typed into the settings panel in a browser, and **core** asked afterwards: *the link is real, and core agrees* — on both engines |

```
✓ @refused   an unlinked account is refused, in core's own words
✓ @admitted  the account is admitted once the rule allows it
✓ @outage    a dead core denies, and blames the system rather than the person
✓ @recovery  the next attempt simply works, with no intervention
✓ @link      a member links this account by entering a code from another platform

accounts created by the allowing passes: 2 · by the refusing passes: 0
core reports the game identity is linked to 2 identities
```

The refusing-passes count is the assertion no browser could make: a refused
registration created **nothing**. A gate that shows a refusal and lets the row
through looks like it is working.

### Deliverables

| Deliverable | State |
|---|---|
| Extension per §10.4, Flarum pinned | 1.8.19; 2.x is only an RC (DECISIONS 7.1) |
| PHP protocol re-implementation | Held to the game side by the golden vectors |
| PHP vector consumer, hostile charset | Both entry points, both charsets |
| Webhook receiver | Signature, clock, replay; 12 mutations caught |
| Settings UI | Admin page and the member link panel |
| Register and post gates | With no forum in the deciding half |
| T3 message-key guard | Extended to the extension |

### What only a running forum found

Three defects in the connector that every static check passed over: the
extension id Flarum computes, a cache interface Flarum does not bind, and a
refusal that could not carry its reason. One in core: thirteen handlers
reporting an unparseable payload as a named field missing.

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

## Phase 8 — in progress

### connector-plan, landed

| Deliverable | State |
|---|---|
| `PlayerLinkView` / `ServerLinkSummary` | `unknown` carried as its own value, end to end |
| `LinkDataSource` | Caches answers, never outages; earliest verification wins; subject id gated on the opt-in |
| `SoulbindDataExtension` | Providers per §10.5, annotated for Plan's scanner |
| Plan API dependency | `com.github.plan-player-analytics:Plan:5.8.3605`, `compileOnly`, exact pin, JitPack scoped to that one publisher |
| Provider bodies under test | 30 tests in the module; every mutation caught, including the seven that first survived |

**Why the provider bodies needed their own tests.** `compileOnly` is right for
the host API and wrong for coverage: the annotations compile and the bodies
never run, so a units error or an empty-to-placeholder slip ships looking
exactly like working code, and Plan reports no error because there is none —
the method ran and returned a value of the right type. The API is on the test
classpath (with `commons-lang3`, which Plan's `Table.Factory` calls without
declaring) purely so those bodies execute. Neither reaches a distributed
artifact. The mutation that mattered: dropping the seconds-to-milliseconds
factor renders 1970 on every page, which reads as a data problem rather than a
units one.

### Outstanding for the gate

| Gate item | State |
|---|---|
| `harness/fullstack/` compose + stage scripts (§12) | **Green on SQLite, end to end.** `run.sh up migrate journeys down` passes against a real stack: Paper and Velocity up, a mineflayer client refused by the gate, admitted by an override, running `/link`, redeeming, and admitted — then migrate against the live used database, the Tier 11 transcript, and a teardown that genuinely stops all three ports. JDK and Node are now checksum-pinned so the same script runs on the guest. MariaDB axis and the `.reaper.toml` wiring outstanding |
| `.reaper.toml` run verb becomes real | **Wired** — the run verb now calls `harness/fullstack/run.sh` for both storage axes, MariaDB on its own database so the tier's rows cannot be mistaken for the unit suite's. Green in a session is not yet claimed |
| Run images digest-pinned | **Done, with departure 8** — the container images are digest-pinned; Paper, Velocity, the JDK and Node are SHA-256 pinned jars and tarballs, because that is how they ship |
| T6 staged battery, both backends, MariaDB started latin1 | **Partial** — migration idempotence lands as the `migrate` stage, in-session against a used database, mutation-checked. Latin1 start, astral-plane pushes and no-backdoor state building outstanding |
| T7 fuzz against the real deployment | Not started |
| T8 scenarios re-run in-session | Not started |
| Plan pages render link data | **Demonstrated in a session.** Plan renders `linked=true`, `linkStatus="linked"`, `platforms="game, harness"`, `proof="link-code"`, the subject id and `linkedSince` in milliseconds, for a player linked by a real client running `/link` and redeeming a code — with Plan's own log reporting `Registered extension: soulbind`. mariadb axis only (DECISIONS 8.15). The check has since been rewritten to assert values rather than labels and to cover the server-wide providers, which the passing version did not (8.16) |
| `journeys` emits the T11 evidence directory | **Green for 1 of the 3 journeys the plan names.** `first-time-player` runs against the live stack and emits a real per-step transcript; `COVERAGE.md` is generated from the recorded outcomes and names the other two as uncovered. No screenshots yet |
| T5 suite against the real stack, 5xx watchdog on | Not started |
| Plan pages render link data for players created through real flows | Not started |

## Where to pick up

Written at the end of a working session so the next one does not have to
reconstruct it. Everything below is true at `a15ac9a`.

### The state of things

Phase 8's **first gate clause is met**: `reaper test` runs the full battery
green on both storage backends in one session — verified four times, most
recently exit 0 in 16m05s with 1055 tests on the guest against 983 on the
workstation. The **second clause is not**: *"Plan pages render link data for
players created through real flows"* has never been demonstrated. No Plan
instance has ever run in this project.

### Next, in the order I would do it

1. **Plan pages rendering real link data.** The other half of the gate. Needs a
   Plan instance against the full-stack tier, and `connector-plan` registered as
   a `DataExtension` in a running Plan. Everything under it is tested; nothing
   has rendered.
2. **T6's remaining items.** The **latin1** half is written and is waiting on a
   session to prove it: the battery's MariaDB now starts
   `--character-set-server=latin1`, `soulbind_fullstack` is created without a
   charset clause so it inherits that, and core states utf8mb4 itself — in the
   dialect migration (`ALTER DATABASE` for future tables, `CONVERT TO` for the
   fifteen V1–V7 already created) and on the pool (`connectionCollation`).
   `SchemaCharsetTest` asserts the schema rather than a round trip, because a
   round trip only sees columns the suite happened to write emoji into.
   **Unverified against a real latin1 server** — the workstation has no MariaDB,
   so only the SQLite branch has ever executed. DECISIONS 8.18. Still open:
   astral-plane pushes through every stage, and no-backdoor state building.
3. **T7 fuzz and T8 scenarios as run stages.** `run.sh`'s `STAGES` list is
   deliberately short; adding a name without a `stage_` function is rejected
   before anything runs, and `FullstackStagesGuardTest` asserts the list, the
   functions and the README agree.
4. **T5 against the real stack** — the browser suite, 5xx watchdog on, no
   injection.
5. **The two uncovered journeys**, `forum-first-user` and `bedrock-player`,
   which the generated `COVERAGE.md` names on every run.

### What will bite you

- **The workstation's npm is broken** — `MODULE_NOT_FOUND` inside npm's own
  dependency tree, for any package. It cannot repair itself. The way round it,
  should `harness/player-driver` ever drift from its lock again: npm ships as a
  self-contained tarball, so fetch one from the registry, verify its shasum
  against the registry metadata, and run `node <extracted>/bin/npm-cli.js ci`.
  No system change and no bootstrap-by-npm paradox. The tree was reconciled that
  way on 2026-08-17 and the local loop works again.
- **`reaper test` does not provision.** Run `reaper up` first, or it exits 1
  immediately with "no sessions".
- **`JAVA_HOME=/usr/local/openjdk17` is exported on this workstation.** Set
  `JAVA=/usr/local/openjdk25/bin/java`; the harness derives `JAVA_HOME` from it
  and overrides the inherited one, but anything outside the harness will not.
- **A session left up is reused by every `reaper test`.** That is fast and it is
  also how a 24-hour-old guest came to make a stash-based baseline untrustworthy
  (`reaper_bugs.md` #2). Tear it down when the work pauses.
- **`out/` on the workstation is never truncated**, only overwritten — so after a
  run that fails before the run stage, it still holds the last green result.
  `reaper_bugs.md` #3.

### The pattern this phase kept producing

Recorded because it will recur, not as commentary. Three families, each found
several times:

- **A check inside a task cannot say whether the task ran.** Cost three defects:
  `fuzzTest` discovering nothing, `charsetHostilityTest` skipped with its guard
  inside it, and `:core:test` served `FROM-CACHE` past a guard that never
  executed. Whatever decides "did the work happen" has to sit outside the work.
- **A fix written for the instance misses the class.** `.gitignore` naming
  `run/` when the runner creates `run-<db>`; the interpreter-resolution defect
  appearing four times across `$JAVA`, `JAVA_HOME`, the pinned JDK and `$NODE`.
- **Static review of code that has never run converges on the wrong things.**
  Three adversarial rounds hardened guards; the first execution found a JVM
  mismatch, an inverted condition and a misattributed verdict, none of them
  visible to any amount of reading.

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
| Copyleft packaging | No LGPL artifact the specification pins to a non-bundling scope is declared in a configuration that would bundle it |
| Non-vacuous tiers | A tag-selected task whose module declares that tag must execute at least one test, so a tier cannot silently become zero coverage |
| Full-stack stages | The stage list, the implementations and the README table name one set; the runner still fails a stage that emits no result; no stage can report a skip |

Every one is paired with a deliberately-broken fixture and has been
mutation-checked against the real tree, not only the fixture.

Their module coverage is derived from `settings.gradle.kts` rather than
hand-listed, so a new module is guarded the day it is created and has to be
excluded deliberately, with a reason, rather than by omission.
