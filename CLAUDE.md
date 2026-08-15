# Working in this repository

## What this project is

soulbind binds one person's several platform identities to a single subject,
with configurable verification requirements, enforcement gates, effectors and a
complete audit trail. Core is the only authority on the identity graph; every
integration is an out-of-process connector speaking a versioned protocol.

Read `docs/soulbind-plan.md` before making architectural decisions. It is the
specification and a **record**: it is never edited. Where reality departs from
it, the departure goes in the README's departures table naming the section it
overrides.

`docs/STATUS.md` is trusted over the specification when they disagree.

## The documents, and which one wins

| Document | Role |
|---|---|
| `docs/soulbind-plan.md` | The specification. A record. Never edited. |
| `docs/testing-methodology.md` | Normative. Its §2 binds every commit. |
| `docs/STATUS.md` | Where the work actually stands. Trusted over the specification. |
| `README.md` departures table | Every decision that overrides the specification. |
| `docs/DECISIONS.md` | Judgement calls that did not override anything. |

## Non-negotiables

From `docs/testing-methodology.md` §2. These are process rules and they outrank
any individual test:

1. **Never weaken a test, check, assertion or lint to route around a defect.**
   Not by disabling a phase, adding an exclusion, lowering a threshold, marking
   `skip`/`ignore`, appending `|| true`, catching and swallowing, or narrowing a
   suite's scope.
2. **Every narrowing needs a stated reason covering exactly what it narrows.**
   An exclusion suppressing four things behind a justification for two is a bug
   in the justification. Narrowings are reported in the human-facing summary,
   not buried in a comment.
3. **Every fix ships with a test that would have caught it.** Where a change is
   genuinely untestable in isolation — dead-code removal, a version bump,
   configuration, comments — say so explicitly rather than inventing a test that
   asserts a constant.
4. **A pre-existing failure must be proven pre-existing.** Stash, re-run, name
   it.
5. **New assertions get mutation-checked.** Break the covered thing, confirm the
   test fails, note it in the commit message. A test never observed failing has
   unmeasured value. *This has already paid for itself here — see
   `docs/DECISIONS.md` 0.8.*
6. **Fix the cause, not the symptom.** A quiet terminal is not the goal.

## The seams, and why the guards exist

Good intentions do not hold an architecture together; lint guards do. Each seam
in specification §5 has a guard in `guards/`, and **each guard has a
deliberately-broken fixture proving it fires**. A guard without such a fixture
is a guard nobody has watched work.

The load-bearing one: **no platform name appears in `core/` or `protocol/`.**
Core learns platform kinds at runtime from connector registration. The moment a
platform is named there, hub-and-spoke has quietly become a mesh with a
favourite. The allowlist starts empty and every entry needs a stated reason.

A practical consequence: `core/` and `protocol/` cannot use words like `plan` or
`paper` even in prose. Say "specification". This is deliberate — see
`docs/DECISIONS.md` 0.4.

## Build

```sh
./gradlew build          # compile + test, including every guard
./gradlew guards         # the seam guards alone
```

Toolchain is Java 25 everywhere; **modules differ only in `--release`**. Modules
loaded into a server operator's JVM target 21 because that runtime's floor is
21; standalone modules target 25. Getting this wrong produces
`UnsupportedClassVersionError` at deploy time, not at build time, which is why
there is a guard for it.

Toolchains are **declared, never inherited** — on some hosts a bare `javac`
resolves to an older JDK than the one installed. See `gradle.properties`.

## Testing tiers

Eleven, defined in specification §11 and mapped to the methodology. The cheap
ones run on the workstation; the ones needing a real machine run in a reaper
session via `.reaper.toml`. Moving a cheap tier into a session because sessions
exist is an anti-pattern the methodology names explicitly.

Randomness prints its seed and accepts it back through `SOULBIND_SEED`.

## Conventions

- **Config is TOML** wherever soulbind owns the file. Where a host platform
  imposes its own convention, the host wins. A guard keeps YAML parsers out of
  the dependency graph.
- **Licence headers** on every source file; Apache-2.0.
- **No platform vocabulary in core.** See above.
- **Documentation is per-module.** Every module has a README saying what it is,
  why it is separate, and what it may not depend on.

## Commits

- Conventional, imperative subject lines.
- **No trailers of any kind.** No `Co-Authored-By`, no generated attribution.
- Mutation-check notes belong in the commit message when an assertion is added.
- `docs/protocol.md` and `docs/STATUS.md` are updated in the **same commit** as
  the change they describe.

## Scope

soulbind is not a chat bridge, a permissions plugin, an identity provider, a
moderation bot, a modreq system, or a web CMS. Specification §3 states the fence
and the answer when a request implies one of these: a connector-side change, or
a plain no.
