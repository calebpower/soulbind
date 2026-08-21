# Threat model

Copyright (c) 2026 Caleb L. Power. Apache-2.0; see [LICENSE](../LICENSE).

The §14 Phase 10 pass over the protocol. Every claim below is held by a named
test, guard, or structural property — a claim held only by this document is
marked **(stated, not enforced)**, and there are deliberately few of those,
because a threat model that drifts from the code is a comfort document.

This is written for the person deciding whether to deploy soulbind and for the
person reviewing an incident on one. It states what the system defends against,
what it delegates to the deployment, and what it does not defend against at
all. The third list is the one that earns the document its keep.

---

## Assets

In descending order of what an attacker gains:

1. **Connector credentials.** A credential is a capability set. The worst one
   is `config-management`: it administers core, reads the whole audit log, and
   rotates every other credential.
2. **The identity graph.** Who is linked to whom, across platforms. Its
   *integrity* is what enforcement decisions rest on; its *confidentiality* is
   a privacy obligation to the people in it.
3. **The audit log.** The record an incident review trusts. Its value is that
   it cannot be quietly wrong.
4. **Link codes.** Each is a short-lived bearer token for "these two accounts
   are the same person". Single-use, expiring, and never written to the audit
   log.
5. **Availability of decisions.** An enforcement point that cannot ask core
   must do *something*; which something is the connector's configured fail
   mode.

## Trust boundaries

```
 operator shell ──── CLI (register/doctor/serve) ──┐
                                                    │ same process,
 connectors ──HTTP──> transport ──> dispatcher ──> core ──JDBC──> database
                        │
                        └── every arrow crossing this line is authenticated,
                            signed, and capability-checked
```

- **Connector ↔ core** is the boundary this protocol defends. Every request is
  authenticated (credential), integrity-bound (HMAC), replay-bounded
  (timestamp + nonce), and authorized (capability table).
- **Core ↔ database** is *not* an adversarial boundary. Whoever controls the
  database controls the graph; the deployment protects the database the way it
  protects core's own host. What core does defend there: the audit repository
  exposes no update and no delete (`AuditImmutabilityGuardTest` asserts no
  code path or migration grows one), so a compromised *caller* of core cannot
  alter history through core.
- **The CLI ↔ core's database** is the operator's own machine. `register`
  writes directly; whoever has that shell has everything anyway.

## Attacker models considered

| Attacker | Holds | Considered |
|---|---|---|
| Network outsider | Can reach the transport port | yes |
| Passive eavesdropper | Can read traffic between a connector and core | yes — this one is decisive for deployment guidance |
| Captured-traffic replayer | Holds a recorded request | yes |
| Compromised connector | One valid credential and its capability set | yes — the model the capability system exists for |
| Malicious code-holder | Overheard or phished a link code | yes |
| Host/database compromise | root on core's machine | out of scope: no protocol survives its own host |

---

## The transport, honestly

**The credential is the signing key, and it also travels in the
`Authorization` header of every request.** That is a deliberate design fact
with a consequence that must be stated plainly:

> Anyone who can read one request between a connector and core holds that
> connector's credential. The HMAC is not a substitute for TLS, and nothing in
> this protocol makes it one.

What the signature actually buys, given that:

- **Replay bounding.** A recorded request is valid only within the signature
  window (default 300s, bounded on both sides — a *future* timestamp is
  refused too, `SignedTransportRefusalTest`) and only once (nonce).
- **Body–credential binding.** A request body seen without its headers — a
  proxy log, a pasted bug report — cannot be submitted by someone who does not
  hold the credential.
- **No confidentiality. No protection from an in-path attacker**, who has the
  header and therefore the key.

The deployment guidance follows from this, and `docs/install.md` and the
sample config both state it: core binds loopback by default (a default that
opens a socket to the whole network is not one anybody should get by
omission — `CliTest`), and anything crossing a network goes behind a
TLS-terminating reverse proxy. `doctor` warns on a bind to every interface.

**Verification order** (`TransportServer.handleSigned`, in this order):

1. Credential resolved first — by SHA-256 hash lookup, so an unknown
   credential is refused before anything else is touched. Timing-safe in the
   sense that matters: the comparison is between digests the attacker cannot
   choose, so equality-check timing reveals nothing useful about the stored
   value.
