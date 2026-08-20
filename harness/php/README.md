# `harness/php/`

Mutation coverage for the PHP extension, using **Infection**.

The same question `./gradlew :<module>:mutationTest` asks of the Java modules:
does a test *fail* when the thing it covers is broken? Sixty-one PHPUnit tests
pass today, and nothing has established that any of them would notice if `src/`
were wrong.

## Why a pinned PHAR rather than a composer dependency

`composer require --dev infection/infection` **does not resolve.** Flarum 1.8
locks `psr/log` to 1.x; Infection 0.35 requires `^2 || ^3`. No version pair
satisfies both.

That is not a defect in either package — a mutation runner has no business
sharing a lock with the code it mutates — and the PHAR is how Infection ships
for exactly this case. It is also strictly better here: **one checksum instead
of a hundred transitive packages resolved fresh.**

## Why an image is built

Infection cannot start without line coverage: it needs to know which tests cover
the line it just mutated. `php-code-coverage` 11 (what PHPUnit 11 uses) accepts
**pcov or Xdebug and nothing else** — phpdbg support was removed in version 10.
The pinned PHP image ships neither, so `Containerfile` adds pcov to it, once per
session, from the digest-pinned base.

pcov rather than Xdebug: it does line coverage and nothing else, at a fraction
of the overhead, and Infection re-runs the suite once per mutant.

## Running it

It runs as part of `reaper test`, after the PHPUnit entry point. The report is
copied into `out/infection/` so it survives the session.

**It does not run on the workstation**, and the reason is stated rather than
worked around: FreeBSD's PHP here has no coverage driver, and `ext-xmlwriter` is
absent too. Both are one `pkg install` away — `php84-pecl-pcov` and
`php84-xmlwriter` — after which

```sh
cd connector-flarum && php /path/to/infection.phar --configuration=infection.json5
```

works locally. Until then this tier is session-only, which makes its loop slow
and is a fact about this machine rather than about the tier.

## No minimum MSI

Deliberately. A threshold gets lowered the first time it is inconvenient, and a
lowered threshold is a decision about what this project permanently stops
noticing. The number goes in when there is one worth defending.
