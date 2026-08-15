# The connector protocol

> **Status: in progress.** The wire contract is defined incrementally from
> Phase 1. A structural test (`ProtocolDocSyncGuardTest`) holds this document to
> the code: every operation declared in `Authorizer.Operation` must appear in the
> operations table below against the same capability, and every row in that table
> must correspond to a declared operation. Neither can drift without failing the
> build.

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

The credential is stored as a lowercase-hex SHA-256 digest of the UTF-8 token
bytes. SHA-256 rather than bcrypt or argon2 is deliberate: a password-hashing
function exists to make *guessing* expensive for low-entropy human secrets, and
against a uniformly random 256-bit token guessing is not the threat — the work
factor would only slow down every legitimate authenticated request.

### Canonical signing form

The signed bytes are, exactly:

```
<timestamp-seconds> LF <nonce> LF <body>
```

encoded **UTF-8**, hex output **lowercase**. Every part of that is contract, not
implementation detail, because this scheme is re-implemented in PHP:

- The separator is `LF` (`0x0A`). It cannot appear in a timestamp or a nonce, so
  no field boundary can be shifted; a nonce containing one is **refused**, not
  escaped. Concatenating without a separator would let `(12, "3x")` and
  `(123, "x")` sign identical bytes — a canonicalisation collision, which is a
  signature forgery in disguise.
- The body is last and may contain `LF` freely.
- An absent body canonicalises to empty, never to the string `null`.
- UTF-8 is explicit and never the platform default: a digest taken over
  default-charset bytes differs between hosts and silently locks out every
  credential minted on the other one, but only for requests containing
  non-ASCII. The test suite re-runs the encoding assertions under a non-UTF-8
  default charset, because on a modern JVM the two spellings are otherwise
  indistinguishable.

Reference vector — `hash_hmac('sha256', "1700000000\nabc123\n{\"a\":1}", 'soulbind-test-key')`:

```
8d6c67e7d2420e18bb54aa175c4b381a661b82bd4668200d4a7f87a0f7bdbe80
```

The scheme lives in `protocol/`, is re-implemented in PHP, and is pinned by
golden vectors generated in Phase 2. TLS is assumed at the deployment layer; the
protocol does not rely on it for authentication.

## Capabilities

The vocabulary the dispatcher knows. There is no notion of a "chat connector" or
a "forum connector" anywhere in the system — those are descriptions humans use,
and they fall out of which capabilities a connector claims. That is what lets a
new integration arrive without a dispatcher change.

| Capability | Grants |
|---|---|
| `identity-provider` | Claim a platform account completed a challenge |
| `code-display` | Request a code for an account it vouches for |
| `code-entry` | Submit a code typed by an account it vouches for |
| `enforcement-point` | Ask allow/deny for an (identity, gate) pair |
| `effector` | Consume events and apply side effects |
| `audit-source` | Append connector-side events to the audit stream |
| `config-management` | Read and mutate rules, overrides and runtime config; inspect subjects; unlink |

## Operations

Each row is one operation and the capability it requires. This table is held to
`Authorizer.Operation` mechanically — it is a view of that enum, not a second
copy of the rule.

| Operation | Required capability |
|---|---|
| `hello` | *(any registered)* |
| `heartbeat` | *(any registered)* |
| `event.subscribe` | *(any registered)* |
| `attest` | `identity-provider` |
| `code.issue` | `code-display` |
| `code.redeem` | `code-entry` |
| `decide` | `enforcement-point` |
| `audit.push` | `audit-source` |
| `rule.get` | `config-management` |
| `rule.set` | `config-management` |
| `override.get` | `config-management` |
| `override.set` | `config-management` |
| `config.get` | `config-management` |
| `config.set` | `config-management` |
| `connector.list` | `config-management` |
| `subject.inspect` | `config-management` |
| `identity.unlink` | `config-management` |
| `audit.query` | `config-management` |

*(any registered)* means any credential that resolves to an **active**
registered connector. It never means unauthenticated: a caller with no
credential is refused before this table is consulted, and a suspended connector
is refused every operation including `heartbeat` — suspension that still allows
a heartbeat is suspension in name only.

`effector` grants no request operation of its own. An effector *receives* events
and acknowledges them; the acknowledgement operation is defined with the event
transport. A test asserts that this is the only capability in that position, so
an ungated capability cannot appear by accident.

Refusals name their reason (`unknown-credential`, `suspended`,
`missing-capability`) and, for the last, the capability that was missing — an
operator can then act on it rather than guess.

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
