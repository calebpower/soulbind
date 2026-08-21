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
| `link-state-reader` | Read link state for an identity, and nothing else. The only capability that grants no mutation |
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
| `event.ack` | *(any registered)* |
| `attest` | `identity-provider` |
| `code.issue` | `code-display` |
| `identity.describe` | `link-state-reader` |
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
| `connector.rotate` | `config-management` |
| `subject.inspect` | `config-management` |
| `identity.unlink` | `config-management` |
| `audit.query` | `config-management` |

*(any registered)* means any credential that resolves to an **active**
registered connector. It never means unauthenticated: a caller with no
credential is refused before this table is consulted, and a suspended connector
is refused every operation including `heartbeat` — suspension that still allows
a heartbeat is suspension in name only.

`event.ack` is unprivileged like the subscribe it pairs with: a connector can
only move its **own** cursor, because the connector id comes from the credential
and never from the payload, so there is nothing here another capability would
protect.

`effector` therefore still grants no request operation of its own — it describes
a connector that *consumes* events, and consuming is not a request. A test
asserts it is the only capability in that position, so an ungated capability
cannot appear by accident.

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
connector. Either side can be display or entry, and **core never knows the
pairing** — it sees a code issued for one account and later redeemed by
another, and both sides are the same shape.

That symmetry is not a convenience. It is what stops whichever platform was
implemented first from becoming the de-facto root of identity.

### The flow

1. `code.issue` mints a code bound to `(platform_kind, platform_id)`. The
   connector vouches for the account locally; core does not verify it and could
   not, having no way to authenticate a platform account itself.
2. The person types it into any `code-entry` connector, which calls
   `code.redeem` with its own account context.
3. Core normalises, checks, **claims**, and only then links.

The code is normalised **once, in core**. A connector normalising first and core
normalising again would be two chances to disagree about what a typed code
means.

A connector may still normalise locally to reject nonsense before a round trip,
and the golden vectors exist to keep that identical to core's rule rather than
merely similar.

### What normalisation is

The rule is part of the wire contract, because two implementations that
normalise differently accept different codes.

1. **Strip** the ASCII separators `-` `_` `.` `:` `,`, every Unicode whitespace
   character, and the three invisibles a copy-paste from a web page drags along:
   `U+00A0`, `U+200B`, `U+FEFF`. The last three are not whitespace to a general
   whitespace test, which is why they are named as well.
2. **Uppercase ASCII `a`–`z`, and nothing else.**
3. **Reject** unless every remaining character is in the alphabet
   `23456789BCDFGHJKMNPQRSTVWXYZ` — no `0`/`O`, no `1`/`l`/`I`, no vowels, so
   look-alikes stay apart and a short code cannot spell a word.

**Reject, never repair.** Mapping `O` to `0` would silently redeem a *different*
code and link the wrong account, with no error anybody can see.

Step 2 says ASCII because that rule was learned the hard way. Unicode case
mapping does not stay inside its input set: `U+017F` (long s) uppercases to `S`,
and a whole-string fold also expands `U+00DF` to `SS` and the `ﬀ`/`ﬅ`/`ﬆ`
ligatures to `FF`/`ST`. Applied before validation, that turns a character nobody
may type into a code somebody else holds — the repair step committing the exact
harm the reject-never-repair rule forbids. Both implementations shipped it, and
disagreed about which characters were affected. See `DECISIONS.md` 7.3.

ASCII-only mapping is the only rule that cannot invent an alphabet character
from a non-alphabet one. It is also locale-independent, which matters
separately: a Turkish locale maps `i` to `İ`, so a locale-sensitive uppercase
would stop validating codes on a correctly-configured Turkish server.

Held by the vectors in `vectors/link-code-normalisation.tsv`, consumed by both
languages, and by an exhaustive sweep of every code point in each.

### Single use

One statement carries the decision:

```sql
UPDATE link_code SET redeemed_at = ?, redeemed_by_connector = ?
 WHERE code = ? AND redeemed_at IS NULL
```

