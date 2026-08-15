# build-logic

Convention plugins. Included, not published.

## Why it exists

So that toolchain and release-level decisions live in exactly one place. A
structural test asserts each module's resolved release level against the
specification's table, and that assertion is only meaningful if there is one
place the answer comes from.

## The plugins

| Plugin | Applies |
|---|---|
| `soulbind.java-common` | Toolchain 25, JUnit 5, UTF-8, reproducible jars, seed plumbing |
| `soulbind.java-21` | `soulbind.java-common` + `--release 21` |
| `soulbind.java-25` | `soulbind.java-common` + `--release 25` |

**One toolchain, two targets.** Java 25 compiles everything. Modules loaded into
a server operator's JVM emit Java 21 bytecode because that runtime's floor is
21; standalone modules emit 25.

## Two things that are deliberate, not oversights

**No `repositories { }` block.** `settings.gradle.kts` sets
`FAIL_ON_PROJECT_REPOS`, so repositories are declared once and a module cannot
quietly introduce its own source of artifacts. Declaring one here is a build
failure, by design.

**`java-library`, not `java`.** `connector-sdk` exposes `protocol`'s types to
its consumers, which is an `api` relationship.

## Toolchain discovery

`gradle.properties` pins installation paths explicitly rather than trusting
discovery. On some hosts a bare `javac` resolves to an older JDK than the newest
installed, and the resulting failure names the compiler rather than the
toolchain — a long way from the cause.