2. Timestamp window checked both directions.
3. Nonce consumed. Unauthenticated traffic **never reaches the nonce store**
   (step 1 already refused it), so an outsider cannot burn nonces or fill the
   store.
4. Signature verified with `MessageDigest.isEqual` — constant-time, per the
   comment on `RequestSigner.verify` explaining why `String.equals` is wrong.
5. Only then is the body parsed and dispatched.

## Replay windows and the nonce store (§14's named items)

**The window.** Configurable, default 300s, load-bounded at both ends: zero
would refuse everything, unbounded would make replay protection depend
entirely on the nonce store never losing an entry. `doctor` warns above 900s.
The remedy for skew is NTP, not widening — the install doc says so where the
operator will read it.

**Bounds.** The store holds at most 1,000,000 entries and **fails closed**: at
capacity it refuses new requests rather than evicting old nonces
(`NonceStore.recordIfNew`). Eviction would silently re-open the replay window
under memory pressure — precisely when an attacker can create memory
pressure. The cost of failing closed is honest: a *credential holder* can
flood the store and deny service to other connectors for up to the retention
period. They are authenticated, named in every log line, and can be rotated
away in one operation; that trade is taken knowingly.

**Retention** is twice the signature window (`NonceStore.retentionFor`,
mutation-checked in both languages — the PHP verifier keeps the equivalent
bound with its own off-by-one accounted for), so a nonce outlives every
timestamp that could lawfully accompany it.

**The honest gap: restarts.** The store is in-memory and per-process. A core
restart empties it, so a request captured moments before a restart is
replayable for the remainder of its window afterwards. **(stated, not
enforced)** — accepted because the persistent alternative buys little: the
replayed request still authenticates as the same connector and still passes
capability checks; what replay can do is repeat an operation, and the
operations with teeth are separately idempotent or single-use (a code redeems
exactly once — proven under concurrency on both backends, Phase 2 gate). An
operator who considers even that too much can front core with a proxy that
holds connections through restarts.

## Credential lifecycle (§14's third named item)

- **Minting.** `SecureRandom`, printed exactly once, only the SHA-256 hash
  stored. A database read yields no credentials. Registration is audited
  (`connector.registered`, actor `cli`) — landed this phase after the install
  gate rehearsal caught it missing.
- **Rotation.** `connector.rotate`, `config-management`. Replaces the stored
  hash — one column, so no second live credential can exist structurally.
  **No overlap window**: the case rotation exists for is a credential in the
  wrong hands, and a grace period is exactly what is not wanted then. The old
  credential fails on the next request (`CredentialRotationTest`, all five
  assertions mutation-checked). Audited *before* the plaintext is returned, so
  a rotation that reached the caller and not the log cannot happen.
- **Self-rotation** works — the admin credential that leaked is the one most
  worth rotating, and authentication precedes dispatch, so the request is
  judged under the old credential and the response carries the new one. Also
  asserted, because moving authentication after dispatch would break it
  silently.
- **Procedure** (the operator-facing half, in `docs/install.md`): rotate
  immediately on suspicion; reconfigure the connector; a lost response means
  rotate again, never recover — core cannot show a credential twice.

## Authorization

Every operation requires a capability, restated three ways that are checked
against each other: the `Authorizer` table (what runs), the
`AuthorizationMatrixTest` contract (an independent restatement — a grant
cannot change in one place only), and `docs/protocol.md`'s table
(`ProtocolDocSyncGuardTest`, both directions). The principle is deny-by-
default: an operation absent from the table is unreachable, and
`ProtocolFuzzTest` throws unknown operations at the dispatcher to hold it.

A compromised connector is therefore bounded by its grant. The harnesses'
grants are themselves checked minimal-and-sufficient (`credential-smoke.sh`,
`PrincipalDriftGuardTest`), so the shipped examples do not teach
over-granting.

Two deliberate authorization decisions worth restating:

- A connector cannot rotate any credential, including its own, without
  `config-management` — rotation takes a *name*, so the capability that allows
  self-rotation allows rotating anybody.
- The audit-write capability (`audit-source`) and the audit-read capability
  (`config-management`) are different, so a connector that reports events does
  not thereby get a window onto everything else.

