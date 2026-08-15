# `config/`

The one place soulbind reads a configuration file.

## Why this is its own module

Both the dispatcher and every connector need to read configuration, and they
must read it *the same way* — same unknown-key handling, same override rules,
same redaction. Two loaders would be two answers to "is this key a typo or a
feature", and the answers would diverge quietly.

The specification places this loader in `connector-sdk`. It lives here instead,
because core reusing it from there would mean the dispatcher depending on the
connector runtime — inheriting transports, retry and the decision cache it has
no use for, and inverting the seam that keeps client-side machinery out of the
server. Recorded as departure 4 in the [root README](../README.md).

It targets **Java 21**, not 25: the loader is shared with modules that load
inside a server operator's JVM, and the lower floor is the one that has to hold.

## The seam

`tomlj` is declared `implementation`, never `api`. No consumer of this module
gains a TOML parser on its compile classpath — they get `Config`, `ConfigKey`
and `ConfigLoader`, and nothing else. A guard asserts that `config` is the only
module in the build declaring a TOML parser at all, which is what makes "one
shared loader" mechanically true rather than a convention people are asked to
respect.

A sibling guard asserts no YAML parser enters any module's graph. The point is
not that YAML is bad; it is that two formats means two loaders, two sets of
parsing edge cases, and a question at every new file.

Where a host platform imposes its own convention, the host wins — a forum
extension stores its settings the way the forum does. This module is for files
soulbind owns.

## What it guarantees

**Unknown keys are rejected.** A misspelt key that is silently ignored is the
most expensive configuration bug there is: the setting looks present, the
default is used, and the symptom appears somewhere unrelated. When a rejected
key is close to a real one, the message names it — but only within edit distance
2, because confidently suggesting an unrelated key sends an operator to change
something that was already correct.

**Every problem is reported at once.** An operator who has to restart the
service to discover the next error stops reading the message and starts
guessing.

**The environment overrides the file.** `storage.password` is overridden by
`SOULBIND_STORAGE_PASSWORD`. Key paths are lowercase alphanumeric segments
separated by dots — no underscores, no hyphens — precisely so this mapping is
injective. Allowing underscores would let `a.b_c` and `a_b.c` collide on one
variable, and an operator setting a secret would silently configure the wrong
thing. Refusing the character is cheaper than detecting the collision.

**Booleans are strict.** `yes`, `1` and `on` are refused. `Boolean.parseBoolean`
maps every non-`true` string to `false`, which would turn a typo into a silently
disabled feature — the precise class of bug this loader exists to prevent.

**Secrets are redacted.** `describe()` and `toString()` both route through the
same redaction, because the dangerous path is the accidental one: a log line, a
debugger transcript, an exception that interpolates the object.

## Using it

```java
static final ConfigKey PORT =
        ConfigKey.required("server.port", Type.INTEGER, "port to bind");
static final ConfigSchema SCHEMA = ConfigSchema.of(PORT);

Config config = ConfigLoader.load(Path.of("soulbind.toml"), SCHEMA);
int port = config.getInt(PORT);
```

Accessors take a `ConfigKey`, not a string, so code cannot read a key the schema
does not declare — the same rule the file is held to, applied to the caller, so
the two cannot disagree about what exists.

An optional key is read with `find*` and returns `Optional`. Reading one with
`get*` is a programming error rather than a silent default: absence is a
decision the caller has to make, not one the loader makes for them.

## Building and testing

```sh
../gradlew :config:test
```

## Extension points

`ConfigSchema.merge` composes schemas, for a component that carries another's
configuration. Merging two schemas that declare the same key differently is
refused — two components disagreeing about what a key means is a bug, and
whichever loaded first would otherwise win silently.
