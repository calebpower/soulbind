# Handoff — deploying 0.1.0 to a live estate

You are picking up **soulbind** immediately after its first release. This file
assumes you know nothing about it. Read it once, top to bottom, before touching
anything.

**This file is not committed and should not be.** `docs/STATUS.md` is the
project's real record of where the work stands, and a second document saying
the same things is a second document that drifts. Use this to get oriented,
then trust `STATUS.md`. Delete this file when the handoff is done.

**The job ahead is a first live deployment.** That is a different kind of work
from everything in this repository's history: up to now every mistake has been
cheap and reversible, and from here some of them are neither. §6 is about that
specifically, and is the part to read twice.

---

## 1. What the project is

soulbind binds one person's several platform identities — a game account, a
chat account, a forum account — to a single **subject**, with configurable
verification requirements, enforcement **gates**, **effectors** that grant and
revoke roles and groups, and an append-only audit trail.

Hub and spoke, deliberately:

- **core** is the only authority on the identity graph.
- Every integration is an out-of-process **connector** speaking a versioned
  protocol. Connectors never talk to each other and never touch the database.
- Core **never names a platform**. It learns platform kinds at runtime from
  connector registration. A lint guard enforces this and it is load-bearing:
  the moment a platform is named in `core/` or `protocol/`, hub-and-spoke has
  quietly become a mesh with a favourite.

| Term | Means |
|---|---|
| subject | one person, holding one or more identities |
| identity | one account on one platform, e.g. `game:9f2c…` |
| platform kind | a namespace for identities — `game`, `chat`, `forum` |
| gate | a named action a connector enforces, e.g. `game.join` |
| rule | the policy applied *at* a gate |
| override | a hand-written exception, for one subject or identity |
| connector | an out-of-process integration holding a credential |
| effector | the part of a connector that applies a decision — a role, a group |

---

## 2. Which document wins

Read `CLAUDE.md` in the repo root first; it is the standing brief. Then:

| Document | Role |
|---|---|
| `docs/soulbind-plan.md` | The specification. A **record**. Never edited. |
| `docs/testing-methodology.md` | Normative. Its §2 binds every commit. |
| `docs/STATUS.md` | Where the work actually stands. **Trusted over the specification.** |
| `README.md` departures table | Every decision that overrides the specification. Eleven, numbered 1–11. |
| `docs/DECISIONS.md` | Judgement calls that overrode nothing. ~8,400 lines, numbered by phase, currently at **10.49**. |
| `docs/protocol.md` | The wire protocol. Updated in the **same commit** as the change it describes. |
| `docs/install.md` | **The operator-facing install guide — the one you will be following.** |
| `docs/threat-model.md` | What this is and is not defending against. |
| `CHANGELOG.md` | What 0.1.0 is, and what is knowingly missing from it. |

---

## 3. Standing constraints from the owner

**These are not suggestions and several are absolute.**

### Absolute

- **Never push, tag, force-push, rewrite already-pushed history, open a pull
  request, or change a remote without express authorization.** Ask *each time*.
  Authorising one push is not authorising the next. `v0.1.0` is tagged and
  pushed; that authorization is spent and does not extend to `v0.1.1`. Since
  `b16d769` a tag also PUBLISHES a release rather than drafting one, so pushing
  one is now an outward-facing act with no second click to catch it.
- **No commit trailers of any kind.** No `Co-Authored-By`, no generated
  attribution, nothing.
- **Commit as the repository's configured git user.** Never `--author`, never
  bypass hooks.
- **The first deployment estate is never named in this repository.** Not in a
  README, a comment, a fixture, a config, or a commit message. It has a name;
  do not use it. This matters more now than it ever has, because you are about
  to be handling that estate's real hostnames and credentials — none of which
  belong in a commit either.
- **reaper's source tree is read-only** — at
  `/home/cpower/projects/tech/code/util/reaper`. The single permitted write is
  `cargo build --release`. Bugs go in `<parent>/reaper_bugs.md`, **never
  committed**.
- **Everything created stays inside soulbind.** No user-wide files, no touching
  system packages.

### Engineering, from `CLAUDE.md` §Non-negotiables

1. **Never weaken a test, check, assertion or lint to route around a defect.**
   Not by disabling a phase, adding an exclusion, lowering a threshold,
   `@Disabled`, `skip`, `|| true`, catching and swallowing, or narrowing scope.
2. **Every narrowing needs a stated reason covering exactly what it narrows**,
   reported in the human-facing summary, not buried in a comment.
3. **Every fix ships with a test that would have caught it.** Where a change is
   genuinely untestable — dead-code removal, a version bump, configuration,
   comments — say so rather than asserting a constant.
