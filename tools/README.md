# tools

Shipped operator tools. Everything here speaks the **protocol** — none of it
touches soulbind's database.

That distinction is the module's reason for existing. The `soulbind` command has
three verbs and keeps three (`doctor`, `register`, `serve`); everything else an
operator can do is an *operation*, reachable through an admin credential and
governed by the same capability table as every connector. A tool that read the
database directly would be a second management surface with its own rules, and
those rules drift. These hold an admin credential and are refused by core
exactly as a connector would be.

| Tool | What it does |
|---|---|
| `rpc.sh` | One signed call to core. Prints the response payload; exits non-zero on refusal. |
| `audit-export.sh` | Exports the audit log, completely, as JSON Lines. Resumable. |

## `rpc.sh` is the only implementation of the signing

Deliberately. Three copies of an HMAC canonical form are three chances to drift
from the thing the golden vectors exist to keep identical, and at one point
there were three. Anything here or in `harness/` that needs to make a signed
call goes through this file.

The one exception is `harness/fullstack/fuzz-live.sh`, which signs for itself
because it sends deliberately malformed bodies that `rpc.sh` refuses before they
reach the wire. That is the whole of the exception.

## `audit-export.sh`

```sh
tools/audit-export.sh <core-url> <admin-credential> [after-sequence] > audit.jsonl
```

Needs `config-management`, like the other administrative operations. Writes one
JSON object per line, oldest first, and prints a summary to stderr ending with
the sequence to resume from.

It **pages**, because `audit.query` is bounded server-side — an unbounded read
from an authenticated endpoint is a way to exhaust memory — so reading an
unbounded log means asking repeatedly. Core says whether more rows remain and
where to resume; the tool loops until it says no.

Pass the printed sequence back as the third argument and only what happened
since comes back. That is what makes it a nightly archive rather than a
whole-log dump every night.

JSON Lines rather than one array so that an interrupted export is still a
readable file of whole rows up to the interruption.

### What it cannot tell you

It cannot detect a core that lies about `more`. A core claiming the log ends
after one page is indistinguishable from a log one page long, and no client-side
check separates them. What it does instead is state how many rows it actually
got, so there is a figure to compare against what you expect.

It *does* refuse a core that reports more rows without advancing the cursor,
rather than looping forever and filling the archive with repeats.

## Requirements

`python3` and a POSIX shell. Both are present on a stock Ubuntu 26.04.

## Tests

`harness/tools/audit-export-smoke.sh` runs a control against a real core with a
log longer than one page, then three mutants of a core that lies about its
paging. It is a `reaper test` stage.
