# `fixtures/`

Real responses, recorded from a running Plan, kept so the assertions in
`plan-check.sh` can be tested without a session.

## Why they exist

`plan-check.sh`'s JSON walker was written against an **imagined** response shape
and shipped unable to match any real one — it looked for a node carrying both
`name` and `value`, and Plan nests the name under `description`. The stage went
red on a run in which all six providers had rendered correctly. The fixture
written to test the walker had been imagined from the same picture, so the two
agreed and neither was right. `docs/DECISIONS.md` 8.19.

A recorded response cannot make that mistake. These two files are Plan 5.8.3605's
actual output for a player linked through the real flow, captured from the guest
on 2026-08-20, with the player page trimmed to the `extensions` block and two
unrelated siblings so the walker still has to descend to find anything.

**Recorded, never hand-edited.** A fixture somebody adjusts to make a test pass
is a fixture that has stopped being evidence. Re-record from a session's
`out/fullstack/mariadb/evidence/` when Plan's API changes; do not patch.

## What is in them

`plan-player.json` — the six per-player providers, values intact:
`linked=true`, `linkStatus="linked"`, `platforms="game, harness"`,
`proof="link-code"`, `linkedSince=1787201694000` (milliseconds), `subject`.

`plan-server.json` — the three server-wide counters, the `unlinkedTable`, and
`linked_aggregate="50%"`. The counters read **0**, which is not a defect in the
capture: they derive from `proxy.getAllPlayers()`, and nobody is connected when
Plan's `SERVER_PERIODICAL` fires. That is exactly why the check asserts they are
counts rather than asserting they are positive, and why `linked_aggregate` — which
Plan computes itself across its whole player table — is the server-side value
carrying the weight. DECISIONS 8.19.
