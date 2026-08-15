# The connector protocol

> **Status: stub.** The wire contract is defined incrementally from Phase 1.
> A structural test holds this document to the code — every operation in code
> must appear here and vice versa, and every capability referenced must be
> declared in the capability table. That test arrives with the first real
> operation.

This document is the human-readable contract. The code is the authority; this
file is checked against it mechanically rather than trusted.

## Versioning

Every message carries `schema` (integer). A connector or core refusing an
unknown major version is **a refusal with a reason**, never a silent downgrade —
a downgrade would let two peers disagree about semantics while appearing to
work.

## Authentication

Each connector holds one credential: a random 256-bit token, hashed core-side.

- **WebSocket transport** authenticates at connect.
- **Webhook/poll transport** signs every request body with HMAC-SHA256 over
  `(timestamp, nonce, body)`. Core rejects stale timestamps and replayed nonces.

The signing scheme lives in `protocol/`, is re-implemented in PHP, and is pinned
by golden vectors. TLS is assumed at the deployment layer; the protocol does not
rely on it for authentication.

## Capabilities

| Capability | Operations |
|---|---|
| *(any registered)* | `hello`, heartbeat, event subscribe/poll |
| `identity-provider` | `attest` |
| `code-display` | `code.issue` |
| `code-entry` | `code.redeem` |
| `enforcement-point` | `decide` |
| `effector` | *(consumes events; acknowledges with an idempotency key)* |
| `audit-source` | `audit.push` |
| `config-management` | `rule.*`, `override.*`, `config.*`, `connector.list`, `subject.inspect`, `identity.unlink` |

The admin API is the same operation set exposed to admin credentials under the
same capability model. **One capability table, one authorization matrix, no
second code path** — that is what keeps the rule from existing in two copies
that drift.

## Linking

Symmetric by construction. A `code-display` connector requests a code for an
account it can authenticate locally; the person types it into any `code-entry`
connector; core transactionally validates TTL and single-use, resolves or
creates the subject, binds both identities, records proof methods, appends
audit, and emits events.

Either side can be display or entry. **Core never knows the pairing.**

## Events

At-least-once, idempotency-keyed, with per-connector cursors so a connector that
was down receives what it missed, in order, on reconnect. Effectors must be
idempotent; the SDK enforces key-based dedup.
