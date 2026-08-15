# connector-sdk

The Java connector runtime: transports, decision caching, retry, idempotent
event handling.

## Why it is a separate module

Three Java connectors need identical protocol behaviour — signing, replay
protection, decision-cache TTL handling, at-least-once event dedup. Implementing
that three times guarantees three subtly different behaviours, and the
differences would surface as connector-specific bugs that look like core bugs.

## Dependencies

`api(protocol)` — the SDK exposes protocol types to its consumers, so the
relationship is `api`, not `implementation`. That is also why the shared
convention applies `java-library`.

## Release level: Java 21

Two of its consumers load inside a server operator's JVM.

## Seams this module sits behind

**Transport seam.** Transports implement one interface; protocol logic lives
above it and is tested against an in-memory transport. No WebSocket or HTTP
client type may escape the transport package. *(Guard arrives in Phase 1 with
the transport code.)*

The door is open a crack for a third compiled-in transport, and no wider: no
dynamic loading, no ABI.

**Fail-closed is the default.** When a connector cannot reach core and holds no
unexpired cached decision, it denies — with messaging that says the *system* is
at fault, not the person. Per-connector config may choose otherwise; every
departure from the default is a visible config line, and the shipped default is
asserted by a test in every reference connector.

## Build and test

```sh
./gradlew :connector-sdk:test
```