One row updated means this caller claimed it; zero means somebody else already
had. No read-then-write, no lock, and no dependence on an isolation level that
differs between backends — the version that worked in testing would otherwise be
the version that failed in deployment.

**Expiry is deliberately not part of that predicate.** A caller redeeming an
expired code is told it expired, not told it was already used: different
problems with different fixes, and collapsing them sends the person to ask the
wrong question. An expired code is also not consumed, so the reason stays
truthful on the next attempt.

### Refusals

| Refusal | Means |
|---|---|
| `unknown-code` | No such code — including one that failed normalisation |
| `expired` | Issued, but its lifetime has passed |
| `already-redeemed` | Somebody claimed it, possibly this caller twice |
| `same-account` | The redeeming account is the one it was issued for |
| `already-linked` | Both accounts already belong to people |

A code is **rejected, never repaired**. Mapping `O` to `0` would silently redeem
a *different* code and link the wrong account, with no error anybody could see.

`same-account` exists because linking an account to itself would produce a
subject with one identity and the appearance of a completed link — the person
believes they are linked and no gate agrees.

There is **no merge**. Two accounts that already belong to different people is a
refusal, not a guess: merging needs a rule for every conflicting field, and the
first time it ran on the wrong pair it would be unrecoverable.

### Unlink

Hard with respect to policy — the identity row is deleted, and a decision asked
one transaction later sees it gone. Soft with respect to audit — the rows naming
it remain forever, because what happened still happened.

Re-linking the same account later creates a **new** identity. A resurrected row
would silently carry its old verification date, and policy asking "how long has
this been proven" would get an answer about an account that had been unlinked in
between.

### Attest

`attest` is how an `identity-provider` connector says "I proved this account by
a means of my own". The proof *method* is recorded, not merely a boolean, so a
gate can accept a link code for one thing and demand something stronger for
another. An account nobody has seen before gets a subject of its own — one
identity is a person known on one platform, which is the honest representation
of what was asserted.

**The code itself is never written to the audit log.** Until it is redeemed or
expires it is a live secret, and an audit log readable by anyone holding
`config-management` would otherwise be a list of working codes.

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

### Reading a log longer than one page

Because the limit is not negotiable, every `audit.query` response says whether
it stopped early:

| Field | Meaning |
|---|---|
| `entries` | the matching rows, **oldest first** |
| `more` | whether at least one further row matches beyond this page |
| `lastSequence` | the highest sequence in this page, or the cursor that was passed in when the page is empty |

and the request accepts `afterSequence`, which returns only rows above it.
Together they are the export: pass `lastSequence` back as `afterSequence` until
`more` is false.

`more` is on **every** response, not only on exports. Without it a caller cannot
tell the whole log from the first page of it — and since the limit is silently
clamped at 1000, asking for everything and believing the answer was the easiest
mistake this operation offered. An export built on that looks complete and is
not, which is worse than having no export.

The cursor is a **sequence**, not an offset, and that is what makes paging safe
while the log is being written: `seq` is monotonic and rows are never mutated or
deleted, so a page cannot shift under a reader the way an offset can. It also
makes the export resumable across runs — keep the last sequence, and the next
run reads only what happened since.

`tools/audit-export.sh` is that loop, writing JSON Lines. It is a **protocol
client**, not a management command: it holds an admin credential and is
authorized by the same capability table as everything else, rather than reading
the database behind core's back.

## Decisions

`decide` asks whether an identity may pass a gate. The **identity**, not the
subject: a connector asking usually knows only the account in front of it, and
requiring a lookup first would make every enforcement point do two round trips
to answer one question.

Gates are recorded on first use. A connector asking about one is declaring that
it exists, and an operator cannot write a rule for a gate they cannot see.

| Field | Means |
|---|---|
| `effect` | `allow` or `deny` |
| `reason` | stable machine-readable code — match on this, never on `detail` |
| `detail` | human-readable, for logs and for showing a person |
| `ttlSeconds` | how long a connector may cache this |
| `missingKinds` | what the subject would need, present on a denial |

### Reasons

`no-rule`, `requirements-met` and `grace` produce `allow`. `not-linked`,
`missing-kinds` and `default` produce `deny`. `override` produces either.

