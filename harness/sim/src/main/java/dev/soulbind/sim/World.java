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
import java.util.Set;

/**
 * What the generator needs to know to pick an applicable action.
 *
 * <p>Distinct from {@link ShadowModel}, and the distinction matters. The model
 * is the ORACLE — a partial second copy of what must be true, which the
 * invariants diff against core. This is the generator's <em>scratchpad</em>:
 * which codes are outstanding, who has rotated a credential, which identities
 * are unlinked. It exists so the generator can avoid proposing an action that
 * is not currently possible.
 *
 * <p>Keeping them apart stops a whole family of quiet failure. If the generator
 * read the oracle, an error in the oracle would steer the generator away from
 * exactly the actions that would have exposed it — the run would get quieter as
 * the model got wronger, which is the worst possible direction.
 */
public final class World {

    private final List<Actor> actors = new ArrayList<>();
    private final Map<String, String> outstandingCodes = new LinkedHashMap<>();
    private final Set<String> linkedRefs = new LinkedHashSet<>();
    private final Set<String> rotatedActors = new LinkedHashSet<>();
    private final List<String> gates = new ArrayList<>();

    public World(List<Actor> actors, List<String> gates) {
        this.actors.addAll(actors);
        this.gates.addAll(gates);
    }

    public List<Actor> actors() {
        return Collections.unmodifiableList(actors);
    }

    public List<String> gates() {
        return Collections.unmodifiableList(gates);
    }

    /** Codes issued and not yet redeemed, mapped to the identity they were issued for. */
    public Map<String, String> outstandingCodes() {
        return Collections.unmodifiableMap(outstandingCodes);
    }

    public Set<String> linkedRefs() {
        return Collections.unmodifiableSet(linkedRefs);
    }

    /** Identities belonging to any actor that are not linked to anything yet. */
    public List<String> unlinkedRefs() {
        List<String> unlinked = new ArrayList<>();
        for (Actor actor : actors) {
            for (String ref : actor.identities()) {
                if (!linkedRefs.contains(ref)) {
                    unlinked.add(ref);
                }
            }
        }
        return unlinked;
    }

    /** Actors that have rotated at least once, so a stale credential exists to misuse. */
    public List<Actor> actorsWithAStaleCredential() {
        List<Actor> stale = new ArrayList<>();
        for (Actor actor : actors) {
            if (rotatedActors.contains(actor.name())) {
                stale.add(actor);
            }
        }
        return stale;
    }

    public void codeIssued(String code, String forRef) {
        outstandingCodes.put(code, forRef);
    }

    public void codeSpent(String code) {
        outstandingCodes.remove(code);
    }

    public void linked(String leftRef, String rightRef) {
        linkedRefs.add(leftRef);
        linkedRefs.add(rightRef);
    }

    public void rotated(Actor actor) {
        rotatedActors.add(actor.name());
        actors.replaceAll(a -> a.name().equals(actor.name()) ? a.rotated() : a);
    }
}
