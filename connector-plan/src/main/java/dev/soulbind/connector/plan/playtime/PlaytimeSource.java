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

package dev.soulbind.connector.plan.playtime;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * How much a player has played, over a window.
 *
 * <p><b>Everything returns {@link Optional}, and that is the whole design of
 * this interface.</b> A source that answered {@code 0} when it could not read
 * would be indistinguishable from one reporting a player who did not play — and
 * the consequence is not a missing grant but a mass revocation: every holder
 * dropping to zero at once, on the sweep after somebody renamed a column.
 *
 * <p>Empty means "I could not answer", never "the answer is none". A caller must
 * treat it as a reason to do nothing, which is the same shape the role effector
 * already uses for an unaskable core.
 */
public interface PlaytimeSource {

    /**
     * Active playtime for one player since an instant, idle time excluded.
     *
     * @return the duration, or empty if it could not be determined
     */
    Optional<Duration> activeSince(UUID player, Instant since);

    /**
     * Everyone with any recorded session ending since an instant.
     *
     * <p>Half of the population a sweep must cover. The other half — everyone
     * last reported with a non-zero value — is the caller's, because only the
     * caller knows what it said last time.
     *
     * @return the players, or empty if they could not be determined
     */
    Optional<Set<UUID>> playersActiveSince(Instant since);
}
