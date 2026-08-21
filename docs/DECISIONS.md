# Decision log

Running record of judgement calls made during implementation: what was decided,
why, and what the alternative was. Distinct from three neighbouring documents:

- **`../README.md` departures table** — decisions that *override the
  specification*. Every departure appears there; not every decision here is a
  departure.
- **`STATUS.md`** — where the work stands. Trusted over the specification.
- **The specification** (`soulbind-plan.md`) — a record, never edited.

A decision earns an entry here when a reasonable implementer could have chosen
otherwise. Mechanical consequences of the specification do not.

---

## Phase 0

### 0.1 — `java-library`, not `java`, in the common convention

`connector-sdk` exposes `protocol`'s types to every connector that depends on
it. That is an `api` relationship, and `api` requires `java-library`. Applying
it in the shared convention rather than per-module keeps the toolchain and
dependency-visibility decisions in one file.

*Alternative:* apply `java-library` only to `connector-sdk`. Rejected — it would
put a second place where a module's plugin set is decided.

### 0.2 — Repositories declared once, in settings

`settings.gradle.kts` sets `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, so the
common convention deliberately carries no `repositories { }` block. A module
cannot quietly introduce its own source of artifacts; attempting to is a build
failure.

This surfaced as a real failure during Phase 0 — the convention plugin declared
`mavenCentral()` and the build refused it. Fixed by deleting the declaration,
not by relaxing the mode.

### 0.3 — Toolchain paths pinned explicitly

On this host `java` and `javac` resolve to **17** through FreeBSD's `javavm`
dispatcher, even though 21 and 25 are both installed. `gradle.properties` pins
`org.gradle.java.installations.paths` and disables auto-download.

Without it the build fails at `--release 25` with a message about the compiler
rather than the toolchain — a confusing failure a long way from its cause.

*Verified, not assumed:* class-file major versions after a build are 65
(Java 21) for `protocol`, `connector-sdk`, `connector-velocity`,
`connector-plan` and 69 (Java 25) for `core`, `connector-discord`.

### 0.4 — `plan` stays on the forbidden-words list, and prose bends around it

The specification's §5 lists `plan` among the platform names core must never
name. It is also an ordinary English word, and the guard fired immediately on
`(plan §7)` in a placeholder javadoc inside `protocol/`.

**The code was changed, not the guard.** Guarded modules say "specification"
where they mean the document. Weakening the guard — dropping the word, or
exempting comments — would have been a permanent narrowing to buy back one
sentence of prose.

*Consequence to live with:* `core/` and `protocol/` cannot use the words
`plan`, `paper`, `java edition` and similar in comments. The guard is scoped to
those two modules only, so everywhere else is unaffected.

### 0.5 — Guard fixtures live under `src/test/resources`

Must-fail fixtures are `.java` files that must NOT compile as part of the build
— they exist to be read as text by a guard. Putting them under
`src/test/resources` means Gradle copies them and never compiles them.

The scan is parameterised so the fixture and the real tree drive **the same
scanning code**. A fixture checked by a second implementation would prove only
that the second implementation works.

### 0.6 — Word-boundary matching in the vocabulary guard

The guard matches whole words, case-insensitively. `planned`, `explanation`,
`planetary` and `javadoc` do not fire.

A guard that fired on every substring would be suppressed rather than obeyed,
and a routinely-suppressed guard protects nothing. A dedicated boundary fixture
holds this behaviour in place.

### 0.7 — NARROWING: three seam guards land in Phase 1, not Phase 0

The specification calls the seam guards "Phase 0/1 deliverables". Phase 0 ships
the guards whose subject matter exists:

| Guard | Phase | Why |
|---|---|---|
| platform vocabulary | 0 | `core/` and `protocol/` exist |
| module release level | 0 | every module exists |
| config format (no YAML parser) | 0 | the dependency graph exists |
| dependency licence allowlist | 0 | the dependency graph exists |
| **storage seam** | **1** | no storage module yet — nothing to constrain |
| **transport seam** | **1** | no transport package yet |
| **capability seam** | **1** | no capability table yet |

**This is a narrowing, and this is its stated reason:** a guard written before
its subject exists cannot be given a must-fail fixture that means anything, and
a guard that cannot be observed failing has unmeasured value. It would pass
vacuously and read as coverage. The three land in Phase 1 alongside the code
they constrain, each with its fixture.

The reason covers exactly these three guards and no others.

### 0.8 — The guards task is never up-to-date (found by mutation-checking)

Writing the release-level guard, the mutation check produced a **green run**:
`connector-velocity` was given the wrong convention plugin and the guard did not
complain.

The logic was correct. Gradle had simply skipped the task — `:guards:test
UP-TO-DATE` — because `connector-velocity/build.gradle.kts` is not one of the
guards module's declared inputs. Forcing `--rerun-tasks` made the guard fire
immediately.

**A guard that reports success without having looked is worse than no guard,
because it is trusted.** `outputs.upToDateWhen { false }` on the guards test
task; the mutation check now fires without forcing.

*Alternative considered:* declare the inputs precisely (every module's build
file, every guarded source tree). Rejected — the declaration needs updating
whenever a module is added, and a guard that silently stops covering new modules
is the same failure in different clothing. A few seconds per run is the right
price.

This is exactly what §2's mutation-check rule exists to catch, and it caught it
on the first guard where it could have mattered.

---

## Phase 1

### 1.1 — Link-code normalisation rejects, never repairs

The alphabet excludes `0`/`O` and `1`/`I`/`L` precisely because people misread
them. It is therefore tempting to *map* a typed `O` onto some alphabet
character, since the user has clearly misread something.

**Rejected.** Which character they misread is unknowable, and a wrong guess does
not fail — it silently produces a *different well-formed code*, which may belong
to somebody else. Rejection asks the user to retype; repair risks redeeming the
wrong link.

An earlier draft had both `Q` in the alphabet and a rule mapping `Q` onto `D`,
which would have corrupted every code containing `Q`. Removing the repair layer
removed the bug with it.

### 1.2 — A tautological test, caught by mutation-checking

`LinkCodeTest` had a test asserting that under a Turkish default locale,
`"bcdfi345"` is rejected. It passed. It also passed when normalisation was
deliberately made locale-*sensitive*.

The reason: Turkish maps `i` to `İ` rather than `I`, and **both are outside the
alphabet**, so a locale-sensitive implementation rejects exactly the same inputs
as a correct one. The test could not distinguish the bug from its absence — a
tautology wearing a locale costume, which is the failure mode the methodology
names explicitly ("matching the test's own fixture").

Replaced with a test that asserts what it can actually observe — every alphabet
character survives uppercasing under `tr-TR`, `az-AZ` and `lt-LT` — and that
**names what it does not prove**, pointing at why. The locale-independent
uppercase stays in `LinkCode` as correct defensive practice; it is simply not
claimed by a test that cannot see it.

*Verified after rewriting:* a real mutation (dropping a character from the
alphabet) is caught.

### 1.3 — Signing separator is a newline, and fields are validated against it

`RequestSigner` joins `(timestamp, nonce, body)` with `\n`. Concatenating
without a separator would let `("12", "3…")` and `("123", "…")` produce
identical signed bytes — a canonicalisation collision, which is signature
forgery in disguise.

The nonce is rejected if it contains the separator; the body is not, because it
is last and no boundary can be shifted by its content.

Comparison uses `MessageDigest.isEqual`, not `String.equals`: string comparison
returns early at the first differing character, and that timing difference is
enough to recover a signature byte by byte.

### 1.4 — SQLite's single-writer reality is handled at the seam, not by callers

SQLite permits one writer. A connection pool does not make that untrue; it makes
it *intermittent*, surfacing as `SQLITE_BUSY` under load rather than as a clear
constraint.

`Storage` therefore gives the SQLite backend a pool of one and serialises every
write through a single-threaded executor, in WAL mode with a busy timeout. A
caller who does not know which backend is in use cannot know to serialise — and
the entire point of the seam is that they should not have to.

MariaDB gets no such executor; forcing concurrent writers through one thread
would discard the reason to run it.

*Mutation-checked:* removing the executor and widening the pool makes
`concurrentAppendsAreUnique` fail. The assertion is on the sequences **read back
from storage**, never on how many calls returned successfully — the latter
passes even when two writers collide.

### 1.5 — NARROWING: MariaDB storage tests skip when no server is reachable

`StorageBackends.available()` yields MariaDB only when
`SOULBIND_TEST_MARIADB_URL` is set. On a workstation without a database server,
storage tests run against SQLite alone.

**The reason covers exactly this:** MariaDB coverage on a machine with no
MariaDB. It does not excuse a failure on either backend, the skip is visible in
the test report rather than silently passing, and the containerised battery
supplies a server so both backends run there. The specification requires both,
and both run where both can.

### 1.6 — Audit detail that cannot be serialised is recorded, not dropped

If a detail map fails to serialise, the entry is still appended with a
`_detailSerialisationFailed` marker in place of the detail.

Neither alternative is acceptable: dropping the detail silently makes the audit
log lie by omission, and failing the append means a serialisation bug can
prevent a security-relevant event being recorded at all. The reader sees that
something was lost rather than assuming there was nothing to see.

### 1.7 — The storage seam guard exempts a PACKAGE, not a source set

First run, the guard fired on eight real violations — all of them in the storage
package's own tests, which name both backends (parameterising over them is their
entire job) and hold a SQL-shaped value from the hostile-input corpus.

The exemption had been written as `core/src/main/java/.../storage`, covering
only `src/main`. Corrected to match the package path wherever it appears, so
`src/test` is covered too.

Had it been left as it was, the guard would have fired on the tests that prove
the seam works — and a guard that fires on correct code gets suppressed rather
than obeyed.

*Mutation-checked twice:* SQL leaked into `core/audit`, and a branch on a
backend name in `core/audit`. Both caught.

### 1.8 — The seam guard reads code, not comments, and says so

Prose explaining the seam necessarily mentions SQL and backend names — the
READMEs and javadoc in this repository do it constantly. A guard that fired on
its own explanation would be routed around.

So comments are stripped before matching. **This is a real narrowing:** a
violation hidden inside a comment is not caught. It is also not a violation,
because a comment does not execute. The reason covers exactly that.

### 1.9 — Credentials are hashed with SHA-256, not bcrypt or argon2

A credential is 32 bytes from `SecureRandom`, so it is not a passphrase and is
not derived from one.

Password-hashing functions exist to make *guessing* expensive for low-entropy
human secrets. Against a uniformly random 256-bit token, guessing is not the
threat — an attacker who can enumerate that space has already won elsewhere —
and the work factor would be paid on every legitimate authenticated request, on
every heartbeat, forever.

What SHA-256 does buy is the property that matters here: a database disclosure
yields nothing presentable.

*Mutation-checked:* algorithm swapped to SHA-1, token shortened to 64 bits,
padded non-URL-safe Base64, empty-credential check removed, and `hashesMatch`
made to accept two nulls. All caught.

### 1.10 — The authorization matrix restates the table by hand

`AuthorizationMatrixTest` writes out all eighteen operation→capability rows
independently rather than reading `Authorizer.table()`.

Deriving the expectation from the thing under test would assert only that the
code agrees with itself: a capability changed in error would be agreed with,
not caught. The duplication is the point. Changing an operation's capability now
requires editing the test too, which is the moment somebody decides rather than
drifts.

220 rows: every operation against every capability held singly, plus none, plus
all, plus suspended, plus no credential at all.

*Mutation-checked:* `code.redeem` pointed at the wrong capability,
`identity.unlink` made unprivileged, the suspension check removed, and the
refusal stopped naming the missing capability. All caught (9, 10, 18 and 90 row
failures respectively).

### 1.11 — `effector` is declared inert rather than left inert

No operation requires the `effector` capability: an effector *receives* events
and acknowledges them, and the specification's capability table lists it with no
request operation of its own.

Left implicit, an inert capability is indistinguishable from one somebody forgot
to wire — an operator could grant it and get nothing, with no way to tell which
it was. So the test names it in an `INTENTIONALLY_UNGATING` set and fails if any
*other* capability grants nothing, and also fails if `effector` later starts
granting something while still listed as inert. The set shrinks by a deliberate
edit when the event-acknowledgement operation lands in Phase 4.

### 1.12 — `audit.query` is mapped to `config-management`

The specification adds audit query/export to the admin API without naming a
capability for it (§7: "the same operation set exposed to admin credentials
... plus audit query/export").

There is no audit-read capability in the enum, and inventing one would be a
protocol change made in passing. `config-management` is the capability an admin
credential holds, so `audit.query` sits under it. Recorded here because it is an
inference from the specification rather than something it states.

### 1.13 — A charset-hostility test run, because UTF-8 tests cannot fail here

**Found by mutation-checking, and the most valuable finding of Phase 1.**
Replacing `getBytes(StandardCharsets.UTF_8)` with `getBytes()` in `Credentials`
produced a **GREEN** run. So did the same mutation in `RequestSigner`.

The cause: since JEP 400 this JVM's default charset *is* UTF-8, so the two
spellings emit identical bytes and no assertion on this machine can tell them
apart. The claim the tests were making — "encoded UTF-8, never platform-default"
— was unobservable, which is to say untested, in exactly the way that reads as
covered.

The fix is a second Gradle task, `charsetHostilityTest`, which re-runs the tests
tagged `charset` under `-Dfile.encoding=ISO-8859-1`. Under that JVM the two
spellings diverge and the pinned vectors fail.

**The narrowing, stated:** the tag selects only tests whose claim is about byte
encoding. Running the whole suite under a hostile charset would be testing the
JDK, not this code. Nothing else is excluded.

The tagged tests also assert that the hostile charset *took effect* — if a
future JDK ignores `file.encoding`, the task would silently become a duplicate
of the ordinary run, and nothing would say so.

This matters beyond tidiness: a digest taken over default-charset bytes differs
between hosts, so a credential minted on one machine would fail to authenticate
on another — but only for tokens containing non-ASCII, and only after both were
in production.

### 1.14 — `RequestSigner` shipped untested; T1 closes that

The class landed in the protocol commit with no test class at all, while its own
javadoc promised golden vectors "run twice, once under a hostile default
charset". Phase 1's T1 covers "config/HMAC/canonicalization", so the tests belong
now; the generated `vectors/` corpus remains Phase 2.

Digests are pinned against three oracles outside this JVM — `openssl dgst`,
PHP's `hash_hmac`, and Python's `hmac` — which agree with each other and with
the Java implementation. The PHP one is the one that matters: it is the function
the other implementation of this class will call.

*Mutation-checked:* separator changed to `:`, canonical bytes taken over the
platform default charset, hex uppercased, the nonce separator check removed, an
absent body canonicalised as the literal `"null"`, and `verify` made to accept a
truncated signature. All caught.

### 1.15 — An empty-key test that passed for the wrong reason

`assertThrows(IllegalArgumentException.class, () -> sign(new byte[0], ...))`
passed with the precondition deleted, because `SecretKeySpec` also rejects an
empty key. The test proved the JCE works, not that soulbind checks anything.

Now the message is asserted, so the failure has to come from soulbind's own
precondition. **What it still does not prove:** that a short-but-nonempty key is
rejected. It is not — key length is a deployment concern, and `soulbind doctor`
is where that check belongs. Said so in the test.

### 1.16 — `protocol` became an `api` dependency of core

`core` declared `implementation(project(":protocol"))` while returning protocol
types from its own public signatures: `Authorizer.Operation.required()` is an
`Optional<Capability>` and `ConnectorRecord.capabilities()` is a
`Set<Capability>`.

That understates the API surface — every consumer would have to re-declare
protocol to use methods core already hands them. Corrected to `api`, which is
also why the convention plugin picked `java-library` in Phase 0.

Surfaced by the doc-sync guard needing to reflect over both, but the declaration
was wrong independently of that.

### 1.17 — The doc-sync guard reflects over the enum, not the source text

`ProtocolDocSyncGuardTest` compares `docs/protocol.md`'s operations table
against `Authorizer.Operation` **reflected**, not re-parsed from Java source. A
source-text reading could agree with the document while both disagreed with the
compiled behaviour.

It is section-scoped rather than whole-file, because the capability table and
the operations table share a row shape: a whole-file scan would pass on a
document where each had been pasted over the other. And it asserts the parser
read the expected number of rows, so a regex that silently matched nothing
cannot make the comparison vacuous.

**What it does not prove:** that the prose around the tables is accurate. No
guard can. It proves the tables — the part a connector author codes against —
cannot silently diverge.

*Mutation-checked:* an operation added in code with no row, a row attributing
`decide` to the wrong capability, the `attest` row deleted, `audit.push` made
unprivileged in code only, and a capability added to the enum undocumented. All
caught.

### 1.18 — A mutation-check harness that read stale results

Worth recording because it nearly produced a false "the guard does not fire".

The harness ran `./gradlew :protocol:test :protocol:charsetHostilityTest` and
read both tasks' XML. When `test` failed — which is the whole point of a
mutation check — Gradle aborted the build before `charsetHostilityTest` ran, and
the harness read the *previous* run's results as if they were current. Four
mutations were scored against stale output.

Fixed by deleting `build/test-results` before each run and passing `--continue`.
The build was correct throughout; the measurement was not. This is the same
shape as the Phase 0 finding where an UP-TO-DATE task reported success without
looking, and the same lesson: a green result is only evidence if you can show
the thing actually ran.

### 1.19 — The release-level guard skipped unbuilt modules while claiming not to

Its comment said an unbuilt tree "must not read as a green guard"; the code
underneath said `continue`. Every module happened to be built, so the guard was
covering — but it would have stopped silently the first time it was not.

Fixed properly rather than by deleting the comment: the guards task now depends
on every inspected module's `classes` task, an unbuilt module is a *failure*
naming the module, and every class file is checked rather than one arbitrary
sample.

*Mutation-checked:* removed `connector-velocity`'s compiled output and excluded
its compile task. The guard failed, naming the module.

### 1.20 — Guard coverage is derived from the build, not hand-listed

Four guards each carried their own copy of the module list. Adding a module
meant remembering four places, and forgetting one produced a module quietly
outside coverage with every guard still green.

They now read `settings.gradle.kts`. A new module is covered the day it is
created and has to be excluded *deliberately*, with a reason, rather than by
omission. The only exclusion is `guards` itself, which contains no production
code and necessarily names the things it forbids elsewhere.

The release-level guard keeps its hand-written table — that table is the
contract, and deriving it from the build would assert only that the build agrees
with itself — but a new test asserts the table covers every module in the build.

### 1.21 — A `config` module rather than the loader in `connector-sdk`

Specification §5 puts the shared loader in `connector-sdk` and adds that "core
reuses the same loader code". Taken literally, core would depend on the
connector runtime — inheriting transports, retry and the decision cache it has
no use for, and inverting the seam that keeps client-side machinery out of the
dispatcher.

A module of its own satisfies the stated intent exactly: one loader, one TOML
parser, no duplication. **The departure is the module's location and nothing
else.** Recorded as departure 4 in the README.

`tomlj` is `implementation`, never `api`, so no consumer gains a parser on its
compile classpath — which is what makes §5's "the shared loader is the only TOML
entry point" a guard rather than a request. That guard fired on its first run:
`core` still declared tomlj from earlier scaffolding.

### 1.22 — Config key paths forbid underscores and hyphens

`storage.password` maps to `SOULBIND_STORAGE_PASSWORD`. If key paths could
contain underscores, `a.b_c` and `a_b.c` would both map to `SOULBIND_A_B_C`, and
an operator setting a secret would silently configure a different key.

Refusing the character is cheaper than detecting the collision, so the character
is refused. `ConfigSchema` *also* checks for environment-name collisions — a
check unreachable while the path rule holds, kept because it is what would catch
a future relaxation of that rule.

### 1.23 — Booleans are strict; unknown keys are fatal

`Boolean.parseBoolean` maps every non-`true` string to `false`.
`SOULBIND_SERVER_TLS=yes` would therefore mean TLS silently disabled. The loader
accepts exactly `true` and `false`, case-insensitively after trimming, and
refuses everything else by name.

Unknown keys are rejected for the same reason: a misspelt key that is silently
ignored looks present, uses the default, and surfaces somewhere unrelated. Near
misses are suggested within edit distance 2 only — confidently naming an
unrelated key sends the operator to change something already correct.

*Mutation-checked:* unknown-key check disabled, `Boolean.valueOf` semantics
restored, environment overrides ignored, collected problems never raised, the
path rule relaxed to allow underscores, and redaction removed. All caught.

### 1.24 — A wrong-typed required key was also reported as missing

Found by the test asserting every problem is reported at once: a required key
with a bad value produced both `must be an integer` and `missing required key`.

It is not missing. Telling an operator to add a key they can see in the file
sends them looking in the wrong place entirely. The "missing" complaint now
fires only when the key was genuinely absent from both the file and the
environment.

### 1.25 — Two more measurement defects in the mutation harness

Recorded as a pair with 1.18, because the shape kept recurring and each time it
produced a *false negative* — a mutation reported as uncaught.

First: a mutation that replaced two `if` branches with an unconditional `return`
made the following statements unreachable, so the module did not compile. No
result files were written, and the harness read "zero failures".

Second: the same reading applied whenever a task did not run at all.

Both fixed by asserting the mutation target exists before editing, and by
reporting the number of tests that actually *ran* alongside the number that
failed. A green result is evidence only if the thing ran; so is a red one.

The three findings together — Phase 0's UP-TO-DATE task, 1.18's aborted build,
and these — are all the same error: trusting an absence of failure without
establishing that anything looked.

### 1.26 — One dispatcher, two transports

Two transports ship because connectors come in two shapes: a daemon holds a
socket open and authenticates once; a connector that exists only while serving a
request cannot hold anything open, so each of its requests stands alone and is
signed.

`Dispatcher` knows about neither. It takes a schema version, an operation name,
a credential and a payload, and returns a `WireResponse`. That is what lets both
transports share one authorization path instead of growing two that diverge —
and it is what makes authorization testable without standing up a server.

The order inside it is fixed and deliberate: **schema, then credential, then
operation, then capability.** An unknown operation is reported only to a caller
that already authenticated; handed to an anonymous caller it is a free oracle
for probing which operations a build supports.

### 1.27 — Refusals are HTTP 200 with the reason in the envelope

A protocol refusal is not a transport failure. Mapping refusals onto status
codes gives every intermediary — proxy, CDN, corporate filter — an opinion about
them, and the first time one acts on that opinion the failure looks like
soulbind's.

`unknown-credential` covers absent, blank and unrecognised credentials alike.
Distinguishing them tells an attacker whether a token they guessed exists; the
operator-facing detail belongs in the audit log, not in a reply to whoever
asked.

### 1.28 — Replay protection is two halves, and the window is symmetric

The timestamp window bounds how long a captured request is useful; the nonce
store makes it useful only once inside that window. Neither alone is enough.

The window rejects timestamps far in the **future** as well as the past. A
one-sided check would let a captured request be given a distant timestamp and
stay replayable indefinitely — the window with its lid off.

Freshness and single-use are checked **before** the signature, because the
signature is a keyed hash over the whole body and verifying it first would let
anyone force unbounded HMAC work with no credential at all. The cost is that a
caller learns "stale" before "bad signature", which is not worth defending: the
timestamp is a value they supplied and the clock is not a secret.

`NonceStore.recordIfNew` is `putIfAbsent`, not check-then-act. Two requests
carrying the same nonce can arrive on different threads at the same moment, and
a check-then-act lets both through — which is the replay it exists to stop, and
only under load, which is when somebody is most likely to be trying.

**Stated narrowing:** at its ceiling the store refuses rather than evicting. A
sustained flood of unique nonces therefore degrades into refusing legitimate
signed requests. That is the correct trade for an authentication control — the
alternative, evicting live entries, is a replay window an attacker can open on
demand — and it is named here rather than discovered.

### 1.29 — `hello` answers with the intersection, not the claim

A connector declares what it claims; core answers with what the credential was
actually granted. Claiming a capability does not grant it, and the connector
learns the truth at handshake rather than one refusal at a time.

**Caught by mutation-checking, and the round-trip test could not see it.** The
test's connector claimed exactly what it held, so "the claim" and "the
intersection" were the same list, and removing `retainAll` passed unnoticed. A
second test now claims strictly more than it holds — and asserts not only that
the extra capability is absent from the answer but that it genuinely does not
work, which is the property the answer is describing.

Unrecognised claimed names come back in `ignored` rather than being dropped. A
connector built against a newer protocol should be able to see that.

### 1.30 — A nonce with a newline cannot be tested over HTTP

`RequestSigner` refuses a nonce containing the field separator, because escaping
it would let two different `(nonce, body)` pairs canonicalise identically — a
signature forgery wearing a helpful hat.

That refusal **cannot be reached over HTTP**: `java.net.http` refuses to build a
request with a newline in a header value, and so does every conformant stack,
because header injection is the reason. The test was moved to
`SignedRequestVerifierTest`, where it is reachable, and its absence from the
HTTP suite is named there so it reads as a decision rather than an oversight.
The defence is not theoretical — the socket transport and the PHP client both
build the canonical form themselves.

### 1.31 — `StorageBackends.any()`, so a test need not name a backend

The storage seam guard fired on the transport tests, which named `Backend.SQLITE`
directly. The catch was correct and the fix was not an exemption: those tests
genuinely do not care which backend they get — signing and replay are
backend-independent — so naming one gave them compile-time knowledge of which
database is in use for no reason.

`any()` says what is actually meant. Parameterising them over both backends
instead would multiply runtime to re-prove what the storage tests already cover.

### 1.32 — The transport seam guard

No HTTP or WebSocket type outside `core/transport` or `sdk/transport`. The
failure it prevents is specific: authorization or replay logic that can only be
exercised by standing up a server. Such logic still works — it just cannot be
tested cheaply, so it is tested less, so it is where the bugs go.

Exempted on the **package** path, so each transport's own tests are covered by
the same exemption, for the reason established in 1.7. Comments are stripped
before matching, the same stated narrowing as 1.8.

*Mutation-checked* along with the rest of the transport layer: one-sided
timestamp window, replay check removed, signature never checked, nonce store
back to check-then-act, authorization result ignored, schema accepted blindly,
and an unauthenticated socket left open. All caught.

### 1.33 — The fuzz oracle is four properties, not "the right things are rejected"

Many corpus values are perfectly legal and must succeed. An oracle of "this is
rejected" would need a second implementation of every rule to compare against,
and would be wrong wherever the two disagreed with no way to tell which.

The four properties need no second implementation and hold for every input a
caller can construct: never a 5xx; every response a well-formed envelope; never
`internal` — an unhandled path reporting itself through the envelope rather than
as a crash; and the server still answering afterwards, checked last against a
request known to be good.

*Mutation-checked:* a deliberate crash on operation names over 40 characters.
Both fuzz tests caught it, naming the input.

### 1.34 — The fuzz tier gets its own never-up-to-date task

**The second instance of the Phase 0 defect, and it would have been invisible.**
A fuzz run draws a fresh seed and explores inputs no previous run tried, so a
cached success is a statement about a run that already happened. Gradle marked
`:core:test` up-to-date and the second invocation explored nothing — which
looked exactly like a passing fuzz run.

`fuzzTest` therefore has `outputs.upToDateWhen { false }` and is wired into
`check`.

**Stated narrowing:** the `fuzz` tag is excluded from the default test task, so
the tier runs once per build rather than twice. It covers exactly that tag, and
`check` still runs it.

Wiring it up produced a third instance of the same family: `configureEach`
applied `excludeTags("fuzz")` to *every* Test task including `fuzzTest`, and
JUnit resolves a tag that is both included and excluded as excluded — so
`fuzzTest` ran **zero tests** and reported success. Caught only by counting tests
run rather than reading the exit code. The exclusion is now conditional on the
task name, and the comment says why.

### 1.35 — Hostile credentials are fuzzed at the dispatcher, not over HTTP

The first fuzz run reported 17 violations, every one of them
`java.net.http: invalid header value` — my own test client refusing to build the
request. Non-ASCII and control characters cannot appear in an HTTP header value
at all, in any conformant stack.

Fuzzing them there would measure the client, not the server. But the full value
space *is* reachable through the dispatcher, which the socket transport and the
PHP client both feed directly — so a second fuzz test drives the dispatcher with
the whole corpus in the operation, credential and payload positions. The HTTP
fuzz restricts the credential position to header-safe values, and says so.

Same shape as 1.30: the defence is real, and it is tested where it can be
exercised rather than where it cannot.

### 1.36 — Seeds are printed on success as well as failure

If the seed were printed only on failure, the first failure would be the first
time anybody found out whether the printing worked. Every run prints; every run
replays with `SOULBIND_SEED`.

Verified rather than assumed: two runs at the same seed print the same seed and
the task genuinely re-runs; a run without one draws a different seed.

### 1.37 — The second backend found four defects the first could not

The first run with a real MariaDB was the most productive hour of Phase 1. None
of these was visible on SQLite, and none was a MariaDB quirk — each was a real
defect that SQLite's single-writer executor or per-test temp file had been
hiding.

**The storage suite was isolated only by accident.** Ten tests failed at once,
every one because an earlier test's rows were still there — "a fresh log starts
at 0" finding 2. SQLite gets a fresh file per `@TempDir`; a server-backed store
gets nothing for free. `StorageBackends.open` now drops and recreates the schema
for MariaDB, which also re-runs the migrations, so every test exercises them
rather than only the first.

Fixing that broke the idempotence test, correctly: it *reopens* its store on
purpose and must get the rows back. `open` (clean) and `reopen` (same store) are
now separate, because conflating them is what made the failure possible.

**Audit sequence assignment was not atomic.** `SELECT COALESCE(MAX(seq),0)+1`
then `INSERT`, inside a transaction, with a comment claiming the transaction
prevented collisions. It did not — a SELECT takes no lock, so two appenders read
the same maximum and one loses on the primary key. 200 concurrent appends
produced **45** distinct sequences.

Replaced with an allocator row updated in place: `UPDATE` takes an exclusive row
lock, so a concurrent appender blocks until the first commits. Portable, so no
dialect needs its own migration. A database-native auto-increment would also
work and was rejected: the two dialects spell it differently, which would put
the audit table's DDL in two files that must agree forever — and the per-dialect
directories are for differences a dialect genuinely forces, not ones a choice
here created.

**The test that should have caught it swallowed the evidence.** The writer tasks
were submitted and never checked, so 155 primary-key violations read as "45
sequences" rather than "155 appends threw". `awaitTermination` returning true
says the threads stopped, not that they succeeded. Every future is now checked.

That is the same error as 1.18, 1.25 and 1.34: an absence of failure taken as
evidence without establishing that anything looked. Four instances now, in four
different disguises.

**A WebSocket test misused the client.** Two `sendText` calls without awaiting
the first — which `java.net.http.WebSocket` forbids, and which silently loses a
message rather than throwing. It passed on the workstation and failed on the
first Linux run.

### 1.38 — Departure 5: the run stage lands early, and narrowly

§14 populates `[run]` in Phase 8 with the full battery. It carries one image and
two tiers now, because Phase 1's gate asks for the fuzz tier clean on *both*
backends and no MariaDB is reachable from the workstation. Claiming the gate
without it would have been a claim no test report could contradict — a skipped
backend leaves no failure behind.

`[run] exec = "host"`, which the manifest schema exists for: the build needs a
pinned toolchain image, the run needs the guest's own container engine, and a
toolchain image has no engine client inside it. The first attempt ignored that
and failed with `podman: not found` — the schema's sentence restated as an exit
code. The second hardcoded `/reaper/work`, which is where the tree appears
*inside* the toolchain container, and failed with `statfs: no such file or
directory`.

Flarum, Paper and Velocity are still Phase 8. The departure covers the run
stage's arrival, not its scope.

### 1.39 — Audit attribution is decided by core, and the DTO has no actor field

`AuditPushRequest` carries no actor. Not "ignored if present" — absent from the
schema, so a payload naming one is rejected by the codec's unknown-field
handling rather than silently dropped. A connector that thought it had set the
actor and had not would be worse off than one told no.

The actor is `connector:<id>`, from the credential core authenticated. A
connector able to name its own actor could attribute its actions to another
connector, or to a person, and **an audit log whose attribution the subject
controls is not evidence of anything.**

*Mutation-checked, and the first attempt missed it.* Making the actor depend on
`subjectId` passed, because the attribution test pushed a minimal entry with no
subject. Both shapes are now asserted — a defect that forged the actor only when
some other field was present would otherwise show up on neither.

`audit.push` requires `audit-source`; `audit.query` requires
`config-management`. Deliberately different: a connector that can *write* audit
events should not thereby get a window onto everything else.

### 1.40 — The audit-immutability guard, and the one thing it exempts

No `UPDATE audit`, `DELETE FROM audit`, `TRUNCATE audit` or `DROP TABLE audit`
in production source or in any migration, and no method on `AuditRepository`
whose name suggests mutation. Both directions, because an interface can grow a
method and an implementation can write SQL without one.

**Exempted: `audit_seq`.** It holds one integer — the next sequence to hand out
— and updating it is how a sequence is allocated. It records nothing that
happened. The exemption covers exactly that table, and a test asserts both
halves: that allocating does not read as rewriting, and that a real
`DELETE FROM audit` still does. Stated as a test rather than a comment because
a later reader would otherwise take the exemption for an oversight and "fix" it,
breaking every append.

**Narrowed to `src/main`, with the reason.** The guard fired on
`AuditRepositoryTest`, which holds `"'; DROP TABLE audit; --"` — a hostile value
in the test that proves the repository resists injection. A guard that fires on
the test proving the defence works gets suppressed rather than obeyed. What this
does not cover: a test deleting audit rows to mask a failure. The interface
offers no way to, and reaching past it means raw JDBC in a test, which is
visible in review in a way a quiet `delete()` would not be.

### 1.41 — Three verbs, and everything else is an operation

`soulbind doctor`, `register`, `serve`. Everything else an operator might want
is an *operation* reachable through an admin credential under the same
authorization table — not a second management surface with rules that drift from
the first. That is the same reasoning that put the admin API under one
capability table.

`serve` runs the configuration checks before binding. A configuration that would
fail `doctor` should not silently start: the failure it names would otherwise
surface as a runtime symptom somewhere unrelated.

`Main.run` takes its streams and returns an exit code rather than calling
`System.exit`, so the tests assert outcomes instead of scraping a terminal. A
command whose only interface is a terminal is a command nobody tests.

### 1.42 — The doctor reports what to DO, and a check that cannot run is not a pass

Every finding carries an action, not only a problem — a finding the reader has
to research before they can act is a finding that gets ignored. Asserted: no
finding may have a blank detail.

A wildcard bind WARNS rather than fails: it is a legitimate deployment behind a
reverse proxy, and the doctor's job is to make sure it was *chosen* rather than
inherited. A password written into the config file also warns — "it will be
committed by somebody eventually" — and the warning does not print the password
it is warning about, which a test asserts.

*Mutation-checked:* missing config treated as fine, doctor always exiting 0,
the wildcard warning removed, duplicate connector names permitted, unrecognised
capabilities silently dropped, and — the worst one — the credential **plaintext**
stored where its hash belongs. All caught.

### 1.43 — Two more tests that passed for the wrong reason

Both found while writing them, both the same shape as 1.15.

The unknown-key test appended a second `[server]` table, which makes the file
invalid TOML — so it failed the config on a syntax error and would have kept
passing with unknown-key detection deleted entirely. The typo now goes inside an
existing table and the assertion names the expected message.

The audit-wire helper signed with the wall clock while the server ran on a fixed
one, putting every timestamp decades outside the freshness window. It failed
loudly rather than passing, so it cost minutes rather than trust — but it is the
same class: a test whose setup does not match the thing under test.

### 1.44 — Test logging turned down so the fuzz seed is visible

`fuzzTest` sets `showStandardStreams` for one reason: the seed line. At the
pool's default DEBUG level that line arrives under two hundred lines of
configuration dump, which defeats the point.

`logback-test.xml` sets third-party libraries to WARN. **This silences nothing
that matters:** warnings and errors still print, and a test failure is reported
by the framework regardless of log level. soulbind's own logger stays at INFO —
if core says something during a test, that is worth seeing.

## Phase 2 — identity graph and linking

### 2.1 — A subject has no name, no email and no password

soulbind does not have accounts of its own. Adding one would make it a thing to
be logged into, breached and reset — and it would immediately become the account
people actually care about, which is the opposite of the point. A subject is the
join between identities and nothing else.

### 2.2 — `(platform_kind, platform_id)` is unique across the whole table

Not per subject. A platform account belongs to at most one person, and letting
two subjects claim the same one is how two people come to share an entitlement
nobody granted twice. Enforced as a database constraint rather than a check
before the insert: a check would race, and the race — two connectors binding the
same account at once — is exactly the case that matters.

### 2.3 — Identity flags are JSON, and core never branches on them

A column per platform trait would put a platform name in the schema, which is
the compile-time knowledge the dispatcher must not have. A connector sets what
it knows about its own platform's accounts and reads it back; core carries it
without understanding it. The moment core reads a flag to decide something, the
seam is gone.

### 2.4 — Single use is one statement, and expiry is not in its predicate

`UPDATE link_code SET redeemed_at = ?, ... WHERE code = ? AND redeemed_at IS
NULL`. One row updated means this caller claimed it. No read-then-write, no
lock, and no dependence on an isolation level that differs between the two
backends — otherwise the version that worked in testing is the version that
failed in deployment.

Expiry is checked separately and *before*, so an expired code is refused as
expired rather than as already-used. Different problems, different fixes, and
collapsing them sends the person to ask the wrong question. An expired code is
also not consumed, so the reason stays truthful on the next attempt — a test
asserts that specifically.

*Mutation-checked:* predicate dropped from the claim, claim result ignored,
expiry made inclusive, same-account check removed, already-linked check removed,
and the live code written into the audit log. All caught.

**One mutation was equivalent, not uncaught.** Removing the early
`isRedeemed()` return lets a redeemed code fall through to `claim`, which
returns false and produces the *same* `ALREADY_REDEEMED` refusal. The two paths
differ only in the human-readable string and in whether a pointless write is
attempted. Recorded as equivalent rather than papered over with an assertion on
prose.

### 2.5 — Expiry is exclusive at the boundary

A code expiring exactly now is still good. "Expired" reading true at the stroke
of the deadline makes the advertised lifetime a lie by one second, and somebody
typing at the last moment should succeed. Both edges are tested — at the instant,
and one millisecond after — which is only possible because the TTL check takes
the instant as a parameter instead of reading a hidden clock.

### 2.6 — There is no merge, and the absence is written down

An operation folding two subjects into one needs a rule for every conflicting
field, and the first time it ran on the wrong pair it would be unrecoverable:
the identities would afterwards be indistinguishable from ones legitimately
linked. Two accounts that already belong to different people is therefore a
refusal. Unlink and re-link is the supported path, and it leaves a trail.

The absence is a comment in `IdentityRepository`, not silence, so the next
person to want one finds the reasoning rather than an apparent oversight.

### 2.7 — Re-linking creates a new identity

A resurrected row would silently carry its old verification date, and policy
asking "how long has this been proven" would get an answer about an account that
had been unlinked in between. Unlink is hard for policy and soft for audit: the
row goes, the history stays.

### 2.8 — The link code is never audited

Until it is redeemed or expires, a code is a live secret. An audit log readable
by anyone holding `config-management` would otherwise be a list of working
codes. The issue event records *which account* a code was issued for, which is
the fact worth keeping.

### 2.9 — Vectors are generated by a third party, not by either implementation

The signatures were produced by Python's `hmac` and cross-checked against
`openssl`. Neither the Java nor the PHP side generated them.

Generating at test time would prove both sides agree with the generator, which
is one implementation wearing a hat. Worse, regenerating from Java would
silently absorb a Java-side mistake as the new expectation. A committed file is
a third party, and a change to it shows up as a diff in review.

Two corpus-shape assertions guard against a vacuous suite: the signing vectors
must all be distinct (a constant-returning implementation would otherwise pass a
reduced corpus), and the normalisation corpus must carry at least ten rows of
each outcome (forty acceptances and no rejections would pass with rejection
deleted entirely).

Invisible characters are written as `\uXXXX` escapes rather than literally. A
literal zero-width space in a vector file is indistinguishable from a typo, and
the next person to edit the file deletes it by accident.

*Mutation-checked:* hyphen no longer stripped, case folding removed, alphabet
check removed, and the invisibles no longer stripped. All four caught in both
the ordinary run and the hostile-charset run.

### 2.10 — The gate uses two connectors, not one holding both capabilities

A single credential doing the whole flow would prove the linking logic works
while asserting nothing about the property the capability model exists for. The
wire test registers a second connector with its own credential and asserts that
the display side cannot redeem and the entry side cannot issue — because if
either could do both, a compromised chat bot could mint a code for any account
and redeem it against one it controls.

### 2.11 — `attest` records the method, not a boolean

Policy is entitled to care about *how* something was proven: a gate may accept a
link code for one thing and demand something stronger for another. An account
nobody has seen before gets a subject of its own — one identity is a person
known on one platform, which is the honest representation of what the connector
asserted, and calling it a link would not be.

### 2.12 — A `wasNull()` ordering bug, caught while writing it

`ResultSet.wasNull()` reports on the last column read. The identity mapper
checked it after a later fetch, so an unverified identity would have come back
verified exactly when `proof_method` happened to be present. Captured
immediately after the column it describes now, with a comment saying why.

### 2.13 — The reaper sync patterns were anchored, and should not have been

`"/build/"` matches only the repository root, so every module's build output was
being copied into the session — megabytes of class files, and a moving target.
A sync racing a local Gradle build failed with `file has vanished:
core/build/tmp/compileJava/.../Main.class.uniqueId1`.

`"build/"` without the leading slash matches at any depth, which is what was
meant. `/out/` stays anchored deliberately: it is where results come back *out*
of the guest, and only the root one is that.

### 2.14 — Bedrock translation lives in the connector, and is the point of the seam

Bedrock clients reach a Java server through Geyser; Floodgate gives them a UUID
in a reserved range and usually a prefixed name. Those are conventions of that
stack, and `connector-velocity` is where knowing them is allowed — core sees the
same platform kind with `flags.bedrock = true` and never branches on it.

**The UUID is the identity; the name is not.** A prefix is configurable, can be
turned off, and changes when an operator decides. Treating it as the identifier
is how a rename silently reassigns an entitlement.

Only one leading prefix is stripped: a player legitimately named `..Alex` behind
a `.` prefix becomes `.Alex`. Stripping repeatedly mangles a real name, and a
mangled display name is worse than an odd one because it looks correct.

A Java player gets **no** bedrock flag rather than `bedrock = false`. Writing one
would put a Bedrock-shaped field on every identity in the system and invite a
reader to treat its absence as "unknown" rather than "no".

The XUID renders unsigned. An XUID can exceed `Long.MAX_VALUE`, and a signed
rendering produces a negative number matching nothing — only for accounts above
the boundary, which is to say in production and not in a hand-written test.

*Mutation-checked:* XUID signed, prefix stripped everywhere, Java players given
`bedrock = false`, display folding case. All caught.

### 2.15 — A comment that was simply wrong, caught by a mutation passing

One mutation was **not** caught: replacing the structural Bedrock-UUID check
with `toString().startsWith("00000000-0000-0000")`. Investigating showed the two
are genuinely equivalent — `UUID.toString()` always emits the same dashed
layout — so there is no input that distinguishes them.

The comment justifying the structural check claimed the string form "varies with
how it was rendered". That is false. It has been corrected to the two reasons
that are true: no string allocation on a path taken for every player join, and
no dependence on a JDK formatting detail this code has no business relying on.

Recorded because the mutation did its job in an unusual way — it did not find a
defect in the code, it found a defect in the *reasoning*, which would have
misled the next person to touch it.

## Phase 3 — policy engine and decisions

### 3.1 — The evaluator is a module with no dependencies

Its dependency block is empty, and that is the design. Evaluation is a pure
function of `(snapshot, rules, overrides, clock)`; it has no storage, no
transport, no JSON and no logging, so there is nothing for a dependency to be
*for*.

That emptiness is what makes the Tier 4 matrix exhaustive rather than
representative: 253 rows call the function directly and run in milliseconds. A
dependency appearing here is the signal that I/O has crept in.

### 3.2 — Precedence, and the apparent contradiction in it

Overrides beat rules; deny beats allow; no rule means allow; grace is checked
after establishing that requirements are unmet.

**Deny beats allow** because wrongly denying costs a complaint and wrongly
allowing costs the thing the gate existed to prevent. Both orderings of the
override list are tested, because a precedence that depended on list order would
be reproducible only by accident.

**No rule means allow** because a gate nobody configured is a gate nobody asked
for — denying would mean every new gate silently locks out everybody the moment
a connector declares it. This sits beside the *opposite* default in the SDK,
where an unreachable core denies. They are not contradictory: here there is
nothing to enforce, there enforcement has failed.

**Grace after requirements** because a subject who already satisfies the rule
should be allowed for that reason, not for a window that happens to still be
running. "Allowed, grace" and "allowed, requirements met" describe different
futures, and the decision log is read by people deciding what happens next.

### 3.3 — A grace decision cannot be cached past the end of grace

Otherwise a connector caches "allow, because grace" for sixty seconds, grace
lapses ten seconds in, and the gate stays open for the remaining fifty. It would
be advisory rather than enforced, and only intermittently — worse than absent,
because somebody would have tested it and seen it work.

The TTL is clamped to the remaining grace, and a TTL of zero means "do not cache
this", which the SDK honours by evicting rather than storing.

### 3.4 — "Linked" means two identities, not one

A subject with a single identity is a person known on one platform — which is
what an attestation produces. Calling that linked would let a gate demanding a
link be satisfied by the very account asking.

### 3.5 — An override needs a reason and exactly one target

No reason, no override: one nobody can review will outlive whoever added it.
Naming both a subject and an identity makes it ambiguous which was followed;
naming neither makes it apply to everybody. Both are refused at construction,
where the mistake is cheap.

Expired overrides are ignored *inside* the engine rather than filtered by the
caller, so a caller that forgets cannot accidentally honour a lapsed one.

*Mutation-checked:* deny no longer beating allow, expired overrides honoured,
the grace boundary flipped, the grace TTL unclamped, one identity counting as
linked, grace removed entirely, and an unconfigured gate denying everybody. All
seven caught.

### 3.6 — Fail-closed is the default, and a typo cannot open a gate

`DecisionCache.FailMode.fromConfigName` maps **only** the exact word `open` to
`OPEN`. Everything else — a typo, an empty string, `yes`, `true`, `1`, null —
becomes `CLOSED`. A mistake in a fail-mode must never be the thing that opens a
gate, and a thrown exception would be a start-up failure an operator fixes by
guessing.

Fail-open remains spellable, because some gates genuinely should not lock a
community out of its own forum over a network blip. The point is that it is
chosen rather than inherited.

The user-facing message blames **the system**: somebody refused because a server
they have never heard of is unreachable should not be told they are not allowed.
A test asserts the message does not read as a refusal of the person.

Denials are cached too. Caching only allowances would mean an outage silently
upgrades every recent denial to whatever the fail mode says — and under
fail-open, that is an upgrade to allow.

### 3.7 — Latency is measured, printed, and deliberately not gating

**p99 = 2.5µs** over 200,000 calls against the specification's 50ms target
(p50 396ns, p99.9 8.9µs). Recorded in STATUS.md.

Its own Gradle task, not part of `check`. The specification says the figure is
informational, and a number that fails a build is a number somebody will loosen
until it stops failing. The assertion that remains is a 50ms ceiling — loose
enough that crossing it means something is doing I/O, not that the machine was
busy.

The measurement warms up first, because measuring a cold JIT measures the JIT,
and it uses a realistic override distribution rather than the empty list —
measuring only the fast path and calling it the budget would be the easy
mistake.

### 3.8 — `Override` was the wrong name for a Java class

`dev.soulbind.policy.Override` shadowed `java.lang.Override`, which is implicitly
imported into every Java file. Any class importing the policy type lost the
ability to write `@Override` — which showed up the moment a repository
implemented an interface, with three compile errors reading "Override cannot be
converted to Annotation".

Renamed to `PolicyOverride`. The collision is not a Java quirk worth working
around with import gymnastics; it is a name that was wrong.

### 3.9 — Gates are recorded when a connector first asks about one

A connector calling `decide` for a gate is declaring the gate exists. Recording
it there means an operator can see the gate in order to write a rule for it —
otherwise the only way to learn a gate's name is to read the connector's source.

### 3.10 — The override path was reachable only in unit tests

**Found by mutation-checking.** Replacing `policy.overridesFor(gate)` with an
empty list passed every wire test. The override mechanism — how an operator
admits or bans one person — was exercised only through the engine's own tests,
and nothing proved core actually consulted it.

Wire tests now cover an allow override, a deny override winning over it, an
override for a different gate not leaking in, and an expired one not applying.

### 3.11 — The T8 race asserts coherence, not stability

A rule mutating under a storm of decisions **must** change the answer — that is
the point of editing a rule. The claim worth asserting is that every answer is
one of the two coherent ones and never a blend of a half-applied edit, so the
test pairs each effect with the reasons that can produce it and counts
mismatches.

Asserting stability instead would have been asserting that editing a rule does
nothing.

## Phase 4 — events and effectors

### 4.1 — An outbox, written in the same transaction as the change

Calling a subscriber inline would make every mutation's latency depend on the
slowest subscriber, and an event emitted by a call that failed is an event
nobody hears about. Writing it beside the change is what makes "the change
happened but nobody heard" impossible.

There is **no delete**. An event removed is an event a connector that was down
will never receive, and "it was probably fine" is not checkable afterwards.

### 4.2 — At-least-once, said plainly

Exactly-once across a network does not exist. What exists is at-least-once plus
an idempotency key, and being honest about which is on offer is how a connector
author learns they must dedup. The key is stable across redeliveries and across
subscribers — if it were regenerated per delivery it would dedup nothing while
looking, from the transport's side, exactly like a correct system.

The gate is asserted **by reading the effector's state back**, never by counting
deliveries. A count proves what the transport did; the state proves what
happened to the world. Under at-least-once, the count is the wrong number to
assert anyway.

### 4.3 — The cursor advances on acknowledgement, never on send

Advancing on send turns a delivery lost in flight into an event nobody will ever
receive — the whole failure the outbox exists to prevent. Acknowledgement is
cumulative, because a cumulative one cannot leave a hole the way a per-event
scheme can, and it never moves backwards: replay is survivable, but a buggy
acknowledgement replaying the entire history is a very different amount of work
arriving without warning.

Cursors are per connector. A shared position would mean whichever subscriber was
fastest decided what the others never saw.

`event.ack` is unprivileged, because a connector can only move its own cursor —
the id comes from the credential, never the payload.

### 4.4 — The sequence allocator, adopted before it was needed

Events allocate sequences by `UPDATE ... SET next_seq = next_seq + 1`, the same
mechanism audit uses. Adopted here from the start rather than after a
multi-writer backend produced 45 distinct sequences out of 200 — which is what
happened the first time, for audit, in Phase 1.

### 4.5 — An unknown event type throws rather than being skipped

A row this build cannot deliver is a version mismatch. Skipping it would deliver
everything around it and hide the gap, and a subscriber has no way to notice.
Refusing makes the mismatch visible.

### 4.6 — The event doc-sync guard

Every `EventType` constant appears in `docs/protocol.md`'s event table and vice
versa. Events are the one place a connector acts on something core said
happened, so an undocumented one is a side effect nobody can audit — and a
documented one that does not exist is a handler waiting for something that never
arrives.

The operations guard caught `event.ack` the moment it was added, and the
authorization matrix caught it separately: I had added an operation without
deciding its authorization in the contract. Both did exactly what they were
built for.

*Mutation-checked:* an event type added but undocumented, and a documented type
that does not exist. Both caught.

### 4.7 — A ceiling that no test could reach

**Found by mutation-checking.** Removing the page-size clamp passed every
delivery test, because they ask for more events than exist — the ceiling never
binds at test scale, and pressing against it through the wire would mean
creating a thousand events to prove one integer.

The clamp is now a small extracted function asserted directly, at the boundary
and past it. The general shape is worth remembering: a bound that only matters
at production scale is a bound no integration test will exercise, and it needs
to be reachable some other way.

*Mutation-checked:* cursor advanced on send, idempotency key regenerated per
delivery, cursor allowed to move backwards, events delivered newest first, and
the ceiling removed. All five caught — the last only after the clamp was made
observable.

### 4.8 — Two insert-if-absent races, and a 5xx that should have been impossible

**Found by the T8 rule-mutation race against a real multi-writer backend.** The
test failed with a Jackson parse error on a response body beginning `Server` —
an HTTP 500 error page, not a protocol response.

Three defects, one visible symptom.

**`platformKind.seen` and `gate.seen` were SELECT-then-INSERT.** Two callers see
"not present" and both insert. Invisible for two phases, because SQLite's
single-writer executor serialises every write; the first time eight threads
called `decide` concurrently against MariaDB, one lost on the primary key.

**The dispatcher let the resulting exception escape to Javalin**, which rendered
an HTML error page. The no-5xx property was asserted by the fuzz oracle and
enforced nowhere — the fuzzer never made a handler throw, so the gap held.
Nothing escapes now: a handler failure becomes an `internal` refusal in a
well-formed envelope, with the cause logged rather than sent, because an
internal failure that tells a peer why is an information disclosure. An `Error`
still propagates — catching `Throwable` would turn a dying JVM into a stream of
polite denials.

**The obvious fix for the race did not work portably.** Catching the exception
and testing for a uniqueness violation fails because one driver reports SQLState
class 23 and the other reports `null`, putting the detail in a vendor result
code. Matching on either would put dialect knowledge in the seam — which is
what the seam exists to prevent.

`Jdbc.ensureExists` asserts the **outcome** instead of classifying the error:
attempt the insert, and if it fails, ask whether the row is there now. If it is,
the thing the caller wanted is true, whoever made it true — which is exactly
what "ensure it exists" means. If not, the failure was something else and is
rethrown.

The regression test asserts the transport property directly, with a handler that
throws, so it does not need MariaDB to hold. Reproducing the original conditions
would have; asserting the property does not.

### 4.9 — The SDK dedups, rather than documenting that connectors should

A rule enforced by a paragraph is a rule that holds until somebody is in a
hurry. `IdempotentApplier` records the key **before** running the effect and
removes it again **if the effect throws** — recording after would let a crash
between effect and record cause a re-apply, and not removing on failure would
mark an effect applied that never happened and swallow the retry.

**It evicts rather than refusing**, which is the opposite of the replay-nonce
store, deliberately. There, forgetting a nonce means failing to detect a replay
— a security control, so it fails closed. Here, forgetting a key means applying
an idempotent effect twice, which is harmless by definition, since that is what
makes it worth deduping. Refusing to apply events because a cache filled would
be an outage caused by bookkeeping.

Eviction is access-ordered, so a connector being hammered with one repeated
redelivery keeps that key live — which is exactly the case the dedup is for.

## Closing the single-writer blind spot

### X.1 — The executor is a deployment necessity, not a correctness mechanism

Four defects of one shape reached the repository, each invisible on SQLite and
each found only when a multi-writer backend ran: audit sequences allocated by
`SELECT MAX+1`; `platformKind.seen` and `gate.seen` as SELECT-then-INSERT (which
reached a live HTTP 500); and an event cursor advanced by SELECT-then-UPDATE,
which could move **backwards** and redeliver events a connector had applied.

The last was found by the guard below, not by any test — no test on this
workstation could have found it.

The single-writer executor exists because SQLite permits one writer. It is a
**deployment** necessity. For four phases it also silently supplied correctness
the repositories had not earned, and the distinction only became visible when
something removed it.

### X.2 — What could be fixed here, and what could not

**`Storage.openWithoutWriteSerialisation`** opens SQLite with a real pool and no
executor, so the concurrency contract suite can let writes interleave. Package-
private and test-only: a deployment running SQLite unserialised would meet
SQLITE_BUSY under load, which is the intermittent failure the executor prevents.

**It is not sufficient, and the reason is worth recording.** Reverting the three
historical defects, the unserialised suite caught only one. SQLite serialises
write transactions *at the engine level* — the interleaving that produces
check-then-act defects **cannot occur** there, whatever this project configures.
That is not a gap in the harness; it is what SQLite is.

So the runtime approach has a ceiling, and the honest conclusion is that this
defect class needs a mechanism that reads the code.

### X.3 — The check-then-act guard

Flags any `jdbc.write` block that reads before it writes, unless it uses
`Jdbc.ensureExists` — which reads *after* the write fails rather than before it,
the inversion that makes the race harmless — or carries a
`CHECK-THEN-ACT REVIEWED:` comment saying why it is safe.

Not a suppression mechanism: the comment has to state the reasoning, and it
lives where the next reader is. Both sequence allocators carry one, because
their read follows an atomic `UPDATE` holding the row lock — write-then-read-
what-I-wrote, not read-then-decide-then-write.

**What it does not prove:** that the flagged shape is always wrong, or that its
absence means correctness. It proves every read-then-write in the storage layer
was looked at by somebody, which is the property that was actually missing.

### X.4 — The guard was disarmed by a comment, and its own mutation check found it

Reverting `platformKind.seen` to its historical form did **not** trip the guard.
The reverted code still carried the comment "See `Jdbc.ensureExists` for why…",
and the exemption was matched against the raw line — so prose mentioning the
sanctioned helper switched the guard off for the whole block.

Exactly the lesson the storage seam guard already carried, unlearned in a new
place: the exemption is now matched against code with comments stripped, while
the REVIEWED marker is still matched on the raw line, because that one **is**
meant to be a comment.

A guard an explanation can switch off is not a guard.

### X.5 — The contract suite paid for itself within the hour

The first MariaDB run of the new suite deadlocked: twelve threads acknowledging
one cursor, and InnoDB rolled one back. The caller received an exception.

That is a fifth defect of the same family — something the multi-writer backend
does that the single-writer one cannot — and it was found by a test written
specifically to look for that family, rather than by a phase gate stumbling
into it.

`Jdbc.write` now retries a rolled-back transaction, bounded, with a growing
pause. Dead code on SQLite. Load-bearing on the other backend.

**The running tally, for whoever reads this later.** Five defects, one shape,
all invisible on the single-writer backend:

| Defect | Found by | Consequence if shipped |
|---|---|---|
| Audit sequence by `SELECT MAX+1` | First MariaDB run | 45 sequences from 200 appends |
| Storage tests isolated by accident | First MariaDB run | Tests passing for the wrong reason |
| `platformKind.seen` check-then-act | T8 race on MariaDB | HTTP 500 |
| `gate.seen` check-then-act | The same 500 | HTTP 500 |
| Cursor `SELECT`-then-`UPDATE` | The check-then-act guard | Redelivery of applied events |
| Cursor acknowledgement deadlock | The contract suite | Exception to the connector |

Three mechanisms now cover it: the contract suite (runtime, needs a real
multi-writer backend), the check-then-act guard (static, runs anywhere), and
`reaper test` after every phase. The guard is the only one that works on this
workstation, which is why it exists.

## Phase 5 — connector-velocity

### 5.1 — The transport seam, and what it buys

`Transport` is one method. Everything above it — envelope construction, signing,
refusal handling, decision caching — is tested against `InMemoryTransport` with
no socket. That makes conditions a real network cannot be asked for on demand
one line each: a core reachable for one call and gone for the next, a truncated
body, a gateway answering in JSON.

The Velocity API comes from the PaperMC repository, which is the only reason a
second repository exists. Content-filtered to `com.velocitypowered`, because a
typo'd coordinate resolving from an unexpected host is how a supply chain gets a
participant nobody chose. Version 3.5.1, a release rather than the estate's
snapshot, and `compileOnly` — the proxy supplies it, and the api module is MIT
while the proxy is GPLv3, which is what keeps this distributable.

### 5.2 — Refused is not unreachable, and two consequences

A refusal is core saying no: tell the person, do not retry, and **do not consult
the cache**. Serving a cached allow after core refuses this connector's
capability would use a stale answer to route around a permissions problem, and
it would keep working long enough for nobody to notice.

A response that is not an envelope is an **outage**. An API gateway, service mesh
or rate limiter answering in JSON is not core, and core never saw the request —
reporting that as a denial tells somebody they were refused by a system that
never heard of them.

*Mutation-checked:* a refusal falling through to the fail mode, an unreadable
effect defaulting open, an outage reported as a refusal. All caught. A fourth —
a non-envelope treated as a refusal — was **not**, and the reason is instructive:
the test feeding it an HTML error page never reached that branch, because HTML
fails JSON parsing entirely and takes the unparseable path. The branch needed
*valid JSON that is not an envelope*, which is also the likely case in practice.

### 5.3 — No core round trip on the event thread, and a timeout that agrees with an outage

A join event waiting on a network call holds a proxy thread. A proxy that stops
accepting connections because one backend service is slow is a worse outcome
than any single decision, so the call goes to a pool and the event thread waits
briefly for a result.

When the wait expires the **fail mode decides, by the same code path as an
outage** — because from the player's point of view it was one. Separate branches
for "timed out" and "unreachable" is how the two drift until one of them fails
open, and a test asserts the two verdicts are identical.

The abandoned call is cancelled with an interrupt. Otherwise every join behind a
slow core accumulates work whose answer nobody will read.

The budget is bounded at both ends in configuration: below 50ms no round trip
completes and every join falls to the fail mode; above 10s a slow core holds
proxy threads long enough to stop the proxy accepting connections at all.

*Mutation-checked:* the budget ignored, the timed-out call left running, a
timeout failing open regardless of configuration, an unconfigured gate ceasing
to allow, and the kick ceasing to name what is missing. All caught.

### 5.4 — An unconfigured gate allows everybody, deliberately

A deployment that wants `/link` without enforcement must be able to say so.
Turning enforcement on before a community has linked is how an operator locks
out their own players, and the migration path has to exist.

### 5.5 — The effector is optional, and says so honestly

The permissions plugin is a soft dependency, looked up reflectively. Its absence
is logged **once** and is non-fatal: a proxy without one should still run
`/link` and still enforce the join gate. Refusing to start over a missing
optional integration turns one operator's choice into an outage.

A present-but-unloadable plugin is logged with its cause rather than reported as
absent — "no permissions plugin found" would send an operator looking for one
that is sitting right there.

**An absent effector reports `false` from `grant`.** It initially returned
`true`, because the no-op did not throw. That would let a caller log "granted"
for a group that exists nowhere, and an operator reading that log would have no
way to discover the plugin was missing.

A grant that throws is reported, not propagated. A player who linked
successfully should not see an error because a permissions plugin was briefly
unhappy: the link happened, and the group is a consequence that can be retried.

### 5.6 — The SDK returns values, not a JSON tree

`Outcome.Ok` first carried a `JsonNode`. It did not compile in a connector,
because Jackson is an `implementation` dependency of the SDK precisely so it
does not leak — and the first connector to read a payload found that out.

The fix was not to make Jackson `api`. That would put it in every connector's
compile classpath and make swapping the parser a breaking change to all of them,
for a reason none of them cares about. `Payload` exposes `text`, `number`,
`flag`, `size`, `texts` and `items`, and Jackson stays inside.

Missing fields return empty or zero rather than throwing: a connector reading a
field a newer core stopped sending should degrade, not die on a rolling upgrade.

### 5.7 — The plugin class is deliberately thin

Every decision it could make is made in a class with no Velocity types —
`JoinGate`, `LinkCommandLogic`, `GroupEffector`, `BedrockIdentity` — because
those are testable in milliseconds and the plugin is not. What is left is
wiring: an event becomes a call, a verdict becomes a kick, a command becomes a
message.

**If a behaviour worth asserting appears in that file, it is in the wrong file.**

It refuses to run half-configured: no config, no enforcement, and an error
rather than defaults. A plugin that starts with defaults and enforces nothing
looks exactly like a plugin that is working.

The gate runs at login rather than at server connection, so a denied player
never reaches a backend and no backend has to know the gate exists.

### 5.8 — Nothing crossed the client/server seam until now

Every suite tested one side. The SDK's ran above an in-memory transport; core's
ran against a hand-built HTTP client. Both were right, and neither proved the
two **agree** — that signing produces what verification expects, that a refusal
core emits is one the SDK recognises, that a TTL survives the round trip.

`SdkAgainstCoreTest` runs the real SDK against an embedded core over the real
transport. It lives in core's tests because core owns the embeddable server;
putting it in a connector would make every connector depend on core to run its
own tests, which is the inversion the seam prevents.

It covers what neither side could alone: a drifted clock reported as a refusal
rather than an outage (the fix is a clock, not a cable), a wrong credential
never opening a gate, and a server that genuinely stops being read as an outage
rather than a decision.

*Mutation-checked:* the signature covering a different body than was sent
(5 failures), a constant nonce, and an unreachable core answering allow. All
caught.

### 5.9 — Two guards fired during this phase, both correctly

The **platform vocabulary guard** caught `minecraft.join` as a gate name in a
core test — borrowed from the connector that motivated the test, which is
exactly the leak it exists to stop. The **one-TOML-parser guard** stayed green
because the loader reaches connectors through the SDK as `api` while tomlj
remains `implementation` inside `config`.

Neither needed me to remember the rule.

### 5.10 — The stack run, and the six things it found

The Phase 5 gate needed a real client against a real proxy. Building it found
six defects, none of which any unit test could have.

**The plugin jar contained no dependencies.** `NoClassDefFoundError` on the SDK:
a proxy plugin gets one file and no classpath, and the plain jar carried only
this module's classes. Now a fat jar — soulbind's own modules plus Jackson,
tomlj and slf4j, all Apache-2.0 or MIT, with velocity-api excluded because the
proxy supplies it and two copies is a classloader argument.

**And the harness reported the gate WORKING while it was unloaded.**
`connectExpectingRefusal` accepted *any* kick as proof, and was being satisfied
by "Unable to connect to lobby". Two defects that hid each other: the plugin was
absent, and the test could not tell. It now requires the text the refusal must
contain, and refuses to run without it.

**Core logged to stdout, where its output is.** `register --quiet` prints a
credential for scripts to read; a connection-pool line on the same stream was
read as the credential and sent as an HTTP header, which the client rejected as
invalid. Logging moved to stderr. A CLI meant to be scripted cannot share a
stream between its output and its commentary.

**An illegal XML comment in logback.xml.** `--` inside `<!-- -->`, which logback
answers by dumping its parser diagnostics to stdout — reintroducing the exact
problem the file was written to fix.

**The jar task read the runtime classpath without declaring it.** It passed
repeatedly on a tree where the other jars happened to be built, and failed the
first time the tree was clean.

**The harness credential lacked `enforcement-point`**, and the run failed
there — correctly. Granting the harness everything would have hidden the
capability model rather than exercised it.

### 5.11 — The chicken and egg the gate creates, solved as designed

A join gate refuses everybody who has not linked, including the player who needs
to get in to run `/link`. The harness solves it the way the system intends: an
**override** admits one player before they have linked, which is the documented
reason overrides exist.

The alternative — turning the gate off for the harness — would have tested a
configuration that no deployment runs.

The final assertion asks core directly rather than reconnecting, because the
override is still in force and a successful join would prove nothing about the
rule. The graph is then read back, because a response can be right about work
that did not persist.

### 5.12 — What the stack pins, and what it costs

Velocity 3.5.1 and Paper 1.21.11 by **checksum**, not just URL: a URL is a
promise somebody else keeps. The fetch refuses on a mismatch rather than
warning, because a jar nobody reviewed makes every result below it meaningless
while looking exactly like a passing run.

Paper is **not** the newest. mineflayer's bundled data tops out at 1.21.11, and
26.2 was refused outright. That costs less than it looks: the plugin runs on the
proxy and never talks to Paper. Worth re-pinning upward when the client catches
up, because "the backend is old" is a difference from production even when it is
not a load-bearing one.

The client speaks the **backend's** protocol rather than the proxy's newest —
a proxy accepts a range, and autodetection refused before anything under test
ran.

The proxy's login rate limit is disabled **for the harness only**, with the
reason stated in the generated config: this harness is one address making
several connections in seconds, which no real deployment is. It weakens nothing
under test — it is a proxy DoS control, not part of linking or the gate.

### 5.13 — npm is broken on this workstation; yarn is not

`npm install` fails for any package with `MODULE_NOT_FOUND: imurmurhash` inside
npm's own dependency tree. Not a soulbind problem and not fixable from inside
this repository — repairing it means touching `/usr/local/lib/node_modules`.

`yarn` is installed and works, so the harness uses it and commits a
`yarn.lock`. Recorded because `npm --version` answers perfectly well, so the
breakage is invisible until something tries to install.

## Phase 6 — connector-discord

### 6.1 — The seam is `ChatSurface`, not the platform's wire protocol

Two implementations: the real client library, and a scripted one the battery
drives. The connector's logic — validating, calling core, deciding what to say,
granting a role — runs identically against both, so all of it is exercised
without the platform.

**Protocol-faithful fakery is out of scope**, and the reason is worth stating:
faking a gateway means maintaining a second implementation of somebody else's
product. It rots the moment they change it, and it tests nothing this connector
owns.

`ScriptedSurface` lives in the connector's **main** source set, not its tests,
because the full-stack battery drives it from another process. It is a test
double the way an in-memory database is — a real implementation with a different
backing store — and it imports the connector's real logic rather than
re-implementing any of it.

### 6.2 — Two gates, and they are not the same gate

The **capability** says what this connector's credential may ask core for. The
**platform permission** says which humans may ask this connector. A connector
holding `config-management` would otherwise let any member of a chat server
rewrite policy — the capability model being correct and the deployment being
wrong.

The platform check runs **before** core is asked. Asking first and refusing on
the answer would let an unprivileged member probe policy by reading refusals,
and would spend a round trip doing it. A test asserts core is never called.

An administrator is still subject to the capability gate. Both, not either: a
server administrator cannot grant this connector something core did not.

### 6.3 — Every reply is private, and the code most of all

A link code in a public channel is a code anybody can redeem, and the person who
asked would not know somebody else took it. A test walks every registered
command and asserts nothing it says is public.

### 6.4 — A contract that contradicted itself, found by a mutation

`grantRole`'s javadoc said it returns false for "already had it" **as well as**
failure, "because the desired state holds either way". That is self-contradictory
— on failure it does not hold.

Worse, it made the connector's own idempotence check unobservable: the scripted
surface deduplicated underneath it via `Set.add`, so removing the check changed
nothing any test could see. The mutation passed.

The contract now says what a real platform offers: **true if the role is held
afterwards**, regardless of whether this call changed it. Only genuine failure is
false.

And the test counts **calls**, not resulting state. Idempotence here is about not
asking the platform twice; the state is identical either way, which is precisely
why state cannot show it.

*Mutation-checked:* the code shown publicly, the platform permission removed,
role application made non-idempotent, and `/whoami` claiming everything is
verified. All caught — the third only after the contract was fixed.

### 6.5 — `/whoami` needed an admin capability, and the stack found it

Running both connectors against one core exposed a real gap in the capability
table: `subject.inspect` is the only way to answer "what is this account linked
to", and the specification puts it under `config-management`.

That means a chat surface answering `/whoami` — a person asking about **their
own** account — would need the capability that rewrites every rule. The
capability model being correct and the deployment being wrong, which is exactly
the failure this connector's two-gate design exists to describe.

`identity.describe` requires only `code-display` and returns the same thing.
The distinction is who may ask, not what comes back:

- `subject.inspect` — an operator, looking at anybody.
- `identity.describe` — a connector, asking for the person in front of it.

`code-display` is the right bar because a connector that may mint a link code
for an account **already vouches for it**, and already learns its graph the
moment a link completes. This grants no reach it did not have; it removes the
need to obtain far more.

An addition rather than a change: `subject.inspect` keeps its admin capability,
and no existing rule moved.

### 6.6 — The stack runs both connectors against one core

The chat side of the battery drives the **real** `ChatConnector` over the
scripted surface, not a hand-written HTTP request. That is the difference
between proving core works and proving the connector does — the run exercises
its command handling, its refusal wording and its privacy rule in the same pass.

It holds its **own** credential with its own capabilities. Sharing the harness's
would prove the flow works for something holding everything, which is not what a
deployment runs — and it was that separation that surfaced the `/whoami` gap.

The driver asserts its own privacy rule before exiting: every reply ephemeral,
or a non-zero exit. Left to the caller, that is a check somebody eventually
forgets, and a code shown publicly is a code anybody can redeem.

Its stdout is the message and nothing else, the same discipline as
`register --quiet` — a driver meant to be scripted cannot share a stream between
its output and its commentary.

---

## Phase 7 — connector-flarum

### 7.1 — Flarum pinned to 1.8.19, not 2.x

2.x exists only as `v2.0.0-rc.5`. Pinning a release candidate for an extension
people would install on a live forum trades their stability for our novelty, and
the 1.x extension API is what the connector needs.

*Alternative:* pin the RC and track it. Rejected — a forum is somebody's
community, and an RC dependency makes every upgrade their problem.

This resolves the departure the plan anticipated for this phase without needing
one: §14 Phase 7 names no version, so pinning the stable line is the plain
reading rather than an override.

### 7.2 — The vector checks are PHPUnit-free, with PHPUnit as a second entry point

PHPUnit 11 requires `ext-xmlwriter`, which this PHP does not have and which
cannot be installed without touching the system PHP — outside the directive that
everything created stays inside the repository.

The vectors are the one artifact whose entire purpose is to be run from *both*
languages. An oracle that runs only where a particular extension happens to be
installed is an oracle that stops being run, and its silence reads the same as
agreement.

So the assertions live in `tests/VectorChecks.php` and have two entry points:
`tests/run-checks.php`, which needs nothing but `mbstring`, `hash` and `json`;
and `tests/GoldenVectorTest.php` under PHPUnit, for where PHPUnit runs. One
implementation, because two copies drift and the copy run less often drifts
further while still looking like coverage.

That sharing introduced a smaller risk in place of the larger one — a check
wired into one runner and not the other, which fails silently. The runner
therefore reflects over `VectorChecks`, enumerates every public check, and
refuses to pass unless **both** entry points invoke every one. Mutation-checked
by unwiring a check from the PHPUnit side and watching the runner name it.

`ext-xmlwriter` is recorded in `STATUS.md` as an owner prerequisite, not worked
around: with it installed, `composer install` succeeds and the PHPUnit suite
runs the same checks.

### 7.3 — Case folding is ASCII-only, in both languages

**A real defect, in shipped code on both sides.** Normalisation uppercases
before it validates — that ordering is what makes a typed code
case-insensitive — and Unicode case mapping does not stay inside its input set.
So the repair step could turn a character that is *not* in the alphabet into one
that is:

| Input | Java (`Character.toUpperCase`) | PHP (`mb_strtoupper`) |
|---|---|---|
| `U+017F` long s | `S` — **accepted** | `S` — **accepted** |
| `U+00DF` sharp s | rejected | `SS` — **accepted** |
| `U+FB00` ﬀ | rejected | `FF` — **accepted** |
| `U+FB05` ﬅ | rejected | `ST` — **accepted** |
| `U+FB06` ﬆ | rejected | `ST` — **accepted** |

Typing a long s where somebody's code began with `S` redeemed **their** code,
with no error anybody could see. That is exactly the harm `LinkCode`'s
reject-never-repair rule is written to prevent, committed by the repair step
itself — and the rule's own comment names the consequence: "silently redeem a
different code and link the wrong account".

The two implementations also *disagreed*: per-character mapping cannot expand
one character into two, so the Java side let only `U+017F` through. A code the
forum accepted, the game refused.

Both now map ASCII `a`–`z` and nothing else. That is the only rule that cannot
invent an alphabet character from a non-alphabet one, it is trivially identical
in both languages, and it is locale-independent — which is what the comments
that stood in both files were reaching for. Locale was the smaller problem, and
fixing only it hid the larger one by making it rare.

*Alternative:* keep Unicode folding and subtract the known-bad characters.
Rejected — that is a denylist against a table Unicode revises, and the next
ligature added is a defect that ships.

**How it was found, and what that says.** Not by a test. The vector corpus
passed, the hand-written tests passed, and the hostile-charset run passed. It
surfaced when the charset handling was mutated — encoding arguments stripped,
`/u` dropped — and *every mutant survived*, because the corpus held no character
whose case mapping leaves ASCII. The blindness was the finding; the defect was
underneath it.

*See also 1.13 (UTF-8 assertions that could not fail on this JVM) and 1.15 (an
empty-key test that passed for the wrong reason): the third time a suite has
been green because nothing in it could distinguish the right answer from the
wrong one. Each was found the same way -- by breaking the thing on purpose and
noticing nothing complained.*

### 7.4 — The folding guard is an exhaustive sweep, not vectors

Eight corpus rows now pin the eight known characters. They are not the guard —
they only ever catch those eight, and the defect they were written for was found
by mutation rather than by any row.

`LinkCodeFoldingTest` and `VectorChecks::foldingCannotSynthesise` therefore
sweep **every code point** — all 1,112,064 — and assert that the set of single
characters normalising to non-null is exactly the 28 alphabet characters plus
the 20 ASCII lowercase letters that fold into them. Under a second, both take
well under a second, so there is no reason to sample.

The expected set is **written out by hand** in both languages, not derived from
`ALPHABET` and not computed with the folding rule. An expectation built the way
production builds it asserts only that the code agrees with itself, and the
defect here was a folding rule that was internally consistent and wrong. This is
the same discipline as the Tier 4 authorization matrix.

Both tests also assert the fix did not overshoot: `bcdfghjk` must still
normalise to `BCDFGHJK`. A change that rejected everything would satisfy every
other assertion in the sweep.

### 7.5 — Unicode whitespace vectors exist to make `/u` observable

Dropping the `u` flag from the forum side's whitespace regex, or swapping the
game side's `Character.isWhitespace` for a literal set, previously changed no
test result: every whitespace vector used a character the explicit strip-list
already named. Four rows now use `U+2003`, `U+3000` and `U+2009`, which only the
general whitespace test catches. Mutation-checked in both directions.

They are written as `\uXXXX` escapes, as the corpus header requires for anything
invisible — a literal ideographic space in a TSV is indistinguishable from a
typo, and the next person to edit the file deletes it by accident.

### 7.6 — The cross-language vectors run in `reaper test`, first

The Gradle suites prove the Java side agrees with the corpus. Agreeing with the
corpus is something both sides could do *while disagreeing with each other* —
and 7.3 is that sentence as a defect. The manifest's `[run]` verb therefore runs
the PHP vector script, ordinary and hostile, in a digest-pinned `php:8.4-cli`
image, before MariaDB starts: the step needs no database, so it fails in seconds
rather than after a server has come up.

The image is pinned by digest for the same reason as the others, and needs only
`mbstring`, `hash` and `json` — notably not `ext-xmlwriter`, because the runner
does not use PHPUnit (7.2).

### 7.7 — The signer's argument contract is asserted, because vectors cannot

Applying 7.3's method to the other cross-language surface: mutate the signer,
see what survives. Eight mutations of the digest path — separator changed to
CRLF, timestamp and nonce swapped, an absent body signed as the four characters
`null`, SHA-256 downgraded to SHA-1, key and message swapped, separator dropped
— were all caught by the corpus. Two were not:

- removing the check that a nonce must not be empty
- removing the check that a nonce must not contain the field separator

Not a defect. Both rules were present and correct in both implementations, and
the other side already tests them directly. But a vector file is rows of
`(key, timestamp, nonce, body) → digest`; it can only describe inputs that
*produce* a signature, so rules about inputs that must **not** produce one are
invisible to it. Deleting either rule left every vector passing.

That matters here more than it would elsewhere, because the two sides must agree
about *refusals* as well as digests. A signer that accepts what the other rejects
produces signatures the other will not verify — and the separator rule is the one
that keeps the canonical form unambiguous, so two different requests cannot sign
to identical bytes.

`VectorChecks::signerArgumentValidation` states the rules directly. All ten
mutations are now caught.

It also pins the *negative*: a carriage return is deliberately **accepted**. The
separator is LF alone, so a CR creates no ambiguity, and both sides sign it. That
was previously true on both sides by accident of how the check was written —
neither tested it — so a plausible "harden the nonce" change on either side would
have silently broken interoperability. `RequestSignerTest` now states it too, and
both statements are mutation-checked by making CR rejected and watching each fail.

The nonce is generated client-side as a UUID, so no caller-supplied CR reaches
the signer in practice. On the receiving side a caller *does* control it, and
`SignedRequestVerifier` already catches `IllegalArgumentException` and answers
`malformed` — a hostile nonce is a refusal, not a 500. Checked rather than
assumed while writing this.

### 7.8 — The decision cache mirrors the other side's rules, not its shape

A forum and a game server that disagree about what an outage means is one person
let in on one and turned away on the other, at the same moment, for the same
reason. So `Client/DecisionCache.php` restates the *rules*, and the checks assert
each of them:

- The shipped default is **closed**, and it is the only default.
- A fail mode that is not exactly `open` is **closed** — `opne`, `true`, `yes`,
  `allow` and an empty string all deny. A typo must never be the thing that opens
  a gate. It is not an exception either: refusing to boot a forum over a spelling
  mistake is a worse failure than denying during an outage.
- A TTL of zero means do not cache **and forget what was cached**. The eviction
  half matters: without it, a zero-TTL answer leaves the previous decision in
  place, so the cache keeps serving an allow core has just withdrawn.
- Expiry is **exclusive** of the instant it names, matching the other side. An
  off-by-one here is a decision served one second past its licence.
- The cache key joins on `U+001F`, which cannot appear in a gate name or a
  platform identifier. A colon would let `("a:b", "c")` and `("a", "b:c")`
  collide, and a collision here serves one subject's decision to another.
- A fail-mode answer carries **TTL 0** and is never stored. It is the absence of
  a decision, not a decision; caching it would extend an outage past its end.
- An unparseable `decide` response is a **denial**. A response nobody can parse
  must never be the thing that lets somebody through — and the check pairs that
  with a well-formed allow, because a parser that denied everything would satisfy
  the first half.
- Webhook invalidation is scoped to one identity. Too narrow and the webhook does
  not take effect; too broad and one webhook becomes a stampede of synchronous
  decides, which is what the cache exists to prevent.

All ten mutations of these rules are caught, including the two that only a
negative assertion catches: making the default open, and letting a typo open it.

### 7.9 — The PHPUnit-free runner generalised beyond the vectors

7.2 built a dependency-free runner because the vectors must be checkable
anywhere. Once it existed, there was no argument for the rest of the suite being
less runnable than its most important part — especially while `composer install`
cannot complete here at all.

`tests/run-checks.php` (was `run-vectors.php`) now runs every `*Checks` class
listed in one table, and the wiring assertion generalised with it: for each
class it enumerates the public checks by reflection and refuses to pass unless
the runner *and* the class's PHPUnit counterpart invoke every one. A listed class
with no PHPUnit counterpart, or one declaring no checks at all, is itself a
failure — otherwise adding a class to the table would report coverage that does
not exist.

*Alternative:* let PHPUnit be the only home for non-vector tests and accept they
do not run here. Rejected — that is a suite nobody on this machine can run,
which is indistinguishable from a suite that passes.

### 7.10 — The client keeps the refusal/outage distinction, and a seam to prove it

`Transport` is an interface so that signing, envelope parsing, the
refusal-versus-outage distinction, cache population and the fail-mode fallback
are all testable without a socket. A test that needs a network is a test that
does not get run, and these are the rules least affordable to leave unrun.

The distinction, stated identically to the other side: a **refusal** is core
answering "no" — final, never softened by a cached answer or a fail mode. An
**outage** is core not answering — cache, then fail mode. Collapsing them turns
"you may not" into "try again later", and turns a misconfigured credential into
an intermittent fault nobody can reproduce.

What the checks pin, each mutation-verified:

- A refusal does not consult the cache **even when a live cached allow exists**,
  does not reach the fail mode **even when the fail mode is OPEN**, keeps core's
  own reason rather than flattening to a generic denial, and is not cached
  against the subject.
- Anything that is not a protocol envelope — a proxy error page, an empty body,
  a JSON array, truncated JSON, a bare string, JSON without `ok` — is an
  **outage**. Core never said no, because core never saw it. Reading a captive
  portal as a policy decision is how an outage becomes a permanent denial.
- Each of those is paired with its obverse: a well-formed envelope must still
  parse, and a well-formed refusal must still be a refusal. Without the pair, a
  client that reported everything as an outage would satisfy the whole list.
- The signature must **verify over the bytes actually sent**, not merely be
  present. A signature over the wrong body is a header that looks right in a log
  and is refused by core.
- Nonces must not repeat across 50 calls, using the real generator rather than
  the injectable test one — a repeated nonce is refused as a replay, and a
  predictable one is a replay window. `random_bytes`, never `uniqid`.

One mutation initially survived: changing the TTL on the refusal decision from 0
to 600. It survived because nothing stores a refusal, so the value was
unobservable — an equivalent mutant by today's code, and a trap for tomorrow's.
It is now asserted, with the reason written where somebody adding caching to a
caller will read it, rather than left true by accident.

### 7.11 — A bug in the check runner, found by the runner

`ReflectionClass::getMethods(IS_PUBLIC | IS_STATIC)` does not mean "public and
static". The filter is a bitmask that **ORs**: it returns everything public *or*
static, which includes the private static helpers the check classes use to build
fixtures. The runner duly tried to call one and died.

Worth recording because of the direction it failed in. It failed loudly, on the
first check class that had a private static helper returning an array — but the
same mistake in a *guard* would have failed silently, quietly widening what the
guard enumerated. The fix tests `isPublic()` and `isStatic()` separately, and the
comment says why rather than leaving the next reader to rediscover the bitmask
semantics.

### 7.12 — The webhook endpoint, and the two rules that read backwards

This endpoint is the only part of the connector an unauthenticated caller can
reach, so it mirrors the other side's request verifier in the same order:
presence, clock, replay, signature.

Replay protection is **two halves and needs both**. The timestamp window bounds
how long a captured delivery stays interesting; the nonce store stops it being
used twice inside that window. Either alone is not replay protection.

Two decisions look wrong at a glance and are not:

**The nonce is recorded before the signature is checked.** Recording only
*verified* nonces would let somebody replay a captured delivery as often as they
liked — the signature is valid on a replay, that is what makes it a replay, and
nothing would remember the first one. Recording unverified nonces is safe here
only because the store is bounded and full means *refuse*, never evict: evicting
the oldest entry to make room is how a replay gets in.

**A replayed delivery answers 200, not 4xx.** The delivery it duplicates was
already accepted, so there is nothing for an at-least-once sender to usefully
retry. Answering 4xx would make a correct sender look permanently broken to its
own operator. Nothing this endpoint refuses is a 5xx at all — a refused webhook
is the endpoint working, and 5xx would make core retry a delivery that will
never be accepted, forever.

Two defects were caught while writing it, both in code that had just been
written and neither by a behavioural test:

- `RequestSigner::sign` **throws** on a nonce carrying the field separator. The
  nonce comes from an attacker-controlled header, so uncaught that is a 500
  anybody can trigger by sending a newline. Now caught and answered `malformed`.
- An **empty secret** also throws. An extension that 500s until somebody fills
  in a setting looks broken rather than unconfigured, so an unconfigured endpoint
  now answers `not-configured` and accepts nothing.

The timestamp header is parsed with a strict integer pattern, not `is_numeric`
and not a cast. `is_numeric` accepts `1.7e9` and `0x654`; a cast reads
`1700000000abc` as a valid timestamp. Each would then land *inside* the window.
Both are in the corpus, and both mutations are caught.

Twelve mutations, all caught — including the window closing at only one end, the
nonce recorded after the signature, and the store growing without bound.

### 7.13 — A guard for the one thing no test can see

Swapping `hash_equals` for `===` changes **no observable behaviour**. Every
assertion in the webhook suite passes either way. What changes is that `===`
short-circuits on the first differing byte, so how long it takes leaks how much
of a guess was right — and a leak nobody can observe from outside is exactly the
kind that survives a test suite indefinitely.

So it is asserted against the *source*, the same reasoning as the other side's
static guards: when a property cannot be checked by running the code, check the
code. `WebhookChecks::secretsAreComparedInConstantTime` scans `src/` for equality
operators applied to secret-shaped variable names.

**Narrowings, stated.** It reads `src/` only, and flags only operands *named*
like secrets — `signature`, `expected`, `secret`, `credential`, `hmac`, `digest`,
`token`. A secret in a variable called something else is not caught. Comparisons
against a literal `null` or `''` are exempt, because those test presence, not
content, and refusing an absent signature is behaviour this code must keep. The
exemption is those two literals and nothing else, so comparing a secret against
any actual value still fires.

It is a backstop for the obvious mistake, not a proof of absence, and the gap is
written down rather than implied. The guard also fails if it scans zero files —
a guard that silently matches nothing reads as coverage.

Mutation-checked in both directions: it fires on a real `!==` signature
comparison, and the presence checks it must tolerate do not trip it. The first
version *did* trip on them, which is how the exemption came to be written
narrowly instead of as a blanket skip.

### 7.14 — An unconfigured connector is inert, not closed

Fail-closed is about an **outage**: core is configured and unreachable, somebody
chose to gate this, and the gate should hold while the answer is unavailable.

Core having never been configured is not an outage. It is the absence of a gate.
A freshly installed extension that locked everybody out of a working forum,
before its owner had entered a URL, would be uninstalled within the minute and
would deserve it. The half-configured case — a gate name set, no credential yet —
resolves the same way: an admin panel that bricks the forum between two form
fields is not a safety feature.

So the two defaults pull in opposite directions on purpose. Gates are **off**
until switched on; the fail mode is **closed** once one is. What must never
happen is a connector that is half-configured and silently *open*, so
`isConfigured()` is one explicit test rather than a scattering of empty checks at
each call site — two call sites disagreeing about what an unset value means is,
here, a gate.

`AccessGate` knows nothing about the host platform: no request, no user model, no
exception type. The listeners that call it are a few lines each and do the
translating. The part with the rules in it is therefore testable without standing
up a forum, and the rules are what must not be wrong.

The platform kind is fixed at construction rather than passed per call — it
identifies which platform this connector speaks for, and a caller able to change
it could ask about somebody else's account on another platform.

A denial carries **core's own wording** when core gave any, because core knows
what is missing and this connector does not. Only when core gives none does this
side supply text, and never a bare "denied": a refusal nobody can act on is a
support ticket.

### 7.15 — An unreadable timeout falls back to the default, not to the floor

Found by mutation, and the survivor is more interesting than the fix.

Removing the strict integer parse left every assertion passing, because the clamp
that follows it caught the damage: `'2.5'` cast to `2` and clamped to `100`, which
is inside the permitted range. The check only asserted the result was in range, so
it saw nothing wrong.

But 100ms is short enough that every `decide` call times out. A typo in an admin
field would have become a permanent outage, and — by the rule directly above — a
permanently closed gate. The setting most likely to be fat-fingered was the one
that could silently shut the forum.

Unreadable values now reach the **default**. Readable but out-of-range values are
still clamped, and the distinction is the point: an operator who typed `99999`
expressed an intent that the nearest permitted value honours, while one who typed
`soon` expressed nothing to honour.

An explicit `0` falls back too. It means "no timeout" to most HTTP clients, which
is the hang the bound exists to prevent; clamping it to 100ms is not what the
operator meant either. Neither reading is safe, so neither is guessed.

The assertion now pins the exact value rather than a range — thirteen mutations,
all caught, where twelve were before.

### 7.16 — The decision cache needed a store, because PHP has no process

The first version held decisions in an object field. It passed every check and
would have cached **nothing**: PHP starts a fresh interpreter per request, so the
field is empty by the time the next page loads — and the webhook that exists to
keep the cache warm would have been warming something no later request could
read.

Caught by asking what the webhook was actually for. The checks could not have
caught it: every one of them lived inside a single request, which is the one
situation where an in-memory cache works.

`DecisionStore` is now the seam, and it is deliberately the narrowest interface a
key-value cache can satisfy — get, put with a TTL, forget. No enumeration, no
prefix scan, no tags. Those are exactly the operations shared caches disagree
about, and depending on one would make the connector work on one host's cache
driver and not another's. The rules stay in `DecisionCache`; only the bytes move.

A check now stores through one cache object and reads through a second over the
same store, which is the smallest thing that distinguishes a real cache from a
field.

### 7.17 — Invalidation by generation, and the resurrection it can cause

A webhook says "this subject changed" and every gate cached for them must go. A
shared cache cannot be enumerated to find them, so each entry key embeds a
per-identity **generation**, and invalidation bumps it. One write orphans every
gate at once, without knowing which gates exist.

That introduces a failure nobody would look for. If a generation marker expired
while an entry it had orphaned was still live, the generation would read back as
`0` and **the orphan would become reachable again** — an invalidated decision
returning from the dead, hours later, silently.

Found by mutation: shortening the generation TTL to one second changed no test
result, because every check ran at a fixed instant. The fix is structural rather
than a longer constant — stored decisions are capped at the generation lifetime,
so the relationship holds by construction even though core chooses the TTL and
could choose a year. The check now walks a clock forward past several generation
lifetimes and asserts the decision stays dead.

A second mutation survived alongside it: removing the store's own expiry check,
because the cache checks expiry too. That redundancy is intentional — the store's
clock is not the cache's clock — but a redundancy nothing asserts is one that
quietly stops being one, so the store's expiry is now checked directly.

### 7.18 — Two caches, two opposite failure directions

Both live in the host's shared cache and they fail in opposite directions, which
is worth stating because a reader who noticed only one would think the other was
a mistake.

**The decision store fails to "ask core".** A cache that will not answer is
treated as a miss: the caller re-asks core, which is slower and entirely correct.
Throwing would turn a cache problem into a page failure.

**The nonce store fails CLOSED.** Every path that cannot prove a nonce is new
returns false, and the verifier reads that as a replay and refuses. A decision
cache that cannot answer degrades to asking core; a replay guard that cannot
answer degrades to *having no replay guard*.

One honest gap, written down rather than glossed: PSR-16 offers no atomic
add, so `has()`-then-`set()` in the nonce store is not atomic. Two identical
deliveries arriving in the same instant could both pass. The window is
milliseconds, the attacker must already hold a validly signed delivery, and the
residual effect is a duplicate cache invalidation — idempotent, costing one extra
`decide`. **If this endpoint ever does something that is not idempotent, that
line stops being adequate and needs a store with an atomic add.** The comment
says so at the call site.

PSR-16 also forbids characters that the cache's own key separator uses, and
permits drivers to throw on others, so keys are hashed. The cache's key rules are
not soulbind's to negotiate, and a key that works on one driver and throws on
another is a fault that only ever appears in somebody else's deployment.

### 7.19 — What the admin page is allowed to know

Flarum serializes declared settings into every admin's browser, where they sit in
the page source. The credential and the webhook secret are therefore **not**
serialized: the admin page writes them and never reads them back, which is the
same discipline as showing a minted credential once.

The core URL is not published either. What the page needs is whether the
connector is configured, so a boolean is derived from the URL and that is what
crosses — a member's browser has no reason to learn where core lives.

### 7.20 — The extension id is not the package name, and only a forum could say so

Flarum computes an extension id by splitting the composer name on the slash and
stripping a leading `flarum-ext-` or `flarum-` from the package half:

```
soulbind/flarum-connector  ->  soulbind + connector  ->  soulbind-connector
```

Not `soulbind-flarum-connector`, which is what slash-replacement gives and what
both the harness and the admin page used.

**What that cost.** The harness enabled an id no extension answers to, so the
extension stayed disabled and its routes never registered. The webhook 404'd —
while composer's `installed.json` recorded `type: flarum-extension` correctly,
`extend.php` loaded and returned all six extenders, and the staged tree was
exactly right. Four browser-tier iterations went into eliminating each of those
in turn.

The same wrong id was in `js/src/admin/index.js`, where the failure is worse
because it is silent: settings registered against a nonexistent extension
produce no error, no missing file, and an empty settings panel.

**Nothing without a running forum could have found this.** Every static check
passed. The package was well-formed by every measure available on this
workstation. It took booting Flarum and asking its own `ExtensionManager` which
extensions it had discovered — which returned seventeen, one of them
`soulbind-connector`, disabled.

That is the argument for the browser tier existing, stated as a defect rather
than as a principle.

**What now prevents it.** `PackagingChecks` implements Flarum's derivation and
asserts the admin page's `.for(...)` argument matches what Flarum will compute
from `composer.json`. It is static, so it runs anywhere, while the failure it
prevents needs a forum, a database and a browser. The rule itself is pinned
against six cases including `flarum/flarum-ext-markdown` and the awkward
`acme/flarum-flarum`.

The harness does **not** use that implementation. It asks the running Flarum for
the id, because hardcoding the correct value would fix today and re-arm the
identical trap for whoever renames the package.

Two defects in the checks themselves surfaced while writing them, both worth
recording because both are ways a guard lies:

- The derivation case for `acme/flarum-flarum` expected `acme-`. `str_replace`
  removes a substring, so the real answer is `acme-flarum`. The implementation
  was right and my expectation was wrong — the case is kept, with a note, since
  it is exactly where the intuitive reading and the actual behaviour diverge.
- The admin-page check grepped the whole file for the wrong id, and matched the
  comment explaining which id is wrong. The check failed on a correct file,
  using its own explanation as the evidence against it. It now reads the
  `.for(...)` argument. A check that cannot tell code from prose about the code
  gets silenced rather than obeyed.

### 7.21 — An unreadable payload no longer blames a field that is present

Thirteen handlers shared one branch for two different faults:

```java
if (request.isEmpty() || blank(request.get().gate())) {
    return WireResponse.error(INVALID_REQUEST, "rule.set names a gate");
}
```

`request.isEmpty()` means the codec could not read the payload at all. The
second clause means it read fine and a required field was blank. They are not
the same problem and they do not have the same fix, but every one of these
answered with the field message.

So a caller who sent a field this build does not recognise was told the gate was
missing — while the gate sat in the request, spelled correctly. That sends
somebody to check the one part of their payload that is definitely right.

**Found by being that caller.** The forum harness added a `detail` field to
`rule.set`, wanting a custom denial message. `RuleView` has no such field, the
bind failed, and the reply said `rule.set names a gate`. The detour that cost is
the whole argument for fixing it.

The helper takes the `Operation` enum rather than a string, so the operation
named in the message cannot drift from the operation handling the request — four
of the sites had already been converted with hand-written literals before that
occurred to me, and those are gone.

It names the *shape* it could not read, not the offending field, because the
codec reports failure without saying which key was at fault. Claiming to know
would be a second wrong answer dressed as a better one.

Both halves are asserted, and the second half matters: without it, "splitting the
branch" could be satisfied by reporting *everything* as unreadable, which would
lose the specific message that was right all along. Mutation-checked by putting
the two faults back on one branch and watching the unknown-field case fail.

*A related observation, not acted on:* a rule carries no denial wording, so the
message a person sees comes from the policy engine — for an unlinked account
that is "this account is not linked to any other". On a **registration** form
that reads oddly, since somebody registering for the first time has nothing to
link yet. Whether registration should be gateable on being linked is a design
question for the owner, not one to settle by editing a string.

### 7.22 — A refusal that never reaches the person

The browser tier drove a real registration against a real forum with the gate
configured to deny, and the person was shown:

> Oops! Something went wrong. Please reload the page and try again.

The gate refused correctly. It refused for the right reason. It attached core's
own wording. And none of that arrived.

`GateRefused implements KnownError` stops Flarum logging a refusal as a server
fault, which is why that was written — but it does **not** make Flarum render the
reason. Flarum maps a known error to an HTTP status through the `ErrorHandling`
registry, and an unregistered type falls through to a generic 500, which the
frontend renders as the message above.

**Every unit check passed**, and they were not wrong to. Each asserts the message
on the `GateOutcome`, and the message on the `GateOutcome` was correct. The
missing piece was the last hop, from the exception to the page, and nothing that
stops short of a browser can see it. This is the second defect in this phase that
only a running forum could find, after the extension id.

403, not 400: the request was well-formed, and the answer is that this account
may not do this. A 400 tells an API client to fix its payload, which is neither
the problem nor a thing the client can act on.

`GateRefused::TYPE` is a constant because it is written in two files that must
agree, and `PackagingChecks::theRefusalTypeIsRegistered` asserts `extend.php`
registers *that constant* rather than a matching literal. Both mutations are
caught: removing the extender, and replacing the constant with the same string
spelled out. The second matters more — a literal that happens to match today is
a silent drift tomorrow, and the symptom is a refusal that looks like a bug in
the forum.

The check reads `GateRefused.php` as **source** rather than loading the class.
The first version referenced the constant directly and died with a
class-not-found: `GateRefused` implements a Flarum interface, and the
dependency-free runner exists precisely to work on a machine with PHP and
nothing else. Loading Flarum to check one string would have made the entire
suite unrunnable there.

### 7.23 — A binding the host does not provide, and the four iterations it cost

`SoulbindProvider` asked the container for `Psr\SimpleCache\CacheInterface`.
Flarum does not bind it. Every gate check therefore threw
`BindingResolutionException`, Flarum reported that as a generic 500, and the page
rendered "Oops! Something went wrong. Please reload the page and try again."

PSR-16 was chosen *because* it is the standard interface. A standard the host
does not implement is worth nothing, and the seam that actually matters here is
`DecisionStore` — this connector's own, which keeps the rules testable without
any cache at all. The stores now take `Illuminate\Contracts\Cache\Repository`,
which Flarum provides.

**That swap closed a gap I had documented as unclosable.** 7.18 recorded that
PSR-16 offers no atomic add, so the nonce store did `has()` then `set()` and
could, in a millisecond-wide window, let two identical deliveries both pass. The
host contract has `add()` — write-if-absent, reporting whether it wrote — which
is exactly the operation that guard always wanted. The race is gone, and the
caveat in 7.18 no longer applies.

**What it cost, and why.** Four iterations, three of them spent narrowing what I
could not see rather than what was wrong:

1. I read `code: "unknown"` as "`GateRefused` is not registered" and changed the
   registration. Flarum's `Registry` calls `getType()` on any `KnownError`, so a
   `GateRefused` would have carried its own code regardless — "unknown" meant the
   exception was something else entirely, and I had assumed its identity.
2. The log dump tailed `flarum.log`; the file is `flarum-YYYY-MM-DD.log`. It
   printed nothing, and nothing is indistinguishable from healthy.
3. Only when the harness resolved the gate from the container *in process* did
   the exception name itself.

The `ErrorHandling` registration from 7.22 is still correct and still needed —
without it a refusal maps to 500 rather than 403 — it simply was not what stood
between the person and their reason.

**The guard.** The stack now resolves every service this extension registers,
from Flarum's own container, and calls the gate, before any browser starts. A
container that cannot build the thing under test is not a subtle failure; it only
looked subtle because nothing had asked the container to build it.

### 7.24 — Correcting 7.22: a status is not enough, and KnownError prevents the fix

7.22 registered a status for `GateRefused` and said that was the missing hop. It
was necessary and it was not sufficient, and the reason is in Flarum's
`Registry::handle()`:

```php
return $this->handleKnownTypes($error)     // KnownError first
    ?? $this->handleCustomTypes($error)    // custom handlers second
    ?? HandledError::unknown($error);
