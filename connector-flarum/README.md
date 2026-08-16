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

## Building and testing just this module

The vector checks — the cross-language oracle this module exists to satisfy —
run with **no dependencies at all**:

```sh
php tests/run-vectors.php            # the ordinary run
php tests/run-vectors.php --hostile  # under a non-UTF-8 internal encoding
```

They need only `mbstring`, `hash` and `json`. That is deliberate. An oracle whose
whole purpose is to be run from both languages must not require a toolchain to
be installed first, or it stops being run and its silence reads as agreement.

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

`tests/VectorChecks.php` holds every assertion. `tests/run-vectors.php` and
`tests/GoldenVectorTest.php` both call into it and neither restates it, because
two copies drift and the copy run less often drifts further while still looking
like coverage.

The runner reflects over `VectorChecks`, enumerates every public check, and
refuses to pass unless **both** entry points invoke every one — otherwise a
check added to one runner and forgotten in the other fails silently.

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