4. **A pre-existing failure must be proven pre-existing.** Stash, re-run, name it.
5. **New assertions get mutation-checked.** Break the covered thing by hand,
   confirm the test fails, note it in the commit message.
6. **Fix the cause, not the symptom.** A quiet terminal is not the goal.

### Working style

- **Never idle-poll a long-running build or test from a model context.** Launch
  detached, end the turn, get re-invoked when it finishes.
- **Forecast cost when declaring an expensive unit** — hours of wall clock or
  six figures of tokens — so the owner can object before it is spent.
- **Say what you intend to treat as a unit of work before starting it**, so
  there is a chance to object before there is anything to unpick.
- The owner does not want to be stopped for check-ins on work that needs no
  input. Finish what is yours; bring back only what is genuinely theirs.

---

## 4. Where things stand

**Phase 10 complete. `0.1.1` released and published.**

`main` is at **`5b76046`**, and **nothing is committed-but-unpushed.** `v0.1.0`
and `v0.1.1` are both tagged and pushed.

```
5b76046 docs: name 0.1.1 in the changelog                <- v0.1.1
ccc44cf docs: record runs 33 and 34, green on the version derivation
19eba96 fix: the version guard asserted a release property on every build
a59df72 fix: the distribution archives shipped a directory named ${project.name}
b16d769 build: derive the version from the git tag, and publish on it
2b32fc4 ci: pin every action by commit SHA, and guard that they stay pinned
8976916 release: 0.1.0, and the pipelines behind it      <- v0.1.0
```

- **Run 34 was green**, `reaper exit=0`: both storage axes, every stage, the
  install gate, Infection, browser evidence and the ratchet. Run 33 failed
  first, on a guard of this project's own making — DECISIONS 10.53, and
  `docs/STATUS.md` records both honestly, including what run 34 did *not*
  exercise.
- **Mutation survivors: 61 across the tree**, down from ~250.
- **`v0.1.1` is published** with four artifacts and `SHA256SUMS`, verified by
  downloading two of them and checking them against the published sums.
- **`v0.1.0`'s draft release was deleted** by the owner. Its artifacts carried
  the `${project.name}` archive bug; `v0.1.1` is the first sound release.

### The version is not written down anywhere

`SoulbindVersion` derives it from `git describe`. **Tagging is the whole act**:
there is no literal to bump, and `release.yml` publishes rather than drafting,
so a pushed tag becomes a public download with no second click in between.

Off a tag you get `0.1.1-3-gabc1234`, `+dirty` for an edited tree. Where there
is no git at all — the reaper guest builds in a JDK image that has none — you
get `0.0.0-unversioned`, and that is correct rather than a defect. Guards must
never assert otherwise; `release.yml` holds the release-time check, by name.

### First thing to check

Nothing is outstanding in the repository, so there is nothing to confirm before
reading. If you want the current CI state:

```sh
curl -s "https://api.github.com/repos/calebpower/soulbind/actions/runs?per_page=5" \
  | python3 -c "import sys,json;[print(r['name'],r['status'],r['conclusion']) for r in json.load(sys.stdin)['workflow_runs']]"
```

`gh` is **not installed** on this machine, and there is no GitHub API token in
the environment either — no `GH_TOKEN`, no `~/.netrc`, and the remote is SSH.
Git works; the REST API does not. Anything needing it (deleting a release,
reading a draft) is the owner's to do.

Both tags are public and neither may be moved.

---

## 5. What is outstanding

| # | Item | Whose |
|---|---|---|
| **76** | Regenerate the Discord bot token. Still at `<scratchpad>/dsmoke/discord.env`, mode 0600. **Blocks the Discord connector going live.** | owner |
| — | ~~The Phase 6 manual smoke~~ — **done**, against a real bot and server; DECISIONS 10.18. Listed as outstanding here in error. | — |
| — | `ext-xmlwriter` for PHP — needs touching system PHP, outside "everything stays inside the repo". Blocks nothing. | owner |
| — | `PlanCheckWalkerGuardTest`'s six guards **skip in a reaper session** and run only on the workstation: `assumeTrue(pythonAvailable())`, and the JDK image has no Python. Pre-existing, unrelated to the release work, and the wrong shape by DECISIONS 7.2's own argument. | either |
| — | Maven Central / Packagist — deliberately not done. Separate decision. | owner |

---

## 6. The live deployment — read this twice

Everything before now has been reversible. This is not, and the difference
should change how you work.

### What the estate is

