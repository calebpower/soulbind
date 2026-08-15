# Status

Where the work actually stands. **This document is trusted over the
specification** (`soulbind-plan.md`) and over the README whenever they disagree.

Last updated: 2026-08-15, Phase 1 in progress.

---

## Phases

| Phase | What it is | State |
|---|---|---|
| 0 | Skeleton and guards | **Complete** — gate passed |
| 1 | Core skeleton: storage, config, registry, audit | **In progress** — storage seam, registry, authorization matrix and HMAC signing landed; config loader, hello/heartbeat, admin bootstrap and fuzz harness outstanding |
| 2 | Identity graph and linking | Not started |
| 3 | Policy engine and decisions | Not started |
| 4 | Events and effectors | Not started |
| 5 | connector-velocity | Not started |
| 6 | connector-discord | Not started |
| 7 | connector-flarum | Not started |
| 8 | connector-plan + full-stack battery | Not started |
| 9 | Simulated users | Not started |
| 10 | Hardening and release | Not started |

## What runs today

`./gradlew build` compiles every Java module and runs both test tasks — the
ordinary one and `charsetHostilityTest` — across 308 tests, green.

Real behaviour exists now, but nothing links anything yet: there is no transport,
so no connector can say `hello`. What runs is the storage seam against SQLite
(MariaDB skips without `SOULBIND_TEST_MARIADB_URL`), link-code normalisation,
HMAC request signing, credential minting, and the authorization matrix.

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
| Fuzz clean on both backends | Not started — T7 harness outstanding |
| hello + heartbeat over both transports | Not started — no transport exists yet |

Outstanding Phase 1 deliverables: TOML config loader, WebSocket and
webhook/poll transports, `hello`/heartbeat, audit query API, `soulbind-admin`
bootstrap, `soulbind doctor`, T2 DTO wire conformance, the audit-immutability
guard, T6 migration idempotence on both backends, and the T7 fuzz harness.

## Guards in force

| Guard | Holds |
|---|---|
| Platform vocabulary | No platform is named where the dispatcher can see it |
| Release level | Declared convention *and* emitted bytecode, per module |
| Dependency graph | No YAML parser; no copyleft artifact shaded |
| Storage seam | No SQL, JDBC type or backend-conditional branch outside `core/storage` |
| Protocol doc sync | `docs/protocol.md`'s operation and capability tables match the code, both directions |

Every one is paired with a deliberately-broken fixture and has been
mutation-checked against the real tree, not only the fixture.
