# Status

Where the work actually stands. **This document is trusted over the
specification** (`soulbind-plan.md`) and over the README whenever they disagree.

Last updated: 2026-09-09. **All ten phases complete, every gate met.**
Phase 11 (measures) is in progress — see the section at the end.
`0.1.5` is released and the first estate runs it, with enforcement off
everywhere — no rules exist, so every gate answers `allow` / `no-rule`.

Sections below are kept in the order the work happened rather than rewritten, so
a phase heading describes what that phase did, not what is happening now.

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
| 6 | connector-discord | **Complete — gate passed.** The manual smoke ran against a real bot and a real server: `/link` typed by a person, the code redeemed on another platform, core confirming the link, and `/whoami` reading it back. It found three defects — a documented capability grant missing `link-state-reader`, commands registering globally when a named guild was not visible, and `subject.requirements-met` being emitted by nothing at all, which meant the effector half could not fire in any deployment. All three fixed; the role grant and revoke were then proven live |
| 7 | connector-flarum | **Complete** — gate passed: vectors green in both languages, the T5 injection suite green cross-engine (five specs against core on each backend), and a forum account linked by code entry against a real core, confirmed by asking core rather than the page |
| 8 | connector-plan + full-stack battery | **Complete — gate passed.** `reaper test` green on both backends in one session (run 13), and Plan renders link data for a player linked through the real flow. Battery covers: the latin1 axis asserted rather than assumed, astral-plane text round-tripped and compared, T7 fuzz against a populated deployment, T8 concurrency re-run in-session on both backends, T11 transcripts for `first-time-player` and `forum-first-user`, and the T5 browser suite with the 5xx watchdog armed on every non-injection pass. `bedrock-player` declined on the plan's own conditional — departure 10 |
| 9 | Simulated users | **Trimmed tier complete; gate met and now meaningful.** Run 12 green on both backends: three seeds × 400 actions, each reporting real work (6–8 links made, ~100 correctly refused), identical counts on both axes. Four-byte UTF-8 survives a round trip through a latin1 server. Shrinker and two nemesis classes deferred (departure 9). **One open lead:** `decisions-follow-the-rules` excluded pending diagnosis — DECISIONS 9.10 |
| 10 | Hardening and release | **Gate met — run 17 green.** Clean install on a fresh guest following only `docs/install.md`, ending in a real cross-platform link core confirmed after a restart, evidence in `out/install-gate/`. Full battery green on both backends in one session, with `t10` reading 1200 audit rows over 5 pages on each axis. Every accumulated narrowing listed below with its reason. Deliverables: credential rotation, audit export, the generated licence inventory, packaging, install docs, `doctor` final checks, `docs/threat-model.md`, Tier 10. The Phase 6 Discord manual smoke is **done** — see the Phase 6 row and DECISIONS 10.18; this row said otherwise for longer than it was true |

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

**Every Java module swept in Phase 10, and the tier is now RATCHETED.** One row
per module in `mutation-baseline.txt`; `mutationRatchet` fails a module whose
survivor or no-coverage count has gone up; it runs in `.reaper.toml`. Run 29 is
the first battery green with it in, `reaper exit=0` on both storage axes.

Run 30 is green on the re-measured `core` row (DECISIONS 10.46), and is the run
that matters for it: this batch changed `Storage.open`, which configures the
MariaDB pool, and nothing on the workstation runs MariaDB. All nine rows held
in the session with the same numbers the workstation produced, which is the
one-table claim in `mutation-baseline.txt` measured again rather than assumed.

| Module | Killed | Survivors | Uncovered |
|---|---|---|---|
| `core` | 68% → **84%** | 131 → **42** | 173 → **113** |
| `connector-discord` | 48% → **76%** | 28 → **1** | 114 → 71 |
| `connector-velocity` | 63% → **77%** | 16 → **3** | 63 → 47 |
| `connector-plan` | 70% → **92%** | 10 → **1** | 20 → **7** |
| `sim` | 53% → **74%** | 69 → **6** | 79 → 74 |
| `config` | 88% → **95%** | 6 → **3** | 6 → **2** |
| `connector-sdk` | 88% → **90%** | 3 → **2** | 9 → 8 |
| `policy` | 98% | 2 | 0 |
| `protocol` | 98% | 1 | 0 |

Survivors across the tree went from roughly 250 to **61**, and a growing share
of what remains is equivalent mutants recorded at the line rather than gaps.
DECISIONS 10.28 through 10.45.

**`connector-discord` swept in Phase 10: 48% to 76%, and test strength 82% to
99%** — 28 survivors to one equivalent mutant, reasoned about at the line so a
later sweep skips it. The role decisions came out of the live-platform adapter
behind a `GroupEffector`-shaped seam (`GuildRoles`), which took the untestable
count from 114 to 70. What remains uncovered is command building, the JDA
lookups, `Main`, and `ScriptedDriver` — which the full-stack tier does exercise,
out of process, where PIT cannot see it. DECISIONS 10.28 and 10.29.

The consequential finds were an assertion pair where one string contained the
other — so `/whoami` could mark every identity unverified and satisfy both — and
`DiscordConfig`, whose fail-mode default of CLOSED was executed by nothing.

**`policy` swept again in Phase 10 and taken from 80% to 98%** — seventeen
survivors to two, both of which are equivalent mutants and are reasoned about in
DECISIONS 10.19 so a later sweep skips rather than rediscovers them. The
consequential find was `PolicyOverride.matches` returning true unconditionally:
an allow-override written as a one-person exception, applying to the whole
deployment, with no test noticing.

The 182 are the number that matters — a test ran the line, its behaviour
changed, nothing failed. What has been fixed and what has not is in
DECISIONS 8.20; the short version is that the security- and correctness-relevant
survivors are dealt with (replay protection, link-code entropy, platform-kind
learning, the asymmetric link path, outage reporting) and the tail is not.

Two caveats on the numbers, both of which make them flattering rather than
harsh: MariaDB-only paths never run on this machine, and the seeded fuzz tier is
excluded by tag, so a mutant killed only by fuzzing is counted here as
surviving.

Neither tool is gated on a threshold and neither is wired into `check` — but the
Java tier is now **ratcheted**. `mutation-baseline.txt` holds one row per module
and `mutationRatchet` fails a module whose survivor or no-coverage count has
gone up, which is a different question from a threshold: nobody has to defend a
number, and nobody can drift past one. It runs in `.reaper.toml`, where slow
things belong, because until Phase 10 the Java tiers ran only when somebody
remembered to ask. DECISIONS 10.30.

## What runs today

`./gradlew build` compiles every Java module and runs both test tasks — the
ordinary one and `charsetHostilityTest` — green, including the seeded fuzz tier.
**983 tests on the workstation, 1055 in a reaper session**, where a real MariaDB
is reachable and both storage backends are exercised; `:core:test` alone is 402
here against 471 there. Those are the last figures this document recorded, not a
fresh count — the point of keeping both is the gap between them, because a local
build proves nothing whatever about the second backend.

The whole product runs. Identities link across platforms, gates decide, effectors
grant and revoke real roles and groups, and the audit log is append-only and
exportable. This paragraph read "nothing links anything yet — that is Phase 2"
from Phase 1 until 2026-09-07, by which point a real cross-platform link had
survived five releases on a live estate.

The storage seam runs against SQLite on the workstation (MariaDB skips without
`SOULBIND_TEST_MARIADB_URL`) and against both in a session.

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

## The pre-push loop

Referenced from the gate table above, and missing from this document until
Phase 10 — a promise it made and did not keep.

