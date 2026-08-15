# corpus

One hostile-input list, shared by every tier that needs one: seeded fuzz
(Tier 7), the full-stack stages (Tier 6), and the simulated-user nemesis
(Tier 9).

**Why one list.** A value that broke the API should reach the UI without anyone
re-typing it. Separate per-tier lists drift, and the drift is invisible until
the tier that lacks a value is the one that would have caught the defect.

## Format

`hostile-inputs.txt` — one value per line. Blank lines and lines beginning `#`
are comments. A value containing characters that cannot survive a line-oriented
file (a newline, say) is expressed as a `\uXXXX` escape and unescaped by the
consumer.

Values are grouped by the failure they probe, and the grouping comment is part
of the file's value: a bare list of odd strings tells a later reader nothing
about what was being defended against.
