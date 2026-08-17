#!/bin/sh
# MUST-FAIL FIXTURE. Not part of the build; read as text by
# FullstackStagesGuardTest.
#
# A stage runner listing a tier it does not implement. This is the shape the
# guard exists to catch: `ghost` appears in the stage list, is reported on, and
# does nothing -- and because the runner's own dispatcher check lives in the
# real script rather than this one, a reader scanning the list sees three tiers
# of coverage where there are two.
set -eu

STAGES="up ghost down"

stage_up() {
    echo "up"
}

stage_down() {
    echo "down"
}
