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
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A small, correct core — with defects that can be switched on one at a time.
 *
 * <p>This is how the acceptance test runs without a session. §14's Phase 9 gate
 * asks for "a deliberately reverted Phase-2-or-later fix rediscovered by a
 * hunting run", and that has to happen against the real thing. But the same
 * question can be asked here in milliseconds, of the whole loop rather than of
 * the invariants alone: does a run that generates its own actions, executes
 * them, and updates its model actually notice?
 *
 * <p><b>Correct by default, and that half is the hard half.</b> A defective
 * core that gets caught proves nothing if a correct one is also caught, and
 * writing a correct one is the only way to find out.
 *
 * <p>Each defect mirrors something this system has genuinely got wrong or could:
 * {@link Defect#REDEEM_DOES_NOT_LINK} is the asymmetric-link bug mutation
 * coverage found in {@code LinkingService}, and it is the defect the session
 * acceptance test reverts for real.
 */
final class InMemoryCore implements CoreDriver, CoreView {

    enum Defect {
        /** Redeem reports success and the accounts are not linked. */
        REDEEM_DOES_NOT_LINK,
        /** A spent code stays redeemable. */
        CODE_STAYS_REDEEMABLE,
        /** A mutation happens with no audit row. */
        AUDIT_DROPS_ROWS,
        /** Two audit rows claim one sequence number. */
        AUDIT_SEQUENCE_REPEATS,
        /** A redeem attaches an identity nobody named. */
        LINKS_A_STRANGER,
        /** The transport sees a 5xx. */
        SERVES_A_5XX,

        /**
         * Rules are stored and never applied: every gate allows everybody.
         *
         * <p>The defect the tier could not see for an entire phase. `SET_RULE`
         * and `DECIDE` both ran, hundreds of times, and nothing related one to
         * the other — so a `rule.set` payload that silently required nothing
         * looked identical to a working one.
         */
        RULES_ARE_IGNORED,

        /**
         * A merge silently drops a member, but only once the subject is large.
         *
         * <p><b>The rare one, and the reason it exists.</b> Every other defect
         * here fires on the first redeem: measured across two hundred seeds,
         * each was caught 200/200 times, always at the first checkpoint. That
         * makes them a fine test of whether the invariants work and no test at
         * all of whether the tier can find something that needs looking for —
         * which is the entire justification for a simulated-user tier over a
         * unit test.
         *
         * <p>This one cannot fire until a subject has accumulated five
         * identities, which takes several successful links between distinct
         * actors. A seed that never builds a group that big will never see it,
         * and that is the point: it produces a detection RATE rather than a
         * yes, and a rate is the only thing that can say whether more seeds or
         * longer runs buy anything.
         */
        MERGE_LOSES_A_MEMBER_AT_DEPTH,
    }

    private final Set<Defect> defects = EnumSet.noneOf(Defect.class);

    private final Map<String, Set<String>> groups = new LinkedHashMap<>();
    private final Map<String, String> subjectIds = new LinkedHashMap<>();
    private final Map<String, String> liveCodes = new LinkedHashMap<>();
    private final Set<String> spentCodes = new LinkedHashSet<>();
    private final List<AuditRow> audit = new ArrayList<>();
    private final List<String> transportComplaints = new ArrayList<>();
    private long nextSequence = 1;
    private int codeCounter = 0;
    private int subjectCounter = 0;

    InMemoryCore with(Defect defect) {
        defects.add(defect);
        return this;
    }

    // --- the write side ------------------------------------------------------

    @Override
    public Result issueCode(Actor actor, String platformKind, String platformId) {
        String code = "CODE" + (++codeCounter);
        liveCodes.put(code, platformKind + ":" + platformId);
        return Result.ok(code);
    }

    @Override
    public Result redeemCode(Actor actor, String code, String platformKind, String platformId) {
        String issuedFor = liveCodes.get(code);
        if (issuedFor == null) {
            return Result.refused(
                    spentCodes.contains(code) ? "already redeemed" : "unknown code");
        }
        String redeemer = platformKind + ":" + platformId;
        if (redeemer.equals(issuedFor)) {
            return Result.refused("an account cannot link to itself");
        }

        // The defect leaves the code LIVE, which is what "still redeemable"
        // means to everything that asks. An earlier version of this defect only
        // skipped adding to `spentCodes` -- a set nothing reads -- so it was a
        // no-op, and the acceptance test caught it as a defect that escaped.
        // The fake's defects have to be real defects or the test grades nothing.
        if (!defects.contains(Defect.CODE_STAYS_REDEEMABLE)) {
            liveCodes.remove(code);
            spentCodes.add(code);
        }

        if (!defects.contains(Defect.REDEEM_DOES_NOT_LINK)) {
            join(issuedFor, redeemer);
            if (defects.contains(Defect.LINKS_A_STRANGER)) {
                join(issuedFor, "ghost:" + code);
            }
        }

        if (!defects.contains(Defect.AUDIT_DROPS_ROWS)) {
            append("identity.linked");
        }
        return Result.ok(subjectIds.getOrDefault(issuedFor, "subject-unknown"));
    }

    @Override
    public Result describe(Actor actor, String platformKind, String platformId) {
        return Result.ok(subjectIds.get(platformKind + ":" + platformId));
    }

    /** Gates with a rule requiring linkage. */
    private final Set<String> requireLinked = new LinkedHashSet<>();

    @Override
    public Result decide(Actor actor, String gate, String platformKind, String platformId) {
        return Result.ok(decide(gate, platformKind, platformId));
    }

    @Override
    public String decide(String gate, String platformKind, String platformId) {
        if (defects.contains(Defect.RULES_ARE_IGNORED)) {
            return "allow";
        }
        if (!requireLinked.contains(gate)) {
            return "allow";
        }
        return groups.containsKey(platformKind + ":" + platformId) ? "allow" : "deny";
    }

    @Override
    public Result setRule(Actor actor, String gate, boolean linkedRequired) {
        if (linkedRequired) {
            requireLinked.add(gate);
        } else {
            requireLinked.remove(gate);
        }
        if (!defects.contains(Defect.AUDIT_DROPS_ROWS)) {
            append("rule.changed");
        }
        return Result.ok(gate);
    }

    @Override
    public Result setConfig(Actor actor, String key, String value) {
        if (!defects.contains(Defect.AUDIT_DROPS_ROWS)) {
            append("config.changed");
        }
        if (defects.contains(Defect.SERVES_A_5XX)) {
            transportComplaints.add("HTTP 500 from config.set");
        }
        return Result.ok(value);
    }

    @Override
    public Result withRetiredCredential(Actor actor, String platformKind, String platformId) {
        // The coherent outcome §11 asks for: a refusal, not a crash, and the
        // live session is undisturbed -- nothing here mutates anything.
        return Result.refused("credential is not valid");
    }

    // --- the read side -------------------------------------------------------

    @Override
    public Optional<Subject> describe(String platformKind, String platformId) {
        String ref = platformKind + ":" + platformId;
        Set<String> group = groups.get(ref);
        if (group == null) {
            return Optional.empty();
        }
        List<Identity> identities = new ArrayList<>();
        for (String member : group) {
            int colon = member.indexOf(':');
            identities.add(new Identity(
                    member.substring(0, colon), member.substring(colon + 1), true));
        }
        return Optional.of(new Subject(subjectIds.get(ref), identities));
    }

    @Override
    public List<AuditRow> auditSince(long after) {
        return audit.stream().filter(r -> r.sequence() > after).toList();
    }

    @Override
    public boolean codeRedeemable(String code) {
        return liveCodes.containsKey(code);
    }

    @Override
    public boolean reachable() {
        return transportComplaints.isEmpty();
    }

    @Override
    public List<String> transportComplaints() {
        return List.copyOf(transportComplaints);
    }

    // --- plumbing ------------------------------------------------------------

    private void join(String left, String right) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(groups.getOrDefault(left, Set.of(left)));
        merged.addAll(groups.getOrDefault(right, Set.of(right)));

        // >= 3, which is a person's THIRD platform. Calibrated against the
        // graph's real shape rather than a guess: a healthy run gives each
        // person one subject holding their three identities, so a subject never
        // exceeds three and a threshold of five could never fire. The first
        // version used five, chosen while the generator was still collapsing
        // every actor into one giant subject where five was easy to reach.
        if (defects.contains(Defect.MERGE_LOSES_A_MEMBER_AT_DEPTH) && merged.size() >= 3) {
            // Drops a member that is neither side of THIS link, so the redeem
            // that triggered it still looks correct to anyone checking only the
            // two accounts just joined. It is an older identity that quietly
            // stops belonging.
            for (String member : merged) {
                if (!member.equals(left) && !member.equals(right)) {
                    merged.remove(member);
                    groups.remove(member);
                    subjectIds.remove(member);
                    break;
                }
            }
        }

        String id = subjectIds.get(left);
        if (id == null) {
            id = subjectIds.get(right);
        }
        if (id == null) {
            id = "subject-" + (++subjectCounter);
        }
        for (String member : merged) {
            groups.put(member, merged);
            subjectIds.put(member, id);
        }
    }

    private void append(String action) {
        long sequence = defects.contains(Defect.AUDIT_SEQUENCE_REPEATS) && !audit.isEmpty()
                ? audit.get(audit.size() - 1).sequence()
                : nextSequence++;
        audit.add(new AuditRow(sequence, "connector:sim", action, "subject-1"));
    }
}
