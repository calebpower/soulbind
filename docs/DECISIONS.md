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
