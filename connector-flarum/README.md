# connector-flarum

Reference forum connector: a PHP extension, distributed as a composer package.

> **Status: skeleton.** Content arrives in Phase 7.

## Why it lives in this repository

It re-implements `protocol/`'s surface — the link-code alphabet and its
normalisation, and HMAC request signing — in a second language. The golden
vectors in `vectors/` are the oracle proving the two agree, and **an oracle
whose two sides live in different repositories drifts**. Same repository, same
commit, one review.

## Why PHP and not the Java SDK

The forum's extension surface runs inside its request lifecycle. That is the
runtime, so that is the language. This is the case the whole architecture is
built for: the seam is a versioned network protocol, not a plugin API, precisely
so a connector can be written in whatever its host demands.

## What it must mirror exactly

- The link-code alphabet, and normalisation (trim, uppercase, strip separators)
- HMAC-SHA256 signing over `(timestamp, nonce, body)`
- Schema version handling: refusing an unknown major version is **a refusal with
  a reason**, never a silent downgrade

Each is pinned by committed vectors, run twice — once normally, once under a
hostile default charset.

## Configuration

**The host's convention wins.** Settings live where the forum keeps settings —
database-backed, through its admin panel — not in a TOML file beside it.
Fighting a host platform's configuration system is worse than the inconsistency,
and the specification says so explicitly.

This is the one place soulbind's "config is TOML" rule does not apply, and it is
a deliberate exception rather than an oversight.

## Fail-closed

When core is unreachable and no unexpired cached decision exists, the gate
denies, and the message tells the truth: the system is at fault, not the person.
Asserted by a test here, and exercised under injected transport faults by the
browser tier.
