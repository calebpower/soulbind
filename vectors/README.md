# vectors

Generated, committed golden vectors. The oracle proving the Java and PHP
implementations of the protocol agree.

> **Status: two files, consumed from Java.** The PHP consumer arrives with the
> forum connector; until then these prove one side against a third-party oracle
> rather than two sides against each other, and that is worth saying plainly.

| File | Rows | Pins |
|---|---|---|
| `link-code-normalisation.tsv` | 44 | raw input to normalised form, or rejection |
| `hmac-signing.tsv` | 13 | key, timestamp, nonce and body to signature |

## Format

Tab-separated, one case per line, `#` for comments. `\uXXXX` escapes anything a
line-oriented file cannot carry — and, deliberately, anything **invisible**: a
literal zero-width space in a vector file is indistinguishable from a typo, and
the next person to edit the file would delete it by accident.

The literal `NULL` means "absent" or "rejected", stated rather than inferred
from emptiness. An empty string is a legitimate value in both files, and
conflating the two would silently weaken every empty-input case.

## Where the signatures came from

Python's `hmac`, cross-checked against `openssl dgst -sha256 -hmac`. Neither
implementation of the protocol produced them. That matters: a vector file
generated from the Java side would prove PHP agrees with Java, which is a
weaker claim than both agreeing with a third party — and it would silently
absorb a Java-side mistake as the new expectation.

## What they pin

1. **Link-code normalisation** — raw input to normalised form, or rejection.
   The alphabet deliberately excludes characters humans confuse (`0`/`O`,
   `1`/`l`/`I`), is case-insensitive, and strips separators before comparison.
2. **HMAC request signing** — key, timestamp, nonce and body to signature.

## Why committed rather than generated at test time

Generated-at-test-time vectors prove both implementations agree with *the
generator*, which is one implementation wearing a hat. Committed vectors are a
third party both sides are checked against, and a change to one shows up as a
diff in review rather than a silently-updated expectation.

## The hostile second run

Each suite runs the vectors **twice**: once normally, once under a hostile
default charset. Normalisation that quietly depends on the platform default
encoding passes the first run and fails the second, which is exactly the defect
that would otherwise appear only on somebody else's machine.

## Regeneration

Regenerating is a deliberate act. A vector file changing in a diff is a change
to the wire contract, and should be reviewed as one.
