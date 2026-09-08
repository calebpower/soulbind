#!/bin/sh
# Refuse a release whose version CHANGELOG.md does not name.
#
#     changelog-check.sh <version> [changelog]
#
# Copyright (c) 2026 Caleb L. Power
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# WHY THIS EXISTS. 0.1.4 and 0.1.5 were both released with no section naming
# them. Each fix wrote its entry into `## Unreleased` in the same commit as the
# fix, the tag was cut on that commit, and the step that turns the heading into
# a version was never taken -- twice in a row, four minutes apart. An operator
# reading CHANGELOG.md saw two published releases that did not appear in it.
#
# Nothing caught it because nothing was looking. The release path already
# refuses a tag whose built artifact disagrees with it; this refuses a tag the
# changelog has never heard of, which is the same class of claim about the same
# release, checked in the same place.
#
# WHY HERE AND NOT IN A GUARD. "The changelog names this version" is a property
# of a RELEASE, and the version is only known at tag time. DECISIONS 10.53 is
# the entry that cost a session to learn this: a release-time assertion put in
# a build-time guard fails the environment that has least, and the reaper guest
# has no git to derive a version from at all.
#
# TWO CHECKS, because the observed defect trips both and each catches something
# the other does not:
#
#   1. A `## <version>` heading exists.  Catches the release nobody named.
#   2. `## Unreleased`, if present, is empty.  Catches the subtler half: an
#      entry that IS written, under a heading that is not a version, shipping
#      unnamed inside this very release.
#
# Check 2 is the one that would have fired first in both real cases.
set -eu

usage() {
  echo "usage: changelog-check.sh <version> [changelog]" >&2
  echo "  version may be given with or without a leading v" >&2
  exit 4
}

[ $# -ge 1 ] && [ $# -le 2 ] || usage

# Accept `v0.1.5` or `0.1.5`. The tag carries the v; the heading does not.
version=${1#v}
[ -n "$version" ] || usage

changelog=${2:-CHANGELOG.md}

if [ ! -f "$changelog" ]; then
  echo "changelog-check: $changelog does not exist." >&2
  exit 4
fi

# One pass, in awk, because both questions are about where a line sits relative
# to the headings around it and neither is a grep.
#
# The heading match is deliberately NOT a prefix match: `## 0.1.5` and
# `## 0.1.5 <anything>` both count, `## 0.1.50` does not. A check that accepted
# 0.1.50 as naming 0.1.5 would go green on the one shape most likely to occur --
# a version whose predecessor is still the newest section in the file.
result=$(awk -v v="$version" '
  BEGIN { want = "## " v; found = 0; in_unreleased = 0; unreleased_content = 0 }
  /^## / {
    in_unreleased = ($0 == "## Unreleased")
    if ($0 == want || index($0, want " ") == 1) found = 1
    next
  }
  { if (in_unreleased && $0 ~ /[^ \t]/) unreleased_content = 1 }
  END { print found, unreleased_content }
' "$changelog")

found=${result% *}
unreleased_content=${result#* }

if [ "$found" -ne 1 ]; then
  echo "changelog-check: $changelog has no '## $version' section." >&2
  echo >&2
  echo "The sections it does have:" >&2
  grep '^## ' "$changelog" | sed 's/^/  /' >&2
  echo >&2
  echo "Cutting a tag is what turns '## Unreleased' into a version. Write the" >&2
  echo "heading, commit it, move the tag to that commit, and push again." >&2
  exit 2
fi

if [ "$unreleased_content" -ne 0 ]; then
  echo "changelog-check: $changelog names $version, but '## Unreleased' still" >&2
  echo "has entries under it -- and this release is shipping them unnamed." >&2
  echo >&2
  echo "What is sitting under Unreleased:" >&2
  awk '/^## Unreleased/ { u = 1; next } /^## / { u = 0 } u && $0 ~ /[^ \t]/ { print "  " $0 }' \
    "$changelog" >&2
  echo >&2
  echo "Move them under '## $version' or into a later one. This is exactly how" >&2
  echo "0.1.4 and 0.1.5 shipped unnamed." >&2
  exit 3
fi

echo "changelog-check: $changelog names $version, and Unreleased is clear."
