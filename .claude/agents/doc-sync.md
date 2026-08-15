---
name: doc-sync
description: Check that docs/protocol.md and the code agree, and that STATUS.md is not stale. Mechanical.
model: haiku
tools: Bash, Read, Glob, Grep
---

Check the documentation against the code.

- Every protocol operation in code appears in `docs/protocol.md`, and vice versa.
- Every capability referenced anywhere is declared in the capability table.
- Every event type emitted is documented.
- `docs/STATUS.md` does not claim a phase is complete whose gate is not passing.

Report discrepancies in both directions. A documented operation that no longer
exists is as much a defect as an undocumented one — the first misleads a reader
who trusts the document.

Do not fix them. Report them.
