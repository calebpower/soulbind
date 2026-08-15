# connector-velocity

Reference connector for a game proxy.

> **Status: skeleton.** Content arrives in Phase 5. This README describes what the
> module will be and the constraints it already inherits.

## Why it is a separate module

A connector is an out-of-process integration speaking the connector protocol. It
is separate because it runs in a runtime core does not control, on its own
release cadence, and because a reference connector's job is to prove the seam is
real: if adding a platform required a core change, the architecture would be a
claim rather than a fact.

## Dependencies

`connector-sdk` (and through it, `protocol`). Never `core`.

A connector that could import core would be tempted to reach into the identity
graph directly, and the moment one does, core stops being the single authority.

## Release level: Java 21

It loads inside a server operator's JVM, whose floor is 21. Bytecode targeting 25 would fail at class-load time in a deployed server.

## Constraints it inherits

- **Fail-closed by default**, with user-facing messaging that blames the system
  rather than the person. The shipped default is asserted by a test here.
- **Holds no authoritative state** beyond a decision cache bounded by the TTL
  the decision carried.
- **Effectors are idempotent**; events arrive at-least-once with an idempotency
  key and the SDK enforces dedup.

## Build and test

```sh
./gradlew :connector-velocity:test
```