## The audit log as evidence

For the log to be worth reading during an incident:

- **Append-only in fact**: no update/delete exists to acquire
  (`AuditImmutabilityGuardTest`, covering code paths and migrations).
- **Attribution is core's**: the push payload has no actor field at all — a
  payload naming one is refused, not ignored (`AuditWireTest`). A connector
  cannot attribute its actions elsewhere.
- **Truncation is visible**: every query response says whether it stopped
  early and where to resume (`AuditExportTest`, `AuditWireTest`), so an
  export that looks complete is complete — the failure mode where it silently
  is not was closed this phase.
- **Codes are never logged**: an audit reader with `config-management` must
  not thereby hold a list of live link codes (protocol doc, held by the
  linking tests).

## Link codes

Single-use (exactly-once proven under concurrency, both backends), expiring
(TTL bounded at load), unpredictable (`SecureRandom` over the vocabulary the
golden vectors pin). The single-use decision is an atomic *claim*, and the
ordering around it is deliberate (`LinkingService.redeem`): a refusal **before**
the claim — same account, malformed request — leaves the code live for the
person to try correctly; a refusal **after** it (the accounts turned out
already linked) keeps the code claimed, because re-offering a used code would
let it be tried against a different account. So an overheard code is worth at
most one claim within its TTL, and its redemption is audited with both
identities — the Phase 9 tier models exactly this split (`codeConsumed`).

### Guessing codes

Bounded since Phase 10, and it was not before. Eight characters from a
twenty-eight character alphabet is 3.8×10¹¹, which makes guessing a
*particular* code hopeless — but an attacker does not want a particular one.
**Any live code links their account to a stranger's subject**, so the target is
the whole outstanding set, and nothing limited the attempt rate.

`RedeemThrottle` counts wrong guesses per redeeming account, over a window, and
refuses past a limit. Only "no such code" counts: expired, already-redeemed and
already-linked all mean the caller had a real code. A success clears the record.

It fails **open** at capacity — evicting rather than refusing — which is the
opposite of the nonce store one section above, and deliberately so: refusing at
capacity would let an attacker who fills the map deny linking to everybody.

Per-account is the finest grain core can manage, because core knows nothing
about IP addresses or sessions. Connectors are expected to add limits in terms
their platform understands; this is the floor beneath them, and the floor is
what catches an attacker spreading attempts across several connectors.

## Injection and hostile input

The T5 suite runs injection specs cross-engine against both backends; the
storage layer is parameterized throughout (`StorageSeamGuardTest` keeps SQL
from leaking out of the storage package, which is also what keeps ad-hoc
string SQL from appearing at call sites). Four-byte UTF-8 survives the round
trip on a latin1-hostile server or core refuses to boot
(`SchemaCharsetTest` + the boot-time refusal) — mangled names are an
integrity failure with a privacy edge, not a cosmetic one. Hostile wire input
is fuzzed at two depths: unit (`ProtocolFuzzTest`) and against a populated
live deployment (Tier 7, `fuzz-live.sh`), with the oracle "no 5xx, always an
envelope, never `internal`, alive after".

## What this protocol does not defend against

Stated so nobody reads silence as coverage:

1. **An in-path attacker on an un-TLS'd network segment.** They hold the
   credential. Deploy loopback or behind TLS; the doctor and the docs both
   push there, and that is the extent of the protocol's reach.
2. **Host or database compromise.** Out of scope, as it is for every protocol.
3. **A malicious operator.** `config-management` is root of this system, and
   the CLI writes to the database directly. The audit log records operator
   actions (`cli` actor) but an operator with database access can stop that
   mattering.
4. **Denial of service by an authenticated connector** — nonce-store flooding
   (bounded, fail-closed, attributable), or simply hammering operations.
   Rate limiting is a reverse-proxy job; core's contribution is that every
   request is attributable to a connector that can be rotated away.
5. **Social engineering of the linking ceremony itself** — a person reading
   their code aloud to someone else. The code is single-use and short-lived,
   which bounds but does not eliminate this; the redeem-must-match-person
   check exists at the connector layer where the person is visible, not in
   core, which cannot see people.
