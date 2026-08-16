# `connector-discord/`

The reference chat connector.

## The seam is `ChatSurface`

Two implementations: the real client library, and `ScriptedSurface`. The
connector's logic — validating input, calling core, deciding what to say,
granting a role — runs identically against both, so all of it is exercised in
milliseconds without the platform in the room.

**Protocol-faithful fakery of the platform's gateway is out of scope.** Faking a
wire protocol means maintaining a second implementation of somebody else's
product: it rots the moment they change it, and it tests nothing this connector
owns. The seam is at the operations the connector actually performs.

`ScriptedSurface` ships in `src/main`, not `src/test`, because the full-stack
battery drives it from another process. It is a test double the way an in-memory
database is — a real implementation with a different backing store — and it
imports the connector's real logic rather than re-implementing any of it.

## Two gates, and they are not the same gate

| | Answers |
|---|---|
| **Capability** | what this *connector's credential* may ask core for |
| **Platform permission** | which *humans* may ask this connector |

A connector holding `config-management` would otherwise let any member of a chat
server rewrite policy — the capability model being correct and the deployment
being wrong.

The platform check runs **before** core is asked. Asking first and refusing on
the answer lets an unprivileged member probe policy by reading refusals, and
spends a round trip doing it.

An administrator is still subject to the capability gate. Both, not either: a
server administrator cannot grant this connector something core did not.

## Every reply is private

A link code in a public channel is a code anybody can redeem, and the person who
asked would not know somebody else took it. A test walks every registered
command and asserts nothing it says is public.

## Idempotence, twice

The SDK's `IdempotentApplier` stops the same **event** being applied twice,
which at-least-once delivery guarantees will happen. The connector's own
`hasRole` check stops the **platform** being asked to grant a role somebody
already holds — which happens for reasons unrelated to delivery, such as an
operator granting it by hand.

Both are asserted by counting *calls*, not resulting state: the state is
identical either way, which is precisely why state cannot show it.

## Acknowledging

Only after applying, and only up to the last event that applied cleanly.
Acknowledging first would turn a delivery this connector failed to act on into
an event it never sees again — and the role would simply never appear, with
nothing anywhere saying why.

A throw stops the batch rather than continuing, because a later event's meaning
can depend on an earlier one: a revoke undoing a grant that never happened.

## Building and testing

```sh
../gradlew :connector-discord:test
```

## The manual smoke

One step is manual and named as such: a real run against a throwaway server,
recorded in `docs/STATUS.md`. It is **evidence, not a tier** — a check that needs
somebody to create an account and click through a consent screen is not a check
that runs, and calling it one would be the dishonesty the methodology exists to
prevent.
