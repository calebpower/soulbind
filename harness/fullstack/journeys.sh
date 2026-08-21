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

# The transcript helpers live in harness/transcript.sh because the forum tier
# emits journeys too, against a stack this one does not have. See that file.
TRANSCRIPT_EVIDENCE="$EVIDENCE"
TRANSCRIPT_DB="$DB"
TRANSCRIPT_CORE_URL="$CORE_URL"
TRANSCRIPT_GATE="$GATE"
. "$HERE/../transcript.sh"

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
    refusal=$("$HERE/../../tools/rpc.sh" "$CORE_URL" "$HARNESS_CRED" decide \
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
    admitted=$("$HERE/../../tools/rpc.sh" "$CORE_URL" "$HARNESS_CRED" decide \
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

- \`forum-first-user\` — **covered, in the forum tier.** It is emitted by
  \`harness/flarum/stack.sh\` and lands in \`out/browser-evidence/<backend>/\`,
  not here, because this tier has no forum: departure 6 split the forum out as
  its own tier in Phase 7, and a journey can only be walked where the forum is.
- \`bedrock-player\` — **not implemented, and the plan says when it would be.**
  §11 Tier 6 calls a Bedrock client through Geyser "a stretch stage, added only
  if Geyser is in the composed stack", and it is not. The same sentence records
  that "Floodgate identity handling is covered by Tiers 1/4 regardless", which
  is where it is covered. Departure 10.

No screenshots from THIS tier: the journeys it walks are chat and protocol flows
with no page to photograph. The forum-first journey is the one with a page, and
it is emitted by the tier that has one.
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
