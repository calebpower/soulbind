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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A deliberately <b>partial</b> second copy of the truth.
 *
 * <p>Partial is the design, not a shortcut. A model that reimplemented core
 * would be a second implementation with its own defects, and the two agreeing
 * would prove they shared a misunderstanding. This one records only what the
 * actors <em>did</em> and what must follow from that — this identity was linked
 * to that one, this code was redeemed, this many mutations happened — and never
 * how core arrived at anything.
 *
 * <p>So it can say "these two accounts must be on one subject" and cannot say
 * which subject id that is. Which is right: the id is core's to choose, and an
 * invariant that pinned it would be asserting an implementation detail.
 */
public final class ShadowModel {

    /** Identity ref -> the group it belongs to. Groups are identified by any member. */
    private final Map<String, String> groupOf = new LinkedHashMap<>();
    private final Map<String, Set<String>> members = new LinkedHashMap<>();
    private final Set<String> redeemedCodes = new LinkedHashSet<>();
    private final Map<String, Boolean> rules = new LinkedHashMap<>();
    private final Map<String, String> displaysSent = new LinkedHashMap<>();
    private final Set<String> neverLinked = new LinkedHashSet<>();
    private final List<String> expectedAuditActions = new ArrayList<>();

    /** Records that two identities are now on one subject, whichever subject that is. */
    public void linked(String leftRef, String rightRef) {
        neverLinked.remove(leftRef);
        neverLinked.remove(rightRef);
        String left = groupOf.computeIfAbsent(leftRef, r -> newGroup(r));
        String right = groupOf.computeIfAbsent(rightRef, r -> newGroup(r));
        if (left.equals(right)) {
            return;
        }
        Set<String> merged = members.get(left);
        merged.addAll(members.remove(right));
        for (String ref : merged) {
            groupOf.put(ref, left);
        }
        expectedAuditActions.add("identity.linked");
    }

    /**
     * Records a rule the actors set on a gate.
     *
     * <p>Only what was asked for, never how core stores it — the model stays a
     * partial second copy rather than a reimplementation.
     */
    public void ruleSet(String gate, boolean requireLinked) {
        rules.put(gate, requireLinked);
    }

    /** Gates the model has set a rule on, and whether it requires linkage. */
    public Map<String, Boolean> rules() {
        return Collections.unmodifiableMap(rules);
    }

    /**
     * Records an identity that exists but has never been linked to anything.
     *
     * <p>Kept SEPARATE from the link graph on purpose. An identity that has only
     * ever had a code issued for it has no subject in core, so putting it in
     * {@link #knownIdentities} would make {@code linkage-mirrors-model} report
     * "core does not know it at all" about an account core is correct to not
     * know. Two different questions, two different sets.
     */
    public void sawUnlinked(String ref) {
        if (!groupOf.containsKey(ref)) {
            neverLinked.add(ref);
        }
    }

    /** Identities the model has seen and never seen linked. */
    public Set<String> neverLinked() {
        return Collections.unmodifiableSet(neverLinked);
    }

    /**
     * Records the display name an actor sent for an identity.
     *
     * <p>The oracle for §11's "astral-plane text from the corpus pushes through
     * the newest text column in every stage". Pushing it through is half the
     * requirement; the half that catches anything is reading it back and
     * comparing, byte for byte.
     */
    public void displaySent(String ref, String display) {
        displaysSent.put(ref, display);
    }

    /** The display name last sent for an identity, if any. */
    public Optional<String> displayFor(String ref) {
        return Optional.ofNullable(displaysSent.get(ref));
    }

    /** Records a code as spent. Spent is permanent; §11's single-use property. */
    public void redeemed(String code) {
        redeemedCodes.add(code);
    }

    /** Records that a mutation happened which core must have audited. */
    public void mutated(String action) {
        expectedAuditActions.add(action);
    }

    private String newGroup(String ref) {
        members.put(ref, new LinkedHashSet<>(List.of(ref)));
        return ref;
    }

    /** Every identity the model believes shares a subject with this one, itself included. */
    public Set<String> groupContaining(String ref) {
        String group = groupOf.get(ref);
        return group == null
                ? Set.of()
                : Collections.unmodifiableSet(members.getOrDefault(group, Set.of()));
    }

    /** Every identity the model has ever linked. */
    public Set<String> knownIdentities() {
        return Collections.unmodifiableSet(groupOf.keySet());
    }

    /** Whether the model saw this code redeemed. */
    public boolean isRedeemed(String code) {
        return redeemedCodes.contains(code);
    }

    /** Codes the model saw redeemed. */
    public Set<String> redeemedCodes() {
        return Collections.unmodifiableSet(redeemedCodes);
    }

    /** Actions core must have written an audit row for, in the order they happened. */
    public List<String> expectedAuditActions() {
        return Collections.unmodifiableList(expectedAuditActions);
    }

    /** The group a ref belongs to, for messages. Empty when the model has never seen it. */
    public Optional<String> groupIdFor(String ref) {
        return Optional.ofNullable(groupOf.get(ref));
    }
}
