# `connector-plan/`

The reference analytics connector: it puts link state on a dashboard an operator
already looks at, and does nothing else.

## Why this is its own module

A connector is an out-of-process integration speaking the connector protocol. It
is separate because it runs in a runtime core does not control, on its own
release cadence, and because a reference connector's job is to prove the seam is
real: if adding a platform required a core change, the architecture would be a
claim rather than a fact.

It is also the module that proves the *read-only* half of that seam. Every other
connector so far asks core to **decide** something — admit this player, refuse
this post. This one only asks core what is already true. Had that required a new
core operation, the read side of the protocol would have been under-designed and
nobody would have found out until a fourth connector needed it.

## Dependencies

`connector-sdk` (and through it, `protocol`). Never `core`.

A connector that could import core would be tempted to reach into the identity
graph directly, and the moment one does, core stops being the single authority.

## Release level: Java 21

It loads inside an analytics plugin running in a server operator's JVM, whose
floor is 21, while `core` targets 25. Bytecode targeting 25 would fail at
class-load time in a deployed server. A guard asserts both the declared
convention and the class-file version actually emitted, because getting it wrong
surfaces as an `UnsupportedClassVersionError` far from its cause.

## Read-only, and not by politeness

The connector is registered with capabilities that permit inspection and nothing
else. A provider here **cannot** mutate the identity graph even if somebody
added one that tried — the credential refuses it at core, not this file.

That is deliberate. "Mutations stay on the admin API" enforced by a comment is a
rule this module has to keep remembering; enforced by the credential it is a
property of the deployment. The dashboard is the most-installed, least-audited
surface in the system, and the one place where a read-only guarantee is worth
paying for structurally.

## Unknown is a state, and the whole module is built around it

A dashboard has exactly one way to be actively harmful: printing a confident
answer it does not have.

If core is unreachable and the page says **"not linked"**, an operator goes and
chases somebody whose links are perfectly fine, with nothing on the page to hint
that anything was wrong. So `unknown` is carried end to end as its own value:

- `PlayerLinkView.known()` distinguishes "core answered" from "core did not".
- The `Link status` **string** provider says `unknown` in words. The `Linked`
  **boolean** provider fails closed to `false`, because a boolean has no third
  value — reading only that one is reading an answer that was never given, which
  is why the string exists beside it.
- **`Unknown` is its own count** on the server page, next to linked and
  unlinked. Without it the two counts do not add up to the roster and an
  operator has nothing to attribute the difference to.
- `ServerLinkSummary.linkedFraction()` excludes unknown from its denominator. A
  percentage that quietly counts unreachable players as unlinked drifts downward
  during an outage and looks like a real decline.

**Outages are never cached.** `LinkDataSource` caches answers, not failures, so
recovery shows up on the next call rather than at the end of a TTL.

## Decisions that would otherwise look arbitrary

**Not `PLAYER_JOIN`.** Providers run on `PLAYER_LEAVE` and `SERVER_PERIODICAL`.
A join is both the moment a player is least likely to have *just* linked and the
one path a proxy plugin must never make slower. Putting a round trip there buys
nothing and costs the thing operators notice most.

**Milliseconds at the boundary.** Core speaks epoch seconds; Plan's date formats
expect milliseconds. The conversion lives in the provider. Getting it wrong
renders 1970 on every page — which reads as a data problem and sends whoever
investigates in the wrong direction entirely.

**The subject id is off the page until an operator opts in.** It is an
identifier that correlates a player across platforms, which is exactly what a
dashboard should not casually publish to everyone with panel access.

**The roster comes from the caller.** Plan asks about the players it knows; core
does not know who is online and should not be asked to guess.

**Earliest verification wins.** When an account has several identities, "linked
since" is the first of them. The most recent would mean the date changes meaning
every time somebody links another platform.

## The Plan API dependency

`compileOnly`, because Plan is the host: it is on the classpath at runtime by
definition, and bundling a second copy is how a plugin ends up loading
annotations that are not the ones the host scans for.

Pinned exactly, never to a range. The extension API is annotation-driven, and a
provider signature that stops matching produces **a page with a missing panel
and no error anywhere** — the same silent failure shape as an unbuilt frontend
bundle. A range would let that happen on somebody else's schedule.

The artifact arrives through **JitPack**, which builds from arbitrary GitHub
repositories on demand and is a materially larger trust surface than the other
repositories here. `settings.gradle.kts` scopes it to this one publisher for
that reason.

`build.gradle.kts` also puts the same artifact on the **test** classpath, plus
`commons-lang3` at test runtime. That is not belt-and-braces: `compileOnly`
alone leaves every provider body unexecutable by a test — the annotations
compile and the bodies never run — and Plan's `Table.Factory` calls
`commons-lang3` without declaring it in its POM. Without both, a units error or
an empty-to-placeholder slip ships looking exactly like working code. Neither
dependency reaches a distributed artifact.

## Constraints it inherits

- **Fail-closed by default**, with user-facing messaging that blames the system
  rather than the person.
- **Holds no authoritative state** beyond a decision cache bounded by the TTL
  the decision carried.
- **Effectors are idempotent**; events arrive at-least-once with an idempotency
  key and the SDK enforces dedup. This connector registers none — it is a
  reader.

## Where the logic lives

Everything that decides anything is in `LinkDataSource`, which knows nothing
about Plan and is tested without a Minecraft server. `SoulbindDataExtension`
exists to be annotated: the less it decides, the less goes untested. Its
provider bodies are still executed by `SoulbindDataExtensionTest`, because
annotation-driven code fails quietly and "cannot be tested" must not become
"is not tested".

## Build and test

```sh
./gradlew :connector-plan:test
```

## Extension points

The panels are deliberately few. Anything richer — per-platform breakdowns,
history, linking trends — is a question about whether the dashboard is the right
place to answer it, not a matter of adding another provider.