```

`handleKnownTypes` builds a `HandledError` with **no details**. So an exception
implementing `KnownError` can never explain itself, and — because that branch
wins — registering a custom handler alongside it does nothing at all. The two
are mutually exclusive, and the one I had chosen was the one that cannot carry a
reason.

The API said so plainly once the probe could reach it:

```
HTTP 403 {"errors":[{"status":"403","code":"soulbind_gate_refused"}]}
```

Correctly refused, correctly typed, correctly statused, and silent.

So `GateRefused` no longer implements `KnownError`, and a `GateRefusedHandler`
returns `(new HandledError($e, TYPE, 403))->withDetails([['detail' => …]])`.
Nothing is lost by dropping the interface: `shouldBeReported()` is true only for
the type `unknown`, so a handler that names its type keeps a refusal out of the
error log exactly as `KnownError` did.

The check now asserts both halves — that a handler is registered, and that
`GateRefused` does **not** implement `KnownError` — because either alone is a
configuration that silently swallows the reason. Both mutations caught.

### 7.25 — One config value, two meanings

The Java SDK builds its endpoint as `trimTrailingSlash(coreUrl) + "/v1/rpc"`.
This side treated the same setting as a complete endpoint and posted to the base
URL, where core does not answer.

So every `decide` was an outage, every gate failed closed, and the forum refused
everybody with reason `unreachable` — while core sat answering the Velocity
connector perfectly, from the same configuration value.

An operator configures both connectors with that value and reasonably expects it
to mean one thing. It meant two, and the failure it produced looked exactly like
core being down.

The path is now a constant, appended the same way, and checked against five
bases including a trailing slash and a prefix path — `https://example.com/soulbind`
must become `https://example.com/soulbind/v1/rpc`, because core behind a prefix
is a real deployment and dropping the prefix would be the same bug wearing a
different hat.