```sh
reaper up          # create the session
reaper test        # sync, build, reset, run
reaper down        # destroy it
```

**`reaper test` does not create its own session.** It did once, which is why
every earlier note here says "`reaper test` green" as though that were the whole
command, and why `reaper list` showed nothing after a run. The current CLI
refuses instead:

```
reaper: no sessions for "soulbind". `reaper up` creates one (it is not part of
`reaper test`, which needs a session to already be there)
```

That is a better refusal than the old implicit behaviour — a session is a
machine, and creating one as a side effect of asking for a test run is the kind
of thing that leaves a VM behind when the run dies. But it means the loop is
three commands, and nothing in this repository said so.

`reaper down` is not optional politeness: the session holds a machine until its
TTL expires, and `reaper doctor` warns that a sweeper with no expired machine to
find has never been proven to work on this site.

What the run covers is the manifest in [`.reaper.toml`](../.reaper.toml): the
unit suite on both storage backends, the cross-language vectors, PHP unit and
mutation, the shell mutation battery and the group-check control, **the Java
mutation ratchet**, the operator tools, the clean-install gate, the forum tier on
both engines, and the full-stack tier on both engines.

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

The Phase 10 gate asks for every narrowing accumulated across the phases in one
human-facing list, each with the reason that covers exactly what it narrows.
This is that list, current as of Phase 10. Structural departures from the
specification live in the README departures table; these are the places a
check deliberately covers less than everything, and what buys each one.

**Resolved narrowings from earlier phases** (listed so their absence is a
statement, not an oversight): Phase 0's "run verb exits 0 with no battery"
ended when the battery landed; "storage/transport/capability guards deferred to
Phase 1" ended when Phase 1 shipped them with fixtures.

