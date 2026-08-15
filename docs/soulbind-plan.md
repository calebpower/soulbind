# soulbind — build plan

Cross-platform account linking for game communities. One person, many
platform identities — Minecraft, Discord, Flarum, and whatever comes next —
bound to a single subject, with configurable verification requirements,
enforcement gates, effectors, and a complete audit trail.

This document is the handoff plan for implementation by Claude Code. It is a
record: once implementation begins, departures from it are named in the
repository README (reaper-style), not edited into this text. `docs/STATUS.md`
is the document to trust about where the work actually stands.

**Binding companion document:** `docs/testing-methodology.md` from
https://github.com/calebpower/reaper — vendor a copy into this repository's
`docs/` at Phase 0 and treat it as normative. Its §2 non-negotiables apply to
every phase of this plan verbatim: never weaken a test to route around a
defect; every narrowing carries a stated reason covering exactly what it
narrows; every fix ships with a test that would have caught it; pre-existing
failures are proven pre-existing (stash, re-run, name it); new assertions are
mutation-checked (break the thing, watch the test fail); fix the cause, not
the symptom. The simulated-user tier follows reaper's
`docs/simulated-user-testing.md` as its specification.

This project is a **reaper tenant**. The `.reaper.toml` in §13 is real from
Phase 0 onward, and `reaper test` runs the tiers that need a real machine.
The cheap tiers stay on the workstation, per reaper's own guidance — migrating
them into sessions because sessions exist is an anti-pattern.

---

## 1. Vocabulary

Fixed, because the components are easy to conflate:

| Term            | Meaning                                                                                          |
| --------------- | ------------------------------------------------------------------------------------------------ |
| **core**        | The dispatcher: a standalone service owning subjects, identities, policy, config, and audit      |
| **connector**   | An out-of-process integration speaking the connector protocol (bot, game plugin, forum extension)|
| **capability**  | A permission a connector's credential carries; determines what protocol operations it may perform |
| **subject**     | A person. The unit the identity graph hangs off                                                   |
| **identity**    | One platform account bound to a subject: `(platform_kind, platform_id, …)`                        |
| **platform kind**| A namespace for identities (`minecraft`, `discord`, `flarum`, …). Registered by connectors, never enumerated in core |
| **link code**   | A short-lived, single-use code issued by core, displayed by one connector, redeemed via another   |
| **attestation** | A connector's signed claim that a platform account completed a challenge                          |
| **proof method**| How an identity was verified (`link-code`, later `oauth`, …). Stored per identity                 |
| **gate**        | A named action a connector enforces (`flarum.post`, `minecraft.join`). Registered by connectors   |
| **rule**        | Per-gate policy: which verified platform kinds are required, overrides, grace periods             |
| **decision**    | Core's answer to "may identity X pass gate Z": allow/deny + reason + cache TTL                    |
| **effector**    | A connector applying side effects on events (grant a Discord role, a LuckPerms group)             |
| **event**       | A state transition broadcast to subscribed connectors, at-least-once, idempotency-keyed           |

## 2. Architecture

Hub-and-spoke, not a mesh. Core is the single authority on the identity
graph, policy, and audit. Connectors hold no authoritative state — at most
short-lived caches of decisions, bounded by the TTL the decision carried.

**The seam is a protocol, not a plugin API.** Connectors live in runtimes
core does not control (PHP inside Flarum's request lifecycle, Java inside
Velocity's and Plan's classloaders, JDA in its own process), so the
integration surface is a versioned network protocol (§7). Anything speaking
it, with a credential carrying the right capabilities, is a connector. "Chat
provider," "forum," "game," and "management" are not categories core knows —
they fall out of which capabilities a connector claims.

**Transports.** Two ship: a persistent WebSocket (for daemon connectors:
Discord bot, Velocity) and signed webhooks + polling (for request-lifecycle
connectors: Flarum). Both carry the same protocol. In the Java connector SDK
the transport sits behind a small interface; the door is open a crack for a
third compiled-in transport (e.g. a modernized bonemesh for Java-to-Java
links) and no wider — no dynamic loading, no ABI.

**Components (v1):**

| Component            | Runtime                    | Role                                                                |
| -------------------- | -------------------------- | ------------------------------------------------------------------- |
| `core`               | Java 25, standalone        | Identity graph, link codes, policy, config, audit, connector registry, admin API |
| `connector-discord`  | Java 25, standalone (JDA)  | Reference chat connector: /link, verification, role effector, slash-command config |
| `connector-velocity` | Java 21, Velocity plugin   | Reference game connector: /link in chat, join gate, LuckPerms effector, Floodgate handling |
| `connector-flarum`   | PHP, Flarum extension      | Reference forum connector: register/post gates, link UI, fail-closed enforcement |
| `connector-plan`     | Java 21, Plan DataExtension| Read-only surfaces on Plan's web UI: link status, proof methods, unlinked-player table |

The management surface in v1 is the **admin API** plus Discord slash commands
(the Discord connector claiming `config-management`). Plan provides the read
dashboard. An interactive web UI, if ever wanted, is a future connector
against the same admin API — nothing here forecloses it.

**Java versions.** Toolchain is Java 25 (current LTS) everywhere. `core` and
`connector-discord` compile with `--release 25`. `connector-velocity` and
`connector-plan` compile with `--release 21`, because they execute inside a
server operator's JVM and Paper/Velocity's floor is 21. Enforced in the
Gradle convention plugin, asserted by a structural test (§11, Tier 3).

## 3. Scope fence

What soulbind is **not**, and the answer when a feature request implies one
of these — a connector-side change, or a plain "no":

- **Not a chat bridge.** It does not relay messages between platforms.
- **Not a permissions plugin.** It emits events; LuckPerms (via the Velocity
  connector as effector) decides what a group means.
