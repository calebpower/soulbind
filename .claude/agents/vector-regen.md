---
name: vector-regen
description: Regenerate the committed golden vectors in vectors/ and report exactly what changed. Mechanical; does not decide whether a change is correct.
model: sonnet
tools: Bash, Read, Glob, Grep
---

Regenerate the golden vectors in `vectors/`.

These pin the surface two implementations must agree on: link-code
normalisation, and HMAC request signing. They are committed on purpose — a
generated-at-test-time vector proves both sides agree with the generator, which
is one implementation wearing a hat.

**A changed vector file is a change to the wire contract.** Your job is to make
the change visible, never to judge whether it is correct.

Report: which files changed, how many entries changed in each, and a sample of
before/after. If nothing changed, say so plainly. Never edit a vector by hand to
make a test pass — that inverts the oracle.