### Precedence

Overrides beat rules; **deny beats allow**; no rule means allow; grace is
checked after establishing that requirements are unmet.

An unconfigured gate allows, because a gate nobody configured is a gate nobody
asked for. Note that a **connector** whose core is unreachable *denies* — those
look contradictory and are not: here there is nothing to enforce, there
enforcement has failed.

### `identity.describe` versus `subject.inspect`

They return the same thing and differ only in who may ask.

`subject.inspect` needs `config-management`: it is an operator looking at
anybody. `identity.describe` needs only `code-display`, because it answers
"what is this account linked to" for a connector asking on behalf of the person
in front of it.

That distinction exists because the alternative is worse. A chat surface needs
to answer "what am I linked to", and granting it `config-management` to do so
would let it rewrite every rule — the capability model being correct and the
deployment being wrong. A connector that may mint a link code for an account
already vouches for that account, and already learns its graph the moment a link
completes; this grants no reach it did not have.

### What "verified" means

A rule naming required kinds is satisfied only by **verified** identities.
Binding an account is not proving it, and a gate that accepted a bound but
unproven identity would accept a claim. A denial names the kind even when the
account is of that kind — what is missing is the proof.

`linked` means **two or more** identities. A subject with one is a person known
on one platform, which is what an attestation produces; calling that linked
would let a gate demanding a link be satisfied by the very account asking.

### Caching, and failing

`ttlSeconds` is carried in the response so cache behaviour is core-tunable
without redeploying connectors. A grace allowance carries a **shortened** TTL,
clamped so it cannot outlive the grace window — otherwise the gate stays open
for the remainder of the cache period, advisory rather than enforced.

A TTL of zero means *do not cache this*.

**Fail-closed is the default.** A connector that cannot reach core and holds no
unexpired cached decision denies, and the message it shows says the *system* is
at fault rather than the person. Fail-open is spellable for gates whose cost of
wrongly denying exceeds the cost of wrongly allowing, but it is always a visible
configuration line — and only the exact word `open` selects it, because a typo
in a fail-mode must never be the thing that opens a gate.

## Events

**At-least-once, and said plainly.** Exactly-once across a network does not
exist; what exists is at-least-once plus an idempotency key. Being honest about
which one is on offer is how a connector author learns they must dedup — and the
SDK enforces it rather than trusting them to remember.

### The outbox

An event is written **in the same transaction as the change that caused it**.
Calling a subscriber inline instead would make every mutation's latency depend
on the slowest subscriber, and an event emitted by a call that failed is an
event nobody hears about. There is no delete: an event removed is an event a
connector that was down will never receive.

### Cursors

One per connector, not a global position — a shared one would mean whichever
subscriber was fastest decided what the others never saw.

**A cursor advances on acknowledgement, never on send.** Advancing on send turns
a delivery lost in flight into an event nobody will ever receive, which is the
whole failure the outbox exists to prevent. Acknowledgement is cumulative: a
connector that applied 1..50 says `50` once, and a cumulative acknowledgement
cannot leave a hole the way a per-event scheme can.

A cursor never moves backwards. Re-delivery is survivable — that is what the
keys are for — but a buggy acknowledgement replaying the entire history is a
very different amount of work arriving without warning.

### Event types, v1

| Type | Emitted when |
|---|---|
| `identity.linked` | Two platform accounts became one subject |
| `identity.unlinked` | An identity was removed from its subject |
| `identity.verified` | An identity established proof |
| `subject.requirements-met` | A subject now satisfies a gate it previously did not |
| `subject.requirements-lost` | A subject stopped satisfying a gate |
| `rule.changed` | A rule changed, so cached decisions for its gate are suspect |
| `config.changed` | Runtime configuration changed |
| `connector.registered` | A connector registered |

`subject.requirements-met` is emitted **per gate**, not once per subject: an
effector granting a role needs to know which gate opened, and telling it only
that something changed would make it re-evaluate everything on every link.

Every type here appears in `EventType` and vice versa — a guard asserts it, for
the same reason as the operations table.
