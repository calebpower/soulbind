# guards

The seam guards. This module contains **no production code** — it reads the
repository as data and fails when a seam has been crossed.

## Why guards exist at all

An architecture held together by good intentions decays at the first deadline.
Every seam in the specification's §5 is a claim about what this codebase will
never do; a claim nothing enforces is a hope.

## Why every guard has a deliberately-broken fixture

**A guard nobody has watched fail has unmeasured value.** Each guard here is
paired with a fixture that must be rejected, and the fixture drives *the same
scanning code* as the real check — a fixture verified by a second implementation
would prove only that the second implementation works.

This is not theoretical. Writing the release-level guard, the mutation check
produced a green run: Gradle had marked the task up-to-date and skipped it while
a real violation sat in another module's build file. The guard was correct and
useless. See `docs/DECISIONS.md` 0.8, and note `outputs.upToDateWhen { false }`
in `build.gradle.kts` — that line is load-bearing, not cautious.

## The guards

| Guard | Enforces | Since |
|---|---|---|
| `PlatformVocabularyGuardTest` | No platform name in `core/` or `protocol/` | Phase 0 |
| `ReleaseLevelGuardTest` | Declared convention plugin **and** emitted class-file version match the contract | Phase 0 |
| `DependencyGraphGuardTest` | No YAML parser in any Java module | Phase 0 |
| storage seam | No SQL string or JDBC type outside the storage module | Phase 1 |
| transport seam | No WebSocket/HTTP client type escapes the transport package | Phase 1 |
| capability seam | Every protocol operation declares its capability in exactly one table | Phase 1 |

The last three land with the code they constrain. A guard written before its
subject exists cannot be given a meaningful fixture and would pass vacuously
while reading as coverage — see `docs/DECISIONS.md` 0.7.

## The allowlist

`platform-vocabulary-allowlist.txt` starts empty and should stay that way.
Entries are `<path>:<word> # reason`, and **an entry without a reason fails the
guard** — silence is how a narrowing becomes permanent without anyone deciding
it should. The reason must cover exactly what it narrows.

## Release level: Java 25

It never ships anywhere.

## Build and test

```sh
./gradlew guards          # from the root
./gradlew :guards:test    # equivalently
```