1. **`PlanCheckWalkerGuardTest` skips without `python3`** — probed
   `assumeTrue`, verified in both directions (6 run/0 skipped with, 6 run/6
   skipped without). The Temurin build container has no python3; the property
   is asserted *harder* on the guest host by the shell mutation battery
   (thirteen mutants against the guard's single read), which runs every
   `reaper test`. Scope: that one guard class, in that one container.
2. **The mutation batteries (PIT, Infection) are not in `check`** — invoked
   explicitly and on the `run` verb, because a full mutation pass per
   workstation build would make `./gradlew build` minutes long and get
   worked around. Scope: when they run, not whether; the run verb runs them
   every session. Known survivor tail recorded under "Mutation coverage".
3. **`fuzz-live.sh` exits 0 when the deployment is unreachable at start** — an
   unreachable stack is the `up` stage's failure, already reported; a second
   failure from the fuzzer would double-report one defect. Scope: the
   reachability probe only; once reachable, every failure fails.
4. ~~Two sim invariants are excluded~~ — **both closed, and the inert list is
   now empty.** `decisions-follow-the-rules` was re-enabled and checked both
   ways (9.12). `redeemed-codes-stay-redeemed` was closed differently: the
   property is now covered by a `DOUBLE_REDEEM` **action**, which attempts a
   spent code deliberately so the executor knows the answer must be a refusal
   — rather than a checker probing and corrupting the graph it asserts about
   (10.22). The runner prints "every invariant is answerable against this
   core".
5. **Sim shrinker and two nemesis classes deferred** — departure 9. The tier
   reports real work per seed (`didWork()`), so the deferral cannot make a
   vacuous run look green.
6. **`bedrock-player` journey not implemented** — the plan's own conditional
   (departure 10): Geyser is not in the composed stack; Floodgate identity
   handling is covered at Tiers 1/4.
7. **`guards` and `sim` are outside the licence inventory** — they ship
   nowhere, so they have no third-party disclosure obligations.
   `LicenceInventoryGuardTest` holds the exclusion to exactly those two names.
8. **`fuzz` group excluded from PIT's mutable paths** — mutating the fuzzer
   mutates the test bed, not the subject. Scope: the fuzz sources only.
9. **`harness/fullstack/fuzz-live.sh` signs for itself** rather than through
   `tools/rpc.sh` — it sends deliberately malformed bodies that `rpc.sh`
   refuses before they reach the wire. Scope: that one script; `redeem.sh`
   lost its copy for exactly this reason.
10. **`core-env.sh` container mode is unverified on the workstation** —
    FreeBSD, no podman. First execution is the session run; the host mode is
    exercised on every workstation invocation. Stated in the script and in
    DECISIONS 10.5.
11. **`harness/install-gate.sh` has never executed end-to-end** — it follows
    an Ubuntu document on an OS this workstation is not; the pieces
    (tarball unpack, doctor, register, serve, link, export) were rehearsed
    individually against the real artifact (DECISIONS 10.8). First full
    execution is the session run, where it is a hard gate, not a skip.
12. ~~The Phase 6 Discord manual smoke is outstanding~~ — **done.** Run
    against a real bot and server; both link directions, both effector
    directions, and the refusal paths. What it found is in DECISIONS 10.18.
13. **The clean-install gate resets soulbind's own footprint before installing,
    and leaves prerequisites alone.** It removes the unit, `/opt/soulbind`,
    `/etc/soulbind`, `/var/lib/soulbind` and the service user, so *every* run
    is genuinely a clean install rather than only the first on a fresh guest.
    It does **not** remove the JRE: that is a prerequisite, not part of
    soulbind's install, and doc §1 handles finding one already present.
    Removing it would re-download a toolchain each run to prove nothing.
    Narrowing: the gate proves a clean install of *soulbind* onto a machine
    whose prerequisites may already be satisfied (DECISIONS 10.15, 10.16).
14. **A rule change revokes but does not grant.** Core emits `rule.changed`
    and connectors now reconcile: each asks `decide` about the accounts on its
    own platform holding its role, and takes the role off whoever no longer
    qualifies. It does **not** hunt for people who newly qualify — that would
    mean asking core about every member of the platform, and nobody is wrongly
    holding anything in that direction; they get the role on their next
    `requirements-met`. Revocation is the half that cannot wait (DECISIONS
    10.21).
15. **`t10`'s five-hundred-error watchdog covers its own requests only** — it
    cannot observe 5xxs served to other clients between stages. Scope: the
    stage's own traffic; the forum tier's watchdog covers the browser suite
    the same way.

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

These three are the whole list. Four further bullets stood here from Phase 0
until 2026-09-07 — no protocol implementation, an empty `vectors/`, an empty
`harness/`, and a placeholder `[run]` verb. All four were false by Phase 8 and
none was removed when it stopped being true: `docs/protocol.md` is 610 lines held
to the code by a guard in both directions, `vectors/` carries the golden vectors
both language implementations are checked against, `harness/` carries nine tiers,
and `[run]` invokes `harness/fullstack/run.sh` on both storage axes.

---

## Phase 1 gate — met

The specification's gate is: *fuzz clean on both backends; matrix green; a
registered connector can hello + heartbeat over both transports.*

| Gate item | State |
|---|---|
| Matrix green | **Met.** 220 rows: every operation × every capability, plus none, all, suspended, and no credential |
| Fuzz clean on both backends | **Met.** Clean on SQLite on the workstation, and clean on SQLite *and* MariaDB in a reaper session, where `reaper test` stands up a digest-pinned database. Both fuzz tiers print `backend=MARIADB` with their seeds |
| hello + heartbeat over both transports | **Met.** Asserted end-to-end against a running server on every available backend, over the socket and the signed request transport |

**All of Phase 1's deliverables have since landed**, each verified in the tree
rather than assumed: both transports in `core/transport/TransportServer` (the
WebSocket authenticating at connect, the webhook/poll signing every body) with
the pair named in `docs/protocol.md` §2; `hello`/heartbeat asserted end to end
over both; `audit.query` in the protocol's operation table, and the export tier
reading 1200 rows over 5 pages on each storage axis; `core/cli/Bootstrap.java`
and `core/cli/Doctor.java`; the wire-conformance suite in
`core/src/test/.../transport/` and `protocol/src/test/` (`AbsentFieldTest`,
`GoldenVectorTest`, `WireVocabularyTest`, and one `*WireTest` per operation); the
audit-immutability guard, which is in the guards table below; T6 migration
idempotence, green against MariaDB in consecutive sessions; and the T7 fuzz
harness, which prints its backend with its seed.

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

**The Phase 6 manual smoke is NOT outstanding.** It ran, against a real bot and
a real server: `/link` typed by a person, the code redeemed on another platform,
core confirming the link, `/whoami` reading it back, and the role grant and
revoke proven live afterwards. It found three defects — a documented capability
grant missing `link-state-reader`, commands registering globally while a guild
was named, and `subject.requirements-met` being emitted by nothing at all, which
meant the effector half of the product could not fire in any deployment. All
three are fixed. DECISIONS 10.18.

This paragraph described it as pending for some time after it had been done,
while the Phase 6 row and narrowing 12 both recorded it as complete. A document
that contradicts itself is worse than one that is merely out of date: each half
looks authoritative on its own, and a reader who finds this half first stops
looking.

**The Discord bot token.** The smoke ran against a token written to a scratchpad
file, which was to be regenerated before the connector went live. `connector-
discord` has since been live on the estate through five releases, so either the
token was rotated and nobody recorded it here, or the smoke token is still in
service. **This document cannot say which, and that is the finding** — it is the
owner's to confirm. Said "see below" here from 2026-08-16 to 2026-09-07 with
nothing below to see; the item lived only in `HANDOFF.md`, a fourth document
that drifted out of agreement with this one and has since been removed.

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

## Phase 8 — complete, gate met

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

### The gate, met

Run 13: `reaper test` green on both storage backends in one session, with Plan
rendering link data for a player linked through the real flow. The rows below
were written while the gate was open and are updated to what closed them.

| Gate item | State |
|---|---|
| `harness/fullstack/` compose + stage scripts (§12) | **Green on SQLite, end to end.** `run.sh up migrate journeys down` passes against a real stack: Paper and Velocity up, a mineflayer client refused by the gate, admitted by an override, running `/link`, redeeming, and admitted — then migrate against the live used database, the Tier 11 transcript, and a teardown that genuinely stops all three ports. JDK and Node are now checksum-pinned so the same script runs on the guest. MariaDB axis and the `.reaper.toml` wiring outstanding |
| `.reaper.toml` run verb becomes real | **Wired** — the run verb now calls `harness/fullstack/run.sh` for both storage axes, MariaDB on its own database so the tier's rows cannot be mistaken for the unit suite's. Green in a session is not yet claimed |
| Run images digest-pinned | **Done, with departure 8** — the container images are digest-pinned; Paper, Velocity, the JDK and Node are SHA-256 pinned jars and tarballs, because that is how they ship |
| T6 staged battery, both backends, MariaDB started latin1 | **Met.** Migration idempotence lands as the `migrate` stage, in-session against a used database, mutation-checked. The latin1 axis is asserted rather than assumed, astral-plane text is round-tripped and compared, and state is built through the real flows with no backdoor (`harness/README.md`) |
| T7 fuzz against the real deployment | **Met** — fuzz runs against a populated deployment, printing its backend with its seed |
| T8 scenarios re-run in-session | **Met** — concurrency re-run in-session on both backends |
| Plan pages render link data | **Demonstrated in a session.** Plan renders `linked=true`, `linkStatus="linked"`, `platforms="game, harness"`, `proof="link-code"`, the subject id and `linkedSince` in milliseconds, for a player linked by a real client running `/link` and redeeming a code — with Plan's own log reporting `Registered extension: soulbind`. mariadb axis only (DECISIONS 8.15). The check has since been rewritten to assert values rather than labels and to cover the server-wide providers, which the passing version did not (8.16) |
| `journeys` emits the T11 evidence directory | **Met for the two journeys that are in scope.** `first-time-player` and `forum-first-user` both emit real per-step transcripts, the second from the forum tier where the forum actually runs (departure 10). `bedrock-player` is declined on the plan's own conditional — §11 Tier 6 makes it contingent on Geyser being in the composed stack, and it is not. `COVERAGE.md` is still generated from the recorded outcomes. No screenshots |
| T5 suite against the real stack, 5xx watchdog on | **Met** — the browser suite runs with the 5xx watchdog armed on every non-injection pass; 5 of 5 specs on each forum engine at run 36 |
| Plan pages render link data for players created through real flows | **Met** — this is the gate's second clause, and run 13 is where it closed |

## Where to pick up

Written at the end of a working session so the next one does not have to
reconstruct it. True at `6c80500`, `v0.1.5-1`.

### The state of things

**Every phase is complete and every gate is met.** `0.1.5` is released, and all
four components — core, `connector-velocity`, `connector-discord`,
`connector-flarum` — run on the first estate, each version read back from the
running process rather than from what was installed.

**Enforcement is ON, and this document said otherwise for longer than it was
true.** It claimed "no rules exist, so every gate answers `allow` / `no-rule`"
and that one cross-platform link existed. Both were stale before the 0.2.x work
began: a `post` rule requiring a verified chat identity was written on
2026-08-25 and has been refusing real people ever since, and the graph holds six
subjects across thirteen identities. Corrected 2026-09-10, from the deployment
rather than from memory.

As of 0.2.1 the estate runs five rules: `post`, the two link gates, and the
hysteresis pair behind an activity role. Three roles are granted by soulbind.
The proxy's join gate remains unset — `gate.join` and `effector.group` are still
empty, so nothing gates a login. The estate's MariaDB is untouched: soulbind
runs on SQLite and has never opened it, and the playtime reporter reads the
dashboard's schema over the dashboard's own pooled connection.

The first rule is long past; what 0.2.x added was the first rule that depends on
something other than identity.

**Two defects reached the estate and were found by deploying, not by testing.**
`measure.identity_ref` was `VARCHAR(191)` where a legal reference reaches 256 —
invisible on SQLite, error 1406 on MariaDB — caught by reading before the axis
ran. And `measure.report` emitted its gate transitions for only the identity it
measured, where the override operations beside it expand to every identity the
subject holds; since effectors route on the reference and each acts on its own
platform's kind, a measurement on one platform could never move a role on
another. Sixty measurements landed, three above the threshold, and the chat
effector correctly discarded every one. Fixed in 0.2.1.

**A property worth writing down, because it surprised the deployment.** A rule
change revokes but does not grant (narrowing 14), and a re-reported measure that
does not cross a threshold emits nothing. So enabling a rule grants nobody
anything: every subject who already qualified stays ungranted until something
about them changes. Backfilling meant driving each gate DOWN and back up — a
deny override, then removing it — which emits the transition the effector needs
and leaves no override behind. It is a legitimate one-off and a bad standing
mechanism, and the bounded sweep that would replace it is still deferred.

### Next, in the order I would do it

1. **The Discord bot token — the owner's, and it blocks nothing until it does.**
   The Phase 6 smoke ran against a token in a scratchpad file that was to be
   retired. The connector has been live through five releases since. Confirm it
   was rotated, or rotate it. See the note under "Outstanding, and needing the
   owner".
2. **The first rule, on one gate.** This is the first moment soulbind can refuse
   a real person entry, and the order matters. The deployment steps that come
   before it are already done on the estate — backed up, core alone, connectors
   registered one at a time with the narrowest capability set that works, each
   deployed in read-only posture. What is left is the rule itself:

   - **One gate.** Not a posture change everywhere at once.
   - **Rollback ready before the rule, not after.** Removing the rule restores
     the previous behaviour immediately, because no rule means allow — that is
     the whole rollback, and it only works if you know which gate to clear.
   - **What does not undo itself** is any role or group an effector granted.
     Those are real changes in LuckPerms and Discord. Know that list *before*
     writing the rule, so removing them is a list and not an investigation.

   Three things to watch once a rule exists. **Fail-closed and fail-open sit
   next to each other:** a connector whose core is unreachable *denies*, while
   core with no rule *allows*. Those are not contradictory, but together they
   mean core being down is a user-visible outage on any gated action — so know
   which gates are gated before gating them. **`connector.rotate` has no overlap
   window:** the old credential stops working on the next request, which is
   correct for a leak and hostile mid-session. **Overrides emit gate
   transitions:** an override set by hand grants roles and groups and removing
   it revokes them. It is not a quiet annotation.
3. **Wire the audit export before it is needed.** `tools/audit-export.sh` exists
   and the log is prunable. This is cheap now and unrecoverable later.
4. **The storage-backend evidence gap**, under "Known gaps". The battery runs
   both backends and the only artefact proving it that reaches the workstation is
   the fuzz tier's seed line. Removing one `@Tag("fuzz")` would leave the battery
   green with nothing showing the second backend ever ran — which is the same
   shape as every defect the first deployment found.
5. **`decisions-follow-the-rules`**, the one open lead from Phase 9, excluded
   pending diagnosis. DECISIONS 9.10.
6. **`PlanCheckWalkerGuardTest`'s six guards skip in a reaper session** and run
   only on the workstation, on `assumeTrue(pythonAvailable())`, because the JDK
   image has no Python. A guard that skips where the battery runs is the wrong
   shape by DECISIONS 7.2's own argument.

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

## Phase 10 — complete, gate met

### Credential rotation — landed

Until this, the only way to retire a leaked connector credential was to register
the connector again under a new name, which left the leaked one working. That is
the opposite of what an operator wants in the minute they discover a leak.

`connector.rotate` takes a connector name, mints a replacement, and **replaces**
the stored hash. Deliberately:

- **No overlap window.** The old credential stops authenticating on the next
  request. A grace period is exactly what is not wanted when the reason for
  rotating is that somebody else holds the credential. The schema makes this
  structural rather than a matter of care — `connector` holds one
  `credential_hash` column, so there is nowhere for a second live credential to
  live.
- **Audited before the plaintext is returned.** A rotation that reached the
  caller and never reached the log is a credential change nobody can account for
  afterwards, and this is the operation most likely to be run during an incident
  review — which is when the log is read.
- **`config-management`**, like the other `connector.*` operations. A connector
  that could rotate its own credential could rotate somebody else's, and the
  case rotation exists for is a credential in the hands of whoever is calling.
- **No CLI verb.** `soulbind` keeps its three verbs; rotation is an operation
  under the same authorization table, per the reason already recorded in
  `Main`'s javadoc. An admin can rotate *its own* credential — the request
  authenticates before the handler runs — and is cut off the instant the
  response is written, so a lost response means re-registering rather than
  rotating again. That is asserted, not assumed.

Five tests, each mutation-checked against the real tree: a rotation that reports
success and changes nothing fails two of them by name; an audit row without the
connector name fails a third; a nameless "not found" fails a fourth.

### Audit export — landed

`audit.query` was bounded at 1000 rows and always should be: an unbounded read
from an authenticated endpoint is a way to exhaust memory. But the bound was
**silent**, so a caller asking for everything got the ceiling with no way to
tell that answer apart from the whole log. The deliverable was therefore not
"add an export" but "make a truncation distinguishable from an ending".

Every `audit.query` response now carries `more` and `lastSequence`, and the
request accepts `afterSequence`. Together they are the export: pass the cursor
back until `more` is false. On **every** response rather than a dedicated export
operation, because otherwise every other caller keeps the silent ceiling.

The cursor is a sequence, not an offset — `seq` is monotonic and audit rows are
never mutated or deleted, so a page cannot shift under a reader mid-export. It
also makes the export resumable across runs, which is what makes it a nightly
archive rather than a whole-log dump every night.

`tools/audit-export.sh` is that loop, writing JSON Lines. It is a protocol
client holding an admin credential, not a management command reading the
database — `soulbind` keeps its three verbs.

The ceiling was already costing something: `SdkCore.auditSince` in the
simulated-user tier detected it and refused to conclude, which capped how long a
Tier 9 run could be. It pages now.

Tested by a control against a real core plus three mutants of a core that lies
about its paging. One of the three, `truncate-silently`, is listed as
**uncatchable** — a core claiming the log ends after one page is
indistinguishable from a one-page log, and no client-side check separates them.
What is asserted instead is that the tool reports how little it got. The
`freeze-cursor` mutant found a real hole: the first version guarded only the
empty-page case, so a full page with a frozen cursor looped forever, rewriting
the same rows into the archive.

### Two smokes that were never wired to anything

`harness/credential-smoke.sh` had never run automatically anywhere, so it could
rot between the sessions that invoked it by hand. It and the new export smoke
are now a `reaper test` stage, together well under a minute.

Wiring them in surfaced why they had not been: the reaper guest host has podman
and **no JDK**. `harness/tools/core-env.sh` picks between a host JDK and the
pinned toolchain container so the same script runs on both.

**Container mode is unverified until a session run** — this workstation is
FreeBSD and has no podman, so only the host path has executed here.

### Three copies of the canonical signing string, now one

`tools/rpc.sh` (moved there from `harness/`) says it is the single
implementation of the signing. It was not: `harness/fullstack/redeem.sh` held a
full duplicate with no recorded reason, and now calls it.
`harness/fullstack/fuzz-live.sh` keeps its own **because it sends deliberately
malformed bodies that `rpc.sh` refuses before they reach the wire** — that is
the whole narrowing and it covers exactly the one script.

### The licence inventory — landed

§16's generated third-party inventory, which `NOTICE` claimed from Phase 0 until
Phase 8 without existing (DECISIONS 10.1). The `licenceInventory` task runs in
`check` for every distributed module and ships `THIRD-PARTY.txt` beside
`LICENSE` and `NOTICE` in every distribution.

It inventories the **resolved runtime graph**, not the catalogue: the catalogue
declares about a dozen libraries and core's graph is forty-two artifacts. Each
licence is read from the artifact's own POM, walking the parent chain. Three
things fail the build rather than being guessed — an artifact with no licence
anywhere, a licence not allowlisted, and a copyleft artifact not marked as
shipping unbundled.

A dual-licensed artifact fails until the project records which licence it takes
it under. The first version took whichever the POM listed first, which elected
EPL-2.0 for Jetty when §16 says Apache-2.0 in as many words.

**It found two things on its first real run, neither visible from the
catalogue:**

- **JNA 4.4.0 (LGPL-2.1)** in the Discord connector, via JDA's *voice* support.
  The connector sends messages and applies roles and never touches a voice
  channel, so the audio dependency is excluded rather than shipped in `lib/`
  with a relink obligation attached.
- **trove4j (LGPL-2.1)**, also via JDA, which uses it for its entity cache. It
  cannot be excluded, so it ships as its own jar in `lib/`. It is not in
  `libs.versions.toml` because nothing here declares it — exactly the transitive
  copyleft artifact a hand-maintained `NOTICE` never mentions.

### Packaging — landed

Core and connector-discord ship as **distributions**, `bin/` plus `lib/`, not
fat jars — departure 11. §16's rule against bundling a copyleft artifact then
holds by construction rather than by an exclusion list, and the licence
inventory had just demonstrated that the graph contains copyleft nobody knew
about. `ServiceDistGuardTest` asserts the property against the built tree.

connector-velocity and connector-plan are single shaded jars as §14 says,
because a host loads one file out of `plugins/`. Their dependencies are
**relocated** into `dev.soulbind.shaded`, so the host's own copies cannot
collide with ours. `PluginJarGuardTest` reads the zip and asserts relocation
happened — in both directions, since a jar bundling nothing also contains no
unrelocated Jackson — and that the service files were renamed to match, which is
the failure mode with no symptom.

The composer package now carries its own `LICENSE`, byte-identical to the
project's, because composer installs a package by copying its directory and a
recipient never sees the repository root.

systemd units, sample configs and sample secret files ship inside each
distribution, scoped per module.

### `docs/install.md` — landed, and it found two defects by being followed

Its commands were run against the real archive rather than trusted. Two were
wrong: `subject.inspect` takes `platformKind`/`platformId`, not `subjectId`, and
`distTar` produced an uncompressed `.tar` while the document's first command
said `tar -xzf`. The second was fixed on the build side.

**A third defect fell out of it.** Following the document registered two
connectors and exported the audit log, which came back empty: registering a
connector was never audited, while rotating one was — so the log could say a
credential had been replaced with no record of it being created.
`Bootstrap.register`'s javadoc had promised that row since Phase 1.

### The proxy group effector — closed, run 25 green on both axes

The `groups` full-stack stage links a player through the real flow and then
reads **LuckPerms' own JSON storage** for the group. It was red on every run
from 21 to 24 and was right every time; run 25 is green on both storage axes,
and the whole battery with it (`reaper exit=0`, no stage failed, no mutant
survived).

Where the chain stands:

| Link in the chain | Proven |
|---|---|
| LuckPerms loads on the proxy | yes, run 22 |
| soulbind sees it across the plugin classloader boundary | yes, run 22 (`@Dependency`) |
| the resolver builds a real effector | yes — `group sync active` requires it |
| the drain is scheduled | yes |
| core emits `subject.requirements-met` | yes, run 24, from `override.set` |
| the drain applies it | yes, run 24 |
| the group reaches LuckPerms | yes, run 24 |
| the stage can read that it did | yes, run 25 |

The stage's smoke admits the player with an operator override so they can run
`/link`. That identity therefore already satisfied the gate before the link, so
the link produced no transition — and `override.set` emitted nothing at all, so
nothing had told the effector when the override was set either. DECISIONS 10.26.

Run 24 closed that: the group is in LuckPerms' storage. The stage still reported
red, because it matched `group.<name>` — a form LuckPerms' JSON storage never
writes. It now parses the file through `holds-group.sh`, which has a real
recorded LuckPerms file as its control and seven mutants of it. DECISIONS 10.27.

Overrides now emit gate transitions on both set and remove; `override.remove`
exists at all for the first time; and an override that *expires* is deliberately
excluded from what effectors are told, for the reason grace already is.

### The mutation tail — closed as far as the fixed configuration allows

`core` 131 survivors to **47**, `sim` 69 to **6**. Three real defects came out
of the last of it, all of which report success while doing less than they claim:

- **A credential line with no name was accepted.** `Runner.readCredentials`
  tested `equals < 0`, so `=secret` parsed as a credential filed under the
  empty string, matching no actor. The run assembles a cast one principal short
  and reports clean about work it never did.
- **A closed store still served reads.** `Storage.close()` shuts the write
  executor and closes the pool; the only test asserted a *write* failed
  afterwards, which the executor's shutdown alone produces. Nothing proved the
  pool closed, so every open leaked one.
- **`subject.inspect` could report an unproven identity as proven.** Reachable
  only through the repository, because every wire path marks an identity
  proven — which is not a reason to leave it: the column is nullable,
  `PolicyEngine` reads `isVerified()`, and the page exists to answer which
  accounts still need proving.

Three single lines in `Storage` were each deletable with nothing failing:
foreign keys, without which every constraint in the schema is decorative; WAL,
without which SQLite locks out every reader for every write; and the write
thread's daemon flag, without which a CLI that forgets to close hangs at exit.

What remains is recorded at the line as equivalent or unkillable rather than
chased. DECISIONS 10.44.

**And then the count would not hold still.** Tightening `core`'s baseline row
gave 47, 43 and 46 survivors across three runs of an unchanged tree, with
TIMED_OUT at 7, 7 and 10 — so not the SURVIVED/TIMED_OUT drift the baseline
header already warns about. Diffing mutant *identities* rather than counts,
which is the only way to see it, every mutant that moved was in `Storage.open`
and nothing else in the module moved at all: the two credential branches, the
SQLite pool size, and the write-executor ternary. Each changes only how SQLite
behaves under concurrent writers, so the sole witness to four load-bearing
decisions was a race, and its verdict was a coin flip.

The fix is a seam rather than a number. `Storage.open` now takes those
decisions in `poolConfig` and `serialisesWrites`, both static and both
returning something inert — a `HikariConfig` is a value object until a pool is
built from it — and `PoolConfigurationTest` reads them back with no server, no
connection and no race. Fourteen mutants confirmed killed by hand.

One seam was not enough. Two further runs gave 44 and 42, and the mover was
`open`'s ternary — the line that *applies* the decision. Deciding correctly is
worth nothing if `open` wires the answer up backwards, and that was still only
observable by racing a real database and hoping to lose;
`Storage.writesAreSerialised()` reads it off the built store instead. The shape
generalises: extracting a decision leaves a smaller one behind — whether the
extracted answer is used — and that one is usually an accessor away from being
deterministic too.

That retires a claim made one entry earlier: 10.44 recorded those credential
branches as needing "a backend taking credentials" to distinguish. The
*connection* needs one; the *decision* is a pure function of its arguments, and
was entangled with a live server only because nobody had separated them.

A third instance turned up in another file. `LinkingService.redeem`'s
already-redeemed return kept changing status; applying the mutant by hand and
running the whole of `core` three times showed it **surviving every time**. The
deterministic-looking test that appeared to cover it is answered by a friendly
pre-check further up — the line PIT mutates is the atomic claim losing, which
happens only when a second redeem gets past that pre-check before the first
commits. `LosesTheClaim` constructs that state instead of racing for it.

Three instances in one sitting, in two files, is a pattern rather than a
coincidence: **a branch whose only witness is a race has no witness, it has a
coin.** It reads as covered and counts as covered, and the mutation report
cannot say otherwise because the report is the thing that keeps changing. The
tell is a mutant whose status moves without the tree moving. DECISIONS 10.46.

### The gate description, made reachable — landed

`gate.description` had been in the schema since the first policy migration and
in the specification's §8 table, and nothing could write to it: both production
call sites passed `null`, and no wire message carried one. Not a column written
and never read — a column, a repository parameter and an interface signature no
caller could reach.

`rule.set` now carries an optional `description`, and `rule.get` returns it
alongside `registeredBy`. No new operation: the moment somebody writes a rule
is the moment they know what the gate is for, and release-hardening is the
wrong week to widen the authorization matrix. The cost, stated rather than
hidden, is that a gate no rule governs cannot be documented.

The care went into one hazard with two opposite failures, because a gate is
re-declared on **every** `decide`. Insert-only — which is what `gateSeen` was —
accepts a description for an existing gate and stores nothing, and by the time
an operator documents a gate the row has been there a while, so that is the
normal case rather than an edge one. An unconditional update fails the other
way: the next permission check, milliseconds later, overwrites the note with
the `null` that `decide` passes, and the operator concludes the save did not
work. The update therefore runs only when a description was supplied, and
`registered_by` is deliberately not in that statement — it means who declared
the gate *first*.

`registeredBy` is response-only and asserted to be: provenance a caller can
assert is not provenance. Ten mutants confirmed killed by hand, including both
halves of the hazard. DECISIONS 10.47.

The remaining ten never-read columns are settled and need nothing: five are
forensic timestamps you would read with a SQL client, three duplicate an audit
row written on the next statement, and two are `registered_by` on `gate` and
`platform_kind`, which have no second copy because neither registration path
writes an audit row. DECISIONS 10.45.

### The migrate check was deciding by coin flip — fixed

Run 31 reported `MIGRATIONS ARE NOT IDEMPOTENT on sqlite` against a commit that
touched neither migrations nor the harness. It was right that the database
changed and wrong about what changed it: the only difference was
`event_cursor.position` 4 → 7, with `updated_at` five seconds after the first
sample. Flyway does not write `event_cursor`. The event drain does.

The check runs against a live deployment on purpose, and fingerprints the row
contents of every table under 200 rows — also on purpose, since a repeatable
migration that rewrote operational rows would otherwise be invisible. Nobody
had noticed the consequence: the two fingerprints are taken about three seconds
apart while core is serving, so the verdict depended on whether the drain
happened to pause. It passed for fifteen sessions because it always did, until
it did not. That is 10.46's pattern arriving from the other direction — there a
race produced false passes, here a false failure.

The fix reduces nothing that is compared. The check now samples until two
consecutive fingerprints agree before it starts, takes one more sample on the
failure path, and reports **exit 4 — "would not hold still"** as explicitly not
an idempotence failure. The script already had this vocabulary: statuses 1, 2
and 3 exist because the first session run reported "not a no-op" when nothing
had been migrated at all.

`mutation/migrate-selftest.sh` is the test that would have caught it — a
control, an absent database, and a database under continuous write, verified
green against the fix and failing against the previous fingerprint with run
31's exact wrong verdict. DECISIONS 10.48.

**Run 32 is green with it in**, `reaper exit=0`: both storage axes, every stage,
the install gate, all nine ratchet rows, and the self-test running as a stage of
its own. It is also the first run to exercise the MariaDB axis since the gate
description landed — run 31 died at the sqlite `migrate` stage, so the second
backend never ran at all that session, and 10.47 added an `UPDATE` no workstation
test can reach.

### Runs 33 and 34 — the version derivation, on hardware

**Run 33 failed**, `reaper exit=1`, 1m26s into the build half and before the
battery started. `PluginJarGuardTest` refused a jar built as
`0.0.0-unversioned`. The derivation was right and the guard was in the wrong
place: the guest builds inside a digest-pinned Temurin image with no `git`
binary — a probe against a live guest returned `PROBE_GIT_BIN=none` — so it
correctly answered that it did not know its version, and a release-time
assertion failed the session for it. DECISIONS 10.53.

**Run 34 is green**, `reaper exit=0`: both storage axes, every stage, the
install gate, the Infection run, the browser evidence and the ratchet.

What it was run for was `stack.sh`: `copy_the_plugin_jar` replaced two
`cp .../build/libs/*.jar` lines, and it reported exactly one jar on each of its
three calls. **The stale-jar case it exists for was not exercised here** — run
33 had already swept the guest's `0.1.0` jars before it failed, so the directory
was clean by the time run 34 looked. That path is verified on the workstation at
zero, one and two jars; on the guest, only the no-false-positive half is.

Two asymmetries, both pre-existing and neither caused by this work:

- `sqlite` runs eight stages, `mariadb` nine. The extra is `plan`, which needs
  MySQL and cannot run on the SQLite axis — departure 10.
- `PlanCheckWalkerGuardTest`'s six tests SKIP in a session and run on the
  workstation: they `assumeTrue(pythonAvailable())` and the JDK image has no
  Python. Those guards do not fire on hardware. `DoctorFilesystemTest` skips one
  for a comparable reason (root can write anywhere), identically in run 32 and
  run 34 — verified by comparing the two sessions' collected results.

The guest builds artifacts named `0.0.0-unversioned`, which is now correct
rather than a defect. If the battery should exercise jars named as they would
ship, the mechanism is a `SOULBIND_VERSION` override passed in by the manifest;
nothing needs it today, because the names do not affect what the battery tests.

### Run 35, and the first live deployment

**Run 35 is green**, `reaper exit=0`, on the build-manifest change: both storage
axes, every stage, the install gate, Infection, browser evidence, the ratchet.
Same stage tallies as run 34.

**Core is deployed on the first estate**, which is not named here or anywhere in
this repository. `v0.1.2`, installed by following `docs/install.md` and nothing
else — every step worked as written, on a host with ten days of uptime and years
of accumulated state rather than a fresh guest. That document's gate had only
ever been proven on a clean VM.

- SQLite, not the estate's MariaDB. `docs/install.md` recommends it for a single
  core on one host, and it means soulbind never opens the database the forum and
  game live in. `SHOW DATABASES` after install and after two restarts is
  unchanged.
- Bound to loopback. `doctor` before first start: 6 ok, 1 warning, 0 failed.
- A full `mysqldump` of the estate's two live databases was taken first and
  verified — 165 `CREATE TABLE`s against 165 tables.

**`connector-velocity` 0.1.2 is deployed in read-only posture.** `gate.join` and
`effector.group` are both unset, so the join gate is disabled and no group is
ever granted. The plugin says so itself at startup — `soulbind ready; join gate
disabled` — which is the posture asserted by the running system rather than
claimed by its operator. Registered with `code-display,code-entry,
enforcement-point`: the grant `harness/principals.txt` records for a proxy
connector, and deliberately **not** `effector`.

Proven live, with the credential the plugin holds: a signed `decide` against
gate `join` returns `allow` / `no-rule`. An unconfigured gate allows, on the real
deployment, rather than only in the battery.

**Not yet proven:** the plugin's own transport to core. Nothing has driven it,
because with the gate disabled and no player online there is no traffic to
drive. The first `/link` typed by a person is what exercises it.

Two defects were found by deploying and are fixed: 10.54 (a running core could
not say which build it was) and 10.55 (a clean stop reported as a failure).
Neither was reachable from any test that reads the version from the build or the
unit from the source tree.

### Run 36, and the estate on 0.1.3

**Run 36 is green**, `reaper exit=0`: both storage axes, every stage, the install
gate, Infection, the ratchet, and — the reason it was run — **the forum tier on
both engines, 5 of 5 browser specs each**, including a member linking a forum
account by entering a code and core agreeing the link is real. The guest rebuilt
the frontend bundles for each run, so the committed bundles and a clean build
are both exercised.

**Every component of the first estate now runs `0.1.3`**, each version read back
from the running thing rather than from what was installed:

| | |
|---|---|
| core | `soulbind 0.1.3 listening on 127.0.0.1:7180` |
| connector-velocity | `Loaded plugin soulbind 0.1.3` |
| connector-discord | `connector-discord 0.1.3`, 3 guild commands, 0 global |
| connector-flarum | `soulbind-connector 0.1.3` on Flarum 1.8.19 |

All three hand-patches from earlier deployments are retired: the `SuccessExitStatus`
lines now come from the shipped units (a clean stop records `Deactivated
successfully`), and `/soulbind` keeps `default_member_permissions=8` **through a
full command re-registration**, which is what proves it comes from the code and
not from the API patch a bulk overwrite would have wiped.

Enforcement is still off everywhere: no rules exist, `decide` on `join` answers
`allow` / `no-rule`, `gate.join` and `effector.group` are unset on the proxy, and
`[effector] role`/`gate` are empty on Discord. The one real link in the identity
graph survived every upgrade.

`SHOW DATABASES` on the estate's MariaDB is unchanged throughout — soulbind runs
on SQLite and has never opened it.

**Flarum was upgraded 1.8.16 → 1.8.19 first**, because `connector-flarum`
requires `^1.8.19`. 21 packages, 0 installs, 0 removals, nothing to migrate.
Recorded here because the upgrade surfaced something worth keeping: running
`migrate` and `cache:clear` without `assets:publish` leaves the forum serving
untranslated keys in place of text — `docs/install.md` now says so.

**One environment constraint found by deploying:** the estate's PHP has no
`ext-zip`, so a composer *artifact* repository cannot be used there at all. The
published zip is verified against `SHA256SUMS`, extracted, and installed from a
path repository instead. Same bytes, no system package added to a production web
server.

### 0.1.5, and what deploying kept finding

Four releases were cut from a single live deployment, each fixing something no
test could have found because each was about the seam between a correct
component and the world outside it:

| | |
|---|---|
| 10.54 | a running core could not say which build it was — the manifest had no `Implementation-Version` |
| 10.55 | a clean `systemctl stop` was reported as a failure — 143 is not in systemd's default success set |
| 10.56 | Discord listed the administrative command to every member |
| 10.57 | the Flarum extension had never been installable — its compiled bundles were gitignored |
| 10.58 | the Flarum webhook endpoint was unreachable — registered behind CSRF it could never satisfy |

Every one of them had a passing test beside it. The version was computed
correctly and never written; the bundles were built correctly and never shipped;
the verifier was correct and unreachable. **A test that verifies a part says
nothing about whether anything can reach it**, and that sentence is the whole
lesson of the first deployment.

**The estate runs `0.1.5`**, all four components, each version read back from the
running process rather than from what was installed. No rules exist, so every
gate answers `allow` / `no-rule`; the one real cross-platform link has survived
five upgrades.

**A near miss worth recording.** `0.1.5` changed one display string in
`connector-flarum/composer.json`. That commit argued no test was needed — true —
and considered skipping the session as disproportionate, which was not. Editing
the manifest invalidates `composer.lock`'s content hash, and the battery's
`composer validate --check-lock` caught it eight minutes in. Skipping would have
published an extension that fails the check an operator's own composer runs. The
coverage was never missing; the reasoning about blast radius was.

**Two host-level things found by deploying**, neither soulbind's:
`ext-mbstring` was installed for PHP 8.3 while the forum ran 8.4, so Flarum's
announcement excerpt died on `mb_strimwidth`. `symfony/polyfill-mbstring`
declares `provide: ext-mbstring`, which is why composer accepted packages
requiring it — including ours — and why the gap stayed invisible. soulbind's own
three `mb_` calls were inside the polyfill's coverage. Now on the native
extension. The forum's PHP also has no `ext-zip`, so a composer *artifact*
repository cannot be used there; the published zip is verified against
`SHA256SUMS`, extracted, and installed from a path repository.

### Release mechanics — landed

`0.1.0`, tagged `v0.1.0`. **The version is not written down anywhere.**
`SoulbindVersion` derives it from `git describe`, so every Java module moves
together because none of them carries a number of its own — see DECISIONS 10.51,
which also records that the previous claim of "exactly one place" was wrong by
four. `connector-flarum/composer.json` deliberately carries no `version` either:
it is a Composer package installed from this repository's VCS tag, and Composer
takes the version from the tag — the same source of truth, reached a different
way.

Off a tag the answer is deliberately not release-shaped (`0.1.1-3-gabc1234`,
`+dirty` if the tree has edits); with no tag reachable it is
`0.0.0-unversioned`, which no reader can mistake for a release. Both plugin
jars stamp that number into `velocity-plugin.json`, which is what a proxy
actually reports, and `PluginJarGuardTest` asserts the two agree.

Four artifacts ship: the `core` and `connector-discord` distributions, and the
`connector-velocity` and `connector-plan` shaded jars. That closes the hole
`docs/install.md` had from the beginning — it told an operator to "take the
`core` distribution archive" and never said where from, because the build
produced the archives and they had no destination.

**Three GitHub Actions workflows**, in `.github/workflows/`:

| Workflow | When | What |
|---|---|---|
| `build` | every push and PR | `./gradlew build guards`, plus the cross-language vectors through their PHPUnit-free entry point, ordinary and hostile, plus the changelog check's own selftest |
| `mutation ratchet` | weekly, and on demand | every module against `mutation-baseline.txt`, with `--continue` so one regression does not hide the other eight |
| `release` | a `v*` tag | refuses a tag `CHANGELOG.md` does not name, rebuilds, refuses to publish if the tag and the built version disagree, and **publishes** the four artifacts with `SHA256SUMS` |

**CI is the cheap half arriving faster, and is not the gate.** It runs what
needs only a JDK and a PHP. The full-stack battery, the MariaDB axis and the
install gate need a real machine with a container engine, a database server, a
Paper world and a browser — they stay in a reaper session, and a green session
remains what a release is judged on.

**The changelog check — `.github/scripts/changelog-check.sh`.** Every release
from `0.1.3` to `0.1.5` shipped without a `CHANGELOG.md` section naming it: the
entry was written under `## Unreleased` in the same commit as the fix, the tag
was cut on that commit, and the step that turns the heading into a version was
never taken. `0.1.3`'s section was written retroactively; `0.1.4`'s and
`0.1.5`'s were not written at all until `b0f6164`. Three consecutive releases,
and nothing was looking.

It asserts two things and the second is the sharper: that a `## <version>`
heading exists, and that `## Unreleased` — if present at all — is empty, which
catches the entry that *is* written but under a heading that is not a version.
Run against the tagged trees, it refuses `v0.1.3`, `v0.1.4` and `v0.1.5`, and
passes `v0.1.0` through `v0.1.2`.

It is a script with fixtures rather than eight lines of YAML because the only
way to exercise a workflow step is to fire it, and firing this one means pushing
a tag, which publishes. `changelog-check-selftest.sh` runs two controls and six
mutants — the second control exists because a check that refuses everything
kills every mutant and asserts nothing. It runs in `build` on every push **and**
in `release` before the build, because a tag push does not trigger `build`, and
a check nothing verified is the "a check inside a task cannot say whether the
task ran" family this project has already paid for three times.

It is not a Gradle guard, and DECISIONS 10.53 is why: "the changelog names this
version" is a property of a release, the version is known only at tag time, and
a release-time assertion in a build-time guard fails the environment that has
least.

The release workflow **publishes rather than drafts**, at the owner's
instruction: pushing a version tag is the decision to release, and a draft made
it two decisions with the second easy to forget. The trade is stated in
DECISIONS 10.51 — a mistaken tag becomes a download immediately, against which
the full suite, the guards and the version check all run first, so a bad tag
fails the run rather than reaching a draft nobody reads.

All three workflows check out with `fetch-depth: 0`, because a shallow fetch
brings no tags and every artifact would then be named `0.0.0-unversioned`. The
cross-language vectors job stays shallow on purpose: it builds no versioned
artifact.

**Not published to Maven Central or Packagist.** Those need signing keys and
namespace ownership, and they are a separate decision rather than a bigger
version of this one. Depend on the SDK by building it.

## Narrowing in force from 11.1

A role granted on a **measure** survives the reporter going away. The observation
goes stale, `decide` correctly refuses from that instant, but no
`requirements-lost` is emitted — nothing watches the clock — so an effector
holding a standing role keeps it until the reporter returns and reports a low
value. The scope is exactly that: a role held after its evidence expired, caused
by the reporter's absence, corrected on the reporter's return.

It is not the grace case in disguise. A measure that is merely *below threshold*
emits normally, because the report that lowered it is a mutation and re-evaluates
in its own request. Only the reporter falling silent is uncovered.

Mitigated rather than closed: a reporter's cadence must be materially shorter
than the rule's `maxAgeSeconds`. Closing it properly needs a swept operation,
which is the timer this design exists to avoid.

## Narrowing in force from 10.26

An override carrying an `expiresAt` is not counted by
`GateEvaluator.satisfiedGates`, so no role or group is granted for it. It
narrows exactly one thing — what effectors are **told**. The gate itself still
honours a temporary override in full: `decide` returns `allow`, and a test
asserts it. The reason is the one already stated for grace: nothing in this
system re-evaluates on a timer, so a group granted for a one-hour override would
never be taken back.

## Phase 11 — measures, in progress

**What a measure is.** A named number a connector reports about one platform
account, carrying the window it covers and — from core's clock, never the
caller's — when it was recorded. Core never learns what the number counts.

**One observation per (identity, name), overwritten. Not a time series**, and
that is the decision the whole design rests on: storing samples would make core
compute windows, which forces retention, which forces a sweep, which is the timer
this project has twice refused to introduce.

**Reporting is a mutation**, bracketed with `GateTransitions` exactly as
`code.redeem`, `attest`, `identity.unlink` and `override.set` are. That is the
whole answer to "what re-runs a trailing window when nobody has done anything",
and it needs no scheduler: the population is bounded by construction, since one
report concerns one account.

**Hysteresis is two gates composed in the connector**, never state in core. A
high gate grants and a low gate revokes; the band between them is the two
directions the effector deliberately ignores. Each gate stays a plain threshold
and a pure function, so two callers asking about the same person still get the
same answer.

| Landed | |
|---|---|
| `config` | `TABLE_ARRAY` — a list of tables with a nested element schema, so unknown-key rejection recurses into elements |
| `connector-discord` | several role bindings, each with a direction; replaces the single `effector.role`/`effector.gate` pair |
| `policy` | `MeasureRequirement`, `MeasureObservation`, four refusal reasons, still a pure function |
| `protocol` | `measure-source`, `measure.report`, `measure.get`, `RuleView.measure` |
| `core` | `V8__measures.sql`, `MeasureRepository`, aggregation by strongest, `forget` on unlink |

| `connector-plan` | the reporter: population that drains, never zero on an unreadable source, its own `measure-source` credential |
| `guards` | the storage-seam exemption for one package, with a must-fail fixture proving it is one package and not the module |

**The reporter's two rules, because both prevent a silent mass revocation.**
Nothing is reported as zero because it could not be read — both halves of
`PlaytimeSource` answer with `Optional`, and empty means "say nothing about this
player", not "they played none". And the population each cycle is *everyone
active in the window ∪ everyone last reported non-zero*, which is what makes
revocation work with no core sweep and which **drains**: once somebody is
reported at zero they are dropped, because zero is below every threshold.

**Outstanding for the gate:** a reaper session. V8 is a schema change, so the
full-stack battery on both backends is mandatory rather than optional, and the
reporter has never run against a real dashboard. `connector-plan` is also not
deployed anywhere yet, so deploying it is a new install rather than an upgrade.

**Green in a session, on both backends** — run 37, every stage passing on the
SQLite and MariaDB axes, the forum tier 5 of 5 on each, the install gate, the
ratchet, and the `plan` stage rendering against a real dashboard for the first
time. `V8` applied to MariaDB with `success=true` in the Flyway history and the
live schema dumped back showing `identity_ref VARCHAR(256)` and all four
`measure_*` columns on `rule`.

**What the session found that the workstation could not.** `measure.identity_ref`
was `VARCHAR(191)` and had to be 256: a reference is `kind:id` over a 64- and a
191-character column, so a legal one reaches 256. SQLite ignores `VARCHAR` length
entirely, so every workstation run passed; MariaDB raises error 1406. It was
fixed before the axis ran, and the axis is what proves the fix.

**A known gap this exposed, recorded rather than closed.** Nothing generally
asserts that a declared column is wide enough for the widest legal value it can
hold. The two tests that do exist — `MeasureRepositoryTest`'s longest legal
reference and name — were written for this defect and cover this table only.
`identity_ref` appears at `VARCHAR(256)` in three further tables, and the same
reasoning that got `measure` wrong could get the next one wrong the same way:
silently on SQLite, loudly on a deployment, and only for accounts with long
identifiers.

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
| Plugin jar version | The version a plugin reports to its host is the version it was built as. NOT that it is a release version — that is `release.yml`'s, per DECISIONS 10.53 |
| Distribution archive | Every path in a `.tar.gz` or `.zip` lies under one root, and none carries an unexpanded `${` |
| Jar manifest | Every shipped jar declares the version and module it was built as, read from the jar |
| Unit exit status | Every shipped systemd unit counts SIGTERM (143) as success, so a normal stop is not reported as a failure |
| Admin command | `/soulbind` is restricted to ADMINISTRATOR at the platform, the ordinary commands are not, and the connector's own check survives |
| build-logic JUnit pin | The one version number written twice agrees with itself, and neither side floats |
| Check-then-act | No unreviewed read-then-write in any storage write path |
| Event doc sync | Every EventType appears in the document and vice versa |
| Audit immutability | Nothing in production source or any migration mutates the audit table, and the repository declares no mutating method |
| Harness pins | Every `harness/*/pins.env` escapes the `*.env` rule, so a clone can still reproduce a stack run |
| Action pins | Every workflow action is pinned to a 40-character commit, never to a tag — the same rule the container digests and `pins.env` checksums already state, applied where `contents: write` lives |
| Copyleft packaging | No LGPL artifact the specification pins to a non-bundling scope is declared in a configuration that would bundle it |
| Non-vacuous tiers | A tag-selected task whose module declares that tag must execute at least one test, so a tier cannot silently become zero coverage |
| Plugin jar | Dependencies relocated (both directions), service files renamed to match, no host API or copyleft classes bundled, licence files inside, no stale signatures |
| Service distribution | Every artifact the inventory calls unbundled is its own jar in `lib/`; the classpath is explicit; the unit and samples ship, scoped to the module |
| Composer package | Carries a byte-identical `LICENSE` and a `NOTICE`; requires no third-party PHP; excludes tests and vendor from the archive |
| Licence inventory | Every distributed module generates one; every allowlisted licence states its packaging handling; NOTICE names the generated file |
| Full-stack stages | The stage list, the implementations and the README table name one set; the runner still fails a stage that emits no result; no stage can report a skip |

Every one is paired with a deliberately-broken fixture and has been
mutation-checked against the real tree, not only the fixture.

Their module coverage is derived from `settings.gradle.kts` rather than
hand-listed, so a new module is guarded the day it is created and has to be
excluded deliberately, with a reason, rather than by omission.
