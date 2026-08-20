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

## Running it on a workstation

Three PHP extensions, none of them present in a default FreeBSD install:

| Extension | Needed by | Package |
|---|---|---|
| `xmlwriter` | **PHPUnit itself** | `php84-xmlwriter` |
| `pcov` | Infection's line coverage | `php84-pecl-pcov` |
| `zlib` | reading the gz-compressed PHAR | `php84-zlib` |

The first one is worth dwelling on: without `ext-xmlwriter`, `composer install`
in `connector-flarum` fails outright, so **the extension's 61 PHPUnit tests
could not run on the workstation at all** — the whole PHP tier was session-only
from Phase 7 onward, by omission rather than by anybody's decision. A one-line
change cost a full reaper session to verify. With it, the suite runs in 0.6s.

That is the anti-pattern the plan names: a cheap tier living in a session
because that is where it happened to work.

```sh
cd connector-flarum
composer install
vendor/bin/phpunit --configuration phpunit.xml
sh ../harness/php/fetch.sh ../harness/php/.build
php ../harness/php/.build/infection.phar --configuration=infection.json5 --threads=max
```

## PHPUnit and Infection are not alternatives

Infection **runs** PHPUnit — once to establish coverage, then once per surviving
mutant. It has no assertions of its own; it consumes PHPUnit's. PHPUnit measures
the code against the tests; Infection measures the tests against the code.
Line coverage measures neither: it says a line executed, not that anything
checked what it did.

So PHPUnit stays the edit-test loop and Infection is the periodic audit of it.
Infection is strictly the slower of the two by construction and can never
replace the suite it drives.

## No minimum MSI

Deliberately. A threshold gets lowered the first time it is inconvenient, and a
lowered threshold is a decision about what this project permanently stops
noticing. The number goes in when there is one worth defending.