- **Not an identity provider.** v1 issues no OAuth tokens and hosts no login
  for third parties. (An `oauth` proof method for *inbound* verification is a
  contemplated extension, not v1.)
- **Not a moderation bot.** The Discord connector does linking, verification,
  configured effector actions, and slash-command config. Nothing else.
- **Not a modreq system.** The modreq integration is a post-v1 demonstration
  connector consuming events (e.g. "open a ticket after N failed
  verifications"). The event stream is designed so that connector needs no
  core change; that is the acceptance test of the seam, not a v1 deliverable.
- **Not a web CMS.** Core serves JSON. The only HTML anywhere is Flarum's and
  Plan's.

## 4. Repository layout

One repository, Gradle multi-module for the Java components, with the PHP
extension and the test harnesses alongside:

```
soulbind/
  settings.gradle.kts
  build-logic/                  # convention plugins: toolchain 25, --release per module, lint guards wiring
  protocol/                     # protocol DTOs, schema constants, signing, code alphabet  (--release 21)
  core/                         # the dispatcher service                                    (--release 25)
  connector-sdk/                # Java connector runtime: transports, decision cache, retry (--release 21)
  connector-discord/            # JDA connector                                             (--release 25)
  connector-velocity/           # Velocity plugin                                           (--release 21)
  connector-plan/               # Plan DataExtension                                        (--release 21)
  connector-flarum/             # PHP: Flarum extension (composer package)
  vectors/                      # cross-language golden vector files (committed, generated)
  corpus/                       # shared hostile-input corpus (committed)
  harness/
    discord-scripted/           # scripted ChatSurface control harness (see §10.2, §11 Tier 6)
    player-driver/              # mineflayer-based Minecraft player driver (Node)
    fullstack/                  # staged full-stack battery: compose files, stage scripts
    sim/                        # simulated-user harness: generator, actors, shadow model, checker, nemesis, shrinker
  docs/
    soulbind-plan.md            # this document, verbatim, as a record
    testing-methodology.md      # vendored from reaper, normative
    simulated-user-testing.md   # vendored from reaper, normative for the sim tier
    protocol.md                 # the wire contract, kept current (Tier 3 checks hold it to the code)
    STATUS.md                   # where the work actually stands; trusted over this plan
  .reaper.toml
```

`protocol/` is deliberately tiny and dependency-light: DTOs, the link-code
alphabet and normalization, HMAC request signing, schema version constants.
It is the thing the golden vectors pin, and both `core` and every Java
connector depend on it. The PHP extension re-implements exactly this surface,
and the vector files are the oracle that the two implementations agree.

## 5. Seams and their guards

Per reaper: a lint guard enforces each seam; good intentions do not. The
guards are Phase 0/1 deliverables, run in the default test task, and are
themselves tested (each guard has a fixture that must fail it).

**Platform vocabulary guard.** No platform name — `discord`, `flarum`,
`minecraft`, `velocity`, `plan`, `geyser`, `floodgate`, `luckperms` — appears
in `core/` or `protocol/` source, case-insensitively, outside an explicit
allowlist file that starts empty and whose every entry needs a stated reason
(§2 of the methodology: the reason must cover exactly what it narrows). Core
learns platform kinds at runtime from connector registration, never at
compile time.

**Storage seam.** Core's persistence is behind a repository interface with
two implementations (MariaDB, SQLite). The guard: no SQL string and no JDBC
type outside the storage module; no backend-conditional logic outside the two
implementations. Every storage test runs against both backends by
parameterization — a test that names one backend is the exception that needs
a reason (e.g. the SQLite single-writer scenarios in Tier 8).

**Transport seam.** Connector SDK transports implement one interface;
protocol logic (signing, idempotency, decision caching) lives above it and is
tested against an in-memory transport. Guard: no WebSocket or HTTP client
type escapes the transport package.

**Config format.** Wherever soulbind owns a config file, it is TOML — never
YAML, INI, or JSON: core and all Java connectors, via one shared loader in
`connector-sdk` (core reuses the same loader code), with environment-variable
overrides for secrets. Where a host platform imposes its own convention, the
convention wins: the Flarum extension stores its settings Flarum's way
(database-backed, via the admin panel), because fighting the host's config
system is worse than the format. Guard: no YAML parser appears in any Java
module's dependency graph, and the shared loader is the only TOML entry
point. (Velocity itself is TOML-native, so this is also the
platform-idiomatic choice there.)

**Capability seam.** Every protocol operation declares the capability it
requires in exactly one table (code, not docs), from which the Tier 4
authorization matrix and `docs/protocol.md`'s capability column are both
derived — deliberately duplicated in the test per the methodology's
"assert against the source, not a re-export" rule.

## 6. Data model

Core's schema, backend-neutral. Migrations run on every boot and are
idempotent; a second boot rebuilds nothing and backfills are no-ops the
second time — asserted on both backends (§11, Tier 6).

| Table          | Columns (essence)                                                                                     |
| -------------- | ------------------------------------------------------------------------------------------------------ |
| `subject`      | `id (uuid)`, `created_at`, `status (active/suspended)`                                                 |
| `identity`     | `id`, `subject_id`, `platform_kind`, `platform_id`, `display`, `flags (e.g. bedrock)`, `proof_method`, `verified_at`, `created_at`; unique `(platform_kind, platform_id)` |
| `link_code`    | `code (normalized form)`, `issued_by_connector`, `issued_for (platform_kind, platform_id, display)`, `expires_at`, `redeemed_at`, `redeemed_by_connector`; single-use enforced transactionally |
| `connector`    | `id`, `name`, `credential_hash`, `status`, `registered_at`, `last_seen_at`                              |
| `connector_capability` | `connector_id`, `capability`                                                                    |
| `platform_kind`| `kind`, `registered_by`, `first_seen_at` — created on first registration, never enumerated in code      |
| `gate`         | `name`, `registered_by`, `description`                                                                  |
| `rule`         | `gate_name`, `required_kinds (set)`, `grace_seconds`, `default_effect`, `updated_at`, `updated_via`     |
| `override`     | `gate_name`, `subject_id or (platform_kind, platform_id)`, `effect (allow/deny)`, `reason`, `expires_at`|
| `event_outbox` | `id`, `type`, `payload`, `idempotency_key`, `created_at`; per-connector cursor table alongside          |
| `audit`        | `seq (monotonic)`, `at`, `actor (connector or admin credential)`, `action`, `subject_id?`, `identity?`, `gate?`, `detail (json)`; append-only — no UPDATE or DELETE path exists in the storage API |
| `config`       | Non-policy runtime config that is mutable via `config-management` (decision-log verbosity, code TTL, code length) |

Notes that are design decisions, not implementation details:

- **Link codes** are generated from a fixed alphabet chosen to survive humans:
  no `0/O`, no `1/l/I`, case-insensitive, normalized before comparison
  (trim, uppercase, strip separators). The alphabet and normalization live in
  `protocol/`, are re-implemented in PHP, and are pinned by golden vectors —
  including the hostile-charset second run (§11).
- **Floodgate/Geyser:** Bedrock players surface through Geyser with prefixed
  UUIDs and (typically) prefixed names. They are the same `minecraft`
  platform kind with `flags.bedrock = true`; `platform_id` is the UUID as
  presented by the proxy. The Velocity connector detects Floodgate presence
  reflectively (soft dependency). Dedicated unit fixtures cover prefixed
  UUIDs/names, and the code-via-chat flow is the guaranteed path for Bedrock
  players since GUI affordances differ.
- **Unlink** is soft with respect to audit (the audit rows remain forever)
  and hard with respect to policy (the identity row is deleted; a decision
  asked one transaction later sees it gone). Re-linking the same platform
  account later creates a new identity row; history lives in audit.

## 7. Connector protocol

Versioned; every message carries `schema` (integer). A connector or core
refusing an unknown major version is a *refusal with a reason*, never a
silent downgrade. `docs/protocol.md` is the human-readable contract; Tier 3
structural tests hold it to the code (every endpoint in code appears in the
doc and vice versa; every capability referenced is declared).

**Authentication.** Each connector holds one credential (random 256-bit
token, hash stored core-side). WebSocket transport authenticates at connect.
Webhook/poll transport signs every request body with HMAC-SHA256 over
`(timestamp, nonce, body)`; core rejects stale timestamps and replayed
nonces. The signing scheme lives in `protocol/`, is re-implemented in PHP,
and is vector-pinned. TLS is assumed at the deployment layer (reverse proxy
or direct); core can bind TLS itself but the protocol does not rely on it
for authentication.

**Operations, by capability:**

| Capability          | Operations                                                                                    |
| ------------------- | ---------------------------------------------------------------------------------------------- |
| (any registered)    | `hello` (declare capabilities, platform kinds, gates), heartbeat, `event.poll`/stream subscribe |
| `identity-provider` | `attest` — claim a platform account completed a challenge (the redeem path calls this internally) |
| `code-display`      | `code.issue` — request a code for a platform account it vouches for                            |
| `code-entry`        | `code.redeem` — submit a code typed by a platform account it vouches for                        |
| `enforcement-point` | `decide` — ask allow/deny for (identity, gate); response carries reason + cache TTL             |
| `effector`          | (consumes events; acknowledges with idempotency key)                                            |
| `audit-source`      | `audit.push` — append connector-side events to the audit stream                                 |
| `config-management` | `rule.get/set`, `override.get/set`, `config.get/set`, `connector.list`, `subject.inspect`, `identity.unlink` |

The **admin API** is the same operation set exposed to admin credentials
(same capability model, credential kind `admin`), plus audit query/export.
One capability table, one authorization matrix, no second code path — that is
what keeps the rule from existing in two copies that can drift.

**Linking flow** (symmetric by construction):

1. A `code-display` connector calls `code.issue` for a platform account it
   can authenticate locally (the Velocity connector knows who ran `/link`;
   the Discord connector knows who invoked the slash command). Core mints a
   code bound to that `(platform_kind, platform_id)`.
2. The person types the code into any `code-entry` connector, which calls
   `code.redeem` with its own local account context.
3. Core, transactionally: validates TTL and single-use, resolves or creates
   the subject, binds both identities, records proof methods, appends audit,
   emits events (`identity.linked`, and `subject.requirements-met` per gate
   whose requirements just became satisfied).

Either side can be the display or the entry; core never knows the pairing.

**Event delivery** is at-least-once with idempotency keys; effectors must be
idempotent and the SDK enforces key-based dedup. Per-connector cursors mean a
connector that was down receives what it missed on reconnect, in order.
Event types (v1): `identity.linked`, `identity.unlinked`,
`identity.verified`, `subject.requirements-met`, `subject.requirements-lost`,
`rule.changed`, `config.changed`, `connector.registered`.

## 8. Policy engine

**Evaluation is a pure function** of `(identity graph slice, rules,
overrides, clock)` → `(effect, reason, ttl)`. It lives in its own module
with no I/O; core wires storage to it. This is what makes the Tier 4 matrix
possible: every row asserts the function, no HTTP, no database.

- A rule names the verified platform kinds required for a gate. "Must link
  before posting" = `flarum.post` requires `{minecraft}` (or just "any linked
  subject", expressible as an empty required set with `linked=true`). "Must
  verify through Minecraft and Discord" = required set `{minecraft, discord}`.
- **Grace periods**: a rule may allow N seconds after first-seen before the
  gate closes, so a new forum registrant can read before linking. Grace is
  computed from audit-recorded first-seen, not connector-supplied time.
- **Overrides** (allow/deny, per subject or per raw identity, with expiry and
  mandatory reason) beat rules; deny beats allow.
- **Fail-closed is the default.** When a connector cannot reach core and holds
  no unexpired cached decision, it denies, with user-facing messaging that
  says the system (not the person) is at fault. Per-connector config may
  select fail-open or lengthen cache reliance; every departure from the
  default is a visible config line, and the shipped default is asserted by a
  test in every reference connector.
- Decision TTLs are short (default 60s) and carried in the response, so cache
  behavior is core-tunable without connector redeploys.

## 9. Audit

Append-only in fact, not in policy: the storage API for `audit` has no
update or delete, and a Tier 3 structural test asserts no code path acquires
one. Recorded: every link/unlink/verify/attest, every rule/override/config
mutation (with `updated_via` naming the connector or admin credential), every
connector registration and credential event, and policy decisions at a
configurable verbosity (`all` / `denials` / `sampled`, default `denials`).
Queryable and exportable (JSONL) through the admin API with time/actor/
subject filters.

The simulated-user tier asserts audit from both sides (two oracles for one
claim): the shadow model predicts the audit rows that must exist, and no
audit row exists the model cannot account for.

## 10. Components in detail

### 10.1 core

Java 25, standalone. Pinned choices (change requires a named departure):

- HTTP + WebSocket: **Javalin** (Jetty under it) — small surface, trivially
  embeddable in tests.
- JSON: **Jackson**. Persistence: **JDBC + HikariCP**, hand-written SQL in
  the storage module (two dialect implementations; an ORM would smear the
  storage seam). Migrations: **Flyway**, with per-backend migration dirs only
  where dialects force it.
- Drivers: MariaDB Connector/J; **xerial sqlite-jdbc** with WAL mode,
  `busy_timeout` set, and a single-writer executor in the SQLite
  implementation (SQLite's one-writer reality is handled here, not left to
  callers).
- Logging: slf4j + logback. Config file: TOML, one file, environment-variable
  overrides for secrets.
- Packaging: fat JAR + systemd unit; a `soulbind-admin` CLI (same JAR,
  subcommand) for bootstrap: create admin credential, register connector,
  print doctor-style self-check (`soulbind doctor`: config parses, DB
  reachable, migrations current, clock sane, each registered connector's
  last-seen).

### 10.2 connector-discord

Java 25, standalone daemon, **JDA**. The Discord surface sits behind a
`ChatSurface` interface owned by the connector: registering commands,
receiving invocations, sending replies/DMs, mutating roles. Two
implementations: JDA (production) and **scripted**
(`harness/discord-scripted/`) — an in-process implementation driven over a
local control API, used by the full-stack battery so the real connector
logic, SDK, transport, and core are exercised without Discord. Per the
methodology, the scripted implementation imports the connector's real
validation/normalization rather than re-implementing it. Protocol-faithful
gateway fakery is explicitly out of scope; the seam is `ChatSurface`.

Config: TOML via the SDK loader (credential, core URL, guild/role
mappings, fail mode). Surface: `/link` (issue or redeem, one command, argument-dependent),
`/whoami`, admin slash commands mapped 1:1 onto `config-management`
operations and additionally gated on Discord-side permissions. Effector:
role grant/revoke on `subject.requirements-met`/`-lost`, idempotent.

### 10.3 connector-velocity

Java 21, Velocity plugin, built on `connector-sdk`. `/link` in chat (code
display and code entry both — a player can start or finish here), join-time
enforcement of `minecraft.join` when configured (kick with configured
message, fail-closed default), Floodgate-aware identity capture (§6).
Effector: LuckPerms group grant/revoke via the LuckPerms API (soft
dependency, reflective lookup; absence is a logged, non-fatal condition).
Config: TOML via the SDK loader, matching Velocity's own native format.
Async everywhere — no core round-trip on the Velocity event thread; join
decisions use the SDK's cache + async refresh pattern with a bounded wait.

### 10.4 connector-flarum

PHP (target current Flarum stable — verify 1.8 vs 2.x at Phase 7 start and
pin; this is expected to be the plan's most likely named departure).
Composer package. Listens on Flarum's events to enforce `flarum.register`
(configurable) and `flarum.post`; renders link status + code entry/display in
user settings; receives webhooks (signature-verified) to keep a local
decision cache warm; falls back to synchronous `decide` with a short timeout,
then to fail-closed. Config follows Flarum convention:
settings (core URL, credential, fail mode, cache tuning) live in Flarum's
settings store, managed through the extension's admin panel page — the host
platform's config system wins over format preference here (§5). Re-implements the `protocol/` surface (normalization,
HMAC) and consumes the golden vector files in its PHPUnit suite — including
the hostile run: vectors executed under a non-UTF-8 default charset
(`mbstring`/locale pinned hostile), because under a friendly default the
suite passes whether or not encoding is actually pinned.

### 10.5 connector-plan

Java 21, Plan DataExtension. Read-only by API design: booleans, strings,
numbers, tables on player and server pages. Surfaces per player: linked?,
platform kinds verified, proof methods, verified-at, subject id (admin
visibility setting). Server-wide: linked/unlinked counts, table of active
players without links. Data arrives by querying core over the SDK with
caching tuned to Plan's refresh cadence; config is TOML via the SDK loader. This is the v1 read dashboard;
mutations stay on the admin API and slash commands.

## 11. The test battery, tier by tier

Mapped to `docs/testing-methodology.md`; each tier is named by the question
only it answers. Shared assets first, because every tier consumes them:

- **`corpus/`** — one hostile-input list (type confusion, unparseable ids,
  boundary values, oversized strings, SQL/HTML-hostile text, astral-plane
  text, RTL, zero-width) consumed by fuzz, full-stack stages, and the sim
  nemesis. A value that broke the API reaches the UI without re-typing.
- **`vectors/`** — generated, committed golden vectors: (a) link-code
  normalization: raw input → normalized form or rejection; (b) HMAC signing:
  key, timestamp, nonce, body → signature. Consumed by `protocol/` tests
  (Java) and the Flarum extension tests (PHP). Each suite runs the vectors
  twice: once normally, once under a hostile default charset.
- **Determinism everywhere randomness appears:** every randomized tier prints
  its seed and accepts it back via environment variable; anything that must
  vary between runs (run tags) is drawn outside the seeded stream.

**Tier 1 — pure unit.** Code alphabet and normalization boundaries; TTL and
grace arithmetic at the interval edges; Floodgate UUID/name parsing; policy
evaluation (the pure function) at its boundaries; HMAC canonicalization;
config parsing including the rejection paths.

**Tier 2 — component conformance.** No design-system UI of our own in v1;
the analogue is **wire conformance**: exact-serialization tests on protocol
DTOs (whole-string JSON equality on canonical fixtures), so a renamed field
or accidental null-inclusion is a red diff, not a runtime surprise. Same
discipline as markup conformance: additions must serialize to nothing when
unused, so existing fixtures pass byte-identical.

**Tier 3 — source-as-data.** The seam guards (§5) plus: every protocol
operation in code appears in `docs/protocol.md` and vice versa; every
capability referenced anywhere is declared in the capability table; every
event type emitted is documented and has at least one consumer test; audit
storage exposes no update/delete; module `--release` levels match §2's
table; the config-format guard of §5 (no YAML parser in any Java module's
dependency graph; the shared loader is the only TOML entry point); the
license guard of §16 (dependency-graph license allowlist; no LGPL artifact
inside any shaded/fat artifact); every user-facing message key in the connectors has content in every
shipped locale file (start with `en`, the check keeps it honest later).
Each guard has a deliberately-broken fixture proving the guard fires.

**Tier 4 — server contract.** The centerpiece: the **authorization matrix**
— every capability × every operation, and every gate-decision row: identity
states (unlinked / linked-unverified / verified subset / verified all /
overridden allow / overridden deny / grace window / expired grace) × rule
shapes × expected effect, as a table. When policy is refactored, the
existing rows passing unchanged is the gate on the refactor. Plus enum and
boundary decoding, and link-code redeem state machine (fresh / expired /
redeemed / unknown / malformed).

**Tier 5 — browser against a fake.** The browser surface is Flarum's UI with
the extension installed, against a Flarum whose *core* is faked at the HTTP
boundary (the fake imports nothing — it is table-driven responses — but any
normalization it performs imports the real PHP protocol code). Unique
capability, per the methodology: **transport error injection**. Core returns
500 / times out / returns malformed envelopes on demand; assert the
fail-closed messaging renders, the post is refused, the UI tells the truth,
and recovery works when core "returns". Playwright, cross-engine.

**Tier 6 — full stack, containerized, staged.** Runs in the reaper session.
Real core, real MariaDB **and** a second full pass on SQLite (the storage
seam earns its keep or it doesn't), real Flarum + extension + its MariaDB,
real Velocity + Paper with the plugin, the Discord connector on its
scripted `ChatSurface`, the Plan connector inside Paper, and the mineflayer
player driver. MariaDB starts **latin1**; astral-plane text from the corpus
pushes through the newest text column in every stage. Stage list, each
runnable alone:

```
fuzz  linking  policy  enforcement  effectors  audit
concurrency  regressions  journeys  browser  health
```

No backdoors: state is built through the real flows — a player really runs
/link via mineflayer, the code is really redeemed through the scripted
Discord surface or Flarum's UI. Migration idempotence asserted on both
backends (second boot rebuilds nothing). Bedrock-protocol client driving
Geyser is a stretch stage, added only if Geyser is in the composed stack;
Floodgate identity handling is covered by Tiers 1/4 regardless.

**Tier 7 — seeded fuzz.** Against core's connector protocol and admin API.
Oracle: no 5xx ever; every response a well-formed envelope; server alive
afterwards. Corpus-driven, seed printed and replayable. Runs in-process
against an embedded core on the workstation (cheap tier), and again inside
the full-stack `fuzz` stage against the real deployment.

**Tier 8 — concurrency.** Assert the resource read back, never response
counts. Scenarios, each with its non-racing sibling: N-way simultaneous
redeem of one code (exactly one identity pair results); unlink racing
verify; rule mutation racing decision (the decision reflects one rule or the
other, never a blend); double event acknowledgment (effector idempotency);
and the SQLite-specific runs where the single-writer executor is the thing
under test.

**Tier 9 — simulated users.** Per `docs/simulated-user-testing.md`. Actors
span platforms — the same simulated person owns a mineflayer player, a
scripted Discord account, and a Flarum account, because the defects worth
finding live in the cross-platform graph. Weighted actions: issue, redeem,
post, join, unlink, re-link, admin rule flips, override grants. Shadow model:
the identity graph + expected audit rows + expected effector states.
Invariants diff model against core periodically and at the end; every
response also passes the cheap no-5xx/envelope wrapper. Nemesis classes in
the same weighted pool: stale connector credential, act-on-unlinked,
double redeem, hostile corpus input at write endpoints, config flip
mid-flow, abandonment (issued codes never redeemed). Shrinker: bisect length,
then drop action kinds. Fixed committed seeds by default; any seed that ever
finds a defect is promoted permanently. **The oracle self-test is built
before the harness runs** (Phase 9): invariants are fed the responses a
broken core would send — modeled on the matrix's failure modes — and each
must complain, stackless, in about a second.

**Tier 10 — live browser audit.** The Tier 5 Playwright suite pointed at the
full stack, as an actor whose world was accumulated by a prior sim run:
paging past thresholds, deep audit queries, Plan pages rendering over
hundreds of players. Watchdog fails any test observing a 5xx — which is why
fault injection lives on Tier 5 and never here.

**Tier 11 — human evidence.** The journeys stage emits a per-step transcript
and screenshot directory for the linking flows (first-time player, forum-
first user, Bedrock player), so "would a newcomer understand this" is
answered from evidence.

**Acceptance test for the whole battery** (methodology §15): once real
defects have been found and fixed, revert a fix and confirm the harness
rediscovers it. Until then, mutation-check every new assertion by hand.

## 12. Full-stack stage detail

`harness/fullstack/run.sh <stage>...` — compose up (or attach to running
stack), run stages, emit JUnit-ish results + artifacts to `out/`. The
backend matrix is an axis: `SOULBIND_DB=mariadb|sqlite` selects core's
backend; Flarum keeps its own MariaDB either way. Under reaper, all mutable
state (both databases' data dirs, core's SQLite file, Flarum uploads,
server worlds) lives under `$REAPER_STATE` so `reset` actually resets;
container images and Gradle/npm/composer caches live on the never-rolled-back
datasets. The suite's driver calls `$REAPER_CONTROL/snapshot` once the stack
is healthy, so `@pristine` is stack-up, not end-of-run.

## 13. `.reaper.toml`

```toml
schema = 1
project = "soulbind"
guests = ["ubuntu-26.04"]
exec = "container"

[build]
image = "docker.io/library/eclipse-temurin@sha256:<pin at Phase 0>"  # Temurin 25 JDK; digest, never a tag
cmd = "./gradlew --no-daemon build -x test && ./gradlew --no-daemon test"
cache = ["gradle", "npm", "composer"]

[run]
exec = "host"                     # the battery drives the guest's container engine
cmd = "set -o pipefail; harness/fullstack/run.sh all 2>&1 | tee out/fullstack.log"
images = []                       # populated at Phase 8: mariadb, flarum, paper, velocity — all digest-pinned

[sync]
exclude = ["/build/", "/.gradle/", "/node_modules/", "/vendor/", "/out/"]

[reset]
datasets = ["state"]

[resources]
cores = 6
ram_gb = 12
```

Mind reaper's documented traps: `run.cmd` pipes, so `pipefail` is explicit
(dash has none, and a masked failure poisons `@pristine`); every image
digest-pinned; state under `$REAPER_STATE` or reset silently does nothing.

## 14. Phases

Each phase names its deliverables, the tests it adds (by tier), and its
acceptance gate. A phase is done when its gate passes and `docs/STATUS.md`
says so. Tests land in the same phase as the code they cover — never later.
Effort matches change (methodology §14): a doc-only phase step does not
demand the containerized stack; a schema change always does.

### Phase 0 — skeleton and guards

Deliverables: repository layout (§4); model-routing config per §17
(`.claude/settings.json`, subagent definitions); Gradle convention plugins
(toolchain 25, per-module `--release`); vendored methodology docs; `docs/protocol.md`
stub; the seam guards (§5) with their must-fail fixtures; `corpus/` v1;
`.reaper.toml` (build verb only; run verb echoes "no battery yet" and exits
0 with a stated reason recorded as a narrowing); CI-less pre-push loop
proven: `reaper up && reaper test` builds the tree in a session.
Gate: guards fire on their fixtures; `reaper test` green; `reaper-manifest-validate` passes.

### Phase 1 — core skeleton: storage, config, registry, audit

Deliverables: storage seam with both backends; Flyway migrations; connector
registry + credential auth (WebSocket + HMAC webhook verification);
`hello`/heartbeat; append-only audit with query API; `soulbind-admin`
bootstrap + `soulbind doctor`.
Tests: T1 config/HMAC/canonicalization; T2 DTO wire conformance begins; T3
audit-immutability guard, protocol-doc sync; T4 capability matrix v1 (every
operation × every capability, including admin); T6 (workstation-scale, not
session): migration idempotence both backends; T7 fuzz harness online
against embedded core — no-5xx oracle from day one, seeds replayable.
Gate: fuzz clean on both backends; matrix green; a registered connector can
hello + heartbeat over both transports.

### Phase 2 — identity graph and linking

Deliverables: subjects, identities, link codes (issue/redeem/attest),
Floodgate-aware identity fields, unlink; `vectors/` generated and committed
(code normalization + HMAC), Java consumer wired.
Tests: T1 alphabet/normalization/TTL boundaries, Floodgate parsing; T4
redeem state machine rows; T8 first harness: N-way redeem of one code, on
both backends, asserting the graph read back; vectors run hostile-charset
pass in Java.
Gate: two fake connectors (test doubles over the real SDK) complete a full
link, both directions; concurrency harness proves exactly-one-redeem;
mutation-check log recorded for every new assertion class.

### Phase 3 — policy engine and decisions

Deliverables: pure-function evaluator; gates/rules/overrides/grace; `decide`
with TTL; fail-mode semantics in the SDK (fail-closed default, cache,
bounded async refresh); decision audit verbosity config.
Tests: T4 the full decision matrix (identity states × rule shapes ×
overrides × grace boundaries); T1 grace/TTL edges; T8 rule-mutation-racing-
decision; T7 corpus extended with policy-shaped hostility.
Gate: matrix green; SDK fail-closed default asserted by test; decision
latency budget measured and recorded in STATUS.md (target: p99 < 50ms
in-process, informational not gating).

### Phase 4 — events and effectors

Deliverables: outbox + per-connector cursors; at-least-once delivery over
both transports; idempotency-key dedup in the SDK; event types of §7.
Tests: T4 event-emission rows (which mutations emit what); T8 double-ack and
redelivery; T3 every-event-documented-and-consumed guard.
Gate: a connector down for the duration of 100 mutations receives exactly
the missed events, in order, once — asserted by reading the effector's state
back, not by counting deliveries.

### Phase 5 — connector-velocity

Deliverables: the plugin per §10.3; LuckPerms effector; kick messaging;
config file with fail-mode knob defaulting closed.
Tests: T1 Floodgate handling against fixtures; T4 additions for
`minecraft.join`; component tests with the SDK's in-memory transport;
`harness/player-driver/` (mineflayer) built here with a smoke script against
a locally-run Velocity+Paper.
Gate: on a local stack, a mineflayer player runs /link, redeems via a test
double, join gate enforces and LuckPerms group appears — scripted,
repeatable, not hand-verified.

### Phase 6 — connector-discord

Deliverables: JDA connector per §10.2; `ChatSurface`; scripted
implementation + control API (`harness/discord-scripted/`); slash-command
config mapped to `config-management`; role effector.
Tests: connector logic entirely against scripted surface; T4 rows for
Discord-side permission gating layered on capability gating; idempotent role
effector under redelivery (T8 class).
Gate: full link flow game↔Discord on a local stack using scripted surface;
one manual smoke against real Discord in a throwaway guild, results recorded
in STATUS.md (a manual step is named as such, per the methodology's honesty
rules — it is evidence, not a tier).

### Phase 7 — connector-flarum

Deliverables: the extension per §10.4 (pin Flarum version now; name the
departure if 2.x changed the extension API); PHP protocol re-implementation;
PHP vector consumer incl. hostile-charset run; webhook receiver; settings UI;
register/post gates.
Tests: PHPUnit unit + vector suites; T5 arrives: Playwright against Flarum
with core faked at HTTP, transport error injection proving fail-closed UX;
T3 message-key guard extended to the extension.
Gate: vectors green in both languages; T5 injection suite green cross-engine;
a forum account links via code entry against a real core locally.

### Phase 8 — connector-plan + full-stack battery

Deliverables: Plan DataExtension; `harness/fullstack/` compose + stage
scripts (§12); `.reaper.toml` run verb becomes real; images digest-pinned.
Tests: T6 staged battery, both DB backends, MariaDB started latin1,
astral-plane pushes, no-backdoor state building, migration idempotence
in-session; T7 fuzz stage against the real deployment; T8 scenarios re-run
in-session; `journeys` emits the T11 evidence directory; `browser` runs the
T5 suite against the real stack (no injection here — the 5xx watchdog is on).
Gate: `reaper test` runs the full battery green on both backends in one
session; Plan pages render link data for players created through real flows.

### Phase 9 — simulated users

Deliverables, in this order: (1) **the oracle self-test** — invariants fed
broken-core responses modeled on the T4 matrix's failure modes, each must
complain, stackless; (2) generator/actors/shadow model/checker; (3) nemesis
classes; (4) shrinker; (5) fixed seed set committed; promotion rule wired
into the harness output.
Tests: the tier is the test; its own self-test is (1).
Gate: self-test green; three fixed seeds × both backends green in a reaper
session; a deliberately reverted Phase-2-or-later fix is rediscovered by a
hunting run (the methodology's acceptance test, executed for real).

### Phase 10 — hardening and release

Deliverables: T10 live browser audit over a sim-accumulated world; packaging
(fat JARs, systemd units, composer package, plugin JARs — honoring §16: LGPL
jars unbundled in `lib/`, NOTICE and license inventory generated), install
docs for Ubuntu 26.04; `soulbind doctor` final checks; audit export; threat-model
pass over the protocol (replay windows, nonce store bounds, credential
rotation procedure — rotation is an admin operation with an audit row);
README with the departures table; STATUS.md brought current.
Gate: clean install from packages on a fresh Ubuntu 26.04 VM following only
the docs, ending with a real cross-platform link — evidence directory
captured; full battery green; every narrowing accumulated across phases is
listed in the human-facing summary with its reason.

Post-v1 seam demonstrations (explicitly not gated): modreq consumer
connector; OAuth proof method for Discord; interactive web management
connector; bonemesh-transport module if bonemesh is modernized.

## 15. Standing orders for the implementing agent

1. The methodology's §2 non-negotiables bind every commit. In particular:
   never `skip`/`ignore`/`|| true`/threshold-lower around a failure; every
   narrowing gets a stated reason covering exactly what it narrows, surfaced
   in the run summary, not a comment.
2. Every new assertion class is mutation-checked: break the covered thing,
   observe the failure, note it (a one-line log in the PR/commit message
   suffices). A test never observed failing has unmeasured value.
3. A pre-existing failure is proven pre-existing: stash, re-run, name it.
4. Determinism: print every seed; accept it back via env; keep run tags out
   of the seeded stream.
5. Two oracles for claims that matter — audit completeness, event delivery,
   and code single-use each get asserted from both sides.
6. Name what a test does not prove, in the test file, pointing at the test
   that carries the stronger claim.
7. Keep `docs/protocol.md` and `docs/STATUS.md` current in the same commit as
   the change; Tier 3 makes the former mechanical, discipline makes the latter.
8. Departures from this plan are recorded in the README's departures table
   with the plan section they override. The plan text itself is never edited.
9. Model routing follows §17: switch at phase boundaries, verify the active
   model before phase-gate reviews, and record in STATUS.md which model each
   gate review ran on.


## 16. Licensing

soulbind is released under the **Apache License, Version 2.0**. The
dependency landscape supports it, with two rules that are packaging
decisions, not afterthoughts:

| Dependency                | License                  | Handling                                                        |
| ------------------------- | ------------------------ | ---------------------------------------------------------------- |
| Javalin, Jackson, HikariCP, sqlite-jdbc, JDA, Flyway (community), Playwright | Apache-2.0 | Normal use; verify Flyway's community terms at pin time |
| Jetty                     | EPL-2.0 / Apache-2.0 dual| Taken under Apache-2.0                                           |
| slf4j, Flarum, LuckPerms, Geyser/Floodgate, mineflayer | MIT | Normal use                                       |
| Velocity **API** module   | MIT (proxy is GPLv3)     | Plugins compile against the MIT api module only; the proxy is never distributed by us |
| MariaDB Connector/J       | LGPL-2.1                 | **Never shaded.** Ships as a separate jar in `lib/`, replaceable by the operator |
| Plan / Plan-API           | LGPL-3.0                 | `provided` scope only; the extension never bundles Plan code — replaceability by construction |
| logback                   | EPL-1.0 / LGPL-2.1 dual  | Unmodified, unbundled binary dependency; acceptable. A swap to log4j2 (Apache-2.0) is a permitted one-line departure if a strictly permissive graph is preferred |
| MariaDB server, SQLite, Flarum runtime | (various)   | Separate processes over the wire; no license propagation         |

Rules:

- **No LGPL artifact inside any shaded or fat artifact.** LGPL dependencies
  ride in `lib/` next to the fat JAR, on the classpath via the systemd unit,
  so the operator can replace them — that is what satisfies LGPL's relink
  requirement in practice.
- **Prefer avoiding paper-api.** If the Plan connector needs a plugin
  bootstrap, bootstrap on the Velocity platform (Plan supports it; Velocity's
  API is MIT). If paper-api (GPLv3) becomes unavoidable, note it as a
  departure: Apache-2.0 is GPLv3-compatible, so no conflict arises even under
  the strictest derivative-work reading, but the permissive path is cleaner.
- **Mechanical enforcement (Tier 3):** a dependency-license report runs in
  the default test task with an allowlist (Apache-2.0, MIT, BSD, EPL-dual
  taken as Apache, LGPL in unbundled scope only). An artifact-content check
  asserts no LGPL classes appear inside shaded outputs. New licenses entering
  the graph fail the build until allowlisted with a stated reason — the same
  narrowing discipline as every other guard.
- `LICENSE` (Apache-2.0) and a generated `NOTICE` + third-party license
  inventory ship in every distributed artifact.


## 17. Model routing

Directives for the Claude Code sessions that implement this plan. Model
names here are current as of August 2026; prefer **aliases** (`opus`,
`fable`, `sonnet`, `haiku`), which track the recommended version per
provider, and pin a full model name only where a directive says determinism
matters. Requires Claude Code v2.1.219+ (`claude update`).

**Session model by work type:**

| Work                                                                   | Model            | Effort  |
| ---------------------------------------------------------------------- | ---------------- | ------- |
| Implementation phases 0–8, 10 (the workhorse)                          | `opus`           | `xhigh` |
| Phase 9 (simulated users: shadow model, nemesis, shrinker, oracle self-test) | `fable`     | `xhigh` |
| Phase-gate reviews; protocol or seam design revisions                   | `fable`          | `xhigh` |
| Mechanical chores via subagents (see below)                             | `haiku`/`sonnet` | default |

`opusplan` is an acceptable alternative to plain `opus` for implementation
phases (Opus plans, Sonnet executes); use it only where the phase is
implementation-heavy, never for Phase 9 or reviews.

**Rules:**

1. **Switch at phase boundaries, not mid-task.** A mid-session `/model`
   switch re-reads the full history uncached; the switch point is the moment
   a phase gate passes, which is also when STATUS.md is updated.
2. **Effort is `xhigh`, set explicitly** (`/effort xhigh` or `--effort
   xhigh`) — it is not the default and the methodology's mutation-checking
   and two-oracle discipline reward the deeper reasoning. `max` is
   session-only and prone to overthinking; do not adopt it as standing
   default.
3. **Subagents carry their own model.** Define in `.claude/agents/` with
   `model:` frontmatter: fuzz-battery runs, golden-vector regeneration, the
   license report, and doc-sync checks run on `haiku` or `sonnet`; a
   design-review subagent, if defined, runs on `fable`. Never set
   `CLAUDE_CODE_SUBAGENT_MODEL` globally — it overrides all frontmatter.
4. **Classifier-fallback awareness.** This project's HMAC signing,
   credential handling, and replay-nonce work can occasionally trip the
   cybersecurity classifier on Fable 5 or Opus 5, after which *the session
   silently continues on the fallback model*. Acceptable mid-implementation;
   not acceptable for a Fable-designated gate review — before each review,
   confirm the active model (status line or `/status`), and if a review ran
   on a fallback, either re-run it on `fable` or record the actual model in
   STATUS.md. Turning off "Switch models when a message is flagged" in
   `/config` (prompt instead of silent switch) is recommended for review
   sessions.
5. **Headless runs pin `opus`.** In non-interactive mode (`-p`) and through
   the Agent SDK there is no usage-credit consent prompt: a Fable request
   that bills to credits bills without asking. Scripted or scheduled phase
   runs therefore launch with `--model opus` explicitly.
6. **Repo config.** `.claude/settings.json` ships with `"model": "opus"` and
   `"effortLevel": "xhigh"` as the project starting point (initial
   selection, not enforcement); the Fable escalations above are deliberate
   per-session choices, never the saved default.
