/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.soulbind.sim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The invariants the simulated-user tier diffs against core.
 *
 * <p>Each one is a separate object rather than a method on a checker, so the
 * self-test can hand any single one a broken {@link CoreView} and see what it
 * says. A checker with the whole set inlined could only be tested as a set, and
 * a set is exactly what hides an invariant that never fires.
 */
public final class Invariants {

    private Invariants() {
        throw new AssertionError("no instances");
    }

    /** Every invariant, in the order a report should read them. */
    public static List<Invariant> all() {
        return List.of(
                linkageMirrorsModel(),
                coreInventsNoLinks(),
                everyMutationIsAudited(),
                auditSequenceStrictlyIncreases(),
                redeemedCodesStayRedeemed(),
                decisionsFollowTheRules(),
                textSurvivesTheRoundTrip(),
                everyResponseWasAnEnvelope());
    }

    /**
     * Accounts the model linked are on one subject in core.
     *
     * <p>Asserted as a SET, not as a subject id. Which id core chose is core's
     * business; that the same accounts came out together is the claim.
     */
    public static Invariant linkageMirrorsModel() {
        return invariant(
                "linkage-mirrors-model",
                "accounts the model linked share one subject in core",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    Set<String> alreadyReported = new LinkedHashSet<>();
                    for (String ref : model.knownIdentities()) {
                        if (!alreadyReported.add(ref)) {
                            continue;
                        }
                        Set<String> expected = model.groupContaining(ref);
                        alreadyReported.addAll(expected);

                        String[] parts = split(ref);
                        Optional<CoreView.Subject> subject = core.describe(parts[0], parts[1]);
                        if (subject.isEmpty()) {
                            complaints.add(ref + " was linked by an actor and core does not"
                                    + " know it at all");
                            continue;
                        }
                        Set<String> actual = new LinkedHashSet<>();
                        for (CoreView.Identity identity : subject.get().identities()) {
                            actual.add(identity.ref());
                        }
                        if (!actual.equals(expected)) {
                            complaints.add(ref + " should share a subject with " + expected
                                    + " and core reports " + actual);
                        }
                    }
                    return complaints;
                });
    }

    /**
     * Core reports no identity nobody linked.
     *
     * <p>The other direction, and it needs saying separately: an invariant that
     * only checks the model's own links is satisfied by a core that ALSO
     * attaches strangers to the subject. A link nobody asked for is the more
     * alarming of the two failures and the easier one to leave uncovered.
     */
    public static Invariant coreInventsNoLinks() {
        return invariant(
                "core-invents-no-links",
                "core attaches no identity that no actor linked",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    for (String ref : model.knownIdentities()) {
                        String[] parts = split(ref);
                        Optional<CoreView.Subject> subject = core.describe(parts[0], parts[1]);
                        if (subject.isEmpty()) {
                            continue; // linkage-mirrors-model reports this
                        }
                        for (CoreView.Identity identity : subject.get().identities()) {
                            if (!model.knownIdentities().contains(identity.ref())) {
                                complaints.add("core has " + identity.ref() + " on the subject"
                                        + " holding " + ref + ", and no actor ever linked it");
                            }
                        }
                    }
                    return complaints;
                });
    }

    /**
     * Every mutation has an audit row.
     *
     * <p>One of §11's two-oracle properties: the model counts what the actors
     * did, core is asked what it recorded, and neither derives from the other.
     * Counted by action rather than matched row-for-row, because the model does
     * not know the subject ids core assigned and pretending otherwise would make
     * this an assertion about core's own bookkeeping.
     */
    public static Invariant everyMutationIsAudited() {
        return invariant(
                "every-mutation-is-audited",
                "core wrote an audit row for every mutation an actor performed",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    List<CoreView.AuditRow> rows = core.auditSince(0);
                    for (String action : new LinkedHashSet<>(model.expectedAuditActions())) {
                        long expected = model.expectedAuditActions().stream()
                                .filter(action::equals).count();
                        long actual = rows.stream()
                                .filter(r -> action.equals(r.action())).count();
                        if (actual < expected) {
                            complaints.add("actors performed " + expected + " " + action
                                    + " but core audited " + actual
                                    + ". An unaudited mutation is one nobody can review.");
                        }
                    }
                    return complaints;
                });
    }

    /**
     * Audit sequence numbers strictly increase.
     *
     * <p>A repeated sequence number means two entries claim one position, and
     * the audit log stops being a log. A decreasing one means it was rewritten.
     */
    public static Invariant auditSequenceStrictlyIncreases() {
        return invariant(
                "audit-sequence-strictly-increases",
                "audit sequence numbers are unique and ascending",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    long previous = Long.MIN_VALUE;
                    Set<Long> seen = new LinkedHashSet<>();
                    for (CoreView.AuditRow row : core.auditSince(0)) {
                        if (!seen.add(row.sequence())) {
                            complaints.add("audit sequence " + row.sequence()
                                    + " appears more than once");
                        // `<=`, and the `<` mutant of it is EQUIVALENT rather
                        // than surviving through inattention: `previous` starts
                        // at 0 and the only caller reads `auditSince(0)`, which
                        // returns rows with `sequence > 0`. A row numbered zero
                        // therefore cannot reach here, and equality with any
                        // later `previous` is caught by the duplicate branch
                        // above. Recorded so a later sweep skips it. DECISIONS
                        // 10.33.
                        } else if (row.sequence() <= previous) {
                            complaints.add("audit sequence went from " + previous + " to "
                                    + row.sequence() + "; the log is not append-only");
                        }
                        previous = row.sequence();
                    }
                    return complaints;
                });
    }

    /** A code the model saw redeemed is never redeemable again. */
    public static Invariant redeemedCodesStayRedeemed() {
        return invariant(
                "redeemed-codes-stay-redeemed",
                "a code that was redeemed is never redeemable again",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    for (String code : model.redeemedCodes()) {
                        if (core.codeRedeemable(code)) {
                            complaints.add("code " + code + " was redeemed and core still"
                                    + " offers it. Single use is the whole guarantee a link"
                                    + " code carries.");
                        }
                    }
                    return complaints;
                });
    }

    /**
     * A gate requiring linkage refuses an identity that is not linked.
     *
     * <p>Written after discovering that {@code SET_RULE} and {@code DECIDE} —
     * two of nine action classes — were doing work no assertion read. The tier
     * set rules and asked for decisions and never related one to the other, so
     * a {@code rule.set} payload silently missing {@code requireLinked} stored
     * rules that required nothing, for an entire phase, invisibly.
     *
     * <p>Asserted in the direction that catches that. If rules were ignored
     * entirely, every gate would allow everybody — so the claim worth making is
     * about identities the model believes are <b>not linked</b>: they must be
     * refused. Asserting the linked direction instead would pass just as well
     * against a core that had never applied a rule in its life.
     *
     * <p>Uses {@link ShadowModel#neverLinked}, which is deliberately separate
     * from the link graph: an identity that has only ever had a code issued for
     * it has no subject in core, and folding it into the graph would make
     * {@code linkage-mirrors-model} complain about an account core is right not
     * to know.
     */
    public static Invariant decisionsFollowTheRules() {
        return invariant(
                "decisions-follow-the-rules",
                "a gate requiring linkage refuses an unlinked identity",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    for (var rule : model.rules().entrySet()) {
                        if (!Boolean.TRUE.equals(rule.getValue())) {
                            continue;
                        }

                        // An account core has never heard of, which is
                        // definitionally linked to nothing.
                        //
                        // The probe exists because the real unlinked set empties
                        // out: the actors link everything they own within the
                        // first few dozen actions, so by the time a rule has
                        // been set there is often no unlinked identity left and
                        // the loop below asserts nothing. The first version of
                        // this invariant was exactly that vacuous, and the
                        // acceptance test caught it -- RULES_ARE_IGNORED was
                        // switched on and a four-hundred-action run did not
                        // notice.
                        //
                        // This one cannot empty out, and it is sound for the
                        // same reason it is always available: an identity core
                        // has never seen is not linked to anything, so a gate
                        // requiring linkage must refuse it.
                        String probeEffect = core.decide(
                                rule.getKey(), "game", "soulbind-sim-never-linked-probe");
                        if (!probeEffect.isEmpty() && !"deny".equals(probeEffect)) {
                            complaints.add("gate " + rule.getKey() + " requires linkage and"
                                    + " core says " + probeEffect + " for an account it has"
                                    + " never heard of. Either the rule was not applied or it"
                                    + " was stored requiring nothing.");
                        }

                        for (String ref : model.neverLinked()) {
                            String[] parts = split(ref);
                            String effect = core.decide(rule.getKey(), parts[0], parts[1]);
                            if (effect.isEmpty()) {
                                // Unaskable is not the same as allowed. The
                                // envelope invariant reports the outage; this
                                // one must not turn it into a policy verdict.
                                continue;
                            }
                            if (!"deny".equals(effect)) {
                                complaints.add(ref + " is linked to nothing and gate "
                                        + rule.getKey() + " requires linkage, and core says "
                                        + effect + ". Either the rule was not applied or it"
                                        + " was stored requiring nothing.");
                            }
                        }
                    }
                    return complaints;
                });
    }

    /**
     * A display name comes back exactly as it was sent.
     *
     * <p>The actors write four-byte UTF-8 — the astral-plane section of
     * {@code corpus/hostile-inputs.txt}, "the classic latin1 tripwire" — into
     * every display name, per §11 Tier 6. This is the half that catches
     * something: pushing hostile text through a system proves nothing unless
     * somebody reads it back.
     *
     * <p>It is also the end-to-end proof of 8.18 and 8.24. Those are otherwise
     * asserted by reading {@code information_schema}, which is a claim about
     * what the schema DECLARES. This is a claim about what survives a round
     * trip through a real deployment against a server started latin1 — which is
     * the one anybody cares about: a person whose name is an emoji can link.
     *
     * <p>Compared exactly. A truncated or re-encoded name is the failure mode,
     * and "close enough" is how a mangled name ships.
     */
    public static Invariant textSurvivesTheRoundTrip() {
        return invariant(
                "text-survives-the-round-trip",
                "a display name comes back byte for byte as it was sent",
                (model, core) -> {
                    List<String> complaints = new ArrayList<>();
                    Set<String> checked = new LinkedHashSet<>();
                    for (String ref : model.knownIdentities()) {
                        String[] parts = split(ref);
                        Optional<CoreView.Subject> subject = core.describe(parts[0], parts[1]);
                        if (subject.isEmpty()) {
                            continue; // linkage-mirrors-model reports this
                        }
                        for (CoreView.Identity identity : subject.get().identities()) {
                            if (!checked.add(identity.ref())) {
                                continue;
                            }
                            Optional<String> expected = model.displayFor(identity.ref());
                            if (expected.isEmpty()) {
                                continue;
                            }
                            if (!expected.get().equals(identity.display())) {
                                complaints.add(identity.ref() + " was written with display "
                                        + quote(expected.get()) + " and core returns "
                                        + quote(identity.display())
                                        + ". Four-byte text did not survive the round trip;"
                                        + " the column or the connection charset is wrong.");
                            }
                        }
                    }
                    return complaints;
                });
    }

    /** Shows a string with its code points, so a mangled one is readable. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"").append(value).append("\" [");
        value.codePoints().forEach(cp -> out.append("U+")
                .append(Integer.toHexString(cp).toUpperCase(java.util.Locale.ROOT)).append(' '));
        return out.append(']').toString();
    }

    /**
     * The cheap oracle: no 5xx, every response an envelope.
     *
     * <p>Independent of everything above, per §11. A run whose model and server
     * agree perfectly, having exchanged a 500 on the way, has still found
     * something — and none of the other invariants would mention it.
     */
    public static Invariant everyResponseWasAnEnvelope() {
        return invariant(
                "every-response-was-an-envelope",
                "no response was a 5xx or a malformed envelope",
                (model, core) -> core.reachable()
                        ? List.of()
                        : List.copyOf(core.transportComplaints()));
    }

    // --- plumbing ------------------------------------------------------------

    private static String[] split(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0
                ? new String[] {ref, ""}
                : new String[] {ref.substring(0, colon), ref.substring(colon + 1)};
    }

    private interface Check {
        List<String> apply(ShadowModel model, CoreView core);
    }

    private static Invariant invariant(String name, String describes, Check check) {
        return new Invariant() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String describes() {
                return describes;
            }

            @Override
            public List<String> check(ShadowModel model, CoreView core) {
                return check.apply(model, core);
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }
}
