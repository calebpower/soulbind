#!/bin/sh
# Tier 11 — human evidence (methodology §13, plan §11).
#
#     journeys.sh <run-dir> <backend> <evidence-dir>
#
# Emits a per-step transcript for each linking journey, so "would a newcomer
# understand this?" can be answered from evidence rather than from memory. It
# deliberately produces EVIDENCE, not a verdict on the wording: that judgement
# is a person's, and a script returning pass/fail on prose would be faking the
# one thing the tier exists to avoid faking.
#
# It does, however, assert the FLOW. The first version of this file recorded
# every step and asserted almost none of them, and an adversarial review showed
# what that is worth: it called an operation that does not exist, against a gate
# nobody had configured, and the obvious fix would have made it pass with an
# unlinked player reported as admitted. A transcript of a flow that did not
# happen is worse than no transcript.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RUN=$1
DB=$2
EVIDENCE=$3

CORE_PORT=${CORE_PORT:-7100}
CORE_URL="http://127.0.0.1:$CORE_PORT"

# The gate the stack actually configures. Asking about an unconfigured gate is
# not a harmless typo: core answers `allow` with reason `no-rule`, so a journey
# aimed at the wrong name reports a player who linked nothing as admitted, and
# the entire flow becomes irrelevant to the verdict.
GATE=${SOULBIND_JOURNEY_GATE:-game.join}

log() { echo "[journeys] $*"; }

# The journeys this stage runs. The plan names three -- first-time player,
# forum-first user, Bedrock player. Only the ones listed here are attempted, and
# COVERAGE.md records which of the three that is AND whether each one actually
# succeeded, because a coverage note that lists attempts rather than outcomes is
# a coverage note that lies on exactly the runs that matter.
JOURNEYS="first-time-player"

mkdir -p "$EVIDENCE"

# --- reading core's answers -------------------------------------------------
#
# `reason` and `effect` are parsed as JSON. The first version matched
# `*ALLOW*|*allow*` as a substring over merged stdout and stderr, which any text
# containing the word "allow" satisfies -- including an error message explaining
# that nothing was allowed. protocol.md says it plainly: match on `reason`,
# never on `detail`.

field() {
    python3 -c '
import json, sys
try:
    print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))
except Exception:
    print("")
' "$1"
}

# --- transcript -------------------------------------------------------------
#
# Markdown, because it is readable without a tool and diffable between runs.

TRANSCRIPT=
step_n=0

