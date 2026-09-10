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

## What it says to a player is the operator's, not ours

Every player-facing string is a [MiniMessage][mm] template under `[messages]`,
because a server whose every other message is formatted should not get this one
plugin's output in flat white — and because changing the wording should be an
edit, not a rebuild. All of them are optional; unset means the built-in default.

```toml
[messages]
prefix      = "<dark_gray>[<aqua>soulbind<dark_gray>]<reset> "
code        = "<prefix><gray>Your link code is <white><bold><code></bold><gray>. Enter it on the other platform to finish linking. It expires in <white><expires><gray>."
linked      = "<prefix><green>Linked. <gray>Your account is now connected to <white><count><gray> other <plural>."
failure     = "<prefix><red><reason>"
usage       = "<prefix><gray>Usage: <white>/link<gray>, or <white>/link CODE<gray> to finish."
playersonly = "<prefix><red>/link is for players."
unavailable = "<prefix><red>Linking is not configured on this proxy."
```

`<prefix>` is available in every one of them, so branding is a single edit.
`gate.kickmessage` is parsed the same way and takes **no** prefix — a kick
screen is a full page, not a chat line. Text with no tags in it renders as
itself, so an estate that never wanted colour sees exactly what it saw before.

**Values are inserted unparsed, and that is a correctness property.** `<code>`,
`<count>`, `<plural>`, `<expires>` and `<reason>` are substituted as literal
text, never as markup. `<reason>` is quoted from core's own refusal and can name
an account somebody typed; parsing it would let a player choose what another
player's screen says.

A tag that is not a tag renders as the characters you wrote — MiniMessage is
lenient, and this connector leaves it that way. A typo shows up on screen where
the person who made it will see it, and no message is ever silently replaced.

[mm]: https://docs.advntr.dev/minimessage/format.html

## Building and testing

```sh
../gradlew :connector-velocity:test
```

## Extension points

The plugin proper — join gate, `/link` in chat, the permissions effector, kick
messaging — is here and shipped. `BedrockIdentity` remains usable on its own,
which is why it has no dependency on any of it.
