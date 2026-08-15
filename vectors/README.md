# vectors

Generated, committed golden vectors. The oracle proving the Java and PHP
implementations of the protocol agree.

> **Status: empty.** Vectors arrive with the surface they pin — link-code
> normalisation in Phase 2, request signing in Phase 1/2.

## What they pin

1. **Link-code normalisation** — raw input to normalised form, or rejection.
   The alphabet deliberately excludes characters humans confuse (`0`/`O`,
   `1`/`l`/`I`), is case-insensitive, and strips separators before comparison.
2. **HMAC request signing** — key, timestamp, nonce and body to signature.

## Why committed rather than generated at test time

Generated-at-test-time vectors prove both implementations agree with *the
generator*, which is one implementation wearing a hat. Committed vectors are a
third party both sides are checked against, and a change to one shows up as a
diff in review rather than a silently-updated expectation.

## The hostile second run

Each suite runs the vectors **twice**: once normally, once under a hostile
default charset. Normalisation that quietly depends on the platform default
encoding passes the first run and fails the second, which is exactly the defect
that would otherwise appear only on somebody else's machine.

## Regeneration

Regenerating is a deliberate act. A vector file changing in a diff is a change
to the wire contract, and should be reviewed as one.