Nothing in the PHP suite could have found this: none of it opens a socket, which
is deliberate and remains right. The harness found it the moment it asked the
gate a question, which is the argument for the gate-resolution smoke in 7.23.

### 7.26 — Two error types, because the frontend reads the type and not the reason

The API carried core's reason correctly:

```
403 {"code":"soulbind_gate_refused","detail":"this account is not linked to any other"}
```

and the page said **"You do not have permission to do that."**

Flarum's frontend picks what a person reads from the error TYPE and ignores the
detail in the body. With one type, the two cases this connector exists to keep
apart collapse into one sentence:

- somebody who has not linked an account, and
- somebody refused because a server they have never heard of is unreachable.

The first message is true. The second is a lie, and it is the exact lie the
fail-closed message was written to avoid — *"this is a problem on our side, not
yours"* — discarded at the last hop, after being carried faithfully through
every layer beneath.

So there are two types. `soulbind_gate_refused` is a 403 and reads "This action
needs a linked account." `soulbind_unavailable` is a **503** and keeps the
system-blaming wording. 503 is right on its own terms: the request was fine and
the service could not answer.

The detail still carries core's precise reason for anything reading the API,
which is where a machine-readable answer belongs.

Checked two ways. Statically: both types must exist and both must be translated
**under `core.lib.error`**, which is the only place Flarum looks — an
extension-scoped key would read correctly and do nothing. Behaviourally: the
`@refused` browser pass asserts the linked-account wording and rejects the
outage wording, and the `@outage` pass does the reverse. Either assertion alone
would pass with one type.

