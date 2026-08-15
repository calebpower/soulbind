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