A Velocity proxy in front of several Paper servers, a Flarum forum, a Discord
guild, and a Plan instance, sharing one MariaDB. It has real users. **Do not
name it in anything you commit.**

### What has been proven, and what has not

**Proven, on every session run:** a clean install following only
`docs/install.md`, ending in a real cross-platform link that core confirms
after a restart. The full game↔chat link flow. Group effects reaching a real
LuckPerms. Migrations on both backends. Hostile input never producing a 5xx.

**Also proven, once, by hand:** that the Discord client library is wired to its
seam correctly — command registration reaching the platform, an interaction
arriving as an invocation, a role actually appearing on and disappearing from a
member. That is the Phase 6 manual smoke, and it ran against a real bot in a
real server. It found three defects, the third being that
`subject.requirements-met` was emitted by nothing at all, so the effector half
of the product could not fire in any deployment. All three are fixed.
DECISIONS 10.18.

Earlier versions of this file called that smoke the single largest unknown
going into deployment. That was wrong, and it was wrong because
`docs/STATUS.md` contradicted itself — the Phase 6 row and narrowing 12 both
said "done", while the Phase 10 row and a later paragraph still said
"outstanding". Both halves of STATUS are corrected now.

**What is genuinely unproven** is narrower: the smoke ran against a bot token
that is being retired (#76), and it was one run in a throwaway guild rather
than a rehearsal of the real estate. Nothing about the client library's wiring
is in doubt; what has never been exercised is *this* estate's own guild, roles
and permissions.

### Order of operations

The order matters and is not arbitrary — each step is reversible until the next
one starts.

1. **Back up first.** The estate's MariaDB, in full. soulbind creates its own
   schema and does not touch existing tables, but "does not" is a claim, and a
   backup is the thing that makes it a survivable claim.
2. **Core first, alone.** Install per `docs/install.md`. Run `soulbind doctor`
   **before** the first start — it exists for this moment. Nothing enforces
   anything yet; core with no connectors is inert.
3. **Register connectors one at a time**, each with the narrowest capability
   set that works. `docs/protocol.md` has the capability table. A connector
   with `config-management` can rewrite every rule and unlink any identity.
4. **Deploy connectors in read-only posture first.** Gates with no rule
   **allow** — an unconfigured gate is a gate nobody asked for. So a connector
   can be live and observed for a while before any rule exists to enforce.
5. **Write the first rule last**, and on one gate. That is the first moment
   soulbind can refuse a real person entry.
6. **Have the rollback ready before step 5**, not after: removing the rule
   restores the previous behaviour immediately, because no rule means allow.

### What to watch

- **Fail-closed vs fail-open.** A connector whose core is unreachable
  **denies**. Core with no rule **allows**. Those look contradictory and are
  not — but it means core being down is a user-visible outage on any gated
  action. Know which gates are gated before you gate them.
- **`connector.rotate` has no overlap window.** The old credential stops
  working on the next request. That is correct for a leak and hostile if you
  do it casually mid-session.
- **The audit log is append-only and prunable.** Set up the export
  (`tools/audit-export.sh`) before you need it, not after.
- **Overrides emit gate transitions.** An override set by hand grants roles and
  groups; removing it revokes them. It is not a quiet annotation.

### The rollback

soulbind's own state lives in its own schema. Stopping core and removing the
connectors returns the estate to its previous behaviour — *except* for roles
and groups that effectors granted, which are real changes in LuckPerms and
Discord and do not undo themselves. Know which groups those are before you
start, so removing them is a list and not an investigation.

---

## 7. How to build, test and release

```sh
./gradlew build            # compile + test + every guard
./gradlew guards           # the seam guards alone
./gradlew :<mod>:mutationTest        # PIT for one module
./gradlew mutationRatchet --continue # every module against the baseline
```

Use `--offline` when the network is not needed; the dependency cache is warm.

**Toolchain**: Java 25 everywhere; modules differ only in `--release` — modules
loaded into a server operator's JVM target **21**, standalone modules target
**25**. Getting this wrong produces `UnsupportedClassVersionError` at deploy
time, not build time, which is why there is a guard for it. Toolchains are
**declared, never inherited** — the bare `java` here is 17. Java 25 is at
`/usr/local/openjdk25/bin/java`.

### CI, and what it is not

| Workflow | When | What |
|---|---|---|
| `build` | every push and PR | `./gradlew build guards`, plus cross-language vectors, ordinary and hostile |
| `mutation ratchet` | weekly, and on demand | every module against `mutation-baseline.txt`, `--continue` |
| `release` | a `v*` tag | rebuild, refuse if tag and built version disagree, attach four artifacts + `SHA256SUMS`, **publish** |

**CI is the cheap half arriving faster. It is not the gate.** The full-stack
battery, the MariaDB axis, the install gate, browser evidence and the PHP
mutation tier all need a real machine — they stay in `.reaper.toml`, and **a
green session is what a release is judged on.** DECISIONS 10.49 says why that
distinction is written down rather than assumed.

### reaper — the pre-push loop

Disposable Proxmox VMs. Installed at `/usr/local/bin/reaper` (0.1.1).

```sh
reaper up      # create a session — NOT implicit any more
reaper test    # sync, build, reset, run
reaper down    # destroy it, collecting results first
reaper list    # what is running (other projects' sessions appear here too)
```

`reaper test` **no longer creates its own session**; without one it exits 1.
`reaper list` shows all projects, so a listed session is not necessarily a
soulbind one. A full battery is ~50 minutes — launch it detached and end your
turn.

### Releasing again

1. **No version to set.** It is derived from the tag. Step 1 used to be
   "set `version` in `soulbind.java-common.gradle.kts`" and that line no longer
   exists in the build.
2. Name the release in `CHANGELOG.md` — the section is written as `Unreleased`
   while the work lands, and cutting the tag is what makes it a version.
3. **Green reaper session**, on the tree being tagged.
4. Commit, **ask**, push.
5. Tag locally first and rebuild: `git describe` must print the tag exactly, and
   `core-<v>.tar.gz` must appear. That is free to undo; a pushed tag is not.
6. **Ask**, then push the tag. It publishes immediately — there is no draft.

The release workflow still refuses when the tag and the built artifact names
disagree. It can no longer catch a forgotten bump, because there is nothing to
forget; what it catches now is a checkout with no tags (a shallow fetch) or a
tag the derivation refuses as not release-shaped. `v1.0` would do it: three
numeric components, as in `v1.2.3`.

---

## 8. Traps that have already bitten

Each cost real time. They will recur.

**Reading a stale mutation report as current.** A ten-hour-old PIT report was
read as fresh and used to rank work, because the build log said `BUILD FAILED`
and the summary lines were grepped anyway. `mutationTest` now deletes its
report first — *a missing report is a loud, obvious failure; a stale one is a
quiet, confident wrong answer.* This shape has landed three times.

**A number that will not hold still.** If a module's mutation counts move
between runs of an unchanged tree, **diff the mutant identities, not the
counts**. Counts say something changed; only identities say what.

**A branch whose only witness is a race has no witness — it has a coin.** It
reads as covered, counts as covered, and the report cannot tell you otherwise
because the report is what keeps changing. **Four instances so far**: twice in
`Storage.open`, once in `LinkingService`, once in the migrate check — where it
produced a *false failure* instead of a false pass. The fix each time was to
construct the state the race was being used to produce. DECISIONS 10.46, 10.48.
**This is the single most productive suspicion in the project's history.**

**"Untestable without X" usually means "not yet separated from X."** A claim
that `Storage.open`'s credential branches needed a live backend was retired one
DECISIONS entry after it was written.

**Mutating by hand: restore from a pristine copy every iteration.** A loop that
restored only the file it was about to mutate let mutants accumulate and
reported a mutant killed that the suite could not kill.

**A test that passes for the wrong reason.** Several were caught before
landing: fixtures tripping an earlier check than the one under test; a
deterministic-looking test actually answered by a friendly pre-check further
up; a self-test whose writer never compiled, so its case passed and meant
nothing. **When a new test passes first time, break the thing it covers and
watch it fail.**

**Gradle fails fast across modules.** `mutationRatchet` reported one module per
50-minute session until `--continue` was added.

**Parallel PIT runs kill the coverage minion.** `MutationLock` exists for this.

---

## 9. Conventions

- **Config is TOML** wherever soulbind owns the file; where a host platform
  imposes its own, the host wins. A guard keeps YAML parsers out of the graph.
- **Apache-2.0 header on every source file.**
- **Every module has a README** saying what it is, why it is separate, and what
  it may not depend on.
- **Conventional, imperative commit subjects.**
- **Comments explain why, not what** — and this codebase leans on that heavily.
  A comment recording *why a line exists* is how equivalent mutants are marked
  so a later sweep skips rather than rediscovers them.
- Randomness prints its seed and accepts it back through `SOULBIND_SEED`.

---

## 10. Scope fence

soulbind is **not** a chat bridge, a permissions plugin, an identity provider,
a moderation bot, a modreq system, or a web CMS. Specification §3 states the
fence and the answer when a request implies one of these: a connector-side
change, or a plain no.
