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
