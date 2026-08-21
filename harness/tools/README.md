# harness/tools

Tests for the shipped operator tools in `tools/`.

They are here rather than under `core/src/test` because what they cover is a
POSIX shell script speaking the real wire format to a real core — neither half
of which a JVM test exercises.

| Script | What it asserts |
|---|---|
| `audit-export-smoke.sh` | A control against real core, then three mutants of a core that lies about its paging |
| `audit-replay.py` | The lying core those mutants run against |
| `core-env.sh` | Sourced helper: builds and runs core on a host JDK or in the pinned toolchain container |

`harness/credential-smoke.sh` also sources `core-env.sh`. Both are wired into
the `run` verb of `.reaper.toml`, together well under a minute.

## The mutants are in the observations, not the script

Mutating `audit-export.sh` would only prove that `audit-export.sh` can be
broken. What matters is whether its completeness checks fire when **core**
misbehaves, so `audit-replay.py` stands in for a core and lies in three specific
ways:

| Mutant | The lie | Required outcome |
|---|---|---|
| `truncate-silently` | `more: false` while rows remain | Cannot be caught — asserted instead is that the summary states how little it got |
| `freeze-cursor` | `more: true`, full page, `lastSequence` never advances | Exit non-zero rather than loop |
| `empty-but-more` | `more: true`, no entries, forever | Exit non-zero rather than loop |

`truncate-silently` is listed as uncatchable on purpose. A mutant a tool cannot
detect is still worth running, because the alternative is believing it can.

## `core-env.sh` exists because the two machines differ

The workstation has a JDK and no podman. The reaper guest host has podman and
**no JDK** — `java` there lives only inside the digest-pinned toolchain image.
A smoke that ran gradle on the guest host failed with "JAVA_HOME is not set and
no `java` command could be found", which is why these scripts were never wired
into a session run until now.

`core_env_init <work-dir> <port> <repo-root>` picks a mode and builds core;
`core_cli`, `core_serve`, `core_stop` and `core_url` work identically either
way. The tools under test never see which mode was chosen — they speak HTTP to a
published port.

The repo root is a parameter rather than derived from `$0`: a sourced POSIX
shell file has no reliable way to find its own path, and deriving it worked for
the first caller and pointed one directory above the repository for the second.

**Container mode is unverified on the workstation** — FreeBSD, no podman — so
only the host path has run here. It is exercised for the first time on a session
run.
