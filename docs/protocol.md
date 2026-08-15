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

## Transports

Two ship, because connectors come in two shapes. Both carry the same protocol
and are served by **one dispatcher** — nothing about authorization, operation
resolution or refusal wording lives in either transport, which is what stops
them developing different ideas about who may do what.

| | Socket | Signed request |
|---|---|---|
| Path | `/v1/socket` | `/v1/rpc` |
| For | Daemons that stay running | Connectors that exist only while serving a request |
| Authenticates | Once, at connect | Every request, independently |
| Credential | `Authorization: Bearer <token>` header | Same header, plus a signature |

A socket that cannot present a valid credential is **closed**, not left open in
an unauthenticated state: an open socket is a resource, and one that can never
do anything is a resource an unauthenticated peer is holding.

### Message shape

Request:

```json
{"schema": 1, "op": "hello", "id": "<uuid>", "payload": { }}
```

Response:

```json
{"schema": 1, "id": "<uuid>", "ok": true,  "payload": { }}
{"schema": 1, "id": "<uuid>", "ok": false, "error": {"code": "missing-capability",
                                                     "message": "...",
                                                     "capability": "code-entry"}}
```

`id` is chosen by the caller and echoed unchanged, so a response can be matched
to its request on a multiplexed connection. It is never interpreted by the
server — in particular it is **not** the idempotency key and **not** the replay
nonce, which are separate things meaning separate things.

An absent `payload` is equivalent to an empty one. A caller should not have to
send `"payload": {}` to say nothing.

### Refusals

Every refusal carries a machine-readable `code`. A refusal without one forces
the other side to match on prose, which breaks the first time the prose is
improved.

| Code | Meaning |
|---|---|
| `unknown-credential` | No credential, or one matching no registered connector |
| `suspended` | Registered, but suspended |
| `missing-capability` | Active, but lacking the capability; names it in `capability` |
| `unknown-operation` | No such operation at this schema version |
| `schema-mismatch` | A schema version this peer does not speak |
| `malformed` | Unparseable, or a required field absent |
| `bad-signature` | The signature did not match the body |
| `stale-timestamp` | The signed timestamp fell outside the freshness window |
| `replayed-nonce` | The nonce has been seen before inside the window |
| `invalid-request` | Well-formed and permitted, but the content was rejected |
| `internal` | A failure the caller did not cause. Deliberately opaque |

**Every refusal is HTTP 200 with the reason in the envelope.** A protocol
refusal is not a transport failure, and mapping refusals onto status codes gives
every intermediary — proxy, CDN, corporate filter — an opinion about them.

`unknown-credential` covers absent, blank and unrecognised credentials alike.
Distinguishing them would tell an attacker whether a token they guessed exists.

An `unknown-operation` refusal is only ever returned to a caller that already
authenticated. Handed to an anonymous caller it would be a free oracle for
probing which operations a build supports.

### Replay protection

Two halves, and both are required. The **timestamp window** bounds how long a
captured request is useful; the **nonce store** makes it useful only once inside
that window. A window without a nonce store lets a captured request be replayed
freely until it expires; a nonce store without a window has to grow forever.

The window is symmetric: a timestamp far in the *future* is refused too, or a
captured request given a distant timestamp stays replayable indefinitely — the
window with its lid off.

Freshness and single-use are checked **before** the signature. The signature is
a keyed hash over the whole body, so verifying it first would let anyone force
unbounded work by posting large bodies with no credential at all. This does mean
a caller learns "stale" before "bad signature"; that is not a disclosure worth
defending, since the timestamp is a value the caller supplied and the clock is
not a secret.

When the nonce store reaches its ceiling it **refuses**. Fail closed: if it
cannot prove a nonce is new, accepting on that basis would silently turn replay
protection off exactly when something abnormal is happening.

### `hello`

A connector declares its name, the capabilities it claims, the platform kinds it
speaks for, and the gates it enforces. That is the whole of what core learns —
there is no registry of known integrations anywhere in the dispatcher, which is
what lets a new one arrive without a dispatcher change.

Core answers with the **intersection** of what was claimed and what the
credential was granted at registration. Claiming a capability does not grant it;
the connector learns what it actually holds at handshake rather than one refusal
at a time. Claimed names core does not recognise come back in `ignored` rather
than vanishing — a connector built against a newer protocol should be able to
see that, and an operator reading a log should not have to guess why something
is inert.

Both `hello` and `heartbeat` return core's clock, so a connector can spot skew
before that skew starts having its signed requests refused as stale.

## Linking

Symmetric by construction. A `code-display` connector requests a code for an
account it can authenticate locally; the person types it into any `code-entry`
connector; core transactionally validates TTL and single-use, resolves or
creates the subject, binds both identities, records proof methods, appends
audit, and emits events.

Either side can be display or entry. **Core never knows the pairing.**

## Audit

`audit.push` appends a connector-sourced event; `audit.query` reads the log.
They require **different** capabilities — `audit-source` and `config-management`
respectively — because a connector that can write audit events should not
thereby get a window onto everything else.

**The actor is decided by core, never by the caller.** The push payload has no
actor field at all: not "ignored if present", absent from the schema, so a
payload naming one is refused rather than silently accepted. A connector able to
name its own actor could attribute its actions to another connector, or to a
person, and an audit log whose attribution the subject controls is not evidence
of anything.

Audit is **append-only in fact rather than in policy**: the storage interface
exposes no update and no delete, so the capability to alter a recorded event
does not exist for a caller to acquire. A guard asserts that no code path grows
one, and that no migration mutates the table.

Query limits are bounded server-side whatever is asked for. An unbounded audit
query against a long-lived deployment is a way to exhaust memory from an
authenticated endpoint, and "the caller asked nicely" is not a defence.

## Events

At-least-once, idempotency-keyed, with per-connector cursors so a connector that
was down receives what it missed, in order, on reconnect. Effectors must be
idempotent; the SDK enforces key-based dedup.