*One correction worth recording.* I first placed the translation assertions with
a text anchor that matched the end of the wrong method, so a missing translation
was reported under "the endpoint matches the protocol path". The check failed
correctly and blamed the wrong thing, which is the same defect I had just fixed
in core's handlers — and I introduced it while fixing that. It now sits in the
refusal check, and the mutation confirms the attribution as well as the failure.

### 7.27 — The extension ships forum JavaScript, for one branch in Flarum

Flarum's `requestErrorCatch` renders a response `detail` for exactly one status:

```js
case 422:
  content = formattedErrors.map(...)      // the detail
case 401:
case 403:
  content = app.translator.trans('core.lib.error.permission_denied_message');
```

Every other status gets a fixed sentence. So a refusal that travelled correctly
through core, the connector, the gate, the exception handler and the API arrived
at the person as *"You do not have permission to do that."*

**The cheap fix was 422**, and it would have worked immediately. It also tells
every API client that their payload was unprocessable — untrue, and unactionable,
which is the same objection that ruled out 400 for a refusal. Choosing a wrong
status to win a rendering argument is exactly the kind of trade that looks free
and is paid for by whoever integrates next.

So the statuses stay honest — 403 refused, 503 unavailable — and the extension
ships a forum bundle that puts the reason back on the page. It extends
`requestErrorCatch`, replaces the alert content only when the error carries one
of this connector's two codes, and otherwise leaves Flarum's message alone: a
partial override that blanked the alert would be worse than none.

What it shows is **core's** detail, not the connector's fallback translation.
Core knows which platform kinds are missing; this connector does not. The
translations added in 7.26 remain as the fallback for when the bundle has not
loaded.

**A missing asset that had been missing all along.** `extend.php` registered
`js/dist/admin.js` from the day the admin page was written, and that file has
never existed. Flarum tolerated it in silence — which is precisely how an asset
stays missing: nothing complains, and the page it belongs to simply does
nothing. The harness now builds both bundles before staging the extension, and
asserts each is non-empty, because webpack can exit 0 having produced nothing
when its entry resolves to nothing.

### 7.28 — The extension id trap, a third time, in the locale file

The member panel rendered, reached core, and correctly reported "not linked" —
and showed the member this:

```
soulbind-connector.forum.link.titlesoulbind-connector.forum.link.not_linked…
```

The locale file declared its namespace as `soulbind-flarum`. Flarum derives an
extension's namespace from its id, which is `soulbind-connector`, so every key
the frontend asked for resolved to nothing and Flarum rendered the key itself.

**Third occurrence of one root cause.** The `.for()` call in the admin page, the
id the harness enabled, and now the locale namespace. Each cost its own
discovery; the first two are guarded and the third was not, so it waited until a
browser put the key names on screen.

The admin page had the same mismatch and would have shown raw keys too. Nobody
had opened it, which is exactly how a settings page stays broken.

**The guard** — the T3 message-key guard the plan asks for, extended to the
extension. It asserts the namespace *is* the derived id, and that every key the
frontend asks for exists in `locale/en.yml`. A missing key does not fail
anything: Flarum renders it, so a member reads `…link.not_linked` where a
sentence should be.

Narrowings, stated:

- Comments are stripped before scanning, because `js/src/forum/index.js` quotes
  Flarum's own error switch — translator call and all — to explain why the bundle
  exists. The first version read that quotation as a demand. **That is the same
  mistake the admin-id check made against its own explanatory comment**, which I
  had already fixed, reintroduced in a new guard three hundred lines away.
- Only keys in this extension's namespace are checked. A `core.*` key belongs to
  Flarum and legitimately is not in this file; demanding it would force somebody
  to copy Flarum's translations in to silence a guard.
- Template keys are checked by prefix, since the leaf is computed. A wrong prefix
  is exactly the failure this was written for.
- It fails if it finds no keys at all, so it cannot pass by matching nothing.

Both mutations caught: a renamed key, and the wrong namespace.

## Phase 8 — connector-plan and the full-stack battery

### 8.1 — Unknown is a third state, and a boolean cannot carry it

A dashboard has exactly one way to be actively harmful: printing a confident
answer it does not have. If core is unreachable and the page says **"not
linked"**, an operator goes and chases somebody whose links are fine, and
nothing on the page hints that anything was wrong.

Plan's `@BooleanProvider` has no third value. It fails closed to `false`, which
is correct and insufficient — read on its own it is an answer that was never
given. So `unknown` is carried beside it in three places that can hold it: a
`@StringProvider` that says the word, its own `@NumberProvider` count on the
server page, and `ServerLinkSummary.linkedFraction()`, whose denominator
excludes unknown so an outage does not read as a decline.

The count is the one that matters most in practice. Without it, linked and
unlinked simply do not add up to the roster, and an operator has nothing to
attribute the difference to — the page is wrong and gives no handle on why.

`LinkDataSource` caches answers and never outages, so recovery is visible on the
next call rather than at the end of a TTL. Caching a failure is how a
thirty-second blip becomes a thirty-second-and-a-TTL blip that looks unrelated
to its cause.

### 8.2 — `compileOnly` is right for the host and wrong for coverage

Plan is the host: it is on the classpath at runtime by definition, and bundling
a second copy is how a plugin loads annotations that are not the ones the host
scans for. §16 pins it to `provided` scope for the LGPL packaging reason as
well. `compileOnly` is correct.

It also makes every provider body unexecutable by a test. The annotations
compile; the bodies never run. And nothing about that failure is loud —
annotation-driven code produces **a page with a missing panel and no error
anywhere**, or worse, a panel with a plausible wrong number. Plan reports
nothing, because there is nothing to report: the method ran and returned a value
of the right type.

So the same artifact is on the test classpath, with `commons-lang3` at test
runtime because Plan's `Table.Factory` calls it without declaring it in its POM.
Neither reaches a distributed artifact, so the packaging guarantee is untouched.
Recorded as departure 7.

The mutation that justifies the whole arrangement: dropping the
seconds-to-milliseconds conversion in `linkedSince` renders **1970 on every
page**. That reads as a data problem and sends whoever investigates in the wrong
direction entirely. Seven of seven mutations caught, including that one.

### 8.3 — A guard that documented a rule it did not implement

`DependencyGraphGuardTest`'s class comment opened with *"Two rules live here"*
and listed both: no YAML parser, and no copyleft artifact bundled into a
distributed artifact. It implemented three tests, all of them about YAML and
TOML. **There was no licence assertion at all.**

This surfaced because adding the Plan API — LGPL-3.0 — put an artifact into a
new configuration, the build went green, and the green meant nothing about the
question it appeared to answer. A guard whose documentation overclaims is worse
than a missing guard: the missing one prompts somebody to write it, and this one
told every reader it was already handled.

The rule is now implemented for the two artifacts §16 names specifically: Plan
(`provided` only) and MariaDB Connector/J (never shaded). A declaration of
either in `implementation`, `api`, `runtimeOnly` or `compileOnlyApi` fails.

**Narrowings, stated.** It covers those two coordinates and no others — in
particular **not logback**, which is EPL-1.0 / LGPL-2.1 dual and which §16
explicitly permits as an unmodified, unbundled binary dependency; listing it
would fail the build for a decision the specification already made. That
exclusion covers logback and nothing else. It reads declared dependencies as
text, not a resolved graph, so a copyleft artifact arriving transitively behind
a permissive one is not caught; that claim needs the licence-report task in
Phase 10, and the gap is written here rather than implied.

Two fixtures, not one. The must-fail fixture proves it fires; a second
**must-pass** fixture proves `compileOnly` and the `test*` configurations are
still allowed, because a guard that rejects the correct usage too would read as
coverage while making the module unbuildable. Comments are stripped before
matching — the fixtures name these artifacts in their own explanatory prose, and
a guard that matches its own explanation reports a violation that is only a
sentence about the violation. That mistake has now been made three times in this
repository and caught by writing the stripping in from the start.

**Corrected after a full battery, because the first version could not fail.**
Every pinned artifact in this repository is declared through the version
catalog — `runtimeOnly(libs.mariadb.jdbc)`, not the coordinate — and the scan
matched literal coordinates only, so it never saw the one real declaration.
Worse, the single shared list of bundling configurations contained
`runtimeOnly`, which is precisely how §16 says Connector/J *should* ship
(`libs.versions.toml` says so in as many words). Two errors cancelling out: a
matcher that could not see the artifact, and a rule that would have failed the
build on its correct declaration.

The original mutation check — flipping `connector-plan`'s `compileOnly` to
`implementation` — passed only because that module happens to use a literal
coordinate, and it gave false confidence about the catalog case. **This is the
same failure shape the entry above was written about: documentation claiming a
rule the implementation does not enforce.** Recorded rather than quietly fixed,
because the lesson is that "I mutation-checked it" is not the same as "I
mutation-checked the form the code actually uses".

The rule is now per artifact, since §16 states two different rules: Plan may not
appear in `implementation`, `api`, `runtimeOnly` or `compileOnlyApi`, while
Connector/J may not appear in the first, second or fourth — `runtimeOnly` is its
prescribed form. Catalog aliases are resolved from `libs.versions.toml`, and a
separate test fails if that resolution finds nothing, so the scan cannot go
blind again without saying so. Mutation-checked in the form the tree really
uses: bundling `libs.mariadb.jdbc` as `implementation` fails, and breaking alias
resolution fails.

### 8.4 — Not on player join

Providers run on `PLAYER_LEAVE` and `SERVER_PERIODICAL`.

A join is both the moment a player is least likely to have *just* linked and the
one path a proxy plugin must never make slower. A round trip there buys the
freshest possible answer to a question nobody is asking yet, and pays for it on
the surface operators notice first and complain about hardest.

`PLAYER_LEAVE` catches the session that just happened, which is when a link made
during play becomes visible; `SERVER_PERIODICAL` keeps the server page honest
without touching a player path at all.

### 8.5 — A tier that runs nothing, and two checks that could not see it

`fuzzTest` and `charsetHostilityTest` are wired into `check` for all nine Java
modules. Only `core` has a `@Tag("fuzz")` test; only `protocol` and `core` have
`@Tag("charset")` ones. So eight modules ran the fuzz task and seven ran the
charset task having discovered **zero tests**, every one of them reporting
success.

For a module with no tagged tests that is the right answer. The danger is the
other case: the default test task's comment already records a narrowing that
*"made fuzzTest run zero tests -- a green run that fuzzed nothing"*, fixed with
`outputs.upToDateWhen { false }`. That stops a **cached** empty result and does
nothing about a genuinely empty one — rename a tag, move a class, or add one
more exclusion, and the tier silently becomes zero coverage while the build goes
green.

So a tag-selected task now fails if its module's test sources **contain** the
tag and the task executed no tests. The narrowing is exactly that: modules with
no occurrence of the tag are not required to run anything, because for them zero
is the right answer.

**It took two wrong versions to get one that fires, and both were green.**

*First:* it counted XML files under `build/test-results/<task>`. Gradle does not
clear that directory when a run discovers nothing, so the previous run's files
were still there and the count was never zero. A check that reads the last run's
evidence cannot observe this one.

*Second:* the marker was written `"@Tag(\"${'$'}tag\")"`. In Kotlin `${'$'}` is
the escape for a literal dollar sign, so that compiles to the text
`@Tag("$tag")` — the tag was never substituted, nothing ever matched,
`declaresTag` was always false, and the check silently did nothing. It is now
built by concatenation, which has no interpolation to get wrong.

*Third, found by a full battery:* `charsetHostilityTest` had no
`outputs.upToDateWhen { false }`, so Gradle skipped it on an incremental build —
and a `doLast` does not run on a skipped task. Two consecutive invocations both
reported `:protocol:charsetHostilityTest UP-TO-DATE`, meaning the hostile-charset
tier *and* the new guard that reports whether it ran anything both silently did
nothing. A guard that is skipped whenever the thing it guards is skipped guards
nothing. It is now never up-to-date, for a different reason than `fuzzTest`: this
tier is deterministic, so the cached result is not stale — what could not survive
caching was the check riding along inside it.

*Fourth, and the one that should have been impossible:* the scan read raw file
text, and the javadoc added in this same change to `StorageBackends` — warning
that dropping `@Tag("fuzz")` would leave the battery green — itself contains
that literal. So `core` declared the tag partly because of a comment about the
tag, which made the warning false and would have failed the build forever, with
an untrue message, had `core` ever legitimately lost its last fuzz test. **The
copyleft guard thirty lines away already strips comments.** This is the fourth
time in this repository a check has matched its own explanatory prose, and the
second time it was reintroduced immediately after being fixed. It now strips
block and line comments, verified in both directions: the javadoc literal alone
does not fire it, and a real tag with zero discovery still does.

Four wrong versions, all green, before one that fires. The lesson is not that
mutation checks work; it is that they only work against the form the code
actually takes — and that a fix for this pattern does not inoculate the code
written next to it. Three mutations now fail as they should —
excluding the fuzz tag from `fuzzTest` (the historical bug, exactly), pointing a
task at a tag that matches nothing, and a real tag whose tests stop being
discovered — while modules with no tagged tests still pass, and a module whose
only occurrence of the tag is in a comment is correctly treated as having
none.

### 8.6 — A stage cannot report success for work it did not do

`harness/fullstack/run.sh` runs the battery's tiers against one live
deployment. The rule it is built around is one sentence: **every stage must
emit a result, and a stage that returns without emitting one fails the run.**

That is not defensive habit. It is the specific failure this repository keeps
hitting, in four different disguises so far: `fuzzTest` wired into `check` and
discovering zero tests; `charsetHostilityTest` skipped as up-to-date with its
own zero-tests guard inside it; a browser tier whose only failure evidence died
with the VM; and `:connector-plan:test` reporting UP-TO-DATE on the guest across
two full batteries. Every one of them reported green. None was caught by a red
run.

So the runner treats "no result" as a failure rather than as success, and says
so in the result it writes: *the stage returned without emitting a result, so
nothing it claims can be trusted*. `journeys.sh` holds the same invariant one
level down — a journey that emits no transcript fails, because silence is not
evidence.

**There is deliberately no skip result.** Every narrowing this project has
needed — MariaDB with no server, PHPUnit without ext-xmlwriter — is expressed as
a narrowing with a stated reason at the point that narrows it. A green result
carrying the word "skipped" is how a tier stops running without anybody
noticing.

**The invariant had a hole, found by probing it rather than by reading it.**
`result_open` clears the file it is about to write — which does nothing for a
stage that dies *before* reaching it, and a stage can die early for the most
ordinary reasons: a missing binary, an unreadable config. The previous run's
result then survives, and since the check only asked whether a result EXISTS,
last run's PASS was counted as this run's.

Two variants, both reproduced. A stage that failed early exited 1 but left a
stale PASS in `out/`. A stage that returned 0 having done nothing exited **0**,
reported as passing, with evidence from a run that had already finished. Since
`out/` is the only thing reaper syncs back, that stale file is exactly what a
reader sees — and evidence which outlives the run that produced it is worse than
no evidence, because it looks current.

Results are now cleared for every requested stage before any stage runs.

`FullstackStagesGuardTest` covers what run time cannot: that the stage list, the
implementations and the README's table name the same set, that no `stage_`
function is unreachable, that the resultless-stage check is still present and
still tests for the result file, and that no `result_skip` has appeared. Ten mutations, all caught — including deleting the invariant
itself and moving the clearing loop after the stages, neither of which changes
anything visible on a green run. That is exactly why a static guard is worth
having for a shell script.

One of those assertions was itself wrong when written: it anchored the ordering
check on `"stage_$requested"`, which also appears in the pre-flight validation
loop, so it compared the wrong pair and failed on the correct tree. Both
mutations "passed" while it was broken — a mutation result is only as good as
the assertion's own health, and one run against a red assertion tells you
nothing.

**And the guard's own must-fail fixture proved nothing.** It re-derived `STAGES`
with its own regex and re-applied its own containment test, so disabling the
real detection left all seven cases green. `SourceTree`'s javadoc states the
rule this broke — *a fixture checked by a second, parallel implementation would
prove only that the second implementation works* — and the fixture was written
in violation of it anyway. There is now one detector, called by both.

Three further holes an adversarial review found, all of which made a stage
report success for work it did not do — the file's stated subject:

- `result_pass` never checked that the write succeeded, so an unwritable `out/`
  produced a PASS over a stale file. A failed write now leaves the stage marked
  in-progress, which the existing invariant catches.
- `stage_down` ran `stack.sh --down || true` and then reported PASS
  unconditionally. It was green with `stack.sh` deleted from disk — a green
  result for work not done, produced by the one construct this project forbids
  outright, in the file whose whole subject is that failure mode.
- Nothing set `exit $failed` under test, so changing it to `exit 0` left every
  assertion green while a recorded failure never reached the caller.

A fourth was self-inflicted while fixing the third: `if "$HERE/stack.sh" | tee
"$OUT/stack.log"` tests *tee's* exit status, so a failed bring-up would have
read as a pass. Written minutes after catching the identical shape in a
verification command of my own.

**And the fix for that introduced a fifth.** The status was routed through
`$OUT/.stack-status` — a file the up-front clearing loop did not clear, one
filename away from the loop written to stop stale reads. Under FreeBSD's
`/bin/sh`, `set -e` is not suspended inside that subshell, so a failing
`stack.sh` killed it before `echo $?` ran and the *previous* run's `0` was read:
`up` reported PASS on a bring-up that failed, and the pristine snapshot was
taken on a broken stack. The file is now removed first and a missing file means
failure.

A second review round found four more of the same family, and the pattern in
them is worth naming: **each fix relocated the defect rather than removing it.**

- `stage_down` stopped using `|| true` — and `stack.sh --down` started. It ran
  `kill || true`, removed the pidfile regardless, logged "stopped pid N", and
  exited 0 whether anything had been running, whether the pid was dead, or
  whether the JVM ignored TERM. It now checks the process is alive, waits,
  escalates to KILL, and fails if the process survives.
- Clearing per-stage results was not enough: a `run.sh up down` followed by a
  failing `run.sh up` left a green `down.xml` beside the failure, and
  `evidence/` was never cleared at all, so a journeys stage that died early
  pointed the reader at the *previous* run's complete, passing transcript. The
  whole result directory now belongs to the invocation. The cost is stated:
  results do not accumulate across invocations.
- Two guard assertions could not fail. `body.contains("HARNESS FAULT")` was
  satisfied by `result_pass`'s and `result_fail`'s own logs, and
  `body.contains("failed=1")` by the ordinary stage-failure branch twelve lines
  above the fault branch it was written for — so flipping that branch to
  `failed=0` left all 44 guard cases green while the runner recorded a failure
  and exited 0.
- `journeys.sh` had no guard coverage at all. Deleting its refusal assertion,
  its admission assertion and its step-count fault left every guard case green.

A third round found the root cause of all of it, and it was architectural rather
than a slip. **`stack.sh` is a one-shot smoke.** It carries
`trap cleanup EXIT INT TERM` and never disarms it, so on SUCCESS it tears the
whole stack down and leaves the pidfiles behind. `run.sh` was built on top
assuming `up` left a stack running. So:

- `up` reported PASS on a stack that was already dead;
- the `@pristine` snapshot was taken on that dead stack, making the README's
  "roll back to a working stack" false;
- `journeys` could not reach core — round one's finding surviving through an
  entirely different mechanism;
- `down` reported PASS having killed nothing but dead pidfiles, **on every
  run**, which is the sixth route to unearned success in this one file.

`stack.sh --keep` now disarms the trap at the very end, only on success, only
when asked — every failure path and both signals still tear down. And `stage_up`
no longer trusts an exit status: it probes core's port afterwards, because a
script finishing is not a stack existing.

Three more from the same round, each the same shape one step over:

