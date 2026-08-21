#!/bin/sh
# Tier 11 evidence: the per-step transcript a journey leaves behind.
#
# Sourced, never executed. `harness/fullstack/journeys.sh` and
# `harness/flarum/stack.sh` both emit journeys, and they run in different tiers
# against different stacks -- the game side has no forum and the forum tier has
# no proxy. A second copy of these helpers in each would be two definitions of
# what a transcript IS, and the deliverable of this tier is the transcript.
#
# Callers must set: TRANSCRIPT_EVIDENCE (where directories go), TRANSCRIPT_DB,
# TRANSCRIPT_CORE_URL, TRANSCRIPT_GATE. They are named rather than inherited so
# a caller that forgets one gets an obviously empty header instead of a
# transcript quietly attributed to the wrong backend.

transcript_open() {
    TRANSCRIPT="$TRANSCRIPT_EVIDENCE/$1/transcript.md"
    mkdir -p "$TRANSCRIPT_EVIDENCE/$1"
    step_n=0
    cat > "$TRANSCRIPT" <<MD
# Journey: $1

Backend: \`$TRANSCRIPT_DB\` · core: \`$TRANSCRIPT_CORE_URL\` · gate: \`$TRANSCRIPT_GATE\`

Every step below records what the person doing this would have seen. Read it
cold: the question this evidence answers is whether a newcomer could follow the
flow, and that judgement is not automated anywhere.

MD
}

# step <what the person did> <what they saw>
step() {
    step_n=$((step_n + 1))
    {
        echo "## Step $step_n — $1"
        echo
        # A FOUR-backtick fence, and the payload passed through a filter that
        # neutralises any fence it contains.
        #
        # Command output is untrusted text as far as this document is concerned.
        # With a three-backtick fence, output containing one closes the block and
        # the remainder renders as prose -- a refusal string was used to forge a
        # convincing "the player was admitted" step into a transcript. Since the
        # transcript IS this tier's deliverable, an artifact that can be made to
        # misreport what happened is the whole failure.
        echo '````'
        fenced "$2"
        echo '````'
        echo
    } >> "$TRANSCRIPT"
    log "  step $step_n: $1"
}

# Untrusted text, made safe to put inside a fence.
#
# Two things, both found by attacking the transcript rather than reading it:
#
#   * a closing fence may be indented by up to three spaces per CommonMark, so
#     anchoring the filter at ^ let an indented ```` close the block and render
#     the rest as prose -- the same forgery the four-backtick fence was added to
#     stop, one space over;
#   * printf, never echo. The guest shell is dash, whose echo expands backslash
#     escapes, so a \n in connector output became a real newline and the payload
#     became markdown.
fenced() {
    printf '%s\n' "$1" | sed 's/^ \{0,3\}````*/'"'"'&/'
}

note() {
    # Every line prefixed, and newlines cannot escape the quote block.
    #
    # This is author text -- but it INTERPOLATES core's output ("Refused, with
    # reason `$reason`"), and a newline in that value left the second line
    # unprefixed and rendering as document prose. fail_journey was fenced last
    # round for exactly this reason and its sibling was missed, which is the same
    # defect one function over.
    printf '%s\n' "$1" | sed 's/^/> /' >> "$TRANSCRIPT"
    printf '\n' >> "$TRANSCRIPT"
}
