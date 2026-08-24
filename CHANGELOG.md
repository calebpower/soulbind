# Changelog

Notable changes, newest first. Dates are the date of the release, not of the
work.

This file records what a person installing or upgrading needs to know.
`docs/DECISIONS.md` records *why* things are the way they are, in far more
detail; `docs/STATUS.md` records where the work stands. Neither is a substitute
for the other.

## 0.1.2 — 2026-08-24

### Fixed

- **A running soulbind could not say which build it was.** Every published jar
  carried a manifest with no `Implementation-Version`, so core announced itself
  on startup as `soulbind (development)` regardless of the release it came from
  — including `v0.1.1`, in production. Jars now declare their version and
  module, and a guard reads it back out of the built artifact.

### Internal

- `build/libs` no longer accumulates a jar per commit. Stale artifacts are swept
  when a new one is written, matching what already happened for shaded plugin
  jars and distribution archives.

## 0.1.1 — 2026-08-23

### Changed

- **A version tag now releases by itself.** Pushing `vX.Y.Z` builds, runs the
  full suite and the guards, and publishes the release with its four artifacts
  and `SHA256SUMS`. It used to create a draft for a person to publish, and it
  used to require the version to have been bumped in the build first — that
  second step is gone entirely.
- **The version is derived from the git tag** rather than declared in
  `build-logic`. Nothing in the tree names a version any more. A build off a tag
  reports `X.Y.Z-<n>-g<sha>`, with `+dirty` if the tree has uncommitted edits;
  a build with no tag reachable reports `0.0.0-unversioned`.
- **Both plugin jars now report the version they were built as.**
  `velocity-plugin.json` — the file a proxy actually reads — carried a
  hand-edited literal, so the version shown in a proxy's plugin list was
  whatever was true the last time someone remembered to change it. It is now
  stamped by the build, and a guard fails the build if the two disagree.

### Fixed

- **`connector-discord`'s distribution archives shipped a bogus top-level
  directory.** Both `connector-discord-<v>.tar.gz` and its `.zip` contained a
  second root named, literally, `${project.name}-${project.version}`, with the
  `scripted-driver` script inside it rather than in the distribution's `bin/`.
  A Kotlin escape for a literal dollar had been used where interpolation was
  meant. Present in `0.1.0`. The unpacked install tree — what `installDist`
  produces and what the test battery runs — was never affected.

### For anyone building from source

- Building from a **git checkout** is unchanged. Building from a **source
  archive with no `.git`** now produces artifacts named `0.0.0-unversioned`,
  because there is no tag to read. Use a checkout if the name matters.
- `./gradlew guards` now also runs `build-logic`'s own tests.

## 0.1.0 — 2026-08-23

The first release. Everything below is new, so this entry describes what
soulbind *is* rather than what changed.

### What it does

Binds one person's several platform identities — a game account, a chat
account, a forum account — to a single **subject**, with configurable
verification requirements, enforcement **gates**, effectors that grant and
revoke roles and groups, and an append-only audit trail.

**core** is the only authority on the identity graph. Every integration is an
out-of-process **connector** speaking a versioned protocol; connectors never
talk to each other and never touch the database. Core never names a platform —
it learns platform kinds at runtime from connector registration, and a lint
guard enforces that.

### What ships

| Artifact | Where it goes |
|---|---|
| `core-0.1.0.tar.gz` | `/opt/soulbind/core` — the server, `bin/` plus one jar per dependency in `lib/` |
| `connector-discord-0.1.0.tar.gz` | anywhere; a standalone service |
| `connector-velocity-0.1.0.jar` | a Velocity proxy's `plugins/` |
| `connector-plan-0.1.0.jar` | Plan's `extensions/` |

The Flarum connector is a Composer package installed from this repository's VCS
tag, so it ships no artifact here.

The two distributions are not fat jars. Every dependency is its own replaceable
file in `lib/`, on an explicit classpath, which is what makes the licence
guarantee in §16 hold by construction rather than by an exclusion list somebody
has to maintain correctly. The two plugin jars are shaded, because a host loads
one file out of a directory and there is no `lib/` to unbundle into.

### Storage

SQLite or MariaDB, chosen in configuration. SQLite is serialised through a
single writer with WAL and a busy timeout, because SQLite permits exactly one
writer and a pool of them does not make that untrue — it makes it intermittent.
MariaDB gets a real pool and a stated `utf8mb4` connection collation.

Migrations run on every start, and a second apply is asserted to change
nothing.

### Operating it

- `soulbind serve`, `soulbind register`, `soulbind doctor` — three operational
  verbs, plus `version`. `doctor` is meant to be run *before* the first start.
  Everything else is a protocol operation under the authorization table rather
  than a command, so that one table governs who may do what.
- **Credential rotation** (`connector.rotate`) replaces a connector's
  credential with no overlap window. A grace period is exactly what is not
  wanted when the reason for rotating is that somebody else holds the
  credential.
- **Audit export**: every `audit.query` response carries `more` and
  `lastSequence`, and accepts `afterSequence`. `tools/audit-export.sh` is that
  loop, writing JSON Lines. The 1000-row bound was always there; what is new is
  that a truncation can be told apart from an ending.
- **Overrides** can be set *and removed*, and both emit gate transitions, so an
  effector learns when somebody is admitted or stopped being admitted by hand.
- **Gate descriptions**: `rule.set` accepts an optional `description` and
  `rule.get` returns it, alongside the connector that first declared the gate.

### Installing

`docs/install.md`, start to finish. It is verified on every session run against
a clean guest, ending in a real cross-platform link that core confirms after a
restart.

### Known gaps

Listed in full in `docs/STATUS.md`. The ones worth knowing before you install:

- **The Discord manual smoke has not been run.** The specification names it as
  evidence rather than a tier, because it needs a human with an account. The
  connector's logic, refusal wording, privacy rule and role effector are all
  covered against a scripted surface, and the full game↔chat link flow runs
  green in the stack — but the client library being wired to the seam correctly
  is the one thing a scripted surface cannot prove.
- **No Bedrock journey.** The specification makes it conditional on Geyser
  being in the composed stack, and it is not.
- **Nothing is published to Maven Central or Packagist.** Depend on the SDK by
  building it.
