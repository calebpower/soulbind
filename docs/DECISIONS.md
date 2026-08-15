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
