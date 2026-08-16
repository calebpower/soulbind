# `connector-sdk/`

What a connector uses to talk to core.

## The transport seam

`Transport` is one method: send a request body, get a response body. Everything
interesting — envelope construction, signing, refusal handling, decision
caching, idempotent application — lives **above** it, and is therefore tested
against `InMemoryTransport` with no socket in the room.

A guard asserts no HTTP or WebSocket type appears outside `sdk/transport`. The
failure it prevents is protocol logic that can only be exercised by standing up
a server: such logic still works, it just gets tested less, so it is where the
bugs go.

The door is open a crack for a third compiled-in transport and no wider — no
dynamic loading, no service discovery, no ABI. A transport is a class in that
package, chosen at construction.

## Refused is not unreachable

This is the distinction the SDK exists to keep.

- A **refusal** is core saying no. Tell the person. Do not retry.
- An **outage** is core not answering. Fall back to the cache, then the fail
  mode.

Collapsing them turns "you may not" into "try again later", and turns a genuine
denial into something a retry loop eventually gets past.

Two consequences that are easy to get backwards:

**A refusal never consults the cache.** If core refuses because this connector's
capability was revoked, serving a cached allow would use a stale answer to route
around a permissions problem — and it would keep working long enough for nobody
to notice.

**A response that is not an envelope is an outage, not a refusal.** An API
gateway, a service mesh or a rate limiter answering in JSON is not core, and
core never saw the request. Reporting that as a denial tells somebody they were
refused by a system that never heard of them.

## Fail-closed

The shipped default, in every reference connector. A gate that opens whenever
the dispatcher is down is a gate an attacker opens by taking the dispatcher
down.

Only the exact word `open` selects fail-open — a typo, an empty string, `yes`,
`true`, `1` and null all mean closed, because a mistake in a fail-mode must
never be the thing that opens a gate.

The user-facing message blames the system. Somebody refused because a server
they have never heard of is unreachable should not be told they are not allowed.

## Idempotent application

Delivery is at-least-once. `IdempotentApplier` does the dedup rather than
documenting that connector authors should, because a rule enforced by a
paragraph is a rule that holds until somebody is in a hurry.

It records the key **before** the effect and removes it again **if the effect
throws** — recording after would let a crash cause a re-apply, and not removing
on failure would mark an effect applied that never happened.

It **evicts** rather than refusing when full, which is the opposite of the
replay-nonce store and deliberately so: forgetting a nonce means failing to
detect a replay, but forgetting an idempotency key means applying an idempotent
effect twice, which is harmless by definition.

## Building and testing

```sh
../gradlew :connector-sdk:test
```

Every test runs above the seam. Conditions a real network cannot be asked for on
demand — a core reachable for one call and gone for the next, a truncated body,
a gateway answering in JSON — are one line each.