- `rm -rf "$OUT"` — the blunter instrument that replaced per-stage clearing —
  destroyed data outside the repository when `run.sh` was reached through a
  symlink (`$HERE` resolves the link's directory), and destroyed the LIVE
  `creds.env` and database when the caller pointed `SOULBIND_STACK_RUN` inside
  `out/`, which the forum tier already does. `$REPO` is now checked to be this
  repository and a run directory inside the results directory is refused.
- `note()` was the unfenced sibling of the `fail_journey()` fixed the round
  before, and it interpolates core's own `reason` into the transcript.
- The `--down` loop trusted pidfile contents. `kill -0 -1` succeeds, so a
  pidfile containing `-1` read as alive — and the escalation-to-SIGKILL added
  that same round would then have signalled every process the user owns. A
  one-shot `kill || true` merely failed; a loop that insists is what made
  validation necessary.

And a third inert guard assertion, plus a surviving one-character mutation:
`assertTrue(body.contains("write_coverage"))` was satisfied by the function
DEFINITION, so deleting the call stayed green — the lesson recorded four methods
above in `staleResultsAreClearedUpFront` and not applied here. Changing the
invariant's `||` to `&&` also left all eleven cases green while turning a
resultless stage from "HARNESS FAULT, exit 1" into silence and exit 0.

The guard strips shell comments before matching. Four instances of a check
matching its own explanatory prose have now happened here, so that is written in
from the start rather than after.

### 8.7 — Migration idempotence is a claim about restarts, not about fresh databases

Core migrates on every `Storage.open`, so a deployed server re-runs migrations
every time it starts. If a second apply were not a no-op, the schema would drift
once per restart — a failure visible only on a long-lived server, and invisible
to any test that migrates a fresh database and stops.

So the check runs **in-session, against a database that has already been
migrated and used**, and it does the work through core's own installed
classpath: the same Flyway, the same drivers, the same `Storage.open` the server
calls. A shell re-implementation of "apply the migrations" would be a second
definition that could agree with itself while disagreeing with the server.

The fingerprint is dialect-neutral on purpose — Flyway's history rows plus JDBC
`DatabaseMetaData` for tables and columns. Comparing DDL text would compare
SQLite's and MariaDB's spelling of the same schema and report differences that
are not defects.

**The first fingerprint was far weaker than the claim made for it.** It read
Flyway's history plus table and column names, and I mutation-checked it by
adding a table and adding a column — the two things it was always going to see.
An adversarial review then dropped an index and inserted a row into
`audit_seq`, and it reported the database unchanged.

The seed-row case is the one that matters. A migration that re-inserts or
resets a sequence-emulation row on every apply hands out audit and event
identifiers that were already used — drift on every restart, with no schema
change at all, which is *exactly* what this check exists to catch. It passed.

It now also reads indexes (with uniqueness — dropping a UNIQUE constraint is the
difference between one identity per platform account and any number), primary
keys, foreign keys with their delete rule, column size, default and ordinal
position, and the contents of the sequence tables. Five mutations caught: a
dropped index, an inserted seed row, a **reset sequence counter**, an added
table and an added column.

What it still cannot see is written down rather than implied: views and triggers
(the schema has none) and CHECK constraints, which JDBC metadata does not expose
portably.

A second round found it still blind to **table contents**: resetting
`runtime_config.config_value` — an operator's configured code TTL — and
emptying `platform_kind` both left the fingerprint identical. The seed-table
list was also hand-written and skipped missing tables silently, and nothing
asserted the fingerprint had measured anything at all, so a metadata call
returning nothing would have compared two empty strings and printed
"idempotent".

It now records a row count for every table, dumps the contents of every small
one — derived by size rather than named, so the next config or sequence table is
covered the day it exists — reads the FK `UPDATE_RULE` beside the `DELETE_RULE`,
and refuses to report anything if it measured less than a floor.

A third round found two more, both of which would have made the whole
contents-and-counts half worthless:

- The 200-row threshold was a **silent coverage cliff**. Rewriting every row of
  `audit` was caught at 200 rows and invisible at 201 — and `audit` and
  `event_outbox`, where reused identifiers actually hurt, are precisely the
  tables that cross it on a long-lived server. Worse, the cliff is
  data-dependent, so the same drift is caught on a fresh database and missed on
  the used one this file exists to test. Large tables now get a streamed,
  order-insensitive digest instead of a bare count; the boundary was re-tested at
  200, 201, 250 and 1000 rows and all four are caught.
- Every count and contents read was written `SELECT COUNT(*) FROM "table"`.
  MariaDB reads a double-quoted token as a string LITERAL without `ANSI_QUOTES`,
  which nothing sets — so on the backend this had never run against, every one
  of those queries would have been a syntax error, swallowed by the broad
  handler, reported as `-1` in both fingerprints, and compared equal. The
  identifier quote string now comes from the driver. Core itself never quotes,
  for the same dialect reason.

The lesson is the same one this phase keeps teaching: mutation-checking against
the cases you already had in mind measures your imagination, not the check. Two
of those mutations first read as SURVIVED for a reason that was not the
fingerprint's fault — the tables were empty on a database created by `register`
alone, so the mutation changed nothing. A mutation run against the wrong setup
is as uninformative as one run against a red assertion, and both happened in the
same afternoon.

### 8.8 — The toolchains are pinned artefacts, not a reason to containerise

The Linux guest ships "ZFS, podman, rsync, guest agent. No language
toolchains." That was treated for some time as a wall: the game tier would have
to be rebuilt to run every component in a container, the way the forum tier
does.

It was not a wall. `harness/fullstack/fetch.sh` already fetches
checksum-pinned artefacts and refuses to continue on a mismatch, and it was
written from the start to run on the workstation **and** in the guest — it
carries an explicit branch for FreeBSD's `sha256 -q` versus Linux's
`sha256sum`, with a comment saying exactly that. A JDK and a Node are simply two
more pinned artefacts, and one `stack.sh` then runs in both places.

Not `apt-get install openjdk`. An unpinned toolchain is precisely what
`pins.env`'s own header argues against — *"a stack that silently changed proxy
build underneath a test is a stack whose green result means nothing"* — and a
JDK is more load-bearing than a proxy jar, not less. (reaper's `host_packages`
escape hatch is unimplemented in any case: no source references it, and the plan
says "implement last, if at all.")

**Platform asymmetry, stated rather than hidden.** Neither Temurin nor
nodejs.org publishes a FreeBSD build, so the tarballs are linux-x64 and are
fetched only there. The workstation uses its own toolchains and gets a
*minimum-version assertion* instead of a pin. The guest — where nobody is
watching — gets the pinned bytes.

### 8.9 — What running it once found that three review rounds did not

The stage runner was reviewed adversarially three times before it had ever
executed. Running it twice found three defects in about ten minutes, none of
which any review had surfaced:

- The toolchain selection read `[ -z "$JAVA" ]` **after** `JAVA=${JAVA:-java}`,
  so the pinned JDK would have been fetched, verified, extracted and never used.
  On the guest that failure would have read as "no JVM on the guest" all over
  again — the very belief that made the containerisation look necessary.
- **This workstation exports `JAVA_HOME=/usr/local/openjdk17`.** Both scripts
  derived `JAVA_HOME` only when it was unset, so Java 17 won, while the version
  check tested `$JAVA` and logged a cheerful "java 25". The check validated one
  JVM and Gradle's start scripts used another. The comment directly above that
  code already described this exact failure — the fix had been written for the
  case where `JAVA_HOME` was absent, not the case where it was wrong.
- `migrate` reported "re-applying migrations was not a no-op" when the truth was
  that `up` had failed and there was no database at all. It now exits 3 and says
  so.

And `git status` offered 182 MB of generated Paper world as committable:
`.gitignore` named `harness/fullstack/run/` while the runner creates
`run-<db>`. The instance, not the class — the third time in this repository a
rule written for one filename missed its successor, which the `.gitignore`
comment for `pins.env` already records twice. Now a glob, and guarded.

The lesson is not that the reviews were wasted; they found real defects and the
severity fell each round. It is that **static review of code that has never run
converges on the wrong things.** Three rounds hardened guards, and the first
execution found a JVM mismatch, an inverted condition and a misattributed
verdict — none of them visible to any amount of reading.

### 8.10 — A test double that corrupts under the concurrency it is used to test

`LinkDataSourceTest`'s concurrency case drives eight threads through one
`InMemoryTransport` — which held its record of sent requests in a plain
`ArrayList`. On a contended guest it threw `ArrayIndexOutOfBoundsException` from
inside `ArrayList.add`, and the stack trace named the SDK's test double rather
than the code under test.

The loud failure is the exception. The quiet one is worse: `sent()` returning a
list missing entries, so an assertion about what a connector sent is wrong and
nothing says so. A test double that cannot survive the concurrency it exists to
exercise makes every concurrency test built on it untrustworthy — including the
one written to prove `LinkDataSource` is safe under Plan's threads.

Now a `ConcurrentLinkedQueue`: correct under concurrent senders, and without the
whole-array copy per send that a copy-on-write list would cost a test sending
thousands of requests.

**The first version of this entry was wrong, and the way it was wrong matters.**
It said the fix "cannot be mutation-checked on the workstation, because the
defect it repairs does not reproduce there". A review measured it: driving the
class directly, eight threads by four hundred sends, the broken version reported
a wrong count **4999 times in 5000 on this workstation**. It reproduces here
almost every time.

What did not reproduce was the *test's* failure — 0 detections in 16 contended
runs — because the test asserted on `LinkDataSource`'s cache and never on the
transport, and the cache is correct even when the record of what was sent is
not. I read "the test does not fail here" as "the defect does not occur here",
and wrote the second into the record.

So the fix had shipped with **no test that would have caught it**, which is the
standing rule this project holds on every commit. The concurrency test now
asserts `transport.sendCount()` as well: with that line, the broken transport is
caught **8 times in 8** on the workstation, deterministically. A test that drives
a double from eight threads and never checks the double is a test standing on an
assumption.

One measurement worth keeping, because it contradicts the obvious model:
contention *lowered* the class-level failure rate — 87% idle against 6.5% under
load. The window is inside `ArrayList.add`, and a busier machine spends
proportionally more time elsewhere. "Run it under load to shake out a race" is
not reliably true.

### 8.11 — The same defect, a fourth time, on a third axis

`stack.sh` resolves `$NODE` from the pinned toolchain, and the player-driver
dependency check runs `"$NODE"`. The smoke then ran a bare `node`.

The `PATH` prepend that makes a bare `node` mean the pinned one was inside the
branch that only fires when the caller did *not* set `NODE` — so setting `NODE`
explicitly left `PATH` untouched and the smoke executed on whatever was first on
`PATH`, a different runtime from the one whose dependency tree had just been
verified.

This is the `$JAVA` versus `$JAVA_HOME/bin/java` defect, which this same file
documents in three separate comments, reappearing on the Node axis. The pattern
is now unmistakable: **when a script resolves an interpreter into a variable, every
invocation must use that variable, and any `PATH` that stands in for it must follow
the same resolution.** `PATH` now follows whichever `node` was chosen, pinned or
caller-set, and the smoke invokes `"$NODE"` directly.

### 8.12 — The environment that supplies a capability is the environment that asserts it

`StorageBackends.available()` returned one backend or two depending on whether
`SOULBIND_TEST_MARIADB_URL` was set, and nothing anywhere checked that a session
which was *supposed* to have MariaDB actually did. Drop that variable — a typo in
the manifest, a container that failed to start — and the storage battery halves
from 471 tests to 402, in silence, and the session reports green having proven
half of what its gate asks for.

The workstation legitimately has no MariaDB, so the requirement cannot be
unconditional. So the run stage, which starts the server, now also sets
`SOULBIND_REQUIRE_MARIADB=1` — and `available()` refuses to fall back to SQLite
alone when that promise is present.

Same shape as the tag-selected task guard: **whoever supplies a capability is
whoever asserts it arrived.** A narrowing is legitimate where it is honest about
the environment; it is not legitimate as a silent fallback in an environment that
declared it would not need one.

What this does *not* catch is a cached result standing in for a real run. The
run stage's `:core:test` re-executes only because its project cache dir is inside
a `--rm` container; that dependence is now written into the manifest, since
nothing enforces it.

### 8.13 — A guard the build cache can walk around

`SOULBIND_REQUIRE_MARIADB` (8.12) makes a session that promised a MariaDB server
fail when it has none. It cannot make a session that never runs the task fail at
all.

`gradle.properties` sets `org.gradle.caching=true`, the run stage's
`GRADLE_USER_HOME` lives inside the guest work tree, `[reset]` rolls back only
`state`, and **an environment variable is not a task input**. So with the flag
set, no URL, and no MariaDB container running whatsoever, `:core:test` came back
`FROM-CACHE` — BUILD SUCCESSFUL, exit 0, and result XML claiming 471 tests and
seventy `MARIADB` cases, replayed from a previous session. `available()` never
ran, so the guard never spoke.

`:core:test` is the one `Test` task without `outputs.upToDateWhen { false }`, and
it is the task carrying the two-backend claim.

The fix is to declare the storage environment as an input rather than to disable
the cache: a SQLite-only result and a two-backend result now have different keys,
so each remains reusable and neither can stand in for the other. Verified —
same environment hits cache, setting the MariaDB URL forces a real run, clearing
it hits the first result again.

**The general shape, which has now cost three separate defects:** a check that
lives *inside* a task cannot defend a claim about *whether the task ran*.
`failIfTaggedTestsExistButNoneRan` had to be paired with `upToDateWhen { false }`
for the same reason, and the resultless-stage invariant in `run.sh` exists
because a stage that returns without emitting proves nothing. Whatever decides
"did the work happen" has to sit outside the work.

### 8.14 — There is no read-only capability, and the dashboard needs one

`connector-plan` was written, documented and committed on the claim that it is
"registered with capabilities that permit inspection and nothing else". That was
not true, and wiring the plugin bootstrap is what surfaced it: the operation it
called, `subject.inspect`, requires **`config-management`** — which also unlocks
`rule.set`, `override.set`, `config.set`, `audit.query` and `identity.unlink`.

A read-only dashboard would have shipped holding a credential that can rewrite
every rule and unlink anybody. `protocol.md` names this exact failure:
*"granting it `config-management` to do so would let it rewrite every rule — the
capability model being correct and the deployment being wrong."*

`identity.describe` returns **the same thing** — core binds the same request
type for both and the handler says so — and needs only `code-display`. The
connector now asks that, so the grant drops from admin to code-issuing.

**That is a mitigation, not a fix, and the gap is the point.** The seven
capabilities are `identity-provider`, `code-display`, `code-entry`,
`enforcement-point`, `effector`, `audit-source`, `config-management`. None means
"may read, may not write". So the least-privilege grant available to a
read-only connector still permits minting link codes — bounded, since a code
must be redeemed by whoever controls the other account, but not nothing.

The real fix is a read-only capability in core. That is an addition to the
capability model late in a phase, it touches the protocol document and the
authorization matrix, and it is the owner's call rather than one to make
heads-down. Recorded here rather than quietly living with the overstatement.

**Why no test caught it:** the two operations return identical payloads, so
every existing assertion passed either way. `LinkDataSourceTest` now pins the
operation name, and switching it back to `subject.inspect` fails. This is the
same shape as everything else this phase produced — the property that mattered
was invisible to every test that looked at results rather than at the request.

### 8.15 — Plan on a proxy takes MySQL, and that decides where Plan runs

Bootstrapping `connector-plan` meant running Plan, and running Plan surfaced a
constraint no amount of reading the connector would have: **Plan on a proxy
supports MySQL only.** Its own generated config says so —
`# Supported databases: MySQL` — and on the sqlite axis it tried localhost,
failed, disabled itself, and left the extension unregistered.

Two ways out, and §16 chooses between them:

- **Bootstrap on Paper**, where Plan can use SQLite, and run on both axes. Costs
  a GPLv3 compile dependency and a departure.
- **Keep Velocity** (velocity-api is MIT) and run Plan only where a MySQL-family
  server exists — which is the mariadb axis, since it already runs one.

§16 permits the first only if paper-api becomes *unavoidable*. It has not. So
Plan and its connector install on the mariadb axis alone, and the narrowing is
stated where it narrows: it covers Plan and `soulbind-plan` and nothing else,
with every other component still running on both axes.

**The cost is real and worth naming rather than glossing.** This workstation has
no MySQL server, so the Plan tier cannot be exercised locally at all — the same
fast loop that has repeatedly found what review missed. If local testability
ever matters more than the licence preference, the Paper route is a departure
§16 already anticipates, and this decision is the place to revisit.

Two smaller things the first run taught, both invisible from source:

- Velocity refused the connector jar outright. `@Plugin` is decoration; the
  runtime reads `velocity-plugin.json`, which `connector-velocity` carries
  hand-written in `src/main/resources` rather than generated by an annotation
  processor. Without it the plugin does not load and nothing says why beyond
  "Unable to load plugin".
- The connector declares a Velocity plugin dependency on `plan`, so the proxy
  loads it second. Without that, `ExtensionService.getInstance()` races Plan's
  startup and the extension is silently absent — a page with a missing panel and
  no error anywhere, which is this connector's whole failure mode.

### 8.16 — Plan renders it, and the check that said so could not have said otherwise

The gate's second clause is met. Plan, running on Velocity against MariaDB,
renders for a player linked by a real client running `/link` and redeeming a
code:

```
linked = true            linkStatus = "linked"     platforms = "game, harness"
proof  = "link-code"     subject = 2081dcaa-…      linkedSince = 1787148246000
```

Plan's own log corroborates the connector rather than the other way round:
`Registered extension: soulbind`. And it proves the capability change from 8.14
end to end — `LinkDataSource` reached `identity.describe` holding `code-display`
alone and got real data, so nothing silently failed authorization.

**The first run of the check went red for a reason entirely inside the harness.**
Plan's `JettyResponseSender` gzips every `application/json` response and sets
`Content-Encoding: gzip` — on mime type alone, never reading `Accept-Encoding`.
`curl` without `--compressed` therefore wrote two kilobytes of gzip into the
evidence file, every grep missed, and the stage reported "no soulbind extension
on the player page" about a page that had rendered all six providers correctly.

**Then the check passed, and it was under-earned in three ways** — all the same
family this phase keeps producing:

- It asserted on `Linked` and `Link status`, which are the annotations' `text=`
  **labels**. Plan renders those whether the provider returned true or false, so
  an extension reporting "not linked" for a linked player passed identically —
  and the unlinked bot in the same run produced exactly those rows. It now reads
  the **values**, including that `linkedSince` is milliseconds rather than
  seconds, which is the difference between a date and 1970.
- It matched the string `soulbind`, which appears in `extensionInformation`
  whether or not a single provider ever ran — the precise silent failure the
  tier exists to catch, and the check's own comment said it was avoiding it.
- It asked `/v1/serverOverview?server=soulbind-harness`, which returned **HTTP
  400 on every run and was never asserted on**. It could not have worked:
  `Server.ServerName` is ignored on a proxy (Plan registers it as `Velocity`),
  and `serverOverview` carries no extension data even when it succeeds. So the
  four server-wide providers and the table were covered by nothing while the
  stage reported green. Now `/v1/extensionData`, with a bounded retry because
  Plan gathers `SERVER_PERIODICAL` on its own schedule.

### 8.17 — Pinning the dependency somebody else already pinned

Plan fetches its MySQL driver at every startup through a dependency downloader:
`com.mysql:mysql-connector-j` and `com.google.protobuf:protobuf-java`, into
`plugins/plan/libraries`.

It **does** verify them — `assets/plan/dependencies/mysqlDriver.txt` inside the
Plan jar carries SHA-256 for both — so this was never an unverified download.
It was a verified one this repository did not control and could not reproduce
offline: a stack-up that reaches the internet on every run fails when the
network does, for a reason unrelated to anything under test.

Both are now pinned here and pre-seeded into the directory under the exact
filenames Plan's downloader writes, so Plan finds them present and fetches
nothing. The checksums were taken from Maven Central and **cross-checked against
Plan's own manifest** — two independent statements of the same value, which is a
better guarantee than either alone, and they matched exactly.

The general point: a harness whose discipline is "the bytes are pinned or the
green means nothing" should not have one component exempt because somebody else
did the pinning. Their pin is not ours to rely on, and it does not survive an
offline run.

### 8.18 — The schema never said what charset it was in

Specification §11 Tier 6 requires the battery's MariaDB to start **latin1**. It
had never been started that way, and writing the flag turned out to be the
smallest part of the change.

Core specified **nothing** about charset — not on the connection, not in the
DDL. All seven common migrations create tables with no `CHARACTER SET` clause,
so each table takes the database default, which takes the server default. The
harness then created every database `CHARACTER SET utf8mb4` explicitly, and the
image's own default is utf8mb4. Two layers of coincidence, and underneath them
core had no opinion at all.

`AuditRepositoryTest.survivesHostileText` pushes `😀🤖` through the audit detail
on both backends and reads it back. It has been green since Phase 1. It was
green because the harness had already made the database right on core's behalf —
the assertion was correct, well-motivated, and testing the fixture.

Point the same code at a server started `--character-set-server=latin1` and
every text column in the schema is latin1. A four-byte character reaching one is
truncated or rejected depending on `sql_mode`, so a player whose name is an
emoji cannot link, and the error names a column rather than a charset. That is
not an exotic deployment: it is what a long-lived installation upgraded across
major versions typically still has, and nobody involved knows it.

**The fix says the charset out loud in three places, because they fail
differently:**

- `ALTER DATABASE` in the dialect migration, so every table a *future* migration
  creates inherits utf8mb4 and V9's author does not have to know this.
- `CONVERT TO CHARACTER SET` for the fifteen tables V1–V7 already created,
  because inheritance cannot reach backwards. A no-op on a database that was
  already utf8mb4, which is what makes it safe to add now rather than only for
  new installations.
- `connectionCollation` on the pool. Connector/J 3.5 issues `SET NAMES utf8mb4`
  of its own accord, so this changes nothing today — and "the driver happens to"
  is not a property a schema can afford to inherit. utf8mb4 columns reached over
  a latin1 connection are mangled on the way in while every column definition
  still looks correct.

**Why the round-trip test is not enough on its own.** It can only see a column
somebody wrote four-byte text into during the suite. A new table added by a
later migration, on a latin1 server, is broken from the day it ships and every
test stays green. `SchemaCharsetTest` therefore asserts on the schema itself —
the database default, the session variables on the pool core actually writes
through, every table's collation and every text column's charset — and it checks
the enumeration is non-empty first, because "nothing in this list is wrong" is
satisfied by an empty list.

**Two narrowings, both stated where they apply.** `flyway_schema_history` is not
converted: Flyway is writing this migration's own row into it while the
statements run, and it holds versions, descriptions and filenames authored in
this repository, all ASCII. And the forum tier's MariaDB is *not* started latin1,
because it is shared with Flarum, which requires utf8mb4 — a latin1 server there
would break the forum and report the result as soulbind's. What that tier does
contribute is the privilege question: core migrates there as a non-root user, so
`ALTER DATABASE` has to work without superuser rights.

**The pattern, again.** This is 8.5 and 8.13 in a new place: a check that passes
because the environment was arranged to make it pass. The tell is the same one
every time — the assertion is about something the test does not control, and
nothing in the run says which of the two produced the green.

### 8.19 — An assertion that could not pass, and the one beside it that could not fail

`8557416` rewrote `plan-check.sh` to assert on provider *values* rather than on
annotation labels. The rewrite was right about the problem and wrong about the
data. Its JSON walker looked for a node carrying both `name` and `value`:

```python
if node.get("name") == name and "value" in node:
```

Plan's actual shape nests the name one level down, and `value` is a sibling of
the object holding it:

```json
{"description": {"name": "linked", "text": "Linked"}, "type": "BOOLEAN", "value": true}
```

The dict with `name` has no `value`; the dict with `value` has no `name`. **No
Plan response can satisfy that predicate.** The stage went red on a run where
all six providers had rendered correctly, and the commit message claimed it had
"made the Plan check able to fail". It had. It had also made it unable to
succeed, and the two are worth the same: neither tells you anything about the
system.

The cause is not subtle and is worth naming, because it is the same one as 8.9.
The shape was **imagined rather than read off a real response**, and the
fixture written to test the walker was imagined from the same picture, so the
two agreed with each other. A check and its fixture built from one guess are one
guess, not two.

**And the block twenty lines below it still grepped for provider names** — the
exact defect the player page had just been rewritten to remove, left standing in
the same file. A 76-byte plain-text file containing only the words
`linkedPlayers unlinkedPlayers unknownPlayers unlinkedTable` passed the whole
server-page section. The retry loop compounded it: it broke on
`grep -q linkedPlayers` and the first of the four assertions then grepped the
same unchanged file for the same string, so that assertion could not fail by
construction.

**What the real evidence says.** The captured `plan-server.json` from a run in
which a player was provably linked reads `linkedPlayers = 0`,
`unlinkedPlayers = 0`, `unknownPlayers = 0`, `unlinkedTable` with no rows — and
`linked_aggregate = "50%"`. The counters are zero because they derive from
`proxy.getAllPlayers()`, and nobody is connected when Plan's `SERVER_PERIODICAL`
fires. So a `>= 1` assertion there would fail on correct code, and the counters
cannot presently distinguish a working extension from a broken one. They are
asserted to be *counts* and nothing more, and the gap is logged by the stage
itself rather than left to be inferred from a green run.

`linked_aggregate` is the one server-side value that does discriminate, and it
is not one of this connector's providers: **Plan computes it itself** by
aggregating the per-player `linked` boolean across its whole player table. That
makes it corroboration rather than an echo — it says Plan stored what the player
provider returned and could compute over it — and it is non-zero on an idle
server. It is now asserted.

**Two further things this run exposed.** The proxy log, which carries Plan's own
`Registered extension: soulbind` — the only line in the whole stage not written
by the code under test — was tailed on a single failure branch and discarded on
success, so the passing run kept no copy of the most valuable evidence in it. It
is now collected on both paths and asserted. And `curl -o` does not truncate its
output file when the connection fails, so a second fetch that cannot reach Plan
leaves the previous body on disk for `[ -s ]` to accept; harmless only because
`run.sh` removes `out/` per invocation.

**The mutation battery, replayed against the real captured response** rather
than a fixture: `linked`→false, `linkStatus`→"not linked", `proof`→null,
`linkedSince`→seconds, `platforms`→"forum", `linked_aggregate`→"0%",
`unlinkedTable` columns emptied, the whole server body replaced with the plain
text that used to pass, and the `Registered extension` line removed from the
proxy log. Ten mutants, ten reds, control green. An ungzipped response still
passes, so `--compressed` is safe in both directions.

**A note on the harness that found this.** The first version of my own mutation
runner wrote each mutant to a file the replay server never served, so all eight
mutants "survived" identically. The tell was uniformity: eight different
mutations producing byte-identical output is not a finding about the code, it is
a finding about the runner. A mutation battery needs its own control, and the
control has to be a mutant that is *known* to fail.

**On the gate clause.** Phase 8's second clause — "Plan pages render link data
for players created through real flows" — is met, and was met before any of
this: the captured evidence shows `linked=true`, `linkStatus="linked"`,
`platforms="game, harness"`, `proof="link-code"`, `linkedSince` in milliseconds,
against a player linked by a mineflayer client running `/link`. What was not
true is that the automated check *enforced* it. From `558df1e` it passed on
labels that render regardless of value; from `8557416` it could not pass at all.
The clause rested on evidence a human read, which is exactly the arrangement the
tier exists to replace.

### 8.20 — Automating the thing this project had been doing by hand

Every vacuous assertion found in Phase 8 was found the same way: break the
covered code by hand, watch what happens. That operation is mechanical, and
tools have performed it exhaustively for forty years. Doing it by hand, once per
assertion, on discipline, was the most laborious standing rule in this
repository and the one most likely to be skipped on a bad day.

Three tiers now do it mechanically.

**Java — PIT**, invoked directly rather than through the community Gradle
plugin, whose last release predates Gradle 9 by two major versions. A build tool
that breaks on upgrade is a tool that gets disabled in a hurry the first time it
is inconvenient. The command line is a stable interface; the convention plugin
is forty lines and will not rot.

It is applied from `soulbind.java-common` rather than module by module, so a new
module cannot be created without it, and it registers only where main sources
exist — `guards` grades the tree, it is not graded.

**PHP — Infection**, from a checksum-pinned PHAR. `composer require --dev` does
not resolve: Flarum 1.8 locks `psr/log` to 1.x and Infection 0.35 needs
`^2 || ^3`. A mutation runner sharing a lock with the code it mutates was never
a good arrangement, and the PHAR is one checksum instead of a hundred transitive
packages resolved fresh. It needs a coverage driver, so the pinned PHP image
gains pcov in a build step.

**Shell — fixtures.** There is no mature mutation tool for POSIX shell and it
would be the wrong tool: these scripts *are* the tests, so mutating them asks
nothing useful. What has to be mutated is what they **observe**. So a recorded
Plan response is replayed with Plan's own wire behaviour, once clean and once
per catalogued mutation, and the check is required to complain each time.

The runner asserts three things, and the third two are the ones that matter:
the control must **pass**, at least one mutant must have **run**, and every
mutant must die. Without the control, a check that rejects everything scores
100% — which the walker in `8557416` would have done.

#### What the first run found

1,630 mutants across eight modules. 1,015 killed. **416 never executed by any
test at all, and 199 executed by a test that did not notice.** Those 199 are the
interesting number: a test ran the line, the line's behaviour changed, and
nothing failed.

Six of them were in `NonceStore`, which is replay protection. Both thresholds —
the amortised sweep and the fail-closed refusal when full — sit 256 and
1,000,000 insertions away in production, and every existing test called
`sweep()` by hand. Negating the conditional that decides whether a sweep ever
happens unprompted changed nothing any assertion could see. Neither did deleting
the sweep call. The thresholds are now injectable for tests, which is not a
weakening: the comparison being mutated is the same comparison, and asserting a
branch in milliseconds beats asserting it in a million insertions and several
hundred megabytes.

Three were `kinds.seen(...)` in `LinkingService.issue`, `redeem` and `attest`.
Core has no compiled-in list of platforms; it learns them from what connectors
do, and `soulbind doctor`, the admin surface and the policy engine all read that
list back. Deleting all three calls left every linking test green: the links
formed, the graph read back correctly, and core simply never learned the
platform existed.

`LinkCode.isCanonical` could return `true` unconditionally. Every existing
assertion was about a string that *is* canonical, so a method answering yes to
everything passed them all.

The rejection sampling in `LinkCode.generate` could have its limit turned from
`256 - (256 % 28)` into `256 + (256 % 28)` — which disables rejection entirely
and restores the modulo bias — with nothing failing. Both mutants produce codes
of the right length drawn from the right alphabet; only the *distribution*
changes, and a distribution assertion against a real CSPRNG is either flaky or
slow. The byte source is now injectable, so a draw the sampler must reject can
be fed in and its absence asserted.

`SoulbindClient.call` could return `null` on a transport failure. Every test
went through `decide()`, which falls back to the cache or the fail mode and
therefore produces a sensible answer whatever `call` returns. The first
connector to use `call` for something that is not a decision would have taken a
`NullPointerException` during an outage.

#### Two equivalent mutants, named rather than suppressed

`LinkCode.normalise`'s `c >= 'a'` can become `c > 'a'` harmlessly: `'a'` folds
to `'A'`, and `'A'` is not in the alphabet, so both spellings reject it. The
sibling mutant on the same line — `c <= 'z'` — *is* killed, which is how the
first one is known to be genuinely equivalent rather than merely uncovered.

`JdbcAuditRepository.writeDetail` returning `""` instead of `null` for empty
detail is equivalent at the seam: `readDetail` treats null and blank
identically, and nothing queries the column for null.

Neither is excluded from the report. An exclusion list is a place where real
survivors go to be forgotten; a paragraph is not.

#### No thresholds, yet

Neither PIT nor Infection is gated on a minimum score, and `check` does not
depend on either. A threshold gets lowered the first time it is inconvenient,
and a lowered threshold is a decision about what this project permanently stops
noticing. A slow `check` is a `check` people stop running. Both numbers go in
when there is one worth defending.

### 8.21 — A replay hole on both sides, found by symmetry

The PHP tier's first mutation run reported that `WebhookVerifier`'s nonce
retention — `$this->windowSeconds * 2` — could be changed to `* 1`, `* 3` or
`/ 2` with every check still green. Nothing asserted it at all.

Writing the test that killed those mutants is what found the defect, and the
defect was in the `* 2`.

A delivery stamped `t` is acceptable while `|now - t| <= W`: a span **2W wide,
inclusive at both ends**. A nonce first seen at the earliest of those, `t - W`,
must still be remembered at the latest, `t + W`. Retaining for exactly `2W`
makes it expire at `t - W + 2W = t + W`, and that store sweeps entries whose
expiry is `<= $now` — so at `t + W` the nonce is forgotten while the timestamp
check still accepts. **A captured delivery replayed at the final instant of its
own window was accepted.** Confirmed by reverting the fix: the verdict comes
back `accepted` rather than `replayed-nonce`.

One instant wide, on the only endpoint an unauthenticated caller can reach.

#### The same reasoning, applied to core, found a much wider one

`SignedRequestVerifier` accepts `|now - timestamp| <= W`, the same 2W span. And
`Main` constructed its store as `new NonceStore(window)` — **retention W, not
2W**. A nonce first seen at `t - W` is swept once `now > t`, leaving a captured
request replayable from `t` to `t + W`: **the entire second half of its own
window**, at the default 300s setting.

`new NonceStore(window)` reads entirely reasonably, which is why it survived
review. The retention is now a named factory, `NonceStore.retentionFor`, so the
relationship lives in one place instead of at each call site.

#### The test needed a sweep, and saying so matters

Two `recordIfNew` calls do not trigger one: the store sweeps every 256
insertions. A naive test would pass against the broken retention, because the
entry simply had not been reclaimed yet. The test therefore uses
`sweepInterval = 1`.

That is not arranging the failure. It is what a server with traffic does every
256 requests; a test that never sweeps is testing a store that never reclaims,
and would pass with the retention set to anything at all.

#### A correction, on the record

The first version of the Java fix used `2W + 1`, and its comment asserted that
`2W` was "wrong by one tick". **That was wrong**, and the mutation check is what
said so: `2W` passes, only `W` fails. Java's `sweep` drops entries *strictly*
older than the cutoff, so an entry recorded at `t - W` survives a cutoff of
exactly `t - W`. The `+1` was an unasserted constant defended by a comment I had
not verified — the same defect as an assertion that cannot fail, wearing
different clothes.

The two sides now differ by one tick and are each correct for their own sweep:
core retains `2W` against a strict comparison, the PHP store `2W + 1` against an
inclusive one. The asymmetry is deliberate and is recorded in both places
because it looks like a mistake.

#### What is left there, and why it stays

Two `IncrementInteger` mutants survive on the PHP retention line. Both make
retention *longer*, which is strictly more conservative: they cost memory, never
correctness. They are not excluded from the report.

#### The number

PHP: 415 mutants, 332 killed, **83 covered and undetected**, 100% mutation code
coverage, 80% MSI. After: 421 / 343 / 78, 81% MSI, and the replay path clean but
for the two above.

The 100% mutation code coverage is worth separating from the MSI. Every mutable
line in `src/` is reached by a test — the PHP suite has no blind spots of the
kind core has 411 of. What it has is 78 places where a test watched something
happen and did not check what it was.

## Phase 9 — simulated users

### 9.1 — The shrinker is deferred, and the re-entry criteria for it

Phase 9 ships trimmed. **What lands:** the oracle self-test, the generator, the
actors, the shadow model, the checker, a small committed seed set and the
promotion rule. **What does not:** the shrinker, and two of the six nemesis
classes.

#### Why the shrinker is the right thing to defer

A shrinker turns *"seed 481923 fails after 400 actions"* into *"these six
actions fail"*: bisect the run to the shortest failing prefix, then drop action
kinds one at a time and keep each removal that still fails.

That is **ergonomics, not detection.** It changes how long a person takes to
understand a failure; it has no effect whatever on whether the failure is found.
Detection is the generator, the shadow model and the checker — and all three
land. A trimmed tier finds exactly the same defects as a full one. It just
reports them less kindly.

The cost is paid only when a seed actually fails, and it is paid by somebody who
already knows a real defect exists. That is the best possible moment to spend an
hour, and the worst possible moment to have spent three weeks in advance.

#### The risk this carries, stated plainly

The failure mode is not "reading traces is tedious". It is **a failing seed
going uninvestigated because the trace is too long to face.** That would convert
a working detector into decoration, and it would happen quietly, which is this
project's whole recurring theme.

Two things guard against it, and they are obligations rather than hopes:

- **The transcript is readable by construction.** The tier emits a per-step
  transcript in the shape the `journeys` stage already uses for Tier 11
  evidence, not a raw action dump. A 400-step transcript that reads as prose is
  a different object from a 400-element array.
- **A failing seed is never dismissed.** It is promoted to the committed set
  and investigated, however long that takes. A seed that found a defect once is
  permanent, per §11 Tier 9.

#### Why two nemesis classes and not the other four

The line is not "how much time is left". It is **whether the defect requires
accumulated history**:

| Class | Kept | Why |
|---|---|---|
| Stale connector credential | **yes** | Only interesting when the credential was retired two hundred actions ago and something still holds it |
| Act-on-unlinked | **yes** | The interesting case is an identity unlinked long before the connector acts on it |
| Config flip mid-flow | **yes** | Nothing else in the battery changes runtime configuration underneath an in-progress flow |
| Abandonment | **yes** | Codes accumulating unredeemed over a long run is a state no other tier constructs |
| Hostile corpus input at write endpoints | no | Tier 7 drives the same corpus at the same endpoints. Depth adds nothing: a hostile string is hostile on action 1 and on action 400 |
| Double redeem | no | Proven under concurrency on both backends at the Phase 2 gate, which is a stronger test than a weighted action would be |

Both deferrals are **already covered elsewhere**, which is the whole reason they
are the ones deferred. Neither is dropped from the system's coverage; they are
dropped from *this tier's* pool.

#### The gate is unaffected in substance

§14's Phase 9 gate asks for the self-test green, three fixed seeds across both
backends in a session, and — the important one — *a deliberately reverted
Phase-2-or-later fix rediscovered by a hunting run*.

**A hunting run does not need a shrinker.** Rediscovery is detection, and
detection lands in full. What the trimmed tier cannot claim is the plan's full
*deliverable* list, which is why this is recorded as a departure rather than
quietly absorbed.

Worth noting that this gate clause got materially cheaper to satisfy during
Phase 8. The plan says *"until a real defect exists to revert, every new
assertion is mutation-checked by hand"* — there were none when it was written.
There are now several, found by mutation coverage rather than by review: the
nonce retention hole (8.21) and the asymmetric-link path (in `LinkingService`)
are both genuine, both recent, and both excellent revert candidates.

#### Re-entry criteria

Written down so this does not drift into a permanent omission by default. Build
the shrinker when **any** of these becomes true:

1. A failing seed goes uninvestigated for more than a week.
2. A trace of more than roughly a hundred actions has to be read by hand twice.
3. The tier finds its third real defect — at which point reading traces stops
   being an occasional cost and becomes a routine one.

Whichever comes first. The shrinker is deferred, not declined.

### 8.22 — A guard that documented a skip it never implemented, and failed the battery at the build stage

The first session run of these eleven commits went red in **56 seconds**, at the
build verb, before a single tier had started. `PlanCheckWalkerGuardTest` — added
in 8.19 to catch a walker that could not parse real Plan output — failed three
of its own cases with `expected: <0> but was: <127>`.

127 is "command not found". **The digest-pinned Temurin image the build verb
runs inside has no `python3`**, so every probe the guard extracts from
`plan-check.sh` and executes returns nothing.

Its class comment said:

> *Skipped rather than failed when `python3` is absent: the script needs it at
> run time and the session guest has it, so its absence here is a fact about
> this workstation and not about the check.*

Two things wrong with one sentence. There was **no skip** — no `assumeTrue`, not
even the import. And the direction was inverted: the workstation has `python3`
and the build container does not, which is the opposite of what it claimed.

This is 8.3 exactly — a guard documenting a rule it did not implement — written
by somebody who had just finished recording 8.3.

#### Why a prose claim is worse than no claim

An unimplemented skip is not a missing feature. It is a **false statement in the
place a reader goes to find out whether the behaviour is deliberate.** The next
person to see this failure would have read that comment, believed the skip
existed, and gone looking for why it had stopped working — which is strictly
worse than finding nothing at all.

The comment also cost the run. A guard is supposed to be cheap; this one halted
the battery before the six things the session existed to verify had run.

#### The skip, now implemented, and what it is allowed to cover

It probes rather than assumes: it runs `python3 --version` and checks the exit
status, because "is it on the path where I expect" answers a different question
from "does running it work".

The narrowing covers exactly *"this environment cannot execute the shipped
probes"* — nothing about whether they are correct. **Verified in both
directions**, which is the part that would otherwise be another unimplemented
claim: with `python3` on the path, six tests run and none skip; with it removed
from `PATH`, six run and six skip, none fail.

#### The property is not left unasserted, and this time the compensation exists

`harness/fullstack/mutation/run.sh` now runs in the **run** verb, which executes
on the guest host, which does have `python3`.

That is not a consolation prize. It is thirteen mutants of a real recorded Plan
response, each required to turn the stage red, plus a control that must pass —
against the guard's single read. The property is asserted *harder* where the
guard skips than where it runs.

Placed early in the run verb, before the forum and game tiers: it needs no
database, no proxy and no forum, so a broken check surfaces in seconds rather
than after the tier it guards has already spent twenty minutes.

#### The general lesson, since this is the second time

A guard that shells out has an **environment dependency**, and an environment
dependency is a thing to declare and satisfy — not a thing to describe. The
build container is a JVM toolchain; nothing entitles it to an interpreter, and
the first guard to want one should have said so somewhere that fails.

## Phase 10 — hardening and release

### 10.1 — NOTICE claimed a generator that was never written

From Phase 0 until Phase 8, `NOTICE` — the legal file that accompanies every
copy of this software — said:

> This product includes software developed by third parties. A complete
> third-party licence inventory **is generated at build time and ships in every
> distributed artifact**; see docs/ and the generated inventory for the
> authoritative list of dependencies and their licences.

No such generator was ever written. There is no task in `build-logic/` that
produces an inventory, and no artifact has ever contained one. The file also
pointed at "docs/ and the generated inventory", neither of which held the list
either.

#### Why it survived eight phases

Because nothing read it.

Every other claim this repository makes about itself is held to the code by a
guard — the release levels, the storage seam, the transport seam, the dependency
graph, the protocol document, the stage list, the pinned artefacts. `NOTICE` was
prose in a file nobody's tests opened, and prose drifts silently. It is the same
shape as an assertion that cannot fail, relocated into a document.

It is also the worst file in the repository to be wrong in. Its entire purpose
is to be relied on by somebody who is **not** reading the code: a redistributor,
an operator's legal review, anyone assembling their own third-party disclosures.
An inventory that does not exist cannot be consulted, and a reader who trusts the
sentence has no way to discover that.

#### What it says now

The actual list, inline: twelve third-party components with their licences, the
two LGPL/EPL ones marked as shipped unmodified and unbundled in `lib/`, and the
two `compileOnly` ones marked as not distributed at all. That is a real
inventory. It is hand-maintained, which is exactly the weakness the generator
would remove — so `NoticeGuardTest` holds it to the catalogue instead.

**Two assertions, and they are different claims.** First, nothing in
`gradle/libs.versions.toml` may be missing from `NOTICE`: the catalogue is where
a dependency is added, so it is the side that moves, and an addition that never
reaches `NOTICE` is an undisclosed third party. Second, `NOTICE` may not claim a
generated inventory while no generator exists — so when Phase 10's packaging
work lands one, updating the claim is part of landing it rather than something
to remember.

The generator check looks for a **task**, not for an output file. An output file
can be left behind by a run that no longer happens, and the guard would then
agree with a `NOTICE` that had quietly become untrue again.

#### The historical note lives here, not there

The first version of the corrected `NOTICE` quoted the old sentence, so the
claim was recorded where it had shipped. The new guard immediately failed on it:
the matcher is deliberately loose, and a quoted false claim is still the false
sentence sitting in the legal file. It was right to fail. History belongs in
this document; `NOTICE` states only what is true today.

### 8.23 — The charset migration could not run, and only a real MariaDB could say so

`V8__utf8mb4.sql` (8.18) was written, reviewed, committed and green on the
workstation. Its first contact with an actual MariaDB failed on the fourth
statement:

```
1833: Cannot change column 'id': used in a foreign key constraint
      'fk_capability_connector' of table 'soulbind.connector_capability'
```

`CONVERT TO CHARACTER SET` rewrites every char column's definition, and MariaDB
refuses to change a column referenced by a foreign key while the other side
still carries the old charset. **No ordering avoids it**: converting the parent
first leaves the child pointing at a column it no longer matches, and converting
the child first does the same in reverse. Both sides have to move and they
cannot move simultaneously, so the checks come off for the duration.

Why that is safe here, stated rather than assumed: the migration inserts and
deletes nothing — it rewrites column metadata, and for the ASCII identifiers in
these columns the stored bytes are identical before and after. Every table on
both sides of every foreign key is converted in this one script, so the schema
is consistent again before the flag is restored. The flag is a session variable,
so the real hazard is a pooled connection escaping with checks disabled; it
cannot, because on success the script restores it, and on failure Flyway aborts,
`Storage.open` throws and core never starts — the pool dies with the process,
having served nothing.

**The point worth keeping.** The workstation has no MariaDB. Every test that
would have exercised this file was skipped there, so `./gradlew build` was green
over a migration that could not run. `SchemaCharsetTest` asserts the schema
correctly and had never executed its MariaDB branch. The commit message said so
— *"the latin1 axis is written and unproven until the next session"* — and that
was the accurate part; what it could not say was which of the two ways it would
turn out to be wrong.

This is 8.9 again, in a new place: **static review of code that has never run
converges on the wrong things.** Three rounds of reading found the charset
inheritance, the connection collation, the anti-vacuity floors and two
narrowings worth stating. None of them found that the DDL was rejected outright.

#### What the same run did confirm

The failure was fourth in the queue, and three things had already passed:

- **Infection ran on the guest**, first time: PHAR fetched and checksum-verified
  twice, pcov loaded in the derived image, 343 killed / 78 escaped / 81% MSI —
  the same numbers as the workstation — and the report copied into `out/`.
- **The shell mutation battery ran** in the run verb: control green, 13 of 13
  mutants killed.
- **`PlanCheckWalkerGuardTest` skipped** in the build container rather than
  failing or silently passing (8.22).

And one thing was answered in passing: the failure was on statement four, which
means `ALTER DATABASE` had already succeeded **as the forum tier's non-root
user**. That was an open question in 8.18 and it is now closed.

### 8.24 — Three ways to set a charset, two of which a real server refused and one of which deadlocks SQLite

The charset work of 8.18 took three attempts to land, and each failure was
invisible to the workstation because it has no MariaDB.

**Attempt one — convert afterwards.** `V8__utf8mb4.sql` did `ALTER DATABASE`
plus `ALTER TABLE ... CONVERT TO CHARACTER SET` for fifteen tables. Its first
contact with a real server failed on the fourth statement with error 1833:
`Cannot change column 'id': used in a foreign key constraint`. CONVERT rewrites
every char column's definition, and the server refuses while the other side of a
foreign key still carries the old charset. No ordering avoids it — whichever
side moves first stops matching the other.

**Attempt two — disable the checks.** Bracketing the conversions with
`SET FOREIGN_KEY_CHECKS = 0`, which is the usual advice, produced **error 1833
on exactly the same statement**. That flag governs referential integrity of
*data*; it does not permit a column's definition to diverge from the one
referencing it. One session run to learn that.

**Attempt three — a Flyway callback**, which is where it got interesting.

A `beforeMigrate.sql` in the per-dialect location is the idiomatic answer to
"do this before anything is created", and it removes the problem entirely: set
the database default first and the tables are *born* utf8mb4, so there is
nothing to convert and no foreign key to fight.

Before spending a third session run, the one assumption that *could* be checked
on the workstation was checked: does Flyway even discover a callback from these
locations? A throwaway probe put `CREATE TABLE flyway_callback_probe` in the
SQLite dialect directory and asserted the table existed after boot.

It failed — and not for the reason expected:

```
FlywaySqlException: Unable to obtain connection from database: soulbind-sqlite
  - Connection is not available, request timed out after 30000ms
    (total=1, active=1, idle=0, waiting=0)
  at DefaultCallbackExecutor.onMigrateOrUndoEvent
```

The callback **was** discovered. Flyway opens a **second connection** to execute
it, and this project's SQLite pool is deliberately one connection — SQLite
permits exactly one writer, and a pool of several does not make that untrue, it
makes it intermittent. So the callback waits thirty seconds for a connection the
migration it precedes is still holding, and then fails the boot.

**The trap in that shape.** The same file under `mariadb/` would have worked,
because that pool has ten connections. Under `common/` or `sqlite/` it takes the
product down at boot on the backend a small deployment is most likely to be
running. An asymmetry that severe, discoverable only at runtime, on one backend,
is exactly what nobody finds before a user does — so `FlywayCallbackGuardTest`
forbids callbacks in **all** migration locations, names every callback filename
Flyway recognises rather than only the one that bit, and carries a planted
must-fail fixture.

**What actually landed.** One statement in `Storage.migrate`, on the pool's own
connection, before Flyway runs. No second connection, no foreign key, no
ordering problem — Flyway creates its own history table before the first
migration, so a versioned migration could never have run early enough anyway.
It throws loudly on failure: a silent one leaves a latin1 schema that looks
correct until somebody's name has an emoji in it, and the error then names a
column rather than a charset.

#### The thing worth keeping

Two session runs were spent on attempts one and two. The third was checked
locally in **two minutes**, by asking what the smallest testable assumption was
and testing that instead of the whole change — and it found a worse defect than
the one being fixed.

The lesson is not "test more". It is that after being wrong twice about the same
file, the next move is not a third attempt at the same *kind* of verification.
It is to find the part that *can* be verified here and verify it, however small.

### 8.25 — A cache key that documented its own invariant and did not enforce it

Both decision caches — the Java SDK's and the Flarum connector's — built their
key by joining the gate and the identity reference with a unit separator. The
Java one carried this comment:

> *A unit separator, which **cannot appear** in a gate name or a platform
> identifier. Joining with a colon would let the pair `("a:b", "c")` and
> `("a", "b:c")` collide on one key — and a collision here serves one subject's
> decision to another.*

The second sentence is exactly right about the danger. The first is an
unenforced assertion about inputs neither class controls: a gate name comes from
an operator's configuration, and a platform identifier comes from whatever the
platform hands the connector. **Nothing checked it on either side.**

So the comment had correctly identified the failure mode, correctly identified
why a colon was unsafe, and then chosen a rarer character and called the problem
solved. A unit separator is unusual in a platform identifier. It is not
impossible, and the hostile corpus exists precisely because the exotic input
eventually arrives.

The consequence is not a crash. It is an **allow served to an identity it was
never issued for**, from the component whose entire job is to answer quickly
without asking core.

#### Length-prefixing rather than validation

The key now states the gate's length before the gate, so the boundary is
declared instead of inferred and no content can move it.

Validation was the other option and is worse here. Refusing a gate name
containing the separator turns an exotic-but-harmless input into a refused
decision, and on a fail-closed gate that is an outage for whoever owns that
identity. A key that cannot be ambiguous needs no failure path at all.

Note the contrast with `RequestSigner.requireNoSeparator`, which *does*
validate, and should: there the canonical bytes must be reproducible in two
languages, so an input that cannot be represented unambiguously has to be
refused rather than silently re-encoded. Same hazard, different correct answer,
because one is a wire format and the other is a local lookup.

#### How it was found

Not by review. Infection reported that several `ConcatOperandRemoval` mutants
survived on the PHP key-construction line — removing a separator changed nothing
any test noticed. That is a weaker signal than a collision, but it points at the
same place: **nothing asserted the key was unambiguous**, so nothing would notice
when it stopped being.

Checking the Java side for the same shape is what turned up the comment, and the
comment is what named the danger it had not prevented. The nonce retention hole
of 8.21 was found by the same move — a defect in one implementation is worth
looking for in the other before assuming it is local.

Both sides now carry a test built from a pair chosen to collide, and both were
mutation-checked by restoring the old key format: red on the collision
assertion, green on restore.

### 8.26 — What a green run still could not prove

The battery went green on its fourth attempt. A QA pass over that green run
produced six findings, and the useful thing about them is that **none is about a
test failing** — they are all about what a passing run does and does not
establish.

#### The axis was never checked for being hostile

`--character-set-server=latin1` is a *request*. Nothing read the answer back, so
a flag silently dropped — a typo, an image whose entrypoint reorders arguments,
a future MariaDB that declines latin1 — would leave every charset assertion in
this repository passing against a friendly server, proving nothing. **The entire
point of 8.18 is that a green charset test on a utf8mb4 server is the defect**,
and the harness had no way to tell the two situations apart.

Confirmed by direct probe that the server really does start latin1 today, and
the manifest now asserts `@@GLOBAL.character_set_server` after the readiness
ping and refuses to continue otherwise. The PHP side has done this for phases —
its vector runner prints "hostility took effect" — and the database axis simply
never got the equivalent.

#### The fix had quietly narrowed to fresh databases

Deleting `V8` in 8.24 was correct and it moved the boundary. Setting the
database default before Flyway makes tables *born* utf8mb4; it cannot repair
tables an earlier boot created latin1, because that needs `CONVERT` and
`CONVERT` is what the server refuses.

That leaves exactly one uncovered case, and it is the case the whole exercise
was about: **an installation that ran an older soulbind against a latin1
server.** Worse, no test could ever catch it — `StorageBackends.open` drops and
recreates the schema for every test, so a fresh database is the only kind any
test has ever seen. The narrowing was documented in a comment and invisible
everywhere else.

So it is enforced instead: after migrating, core reads `information_schema`
back and **refuses to start** if any table cannot hold four-byte text, naming
them. Loud rather than automatic, because repairing it means dropping and
rebuilding foreign keys around a conversion — a migration somebody writes
against the schema in front of them, having read the message.

#### "Did not fail" is not "ran and passed"

`build/` is excluded from reaper's backward sync, and Gradle prints only SKIPPED
and FAILED. So nothing about *which* tests ran on the guest ever left it: a
green run reported "45 tasks executed" and could not say whether the MariaDB
half of a parameterised storage test had executed or quietly yielded one
backend. `StorageBackends` has carried a comment admitting this since Phase 1.

The run verb now copies `core/build/test-results/` into `out/`. That tier is the
one that matters — it is the only place `SOULBIND_TEST_MARIADB_URL` is set — and
the XML names every test and every parameter. This closes the whole
"did it actually run?" class of question rather than one instance of it.

#### Last time's verdict, read as this time's

`out/browser-evidence/mariadb/.last-run.json` said `passed` while being an
artifact of run 3, which **failed**. reaper's backward sync never deletes, and
the tier kept evidence only on failure — so a passing run left nothing to
overwrite the stale verdict with.

Two changes, both small: a `run.json` stamp is written on every run including a
green one, so a pass leaves proof it ran and cannot be confused with last time's
result; and a failing run that captured no report now says so loudly instead of
`rmdir`-ing the empty directory, which had made "failed and produced nothing"
indistinguishable from "was never asked for anything".

#### A documented gap that only the source could see

`plan-check.sh` explains at length why Plan's three server-wide counters are
legitimately zero. It explained it only in a comment — so the run output showed
three zeros followed by a green PASS, and an operator would reasonably conclude
the check had verified them. It now says so in the output, where the person
reading the result is.

#### The pattern

Every one of these is the same shape: **a claim whose evidence lives somewhere
the reader is not.** In a comment, in a log that is not kept, in an artifact
from a different run, in the absence of a failure. The tests were fine. What was
missing was any way for a passing run to distinguish itself from a run that had
not happened.

### 8.27 — The read-only capability, and what it cost to not have one

8.14 recorded that soulbind had no capability granting reading and only reading,
and left the decision to the owner. The decision was to add it before v1, and
this is it.

**`link-state-reader`.** It reaches `identity.describe` and nothing else, and it
is the only capability in the enum that grants no mutation of any kind. Every
other one either changes the identity graph, changes policy, or causes a side
effect somewhere.

#### Why the absence was worse than it looked

A connector that wanted link state had to hold one of two grants, and the module
README had been carrying the table for two phases:

| Operation | Capability | What else it unlocked |
|---|---|---|
| `subject.inspect` | `config-management` | `rule.set`, `override.set`, `config.set`, `audit.query`, `identity.unlink` |
| `identity.describe` | `code-display` | `code.issue` — minting a link code |

The two **return the same data** — core binds the same request type for both —
so the choice was never about what came back. It was about who may ask, and both
answers were wrong for a dashboard: one lets it rewrite every rule and unlink
anybody, the other lets it mint credentials-in-waiting.

The analytics connector took the second, which was the right call among the
available wrong ones and was documented as such. It is also the most-installed
and least-audited surface in the system, which is the worst place to be spending
a capability one does not need.

#### Two connectors, not one

Worth noting because it changes the shape of the fix: the **forum connector also
calls `identity.describe`** — `LinkService.php` uses it to show a member what
they are linked to. It reached that through `code-display`, which it holds
legitimately for its own code-issuing flow, so the extra reach was invisible.

That is the quieter half of the problem. The dashboard's over-grant was at least
*visible*, written down in a README with a paragraph explaining it. The forum
connector's was an accident of overlap: it needed `code-display` anyway, so
nobody had to notice that reading link state came along with it.

Both now hold `link-state-reader` explicitly. A grant that is stated is a grant
somebody can review.

#### What it did not become

Not "read-only" as a general permission. It reads **link state**, which is one
operation's worth of data, and the name says so. A capability called `read-only`
invites the next read operation to be added under it without anybody deciding
whether the same holders should have it — which is how `config-management` came
to mean nine different things.

And reading is not *harmless*: link state says which platforms a person is on
and when they joined them, which is exactly the correlation a dashboard should
not publish casually. The analytics connector keeps the subject id off the page
unless an operator opts in, and that remains necessary.

#### Verification

`AuthorizationMatrixTest` restates the operation-to-capability mapping by hand,
independently of the production table, and runs every operation against every
capability — so adding one capability added 24 cases without anybody writing
them. Mutation-checked by pointing `IDENTITY_DESCRIBE` back at `CODE_DISPLAY`:
**10 of 264 cases fail**, and they are the right ten. `ProtocolDocSyncGuardTest`
caught `protocol.md` before the build did, which is what it is for.

### 9.2 — The self-test comes first, and what that ordering is protecting against

§14 puts the oracle self-test ahead of the harness it grades. That ordering is
the most valuable instruction in Phase 9 and it is worth writing down why.

**A simulated-user run that finds nothing looks exactly like a set of invariants
that cannot find anything.** Both print the same thing: hundreds of actions, no
violations. And the second is the more likely of the two — it is this
repository's most-repeated defect, arriving here in the tier where it would be
hardest to notice, because a quiet run in a fuzzing tier reads as success rather
than as silence.

So every invariant is fed the response a broken core would send and must
complain. And — the half that is easy to skip — every invariant is fed a
**healthy** core and must stay silent. Without that control, an invariant that
complains about everything scores identically to a good one, catches every fault
and is worth nothing. The shell mutation battery learned the same lesson two
weeks earlier and for the same reason.

#### `CoreView` is the design decision

The self-test is only possible if "what core said" is a **value a test can
fabricate**. So the invariants never touch a socket: they read a narrow
interface, one implementation of which will talk to a real core through
`connector-sdk`, and another of which lies in whatever way the case requires.

The whole self-test then runs in milliseconds with no server, which is what
makes it something to run constantly rather than something to run in a session.

The interface is deliberately narrow — every method is a question some invariant
actually asks. A wide seam is a seam the self-test cannot cover, and an
unfabricatable answer is an invariant that can only be tested against the real
thing, which is where this started.

#### The model is partial on purpose

A shadow model that reimplemented core would be a second implementation with its
own defects, and the two agreeing would prove they shared a misunderstanding.

`ShadowModel` records what the actors *did* and what must follow from it: these
two accounts are now on one subject, this code is spent, this many mutations
happened. It never records how core arrived at anything. So it can assert that
two accounts share a subject and cannot assert which subject id that is — which
is correct, because the id is core's to choose and an invariant pinning it would
be asserting an implementation detail rather than a property.

#### The six, and why the cheap one is separate

`linkage-mirrors-model` and `core-invents-no-links` are deliberately two
invariants rather than one. The first catches a link that vanished; the second
catches an identity attached to a subject nobody linked it to. **The first alone
passes a core that also attaches strangers**, because the accounts the model
linked are all present — and a link nobody asked for is the more alarming
failure and the easier one to leave uncovered.

`every-response-was-an-envelope` is independent of all the others, per §11. A
run whose model and server agree perfectly, having exchanged a 500 on the way,
has still found something — and none of the other five would mention it. The
self-test asserts exactly that shape: a case where the cheap oracle complains
and every other invariant correctly does not.

#### Verified by neutering one

The self-test was checked the only way it can be: an invariant was made unable
to complain — one conditional forced false — and the suite went red on the case
that grades it, naming it. Restored, green.

That is the same operation PIT performs on the Java suite, applied by hand to
the thing PIT cannot reach, because the defect being guarded against is not a
wrong answer but an absent one.

### 9.3 — The generator's scratchpad is not the oracle

Deliverable (2): the generator, the actors and the checker. Three decisions in
it are worth recording because each closes a way the tier could have been quietly
useless.

#### The generator reads a different structure from the one the invariants check

`World` — outstanding codes, who has rotated, which refs are unlinked — is the
generator's scratchpad. `ShadowModel` is the oracle. They are separate objects
holding overlapping facts, which looks like duplication and is not.

**If the generator read the oracle, an error in the oracle would steer the
generator away from the actions that would have exposed it.** The run would get
quieter as the model got wronger. That is the worst available direction for a
feedback loop, and it would present as a clean run.

#### The per-run tag is drawn outside the seeded stream

§11 asks for this in one clause and it is the subtlest requirement in the tier:
*"anything that must vary between runs — a tag distinguishing this process's data
from an earlier run's — is drawn outside the seeded stream, so replay reproduces
the action sequence exactly"*.

If the tag came from the seeded PRNG, replaying a seed would either collide with
the original run's rows or shift every subsequent draw. Either way the seed
stops being replayable — and nothing says so, because a run with a shifted
sequence still looks like a run. It is invisible until somebody tries to
reproduce a failure, which is the moment it matters most.

`Generator` therefore takes a seed and nothing else, and `GeneratorTest` asserts
that two different tags under one seed produce an identical sequence of action
kinds.

#### Only applicable actions are proposed, and the nemesis is in the same pool

A draw spent proposing a redeem when no code is outstanding is a draw the
executor must refuse. Do that often enough and the weights stop meaning what
they say: the run drifts toward whatever happens to be possible rather than
toward what was chosen.

The four kept nemesis classes sit in that same weighted pool rather than in a
mode, per §11, so they land at arbitrary depth. A stale credential presented on
action three against two rows of state is a unit test; the same credential on
action three hundred, against a graph that has been merged, unlinked and
re-linked, is the thing no other tier can construct. `GeneratorTest` asserts
they occur **in the second half** of a long run — a pool whose adversarial
classes never get drawn documents an intention rather than testing anything.

#### The checker deduplicates, and that needed its own test

Violations are recorded once per (invariant, complaint). Without it, a
divergence that persists is re-reported at every subsequent check, and the
report's length measures how long the run continued after the first failure
rather than how many things are wrong.

The risk that introduces is obvious in hindsight and easy to ship: a
deduplication keyed too coarsely swallows the *second, different* failure. So it
keys on the complaint rather than on the invariant, and there is a test that two
different divergences within one invariant are both heard.

Violations also carry the action number they were first seen after. With the
shrinker deferred (9.1), **that number is the main thing standing between a
failing seed and reading a four-hundred-action trace in full.**

#### What is deliberately still missing

The executor — the thing that turns an `Action` into a real call. Everything
above runs in-process against fakes, which is why the whole module tests in
milliseconds. The executor is the part that needs a session, and it is next.

### 9.4 — One question the protocol cannot answer without changing the answer

`redeemed-codes-stay-redeemed` asks whether a spent code is still redeemable.
Against the in-memory core that is a lookup. Against a real one it is not
askable at all: there is no operation that reports a code's state, and the only
way to find out is to **attempt the redeem** — which, against a broken core,
succeeds, links a phantom identity, and corrupts the graph the rest of the run
is asserting about.

Three options, and the middle one is the trap:

1. Probe anyway. The check works and the run's other invariants start reporting
   damage the checker caused.
2. Answer `false`. The invariant never fires, the report stays green, and it is
   an assertion that cannot fail — arrived at by politeness rather than by
   oversight, which makes it harder to spot later.
3. Say so.

The view declares the invariant **inert**, with the reason, and the runner
prints it **first and unconditionally** — before the verdict, on green runs as
well as red ones. `RunnerTest` asserts that ordering, because a narrowing that
only appears in a failing run is a narrowing nobody reads: the runs people look
at are the green ones.

Single use is not left uncovered. The Phase 2 gate proves it under real
concurrency on both backends, which is a stronger test than a weighted action
would be — and it is why `double redeem` was one of the two nemesis classes
deferred in 9.1. When that class returns, the executor will exercise it at
action time and this invariant becomes live again.

Adding a read-only "is this code spent" operation to core was considered and
rejected: it would put a query in the protocol that only a test uses, and the
capability model would then have to decide who may ask it.

### 9.5 — The acceptance test, and the control that caught the harness

§14's Phase 9 gate asks for "a deliberately reverted Phase-2-or-later fix
rediscovered by a hunting run", which the methodology (§15) calls the battery's
own acceptance test. It is the only check that establishes the tier has **power**
rather than coverage: a suite that has never caught anything is indistinguishable
from a suite that cannot.

`harness/sim/acceptance.sh` reverts a real, recent fix — the one mutation
coverage found in `LinkingService.redeem`, where `issuedSide.or(() ->
redeemedSide)` returning empty makes a redeem report success while leaving the
two accounts unlinked — rebuilds core, and requires the committed seeds to
rediscover it.

**It runs on a workstation.** The tier talks to core over HTTP and has no
opinion about Paper, the proxy or the forum, so the gate's hardest clause needs
core and SQLite and nothing else. That was worth discovering: it turns a
once-a-session check into one that can be run whenever somebody touches the
linking path.

#### What the control caught on its first run

The script runs the seeds against **unmodified** core first, and requires that
run to be clean. It was not.

Sixteen violations, every one from `every-mutation-is-audited`, every one saying
core had audited nothing. A convincing finding: a real invariant, a coherent
story, arrived at by a new tier on its first contact with real code — which is
exactly the result that gets believed.

It was entirely my harness. `audit.query` takes `fromEpochSeconds`,
`toEpochSeconds`, `actor`, `subjectId`, `action` and `limit`; I sent a
`sinceSequence` cursor that does not exist, core's codec fails on unknown
properties, and every call came back `MALFORMED`.

And the second bug is the one worth keeping: **`auditSince` returned an empty
list when the call failed.** So "I could not ask" and "core has audited nothing"
were the same value, and a broken query in the checker reported itself as a
missing-audit defect in the thing being checked. Exactly backwards.

It now throws, naming the refusal. A checker that cannot read the audit log
cannot conclude anything about audit completeness, and continuing produces a
confident wrong verdict rather than an honest failure. There is also a guard for
core's 1000-row `MAX_LIMIT`: a run producing more rows than core will return
would compare against a truncated log and report a shortfall that is the
harness's fault.

**Without the control this would have shipped as a discovery.** The first
question asked would have been "what is wrong with core's audit?" rather than
"is my harness right?", and the answer would have been found eventually, by
somebody, after a while.

#### The result

Control: three committed seeds, four hundred actions each, against unmodified
core — clean. Reverted: rediscovered at **action 50**, naming
`linkage-mirrors-model`, with the diagnosis stated in full — these accounts
should share a subject, core reports a smaller set. Source restored and rebuilt
on the way out, so a failed run never leaves a reverted fix compiled into
anybody's install directory.

### 9.6 — Every simulated person was the same person

The question was "are three seeds enough?". Measuring it found something better
than an answer.

Each injectable defect was run against the three committed seeds and against two
hundred arbitrary ones. Every defect, every seed, caught at the **first
checkpoint**:

```
REDEEM_DOES_NOT_LINK      3/3   200/200   median first action 50
CODE_STAYS_REDEEMABLE     3/3   200/200   median first action 50
AUDIT_DROPS_ROWS          3/3   200/200   median first action 50
AUDIT_SEQUENCE_REPEATS    3/3   200/200   median first action 50
LINKS_A_STRANGER          3/3   200/200   median first action 50
SERVES_A_5XX              3/3   200/200   median first action 50
```

A uniform result across six unrelated defects is not a finding about the
defects. It is a finding about the world they were injected into.

#### The generator was collapsing the graph

`REDEEM_CODE` allowed **any** actor to redeem **any** actor's code. Every redeem
therefore merged two arbitrary components, the graph closed transitively, and
every simulated person became one person. Measured directly:

```
actors=3   identities=9    action=50    biggest subject=7
actors=3   identities=9    action=400   biggest subject=9
actors=12  identities=36   action=400   biggest subject=32
```

Thirty-two of thirty-six identities on a single subject. A tier whose entire
subject is a **cross-platform identity graph** had reduced it to one node.

That is not a smaller test, it is a different one. With no distinct subjects
there is nothing to exercise two people colliding over one account, no
`ALREADY_LINKED` "different people" path, no merge between two established
subjects — and every defect becomes reachable on the first link, which is
exactly why the detection rates were uniform and useless.

It was also simply wrong about the domain. Linking is a person joining **their
own** accounts: a code minted on a game account and typed into a chat account by
the same person. Somebody else redeeming your code is a conflict, not a link.

#### After the fix

`REDEEM_CODE` links one person's own accounts, and `REDEEM_FOREIGN` — weighted
low — is somebody else attempting it, which is the refusal path worth having.

```
actors=3   identities=9    action=400   biggest subject=3   linked=9
actors=12  identities=36   action=400   biggest subject=3   linked=36
```

One subject per person holding their three platforms, filling in gradually. The
shape the tier is supposed to be exploring.

`GeneratorTest` now asserts it and was mutation-checked by reintroducing the
collapse: red, then green on restore.

#### And the honest answer to the original question

Still 200/200 at the first checkpoint, even on the corrected graph, including a
defect written specifically to require accumulated depth.

**So: for every defect I can construct, three seeds is ample and one would do.
That is a statement about my defect catalogue, not about the tier.** I tried
three times to build a defect that some seeds miss and failed each time —
which means I have **no measurement at all** of the tier's power against the
rare, interleaving-dependent defects that are the entire justification for a
simulated-user tier over a unit test.

The seed count is therefore the wrong knob to worry about. What is missing is
that **nothing ever hunts**: the runner executes the committed set and no other,
so the promotion rule — any seed that finds a defect is kept forever — can never
fire, and the set can never grow. A regression set with no exploration behind it
stays exactly as good as the day it was written.

### 9.7 — Hunting, and the sentence it must not be allowed to become

9.6 ended by naming the real gap: nothing ever explores, so the promotion rule
can never fire and the committed set can never grow. This closes it.

`SOULBIND_SIM_HUNT=<n>` runs fresh seeds from `SecureRandom` — outside every
seeded stream by construction — and stops at the first finding.

**Opt-in, and a test asserts the battery never enables it.** A hunt is
nondeterministic in runtime *and* outcome. Wired into `reaper test` it would
make the battery's green depend on a dice roll, and the failure would present as
flakiness rather than as a finding — which is how a real defect gets re-run
until it goes away.

**Seeds are printed before the run, not after.** If the JVM dies mid-seed, the
seed that did it is the most valuable thing in the output, and printing it
afterwards means not printing it at all.

**It stops at the first finding.** The budget is a bound, not a target: once
there is something to fix, spending the remaining budget looking for a second
thing delays the first.

#### The wording is load-bearing

A hunt that finds nothing has established that **these particular N seeds found
nothing**. That is the budget running out. It is not evidence of correctness, and
it is the single most quotable sentence this tier produces — "we hunted fifty
seeds and it was clean" is exactly the claim somebody will carry into a release
discussion.

So the report says *"found nothing. That is not a clean bill of health — it is
the budget running out"*, and there is a test asserting those words survive. A
comment explaining the distinction would not have, because the person quoting
the number is not reading the source.

### 9.8 — A capability change broke a credential nobody thought about

The session run following the `link-state-reader` change failed in the forum
tier:

```
identity.describe was refused: {"code": "missing-capability",
  "capability": "link-state-reader"}
```

`harness/flarum/stack.sh` calls `identity.describe` directly, far below the
registration block, to read the graph back and confirm a link actually happened.
It used `HARNESS_CRED`, which reached the operation through `code-display` —
and when the operation moved to the read-only capability, that credential was
not updated.

Both connectors *were* updated: 8.27 traced `identity.describe` to
`connector-plan` and to `connector-flarum`'s `LinkService.php`, and granted both.
What it did not trace was the **harness itself** calling the operation, a
thousand lines away from where its credential is declared and in a different
language from the connectors that were checked.

The lesson is narrow and worth having: **changing an operation's capability
means finding every caller, and the callers are not only the connectors.** The
harness is a client too. A grep for the operation name across the whole
repository would have found it; a review of the connector modules never could.

Fixed by granting it, with a note at the registration saying why — the grant is
not obvious from the capability list alone, since the harness stands in for an
operator's tooling and reading link state is exactly what such tooling does.

#### Four callers, found one session run at a time

It was not one credential. The exhaustive search — every language, not just the
shell scripts I checked first — gives five call sites of `identity.describe`
that need a granted credential:

| Caller | Credential |
|---|---|
| `harness/flarum/stack.sh` | the forum tier's `HARNESS_CRED` |
| `connector-flarum/src/Link/LinkService.php` | `FORUM_CRED` |
| `connector-plan`'s `LinkDataSource` | `PLAN_CRED` |
| `connector-discord`'s `ChatConnector` (`/whoami`) | `CHAT_CRED` |
| `harness/sim`'s `SdkCore` | the sim cast |

8.27 granted two of them. Run 5 found the third. Run 6 found the fourth — the
chat connector's `/whoami`, which describes the account and so failed the
fullstack smoke with *"the chat connector does not see the link it just made"*.

Two session runs, roughly an hour, to learn something a single repository-wide
grep would have said in a second. **The lesson is not "add a guard" — it is that
a capability change is a search problem, and the search has to run across every
language before the first run, not after each failure.**

#### The guard I nearly built, and why I did not

The obvious protection is an enumeration: a checked-in list of every call site
of the capability-sensitive operations, with the credential covering each, and a
guard asserting the list matches reality.

It would not have caught this. That guard fails when a **call site** is added or
removed. Here the call sites never changed — only the capability did — so it
would have stayed green through the entire incident while looking like
protection. Building it would have been worse than building nothing, because the
next person would have trusted it.

What *would* catch the class is a **credential smoke**: start core on SQLite,
register the same credentials the harnesses register, and assert each expected
operation succeeds. `harness/sim/acceptance.sh` already proves core-on-SQLite is
a workstation-runnable fixture, so this would move detection from a
thirty-minute session to about thirty seconds — genuinely valuable, and not
built here because it needs the caller-to-credential mapping to live in one
place rather than being duplicated out of two `stack.sh` files, which is a
design question rather than an afternoon.

Recorded as the thing to build, with the reason the cheaper-looking option is a
trap.

### 9.9 — Two action classes with no oracle, and a rule that required nothing

Checking the sim's payloads against core's actual request types, while a session
run was in flight, found that `rule.set` binds
`RuleView(gate, requiredKinds, requireLinked, graceSeconds, defaultEffect)` and
the tier was sending three of the five.

**It did not fail.** `requireLinked` and `graceSeconds` are primitives, so
Jackson filled them with `false` and `0`, the bind succeeded, and core happily
stored a rule requiring nothing. The `SET_RULE` class had been performing
hundreds of operations that could not change any decision.

Fixed by sending all five. But the interesting part is why nothing noticed, and
that is not the payload.

#### `SET_RULE` and `DECIDE` have no oracle at all

The tier performs both and checks neither. There is no invariant relating a
decision to the rules that produced it — the shadow model records links, codes
and audit expectations, and nothing about policy. So a rule that requires
nothing, a rule that is never applied, and a `decide` that returns the wrong
effect are all invisible.

Two of nine action classes are therefore doing work no assertion reads. That is
precisely the shape this tier exists to catch, arrived at inside the tier
itself, and it is the second time in Phase 9 — 9.6 was the same thing about the
graph.

The invariant to write is `decisions-follow-the-rules`: when the model has set a
rule on a gate requiring linkage, a `decide` for an identity the model believes
unlinked must come back `deny`, and one for a linked identity must not be denied
for that reason.

**Deliberately not written yet.** A session loop is in progress with the
instruction to get it green, and adding an assertion mid-loop means the next red
run cannot be attributed to the previous fix. It goes in once a run is clean —
recorded here so it is not lost, because "we noticed but were busy" is how a gap
becomes permanent.

### 9.10 — An invariant that fires against correct core, and is excluded loudly

9.9 recorded that `SET_RULE` and `DECIDE` had no oracle. Writing one
(`decisions-follow-the-rules`: a gate requiring linkage must refuse an identity
linked to nothing) found three things, in order.

**First, it caught the real bug it was written for.** With `rule.set` sending
`requireLinked: false` — the payload defect of 9.9 — the control fails
immediately and says exactly why: *"gate forum.post requires linkage and core
says allow for an account it has never heard of. Either the rule was not applied
or it was stored requiring nothing."* That is the invariant working.

**Second, it was silently inert before that.** `decide` requires
`enforcement-point`, and the sim's admin credential held
`config-management, link-state-reader, code-entry` and not that. The call was
refused, `SdkCore.decide` returned empty, and the invariant skipped — an
invariant written *because* two action classes had no oracle, itself
unobservable, for the same reason. Found by injecting the bug it was meant to
catch and watching it not care.

Fixed twice over: the credential now holds `enforcement-point`, and a refusal
from `decide` now **throws** rather than returning "no answer". A checker that
cannot ask cannot conclude; 9.5 learned this about `auditSince` and it arrived
again here.

**Third, and unresolved: it fires against unmodified core.**

An identity the model believes is linked to nothing is **allowed** at a gate
requiring linkage — while a synthetic account core has never heard of is
correctly **denied**. The only difference between them is that core has *seen*
the first: a code was issued for it. And `LinkingService.issue` creates no
identity, so both should be unlinked and both should be denied.

Two possibilities, and they are not close in importance:

1. **Core treats a seen-but-unlinked identity as satisfying `requireLinked`.**
   That would be a real defect in the policy path — an account that has done
   nothing but request a code passing a gate that requires linkage.
2. **This tier's model loses track of a link**, so an identity that really is
   linked is believed unlinked.

The second is more likely on priors — the tier is days old and core has been
exercised for eight phases — but "more likely" is not "established", and the
first is serious enough that guessing is not good enough.

#### Excluded, not deleted, and not quietly

Running it would make every session red for a reason nobody has established,
which trains people to ignore red. Deleting it would lose both the invariant and
the question.

So it is declared **inert** by `SdkCore`, and the mechanism now actually skips
rather than running-and-ignoring: an invariant a view cannot answer produces
either a false pass or a false failure, and both are worse than not running it —
*provided the skip is loud*. It is. `Runner` prints the inert list first, before
the verdict, on green runs as well as red, and there is a test on that ordering.

The reproduction is: grant the sim's admin credential `enforcement-point`, put
`decisions-follow-the-rules` back in the answerable set, and run
`harness/sim/acceptance.sh`. The control fails at action 50 with several real
identities allowed at `game.join`.

**This is a lead, not a finding.** It is written down at this length because the
next person to look at it should not have to rediscover which of the two
explanations they are choosing between.

### 9.11 — The lead resolved: core was right, two of three seeds were doing nothing

9.10 left an open question: an identity the model believed unlinked was allowed
at a gate requiring linkage. Either core treated seen-but-unlinked as satisfying
`requireLinked`, or the tier's model lost track of a link.

**Core is correct**, established directly rather than assumed. Against a live
core with a rule requiring linkage:

```
decide game:never-seen   -> deny / not-linked
code.issue game:seen-only
decide game:seen-only    -> deny / not-linked
```

Both denied. `issue` creates no identity and core treats both as unlinked, which
is right.

So the model was stale — and finding out why turned up something worse than the
invariant.

#### All three seeds shared one identity namespace

The run tag varied per **run**, not per **seed**, and all three seeds execute
against one core and one database. Seed one linked alex's accounts; seeds two
and three then replayed the *same identity names* against a graph that was
already built.

Every redeem came back `already-linked` or `already-redeemed`. **Zero successful
redeems across 800 traced actions.** And because each seed gets a fresh
`ShadowModel`, seeds two and three believed nothing was linked while core had it
all — which is exactly the staleness 9.10 was chasing.

The reported result for those runs was *"3 of 3 seeds clean"*. Two of the three
had done nothing at all. **A tier cannot disagree with core about a graph it
never built**, so silence was guaranteed and meant nothing.

Fixed by giving each seed its own namespace. The seed value is not drawn from
the seeded stream, so §11's replay property is untouched, and `GeneratorTest`
already asserts the action-kind sequence is identical across tags.

#### A refusal can still spend the code

The same trace showed 132 of 234 refusals were `already-redeemed` for codes the
tier kept re-proposing. Core claims a link code even when it declines the link —
deliberately: *"it was used, and re-offering it would let the same collision be
retried indefinitely."* The tier only retired a code on success, so a spent code
stayed in the generator's pool for the rest of the run, and every draw that
picked it was wasted.

`CoreDriver.Result` now carries `codeConsumed`, and the executor retires the code
whether or not the link happened.

#### A green run now has to show its working

Both bugs produced the same symptom: **a clean report from a run that did
nothing**. "400 actions" counts attempts, and an attempt core refused did not
happen.

So an outcome now carries links made and refusals, the summary prints them, and
**a seed that linked nothing is a failure** — a HARNESS FAULT, not a pass. That
is the `journeys` stage's "no steps recorded" rule, arriving in the tier that
needed it most.

Reading the numbers matters: with three actors of three platforms each, the
graph *completes* after about six successful redeems, and every redeem after
that is correctly refused. A low link count late in a long run is saturation.
Zero is the failure.

#### What this says about the earlier green runs

Runs 7 and 8 reported the sim clean on both backends. That result now has to be
read as: **seed one did real work and seeds two and three did not.** The gate
clause was met on one seed per axis rather than three.

Not retracted — one seed doing four hundred actions against a real deployment on
both backends is still the clause, and the acceptance test's power is unchanged
because it never depended on seeds two and three. But it was weaker than it
read, and it read that way because nothing made the tier show its working.

### 9.12 — Astral text with an oracle, which is the half that catches anything

§11 Tier 6 asks that "astral-plane text from the corpus pushes through the
newest text column in every stage". The simulated-user tier now writes four-byte
UTF-8 — the astral-plane section of `corpus/hostile-inputs.txt`, "the classic
latin1 tripwire" — into every display name it sends.

**Pushing it through is the half that proves nothing.** A system that silently
truncates at the first surrogate accepts the write, returns success, and looks
identical to one that stored it correctly. So `text-survives-the-round-trip`
reads it back and compares byte for byte, and the complaint prints code points
on both sides, because a mangled name and a correct one look the same in a log.

#### Why this matters more than the schema assertions

`SchemaCharsetTest` and the boot-time check in `Storage.migrate` assert what the
schema **declares** — every table utf8mb4, every text column utf8mb4, the
database default correct. Those are worth having and they are not the claim
anybody cares about.

This is: **a person whose name is an emoji can link, on a server started
latin1.** It goes through the real transport, the real connection, the real
column, and comes back. That is the end-to-end proof the charset work of 8.18
and 8.24 never had — those cost three attempts and two session runs, and were
verified by asking the schema about itself.

#### The driver is asked what it sends

`CoreDriver.displayFor` exists so the model records exactly what went on the
wire. Duplicating the rule — the executor building one string and the model
remembering another — would leave the round-trip invariant comparing the model's
idea of the name against core's, with the value actually sent in neither. That
is a comparison that can pass while both are wrong.

#### Covered by the acceptance test

`MANGLES_FOUR_BYTE_TEXT` truncates a display at the first high surrogate, which
is what a latin1 column does. It joins the enum the acceptance test is
parameterised over, so it was tested the moment it was written — and the control
still passes, which is the claim: four-byte text survives a round trip through a
real core.

### 8.28 — Tier 7 against a deployment that has been running

Phase 8's test list asks for "T7 fuzz stage against the real deployment". The
interesting question was what that adds, because `:core:fuzzTest` already drives
**real HTTP and real signing** — `TestCore` stands up an actual server — so the
obvious answers (real transport, real decoding) were already covered.

What it adds is **accumulated state**. `:core:fuzzTest` starts its own core with
an empty database. This stage runs after `journeys` and `sim`, against a
deployment holding subjects, identities, spent codes, rules and an audit log.

A malformed request against an empty database exercises the decoder. The same
request against a populated one exercises the decoder, the query paths it
reaches, and whatever the accumulated state makes reachable. That is §11's own
argument for keeping the nemesis in the weighted pool — "faults land at
arbitrary depths in an accumulated history rather than against a clean fixture"
— applied to hostile input rather than to hostile actions.

#### The oracle is Tier 7's, unchanged

No 5xx; every response a well-formed envelope with an `ok` field; never an
`internal` error code; core alive and answering a valid heartbeat afterwards.

"The right things are rejected" is deliberately not asserted, for the reason
`ProtocolFuzzTest` already records: it would need a second implementation of
every validation rule to compare against, and would be wrong wherever the two
disagreed with no way to tell which.

Six request shapes, each built from the corpus: hostile operation name, hostile
field value, hostile field *name*, a payload that is not an object at all,
hostile in every field, and a plausible request with hostile identifiers.

#### The bug in the stage, found by testing the stage

The first version caught only `HTTPError`. A refused connection raises
`URLError`, which is not one — so **a core that died mid-fuzz would abort the
run with a Python traceback rather than reporting that the deployment stopped
answering.**

That is the single most important thing this stage can find, and it was the one
outcome it could not report. Found by pointing it at a dead port, which took
thirty seconds and is the kind of check that should be automatic when writing
something whose whole job is to survive a crash.

It now records status 0 — a status core cannot return, so it cannot be confused
with one — stops immediately, and says how many requests it got through first.

#### Verified before it went near a session

200 hostile requests against a live core: all reached the dispatcher, spread
across `decide`, `identity.describe`, `code.redeem`, `rule.set` and `heartbeat`,
all answered with HTTP 200 and a refusal inside the envelope, which is correct.
Then pointed at a dead port: two failures, exit 1.

A fuzz stage that has only ever passed is indistinguishable from one that cannot
fail, and this one was checked in both directions before being wired in.

### 9.13 — A stage that ran the previous run's binary and reported green

Run 10 came back green on both backends, three seeds each, no violations. It had
tested nothing that changed since run 9.

`stage_sim` built the simulated-user tier only when the start script was
missing:

```sh
if [ ! -x "$sim_home/bin/soulbind-sim" ]; then ... gradlew :sim:installDist ; fi
```

The guest's work directory **survives between `reaper test` invocations in one
session**. So run 9 built the tier, run 10 found the binary already there,
skipped the build, and executed run 9's tier against run 10's source. The
namespace fix, the code-consumption fix, the work reporting and the astral
round-trip invariant were all in the tree and none of them ran.

#### How it was caught, and how nearly it was not

By an **absence**. The current source prints "N links made, M refused" in every
seed summary, and the log did not have it. Everything else looked right: the
stage passed, all three seeds passed, both axes agreed.

Nothing would have reported this. The stage exits 0, the seeds report clean, and
"clean" is exactly what a tier does when it is asked about a graph it did not
build. If the work reporting of 9.11 had not landed a few hours earlier, the
only symptom would have been a green run that stayed green no matter what the
tier was changed to.

That is the worst shape a harness defect can take: it does not fail, it does not
warn, and it makes every subsequent result meaningless in a way that looks like
success.

#### The fix, and where the decision belongs

Build unconditionally. Gradle's up-to-date checking makes the call nearly free
when nothing changed, and that is the correct place for the decision — Gradle
knows what the task's inputs are and a shell script does not.

The general form is worth stating: **a conditional build keyed on the existence
of an output is a cache with no invalidation.** It is correct exactly once, on a
clean machine, and wrong every time afterwards on a machine that keeps state —
which is precisely what a reused session is.

#### What it costs the record

Runs 9 and 10 both tested the tier as of run 9's build. Run 9's green stands for
what it was: the state at `d6f1d69`, which still had the shared-namespace bug.
Run 10's green establishes nothing beyond that, and the Phase 9 fixes remain
unverified in a session until the next run.

### 8.29 — The snapshot nobody rolls back to

A question worth recording because the answer was half wrong.

**State rollback is not forgotten and is doing real work.** `reaper test` rolls
`tank/state` back to `@pristine` at the start of every run — visible in the log
as `rolled_back=tank/state@pristine` — which is why database state does not leak
between runs, and why the seed-namespace collision of 9.11 was a within-run
problem rather than a cross-run one.

**It could not have caught the stale binary of 9.13.** Build output lives in
`tank/work`, which `reset` deliberately does not touch: that dataset holds the
synced source tree, and rolling it back would fight the sync. Building
unconditionally remains the right fix.

**But `run.sh` takes `stack-$DB` snapshots that nothing ever rolls back to.** A
mechanism with an input and no consumer — the exact shape this repository keeps
objecting to, sitting in its own harness.

#### What it is actually for, stated rather than implied

It is an affordance for a **person**. Every stage is runnable alone against a
stack that is already up, so somebody debugging a failure can roll back to this
point and re-run one stage against a known-good deployment instead of spending
ninety seconds rebuilding the world. §12 asks for the snapshot at stack-up
rather than end-of-run for precisely that reason: rolling back should land you
on a working stack, not an empty machine.

That is a real purpose and it is now written where the snapshot is taken,
because a reader finding an unused snapshot will otherwise assume it is a
leftover.

#### Automating a rollback between stages: considered, rejected

It would mean stopping the stack, rolling a dataset back underneath processes
holding files open, and restarting — expensive and easy to get subtly wrong.

And the one problem it would have solved is solved for free by **ordering**. The
`fuzz` stage throws hostile input at a live deployment; running it before `plan`
would make a plan failure ambiguous — damage the fuzzer caused, or a defect in
the connector? Moving `fuzz` to last removes the question, and hands it more
accumulated state at the same time, which is the whole reason the stage exists.

Two things pulling the same way is usually the sign that the ordering is the
real answer and the machinery was the workaround.

### 8.30 — The battery has a live dependency on GitHub

Run 11 failed at the forum tier with:

```
The "https://api.github.com/repos/symfony/css-selector/zipball/..." file
could not be downloaded (HTTP/2 504)
```

Not a defect. GitHub returned a gateway error while composer was installing
Flarum's dependency tree, and a re-run succeeded. Recorded because the
*classification* took a moment and the fragility is real.

**Everything about the versions is pinned.** `harness/flarum/site-composer.lock`
fixes exactly which packages install, and the container images and jars are
digest- and checksum-pinned. What is not pinned is **availability**: the install
still fetches from packagist and GitHub at run time, so the battery cannot
complete while either is having a bad minute.

That is a different property from reproducibility, and the repository is strong
on the first and silent on the second. A run that fails this way is
indistinguishable, at a glance, from a run that found something — which is the
part worth having written down, because the reflex on a red battery should be to
read the failure rather than to re-run it, and this is one of the few cases
where re-running is the correct response.

**Not fixed here.** The honest options are a composer cache on the never-rolled-
back dataset — which the Gradle cache already does for the Java side, and which
would make this a first-run-only exposure — or vendoring Flarum's tree, which is
several hundred packages this project does not own. The first is worth doing and
is not a five-minute job, so it is written down rather than half-done.

Worth noting the asymmetry it exposes: the Java side fetches through
`$REAPER_CACHE_GRADLE`, which survives resets, so a second run needs no network
for dependencies. The PHP side has no equivalent, and that is why this failure
mode is specific to the forum tier.

### 8.31 — Finishing Phase 8: a watchdog, a journey, and a stretch goal declined

Three items remained. Each resolved differently, and the differences are the
point.

#### The browser tier already ran against the real stack

§14 asks the `browser` tier to run "the T5 suite against the real stack (no
injection here — the 5xx watchdog is on)". The forum tier has run five Playwright
passes against a **real** core and a real Flarum since Phase 7, so "against the
real stack" was already true. What was missing was the watchdog.