transcript_open() {
    TRANSCRIPT="$EVIDENCE/$1/transcript.md"
    mkdir -p "$EVIDENCE/$1"
    step_n=0
    cat > "$TRANSCRIPT" <<MD
# Journey: $1

Backend: \`$DB\` · core: \`$CORE_URL\` · gate: \`$GATE\`

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

fail_journey() {
    log "$1"
    # FENCED. This wrote its argument as bare markdown, and the argument
    # interpolates connector and core output -- so a crafted reply forged a
    # "## Step 98 — tried to join again" heading and an {"effect":"allow"} payload
    # into a FAILING journey's transcript. The transcript is this tier's entire
    # deliverable; one that can be made to misreport what happened is the whole
    # failure.
    {
        echo "## Journey did not complete"
        echo
        echo '````'
        fenced "$1"
        echo '````'
        echo
    } >> "$TRANSCRIPT"
    return 1
}

# --- journey: first-time player ---------------------------------------------
#
# A player who has never linked anything: refused at the gate, told why, runs
# /link on the other platform, redeems the code, and is then admitted. Every
# step goes through the real protocol -- nothing writes state directly, because
# a journey that arranged its own starting position would keep reading correctly
# after the flow it documents had broken.

journey_first_time_player() {
    transcript_open first-time-player
    note "The flow a brand-new player meets. Nothing is pre-arranged: credentials are minted through \`soulbind register\`, the code is issued by \`/link\` in real chat, and the redeem goes through the real protocol."

    creds="$RUN/core/creds.env"
    [ -f "$creds" ] || { fail_journey "no credentials at $creds -- the up stage did not complete"; return 1; }
    # shellcheck disable=SC1090
    . "$creds"

    player="journey-player-$$"
    chat_id="journey-chat-$$"

    # --- refused --------------------------------------------------------
    refusal=$("$HERE/../rpc.sh" "$CORE_URL" "$HARNESS_CRED" decide \
        "{\"gate\":\"$GATE\",\"platformKind\":\"game\",\"platformId\":\"$player\"}" 2>&1) || true
    step "tried to join before linking anything" "$refusal"

    effect=$(printf '%s' "$refusal" | field effect)
    reason=$(printf '%s' "$refusal" | field reason)
    if [ "$effect" != "deny" ]; then
        fail_journey "an unlinked player was not refused: effect='$effect' reason='$reason'. A journey that starts from an already-open gate documents nothing about linking."
        return 1
    fi
    note "Refused, with reason \`$reason\`. The refusal has to say what to do next; a gate that says only 'denied' is a gate that generates support tickets."

    # --- the code -------------------------------------------------------
    chat_driver="$REPO/connector-discord/build/install/connector-discord/bin/scripted-driver"
    [ -x "$chat_driver" ] || { fail_journey "no scripted chat surface at $chat_driver"; return 1; }

    reply=$(SOULBIND_DRIVER_KIND=chat "$chat_driver" \
        "$CORE_URL" "$CHAT_CRED" "$chat_id" link 2>&1 | head -1) || true
    step "ran /link on the other platform" "$reply"

    # The code is extracted from the SENTENCE the connector shows a person,
    # against the protocol's alphabet -- which excludes the characters people
    # confuse when reading a code aloud. Matching a bare token would silently
    # accept the first word of an error message.
    code=$(printf '%s' "$reply" | sed -n 's/.*code is \([23456789BCDFGHJKMNPQRSTVWXYZ]*\).*/\1/p')
    if [ -z "$code" ]; then
        fail_journey "no link code in what the connector said: '$reply'"
        return 1
    fi
    note "The message a person actually reads has to contain the code and say where to put it. That sentence is the product here, not the code."

    # --- redeemed -------------------------------------------------------
    if redeem=$("$HERE/redeem.sh" "$CORE_URL" "$HARNESS_CRED" "$code" game "$player" 2>&1); then
        step "entered the code in game" "$redeem"
    else
        step "entered the code in game" "$redeem"
        fail_journey "the redeem failed, so nothing after this step would mean anything"
        return 1
    fi

    # --- admitted -------------------------------------------------------
    admitted=$("$HERE/../rpc.sh" "$CORE_URL" "$HARNESS_CRED" decide \
        "{\"gate\":\"$GATE\",\"platformKind\":\"game\",\"platformId\":\"$player\"}" 2>&1) || true
    step "tried to join again" "$admitted"

    effect=$(printf '%s' "$admitted" | field effect)
    reason=$(printf '%s' "$admitted" | field reason)
    if [ "$effect" != "allow" ]; then
        fail_journey "the player was still refused after linking: effect='$effect' reason='$reason'"
        return 1
    fi
    note "Admitted, with reason \`$reason\` — and the only thing that changed between the two attempts is that the player linked an account."
}

# --- coverage ---------------------------------------------------------------

write_coverage() {
    cat > "$EVIDENCE/COVERAGE.md" <<MD
# Journey coverage

Emitted by \`journeys.sh\` on backend \`$DB\`, gate \`$GATE\`. Generated from
the journey list and the recorded OUTCOMES, never hand-maintained — a coverage
note somebody updates by hand is a coverage note that stops being true, and one
that lists attempts rather than outcomes lies on exactly the runs that matter.

## This session

$OUTCOMES

## Named by the plan and NOT yet covered

- \`forum-first-user\` — needs the browser tier driving the forum in the same
  session as the game stack. The forum tier is green on its own; joining the two
  into one journey is outstanding.
- \`bedrock-player\` — needs a Bedrock client through Geyser/Floodgate. The
  identity translation it would exercise is unit-tested in
  \`connector-velocity\`, but that is a fixture, not a client.

No screenshots: the journeys covered so far are chat and protocol flows with no
page to photograph. The forum-first journey is the one that will carry them,
which is part of why it is named separately here rather than folded in.
MD
}

# --- run --------------------------------------------------------------------

failed=0
OUTCOMES=""

for journey in $JOURNEYS; do
    log "journey: $journey"
    fn="journey_$(echo "$journey" | tr '-' '_')"
    if "$fn"; then outcome="completed"; else outcome="FAILED"; failed=1; fi

    # A transcript that is merely non-empty proves nothing: transcript_open
    # writes a header, so `-s` is satisfied by a journey that recorded zero
    # steps. The step count is what says work happened, and it was sitting right
    # there unasserted in the first version of this file.
    if [ "$step_n" -eq 0 ]; then
        log "HARNESS FAULT: journey '$journey' recorded no steps"
        outcome="FAILED (no steps recorded)"
        failed=1
    fi
    if [ ! -s "$EVIDENCE/$journey/transcript.md" ]; then
        log "HARNESS FAULT: journey '$journey' emitted no transcript"
        outcome="FAILED (no transcript)"
        failed=1
    fi

    OUTCOMES="$OUTCOMES
- \`$journey\` — **$outcome**, $step_n steps — [transcript]($journey/transcript.md)"
done

write_coverage
log "evidence in $EVIDENCE"
exit $failed
