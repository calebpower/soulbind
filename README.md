# soulbind

Cross-platform account linking for game communities.

One person has several platform identities — a game account, a chat account, a
forum account, and whatever arrives next. soulbind binds them to a single
**subject**, with configurable verification requirements, enforcement gates,
effectors and a complete audit trail.

> **Status: Phase 0.** The skeleton, the build, and the seam guards exist.
> Nothing links anything yet. `docs/STATUS.md` is the document to trust about
> where the work actually stands, and it wins over this file and over the
> specification whenever they disagree.

**Licence:** Apache-2.0. See [`LICENSE`](LICENSE).

---

## The shape of it

Hub-and-spoke, not a mesh. **Core** is the single authority on the identity
graph, policy and audit. **Connectors** hold no authoritative state — at most a
short-lived cache of decisions, bounded by the TTL the decision carried.

```
                       ┌──────────────────────────┐
                       │           core           │
                       │  subjects · identities   │
                       │  policy · audit · events │
                       └────────────┬─────────────┘
                                    │  versioned protocol
                                    │  (WebSocket, or signed webhooks + polling)
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
       ┌──────┴──────┐       ┌──────┴──────┐       ┌──────┴──────┐
       │ connector A │       │ connector B │       │ connector C │
       │ chat        │       │ game proxy  │       │ forum       │
       └─────────────┘       └─────────────┘       └─────────────┘
```

**The seam is a protocol, not a plugin API.** Connectors live in runtimes core
does not control, so the integration surface is a versioned network protocol.
Anything speaking it, with a credential carrying the right capabilities, is a
connector.

Core never learns what a "chat provider" or a "forum" is. Those categories fall
out of which **capabilities** a connector claims — and a lint guard fails the
build if a platform's name appears in core at all.

## Vocabulary

Fixed, because these are easy to conflate.

| Term | Meaning |
|---|---|
| **core** | The dispatcher: owns subjects, identities, policy, config and audit |
| **connector** | An out-of-process integration speaking the connector protocol |
| **capability** | A permission a connector's credential carries; determines which protocol operations it may perform |
| **subject** | A person. The unit the identity graph hangs off |
| **identity** | One platform account bound to a subject |
| **platform kind** | A namespace for identities. Registered by connectors, never enumerated in core |
| **link code** | A short-lived, single-use code issued by core, displayed by one connector, redeemed via another |
| **gate** | A named action a connector enforces |
| **rule** | Per-gate policy: which verified platform kinds are required, overrides, grace periods |
| **decision** | Core's answer to "may identity X pass gate Z": allow/deny + reason + cache TTL |
| **effector** | A connector applying side effects on events |
| **event** | A state transition broadcast to subscribed connectors, at-least-once, idempotency-keyed |

## Modules

| Module | Target | What it is |
|---|---|---|
| [`protocol/`](protocol/) | Java 21 | Wire DTOs, link-code alphabet and normalisation, request signing, schema constants |
| [`config/`](config/) | Java 21 | The shared TOML loader. The only module with a TOML parser |
| [`core/`](core/) | Java 25 | The dispatcher service |
| [`connector-sdk/`](connector-sdk/) | Java 21 | Connector runtime: transports, decision cache, retry |
| [`connector-discord/`](connector-discord/) | Java 25 | Reference chat connector |
| [`connector-velocity/`](connector-velocity/) | Java 21 | Reference game-proxy connector |
| [`connector-plan/`](connector-plan/) | Java 21 | Read-only dashboard surfaces |
| [`connector-flarum/`](connector-flarum/) | PHP | Reference forum connector |
| [`guards/`](guards/) | — | The seam guards. No production code |
| [`corpus/`](corpus/) | — | Shared hostile-input list |
| [`vectors/`](vectors/) | — | Cross-language golden vectors |
| [`harness/`](harness/) | — | Test drivers and the staged full-stack battery |

**One toolchain, two targets.** Java 25 compiles everything; modules that load
inside a server operator's JVM emit Java 21 bytecode because that runtime's
floor is 21. A guard asserts both the declared intent and the emitted class-file
version.

## Build

```sh
./gradlew build     # compile + test, including every guard
./gradlew guards    # the seam guards alone
```

Requires a Java 25 toolchain. Toolchains are **declared, not inherited** — see
`gradle.properties`, which pins the paths because a bare `javac` does not
necessarily resolve to the newest installed JDK.

## Testing

Eleven tiers, each named by the question only it answers. The cheap tiers run on
the workstation; tiers needing a real machine run in a disposable VM via
[`.reaper.toml`](.reaper.toml).

The rules that matter more than any individual test are in
[`docs/testing-methodology.md`](docs/testing-methodology.md) §2. The short
version: never weaken a check to route around a defect; every narrowing carries
a reason covering exactly what it narrows; every new assertion is
mutation-checked by breaking the thing it covers and watching it fail.

Randomised tiers print their seed and accept it back through `SOULBIND_SEED`.

## What soulbind is not

The fence, and the answer when a request implies crossing it — a connector-side
change, or a plain no:

- **Not a chat bridge.** It does not relay messages between platforms.
- **Not a permissions plugin.** It emits events; something else decides what a
  group means.
- **Not an identity provider.** It issues no tokens and hosts no login for third
  parties.
- **Not a moderation bot.** Linking, verification, configured effector actions,
  configuration. Nothing else.
- **Not a ticket system.** Such a thing is a connector consuming events, needing
  no core change — that is the acceptance test of the seam, not a deliverable.
- **Not a web CMS.** Core serves JSON.

## Documentation