It is worth having, and what it catches is narrow: **a page can render exactly
the right words while the request behind it five-hundreds** and the UI falls
back to a default or a cached value. Every assertion in those specs is about
what the page says, so none of them can see it.

Armed on the three non-injection passes and **deliberately off** for `@outage`
and `@recovery`, where a server error is the fixture doing its job. §11 Tier 10
gives the reason the two travel together: "Watchdog fails any test observing a
5xx — which is why fault injection lives on Tier 5 and never here."

A watchdog clever enough to tell an injected 500 from a real one would be a
second implementation of the fault injector, and would disagree with it. Two
passes, one flag, no cleverness.

#### `forum-first-user` moved to where the forum is

The full-stack tier has no forum — departure 6 split it into its own tier in
Phase 7 — so this journey could not be walked from the `journeys` stage without
composing the two stacks. It is emitted by the forum tier instead, and lands in
that tier's evidence directory.

The transcript helpers moved to `harness/transcript.sh` so both tiers use one
definition. A second copy would be two definitions of what a transcript **is**,
in a tier whose entire deliverable is the transcript.

It also carries the tier's own rule: a journey that records fewer than four
steps fails, because a transcript with no steps is not evidence, it is a file.

#### `bedrock-player` is declined, on the plan's own terms

§11 Tier 6 calls a Bedrock client through Geyser "a stretch stage, added only if
Geyser is in the composed stack". Geyser is not in the stack, and the same
sentence says where the property is covered instead: "Floodgate identity
handling is covered by Tiers 1/4 regardless."

