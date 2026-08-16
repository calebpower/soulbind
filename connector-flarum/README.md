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

## What is here

| Part | What it does |
|---|---|
| `src/Protocol/` | Link-code normalisation and HMAC signing, re-implemented. Held to the game side by the golden vectors |
| `src/Policy/` | `Decision` and `Effect` as this side sees them |
| `src/Client/` | The protocol client, the decision cache and the fail mode. `Transport` is an interface so none of it needs a socket to test |
| `src/Webhook/` | The inbound webhook: signature, clock and replay checks, and the nonce store behind them |

### The rules that are shared, not merely similar

A forum and a game server that disagree about what an outage means is one person
let in on one and turned away on the other, at the same moment, for the same
reason. So these are restated here deliberately, and each is asserted:

- **A refusal is not an outage.** Core answering "no" is final: it never
  consults the cache and never reaches the fail mode. Core *not answering* falls
  back to the cache, then the fail mode. Collapsing the two turns "you may not"
  into "try again later".
- **Anything that is not a protocol envelope is an outage.** A proxy error page
  is not a policy decision.
- **The fail mode defaults to closed**, and only an exact `open` opens it. A typo
  must never be the thing that opens a gate.
- **A fail-mode denial blames the system.** Somebody refused because a server
  they have never heard of is unreachable should not be told they are not
  allowed.

## Building and testing just this module

The whole suite — the cross-language vectors and this module's own unit checks —
runs with **no dependencies at all**:

```sh
php tests/run-checks.php            # the ordinary run
php tests/run-checks.php --hostile  # under a non-UTF-8 internal encoding
```

They need only `mbstring`, `hash` and `json`. That is deliberate. An oracle whose
whole purpose is to be run from both languages must not require a toolchain to
be installed first, or it stops being run and its silence reads as agreement —
and once a runner exists for the vectors, there is no reason for the rest of the
suite to be less runnable than its most important part.

`reaper test` runs both passes in a digest-pinned `php:8.4-cli` image before it
starts anything else, so a cross-language disagreement fails in seconds.

The full suite, once `composer install` has run:

```sh
composer install
vendor/bin/phpunit -c phpunit.xml            # ordinary
vendor/bin/phpunit -c phpunit-hostile.xml    # hostile charset
```

**`composer install` currently fails on this workstation**: PHPUnit 11 requires
`ext-xmlwriter`, which the system PHP does not have. It is recorded in
`docs/STATUS.md` as an owner prerequisite. Nothing is blocked by it — PHPUnit is
a *second entry point* to the same checks, not additional coverage.

### One implementation, two entry points

Every assertion lives in a `*Checks` class — `tests/VectorChecks.php`,
`tests/CacheChecks.php` — and each has two callers: `tests/run-checks.php` and a
PHPUnit class. Neither restates the assertions, because two copies drift and the
copy run less often drifts further while still looking like coverage.

The runner reflects over each `*Checks` class, enumerates every public check, and
refuses to pass unless **both** entry points invoke every one. Without that, a
check added to one runner and forgotten in the other fails silently — the suite
still passes and the gap looks like coverage. Adding a check class means adding
one line to the runner's `$suites` table; a class with no PHPUnit counterpart, or
one that declares no checks, is itself a failure.

## The seam this module sits behind

The wire contract is `docs/protocol.md`, and the golden vectors in `vectors/`
are what hold this implementation to it. Neither side of the corpus is
authoritative over the other: when they disagree, one of the two is wrong and
the disagreement is the finding.

That is not hypothetical. Both implementations shipped a link-code
normalisation defect in which Unicode case folding turned characters *outside*
the alphabet into valid codes — and they disagreed about which ones, so a code
this connector accepted the game side refused. See `docs/DECISIONS.md` 7.3, and
`VectorChecks::foldingCannotSynthesise`, which sweeps every code point rather
than trusting the eight rows that name the known cases.