| Document | Role |
|---|---|
| [`docs/soulbind-plan.md`](docs/soulbind-plan.md) | The specification. A record; never edited |
| [`docs/testing-methodology.md`](docs/testing-methodology.md) | Normative. §2 binds every commit |
| [`docs/STATUS.md`](docs/STATUS.md) | Where the work stands. Trusted over the specification |
| [`docs/protocol.md`](docs/protocol.md) | The wire contract, held to the code by a structural test |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Judgement calls and their alternatives |

---

## Departures from the specification

The specification is a record and is never edited. Every decision that overrides
it is listed here with the section it overrides.

| # | Section | Departure | Reason |
|---|---|---|---|
| 1 | §4, preamble | `docs/simulated-user-testing.md` is **not** vendored; only `testing-methodology.md` is | No such file exists upstream. It was folded into `testing-methodology.md`, whose §11 is the specification for the simulated-user tier. The citation is a stale reference in the upstream docs, recorded so the absence is not later mistaken for an omission |
| 2 | §13 | `[run]` states that no battery exists and exits 0 | Phase 0 has no end-to-end battery; it lands in Phase 8, when the run verb and its pinned images become real. A narrowing scoped to the run verb at this phase only — `build` runs the real compile, the real test task and every guard |
| 3 | §5, §14 Phase 0 | Storage, transport and capability guards land in **Phase 1**, not Phase 0 | Their subject code does not exist yet. A guard written before its subject cannot be given a meaningful must-fail fixture, would pass vacuously, and would read as coverage while proving nothing. The reason covers exactly these three guards |
| 5 | §14 Phase 8 | The reaper `[run]` stage lands in **Phase 1**, carrying one digest-pinned database image and two tiers | Phase 1's gate asks for the fuzz tier clean on *both* backends, and no MariaDB is reachable from the workstation. Claiming it without one would be a claim no test report could contradict, because a skipped backend leaves no failure behind. The departure covers the run stage's *arrival*, not its scope: the full-stack images — forum, game server, proxy — remain Phase 8 as written |
| 6 | §14 Phase 8 | The **forum tier** of the run stage lands in **Phase 7** — a digest-pinned forum, database and browser | Phase 7's own gate asks for a forum account linking against a real core, and none of MariaDB, PHP or a web server runs on the workstation. Bringing the stage forward is the alternative to claiming a gate no test report could contradict, because a tier that never ran leaves no failure behind. The scope stays narrow and is the departure's whole extent: forum, database, browser. The game-side images — Paper, Velocity — remain Phase 8 as written |
| 7 | §16 | The Plan API is declared in **test** scope as well as `compileOnly`, and `commons-lang3` is added at test runtime | §16 pins Plan to "`provided` scope only", which `compileOnly` satisfies for everything soulbind distributes. Test scope reaches no published artifact, so the packaging guarantee LGPL actually depends on is untouched — and without it every provider body in `SoulbindDataExtension` is unexecutable by a test, since `compileOnly` compiles the annotations and never runs the bodies. `commons-lang3` is there because Plan's `Table.Factory` calls it without declaring it in its POM. The departure is the test classpath and nothing else; a new guard asserts neither artifact ever reaches a bundling configuration |
| 8 | §14 Phase 8 | Paper and Velocity are pinned as **checksummed jars**, not as digest-pinned container images | §14 says the run verb's images become digest-pinned, naming paper and velocity among them. Neither is distributed as a container image; they are jars, and `harness/fullstack/pins.env` pins them by SHA-256 with a dated note — the same guarantee the digests give, expressed the way these artefacts actually ship. The JDK and Node the tier runs on are pinned the same way, which is what lets one `stack.sh` run on a workstation and on a guest that has no toolchains. The container images the manifest does use — mariadb, php, composer, playwright, temurin — remain digest-pinned as written |
| 10 | §11 Tier 6, §11 Tier 11 | The `bedrock-player` journey is **not implemented**, and the `forum-first-user` journey is emitted by the **forum tier** rather than by the full-stack `journeys` stage | Bedrock is the plan's own conditional: §11 Tier 6 calls a Bedrock client through Geyser "a stretch stage, added only if Geyser is in the composed stack", and Geyser is not in it — the same sentence records that Floodgate identity handling is covered by Tiers 1/4 regardless, which is where it is covered. The forum journey moved because departure 6 split the forum into its own tier in Phase 7, so the full-stack tier has no forum to walk it against; it is walked where the forum is, and emits the same Tier 11 transcript through the same shared helpers |
| 9 | §14 Phase 9, §11 Tier 9 | The simulated-user tier ships **trimmed**: the oracle self-test, generator, actors, shadow model, checker and committed seed set land; the **shrinker** and **two of the six nemesis classes** do not | A shrinker is ergonomics, not detection — it changes how long a person takes to understand a failure, never whether the failure is found. Detection is the generator, the shadow model and the checker, all of which land. The deferred nemesis classes are the two whose defects do not require accumulated history and are already proven single-shot elsewhere: hostile corpus input at write endpoints (Tier 7 drives the same corpus at the same endpoints) and double redeem (proven under concurrency on both backends at the Phase 2 gate). The four kept — stale credential, act-on-unlinked, config flip mid-flow, abandonment — are kept because depth is the point: each is only interesting when it lands hundreds of actions into an accumulated history, which is the one thing no other tier can construct. The gate's substance is unaffected: a hunting run does not need a shrinker to rediscover a reverted fix. `docs/DECISIONS.md` 9.1 carries the re-entry criteria |
| 4 | §4, §5 | The shared config loader lives in its own [`config/`](config/) module, not in `connector-sdk` | §5 places it in `connector-sdk` and adds that "core reuses the same loader code". Taken literally, core would have to depend on the connector runtime — inheriting transports, retry and the decision cache it has no use for, and inverting the seam that keeps client-side machinery out of the dispatcher. A module of its own satisfies the stated intent exactly: one loader, one TOML parser, no duplication. The departure is the module's location and nothing else |