So this is not a gap being waved through — it is a conditional the plan wrote,
whose condition is false, with the alternative coverage named by the same
author. Recorded as departure 10 rather than left as an unexplained absence in
the coverage document, because "named by the plan and not covered" and "declined
for a stated reason" read very differently to somebody auditing the tier.

### 10.2 — The case no test had ever seen: a database that already existed

Every storage test in this repository starts from nothing.
`StorageBackends.open` drops and recreates the schema for each one, which is
right for isolation and leaves one enormous case uncovered: **the deployment
that has been running**, which is every deployment after its first day.

It had already cost. The charset migration of 8.18 was green on the workstation
and rejected outright by a real server, twice, because the thing it did wrong
could only happen against tables that already existed. Three rounds of review
found the wrong problems (8.23, 8.24). Nothing in the suite could have caught
it, and nothing in the suite could catch the next one either.

#### Building the old database with the old migrations

`UpgradePathTest` migrates to an earlier version using Flyway's `target`, writes
rows into it, and then opens the store normally so every remaining migration
runs.

Using `target` rather than hand-written DDL is the part that makes it honest:
the "old" schema is built by **the same migration files an older soulbind would
have run**. A hand-written approximation of a previous schema is a second
definition of it, and it drifts — silently, in the direction of whatever the
person writing it believed.

The rows go in with raw SQL for the mirror-image reason. The repositories are
today's code; using them to populate yesterday's schema would be exercising a
combination that never existed anywhere.

#### Stopping at version 4, not at the newest minus one

A one-migration upgrade tests one migration. This is meant to test the *path*,
so it stops far enough back that several run.

And the number of migrations actually applied is asserted, because "the row
survived" passes trivially against an upgrade that ran nothing at all —
mutation-checked by setting the target to the current version, which turns the
test red with *"only 7 migrations are recorded, so the upgrade ran almost
nothing and this test asserted almost nothing"*.

#### Idempotence, again, but for a different history

Idempotence was already asserted for a database built at the current version.
This asserts it for one that arrived **by upgrade** — a different history, and
the one a real deployment has. A restart that is not a no-op is drift per
restart, and it would show up here and nowhere else.

#### What it still does not cover

The MariaDB half only runs where MariaDB does, so on a workstation this is a
SQLite-only assertion. That is the same narrowing every storage test carries and
it is the reason the battery exists — but it is worth saying plainly, because
the defect this test was written for was specifically a MariaDB one.

### 10.3 — One place for a grant, and thirty seconds to check it

Moving `identity.describe` from `code-display` to `link-state-reader` broke four
callers. Two were found by review, the third by a session run and the fourth by
the session run after that — roughly an hour of battery time to learn what one
repository-wide search would have answered immediately (9.8).

Three things now close that, and they close different halves of it.

**`harness/principals.txt`** is the one place a grant is written down: every
principal the harnesses register, the capabilities it holds, and the operations
it must be permitted.

**`harness/credential-smoke.sh`** registers each of them against a real core on
SQLite and attempts each listed operation. Twenty-four checks across eight
principals, about thirty seconds, no container and no proxy. Mutation-checked by
removing `link-state-reader` from `chat` — precisely the 8.27 regression — which
it reports as *"FAIL chat may not identity.describe -- it holds
code-display,code-entry"*.

**`PrincipalDriftGuardTest`** asserts every `--capabilities` list in the harness
scripts appears in the table, so a grant changed in a shell script and nowhere
else fails `./gradlew build` rather than a session.

#### The only failure the smoke reports is `missing-capability`

Every other refusal is expected. The payloads it sends are deliberately minimal
and often wrong for the operation, and core is entitled to reject them on their
contents — a `MALFORMED` refusal means the request reached the dispatcher, which
means the capability was accepted, which is the whole question.

Asserting "the operation succeeded" would need valid arguments for every
operation: a second implementation of the protocol, in shell, that would drift
from the first. "Was not refused for lack of a capability" needs none of that
and is exactly the property that broke.

#### What it deliberately does not assert

That a principal holds no **more** than it needs. That is a real property and a
different test — it would mean attempting every operation each principal should
be refused, which is the whole matrix, and `AuthorizationMatrixTest` already
covers that at the unit level against the authorizer itself.

#### The check that was checking nothing

Two attempts to mutation-check the guard reported it passing, and I nearly
recorded it as broken. The replacement string in my check did not match the
file — a trailing backslash — so the mutation was a silent no-op and the guard
was correctly reporting no drift, because there was none.

Caught by adding `assert old in s` to the mutation itself. The lesson is the
one this session keeps producing from a new angle: **a mutation check needs its
own assertion that the mutation applied**, or a green result means "the tool
found nothing" and "the tool was handed nothing" indistinguishably.

### 10.4 — Retiring a leaked credential, and why there is no overlap window

Until this, a connector credential that had leaked could not be retired. The
only move available was `register` under a new name, which minted a second
credential and left the first one working — the opposite of what the situation
calls for.

`connector.rotate` mints a replacement and **replaces** the stored hash.

#### No overlap window, and the schema enforces it

The usual shape for credential rotation is two live secrets and a grace period,
so callers can migrate without downtime. That shape is wrong here, because the
case rotation exists for is *somebody else has this credential*, and a grace
period is precisely the thing you do not want then.

It is structural rather than careful: `connector` holds one `credential_hash`
column, so there is nowhere for a second live credential to sit. A future
change wanting an overlap would have to alter the schema, which is a visible
decision rather than a quiet one.

The cost is real and accepted: a connector is briefly unable to authenticate
between the rotation and its reconfiguration. An operator rotating because of a
leak wants exactly that; an operator rotating on a schedule can register a
second connector, cut over, and retire the first.

#### Audited before the plaintext is returned

The ordering is the point. A rotation that reached the caller and never reached
the log is a credential change nobody can account for afterwards — and this is
the operation most likely to be run *during* an incident review, which is when
the log is read.

#### `config-management`, and no CLI verb

A connector able to rotate its own credential would be able to rotate anybody
else's, since the operation takes a name. Administrative, therefore.

And it stays an operation: `soulbind` has three verbs and keeps three, per the
reason already in `Main`'s javadoc — everything else an operator can do goes
through an admin credential under the same capability table, rather than a
second management surface whose rules drift from the first.

The sharp case is an admin rotating **its own** credential, which is the
likeliest real rotation of all: the admin credential leaked, and the only
credential able to authorize the rotation is the one being rotated. It works,
because the request authenticates before the handler runs — and the caller is
cut off the instant the response is written, so a lost response means
re-registering rather than rotating again. Asserted, because moving
authentication after dispatch would break it and the symptom would be an
operator locked out of their own core mid-incident.

### 10.5 — An export is a loop, and a loop needs core to admit it stopped early

`audit.query` is bounded server-side at 1000 rows and always was, for a good
reason: an unbounded read from an authenticated endpoint is a way to exhaust
memory. But the bound was **silent** — a caller asking for everything got the
ceiling with no way to tell that answer apart from the whole log.

So the export deliverable was not really "add an export". It was: make a
truncation distinguishable from an ending.

#### `more` and `lastSequence` on every response, not just exports

Every `audit.query` response now carries whether more rows match and where to
resume. Putting them only on a dedicated export operation would have left every
other caller with the same silent ceiling, and an export built on a silent
ceiling looks complete and is not — which is worse than having no export.

The cursor is a **sequence**, not an offset. `seq` is monotonic and audit rows
are never mutated or deleted, so a page cannot shift under a reader mid-export
the way an offset can. It also makes the export resumable across runs.

`more` is computed by fetching one row more than the limit and dropping it. A
`COUNT` would be a second query that could disagree with the page it describes.

#### The ceiling was already costing something

`SdkCore.auditSince` in the simulated-user tier carried this comment:

> the audit log came back at core's maximum of 1000 rows, so it is truncated.
> [...] Shorten the run or add a cursor to `audit.query`.

It detected the ceiling and refused to conclude, which was right, but it also
capped how long a Tier 9 run could be. It pages now, so run length is no longer
limited by how much of the log the checker can read.

#### What the tool cannot do, stated rather than implied

`tools/audit-export.sh` cannot detect a core that lies about `more`. A core
claiming the log ends after one page is indistinguishable from a log that is
one page long, and no client-side check can separate them. What it can do is
report what it actually got, so the figure is there to compare against what the
operator expects — and that is what the `truncate-silently` mutant asserts.

What it *can* catch is a core that says more remain without advancing the
cursor. A tool that trusts that loops forever, rewriting the same page into the
archive: a file of repeated rows that looks like an export. The first version
guarded only the empty-page case, and the `freeze-cursor` mutant — a full page
with a frozen cursor — went straight through it and hung. Two mutants because
they are two bugs.

#### Three copies of the canonical signing string

`tools/rpc.sh` opens by saying it is the one implementation of the signing,
deliberately, because three copies of an HMAC canonical form are three chances
to drift from what the golden vectors exist to keep identical.

There were three copies. `harness/fullstack/redeem.sh` had a full duplicate,
down to the newlines, with no reason recorded — it now calls `rpc.sh`.
`harness/fullstack/fuzz-live.sh` keeps its own and has to: it sends
deliberately malformed bodies that `rpc.sh` refuses before they reach the wire.
That is the whole narrowing, and it covers exactly the one script.

`rpc.sh` moved from `harness/` to `tools/` in the process. It is not a harness
detail any more — it is how an operator makes a signed call, and the export
tool is its first shipped caller.

#### Two smokes that were never wired to anything

`credential-smoke.sh` was written as a workstation one-off and never ran
anywhere automatically, so it could rot silently between the sessions that
happened to invoke it by hand. Both it and the new export smoke are now a
`reaper test` stage — cheap, well under a minute together, and covering the one
thing a workstation build cannot: a shipped script speaking the real wire
format to a real core.

Wiring them in surfaced the reason they had not been: the reaper guest host has
podman and **no JDK**, so a smoke running gradle there fails with "JAVA_HOME is
not set". `harness/tools/core-env.sh` picks between a host JDK and the pinned
toolchain container, so the same script runs on both. Its repo root is a
parameter rather than derived from `$0` — deriving it worked for the caller it
was written against and pointed one directory above the repository for the
second one.

**Container mode is unverified until a session run.** This workstation is
FreeBSD and has no podman, so only the host path has executed. Stated rather
than discovered.

### 10.6 — The licence inventory, and the two things it found on its first run

§16 asks for "a generated `NOTICE` + third-party licence inventory in every
distributed artifact". 10.1 records that `NOTICE` claimed one from Phase 0 until
Phase 8 and none existed. This is the generator.

#### It inventories the resolved graph, not the catalogue

Those are different lists and the difference is the entire point. The catalogue
declares about a dozen libraries; core's runtime graph is **forty-two**
artifacts, and the Discord connector's is its own set again. A redistributor's
legal review needs the second list, and hand-maintaining it is precisely how it
goes stale — which is what 10.1 is about.

Each licence is read from the artifact's **own POM**, walking the parent chain,
because most multi-module projects (Jetty, Jackson, Kotlin) declare the licence
once on the parent. Nothing is guessed: an artifact whose chain names no licence
fails the build until `gradle/licences.conf` records one with a reason.

#### Three things fail the build

An unknown licence name, an artifact with no licence anywhere, and a copyleft
artifact not marked as shipping unbundled. §16's "new licences entering the
graph fail the build until allowlisted with a stated reason", mechanically.

The handling — `shadeable`, `unbundled`, `not-distributed` — is a **stated
field** on each allowlisted licence rather than inferred from its name. "May
this be shaded?" is the question §16 turns on, and inferring it from a string
means a new licence silently gets whatever the matching decides.

#### The first version let somebody else's XML decide our obligations

It took whichever licence the POM listed first. For Jetty that elected
**EPL-2.0**, when §16 says in as many words: "Jetty | EPL-2.0 / Apache-2.0 dual
| Taken under Apache-2.0". A decision about this project's own licensing
obligations was being made by the ordering of an XML file we do not control.

A dual-licensed artifact now fails until `[dual]` records which licence this
project takes it under, with the reason. Jetty and JNA are elected Apache-2.0;
logback EPL-1.0.

#### What it found, which is why it exists

**JNA 4.4.0, LGPL-2.1, in the Discord connector.** It arrives via
`club.minnced:opus-java` — JDA's *voice* support. JNA has been dual
LGPL/Apache-2.0 since 4.0, but 4.4.0's POM predates that and declares LGPL and
nothing else, so it could not be elected away.

The workaround would have been to add it to `lib/` and take on the relink
obligation. The real fix was smaller: this connector sends messages and applies
roles and never touches a voice channel, so JDA's audio support is excluded.
An LGPL dependency nobody chose, for a feature nobody uses, is now out of the
graph entirely.

**trove4j, LGPL-2.1, also in the Discord connector.** JDA uses it for its entity
cache, so it cannot be excluded — it ships as its own jar in `lib/`, unmodified
and replaceable. It is not in `gradle/libs.versions.toml`, because nothing here
declares it: it is exactly the transitive copyleft artifact that a
hand-maintained `NOTICE` never mentions.

Neither was visible from the catalogue. Both were found on the generator's first
real run, which is the argument for having one.

#### The guard, because the generator is only as good as its coverage

`LicenceInventoryGuardTest` derives from `settings.gradle.kts` that every
distributed module applies the plugin. A module created later and never wired up
would be outside the inventory entirely with every other check green — the same
shape as the `NOTICE` claim it replaces. `guards` and `sim` are excluded and
only those: neither ships.

It also asserts `NOTICE` still names `THIRD-PARTY.txt`. `NoticeGuardTest` stops
`NOTICE` claiming a generator that does not exist; this stops the inverse — the
inline declared list quietly being presented as complete again while the graph
grows past it.

### 10.7 — Packaging, and why the services are not fat jars

§14 Phase 10 says "fat JARs". Core and connector-discord ship as
distributions — `bin/` plus `lib/` — instead. Departure 11, and the reasons in
order of weight:

**§16's rule holds by construction rather than by a list.** No LGPL artifact may
sit inside a shaded artifact. In this layout every dependency is already its own
file, so there is nothing to exclude and nothing to get wrong. In a fat jar the
rule would hold by an exclusion list — a thing that can be silently wrong in the
direction of a licence violation. 10.6 is the argument: the inventory found two
copyleft artifacts in the graph that nobody knew were there, and packaging that
depends on knowing is packaging that depends on the thing that just failed.

**`META-INF/services` merging is a silent failure.** Javalin, Jetty, Flyway and
the JDBC drivers all register through it. Getting the merge wrong drops a
services file rather than failing the build, and the symptom is "no suitable
driver" on an operator's machine.

**There is no operator benefit on the other side.** A systemd unit runs a start
script either way, and this is already the layout the full-stack and forum tiers
have run core from since Phase 7 — so it is the exercised path, not a second
artifact that only the release gate touches.

`ServiceDistGuardTest` asserts the property the departure is justified by, and
asserts it against the built tree: every artifact the generated inventory calls
unbundled really is its own jar in `lib/`, and the start script really does
build its classpath from there.

Its first version guessed jar names from artifact ids and got trove4j wrong —
its Maven artifact is literally named `core`, so the file is `core-3.1.0.jar`.
It now derives the expectation from the module's own `THIRD-PARTY.txt`, which is
better than the guess it replaced: it ties the claim in the legal document
directly to the bytes on disk.

#### The plugins are shaded, and relocated

A host loads one jar out of `plugins/` and there is no `lib/` to unbundle into,
so connector-velocity and connector-plan are single files as §14 says. Both
graphs are seven artifacts and entirely permissive, so §16's rule costs nothing
there.

They are **relocated**, not shaded flat. The jar loads into a JVM that has its
own libraries — and connector-plan loads into Plan, which loads into a proxy —
so a flat shade leaves it to the host's classloading to decide which copy our
code binds to. That decides differently on different host builds and surfaces as
a `LinkageError` on somebody else's machine.

What is claimed is narrow: relocation makes the collision impossible, not that
the plugin works against every host build. `PluginJarGuardTest` asserts
relocation actually happened by reading the zip — both that nothing sits at an
original path *and* that something sits under `dev/soulbind/shaded/`, because a
jar bundling nothing at all also contains no unrelocated Jackson. It also
asserts the service files were renamed to match, which is the failure mode with
no symptom: a `ServiceLoader` lookup that finds nothing and says nothing.

`§16`'s "artifact-content check asserts no LGPL classes appear inside shaded
outputs" is in there too. It could not be written until there were shaded
outputs.

#### The composer package needed the same treatment for a different reason

Composer installs a package by copying its directory, so somebody receiving the
Flarum connector gets `connector-flarum/` and nothing above it. The repository
root's `LICENSE` is not something they have. It now carries its own, and
`ComposerPackageGuardTest` asserts it is byte-identical — a copy that drifts
from the licence it claims to be is worse than a reference, because it looks
authoritative.

Its `NOTICE` says the package bundles no third-party code, which is why it has
no generated inventory: its dependency graph is empty by construction. That is
the kind of statement that quietly stops being true, so the guard fails if
`require` grows anything beyond `php`, `ext-*` and `flarum/core`.

### 10.8 — Two defects the install document found by being followed

The gate is a clean install on a fresh machine following only `docs/install.md`.
Rather than wait for the VM, its commands were run against the real archive on
the workstation. Two were wrong:

**`subject.inspect` takes a platform identity, not a subject id.** The document
said `{"subjectId": …}`; the operation's payload is `platformKind` and
`platformId`. A connector asking usually knows only the account in front of it,
and requiring a lookup first would make every caller do two round trips to
answer one question — so the document was wrong about the shape of the thing it
was recommending as the verification step.

**`distTar` produces an uncompressed `.tar`.** The document opened with
`tar -xzf core-*.tar.gz`, which is the first real command an operator runs, and
it would have failed. Fixed on the build side rather than the document side: the
distributions are gzipped now and the extension says so.

### 10.9 — Registering a connector was not audited, and a comment said it was

Running the install document end to end registered two connectors and then
exported the audit log. It came back **empty**.

`Bootstrap.register` mints a credential and writes no audit row. Meanwhile
`connector.rotate`, landed earlier this phase, does — so the log could record
that a credential had been *replaced* with no record of it ever having been
created. "When was this connector added, and with what capabilities?" is the
first question an incident review asks.

The javadoc on `Bootstrap.register` had said, since Phase 1, that losing a
credential means registering a new connector — "a deliberate act **with an audit
row**". There was no audit row. A claim about the system living in a comment
that nothing read, which is the third time this phase has found that exact
shape: `NOTICE`'s generator (10.1), the plugin relocation that was going to be a
comment (10.7), and this.

The actor is `cli`, not `connector:<id>`. No connector takes this action — it
runs on the machine, against the database, as whoever has a shell there — and
recording a connector id would attribute an operator's action to something that
did not take it, which is the one property audit attribution exists to protect.

That javadoc is also updated: rotation exists now, so losing a credential no
longer means registering a second connector.

### 10.10 — The doctor could not see the machine

Every check the doctor had read the configuration and reasoned about it. That
is the wrong half of the problem. An operator's failures are about the machine:
a config file anybody can read, a database directory the service user cannot
write, a path that means one thing in the shell it was tested in and another
under systemd.

Four checks added, all of which need the filesystem:

- **Config permissions.** The install document says mode 0640 and nothing
  enforced it. A world-readable config may hold the storage password, and there
  was no other place in the system that would ever mention it.
- **Database directory writable.** The most valuable of the four. *"It runs by
  hand and fails under systemd"* is nearly always a hardened unit with
  `ProtectSystem=strict` and a database path outside `ReadWritePaths=`, and it
  arrives as a JDBC error about a file, several layers from the cause. The
  finding names `ReadWritePaths=` so the reader does not have to go and find it.
- **Relative database path.** Resolves against the working directory, which
  under systemd is not the one it was tested in — so core comes up against an
  empty database with no error at all.
- **Settings that do nothing.** `storage.user` with `backend = "sqlite"` works
  fine and is almost always somebody who meant to switch to MariaDB and changed
  one line of two.

Each has a control asserting it does *not* fire on a correct installation. A
doctor that warns about everything gets ignored, which is a worse failure than
not having one, because it is the state people believe they have checked.

Run against the shipped sample config on a machine where `/var/lib/soulbind`
does not exist, it now fails with that as the reason — which is the install
document's step 2, and confirms the document's ordering puts directory creation
before the check that depends on it.

#### The doctor learned which database was in use, twice

The first version of these checks parsed a `jdbc:sqlite:` URL and named
`Backend.SQLITE`, and the storage seam guard failed the build. It was right to:
a doctor that knows what a SQLite URL looks like is a caller that has learned
which database is in use, which is the thing the seam exists to prevent.

Fixed by moving the knowledge behind the seam rather than by exempting the
doctor. `Backend` gained `usesCredentials()`, `writableDirectory(url)` and
`isRelativeLocation(url)`; the doctor asks those questions and never learns the
answers' shape. The findings improved as a side effect — they name the
configured backend rather than saying "SQLite", so they stay true for a backend
added later.

Then it failed **again**, on the test file, which was still writing
`jdbc:sqlite:` by hand. Fixing production and leaving the test asserting against
a hardcoded URL would have left the seam half-crossed, and the guard covers test
sources for exactly that reason. The tests go through `StorageBackends` like
every other config-writing test, and skip via `Backend`'s own predicates when
the available backend keeps no local files — a narrowing that is exactly the
checks' subject and no wider.

