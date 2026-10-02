# `connector-forge/`

The git-forge connector: it is the only door through which a forge account can
be created, and it opens that door for a person whose estate identity core has
already verified.

## Why this is its own module

A connector is an out-of-process integration speaking the connector protocol. It
is separate because it runs in a runtime core does not control, on its own
release cadence, and because a reference connector's job is to prove the seam is
real: if adding a platform required a core change, the architecture would be a
claim rather than a fact. This one adds **no** protocol operation — it is built
entirely from `code.redeem`, `decide`, `identity.describe`, `event.subscribe`
and `event.ack`, which is the strongest available evidence that the seam was
designed wide enough.

It is also the module that proves the *host cannot be entered* case. Every other
connector so far runs inside the thing it gates, or inside a plugin host that
does: the proxy loads a jar, the forum loads an extension, the dashboard loads a
provider. A git forge is a single compiled binary with no extension surface at
all, so this connector stands beside its host rather than inside it and reaches
it over the host's own administrative interface. Had the protocol assumed a
connector could always observe its host from within, that assumption would have
survived three connectors and failed on the fourth.

## Dependencies

`connector-sdk` (and through it, `protocol`, `policy` and `config`). Never
`core`.

A connector that could import core would be tempted to reach into the identity
graph directly, and the moment one does, core stops being the single authority.

## Release level: Java 25

It is a standalone daemon, like `connector-discord`, not a plugin loaded into a
server operator's JVM — so the 21 floor that binds `connector-velocity` and
`connector-plan` does not apply and there is no reason to target below the
toolchain. A guard asserts both the declared convention and the class-file
version actually emitted.

## The platform kind is `forge`, and it is not negotiable at run time

Fixed at construction, never taken from a caller. A connector that accepted its
own kind as input could assert identities on any platform, which is the whole
authority the capability model exists to divide up.

`forge` is the kind; `forgejo` is one implementation of it. The distinction is
the same one `forum` and `flarum` already draw, and it is why the vocabulary
guard forbids the product name and not the kind.

## What this module may not become

An authentication provider. It establishes *that* a person holds a verified
estate identity and asks core whether that is enough; it does not verify
passwords, mint sessions or issue tokens. The host owns authentication, and the
moment this module owned it too there would be two answers to "who is this" and
no way to tell which one a given request used.
