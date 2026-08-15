# core

The dispatcher. The single authority on the identity graph, policy, config,
audit and the connector registry.

## Why it is a separate module

Everything else in the system is replaceable; this is not. Connectors hold no
authoritative state, so the properties that matter — a link code is single-use,
audit is append-only, a decision is a pure function of the graph and the rules —
are all properties of this module and can be tested without a network.

## Dependencies

`protocol`. Nothing else in soulbind.

Core must never depend on a connector, or on anything naming a platform. The
dependency direction is the architecture: connectors know about core, core knows
only about capabilities.

## Release level: Java 25

Standalone service in a JVM we control, so nothing constrains it downward.

## Seams this module sits behind

**Platform vocabulary.** No platform name appears in this module's source. Core
learns platform kinds at runtime from connector registration. The moment a name
is compiled in here, hub-and-spoke has become a mesh with a favourite, and the
claim that adding a platform needs no core change is no longer true.

Same prose consequence as `protocol/`: say "specification", not the other word.

**Storage seam** *(guard arrives in Phase 1 with the code it constrains).*
Persistence sits behind a repository interface with two implementations. No SQL
string and no JDBC type outside the storage module; every storage test runs
against both backends by parameterisation.

**Audit is append-only in fact, not in policy.** The storage API for audit
exposes no update and no delete, and a structural test asserts no code path
acquires one.

## Build and test

```sh
./gradlew :core:test
```