And a third time, on an assertion *message* containing the word. That one is
the guard being blunter than it needs to be, but the cost of rewording a string
is a string, and the cost of an exemption is a guard that has learned to make
exceptions.

**A process note, recorded because it matters more than the defect.** This work
was committed on a red build: the verification command was chained such that the
failure did not stop the commit, and `BUILD FAILED` scrolled past. The commit
stands and the fix is on top of it rather than amended in, because the history
being honest about that is worth more than it being tidy.

### 10.11 — The threat model, and what writing it required checking

`docs/threat-model.md` is §14's pass over the protocol. The rule it is written
under: every claim is held by a named test, guard, or structural property, and
the few that are not are marked "(stated, not enforced)" — because a threat
model that drifts from the code is a comfort document.

Things established by checking rather than recalling:

- **The nonce is consumed before the signature is verified**, but the
  credential is resolved before either, so unauthenticated traffic can never
  reach the nonce store. The order was read, not assumed.
- **The store fails closed at capacity** — refuses rather than evicts. Evicting
  would re-open the replay window exactly when an attacker can create memory
  pressure. The cost (an authenticated connector can flood it) is taken
  knowingly and said out loud.
- **The claim ordering in `redeem`** — a first draft said "a refusal never
  hands the code back", which is wrong in both directions: SAME_ACCOUNT
  refuses *before* the claim (the person can still try correctly), and
  ALREADY_LINKED refuses *after* it (the code stays claimed, because
  re-offering a used code lets it be tried against a different account).
  Corrected against `LinkingService.redeem` before it shipped.
- **The document's bluntest sentence is its most important**: the credential
  travels in the `Authorization` header and is the signing key, so anyone who
  reads one request holds the credential. The HMAC bounds replay and binds
  the body; it is not TLS and the document refuses to imply otherwise.

The honest-gaps section (in-path attackers, host compromise, malicious
operators, authenticated DoS, social engineering of the ceremony) exists so
nobody reads silence as coverage.

### 10.12 — Tier 10, and the driver that swallowed its own verdict

T10 per §11: deep reads over the world the sim just accumulated — the whole
audit log paged past the 1000-row single-query ceiling in small pages,
contiguity asserted; a greedy one-request query required to *admit* it was
truncated; filtered paging required to return exactly the rows a real
`audit.push` principal contributed; any 5xx fails the stage, and no fault
injection runs here, so blame is unambiguous. Ordered before `fuzz` for the
same reason fuzz is last.

Scoped honestly: the plan sketches "Plan pages over hundreds of players", which
would need hundreds of clients to have joined a real server. What the tier
actually asserts is the read-path property at depth — and the `plan` stage
already renders over the sim-accumulated core, since Phase 8 put it after
`sim`. The depth top-up goes through real `audit.push` operations by a
registered `t10-auditor` principal (audit-source + config-management, recorded
in principals.txt; the drift guard now scans `run.sh` because that is where the
registration lives). Always at least fifty rows, so the filtered-paging
comparison can never become 0 == 0.

**The first real run of the driver exited 0 on a refusal.** The watchdog fired
correctly, printed its complaint — and the stage passed, because
`python3 | tee` reports the *tee's* status in POSIX sh, and `pipefail` is not
portable `/bin/sh`. The fix captures the driver's status, replays the log, and
exits with the truth. This is the exact shape of failure the battery exists to
keep out of every other stage, found in the stage being added to the battery.

### 10.13 — The install gate, and the defect it found before it ever ran

`harness/install-gate.sh` executes `docs/install.md`'s own commands in the
document's order on the guest host — a fresh Ubuntu VM with systemd, root, and
no soulbind — ending with a real cross-platform link read back from core, an
audit export that must contain the gate's own registrations and link, and a
restart the link must survive. Where the document offers a choice (apt vs
Temurin), the gate takes the document's primary path and falls back to the
document's stated alternative, naming which in the evidence.

**Writing it found a defect in the document.** §2 created `/etc/soulbind` as
`root:root 750` — which locks the `soulbind` user out of its own
configuration, and the failure would have surfaced two steps later as `doctor`
unable to read a file that looks perfectly in order. The tell was the gate
script needing a `chgrp` the document never says: under this gate's own rule —
anything the script must do that the document does not say is a defect in the
document — the fix went into the document, and the script now mirrors it.

The gate has never executed end-to-end: the workstation is FreeBSD, with no
systemd and no useradd, and the pieces were rehearsed individually against the
real tarball in 10.8. Narrowings ledger item 11; the session run is the first
full execution, and there it is a hard gate — no systemctl, no pass.

### 10.14 — Run 14: the install gate passed, and a test blamed core for its own bug

Run 14 was the first session execution of Tier 10, the clean-install gate, and
`core-env.sh`'s container mode. It **failed**, at the MariaDB unit stage, before
the full-stack tier ever started — so `t10` still has no session behind it.

**What passed, and it is the headline:** the clean-install gate ran all ten
steps green on a fresh guest. Unpack the real tarball, the shipped samples,
`doctor`, the hardened systemd unit — which came up, first try, with
`ProtectSystem=strict` and an empty capability set — three registered
principals, and then step 8: *"core confirms one subject holding both game and
forum identities"*. A real cross-platform link on a machine that had never seen
soulbind, following only `docs/install.md`. The audit export contained the
gate's own registrations and link, and the link survived a restart. That is
§14 Phase 10's gate clause, met.

Container mode worked too (narrowings ledger item 10, now discharged).

**What failed:** `UpgradePathTest`, both tests, on MariaDB only — first MariaDB
execution ever. It was committed at 21:11 and run 13 finished at 20:54, so the
workstation had only ever run it on SQLite, where none of this applies.

Core refused to boot: *"these tables cannot hold four-byte text: connector,
audit_seq, audit, platform_kind, runtime_config, connector_capability"*.

**The defect was in the test.** `migrateToOldVersion` claimed in its own first
line to build a database "the way an older release would have", and did it with
raw Flyway — skipping the `ALTER DATABASE` that `Storage.migrate` has run
*before* Flyway since Phase 8. On the session's latin1-default server that
produced a schema whose early tables were latin1 and whose later ones were not:
a state no release of soulbind can produce, because none of them runs Flyway
without that step. Core then refused to serve it, correctly and by design
(8.24), and the refusal arrived as two red tests pointing at core.

Fixed by calling the real code — `Storage.setDatabaseCharset` is now
package-private for exactly this — rather than re-issuing the ALTER in the
test. A second copy of an ordering decision is how the two come to disagree,
and the method's claim about fidelity is now true by construction instead of by
comment.

**The accident found something worth keeping.** The state it manufactured is
one an operator *can* reach without any help from a broken test: restore a dump
taken from a latin1 deployment, or hand core a database somebody else built.
Core handles it exactly right and nothing asserted that. Now something does —
`aGenuinelyLatinSchemaIsRefused` forces the database to latin1 explicitly
rather than relying on the server default, so it asserts the same property on
any server, and it checks that the refusal names an offending table and points
at the repair procedure. An operator told only "wrong charset" has to
rediscover that the conversion needs foreign keys dropped and rebuilt around
it.

It restores the database to utf8mb4 afterwards. `freshSchema` would cover it,
but a failure between the two would leave every later MariaDB test poisoned
with a fault nobody would trace back here.

### 10.15 — Run 15: the gate's own side effects broke the stage before it

Run 15 got past the MariaDB unit stage — 10.14's fix held, though the stage it
was fixing was never reached — and failed earlier in the run verb, at the
operator tools, with:

```
[core-env] mode: host
ERROR: JAVA_HOME is set to an invalid directory: .
```

Run 14 chose **container** mode on the same guest. Run 15 chose **host**. The
machine changed between them, and what changed it was run 14's own
clean-install gate: it installed `openjdk-25-jre-headless`, and reaper rolls
back the `state` dataset, not the guest's root disk. The gate's apt install
outlived the run that performed it.

**Two bugs in one line of `core_env_init`.**

`command -v java` proves a *runtime* exists. Gradle needs a *toolchain*, and
`openjdk-25-jre-headless` has no `javac` — so even the mode choice was wrong on
its own terms. And a bare `java` off `PATH` is not a path, so
`dirname $(dirname "$JAVA")` produced `.`, which is what reached gradle. The
workstation never saw either, because it always passes an absolute `JAVA`.

The derivation was wrong in a third way nobody had hit yet: on the usual Linux
layout `/usr/bin/java` is a symlink through `/etc/alternatives` into the real
JDK, and two `dirname`s of the symlink give `/usr`.

Now: an explicitly supplied `JAVA` wins, because a caller naming one has made
the decision. Otherwise podman, because a digest-pinned image is the same
toolchain every time and whatever happens to be installed on a shared guest is
not. Only then a JDK found on `PATH`, and it must have a `javac`. The resolver
follows symlinks and then **checks** that the result contains one, because a
`JAVA_HOME` without a `javac` is not a `JAVA_HOME` and saying so here costs one
line — letting gradle discover it costs a stage failure naming neither this
file nor the reason.

Verified against all four cases, including a JRE-shaped directory that must be
refused. The first attempt at that case was a bad simulation: the fake JRE's
`java` was a symlink to a real JDK, so resolving it correctly found the JDK and
the case "failed" for being right. A JRE with its own `java` is what the check
actually needed.

#### The gate is a clean install once per guest, and that is now written down

The underlying fact is not a bug and cannot be fixed away: the clean-install
gate installs packages, creates a user, writes `/etc` and enables a systemd
unit, because that is what installing means. Those survive into every later
`reaper test` in the same session.

So the gate proves *a clean install* on a fresh guest, and *an idempotent
re-install* on any run after the first. Narrowings ledger item 15. The response
was not to make the gate tidy up after itself — an install that uninstalls
itself is not the thing under test — but to stop anything else inferring the
machine's toolchain from what happens to be lying on it.

#### And the precedence was backwards

The fix above had its own bug, found by re-running the smoke rather than
assuming. `core_env_java_home` consulted an ambient `JAVA_HOME` *before* an
explicitly supplied `JAVA`. This workstation carries
`JAVA_HOME=/usr/local/openjdk17`, which has a perfectly good `javac` — so a
caller passing `JAVA=/usr/local/openjdk25/bin/java` was overruled by the
environment it was trying to override, and core started under Java 17 and died
at class load:

```
UnsupportedClassVersionError: dev/soulbind/core/cli/Main has been compiled by a
more recent version of the Java Runtime (class file version 69.0), this version
only recognizes class file versions up to 61.0
```

A caller who names a JDK has made the decision, and nothing ambient outranks
it. That is the same rule the build follows — bare `java` here is 17, and every
toolchain in this repository is declared rather than inherited. The resolver
now follows it too.

A **version floor** went in alongside: the resolved JDK is asked its own
version and refused below core's `--release` level, because the alternative is
the message above, which names neither the harness nor the cause.

#### A note on how both of these hid

Both failures were invisible for a while because the verification commands
piped through `grep`/`tail`, and a POSIX pipeline reports the *last* command's
status. The smoke exited non-zero; `tail` exited zero; the harness reported
success. That is the third instance this phase — the t10 driver (10.12) and a
commit made on a red build (see the doctor commit) being the others — and it is
the same mistake each time: **status through a pipe is the pipe's, not the
program's.** Verification here now captures the status first and prints
afterwards.

### 10.16 — Run 16: the gate was not testing what it said it was

Run 16 cleared the operator tools — 10.15's fix held, 26 operations with no
capability refusals and the export smoke's three mutants caught — and failed at
the install gate, step 7:

```
a connector named 'game-side' is already registered. Audit attributes events to
a connector, so two with one name would make the log ambiguous exactly where it
is being read to explain something.
```

Core refusing, correctly, for the reason it says. The database was run 14's:
`/var/lib/soulbind` sits on the guest's root disk, and reaper rolls back the
state dataset.

**The gate asserts that a clean install works, and had never established that
the machine was clean.** On a fresh guest that was true by luck; on any later
run in the same session it was false, and the gate failed rather than
re-installing.

10.15 argued that an install which uninstalls itself is not the thing under
test. That is right about cleaning up *afterwards* and wrong about establishing
the *precondition*. A gate that says "clean install" has to make the machine
clean, or it is testing something else under that name. It now removes the
unit, `/opt/soulbind`, `/etc/soulbind`, `/var/lib/soulbind` and the service
user before doing anything else.

Scoped to soulbind's own footprint. The JRE stays: it is a prerequisite rather
than part of soulbind's install, doc §1 explicitly handles finding one already
present, and removing it would mean re-downloading a toolchain every run to
prove nothing. Narrowing 13 is rewritten accordingly — its previous wording
claimed later runs proved "an idempotent re-install", which run 16 showed was
not true of anything.

**Three runs, three defects, none in soulbind.** 14 found a test that
manufactured an impossible database and blamed core; 15 found a harness
inferring a toolchain from whatever the previous run had installed; 16 found a
gate that assumed the precondition it exists to establish. The thing they have
in common is that each was a check being wrong about the world rather than the
world being wrong — which is the failure mode a battery is *supposed* to
surface before a deployment does, and the reason none of them was found by
reading.

### 10.17 — Run 17: green, and what the gate's evidence actually shows

`reaper test` exit 0. Every stage, both backends, one session.

**The clean-install gate passed on a machine it had made clean itself.** Its
evidence directory is the gate's whole argument, and it holds:

```
1 cli                    connector.registered
2 cli                    connector.registered
3 cli                    connector.registered
4 connector:7126ba47…    code.issued
5 connector:f1226a98…    identity.linked
```

Five audit rows, exported through `tools/audit-export.sh`, describing exactly
what the gate did and nothing else. The three `cli` rows are the registration
audit added in 10.9 — which did not exist a few days ago, and whose absence is
what made an earlier export come back empty. The link was issued by one
connector and redeemed by a different one, each holding only the capability its
half needs.

And `subject-after-restart.json`: one subject holding `game:gate-player` and
`forum:gate-account`, read back from core *after* `systemctl restart`. A real
cross-platform link, on a machine that had never seen soulbind, following only
the document.

**Tier 10 ran for the first time, on both axes, with real depth.** 500 rows
accumulated by journeys and the simulated users, topped to 1200 through real
`audit.push` calls, read back over five pages with contiguity asserted; a
greedy single query clamped at 1000 and admitted more remained; the filtered
deep read returned exactly the 700 pushed rows. Identical numbers on SQLite and
MariaDB, which is the same determinism the sim reports.

**Every skip in the run is accounted for.** `PlanCheckWalkerGuardTest` × 6 and
`DoctorFilesystemTest`'s writability case skip inside the build container —
python3 is absent there and the container runs as root — and both properties
are covered elsewhere: the shell mutation battery asserts the walker harder on
the guest host (13 mutants, all killed), and the writability case runs on the
workstation as a non-root user. `UpgradePathTest`'s latin1 case skips on
SQLite, which has no charset to be created under, and ran on MariaDB.

Four runs to get here, and the three failures were all checks being wrong about
the world rather than the world being wrong. That is the outcome a battery is
for; the alternative was finding each of them in a deployment.

### 10.18 — The effector half did not exist, and a manual smoke found it

The Phase 6 manual smoke — a real bot, a real server, a person typing `/link` —
found three things. The third is the reason manual smokes are in the plan.

#### `/whoami` was refused, and the documentation caused it

`docs/install.md` told operators to register the Discord connector with
`code-display,code-entry,effector`. The connector calls `identity.describe`,
which needs `link-state-reader`. So an operator following the document got a
connector that links accounts perfectly and then answers "this credential does
not hold the capability this operation requires" the first time anybody asks
what they are linked to.

Fifth caller of `identity.describe` broken by that grant (9.8, 10.3). The
existing defence — `principals.txt` plus `credential-smoke.sh` — covers the
*harness's* principals. The grant an operator actually types lived in a
document nothing read. A guard now parses `--capabilities` out of
`docs/install.md` and fails the build unless `principals.txt` records it, so the
thirty-second smoke covers it every run.

#### Commands registered globally while a guild was named

The first run registered three commands globally although `platform.guild` was
set — the bot had not been invited to the server yet, so the guild was not
visible, and the code fell back to global with one log line. That left three
global commands on the application: visible in every server the bot is ever
added to, an hour to propagate, and there until deleted by hand.

My first diagnosis was a race in `awaitReady()`. It was wrong, and a restart
disproved it: with the bot already in the guild, guild setup completes before
registration. The owner's explanation — the bot was added late — was correct.

The defect is real anyway, and it is the fallback rather than the timing. A
typo'd guild id does the same thing. So a configured-but-invisible guild now
**refuses to start**, naming the id and the two likely causes. Global
registration is still available by leaving `platform.guild` unset, which is a
decision rather than an accident.

#### `subject.requirements-met` was never emitted by anything

Setting up the role effector surfaced it. `RoleEffector` acts on exactly two
event types. Core emits five, and neither is among them.

Both were declared in `EventType`, documented in `docs/protocol.md` as part of
the wire contract — with a paragraph explaining why they are per-gate — and
specified in the plan §7: core emits them "per gate whose requirements just
became satisfied". Nothing produced them. **The effector half of the product
could not fire in any deployment**, and the same was true of
`connector-velocity`'s `GroupEffector`.

**Why ten phases of testing missed it.** `RoleEffectorTest` injects the event
and proves the role is applied. The full-stack battery drives `decide`, a
different path. Phase 4's gate — 100 mutations delivered in order and applied
once — put the events into the stream itself. Every test asserted one half
against the other half's assumption, and nobody checked that the event the
effector waits for is one core sends. Two oracles that never met.

#### What was built

`LinkingService` now brackets each mutation — redeem, attest, unlink — with a
before/after diff of the gates each identity satisfies, and emits the
transitions in the same call path as the change.

**Per identity, not per subject.** An effector grants on its own platform and
finds its target from the event's `identityRef`, refusing a ref whose kind is
not its own. One event per subject would carry one identity's ref and every
other platform's effector would ignore it.

**One evaluator.** `GateEvaluator` is used by the emission *and* by `decide`,
which previously built its snapshot inline. Two copies is how an effector comes
to grant a role for a gate that `decide` refuses — a person holding a role that
does not admit them, with nothing reporting it because each half is correct
alone.

**Three exclusions from "satisfied".** A gate with no rule, because gates are
recorded on first *mention* and emitting would hand a standing role to every
subject the moment any connector asked about one. Grace, because it is an
explicit temporary reprieve and nothing re-evaluates on a timer, so the role
would never come back off. And anything that denies.

#### The vacuous test, caught by mutation

Five tests, mutation-checked. Deleting the emission failed three. But making
unruled gates count as satisfied changed **nothing** — such a gate is satisfied
before and after any change, so it never yields a transition, and the
event-level test passed either way. It was asserting nothing.

Rewritten to assert against `GateEvaluator` directly, where the behaviour lives.
It kills the mutant now. Recorded because the test looked entirely reasonable
and was worthless, and only mutation said so.

#### Proven live

Against the real bot and server: `/link` typed by a person, code redeemed as
the game side, core emitting `requirements-met` per identity, and the role
appearing on the account within one poll — with nothing touching Discord's API
but the effector. Then `identity.unlink`, `requirements-lost` for **both** the
removed identity and its sibling, and the role removed.

The sibling case is the one worth naming: unlinking the *game* identity is what
takes the *Discord* role away, because the subject drops below the rule. Miss
it and an effector holds a role forever for somebody who no longer qualifies.

#### Not done, deliberately

`rule.changed` does not trigger re-evaluation. Editing a rule can flip every
subject at once, and doing that well needs a bounded sweep rather than a
synchronous fan-out inside the request that changed the rule. Narrowing 15.

#### What the Phase 6 smoke actually exercised

Recorded because the plan names this manual pass as *evidence*, and evidence
that lives only in a chat log is not evidence. Against a real application, a
real bot token and a real server, with a person at the keyboard:

| Exercised | Result |
|---|---|
| Gateway session, privileged `GUILD_MEMBERS` intent | connected, `Finished Loading!` |
| Guild-scoped command registration | 3 commands, named guild |
| `/link` — Discord **issues** a code | code returned, ephemeral |
| `/link <code>` — Discord **accepts** one | link completed |
| `/whoami` — link state read back | both identities, both `verified` |
| Role granted by the effector | applied within one poll |
| Role revoked on unlink | removed, including via the sibling path |
| `/soulbind connectors` without `config-management` | refused cleanly |
| `/link` with a malformed code | told plainly it was invalid |

Both link directions and both effector directions, which matters more than the
count: core never learns which platform is "normal", and the second round had
Discord as the *redeemer* where the first had it as the issuer. The two produced
the same shape, which is the symmetry §7 insists on rather than a coincidence.

`/whoami`'s wording is worth keeping as it is. It reports the **subject's**
identities rather than "what you are linked to" pairwise, which is the correct
model — there is no A-linked-to-B in soulbind, only a subject owning identities.
On a one-identity subject it therefore reads as "known here and nowhere else",
which is accurate. The concern that it would read oddly did not survive contact
with the actual output.

The two error paths were checked last and deliberately: a refusal and a
malformed input are what a person hits first in practice and what a suite covers
least, because a test asserting "it refused" rarely asks whether the refusal was
comprehensible.

#### `/soulbind rules` was advertised and did not exist

The smoke's last finding, and the one nothing could have caught but a person
reading the reply. `handleAdmin`'s usage line said:

```
Usage: /soulbind <rules|connectors>
```

and the switch had one case, `connectors`. Typing `rules` fell to `default`,
which replied with **the same usage line that had just suggested it** — a loop
with no exit, for somebody doing exactly what they were told.

Implemented rather than deleted from the advert. `rule.get` exists; what does
not exist is any way to enumerate gates over the protocol, so it takes a gate
name: `/soulbind rules discord.member`. The single Discord option carries the
whole subcommand, so it is split on whitespace here.

Missing the gate name gets its **own** message rather than the generic usage
line. "You are in the right place and need one more word" is a different fact
from "that is not a subcommand", and answering the first with the second is
precisely what made the original feel like a dead end.

The option's *description* was the string `"connectors"` — which is what
Discord shows the person typing, so the built-in hint named half the answer. It
describes the option now.

Two tests, both mutation-checked by restoring the original defect: reverting
`rules` to fall through fails them with "core was never asked for the rule" and
"a subcommand missing its argument got the generic usage line".

### 9.12 — The excluded invariant, re-enabled and checked both ways

9.10 excluded `decisions-follow-the-rules` as an **open question** rather than a
settled narrowing: it fired against unmodified core, and either core treated a
seen-but-unlinked identity as satisfying `requireLinked` — a real defect — or
the tier's model lost track of a link.

9.11 established which. Core is right. The model was stale, because all three
seeds shared one identity namespace and seeds two and three replayed names seed
one had already linked. The per-seed namespace and `didWork()` fixed that.

**What had not happened until now is switching it back on.** The tier went on
printing NOT CHECKED for something already repaired — which is how an exclusion
nobody revisits becomes indistinguishable from a defect nobody found. Ten runs
reported "3 of 3 seeds clean" alongside a line saying this was not among the
things checked, and nobody read the second half.

Re-enabled and verified **in both directions**, which is what 9.10 never got:

- Against unmodified core: three seeds, 400 actions each, **0 violations**.
- Against a core mutated to ignore `requireLinked` — one line in
  `PolicyEngine` — **all three seeds fail**, at action 50, naming the invariant
  and the gate: *"gate forum.post requires linkage and core says allow for an
  account it has never heard of."*

The never-seen probe is what makes it non-vacuous. The real unlinked set empties
out within a few dozen actions as the actors link everything they own, so a
version looping only over `neverLinked()` asserts nothing by the time a rule
exists — the first version of this invariant was exactly that, and the
acceptance test caught it. An identity core has never heard of cannot empty out
and is definitionally unlinked, so the probe always has something true to
assert.

One invariant is still excluded, and it is a settled narrowing rather than an
open question: `redeemed-codes-stay-redeemed` has no non-mutating way to ask a
real core whether a code is still redeemable. Attempting the redeem *is* the
check, and against a broken core it would link a phantom identity and corrupt
the graph the rest of the run asserts about. Single use is proven under real
concurrency by the Phase 2 gate, which is the stronger test (9.4).

### 10.19 — The survivor tail, chased on one module

The mutation tail was recorded as known-and-unfixed: 182 mutants executed by a
test that did not notice. This is a pass over `policy`, the module where a
surviving mutant has the most direct consequence for a person at a gate.

**80% → 98%. Seventeen survivors down to two, and both of those are
equivalent.**

#### What it found, in descending order of consequence

**An override for one person applied to everybody.**
`PolicyOverride.matches` returning `true` unconditionally killed no test —
every override in the suite targeted the subject under test, so nothing
asserted that an override naming somebody else is ignored. That mutant is an
allow-override written as a one-person exception admitting the entire
deployment, which is the worst thing an override can do, and operators reach
for overrides precisely to make exceptions. The new assertion fails 48 ways.

**The refusal wording.** The matrix asserts effects; nothing asserted `detail`.
So the branches choosing between "you are not linked", "you are not linked and
are missing X", and "you are missing X" all survived — swap them and a linked
person is told to link, or an unlinked person is told about a missing platform
and comes back still refused. This project has already shipped one message that
sent somebody in a circle; the cost of a wrong instruction is paid by whoever
receives it.

**`Effect.fromConfigName` had no coverage at all.** It reads a rule's
`defaultEffect` out of configuration — what happens to somebody whose
requirements are unmet. A typo'd value silently becoming ALLOW is a gate that
admits everybody it was written to refuse.

**The factories, and why they survived is the interesting part.**
`Rule.open` and `Rule.requiring` feed `DecisionMatrixTest`'s parameter source,
so a factory returning `null` produced a matrix of null rules — and a null rule
is "no rule governs this gate", which the matrix's own oracle computes
identically. Both sides moved together and every assertion held. Not a gap in
the matrix, which asserts decisions and does it well; the absence of anybody
asserting that `Rule.open` returns an open rule.

#### The two that remain are equivalent, and saying so is the point

- `graceTtl`: `remaining <= 0` → `remaining < 0`. At exactly zero the original
  returns 0 and the mutant returns `min(ttlSeconds, 0)`, which is 0. No input
  distinguishes them.
- `SubjectSnapshot`: `identityCount < 0` → `<= 0`, which rejects a snapshot of
  zero identities. Nothing constructs one and arguably nothing should, so the
  mutant is a slightly stricter implementation rather than a wrong one.

Recorded because an unexplained survivor is a standing invitation to write a
test that cannot fail. Both of these have been reasoned about and neither is
worth an assertion; a future sweep should skip them rather than rediscover them.

Twelve mutants killed by four new test classes, none of which was written to
raise a number — each names the person the defect would have reached.

#### The protocol module: 56% → 99%

The same pass over `protocol`, where forty-three survivors became one. Two were
`SURVIVED` rather than uncovered, and both were in load-bearing places.

**`RequestSigner.requireNoSeparator`: `indexOf(SEPARATOR) >= 0` weakened to
`> 0` killed no test.** The existing case put the separator in the middle of a
nonce, and index 0 is the single value on which the two conditions disagree —
so a value *beginning* with the separator was never tried. What that mutant
permits is a nonce of `"\nb"` and a nonce of `""` with a body starting `"b"`
producing identical canonical bytes: one signature valid for two different
requests, reachable by any caller who chooses their own nonce. That is exactly
the ambiguity the guard exists to prevent.

**`LinkCode.normalise`'s fold, and a test whose name was a promise it did not
keep.** The parameterised cases were titled *"strips separators and
uppercases"* and every input was already uppercase. Weakening the fold's upper
bound left `z` unfolded, so a code containing one was refused and nothing
noticed — and people type codes in lowercase constantly.

**`HelloRequest` was entirely uncovered, which was the surprise.** It is not a
data holder; it carries the forward-compatibility rule stated on its own class:
a connector built against a newer protocol may claim a capability this core has
never heard of, and the answer is to grant it nothing extra rather than refuse
the handshake and take the connector offline over a name. That is the
difference between a newer connector degrading and failing to start, and
nothing asserted it.

**`Capability.fromWireName` had no coverage at all.** What rides on it is
authorization: a wire name read as the wrong capability, or as none, is a
connector granted or refused the wrong thing. Now round-tripped for every
constant, derived from `values()` so a capability added later is covered the
day it is added — which matters, because `link-state-reader` was added
mid-project and broke four callers.

**The DTO defences turned out not to be trivia.** Nine `x == null ? empty :
copyOf(x)` lines were uncovered. The codec binds JSON into these records, so a
field a connector simply *omits* arrives as null: negate the condition and an
optional field left out of a request throws inside the dispatcher rather than
defaulting. Which fields are optional is a protocol promise.

The one survivor left is equivalent: `normalise`'s lower fold bound. `a` is not
in the code alphabet, so folding it to `A` — also absent — or leaving it alone
both end in the same rejection.

#### A guard caught this work, which is the point of having it

`PlatformVocabularyGuardTest` failed the build: the new tests used a real
platform's name. Core and protocol learn platform kinds at runtime from
connector registration, and a name compiled into either means that is no longer
true — in a *test* as much as in production, because a test naming a platform
is a test that would have to change when the platform does. Neutral kinds now.
Third time in this phase a guard has failed my own work rather than somebody
else's, which is the only real evidence that a guard is doing anything.

#### `connector-sdk` 68% → 88%, and `config` 77% → 88%

**A public SDK overload used by nothing.**
`IdempotentApplier.applyOnce(String, Consumer<String>)` was uncovered because
it is called nowhere — not in production, not in tests. Kept rather than
deleted: it is published surface a connector author may reasonably reach for,
and its entire value is that the effect is told which key it is running under.
An effect handed the wrong string would key its own bookkeeping wrongly, which
is the one mistake this class exists to prevent. Now asserted.

**`Payload` — trusted twenty-odd times from other modules, asserted in none.**
Every connector reads core's answers through it. The contract worth pinning is
what happens on fields that are *absent*: every accessor is total, so a
connector reading an optional field need not guard every call — and the cost is
that a misread field looks exactly like an absent one, which makes `has()` the
only way to tell "core said no" from "core said nothing". A null body behaves
the same way, because an outage is not a payload.

**`HttpTransport`'s endpoint.** A trailing slash in an operator's `core.url`
would produce `//v1/rpc`, which some servers route and some do not — the same
configuration working on one deployment and 404ing on the next, with nothing in
either explaining it. `endpoint()` is package-private so a test can see what
the URL became.

**Edit distance could not be tested through the thing that uses it.** Three
mutants in `ConfigLoader.editDistance` survived a suite of suggestion tests,
and adding more suggestion tests did not kill them: the suggestion depends only
on whether the distance is *under a threshold*, so the arithmetic can be wrong
and every input still land on the same side. The first attempt at fixing this
made it worse in an instructive way — the new cases were all distance 1 and 4,
never touching the 2/3 boundary the threshold actually sits on.

Made package-private and tested as the pure function it is. The empty-string
rows catch a broken initialisation row and the equal-length rows catch an outer
loop that stops one character short, neither of which any suggestion would ever
reveal.

**`describeType` had one branch of five.** The wrong-type message named a
string found where an integer was wanted, and nothing exercised integer,
boolean, float or the fallback. An operator reading "must be an integer, found"
and nothing else has been told half of what is wrong, and the half they were
told is the half they already knew. The first version of that test appended a
line to a fixed body and hit TOML's duplicate-key error instead, asserting
nothing about types at all — whole bodies now.

### 10.20 — Nothing bounded how fast a code could be guessed

A link code is eight characters from a twenty-eight character alphabet:
3.8×10¹¹ possibilities, and guessing a *particular* one is hopeless. That is
the reassuring number, and it is the wrong one. **An attacker does not need a
particular code — any live code links their account to a stranger's subject.**
With a hundred codes outstanding, a guess lands with probability ~2.6×10⁻¹⁰,
and nothing at all bounded the guessing rate.

`docs/threat-model.md` said codes were "single-use, expiring, unpredictable"
and never mentioned online guessing. Raised by the owner while discussing
something else entirely, which is the useful kind of review.

**Only "no such code" counts as a guess.** Not expired, not already-redeemed,
not refused because both accounts were already linked — those all mean the
caller *had* a real code and something else was wrong, and counting them would
throttle somebody who walked between two platforms too slowly.

**Keyed on the account, not the connector.** Throttling the connector takes a
whole platform offline over one abuser. The account is who is guessing, and it
is the finest thing core can see: core deliberately knows nothing about IP
addresses or sessions, which is exactly why the owner's instinct — let each
platform limit by whatever it understands — is right. This is the floor beneath
those, and it is the floor that matters, because an attacker spreading attempts
across several connectors evades every per-connector limit and still meets this
one.

**A success clears the record.** Somebody who mistypes twice and gets it right
is not a threat.

#### It fails open, and that inverts its neighbour deliberately

`NonceStore` fails **closed** at capacity: letting a replay through is worse
than refusing a request. `RedeemThrottle` must do the opposite. Refusing at
capacity would let an attacker who fills the map lock every legitimate person
out of linking — turning a guessing limit into a denial-of-service lever, which
is a worse weapon than the one it was built to remove. The cost is that a
determined attacker can age their own record out by making noise from other
accounts, buying a multiplier on a limit that was already generous.

Two classes, one page apart, choosing opposite behaviours at capacity. Stated
in both, because the next person to read either will assume consistency.

### 10.21 — Connectors now hear that a rule changed

Narrowing 14 said core does not re-evaluate gates when a rule changes, and
framed it as work core would have to do. The owner pointed out the better
shape: core dispatches the notification and each platform syncs its own state.

Core has emitted `rule.changed` since Phase 3. **Nothing consumed it** — not
the effectors, not the SDK's decision cache. So the gap was never core's; it
was that no connector listened.

That framing also dissolves the objection that stopped me. I argued grants were
unbounded because an effector would have to enumerate candidates. But a
connector reconciling *its own* population is bounded: the accounts on this
platform holding this role, which the platform can be asked for directly.

**Revocations only, and that asymmetry is the design.** Finding people who
newly qualify would mean asking core about every member of the platform. Nobody
is wrongly holding anything in that direction — they get the role on their next
`requirements-met`. Taking a role away is the half that cannot wait, because
until it happens somebody has access a rule says they should not.

**Asked of the platform, not remembered.** An operator may have granted or
removed the role by hand, and a connector reconciling against its own memory
would fight them. It also survives a restart without persisting anything.

**Null is not false.** A `decide` that cannot be answered — an outage, or a
connector without `enforcement-point` — revokes nothing. The alternative turns
a brief core restart into a mass removal somebody then undoes by hand. A
missing capability is logged rather than swallowed, because a connector
silently reconciling nothing looks exactly like one that is working.

This is the connector's first use of `decide`, so the documented grant gains
`enforcement-point` — and the guard added earlier this phase is what would have
caught the doc if it had not.
