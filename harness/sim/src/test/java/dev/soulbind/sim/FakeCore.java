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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A core that answers however the self-test tells it to.
 *
 * <p>Built healthy and then broken in one named way per case, so each test says
 * exactly which lie it is telling. A fake that starts broken would make the
 * control — the run where the invariant must stay silent — impossible to write,
 * and without a control an invariant that complains about everything scores
 * perfectly.
 */
final class FakeCore implements CoreView {

    private final Map<String, Set<String>> subjects = new LinkedHashMap<>();
    private final Map<String, String> subjectIds = new LinkedHashMap<>();
    private final List<AuditRow> audit = new ArrayList<>();
    private final Set<String> redeemable = new LinkedHashSet<>();
    private boolean reachable = true;
    private final List<String> transportComplaints = new ArrayList<>();
    private long nextSequence = 1;

    /** Puts these refs on one subject, as a healthy core would after a link. */
    FakeCore linked(String... refs) {
        String id = "subject-" + (subjectIds.size() + 1);
        Set<String> group = new LinkedHashSet<>(List.of(refs));
        for (String ref : refs) {
            subjects.put(ref, group);
            subjectIds.put(ref, id);
        }
        return this;
    }

    private final Map<String, String> displays = new LinkedHashMap<>();

    /**
     * What core reports as an identity's display name.
     *
     * <p>Null by default, which the text invariant reads as "core has nothing
     * to compare" and skips. Scripting one is how a test asks whether a
     * mangled round trip is noticed.
     */
    FakeCore displays(String ref, String display) {
        displays.put(ref, display);
        return this;
    }

    /** Appends an audit row with the next sequence number. */
    FakeCore audited(String action) {
        audit.add(new AuditRow(nextSequence++, "connector:a", action, "subject-1"));
        return this;
    }

    /** Appends a row with a sequence chosen by the caller, however wrong. */
    FakeCore auditedAtSequence(long sequence, String action) {
        audit.add(new AuditRow(sequence, "connector:a", action, "subject-1"));
        return this;
    }

    /** Marks a code as still offered, whatever the model believes. */
    FakeCore offersCode(String code) {
        redeemable.add(code);
        return this;
    }

    /** Attaches a ref to the subject already holding {@code existing}. */
    FakeCore alsoOnSubjectOf(String existing, String stranger) {
        Set<String> group = subjects.get(existing);
        group.add(stranger);
        subjects.put(stranger, group);
        subjectIds.put(stranger, subjectIds.get(existing));
        return this;
    }

    /** Drops a ref from the subject that should hold it. */
    FakeCore forgot(String ref) {
        Set<String> group = subjects.get(ref);
        if (group != null) {
            group.remove(ref);
        }
        subjects.remove(ref);
        subjectIds.remove(ref);
        return this;
    }

    FakeCore unreachable(String complaint) {
        this.reachable = false;
        this.transportComplaints.add(complaint);
        return this;
    }

    @Override
    public Optional<Subject> describe(String platformKind, String platformId) {
        String ref = platformKind + ":" + platformId;
        Set<String> group = subjects.get(ref);
        if (group == null) {
            return Optional.empty();
        }
        List<Identity> identities = new ArrayList<>();
        for (String member : group) {
            int colon = member.indexOf(':');
            identities.add(new Identity(
                    member.substring(0, colon), member.substring(colon + 1), true,
                    displays.get(member)));
        }
        return Optional.of(new Subject(subjectIds.get(ref), identities));
    }

    @Override
    public List<AuditRow> auditSince(long after) {
        return audit.stream().filter(r -> r.sequence() > after).toList();
    }

    /** Gate -> effect, for the policy invariant. */
    private final Map<String, String> decisions = new LinkedHashMap<>();

    FakeCore decides(String gate, String ref, String effect) {
        decisions.put(gate + "|" + ref, effect);
        return this;
    }

    /**
     * The scripted answer, or "" — meaning core could not be asked.
     *
     * <p>Not "allow". A default of "allow" means a test that forgets to script
     * a decision gets a PERMISSIVE answer rather than a missing one, which is
     * the direction that hides faults — in a fixture whose entire job is
     * letting the oracle's own tests fabricate core's replies. Every caller
     * that meant "allow" now says so. DECISIONS 10.38.
     */
    @Override
    public String decide(String gate, String platformKind, String platformId) {
        return decisions.getOrDefault(gate + "|" + platformKind + ":" + platformId, "");
    }

    @Override
    public boolean codeRedeemable(String code) {
        return redeemable.contains(code);
    }

    @Override
    public boolean reachable() {
        return reachable;
    }

    @Override
    public List<String> transportComplaints() {
        return List.copyOf(transportComplaints);
    }
}
