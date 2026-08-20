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

import java.util.List;

/**
 * One simulated person, who owns an account on each platform.
 *
 * <p><b>Spanning platforms is the point.</b> §11 asks for actors where "the
 * same simulated person owns a mineflayer player, a scripted chat account, and
 * a forum account, because the defects worth finding live in the cross-platform
 * graph". An actor confined to one platform would exercise one connector, which
 * every other tier already does better.
 *
 * @param name a stable handle, used in messages
 * @param identities this person's account on each platform, as {@code kind:id}
 * @param credentialGeneration bumped when the actor's credential is rotated;
 *     a nemesis action holds on to an older one
 */
public record Actor(String name, List<String> identities, int credentialGeneration) {

    public Actor {
        identities = List.copyOf(identities);
    }

    /** The same actor, one credential generation later. */
    public Actor rotated() {
        return new Actor(name, identities, credentialGeneration + 1);
    }

    /** This actor's identity on a given platform kind, or null when they have none. */
    public String identityOn(String platformKind) {
        String prefix = platformKind + ":";
        for (String ref : identities) {
            if (ref.startsWith(prefix)) {
                return ref;
            }
        }
        return null;
    }
}
