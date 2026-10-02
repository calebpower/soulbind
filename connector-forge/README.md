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

## Where the signup form lives

A listener on **loopback**, served at a **path on the forge's own hostname**
(`/soulbind/signup` by default), which the reverse proxy in front of the forge
routes here ahead of its rule for the forge itself.

Loopback because that proxy already terminates TLS for the name people type, and
a second public listener would be a second thing to get right. A path on the
existing hostname rather than a hostname of its own because a new name costs a
DNS record and a certificate to serve one form, and the path is one proxy rule.

Both the path and the port are configuration, because the path has to match the
proxy's rule and a mismatch between them is a form whose submit button 404s with
nothing anywhere saying why.

## The transport package, and why it is exempt

`transport/` is the third package admitted to `TransportSeamGuardTest`'s
exemption, after core's and the SDK's. A git forge cannot be entered — it is one
compiled binary with no extension surface — so this connector needs an inbound
listener and outbound calls to the host's admin interface, and neither can be
written without naming a transport type.

The exemption buys as little as it can. The form's checks, the mapping from an
outcome to a status, and the page's escaping are all pure, live outside that
package, and are tested as plain assertions. What is inside it is request
decoding and routing. If connector logic starts appearing in there, the
exemption has stopped being a seam and become a hole.

## What no rule can do

**Deactivate an administrator.** A gate that can deactivate the last
administrator can lock everybody out of the forge, including the person who
would go and fix it — and it would do so for an entirely ordinary reason, such
as a measure going stale. No rule an operator can write reaches that: the
effector asks the host whether an account administers it, and skips the
deactivation if so, saying as much in the log.

It is a skip rather than a refusal on purpose. Refusing would leave the event
unacknowledged forever and stall the cursor behind it, so one administrator
whose requirements lapsed would halt account management for everybody.

Activation is not blocked, because it is not the hazard — and blocking it would
leave an administrator locked out by the very guard meant to protect them. Not
knowing counts as "may not", which is the safe direction: the event comes round
again and the next pass decides it once the forge answers.

## What this module may not become

An authentication provider. It establishes *that* a person holds a verified
estate identity and asks core whether that is enough; it does not verify
passwords, mint sessions or issue tokens. The host owns authentication, and the
moment this module owned it too there would be two answers to "who is this" and
no way to tell which one a given request used.
