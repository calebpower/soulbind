# protocol

The wire contract, as code: DTOs, the link-code alphabet and its normalisation,
HMAC request signing, and schema version constants.

## Why it is a separate module

Two implementations must agree on this surface — this one, and the PHP
re-implementation in `connector-flarum/`. The golden vectors in `vectors/` are
the oracle proving they do. Keeping the surface in its own small module makes
"what must PHP mirror?" answerable by listing a directory.

## Dependencies

**Deliberately almost none.** Every dependency added here is one the PHP side
must somehow mirror, so additions need a reason recorded in
`docs/DECISIONS.md`.

It may not depend on any other soulbind module. Everything depends on it.

## Release level: Java 21

Because the connectors depend on it, and two of those load inside a server
operator's JVM whose floor is 21. Bytecode targeting 25 would fail at class-load
time in a deployed proxy — a defect that surfaces far from its cause, which is
why `guards/` asserts the emitted class-file version rather than trusting the
build file.

## Seams this module sits behind

**Platform vocabulary.** No platform name may appear in this module's source,
case-insensitively, outside an allowlist that starts empty. The protocol is a
namespace mechanism, not an enumeration: platform kinds are registered by
connectors at runtime and never compiled in.

A practical consequence: prose here cannot use words like `plan` or `paper`. Say
"specification". See `docs/DECISIONS.md` 0.4.

## Build and test

```sh
./gradlew :protocol:test
```
