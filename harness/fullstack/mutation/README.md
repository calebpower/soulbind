# `mutation/`

Mutation coverage for the shell stages: **does `plan-check.sh` actually fail
when Plan's answer is wrong?**

```sh
harness/fullstack/mutation/run.sh              # every mutant
harness/fullstack/mutation/run.sh linked-false # one
```

## Why this is not just "run a mutation tool"

There is no mature mutation-testing tool for POSIX shell, and it would be the
wrong tool anyway. Mutation testing mutates *production* code and asks whether
the tests notice. **These scripts are the tests.** Mutating them asks nothing
useful — the meaningful operation is to mutate what they *observe* and require
them to complain.

So this replays a Plan response the real Plan actually sent, once unmutated and
once per entry in `mutants.txt`, and requires:

- **the control to pass** — a check that rejects everything kills every mutant
  and asserts nothing. That is not hypothetical: the walker this battery was
  built against could not match any real Plan response, so it failed on the
  recorded evidence too, and a runner without a control would have scored it
  100%;
- **every mutant to fail**, naming which assertion caught it;
- **at least one mutant to have run** — every claim here has the form "each
  mutant died", which an empty list satisfies.

## The parts

| File | Role |
|---|---|
| `run.sh` | The runner. Control, then each mutant. Exit 0 only if all three rules above hold. |
| `replay.py` | Serves `../fixtures/*.json` with **Plan's own wire behaviour** — gzip on mime type alone, ignoring `Accept-Encoding`. A replay server that skipped that would let the check pass here and fail against the real thing. |
| `mutate.py` | Applies one named mutation. Unknown names are an error, never a silent copy-through — a typo'd mutant that passes the file unchanged "survives" every run and reads as a finding about the check. |
| `mutants.txt` | The catalogue. `PlanCheckWalkerGuardTest` asserts every name here is implemented and that the list has not shrunk. |

Every mutation changes a **value**, never the shape. A mutant producing
malformed JSON would be killed by the parser rather than by the assertion, and
would say nothing about whether the assertion works.

## Adding one

Implement it in `mutate.py`, add a line to `mutants.txt`, run `run.sh`. If it
survives, you have found an assertion that cannot fail — which is the point.

The fixtures are **recorded, never hand-edited**: see `../fixtures/README.md`.
