# `connector-velocity/`

The reference game-proxy connector.

## Why this is its own module

It loads inside a server operator's JVM, which is why it targets **Java 21** —
the proxy's floor — while `core` targets 25. A guard asserts both the declared
convention and the class-file version actually emitted, because getting it wrong
produces an `UnsupportedClassVersionError` at load time, far from its cause.

## What it may know that core may not

**This is the module where platform names are allowed.** Core and `protocol` are
scanned by the platform-vocabulary guard and may not contain one; the whole
architecture depends on the dispatcher not knowing which platforms exist. A
connector is the opposite: knowing one platform deeply is its entire job.

`BedrockIdentity` is the clearest case. Bedrock clients reach a Java server
through Geyser, and Floodgate gives them a UUID in a reserved range and usually
a name carrying a configured prefix. Those are conventions of that stack, and
the connector translates them into what core understands: the same platform kind
with `flags.bedrock = true`.

Core stores those flags and returns them. It never branches on one — the moment
it does, it has learned a platform's peculiarity and the seam is gone.

## The identity is the UUID

A name prefix is configurable, can be turned off, and changes when an operator
decides it should. Treating it as the identifier is how a rename silently
reassigns an entitlement. The prefix is parsed **only** to produce a readable
display name, and the display name is never used to look anything up.

Only one leading prefix is stripped. A player legitimately named `..Alex` behind
a `.` prefix becomes `.Alex`, not `Alex`: stripping repeatedly mangles a real
name, and a mangled display name is worse than an odd one because it looks
correct.

## Floodgate is a soft dependency

The plugin detects it reflectively. `BedrockIdentity` has no Floodgate
dependency at all and never will — it is the part that must work whether or not
Floodgate is present, and must be testable without a proxy, a Geyser instance or
a network in the room.

What the fixtures do **not** prove: that Floodgate still produces these shapes.
That claim needs a real client, and it lives in the full-stack battery.

## Building and testing

```sh
../gradlew :connector-velocity:test
```

## Extension points

The plugin proper — join gate, `/link` in chat, the permissions effector, kick
messaging — arrives in its own phase. What is here now is the platform
translation it will depend on, landed early because the identity work needed it.
